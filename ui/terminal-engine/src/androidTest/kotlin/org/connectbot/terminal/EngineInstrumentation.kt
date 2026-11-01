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

/** JNI smoke checks in an isolated test APK; no app activity or clipboard access. */
class EngineInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = runCatching { verifyEngine(); verifyScrollbackWraps(); verifyWrapFlagsAcrossWrites(); verifyLinks(); verifyLinksAcrossScrolls(); verifyRenderer() }
        finish(if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", if (result.isSuccess) "PASS: pinned JNI, scrollback, wrapped lines in it, wrap flags across writes, links (OSC 8, plain, wrapped, in the history, on their own rows while the screen scrolls), text/cursor, reset, close, late work, truecolor/inverse contrast, cursor colours\n"
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

        // [mayLose]: a row may have lost its link (a resize does not move them yet), but never has another row's.
        fun check(emulator: TerminalEmulator, links: Int, where: String, mayLose: Boolean = false) {
            val rows = rows(emulator)
            for ((index, row) in rows.withIndex()) {
                val expected = if (row.first.startsWith("link")) listOf("https://l${row.first.removePrefix("link")}.test/") else emptyList()
                check(row.second == expected || (mayLose && row.second.isEmpty())) { "Links of row $index '${row.first}' $where: ${row.second}, not $expected; rows $rows" }
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
            // The soft keyboard comes up and goes away again: the rows above the cursor go to the history and come back from it.
            // The links of the rows that stayed are lost, as before (a resize does not move segments); none is on another row.
            emulator.resize(3, 40)
            emulator.resize(6, 40)
            check(emulator, 5, "after the screen shrank and grew back", mayLose = true)
        }
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
