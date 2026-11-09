package com.hienlh.ppm.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ChatTab
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.chat.ChatScrollFadeKey
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.ChatFrame
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.SlashItem
import com.hienlh.ppm.protocol.SlashItemsPayload
import com.hienlh.ppm.protocol.TurnStop
import com.hienlh.ppm.state.ChatBlock
import com.hienlh.ppm.state.ChatController
import com.hienlh.ppm.state.ChatItem
import com.hienlh.ppm.state.FileIndexStore
import com.hienlh.ppm.state.SlashItemsStore
import java.time.Instant
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * End-to-end integration tests for the unified chat footer in [ChatPane]
 * (`tests/e2e/chat-footer-e2e.mjs` + `chat-tab.tsx` + `globals.css`):
 * - transcript 12dp bottom scroll fade + `-12dp` overlap when nothing sits between list and footer,
 *   restoring ordinary spacing when `TurnStopBar` appears;
 * - floating `/` and `@` pickers hovering 6dp above `ChatComposer` with matching width, overlapping
 *   `chat-context-row` without pushing it up, and receiving click events;
 * - tablet `FooterPopover` (`More` / `History`) anchored 4dp above `chat-context-row` without shifting
 *   layout and dismissing via `FooterPopoverScrim`;
 * - `ConnectionChip` stages (`HIDDEN` -> `RECONNECTING` -> `LOST`) and offline composer placeholder.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w900dp-h1200dp-xxhdpi")
class ChatFooterIntegrationTest {

    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var model: AppModel
    private lateinit var api: ApiClient

    private val sampleMessages = listOf(
        ChatItem("u1", ChatItem.Role.USER, listOf(ChatBlock.Text("Hello footer")), Instant.ofEpochSecond(1_700_000_000L)),
        ChatItem("a1", ChatItem.Role.ASSISTANT, listOf(ChatBlock.Text("Ready with the new unified footer.")), Instant.ofEpochSecond(1_700_000_005L)),
    )

    private val slashFixture = listOf(
        SlashItem(type = "skill", name = "ak-research", description = "Research solutions.", scope = "user"),
        SlashItem(type = "agent", name = "Explore", description = "Fast read-only search.", scope = "bundled"),
        SlashItem(type = "command", name = "deploy-staging", description = "Deploy to staging.", scope = "project"),
        SlashItem(type = "builtin", name = "compact", description = "Summarize conversation.", scope = "bundled"),
    )

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path.endsWith("/messages") -> MockResponse.Builder()
                        .code(200)
                        .body("""{"ok":true,"data":{"messages":[],"total":0,"hasMore":false}}""")
                        .build()
                    path.contains("/files/index") -> MockResponse.Builder()
                        .code(200)
                        .body(
                            """{"ok":true,"data":[
                                {"name":"chat-tab.tsx","path":"src/web/components/chat/chat-tab.tsx","type":"file"},
                                {"name":"chat-footer-tools.tsx","path":"src/web/components/chat/chat-footer-tools.tsx","type":"file"}
                            ]}""",
                        )
                        .build()
                    path.startsWith("/ws/") -> MockResponse.Builder().code(503).build()
                    else -> MockResponse.Builder().code(200).body("""{"ok":true,"data":{}}""").build()
                }
            }
        }
        server.start()
        model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        api = ApiClient(server.url("/"), "secret")
        model.api = api

        val slashStore = SlashItemsStore(scope)
        slashStore.seed(SlashItemsPayload(slashFixture, recentNames = emptyList()), "p", "claude")
        model.slashItems = slashStore
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    @Test
    fun chatPane_rendersUnifiedFooter_masksAndOverlapsTranscript12dp_andRestoresSpacingForTurnStopBar() {
        val parent = SessionInfo(id = "parent-1", providerId = "claude", title = "Parent session")
        val current = SessionInfo(id = "s-1", providerId = "claude", title = "Child session", spawnedBy = "parent-1", parentTitle = "Parent session")
        model.sessions = listOf(parent, current)

        val tab = ChatTab("tab-1", session = current, project = "p", providerId = "claude")
        val chat = ChatController(api, "p", current, scope, providerId = "claude", stale = sampleMessages)
        tab.controller = chat

        lateinit var density: Density
        compose.setContent {
            density = LocalDensity.current
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    Box(Modifier.size(width = 800.dp, height = 700.dp)) {
                        ChatPane(model = model, tab = tab)
                    }
                }
            }
        }
        compose.waitForIdle()

        // Transcript exposes 12dp bottom scroll fade mask and overlaps 12dp behind `chat-footer`
        val transcriptNode = compose.onNodeWithTag("chat-transcript").fetchSemanticsNode()
        assertEquals(12.dp, transcriptNode.config.getOrNull(ChatScrollFadeKey))

        val transcriptBounds = transcriptNode.boundsInRoot
        val footerBounds = compose.onNodeWithTag("chat-footer").fetchSemanticsNode().boundsInRoot
        val contextRowBounds = compose.onNodeWithTag("chat-context-row").fetchSemanticsNode().boundsInRoot
        val composerBounds = compose.onNodeWithTag("chat-composer").fetchSemanticsNode().boundsInRoot
        val twelvePx = with(density) { 12.dp.toPx() }
        val fourPx = with(density) { 4.dp.toPx() }

        assertTrue(
            "expected chat-footer to pull up 12dp over chat-transcript, got overlap=${transcriptBounds.bottom - footerBounds.top} vs $twelvePx",
            abs((transcriptBounds.bottom - footerBounds.top) - twelvePx) <= 2f,
        )
        assertTrue(
            "expected chat-context-row directly above chat-composer, got rowBottom=${contextRowBounds.bottom} composerTop=${composerBounds.top}",
            abs(composerBounds.top - contextRowBounds.bottom) <= 2f,
        )

        // Activity chip counts the linked parent ("1" + "linked") with no legacy "SESSION" badge
        compose.onNodeWithTag("activity-chip").assertIsDisplayed()
        compose.onNodeWithTag("activity-part:sessions", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("linked", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("footer-cluster").assertIsDisplayed()
        compose.onNodeWithTag("footer-history").assertIsDisplayed()
        compose.onNodeWithTag("footer-more").assertIsDisplayed()

        // Opening More anchors `footer-popover:Chat menu` 4dp (`mb-1`) above `chat-context-row` without moving it
        compose.onNodeWithTag("footer-more").performClick()
        compose.waitForIdle()
        val popoverBounds = compose.onNodeWithTag("footer-popover:Chat menu").fetchSemanticsNode().boundsInRoot
        val contextRowAfterPopover = compose.onNodeWithTag("chat-context-row").fetchSemanticsNode().boundsInRoot
        assertEquals(contextRowBounds.top, contextRowAfterPopover.top, 0.5f)
        assertTrue(
            "expected footer-popover 4dp above chat-context-row, got gap=${contextRowBounds.top - popoverBounds.bottom} vs $fourPx",
            abs((contextRowBounds.top - popoverBounds.bottom) - fourPx) <= 2f,
        )

        // Tapping outside via `footer-popover-scrim` closes the popover
        compose.onNodeWithTag("footer-popover-scrim").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("footer-popover:Chat menu").fetchSemanticsNodes().let { assertEquals(0, it.size) }

        // When TurnStopBar appears between transcript and footer, the -12dp overlap is removed
        compose.runOnIdle {
            chat.transcript.handle(ChatFrame.TurnStopped(TurnStop(subtype = "error_max_turns", message = "Max turns reached")))
            chat.markTeamRead()
        }
        compose.waitForIdle()
        compose.onNodeWithText("Continue", substring = true).assertIsDisplayed()
        val transcriptWithStop = compose.onNodeWithTag("chat-transcript").fetchSemanticsNode().boundsInRoot
        val footerWithStop = compose.onNodeWithTag("chat-footer").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "expected TurnStopBar between transcript and footer to remove overlap, got transcriptBottom=${transcriptWithStop.bottom} footerTop=${footerWithStop.top}",
            footerWithStop.top >= transcriptWithStop.bottom - 1f,
        )
    }

    @Test
    fun chatPane_floatingSlashAndAtPickers_hover6dpAboveComposerOverContextRowWithoutShiftingLayout() {
        val fileStore = FileIndexStore(api, scope)
        runBlocking { fileStore.load("p").join() }
        model.fileIndex = fileStore
        val closeReader = fileStore.openReader("p")

        val current = SessionInfo(id = "s-picker", providerId = "claude", title = "Picker chat")
        val tab = ChatTab("tab-picker", session = current, project = "p", providerId = "claude")
        val chat = ChatController(api, "p", current, scope, providerId = "claude", stale = sampleMessages)
        tab.controller = chat

        lateinit var density: Density
        compose.setContent {
            density = LocalDensity.current
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    Box(Modifier.size(width = 800.dp, height = 700.dp)) {
                        ChatPane(model = model, tab = tab)
                    }
                }
            }
        }
        compose.waitForIdle()

        val sixPx = with(density) { 6.dp.toPx() }
        val rowBefore = compose.onNodeWithTag("chat-context-row").fetchSemanticsNode().boundsInRoot
        val composerBefore = compose.onNodeWithTag("chat-composer").fetchSemanticsNode().boundsInRoot

        // 1. Floating `/` picker: composer-wide, 6dp above composer, overlapping context row without pushing it up
        compose.runOnIdle {
            tab.draft.setTextAndPlaceCursorAtEnd("/")
            tab.draft.edit { selection = TextRange(1) }
            tab.updatePickers("/", 1)
        }
        compose.waitForIdle()

        val slashPickerNode = compose.onNodeWithTag("composer-picker").fetchSemanticsNode()
        assertEquals("floating", slashPickerNode.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.StateDescription))
        val slashPicker = slashPickerNode.boundsInRoot
        val rowDuringSlash = compose.onNodeWithTag("chat-context-row").fetchSemanticsNode().boundsInRoot
        val composerDuringSlash = compose.onNodeWithTag("chat-composer").fetchSemanticsNode().boundsInRoot

        assertEquals(rowBefore.top, rowDuringSlash.top, 0.5f)
        assertTrue(
            "expected 6dp gap between floating slash picker and composer, got ${composerDuringSlash.top - slashPicker.bottom} vs $sixPx",
            abs((composerDuringSlash.top - slashPicker.bottom) - sixPx) <= 3f,
        )
        assertTrue(
            "expected floating slash picker width (${slashPicker.width}) to match composer (${composerBefore.width})",
            abs(composerBefore.width - slashPicker.width) <= 3f,
        )
        assertTrue(
            "expected floating slash picker (${slashPicker.top}) to cover context row (${rowDuringSlash.top})",
            slashPicker.top < rowDuringSlash.top,
        )

        // Clicking a filter tab inside the floating overlay works cleanly via Compose hit-testing
        compose.onNodeWithTag("slash-kind-tab:command").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("slash-kind-tab:command").assertIsSelected()
        compose.onNodeWithTag("slash-name:deploy-staging", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("slash-name:compact", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithTag("slash-name:ak-research", useUnmergedTree = true).fetchSemanticsNodes().let { assertEquals(0, it.size) }

        // 2. Floating `@chat` picker: same 6dp gap, composer-wide, zero shift on `chat-context-row`
        compose.runOnIdle {
            tab.draft.setTextAndPlaceCursorAtEnd("@chat")
            tab.draft.edit { selection = TextRange(5) }
            tab.updatePickers("@chat", 5)
        }
        compose.waitForIdle()

        val filePicker = compose.onNodeWithTag("composer-picker").fetchSemanticsNode().boundsInRoot
        val rowDuringFile = compose.onNodeWithTag("chat-context-row").fetchSemanticsNode().boundsInRoot
        val composerDuringFile = compose.onNodeWithTag("chat-composer").fetchSemanticsNode().boundsInRoot

        assertEquals(rowBefore.top, rowDuringFile.top, 0.5f)
        assertTrue(
            "expected 6dp gap between floating @ picker and composer, got ${composerDuringFile.top - filePicker.bottom} vs $sixPx",
            abs((composerDuringFile.top - filePicker.bottom) - sixPx) <= 3f,
        )
        assertTrue(
            "expected floating @ picker width (${filePicker.width}) to match composer (${composerBefore.width})",
            abs(composerBefore.width - filePicker.width) <= 3f,
        )
        assertTrue(
            "expected floating @ picker (${filePicker.top}) to cover context row (${rowDuringFile.top})",
            filePicker.top < rowDuringFile.top,
        )

        closeReader()
    }

    @Test
    fun chatPane_reconnectIndicator_transitionsHiddenToReconnectingToLostAndSwitchesPlaceholder() {
        val current = SessionInfo(id = "s-reconnect", providerId = "claude", title = "Offline chat")
        val tab = ChatTab("tab-reconnect", session = current, project = "p", providerId = "claude")
        val chat = ChatController(api, "p", current, scope, providerId = "claude", stale = sampleMessages)
        tab.controller = chat

        compose.mainClock.autoAdvance = false
        compose.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    Box(Modifier.size(width = 800.dp, height = 700.dp)) {
                        ChatPane(model = model, tab = tab)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()

        // Trigger real socket failure (MockWebServer returns 503 on `/ws/`) -> Connection.Reconnecting
        chat.start()
        val deadline = System.currentTimeMillis() + 5_000L
        while (chat.state.value.connection !is ChatController.Connection.Reconnecting && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue("expected ChatController to enter Reconnecting", chat.state.value.connection is ChatController.Connection.Reconnecting)

        compose.mainClock.advanceTimeByFrame()
        // Under 800ms grace period: ConnectionChip stays hidden, placeholder stays "Ask anything..."
        compose.onAllNodesWithTag("connection-chip:reconnecting").fetchSemanticsNodes().let { assertEquals(0, it.size) }
        assertEquals(
            "Ask anything...",
            compose.onNodeWithTag("composer-field").fetchSemanticsNode().config.getOrNull(ComposerPlaceholderKey),
        )

        // Past 800ms: Reconnecting chip appears and composer placeholder switches to offline text
        compose.mainClock.advanceTimeBy(900)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("connection-chip:reconnecting").assertIsDisplayed()
        assertEquals(
            "Will send when reconnected...",
            compose.onNodeWithTag("composer-field").fetchSemanticsNode().config.getOrNull(ComposerPlaceholderKey),
        )

        // Past 8000ms total: Lost chip appears ("Connection lost · Retry"), clicking restarts grace window
        compose.mainClock.advanceTimeBy(7_500)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("connection-chip:lost").assertIsDisplayed()
        compose.onNodeWithContentDescription("Connection lost — tap to retry").performClick()
        compose.mainClock.advanceTimeBy(50)
        compose.mainClock.advanceTimeByFrame()
        compose.onAllNodesWithTag("connection-chip:lost").fetchSemanticsNodes().let { assertEquals(0, it.size) }

        chat.stop()
    }
}
