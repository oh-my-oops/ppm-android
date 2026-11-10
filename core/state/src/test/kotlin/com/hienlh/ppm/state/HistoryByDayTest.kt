package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class HistoryByDayTest {

    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private val now = Instant.parse("2026-04-17T08:00:00Z") // 2026-04-17 15:00 +07

    @Test
    fun `groups sessions into Pinned, Today, Yesterday and dated buckets`() {
        val sessions = listOf(
            SessionInfo(
                id = "s-older",
                providerId = "claude",
                title = "Fix auth token refresh",
                updatedAt = Instant.parse("2026-04-14T05:00:00Z"),
                pinned = false,
            ),
            SessionInfo(
                id = "s-today-1",
                providerId = "claude",
                title = "Phone chat layout",
                updatedAt = Instant.parse("2026-04-17T06:00:00Z"),
                pinned = false,
            ),
            SessionInfo(
                id = "s-pinned",
                providerId = "codex",
                title = "Release checklist",
                updatedAt = Instant.parse("2026-04-10T04:00:00Z"),
                pinned = true,
            ),
            SessionInfo(
                id = "s-yesterday",
                providerId = "antigravity",
                title = "Database schema migration",
                updatedAt = Instant.parse("2026-04-16T07:00:00Z"),
                pinned = false,
            ),
            SessionInfo(
                id = "s-last-year",
                providerId = "claude",
                title = "Initial setup",
                updatedAt = Instant.parse("2025-12-20T07:00:00Z"),
                pinned = false,
            ),
        )

        val groups = HistoryByDay.groupSessionsByDay(sessions, now = now, zoneId = zone)

        assertEquals(
            listOf("Pinned", "Today", "Yesterday", "Apr 14", "Dec 20, 2025"),
            groups.map { it.label },
        )
        assertEquals(
            listOf(
                SessionDayBucketKind.PINNED,
                SessionDayBucketKind.TODAY,
                SessionDayBucketKind.YESTERDAY,
                SessionDayBucketKind.DATED,
                SessionDayBucketKind.DATED,
            ),
            groups.map { it.kind },
        )
        assertEquals(listOf("s-pinned"), groups[0].sessions.map { it.id })
        assertEquals(listOf("s-today-1"), groups[1].sessions.map { it.id })
        assertEquals(listOf("s-yesterday"), groups[2].sessions.map { it.id })
        assertEquals(listOf("s-older"), groups[3].sessions.map { it.id })
        assertEquals(listOf("s-last-year"), groups[4].sessions.map { it.id })
    }

    @Test
    fun `omits Pinned group when no session is pinned and sorts within day by recency`() {
        val sessions = listOf(
            SessionInfo(
                id = "s-morning",
                providerId = "claude",
                title = "Morning chat",
                updatedAt = Instant.parse("2026-04-17T02:00:00Z"),
                pinned = false,
            ),
            SessionInfo(
                id = "s-afternoon",
                providerId = "claude",
                title = "Afternoon chat",
                updatedAt = Instant.parse("2026-04-17T07:30:00Z"),
                pinned = false,
            ),
        )

        val groups = HistoryByDay.groupSessionsByDay(sessions, now = now, zoneId = zone)

        assertEquals(1, groups.size)
        assertEquals("Today", groups[0].label)
        assertEquals(listOf("s-afternoon", "s-morning"), groups[0].sessions.map { it.id })
    }

    @Test
    fun `swipe and long-press actions expose Pin, Rename, Delete and sheet actions`() {
        val unpinned = SessionInfo(
            id = "s1",
            providerId = "claude",
            title = "Chat 1",
            pinned = false,
        )
        val pinned = unpinned.copy(pinned = true)

        val unpinnedSwipe = HistoryByDay.swipeActionsForSession(unpinned)
        assertEquals(
            listOf(SessionRowActionKind.PIN, SessionRowActionKind.RENAME, SessionRowActionKind.DELETE),
            unpinnedSwipe.map { it.kind },
        )
        assertEquals(listOf("Pin", "Rename", "Delete"), unpinnedSwipe.map { it.label })
        assertFalse(unpinnedSwipe[0].destructive)
        assertTrue(unpinnedSwipe[2].destructive)

        val pinnedSwipe = HistoryByDay.swipeActionsForSession(pinned)
        assertEquals(SessionRowActionKind.UNPIN, pinnedSwipe[0].kind)
        assertEquals("Unpin", pinnedSwipe[0].label)

        val longPress = HistoryByDay.longPressActionsForSession(unpinned, isUnread = false, hasTags = true)
        assertEquals(
            listOf(
                SessionRowActionKind.PIN,
                SessionRowActionKind.MARK_UNREAD,
                SessionRowActionKind.RENAME,
                SessionRowActionKind.SET_TAG,
                SessionRowActionKind.DELETE,
            ),
            longPress.map { it.kind },
        )
    }
}
