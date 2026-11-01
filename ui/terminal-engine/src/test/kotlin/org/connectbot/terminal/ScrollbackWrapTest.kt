package org.connectbot.terminal

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.spy
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the history says about its own soft wraps. libvterm's JNI sends the flag of the row *after* the one it pushes
 * (see [ScrollbackWrap]), and the lines of the fixture below, a command's output scrolled off a 26-row screen, show
 * what that did: `local:` was glued to `long :`, the long URL was cut after its third row and glued to `oauth:`, and
 * the OAuth URL lost its `redirect_uri`.
 *
 * The flags fed in are the ones libvterm 0.3.13 sent for exactly this output, recorded from a native build of its
 * sources (`state.c`, `screen.c` and `Terminal.cpp`'s `termSbPushline`) as it scrolled `clear; sed -n 4,6p term-links.txt;
 * seq 1 30` off a 26 x 80 screen; its own `lineinfo` after the shift gave [truth]. The emulator is the real one and
 * the callbacks are called as the library calls them; only the JNI is missing, which cannot load here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScrollbackWrapTest {
    private val columns = 80

    private val logicalLines = listOf(
        "$ clear; sed -n 4,6p term/term-links.txt; seq 1 30",
        "local: http://10.0.2.2:8937/?link-test=1",
        "long : https://example.com/very/long/path/" + "segment-".repeat(20) + "end?query=" + "abcdef".repeat(12) + "&x=1",
        "oauth: https://auth.example.com/authorize?client_id=abc&redirect_uri=http%3A%2F%2Flocalhost%3A54321%2Fcallback&state=xyz",
        "1", "2", "3", "4", "5",
    )

    /** The rows the screen held, in the order they scrolled off. */
    private val rows = logicalLines.flatMap { it.chunked(columns) }

    /** Whether each row continued onto the next, as libvterm's own line info said after its shift. */
    private val truth = logicalLines.flatMap { line -> line.chunked(columns).mapIndexed { i, _ -> (i + 1) * columns < line.length } }

    /** What libvterm's `termSbPushline` sent with each of those rows: the next row's flag. */
    private val sent = listOf(false, true, true, true, false, true, false, false, false, false, false, false, false)

    private val emulator: TerminalEmulatorImpl = spy(TerminalEmulatorFactory.create(initialRows = 26, initialCols = columns) as TerminalEmulatorImpl).also {
        // The snapshot worker would reach for libvterm's JNI. What these tests read is the history itself.
        doAnswer { null }.`when`(it).processPendingUpdates()
    }

    private fun cells(text: String): ByteBuffer {
        val buffer = CellData.buffer()
        for (col in 0 until columns) {
            val base = col * CellData.BYTES
            for (slot in 0 until CellData.STRIDE) buffer.putInt(base + slot * 4, 0)
            buffer.putInt(base, text.getOrElse(col) { ' ' }.code)
            buffer.putInt(base + CellData.WIDTH, 1)
        }
        return buffer
    }

    private fun push(text: String, flag: Boolean) {
        emulator.pushScrollbackLine(columns, 0, columns, cells(text), flag)
    }

    private fun pop() = emulator.popScrollbackLine(columns, 0, columns, CellData.buffer())

    @Suppress("UNCHECKED_CAST")
    private fun history(): List<TerminalLine> {
        val field = TerminalEmulatorImpl::class.java.getDeclaredField("scrollback").apply { isAccessible = true }
        return (field.get(emulator) as Collection<TerminalLine>).toList()
    }

    private fun flags() = history().map { it.softWrapped }

    @Test fun `the fixture is what the recorded flags were taken from`() {
        assertEquals(13, rows.size)
        assertEquals(listOf(false, false, true, true, true, false, true, false, false, false, false, false, false), truth)
        // The shift: what arrived with a row is the truth of the one after it.
        assertEquals(truth.drop(1) + false, sent)
    }

    @Test fun `every row keeps the flag it had on the screen, though libvterm sent the next row's`() {
        rows.indices.forEach { push(rows[it], sent[it]) }
        assertEquals(truth, flags())
    }

    @Test fun `a wrapped line in the history reads as one line, and the lines either side of it stay apart`() {
        rows.indices.forEach { push(rows[it], sent[it]) }
        val snapshot = TerminalBufferSnapshot(
            history().map { TerminalBufferLine(it.text, it.softWrapped) }, 0, 0, 0, false,
        )
        assertEquals(logicalLines.joinToString("\n"), snapshot.getTextBetweenRows(0, rows.size))
    }

    @Test fun `a row that comes back from the history is pushed again with the flag it had`() {
        push("one", true)
        push("two", false)
        push("three", true)
        // "three" is pulled back onto the screen and scrolls off again; libvterm now sends the flag of the row below it.
        pop()
        push("three", false)
        push("four", true)
        assertEquals(listOf("one", "two", "three", "four"), history().map { it.text.trim() })
        assertEquals(listOf(false, true, false, false), flags())
    }

    @Test fun `pulling back from an empty history leaves the waiting flag alone`() {
        push("one", true)
        pop()
        pop()
        push("again", true)
        push("next", false)
        assertEquals(listOf(false, true), flags())
    }

    @Test fun `clearing the history forgets the flag that was waiting`() {
        push("one", true)
        emulator.clearScrollback()
        push("two", false)
        assertEquals(listOf(false), flags())
    }

    @Test fun `a reset forgets it too`() {
        push("one", true)
        emulator.reset()
        push("two", false)
        assertEquals(listOf(false), flags())
    }

    @Test fun `a row split over several callbacks is pushed once and takes one flag`() {
        // libvterm hands a row over in pieces when it is wider than the transfer buffer.
        val half = columns / 2
        emulator.pushScrollbackLine(columns, 0, half, cells("first half"), true)
        emulator.pushScrollbackLine(columns, half, columns - half, cells("second half"), true)
        push("next", false)
        assertEquals(listOf(false, true), flags())
    }
}
