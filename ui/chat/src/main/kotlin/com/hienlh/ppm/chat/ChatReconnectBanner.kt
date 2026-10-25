package com.hienlh.ppm.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ThemeShadow
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import kotlinx.coroutines.delay

/** What the chat shows while its socket is being re-established (`ReconnectStage`). */
private enum class ReconnectStage { HIDDEN, RECONNECTING, LOST }

/** A reconnect that lands within this window is never shown: returning to the app should not flash. */
private const val SHOW_DELAY_MS = 800L

/** Past this the connection is reported lost and a retry is offered. */
private const val LOST_AFTER_MS = 8_000L

/**
 * A small pill floating over the top of the chat while its socket reconnects
 * (chat-reconnect-banner.tsx). It covers nothing else: the messages stay readable and scrollable,
 * and what is sent meanwhile goes out once the socket is back.
 *
 * Shown only once [reconnecting] has lasted 800 ms, and as "Connection lost" with a Retry after
 * 8 s (`useReconnectIndicator`). Both clocks start again when [resumed] changes, the app having
 * come to the front: the web restarts them on `visibilitychange`, because timers frozen in the
 * background fire at once on return and would call a reconnect that just began lost.
 */
@Composable
fun BoxScope.ChatReconnectBanner(reconnecting: Boolean, resumed: Int, onRetry: () -> Unit) {
    var stage by remember { mutableStateOf(ReconnectStage.HIDDEN) }
    var retries by remember { mutableIntStateOf(0) }
    LaunchedEffect(reconnecting, resumed, retries) {
        stage = ReconnectStage.HIDDEN
        if (!reconnecting) return@LaunchedEffect
        delay(SHOW_DELAY_MS)
        stage = ReconnectStage.RECONNECTING
        delay(LOST_AFTER_MS - SHOW_DELAY_MS)
        stage = ReconnectStage.LOST
    }
    if (stage == ReconnectStage.HIDDEN) return

    val tokens = LocalPpmTokens.current
    val text = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color)
    // `pointer-events-none absolute inset-x-0 top-2 z-40 flex justify-center px-4`.
    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 16.dp), contentAlignment = Alignment.TopCenter) {
        // `pointer-events-auto flex items-center gap-2 rounded-full border border-border bg-surface/95
        // px-3 py-1 text-xs text-text-secondary shadow-sm`. The web blurs what is behind it too
        // (`backdrop-blur-sm`), which this leaves out.
        var pill = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        for (shadow in SHADOW_SM) pill = pill.ppmShadow(shadow, CircleShape)
        Row(
            pill
                // Takes the touches that land on it, as `pointer-events-auto` does, so none reach the transcript under it.
                .pointerInput(Unit) {}
                .background(tokens.panel.opacity(0.95).color, CircleShape)
                .cssBorder(tokens.border.color, CircleShape)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (stage == ReconnectStage.RECONNECTING) {
                PpmIcon(ProductIcon.Loader2, 14.dp, tokens.text2.color, spinning = true)
                BasicText("Reconnecting…", style = text)
            } else {
                PpmIcon(ProductIcon.WifiOff, 14.dp, tokens.error.color)
                BasicText("Connection lost", style = text)
                RetryButton {
                    retries++
                    onRetry()
                }
            }
        }
    }
}

/**
 * `-my-1 min-h-11 rounded-full px-2 font-medium text-primary hover:underline active:opacity-70
 * md:min-h-0 md:py-1`: on a phone 44 px tall but 36 px in the row, its text centred as a button
 * centres it; on a tablet 24 px, 16 in the row. Underlined under a mouse at once.
 */
@Composable
private fun RetryButton(onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val hover = rememberHover(instant = true)
    val pressed by hover.source.collectIsPressedAsState()
    Box(
        Modifier
            // `-my-1`: 4 px of it over the row's padding above and below.
            .layout { measurable, constraints ->
                val margin = 4.dp.roundToPx()
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height - 2 * margin) { placeable.place(0, -margin) }
            }
            .ppmClickable(pressedColor = Color.Transparent, source = hover.source, onClick = onClick)
            .alpha(if (pressed) 0.7f else 1f)
            .then(if (mobile) Modifier.heightIn(min = 44.dp) else Modifier.padding(vertical = 4.dp))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            "Retry",
            style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(
                color = tokens.accent.color,
                textDecoration = if (hover.lit > 0f) TextDecoration.Underline else null,
            ),
        )
    }
}

/** Tailwind's `shadow-sm`. */
private val SHADOW_SM = listOf(
    ThemeShadow(0.0, 1.0, 3.0, 0.0, Rgba(0.0, 0.0, 0.0, 0.1)),
    ThemeShadow(0.0, 1.0, 2.0, -1.0, Rgba(0.0, 0.0, 0.0, 0.1)),
)
