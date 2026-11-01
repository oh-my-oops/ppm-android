package com.hienlh.ppm.state.files

import org.junit.Assert.*
import org.junit.Test

class FileDropValidationTest {
    @Test fun `tree drop rejects self descendant and same parent without prefix false positives`() {
        assertEquals(FileDropRejection.SELF, validateFileDrop("src/", "src"))
        assertEquals(FileDropRejection.DESCENDANT, validateFileDrop("src", "src/deep/folder"))
        assertEquals(FileDropRejection.SAME_PARENT, validateFileDrop("src/a", "src"))
        assertEquals(FileDropRejection.SAME_PARENT, validateFileDrop("a", ""))
        assertNull(validateFileDrop("src", "src-two"))
        assertEquals("src-two/src", fileDropDestination("src", "src-two"))
        assertEquals("a", fileDropDestination("src/a", ""))
        assertEquals("other/src", fileDropDestination("src/", "other/"))
        assertNull(fileDropDestination("src", "src/deep"))
    }

    @Test fun `drag only accepts canonical project paths and never host paths or dot traversal`() {
        for ((source, target) in listOf("" to "dest", "/a" to "b", "a" to "/", "C:\\a" to "b",
            "a/../b" to "a", "a" to "../b", "a" to "x/./b", "a//b" to "c")) {
            assertEquals("$source → $target", FileDropRejection.INVALID_PATH, validateFileDrop(source, target))
        }
        assertNull(validateFileDrop("a..b", "dir"))
    }
}
