package com.hienlh.ppm.state.osexplorer

import com.hienlh.ppm.protocol.HostFileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** explorer-context-menu.tsx: which rows each kind of target gets, in the web's order. */
class HostExplorerMenusTest {
    private fun dir(name: String) = HostFileEntry(name, "/home/me/$name", "directory", "directory")
    private fun file(name: String) = HostFileEntry(name, "/home/me/$name", "file", "file", size = 10)

    private val tablet = HostMenuContext(phone = false, hasClipboard = false)
    private val phone = tablet.copy(phone = true)

    /** One line per row: `—` for a divider, `Name*` for a disabled row, `Name!` for a destructive one. */
    private fun shown(items: List<HostMenuItem>): List<String> = items.map {
        when (it) {
            HostMenuItem.Separator -> "—"
            is HostMenuItem.SortBy -> "Sort by >"
            is HostMenuItem.Row -> it.label + (if (!it.enabled) "*" else "") + (if (it.destructive) "!" else "")
        }
    }

    @Test
    fun `the folder's own menu is the web's, in its order, with Paste disabled for an empty clipboard`() {
        assertEquals(
            listOf(
                "New File", "New Folder", "—", "Paste*", "—", "Upload Files…", "Upload Folder…", "—",
                "Open in Terminal", "—", "Sort by >", "—", "Copy Path",
            ),
            shown(hostBackgroundMenu(tablet)),
        )
    }

    @Test
    fun `Paste is enabled once something is on the clipboard`() {
        assertTrue("Paste" in shown(hostBackgroundMenu(tablet.copy(hasClipboard = true))))
    }

    @Test
    fun `the folder's menu carries the sort in force for the submenu to tick`() {
        val menu = hostBackgroundMenu(tablet.copy(sort = ExplorerSort.SIZE, descending = true))
        assertEquals(HostMenuItem.SortBy(ExplorerSort.SIZE, true), menu.filterIsInstance<HostMenuItem.SortBy>().single())
    }

    @Test
    fun `no folder upload leaves Upload Folder out`() {
        val menu = shown(hostBackgroundMenu(tablet.copy(supportsFolderUpload = false)))
        assertFalse("Upload Folder…" in menu)
        assertTrue("Upload Files…" in menu)
    }

    @Test
    fun `no upload queue leaves both upload rows out, and the dividers round them make one`() {
        assertEquals(
            listOf("New File", "New Folder", "—", "Paste*", "—", "Open in Terminal", "—", "Sort by >", "—", "Copy Path"),
            shown(hostBackgroundMenu(tablet.copy(canUpload = false))),
        )
    }

    @Test
    fun `the folder's menu has none of the extras the web does not draw`() {
        val labels = shown(hostBackgroundMenu(tablet.copy(hasClipboard = true)))
        assertTrue(listOf("Properties", "Refresh", "View and sort", "Upload files", "Upload folder").none { it in labels })
    }

    @Test
    fun `no targets is the folder's menu`() {
        assertEquals(hostBackgroundMenu(tablet), hostEntryMenu(emptyList(), tablet))
    }

    @Test
    fun `one folder gets Open, a window, a terminal, Paste and Pin and no Download`() {
        assertEquals(
            listOf(
                "Open", "Open in New Window", "Open in Terminal", "—", "Cut", "Copy", "Paste*", "—",
                "Rename", "Move to Trash", "Delete permanently!", "—", "Copy Path", "Copy Name", "Pin to Sidebar", "—", "Properties",
            ),
            shown(hostEntryMenu(listOf(dir("src")), tablet)),
        )
    }

    @Test
    fun `on the phone a folder has no second window to open in`() {
        val menu = shown(hostEntryMenu(listOf(dir("src")), phone))
        assertFalse("Open in New Window" in menu)
        assertTrue("Open in Terminal" in menu)
    }

    @Test
    fun `a pinned folder offers Unpin and a clipboard enables its Paste`() {
        val menu = shown(hostEntryMenu(listOf(dir("src")), tablet.copy(pinned = setOf("/home/me/src"), hasClipboard = true)))
        assertTrue("Unpin from Sidebar" in menu)
        assertFalse("Pin to Sidebar" in menu)
        assertTrue("Paste" in menu)
    }

    @Test
    fun `a file a viewer opens gets Open and Download, and no folder rows`() {
        assertEquals(
            listOf(
                "Open", "—", "Cut", "Copy", "—", "Rename", "Move to Trash", "Delete permanently!", "—",
                "Copy Path", "Copy Name", "Download", "—", "Properties",
            ),
            shown(hostEntryMenu(listOf(file("notes.md")), tablet)),
        )
    }

    @Test
    fun `a file nothing opens has no Open, and its menu begins with the divider the web draws first`() {
        val menu = shown(hostEntryMenu(listOf(file("backup.zip")), tablet))
        assertEquals(listOf("—", "Cut", "Copy", "—", "Rename"), menu.take(5))
        assertTrue("Download" in menu)
    }

    @Test
    fun `several files count what Cut, Copy and Trash act on, and have no single-entry rows`() {
        val menu = shown(hostEntryMenu(listOf(file("a.txt"), file("b.txt"), file("c.txt")), tablet))
        assertEquals(
            listOf(
                "—", "Cut 3 items", "Copy 3 items", "—", "Move to Trash 3 items", "Delete permanently!", "—",
                "Copy Path", "Copy Name", "Download",
            ),
            menu,
        )
    }

    @Test
    fun `several folders have no Open, window, terminal, Paste, Pin or Download`() {
        assertEquals(
            listOf(
                "—", "Cut 2 items", "Copy 2 items", "—", "Move to Trash 2 items", "Delete permanently!", "—", "Copy Path", "Copy Name",
            ),
            shown(hostEntryMenu(listOf(dir("a"), dir("b")), tablet)),
        )
    }

    @Test
    fun `a mix of a folder and a file downloads the file`() {
        val menu = shown(hostEntryMenu(listOf(dir("a"), file("b.txt")), tablet))
        assertTrue("Download" in menu)
        assertFalse("Paste*" in menu || "Paste" in menu)
    }

    @Test
    fun `only Delete permanently is destructive`() {
        val rows = hostEntryMenu(listOf(dir("src")), tablet).filterIsInstance<HostMenuItem.Row>()
        assertEquals(listOf(HostMenuCommand.DELETE_PERMANENTLY), rows.filter { it.destructive }.map { it.command })
    }

    @Test
    fun `no menu ends with a divider or repeats one, and only an entry's can begin with one`() {
        val targets = listOf(
            emptyList(), listOf(dir("a")), listOf(file("a.txt")), listOf(file("a.zip")), listOf(dir("a"), dir("b")),
            listOf(file("a.txt"), file("b.zip")), listOf(dir("a"), file("b.zip")),
        )
        for (phoneMenu in listOf(false, true)) for (canUpload in listOf(false, true)) for (items in targets) {
            val menu = hostEntryMenu(items, tablet.copy(phone = phoneMenu, canUpload = canUpload))
            assertTrue(menu.last() != HostMenuItem.Separator)
            assertTrue(menu.zipWithNext().none { (a, b) -> a == HostMenuItem.Separator && b == HostMenuItem.Separator })
            if (items.isEmpty()) assertTrue(menu.first() != HostMenuItem.Separator)
        }
    }

    @Test
    fun `sort and order read as the web says them`() {
        assertEquals(listOf("Name", "Size", "Modified", "Kind"), ExplorerSort.entries.map { it.label })
        assertEquals("Ascending", hostOrderLabel(false))
        assertEquals("Descending", hostOrderLabel(true))
    }

    @Test
    fun `copying says what was copied and how many`() {
        assertEquals("Path copied", hostCopiedMessage(1, "Path"))
        assertEquals("3 paths copied", hostCopiedMessage(3, "Path"))
        assertEquals("Name copied", hostCopiedMessage(1, "Name"))
        assertEquals("2 names copied", hostCopiedMessage(2, "Name"))
    }

    @Test
    fun `Copy Path of entries is one path a line, counted`() {
        val copied = hostCopied(HostMenuCommand.COPY_PATH, listOf(file("a.txt"), dir("src")), "/home/me")
        assertEquals("/home/me/a.txt\n/home/me/src", copied.text)
        assertEquals("2 paths copied", copied.message)
        assertEquals(HostCopied("/home/me/a.txt", "Path copied"), hostCopied(HostMenuCommand.COPY_PATH, listOf(file("a.txt")), "/home/me"))
    }

    @Test
    fun `Copy Name of entries is one name a line, counted`() {
        val copied = hostCopied(HostMenuCommand.COPY_NAME, listOf(file("a.txt"), dir("src")), "/home/me")
        assertEquals("a.txt\nsrc", copied.text)
        assertEquals("2 names copied", copied.message)
        assertEquals(HostCopied("src", "Name copied"), hostCopied(HostMenuCommand.COPY_NAME, listOf(dir("src")), "/home/me"))
    }

    @Test
    fun `Copy Path in the folder's own menu copies the folder the menu opened on`() {
        assertEquals(HostCopied("/home/me/work", "Path copied"), hostCopied(HostMenuCommand.COPY_PATH, emptyList(), "/home/me/work"))
    }
}
