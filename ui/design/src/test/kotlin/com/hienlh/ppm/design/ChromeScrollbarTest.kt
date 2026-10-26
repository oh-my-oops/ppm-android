package com.hienlh.ppm.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The thumb where Chrome 145 on the tablet emulator (2x, 8 px thick) drew it, in device px. */
class ChromeScrollbarTest {
    @Test
    fun `as long as the share of the content in view`() {
        // 200 CSS px tall showing 2,000, and 300 wide showing 1,200.
        assertEquals(0 to 40, chromeThumb(0f, 3600f, 400, 400, 8))
        assertEquals(0 to 150, chromeThumb(0f, 1800f, 600, 600, 8))
        // 200 showing 220.
        assertEquals(0 to 364, chromeThumb(0f, 40f, 400, 400, 8))
    }

    @Test
    fun `never shorter than it is thick`() {
        // 200 showing 100,000, and 60 showing 2,000.
        assertEquals(0 to 8, chromeThumb(0f, 199_600f, 400, 400, 8))
        assertEquals(0 to 8, chromeThumb(0f, 3880f, 120, 120, 8))
    }

    @Test
    fun `at the scroll position's share of the room left`() {
        assertEquals(180 to 40, chromeThumb(1800f, 3600f, 400, 400, 8))
        assertEquals(225 to 150, chromeThumb(900f, 1800f, 600, 600, 8))
        assertEquals(360 to 40, chromeThumb(3600f, 3600f, 400, 400, 8))
        // Truncated, but off the start as soon as it moves at all.
        assertEquals(1 to 40, chromeThumb(1f, 3600f, 400, 400, 8))
        assertEquals(2 to 40, chromeThumb(25f, 3600f, 400, 400, 8))
    }

    @Test
    fun `short of the corner both bars share`() {
        // 300 by 200 showing 1,200 by 2,000: each track 8 short, each thumb the view's share of it.
        assertEquals(0 to 39, chromeThumb(0f, 3600f, 400, 392, 8))
        assertEquals(0 to 148, chromeThumb(0f, 1800f, 600, 592, 8))
        assertEquals(353 to 39, chromeThumb(3600f, 3600f, 400, 392, 8))
    }

    @Test
    fun `none when nothing overflows or the track is shorter than the thumb`() {
        assertNull(chromeThumb(0f, 0f, 400, 400, 8))
        assertNull(chromeThumb(0f, 100f, 6, 6, 8))
    }
}
