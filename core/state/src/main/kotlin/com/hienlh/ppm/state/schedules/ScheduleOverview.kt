package com.hienlh.ppm.state.schedules

import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.long
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonElement

/** The newest run of a schedule that did something (`ScheduleLastRun`). */
data class ScheduleLastRun(
    val id: Int,
    /** `running` | `done` | `error` | `skipped`. */
    val status: String,
    val startedAt: String,
    val endedAt: String? = null,
    val error: String? = null,
    val sessionId: String? = null,
)

/** The task a reminder belongs to (`ScheduleTaskLink`): `ref` is `ppm/12`. */
data class ScheduleTaskLink(
    val id: String,
    val ref: String,
    val title: String,
    val project: String,
    val closed: Boolean,
) {
    /** The number in [ref] (`ppm/12` → 12). */
    val number: Int? get() = ref.substringAfterLast('/').toIntOrNull()
}

/**
 * One schedule as the Schedules window lists it (`ScheduleOverview`, `GET /api/schedules`): the
 * view plus its newest run, its project, the permission mode its turns really run in, the next
 * fires the host computed and the task a reminder belongs to.
 */
data class ScheduleOverview(
    val id: Int,
    val name: String,
    /** `prompt` | `script` | `session`. */
    val kind: String = "prompt",
    /** `tick` | `always-on`. */
    val mode: String = "tick",
    val cronExpr: String = "",
    val providerId: String = "claude",
    val projectPath: String = "",
    val prompt: String = "",
    val script: String? = null,
    val permissionMode: String = "default",
    val timeoutMs: Long = 900_000,
    val enabled: Boolean = true,
    val sessionId: String? = null,
    val nextFireAt: String? = null,
    val updatedAt: String? = null,
    val targetSessionId: String? = null,
    val targetTitle: String? = null,
    val timezone: String? = null,
    val recurring: Boolean = true,
    val pendingSince: String? = null,
    val pausedReason: String? = null,
    val attentionDismissed: String? = null,
    /** The script process's state (`status.state`): `idle`, `running`, `failed`… */
    val runState: String = "idle",
    val lastRun: ScheduleLastRun? = null,
    val projectName: String? = null,
    val effectivePermissionMode: String = "default",
    val nextFires: List<String> = emptyList(),
    val task: ScheduleTaskLink? = null,
)

/** SQLite's 0/1 or a JSON boolean. */
private fun flag(e: JsonElement?, default: Boolean): Boolean = e.bool ?: e.int?.let { it != 0 } ?: default

fun parseScheduleOverview(e: JsonElement?): ScheduleOverview? {
    val o = e.obj ?: return null
    val id = o["id"].int ?: return null
    val run = o["last_run"]?.takeIf { it.obj != null }?.let { r ->
        val runId = r["id"].int ?: return@let null
        ScheduleLastRun(
            id = runId,
            status = r["status"].string ?: "done",
            startedAt = r["started_at"].string ?: "",
            endedAt = r["ended_at"].string,
            error = r["error"].string,
            sessionId = r["session_id"].string,
        )
    }
    val task = o["task"]?.takeIf { it.obj != null }?.let { t ->
        val tid = t["id"].string ?: return@let null
        ScheduleTaskLink(tid, t["ref"].string ?: "", t["title"].string ?: "", t["project"].string ?: "", t["closed"].bool ?: false)
    }
    return ScheduleOverview(
        id = id,
        name = o["name"].string ?: "Schedule #$id",
        kind = o["kind"].string ?: "prompt",
        mode = o["mode"].string ?: "tick",
        cronExpr = o["cron_expr"].string ?: "",
        providerId = o["provider_id"].string ?: "claude",
        projectPath = o["project_path"].string ?: "",
        prompt = o["prompt"].string ?: "",
        script = o["script"].string,
        permissionMode = o["permission_mode"].string ?: "default",
        timeoutMs = o["timeout_ms"].long ?: 900_000,
        enabled = flag(o["enabled"], true),
        sessionId = o["session_id"].string,
        nextFireAt = o["next_fire_at"].string,
        updatedAt = o["updated_at"].string,
        targetSessionId = o["target_session_id"].string,
        targetTitle = o["target_title"].string,
        timezone = o["timezone"].string,
        recurring = flag(o["recurring"], true),
        pendingSince = o["pending_since"].string,
        pausedReason = o["paused_reason"].string,
        attentionDismissed = o["attention_dismissed"].string,
        runState = o["status"]["state"].string ?: "idle",
        lastRun = run,
        projectName = o["project_name"].string,
        effectivePermissionMode = o["effective_permission_mode"].string ?: o["permission_mode"].string ?: "default",
        nextFires = o["next_fires"].array.orEmpty().mapNotNull { it.string },
        task = task,
    )
}

/** The list `GET /api/schedules` answers (an array, or `{ schedules }` from an older server). */
fun parseScheduleOverviews(e: JsonElement?): List<ScheduleOverview> =
    (e.array ?: e["schedules"].array).orEmpty().mapNotNull(::parseScheduleOverview)
