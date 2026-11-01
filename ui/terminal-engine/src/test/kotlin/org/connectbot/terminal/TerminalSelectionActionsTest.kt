package org.connectbot.terminal

import androidx.compose.ui.unit.IntOffset
import org.junit.Assert.*
import org.junit.Test

class TerminalSelectionActionsTest {
    private fun position(
        first: Int = 1,
        last: Int = 2,
        anchor: Float = 40f,
        width: Int = 120,
        height: Int = 40,
        viewportWidth: Int = 300,
        viewportHeight: Int = 200,
    ) = terminalSelectionActionsPosition(
        first, last, 10, anchor, 20f, 16f, viewportWidth, viewportHeight, width, height,
    )

    @Test fun `actual action dimensions determine edge clamping and above placement`() {
        assertEquals(IntOffset(40, 76), position())
        assertEquals(IntOffset(180, 76), position(anchor = 290f))
        assertEquals(IntOffset(50, 76), position(anchor = 290f, width = 250))
        // A taller custom bubble must move above, rather than using the legacy 48dp height.
        assertEquals(IntOffset(40, 14), position(first = 5, last = 6, height = 70))
        // Above the first selected row, not the final row where it would obscure the selection.
        assertEquals(IntOffset(40, 44), position(first = 5, last = 9))
    }

    @Test fun `entirely offscreen selection hides actions but partial selection remains`() {
        assertNull(position(first = -5, last = -1))
        assertNull(position(first = 10, last = 15))
        assertEquals(IntOffset(40, 56), position(first = -5, last = 1))
        assertEquals(IntOffset(40, 104), position(first = 8, last = 15))
        assertEquals(IntOffset(40, 160), position(first = -5, last = 15))
        // Inclusive end at column zero still selects a cell on that row.
        assertEquals(IntOffset(0, 36), position(first = -1, last = 0, anchor = 0f))
    }

    @Test fun `tiny viewport and content resize keep actions within available bounds`() {
        assertEquals(IntOffset(0, 0), position(width = 30, height = 20, viewportWidth = 30, viewportHeight = 20))
        assertEquals(IntOffset(0, 160), position(first = 0, last = 9, anchor = -10f))
        assertNull(position(viewportWidth = 0))
        assertNull(position(viewportHeight = 0))
        assertNull(position(first = 4, last = 3))
    }
}
