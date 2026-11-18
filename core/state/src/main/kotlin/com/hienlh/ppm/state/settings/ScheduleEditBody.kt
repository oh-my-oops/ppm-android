package com.hienlh.ppm.state.settings

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The `PATCH /api/schedules/:id` body of an edited [row] (`requestBody(form, editing = true)` in
 * `schedule-form-model.ts`), for the fields this app's form edits; whatever it leaves out stays
 * as it was. A schedule's kind never changes, nor a chat schedule's chat (and so its project).
 */
fun scheduleEditBody(
    row: ScheduleRowState,
    name: String,
    projectPath: String,
    mode: String,
    cron: String,
    prompt: String,
    script: String,
    timezone: String = "",
    recurring: Boolean = true,
): JsonObject = buildJsonObject {
    put("name", name.trim())
    if (row.isSession) {
        put("prompt", prompt)
        put("cron_expr", cron.trim())
        put("timezone", timezone.trim().ifEmpty { null }?.let(::JsonPrimitive) ?: JsonNull)
        put("recurring", recurring)
        return@buildJsonObject
    }
    put("project_path", projectPath)
    put("mode", mode)
    if (mode == "tick") {
        put("cron_expr", cron.trim())
        put("timeout_ms", row.timeoutMs)
    }
    if (row.kind == "prompt") put("prompt", prompt) else put("script", script)
}
