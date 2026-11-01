package com.hienlh.ppm.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.LocalPpmTokens
import kotlin.math.round

/** Pointer cancellation commits too, matching the web range input. Focused arrows stay here. */
@Composable
internal fun MediaSlider(
    label: String, value: Double, maximum: Double, step: Double, modifier: Modifier = Modifier,
    buffered: Double = 0.0, enabled: Boolean = true,
    onChange: (Double) -> Unit, onCommit: () -> Unit = {},
) {
    val colors = LocalPpmTokens.current
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    fun snap(v: Double) = (round(v / step) * step).coerceIn(0.0, maximum)
    Canvas(modifier.height(44.dp)
        .semantics {
            contentDescription = label
            progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), 0f..maximum.toFloat())
            if (!enabled) disabled()
            setProgress { if (enabled) { change(snap(it.toDouble())); commit(); true } else false }
        }
        .onKeyEvent {
            val delta = when (it.key) { Key.DirectionLeft, Key.DirectionDown -> -step; Key.DirectionRight, Key.DirectionUp -> step; else -> null }
            val target = when (it.key) { Key.MoveHome -> 0.0; Key.MoveEnd -> maximum; else -> delta?.let { d -> current + d } }
            if (target == null) false else {
                if (enabled) { if (it.type == KeyEventType.KeyDown) change(snap(target)) else if (it.type == KeyEventType.KeyUp) commit() }
                true
            }
        }.focusable(enabled)
        .pointerInput(maximum, step, enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown()
                val inset = 6.dp.toPx()
                fun fromX(x: Float) = snap(((x - inset) / (size.width - 2 * inset).coerceAtLeast(1f)).toDouble() * maximum)
                down.consume()
                change(fromX(down.position.x))
                try { drag(down.id) { it.consume(); change(fromX(it.position.x)) } }
                finally { commit() }
            }
        }) {
        val inset = 6.dp.toPx()
        val start = Offset(inset, size.height / 2)
        val end = Offset((size.width - inset).coerceAtLeast(inset), start.y)
        fun at(v: Double) = Offset(start.x + (end.x - start.x) * (v / maximum).coerceIn(0.0, 1.0).toFloat(), start.y)
        drawLine(colors.border.color, start, end, 4.dp.toPx(), StrokeCap.Round)
        if (buffered > 0) drawLine(colors.text3.color, start, at(buffered), 4.dp.toPx(), StrokeCap.Round)
        drawLine(if (enabled) colors.accent.color else colors.text3.color, start, at(value), 4.dp.toPx(), StrokeCap.Round)
        drawCircle(if (enabled) colors.accent.color else colors.text3.color, 6.dp.toPx(), at(value))
    }
}
