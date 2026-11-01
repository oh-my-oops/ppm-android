package org.connectbot.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.*
import org.junit.Test

class TerminalEngineTest {
    @Test fun `cursor cell bounds use measured dimensions and visual column`() {
        assertEquals(androidx.compose.ui.geometry.Rect(32.5f, 51f, 45.5f, 68f),
            terminalCursorCellBounds(3, 5, 2, 6.5f, 17f))
        assertEquals(androidx.compose.ui.geometry.Rect(0f, 0f, 6.5f, 17f),
            terminalCursorCellBounds(0, 0, 0, 6.5f, 17f))
    }

    @Test fun `contrast matches web xterm across RGB and inverse pairs`() {
        javaClass.getResourceAsStream("/xterm-contrast.tsv")!!.bufferedReader().useLines { lines ->
            lines.filter { !it.startsWith("#") }.forEach { row ->
                val (bg, fg, ratio, result) = row.split('\t')
                assertEquals(row, result.toLong(16).toInt(),
                    ensureTerminalContrast(bg.toLong(16).toInt(), fg.toLong(16).toInt(), ratio.toDouble()))
            }
        }
    }

    @Test fun `contrast preserves backgrounds and xterm decorative glyph exclusions`() {
        val contrast = TerminalContrast()
        val fg = Color(0xFF777777)
        val bg = Color(0xFF888888)
        for (code in listOf(0x2500, 0x259F, 0xE0B0, 0xE0D6)) {
            assertEquals(fg, contrast.foreground(fg, bg, Color.White, 4.5, code))
        }
        assertEquals(fg, contrast.foreground(fg, bg, Color.White, 1.0, 'a'.code))
        val adjusted = contrast.foreground(fg, bg, Color.White, 4.5, 'a'.code)
        assertTrue(terminalContrastRatio(terminalLuminance(bg.toArgb()), terminalLuminance(adjusted.toArgb())) >= 4.5)
        // Changing a cell's truecolor background uses a different cache entry, even for one fg.
        assertNotEquals(adjusted, contrast.foreground(fg, Color.Black, Color.Black, 4.5, 'a'.code))
    }

    @Test fun `buffer reads retain indentation soft wraps and absolute coordinates`() {
        assertEquals("", terminalText(TerminalSnapshot.empty(3, 12).lines))
        val snapshot = TerminalBufferSnapshot(listOf(
            TerminalBufferLine("history   ", false),
            TerminalBufferLine("  command ", true),
            TerminalBufferLine("continued ", false),
            TerminalBufferLine("          ", false),
        ), cursorRow = 2, cursorColumn = 4, screenStartRow = 1, alternateScreen = false)
        assertEquals("  command continued", snapshot.getTextBetweenRows(1, 4))
        assertEquals("history\n  command continued", snapshot.getTextBetweenRows(0, 100))
        assertEquals("", snapshot.getTextBetweenRows(99, 100))
        assertEquals(2, snapshot.cursorRow)
        assertEquals(4, snapshot.lines.size)
    }

    @Test fun `selection read leaves range mode and extending state untouched`() {
        fun line(row: Int, text: String, wraps: Boolean) = TerminalLine(row,
            text.map { TerminalLine.Cell(it, fgColor = Color.White, bgColor = Color.Black) }, softWrapped = wraps)
        val snapshot = TerminalSnapshot.empty(2, 6).copy(lines = listOf(line(0, "abcdef", true), line(1, "ghijkl", false)))
        val manager = SelectionManager()
        manager.startSelection(0, 2, 6, snapshot = snapshot)
        manager.updateSelection(1, 2)
        val range = manager.selectionRange
        val mode = manager.mode
        val extending = manager.isSelecting
        repeat(2) { assertEquals("cdefghi", manager.getSelectedText(snapshot)) }
        assertEquals(range, manager.selectionRange)
        assertEquals(mode, manager.mode)
        assertEquals(extending, manager.isSelecting)
    }
}
