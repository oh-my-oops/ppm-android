package com.hienlh.ppm.state.tasks

import com.hienlh.ppm.protocol.TaskCheckSummary
import com.hienlh.ppm.protocol.TaskConst
import com.hienlh.ppm.protocol.TaskDto
import com.hienlh.ppm.protocol.TaskLink
import com.hienlh.ppm.protocol.TaskLinkState
import com.hienlh.ppm.protocol.TaskMember
import com.hienlh.ppm.protocol.TaskWorkingSession
import com.hienlh.ppm.protocol.double
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * The rules every task view shares, ported from the web's `shared/ppm-tasks-*.ts` and
 * `lib/ppm-tasks/ppm-tasks-kit-logic.ts`, `task-format.ts`, `task-snooze-choices.ts`: labels, the
 * due label, waits, snoozes, codes, link addresses and the chips' words. Pure, so they are tested
 * without a screen. Days are `YYYY-MM-DD` strings, as on the web.
 */

val URGENCY_LABEL = mapOf("now" to "Now", "today" to "Today", "week" to "This week", "later" to "Later")
val IMPORTANCE_LABEL = mapOf("must" to "Must", "should" to "Should", "nice" to "Nice to have")
private val TYPE_LABEL = mapOf("task" to "Task", "decision" to "Decision", "reply" to "Reply")

fun urgencyLabel(u: String): String = URGENCY_LABEL[u] ?: u

fun importanceLabel(i: String): String = IMPORTANCE_LABEL[i] ?: i

fun typeLabel(type: String): String = TYPE_LABEL[type] ?: type.replaceFirstChar { it.uppercaseChar() }

private val YMD = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")

private fun ymdDate(ymd: String): LocalDate? {
    val m = YMD.matchEntire(ymd) ?: return null
    // JavaScript's Date.UTC rolls an out-of-range day over; so does plusDays from the 1st.
    return runCatching {
        LocalDate.of(m.groupValues[1].toInt(), 1, 1).plusMonths(m.groupValues[2].toLong() - 1).plusDays(m.groupValues[3].toLong() - 1)
    }.getOrNull()
}

/** Whole days from [fromYmd] to [toYmd]; null for a malformed date. */
fun daysBetween(fromYmd: String, toYmd: String): Int? {
    val a = ymdDate(fromYmd) ?: return null
    val b = ymdDate(toYmd) ?: return null
    return ChronoUnit.DAYS.between(a, b).toInt()
}

/** [ymd] moved by [days] calendar days. */
fun addDaysYmd(ymd: String, days: Int): String = ymdDate(ymd)?.plusDays(days.toLong())?.toString() ?: ymd

/** The device's date as `YYYY-MM-DD`. */
fun localYmd(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String = now.atZone(zone).toLocalDate().toString()

/** The floor a due date sets (`urgencyFloorForDue`), or null. */
fun urgencyFloorForDue(dueYmd: String?, todayYmd: String): String? {
    if (dueYmd == null) return null
    val days = daysBetween(todayYmd, dueYmd) ?: return null
    return when {
        days <= 0 -> "now"
        days == 1 -> "today"
        days <= 6 -> "week"
        else -> null
    }
}

private val WEEKDAY = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val MONTH = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private fun weekday(d: LocalDate) = WEEKDAY[d.dayOfWeek.value - 1]

/** "Thu 20 Nov" (the year only when it is not [todayYmd]'s). */
fun shortDay(ymd: String, todayYmd: String? = null): String {
    val d = ymdDate(ymd) ?: return ymd
    val sameYear = todayYmd == null || todayYmd.take(4) == ymd.take(4)
    return "${weekday(d)} ${d.dayOfMonth} ${MONTH[d.monthValue - 1]}${if (sameYear) "" else " ${ymd.take(4)}"}"
}

/** The due label: "Overdue 2d", "Today", "Tomorrow", "Fri", "18 Nov"; [soon] when due tomorrow or earlier. */
data class DueView(val label: String, val soon: Boolean, val overdue: Boolean)

fun dueView(dueYmd: String?, todayYmd: String): DueView? {
    if (dueYmd == null) return null
    val days = daysBetween(todayYmd, dueYmd) ?: return DueView(dueYmd, soon = false, overdue = false)
    if (days < 0) return DueView("Overdue ${-days}d", soon = true, overdue = true)
    if (days == 0) return DueView("Today", soon = true, overdue = false)
    if (days == 1) return DueView("Tomorrow", soon = true, overdue = false)
    val d = ymdDate(dueYmd)!!
    if (days <= 6) return DueView(weekday(d), soon = false, overdue = false)
    return DueView("${d.dayOfMonth} ${MONTH[d.monthValue - 1]}", soon = false, overdue = false)
}

/** "Overdue (2026-11-14)", "Due today", "Due tomorrow", "Due in 3 days", "Due 2026-12-01". */
fun dueText(dueDate: String, today: String): String {
    val days = daysBetween(today, dueDate) ?: return "Due $dueDate"
    return when {
        days < 0 -> "Overdue ($dueDate)"
        days == 0 -> "Due today"
        days == 1 -> "Due tomorrow"
        days < 7 -> "Due in $days days"
        else -> "Due $dueDate"
    }
}

private const val MIN = 60_000L
private const val HOUR = 60 * MIN
private const val DAY = 24 * HOUR

/** "now", "5m", "3h", "2d", "3w". */
fun ageShort(since: Long, now: Long = System.currentTimeMillis()): String {
    val d = max(0, now - since)
    return when {
        d < MIN -> "now"
        d < HOUR -> "${d / MIN}m"
        d < DAY -> "${d / HOUR}h"
        d < 14 * DAY -> "${d / DAY}d"
        else -> "${d / (7 * DAY)}w"
    }
}

/** "Today 09:02", "Yesterday 17:52", "Mon 09:02" within a week, else "12 Nov 09:02". */
fun whenShort(at: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
    val d = Instant.ofEpochMilli(at).atZone(zone)
    val time = "%02d:%02d".format(d.hour, d.minute)
    val days = ChronoUnit.DAYS.between(d.toLocalDate(), Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
    return when {
        days == 0L -> "Today $time"
        days == 1L -> "Yesterday $time"
        days in 2..6 -> "${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} $time"
        else -> "${d.dayOfMonth} ${MONTH[d.monthValue - 1]} $time"
    }
}

// Members

/** Up to two initials; "?" for a blank name. */
fun memberInitials(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return "?"
    val letters = if (words.size == 1) words[0].take(2) else "${words[0][0]}${words.last()[0]}"
    return letters.uppercase()
}

/** One of five tones (1–5), the same for a member everywhere (`memberTone`). */
fun memberTone(id: String): Int {
    var h = 0L
    for (c in id) h = (h * 31 + c.code) and 0xffffffffL
    return (h % 5).toInt() + 1
}

/** "You" for the user, the member's name, or the raw id when it names nobody. */
fun memberName(id: String?, members: List<TaskMember>?): String? {
    if (id == null) return null
    if (id == TaskConst.ME) return "You"
    return members?.firstOrNull { it.id == id }?.name ?: id
}

// Waiting

/** The wait of a task: [label] "Waiting on you", [name] "you"/"Việt", [until] its due date or since when. */
data class WaitingDetail(val label: String, val name: String?, val mine: Boolean, val dueDate: String?, val since: Long?)

fun isWaitingTask(t: TaskDto): Boolean = t.baseGroup != "closed" && (t.baseGroup == "waiting" || t.waitingOnMemberId != null)

fun isWaitingOnMe(t: TaskDto): Boolean = isWaitingTask(t) && t.waitingOnMemberId == TaskConst.ME

fun waitedSince(t: TaskDto): Long? {
    if (t.baseGroup == "waiting" && t.waitingStartedAt != null && t.waitingStartedAt != 0L) return t.waitingStartedAt
    return t.waitingSince ?: t.waitingStartedAt
}

/** "Blocked" for a Waiting-group status not called "Waiting", else "Waiting". */
fun waitingWord(baseGroup: String, statusName: String?): String {
    val own = statusName?.replace(Regex("\\s+"), " ")?.trim()
    return if (baseGroup == "waiting" && !own.isNullOrEmpty() && own.lowercase() != "waiting") own else "Waiting"
}

/** The wait as rows word it, or null; the member list names people, else the server's [TaskDto.waitingOnName]. */
fun taskWaiting(t: TaskDto, members: List<TaskMember>? = null): WaitingDetail? {
    if (!isWaitingTask(t)) return null
    val id = t.waitingOnMemberId
    val mine = id == TaskConst.ME
    val name = when {
        id == null -> null
        mine -> "you"
        else -> (members?.firstOrNull { it.id == id }?.name ?: t.waitingOnName)?.trim()?.takeIf { it.isNotEmpty() } ?: id
    }
    val since = waitedSince(t)?.takeIf { it != 0L }
    val word = waitingWord(t.baseGroup, t.statusName)
    return WaitingDetail(if (name != null) "$word on $name" else word, name, mine, t.dueDate, if (t.dueDate == null) since else null)
}

/** The label in full: "Waiting on Việt for 2d", "Waiting on you, due 2026-11-18". */
fun waitingTitle(d: WaitingDetail, now: Long = System.currentTimeMillis()): String = when {
    d.dueDate != null -> "${d.label}, due ${d.dueDate}"
    d.since != null -> "${d.label} for ${ageShort(d.since, now)}"
    else -> d.label
}

// Status glyph

/** `todo`, `active`, `wait`, `done` or `drop`. */
fun statusGlyphKind(baseGroup: String, closedAs: String?): String = when (baseGroup) {
    "closed" -> if (closedAs == "dropped") "drop" else "done"
    "active" -> "active"
    "waiting" -> "wait"
    else -> "todo"
}

// Snooze

fun hasSnooze(snoozedUntil: String?, snoozeUntilChange: Boolean): Boolean = snoozedUntil != null || snoozeUntilChange

fun hasWatchableLink(links: List<TaskLink>): Boolean = links.any { it.kind == "jira" || it.kind == "pr" }

/** "until Thu 20 Nov", "until a link changes", "until Thu 20 Nov or a link changes"; null when not snoozed. */
fun snoozeText(snoozedUntil: String?, snoozeUntilChange: Boolean, todayYmd: String? = null): String? {
    if (!hasSnooze(snoozedUntil, snoozeUntilChange)) return null
    val parts = listOfNotNull(snoozedUntil?.let { shortDay(it, todayYmd) }, "a link changes".takeIf { snoozeUntilChange })
    return "until ${parts.joinToString(" or ")}"
}

/** "Snoozed until Thu 20 Nov"; null when not snoozed. */
fun snoozedLabel(t: TaskDto, today: String? = null): String? = snoozeText(t.snoozedUntil, t.snoozeUntilChange, today)?.let { "Snoozed $it" }

/** "Until Thu 20 Nov", "Back today", or null; [compact]: "Thu 20 Nov" or "link change". */
fun snoozeBadgeText(t: TaskDto, today: String, compact: Boolean = false): String? {
    if (t.snoozed && compact) return t.snoozedUntil?.let { shortDay(it, today) } ?: "link change"
    if (t.snoozed) return snoozeText(t.snoozedUntil, t.snoozeUntilChange, today)?.replaceFirstChar { it.uppercaseChar() }
    return if (t.resurfacedToday) "Back today" else null
}

/** A snooze patch: the day the task shows again (or null) and whether a link change wakes it. */
data class SnoozePatch(val snoozedUntil: String?, val snoozeUntilChange: Boolean)

val UNSNOOZE_PATCH = SnoozePatch(null, false)

fun snoozeUntilPatch(day: String) = SnoozePatch(day, false)

/** One "Snooze until…" choice: [id] `tomorrow`, `week`, `date`, `link` or `clear`; [patch] null for Pick a date. */
data class SnoozeChoice(val id: String, val label: String, val detail: String?, val disabled: Boolean, val patch: SnoozePatch?)

fun snoozeChoices(t: TaskDto, today: String): List<SnoozeChoice> {
    val tomorrow = addDaysYmd(today, 1)
    val week = addDaysYmd(today, 7)
    val watchable = hasWatchableLink(t.links)
    return buildList {
        add(SnoozeChoice("tomorrow", "Tomorrow", shortDay(tomorrow, today), false, snoozeUntilPatch(tomorrow)))
        add(SnoozeChoice("week", "1 week", shortDay(week, today), false, snoozeUntilPatch(week)))
        add(SnoozeChoice("date", "Pick a date…", null, false, null))
        add(
            SnoozeChoice(
                "link", "Until a link changes", if (watchable) "Jira status or PR update" else "Needs a Jira or PR link",
                !watchable, SnoozePatch(null, true),
            ),
        )
        if (hasSnooze(t.snoozedUntil, t.snoozeUntilChange)) add(SnoozeChoice("clear", "Unsnooze", "Back in the list now", false, UNSNOOZE_PATCH))
    }
}

/** Whether a picked day can be a snooze day: a whole date after today. */
fun isSnoozeDay(day: String, today: String): Boolean = YMD.matches(day) && day > today

/** The toast after a snooze write: "ppm/12 snoozed until Thu 20 Nov" or "ppm/12 is back in the list". */
fun snoozeToastText(t: TaskDto, today: String): String {
    val text = snoozeText(t.snoozedUntil, t.snoozeUntilChange, today)
    return if (text != null) "${t.ref} snoozed $text" else "${t.ref} is back in the list"
}

// Codes and refs

data class ParsedTaskRef(val project: String?, val number: Int)

private val TASK_REF = Regex("^(?:([A-Za-z0-9_.-]{1,100})/)?#?(\\d{1,9})$")

/** `ppm/12`, `#12` or `12`; null for anything else. */
fun parseTaskRef(input: String): ParsedTaskRef? {
    val m = TASK_REF.matchEntire(input.trim()) ?: return null
    val n = m.groupValues[2].toInt()
    if (n < 1) return null
    return ParsedTaskRef(m.groupValues[1].ifEmpty { null }, n)
}

fun formatTaskRef(number: Int, project: String? = null): String = if (!project.isNullOrEmpty()) "$project/$number" else "#$number"

/** `#12` inside a view of the task's own project, `ppm/12` in a view that spans projects or belongs to another. */
fun taskCode(t: TaskDto, viewProject: String?): String =
    if (viewProject != null && viewProject == t.project) formatTaskRef(t.number) else formatTaskRef(t.number, t.project)

/** A search text read as a task code: `#82`, `ppm/82`, `ppm/#82`; bare digits are not one. */
fun parseTaskCodeQuery(input: String): ParsedTaskRef? {
    val s = input.trim()
    if (!s.startsWith("#") && !s.contains("/")) return null
    return parseTaskRef(s)
}

fun matchesTaskCode(t: TaskDto, code: ParsedTaskRef): Boolean =
    t.number == code.number && (code.project == null || code.project.lowercase() == t.project.lowercase())

/** The tasks a code names (`tasksMatchingCode`): a bare `#82` with a project in view is that project's, unless [anywhere] finds none there. */
fun tasksMatchingCode(tasks: List<TaskDto>, code: ParsedTaskRef, contextProject: String?, anywhere: Boolean = false): List<TaskDto> {
    val all = tasks.filter { it.deletedAt == null && matchesTaskCode(it, code) }
    if (code.project != null || contextProject == null) return all
    val own = all.filter { it.project == contextProject }
    return if (own.isNotEmpty() || !anywhere) own else all
}

private val JIRA_KEY = Regex("^[A-Z][A-Z0-9]{0,19}-\\d{1,9}$")

fun parseJiraKey(input: String): String? = input.trim().uppercase().takeIf { JIRA_KEY.matches(it) }

data class PrRef(val owner: String, val repo: String, val number: Int)

private const val OWNER = "[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})"
private const val REPO = "[A-Za-z0-9_.-]{1,100}"
private val PR_SHORT = Regex("^($OWNER)/($REPO)#(\\d{1,9})$")
private val PR_URL = Regex("^https?://(?:www\\.)?github\\.com/($OWNER)/($REPO)/pull/(\\d{1,9})(?:[/?#].*)?$", RegexOption.IGNORE_CASE)

fun parsePrRef(input: String): PrRef? {
    val s = input.trim()
    val m = PR_SHORT.matchEntire(s) ?: PR_URL.matchEntire(s) ?: return null
    val n = m.groupValues[3].toInt()
    if (n < 1) return null
    return PrRef(m.groupValues[1], m.groupValues[2], n)
}

fun canonicalPrRef(input: String): String? = parsePrRef(input)?.let { "${it.owner}/${it.repo}#${it.number}" }

/** An `http:`/`https:` address as the browser would load it; null otherwise. */
fun safeWebUrl(raw: String): String? {
    val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https" || uri.host.isNullOrEmpty()) return null
    val path = uri.rawPath.orEmpty().ifEmpty { "/" }
    return buildString {
        append(scheme).append("://")
        uri.rawUserInfo?.let { append(it).append('@') }
        append(uri.host.lowercase())
        if (uri.port != -1 && !(scheme == "http" && uri.port == 80) && !(scheme == "https" && uri.port == 443)) append(':').append(uri.port)
        append(path)
        uri.rawQuery?.let { append('?').append(it) }
        uri.rawFragment?.let { append('#').append(it) }
    }
}

/** A Jira site without its trailing slash, query or fragment; null when not a web address or it carries credentials. */
fun jiraSiteUrl(baseUrl: String?): String? {
    if (baseUrl.isNullOrEmpty()) return null
    val href = safeWebUrl(baseUrl) ?: return null
    val uri = URI(href)
    if (uri.rawUserInfo != null) return null
    val port = if (uri.port != -1) ":${uri.port}" else ""
    return "${uri.scheme}://${uri.host}$port${uri.rawPath.orEmpty().trimEnd('/')}"
}

private val BROWSE_PATH = Regex("^(.*?)/browse/([A-Za-z][A-Za-z0-9]{0,19}-\\d{1,9})/?$")

/** A Jira issue's address as its key and its site; null for anything else. */
fun parseJiraIssueUrl(input: String): Pair<String, String>? {
    val href = safeWebUrl(input) ?: return null
    val uri = URI(href)
    if (uri.rawUserInfo != null) return null
    val m = BROWSE_PATH.matchEntire(uri.rawPath.orEmpty()) ?: return null
    val key = parseJiraKey(m.groupValues[2]) ?: return null
    val port = if (uri.port != -1) ":${uri.port}" else ""
    val site = jiraSiteUrl("${uri.scheme}://${uri.host}$port${m.groupValues[1]}") ?: return null
    return key to site
}

/** Where a link opens in a browser; null when it has no safe web address (a chat link opens in PPM). */
fun taskLinkWebHref(link: TaskLink, jiraSite: String?): String? = when (link.kind) {
    "url" -> safeWebUrl(link.ref)
    "pr" -> parsePrRef(link.ref)?.let { "https://github.com/${it.owner}/${it.repo}/pull/${it.number}" }
    "jira" -> {
        val key = parseJiraKey(link.ref)
        val site = jiraSiteUrl(jiraSite)
        if (key != null && site != null) "$site/browse/$key" else null
    }
    else -> null
}

// Chips

/** [tone] `run`, `idle`, `need`, `mgr`, `none`, `stalled`, `finished` or `gone`. */
data class SessionChipView(val tone: String, val label: String, val detail: String?, val sessionId: String?, val title: String)

private fun minutes(ms: Long): Int = max(1, (ms / 60_000.0).roundToInt())

/** What the working-session chip shows; a handed-off chat's check wins over its live dot. */
fun sessionChipView(working: TaskWorkingSession, check: TaskCheckSummary?, now: Long): SessionChipView {
    val s = working.session
    if (working.kind == "start_chat" || s == null) {
        return SessionChipView("none", "Start chat", null, null, "No chat works on this task: start one")
    }
    val manager = working.kind == "manager"
    val name = if (manager) "Manager" else s.title?.trim()?.ifEmpty { null } ?: "Chat"
    fun v(tone: String, detail: String?, title: String) = SessionChipView(tone, name, detail, s.sessionId, title)
    if (s.state == "gone") return v("gone", "deleted", "$name — the chat no longer exists")
    if (!manager && check?.state == "finished_no_report") return v("finished", "finished, no report", "$name — finished without recording a result")
    if (!manager && check?.state == "stalled") {
        val detail = "stalled ${minutes(now - check.since)} min"
        return v("stalled", detail, "$name — $detail")
    }
    if (s.state == "waiting_card") return v("need", "waiting on you", "$name — waiting on you")
    if (s.state == "running") return v(if (manager) "mgr" else "run", null, "$name — running")
    return v(if (manager) "mgr" else "idle", null, "$name — ${if (s.state == "unknown") "state unknown" else "idle"}")
}

/** [tone] `info`, `ok`, `bad`, `warn`, `idle` or `merge`. */
data class LinkStateView(val key: String, val state: String?, val tone: String)

private fun hostOf(url: String): String = runCatching { URI(url).let { u -> u.host?.let { h -> if (u.port != -1) "$h:${u.port}" else h } } }.getOrNull()?.ifEmpty { null } ?: url

/** How a link and its cached live state read on a chip. */
fun linkStateView(link: TaskLink, live: TaskLinkState?): LinkStateView {
    val key = link.label?.trim()?.ifEmpty { null } ?: when (link.kind) {
        "pr" -> link.ref.replaceFirst(Regex("^[^/]+/"), "")
        "url" -> hostOf(link.ref)
        else -> link.ref
    }
    if (link.kind == "url") return LinkStateView(key, null, "idle")
    if (!live?.error.isNullOrEmpty()) return LinkStateView(key, "state unknown · ${live!!.error}", "idle")
    val st = live?.state ?: return LinkStateView(key, "…", "idle")
    when (link.kind) {
        "jira" -> {
            val category = st["category"].string
            val status = st["status"].string ?: st["status"]?.toString()?.takeIf { it != "null" } ?: "unknown"
            return LinkStateView(key, status, if (category == "done") "ok" else if (category == "indeterminate") "info" else "idle")
        }
        "pr" -> {
            val state = st["state"].string ?: ""
            val failing = (st["checks"]["failing"].double ?: 0.0) > 0
            val pending = (st["checks"]["pending"].double ?: 0.0) > 0
            if (state == "merged") return LinkStateView(key, "merged", "merge")
            if (state == "closed") return LinkStateView(key, "closed", "idle")
            val ci = if (failing) " · CI failing" else if (pending) " · CI running" else ""
            val tone = when {
                failing -> "bad"
                state == "draft" -> "idle"
                pending -> "warn"
                else -> "info"
            }
            return LinkStateView(key, "$state$ci", tone)
        }
    }
    val chat = st["state"].string
    val title = st["title"].string?.trim()?.ifEmpty { null }
    val named = link.label?.trim()?.ifEmpty { null } ?: title ?: key
    return when (chat) {
        "running" -> LinkStateView(named, "running", "ok")
        "waiting_card" -> LinkStateView(named, "waiting on you", "bad")
        "gone" -> LinkStateView(named, "deleted", "idle")
        else -> LinkStateView(named, if (chat == "unknown") "state unknown" else "idle", "idle")
    }
}

// Writes

/** The toast after a decision is sent ("Choice sent to X: …"), by how the chat took it. */
fun decisionDeliveredMessage(delivered: String?, sessionTitle: String?): String {
    val to = sessionTitle?.trim()?.ifEmpty { null } ?: "the chat"
    return when (delivered) {
        "started" -> "Choice sent to $to: it started working on it"
        "inserted" -> "Choice sent to $to: it reads it in the turn running now"
        "queued" -> "Choice sent to $to: queued behind its current turn or a card waiting on you"
        else -> "Choice recorded"
    }
}

/** A–J for a decision option's position. */
fun optionLetter(index: Int): String = ('A' + index).toString()

// Reminders

/** One quick choice of Remind…: [id] `hour`, `evening` or `morning`; [at] `2026-11-17T18:30` on the zone's clock. */
data class ReminderChoice(val id: String, val label: String, val at: String)

const val REMINDER_NOTE_MAX = 500

private val LOCAL_MOMENT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

/** `2026-11-17T18:30` for [at] as the clock in [zone] reads it. */
fun localMomentText(at: Instant, zone: ZoneId): String = LOCAL_MOMENT.format(at.atZone(zone))

/** In an hour (rounded up to five minutes), this evening at 18:00 while before 17:00, tomorrow at 09:00. */
fun reminderChoices(now: Instant, zone: ZoneId): List<ReminderChoice> {
    val step = 300_000L
    val inHour = Instant.ofEpochMilli((now.toEpochMilli() + 60 * 60_000 + step - 1) / step * step)
    val today = now.atZone(zone).toLocalDate()
    val hourThere = now.atZone(zone).hour
    return buildList {
        add(ReminderChoice("hour", "In 1 hour", localMomentText(inHour, zone)))
        if (hourThere < 17) add(ReminderChoice("evening", "This evening", "${today}T18:00"))
        add(ReminderChoice("morning", "Tomorrow morning", "${today.plusDays(1)}T09:00"))
    }
}

/** `HH:mm` of a local moment, for "Reminder set for 18:00". */
fun reminderTimeText(at: String): String = runCatching { LocalDateTime.parse(at, LOCAL_MOMENT) }.getOrNull()?.let { "%02d:%02d".format(it.hour, it.minute) } ?: at

/** The rail's count: open Now tasks in the scope the Tasks section shows (`railNowCount`). */
fun railNowCount(projects: List<com.hienlh.ppm.protocol.TaskProjectSummary>, scope: String, activeProject: String?, hidden: List<String>): Int {
    if (scope == "all") return projects.filter { it.enabled && it.name !in hidden }.sumOf { it.now }
    val own = projects.firstOrNull { it.name == activeProject }
    return if (own?.enabled == true) own.now else 0
}

/** Clipped for menus and toasts: "PSR Ireland export…". */
fun clip(s: String, n: Int = 48): String = if (s.length > n) s.take(n - 1) + "…" else s
