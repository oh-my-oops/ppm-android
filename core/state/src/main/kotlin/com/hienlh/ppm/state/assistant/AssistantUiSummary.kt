package com.hienlh.ppm.state.assistant

import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.windows.WindowKind
import com.hienlh.ppm.state.windows.WindowsState
import com.hienlh.ppm.state.workspace.DOCK_PANEL_ID
import com.hienlh.ppm.state.workspace.WorkspaceState
import com.hienlh.ppm.state.workspace.windowPanelId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val MAX_TABS = 12
private const val MAX_TITLE_CHARS = 80
private const val MAX_WINDOWS = 10

private fun cut(text: String) = if (text.length > MAX_TITLE_CHARS) text.take(MAX_TITLE_CHARS - 1) + "…" else text

/**
 * The short picture of this device's screen each PPM Assistant message carries (`buildUiSummary`):
 * the project, every grid panel's tabs (the dock's while it shows) and the floating windows —
 * types and titles only. A phone has no windows and one panel in view.
 */
fun buildUiSummary(state: WorkspaceState?, windows: WindowsState?, phone: Boolean): JsonObject = buildJsonObject {
    put("project", state?.currentProject?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
    put("layout", if (phone) "phone" else "desktop")
    val panels = mutableListOf<JsonObject>()
    if (state != null) {
        val ids = state.gridPanelIds + if (state.dock.visible && !phone) listOf(DOCK_PANEL_ID) else emptyList()
        for (id in ids) {
            val panel = state.panels[id] ?: continue
            val kept = panel.tabs.take(MAX_TABS)
            panels += buildJsonObject {
                put("area", if (id == DOCK_PANEL_ID) "dock" else "grid")
                if (id == state.focusedPanelId) put("focused", true)
                put(
                    "tabs",
                    JsonArray(
                        kept.map { tab ->
                            buildJsonObject {
                                put("type", tab.type)
                                put("title", cut(tab.title))
                                if (tab.id == panel.activeTabId) put("active", true)
                            }
                        },
                    ),
                )
                if (panel.tabs.size > kept.size) put("more", panel.tabs.size - kept.size)
            }
        }
    }
    put("panels", JsonArray(panels))
    val shown = if (phone) emptyList() else windows?.windows?.values.orEmpty().sortedBy { it.opened }.take(MAX_WINDOWS)
    put(
        "windows",
        JsonArray(
            shown.map { w ->
                val hosted = if (w.kind == WindowKind.TabHost) state?.panels?.get(windowPanelId(w.id))?.activeTab?.title else null
                buildJsonObject {
                    put("kind", w.kind.wire)
                    put("title", cut(hosted ?: w.payload?.get("title").string ?: w.kind.wire))
                    put("state", w.state.wire)
                }
            },
        ),
    )
}
