package org.connectbot.terminal

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * use-terminal-touch-selection.ts: in select mode a one-finger drag selects what xterm's mouse drag selects, from
 * the cell the finger came down on to the one it is on, both included. The engine's pointer loop drives the
 * manager exactly as [drag] does (begin on the first move past the touch slop, `updateSelection` on every move,
 * `endSelection` on lift), which is why the selection it leaves is read through the same calls Copy uses.
 */
class TerminalTouchSelectionTest {
    private fun line(row: Int, text: String) = TerminalLine(
        row, text.map { TerminalLine.Cell(it, fgColor = Color.White, bgColor = Color.Black) },
    )

    private val snapshot = TerminalSnapshot.empty(3, 10).copy(
        lines = listOf(line(0, "abcdefghij"), line(1, "klmnopqrst"), line(2, "uvwxyz    ")),
    )

    private fun drag(manager: SelectionManager, from: Pair<Int, Int>, vararg through: Pair<Int, Int>) {
        manager.beginDragSelection(from.first, from.second, snapshot, 0)
        for ((row, col) in through) {
            manager.updateSelection(row, col)
            manager.adjustSelectionForMode(snapshot.cols, snapshot, 0)
        }
        manager.endSelection()
    }

    @Test
    fun `a drag selects from the cell it came down on to the cell it is on, both included`() {
        val manager = SelectionManager()
        drag(manager, 0 to 2, 0 to 4, 1 to 4)
        assertEquals("cdefghij\nklmno", manager.getSelectedText(snapshot))
    }

    @Test
    fun `a drag up and to the left selects the same text as the drag back down`() {
        val manager = SelectionManager()
        drag(manager, 1 to 4, 1 to 1, 0 to 2)
        assertEquals("cdefghij\nklmno", manager.getSelectedText(snapshot))
    }

    @Test
    fun `a drag along one row selects its cells`() {
        val manager = SelectionManager()
        drag(manager, 0 to 3, 0 to 6)
        assertEquals("defg", manager.getSelectedText(snapshot))
    }

    @Test
    fun `lifting the finger ends the drag and keeps the selection for Copy`() {
        val manager = SelectionManager()
        manager.beginDragSelection(0, 1, snapshot, 0)
        assertTrue("the drag is still extending it", manager.isSelecting)
        manager.updateSelection(0, 3)
        manager.endSelection()
        assertFalse(manager.isSelecting)
        assertEquals(SelectionMode.CHARACTER, manager.mode)
        assertEquals("bcd", manager.getSelectedText(snapshot))
    }

    @Test
    fun `a new drag replaces a selection in any mode, as a mousedown does`() {
        val manager = SelectionManager()
        manager.startSelection(0, 5, snapshot.cols, SelectionMode.LINE, snapshot, 0)
        assertEquals(SelectionMode.LINE, manager.mode)

        drag(manager, 2 to 1, 2 to 3)

        assertEquals(SelectionMode.CHARACTER, manager.mode)
        assertEquals("vwx", manager.getSelectedText(snapshot))
    }

    @Test
    fun `the first selection is one cell until the finger moves, so it is never a selection from the cursor`() {
        val manager = SelectionManager()
        manager.beginDragSelection(1, 6, snapshot, 0)
        assertEquals("q", manager.getSelectedText(snapshot))
    }

    @Test
    fun `only one finger selects`() {
        assertTrue(dragSelects(touchSelect = true, singleTouch = true))
        assertFalse("a second finger is the web's multi-touch, which it ignores", dragSelects(touchSelect = true, singleTouch = false))
        assertFalse("select mode off leaves a drag to scroll", dragSelects(touchSelect = false, singleTouch = true))
    }
}
