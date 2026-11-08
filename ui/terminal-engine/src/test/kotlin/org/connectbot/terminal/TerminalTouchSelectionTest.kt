package org.connectbot.terminal

import androidx.compose.ui.geometry.Offset
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

    @Test
    fun `selection auto-scroll stays still inside the viewport and matches xterm outside it`() {
        // Inside 0..height (including the 40 dp edge band termlib used to scroll in): 0 rows.
        assertEquals(0, selectionAutoScrollRows(y = 0f, height = 500f, maxThresholdPx = 100f))
        assertEquals(0, selectionAutoScrollRows(y = 20f, height = 500f, maxThresholdPx = 100f))
        assertEquals(0, selectionAutoScrollRows(y = 480f, height = 500f, maxThresholdPx = 100f))
        assertEquals(0, selectionAutoScrollRows(y = 500f, height = 500f, maxThresholdPx = 100f))

        // Above the top edge (y < 0): positive rows into history, up to 15 rows/tick at 50 dp (100 px at 2x density).
        assertEquals(1, selectionAutoScrollRows(y = -1f, height = 500f, maxThresholdPx = 100f))
        assertEquals(2, selectionAutoScrollRows(y = -6f, height = 500f, maxThresholdPx = 100f))
        // At y = -25 (-0.25 * 14 = -3.5), JS Math.round(-3.5) is -3 -> -(-1 + -3) = 4.
        assertEquals(4, selectionAutoScrollRows(y = -25f, height = 500f, maxThresholdPx = 100f))
        assertEquals(8, selectionAutoScrollRows(y = -48f, height = 500f, maxThresholdPx = 100f))
        assertEquals(15, selectionAutoScrollRows(y = -100f, height = 500f, maxThresholdPx = 100f))
        assertEquals(15, selectionAutoScrollRows(y = -300f, height = 500f, maxThresholdPx = 100f))

        // Below the bottom edge (y > height): negative rows towards the live bottom, down to -15 rows/tick.
        assertEquals(-1, selectionAutoScrollRows(y = 501f, height = 500f, maxThresholdPx = 100f))
        assertEquals(-2, selectionAutoScrollRows(y = 506f, height = 500f, maxThresholdPx = 100f))
        // At y = 525 (+0.25 * 14 = +3.5), JS Math.round(+3.5) is +4 -> -(1 + 4) = -5.
        assertEquals(-5, selectionAutoScrollRows(y = 525f, height = 500f, maxThresholdPx = 100f))
        assertEquals(-8, selectionAutoScrollRows(y = 550f, height = 500f, maxThresholdPx = 100f))
        assertEquals(-15, selectionAutoScrollRows(y = 600f, height = 500f, maxThresholdPx = 100f))

        // Clamping the endpoint to start-of-top-row / end-of-bottom-row outside the viewport.
        assertEquals(Offset(0f, 0f), selectionAutoScrollPoint(Offset(140f, -12f), 800f, 500f))
        assertEquals(Offset(800f, 500f), selectionAutoScrollPoint(Offset(140f, 512f), 800f, 500f))
        assertEquals(Offset(140f, 250f), selectionAutoScrollPoint(Offset(140f, 250f), 800f, 500f))
    }

    @Test
    fun `touch scroll and wheel accumulation match xterm MouseService`() {
        assertEquals(0 to 12f, touchScrollLines(12f, 25f))
        assertEquals(2 to 5f, touchScrollLines(55f, 25f))
        assertEquals(-2 to -5f, touchScrollLines(-55f, 25f))

        // Fractional trackpad deltas accumulate in partialScroll until |total| >= 1, emitting one step and keeping total % 1.
        val (step1, rem1) = consumeWheelDelta(0.4f, 0f)
        assertEquals(0, step1)
        assertEquals(0.4f, rem1, 1e-5f)
        val (step2, rem2) = consumeWheelDelta(0.4f, rem1)
        assertEquals(0, step2)
        assertEquals(0.8f, rem2, 1e-5f)
        val (step3, rem3) = consumeWheelDelta(0.4f, rem2)
        assertEquals(1, step3)
        assertEquals(0.2f, rem3, 1e-5f)

        // Shift suppresses wheel consumption in MouseService._consumeWheelEvent; Alt/Ctrl multiply by fastScrollSensitivity (5).
        assertEquals(0 to 0.2f, consumeWheelDelta(1f, 0.2f, shiftPressed = true))
        val (fastUp, fastRem) = consumeWheelDelta(-1f, 0f, fastScroll = true)
        assertEquals(-5, fastUp)
        assertEquals(0f, fastRem, 1e-5f)

        assertEquals(3, wheelScrollbackLines(-1))
        assertEquals(15, wheelScrollbackLines(fastUp))
        assertEquals(-3, wheelScrollbackLines(1))
    }
}
