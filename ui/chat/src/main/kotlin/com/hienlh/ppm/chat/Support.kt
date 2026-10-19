package com.hienlh.ppm.chat

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
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

internal fun linkedUserText(text: String, tokens: ThemeTokens, uriHandler: UriHandler): AnnotatedString {
    if (!text.contains("://")) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        val style = TextLinkStyles(SpanStyle(color = tokens.accent.color, textDecoration = TextDecoration.Underline))
        for (match in userUrl.findAll(text)) {
            val url = match.value
            addLink(LinkAnnotation.Url(url, style) { runCatching { uriHandler.openUri(url) } }, match.range.first, match.range.last + 1)
        }
    }
}
