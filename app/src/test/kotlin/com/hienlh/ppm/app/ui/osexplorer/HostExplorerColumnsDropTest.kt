package com.hienlh.ppm.app.ui.osexplorer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithContentDescription
import com.hienlh.ppm.app.DragModifierKeys
import com.hienlh.ppm.state.osexplorer.ExplorerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Column view's rows and the background of each column as drop targets for entries (`column-view-column.tsx`, `column-row.tsx`): a
 * folder's row takes them into the folder, a file's row and the space under the rows are the column's own folder, which for the
 * columns left of the last is a folder above the one the window shows, and a drag that rests 800 ms on a folder that would take it opens it.
 * The window is `/home/me` (`docs`, `src`, `a.txt`, `b.txt`) with `/home` (`me`, `shared`, `z.txt`) to its left.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h700dp")
internal class HostExplorerColumnsDropTest : HostEntryDndTestBase() {
    @Test
    fun `entries let go on a folder's row are moved into that folder, and the window says Moved`() {
        show(ExplorerView.COLUMNS, clockHeld = true)
        assertTrue(begin(entries("/elsewhere/x.txt")))
        hover(center("docs"))
        assertTrue(letGo(center("docs")))
        assertEquals(listOf("move /elsewhere/x.txt -> /home/me/docs/x.txt"), transfers.toList())
        assertEquals(listOf("Moved 1 item"), titles())
    }

    @Test
    fun `a file's row is the column's folder`() {
        show(ExplorerView.COLUMNS, clockHeld = true)
        begin(entries("/elsewhere/x.txt"))
        hover(center("a.txt"))
        assertTrue(letGo(center("a.txt")))
        assertEquals(listOf("move /elsewhere/x.txt -> /home/me/x.txt"), transfers.toList())
    }

    @Test
    fun `so is the space under the rows`() {
        show(ExplorerView.COLUMNS, clockHeld = true)
        begin(entries("/elsewhere/x.txt"))
        hover(belowColumn("/home/me"))
        assertTrue(letGo(belowColumn("/home/me")))
        assertEquals(listOf("move /elsewhere/x.txt -> /home/me/x.txt"), transfers.toList())
    }

    @Test
    fun `the column left of the last is its own folder's, so a drop under its rows moves up a level`() {
        show(ExplorerView.COLUMNS, clockHeld = true)
        begin(entries("/home/me/a.txt"))
        hover(belowColumn("/home"))
        assertTrue(letGo(belowColumn("/home")))
        assertEquals(listOf("move /home/me/a.txt -> /home/a.txt"), transfers.toList())
    }

    @Test
    fun `a folder's row in that column takes it into that folder`() {
        show(ExplorerView.COLUMNS, clockHeld = true)
        begin(entries("/home/me/a.txt"))
        hover(center("shared"))
        assertTrue(letGo(center("shared")))
        assertEquals(listOf("move /home/me/a.txt -> /home/shared/a.txt"), transfers.toList())
    }

    @Test
    fun `what is in the last column's folder already is refused under its rows, and nothing is said of it`() {
        show(ExplorerView.COLUMNS, clockHeld = true)
        begin(entries("/home/me/a.txt"))
        hover(belowColumn("/home/me"))
        assertFalse(letGo(belowColumn("/home/me")))
        assertEquals(emptyList<String>(), transfers.toList())
        assertEquals(emptyList<String>(), titles())
    }

    @Test
    fun `a folder that refuses is the target all the same, so what it refuses is not handed on to the column's folder`() {
        show(ExplorerView.COLUMNS, clockHeld = true)
        begin(entries("/home/me/docs/guide.md"))
        hover(center("docs"))
        assertFalse(letGo(center("docs")))
        assertEquals(emptyList<String>(), transfers.toList())
    }

    @Test
    fun `with Ctrl down when it is let go the drop is a copy`() {
        show(ExplorerView.COLUMNS, clockHeld = true)
        begin(entries("/elsewhere/x.txt"))
        hover(center("docs"))
        DragModifierKeys.update(ctrl = true, alt = false)
        assertTrue(letGo(center("docs")))
        assertEquals(listOf("copy /elsewhere/x.txt -> /home/me/docs/x.txt"), transfers.toList())
        assertEquals(listOf("Copied 1 item"), titles())
    }

    @Test
    fun `the drag of a column's row is that entry alone, a folder's included, and is let go on another column`() {
        show(ExplorerView.COLUMNS, clockHeld = true)
        begin(entries("/home/me/docs"))
        hover(belowColumn("/home"))
        assertTrue(letGo(belowColumn("/home")))
        assertEquals(listOf("move /home/me/docs -> /home/docs"), transfers.toList())
    }

    @Test
    fun `rows of a project tree are moved by their absolute paths`() {
        show(ExplorerView.COLUMNS, clockHeld = true)
        assertTrue(begin(treeRows("src/app.kt")))
        hover(center("shared"))
        assertTrue(letGo(center("shared")))
        assertEquals(listOf("move /home/me/p/src/app.kt -> /home/shared/app.kt"), transfers.toList())
    }

    @Test
    fun `a drag that rests 800 ms on a folder that would take it opens it`() {
        show(ExplorerView.COLUMNS, clockHeld = true)
        begin(entries("/elsewhere/x.txt"))
        hover(center("docs"))
        compose.mainClock.advanceTimeBy(790, ignoreFrameDuration = true)
        assertEquals("not yet", "/home/me", session.state.value.path)
        compose.mainClock.advanceTimeBy(30, ignoreFrameDuration = true)
        assertEquals("/home/me/docs", session.state.value.path)
    }

    @Test
    fun `it opens the folder of an ancestor column as well`() {
        show(ExplorerView.COLUMNS, clockHeld = true)
        begin(entries("/elsewhere/x.txt"))
        hover(center("shared"))
        compose.mainClock.advanceTimeBy(2_000, ignoreFrameDuration = true)
        assertEquals("/home/shared", session.state.value.path)
    }

    @Test
    fun `a drag that leaves before the wait is over opens nothing`() {
        show(ExplorerView.COLUMNS, clockHeld = true)
        begin(entries("/elsewhere/x.txt"))
        hover(center("docs"))
        compose.mainClock.advanceTimeBy(500, ignoreFrameDuration = true)
        hover(belowColumn("/home/me"))
        compose.mainClock.advanceTimeBy(2_000, ignoreFrameDuration = true)
        assertEquals("/home/me", session.state.value.path)
    }

    @Test
    fun `a file's row and the space under the rows open nothing`() {
        show(ExplorerView.COLUMNS, clockHeld = true)
        begin(entries("/elsewhere/x.txt"))
        hover(center("a.txt"))
        compose.mainClock.advanceTimeBy(2_000, ignoreFrameDuration = true)
        hover(belowColumn("/home/me"))
        compose.mainClock.advanceTimeBy(2_000, ignoreFrameDuration = true)
        assertEquals("/home/me", session.state.value.path)
    }

    @Test
    fun `a folder that would refuse the drop is not opened by it, itself or one that holds the drag already`() {
        show(ExplorerView.COLUMNS, clockHeld = true)
        begin(entries("/home/me/docs"))
        hover(center("docs"))
        compose.mainClock.advanceTimeBy(2_000, ignoreFrameDuration = true)
        assertEquals("onto itself", "/home/me", session.state.value.path)
        driver.end()

        begin(entries("/home/me/docs/guide.md"))
        hover(center("docs"))
        compose.mainClock.advanceTimeBy(2_000, ignoreFrameDuration = true)
        assertEquals("a move out of it", "/home/me", session.state.value.path)
    }

    @Test
    fun `at the line between two rows the lower one has the drop, the row above ending where it begins`() {
        show(ExplorerView.COLUMNS, clockHeld = true)
        val docs = compose.onNodeWithContentDescription("docs").fetchSemanticsNode().boundsInRoot
        val src = compose.onNodeWithContentDescription("src").fetchSemanticsNode().boundsInRoot
        assertEquals("the rows touch", docs.bottom, src.top, 0.01f)
        begin(entries("/elsewhere/x.txt"))
        val line = Offset(docs.center.x, docs.bottom)
        hover(line)
        assertTrue(letGo(line))
        assertEquals(listOf("move /elsewhere/x.txt -> /home/me/src/x.txt"), transfers.toList())
    }

    @Test
    fun `a window on a phone takes no drag`() {
        show(ExplorerView.COLUMNS, mobile = true)
        assertFalse(begin(entries("/elsewhere/x.txt")))
    }

    @Test
    fun `a window with no commands takes no drag`() {
        show(ExplorerView.COLUMNS, withCommands = false)
        assertFalse(begin(entries("/elsewhere/x.txt")))
    }
}
