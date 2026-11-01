package org.connectbot.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The read made on every key typed. [TerminalEmulator.getBufferSnapshot] copies every row of the history (50,000 of them, with
 * their text rebuilt, while the parser's lock is held), and a first key after Enter echoed late on a tablet whose history was full.
 * What the key needs is the cursor's line, so [TerminalEmulator.getScreenSnapshot] leaves the history out except for the rows the
 * screen's first line wraps on from. The full read is the oracle: the window must be the bottom of it, and say where it starts.
 *
 * The real emulator runs against [FakeVterm], a model of libvterm's parse and screen (the JNI cannot load on the JVM).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenSnapshotTest {
    private val escape = "\u001b"

    private class Rig(rows: Int, cols: Int) : AutoCloseable {
        val vterm = FakeVterm(rows, cols)
        val emulator = TerminalEmulatorImpl(initialRows = rows, initialCols = cols, nativeFactory = { callbacks -> vterm.attach(callbacks) })

        /** What libvterm's parse does at its end, which the model leaves to the caller: it reports where the cursor is. */
        fun write(text: String) {
            emulator.writeInput(text.toByteArray(Charsets.ISO_8859_1), 0, text.length)
            val (row, col) = vterm.cursor
            emulator.moveCursor(row, col, row, col, true)
        }

        override fun close() = emulator.close()
    }

    private fun TerminalBufferSnapshot.texts() = lines.map { it.text.trimEnd() }

    /** The window is the bottom of the full read, and its numbers say where in the whole buffer that is. */
    private fun Rig.windowOfFull(): TerminalBufferSnapshot {
        val full = emulator.getBufferSnapshot()
        val screen = emulator.getScreenSnapshot()
        assertEquals("the rows are the bottom of the whole buffer", full.lines.takeLast(screen.lines.size), screen.lines)
        assertEquals("where they start", full.lines.size - screen.lines.size, screen.firstRow)
        assertEquals("the cursor, in the whole buffer", full.cursorRow, screen.firstRow + screen.cursorRow)
        assertEquals("the screen's first row, in the whole buffer", full.screenStartRow, screen.firstRow + screen.screenStartRow)
        assertEquals(full.cursorColumn, screen.cursorColumn)
        assertEquals(full.alternateScreen, screen.alternateScreen)
        return screen
    }

    @Test fun `a long history is left out, and the rows that are read are the bottom of the whole buffer`() {
        Rig(24, 80).use { rig ->
            rig.write((1..3000).joinToString("") { "line $it\r\n" } + "$ ")
            assertTrue("the history is there", rig.emulator.getBufferSnapshot().lines.size > 3000)
            val screen = rig.windowOfFull()
            assertEquals("the screen and no more", 24, screen.lines.size)
            assertEquals("the screen starts the window", 0, screen.screenStartRow)
            assertEquals(23, screen.cursorRow)
            assertEquals("$", screen.texts()[screen.cursorRow])
            assertEquals("line 3000", screen.texts()[screen.cursorRow - 1])
            assertEquals(2, screen.cursorColumn)
        }
    }

    @Test fun `the read catches up with what was written, as the full read does`() {
        Rig(24, 80).use { rig ->
            rig.write("one\r\ntwo\r\n$ ls")
            // Nothing else has read the emulator since the write.
            val screen = rig.emulator.getScreenSnapshot()
            assertEquals(listOf("one", "two", "$ ls"), screen.texts().take(3))
            assertEquals(2, screen.cursorRow)
            assertEquals(4, screen.cursorColumn)
        }
    }

    @Test fun `a screen that nothing has scrolled off is the full read`() {
        Rig(24, 80).use { rig ->
            rig.write("one\r\ntwo\r\n$ ls")
            val screen = rig.windowOfFull()
            assertEquals(rig.emulator.getBufferSnapshot(), screen)
            assertEquals(0, screen.firstRow)
            assertEquals(2, screen.cursorRow)
            assertEquals(4, screen.cursorColumn)
        }
    }

    @Test fun `a line that began in the history is whole in the window, with the rows it wrapped over`() {
        Rig(3, 10).use { rig ->
            // Five rows of a line that is typed at a prompt: two have scrolled into the history, three are on the screen.
            rig.write("older\r\n".repeat(5) + "0123456789".repeat(4) + "abcde")
            val full = rig.emulator.getBufferSnapshot()
            assertEquals("the line wraps over five rows, the last of them the cursor's", listOf(true, true, true, true, false), full.lines.takeLast(5).map { it.isWrapped })
            assertEquals("two of them are in the history", 2, full.lines.take(full.screenStartRow).takeLastWhile { it.isWrapped }.size)
            val screen = rig.windowOfFull()
            assertEquals(listOf("0123456789", "0123456789", "0123456789", "0123456789", "abcde"), screen.texts())
            assertEquals("two rows of history above the screen", 2, screen.screenStartRow)
            assertEquals(4, screen.cursorRow)
            assertEquals(5, screen.cursorColumn)
            assertEquals(listOf(true, true, true, true, false), screen.lines.map { it.isWrapped })
        }
    }

    @Test fun `a screen whose first line does not continue from the history takes none of it`() {
        Rig(3, 10).use { rig ->
            rig.write("older\r\n".repeat(5) + "x")
            val screen = rig.windowOfFull()
            assertEquals(3, screen.lines.size)
            assertEquals(0, screen.screenStartRow)
            assertEquals(2, screen.cursorRow)
        }
    }

    @Test fun `only the run of wrapped rows that touches the screen is taken, not a wrapped line further up`() {
        Rig(3, 10).use { rig ->
            // A line that wrapped over two rows long ago, lines that did not, then a screen.
            rig.write("first\r\n" + "0123456789abc\r\n" + "plain\r\n".repeat(6) + "x")
            val full = rig.emulator.getBufferSnapshot()
            assertTrue("a wrapped row is in the history", full.lines.take(full.screenStartRow).any { it.isWrapped })
            val screen = rig.windowOfFull()
            assertEquals(3, screen.lines.size)
        }
    }

    @Test fun `the alternate screen takes no history, even when the history ends on a wrapped row`() {
        Rig(3, 10).use { rig ->
            rig.write("older\r\n".repeat(5) + "0123456789".repeat(4) + "abcde")
            val before = rig.emulator.getBufferSnapshot()
            assertTrue("the history ends on a wrapped row", before.lines[before.screenStartRow - 1].isWrapped)
            rig.write("$escape[?1049hvim")
            val screen = rig.windowOfFull()
            assertTrue(screen.alternateScreen)
            assertEquals(0, screen.firstRow)
            assertEquals(3, screen.lines.size)
            assertEquals(rig.emulator.getBufferSnapshot(), screen)
        }
    }
}
