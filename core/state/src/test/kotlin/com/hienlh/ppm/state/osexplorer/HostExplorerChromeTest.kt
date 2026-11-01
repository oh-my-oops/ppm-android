package com.hienlh.ppm.state.osexplorer

import com.hienlh.ppm.protocol.HostFileEntry
import com.hienlh.ppm.protocol.HostFilesystem
import com.hienlh.ppm.protocol.HostPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's chrome decided as the web decides it: mobile-bottom-toolbar.tsx, mobile-more-sheet.tsx and the places. */
class HostExplorerChromeTest {
    private fun file(name: String) = HostFileEntry(name, "/home/me/$name", "file", "file", size = 10)
    private fun dir(name: String) = HostFileEntry(name, "/home/me/$name", "directory", "directory")

    private val entries = listOf(dir("src"), file("a.txt"), file("b.txt"), file("c.txt"))

    @Test
    fun `the selection is what an action acts on, in the order of the listing`() {
        val selection = setOf("/home/me/c.txt", "/home/me/a.txt")
        assertEquals(listOf("a.txt", "c.txt"), hostActiveEntries(entries, selection, anchor = "/home/me/b.txt").map { it.name })
    }

    @Test
    fun `with nothing selected the cursor row is the target, and without one there is none`() {
        assertEquals(listOf("b.txt"), hostActiveEntries(entries, emptySet(), "/home/me/b.txt").map { it.name })
        assertEquals(emptyList<HostFileEntry>(), hostActiveEntries(entries, emptySet(), null))
        assertEquals(emptyList<HostFileEntry>(), hostActiveEntries(entries, emptySet(), "/home/me/gone.txt"))
    }

    @Test
    fun `a selection of rows the listing no longer holds acts on none and does not fall back to the cursor`() {
        assertEquals(emptyList<HostFileEntry>(), hostActiveEntries(entries, setOf("/home/me/gone.txt"), "/home/me/b.txt"))
    }

    @Test
    fun `nothing selected leaves Cut, Copy, Trash, Download and Info off`() {
        val bar = hostSelectBar(emptyList())
        assertEquals(0, bar.count)
        assertFalse(bar.canAct)
        assertFalse(bar.canDownload)
        assertFalse(bar.canShowInfo)
    }

    @Test
    fun `one file turns every button on, Info among them`() {
        val bar = hostSelectBar(listOf(file("a.txt")))
        assertEquals(1, bar.count)
        assertTrue(bar.canAct)
        assertTrue(bar.canDownload)
        assertTrue(bar.canShowInfo)
    }

    @Test
    fun `a folder alone cannot be downloaded, but a file beside it can`() {
        assertFalse(hostSelectBar(listOf(dir("src"))).canDownload)
        assertFalse(hostSelectBar(listOf(dir("src"), dir("lib"))).canDownload)
        assertTrue(hostSelectBar(listOf(dir("src"), file("a.txt"))).canDownload)
    }

    @Test
    fun `Info needs exactly one entry`() {
        assertTrue(hostSelectBar(listOf(dir("src"))).canShowInfo)
        assertFalse(hostSelectBar(listOf(file("a.txt"), file("b.txt"))).canShowInfo)
    }

    @Test
    fun `a pill of another sort starts ascending, and the one in force turns the order over`() {
        assertFalse(hostSortPicked(ExplorerSort.NAME, descending = false, picked = ExplorerSort.SIZE))
        assertFalse(hostSortPicked(ExplorerSort.NAME, descending = true, picked = ExplorerSort.SIZE))
        assertTrue(hostSortPicked(ExplorerSort.NAME, descending = false, picked = ExplorerSort.NAME))
        assertFalse(hostSortPicked(ExplorerSort.NAME, descending = true, picked = ExplorerSort.NAME))
    }

    @Test
    fun `only the pill of the sort in force carries an arrow, up for ascending`() {
        assertEquals("Name ↑", hostSortPillLabel(ExplorerSort.NAME, ExplorerSort.NAME, descending = false))
        assertEquals("Name ↓", hostSortPillLabel(ExplorerSort.NAME, ExplorerSort.NAME, descending = true))
        assertEquals("Size", hostSortPillLabel(ExplorerSort.SIZE, ExplorerSort.NAME, descending = true))
        assertEquals("Modified", hostSortPillLabel(ExplorerSort.MODIFIED, ExplorerSort.KIND, descending = false))
    }

    @Test
    fun `the window toolbar words its three views as the web does, the third Column view in the singular`() {
        assertEquals(listOf("List view", "Icons view", "Column view"), ExplorerView.entries.map(::hostViewLabel))
    }

    @Test
    fun `the folder is named by its last breadcrumb, or by its path before there are any`() {
        val crumbs = listOf(HostPlace("/", "/"), HostPlace("home", "/home"), HostPlace("me", "/home/me"))
        assertEquals("me", hostFolderName("/home/me", crumbs))
        assertEquals("~", hostFolderName("~", emptyList()))
    }

    @Test
    fun `the folder's own entry is a directory at the path, named the same way`() {
        val entry = hostFolderEntry("/home/me", listOf(HostPlace("me", "/home/me")))
        assertEquals(HostFileEntry("me", "/home/me", "directory", "directory"), entry)
        assertTrue(entry.isDirectory)
        assertEquals("/srv", hostFolderEntry("/srv", emptyList()).name)
    }

    @Test
    fun `Windows and macOS name the sections of the places apart, and both call Home Home`() {
        assertEquals(HostPlacesVocab("Home", "Quick access", "Folders", "Drives"), hostPlacesVocab(windows = true))
        assertEquals(HostPlacesVocab("Home", "Favorites", "Locations", "Devices"), hostPlacesVocab(windows = false))
    }

    private val host = HostFilesystem(
        platform = "linux", separator = "/", home = "/home/me", hostname = "box",
        drives = listOf(
            HostPlace("sda1", "/", kind = "fixed", label = "System"),
            HostPlace("usb", "/media/usb", kind = "removable"),
            HostPlace("nas", "/mnt/nas", kind = "network", label = ""),
            HostPlace("odd", "/mnt/odd", kind = "something-new"),
        ),
        knownFolders = listOf(HostPlace("Documents", "/home/me/Documents"), HostPlace("Downloads", "/home/me/Downloads")),
        pinned = listOf(HostPlace("Projects", "/home/me/Projects")),
        warnings = emptyList(),
    )
    private val vocab = hostPlacesVocab(windows = false)
    private val pins = listOf(HostPlace("work", "/srv/work"), HostPlace("Documents", "/home/me/Documents"))

    @Test
    fun `the strip lists the pins, Home, the OS's pinned folders and the drives in that order, dropping none`() {
        val chips = hostPlaceChips(pins, host, vocab)
        assertEquals(
            listOf("work", "Documents", "Home", "Projects", "System (sda1)", "usb", "nas", "odd"),
            chips.map { it.label },
        )
        assertEquals(chips.size, chips.map { it.key }.toSet().size)
    }

    @Test
    fun `the strip leaves the known folders to the sheet, which holds them under Home`() {
        val chips = hostPlaceChips(emptyList(), host, vocab)
        assertFalse(chips.any { it.path == "/home/me/Downloads" })
        assertEquals(listOf("/home/me", "/home/me/Projects"), chips.take(2).map { it.path })
    }

    @Test
    fun `a pin keeps its chip when its folder is also a known folder`() {
        val chips = hostPlaceChips(pins, host, vocab)
        assertEquals(listOf(HostPlaceIcon.PIN, HostPlaceIcon.PIN, HostPlaceIcon.FOLDER, HostPlaceIcon.FOLDER), chips.take(4).map { it.icon })
    }

    @Test
    fun `before the host answers the strip holds only the pins, and with none it is empty`() {
        assertEquals(listOf("work", "Documents"), hostPlaceChips(pins, null, vocab).map { it.label })
        assertEquals(emptyList<HostPlaceLink>(), hostPlaceChips(emptyList(), null, vocab))
    }

    @Test
    fun `a drive reads Label (name) with a label and its bare name without, and its glyph follows its kind`() {
        val drives = hostPlaceChips(emptyList(), host, vocab).drop(2)
        assertEquals(listOf("System (sda1)", "usb", "nas", "odd"), drives.map { it.label })
        assertEquals(
            listOf(HostPlaceIcon.HARD_DRIVE, HostPlaceIcon.USB, HostPlaceIcon.NETWORK, HostPlaceIcon.HARD_DRIVE),
            drives.map { it.icon },
        )
    }

    @Test
    fun `the sheet has a section each, with the skin's titles, Home first among the known folders`() {
        val sections = hostPlaceSections(pins, host, vocab)
        assertEquals(listOf("Pinned", "Favorites", "Locations", "Devices"), sections.map { it.title })
        assertEquals(listOf("work", "Documents"), sections[0].places.map { it.label })
        assertEquals(listOf("Projects"), sections[1].places.map { it.label })
        assertEquals(listOf("Home", "Documents", "Downloads"), sections[2].places.map { it.label })
        assertEquals(listOf("/home/me", "/home/me/Documents", "/home/me/Downloads"), sections[2].places.map { it.path })
        assertEquals(listOf("System (sda1)", "usb", "nas", "odd"), sections[3].places.map { it.label })
    }

    @Test
    fun `Windows titles the sheet's sections Quick access, Folders and Drives`() {
        assertEquals(listOf("Pinned", "Quick access", "Folders", "Drives"), hostPlaceSections(pins, host, hostPlacesVocab(windows = true)).map { it.title })
    }

    @Test
    fun `a section with nothing in it is not drawn, but the known folders always are once the host answered`() {
        val bare = host.copy(pinned = emptyList(), drives = emptyList(), knownFolders = emptyList())
        val sections = hostPlaceSections(emptyList(), bare, vocab)
        assertEquals(listOf("Locations"), sections.map { it.title })
        assertEquals(listOf("Home"), sections.single().places.map { it.label })
    }

    @Test
    fun `before the host answers only the pins are listed`() {
        assertEquals(listOf("Pinned"), hostPlaceSections(pins, null, vocab).map { it.title })
        assertEquals(emptyList<HostPlacesSection>(), hostPlaceSections(emptyList(), null, vocab))
    }

    @Test
    fun `the keys of one section's places do not repeat`() {
        for (section in hostPlaceSections(pins, host, vocab)) assertEquals(section.places.size, section.places.map { it.key }.toSet().size)
    }
}
