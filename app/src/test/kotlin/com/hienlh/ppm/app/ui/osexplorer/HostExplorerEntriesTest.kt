package com.hienlh.ppm.app.ui.osexplorer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.protocol.HostDirectory
import com.hienlh.ppm.protocol.HostFileEntry
import com.hienlh.ppm.protocol.HostPlace
import com.hienlh.ppm.state.files.FileMutations
import com.hienlh.ppm.state.osexplorer.ExplorerSession
import com.hienlh.ppm.state.osexplorer.ExplorerView
import com.hienlh.ppm.state.osexplorer.HostFileActions
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The views' rows and the space between them as the web has them (`list-view.tsx`, `icons-view.tsx`): what a folder with nothing to
 * show says, in both of them and not while a name is being typed; the Icons view's column count, which the arrow keys step by, and
 * its scrolling to the row the cursor moved to; and what a press on the empty space does to the selection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w700dp-h700dp")
class HostExplorerEntriesTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private fun dir(name: String) = HostFileEntry(name, "/home/me/" + name, "directory", "directory")

    private fun directory(vararg names: String) =
        HostDirectory(names.map(::dir), "/home/me", "/home", listOf(HostPlace("me", "/home/me")), "/", truncated = false)

    private lateinit var session: ExplorerSession

    @After
    fun close() = session.close()

    /** Each menu asked for: the names of the entries it is for, none for the folder's own. */
    private val menus = mutableListOf<List<String>>()

    private val columns = HostGridColumns()

    /** How wide the explorer is, to be changed while it shows. */
    private var width by mutableStateOf(400.dp)

    private fun explorer(view: ExplorerView, folder: HostDirectory = directory("a", "b", "c"),
                         commandsOn: (ExplorerSession) -> HostExplorerCommands? = { null }) {
        session = explorerSessionOn(folder)
        session.preferences.update(session.preferences.state.value.copy(view = view))
        val commands = commandsOn(session)
        compose.setContent {
            val state by session.state.collectAsState()
            val preferences by session.preferences.state.collectAsState()
            val entries = remember(state.directory, state.filter, preferences) { session.visibleEntries() }
            Box(Modifier.size(width, 600.dp).testTag("explorer")) {
                HostExplorerEntries(
                    null, session, entries, state, preferences, mobile = false, selectMode = false, windows = false, commands = commands,
                    cut = emptySet(), onFocus = {}, onOpen = {},
                    onMenu = { targets, _ -> menus += targets.map { it.name } },
                    gridColumns = columns,
                )
            }
        }
    }

    /** Commands over a server nothing answers: enough to start typing a name, which sends nothing. */
    private fun commandsFor(on: ExplorerSession): HostExplorerCommands {
        val api = ApiClient("http://localhost:1/".toHttpUrl(), "secret")
        return HostExplorerCommands({}, api, on, HostFileActions(api, FileMutations(api), "/", null), null, CoroutineScope(Dispatchers.Unconfined + SupervisorJob()))
    }

    private fun pressAt(x: Float, y: Float) = with(compose.density) { Offset(x.dp.toPx(), y.dp.toPx()) }

    private fun emptySpace() = compose.onNodeWithTag("explorer")

    private fun row(name: String) = compose.onNodeWithContentDescription(name)

    @Test
    fun `an empty folder says so in the list`() {
        explorer(ExplorerView.LIST, directory())
        compose.onNodeWithText("This folder is empty.").assertIsDisplayed()
    }

    @Test
    fun `an empty folder says so in the grid, as the list does`() {
        explorer(ExplorerView.ICONS, directory())
        compose.onNodeWithText("This folder is empty.").assertIsDisplayed()
    }

    @Test
    fun `a filter that matches nothing says so in the list`() {
        explorer(ExplorerView.LIST)
        session.setFilter("zzz")
        compose.onNodeWithText("No entries match the filter.").assertIsDisplayed()
        compose.onNodeWithText("This folder is empty.").assertDoesNotExist()
    }

    @Test
    fun `a filter that matches nothing says so in the grid`() {
        explorer(ExplorerView.ICONS)
        session.setFilter("zzz")
        compose.onNodeWithText("No entries match the filter.").assertIsDisplayed()
        compose.onNodeWithText("This folder is empty.").assertDoesNotExist()
    }

    @Test
    fun `a folder with entries to show says nothing`() {
        explorer(ExplorerView.ICONS)
        compose.onNodeWithText("This folder is empty.").assertDoesNotExist()
        compose.onNodeWithText("No entries match the filter.").assertDoesNotExist()
    }

    @Test
    fun `the list says nothing of an empty folder while a new name is being typed`() {
        explorer(ExplorerView.LIST, directory()) { commandsFor(it).also { commands -> commands.create(folder = false) } }
        compose.onNodeWithText("This folder is empty.").assertDoesNotExist()
    }

    @Test
    fun `the grid says nothing of an empty folder while a new name is being typed`() {
        explorer(ExplorerView.ICONS, directory()) { commandsFor(it).also { commands -> commands.create(folder = true) } }
        compose.onNodeWithText("This folder is empty.").assertDoesNotExist()
    }

    @Test
    fun `the grid notes how many columns it laid out, and that is how many tiles are in its first row`() {
        val names = (0 until 8).map { "d%02d".format(it) }
        explorer(ExplorerView.ICONS, directory(*names.toTypedArray()))
        for (wide in listOf(600.dp, 300.dp, 450.dp)) {
            width = wide
            compose.waitForIdle()
            val tops = names.map { row(it).getBoundsInRoot().top.value }
            val inFirstRow = tops.count { abs(it - tops.first()) < 1f }
            assertTrue("$inFirstRow tiles across at $wide", inFirstRow > 1)
            assertEquals("at $wide", inFirstRow, columns.count)
        }
    }

    @Test
    fun `the grid scrolls to the tile the cursor moved to when it is out of sight`() {
        val names = (0 until 60).map { "d%02d".format(it) }
        explorer(ExplorerView.ICONS, directory(*names.toTypedArray()))
        session.select("/home/me/d59")
        compose.waitForIdle()
        row("d59").assertIsDisplayed()
    }

    @Test
    fun `a press on the list's empty space empties the selection and the cursor`() {
        explorer(ExplorerView.LIST)
        session.select("/home/me/a")
        emptySpace().performTouchInput { click(pressAt(200f, 400f)) }
        compose.waitForIdle()
        assertEquals(emptySet<String>(), session.state.value.selection)
        assertNull(session.state.value.anchor)
    }

    @Test
    fun `a press on the grid's empty space empties the selection and the cursor`() {
        explorer(ExplorerView.ICONS)
        session.select("/home/me/b")
        emptySpace().performMouseInput { click(pressAt(200f, 450f)) }
        compose.waitForIdle()
        assertEquals(emptySet<String>(), session.state.value.selection)
        assertNull(session.state.value.anchor)
    }

    @Test
    fun `a tap on a row selects it and does not empty the selection it made`() {
        explorer(ExplorerView.LIST)
        session.select("/home/me/a")
        row("b").performClick()
        // A row that can be double-clicked answers a single click once the double-click's time is out.
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertEquals(setOf("/home/me/b"), session.state.value.selection)
        assertEquals("/home/me/b", session.state.value.anchor)
    }

    @Test
    fun `a right click on the empty space empties the selection and opens the folder's menu, as the web's mousedown and contextmenu do`() {
        explorer(ExplorerView.LIST)
        session.select("/home/me/a")
        emptySpace().performMouseInput { rightClick(pressAt(150f, 300f)) }
        compose.waitForIdle()
        assertEquals(emptySet<String>(), session.state.value.selection)
        assertEquals(listOf(emptyList<String>()), menus)
    }

    @Test
    fun `a press held on the empty space opens the folder's menu and leaves the selection, which a finger's long press never clears on the web`() {
        explorer(ExplorerView.LIST)
        session.select("/home/me/a")
        emptySpace().performTouchInput { longClick(pressAt(200f, 400f)) }
        compose.waitForIdle()
        assertEquals(setOf("/home/me/a"), session.state.value.selection)
        assertEquals(listOf(emptyList<String>()), menus)
    }

    @Test
    fun `a right click on a row opens that row's menu and leaves the selection it just made`() {
        explorer(ExplorerView.LIST)
        row("c").performMouseInput { rightClick() }
        compose.waitForIdle()
        assertEquals(setOf("/home/me/c"), session.state.value.selection)
        assertEquals(listOf(listOf("c")), menus)
    }
}
