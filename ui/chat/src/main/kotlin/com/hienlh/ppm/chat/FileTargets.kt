package com.hienlh.ppm.chat

import com.hienlh.ppm.state.workspace.SourceLine

/** A file a message points at, and the line or lines when it names some. */
data class FileTarget(val path: String, val line: SourceLine? = null)

/**
 * What a chat's markdown makes a file reference of, as the web decides it: a link's local
 * destination (`parseMarkdownFileTarget`), an inline code that is a path (`MdCode`), and the
 * `:120` / `#L120` suffix both may carry (`splitSourceLocation`). JavaScript's `.` stops at a
 * line terminator and its `$` only at the very end, which these patterns spell out.
 */
object FileTargets {
    /** `:120`, `:120:5` (the column dropped), `:120-140`, `#L120`, `#L120-L140`; the lazy head lets the last suffix win. */
    private val SOURCE_LOCATION = Regex("""^([^\n\r  ]*?)(?::(\d+)(?::\d+|-(\d+))?|#L(\d+)(?:-L?(\d+))?)\z""")

    /** The path and the location after it; null when the suffix names a line that cannot be. */
    fun splitSourceLocation(text: String): FileTarget? {
        val match = SOURCE_LOCATION.find(text) ?: return FileTarget(text)
        val groups = match.groupValues
        val start = safeInteger(groups[2].ifEmpty { groups[4] }) ?: return null
        if (start < 1) return null
        val rawEnd = groups[3].ifEmpty { groups[5] }
        if (rawEnd.isEmpty()) return FileTarget(groups[1], SourceLine(start))
        val end = safeInteger(rawEnd) ?: return null
        if (end < start) return null
        return FileTarget(groups[1], SourceLine(start, end))
    }

    private val FILE_URL = Regex("""^file://(?:localhost)?(/[^\n\r  ]*)\z""", RegexOption.IGNORE_CASE)
    private val SCHEME = Regex("""^[a-z][a-z\d+.-]*:""", RegexOption.IGNORE_CASE)
    private val DRIVE = Regex("""^[a-z]:[/\\]""", RegexOption.IGNORE_CASE)
    private val SLASHED_DRIVE = Regex("""^/([a-z]:[/\\])""", RegexOption.IGNORE_CASE)

    /** A link's destination when it is a local file or folder (`parseMarkdownFileTarget`); null for anything else. */
    fun markdownLink(href: String): FileTarget? {
        if (href.isEmpty() || href.startsWith("#") || href.any { it == '?' || it == '*' || it < ' ' }) return null
        var destination = href
        if (destination.startsWith("file:", ignoreCase = true)) {
            // A remote authority or a UNC path is nothing the host's API reads.
            destination = FILE_URL.find(destination)?.groupValues?.get(1) ?: return null
        }
        // A suffix naming an impossible line is a broken reference, not line 1.
        val located = splitSourceLocation(destination) ?: return null
        // A heading of a document still opens the document; an anchor alone stays in the page.
        var path = if (located.line != null) located.path else located.path.substringBefore('#')
        path = decodeUriComponent(path) ?: return null
        // Schemes are checked once the line is off: config.ts:136 is a file.
        val unc = path.length >= 2 && path[0] in "/\\" && path[1] in "/\\"
        if (path.isEmpty() || path.any { it < ' ' || it == '\u007f' } || unc || (SCHEME.containsMatchIn(path) && !DRIVE.containsMatchIn(path))) return null
        // Codex puts a slash before a Windows drive in a Markdown destination.
        return FileTarget(path.replace(SLASHED_DRIVE, "$1"), located.line)
    }

    private val EXTERNAL = Regex("""^(https?:)?//""", RegexOption.IGNORE_CASE)

    /** The schemes `defaultUrlTransform` keeps in a link; it empties any other. */
    private val OPENABLE = Regex("""^(https?|ircs?|mailto|xmpp):""", RegexOption.IGNORE_CASE)

    /**
     * The file or folder a markdown link's [destination] names (`MdLink`), read from the `href`
     * the web builds of it ([normalizeUri]); never for a web address.
     */
    fun link(destination: String): FileTarget? {
        val href = normalizeUri(destination)
        return if (EXTERNAL.containsMatchIn(href)) null else markdownLink(href)
    }

    /**
     * What a link that names no file hands the system: a page or a mail address. Any other scheme,
     * `javascript:` among them, goes nowhere, as the web empties it; so does a relative address,
     * with no page here to read it against.
     */
    fun url(destination: String): String? = normalizeUri(destination).takeIf { OPENABLE.containsMatchIn(it) }

    /** `FILE_EXTS`: the text files PPM opens in an editor tab. */
    private const val FILE_EXTS = "ts|tsx|js|jsx|mjs|cjs|py|json|md|mdx|yaml|yml|toml|css|scss|less|html|htm|sh|bash|zsh|go|rs|sql|rb|java|kt|swift|c|cpp|h|hpp|cs|vue|svelte|txt|env|cfg|conf|ini|xml|csv|log|dockerfile|makefile|gradle|output"
    private val FILE_EXT = Regex("""\.($FILE_EXTS)\z""", RegexOption.IGNORE_CASE)
    private val GLOB_CHARS = Regex("""[*?{}\[\]]""")
    private val LINE_REF = Regex("""^([^\n\r  ]+?):(\d+)(?:-(\d+))?\z""")

    /** A bare token that is a path: no space, nothing of a glob, an extension PPM opens. */
    private fun isClickableFilePath(s: String) = s.isNotEmpty() && ' ' !in s && !GLOB_CHARS.containsMatchIn(s) && FILE_EXT.containsMatchIn(s)

    /**
     * An inline code that names a file (`MdCode`): `path`, `path:line` or `path:start-end`. Its
     * numbers are taken as they are written, a 0 or a range ending before it starts included.
     */
    fun inlineCode(code: String): FileTarget? {
        val text = jsTrim(code)
        LINE_REF.find(text)?.let { match ->
            val (path, start, end) = match.destructured
            if (isClickableFilePath(path)) return FileTarget(path, SourceLine(jsNumber(start), end.ifEmpty { null }?.let(::jsNumber)))
        }
        return if (isClickableFilePath(text)) FileTarget(text) else null
    }

    /** `Number.isSafeInteger(Number(digits))`: the value, or null past 2^53 - 1. */
    private fun safeInteger(digits: String): Long? {
        val value = digits.trimStart('0').ifEmpty { "0" }
        if (value.length > 16) return null
        return value.toLong().takeIf { it <= 9_007_199_254_740_991L }
    }

    /** `Number(digits)` where it stays exact; past that the line is beyond any file anyway. */
    private fun jsNumber(digits: String): Long = safeInteger(digits) ?: Long.MAX_VALUE

    /** JavaScript's whitespace and line terminators: what `String.prototype.trim` takes off. */
    private fun isJsSpace(c: Char) = c in "\t\n\u000B\u000C\r       　﻿" || c in ' '..' '

    private fun jsTrim(s: String) = s.trim(::isJsSpace)
}

/**
 * micromark's `normalizeUri`, which makes a link's `href`: whatever a URL may not hold is
 * percent-encoded as UTF-8, a `%` before two letters or digits is left as it is, and a lone
 * surrogate becomes U+FFFD.
 */
internal fun normalizeUri(value: String): String {
    fun alphanumeric(c: Char?) = c != null && (c in '0'..'9' || c in 'A'..'Z' || c in 'a'..'z')
    fun allowed(c: Char) = c == '!' || c == '#' || c == '$' || c in '&'..';' || c == '=' || c in '?'..'Z' || c == '_' || c in 'a'..'z' || c == '~'
    val out = StringBuilder(value.length)
    var start = 0
    var index = 0
    while (index < value.length) {
        val c = value[index]
        var skip = 0
        val replace = when {
            c == '%' && alphanumeric(value.getOrNull(index + 1)) && alphanumeric(value.getOrNull(index + 2)) -> {
                skip = 2
                null
            }
            c.code < 128 -> if (allowed(c)) null else c.toString()
            c.isHighSurrogate() && value.getOrNull(index + 1)?.isLowSurrogate() == true -> {
                skip = 1
                value.substring(index, index + 2)
            }
            c.isSurrogate() -> "\uFFFD"
            else -> c.toString()
        }
        if (replace != null) {
            out.append(value, start, index)
            for (byte in replace.toByteArray(Charsets.UTF_8)) out.append('%').append(HEX[(byte.toInt() shr 4) and 0xF]).append(HEX[byte.toInt() and 0xF])
            start = index + skip + 1
        }
        index += skip + 1
    }
    return out.append(value, start, value.length).toString()
}

private const val HEX = "0123456789ABCDEF"

/** `decodeURIComponent`: every `%XX` read as UTF-8; null where JavaScript throws. */
internal fun decodeUriComponent(s: String): String? {
    if ('%' !in s) return s
    fun byteAt(i: Int): Int? {
        if (i + 2 >= s.length || s[i] != '%') return null
        // ASCII only: `Character.digit` would take other scripts' digits too.
        fun hex(c: Char) = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }
        val high = hex(s[i + 1])
        val low = hex(s[i + 2])
        return if (high < 0 || low < 0) null else high * 16 + low
    }
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        if (s[i] != '%') {
            out.append(s[i++])
            continue
        }
        val lead = byteAt(i) ?: return null
        i += 3
        if (lead < 0x80) {
            out.append(lead.toChar())
            continue
        }
        val size = when {
            lead and 0xE0 == 0xC0 -> 2
            lead and 0xF0 == 0xE0 -> 3
            lead and 0xF8 == 0xF0 -> 4
            else -> return null
        }
        var codePoint = lead and (0xFF shr (size + 1))
        repeat(size - 1) {
            val next = byteAt(i) ?: return null
            if (next and 0xC0 != 0x80) return null
            codePoint = (codePoint shl 6) or (next and 0x3F)
            i += 3
        }
        // An overlong form, a surrogate or a code point past U+10FFFF is what JavaScript refuses.
        val least = when (size) {
            2 -> 0x80
            3 -> 0x800
            else -> 0x10000
        }
        if (codePoint < least || codePoint > 0x10FFFF || codePoint in 0xD800..0xDFFF) return null
        out.appendCodePoint(codePoint)
    }
    return out.toString()
}
