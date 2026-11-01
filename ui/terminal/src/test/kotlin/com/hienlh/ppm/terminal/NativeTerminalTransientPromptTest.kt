package com.hienlh.ppm.terminal

import org.connectbot.terminal.TerminalBufferLine
import org.connectbot.terminal.TerminalBufferSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the last command is when the shell redraws its prompt after Enter. The project's login shell is a zsh with an
 * oh-my-posh theme whose prompt is four rows (a blank spacer, a powerline banner, `╭─ ♥ 21:37 | tmp → ▶ → demo` and `╰─ `)
 * and is transient: on Enter it collapses to the one row `❯ seq 1 3` and everything below moves up three, so the row Enter
 * was sent on then holds the last row of the output (`3`), or the new banner when the output is one row. These are the rows
 * the tablet showed (tester2, e86c388c); a row is padded to the terminal's width, as the emulator pads it.
 */
class NativeTerminalTransientPromptTest {
    private fun buffer(
        rows: List<String>,
        column: Int,
        wrapped: Set<Int> = emptySet(),
        cursor: Int = rows.lastIndex,
        screenStart: Int = 0,
        alternate: Boolean = false,
        width: Int = WIDTH,
    ) = TerminalBufferSnapshot(
        rows.mapIndexed { row, text -> TerminalBufferLine(text.padEnd(width), row in wrapped) },
        cursor, column, screenStart, alternate,
    )

    /** The four rows of the prompt, with [typed] after the `╰─ ` of the last. */
    private fun block(time: String, typed: String = "") = listOf("", BANNER, "╭─ ♥ $time | tmp → ▶ → demo", "╰─ $typed")

    /** The prompt ready for input under [above], the cursor after `╰─ `. */
    private fun waiting(time: String, above: List<String> = listOf("older")) = buffer(above + block(time), column = 3)

    /** The prompt with [text] typed after `╰─ ` and the cursor after it: what the buffer holds when Enter is pressed. */
    private fun typed(time: String, text: String, above: List<String> = listOf("older")) =
        buffer(above + block(time, text), column = 3 + text.length)

    /** The history of a session in which `seq 1 3` was typed at the prompt and entered. */
    private fun enteredSeq(): NativeTerminalHistory = NativeTerminalHistory().also {
        it.send("s", { waiting("21:36") }) { true }
        it.send("eq 1 3", { error("Typing after the first key must not copy the buffer") }) { true }
        it.send("\r", { typed("21:36", "seq 1 3") }) { true }
    }

    /** `seq 1 3` after Enter: the four rows redrawn as one, the output under it, a new prompt below. */
    private fun afterSeq(time: String = "21:37", typedAtPrompt: String = "") = buffer(
        listOf("older", "❯ seq 1 3", "1", "2", "3") + block(time, typedAtPrompt), column = 3 + typedAtPrompt.length,
    )

    private val seqOutput = "1\n2\n3\n\n$BANNER\n╭─ ♥ 21:37 | tmp → ▶ → demo\n╰─"

    @Test
    fun `a prompt that collapses after Enter has its command on the collapsed row and the output below it`() {
        val history = enteredSeq()
        val after = afterSeq()
        // The row Enter was sent on holds the last output row now.
        assertEquals("3", after.lines[4].text.trim())
        assertEquals("❯ seq 1 3", history.lastCommand(after))
        assertEquals(seqOutput, history.lastOutput(after))
    }

    @Test
    fun `a one row output leaves the new banner on the Enter row, and the command is found all the same`() {
        val history = NativeTerminalHistory()
        history.send("d", { waiting("21:41") }) { true }
        history.send("\r", { typed("21:41", "date") }) { true }
        val after = buffer(listOf("older", "❯ date", "Sun Nov  1 21:42:11 +07 2026") + block("21:42"), column = 3)
        assertEquals(BANNER, after.lines[4].text.trim())
        assertEquals("❯ date", history.lastCommand(after))
        assertEquals("Sun Nov  1 21:42:11 +07 2026\n\n$BANNER\n╭─ ♥ 21:42 | tmp → ▶ → demo\n╰─", history.lastOutput(after))
    }

    @Test
    fun `a command of two rows that wraps again after the collapse is found by its last row`() {
        val command = ": " + "0123456789".repeat(11) + "; echo ok"
        assertEquals(121, command.length)
        val width = 105
        val head = listOf("older", "", BANNER, "╭─ ♥ 21:43 | tmp → ▶ → demo")
        val history = NativeTerminalHistory()
        history.send(":", { buffer(head + "╰─ ", column = 3, width = width) }) { true }
        // `╰─ ` and the command are 124 columns: the first row is full and 19 are left for the second.
        val entered = "╰─ $command"
        history.send("\r", { buffer(head + entered.take(width) + entered.drop(width), column = entered.length - width, wrapped = setOf(4), width = width) }) { true }
        // `❯ ` is a column shorter, so the second row is 18 long now, and the banner is where the second row was.
        val collapsed = "❯ $command"
        val after = buffer(listOf("older") + collapsed.take(width) + collapsed.drop(width) + "ok" + block("21:44"), column = 3, wrapped = setOf(1), width = width)
        assertEquals(BANNER, after.lines[5].text.trim())
        assertEquals("123456789; echo ok", history.lastCommand(after))
        assertEquals("ok\n\n$BANNER\n╭─ ♥ 21:44 | tmp → ▶ → demo\n╰─", history.lastOutput(after))
    }

    @Test
    fun `of two collapsed rows with the same command the nearest is the command`() {
        val above = listOf("older", "❯ ls", "a b")
        val history = NativeTerminalHistory()
        history.send("l", { waiting("21:38", above) }) { true }
        history.send("\r", { typed("21:38", "ls", above) }) { true }
        val after = buffer(listOf("older", "❯ ls", "a b", "❯ ls", "c d") + block("21:39"), column = 3)
        assertEquals("❯ ls", history.lastCommand(after))
        assertEquals("c d\n\n$BANNER\n╭─ ♥ 21:39 | tmp → ▶ → demo\n╰─", history.lastOutput(after))
    }

    @Test
    fun `a key typed ahead while the last command still runs starts nowhere, so the screen is read`() {
        val history = NativeTerminalHistory()
        history.send("\r", { typed("21:35", "sleep 5") }) { true }
        // The command has printed `...` and is still running, and the key is typed with the cursor after it.
        history.send("l", { buffer(listOf("older", "❯ sleep 5", "..."), column = 3) }) { true }
        // When sleep ends the shell draws its prompt below and echoes the key there.
        history.send("\r", { buffer(listOf("older", "❯ sleep 5", "...") + block("21:36", "ls"), column = 5) }) { true }
        val after = buffer(listOf("older", "❯ sleep 5", "...", "❯ ls", "a b") + block("21:36"), column = 3, screenStart = 1)
        assertEquals("", history.lastCommand(after))
        assertEquals("❯ sleep 5\n...\n❯ ls\na b\n\n$BANNER\n╭─ ♥ 21:36 | tmp → ▶ → demo\n╰─", history.lastOutput(after))
    }

    @Test
    fun `a prompt that stays where it was has its Enter row as the command, as before`() {
        val history = NativeTerminalHistory()
        history.send("s", { buffer(listOf("older", "bash-5.3$ "), column = 10) }) { true }
        history.send("\r", { buffer(listOf("older", "bash-5.3$ seq 1 3"), column = 17) }) { true }
        val after = buffer(listOf("older", "bash-5.3$ seq 1 3", "1", "2", "3", "bash-5.3$ "), column = 10)
        assertEquals("bash-5.3$ seq 1 3", history.lastCommand(after))
        assertEquals("1\n2\n3\nbash-5.3$", history.lastOutput(after))
    }

    @Test
    fun `the row Enter was sent on wins over a nearer row that also ends with the command`() {
        val history = NativeTerminalHistory()
        history.send("l", { buffer(listOf("older", "bash-5.3$ "), column = 10) }) { true }
        history.send("\r", { buffer(listOf("older", "bash-5.3$ ls"), column = 12) }) { true }
        val after = buffer(listOf("older", "bash-5.3$ ls", "x ls", "bash-5.3$ "), column = 10)
        assertEquals("bash-5.3$ ls", history.lastCommand(after))
        assertEquals("x ls\nbash-5.3$", history.lastOutput(after))
    }

    @Test
    fun `Enter sent before the echo has come back keeps its row, which then holds the command`() {
        val history = NativeTerminalHistory()
        val atPrompt = buffer(listOf("older", "bash-5.3$ "), column = 10)
        history.send("s", { atPrompt }) { true }
        // `eq 1 3` and Enter were typed faster than the shell's echo returned, so the buffer still shows the bare prompt.
        history.send("\r", { atPrompt }) { true }
        val after = buffer(listOf("older", "bash-5.3$ seq 1 3", "1", "2", "3", "bash-5.3$ "), column = 10)
        assertEquals("bash-5.3$ seq 1 3", history.lastCommand(after))
        assertEquals("1\n2\n3\nbash-5.3$", history.lastOutput(after))
    }

    @Test
    fun `a suggestion shown after the cursor is gone once Enter is pressed, and the row is still the command's`() {
        val history = NativeTerminalHistory()
        history.send("l", { buffer(listOf("older", "➜ "), column = 2) }) { true }
        // `ls` is typed and the history's `ls -la` is shown after the cursor.
        history.send("\r", { buffer(listOf("older", "➜ ls -la"), column = 4) }) { true }
        // The output has a row that ends with `ls` too, which a search by the text would take for the command.
        val after = buffer(listOf("older", "➜ ls", "x ls", "➜ "), column = 2)
        assertEquals("➜ ls", history.lastCommand(after))
        assertEquals("x ls\n➜", history.lastOutput(after))
    }

    @Test
    fun `the command text stops at the cursor, so a suggestion or a right prompt after it is not part of it`() {
        val above = listOf("older", "", BANNER, "╭─ ♥ 21:40 | tmp → ▶ → demo")
        val collapsed = buffer(listOf("older", "❯ ls", "a b") + block("21:41"), column = 3)
        val output = "a b\n\n$BANNER\n╭─ ♥ 21:41 | tmp → ▶ → demo\n╰─"

        val suggested = NativeTerminalHistory()
        suggested.send("l", { buffer(above + "╰─ ", column = 3) }) { true }
        suggested.send("\r", { buffer(above + "╰─ ls -la", column = 5) }) { true }
        assertEquals("❯ ls", suggested.lastCommand(collapsed))
        assertEquals(output, suggested.lastOutput(collapsed))

        val rightPrompt = NativeTerminalHistory()
        rightPrompt.send("l", { buffer(above + ("╰─ ".padEnd(33) + "[21:40]"), column = 3) }) { true }
        rightPrompt.send("\r", { buffer(above + ("╰─ ls".padEnd(33) + "[21:40]"), column = 5) }) { true }
        assertEquals("❯ ls", rightPrompt.lastCommand(collapsed))
        assertEquals(output, rightPrompt.lastOutput(collapsed))
    }

    @Test
    fun `an Enter with nothing typed is no command to look for, so the screen is read`() {
        val history = NativeTerminalHistory()
        // The first key is the Enter itself, and the shell collapses the block to a bare `❯`.
        history.send("\r", { buffer(listOf("older", "❯ true") + block("21:36"), column = 3) }) { true }
        val after = buffer(listOf("older", "❯ true", "❯") + block("21:37"), column = 3)
        assertEquals("", history.lastCommand(after))
        assertEquals("older\n❯ true\n❯\n\n$BANNER\n╭─ ♥ 21:37 | tmp → ▶ → demo\n╰─", history.lastOutput(after))
    }

    @Test
    fun `Up as the first key still starts at the prompt, the command being what it recalls`() {
        val history = NativeTerminalHistory()
        var current = waiting("21:36")
        history.send("\u001b[A", { current }) {
            current = typed("21:36", "seq 1 3")
            true
        }
        history.send("\r", { current }) { true }
        assertEquals("❯ seq 1 3", history.lastCommand(afterSeq()))
        assertEquals(seqOutput, history.lastOutput(afterSeq()))
    }

    @Test
    fun `Ctrl-C or Ctrl-Z starts nothing, the first key at the prompt that follows does`() {
        for (stop in listOf("\u0003", "\u001a")) {
            val history = NativeTerminalHistory()
            history.send("\r", { typed("21:35", "ping x") }) { true }
            // The key is pressed with the cursor at the end of a row of ping's output, and has no prompt to look for.
            history.send(stop, { error("A key that stops the command must not copy the buffer") }) { true }
            val fresh = listOf("older", "❯ ping x", "64 bytes from x", "^C")
            history.send("l", { waiting("21:36", fresh) }) { true }
            history.send("s", { error("Typing after the first key must not copy the buffer") }) { true }
            history.send("\r", { typed("21:36", "ls", fresh) }) { true }
            val after = buffer(fresh + listOf("❯ ls", "a b") + block("21:37"), column = 3)
            assertEquals(stop, "❯ ls", history.lastCommand(after))
            assertEquals(stop, "a b\n\n$BANNER\n╭─ ♥ 21:37 | tmp → ▶ → demo\n╰─", history.lastOutput(after))
        }
    }

    @Test
    fun `keys typed in the alternate screen start nothing, the first one at the prompt after it does`() {
        val history = NativeTerminalHistory()
        history.send("\r", { typed("21:35", "vim a") }) { true }
        var reads = 0
        val editor = { reads++; buffer(listOf("one", "two"), column = 3, alternate = true) }
        for (key in listOf("i", "x", "ZZ")) history.send(key, editor) { true }
        assertEquals("the editor's keys each look, as the first key does until a prompt is found", 3, reads)
        val fresh = listOf("older", "❯ vim a")
        history.send("l", { reads++; waiting("21:36", fresh) }) { true }
        assertEquals(4, reads)
        history.send("s", { error("Typing after the first key must not copy the buffer") }) { true }
        history.send("\r", { typed("21:36", "ls", fresh) }) { true }
        val after = buffer(fresh + listOf("❯ ls", "a b") + block("21:37"), column = 3)
        assertEquals("❯ ls", history.lastCommand(after))
        assertEquals("a b\n\n$BANNER\n╭─ ♥ 21:37 | tmp → ▶ → demo\n╰─", history.lastOutput(after))
    }

    @Test
    fun `a word that merely ends in the command is not the command row, nor is a row of nothing but the command`() {
        val history = NativeTerminalHistory()
        history.send("l", { waiting("21:36") }) { true }
        history.send("\r", { typed("21:36", "ls") }) { true }
        val after = buffer(listOf("older", "❯ ls", "bin", "tools", "ls") + block("21:37"), column = 3)
        assertEquals("❯ ls", history.lastCommand(after))
        assertEquals("bin\ntools\nls\n\n$BANNER\n╭─ ♥ 21:37 | tmp → ▶ → demo\n╰─", history.lastOutput(after))
    }

    @Test
    fun `the prompt being typed at is not the command, though it holds the same text`() {
        val history = enteredSeq()
        // Up has recalled it at the next prompt and Enter has not been pressed.
        val recalled = afterSeq(typedAtPrompt = "seq 1 3")
        assertEquals("❯ seq 1 3", history.lastCommand(recalled))
        assertEquals("1\n2\n3\n\n$BANNER\n╭─ ♥ 21:37 | tmp → ▶ → demo\n╰─ seq 1 3", history.lastOutput(recalled))
    }

    @Test
    fun `a reconnect after the prompt collapsed carries the collapsed row, which the replay has again`() {
        val history = enteredSeq()
        history.replaced(afterSeq())
        // The replay draws the session under a banner and a message of the day the old buffer did not have.
        val replay = buffer(listOf("Welcome", "motd", "older", "❯ seq 1 3", "1", "2", "3") + block("21:37"), column = 3)
        assertEquals("❯ seq 1 3", history.lastCommand(replay))
        assertEquals(seqOutput, history.lastOutput(replay))
    }

    @Test
    fun `a reconnect that wraps the command at another width finds it by its text`() {
        val command = ": " + "0123456789".repeat(11) + "; echo ok"
        val head = listOf("older", "", BANNER, "╭─ ♥ 21:43 | tmp → ▶ → demo")
        val history = NativeTerminalHistory()
        history.send(":", { buffer(head + "╰─ ", column = 3, width = 105) }) { true }
        val entered = "╰─ $command"
        history.send("\r", { buffer(head + entered.take(105) + entered.drop(105), column = entered.length - 105, wrapped = setOf(4), width = 105) }) { true }
        val collapsed = "❯ $command"
        history.replaced(buffer(listOf("older") + collapsed.take(105) + collapsed.drop(105) + "ok" + block("21:44"), column = 3, wrapped = setOf(1), width = 105))
        // Replayed into 80 columns the second row is not the row it was, so the row cannot be told by its own text.
        val replay = buffer(listOf("older") + collapsed.take(80) + collapsed.drop(80) + "ok" + block("21:44"), column = 3, wrapped = setOf(1), width = 80)
        assertEquals(collapsed.drop(80), history.lastCommand(replay))
        assertEquals("ok\n\n$BANNER\n╭─ ♥ 21:44 | tmp → ▶ → demo\n╰─", history.lastOutput(replay))
    }

    @Test
    fun `each command is looked for after its own prompt, which changes with the directory`() {
        val history = NativeTerminalHistory()
        // A prompt of one row that the shell redraws as `❯ cmd` after Enter, and that names the directory.
        history.send("c", { buffer(listOf("older", "~ ❯ "), column = 4) }) { true }
        history.send("\r", { buffer(listOf("older", "~ ❯ cd /tmp"), column = 11) }) { true }
        history.send("l", { buffer(listOf("older", "❯ cd /tmp", "/tmp ❯ "), column = 7) }) { true }
        history.send("\r", { buffer(listOf("older", "❯ cd /tmp", "/tmp ❯ ls"), column = 9) }) { true }
        val after = buffer(listOf("older", "❯ cd /tmp", "❯ ls", "a b", "/tmp ❯ "), column = 7)
        assertEquals("❯ ls", history.lastCommand(after))
        assertEquals("a b\n/tmp ❯", history.lastOutput(after))
    }

    @Test
    fun `a key the terminal refused starts nothing, so the next one is still the first`() {
        val history = NativeTerminalHistory()
        var reads = 0
        assertFalse(history.send("l", { reads++; waiting("21:36") }) { false })
        assertTrue(history.send("l", { reads++; waiting("21:36") }) { true })
        assertEquals(2, reads)
        history.send("s", { error("Typing after the first key must not copy the buffer") }) { true }
    }

    private companion object {
        const val WIDTH = 40
        const val BANNER = "zsh MEM 41% 52ms"
    }
}
