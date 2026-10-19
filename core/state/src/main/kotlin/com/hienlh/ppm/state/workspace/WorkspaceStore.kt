package com.hienlh.ppm.state.workspace

import com.hienlh.ppm.protocol.string
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Where a project's layout is read from and written to (this device's copy; see [WorkspaceCache]). */
interface LayoutStorage {
    /** The stored layout, already migrated; null when the project has none. */
    fun load(project: String): PanelLayout?

    fun save(project: String, layout: PanelLayout)
}

/** Everything the workspace shows, as one immutable snapshot. */
data class WorkspaceState(
    /** Every panel in memory: the open project's grid, the keep-alive grids of projects seen earlier, the dock. */
    val panels: Map<String, WorkspacePanel>,
    val grid: List<List<String>>,
    val focusedPanelId: String,
    val currentProject: String? = null,
    val dock: DockState = DEFAULT_DOCK,
    /** Dock maximized; for this run only, never saved. */
    val dockExpanded: Boolean = false,
    val projectGrids: Map<String, List<List<String>>> = emptyMap(),
    val projectFocused: Map<String, String> = emptyMap(),
    val projectDock: Map<String, DockState> = emptyMap(),
    /** The provider of the chat last focused in each project, for "follow focus" new chats. */
    val lastFocusedChatProviders: Map<String, String> = emptyMap(),
) {
    val gridPanelIds: List<String> get() = grid.flatten()

    val dockPanel: WorkspacePanel? get() = panels[DOCK_PANEL_ID]

    /**
     * The phone's one tab bar: every grid panel's tabs in grid order, without another project's
     * (`mobile-nav.tsx`). Each comes with the panel it lives in.
     */
    val mergedTabs: List<Pair<WorkspaceTab, String>>
        get() = gridPanelIds.flatMap { pid ->
            panels[pid]?.tabs.orEmpty()
                .filter { it.projectId.isNullOrEmpty() || currentProject == null || it.projectId == currentProject }
                .map { it to pid }
        }

    /** What the main area shows: the focused grid panel's tab, or the first grid panel's when focus is elsewhere. */
    val activeGridTab: WorkspaceTab?
        get() {
            val pid = focusedPanelId.takeIf { it in gridPanelIds } ?: gridPanelIds.firstOrNull() ?: return null
            return panels[pid]?.activeTab
        }

    fun panelOf(tabId: String): WorkspacePanel? = panels.values.firstOrNull { panel -> panel.tabs.any { it.id == tabId } }
}

enum class SplitDirection { Left, Right, Up, Down }

/**
 * The tabs, panels and dock of the open project: a port of `usePanelStore`
 * (src/web/stores/panel-store.ts, with panel-utils.ts and dock-actions.ts). Floating windows come
 * with phase 02e; what opening a chat resolves from the web's local cache is up to the caller.
 *
 * Like the other controllers it is driven from one thread; [state] is what other threads read.
 */
class WorkspaceStore(
    private val storage: LayoutStorage,
    /** The window is narrower than 768 dp: one column, and every tab opens in the first panel. */
    private val isMobile: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val randomId: () -> String = ::webRandomId,
) {
    private val _state = MutableStateFlow(defaultState())
    val state: StateFlow<WorkspaceState> = _state

    private val s: WorkspaceState get() = _state.value

    private fun newPanelId() = "panel-${randomId()}"

    private fun defaultState(): WorkspaceState {
        val panel = createPanel(newPanelId())
        // The dock panel is in the map but never in the grid.
        return WorkspaceState(
            panels = linkedMapOf(panel.id to panel, DOCK_PANEL_ID to createDockPanel()),
            grid = listOf(listOf(panel.id)),
            focusedPanelId = panel.id,
        )
    }

    // -----------------------------------------------------------------------------------------
    // Project lifecycle
    // -----------------------------------------------------------------------------------------

    fun switchProject(projectName: String) {
        val st = s
        if (st.currentProject == projectName) return

        val grids = st.projectGrids.toMutableMap()
        val focused = st.projectFocused.toMutableMap()
        val docks = st.projectDock.toMutableMap()
        st.currentProject?.let { current ->
            docks[current] = st.dock
            grids[current] = st.grid
            focused[current] = st.focusedPanelId
            storage.save(current, st.layoutForSave())
        }

        // Already in memory: restore the snapshot, pointing the shared dock at this project's tab.
        grids[projectName]?.let { restoredGrid ->
            val dockPanel = st.panels[DOCK_PANEL_ID]
            val panels = if (dockPanel == null) st.panels
            else st.panels + (DOCK_PANEL_ID to dockPanel.copy(activeTabId = pickDockActiveTab(dockPanel, projectName)))
            commit(st.copy(
                currentProject = projectName,
                panels = panels,
                grid = restoredGrid,
                focusedPanelId = focused[projectName] ?: restoredGrid.firstOrNull()?.firstOrNull().orEmpty(),
                projectGrids = grids,
                projectFocused = focused,
                dock = docks[projectName] ?: DEFAULT_DOCK,
                projectDock = docks,
            ))
            return
        }

        val loaded = storage.load(projectName)
        if (loaded != null && loaded.panels.isNotEmpty()) {
            // Drop obsolete tab types and other projects' tabs: a foreign tab is hidden from the tab
            // bar, so it can never be closed, and its panel would be stuck as a blank slot.
            var healed = false
            val restored = LinkedHashMap<String, WorkspacePanel>()
            for ((pid, panel) in loaded.panels) {
                val tabs = visibleTabs(panel.tabs, projectName).filter { it.type !in OBSOLETE_TAB_TYPES }
                if (tabs.size != panel.tabs.size) healed = true
                val history = panel.tabHistory.filter { id -> tabs.any { it.id == id } }
                val active = panel.activeTabId?.takeIf { id -> tabs.any { it.id == id } } ?: history.lastOrNull() ?: tabs.firstOrNull()?.id
                restored[pid] = panel.copy(tabs = tabs, tabHistory = history, activeTabId = active)
            }
            // No empty panel on restore either, but the last one always stays.
            var grid = loaded.grid
            for (pid in loaded.grid.flatten()) {
                if ((restored[pid]?.tabs?.size ?: 0) > 0) continue
                if (grid.flatten().size <= 1) break
                grid = gridRemovePanel(grid, pid)
                restored.remove(pid)
                healed = true
            }
            val focusedId = loaded.focusedPanelId.takeIf { it in grid.flatten() } ?: grid.flatten().firstOrNull() ?: loaded.focusedPanelId

            // The dock panel holds live tabs of every project: union this project's saved dock tabs
            // into it, so another project's terminal is never dropped.
            val existing = st.panels[DOCK_PANEL_ID] ?: createDockPanel()
            val incoming = loaded.dockPanel ?: createDockPanel()
            val dockTabs = existing.tabs.toMutableList()
            for (tab in incoming.tabs) if (dockTabs.none { it.id == tab.id }) dockTabs += tab
            val dockIds = dockTabs.map { it.id }.toSet()
            var dockPanel = existing.copy(
                tabs = dockTabs,
                tabHistory = (existing.tabHistory + incoming.tabHistory).distinct().filter { it in dockIds },
                activeTabId = null,
            )
            dockPanel = dockPanel.copy(activeTabId = pickDockActiveTab(dockPanel, projectName))

            grids[projectName] = grid
            focused[projectName] = focusedId
            val dock = (loaded.dock ?: docks[projectName] ?: DEFAULT_DOCK).let { if (isMobile() && it.visible) it.copy(visible = false) else it }
            commit(st.copy(
                currentProject = projectName,
                panels = LinkedHashMap(st.panels).apply { putAll(restored); put(DOCK_PANEL_ID, dockPanel) },
                grid = grid,
                focusedPanelId = focusedId,
                projectGrids = grids,
                projectFocused = focused,
                dock = dock,
                projectDock = docks,
            ))
            // Write the repair back, so it sticks and reaches the server's copy.
            if (healed) storage.save(projectName, PanelLayout(restored, grid, focusedId, dock, dockPanel))
        } else {
            val panel = createPanel(newPanelId())
            val grid = listOf(listOf(panel.id))
            grids[projectName] = grid
            focused[projectName] = panel.id
            val dock = docks[projectName] ?: DEFAULT_DOCK
            storage.save(projectName, PanelLayout(linkedMapOf(panel.id to panel), grid, panel.id, dock, st.panels[DOCK_PANEL_ID]))
            commit(st.copy(
                currentProject = projectName,
                panels = st.panels + (panel.id to panel),
                grid = grid,
                focusedPanelId = panel.id,
                projectGrids = grids,
                projectFocused = focused,
                dock = dock,
                projectDock = docks,
            ))
        }
    }

    /** Forgets what is in memory for [projectName] and loads it again, e.g. after its copy was replaced by the server's. */
    fun reloadProject(projectName: String) {
        val st = s
        val oldIds = st.projectGrids[projectName]?.flatten()?.toSet().orEmpty()
        // The dock panel is shared: only this project's tabs leave it.
        val panels = LinkedHashMap(st.panels)
        for (id in oldIds) if (id != DOCK_PANEL_ID) panels.remove(id)
        panels[DOCK_PANEL_ID]?.let { dock ->
            val remaining = dock.tabs.filter { it.projectId != projectName }
            val ids = remaining.map { it.id }.toSet()
            panels[DOCK_PANEL_ID] = dock.copy(
                tabs = remaining,
                tabHistory = dock.tabHistory.filter { it in ids },
                activeTabId = dock.activeTabId?.takeIf { it in ids } ?: remaining.lastOrNull()?.id,
            )
        }
        commit(st.copy(
            projectGrids = st.projectGrids - projectName,
            projectFocused = st.projectFocused - projectName,
            projectDock = st.projectDock - projectName,
            panels = panels,
            currentProject = null,
        ))
        switchProject(projectName)
    }

    // -----------------------------------------------------------------------------------------
    // Focus and tabs
    // -----------------------------------------------------------------------------------------

    /** Focus decides where the next tab opens, so it stays on a real, on-grid panel. */
    fun setFocusedPanel(panelId: String, rememberChat: Boolean = true) {
        if (isWindowPanelId(panelId)) return
        val panel = s.panels[panelId] ?: return
        commit(s.copy(focusedPanelId = panelId))
        if (rememberChat) rememberChatProvider(panel.activeTab)
    }

    /** Opens [def] in [panelId] (the focused panel by default), or focuses the tab already showing it; returns its id. */
    fun openTab(def: NewTab, panelId: String? = null): String {
        val mobile = isMobile()
        // A phone has one merged tab bar: everything opens in the first panel.
        val pid = (if (mobile) s.grid.firstOrNull()?.firstOrNull() else null) ?: panelId ?: s.focusedPanelId
        if (s.panels[pid] == null) return ""

        var tabDef = def
        if (tabDef.type == "terminal" && !jsTruthyKey(tabDef, "terminalIndex")) {
            tabDef = tabDef.copy(metadata = tabDef.metadata.merged(mapOf("terminalIndex" to JsonPrimitive(nextTerminalIndex()))))
        }
        val baseId = deriveTabId(tabDef.type, tabDef.metadata, randomId)

        // A chat already open anywhere is focused, matched by its session: a "New Chat" tab keeps
        // the id it was minted with after it gets a session. A design tab hosts its session too.
        if (tabDef.type == "chat") {
            tabDef.metadata["sessionId"].string?.takeIf { it.isNotEmpty() }?.let { sid ->
                for (panel in s.panels.values) {
                    val existing = panel.tabs.firstOrNull { tabSessionId(it) == sid } ?: continue
                    rememberChatProvider(existing)
                    activateIn(panel.id, existing.id, focus = focusAfterActivate(panel.id))
                    return existing.id
                }
            }
        }

        // One tab per design, matched by slug and project: other projects' keep-alive panels can share a slug.
        if (tabDef.type == "design") {
            val slug = tabDef.metadata["designSlug"]
            val project = tabDef.projectId ?: tabDef.metadata["projectName"].string
            for (panel in s.panels.values) {
                val existing = panel.tabs.firstOrNull {
                    it.type == "design" && it.metadata["designSlug"] == slug && (it.projectId ?: it.metadata["projectName"].string) == project
                } ?: continue
                activateIn(panel.id, existing.id, focus = focusAfterActivate(panel.id))
                return existing.id
            }
        }

        // On screen only: the grid and, off a phone, the floating windows. The web looks for a
        // singleton in every panel, other projects' keep-alive ones too, so on a phone Settings
        // opened in a second project focused the first one's hidden panel and showed nothing.
        // Searched here instead, a singleton is what the web says it is: one per project.
        if (tabDef.type in ONE_PER_WORKSPACE_TYPES || tabDef.type in SINGLETON_TYPES) {
            val windows = if (mobile) emptyList() else s.panels.keys.filter(::isWindowPanelId)
            for (opid in s.gridPanelIds + windows) {
                val panel = s.panels[opid] ?: continue
                val existing = panel.tabs.firstOrNull { it.id == baseId || it.id.startsWith("$baseId@") } ?: continue
                activateIn(panel.id, existing.id, focus = focusAfterActivate(panel.id))
                return existing.id
            }
        }

        // A phone's merged tab bar shows every grid panel's tabs, so it dedups across all of them.
        if (mobile) {
            for (gpid in s.gridPanelIds) {
                val panel = s.panels[gpid] ?: continue
                val existing = panel.tabs.firstOrNull { it.id == baseId || it.id.startsWith("$baseId@") } ?: continue
                activateIn(panel.id, existing.id, focus = panel.id)
                return existing.id
            }
        }

        val current = s.panels.getValue(pid)
        current.tab(baseId)?.let {
            activateIn(pid, it.id, focus = s.focusedPanelId)
            return it.id
        }

        // The same thing open in another panel (a split) gets this panel's id as a suffix.
        val elsewhere = s.panels.values.any { it.id != pid && it.tabs.any { t -> t.id == baseId } }
        val id = if (elsewhere) "$baseId@$pid" else baseId
        val tab = tabDef.withId(id).withMetadata("lastActiveAt" to JsonPrimitive(now()))
        val panel = s.panels.getValue(pid)
        commit(s.copy(
            focusedPanelId = pid,
            panels = s.panels + (pid to panel.copy(tabs = panel.tabs + tab, activeTabId = id, tabHistory = pushHistory(panel.tabHistory, id))),
        ))
        persist()
        return id
    }

    fun closeTab(tabId: String, panelId: String? = null) {
        val panel = (if (panelId != null) s.panels[panelId] else s.panelOf(tabId)) ?: return
        val pid = panel.id
        val st = s
        val tabs = panel.tabs.filter { it.id != tabId }
        val history = panel.tabHistory.filter { it != tabId }
        var active = panel.activeTabId
        if (panel.activeTabId == tabId) {
            val previous = history.lastOrNull()
            active = previous?.takeIf { id -> tabs.any { it.id == id } } ?: tabs.lastOrNull()?.id
        }
        // An emptied panel goes unless it is the grid's last. The dock never goes: it outlives its
        // tabs, only its visibility follows them. A window's panel always goes with its last tab.
        val removable = isWindowPanelId(pid) || (st.gridPanelIds.size > 1 && pid != DOCK_PANEL_ID)
        if (tabs.isEmpty() && removable) {
            val rest = st.panels - pid
            val grid = gridRemovePanel(st.grid, pid)
            // Focus lands on a panel still in the grid, not on another project's keep-alive panel.
            val focusedId = if (st.focusedPanelId == pid) grid.flatten().firstOrNull() ?: rest.keys.first() else st.focusedPanelId
            commit(st.copy(panels = rest, grid = grid, focusedPanelId = focusedId))
        } else {
            commit(st.copy(panels = st.panels + (pid to panel.copy(tabs = tabs, activeTabId = active, tabHistory = history))))
        }
        persist()
        // The dock has no empty state: its last tab closing hides it.
        if (pid == DOCK_PANEL_ID && activeProjectDockTabCount() == 0) setDockVisible(false)
    }

    fun setActiveTab(tabId: String, panelId: String? = null) {
        val panel = (if (panelId != null) s.panels[panelId] else s.panelOf(tabId)) ?: return
        rememberChatProvider(panel.tab(tabId))
        activateIn(panel.id, tabId, focus = focusAfterActivate(panel.id))
    }

    /** Replaces the tab [tabId] with what [change] makes of it (the web's `{ ...tab, ...updates }`). */
    fun updateTab(tabId: String, change: (WorkspaceTab) -> WorkspaceTab) {
        val panel = s.panelOf(tabId) ?: return
        commit(s.copy(panels = s.panels + (panel.id to panel.copy(tabs = panel.tabs.map { if (it.id == tabId) change(it).copy(id = tabId) else it }))))
        persist()
    }

    fun reorderTab(tabId: String, panelId: String, newIndex: Int) {
        val panel = s.panels[panelId] ?: return
        val oldIndex = panel.tabs.indexOfFirst { it.id == tabId }
        if (oldIndex == -1 || oldIndex == newIndex) return
        val tabs = panel.tabs.toMutableList()
        val moved = tabs.removeAt(oldIndex)
        tabs.add(newIndex.coerceIn(0, tabs.size), moved)
        commit(s.copy(panels = s.panels + (panelId to panel.copy(tabs = tabs))))
        persist()
    }

    fun moveTab(tabId: String, fromPanelId: String, toPanelId: String, insertIndex: Int? = null) {
        if (fromPanelId == toPanelId) return
        val from = s.panels[fromPanelId] ?: return
        val to = s.panels[toPanelId] ?: return
        val tab = from.tab(tabId) ?: return

        val fromTabs = from.tabs.filter { it.id != tabId }
        val fromHistory = from.tabHistory.filter { it != tabId }
        val fromActive = if (from.activeTabId == tabId) fromHistory.lastOrNull() ?: fromTabs.lastOrNull()?.id else from.activeTabId
        val toTabs = to.tabs.toMutableList().apply { if (insertIndex != null) add(insertIndex.coerceIn(0, size), tab) else add(tab) }
        val target = to.copy(tabs = toTabs, activeTabId = tabId, tabHistory = pushHistory(to.tabHistory, tabId))

        val st = s
        // The dock and a window's panel are never removed here: the dock outlives its tabs, and a
        // window's panel goes with its window.
        val offGridSource = fromPanelId == DOCK_PANEL_ID || isWindowPanelId(fromPanelId)
        if (fromTabs.isEmpty() && st.gridPanelIds.size > 1 && !offGridSource) {
            commit(st.copy(
                panels = (st.panels - fromPanelId) + (toPanelId to target),
                grid = gridRemovePanel(st.grid, fromPanelId),
                focusedPanelId = toPanelId,
            ))
        } else {
            commit(st.copy(
                focusedPanelId = toPanelId,
                panels = st.panels + (fromPanelId to from.copy(tabs = fromTabs, activeTabId = fromActive, tabHistory = fromHistory)) + (toPanelId to target),
            ))
        }
        persist()
    }

    /** Moves [tabId] into a new panel beside [targetPanelId] (or its own panel); false when the grid is full that way. */
    fun splitPanel(direction: SplitDirection, tabId: String, sourcePanelId: String, targetPanelId: String? = null): Boolean {
        val st = s
        val source = st.panels[sourcePanelId] ?: return false
        val tab = source.tab(tabId) ?: return false
        val pos = findPanelPosition(st.grid, targetPanelId ?: sourcePanelId) ?: return false

        val horizontal = direction == SplitDirection.Left || direction == SplitDirection.Right
        if (horizontal && (st.grid.getOrNull(pos.row)?.size ?: 0) >= maxColumns(isMobile())) return false
        if (!horizontal && st.grid.size >= MAX_ROWS) return false

        val newPanel = createPanel(newPanelId(), listOf(tab), tab.id)
        val srcTabs = source.tabs.filter { it.id != tabId }
        val srcHistory = source.tabHistory.filter { it != tabId }
        val srcActive = if (source.activeTabId == tabId) srcHistory.lastOrNull() ?: srcTabs.lastOrNull()?.id else source.activeTabId

        var grid: List<List<String>> = if (horizontal) {
            st.grid.mapIndexed { r, row ->
                if (r != pos.row) row
                else row.toMutableList().apply { add(if (direction == SplitDirection.Right) pos.col + 1 else pos.col, newPanel.id) }
            }
        } else {
            st.grid.toMutableList().apply { add(if (direction == SplitDirection.Down) pos.row + 1 else pos.row, listOf(newPanel.id)) }
        }

        var panels = st.panels + (newPanel.id to newPanel)
        if (srcTabs.isEmpty() && st.gridPanelIds.size > 1) {
            panels = panels - sourcePanelId
            grid = gridRemovePanel(grid, sourcePanelId)
        } else {
            panels = panels + (sourcePanelId to source.copy(tabs = srcTabs, activeTabId = srcActive, tabHistory = srcHistory))
        }
        commit(st.copy(panels = panels, grid = grid, focusedPanelId = newPanel.id))
        persist()
        return true
    }

    /** Closes a panel by merging its tabs into the one before it (or after, for the first). */
    fun closePanel(panelId: String) {
        val st = s
        if (st.gridPanelIds.size <= 1) return
        val panel = st.panels[panelId] ?: return
        val all = st.gridPanelIds
        val idx = all.indexOf(panelId)
        val neighborId = if (idx > 0) all[idx - 1] else all.getOrNull(1) ?: return
        val neighbor = st.panels[neighborId] ?: return
        val merged = neighbor.copy(
            tabs = neighbor.tabs + panel.tabs,
            activeTabId = neighbor.activeTabId ?: panel.activeTabId,
            tabHistory = neighbor.tabHistory + panel.tabHistory,
        )
        commit(st.copy(panels = (st.panels - panelId) + (neighborId to merged), grid = gridRemovePanel(st.grid, panelId), focusedPanelId = neighborId))
        persist()
    }

    // -----------------------------------------------------------------------------------------
    // Dock (dock-actions.ts)
    // -----------------------------------------------------------------------------------------

    /** Opening an empty dock opens a terminal in it: the dock has no empty state. */
    fun toggleDock() {
        val visible = !s.dock.visible
        commit(s.copy(dock = s.dock.copy(visible = visible)))
        persist()
        if (visible && activeProjectDockTabCount() == 0) openTerminalInDock()
    }

    fun setDockVisible(visible: Boolean) {
        commit(s.copy(dock = s.dock.copy(visible = visible)))
        persist()
    }

    fun setDockHeight(percent: Double) {
        commit(s.copy(dock = s.dock.copy(height = percent.coerceIn(DOCK_HEIGHT_MIN, DOCK_HEIGHT_MAX))))
        persist()
    }

    fun toggleDockExpanded() {
        commit(s.copy(dockExpanded = !s.dockExpanded))
    }

    /** Opens [def] in the dock and shows it, focusing the same tab if the dock already has it. */
    fun openInDock(def: NewTab): String {
        val dockPanel = s.panels[DOCK_PANEL_ID] ?: createDockPanel()
        var tabDef = def
        if (tabDef.type == "terminal" && !jsTruthyKey(tabDef, "terminalIndex")) {
            tabDef = tabDef.copy(metadata = tabDef.metadata.merged(mapOf("terminalIndex" to JsonPrimitive(nextTerminalIndex()))))
        }
        val tabId = deriveTabId(tabDef.type, tabDef.metadata, randomId)
        val next = if (dockPanel.tab(tabId) != null) {
            dockPanel.copy(activeTabId = tabId, tabHistory = pushHistory(dockPanel.tabHistory, tabId))
        } else {
            dockPanel.copy(tabs = dockPanel.tabs + tabDef.withId(tabId), activeTabId = tabId, tabHistory = pushHistory(dockPanel.tabHistory, tabId))
        }
        commit(s.copy(dock = s.dock.copy(visible = true), panels = s.panels + (DOCK_PANEL_ID to next)))
        persist()
        return tabId
    }

    /** Parks a terminal from a grid panel in the dock without ending it ("Move to Dock"). */
    fun redockTab(tabId: String, fromPanelId: String) {
        moveTab(tabId, fromPanelId, DOCK_PANEL_ID)
        commit(s.copy(dock = s.dock.copy(visible = true)))
        persist()
    }

    /** The dock tabs of the open project (the dock panel holds every project's). */
    fun activeProjectDockTabCount(): Int {
        val dock = s.panels[DOCK_PANEL_ID] ?: return 0
        val project = s.currentProject
        return dock.tabs.count { it.projectId.isNullOrEmpty() || project == null || it.projectId == project }
    }

    private fun openTerminalInDock() {
        val project = s.currentProject
        openInDock(NewTab(
            type = "terminal",
            title = "Terminal",
            projectId = project,
            metadata = if (project != null) JsonObject(mapOf("projectName" to JsonPrimitive(project))) else EMPTY_OBJECT,
        ))
    }

    // -----------------------------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------------------------

    /** Only the open project's grid panels and the dock are its layout. */
    private fun WorkspaceState.layoutForSave(): PanelLayout {
        val ids = grid.flatten().toSet()
        return PanelLayout(
            panels = panels.filterKeys { it in ids },
            grid = grid,
            focusedPanelId = focusedPanelId,
            dock = dock,
            dockPanel = panels[DOCK_PANEL_ID] ?: createDockPanel(),
        )
    }

    private fun persist() {
        val project = s.currentProject ?: return
        storage.save(project, s.layoutForSave())
    }

    /** Activates [tabId] in [panelId], stamping it for the phone's "Recent" order. */
    private fun activateIn(panelId: String, tabId: String, focus: String) {
        val panel = s.panels[panelId] ?: return
        commit(s.copy(
            focusedPanelId = focus,
            panels = s.panels + (panelId to panel.copy(tabs = stampActive(panel.tabs, tabId), activeTabId = tabId, tabHistory = pushHistory(panel.tabHistory, tabId))),
        ))
        persist()
    }

    /** A tab activated inside a floating window leaves focus where it was: a window has no tab bar to open into. */
    private fun focusAfterActivate(panelId: String): String = if (isWindowPanelId(panelId)) s.focusedPanelId else panelId

    private fun stampActive(tabs: List<WorkspaceTab>, id: String): List<WorkspaceTab> {
        val at = JsonPrimitive(now())
        return tabs.map { if (it.id == id) it.withMetadata("lastActiveAt" to at) else it }
    }

    private fun nextTerminalIndex(): Int {
        val numbers = s.panels.values.flatMap { it.tabs }.filter { it.type == "terminal" }
            .map { TERMINAL.find(it.id)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
        return if (numbers.isEmpty()) 1 else numbers.max() + 1
    }

    private fun pickDockActiveTab(dock: WorkspacePanel, projectName: String): String? {
        val own = dock.tabs.filter { it.projectId.isNullOrEmpty() || it.projectId == projectName }
        if (own.isEmpty()) return null
        return dock.tabHistory.lastOrNull { id -> own.any { it.id == id } } ?: own.last().id
    }

    /** Publishes [next], then remembers the focused chat's provider when the focused tab changed (the web's store subscription). */
    private fun commit(next: WorkspaceState) {
        val previous = _state.value
        _state.value = next
        val tab = next.panels[next.focusedPanelId]?.activeTab
        val old = previous.panels[next.focusedPanelId]?.activeTab
        if (tab?.id == old?.id && tab?.metadata?.get("providerId") == old?.metadata?.get("providerId") &&
            next.currentProject == previous.currentProject
        ) return
        rememberChatProvider(tab)
    }

    private fun rememberChatProvider(tab: WorkspaceTab?) {
        if (tab?.type != "chat" || jsTruthy(tab.metadata["providerPending"])) return
        val st = _state.value
        val project = tab.projectId ?: tab.metadata["projectName"].string ?: st.currentProject
        val provider = tab.metadata["providerId"].string ?: "claude"
        if (provider == "default") return
        if (project.isNullOrEmpty() || st.lastFocusedChatProviders[project] == provider) return
        _state.value = st.copy(lastFocusedChatProviders = st.lastFocusedChatProviders + (project to provider))
    }

    private fun jsTruthyKey(tab: NewTab, key: String) = jsTruthy(tab.metadata[key])

    private companion object {
        const val DOCK_HEIGHT_MIN = 15.0
        const val DOCK_HEIGHT_MAX = 85.0
        val TERMINAL = Regex("""^terminal:(\d+)""")

        fun pushHistory(history: List<String>, id: String): List<String> {
            val out = history.filter { it != id }.toMutableList()
            out += id
            if (out.size > 50) out.removeAt(0)
            return out
        }
    }
}
