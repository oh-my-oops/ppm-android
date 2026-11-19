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
                    """{"ok":true,"data":[
                        {"value":"gemini-4-neon","label":"Gemini 4 Neon","isDefault":true,"supportedEfforts":["low","medium","high","xhigh"]},
                        {"value":"gemini-4-carbon","label":"Gemini 4 Carbon","supportedEfforts":["low","medium","high"]},
                        {"value":"claude-opus-5-5","label":"Claude Opus 5.5","supportedEfforts":["low","medium","high"]}
                    ]}"""
                } else {
                    """{"ok":true,"data":[
                        {"value":"opus","label":"Claude Opus"},
                        {"value":"sonnet","label":"Claude Sonnet"},
                        {"value":"claude-sonnet-4-6","label":"Claude Sonnet 4.6","supportedEfforts":["low","medium","high","max"]},
                        {"value":"claude-haiku-4-5","label":"Claude Haiku 4.5","supportedEfforts":[]}
                    ]}"""
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
    fun `Antigravity offers Extra only on models with xhigh, resets to High when switching away, and hides Thinking`() {
        val api = ApiClient(server.url("/"), "secret")
        chat = ChatController(api, "p", null, scope, providerId = "antigravity")
        compose.setContent {
            accent = LocalPpmTokens.current.accent.color
            val state by chat.state.collectAsState()
            ModelColumns(catalog, chat, state, Modifier.width(320.dp), Modifier, onPicked = { closed++ })
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Gemini 4 Neon").fetchSemanticsNodes().isNotEmpty() }

        // Default model (Gemini 4 Neon) supports xhigh: Extra is offered, Max and Thinking stay hidden; Claude keeps its prefix
        assertEquals(
            listOf("Gemini 4 Neon", "High"),
            checked("Gemini 4 Neon", "Gemini 4 Carbon", "Claude Opus 5.5", "Low", "Medium", "High", "Extra"),
        )
        compose.onNodeWithText("Max").assertDoesNotExist()
        compose.onNodeWithText("Thinking").assertDoesNotExist()

        // Picking Extra on Neon, then switching to Carbon (no xhigh) hides Extra and resets effort to High
        press("Extra")
        assertEquals(Effort.XHIGH.value, chat.state.value.effort)
        press("Gemini 4 Carbon")
        assertEquals("gemini-4-carbon", chat.state.value.model)
        assertEquals(Effort.HIGH.value, chat.state.value.effort)
        compose.onNodeWithText("Extra").assertDoesNotExist()
        assertEquals(
            listOf("Gemini 4 Carbon", "High"),
            checked("Gemini 4 Neon", "Gemini 4 Carbon", "Claude Opus 5.5", "Low", "Medium", "High"),
        )

        // Legacy suffixed model id highlights base model + suffixed effort
        chat.setModel("claude-opus-5-5-medium")
        chat.setEffort(Effort.MEDIUM)
        compose.waitForIdle()
        assertEquals(
            listOf("Claude Opus 5.5", "Medium"),
            checked("Gemini 4 Neon", "Gemini 4 Carbon", "Claude Opus 5.5", "Low", "Medium", "High"),
        )
        compose.onNodeWithText("Extra").assertDoesNotExist()

        // A model saved with agy's xhigh suffix reads as Extra when that model supports xhigh
        chat.setModel("gemini-4-neon-xhigh")
        chat.setEffort(Effort.XHIGH)
        compose.waitForIdle()
        assertEquals(
            listOf("Gemini 4 Neon", "Extra"),
            checked("Gemini 4 Neon", "Gemini 4 Carbon", "Claude Opus 5.5", "Low", "Medium", "High", "Extra"),
        )
    }

    @Test
    fun `Claude hides Extra on 4_6 models and hides Extra and Max on Haiku 4_5`() {
        chat.setModel("opus")
        chat.setEffort(Effort.XHIGH)
        show()
        assertEquals(listOf("Opus", "Extra"), checked("Opus", "Sonnet", "Sonnet 4.6", "Haiku 4.5", "Low", "Medium", "High", "Extra", "Max"))

        // Switching to Sonnet 4.6 (no xhigh) hides Extra, keeps Max, and resets Extra -> High
        press("Sonnet 4.6")
        assertEquals("claude-sonnet-4-6", chat.state.value.model)
        assertEquals(Effort.HIGH.value, chat.state.value.effort)
        compose.onNodeWithText("Extra").assertDoesNotExist()
        compose.onNodeWithText("Max").assertExists()

        // Switching to Haiku 4.5 (supportedEfforts: []) hides both Extra and Max
        press("Haiku 4.5")
        assertEquals("claude-haiku-4-5", chat.state.value.model)
        compose.onNodeWithText("Extra").assertDoesNotExist()
        compose.onNodeWithText("Max").assertDoesNotExist()
        assertEquals(listOf("Haiku 4.5", "High"), checked("Opus", "Sonnet", "Sonnet 4.6", "Haiku 4.5", "Low", "Medium", "High"))
    }
}
