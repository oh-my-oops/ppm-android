package com.hienlh.ppm.state.schedules

import com.hienlh.ppm.protocol.WireJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** The Schedules window's rules, against the web's (`schedule-attention.ts`, `schedules-view.ts`). */
class SchedulesStateTest {
    private val now = Instant.parse("2026-11-18T10:00:00Z").toEpochMilli()
    private val utc = ZoneId.of("UTC")

    private fun s(
        id: Int = 1,
        enabled: Boolean = true,
        kind: String = "prompt",
        mode: String = "tick",
        next: String? = null,
        pending: String? = null,
        paused: String? = null,
        run: ScheduleLastRun? = null,
        dismissed: String? = null,
        mode2: String = "default",
        project: String? = "ppm",
        updated: String? = "2026-11-18 09:00:00",
    ) = ScheduleOverview(
        id = id, name = "S$id", kind = kind, mode = mode, enabled = enabled, nextFireAt = next, pendingSince = pending, pausedReason = paused,
        lastRun = run, attentionDismissed = dismissed, effectivePermissionMode = mode2, projectName = project, updatedAt = updated,
        projectPath = "/home/u/ppm", timeoutMs = 600_000,
    )

    @Test
    fun `a stamp in SQLite's form is UTC, an ISO one passes, and nonsense reads as nothing`() {
        assertEquals(now, parseStamp("2026-11-18 10:00:00"))
        assertEquals(now, parseStamp("2026-11-18 10:00"))
        assertEquals(now, parseStamp("2026-11-18T10:00:00.000Z"))
        assertNull(parseStamp("soon"))
    }

    @Test
    fun `a problem is paused, then waiting, then stuck, then failed, and a dismissed one has no dot`() {
        assertNull(scheduleProblem(s(enabled = false, paused = "gone"), now))
        val paused = scheduleProblem(s(paused = "Its chat is gone"), now)!!
        assertEquals(AttentionKind.PAUSED, paused.kind)
        assertEquals("paused:Its chat is gone@2026-11-18 09:00:00", paused.key)
        // Five minutes is not long; forty is.
        assertNull(scheduleProblem(s(pending = "2026-11-18 09:55:00"), now))
        val waiting = scheduleProblem(s(pending = "2026-11-18 09:20:00"), now)!!
        assertEquals("Waiting for its chat 40 min", waiting.text)
        assertEquals("waiting:2026-11-18 09:20:00", waiting.key)
        val stuck = scheduleProblem(s(run = ScheduleLastRun(7, "running", "2026-11-18 09:00:00")), now)!!
        assertEquals("Stuck: running 60 min", stuck.text)
        assertEquals("Its time limit is 10 min.", stuck.detail)
        // An always-on process is meant to run for good.
        assertNull(scheduleProblem(s(mode = "always-on", run = ScheduleLastRun(7, "running", "2026-11-18 09:00:00")), now))
        val failed = s(run = ScheduleLastRun(9, "error", "2026-11-18 09:00:00", "2026-11-18 09:01:00", "boom"))
        assertEquals("failed:9", scheduleProblem(failed, now)!!.key)
        assertEquals("Last run failed", scheduleAttention(failed, now)!!.text)
        assertNull(scheduleAttention(failed.copy(attentionDismissed = "failed:9"), now))
        // A newer failure is a different problem: the dot comes back.
        assertTrue(scheduleAttention(failed.copy(attentionDismissed = "failed:8"), now) != null)
    }

    @Test
    fun `the rail counts what fires within a day or waits, and the problems not dismissed`() {
        val list = listOf(
            s(1, next = "2026-11-18T18:00:00Z"),
            s(2, next = "2026-11-20T18:00:00Z"),
            s(3, pending = "2026-11-18 09:59:00"),
            s(4, mode = "always-on", next = "2026-11-18T11:00:00Z"),
            s(5, enabled = false, next = "2026-11-18T11:00:00Z"),
            s(6, run = ScheduleLastRun(1, "error", "2026-11-18 08:00:00")),
        )
        assertEquals(RailSummary(upcoming = 2, attention = 1), railSummary(list, now))
        assertEquals("Schedules · 2 in the next 24 h · 1 needs attention", railLabel(railSummary(list, now)))
        assertEquals("Schedules · 0 in the next 24 h", railLabel(RailSummary(0, 0)))
    }

    @Test
    fun `groups come in a fixed order, soonest first, empty ones left out`() {
        val list = listOf(
            s(1, next = "2026-11-18T18:00:00Z"),
            s(2, next = "2026-11-18T12:00:00Z"),
            s(3, next = "2026-11-25T12:00:00Z"),
            s(4, enabled = false),
            s(5, run = ScheduleLastRun(1, "error", "2026-11-18 08:00:00")),
        )
        val groups = groupSchedules(list, now)
        assertEquals(listOf("Needs attention", "Next 24 hours", "Later", "Off"), groups.map { it.label })
        assertEquals(listOf(2, 1), groups[1].items.map { it.id })
        assertEquals(listOf(ScheduleGroupId.UPCOMING, ScheduleGroupId.LATER), groupSchedules(list.take(3), now).map { it.id })
    }

    @Test
    fun `chips, the project and a search filter the list, and each chip counts inside the project`() {
        val list = listOf(
            s(1, next = "2026-11-18T18:00:00Z", mode2 = "bypassPermissions"),
            s(2, enabled = false, project = "other"),
            s(3, next = "2026-11-30T18:00:00Z").copy(name = "Nightly report"),
        )
        assertEquals(listOf(1), filterSchedules(list, SchedulesFilter(chip = ScheduleChip.BYPASS), now).map { it.id })
        assertEquals(listOf(2), filterSchedules(list, SchedulesFilter(project = "other"), now).map { it.id })
        assertEquals(listOf(3), filterSchedules(list, SchedulesFilter(query = "nightly"), now).map { it.id })
        val counts = chipCounts(list, "ppm", now)
        assertEquals(2, counts[ScheduleChip.ALL])
        assertEquals(1, counts[ScheduleChip.UPCOMING])
        assertEquals(0, counts[ScheduleChip.OFF])
        assertEquals(listOf("other", "ppm"), projectsOf(list))
        assertEquals("ppm", projectLabel(s(project = null)))
    }

    @Test
    fun `times read as the web's, on the schedule's own clock`() {
        assertEquals("now", relativeIn(now + 30_000, now))
        assertEquals("in 3m", relativeIn(now + 3 * 60_000, now))
        assertEquals("in 8h 10m", relativeIn(now + (8 * 60 + 10) * 60_000L, now))
        assertEquals("in 2d 22h", relativeIn(now + (70 * 60) * 60_000L, now))
        assertEquals("just now", relativeAgo(now - 10_000, now))
        assertEquals("3h ago", relativeAgo(now - 3 * 3_600_000L, now))
        assertEquals("2d ago", relativeAgo(now - 49 * 3_600_000L, now))
        assertEquals("18:30", clockLabel(Instant.parse("2026-11-18T18:30:00Z").toEpochMilli(), null, now, utc))
        assertEquals("tomorrow 02:00", clockLabel(Instant.parse("2026-11-19T02:00:00Z").toEpochMilli(), null, now, utc))
        assertEquals("Fri 16:00", clockLabel(Instant.parse("2026-11-20T16:00:00Z").toEpochMilli(), null, now, utc))
        assertEquals("30 Nov 09:00", clockLabel(Instant.parse("2026-11-30T09:00:00Z").toEpochMilli(), null, now, utc))
        assertEquals("18:30 London", clockLabel(Instant.parse("2026-11-18T18:30:00Z").toEpochMilli(), "Europe/London", now, utc))
        val next = nextRunLabel(s(next = "2026-11-18T18:30:00Z"), now, utc)
        assertEquals(WhenLabel("18:30", "in 8h 30m"), next)
        assertEquals(" · once", nextRunLabel(s(kind = "session", next = "2026-11-18T18:30:00Z").copy(recurring = false), now, utc).sub.removePrefix("in 8h 30m"))
        assertEquals(WhenLabel("Due now", "waiting for its chat"), nextRunLabel(s(pending = "2026-11-18 09:59:00"), now, utc))
        assertEquals(WhenLabel("Off", ""), nextRunLabel(s(enabled = false), now, utc))
    }

    @Test
    fun `the last run says what happened in a word or two`() {
        assertEquals(RunLine(RunTone.NONE, "Not run yet"), lastRunLabel(s(), now))
        assertEquals(RunLine(RunTone.OK, "Done", "1h ago"), lastRunLabel(s(run = ScheduleLastRun(1, "done", "2026-11-18 08:58:00", "2026-11-18 09:00:00")), now))
        assertEquals("boom", lastRunLabel(s(run = ScheduleLastRun(1, "error", "2026-11-18 08:58:00", "2026-11-18 09:00:00", "boom\nstack")), now).text)
        assertEquals(RunTone.RUNNING, lastRunLabel(s(run = ScheduleLastRun(1, "running", "2026-11-18 09:58:00")), now).tone)
        assertEquals("2m", lastRunLabel(s(run = ScheduleLastRun(1, "running", "2026-11-18 09:58:00")), now).whenText)
        assertEquals(RunLine(RunTone.BAD, "Paused"), lastRunLabel(s(paused = "x"), now))
        assertEquals("42s", runDuration("2026-11-18 09:00:00", "2026-11-18 09:00:42"))
        assertEquals("3m", runDuration("2026-11-18 09:00:00", "2026-11-18 09:03:00"))
        assertEquals("running", runDuration("2026-11-18 09:00:00", null))
    }

    @Test
    fun `an overview is read off the wire with its run, its task and the mode its turns run in`() {
        val list = parseScheduleOverviews(
            WireJson.parse(
                """[{"id":4,"name":"Remind","kind":"session","mode":"tick","cron_expr":"0 9 * * *","provider_id":"claude",
                "project_path":"/p","prompt":"hi","permission_mode":"default","timeout_ms":60000,"enabled":1,"recurring":0,
                "target_session_id":"abc","target_title":"Chat","timezone":"Europe/London","pending_since":null,"paused_reason":null,
                "attention_dismissed":"failed:2","status":{"state":"idle"},
                "last_run":{"id":2,"status":"error","started_at":"2026-11-18 08:00:00","ended_at":null,"error":"x","session_id":"s1"},
                "project_name":"ppm","effective_permission_mode":"bypassPermissions","next_fires":["2026-11-19T09:00:00.000Z"],
                "task":{"id":"t1","ref":"ppm/12","title":"Fix","project":"ppm","closed":false}}]""",
            ),
        )
        val o = list.single()
        assertEquals("Remind", o.name)
        assertTrue(o.enabled)
        assertFalse(o.recurring)
        assertEquals("bypassPermissions", o.effectivePermissionMode)
        assertEquals(12, o.task?.number)
        assertEquals("error", o.lastRun?.status)
        assertEquals("abc", chatOf(o))
        assertEquals(listOf("2026-11-19T09:00:00.000Z"), o.nextFires)
        assertNull(scheduleAttention(o, now))
    }

    @Test
    fun `there is one Schedules tab, so a second open focuses it`() {
        assertEquals("schedules", com.hienlh.ppm.state.workspace.deriveTabId("schedules", kotlinx.serialization.json.JsonObject(emptyMap())))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a burst of changes shares one read and one more after it, and a local write is not undone by an older read`() {
        val scope = TestScope(StandardTestDispatcher())
        var reads = 0
        var gate = CompletableDeferred<List<ScheduleOverview>>()
        val feed = SchedulesFeed(scope, { reads++; gate.await() }, { now })
        feed.refetch()
        scope.runCurrent()
        feed.refetch()
        feed.refetch()
        assertEquals(1, reads)
        // A write lands while the read is out: the read's answer is stale.
        feed.put(s(9))
        val first = gate
        gate = CompletableDeferred()
        first.complete(listOf(s(1)))
        scope.runCurrent()
        assertEquals(2, reads)
        assertEquals(listOf(9), feed.state.value.list.map { it.id })
        gate.complete(listOf(s(1), s(9)))
        scope.runCurrent()
        assertEquals(listOf(1, 9), feed.state.value.list.map { it.id })
        assertTrue(feed.state.value.loaded)
        feed.remove(1)
        assertEquals(listOf(9), feed.state.value.list.map { it.id })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a failed first read says why, a failed later one keeps the list`() {
        val scope = TestScope(StandardTestDispatcher())
        var fail = true
        val feed = SchedulesFeed(scope, { if (fail) error("Down") else listOf(s(1)) }, { now })
        feed.refetch()
        scope.runCurrent()
        assertEquals("Down", feed.state.value.error)
        fail = false
        feed.refetch()
        scope.runCurrent()
        assertNull(feed.state.value.error)
        fail = true
        feed.refetch()
        scope.runCurrent()
        assertNull(feed.state.value.error)
        assertEquals(1, feed.state.value.list.size)
    }
}
