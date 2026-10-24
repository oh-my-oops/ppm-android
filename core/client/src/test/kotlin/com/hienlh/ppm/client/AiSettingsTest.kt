package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.AiSettingsPatch
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

/** The requests the AI settings panel makes (ai-settings-section.tsx, mcp-sign-in-list.tsx). */
class AiSettingsTest {
    private fun <T> withServer(vararg responses: MockResponse, block: suspend (MockWebServer, ApiClient) -> T): T = runBlocking {
        val server = MockWebServer()
        responses.forEach(server::enqueue)
        server.start()
        try {
            block(server, ApiClient(server.url("/"), "t"))
        } finally {
            server.close()
        }
    }

    private fun ok(data: String) = MockResponse.Builder().body("""{"ok":true,"data":$data}""").build()

    private val saved = """{"default_provider":"claude","new_chat_provider_mode":"default","providers":{"claude":{"effort":"max"}}}"""

    @Test
    fun `settings are read and saved one change at a time`() = withServer(ok(saved), ok(saved)) { server, api ->
        assertEquals("default", api.aiSettings().newChatProviderMode)
        assertEquals("GET /api/settings/ai", server.takeRequest().let { "${it.method} ${it.target}" })
        val updated = api.updateAiSettings(AiSettingsPatch.field("claude", "effort", "max"))
        assertEquals("max", updated.providers.getValue("claude").effort)
        val put = server.takeRequest()
        assertEquals("PUT /api/settings/ai", "${put.method} ${put.target}")
        assertEquals("""{"providers":{"claude":{"effort":"max"}}}""", put.body?.utf8())
    }

    @Test
    fun `provider status, a probe and a provider's models`() = withServer(
        ok("""[{"id":"codex","registered":false,"reason":"not found","attempts":2,"lastProbeAt":"2026-10-24T10:00:00Z"}]"""),
        ok("""{"id":"codex","registered":true,"attempts":3,"lastProbeAt":"2026-10-24T10:01:00Z"}"""),
        ok("""[{"value":"gpt-6-sol","label":"GPT-6 Sol"},{"value":"bare"},{"label":"no value"}]"""),
    ) { server, api ->
        assertEquals("not found", api.providerStatuses().single().reason)
        assertEquals("GET /api/settings/ai/providers/status", server.takeRequest().let { "${it.method} ${it.target}" })
        assertEquals(true, api.probeProvider("codex").registered)
        assertEquals("POST /api/settings/ai/providers/codex/probe", server.takeRequest().let { "${it.method} ${it.target}" })
        assertEquals(listOf("GPT-6 Sol", "bare"), api.settingsModels("codex").map { it.label })
        assertEquals("GET /api/settings/ai/providers/codex/models", server.takeRequest().let { "${it.method} ${it.target}" })
    }

    @Test
    fun `teams are listed and deleted by name`() = withServer(
        ok("""[{"name":"release review","members":[{}]}]"""),
        ok("""{"deleted":"release review"}"""),
    ) { server, api ->
        assertEquals(listOf("release review"), api.teams().map { it.name })
        server.takeRequest()
        api.deleteTeam("release review")
        assertEquals("DELETE /api/teams/release%20review", server.takeRequest().let { "${it.method} ${it.target}" })
    }

    @Test
    fun `MCP status asks for the home directory's servers, fresh only when told`() = withServer(
        ok("""[{"name":"linear","status":"needs-auth","scope":"user"}]"""),
        ok("""[]"""),
    ) { server, api ->
        assertEquals("linear", api.mcpAuthStatus(null, fresh = false).single().name)
        assertEquals("/api/mcp-auth/status", server.takeRequest().target)
        api.mcpAuthStatus("my app", fresh = true)
        assertEquals("/api/mcp-auth/status?project=my%20app&fresh=1", server.takeRequest().target)
    }
}
