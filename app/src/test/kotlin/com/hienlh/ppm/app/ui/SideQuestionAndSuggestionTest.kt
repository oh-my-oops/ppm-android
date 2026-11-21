package com.hienlh.ppm.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.Key
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ChatTab
import com.hienlh.ppm.app.sideQuestionCommand
import com.hienlh.ppm.app.ui.phone.chat.PhoneChatScreen
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.state.ChatController
import com.hienlh.ppm.state.ChatState
import com.hienlh.ppm.state.SideQuestionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mockwebserver3.MockWebServer
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

/**
 * `/btw`'s answer above the composer, Claude's suggested next prompt in the box (web 79846f551),
 * and the phone box's two starting lines (9e8ab6d63).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h1200dp-xxhdpi")
class SideQuestionAndSuggestionTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var model: AppModel
    private lateinit var chat: ChatController

    @Before
    fun setUp() {
        server.start()
        model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        val api = ApiClient(server.url("/"), "secret")
        model.api = api
        chat = ChatController(api, "p", null, scope, providerId = "claude")
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun showPanel(mobile: Boolean, state: () -> SideQuestionState, onClose: () -> Unit) {
        compose.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                SideQuestionPanel(state(), onClose)
            }
        }
    }

    @Test
    fun tablet_sideQuestionPanel_asks_answers_fails_andCloses() {
        var state by mutableStateOf<SideQuestionState>(SideQuestionState.Asking("where is the app?"))
        var closed = 0
        showPanel(mobile = false, state = { state }) { closed++ }
        compose.onNodeWithText("BY THE WAY · NOT ADDED TO THE CHAT").assertIsDisplayed()
        compose.onNodeWithText("where is the app?").assertIsDisplayed()
        compose.onNodeWithText("Asking Claude…").assertIsDisplayed()
        state = SideQuestionState.Answered("where is the app?", "In **src/app.ts**.")
        compose.onNodeWithTag("side-question-answer").assertIsDisplayed()
        state = SideQuestionState.Answered("where is the app?", null)
        compose.onNodeWithText("Claude gave no answer.").assertIsDisplayed()
        state = SideQuestionState.Failed("where is the app?", "This chat's Claude process is not running. Send a message first, then ask with /btw.")
        compose.onNodeWithTag("side-question-error").assertIsDisplayed()
        compose.onNodeWithTag("side-question-close").performClick()
        assertEquals(1, closed)
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xxhdpi")
    fun phone_sideQuestionPanel_showsTheAnswer_andCloses() {
        var closed = 0
        showPanel(mobile = true, state = { SideQuestionState.Answered("q", "An answer.") }) { closed++ }
        compose.onNodeWithTag("side-question-panel").assertIsDisplayed()
        compose.onNodeWithTag("side-question-answer").assertIsDisplayed()
        compose.onNodeWithTag("side-question-close").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun btw_isAskedOfTheSession_notSent_andARefusalKeepsTheText() {
        server.enqueue(mockwebserver3.MockResponse.Builder().body("""{"ok":true,"data":{"response":"Yes."}}""").build())
        val tab = ChatTab("t1", SessionInfo("s1", "claude", "Chat"), "p", "claude")
        tab.controller = chat
        tab.clientCommand = { text -> model.sideQuestionCommand(tab, text) }
        tab.draft.edit { replace(0, length, "/btw is it done?") }
        tab.submit()
        assertEquals("", tab.draft.text.toString())
        // The answer comes on OkHttp's thread and is applied on the main looper.
        repeat(100) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (model.sideQuestions.states.value["t1"] !is SideQuestionState.Asking) return@repeat
            Thread.sleep(50)
        }
        assertEquals(SideQuestionState.Answered("is it done?", "Yes."), model.sideQuestions.states.value["t1"])
        val request = server.takeRequest()
        assertEquals("/api/project/p/chat/sessions/s1/side-question", request.target)

        tab.draft.edit { replace(0, length, "/btw") }
        tab.submit()
        assertEquals("/btw", tab.draft.text.toString())
    }

    @Test
    fun tablet_suggestionIsThePlaceholderWithTab_andTabTakesIt() {
        val tab = ChatTab("t2", SessionInfo("s1", "claude", "Chat"), "p", "claude")
        compose.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                Box(Modifier.width(720.dp)) {
                    ChatComposer(
                        model = model,
                        tab = tab,
                        chat = chat,
                        state = ChatState(sessionId = "s1", promptSuggestion = "Run the tests"),
                        narrow = false,
                        fileDrop = ChatFileDrop(tab) { _, _ -> },
                        pickers = rememberComposerPickers(model, tab),
                    )
                }
            }
        }
        compose.onNodeWithTag("composer-field").assert(placeholder("Run the tests  (Tab)"))
        compose.onNodeWithTag("prompt-suggestion-chip").assertDoesNotExist()
        tab.requestFocus()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.Tab) }
        compose.waitForIdle()
        assertEquals("Run the tests", tab.draft.text.toString())
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xxhdpi")
    fun phoneLayoutComposer_suggestionIsATap_andTheBoxStartsTwoLinesTall() {
        val tab = ChatTab("t3", SessionInfo("s1", "claude", "Chat"), "p", "claude")
        var running by mutableStateOf(false)
        compose.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                ChatComposer(
                    model = model,
                    tab = tab,
                    chat = chat,
                    state = ChatState(sessionId = "s1", promptSuggestion = "Run the tests", phase = if (running) com.hienlh.ppm.protocol.SessionPhase.STREAMING else com.hienlh.ppm.protocol.SessionPhase.IDLE),
                    narrow = true,
                    fileDrop = ChatFileDrop(tab) { _, _ -> },
                    pickers = rememberComposerPickers(model, tab),
                )
            }
        }
        compose.onNodeWithTag("composer-field").assert(placeholder("Run the tests"))
        // `min-h-12` with `pt-2`: 40 dp of box under 8 dp of padding.
        val height = compose.onNodeWithTag("composer-field").getBoundsInRoot().let { it.bottom - it.top }
        assertTrue("field is $height tall", height >= 40.dp)
        compose.onNodeWithTag("prompt-suggestion-chip").performClick()
        assertEquals("Run the tests", tab.draft.text.toString())
        compose.onNodeWithTag("prompt-suggestion-chip").assertDoesNotExist()

        tab.draft.edit { replace(0, length, "") }
        running = true
        compose.waitForIdle()
        compose.onNodeWithTag("prompt-suggestion-chip").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xxhdpi")
    fun phoneScreen_suggestionChipFillsTheBox_andTheOpenBoxIsTwoLinesTall() {
        val draft = TextFieldState()
        val taken = mutableListOf<String>()
        var suggestion by mutableStateOf<String?>("Commit the change")
        compose.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneChatScreen(
                    composerState = draft,
                    onSendMessage = {},
                    onStopTurn = {},
                    onScrollToWaitingCard = null,
                    promptSuggestion = suggestion,
                    onPromptSuggestionTaken = { taken += it },
                    aboveComposerContent = { SideQuestionPanel(SideQuestionState.Asking("q?"), onClose = {}) },
                )
            }
        }
        compose.onNodeWithTag("side-question-panel").assertIsDisplayed()
        // The collapsed box's placeholder and the chip both say it.
        assertEquals(2, compose.onAllNodesWithText("Commit the change", useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onNodeWithTag("prompt-suggestion-chip").performClick()
        compose.waitForIdle()
        assertEquals("Commit the change", draft.text.toString())
        assertEquals(listOf("Commit the change"), taken)
        compose.onNodeWithTag("prompt-suggestion-chip").assertDoesNotExist()
        suggestion = null
        compose.waitForIdle()
        compose.onNodeWithTag("phone-composer-collapsed").assertDoesNotExist()
    }

    private fun placeholder(text: String) = androidx.compose.ui.test.SemanticsMatcher.expectValue(ComposerPlaceholderKey, text)
}
