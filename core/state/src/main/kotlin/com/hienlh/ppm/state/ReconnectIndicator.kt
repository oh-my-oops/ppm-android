package com.hienlh.ppm.state

/**
 * Stages of the chat footer's connection indicator (`use-reconnect-indicator.ts`) and how the
 * composer placeholder adapts (`message-input.tsx`).
 */
enum class ReconnectStage(val wire: String) {
    /** Connected, or within the initial grace window before showing a reconnect notice. */
    HIDDEN("hidden"),

    /** Past the grace delay (`>= 800ms`) while reconnecting automatically. */
    RECONNECTING("reconnecting"),

    /** Past the lost threshold (`>= 8s`), offering manual Retry. */
    LOST("lost"),
}

const val RECONNECT_SHOW_DELAY_MS = 800L
const val RECONNECT_LOST_AFTER_MS = 8_000L

/** Pure stage calculation at [elapsedMs] since the current reconnect window started (or restarted). */
fun reconnectStageAt(
    reconnecting: Boolean,
    elapsedMs: Long,
    showDelayMs: Long = RECONNECT_SHOW_DELAY_MS,
    lostAfterMs: Long = RECONNECT_LOST_AFTER_MS,
): ReconnectStage {
    if (!reconnecting || elapsedMs < showDelayMs) return ReconnectStage.HIDDEN
    if (elapsedMs >= lostAfterMs) return ReconnectStage.LOST
    return ReconnectStage.RECONNECTING
}

/**
 * Composer placeholder (`message-input.tsx`):
 * - while reconnecting/lost (`stage != HIDDEN`): `"Will send when reconnected..."`
 * - while a turn runs: `"Follow-up or Stop..."`
 * - idle: `"Ask anything..."`
 */
fun composerPlaceholder(stage: ReconnectStage, running: Boolean): String = when {
    stage != ReconnectStage.HIDDEN -> "Will send when reconnected..."
    running -> "Follow-up or Stop..."
    else -> "Ask anything..."
}
