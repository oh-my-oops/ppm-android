package com.hienlh.ppm.state.terminal

import com.hienlh.ppm.state.terminal.Keystroke.Character
import com.hienlh.ppm.state.terminal.Keystroke.Named
import com.hienlh.ppm.state.terminal.MobileKey.CtrlC
import com.hienlh.ppm.state.terminal.MobileKey.Down
import com.hienlh.ppm.state.terminal.MobileKey.Esc
import com.hienlh.ppm.state.terminal.MobileKey.Left
import com.hienlh.ppm.state.terminal.MobileKey.Right
import com.hienlh.ppm.state.terminal.MobileKey.Tab
import com.hienlh.ppm.state.terminal.MobileKey.Up
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** terminal-mobile-toolbar.tsx's key row, and the sticky Ctrl that terminal-tab.tsx's `sendKey` got wrong. */
class MobileKeysTest {
    /** A finger on the row: Ctrl armed or not, and what each tap sent. */
    private class Row {
        var ctrl = false
        val sent = mutableListOf<Keystroke?>()

        fun tap(key: MobileKey) {
            val press = key.press(ctrl)
            ctrl = press.ctrlArmed
            sent += press.keystroke
        }
    }

    @Test
    fun `the row is the web's, key for key and in its order`() {
        assertEquals(listOf("Tab", "Esc", "Ctrl", "↑", "↓", "←", "→", "^C"), MobileKey.entries.map { it.label })
    }

    @Test
    fun `Ctrl arms and disarms, and sends nothing itself`() {
        assertEquals(MobileKeyPress(null, true), MobileKey.Ctrl.press(false))
        assertEquals(MobileKeyPress(null, false), MobileKey.Ctrl.press(true))
    }

    @Test
    fun `Tab, Esc and ^C send themselves with Ctrl armed or not, where the web swallowed them under Ctrl`() {
        for (armed in listOf(false, true)) {
            assertEquals(MobileKeyPress(Named(NamedKey.Tab), false), Tab.press(armed))
            assertEquals(MobileKeyPress(Named(NamedKey.Escape), false), Esc.press(armed))
            assertEquals(MobileKeyPress(Character('c', ctrl = true), false), CtrlC.press(armed))
        }
    }

    @Test
    fun `an arrow takes the armed Ctrl with it, where the web sent it bare and left Ctrl armed`() {
        for ((key, named) in listOf(Up to NamedKey.Up, Down to NamedKey.Down, Left to NamedKey.Left, Right to NamedKey.Right)) {
            assertEquals(MobileKeyPress(Named(named, ctrl = false), false), key.press(false))
            assertEquals(MobileKeyPress(Named(named, ctrl = true), false), key.press(true))
        }
    }

    @Test
    fun `Ctrl is for the next key only`() {
        val row = Row()
        row.tap(MobileKey.Ctrl)
        row.tap(Left)
        row.tap(Left)
        assertEquals(listOf(null, Named(NamedKey.Left, ctrl = true), Named(NamedKey.Left, ctrl = false)), row.sent)
        assertFalse(row.ctrl)
    }

    @Test
    fun `whatever key follows an armed Ctrl uses it up`() {
        for (key in MobileKey.entries.filter { it != MobileKey.Ctrl }) {
            val row = Row()
            row.tap(MobileKey.Ctrl)
            assertTrue(row.ctrl)
            row.tap(key)
            assertFalse("$key left Ctrl armed", row.ctrl)
        }
    }

    @Test
    fun `tapping Ctrl twice puts it down again`() {
        val row = Row()
        row.tap(MobileKey.Ctrl)
        row.tap(MobileKey.Ctrl)
        row.tap(Up)
        assertEquals(listOf(null, null, Named(NamedKey.Up, ctrl = false)), row.sent)
    }

    @Test
    fun `keys with Ctrl down leave it down`() {
        val row = Row()
        row.tap(Tab)
        row.tap(Esc)
        row.tap(CtrlC)
        assertFalse(row.ctrl)
        assertEquals(listOf(Named(NamedKey.Tab), Named(NamedKey.Escape), Character('c', ctrl = true)), row.sent)
    }

    @Test
    fun `only the arrows repeat when held`() {
        assertEquals(listOf(Up, Down, Left, Right), MobileKey.entries.filter { it.repeats })
    }
}
