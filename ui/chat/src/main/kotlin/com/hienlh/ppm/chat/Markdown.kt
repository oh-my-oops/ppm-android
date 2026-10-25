package com.hienlh.ppm.chat

/**
 * A chat message's markdown as the blocks the web draws (react-markdown + remark-gfm, styled by
 * `.markdown-content` in globals.css). Inline syntax stays in the strings; it is read when the
 * block is drawn. Ported from the Apple app's `Markdown.swift`, rule for rule.
 */
sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Heading(val level: Int, val text: String) : MarkdownBlock

    /** [closed] is false while the closing fence has not streamed in yet. */
    data class Code(val language: String?, val text: String, val closed: Boolean) : MarkdownBlock
    /** [loose]: a blank line between items, or between two blocks of one, so each item's text is a paragraph. */
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<MarkdownListItem>, val loose: Boolean = false) : MarkdownBlock
    data class Quote(val blocks: List<MarkdownBlock>) : MarkdownBlock

    /** [aligns]: each column's `left`, `center` or `right` from the delimiter row, null where it sets none. */
    data class Table(val header: List<String>, val rows: List<List<String>>, val aligns: List<String?> = emptyList()) : MarkdownBlock
    data object Rule : MarkdownBlock

    /** An HTML block (CommonMark's seven kinds), its lines as written. */
    data class Html(val text: String) : MarkdownBlock
}

/** [checked] is a GFM task item's box; null for a plain item. */
data class MarkdownListItem(val blocks: List<MarkdownBlock>, val checked: Boolean? = null)

object Markdown {
    fun parse(text: String): List<MarkdownBlock> = blocks(text.replace("\r\n", "\n").split("\n"))

    // Blocks

    internal fun blocks(lines: List<String>, lazy: Set<Int> = emptySet(), interrupting: Boolean = false, final: Boolean = true): List<MarkdownBlock> =
        parseBlocks(lines, lazy, interrupting, final).blocks

    /**
     * Where a container's lines end, as micromark exits what is still open inside it. [closing]: the
     * last line is not the text's own but stands for the container line that closes the flow there,
     * so an open HTML block or fence keeps the line ending before it. [inQuote]: a block quote holds
     * the lines, so each of them starts with a `>`. [pinned]: the exits land right after a `>` on the
     * line that closes them, where micromark's subtokenize cannot move them back past the line
     * endings before them.
     */
    private data class End(val closing: Boolean = false, val inQuote: Boolean = false, val pinned: Boolean = false)

    /** What [parseBlocks] reads, [gap] being a blank line between two blocks (what makes a list item loose). */
    private class Parsed(val blocks: List<MarkdownBlock>, val gap: Boolean, val tail: Tail)

    /**
     * How the lines end. [open]: in a paragraph, however deep in quotes and lists, which a lazy line
     * may continue. [eols]: the line endings after the last block's text — its own last line's and
     * each blank line's after it (the message's last line has none). [list]: the last block is a
     * list, which took the blank lines into its last item; [deepEols] are then that item's own,
     * however deep the lists go. [table]: the last thing read is a table's row, however deep.
     */
    private class Tail(val open: Boolean, val eols: Int, val list: Boolean, val deepEols: Int, val table: Boolean = false)

    /**
     * The blocks of [lines]. [lazy] are the lines a container took in lazily, which no table may use
     * and on which any HTML block may interrupt a paragraph. [interrupting]: the first line opens its
     * containers while micromark still holds a construct open around them (its `self.interrupt`), so
     * no empty item and no ordered item but `1.` starts there. [final]: the last line ends the message.
     */
    private fun parseBlocks(
        lines: List<String>,
        lazy: Set<Int> = emptySet(),
        interrupting: Boolean = false,
        final: Boolean = true,
        end: End = End(),
    ): Parsed {
        val out = object : ArrayList<MarkdownBlock>() {
            var blankBefore = false
            var gap = false

            override fun add(element: MarkdownBlock): Boolean {
                if (blankBefore && isNotEmpty()) gap = true
                blankBefore = false
                return super.add(element)
            }
        }
        val paragraph = mutableListOf<String>()
        var i = 0
        // Indented code is still open until a line that is not code: blank lines may yet lead to more.
        var afterCode = false
        // The line a quote or list ended on without a container opening there: micromark still reads
        // it as lazy inside them, where indented code cannot go past its first line.
        var lazyAt = -1
        // Blank lines since the last block, how that block ends when it is a quote or a list, and
        // whether any line held more than whitespace.
        var trailing = 0
        var last: Tail? = null
        var text = false
        // Whether the quote or list that ended on [lazyAt] ended with a table row.
        var lastTable = false

        fun flush() {
            if (paragraph.isEmpty()) return
            out += MarkdownBlock.Paragraph(paragraph.joinToString("\n"))
            paragraph.clear()
        }

        while (i < lines.size) {
            val line = lines[i]
            val indent = indentation(line)
            val trimmed = line.trimSpaces()

            if (trimmed.isEmpty()) {
                flush()
                out.blankBefore = true
                trailing++
                i++
                continue
            }
            trailing = 0
            last = null
            text = true
            // micromark looks for new containers before it knows whether what is open goes on.
            val interrupt = paragraph.isNotEmpty() || afterCode || (i == 0 && interrupting)
            afterCode = false
            if (indent >= 4) {
                if (paragraph.isEmpty()) {
                    // An indented code block: runs on through blank lines that more of it follows,
                    // a whitespace line among them keeping what lies past its four columns.
                    var end = i + 1
                    var j = i + 1
                    while (j < lines.size && i != lazyAt) {
                        if (indentation(lines[j]) >= 4) end = j + 1 else if (lines[j].trimSpaces().isNotEmpty()) break
                        j++
                    }
                    val code = (i until end).map { if (indentation(lines[it]) >= 4) stripColumns(lines[it], 4) else "" }.toMutableList()
                    // mdast drops the one line ending a closing whitespace line leaves.
                    if (code.last().isEmpty()) code.removeAt(code.lastIndex)
                    out += MarkdownBlock.Code(null, code.joinToString("\n"), closed = true)
                    afterCode = i != lazyAt
                    i = end
                } else {
                    // A lazy continuation line of the paragraph.
                    paragraph += trimmed
                    i++
                }
                continue
            }
            val fence = Fence.of(trimmed)
            if (fence != null) {
                flush()
                val code = mutableListOf<String>()
                var j = i + 1
                var closed = false
                while (j < lines.size) {
                    if (indentation(lines[j]) < 4 && fence.closes(lines[j].trimSpaces())) {
                        closed = true
                        break
                    }
                    code += stripColumns(lines[j], indent)
                    j++
                }
                // mdast drops the line ending before the end of an unclosed fence.
                if (!closed && code.lastOrNull() == "") code.removeAt(code.lastIndex)
                out += MarkdownBlock.Code(fence.language, code.joinToString("\n"), closed)
                i = if (closed) j + 1 else j
                continue
            }
            val heading = atxHeading(trimmed)
            if (heading != null) {
                flush()
                out += heading
                i++
                continue
            }
            // A lazy line is never an underline.
            val level = if (paragraph.isNotEmpty() && i !in lazy) setextLevel(trimmed) else null
            if (level != null) {
                out += MarkdownBlock.Heading(level, paragraph.joinToString("\n"))
                paragraph.clear()
                i++
                continue
            }
            if (isRule(trimmed)) {
                flush()
                out += MarkdownBlock.Rule
                i++
                continue
            }
            // A header needs no `|` (the delimiter row does), but cannot be one `|` alone. Right after
            // a table that a container ended with, micromark reads the line lazily as one more row of
            // it, which it refuses there, so no table starts on it either.
            val afterTable = i == lazyAt && lastTable
            val header = if (i + 1 < lines.size && trimmed != "|" && !afterTable) tableCells(trimmed) ?: listOf(trimmed) else null
            val table = header != null && i + 1 !in lazy && isTableSeparator(lines[i + 1], header.size)
            // On a lazy line even a kind 7 block interrupts the paragraph. So does a table, after
            // which micromark reads the line afresh, and HTML comes first there.
            val html = HtmlBlock.of(stripColumns(line, indent), interrupting = paragraph.isNotEmpty() && i !in lazy && !table)
            if (html != null) {
                flush()
                val (text, next) = html.collect(lines, i)
                out += MarkdownBlock.Html(text)
                i = next
                continue
            }
            if (line.trimStart(' ', '\t').startsWith(">")) {
                flush()
                val inner = mutableListOf<String>()
                val lazyInner = mutableSetOf<Int>()
                while (i < lines.size) {
                    val next = lines[i].trimSpaces()
                    val content = lines[i].trimStart(' ', '\t')
                    if (i !in lazy && indentation(lines[i]) < 4 && content.startsWith(">")) {
                        // Past the `>` and one space, the line keeps all it holds, trailing spaces too.
                        var rest = content.substring(1)
                        if (rest.startsWith(" ")) rest = rest.substring(1)
                        inner += rest
                    } else if (
                        next.isNotEmpty() &&
                        if (i in lazy) endsInParagraph(inner, lazyInner, interrupt) else staysLazily(lines, i, final, inner, lazyInner, interrupt)
                    ) {
                        // Kept as written: what it is depends on its indentation, and HTML keeps it.
                        lazyInner += inner.size
                        inner += lines[i]
                    } else {
                        break
                    }
                    i++
                }
                // A container opening on the next line closes the quote, its last line ending included —
                // and so does the one closing the lines around it, when the quote runs up to it.
                val closing = i < lines.size && i !in lazy && opensContainer(lines[i]) || end.closing && i == lines.lastIndex
                if (closing) inner += ""
                val innerEnd = End(
                    closing = closing,
                    inQuote = true,
                    pinned = when {
                        // The message ends on a `>` line: micromark exits everything right after it.
                        i == lines.size -> end.pinned || final && inner.last().trimSpaces().isEmpty()
                        end.closing && i == lines.lastIndex -> end.pinned
                        // A `>` of a quote further out stays on the line that opens the new container.
                        else -> closing && end.inQuote
                    },
                )
                val quote = parseBlocks(inner, lazyInner, interrupt, final && i == lines.size, innerEnd)
                out += MarkdownBlock.Quote(quote.blocks)
                last = quote.tail
                lastTable = quote.tail.table
                lazyAt = if (i < lines.size && lines[i].trimSpaces().isNotEmpty()) i else -1
                continue
            }
            val marker = ListMarker.of(line)
            if (marker != null && (!interrupt || marker.canInterruptParagraph)) {
                flush()
                val read = list(lines, i, marker, interrupt, final, lazy, end)
                out += read.block
                // The blank lines the last item took in still part the list from what follows.
                if (read.blankAfter) out.blankBefore = true
                last = read.tail
                lastTable = read.tail.table
                i = read.next
                lazyAt = if (i < lines.size && lines[i].trimSpaces().isNotEmpty()) i else -1
                continue
            }
            if (table && header != null) {
                flush()
                val delimiters = lines[i + 1].trimSpaces()
                val aligns = (tableCells(delimiters) ?: listOf(delimiters)).map { cell ->
                    when {
                        cell.startsWith(":") && cell.endsWith(":") -> "center"
                        cell.startsWith(":") -> "left"
                        cell.endsWith(":") -> "right"
                        else -> null
                    }
                }
                val rows = mutableListOf<List<String>>()
                i += 2
                // A body row is any line that starts nothing else, with or without a `|`.
                while (i < lines.size && i !in lazy) {
                    val next = lines[i].trimSpaces()
                    if (next.isEmpty() || indentation(lines[i]) >= 4 || startsBlock(lines[i])) break
                    var cells = tableCells(next) ?: listOf(next)
                    if (cells.size < header.size) cells = cells + List(header.size - cells.size) { "" }
                    rows += cells.take(header.size)
                    i++
                }
                out += MarkdownBlock.Table(header, rows, aligns)
                continue
            }
            paragraph += trimmed
            i++
        }
        val open = paragraph.isNotEmpty() || trailing == 0 && last?.open == true
        flush()
        // The message's last line, or the one standing for the container line after these, has no
        // line ending of its own.
        val noEol = final || end.closing
        val blanks = if (noEol && trailing > 0) trailing - 1 else trailing
        val eols = blanks + if (text && (trailing > 0 || !noEol)) 1 else 0
        val table = trailing == 0 && (out.lastOrNull() is MarkdownBlock.Table || last?.table == true)
        val tail = if (out.lastOrNull() is MarkdownBlock.ListBlock && last != null) {
            Tail(open, 0, list = true, deepEols = last.deepEols, table = table)
        } else {
            Tail(open, eols, list = false, deepEols = eols, table = table)
        }
        return Parsed(out, out.gap, tail)
    }

    /** What [list] reads: the list, the line after it, whether blank lines end it, and how its last item ends. */
    private class ListRead(val block: MarkdownBlock, val next: Int, val blankAfter: Boolean, val tail: Tail)

    /**
     * The list starting at line [start]. [interrupting] and [final] as [parseBlocks] has them, the
     * first for the first item's own line only; [outerLazy] are the lines lazy to a container around
     * the list, so to its items as well; [end] is where those lines end.
     */
    private fun list(
        lines: List<String>,
        start: Int,
        first: ListMarker,
        interrupting: Boolean,
        final: Boolean,
        outerLazy: Set<Int>,
        end: End,
    ): ListRead {
        val items = mutableListOf<MarkdownListItem>()
        var loose = false
        var blanks = 0
        var tail: Tail? = null
        var pinned = false
        var i = start
        while (i < lines.size) {
            val marker = (if (i in outerLazy) null else ListMarker.of(lines[i])) ?: break
            if (!marker.continues(first) || marker.indent >= first.contentOffset) break
            // mdast's prepareList: two line endings between an item's last content and the next
            // marker make the list loose. Inside a quote, a `>` on the last blank line keeps the
            // exit of a list nested at the item's end right there, so only one is left after it.
            if (tail != null && (if (end.inQuote) if (tail.list) 0 else tail.eols else tail.deepEols) >= 2) loose = true
            val opening = interrupting && items.isEmpty()
            val markerLine = lines[i]
            val itemLines = mutableListOf(marker.content)
            val lazy = mutableSetOf<Int>()
            var startedBlank = marker.content.isEmpty()
            blanks = 0
            i++
            while (i < lines.size) {
                val line = lines[i]
                if (i !in outerLazy && line.trimSpaces().isEmpty()) {
                    // Blank lines go on with the item (fenced code and HTML read them, whitespace
                    // past the item's indent included); only what follows them says whether the
                    // item goes on too.
                    itemLines += stripColumns(line, marker.contentOffset)
                    blanks++
                    i++
                    continue
                }
                if (i in outerLazy) {
                    if (blanks > 0 || !endsInParagraph(itemLines, lazy, opening)) break
                    lazy += itemLines.size
                    itemLines += line
                } else if (indentation(line) >= marker.contentOffset && !(startedBlank && blanks > 0)) {
                    // An item that opened on a blank line ends at the next blank one.
                    itemLines += stripColumns(line, marker.contentOffset)
                } else if (blanks == 0 && staysLazily(lines, i, final, itemLines, lazy, opening)) {
                    lazy += itemLines.size
                    itemLines += line
                } else {
                    break
                }
                startedBlank = false
                blanks = 0
                i++
            }
            // A container opening on the next line closes the item, its last line ending included.
            val next = if (i < lines.size && i !in outerLazy) lines[i] else null
            val closing = next != null && opensContainer(next)
            if (closing) itemLines += ""
            // micromark exits what an item holds right after its last line when the next item
            // starts there, and on the next line itself when another container opens on it.
            val sibling = next != null && ListMarker.of(next)?.let { it.continues(first) && it.indent < first.contentOffset } == true
            val itemEnd = if (i == lines.size) end else End(closing, end.inQuote, pinned = closing && !sibling && end.inQuote)
            pinned = itemEnd.pinned
            val parsed = parseBlocks(itemLines, lazy, opening, final && i == lines.size, itemEnd)
            var blocks = parsed.blocks
            // A task box opens the item's first paragraph, as remark-gfm reads it there: with text
            // after it, past a space or on the paragraph's next line. The paragraph must start the
            // item, or follow a bare marker on the line right after it, flush with the item's text.
            var checked: Boolean? = null
            val head = (blocks.firstOrNull() as? MarkdownBlock.Paragraph)?.text
            val boxable = itemLines[0].isNotEmpty() || markerLine.trimEnd(' ', '\t') == markerLine &&
                itemLines.size > 1 && 1 !in lazy && itemLines[1].isNotEmpty() && indentation(itemLines[1]) == 0
            if (boxable && head != null && head.length > 3 && head[0] == '[' && head[1] in " \txX" && head[2] == ']' && head[3] in " \t\n") {
                checked = head[1] == 'x' || head[1] == 'X'
                blocks = listOf(MarkdownBlock.Paragraph(head.substring(4))) + blocks.drop(1)
            }
            items += MarkdownListItem(blocks, checked)
            if (parsed.gap) loose = true
            tail = parsed.tail
        }
        // The list's own exit, where the same count is made. A `>` on the line that closes the list
        // pins it after all the blank lines; anywhere else inside a quote, subtokenize moves it back
        // to the last blank line's `>`, which leaves one line ending fewer before it.
        val last = tail!!
        if (end.inQuote && !last.list && (if (pinned) last.eols else last.eols - 1) >= 2) loose = true
        return ListRead(MarkdownBlock.ListBlock(first.ordered, first.number, items, loose), i, blanks > 0, last)
    }

    // Line kinds

    internal class Fence private constructor(private val marker: Char, private val length: Int, val language: String?) {
        fun closes(trimmed: String): Boolean {
            val run = trimmed.takeWhile { it == marker }.length
            return run >= length && trimmed.substring(run).all { it.isWhitespace() }
        }

        companion object {
            fun of(trimmed: String): Fence? {
                val first = trimmed.firstOrNull() ?: return null
                if (first != '`' && first != '~') return null
                val run = trimmed.takeWhile { it == first }.length
                if (run < 3) return null
                val info = trimmed.substring(run).trimSpaces()
                if (first == '`' && info.contains('`')) return null
                return Fence(first, run, info.split(' ').firstOrNull { it.isNotEmpty() })
            }
        }
    }

    internal class ListMarker private constructor(
        val ordered: Boolean,
        /** The bullet (`-`, `*`, `+`) or the delimiter after the number (`.`, `)`). */
        private val char: Char,
        val number: Int,
        /** Columns before the marker. */
        val indent: Int,
        /** The column the item's content starts at; continuation lines are indented this far. */
        val contentOffset: Int,
        val content: String,
        val canInterruptParagraph: Boolean,
    ) {
        /** Whether this item belongs to [first]'s list: the same bullet, or the same delimiter after a number. */
        fun continues(first: ListMarker) = ordered == first.ordered && char == first.char

        companion object {
            fun of(line: String): ListMarker? {
                val indent = indentation(line)
                if (indent >= 4) return null
                val rest = stripColumns(line, indent)
                val ordered: Boolean
                val char: Char
                val number: Int
                val markerWidth: Int
                val first = rest.firstOrNull()
                // `- - -` and `* * *` are thematic breaks before they are items.
                if (first != null && first in "-*" && isRule(rest.trimSpaces())) return null
                if (first != null && first in "-*+") {
                    ordered = false
                    char = first
                    number = 1
                    markerWidth = 1
                } else {
                    val digits = rest.takeWhile { it in '0'..'9' }
                    if (digits.length !in 1..9) return null
                    val delimiter = rest.getOrNull(digits.length)
                    if (delimiter != '.' && delimiter != ')') return null
                    ordered = true
                    char = delimiter
                    number = digits.toIntOrNull() ?: 1
                    markerWidth = digits.length + 1
                }
                val afterMarker = rest.substring(markerWidth)
                if (afterMarker.isNotEmpty() && afterMarker[0] != ' ' && afterMarker[0] != '\t') return null
                val spaces = indentation(afterMarker)
                val body = afterMarker.trimSpaces()
                // Five or more spaces start indented code inside the item; one of them is the gap.
                val gap = if (body.isEmpty() || spaces > 4) 1 else spaces
                return ListMarker(
                    ordered = ordered,
                    char = char,
                    number = number,
                    indent = indent,
                    contentOffset = indent + markerWidth + gap,
                    // What follows the marker and its gap — never the marker itself, or the item
                    // would parse as the same list again, forever.
                    content = if (body.isEmpty()) "" else stripColumns(afterMarker, gap),
                    // micromark takes a single digit only: `01.` cannot interrupt either.
                    canInterruptParagraph = body.isNotEmpty() && (!ordered || markerWidth == 2 && number == 1),
                )
            }
        }
    }

    /**
     * The start of an HTML block, as micromark's `htmlFlow` reads it: kinds 1–5 run to the line
     * holding their end marker, 6 and 7 to a blank line, and 7 (any complete tag alone on its line)
     * cannot interrupt a paragraph.
     */
    internal class HtmlBlock private constructor(val kind: Int, private val searchFrom: Int) {
        /** The block's text from line [start], and the line after it. */
        fun collect(lines: List<String>, start: Int): Pair<String, Int> {
            val out = mutableListOf<String>()
            var i = start
            while (i < lines.size) {
                val line = lines[i]
                if (kind >= 6) {
                    if (i > start && line.trimSpaces().isEmpty()) break
                    out += line
                    i++
                    continue
                }
                out += line
                i++
                // The end marker may share the opening's characters (`<!-->`), so the first line is
                // searched from inside the opening.
                val from = if (i - 1 == start) indentation(line).let { stripColumns(line, it) }.let { line.length - it.length } + searchFrom else 0
                if (ends(line, from)) break
            }
            return out.joinToString("\n") to i
        }

        private fun ends(line: String, from: Int): Boolean = when (kind) {
            1 -> RAW_END.containsMatchIn(line.substring(minOf(from, line.length)))
            2 -> line.indexOf("-->", from) >= 0
            3 -> line.indexOf("?>", from) >= 0
            4 -> line.indexOf('>', from) >= 0
            else -> line.indexOf("]]>", from) >= 0
        }

        companion object {
            private val RAW = setOf("pre", "script", "style", "textarea")
            private val RAW_END = Regex("</(?:pre|script|style|textarea)>", RegexOption.IGNORE_CASE)
            private val BLOCK = setOf(
                "address", "article", "aside", "base", "basefont", "blockquote", "body", "caption", "center", "col",
                "colgroup", "dd", "details", "dialog", "dir", "div", "dl", "dt", "fieldset", "figcaption", "figure",
                "footer", "form", "frame", "frameset", "h1", "h2", "h3", "h4", "h5", "h6", "head", "header", "hr",
                "html", "iframe", "legend", "li", "link", "main", "menu", "menuitem", "nav", "noframes", "ol",
                "optgroup", "option", "p", "param", "search", "section", "summary", "table", "tbody", "td", "tfoot",
                "th", "thead", "title", "tr", "track", "ul",
            )
            private const val NAME = "[A-Za-z][A-Za-z0-9-]*"
            // micromark ends an unquoted value at `/` too.
            private const val ATTRIBUTE = "[ \\t]+[A-Za-z_:][A-Za-z0-9_.:-]*(?:[ \\t]*=[ \\t]*(?:[^ \\t\"'=<>`/]+|'[^']*'|\"[^\"]*\"))?"
            private val COMPLETE_OPEN = Regex("<$NAME(?:$ATTRIBUTE)*[ \\t]*/?>[ \\t]*")
            private val COMPLETE_CLOSE = Regex("</$NAME[ \\t]*>[ \\t]*")

            /** [content]: the line after its indentation. */
            fun of(content: String, interrupting: Boolean): HtmlBlock? {
                if (!content.startsWith("<")) return null
                when {
                    content.startsWith("<!--") -> return HtmlBlock(2, 2)
                    content.startsWith("<![CDATA[") -> return HtmlBlock(5, 9)
                    content.startsWith("<!") && content.getOrNull(2)?.isAsciiLetter() == true -> return HtmlBlock(4, 2)
                    content.startsWith("<?") -> return HtmlBlock(3, 1)
                }
                val closing = content.startsWith("</")
                val nameStart = if (closing) 2 else 1
                var end = nameStart
                while (end < content.length && (content[end].isAsciiLetter() || content[end].isDigit() && content[end] < '\u0080' || content[end] == '-')) end++
                if (end == nameStart || !content[nameStart].isAsciiLetter()) return null
                val after = content.getOrNull(end)
                if (after != null && after != ' ' && after != '\t' && after != '/' && after != '>') return null
                val name = content.substring(nameStart, end).lowercase()
                if (after != '/' && !closing && name in RAW) return HtmlBlock(1, end)
                if (name in BLOCK) {
                    if (after == '/' && content.getOrNull(end + 1) != '>') return null
                    return HtmlBlock(6, 0)
                }
                if (interrupting) return null
                val complete = if (closing) COMPLETE_CLOSE else COMPLETE_OPEN
                return if (complete.matches(content)) HtmlBlock(7, 0) else null
            }

            private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
        }
    }

    private val closingHashes = Regex("\\s+#+$")

    internal fun atxHeading(trimmed: String): MarkdownBlock.Heading? {
        val hashes = trimmed.takeWhile { it == '#' }.length
        if (hashes !in 1..6) return null
        val rest = trimmed.substring(hashes)
        if (rest.isNotEmpty() && rest[0] != ' ' && rest[0] != '\t') return null
        var text = rest.trimSpaces()
        // An optional closing run of #s.
        val closing = closingHashes.find(text)
        if (closing != null) {
            text = text.substring(0, closing.range.first)
        } else if (text.all { it == '#' }) {
            text = ""
        }
        return MarkdownBlock.Heading(hashes, text)
    }

    internal fun setextLevel(trimmed: String): Int? = when {
        trimmed.all { it == '=' } -> 1
        trimmed.all { it == '-' } -> 2
        else -> null
    }

    internal fun isRule(trimmed: String): Boolean {
        val compact = trimmed.replace(" ", "").replace("\t", "")
        return compact.length >= 3 && (compact.all { it == '-' } || compact.all { it == '*' } || compact.all { it == '_' })
    }

    /**
     * Whether a line starts a block where nothing open could take it in — on a line lazy to its
     * containers, or after a table row: there any list item and any HTML block may start.
     */
    internal fun startsBlock(line: String): Boolean {
        if (indentation(line) >= 4) return false
        val trimmed = line.trimSpaces()
        return Fence.of(trimmed) != null || atxHeading(trimmed) != null || isRule(trimmed) || trimmed.startsWith(">") ||
            ListMarker.of(line) != null || HtmlBlock.of(trimmed, interrupting = false) != null
    }

    /**
     * Whether line [i] of [lines], lazy to the container holding [inner], stays in it: as more of the
     * paragraph [inner] ends in, or as an HTML block of kind 7. micromark knows whether that one
     * interrupts the paragraph only once it reads the next line, and by then it has kept the
     * containers open around it — unless the message ends there ([final]).
     */
    private fun staysLazily(lines: List<String>, i: Int, final: Boolean, inner: List<String>, lazy: Set<Int>, interrupting: Boolean): Boolean {
        if (!endsInParagraph(inner, lazy, interrupting)) return false
        if (!startsBlock(lines[i])) return true
        return (i + 1 < lines.size || !final) && HtmlBlock.of(lines[i].trimSpaces(), interrupting = false)?.kind == 7
    }

    /** Whether [line] opens a container: a list item or a block quote. */
    private fun opensContainer(line: String): Boolean =
        ListMarker.of(line) != null || indentation(line) < 4 && line.trimSpaces().startsWith(">")

    /** Whether [lines] end inside a paragraph, however deep in quotes and lists, which a lazy line may continue. */
    private fun endsInParagraph(lines: List<String>, lazy: Set<Int>, interrupting: Boolean): Boolean =
        parseBlocks(lines, lazy, interrupting, final = false).tail.open

    /** A GFM table row's cells, or null for a line with no `|`. */
    internal fun tableCells(trimmed: String): List<String>? {
        if (!trimmed.contains('|')) return null
        var row = trimmed
        if (row.startsWith("|")) row = row.substring(1)
        if (row.endsWith("|") && !row.endsWith("\\|")) row = row.dropLast(1)
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var escaped = false
        for (ch in row) {
            if (escaped) {
                if (ch == '|') cell.append('|') else cell.append('\\').append(ch)
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else if (ch == '|') {
                cells += cell.toString().trimSpaces()
                cell.clear()
            } else {
                cell.append(ch)
            }
        }
        if (escaped) cell.append('\\')
        cells += cell.toString().trimSpaces()
        return cells
    }

    private val separatorCell = Regex("^:?-+:?$")

    internal fun isTableSeparator(line: String, columns: Int): Boolean {
        if (indentation(line) >= 4) return false
        val trimmed = line.trimSpaces()
        // With neither `|` nor `:` it is a thematic break or a setext underline instead.
        if ('|' !in trimmed && ':' !in trimmed) return false
        // `- …` opens a list item before the table may read the line.
        if (ListMarker.of(line)?.canInterruptParagraph == true) return false
        val cells = tableCells(trimmed) ?: listOf(trimmed)
        return cells.size == columns && cells.all { separatorCell.matches(it) }
    }

    // Columns

    /** Leading whitespace in columns, tabs stopping every 4. */
    internal fun indentation(line: String): Int {
        var column = 0
        for (ch in line) {
            when (ch) {
                ' ' -> column++
                '\t' -> column += 4 - column % 4
                else -> break
            }
        }
        return column
    }

    /**
     * The line with [columns] columns of leading whitespace removed; a tab only partly removed
     * leaves the rest of its width as spaces.
     */
    internal fun stripColumns(line: String, columns: Int): String {
        var column = 0
        var index = 0
        while (index < line.length && column < columns) {
            val ch = line[index]
            if (ch == ' ') {
                column++
            } else if (ch == '\t') {
                val next = column + 4 - column % 4
                if (next > columns) return " ".repeat(next - columns) + line.substring(index + 1)
                column = next
            } else {
                break
            }
            index++
        }
        return line.substring(index)
    }
}

/** Spaces and tabs off both ends, as Foundation's `.whitespaces` trims: never a line break. */
internal fun String.trimSpaces(): String = trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }
