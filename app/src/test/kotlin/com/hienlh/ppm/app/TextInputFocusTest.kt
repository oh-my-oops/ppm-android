package com.hienlh.ppm.app

import androidx.compose.foundation.text.input.TextFieldState
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which taps take the focus from a text field, as Chrome's press does: [TextInputFocus.tapLeaves]. */
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
}
