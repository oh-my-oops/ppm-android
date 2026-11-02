package com.hienlh.ppm.app.ui.osexplorer

import com.hienlh.ppm.app.ui.files.HostEntryDrag
import com.hienlh.ppm.state.osexplorer.ExplorerView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Icons view's tiles as the source of a drag ([HostEntriesViewDragSourceTestBase]), carrying the selection as the list's rows do. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h700dp")
internal class HostExplorerIconsDragSourceTest : HostEntriesViewDragSourceTestBase(ExplorerView.ICONS) {
    @Test
    fun `a tile that is one of the selection carries the whole selection, and one that is not carries itself`() {
        show(ExplorerView.ICONS)
        session.select("/home/me/src")
        session.select("/home/me/b.txt", toggle = true)
        compose.waitForIdle()
        dragWithMouse("b.txt")
        assertEquals(HostEntryDrag(listOf("/home/me/src", "/home/me/b.txt")), startedLocalState())
        dragWithMouse("docs")
        assertEquals(HostEntryDrag(listOf("/home/me/docs")), startedLocalState())
    }
}
