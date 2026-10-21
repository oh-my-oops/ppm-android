package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** The account a chat with no session yet claims (`PickedAccount` of account-pick.service.ts). */
data class PickedAccount(val id: String, val label: String?) {
    companion object {
        fun from(value: JsonElement?): PickedAccount? {
            val id = value["id"].string ?: return null
            return PickedAccount(id, value["label"].string)
        }
    }
}

/** How `/chat/prepare`'s account pick came out: one account, none usable, too slow, or not asked for. */
sealed interface AccountPick {
    data class Picked(val account: PickedAccount) : AccountPick

    /** Nothing usable, or a provider with no accounts of PPM's: final for the tab. */
    data object None : AccountPick

    /** The pick ran out of time; asking again may still find one. */
    data object Timeout : AccountPick

    /** The tab already held a claim, so none was made. */
    data object Skipped : AccountPick

    companion object {
        fun from(value: JsonElement?): AccountPick = when {
            value.string == "timeout" -> Timeout
            value.string == "skipped" -> Skipped
            value == null || value is JsonNull -> None
            else -> PickedAccount.from(value)?.let(::Picked) ?: None
        }
    }
}

/**
 * The part of the AI settings a new chat picks its provider and mode from
 * (`ChatPreparationSettings`): never a key, a prompt or anything else a client must not see.
 */
data class ChatPreparationSettings(
    val defaultProvider: String,
    /** `default` or `follow-focus`. */
    val newChatProviderMode: String?,
    /** Each provider's default permission mode, by provider id. */
    val permissionModes: Map<String, String>,
) {
    /** `resolveNewChatProvider`: the focused chat's provider when the mode follows focus, else the default, else Claude. */
    fun resolveProvider(focusedProvider: String?): String =
        if (newChatProviderMode == "follow-focus" && !focusedProvider.isNullOrEmpty() && focusedProvider != "default") {
            focusedProvider
        } else {
            defaultProvider.ifEmpty { "claude" }
        }

    /** The mode a new chat on [providerId] starts in: its setting, else `bypassPermissions`, as the web defaults it. */
    fun permissionMode(providerId: String): String = permissionModes[providerId] ?: "bypassPermissions"

    companion object {
        fun from(value: JsonElement?): ChatPreparationSettings? {
            if (value.obj == null) return null
            return ChatPreparationSettings(
                defaultProvider = value["default_provider"].string.orEmpty(),
                newChatProviderMode = value["new_chat_provider_mode"].string,
                permissionModes = value["providers"].obj.orEmpty().mapNotNull { (id, provider) ->
                    provider["permission_mode"].string?.let { id to it }
                }.toMap(),
            )
        }
    }
}

/**
 * `POST /chat/prepare`: everything a chat tab with no session needs, in one request
 * (chat-prepare.service.ts). [providerId] is the one asked for, or [resolvedProviderId] — the
 * one the settings pick — when none was. Each part that took too long comes back null.
 */
data class PreparedChat(
    val resolvedProviderId: String,
    val providerId: String,
    val settings: ChatPreparationSettings,
    val providers: List<ProviderInfo>,
    val pickedAccount: AccountPick,
    val usage: UsageInfo?,
    /** The project's `__new__` draft; null for none, or for one that took too long to read. */
    val draft: ChatDraft?,
    val tags: ProjectTags?,
    val slash: SlashItemsPayload?,
) {
    companion object {
        fun from(value: JsonElement?): PreparedChat? {
            val resolved = value["resolvedProviderId"].string ?: return null
            val settings = ChatPreparationSettings.from(value["settings"]) ?: return null
            return PreparedChat(
                resolvedProviderId = resolved,
                providerId = value["providerId"].string ?: resolved,
                settings = settings,
                providers = value["providers"].array.orEmpty().mapNotNull(ProviderInfo::from),
                pickedAccount = AccountPick.from(value["pickedAccount"]),
                usage = UsageInfo.from(value["usage"]),
                draft = ChatDraft.from(value["draft"]),
                tags = value["tags"]?.takeIf { it.obj != null }?.let(ProjectTags::from),
                slash = SlashItemsPayload.from(value["slash"]),
            )
        }
    }
}
