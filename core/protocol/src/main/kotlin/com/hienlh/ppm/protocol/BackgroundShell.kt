package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement

/**
 * A command the AI left running in the background (Bash with `run_in_background`), as the server
 * tracks it for a session (`BackgroundShell`). Its output goes to a file the server can read back.
 */
data class BackgroundShell(
    /** The SDK's task id: also the `.output` file's name. */
    val shellId: String,
    val command: String,
    /** The `.output` file, absolute. */
    val outputPath: String,
    /** The Bash call that started it. */
    val toolUseId: String = "",
    /** `running`, `stopping` (asked to stop, the AI not done yet) or `stopped`. */
    val status: String = "running",
    /** Epoch milliseconds. */
    val startedAt: Double = 0.0,
) {
    val isRunning: Boolean get() = status == "running"
    val isStopping: Boolean get() = status == "stopping"
    val isStopped: Boolean get() = status == "stopped"

    companion object {
        fun from(json: JsonElement?): BackgroundShell? {
            val shellId = json["shellId"].string ?: return null
            return BackgroundShell(
                shellId = shellId,
                command = json["command"].string.orEmpty(),
                outputPath = json["outputPath"].string.orEmpty(),
                toolUseId = json["toolUseId"].string.orEmpty(),
                status = json["status"].string ?: "running",
                startedAt = json["startedAt"].double ?: 0.0,
            )
        }
    }
}
