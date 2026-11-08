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
    private class Rig(
        rows: Int,
        cols: Int,
        cap: Int,
        byteCap: Long = Runtime.getRuntime().maxMemory() / 6,
    ) : AutoCloseable {
        val vterm = FakeVterm(rows, cols)
        val emulator = TerminalEmulatorImpl(
            initialRows = rows,
            initialCols = cols,
            maxScrollbackLines = cap,
            maxScrollbackBytes = byteCap,
            nativeFactory = { callbacks -> vterm.attach(callbacks) },
        )

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

    @Test fun `the byte budget evicts oldest rows when heavy rows blow past it before the 50,000-line cap`() {
        val budget = 100_000L
        Rig(24, 105, 50_000, byteCap = budget).use { rig ->
            val full = "0123456789".repeat(10)
            rig.flood(2_000) { full + it.toString().padStart(4, '0') }
            val history = rig.history()
            val bytes = history.bytes()
            assertTrue("kept ${history.size} rows (${bytes} B), well below the 50,000-line cap", history.size in 200..400)
            assertTrue("never exceeds the byte budget ($bytes <= $budget)", bytes <= budget)
            // One more row would push it over the budget.
            val oneRowBytes = history.first().cells.shapingRetentionBytes
            assertTrue("packed right up to the budget", bytes + oneRowBytes > budget)
            val newest = 2_000 - 23
            assertTrue(history.last().text.trimEnd().endsWith(newest.toString().padStart(4, '0')))
            assertTrue(history.first().text.trimEnd().endsWith((newest - history.size + 1).toString().padStart(4, '0')))

            // Growing the screen pops rows back from scrollback; a subsequent flood still fills right up to the byte budget.
            rig.emulator.resize(40, 105)
            rig.flood(500) { full + (2_000 + it).toString().padStart(4, '0') }
            val afterResize = rig.history()
            assertTrue(afterResize.bytes() in (budget - oneRowBytes + 1)..budget)
        }
    }

    @Test fun `replaying the newest 1 MB ring into a fresh emulator yields the same screen and tail rows as the live emulator`() {
        val stream = (1..200_000).joinToString("") { "$it\r\n" }.toByteArray(Charsets.ISO_8859_1)
        val tail = stream.copyOfRange(stream.size - 1_048_576, stream.size)
        Rig(24, 105, 50_000).use { live ->
            Rig(24, 105, 50_000).use { replayed ->
                live.emulator.writeInput(stream, 0, stream.size)
                val (liveRow, liveCol) = live.vterm.cursor
                live.emulator.moveCursor(liveRow, liveCol, liveRow, liveCol, true)

                val startNanos = System.nanoTime()
                replayed.emulator.writeReplay(tail)
                val (repRow, repCol) = replayed.vterm.cursor
                replayed.emulator.moveCursor(repRow, repCol, repRow, repCol, true)
                val reshowMs = (System.nanoTime() - startNanos) / 1_000_000

                assertEquals(live.emulator.getScreenText(), replayed.emulator.getScreenText())
                assertEquals(live.history().takeLast(500).map { it.text }, replayed.history().takeLast(500).map { it.text })
                assertTrue("1 MB re-show replay took ${reshowMs} ms", reshowMs < 15_000)
            }
        }
    }

    @Test fun `emulator exposes isAlternateScreen and isMouseTracking so parking stands down when either is active`() {
        Rig(24, 105, 50_000).use { rig ->
            val emulator: TerminalEmulator = rig.emulator
            assertEquals(false, emulator.isAlternateScreen)
            assertEquals(false, emulator.isMouseTracking)

            rig.write("\u001b[?1049h")
            assertEquals(true, emulator.isAlternateScreen)
            rig.write("\u001b[?1049l")
            assertEquals(false, emulator.isAlternateScreen)

            rig.write("\u001b[?1000h")
            assertEquals(true, emulator.isMouseTracking)
            rig.write("\u001b[?1000l")
            assertEquals(false, emulator.isMouseTracking)
        }
    }
}
