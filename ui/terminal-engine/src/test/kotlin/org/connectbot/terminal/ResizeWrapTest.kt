package org.connectbot.terminal

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.spy
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a resize leaves of the rows' soft wraps and links. libvterm's resize (reflow is off in the shipped JNI) keeps each row as one
 * row, from the bottom up: the rows that no longer fit at the top go into the history, a taller screen takes rows back from it, and
 * it clears the continuation flag of EVERY row on the way. So a wrapped line (a long URL, a command that ran over two rows) came back
 * from a keyboard that opened and closed as two lines; the rows pushed into the history all carried the flag of the row that was
 * first (the one `lineinfo(1)` the JNI reads is the old array's), and the links, which the engine keys by row, stayed on the row
 * numbers they had while the rows under them moved.
 *
 * The real emulator runs against [FakeVterm], a model of libvterm's parse, screen and resize that `EngineInstrumentation` holds to the
 * library's own answers (a fixed set of scenarios, traced the same way on both); `EngineInstrumentation.verifyResize` runs the
 * cases below through the real library on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResizeWrapTest {
    private val escape = "\u001b"
    private fun link(n: Int) = "$escape]8;;https://l$n.test/\u0007link$n$escape]8;;\u0007"

    /** Eight rows of ten columns: a short line, then a line that wraps over two rows and another over three, the shell's prompt last. */
    private val page = "xx\r\n" + "B".repeat(10) + "C".repeat(10) + "D".repeat(5) + "\r\n" + "EEEEE\r\n" + "$ "

    private val pageRows = listOf("xx" to false, "BBBBBBBBBB" to true, "CCCCCCCCCC" to true, "DDDDD" to false, "EEEEE" to false, "$" to false, "" to false, "" to false)

    private class Rig(rows: Int, cols: Int) : AutoCloseable {
        @Volatile var flushing = false
        val vterm = FakeVterm(rows, cols)
        val emulator: TerminalEmulatorImpl = spy(
            TerminalEmulatorImpl(initialRows = rows, initialCols = cols, nativeFactory = { callbacks -> vterm.attach(callbacks) }),
        ).also {
            // The snapshot worker must not catch up between writes on its own: when the emulator has caught up is the test's to say.
            doAnswer { call -> if (flushing) call.callRealMethod() else null }.`when`(it).processPendingUpdates()
        }

        fun write(text: String) = emulator.writeInput(text.toByteArray(Charsets.ISO_8859_1), 0, text.length)

        /** What the snapshot worker does once a write is over. */
        fun catchUp() {
            flushing = true
            try { emulator.processPendingUpdates() } finally { flushing = false }
        }

        /** The view is laid out at another size, as the Compose terminal reports it. The emulator is not told to catch up first: a resize has to. */
        fun resize(rows: Int, cols: Int) {
            flushing = true
            try { emulator.resize(rows, cols, cols * 10, rows * 20) } finally { flushing = false }
        }

        fun history(): List<TerminalLine> { catchUp(); return emulator.snapshot.value.scrollback }

        fun screen(): List<TerminalLine> { catchUp(); return emulator.snapshot.value.lines }

        override fun close() = emulator.close()
    }

    private fun List<TerminalLine>.wraps() = map { it.text.replace('\u0000', ' ').trimEnd() to it.softWrapped }

    private fun List<TerminalLine>.links() = map { line -> line.semanticSegments.filter { it.semanticType == SemanticType.HYPERLINK }.map { it.metadata } }

    private fun List<TerminalLine>.texts() = map { it.text.replace('\u0000', ' ').trimEnd() }

    /** How many links the emulator holds the text of, to tell it from the cells under it: one per link on the screen, none for a row that left it. */
    private fun linkTexts(rig: Rig): Int {
        val field = TerminalEmulatorImpl::class.java.getDeclaredField("semanticSegmentTexts").apply { isAccessible = true }
        return (field.get(rig.emulator) as Map<*, *>).size
    }

    // ---------------------------------------------------------------- the rows a shrinking screen pushes

    @Test fun `a shrink gives each row it pushes into the history the flag that row had, not the first row's`() {
        Rig(8, 10).use { rig ->
            rig.write(page)
            rig.resize(4, 10)
            assertEquals(listOf("xx" to false, "BBBBBBBBBB" to true), rig.history().wraps())
            assertEquals(listOf("CCCCCCCCCC" to true, "DDDDD" to false, "EEEEE" to false, "$" to false), rig.screen().wraps())
        }
    }

    @Test fun `the row that is on top after a shrink is pushed by the next line feed with the flag it had`() {
        Rig(8, 10).use { rig ->
            rig.write(page)
            rig.resize(4, 10)
            rig.write("\r\nfoo")
            assertEquals(listOf("xx" to false, "BBBBBBBBBB" to true, "CCCCCCCCCC" to true), rig.history().wraps())
            assertEquals(listOf("DDDDD" to false, "EEEEE" to false, "$" to false, "foo" to false), rig.screen().wraps())
        }
    }

    @Test fun `two wrapped rows on top after a shrink are pushed by one write with a flag each`() {
        Rig(8, 10).use { rig ->
            rig.write("xx\r\nyy\r\n" + "C".repeat(10) + "D".repeat(10) + "E".repeat(5) + "\r\n$ ")
            rig.resize(4, 10)
            assertEquals(listOf("xx" to false, "yy" to false), rig.history().wraps())
            assertEquals(listOf("CCCCCCCCCC" to true, "DDDDDDDDDD" to true, "EEEEE" to false, "$" to false), rig.screen().wraps())
            // Two line feeds in one write: libvterm sends the flag of the row below the one it pushes, and after a resize it knows none.
            rig.write("\r\n\r\nfoo")
            assertEquals(listOf("xx" to false, "yy" to false, "CCCCCCCCCC" to true, "DDDDDDDDDD" to true), rig.history().wraps())
        }
    }

    // ---------------------------------------------------------------- the screen's own flags

    @Test fun `a keyboard that opens and closes gives the screen back its wrapped lines, whichever rows were pushed and pulled back`() {
        Rig(8, 10).use { rig ->
            rig.write(page)
            rig.resize(4, 10)
            rig.resize(8, 10)
            assertEquals(pageRows, rig.screen().wraps())
            assertEquals(emptyList<Pair<String, Boolean>>(), rig.history().wraps())
        }
    }

    @Test fun `and as often as it does`() {
        Rig(8, 10).use { rig ->
            rig.write(page)
            repeat(3) {
                rig.resize(4, 10)
                rig.resize(8, 10)
            }
            assertEquals(pageRows, rig.screen().wraps())
            assertEquals(emptyList<Pair<String, Boolean>>(), rig.history().wraps())
        }
    }

    @Test fun `a row that stays on the screen keeps its flag when the screen shrinks only by its blank rows`() {
        Rig(6, 10).use { rig ->
            rig.write("A".repeat(10) + "BBB\r\n$ ")
            rig.resize(4, 10)
            assertEquals(listOf("AAAAAAAAAA" to true, "BBB" to false, "$" to false, "" to false), rig.screen().wraps())
            assertEquals(emptyList<Pair<String, Boolean>>(), rig.history().wraps())
        }
    }

    @Test fun `the flags follow their rows when the screen scrolls`() {
        Rig(8, 10).use { rig ->
            rig.write(page)
            rig.resize(4, 10)
            rig.resize(8, 10)
            // The cursor is on row 5: four line feeds scroll the screen twice, which pushes "xx" and the first wrapped row.
            rig.write("\r\n\r\n\r\n\r\n")
            assertEquals(listOf("xx" to false, "BBBBBBBBBB" to true), rig.history().wraps())
            assertEquals(
                listOf("CCCCCCCCCC" to true, "DDDDD" to false, "EEEEE" to false, "$" to false, "" to false, "" to false, "" to false, "" to false),
                rig.screen().wraps(),
            )
        }
    }

    @Test fun `writing over a row ends the wrap it kept, and only that row's`() {
        Rig(8, 10).use { rig ->
            rig.write(page)
            rig.resize(4, 10)
            rig.resize(8, 10)
            // Erase from the start of row 2 (the second row of the wrapped line) to its end.
            rig.write("$escape[3;1H$escape[K")
            assertEquals(
                listOf("xx" to false, "BBBBBBBBBB" to true, "" to false, "DDDDD" to false, "EEEEE" to false, "$" to false, "" to false, "" to false),
                rig.screen().wraps(),
            )
        }
    }

    @Test fun `text written on the rows below leaves it alone`() {
        Rig(8, 10).use { rig ->
            rig.write(page)
            rig.resize(4, 10)
            rig.resize(8, 10)
            rig.write("$escape[7;1Hlater")
            assertEquals(pageRows.take(6) + listOf("later" to false, "" to false), rig.screen().wraps())
        }
    }

    @Test fun `a flag the library reports again is the library's from then on, so what clears it clears it`() {
        Rig(4, 10).use { rig ->
            rig.write("AA\r\n" + "B".repeat(10) + "C".repeat(5))
            rig.resize(3, 10)
            rig.resize(4, 10)
            // The same line is written again from row 1 and wraps again: the library holds its flag now, and it is no longer a kept one.
            rig.write("$escape[2;1H" + "B".repeat(10) + "C".repeat(5))
            rig.catchUp()
            // One write erases the row and scrolls row 0 off, the next one scrolls the erased row off: that row does not wrap.
            rig.write("$escape[2;1H$escape[K$escape[4;1H\r\n")
            rig.write("$escape[4;1H\r\n")
            assertEquals(listOf("AA" to false, "" to false), rig.history().wraps())
        }
    }

    @Test fun `a new colour scheme re-reads every row and leaves the flags kept through a resize alone`() {
        Rig(8, 10).use { rig ->
            rig.write(page)
            rig.resize(4, 10)
            rig.resize(8, 10)
            // Every row is damaged, and re-read in other colours, so none of them is the cells it was; none of them is written to either.
            rig.emulator.setDefaultColors(0xFF102030.toInt(), 0xFFE0D0C0.toInt())
            assertEquals(pageRows, rig.screen().wraps())
        }
    }

    @Test fun `a row between two writes keeps its flag, though the library reports one rectangle that covers it`() {
        Rig(8, 10).use { rig ->
            rig.write(page)
            rig.resize(4, 10)
            rig.resize(8, 10)
            // Row 2 is inside the damage of both writes and is not touched by either; row 1 is rewritten, and a rewritten row is not known to wrap.
            rig.write("$escape[2;1Hzz$escape[7;1Hlater")
            assertEquals(
                listOf("xx" to false, "zzBBBBBBBB" to false, "CCCCCCCCCC" to true, "DDDDD" to false, "EEEEE" to false, "$" to false, "later" to false, "" to false),
                rig.screen().wraps(),
            )
        }
    }

    @Test fun `a row that one write erases and scrolls off is pushed with the library's flag, not the older one the screen showed`() {
        Rig(4, 10).use { rig ->
            rig.write("AA\r\n" + "B".repeat(10) + "C".repeat(5))
            rig.catchUp()
            // The screen has shown row 1 as wrapped. The write erases it, which clears its flag, and scrolls row 0 off; the next one scrolls it off.
            rig.write("$escape[2;1H$escape[K$escape[4;1H\r\n")
            rig.write("$escape[4;1H\r\n")
            assertEquals(listOf("AA" to false, "" to false), rig.history().wraps())
        }
    }

    @Test fun `a flag the library knows again, because a line wrapped after the resize, is read from it`() {
        Rig(8, 10).use { rig ->
            rig.write(page)
            rig.resize(4, 10)
            rig.resize(8, 10)
            rig.write("$escape[7;1H" + "F".repeat(10) + "GGG")
            assertEquals(pageRows.take(6) + listOf("FFFFFFFFFF" to true, "GGG" to false), rig.screen().wraps())
            // And it survives the next resize like any other.
            rig.resize(5, 10)
            rig.resize(8, 10)
            assertEquals(pageRows.take(6) + listOf("FFFFFFFFFF" to true, "GGG" to false), rig.screen().wraps())
        }
    }

    @Test fun `a resize in columns ends every wrap on the screen, as the rows are cut or padded and no longer break where they did`() {
        Rig(6, 10).use { rig ->
            rig.write("A".repeat(10) + "BBB\r\n$ ")
            rig.resize(6, 12)
            assertEquals(listOf("AAAAAAAAAA" to false, "BBB" to false, "$" to false, "" to false, "" to false, "" to false), rig.screen().wraps())
        }
    }

    @Test fun `but the rows a resize in columns pushes keep the flags they had at the width they were`() {
        Rig(8, 10).use { rig ->
            rig.write(page)
            rig.resize(4, 12)
            assertEquals(listOf("xx" to false, "BBBBBBBBBB" to true), rig.history().wraps())
            assertEquals(listOf(false, false, false, false), rig.screen().map { it.softWrapped })
        }
    }

    // ---------------------------------------------------------------- the alternate screen

    @Test fun `with the alternate screen up a resize pushes the rows of the alternate screen into the history, each with its own flag, and the shell's are not in it`() {
        Rig(6, 10).use { rig ->
            rig.write("A".repeat(10) + "BBBBB\r\np2\r\np3\r\n$ ")
            rig.write("$escape[H$escape[?1049h" + "ALTALTALTA" + "LT\r\nALT2")
            rig.resize(3, 10)
            // The library pushes what is on the screen it shows, not the primary screen's rows that no longer fit (the wrapped line is one).
            assertEquals(listOf("ALTALTALTA" to true, "LT" to false), rig.history().wraps())
            // And it clears the flags of the screen it shows; none is carried for the alternate screen, whose rows the pushes do not account for.
            assertEquals(listOf("ALTALTALTA" to false, "LT" to false, "ALT2" to false), rig.screen().wraps())
        }
    }

    @Test fun `the alternate screen stays as it is when it grows, though the history gives rows back to the shell under it`() {
        Rig(4, 40).use { rig ->
            rig.write((0..5).joinToString("\r\n") { "h$it" } + "\r\n$ $escape[H$escape[?1049h" + link(1) + "\r\n" + link(2))
            val url = { n: Int -> listOf("https://l$n.test/") }
            assertEquals(listOf("h0", "h1", "h2"), rig.history().texts())
            rig.resize(6, 40)
            // Two of them went to the screen that is not shown; the alternate screen's own rows did not move.
            assertEquals(listOf("h0"), rig.history().texts())
            assertEquals(listOf(url(1), url(2), emptyList(), emptyList(), emptyList(), emptyList()), rig.screen().links())
        }
    }

    @Test fun `the links of the alternate screen stay where they are when it grows, and none is kept when it shrinks`() {
        Rig(6, 40).use { rig ->
            rig.write("p0\r\np1\r\n$ $escape[H$escape[?1049h" + link(1) + "\r\n" + link(2) + "\r\n" + link(3))
            val url = { n: Int -> listOf("https://l$n.test/") }
            rig.resize(8, 40)
            assertEquals(listOf(url(1), url(2), url(3), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()), rig.screen().links())
            rig.resize(4, 40)
            assertEquals(listOf(emptyList<String?>(), emptyList(), emptyList(), emptyList()), rig.screen().links())
        }
    }

    @Test fun `the prompts of the alternate screen stay where they are when it grows, and none is kept when it shrinks, as its links`() {
        Rig(6, 40).use { rig ->
            rig.write("p0\r\n\$ $escape[H$escape[?1049h" + "a0\r\n$escape]133;A\u0007\$ $escape]133;B\u0007ls\r\na2")
            rig.resize(8, 40)
            assertEquals(listOf(false, true, false, false, false, false, false, false), rig.screen().map { it.hasPrompt() })
            rig.resize(4, 40)
            assertEquals(listOf(false, false, false, false), rig.screen().map { it.hasPrompt() })
        }
    }

    // ---------------------------------------------------------------- the links of the rows

    @Test fun `the links of the rows that stay on the screen go with their rows, and the pushed rows take theirs into the history`() {
        Rig(8, 40).use { rig ->
            rig.write((0..5).joinToString("") { link(it) + "\r\n" })
            rig.resize(4, 40)
            val url = { n: Int -> listOf("https://l$n.test/") }
            assertEquals(listOf(url(0), url(1), url(2)), rig.history().links())
            assertEquals(listOf(url(3), url(4), url(5), emptyList()), rig.screen().links())
            assertEquals(listOf("link3", "link4", "link5", ""), rig.screen().texts())
        }
    }

    @Test fun `and a taller screen brings the links of the rows it takes back from the history`() {
        Rig(8, 40).use { rig ->
            rig.write((0..5).joinToString("") { link(it) + "\r\n" })
            rig.resize(4, 40)
            rig.resize(8, 40)
            val url = { n: Int -> listOf("https://l$n.test/") }
            assertEquals(emptyList<List<String?>>(), rig.history().links())
            assertEquals(listOf(url(0), url(1), url(2), url(3), url(4), url(5), emptyList(), emptyList()), rig.screen().links())
        }
    }

    @Test fun `a link on a row that went to the history and came back is still the link of its text, and ends with it`() {
        Rig(8, 40).use { rig ->
            rig.write((0..5).joinToString("") { link(it) + "\r\n" })
            rig.resize(4, 40)
            rig.resize(8, 40)
            // Row 1 is overwritten: its link goes, the others stay.
            rig.write("$escape[2;1Hother")
            val url = { n: Int -> listOf("https://l$n.test/") }
            assertEquals(listOf(url(0), emptyList(), url(2), url(3), url(4), url(5), emptyList(), emptyList()), rig.screen().links())
        }
    }

    @Test fun `a prompt goes with its row too`() {
        Rig(8, 40).use { rig ->
            rig.write("a\r\nb\r\nc\r\nd\r\n$escape]133;A\u0007\$ $escape]133;B\u0007ls")
            rig.resize(4, 40)
            assertEquals(listOf(false, false, false, true), rig.screen().map { it.hasPrompt() })
            rig.resize(8, 40)
            assertEquals(listOf(false, false, false, false, true, false, false, false), rig.screen().map { it.hasPrompt() })
        }
    }

    @Test fun `the texts kept to tell a link from the cells under it go with their rows, and none is left for a row that went to the history`() {
        Rig(8, 40).use { rig ->
            rig.write((0..5).joinToString("") { link(it) + "\r\n" })
            rig.catchUp()
            assertEquals(6, linkTexts(rig))
            rig.resize(4, 40)
            assertEquals(3, linkTexts(rig))
            rig.resize(8, 40)
            assertEquals(6, linkTexts(rig))
        }
    }

    @Test fun `a resize in columns cuts a link at the new width, as before, and keeps the others where they are`() {
        Rig(4, 40).use { rig ->
            rig.write("  " + link(1) + "\r\n" + " ".repeat(30) + link(2) + "\r\n")
            rig.resize(4, 20)
            assertEquals(listOf(listOf("https://l1.test/"), emptyList()), rig.screen().links().take(2))
            rig.resize(4, 40)
            assertEquals(listOf("https://l1.test/"), rig.screen().links()[0])
        }
    }
}
