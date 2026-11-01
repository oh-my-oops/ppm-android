package com.hienlh.ppm.app

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tab a file chip opens (`FilePathChip` in the web's `message-user-bubble.tsx`). */
class FilePathTabTest {
    @Test
    fun `a chip opens an editor titled by the file's name that belongs to no project`() {
        val tab = filePathTab("/home/me/ppm/src/main.ts", "ppm")
        assertEquals("editor", tab.type)
        assertEquals("main.ts", tab.title)
        assertNull(tab.projectId)
        assertTrue(tab.closable)
    }

    @Test
    fun `the project the chip was shown in is named in the metadata beside the path`() {
        assertEquals(
            mapOf("filePath" to JsonPrimitive("/home/me/ppm/src/main.ts"), "projectName" to JsonPrimitive("ppm")),
            filePathTab("/home/me/ppm/src/main.ts", "ppm").metadata.toMap(),
        )
    }

    @Test
    fun `no project, or an empty one, leaves the name out as the web does`() {
        val only = mapOf("filePath" to JsonPrimitive("/tmp/out.txt"))
        assertEquals(only, filePathTab("/tmp/out.txt", null).metadata.toMap())
        assertEquals(only, filePathTab("/tmp/out.txt", "").metadata.toMap())
    }

    @Test
    fun `the title is the last part of either kind of path, as the web's basename gives it`() {
        assertEquals("notes.md", filePathTab("""C:\Users\me\ppm\notes.md""", null).title)
        assertEquals("notes.md", filePathTab("""docs/a\b/notes.md""", null).title)
        assertEquals("notes.md", filePathTab("notes.md", null).title)
        assertEquals("", filePathTab("/home/me/ppm/", null).title)
    }
}
