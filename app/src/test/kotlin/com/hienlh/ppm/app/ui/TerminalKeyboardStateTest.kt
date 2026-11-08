package com.hienlh.ppm.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalKeyboardStateTest {
    @Test
    fun `a reconnect that drops inputReady keeps keyboardRequested and re-shows the soft keyboard when inputReady returns`() {
        val kb = TerminalKeyboardState()
        var asks = 0
        var focusRequests = 0
        var shows = 0
        var hides = 0
        var releases = 0

        kb.onTerminalTap(askFocus = { asks++ }, showKeyboard = { shows++ })
        kb.onImeVisibilityChanged(imeVisible = true, inputReady = true)
        assertTrue(kb.terminalFocused)
        assertTrue(kb.keyboardRequested)
        assertTrue(kb.showSoftKeyboard(inputReady = true))
        assertEquals(1, shows)

        // Socket reconnect: connected/shellReady drop (inputReady = false), while tab stays visible and alive.
        kb.onTerminalLivenessChanged(
            visible = true,
            exited = false,
            hasError = false,
            hideKeyboard = { hides++ },
            releaseFocus = { releases++ },
        )
        // Even if the transient disable causes IME visibility to report false while !inputReady,
        // keyboardRequested must survive the reconnect.
        kb.onImeVisibilityChanged(imeVisible = false, inputReady = false)
        assertTrue("keyboardRequested survives transient inputReady drop", kb.keyboardRequested)
        assertTrue("terminalFocused survives transient inputReady drop", kb.terminalFocused)
        assertEquals("reconnect does not explicitly hide keyboard", 0, hides)
        assertEquals(0, releases)

        // Reconnect completes: inputReady returns true.
        kb.onReadyStateChanged(
            inputReady = true,
            requestFocus = { focusRequests++ },
            showKeyboard = { shows++ },
        )
        assertTrue(kb.showSoftKeyboard(inputReady = true))
        assertEquals(1, focusRequests)
        assertEquals(2, shows)
    }

    @Test
    fun `process exit or hidden tab clears keyboardRequested and hides the soft keyboard`() {
        val kb = TerminalKeyboardState()
        var hides = 0
        var releases = 0

        kb.onTerminalTap(askFocus = {}, showKeyboard = {})
        assertTrue(kb.keyboardRequested)

        kb.onTerminalLivenessChanged(
            visible = false,
            exited = false,
            hasError = false,
            hideKeyboard = { hides++ },
            releaseFocus = { releases++ },
        )
        assertFalse(kb.keyboardRequested)
        assertEquals(1, hides)
        assertEquals(0, releases)

        kb.onTerminalTap(askFocus = {}, showKeyboard = {})
        kb.onTerminalLivenessChanged(
            visible = true,
            exited = true,
            hasError = false,
            hideKeyboard = { hides++ },
            releaseFocus = { releases++ },
        )
        assertFalse(kb.keyboardRequested)
        assertFalse(kb.terminalFocused)
        assertEquals(2, hides)
        assertEquals(1, releases)
    }

    @Test
    fun `select mode raises nothing on toggle, tap, drag or exit`() {
        val kb = TerminalKeyboardState()
        var asks = 0
        var focusRequests = 0
        var shows = 0
        var hides = 0

        // Enter select mode from an unfocused terminal: raises nothing, requests no focus.
        kb.onToggleSelect(hideKeyboard = { hides++ })
        assertTrue(kb.selecting)
        assertFalse(kb.keyboardEnabled(inputReady = true))
        assertFalse(kb.showSoftKeyboard(inputReady = true))
        assertEquals(0, asks)
        assertEquals(0, focusRequests)
        assertEquals(0, shows)
        assertEquals(1, hides)

        // Tapping the terminal while in select mode raises nothing and does not focus.
        kb.onTerminalTap(askFocus = { asks++ }, showKeyboard = { shows++ })
        assertFalse(kb.terminalFocused)
        assertFalse(kb.keyboardRequested)
        assertEquals(0, asks)
        assertEquals(0, shows)

        // Leaving select mode does not raise the keyboard or focus an unfocused terminal.
        kb.onToggleSelect(hideKeyboard = { hides++ })
        kb.onReadyStateChanged(
            inputReady = true,
            requestFocus = { focusRequests++ },
            showKeyboard = { shows++ },
        )
        assertFalse(kb.selecting)
        assertFalse(kb.showSoftKeyboard(inputReady = true))
        assertEquals(0, focusRequests)
        assertEquals(0, shows)
    }
}
