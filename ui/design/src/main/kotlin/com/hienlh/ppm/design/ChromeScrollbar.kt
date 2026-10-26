package com.hienlh.ppm.design

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The scrollbar Chrome on Android draws for the web's global `scrollbar-width: thin;
 * scrollbar-color: var(--color-border) transparent`: a square thumb in [color], 4 px thick in
 * whole device pixels rounded down, always shown, drawn over the content along the far edge of
 * the box it scrolls — the bottom for [vertical] false, the end otherwise. Measured: 8 px at 2x
 * and 10 px at 2.625x, flush with the edges, no margin, no track (a track colour is not drawn
 * either), no fade, and no room taken from the content.
 *
 * Sized as Blink sizes it (`ScrollbarTheme`): the thumb as long as the track times the share of
 * the content in view, rounded, never shorter than it is thick, and placed at the scroll
 * position's share of the room left, truncated, never less than one pixel off the start once
 * scrolled. Goes before the scroll modifier, so it draws on the viewport rather than scrolling
 * with the content.
 *
 * [across] is the box's scroll the other way, if it has one: while both overflow, each bar stops
 * short of the corner they share by the other's thickness, as Blink's `ScrollCornerRect` makes it.
 * Chrome does not clip a scroller's own thumb to its rounded corners, it draws it over them: a
 * rounded box puts this before its `clip`, passing the [border] it has on every side, which the
 * thumb keeps inside.
 */
fun Modifier.chromeScrollbar(
    state: ScrollState,
    vertical: Boolean,
    color: Color,
    across: ScrollState? = null,
    border: Dp = 0.dp,
): Modifier = drawWithContent {
    drawContent()
    val max = state.maxValue
    if (max == Int.MAX_VALUE) return@drawWithContent
    val corner = across != null && across.maxValue in 1 until Int.MAX_VALUE
    chromeThumb(state.value.toFloat(), max.toFloat(), vertical, color, corner, cssBorderPx(border))
}

/**
 * [chromeScrollbar] for a lazy list, which knows only the items in view: those out of view are
 * taken to be as long as these are on average, as Compose for Desktop's scrollbar takes them, so
 * the thumb is Chrome's exactly once the first item and the last are both in view, and at the
 * very start or end whenever the list is.
 */
fun Modifier.chromeScrollbar(state: LazyListState, color: Color): Modifier = drawWithContent {
    drawContent()
    val info = state.layoutInfo
    val items = info.visibleItemsInfo
    if (items.isEmpty()) return@drawWithContent
    val first = items.first()
    val last = items.last()
    // An item's share of the list, the gap after it included.
    val average = (last.offset + last.size - first.offset + info.mainAxisItemSpacing).toFloat() / items.size
    // Where the content starts and ends, in the items' own offsets (the viewport's start is
    // `viewportStartOffset`), its padding included.
    val start = first.offset - info.beforeContentPadding - first.index * average
    val end = last.offset + last.size + info.afterContentPadding + (info.totalItemsCount - 1 - last.index) * average
    val max = end - start - (info.viewportEndOffset - info.viewportStartOffset)
    chromeThumb(info.viewportStartOffset - start, max, info.orientation == Orientation.Vertical, color)
}

/** The web's `overflow-y: auto` as Chrome on Android shows it: [verticalScroll] under [chromeScrollbar]. */
@Composable
fun Modifier.cssScrollY(state: ScrollState = rememberScrollState()): Modifier =
    chromeScrollbar(state, vertical = true, LocalPpmTokens.current.border.color).verticalScroll(state)

/** The web's `overflow-x: auto` as Chrome on Android shows it: [horizontalScroll] under [chromeScrollbar]. */
@Composable
fun Modifier.cssScrollX(state: ScrollState = rememberScrollState()): Modifier =
    chromeScrollbar(state, vertical = false, LocalPpmTokens.current.border.color).horizontalScroll(state)

/** [chromeScrollbar] for a lazy list, in the colour the web's global style gives every scrollbar. */
@Composable
fun Modifier.cssScrollbar(state: LazyListState): Modifier = chromeScrollbar(state, LocalPpmTokens.current.border.color)

private fun DrawScope.chromeThumb(position: Float, max: Float, vertical: Boolean, color: Color, corner: Boolean = false, border: Int = 0) {
    val thickness = floor(4.dp.toPx()).toInt()
    // The padding box, which is what scrolls.
    val width = size.width.roundToInt() - 2 * border
    val height = size.height.roundToInt() - 2 * border
    val visible = if (vertical) height else width
    val (at, length) = chromeThumb(position, max, visible, visible - if (corner) thickness else 0, thickness) ?: return
    if (vertical) {
        drawRect(color, Offset((border + width - thickness).toFloat(), (border + at).toFloat()), Size(thickness.toFloat(), length.toFloat()))
    } else {
        drawRect(color, Offset((border + at).toFloat(), (border + height - thickness).toFloat()), Size(length.toFloat(), thickness.toFloat()))
    }
}

/**
 * Where Chrome puts the thumb of a [track] px scrollbar [thickness] px thick over a [visible] px
 * view scrolled [position] px of [max]: its start and its length, or null when it draws none.
 */
internal fun chromeThumb(position: Float, max: Float, visible: Int, track: Int, thickness: Int): Pair<Int, Int>? {
    if (max <= 0f) return null
    val length = (visible / (visible + max) * track).roundToInt().coerceAtLeast(thickness)
    if (length > track) return null
    val exact = position.coerceIn(0f, max) * (track - length) / max
    return (if (exact > 0f && exact < 1f) 1 else exact.toInt()) to length
}
