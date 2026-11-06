package org.connectbot.terminal

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The scroll region on the real library (`EngineInstrumentation` runs these on a device). `ScrollRegionTest` and `WriteCutsTest` run the same
 * flows on the JVM against [FakeVterm], a model of libvterm's region, DECOM and alternate screen that is only worth anything while the library
 * does the same: [verifyScrollRegion] is those flows on the library, with a vim session captured from a device as a replay, [verifyRegionScan]
 * holds the scan's idea of the region the library has to what the library answers to a DECRQSS, over streams of every sequence that can change
 * it, and [verifyRegionOps] holds the region the emulator leaves on each screen to the one xterm.js keeps, over random sessions.
 */
internal object RegionChecks {
    private const val ESC = "\u001b"
    private const val BEL = "\u0007"
    private const val CAN = "\u0018"
    private const val SUB = "\u001a"
    private const val ENTER = ESC + "[?1049h"
    private const val LEAVE = ESC + "[?1049l"

    /** DECRQSS for the scroll region, and the library's answer to it: `DCS 1 $ r top ; bottom r ST`. */
    private const val ASK_REGION = ESC + "P\$qr" + ESC + "\\"
    private val regionAnswer = Regex("\u001bP1[$]r(\\d+);(\\d+)r\u001b\\\\")
    private val cursorAnswer = Regex("\u001b\\[(\\d+);(\\d+)R")

    private fun <T> expect(what: String, actual: T, expected: T) = check(actual == expected) { "$what: $actual, not $expected" }

    private fun parseRegion(answer: String): Pair<Int, Int> {
        val match = regionAnswer.find(answer) ?: error("No answer to the DECRQSS: " + answer.replace(ESC, "<ESC>"))
        return match.groupValues[1].toInt() to match.groupValues[2].toInt()
    }

    private fun parseCursor(answer: String): Pair<Int, Int> {
        val match = cursorAnswer.find(answer) ?: error("No answer to the DSR: " + answer.replace(ESC, "<ESC>"))
        return match.groupValues[1].toInt() - 1 to match.groupValues[2].toInt() - 1
    }

    /** An emulator on the real library, with what the library says in answer collected from the looper it is posted to. */
    private class Term(rows: Int, cols: Int) : AutoCloseable {
        private val thread = HandlerThread("region-checks").also { it.start() }
        private val heard = ByteArrayOutputStream()
        val emulator = TerminalEmulatorFactory.create(
            looper = thread.looper,
            initialRows = rows,
            initialCols = cols,
            onKeyboardInput = { synchronized(heard) { heard.write(it) } },
        ) as TerminalEmulatorImpl

        fun write(text: String) = emulator.writeInput(text.toByteArray(Charsets.ISO_8859_1))

        fun write(bytes: ByteArray) = emulator.writeInput(bytes)

        fun replay(bytes: ByteArray) = emulator.writeReplay(bytes)

        fun resize(rows: Int, cols: Int) = emulator.resize(rows, cols, cols * 10, rows * 20)

        /** What the library has said since this was last asked, once every answer posted before the call has been delivered. */
        fun heard(): String {
            val done = CountDownLatch(1)
            Handler(thread.looper).post { done.countDown() }
            check(done.await(10, TimeUnit.SECONDS)) { "The emulator's looper did not run" }
            return synchronized(heard) { heard.toString("ISO-8859-1").also { heard.reset() } }
        }

        /** The rows of the screen as text. */
        fun screen(): List<String> {
            val buffer = emulator.getBufferSnapshot()
            return buffer.lines.subList(buffer.screenStartRow, buffer.lines.size).map { it.text.trimEnd() }
        }

        /** The scroll region the library holds, from its answer to a DECRQSS: the first row counted from 1, and the last. */
        fun region(): Pair<Int, Int> {
            heard()
            write(ASK_REGION)
            return parseRegion(heard())
        }

        /** Where the library's own cursor is, from its answer to a DSR: the row and the column counted from 0. */
        fun cursor(): Pair<Int, Int> {
            heard()
            write(ESC + "[6n")
            return parseCursor(heard())
        }

        override fun close() {
            emulator.close()
            thread.quitSafely()
        }
    }

    /**
     * The library with nothing between it and the bytes but what the emulator does to a write ([scan] false: nothing at all): pieces cut where
     * the scan says, and the region put right after each cut. Its answers are collected as they are given, which is while the write is made.
     */
    private class Raw(private val rows: Int, private val cols: Int, private val scan: Boolean = true) : TerminalCallbacks, AutoCloseable {
        private val scratch = CellData.buffer()
        val scanner = WriteCuts(rows)
        private val heard = ByteArrayOutputStream()
        var row = 0
        var column = 0
        var alternate = false
        private val native = TerminalNative(this).also { it.resize(rows, cols) }

        override fun cellBuffer(): ByteBuffer = scratch
        override fun damage(startRow: Int, endRow: Int, startCol: Int, endCol: Int) = 0
        override fun moverect(dest: TermRect, src: TermRect) = 0
        override fun moveCursor(pos: CursorPosition, oldPos: CursorPosition, visible: Boolean): Int {
            row = pos.row
            column = pos.col
            return 0
        }
        override fun setTermProp(prop: Int, value: TerminalProperty): Int {
            if (prop == 3 && value is TerminalProperty.BoolValue) alternate = value.value
            return 0
        }
        override fun bell() = 0
        override fun pushScrollbackLine(cols: Int, start: Int, count: Int, cells: ByteBuffer, softWrapped: Boolean) = 0
        override fun popScrollbackLine(cols: Int, start: Int, count: Int, cells: ByteBuffer) = 0
        override fun clearScrollback() = 0
        override fun onKeyboardInput(data: ByteArray): Int {
            heard.write(data)
            return 0
        }
        override fun onTextFragment(kind: Int, command: Int, data: ByteArray, initial: Boolean, final: Boolean, cursorRow: Int, cursorCol: Int) = 1

        /** The library is asked, directly: what it says in answer. The scan has nothing to learn from a complete sequence that changes nothing. */
        fun ask(query: String): String {
            heard.reset()
            val bytes = query.toByteArray(Charsets.ISO_8859_1)
            native.writeInput(bytes, 0, bytes.size)
            return heard.toString("ISO-8859-1").also { heard.reset() }
        }

        /** [ask] for the cursor: where the library's own is. */
        fun libraryCursor() = parseCursor(ask(ESC + "[6n"))

        fun heardSinceLastAsk() = heard.toString("ISO-8859-1").also { heard.reset() }

        /**
         * What the emulator does at the end of a resize: the library is resized, the scan is told, and the region put right. [between] runs once the
         * library has resized and before the fix, when the region the library holds is the one it has bounded by the new height itself.
         */
        fun resize(newRows: Int, between: () -> Unit) {
            native.resize(newRows, cols)
            scanner.resized(newRows)
            between()
            val cursor = libraryCursor()
            row = cursor.first
            column = cursor.second
            scanner.regionFix(row, column)?.let { native.writeInput(it, 0, it.size) }
        }

        /**
         * What `TerminalEmulatorImpl.writeInput` does with the bytes from [from] until [to]. [fixed] hears of each region put right: the cursor the
         * emulator had tracked, the one the library had (asked), the one it has after the fix, and whether the origin mode was on.
         */
        fun feed(
            bytes: ByteArray,
            from: Int = 0,
            to: Int = bytes.size,
            fixed: ((tracked: Pair<Int, Int>, before: Pair<Int, Int>, after: Pair<Int, Int>, origin: Boolean) -> Unit)? = null,
        ) {
            if (!scan) {
                native.writeInput(bytes, from, to - from)
                return
            }
            var start = from
            while (true) {
                val cut = scanner.next(bytes, start, to)
                val end = if (cut < 0) to else cut
                // A cut just before the `h` of an entry into the alternate screen can stand where the piece began, and an empty one is not handed over.
                if (end > start) native.writeInput(bytes, start, end - start)
                if (cut >= 0) {
                    val fix = scanner.regionFix(row, column)
                    if (fix != null) {
                        val tracked = row to column
                        val before = libraryCursor()
                        val origin = scanner.origin
                        native.writeInput(fix, 0, fix.size)
                        fixed?.invoke(tracked, before, libraryCursor(), origin)
                    }
                }
                if (end >= to) return
                start = end
            }
        }

        /** The rows of the screen as text, read from the library. */
        fun screen(): List<String> {
            val ranges = IntArray(rows * 2) { if (it % 2 == 0) 0 else cols }
            val lines = Array(rows) { StringBuilder() }
            // A row that does not fit in what is left of the transfer buffer comes in two pieces, in order.
            ScreenTransfer().fetch(TerminalNativeAdapter(native), ranges) { r, _, count, buffer, offset, _ ->
                for (i in 0 until count) {
                    val cp = buffer.getInt(offset + i * CellData.BYTES)
                    lines[r].append(if (cp == 0) ' ' else String(Character.toChars(cp)))
                }
            }
            return lines.map { it.toString().trimEnd() }
        }

        override fun close() = native.close()
    }

    // ---------------------------------------------------------------- the flows of ScrollRegionTest, on the library

    /** What vim writes: the alternate screen, a region of the first [height] rows that it leaves set, some drawing, and the way out. */
    private fun vim(height: Int) =
        ENTER + ESC + "[22;0;0t" + ESC + "[>4;2m" + ESC + "[?1h" + ESC + "=" + ESC + "[1;" + height + "r" + ESC + "[H" + ESC + "[2J~\r\n~\r\n~" +
            ESC + "[?25l" + ESC + "[?25h" + ESC + "[?1l" + ESC + ">" + ESC + "[>4;m" + ESC + "[23;0;0t" + LEAVE

    /** "last", a line feed and "next", written from the first column of the last row: the screen scrolls, or "next" lands on "last". */
    private fun expectScrollsAtTheBottom(what: String, term: Term, rows: Int) {
        term.write(ESC + "[" + rows + ";1H" + "last\r\nnext")
        expect(what + ": the last two rows after a line feed on the last", term.screen().takeLast(2), listOf("last", "next"))
    }

    /** Row n holds "R" and n, for the rows 0 until [rows], whatever they held. */
    private fun label(term: Term, rows: Int) {
        for (row in 0 until rows) term.write(ESC + "[" + (row + 1) + ";1H" + ESC + "[2KR" + row)
    }

    /** Rows 3 to 8 of ten are the region, and a line feed on row 8 scrolls them and no others. */
    private fun expectRegionOfThreeToEight(what: String, term: Term) {
        term.write(ESC + "[8;1H\n")
        expect(what, term.screen(), listOf("R0", "R1", "R3", "R4", "R5", "R6", "R7", "", "R8", "R9"))
    }

    fun verifyScrollRegion(context: Context) {
        Term(6, 20).use { t ->
            t.replay(("\$ vim\r\n" + vim(12) + "\$ ").toByteArray())
            t.resize(12, 20)
            expect("Region of a replay written at 6 rows and shown at 12", t.region(), 1 to 12)
            expectScrollsAtTheBottom("A replay written at 6 rows and shown at 12", t, 12)
        }
        Term(30, 20).use { t ->
            t.replay(("\$ vim\r\n" + vim(18) + "\$ ").toByteArray())
            expect("Region of the replay of a vim that ran at 18 rows, shown at 30", t.region(), 1 to 30)
            expectScrollsAtTheBottom("The replay of a vim that ran at 18 rows, shown at 30", t, 30)
        }
        for ((rows, cols) in listOf(14 to 20, 10 to 30, 6 to 20)) {
            Term(10, 20).use { t ->
                t.write(ESC + "[3;8r")
                expect("Region before a resize to " + rows + "x" + cols, t.region(), 3 to 8)
                t.resize(rows, cols)
                expect("Region after a resize to " + rows + "x" + cols, t.region(), 1 to rows)
                expectScrollsAtTheBottom("A region set before a resize to " + rows + "x" + cols, t, rows)
            }
        }
        Term(10, 20).use { t ->
            t.write(ESC + "[3;8r")
            t.emulator.resize(10, 20, 999, 999)
            expect("Region after a resize in pixels only", t.region(), 3 to 8)
            label(t, 10)
            expectRegionOfThreeToEight("A resize in pixels only is not a size change", t)
        }
        Term(18, 20).use { t ->
            t.write("\$ vim\r\n" + vim(18) + "\$ ")
            expect("Region after vim at the height of the screen", t.region(), 1 to 18)
            expectScrollsAtTheBottom("Vim at the height of the screen", t, 18)
        }
        Term(10, 20).use { t ->
            label(t, 10)
            t.write(ESC + "[3;8r")
            t.write(ENTER + ESC + "[1;5r" + ESC + "[2J" + ESC + "[H" + "vim")
            expect("Region of the alternate screen", t.region(), 1 to 5)
            t.write(LEAVE)
            expect("Region of the shell's screen when the alternate one is left", t.region(), 3 to 8)
            expect("The shell's screen is as it was", t.screen(), (0 until 10).map { "R$it" })
            expectRegionOfThreeToEight("The shell's region comes back when the alternate screen is left", t)
        }
        Term(10, 20).use { t ->
            label(t, 10)
            t.write(ESC + "[3;8r" + ENTER)
            expect("Region of the alternate screen the shell's region was set before", t.region(), 1 to 10)
            t.write(ESC + "[10;1H" + "A\r\nB")
            expect("The alternate screen starts with the whole screen as its region", t.screen().takeLast(2), listOf("A", "B"))
            t.write(LEAVE)
            expectRegionOfThreeToEight("The shell's region after the alternate screen", t)
        }
        Term(10, 20).use { t ->
            t.write(ENTER + ESC + "[2;5r" + LEAVE + ENTER)
            expect("Region of the alternate screen entered again", t.region(), 1 to 10)
            t.write(ESC + "[10;1H" + "A\r\nB")
            expect("A region set on the alternate screen is not the next one's", t.screen().takeLast(2), listOf("A", "B"))
        }
        Term(10, 20).use { t ->
            t.write(ESC + "[3;8r" + ESC + "[6;4H")
            expect("Cursor before the alternate screen", t.cursor(), 5 to 3)
            t.write(ENTER)
            expect("Cursor when the alternate screen is entered", t.cursor(), 5 to 3)
            t.write(ESC + "[1;5r" + ESC + "[8;9H")
            t.write(LEAVE)
            expect("Cursor when the alternate screen is left", t.cursor(), 5 to 3)
            t.write("X")
            expect("The next character is written there", t.screen()[5].getOrNull(3), 'X')
        }
        Term(10, 20).use { t ->
            t.write(ESC + "[3;8r" + ESC + "[8;4H")
            t.resize(14, 20)
            val after = t.cursor()
            Term(10, 20).use { control ->
                control.write(ESC + "[8;4H")
                control.resize(14, 20)
                expect("Cursor of a resize with a region held, as the one with none", after, control.cursor())
            }
        }
        Term(10, 20).use { t ->
            t.write(ESC + "[3;8r" + ESC + "[?6h" + ESC + "[2;5H")
            expect("Cursor with the origin mode on", t.cursor(), 3 to 4)
            t.write(ENTER)
            expect("Cursor with the origin mode on, in the alternate screen", t.cursor(), 3 to 4)
            t.write(ESC + "[1;5r")
            t.write(LEAVE)
            expect("Cursor with the origin mode on, when the alternate screen is left", t.cursor(), 3 to 4)
            t.write(ESC + "[1;1H")
            expect("The first row is the first of the shell's region", t.cursor(), 2 to 0)
        }
        Term(10, 20).use { t ->
            t.write(ESC + "[3;8r" + ESC + "[1;1H" + ENTER + ESC + "[?6h" + ESC + "[1;5r")
            t.write(LEAVE)
            expect("A cursor above the region cannot be put back in the origin mode", t.cursor(), 2 to 0)
        }
        Term(5, 10).use { t ->
            t.write("AAAAAAAAAA" + ENTER + LEAVE + "B")
            expect("A pending wrap survives a program that only enters and leaves", t.screen().take(2), listOf("AAAAAAAAAA", "B"))
        }
        Term(10, 20).use { t ->
            t.write(ESC + "[3;8r" + ESC + "c")
            expect("Region after RIS", t.region(), 1 to 10)
            t.resize(14, 20)
            t.write(ENTER + LEAVE)
            expect("Region after RIS, a resize, and the alternate screen", t.region(), 1 to 14)
            expectScrollsAtTheBottom("RIS", t, 14)
        }
        Term(10, 20).use { t ->
            t.write(ESC + "[3;8r" + ESC + "[!p")
            expect("Region after DECSTR", t.region(), 1 to 10)
            t.resize(14, 20)
            t.write(ENTER + LEAVE)
            expect("Region after DECSTR, a resize, and the alternate screen", t.region(), 1 to 14)
        }
        Term(30, 20).use { t ->
            t.write(ESC + "[1;18r" + ESC + "[2")
            t.resize(40, 20)
            t.write(";3Hmore")
            expect("The sequence a resize came in the middle of is taken whole", t.screen()[1], "  more")
            expect("Region after it", t.region(), 1 to 40)
            expectScrollsAtTheBottom("A resize in the middle of a sequence", t, 40)
        }
        Term(10, 20).use { t ->
            t.write(ESC + "[3;8r")
            t.emulator.reset()
            expect("Region of the library a reset makes", t.region(), 1 to 10)
            t.write(ENTER + LEAVE)
            expect("Region after the alternate screen, in the library a reset made", t.region(), 1 to 10)
        }
        verifyVimReplay(context)
    }

    // ---------------------------------------------------------------- a vim session captured from a device

    /**
     * `replay-vim.bin` is the replay the server gave a terminal that had run vim at 105x18 (its identifying strings replaced, byte for byte): vim
     * sets `ESC [ 1 ; 18 r` on the alternate screen and leaves it with `?1049l`. The library alone keeps that region, which at any other
     * height is not the whole screen. The emulator gives the shell the region it had and answers nothing to the queries in the replay; and
     * what it does to the replay is one thing, which this holds it to: the same bytes written to the library alone, with `ESC [ r` and a
     * cursor position (the one the library had) put in just after the `?1049l`.
     */
    private fun verifyVimReplay(context: Context) {
        val replay = context.assets.open("replay-vim.bin").use { it.readBytes() }
        val leave = LEAVE.toByteArray(Charsets.ISO_8859_1)
        val leaveAt = (0..replay.size - leave.size).first { at -> leave.indices.all { replay[at + it] == leave[it] } } + leave.size
        Raw(30, 105, scan = false).use { bare ->
            bare.feed(replay)
            expect("Region the library alone keeps after the replay at 30 rows", parseRegion(bare.ask(ASK_REGION)), 1 to 18)
        }
        val referenceScreen: List<String>
        val referenceCursor: Pair<Int, Int>
        Raw(30, 105, scan = false).use { reference ->
            reference.feed(replay, 0, leaveAt)
            val (row, column) = reference.libraryCursor()
            reference.feed((ESC + "[r" + ESC + "[" + (row + 1) + ";" + (column + 1) + "H").toByteArray(Charsets.ISO_8859_1))
            reference.feed(replay, leaveAt, replay.size)
            expect("Region of the library alone with the region put right at the end of vim", parseRegion(reference.ask(ASK_REGION)), 1 to 30)
            referenceScreen = reference.screen()
            referenceCursor = reference.libraryCursor()
        }
        check(referenceScreen.count { it.isNotEmpty() } > 20) { "The replay left too little on the screen to compare: " + referenceScreen }
        Term(30, 105).use { t ->
            t.replay(replay)
            expect("Answers the replay gave", t.heard(), "")
            expect("Region after the replay at 30 rows", t.region(), 1 to 30)
            expect("Screen after the replay at 30 rows, as the bytes with the region put right at the end of vim", t.screen(), referenceScreen)
            expect("Cursor after the replay at 30 rows, as the bytes with the region put right at the end of vim", t.cursor(), referenceCursor)
            expectScrollsAtTheBottom("The replay of vim at 30 rows", t, 30)
        }
        Term(6, 105).use { t ->
            t.replay(replay)
            t.resize(18, 105)
            expect("Answers the replay gave", t.heard(), "")
            expect("Region after the replay at 6 rows and a resize to 18", t.region(), 1 to 18)
            expectScrollsAtTheBottom("The replay of vim at 6 rows, shown at 18", t, 18)
        }
        Term(18, 105).use { t ->
            t.replay(replay)
            expect("Region after the replay at the size it was captured at", t.region(), 1 to 18)
            expectScrollsAtTheBottom("The replay of vim at the size it was captured at", t, 18)
        }
        // The same bytes, live, are answered: the check above is not vacuous.
        Term(30, 105).use { t ->
            t.write(replay)
            check(t.heard().isNotEmpty()) { "The library answered none of the queries in the session written live" }
            expect("Region after the session written live", t.region(), 1 to 30)
        }
    }

    // ---------------------------------------------------------------- the scan, held to the library

    private fun fragments(): List<(Random) -> String> = listOf(
        // DECSTBM, with arguments on either side of the 8 rows of the screen, with one argument and with none.
        { r -> ESC + "[" + r.nextInt(0, 12) + ";" + r.nextInt(0, 12) + "r" },
        { r -> ESC + "[" + r.nextInt(0, 12) + "r" },
        { r -> ESC + "[;" + r.nextInt(0, 12) + "r" },
        { r -> ESC + "[" + r.nextInt(1, 6) + ";" + r.nextInt(5, 9) + "r" },
        { _ -> ESC + "[r" },
        { _ -> ESC + "[;r" },
        { _ -> ESC + "[1;8r" },
        { _ -> ESC + "[1;9r" },
        { _ -> ESC + "[0;0r" },
        // The alternate screen, its relatives, and the origin mode.
        { _ -> ENTER },
        { _ -> LEAVE },
        { _ -> ENTER },
        { _ -> LEAVE },
        { _ -> ESC + "[?1047h" },
        { _ -> ESC + "[?1047l" },
        { _ -> ESC + "[?1048h" },
        { _ -> ESC + "[?1048l" },
        { _ -> ESC + "[?47h" },
        { _ -> ESC + "[?47l" },
        { _ -> ESC + "[?25;1049h" },
        { _ -> ESC + "[?1049;25l" },
        { _ -> ESC + "[?1049;1047h" },
        { _ -> ESC + "[?6h" },
        { _ -> ESC + "[?6l" },
        { _ -> ESC + "[?6;1049h" },
        { _ -> ESC + "[?h" },
        { _ -> ESC + "[?;1049h" },
        { _ -> ESC + "[?1;2;3;4;5;6;7;8;9;10;11;12;13;14;15l" },
        // Resets.
        { _ -> ESC + "[!p" },
        { _ -> ESC + "c" },
        { _ -> ESC + "[!q" },
        // Sequences that look like a region and are not one.
        { _ -> ESC + "[>1;3r" },
        { _ -> ESC + "[=1;3r" },
        { _ -> ESC + "[?1;3r" },
        { _ -> ESC + "[1;3 r" },
        { _ -> ESC + "[\$1;3r" },
        { _ -> ESC + "[1;3\$r" },
        { _ -> ESC + "[1;3%r" },
        { _ -> ESC + "[<1;3r" },
        // Text and movement.
        { _ -> "text\r\n" },
        { _ -> "\n\n\n\n" },
        { _ -> "x".repeat(20) },
        { _ -> "Ã©" },
        { r -> ESC + "[" + r.nextInt(1, 9) + ";" + r.nextInt(1, 21) + "H" },
        { _ -> ESC + "M" },
        // Strings, closed and not, with a region inside.
        { _ -> ESC + "]0;title" + BEL },
        { _ -> ESC + "]2;a" + ESC + "\\" },
        { _ -> ESC + "]0;a" + ESC + "[1;3r" + BEL },
        { _ -> ESC + "]0;a" + ESC + "[1;3r" },
        { _ -> ESC + "]0;a" + CAN + ESC + "[2;4r" },
        { _ -> ESC + "]0;a" + BEL + ESC + "[2;4r" },
        { _ -> ESC + "]8;;https://x.test/" + BEL + "link" + ESC + "]8;;" + BEL },
        { _ -> ESC + "P1;2q" + ESC + "[1;3r" + ESC + "\\" },
        { _ -> ESC + "Pabc" + BEL + ESC + "[2;5r" },
        // The command part of a DCS, before the byte that ends it, is not yet a string: an ESC leaves it, a BEL is a control.
        { _ -> ESC + "P1" + ESC + "[2;4r" },
        { _ -> ESC + "P1" + BEL + ";2q" + ESC + "[2;4r" },
        { _ -> ESC + "P1" + BEL + ";2q" + ESC + "\\" + ESC + "[2;4r" },
        { _ -> ESC + "P\$qm" + ESC + "\\" },
        { _ -> ESC + "_a" + ESC + "\\" },
        { _ -> ESC + "_a" + ESC + "[1;3r" + ESC + "\\" },
        { _ -> ESC + "^a" + BEL },
        { _ -> ESC + "Xa" + ESC + "\\" },
        { _ -> ESC + "X" + ESC + "[1;3r" + BEL },
        // Sequences cancelled, interrupted, or carrying odd bytes.
        { _ -> ESC + "[1;" + CAN + "5r" },
        { _ -> ESC + "[1" + SUB + ";5r" },
        { _ -> ESC + "[1;" + "\r" + "4r" },
        { _ -> ESC + "[1;\u0000" + "4r" },
        { _ -> ESC + "[1;\u007f" + "4r" },
        { _ -> ESC + "[1;3" + ESC + "[2;5r" },
        { _ -> ESC + "[1;3" + ESC + "M" + "4r" },
        { _ -> ESC + "[1;3" + "Ã©" + "r" },
        { _ -> ESC + "[99999999999;3r" },
        { _ -> ESC + "[4294967297;5r" },
        { _ -> ESC + "[2147483649;4r" },
        { _ -> ESC + "[1:2;3r" },
        { _ -> ESC + "[1;2;3;4;5;6;7;8;9;10;11;12;13;14;15r" },
        { _ -> ESC },
        { _ -> ESC + ESC },
        { _ -> ESC + "\\" },
        { _ -> ESC + " F" },
        { _ -> ESC + "%G" },
        { _ -> ESC + "#8" },
        { _ -> CAN },
        { _ -> SUB },
        { _ -> "\u0000" },
        { _ -> "\u007f" },
    )

    /**
     * Random streams of [fragments], in chunks of random sizes, through the library the way the emulator writes them. At every place the scan
     * says it is between sequences (some of them), the library is asked what its region is, whether the alternate screen is up, whether the
     * origin mode is on and where the cursor is, and the scan must have the same; after each region the fix wrote, the cursor must be where it
     * was before (out of the origin mode, where a cursor outside the region cannot be). A scan that thought itself between sequences when the
     * library was not would send the probe into the middle of one, and the next answer would not be the scan's. Each stream ends with the screen
     * made another height: the region the library has bounded to it must be the scan's, and the fix must give the whole screen back.
     */
    fun verifyRegionScan() {
        val pool = fragments()
        var probes = 0
        var fixes = 0
        var resizes = 0
        var resizedWithRegion = 0
        for (seed in 1..6) {
            val random = Random(seed * 31)
            repeat(250) { round ->
                val parts = (1..random.nextInt(1, 16)).map { pool[random.nextInt(pool.size)](random) }
                // CAN ends anything left open, so the last probe is between sequences.
                val stream = (parts.joinToString("") + CAN).toByteArray(Charsets.ISO_8859_1)
                val text = parts.joinToString("").replace(ESC, "<ESC>")
                Raw(8, 20).use { raw ->
                    fun probe(at: Int) {
                        val where = "seed " + seed + " round " + round + " after " + at + " of " + stream.size + " bytes of " + text
                        val region = parseRegion(raw.ask(ASK_REGION))
                        expect("Region the library holds, $where", region, raw.scanner.heldTop to raw.scanner.heldBottom)
                        expect("Alternate screen, $where", raw.alternate, raw.scanner.onAlternate)
                        val origin = raw.ask(ESC + "[?6\$p")
                        expect("Origin mode, $where", origin, ESC + "[?6;" + (if (raw.scanner.origin) 1 else 2) + "\$y")
                        // In the origin mode the library puts the cursor back inside the region after it has said where it went (see the fix below).
                        if (!raw.scanner.origin) expect("Cursor the emulator has, $where", raw.row to raw.column, raw.libraryCursor())
                        probes++
                    }
                    var at = 0
                    while (at < stream.size) {
                        val end = minOf(stream.size, at + random.nextInt(1, 9))
                        raw.feed(stream, at, end) { tracked, before, after, origin ->
                            fixes++
                            val where = "seed " + seed + " round " + round + " after " + end + " of " + stream.size + " bytes of " + text
                            // In the origin mode the library can move the cursor into the region after a sequence without saying so (a restore of a cursor outside it).
                            if (!origin) {
                                expect("Cursor the emulator has when the fix is made, $where", tracked, before)
                                expect("Cursor after the fix, $where", after, before)
                            }
                        }
                        at = end
                        if (raw.scanner.idle() && (at == stream.size || random.nextInt(3) == 0)) probe(at)
                    }
                    // A size change at the end: the library bounds its own region by the new height and the scan must have done the same, then the fix
                    // gives the whole screen back, on whichever screen is up.
                    val newRows = random.nextInt(3, 13)
                    val where = "seed " + seed + " round " + round + " resized to " + newRows + " rows after " + text
                    if (raw.scanner.heldTop != 1 || raw.scanner.heldBottom != 8) resizedWithRegion++
                    raw.resize(newRows) {
                        expect("Region the library holds after the resize, $where", parseRegion(raw.ask(ASK_REGION)), raw.scanner.heldTop to raw.scanner.heldBottom)
                    }
                    expect("Region after the fix of the resize, $where", parseRegion(raw.ask(ASK_REGION)), 1 to newRows)
                    expect("Alternate screen after the resize, $where", raw.alternate, raw.scanner.onAlternate)
                    resizes++
                }
            }
        }
        check(probes > 2000 && fixes > 100 && resizes == 1500 && resizedWithRegion > 200) {
            "The streams did too little: $probes probes, $fixes fixes, $resizes resizes, $resizedWithRegion of them with a region"
        }
    }

    // ---------------------------------------------------------------- the emulator, held to xterm.js's rule

    /**
     * Random sessions through the emulator (some of them a replay, every write cut at random), against the rule the web app's terminal follows
     * and a model written here from the rule alone: each screen has a region of its own, the alternate one starts whole every time it is entered,
     * and a real size change gives both back none. After every step the library is asked its region, and where the script knows the cursor, that.
     */
    fun verifyRegionOps() {
        var steps = 0
        for (seed in 1..60) {
            val random = Random(seed * 17)
            var rows = random.nextInt(5, 13)
            var cols = 20
            Term(rows, cols).use { t ->
                var primary: Pair<Int, Int>? = null
                var alternate: Pair<Int, Int>? = null
                var inAlternate = false
                var kind = 1049
                var saved = 0 to 0
                var at: Pair<Int, Int>? = 0 to 0
                var serial = 0

                fun deliver(text: String) {
                    val bytes = text.toByteArray(Charsets.ISO_8859_1)
                    if (random.nextInt(4) == 0) {
                        t.replay(bytes)
                        return
                    }
                    var from = 0
                    while (from < bytes.size) {
                        val to = minOf(bytes.size, from + random.nextInt(1, 7))
                        t.write(bytes.copyOfRange(from, to))
                        from = to
                    }
                }

                fun current() = if (inAlternate) alternate else primary
                fun setCurrent(region: Pair<Int, Int>?) { if (inAlternate) alternate = region else primary = region }

                fun verify(step: String) {
                    val expected = current() ?: (1 to rows)
                    val where = "seed " + seed + " step " + (steps) + " (" + step + ")"
                    expect("Region of the screen shown, " + where + ", on the " + (if (inAlternate) "alternate" else "shell's") + " screen at " + rows + "x" + cols, t.region(), expected)
                    at?.let { expect("Cursor, $where", t.cursor(), it) }
                    steps++
                }

                repeat(random.nextInt(8, 26)) {
                    when (random.nextInt(11)) {
                        0, 1, 2 -> {
                            val top = random.nextInt(1, rows + 1)
                            val bottom = random.nextInt(top, rows + 1)
                            deliver(ESC + "[" + top + ";" + bottom + "r")
                            setCurrent(if (top == 1 && bottom == rows) null else top to bottom)
                            at = 0 to 0
                            verify("region $top;$bottom")
                        }
                        3 -> {
                            deliver(ESC + "[r")
                            setCurrent(null)
                            at = 0 to 0
                            verify("no region")
                        }
                        4, 5 -> {
                            val position = random.nextInt(1, rows + 1) to random.nextInt(1, cols + 1)
                            if (!inAlternate) {
                                kind = if (random.nextBoolean()) 1049 else 1047
                                deliver(ESC + "[" + position.first + ";" + position.second + "H" + ESC + "[?" + kind + "h")
                                saved = position.first - 1 to position.second - 1
                                inAlternate = true
                                alternate = null
                                at = saved
                                verify("enter $kind")
                            } else {
                                deliver(ESC + "[?" + kind + "l")
                                inAlternate = false
                                // The library puts the saved cursor back and then keeps it on the screen, which may have lost columns since.
                                if (kind == 1049) at = minOf(saved.first, rows - 1) to minOf(saved.second, cols - 1)
                                verify("leave $kind")
                            }
                        }
                        6 -> {
                            val least = if (inAlternate && kind == 1049) saved.first + 1 else 3
                            val newRows = random.nextInt(maxOf(least, 3), 15)
                            val newCols = if (random.nextInt(3) == 0) random.nextInt(10, 31) else cols
                            if (newRows != rows || newCols != cols) {
                                t.resize(newRows, newCols)
                                rows = newRows
                                cols = newCols
                                primary = null
                                alternate = null
                                at = null
                                verify("resize")
                            }
                        }
                        7, 8 -> {
                            serial++
                            deliver("line " + serial + "\r\n" + "x".repeat(random.nextInt(0, 25)) + "\r\n")
                            at = null
                            verify("text")
                        }
                        9 -> {
                            val position = random.nextInt(1, rows + 1) to random.nextInt(1, cols + 1)
                            deliver(ESC + "[" + position.first + ";" + position.second + "H")
                            at = position.first - 1 to position.second - 1
                            verify("cursor")
                        }
                        else -> {
                            // The rows of the region are where a line feed on its last row scrolls.
                            val region = current() ?: (1 to rows)
                            label(t, rows)
                            deliver(ESC + "[" + region.second + ";1H\n")
                            val screen = t.screen()
                            val expectedScreen = (0 until rows).map { row ->
                                val line = row + 1
                                when {
                                    line < region.first || line > region.second -> "R$row"
                                    line < region.second -> "R" + (row + 1)
                                    else -> ""
                                }
                            }
                            expect("Rows after a line feed on the last row of the region " + region + ", seed " + seed, screen, expectedScreen)
                            at = region.second - 1 to 0
                            verify("line feed")
                        }
                    }
                }
            }
        }
        check(steps > 800) { "The sessions did too little: $steps steps" }
    }
}
