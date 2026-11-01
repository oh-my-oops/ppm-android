package com.hienlh.ppm.app.ui.osexplorer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.protocol.HostDirectory
import com.hienlh.ppm.protocol.HostFileEntry
import com.hienlh.ppm.protocol.HostPlace
import com.hienlh.ppm.state.osexplorer.ExplorerView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The folder's own menu from the empty space of the list and the grid (`backgroundLongPress` and the `ContextMenuTrigger`
 * in `list-view.tsx` and `icons-view.tsx`): a right click or a press held on the space the rows leave opens it, and the
 * same on a row opens that row's menu alone. The rows are folders, whose icon needs nothing the unit tests lack.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w500dp-h700dp")
class HostExplorerBackgroundMenuTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private fun dir(name: String) = HostFileEntry(name, "/home/me/$name", "directory", "directory")

    private val session = explorerSessionOn(
        HostDirectory(listOf(dir("a"), dir("b"), dir("c")), "/home/me", "/home", listOf(HostPlace("me", "/home/me")), "/", truncated = false),
    )

    @After
    fun close() = session.close()

    /** Each menu asked for, in order: the names of the entries it is for (none for the folder's own) and where. */
    private val menus = mutableListOf<Pair<List<String>, Offset>>()

    private fun explorer(view: ExplorerView, mobile: Boolean = false) {
        session.preferences.update(session.preferences.state.value.copy(view = view))
        compose.setContent {
            val state by session.state.collectAsState()
            val preferences by session.preferences.state.collectAsState()
            val entries = remember(state.directory, state.filter, preferences) { session.visibleEntries() }
            Box(Modifier.size(400.dp, 600.dp).testTag("explorer")) {
                HostExplorerEntries(
                    null, session, entries, state, preferences, mobile, selectMode = false, windows = false, commands = null,
                    cut = emptySet(), onFocus = {}, onOpen = {},
                    onMenu = { targets, at -> menus += targets.map { it.name } to at },
                )
            }
        }
    }

    /** Where the point [x], [y] dp into the explorer is in the window, which is where a menu is asked to open. */
    private fun inWindow(x: Float, y: Float): Offset {
        val origin = compose.onNodeWithTag("explorer").fetchSemanticsNode().positionInWindow
        return with(compose.density) { Offset(origin.x + x.dp.toPx(), origin.y + y.dp.toPx()) }
    }

    private fun assertMenu(names: List<String>, at: Offset) {
        assertEquals(1, menus.size)
        assertEquals(names, menus.single().first)
        assertEquals(at.x, menus.single().second.x, 2f)
        assertEquals(at.y, menus.single().second.y, 2f)
    }

    private fun emptySpace() = compose.onNodeWithTag("explorer")

    private fun pressAt(x: Float, y: Float) = with(compose.density) { Offset(x.dp.toPx(), y.dp.toPx()) }

    @Test
    fun `a press held on the list's empty space opens the folder's own menu there`() {
        explorer(ExplorerView.LIST)
        emptySpace().performTouchInput { longClick(pressAt(200f, 400f)) }
        assertMenu(emptyList(), inWindow(200f, 400f))
    }

    @Test
    fun `a right click on the list's empty space opens the folder's own menu there`() {
        explorer(ExplorerView.LIST)
        emptySpace().performMouseInput { rightClick(pressAt(150f, 300f)) }
        assertMenu(emptyList(), inWindow(150f, 300f))
    }

    @Test
    fun `a press held on a row opens that row's menu and not the folder's as well`() {
        explorer(ExplorerView.LIST)
        compose.onNodeWithContentDescription("b").performTouchInput { longClick() }
        assertEquals(1, menus.size)
        assertEquals(listOf("b"), menus.single().first)
    }

    @Test
    fun `a right click on a row opens that row's menu and not the folder's as well`() {
        explorer(ExplorerView.LIST)
        compose.onNodeWithContentDescription("c").performMouseInput { rightClick() }
        assertEquals(1, menus.size)
        assertEquals(listOf("c"), menus.single().first)
    }

    @Test
    fun `a tap on the list's empty space opens no menu`() {
        explorer(ExplorerView.LIST)
        emptySpace().performTouchInput { click(pressAt(200f, 400f)) }
        assertEquals(emptyList<Pair<List<String>, Offset>>(), menus)
    }

    @Test
    fun `a press held on the icons grid's empty space opens the folder's own menu there`() {
        explorer(ExplorerView.ICONS)
        emptySpace().performTouchInput { longClick(pressAt(200f, 450f)) }
        assertMenu(emptyList(), inWindow(200f, 450f))
    }

    @Test
    fun `a right click on the icons grid's empty space opens the folder's own menu there`() {
        explorer(ExplorerView.ICONS)
        emptySpace().performMouseInput { rightClick(pressAt(120f, 350f)) }
        assertMenu(emptyList(), inWindow(120f, 350f))
    }

    @Test
    fun `a press held on a tile opens that tile's menu alone, and so does a right click`() {
        explorer(ExplorerView.ICONS)
        compose.onNodeWithContentDescription("a").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("c").performMouseInput { rightClick() }
        assertEquals(listOf(listOf("a"), listOf("c")), menus.map { it.first })
    }

    @Test
    fun `on the phone a press held on the list's empty space asks for the folder's menu as well`() {
        explorer(ExplorerView.LIST, mobile = true)
        emptySpace().performTouchInput { longClick(pressAt(200f, 400f)) }
        assertMenu(emptyList(), inWindow(200f, 400f))
    }
}
