package com.hienlh.ppm.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where Chrome's tablet `<select>` dropdown goes, in device pixels, against what Chrome 145 measured at 2x. */
class ChromeDropdownPlacementTest {
    // The Codex context window select: 612..1048 x 999..1055, a 12 px nine-patch padding on every
    // side, so the anchor Chrome measures from runs 1011..1043; the list's first row is 100 px.
    private val side = chromeDropdownSide(anchorTop = 1011, anchorBottom = 1043, freeTop = 48, freeBottom = 1552, paddingY = 24, idealHeight = 100)

    @Test
    fun `with more room above it opens above, the card's bottom edge on the select's top`() {
        assertEquals(false to 939, side)
        // Eight rows and seven 1 px dividers fit in 939; the window is that plus the padding.
        val height = 8 * 100 + 7 + 24
        assertEquals(180, 1011 - height)
        assertEquals(192, 1011 - height + 12)
    }

    @Test
    fun `as wide as the select, the card's left edge on the select's`() {
        assertEquals(600, chromeDropdownX(anchorLeft = 612, anchorWidth = 436, popupWidth = 460, freeRight = 2560))
    }

    @Test
    fun `with as much room below it opens below`() {
        assertEquals(true to 500, chromeDropdownSide(anchorTop = 524, anchorBottom = 1000, freeTop = 0, freeBottom = 1524, paddingY = 24, idealHeight = 100))
    }

    @Test
    fun `a first row that fits on neither side still goes below, as Chrome's calculator has it`() {
        assertEquals(true to 30, chromeDropdownSide(anchorTop = 124, anchorBottom = 1000, freeTop = 50, freeBottom = 1054, paddingY = 24, idealHeight = 100))
    }

    @Test
    fun `a list wider than the room left of the edge is pushed back inside`() {
        assertEquals(2236, chromeDropdownX(anchorLeft = 2400, anchorWidth = 300, popupWidth = 324, freeRight = 2560))
        assertEquals(0, chromeDropdownX(anchorLeft = 0, anchorWidth = 100, popupWidth = 300, freeRight = 2560))
    }
}
