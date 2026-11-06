package org.connectbot.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.spy
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The scroll region of the screen that is shown. libvterm keeps one region for the whole terminal and shows either screen through it;
 * xterm.js keeps one for each screen, starts the alternate one whole every time it is entered, gives the shell's own back when it is left,
 * and resets both whenever the size really changes. Vim sets `ESC [ 1 ; 18 r` on the alternate screen and leaves it with `?1049l`, never
 * `ESC [ r`, so after it the shell went on with vim's region: a line feed on the bottom row did not scroll and everything printed after that was
 * drawn over the last rows. `ESC [ 1 ; 18 r` is also not "the whole screen" to libvterm when the screen is not 18 rows tall but an explicit bottom
 * of 18 (or of the height at that moment), which only a shorter screen ever clamps: a replay written at one height and shown at another kept a
 * region the screen had outgrown. A fresh terminal, a terminal that ran only `less`, and a reconnect with no restart were fine, which is why it
 * only showed after a force-stop.
 *
 * The real emulator runs against [FakeVterm], whose DECSTBM, modes, origin mode and cursor reports are `state.c`'s, and
 * `EngineInstrumentation.verifyScrollRegion` runs the same flows through the real library on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScrollRegionTest {
    private val esc = "\u001b"
    private val enter = "$esc[?1049h"
    private val leave = "$esc[?1049l"

    private class Rig(rows: Int, cols: Int) : AutoCloseable {
        @Volatile var flushing = false
        lateinit var vterm: FakeVterm
        val emulator: TerminalEmulatorImpl = spy(
            TerminalEmulatorImpl(initialRows = rows, initialCols = cols, nativeFactory = { callbacks -> FakeVterm(rows, cols).also { vterm = it }.attach(callbacks) }),
        ).also {
            // The snapshot worker must not catch up between writes on its own: when the emulator has caught up is the test's to say.
            doAnswer { call -> if (flushing) call.callRealMethod() else null }.`when`(it).processPendingUpdates()
            // The model is made with the emulator's first use of its native.
            it.writeInput(ByteArray(0), 0, 0)
        }

        fun write(text: String) = emulator.writeInput(text.toByteArray(Charsets.ISO_8859_1), 0, text.length)

        fun replay(text: String) = emulator.writeReplay(text.toByteArray(Charsets.ISO_8859_1))

        fun resize(rows: Int, cols: Int) {
            flushing = true
            try { emulator.resize(rows, cols, cols * 10, rows * 20) } finally { flushing = false }
        }

        fun screen(): List<String> {
            flushing = true
            try { emulator.processPendingUpdates() } finally { flushing = false }
            return emulator.snapshot.value.lines.map { it.text.replace('\u0000', ' ').trimEnd() }
        }

        /** What the library was given, in the order, as the text of each write (the empty one that made the model is not one). */
        val writes: List<String> get() = vterm.writes.filter { it.isNotEmpty() }

        override fun close() = emulator.close()
    }

    /** What the library is given of [text] when nothing is added to it: the same bytes, in writes that each end before the h of an `enter`, where the shell's screen is kept. */
    private fun cutAtEntries(text: String): List<String> {
        val pieces = text.split(enter)
        return pieces.mapIndexed { i, piece -> (if (i > 0) "h" else "") + piece + (if (i < pieces.lastIndex) "$esc[?1049" else "") }
    }

    /** What vim writes: the alternate screen, a region of the first [height] rows that it leaves set, some drawing, and the way out. */
    private fun vim(height: Int) =
        "$enter$esc[22;0;0t$esc[>4;2m$esc[?1h$esc=$esc[1;" + height + "r$esc[H$esc[2J~\r\n~\r\n~$esc[?25l$esc[?25h" +
            "$esc[?1l$esc>$esc[>4;m$esc[23;0;0t$leave"

    /** "last", a line feed and "next", written from the first column of the last row: the screen scrolls, or "next" lands on "last". */
    private fun scrollsAtTheBottom(rig: Rig, rows: Int): Boolean {
        rig.write("$esc[" + rows + ";1H" + "last\r\nnext")
        val screen = rig.screen()
        return screen[rows - 2] == "last" && screen[rows - 1] == "next"
    }

    // ---------------------------------------------------------------- the three cases of the report

    @Test fun `a replay written at a smaller height than the screen ends up with leaves no region the screen grows out of`() {
        Rig(6, 20).use { rig ->
            rig.replay("\$ vim\r\n" + vim(12) + "\$ ")
            rig.resize(12, 20)
            assertTrue(scrollsAtTheBottom(rig, 12))
        }
    }

    @Test fun `a replay written at the height of the screen vim ran in, in a taller screen, leaves no region either`() {
        Rig(30, 20).use { rig ->
            rig.replay("\$ vim\r\n" + vim(18) + "\$ ")
            assertTrue(scrollsAtTheBottom(rig, 30))
        }
    }

    @Test fun `a region set on the shell's screen is gone when the size changes, in rows or in columns`() {
        Rig(10, 20).use { rig ->
            rig.write("$esc[3;8r")
            rig.resize(14, 20)
            assertTrue(scrollsAtTheBottom(rig, 14))
        }
        Rig(10, 20).use { rig ->
            rig.write("$esc[3;8r")
            rig.resize(10, 30)
            assertTrue(scrollsAtTheBottom(rig, 10))
        }
        Rig(10, 20).use { rig ->
            rig.write("$esc[3;8r")
            rig.resize(6, 20)
            assertTrue(scrollsAtTheBottom(rig, 6))
        }
    }

    @Test fun `the library is given the whole screen back as the size changes, not when the next write comes`() {
        Rig(10, 20).use { rig ->
            rig.write("$esc[3;8r")
            val before = rig.writes.size
            rig.resize(14, 20)
            // Nothing was written after the resize: a first byte of the next write, a line feed, would have scrolled the old region.
            assertEquals(before + 1, rig.writes.size)
            assertTrue(rig.writes.last(), rig.writes.last().startsWith("$esc[r"))
        }
    }

    @Test fun `a size that only differs in pixels is not a size change, and the region stays`() {
        Rig(10, 20).use { rig ->
            rig.write("$esc[3;8r")
            val before = rig.writes.size
            rig.emulator.resize(10, 20, 999, 999)
            assertEquals(before, rig.writes.size)
            // Still the region of rows 3 to 8: a line feed on row 8 scrolls those rows only.
            for (row in 0 until 10) rig.write("$esc[" + (row + 1) + ";1HR" + row)
            rig.write("$esc[8;1H\n")
            assertEquals(listOf("R0", "R1", "R3", "R4", "R5", "R6", "R7", "", "R8", "R9"), rig.screen())
        }
    }

    // ---------------------------------------------------------------- vim as a live program, and the screens apart

    @Test fun `vim at the height of the screen is a region of the whole screen and costs the library nothing`() {
        Rig(18, 20).use { rig ->
            val flow = "\$ vim\r\n" + vim(18) + "\$ "
            rig.write(flow)
            assertEquals(cutAtEntries(flow), rig.writes)
            assertTrue(scrollsAtTheBottom(rig, 18))
        }
    }

    @Test fun `the shell's region comes back when the alternate screen is left, and vim's does not stay`() {
        Rig(10, 20).use { rig ->
            for (row in 0 until 10) rig.write("$esc[" + (row + 1) + ";1HR" + row)
            rig.write("$esc[3;8r")
            rig.write(enter + "$esc[1;5r$esc[2J$esc[H" + "vim" + leave)
            // The shell's screen is as it was, and rows 3 to 8 are still the region.
            assertEquals((0 until 10).map { "R$it" }, rig.screen())
            rig.write("$esc[8;1H\n")
            assertEquals(listOf("R0", "R1", "R3", "R4", "R5", "R6", "R7", "", "R8", "R9"), rig.screen())
        }
    }

    @Test fun `the alternate screen starts with the whole screen as its region whatever the shell had`() {
        Rig(10, 20).use { rig ->
            for (row in 0 until 10) rig.write("$esc[" + (row + 1) + ";1HR" + row)
            rig.write("$esc[3;8r$enter")
            rig.write("$esc[10;1H" + "A\r\nB")
            assertEquals(listOf("A", "B"), rig.screen().takeLast(2))
            // And leaving it puts the shell's back.
            rig.write(leave)
            rig.write("$esc[8;1H\n")
            assertEquals(listOf("R0", "R1", "R3", "R4", "R5", "R6", "R7", "", "R8", "R9"), rig.screen())
        }
    }

    @Test fun `a region set on the alternate screen is not the shell's when it is entered again`() {
        Rig(10, 20).use { rig ->
            rig.write(enter + "$esc[2;5r" + leave + enter)
            rig.write("$esc[10;1H" + "A\r\nB")
            assertEquals(listOf("A", "B"), rig.screen().takeLast(2))
        }
    }

    // ---------------------------------------------------------------- the cursor

    @Test fun `the cursor is where it was when the alternate screen is left, and when it is entered`() {
        Rig(10, 20).use { rig ->
            rig.write("$esc[3;8r$esc[6;4H")
            assertEquals(5 to 3, rig.vterm.cursor)
            rig.write(enter)
            assertEquals(5 to 3, rig.vterm.cursor)
            rig.write("$esc[1;5r$esc[8;9H")
            rig.write(leave)
            assertEquals(5 to 3, rig.vterm.cursor)
            rig.write("X")
            assertEquals("X", rig.screen()[5].trim().take(1))
            assertEquals('X', rig.screen()[5][3])
        }
    }

    @Test fun `the cursor of a resize is where the library put it`() {
        Rig(10, 20).use { rig ->
            rig.write("$esc[3;8r$esc[8;4H")
            rig.resize(14, 20)
            val after = rig.vterm.cursor
            // The same resize with no region held is the control.
            Rig(10, 20).use { control ->
                control.write("$esc[8;4H")
                control.resize(14, 20)
                assertEquals(control.vterm.cursor, after)
            }
        }
    }

    @Test fun `with the origin mode on the rows are counted from the top of the region, and the cursor goes back to the same place`() {
        Rig(10, 20).use { rig ->
            rig.write("$esc[3;8r$esc[?6h$esc[2;5H")
            val at = rig.vterm.cursor
            assertEquals(3 to 4, at)
            rig.write(enter)
            assertEquals(at, rig.vterm.cursor)
            rig.write("$esc[1;5r")
            rig.write(leave)
            assertEquals(at, rig.vterm.cursor)
            // Still the origin mode and still rows 3 to 8: the first row is the third.
            rig.write("$esc[1;1H")
            assertEquals(2 to 0, rig.vterm.cursor)
        }
    }

    @Test fun `a cursor above the region in the origin mode cannot be put back, and stays where the region puts it`() {
        Rig(10, 20).use { rig ->
            // The shell has rows 3 to 8 and its cursor on the first row, outside them; the program turns the origin mode on, where a cursor
            // can only be put inside the region, and leaves.
            rig.write("$esc[3;8r$esc[1;1H" + enter + "$esc[?6h$esc[1;5r")
            rig.write(leave)
            assertEquals(2 to 0, rig.vterm.cursor)
        }
    }

    // ---------------------------------------------------------------- what is not touched

    @Test fun `a plain shell is never written to by the emulator, and a program that ends as it began is not either`() {
        Rig(10, 20).use { rig ->
            val session = "ls\r\n$esc[31mred$esc[m\r\n$esc[2J$esc[H" + enter + "less\r\n$esc[2J" + leave + "$esc]0;title\u0007\$ " + enter + leave
            rig.write(session)
            assertEquals(cutAtEntries(session), rig.writes)
            rig.resize(14, 20)
            rig.resize(14, 24)
            rig.resize(10, 20)
            assertEquals(cutAtEntries(session), rig.writes)
        }
    }

    @Test fun `a pending wrap survives a program that only enters and leaves the alternate screen`() {
        Rig(5, 10).use { rig ->
            rig.write("AAAAAAAAAA" + enter + leave + "B")
            assertEquals(listOf("AAAAAAAAAA", "B"), rig.screen().take(2))
        }
    }

    @Test fun `RIS and the soft reset leave nothing for the library to be told`() {
        Rig(10, 20).use { rig ->
            rig.write("$esc[3;8r${esc}c")
            val before = rig.writes.size
            rig.resize(14, 20)
            rig.write(enter + leave)
            // The write is cut before the h of the enter, and nothing is added to either piece.
            assertEquals(before + 2, rig.writes.size)
            assertEquals(cutAtEntries(enter + leave), rig.writes.takeLast(2))
            assertTrue(scrollsAtTheBottom(rig, 14))
        }
        Rig(10, 20).use { rig ->
            rig.write("$esc[3;8r$esc[!p")
            rig.resize(14, 20)
            rig.write(enter + leave)
            assertTrue(rig.writes.none { it.startsWith("$esc[r") })
            assertTrue(scrollsAtTheBottom(rig, 14))
        }
    }

    // ---------------------------------------------------------------- the writes the fix makes

    @Test fun `a size change in the middle of a sequence waits for the end of the sequence, and the sequence is not cut short`() {
        Rig(30, 20).use { rig ->
            rig.write("$esc[1;18r$esc[2")
            rig.resize(40, 20)
            // Nothing was added to the open sequence.
            assertEquals(listOf("$esc[1;18r$esc[2"), rig.writes.take(1))
            assertEquals(1, rig.writes.size)
            rig.write(";3Hmore")
            // The sequence was taken whole: the cursor is on row 2, column 3, and "more" is written after it.
            assertEquals("  more", rig.screen()[1])
            assertTrue(scrollsAtTheBottom(rig, 40))
        }
    }

    @Test fun `the bytes added are a region and a cursor position, one write and nothing else`() {
        Rig(30, 20).use { rig ->
            val flow = vim(18)
            rig.write(flow)
            // The flow up to the h of the enter, the rest of it, and what is added at the end of the leave.
            assertEquals(cutAtEntries(flow), rig.writes.take(2))
            val added = rig.writes.drop(2)
            assertEquals(1, added.size)
            assertTrue(added.joinToString(), Regex("\u001b\\[r\u001b\\[\\d+;\\d+H").matches(added.single()))
            assertFalse(added.single().contains("n") || added.single().contains("c"))
        }
    }

    @Test fun `a write that is cut where the library's region stops being the shell's is cut once, at the end of the sequence`() {
        Rig(30, 20).use { rig ->
            rig.write("a" + vim(18) + "b")
            // Before the h of the enter, then the rest of vim, then the fix at the end of the leave, then what follows it.
            assertEquals(4, rig.writes.size)
            assertEquals(cutAtEntries("a" + vim(18)), rig.writes.take(2))
            assertEquals("b", rig.writes[3])
        }
    }

    @Test fun `a replay is written whole to the library, and every write the emulator makes in it counts as replaying`() {
        // Whatever the library says in answer to a write that is part of a replay is dropped, the added bytes included.
        val field = TerminalEmulatorImpl::class.java.getDeclaredField("replaying").apply { isAccessible = true }
        Rig(30, 20).use { rig ->
            val replayingAt = mutableListOf<Boolean>()
            val texts = mutableListOf<String>()
            val emulator = rig.emulator
            val native = rig.vterm
            val seen = object : ScreenNative by native {
                override fun writeInput(data: ByteArray, offset: Int, length: Int): Int {
                    replayingAt += field.getBoolean(emulator)
                    texts += String(data, offset, length, Charsets.ISO_8859_1)
                    return native.writeInput(data, offset, length)
                }
            }
            val nativeField = TerminalEmulatorImpl::class.java.getDeclaredField("nativeInstance").apply { isAccessible = true }
            nativeField.set(emulator, seen)
            rig.replay("\$ vim\r\n$esc[6n$esc[c" + vim(18) + "$esc[>c$esc]10;?\u0007\$ ")
            assertTrue("the fix was written: " + texts, texts.any { it.startsWith("$esc[r") })
            assertTrue(replayingAt.toString(), replayingAt.all { it })
            // And the same bytes written live are not a replay.
            replayingAt.clear()
            texts.clear()
            rig.write(vim(18))
            assertTrue("the fix was written: " + texts, texts.any { it.startsWith("$esc[r") })
            assertTrue(replayingAt.toString(), replayingAt.none { it })
        }
    }

    // ---------------------------------------------------------------- the emulator is reset

    @Test fun `a reset forgets the region, and the library the emulator makes next starts with none`() {
        Rig(10, 20).use { rig ->
            rig.write("$esc[3;8r")
            rig.emulator.reset()
            rig.write("x")
            rig.write(enter + leave)
            // The new library holds no region, so entering and leaving the alternate screen writes nothing to it.
            assertEquals(listOf("x") + cutAtEntries(enter + leave), rig.writes)
        }
    }
}
