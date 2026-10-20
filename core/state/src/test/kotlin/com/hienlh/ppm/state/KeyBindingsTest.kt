package com.hienlh.ppm.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** keybindings-store.ts and the Shift+Shift of use-global-keybindings.ts, as the app reads them. */
class KeyBindingsTest {
    @Test
    fun `a combo matches its key case-blind and its modifiers exactly`() {
        val modB = KeyCombo.parse("Mod+B")
        assertTrue(modB.matches(KeyPress("b", ctrl = true)))
        assertTrue(modB.matches(KeyPress("B", ctrl = true)))
        assertFalse(modB.matches(KeyPress("b", ctrl = true, shift = true)))
        assertFalse(modB.matches(KeyPress("b", meta = true)))
        assertFalse(modB.matches(KeyPress("b")))
        assertTrue(KeyCombo.parse("Alt+ArrowUp").matches(KeyPress("ArrowUp", alt = true)))
        assertTrue(KeyCombo.parse("F1").matches(KeyPress("F1")))
        assertTrue(KeyCombo.parse("Cmd+K").matches(KeyPress("k", meta = true)))
        assertTrue(DOCK_BACKTICK.matches(KeyPress("`", ctrl = true)))
        // Shift changes the key the web reports, so Mod+Shift+1 can never match: "!" is not "1".
        assertFalse(KeyCombo.parse("Mod+Shift+1").matches(KeyPress("!", ctrl = true, shift = true)))
    }

    @Test
    fun `an override replaces the default, and an empty one unbinds the action`() {
        val bindings = KeyBindings(mapOf("toggle-sidebar" to "Alt+S", "open-chat" to ""))
        assertEquals("Alt+S", bindings.binding("toggle-sidebar"))
        assertTrue(bindings.matches(KeyPress("s", alt = true), "toggle-sidebar"))
        assertFalse(bindings.matches(KeyPress("b", ctrl = true), "toggle-sidebar"))
        assertEquals("", bindings.binding("open-chat"))
        assertFalse(bindings.matches(KeyPress("l", ctrl = true), "open-chat"))
        assertEquals("Mod+,", bindings.binding("open-settings"))
        assertTrue(bindings.matches(KeyPress(",", ctrl = true), "open-settings"))
        assertTrue(bindings.matches(KeyPress("3", ctrl = true), "switch-project-3"))
        assertEquals("", bindings.binding("open-terminal"))
        assertEquals("", bindings.binding("no-such-action"))
    }

    @Test
    fun `two plain Shift taps within 400 ms open the palette`() {
        val shift = DoubleShift()
        shift.keyDown(isShift = true, shiftHeld = true)
        assertFalse(shift.shiftUp(1_000, otherModifiers = false, composing = false))
        shift.keyDown(isShift = true, shiftHeld = true)
        assertTrue(shift.shiftUp(1_300, otherModifiers = false, composing = false))
        // Spent: a third tap starts over.
        shift.keyDown(isShift = true, shiftHeld = true)
        assertFalse(shift.shiftUp(1_500, otherModifiers = false, composing = false))
    }

    @Test
    fun `slow taps, a key between them, Shift as a modifier or an IME composing do not`() {
        fun tap(shift: DoubleShift, at: Long, otherModifiers: Boolean = false, composing: Boolean = false): Boolean {
            shift.keyDown(isShift = true, shiftHeld = true)
            return shift.shiftUp(at, otherModifiers, composing)
        }
        DoubleShift().let { assertFalse(tap(it, 1_000)); assertFalse(tap(it, 1_400)) }
        DoubleShift().let {
            tap(it, 1_000)
            it.keyDown(isShift = false, shiftHeld = false)
            assertFalse(tap(it, 1_100))
        }
        DoubleShift().let {
            tap(it, 1_000)
            // Shift+T: Shift held for a capital, then let go.
            it.keyDown(isShift = true, shiftHeld = true)
            it.keyDown(isShift = false, shiftHeld = true)
            assertFalse(it.shiftUp(1_100, otherModifiers = false, composing = false))
        }
        DoubleShift().let { tap(it, 1_000); assertFalse(tap(it, 1_100, otherModifiers = true)) }
        DoubleShift().let { tap(it, 1_000); assertFalse(tap(it, 1_100, composing = true)) }
    }
}
