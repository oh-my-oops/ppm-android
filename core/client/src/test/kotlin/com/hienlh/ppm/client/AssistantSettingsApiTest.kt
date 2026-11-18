package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.AssistantMcpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings → PPM Assistant's routes (`assistant-settings-api.ts`, `assistant-settings-client.ts`). */
class AssistantSettingsApiTest {
    private fun withServer(vararg responses: MockResponse, block: suspend (MockWebServer, ApiClient) -> Unit) = runBlocking {
        val server = MockWebServer()
        responses.forEach(server::enqueue)
        server.start()
        try {
            block(server, ApiClient(server.url("/"), "tok"))
        } finally {
            server.close()
        }
    }

    private fun ok(data: String) = MockResponse.Builder().body("""{"ok":true,"data":$data}""").build()

    private fun json(text: String?) = Json.parseToJsonElement(text!!).jsonObject

    private val row = """{"default_provider":"claude","providers":{"claude":{"model":"opus","effort":"xhigh"}},"instructions":"Be brief.",
        "persona":{"name":"Mi","address_forms":[{"user":"anh","self":"em","when":""}],"soul":"warm"},
        "mcp_servers":[{"id":"s1","name":"github","enabled":true,"transport":"stdio","command":"npx","args":["-y","gh"],"env":{"TOKEN":""}},
          {"id":"s2","name":"docs","enabled":false,"transport":"http","url":"https://mcp.example.com/mcp","headers":{"Authorization":""}}],
        "digest":{"enabled":true,"time":"08:30"},"quiet_hours":{"enabled":true,"start":"22:00","end":"07:00"},"timezone":"Asia/Ho_Chi_Minh",
        "manager_default_mode":"plan","manager_template":"","restart_news":false,"auto_readonly_commands":true,"act_on_own":false,
        "main_session":{"sessionId":"m","providerId":"claude"},"future_key":7}"""

    private val response = """{"settings":$row,"providers":[{"id":"claude","name":"Claude"},{"id":"codex","name":"Codex"}],
        "limits":{"instructionsMaxChars":8000,"maxServers":20},"managerTemplateBuiltIn":"# Rules"}"""

    @Test
    fun `the settings are read with every pane's fields, the MCP values blanked`() = withServer(ok(response)) { server, api ->
        val res = api.assistantSettings()
        assertEquals("/api/assistant/settings", server.takeRequest().target)
        val s = res.settings
        assertEquals("claude", s.general.defaultProvider)
        assertEquals("xhigh", s.general.providers["claude"]?.effort)
        assertEquals("Mi", s.general.persona.name)
        assertEquals("em", s.general.persona.addressForms.single().self)
        assertEquals(listOf("github", "docs"), s.general.mcpServers.map { it.name })
        assertEquals("npx -y gh", s.general.mcpServers[0].summary)
        assertEquals(mapOf("Authorization" to ""), s.general.mcpServers[1].pairs)
        assertEquals("08:30", s.digest.time)
        assertEquals("plan", s.managerDefaultMode)
        assertFalse(s.restartNews)
        assertTrue(s.autoReadonlyCommands)
        assertEquals(listOf("claude" to "Claude", "codex" to "Codex"), res.providers)
        assertEquals("# Rules", res.managerTemplateBuiltIn)
    }

    @Test
    fun `General's save leaves the other panes' keys out and keeps keys it does not know`() = withServer(ok(response), ok(response)) { server, api ->
        val loaded = api.assistantSettings().settings
        server.takeRequest()
        val draft = loaded.general.copy(
            instructions = "Answer in Vietnamese.",
            mcpServers = loaded.general.mcpServers + AssistantMcpServer("", "new", true, "http", url = "https://x.test/mcp", pairs = mapOf("X-Key" to "s3cret-value-1")),
        )
        api.saveAssistantGeneral(loaded.raw, draft)
        val sent = server.takeRequest()
        assertEquals("PUT", sent.method)
        val body = json(sent.body!!.utf8())
        for (key in listOf("digest", "quiet_hours", "timezone", "manager_default_mode", "manager_template", "restart_news", "auto_readonly_commands", "act_on_own")) {
            assertFalse(key, key in body)
        }
        assertEquals(JsonPrimitive(7), body["future_key"])
        assertEquals(JsonPrimitive("Answer in Vietnamese."), body["instructions"])
        assertEquals(Json.parseToJsonElement("""{"TOKEN":""}"""), (body["mcp_servers"] as kotlinx.serialization.json.JsonArray)[0].jsonObject["env"])
        assertEquals(Json.parseToJsonElement("""{"X-Key":"s3cret-value-1"}"""), (body["mcp_servers"] as kotlinx.serialization.json.JsonArray)[2].jsonObject["headers"])
        assertEquals("[REDACTED]", TraceRedactor.Global.redact("s3cret-value-1"))
    }

    @Test
    fun `a patch reads the row first and sends it back with only the named key changed`() = withServer(ok(response), ok(response)) { server, api ->
        api.patchAssistantSettings(buildJsonObject { put("act_on_own", true) })
        assertEquals("GET", server.takeRequest().method)
        val body = json(server.takeRequest().body!!.utf8())
        assertEquals(JsonPrimitive(true), body["act_on_own"])
        assertFalse("main_session" in body)
        assertFalse("digest" in body)
        assertEquals(JsonPrimitive("Be brief."), body["instructions"])
    }

    @Test
    fun `manager rules, a held change's decision, a new manager and a patch name the project`() = withServer(
        ok("""{"rules":{"approved":"a","accepted":"a\nb","proposed":"c","pendingHash":"h1","approvedAt":5,"addedSince":["b"],"addedSinceUnder":["Allowed"],"proposedDiff":{"added":["c"],"removed":["a"]}}}"""),
        ok("""{"rules":{"approved":"c","accepted":null,"proposed":null,"pendingHash":null,"approvedAt":6,"addedSince":[],"proposedDiff":null}}"""),
        ok("""{"sessionId":"new-1","providerId":"claude","permissionMode":"default"}"""),
        ok("{}"),
        ok("""{"rules":{"approved":"z","accepted":"z","proposed":null,"pendingHash":null,"approvedAt":7,"addedSince":[],"proposedDiff":null}}"""),
    ) { server, api ->
        val rules = api.managerRules("my app")
        assertEquals("/api/assistant/managers/my%20app/rules", server.takeRequest().target)
        assertEquals("a\nb", rules.inForce)
        assertEquals(listOf("Allowed"), rules.addedSinceUnder)
        assertEquals(listOf("c"), rules.proposedAdded)
        val after = api.decideManagerRules("my app", approve = false, hash = "h1")
        val decision = server.takeRequest()
        assertEquals("/api/assistant/managers/my%20app/rules/decision", decision.target)
        assertEquals(Json.parseToJsonElement("""{"action":"revert","hash":"h1"}"""), json(decision.body!!.utf8()))
        assertNull(after.proposedAdded)
        assertEquals("c", after.inForce)
        assertEquals("new-1", api.createManagerChat("my app"))
        assertEquals("POST /api/assistant/managers/my%20app/create", server.takeRequest().let { it.method + " " + it.target })
        api.patchManagerChat("my app", telegramDetail = "title")
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals(Json.parseToJsonElement("""{"telegramDetail":"title"}"""), json(patch.body!!.utf8()))
        assertEquals("z", api.putManagerRules("my app", "z").inForce)
        assertEquals(Json.parseToJsonElement("""{"text":"z"}"""), json(server.takeRequest().body!!.utf8()))
    }

    @Test
    fun `the notebook is listed, written, verified and deleted by id`() = withServer(
        ok("""{"notes":[{"id":3,"text":"Ship Friday","source":"assistant","unverified":true,"createdAt":1,"updatedAt":2}],"used":11,"cap":4000,"noteMax":300}"""),
        ok("""{"note":{}}"""), ok("""{"note":{}}"""), ok("""{"note":{}}"""), ok("{}"),
    ) { server, api ->
        val view = api.assistantNotes()
        server.takeRequest()
        assertEquals(11, view.used)
        assertTrue(view.notes.single().unverified)
        api.addAssistantNote("x")
        assertEquals("POST /api/assistant/notes", server.takeRequest().let { it.method + " " + it.target })
        api.editAssistantNote(3, "y")
        assertEquals(Json.parseToJsonElement("""{"text":"y"}"""), json(server.takeRequest().body!!.utf8()))
        api.verifyAssistantNote(3)
        assertEquals(Json.parseToJsonElement("""{"verified":true}"""), json(server.takeRequest().body!!.utf8()))
        api.deleteAssistantNote(3)
        assertEquals("DELETE /api/assistant/notes/3", server.takeRequest().let { it.method + " " + it.target })
    }

    @Test
    fun `Telegram binding and the old bot's memories`() = withServer(
        ok("""{"enabled":true,"running":true,"quietWhileInPpm":false,"chats":[{"chatId":"42","name":"Phone","sessionId":"s1","sessionTitle":null},{"chatId":"","name":"bad"}]}"""),
        ok("{}"),
        ok("""{"memories":[{"id":1,"project":"_global","category":"fact","content":"  likes tea ","createdAt":5},{"id":2,"content":"   "}]}"""),
    ) { server, api ->
        val telegram = api.assistantTelegram()
        server.takeRequest()
        assertEquals(listOf("42"), telegram.chats.map { it.chatId })
        assertEquals(listOf("42"), telegram.bindableChats.map { it.chatId })
        assertEquals(1, telegram.chatsOn("s1").size)
        api.bindAssistantTelegram("s2", "42")
        val bind = server.takeRequest()
        assertEquals("/api/assistant/telegram/bind", bind.target)
        assertEquals(Json.parseToJsonElement("""{"sessionId":"s2","chatId":"42"}"""), json(bind.body!!.utf8()))
        val memories = api.legacyMemories()
        assertEquals(listOf("likes tea"), memories.map { it.content })
    }

    @Test
    fun `utf8Bytes counts the way the server does`() {
        assertEquals(3, utf8Bytes("abc"))
        assertEquals(3, utf8Bytes("ệ"))
        assertEquals(JsonObject(emptyMap()), buildJsonObject { })
    }
}
