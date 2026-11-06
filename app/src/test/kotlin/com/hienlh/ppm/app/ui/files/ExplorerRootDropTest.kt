package com.hienlh.ppm.app.ui.files

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowWindowManagerGlobal

/**
 * What a drop on the tree's blank area does, which is the web's: the project folder takes the drag whole, through the transfer
 * an explorer window's Paste runs, as absolute paths. Ctrl or Alt held when the row is let go makes it a copy. A drop on a row is
 * not that: it keeps the tree's own move of the one row that was dragged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class ExplorerRootDropTest : ExplorerTreeDragTestBase() {

    private fun dropOnBlank(drag: EntryDrag): Boolean {
        assertTrue(begin(drag))
        over(drag, blank())
        return drop(drag, blank())
    }

    @Test fun `a selection dropped on the blank area goes whole, as absolute paths, in the order it was selected`() {
        show()
        val selection = listOf("src/main.kt", "src/util", "docs/guide.md")
        assertTrue(dropOnBlank(row("src/util", isDirectory = true, paths = selection)))
        assertEquals(listOf(Transfer(selection.map { "$ROOT/$it" }, ROOT, copy = false)), transfers)
    }

    @Test fun `a row of the root dragged with others is taken, for the others are not in the root`() {
        show()
        assertTrue(dropOnBlank(row("README.md", paths = listOf("README.md", "src/app.kt"))))
        assertEquals(listOf("$ROOT/README.md", "$ROOT/src/app.kt"), transfers.single().paths)
    }

    @Test fun `a drag of a window's entries, or of another project's rows with a known root, is taken by the blank area, the header, a file's row and a folder's row`() {
        show()
        val windowDrag = HostEntryDrag(listOf("/elsewhere/x.txt"))
        assertTrue(dropOnBlank(windowDrag))
        assertTrue(dropOnHeader(windowDrag))
        assertTrue(begin(windowDrag))
        over(windowDrag, center("README.md"))
        assertTrue("a file's row hands it to the project folder", drop(windowDrag, center("README.md")))
        assertTrue(begin(windowDrag))
        over(windowDrag, center("docs"))
        assertTrue("a folder's row takes it into that folder", drop(windowDrag, center("docs")))

        val otherProject = ExplorerRowDrag("elsewhere", "src/app.kt", false, listOf("src/app.kt"), "/home/me/p2")
        assertTrue(dropOnBlank(otherProject))
        assertTrue(begin(otherProject))
        over(otherProject, center("docs"))
        assertTrue(drop(otherProject, center("docs")))

        assertEquals(
            listOf(
                Transfer(listOf("/elsewhere/x.txt"), ROOT, copy = false),
                Transfer(listOf("/elsewhere/x.txt"), ROOT, copy = false),
                Transfer(listOf("/elsewhere/x.txt"), ROOT, copy = false),
                Transfer(listOf("/elsewhere/x.txt"), "$ROOT/docs", copy = false),
                Transfer(listOf("/home/me/p2/src/app.kt"), ROOT, copy = false),
                Transfer(listOf("/home/me/p2/src/app.kt"), "$ROOT/docs", copy = false),
            ),
            transfers,
        )
        assertEquals(emptyList<Pair<String, String>>(), moves)
    }

    @Test fun `a folder row refuses a window's drag that is in it already as a move, or is an ancestor of it, and does not hand it on to the project folder`() {
        show()
        val inside = HostEntryDrag(listOf("$ROOT/docs/guide.md"))
        assertTrue(begin(inside))
        over(inside, center("docs"))
        assertFalse(drop(inside, center("docs")))

        val ancestor = HostEntryDrag(listOf(ROOT))
        for (copy in listOf(false, true)) {
            copyHeld = copy
            assertTrue(begin(ancestor))
            over(ancestor, center("docs"))
            assertFalse("ancestor, copy=$copy", drop(ancestor, center("docs")))
        }
        assertEquals(emptyList<Transfer>(), transfers)

        copyHeld = true
        assertTrue(begin(inside))
        over(inside, center("docs"))
        assertTrue("a copy in the same folder is taken", drop(inside, center("docs")))
        assertEquals(listOf(Transfer(listOf("$ROOT/docs/guide.md"), "$ROOT/docs", copy = true)), transfers)
    }

    @Test fun `a window's drag resting 800 ms on a shut folder opens it when the folder would take the drop, and not when it would refuse`() {
        show()
        compose.mainClock.autoAdvance = false
        val refused = HostEntryDrag(listOf("$ROOT/docs/guide.md"))
        assertTrue(begin(refused))
        hover(refused, center("docs"))
        advance(2_000)
        assertFalse("refused move does not spring-load", "docs" in expanded())
        dispatch(android.view.DragEvent.ACTION_DRAG_ENDED, refused)
        compose.mainClock.advanceTimeByFrame()

        val taken = HostEntryDrag(listOf("/elsewhere/x.txt"))
        assertTrue(begin(taken))
        hover(taken, center("docs"))
        advance(700)
        assertFalse("not yet at 700 ms", "docs" in expanded())
        advance(200)
        assertTrue("opened after 800 ms", "docs" in expanded())
    }

    @Test fun `the root drop modifier wraps the header as well as the list`() {
        show(content = { tree, commands ->
            ExplorerTree(tree, onOpenFile = {}, mutations = commands, entryDrops = drops, dropModifier = { dir -> Modifier.testTag("drop:$dir") })
        })
        val bounds = compose.onAllNodesWithTag("drop:")[0].fetchSemanticsNode().boundsInRoot
        assertTrue("covers the header", bounds.contains(header()))
        assertTrue("covers the blank area", bounds.contains(blank()))
    }

    private fun dropOnHeader(drag: EntryDrag): Boolean {
        assertTrue(begin(drag))
        over(drag, header())
        return drop(drag, header())
    }

    @Test fun `a drop on the header is taken as one on the blank area is, the web's root target being the whole tree`() {
        show()
        assertTrue(dropOnHeader(row("src/app.kt")))
        assertEquals(listOf(Transfer(listOf("$ROOT/src/app.kt"), ROOT, copy = false)), transfers)
        assertEquals("not the move of a row onto a row", emptyList<Pair<String, String>>(), moves)
    }

    @Test fun `a selection dropped on the header goes whole, and Ctrl or Alt makes it a copy there too`() {
        show()
        val selection = listOf("src/main.kt", "src/util", "docs/guide.md")
        assertTrue(dropOnHeader(row("src/util", isDirectory = true, paths = selection)))
        copyHeld = true
        assertTrue(dropOnHeader(row("README.md")))
        assertEquals(
            listOf(Transfer(selection.map { "$ROOT/$it" }, ROOT, copy = false), Transfer(listOf("$ROOT/README.md"), ROOT, copy = true)),
            transfers,
        )
    }

    @Test fun `the header refuses what the blank area does, a move of rows that are in the root already`() {
        show()
        assertFalse(dropOnHeader(row("README.md", paths = listOf("README.md", "docs"))))
        assertEquals(emptyList<Transfer>(), transfers)
    }

    @Test fun `the header takes no drag of another project, and none while a drop is carried out`() {
        show()
        val elsewhere = ExplorerRowDrag("elsewhere", "src/app.kt", false)
        assertFalse(begin(elsewhere))

        transferHeld = CompletableDeferred()
        assertTrue(dropOnHeader(row("src/app.kt")))
        compose.waitForIdle()
        assertFalse("busy", begin(row("src/main.kt")))
        compose.runOnUiThread { transferHeld!!.complete(Unit) }
        compose.waitForIdle()
        assertEquals(1, transfers.size)
    }

    @Test fun `a tree with no shared transfer takes nothing on the header either`() {
        show(content = { tree, commands -> ExplorerTree(tree, onOpenFile = {}, mutations = commands) })
        val drag = row("src/app.kt")
        assertTrue(begin(drag))
        over(drag, header())
        assertFalse(drop(drag, header()))
        assertEquals(emptyList<Transfer>(), transfers)
    }

    @Test fun `a point just above the list is the project folder, though the first row, scrolled half out of sight, is laid out there`() {
        listing[""] = (0 until 40).map { dir("d%02d".format(it)) }
        show(expand = emptyList())
        compose.runOnUiThread { compose.onNode(hasScrollAction()).fetchSemanticsNode().config[SemanticsActions.ScrollBy].action!!.invoke(0f, 13f) }
        compose.waitForIdle()
        val above = Offset(blank().x, compose.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot.top - 5f)
        copyHeld = true
        val drag = row("d30", isDirectory = true)
        assertTrue(begin(drag))
        over(drag, above)
        assertTrue(drop(drag, above))
        assertEquals(listOf(Transfer(listOf("$ROOT/d30"), ROOT, copy = true)), transfers)
        assertEquals("not a move into the row that is half in sight", emptyList<Pair<String, String>>(), moves)
    }

    @Test fun `the list's own first pixel is still the row there, not the project folder`() {
        listing[""] = (0 until 40).map { dir("d%02d".format(it)) }
        show(expand = emptyList())
        compose.runOnUiThread { compose.onNode(hasScrollAction()).fetchSemanticsNode().config[SemanticsActions.ScrollBy].action!!.invoke(0f, 13f) }
        compose.waitForIdle()
        val top = Offset(blank().x, compose.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot.top)
        val drag = row("d30", isDirectory = true)
        assertTrue(begin(drag))
        over(drag, top)
        assertTrue(drop(drag, top))
        assertEquals(listOf("d30" to "d00/d30"), moves)
        assertEquals(emptyList<Transfer>(), transfers)
    }

    @Test fun `the project folder, or a folder above it, dragged onto the tree is refused, on the blank area and on the header, and nothing is sent`() {
        show()
        for (copy in listOf(false, true)) {
            copyHeld = copy
            for (dragged in listOf(ROOT, "/home/me", "/home")) {
                val drag = entries(dragged)
                assertTrue(dragged, begin(drag))
                over(drag, blank())
                assertFalse("$dragged on the blank area", drop(drag, blank()))
                assertTrue(begin(drag))
                over(drag, header())
                assertFalse("$dragged on the header", drop(drag, header()))
            }
        }
        assertEquals(emptyList<Transfer>(), transfers)
        assertFalse("the tree is free after a refusal", drops.running)
    }

    @Test fun `one such entry among others refuses the whole drag, and entries beside the project folder are taken`() {
        show()
        assertFalse(dropOnBlank(entries("$ROOT/src/app.kt", "/home/me")))
        assertTrue(dropOnBlank(entries("/home/me/other/app.kt", "/home/me/p2")))
        assertEquals(listOf(Transfer(listOf("/home/me/other/app.kt", "/home/me/p2"), ROOT, copy = false)), transfers)
    }

    @Test fun `a selection that is in the root already is refused as a move`() {
        show()
        assertFalse(dropOnBlank(row("README.md", paths = listOf("README.md", "docs"))))
        assertEquals(emptyList<Transfer>(), transfers)
    }

    @Test fun `Ctrl or Alt held at the drop makes it a copy`() {
        show()
        copyHeld = true
        assertTrue(dropOnBlank(row("src/app.kt")))
        assertEquals(listOf(Transfer(listOf("$ROOT/src/app.kt"), ROOT, copy = true)), transfers)
    }

    @Test fun `a copy of rows that are in the root already is taken, as every OS makes name (2) of it`() {
        show()
        copyHeld = true
        assertTrue(dropOnBlank(row("README.md")))
        assertEquals(listOf(Transfer(listOf("$ROOT/README.md"), ROOT, copy = true)), transfers)
    }

    @Test fun `the keys are read when the row is let go, not when the drag began or last moved`() {
        show()
        val drag = row("src/app.kt")
        copyHeld = true
        assertTrue(begin(drag))
        over(drag, blank())
        copyHeld = false
        assertTrue(drop(drag, blank()))
        assertFalse("a move: the key was let go before the drop", transfers.single().copy)

        copyHeld = false
        assertTrue(begin(drag))
        over(drag, blank())
        copyHeld = true
        assertTrue(drop(drag, blank()))
        assertTrue("a copy: the key went down after the last move", transfers.last().copy)
    }

    @Test fun `a drop on a row moves only the row that was dragged, and a key held does not make it a copy`() {
        show()
        copyHeld = true
        val drag = row("README.md", paths = listOf("README.md", "src/main.kt"))
        assertTrue(begin(drag))
        over(drag, center("docs"))
        assertTrue(drop(drag, center("docs")))
        assertEquals(listOf("README.md" to "docs/README.md"), moves)
        assertEquals(emptyList<Transfer>(), transfers)
    }

    @Test fun `a tree with no shared transfer takes nothing on the blank area, and still moves a row onto a row`() {
        show(content = { tree, commands -> ExplorerTree(tree, onOpenFile = {}, mutations = commands) })
        assertFalse(begin(HostEntryDrag(listOf("/elsewhere/x.txt"))))
        assertFalse(begin(ExplorerRowDrag("elsewhere", "src/app.kt", false, listOf("src/app.kt"), "/home/me/p2")))
        val drag = row("src/app.kt")
        assertTrue(begin(drag))
        over(drag, blank())
        assertFalse(drop(drag, blank()))
        assertEquals(emptyList<Pair<String, String>>(), moves)

        assertTrue(begin(drag))
        over(drag, center("docs"))
        assertTrue(drop(drag, center("docs")))
        assertEquals(listOf("src/app.kt" to "docs/app.kt"), moves)
    }

    @Test fun `a drop being carried out keeps the tree busy, and it takes no other until that is done`() {
        show()
        transferHeld = CompletableDeferred()
        val drag = row("src/app.kt")
        assertTrue(dropOnBlank(drag))
        compose.waitForIdle()
        assertTrue(drops.running)
        assertFalse("a second drag while the first is carried out", begin(drag))

        compose.runOnUiThread { transferHeld!!.complete(Unit) }
        compose.waitForIdle()
        assertFalse(drops.running)
        assertTrue("and once it is done", begin(drag))
    }

    @Test fun `while a drop is carried out the blank area is asked to take no other, and takes none`() {
        show()
        transferHeld = CompletableDeferred()
        val drag = row("src/app.kt")
        assertTrue("free, it would", drops.takes(drag))
        assertTrue(dropOnBlank(drag))
        compose.waitForIdle()
        assertTrue(drops.running)
        assertFalse("it would not take a drag now", drops.takes(drag))
        assertFalse("and is not given one", drops.dropOnRoot(drag))
        assertEquals("the second drop never reached the transfer", 1, transfers.size)

        compose.runOnUiThread { transferHeld!!.complete(Unit) }
        compose.waitForIdle()
        assertTrue("done, it takes one again", drops.takes(drag))
    }

    @Test fun `a row dragged in the real tree carries its path under the project folder the tree was given`() {
        show()
        ShadowWindowManagerGlobal.clearLastDragClipData()
        compose.onNodeWithText("app.kt").performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(0f, 40f))
            moveBy(Offset(0f, 40f))
            release()
        }
        compose.waitForIdle()
        val clip = ShadowWindowManagerGlobal.getLastDragClipData()!!
        assertEquals("src/app.kt", clip.getItemAt(0).text)
        assertEquals("""{"paths":["/home/me/p/src/app.kt"],"origin":"tree","projectName":"p"}""", clip.getItemAt(1).text.toString())
    }

    @Test fun `a drop that is cancelled leaves the tree free for the next`() {
        show()
        transferHeld = CompletableDeferred()
        assertTrue(dropOnBlank(row("src/app.kt")))
        compose.runOnUiThread { transferHeld!!.cancel() }
        compose.waitForIdle()
        assertFalse(drops.running)
        transferHeld = null
        assertTrue(dropOnBlank(row("src/main.kt")))
        assertEquals(2, transfers.size)
    }
}
