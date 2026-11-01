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
 * Enter, Tab, Backspace and Escape with Ctrl, Shift or Alt held, down to the bytes the PTY reads: the ones xterm.js
 * writes. libvterm writes CSI u for these four as soon as Ctrl or Shift is held ([FakePty.key] models that from the
 * shipped library), which a shell prints as `[13;5u`, so none of it is left to libvterm. See [dispatchKeyLikeXterm].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpecialKeysImeTest {
    private val rig = ImeRig()

    private val keys = listOf(
        "Enter" to KeyEvent.KEYCODE_ENTER, "Tab" to KeyEvent.KEYCODE_TAB,
        "Backspace" to KeyEvent.KEYCODE_DEL, "Escape" to KeyEvent.KEYCODE_ESCAPE,
    )

    /** xterm.js's `evaluateKeyboardEvent` for these four keys, written as `Keyboard.ts` reads (`case 13`, `9`, `8`, `27`). */
    private fun xterm(key: String, shift: Boolean, alt: Boolean, ctrl: Boolean): String = when (key) {
        "Enter" -> if (alt) "\u001b\r" else "\r"
        "Tab" -> if (shift) "\u001b[Z" else "\t"
        "Backspace" -> (if (alt) "\u001b" else "") + (if (ctrl) "\b" else "\u007f")
        "Escape" -> if (alt) "\u001b\u001b" else "\u001b"
        else -> error(key)
    }

    private fun meta(shift: Boolean, alt: Boolean, ctrl: Boolean) =
        (if (shift) KeyEvent.META_SHIFT_ON else 0) or
            (if (alt) KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON else 0) or
            (if (ctrl) KeyEvent.META_CTRL_ON else 0)

    private fun press(keyCode: Int, shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false): String {
        rig.pty.clear()
        assertTrue(rig.rawKey(keyCode, meta(shift, alt, ctrl)))
        return rig.pty.stream
    }

    @Test fun `Ctrl with Backspace, Enter, Tab and Escape is ^H, CR, HT and ESC as xterm_js sends them`() {
        assertEquals("Ctrl+Backspace is ^H", "\u0008", press(KeyEvent.KEYCODE_DEL, ctrl = true))
        assertEquals("Ctrl+Enter is CR", "\u000d", press(KeyEvent.KEYCODE_ENTER, ctrl = true))
        assertEquals("Ctrl+Tab is HT", "\u0009", press(KeyEvent.KEYCODE_TAB, ctrl = true))
        assertEquals("Ctrl+Esc is ESC", "\u001b", press(KeyEvent.KEYCODE_ESCAPE, ctrl = true))
    }

    @Test fun `every combination of Shift, Alt and Ctrl on the four keys is what xterm_js sends`() {
        for ((name, keyCode) in keys) for (mask in 0..7) {
            val shift = mask and 1 != 0
            val alt = mask and 2 != 0
            val ctrl = mask and 4 != 0
            assertEquals(
                "$name with ${listOf("Shift" to shift, "Alt" to alt, "Ctrl" to ctrl).filter { it.second }.map { it.first }}",
                xterm(name, shift, alt, ctrl),
                press(keyCode, shift, alt, ctrl),
            )
        }
    }

    @Test fun `the plain keys are unchanged`() {
        assertEquals("\r", press(KeyEvent.KEYCODE_ENTER))
        assertEquals("\t", press(KeyEvent.KEYCODE_TAB))
        assertEquals("\u007f", press(KeyEvent.KEYCODE_DEL))
        assertEquals("\u001b", press(KeyEvent.KEYCODE_ESCAPE))
    }

    @Test fun `Shift with Tab stays the back-tab and no modifier is added to it`() {
        assertEquals("\u001b[Z", press(KeyEvent.KEYCODE_TAB, shift = true))
        assertEquals("with Ctrl too, not ESC[1;6Z", "\u001b[Z", press(KeyEvent.KEYCODE_TAB, shift = true, ctrl = true))
        assertEquals("with Alt too, not ESC[1;4Z", "\u001b[Z", press(KeyEvent.KEYCODE_TAB, shift = true, alt = true))
    }

    @Test fun `the other special keys keep the modifiers libvterm gives them`() {
        assertEquals("\u001b[A", press(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals("\u001b[1;5D", press(KeyEvent.KEYCODE_DPAD_LEFT, ctrl = true))
        assertEquals("\u001b[1;2A", press(KeyEvent.KEYCODE_DPAD_UP, shift = true))
        assertEquals("\u001b[1;3C", press(KeyEvent.KEYCODE_DPAD_RIGHT, alt = true))
    }

    @Test fun `the toolbar's sticky Ctrl does the same to the next hardware key, then lets go`() {
        for ((keyCode, expected) in listOf(
            KeyEvent.KEYCODE_ENTER to "\r", KeyEvent.KEYCODE_TAB to "\t",
            KeyEvent.KEYCODE_DEL to "\b", KeyEvent.KEYCODE_ESCAPE to "\u001b",
        )) {
            rig.pty.clear()
            rig.modifiers.ctrl = true
            assertTrue(rig.rawKey(keyCode))
            assertEquals("key $keyCode with Ctrl armed", expected, rig.pty.stream)
            assertFalse("Ctrl is for one key", rig.modifiers.ctrl)
        }
    }

    @Test fun `an Enter the soft keyboard commits while Ctrl is armed is a CR`() {
        rig.modifiers.ctrl = true
        assertTrue(rig.ime.commitText("\n", 1))
        assertEquals("\r", rig.pty.stream)
        assertFalse(rig.modifiers.ctrl)
    }

    @Test fun `handed to libvterm as they are, these keys are CSI u, which is why they are not`() {
        // The model of the library, not the library: it keeps the tests above honest about what they avoid, and
        // CommittedImeInstrumentation holds it to the real JNI on a device.
        val pty = FakePty()
        pty.key(FakePty.CTRL, VTermKey.ENTER)
        pty.key(FakePty.CTRL, VTermKey.TAB)
        pty.key(FakePty.CTRL, VTermKey.BACKSPACE)
        pty.key(FakePty.CTRL, VTermKey.ESCAPE)
        pty.key(FakePty.SHIFT, VTermKey.BACKSPACE)
        pty.key(FakePty.ALT, VTermKey.TAB)
        assertEquals(
            "\u001b[13;5u" + "\u001b[9;5u" + "\u001b[127;5u" + "\u001b[27;5u" + "\u001b[127;2u" + "\u001b\t",
            pty.stream,
        )
    }
}
