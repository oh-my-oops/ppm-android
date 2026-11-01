package com.hienlh.ppm.app

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    private val terminal = Any()
    private val otherTerminal = Any()

    @After
    fun releaseAll() {
        TextInputFocus.release(composer)
        TextInputFocus.release(search)
        TextInputFocus.releaseNative(terminal)
        TextInputFocus.releaseNative(otherTerminal)
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

    @Test
    fun `native focus in another window clears Compose and survives its late release`() {
        val page = Window { TextInputFocus.release(composer) }
        val terminalWindow = Window()
        composer.edit { append("a draft") }
        TextInputFocus.take(composer, window = page)
        assertEquals("a draft", TextInputFocus.text)

        TextInputFocus.takeNative(terminal, terminalWindow) { null }

        assertEquals(1, page.cleared)
        assertEquals(0, terminalWindow.cleared)
        assertTrue(TextInputFocus.focused)
        assertSame(terminal, TextInputFocus.field)
        assertNull(TextInputFocus.text)
        assertFalse(TextInputFocus.composing)
        TextInputFocus.release(composer)
        assertSame(terminal, TextInputFocus.field)
        assertTrue(TextInputFocus.focused)
    }

    @Test
    fun `a stale native release leaves the new Compose field and its window focused`() {
        val terminalWindow = Window { TextInputFocus.releaseNative(terminal) }
        val palette = Window()
        TextInputFocus.takeNative(terminal, terminalWindow) { null }
        TextInputFocus.take(search, window = palette)
        assertEquals(1, terminalWindow.cleared)

        TextInputFocus.releaseNative(terminal)

        assertSame(search, TextInputFocus.field)
        assertTrue(TextInputFocus.focused)
        assertEquals("", TextInputFocus.text)
        // A late terminal disposal must not forget which window owns the new field either.
        TextInputFocus.takeNative(otherTerminal, Window()) { null }
        assertEquals(1, palette.cleared)
        assertSame(otherTerminal, TextInputFocus.field)
    }

    @Test
    fun `a stale native release leaves a replacement native owner and its window focused`() {
        val page = Window()
        TextInputFocus.takeNative(terminal, page) { null }
        TextInputFocus.takeNative(otherTerminal, page) { null }
        assertEquals(0, page.cleared)

        TextInputFocus.releaseNative(terminal)

        assertSame(otherTerminal, TextInputFocus.field)
        assertTrue(TextInputFocus.focused)
        TextInputFocus.take(search, window = Window())
        assertEquals(1, page.cleared)
        assertSame(search, TextInputFocus.field)
    }

    @Test
    fun `a tap whose handler asks retains native focus for that tap only`() {
        TextInputFocus.takeNative(terminal, Window()) { null }
        val start = TextInputFocus.tapStarts()
        assertSame(terminal, start.focused)
        assertTrue(TextInputFocus.tapLeaves(start))

        TextInputFocus.asked()

        assertFalse(TextInputFocus.tapLeaves(start))
        assertSame(terminal, TextInputFocus.field)
        assertTrue(TextInputFocus.focused)
        assertTrue(TextInputFocus.tapLeaves(TextInputFocus.tapStarts()))
    }

    @Test
    fun `a tap that moves native focus to another native or Compose field keeps the new focus`() {
        val page = Window()
        TextInputFocus.takeNative(terminal, page) { null }
        val nativeStart = TextInputFocus.tapStarts()
        TextInputFocus.takeNative(otherTerminal, page) { null }
        assertFalse(TextInputFocus.tapLeaves(nativeStart))
        assertSame(otherTerminal, TextInputFocus.field)

        val composeStart = TextInputFocus.tapStarts()
        TextInputFocus.take(composer, window = page)
        assertFalse(TextInputFocus.tapLeaves(composeStart))
        assertSame(composer, TextInputFocus.field)
        assertEquals(0, page.cleared)
    }

    @Test
    fun `a tap on nothing lets the focused native editor hide its keyboard`() {
        val page = Window()
        var hidden = 0
        TextInputFocus.takeNative(terminal, page, onBlur = { hidden++ }) { null }

        TextInputFocus.blurNative(page)

        assertEquals(1, hidden)
    }

    @Test
    fun `a tap in another window leaves the keyboard of the native editor in this one`() {
        var hidden = 0
        TextInputFocus.takeNative(terminal, Window(), onBlur = { hidden++ }) { null }

        TextInputFocus.blurNative(Window())

        assertEquals(0, hidden)
    }

    @Test
    fun `a native editor that let go of the focus is not told of the next tap`() {
        val page = Window()
        var hidden = 0
        TextInputFocus.takeNative(terminal, page, onBlur = { hidden++ }) { null }
        TextInputFocus.releaseNative(terminal)

        TextInputFocus.blurNative(page)

        assertEquals(0, hidden)
    }

    @Test
    fun `a Compose field that took the focus from a native editor is not a native editor to blur`() {
        val page = Window()
        var hidden = 0
        TextInputFocus.takeNative(terminal, page, onBlur = { hidden++ }) { null }
        TextInputFocus.take(composer, window = page)

        TextInputFocus.blurNative(page)

        assertEquals(0, hidden)
    }

    @Test
    fun `only the native editor that holds the focus is told`() {
        val page = Window()
        val told = mutableListOf<String>()
        TextInputFocus.takeNative(terminal, page, onBlur = { told += "first" }) { null }
        TextInputFocus.takeNative(otherTerminal, page, onBlur = { told += "second" }) { null }

        TextInputFocus.blurNative(page)

        assertEquals(listOf("second"), told)
    }

    private class Window(private val onClear: () -> Unit = {}) : FocusManager {
        var cleared = 0

        override fun clearFocus(force: Boolean) {
            cleared++
            onClear()
        }

        override fun moveFocus(focusDirection: FocusDirection) = false
    }
}
