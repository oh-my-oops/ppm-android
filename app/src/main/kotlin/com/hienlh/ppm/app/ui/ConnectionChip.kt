package com.hienlh.ppm.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.state.RECONNECT_LOST_AFTER_MS
import com.hienlh.ppm.state.RECONNECT_SHOW_DELAY_MS
import com.hienlh.ppm.state.ReconnectStage
import com.hienlh.ppm.state.reconnectStageAt
import kotlinx.coroutines.delay

@Stable
data class ReconnectIndicatorState(
    val stage: ReconnectStage,
    val restart: () -> Unit,
)

/**
 * Turns a raw [isReconnecting] flag into [ReconnectStage] (`useReconnectIndicator` in
 * `use-reconnect-indicator.ts`), restarting both grace timers when [windowVisible] resumes or on Retry.
 */
@Composable
fun rememberReconnectIndicator(
    isReconnecting: Boolean,
    windowVisible: Boolean = true,
    showDelayMs: Long = RECONNECT_SHOW_DELAY_MS,
    lostAfterMs: Long = RECONNECT_LOST_AFTER_MS,
): ReconnectIndicatorState {
    var stage by remember { mutableStateOf(ReconnectStage.HIDDEN) }
    var epoch by remember { mutableIntStateOf(0) }
    val restart: () -> Unit = remember {
        {
            stage = ReconnectStage.HIDDEN
            epoch += 1
        }
    }

    var wasVisible by remember { mutableStateOf(windowVisible) }
    LaunchedEffect(windowVisible) {
        if (windowVisible && !wasVisible) {
            epoch++
        }
        wasVisible = windowVisible
    }

    LaunchedEffect(isReconnecting, showDelayMs, lostAfterMs, epoch) {
        stage = ReconnectStage.HIDDEN
        if (!isReconnecting) return@LaunchedEffect
        delay(showDelayMs)
        stage = reconnectStageAt(true, showDelayMs, showDelayMs, lostAfterMs)
        val remaining = (lostAfterMs - showDelayMs).coerceAtLeast(0L)
        delay(remaining)
        stage = reconnectStageAt(true, lostAfterMs, showDelayMs, lostAfterMs)
    }

    return ReconnectIndicatorState(stage = stage, restart = restart)
}

/**
 * The chat's connection status in the footer's context row (`ConnectionChip` in `connection-chip.tsx`):
 * silent while connected, spinning after the grace period, red "Connection lost · Retry" once lost.
 */
@Composable
fun ConnectionChip(
    stage: ReconnectStage,
    narrow: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (stage == ReconnectStage.HIDDEN) return

    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val reducedMotion = rememberPrefersReducedMotion()
    val hideWords = narrow || mobile

    if (stage == ReconnectStage.RECONNECTING) {
        val spinAngle = if (!reducedMotion) {
            val transition = rememberInfiniteTransition(label = "reconnectSpin")
            val angle by transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
                label = "reconnectSpinAngle",
            )
            angle
        } else {
            0f
        }
        FooterChip(
            narrow = narrow,
            modifier = modifier,
            title = "Reconnecting — anything you send now goes out once the connection is back",
            contentDescription = "Reconnecting",
            testTag = "connection-chip:reconnecting",
            onClick = null,
        ) {
            PpmIcon(ProductIcon.Loader2, 14.dp, LocalFooterContentColor.current, Modifier.rotate(spinAngle))
            if (!hideWords) {
                BasicText(
                    "Reconnecting…",
                    style = LocalFooterTextStyle.current,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        return
    }

    FooterChip(
        narrow = narrow,
        modifier = modifier,
        tone = FooterChipTone.Danger,
        title = "Connection lost — tap to retry",
        contentDescription = "Connection lost — tap to retry",
        testTag = "connection-chip:lost",
        onClick = onRetry,
    ) {
        PpmIcon(ProductIcon.WifiOff, 14.dp, tokens.error.color)
        BasicText(
            if (hideWords) "Offline" else "Connection lost",
            style = LocalFooterTextStyle.current,
            maxLines = 1,
            softWrap = false,
        )
        BasicText(
            "·",
            style = LocalFooterTextStyle.current.copy(color = tokens.error.opacity(0.60).color),
            maxLines = 1,
            softWrap = false,
        )
        BasicText(
            "Retry",
            style = LocalFooterTextStyle.current.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            softWrap = false,
        )
    }
}
