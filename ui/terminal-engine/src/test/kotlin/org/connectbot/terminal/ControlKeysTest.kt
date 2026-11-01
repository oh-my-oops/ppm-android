package org.connectbot.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ctrl held with a character, as xterm.js's `evaluateKeyboardEvent` (`Keyboard.ts`, the `default:`
 * branch for a character key) answers it. The comments name the key it reads each from.
 */
class ControlKeysTest {
    @Test fun `every letter of either case is its control code, i j and m included`() {
        for (letter in 'a'..'z') {
            val expected = letter - 'a' + 1 // keyCode 65..90: String.fromCharCode(keyCode - 64)
            assertEquals("Ctrl+$letter", expected, ControlKeys.code(letter.code))
            assertEquals("Ctrl+${letter.uppercaseChar()}", expected, ControlKeys.code(letter.uppercaseChar().code))
        }
        assertEquals("Ctrl+I is Tab", 0x09, ControlKeys.code('i'.code))
        assertEquals("Ctrl+J is a line feed", 0x0a, ControlKeys.code('j'.code))
        assertEquals("Ctrl+M is a carriage return", 0x0d, ControlKeys.code('m'.code))
    }

    @Test fun `the brackets, backslash, caret and underscore are the five control codes after Escape`() {
        assertEquals(0x1b, ControlKeys.code('['.code)) // keyCode 219
        assertEquals(0x1c, ControlKeys.code('\\'.code)) // 220
        assertEquals(0x1d, ControlKeys.code(']'.code)) // 221
        assertEquals(0x1e, ControlKeys.code('^'.code)) // Ctrl+Shift+6
        assertEquals(0x1f, ControlKeys.code('_'.code)) // Ctrl+Shift+-
    }

    @Test fun `Space, at sign, the digits 3 to 8 and the slash`() {
        assertEquals(0x00, ControlKeys.code(' '.code)) // keyCode 32
        assertEquals(0x00, ControlKeys.code('@'.code)) // Ctrl+Shift+2
        for ((digit, expected) in listOf('3' to 0x1b, '4' to 0x1c, '5' to 0x1d, '6' to 0x1e, '7' to 0x1f, '8' to 0x7f)) {
            assertEquals("Ctrl+$digit", expected, ControlKeys.code(digit.code)) // keyCode 51..56
        }
        assertEquals(0x1f, ControlKeys.code('/'.code)) // key '/'
    }

    @Test fun `with Shift held only the at sign, caret and underscore are sent`() {
        assertEquals(0x00, ControlKeys.code('@'.code, shift = true))
        assertEquals(0x1e, ControlKeys.code('^'.code, shift = true))
        assertEquals(0x1f, ControlKeys.code('_'.code, shift = true))
        // Ctrl+Shift+C is not an interrupt: xterm.js's letter branch needs Shift up.
        for (letter in 'a'..'z') assertNull("Ctrl+Shift+$letter", ControlKeys.code(letter.code, shift = true))
        for (letter in 'A'..'Z') assertNull("Ctrl+Shift+$letter", ControlKeys.code(letter.code, shift = true))
        assertNull(ControlKeys.code(' '.code, shift = true))
        assertNull(ControlKeys.code('/'.code, shift = true))
    }

    @Test fun `what xterm_js sends nothing for is not given a code`() {
        for (char in listOf('0', '1', '2', '9', '-', '=', ';', '\'', ',', '.', '`', '{', '}', '|', ':', '"', '<', '>', '?', '!', '#', '&', '*', '(', ')', '+', '~')) {
            assertNull("Ctrl+$char", ControlKeys.code(char.code))
        }
        for (char in listOf('é', 'ê', '日', 'я', '\u007f', '\t', '\n', '\r')) assertNull("U+${char.code.toString(16)}", ControlKeys.code(char.code))
        assertNull("an emoji", ControlKeys.code(0x1f600))
    }
}
