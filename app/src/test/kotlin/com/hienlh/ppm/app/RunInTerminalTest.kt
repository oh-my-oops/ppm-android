package com.hienlh.ppm.app

import com.hienlh.ppm.state.workspace.DOCK_PANEL_ID
import com.hienlh.ppm.state.workspace.DockState
import com.hienlh.ppm.state.workspace.LayoutStorage
import com.hienlh.ppm.state.workspace.NewTab
import com.hienlh.ppm.state.workspace.PanelLayout
import com.hienlh.ppm.state.workspace.WorkspacePanel
import com.hienlh.ppm.state.workspace.WorkspaceState
import com.hienlh.ppm.state.workspace.WorkspaceStore
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a command run from a code block goes (`runInTerminal` and `use-terminal-command-queue`): a
 * terminal on screen takes it, whichever project it was started for, and with none on screen one
 * opens in the dock to hold it. The web types it into every terminal on screen; here one takes it.
 */
class RunInTerminalTest {
    private fun terminal(id: String, project: String? = "ppm") = WorkspaceTab(
        id, "terminal", "Terminal", project,
        JsonObject(if (project.isNullOrEmpty()) emptyMap() else mapOf("projectName" to JsonPrimitive(project))),
    )

    private fun editor(id: String) = WorkspaceTab(id, "editor", id, "ppm")

    /** A panel showing [front], which is its first tab unless said. */
    private fun panel(id: String, vararg tabs: WorkspaceTab, front: WorkspaceTab? = tabs.firstOrNull()) =
        WorkspacePanel(id, tabs.toList(), front?.id)

    /** The grid of [grid]'s rows, the dock when there is one, and [elsewhere]: panels that are in no grid on screen. */
    private fun state(
        vararg grid: List<WorkspacePanel>,
        focused: String = grid.first().first().id,
        dock: WorkspacePanel? = null,
        dockVisible: Boolean = dock != null,
        elsewhere: List<WorkspacePanel> = emptyList(),
    ) = WorkspaceState(
        panels = (grid.toList().flatten() + elsewhere + listOfNotNull(dock?.copy(id = DOCK_PANEL_ID))).associateBy { it.id },
        grid = grid.map { row -> row.map { it.id } },
        focusedPanelId = focused,
        currentProject = "ppm",
        dock = DockState(visible = dockVisible, height = 30.0),
    )

    @Test
    fun `a terminal started for another project takes the command`() {
        val other = terminal("terminal:1", "other")
        assertSame(other, shownTerminalOf(state(listOf(panel("p1", other)))))
    }

    @Test
    fun `a terminal of the host's own explorer takes it too`() {
        val host = terminal("terminal:1", null)
        assertSame(host, shownTerminalOf(state(listOf(panel("p1", host)))))
    }

    @Test
    fun `with two on screen the focused panel's takes it, and it alone`() {
        val inDock = terminal("terminal:1")
        val inGrid = terminal("terminal:2")
        val shown = state(listOf(panel("p1", inGrid)), dock = panel("d", inDock))
        assertSame(inGrid, shownTerminalOf(shown))
        val twoPanels = state(listOf(panel("p1", terminal("terminal:3")), panel("p2", inGrid)), focused = "p2")
        assertSame(inGrid, shownTerminalOf(twoPanels))
    }

    @Test
    fun `with the focus on a panel that shows no terminal the first shown takes it, the dock's before the grid's`() {
        val inDock = terminal("terminal:1")
        val inGrid = terminal("terminal:2")
        val withDock = state(listOf(panel("p1", editor("editor:a")), panel("p2", inGrid)), dock = panel("d", inDock))
        assertSame(inDock, shownTerminalOf(withDock))
        // Without the dock on screen, the grid's.
        assertSame(inGrid, shownTerminalOf(state(listOf(panel("p1", editor("editor:a")), panel("p2", inGrid)), dock = panel("d", inDock), dockVisible = false)))
    }

    @Test
    fun `the grid's panels are tried in grid order`() {
        val first = terminal("terminal:1")
        val second = terminal("terminal:2")
        val grid = arrayOf(listOf(panel("p1", editor("editor:a")), panel("p2", first)), listOf(panel("p3", second)))
        assertSame(first, shownTerminalOf(state(*grid)))
        assertSame(second, shownTerminalOf(state(*grid, focused = "p3")))
    }

    @Test
    fun `only a panel's front tab is on screen, and only a visible dock's`() {
        val inFront = editor("editor:a")
        val behindIt = panel("p1", terminal("terminal:1"), inFront, front = inFront)
        assertNull(shownTerminalOf(state(listOf(behindIt))))
        assertNull(shownTerminalOf(state(listOf(panel("p1", editor("editor:a"))), dock = panel("d", terminal("terminal:2")), dockVisible = false)))
    }

    @Test
    fun `a focus the grid does not hold is ignored`() {
        val inGrid = terminal("terminal:2")
        val grid = listOf(panel("p1", editor("editor:a")), panel("p2", inGrid))
        assertSame(inGrid, shownTerminalOf(state(grid, focused = "gone")))
        // A panel of a project seen earlier, kept alive off screen, is not on screen with its terminal.
        val offScreen = panel("x", terminal("terminal:9", "other"))
        assertSame(inGrid, shownTerminalOf(state(grid, focused = "x", elsewhere = listOf(offScreen))))
        // Nor is the dock the focused panel: the grid's focus stays where it was.
        val inDock = terminal("terminal:1")
        assertSame(inDock, shownTerminalOf(state(grid, focused = DOCK_PANEL_ID, dock = panel("d", inDock))))
    }

    @Test
    fun `with none on screen none takes it`() {
        assertNull(shownTerminalOf(state(listOf(panel("p1", editor("editor:a"))))))
        assertNull(shownTerminalOf(state(listOf(panel("p1")))))
    }

    @Test
    fun `the tab that holds a command is a terminal for the dock, on the project and carrying it`() {
        val tab = runInTerminalTab("ls -la", "ppm")
        assertEquals("terminal", tab.type)
        assertEquals("Terminal", tab.title)
        assertEquals("ppm", tab.projectId)
        assertTrue(tab.closable)
        assertEquals(
            mapOf("projectName" to JsonPrimitive("ppm"), "pendingCommand" to JsonPrimitive("ls -la")),
            tab.metadata.toMap(),
        )
        assertEquals(listOf("projectName", "pendingCommand"), tab.metadata.keys.toList())
    }

    @Test
    fun `with no project the tab has none, on it or in its metadata`() {
        val none = runInTerminalTab("brew install ffmpeg", null)
        assertNull(none.projectId)
        assertEquals(mapOf("pendingCommand" to JsonPrimitive("brew install ffmpeg")), none.metadata.toMap())
        // An empty project is the tab's as given but is not named, as `projectName ? { projectName } : {}` has it.
        val empty = runInTerminalTab("ls", "")
        assertEquals("", empty.projectId)
        assertEquals(mapOf("pendingCommand" to JsonPrimitive("ls")), empty.metadata.toMap())
    }

    @Test
    fun `once the dock holds the command's terminal, the next command goes to it`() {
        val store = WorkspaceStore(object : LayoutStorage {
            override fun load(project: String): PanelLayout? = null

            override fun save(project: String, layout: PanelLayout) = Unit
        }, isMobile = { false })
        store.switchProject("ppm")
        store.openTab(NewTab("editor", "a.ts", "ppm", JsonObject(mapOf("filePath" to JsonPrimitive("a.ts")))))
        assertNull(shownTerminalOf(store.state.value))

        val id = store.openInDock(runInTerminalTab("ls", "ppm"))

        val state = store.state.value
        assertTrue(state.dock.visible)
        val held = state.dockPanel!!.tab(id)!!
        assertEquals("ls", held.metaString("pendingCommand"))
        assertEquals("ppm", held.metaString("projectName"))
        assertEquals(id, shownTerminalOf(state)?.id)
    }
}
