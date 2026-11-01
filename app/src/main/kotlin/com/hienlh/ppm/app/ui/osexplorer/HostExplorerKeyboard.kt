package com.hienlh.ppm.app.ui.osexplorer

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.unit.Density
import com.hienlh.ppm.state.osexplorer.HostKey
import com.hienlh.ppm.state.osexplorer.HostKeyPress

/** [event] as the explorer's key table reads it: the key, the character it types, and the modifiers (Command counts as Control). */
internal fun hostKeyPress(event: KeyEvent): HostKeyPress {
    val typed = event.nativeKeyEvent.unicodeChar
    return HostKeyPress(
        key = when (event.key) {
            Key.DirectionUp -> HostKey.UP
            Key.DirectionDown -> HostKey.DOWN
            Key.DirectionLeft -> HostKey.LEFT
            Key.DirectionRight -> HostKey.RIGHT
            Key.MoveHome -> HostKey.HOME
            Key.MoveEnd -> HostKey.END
            Key.Backspace -> HostKey.BACKSPACE
            Key.Enter, Key.NumPadEnter -> HostKey.ENTER
            Key.F2 -> HostKey.F2
            Key.Delete -> HostKey.DELETE
            Key.Escape -> HostKey.ESCAPE
            Key.A -> HostKey.A
            Key.C -> HostKey.C
            Key.X -> HostKey.X
            Key.V -> HostKey.V
            else -> HostKey.OTHER
        },
        // A control character (Enter's 10, Escape's 27, Backspace's 8, Delete's 127) is a key, not a letter to type ahead.
        char = typed.takeIf { it in 32..0xFFFF && it != 127 }?.toChar(),
        ctrl = event.isCtrlPressed || event.isMetaPressed,
        shift = event.isShiftPressed,
        alt = event.isAltPressed,
    )
}

/** How many columns the Icons view last laid out, for the arrow keys to step a row by. Written while the grid measures, so not state. */
internal class HostGridColumns {
    var count = 1
}

/** [inner] as it is, noting in [columns] how many columns it made of the width it was given. */
internal class HostRecordedCells(private val inner: GridCells, private val columns: HostGridColumns) : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> =
        with(inner) { calculateCrossAxisCellSizes(availableSize, spacing) }.also { columns.count = it.size.coerceAtLeast(1) }
}
