package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement

/**
 * Background work a chat's Claude CLI is running right now — a shell, an agent, a monitor, a
 * workflow — as the server forwards the CLI's `background_tasks_changed` (`background_tasks`, a
 * level signal: each list replaces the last). Shown in the activity chip's Tasks tab, each with
 * its own Stop (`src/shared/background-tasks.ts`).
 */
data class LiveBackgroundTask(
    /** The SDK's task id: what `stop_background_task` names, and a background shell's `shellId`. */
    val taskId: String,
    /** `local_bash`, `local_agent`, `monitor`, `workflow`, … — passed through, never assumed closed. */
    val taskType: String = "task",
    val description: String = "",
    val subagentType: String? = null,
    val parentTaskId: String? = null,
) {
    /** A word for the kind of task, for its row (`taskKindLabel`). */
    val kindLabel: String
        get() = when (taskType) {
            "local_bash" -> "Command"
            "local_agent" -> if (subagentType != null && subagentType != "general-purpose") "Agent · $subagentType" else "Agent"
            "monitor", "local_monitor" -> "Monitor"
            "workflow", "local_workflow" -> "Workflow"
            else -> "Task"
        }

    companion object {
        fun from(json: JsonElement?): LiveBackgroundTask? {
            val id = json["taskId"].string?.takeIf { it.isNotEmpty() } ?: return null
            return LiveBackgroundTask(
                taskId = id,
                taskType = json["taskType"].string ?: "task",
                description = json["description"].string.orEmpty(),
                subagentType = json["subagentType"].string,
                parentTaskId = json["parentTaskId"].string,
            )
        }

        /** Anything but an array is none (`Array.isArray(ev.tasks) ? ev.tasks : []`). */
        fun list(json: JsonElement?): List<LiveBackgroundTask> = json.array.orEmpty().mapNotNull(::from)
    }
}
