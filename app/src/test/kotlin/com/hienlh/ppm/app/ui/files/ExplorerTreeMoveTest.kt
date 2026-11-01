package com.hienlh.ppm.app.ui.files

import android.view.DragEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A row of the project tree dragged over the real tree, as Android delivers it: what a drop does and what it refuses, who may
 * drop at all, and where a folder opens by itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class ExplorerTreeMoveTest : ExplorerTreeDragTestBase() {

    @Test fun `a file dropped on a folder moves into it`() {
        show()
        val drag = row("README.md")
        assertTrue(begin(drag))
        over(drag, center("docs"))
        assertTrue(drop(drag, center("docs")))
        assertEquals(listOf("README.md" to "docs/README.md"), moves)
    }

    @Test fun `a folder dropped on a folder moves into it`() {
        show()
        val drag = row("src/util", isDirectory = true)
        assertTrue(begin(drag))
        over(drag, center("docs"))
        assertTrue(drop(drag, center("docs")))
        assertEquals(listOf("src/util" to "docs/util"), moves)
    }

    @Test fun `a file dropped on a file moves into the folder that file is in`() {
        show()
        val drag = row("README.md")
        assertTrue(begin(drag))
        over(drag, center("app.kt"))
        assertTrue(drop(drag, center("app.kt")))
        assertEquals(listOf("README.md" to "src/README.md"), moves)
    }

    @Test fun `a drop under the last row goes to the shared transfer, into the project folder, and not to the tree's own move`() {
        show()
        val drag = row("src/app.kt")
        assertTrue(begin(drag))
        over(drag, blank())
        assertTrue(drop(drag, blank()))
        assertEquals(listOf(Transfer(listOf("$ROOT/src/app.kt"), ROOT, copy = false)), transfers)
        assertEquals(emptyList<Pair<String, String>>(), moves)
    }

    @Test fun `the drop goes where the pointer is when it is let go, not where it last was`() {
        show()
        val drag = row("README.md")
        assertTrue(begin(drag))
        over(drag, center("docs"))
        assertTrue(drop(drag, center("app.kt")))
        assertEquals(listOf("README.md" to "src/README.md"), moves)
    }

    @Test fun `drops the web refuses are not taken, so Android takes the row back, and send nothing`() {
        show()
        val refused = listOf(
            Triple(row("src", isDirectory = true), "src", "onto itself"),
            Triple(row("src", isDirectory = true), "util", "into a folder of its own"),
            Triple(row("src/app.kt"), "src", "onto the folder it is in"),
            Triple(row("src/app.kt"), "main.kt", "onto a file of the folder it is in"),
            Triple(row("README.md"), "README.md", "onto itself"),
        )
        for ((drag, target, what) in refused) {
            assertTrue(begin(drag))
            over(drag, center(target))
            assertFalse("${drag.path} $what", drop(drag, center(target)))
        }
        val inRoot = row("README.md")
        assertTrue(begin(inRoot))
        over(inRoot, blank())
        assertFalse("a row of the root onto the blank area", drop(inRoot, blank()))
        assertEquals(emptyList<Pair<String, String>>(), moves)
        assertEquals(emptyList<Transfer>(), transfers)
    }

    @Test fun `a drag from another project's tree is not taken`() {
        show()
        val other = ExplorerRowDrag("elsewhere", "README.md", false)
        assertFalse(begin(other))
        over(other, center("docs"))
        assertFalse(drop(other, center("docs")))
        assertEquals(emptyList<Pair<String, String>>(), moves)
    }

    @Test fun `a tree with nothing to write with takes no drag`() {
        show(mutations = null)
        assertFalse(begin(row("README.md")))
    }

    @Test fun `a tree whose callbacks are another project's takes no drag`() {
        show(mutations = callbacks(project = "elsewhere"))
        assertFalse(begin(row("README.md")))
    }

    @Test fun `a tree that is busy takes no drag`() {
        show(mutations = callbacks().copy(busy = true))
        assertFalse(begin(row("README.md")))
    }

    // The folder that opens by itself, with the clock in hand: `delay` is the test's virtual time.

    @Test fun `a drag resting on a shut folder opens it after 800 ms and not before`() {
        show()
        compose.mainClock.autoAdvance = false
        val drag = row("README.md")
        begin(drag)
        hover(drag, center("docs"))
        advance(700)
        assertFalse("docs" in expanded())
        advance(200)
        assertTrue("docs" in expanded())
    }

    @Test fun `a drag that leaves a shut folder before the wait is over leaves it shut`() {
        show()
        compose.mainClock.autoAdvance = false
        val drag = row("README.md")
        begin(drag)
        hover(drag, center("docs"))
        advance(500)
        hover(drag, center("app.kt"))
        advance(1000)
        assertFalse("docs" in expanded())
    }

    @Test fun `moving to another shut folder starts the wait again`() {
        show()
        compose.mainClock.autoAdvance = false
        val drag = row("README.md")
        begin(drag)
        hover(drag, center("docs"))
        advance(500)
        hover(drag, center("util"))
        advance(500)
        assertEquals("the first folder's wait is over, the second's is not", setOf("src"), expanded())
        advance(400)
        assertEquals(setOf("src", "src/util"), expanded())
    }

    @Test fun `an open folder or a file does not open on a rest`() {
        show()
        compose.mainClock.autoAdvance = false
        val drag = row("README.md")
        begin(drag)
        hover(drag, center("src"))
        advance(1500)
        hover(drag, center("app.kt"))
        advance(1500)
        assertEquals(setOf("src"), expanded())
    }

    @Test fun `a drop ends the wait`() {
        show()
        compose.mainClock.autoAdvance = false
        val drag = row("README.md")
        begin(drag)
        hover(drag, center("docs"))
        advance(500)
        dispatch(DragEvent.ACTION_DROP, drag, center("docs"))
        dispatch(DragEvent.ACTION_DRAG_ENDED, drag)
        advance(1000)
        assertFalse("docs" in expanded())
    }

    @Test fun `a drag that ends elsewhere ends the wait`() {
        show()
        compose.mainClock.autoAdvance = false
        val drag = row("README.md")
        begin(drag)
        hover(drag, center("docs"))
        advance(500)
        dispatch(DragEvent.ACTION_DRAG_ENDED, drag)
        advance(1000)
        assertFalse("docs" in expanded())
    }

    @Test fun `a pointer that leaves the list ends the wait`() {
        show()
        compose.mainClock.autoAdvance = false
        val drag = row("README.md")
        begin(drag)
        hover(drag, center("docs"))
        advance(500)
        hover(drag, Offset(blank().x, 2f))
        advance(1000)
        assertFalse("docs" in expanded())
    }

    // The list scrolling under a dragged row, in the real tree.

    private fun top(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    @Test fun `a row held within 48 dp of the bottom scrolls the tree's list`() {
        listing[""] = (0 until 80).map { file("f%02d.txt".format(it)) }
        show(expand = emptyList())
        compose.mainClock.autoAdvance = false
        val drag = row("f40.txt")
        begin(drag)
        hover(drag, Offset(blank().x, blank().y + 4f - 12f))
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        val before = top("f05.txt")
        compose.mainClock.advanceTimeByFrame()
        assertEquals("12 dp a frame, 12 dp from the edge", 12f, before - top("f05.txt"), 0.01f)
    }

    private fun scrollingTree(): ExplorerRowDrag {
        listing[""] = (0 until 80).map { file("f%02d.txt".format(it)) }
        show(expand = emptyList())
        compose.mainClock.autoAdvance = false
        val drag = row("f40.txt")
        begin(drag)
        hover(drag, Offset(blank().x, blank().y + 4f - 12f))
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        return drag
    }

    /** What the list moves in the next frame: the position of a row that stays in view. */
    private fun movesInAFrame(): Float {
        val before = top("f05.txt")
        compose.mainClock.advanceTimeByFrame()
        return before - top("f05.txt")
    }

    @Test fun `the list stops scrolling when the drag ends`() {
        val drag = scrollingTree()
        assertEquals(12f, movesInAFrame(), 0.01f)
        dispatch(DragEvent.ACTION_DRAG_ENDED, drag)
        compose.mainClock.advanceTimeByFrame()
        assertEquals(0f, movesInAFrame(), 0.01f)
    }

    @Test fun `the list stops scrolling when the pointer leaves it`() {
        val drag = scrollingTree()
        assertEquals(12f, movesInAFrame(), 0.01f)
        hover(drag, Offset(blank().x, 2f))
        compose.mainClock.advanceTimeByFrame()
        assertEquals(0f, movesInAFrame(), 0.01f)
    }

    @Test fun `the list stops scrolling when the row is dropped`() {
        val drag = scrollingTree()
        assertEquals(12f, movesInAFrame(), 0.01f)
        dispatch(DragEvent.ACTION_DROP, drag, Offset(blank().x, blank().y + 4f - 12f))
        dispatch(DragEvent.ACTION_DRAG_ENDED, drag)
        compose.mainClock.advanceTimeByFrame()
        assertEquals(0f, movesInAFrame(), 0.01f)
    }

    @Test fun `a folder opened by hand while the drag rests on it stays open`() {
        show()
        compose.mainClock.autoAdvance = false
        val drag = row("README.md")
        begin(drag)
        hover(drag, center("docs"))
        advance(400)
        tree.setExpanded("docs", true)
        advance(1000)
        assertTrue("docs" in expanded())
    }

    @Test fun `a folder that scrolls under a held pointer opens after the wait`() {
        // The folder is the last row, so the list stops at it with the pointer over it.
        listing[""] = (0 until 30).map { file("f%02d.txt".format(it)) } + dir("zdir")
        listing["zdir"] = listOf(file("z.kt"))
        show(expand = emptyList())
        compose.mainClock.autoAdvance = false
        val drag = row("f00.txt")
        begin(drag)
        hover(drag, Offset(blank().x, blank().y + 4f - 10f))
        repeat(100) { advance(100) }
        assertTrue("zdir" in expanded())
    }
}
