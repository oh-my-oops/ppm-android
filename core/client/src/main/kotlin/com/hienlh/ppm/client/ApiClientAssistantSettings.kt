package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.ASSISTANT_KEYS_SAVED_ELSEWHERE
import com.hienlh.ppm.protocol.AssistantGeneralDraft
import com.hienlh.ppm.protocol.AssistantNotesView
import com.hienlh.ppm.protocol.AssistantSettingsResponse
import com.hienlh.ppm.protocol.LegacyMemory
import com.hienlh.ppm.protocol.ManagerRulesView
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * The routes Settings → PPM Assistant calls (`assistant-settings-api.ts`, `assistant-settings-client.ts`):
 * the settings row, each project's manager chat and its rules, the notebook, the Telegram binding
 * and what the old PPMBot remembered. The main session, the manager list and the assign check are
 * in `ApiClientAssistant.kt`. A project is always named, never a path.
 */

private const val SETTINGS = "/api/assistant/settings"

private fun managerPath(project: String) = "/api/assistant/managers/${ApiClient.escape(project)}"

suspend fun ApiClient.assistantSettings(): AssistantSettingsResponse = AssistantSettingsResponse.from(call("GET", SETTINGS))

/** A put of the whole row; every MCP env and header value typed is a secret, kept out of traces. */
private suspend fun ApiClient.putAssistantSettings(body: JsonObject): AssistantSettingsResponse {
    body["mcp_servers"]?.let { servers ->
        (servers as? JsonArray)?.forEach { s ->
            (s["env"].obj.orEmpty() + s["headers"].obj.orEmpty()).values.forEach { v -> v.string?.takeIf { it.isNotEmpty() }?.let(TraceRedactor.Global::remember) }
        }
    }
    return AssistantSettingsResponse.from(call("PUT", SETTINGS, body = body))
}

/** What the General pane's Save sends: the row as loaded, its fields replaced by [draft], the other panes' keys left out. */
fun assistantGeneralBody(loaded: JsonObject, draft: AssistantGeneralDraft): JsonObject = buildJsonObject {
    for ((key, value) in loaded) if (key !in ASSISTANT_KEYS_SAVED_ELSEWHERE) put(key, value)
    put("default_provider", draft.defaultProvider?.let(::JsonPrimitive) ?: JsonNull)
    put("providers", JsonObject(draft.providers.filterValues { !it.isEmpty }.mapValues { it.value.toJson() }))
    put("instructions", draft.instructions)
    put("persona", draft.persona.toJson())
    put("mcp_servers", JsonArray(draft.mcpServers.map { it.toJson() }))
}

/** `saveGeneralAssistantSettings`. */
suspend fun ApiClient.saveAssistantGeneral(loaded: JsonObject, draft: AssistantGeneralDraft): AssistantSettingsResponse =
    putAssistantSettings(assistantGeneralBody(loaded, draft))

/**
 * `patchAssistantSettings`: changes only the keys in [patch]. The General fields are read first and
 * sent back as stored (a blank env or header value keeps the saved one); every other pane's key is
 * left out, so two panes saving at once cannot put each other's old value back.
 */
suspend fun ApiClient.patchAssistantSettings(patch: JsonObject): AssistantSettingsResponse {
    val current = assistantSettings().settings.raw
    val body = buildJsonObject {
        for ((key, value) in current) if (key != "main_session" && key !in ASSISTANT_KEYS_SAVED_ELSEWHERE) put(key, value)
        for ((key, value) in patch) put(key, value)
    }
    return putAssistantSettings(body)
}

// Manager chats

suspend fun ApiClient.patchManagerChat(project: String, digest: Boolean? = null, telegramDetail: String? = null) {
    call(
        "PATCH",
        managerPath(project),
        body = buildJsonObject {
            digest?.let { put("digest", it) }
            telegramDetail?.let { put("telegramDetail", it) }
        },
    )
}

/** A new Claude chat made the project's manager; answers its session id. */
suspend fun ApiClient.createManagerChat(project: String): String? =
    call("POST", "${managerPath(project)}/create", body = JsonObject(emptyMap()))["sessionId"].string

suspend fun ApiClient.managerRules(project: String): ManagerRulesView = ManagerRulesView.from(call("GET", "${managerPath(project)}/rules")["rules"])

suspend fun ApiClient.putManagerRules(project: String, text: String): ManagerRulesView =
    ManagerRulesView.from(call("PUT", "${managerPath(project)}/rules", body = buildJsonObject { put("text", text) })["rules"])

/** A held change: approve or revert, named by its hash. */
suspend fun ApiClient.decideManagerRules(project: String, approve: Boolean, hash: String): ManagerRulesView = ManagerRulesView.from(
    call(
        "POST",
        "${managerPath(project)}/rules/decision",
        body = buildJsonObject {
            put("action", if (approve) "approve" else "revert")
            put("hash", hash)
        },
    )["rules"],
)

// Notebook

suspend fun ApiClient.assistantNotes(): AssistantNotesView = AssistantNotesView.from(call("GET", "/api/assistant/notes"))

suspend fun ApiClient.addAssistantNote(text: String) {
    call("POST", "/api/assistant/notes", body = buildJsonObject { put("text", text) })
}

suspend fun ApiClient.editAssistantNote(id: Long, text: String) {
    call("PATCH", "/api/assistant/notes/$id", body = buildJsonObject { put("text", text) })
}

suspend fun ApiClient.verifyAssistantNote(id: Long) {
    call("PATCH", "/api/assistant/notes/$id", body = buildJsonObject { put("verified", true) })
}

suspend fun ApiClient.deleteAssistantNote(id: Long) {
    call("DELETE", "/api/assistant/notes/$id")
}

// Telegram

/** Puts [sessionId] on the connected Telegram chat [chatId]. */
suspend fun ApiClient.bindAssistantTelegram(sessionId: String, chatId: String) {
    call(
        "POST",
        "/api/assistant/telegram/bind",
        body = buildJsonObject {
            put("sessionId", sessionId)
            put("chatId", chatId)
        },
    )
}

/** What the old PPMBot remembered; empty from a server without the list. */
suspend fun ApiClient.legacyMemories(): List<LegacyMemory> = LegacyMemory.list(call("GET", "/api/assistant/telegram/legacy-memories"))

/** Bytes as the server counts them (UTF-8), for the 16 KB rules and template counters. */
fun utf8Bytes(text: String): Int = text.toByteArray(Charsets.UTF_8).size
