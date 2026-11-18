package com.hienlh.ppm.state.schedules

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/*
 * What the Schedules window and the rail's button say about a schedule without asking the server
 * again (`src/shared/schedule-attention.ts`): whether it needs the user (the red dot), and whether
 * it fires in the next 24 hours (the count). A problem has a key naming that one occurrence; the
 * user's Dismiss stores it and the dot comes back only for a different problem.
 */

/** A due session schedule still waiting for its chat after this long is worth a look. */
const val WAITING_TOO_LONG_MS = 10 * 60_000L

/** The window the rail's count covers. */
const val UPCOMING_WINDOW_MS = 24 * 60 * 60_000L

private val SQLITE_STAMP = Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}(:\\d{2})?$")

/**
 * A stored timestamp as epoch ms (`parseStamp`). SQLite's own `YYYY-MM-DD HH:MM:SS` is UTC with no
 * zone; ISO strings pass. Null where the web's `Date.parse` answers NaN.
 */
fun parseStamp(ts: String?): Long? {
    if (ts.isNullOrBlank()) return null
    if (SQLITE_STAMP.matches(ts)) {
        val iso = ts.replace(' ', 'T').let { if (it.length == 16) "$it:00" else it }
        return runCatching { LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    }
    return runCatching { Instant.parse(ts).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(ts).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(ts).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
}

enum class AttentionKind(val wire: String) { FAILED("failed"), STUCK("stuck"), PAUSED("paused"), WAITING("waiting") }

/** One problem: [key] names this occurrence (`failed:<run id>`, `paused:<reason>@<updated>`…). */
data class ScheduleProblem(val kind: AttentionKind, val key: String, val text: String, val detail: String?)

private fun minutes(ms: Long): String {
    val m = maxOf(1L, Math.round(ms / 60_000.0))
    return if (m < 120) "$m min" else "${Math.round(m / 60.0)} h"
}

/** The schedule's current problem, dismissed or not; null when there is none (or it is off). */
fun scheduleProblem(s: ScheduleOverview, now: Long): ScheduleProblem? {
    if (!s.enabled) return null
    s.pausedReason?.takeIf { it.isNotEmpty() }?.let { reason ->
        return ScheduleProblem(AttentionKind.PAUSED, "paused:$reason@${s.updatedAt ?: ""}", "Paused", reason)
    }
    s.pendingSince?.takeIf { it.isNotEmpty() }?.let { since ->
        val at = parseStamp(since)
        if (at != null) {
            val waited = now - at
            if (waited >= WAITING_TOO_LONG_MS) {
                return ScheduleProblem(AttentionKind.WAITING, "waiting:$since", "Waiting for its chat ${minutes(waited)}", null)
            }
        }
    }
    val run = s.lastRun ?: return null
    // An always-on script's run row is its process, which is meant to run for good.
    if (run.status == "running" && s.mode == "tick") {
        val started = parseStamp(run.startedAt)
        if (started != null) {
            val ran = now - started
            if (ran > s.timeoutMs) {
                return ScheduleProblem(AttentionKind.STUCK, "stuck:${run.id}", "Stuck: running ${minutes(ran)}", "Its time limit is ${minutes(s.timeoutMs)}.")
            }
        }
    }
    if (run.status == "error") return ScheduleProblem(AttentionKind.FAILED, "failed:${run.id}", "Last run failed", run.error)
    return null
}

/** The problem the red dot is for: one the user has not dismissed. */
fun scheduleAttention(s: ScheduleOverview, now: Long): ScheduleProblem? =
    scheduleProblem(s, now)?.takeIf { it.key != s.attentionDismissed }

/**
 * Whether the schedule goes out within the next 24 hours: its next fire falls inside the window,
 * or a fire already came due and waits. An always-on script is never "about to run".
 */
fun firesWithin(s: ScheduleOverview, now: Long, windowMs: Long = UPCOMING_WINDOW_MS): Boolean {
    if (!s.enabled || !s.pausedReason.isNullOrEmpty() || s.mode != "tick") return false
    if (!s.pendingSince.isNullOrEmpty()) return true
    val at = parseStamp(s.nextFireAt) ?: return false
    return at <= now + windowMs
}

data class RailSummary(val upcoming: Int, val attention: Int)

/** The rail button's numbers: schedules about to run, and problems not dismissed. */
fun railSummary(list: List<ScheduleOverview>, now: Long): RailSummary {
    var upcoming = 0
    var attention = 0
    for (s in list) {
        if (firesWithin(s, now)) upcoming++
        if (scheduleAttention(s, now) != null) attention++
    }
    return RailSummary(upcoming, attention)
}

/** The rail button's label (`nav-section-rail.tsx`). */
fun railLabel(summary: RailSummary): String = buildString {
    append("Schedules · ${summary.upcoming} in the next 24 h")
    if (summary.attention > 0) append(" · ${summary.attention} need${if (summary.attention == 1) "s" else ""} attention")
}
