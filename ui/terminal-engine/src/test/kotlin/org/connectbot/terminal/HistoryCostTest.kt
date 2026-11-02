package org.connectbot.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a flood leaves in the history, on the real emulator over the model of libvterm: `seq 1 300000` is six times the 50,000 rows the app keeps,
 * and what stays has to be exactly the cap, the newest rows, each costing what it holds. Upstream's rows cost 2,198 B whatever they held (104 MB for the
 * cap), the heap an app gets is 192 MB, and one terminal's flood plus a reconnect's replay was an `OutOfMemoryError`. The bytes are those
 * the rows keep in arrays, counted as the shaping cache counts a row it retains (`shapingRetentionBytes`); `-e only memory` on `EngineInstrumentation`
 * measures the heap itself on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryCostTest {
    private class Rig(rows: Int, cols: Int, cap: Int) : AutoCloseable {
        val vterm = FakeVterm(rows, cols)
        val emulator = TerminalEmulatorImpl(initialRows = rows, initialCols = cols, maxScrollbackLines = cap, nativeFactory = { callbacks -> vterm.attach(callbacks) })

        /** What libvterm's parse does at its end, which the model leaves to the caller: it reports where the cursor is. */
        fun write(text: String) {
            emulator.writeInput(text.toByteArray(Charsets.ISO_8859_1), 0, text.length)
            val (row, col) = vterm.cursor
            emulator.moveCursor(row, col, row, col, true)
        }

        fun flood(lines: Int, line: (Int) -> String) {
            for (from in 1..lines step 1000) write((from until minOf(from + 1000, lines + 1)).joinToString("") { line(it) + "\r\n" })
        }

        /** The rows the history holds, oldest first: the emulator's own, not a copy that has had its text made. */
        @Suppress("UNCHECKED_CAST")
        fun history(): List<TerminalLine> {
            fun read(name: String) = emulator.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(emulator)
            return synchronized(read("damageLock")!!) { (read("scrollback") as Collection<TerminalLine>).toList() }
        }

        override fun close() = emulator.close()
    }

    /** What the rows keep in arrays. */
    private fun List<TerminalLine>.bytes() = sumOf { it.cells.shapingRetentionBytes.toLong() }

    @Test fun `a flood of 300,000 lines leaves the cap's newest rows, each costing what it holds`() {
        Rig(24, 105, 50_000).use { rig ->
            rig.flood(300_000) { it.toString() }
            val history = rig.history()
            assertEquals("the cap, and no more", 50_000, history.size)
            // The screen keeps the last 23 lines and the row the cursor is on, so the history ends 23 lines before the flood does.
            val newest = 300_000 - 23
            assertEquals("the newest of the flood", newest.toString(), history.last().text.trimEnd())
            assertEquals("and the oldest of those the cap keeps", (newest - 50_000 + 1).toString(), history.first().text.trimEnd())
            val bytes = history.bytes()
            assertTrue("50,000 rows of seq output hold ${bytes / 1_048_576} MB in arrays, ${bytes / history.size} B each", bytes <= 50_000L * 160)
        }
    }

    @Test fun `a flood of full rows costs a few hundred bytes a row, not two thousand`() {
        Rig(24, 105, 50_000).use { rig ->
            val full = "0123456789".repeat(11).take(104)
            rig.flood(60_000) { full }
            val history = rig.history()
            assertEquals(50_000, history.size)
            assertTrue("${history.bytes() / history.size} B a row", history.bytes() <= 50_000L * 360)
        }
    }

    @Test fun `several terminals flooded at once hold what each of them holds`() {
        val rigs = List(4) { Rig(24, 105, 50_000) }
        try {
            rigs.forEach { it.flood(100_000) { n -> n.toString() } }
            val histories = rigs.map { it.history() }
            histories.forEach { assertEquals(50_000, it.size) }
            val total = histories.sumOf { it.bytes() }
            assertTrue("four terminals of 50,000 rows: ${total / 1_048_576} MB in arrays", total <= 4L * 50_000 * 160)
        } finally {
            rigs.forEach { it.close() }
        }
    }
}
