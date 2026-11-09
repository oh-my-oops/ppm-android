package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement

/**
 * A Claude account as `GET /api/accounts` lists it (`AccountInfo` of api-settings.ts), narrowed
 * to what an account card reads. [expiresAt] and [grantExpiresAt] are Unix seconds.
 */
data class AccountInfo(
    val id: String,
    val label: String?,
    val email: String?,
    /** `active`, `cooldown` or `disabled`. */
    val status: String,
    val expiresAt: Long?,
    val hasRefreshToken: Boolean,
    /** The OAuth server rejected this account's refresh token; only signing in again fixes it. */
    val reauthRequired: Boolean,
    /** When the sign-in stops working, where the server knows. */
    val grantExpiresAt: Long?,
) {
    companion object {
        fun from(value: JsonElement?): AccountInfo? {
            val id = value["id"].string ?: return null
            return AccountInfo(
                id = id,
                label = value["label"].string,
                email = value["email"].string,
                status = value["status"].string ?: "active",
                expiresAt = value["expiresAt"].long,
                hasRefreshToken = value["hasRefreshToken"].bool == true,
                reauthRequired = value["reauthRequired"].bool == true,
                grantExpiresAt = value["grantExpiresAt"].long,
            )
        }
    }
}

/** One account's windows as `GET /api/accounts/usage` reports them (`AccountUsageEntry.usage`). */
data class AccountUsage(
    val lastFetchedAt: String? = null,
    val session: LimitBucket? = null,
    val weekly: LimitBucket? = null,
    val weeklyOpus: LimitBucket? = null,
    val weeklySonnet: LimitBucket? = null,
    val weeklyScoped: List<LimitBucket> = emptyList(),
) {
    val hasBuckets: Boolean
        get() = session != null || weekly != null || weeklyOpus != null || weeklySonnet != null || weeklyScoped.isNotEmpty()

    companion object {
        fun from(value: JsonElement?) = AccountUsage(
            lastFetchedAt = value["lastFetchedAt"].string,
            session = LimitBucket.from(value["session"]),
            weekly = LimitBucket.from(value["weekly"]),
            weeklyOpus = LimitBucket.from(value["weeklyOpus"]),
            weeklySonnet = LimitBucket.from(value["weeklySonnet"]),
            weeklyScoped = value["weeklyScoped"].array.orEmpty().mapNotNull(LimitBucket::from),
        )
    }
}

/** An account in the shape every account card speaks (`AccountUsageEntry`): a Claude one, or a Codex one made to fit. */
data class AccountUsageEntry(
    val accountId: String,
    val accountLabel: String?,
    val accountStatus: String,
    /** A sign-in rather than a static API key. */
    val isOAuth: Boolean,
    val usage: AccountUsage,
) {
    companion object {
        fun from(value: JsonElement?): AccountUsageEntry? {
            val id = value["accountId"].string ?: return null
            return AccountUsageEntry(
                accountId = id,
                accountLabel = value["accountLabel"].string,
                accountStatus = value["accountStatus"].string ?: "active",
                isOAuth = value["isOAuth"].bool == true,
                usage = AccountUsage.from(value["usage"]),
            )
        }
    }
}

/** A Codex account as `GET /api/codex-accounts` lists it (`CodexAccount` of use-codex-accounts.ts). */
data class CodexAccount(
    val id: String,
    val label: String,
    /** `chatgpt` (a sign-in) or `apiKey`. */
    val type: String,
    val planType: String?,
    val status: String,
    val dailyGuardEnabled: Boolean,
    /** The server saw this account's login refused (revoked or expired). Live, not stored. */
    val signedOut: Boolean,
) {
    companion object {
        fun from(value: JsonElement?): CodexAccount? {
            val id = value["id"].string ?: return null
            return CodexAccount(
                id = id,
                label = value["label"].string.orEmpty(),
                type = value["type"].string.orEmpty(),
                planType = value["planType"].string,
                status = value["status"].string ?: "active",
                dailyGuardEnabled = value["dailyGuardEnabled"].bool == true,
                signedOut = value["signedOut"].bool == true,
            )
        }
    }
}

/**
 * An Antigravity account as `GET /api/antigravity-accounts` lists it (`AntigravityAccount` of
 * antigravity-account.service.ts). The ambient IDE/CLI account has `id == "default"` and `home == ""`.
 */
data class AntigravityAccount(
    val id: String,
    val label: String,
    val email: String? = null,
    val planType: String? = null,
    val home: String = "",
    val status: String = "active",
    val addedAt: String = "",
) {
    val isDefault: Boolean
        get() = id == DEFAULT_ID

    /** `formatAntigravityAccountLabel`: label with its plan badge for the chat toolbar. */
    val displayLabel: String
        get() = if (!planType.isNullOrEmpty()) "$label · $planType" else label

    companion object {
        const val DEFAULT_ID = "default"

        fun from(value: JsonElement?): AntigravityAccount? {
            val id = value["id"].string ?: return null
            return AntigravityAccount(
                id = id,
                label = value["label"].string.orEmpty(),
                email = value["email"].string,
                planType = value["planType"].string,
                home = value["home"].string.orEmpty(),
                status = value["status"].string ?: "active",
                addedAt = value["addedAt"].string.orEmpty(),
            )
        }
    }
}
