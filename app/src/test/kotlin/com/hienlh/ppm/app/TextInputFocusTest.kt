package com.hienlh.ppm.app

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which taps take the focus from a text field, as Chrome's press does ([TextInputFocus.tapLeaves]),
 * and the one focus a page has across the app's windows.
 */
class TextInputFocusTest {
    private val composer = TextFieldState()
    private val search = TextFieldState()

    @After
    fun releaseBoth() {
        TextInputFocus.release(composer)
        TextInputFocus.release(search)
    }

    @Test
    fun `a tap on the page leaves the focused field`() {
        TextInputFocus.take(composer)
        val start = TextInputFocus.tapStarts()
        assertTrue(TextInputFocus.tapLeaves(start))
    }

    @Test
    fun `a tap in the focused field keeps it`() {
        TextInputFocus.take(composer)
        val start = TextInputFocus.tapStarts()
        TextInputFocus.pressed(composer)
        assertFalse(TextInputFocus.tapLeaves(start))
    }

    @Test
    fun `a tap whose handler asks for the focus back keeps it`() {
        TextInputFocus.take(composer)
        val start = TextInputFocus.tapStarts()
        TextInputFocus.asked()
        assertFalse(TextInputFocus.tapLeaves(start))
    }

    @Test
    fun `a tap that moves the focus to another field leaves it there`() {
        TextInputFocus.take(composer)
        val start = TextInputFocus.tapStarts()
        TextInputFocus.pressed(search)
        TextInputFocus.release(composer)
        TextInputFocus.take(search)
        assertFalse(TextInputFocus.tapLeaves(start))
    }

    @Test
    fun `a tap that focuses a field with none focused keeps it`() {
        val start = TextInputFocus.tapStarts()
        TextInputFocus.pressed(composer)
        TextInputFocus.take(composer)
        assertFalse(TextInputFocus.tapLeaves(start))
    }

    @Test
    fun `a press in the field before this tap does not count for it`() {
        TextInputFocus.take(composer)
        TextInputFocus.pressed(composer)
        val start = TextInputFocus.tapStarts()
        assertTrue(TextInputFocus.tapLeaves(start))
    }

    @Test
    fun `a field taking the focus in another window takes it from this one`() {
        val page = Window()
        TextInputFocus.take(composer, window = page)
        TextInputFocus.take(search, window = Window())
        assertEquals(1, page.cleared)
        // The composer's window let its field go: the palette's search is the one focused.
        TextInputFocus.release(composer)
        assertSame(search, TextInputFocus.field)
        TextInputFocus.release(search)
        assertFalse(TextInputFocus.focused)
    }

    @Test
    fun `a field taking the focus in the same window leaves the window to move it`() {
        val page = Window()
        TextInputFocus.take(composer, window = page)
        TextInputFocus.take(search, window = page)
        TextInputFocus.take(search, window = page)
        assertEquals(0, page.cleared)
        assertSame(search, TextInputFocus.field)
    }

    private class Window : FocusManager {
        var cleared = 0

        override fun clearFocus(force: Boolean) {
            cleared++
        }

        override fun moveFocus(focusDirection: FocusDirection) = false
    }
}
