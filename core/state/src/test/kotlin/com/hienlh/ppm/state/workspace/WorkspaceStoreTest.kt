package com.hienlh.ppm.state.workspace

import com.hienlh.ppm.protocol.ChatPreparationSettings
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.string
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The web's layout rules (panel-store.ts, panel-utils.ts, dock-actions.ts), case by case. */
class WorkspaceStoreTest {
    private class MemoryStorage(vararg initial: Pair<String, PanelLayout>) : LayoutStorage {
        val layouts = hashMapOf(*initial)
        val saves = mutableListOf<Pair<String, PanelLayout>>()

        override fun load(project: String) = layouts[project]

        override fun save(project: String, layout: PanelLayout) {
            layouts[project] = layout
            saves += project to layout
        }
    }

    private var ids = 0
    private var clock = 1_000L
    private var mobile = false

    private fun store(storage: LayoutStorage = MemoryStorage()) =
        WorkspaceStore(storage, isMobile = { mobile }, now = { clock }, randomId = { "r${++ids}" })

    private fun json(text: String) = WireJson.parse(text) as JsonObject

    private fun layout(text: String) = PanelLayout.fromJson(json(text))!!

    private fun meta(vararg pairs: Pair<String, Any>) = JsonObject(pairs.associate { (k, v) ->
        k to when (v) {
            is String -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            else -> error("unsupported")
        }
    })

    private fun editor(path: String, project: String = "app") =
        NewTab("editor", path, project, meta("filePath" to path))

    private val WorkspaceStore.s get() = state.value

    private fun WorkspaceStore.focused() = s.panels.getValue(s.focusedPanelId)

    // ---------------------------------------------------------------------------------------
    // Tab ids
    // ---------------------------------------------------------------------------------------

    @Test
    fun `forget active project does not save it when switching to another project`() {
        val storage = MemoryStorage()
        val store = store(storage)
        store.switchProject("old")
        store.openTab(editor("old.txt", "old"))
        storage.saves.clear()
        store.forgetProject("old")
        assertNull(store.s.currentProject)
        assertTrue(store.s.grid.isEmpty())
        assertTrue(store.s.panels.values.flatMap { it.tabs }.none { it.projectId == "old" })
        store.switchProject("next")
        assertEquals("next", store.s.currentProject)
        assertTrue(storage.saves.none { it.first == "old" })
    }

    @Test
    fun `forget inactive project preserves active panels and strips its keep alive grid`() {
        val store = store()
        store.switchProject("old")
        store.openTab(editor("old.txt", "old"))
        store.switchProject("kept")
        store.openTab(editor("kept.txt", "kept"))
        val grid = store.s.grid
        val focused = store.s.focusedPanelId
        store.forgetProject("old")
        assertEquals("kept", store.s.currentProject)
        assertEquals(grid, store.s.grid)
        assertEquals(focused, store.s.focusedPanelId)
        assertFalse(store.s.projectGrids.containsKey("old"))
        assertTrue(store.s.panels.values.flatMap { it.tabs }.none { it.projectId == "old" })
        assertTrue(store.s.panels.values.flatMap { it.tabs }.any { it.projectId == "kept" })
    }

    @Test
    fun `tab ids are derived as the web derives them`() {
        val random = { "abc" }
        assertEquals("editor:src/a.ts", deriveTabId("editor", meta("filePath" to "src/a.ts"), random))
        assertEquals("editor:untitled", deriveTabId("editor", meta(), random))
        assertEquals("editor:untitled-3", deriveTabId("editor", meta("isUntitled" to true, "untitledNumber" to 3), random))
        assertEquals("editor:viewer:k", deriveTabId("editor", meta("viewerKey" to "k", "filePath" to "x"), random))
        assertEquals("chat:codex/s1", deriveTabId("chat", meta("providerId" to "codex", "sessionId" to "s1"), random))
        assertEquals("chat:default/abc", deriveTabId("chat", meta(), random))
        // A number goes into the id as JavaScript prints it: 2, never 2.0.
        assertEquals("terminal:2", deriveTabId("terminal", meta("terminalIndex" to 2.0), random))
        assertEquals("terminal:1", deriveTabId("terminal", meta(), random))
        assertEquals("extension:git-graph", deriveTabId("extension", meta("viewType" to "git-graph.view"), random))
        assertEquals("settings", deriveTabId("settings", meta(), random))
        assertEquals("design:landing", deriveTabId("design", meta("designSlug" to "landing"), random))
        assertEquals("db-connection:new-abc", deriveTabId("db-connection", meta(), random))
        assertEquals("jira:abc", deriveTabId("jira", meta(), random))
    }

    // ---------------------------------------------------------------------------------------
    // The shared blob
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a layout written back keeps every tab, key and field the app does not know`() {
        val blob = json(
            """{"panels":{"p1":{"id":"p1","tabs":[{"id":"jira:1","type":"jira","title":"J","projectId":"app",
               "metadata":{"k":[1,2],"n":{"a":null}},"closable":true,"pinned":true}],"activeTabId":"jira:1",
               "tabHistory":["jira:1"],"size":40}},"grid":[["p1"]],"focusedPanelId":"p1",
               "dock":{"visible":true,"height":42.5},"dockPanel":{"id":"__dock__","tabs":[],"activeTabId":null,"tabHistory":[]},
               "future":{"a":1},"updatedAt":"2026-10-20T00:00:00.000Z"}""",
        )
        val saved = SavedLayout.fromJson(blob)!!
        assertEquals("2026-10-20T00:00:00.000Z", saved.updatedAt)
        assertEquals(blob, saved.toJson())
        // The server's copy is the layout alone.
        assertEquals(JsonObject(blob - "updatedAt"), saved.layout.toJson())
    }

    @Test
    fun `a whole dock height goes out as a whole number`() {
        assertEquals("""{"visible":false,"height":30}""", DEFAULT_DOCK.toJson().toString())
    }

    @Test
    fun `loading drops old tunnel tabs, re-keys random ids and gives an old blob a dock`() {
        val loaded = migrateLoadedLayout(layout(
            """{"panels":{"p1":{"id":"p1","tabs":[
                 {"id":"tab-9","type":"editor","title":"a","metadata":{"filePath":"a.ts"},"closable":true},
                 {"id":"ports","type":"ports","title":"Ports","metadata":{},"closable":true}],
               "activeTabId":"ports","tabHistory":["tab-9","ports"]}},"grid":[["p1"]],"focusedPanelId":"p1"}""",
        ))
        val panel = loaded.panels.getValue("p1")
        assertEquals(listOf("editor:a.ts"), panel.tabs.map { it.id })
        assertEquals("editor:a.ts", panel.activeTabId)
        assertEquals(listOf("editor:a.ts"), panel.tabHistory)
        assertEquals(DEFAULT_DOCK, loaded.dock)
        assertEquals(DOCK_PANEL_ID, loaded.dockPanel?.id)
    }

    @Test
    fun `a dock panel keeps only what may sit in the dock`() {
        val loaded = migrateLoadedLayout(layout(
            """{"panels":{},"grid":[[]],"focusedPanelId":"","dock":{"visible":true,"height":50},
               "dockPanel":{"id":"__dock__","tabs":[
                 {"id":"terminal:1","type":"terminal","title":"T","metadata":{},"closable":true},
                 {"id":"editor:x","type":"editor","title":"x","metadata":{},"closable":true}],
               "activeTabId":"editor:x","tabHistory":["terminal:1","editor:x"]}}""",
        ))
        assertEquals(listOf("terminal:1"), loaded.dockPanel!!.tabs.map { it.id })
        assertEquals("terminal:1", loaded.dockPanel!!.activeTabId)
    }

    @Test
    fun `the newer copy wins, a tie goes to the server, and a time that does not parse loses`() {
        val base = layout("""{"panels":{},"grid":[],"focusedPanelId":""}""")
        val early = SavedLayout(base, "2026-10-20T01:00:00.000Z")
        val late = SavedLayout(base, "2026-10-20T02:00:00.000Z")
        val tie = SavedLayout(base, "2026-10-20T01:00:00.000Z")
        val broken = SavedLayout(base, "yesterday")
        assertTrue(resolveWorkspaceConflict(early, late) === late)
        assertTrue(resolveWorkspaceConflict(late, early) === late)
        assertTrue(resolveWorkspaceConflict(early, tie) === tie)
        assertTrue(resolveWorkspaceConflict(early, broken) === early)
        // NaN on either side makes `>=` false, so the local copy stays.
        assertTrue(resolveWorkspaceConflict(broken, late) === broken)
        assertTrue(resolveWorkspaceConflict(null, late) === late)
    }

    // ---------------------------------------------------------------------------------------
    // Switching projects
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a restored layout loses other projects' tabs, obsolete tabs and the panels they emptied`() {
        val storage = MemoryStorage("app" to layout(
            """{"panels":{
                 "p1":{"id":"p1","tabs":[
                   {"id":"editor:a.ts","type":"editor","title":"a","projectId":"app","metadata":{},"closable":true},
                   {"id":"editor:b.ts","type":"editor","title":"b","projectId":"other","metadata":{},"closable":true}],
                   "activeTabId":"editor:b.ts","tabHistory":["editor:a.ts","editor:b.ts"]},
                 "p2":{"id":"p2","tabs":[{"id":"git-status","type":"git-status","title":"Git","metadata":{},"closable":true}],
                   "activeTabId":"git-status","tabHistory":["git-status"]}},
               "grid":[["p1","p2"]],"focusedPanelId":"p2"}""",
        ))
        val store = store(storage)
        store.switchProject("app")

        assertEquals(listOf(listOf("p1")), store.s.grid)
        assertEquals("p1", store.s.focusedPanelId)
        assertEquals(listOf("editor:a.ts"), store.focused().tabs.map { it.id })
        assertEquals("editor:a.ts", store.focused().activeTabId)
        assertNull(store.s.panels["p2"])
        // The repair is written back.
        val (project, saved) = storage.saves.last()
        assertEquals("app", project)
        assertEquals(setOf("p1"), saved.panels.keys)
    }

    @Test
    fun `the dock keeps every project's tabs and shows this project's`() {
        val storage = MemoryStorage(
            "one" to layout(
                """{"panels":{"a":{"id":"a","tabs":[],"activeTabId":null,"tabHistory":[]}},"grid":[["a"]],"focusedPanelId":"a",
                   "dockPanel":{"id":"__dock__","tabs":[{"id":"terminal:1","type":"terminal","title":"T","projectId":"one","metadata":{},"closable":true}],
                   "activeTabId":"terminal:1","tabHistory":["terminal:1"]}}""",
            ),
            "two" to layout(
                """{"panels":{"b":{"id":"b","tabs":[],"activeTabId":null,"tabHistory":[]}},"grid":[["b"]],"focusedPanelId":"b",
                   "dockPanel":{"id":"__dock__","tabs":[{"id":"terminal:2","type":"terminal","title":"T","projectId":"two","metadata":{},"closable":true}],
                   "activeTabId":"terminal:2","tabHistory":["terminal:2"]}}""",
            ),
        )
        val store = store(storage)
        store.switchProject("one")
        assertEquals("terminal:1", store.s.dockPanel!!.activeTabId)
        store.switchProject("two")
        assertEquals(listOf("terminal:1", "terminal:2"), store.s.dockPanel!!.tabs.map { it.id })
        assertEquals("terminal:2", store.s.dockPanel!!.activeTabId)
        // Back to a project already in memory: its grid comes back, and the dock points at its tab again.
        store.switchProject("one")
        assertEquals(listOf(listOf("a")), store.s.grid)
        assertEquals("terminal:1", store.s.dockPanel!!.activeTabId)
    }

    // A tab that names no project (what Send to Chat opens: `projectId: null`, the project in its
    // metadata) is not another project's. tab-pool-collect.ts skips a tab only when it has a
    // projectId that differs from the project's.

    @Test
    fun `a tab that names no project is visible under every project, and only one that names another is hidden`() {
        val loose = WorkspaceTab("chat:x", "chat", "Chat", null, meta("projectName" to "one"))
        val own = WorkspaceTab("editor:a", "editor", "a", "one")
        val foreign = WorkspaceTab("editor:b", "editor", "b", "two")
        // `tab.projectId &&` is falsy for "" as well.
        val empty = WorkspaceTab("chat:y", "chat", "Chat", "")
        val tabs = listOf(loose, own, foreign, empty)
        assertEquals(listOf("chat:x", "editor:a", "chat:y"), visibleTabs(tabs, "one").map { it.id })
        assertEquals(listOf("chat:x", "editor:b", "chat:y"), visibleTabs(tabs, "two").map { it.id })
        assertEquals(listOf("chat:x", "chat:y"), visibleTabs(tabs, "three").map { it.id })
        assertEquals(tabs, visibleTabs(tabs, null))
    }

    @Test
    fun `a chat that names no project is still there after switching away and back, and after a restore`() {
        val storage = MemoryStorage()
        val store = store(storage)
        store.switchProject("one")
        val id = store.openTab(NewTab("chat", "Chat", null, meta("projectName" to "one", "pendingMessage" to "hi")))
        store.switchProject("two")
        store.switchProject("one")
        assertEquals(listOf(id), store.focused().tabs.map { it.id })

        // A fresh start reads the saved layout back and drops other projects' tabs, not this one.
        val restored = store(storage)
        restored.switchProject("one")
        val tab = restored.focused().tab(id)!!
        assertNull(tab.projectId)
        assertEquals("hi", tab.metaString("pendingMessage"))
    }

    @Test
    fun `the dock offers a tab that names no project under every project, and one that names a project under its own`() {
        val storage = MemoryStorage(
            "one" to layout(
                """{"panels":{"a":{"id":"a","tabs":[],"activeTabId":null,"tabHistory":[]}},"grid":[["a"]],"focusedPanelId":"a",
                   "dockPanel":{"id":"__dock__","tabs":[
                     {"id":"terminal:1","type":"terminal","title":"T","projectId":null,"metadata":{},"closable":true},
                     {"id":"terminal:2","type":"terminal","title":"T","projectId":"one","metadata":{"projectName":"one"},"closable":true}],
                   "activeTabId":"terminal:2","tabHistory":["terminal:1","terminal:2"]}}""",
            ),
            "two" to layout(
                """{"panels":{"b":{"id":"b","tabs":[],"activeTabId":null,"tabHistory":[]}},"grid":[["b"]],"focusedPanelId":"b"}""",
            ),
        )
        val store = store(storage)
        store.switchProject("one")
        assertEquals("terminal:2", store.s.dockPanel!!.activeTabId)
        assertEquals(2, store.activeProjectDockTabCount())

        store.switchProject("two")
        assertEquals(listOf("terminal:1"), visibleTabs(store.s.dockPanel!!.tabs, "two").map { it.id })
        assertEquals("terminal:1", store.s.dockPanel!!.activeTabId)
        assertEquals(1, store.activeProjectDockTabCount())

        store.switchProject("one")
        assertEquals("terminal:2", store.s.dockPanel!!.activeTabId)
        assertEquals(2, store.activeProjectDockTabCount())
    }

    @Test
    fun `a phone restores a visible dock collapsed`() {
        mobile = true
        val storage = MemoryStorage("app" to layout(
            """{"panels":{"a":{"id":"a","tabs":[],"activeTabId":null,"tabHistory":[]}},"grid":[["a"]],"focusedPanelId":"a",
               "dock":{"visible":true,"height":40}}""",
        ))
        val store = store(storage)
        store.switchProject("app")
        assertEquals(DockState(visible = false, height = 40.0), store.s.dock)
    }

    @Test
    fun `a project with no layout gets one empty panel, saved at once`() {
        val storage = MemoryStorage()
        val store = store(storage)
        store.switchProject("app")
        assertEquals(1, store.s.gridPanelIds.size)
        assertTrue(store.focused().tabs.isEmpty())
        assertEquals("app", storage.saves.single().first)
    }

    @Test
    fun `reloading a project reads its layout again`() {
        val storage = MemoryStorage()
        val store = store(storage)
        store.switchProject("app")
        store.openTab(editor("a.ts"))
        storage.layouts["app"] = layout(
            """{"panels":{"x":{"id":"x","tabs":[{"id":"editor:z.ts","type":"editor","title":"z","projectId":"app","metadata":{},"closable":true}],
               "activeTabId":"editor:z.ts","tabHistory":["editor:z.ts"]}},"grid":[["x"]],"focusedPanelId":"x"}""",
        )
        store.reloadProject("app")
        assertEquals(listOf(listOf("x")), store.s.grid)
        assertEquals("editor:z.ts", store.focused().activeTabId)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a reload never publishes the project without the tabs that come back`() = runTest {
        val chat = """{"id":"chat:mock/s1","type":"chat","title":"Chat","projectId":"app","metadata":{"sessionId":"s1","providerId":"mock"},"closable":true}"""
        fun withTabs(tabs: String, active: String) = layout(
            """{"panels":{"p1":{"id":"p1","tabs":[$tabs],"activeTabId":"$active","tabHistory":["$active"]}},"grid":[["p1"]],"focusedPanelId":"p1"}""",
        )
        val storage = MemoryStorage("app" to withTabs(chat, "chat:mock/s1"))
        val store = store(storage)
        store.switchProject("app")
        // Another device opened a file beside the chat.
        storage.layouts["app"] = withTabs(
            """$chat,{"id":"editor:z.ts","type":"editor","title":"z","projectId":"app","metadata":{},"closable":true}""",
            "editor:z.ts",
        )
        // Collected as the app collects it: each state as it is set.
        val seen = mutableListOf<WorkspaceState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.state.collect { seen += it } }
        seen.clear()
        store.reloadProject("app")
        assertTrue(seen.isNotEmpty())
        // A chat is ended when its tab leaves, so a tab gone for one state would start over.
        for (state in seen) assertTrue(state.panels.values.any { panel -> panel.tabs.any { it.id == "chat:mock/s1" } })
        assertEquals(listOf("chat:mock/s1", "editor:z.ts"), store.focused().tabs.map { it.id })
    }

    // ---------------------------------------------------------------------------------------
    // Opening and closing tabs
    // ---------------------------------------------------------------------------------------

    @Test
    fun `opening the same file twice in a panel finds it, and in another panel suffixes the panel`() {
        val store = store()
        store.switchProject("app")
        val first = store.s.focusedPanelId
        assertEquals("editor:a.ts", store.openTab(editor("a.ts")))
        assertEquals("editor:a.ts", store.openTab(editor("a.ts")))
        assertEquals(1, store.focused().tabs.size)
        assertEquals(JsonPrimitive(clock), store.focused().activeTab!!.metadata["lastActiveAt"])

        store.openTab(editor("b.ts"))
        assertTrue(store.splitPanel(SplitDirection.Right, "editor:b.ts", first))
        val second = store.s.focusedPanelId
        assertNotEquals(first, second)
        assertEquals("editor:a.ts@$second", store.openTab(editor("a.ts")))
    }

    @Test
    fun `a chat is found by its session, even under the id it was minted with`() {
        val store = store()
        store.switchProject("app")
        val minted = store.openTab(NewTab("chat", "New Chat", "app", meta("providerId" to "claude")))
        assertEquals("chat:claude/r3", minted)
        store.updateTab(minted) { it.withMetadata("sessionId" to JsonPrimitive("s1")) }
        store.openTab(editor("a.ts"))
        assertEquals(minted, store.openTab(NewTab("chat", "Chat", "app", meta("providerId" to "claude", "sessionId" to "s1"))))
        assertEquals(minted, store.focused().activeTabId)
    }

    @Test
    fun `a singleton is focused wherever it is`() {
        val store = store()
        store.switchProject("app")
        val first = store.s.focusedPanelId
        store.openTab(NewTab("settings", "Settings"))
        store.openTab(editor("a.ts"))
        store.splitPanel(SplitDirection.Down, "editor:a.ts", first)
        assertNotEquals(first, store.s.focusedPanelId)
        assertEquals("settings", store.openTab(NewTab("settings", "Settings")))
        assertEquals(first, store.s.focusedPanelId)
    }

    @Test
    fun `a singleton is one per project, not one among every project in memory`() {
        val store = store()
        store.switchProject("one")
        store.openTab(NewTab("settings", "Settings"))
        store.switchProject("two")
        val panel = store.s.focusedPanelId
        val id = store.openTab(NewTab("settings", "Settings"))
        assertEquals("settings@$panel", id)
        assertEquals(panel, store.s.focusedPanelId)
        assertEquals(id, store.s.activeGridTab?.id)
        // Found again, suffix and all.
        store.openTab(editor("a.ts"))
        assertEquals(id, store.openTab(NewTab("settings", "Settings")))
    }

    @Test
    fun `terminals are numbered past the highest open one, the dock's included`() {
        val store = store()
        store.switchProject("app")
        assertEquals("terminal:1", store.openTab(NewTab("terminal", "Terminal", "app")))
        assertEquals("terminal:2", store.openInDock(NewTab("terminal", "Terminal", "app")))
        assertEquals("terminal:3", store.openTab(NewTab("terminal", "Terminal", "app")))
    }

    @Test
    fun `a phone opens everything in the first panel and finds a tab in any of them`() {
        mobile = true
        val storage = MemoryStorage("app" to layout(
            """{"panels":{
                 "p1":{"id":"p1","tabs":[{"id":"editor:x.ts","type":"editor","title":"x","projectId":"app","metadata":{"filePath":"x.ts"},"closable":true}],
                   "activeTabId":"editor:x.ts","tabHistory":["editor:x.ts"]},
                 "p2":{"id":"p2","tabs":[{"id":"editor:a.ts@p2","type":"editor","title":"a","projectId":"app","metadata":{"filePath":"a.ts"},"closable":true},
                   {"id":"editor:c.ts","type":"editor","title":"c","projectId":"app","metadata":{"filePath":"c.ts"},"closable":true}],
                   "activeTabId":"editor:c.ts","tabHistory":["editor:c.ts"]}},
               "grid":[["p1"],["p2"]],"focusedPanelId":"p2"}""",
        ))
        val store = store(storage)
        store.switchProject("app")
        assertEquals("editor:a.ts@p2", store.openTab(editor("a.ts")))
        assertEquals("p2", store.s.focusedPanelId)
        assertEquals("editor:b.ts", store.openTab(editor("b.ts")))
        assertEquals("p1", store.s.focusedPanelId)
        // One column on a phone.
        assertFalse(store.splitPanel(SplitDirection.Right, "editor:b.ts", "p1"))
    }

    @Test
    fun `the phone's tab bar is every grid panel's tabs in grid order, this project's only`() {
        val storage = MemoryStorage("app" to layout(
            """{"panels":{
                 "p1":{"id":"p1","tabs":[{"id":"editor:a","type":"editor","title":"a","projectId":"app","metadata":{},"closable":true}],"activeTabId":"editor:a","tabHistory":[]},
                 "p2":{"id":"p2","tabs":[{"id":"editor:b","type":"editor","title":"b","metadata":{},"closable":true},
                   {"id":"editor:c","type":"editor","title":"c","projectId":"app","metadata":{},"closable":true}],"activeTabId":"editor:b","tabHistory":[]}},
               "grid":[["p2"],["p1"]],"focusedPanelId":"p1"}""",
        ))
        val store = store(storage)
        store.switchProject("app")
        assertEquals(listOf("editor:b" to "p2", "editor:c" to "p2", "editor:a" to "p1"), store.s.mergedTabs.map { (tab, pid) -> tab.id to pid })
    }

    @Test
    fun `closing the active tab goes back through its history`() {
        val store = store()
        store.switchProject("app")
        store.openTab(editor("a.ts"))
        store.openTab(editor("b.ts"))
        store.openTab(editor("c.ts"))
        store.setActiveTab("editor:a.ts")
        store.closeTab("editor:a.ts")
        assertEquals("editor:c.ts", store.focused().activeTabId)
    }

    @Test
    fun `an emptied panel goes, the grid's last one stays`() {
        val store = store()
        store.switchProject("app")
        val first = store.s.focusedPanelId
        store.openTab(editor("a.ts"))
        store.openTab(editor("b.ts"))
        store.splitPanel(SplitDirection.Right, "editor:b.ts", first)
        val second = store.s.focusedPanelId
        store.closeTab("editor:b.ts")
        assertEquals(listOf(listOf(first)), store.s.grid)
        assertEquals(first, store.s.focusedPanelId)
        assertNull(store.s.panels[second])
        store.closeTab("editor:a.ts")
        assertEquals(listOf(listOf(first)), store.s.grid)
        assertTrue(store.focused().tabs.isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // Splits and panels
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a row holds three panels and the grid three rows`() {
        val store = store()
        store.switchProject("app")
        val first = store.s.focusedPanelId
        for (name in listOf("a", "b", "c", "d")) store.openTab(editor(name), first)
        assertTrue(store.splitPanel(SplitDirection.Right, "editor:b", first))
        assertTrue(store.splitPanel(SplitDirection.Left, "editor:c", first))
        assertFalse(store.splitPanel(SplitDirection.Right, "editor:d", first))
        assertEquals(3, store.s.grid.single().size)
        // Left of the first panel: the new one takes its place.
        assertEquals(first, store.s.grid.single()[1])
        assertTrue(store.splitPanel(SplitDirection.Down, "editor:d", first))
        store.openTab(editor("e"), first)
        assertTrue(store.splitPanel(SplitDirection.Up, "editor:e", first))
        store.openTab(editor("f"), first)
        assertFalse(store.splitPanel(SplitDirection.Down, "editor:f", first))
        assertEquals(3, store.s.grid.size)
    }

    @Test
    fun `closing a panel hands its tabs to the one before it`() {
        val store = store()
        store.switchProject("app")
        val first = store.s.focusedPanelId
        store.openTab(editor("a.ts"))
        store.openTab(editor("b.ts"))
        store.splitPanel(SplitDirection.Right, "editor:b.ts", first)
        store.closePanel(store.s.focusedPanelId)
        assertEquals(listOf(listOf(first)), store.s.grid)
        assertEquals(listOf("editor:a.ts", "editor:b.ts"), store.focused().tabs.map { it.id })
        assertEquals("editor:a.ts", store.focused().activeTabId)
    }

    // ---------------------------------------------------------------------------------------
    // Dock
    // ---------------------------------------------------------------------------------------

    @Test
    fun `an empty dock opens with a terminal and hides with its last tab`() {
        val store = store()
        store.switchProject("app")
        store.toggleDock()
        assertTrue(store.s.dock.visible)
        val terminal = store.s.dockPanel!!.tabs.single()
        assertEquals("terminal:1", terminal.id)
        assertEquals("app", terminal.projectId)
        assertEquals("app", terminal.metadata["projectName"].string)
        store.closeTab(terminal.id)
        assertFalse(store.s.dock.visible)
        assertTrue(store.s.panels.containsKey(DOCK_PANEL_ID))
    }

    @Test
    fun `the dock's height stays between 15 and 85 percent`() {
        val store = store()
        store.switchProject("app")
        store.setDockHeight(5.0)
        assertEquals(15.0, store.s.dock.height, 0.0)
        store.setDockHeight(90.0)
        assertEquals(85.0, store.s.dock.height, 0.0)
    }

    @Test
    fun `a terminal moved to the dock keeps its id and shows the dock`() {
        val store = store()
        store.switchProject("app")
        val first = store.s.focusedPanelId
        store.openTab(editor("a.ts"))
        val id = store.openTab(NewTab("terminal", "Terminal", "app"))
        store.redockTab(id, first)
        assertTrue(store.s.dock.visible)
        assertEquals(listOf(id), store.s.dockPanel!!.tabs.map { it.id })
        assertEquals(listOf("editor:a.ts"), store.s.panels.getValue(first).tabs.map { it.id })
    }

    @Test
    fun `picking a dock tab leaves focus on the grid, where the next tab opens`() {
        val store = store()
        store.switchProject("app")
        val first = store.s.focusedPanelId
        val id = store.openTab(NewTab("terminal", "Terminal", "app"))
        store.redockTab(id, first)
        store.setActiveTab(id, DOCK_PANEL_ID)
        assertEquals(first, store.s.focusedPanelId)
        store.openTab(editor("b.ts"))
        assertEquals(listOf("editor:b.ts"), store.s.panels.getValue(first).tabs.map { it.id })
        assertEquals(listOf(id), store.s.dockPanel!!.tabs.map { it.id })
    }

    // ---------------------------------------------------------------------------------------
    // The focused chat's provider
    // ---------------------------------------------------------------------------------------

    @Test
    fun `focusing a chat remembers its provider for that project`() {
        val store = store()
        store.switchProject("app")
        store.openTab(NewTab("chat", "Chat", "app", meta("providerId" to "codex", "sessionId" to "s1")))
        assertEquals(mapOf("app" to "codex"), store.s.lastFocusedChatProviders)
        // Placeholders say nothing about a choice.
        store.openTab(NewTab("chat", "Chat", "app", meta("providerId" to "default", "sessionId" to "s2")))
        store.openTab(NewTab("chat", "Chat", "app", meta("providerPending" to true, "sessionId" to "s3")))
        assertEquals(mapOf("app" to "codex"), store.s.lastFocusedChatProviders)
        store.openTab(NewTab("chat", "Chat", "app", meta("sessionId" to "s4")))
        assertEquals(mapOf("app" to "claude"), store.s.lastFocusedChatProviders)
    }

    // ---------------------------------------------------------------------------------------
    // A new chat's provider, before its first message
    // ---------------------------------------------------------------------------------------

    private val followFocus = ChatPreparationSettings("claude", "follow-focus", mapOf("codex" to "default"))

    /** A new chat tab opened with [metadata], as it stands once open. */
    private fun WorkspaceStore.openChat(vararg metadata: Pair<String, Any>): WorkspaceTab {
        val id = openTab(NewTab("chat", "AI Chat", "app", meta(*metadata)))
        return focused().tab(id)!!
    }

    @Test
    fun `a new chat opens on the provider the cached settings name, when the project offers it`() {
        var defaults: NewChatDefaults? = NewChatDefaults(followFocus, listOf("claude", "codex"))
        val store = WorkspaceStore(MemoryStorage(), isMobile = { mobile }, now = { clock }, randomId = { "r${++ids}" }, newChatDefaults = { defaults })
        store.switchProject("app")
        store.openTab(NewTab("chat", "Chat", "app", meta("providerId" to "codex", "sessionId" to "s1")))
        val warm = store.openChat("projectName" to "app")
        assertTrue(warm.id, warm.id.startsWith("chat:codex/"))
        assertEquals("codex", warm.metaString("providerId"))
        assertEquals("default", warm.metaString("permissionMode"))
        assertEquals("cache", warm.metaString("permissionModeSource"))
        assertNull(warm.metadata["providerPending"])
        // Nothing cached, or a provider the project does not offer: pending, with the focus it opened on.
        defaults = NewChatDefaults(followFocus, listOf("claude"))
        val pending = store.openChat("projectName" to "app")
        assertTrue(pending.id, pending.id.startsWith("chat:default/"))
        assertEquals(JsonPrimitive(true), pending.metadata["providerPending"])
        assertEquals("codex", pending.metaString("focusedProviderOnOpen"))
        assertNull(pending.metaString("providerId"))
        defaults = null
        assertEquals(JsonPrimitive(true), store.openChat("projectName" to "app").metadata["providerPending"])
    }

    @Test
    fun `a new chat on a named provider takes the cached mode, unless it was opened with one on purpose`() {
        val defaults = NewChatDefaults(ChatPreparationSettings("claude", null, mapOf("mock" to "plan")), null)
        val store = WorkspaceStore(MemoryStorage(), isMobile = { mobile }, now = { clock }, randomId = { "r${++ids}" }, newChatDefaults = { defaults })
        store.switchProject("app")
        assertEquals("plan", store.openChat("providerId" to "mock", "permissionMode" to "default", "permissionModeSource" to "cache").metaString("permissionMode"))
        assertEquals("default", store.openChat("providerId" to "mock", "permissionMode" to "default").metaString("permissionMode"))
        assertNull(store.openChat("providerId" to "codex").metaString("permissionMode"))
    }
}
