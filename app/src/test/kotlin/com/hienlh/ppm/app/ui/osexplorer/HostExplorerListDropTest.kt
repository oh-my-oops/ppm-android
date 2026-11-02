package com.hienlh.ppm.app.ui.osexplorer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithContentDescription
import com.hienlh.ppm.state.osexplorer.ExplorerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The List view's rows and its background as drop targets for entries ([HostEntriesViewDropTestBase]), and what is only the list's. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h700dp")
internal class HostExplorerListDropTest : HostEntriesViewDropTestBase(ExplorerView.LIST) {
    @Test
    fun `the header above the rows is no target, as the web's background is the scrolling list alone`() {
        show(ExplorerView.LIST)
        begin(entries("/elsewhere/x.txt"))
        hover(top())
        assertFalse(letGo(top()))
        assertEquals(emptyList<String>(), transfers.toList())
    }

    @Test
    fun `at the line between two rows the lower one has the drop, the row above ending where it begins`() {
        show(ExplorerView.LIST)
        val docs = compose.onNodeWithContentDescription("docs").fetchSemanticsNode().boundsInRoot
        val src = compose.onNodeWithContentDescription("src").fetchSemanticsNode().boundsInRoot
        assertEquals("the rows touch", docs.bottom, src.top, 0.01f)
        begin(entries("/elsewhere/x.txt"))
        val line = Offset(docs.center.x, docs.bottom)
        hover(line)
        assertTrue(letGo(line))
        assertEquals(listOf("move /elsewhere/x.txt -> /home/me/src/x.txt"), transfers.toList())
    }
}
