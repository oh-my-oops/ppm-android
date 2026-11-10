package com.hienlh.ppm.app.ui.phone.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hienlh.ppm.app.ui.HistoryByDay
import com.hienlh.ppm.app.ui.PhoneSessionHistoryList
import com.hienlh.ppm.app.ui.PhoneSessionHistoryRow
import com.hienlh.ppm.app.ui.SessionDayBucketKind
import com.hienlh.ppm.app.ui.SessionLiveState
import com.hienlh.ppm.app.ui.SessionRowActionKind
import com.hienlh.ppm.chat.SessionDebugKeyValueCard
import com.hienlh.ppm.chat.debugInfoFields
import com.hienlh.ppm.chat.middleEllipsis
import com.hienlh.ppm.design.PpmTheme
import com.hienlh.ppm.protocol.SessionDebugInfo
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.SessionTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp")
class HistoryByDayTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private val now = Instant.parse("2026-04-17T08:00:00Z") // 2026-04-17 15:00 +07

    @Test
    fun groupsSessionsIntoPinnedTodayYesterdayAndDatedBuckets() {
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
    fun omitsPinnedGroupWhenNoSessionIsPinnedAndSortsWithinDayByRecency() {
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
    fun swipeAndLongPressActionsExposePinRenameDeleteAndSheetActions() {
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

    @Test
    fun rendersDayGroupedHistoryAndSwipeRowActions() {
        val sessions = listOf(
            SessionInfo(
                id = "s-pin",
                providerId = "claude",
                title = "Pinned architecture review",
                updatedAt = Instant.parse("2026-04-17T07:00:00Z"),
                pinned = true,
                tag = SessionTag(1, "feature", "#22c55e"),
            ),
            SessionInfo(
                id = "s-today",
                providerId = "codex",
                title = "Implement phone chat",
                updatedAt = Instant.parse("2026-04-17T06:00:00Z"),
                pinned = false,
            ),
        )
        val groups = HistoryByDay.groupSessionsByDay(sessions, now = now, zoneId = zone)
        var pinnedToggledId: String? = null
        var deletedId: String? = null

        composeRule.setContent {
            PpmTheme(mobile = true) {
                PhoneSessionHistoryList(
                    groups = groups,
                    runningSessionIds = setOf("s-today"),
                    onOpenSession = {},
                    onTogglePin = { pinnedToggledId = it.id },
                    onDeleteSession = { deletedId = it.id },
                )
            }
        }

        composeRule.onNodeWithText("PINNED").assertIsDisplayed()
        composeRule.onNodeWithText("TODAY").assertIsDisplayed()
        composeRule.onNodeWithText("Pinned architecture review").assertIsDisplayed()
        composeRule.onNodeWithText("Implement phone chat").assertIsDisplayed()
        composeRule.onNodeWithText("Running · now").assertIsDisplayed()

        // Verify swipe-expanded row exposes Pin, Rename, Delete actions
        composeRule.setContent {
            PpmTheme(mobile = true) {
                PhoneSessionHistoryRow(
                    session = sessions[1],
                    liveState = SessionLiveState.NEEDS_YOU,
                    swipeExpanded = true,
                    onOpen = {},
                    onTogglePin = { pinnedToggledId = sessions[1].id },
                    onStartRename = {},
                    onDelete = { deletedId = sessions[1].id },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Pin").assertIsDisplayed().performClick()
        assertEquals("s-today", pinnedToggledId)

        composeRule.onNodeWithContentDescription("Delete").assertIsDisplayed().performClick()
        assertEquals("s-today", deletedId)
    }

    @Test
    fun sessionDebugFieldsMiddleEllipsizeLongIdsAndOmitBrowserOnlyMetrics() {
        val debugInfo = SessionDebugInfo(
            ppmSessionId = "c8e3f4a1-7b12-49d0-a612-88d19f2b1408",
            sdkSessionId = "sdk_99a1b2c3d4e5f60718293a4b5c6d7e8f",
            jsonlPath = "/home/thawngho/.claude/projects/ppm/sdk_99a1.jsonl",
            projectPath = "/home/thawngho/Projects/ppm",
            jsonlSizeBytes = 1_488_978L,
            jsonlLines = 184,
        )
        val fields = debugInfoFields(
            data = debugInfo,
            renderStats = listOf(
                "Rendered: 42 messages",
                "DOM nodes: 1840",
                "JS heap: 44 MB",
            ),
            nativeApp = true,
        )

        assertEquals(
            listOf("PPM Session", "SDK Session", "JSONL", "Project", "Rendered"),
            fields.map { it.label },
        )
        assertEquals("c8e3f4a1…9f2b1408", fields[0].displayValue)
        assertEquals(debugInfo.ppmSessionId, fields[0].fullValue)
        assertEquals("42 messages", fields[4].displayValue)
        assertEquals("short-id", middleEllipsis("short-id", head = 8, tail = 8))

        composeRule.setContent {
            PpmTheme(mobile = true) {
                SessionDebugKeyValueCard(fields = fields)
            }
        }
        composeRule.onNodeWithText("PPM Session").assertIsDisplayed()
        composeRule.onNodeWithText("c8e3f4a1…9f2b1408").assertIsDisplayed()
    }
}
