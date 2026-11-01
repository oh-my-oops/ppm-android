package com.hienlh.ppm.app.ui.osexplorer

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hienlh.ppm.app.ui.MenuBox
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.protocol.HostFileEntry
import com.hienlh.ppm.state.osexplorer.ExplorerSort
import com.hienlh.ppm.state.osexplorer.HostMenuContext
import com.hienlh.ppm.state.osexplorer.HostMenuItem
import com.hienlh.ppm.state.osexplorer.hostBackgroundMenu
import com.hienlh.ppm.state.osexplorer.hostEntryMenu
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The explorer's menu as it is drawn: the rows the pure lists name, in their order, a disabled one
 * dead, a radio ticked as the sort in force says, and what a press does — close the menu, then run.
 * The screen is tall enough for the phone's whole sheet, which scrolls on a real one. The "Sort by"
 * submenu is a popup, which this module's Robolectric has no resources to create, so the radios it
 * holds are drawn directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h1800dp")
class HostExplorerMenuTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private fun dir(name: String) = HostFileEntry(name, "/home/me/$name", "directory", "directory")
    private fun file(name: String) = HostFileEntry(name, "/home/me/$name", "file", "file", size = 10)

    private val context = HostMenuContext(phone = false, hasClipboard = false)

    /** What happened, in order: `dismiss`, `run CUT`, `sort KIND descending`. */
    private val events = mutableListOf<String>()

    private fun tablet(items: List<HostMenuItem>) = compose.setContent {
        MenuBox { HostMenuRows(items, { events += "run $it" }, sorted, { events += "dismiss" }) }
    }

    private fun phone(items: List<HostMenuItem>) = compose.setContent {
        Column { HostSheetRows(items, { events += "run $it" }, sorted, { events += "dismiss" }) }
    }

    private val sorted: (ExplorerSort, Boolean) -> Unit = { key, descending ->
        events += "sort $key ${if (descending) "descending" else "ascending"}"
    }

    /** The text of every node that can be pressed, top to bottom. */
    private fun pressable(): List<String> = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        .map { node -> node.config[SemanticsProperties.Text].joinToString("") { it.text } }

    @Test
    fun `a tablet's folder menu is one pressable row each in the web's order, Sort by among them`() {
        tablet(hostBackgroundMenu(context))
        assertEquals(
            listOf("New File", "New Folder", "Paste", "Upload Files…", "Upload Folder…", "Open in Terminal", "Sort by", "Copy Path"),
            pressable(),
        )
    }

    @Test
    fun `Paste is half faded and dead with nothing to paste, and a press on it goes nowhere`() {
        tablet(hostBackgroundMenu(context))
        compose.onNodeWithText("Paste").assertIsNotEnabled().performClick()
        compose.onNodeWithText("New File").assertIsEnabled()
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `Paste works once there is something to paste`() {
        tablet(hostBackgroundMenu(context.copy(hasClipboard = true)))
        compose.onNodeWithText("Paste").assertIsEnabled().performClick()
        assertEquals(listOf("dismiss", "run PASTE"), events)
    }

    @Test
    fun `a press closes the menu and then runs the row`() {
        tablet(hostEntryMenu(listOf(file("notes.md"), file("todo.md")), context))
        compose.onNodeWithText("Cut 2 items").performClick()
        compose.onNodeWithText("Delete permanently").performClick()
        assertEquals(listOf("dismiss", "run CUT", "dismiss", "run DELETE_PERMANENTLY"), events)
    }

    @Test
    fun `the sort radios tick the key and the order in force, and a press says which was picked beside the other`() {
        compose.setContent { MenuBox { HostSortRadios(HostMenuItem.SortBy(ExplorerSort.SIZE, descending = true), sorted) } }
        compose.onNodeWithText("Size").assertIsSelected()
        compose.onNodeWithText("Descending").assertIsSelected()
        for (other in listOf("Name", "Modified", "Kind", "Ascending")) compose.onNodeWithText(other).assertIsNotSelected()

        compose.onNodeWithText("Kind").performClick()
        compose.onNodeWithText("Ascending").performClick()
        assertEquals(listOf("sort KIND descending", "sort SIZE ascending"), events)
    }

    @Test
    fun `the phone's sheet flattens Sort by to a label with its radios under it`() {
        phone(hostBackgroundMenu(context.copy(phone = true, sort = ExplorerSort.KIND)))
        assertEquals(
            listOf(
                "New File", "New Folder", "Paste", "Upload Files…", "Upload Folder…", "Open in Terminal",
                "Name", "Size", "Modified", "Kind", "Ascending", "Descending", "Copy Path",
            ),
            pressable(),
        )
        compose.onNodeWithText("Sort by").assertHasNoClickAction()
        compose.onNodeWithText("Kind").assertIsSelected()
        compose.onNodeWithText("Ascending").assertIsSelected()
        compose.onNodeWithText("Name").assertIsNotSelected()
    }

    @Test
    fun `on the phone a radio closes the sheet and then sorts, and a disabled row is dead`() {
        phone(hostBackgroundMenu(context.copy(phone = true)))
        compose.onNodeWithText("Paste").assertIsNotEnabled().performClick()
        assertEquals(emptyList<String>(), events)
        compose.onNodeWithText("Descending").performClick()
        compose.onNodeWithText("Copy Path").performClick()
        assertEquals(listOf("dismiss", "sort NAME descending", "dismiss", "run COPY_PATH"), events)
    }
}
