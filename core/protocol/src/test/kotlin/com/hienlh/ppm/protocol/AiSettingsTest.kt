package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The AI settings as `/api/settings/ai` and its neighbours answer them, and the bodies the panel saves. */
class AiSettingsTest {
    private val settings = AiSettings.from(
        WireJson.parse(
            """
            {"default_provider":"claude","new_chat_provider_mode":"follow-focus","share_provider_context":false,
             "providers":{
               "mock":{"type":"mock"},
               "claude":{"type":"agent-sdk","model":"claude-opus-5-5","effort":"high","max_turns":1000,"max_budget_usd":2.5,
                         "thinking_budget_tokens":8000,"permission_mode":"plan","base_url":null,"api_key":"••••abcd",
                         "agent_teams":true,"inherit_claude_mcp":false,"system_prompt":"Be brief."},
               "codex":{"type":"cli","cli_command":"codex","model":"gpt-6-sol","model_context_window":400000}}}
            """,
        ),
    )!!

    @Test
    fun `the settings are read with each provider in the server's order, the mock one left off the tabs`() {
        assertEquals("claude", settings.defaultProvider)
        assertEquals("follow-focus", settings.newChatProviderMode)
        assertEquals(false, settings.shareProviderContext)
        assertEquals(listOf("claude", "codex"), settings.providerTabs)
        val claude = settings.providers.getValue("claude")
        assertEquals(1000, claude.maxTurns)
        assertEquals(2.5, claude.maxBudgetUsd!!, 0.0)
        assertEquals(8000L, claude.thinkingBudgetTokens)
        assertNull(claude.baseUrl)
        assertEquals("••••abcd", claude.apiKey)
        assertEquals(true, claude.agentTeams)
        assertNull(claude.context1m)
        assertEquals(false, claude.inheritClaudeMcp)
        assertEquals(400000L, settings.providers.getValue("codex").modelContextWindow)
        assertNull(settings.providers.getValue("codex").modelAutoCompactTokenLimit)
    }

    @Test
    fun `a new chat keeps only the provider part of what was saved`() {
        val preparation = settings.preparation
        assertEquals("codex", preparation.resolveProvider("codex"))
        assertEquals(mapOf("claude" to "plan"), preparation.permissionModes)
        assertNull(AiSettings.from(WireJson.parse("""{"default_provider":"claude"}""")))
    }

    @Test
    fun `Claude counts as the SDK provider by type, or by name when the server gives none`() {
        assertTrue(AiProviderSettings.isSdk("claude", null))
        assertTrue(AiProviderSettings.isSdk("claude", AiProviderSettings(type = "")))
        assertTrue(AiProviderSettings.isSdk("custom", AiProviderSettings(type = "agent-sdk")))
        assertFalse(AiProviderSettings.isSdk("codex", AiProviderSettings(type = "cli")))
        assertFalse(AiProviderSettings.isSdk("claude", AiProviderSettings(type = "cli")))
    }

    @Test
    fun `one change is one body, and an emptied field is sent as null so it clears`() {
        assertEquals("""{"new_chat_provider_mode":"default"}""", AiSettingsPatch.newChatProviderMode("default").toString())
        assertEquals("""{"default_provider":"codex"}""", AiSettingsPatch.defaultProvider("codex").toString())
        assertEquals("""{"share_provider_context":true}""", AiSettingsPatch.shareProviderContext(true).toString())
        assertEquals("""{"providers":{"claude":{"effort":"max"}}}""", AiSettingsPatch.field("claude", "effort", "max").toString())
        assertEquals("""{"providers":{"claude":{"base_url":null}}}""", AiSettingsPatch.field("claude", "base_url", null as String?).toString())
        assertEquals("""{"providers":{"claude":{"max_turns":0}}}""", AiSettingsPatch.field("claude", "max_turns", 0).toString())
        assertEquals("""{"providers":{"claude":{"agent_teams":false}}}""", AiSettingsPatch.field("claude", "agent_teams", false).toString())
        assertEquals(
            """{"providers":{"codex":{"model_context_window":272000,"model_auto_compact_token_limit":null}}}""",
            AiSettingsPatch.codexContext(272000, null).toString(),
        )
        assertEquals(JsonNull, AiSettingsPatch.field("codex", "model", null as Number?)["providers"]!!["codex"]!!["model"])
        assertEquals(JsonPrimitive(2.5), AiSettingsPatch.field("claude", "max_budget_usd", 2.5)["providers"]!!["claude"]!!["max_budget_usd"])
    }

    @Test
    fun `the masked key is never sent back as the key`() {
        assertNull(AiSettingsPatch.apiKey("claude", "••••abcd"))
        assertNull(AiSettingsPatch.apiKey("claude", "  ••••abcd "))
        assertEquals("""{"providers":{"claude":{"api_key":"sk-ant-x"}}}""", AiSettingsPatch.apiKey("claude", " sk-ant-x ").toString())
        assertEquals("""{"providers":{"claude":{"api_key":null}}}""", AiSettingsPatch.apiKey("claude", "  ").toString())
    }

    @Test
    fun `a provider chat cannot reach says why and when the server looks again`() {
        val down = ProviderProbeStatus.from(
            WireJson.parse(
                """{"id":"codex","registered":false,"reason":"codex exited with code 127","attempts":3,
                    "lastProbeAt":"2026-10-24T10:00:00.000Z","nextProbeAt":"2026-10-24T10:02:00.000Z"}""",
            ),
        )!!
        assertFalse(down.registered)
        assertEquals("codex exited with code 127", down.reason)
        assertEquals(Instant.parse("2026-10-24T10:02:00Z"), down.nextProbeAt)
        val up = ProviderProbeStatus.from(WireJson.parse("""{"id":"codex","registered":true,"attempts":1,"lastProbeAt":"2026-10-24T10:00:00Z"}"""))!!
        assertTrue(up.registered)
        assertNull(up.reason)
        assertNull(up.nextProbeAt)
    }

    @Test
    fun `an MCP server says where it was configured, in words`() {
        fun origin(json: String) = McpServerState.from(WireJson.parse(json))!!.originLabel
        assertEquals("claude.ai connector", origin("""{"name":"claude.ai Gmail","status":"needs-auth"}"""))
        assertEquals("claude.ai connector", origin("""{"name":"Gmail","status":"needs-auth","scope":"claudeai"}"""))
        assertEquals("Plugin", origin("""{"name":"figma","status":"needs-auth","source":"plugin","scope":"user"}"""))
        assertEquals("user", origin("""{"name":"linear","status":"needs-auth","scope":"user"}"""))
        assertNull(origin("""{"name":"ppm","status":"connected","scope":"dynamic"}"""))
        assertNull(origin("""{"name":"bare","status":"failed"}"""))
    }

    @Test
    fun `a team counts its members from the list, else from the count`() {
        val listed = TeamInfo.from(WireJson.parse("""{"name":"review","description":"Reviews","members":[{},{},{}],"createdAt":1761300000000}"""))!!
        assertEquals(3, listed.memberCount)
        assertEquals(Instant.ofEpochMilli(1761300000000), listed.createdAt)
        val counted = TeamInfo.from(WireJson.parse("""{"name":"squad","memberCount":2}"""))!!
        assertEquals(2, counted.memberCount)
        assertNull(counted.description)
        assertNull(counted.createdAt)
        assertEquals(0, TeamInfo.from(WireJson.parse("""{"name":"empty"}"""))!!.memberCount)
    }
}
