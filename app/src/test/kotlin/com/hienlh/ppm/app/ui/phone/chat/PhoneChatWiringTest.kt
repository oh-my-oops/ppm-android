package com.hienlh.ppm.app.ui.phone.chat

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ChatTab
import com.hienlh.ppm.app.PrefsStore
import com.hienlh.ppm.app.ui.files.installBlankFileIcons
import com.hienlh.ppm.app.ui.phone.PhoneChatContent
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.BackgroundShell
import com.hienlh.ppm.protocol.ChatFrame
import com.hienlh.ppm.protocol.ProjectInfo
import com.hienlh.ppm.protocol.QueuedMessage
import com.hienlh.ppm.protocol.SessionPhase
import com.hienlh.ppm.protocol.ReplyReference
import com.hienlh.ppm.protocol.SessionFileChange
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.TurnStop
import com.hienlh.ppm.state.DraftLocalStore
import com.hienlh.ppm.state.PermissionMode
import com.hienlh.ppm.state.UsageReader
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The phone chat wired to the app's state, as ChatPane wires the tablet's: each test drives the real
 * [AppModel] and [ChatTab] against a fake server and checks what the screen shows and what it sends.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h840dp-xhdpi")
class PhoneChatWiringTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val requests = CopyOnWriteArrayList<Pair<String, String>>()
    private lateinit var context: Context
    private lateinit var model: AppModel
    private lateinit var api: ApiClient

    @Volatile private var reviewed = false

    private val session = SessionInfo(id = "s-1", providerId = "claude", title = "Fix the parser")

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                requests += (request.method + " " + path) to (request.body?.utf8() ?: "")
                val data = when {
                    path.startsWith("/ws/") -> return MockResponse.Builder().code(503).build()
                    path.endsWith("/file-changes/count") -> """{"count":1}"""
                    path.endsWith("/file-changes/reviewed") -> {
                        reviewed = true
                        """{}"""
                    }
                    path.endsWith("/file-changes") ->
                        """{"files":[{"path":"/repo/src/Parser.kt","status":"modified","additions":3,"deletions":1,"version":"v1","reviewed":$reviewed}]}"""
                    path.endsWith("/chat/usage") -> """{"totalCostUsd":0.1234}"""
                    path.endsWith("/messages") -> """{"messages":[],"total":0,"hasMore":false}"""
                    else -> """{}"""
                }
                return MockResponse.Builder().code(200).body("""{"ok":true,"data":$data}""").build()
            }
        }
        server.start()
        context = ApplicationProvider.getApplicationContext<Application>()
        model = AppModel(context)
        api = ApiClient(server.url("/"), "secret")
        model.api = api
        model.selectedProject = "p"
        model.projects = listOf(ProjectInfo(name = "p", path = "/repo"))
        model.sessions = listOf(session)
        installBlankFileIcons()
    }

    /**
     * Waits for [condition], letting the main looper run between checks: what the model and the
     * composition's coroutines do after a server answer resumes there, which `waitUntil` alone never runs.
     */
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            composeRule.waitForIdle()
            if (condition()) return
            check(System.currentTimeMillis() < deadline) { "Condition not met in 5 s" }
            Thread.sleep(20)
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun startedTab(): ChatTab = ChatTab("tab-1", session, "p", "claude").also {
        it.start(api, scope, DraftLocalStore(PrefsStore(context, "phone-chat-wiring-test"), "server"))
    }

    private fun show(tab: ChatTab, visited: MutableList<String> = mutableListOf()) {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneChatContent(model, tab, "Fix the parser", { visited += it })
            }
        }
        composeRule.waitForIdle()
    }

    private fun feed(tab: ChatTab, frame: ChatFrame) {
        composeRule.runOnIdle {
            tab.controller!!.transcript.handle(frame)
            tab.controller!!.markTeamRead()
        }
        composeRule.waitForIdle()
    }

    @Test
    fun sessionInfo_showsTheModeLabelAndSessionCost_andRenameRenamesTheSession() {
        val tab = startedTab()
        show(tab)

        // The usage is first read half a second after the chat shows (`UsageReader.FIRST_READ_MILLIS`).
        composeRule.mainClock.advanceTimeBy(UsageReader.FIRST_READ_MILLIS + 100)
        composeRule.onNodeWithText("Info").performClick()
        waitFor { composeRule.onAllNodesWithText("$0.1234").fetchSemanticsNodes().isNotEmpty() }
        val mode = tab.permissionMode ?: PermissionMode.FALLBACK
        composeRule.onNodeWithText(mode.label).assertExists()
        // No event stream: nothing to mark unread, so the row is not offered.
        assertTrue(composeRule.onAllNodesWithText("Mark as unread").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithText("Show logs for this chat").assertExists()
        composeRule.onNodeWithText("Session debug info").assertExists()

        composeRule.onNodeWithText("Rename").performClick()
        // The dialog's field, not the composer's underneath it.
        composeRule.onAllNodes(hasSetTextAction()).onLast().performTextReplacement("Parser rewrite")
        composeRule.onNodeWithText("OK").performClick()
        waitFor { model.sessions.first().title == "Parser rewrite" }
    }

    @Test
    fun sessionDebugInfo_opensTheDebugDialogForThisChat() {
        val tab = startedTab()
        show(tab)

        composeRule.onNodeWithText("Info").performClick()
        composeRule.onNodeWithText("Session debug info").performClick()
        waitFor { composeRule.onAllNodesWithText("Rendered messages").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun changesChip_countsTheSessionsFiles_reviewedTogglePosts_andDiffOpensTheSessionReview() {
        val tab = startedTab()
        show(tab)

        waitFor { composeRule.onAllNodesWithText("1 file").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("1 file").performClick()
        waitFor { composeRule.onAllNodesWithText("src/Parser.kt").fetchSemanticsNodes().isNotEmpty() }

        composeRule.onNodeWithContentDescription("Reviewed src/Parser.kt").performClick()
        waitFor { requests.any { it.first.endsWith("/file-changes/reviewed") } }
        val body = requests.first { it.first.endsWith("/file-changes/reviewed") }.second
        assertTrue(body, body.contains("/repo/src/Parser.kt") && body.contains("\"reviewed\":true"))

        composeRule.onNodeWithText("src/Parser.kt").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("phone-session-review").assertExists()
        composeRule.onNodeWithText("Changes: Fix the parser", substring = true).assertExists()
    }

    @Test
    fun attachSheet_pickedFileBecomesADraftAttachment_andCameraIsHiddenWithNoCameraApp() {
        val tab = startedTab()
        val picked = File(context.cacheDir, "notes.txt").apply { writeText("hello") }
        val launched = mutableListOf<String>()
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                launched += contract::class.java.simpleName
                dispatchResult(requestCode, listOf(Uri.fromFile(picked)))
            }
        }
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    PhoneChatContent(model, tab, "Fix the parser", {})
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Attach").performClick()
        assertTrue(composeRule.onAllNodesWithText("Camera").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithText("Files").performClick()
        composeRule.waitForIdle()
        waitFor { tab.attachments.isNotEmpty() }
        assertEquals(listOf("GetMultipleContents"), launched)
        assertEquals("notes.txt", tab.attachments.single().name)
        waitFor { composeRule.onAllNodesWithText("notes.txt").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun backgroundCommand_barShowsTheSessionsShell_andViewOutputOpensIt() {
        val tab = startedTab()
        show(tab)

        feed(tab, ChatFrame.BackgroundRegistry(listOf(BackgroundShell("sh-1", "npm run dev", "/tmp/sh-1.output"))))
        composeRule.onNodeWithText("npm run dev").assertExists()
        composeRule.onNodeWithText("View output").performClick()
        assertEquals("sh-1", model.backgroundOutput?.shellId)

        // A stop asked for: no second Stop, and the bar says so.
        feed(tab, ChatFrame.BackgroundRegistry(listOf(BackgroundShell("sh-1", "npm run dev", "/tmp/sh-1.output", status = "stopping"))))
        composeRule.onNodeWithText("stopping…").assertExists()
        assertTrue(composeRule.onAllNodesWithText("Stop").fetchSemanticsNodes().isEmpty())

        feed(tab, ChatFrame.BackgroundRegistry(listOf(BackgroundShell("sh-1", "npm run dev", "/tmp/sh-1.output", status = "stopped"))))
        assertTrue(composeRule.onAllNodesWithText("npm run dev").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun stoppedTurn_offersContinue_andMcpServersNeedingSignIn_showTheirBar() {
        val tab = startedTab()
        show(tab)

        feed(tab, ChatFrame.TurnStopped(TurnStop(subtype = "error_max_turns", message = "Max turns reached")))
        composeRule.onNodeWithText("Continue", substring = true).assertExists()

        feed(tab, ChatFrame.McpStatus(listOf("linear")))
        composeRule.onNodeWithText("MCP server needs sign-in:", substring = true).assertExists()
    }

    @Test
    fun replyBanner_followsTheTabsReply_andCancelClearsIt() {
        val tab = startedTab()
        show(tab)

        composeRule.runOnIdle {
            tab.selectReply(ReplyReference("s-1", "claude", "a1", role = ReplyReference.Role.ASSISTANT, timestamp = Instant.EPOCH.toString(), quote = "Use a Pratt parser", truncated = false))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Replying to AI").assertExists()
        composeRule.onNodeWithContentDescription("Cancel Replying to AI").performClick()
        composeRule.waitForIdle()
        assertNull(tab.replyTo)
        assertTrue(composeRule.onAllNodesWithText("Replying to AI").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun noSpecMode_noTurnJump_andTheAccountChipOpensTheAccountSwitcher() {
        val tab = startedTab()
        show(tab)

        assertTrue(composeRule.onAllNodesWithText("✦ Spec").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithText("Default account", substring = true).performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithTag("phone-account-sheet").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun aMessageSentMidTurn_drawsNoBubble_andThereIsNoPriorityChip() {
        val tab = startedTab()
        show(tab)
        feed(tab, ChatFrame.PhaseChanged(SessionPhase.THINKING))
        // The Now/Next/Later chip is gone: the server queues whatever a message carries.
        assertTrue(composeRule.onAllNodesWithText("Next").fetchSemanticsNodes().isEmpty())

        composeRule.onNode(hasSetTextAction()).performTextReplacement("one more thing")
        composeRule.onNodeWithContentDescription("Send message").performClick()
        composeRule.waitForIdle()
        assertEquals("", tab.draft.text.toString())
        assertTrue("queued, not drawn", tab.controller!!.state.value.items.none { it.text == "one more thing" })
    }

    @Test
    fun theQueueStack_showsTheServersQueue_editBringsTheTextBack_andRemoveDoesNot() {
        val tab = startedTab()
        show(tab)
        feed(tab, ChatFrame.PhaseChanged(SessionPhase.THINKING))
        feed(
            tab,
            ChatFrame.QueueState(
                listOf(
                    QueuedMessage("q1", "fix the tests too", 0),
                    QueuedMessage("q2", "and the docs", 2),
                ),
            ),
        )
        composeRule.onNodeWithText("2 queued · sent when the AI is ready").assertExists()
        composeRule.onNodeWithText("fix the tests too").assertExists()
        composeRule.onNodeWithContentDescription("2 images").assertExists()
        composeRule.onNodeWithText("Send now").assertExists()

        // Remove: what the server hands back is dropped.
        composeRule.onAllNodes(hasContentDescription("Remove queued message")).onLast().performClick()
        feed(tab, ChatFrame.QueueTakenBack("q2", "and the docs", null))
        assertEquals("", tab.draft.text.toString())

        // Edit: the text joins the box.
        composeRule.onAllNodes(hasContentDescription("Edit queued message")).onFirst().performClick()
        feed(tab, ChatFrame.QueueTakenBack("q1", "fix the tests too", null))
        assertEquals("fix the tests too", tab.draft.text.toString())
        assertNull(tab.controller!!.state.value.queueAnswer)

        feed(tab, ChatFrame.QueueState(emptyList()))
        assertTrue(composeRule.onAllNodesWithTag("phone-queued-messages").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theActivityChip_listsTheSessionsThisChatOpened_inItsSheet() {
        model.sessions = listOf(session, SessionInfo(id = "child-1", providerId = "claude", title = "Write the tests", spawnedBy = "s-1"))
        val tab = startedTab()
        show(tab)

        composeRule.onNodeWithTag("activity-chip").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("activity-tab:sessions").assertExists()
        // The sheet's row, with its status (the welcome list under it names the chat too).
        composeRule.onNode(hasText("Write the tests") and hasText("Idle")).assertExists()
    }

    @Test
    fun noActivityChip_whileTheChatHasNoAgentsOrLinkedSessions() {
        val tab = startedTab()
        show(tab)
        assertTrue(composeRule.onAllNodesWithTag("activity-chip").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun labels_elapsedCostAndChangesChip() {
        val start = Instant.parse("2026-11-11T10:00:00Z")
        assertEquals("0:04", phoneElapsedLabel(start, start.plusSeconds(4)))
        assertEquals("12:05", phoneElapsedLabel(start, start.plusSeconds(725)))
        assertEquals("1:00:01", phoneElapsedLabel(start, start.plusSeconds(3601)))
        assertEquals("—", phoneCostLabel(null))
        assertEquals("$0.0500", phoneCostLabel(0.05))

        val file = SessionFileChange("/repo/a.kt", SessionFileChange.Status.MODIFIED, additions = 2, deletions = 1)
        assertNull(phoneChangesChip(emptyList(), loaded = false, unloadedCount = 0))
        assertEquals(PhoneChangesChipState(3), phoneChangesChip(emptyList(), loaded = false, unloadedCount = 3))
        assertEquals(PhoneChangesChipState(1, 2, 1), phoneChangesChip(listOf(file), loaded = true, unloadedCount = 0))
        assertEquals(PhoneChangesChipState(1, allReviewed = true), phoneChangesChip(listOf(file.copy(reviewed = true)), loaded = true, unloadedCount = 0))
        assertEquals(listOf("a.kt"), phoneChangedFiles(listOf(file), "/repo").map { it.displayPath })
    }
}
