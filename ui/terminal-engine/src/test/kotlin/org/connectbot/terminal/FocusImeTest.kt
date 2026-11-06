package org.connectbot.terminal

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 *
 * Then a hardware key pressed with nothing focused reaches Gboard through the same attachment, with no
 * editor behind it, and Gboard goes on composing the word: the next editor inherits it, soft taps add to it and
 * no byte reaches the shell. Chrome tells the IME that a blurred textarea is no editor at all (no connection,
 * `TYPE_NULL`), which drops the word with the session, so the view does the same once the focus has stayed away.
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

    @Test fun `the IME is asked to start over once the focus has stayed away, and again when it comes back`() {
        rig.view.requestFocus()
        rig.ime
        assertEquals("the first focus finds nothing to replace", 0, rig.restarts)

        rig.view.clearFocus()
        assertEquals("the focus may be going to another view, which the IME starts a connection for itself", 0, rig.restarts)

        rig.settle()
        assertEquals("nothing took it: the IME is told there is no editor", 1, rig.restarts)

        rig.view.requestFocus()
        assertEquals("and it is asked for a new one when the focus is back", 2, rig.restarts)
    }

    @Test fun `once nothing took the focus the IME is told there is no editor, as Chrome tells it for a blurred textarea`() {
        rig.view.requestFocus()
        assertTrue(rig.ime.commitText("a", 1))

        rig.view.clearFocus()
        rig.settle()

        assertNull("nothing to type into", rig.imeOrNull)
        assertEquals(EditorInfo.TYPE_NULL, rig.info.inputType)
    }

    @Test fun `the focus coming back before the IME was told leaves it one restart, for the new editor`() {
        rig.view.requestFocus()
        rig.ime

        rig.view.clearFocus()
        rig.view.requestFocus()
        rig.settle()

        assertEquals("what was posted finds the focus back and does nothing", 1, rig.restarts)
        assertTrue(rig.ime.commitText("b", 1))
        assertEquals("b", rig.pty.line)
    }

    @Test fun `a view that lost the focus before the IME ever connected gets an editor when it comes back`() {
        rig.view.requestFocus()
        rig.view.clearFocus()
        rig.settle()
        assertNull(rig.imeOrNull)

        rig.view.requestFocus()

        assertTrue("the IME is not left with no editor", rig.ime.commitText("a", 1))
        assertEquals("a", rig.pty.line)
    }

    @Test fun `the editor after a blur starts empty, and the IME is told the caret moved and came back so it drops a word it still composes`() {
        rig.view.requestFocus()
        assertTrue(rig.ime.setComposingText("tiê", 1))
        rig.view.clearFocus()
        rig.settle()
        rig.reports.clear()

        rig.view.requestFocus()

        assertEquals(listOf(listOf(1, 1, -1, -1), listOf(0, 0, -1, -1)), rig.reports)
        assertEquals("", rig.imeContext)
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

    @Test fun `returning to the window keeps the focused editor live without re-raising the soft keyboard`() {
        rig.view.requestFocus()
        rig.view.observeImeVisibility(true)
        assertTrue(rig.ime.commitText("a", 1))

        rig.view.onWindowFocusChanged(false)
        rig.view.onWindowFocusChanged(true)

        assertTrue("the terminal keeps focus across the window switch", rig.view.hasFocus())
        assertEquals("returning to the window never asks the IME to pop back up", 0, rig.shows)
        assertTrue("the editor stays live for immediate hardware or IME input", rig.ime.commitText("b", 1))
        assertEquals("ab", rig.pty.line)
    }

    @Test fun `detaching a focused view right after ViewGroup clears its focus still hides the soft keyboard and drops the editor`() {
        rig.view.requestFocus()
        assertTrue(rig.ime.commitText("exit", 1))

        // ViewGroup.removeViewInternal clears the child's focus synchronously before calling onDetachedFromWindow.
        rig.view.clearFocus()
        val onDetached = android.view.View::class.java.getDeclaredMethod("onDetachedFromWindow").apply { isAccessible = true }
        onDetached.invoke(rig.view)

        assertEquals("the soft keyboard is hidden even though clearFocus ran first", 1, rig.hides)
        assertEquals("the IME is told the editor is gone without waiting for the posted runnable", 1, rig.restarts)
        assertNull(rig.imeOrNull)
        assertEquals(EditorInfo.TYPE_NULL, rig.info.inputType)
    }
}
