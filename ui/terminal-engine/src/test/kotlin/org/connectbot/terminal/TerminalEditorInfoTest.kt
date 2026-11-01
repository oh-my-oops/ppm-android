package org.connectbot.terminal

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The numbers `dumpsys input_method` shows for Chrome's xterm.js textarea, measured on a tablet with
 * Gboard: inputType 0x2200a1 and imeOptions 0x12000001. Gboard does not autocorrect that editor, and
 * it did autocorrect the 0xa0001 / 0x51000001 this app sent before ("ech " came out as "echo ").
 */
class TerminalEditorInfoTest {
    @Test fun `before Android 17 the input type is a multi-line web edit text`() {
        assertEquals(0x200a1, TerminalEditorInfo.inputType(sdk = 26))
        assertEquals(0x200a1, TerminalEditorInfo.inputType(sdk = 36))
    }

    @Test fun `from Android 17 it also carries the flag Chrome adds`() {
        assertEquals(0x2200a1, TerminalEditorInfo.inputType(sdk = 37))
    }

    @Test fun `it asks for no correction, no completion and no capital letters`() {
        val asked = InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE or
            InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        for (sdk in listOf(26, 36, 37)) assertEquals("Android $sdk", 0, TerminalEditorInfo.inputType(sdk) and asked)
    }

    @Test fun `the ime options are Chrome's, with no action, no full-screen editor and no extracted text`() {
        assertEquals(0x12000001, TerminalEditorInfo.IME_OPTIONS)
    }
}
