package org.connectbot.terminal

import androidx.compose.ui.graphics.Color
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a row reads of itself (its text, its text one character per column, its links) was a `by lazy` each: a lazy object and a lambda, 120 B on every row of
 * the history, as much as its cells. The patch keeps each read in a field of the row instead (`terminal_line` in `patch-terminal-engine.py`), and this
 * is what must stay as it was: a read is made once, a row is equal to another by what it holds and not by what was read from it, and a copy reads again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalLineCacheTest {
    private fun line(text: String, row: Int = 0) = TerminalLine(
        row = row,
        cells = text.map { TerminalLine.Cell(char = it, fgColor = Color.White, bgColor = Color.Black) },
        lastModified = 1L,
    )

    @Test fun `a row holds no lazy objects`() {
        val lazies = TerminalLine::class.java.declaredFields.filter { Lazy::class.java.isAssignableFrom(it.type) }.map { it.name }
        assertTrue("a lazy costs a row 40 B, and the history holds 50,000 rows: $lazies", lazies.isEmpty())
    }

    @Test fun `cache fields are volatile so another thread never sees a half-published list`() {
        for (name in listOf("textCache", "columnTextCache", "urlsCache")) {
            val field = TerminalLine::class.java.getDeclaredField(name)
            assertTrue("$name must be @Volatile", Modifier.isVolatile(field.modifiers))
        }
    }

    @Test fun `a read of a row is made once`() {
        val line = line("see https://example.com/a now")
        assertEquals("see https://example.com/a now", line.text)
        assertSame(line.text, line.text)
        assertSame(line.columnText, line.columnText)
        assertEquals(listOf(Triple(4, 25, "https://example.com/a")), line.autoDetectedUrls)
        assertSame(line.autoDetectedUrls, line.autoDetectedUrls)
        assertEquals("https://example.com/a", line.getHyperlinkUrlAt(10, autoDetectUrls = true))
        assertEquals(null, line.getHyperlinkUrlAt(1, autoDetectUrls = true))
    }

    @Test fun `a row with no cells has no links, and a blank text`() {
        val line = TerminalLine(row = 0, cells = PackedCells.empty(0, Color.White, Color.Black), lastModified = 1L)
        assertEquals("", line.text)
        assertTrue(line.autoDetectedUrls.isEmpty())
    }

    @Test fun `rows are equal by what they hold, not by what has been read from them`() {
        val read = line("abc")
        val unread = line("abc")
        assertEquals("abc", read.text)
        assertEquals(read.columnText, "abc")
        assertEquals(unread, read)
        assertEquals(unread.hashCode(), read.hashCode())
    }

    @Test fun `a copy of a row reads again`() {
        val line = line("abc")
        val text = line.text
        val copy = line.copy(row = 5)
        assertEquals(text, copy.text)
        assertNotSame(text, copy.text)
    }
}
