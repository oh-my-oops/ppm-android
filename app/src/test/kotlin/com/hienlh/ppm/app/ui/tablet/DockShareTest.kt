package com.hienlh.ppm.app.ui.tablet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The dock's share of the working area, worked out in the composition that has the layout it comes from. It used to be state of its own that took
 * the layout's saved height in an effect, one frame after the composition that had it: a project's layout arriving with the dock showing laid the
 * dock out at the height of the layout before it, its terminal was sized to that, and a frame later to the saved one.
 *
 * `PanelArea` calls [dockShare] while composing and [DockDrag.settle] in an effect when the saved height changes, makes a [DockDrag] at each move
 * of the handle and gives it what the layout kept at its end; the tests below call them in that order.
 */
class DockShareTest {
    private fun share(saved: Double, drag: DockDrag? = null, bottom: Boolean = true, expanded: Boolean = false) = dockShare(bottom, expanded, saved, drag)

    private fun assertShare(expected: Float, actual: Float) = assertEquals(expected, actual, 0f)

    @Test
    fun `the saved height is the share in the composition that has it`() {
        // The layout first drawn is the default one, a dock of 30 %, and the project's, with a dock of 60 %, comes after it: each is the share at once.
        assertShare(0.3f, share(30.0))
        assertShare(0.6f, share(60.0))
    }

    @Test
    fun `the same for a dock at the side`() {
        assertShare(0.3f, share(30.0, bottom = false))
        assertShare(0.6f, share(60.0, bottom = false))
    }

    @Test
    fun `a maximized dock takes the web's own share, whatever the layout saves`() {
        assertShare(0.70f, share(30.0, expanded = true))
        assertShare(0.70f, share(60.0, expanded = true))
        assertShare(0.55f, share(30.0, bottom = false, expanded = true))
        assertShare(0.55f, share(60.0, bottom = false, expanded = true))
    }

    @Test
    fun `a drag's share stands while the layout shows the height it began from`() {
        val drag = DockDrag(0.45f, began = 30.0)
        assertShare(0.45f, share(30.0, drag))
    }

    @Test
    fun `the share stays after the drag ends while the layout has not yet taken its save`() {
        // The layout takes a save a moment after it was made: for that moment it still shows the height before, and the dock must not go back to it.
        val ended = DockDrag(0.45f, began = 30.0, kept = 45.0)
        assertShare(0.45f, share(30.0, ended))
        assertShare(0.45f, share(45.0, ended))
    }

    @Test
    fun `the drag's share stays when the layout keeps less than was saved`() {
        // A drag can reach 10 % and the layout keeps 15 %: the web keeps the drag's size.
        val ended = DockDrag(0.10f, began = 30.0, kept = 15.0)
        assertShare(0.10f, share(30.0, ended))
        assertShare(0.10f, share(15.0, ended))
    }

    @Test
    fun `a height that comes from elsewhere is the layout's, over a drag's`() {
        val ended = DockDrag(0.45f, began = 30.0, kept = 45.0)
        assertShare(0.5f, share(50.0, ended))
        // Also while a drag has not ended: the next move of the handle sets it again.
        assertShare(0.5f, share(50.0, DockDrag(0.45f, began = 30.0)))
    }

    @Test
    fun `a maximized dock keeps the share it was dragged to, which is not saved`() {
        val drag = DockDrag(0.8f, began = 30.0)
        assertShare(0.8f, share(30.0, drag, expanded = true))
        assertShare(0.8f, share(60.0, drag, expanded = true))
    }

    @Test
    fun `a height that came from elsewhere ends the drag, which a height that repeats one cannot bring back`() {
        // Dragged from 30 to 45, the layout took 45, and another device sets 50 and then 30, the height the drag began from.
        var drag: DockDrag? = DockDrag(0.45f, began = 30.0, kept = 45.0)
        drag = drag?.settle(45.0)
        assertShare(0.45f, share(45.0, drag))
        drag = drag?.settle(50.0)
        assertNull(drag)
        assertShare(0.5f, share(50.0, drag))
        drag = drag?.settle(30.0)
        assertShare(0.3f, share(30.0, drag))
    }

    @Test
    fun `settle leaves a drag where the layout has not moved`() {
        val drag = DockDrag(0.45f, began = 30.0)
        assertSame(drag, drag.settle(30.0))
        val ended = DockDrag(0.45f, began = 30.0, kept = 45.0)
        assertSame(ended, ended.settle(30.0))
    }

    @Test
    fun `settle holds the save alone once the layout shows it`() {
        val ended = DockDrag(0.45f, began = 30.0, kept = 45.0)
        assertEquals(DockDrag(0.45f, began = 45.0, kept = 45.0), ended.settle(45.0))
        // So the height before the drag no longer brings it back.
        assertNull(ended.settle(45.0)?.settle(30.0))
    }

    @Test
    fun `settle forgets a drag that has not ended when another height arrives`() {
        assertNull(DockDrag(0.45f, began = 30.0).settle(50.0))
    }

    @Test
    fun `a drag that saved what it began from stands for that height`() {
        // Dragged to 10 % from 15 %: the layout keeps 15 % as it had, and shows no change.
        val ended = DockDrag(0.10f, began = 15.0, kept = 15.0)
        assertSame(ended, ended.settle(15.0))
        assertShare(0.10f, share(15.0, ended))
    }
}
