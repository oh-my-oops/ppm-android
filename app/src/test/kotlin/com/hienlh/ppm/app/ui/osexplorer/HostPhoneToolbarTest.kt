package com.hienlh.ppm.app.ui.osexplorer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.protocol.HostDirectory
import com.hienlh.ppm.protocol.HostFileEntry
import com.hienlh.ppm.protocol.HostPlace
import com.hienlh.ppm.state.osexplorer.ExplorerSessionState
import com.hienlh.ppm.state.osexplorer.ExplorerSort
import com.hienlh.ppm.state.osexplorer.ExplorerView
import com.hienlh.ppm.state.osexplorer.ExplorerViewPreferences
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The phone's bar with the sheets it opens, as a finger meets them: which row closes its sheet and which leaves
 * it up, as the web's do (`mobile-bottom-toolbar.tsx`, `mobile-more-sheet.tsx`), what each hands on, and the
 * swap between the folder's row and the selection's. The sheets are real `BottomSheet`s here, windows and all;
 * `HostExplorerPhoneTest` draws their rows alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h1800dp")
class HostPhoneToolbarTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private fun file(name: String) = HostFileEntry(name, "/home/me/$name", "file", "file", size = 10)

    private val entries = listOf(file("a.txt"), file("b.txt"), file("c.txt"))

    /** What the bar handed on, in order: `command CUT [a.txt]`, `view ICONS`, `sort SIZE descending=false`, `hidden true`, `select true`. */
    private val events = mutableListOf<String>()

    private var preferences by mutableStateOf(ExplorerViewPreferences(sort = ExplorerSort.NAME, descending = false, view = ExplorerView.LIST))
    private var selectMode by mutableStateOf(false)

    /** The mode is set before the content, not in it: a write in composition would undo every press of Select. */
    private fun toolbar(
        selection: Set<String> = emptySet(),
        anchor: String? = null,
        select: Boolean = false,
        upload: Boolean = true,
    ) {
        selectMode = select
        compose.setContent { Bar(selection, anchor, upload) }
    }

    @Composable
    private fun Bar(selection: Set<String>, anchor: String?, upload: Boolean) {
        val state = ExplorerSessionState(
            path = "/home/me",
            history = listOf("/home", "/home/me"),
            historyIndex = 1,
            directory = HostDirectory(entries, "/home/me", "/home", listOf(HostPlace("me", "/home/me")), "/", truncated = false),
            selection = selection,
            anchor = anchor,
        )
        HostPhoneToolbar(
            state, entries, preferences,
            selectMode = selectMode,
            hasClipboard = true,
            canUpload = upload,
            onSelectMode = {
                events += "select $it"
                selectMode = it
            },
            onBack = { events += "back" },
            onPreferences = {
                if (it.view != preferences.view) events += "view ${it.view}"
                if (it.sort != preferences.sort || it.descending != preferences.descending) events += "sort ${it.sort} descending=${it.descending}"
                if (it.showHidden != preferences.showHidden) events += "hidden ${it.showHidden}"
                preferences = it
            },
            onCommand = { command, targets -> events += "command $command ${targets.map { it.name }}" },
        )
    }

    private fun press(label: String) = compose.onNodeWithText(label).performClick()

    @Test
    fun `New opens its sheet, and New File closes it before its command runs, with no entries to act on`() {
        toolbar()
        compose.onNodeWithText("New File").assertDoesNotExist()
        press("New")
        compose.onNodeWithText("New Folder").assertExists()
        press("New File")
        compose.onNodeWithText("New Folder").assertDoesNotExist()
        assertEquals(listOf("command NEW_FILE []"), events)
    }

    @Test
    fun `New Folder and Upload Files close the sheet too`() {
        toolbar()
        press("New")
        press("New Folder")
        compose.onNodeWithText("New File").assertDoesNotExist()
        press("New")
        press("Upload Files…")
        compose.onNodeWithText("New File").assertDoesNotExist()
        assertEquals(listOf("command NEW_FOLDER []", "command UPLOAD_FILES []"), events)
    }

    @Test
    fun `without an upload queue New has no upload row`() {
        toolbar(upload = false)
        press("New")
        compose.onNodeWithText("New File").assertExists()
        compose.onNodeWithText("Upload Files…").assertDoesNotExist()
    }

    @Test
    fun `the folder's row hands on Back, Paste and Select, and none of them opens a sheet`() {
        toolbar()
        press("Back")
        press("Paste")
        press("Select")
        assertEquals(listOf("back", "command PASTE []", "select true"), events)
        compose.onNodeWithText("New File").assertDoesNotExist()
        compose.onNodeWithText("Properties of this folder").assertDoesNotExist()
    }

    @Test
    fun `Select swaps the folder's row for the selection's, and Cancel swaps it back`() {
        toolbar()
        compose.onNodeWithText("Cancel").assertDoesNotExist()
        press("Select")
        compose.onNodeWithText("0 selected").assertExists()
        compose.onNodeWithText("New").assertDoesNotExist()
        compose.onNodeWithText("Cut").assertIsNotEnabled()
        press("Cancel")
        compose.onNodeWithText("New").assertIsEnabled()
        compose.onNodeWithText("Cancel").assertDoesNotExist()
        assertEquals(listOf("select true", "select false"), events)
    }

    @Test
    fun `the selection's row acts on the selected rows in the listing's order`() {
        toolbar(selection = setOf("/home/me/c.txt", "/home/me/a.txt"), anchor = "/home/me/b.txt", select = true)
        compose.onNodeWithText("2 selected").assertExists()
        press("Cut")
        press("Copy")
        press("Trash")
        press("Download")
        assertEquals(
            listOf(
                "command CUT [a.txt, c.txt]",
                "command COPY [a.txt, c.txt]",
                "command TRASH [a.txt, c.txt]",
                "command DOWNLOAD [a.txt, c.txt]",
            ),
            events,
        )
    }

    @Test
    fun `Info is on the selection's row for exactly one row, and shows that row rather than the folder`() {
        toolbar(selection = setOf("/home/me/b.txt"), select = true)
        press("Info")
        assertEquals(listOf("command PROPERTIES [b.txt]"), events)
    }

    @Test
    fun `Info is not on the selection's row for two rows`() {
        toolbar(selection = setOf("/home/me/a.txt", "/home/me/b.txt"), select = true)
        compose.onNodeWithText("2 selected").assertExists()
        compose.onNodeWithText("Info").assertDoesNotExist()
    }

    @Test
    fun `a view button of More sets the view and closes the sheet`() {
        toolbar()
        press("More")
        compose.onNodeWithText("Properties of this folder").assertExists()
        press("Icons")
        compose.onNodeWithText("Properties of this folder").assertDoesNotExist()
        assertEquals(listOf("view ICONS"), events)
    }

    @Test
    fun `a sort pill closes More, another starts ascending and the one in force turns the order over`() {
        toolbar()
        press("More")
        press("Size")
        compose.onNodeWithText("Properties of this folder").assertDoesNotExist()
        press("More")
        press("Size ↑")
        press("More")
        press("Size ↓")
        assertEquals(
            listOf("sort SIZE descending=false", "sort SIZE descending=true", "sort SIZE descending=false"),
            events,
        )
        compose.onNodeWithText("Properties of this folder").assertDoesNotExist()
    }

    @Test
    fun `the hidden-files row flips its own label and leaves More open`() {
        toolbar()
        press("More")
        press("Show hidden files")
        compose.onNodeWithText("Hide hidden files").assertExists()
        compose.onNodeWithText("Properties of this folder").assertExists()
        assertEquals(listOf("hidden true"), events)
    }

    @Test
    fun `Properties hands on the folder itself and leaves More open behind its dialog`() {
        toolbar(selection = setOf("/home/me/a.txt"), anchor = "/home/me/a.txt")
        press("More")
        press("Properties of this folder")
        compose.onNodeWithText("Properties of this folder").assertExists()
        assertEquals(listOf("command PROPERTIES [me]"), events)
    }

    @Test
    fun `Delete permanently acts on the selection, counts it, and leaves More open`() {
        toolbar(selection = setOf("/home/me/c.txt", "/home/me/a.txt"), anchor = "/home/me/b.txt")
        press("More")
        press("Delete permanently (2)")
        compose.onNodeWithText("Delete permanently (2)").assertExists()
        assertEquals(listOf("command DELETE_PERMANENTLY [a.txt, c.txt]"), events)
    }

    @Test
    fun `with nothing selected Delete permanently takes the cursor row`() {
        toolbar(anchor = "/home/me/b.txt")
        press("More")
        press("Delete permanently")
        assertEquals(listOf("command DELETE_PERMANENTLY [b.txt]"), events)
    }

    @Test
    fun `without a selection or a cursor row Delete permanently is dead and a press on it goes nowhere`() {
        toolbar()
        press("More")
        compose.onNodeWithText("Delete permanently").assertIsNotEnabled().performClick()
        assertEquals(emptyList<String>(), events)
    }
}
