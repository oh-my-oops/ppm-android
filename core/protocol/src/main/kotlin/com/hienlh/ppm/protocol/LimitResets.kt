package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement

/**
 * One grant of Claude's free limit resets (`ClaudeLimitResetGrant` of `src/types/chat.ts`), as the
 * server already parsed it from Anthropic's `cedar_ember` block. Limit names (`clears`, `blocking`,
 * `percentUsed` keys) are Anthropic's: `five_hour`, `seven_day`, `seven_day_overage_included`, …
 */
data class ClaudeLimitResetGrant(
    val id: String,
    /** Anthropic's own description ("One full limit reset (November 2026)"). */
    val label: String,
    /** `session`, `weekly`, `full` or `fable`, as Claude Code groups them. */
    val kind: String,
    val resetsLeft: Int,
    val resetsTotal: Int = 0,
    /** ISO time it lapses, when it does. */
    val endsAt: String? = null,
    val clears: List<String> = emptyList(),
    /** Also gives back what Fable used of the weekly limit. */
    val givesBackWeekly: Boolean = false,
    val paused: Boolean = false,
    /** Anthropic would accept it right now. */
    val usableNow: Boolean = false,
    /** Usable only once a limit it refills is reached. */
    val useRequiresLimit: Boolean = true,
    /** Limits that, while reached, keep this one from being used. */
    val blocking: List<String> = emptyList(),
    /** 0–100 per limit it clears. */
    val percentUsed: Map<String, Int> = emptyMap(),
    val unavailableReason: String? = null,
) {
    companion object {
        fun from(value: JsonElement?): ClaudeLimitResetGrant? {
            val id = value["id"].string ?: return null
            return ClaudeLimitResetGrant(
                id = id,
                label = value["label"].string ?: "Limit reset",
                kind = value["kind"].string ?: "full",
                resetsLeft = value["resetsLeft"].int ?: 0,
                resetsTotal = value["resetsTotal"].int ?: 0,
                endsAt = value["endsAt"].string,
                clears = value["clears"].array.orEmpty().mapNotNull { it.string },
                givesBackWeekly = value["givesBackWeekly"].bool == true,
                paused = value["paused"].bool == true,
                usableNow = value["usableNow"].bool == true,
                useRequiresLimit = value["useRequiresLimit"].bool != false,
                blocking = value["blocking"].array.orEmpty().mapNotNull { it.string },
                percentUsed = value["percentUsed"].obj.orEmpty().mapNotNull { (k, v) -> v.int?.let { k to it } }.toMap(),
                unavailableReason = value["unavailableReason"].string,
            )
        }
    }
}

/** Claude's free limit resets an account holds (`ClaudeLimitResets`). */
data class ClaudeLimitResets(
    val grants: List<ClaudeLimitResetGrant>,
    /** The account is at a limit right now. */
    val atLimit: Boolean = false,
    /** The limits it is at. */
    val exhausted: List<String> = emptyList(),
    /** A reset was used moments ago; none until this ISO time. */
    val cooldownUntil: String? = null,
    /** When the weekly limit resets — a Claude reset does not move it. */
    val weeklyResetsAt: String? = null,
) {
    companion object {
        /** Null when there are no grants, as `deserializeUsageExtra` keeps it. */
        fun from(value: JsonElement?): ClaudeLimitResets? {
            val grants = value["grants"].array.orEmpty().mapNotNull(ClaudeLimitResetGrant::from)
            if (grants.isEmpty()) return null
            return ClaudeLimitResets(
                grants = grants,
                atLimit = value["atLimit"].bool == true,
                exhausted = value["exhausted"].array.orEmpty().mapNotNull { it.string },
                cooldownUntil = value["cooldownUntil"].string,
                weeklyResetsAt = value["weeklyResetsAt"].string,
            )
        }
    }
}

/** One Codex reset credit still available (`ResetCredits.credits[]`). */
data class ResetCredit(val id: String, val title: String? = null, val expiresAt: String? = null) {
    companion object {
        fun from(value: JsonElement?): ResetCredit? {
            val id = value["id"].string ?: return null
            return ResetCredit(id, value["title"].string, value["expiresAt"].string)
        }
    }
}

/**
 * What `POST /api/accounts/:id/limit-reset` answered (`LimitResetResult`): the claim's `result`,
 * the kind spent and what Anthropic said of it.
 */
data class ClaudeLimitResetAnswer(
    val result: String,
    val kind: String? = null,
    val reason: String? = null,
    val resetsLeft: Int? = null,
    val weeklyResetsAt: String? = null,
) {
    companion object {
        fun from(value: JsonElement?) = ClaudeLimitResetAnswer(
            result = value["result"].string ?: "error",
            kind = value["kind"].string,
            reason = value["reason"].string,
            resetsLeft = value["resetsLeft"].int,
            weeklyResetsAt = value["weeklyResetsAt"].string,
        )
    }
}
