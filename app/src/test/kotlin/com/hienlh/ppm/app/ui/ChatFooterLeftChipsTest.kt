package com.hienlh.ppm.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ui.files.installBlankFileIcons
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.AgentRunningEntry
import com.hienlh.ppm.protocol.ChatFromSession
import com.hienlh.ppm.protocol.FromSessionReference
import com.hienlh.ppm.protocol.SessionFileChange
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.SessionLinkRelation
import com.hienlh.ppm.state.ChatBlock
import com.hienlh.ppm.state.ChatItem
import com.hienlh.ppm.state.LinkedSessionStatus
import com.hienlh.ppm.state.LinkedSessionView
import com.hienlh.ppm.state.ReconnectStage
import com.hienlh.ppm.state.RunningAgentView
import com.hienlh.ppm.state.SessionCollabState
import com.hienlh.ppm.state.ToolBlock
import java.time.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h1200dp-xxhdpi")
class ChatFooterLeftChipsTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    @Before
    fun setUp() {
        installBlankFileIcons()
    }

    private val me = "aaaaaaaa-0000-4000-8000-000000000001"
    private val parentId = "aaaaaaaa-0000-4000-8000-000000000000"
    private val childId = "cccccccc-2222-4000-8000-000000000002"
    private val peerId = "dddddddd-3333-4000-8000-000000000003"

    @Test
    fun activityChip_hidesWithoutSessionOrActivity_andListsRunningAndFinishedAgents() {
        var sessionId by mutableStateOf<String?>(null)
        var running by mutableStateOf(emptyList<AgentRunningEntry>())
        var openedAgent: RunningAgentView? = null

        val messages = listOf(
            ChatItem(
                id = "m1",
                role = ChatItem.Role.ASSISTANT,
                blocks = listOf(
                    ChatBlock.Tool(
                        ToolBlock(
                            toolUseId = "toolu_0",
                            name = "Agent",
                            input = buildJsonObject { put("name", "fix-0"); put("description", "fix bug") },
                        ),
                    ),
                    ChatBlock.Tool(
                        ToolBlock(
                            toolUseId = "toolu_9",
                            name = "Agent",
                            input = buildJsonObject { put("description", "review docs") },
                            bgStatus = "completed",
                        ),
                    ),
                ),
                timestamp = Instant.parse("2026-11-10T00:00:00Z"),
                timestampText = "2026-11-10T00:00:00Z",
            ),
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    Column {
                        ActivityChip(
                            sessionId = sessionId,
                            running = running,
                            teamMembers = emptyList(),
                            items = messages,
                            sessions = emptyList(),
                            collab = SessionCollabState(),
                            streamingSessions = emptySet(),
                            unreads = emptyMap(),
                            unlinkedForChat = emptyMap(),
                            narrow = false,
                            onOpenAgent = { openedAgent = it },
                            onOpenLinkedSession = {},
                            onSetSessionsUnlinked = { _, _, _ -> },
                            onStopChildSessions = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("activity-chip").assertDoesNotExist()

        sessionId = me
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("activity-chip").assertDoesNotExist()

        running = listOf(AgentRunningEntry(cardId = "toolu_0", memberName = "fix-0", lastWriteAt = 1L, lastStep = "Editing x.ts"))
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("activity-chip").assertIsDisplayed()
        composeRule.onNodeWithTag("linked-dot:running", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("activity-part:sessions", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("agent").assertIsDisplayed()

        composeRule.onNodeWithTag("activity-chip").performClick()
        composeRule.onNodeWithTag("activity-tab:agents").assertIsSelected()
        composeRule.onNodeWithText("Editing x.ts").assertIsDisplayed()
        composeRule.onNodeWithText("review docs").assertIsDisplayed()
        composeRule.onNodeWithText("Finished").assertIsDisplayed()

        composeRule.onNodeWithTag("agent-row:card:toolu_0").performClick()
        assertEquals("toolu_0", openedAgent?.row?.cardId)
    }

    @Test
    fun activityChip_prioritizesSessionsTabWhenLinkedSessionNeedsApproval() {
        var collab by mutableStateOf(SessionCollabState())
        var narrow by mutableStateOf(false)
        val peerMessage = ChatItem(
            id = "u1",
            role = ChatItem.Role.USER,
            blocks = listOf(
                ChatBlock.Text(
                    ChatFromSession.encode(
                        "hello",
                        FromSessionReference(
                            sessionId = peerId,
                            providerId = "codex",
                            projectName = "ppm",
                            title = "Peer",
                            sentAt = "2026-11-10T00:00:00Z",
                        ),
                    ),
                ),
            ),
            timestamp = Instant.parse("2026-11-10T00:00:00Z"),
            timestampText = "2026-11-10T00:00:00Z",
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    Column {
                        ActivityChip(
                            sessionId = me,
                            running = listOf(AgentRunningEntry(cardId = null, memberName = "worker-1", lastWriteAt = 1L, lastStep = "Bash")),
                            teamMembers = emptyList(),
                            items = listOf(peerMessage),
                            sessions = listOf(SessionInfo(id = me, providerId = "claude", title = "Me", createdAt = null)),
                            collab = collab,
                            streamingSessions = emptySet(),
                            unreads = emptyMap(),
                            unlinkedForChat = emptyMap(),
                            narrow = narrow,
                            onOpenAgent = {},
                            onOpenLinkedSession = {},
                            onSetSessionsUnlinked = { _, _, _ -> },
                            onStopChildSessions = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("linked").assertIsDisplayed()
        composeRule.onNodeWithText("· needs you").assertDoesNotExist()

        collab = SessionCollabState(pendingApprovals = mapOf(peerId to "req-1"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("linked-dot:needs_approval", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("· needs you").assertIsDisplayed()

        // Even while an agent runs, clicking opens on Sessions because a session waits on the person.
        composeRule.onNodeWithTag("activity-chip").performClick()
        composeRule.onNodeWithTag("activity-tab:sessions").assertIsSelected()
        composeRule.onNodeWithTag("activity-chip").performClick()

        // Narrow drops words ("agent", "linked", "· needs you") while keeping counts.
        narrow = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText("linked").assertDoesNotExist()
        composeRule.onNodeWithText("· needs you").assertDoesNotExist()
    }

    @Test
    fun linkedSessionsPanel_groupsUnlinksUndoRelinkBusyAndStopChildrenConfirm() {
        val parent = LinkedSessionView(
            parentId, "Planner", "claude", listOf(SessionLinkRelation.PARENT),
            LinkedSessionStatus.IDLE,
        )
        val child = LinkedSessionView(
            childId, "Tests", "claude", listOf(SessionLinkRelation.CHILD),
            LinkedSessionStatus.RUNNING,
        )
        val peer = LinkedSessionView(
            peerId, "Peer", "codex", listOf(SessionLinkRelation.MESSAGED),
            LinkedSessionStatus.NEW_MESSAGE,
        )

        var hiddenIds by mutableStateOf(emptySet<String>())
        var stopCalls = 0
        val all = listOf(parent, child, peer)

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    val groups = LinkedSessionGroups(
                        visible = all.filterNot { it.sessionId in hiddenIds },
                        hidden = all.filter { it.sessionId in hiddenIds },
                        onUnlink = { ids -> hiddenIds = hiddenIds + ids },
                        onRelink = { ids -> hiddenIds = hiddenIds - ids.toSet() },
                    )
                    LinkedSessionsPanel(
                        groups = groups,
                        onOpenSession = {},
                        onStopChildSessions = { stopCalls++ },
                    )
                }
            }
        }

        composeRule.onNodeWithText("CREATED THIS SESSION").assertIsDisplayed()
        composeRule.onNodeWithText("CREATED BY THIS SESSION").assertIsDisplayed()
        composeRule.onNodeWithText("MESSAGED").assertIsDisplayed()
        composeRule.onNodeWithText("SESSION").assertDoesNotExist()

        // Running child cannot be unlinked.
        composeRule.onNodeWithTag("unlink-action:$childId:busy").assertIsNotEnabled()

        // Unlink single peer session -> shows Undo bar + Unlinked fold.
        composeRule.onNodeWithTag("unlink-action:$peerId:unlink").performClick()
        composeRule.onNodeWithText("MESSAGED").assertDoesNotExist()
        composeRule.onNodeWithText("Unlinked “Peer”").assertIsDisplayed()

        // Open Unlinked fold and relink via row button.
        composeRule.onNodeWithTag("unlinked-toggle").performClick()
        composeRule.onNodeWithTag("unlink-action:$peerId:relink").performClick()
        composeRule.onNodeWithText("MESSAGED").assertIsDisplayed()
        composeRule.onNodeWithTag("unlinked-toggle").assertDoesNotExist()
        composeRule.onNodeWithTag("undo-bar").assertDoesNotExist()

        // Unlink idle & done (2) -> Undo puts both back.
        composeRule.onNodeWithText("Unlink idle & done (2)").performClick()
        composeRule.onNodeWithText("Unlinked 2 sessions").assertIsDisplayed()
        composeRule.onNodeWithTag("unlink-finished").assertDoesNotExist()
        composeRule.onNodeWithTag("undo-unlink-btn").performClick()
        composeRule.onNodeWithText("Unlink idle & done (2)").assertIsDisplayed()

        // Stop all child sessions asks inline first; Cancel aborts; Confirm calls stopChildSessions.
        composeRule.onNodeWithTag("stop-all-children").performClick()
        assertEquals(0, stopCalls)
        composeRule.onNodeWithTag("stop-children-confirm").assertIsDisplayed()
        composeRule.onNodeWithTag("stop-children-cancel").performClick()
        assertEquals(0, stopCalls)
        composeRule.onNodeWithTag("stop-all-children").performClick()
        composeRule.onNodeWithText("Stop 1 session").performClick()
        composeRule.waitForIdle()
        assertEquals(1, stopCalls)
    }

    @Test
    fun linkedSessionsPanel_flushesMidSlideUnlinkWhenDisposedEarly() {
        val peer = LinkedSessionView(
            peerId, "Peer", "codex", listOf(SessionLinkRelation.MESSAGED),
            LinkedSessionStatus.IDLE,
        )
        val flushed = mutableListOf<String>()
        var mounted by mutableStateOf(true)

        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.setContent {
                PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                    if (mounted) {
                        LinkedSessionsPanel(
                            groups = LinkedSessionGroups(
                                visible = listOf(peer),
                                hidden = emptyList(),
                                onUnlink = { flushed.addAll(it) },
                                onRelink = {},
                            ),
                            onOpenSession = {},
                            onStopChildSessions = {},
                        )
                    }
                }
            }
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.onNodeWithTag("unlink-action:$peerId:unlink").performClick()
            // Advance 50ms (< 180ms slide), then unmount panel before timer finishes.
            composeRule.mainClock.advanceTimeBy(50L)
            mounted = false
            composeRule.mainClock.advanceTimeByFrame()
            assertEquals(listOf(peerId), flushed)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun sessionChangesChip_showsCountsOpensPopoverMarksReviewedAndOpensReview() {
        var files by mutableStateOf(
            listOf(
                SessionFileChange("/repo/src/a.kt", SessionFileChange.Status.MODIFIED, 12, 3),
                SessionFileChange("/repo/src/b.kt", SessionFileChange.Status.ADDED, 20, 0),
            ),
        )
        var reviewedPaths = emptyList<String>()
        var openedReviewPath: String? = "unset"

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    Column {
                        SessionChangesChip(
                            files = files,
                            loaded = true,
                            loading = false,
                            projectPath = "/repo",
                            providerId = "claude",
                            narrow = false,
                            hideTotals = false,
                            onReview = { openedReviewPath = it },
                            onSetReviewed = { target, rev ->
                                reviewedPaths = target.map { it.path }
                                val set = reviewedPaths.toSet()
                                files = files.map { if (it.path in set) it.copy(reviewed = rev) else it }
                            },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("session-changes-chip").assertIsDisplayed()
        composeRule.onNodeWithText("files").assertIsDisplayed()
        composeRule.onNodeWithText("+32").assertIsDisplayed()
        composeRule.onNodeWithText("−3").assertIsDisplayed()

        composeRule.onNodeWithTag("session-changes-chip").performClick()
        composeRule.onNodeWithText("2 files changed").assertIsDisplayed()
        composeRule.onNodeWithTag("session-changes-review").performClick()
        assertEquals(null, openedReviewPath)

        // Mark all reviewed -> chip switches to "2 reviewed".
        composeRule.onNodeWithTag("session-changes-chip").performClick()
        composeRule.onNodeWithTag("session-changes-mark-all").performClick()
        assertEquals(2, reviewedPaths.size)
        composeRule.onNodeWithText("reviewed").assertIsDisplayed()
    }

    @Test
    fun connectionChip_transitionsStagesAndPhoneChipAnswers44dpTouchTarget() {
        var stage by mutableStateOf(ReconnectStage.HIDDEN)
        var mobile by mutableStateOf(false)
        var retries = 0

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    Box(Modifier.padding(vertical = 24.dp)) {
                        ConnectionChip(
                            stage = stage,
                            narrow = false,
                            onRetry = { retries++ },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("connection-chip:reconnecting").assertDoesNotExist()
        composeRule.onNodeWithTag("connection-chip:lost").assertDoesNotExist()

        stage = ReconnectStage.RECONNECTING
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("connection-chip:reconnecting").assertIsDisplayed()
        composeRule.onNodeWithText("Reconnecting…").assertIsDisplayed()

        stage = ReconnectStage.LOST
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("connection-chip:lost").assertIsDisplayed()
        composeRule.onNodeWithText("Connection lost").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
        composeRule.onNodeWithTag("connection-chip:lost").performClick()
        assertEquals(1, retries)

        // Phone mode: visual layout height is 26dp, says "Offline", and answers a tap 8dp above the 26dp pill!
        mobile = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Offline").assertIsDisplayed()
        val node = composeRule.onNodeWithTag("connection-chip:lost")
        node.assertHeightIsEqualTo(26.dp)
        node.performTouchInput {
            click(Offset(centerX, -6.dp.toPx()))
        }
        assertTrue("Expected tap above 26dp visual bounds (within 44dp touch area) to trigger retry", retries == 2)
    }
}
