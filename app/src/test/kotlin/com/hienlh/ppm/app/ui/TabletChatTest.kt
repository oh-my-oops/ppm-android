package com.hienlh.ppm.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ui.tablet.DropZone
import com.hienlh.ppm.app.ui.tablet.dropZoneLabel
import com.hienlh.ppm.app.ui.tablet.shouldFoldChatPaneBody
import com.hienlh.ppm.app.ui.tablet.shouldStackRowInPortrait
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.chat.ChatTranscriptView
import com.hienlh.ppm.chat.navigatorTooltipText
import com.hienlh.ppm.chat.readingColumnHorizontalPadding
import com.hienlh.ppm.chat.shouldKeepNavigatorOpen
import com.hienlh.ppm.chat.waitingApprovalPillLabel
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.ApprovalRequest
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.state.ChatBlock
import com.hienlh.ppm.state.ChatItem
import com.hienlh.ppm.state.ChatState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for Task 8 (`Tablet 1 · Chat` — Requirements 4.1, 4.4, 4.5):
 * - Always-visible message navigator on tablet (`shouldKeepNavigatorOpen`, `navigatorTooltipText`)
 *   and Compose verification that `chat-scroll-navigator` stays open past the phone auto-collapse window;
 * - 760 dp centered reading column horizontal padding (`readingColumnHorizontalPadding`);
 * - Floating "Needs you" pill when scrolled away from a waiting approval/question card (`waitingApprovalPillLabel`);
 * - Docked subagent session side panel in landscape, slide-over in portrait, and window mode (`resolveTabletAgentDockMode`, `clampTabletAgentDockWidth`);
 * - Portrait split stacking (`shouldStackRowInPortrait`), narrow chat pane folding (`shouldFoldChatPaneBody`),
 *   and tab drop zone action pills (`dropZoneLabel`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h1200dp-xhdpi")
class TabletChatTest {

    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    @Test
    fun navigator_staysOpenOnTablet_hidesWhileApprovalWaits_andCollapsesOnPhone() {
        assertTrue(shouldKeepNavigatorOpen(mobile = false, totalMessages = 12, hasPendingApproval = false))
        assertFalse(shouldKeepNavigatorOpen(mobile = false, totalMessages = 0, hasPendingApproval = false))
        assertFalse(shouldKeepNavigatorOpen(mobile = false, totalMessages = 12, hasPendingApproval = true))
        assertFalse(shouldKeepNavigatorOpen(mobile = true, totalMessages = 12, hasPendingApproval = false))

        assertEquals("Message 7 of 48", navigatorTooltipText(position = 7, total = 48))
        assertEquals("Message 1 of 4", navigatorTooltipText(position = 0, total = 4))
        assertEquals("Message 0 of 0", navigatorTooltipText(position = 0, total = 0))
    }

    @Test
    fun readingColumn_centers760dpColumnOnWideTabletPane_andKeeps16dpOnPhoneOrNarrowPane() {
        assertEquals(16f, readingColumnHorizontalPadding(paneWidthDp = 1138f, mobile = true), 0.01f)
        assertEquals(16f, readingColumnHorizontalPadding(paneWidthDp = 760f, mobile = false), 0.01f)
        assertEquals(189f, readingColumnHorizontalPadding(paneWidthDp = 1138f, mobile = false), 0.01f)
    }

    @Test
    fun waitingApprovalPill_showsToolOrAnswerWhenScrolledAway_andHidesAtBottom() {
        val bashApproval = ApprovalRequest(requestId = "req-1", tool = "Bash", input = WireJson.parse("""{"command":"rm -rf dist"}"""))
        val questionApproval = ApprovalRequest(requestId = "req-2", tool = "AskUserQuestion", input = WireJson.parse("""{"questions":[]}"""))

        assertEquals("Waiting for approval · Bash", waitingApprovalPillLabel(bashApproval, atBottom = false))
        assertEquals("Waiting for answer", waitingApprovalPillLabel(questionApproval, atBottom = false))
        assertNull(waitingApprovalPillLabel(bashApproval, atBottom = true))
        assertNull(waitingApprovalPillLabel(null, atBottom = false))
    }

    @Test
    fun agentDock_docksBesideChatInLandscape_slidesOverInPortraitOrNarrowPane_andRespectsWindowPref() {
        assertEquals(
            TabletAgentDockMode.DOCKED_SIDE,
            resolveTabletAgentDockMode(paneWidthDp = 1138f, isPortrait = false, preferWindow = false),
        )
        assertEquals(
            TabletAgentDockMode.SLIDE_OVER,
            resolveTabletAgentDockMode(paneWidthDp = 900f, isPortrait = true, preferWindow = false),
        )
        assertEquals(
            TabletAgentDockMode.SLIDE_OVER,
            resolveTabletAgentDockMode(paneWidthDp = 680f, isPortrait = false, preferWindow = false),
        )
        assertEquals(
            TabletAgentDockMode.WINDOW,
            resolveTabletAgentDockMode(paneWidthDp = 1138f, isPortrait = false, preferWindow = true),
        )

        // Default 440 dp stays 440 dp when pane is 1138 dp wide; clamps to [320, 640] and leaves >= 432 dp for chat + handle
        assertEquals(440f, clampTabletAgentDockWidth(requestedWidthDp = 440f, paneWidthDp = 1138f), 0.01f)
        assertEquals(320f, clampTabletAgentDockWidth(requestedWidthDp = 200f, paneWidthDp = 1138f), 0.01f)
        assertEquals(640f, clampTabletAgentDockWidth(requestedWidthDp = 800f, paneWidthDp = 1200f), 0.01f)
        assertEquals(368f, clampTabletAgentDockWidth(requestedWidthDp = 440f, paneWidthDp = 800f), 0.01f)
    }

    @Test
    fun panelGrid_stacksMultiColumnSplitsInPortrait_foldsChatBelow420dp_andFormatsDropZonePills() {
        // Portrait (900 x 1440): 2-column split stacks top/bottom; Landscape (1440 x 900): stays side-by-side
        assertTrue(shouldStackRowInPortrait(columnCount = 2, gridWidthDp = 900f, gridHeightDp = 1440f))
        assertFalse(shouldStackRowInPortrait(columnCount = 2, gridWidthDp = 1440f, gridHeightDp = 900f))
        assertFalse(shouldStackRowInPortrait(columnCount = 1, gridWidthDp = 900f, gridHeightDp = 1440f))

        // Narrow chat pane (< 420 dp) in a multi-pane split folds its body to the tab bar
        assertTrue(shouldFoldChatPaneBody(multiple = true, activeTabType = "chat", paneWidthDp = 380f))
        assertFalse(shouldFoldChatPaneBody(multiple = true, activeTabType = "chat", paneWidthDp = 420f))
        assertFalse(shouldFoldChatPaneBody(multiple = false, activeTabType = "chat", paneWidthDp = 380f))
        assertFalse(shouldFoldChatPaneBody(multiple = true, activeTabType = "editor", paneWidthDp = 380f))

        assertEquals("Split left", dropZoneLabel(DropZone.Left))
        assertEquals("Split right", dropZoneLabel(DropZone.Right))
        assertEquals("Split up", dropZoneLabel(DropZone.Top))
        assertEquals("Split down", dropZoneLabel(DropZone.Bottom))
        assertEquals("Move here", dropZoneLabel(DropZone.Center))
    }

    @Test
    fun chatTranscriptView_keepsScrollNavigatorVisibleOnTabletPastAutoCollapseDelay() {
        val items = listOf(
            ChatItem("u1", ChatItem.Role.USER, listOf(ChatBlock.Text("First prompt")), Instant.ofEpochSecond(1_700_000_000L)),
            ChatItem("a1", ChatItem.Role.ASSISTANT, listOf(ChatBlock.Text("First answer")), Instant.ofEpochSecond(1_700_000_002L)),
            ChatItem("u2", ChatItem.Role.USER, listOf(ChatBlock.Text("Second prompt")), Instant.ofEpochSecond(1_700_000_004L)),
            ChatItem("a2", ChatItem.Role.ASSISTANT, listOf(ChatBlock.Text("Second answer")), Instant.ofEpochSecond(1_700_000_006L)),
        )
        val state = ChatState(sessionId = "s-tablet", items = items, hasLoadedHistory = true)

        compose.mainClock.autoAdvance = false
        compose.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                Box(Modifier.size(width = 900.dp, height = 700.dp)) {
                    ChatTranscriptView(state, onRespond = { _, _, _ -> }, onLoadEarlier = {}, onReload = {})
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        compose.onNodeWithTag("chat-scroll-navigator").assertIsDisplayed()

        // Advance well past the 3200ms mobile auto-collapse timeout: navigator stays open on tablet
        compose.mainClock.advanceTimeBy(5_000L)
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        assertEquals(1, compose.onAllNodesWithTag("chat-scroll-navigator").fetchSemanticsNodes().size)
    }
}
