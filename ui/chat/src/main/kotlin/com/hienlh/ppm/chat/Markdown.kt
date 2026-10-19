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
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<MarkdownListItem>) : MarkdownBlock
    data class Quote(val blocks: List<MarkdownBlock>) : MarkdownBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MarkdownBlock
    data object Rule : MarkdownBlock
}

/** [checked] is a GFM task item's box; null for a plain item. */
data class MarkdownListItem(val blocks: List<MarkdownBlock>, val checked: Boolean? = null)

object Markdown {
    fun parse(text: String): List<MarkdownBlock> = blocks(text.replace("\r\n", "\n").split("\n"))

    // Blocks

    internal fun blocks(lines: List<String>): List<MarkdownBlock> {
        val out = mutableListOf<MarkdownBlock>()
        val paragraph = mutableListOf<String>()
        var i = 0

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
                i++
                continue
            }
            if (indent >= 4) {
                if (paragraph.isEmpty()) {
                    // An indented code block: runs on through blank lines that more of it follows.
                    val code = mutableListOf<String>()
                    while (i < lines.size) {
                        val next = lines[i]
                        if (next.trimSpaces().isEmpty()) {
                            code += ""
                        } else if (indentation(next) >= 4) {
                            code += stripColumns(next, 4)
                        } else {
                            break
                        }
                        i++
                    }
                    while (code.lastOrNull() == "") code.removeAt(code.lastIndex)
                    out += MarkdownBlock.Code(null, code.joinToString("\n"), closed = true)
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
            val level = if (paragraph.isNotEmpty()) setextLevel(trimmed) else null
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
            if (trimmed.startsWith(">")) {
                flush()
                val inner = mutableListOf<String>()
                while (i < lines.size) {
                    val next = lines[i].trimSpaces()
                    if (next.startsWith(">")) {
                        var rest = next.substring(1)
                        if (rest.startsWith(" ")) rest = rest.substring(1)
                        inner += rest
                    } else if (next.isNotEmpty() && inner.lastOrNull()?.isNotEmpty() == true && !startsBlock(lines[i])) {
                        inner += next
                    } else {
                        break
                    }
                    i++
                }
                out += MarkdownBlock.Quote(blocks(inner))
                continue
            }
            val marker = ListMarker.of(line)
            if (marker != null && (paragraph.isEmpty() || marker.canInterruptParagraph)) {
                flush()
                val (list, next) = list(lines, i, marker)
                out += list
                i = next
                continue
            }
            val header = if (i + 1 < lines.size) tableCells(trimmed) else null
            if (header != null && isTableSeparator(lines[i + 1], header.size)) {
                flush()
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < lines.size) {
                    val next = lines[i].trimSpaces()
                    if (next.isEmpty() || !next.contains("|") || startsBlock(lines[i])) break
                    var cells = tableCells(next) ?: break
                    if (cells.size < header.size) cells = cells + List(header.size - cells.size) { "" }
                    rows += cells.take(header.size)
                    i++
                }
                out += MarkdownBlock.Table(header, rows)
                continue
            }
            paragraph += trimmed
            i++
        }
        flush()
        return out
    }

    /** The list starting at line [start], and the line after it. */
    private fun list(lines: List<String>, start: Int, first: ListMarker): Pair<MarkdownBlock, Int> {
        val items = mutableListOf<MarkdownListItem>()
        var i = start
        while (i < lines.size) {
            val marker = ListMarker.of(lines[i]) ?: break
            if (marker.ordered != first.ordered || marker.indent >= first.contentOffset) break
            val itemLines = mutableListOf(marker.content)
            i++
            while (i < lines.size) {
                val line = lines[i]
                if (line.trimSpaces().isEmpty()) {
                    // A blank line belongs to the item only if more of the item follows it.
                    var k = i + 1
                    while (k < lines.size && lines[k].trimSpaces().isEmpty()) k++
                    if (k >= lines.size || indentation(lines[k]) < marker.contentOffset) break
                    repeat(k - i) { itemLines += "" }
                    i = k
                    continue
                }
                if (indentation(line) >= marker.contentOffset) {
                    itemLines += stripColumns(line, marker.contentOffset)
                } else if (ListMarker.of(line) == null && !startsBlock(line) && itemLines.last().isNotEmpty()) {
                    // A lazy continuation of the item's last paragraph.
                    itemLines += line.trimSpaces()
                } else {
                    break
                }
                i++
            }
            var checked: Boolean? = null
            val head = itemLines.first()
            for ((prefix, value) in listOf("[ ] " to false, "[x] " to true, "[X] " to true)) {
                if (head.startsWith(prefix)) {
                    checked = value
                    itemLines[0] = head.substring(prefix.length)
                }
            }
            items += MarkdownListItem(blocks(itemLines), checked)
            // Blank lines between items keep the list going.
            var k = i
            while (k < lines.size && lines[k].trimSpaces().isEmpty()) k++
            if (k > i && k < lines.size) {
                val next = ListMarker.of(lines[k])
                if (next != null && next.ordered == first.ordered && next.indent < first.contentOffset) i = k
            }
        }
        return MarkdownBlock.ListBlock(first.ordered, first.number, items) to i
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
        val number: Int,
        /** Columns before the marker. */
        val indent: Int,
        /** The column the item's content starts at; continuation lines are indented this far. */
        val contentOffset: Int,
        val content: String,
        val canInterruptParagraph: Boolean,
    ) {
        companion object {
            fun of(line: String): ListMarker? {
                val indent = indentation(line)
                if (indent >= 4) return null
                val rest = stripColumns(line, indent)
                val ordered: Boolean
                val number: Int
                val markerWidth: Int
                val first = rest.firstOrNull()
                if (first != null && first in "-*+") {
                    ordered = false
                    number = 1
                    markerWidth = 1
                } else {
                    val digits = rest.takeWhile { it in '0'..'9' }
                    if (digits.length !in 1..9) return null
                    val delimiter = rest.getOrNull(digits.length)
                    if (delimiter != '.' && delimiter != ')') return null
                    ordered = true
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
                    number = number,
                    indent = indent,
                    contentOffset = indent + markerWidth + gap,
                    // What follows the marker and its gap — never the marker itself, or the item
                    // would parse as the same list again, forever.
                    content = if (body.isEmpty()) "" else stripColumns(afterMarker, gap),
                    canInterruptParagraph = body.isNotEmpty() && (!ordered || number == 1),
                )
            }
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

    /** Whether a line starts a block that ends a paragraph or a lazy continuation. */
    internal fun startsBlock(line: String): Boolean {
        if (indentation(line) >= 4) return false
        val trimmed = line.trimSpaces()
        return Fence.of(trimmed) != null || atxHeading(trimmed) != null || isRule(trimmed) || trimmed.startsWith(">") ||
            ListMarker.of(line)?.canInterruptParagraph == true
    }

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
        val cells = tableCells(line.trimSpaces()) ?: return false
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
