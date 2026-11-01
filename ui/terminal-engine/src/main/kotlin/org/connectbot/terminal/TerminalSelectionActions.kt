package org.connectbot.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import kotlin.math.roundToInt

/** Inclusive terminal rows; unlike xterm's end position, the last cell is selected. */
internal fun terminalSelectionVisible(firstRow: Int, lastRow: Int, rows: Int): Boolean =
    rows > 0 && firstRow <= lastRow && lastRow >= 0 && firstRow < rows

/** Physical pixels in the terminal content box. Match web visibility, retaining handle clearance. */
internal fun terminalSelectionActionsPosition(
    firstRow: Int,
    lastRow: Int,
    rows: Int,
    anchorX: Float,
    cellHeight: Float,
    clearance: Float,
    availableWidth: Int,
    availableHeight: Int,
    actionsWidth: Int,
    actionsHeight: Int,
): IntOffset? {
    if (!terminalSelectionVisible(firstRow, lastRow, rows) ||
        availableWidth <= 0 || availableHeight <= 0 || cellHeight <= 0f) return null
    val maxX = (availableWidth - actionsWidth).coerceAtLeast(0)
    val maxY = (availableHeight - actionsHeight).coerceAtLeast(0)
    val below = (lastRow.coerceAtMost(rows - 1) + 1) * cellHeight + clearance
    val above = firstRow.coerceAtLeast(0) * cellHeight - actionsHeight - clearance
    val y = if (below <= maxY) below else if (above >= 0f) above else maxY.toFloat()
    return IntOffset(anchorX.roundToInt().coerceIn(0, maxX), y.roundToInt().coerceIn(0, maxY))
}

/** Measure and place in the same layout pass, so custom actions never flash at a guessed size. */
@Composable
internal fun TerminalSelectionActions(
    firstRow: Int,
    lastRow: Int,
    rows: Int,
    anchorX: Float,
    cellHeight: Float,
    handleHeight: Float,
    gap: Float,
    availableWidth: Int,
    availableHeight: Int,
    content: @Composable () -> Unit,
) {
    // Remove offscreen actions from hit testing and accessibility, without clearing selection.
    if (!terminalSelectionVisible(firstRow, lastRow, rows) ||
        availableWidth <= 0 || availableHeight <= 0) return
    Layout(modifier = Modifier.clipToBounds(), content = { Box { content() } }) { measurables, constraints ->
        val width = constraints.constrainWidth(availableWidth)
        val height = constraints.constrainHeight(availableHeight)
        val actions = measurables.single().measure(Constraints(maxWidth = width, maxHeight = height))
        val position = terminalSelectionActionsPosition(
            firstRow, lastRow, rows, anchorX, cellHeight, handleHeight + gap,
            width, height, actions.width, actions.height,
        )
        layout(width, height) {
            // Terminal columns are physical left-to-right coordinates even in an RTL app locale.
            if (position != null) actions.place(position.x, position.y)
        }
    }
}
