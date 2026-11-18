package com.hienlh.ppm.state.assistant

import com.hienlh.ppm.state.windows.WindowRuntimeState
import com.hienlh.ppm.state.windows.WindowVisualState
import com.hienlh.ppm.state.windows.windowsInRankOrder
import com.hienlh.ppm.state.workspace.DOCK_PANEL_ID
import com.hienlh.ppm.state.workspace.WorkspacePanel
import com.hienlh.ppm.state.workspace.WorkspaceState
import com.hienlh.ppm.state.workspace.WorkspaceTab
import com.hienlh.ppm.state.workspace.visibleTabs
import com.hienlh.ppm.state.workspace.windowPanelId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * What the PPM Assistant's `ui_get_state` reads (`ui-state-snapshot.ts`): the layout this device
 * shows, as plain data. Only the metadata keys listed for a tab's type go out, and only short
 * scalars; titles are cut and the whole answer is kept under [MAX_SNAPSHOT_CHARS].
 */

const val MAX_SNAPSHOT_CHARS = 32 * 1024
const val MAX_SNAPSHOT_TITLE_CHARS = 160
private const val MAX_METADATA_VALUE_CHARS = 300

private val SESSION_KEYS = listOf("sessionId", "providerId")
private val DB_KEYS = listOf("connectionId", "connectionName", "dbType", "database", "schemaName", "tableName", "objectName", "objectKind")

/** Per tab type, the metadata keys that say which thing the tab shows (`TAB_METADATA_ALLOW`). */
val TAB_METADATA_ALLOW: Map<String, List<String>> = mapOf(
    "chat" to SESSION_KEYS,
    "assistant" to SESSION_KEYS,
    "design" to listOf("designSlug") + SESSION_KEYS,
    "editor" to listOf("filePath", "lineNumber", "isUntitled", "language"),
    "conflict-editor" to listOf("filePath"),
    "git-diff" to listOf("filePath", "ref1", "ref2"),
    "session-review" to listOf("sessionId"),
    "terminal" to listOf("terminalIndex"),
    "database" to DB_KEYS,
    "db-structure" to DB_KEYS,
    "db-sql" to DB_KEYS,
    "db-query" to DB_KEYS,
    "db-impexp" to DB_KEYS,
    "db-connection" to DB_KEYS,
    "sqlite" to listOf("filePath", "tableName"),
    "web-preview" to listOf("url"),
    "extension" to listOf("viewType"),
    "extension-webview" to listOf("viewType"),
    "ai-resource" to listOf("resourceType", "name"),
    "android" to listOf("avdId"),
    "group" to listOf("groupId"),
    "settings" to listOf("category"),
)

private fun cut(text: String, max: Int) = if (text.length > max) text.take(max - 1) + "…" else text

/** The allow-listed, scalar metadata of a tab; null when nothing qualifies (`tabDetails`). */
fun assistantTabDetails(tab: WorkspaceTab): JsonObject? {
    val allowed = TAB_METADATA_ALLOW[tab.type] ?: return null
    val out = LinkedHashMap<String, JsonElement>()
    for (key in allowed) {
        val value = tab.metadata[key] as? JsonPrimitive ?: continue
        when {
            value.isString -> if (value.content.isNotEmpty()) out[key] = JsonPrimitive(cut(value.content, MAX_METADATA_VALUE_CHARS))
            value is JsonNull -> Unit
            value.content == "true" || value.content == "false" -> out[key] = JsonPrimitive(value.content == "true")
            value.content.toDoubleOrNull()?.isFinite() == true -> out[key] = value
        }
    }
    return if (out.isEmpty()) null else JsonObject(out)
}

/** The project a tab belongs to: its own, else the one its metadata names. */
fun assistantTabProject(tab: WorkspaceTab): String? =
    tab.projectId?.takeIf { it.isNotEmpty() } ?: (tab.metadata["projectName"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content

private class SnapPanel(val json: (keep: Int) -> JsonObject, val count: Int)

private fun panelJson(panel: WorkspacePanel, area: String, state: WorkspaceState, position: Pair<Int, Int>?): SnapPanel {
    // A floating window shows its tabs whichever project is current; a panel shows that project's.
    val tabs = if (area == "window") panel.tabs else visibleTabs(panel.tabs, state.currentProject)
    val active = panel.activeTabId?.takeIf { id -> tabs.any { it.id == id } }
    return SnapPanel(
        json = { keep ->
            buildJsonObject {
                put("id", panel.id)
                put("area", area)
                position?.let { (r, c) -> put("position", buildJsonObject { put("row", r); put("col", c) }) }
                put("focused", panel.id == state.focusedPanelId)
                put("activeTabId", active?.let(::JsonPrimitive) ?: JsonNull)
                put(
                    "tabs",
                    JsonArray(
                        tabs.take(keep).map { tab ->
                            buildJsonObject {
                                put("id", tab.id)
                                put("type", tab.type)
                                put("title", cut(tab.title, MAX_SNAPSHOT_TITLE_CHARS))
                                put("project", assistantTabProject(tab)?.let(::JsonPrimitive) ?: JsonNull)
                                put("active", tab.id == active)
                                assistantTabDetails(tab)?.let { put("details", it) }
                            }
                        },
                    ),
                )
                if (tabs.size > keep) put("omittedTabs", tabs.size - keep)
            }
        },
        count = tabs.size,
    )
}

private fun windowTitle(win: WindowRuntimeState, state: WorkspaceState): String {
    val hosted = state.panels[windowPanelId(win.id)]
    val tab = hosted?.tabs?.firstOrNull { it.id == hosted.activeTabId } ?: hosted?.tabs?.firstOrNull()
    val explicit = (win.payload?.get("title") as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content
    return cut(tab?.title?.takeIf { it.isNotEmpty() } ?: explicit ?: win.kind.wire, MAX_SNAPSHOT_TITLE_CHARS)
}

/** `buildUiStateSnapshot`: [windows] are the tablet's floating windows, none on a phone. */
fun buildUiStateSnapshot(state: WorkspaceState, windows: Map<String, WindowRuntimeState>, phone: Boolean): JsonObject {
    val panels = mutableListOf<SnapPanel>()
    state.grid.forEachIndexed { r, row ->
        row.forEachIndexed { c, id -> state.panels[id]?.let { panels += panelJson(it, "grid", state, r to c) } }
    }
    state.panels[DOCK_PANEL_ID]?.let { panels += panelJson(it, "dock", state, null) }
    val ordered = if (phone) emptyList() else windowsInRankOrder(windows)
    val frontId = ordered.lastOrNull { it.state != WindowVisualState.Minimized }?.id
    val windowJson = ordered.map { win ->
        val panelId = windowPanelId(win.id)
        val hosted = state.panels[panelId]
        if (hosted != null) panels += panelJson(hosted, "window", state, null)
        buildJsonObject {
            put("id", win.id)
            put("kind", win.kind.wire)
            put("title", windowTitle(win, state))
            put("state", win.state.wire)
            put("front", win.id == frontId)
            if (hosted != null) put("panelId", panelId)
        }
    }
    fun snapshot(keep: Int) = buildJsonObject {
        put("currentProject", state.currentProject?.let(::JsonPrimitive) ?: JsonNull)
        put("layout", if (phone) "phone" else "desktop")
        put("focusedPanelId", state.focusedPanelId)
        put("panels", JsonArray(panels.map { it.json(keep) }))
        put("dock", buildJsonObject { put("visible", state.dock.visible); put("expanded", state.dockExpanded) })
        put("windows", JsonArray(windowJson))
    }
    var keep = panels.maxOfOrNull { it.count } ?: 0
    var answer = snapshot(keep)
    while (answer.toString().length > MAX_SNAPSHOT_CHARS && keep > 0) {
        keep /= 2
        answer = snapshot(keep)
    }
    val omitted = panels.sumOf { (it.count - keep).coerceAtLeast(0) }
    if (omitted > 0) answer = JsonObject(answer + ("truncated" to JsonPrimitive("$omitted tabs left out to keep this answer small")))
    return answer
}

/**
 * Why closing [tab] would lose something, or null (`unsavedWorkReason`). [dirty] is whether its
 * open editor holds changes not saved yet. Database work is left to the tab's own close check.
 */
fun unsavedWorkReason(tab: WorkspaceTab, dirty: Boolean): String? {
    if (tab.type == "editor") {
        if (dirty) return "Its editor has changes that are not saved yet."
        val text = (tab.metadata["unsavedContent"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if ((tab.metadata["isUntitled"] as? JsonPrimitive)?.content == "true" && !text.isNullOrEmpty()) {
            return "It is an untitled file whose text has never been saved."
        }
    }
    if (tab.type == "terminal") return "Closing a terminal ends its shell and anything still running in it."
    return null
}
