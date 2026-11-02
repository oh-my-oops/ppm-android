package com.hienlh.ppm.state.osexplorer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `encodeEntryDrag` (entry-drag-payload.ts): what a drag of entries carries, as the web's `JSON.stringify` spells it, because every
 * drop target of PPM, the web's and this app's, reads the same bytes.
 */
class EntryDragPayloadTest {
    @Test fun `a drag from an explorer window is its paths and where it began`() {
        assertEquals(
            """{"paths":["/home/me/a.txt","/home/me/b"],"origin":"explorer"}""",
            EntryDragPayload(listOf("/home/me/a.txt", "/home/me/b"), EntryDragOrigin.EXPLORER).toJson(),
        )
    }

    @Test fun `a drag from a project tree names the project, last`() {
        assertEquals(
            """{"paths":["/home/me/p/src/app.kt"],"origin":"tree","projectName":"p"}""",
            EntryDragPayload(listOf("/home/me/p/src/app.kt"), EntryDragOrigin.TREE, "p").toJson(),
        )
    }

    @Test fun `an empty project name is none`() {
        assertEquals("""{"paths":["/a"],"origin":"tree"}""", EntryDragPayload(listOf("/a"), EntryDragOrigin.TREE, "").toJson())
        assertEquals("""{"paths":["/a"],"origin":"tree"}""", EntryDragPayload(listOf("/a"), EntryDragOrigin.TREE, null).toJson())
    }

    @Test fun `a Windows path has its backslashes escaped, as JSON does`() {
        assertEquals(
            """{"paths":["C:\\Users\\me\\p\\a.txt"],"origin":"explorer"}""",
            EntryDragPayload(listOf("C:\\Users\\me\\p\\a.txt"), EntryDragOrigin.EXPLORER).toJson(),
        )
    }

    @Test fun `the origin is spelled as the web spells it`() {
        assertEquals("explorer", EntryDragOrigin.EXPLORER.wire)
        assertEquals("tree", EntryDragOrigin.TREE.wire)
    }
}
