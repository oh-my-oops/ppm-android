package org.connectbot.terminal

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The sticky Ctrl of the toolbar's row, from the soft keyboard's side: Ctrl is armed in the app's
 * `TerminalModifiers` (here [TestModifiers]) and the next key the IME sends uses it up. The web never
 * applied it to the soft keyboard at all; its row only honoured it for its own keys.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StickyCtrlImeTest {
    private val rig = ImeRig()

    @Test fun `an armed Ctrl makes the next letter the IME commits its control byte, and is then let go`() {
        rig.modifiers.ctrl = true
        assertTrue(rig.ime.commitText("c", 1))
        assertEquals("\u0003", rig.pty.stream)
        assertFalse("Ctrl is for one key", rig.modifiers.ctrl)

        assertTrue(rig.ime.commitText("c", 1))
        assertEquals("the second c is a plain c", "\u0003c", rig.pty.stream)
    }

    @Test fun `an armed Ctrl does the same to a raw key event, as the IME sends in key-event mode`() {
        rig.modifiers.ctrl = true
        rig.ime.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_D, 0, 0))
        assertEquals("\u0004", rig.pty.stream)
        assertFalse(rig.modifiers.ctrl)
    }

    @Test fun `ending the editor for a key of the row sends the word in it bare and keeps Ctrl armed for that key`() {
        assertTrue(rig.ime.setComposingText("tiê", 1))
        assertEquals("", rig.pty.stream)
        rig.modifiers.ctrl = true

        // What the toolbar does first on every key: flush, then retire the editor.
        rig.view.finishBeforeExternalInput()

        assertEquals("the word is not Ctrl-ed", "tiê", rig.pty.stream)
        assertTrue("the key about to be sent is the one that uses Ctrl", rig.modifiers.ctrl)
    }

    @Test fun `modifier keys alone do not use Ctrl up`() {
        rig.modifiers.ctrl = true
        rig.ime.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT))
        assertEquals("", rig.pty.stream)
        assertTrue(rig.modifiers.ctrl)
    }
}
