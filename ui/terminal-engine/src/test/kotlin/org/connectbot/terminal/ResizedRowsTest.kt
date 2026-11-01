package org.connectbot.terminal

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where each row of the screen goes when libvterm resizes it, and which flags are carried (see [ResizedRows]). The library's side of it, that
 * it moves the rows like this, is what `EngineInstrumentation` holds the model to; these are the arithmetic and the rules for the flags.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResizedRowsTest {
    private val cols = 6

    private fun line(row: Int, text: String, wrapped: Boolean = false, segments: List<SemanticSegment> = emptyList()) = TerminalLine(
        row,
        List(cols) { col -> TerminalLine.Cell(text.getOrElse(col) { ' ' }, emptyList(), fgColor = Color.White, bgColor = Color.Black, width = 1) },
        semanticSegments = segments,
        softWrapped = wrapped,
    )

    /** Rows named by their text, from row 0; a trailing `~` makes the row wrap onto the next. */
    private fun screen(vararg rows: String) = rows.mapIndexed { row, name -> line(row, name.trimEnd('~'), name.endsWith("~")) }

    private fun List<TerminalLine>.names() = map { it.text.trim() + if (it.softWrapped) "~" else "" }

    private fun List<TerminalLine>.kept() = map { it.wrapKept }

    private val abcdef = screen("A", "B", "C", "D", "E", "F")

    // ---------------------------------------------------------------- which row is which

    @Test fun `a shrink that pushes rows moves the rest up by as many as it pushed`() {
        val rows = ResizedRows(oldRows = 6, newRows = 3, pushed = 3, popped = 0)
        assertEquals(listOf("D", "E", "F"), rows.remap(abcdef, emptyList(), keepFlags = true).names())
        assertEquals(-3, rows.newRowOf(0))
        assertEquals(0, rows.newRowOf(3))
        assertEquals(2, rows.newRowOf(5))
    }

    @Test fun `a shrink that only lets blank rows go keeps the others where they were`() {
        val rows = ResizedRows(oldRows = 6, newRows = 4, pushed = 0, popped = 0)
        assertEquals(listOf("A", "B", "C", "D"), rows.remap(abcdef, emptyList(), keepFlags = true).names())
        assertEquals(1, rows.newRowOf(1))
        assertEquals(5, rows.newRowOf(5))
    }

    @Test fun `a grow takes rows back from the history and puts them above the old ones, the newest next to them`() {
        val rows = ResizedRows(oldRows = 3, newRows = 6, pushed = 0, popped = 3)
        // The library took "c" first, being the row pushed last, then "b", then "a".
        val restored = screen("c", "b", "a")
        assertEquals(listOf("a", "b", "c", "A", "B", "C"), rows.remap(screen("A", "B", "C"), restored, keepFlags = true).names())
        assertEquals(3, rows.newRowOf(0))
        assertEquals(5, rows.newRowOf(2))
    }

    @Test fun `a grow that finds less in the history than it has room for leaves blank rows at the bottom`() {
        val rows = ResizedRows(oldRows = 3, newRows = 6, pushed = 0, popped = 1)
        assertEquals(listOf("c", "A", "B", "C", "", ""), rows.remap(screen("A", "B", "C"), screen("c"), keepFlags = true).names())
    }

    @Test fun `a grow from an empty history leaves them all at the bottom`() {
        val rows = ResizedRows(oldRows = 3, newRows = 5, pushed = 0, popped = 0)
        assertEquals(listOf("A", "B", "C", "", ""), rows.remap(screen("A", "B", "C"), emptyList(), keepFlags = true).names())
    }

    @Test fun `a resize that moves nothing keeps every row`() {
        val rows = ResizedRows(oldRows = 6, newRows = 6, pushed = 0, popped = 0)
        assertEquals(listOf("A", "B", "C", "D", "E", "F"), rows.remap(abcdef, emptyList(), keepFlags = true).names())
    }

    @Test fun `every row of the new screen knows its own number, the ones brought back and the blank ones too`() {
        val grown = ResizedRows(oldRows = 3, newRows = 6, pushed = 0, popped = 2)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), grown.remap(screen("A", "B", "C"), screen("b", "a"), keepFlags = true).map { it.row })
        val shrunk = ResizedRows(oldRows = 6, newRows = 2, pushed = 4, popped = 0)
        assertEquals(listOf(0, 1), shrunk.remap(abcdef, emptyList(), keepFlags = true).map { it.row })
    }

    // ---------------------------------------------------------------- what is carried

    @Test fun `a row keeps its wrap, and says it is a flag the library does not hold`() {
        val rows = ResizedRows(oldRows = 4, newRows = 4, pushed = 0, popped = 0)
        val after = rows.remap(screen("A~", "B~", "C", "D"), emptyList(), keepFlags = true)
        assertEquals(listOf("A~", "B~", "C", "D"), after.names())
        assertEquals(listOf(true, true, false, false), after.kept())
    }

    @Test fun `no row keeps a wrap when the screen changed in columns, as none breaks where it did`() {
        val rows = ResizedRows(oldRows = 4, newRows = 3, pushed = 1, popped = 0)
        val after = rows.remap(screen("A~", "B~", "C~", "D"), emptyList(), keepFlags = false)
        assertEquals(listOf("B", "C", "D"), after.names())
        assertEquals(listOf(false, false, false), after.kept())
    }

    @Test fun `a row does not wrap onto a row below the screen, whatever the one dropped under it said`() {
        val rows = ResizedRows(oldRows = 4, newRows = 2, pushed = 2, popped = 0)
        val after = rows.remap(screen("A", "B", "C~", "D~"), emptyList(), keepFlags = true)
        assertEquals(listOf("C~", "D"), after.names())
        assertEquals(listOf(true, false), after.kept())
    }

    @Test fun `a row brought back from the history brings the flag it had there`() {
        val rows = ResizedRows(oldRows = 3, newRows = 5, pushed = 0, popped = 2)
        // "b~" is the nearest to the old rows (taken first), "a~" the farthest.
        val after = rows.remap(screen("A", "B", "C"), screen("b~", "a~"), keepFlags = true)
        assertEquals(listOf("a~", "b~", "A", "B", "C"), after.names())
        assertEquals(listOf(true, true, false, false, false), after.kept())
    }

    @Test fun `a line keeps its cells and its links, and only its row changes`() {
        val link = SemanticSegment(1, 4, SemanticType.HYPERLINK, "https://l.test/")
        val original = line(5, "ABCDEF", segments = listOf(link))
        val moved = ResizedRows(oldRows = 6, newRows = 3, pushed = 3, popped = 0)
            .remap(abcdef.take(5) + original, emptyList(), keepFlags = true)[2]
        assertEquals(2, moved.row)
        assertEquals(listOf(link), moved.semanticSegments)
        assertSame(original.cells, moved.cells)
    }
}
