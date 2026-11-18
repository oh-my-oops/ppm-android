package com.hienlh.ppm.state.assistant

import com.hienlh.ppm.state.windows.Bounds
import com.hienlh.ppm.state.windows.Rect
import com.hienlh.ppm.state.windows.clampRect
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The Assistant's one tab id (`deriveTabId("assistant")`) and its title. */
const val ASSISTANT_TAB_ID = "assistant"
const val ASSISTANT_TAB_TITLE = "PPM Assistant"

/** What an Assistant chat always runs with (`ASSISTANT_PERMISSION`): the server asks before every change regardless. */
const val ASSISTANT_PERMISSION_MODE = "default"

/** The provider a manager chat runs on (`MANAGER_PROVIDER_ID`). */
const val MANAGER_PROVIDER_ID = "claude"

/** A manager chat's window (`MANAGER_WINDOW_SIZE`). */
const val MANAGER_WINDOW_W = 520.0
const val MANAGER_WINDOW_H = 640.0

/** How long the one-time "Switched to…" note stays (`NOTE_MS`). */
const val SWITCHED_NOTE_MS = 8000L

/** A manager chat's tab title (`managerChatTitle`). */
fun managerChatTitle(projectName: String): String = "$projectName · manager"

/** The note shown once after a phone switched project for a manager chat (`switchedNote`). */
fun switchedNote(projectName: String): String = "Switched to $projectName. Tap ‹ Assistant to go back."

/** A portrait window, top-right, so the code on the left stays readable (`assistantSpawnRect`). */
fun assistantSpawnRect(bounds: Bounds): Rect {
    val w = minOf(470.0, bounds.w - 16 * 2)
    val h = minOf(620.0, bounds.h - 16 * 2)
    return clampRect(Rect(bounds.w - 28 - w, 16.0, w, h), bounds)
}

/** Where a manager chat a phone opened from the Assistant goes back to (`AssistantReturn`). */
data class AssistantReturn(
    /** The project that was active when the chip was tapped; null when it was the same one. */
    val project: String?,
)

/** The return a phone-opened manager chat carries in its tab's metadata, or null (`assistantReturnOf`). */
fun assistantReturnOf(metadata: JsonObject?): AssistantReturn? {
    val r = metadata?.get("returnTo") as? JsonObject ?: return null
    if ((r["kind"] as? JsonPrimitive)?.content != "assistant") return null
    val project = (r["project"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content
    return AssistantReturn(project)
}

/** The epoch after the one [metadata] carries (`nextAssistantChatEpoch`). */
fun nextAssistantChatEpoch(metadata: JsonObject?): Int =
    ((metadata?.get("assistantChatEpoch") as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: 0) + 1
