package com.hienlh.ppm.design.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import kotlinx.coroutines.delay
import kotlin.math.ceil

/**
 * 8-second countdown state machine for [UndoBar] (Requirement 9.4, `design.md` UndoBar 8 s).
 *
 * Pure JVM-testable state holder: call [show] to start or restart the 8 s window, [tick] to
 * advance time, [triggerUndo] when the user taps Undo, or [dismiss] to hide immediately.
 */
@Stable
class UndoBarTimer(
    val defaultDurationMs: Long = DEFAULT_DURATION_MS,
    private val onUndo: (Any?) -> Unit = {},
    private val onExpire: (Any?) -> Unit = {},
) {
    var active: Boolean by mutableStateOf(false)
        private set

    var message: String by mutableStateOf("")
        private set

    var actionLabel: String by mutableStateOf("Undo")
        private set

    var totalDurationMs: Long by mutableLongStateOf(defaultDurationMs)
        private set

    var remainingMs: Long by mutableLongStateOf(0L)
        private set

    var payload: Any? by mutableStateOf(null)
        private set

    /** Whole seconds remaining (`8` down to `1`, `0` when inactive or expired). */
    val remainingSeconds: Int
        get() = if (!active || remainingMs <= 0L) 0 else ceil(remainingMs / 1000.0).toInt()

    /** Fraction of the countdown window remaining (`1.0f` at start, `0.0f` when expired). */
    val progress: Float
        get() = if (!active || totalDurationMs <= 0L) 0f else (remainingMs.toFloat() / totalDurationMs).coerceIn(0f, 1f)

    /** Starts (or restarts) the countdown bar with [message] for [durationMs] (default 8 000 ms). */
    fun show(
        message: String,
        actionLabel: String = "Undo",
        durationMs: Long = defaultDurationMs,
        payload: Any? = null,
    ) {
        val clamped = durationMs.coerceAtLeast(0L)
        this.message = message
        this.actionLabel = actionLabel
        this.totalDurationMs = clamped
        this.remainingMs = clamped
        this.payload = payload
        this.active = clamped > 0L
        if (clamped == 0L) {
            this.payload = null
            onExpire(payload)
        }
    }

    /**
     * Advances the countdown by [deltaMs]. Returns `true` if the timer expired on this tick.
     */
    fun tick(deltaMs: Long): Boolean {
        if (!active || deltaMs <= 0L) return false
        val next = (remainingMs - deltaMs).coerceAtLeast(0L)
        remainingMs = next
        if (next == 0L) {
            val expiredPayload = payload
            active = false
            payload = null
            onExpire(expiredPayload)
            return true
        }
        return false
    }

    /**
     * Invokes the Undo callback and closes the bar without firing [onExpire].
     * Returns `true` if the bar was active.
     */
    fun triggerUndo(): Boolean {
        if (!active) return false
        val currentPayload = payload
        active = false
        remainingMs = 0L
        payload = null
        onUndo(currentPayload)
        return true
    }

    /** Alias for [triggerUndo]. */
    fun triggerAction(): Boolean = triggerUndo()

    /** Dismisses the bar immediately without firing [onUndo] or [onExpire]. */
    fun dismiss() {
        active = false
        remainingMs = 0L
        payload = null
    }

    companion object {
        const val DEFAULT_DURATION_MS: Long = KitSpec.UNDO_BAR_DURATION_MS
        const val DEFAULT_SECONDS: Int = KitSpec.UNDO_BAR_SECONDS
        const val TICK_INTERVAL_MS: Long = 100L
    }
}

/**
 * Remembers an [UndoBarTimer] and drives its 8-second countdown while [UndoBarTimer.active] is true.
 */
@Composable
fun rememberUndoBarTimer(
    defaultDurationMs: Long = UndoBarTimer.DEFAULT_DURATION_MS,
    onUndo: (Any?) -> Unit = {},
    onExpire: (Any?) -> Unit = {},
): UndoBarTimer {
    val timer = remember(defaultDurationMs) {
        UndoBarTimer(
            defaultDurationMs = defaultDurationMs,
            onUndo = onUndo,
            onExpire = onExpire,
        )
    }
    LaunchedEffect(timer.active, timer.remainingMs) {
        if (timer.active && timer.remainingMs > 0L) {
            val step = minOf(UndoBarTimer.TICK_INTERVAL_MS, timer.remainingMs)
            delay(step)
            timer.tick(step)
        }
    }
    return timer
}

/**
 * Inverse-surface snackbar with an 8-second countdown and 40 dp action button inside a 48 dp touch
 * target (Foundation Sections 3.6 & 7).
 */
@Composable
fun UndoBar(
    timer: UndoBarTimer,
    modifier: Modifier = Modifier,
    showCountdown: Boolean = true,
) {
    if (!timer.active) return
    UndoBar(
        message = timer.message,
        actionLabel = timer.actionLabel,
        remainingSeconds = if (showCountdown) timer.remainingSeconds else null,
        onAction = { timer.triggerUndo() },
        modifier = modifier,
    )
}

/**
 * Stateless overload of [UndoBar] (52 dp min height, 14 dp radius, inverse surface, 40 dp action
 * button inside a 48 dp touch target).
 */
@Composable
fun UndoBar(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    remainingSeconds: Int? = KitSpec.UNDO_BAR_SECONDS,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(KitSpec.UndoBarRadius)
    val actionShape = RoundedCornerShape(10.dp)
    val bgColor = tokens.text.color
    val fgColor = tokens.panel2.color

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = KitSpec.UndoBarMinHeight)
            .ppmShadow(tokens.shadowFloat, shape)
            .clip(shape)
            .background(bgColor, shape)
            .padding(start = 14.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicText(
            text = message,
            style = ppmText(13.5f, FontWeight.Medium, lineHeight = 18f).copy(color = fgColor),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (remainingSeconds != null && remainingSeconds > 0) {
            BasicText(
                text = "${remainingSeconds}s",
                style = ppmText(12f, FontWeight.Normal, FontKind.MONO, lineHeight = 15f)
                    .copy(color = fgColor.copy(alpha = 0.72f)),
            )
        }

        Box(
            modifier = Modifier
                .minTouchTarget(minWidth = KitSpec.MinTouchTarget, minHeight = KitSpec.MinTouchTarget)
                .clip(actionShape)
                .ppmClickable(
                    shape = actionShape,
                    pressedColor = tokens.accent.opacity(0.22).color,
                    hoverColor = tokens.accent.opacity(0.14).color,
                    onClickLabel = actionLabel,
                    onClick = onAction,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .height(KitSpec.UndoBarActionHeight)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = actionLabel,
                    style = ppmText(13.5f, FontWeight.Bold, lineHeight = 17f).copy(color = tokens.accent.color),
                    maxLines = 1,
                )
            }
        }
    }
}
