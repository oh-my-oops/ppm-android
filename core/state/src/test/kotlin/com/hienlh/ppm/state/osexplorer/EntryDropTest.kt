package com.hienlh.ppm.state.osexplorer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `decideDrop` (drop-target-decision.ts) and the path guards under it (entry-drag-payload.ts), case for case from the web's own
 * tests: may these paths land in this folder, and as what. A drop is refused silently, in the hover and again at the drop, which
 * is why the web's "Cannot place an item inside itself" toast, behind both, is never shown.
 */
class EntryDropTest {
    private fun decide(paths: List<String> = listOf("/src/a.txt"), target: String? = "/dst", copy: Boolean = false) =
        decideEntryDrop(paths, target, copy)

    private val move = EntryDropDecision.Accept(HostTransferOperation.MOVE)
    private val copy = EntryDropDecision.Accept(HostTransferOperation.COPY)
    private fun refused(reason: EntryDropRefusal) = EntryDropDecision.Reject(reason)

    @Test fun `a null or empty target folder takes nothing`() {
        assertEquals(refused(EntryDropRefusal.NO_TARGET), decide(target = null))
        assertEquals(refused(EntryDropRefusal.NO_TARGET), decide(target = ""))
    }

    @Test fun `the dragged path itself is no target`() {
        assertEquals(refused(EntryDropRefusal.SELF_OR_DESCENDANT), decide(listOf("/src/a"), "/src/a"))
    }

    @Test fun `a folder inside a dragged folder is no target`() {
        assertEquals(refused(EntryDropRefusal.SELF_OR_DESCENDANT), decide(listOf("/src"), "/src/child"))
    }

    @Test fun `the project folder or one of its parents, dragged, is refused, whichever the operation`() {
        // What the web's tree root hears when a window's entry is the project folder (/home/me/p) or a folder above it.
        for (dragged in listOf("/home/me/p", "/home/me", "/home", "/")) {
            assertEquals(dragged, refused(EntryDropRefusal.SELF_OR_DESCENDANT), decide(listOf(dragged), "/home/me/p"))
            assertEquals(dragged, refused(EntryDropRefusal.SELF_OR_DESCENDANT), decide(listOf(dragged), "/home/me/p", copy = true))
        }
    }

    @Test fun `one such path among others refuses the whole drop`() {
        assertEquals(refused(EntryDropRefusal.SELF_OR_DESCENDANT), decide(listOf("/a/x", "/home/me", "/b/y"), "/home/me/p"))
    }

    @Test fun `a move that changes nothing is refused as the same directory`() {
        assertEquals(refused(EntryDropRefusal.SAME_DIRECTORY), decide(listOf("/dst/a.txt"), "/dst"))
    }

    @Test fun `a copy into the folder an entry is in is a duplicate, and taken`() {
        assertEquals(copy, decide(listOf("/dst/a.txt"), "/dst", copy = true))
    }

    @Test fun `a move to another folder is taken, and Ctrl or Alt makes it a copy`() {
        assertEquals(move, decide())
        assertEquals(copy, decide(copy = true))
    }

    @Test fun `a selection is taken unless every entry is in the folder already`() {
        assertEquals(move, decide(listOf("/dst/a", "/other/b"), "/dst"))
        assertEquals(refused(EntryDropRefusal.SAME_DIRECTORY), decide(listOf("/dst/a", "/dst/b"), "/dst"))
    }

    @Test fun `the dragged path itself is a target of no kind`() {
        assertTrue(isSelfOrDescendant(listOf("C:\\a\\b"), "C:\\a\\b"))
    }

    @Test fun `a descendant of a dragged folder is`() {
        assertTrue(isSelfOrDescendant(listOf("C:\\a"), "C:\\a\\b"))
    }

    @Test fun `a sibling, or a folder whose name only starts with the dragged one's, is not`() {
        assertFalse(isSelfOrDescendant(listOf("C:\\a"), "C:\\b"))
        assertFalse(isSelfOrDescendant(listOf("C:\\a"), "C:\\ab"))
        assertFalse(isSelfOrDescendant(listOf("/home/me/p"), "/home/me/p2"))
    }

    @Test fun `the parent of a dragged folder is not, moving up is fine`() {
        assertFalse(isSelfOrDescendant(listOf("C:\\a\\b"), "C:\\a"))
    }

    @Test fun `Windows paths are compared without case, POSIX ones with it`() {
        assertTrue(isSelfOrDescendant(listOf("C:\\Users\\PC"), "c:\\users\\pc\\sub"))
        assertFalse(isSelfOrDescendant(listOf("/Users/pc"), "/users/pc/sub"))
    }

    @Test fun `a UNC path is a Windows path`() {
        assertTrue(isSelfOrDescendant(listOf("\\\\Server\\Share\\Dir"), "\\\\server\\share\\dir\\sub"))
    }

    @Test fun `either separator, and a trailing one, name the same folder`() {
        assertTrue(isSelfOrDescendant(listOf("C:/a/b/"), "C:\\a\\b"))
        assertTrue(isSelfOrDescendant(listOf("/a/b/"), "/a/b/c"))
        assertEquals("a path kept as the web keeps it, with nothing resolved", "/a/b/..", normaliseEntryPath("/a/b/.."))
        assertEquals("c:/users/pc", normaliseEntryPath("C:\\Users\\PC\\"))
        assertEquals("/Users/pc", normaliseEntryPath("/Users/pc//"))
    }

    @Test fun `every path of a selection is checked`() {
        assertTrue(isSelfOrDescendant(listOf("/a", "/b"), "/b/child"))
        assertFalse(isSelfOrDescendant(listOf("/a", "/b"), "/c"))
    }

    @Test fun `every path in a folder, the Windows drive root's included, is already in it`() {
        assertTrue(allAlreadyIn(listOf("/a/one.txt", "/a/two.txt"), "/a"))
        assertFalse(allAlreadyIn(listOf("/a/one.txt", "/b/two.txt"), "/a"))
        assertTrue(allAlreadyIn(listOf("C:\\file.txt"), "C:\\"))
        assertFalse(allAlreadyIn(listOf("C:\\x\\file.txt"), "C:\\"))
    }

    /** The web's own quirk, ported as it is: `/` normalises to "" and the parent of `/file.txt` is spelled `/`, so the two never meet. */
    @Test fun `a file directly under the POSIX root is not seen as already in it`() {
        assertFalse(allAlreadyIn(listOf("/file.txt"), "/"))
        assertEquals(move, decide(listOf("/file.txt"), "/"))
    }
}
