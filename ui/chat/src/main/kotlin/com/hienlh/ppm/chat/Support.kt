package com.hienlh.ppm.chat

import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import com.hienlh.ppm.design.CssText
import com.hienlh.ppm.design.PpmFormat
import com.hienlh.ppm.design.ThemeTokens
import kotlinx.coroutines.delay
import java.time.Instant
import kotlin.math.roundToInt

/** `formatRelativeDate`, kept current: "just now" becomes "1m ago" without a redraw from outside. */
@Composable
fun RelativeTime(date: Instant, style: TextStyle, modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(date) {
        while (true) {
            now = Instant.now()
            delay(30_000)
        }
    }
    CssText(PpmFormat.relative(date, now), style, modifier)
}

/** `basename()` of a path as the web writes it. */
internal fun basename(path: String): String = path.split('/', '\\').last()

/** The web's `truncate(str, max)`: a prefix and an ellipsis. */
internal fun truncate(text: String, max: Int): String = if (text.length > max) text.take(max) + "…" else text

/**
 * `max-w-[80%]` on a box that an `items-end` column puts flush with its end: as wide as what it
 * holds, up to [fraction] of the width it is offered, and starting where Chrome rounds its start.
 * Chrome keeps the fraction of both widths and snaps the box's left edge; whole pixels floored a
 * wrapped bubble and took a short one's text to its next pixel, each a pixel off the web's.
 * [exact] is how wide what it holds is exactly, told the width that measures, or NaN for that.
 */
internal fun Modifier.fitWidthAtEnd(fraction: Float, exact: Density.(measured: Int) -> Float): Modifier = layout { measurable, constraints ->
    val available = constraints.maxWidth
    val measured = measurable.maxIntrinsicWidth(Constraints.Infinity)
    val wanted = exact(measured).takeUnless { it.isNaN() } ?: measured.toFloat()
    // The start rounds half up, so from the end the width rounds half down.
    val width = (-(-minOf(wanted, available * fraction)).roundToInt()).coerceIn(constraints.minWidth, available)
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(width, placeable.height) { placeable.place(0, 0) }
}

/**
 * How wide [text]'s widest line is in [style], in fractions of a pixel: its lines' own widths,
 * since Compose rounds intrinsic widths up to whole pixels.
 */
@Composable
internal fun rememberExactTextWidth(text: AnnotatedString, style: TextStyle): Float {
    val measurer = rememberTextMeasurer()
    return remember(text, style, measurer) {
        val laid = measurer.measure(text, style)
        (0 until laid.lineCount).maxOfOrNull { laid.getLineRight(it) - laid.getLineLeft(it) } ?: 0f
    }
}

/**
 * Text that may lay out a pixel wider than it is offered, for a box sized to the text's exact
 * width ([fitWidthAtEnd]): Compose wraps a line that needs 420.3 px in 420, where Chrome's box is
 * the 420.3 the line needs.
 */
internal fun Modifier.exactTextSlack(): Modifier = layout { measurable, constraints ->
    val wider = if (constraints.hasBoundedWidth) constraints.copy(maxWidth = constraints.maxWidth + 1) else constraints
    val placeable = measurable.measure(wider)
    layout(minOf(placeable.width, constraints.maxWidth), placeable.height) { placeable.place(0, 0) }
}

/** `TextWithLinks` in message-user-bubble.tsx: a URL in what the user typed, underlined. */
private val userUrl = Regex("https?://[^\\s<>()]+[^\\s<>().,;:!?'\"]")

/**
 * The text with each URL a link, `text-primary underline hover:text-primary/80 break-all`, and
 * the rest ending its lines where Chrome's do ([breakMark]). The marks go only into text that
 * holds none of its own, so that a copy can take every one back out ([userTextMarks]).
 */
internal fun linkedUserText(text: String, tokens: ThemeTokens, uriHandler: UriHandler): AnnotatedString {
    val breakAll = BREAK !in text
    val marked = breakAll && JOIN !in text
    if (!text.contains("://")) return AnnotatedString(if (marked) chromeBreaks(text) else text)
    return buildAnnotatedString {
        val styles = TextLinkStyles(
            SpanStyle(color = tokens.accent.color, textDecoration = TextDecoration.Underline),
            hoveredStyle = SpanStyle(color = tokens.accent.opacity(0.8).color),
        )
        fun plain(from: Int, to: Int) {
            for (i in from until to) {
                if (marked) breakMark(text, i)?.let { append(it) }
                append(text[i])
            }
        }
        var last = 0
        for (match in userUrl.findAll(text)) {
            plain(last, match.range.first)
            if (marked) breakMark(text, match.range.first)?.let { append(it) }
            val url = match.value
            val start = length
            append(if (breakAll) breakAnywhere(url) else url)
            addLink(LinkAnnotation.Url(url, styles) { runCatching { uriHandler.openUri(url) } }, start, length)
            last = match.range.last + 1
        }
        plain(last, text.length)
    }
}

/** The marks [linkedUserText] puts into [text], for a copy to take out. */
internal fun userTextMarks(text: String): String = when {
    BREAK in text -> ""
    JOIN in text -> "$BREAK"
    else -> "$BREAK$JOIN"
}

/** U+200B: a line may end here, and nothing is drawn. */
internal const val BREAK = '​'

/** What Chrome's `break-all` will not break before, and after, measured between two letters. */
private const val NO_BREAK_BEFORE = "!\"%'),./:;?]}"
private const val NO_BREAK_AFTER = "\"$'([\\{"

private fun breaksBefore(c: Int) = if (c < 0x80) c > 0x20 && c.toChar() !in NO_BREAK_BEFORE else Character.isLetterOrDigit(c)

private fun breaksAfter(c: Int) = if (c < 0x80) c > 0x20 && c.toChar() !in NO_BREAK_AFTER else Character.isLetterOrDigit(c)

/**
 * What Android's line breaker will not start a line with even after a space (UAX #14 LB13),
 * where Chrome ends a line at any space.
 */
private const val NO_BREAK_AFTER_SPACE = "!),./:;?]}"

/**
 * `word-break: break-all`: a [BREAK] wherever Chrome may end a line inside a word, which no
 * Android line breaker offers, and before a run of spaces that only Chrome breaks after. Put
 * before the spaces, it lets them hang at the end of the line as Chrome's do.
 */
fun breakAnywhere(text: String): String = buildString(text.length * 2) {
    var previous = -1
    var i = 0
    while (i < text.length) {
        val c = text.codePointAt(i)
        if (breaksBetween(text, i, previous, c)) append(BREAK)
        appendCodePoint(c)
        previous = c
        i += Character.charCount(c)
    }
}

/**
 * U+FEFF: no line ends on either side, and nothing is drawn. U+2060 says the same, but Geist has
 * no glyph for it, so Android would set it in another font and split the run of text around it.
 */
internal const val JOIN = '﻿'

/**
 * What Android's line breaker lets a line start with after `!`, `?`, `|`, `}` and `/`, which
 * Blink's table never does, except after `?`.
 */
private const val ICU_LINE_START = "#$%&(*+0123456789<=>ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\^_`abcdefghijklmnopqrstuvwxyz{~"

/**
 * Whether Android's line breaker ends a line between two printable ASCII characters, [x] then
 * [y]: whatever surrounds them with [always], else around some. Measured on Android 17, where
 * Minikin asks ICU and never ends a line after a hyphen.
 */
private fun androidBreaks(x: Char, y: Char, always: Boolean): Boolean = when (x) {
    '!', '?', '|' -> y in ICU_LINE_START
    '}' -> y in ICU_LINE_START && !(always && y in "$%+\\")
    '/' -> if (always) y in ICU_LINE_START && y !in "$%+\\0123456789" else y in ICU_LINE_START || y == '@'
    '$', '%', '+', '\\' -> y in (if (always) "$%+\\" else "$%(+[\\{")
    ')', ',', '.', ':', ';', ']' -> y in (if (always) "([{" else "$%(+[\\{")
    else -> false
}

private fun Char.isAsciiLetterOrDigit() = this in '0'..'9' || this in 'a'..'z' || this in 'A'..'Z'

/**
 * Whether Blink ends a line between two printable ASCII characters, [x] then [y], after [before]:
 * its own table (`LineBreakData::FillAscii`), which breaks only before an opening bracket, after a
 * hyphen and after a question mark, and `ShouldBreakFast`, which breaks between a hyphen and a
 * digit only after a letter or a digit.
 */
private fun blinkBreaks(before: Char?, x: Char, y: Char): Boolean = when (x) {
    '-' -> if (y in '0'..'9') before?.isAsciiLetterOrDigit() == true else y !in "$!),./:;?]}"
    '?' -> y !in "!\"'),./:;?]}"
    else -> y in "(<[{" && x !in "$'(/<@[^_`{" && !x.isAsciiLetterOrDigit()
}

/**
 * What goes between [text]`[i - 1]` and [text]`[i]` for Android to end lines where Chrome does
 * under `word-break: normal`: a [JOIN] where only Android's line breaker would end one, a [BREAK]
 * where only Blink's would, or nothing. [codeEnd] and [codeStart] say a code's box ends or starts
 * there, its edge reading as a letter to Android; the mark goes outside it.
 *
 * Between two printable ASCII characters Blink keeps a table of its own, and ICU breaks after
 * `/`, `!`, `|` and `}` too. Minikin never breaks after a hyphen or an en dash, and keeps a word
 * with `@` or `://` whole up to its first character past ASCII, which a [JOIN] there ends. After
 * spaces Chrome always may, where ICU will not before what [NO_BREAK_AFTER_SPACE] holds or after
 * an opening bracket: the [BREAK] goes before the spaces, as [breakAnywhere]'s does.
 */
internal fun breakMark(text: CharSequence, i: Int, codeEnd: Boolean = false, codeStart: Boolean = false): Char? {
    if (i <= 0 || i >= text.length) return null
    val x = text[i - 1]
    val y = text[i]
    if (isBlank(x.code)) return null
    if (isBlank(y.code)) {
        var next = i
        while (next < text.length && isBlank(text[next].code)) next++
        return if (next < text.length && (text[next] in NO_BREAK_AFTER_SPACE || x in "([{")) BREAK else null
    }
    val before = text.getOrNull(i - 2)
    if (x !in '!'..'~' || y !in '!'..'~') {
        // Past ASCII Chrome asks ICU too, which breaks after a hyphen or an en dash, unless a
        // hyphen starts the word (UAX #14 LB20a).
        val hyphen = x == '-' || x == '‐' || x == '–'
        val starts = x != '–' && (before == null || before.isWhitespace())
        return if (hyphen && !starts && y.isLetterOrDigit()) BREAK else null
    }
    val blink = blinkBreaks(before, x, y)
    val left = if (codeEnd) 'a' else x
    val right = if (codeStart) 'a' else y
    return when {
        blink -> if (androidBreaks(left, right, always = true)) null else BREAK
        androidBreaks(left, right, always = false) || y == '@' || (x == ':' && y == '/') -> JOIN
        else -> null
    }
}

/** [breakMark] for a textarea's text, which wraps as a `pre-wrap` message does: the mark before [text]`[i]`. */
fun textareaBreakMark(text: CharSequence, i: Int): Char? = breakMark(text, i)

/**
 * The clipboard for a field laid out with [textareaBreakMark]'s marks, to provide as `LocalClipboard`: a copy or a
 * cut puts the text on the clipboard without them. Foundation copies the text as it is laid out, so a path copied
 * from the box would otherwise paste with invisible characters in it (`echo <paste> | od -c` showed them). The box
 * marks every text, so a real U+200B or U+FEFF the user typed or pasted cannot be told from a mark and is dropped
 * from the clipboard with them, where a textarea's copy would keep it; both are invisible. Reading the clipboard,
 * a paste, is untouched.
 */
@Composable
fun textareaClipboard(): ProvidedValue<Clipboard> {
    val clipboard = LocalClipboard.current
    return LocalClipboard provides remember(clipboard) { CleanClipboard(clipboard, "$BREAK$JOIN") }
}

/** [text] with [breakMark]'s marks in it, for Android to end its lines where Chrome ends them. */
internal fun chromeBreaks(text: String): String = buildString(text.length + 8) {
    for (i in text.indices) {
        breakMark(text, i)?.let { append(it) }
        append(text[i])
    }
}

/** The same for styled text, each style kept on what it styled: a mark at a style's edge goes outside it. */
fun chromeBreaks(text: AnnotatedString): AnnotatedString {
    val plain = text.text
    // Where each character of [plain] lands among the marks.
    val at = IntArray(plain.length)
    val marked = buildString(plain.length + 8) {
        for (i in plain.indices) {
            breakMark(plain, i)?.let { append(it) }
            at[i] = length
            append(plain[i])
        }
    }
    fun end(end: Int) = if (end == 0) 0 else at[end - 1] + 1
    return AnnotatedString(marked, text.spanStyles.map { AnnotatedString.Range(it.item, if (it.start < plain.length) at[it.start] else marked.length, end(it.end)) })
}

/** Whether a code's box needs a [JOIN] between its start edge and [first], or its [last] and its end edge. */
internal fun joinsCodeStart(first: Char): Boolean = first.code >= 0x80

internal fun joinsCodeEnd(last: Char): Boolean = last.code >= 0x80 || androidBreaks(last, 'a', always = false)

/** Columns from one tab stop to the next: Tailwind's preflight sets `tab-size: 4`. */
private const val TAB_SIZE = 4

/**
 * [text] as a `white-space: pre-wrap` block of monospace lays it out: a tab as the spaces up to
 * the next stop of [TAB_SIZE] columns (a copy then holds the spaces), and with [breakAll] a
 * [BREAK] wherever [breakAnywhere] puts one. [style] goes on the `[start, end)` [spans] of [text].
 */
internal fun preText(text: String, breakAll: Boolean, spans: List<Pair<Int, Int>> = emptyList(), style: SpanStyle? = null): AnnotatedString {
    val starts = IntArray(text.length)
    val ends = IntArray(text.length)
    val out = StringBuilder(text.length * 2)
    var previous = -1
    var column = 0
    var i = 0
    while (i < text.length) {
        val c = text.codePointAt(i)
        val size = Character.charCount(c)
        if (breakAll && breaksBetween(text, i, previous, c)) out.append(BREAK)
        for (k in 0 until size) starts[i + k] = out.length
        when (c) {
            '\t'.code -> {
                val n = TAB_SIZE - column % TAB_SIZE
                repeat(n) { out.append(' ') }
                column += n
            }
            '\n'.code -> {
                out.append('\n')
                column = 0
            }
            else -> {
                out.appendCodePoint(c)
                column++
            }
        }
        for (k in 0 until size) ends[i + k] = out.length
        previous = c
        i += size
    }
    return buildAnnotatedString {
        append(out)
        if (style != null) for ((start, end) in spans) if (end > start) addStyle(style, starts[start], ends[end - 1])
    }
}

private fun isBlank(c: Int) = c == ' '.code || c == '\t'.code

/** Whether [breakAnywhere] puts a [BREAK] before [c], at [i] of [text], after [previous] (-1 at the start). */
private fun breaksBetween(text: String, i: Int, previous: Int, c: Int): Boolean {
    if (previous < 0) return false
    if (breaksAfter(previous) && breaksBefore(c)) return true
    if (!isBlank(c) || isBlank(previous)) return false
    var next = i
    while (next < text.length && isBlank(text[next].code)) next++
    return next < text.length && text[next] in NO_BREAK_AFTER_SPACE
}

/** [content] copied to the clipboard without the [BREAK]s [breakAnywhere] put in it: a pasted URL has to be the URL. */
@Composable
internal fun WithoutBreaksOnCopy(enabled: Boolean, content: @Composable () -> Unit) {
    if (!enabled) return content()
    WithoutOnCopy("$BREAK", content)
}

/** [content] copied to the clipboard without [marks], characters there for the layout alone. */
@Composable
internal fun WithoutOnCopy(marks: String, content: @Composable () -> Unit) {
    val clipboard = LocalClipboard.current
    val clean = remember(clipboard, marks) { CleanClipboard(clipboard, marks) }
    CompositionLocalProvider(LocalClipboard provides clean, content = content)
}

private class CleanClipboard(private val inner: Clipboard, private val marks: String) : Clipboard by inner {
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val data = clipEntry?.clipData
        val text = data?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.text
        if (text == null || text.none { it in marks }) return inner.setClipEntry(clipEntry)
        inner.setClipEntry(ClipEntry(ClipData.newPlainText(data.description.label, text.filterNot { it in marks }.toString())))
    }
}
