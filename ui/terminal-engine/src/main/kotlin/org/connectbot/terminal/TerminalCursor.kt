package org.connectbot.terminal

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect

/** Full cell bounds, independent of cursor shape/blink; visualCol already accounts for bidi. */
internal fun terminalCursorCellBounds(row: Int, visualCol: Int, columns: Int, width: Float, height: Float) =
    Rect(visualCol * width, row * height, (visualCol + columns.coerceAtLeast(1)) * width, (row + 1) * height)

/** Redraw the actual cell through the cursor clip, including shaped text and wide glyphs. */
internal fun DrawScope.drawTerminalCursor(
    line: TerminalLine, row: Int, col: Int, visualCol: Int, shape: CursorShape,
    width: Float, height: Float, baseline: Float, paint: TerminalTextPaint,
    underlinePaint: Paint, background: Color, cursorColor: Color, cursorAccent: Color,
) {
    val rect = cursorBounds(row, visualCol, line.cells.width(col).coerceAtLeast(1), width, height, shape,
        paint.resolvedRtl(line.cells, col, width))
    drawRect(cursorColor, Offset(rect.left, rect.top), Size(rect.width(), rect.height()))
    if (shape != CursorShape.BLOCK) return
    clipRect(rect.left, rect.top, rect.right, rect.bottom) {
        drawLine(line.copy(images = emptyList()), row, width, height, baseline, paint, underlinePaint,
            cursorAccent, background, null, backgroundsOnly = false, foregroundOverride = cursorAccent)
    }
}
