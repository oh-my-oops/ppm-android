package com.hienlh.ppm.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Radix's item-aligned select placement, in CSS pixels, against what Chrome measured. */
class SelectPlacementTest {
    private fun place(triggerTop: Float, count: Int, selected: Int, border: Float = 0f) =
        placeSelect(triggerTop, 36f, viewTop = 0f, viewBottom = 813f, item = 32f, pad = 4f, margin = 10f, button = 24f, border = border, count = count, selected = selected)

    @Test
    fun `under the trigger the wrapper is pinned at the bottom, as tall as Chrome measured`() {
        // The phone's Effort select: High chosen, trigger at 458.4 in an 813 px viewport.
        val p = place(458.4f, count = 5, selected = 2)
        assertNull(p.top)
        assertEquals(803f, p.bottom!!, 0.001f)
        assertEquals(410.6f, p.height, 0.01f)
        assertEquals(160f, p.minHeight, 0.001f)
        assertEquals(793f, p.maxHeight, 0.001f)
        assertEquals(410.6f, p.usedHeight(), 0.01f)
        assertEquals(0f, p.scroll, 0.001f)
    }

    @Test
    fun `near the top it is pinned there and starts scrolled, past the scroll-up button`() {
        val p = place(20f, count = 5, selected = 2)
        assertEquals(10f, p.top!!, 0.001f)
        assertNull(p.bottom)
        // max(28, 24 + 16) above the row's middle, 84 below it; the minimum of five rows wins.
        assertEquals(124f, p.height, 0.001f)
        assertEquals(160f, p.usedHeight(), 0.001f)
        assertEquals(84f - 28f + 24f, p.scroll, 0.001f)
    }

    @Test
    fun `the last row keeps the padding under it, and a clipped list room for the scroll-down button`() {
        // The last of 12 chosen, the trigger low: 372 above the row's middle, 25 below.
        val last = place(760f, count = 12, selected = 11)
        assertEquals(803f, last.bottom!!, 0.001f)
        assertEquals(397f, last.height, 0.001f)
        // The first chosen right at the bottom: five rows' minimum clips the list, so Radix
        // places it again with the button under the rows.
        val clipped = place(780f, count = 12, selected = 0)
        assertEquals(20f + 16f + 24f, clipped.height, 0.001f)
        assertEquals(160f, clipped.usedHeight(), 0.001f)
    }

    @Test
    fun `a whole-pixel border at 2x moves the rows down by it`() {
        val p = place(458.4f, count = 5, selected = 2, border = 1f)
        assertEquals(85f + (793f - 466.4f), p.height, 0.01f)
    }
}
