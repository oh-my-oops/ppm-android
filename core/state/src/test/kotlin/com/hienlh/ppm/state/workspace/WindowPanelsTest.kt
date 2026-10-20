package com.hienlh.ppm.state.workspace

import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.windows.Bounds
import com.hienlh.ppm.state.windows.MAX_WINDOWS
import com.hienlh.ppm.state.windows.WindowKind
import com.hienlh.ppm.state.windows.WindowStore
import com.hienlh.ppm.state.windows.WindowVisualState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tabs detached into floating windows (window-panel-actions.ts, -persistence.ts, -reconcile.ts, close-window.ts). */
class WindowPanelsTest {
    private class Storage(vararg initial: Pair<String, PanelLayout>) : LayoutStorage {
        val layouts = hashMapOf(*initial)
        var windowPanels: String? = null

        override fun load(project: String) = layouts[project]

        override fun save(project: String, layout: PanelLayout) {
            layouts[project] = layout
        }

        override fun loadWindowPanels() = windowPanels

        override fun saveWindowPanels(text: String?) {
            windowPanels = text
        }
    }

    private val values = MemoryValues()
    private var mobile = false
    private var ids = 0
    private var windowIds = 0
    private val windows = WindowStore(values, "windows", now = { 1_000L }, randomSuffix = { "w${++windowIds}" })

    private fun tab(id: String, project: String = "app", type: String = id.substringBefore(':'), metadata: String = "{}") =
        """{"id":"$id","type":"$type","title":"$id","projectId":"$project","metadata":$metadata,"closable":true}"""

    private fun panel(id: String, vararg tabs: String): String {
        val tabIds = tabs.map { (WireJson.parse(it) as JsonObject)["id"].string!! }
        return """"$id":{"id":"$id","tabs":[${tabs.joinToString(",")}],"activeTabId":${tabIds.firstOrNull()?.let { "\"$it\"" } ?: "null"},"tabHistory":[${tabIds.joinToString(",") { "\"$it\"" }}]}"""
    }

    private fun layout(vararg panels: String, grid: String, focused: String, dock: String = panel(DOCK_PANEL_ID)) =
        PanelLayout.fromJson(WireJson.parse("""{"panels":{${panels.joinToString(",")}},"grid":$grid,"focusedPanelId":"$focused","dockPanel":${dock.substringAfter(":")}}"""))!!

    /** p1 [a, b] beside p2 [terminal], p1 focused, the dock holding a terminal. */
    private fun twoPanels() = layout(
        panel("p1", tab("editor:a.kt"), tab("editor:b.kt")),
        panel("p2", tab("terminal:1")),
        grid = """[["p1","p2"]]""",
        focused = "p1",
        dock = panel(DOCK_PANEL_ID, tab("terminal:2")),
    )

    private fun store(storage: Storage) =
        WorkspaceStore(storage, isMobile = { mobile }, now = { 1_000L }, randomId = { "r${++ids}" }, windows = windows).also { it.switchProject("app") }

    private val WorkspaceStore.s get() = state.value

    private val openWindows get() = windows.state.value.windows

    @Test
    fun `a popped out tab moves into an off-grid window panel and focus stays on the grid`() {
        val storage = Storage("app" to twoPanels())
        val store = store(storage)
        val windowId = store.popOutTab("editor:a.kt", "p1")!!
        val panelId = windowPanelId(windowId)

        val win = openWindows.getValue(windowId)
        assertEquals(WindowKind.TabHost, win.kind)
        assertEquals("p1", win.payload!!["originPanelId"].string)
        assertEquals("editor:a.kt", win.payload!!["title"].string)
        assertNull(win.payload!!["closeTabsOnClose"])
        assertEquals(listOf("editor:a.kt"), store.s.panels.getValue(panelId).tabs.map { it.id })
        assertEquals(listOf(listOf("p1", "p2")), store.s.grid)
        assertEquals("p1", store.s.focusedPanelId)
        // Saved on this device; the project's layout no longer holds the tab, nor the window's panel.
        assertTrue(storage.windowPanels!!.contains("editor:a.kt"))
        val saved = storage.layouts.getValue("app")
        assertEquals("p1", saved.focusedPanelId)
        assertFalse(saved.panels.keys.any(::isWindowPanelId))
        assertEquals(listOf("editor:b.kt"), saved.panels.getValue("p1").tabs.map { it.id })
        // The next tab opens in the grid, not in the window.
        store.openTab(NewTab("editor", "c.kt", "app", WireJson.parse("""{"filePath":"c.kt"}""") as JsonObject))
        assertEquals("editor:c.kt", store.s.panels.getValue("p1").activeTabId)
    }

    @Test
    fun `popping out a panel's last tab closes the panel, but never the grid's last`() {
        val store = store(Storage("app" to twoPanels()))
        store.popOutTab("terminal:1", "p2")
        assertEquals(listOf(listOf("p1")), store.s.grid)
        assertEquals("p1", store.s.focusedPanelId)

        val single = WorkspaceStore(Storage("app" to layout(panel("p1", tab("editor:a.kt")), grid = """[["p1"]]""", focused = "p1")), isMobile = { false }, windows = windows)
        single.switchProject("app")
        single.popOutTab("editor:a.kt", "p1")
        assertEquals(listOf(listOf("p1")), single.state.value.grid)
        assertTrue(single.state.value.panels.getValue("p1").tabs.isEmpty())
    }

    @Test
    fun `nothing is detached on a phone, for a tab with a window of its own, or with every slot taken`() {
        val storage = Storage("app" to layout(panel("p1", tab("editor:a.kt"), tab("settings", project = "")), grid = """[["p1"]]""", focused = "p1"))
        val store = store(storage)
        val before = store.s
        mobile = true
        assertNull(store.popOutTab("editor:a.kt", "p1"))
        mobile = false
        assertNull(store.popOutTab("settings", "p1"))
        assertNull(store.popOutTab("editor:missing", "p1"))
        repeat(MAX_WINDOWS) { windows.open(WindowKind.Logs) }
        assertNull(store.popOutTab("editor:a.kt", "p1"))
        assertEquals(before, store.s)
        assertEquals(MAX_WINDOWS, openWindows.size)
    }

    @Test
    fun `closing the window hands its tab back where it came from`() {
        val store = store(Storage("app" to twoPanels()))
        val toOrigin = store.popOutTab("editor:a.kt", "p1")!!
        store.closeWindow(toOrigin)
        assertEquals(listOf("editor:b.kt", "editor:a.kt"), store.s.panels.getValue("p1").tabs.map { it.id })
        assertFalse(store.s.panels.keys.any(::isWindowPanelId))
        assertTrue(openWindows.isEmpty())

        // Its panel gone, the tab goes to the focused grid panel.
        val orphan = store.popOutTab("terminal:1", "p2")!!
        assertEquals(listOf(listOf("p1")), store.s.grid)
        store.closeWindow(orphan)
        assertEquals("terminal:1", store.s.panels.getValue("p1").activeTabId)

        // A terminal parked in the dock goes back to the dock.
        val fromDock = store.popOutTab("terminal:2", DOCK_PANEL_ID)!!
        store.closeWindow(fromDock)
        assertEquals(listOf("terminal:2"), store.s.panels.getValue(DOCK_PANEL_ID).tabs.map { it.id })
        assertEquals("p1", store.s.focusedPanelId)
    }

    @Test
    fun `a window that is its tab's home closes the tab with it`() {
        val store = store(Storage("app" to twoPanels()))
        val windowId = store.popOutTab("editor:a.kt", "p1", closeTabsOnClose = true)!!
        assertEquals(JsonPrimitive(true), openWindows.getValue(windowId).payload!!["closeTabsOnClose"])
        store.closeWindow(windowId)
        assertNull(store.s.panelOf("editor:a.kt"))
        assertTrue(openWindows.isEmpty())
    }

    @Test
    fun `a window emptied by a close or a move goes with its panel`() {
        val store = store(Storage("app" to twoPanels()))
        val closed = store.popOutTab("editor:a.kt", "p1")!!
        store.closeTab("editor:a.kt")
        assertFalse(closed in openWindows)
        assertFalse(windowPanelId(closed) in store.s.panels)

        val moved = store.popOutTab("editor:b.kt", "p1")!!
        store.moveTab("editor:b.kt", windowPanelId(moved), "p2")
        assertFalse(moved in openWindows)
        assertFalse(windowPanelId(moved) in store.s.panels)
        assertEquals("p2", store.s.focusedPanelId)
    }

    @Test
    fun `reloading a project takes its tabs out of the windows, and leaves another project's alone`() {
        val storage = Storage("app" to twoPanels(), "api" to layout(panel("q1", tab("editor:main.go", project = "api")), grid = """[["q1"]]""", focused = "q1"))
        val store = store(storage)
        val mine = store.popOutTab("editor:a.kt", "p1")!!
        store.switchProject("api")
        val theirs = store.popOutTab("editor:main.go", "q1")!!
        store.reloadProject("app")
        assertFalse(mine in openWindows)
        assertFalse(windowPanelId(mine) in store.s.panels)
        assertTrue(theirs in openWindows)
        assertEquals(listOf("editor:main.go"), store.s.panels.getValue(windowPanelId(theirs)).tabs.map { it.id })
    }

    @Test
    fun `a tab already open in a window brings the window forward and leaves focus on the grid`() {
        val chat = tab("chat:mock/s1", metadata = """{"sessionId":"s1","providerId":"mock"}""")
        val store = store(Storage("app" to layout(panel("p1", chat, tab("editor:a.kt")), grid = """[["p1"]]""", focused = "p1")))
        val windowId = store.popOutTab("chat:mock/s1", "p1")!!
        windows.setState(windowId, WindowVisualState.Minimized)
        store.openTab(NewTab("chat", "Chat", "app", WireJson.parse("""{"sessionId":"s1","providerId":"mock"}""") as JsonObject))
        assertEquals("p1", store.s.focusedPanelId)
        assertEquals(WindowVisualState.Normal, openWindows.getValue(windowId).state)
        // Nor does activating it inside the window move focus there.
        store.setActiveTab("chat:mock/s1")
        assertEquals("p1", store.s.focusedPanelId)
        store.setFocusedPanel(windowPanelId(windowId))
        assertEquals("p1", store.s.focusedPanelId)
    }

    @Test
    fun `detached tabs come back after a restart, and the ones whose window did not come home`() {
        val storage = Storage("app" to twoPanels())
        val first = store(storage)
        val kept = first.popOutTab("editor:a.kt", "p1")!!
        val lost = first.popOutTab("terminal:1", "p2")!!
        // The windows were saved without the second one: say its blob was dropped.
        windows.close(lost)
        val ghost = windows.open(WindowKind.TabHost)
        val windowsText = values.map.getValue("windows")

        val restartedWindows = WindowStore(values, "windows")
        val second = WorkspaceStore(storage, isMobile = { mobile }, windows = restartedWindows)
        second.switchProject("app")
        assertTrue(windowPanelId(kept) in second.state.value.panels)
        assertTrue(windowPanelId(lost) in second.state.value.panels)

        restartedWindows.restoreAll(Bounds(1280.0, 800.0))
        assertEquals(windowsText, values.map["windows"])
        second.reconcileWindowPanels()
        val st = second.state.value
        assertTrue(windowPanelId(kept) in st.panels)
        assertFalse(windowPanelId(lost) in st.panels)
        assertEquals("terminal:1", st.panels.getValue(st.focusedPanelId).tabs.last().id)
        // A window with no panel behind it can never show anything: it closes.
        assertFalse(ghost in restartedWindows.state.value.windows)
        assertTrue(kept in restartedWindows.state.value.windows)

        // On a phone no window is alive: every detached tab comes home.
        mobile = true
        second.reconcileWindowPanels()
        assertFalse(second.state.value.panels.keys.any(::isWindowPanelId))
        assertNull(storage.windowPanels)
    }

    @Test
    fun `going narrow sends every detached tab home and keeps the other windows`() {
        val store = store(Storage("app" to twoPanels()))
        val detached = store.popOutTab("editor:a.kt", "p1")!!
        val settings = windows.open(WindowKind.Settings)
        store.redockAllWindows()
        assertFalse(detached in openWindows)
        assertTrue(settings in openWindows)
        assertEquals(listOf("editor:b.kt", "editor:a.kt"), store.s.panels.getValue("p1").tabs.map { it.id })
    }

    @Test
    fun `saved window panels bring back only what may live in a window`() {
        val text = """{
            ${panel("__win__:w1", tab("settings", project = ""), tab("editor:a.kt"), """{"id":"editor:untitled","type":"editor"}""")},
            ${panel("__win__:w2", tab("logs", project = ""))},
            ${panel("p1", tab("editor:b.kt"))},
            "__win__:w3":{"id":"__win__:w3","tabs":[${tab("terminal:1")},${tab("terminal:2")}],"activeTabId":"gone","tabHistory":["gone","terminal:1"]}
        }"""
        val loaded = loadWindowPanels(text)
        assertEquals(listOf("__win__:w1", "__win__:w3"), loaded.keys.toList())
        // Settings owns a window kind; a tab without a title is dropped too.
        assertEquals(listOf("editor:a.kt"), loaded.getValue("__win__:w1").tabs.map { it.id })
        assertEquals("editor:a.kt", loaded.getValue("__win__:w1").activeTabId)
        assertEquals("terminal:1", loaded.getValue("__win__:w3").activeTabId)
        assertEquals(listOf("terminal:1"), loaded.getValue("__win__:w3").tabHistory)
        assertEquals(emptyMap<String, WorkspacePanel>(), loadWindowPanels("[]"))
        assertNull(saveWindowPanels(mapOf("__win__:w9" to WorkspacePanel("__win__:w9"))))
        assertNotNull(saveWindowPanels(loaded))
    }
}
