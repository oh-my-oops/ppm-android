package com.hienlh.ppm.app.ui.osexplorer

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.hienlh.ppm.protocol.HostFileEntry
import com.hienlh.ppm.protocol.HostFilesystem
import com.hienlh.ppm.protocol.HostPlace
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.state.osexplorer.ExplorerSort
import com.hienlh.ppm.state.osexplorer.ExplorerView
import com.hienlh.ppm.state.osexplorer.ExplorerViewPreferences
import com.hienlh.ppm.state.osexplorer.HostMenuCommand
import com.hienlh.ppm.state.osexplorer.hostPlaceChips
import com.hienlh.ppm.state.osexplorer.hostPlaceSections
import com.hienlh.ppm.state.osexplorer.hostPlacesVocab
import com.hienlh.ppm.state.osexplorer.hostSelectBar
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The phone's chrome as it is drawn (`mobile/` on the web): the rows of the bar under the list, what each
 * sheet holds, the strip of places and the sheet behind its last chip. The sheets' rows are drawn directly, as
 * they sit inside one; `HostPhoneToolbarTest` opens the real sheets and presses them. The screen is tall
 * enough for all of them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h1800dp")
class HostExplorerPhoneTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private fun dir(name: String) = HostFileEntry(name, "/home/me/$name", "directory", "directory")
    private fun file(name: String) = HostFileEntry(name, "/home/me/$name", "file", "file", size = 10)

    /** What happened, in order: `back`, `command CUT`, `view ICONS`, `go /home/me`. */
    private val events = mutableListOf<String>()

    private fun label(node: SemanticsNode): String =
        node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
            ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("").orEmpty()

    /** The label of every node that can be pressed, left to right and top to bottom. */
    private fun pressable(): List<String> = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().map(::label)

    private fun row(canBack: Boolean = true, hasClipboard: Boolean = true) = compose.setContent {
        HostPhoneRow(
            canBack, hasClipboard,
            onBack = { events += "back" }, onNew = { events += "new" }, onPaste = { events += "paste" },
            onSelect = { events += "select" }, onMore = { events += "more" },
        )
    }

    private fun selection(vararg selected: HostFileEntry) = compose.setContent {
        HostSelectRow(hostSelectBar(selected.toList()), onCancel = { events += "cancel" }) { events += "command $it" }
    }

    @Test
    fun `the folder's row is Back, New, Paste, Select and More`() {
        row()
        assertEquals(listOf("Back", "New", "Paste", "Select", "More"), pressable())
    }

    @Test
    fun `Back is dead with no history and Paste with nothing to paste, and a press on either goes nowhere`() {
        row(canBack = false, hasClipboard = false)
        compose.onNodeWithText("Back").assertIsNotEnabled().performClick()
        compose.onNodeWithText("Paste").assertIsNotEnabled().performClick()
        compose.onNodeWithText("New").assertIsEnabled()
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `each button of the folder's row says what was pressed`() {
        row()
        for (name in listOf("Back", "New", "Paste", "Select", "More")) compose.onNodeWithText(name).assertIsEnabled().performClick()
        assertEquals(listOf("back", "new", "paste", "select", "more"), events)
    }

    @Test
    fun `the selection's row is Cancel and how many, then Cut, Copy, Trash and Download, Info only for one entry`() {
        selection(file("a.txt"), file("b.txt"))
        compose.onNodeWithText("2 selected").assertExists()
        assertEquals(listOf("Cancel", "Cut", "Copy", "Trash", "Download"), pressable())
    }

    @Test
    fun `one entry adds Info`() {
        selection(file("a.txt"))
        compose.onNodeWithText("1 selected").assertExists()
        assertEquals(listOf("Cancel", "Cut", "Copy", "Trash", "Download", "Info"), pressable())
    }

    @Test
    fun `with nothing selected only Cancel answers`() {
        selection()
        compose.onNodeWithText("0 selected").assertExists()
        compose.onNodeWithText("Cancel").assertIsEnabled()
        for (name in listOf("Cut", "Copy", "Trash", "Download")) compose.onNodeWithText(name).assertIsNotEnabled()
        compose.onNodeWithText("Info").assertDoesNotExist()
    }

    @Test
    fun `Download is dead for folders alone and Info is there for one folder`() {
        selection(dir("src"))
        compose.onNodeWithText("Download").assertIsNotEnabled()
        compose.onNodeWithText("Trash").assertIsEnabled()
        compose.onNodeWithText("Info").assertIsEnabled()
    }

    @Test
    fun `a file among folders brings Download back`() {
        selection(dir("src"), file("a.txt"))
        compose.onNodeWithText("Download").assertIsEnabled()
        compose.onNodeWithText("Info").assertDoesNotExist()
    }

    @Test
    fun `the selection's buttons send their command with Cancel apart, which leaves Select mode`() {
        selection(file("a.txt"))
        for (name in listOf("Cut", "Copy", "Trash", "Download", "Info", "Cancel")) compose.onNodeWithText(name).performClick()
        assertEquals(
            listOf("command CUT", "command COPY", "command TRASH", "command DOWNLOAD", "command PROPERTIES", "cancel"),
            events,
        )
    }

    private fun news(canUpload: Boolean) = compose.setContent {
        Column { HostNewRows(canUpload) { events += "command $it" } }
    }

    @Test
    fun `New offers a file, a folder and uploading files`() {
        news(canUpload = true)
        assertEquals(listOf("New File", "New Folder", "Upload Files…"), pressable())
        for (name in listOf("New File", "New Folder", "Upload Files…")) compose.onNodeWithText(name).performClick()
        assertEquals(listOf("command NEW_FILE", "command NEW_FOLDER", "command UPLOAD_FILES"), events)
    }

    @Test
    fun `uploading is not offered until there is a queue to upload through`() {
        news(canUpload = false)
        assertEquals(listOf("New File", "New Folder"), pressable())
    }

    private val preferences = ExplorerViewPreferences(sort = ExplorerSort.SIZE, descending = true, view = ExplorerView.ICONS, showHidden = false)

    private fun more(prefs: ExplorerViewPreferences = preferences, targets: List<HostFileEntry> = emptyList()) = compose.setContent {
        Column {
            HostMoreRows(
                prefs, targets,
                onView = { events += "view $it" }, onSort = { events += "sort $it" }, onToggleHidden = { events += "hidden" },
                onDeletePermanently = { events += "delete" }, onProperties = { events += "properties" },
            )
        }
    }

    @Test
    fun `More holds the view, the sort, the hidden files, delete and the folder's properties, in that order`() {
        more(targets = listOf(file("a.txt")))
        assertEquals(
            listOf(
                "List", "Icons", "Columns", "Name", "Size ↓", "Modified", "Kind",
                "Show hidden files", "Delete permanently", "Properties of this folder",
            ),
            pressable(),
        )
    }

    @Test
    fun `the view in force is the selected one, and a press names the other`() {
        more()
        compose.onNodeWithText("Icons").assertIsSelected()
        compose.onNodeWithText("List").assertIsNotSelected()
        compose.onNodeWithText("Columns").assertIsNotSelected()
        compose.onNodeWithText("Columns").performClick()
        compose.onNodeWithText("List").performClick()
        assertEquals(listOf("view COLUMNS", "view LIST"), events)
    }

    @Test
    fun `only the pill of the sort in force carries an arrow and is selected`() {
        more()
        compose.onNodeWithText("Size ↓").assertIsSelected()
        for (other in listOf("Name", "Modified", "Kind")) compose.onNodeWithText(other).assertIsNotSelected()
        compose.onNodeWithText("Kind").performClick()
        compose.onNodeWithText("Size ↓").performClick()
        assertEquals(listOf("sort KIND", "sort SIZE"), events)
    }

    @Test
    fun `an ascending sort shows its arrow up`() {
        more(preferences.copy(sort = ExplorerSort.NAME, descending = false))
        compose.onNodeWithText("Name ↑").assertIsSelected()
    }

    @Test
    fun `the hidden-files row says what a press does`() {
        more(preferences.copy(showHidden = true))
        compose.onNodeWithText("Show hidden files").assertDoesNotExist()
        compose.onNodeWithText("Hide hidden files").performClick()
        assertEquals(listOf("hidden"), events)
    }

    @Test
    fun `Delete permanently is dead with no targets, plain for one and counts several`() {
        more()
        compose.onNodeWithText("Delete permanently").assertIsNotEnabled().performClick()
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `one target leaves Delete permanently without a count`() {
        more(targets = listOf(file("a.txt")))
        compose.onNodeWithText("Delete permanently").assertIsEnabled().performClick()
        assertEquals(listOf("delete"), events)
    }

    @Test
    fun `several targets put their number on Delete permanently`() {
        more(targets = listOf(file("a.txt"), file("b.txt"), dir("src")))
        compose.onNodeWithText("Delete permanently").assertDoesNotExist()
        compose.onNodeWithText("Delete permanently (3)").assertIsEnabled()
    }

    @Test
    fun `the folder's properties are always there to open`() {
        more()
        compose.onNodeWithText("Properties of this folder").assertIsEnabled().performClick()
        assertEquals(listOf("properties"), events)
    }

    private val host = HostFilesystem(
        platform = "darwin", separator = "/", home = "/Users/me", hostname = "mac",
        drives = listOf(HostPlace("Macintosh HD", "/", kind = "fixed"), HostPlace("backup", "/Volumes/backup", kind = "removable", label = "Backup")),
        knownFolders = listOf(HostPlace("Desktop", "/Users/me/Desktop"), HostPlace("Documents", "/Users/me/Documents")),
        pinned = listOf(HostPlace("Applications", "/Applications")),
        warnings = emptyList(),
    )
    private val pins = listOf(HostPlace("work", "/srv/work"))
    private val vocab = hostPlacesVocab(windows = false)

    private fun strip(current: String) = compose.setContent {
        HostPlacesStrip(hostPlaceChips(pins, host, vocab), current, { Modifier }, onGo = { events += "go $it" }, onAll = { events += "all" })
    }

    @Test
    fun `the strip is a chip for each pin, Home, the OS's pinned folders and the drives, then All places`() {
        strip("/Users/me")
        assertEquals(listOf("work", "Home", "Applications", "Macintosh HD", "Backup (backup)", "All places"), pressable())
    }

    @Test
    fun `the chip of the open folder is the selected one`() {
        strip("/Users/me")
        compose.onNodeWithText("Home").assertIsSelected()
        compose.onNodeWithText("work").assertIsNotSelected()
        compose.onNodeWithText("Applications").assertIsNotSelected()
    }

    @Test
    fun `a chip goes to its place and the last one asks for the rest`() {
        strip("/")
        compose.onNodeWithText("work").performClick()
        compose.onNodeWithText("Home").performClick()
        compose.onNodeWithContentDescription("All places").performScrollTo().performClick()
        assertEquals(listOf("go /srv/work", "go /Users/me", "all"), events)
    }

    private fun sheet(current: String) = compose.setContent {
        Column { HostPlacesRows(hostPlaceSections(pins, host, vocab), current) { events += "go $it" } }
    }

    @Test
    fun `the sheet has a titled section each, the skin's names for them`() {
        sheet("/Users/me/Desktop")
        for (title in listOf("PINNED", "FAVORITES", "LOCATIONS", "DEVICES")) compose.onNodeWithText(title).assertExists()
        compose.onNodeWithText("FOLDERS").assertDoesNotExist()
        compose.onNodeWithText("DRIVES").assertDoesNotExist()
    }

    @Test
    fun `the sheet lists the known folders the strip leaves out, and goes to the one pressed`() {
        sheet("/Users/me/Desktop")
        compose.onNodeWithText("Desktop").assertIsSelected()
        compose.onNodeWithText("Documents").assertIsNotSelected().performClick()
        compose.onNodeWithText("Backup (backup)").performClick()
        assertEquals(listOf("go /Users/me/Documents", "go /Volumes/backup"), events)
    }

    @Test
    fun `the sheet has no button to unpin, which the web leaves to the sidebar`() {
        sheet("/")
        assertEquals(
            listOf("work", "Applications", "Home", "Desktop", "Documents", "Macintosh HD", "Backup (backup)"),
            pressable(),
        )
    }

    @Test
    fun `the title bar is the folder's name and a round button to close`() {
        compose.setContent { HostPhoneTopBar("Documents", onClose = { events += "close" }) }
        compose.onNodeWithText("Documents").assertExists()
        compose.onNodeWithContentDescription("Close file explorer").assertIsEnabled().performClick()
        assertEquals(listOf("close"), events)
    }
}
