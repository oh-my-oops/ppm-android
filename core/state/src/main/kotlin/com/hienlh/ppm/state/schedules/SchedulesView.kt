package com.hienlh.ppm.state.schedules

import com.hienlh.ppm.state.settings.scheduleZone
import com.hienlh.ppm.state.settings.zoneCity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/*
 * How the Schedules window reads a list of schedules (`src/web/lib/schedules/schedules-view.ts`):
 * which project and chip a row passes, which group it sits in, and the words for its next run,
 * last run and permission mode. Times are shown on the clock of the zone the schedule's cron is
 * read in; everything else on this device's clock with no label.
 */

enum class ScheduleChip(val id: String, val label: String) {
    ALL("all", "All"),
    UPCOMING("upcoming", "Next 24h"),
    ATTENTION("attention", "Attention"),
    BYPASS("bypass", "Bypass"),
    OFF("off", "Off"),
}

data class SchedulesFilter(
    /** A registered project's name; null for every project. */
    val project: String? = null,
    val chip: ScheduleChip = ScheduleChip.ALL,
    val query: String = "",
)

enum class ScheduleGroupId(val id: String, val label: String) {
    ATTENTION("attention", "Needs attention"),
    UPCOMING("upcoming", "Next 24 hours"),
    LATER("later", "Later"),
    OFF("off", "Off"),
}

data class ScheduleGroup(val id: ScheduleGroupId, val items: List<ScheduleOverview>) {
    val label: String get() = id.label
}

fun isBypass(s: ScheduleOverview): Boolean = s.effectivePermissionMode == "bypassPermissions"

/** The project a row is listed under: its registered name, else its folder's last part. */
fun projectLabel(s: ScheduleOverview): String {
    s.projectName?.let { return it }
    return s.projectPath.split('/', '\\').filter { it.isNotEmpty() }.lastOrNull() ?: s.projectPath
}

fun groupOf(s: ScheduleOverview, now: Long): ScheduleGroupId = when {
    !s.enabled -> ScheduleGroupId.OFF
    scheduleAttention(s, now) != null -> ScheduleGroupId.ATTENTION
    firesWithin(s, now) -> ScheduleGroupId.UPCOMING
    else -> ScheduleGroupId.LATER
}

private fun passesChip(s: ScheduleOverview, chip: ScheduleChip, now: Long): Boolean = when (chip) {
    ScheduleChip.ALL -> true
    ScheduleChip.UPCOMING -> firesWithin(s, now)
    ScheduleChip.ATTENTION -> scheduleAttention(s, now) != null
    ScheduleChip.BYPASS -> s.enabled && isBypass(s)
    ScheduleChip.OFF -> !s.enabled
}

private fun matchesQuery(s: ScheduleOverview, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    return listOf(s.name, s.prompt, s.cronExpr, projectLabel(s), s.targetTitle ?: "", s.task?.ref ?: "", s.task?.title ?: "")
        .any { it.lowercase().contains(q) }
}

fun filterSchedules(list: List<ScheduleOverview>, filter: SchedulesFilter, now: Long): List<ScheduleOverview> =
    list.filter { s ->
        (filter.project == null || projectLabel(s) == filter.project) && passesChip(s, filter.chip, now) && matchesQuery(s, filter.query)
    }

/** How many rows each chip would show inside the chosen project. */
fun chipCounts(list: List<ScheduleOverview>, project: String?, now: Long): Map<ScheduleChip, Int> {
    val inProject = list.filter { project == null || projectLabel(it) == project }
    return ScheduleChip.entries.associateWith { chip -> inProject.count { passesChip(it, chip, now) } }
}

/** Every project that has a schedule, by name, for the project picker. */
fun projectsOf(list: List<ScheduleOverview>): List<String> = list.map(::projectLabel).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)

private fun fireTime(s: ScheduleOverview): Double {
    if (!s.pendingSince.isNullOrEmpty()) return parseStamp(s.pendingSince)?.toDouble() ?: Double.NaN
    return s.nextFireAt?.let { parseStamp(it)?.toDouble() ?: Double.NaN } ?: Double.POSITIVE_INFINITY
}

/** Groups in a fixed order, each soonest first; empty groups are left out. */
fun groupSchedules(list: List<ScheduleOverview>, now: Long): List<ScheduleGroup> {
    val buckets = ScheduleGroupId.entries.associateWith { mutableListOf<ScheduleOverview>() }
    for (s in list) buckets.getValue(groupOf(s, now)) += s
    return ScheduleGroupId.entries.map { id ->
        ScheduleGroup(id, buckets.getValue(id).sortedWith { a, b ->
            val d = fireTime(a) - fireTime(b)
            // NaN (an unreadable stamp) compares as equal in the web's sort, which keeps the id order.
            if (d.isNaN() || d == 0.0) a.id.compareTo(b.id) else if (d < 0) -1 else 1
        })
    }.filter { it.items.isNotEmpty() }
}

/** "in 8h 10m", "in 3m", "in 2d 22h", "now". */
fun relativeIn(at: Long, now: Long): String {
    val ms = at - now
    if (ms < 60_000) return "now"
    val minutes = Math.round(ms / 60_000.0)
    if (minutes < 60) return "in ${minutes}m"
    val hours = minutes / 60
    if (hours < 24) return "in ${hours}h" + if (minutes % 60 != 0L) " ${minutes % 60}m" else ""
    val days = hours / 24
    return "in ${days}d" + if (hours % 24 != 0L) " ${hours % 24}h" else ""
}

/** "3h ago", "2d ago". */
fun relativeAgo(at: Long, now: Long): String {
    val minutes = Math.round((now - at) / 60_000.0)
    if (minutes < 1) return "just now"
    if (minutes < 60) return "${minutes}m ago"
    val hours = Math.round(minutes / 60.0)
    if (hours < 48) return "${hours}h ago"
    return "${Math.round(hours / 24.0)}d ago"
}

private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)

/**
 * [at] as a clock time in [zone] (this device's when null or the same as the device's), with the
 * day in front when it is not today there: "18:30", "tomorrow 02:00", "Fri 16:00", "30 Nov 09:00",
 * and the zone's city after it when the zone is not this device's: "18:30 London".
 */
fun clockLabel(at: Long, zone: String?, now: Long, device: ZoneId = ZoneId.systemDefault()): String {
    val tz = zone?.takeIf { it != device.id }?.let(::scheduleZone)
    val shown = tz ?: device
    val instant = Instant.ofEpochMilli(at)
    val time = HM.withZone(shown).format(instant)
    val dayOf = instant.atZone(shown).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(shown).toLocalDate()
    val day = when {
        dayOf == today -> ""
        dayOf == Instant.ofEpochMilli(now + 86_400_000).atZone(shown).toLocalDate() -> "tomorrow "
        at - now < 6 * 86_400_000L && at > now -> dayOf.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.UK) + " "
        else -> "${dayOf.dayOfMonth} ${dayOf.month.getDisplayName(TextStyle.SHORT, Locale.UK)} "
    }
    val city = if (zone != null && zone != device.id) " ${zoneCity(zone)}" else ""
    return "$day$time$city"
}

data class WhenLabel(val main: String, val sub: String)

/** The "Next run" cell: when, on the schedule's own clock, and how far off. */
fun nextRunLabel(s: ScheduleOverview, now: Long, device: ZoneId = ZoneId.systemDefault()): WhenLabel {
    if (s.mode == "always-on") return WhenLabel("Always on", if (s.enabled) s.runState else "off")
    if (!s.enabled) return WhenLabel("Off", "")
    if (!s.pausedReason.isNullOrEmpty()) return WhenLabel("Paused", "turn it on to resume")
    if (!s.pendingSince.isNullOrEmpty()) return WhenLabel("Due now", "waiting for its chat")
    val at = parseStamp(s.nextFireAt) ?: return WhenLabel("No next run", "")
    val repeats = s.kind != "session" || s.recurring
    return WhenLabel(clockLabel(at, s.timezone, now, device), relativeIn(at, now) + if (repeats) "" else " · once")
}

enum class RunTone { OK, BAD, WARN, RUNNING, NONE }

data class RunLine(val tone: RunTone, val text: String, val whenText: String = "")

private fun firstLine(text: String): String {
    val line = text.split('\n').first().trim()
    return if (line.length > 60) line.take(59) + "…" else line
}

/** The "Last run" cell: its outcome in a word or two, and when. */
fun lastRunLabel(s: ScheduleOverview, now: Long): RunLine {
    val problem = scheduleProblem(s, now)
    val run = s.lastRun
    if (problem?.kind == AttentionKind.WAITING) return RunLine(RunTone.WARN, problem.text)
    if (problem?.kind == AttentionKind.PAUSED) return RunLine(RunTone.BAD, "Paused")
    if (run == null) return RunLine(RunTone.NONE, "Not run yet")
    val at = parseStamp(run.endedAt ?: run.startedAt) ?: now
    val whenText = relativeAgo(at, now)
    if (problem?.kind == AttentionKind.STUCK) return RunLine(RunTone.BAD, problem.text)
    if (run.status == "running") return RunLine(RunTone.RUNNING, "Running", relativeAgo(parseStamp(run.startedAt) ?: now, now).replace(" ago", ""))
    if (run.status == "error") return RunLine(RunTone.BAD, run.error?.let(::firstLine) ?: "Failed", whenText)
    return RunLine(RunTone.OK, "Done", whenText)
}

private val MODE_LABELS = mapOf("bypassPermissions" to "Bypass", "acceptEdits" to "Edits", "plan" to "Plan", "default" to "Ask")

fun modeLabel(mode: String): String = MODE_LABELS[mode] ?: mode

fun kindLabel(kind: String): String = when (kind) {
    "prompt" -> "Prompt · new chat each run"
    "session" -> "Sends into a chat"
    "script" -> "Script"
    else -> kind
}

/** The chat a schedule is about: a reminder's target, else the chat its last run used (`chatOf`). */
fun chatOf(s: ScheduleOverview): String? =
    if (s.kind == "session") s.targetSessionId else s.lastRun?.sessionId ?: s.sessionId

/** The window's summary line: runs in 24 h, problems, failures and Bypass among [shown]. */
data class SchedulesSummary(val upcoming: Int, val attention: Int, val failed: Int, val bypass: Int)

fun schedulesSummary(shown: List<ScheduleOverview>, now: Long): SchedulesSummary {
    val rail = railSummary(shown, now)
    return SchedulesSummary(
        upcoming = rail.upcoming,
        attention = rail.attention,
        failed = shown.count { it.enabled && it.lastRun?.status == "error" },
        bypass = shown.count { it.enabled && isBypass(it) },
    )
}

/** A run's length (`duration` in `schedule-recent-runs.tsx`): "running", "42s", "3m". */
fun runDuration(startedAt: String, endedAt: String?): String {
    if (endedAt == null) return "running"
    val start = parseStamp(startedAt) ?: return "running"
    val end = parseStamp(endedAt) ?: return "running"
    val s = maxOf(0L, Math.round((end - start) / 1000.0))
    return if (s < 90) "${s}s" else "${Math.round(s / 60.0)}m"
}
