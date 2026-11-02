package com.hienlh.ppm.app.ui.osexplorer

import com.hienlh.ppm.app.DragModifierKeys
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.state.osexplorer.ExplorerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the List and the Icons views do with entries dragged over them, as the web does (`list-view.tsx`, `icons-view.tsx`, `use-entry-row-dnd.ts`,
 * `use-entry-drop-target.ts`): a folder's row or tile takes them into the folder, a file's row or tile and the space around the rows
 * are the folder the view shows, and what the folder refuses is not taken, nothing said. Each view runs these with its own rows.
 */
internal abstract class HostEntriesViewDropTestBase(private val view: ExplorerView) : HostEntryDndTestBase() {
    @Test
    fun `entries let go on a folder's row are moved into that folder, and the window says Moved`() {
        show(view)
        assertTrue(begin(entries("/elsewhere/x.txt")))
        hover(center("docs"))
        assertTrue(letGo(center("docs")))
        assertEquals(listOf("move /elsewhere/x.txt -> /home/me/docs/x.txt"), transfers.toList())
        assertEquals(listOf("Moved 1 item"), titles())
    }

    @Test
    fun `entries let go on a file's row are moved into the folder the view shows, which is where the file is`() {
        show(view)
        begin(entries("/elsewhere/x.txt"))
        hover(center("a.txt"))
        assertTrue(letGo(center("a.txt")))
        assertEquals(listOf("move /elsewhere/x.txt -> /home/me/x.txt"), transfers.toList())
    }

    @Test
    fun `entries let go on the space below the rows are moved into the folder the view shows`() {
        show(view)
        begin(entries("/elsewhere/x.txt"))
        hover(below())
        assertTrue(letGo(below()))
        assertEquals(listOf("move /elsewhere/x.txt -> /home/me/x.txt"), transfers.toList())
        assertEquals(listOf("Moved 1 item"), titles())
    }

    @Test
    fun `a drag of two entries moves both, in the order they were dragged, and says how many`() {
        show(view)
        begin(entries("/elsewhere/y.txt", "/elsewhere/x.txt"))
        hover(center("src"))
        assertTrue(letGo(center("src")))
        assertEquals(listOf("move /elsewhere/y.txt -> /home/me/src/y.txt", "move /elsewhere/x.txt -> /home/me/src/x.txt"), transfers.toList())
        assertEquals(listOf("Moved 2 items"), titles())
    }

    @Test
    fun `with Ctrl down when it is let go the drop is a copy, and the window says Copied`() {
        show(view)
        begin(entries("/elsewhere/x.txt"))
        hover(center("docs"))
        DragModifierKeys.update(ctrl = true, alt = false)
        assertTrue(letGo(center("docs")))
        assertEquals(listOf("copy /elsewhere/x.txt -> /home/me/docs/x.txt"), transfers.toList())
        assertEquals(listOf("Copied 1 item"), titles())
    }

    @Test
    fun `Alt is a copy as well`() {
        show(view)
        begin(entries("/elsewhere/x.txt"))
        hover(below())
        DragModifierKeys.update(ctrl = false, alt = true)
        assertTrue(letGo(below()))
        assertEquals(listOf("copy /elsewhere/x.txt -> /home/me/x.txt"), transfers.toList())
    }

    @Test
    fun `entries that are in the folder already are not taken by the space below, and nothing is said of it`() {
        show(view)
        assertTrue("the view takes the drag; it is the folder that refuses what is let go", begin(entries("/home/me/a.txt")))
        hover(below())
        assertFalse(letGo(below()))
        assertEquals(emptyList<String>(), transfers.toList())
        assertEquals(emptyList<String>(), titles())
    }

    @Test
    fun `nor by a file's row, which is the same folder`() {
        show(view)
        begin(entries("/home/me/a.txt"))
        hover(center("b.txt"))
        assertFalse(letGo(center("b.txt")))
        assertEquals(emptyList<String>(), transfers.toList())
        assertEquals(emptyList<String>(), titles())
    }

    @Test
    fun `with Ctrl down the same entries are copied into the folder, under a numbered name, as every OS does`() {
        show(view)
        begin(entries("/home/me/a.txt"))
        hover(below())
        DragModifierKeys.update(ctrl = true, alt = false)
        assertTrue(letGo(below()))
        assertEquals(listOf("copy /home/me/a.txt -> /home/me/a (2).txt"), transfers.toList())
    }

    @Test
    fun `a folder let go on itself is refused`() {
        show(view)
        begin(entries("/home/me/docs"))
        hover(center("docs"))
        assertFalse(letGo(center("docs")))
        assertEquals(emptyList<String>(), transfers.toList())
        assertEquals(emptyList<String>(), titles())
    }

    @Test
    fun `a folder that refuses is the target all the same, so what it refuses is not handed on to the folder behind it`() {
        show(view)
        // Out of docs and into /home/me is a move the view's own folder would take, were it asked; docs holds it already and refuses.
        begin(entries("/home/me/docs/guide.md"))
        hover(center("docs"))
        assertFalse(letGo(center("docs")))
        assertEquals(emptyList<String>(), transfers.toList())

        begin(entries("/home/me/docs/guide.md"))
        hover(center("a.txt"))
        assertTrue("the same drag on a file's row is the view's own folder's, and it takes it", letGo(center("a.txt")))
        assertEquals(listOf("move /home/me/docs/guide.md -> /home/me/guide.md"), transfers.toList())
    }

    @Test
    fun `rows of a project tree are moved by their absolute paths`() {
        show(view)
        assertTrue(begin(treeRows("src/app.kt")))
        hover(center("docs"))
        assertTrue(letGo(center("docs")))
        assertEquals(listOf("move /home/me/p/src/app.kt -> /home/me/docs/app.kt"), transfers.toList())
    }

    @Test
    fun `rows of a tree that does not know its folder are not taken by anything`() {
        show(view)
        assertFalse(begin(treeRows("src/app.kt", root = null)))
    }

    @Test
    fun `a drag that began in another app is no drag of entries`() {
        show(view)
        driver.local = null
        assertFalse(driver.begin())
    }

    @Test
    fun `a window on a phone takes no drag, as the web's takes none on touch`() {
        show(view, mobile = true)
        assertFalse(begin(entries("/elsewhere/x.txt")))
    }

    @Test
    fun `a window with no commands takes no drag`() {
        show(view, withCommands = false)
        assertFalse(begin(entries("/elsewhere/x.txt")))
    }

    @Test
    fun `once the window has gone into another folder, the space under the rows is that folder`() {
        show(view)
        compose.runOnIdle { session.go("/home/me/docs") }
        compose.waitUntil(10_000) { session.state.value.currentDirectory == "/home/me/docs" }
        compose.waitForIdle()
        begin(entries("/elsewhere/x.txt"))
        hover(below())
        assertTrue(letGo(below()))
        assertEquals(listOf("move /elsewhere/x.txt -> /home/me/docs/x.txt"), transfers.toList())
    }

    @Test
    fun `a window that loses its commands stops taking drags, and takes them again when they are back`() {
        show(view)
        assertTrue(begin(entries("/elsewhere/x.txt")))
        driver.end()
        compose.runOnIdle { commandsAvailable.value = false }
        compose.waitForIdle()
        assertFalse(begin(entries("/elsewhere/x.txt")))
        compose.runOnIdle { commandsAvailable.value = true }
        compose.waitForIdle()
        assertTrue(begin(entries("/elsewhere/x.txt")))
        hover(center("docs"))
        assertTrue(letGo(center("docs")))
        assertEquals(listOf("move /elsewhere/x.txt -> /home/me/docs/x.txt"), transfers.toList())
    }

    @Test
    fun `a window that is given other commands drops through those, not the ones it was drawn with`() {
        show(view)
        val said = mutableListOf<ErrorToastMessage>()
        compose.runOnIdle { replacement.value = otherCommands(said) }
        compose.waitForIdle()
        begin(entries("/elsewhere/x.txt"))
        hover(center("docs"))
        assertTrue(letGo(center("docs")))
        assertEquals(listOf("move /elsewhere/x.txt -> /home/me/docs/x.txt"), transfers.toList())
        assertEquals(listOf("Moved 1 item"), said.map { it.title })
        assertEquals(emptyList<String>(), titles())
    }

    @Test
    fun `a window that becomes a phone's stops taking drags`() {
        show(view)
        compose.runOnIdle { phone.value = true }
        compose.waitForIdle()
        assertFalse(begin(entries("/elsewhere/x.txt")))
    }

    @Test
    fun `a drag that rests on a folder does not open it, which only the Column view does`() {
        show(view, clockHeld = true)
        begin(entries("/elsewhere/x.txt"))
        hover(center("docs"))
        compose.mainClock.advanceTimeBy(2_000, ignoreFrameDuration = true)
        assertEquals("/home/me", session.state.value.path)
    }
}
