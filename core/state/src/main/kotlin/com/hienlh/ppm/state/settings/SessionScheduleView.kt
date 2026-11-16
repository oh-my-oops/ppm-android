package com.hienlh.ppm.state.settings

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ===========================================================================
// How a session schedule reads to a person (`src/web/lib/session-schedule-view.ts` and
// `src/shared/cron-in-words.ts`), shared by the chat's schedule chip, the schedule-run line in the
// transcript and Settings → Schedules, so the three never disagree.
// ===========================================================================

private val WEEKDAYS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val INT = Regex("^\\d+$")
private val ONE_DIGIT = Regex("^\\d$")
private val DAY_RANGE = Regex("^(\\d)-(\\d)$")
private val EVERY = Regex("^\\*/(\\d+)$")
private val CRON_SPACE = Regex("\\s+")

private fun pad(n: String) = n.padStart(2, '0')

/** "1-5" or "1,3,5" as weekday names; null when the field is not a plain list or range of days. */
private fun weekdayText(field: String): String? {
    if (field == "1-5") return "weekdays"
    if (field == "0,6" || field == "6,0") return "weekends"
    val days = mutableListOf<String>()
    for (part in field.split(',')) {
        val range = DAY_RANGE.find(part)
        if (range != null) {
            val from = range.groupValues[1].toInt()
            val to = range.groupValues[2].toInt()
            if (from > to || to > 7) return null
            days += "${WEEKDAYS[from % 7]}–${WEEKDAYS[to % 7]}"
        } else if (ONE_DIGIT.matches(part) && part.toInt() <= 7) {
            days += WEEKDAYS[part.toInt() % 7]
        } else {
            return null
        }
    }
    return days.joinToString(", ")
}

/**
 * `describeCron`: a 5-field cron in words for the common shapes ("Daily at 08:00", "Every 15
 * minutes"); the expression itself for anything else, which is never wrong, only less friendly.
 */
fun describeCron(expr: String): String {
    val fields = expr.trim().split(CRON_SPACE)
    if (fields.size != 5) return expr
    val (min, hour, dom, mon, dow) = fields
    if (hour == "*" && dom == "*" && mon == "*" && dow == "*") {
        if (min == "*") return "Every minute"
        EVERY.find(min)?.let { return "Every ${it.groupValues[1]} minutes" }
        if (INT.matches(min)) return "Hourly at :${pad(min)}"
        return expr
    }
    if (!INT.matches(min) || !INT.matches(hour)) return expr
    val at = "${pad(hour)}:${pad(min)}"
    if (dom == "*" && mon == "*") {
        if (dow == "*") return "Daily at $at"
        val days = weekdayText(dow) ?: return expr
        return "${days.substring(0, 1).uppercase()}${days.substring(1)} at $at"
    }
    if (dow != "*" || !INT.matches(dom)) return expr
    if (mon == "*") return "Monthly on day $dom at $at"
    if (INT.matches(mon) && mon.toInt() in 1..12) return "${MONTHS[mon.toInt() - 1]} $dom at $at"
    return expr
}

/** "Europe/London" → "London", "America/Argentina/Buenos_Aires" → "Buenos Aires"; "UTC" stays. */
fun zoneCity(zone: String): String = zone.split('/').last().replace('_', ' ')

/** How a schedule's time is written: [withDate] puts the month and day in front. */
fun interface ScheduleClock {
    fun format(instant: Instant, zone: ZoneId, withDate: Boolean): String

    companion object {
        /** Chrome's en-US for `{ month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" }`. */
        val English = ScheduleClock { instant, zone, withDate ->
            DateTimeFormatter.ofPattern(if (withDate) "MMM d, hh:mm a" else "hh:mm a", Locale.US).withZone(zone).format(instant)
        }
    }
}

/** An IANA zone as the browser's `Intl` would take it (any case), or null when it is none. */
fun scheduleZone(zone: String): ZoneId? {
    val name = zone.trim()
    if (name.isEmpty() || name.length > 64) return null
    runCatching { return ZoneId.of(name) }
    val canonical = ZoneId.getAvailableZoneIds().firstOrNull { it.equals(name, ignoreCase = true) } ?: return null
    return ZoneId.of(canonical)
}

internal fun parseScheduleInstant(iso: String): Instant? =
    runCatching { Instant.parse(iso) }.getOrNull() ?: runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()

/**
 * `formatInZone`: [iso] as a clock time in [zone] — with the date in front when it is not today
 * there — and the zone's city after it. With no zone it is the device's own time, unlabelled.
 */
fun formatInZone(
    iso: String,
    zone: String?,
    now: Instant = Instant.now(),
    clock: ScheduleClock = ScheduleClock.English,
    deviceZone: ZoneId = ZoneId.systemDefault(),
): String {
    val instant = parseScheduleInstant(iso) ?: return iso
    val resolved = if (zone != null) scheduleZone(zone) else deviceZone
    val text = if (resolved == null) {
        // A zone this device does not know: its own time, still labelled with the zone.
        clock.format(instant, deviceZone, withDate = true)
    } else {
        val sameDay = instant.atZone(resolved).toLocalDate() == now.atZone(resolved).toLocalDate()
        clock.format(instant, resolved, withDate = !sameDay)
    }
    return if (zone != null) "$text (${zoneCity(zone)})" else text
}

enum class SessionScheduleTone { OK, WAIT, BAD, OFF }

data class SessionScheduleStatus(
    val text: String,
    val tone: SessionScheduleTone,
    /** A sentence saying why, for a paused or waiting schedule. */
    val detail: String? = null,
)

/** `sessionScheduleStatus`: active, waiting for its chat (or held after a Stop), paused (and why), or off. */
fun sessionScheduleStatus(
    s: ScheduleRowState,
    now: Instant = Instant.now(),
    clock: ScheduleClock = ScheduleClock.English,
    deviceZone: ZoneId = ZoneId.systemDefault(),
): SessionScheduleStatus {
    s.pausedReason?.takeIf { it.isNotEmpty() }?.let { return SessionScheduleStatus("Paused", SessionScheduleTone.BAD, it) }
    if (!s.enabled) return SessionScheduleStatus("Off", SessionScheduleTone.OFF)
    val pending = s.pendingSince?.takeIf { it.isNotEmpty() }
    if (pending != null) {
        val since = formatInZone(pending, s.timezone, now, clock, deviceZone)
        return if (s.heldAfterStop) {
            SessionScheduleStatus(
                "Held after Stop",
                SessionScheduleTone.WAIT,
                "Due since $since; held after Stop until the chat's next turn ends or the next fire.",
            )
        } else {
            SessionScheduleStatus("Waiting for the chat", SessionScheduleTone.WAIT, "Due since $since; it is sent once the chat is idle.")
        }
    }
    return SessionScheduleStatus("Active", SessionScheduleTone.OK)
}

/** `needsAttention`: paused by PPM, or due and still waiting for its chat. */
fun needsAttention(s: ScheduleRowState): Boolean =
    !s.pausedReason.isNullOrEmpty() || (s.enabled && !s.pendingSince.isNullOrEmpty())

/** `nextRunText`: "Next: Nov 15, 08:00 AM (London)", or why there is none. */
fun nextRunText(
    s: ScheduleRowState,
    now: Instant = Instant.now(),
    clock: ScheduleClock = ScheduleClock.English,
    deviceZone: ZoneId = ZoneId.systemDefault(),
): String {
    if (!s.enabled || !s.pausedReason.isNullOrEmpty()) return "Not scheduled"
    val next = s.nextFireAt?.takeIf { it.isNotEmpty() } ?: return if (!s.pendingSince.isNullOrEmpty()) "Due now" else "No next run"
    return "Next: ${formatInZone(next, s.timezone, now, clock, deviceZone)}"
}

/** `scheduleRunNote`: the muted note after a run's time — sent late, standing for later fires, or both. */
fun scheduleRunNote(catchUp: Boolean, coalesced: Boolean): String? =
    listOfNotNull("sent late".takeIf { catchUp }, "later fires folded in".takeIf { coalesced }).joinToString(" · ").ifEmpty { null }

// ===========================================================================
// The "Chat" kind of the schedule form (`schedule-form-model.ts` for a session schedule, its rules
// from `session-schedule-input.ts`).
// ===========================================================================

/** `MAX_SCHEDULES_PER_SESSION`. */
const val MAX_SCHEDULES_PER_SESSION = 20

private val SESSION_ID_RE = Regex("^[A-Za-z0-9_-]{8,64}$")
private const val NAME_LIMIT = 100
private const val PROMPT_LIMIT = 100_000
private val CRON_FIELD = Regex("^[0-9A-Za-z*,/\\-?#]+$")

/** `timezoneProblem`: why [zone] cannot be a schedule's time zone, or null when it can (blank is the host's). */
fun timezoneProblem(zone: String): String? =
    if (zone.isBlank() || scheduleZone(zone) != null) null else "timezone must be an IANA time zone such as Europe/London"

/** A session schedule as the form holds it. */
data class SessionScheduleForm(
    val projectPath: String = "",
    val targetSessionId: String = "",
    val name: String = "",
    val prompt: String = "",
    val cron: String = "0 7 * * *",
    /** As typed; blank is the PPM host's own zone. */
    val timezone: String = "",
    /** False: it fires once and then removes itself. */
    val recurring: Boolean = true,
    val enabled: Boolean = true,
) {
    /**
     * What is wrong with it, one sentence each (`sessionProblems`): the server's own rules, a missing
     * chat asked for by name. Croner's finer cron checks are left to the server's answer.
     */
    fun problems(): List<String> {
        val problems = mutableListOf<String>()
        if (projectPath.isEmpty()) problems += "Pick a project"
        if (!SESSION_ID_RE.matches(targetSessionId)) problems += "Pick a chat"
        if (prompt.isBlank()) problems += "A session schedule needs a prompt"
        else if (prompt.length > PROMPT_LIMIT) problems += "The prompt is longer than $PROMPT_LIMIT characters"
        if (name.trim().length > NAME_LIMIT) problems += "name must be text of at most $NAME_LIMIT characters"
        timezoneProblem(timezone)?.let { problems += it }
        val fields = cron.trim().split(CRON_SPACE).filter { it.isNotEmpty() }
        when {
            fields.isEmpty() -> problems += "A session schedule needs a cron expression"
            fields.size != 5 -> problems += "A session schedule takes 5 cron fields: minute hour day month weekday"
            fields.any { !CRON_FIELD.matches(it) } -> problems += "Invalid cron expression"
        }
        return problems
    }

    /** `POST /api/schedules` (`sessionRequestBody`): the project, provider and mode come from the chat on the server. */
    fun requestBody(): JsonObject = buildJsonObject {
        put("name", name.trim())
        put("prompt", prompt)
        put("cron_expr", cron.trim())
        put("timezone", timezone.trim().ifEmpty { null }?.let(::JsonPrimitive) ?: JsonNull)
        put("recurring", recurring)
        put("enabled", enabled)
        put("kind", "session")
        put("target_session_id", targetSessionId)
    }
}
