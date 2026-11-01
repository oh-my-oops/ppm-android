package com.hienlh.ppm.terminal

import com.hienlh.ppm.state.terminal.MobileKey
import org.connectbot.terminal.VTermKey
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The toolbar's keys as the emulator is told of them, and the bytes libvterm turns that into. The
 * real libvterm cannot load on the JVM, so [libvterm] writes out what `vterm_keyboard_key` and
 * `vterm_keyboard_unichar` do for exactly these keys: it is a model, and the device run of the
 * toolbar is what holds it to the real thing.
 */
class EngineKeyTest {
    /** `VTERM_MOD_CTRL`; the shift is 1 and the alt 2. */
    private val ctrl = 4

    private fun libvterm(key: EngineKey, applicationCursorKeys: Boolean = false): String = when (key) {
        is EngineKey.Text -> {
            check(key.modifiers == ctrl && key.codepoint in 'a'.code..'z'.code) { "only Ctrl and a lowercase letter are modelled" }
            (key.codepoint and 0x1f).toChar().toString()
        }
        is EngineKey.Special -> when (key.code) {
            VTermKey.TAB -> "\t"
            VTermKey.ESCAPE -> "\u001b"
            else -> {
                val letter = mapOf(VTermKey.UP to 'A', VTermKey.DOWN to 'B', VTermKey.RIGHT to 'C', VTermKey.LEFT to 'D').getValue(key.code)
                when {
                    // A modifier turns the arrow into CSI 1 ; <modifier + 1> <letter>, in either cursor mode.
                    key.modifiers != 0 -> "\u001b[1;${key.modifiers + 1}$letter"
                    applicationCursorKeys -> "\u001bO$letter"
                    else -> "\u001b[$letter"
                }
            }
        }
    }

    private fun bytes(key: MobileKey, ctrlArmed: Boolean = false, applicationCursorKeys: Boolean = false): String =
        libvterm(engineKey(key.press(ctrlArmed).keystroke!!), applicationCursorKeys)

    @Test
    fun `at a prompt every key is the bytes the web's row sends`() {
        assertEquals("\t", bytes(MobileKey.Tab))
        assertEquals("\u001b", bytes(MobileKey.Esc))
        assertEquals("\u001b[A", bytes(MobileKey.Up))
        assertEquals("\u001b[B", bytes(MobileKey.Down))
        assertEquals("\u001b[D", bytes(MobileKey.Left))
        assertEquals("\u001b[C", bytes(MobileKey.Right))
        assertEquals("\u0003", bytes(MobileKey.CtrlC))
    }

    @Test
    fun `a program that asked for application cursor keys gets the arrows it reads`() {
        assertEquals("\u001bOA", bytes(MobileKey.Up, applicationCursorKeys = true))
        assertEquals("\u001bOB", bytes(MobileKey.Down, applicationCursorKeys = true))
        assertEquals("\u001bOD", bytes(MobileKey.Left, applicationCursorKeys = true))
        assertEquals("\u001bOC", bytes(MobileKey.Right, applicationCursorKeys = true))
        // The rest do not depend on the mode.
        assertEquals("\t", bytes(MobileKey.Tab, applicationCursorKeys = true))
        assertEquals("\u0003", bytes(MobileKey.CtrlC, applicationCursorKeys = true))
    }

    @Test
    fun `an arrow under an armed Ctrl is the modified arrow in either mode`() {
        for (mode in listOf(false, true)) {
            assertEquals("\u001b[1;5A", bytes(MobileKey.Up, ctrlArmed = true, applicationCursorKeys = mode))
            assertEquals("\u001b[1;5B", bytes(MobileKey.Down, ctrlArmed = true, applicationCursorKeys = mode))
            assertEquals("\u001b[1;5D", bytes(MobileKey.Left, ctrlArmed = true, applicationCursorKeys = mode))
            assertEquals("\u001b[1;5C", bytes(MobileKey.Right, ctrlArmed = true, applicationCursorKeys = mode))
        }
    }

    @Test
    fun `Tab, Esc and ^C are the same bytes whether Ctrl was armed or not`() {
        for (key in listOf(MobileKey.Tab, MobileKey.Esc, MobileKey.CtrlC)) {
            assertEquals(bytes(key, ctrlArmed = false), bytes(key, ctrlArmed = true))
        }
    }

    @Test
    fun `the emulator is called with libvterm's key codes and modifier bits`() {
        assertEquals(EngineKey.Special(0, VTermKey.TAB), engineKey(MobileKey.Tab.press(false).keystroke!!))
        assertEquals(EngineKey.Special(0, VTermKey.ESCAPE), engineKey(MobileKey.Esc.press(false).keystroke!!))
        assertEquals(EngineKey.Special(0, VTermKey.UP), engineKey(MobileKey.Up.press(false).keystroke!!))
        assertEquals(EngineKey.Special(ctrl, VTermKey.LEFT), engineKey(MobileKey.Left.press(true).keystroke!!))
        assertEquals(EngineKey.Text(ctrl, 'c'.code), engineKey(MobileKey.CtrlC.press(false).keystroke!!))
        // libvterm's own numbering, which a drift in the engine would otherwise turn into the wrong key.
        assertEquals(listOf(5, 6, 7, 8), listOf(VTermKey.UP, VTermKey.DOWN, VTermKey.LEFT, VTermKey.RIGHT))
    }
}
