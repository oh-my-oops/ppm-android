package com.hienlh.ppm.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a context menu's box goes for a press, in device pixels. */
class ContextMenuPlacementTest {
    private val window = IntSize(1000, 800)
    private val box = IntSize(200, 300)

    @Test
    fun `a press in the window is where the menu opens, with the popup parent's own place left out`() {
        // An explorer row far down a floating window reports `positionInWindow()` plus the pointer.
        // The window's parent is at 150,100 in the screen, which the press already counts.
        assertEquals(IntOffset(552, 400), contextMenuOffset(Offset(550f, 400f), IntOffset.Zero, window, box, gap = 2))
    }

    @Test
    fun `a press within the parent is moved by the parent's place`() {
        assertEquals(IntOffset(552, 400), contextMenuOffset(Offset(400f, 300f), IntOffset(150, 100), window, box, gap = 2))
    }

    @Test
    fun `with no room on the right it opens on the left of the press`() {
        assertEquals(IntOffset(698, 100), contextMenuOffset(Offset(900f, 100f), IntOffset.Zero, window, box, gap = 2))
    }

    @Test
    fun `it is kept on the screen at the bottom, and one taller than the screen starts at the top`() {
        assertEquals(500, contextMenuOffset(Offset(100f, 700f), IntOffset.Zero, window, box, gap = 2).y)
        assertEquals(0, contextMenuOffset(Offset(100f, 700f), IntOffset.Zero, window, IntSize(200, 900), gap = 2).y)
    }
}
