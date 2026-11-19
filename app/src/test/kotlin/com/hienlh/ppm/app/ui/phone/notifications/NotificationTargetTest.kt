package com.hienlh.ppm.app.ui.phone.notifications

import com.hienlh.ppm.client.FeedKind
import com.hienlh.ppm.client.FeedRow
import com.hienlh.ppm.protocol.WireJson
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a notification feed row leads, now that schedule notifications record their schedule (`schedule_id`). */
class NotificationTargetTest {
    private fun row(sessionId: String? = null, scheduleId: String? = null) =
        FeedRow(id = 1L, kind = FeedKind.SCHEDULE, project = "ppm", sessionId = sessionId, scheduleId = scheduleId, title = "Schedule finished")

    @Test
    fun `a script's run summary names only its schedule, which the row opens`() {
        // As the server records it: `scheduleId: String(schedule.id)` and an empty session.
        val parsed = FeedRow.from(WireJson.parse("""{"id":9,"kind":"schedule","project":"ppm","sessionId":"","scheduleId":"42","title":"watch · exit 0"}"""))!!
        assertEquals(NotificationTarget.Schedule(42), notificationTarget(parsed))
        assertEquals("Open schedule", notificationOpenLabel(parsed))
    }

    @Test
    fun `a notification about a chat still opens the chat, schedule or not`() {
        assertEquals(NotificationTarget.Chat("s-1"), notificationTarget(row(sessionId = "s-1", scheduleId = "7")))
        assertEquals("Open chat", notificationOpenLabel(row(sessionId = "s-1", scheduleId = "7")))
    }

    @Test
    fun `a row with neither, or a schedule id that is not one, opens its project`() {
        assertEquals(NotificationTarget.Project, notificationTarget(row()))
        assertEquals(NotificationTarget.Project, notificationTarget(row(scheduleId = "abc")))
        assertEquals("Open project", notificationOpenLabel(row()))
    }
}
