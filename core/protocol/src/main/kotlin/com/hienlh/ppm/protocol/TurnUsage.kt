package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A turn's token split as `done` reports it (`TurnUsage` in shared/turn-usage.ts), narrowed to
 * what the app reads: the cost notice, the prompt cache's clock and the session's token history.
 */
data class TurnUsage(
    val model: String,
    /** Fresh, uncached input. */
    val inputTokens: Long,
    val outputTokens: Long,
    /** Prefix served from the cache. */
    val cacheReadTokens: Long,
    /** Prefix written into the cache. */
    val cacheWriteTokens: Long,
    val costUsd: Double,
    /** Share of the replayed prefix served from the cache, 0–1. */
    val cacheHitRate: Double,
    /** The turn re-sent an existing transcript on a subprocess spawned for it. */
    val coldStart: Boolean,
    /** Why the previous subprocess went away, when PPM knows. */
    val coldReason: String? = null,
    /** What the next message replays; absent when no message of the turn measured it. */
    val contextTokens: Double? = null,
    /** The cache window the API reported, when it reported one. */
    val cacheTtlMs: Double? = null,
    /** A compaction the turn ended on, which the next message pays to re-cache. */
    val compactedAt: Double? = null,
    /** The account the turn ran on, when PPM knows it. */
    val accountId: String? = null,
    val accountLabel: String? = null,
) {
    /** The whole prefix replayed this turn, cached or not (`prefixTokens`). */
    val prefixTokens: Long get() = inputTokens + cacheReadTokens + cacheWriteTokens

    /** The part of the prefix sent at full price (`uncachedPrefixTokens`). */
    val uncachedPrefixTokens: Long get() = inputTokens + cacheWriteTokens

    companion object {
        fun from(json: JsonElement?): TurnUsage? {
            if (json !is JsonObject) return null
            return TurnUsage(
                model = json["model"].string ?: "",
                inputTokens = json["inputTokens"].long ?: 0,
                outputTokens = json["outputTokens"].long ?: 0,
                cacheReadTokens = json["cacheReadTokens"].long ?: 0,
                cacheWriteTokens = json["cacheWriteTokens"].long ?: 0,
                costUsd = json["costUsd"].double ?: 0.0,
                cacheHitRate = json["cacheHitRate"].double ?: 0.0,
                coldStart = json["coldStart"].bool ?: false,
                coldReason = json["coldReason"].string,
                contextTokens = json["contextTokens"].double,
                cacheTtlMs = json["cacheTtlMs"].double,
                compactedAt = json["compactedAt"].double,
                accountId = json["accountId"].string,
                accountLabel = json["accountLabel"].string,
            )
        }
    }
}
