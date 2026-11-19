package com.hienlh.ppm.state.workspace

import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.windows.MAX_WINDOWS
import com.hienlh.ppm.state.windows.Rect
import com.hienlh.ppm.state.windows.WindowKind
import com.hienlh.ppm.state.windows.WindowRuntimeState
import com.hienlh.ppm.state.windows.WindowStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Where a project's layout is read from and written to (this device's copy; see [WorkspaceCache]). */
interface LayoutStorage {
    /** The stored layout, already migrated; null when the project has none. */
    fun load(project: String): PanelLayout?

    fun save(project: String, layout: PanelLayout)

    /** The floating windows' panels ([saveWindowPanels]): this device's only, in no project's layout. */
    fun loadWindowPanels(): String? = null

    fun saveWindowPanels(text: String?) {}
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
 * (src/web/stores/panel-store.ts, with panel-utils.ts, dock-actions.ts and window-panel-actions.ts
 * for tabs detached into [windows]). What opening a chat resolves from the web's local cache is up
 * to the caller.
 *
 * Like the other controllers it is driven from one thread; [state] is what other threads read.
 */
class WorkspaceStore(
    private val storage: LayoutStorage,
    /** The window is narrower than 768 dp: one column, and every tab opens in the first panel. */
    private val isMobile: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val randomId: () -> String = ::webRandomId,
    /** The floating windows a tab can be detached into; none, none can. */
    private val windows: WindowStore? = null,
    /** Where a request the store had to redirect or refuse is reported (logcat on Android). */
    private val warn: (String) -> Unit = {},
    /** What a new chat tab is resolved from before its first paint, by project; none, each waits for its preparation. */
    private val newChatDefaults: (project: String?) -> NewChatDefaults? = { null },
) {
    private val _state = MutableStateFlow(defaultState())
    val state: StateFlow<WorkspaceState> = _state

    private val s: WorkspaceState get() = _state.value

    /** For the rules kept beside the store (`openAiTab`). */
    internal val mobile: Boolean get() = isMobile()

    internal fun clock(): Long = now()

    /** The saved window panels join the panels once, before the first project reads them. */
    private var windowPanelsHydrated = false

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
        hydrateWindowPanels()
        switchProject(s, projectName)
    }

    /** [switchProject] from [st], which a reload has not published. */
    private fun switchProject(st: WorkspaceState, projectName: String) {
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
        grids[projectName]?.let { kept ->
            // Never a grid naming a panel that went while the project was out of sight, nor focus on one:
            // a tab asked for would open nowhere.
            var restoredGrid = kept.map { row -> row.filter { it in st.panels } }.filter { it.isNotEmpty() }
            var held = st.panels
            if (restoredGrid.isEmpty()) {
                val panel = createPanel(newPanelId())
                held = held + (panel.id to panel)
                restoredGrid = listOf(listOf(panel.id))
            }
            if (restoredGrid != kept) {
                warn("switchProject($projectName): its kept grid $kept named panels that are gone; showing $restoredGrid")
                grids[projectName] = restoredGrid
            }
            val dockPanel = held[DOCK_PANEL_ID]
            val panels = if (dockPanel == null) held
            else held + (DOCK_PANEL_ID to dockPanel.copy(activeTabId = pickDockActiveTab(dockPanel, projectName)))
            commit(st.copy(
                currentProject = projectName,
                panels = panels,
                grid = restoredGrid,
                focusedPanelId = focused[projectName]?.takeIf { it in restoredGrid.flatten() } ?: restoredGrid.first().first(),
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

    /** Detach a removed/renamed registration without saving its old layout again. */
    fun forgetProject(projectName: String) {
        val st = s
        val active = st.currentProject == projectName
        val oldIds = (st.projectGrids[projectName].orEmpty().flatten() + if (active) st.grid.flatten() else emptyList()).toSet()
        val panels = LinkedHashMap(st.panels)
        for (id in oldIds) if (id != DOCK_PANEL_ID) panels.remove(id)
        panels[DOCK_PANEL_ID]?.let { dock ->
            val remaining = dock.tabs.filter { (it.projectId ?: it.metadata["projectName"].string) != projectName }
            val ids = remaining.map { it.id }.toSet()
            panels[DOCK_PANEL_ID] = dock.copy(tabs = remaining,
                tabHistory = dock.tabHistory.filter { it in ids },
                activeTabId = dock.activeTabId?.takeIf { it in ids } ?: remaining.lastOrNull()?.id)
        }
        val (stripped, emptied) = stripProjectFromWindowPanels(panels, projectName)
        commit(st.copy(panels = stripped,
            currentProject = if (active) null else st.currentProject,
            grid = if (active) emptyList() else st.grid,
            focusedPanelId = if (active) "" else st.focusedPanelId,
            dock = if (active) DEFAULT_DOCK else st.dock,
            dockExpanded = if (active) false else st.dockExpanded,
            projectGrids = st.projectGrids - projectName,
            projectFocused = st.projectFocused - projectName,
            projectDock = st.projectDock - projectName,
            lastFocusedChatProviders = st.lastFocusedChatProviders - projectName))
        for (windowId in emptied) windows?.close(windowId)
    }

    /**
     * Forgets what is in memory for [projectName] and loads it again, e.g. after its copy was
     * replaced by the server's. One state goes out, not the project gone and then back: a chat
     * whose tab comes back would be ended in between and started over, losing what it showed.
     */
    fun reloadProject(projectName: String) {
        hydrateWindowPanels()
        val st = s
        // The open project's grid is the live one: its entry in projectGrids is the snapshot taken
        // when it was loaded, without the panels split off since. Left behind, those would stay in
        // memory with their tabs, on no grid, and a chat found in one would be "shown" there.
        val oldIds = (st.projectGrids[projectName].orEmpty().flatten() + if (st.currentProject == projectName) st.grid.flatten() else emptyList()).toSet()
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
        // A window panel is off the grid, so the loop above never reaches it: a floating window
        // would go on showing a tab of the project as it was.
        val (stripped, emptied) = stripProjectFromWindowPanels(panels, projectName)
        switchProject(st.copy(
            projectGrids = st.projectGrids - projectName,
            projectFocused = st.projectFocused - projectName,
            projectDock = st.projectDock - projectName,
            panels = stripped,
            currentProject = null,
        ), projectName)
        // The project coming back was a project change, which records the focused chat's provider.
        rememberChatProvider(s.panels[s.focusedPanelId]?.activeTab)
        for (windowId in emptied) windows?.close(windowId)
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
        val asked = (if (mobile) s.grid.firstOrNull()?.firstOrNull() else null) ?: panelId ?: s.focusedPanelId
        val pid = openTarget(asked) ?: run {
            warn("openTab(${def.type}): no panel to open in (asked $asked, grid ${s.grid}, project ${s.currentProject})")
            return ""
        }

        var tabDef = def
        // A new chat opens with a provider and mode already, when this device's copy of the
        // settings can name them; else pending, for its preparation to resolve.
        if (tabDef.type == "chat" && !jsTruthyKey(tabDef, "sessionId") && !jsTruthyKey(tabDef, "providerId")) {
            val project = tabDef.projectId ?: tabDef.metadata["projectName"].string ?: s.currentProject
            val focused = project?.let { s.lastFocusedChatProviders[it] }
            tabDef = tabDef.copy(metadata = tabDef.metadata.merged(resolveChatOpenMetadata(newChatDefaults(project), focused)))
        } else if (tabDef.type == "chat" && !jsTruthyKey(tabDef, "sessionId")) {
            val providerId = tabDef.metadata["providerId"].string
            if (providerId != null) {
                val project = tabDef.projectId ?: tabDef.metadata["projectName"].string ?: s.currentProject
                tabDef = tabDef.copy(metadata = tabDef.metadata.merged(resolveChatOpenPermission(tabDef.metadata, providerId, newChatDefaults(project)?.settings)))
            }
        }
        if (tabDef.type == "terminal" && !jsTruthyKey(tabDef, "terminalIndex")) {
            tabDef = tabDef.copy(metadata = tabDef.metadata.merged(mapOf("terminalIndex" to JsonPrimitive(nextTerminalIndex()))))
        }
        val baseId = deriveTabId(tabDef.type, tabDef.metadata, randomId)

        // A chat already open anywhere is focused, matched by its session: a "New Chat" tab keeps
        // the id it was minted with after it gets a session. A design tab hosts its session too.
        if (tabDef.type == "chat") {
            tabDef.metadata["sessionId"].string?.takeIf { it.isNotEmpty() }?.let { sid ->
                for (panel in panelsOnScreenFirst()) {
                    val existing = panel.tabs.firstOrNull { tabSessionId(it) == sid } ?: continue
                    rememberChatProvider(existing)
                    if (bringIntoView(existing, panel.id, pid)) return existing.id
                    activateIn(panel.id, existing.id, focus = focusAfterActivate(panel.id))
                    raiseWindowOf(panel.id)
                    return existing.id
                }
            }
        }

        // One tab per design, matched by slug and project: other projects' keep-alive panels can share a slug.
        if (tabDef.type == "design") {
            val slug = tabDef.metadata["designSlug"]
            val project = tabDef.projectId ?: tabDef.metadata["projectName"].string
            for (panel in panelsOnScreenFirst()) {
                val existing = panel.tabs.firstOrNull {
                    it.type == "design" && it.metadata["designSlug"] == slug && (it.projectId ?: it.metadata["projectName"].string) == project
                } ?: continue
                if (bringIntoView(existing, panel.id, pid)) return existing.id
                activateIn(panel.id, existing.id, focus = focusAfterActivate(panel.id))
                raiseWindowOf(panel.id)
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
                raiseWindowOf(panel.id)
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
        // With no panel named, the one on screen: another project's kept panel can hold a tab of the
        // same id (a file both projects have, a design slug, the remote desktop).
        val panel = (if (panelId != null) s.panels[panelId] else panelsOnScreenFirst().firstOrNull { it.tab(tabId) != null }) ?: return
        val pid = panel.id
        val st = s
        val tabs = panel.tabs.filter { it.id != tabId }
        val history = panel.tabHistory.filter { it != tabId }
        var active = panel.activeTabId
        if (panel.activeTabId == tabId) {
            val previous = history.lastOrNull()
            active = previous?.takeIf { id -> tabs.any { it.id == id } } ?: tabs.lastOrNull()?.id
        }
        val closed = st.copy(panels = st.panels + (pid to panel.copy(tabs = tabs, activeTabId = active, tabHistory = history)))
        commit(if (tabs.isEmpty()) withoutEmptiedPanel(closed, pid) ?: closed else closed)
        persist()
        // The dock has no empty state: its last tab closing hides it.
        if (pid == DOCK_PANEL_ID && activeProjectDockTabCount() == 0) setDockVisible(false)
        // A window that lost its last tab closes with it: it can never show anything again.
        if (isWindowPanelId(pid)) syncWindowPanel(pid)
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

    /** Pins or unpins a tab (`setTabPinned`); pinned tabs stay at the start of their panel's bar. */
    fun setTabPinned(tabId: String, pinned: Boolean) {
        val panel = s.panelOf(tabId) ?: return
        val tabs = withPinned(panel.tabs, tabId, pinned)
        if (tabs === panel.tabs) return
        commit(s.copy(panels = s.panels + (panel.id to panel.copy(tabs = tabs))))
        persist()
    }

    fun reorderTab(tabId: String, panelId: String, newIndex: Int) {
        val panel = s.panels[panelId] ?: return
        val oldIndex = panel.tabs.indexOfFirst { it.id == tabId }
        if (oldIndex == -1 || oldIndex == newIndex) return
        val tabs = panel.tabs.toMutableList()
        val moved = tabs.removeAt(oldIndex)
        tabs.add(newIndex.coerceIn(0, tabs.size), moved)
        // A drag cannot cross the pinned edge: pins stay first, so a tab dropped among them lands
        // right after them, and a pinned one dropped among the others lands at the end of the pins.
        commit(s.copy(panels = s.panels + (panelId to panel.copy(tabs = pinnedFirst(tabs)))))
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
        val toTabs = pinnedFirst(to.tabs.toMutableList().apply { if (insertIndex != null) add(insertIndex.coerceIn(0, size), tab) else add(tab) })
        val target = to.copy(tabs = toTabs, activeTabId = tabId, tabHistory = pushHistory(to.tabHistory, tabId))

        val st = s
        // The dock and a window's panel are never removed here: the dock outlives its tabs, and a
        // window's panel goes with its window.
        val offGridSource = fromPanelId == DOCK_PANEL_ID || isWindowPanelId(fromPanelId)
        // Focus follows the tab, except into the dock, where it stays on the grid (focusAfterActivate).
        fun focus(grid: List<List<String>>) = when {
            toPanelId != DOCK_PANEL_ID -> toPanelId
            st.focusedPanelId in grid.flatten() -> st.focusedPanelId
            else -> grid.flatten().firstOrNull() ?: st.focusedPanelId
        }
        if (fromTabs.isEmpty() && st.gridPanelIds.size > 1 && !offGridSource) {
            val grid = gridRemovePanel(st.grid, fromPanelId)
            commit(st.copy(
                panels = (st.panels - fromPanelId) + (toPanelId to target),
                grid = grid,
                focusedPanelId = focus(grid),
            ))
        } else {
            commit(st.copy(
                focusedPanelId = focus(st.grid),
                panels = st.panels + (fromPanelId to from.copy(tabs = fromTabs, activeTabId = fromActive, tabHistory = fromHistory)) + (toPanelId to target),
            ))
        }
        persist()
        // Moving the last tab out of a window empties it exactly as closing that tab would.
        if (isWindowPanelId(fromPanelId)) syncWindowPanel(fromPanelId)
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
    // Floating windows (window-panel-actions.ts, close-window.ts)
    // -----------------------------------------------------------------------------------------

    /**
     * Detaches [tabId] into a new floating window and returns the window's id; null when that is
     * refused — on a phone, for a tab type with a window of its own, with every window slot taken —
     * and then nothing has changed. With [closeTabsOnClose] the window is the tab's home, and closing
     * it closes the tab rather than handing it back.
     */
    fun popOutTab(tabId: String, fromPanelId: String, closeTabsOnClose: Boolean = false, rect: Rect? = null): String? {
        val windows = windows ?: return null
        if (isMobile()) return null
        val tab = s.panels[fromPanelId]?.tab(tabId) ?: return null
        if (tab.type in NON_POPPABLE_TAB_TYPES) return null
        // At the cap `open` raises the oldest window and answers its id: the tab would land in an unrelated one.
        if (windows.state.value.windows.size >= MAX_WINDOWS) return null

        val windowId = windows.open(WindowKind.TabHost, buildJsonObject {
            put("originPanelId", JsonPrimitive(fromPanelId))
            put("title", JsonPrimitive(tab.title))
            if (closeTabsOnClose) put("closeTabsOnClose", JsonPrimitive(true))
        }, rect)
        val panelId = windowPanelId(windowId)
        commit(s.copy(panels = s.panels + (panelId to createPanel(panelId))))
        moveTab(tabId, fromPanelId, panelId)
        // moveTab focused the window's panel: the next tab opened would land inside it.
        gridFocusTarget(s.grid, fromPanelId)?.let { commit(s.copy(focusedPanelId = it)) }
        persist()
        return windowId
    }

    /**
     * Opens [def] straight into a new floating window that is its home (`openTabInNewWindow`):
     * never through a grid, where `openTab` would focus a copy kept in another project's hidden
     * panel. Answers the tab's and the window's ids, or null on a phone or with every window slot
     * taken, and then nothing has changed.
     */
    fun openTabInNewWindow(def: NewTab, rect: Rect? = null): Pair<String, String>? {
        val windows = windows ?: return null
        if (isMobile()) return null
        if (windows.state.value.windows.size >= MAX_WINDOWS) return null
        val windowId = windows.open(WindowKind.TabHost, buildJsonObject {
            put("originPanelId", JsonNull)
            put("title", JsonPrimitive(def.title))
            put("closeTabsOnClose", JsonPrimitive(true))
        }, rect)
        val panelId = windowPanelId(windowId)
        val baseId = deriveTabId(def.type, def.metadata, randomId)
        // The same session may be open in a grid too: a tab id is unique across every panel.
        val taken = s.panels.values.any { panel -> panel.tabs.any { it.id == baseId } }
        val id = if (taken) "$baseId@$panelId" else baseId
        val tab = def.withId(id).withMetadata("lastActiveAt" to JsonPrimitive(now()))
        commit(s.copy(panels = s.panels + (panelId to createPanel(panelId, listOf(tab), id))))
        persist()
        return id to windowId
    }

    /**
     * Sends every tab of a window back to the grid, drops its panel and closes the window: to the
     * panel the tabs came from while it exists (the dock too, never another window), else the
     * focused grid panel. Nothing happens once the panel is gone.
     */
    fun redockFromWindow(windowId: String, originPanelId: String?) {
        val panelId = windowPanelId(windowId)
        val panel = s.panels[panelId] ?: return
        val target = redockTarget(originPanelId)
        if (target != null) for (tab in panel.tabs) moveTab(tab.id, panelId, target)
        val st = s
        val focus = gridFocusTarget(st.grid, target ?: st.focusedPanelId)
        commit(st.copy(panels = st.panels - panelId, focusedPanelId = focus ?: st.focusedPanelId))
        persist()
        windows?.close(windowId)
    }

    /**
     * The one way a user closes a window: its ×, Escape on its titlebar, the dock list's ×. A
     * detached tab goes back to the grid, unless the window is its home (`closeTabsOnClose`), when
     * its tabs close with it. The web hands the tab back when the window's body unmounts; here
     * closing the window does.
     */
    fun closeWindow(windowId: String) {
        val windows = windows ?: return
        val win = windows.state.value.windows[windowId] ?: return
        if (win.kind == WindowKind.TabHost) {
            val panelId = windowPanelId(windowId)
            val tabs = s.panels[panelId]?.tabs.orEmpty()
            if (closesItsTabs(win) && tabs.isNotEmpty()) {
                // The last one closing takes the window with it (syncWindowPanel).
                for (tab in tabs) closeTab(tab.id, panelId)
                return
            }
            redockFromWindow(windowId, win.payload?.get("originPanelId").string)
        }
        windows.close(windowId)
    }

    /**
     * Every detached tab back to the grid, as when the web's window layer unmounts below 768 px:
     * the phone layout has no windows to show them in. Other windows wait for the layer to return.
     */
    fun redockAllWindows() {
        val windows = windows ?: return
        for (win in windows.state.value.windows.values) {
            if (win.kind == WindowKind.TabHost) redockFromWindow(win.id, win.payload?.get("originPanelId").string)
        }
    }

    /**
     * Mends the window panels against the windows alive (use-window-panel-reconcile.ts): run once
     * per project load, after the windows were restored — or at once on a phone, where no window
     * is alive and every detached tab comes home.
     */
    fun reconcileWindowPanels() {
        val live = if (isMobile()) emptyList()
        else windows?.state?.value?.windows?.values.orEmpty().filter { it.kind == WindowKind.TabHost }.map { it.id }
        val st = s
        val result = reconcileTabHostWindows(st.panels, st.grid, st.focusedPanelId, live)
        if (result.changed) {
            commit(st.copy(panels = result.panels, focusedPanelId = result.focusedPanelId))
            persist()
        }
        for (windowId in result.windowIdsToClose) windows?.close(windowId)
    }

    /** A window and its panel go together: a panel emptied by a close or a move takes its window along. */
    private fun syncWindowPanel(panelId: String) {
        if (s.panels[panelId]?.tabs?.isNotEmpty() == true) return
        if (panelId in s.panels) commit(s.copy(panels = s.panels - panelId))
        windowIdFromPanelId(panelId)?.let { windows?.close(it) }
    }

    /** A tab found open in a window is only shown once that window comes forward, out of the dock if need be. */
    private fun raiseWindowOf(panelId: String) {
        windowIdFromPanelId(panelId)?.let { windows?.focus(it) }
    }

    /** The saved window panels join what is in memory, which wins: a saved twin can only be older. */
    private fun hydrateWindowPanels() {
        if (windowPanelsHydrated) return
        windowPanelsHydrated = true
        val loaded = loadWindowPanels(storage.loadWindowPanels())
        if (loaded.isNotEmpty()) commit(s.copy(panels = loaded + s.panels))
    }

    /** Where detached tabs go home to. */
    private fun redockTarget(originPanelId: String?): String? {
        if (originPanelId != null && originPanelId in s.panels && !isWindowPanelId(originPanelId)) return originPanelId
        return gridFocusTarget(s.grid, s.focusedPanelId)
    }

    /** A grid panel to hold focus: [preferred] while it is in the grid, else the first; null for an empty grid. */
    private fun gridFocusTarget(grid: List<List<String>>, preferred: String?): String? {
        val flat = grid.flatten()
        return preferred?.takeIf { it in flat } ?: flat.firstOrNull()
    }

    /**
     * Whether [panelId] is on screen: in the open project's grid, the dock while it shows, or (off
     * a phone) the panel of a window that is open. Another project's kept panels, a window panel
     * whose window went, and a panel no grid holds any more are in memory but nowhere to be seen.
     */
    private fun onScreen(panelId: String): Boolean {
        if (panelId !in s.panels) return false
        if (panelId in s.gridPanelIds) return true
        if (panelId == DOCK_PANEL_ID) return s.dock.visible
        val windowId = windowIdFromPanelId(panelId) ?: return false
        if (isMobile()) return false
        val windows = windows ?: return true
        return windowId in windows.state.value.windows
    }

    /** Every panel, those on screen first: what a search for an open tab should find first. */
    private fun panelsOnScreenFirst(): List<WorkspacePanel> = s.panels.values.sortedBy { !onScreen(it.id) }

    /**
     * Where a tab asked for in [asked] opens: there, while it is on screen (or the dock, named on
     * purpose); else in a grid panel, which takes focus — a tab opened anywhere else would never be
     * seen, and neither would every one after it. Null when the grid has no panel.
     */
    private fun openTarget(asked: String): String? {
        if (asked == DOCK_PANEL_ID && asked in s.panels || onScreen(asked)) return asked
        val fallback = gridFocusTarget(s.grid, s.focusedPanelId)?.takeIf { it in s.panels }
            ?: s.gridPanelIds.firstOrNull { it in s.panels } ?: return null
        warn("openTab: panel $asked is not on screen (grid ${s.grid}, focus ${s.focusedPanelId}); opening in $fallback")
        if (s.focusedPanelId != fallback) commit(s.copy(focusedPanelId = fallback))
        return fallback
    }

    /**
     * A tab found open in [fromId], a panel nobody sees, is moved to the front of a grid panel
     * ([intoId] when that is one, else the focused one), which takes focus: activated where it was,
     * opening it would do nothing at all. False when it is on screen already, or no grid panel is there.
     */
    private fun bringIntoView(tab: WorkspaceTab, fromId: String, intoId: String): Boolean {
        if (onScreen(fromId)) return false
        val st = s
        val target = intoId.takeIf { it in st.gridPanelIds } ?: gridFocusTarget(st.grid, st.focusedPanelId) ?: return false
        val from = st.panels[fromId] ?: return false
        val into = st.panels[target] ?: return false
        warn("openTab: ${tab.id} was in $fromId, which is not on screen; moving it to $target")
        val fromTabs = from.tabs.filter { it.id != tab.id }
        val fromHistory = from.tabHistory.filter { it != tab.id }
        val left = from.copy(
            tabs = fromTabs,
            tabHistory = fromHistory,
            activeTabId = from.activeTabId?.takeIf { it != tab.id } ?: fromHistory.lastOrNull() ?: fromTabs.lastOrNull()?.id,
        )
        val moved = stampActive(listOf(tab), tab.id)
        val arrived = into.copy(
            tabs = pinnedFirst(into.tabs.filter { it.id != tab.id } + moved),
            activeTabId = tab.id,
            tabHistory = pushHistory(into.tabHistory, tab.id),
        )
        val next = st.copy(focusedPanelId = target, panels = st.panels + (fromId to left) + (target to arrived))
        commit(if (fromTabs.isEmpty()) withoutEmptiedPanel(next, fromId) ?: next else next)
        persist()
        if (fromTabs.isEmpty() && isWindowPanelId(fromId)) windowIdFromPanelId(fromId)?.let { windows?.close(it) }
        return true
    }

    /**
     * [st] without the panel [pid], just emptied, wherever it is: off the open grid unless it is
     * its last, off another project's kept grid unless it is that one's last (and that project's
     * focus moved off it, or switching back would focus a panel that is gone), a window's panel or
     * one no grid holds always. Null when it stays: the dock outlives its tabs.
     */
    private fun withoutEmptiedPanel(st: WorkspaceState, pid: String): WorkspaceState? {
        if (pid == DOCK_PANEL_ID) return null
        if (pid in st.gridPanelIds) {
            if (st.gridPanelIds.size <= 1) return null
            val grid = gridRemovePanel(st.grid, pid)
            val focusedId = if (st.focusedPanelId == pid) grid.flatten().first() else st.focusedPanelId
            return st.copy(panels = st.panels - pid, grid = grid, focusedPanelId = focusedId)
        }
        // The open project's own entry is a snapshot from its load; its grid was handled above.
        val owner = st.projectGrids.entries.firstOrNull { (project, grid) -> project != st.currentProject && grid.any { pid in it } }
        if (owner != null) {
            val (project, kept) = owner
            if (kept.flatten().size <= 1) return null
            val grid = gridRemovePanel(kept, pid)
            val focusedId = st.projectFocused[project]?.takeIf { it != pid } ?: grid.flatten().first()
            return st.copy(
                panels = st.panels - pid,
                projectGrids = st.projectGrids + (project to grid),
                projectFocused = st.projectFocused + (project to focusedId),
            )
        }
        return st.copy(panels = st.panels - pid)
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

    /**
     * A tab activated inside a floating window leaves focus where it was: a window has no tab bar
     * to open into. Nor does the dock here, though the web moves focus there: a chat opened from
     * the sidebar after a dock pill was picked would land in the dock (a web bug, see the plan).
     */
    private fun focusAfterActivate(panelId: String): String =
        if (isWindowPanelId(panelId) || panelId == DOCK_PANEL_ID) s.focusedPanelId else panelId

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
        // Saved on every change. The web saves them at its moves and closes only, so a tab updated
        // inside a window afterwards (a chat getting its session) stays saved as it was before.
        if (previous.panels !== next.panels) {
            val windowPanels = next.panels.filterKeys(::isWindowPanelId)
            if (windowPanels != previous.panels.filterKeys(::isWindowPanelId)) storage.saveWindowPanels(saveWindowPanels(windowPanels))
        }
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
