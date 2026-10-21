package com.hienlh.ppm.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The page's pan for the keyboard, checked against what Chrome's visual viewport did on the same phone. */
class KeyboardPanTest {
    // Chrome on the 411 × 923 dp phone, keyboard up: a 789 px layout, 477 px seen over the
    // textarea's keyboard and 413 px over an input's, whose autofill bar is taller.
    private val textareaView = 477f
    private val inputView = 413f
    private val layout = 789f

    /** Within the pixel Chrome's fractional offset rounds to. */
    private fun assertNear(chrome: Float, pan: Int) = assertEquals(chrome, pan.toFloat(), 1f)

    @Test
    fun `the message box at the bottom pans the page all the way, the nav onto the keyboard`() {
        // Chrome: vvTop 312.4 for the textarea at 689.7..724.6, with or without a reply above it.
        val pan = centredIfNeeded(689.7f, 724.6f, 0f, textareaView, now = 0, max = (layout - textareaView).toInt())
        assertNear(312.4f, pan)
    }

    @Test
    fun `a field the keyboard would cover is centred in what it leaves`() {
        // Chrome: vvTop 265.1 for the History search at 460..484.
        val pan = centredIfNeeded(460f, 484f, 0f, inputView, now = 0, max = (layout - inputView).toInt())
        assertNear(265.1f, pan)
    }

    @Test
    fun `a field already in view leaves the page where it is`() {
        // Chrome: vvTop 0 for the drawer's search at 98..132.
        assertEquals(0, centredIfNeeded(98f, 132f, 0f, inputView, now = 0, max = 376))
        // And a pan already made stays for a field it shows.
        assertEquals(312, centredIfNeeded(689.7f, 724.6f, 0f, textareaView, now = 312, max = 312))
    }

    @Test
    fun `a field the pan has put out of sight brings the page back, no further than its top`() {
        assertEquals(0, centredIfNeeded(98f, 132f, 0f, textareaView, now = 312, max = 312))
    }

    @Test
    fun `the view below a status bar is the one the field is centred in`() {
        // The app's phone: a 142 px status bar, the keyboard's top at 1541 px.
        assertEquals(677, centredIfNeeded(1487f, 1550f, 142f, 1541f, now = 0, max = 820))
    }
}
