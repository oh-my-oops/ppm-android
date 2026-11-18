package com.hienlh.ppm.state.assistant

import com.hienlh.ppm.protocol.AssistantMcpServer
import com.hienlh.ppm.protocol.ManagerChatSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class AssistantSettingsFormsTest {
    private fun problem(check: McpServerCheck) = (check as McpServerCheck.Problem).error

    @Test
    fun `MCP names follow the server's rules`() {
        assertNull(assistantMcpNameError("github"))
        assertEquals("Name is required", assistantMcpNameError(""))
        assertEquals("Name cannot contain two underscores in a row", assistantMcpNameError("a__b"))
        assertEquals("Name must start with a letter or digit and use only letters, digits, - and _", assistantMcpNameError("-x"))
        assertEquals("\"PPM_Assistant_\" is the Assistant's own tool server", assistantMcpNameError("PPM_Assistant_"))
        assertEquals("\"ppm-sessions\" is PPM's session tool server", assistantMcpNameError("ppm-sessions"))
    }

    @Test
    fun `a new server needs a command or an http URL and a value for every variable`() {
        val stdio = McpServerForm(name = "gh", command = " npx ", args = "-y\n\n gh \n", pairs = listOf("TOKEN" to "abc", "" to "ignored"))
        val ok = checkMcpServer(null, stdio, emptySet(), emptySet()) as McpServerCheck.Ok
        assertEquals(AssistantMcpServer("", "gh", true, "stdio", command = "npx", args = listOf("-y", "gh"), pairs = mapOf("TOKEN" to "abc")), ok.server)
        assertEquals("MCP server \"gh\": command is required", problem(checkMcpServer(null, stdio.copy(command = ""), emptySet(), emptySet())))
        assertEquals("TOKEN needs a value", problem(checkMcpServer(null, stdio.copy(pairs = listOf("TOKEN" to "")), emptySet(), emptySet())))
        assertEquals("MCP server \"gh\": \"1X\" is not a valid variable name", problem(checkMcpServer(null, stdio.copy(pairs = listOf("1X" to "v")), emptySet(), emptySet())))
        assertEquals("Another server is already named \"GH\"", problem(checkMcpServer(null, stdio.copy(name = "GH"), emptySet(), setOf("gh"))))
        val http = McpServerForm(name = "docs", transport = "http", url = "ftp://x")
        assertEquals("MCP server \"docs\": URL must be an http:// or https:// address", problem(checkMcpServer(null, http, emptySet(), emptySet())))
        assertEquals("MCP server \"docs\": Auth cannot contain a line break", problem(checkMcpServer(null, http.copy(url = "https://a.test", pairs = listOf("Auth" to "a\nb")), emptySet(), emptySet())))
    }

    @Test
    fun `a saved value is kept while blank, but not once the URL moves to another origin`() {
        val saved = AssistantMcpServer("s2", "docs", false, "http", url = "https://mcp.example.com/mcp", pairs = mapOf("Authorization" to ""))
        val form = McpServerForm.of(saved)
        assertTrue(mcpKeepsSaved(saved, form))
        val kept = checkMcpServer(saved, form.copy(url = "https://mcp.example.com:443/other"), setOf("Authorization"), emptySet()) as McpServerCheck.Ok
        assertEquals("s2", kept.server.id)
        assertFalse(kept.server.enabled)
        val moved = form.copy(url = "https://evil.test/mcp")
        assertFalse(mcpKeepsSaved(saved, moved))
        assertEquals(
            "Authorization needs a value: the URL now points at a different server, so the saved value is not sent there",
            problem(checkMcpServer(saved, moved, setOf("Authorization"), emptySet())),
        )
        assertEquals("saved for the old URL — type it again", mcpValuePlaceholder(saved = true, keepsSaved = false))
        assertEquals("•••••• saved — blank keeps it", mcpValuePlaceholder(saved = true, keepsSaved = true))
        assertEquals("value", mcpValuePlaceholder(saved = false, keepsSaved = true))
        assertFalse(mcpKeepsSaved(saved, form.copy(transport = "stdio")))
    }

    @Test
    fun `small texts read as the web's`() {
        assertEquals("Be brief.\nlikes tea", appendToInstructions("Be brief.\n\n", "likes tea"))
        assertEquals("likes tea", appendToInstructions("  ", "likes tea"))
        assertEquals("", templateToStore(" # Rules\n", "# Rules"))
        assertEquals("# Mine", templateToStore("# Mine", "# Rules"))
        assertTrue(isClockTime("08:30"))
        assertFalse(isClockTime("8:30"))
        assertFalse(isClockTime("24:00"))
        assertEquals("Times are in the server's time zone until you save one of them here.", zoneLine(null, "Europe/London"))
        assertEquals("Times are in Asia/Ho_Chi_Minh (this device's zone when you saved).", zoneLine("Asia/Ho_Chi_Minh", "Asia/Ho_Chi_Minh"))
        assertEquals(
            "Times are in Asia/Ho_Chi_Minh (this device's zone when you saved). This device is on Europe/London; saving again switches to it.",
            zoneLine("Asia/Ho_Chi_Minh", "Europe/London"),
        )
        assertEquals("1,400 / 4,000 characters", notebookMeterLabel(1400, 4000))
        val nov4 = 1_762_214_400_000 // 2025-11-04T00:00:00Z
        assertEquals("You · 04/11", noteSourceLabel("user", nov4, ZoneOffset.UTC))
        assertEquals("Assistant", noteSourceLabel("assistant", 0, ZoneOffset.UTC))
        assertEquals("Untitled session · since 04/11", mainSessionLabel("", nov4, ZoneOffset.UTC))
        assertEquals("QL my app", defaultManagerTitle("my\napp"))
        assertEquals("Ask", managerModeLabel("default"))
        assertEquals("custom", managerModeLabel("custom"))
    }

    @Test
    fun `the manager table lists every project, then a manager whose project is gone`() {
        fun manager(project: String) = ManagerChatSummary(project, "/p/$project", "s-$project", "claude", null, "idle", "default", 0, false)
        val rows = managerTableRows(listOf("a", "__assistant__", "b"), listOf(manager("b"), manager("gone")))
        assertEquals(listOf("a", "b", "gone"), rows.map { it.projectName })
        assertNull(rows[0].manager)
        assertEquals("s-b", rows[1].manager?.sessionId)
        assertEquals(listOf(true, true, false), rows.map { it.registered })
    }
}
