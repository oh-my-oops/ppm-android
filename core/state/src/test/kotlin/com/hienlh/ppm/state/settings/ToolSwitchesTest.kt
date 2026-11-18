package com.hienlh.ppm.state.settings

import com.hienlh.ppm.protocol.AiSettingsPatch
import com.hienlh.ppm.protocol.WireJson
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings → Tools: the switches the web added (`ppm-tools.ts`) and `session-collab-settings.ts`. */
class ToolSwitchesTest {
    private fun group(title: String) = PPM_TOOL_GROUPS.first { it.title == title }.tools.map { it.id }

    @Test
    fun `the groups list every tool the web shows, in its order`() {
        assertEquals(listOf("open_file", "open_preview", "open_url", "open_branch_review"), group("Tabs"))
        assertEquals(listOf("create_session", "send_to_session", "session_status", "design_screenshot"), group("Session collaboration"))
        assertEquals(
            listOf(
                "ppm_task_list", "ppm_task_get", "ppm_task_project", "ppm_task_create", "ppm_task_update", "ppm_task_close",
                "ppm_task_reorder", "ppm_task_undo", "ppm_task_project_update", "ppm_task_notify_manager",
            ),
            group("Tasks"),
        )
        assertEquals(VAULT_TOOLS, group("Vault"))
        assertEquals(
            listOf("Tabs", "Terminal", "Session history", "Database", "Session collaboration", "Schedules", "Tasks", "Vault", "Spec mode", "Memory"),
            PPM_TOOL_GROUPS.map { it.title },
        )
    }

    @Test
    fun `the vault's tools are off until turned on, and design screenshots follow the session tools' answer`() {
        assertFalse(isPpmToolEnabled(emptyMap(), "vault_read"))
        assertTrue(isPpmToolEnabled(mapOf("vault_read" to true), "vault_read"))
        assertTrue(isPpmToolEnabled(emptyMap(), "ppm_task_list"))
        assertFalse(isPpmToolEnabled(emptyMap(), "design_screenshot"))
        assertTrue(isPpmToolEnabled(mapOf("create_session" to true), "design_screenshot"))
        // Flipping one introduced tool keeps its siblings where they show.
        assertEquals(false, patchPpmToolSwitches(emptyMap(), "create_session", true)["design_screenshot"])
    }

    @Test
    fun `session collaboration settings resolve to defaults and clamp`() {
        assertEquals(SessionCollabSettings(), SessionCollabSettings.resolve(null))
        val stored = WireJson.parse("""{"askBeforeCreate":false,"maxDepth":9,"maxAutoMessagesPerPair":"x","maxRunningSpawned":2.5,"waitTimeoutMinutes":0}""")
        assertEquals(SessionCollabSettings(askBeforeCreate = false, maxDepth = 5, maxAutoMessagesPerPair = 20, maxRunningSpawned = 3, waitTimeoutMinutes = 1), SessionCollabSettings.resolve(stored))
        assertEquals(200, commitSessionCollabNumber("maxAutoMessagesPerPair", "500", 20))
        assertEquals(7, commitSessionCollabNumber("maxRunningSpawned", "abc", 7))
        assertEquals(7, commitSessionCollabNumber("maxRunningSpawned", " ", 7))
        assertEquals("""{"session_collab":{"maxDepth":4}}""", AiSettingsPatch.sessionCollab("maxDepth", JsonPrimitive(4)).toString())
    }
}
