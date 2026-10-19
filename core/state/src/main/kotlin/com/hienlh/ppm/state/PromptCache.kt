package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.PromptCacheState
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** The cache's clock (`promptCacheStatus`): nothing to say, the time left, or gone and why. */
sealed interface PromptCacheStatus {
    data object Unknown : PromptCacheStatus
    data class Warm(val remainingMs: Double) : PromptCacheStatus
    data class Cold(val compacted: Boolean, val idleMs: Double, val contextTokens: Double?) : PromptCacheStatus

    /** The chip's text: minutes left, or how long it has sat idle. */
    val label: String?
        get() = when (this) {
            Unknown -> null
            is Warm -> countdown(remainingMs)
            is Cold -> idle(idleMs)
        }

    /** The chip's tooltip. */
    val detail: String?
        get() = when (this) {
            Unknown -> null
            is Warm -> "Prompt cache warm, about ${countdown(remainingMs).dropLast(1)} min left."
            is Cold -> if (compacted) {
                "Prompt cache does not cover the compacted conversation. Compacted ${idle(idleMs)} ago, so your next message will re-cache it."
            } else {
                val resend = contextTokens?.let { "about ${tokens(it)} tokens" } ?: "the whole transcript"
                "Prompt cache expired ${idle(idleMs)} ago. Your next message re-sends $resend at full price."
            }
        }

    companion object {
        /** A session that never billed this much cached prefix has nothing worth warning about. */
        const val WARN_TOKENS = 20_000.0

        private const val MINUTE = 60_000.0
        private const val HOUR = 60 * MINUTE
        private const val DAY = 24 * HOUR

        /** [now] is in the server's epoch milliseconds, as the state's times are. */
        fun of(state: PromptCacheState?, now: Double): PromptCacheStatus {
            state ?: return Unknown
            // A compaction makes the cache inapplicable however recent the last turn was.
            state.compactedAt?.let { return Cold(compacted = true, idleMs = max(0.0, now - it), contextTokens = null) }
            val ended = state.lastTurnEndedAt
            val billed = state.billedPrefixTokens
            if (ended == null || billed == null || billed < WARN_TOKENS) return Unknown
            val idle = now - ended
            return if (idle < state.ttlMs) {
                Warm(remainingMs = min(state.ttlMs, state.ttlMs - idle))
            } else {
                Cold(compacted = false, idleMs = idle, contextTokens = state.contextTokens)
            }
        }

        /** Rounded up, so a fresh turn reads the whole window and the last minute never reads 0. */
        fun countdown(ms: Double): String = "${max(1, ceil(ms / MINUTE).toInt())}m"

        /** The coarsest unit that still says something; minutes are dropped past a day. */
        fun idle(ms: Double): String {
            if (ms >= DAY) {
                val days = (ms / DAY).toInt()
                val hours = (ms % DAY / HOUR).toInt()
                return if (hours > 0) "${days}d ${hours}h" else "${days}d"
            }
            if (ms >= HOUR) {
                val hours = (ms / HOUR).toInt()
                val minutes = (ms % HOUR / MINUTE).toInt()
                return if (minutes > 0) "${hours}h ${minutes}m" else "${hours}h"
            }
            return "${max(1, (ms / MINUTE).toInt())}m"
        }

        /**
         * Whole thousands, one decimal of millions below ten (`formatContextTokens`). A half
         * rounds up, as `toFixed(1)` does: `kotlin.math.round` would take 1.25 to the even 1.2.
         */
        fun tokens(count: Double): String {
            if (count >= 1_000_000) {
                val millions = count / 1_000_000
                if (millions >= 10) return "${Math.round(millions)}M"
                val tenths = Math.round(millions * 10)
                return "${tenths / 10}.${tenths % 10}M"
            }
            if (count >= 1_000) return "${Math.round(count / 1_000)}k"
            return "${count.toInt()}"
        }
    }
}
