package com.hienlh.ppm.app.ui.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a drag of a window's own entries carries: the shared payload alone, which is the web's `encodeEntryDrag` for an `explorer` drag
 * ([HostEntryDrag]), and the words on the chip. A project tree's row has its own ([ExplorerTreeDragTest]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HostEntryDragTest {
    @Test fun `the payload is the paths as they are, and where they came from`() {
        assertEquals(
            """{"paths":["/home/me/a.txt","/home/me/docs"],"origin":"explorer"}""",
            explorerEntryPayload(HostEntryDrag(listOf("/home/me/a.txt", "/home/me/docs"))),
        )
    }

    @Test fun `it names no project, which an explorer drag has none of`() {
        assertFalse("projectName" in explorerEntryPayload(HostEntryDrag(listOf("/a"))))
    }

    @Test fun `a Windows path is carried in its own separators`() {
        assertEquals(
            """{"paths":["C:\\Users\\me\\a.txt"],"origin":"explorer"}""",
            explorerEntryPayload(HostEntryDrag(listOf("C:\\Users\\me\\a.txt"))),
        )
    }

    @Test fun `the clip has the shared type and the payload, and no legacy path for the chat to read`() {
        val clip = explorerDragClip(HostEntryDrag(listOf("/home/me/a.txt")))
        assertEquals(listOf(ENTRY_DRAG_MIME), (0 until clip.description.mimeTypeCount).map { clip.description.getMimeType(it) })
        assertEquals(1, clip.itemCount)
        assertEquals("""{"paths":["/home/me/a.txt"],"origin":"explorer"}""", clip.getItemAt(0).text.toString())
        assertFalse(clip.description.hasMimeType(TREE_PATH_MIME))
    }

    @Test fun `the chip says the name of one entry and the count of several`() {
        assertEquals("a.txt", explorerDragLabel(HostEntryDrag(listOf("/home/me/a.txt"))))
        assertEquals("docs", explorerDragLabel(HostEntryDrag(listOf("/home/me/docs"))))
        assertEquals("a.txt", explorerDragLabel(HostEntryDrag(listOf("C:\\Users\\me\\a.txt"))))
        assertEquals("2 items", explorerDragLabel(HostEntryDrag(listOf("/home/me/a.txt", "/home/me/docs"))))
        assertEquals("3 items", explorerDragLabel(HostEntryDrag(listOf("/a", "/b", "/c"))))
    }

    @Test fun `a folder's own name is what the chip says, whether or not its path ends in a separator`() {
        assertEquals("docs", explorerDragLabel(HostEntryDrag(listOf("/home/me/docs/"))))
    }
}
