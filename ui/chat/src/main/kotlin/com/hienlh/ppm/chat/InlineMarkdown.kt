package com.hienlh.ppm.chat

/**
 * One paragraph's inline markdown: its text, and the ranges that are emphasis, code, links or
 * struck through. The web parses chat text with remark (CommonMark, GFM with `singleTilde:
 * false`, remark-breaks); this follows CommonMark's inline algorithm — delimiter runs and their
 * flanking, the rule of three, a link closing on the nearest bracket — for what chat text uses.
 * Raw HTML stays text (the web renders it through rehype-raw), and an image is an [IMAGE_SLOT] in
 * the text with its source and alt text in [Parsed.images], for the paragraph to draw as a block.
 * Android has no system markdown parser, so this stands in for the Apple app's
 * `AttributedString(markdown:)`.
 */
object InlineMarkdown {
    enum class Kind { STRONG, EMPHASIS, CODE, STRIKE, LINK }

    /** [url] is set for a link only. */
    data class Span(val start: Int, val end: Int, val kind: Kind, val url: String? = null)

    /** `![alt](url)`: [alt] is the plain text of what the brackets hold, as remark gives `alt`. */
    data class Image(val url: String, val alt: String)

    /** [images] in the order of their [IMAGE_SLOT]s in [text]. */
    data class Parsed(val text: String, val spans: List<Span>, val images: List<Image> = emptyList())

    fun parse(source: String): Parsed {
        val out = StringBuilder()
        val spans = mutableListOf<Span>()
        val images = mutableListOf<Image>()
        render(Scanner(source).scan(), out, spans, images)
        val text = out.toString()
        autolink(text, spans)
        return Parsed(text, spans.sortedWith(compareBy<Span> { it.start }.thenByDescending { it.end }.thenBy { it.kind }), images)
    }

    private fun render(nodes: List<Node>, out: StringBuilder, spans: MutableList<Span>, images: MutableList<Image>) {
        for (node in nodes) {
            when (node) {
                is Node.Text -> out.append(node.text)
                is Node.Delimiter -> repeat(node.count) { out.append(node.char) }
                is Node.Bracket -> out.append(if (node.image) "![" else "[")
                is Node.Code -> {
                    val start = out.length
                    out.append(node.text)
                    if (out.length > start) spans += Span(start, out.length, Kind.CODE)
                }
                // The web's `<img>` is `display: block`: one slot here, where the paragraph is cut.
                is Node.Inline -> if (node.kind == null) {
                    out.append(IMAGE_SLOT)
                    images += Image(node.url.orEmpty(), plain(node.children))
                } else {
                    val start = out.length
                    render(node.children, out, spans, images)
                    if (out.length > start) spans += Span(start, out.length, node.kind, node.url)
                }
            }
        }
    }

    private fun plain(nodes: List<Node>): String =
        StringBuilder().also { render(nodes, it, mutableListOf(), mutableListOf()) }.toString().replace(IMAGE_SLOT.toString(), "")

    private val bareUrl = Regex("https?://[^\\s<>\"'`\uE001]+")

    /** GFM's bare-URL links, minus the sentence's punctuation; never inside code or a link. */
    private fun autolink(text: String, spans: MutableList<Span>) {
        if (!text.contains("://")) return
        val taken = spans.filter { it.kind == Kind.LINK || it.kind == Kind.CODE }
        for (match in bareUrl.findAll(text)) {
            var url = match.value
            // Trailing punctuation ends the sentence, not the address; so does an unmatched ")".
            while (url.isNotEmpty()) {
                val last = url.last()
                val unmatched = last == ')' && url.count { it == '(' } < url.count { it == ')' }
                if (last in ".,;:!?" || unmatched) url = url.dropLast(1) else break
            }
            val start = match.range.first
            val end = start + url.length
            if (taken.any { it.start < end && start < it.end }) continue
            spans += Span(start, end, Kind.LINK, url)
        }
    }

    private sealed interface Node {
        class Text(val text: String) : Node
        class Code(val text: String) : Node

        /** A run of `*`, `_` or `~~`; what is left of [count] after matching is drawn as itself. */
        class Delimiter(val char: Char, var count: Int, val canOpen: Boolean, val canClose: Boolean) : Node {
            val length = count
            var active = true
        }

        class Bracket(val image: Boolean) : Node {
            var active = true
        }

        /** [kind] null: an image. */
        class Inline(val kind: Kind?, val url: String?, val children: List<Node>) : Node
    }

    private class Scanner(private val source: String) {
        private val nodes = mutableListOf<Node>()
        private val pending = StringBuilder()
        private var i = 0

        fun scan(): List<Node> {
            while (i < source.length) {
                when (val c = source[i]) {
                    '\\' -> escape()
                    '`' -> codeSpan()
                    '*', '_', '~' -> delimiterRun(c)
                    '[' -> bracket(image = false, width = 1)
                    '!' -> if (source.getOrNull(i + 1) == '[') bracket(image = true, width = 2) else literal(c)
                    ']' -> closeBracket()
                    '<' -> angleAutolink()
                    '&' -> entity()
                    else -> literal(c)
                }
            }
            flush()
            processEmphasis(nodes, 0)
            return nodes
        }

        private fun literal(c: Char) {
            pending.append(c)
            i++
        }

        private fun flush() {
            if (pending.isEmpty()) return
            nodes += Node.Text(pending.toString())
            pending.clear()
        }

        private fun escape() {
            val next = source.getOrNull(i + 1)
            when {
                next != null && next in ASCII_PUNCTUATION -> {
                    pending.append(next)
                    i += 2
                }
                // A hard line break; the break itself is kept.
                next == '\n' -> {
                    pending.append('\n')
                    i += 2
                }
                else -> literal('\\')
            }
        }

        private fun runLength(from: Int, c: Char): Int {
            var end = from
            while (end < source.length && source[end] == c) end++
            return end - from
        }

        /** A code span closes on the next run of exactly as many backticks; else they are text. */
        private fun codeSpan() {
            val run = runLength(i, '`')
            var j = i + run
            while (j < source.length) {
                if (source[j] != '`') {
                    j++
                    continue
                }
                val other = runLength(j, '`')
                if (other == run) {
                    flush()
                    val content = source.substring(i + run, j).replace('\n', ' ')
                    val strip = content.length >= 2 && content.first() == ' ' && content.last() == ' ' && content.any { it != ' ' }
                    nodes += Node.Code(if (strip) content.substring(1, content.length - 1) else content)
                    i = j + run
                    return
                }
                j += other
            }
            pending.append(source, i, i + run)
            i += run
        }

        private fun delimiterRun(c: Char) {
            val run = runLength(i, c)
            // Strikethrough is exactly two tildes (`singleTilde: false`); any other run is text.
            if (c == '~' && run != 2) {
                pending.append(source, i, i + run)
                i += run
                return
            }
            // The start and end of the text count as whitespace.
            val before = if (i == 0) ' '.code else source.codePointBefore(i)
            val after = if (i + run >= source.length) ' '.code else source.codePointAt(i + run)
            val left = !isWhitespace(after) && (!isPunctuation(after) || isWhitespace(before) || isPunctuation(before))
            val right = !isWhitespace(before) && (!isPunctuation(before) || isWhitespace(after) || isPunctuation(after))
            // `_` may not open or close inside a word.
            val canOpen = if (c == '_') left && (!right || isPunctuation(before)) else left
            val canClose = if (c == '_') right && (!left || isPunctuation(after)) else right
            flush()
            nodes += Node.Delimiter(c, run, canOpen, canClose)
            i += run
        }

        private fun bracket(image: Boolean, width: Int) {
            flush()
            nodes += Node.Bracket(image)
            i += width
        }

        private fun closeBracket() {
            flush()
            val index = nodes.indexOfLast { it is Node.Bracket }
            if (index < 0) return literal(']')
            val opener = nodes[index] as Node.Bracket
            val link = if (opener.active) inlineLink(i + 1) else null
            if (link == null) {
                // No link here: this opener is text, and an earlier one may still close.
                nodes[index] = Node.Text(if (opener.image) "![" else "[")
                return literal(']')
            }
            processEmphasis(nodes, index + 1)
            val children = nodes.subList(index + 1, nodes.size).toList()
            while (nodes.size > index) nodes.removeAt(nodes.lastIndex)
            nodes += Node.Inline(if (opener.image) null else Kind.LINK, link.first, children)
            // A link cannot hold another link.
            if (!opener.image) for (node in nodes) if (node is Node.Bracket && !node.image) node.active = false
            i = link.second
        }

        /** `(destination "title")` starting at [start]: the destination and the index after `)`. */
        private fun inlineLink(start: Int): Pair<String, Int>? {
            if (source.getOrNull(start) != '(') return null
            var j = skipSpace(start + 1)
            val destination: String
            if (source.getOrNull(j) == '<') {
                var k = j + 1
                while (k < source.length && source[k] != '>') {
                    if (source[k] == '\n' || source[k] == '<') return null
                    if (source[k] == '\\' && k + 1 < source.length) k++
                    k++
                }
                if (k >= source.length) return null
                destination = source.substring(j + 1, k)
                j = k + 1
            } else {
                var k = j
                var depth = 0
                while (k < source.length) {
                    val ch = source[k]
                    if (ch == '\\' && k + 1 < source.length && source[k + 1] in ASCII_PUNCTUATION) {
                        k += 2
                        continue
                    }
                    if (ch.isWhitespace() || ch.code < 0x20) break
                    if (ch == '(') depth++
                    if (ch == ')') {
                        if (depth == 0) break
                        depth--
                    }
                    k++
                }
                if (depth != 0) return null
                destination = source.substring(j, k)
                j = k
            }
            val afterDestination = j
            j = skipSpace(j)
            // A title needs whitespace before it; it is not shown.
            if (j > afterDestination && j < source.length && source[j] in "\"'(") {
                val close = if (source[j] == '(') ')' else source[j]
                var k = j + 1
                while (k < source.length && source[k] != close) {
                    if (source[k] == '\\' && k + 1 < source.length) k++
                    k++
                }
                if (k >= source.length) return null
                j = skipSpace(k + 1)
            }
            if (source.getOrNull(j) != ')') return null
            return unescape(destination) to j + 1
        }

        private fun skipSpace(from: Int): Int {
            var j = from
            while (j < source.length && (source[j] == ' ' || source[j] == '\t' || source[j] == '\n')) j++
            return j
        }

        /** `<https://…>` or `<name@host>`; anything else is a literal `<` (raw HTML stays text). */
        private fun angleAutolink() {
            val close = source.indexOf('>', i + 1)
            if (close > i) {
                val inner = source.substring(i + 1, close)
                val url = when {
                    URI_AUTOLINK.matches(inner) -> inner
                    EMAIL_AUTOLINK.matches(inner) -> "mailto:$inner"
                    else -> null
                }
                if (url != null) {
                    flush()
                    nodes += Node.Inline(Kind.LINK, url, listOf(Node.Text(inner)))
                    i = close + 1
                    return
                }
            }
            literal('<')
        }

        private fun entity() {
            val match = ENTITY.matchAt(source, i)
            val decoded = match?.let(::decodeEntity)
            if (match == null || decoded == null) return literal('&')
            pending.append(decoded)
            i = match.range.last + 1
        }
    }

    /**
     * Matches [closer] against delimiters back to [bottom], innermost first, wrapping what lies
     * between each pair. Whatever stays unmatched is text.
     */
    private fun processEmphasis(nodes: MutableList<Node>, bottom: Int) {
        var c = bottom
        while (c < nodes.size) {
            val closer = nodes[c]
            if (closer !is Node.Delimiter || !closer.active || !closer.canClose) {
                c++
                continue
            }
            var o = c - 1
            while (o >= bottom) {
                val node = nodes[o]
                if (node is Node.Delimiter && node.active && node.char == closer.char && node.canOpen && pairs(node, closer)) break
                o--
            }
            if (o < bottom) {
                if (!closer.canOpen) closer.active = false
                c++
                continue
            }
            val opener = nodes[o] as Node.Delimiter
            val use = if (closer.char == '~' || (opener.count >= 2 && closer.count >= 2)) 2 else 1
            val kind = if (closer.char == '~') Kind.STRIKE else if (use == 2) Kind.STRONG else Kind.EMPHASIS
            val between = nodes.subList(o + 1, c)
            val children = between.toList()
            between.clear()
            nodes.add(o + 1, Node.Inline(kind, null, children))
            opener.count -= use
            closer.count -= use
            // The opener, the new span, the closer: a closer with delimiters left closes again.
            c = o + 2
            if (opener.count == 0) {
                nodes.removeAt(o)
                c--
            }
            if (closer.count == 0) nodes.removeAt(c)
        }
        for (k in bottom until nodes.size) (nodes[k] as? Node.Delimiter)?.active = false
    }

    /** The rule of three: a run that can both open and close pairs only on a length sum not divisible by 3. */
    private fun pairs(opener: Node.Delimiter, closer: Node.Delimiter): Boolean {
        if (closer.char == '~') return opener.length == closer.length
        if (!opener.canClose && !closer.canOpen) return true
        return (opener.length + closer.length) % 3 != 0 || (opener.length % 3 == 0 && closer.length % 3 == 0)
    }

    private const val ASCII_PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
    private val URI_AUTOLINK = Regex("[A-Za-z][A-Za-z0-9+.\\-]{1,31}:[^\\s<>\\x00-\\x1F]*")
    private val EMAIL_AUTOLINK = Regex(
        "[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*",
    )
    private val ENTITY = Regex("&(?:#[xX]([0-9a-fA-F]{1,6})|#([0-9]{1,7})|([A-Za-z][A-Za-z0-9]{1,31}));")

    /** The named references chat text actually carries; an unknown one stays as typed. */
    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "copy" to "©", "reg" to "®", "trade" to "™", "hellip" to "…", "mdash" to "—", "ndash" to "–",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "laquo" to "«", "raquo" to "»",
        "bull" to "•", "middot" to "·", "times" to "×", "divide" to "÷", "plusmn" to "±", "deg" to "°",
        "larr" to "←", "rarr" to "→", "uarr" to "↑", "darr" to "↓", "harr" to "↔", "rArr" to "⇒",
        "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢", "sect" to "§", "para" to "¶",
    )

    private fun decodeEntity(match: MatchResult): String? {
        val (hex, decimal, name) = match.destructured
        if (name.isNotEmpty()) return NAMED_ENTITIES[name]
        val code = if (hex.isNotEmpty()) hex.toInt(16) else decimal.toInt()
        // NUL and anything that is not a scalar value become U+FFFD, as CommonMark says.
        val valid = code in 1..0x10FFFF && code !in 0xD800..0xDFFF
        return if (valid) String(Character.toChars(code)) else "�"
    }

    /** A link destination's backslash escapes and character references, resolved. */
    private fun unescape(text: String): String {
        if ('\\' !in text && '&' !in text) return text
        val out = StringBuilder()
        var k = 0
        while (k < text.length) {
            val ch = text[k]
            if (ch == '\\' && k + 1 < text.length && text[k + 1] in ASCII_PUNCTUATION) {
                out.append(text[k + 1])
                k += 2
                continue
            }
            val match = if (ch == '&') ENTITY.matchAt(text, k) else null
            val decoded = match?.let(::decodeEntity)
            if (match != null && decoded != null) {
                out.append(decoded)
                k = match.range.last + 1
            } else {
                out.append(ch)
                k++
            }
        }
        return out.toString()
    }

    private fun isWhitespace(codePoint: Int) =
        Character.isWhitespace(codePoint) || Character.getType(codePoint) == Character.SPACE_SEPARATOR.toInt()

    /** CommonMark's punctuation: Unicode's P and S categories. */
    private fun isPunctuation(codePoint: Int) = when (Character.getType(codePoint).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
        Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
        Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
        -> true
        else -> false
    }
}

/** Where an image stands in a paragraph's parsed text: a private-use character, cut out before it is drawn. */
internal const val IMAGE_SLOT = ''
