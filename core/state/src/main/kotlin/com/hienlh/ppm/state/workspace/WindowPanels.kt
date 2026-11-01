package com.hienlh.ppm.state.workspace

import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.windows.WindowKind
import com.hienlh.ppm.state.windows.WindowRuntimeState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

// The panels that hold the tabs of floating windows (window-panel-persistence.ts,
// window-panel-reconcile.ts and the pure half of window-panel-actions.ts).

/** The panel holding the tabs of the floating window [windowId]. */
fun windowPanelId(windowId: String): String = "$WINDOW_PANEL_PREFIX$windowId"

/** Closing this window closes its tabs too, rather than handing them back to the grid. */
fun closesItsTabs(win: WindowRuntimeState): Boolean =
    win.kind == WindowKind.TabHost && win.payload?.get("closeTabsOnClose") == JsonPrimitive(true)

/**
 * Tab types a saved window panel may bring back (`POPPABLE_TAB_TYPES`): the stored blob is not
 * trusted to mount whatever it names in a window. Settings, System Monitor, Problems and Logs own
 * a window kind of their own.
 */
private val POPPABLE_TAB_TYPES = setOf(
    "terminal", "chat", "editor", "database", "db-structure", "db-sql", "db-query", "db-impexp", "db-connection",
    "sqlite", "git-diff", "branch-review", "session-review", "git-review", "extension", "extension-webview",
    "conflict-editor", "git-log", "ai-resource", "group", "design", "android", "web-preview",
)

fun isPoppableTabType(type: String): Boolean = type in POPPABLE_TAB_TYPES

/**
 * The window panels as `ppm-window-panels` holds them, empty ones left out; null for none. They are
 * this device's alone, like the windows: never part of a project's layout, so never on the server.
 */
fun saveWindowPanels(panels: Map<String, WorkspacePanel>): String? {
    val kept = panels.filter { (id, panel) -> isWindowPanelId(id) && panel.tabs.isNotEmpty() }
    if (kept.isEmpty()) return null
    return buildJsonObject { for ((id, panel) in kept) put(id, panel.toJson()) }.toString()
}

/** Reads saved window panels, dropping anything malformed or not allowed in a window. */
fun loadWindowPanels(text: String?): Map<String, WorkspacePanel> {
    val parsed = text?.let(WireJson::parseOrNull) as? JsonObject ?: return emptyMap()
    val out = LinkedHashMap<String, WorkspacePanel>()
    for ((id, value) in parsed) {
        if (!isWindowPanelId(id)) continue
        val panel = value as? JsonObject ?: continue
        val rawTabs = panel["tabs"].array ?: continue
        // `isValidTab`: a non-empty id, a title and a type that may live in a window.
        val tabs = rawTabs.filter { (it as? JsonObject)?.get("title").string != null }
            .mapNotNull(WorkspaceTab::fromJson)
            .filter { it.id.isNotEmpty() && isPoppableTabType(it.type) }
        if (tabs.isEmpty()) continue
        val ids = tabs.map { it.id }.toSet()
        val history = panel["tabHistory"].array.orEmpty().mapNotNull { it.string }.filter { it in ids }
        val active = panel["activeTabId"].string?.takeIf { it in ids } ?: history.lastOrNull() ?: tabs.last().id
        out[id] = WorkspacePanel(id = id, tabs = tabs, activeTabId = active, tabHistory = history)
    }
    return out
}

/** What [reconcileTabHostWindows] decided. */
data class WindowReconcile(
    val panels: Map<String, WorkspacePanel>,
    val focusedPanelId: String,
    /** Tab-host windows with no panel behind them: the caller closes these. */
    val windowIdsToClose: List<String>,
    /** [panels] or [focusedPanelId] differ from what went in. */
    val changed: Boolean,
)

/**
 * Mends the split between window panels and the windows alive (the two are saved apart, so a
 * restart can bring back either alone): a panel whose window is gone sends its tabs to the grid,
 * and a window with no panel is closed. With no window layer (a phone) no window is alive, so
 * every detached tab comes home.
 */
fun reconcileTabHostWindows(
    panels: Map<String, WorkspacePanel>,
    grid: List<List<String>>,
    focusedPanelId: String,
    liveWindowIds: Collection<String>,
): WindowReconcile {
    val flat = grid.flatten()
    val out = LinkedHashMap(panels)
    var focused = focusedPanelId
    var changed = false

    // With no grid panel to take them, the tabs stay where they are: losing them would be worse.
    val target = if (focusedPanelId in flat) focusedPanelId else flat.firstOrNull()
    for ((panelId, panel) in panels) {
        if (!isWindowPanelId(panelId)) continue
        val windowId = windowIdFromPanelId(panelId)
        if (windowId != null && windowId in liveWindowIds) continue
        if (target == null) continue
        val receiver = out[target] ?: continue
        out[target] = appendTabs(receiver, panel.tabs)
        out.remove(panelId)
        changed = true
    }

    val windowIdsToClose = liveWindowIds.filter { windowPanelId(it) !in out }

    // Focus must sit on a grid panel, or the next tab opened lands in a window, or nowhere.
    if (focused !in flat) {
        flat.firstOrNull()?.let {
            focused = it
            changed = true
        }
    }
    return WindowReconcile(out, focused, windowIdsToClose, changed)
}

/** Appends [tabs] to [target] without changing the tab its user was last looking at. */
private fun appendTabs(target: WorkspacePanel, tabs: List<WorkspaceTab>): WorkspacePanel {
    val known = target.tabs.map { it.id }.toSet()
    val incoming = tabs.filter { it.id !in known }
    if (incoming.isEmpty()) return target
    val merged = target.tabs + incoming
    return target.copy(
        tabs = merged,
        tabHistory = target.tabHistory + incoming.map { it.id },
        activeTabId = target.activeTabId ?: merged.last().id,
    )
}

/**
 * [projectName]'s tabs taken out of every window panel, for a project reloaded from scratch: its
 * grid goes, and a window still showing one of its tabs would outlive it. Returns the panels and
 * the windows left empty.
 */
fun stripProjectFromWindowPanels(panels: Map<String, WorkspacePanel>, projectName: String): Pair<Map<String, WorkspacePanel>, List<String>> {
    val out = LinkedHashMap(panels)
    val windowIdsToClose = ArrayList<String>()
    for ((id, panel) in panels) {
        if (!isWindowPanelId(id)) continue
        val tabs = panel.tabs.filter { (it.projectId ?: it.metadata["projectName"].string) != projectName }
        if (tabs.size == panel.tabs.size) continue
        val windowId = windowIdFromPanelId(id)
        if (tabs.isEmpty()) {
            out.remove(id)
            windowId?.let(windowIdsToClose::add)
            continue
        }
        val ids = tabs.map { it.id }.toSet()
        val history = panel.tabHistory.filter { it in ids }
        out[id] = panel.copy(
            tabs = tabs,
            tabHistory = history,
            activeTabId = panel.activeTabId?.takeIf { it in ids } ?: history.lastOrNull() ?: tabs.last().id,
        )
    }
    return out to windowIdsToClose
}
