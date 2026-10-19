package com.hienlh.ppm.state.workspace

import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.double
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.math.abs
import kotlin.math.floor

internal val EMPTY_OBJECT = JsonObject(emptyMap())

/**
 * A tab as the web stores it in a project's layout (`Tab`, src/web/stores/tab-store.ts).
 *
 * The layout is one blob every device shares, so a tab type this app cannot show yet, a metadata
 * key it does not read and a field it has never heard of must all survive the app writing the
 * layout back: [metadata] is kept whole, and unknown fields ride along in [extra].
 */
data class WorkspaceTab(
    val id: String,
    val type: String,
    val title: String,
    val projectId: String? = null,
    val metadata: JsonObject = EMPTY_OBJECT,
    val closable: Boolean = true,
    val extra: JsonObject = EMPTY_OBJECT,
) {
    fun metaString(key: String): String? = metadata[key].string

    /** [changes] merged into the metadata, as `{ ...metadata, ...changes }`; a null value removes the key. */
    fun withMetadata(vararg changes: Pair<String, JsonElement?>): WorkspaceTab =
        copy(metadata = metadata.merged(changes.toMap()))

    fun toJson(): JsonObject = buildJsonObject {
        for ((key, value) in extra) put(key, value)
        put("id", JsonPrimitive(id))
        put("type", JsonPrimitive(type))
        put("title", JsonPrimitive(title))
        put("projectId", projectId?.let(::JsonPrimitive) ?: JsonNull)
        put("metadata", metadata)
        put("closable", JsonPrimitive(closable))
    }

    companion object {
        private val KNOWN = setOf("id", "type", "title", "projectId", "metadata", "closable")

        fun fromJson(json: JsonElement?): WorkspaceTab? {
            val obj = json as? JsonObject ?: return null
            return WorkspaceTab(
                id = obj["id"].string ?: return null,
                type = obj["type"].string ?: return null,
                title = obj["title"].string.orEmpty(),
                projectId = obj["projectId"].string,
                metadata = obj["metadata"] as? JsonObject ?: EMPTY_OBJECT,
                // Missing reads as the web reads `undefined`: not closable.
                closable = obj["closable"].bool ?: false,
                extra = JsonObject(obj.filterKeys { it !in KNOWN }),
            )
        }
    }
}

/** A tab before it has an id: what `openTab` takes. */
data class NewTab(
    val type: String,
    val title: String,
    val projectId: String? = null,
    val metadata: JsonObject = EMPTY_OBJECT,
    val closable: Boolean = true,
) {
    internal fun withId(id: String) = WorkspaceTab(id, type, title, projectId, metadata, closable)
}

/** One panel of the grid (or the dock, or a floating window's panel): its tabs and which is in front. */
data class WorkspacePanel(
    val id: String,
    val tabs: List<WorkspaceTab> = emptyList(),
    val activeTabId: String? = null,
    val tabHistory: List<String> = emptyList(),
    val extra: JsonObject = EMPTY_OBJECT,
) {
    fun tab(id: String): WorkspaceTab? = tabs.firstOrNull { it.id == id }

    val activeTab: WorkspaceTab? get() = activeTabId?.let(::tab)

    fun toJson(): JsonObject = buildJsonObject {
        for ((key, value) in extra) put(key, value)
        put("id", JsonPrimitive(id))
        put("tabs", JsonArray(tabs.map { it.toJson() }))
        put("activeTabId", activeTabId?.let(::JsonPrimitive) ?: JsonNull)
        put("tabHistory", JsonArray(tabHistory.map(::JsonPrimitive)))
    }

    companion object {
        private val KNOWN = setOf("id", "tabs", "activeTabId", "tabHistory")

        fun fromJson(json: JsonElement?, fallbackId: String? = null): WorkspacePanel? {
            val obj = json as? JsonObject ?: return null
            return WorkspacePanel(
                id = obj["id"].string ?: fallbackId ?: return null,
                tabs = obj["tabs"].array.orEmpty().mapNotNull(WorkspaceTab::fromJson),
                activeTabId = obj["activeTabId"].string,
                tabHistory = obj["tabHistory"].array.orEmpty().mapNotNull { it.string },
                extra = JsonObject(obj.filterKeys { it !in KNOWN }),
            )
        }
    }
}

/** The dock's visibility and height (a percentage of the viewport, 15 to 85). */
data class DockState(val visible: Boolean, val height: Double) {
    fun toJson(): JsonObject = buildJsonObject {
        put("visible", JsonPrimitive(visible))
        put("height", jsNumber(height))
    }

    companion object {
        fun fromJson(json: JsonElement?): DockState? {
            val obj = json as? JsonObject ?: return null
            return DockState(visible = obj["visible"].bool ?: false, height = obj["height"].double ?: 30.0)
        }
    }
}

/** A project's layout (`PanelLayout`, src/web/stores/panel-utils.ts): what the server keeps for every device. */
data class PanelLayout(
    val panels: Map<String, WorkspacePanel>,
    /** `grid[row][col]` = panel id. */
    val grid: List<List<String>>,
    val focusedPanelId: String,
    /** Missing in a blob written before the dock existed. */
    val dock: DockState? = null,
    val dockPanel: WorkspacePanel? = null,
    val extra: JsonObject = EMPTY_OBJECT,
) {
    fun toJson(): JsonObject = buildJsonObject {
        for ((key, value) in extra) put(key, value)
        put("panels", buildJsonObject { for ((id, panel) in panels) put(id, panel.toJson()) })
        put("grid", JsonArray(grid.map { row -> JsonArray(row.map(::JsonPrimitive)) }))
        put("focusedPanelId", JsonPrimitive(focusedPanelId))
        dock?.let { put("dock", it.toJson()) }
        dockPanel?.let { put("dockPanel", it.toJson()) }
    }

    companion object {
        /** `updatedAt` belongs to [SavedLayout], which the local copy is written as. */
        private val KNOWN = setOf("panels", "grid", "focusedPanelId", "dock", "dockPanel", "updatedAt")

        fun fromJson(json: JsonElement?): PanelLayout? {
            val obj = json as? JsonObject ?: return null
            val panelsJson = obj["panels"] as? JsonObject ?: return null
            val panels = LinkedHashMap<String, WorkspacePanel>()
            for ((id, panel) in panelsJson) WorkspacePanel.fromJson(panel, fallbackId = id)?.let { panels[id] = it }
            return PanelLayout(
                panels = panels,
                grid = obj["grid"].array?.map { row -> row.array.orEmpty().mapNotNull { it.string } } ?: return null,
                focusedPanelId = obj["focusedPanelId"].string.orEmpty(),
                dock = DockState.fromJson(obj["dock"]),
                dockPanel = WorkspacePanel.fromJson(obj["dockPanel"], fallbackId = DOCK_PANEL_ID),
                extra = JsonObject(obj.filterKeys { it !in KNOWN }),
            )
        }
    }
}

/** A layout with the time it was saved, ISO 8601 — the web's `{ ...layout, updatedAt }`. */
data class SavedLayout(val layout: PanelLayout, val updatedAt: String) {
    fun toJson(): JsonObject = JsonObject(layout.toJson() + ("updatedAt" to JsonPrimitive(updatedAt)))

    companion object {
        fun fromJson(json: JsonElement?): SavedLayout? {
            val layout = PanelLayout.fromJson(json) ?: return null
            return SavedLayout(layout, (json as JsonObject)["updatedAt"].string.orEmpty())
        }
    }
}

internal fun JsonObject.merged(changes: Map<String, JsonElement?>): JsonObject {
    if (changes.isEmpty()) return this
    val out = LinkedHashMap<String, JsonElement>(this)
    for ((key, value) in changes) if (value == null) out.remove(key) else out[key] = value
    return JsonObject(out)
}

/** A whole number goes out as one (`30`, not `30.0`), as JavaScript writes it. */
internal fun jsNumber(value: Double): JsonPrimitive =
    if (value == floor(value) && abs(value) < 9e15) JsonPrimitive(value.toLong()) else JsonPrimitive(value)
