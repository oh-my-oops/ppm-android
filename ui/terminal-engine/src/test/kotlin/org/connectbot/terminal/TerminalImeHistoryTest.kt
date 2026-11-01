package org.connectbot.terminal

import org.junit.Assert.*
import org.junit.Test

class TerminalImeHistoryTest {
    private class Terminal {
        var text = "outside> "
        fun apply(edit: TerminalImeHistory.Rewrite) {
            repeat(edit.deleteCodePoints) { text = text.substring(0, text.offsetByCodePoints(text.length, -1)) }
            text += edit.append
        }
    }

    @Test fun `captured Gboard Telex commit and replace sequence produces exact Vietnamese`() {
        val history = TerminalImeHistory()
        val terminal = Terminal()
        // Captured from real Gboard on API 37, including the unchanged suffix in each replace.
        val trace = listOf("t", "ti", "tie", "tiê", "tiên", "tiêng", "tiếng", "tiếng ",
            "tiếng v", "tiếng vi", "tiếng vie", "tiếng viê", "tiếng viêt", "tiếng việt", "tiếng việt ")
        for (text in trace) terminal.apply(history.update(text))
        assertEquals("outside> tiếng việt ", terminal.text)
        assertEquals(trace.last(), history.text)
    }

    @Test fun `replacement erases the changed tail and resends the surviving suffix`() {
        val history = TerminalImeHistory()
        val terminal = Terminal()
        terminal.apply(history.update("abcdef"))
        val rewrite = history.update("ab道😀ef")
        assertEquals(TerminalImeHistory.Rewrite(4, "道😀ef", false), rewrite)
        terminal.apply(rewrite)
        assertEquals("outside> ab道😀ef", terminal.text)
    }

    @Test fun `supplementary character replacement never preserves half a surrogate pair`() {
        val history = TerminalImeHistory()
        history.update("A😀B")
        assertEquals(TerminalImeHistory.Rewrite(2, "😁B", false), history.update("A😁B"))
        assertEquals(TerminalImeHistory.Rewrite(2, "B", false), history.update("AB"))
    }

    @Test fun `diff uses the same NFC normalization as terminal dispatch`() {
        val history = TerminalImeHistory()
        assertEquals("é", history.update("e\u0301").append)
        assertEquals(TerminalImeHistory.Rewrite(0, "", false), history.update("é"))
        assertEquals(TerminalImeHistory.Rewrite(1, "ế", false), history.update("e\u0302\u0301"))
    }

    @Test fun `repeated independent identical commits append while unchanged finish is a no-op`() {
        val history = TerminalImeHistory()
        history.update("你")
        assertEquals(TerminalImeHistory.Rewrite(0, "你", false), history.update("你你"))
        assertEquals(TerminalImeHistory.Rewrite(0, "", false), history.update("你你"))
    }

    @Test fun `clearing ownership prevents a later edit from deleting prior terminal text`() {
        val history = TerminalImeHistory()
        val terminal = Terminal()
        terminal.apply(history.update("old"))
        history.clear()
        terminal.apply(history.update(""))
        terminal.apply(history.update("new"))
        assertEquals("outside> oldnew", terminal.text)
    }

    @Test fun `history stays bounded and overflow ends the editable generation`() {
        val history = TerminalImeHistory(8)
        history.update("12345678")
        val overflow = history.update("123456789😀")
        assertEquals(TerminalImeHistory.Rewrite(0, "9😀", true), overflow)
        assertEquals("", history.text)
    }

    @Test fun `Enter and terminal control characters end ownership after their emitted text`() {
        for (control in listOf('\r', '\n', '\t', '\u001b', '\u0003', '\u007f')) {
            val history = TerminalImeHistory()
            history.update("abc")
            assertEquals(TerminalImeHistory.Rewrite(0, "$control", true), history.update("abc$control"))
            assertEquals("", history.text)
        }
    }

    @Test fun `UTF16 index validation rejects malformed text and split emoji endpoints`() {
        assertTrue(validImeUnicode("tiếng😀中文"))
        assertFalse(validImeUnicode("\uD83D"))
        assertFalse(validImeUnicode("\uDE00"))
        assertFalse(unicodeBoundary("A😀B", 2))
        assertTrue(unicodeBoundary("A😀B", 3))
        assertFalse(unicodeBoundary("A😀B", -1))
        assertFalse(unicodeBoundary("A😀B", 5))
    }
}
