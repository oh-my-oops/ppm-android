package com.hienlh.ppm.state.jira

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.state.GlobalEvents
import com.hienlh.ppm.state.settings.parseJiraWatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** The Jira panel's words and parsers, against `src/web/components/jira/`. */
class JiraViewTest {
    private val now = Instant.parse("2026-11-18T10:00:00Z").toEpochMilli()

    @Test
    fun `the builder quotes every value but projects, and orders newest first`() {
        assertEquals("ORDER BY updated DESC", filtersToJql(emptyMap()))
        assertEquals(
            "project IN (PPM, WEB) AND issuetype IN (\"Bug\") AND assignee IN (\"acc-1\") ORDER BY updated DESC",
            filtersToJql(
                mapOf(
                    JiraFilterField.PROJECT to listOf("PPM", "WEB"),
                    JiraFilterField.ISSUE_TYPE to listOf("Bug"),
                    JiraFilterField.STATUS to emptyList(),
                    JiraFilterField.ASSIGNEE to listOf("acc-1"),
                ),
            ),
        )
    }

    @Test
    fun `a ticket's age, the debug prompt and the interval read as the web's`() {
        assertEquals("now", jiraTimeAgo("2026-11-18 09:59:30", now))
        assertEquals("5m", jiraTimeAgo("2026-11-18 09:55:00", now))
        assertEquals("3h", jiraTimeAgo("2026-11-18 06:30:00", now))
        assertEquals("2d", jiraTimeAgo("2026-11-16 09:00:00", now))
        assertEquals("Debug Jira issue PPM-1: Crash", jiraDebugPrompt(null, "PPM-1", "Crash"))
        assertEquals("Fix PPM-1 (Crash) PPM-1", jiraDebugPrompt("Fix {issue_key} ({summary}) {issue_key}", "PPM-1", "Crash"))
        assertEquals("Fix PPM-1 ()", jiraDebugPrompt("Fix {issue_key} ({summary})", "PPM-1", null))
        assertEquals("30s", formatJiraInterval(30_000))
        assertEquals("2m", formatJiraInterval(120_000))
        assertEquals("1h", formatJiraInterval(3_600_000))
        assertEquals("1.5h", formatJiraInterval(5_400_000))
        assertEquals("Pulled 1 new issue", jiraPulledText(1))
        assertEquals("Pulled 0 new issues", jiraPulledText(0))
        assertTrue(isUnreadJiraResult("done", null))
        assertFalse(isUnreadJiraResult("done", "2026-11-18 09:00:00"))
        assertFalse(isUnreadJiraResult("failed", null))
    }

    @Test
    fun `a ticket is read with its document description as indented JSON`() {
        val issue = parseJiraIssue(
            WireJson.parse(
                """{"key":"PPM-1","id":"1","fields":{"summary":"Crash","description":{"type":"doc","version":1},
                "status":{"name":"To Do","id":"1"},"priority":null,"assignee":{"accountId":"a","displayName":"An"},
                "updated":"2026-11-18T10:00:00.000+0000"}}""",
            ),
        )!!
        assertEquals("Crash", issue.summary)
        assertEquals("{\n  \"type\": \"doc\",\n  \"version\": 1\n}", issue.description)
        assertEquals("To Do", issue.statusName)
        assertNull(issue.priorityName)
        assertEquals("An", issue.assigneeName)
        assertNull(parseJiraIssue(WireJson.parse("""{"key":"X-1","fields":{"description":null}}"""))!!.description)
        assertTrue(jiraUpdatedLabel(issue.updated, ZoneId.of("UTC")).contains("2026"))
        val transitions = parseJiraTransitions(WireJson.parse("""[{"id":"31","name":"Start","to":{"name":"In Progress"}}]"""))
        assertEquals("Start → In Progress", transitions.single().label)
    }

    @Test
    fun `the builder's choices and the test's preview come off the wire`() {
        assertEquals(listOf(JiraFieldOption("PPM", "PPM — Project")), parseJiraMetadataProjects(WireJson.parse("""[{"key":"PPM","name":"Project"}]""")))
        assertEquals(listOf(JiraFieldOption("Bug", "Bug")), parseJiraNamedOptions(WireJson.parse("""[{"id":"1","name":"Bug"}]""")))
        assertEquals(listOf(JiraFieldOption("a1", "An")), parseJiraAssigneeOptions(WireJson.parse("""[{"accountId":"a1","displayName":"An"}]""")))
        val test = parseJiraTestResult(WireJson.parse("""{"issues":[{"key":"PPM-1","fields":{"summary":"S","status":{"name":"Done"}}}],"total":12}"""))
        assertEquals("12 tickets found (showing 1)", jiraTestHeading(test))
        assertEquals("1 ticket found", jiraTestHeading(JiraTestResult(test.issues, 1)))
        assertEquals(4, parseJiraUnreadCount(WireJson.parse("""{"count":4}""")))
        assertEquals(2, parseJiraPulled(WireJson.parse("""{"polled":true,"newIssues":2}""")))
    }

    @Test
    fun `a watcher names its config the way the server sends it`() {
        val w = parseJiraWatchers(WireJson.parse("""[{"id":1,"jiraConfigId":7,"name":"Bugs","jql":"x","enabled":false,"mode":"debug","intervalMs":60000}]""")).single()
        assertEquals(7, w.configId)
        assertFalse(w.enabled)
    }

    @Test
    fun `every jira event reaches its listeners with its type`() {
        val events = GlobalEvents(ApiClient("http://127.0.0.1:9/".toHttpUrl(), "t"), CoroutineScope(Job()))
        val seen = mutableListOf<String>()
        val off = events.onJiraEvent { type, _ -> seen += type }
        events.handle(WireJson.parse("""{"type":"jira:watchers_changed"}"""))
        events.handle(WireJson.parse("""{"type":"jira:status_change","resultId":1,"issueKey":"PPM-1","status":"done"}"""))
        events.handle(WireJson.parse("""{"type":"schedules:changed"}"""))
        off()
        events.handle(WireJson.parse("""{"type":"jira:config_changed"}"""))
        assertEquals(listOf("jira:watchers_changed", "jira:status_change"), seen)
    }
}
