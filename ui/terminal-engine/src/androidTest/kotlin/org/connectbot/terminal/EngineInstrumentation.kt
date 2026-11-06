package org.connectbot.terminal

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.nio.ByteBuffer
import kotlin.random.Random

/** JNI smoke checks in an isolated test APK; no app activity or clipboard access. */
class EngineInstrumentation : Instrumentation() {
    /** `-e only region` runs the scroll region checks alone; `-e only memory` prints what the history costs in heap. */
    private var only: String? = null

    override fun onCreate(arguments: Bundle?) { only = arguments?.getString("only"); super.onCreate(arguments); start() }

    private fun verifyScrollRegions() {
        ResizeChecks.verifyRegionModel()
        RegionChecks.verifyScrollRegion(context)
        RegionChecks.verifyRegionScan()
        RegionChecks.verifyRegionOps()
    }

    override fun onStart() {
        if (only == "memory") {
            // Numbers, not a verdict: what the history costs in heap on the real library (see MemoryChecks).
            val text = runCatching { MemoryChecks.run() }.getOrElse { it.stackTraceToString() }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", text) })
            return
        }
        val result = runCatching {
            if (only == "region") verifyScrollRegions()
            else { verifyEngine(); verifyScrollbackWraps(); verifyWrapFlagsAcrossWrites(); verifyLinks(); verifyLinksAcrossScrolls(); verifyStaleLinks(); ResizeChecks.verifyResizeModel(); ResizeChecks.verifyResize(); verifyWriteCuts(); verifyScrollRegions(); verifyReplayCursor(); verifyRenderer() }
        }
        finish(if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", if (result.isSuccess && only != null) "PASS: only the " + only + " checks ran\n" else if (result.isSuccess) "PASS: pinned JNI, scrollback, wrapped lines in it, wrap flags across writes, links (OSC 8, plain, wrapped, in the history, on their own rows while the screen scrolls, none left over text that is not theirs: 22 operations in 3 ways of writing, a replay, a resize), a prompt beside them, a resize (the model of the library's resize held to its traces in 52 scripted scenarios, the wraps and links of the rows through a shrink and a grow, the alternate screen), where writes are cut for them, the scroll region (the model of the library's held to its traces in 60 scripted scenarios, the shell's region and the alternate screen's through a vim session captured on a device, a resize and a replay, the scan's region held to the library's own answer over random streams and a resize after each, the emulator's held to xterm.js's rule over random sessions), the cursor after a replay, text/cursor, reset, close, late work, truecolor/inverse contrast, cursor colours\n"
                else result.exceptionOrNull()!!.stackTraceToString())
        })
    }

    private fun verifyEngine() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 3, initialCols = 12, maxScrollbackLines = 3)
        try {
            emulator.writeInput((0..8).joinToString("") { "line$it\r\n" }.toByteArray())
            val buffer = emulator.getBufferSnapshot()
            check(buffer.screenStartRow == 3)
            check(buffer.lines.size == 6)
            check(buffer.cursorRow == buffer.screenStartRow + emulator.getCursorPosition().first)
            check(emulator.getScreenText().contains("line8"))
            check(emulator.getBufferLines() == buffer.lines)
            check(emulator.getTextBetweenRows(0, 6).contains("line8"))
            val before = emulator.getBufferSnapshot()
            emulator.getScreenText(); emulator.getBufferLines(); emulator.getCursorPosition()
            check(emulator.getBufferSnapshot() == before)
            emulator.writeInput("\u001b[?1049hALTERNATE".toByteArray())
            check(emulator.getBufferSnapshot().alternateScreen)
            check(emulator.getBufferSnapshot().screenStartRow == 0)
            check(!emulator.getScreenText().contains("line8"))
            val palette = IntArray(16) { 0xFF123456.toInt() }
            emulator.applyColorScheme(palette, 0xFFEEEEEE.toInt(), 0xFF112233.toInt())
            // Reset must leave an unfinished OSC parser and alternate screen behind as well.
            emulator.writeInput("\u001b]0;unfinished".toByteArray())
            emulator.reset()
            check(emulator.getScreenText().isEmpty()) { "Reset screen: ${emulator.getScreenText().toList()}" }
            check(emulator.getBufferSnapshot().screenStartRow == 0)
            check(!emulator.getBufferSnapshot().alternateScreen)
            check(emulator.getCursorPosition() == (0 to 0))
            emulator.writeInput("\u001b[31mnew".toByteArray())
            check(emulator.getScreenText() == "new")
            check((emulator as TerminalEmulatorImpl).snapshot.value.lines[0].cells.foreground(0).toArgb() == palette[1])
            emulator.close(); emulator.close()
            check(emulator.isClosed)
            emulator.writeInput("late".toByteArray())
            emulator.writeReplay("late".toByteArray())
            emulator.pasteText("late"); emulator.dispatchCharacter(0, 'x'.code); emulator.resize(4, 20)
            emulator.reset()
            check(emulator.getScreenText().isEmpty()) { "Closed screen: ${emulator.getScreenText().toList()}" }
        } finally { emulator.close() }

        TerminalEmulatorFactory.create(initialRows = 2, initialCols = 12).use { large ->
            large.writeInput((0..1100).joinToString("") { "$it\r\n" }.toByteArray())
            check(large.getBufferSnapshot().screenStartRow > 1000)
        }
        TerminalEmulatorFactory.create(maxScrollbackLines = 0).use { empty ->
            empty.writeInput("x\r\n".repeat(40).toByteArray())
            check(empty.getBufferSnapshot().screenStartRow == 0)
        }
        // Never-used instances close without creating JNI; validate limits before allocating it.
        TerminalEmulatorFactory.create().use { }
        check(runCatching { TerminalEmulatorFactory.create(maxScrollbackLines = -1) }.isFailure)
    }

    /**
     * The shipped libvterm sends the soft-wrap flag of the row after the one it pushes into the history (`ScrollbackWrap`).
     * `ScrollbackWrapTest` feeds the emulator what a native build of that libvterm sent; this runs the same output through
     * the real JNI, so the model cannot drift from the library unnoticed: every wrapped line that scrolled off a 26 x 80
     * screen must still read as one line, and the lines either side of it as other lines.
     */
    private fun verifyScrollbackWraps() {
        val lines = listOf(
            "$ clear; sed -n 4,6p term/term-links.txt; seq 1 30",
            "local: http://10.0.2.2:8937/?link-test=1",
            "long : https://example.com/very/long/path/" + "segment-".repeat(20) + "end?query=" + "abcdef".repeat(12) + "&x=1",
            "oauth: https://auth.example.com/authorize?client_id=abc&redirect_uri=http%3A%2F%2Flocalhost%3A54321%2Fcallback&state=xyz",
        ) + (1..30).map { it.toString() }
        TerminalEmulatorFactory.create(initialRows = 26, initialCols = 80).use { emulator ->
            emulator.writeInput(("\u001b[H\u001b[2J" + lines.joinToString("\r\n") + "\r\n$ ").toByteArray())
            val buffer = emulator.getBufferSnapshot()
            // The prompt, 1 + 1 + 4 + 2 rows of the first four lines and five numbers: 13 rows scrolled off.
            check(buffer.screenStartRow == 13) { "Scrolled off: ${buffer.screenStartRow}" }
            val expected = (lines.take(4) + (1..5).map { it.toString() }).joinToString("\n")
            val actual = buffer.getTextBetweenRows(0, buffer.screenStartRow)
            check(actual == expected) { "History reads\n$actual\nnot\n$expected" }
        }
    }

    /**
     * libvterm sets a screen row's soft-wrap flag when text reaches the row below it, and the JNI hands the flag over only with
     * a read of a cell of that row. A line written in two chunks, the first ending at the right margin (a prompt that wraps while
     * it is typed), damages only the row below, so the row above kept "not wrapped" until `refreshRowsAbove` made the emulator read
     * it too. The second case is the same at the bottom of the screen, where the wrap scrolls.
     */
    private fun verifyWrapFlagsAcrossWrites() {
        TerminalEmulatorFactory.create(initialRows = 26, initialCols = 80).use { emulator ->
            emulator.writeInput(("\u001b[H\u001b[2J" + "a".repeat(80)).toByteArray())
            check(!emulator.getBufferLines()[0].isWrapped) { "Row 0 is wrapped before anything was written under it" }
            emulator.writeInput("bbb".toByteArray())
            check(emulator.getBufferLines()[0].isWrapped) { "Row 0 does not know its line went on in a later write" }
            emulator.writeInput(("\u001b[H\u001b[2J" + "x\r\n".repeat(25) + "c".repeat(80)).toByteArray())
            emulator.writeInput("ddd".toByteArray())
            val buffer = emulator.getBufferSnapshot()
            val screen = buffer.lines.subList(buffer.screenStartRow, buffer.lines.size)
            check(buffer.screenStartRow == 1) { "The bottom row did not scroll: ${buffer.screenStartRow}" }
            check(screen[24].isWrapped && !screen[25].isWrapped) { "Wrap flags after a scroll: ${screen.takeLast(2).map { it.isWrapped }}" }
            check(buffer.getTextBetweenRows(buffer.screenStartRow + 24, buffer.lines.size) == "c".repeat(80) + "ddd") { "Joined: ${emulator.getScreenText().takeLast(90)}" }
        }
    }

    /**
     * The links of a real emulator's screen through the JNI: an OSC 8 link's segment (its columns, its address), a plain URL, one
     * that wrapped onto the next row, and the same links once their rows scrolled into the history, where the wrap flag is the one
     * the engine rebuilds (`ScrollbackWrap`). `TerminalWebLinksTest` runs the rules over rows built by hand; these are the engine's own.
     */
    private fun verifyLinks() {
        TerminalEmulatorFactory.create(initialRows = 6, initialCols = 40).use { emulator ->
            val impl = emulator as TerminalEmulatorImpl
            val wrapped = "https://wrap.example.com/" + "x".repeat(30)
            emulator.writeInput(
                ("\u001b[H\u001b[2J" + "see \u001b]8;;https://osc.example.com/a\u0007the docs\u001b]8;;\u0007 http://p.test/b ok\r\n" + wrapped + "\r\nend").toByteArray(),
            )
            fun checkLinks(state: TerminalScreenState, where: String) {
                val osc = state.linkAt(0, 6)
                check(osc == TerminalLink("https://osc.example.com/a", 5, 1, 12, 1, osc8 = true)) { "OSC 8 link $where: $osc" }
                check(state.linkAt(0, 3) == null && state.linkAt(0, 12) == null) { "Cells beside the OSC 8 link $where" }
                val plain = state.linkAt(0, 15)
                check(plain == TerminalLink("http://p.test/b", 14, 1, 28, 1)) { "Plain link $where: $plain" }
                val wrap = state.linkAt(1, 5)
                check(wrap == TerminalLink(wrapped, 1, 2, 15, 3)) { "Wrapped link $where: $wrap" }
                check(state.linkAt(2, 14) == wrap && state.linkAt(2, 15) == null) { "Second row of the wrapped link $where" }
                check(state.linkAt(3, 1) == null) { "Plain text $where" }
            }
            // Reading the buffer applies what is still queued: the snapshot is built from it.
            emulator.getBufferSnapshot()
            checkLinks(TerminalScreenState(impl.snapshot.value), "on the screen")
            // Eleven rows in all on a six-row screen: the first five are history, the OSC 8 row at the top of it.
            emulator.writeInput("\r\n1\r\n2\r\n3\r\n4\r\n5\r\n6\r\n7".toByteArray())
            emulator.getBufferSnapshot()
            val state = TerminalScreenState(impl.snapshot.value)
            check(state.snapshot.scrollback.size >= 4) { "Scrolled off: ${state.snapshot.scrollback.size}" }
            state.scrollToTop()
            checkLinks(state, "in the history")
        }
    }

    /**
     * A link stays on its own row while the screen scrolls. libvterm merges the scrolls of a write: it sends each row it pushes into
     * the history, and each OSC with the cursor's row, as they happen, but the one `moverect` only when the write is flushed, so
     * the engine put the top row's links on every row a write pushed and a link written after a scroll on a row about to move.
     * `SegmentScrollTest` feeds the emulator that order; these are the real library's writes: one chunk, one write per line before
     * the emulator has caught up and after it has, a chunk of plain lines over links already on the screen, and a chunk that
     * scrolls more rows than the screen has followed by a small one, the last also through the direct-buffer overload. In every row,
     * history first, the links are the row's own.
     */
    private fun verifyLinksAcrossScrolls() {
        fun link(n: Int) = "\u001b]8;;https://l$n.test/\u0007link$n\u001b]8;;\u0007"

        fun rows(emulator: TerminalEmulator): List<Pair<String, List<String?>>> {
            emulator.getBufferSnapshot()
            val snapshot = (emulator as TerminalEmulatorImpl).snapshot.value
            return (snapshot.scrollback + snapshot.lines).map { line -> line.text.replace('\u0000', ' ').trimEnd() to line.semanticSegments.map { it.metadata } }
        }

        fun check(emulator: TerminalEmulator, links: Int, where: String) {
            val rows = rows(emulator)
            for ((index, row) in rows.withIndex()) {
                val expected = if (row.first.startsWith("link")) listOf("https://l${row.first.removePrefix("link")}.test/") else emptyList()
                check(row.second == expected) { "Links of row $index '${row.first}' $where: ${row.second}, not $expected; rows $rows" }
            }
            check(rows.count { it.first.startsWith("link") } == links) { "Link rows $where: ${rows.map { it.first }}" }
        }

        val clear = "\u001b[H\u001b[2J"
        TerminalEmulatorFactory.create(initialRows = 6, initialCols = 40).use { emulator ->
            emulator.writeInput((clear + (0..7).joinToString("") { link(it) + "\r\n" } + "tail").toByteArray())
            check(emulator, 8, "after one chunk of eight link lines on six rows")
        }
        TerminalEmulatorFactory.create(initialRows = 6, initialCols = 40).use { emulator ->
            emulator.writeInput(clear.toByteArray())
            for (n in 0..7) emulator.writeInput((link(n) + "\r\n").toByteArray())
            emulator.writeInput("tail".toByteArray())
            check(emulator, 8, "after one write per line, the emulator not caught up in between")
        }
        TerminalEmulatorFactory.create(initialRows = 6, initialCols = 40).use { emulator ->
            emulator.writeInput(clear.toByteArray())
            for (n in 0..7) { emulator.writeInput((link(n) + "\r\n").toByteArray()); emulator.getBufferSnapshot() }
            emulator.writeInput("tail".toByteArray())
            check(emulator, 8, "after one write per line, the emulator caught up after each")
        }
        TerminalEmulatorFactory.create(initialRows = 6, initialCols = 40).use { emulator ->
            emulator.writeInput((clear + link(0) + "\r\nplain1\r\n" + link(2) + "\r\nplain3\r\nplain4").toByteArray())
            emulator.getBufferSnapshot()
            emulator.writeInput("\r\nA\r\nB\r\nC\r\nD\r\nE".toByteArray())
            check(emulator, 2, "after plain lines scrolled the links on the screen into the history")
        }
        TerminalEmulatorFactory.create(initialRows = 6, initialCols = 40).use { emulator ->
            emulator.writeInput((clear + (0..19).joinToString("") { link(it) + "\r\n" } + "tail").toByteArray())
            check(emulator, 20, "after a chunk that scrolled more rows than the screen has")
            emulator.writeInput(("\r\n" + (20..22).joinToString("") { link(it) + "\r\n" } + "tail").toByteArray())
            check(emulator, 23, "after a small write following it")
        }
        TerminalEmulatorFactory.create(initialRows = 6, initialCols = 40).use { emulator ->
            // The same two writes through the direct-buffer overload, whose end has to be told as well: the first leaves pushes that
            // no moverect accounted for, and the count of them must not be carried into the second.
            fun direct(text: String) = text.toByteArray().let { bytes -> ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); flip() } to bytes.size }
            val (first, firstLength) = direct(clear + (0..19).joinToString("") { link(it) + "\r\n" } + "tail")
            emulator.writeInput(first, firstLength)
            check(emulator, 20, "after a chunk through a direct buffer that scrolled more rows than the screen has")
            val (second, secondLength) = direct("\r\n" + (20..22).joinToString("") { link(it) + "\r\n" } + "tail")
            emulator.writeInput(second, secondLength)
            check(emulator, 23, "after a small write through a direct buffer following it")
        }
        TerminalEmulatorFactory.create(initialRows = 6, initialCols = 40).use { emulator ->
            emulator.writeInput((clear + (0..4).joinToString("") { link(it) + "\r\n" } + "tail").toByteArray())
            check(emulator, 5, "before the screen shrank")
            // The soft keyboard comes up and goes away again: the rows above the cursor go to the history and come back from it,
            // and every link goes with its row there and back.
            emulator.resize(3, 40)
            check(emulator, 5, "after the screen shrank")
            emulator.resize(6, 40)
            check(emulator, 5, "after the screen shrank and grew back")
        }
    }

    /**
     * An OSC 8 link goes away with the cells it was written over, whichever way the writes that make and replace it arrive: one
     * write (a replay is one), writes the emulator has not caught up with, writes it has. The 22 operations of `StaleLinksTest`
     * on the real library: no link is left over text that is not its own, with the emulator caught up each operation leaves the
     * links a live shell always had (the numbers are the same as there), and the other two ways never keep a link that one lost.
     */
    private fun verifyStaleLinks() {
        val esc = "\u001b"
        fun link(n: Int) = "$esc]8;;https://l$n.test/\u0007link$n$esc]8;;\u0007"
        // Name, bytes, and the links left when the emulator has caught up between the writes.
        val operations = listOf(
            Triple("ED 0 from home", "$esc[H$esc[J", 0),
            Triple("ED 2", "$esc[2J", 0),
            Triple("ED 1 at row 4", "$esc[4;3H$esc[1J", 2),
            Triple("ED 2 and ED 3", "$esc[H$esc[2J$esc[3J", 0),
            Triple("EL 0, row 2 from column 0", "$esc[2;1H$esc[K", 5),
            Triple("EL 1, row 2 to column 3", "$esc[2;3H$esc[1K", 5),
            Triple("EL 2, row 2", "$esc[2;1H$esc[2K", 5),
            Triple("ECH 3, row 2", "$esc[2;1H$esc[3X", 5),
            Triple("DCH 2, row 2", "$esc[2;1H$esc[2P", 5),
            Triple("ICH 2, row 2", "$esc[2;1H$esc[2@", 5),
            Triple("IL, row 2", "$esc[2;1H$esc[L", 6),
            Triple("DL, row 2", "$esc[2;1H$esc[M", 4),
            Triple("scroll up in the region 2-5", "$esc[2;5r$esc[5;1H\n$esc[r", 4),
            Triple("scroll down in the region 2-5", "$esc[2;5r$esc[2;1H${esc}M$esc[r", 4),
            Triple("scroll up in the region 1-4", "$esc[1;4r$esc[4;1H\n$esc[r", 1),
            Triple("plain text over row 2", "$esc[2;1HXYZ", 5),
            Triple("plain text over row 2, then EL", "$esc[2;1HXYZ$esc[K", 5),
            Triple("RIS", "${esc}c", 0),
            Triple("alternate screen, in and out", "$esc[?1049hALT$esc[?1049l", 6),
            Triple("SU 2", "$esc[2S", 6),
            Triple("SD 2", "$esc[2T", 5),
            Triple("CR and text over the last row", "\r____", 6),
        )
        val links = (1..6).joinToString("") { link(it) + "\r\n" }
        val setup = links + "tail"

        fun rows(emulator: TerminalEmulator): List<Pair<String, List<String>>> {
            emulator.getBufferSnapshot()
            val snapshot = (emulator as TerminalEmulatorImpl).snapshot.value
            return (snapshot.scrollback + snapshot.lines).map { line ->
                line.text.replace('\u0000', ' ').trimEnd() to line.semanticSegments.map { "[${it.startCol},${it.endCol}) ${it.metadata} '${line.cells.text(it.startCol, it.endCol)}'" }
            }
        }

        // A link whose text is not the text of its address, which no write made.
        fun stale(rows: List<Pair<String, List<String>>>) = rows.flatMap { it.second }.filter { segment ->
            !segment.endsWith("'link${segment.substringAfter("https://l").substringBefore(".test/")}'")
        }

        // 0: one write. 1: separate writes, the emulator's own snapshot worker free to catch up or not. 2: caught up after each.
        fun outcome(bytes: String, before: String, way: Int): List<Pair<String, List<String>>> =
            TerminalEmulatorFactory.create(initialRows = 8, initialCols = 40).use { emulator ->
                when (way) {
                    0 -> emulator.writeInput((before + bytes + "after").toByteArray())
                    1 -> { emulator.writeInput(before.toByteArray()); emulator.writeInput(bytes.toByteArray()); emulator.writeInput("after".toByteArray()) }
                    else -> {
                        emulator.writeInput(before.toByteArray()); emulator.getBufferSnapshot()
                        emulator.writeInput(bytes.toByteArray()); emulator.getBufferSnapshot()
                        emulator.writeInput("after".toByteArray())
                    }
                }
                rows(emulator)
            }

        for ((name, bytes, left) in operations) {
            val reference = outcome(bytes, setup, 2)
            check(stale(reference).isEmpty()) { "$name, caught up: ${stale(reference)}" }
            check(reference.sumOf { it.second.size } == left) { "$name, caught up: ${reference.sumOf { it.second.size }} links, not $left: ${reference.filter { it.second.isNotEmpty() }}" }
            val kept = reference.flatMap { it.second }.toSet()
            for (way in 0..1) {
                val actual = outcome(bytes, setup, way)
                check(stale(actual).isEmpty()) { "$name, way $way: a link over text that is not its own: ${stale(actual)}" }
                check(actual.flatMap { it.second }.all { it in kept }) { "$name, way $way keeps a link that the caught-up one lost: ${actual.filter { it.second.isNotEmpty() }}" }
            }
            // With nothing after the links in the same flush, the changed cells are the same rectangle either way.
            val flat = outcome(bytes, links, 2)
            check(outcome(bytes, links, 0) == flat) { "$name: one write ${outcome(bytes, links, 0)}, caught up $flat" }
        }

        // A replay of links, a clear and plain lines: ONE write, which is how the server sends a shell's buffer back.
        val clear = "$esc[H$esc[2J$esc[3J"
        val before = (1..40).joinToString("") { link(it) + "\r\n" } + "\$ clear; bash plain.sh\r\n"
        val plain = (1..40).joinToString("") { "https://site$it.example.com/path$it\r\n" } + "\$ "
        TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80).use { emulator ->
            emulator.writeReplay((before + clear + plain).toByteArray())
            val replayed = rows(emulator)
            check(replayed.sumOf { it.second.size } == 0) { "A replay left links on plain lines: ${replayed.filter { it.second.isNotEmpty() }}" }
        }
        TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80).use { emulator ->
            emulator.writeInput((before + clear + plain).toByteArray())
            check(rows(emulator).sumOf { it.second.size } == 0) { "One write left links on plain lines" }
        }
        TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80).use { emulator ->
            emulator.writeInput(before.toByteArray()); emulator.writeInput(clear.toByteArray()); emulator.writeInput(plain.toByteArray())
            check(rows(emulator).sumOf { it.second.size } == 0) { "Three writes left links on plain lines" }
        }

        // A history cut at its limit holds the links of the rows it kept, and only theirs.
        TerminalEmulatorFactory.create(initialRows = 8, initialCols = 40, maxScrollbackLines = 5).use { emulator ->
            emulator.writeReplay(((1..40).joinToString("") { link(it) + "\r\n" } + "\$ ").toByteArray())
            val cut = rows(emulator)
            check(stale(cut).isEmpty() && cut.sumOf { it.second.size } == 12 && cut.take(5).map { it.first } == (29..33).map { "link$it" }) { "History cut at its limit: $cut" }
        }

        // A listing that scrolled, a clear and the same names plain, in separate writes: the screen is the same, so only the redraw ends the links.
        val names = (1..30).map { "file$it.txt" }
        val listed = names.joinToString("") { "$esc]8;;file:///home/u/$it\u0007$it$esc]8;;\u0007\r\n" } + "\$ "
        val replotted = "$esc[H$esc[2J" + names.drop(7).joinToString("\r\n") + "\r\n\$ "
        for (caughtUp in listOf(false, true)) {
            TerminalEmulatorFactory.create(initialRows = 24, initialCols = 40).use { emulator ->
                emulator.writeInput(listed.toByteArray())
                if (caughtUp) emulator.getBufferSnapshot()
                emulator.writeInput(replotted.toByteArray())
                val screen = rows(emulator).takeLast(24)
                check(screen.map { it.first } == names.drop(7) + "\$" && screen.sumOf { it.second.size } == 0) {
                    "A scrolled listing, a clear, the same names plain (caught up: $caughtUp): ${screen.filter { it.second.isNotEmpty() }.take(3)}"
                }
            }
        }

        // Wide characters: a link over them goes with them, and keeps its own text while they stay.
        fun wide(n: Int) = "$esc]8;;https://w$n.test/\u0007リンク$n$esc]8;;\u0007"
        TerminalEmulatorFactory.create(initialRows = 8, initialCols = 40).use { emulator ->
            emulator.writeReplay(((1..3).joinToString("") { wide(it) + "\r\n" } + clear + "ふつうの文字\r\n").toByteArray())
            check(rows(emulator).sumOf { it.second.size } == 0) { "Wide links over wide plain text: ${rows(emulator)}" }
        }
        TerminalEmulatorFactory.create(initialRows = 8, initialCols = 40).use { emulator ->
            emulator.writeReplay(((1..3).joinToString("") { wide(it) + "\r\n" } + "after").toByteArray())
            val shown = rows(emulator).flatMap { it.second }
            check(shown == (1..3).map { "[0,${6 + 1}) https://w$it.test/ 'リンク$it'" }) { "Wide links left alone: $shown" }
        }

        // A resize: the library reports the whole screen damaged for it, and that is no redraw. A resize in columns moves no cell, so
        // every link stays; one in rows moves the rows, and a link must not stay on text that is not its own.
        TerminalEmulatorFactory.create(initialRows = 8, initialCols = 40).use { emulator ->
            emulator.writeInput(setup.toByteArray())
            val before = rows(emulator)
            (emulator as TerminalEmulatorImpl).resize(8, 50, 500, 400)
            val after = rows(emulator)
            check(before.map { it.second } == after.map { it.second } && after.sumOf { it.second.size } == 6) { "A resize in columns: $before, then $after" }
        }
        TerminalEmulatorFactory.create(initialRows = 8, initialCols = 40).use { emulator ->
            emulator.writeInput(setup.toByteArray())
            (emulator as TerminalEmulatorImpl).resize(6, 40, 400, 300)
            check(stale(rows(emulator)).isEmpty()) { "A resize in rows left a link on text that is not its own: ${stale(rows(emulator))}" }
        }

        // A prompt (OSC 133) is not a link and keeps the rule it had: damage on its columns ends it. `FakeVterm` models this one for the JVM tests.
        fun prompts(after: String): List<String> = TerminalEmulatorFactory.create(initialRows = 8, initialCols = 40).use { emulator ->
            emulator.writeInput("$esc]133;A\u0007\$ $esc]133;B\u0007ls".toByteArray())
            emulator.getBufferSnapshot()
            emulator.writeInput(after.toByteArray())
            rows(emulator).first().second.filter { it.startsWith("[0,2)") }
        }
        check(prompts(" -la") == listOf("[0,2) null '\$ '")) { "A prompt with text typed after it: ${prompts(" -la")}" }
        check(prompts("\rXY").isEmpty()) { "A prompt written over: ${prompts("\rXY")}" }
    }

    /**
     * After a replay the cursor is where the text ends, shown: a line typed and not entered, which wraps over three rows, and one that
     * ends exactly at the right edge (libvterm leaves the cursor on the last column, as the web's does, until the next character wraps).
     */
    private fun verifyReplayCursor() {
        for (cols in listOf(100, 80)) for (total in listOf(284, 3 * cols)) {
            TerminalEmulatorFactory.create(initialRows = 24, initialCols = cols).use { emulator ->
                emulator.writeReplay(("some output\r\n" + "x".repeat(total)).toByteArray())
                emulator.getBufferSnapshot()
                val snapshot = (emulator as TerminalEmulatorImpl).snapshot.value
                val wrapped = (total - 1) / cols
                val column = total - wrapped * cols - 1
                // Written to the last column the cursor stays on it (pending wrap); otherwise it is just after the last character.
                val expected = 1 + wrapped to if (total % cols == 0) cols - 1 else column + 1
                check(snapshot.cursorRow to snapshot.cursorCol == expected && snapshot.cursorVisible) {
                    "$cols columns, $total characters: cursor at ${snapshot.cursorRow},${snapshot.cursorCol} visible=${snapshot.cursorVisible}, expected $expected"
                }
            }
        }
    }

    /**
     * Where `WriteCuts` says an OSC 8 ends is where libvterm finishes one: random streams of links in both terminators, other
     * OSCs, strings that are cancelled or aborted and stray ESCs go to the library a byte at a time, and the byte it reports an
     * OSC 8 done on is compared with the scan, which follows the library's parser exactly: a string opened inside a string cancels it (the
     * `nested` fragment), and nothing is cut inside it.
     */
    private fun verifyWriteCuts() {
        val esc = "\u001b"
        var finished = 0
        val callbacks = object : TerminalCallbacks {
            private val scratch = CellData.buffer()
            override fun cellBuffer(): ByteBuffer = scratch
            override fun damage(startRow: Int, endRow: Int, startCol: Int, endCol: Int) = 0
            override fun moverect(dest: TermRect, src: TermRect) = 0
            override fun moveCursor(pos: CursorPosition, oldPos: CursorPosition, visible: Boolean) = 0
            override fun setTermProp(prop: Int, value: TerminalProperty) = 0
            override fun bell() = 0
            override fun pushScrollbackLine(cols: Int, start: Int, count: Int, cells: ByteBuffer, softWrapped: Boolean) = 0
            override fun popScrollbackLine(cols: Int, start: Int, count: Int, cells: ByteBuffer) = 0
            override fun clearScrollback() = 0
            override fun onKeyboardInput(data: ByteArray) = 0
            override fun onTextFragment(kind: Int, command: Int, data: ByteArray, initial: Boolean, final: Boolean, cursorRow: Int, cursorCol: Int): Int {
                if (kind == TerminalTextDecoder.OSC && command == 8 && final) finished++
                return 1
            }
        }
        val fragments: List<(Int) -> String> = listOf(
            { n -> "$esc]8;;https://a$n.test/\u0007link$n$esc]8;;\u0007" },
            { n -> "$esc]8;id=$n;https://x$n.test/$esc\\x$n$esc]8;;$esc\\" },
            { n -> "$esc]0;title $n\u0007" },
            { n -> "$esc]8;;https://cancelled$n.test/\u0018still text" },
            { n -> "$esc]8;;https://aborted$n.test/${esc}Xtext" },
            { _ -> esc },
            { _ -> "$esc\\" },
            { n -> "plain $n\r\n" },
            { n -> "$esc]133;A\u0007prompt $n" },
            { _ -> "$esc]8;;\u0007" },
            { n -> "$esc]08;;https://zero$n.test/\u0007z$n$esc]8;;\u0007" },
            { n -> "$esc]80;not a link $n\u0007" },
            { _ -> "$esc]8\u0007" },
            { n -> "$esc]4294967304;;https://wrapped$n.test/\u0007w$n$esc]8;;\u0007" },
            { _ -> "$esc]8$esc\\" },
            { n -> "$esc]8\r0;;https://control$n.test/\u0007c$n$esc]8;;\u0007" },
            { n -> "$esc\r]8;;https://pending$n.test/\u0007p$n$esc]8;;\u0007" },
            { n -> "$esc]8;;https://cancelled$n.test/$esc\u0007]8;;https://after$n.test/\u0007a$n$esc]8;;\u0007" },
        )
        val nested: (Int) -> String = { n -> "$esc]8;;https://a.test/$esc]8;;https://b.test/\u0007text$n$esc]8;;\u0007" }

        fun compare(pool: List<(Int) -> String>, seed: Int) {
            val random = Random(seed)
            repeat(300) { round ->
                val text = (1..random.nextInt(1, 14)).joinToString("") { pool[random.nextInt(pool.size)](it) }
                val stream = text.toByteArray(Charsets.ISO_8859_1)
                val finishedAt = mutableListOf<Int>()
                val native = TerminalNative(callbacks)
                try {
                    native.resize(8, 60)
                    for (i in stream.indices) {
                        val before = finished
                        native.writeInput(stream, i, 1)
                        if (finished > before) finishedAt += i + 1
                    }
                } finally {
                    native.close()
                }
                val scanner = WriteCuts()
                val cuts = mutableListOf<Int>()
                var from = 0
                while (true) {
                    val cut = scanner.next(stream, from, stream.size)
                    if (cut < 0) break
                    cuts += cut
                    from = cut
                }
                val shown = text.replace(esc, "<ESC>")
                check(cuts == finishedAt) { "Round $round: libvterm finished OSC 8 at $finishedAt, the scan cuts at $cuts, in $shown" }
            }
        }
        compare(fragments, seed = 8)
        compare(fragments + nested, seed = 88)
    }

    private fun verifyRenderer() {
        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap.asImageBitmap())
        val scope = CanvasDrawScope()
        val paint = TerminalTextPaint(Typeface.MONOSPACE, 24f, 4.5)
        val underline = Paint()
        val fg = Color(0xFF777777)
        val bg = Color(0xFF888888)
        fun pixels() = IntArray(1600).also { bitmap.getPixels(it, 0, 40, 0, 0, 40, 40) }
        try {
            for (inverse in listOf(false, true)) {
                val line = TerminalLine(0, listOf(TerminalLine.Cell('M', fgColor = fg, bgColor = bg, reverse = inverse)))
                scope.draw(Density(1f), LayoutDirection.Ltr, canvas, Size(40f, 40f)) {
                    drawLine(line, 0, 40f, 40f, 30f, paint, underline, fg, bg, null)
                }
                val back = if (inverse) fg else bg
                val front = if (inverse) bg else fg
                val adjusted = ensureTerminalContrast(back.toArgb(), front.toArgb(), 4.5)
                check(bitmap.getPixel(39, 0) == back.toArgb()) { "Cell background was changed" }
                check(adjusted in pixels()) { "Rendered truecolor/inverse foreground misses contrast adjustment" }
            }
            val cursor = Color(0xFF22AA55)
            val accent = Color(0xFF112233)
            val line = TerminalLine(0, listOf(TerminalLine.Cell('M', fgColor = fg, bgColor = bg)))
            for (shape in CursorShape.entries) {
                scope.draw(Density(1f), LayoutDirection.Ltr, canvas, Size(40f, 40f)) {
                    drawRect(Color.White)
                    drawTerminalCursor(line, 0, 0, 0, shape, 40f, 40f, 30f, paint, underline, bg, cursor, accent)
                }
                check(cursor.toArgb() in pixels()) { "$shape cursor colour not drawn" }
                if (shape == CursorShape.BLOCK) check(accent.toArgb() in pixels()) { "Cursor accent not drawn" }
            }
        } finally { bitmap.recycle() }
    }
}
