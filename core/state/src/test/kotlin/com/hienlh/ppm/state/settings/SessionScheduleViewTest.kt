package com.hienlh.ppm.state.settings

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.state.GlobalEvents
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `cron-in-words.ts`, `session-schedule-view.ts` and the "Chat" kind of `schedule-form-model.ts`. */
class SessionScheduleViewTest {
    private val now = Instant.parse("2026-11-16T10:00:00Z")
    private val utc = ZoneId.of("UTC")

    @Test
    fun `a cron reads in words as the web writes it`() {
        // What `describeCron` in src/shared/cron-in-words.ts answered for each, run with bun.
        val expected = listOf(
            "* * * * *" to "Every minute",
            "*/15 * * * *" to "Every 15 minutes",
            "5 * * * *" to "Hourly at :05",
            "0 8 * * *" to "Daily at 08:00",
            "30 7 * * 1-5" to "Weekdays at 07:30",
            "0 9 * * 0,6" to "Weekends at 09:00",
            "0 9 * * 1,3,5" to "Mon, Wed, Fri at 09:00",
            "0 9 * * 1-3,5" to "Mon–Wed, Fri at 09:00",
            "0 9 1 * *" to "Monthly on day 1 at 09:00",
            "0 9 15 3 *" to "Mar 15 at 09:00",
            "0 9 * * 8" to "0 9 * * 8",
            "1,2 * * * *" to "1,2 * * * *",
            "0 8 * * 7" to "Sun at 08:00",
            "0 */2 * * *" to "0 */2 * * *",
            "0 9 1 13 *" to "0 9 1 13 *",
            "0 9 1 * 1" to "0 9 1 * 1",
            " 0  8 * * * " to "Daily at 08:00",
            "0 9 * * 5-1" to "0 9 * * 5-1",
        )
        for ((cron, words) in expected) assertEquals(cron, words, describeCron(cron))
    }

    @Test
    fun `a time is written in the schedule's zone, with the date only when it is not today there`() {
        assertEquals("London", zoneCity("Europe/London"))
        assertEquals("Buenos Aires", zoneCity("America/Argentina/Buenos_Aires"))
        assertEquals("UTC", zoneCity("UTC"))
        assertEquals("08:00 AM (London)", formatInZone("2026-11-16T08:00:00Z", "Europe/London", now, deviceZone = utc))
        assertEquals("Nov 17, 08:00 AM (London)", formatInZone("2026-11-17T08:00:00.000Z", "Europe/London", now, deviceZone = utc))
        // 23:30 in London on the 16th is the 17th in Saigon, which is not "today" there.
        assertEquals("Nov 17, 06:30 AM (Saigon)", formatInZone("2026-11-16T23:30:00Z", "Asia/Saigon", now, deviceZone = utc))
        // No zone: the device's own time, unlabelled.
        assertEquals("08:00 AM", formatInZone("2026-11-16T08:00:00Z", null, now, deviceZone = utc))
        // A zone the device does not know: its own time with the date, still labelled.
        assertEquals("Nov 16, 08:00 AM (Nowhere)", formatInZone("2026-11-16T08:00:00Z", "Mars/Nowhere", now, deviceZone = utc))
        assertEquals("not a time", formatInZone("not a time", "Europe/London", now, deviceZone = utc))
    }

    private val row = ScheduleRowState(id = 1, name = "Morning", kind = "session", timezone = "Europe/London")

    @Test
    fun `a session schedule says what it is doing`() {
        fun status(r: ScheduleRowState) = sessionScheduleStatus(r, now, deviceZone = utc)
        assertEquals(SessionScheduleStatus("Active", SessionScheduleTone.OK), status(row))
        assertEquals(SessionScheduleStatus("Off", SessionScheduleTone.OFF), status(row.copy(enabled = false)))
        assertEquals(
            SessionScheduleStatus("Paused", SessionScheduleTone.BAD, "Its chat was deleted"),
            status(row.copy(enabled = false, pausedReason = "Its chat was deleted")),
        )
        assertEquals(
            SessionScheduleStatus("Waiting for the chat", SessionScheduleTone.WAIT, "Due since 08:00 AM (London); it is sent once the chat is idle."),
            status(row.copy(pendingSince = "2026-11-16T08:00:00.000Z")),
        )
        assertEquals(
            SessionScheduleStatus(
                "Held after Stop",
                SessionScheduleTone.WAIT,
                "Due since 08:00 AM (London); held after Stop until the chat's next turn ends or the next fire.",
            ),
            status(row.copy(pendingSince = "2026-11-16T08:00:00.000Z", heldAfterStop = true)),
        )
        assertFalse(needsAttention(row))
        assertTrue(needsAttention(row.copy(pausedReason = "gone")))
        assertTrue(needsAttention(row.copy(pendingSince = "2026-11-16T08:00:00Z")))
        assertFalse(needsAttention(row.copy(enabled = false, pendingSince = "2026-11-16T08:00:00Z")))
    }

    @Test
    fun `the next run is said, or why there is none`() {
        fun next(r: ScheduleRowState) = nextRunText(r, now, deviceZone = utc)
        assertEquals("Next: Nov 17, 08:00 AM (London)", next(row.copy(nextFireAt = "2026-11-17T08:00:00.000Z")))
        assertEquals("Not scheduled", next(row.copy(enabled = false, nextFireAt = "2026-11-17T08:00:00.000Z")))
        assertEquals("Not scheduled", next(row.copy(pausedReason = "gone")))
        assertEquals("Due now", next(row.copy(pendingSince = "2026-11-16T08:00:00Z")))
        assertEquals("No next run", next(row))
        assertEquals("sent late · later fires folded in", scheduleRunNote(catchUp = true, coalesced = true))
        assertEquals("later fires folded in", scheduleRunNote(catchUp = false, coalesced = true))
        assertNull(scheduleRunNote(catchUp = false, coalesced = false))
    }

    @Test
    fun `a session schedule's fields come off the server's view`() {
        val rows = parseSchedulesList(
            WireJson.parse(
                """[{"id":4,"name":"Standup","kind":"session","cron_expr":"0 9 * * 1-5","enabled":1,"target_session_id":"abcdef12",
                   "target_title":"Release chat","timezone":"Asia/Saigon","recurring":0,"pending_since":"2026-11-16T02:00:00.000Z",
                   "paused_reason":null,"held_after_stop":true,"status":{"state":"idle"}},
                  {"id":5,"name":"Old","kind":"prompt","enabled":0}]""",
            ),
        )
        val session = rows[0]
        assertTrue(session.isSession)
        assertEquals("abcdef12", session.targetSessionId)
        assertEquals("Release chat", session.targetTitle)
        assertEquals("Asia/Saigon", session.timezone)
        assertFalse(session.recurring)
        assertEquals("2026-11-16T02:00:00.000Z", session.pendingSince)
        assertNull(session.pausedReason)
        assertTrue(session.heldAfterStop)
        // A prompt schedule has none of them, and repeats.
        assertFalse(rows[1].isSession)
        assertTrue(rows[1].recurring)
        assertFalse(rows[1].heldAfterStop)
    }

    @Test
    fun `the Chat form asks for what the server would refuse, and sends only what a session schedule has`() {
        assertEquals(
            listOf("Pick a project", "Pick a chat", "A session schedule needs a prompt"),
            SessionScheduleForm(prompt = "  ").problems(),
        )
        val form = SessionScheduleForm(projectPath = "/p", targetSessionId = "abcdef12", prompt = "Summarise", name = " Daily ", cron = "0 7 * * *")
        assertEquals(emptyList<String>(), form.problems())
        assertEquals(
            listOf("A session schedule takes 5 cron fields: minute hour day month weekday"),
            form.copy(cron = "*/5 0 7 * * *").problems(),
        )
        assertEquals(listOf("A session schedule needs a cron expression"), form.copy(cron = " ").problems())
        assertEquals(listOf("timezone must be an IANA time zone such as Europe/London"), form.copy(timezone = "Mars/Base").problems())
        assertEquals(emptyList<String>(), form.copy(timezone = "europe/london").problems())
        assertEquals(
            """{"name":"Daily","prompt":"Summarise","cron_expr":"0 7 * * *","timezone":null,"recurring":true,"enabled":true,"kind":"session","target_session_id":"abcdef12"}""",
            form.requestBody().toString(),
        )
        assertEquals(
            """{"name":"","prompt":"Summarise","cron_expr":"0 7 * * *","timezone":"Asia/Saigon","recurring":false,"enabled":false,"kind":"session","target_session_id":"abcdef12"}""",
            form.copy(name = "", timezone = " Asia/Saigon ", recurring = false, enabled = false).requestBody().toString(),
        )
    }

    @Test
    fun `schedules changed on the global socket reaches its listeners`() {
        val events = GlobalEvents(ApiClient("http://127.0.0.1:9/".toHttpUrl(), "t"), CoroutineScope(Job()))
        var changed = 0
        val off = events.onSchedulesChanged { changed++ }
        events.handle(WireJson.parse("""{"type":"schedules:changed"}"""))
        off()
        events.handle(WireJson.parse("""{"type":"schedules:changed"}"""))
        assertEquals(1, changed)
    }
}
