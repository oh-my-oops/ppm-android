package com.hienlh.ppm.terminal

import org.connectbot.terminal.TerminalBufferLine
import org.connectbot.terminal.TerminalBufferSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTerminalHistoryTest {
    private fun snapshot(vararg rows: String, cursor: Int = rows.lastIndex, screenStart: Int = 0) = TerminalBufferSnapshot(
        rows.map { TerminalBufferLine(it, false) }, cursor, 0, screenStart, alternateScreen = false,
    )

    @Test
    fun `without Enter command is empty and output is the screen through the cursor, not the scrollback above it`() {
        val history = NativeTerminalHistory()
        val buffer = snapshot("scrolled off", "also scrolled off", "banner", "  indented output   ", "   ", "stale row below cursor", cursor = 4, screenStart = 2)
        assertEquals("", history.lastCommand(buffer))
        assertEquals("banner\n  indented output", history.lastOutput(buffer))
        assertEquals("", history.lastOutput(snapshot()))
        assertEquals("", history.lastOutput(snapshot("   ", "\u00a0\ufeff")))
    }

    @Test
    fun `Enter captures the absolute prompt row before send and output starts on the next row`() {
        val history = NativeTerminalHistory()
        var buffer = snapshot("older history", "another command", "  $ printf hello   ", cursor = 2, screenStart = 2)
        assertTrue(history.send("\r", { buffer }) {
            buffer = snapshot("older history", "another command", "  $ printf hello   ", "hello    ", "", cursor = 4, screenStart = 3)
            true
        })
        assertEquals("  $ printf hello", history.lastCommand(buffer))
        assertEquals("hello", history.lastOutput(buffer))
    }

    @Test
    fun `a known command keeps all of its output, however far up into the scrollback it has gone`() {
        val history = NativeTerminalHistory()
        history.send("\r", { snapshot("$ seq 1 6", cursor = 0) }) { true }
        // The screen is the last two rows; the output began five rows above it.
        val after = snapshot("$ seq 1 6", "1", "2", "3", "4", "5", "6", "$ ", cursor = 7, screenStart = 6)
        assertEquals("$ seq 1 6", history.lastCommand(after))
        assertEquals("1\n2\n3\n4\n5\n6\n$", history.lastOutput(after))
    }

    @Test
    fun `ordinary input needs no snapshot and a rejected Enter does not replace the command boundary`() {
        val history = NativeTerminalHistory()
        assertTrue(history.send("typed", { error("Non-Enter input must not copy the buffer") }) { true })
        history.send("\r", { snapshot("$ first", cursor = 0) }) { true }
        val buffer = snapshot("$ first", "result", "$ rejected", cursor = 2)
        assertFalse(history.send("\r", { buffer }) { false })
        assertEquals("$ first", history.lastCommand(buffer))
        assertEquals("result\n$ rejected", history.lastOutput(buffer))
    }

    @Test
    fun `CR LF and multiline input each track the current physical row once`() {
        for (input in listOf("\r", "\n", "\r\n", "echo one\necho two\r")) {
            val history = NativeTerminalHistory()
            var reads = 0
            history.send(input, { reads++; snapshot("earlier", "$ prompt", cursor = 1) }) { true }
            val after = snapshot("earlier", "$ prompt", "one", "two")
            assertEquals(input, 1, reads)
            assertEquals("$ prompt", history.lastCommand(after))
            assertEquals("one\ntwo", history.lastOutput(after))
        }
    }

    @Test
    fun `a new Enter replaces the previous command and removes only trailing blank output rows`() {
        val history = NativeTerminalHistory()
        history.send("\r", { snapshot("$ first") }) { true }
        val prompt = snapshot("$ first", "first output", "  $ second   ")
        history.send("\n", { prompt }) { true }
        val after = snapshot("$ first", "first output", "  $ second   ", "", "  second output  ", "", "   ")
        assertEquals("  $ second", history.lastCommand(after))
        assertEquals("\n  second output", history.lastOutput(after))
    }

    @Test
    fun `output keeps physical soft wrap boundaries while command reads only its captured row`() {
        val history = NativeTerminalHistory()
        val buffer = TerminalBufferSnapshot(
            listOf(
                TerminalBufferLine("$ wrapped comm", true),
                TerminalBufferLine("and", false),
                TerminalBufferLine("long output", true),
                TerminalBufferLine("continued", false),
            ), cursorRow = 1, cursorColumn = 3, screenStartRow = 0, alternateScreen = false,
        )
        history.send("\r", { buffer }) { true }
        assertEquals("and", history.lastCommand(buffer))
        assertEquals("long output\ncontinued", history.lastOutput(buffer.copy(cursorRow = 3)))
    }

    @Test
    fun `reset discards old row markers before replay and shorter buffers stay safe`() {
        val history = NativeTerminalHistory()
        history.send("\r", { snapshot("history", "$ old", cursor = 1) }) { true }
        assertEquals("", history.lastCommand(snapshot("shorter")))
        assertEquals("", history.lastOutput(snapshot("shorter")))
        history.reset()
        val replay = snapshot("replayed history", "$ new prompt")
        assertEquals("", history.lastCommand(replay))
        assertEquals("replayed history\n$ new prompt", history.lastOutput(replay))
    }

    @Test
    fun `native next-row wrap flags convert to previous-row flags for web URL scanning`() {
        val history = NativeTerminalHistory()
        val buffer = TerminalBufferSnapshot(
            listOf(
                TerminalBufferLine("https://older.test/page", false),
                TerminalBufferLine("https://long.test/part", true),
                TerminalBufferLine("two?key=", true),
                TerminalBufferLine("value).   ", false),
                TerminalBufferLine("https://older.test/page", false),
                TerminalBufferLine("https://latest.test/x!", false),
                TerminalBufferLine("https://below-cursor.test/", false),
            ), cursorRow = 5, cursorColumn = 0, screenStartRow = 3, alternateScreen = false,
        )
        assertEquals(
            listOf("https://latest.test/x!", "https://long.test/parttwo?key=value", "https://older.test/page"),
            history.bufferUrls(buffer),
        )
    }

    @Test
    fun `URL scan keeps the web physical row limit and alternate screen coordinates`() {
        val history = NativeTerminalHistory()
        val rows = MutableList(5_004) { TerminalBufferLine("", false) }
        rows[0] = TerminalBufferLine("https://too-old.test", false)
        rows[2] = TerminalBufferLine("https://boundary.test/part", true)
        rows[3] = TerminalBufferLine("two", false)
        rows[5_002] = TerminalBufferLine("https://latest.test", false)
        rows[5_003] = TerminalBufferLine("https://below-cursor.test", false)
        val snapshot = TerminalBufferSnapshot(rows, 5_002, 0, 5_000, alternateScreen = false)
        assertEquals(listOf("https://latest.test", "https://boundary.test/parttwo"), history.bufferUrls(snapshot))
        assertEquals(listOf("https://alternate.test"), history.bufferUrls(
            snapshot("https://alternate.test").copy(alternateScreen = true),
        ))
    }
}
