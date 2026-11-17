package com.hienlh.ppm.state.workspace

import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.state.windows.WindowStore
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A session picked in the chat history always ends up on screen (the tablet bug of 2026-11-17:
 * after a long run, a tap on an old session did nothing until every tab was closed). Each case
 * drives the store into the state the bug needs through its public operations only.
 */
class OpenChatInViewTest {
    private class Storage : LayoutStorage {
        val layouts = HashMap<String, PanelLayout>()

        override fun load(project: String) = layouts[project]

        override fun save(project: String, layout: PanelLayout) {
            layouts[project] = layout
        }
    }

    private val storage = Storage()
    private val warnings = mutableListOf<String>()
    private var ids = 0
    private var windowIds = 0
    private val windows = WindowStore(MemoryValues(), "windows", now = { 1_000L }, randomSuffix = { "w${++windowIds}" })

    private fun store() = WorkspaceStore(
        storage, isMobile = { false }, now = { 1_000L }, randomId = { "r${++ids}" }, windows = windows,
        warn = { warnings += it },
    )

    private val WorkspaceStore.s get() = state.value

    private fun obj(text: String) = WireJson.parse(text) as JsonObject

    /** What the history's row opens (`AppModel.openTab(session)`). */
    private fun chat(session: String, project: String = "app") =
        NewTab("chat", "Chat", project, obj("""{"projectName":"$project","sessionId":"$session","providerId":"claude"}"""))

    private fun editor(path: String, project: String = "app") = NewTab("editor", path, project, obj("""{"filePath":"$path"}"""))

    /** [tabId] is in front of the panel that has focus, and that panel is one the grid shows. */
    private fun WorkspaceStore.assertShown(tabId: String) {
        assertTrue("opened nothing", tabId.isNotEmpty())
        assertTrue("focus ${s.focusedPanelId} is not on the grid ${s.grid}", s.focusedPanelId in s.gridPanelIds)
        assertEquals(tabId, s.activeGridTab?.id)
    }

    /**
     * Every panel holding tabs is on a grid (this project's or a kept one), the dock or a window,
     * and every panel the grid names exists. (The empty panel of the layout with no project,
     * made before the first project, is left behind by design.)
     */
    private fun WorkspaceStore.assertNoStrayPanels() {
        val held = s.gridPanelIds.toSet() + s.projectGrids.values.flatMap { it.flatten() } + DOCK_PANEL_ID
        val stray = s.panels.filter { (id, panel) -> id !in held && !isWindowPanelId(id) && panel.tabs.isNotEmpty() }.keys
        assertTrue("panels no grid holds: $stray", stray.isEmpty())
        for (pid in s.gridPanelIds) assertTrue("the grid names $pid, which is gone", pid in s.panels)
    }

    @Test
    fun `a split another device took away leaves no hidden panel for the next chat to land in`() {
        val store = store()
        store.switchProject("app")
        val main = store.s.focusedPanelId
        store.openTab(editor("a.ts"))
        val old = store.openTab(chat("s1"))
        // Split after the project was loaded: the split's panel is not in the snapshot taken then.
        assertTrue(store.splitPanel(SplitDirection.Right, old, main))
        // The web, still holding the layout from before the split, saves it over the server's
        // copy; back in front, the app reloads the project from it.
        storage.layouts["app"] = PanelLayout(
            panels = linkedMapOf(main to WorkspacePanel(main, listOf(editor("a.ts").withId("editor:a.ts")), "editor:a.ts", listOf("editor:a.ts"))),
            grid = listOf(listOf(main)),
            focusedPanelId = main,
        )
        store.reloadProject("app")
        assertEquals(listOf(listOf(main)), store.s.grid)

        // The chat the web closed opens again, in view; so does any other.
        store.assertShown(store.openTab(chat("s1")))
        store.assertShown(store.openTab(chat("s2")))
        store.assertShown(store.openTab(chat("s1")))
        store.assertNoStrayPanels()
        // Mended where it broke: no open needed a detour.
        assertEquals(emptyList<String>(), warnings)
    }

    @Test
    fun `a panel emptied in another project's kept grid leaves that project's focus on a panel it still has`() {
        val store = store()
        store.switchProject("app")
        val appMain = store.s.focusedPanelId
        store.openTab(editor("README.md"))
        store.openTab(editor("b.ts"))
        // README.md beside, focused: the panel that will go.
        assertTrue(store.splitPanel(SplitDirection.Right, "editor:README.md", appMain))

        store.switchProject("other")
        val otherMain = store.s.focusedPanelId
        store.openTab(editor("x.ts", "other"))
        store.openTab(editor("y.ts", "other"))
        assertTrue(store.splitPanel(SplitDirection.Right, "editor:y.ts", otherMain))
        // A close that names only the tab id (a design canvas's, a remote desktop's) finds the
        // first panel holding that id: here app's split, still in memory behind "other".
        store.closeTab("editor:README.md")

        store.switchProject("app")
        store.assertShown(store.openTab(chat("s1")))
        store.assertNoStrayPanels()
        assertEquals(emptyList<String>(), warnings)
    }

    @Test
    fun `a chat found in another project's kept panel comes to this project's grid`() {
        val store = store()
        store.switchProject("app")
        store.switchProject("other")
        store.openTab(editor("x.ts", "other"))
        // Opened while "other" was shown (a notification row, say): the tab sits in other's grid.
        val stray = store.openTab(chat("s1", "app"))
        store.switchProject("app")
        store.openTab(editor("a.ts"))

        val opened = store.openTab(chat("s1"))
        assertEquals(stray, opened)
        store.assertShown(opened)
        assertTrue(warnings.single().contains("not on screen"))
        // And the next one opens beside it, not in the panel it came from.
        store.assertShown(store.openTab(chat("s2")))
    }

    @Test
    fun `a chat left in a window panel with no window alive comes to the grid`() {
        val store = store()
        store.switchProject("app")
        val main = store.s.focusedPanelId
        store.openTab(editor("a.ts"))
        val old = store.openTab(chat("s1"))
        val windowId = store.popOutTab(old, main)!!
        // The window goes without its panel (WindowStore closed directly).
        windows.close(windowId)

        store.assertShown(store.openTab(chat("s1")))
        // The dead window's panel, emptied by the move, goes with it.
        assertTrue(store.s.panels.keys.none(::isWindowPanelId))
        assertTrue(warnings.single().contains("not on screen"))
    }
}
