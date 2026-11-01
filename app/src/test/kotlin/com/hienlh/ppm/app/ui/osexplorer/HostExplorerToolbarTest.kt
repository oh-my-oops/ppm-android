package com.hienlh.ppm.app.ui.osexplorer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ui.MenuBox
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.protocol.HostDirectory
import com.hienlh.ppm.protocol.HostPlace
import com.hienlh.ppm.state.osexplorer.ExplorerSort
import com.hienlh.ppm.state.osexplorer.ExplorerView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The window's toolbar as it is drawn (`explorer-toolbar.tsx`): its buttons in the web's order and under the web's
 * names, Escape in the path and filter fields, the finger's reach past a 28 dp button, and the rows of the Sort and
 * Upload dropdowns. The dropdowns are popups, which this module's Robolectric has no resources to create, so their
 * rows are drawn directly, as they sit inside one. The screen is wide enough for the toolbar not to wrap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h600dp")
class HostExplorerToolbarTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val session = explorerSessionOn(
        HostDirectory(emptyList(), "/home/me", "/home", listOf(HostPlace("home", "/home"), HostPlace("me", "/home/me")), "/", truncated = false),
    )

    @After
    fun close() = session.close()

    /** What the toolbar handed on to be uploaded, in order: `files` or `folder`. */
    private val uploads = mutableListOf<String>()

    /** What a radio of the Sort dropdown picked: `KIND descending`. */
    private val picks = mutableListOf<String>()

    /** How wide the bar is held, to see where it breaks; the screen's own width unless a test narrows it. */
    private var barWidth by mutableStateOf(1000.dp)

    private fun toolbar(canUpload: Boolean = true) = compose.setContent {
        val state by session.state.collectAsState()
        val preferences by session.preferences.state.collectAsState()
        Box(Modifier.width(barWidth)) {
            HostExplorerToolbar(session, state, preferences, canUpload) { folder -> uploads += if (folder) "folder" else "files" }
        }
    }

    private fun button(description: String) = compose.onNodeWithContentDescription(description)

    private fun field(index: Int) = compose.onAllNodes(hasSetTextAction())[index]

    private fun typed(index: Int) = field(index).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    /** The text of every node that can be pressed, top to bottom. */
    private fun pressable(): List<String> = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        .map { node -> node.config[SemanticsProperties.Text].joinToString("") { it.text } }

    @Test
    fun `the buttons are the web's, in its order, history and Refresh then the path then the views, Sort, Upload and hidden files`() {
        toolbar()
        val names = listOf(
            "Back", "Forward", "Up one level", "Refresh", "Edit path",
            "List view", "Icons view", "Column view", "Sort", "Upload", "Show hidden files",
        )
        val lefts = names.map { button(it).getBoundsInRoot().left.value }
        assertEquals(lefts.sorted(), lefts)
        assertEquals(lefts.size, lefts.toSet().size)
    }

    @Test
    fun `the old File actions button is gone, and the third view is Column view, not Columns view`() {
        toolbar()
        button("File actions").assertDoesNotExist()
        button("Columns view").assertDoesNotExist()
        button("Column view").assertIsEnabled()
    }

    @Test
    fun `a view button sets the view and the hidden-files switch flips, taking its name from its state`() {
        toolbar()
        button("Column view").performClick()
        assertEquals(ExplorerView.COLUMNS, session.preferences.state.value.view)
        button("Icons view").performClick()
        assertEquals(ExplorerView.ICONS, session.preferences.state.value.view)
        button("Show hidden files").performClick()
        assertTrue(session.preferences.state.value.showHidden)
        button("Hide hidden files").performClick()
        assertFalse(session.preferences.state.value.showHidden)
    }

    @Test
    fun `Upload is dead until the upload queue exists, and a press on it opens nothing`() {
        toolbar(canUpload = false)
        button("Upload").assertIsNotEnabled().performClick()
        button("Sort").assertIsEnabled()
        assertEquals(emptyList<String>(), uploads)
    }

    @Test
    fun `a button takes a finger that lands 8 dp outside it, as the web's 44 px box does, and not one 14 dp out`() {
        toolbar()
        val hidden = button("Show hidden files")
        hidden.performTouchInput { click(Offset(width + 14.dp.toPx(), height / 2f)) }
        assertFalse(session.preferences.state.value.showHidden)
        hidden.performTouchInput { click(Offset(width + 8.dp.toPx(), height / 2f)) }
        assertTrue(session.preferences.state.value.showHidden)
    }

    @Test
    fun `Escape in the filter empties it`() {
        toolbar()
        field(0).performClick()
        field(0).performTextInput("rea")
        compose.waitForIdle()
        assertEquals("rea", session.state.value.filter)
        field(0).performKeyInput { pressKey(Key.Escape) }
        compose.waitForIdle()
        assertEquals("", session.state.value.filter)
        assertEquals("", typed(0))
    }

    @Test
    fun `Escape in the path field closes it with what was typed dropped, and it opens again on the folder's path`() {
        toolbar()
        button("Edit path").performClick()
        compose.waitForIdle()
        assertEquals("/home/me", typed(0))
        field(0).performTextInput("/typed")
        assertEquals("/home/me/typed", typed(0))
        field(0).performKeyInput { pressKey(Key.Escape) }
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
        button("Edit path").performClick()
        compose.waitForIdle()
        assertEquals("/home/me", typed(0))
        assertEquals("/home/me", session.state.value.path)
    }

    @Test
    fun `losing focus closes the path field, as onBlur does, without going anywhere`() {
        toolbar()
        button("Edit path").performClick()
        compose.waitForIdle()
        assertEquals("the field stays open once it has the focus it asked for", 2, compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
        field(0).performTextInput("/typed")
        field(1).performClick()
        compose.waitForIdle()
        assertEquals("only the filter is left", 1, compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
        assertEquals("/home/me", session.state.value.path)
        button("Edit path").performClick()
        compose.waitForIdle()
        assertEquals("the draft stays for the next time, as the web's draftPath does after a blur (only Escape drops it)", "/home/me/typed", typed(0))
    }

    @Test
    fun `the path box takes what the buttons leave, so the bar is full and the last button sits at its right edge`() {
        toolbar()
        assertEquals(1000f - 6f, button("Show hidden files").getBoundsInRoot().right.value, 0.5f)
        // 1000 - the 6 dp either side - the buttons, the filter and the gaps: far more than the 350 it was held to.
        val pathBox = button("Edit path").getBoundsInRoot().right.value + 5f - (button("Refresh").getBoundsInRoot().right.value + 4f)
        assertTrue("the path box is $pathBox dp wide", pathBox > 500f)
    }

    /** The top of a few of the bar's items with the bar held [width] wide: items on one line share a top. */
    private fun tops(width: Dp): Map<String, Float> {
        barWidth = width
        compose.waitForIdle()
        return listOf("Back", "Edit path", "List view", "Show hidden files").associateWith { button(it).getBoundsInRoot().top.value } +
            ("Filter" to field(0).getBoundsInRoot().top.value)
    }

    @Test
    fun `the filter box is 124 dp, so the bar holds it on the first line at 396 dp wide and not at 395`() {
        toolbar()
        // The history buttons 124, a gap, the path box's 8rem, a gap and the filter box 124, in the 6 dp the bar keeps either side.
        val fits = tops(396.dp)
        assertEquals(fits.getValue("Back"), fits.getValue("Filter"), 1.5f)
        assertEquals(fits.getValue("Back"), fits.getValue("Edit path"), 1.5f)
        assertTrue("the views are on the second line", fits.getValue("List view") > fits.getValue("Back") + 20f)
        val short = tops(395.dp)
        assertEquals("the path box keeps the first line", short.getValue("Back"), short.getValue("Edit path"), 1.5f)
        assertTrue("the filter wraps", short.getValue("Filter") > short.getValue("Back") + 20f)
        assertEquals("and the views follow it", short.getValue("Filter"), short.getValue("List view"), 1.5f)
    }

    @Test
    fun `a bar too narrow for the history buttons and the path box's 8rem puts the path box on a line of its own, full width`() {
        toolbar()
        val tiny = tops(200.dp)
        assertTrue("the path box is under the history buttons", tiny.getValue("Edit path") > tiny.getValue("Back") + 20f)
        val box = button("Edit path").getBoundsInRoot()
        assertEquals("it fills its line, 6 dp in from the edge, the pencil 5 dp inside the box", 200f - 6f - 5f, box.right.value, 1.5f)
    }

    @Test
    fun `the Sort dropdown is Sort by over a radio per key, then Order over one per direction, ticking what is in force`() {
        compose.setContent {
            MenuBox { HostSortMenuRows(ExplorerSort.SIZE, descending = true) { key, descending -> picks += "$key ${if (descending) "descending" else "ascending"}" } }
        }
        assertEquals(listOf("Name", "Size", "Modified", "Kind", "Ascending", "Descending"), pressable())
        compose.onNodeWithText("Sort by").assertHasNoClickAction()
        compose.onNodeWithText("Order").assertHasNoClickAction()
        val tops = listOf("Sort by", "Name", "Size", "Modified", "Kind", "Order", "Ascending", "Descending")
            .map { compose.onNodeWithText(it).getBoundsInRoot().top.value }
        assertEquals(tops.sorted(), tops)
        compose.onNodeWithText("Size").assertIsSelected()
        compose.onNodeWithText("Descending").assertIsSelected()
        for (other in listOf("Name", "Modified", "Kind", "Ascending")) compose.onNodeWithText(other).assertIsNotSelected()
    }

    @Test
    fun `a Sort radio hands on its pick beside the order or the key in force`() {
        compose.setContent {
            MenuBox { HostSortMenuRows(ExplorerSort.SIZE, descending = true) { key, descending -> picks += "$key ${if (descending) "descending" else "ascending"}" } }
        }
        compose.onNodeWithText("Kind").performClick()
        compose.onNodeWithText("Ascending").performClick()
        assertEquals(listOf("KIND descending", "SIZE ascending"), picks)
    }

    @Test
    fun `the Upload dropdown is Upload Files and Upload Folder, each saying which it is`() {
        compose.setContent { MenuBox { HostUploadMenuRows { folder -> uploads += if (folder) "folder" else "files" } } }
        assertEquals(listOf("Upload Files…", "Upload Folder…"), pressable())
        compose.onNodeWithText("Upload Folder…").performClick()
        compose.onNodeWithText("Upload Files…").performClick()
        assertEquals(listOf("folder", "files"), uploads)
    }
}
