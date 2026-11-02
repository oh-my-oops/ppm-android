package com.hienlh.ppm.app.ui.osexplorer

import androidx.compose.ui.test.MouseButton
import com.hienlh.ppm.app.ui.files.HostEntryDrag
import com.hienlh.ppm.state.osexplorer.ExplorerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The List view's rows as the source of a drag ([HostEntriesViewDragSourceTestBase]), and what the selection does to what one carries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h700dp")
internal class HostExplorerListDragSourceTest : HostEntriesViewDragSourceTestBase(ExplorerView.LIST) {
    @Test
    fun `a row that is one of the selection carries the whole selection, in the order it is shown`() {
        show(ExplorerView.LIST)
        session.select("/home/me/a.txt")
        session.select("/home/me/docs", toggle = true)
        compose.waitForIdle()
        dragWithMouse("a.txt")
        assertEquals(HostEntryDrag(listOf("/home/me/docs", "/home/me/a.txt")), startedLocalState())
        assertEquals("""{"paths":["/home/me/docs","/home/me/a.txt"],"origin":"explorer"}""", startedClip()!!.getItemAt(0).text.toString())
    }

    @Test
    fun `a row that is not in the selection carries itself alone, and leaves the selection as it was`() {
        show(ExplorerView.LIST)
        session.select("/home/me/a.txt")
        session.select("/home/me/docs", toggle = true)
        compose.waitForIdle()
        dragWithMouse("b.txt")
        assertEquals(HostEntryDrag(listOf("/home/me/b.txt")), startedLocalState())
        assertEquals(setOf("/home/me/a.txt", "/home/me/docs"), session.state.value.selection)
    }

    @Test
    fun `a row with no selection around it carries itself alone, and the drag does not select it`() {
        show(ExplorerView.LIST)
        dragWithMouse("src")
        assertEquals(HostEntryDrag(listOf("/home/me/src")), startedLocalState())
        assertEquals(emptySet<String>(), session.state.value.selection)
    }

    @Test
    fun `the selection is asked for when the drag begins, not when the row was drawn`() {
        show(ExplorerView.LIST)
        compose.waitForIdle()
        session.select("/home/me/a.txt")
        session.select("/home/me/b.txt", toggle = true)
        compose.waitForIdle()
        dragWithMouse("a.txt")
        assertEquals(HostEntryDrag(listOf("/home/me/a.txt", "/home/me/b.txt")), startedLocalState())
    }

    @Test
    fun `the secondary button on a row of the selection leaves the selection and starts no drag`() {
        show(ExplorerView.LIST)
        session.select("/home/me/a.txt")
        dragWithMouse("a.txt", MouseButton.Secondary)
        assertNull(startedClip())
        assertEquals(setOf("/home/me/a.txt"), session.state.value.selection)
    }
}
