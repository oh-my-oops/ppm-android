package com.hienlh.ppm.app.ui

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.state.ChatController
import com.hienlh.ppm.state.Effort
import com.hienlh.ppm.state.ModelCatalog
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the composer's model picker does with a press, through the real picker over a real chat controller and a model
 * list a server answered: a model row and an effort row choose that model or effort and close the picker, and the Thinking
 * switch turns thinking over and leaves it open, as the web's `ModelThinkingSelector` does; and the rows that end in a check
 * are the ones the chat has chosen. [ComposerModelPickerFocusTest] has the keyboard; this has what the rows hand on and show.
 * Native graphics, 3 pixels to the dp, for the checks.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class ComposerModelColumnsTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var catalog: ModelCatalog
    private lateinit var chat: ChatController
    private var closed = 0
    private var accent = Color.Unspecified

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = if (request.url.encodedPath.endsWith("/antigravity/models")) {
                    """{"ok":true,"data":[{"value":"gemini-4-argon","label":"Gemini 4 Argon","isDefault":true},{"value":"claude-opus-5-5","label":"Claude Opus 5.5"}]}"""
                } else {
                    """{"ok":true,"data":[{"value":"opus","label":"Claude Opus"},{"value":"sonnet","label":"Claude Sonnet"}]}"""
                }
                return MockResponse.Builder().code(200)
                    .addHeader("Content-Type", "application/json")
                    .body(body).build()
            }
        }
        server.start()
        val api = ApiClient(server.url("/"), "secret")
        catalog = ModelCatalog(api, "p")
        chat = ChatController(api, "p", null, scope, providerId = "claude")
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun show() {
        compose.setContent {
            accent = LocalPpmTokens.current.accent.color
            val state by chat.state.collectAsState()
            ModelColumns(catalog, chat, state, Modifier.width(320.dp), Modifier, onPicked = { closed++ })
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Sonnet").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun press(label: String) {
        compose.onNodeWithText(label).performClick()
        compose.waitForIdle()
    }

    private fun checked(vararg labels: String) = labels.filter { compose.onNode(hasClickAction() and hasText(it)).endsInAccent(accent) }

    @Test
    fun `a model row chooses that model and closes the picker`() {
        show()
        press("Sonnet")
        assertEquals("sonnet", chat.state.value.model)
        assertEquals(1, closed)
    }

    @Test
    fun `an effort row chooses that effort and closes the picker`() {
        show()
        press("Medium")
        assertEquals(Effort.MEDIUM.value, chat.state.value.effort)
        assertEquals(1, closed)
    }

    @Test
    fun `the Thinking switch turns thinking off and on again, and leaves the picker open`() {
        show()
        assertEquals("a chat starts with thinking on", true, chat.state.value.thinking)
        press("Thinking")
        assertEquals(false, chat.state.value.thinking)
        press("Thinking")
        assertEquals(true, chat.state.value.thinking)
        assertEquals(0, closed)
    }

    @Test
    fun `a chat that has chosen nothing shows the default effort checked and no model`() {
        show()
        assertEquals(listOf("High"), checked("Opus", "Sonnet", "Low", "Medium", "High", "Extra", "Max"))
    }

    @Test
    fun `the checks are on the model and the effort the chat has chosen, and move with a pick`() {
        chat.setModel("sonnet")
        chat.setEffort(Effort.LOW)
        show()
        assertEquals(listOf("Sonnet", "Low"), checked("Opus", "Sonnet", "Low", "Medium", "High", "Extra", "Max"))

        press("Opus")
        press("Max")
        assertEquals(listOf("Opus", "Max"), checked("Opus", "Sonnet", "Low", "Medium", "High", "Extra", "Max"))
    }

    @Test
    fun `a pick of one kind leaves the other two as they were`() {
        show()
        val effort = chat.state.value.effort
        press("Opus")
        assertEquals("opus", chat.state.value.model)
        assertEquals(effort, chat.state.value.effort)
        assertEquals(true, chat.state.value.thinking)
        press("Low")
        assertEquals("opus", chat.state.value.model)
        assertEquals(Effort.LOW.value, chat.state.value.effort)
        assertEquals(true, chat.state.value.thinking)
    }

    @Test
    fun `Antigravity's Auto is the model its list marks, keeps Claude prefix, offers four efforts up to Extra and hides Thinking`() {
        val api = ApiClient(server.url("/"), "secret")
        chat = ChatController(api, "p", null, scope, providerId = "antigravity")
        compose.setContent {
            accent = LocalPpmTokens.current.accent.color
            val state by chat.state.collectAsState()
            ModelColumns(catalog, chat, state, Modifier.width(320.dp), Modifier, onPicked = { closed++ })
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Gemini 4 Argon").fetchSemanticsNodes().isNotEmpty() }

        // No model chosen resolves to the one the list marks isDefault; Claude Opus 5.5 keeps "Claude "; Low/Medium/High/Extra exist, no Max; no Thinking switch
        assertEquals(
            listOf("Gemini 4 Argon", "High"),
            checked("Gemini 4 Argon", "Claude Opus 5.5", "Low", "Medium", "High", "Extra"),
        )
        compose.onNodeWithText("Max").assertDoesNotExist()
        compose.onNodeWithText("Thinking").assertDoesNotExist()

        // Legacy suffixed model id highlights base model + suffixed effort
        chat.setModel("claude-opus-5-5-medium")
        compose.waitForIdle()
        assertEquals(
            listOf("Claude Opus 5.5", "Medium"),
            checked("Gemini 4 Argon", "Claude Opus 5.5", "Low", "Medium", "High", "Extra"),
        )

        // A model saved with agy's xhigh suffix reads as Extra
        chat.setModel("gemini-4-argon-xhigh")
        compose.waitForIdle()
        assertEquals(
            listOf("Gemini 4 Argon", "Extra"),
            checked("Gemini 4 Argon", "Claude Opus 5.5", "Low", "Medium", "High", "Extra"),
        )
    }
}
