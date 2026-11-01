package com.hienlh.ppm.app.ui.files

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import java.util.Locale

/** Monaco line endings, including the empty last row after a newline. Offsets remain UTF-16. */
internal class FileCodeLines(source: String) {
    val text = source.replace("\r\n", "\n").replace('\r', '\n')
    val starts: IntArray
    val count: Int get() = starts.size
    val maxColumns: Int

    init {
        val offsets = arrayListOf(0)
        var columns = 0
        var widest = 0
        var index = 0
        while (index < text.length) {
            val cp = text.codePointAt(index)
            when (cp) {
                10 -> { offsets += index + 1; widest = maxOf(widest, columns); columns = 0 }
                9 -> columns += 4 - columns % 4
                else -> columns += fileCodeColumns(cp)
            }
            index += Character.charCount(cp)
        }
        starts = offsets.toIntArray()
        maxColumns = maxOf(widest, columns)
    }

    fun end(row: Int): Int = if (row + 1 < count) starts[row + 1] - 1 else text.length
    fun line(row: Int): String = text.substring(starts[row], end(row))

    /** Clip every span to every row it crosses; do not lose multiline comments or string styles. */
    fun tokenRows(tokens: AnnotatedString): List<AnnotatedString>? {
        if (tokens.text != text) return null
        val styles = List(count) { mutableListOf<AnnotatedString.Range<SpanStyle>>() }
        for (span in tokens.spanStyles) {
            if (span.start >= span.end) continue
            var row = starts.binarySearch(span.start).let { if (it < 0) -it - 2 else it }.coerceAtLeast(0)
            while (row < count && starts[row] < span.end) {
                val first = maxOf(starts[row], span.start)
                val last = minOf(end(row), span.end)
                if (first < last) styles[row] += AnnotatedString.Range(span.item, first - starts[row], last - starts[row])
                row++
            }
        }
        return List(count) { AnnotatedString(line(it), styles[it]) }
    }
}

/** Tabs stop every four columns; remap token offsets as well as the text. */
internal fun fileCodeDisplayLine(text: String, tokens: AnnotatedString?): AnnotatedString {
    val styled = tokens?.takeIf { it.text == text } ?: AnnotatedString(text)
    if ('\t' !in text) return styled
    val offsets = IntArray(text.length + 1)
    val expanded = StringBuilder(text.length)
    var column = 0
    text.forEachIndexed { index, char ->
        offsets[index] = expanded.length
        if (char == '\t') {
            val spaces = 4 - column % 4
            repeat(spaces) { expanded.append(' ') }
            column += spaces
        } else {
            expanded.append(char)
            if (!Character.isLowSurrogate(char)) column += fileCodeColumns(text.codePointAt(index))
        }
    }
    offsets[text.length] = expanded.length
    return AnnotatedString(expanded.toString(), styled.spanStyles.map {
        AnnotatedString.Range(it.item, offsets[it.start], offsets[it.end])
    })
}

private fun fileCodeColumns(cp: Int): Int {
    when (Character.getType(cp)) {
        Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(), Character.FORMAT.toInt() -> return 0
    }
    return if (cp >= 0x1100 && (cp <= 0x115f || cp in 0x2e80..0xa4cf || cp in 0xac00..0xd7a3 ||
        cp in 0xf900..0xfaff || cp in 0xff01..0xff60 || cp >= 0x1f000)) 2 else 1
}

/** Metadata is one-based; invalid starts do nothing, valid out-of-bounds locations clamp. */
internal fun fileCodeReveal(line: Long?, end: Long?, count: Int): IntRange? {
    if (line == null || line <= 0 || count <= 0) return null
    val start = line.coerceAtMost(count.toLong()).toInt() - 1
    val last = end?.takeIf { it >= line }?.coerceAtMost(count.toLong())?.toInt()?.minus(1) ?: start
    return start..last
}

/** Shiki aliases; explicit inline/language metadata wins. Dotenv suffixes are not languages. */
internal fun fileCodeLanguage(path: String?, override: String? = null): String {
    override?.takeIf { it.isNotBlank() }?.let { return it }
    val name = path.orEmpty().substringAfterLast('/').substringAfterLast('\\').lowercase(Locale.ROOT)
    val ext = name.substringAfterLast('.', "")
    val known = mapOf("kt" to "kotlin", "kts" to "kotlin", "js" to "javascript", "mjs" to "javascript", "cjs" to "javascript",
        "ts" to "typescript", "mts" to "typescript", "cts" to "typescript", "py" to "python", "rb" to "ruby",
        "rs" to "rust", "sh" to "shell", "bash" to "shell", "yml" to "yaml", "htm" to "html", "mdx" to "md",
        "cs" to "csharp", "hpp" to "cpp", "h" to "c")
    known[ext]?.let { return it }
    if (ext in setOf("md", "html", "css", "scss", "json", "yaml")) return ext
    if (name == ".env" || name.startsWith(".env.")) return "dotenv"
    return when (name) { "dockerfile" -> "dockerfile"; "makefile" -> "make"; else -> ext.ifEmpty { "text" } }
}
