package com.hienlh.ppm.state

/**
 * Claude's suggested next prompt as the composer offers it (`offeredSuggestion` in
 * `message-input.tsx`): only in a Claude chat, on an empty, idle composer that is online and open
 * to input, with no agent chip. Null when nothing is offered.
 */
fun offeredPromptSuggestion(
    suggestion: String?,
    providerId: String?,
    running: Boolean,
    offline: Boolean,
    disabled: Boolean,
    text: CharSequence,
    hasAgent: Boolean,
): String? {
    if (suggestion.isNullOrEmpty() || providerId != "claude") return null
    if (running || offline || disabled || text.isNotEmpty() || hasAgent) return null
    return suggestion
}

/** The context meter's figure colour (`contextPctClass`): error from 90 %, warning from 75 %. */
enum class ContextMeterTone { NORMAL, WARNING, ERROR }

fun contextMeterTone(pct: Int): ContextMeterTone = when {
    pct >= 90 -> ContextMeterTone.ERROR
    pct >= 75 -> ContextMeterTone.WARNING
    else -> ContextMeterTone.NORMAL
}

/** Where the activity chip's popover opens (`toggle` in `activity-chip.tsx`): a session that needs you, else agents, else tasks. */
enum class ActivityPopoverTab { AGENTS, TASKS, SESSIONS }

fun activityOpeningTab(needsYou: Boolean, hasAgents: Boolean, hasTasks: Boolean): ActivityPopoverTab = when {
    needsYou -> ActivityPopoverTab.SESSIONS
    hasAgents -> ActivityPopoverTab.AGENTS
    hasTasks -> ActivityPopoverTab.TASKS
    else -> ActivityPopoverTab.SESSIONS
}

/** The tab shown: the one picked while it still exists, else Sessions (`shown`). */
fun activityShownTab(picked: ActivityPopoverTab, hasAgents: Boolean, hasTasks: Boolean): ActivityPopoverTab = when {
    picked == ActivityPopoverTab.AGENTS && hasAgents -> picked
    picked == ActivityPopoverTab.TASKS && hasTasks -> picked
    else -> ActivityPopoverTab.SESSIONS
}
