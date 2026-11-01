package org.connectbot.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a tap on nothing does to the soft keyboard's side of a terminal, which the tester measured on a
 * tablet: after a tap on the blank part of the sidebar Gboard stayed up and showed suggestions, nothing
 * reached the shell, and neither a tap on the terminal nor hiding and showing the keyboard brought typing
 * back (the toolbar's Ctrl, which restarts the IME, did).
 *
 * The tap makes Compose clear the focus, so the view loses its Android focus and ends its editor. Android
 * leaves the IME attached to that view: `InputMethodManager` starts a new connection only for a view that
 * gains the focus when it is *another* one (`checkFocusInternalLocked`: served view equals next served view
 * means nothing to do), so the IME keeps typing into the ended editor until the view asks it to start over.
 * The rig models exactly that: the IME holds its connection until the view calls `restartInput`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FocusImeTest {
    private val rig = ImeRig()

    @Test fun `a tap on the terminal after a tap on nothing took its focus types again`() {
        rig.view.requestFocus()
        assertTrue(rig.ime.commitText("a", 1))
        assertEquals("a", rig.pty.line)

        // The tap on blank space.
        rig.view.clearFocus()

        // The IME is still attached to the view and types into what it holds. Nothing arrives, as nothing
        // does in the textarea a browser blurred.
        assertFalse(rig.ime.commitText("x", 1))
        assertEquals("a", rig.pty.line)

        // The tap back on the terminal.
        rig.view.requestFocus()
        assertTrue("the IME holds a live editor again", rig.ime.commitText("b", 1))
        assertEquals("ab", rig.pty.line)
    }

    @Test fun `the IME is asked to start over when the focus comes back to a view whose editor ended with it, and not before`() {
        rig.view.requestFocus()
        rig.ime
        assertEquals("the first focus finds nothing to replace", 0, rig.restarts)

        rig.view.clearFocus()
        assertEquals("a view with no focus must not pull the IME back to itself", 0, rig.restarts)

        rig.view.requestFocus()
        assertEquals(1, rig.restarts)
    }

    @Test fun `a tap on a terminal that kept its focus leaves the word being composed alone`() {
        rig.view.requestFocus()
        assertTrue(rig.ime.setComposingText("tiê", 1))
        assertEquals("tiê", rig.preedit)

        rig.view.requestFocus()
        rig.view.showIme()

        assertEquals(0, rig.restarts)
        assertEquals("tiê", rig.preedit)
        assertTrue(rig.ime.commitText("tiếng", 1))
        assertEquals("tiếng", rig.pty.line)
    }

    @Test fun `a word being composed when the focus went is dropped and the editor after it starts empty`() {
        rig.view.requestFocus()
        assertTrue(rig.ime.setComposingText("tiê", 1))

        rig.view.clearFocus()
        assertEquals("the overlay goes with the editor", "", rig.preedit)
        assertEquals("the unsent word is not typed behind the user's back", "", rig.pty.line)

        rig.view.requestFocus()
        assertEquals("", rig.imeContext)
        assertTrue(rig.ime.commitText("ls", 1))
        assertEquals("ls", rig.pty.line)
    }

    @Test fun `the editor the IME gets back is the one the terminal always hands out, not a raw one`() {
        rig.view.requestFocus()
        rig.ime
        rig.view.clearFocus()
        rig.view.requestFocus()

        assertEquals(TerminalEditorInfo.inputType(), rig.info.inputType)
        assertEquals(TerminalEditorInfo.IME_OPTIONS, rig.info.imeOptions)
    }
}
