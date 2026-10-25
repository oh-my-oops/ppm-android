package com.hienlh.ppm.chat

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.CssColumn
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFormat
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.TruncatedText
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.protocol.BackgroundShell

/** Tailwind's `emerald-400` and `emerald-500`, as Chrome draws their `oklch()` in sRGB. */
private val EMERALD_400 = Rgba(0.0, 212.31 / 255, 145.95 / 255, 1.0)
val EMERALD_500 = Rgba(0.0, 188.39 / 255, 124.77 / 255, 1.0)

/**
 * The session's background commands not stopped yet, pinned at the top of the chat
 * (`background-command-bar.tsx`): one row each — a green dot pinging while it runs, a spinner
 * while it is being stopped — with its command, a button for its output and one to stop it.
 * Nothing while none runs.
 */
@Composable
fun BackgroundCommandBar(shells: List<BackgroundShell>, onViewOutput: (BackgroundShell) -> Unit, onStop: (BackgroundShell) -> Unit) {
    val active = shells.filter { !it.isStopped }
    if (active.isEmpty()) return
    val tokens = LocalPpmTokens.current
    // `shrink-0 border-b border-border bg-surface-elevated/60 px-2 py-1.5 space-y-1`.
    CssColumn(
        Modifier.fillMaxWidth().background(tokens.panel2.opacity(0.6).color).cssBorder(tokens.border.color, sides = CssSides.Bottom).padding(horizontal = 8.dp),
        gap = 4.dp,
        top = 6.dp,
        bottom = 6.dp,
    ) {
        active.forEach { shell -> CommandRow(shell, { onViewOutput(shell) }, { onStop(shell) }) }
    }
}

/** `text-xs` in the mono face: 12 on 16. */
private val commandText = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f)

/** `text-[11px]` under `text-xs`'s line height of 4/3. */
private val stoppingText = ppmText(11f, lineHeight = 44f / 3)

/** `flex items-center gap-2 text-xs`, as tall as its 32dp buttons. */
@Composable
private fun CommandRow(shell: BackgroundShell, onViewOutput: () -> Unit, onStop: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (shell.isStopping) PpmIcon(ProductIcon.Loader2, 14.dp, tokens.warning.color, spinning = true) else PingDot()
        PpmIcon(ProductIcon.TerminalSquare, 14.dp, tokens.text2.color)
        // `flex-1 truncate font-mono`: one line, its white space run together as `nowrap` runs it.
        TruncatedText(AnnotatedString(PpmFormat.collapsedWhitespace(shell.command)), commandText.copy(color = tokens.text.color), Modifier.weight(1f).webTitle(shell.command))
        if (shell.isStopping) BasicText("stopping…", style = stoppingText.copy(color = tokens.warning.color), maxLines = 1, softWrap = false)
        BarButton(ProductIcon.Eye, 16.dp, tokens.text, "View output", enabled = true, onClick = onViewOutput)
        BarButton(ProductIcon.Square, 14.dp, tokens.error, "Stop command", enabled = !shell.isStopping, onClick = onStop)
    }
}

/**
 * `size-8 rounded hover:bg-surface text-text-secondary transition-colors`, lit to [lit] under the
 * pointer. A disabled one is at 40% and still lit by a mouse, as `:hover` matches a disabled button.
 */
@Composable
private fun BarButton(icon: ProductIcon, size: Dp, lit: Rgba, label: String, enabled: Boolean, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover(enabled = true)
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .size(32.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .webTitle(label)
            .ppmClickable(
                enabled = enabled,
                pressedColor = tokens.panel.color,
                hoverColor = tokens.panel.color,
                shape = shape,
                onClickLabel = label,
                source = hover.source,
                hoverWhileDisabled = true,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(icon, size, lit.mix(tokens.text2, hover.lit.toDouble()).color)
    }
}

/**
 * `relative flex size-2`: an emerald-500 dot under an emerald-400 one at 60% that pings
 * (`animate-ping`: to twice the size and nothing by three quarters of each second).
 */
@Composable
private fun PingDot() {
    val ping by rememberInfiniteTransition(label = "ping").animateFloat(
        0f, 1f,
        infiniteRepeatable(
            keyframes {
                durationMillis = 1_000
                0f at 0 using CubicBezierEasing(0f, 0f, 0.2f, 1f)
                1f at 750
            },
        ),
        label = "ping",
    )
    Canvas(Modifier.size(8.dp)) {
        val radius = size.minDimension / 2
        drawCircle(EMERALD_400.opacity(0.6 * (1.0 - ping)).color, radius * (1 + ping))
        drawCircle(EMERALD_500.color, radius)
    }
}
