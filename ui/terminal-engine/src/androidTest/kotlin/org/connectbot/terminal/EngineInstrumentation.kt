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
        val result = runCatching { verifyEngine(); verifyRenderer() }
        finish(if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", if (result.isSuccess) "PASS: pinned JNI, scrollback, text/cursor, reset, close, late work, truecolor/inverse contrast, cursor colours\n"
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
