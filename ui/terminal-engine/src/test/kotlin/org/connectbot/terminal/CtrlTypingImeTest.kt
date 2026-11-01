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
 * Ctrl with a character, from a physical keyboard and from the toolbar's sticky Ctrl, down to the bytes
 * the PTY reads: the same ones xterm.js writes (see [ControlKeysTest] for the table). libvterm writes
 * CSI u for Ctrl+[ i j m, for a capital, and for digits and `/` ([FakePty] models that), and a shell
 * prints it, so none of these is left to libvterm.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CtrlTypingImeTest {
    private val rig = ImeRig()

    private fun sent(): List<Int> = rig.pty.bytes.map { it.toInt() and 0xff }

    private fun press(keyCode: Int, meta: Int = 0): List<Int> {
        rig.pty.clear()
        assertTrue(rig.ctrlKey(keyCode, meta))
        return sent()
    }

    @Test fun `Ctrl and a bracket, i, j or m on a physical keyboard are the control codes libvterm would spell as CSI u`() {
        assertEquals("Ctrl+[ is Escape", listOf(0x1b), press(KeyEvent.KEYCODE_LEFT_BRACKET))
        assertEquals("Ctrl+I is Tab", listOf(0x09), press(KeyEvent.KEYCODE_I))
        assertEquals("Ctrl+J is a line feed", listOf(0x0a), press(KeyEvent.KEYCODE_J))
        assertEquals("Ctrl+M is a carriage return", listOf(0x0d), press(KeyEvent.KEYCODE_M))
        assertEquals("Ctrl+\\", listOf(0x1c), press(KeyEvent.KEYCODE_BACKSLASH))
        assertEquals("Ctrl+]", listOf(0x1d), press(KeyEvent.KEYCODE_RIGHT_BRACKET))
    }

    @Test fun `every letter key with Ctrl is its control code, with Caps Lock on as well`() {
        for (letter in 'a'..'z') {
            val keyCode = KeyEvent.KEYCODE_A + (letter - 'a')
            val expected = listOf(letter - 'a' + 1)
            assertEquals("Ctrl+$letter", expected, press(keyCode))
            assertEquals("Ctrl+$letter with Caps Lock on", expected, press(keyCode, KeyEvent.META_CAPS_LOCK_ON))
        }
    }

    @Test fun `Ctrl with Space, the digits 3 to 8 and the slash`() {
        assertEquals(listOf(0x00), press(KeyEvent.KEYCODE_SPACE))
        assertEquals(listOf(0x1b), press(KeyEvent.KEYCODE_3))
        assertEquals(listOf(0x1c), press(KeyEvent.KEYCODE_4))
        assertEquals(listOf(0x1d), press(KeyEvent.KEYCODE_5))
        assertEquals(listOf(0x1e), press(KeyEvent.KEYCODE_6))
        assertEquals(listOf(0x1f), press(KeyEvent.KEYCODE_7))
        assertEquals(listOf(0x7f), press(KeyEvent.KEYCODE_8))
        assertEquals(listOf(0x1f), press(KeyEvent.KEYCODE_SLASH))
    }

    @Test fun `Ctrl and Shift with 2, 6 or the minus key are NUL, record separator and unit separator`() {
        assertEquals("Ctrl+@", listOf(0x00), press(KeyEvent.KEYCODE_2, KeyEvent.META_SHIFT_ON))
        assertEquals("Ctrl+^", listOf(0x1e), press(KeyEvent.KEYCODE_6, KeyEvent.META_SHIFT_ON))
        assertEquals("Ctrl+_", listOf(0x1f), press(KeyEvent.KEYCODE_MINUS, KeyEvent.META_SHIFT_ON))
    }

    @Test fun `Ctrl and Shift with a letter sends nothing, as xterm_js does, so Ctrl Shift C interrupts nothing`() {
        for (keyCode in listOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_Z)) {
            assertEquals(emptyList<Int>(), press(keyCode, KeyEvent.META_SHIFT_ON))
        }
    }

    @Test fun `Ctrl with a key xterm_js has no code for sends nothing rather than CSI u`() {
        for (keyCode in listOf(KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_SEMICOLON)) {
            assertEquals("key $keyCode", emptyList<Int>(), press(keyCode))
        }
    }

    @Test fun `Ctrl and Alt with a letter is Escape then its control code`() {
        assertEquals(listOf(0x1b, 0x09), press(KeyEvent.KEYCODE_I, KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON))
        assertEquals(listOf(0x1b, 0x01), press(KeyEvent.KEYCODE_A, KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON))
    }

    @Test fun `the toolbar's sticky Ctrl does the same to the text the soft keyboard commits, then lets go`() {
        val expected = listOf(
            "[" to 0x1b, "i" to 0x09, "j" to 0x0a, "m" to 0x0d, "\\" to 0x1c, "]" to 0x1d, "^" to 0x1e, "_" to 0x1f,
            // A capital is typed with the keyboard's own Shift, which the app cannot see.
            "C" to 0x03, "c" to 0x03, "@" to 0x00, " " to 0x00, "3" to 0x1b, "8" to 0x7f, "/" to 0x1f,
        )
        for ((text, code) in expected) {
            rig.pty.clear()
            rig.modifiers.ctrl = true
            assertTrue(rig.ime.commitText(text, 1))
            assertEquals("Ctrl then \"$text\"", listOf(code), sent())
            assertFalse("Ctrl is for one key", rig.modifiers.ctrl)
        }
    }

    @Test fun `sticky Ctrl with a character that has no code sends nothing and is still let go`() {
        rig.modifiers.ctrl = true
        assertTrue(rig.ime.commitText("1", 1))
        assertEquals(emptyList<Int>(), sent())
        assertFalse(rig.modifiers.ctrl)
        assertTrue(rig.ime.commitText("1", 1))
        assertEquals("the next one is a plain 1", listOf('1'.code), sent())
    }

    @Test fun `sticky Ctrl does the same to a key event the soft keyboard sends`() {
        rig.modifiers.ctrl = true
        assertTrue(rig.ime.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_LEFT_BRACKET, 0, 0)))
        assertEquals(listOf(0x1b), sent())
        assertFalse(rig.modifiers.ctrl)
    }

    @Test fun `without Ctrl these characters are typed as they are`() {
        for (text in listOf("[", "i", "j", "m", "C", "3", "/")) assertTrue(rig.ime.commitText(text, 1))
        assertEquals("[ijmC3/", rig.pty.stream)
    }
}
