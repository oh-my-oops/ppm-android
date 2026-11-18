package com.hienlh.ppm.state.tasks

import com.hienlh.ppm.client.TaskListQuery
import com.hienlh.ppm.protocol.TaskCheckSummary
import com.hienlh.ppm.protocol.TaskHistoryChange
import com.hienlh.ppm.protocol.TaskHistoryItem
import com.hienlh.ppm.protocol.TaskActor
import com.hienlh.ppm.protocol.TaskLink
import com.hienlh.ppm.protocol.TaskLinkState
import com.hienlh.ppm.protocol.TaskMember
import com.hienlh.ppm.protocol.TaskSessionState
import com.hienlh.ppm.protocol.TaskStatusDef
import com.hienlh.ppm.protocol.TaskWorkingSession
import com.hienlh.ppm.protocol.WireJson
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** The shared task rules, against the web's own examples (`shared/ppm-tasks-*.ts`, `ppm-tasks-kit-logic.ts`). */
class TaskRulesTest {
    @Test
    fun `days, due labels and short days read as on the web`() {
        assertEquals(3, daysBetween("2026-11-18", "2026-11-21"))
        assertEquals(-2, daysBetween("2026-11-18", "2026-11-16"))
        assertNull(daysBetween("2026-11-18", "soon"))
        assertEquals("2026-12-01", addDaysYmd("2026-11-30", 1))
        assertEquals("Fri 20 Nov", shortDay("2026-11-20", "2026-11-18"))
        assertEquals("Fri 1 Jan 2027", shortDay("2027-01-01", "2026-11-18"))
        assertEquals(DueView("Overdue 2d", soon = true, overdue = true), dueView("2026-11-16", "2026-11-18"))
        assertEquals("Today", dueView("2026-11-18", "2026-11-18")?.label)
        assertEquals("Tomorrow", dueView("2026-11-19", "2026-11-18")?.label)
        assertEquals("Mon", dueView("2026-11-23", "2026-11-18")?.label)
        assertEquals("30 Nov", dueView("2026-11-30", "2026-11-18")?.label)
        assertEquals("Due in 3 days", dueText("2026-11-21", "2026-11-18"))
        assertEquals("Overdue (2026-11-14)", dueText("2026-11-14", "2026-11-18"))
        assertEquals("now", urgencyFloorForDue("2026-11-18", "2026-11-18"))
        assertEquals("week", urgencyFloorForDue("2026-11-24", "2026-11-18"))
        assertNull(urgencyFloorForDue("2026-11-25", "2026-11-18"))
    }

    @Test
    fun `ages, initials and tones`() {
        assertEquals("now", ageShort(0, 59_000))
        assertEquals("5m", ageShort(0, 5 * 60_000))
        assertEquals("3h", ageShort(0, 3 * 3_600_000))
        assertEquals("2d", ageShort(0, 2 * 86_400_000L))
        assertEquals("3w", ageShort(0, 21 * 86_400_000L))
        assertEquals("VN", memberInitials("Việt  Nguyễn"))
        assertEquals("AL", memberInitials("alden"))
        assertEquals("?", memberInitials("  "))
        // The web's `(h * 31 + c) >>> 0` then `% 5 + 1`.
        assertEquals(1, memberTone("me"))
        assertEquals("You", memberName("me", null))
        assertEquals("Việt", memberName("m1", listOf(TaskMember("m1", "Việt", null, null, null, 0, null))))
        assertEquals("m9", memberName("m9", emptyList()))
    }

    @Test
    fun `a wait names whom it is on and how long`() {
        val mine = task(waitingOnMemberId = "me", baseGroup = "active").copy(waitingSince = 1_000)
        val d = taskWaiting(mine)!!
        assertEquals("Waiting on you", d.label)
        assertTrue(d.mine)
        assertEquals("Waiting on you for 2d", waitingTitle(d, 1_000 + 2 * 86_400_000L))
        val blocked = task(baseGroup = "waiting", statusName = "Blocked", waitingOnMemberId = "m1").copy(waitingOnName = "Việt", dueDate = "2026-11-20")
        assertEquals("Blocked on Việt", taskWaiting(blocked)!!.label)
        assertEquals("Blocked on Việt, due 2026-11-20", waitingTitle(taskWaiting(blocked)!!))
        assertEquals("Waiting", taskWaiting(task(baseGroup = "waiting", statusName = "Waiting"))!!.label)
        assertNull(taskWaiting(task(baseGroup = "closed", waitingOnMemberId = "me")))
        assertTrue(isWaitingOnMe(mine))
    }

    @Test
    fun `snooze words, badge and choices`() {
        val today = "2026-11-18"
        val t = task(snoozed = true, snoozedUntil = "2026-11-20")
        assertEquals("Snoozed until Fri 20 Nov", snoozedLabel(t, today))
        assertEquals("Until Fri 20 Nov", snoozeBadgeText(t, today))
        assertEquals("Fri 20 Nov", snoozeBadgeText(t, today, compact = true))
        assertEquals("Back today", snoozeBadgeText(task().copy(resurfacedToday = true), today))
        assertEquals("ppm/1 snoozed until Fri 20 Nov", snoozeToastText(t, today))
        assertEquals("ppm/1 is back in the list", snoozeToastText(task(), today))
        val choices = snoozeChoices(t, today)
        assertEquals(listOf("tomorrow", "week", "date", "link", "clear"), choices.map { it.id })
        assertEquals("Thu 19 Nov", choices[0].detail)
        assertEquals(SnoozePatch("2026-11-25", false), choices[1].patch)
        assertTrue(choices[3].disabled)
        assertEquals("Needs a Jira or PR link", choices[3].detail)
        val linked = snoozeChoices(task(links = listOf(TaskLink("pr", "o/r#1", null))), today)
        assertFalse(linked[3].disabled)
        assertEquals(4, linked.size)
        assertTrue(isSnoozeDay("2026-11-19", today))
        assertFalse(isSnoozeDay("2026-11-18", today))
    }

    @Test
    fun `codes, Jira keys, PRs and link addresses`() {
        assertEquals(ParsedTaskRef("ppm", 12), parseTaskRef("ppm/#12"))
        assertEquals(ParsedTaskRef(null, 12), parseTaskRef("12"))
        assertNull(parseTaskCodeQuery("12"))
        assertEquals(ParsedTaskRef(null, 82), parseTaskCodeQuery(" #82 "))
        assertEquals("#3", taskCode(task(number = 3), "ppm"))
        assertEquals("ppm/3", taskCode(task(number = 3), null))
        assertTrue(matchesTaskCode(task(number = 3), ParsedTaskRef("PPM", 3)))
        val tasks = listOf(task(id = "a", number = 3), task(id = "b", number = 3, project = "web"))
        assertEquals(listOf("a"), tasksMatchingCode(tasks, ParsedTaskRef(null, 3), "ppm").map { it.id })
        assertEquals(listOf("a", "b"), tasksMatchingCode(tasks, ParsedTaskRef(null, 3), "other", anywhere = true).map { it.id })
        assertEquals("NX-6318", parseJiraKey(" nx-6318 "))
        assertNull(parseJiraKey("NX6318"))
        assertEquals("o/r#4", canonicalPrRef("https://github.com/o/r/pull/4/files"))
        assertEquals("https://github.com/o/r/pull/4", taskLinkWebHref(TaskLink("pr", "o/r#4", null), null))
        assertEquals("https://x.atlassian.net/jira/browse/NX-1", taskLinkWebHref(TaskLink("jira", "NX-1", null), "https://x.atlassian.net/jira/"))
        assertNull(taskLinkWebHref(TaskLink("jira", "NX-1", null), null))
        assertNull(taskLinkWebHref(TaskLink("url", "javascript:alert(1)", null), null))
        assertNull(jiraSiteUrl("https://u:p@x.atlassian.net"))
        assertEquals("NX-6318" to "https://x.atlassian.net", parseJiraIssueUrl("https://x.atlassian.net/browse/NX-6318"))
    }

    @Test
    fun `the working-session chip prefers the check of a handed-off chat`() {
        val s = TaskSessionState("c1", "idle", "Login chat", null, null, null)
        assertEquals("Start chat", sessionChipView(TaskWorkingSession.START_CHAT, null, 0).label)
        assertEquals("idle", sessionChipView(TaskWorkingSession("session", s), null, 0).tone)
        val stalled = sessionChipView(TaskWorkingSession("session", s), TaskCheckSummary("stalled", 0, null), 40 * 60_000L)
        assertEquals("stalled", stalled.tone)
        assertEquals("stalled 40 min", stalled.detail)
        val mgr = sessionChipView(TaskWorkingSession("manager", s.copy(state = "running")), TaskCheckSummary("stalled", 0, null), 0)
        assertEquals("mgr", mgr.tone)
        assertEquals("Manager", mgr.label)
        assertEquals("need", sessionChipView(TaskWorkingSession("session", s.copy(state = "waiting_card")), null, 0).tone)
        assertEquals("gone", sessionChipView(TaskWorkingSession("session", s.copy(state = "gone")), null, 0).tone)
    }

    @Test
    fun `link chips read the live state`() {
        fun live(json: String) = TaskLinkState("x", "x", WireJson.parse(json), null, null, null)
        assertEquals(LinkStateView("NX-1", "Done", "ok"), linkStateView(TaskLink("jira", "NX-1", null), live("""{"status":"Done","category":"done"}""")))
        assertEquals(LinkStateView("r#4", "open · CI failing", "bad"), linkStateView(TaskLink("pr", "o/r#4", null), live("""{"state":"open","checks":{"failing":1}}""")))
        assertEquals(LinkStateView("r#4", "merged", "merge"), linkStateView(TaskLink("pr", "o/r#4", null), live("""{"state":"merged"}""")))
        assertEquals(LinkStateView("x.dev", null, "idle"), linkStateView(TaskLink("url", "https://x.dev/a", null), null))
        assertEquals(LinkStateView("NX-1", "…", "idle"), linkStateView(TaskLink("jira", "NX-1", null), null))
        assertEquals(LinkStateView("Chat A", "waiting on you", "bad"), linkStateView(TaskLink("session", "c1", null), live("""{"state":"waiting_card","title":"Chat A"}""")))
    }

    @Test
    fun `reminder choices follow the zone's clock`() {
        val zone = ZoneId.of("Europe/London")
        val morning = reminderChoices(Instant.parse("2026-11-18T09:02:00Z"), zone)
        assertEquals(listOf("hour", "evening", "morning"), morning.map { it.id })
        assertEquals("2026-11-18T10:05", morning[0].at)
        assertEquals("2026-11-18T18:00", morning[1].at)
        assertEquals("2026-11-19T09:00", morning[2].at)
        assertEquals(listOf("hour", "morning"), reminderChoices(Instant.parse("2026-11-18T17:30:00Z"), zone).map { it.id })
        assertEquals("18:00", reminderTimeText("2026-11-18T18:00"))
    }

    @Test
    fun `history rows in words`() {
        val statuses = listOf(TaskStatusDef("s1", "To do", "not_started", null, 0), TaskStatusDef("s9", "Done", "closed", "done", 9))
        fun item(kind: String, vararg changes: TaskHistoryChange) =
            TaskHistoryItem(1, "t", 0, TaskActor("manager", "Manager ppm", null), "tool", false, kind, changes.toList(), null, null, null, true)
        fun ch(field: String, before: String?, after: String?) =
            TaskHistoryChange(field, before?.let(WireJson::parse), after?.let(WireJson::parse))
        assertEquals("changed urgency This week → Now", historyEntryText(item("updated", ch("urgency", "\"week\"", "\"now\"")), statuses, null))
        assertEquals("closed it as Done", historyEntryText(item("closed", ch("statusId", "\"s1\"", "\"s9\"")), statuses, null))
        assertEquals("edited notes", historyEntryText(item("updated", ch("notes", null, "\"x\"")), statuses, null))
        assertEquals("linked NX-2", historyEntryText(item("updated", ch("links", "[]", """[{"ref":"NX-2"}]""")), statuses, null))
        assertEquals("set due date to 2026-11-20", historyEntryText(item("updated", ch("dueDate", null, "\"2026-11-20\"")), statuses, null))
        assertEquals("snoozed it until Fri 20 Nov", historyEntryText(item("snoozed", ch("snoozedUntil", null, "\"2026-11-20\"")), statuses, null))
        assertEquals("chose B — Wait", historyEntryText(item("decided", ch("decision", null, """{"options":[{"id":"a","label":"Ship"},{"id":"b","label":"Wait"}],"chosen":{"optionId":"b"}}""")), statuses, null))
        assertEquals("proposed closing it as Dropped", historyEntryText(item("close_proposed", TaskHistoryChange("closeProposal", null, JsonPrimitive("dropped"))), statuses, null))
        assertTrue(isAiEntry(item("updated")))
        assertEquals("from Telegram", viaText("telegram"))
    }

    @Test
    fun `decision delivery messages`() {
        assertEquals("Choice sent to Login chat: it started working on it", decisionDeliveredMessage("started", "Login chat"))
        assertEquals("Choice sent to the chat: queued behind its current turn or a card waiting on you", decisionDeliveredMessage("queued", null))
        assertEquals("Choice recorded", decisionDeliveredMessage(null, "x"))
        assertEquals("C", optionLetter(2))
    }

    @Test
    fun `the list query names the filters and a code asks for the whole scope`() {
        val f = TaskViewFilters(groups = listOf("active"), person = "me")
        assertEquals(TaskListQuery("ppm", groups = listOf("active"), person = "me", q = "login", closed = true, snoozed = true), listQueryFor("project", "ppm", f, " login "))
        assertEquals(TaskListQuery("all", closed = true, snoozed = true), listQueryFor("all", null, f, "#4"))
        assertNull(listQueryFor("project", null, f, ""))
        assertEquals(listKey(TaskListQuery("p", groups = listOf("b", "a"))), listKey(TaskListQuery("p", groups = listOf("a", "b"))))
    }
}
