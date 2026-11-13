package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement

/** One rate-limit window (`LimitBucket`): how full it is, 0–1, and when it empties. */
data class LimitBucket(
    val utilization: Double,
    val resetsAt: String,
    val resetsInMinutes: Long?,
    val resetsInHours: Double?,
    val windowHours: Double,
    /** Set on a per-model weekly window (`ScopedLimitBucket`): the model, as the provider names it. */
    val label: String? = null,
) {
    companion object {
        fun from(value: JsonElement?): LimitBucket? {
            val utilization = value["utilization"].double ?: return null
            return LimitBucket(
                utilization,
                value["resetsAt"].string.orEmpty(),
                value["resetsInMinutes"].long,
                value["resetsInHours"].double,
                value["windowHours"].double ?: 0.0,
                value["label"].string,
            )
        }
    }
}

/**
 * Codex's free rate-limit resets still open to an account (`ResetCredits`). [credits] lists every
 * one, soonest to lapse first; an older stored reading has only the count and the soonest.
 */
data class ResetCredits(
    val available: Int,
    val nextExpiresAt: String?,
    val title: String?,
    val nextCreditId: String?,
    val credits: List<ResetCredit> = emptyList(),
) {
    companion object {
        fun from(value: JsonElement?): ResetCredits? {
            val available = value["available"].int ?: return null
            return ResetCredits(
                available,
                value["nextExpiresAt"].string,
                value["title"].string,
                value["nextCreditId"].string,
                value["credits"].array.orEmpty().mapNotNull(ResetCredit::from),
            )
        }
    }
}

/**
 * What `GET /chat/usage` answers (`UsageInfo` plus the snapshot's `lastFetchedAt`): the
 * account's windows as fractions, 0–1, and what the session has cost.
 */
data class UsageInfo(
    val totalCostUsd: Double? = null,
    val queryCostUsd: Double? = null,
    val fiveHour: Double? = null,
    val sevenDay: Double? = null,
    val fiveHourResetsAt: String? = null,
    val sevenDayResetsAt: String? = null,
    val session: LimitBucket? = null,
    val weekly: LimitBucket? = null,
    val weeklyOpus: LimitBucket? = null,
    val weeklySonnet: LimitBucket? = null,
    val weeklyScoped: List<LimitBucket> = emptyList(),
    val resetCredits: ResetCredits? = null,
    val activeAccountId: String? = null,
    val activeAccountLabel: String? = null,
    val lastFetchedAt: String? = null,
) {
    companion object {
        fun from(value: JsonElement?): UsageInfo? {
            if (value.obj == null) return null
            return UsageInfo(
                totalCostUsd = value["totalCostUsd"].double,
                queryCostUsd = value["queryCostUsd"].double,
                fiveHour = value["fiveHour"].double,
                sevenDay = value["sevenDay"].double,
                fiveHourResetsAt = value["fiveHourResetsAt"].string,
                sevenDayResetsAt = value["sevenDayResetsAt"].string,
                session = LimitBucket.from(value["session"]),
                weekly = LimitBucket.from(value["weekly"]),
                weeklyOpus = LimitBucket.from(value["weeklyOpus"]),
                weeklySonnet = LimitBucket.from(value["weeklySonnet"]),
                weeklyScoped = value["weeklyScoped"].array.orEmpty().mapNotNull(LimitBucket::from),
                resetCredits = ResetCredits.from(value["resetCredits"]),
                activeAccountId = value["activeAccountId"].string,
                activeAccountLabel = value["activeAccountLabel"].string,
                lastFetchedAt = value["lastFetchedAt"].string,
            )
        }
    }
}
