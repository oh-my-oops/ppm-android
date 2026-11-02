package com.hienlh.ppm.app.ui.osexplorer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithContentDescription
import com.hienlh.ppm.state.osexplorer.ExplorerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Icons view's tiles and its background as drop targets for entries ([HostEntriesViewDropTestBase]), and what is only the grid's. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h700dp")
internal class HostExplorerIconsDropTest : HostEntriesViewDropTestBase(ExplorerView.ICONS) {
    @Test
    fun `the gap between two tiles is the grid's folder, not either tile's`() {
        show(ExplorerView.ICONS)
        // Each tile is its cell less 4 dp on every side, so two side by side are 8 dp apart.
        val docs = compose.onNodeWithContentDescription("docs").fetchSemanticsNode().boundsInRoot
        val src = compose.onNodeWithContentDescription("src").fetchSemanticsNode().boundsInRoot
        assertTrue("the tiles are in a row", docs.top == src.top && docs.right < src.left)
        val gap = Offset((docs.right + src.left) / 2f, docs.center.y)
        begin(entries("/elsewhere/x.txt"))
        hover(gap)
        assertTrue(letGo(gap))
        assertEquals(listOf("move /elsewhere/x.txt -> /home/me/x.txt"), transfers.toList())
    }

    @Test
    fun `so are the strip above the first row and the one left of the first column, which are cell and padding and no tile`() {
        show(ExplorerView.ICONS)
        val docs = compose.onNodeWithContentDescription("docs").fetchSemanticsNode().boundsInRoot
        for (at in listOf(Offset(docs.center.x, docs.top - 2f), Offset(docs.left - 2f, docs.center.y))) {
            begin(entries("/elsewhere/x.txt"))
            hover(at)
            assertTrue("at $at", letGo(at))
        }
        assertEquals(List(2) { "move /elsewhere/x.txt -> /home/me/x.txt" }, transfers.toList())
    }

    @Test
    fun `a point just outside a tile is the grid's folder, on each of its four sides`() {
        show(ExplorerView.ICONS)
        val src = compose.onNodeWithContentDescription("src").fetchSemanticsNode().boundsInRoot
        val outside = listOf(
            Offset(src.center.x, src.top - 1f), Offset(src.center.x, src.bottom + 1f),
            Offset(src.left - 1f, src.center.y), Offset(src.right + 1f, src.center.y),
        )
        for (at in outside) {
            begin(entries("/elsewhere/x.txt"))
            hover(at)
            assertTrue("at $at", letGo(at))
        }
        assertEquals(List(4) { "move /elsewhere/x.txt -> /home/me/x.txt" }, transfers.toList())
    }

    @Test
    fun `a point just inside a tile is the tile's folder, on each of its four sides`() {
        show(ExplorerView.ICONS)
        val docs = compose.onNodeWithContentDescription("docs").fetchSemanticsNode().boundsInRoot
        val inside = listOf(
            Offset(docs.center.x, docs.top + 1f), Offset(docs.center.x, docs.bottom - 2f),
            Offset(docs.left + 1f, docs.center.y), Offset(docs.right - 2f, docs.center.y),
        )
        for (at in inside) {
            begin(entries("/elsewhere/x.txt"))
            hover(at)
            assertTrue("at $at", letGo(at))
        }
        assertEquals(List(4) { "move /elsewhere/x.txt -> /home/me/docs/x.txt" }, transfers.toList())
    }
}
