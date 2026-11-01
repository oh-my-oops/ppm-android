package com.hienlh.ppm.state.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Which tabs the web's tab pool mounts when it first sees them (tab-pool.tsx): the front tab of every panel, on screen or not.
 * The cases a terminal depends on are the ones where "in front" and "on screen" part: a dock that is collapsed on a phone or
 * hidden on a tablet, a minimized window, a tab behind another, another project's grid. A shell whose tab is not mounted is not
 * attached, and the server ends one that nothing has been attached to for 30 minutes.
 */
class TabMountsTest {
    private fun terminal(id: String, project: String? = "app") = WorkspaceTab(id, "terminal", "Terminal", project)

    private fun editor(id: String, project: String? = "app") = WorkspaceTab(id, "editor", id, project)

    /** A panel with [front] in front, the first tab unless said. */
    private fun panel(id: String, vararg tabs: WorkspaceTab, front: WorkspaceTab? = tabs.firstOrNull()) =
        WorkspacePanel(id, tabs.toList(), front?.id)

    private fun state(
        vararg grid: List<WorkspacePanel>,
        dock: WorkspacePanel? = null,
        dockVisible: Boolean = false,
        windows: Map<String, WorkspacePanel> = emptyMap(),
        kept: Map<String, List<WorkspacePanel>> = emptyMap(),
        project: String? = "app",
    ): WorkspaceState = WorkspaceState(
        panels = (grid.toList().flatten() + kept.values.flatten() + listOfNotNull(dock?.copy(id = DOCK_PANEL_ID)) +
            windows.map { (id, panel) -> panel.copy(id = windowPanelId(id)) }).associateBy { it.id },
        grid = grid.map { row -> row.map { it.id } },
        focusedPanelId = grid.first().first().id,
        currentProject = project,
        dock = DockState(visible = dockVisible, height = 30.0),
        projectGrids = kept.mapValues { (_, panels) -> panels.map { listOf(it.id) } },
    )

    private fun front(state: WorkspaceState) = frontTabs(state).map { it.id }

    @Test
    fun `the front tab of every panel of the open project, and not a tab behind one`() {
        val a = terminal("terminal:1")
        val b = terminal("terminal:2")
        val c = terminal("terminal:3")
        // The third panel has no tab in front at all.
        val shown = state(listOf(panel("p1", a, editor("editor:x")), panel("p2", editor("editor:y"), b, front = b)), listOf(panel("p3", c, front = null)))
        assertEquals(listOf("terminal:1", "terminal:2"), front(shown))
    }

    @Test
    fun `a hidden dock's front terminal, as on a tablet that has the dock hidden`() {
        val inDock = terminal("terminal:1")
        val behind = terminal("terminal:2")
        val hidden = state(listOf(panel("p1", editor("editor:x"))), dock = panel("d", inDock, behind), dockVisible = false)
        assertEquals(listOf("editor:x", "terminal:1"), front(hidden))
    }

    @Test
    fun `a phone restores the dock collapsed, and its front terminal is still there`() {
        val saved = PanelLayout(
            panels = mapOf("p1" to panel("p1", editor("editor:x"))),
            grid = listOf(listOf("p1")),
            focusedPanelId = "p1",
            dock = DockState(visible = true, height = 30.0),
            dockPanel = WorkspacePanel(DOCK_PANEL_ID, listOf(terminal("terminal:1")), "terminal:1", listOf("terminal:1")),
        )
        val store = WorkspaceStore(
            object : LayoutStorage {
                override fun load(project: String) = saved
                override fun save(project: String, layout: PanelLayout) {}
            },
            isMobile = { true },
        )
        store.switchProject("app")
        assertFalse(store.state.value.dock.visible)
        assertEquals(listOf("editor:x", "terminal:1"), front(store.state.value))
    }

    @Test
    fun `a minimized window's terminal, its panel being in no grid`() {
        val inWindow = terminal("terminal:1")
        val shown = state(listOf(panel("p1", editor("editor:x"))), windows = mapOf("w1" to panel("w", inWindow)))
        assertEquals(listOf("editor:x", "terminal:1"), front(shown))
    }

    @Test
    fun `a project seen earlier keeps its grid, and that grid's front tab is still there`() {
        val here = terminal("terminal:1")
        val there = terminal("terminal:2", "other")
        val behindThere = terminal("terminal:3", "other")
        val shown = state(listOf(panel("p1", here)), kept = mapOf("other" to listOf(panel("x", there, behindThere))))
        assertEquals(listOf("terminal:1", "terminal:2"), front(shown))
    }

    @Test
    fun `the project a switch has just left keeps its grid, and the tab it had in front is still in front there`() {
        val store = WorkspaceStore(
            object : LayoutStorage {
                override fun load(project: String): PanelLayout? = null
                override fun save(project: String, layout: PanelLayout) {}
            },
            isMobile = { false },
        )
        store.switchProject("a")
        store.openTab(NewTab("terminal", "Terminal", "a"))
        val inFront = store.openTab(NewTab("terminal", "Terminal", "a"))
        store.switchProject("b")
        val here = store.openTab(NewTab("terminal", "Terminal", "b"))
        assertEquals(listOf(here, inFront), front(store.state.value))
    }

    @Test
    fun `the open project's grid does not hold another project's tab, which the web guards against`() {
        val foreign = terminal("terminal:2", "other")
        val shown = state(listOf(panel("p1", foreign)))
        assertEquals(emptyList<String>(), front(shown))
        // A tab that belongs to no project is any project's, as a shell opened from the host's own explorer is.
        assertEquals(listOf("terminal:3"), front(state(listOf(panel("p1", terminal("terminal:3", null))))))
        // Nor is it guarded when no project is open.
        assertEquals(listOf("terminal:2"), front(state(listOf(panel("p1", foreign)), project = null)))
    }

    @Test
    fun `the dock and the windows are shared by every project, so they hold tabs of any`() {
        val shown = state(
            listOf(panel("p1", editor("editor:x"))),
            dock = panel("d", terminal("terminal:1", "other")),
            windows = mapOf("w1" to panel("w", terminal("terminal:2", "third"))),
        )
        assertEquals(listOf("editor:x", "terminal:2", "terminal:1"), front(shown))
    }

    @Test
    fun `a tab that is in two panels at once belongs to the first, as the web reads them`() {
        val tab = terminal("terminal:1")
        val other = terminal("terminal:2")
        // Behind in the grid, in front in the dock: the grid's word stands.
        val behind = state(listOf(panel("p1", other, tab, front = other)), dock = panel("d", tab))
        assertEquals(listOf("terminal:2"), front(behind))
        // In front in the grid, behind in the dock: in front, and the dock's front tab, which the grid holds behind, is not.
        val inFront = state(listOf(panel("p1", tab, other, front = tab)), dock = panel("d", other, tab, front = other))
        assertEquals(listOf("terminal:1"), front(inFront))
    }

    @Test
    fun `a workspace with no dock panel, no windows and a grid that names a panel that is gone is read all the same`() {
        val shown = state(listOf(panel("p1", terminal("terminal:1"))))
        assertEquals(listOf("terminal:1"), front(shown))
        assertEquals(emptyList<String>(), front(shown.copy(grid = listOf(listOf("gone")))))
    }
}
