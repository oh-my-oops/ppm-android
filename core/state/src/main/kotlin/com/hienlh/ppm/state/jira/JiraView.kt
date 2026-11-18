package com.hienlh.ppm.state.jira

import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.schedules.parseStamp
import com.hienlh.ppm.protocol.prettyString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/*
 * What the Jira panel needs beyond the rows Settings already parses (`JiraConfigRow`,
 * `JiraWatcherRow`, `JiraResultRow`): a ticket as the detail dialog shows it, its transitions,
 * the filter builder's options and JQL, and the small words the cards use
 * (`src/web/components/jira/`).
 */

/** A ticket as `GET /api/jira/ticket/:configId/:issueKey` answers it (Jira's own issue, the fields PPM asks for). */
data class JiraIssueView(
    val key: String,
    val summary: String,
    /** Plain text, or Jira's document format as indented JSON, as the web shows it; null when there is none. */
    val description: String?,
    val statusName: String,
    val priorityName: String?,
    val assigneeName: String?,
    val updated: String?,
)

fun parseJiraIssue(e: JsonElement?): JiraIssueView? {
    val o = e.obj ?: return null
    val key = o["key"].string ?: return null
    val f = o["fields"]
    val desc = f["description"]
    val description = when {
        desc == null || desc is JsonNull -> null
        desc is JsonPrimitive -> desc.string?.takeIf { it.isNotEmpty() }
        else -> desc.prettyString
    }
    return JiraIssueView(
        key = key,
        summary = f["summary"].string ?: "",
        description = description,
        statusName = f["status"]["name"].string ?: "",
        priorityName = f["priority"]["name"].string,
        assigneeName = f["assignee"]["displayName"].string,
        updated = f["updated"].string,
    )
}

/** One way a ticket may move (`JiraTransition`): shown as "name → to". */
data class JiraTransitionView(val id: String, val name: String, val toName: String) {
    val label: String get() = "$name → $toName"
}

fun parseJiraTransitions(e: JsonElement?): List<JiraTransitionView> =
    (e.array ?: e["transitions"].array).orEmpty().mapNotNull { t ->
        val id = t["id"].string ?: t["id"].int?.toString() ?: return@mapNotNull null
        JiraTransitionView(id, t["name"].string ?: id, t["to"]["name"].string ?: "")
    }

/** A ticket the "Test Filter" button found. */
data class JiraTestIssue(val key: String, val summary: String, val statusName: String)

data class JiraTestResult(val issues: List<JiraTestIssue>, val total: Int)

fun parseJiraTestResult(e: JsonElement?): JiraTestResult {
    val issues = e["issues"].array.orEmpty().mapNotNull { i ->
        val key = i["key"].string ?: return@mapNotNull null
        JiraTestIssue(key, i["fields"]["summary"].string ?: "", i["fields"]["status"]["name"].string ?: "")
    }
    return JiraTestResult(issues, e["total"].int ?: issues.size)
}

/** "N ticket(s) found (showing M)" over the test's preview. */
fun jiraTestHeading(r: JiraTestResult): String =
    "${r.total} ticket${if (r.total != 1) "s" else ""} found" +
        if (r.total > r.issues.size) " (showing ${r.issues.size})" else ""

// Filter builder

enum class JiraFilterField(val label: String, val placeholder: String) {
    PROJECT("Project", "Select project..."),
    ISSUE_TYPE("Issue Type", "Select issue type..."),
    PRIORITY("Priority", "Select priority..."),
    STATUS("Status", "Select status..."),
    ASSIGNEE("Assignee", "Select assignee..."),
}

/** One value a field may take: [value] goes into the JQL, [label] is what the picker shows. */
data class JiraFieldOption(val value: String, val label: String)

/** `/metadata/:configId/projects`: `KEY — Name`, keyed by the project key. */
fun parseJiraMetadataProjects(e: JsonElement?): List<JiraFieldOption> = e.array.orEmpty().mapNotNull { p ->
    val name = p["name"].string ?: return@mapNotNull null
    val key = p["key"].string ?: name
    JiraFieldOption(key, "$key — $name")
}

/** `/metadata/:configId/{issuetype,priority,status}`: by name. */
fun parseJiraNamedOptions(e: JsonElement?): List<JiraFieldOption> = e.array.orEmpty().mapNotNull { o ->
    o["name"].string?.let { JiraFieldOption(it, it) }
}

/** `/metadata/:configId/assignees`: by account id, shown by display name. */
fun parseJiraAssigneeOptions(e: JsonElement?): List<JiraFieldOption> = e.array.orEmpty().mapNotNull { u ->
    val name = u["displayName"].string ?: return@mapNotNull null
    JiraFieldOption(u["accountId"].string ?: name, name)
}

/** The chosen values of each field, in the order they were picked. */
typealias JiraFilters = Map<JiraFilterField, List<String>>

/** The builder's JQL (`filtersToJql`): projects bare, every other value quoted, newest first. */
fun filtersToJql(filters: JiraFilters): String {
    fun quoted(f: JiraFilterField) = filters[f].orEmpty().joinToString(", ") { "\"$it\"" }
    val clauses = buildList {
        filters[JiraFilterField.PROJECT].orEmpty().takeIf { it.isNotEmpty() }?.let { add("project IN (${it.joinToString(", ")})") }
        if (filters[JiraFilterField.ISSUE_TYPE].orEmpty().isNotEmpty()) add("issuetype IN (${quoted(JiraFilterField.ISSUE_TYPE)})")
        if (filters[JiraFilterField.PRIORITY].orEmpty().isNotEmpty()) add("priority IN (${quoted(JiraFilterField.PRIORITY)})")
        if (filters[JiraFilterField.STATUS].orEmpty().isNotEmpty()) add("status IN (${quoted(JiraFilterField.STATUS)})")
        if (filters[JiraFilterField.ASSIGNEE].orEmpty().isNotEmpty()) add("assignee IN (${quoted(JiraFilterField.ASSIGNEE)})")
    }
    return if (clauses.isEmpty()) "ORDER BY updated DESC" else clauses.joinToString(" AND ") + " ORDER BY updated DESC"
}

// Watchers

/** The watcher form's choices of how often to poll. */
val JIRA_INTERVALS: List<Pair<String, Int>> = listOf(
    "30s" to 30_000, "1m" to 60_000, "2m" to 120_000, "5m" to 300_000,
    "10m" to 600_000, "30m" to 1_800_000, "1h" to 3_600_000,
)

/** "2m", "1h", "30s" (`formatInterval`). */
fun formatJiraInterval(ms: Int): String = when {
    ms >= 3_600_000 -> "${trim(ms / 3_600_000.0)}h"
    ms >= 60_000 -> "${trim(ms / 60_000.0)}m"
    else -> "${trim(ms / 1000.0)}s"
}

private fun trim(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

/** "Pulled N new issue(s)". */
fun jiraPulledText(newIssues: Int): String = "Pulled $newIssues new issue${if (newIssues != 1) "s" else ""}"

// Tickets

const val JIRA_DEFAULT_PROMPT = "Debug Jira issue {issue_key}: {summary}"

/** The debug dialog's starting prompt: the watcher's template, or the default, filled in. */
fun jiraDebugPrompt(template: String?, issueKey: String, summary: String?): String =
    (template ?: JIRA_DEFAULT_PROMPT).replace("{issue_key}", issueKey).replace("{summary}", summary ?: "")

/** "now", "5m", "3h", "2d" since [createdAt] (`timeAgo`); empty when it cannot be read. */
fun jiraTimeAgo(createdAt: String?, now: Long): String {
    val at = parseStamp(createdAt) ?: return ""
    val mins = Math.floorDiv(now - at, 60_000L)
    if (mins < 1) return "now"
    if (mins < 60) return "${mins}m"
    val hrs = mins / 60
    if (hrs < 24) return "${hrs}h"
    return "${hrs / 24}d"
}

/** A done result nobody opened: the card's dot and the panel's count. */
fun isUnreadJiraResult(status: String, readAt: String?): Boolean = status == "done" && readAt == null

private val JIRA_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")

/** Jira's `updated` (`2026-11-18T10:00:00.000+0000`) on this device's clock, as `toLocaleString` would. */
fun jiraUpdatedLabel(updated: String?, zone: ZoneId = ZoneId.systemDefault()): String {
    if (updated.isNullOrBlank()) return ""
    val at = runCatching { OffsetDateTime.parse(updated, JIRA_STAMP).toInstant() }.getOrNull()
        ?: parseStamp(updated)?.let(Instant::ofEpochMilli)
        ?: return updated
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(zone).format(at)
}

/** `GET /api/jira/results/unread-count`. */
fun parseJiraUnreadCount(e: JsonElement?): Int = e["count"].int ?: 0

/** `POST /api/jira/watchers/:id/pull`. */
fun parseJiraPulled(e: JsonElement?): Int = e["newIssues"].int ?: 0
