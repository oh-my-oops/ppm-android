package com.hienlh.ppm.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Background commands and MCP sign-in on the wire (`PPMSelfTest/BackgroundShellTests.swift`, `McpSignInTests.swift`). */
class BackgroundAndMcpTest {
    private fun json(text: String) = WireJson.parse(text)

    private fun frame(text: String) = ChatFrame.from(json(text))

    @Test
    fun `the registry frame lists every command with an id, and the stop message names one`() {
        val registry = frame(
            """{"type":"background_registry","sessionId":"s","shells":[
              {"shellId":"b1","command":"bun dev","outputPath":"/tmp/b1.output","toolUseId":"t1","status":"running","startedAt":1760000000000},
              {"command":"no id"}, 7]}""",
        )
        val shell = BackgroundShell("b1", "bun dev", "/tmp/b1.output", "t1", "running", 1_760_000_000_000.0)
        assertEquals(ChatFrame.BackgroundRegistry(listOf(shell)), registry)
        assertEquals(ChatFrame.BackgroundRegistry(emptyList()), frame("""{"type":"background_registry","sessionId":"s"}"""))
        assertEquals(BackgroundShell("b2", "", "", status = "running"), BackgroundShell.from(json("""{"shellId":"b2"}""")))
        assertTrue(shell.copy(status = "stopping").isStopping)
        assertTrue(shell.copy(status = "stopped").isStopped)
        assertEquals(json("""{"type":"kill_background_shell","shellId":"b1"}"""), ChatClientMessage.KillBackgroundShell("b1").json)
    }

    @Test
    fun `the needs-sign-in list comes from mcp_status and every greeting`() {
        assertEquals(ChatFrame.McpStatus(listOf("linear", "plugin:x:notion")), frame("""{"type":"mcp_status","needsAuth":["linear","plugin:x:notion",3,null]}"""))
        assertEquals(ChatFrame.McpStatus(emptyList()), frame("""{"type":"mcp_status","needsAuth":"linear"}"""))
        assertEquals(ChatFrame.McpStatus(emptyList()), frame("""{"type":"mcp_status"}"""))
        val state = (frame("""{"type":"session_state","phase":"idle","mcpNeedsAuth":["a"]}""") as ChatFrame.SessionState).state
        assertEquals(listOf("a"), state.mcpNeedsAuth)
        // The greeting of a session no chat has opened yet leaves the list out: none, as on the web.
        val bare = (frame("""{"type":"session_state","phase":"idle"}""") as ChatFrame.SessionState).state
        assertEquals(emptyList<String>(), bare.mcpNeedsAuth)
    }

    @Test
    fun `a flow as the server reports it`() {
        val flow = McpAuthFlow.from(json("""{"id":"f","serverName":"linear","status":"waiting","authUrl":"https://a","callbackExpected":true,"redirectScheme":"custom"}"""))
        assertEquals(McpAuthFlow("f", "linear", McpAuthFlow.Status.WAITING, "https://a", true, McpAuthFlow.RedirectScheme.CUSTOM), flow)
        assertTrue(flow!!.isOpen)
        val settled = McpAuthFlow.from(json("""{"id":"f","serverName":"linear","status":"failed","callbackExpected":false,"authUrl":"","error":"Nope"}"""))
        assertFalse(settled!!.isOpen)
        assertNull(settled.authUrl)
        assertEquals("Nope", settled.error)
        assertTrue(McpAuthFlow.from(json("""{"id":"f","status":"completing"}"""))!!.isOpen)
        assertNull(McpAuthFlow.from(json("""{"id":"f","status":"weird"}""")))
        assertNull(McpAuthFlow.from(json("""{"status":"done"}""")))
    }

    @Test
    fun `server names, connectors and the hidden list`() {
        assertEquals("jira", McpServerName.short("plugin:atlassian:jira"))
        assertEquals("x", McpServerName.short("plugin:x"))
        assertEquals("plugin:x:", McpServerName.short("plugin:x:"))
        assertEquals("github:enterprise", McpServerName.short("github:enterprise"))
        assertTrue(McpServerName.isClaudeAiConnector("claude.ai Gmail"))
        assertFalse(McpServerName.isClaudeAiConnector("claude.ai"))
        assertTrue(McpServerName.isClaudeAiConnector("gmail", scope = "claudeai"))

        assertEquals(listOf("a", "b"), McpSignInDismissals.sanitized(json("""["a","",7,"b"]""")))
        assertNull(McpSignInDismissals.sanitized(json("""{"a":1}""")))
        assertNull(McpSignInDismissals.sanitized(null))
        // 201 UTF-16 units is one too many, as JavaScript counts a string's length.
        val long = "é".repeat(201)
        assertEquals(listOf("ok"), McpSignInDismissals.sanitized(json("""["$long","ok"]""")))
        assertEquals(listOf("a", "b", "c"), McpSignInDismissals.dismissing(listOf("b", "c"), listOf("a", "b")))
        val full = (0 until 200).map { "s$it" }
        val more = McpSignInDismissals.dismissing(listOf("new"), full)
        assertEquals(200, more.size)
        assertEquals("s1", more.first())
        assertEquals("new", more.last())
        assertEquals(listOf("a"), McpSignInDismissals.undismissing("b", listOf("a", "b")))
        assertNull(McpSignInDismissals.undismissing("z", listOf("a")))
    }
}
