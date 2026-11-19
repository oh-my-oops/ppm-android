package com.hienlh.ppm.chat

import com.hienlh.ppm.protocol.WireJson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every MCP tool PPM serves to a chat has a card here (`ppm-tool-card-coverage.test.ts`): a card of
 * its own, or the ordinary card with a readable name and a line read from its input and output. The
 * web reads each endpoint's `tools/list`; this app cannot, so the lists are the server's at the time
 * of the port (`session-server-tool-list.ts`, `tab-tools-mcp-tool-list.ts`, `dbToolDefinitions`,
 * `DESIGN_MCP_TOOL_DEFINITIONS`, a sample of `ASSISTANT_TOOL_DEFINITIONS`, which only need a name).
 */
class PpmToolCardCoverageTest {
    private class Server(val claude: String, val codex: String, val tools: List<String>, val namedOnly: Boolean = false)

    private val servers = listOf(
        Server(
            "ppm-sessions", "ppm_sessions",
            listOf(
                "create_session", "send_to_session", "session_status", "design_screenshot",
                "session_schedule_create", "session_schedule_list", "session_schedule_delete", "session_schedule_set_enabled", "session_schedule_propose_script",
                "report_to_assistant", "vault_list", "vault_read", "vault_propose",
                "ppm_task_list", "ppm_task_get", "ppm_task_project", "ppm_task_create", "ppm_task_update", "ppm_task_close",
                "ppm_task_reorder", "ppm_task_undo", "ppm_task_project_update", "ppm_task_notify_manager",
            ),
        ),
        Server(
            "ppm-tabs", "ppm_tabs",
            listOf("open_file", "open_preview", "open_url", "open_branch_review", "read_terminal", "run_in_terminal", "search_sessions", "list_session_turns", "read_session_turns"),
        ),
        Server("ppm-db", "ppm_db", listOf("db_query", "open_query", "db_execute")),
        Server("ppm-design", "ppm_design", listOf("design_check")),
        Server("ppm-assistant", "ppm_assistant", listOf("git_status", "chats_attention", "chat_answer_approval", "ppm_cli_reference", "projects_list"), namedOnly = true),
    )

    /** An input every own card reads, so a tool with one is routed to it. */
    private val anyInput = WireJson.parse("""{"path":"a","title":"t","task":"x","session_id":"s","message":"m","session_ids":["s"]}""") as JsonObject

    /** A card of its own: routed before the ordinary card in `ToolCardView`. */
    private fun ownCard(name: String): Boolean =
        sessionCollabToolCall(name, anyInput) != null || tabToolCall(name, anyInput) != null || deviceToolOf(name) != null ||
            vaultToolOf(name) != null || dbToolOf(name) != null

    /** What is wrong with a tool's card, or null when it has one. */
    private fun missingCard(name: String, tool: String, namedOnly: Boolean): String? {
        if (ownCard(name)) return null
        if (ppmToolName(name) == null) return "$name is not read as a PPM tool name (PpmToolSummaries.kt SERVERS)"
        val summary = ppmToolSummary(name, JsonObject(emptyMap()), null, false)
        if (summary == null || summary.label.isBlank() || Regex("__|:|_").containsMatchIn(summary.label) || summary.label == tool) {
            return "$name shows no readable name (got ${summary?.label})"
        }
        if (namedOnly || tool in PPM_TOOLS_NAMED_ONLY || ppmToolHasOwnLine(tool)) return null
        return "$name shows only its humanized name \"${summary.label}\", with no line read from its input and output"
    }

    @Test
    fun `every PPM tool has a card, under Claude's name and Codex's`() {
        val problems = servers.flatMap { server ->
            server.tools.flatMap { tool ->
                listOf("mcp__${server.claude}__$tool", "${server.codex}:$tool").mapNotNull { missingCard(it, tool, server.namedOnly) }
            }
        }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `lists as named only tools some endpoint still serves`() {
        val served = servers.flatMap { it.tools }.toSet()
        assertEquals(emptyList<String>(), PPM_TOOLS_NAMED_ONLY.keys.filter { it !in served })
    }

    @Test
    fun `fails for a new tool that has no card`() {
        assertTrue(missingCard("mcp__ppm-sessions__ppm_task_archive", "ppm_task_archive", false)!!.contains("no line read from its input and output"))
        assertTrue(missingCard("ppm_tabs:session_export", "session_export", false)!!.contains("no line read"))
        assertNull(missingCard("mcp__ppm-assistant__git_rebase", "git_rebase", true))
    }
}
