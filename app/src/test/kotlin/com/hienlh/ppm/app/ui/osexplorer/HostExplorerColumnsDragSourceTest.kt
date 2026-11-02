package com.hienlh.ppm.app.ui.osexplorer

import com.hienlh.ppm.app.ui.files.HostEntryDrag
import com.hienlh.ppm.state.osexplorer.ExplorerView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Column view's rows as the source of a drag ([HostEntriesViewDragSourceTestBase]): always the one entry, as `column-row.tsx` has `dragPaths: [entry.path]`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h700dp")
internal class HostExplorerColumnsDragSourceTest : HostEntriesViewDragSourceTestBase(ExplorerView.COLUMNS) {
    @Test
    fun `a row carries itself alone, though the window has a selection that holds it`() {
        show(ExplorerView.COLUMNS, clockHeld = true)
        session.select("/home/me/a.txt")
        session.select("/home/me/b.txt", toggle = true)
        compose.mainClock.advanceTimeByFrame()
        dragWithMouse("a.txt")
        assertEquals(HostEntryDrag(listOf("/home/me/a.txt")), startedLocalState())
    }

    @Test
    fun `a row of a column left of the last carries its own path`() {
        show(ExplorerView.COLUMNS, clockHeld = true)
        dragWithMouse("shared")
        assertEquals(HostEntryDrag(listOf("/home/shared")), startedLocalState())
    }
}
