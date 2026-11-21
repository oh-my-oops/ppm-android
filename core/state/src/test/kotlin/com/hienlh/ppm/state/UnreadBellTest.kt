package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.SessionUnread
import org.junit.Assert.assertEquals
import org.junit.Test

/** The bell's list of unread sessions (notification-bell-popover.tsx). */
class UnreadBellTest {
    private fun unread(type: String, project: String, title: String?) = SessionUnread(type = type, count = 1, projectName = project, sessionTitle = title)

    @Test
    fun `sessions are grouped by project in the order they arrived, one row per session named by its title`() {
        val groups = groupUnreadSessions(
            linkedMapOf(
                "s1" to unread("done", "ppm", "Fix the bell"),
                "s2" to unread("approval_request", "api", "Deploy"),
                "s3" to unread("question", "ppm", "Which colour?"),
                "s4" to unread("collab_approval", "", null),
            ),
        )
        assertEquals(listOf("ppm", "api", "Unknown"), groups.map { it.project })
        assertEquals(
            listOf(UnreadBellRow("s1", "Fix the bell", UnreadKind.DONE), UnreadBellRow("s3", "Which colour?", UnreadKind.QUESTION)),
            groups[0].rows,
        )
        assertEquals(UnreadKind.APPROVAL, groups[1].rows.single().kind)
        assertEquals(UnreadKind.APPROVAL, groups[2].rows.single().kind)
    }

    @Test
    fun `a session with no title shows its id's first 12 characters`() {
        assertEquals("0123456789ab...", unreadBellTitle("0123456789abcdef", null))
        assertEquals("0123456789ab...", unreadBellTitle("0123456789abcdef", ""))
        assertEquals("short...", unreadBellTitle("short", null))
        assertEquals("Named", unreadBellTitle("0123456789abcdef", "Named"))
    }

    @Test
    fun `an unknown type is tinted as the web tints it, never dropped`() {
        assertEquals(UnreadKind.OTHER, UnreadKind.of("something_new"))
        assertEquals(UnreadKind.OTHER, UnreadKind.of(null))
        assertEquals(emptyList<UnreadBellGroup>(), groupUnreadSessions(emptyMap()))
    }
}
