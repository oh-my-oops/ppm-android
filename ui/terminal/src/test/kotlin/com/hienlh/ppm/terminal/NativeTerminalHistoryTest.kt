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
    fun `only the first input after an Enter looks at the buffer, and a rejected Enter does not replace the command boundary`() {
        val history = NativeTerminalHistory()
        assertTrue(history.send("t", { snapshot("$ ") }) { true })
        assertTrue(history.send("yped", { error("Ordinary input after the first must not copy the buffer") }) { true })
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
        // The row Enter was sent on is not in the buffer any more, so there is no command to start from: the screen.
        assertEquals("", history.lastCommand(snapshot("shorter")))
        assertEquals("shorter", history.lastOutput(snapshot("shorter")))
        history.reset()
        val replay = snapshot("replayed history", "$ new prompt")
        assertEquals("", history.lastCommand(replay))
        assertEquals("replayed history\n$ new prompt", history.lastOutput(replay))
    }

    /**
     * The shell's buffer as its emulator held it when a reconnect replaced it: `seq 1 3` entered on row 2, its output
     * below and a prompt after. The server's replay rebuilds a buffer, and its rows need not be these.
     */
    private val before = snapshot("$ echo old", "old", "$ seq 1 3  ", "1", "2", "3", "$ ")

    private fun afterEnter(): NativeTerminalHistory = NativeTerminalHistory().also {
        it.send("\r", { snapshot("$ echo old", "old", "$ seq 1 3", cursor = 2) }) { true }
    }

    @Test
    fun `a replay with the same rows has the command and its output, whatever trailing spaces its rows end in`() {
        val history = afterEnter()
        history.replaced(before)
        val replay = snapshot("$ echo old", "old", "$ seq 1 3   ", "1", "2", "3", "$ ")
        assertEquals("$ seq 1 3", history.lastCommand(replay))
        assertEquals("1\n2\n3\n$", history.lastOutput(replay))
    }

    @Test
    fun `a replay with fewer rows above the command still has it, where the old row number is past its cursor`() {
        // The old buffer has six rows above the command that the replay does not draw. Row 8 of it is past the replay's
        // cursor on row 6, which read as nothing at all.
        val history = NativeTerminalHistory()
        val old = snapshot("%", "% ", "%  ", "%", "% ", "%  ", "$ echo old", "old", "$ seq 1 3", "1", "2", "3", "$ ")
        history.send("\r", { old.copy(cursorRow = 8) }) { true }
        history.replaced(old)
        assertEquals("$ seq 1 3", history.lastCommand(before))
        assertEquals("1\n2\n3\n$", history.lastOutput(before))
    }

    @Test
    fun `a replay with more rows above the command does not take the rows the old number points at for it`() {
        val history = afterEnter()
        history.replaced(before)
        val replay = snapshot("banner", "message of the day", "$ echo old", "old", "$ seq 1 3", "1", "2", "3", "$ ")
        assertEquals("$ seq 1 3", history.lastCommand(replay))
        assertEquals("1\n2\n3\n$", history.lastOutput(replay))
    }

    @Test
    fun `a command the replay does not hold is the screen, and the same command is found on a later read`() {
        val history = afterEnter()
        history.replaced(before)
        // The replay is still arriving: its rows so far stop before the command.
        val partial = snapshot("$ echo old", "old", "$ ", cursor = 2, screenStart = 1)
        assertEquals("", history.lastCommand(partial))
        assertEquals("old\n$", history.lastOutput(partial))
        // The rest of it has come.
        assertEquals("$ seq 1 3", history.lastCommand(before))
        assertEquals("1\n2\n3\n$", history.lastOutput(before))
    }

    @Test
    fun `of equal rows above the cursor the nearest is the command`() {
        val history = NativeTerminalHistory()
        history.send("\r", { snapshot("$ ls", "a b", "$ ls", cursor = 2) }) { true }
        history.replaced(snapshot("$ ls", "a b", "$ ls", "c d", "$ "))
        // A nearer row holding the command's text and more is another command.
        val replay = snapshot("banner", "$ ls", "a b", "$ ls", "c d", "$ ls -la", "e f", "$ ")
        assertEquals("$ ls", history.lastCommand(replay))
        assertEquals("c d\n$ ls -la\ne f\n$", history.lastOutput(replay))
    }

    @Test
    fun `a read while the replay is still arriving does not fix the command on an earlier row of the same text`() {
        val history = NativeTerminalHistory()
        history.send("\r", { snapshot("$ ls", "a b", "$ ls", cursor = 2) }) { true }
        history.replaced(snapshot("$ ls", "a b", "$ ls", "c d", "$ "))
        // The replay so far holds the first `ls` only.
        val partial = snapshot("$ ls", "a b", "$ ")
        assertEquals("a b\n$", history.lastOutput(partial))
        val replay = snapshot("$ ls", "a b", "$ ls", "c d", "$ ")
        assertEquals("c d\n$", history.lastOutput(replay))
    }

    @Test
    fun `a command with no output, on the row just above the prompt, is found`() {
        val history = NativeTerminalHistory()
        history.send("\r", { snapshot("$ cd /tmp", cursor = 0) }) { true }
        history.replaced(snapshot("$ cd /tmp", "$ "))
        val replay = snapshot("banner", "$ cd /tmp", "$ ")
        assertEquals("$ cd /tmp", history.lastCommand(replay))
        assertEquals("$", history.lastOutput(replay))
    }

    @Test
    fun `the cursor's own row is not the command, though it holds the same text`() {
        // The last command recalled to the prompt with the up arrow and not yet entered.
        val history = NativeTerminalHistory()
        history.send("\r", { snapshot("$ ls", cursor = 0) }) { true }
        history.replaced(snapshot("$ ls", "a b", "$ "))
        val replay = snapshot("$ ls", "a b", "$ ls")
        assertEquals("$ ls", history.lastCommand(replay))
        assertEquals("a b\n$ ls", history.lastOutput(replay))
    }

    @Test
    fun `an Enter after the reconnect is the command, not the text carried over it`() {
        val history = afterEnter()
        history.replaced(before)
        val replay = snapshot("$ echo old", "old", "$ seq 1 3", "1", "2", "3", "$ date", cursor = 6)
        history.send("\r", { replay }) { true }
        val after = snapshot("$ echo old", "old", "$ seq 1 3", "1", "2", "3", "$ date", "Sun", "$ ")
        assertEquals("$ date", history.lastCommand(after))
        assertEquals("Sun\n$", history.lastOutput(after))
    }

    @Test
    fun `a command row with no text is not searched for`() {
        // Every blank row of the replay would be it.
        val history = NativeTerminalHistory()
        history.send("\r", { snapshot("   ", cursor = 0) }) { true }
        history.replaced(snapshot("   ", "x", "$ "))
        val replay = snapshot("a", "", "b", "c")
        assertEquals("", history.lastCommand(replay))
        assertEquals("a\n\nb\nc", history.lastOutput(replay))
    }

    @Test
    fun `a reconnect before any Enter has no command to carry over, so the screen is read`() {
        val history = NativeTerminalHistory()
        history.replaced(snapshot("$ echo old", "old", "$ "))
        val replay = snapshot("banner", "$ echo old", "old", "$ ", screenStart = 2)
        assertEquals("", history.lastCommand(replay))
        assertEquals("old\n$", history.lastOutput(replay))
    }

    @Test
    fun `a second replacement before any read keeps what the first one took`() {
        val history = afterEnter()
        history.replaced(before)
        // The replay was cut short and a reconnect replaced that emulator too.
        history.replaced(snapshot("$ echo old", "old"))
        assertEquals("$ seq 1 3", history.lastCommand(before))
        assertEquals("1\n2\n3\n$", history.lastOutput(before))
    }

    @Test
    fun `reset drops the text carried over a replacement`() {
        val history = afterEnter()
        history.replaced(before)
        history.reset()
        assertEquals("", history.lastCommand(before))
        assertEquals("$ echo old\nold\n$ seq 1 3\n1\n2\n3\n$", history.lastOutput(before))
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
