package com.hienlh.ppm.state.workspace

import com.hienlh.ppm.protocol.ChatPreparationSettings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor

// The rules of src/web/stores/panel-utils.ts, in the same order.

/** The dock's panel: in the panels map, never in the grid, so no grid arithmetic counts it. */
const val DOCK_PANEL_ID = "__dock__"

/** What may sit in the dock; anything else a saved blob puts there is dropped on load. */
val DOCK_ALLOWED_TAB_TYPES = setOf("terminal", "system-monitor", "problems")

/** Tab types that own a window kind of their own, so they never detach into a floating window. */
val NON_POPPABLE_TAB_TYPES = setOf("system-monitor", "settings", "problems", "logs")

/** A design canvas needs its iframe's parent to be the main window. */
val NON_PIP_TAB_TYPES = setOf("design")

/** Prefix of the off-grid panels that hold the tabs of a floating window; the window id follows. */
const val WINDOW_PANEL_PREFIX = "__win__:"

/** The layout key of "no project", which never reaches the server. */
const val GLOBAL_PROJECT = "__global__"

const val MAX_ROWS = 3

/** One column on a phone, three on anything wider. */
fun maxColumns(isMobile: Boolean): Int = if (isMobile) 1 else 3

val DEFAULT_DOCK = DockState(visible = false, height = 30.0)

/** One instance per project. */
internal val SINGLETON_TYPES = setOf("settings", "git-log", "assistant")

/** One tab per table's data, structure and object SQL across the grid and the windows, as DBGate keys them. */
internal val ONE_PER_WORKSPACE_TYPES = setOf("database", "db-structure", "db-sql")

/** Removed in earlier versions of the web; filtered out on load. */
internal val OBSOLETE_TAB_TYPES = setOf("projects", "git-status", "git-graph")

/** Tab types that host a chat session (`tabSessionId`, src/web/lib/tab-session-id.ts). */
private val SESSION_TAB_TYPES = setOf("chat", "design", "assistant")

/** `ports` (≤0.17.7) became `tunnels` in the dock, which then moved to the sidebar: neither is a tab any more. */
private val LEGACY_TUNNEL_TAB_TYPES = setOf("ports", "tunnels")

fun isWindowPanelId(panelId: String): Boolean = panelId.startsWith(WINDOW_PANEL_PREFIX)

fun windowIdFromPanelId(panelId: String): String? =
    if (isWindowPanelId(panelId)) panelId.removePrefix(WINDOW_PANEL_PREFIX).ifEmpty { null } else null

/** The web's `randomId()`: the first eight characters of a random UUID. */
fun webRandomId(): String = UUID.randomUUID().toString().take(8)

fun createPanel(id: String, tabs: List<WorkspaceTab> = emptyList(), activeTabId: String? = null) =
    WorkspacePanel(id = id, tabs = tabs, activeTabId = activeTabId, tabHistory = listOfNotNull(activeTabId))

fun createDockPanel() = WorkspacePanel(id = DOCK_PANEL_ID)

fun gridRemovePanel(grid: List<List<String>>, panelId: String): List<List<String>> =
    grid.map { row -> row.filter { it != panelId } }.filter { it.isNotEmpty() }

data class GridPosition(val row: Int, val col: Int)

fun findPanelPosition(grid: List<List<String>>, panelId: String): GridPosition? {
    for ((r, row) in grid.withIndex()) {
        val c = row.indexOf(panelId)
        if (c != -1) return GridPosition(r, c)
    }
    return null
}

/**
 * The tabs a project may see. A grid panel can end up holding another project's tab, which the tab
 * bar hides, so every "is this panel empty?" check has to agree with it. Null and `__global__` both
 * mean unfiltered.
 */
fun visibleTabs(tabs: List<WorkspaceTab>, projectName: String?): List<WorkspaceTab> {
    if (projectName == null || projectName == GLOBAL_PROJECT) return tabs
    return tabs.filter { it.projectId.isNullOrEmpty() || it.projectId == projectName }
}

/** The chat session a tab shows: chat and design tabs both host one. */
fun tabSessionId(tab: WorkspaceTab?): String? {
    if (tab == null || tab.type !in SESSION_TAB_TYPES) return null
    return tab.metadata["sessionId"].let { (it as? JsonPrimitive)?.takeIf { p -> p.isString && p.content.isNotEmpty() }?.content }
}

fun nextUntitledNumber(panels: Map<String, WorkspacePanel>): Int {
    var max = 0
    for (panel in panels.values) for (tab in panel.tabs) {
        UNTITLED.matchEntire(tab.id)?.let { max = maxOf(max, it.groupValues[1].toInt()) }
    }
    return max + 1
}

private val UNTITLED = Regex("""editor:untitled-(\d+)""")

/** Derives a tab's id from its type and metadata (`deriveTabId`), so opening the same thing twice finds it. */
fun deriveTabId(type: String, metadata: JsonObject, randomId: () -> String = ::webRandomId): String {
    fun m(key: String): String? = jsText(metadata[key])
    return when (type) {
        "editor" -> when {
            jsTruthy(metadata["viewerKey"]) -> "editor:viewer:${m("viewerKey")}"
            jsTruthy(metadata["isUntitled"]) -> "editor:untitled-${m("untitledNumber") ?: 1}"
            else -> "editor:${m("filePath") ?: "untitled"}"
        }
        "chat" -> "chat:${m("providerId") ?: "default"}/${m("sessionId") ?: randomId()}"
        "terminal" -> "terminal:${m("terminalIndex") ?: 1}"
        // Database tab ids (`dbTabId`, src/web/lib/db-tabs.ts) come with the database phase.
        "db-query" -> "db-query:${(metadata["queryId"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content ?: randomId()}"
        "db-impexp" -> "db-impexp:${(metadata["impexpId"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content ?: randomId()}"
        "db-connection" -> "db-connection:${m("connectionId") ?: "new-${randomId()}"}"
        "sqlite" -> "sqlite:${m("filePath") ?: "default"}"
        "extension" -> "extension:${(m("viewType") ?: "unknown").removeSuffix(".view")}"
        "git-diff" -> when {
            !m("file1").isNullOrEmpty() && !m("file2").isNullOrEmpty() -> "git-diff:compare:${m("file1")}↔${m("file2")}"
            else -> "git-diff:${m("filePath") ?: "unknown"}"
        }
        "branch-review" -> "branch-review:${m("projectName") ?: "unknown"}"
        "session-review" -> "session-review:${m("projectName") ?: "unknown"}:${m("sessionId") ?: "unknown"}"
        "git-review" -> "git-review:${m("projectName") ?: "unknown"}"
        "conflict-editor" -> "conflict-editor:${m("filePath") ?: "unknown"}"
        "settings", "problems", "system-monitor", "logs", "assistant" -> type
        "group" -> "group:${m("groupId") ?: "unknown"}"
        "design" -> "design:${m("designSlug") ?: "unknown"}"
        "web-preview" -> "web-preview:${m("url") ?: "unknown"}"
        "task" -> "task:${m("taskId") ?: "unknown"}"
        else -> "$type:${randomId()}"
    }
}

private fun scrubLegacyTunnelTabs(panel: WorkspacePanel): WorkspacePanel {
    if (panel.tabs.none { it.type in LEGACY_TUNNEL_TAB_TYPES }) return panel
    val removed = panel.tabs.filter { it.type in LEGACY_TUNNEL_TAB_TYPES }.map { it.id }.toSet()
    val tabs = panel.tabs.filter { it.id !in removed }
    val history = panel.tabHistory.filter { it !in removed }
    val active = panel.activeTabId?.takeIf { it !in removed } ?: history.lastOrNull() ?: tabs.lastOrNull()?.id
    return panel.copy(tabs = tabs, tabHistory = history, activeTabId = active)
}

/** Drops the `ports` / `tunnels` tabs of old versions from every panel and the dock. Idempotent. */
fun migratePortsToTunnels(layout: PanelLayout): PanelLayout = layout.copy(
    panels = layout.panels.mapValuesTo(LinkedHashMap()) { scrubLegacyTunnelTabs(it.value) },
    dockPanel = layout.dockPanel?.let(::scrubLegacyTunnelTabs),
)

/**
 * Old random tab ids (`tab-…`) become derived ones. The web also re-keys a table tab saved before it
 * named its database and schema; that needs `dbTabId`, so it is left to the web until the database phase.
 */
fun migrateTabIds(layout: PanelLayout, randomId: () -> String = ::webRandomId): PanelLayout = layout.copy(
    panels = layout.panels.mapValuesTo(LinkedHashMap()) { (_, panel) ->
        val tabs = panel.tabs.map { if (it.id.startsWith("tab-")) it.copy(id = deriveTabId(it.type, it.metadata, randomId)) else it }
        val ids = HashMap<String, String>()
        panel.tabs.forEachIndexed { i, old -> if (old.id != tabs[i].id) ids[old.id] = tabs[i].id }
        if (ids.isEmpty()) panel
        else panel.copy(
            tabs = tabs,
            activeTabId = panel.activeTabId?.let { ids[it] ?: it },
            tabHistory = panel.tabHistory.map { ids[it] ?: it },
        )
    },
)

/**
 * A blob from before the dock gets a hidden one and an empty dock panel, and a dock panel keeps
 * only the tab types allowed there — a crafted blob must not put anything else in that slot.
 */
fun migrateDockDefaults(layout: PanelLayout): PanelLayout {
    val dock = layout.dock ?: DEFAULT_DOCK
    val saved = layout.dockPanel ?: return layout.copy(dock = dock, dockPanel = createDockPanel())
    val tabs = saved.tabs.filter { it.type in DOCK_ALLOWED_TAB_TYPES }
    val ids = tabs.map { it.id }.toSet()
    val history = saved.tabHistory.filter { it in ids }
    val active = saved.activeTabId?.takeIf { it in ids } ?: history.lastOrNull() ?: tabs.lastOrNull()?.id
    return layout.copy(dock = dock, dockPanel = saved.copy(tabs = tabs, tabHistory = history, activeTabId = active))
}

/** What `loadPanelLayout` does to a stored blob before anything reads it. */
fun migrateLoadedLayout(layout: PanelLayout): PanelLayout = migrateDockDefaults(migrateTabIds(migratePortsToTunnels(layout)))

/**
 * The newer of the two copies; a tie goes to the server's. A time that does not parse loses, as
 * `new Date(x).getTime()` gives NaN on the web and every comparison with NaN is false.
 */
fun resolveWorkspaceConflict(local: SavedLayout?, server: SavedLayout?): SavedLayout? {
    if (local == null) return server
    if (server == null) return local
    return if (isoMillis(server.updatedAt) >= isoMillis(local.updatedAt)) server else local
}

internal fun isoMillis(text: String): Double = try {
    Instant.parse(text).toEpochMilli().toDouble()
} catch (_: Exception) {
    Double.NaN
}

/** JavaScript truthiness of a metadata value. */
internal fun jsTruthy(value: JsonElement?): Boolean = when (value) {
    null, JsonNull -> false
    is JsonPrimitive -> when {
        value.isString -> value.content.isNotEmpty()
        value.booleanOrNull != null -> value.booleanOrNull!!
        else -> value.doubleOrNull?.let { it != 0.0 && !it.isNaN() } ?: true
    }
    else -> true
}

/** A value as a template literal prints it (`${value}`), or null for what `??` replaces. */
internal fun jsText(value: JsonElement?): String? = when (value) {
    null, JsonNull -> null
    is JsonPrimitive -> when {
        value.isString -> value.content
        value.booleanOrNull != null -> value.content
        else -> value.doubleOrNull?.let { d -> if (d == floor(d) && abs(d) < 1e21) d.toLong().toString() else d.toString() } ?: value.content
    }
    is JsonObject -> "[object Object]"
    is JsonArray -> value.joinToString(",") { jsText(it).orEmpty() }
}

/** What a new chat tab is resolved from before any request answers: the cached AI settings, and the project's cached providers. */
data class NewChatDefaults(val settings: ChatPreparationSettings?, val providers: List<String>?)

/**
 * `resolveChatOpenMetadata`: a new chat tab's provider and mode, "warm" from [defaults] so its
 * first frame has both — a mode marked as a cached default, which its preparation may still
 * replace. When they cannot name a provider the project offers, the pending placeholder instead.
 */
fun resolveChatOpenMetadata(defaults: NewChatDefaults?, focusedProvider: String?): Map<String, JsonElement?> {
    val settings = defaults?.settings
    val resolved = settings?.resolveProvider(focusedProvider)
    if (settings != null && resolved != null && defaults.providers?.contains(resolved) == true) {
        return mapOf(
            "providerId" to JsonPrimitive(resolved),
            "permissionMode" to JsonPrimitive(settings.permissionMode(resolved)),
            "permissionModeSource" to JsonPrimitive("cache"),
        )
    }
    return mapOf("providerPending" to JsonPrimitive(true), "focusedProviderOnOpen" to focusedProvider?.let(::JsonPrimitive))
}

/**
 * `resolveChatOpenPermission`: a new chat opened on a known provider (`/clear`) gets the cached
 * mode, so its chip is not blank until its preparation answers; a mode it was opened with on
 * purpose is kept.
 */
fun resolveChatOpenPermission(metadata: JsonObject, providerId: String, settings: ChatPreparationSettings?): Map<String, JsonElement?> {
    if (jsTruthy(metadata["permissionMode"]) && metadata["permissionModeSource"].let { (it as? JsonPrimitive)?.content } != "cache") return emptyMap()
    val cached = settings?.permissionModes?.get(providerId)?.takeIf { it.isNotEmpty() } ?: return emptyMap()
    return mapOf("permissionMode" to JsonPrimitive(cached), "permissionModeSource" to JsonPrimitive("cache"))
}
