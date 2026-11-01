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

    // The tablet with its dock enlarged and the keyboard up, in raw pixels (2 to the dp): a 1600 px page, a 48 px status bar, the keyboard's top at
    // 846 px and the page's bottom on it at a pan of 688. The terminal's cursor is on its row 3 at 876..902, under the keyboard, and its key row is
    // at 1372..1480, which is where the screenshot of the dock enlarged had them.
    private val view = 48f to 846f

    @Test
    fun `the cursor row and the key row are both brought into view when they fit the view together`() {
        // The cursor's row alone would be centred, a pan of 442 that leaves the keys under the keyboard; the two together are 604 px of the 798.
        assertEquals(442, panFor(876f, 902f, null, null, view.first, view.second, now = 0, max = 688))
        assertEquals(688, panFor(876f, 902f, 1372f, 1480f, view.first, view.second, now = 0, max = 688))
    }

    @Test
    fun `the cursor row alone is brought into view when the two do not fit the view together`() {
        // A dock three times as tall: the keys are 2,100 px under the cursor, and the cursor is what shows.
        assertEquals(442, panFor(876f, 902f, 2900f, 3008f, view.first, view.second, now = 0, max = 688))
    }

    @Test
    fun `the cursor row alone is brought into view when the page cannot go far enough for both`() {
        // 1000..1790 is 790 px of 798 and fits, but the pan would be 948 and the page goes to 688: the keys would still be under the keyboard.
        assertEquals(566, panFor(1000f, 1026f, 1690f, 1790f, view.first, view.second, now = 0, max = 688))
    }

    @Test
    fun `the cursor row and the key row fit when they are exactly the height of the view, and not a pixel over`() {
        // 876..1674 is 798 px, the view's own height, so the pan that centres it is the one that puts the top on the view's.
        assertEquals(828, panFor(876f, 902f, 1600f, 1674f, view.first, view.second, now = 0, max = 828))
        assertEquals(442, panFor(876f, 902f, 1600f, 1675f, view.first, view.second, now = 0, max = 828))
    }

    @Test
    fun `a pair that would start above the view is not pulled in, and the field alone decides`() {
        // Keys at 10..50 are above the view's top, 48: the pair would be centred at a pan of 0 and the field, in view at the pan of 100, is kept there.
        assertEquals(100, panFor(700f, 720f, 10f, 50f, view.first, view.second, now = 100, max = 688))
    }

    @Test
    fun `the two are what is centred whichever is higher`() {
        // 876..1480 has its middle, 1178, put at the view's, 447: a pan of 731, which the page may go to.
        assertEquals(731, panFor(876f, 902f, 1372f, 1480f, view.first, view.second, now = 0, max = 900))
        assertEquals(731, panFor(1372f, 1480f, 876f, 902f, view.first, view.second, now = 0, max = 900))
    }

    @Test
    fun `a cursor row and key row that are in view leave the page where it is`() {
        assertEquals(0, panFor(100f, 126f, 700f, 800f, view.first, view.second, now = 0, max = 688))
        assertEquals(300, panFor(1000f, 1026f, 1100f, 1140f, view.first, view.second, now = 300, max = 688))
    }

    @Test
    fun `a cursor in view brings the keys up with it when both fit`() {
        // The phone's page, a keyboard of 1,100 px under it: the cursor's row just above the keys and the nav under them.
        assertEquals(1028, panFor(2060f, 2085f, 2083f, 2203f, 142f, 1300f, now = 0, max = 1028))
        // A cursor row in view with the keys under the keyboard: the page goes up for them.
        assertEquals(353, panFor(700f, 726f, 800f, 900f, view.first, view.second, now = 0, max = 688))
    }

    @Test
    fun `a field with nothing beside it is centred as before`() {
        assertEquals(centredIfNeeded(689.7f, 724.6f, 0f, textareaView, now = 0, max = 312), panFor(689.7f, 724.6f, null, null, 0f, textareaView, now = 0, max = 312))
        assertEquals(centredIfNeeded(460f, 484f, 0f, inputView, now = 0, max = 376), panFor(460f, 484f, null, null, 0f, inputView, now = 0, max = 376))
    }
}
