package com.hienlh.ppm.chat

import com.hienlh.ppm.protocol.isJsSpace

/**
 * One paragraph's inline markdown: its text, and the ranges that are emphasis, code, links or
 * struck through. The web parses chat text with remark (CommonMark, GFM with `singleTilde:
 * false`, remark-breaks); this follows CommonMark's inline algorithm — delimiter runs and their
 * flanking, the rule of three, a link closing on the nearest bracket — for what chat text uses.
 * Raw HTML is read as micromark reads it and drawn as its text here; [tree] hands it to the HTML
 * path. An image is an [IMAGE_SLOT] in the text with its source and alt text in [Parsed.images],
 * for the paragraph to draw as a block. Bare addresses become links as remark-gfm makes them:
 * micromark's literal autolinks while scanning, then its transform over the text that is left.
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

    /** mdast's phrasing content, once remark-gfm has made bare addresses links. */
    sealed interface Inline {
        data class Text(val text: String) : Inline
        data class Code(val text: String) : Inline

        /** Raw HTML, as written. */
        data class Html(val html: String) : Inline

        /** [kind] is [Kind.STRONG], [Kind.EMPHASIS] or [Kind.STRIKE]. */
        data class Styled(val kind: Kind, val children: List<Inline>) : Inline
        data class Link(val url: String, val title: String?, val children: List<Inline>) : Inline

        /** [alt] is the plain text of what the brackets hold, as remark gives `alt`. */
        data class Image(val url: String, val alt: String, val title: String?) : Inline
    }

    fun parse(source: String): Parsed {
        val out = StringBuilder()
        val spans = mutableListOf<Span>()
        val images = mutableListOf<Image>()
        render(tree(source), out, spans, images)
        return Parsed(out.toString(), spans.sortedWith(compareBy<Span> { it.start }.thenByDescending { it.end }.thenBy { it.kind }), images)
    }

    fun tree(source: String): List<Inline> {
        var nodes = inlines(Scanner(source).scan())
        // remark-gfm's transform: addresses micromark left as text, every web address first.
        nodes = replaceTexts(nodes) { findAll(it, WEB_ADDRESS, ::webAddress) }
        return replaceTexts(nodes) { findAll(it, EMAIL_ADDRESS, ::emailAddress) }
    }

    private fun render(nodes: List<Inline>, out: StringBuilder, spans: MutableList<Span>, images: MutableList<Image>) {
        for (node in nodes) {
            when (node) {
                is Inline.Text -> out.append(node.text)
                is Inline.Html -> out.append(node.html)
                is Inline.Code -> {
                    val start = out.length
                    out.append(node.text)
                    if (out.length > start) spans += Span(start, out.length, Kind.CODE)
                }
                // The web's `<img>` is `display: block`: one slot here, where the paragraph is cut.
                is Inline.Image -> {
                    out.append(IMAGE_SLOT)
                    images += Image(node.url, node.alt)
                }
                is Inline.Styled -> spanOver(node.children, node.kind, null, out, spans, images)
                is Inline.Link -> spanOver(node.children, Kind.LINK, node.url, out, spans, images)
            }
        }
    }

    private fun spanOver(children: List<Inline>, kind: Kind, url: String?, out: StringBuilder, spans: MutableList<Span>, images: MutableList<Image>) {
        val start = out.length
        render(children, out, spans, images)
        if (out.length > start) spans += Span(start, out.length, kind, url)
    }

    /** The scanner's nodes as mdast's: what is left of delimiters and brackets joins the text around it. */
    private fun inlines(nodes: List<Node>): List<Inline> {
        val out = mutableListOf<Inline>()
        val text = StringBuilder()
        fun add(node: Inline) {
            if (text.isNotEmpty()) out += Inline.Text(text.toString())
            text.clear()
            out += node
        }
        for (node in nodes) {
            when (node) {
                is Node.Text -> text.append(node.text)
                is Node.Delimiter -> repeat(node.count) { text.append(node.char) }
                is Node.Bracket -> text.append(if (node.image) "![" else "[")
                is Node.Code -> add(Inline.Code(node.text))
                is Node.Raw -> add(Inline.Html(node.html))
                is Node.Inline -> add(
                    when (node.kind) {
                        null -> Inline.Image(node.url.orEmpty(), plain(inlines(node.children)), node.title)
                        Kind.LINK -> Inline.Link(node.url.orEmpty(), node.title, inlines(node.children))
                        else -> Inline.Styled(node.kind, inlines(node.children))
                    },
                )
            }
        }
        if (text.isNotEmpty()) out += Inline.Text(text.toString())
        return out
    }

    /** mdast-util-to-string: what an image's brackets say, raw HTML and inner images' alt text included. */
    private fun plain(nodes: List<Inline>): String = buildString {
        for (node in nodes) {
            when (node) {
                is Inline.Text -> append(node.text)
                is Inline.Code -> append(node.text)
                is Inline.Html -> append(node.html)
                is Inline.Image -> append(node.alt)
                is Inline.Styled -> append(plain(node.children))
                is Inline.Link -> append(plain(node.children))
            }
        }
    }

    /** Each text outside a link, as [replace] splits it (null: unchanged), as mdast-util-find-and-replace walks. */
    private fun replaceTexts(nodes: List<Inline>, replace: (String) -> List<Inline>?): List<Inline> = nodes.flatMap { node ->
        when (node) {
            is Inline.Text -> replace(node.text) ?: listOf(node)
            is Inline.Styled -> listOf(node.copy(children = replaceTexts(node.children, replace)))
            else -> listOf(node)
        }
    }

    /** Every match of [find] in [value] that [replace] takes (null: not this one, try one character on). */
    private fun findAll(value: String, find: Regex, replace: (MatchResult, String) -> List<Inline>?): List<Inline>? {
        val out = mutableListOf<Inline>()
        var start = 0
        var from = 0
        var changed = false
        while (from <= value.length) {
            val match = find.find(value, from) ?: break
            val position = match.range.first
            val nodes = replace(match, value)
            if (nodes == null) {
                from = position + 1
                continue
            }
            if (start != position) out += Inline.Text(value.substring(start, position))
            out += nodes
            start = position + match.value.length
            from = start
            changed = true
        }
        if (!changed) return null
        if (start < value.length) out += Inline.Text(value.substring(start))
        return out
    }

    private val WEB_ADDRESS = Regex("(https?://|www(?=\\.))([-.\\w]+)([^ \t\r\n]*)", RegexOption.IGNORE_CASE)
    /** JavaScript's `\s`, inside a character class. */
    private const val JS_SPACE = "\\t\\n\\x0B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
    private val EMAIL_ADDRESS = Regex("(?<=^|[$JS_SPACE]|\\p{P}|\\p{S})([-.\\w+]+)@([-\\w]+(?:\\.[-\\w]+)+)")

    /** mdast-util-gfm-autolink-literal's `findUrl`. */
    private fun webAddress(match: MatchResult, input: String): List<Inline>? {
        if (!previousAllows(input, match.range.first, email = false)) return null
        var (protocol, domain) = match.destructured
        var prefix = ""
        if (protocol[0] == 'w' || protocol[0] == 'W') {
            domain = protocol + domain
            protocol = ""
            prefix = "http://"
        }
        if (!isCorrectDomain(domain)) return null
        val (url, trail) = splitUrl(domain + match.groupValues[3])
        if (url.isEmpty()) return null
        val link = Inline.Link(prefix + protocol + url, null, listOf(Inline.Text(protocol + url)))
        return if (trail.isEmpty()) listOf(link) else listOf(link, Inline.Text(trail))
    }

    /** mdast-util-gfm-autolink-literal's `findEmail`. */
    private fun emailAddress(match: MatchResult, input: String): List<Inline>? {
        val (atext, label) = match.destructured
        if (!previousAllows(input, match.range.first, email = true) || label.last() == '-' || label.last() == '_' || label.last() in '0'..'9') return null
        return listOf(Inline.Link("mailto:$atext@$label", null, listOf(Inline.Text("$atext@$label"))))
    }

    /** The character before a match, one UTF-16 unit as JavaScript reads it: none, white space or punctuation. */
    private fun previousAllows(input: String, index: Int, email: Boolean): Boolean {
        if (index == 0) return true
        val before = input[index - 1]
        return (isJsSpace(before) || isUnicodePunctuation(before)) && !(email && before == '/')
    }

    private fun isCorrectDomain(domain: String): Boolean {
        val parts = domain.split('.')
        if (parts.size < 2) return false
        for (part in listOf(parts[parts.size - 1], parts[parts.size - 2])) {
            if (part.isNotEmpty() && ('_' in part || part.none { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' })) return false
        }
        return true
    }

    /** The address without the sentence's punctuation after it, and that punctuation; a `)` the address opened stays. */
    private fun splitUrl(value: String): Pair<String, String> {
        val trailStart = value.indexOfLastNot { it in "!\"&'),.:;<>?]}" } + 1
        if (trailStart == value.length) return value to ""
        var url = value.substring(0, trailStart)
        var trail = value.substring(trailStart)
        var closingParenIndex = trail.indexOf(')')
        val openingParens = url.count { it == '(' }
        var closingParens = url.count { it == ')' }
        while (closingParenIndex != -1 && openingParens > closingParens) {
            url += trail.substring(0, closingParenIndex + 1)
            trail = trail.substring(closingParenIndex + 1)
            closingParenIndex = trail.indexOf(')')
            closingParens++
        }
        return url to trail
    }

    private inline fun String.indexOfLastNot(predicate: (Char) -> Boolean): Int {
        for (index in indices.reversed()) if (!predicate(this[index])) return index
        return -1
    }

    private sealed interface Node {
        class Text(val text: String) : Node
        class Code(val text: String) : Node
        class Raw(val html: String) : Node

        /** A run of `*`, `_` or `~~`; what is left of [count] after matching is drawn as itself. */
        class Delimiter(val char: Char, var count: Int, val canOpen: Boolean, val canClose: Boolean) : Node {
            val length = count
            var active = true
        }

        class Bracket(val image: Boolean) : Node {
            var active = true
        }

        /** [kind] null: an image. */
        class Inline(val kind: Kind?, val url: String?, val children: List<Node>, val title: String? = null) : Node
    }

    private class Scanner(private val source: String) {
        private val nodes = mutableListOf<Node>()
        private val pending = StringBuilder()
        private var i = 0

        fun scan(): List<Node> {
            while (i < source.length) {
                val c = source[i]
                if (isAtext(c) || c == 'h' || c == 'H' || c == 'w' || c == 'W') {
                    if (literalAutolink()) continue
                }
                when (c) {
                    '\\' -> escape()
                    '`' -> codeSpan()
                    '*', '_', '~' -> delimiterRun(c)
                    '[' -> bracket(image = false, width = 1)
                    '!' -> if (source.getOrNull(i + 1) == '[') bracket(image = true, width = 2) else literal(c)
                    ']' -> closeBracket()
                    '<' -> angle()
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
            // micromark looks at one UTF-16 unit either side, so an emoji is neither space nor
            // punctuation; the start and end of the text count as whitespace.
            val previous = source.getOrNull(i - 1)
            val next = source.getOrNull(i + run)
            val before = group(previous)
            val after = group(next)
            val canOpen: Boolean
            val canClose: Boolean
            if (c == '~') {
                canOpen = after == OTHER || (after == PUNCTUATION && before != OTHER)
                canClose = before == OTHER || (before == PUNCTUATION && after != OTHER)
            } else {
                // Beside another run of `*`, `_` or `~` it may open or close whatever else is there.
                val open = after == OTHER || (after == PUNCTUATION && before != OTHER) || (next != null && next in "*_~")
                val close = before == OTHER || (before == PUNCTUATION && after != OTHER) || (previous != null && previous in "*_~")
                // `_` may not open or close inside a word.
                canOpen = if (c == '_') open && (before != OTHER || !close) else open
                canClose = if (c == '_') close && (after != OTHER || !open) else close
            }
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
            nodes += Node.Inline(if (opener.image) null else Kind.LINK, link.url, children, link.title)
            // A link cannot hold another link.
            if (!opener.image) for (node in nodes) if (node is Node.Bracket && !node.image) node.active = false
            i = link.end
        }

        private class Destination(val url: String, val title: String?, val end: Int)

        /** `(destination "title")` starting at [start], and the index after `)`. */
        private fun inlineLink(start: Int): Destination? {
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
            // A title needs whitespace before it.
            var title: String? = null
            if (j > afterDestination && j < source.length && source[j] in "\"'(") {
                val close = if (source[j] == '(') ')' else source[j]
                var k = j + 1
                while (k < source.length && source[k] != close) {
                    if (source[k] == '\\' && k + 1 < source.length) k++
                    k++
                }
                if (k >= source.length) return null
                title = unescape(source.substring(j + 1, k))
                j = skipSpace(k + 1)
            }
            if (source.getOrNull(j) != ')') return null
            return Destination(unescape(destination), title, j + 1)
        }

        private fun skipSpace(from: Int): Int {
            var j = from
            while (j < source.length && (source[j] == ' ' || source[j] == '\t' || source[j] == '\n')) j++
            return j
        }

        /** `<https://…>` or `<name@host>`, else raw HTML, else a literal `<`. */
        private fun angle() {
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
            val end = htmlTextEnd(source, i)
            if (end < 0) return literal('<')
            flush()
            nodes += Node.Raw(source.substring(i, end))
            i = end
        }

        /**
         * micromark's GFM literal autolinks at [i]: an email address, then `http(s)://…` at an `h`
         * or `www.…` at a `w`, each only after a character it allows and never inside brackets
         * that may still become a link's text.
         */
        private fun literalAutolink(): Boolean {
            if (nodes.any { it is Node.Bracket }) return false
            val c = source[i]
            val previous = if (i == 0) null else source[i - 1]
            var url: String? = null
            var end = if (isAtext(c) && (previous == null || previous != '/' && !isAtext(previous))) emailEnd(source, i) else -1
            if (end > 0) {
                url = "mailto:" + source.substring(i, end)
            } else if ((c == 'h' || c == 'H') && (previous == null || !previous.isAsciiLetter())) {
                end = protocolEnd(source, i)
                if (end > 0) url = source.substring(i, end)
            } else if ((c == 'w' || c == 'W') && (previous == null || previous in "(*_[]~" || previous.isSpaceOrLineEnding())) {
                end = wwwEnd(source, i)
                if (end > 0) url = "http://" + source.substring(i, end)
            }
            if (url == null) return false
            flush()
            nodes += Node.Inline(Kind.LINK, url, listOf(Node.Text(source.substring(i, end))))
            i = end
            return true
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

    /**
     * The rule of three, over what is left of each run as micromark counts it: when either can do
     * both, a closer not a multiple of 3 does not pair to a sum that is.
     */
    private fun pairs(opener: Node.Delimiter, closer: Node.Delimiter): Boolean {
        if (closer.char == '~') return opener.length == closer.length
        return !((opener.canClose || closer.canOpen) && closer.count % 3 != 0 && (opener.count + closer.count) % 3 == 0)
    }

    private const val WHITESPACE = 1
    private const val PUNCTUATION = 2
    private const val OTHER = 0

    /** micromark's `classifyCharacter`, none (the text's start or end) counting as whitespace. */
    private fun group(c: Char?): Int = when {
        c == null || isJsSpace(c) -> WHITESPACE
        isUnicodePunctuation(c) -> PUNCTUATION
        else -> OTHER
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

}

/** Where an image stands in a paragraph's parsed text: a private-use character, cut out before it is drawn. */
internal const val IMAGE_SLOT = ''

// micromark's tokenizers, over UTF-16 units as it reads them. `-1` past the end.

private fun String.code(index: Int): Int = if (index < length) this[index].code else -1

private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

private fun isAsciiAlpha(code: Int) = code in 'a'.code..'z'.code || code in 'A'.code..'Z'.code

private fun isAsciiAlphanumeric(code: Int) = isAsciiAlpha(code) || code in '0'.code..'9'.code

/** `markdownLineEndingOrSpace`. */
private fun Char.isSpaceOrLineEnding() = this == ' ' || this == '\t' || this == '\n' || this == '\r'

private fun isSpaceOrLineEnding(code: Int) = code >= 0 && code.toChar().isSpaceOrLineEnding()

private fun isUnicodeWhitespace(code: Int) = code >= 0 && isJsSpace(code.toChar())

/** `unicodePunctuation`: Unicode's P and S categories, one UTF-16 unit at a time. */
private fun isUnicodePunctuation(c: Char): Boolean = when (Character.getType(c).toByte()) {
    Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
    Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
    Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
    Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
    -> true
    else -> false
}

private fun isUnicodePunctuation(code: Int) = code >= 0 && isUnicodePunctuation(code.toChar())

/** GFM's email atext: `+ - . _` and ASCII letters and digits. */
private fun isAtext(c: Char) = c == '+' || c == '-' || c == '.' || c == '_' || c in '0'..'9' || c.isAsciiLetter()

/** An email address from [start] (`emailAutolink`): the index after it, or -1. */
private fun emailEnd(s: String, start: Int): Int {
    var j = start
    while (j < s.length && isAtext(s[j])) j++
    if (s.code(j) != '@'.code) return -1
    j++
    var data = false
    var dot = false
    while (true) {
        val code = s.code(j)
        if (code == '.'.code) {
            // A dot not followed by a letter or digit ends the address, outside it.
            if (!isAsciiAlphanumeric(s.code(j + 1))) break
            dot = true
            j++
        } else if (code == '-'.code || code == '_'.code || isAsciiAlphanumeric(code)) {
            data = true
            j++
        } else {
            break
        }
    }
    return if (data && dot && isAsciiAlpha(s.code(j - 1))) j else -1
}

/** `http://` or `https://` and an address from [start] (`protocolAutolink`): the index after it, or -1. */
private fun protocolEnd(s: String, start: Int): Int {
    var j = start + 1
    while (isAsciiAlpha(s.code(j)) && j - start < 5) j++
    val protocol = s.substring(start, j).lowercase()
    if (s.code(j) != ':'.code || (protocol != "http" && protocol != "https")) return -1
    if (s.code(j + 1) != '/'.code || s.code(j + 2) != '/'.code) return -1
    j += 3
    val code = s.code(j)
    if (code < 0x20 || code == 0x7F || isUnicodeWhitespace(code) || isUnicodePunctuation(code)) return -1
    val domain = domainEnd(s, j)
    return if (domain < 0) -1 else pathEnd(s, domain)
}

/** `www.` and an address from [start] (`wwwAutolink`): the index after it, or -1. */
private fun wwwEnd(s: String, start: Int): Int {
    for (k in 0 until 3) if (s.code(start + k) != 'w'.code && s.code(start + k) != 'W'.code) return -1
    if (s.code(start + 3) != '.'.code || s.code(start + 4) < 0) return -1
    val domain = domainEnd(s, start)
    return if (domain < 0) -1 else pathEnd(s, domain)
}

/** A domain from [start]: the index after it, or -1 when it is empty or its last two parts hold an `_`. */
private fun domainEnd(s: String, start: Int): Int {
    var j = start
    var underscoreInLast = false
    var underscoreInLastButOne = false
    var seen = false
    while (true) {
        val code = s.code(j)
        if (code == '.'.code || code == '_'.code) {
            if (isTrail(s, j)) break
            if (code == '_'.code) {
                underscoreInLast = true
            } else {
                underscoreInLastButOne = underscoreInLast
                underscoreInLast = false
            }
            j++
            continue
        }
        if (code < 0 || isUnicodeWhitespace(code) || (code != '-'.code && isUnicodePunctuation(code))) break
        seen = true
        j++
    }
    return if (underscoreInLast || underscoreInLastButOne || !seen) -1 else j
}

/** The rest of an address from [start]: up to white space, its own parentheses kept, punctuation that ends it left out. */
private fun pathEnd(s: String, start: Int): Int {
    var j = start
    var open = 0
    var close = 0
    while (true) {
        val code = s.code(j)
        when {
            code == '('.code -> {
                open++
                j++
            }
            code == ')'.code && close < open -> {
                close++
                j++
            }
            code >= 0 && code.toChar() in "!\"&')*,.:;<?]_~" -> {
                if (isTrail(s, j)) return j
                if (code == ')'.code) close++
                j++
            }
            code < 0 || isUnicodeWhitespace(code) -> return j
            else -> j++
        }
    }
}

/** Whether what follows [start] is punctuation that ends an address (`trail`): up to white space, `<` or the end. */
private fun isTrail(s: String, start: Int): Boolean {
    var j = start
    while (true) {
        val code = s.code(j)
        when {
            code >= 0 && code.toChar() in "!\"')*,.:;?_~" -> j++
            code == '&'.code -> {
                // A character reference: `&`, letters, `;`.
                j++
                if (!isAsciiAlpha(s.code(j))) return false
                while (isAsciiAlpha(s.code(j))) j++
                if (s.code(j) != ';'.code) return false
                j++
            }
            code == ']'.code -> {
                j++
                val next = s.code(j)
                if (next < 0 || next == '('.code || next == '['.code || isUnicodeWhitespace(next)) return true
            }
            code == '<'.code || code < 0 || isUnicodeWhitespace(code) -> return true
            else -> return false
        }
    }
}

/**
 * Raw HTML from the `<` at [start], as micromark's `htmlText` reads it: an open or closing tag, a
 * comment, a processing instruction, a declaration or a CDATA section, any of them across lines.
 * The index after it, or -1.
 */
private fun htmlTextEnd(s: String, start: Int): Int {
    var j = start + 1
    fun at(k: Int = j) = s.code(k)
    fun whitespace(code: Int) = isSpaceOrLineEnding(code)
    when {
        at() == '!'.code -> {
            j++
            when {
                at() == '-'.code -> {
                    if (at(j + 1) != '-'.code) return -1
                    j += 2
                    // `<!-->` and `<!--->` are whole comments; any other runs to the first `-->`.
                    var dashes = 2
                    while (true) {
                        val code = at()
                        if (code < 0) return -1
                        if (code == '>'.code && dashes >= 2) return j + 1
                        dashes = if (code == '-'.code) dashes + 1 else 0
                        j++
                    }
                }
                at() == '['.code -> {
                    if (!s.startsWith("[CDATA[", j)) return -1
                    val close = s.indexOf("]]>", j + 7)
                    return if (close < 0) -1 else close + 3
                }
                isAsciiAlpha(at()) -> {
                    val close = s.indexOf('>', j)
                    return if (close < 0) -1 else close + 1
                }
                else -> return -1
            }
        }
        // The opening's own `?` does not close it.
        at() == '?'.code -> {
            val close = s.indexOf("?>", j + 1)
            return if (close < 0) -1 else close + 2
        }
        at() == '/'.code -> {
            j++
            if (!isAsciiAlpha(at())) return -1
            while (at() == '-'.code || isAsciiAlphanumeric(at())) j++
            while (whitespace(at())) j++
            return if (at() == '>'.code) j + 1 else -1
        }
        isAsciiAlpha(at()) -> {
            while (at() == '-'.code || isAsciiAlphanumeric(at())) j++
            if (at() != '/'.code && at() != '>'.code && !whitespace(at())) return -1
            while (true) {
                // Between attributes.
                while (whitespace(at())) j++
                val code = at()
                if (code == '/'.code) return if (at(j + 1) == '>'.code) j + 2 else -1
                if (code == '>'.code) return j + 1
                if (code != ':'.code && code != '_'.code && !isAsciiAlpha(code)) return -1
                // A name, then maybe `=` and a value.
                while (at().let { it == '-'.code || it == '.'.code || it == ':'.code || it == '_'.code || isAsciiAlphanumeric(it) }) j++
                while (whitespace(at())) j++
                if (at() != '='.code) continue
                j++
                while (whitespace(at())) j++
                val first = at()
                if (first < 0 || first == '<'.code || first == '='.code || first == '>'.code || first == '`'.code) return -1
                if (first == '"'.code || first == '\''.code) {
                    val close = s.indexOf(first.toChar(), j + 1)
                    if (close < 0) return -1
                    j = close + 1
                    if (at() != '/'.code && at() != '>'.code && !whitespace(at())) return -1
                } else {
                    while (true) {
                        val value = at()
                        if (value < 0 || value == '"'.code || value == '\''.code || value == '<'.code || value == '='.code || value == '`'.code) return -1
                        if (value == '/'.code || value == '>'.code || whitespace(value)) break
                        j++
                    }
                }
            }
        }
        else -> return -1
    }
}
