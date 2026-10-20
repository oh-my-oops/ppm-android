package com.hienlh.ppm.state.windows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** window-dock-layout.ts, with the web's own cases ("squeezing the dock"). */
class WindowDockLayoutTest {
    private fun plan(available: Number, fullWidths: List<Number>, frontIndex: Int, maxChips: Int = 8) = planDock(
        available = available.toDouble(),
        fullWidths = fullWidths.map { it.toDouble() },
        iconWidth = 26.0,
        gap = 2.0,
        moreWidth = 40.0,
        frontIndex = frontIndex,
        maxChips = maxChips,
    )

    @Test
    fun `shows every title when there is room`() {
        assertEquals(DockLayout(DockMode.Full, emptyList()), plan(600, listOf(120, 120, 120), frontIndex = 1))
    }

    @Test
    fun `drops the titles of the windows behind first, keeping the front one's`() {
        // 3 x 120 + gaps = 364 > 300; front title + two icons = 120 + 26 + 26 + 4 = 176.
        assertEquals(DockLayout(DockMode.Compact, emptyList()), plan(300, listOf(120, 120, 120), frontIndex = 0))
    }

    @Test
    fun `folds the newest chips into +N, never the one in front`() {
        val layout = plan(230, List(6) { 120 }, frontIndex = 5)
        assertFalse(5 in layout.hidden)
        assertTrue(layout.hidden.isNotEmpty())
        // Folded from the newest end: the oldest windows stay in the row.
        assertTrue(layout.hidden.min() > 0)
    }

    @Test
    fun `folds past the chip limit even when the row would fit`() {
        assertEquals(listOf(4, 3), plan(5000, List(5) { 60 }, frontIndex = 0, maxChips = 3).hidden)
    }

    @Test
    fun `takes the title off the front chip too when only a sliver of it would show`() {
        val layout = plan(120, List(4) { 140 }, frontIndex = 0)
        assertEquals(DockMode.Tight, layout.mode)
        assertFalse(0 in layout.hidden)
    }

    @Test
    fun `has nothing to plan with no windows`() {
        assertEquals(DockLayout(DockMode.Full, emptyList()), plan(100, emptyList(), frontIndex = -1))
    }
}
