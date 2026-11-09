package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.ChatPreparationSettings
import com.hienlh.ppm.protocol.PickedAccount
import com.hienlh.ppm.protocol.PreparedChat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `POST /chat/prepare`, once per chat tab with no session (new-chat-prepare-client.ts). A tab
 * that knows its provider names it; one still to resolve passes the provider of the chat that
 * had focus when it opened. Each call takes an account from the pool unless [skipPick].
 */
suspend fun ApiClient.prepareChat(project: String, providerId: String?, focusedProvider: String?, skipPick: Boolean): PreparedChat {
    val body = buildJsonObject {
        if (providerId != null) put("providerId", providerId) else if (focusedProvider != null) put("focusedProvider", focusedProvider)
        put("skipPick", skipPick)
    }
    return PreparedChat.from(call("POST", "${chatPath(project)}/prepare", body = body))
        ?: throw PpmError.BadResponse("Unexpected chat preparation.")
}

/**
 * `getChatPreparationSettings`: what a new chat tab resolves its provider and mode from when its
 * `/chat/prepare` failed — the provider part of the AI settings, nothing else of them.
 */
suspend fun ApiClient.chatPreparationSettings(): ChatPreparationSettings =
    ChatPreparationSettings.from(call("GET", "/api/settings/ai")) ?: throw PpmError.BadResponse("Unexpected AI settings.")

/**
 * `POST /chat/prewarm` (use-chat-prewarm.ts): the server starts the process a new chat's first
 * message will run on, with the picks that message will carry. Only Claude starts one.
 */
suspend fun ApiClient.prewarmChat(
    project: String,
    providerId: String,
    permissionMode: String?,
    accountId: String?,
    model: String? = null,
    effort: String? = null,
    thinking: Boolean? = null,
) {
    val body = buildJsonObject {
        put("providerId", providerId)
        if (permissionMode != null) put("permissionMode", permissionMode)
        if (accountId != null) put("accountId", accountId)
        if (model != null) put("model", model)
        if (effort != null) put("effort", effort)
        if (thinking != null) put("thinking", thinking)
    }
    call("POST", "${chatPath(project)}/prewarm", body = body)
}

/**
 * `pickAccountForTab`: claims the account a new chat will run on, round robin. Only Claude,
 * Codex and Antigravity keep accounts; any other provider answers null without asking.
 */
suspend fun ApiClient.pickAccount(providerId: String): PickedAccount? {
    val path = when (providerId) {
        "claude" -> "/api/accounts/pick"
        "codex" -> "/api/codex-accounts/pick"
        "antigravity" -> "/api/antigravity-accounts/pick"
        else -> return null
    }
    return PickedAccount.from(call("POST", path))
}
