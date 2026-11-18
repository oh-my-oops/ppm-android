package com.hienlh.ppm.app.ui.chat

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.ui.LinkedSessionGroups
import com.hienlh.ppm.app.ui.LinkedSessionsPanel
import com.hienlh.ppm.app.ui.LocalReducedMotion
import com.hienlh.ppm.app.ui.files.installBlankFileIcons
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.chat.ChatSessionOpener
import com.hienlh.ppm.chat.ChatSessionTarget
import com.hienlh.ppm.chat.ChatTranscriptView
import com.hienlh.ppm.chat.ChatTurnTools
import com.hienlh.ppm.chat.ComposerTurnChipView
import com.hienlh.ppm.chat.LocalChatSessionOpener
import com.hienlh.ppm.client.TurnWindowPage
import com.hienlh.ppm.client.TurnWindowQuery
import com.hienlh.ppm.client.WindowTurn
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.HistoryMessage
import com.hienlh.ppm.protocol.SessionLinkRelation
import com.hienlh.ppm.protocol.TurnLinkTarget
import com.hienlh.ppm.protocol.generated.TurnReference
import com.hienlh.ppm.state.ChatBlock
import com.hienlh.ppm.state.ChatItem
import com.hienlh.ppm.state.ChatJumpRequest
import com.hienlh.ppm.state.ChatState
import com.hienlh.ppm.state.ComposerTurnChip
import com.hienlh.ppm.state.HistoryWindowController
import com.hienlh.ppm.state.LinkedSessionStatus
import com.hienlh.ppm.state.LinkedSessionView
import com.hienlh.ppm.state.monotonicMillis
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Turn links, turn chips, the history window and the Sessions list's filter, on a phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h840dp-xhdpi")
class PhoneTurnLinksUiTest : TurnLinksUiTest(mobile = true)

/** The same on a tablet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h1200dp-xxhdpi")
class TabletTurnLinksUiTest : TurnLinksUiTest(mobile = false)

abstract class TurnLinksUiTest(private val mobile: Boolean) {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    @Before
    fun setUp() {
        installBlankFileIcons()
    }

    private val sid = "0f8e2a4c-1111-4000-8000-000000000001"
    private val tid = "7a1b9c3d-2222-4000-8000-000000000002"
    private val oldTid = "7a1b9c3d-2222-4000-8000-000000000009"

    private fun ref(project: String = "ppm") =
        TurnReference(1, project, "claude", sid, tid, 4.0, "Fix the login", "why does it fail", "2026-11-18T08:00:00.000Z")

    private fun user(id: String, text: String, turnId: String?) =
        ChatItem(id, ChatItem.Role.USER, listOf(ChatBlock.Text(text)), Instant.parse("2026-11-18T08:00:00Z"), turnId = turnId)

    private fun tools(fetch: suspend (TurnWindowQuery) -> TurnWindowPage = { error("no window") }, resolve: String? = null, toasts: MutableList<String> = mutableListOf()) =
        ChatTurnTools(
            projectName = "ppm",
            origin = "http://localhost:8080",
            resolveTurnId = { _, _, _ -> resolve },
            history = HistoryWindowController(CoroutineScope(Dispatchers.Main), fetch),
            toast = { toasts += it },
        )

    private fun clipboardText(): String? {
        val manager = ApplicationProvider.getApplicationContext<Context>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return manager.primaryClip?.getItemAt(0)?.text?.toString()
    }

    /** On a phone the button is its icon alone, named for the reader; on a tablet it says it. */
    private fun copyButton(label: String) =
        if (mobile) composeRule.onNodeWithContentDescription(label) else composeRule.onNodeWithText(label)

    @Test
    fun copyTurnLink_copiesTheLinkToAKnownTurn() {
        val state = ChatState(sessionId = sid, providerId = "claude", items = listOf(user("m1", "hello", tid)))
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                ChatTranscriptView(state, onRespond = { _, _, _ -> }, onLoadEarlier = {}, onReload = {}, turnTools = tools())
            }
        }
        composeRule.waitForIdle()
        copyButton("Copy turn link").performClick()
        composeRule.waitForIdle()
        assertEquals("http://localhost:8080/project/ppm/chat/claude/$sid?turn=$tid", clipboardText())
        copyButton("Copied").assertExists()
    }

    @Test
    fun copyTurnLink_waitsForAnUnknownTurnThenOffersTheLink() {
        // A message drawn here before history named its turn: asked by its record uuid.
        val item = user("local-user-1", "hello", null).copy(sdkUuid = "uuid-1")
        val state = ChatState(sessionId = sid, providerId = "claude", items = listOf(item))
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                ChatTranscriptView(state, onRespond = { _, _, _ -> }, onLoadEarlier = {}, onReload = {}, turnTools = tools(resolve = tid))
            }
        }
        composeRule.waitForIdle()
        copyButton("Copy turn link").performClick()
        composeRule.waitUntil(3_000) { runCatching { copyButton("Link ready").assertExists() }.isSuccess }
        copyButton("Link ready").performClick()
        composeRule.waitForIdle()
        assertEquals("http://localhost:8080/project/ppm/chat/claude/$sid?turn=$tid", clipboardText())
    }

    @Test
    fun copyTurnLink_isOfferedOnlyForClaudeAndCodex() {
        val state = ChatState(sessionId = sid, providerId = "agy", items = listOf(user("m1", "hello", tid)))
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                ChatTranscriptView(state, onRespond = { _, _, _ -> }, onLoadEarlier = {}, onReload = {}, turnTools = tools())
            }
        }
        composeRule.waitForIdle()
        copyButton("Copy turn link").assertDoesNotExist()
    }

    @Test
    fun jumpToAnUnloadedTurn_opensTheHistoryWindow_andBackToLatestLeavesIt() {
        val page = TurnWindowPage(
            sessionId = sid,
            turns = listOf(WindowTurn(oldTid, 2, false)),
            messages = listOf(HistoryMessage("w1", HistoryMessage.Role.USER, "an old question", emptyList(), Instant.parse("2026-11-17T08:00:00Z"), turnId = oldTid, turnN = 2)),
            hasOlder = false,
            hasNewer = true,
            latestTurnId = tid,
        )
        val asked = mutableListOf<TurnWindowQuery>()
        val turnTools = tools(fetch = { asked += it; page })
        // History is in: a turn it does not hold is fetched rather than waited for.
        val state = ChatState(sessionId = sid, providerId = "claude", items = listOf(user("m1", "the newest", tid)), hasLoadedHistory = true)
        var jump by mutableStateOf<ChatJumpRequest?>(null)
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                ChatTranscriptView(
                    state, onRespond = { _, _, _ -> }, onLoadEarlier = {}, onReload = {},
                    jumpRequest = jump, onJumpTaken = { if (jump === it) jump = null }, turnTools = turnTools,
                )
            }
        }
        composeRule.waitForIdle()
        jump = ChatJumpRequest(sid, null, null, monotonicMillis(), turnId = oldTid)
        composeRule.waitUntil(3_000) { composeRule.onAllNodesWithTag("history-window").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(TurnWindowQuery.Around(oldTid), asked.first())
        composeRule.onNodeWithText("an old question").assertExists()
        composeRule.onNodeWithText("Viewing turn 2").assertIsDisplayed()
        composeRule.onNodeWithText("Back to latest").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("history-window").assertDoesNotExist()
        composeRule.onNodeWithText("the newest").assertExists()
    }

    @Test
    fun composerChips_showLoadingErrorAndTheTurn_andEachCanBeRemoved() {
        val target = TurnLinkTarget("ppm", "claude", sid, tid)
        var chips by mutableStateOf(
            listOf(
                ComposerTurnChip.Loading("a", target, 1),
                ComposerTurnChip.Failed("b", target, "This turn is no longer available"),
                ComposerTurnChip.Ready("c", target, ref(project = "other")),
            ),
        )
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                Column {
                    for (chip in chips) ComposerTurnChipView(chip, currentProject = "ppm", onRemove = { chips = chips - chip })
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("composer-turn-chip-loading").assertExists()
        composeRule.onNodeWithText("This turn is no longer available").assertExists()
        composeRule.onNodeWithText("Fix the login").assertExists()
        composeRule.onNodeWithText("Turn 4").assertExists()
        // Another project's turn names its project.
        composeRule.onNodeWithText("· other").assertExists()
        // A chip is a 44 px target on a phone, 28 on a tablet, its 1 px border included (the tag sits inside it).
        val node = composeRule.onNodeWithTag("turn-chip:$tid").fetchSemanticsNode()
        assertEquals(if (mobile) 44f else 28f, node.size.height / node.layoutInfo.density.density + 2f, 0.5f)

        // Remove the failed one: the others stay.
        composeRule.onAllNodesWithContentDescription("Remove turn reference")[1].performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("This turn is no longer available").assertDoesNotExist()
        composeRule.onNodeWithTag("composer-turn-chip-loading").assertExists()
    }

    @Test
    fun composerChip_removeButtonRemovesIt() {
        val target = TurnLinkTarget("ppm", "claude", sid, tid)
        var chips by mutableStateOf(listOf<ComposerTurnChip>(ComposerTurnChip.Ready("c", target, ref())))
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                Column { for (chip in chips) ComposerTurnChipView(chip, currentProject = "ppm", onRemove = { chips = chips - chip }) }
            }
        }
        composeRule.waitForIdle()
        // The turn's own project is not named.
        composeRule.onNodeWithText("· ppm").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Remove turn reference").performClick()
        composeRule.waitForIdle()
        assertTrue(chips.isEmpty())
    }

    @Test
    fun turnChip_tapOpensTheTurn_longPressPreviewsIt() {
        val opened = mutableListOf<ChatSessionTarget>()
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                CompositionLocalProvider(LocalChatSessionOpener provides ChatSessionOpener { opened += it }) {
                    ComposerTurnChipView(ComposerTurnChip.Ready("c", TurnLinkTarget("other", "claude", sid, tid), ref(project = "other")), currentProject = "ppm", onRemove = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Turn 4 of Fix the login").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(ChatSessionTarget("other", "claude", sid, "Fix the login", tid)), opened)

        composeRule.onNodeWithContentDescription("Turn 4 of Fix the login").performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("turn-ref-preview").assertExists()
        composeRule.onNodeWithText("Project: other").assertExists()
        composeRule.onNodeWithText("why does it fail").assertExists()
        composeRule.onNodeWithText("Open session").performClick()
        composeRule.waitForIdle()
        assertEquals(2, opened.size)
        composeRule.onNodeWithTag("turn-ref-preview").assertDoesNotExist()
    }

    private fun linked(n: Int, title: String, at: Long?, status: LinkedSessionStatus = LinkedSessionStatus.IDLE) = LinkedSessionView(
        sessionId = "cccccccc-0000-4000-8000-0000000000%02d".format(n),
        title = title,
        providerId = "claude",
        relations = listOf(SessionLinkRelation.CHILD),
        status = status,
        lastInteractionAt = at,
    )

    private fun filterField() = composeRule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("linked-filter")))

    private fun setPanel(visible: List<LinkedSessionView>, hidden: List<LinkedSessionView> = emptyList()) {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    LinkedSessionsPanel(
                        groups = LinkedSessionGroups(visible = visible, hidden = hidden, onUnlink = {}, onRelink = {}),
                        onOpenSession = {},
                        onStopChildSessions = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun linkedSessions_eightRowsHaveNoFilter_andEachSaysWhenItLastTalked() {
        val now = System.currentTimeMillis()
        setPanel((1..8).map { linked(it, "Session $it", now - it * 3_600_000L) })
        composeRule.onNodeWithTag("linked-filter").assertDoesNotExist()
        assertEquals(8, composeRule.onAllNodesWithTag("linked-interaction", useUnmergedTree = true).fetchSemanticsNodes().size)
        composeRule.onAllNodesWithText("1h ago", substring = true, useUnmergedTree = true).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
    }

    @Test
    fun linkedSessions_moreThanEightRowsOfferAFilterThatFoldsAccents() {
        val now = System.currentTimeMillis()
        val rows = (1..8).map { linked(it, "Session $it", now - it * 60_000L) } + linked(9, "Tự động deploy", now)
        setPanel(rows, hidden = listOf(linked(10, "Đổi tên", now)))
        composeRule.onNodeWithTag("linked-filter").assertExists()
        composeRule.onNodeWithText("Find a session").assertExists()

        filterField().performTextInput("tu dong")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Tự động deploy").assertExists()
        composeRule.onNodeWithText("Session 1").assertDoesNotExist()
    }

    @Test
    fun linkedSessions_aFilterThatMatchesNothingSaysSo() {
        val now = System.currentTimeMillis()
        setPanel((1..9).map { linked(it, "Session $it", now) })
        filterField().performTextInput("zzz")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("No linked session matches “zzz”.").assertExists()
    }
}
