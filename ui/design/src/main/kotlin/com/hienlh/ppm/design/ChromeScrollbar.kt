package com.hienlh.ppm.design

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The scrollbar Chrome on Android draws for the web's global `scrollbar-width: thin;
 * scrollbar-color: var(--color-border) transparent`: a square thumb in [color], 4 px thick in
 * whole device pixels rounded down, always shown, drawn over the content along the far edge of
 * the box it scrolls — the bottom for [vertical] false, the end otherwise. Measured: 10 px at
 * 2.625x, flush with the edges, no margin and no fade.
 *
 * Sized as Blink sizes it (`ScrollbarTheme`): the thumb as long as the track times the share of
 * the content in view, rounded, and placed at the scroll position's share of the room left,
 * truncated, never less than one pixel off the start once scrolled. Goes before the scroll
 * modifier, so it draws on the viewport rather than scrolling with the content.
 */
fun Modifier.chromeScrollbar(state: ScrollState, vertical: Boolean, color: Color): Modifier = drawWithContent {
    drawContent()
    val max = state.maxValue
    if (max <= 0 || max == Int.MAX_VALUE) return@drawWithContent
    val thickness = floor(4.dp.toPx())
    val track = (if (vertical) size.height else size.width).roundToInt()
    val length = (track.toFloat() * track / (track + max)).roundToInt().coerceAtLeast((2 * thickness).toInt())
    if (length >= track) return@drawWithContent
    val exact = state.value.toFloat() * (track - length) / max
    val at = if (exact > 0f && exact < 1f) 1 else exact.toInt()
    if (vertical) {
        drawRect(color, Offset(size.width - thickness, at.toFloat()), Size(thickness, length.toFloat()))
    } else {
        drawRect(color, Offset(at.toFloat(), size.height - thickness), Size(length.toFloat(), thickness))
    }
}
