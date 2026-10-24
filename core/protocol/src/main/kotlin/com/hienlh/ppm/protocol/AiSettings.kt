package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * `GET|PUT /api/settings/ai` (api-settings.ts `AISettings`): which provider a new chat takes,
 * whether providers share rules and memory, and each provider's own settings, in the order the
 * server keeps them.
 */
data class AiSettings(
    val defaultProvider: String,
    /** `default` or `follow-focus`; null from a server that never wrote one. */
    val newChatProviderMode: String?,
    val shareProviderContext: Boolean?,
    val providers: Map<String, AiProviderSettings>,
) {
    /** `providerTabs`: every configured provider but the mock one, in the server's order. */
    val providerTabs: List<String>
        get() = providers.keys.filter { it != "mock" }

    /** What a new chat resolves its provider and mode from, kept on this device once saved (`writeChatPreparationSettings`). */
    val preparation: ChatPreparationSettings
        get() = ChatPreparationSettings(defaultProvider, newChatProviderMode, providers.mapNotNull { (id, p) -> p.permissionMode?.let { id to it } }.toMap())

    companion object {
        fun from(value: JsonElement?): AiSettings? {
            val providers = value["providers"].obj ?: return null
            return AiSettings(
                defaultProvider = value["default_provider"].string.orEmpty(),
                newChatProviderMode = value["new_chat_provider_mode"].string,
                shareProviderContext = value["share_provider_context"].bool,
                providers = providers.mapValues { (_, provider) -> AiProviderSettings.from(provider) },
            )
        }
    }
}

/** One provider's entry (`AIProviderSettings`). Anything the server did not send, or sent as null, is null. */
data class AiProviderSettings(
    val type: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val maxTurns: Int? = null,
    val maxBudgetUsd: Double? = null,
    val thinkingBudgetTokens: Long? = null,
    val permissionMode: String? = null,
    val systemPrompt: String? = null,
    val baseUrl: String? = null,
    /** As the server masks it: `••••` and the key's last four characters. */
    val apiKey: String? = null,
    val agentTeams: Boolean? = null,
    val context1m: Boolean? = null,
    val inheritClaudeMcp: Boolean? = null,
    val modelContextWindow: Long? = null,
    val modelAutoCompactTokenLimit: Long? = null,
) {
    companion object {
        fun from(value: JsonElement): AiProviderSettings = AiProviderSettings(
            type = value["type"].string,
            model = value["model"].string,
            effort = value["effort"].string,
            maxTurns = value["max_turns"].int,
            maxBudgetUsd = value["max_budget_usd"].double,
            thinkingBudgetTokens = value["thinking_budget_tokens"].long,
            permissionMode = value["permission_mode"].string,
            systemPrompt = value["system_prompt"].string,
            baseUrl = value["base_url"].string,
            apiKey = value["api_key"].string,
            agentTeams = value["agent_teams"].bool,
            context1m = value["context_1m"].bool,
            inheritClaudeMcp = value["inherit_claude_mcp"].bool,
            modelContextWindow = value["model_context_window"].long,
            modelAutoCompactTokenLimit = value["model_auto_compact_token_limit"].long,
        )

        /** `isSdkProvider`: an Agent SDK provider by type, or Claude when the server names no type. */
        fun isSdk(providerId: String, settings: AiProviderSettings?): Boolean =
            settings?.type == "agent-sdk" || (settings?.type.isNullOrEmpty() && providerId == "claude")

        /** A key shown as the server masked it (`••••1234`), which must never be sent back as the key. */
        fun isMaskedKey(text: String): Boolean = text.startsWith("••••")
    }
}

/**
 * `PUT /api/settings/ai` bodies, one change each, as the settings panel saves them. A provider
 * field set to null is cleared: the web sends `undefined` there, which JSON drops, so emptying a
 * field never reached the server and the old value came back on the next read.
 */
object AiSettingsPatch {
    fun newChatProviderMode(mode: String): JsonObject = buildJsonObject { put("new_chat_provider_mode", mode) }

    fun defaultProvider(providerId: String): JsonObject = buildJsonObject { put("default_provider", providerId) }

    fun shareProviderContext(on: Boolean): JsonObject = buildJsonObject { put("share_provider_context", on) }

    fun provider(providerId: String, fields: Map<String, JsonElement>): JsonObject =
        buildJsonObject { put("providers", buildJsonObject { put(providerId, JsonObject(fields)) }) }

    fun field(providerId: String, field: String, value: JsonElement): JsonObject = provider(providerId, mapOf(field to value))

    fun field(providerId: String, field: String, value: String?): JsonObject = field(providerId, field, value?.let(::JsonPrimitive) ?: JsonNull)

    fun field(providerId: String, field: String, value: Number?): JsonObject = field(providerId, field, value?.let(::JsonPrimitive) ?: JsonNull)

    fun field(providerId: String, field: String, value: Boolean): JsonObject = field(providerId, field, JsonPrimitive(value))

    /** The API key as typed, or null for the masked key left as it was, which is not sent at all. */
    fun apiKey(providerId: String, typed: String): JsonObject? {
        val key = typed.trim()
        if (AiProviderSettings.isMaskedKey(key)) return null
        return field(providerId, "api_key", key.ifEmpty { null })
    }

    /** Codex's context window and auto-compact threshold together; null puts back Codex's own default. */
    fun codexContext(window: Long?, compactAt: Long?): JsonObject = provider(
        "codex",
        mapOf(
            "model_context_window" to (window?.let(::JsonPrimitive) ?: JsonNull),
            "model_auto_compact_token_limit" to (compactAt?.let(::JsonPrimitive) ?: JsonNull),
        ),
    )
}

/**
 * `GET /api/settings/ai/providers/status` (provider-probe.ts): whether chat can reach a CLI
 * provider, and when not, why and when the server looks again. A provider nothing probed (Claude)
 * is not listed, and is available by construction.
 */
data class ProviderProbeStatus(
    val id: String,
    val registered: Boolean,
    val reason: String?,
    val attempts: Int,
    val nextProbeAt: Instant?,
) {
    companion object {
        fun from(value: JsonElement?): ProviderProbeStatus? {
            val id = value["id"].string ?: return null
            return ProviderProbeStatus(
                id = id,
                registered = value["registered"].bool ?: false,
                reason = value["reason"].string,
                attempts = value["attempts"].int ?: 0,
                nextProbeAt = WireDate.parse(value["nextProbeAt"]),
            )
        }
    }
}

/** `GET /api/mcp-auth/status`: one MCP server as a chat would see it, `needs-auth` while it waits for a sign-in. */
data class McpServerState(
    val name: String,
    val status: String,
    val error: String? = null,
    val scope: String? = null,
    val source: String? = null,
) {
    /** `isClaudeAiConnector`: added on claude.ai, so connected there rather than through a local sign-in. */
    val isClaudeAiConnector: Boolean
        get() = scope == "claudeai" || name.startsWith("claude.ai ")

    /** `originLabel`: where it was configured, in words; the CLI's "dynamic" says nothing and is left out. */
    val originLabel: String?
        get() = when {
            isClaudeAiConnector -> "claude.ai connector"
            source == "plugin" -> "Plugin"
            !scope.isNullOrEmpty() && scope != "dynamic" -> scope
            else -> null
        }

    companion object {
        fun from(value: JsonElement?): McpServerState? {
            val name = value["name"].string ?: return null
            return McpServerState(name, value["status"].string.orEmpty(), value["error"].string, value["scope"].string, value["source"].string)
        }
    }
}

/** `GET /api/teams`: an agent team, as the AI settings list them under Agent Teams. */
data class TeamInfo(val name: String, val description: String?, val memberCount: Int, val createdAt: Instant?) {
    companion object {
        fun from(value: JsonElement?): TeamInfo? {
            val name = value["name"].string ?: return null
            return TeamInfo(
                name = name,
                description = value["description"].string,
                memberCount = value["members"].array?.size ?: value["memberCount"].int ?: 0,
                createdAt = WireDate.parse(value["createdAt"]),
            )
        }
    }
}
