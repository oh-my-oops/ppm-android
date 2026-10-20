package com.hienlh.ppm.chat

import android.content.ClipData
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextDecoration
import com.hienlh.ppm.design.PpmFormat
import com.hienlh.ppm.design.ThemeTokens
import kotlinx.coroutines.delay
import java.time.Instant

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
    BasicText(PpmFormat.relative(date, now), modifier, style)
}

/** `basename()` of a path as the web writes it. */
internal fun basename(path: String): String = path.split('/').lastOrNull { it.isNotEmpty() } ?: path

/** The web's `truncate(str, max)`: a prefix and an ellipsis. */
internal fun truncate(text: String, max: Int): String = if (text.length > max) text.take(max) + "…" else text

/** `max-w-[80%]`: the content is offered at most [fraction] of the width this is offered. */
internal fun Modifier.maxWidthFraction(fraction: Float): Modifier = layout { measurable, constraints ->
    val limit = if (constraints.hasBoundedWidth) (constraints.maxWidth * fraction).toInt() else constraints.maxWidth
    val placeable = measurable.measure(constraints.copy(minWidth = minOf(constraints.minWidth, limit), maxWidth = limit))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/** `TextWithLinks` in message-user-bubble.tsx: a URL in what the user typed, underlined. */
private val userUrl = Regex("https?://[^\\s<>()]+[^\\s<>().,;:!?'\"]")

/**
 * The text with each URL a link, `text-primary underline hover:text-primary/80 break-all`. The
 * URL breaks where Chrome breaks it only when the text holds no [BREAK] of its own, so that
 * [WithoutBreaksOnCopy] can take every one back out of a copy.
 */
internal fun linkedUserText(text: String, tokens: ThemeTokens, uriHandler: UriHandler): AnnotatedString {
    if (!text.contains("://")) return AnnotatedString(text)
    val breakAll = BREAK !in text
    return buildAnnotatedString {
        val styles = TextLinkStyles(
            SpanStyle(color = tokens.accent.color, textDecoration = TextDecoration.Underline),
            hoveredStyle = SpanStyle(color = tokens.accent.opacity(0.8).color),
        )
        var last = 0
        for (match in userUrl.findAll(text)) {
            append(text, last, match.range.first)
            val url = match.value
            val start = length
            append(if (breakAll) breakAnywhere(url) else url)
            addLink(LinkAnnotation.Url(url, styles) { runCatching { uriHandler.openUri(url) } }, start, length)
            last = match.range.last + 1
        }
        append(text, last, text.length)
    }
}

/** U+200B: a line may end here, and nothing is drawn. */
internal const val BREAK = '​'

/** What Chrome's `break-all` will not break before, and after, measured between two letters. */
private const val NO_BREAK_BEFORE = "!\"%'),./:;?]}"
private const val NO_BREAK_AFTER = "\"$'([\\{"

private fun breaksBefore(c: Int) = if (c < 0x80) c > 0x20 && c.toChar() !in NO_BREAK_BEFORE else Character.isLetterOrDigit(c)

private fun breaksAfter(c: Int) = if (c < 0x80) c > 0x20 && c.toChar() !in NO_BREAK_AFTER else Character.isLetterOrDigit(c)

/**
 * `word-break: break-all`: a [BREAK] wherever Chrome may end a line inside a word, which no
 * Android line breaker offers. Spaces and everything else are left to the line breaker.
 */
internal fun breakAnywhere(text: String): String = buildString(text.length * 2) {
    var previous = -1
    var i = 0
    while (i < text.length) {
        val c = text.codePointAt(i)
        if (previous >= 0 && breaksAfter(previous) && breaksBefore(c)) append(BREAK)
        appendCodePoint(c)
        previous = c
        i += Character.charCount(c)
    }
}

/** [content] copied to the clipboard without the [BREAK]s [breakAnywhere] put in it: a pasted URL has to be the URL. */
@Composable
internal fun WithoutBreaksOnCopy(enabled: Boolean, content: @Composable () -> Unit) {
    if (!enabled) return content()
    val clipboard = LocalClipboard.current
    val unbroken = remember(clipboard) { UnbrokenClipboard(clipboard) }
    CompositionLocalProvider(LocalClipboard provides unbroken, content = content)
}

private class UnbrokenClipboard(private val inner: Clipboard) : Clipboard by inner {
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val data = clipEntry?.clipData
        val text = data?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.text
        if (text == null || BREAK !in text) return inner.setClipEntry(clipEntry)
        inner.setClipEntry(ClipEntry(ClipData.newPlainText(data.description.label, text.toString().replace(BREAK.toString(), ""))))
    }
}
