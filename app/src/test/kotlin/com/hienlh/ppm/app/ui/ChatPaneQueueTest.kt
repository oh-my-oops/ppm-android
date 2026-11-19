package com.hienlh.ppm.app.ui

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ChatTab
import com.hienlh.ppm.app.PrefsStore
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.ChatFrame
import com.hienlh.ppm.protocol.ProjectInfo
import com.hienlh.ppm.protocol.QueuedMessage
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.SessionPhase
import com.hienlh.ppm.state.DraftLocalStore
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
 * The tablet's queue stack (`queued-messages-stack.tsx`): a message sent while the AI works is not
 * drawn as a bubble (the server echoes it when the AI starts on it), so the stack above the composer
 * is the only place it shows until then.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h1200dp-xxhdpi")
class ChatPaneQueueTest {

    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var context: Context
    private lateinit var model: AppModel
    private lateinit var api: ApiClient
    private val session = SessionInfo(id = "s-1", providerId = "claude", title = "Fix the parser")

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val data = when {
                    path.startsWith("/ws/") -> return MockResponse.Builder().code(503).build()
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
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun startedTab(): ChatTab = ChatTab("tab-1", session, "p", "claude").also {
        it.start(api, scope, DraftLocalStore(PrefsStore(context, "chat-pane-queue-test"), "server"))
    }

    private fun show(tab: ChatTab) {
        compose.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                Box(Modifier.size(width = 800.dp, height = 700.dp)) {
                    ChatPane(model = model, tab = tab)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun feed(tab: ChatTab, frame: ChatFrame) {
        compose.runOnIdle {
            tab.controller!!.transcript.handle(frame)
            tab.controller!!.markTeamRead()
        }
        compose.waitForIdle()
    }

    @Test
    fun aMessageSentMidTurn_showsInTheQueueStack_andHasNoPriorityChip() {
        val tab = startedTab()
        show(tab)
        feed(tab, ChatFrame.PhaseChanged(SessionPhase.THINKING))
        assertTrue(compose.onAllNodesWithTag("composer-priority-chip").fetchSemanticsNodes().isEmpty())

        compose.runOnIdle { tab.draft.setTextAndPlaceCursorAtEnd("one more thing") }
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.waitForIdle()
        assertEquals("", tab.draft.text.toString())
        assertTrue("queued, not drawn", tab.controller!!.state.value.items.none { it.text == "one more thing" })

        // The server's queue as it answers that send.
        feed(tab, ChatFrame.QueueState(listOf(QueuedMessage("q1", "one more thing", 0))))
        compose.onNodeWithText("1 queued · sent when the AI is ready").assertExists()
        compose.onNodeWithText("one more thing").assertExists()
        compose.onNodeWithText("Send now").assertExists()
    }

    @Test
    fun editBringsAQueuedMessageBackToTheComposer_andRemoveDoesNot() {
        val tab = startedTab()
        show(tab)
        feed(tab, ChatFrame.PhaseChanged(SessionPhase.THINKING))
        feed(
            tab,
            ChatFrame.QueueState(listOf(QueuedMessage("q1", "fix the tests too", 0), QueuedMessage("q2", "and the docs", 0))),
        )
        compose.onNodeWithText("2 queued · sent when the AI is ready").assertExists()

        compose.onAllNodes(hasContentDescription("Remove queued message")).onLast().performClick()
        feed(tab, ChatFrame.QueueTakenBack("q2", "and the docs", null))
        assertEquals("", tab.draft.text.toString())

        compose.onAllNodes(hasContentDescription("Edit queued message")).onFirst().performClick()
        feed(tab, ChatFrame.QueueTakenBack("q1", "fix the tests too", null))
        assertEquals("fix the tests too", tab.draft.text.toString())
        assertNull(tab.controller!!.state.value.queueAnswer)

        feed(tab, ChatFrame.QueueState(emptyList()))
        assertTrue(compose.onAllNodesWithText("queued · sent when the AI is ready", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aStopKeepsTheQueue_held_andTheHeaderSaysItWaitsForThePerson() {
        val tab = startedTab()
        show(tab)
        feed(tab, ChatFrame.PhaseChanged(SessionPhase.THINKING))
        feed(tab, ChatFrame.QueueState(listOf(QueuedMessage("q1", "queued before stop", 0))))
        compose.onNodeWithText("1 queued · sent when the AI is ready").assertExists()

        // Stop: the server keeps the entry, paused, and the turn ends without sending it.
        feed(tab, ChatFrame.QueueState(listOf(QueuedMessage("q1", "queued before stop", 0, paused = true))))
        feed(tab, ChatFrame.PhaseChanged(SessionPhase.IDLE))
        compose.onNodeWithText("1 held after Stop · sent only when you send them").assertExists()
        compose.onNodeWithText("queued before stop").assertExists()
        compose.onNodeWithText("Send now").assertExists()

        // A message queued after the Stop goes at its turn's end as before.
        feed(
            tab,
            ChatFrame.QueueState(listOf(QueuedMessage("q1", "queued before stop", 0, paused = true), QueuedMessage("q2", "after", 0))),
        )
        compose.onNodeWithText("2 queued · 1 held after Stop").assertExists()
    }
}
