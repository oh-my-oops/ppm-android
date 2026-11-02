package com.hienlh.ppm.app.ui.osexplorer

import com.hienlh.ppm.app.ui.files.EntryDrag
import com.hienlh.ppm.app.ui.files.ExplorerDropSite
import com.hienlh.ppm.app.ui.files.ExplorerRowDrag
import com.hienlh.ppm.app.ui.files.HostEntryDrag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a window does with a drag of entries, which is `useEntryDropTarget` over `decideDrop`: the drag is one it takes at all, it
 * would be taken on a folder now, and the drop is the transfer. The pointer, the rows and the timers are the views' and are tested there.
 */
class HostEntryDropsTest {
    private var busy = false
    private var copy = false
    private val transfers = mutableListOf<Triple<List<String>, String, Boolean>>()
    private val drops = HostEntryDrops({ busy }, { paths, directory, asCopy -> transfers += Triple(paths, directory, asCopy) }, { copy })

    private fun entries(vararg paths: String) = HostEntryDrag(paths.toList())

    private fun on(drag: EntryDrag, directory: String) = ExplorerDropSite(drag, directory, directory, collapsedFolder = false)

    private fun treeRows(root: String?, vararg paths: String) = ExplorerRowDrag("p", paths.first(), false, paths.toList(), root)

    @Test
    fun `entries of a window are taken`() {
        assertTrue(drops.accepts(entries("/home/me/a.txt")))
    }

    @Test
    fun `rows of a project tree are taken once the tree knows its folder, which is what makes their paths absolute`() {
        assertTrue(drops.accepts(treeRows("/home/me/p", "src/app.kt")))
    }

    @Test
    fun `rows of a tree that does not know its folder are not, their paths being relative to nothing a window can write to`() {
        assertFalse(drops.accepts(treeRows(null, "src/app.kt")))
    }

    @Test
    fun `nothing is taken while a transfer is running, and it is taken again when it is over`() {
        busy = true
        assertFalse(drops.accepts(entries("/home/me/a.txt")))
        assertFalse(drops.takes(on(entries("/elsewhere/a.txt"), "/home/me")))
        assertFalse(drops.drop(entries("/elsewhere/a.txt"), on(entries("/elsewhere/a.txt"), "/home/me")))
        assertEquals(emptyList<Any>(), transfers)

        busy = false
        assertTrue(drops.accepts(entries("/home/me/a.txt")))
    }

    @Test
    fun `a folder takes what is elsewhere, as a move`() {
        val drag = entries("/elsewhere/a.txt")
        assertTrue(drops.takes(on(drag, "/home/me")))
    }

    @Test
    fun `a folder does not take, as a move, what is in it already`() {
        val drag = entries("/home/me/a.txt", "/home/me/b.txt")
        assertFalse(drops.takes(on(drag, "/home/me")))
    }

    @Test
    fun `with Ctrl or Alt held what is in the folder already is taken, as a copy`() {
        copy = true
        assertTrue(drops.takes(on(entries("/home/me/a.txt"), "/home/me")))
    }

    @Test
    fun `a folder does not take itself, or a folder that holds it, with the keys or without`() {
        val drag = entries("/home/me/docs")
        assertFalse(drops.takes(on(drag, "/home/me/docs")))
        assertFalse(drops.takes(on(drag, "/home/me/docs/deeper")))
        copy = true
        assertFalse(drops.takes(on(drag, "/home/me/docs")))
        assertFalse(drops.takes(on(drag, "/home/me/docs/deeper")))
    }

    @Test
    fun `a drag with a part that is in the folder already is taken whole, as the web decides on all the paths together`() {
        assertTrue(drops.takes(on(entries("/home/me/a.txt", "/elsewhere/b.txt"), "/home/me")))
    }

    @Test
    fun `the drop is the transfer of the drag's paths into the folder, as a move`() {
        val drag = entries("/elsewhere/a.txt", "/elsewhere/b")
        assertTrue(drops.drop(drag, on(drag, "/home/me/docs")))
        assertEquals(listOf(Triple(listOf("/elsewhere/a.txt", "/elsewhere/b"), "/home/me/docs", false)), transfers)
    }

    @Test
    fun `the keys are read when it is let go, not when it began or came over the folder`() {
        val drag = entries("/elsewhere/a.txt")
        copy = false
        assertTrue(drops.takes(on(drag, "/home/me")))
        copy = true
        assertTrue(drops.drop(drag, on(drag, "/home/me")))
        assertEquals(listOf(Triple(listOf("/elsewhere/a.txt"), "/home/me", true)), transfers)
    }

    @Test
    fun `a drop that is refused transfers nothing and says so, which hands the entries back`() {
        val drag = entries("/home/me/a.txt")
        assertFalse(drops.drop(drag, on(drag, "/home/me")))
        assertFalse(drops.drop(entries("/home/me/docs"), on(drag, "/home/me/docs")))
        assertEquals(emptyList<Any>(), transfers)
    }

    @Test
    fun `a tree's rows are transferred by their absolute paths, under the project's folder`() {
        val drag = treeRows("/home/me/p", "src/app.kt", "docs")
        assertTrue(drops.drop(drag, on(drag, "/home/me/other")))
        assertEquals(listOf(Triple(listOf("/home/me/p/src/app.kt", "/home/me/p/docs"), "/home/me/other", false)), transfers)
    }

    @Test
    fun `rows of a tree that does not know its folder are refused on a drop, though the folder would take what they say`() {
        val drag = treeRows(null, "src/app.kt")
        assertFalse(drops.takes(on(drag, "/home/me")))
        assertFalse(drops.drop(drag, on(drag, "/home/me")))
        assertEquals(emptyList<Any>(), transfers)
    }
}
