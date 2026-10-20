package com.hienlh.ppm.state.windows

import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.double
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.workspace.jsNumber
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** A window as it is saved: window-persistence.ts's `PersistedWindow`. */
data class PersistedWindow(
    val id: String,
    val kind: WindowKind,
    val rect: Rect,
    /** Absent in a blob written before the dock existed. */
    val opened: Double?,
    val state: WindowVisualState,
    val restoreTo: WindowVisualState?,
    val payload: JsonObject?,
)

/**
 * Kinds that come back after a restart: all but an agent session, whose live transcript is gone
 * by then — it would come back as an empty window nobody asked for.
 */
private val RESTORABLE_KINDS = WindowKind.entries - WindowKind.AgentSession

private val SHOWN_STATES = listOf(WindowVisualState.Normal, WindowVisualState.Maximized, WindowVisualState.Snapped)

/** The windows as `ppm-windows` holds them, back to front so the stack comes back as it was; null for none. */
fun saveWindowRects(windows: Collection<WindowRuntimeState>): String? {
    if (windows.isEmpty()) return null
    return JsonArray(windows.sortedBy { it.rank }.map { win ->
        buildJsonObject {
            put("id", JsonPrimitive(win.id))
            put("kind", JsonPrimitive(win.kind.wire))
            put("rect", buildJsonObject {
                put("x", jsNumber(win.rect.x))
                put("y", jsNumber(win.rect.y))
                put("w", jsNumber(win.rect.w))
                put("h", jsNumber(win.rect.h))
            })
            put("opened", jsNumber(win.opened))
            put("state", JsonPrimitive(win.state.wire))
            win.restoreTo?.let { put("restoreTo", JsonPrimitive(it.wire)) }
            win.payload?.let { put("payload", it) }
        }
    }).toString()
}

/** Reads saved windows, dropping anything malformed, each re-clamped to the layer as it is now. */
fun loadWindowRects(text: String?, bounds: Bounds): List<PersistedWindow> {
    val parsed = text?.let(WireJson::parseOrNull) as? JsonArray ?: return emptyList()
    val out = ArrayList<PersistedWindow>()
    for (item in parsed) {
        val w = item as? JsonObject ?: continue
        val id = w["id"].string ?: continue
        val kind = WindowKind.fromWire(w["kind"].string)?.takeIf { it in RESTORABLE_KINDS } ?: continue
        val rect = readRect(w["rect"]) ?: continue
        // `JSON.stringify` drops an undefined payload; anything there must be an object.
        val payload = when (val p = w["payload"]) {
            null -> null
            is JsonObject -> p
            else -> continue
        }
        val state = WindowVisualState.fromWire(w["state"].string) ?: WindowVisualState.Normal
        val restoreTo = WindowVisualState.fromWire(w["restoreTo"].string)?.takeIf { it in SHOWN_STATES }
        out += PersistedWindow(
            id = id,
            kind = kind,
            rect = clampRect(rect, bounds),
            opened = w["opened"].double?.takeIf { it.isFinite() },
            state = state,
            restoreTo = restoreTo.takeIf { state == WindowVisualState.Minimized },
            payload = payload,
        )
    }
    return out
}

private fun readRect(value: JsonElement?): Rect? {
    val r = value as? JsonObject ?: return null
    val nums = listOf("x", "y", "w", "h").map { key -> r[key].double?.takeIf { it.isFinite() } ?: return null }
    return Rect(nums[0], nums[1], nums[2], nums[3])
}
