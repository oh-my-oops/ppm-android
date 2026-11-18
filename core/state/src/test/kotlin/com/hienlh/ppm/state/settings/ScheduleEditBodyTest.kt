package com.hienlh.ppm.state.settings

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/** `requestBody(form, editing = true)` of `schedule-form-model.ts`, for the fields this app's form edits. */
class ScheduleEditBodyTest {
    @Test
    fun `an edited script schedule sends its script, never its kind`() {
        val row = ScheduleRowState(id = 3, name = "Old", kind = "script", mode = "tick", projectPath = "/p", timeoutMs = 60_000)
        val body = scheduleEditBody(row, name = "  Nightly ", projectPath = "/q", mode = "tick", cron = " 0 2 * * * ", prompt = "", script = "echo hi")
        assertEquals(
            buildJsonObject {
                put("name", "Nightly")
                put("project_path", "/q")
                put("mode", "tick")
                put("cron_expr", "0 2 * * *")
                put("timeout_ms", 60_000)
                put("script", "echo hi")
            },
            body,
        )
    }

    @Test
    fun `an always-on script sends no cron or timeout, a prompt schedule its prompt`() {
        val script = ScheduleRowState(id = 1, name = "d", kind = "script", mode = "always-on")
        val alwaysOn = scheduleEditBody(script, name = "d", projectPath = "/p", mode = "always-on", cron = "x", prompt = "", script = "loop")
        assertEquals(setOf("name", "project_path", "mode", "script"), alwaysOn.keys)

        val prompt = ScheduleRowState(id = 2, name = "p", kind = "prompt", timeoutMs = 900_000)
        val body = scheduleEditBody(prompt, name = "p", projectPath = "/p", mode = "tick", cron = "0 * * * *", prompt = "Review", script = "")
        assertEquals(JsonPrimitive("Review"), body["prompt"])
        assertEquals(setOf("name", "project_path", "mode", "cron_expr", "timeout_ms", "prompt"), body.keys)
    }

    @Test
    fun `an edited chat schedule keeps its chat and sends only what it has`() {
        val row = ScheduleRowState(id = 9, name = "s", kind = "session", targetSessionId = "abc")
        val body = scheduleEditBody(
            row, name = " Standup ", projectPath = "/p", mode = "tick", cron = "30 7 * * 1-5", prompt = "Status?", script = "",
            timezone = " ", recurring = false,
        )
        assertEquals(
            buildJsonObject {
                put("name", "Standup")
                put("prompt", "Status?")
                put("cron_expr", "30 7 * * 1-5")
                put("timezone", JsonNull)
                put("recurring", false)
            },
            body,
        )
        val zoned = scheduleEditBody(row, "s", "/p", "tick", "0 7 * * *", "x", "", timezone = "Asia/Ho_Chi_Minh", recurring = true)
        assertEquals(JsonPrimitive("Asia/Ho_Chi_Minh"), zoned["timezone"])
    }
}
