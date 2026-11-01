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

/** JNI smoke checks in an isolated test APK; no app activity or clipboard access. */
class EngineInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = runCatching { verifyEngine(); verifyScrollbackWraps(); verifyRenderer() }
        finish(if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", if (result.isSuccess) "PASS: pinned JNI, scrollback, wrapped lines in it, text/cursor, reset, close, late work, truecolor/inverse contrast, cursor colours\n"
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
