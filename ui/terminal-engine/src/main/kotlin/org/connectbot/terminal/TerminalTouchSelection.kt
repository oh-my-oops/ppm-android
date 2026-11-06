package org.connectbot.terminal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Select mode, as the web's `useTerminalTouchSelection` has it: while it is on, a one-finger drag is a selection, as a
 * mouse drag is in xterm, instead of a scroll. The web ignores a second finger (`e.touches.length > 1`), so does this.
 */
internal fun dragSelects(touchSelect: Boolean, singleTouch: Boolean): Boolean = touchSelect && singleTouch

/**
 * The first half of that selection: the cell the finger came down on, as a character selection. What was selected
 * is replaced whatever mode it was in, as xterm's `mousedown` replaces it; the drag then extends this one with
 * [SelectionManager.updateSelection], and lifting the finger ends it with [SelectionManager.endSelection].
 */
internal fun SelectionManager.beginDragSelection(row: Int, col: Int, snapshot: TerminalSnapshot, scrollbackPosition: Int) =
    startSelection(row, col, snapshot.cols, SelectionMode.CHARACTER, snapshot, scrollbackPosition)

/** xterm.js `SelectionService` drag-scroll cadence and geometry (`DRAG_SCROLL_INTERVAL`, `DRAG_SCROLL_MAX_THRESHOLD`, `DRAG_SCROLL_MAX_SPEED`). */
internal const val SELECTION_AUTO_SCROLL_INTERVAL_MS = 50L
internal val SELECTION_AUTO_SCROLL_MAX_THRESHOLD = 50.dp
internal const val SELECTION_AUTO_SCROLL_MAX_SPEED = 15

/**
 * How many rows `screenState.scrollBy` moves on one 50 ms drag-scroll tick when the pointer is at [y] in a terminal [height] px tall,
 * matching xterm.js's `SelectionService._getMouseEventScrollAmount`. Inside `0..height` nothing scrolls (no edge band inside the
 * viewport); above the top (`y < 0`) returns `+1..+15` (into history), and below the bottom (`y > height`) returns `-1..-15` (towards
 * the live bottom), using JavaScript's `Math.round(x) = floor(x + 0.5)`.
 */
internal fun selectionAutoScrollRows(y: Float, height: Float, maxThresholdPx: Float): Int {
    if (height <= 0f || maxThresholdPx <= 0f || (y >= 0f && y <= height)) return 0
    val rawOffset = if (y > height) y - height else y
    val offset = rawOffset.coerceIn(-maxThresholdPx, maxThresholdPx) / maxThresholdPx
    val sign = if (offset < 0f) -1 else 1
    val dragScrollAmount = sign + floor(offset * (SELECTION_AUTO_SCROLL_MAX_SPEED - 1) + 0.5f).toInt()
    return -dragScrollAmount
}

/**
 * Clamps the selection endpoint to the start of the top visible row when dragging above the viewport (`y < 0`) and to the end of the
 * bottom visible row when dragging below it (`y > height`), matching `SelectionService._dragScroll`.
 */
internal fun selectionAutoScrollPoint(raw: Offset, width: Float, height: Float): Offset = when {
    raw.y < 0f -> Offset(0f, 0f)
    raw.y > height -> Offset(width, height)
    else -> raw
}

/**
 * Whole rows of touch scroll in [accumulatedPx] (`-dragAmount.y`, positive when the finger moves up to scroll down into the document)
 * and the remainder left for the next move, matching xterm.js's `Math.trunc(accumulator / cellHeight)` in `MouseService`.
 */
internal fun touchScrollLines(accumulatedPx: Float, cellHeight: Float): Pair<Int, Float> {
    if (cellHeight <= 0f) return 0 to 0f
    val lines = (accumulatedPx / cellHeight).toInt()
    return lines to (accumulatedPx - lines * cellHeight)
}

/**
 * Signed wheel steps for a `PointerEventType.Scroll` vertical delta (`> 0` wheel down, `< 0` wheel up). Each physical wheel notch has
 * magnitude `1.0f`; non-zero fractional trackpad deltas still step at least one notch in their direction.
 */
internal fun wheelScrollSteps(scrollDeltaY: Float): Int {
    if (scrollDeltaY == 0f) return 0
    val count = abs(scrollDeltaY).roundToInt().coerceAtLeast(1)
    return if (scrollDeltaY > 0f) count else -count
}

/**
 * How many rows `screenState.scrollBy` moves for a mouse wheel event on the primary screen: positive scrolls up into history (`scrollDeltaY < 0`),
 * negative scrolls down towards the live prompt (`scrollDeltaY > 0`), 3 rows per notch and 15 with Alt held (`fastScrollSensitivity = 5`).
 */
internal fun wheelScrollbackLines(scrollDeltaY: Float, altPressed: Boolean): Int =
    -wheelScrollSteps(scrollDeltaY) * (if (altPressed) 15 else 3)
