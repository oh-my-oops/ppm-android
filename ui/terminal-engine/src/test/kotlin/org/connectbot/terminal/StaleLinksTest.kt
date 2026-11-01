package org.connectbot.terminal

import java.nio.ByteBuffer
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.spy
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An OSC 8 link goes away with the cells it was written over. The emulator only learned what text a link covers when it caught up
 * with a write (it reads the screen then), so a link written, and its cells replaced, before that moment looked like a link on
 * whatever text the cells hold now: `ls --hyperlink`, then `clear`, then a listing, reached through a reconnect, which replays the
 * shell's whole buffer as ONE write, put the old links under the first six characters of the new lines (seen on a tablet: a click
 * on `https:` of a plain line opened the address of a line that had scrolled away). The same happened live whenever the write that
 * erases or overwrites a link arrived before the emulator had caught up with the one that made it.
 *
 * The real emulator runs here against [FakeVterm], a model of libvterm's parse and screen (the JNI cannot load on the JVM);
 * `EngineInstrumentation.verifyStaleLinks` runs the same bytes through the real library on a device. The test of every operation
 * is that the way the bytes are delivered makes no difference to what is wrong: one write, writes the emulator has not caught up
 * on, and writes it has caught up on (the case that always worked) all end without a link over text that is not its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StaleLinksTest {
    private val escape = "\u001b"
    private fun link(n: Int) = "$escape]8;;https://l$n.test/\u0007link$n$escape]8;;\u0007"
    private val clear = "$escape[H$escape[2J$escape[3J"

    private class Rig(val rows: Int, val cols: Int, maxScrollbackLines: Int = 50_000) : AutoCloseable {
        @Volatile var flushing = false
        val vterm = FakeVterm(rows, cols)
        val emulator: TerminalEmulatorImpl = spy(
            TerminalEmulatorImpl(initialRows = rows, initialCols = cols, maxScrollbackLines = maxScrollbackLines, nativeFactory = { callbacks -> vterm.attach(callbacks) }),
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

        /** Every row from the oldest in the history to the bottom of the screen: its text, and its links as (columns, address, the text under it). */
        fun rowsWithLinks(): List<Pair<String, List<String>>> {
            catchUp()
            val snapshot = emulator.snapshot.value
            return (snapshot.scrollback + snapshot.lines).map { line ->
                val text = line.cells.indices.joinToString("") { line.cells.text(it, it + 1).ifEmpty { " " } }.replace('\u0000', ' ').trimEnd()
                text to line.semanticSegments.map { "[${it.startCol},${it.endCol}) ${it.metadata} '${line.cells.text(it.startCol, it.endCol)}'" }
            }
        }

        override fun close() = emulator.close()
    }

    /** A link whose text is not the text of its address, which no write made. */
    private fun stale(rows: List<Pair<String, List<String>>>) = rows.flatMapIndexed { index, (text, links) ->
        links.filter { link ->
            val n = link.substringAfter("https://l").substringBefore(".test/")
            !link.endsWith("'link$n'")
        }.map { "row $index '${text.take(20)}': $it" }
    }

    private fun linkCount(rows: List<Pair<String, List<String>>>) = rows.sumOf { it.second.size }

    // ---------------------------------------------------------------- the replay of a shell's buffer

    @Test fun `a replay of links, a clear and plain lines, in one write, leaves no link on the plain lines`() {
        Rig(24, 80).use { rig ->
            val links = (1..40).joinToString("") { link(it) + "\r\n" } + "\$ clear; bash plain.sh\r\n"
            val plain = (1..40).joinToString("") { "https://site$it.example.com/path$it\r\n" } + "\$ "
            rig.write(links + clear + plain)
            val rows = rig.rowsWithLinks()
            assertEquals("a link on text that is not its own", emptyList<String>(), stale(rows))
            assertEquals("the plain lines have no link at all", 0, linkCount(rows))
        }
    }

    @Test fun `the same replay in three writes the emulator had not caught up on leaves no link either`() {
        Rig(24, 80).use { rig ->
            rig.write((1..40).joinToString("") { link(it) + "\r\n" } + "\$ clear; bash plain.sh\r\n")
            rig.write(clear)
            rig.write((1..40).joinToString("") { "https://site$it.example.com/path$it\r\n" } + "\$ ")
            assertEquals(0, linkCount(rig.rowsWithLinks()))
        }
    }

    @Test fun `links written and left alone are all there, in the history and on the screen, whatever way they arrive`() {
        val text = (1..40).joinToString("") { link(it) + "\r\n" } + "\$ "
        val oneWrite = Rig(25, 80).use { rig -> rig.write(text); rig.rowsWithLinks() }
        val perLine = Rig(25, 80).use { rig -> text.split("\r\n").forEach { rig.write(if (it.startsWith("\$")) it else it + "\r\n") }; rig.rowsWithLinks() }
        val caughtUp = Rig(25, 80).use { rig -> text.split("\r\n").forEach { rig.write(if (it.startsWith("\$")) it else it + "\r\n"); rig.catchUp() }; rig.rowsWithLinks() }
        assertEquals(40, linkCount(oneWrite))
        assertEquals(caughtUp, oneWrite)
        assertEquals(caughtUp, perLine)
    }

    @Test fun `a history cut at its limit holds the links of the rows it kept, and only theirs`() {
        val text = (1..40).joinToString("") { link(it) + "\r\n" } + "\$ "
        val oneWrite = Rig(8, 40, maxScrollbackLines = 5).use { rig -> rig.write(text); rig.rowsWithLinks() }
        val caughtUp = Rig(8, 40, maxScrollbackLines = 5).use { rig -> text.split("\r\n").forEach { rig.write(if (it.startsWith("\$")) it else it + "\r\n"); rig.catchUp() }; rig.rowsWithLinks() }
        assertEquals(emptyList<String>(), stale(oneWrite))
        assertEquals(caughtUp, oneWrite)
        // 41 lines on 8 rows push 33 into the history, which keeps the last 5 of them; the screen holds the 7 link rows after those.
        assertEquals(listOf("link29", "link30", "link31", "link32", "link33"), oneWrite.take(5).map { it.first })
        assertEquals(5 + 7, linkCount(oneWrite))
    }

    // ---------------------------------------------------------------- everything that replaces a cell, or moves a row

    /** What the bytes of an operation do to [setup] (six link lines and a seventh row of text), written after it, and then more text. */
    private class Operation(val name: String, val bytes: String, val linksLeft: Int)

    /** The links each operation leaves when the emulator has caught up between its writes, as the real library does it (`EngineInstrumentation.verifyStaleLinks`). */
    private val operations: List<Operation> = listOf(
        Triple("ED 0 from home", "$escape[H$escape[J", 0),
        Triple("ED 2", "$escape[2J", 0),
        Triple("ED 1 at row 4", "$escape[4;3H$escape[1J", 2),
        Triple("ED 2 and ED 3", "$escape[H$escape[2J$escape[3J", 0),
        Triple("EL 0, row 2 from column 0", "$escape[2;1H$escape[K", 5),
        Triple("EL 1, row 2 to column 3", "$escape[2;3H$escape[1K", 5),
        Triple("EL 2, row 2", "$escape[2;1H$escape[2K", 5),
        Triple("ECH 3, row 2", "$escape[2;1H$escape[3X", 5),
        Triple("DCH 2, row 2", "$escape[2;1H$escape[2P", 5),
        Triple("ICH 2, row 2", "$escape[2;1H$escape[2@", 5),
        Triple("IL, row 2", "$escape[2;1H$escape[L", 6),
        Triple("DL, row 2", "$escape[2;1H$escape[M", 4),
        Triple("scroll up in the region 2-5", "$escape[2;5r$escape[5;1H\n$escape[r", 4),
        Triple("scroll down in the region 2-5", "$escape[2;5r$escape[2;1H${escape}M$escape[r", 4),
        Triple("scroll up in the region 1-4", "$escape[1;4r$escape[4;1H\n$escape[r", 1),
        Triple("plain text over row 2", "$escape[2;1HXYZ", 5),
        Triple("plain text over row 2, then EL", "$escape[2;1HXYZ$escape[K", 5),
        Triple("RIS", "${escape}c", 0),
        Triple("alternate screen, in and out", "$escape[?1049hALT$escape[?1049l", 0),
        Triple("SU 2", "$escape[2S", 6),
        Triple("SD 2", "$escape[2T", 5),
        Triple("CR and text over the last row", "\r____", 6),
    ).map { Operation(it.first, it.second, it.third) }

    private val links = (1..6).joinToString("") { link(it) + "\r\n" }

    /** Six link lines, then text on the seventh row and the cursor after it. */
    private val setup = links + "tail"

    private enum class Delivery { ONE_WRITE, NOT_CAUGHT_UP, CAUGHT_UP }

    private fun outcome(operation: Operation, delivery: Delivery, before: String = setup): List<Pair<String, List<String>>> =
        Rig(8, 40).use { rig ->
            when (delivery) {
                Delivery.ONE_WRITE -> rig.write(before + operation.bytes + "after")
                Delivery.NOT_CAUGHT_UP -> { rig.write(before); rig.write(operation.bytes); rig.write("after") }
                Delivery.CAUGHT_UP -> { rig.write(before); rig.catchUp(); rig.write(operation.bytes); rig.catchUp(); rig.write("after") }
            }
            rig.rowsWithLinks()
        }

    @Test fun `no operation leaves a link on text that is not its own, however the writes arrive`() {
        val wrong = mutableListOf<String>()
        for (operation in operations) for (delivery in Delivery.values()) {
            wrong += stale(outcome(operation, delivery)).map { "${operation.name}, $delivery: $it" }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test fun `with the emulator caught up, each operation leaves the links the real library leaves`() {
        assertEquals(
            operations.associate { it.name to it.linksLeft },
            operations.associate { it.name to linkCount(outcome(it, Delivery.CAUGHT_UP)) },
        )
    }

    @Test fun `every operation leaves the same links however its writes arrive`() {
        // Also with text written after the links in the same flush: libvterm reports what a flush changed as one rectangle,
        // which covers unchanged cells between the changed ones, and a link on them stands as long as its text does.
        val differ = mutableListOf<String>()
        for (before in listOf(setup, links)) for (operation in operations) {
            val reference = outcome(operation, Delivery.CAUGHT_UP, before)
            for (delivery in listOf(Delivery.ONE_WRITE, Delivery.NOT_CAUGHT_UP)) {
                val actual = outcome(operation, delivery, before)
                if (actual != reference) {
                    differ += "${operation.name}, $delivery, ${if (before == setup) "with text" else "links only"}:\n    caught up ${reference.filter { it.second.isNotEmpty() }}\n    $delivery ${actual.filter { it.second.isNotEmpty() }}"
                }
            }
        }
        assertEquals(emptyList<String>(), differ)
    }

    @Test fun `a link in the middle of a line is looked at too`() {
        Rig(8, 40).use { rig ->
            // "one two " is eight cells, the link is [8, 13), and the line goes on after it. Its cells are written over in the same write.
            rig.write("one two ${link(1)} three\r" + "ONE TWO XXXXX")
            val rows = rig.rowsWithLinks()
            assertEquals(emptyList<String>(), stale(rows))
            assertEquals("the link's cells were written over", 0, linkCount(rows))
        }
        Rig(8, 40).use { rig ->
            rig.write("one two ${link(1)} three")
            rig.catchUp()
            rig.write("\rONE")
            assertEquals("cells beside the link were written over, not its own", listOf("[8,13) https://l1.test/ 'link1'"), rig.rowsWithLinks().flatMap { it.second })
        }
    }

    @Test fun `text and lines written after a link in the same write do not take the link away`() {
        // The rectangle libvterm reports for what changed after the link covers the link's cells: rows 0 to 3, every column, because
        // the wrapped address fills two rows. The link's own text is still there.
        val wrapped = "https://wrap.example.com/" + "x".repeat(30)
        for (caughtUp in listOf(false, true)) Rig(6, 40).use { rig ->
            rig.write("$escape[H$escape[2J" + "see $escape]8;;https://osc.example.com/a\u0007the docs$escape]8;;\u0007 http://p.test/b ok\r\n" + wrapped + "\r\nend")
            if (caughtUp) rig.catchUp()
            assertEquals(listOf("[4,12) https://osc.example.com/a 'the docs'"), rig.rowsWithLinks().flatMap { it.second })
        }
    }

    @Test fun `links followed in the same flush by writes in other rows and columns keep their rows`() {
        // Cursor-addressed output, as a full-screen program draws: links, and then text between them and below.
        Rig(10, 40).use { rig ->
            rig.write("$escape[H$escape[2J" + (1..4).joinToString("") { "$escape[${it * 2};${it * 3}H${link(it)}" } + "$escape[10;1Hstatus$escape[1;30Hcorner")
            val rows = rig.rowsWithLinks()
            assertEquals(emptyList<String>(), stale(rows))
            assertEquals(4, linkCount(rows))
        }
    }

    @Test fun `ls with links, clear, then ls without links over the same names leaves no link, however the writes arrive`() {
        // The text under the old links is the text of the new plain lines, so only the redraw of the whole screen can tell.
        val names = (1..5).map { "file$it.txt" }
        val listed = "$escape[H$escape[2J" + names.joinToString("") { "$escape]8;;file:///home/u/$it\u0007$it$escape]8;;\u0007\r\n" } + "\$ "
        val plain = "clear\r\n$escape[H$escape[2J$escape[3J" + names.joinToString("\r\n") + "\r\n\$ "
        for (delivery in Delivery.values()) Rig(10, 40).use { rig ->
            when (delivery) {
                Delivery.ONE_WRITE -> rig.write(listed + plain)
                Delivery.NOT_CAUGHT_UP -> { rig.write(listed); rig.write(plain) }
                Delivery.CAUGHT_UP -> { rig.write(listed); rig.catchUp(); rig.write(plain) }
            }
            val rows = rig.rowsWithLinks()
            assertEquals("$delivery: the names are on the screen again", names, rows.map { it.first }.filter { it.startsWith("file") })
            assertEquals("$delivery: a link on a plain listing", emptyList<String>(), rows.flatMap { it.second })
        }
    }

    @Test fun `a listing that scrolled, a clear and the same names plain leave no link on the screen, though an earlier write scrolled`() {
        // 30 links on 24 rows leave file8 to file30 and the prompt on the screen (the first seven rows are history). The plain listing
        // draws exactly that screen again without scrolling, so the text under every old link is the text it was made over. Only the
        // redraw of the whole screen ends them, and an earlier write that scrolled must not have taught the emulator otherwise.
        // (In ONE write the Enter after the last link scrolls in the same flush as the clear, which then cannot be told from the
        // scroll alone: links over text that did not change stay. See the README.)
        val names = (1..30).map { "file$it.txt" }
        val listed = names.joinToString("") { "$escape]8;;file:///home/u/$it\u0007$it$escape]8;;\u0007\r\n" } + "\$ "
        val plain = "$escape[H$escape[2J" + names.drop(7).joinToString("\r\n") + "\r\n\$ "
        for (delivery in listOf(Delivery.NOT_CAUGHT_UP, Delivery.CAUGHT_UP)) Rig(24, 40).use { rig ->
            when (delivery) {
                Delivery.NOT_CAUGHT_UP -> { rig.write(listed); rig.write(plain) }
                else -> { rig.write(listed); rig.catchUp(); rig.write(plain) }
            }
            val screen = rig.rowsWithLinks().takeLast(24)
            assertEquals("$delivery: the names are on the screen again", names.drop(7) + "\$", screen.map { it.first })
            assertEquals("$delivery: a link on a plain listing", emptyList<String>(), screen.flatMap { it.second })
        }
    }

    @Test fun `a link made over the cells of another replaces it, even when the text is the same`() {
        fun named(url: String) = "$escape]8;;https://$url.test/\u0007same$escape]8;;\u0007"
        for (delivery in Delivery.values()) Rig(8, 40).use { rig ->
            val first = "$escape[H$escape[2J" + named("old")
            val second = "$escape[H" + named("new")
            when (delivery) {
                Delivery.ONE_WRITE -> rig.write(first + second)
                Delivery.NOT_CAUGHT_UP -> { rig.write(first); rig.write(second) }
                Delivery.CAUGHT_UP -> { rig.write(first); rig.catchUp(); rig.write(second) }
            }
            assertEquals(delivery.name, listOf("[0,4) https://new.test/ 'same'"), rig.rowsWithLinks().flatMap { it.second })
        }
    }

    @Test fun `a prompt, which is not a link, goes when its cells are written over and stays when other cells are, as it did before`() {
        // A link stands as long as its text does. Any other segment is still ended by damage on its columns.
        val prompt = "$escape]133;A\u0007\$ $escape]133;B\u0007"
        fun promptsLeft(after: String) = Rig(8, 40).use { rig ->
            rig.write(prompt + "ls")
            rig.catchUp()
            rig.write(after)
            rig.rowsWithLinks().first().second.filter { it.startsWith("[0,2)") }
        }
        assertEquals(listOf("[0,2) null '\$ '"), promptsLeft(" -la"))
        assertEquals(emptyList<String>(), promptsLeft("\rXY"))
    }

    @Test fun `a resize in columns does not take the links away, though the library damages the whole screen for it`() {
        Rig(8, 40).use { rig ->
            rig.write(setup)
            val before = rig.rowsWithLinks()
            rig.emulator.resize(8, 50, 500, 400)
            assertEquals(before.map { it.second }, rig.rowsWithLinks().map { it.second })
            assertEquals(6, linkCount(rig.rowsWithLinks()))
            // The screen is still redrawn the way it was: a clear ends the links.
            rig.write(clear + "other")
            assertEquals(0, linkCount(rig.rowsWithLinks()))
        }
    }

    @Test fun `a link the writes never ended at is still shown, as it was before they were cut`() {
        Rig(8, 40).use { rig ->
            rig.write("link1")
            rig.catchUp()
            // What the library calls back with when an OSC 8 opens and closes, with no write around it to end at.
            rig.emulator.onTextFragment(TerminalTextDecoder.OSC, 8, ";https://l1.test/".toByteArray(), true, true, 0, 0)
            rig.emulator.onTextFragment(TerminalTextDecoder.OSC, 8, ";".toByteArray(), true, true, 0, 5)
            assertEquals(listOf("[0,5) https://l1.test/ 'link1'"), rig.rowsWithLinks().flatMap { it.second })
        }
    }

    @Test fun `a reset forgets a link that was waiting to be shown, and an OSC 8 that was half read`() {
        Rig(8, 40).use { rig ->
            rig.write("link1")
            rig.emulator.onTextFragment(TerminalTextDecoder.OSC, 8, ";https://l1.test/".toByteArray(), true, true, 0, 0)
            rig.emulator.onTextFragment(TerminalTextDecoder.OSC, 8, ";".toByteArray(), true, true, 0, 5)
            rig.emulator.reset()
            rig.write("${escape}clink1")
            assertEquals(0, linkCount(rig.rowsWithLinks()))
        }
        Rig(8, 40).use { rig ->
            rig.write("$escape]8;;https://a.te")
            rig.emulator.reset()
            rig.vterm.writes.clear()
            rig.write("st/\u0007x")
            assertEquals("the BEL ends nothing the library started: one piece", 1, rig.vterm.writes.size)
        }
    }

    // ---------------------------------------------------------------- the writes themselves

    @Test fun `a write that makes a link is cut after each OSC 8 and the text under each link is read in between, but no snapshot is built until the write is over`() {
        Rig(8, 40).use { rig ->
            val before = rig.emulator.snapshot.value
            rig.write(link(1) + "\r\n" + link(2))
            assertSame("the UI is shown nothing until the write is over", before, rig.emulator.snapshot.value)
            assertEquals("one small read of the screen for each of the two links", 2L, rig.emulator.transferCalls)
            rig.catchUp()
            assertNotSame(before, rig.emulator.snapshot.value)
            assertEquals(
                listOf("$escape]8;;https://l1.test/\u0007", "link1$escape]8;;\u0007", "\r\n$escape]8;;https://l2.test/\u0007", "link2$escape]8;;\u0007"),
                rig.vterm.writes,
            )
        }
    }

    @Test fun `a write with no OSC 8 in it reaches the library as one piece, as before`() {
        Rig(8, 40).use { rig ->
            rig.write("plain\r\n$escape[1mbold$escape[0m\r\n$escape]0;a title\u0007$escape]133;A\u0007")
            assertEquals(1, rig.vterm.writes.size)
        }
    }

    @Test fun `an OSC 8 that ends in the next write is still cut after, and the link is looked at`() {
        Rig(8, 40).use { rig ->
            // The close arrives in a write of its own, the terminator split from its ESC.
            rig.write("$escape]8;;https://l1.test/\u0007link1$escape]8;;$escape")
            rig.write("\\")
            rig.write(clear + "other")
            assertEquals(0, linkCount(rig.rowsWithLinks()))
        }
    }

    @Test fun `an OSC 8 that ends at the end of a write is read before the next write comes`() {
        Rig(8, 40).use { rig ->
            rig.write(link(1))
            rig.write("$escape[2J" + "other")
            assertEquals(0, linkCount(rig.rowsWithLinks()))
        }
    }

    @Test fun `the same write through a direct buffer is cut and read in the same way`() {
        val bytes = (setup + "$escape[H$escape[J" + "after").toByteArray(Charsets.ISO_8859_1)
        val viaArray = Rig(8, 40).use { rig -> rig.emulator.writeInput(bytes, 0, bytes.size); rig.vterm.writes to rig.rowsWithLinks() }
        val viaBuffer = Rig(8, 40).use { rig ->
            val direct = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); flip() }
            rig.emulator.writeInput(direct, bytes.size)
            rig.vterm.writes to rig.rowsWithLinks()
        }
        assertEquals(viaArray, viaBuffer)
        assertTrue("it was cut", viaBuffer.first.size > 1)
        assertEquals(0, linkCount(viaBuffer.second))
    }

    @Test fun `a direct buffer is read from its start up to the length given, whatever its position and limit`() {
        val bytes = (setup + "$escape[H$escape[J" + "after").toByteArray(Charsets.ISO_8859_1)
        val reference = Rig(8, 40).use { rig -> rig.emulator.writeInput(bytes, 0, bytes.size); rig.vterm.writes }
        // Filled and not flipped (the position is at the end, the limit is the capacity), with room to spare.
        val unflipped = Rig(8, 40).use { rig ->
            val direct = ByteBuffer.allocateDirect(bytes.size + 50).apply { put(bytes) }
            rig.emulator.writeInput(direct, bytes.size)
            rig.vterm.writes
        }
        // A limit short of the length, which the library does not look at either.
        val shortLimit = Rig(8, 40).use { rig ->
            val direct = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); limit(3) }
            rig.emulator.writeInput(direct, bytes.size)
            rig.vterm.writes
        }
        assertEquals(reference, unflipped)
        assertEquals(reference, shortLimit)
    }

    // ---------------------------------------------------------------- where the writes are cut

    /** What a random stream is made of: text, links ended both ways, other OSCs, strings that are cancelled or aborted, stray ESCs. */
    private val fragments: List<(Int) -> String> = listOf(
        { n -> link(n) },
        { n -> "$escape]8;id=$n;https://x$n.test/$escape\\x$n$escape]8;;$escape\\" },
        { n -> "$escape]0;title $n\u0007" },
        { n -> "$escape]8;;https://cancelled$n.test/\u0018still text" },
        { n -> "$escape]8;;https://aborted$n.test/${escape}Xtext" },
        { _ -> escape },
        { _ -> "$escape\\" },
        { n -> "plain $n\r\n" },
        { n -> "$escape]133;A\u0007prompt $n" },
        { _ -> "$escape]8;;\u0007" },
        { n -> "$escape]08;;https://zero$n.test/\u0007z$n$escape]8;;\u0007" },
        { n -> "$escape]80;not a link $n\u0007" },
        { _ -> "$escape]8\u0007" },
        { n -> "$escape]4294967304;;https://wrapped$n.test/\u0007w$n$escape]8;;\u0007" },
        { _ -> "$escape]8$escape\\" },
        { n -> "$escape]8\r0;;https://control$n.test/\u0007c$n$escape]8;;\u0007" },
        { n -> "$escape\r]8;;https://pending$n.test/\u0007p$n$escape]8;;\u0007" },
        { n -> "$escape]8;;https://cancelled$n.test/$escape\u0007]8;;https://after$n.test/\u0007a$n$escape]8;;\u0007" },
    )

    /** An OSC inside an OSC: the library cancels the first one at the ESC, and a scan that took the `ESC ]` for a new OSC cuts where the library does not. */
    private val nested: (Int) -> String = { n -> "$escape]8;;https://a.test/$escape]8;;https://b.test/\u0007text$n$escape]8;;\u0007" }

    private class Cuts(val stream: String, val chunkEnds: Set<Int>, val pieceEnds: Set<Int>, val oscEnds: List<Int>)

    /** A random stream given to the emulator in random writes: the ends of the writes, of the pieces the library was given, and of the OSC 8 it finished. */
    private fun cutsOf(random: Random, pool: List<(Int) -> String>): Cuts {
        val text = (1..random.nextInt(1, 14)).joinToString("") { pool[random.nextInt(pool.size)](it) }
        val stream = text.toByteArray(Charsets.ISO_8859_1)
        val largest = listOf(1, 2, 7, 40, 1000)[random.nextInt(5)]
        return Rig(8, 60).use { rig ->
            val chunkEnds = mutableSetOf<Int>()
            var from = 0
            while (from < stream.size) {
                val to = minOf(stream.size, from + random.nextInt(1, largest + 1))
                rig.emulator.writeInput(stream, from, to - from)
                chunkEnds += to
                from = to
            }
            Cuts(text.replace(escape, "<ESC>"), chunkEnds, rig.vterm.writes.runningFold(0) { sum, piece -> sum + piece.length }.toSet(), rig.vterm.oscEnds.toList())
        }
    }

    @Test fun `a write is cut at the end of every OSC 8 the library finishes, and nowhere else, however the writes are chunked`() {
        val random = Random(8)
        repeat(300) { round ->
            val cuts = cutsOf(random, fragments)
            assertEquals("round $round, ${cuts.stream}", cuts.chunkEnds + cuts.oscEnds + 0, cuts.pieceEnds)
        }
    }

    @Test fun `a write is cut at every OSC 8 the library finishes even when strings are nested, where it may also be cut once too often`() {
        val random = Random(88)
        repeat(300) { round ->
            val cuts = cutsOf(random, fragments + nested)
            assertEquals("round $round, ${cuts.stream}", emptyList<Int>(), cuts.oscEnds.filter { it !in cuts.pieceEnds })
        }
    }
}
