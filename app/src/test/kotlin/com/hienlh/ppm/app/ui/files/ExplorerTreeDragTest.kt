package com.hienlh.ppm.app.ui.files

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The decisions behind dragging a row of the project tree: who may start one, how it scrolls the list, what it lights. */
class ExplorerTreeDragTest {
    @Test fun `no step outside the 48 dp zone and the full 16 at the edge`() {
        assertEquals(0, explorerEdgeStep(48.0))
        assertEquals(0, explorerEdgeStep(300.0))
        assertEquals(16, explorerEdgeStep(0.0))
        assertEquals(16, explorerEdgeStep(1.0))
    }

    @Test fun `the step is the ceiling of the intensity times 16`() {
        // (distance from the edge, step): the web's `Math.ceil(intensity * 16)`, intensity = (48 - distance) / 48.
        for ((distance, step) in listOf(3.0 to 15, 12.0 to 12, 24.0 to 8, 36.0 to 4, 45.0 to 1, 46.0 to 1, 47.0 to 1, 47.9 to 1)) {
            assertEquals("$distance dp from the edge", step, explorerEdgeStep(distance))
        }
    }

    @Test fun `a pointer beyond the edge scrolls at the full step`() {
        assertEquals(16, explorerEdgeStep(-10.0))
        assertEquals(16, explorerEdgeStep(-500.0))
    }

    @Test fun `near the bottom scrolls down, near the top up`() {
        assertEquals(12, explorerAutoScrollStep(fromTop = 200.0, fromBottom = 12.0))
        assertEquals(-12, explorerAutoScrollStep(fromTop = 12.0, fromBottom = 200.0))
        assertEquals(0, explorerAutoScrollStep(fromTop = 200.0, fromBottom = 200.0))
    }

    @Test fun `a list shorter than both zones scrolls by the difference`() {
        // 6 for 30 dp from the bottom, 13 for 10 dp from the top: the web's `down - up`.
        assertEquals(-7, explorerAutoScrollStep(fromTop = 10.0, fromBottom = 30.0))
        assertEquals(7, explorerAutoScrollStep(fromTop = 30.0, fromBottom = 10.0))
        assertEquals(0, explorerAutoScrollStep(fromTop = 20.0, fromBottom = 20.0))
    }

    @Test fun `the pointer's distance is measured from the list's own edges in dp`() {
        val area = Rect(0f, 100f, 300f, 500f)
        assertEquals(13, explorerPointerScrollStep(Offset(10f, 490f), area, density = 1f))
        assertEquals(-13, explorerPointerScrollStep(Offset(10f, 110f), area, density = 1f))
        assertEquals(0, explorerPointerScrollStep(Offset(10f, 300f), area, density = 1f))
        // 24 px from the bottom is 24 dp at 1x and 12 dp at 2x.
        assertEquals(8, explorerPointerScrollStep(Offset(10f, 476f), area, density = 1f))
        assertEquals(12, explorerPointerScrollStep(Offset(10f, 476f), area, density = 2f))
    }

    @Test fun `no pointer or no list means no scroll`() {
        assertEquals(0, explorerPointerScrollStep(Offset.Unspecified, Rect(0f, 0f, 100f, 100f), density = 1f))
        assertEquals(0, explorerPointerScrollStep(Offset(10f, 99f), null, density = 1f))
    }

    @Test fun `only a mouse's primary button starts a drag`() {
        assertTrue(explorerDragStarts(PointerType.Mouse, primaryPressed = true))
        assertFalse(explorerDragStarts(PointerType.Mouse, primaryPressed = false))
        for (type in listOf(PointerType.Touch, PointerType.Stylus, PointerType.Eraser, PointerType.Unknown)) {
            assertFalse("$type", explorerDragStarts(type, primaryPressed = true))
        }
    }

    private fun drag(path: String, vararg more: String, isDirectory: Boolean = false, root: String? = "/home/me/p") =
        ExplorerRowDrag("p", path, isDirectory, listOf(path) + more, root)

    @Test fun `a move to the project folder is taken unless every row is in it already`() {
        assertTrue(explorerRootDropTaken(drag("src/app.kt"), copy = false))
        assertTrue(explorerRootDropTaken(drag("src/util", isDirectory = true), copy = false))
        assertTrue("one row below the root is enough", explorerRootDropTaken(drag("README.md", "src/app.kt"), copy = false))
        assertFalse(explorerRootDropTaken(drag("README.md"), copy = false))
        assertFalse(explorerRootDropTaken(drag("src", isDirectory = true), copy = false))
        assertFalse(explorerRootDropTaken(drag("README.md", "docs"), copy = false))
    }

    @Test fun `a copy to the project folder is always taken, for it makes name (2) of what is there`() {
        assertTrue(explorerRootDropTaken(drag("README.md"), copy = true))
        assertTrue(explorerRootDropTaken(drag("README.md", "docs"), copy = true))
        assertTrue(explorerRootDropTaken(drag("src/app.kt"), copy = true))
    }

    @Test fun `the chip says the name of one row and the count of several`() {
        assertEquals("app.kt", explorerDragLabel(drag("src/app.kt")))
        assertEquals("util", explorerDragLabel(drag("src/util", isDirectory = true)))
        assertEquals("README.md", explorerDragLabel(drag("README.md")))
        assertEquals("2 items", explorerDragLabel(drag("a", "b/c")))
        assertEquals("3 items", explorerDragLabel(drag("a", "b/c", "d")))
    }

    @Test fun `the payload is the web's, the absolute paths under the project folder, from a tree and of a project`() {
        assertEquals(
            """{"paths":["/home/me/p/src/app.kt"],"origin":"tree","projectName":"p"}""",
            explorerEntryPayload(drag("src/app.kt")),
        )
        assertEquals(
            """{"paths":["/home/me/p/README.md","/home/me/p/src/app.kt"],"origin":"tree","projectName":"p"}""",
            explorerEntryPayload(drag("README.md", "src/app.kt")),
        )
    }

    @Test fun `a Windows project's paths are under its folder in its own separators`() {
        assertEquals(
            """{"paths":["C:\\Users\\me\\p\\src\\app.kt"],"origin":"tree","projectName":"p"}""",
            explorerEntryPayload(drag("src/app.kt", root = "C:\\Users\\me\\p")),
        )
    }

    @Test fun `a tree that does not know the project folder carries the paths as they are, as the web does`() {
        assertEquals(
            """{"paths":["src/app.kt","docs"],"origin":"tree","projectName":"p"}""",
            explorerEntryPayload(drag("src/app.kt", "docs", root = null)),
        )
    }

    @Test fun `the payload names no project for a drag that has none`() {
        assertEquals(
            """{"paths":["/home/me/p/a.kt"],"origin":"tree"}""",
            explorerEntryPayload(ExplorerRowDrag("", "a.kt", false, listOf("a.kt"), "/home/me/p")),
        )
    }
}
