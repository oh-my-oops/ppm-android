package com.hienlh.ppm.app.ui.settings.assistant

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.assistant
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.ProjectInfo
import com.hienlh.ppm.state.assistant.AssistantStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** Settings → PPM Assistant, on a tablet and on a phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-xhdpi")
class AssistantSettingsUiTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val server = MockWebServer()
    private val asked = CopyOnWriteArrayList<String>()
    private val bodies = CopyOnWriteArrayList<Pair<String, String>>()
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val settings = """{"settings":{"default_provider":null,"providers":{},"instructions":"Be brief.",
        "persona":{"name":"","address_forms":[],"soul":""},
        "mcp_servers":[{"id":"s1","name":"github","enabled":true,"transport":"stdio","command":"npx","args":["-y","gh"],"env":{"TOKEN":""}}],
        "digest":{"enabled":true,"time":"08:00"},"quiet_hours":{"enabled":false,"start":"22:00","end":"07:00"},"timezone":"Asia/Ho_Chi_Minh",
        "manager_default_mode":"default","manager_template":"","restart_news":true,"auto_readonly_commands":true,"act_on_own":false},
        "providers":[{"id":"claude","name":"Claude"}],"limits":{"instructionsMaxChars":8000,"maxServers":20},"managerTemplateBuiltIn":"# Rules"}"""

    private val routes = mapOf(
        "GET /api/assistant/settings" to settings,
        "PUT /api/assistant/settings" to settings,
        "GET /api/assistant/telegram/legacy-memories" to """{"memories":[{"id":7,"project":"_global","category":"fact","content":"Likes short answers","createdAt":1}]}""",
        "GET /api/settings/ai/providers/claude/models" to """[{"value":"opus","label":"Opus"}]""",
        "GET /api/assistant/notes" to """{"notes":[{"id":3,"text":"Ship on Friday","source":"assistant","unverified":true,"createdAt":1,"updatedAt":1762214400000}],"used":14,"cap":4000,"noteMax":300}""",
        "POST /api/assistant/notes" to """{"note":{}}""",
        "PATCH /api/assistant/notes/3" to """{"note":{}}""",
        "GET /api/assistant/managers/web/rules" to """{"rules":{"approved":"Keep it tidy","accepted":"Keep it tidy","proposed":"Keep it tidy\nPush to main","pendingHash":"h9","approvedAt":1,"addedSince":["Use bun"],"addedSinceUnder":["Allowed without asking"],"proposedDiff":{"added":["Push to main"],"removed":[]}}}""",
        "POST /api/assistant/managers/web/rules/decision" to """{"rules":{"approved":"Keep it tidy","accepted":"Keep it tidy","proposed":null,"pendingHash":null,"approvedAt":2,"addedSince":[],"proposedDiff":null}}""",
        "GET /api/assistant/managers" to """{"managers":[{"projectName":"web","projectPath":"/w","sessionId":"m1","providerId":"claude","title":"QL web","phase":"idle","permissionMode":"default","waiting":0,"rulesPending":true,"digest":true,"telegramDetail":"full","toolsPending":true}]}""",
        "PATCH /api/assistant/managers/web" to "{}",
        "GET /api/assistant/main-session" to """{"main":{"sessionId":"a1","providerId":"claude"}}""",
        "PUT /api/assistant/main-session" to "{}",
        "GET /api/assistant/telegram" to """{"enabled":true,"running":true,"quietWhileInPpm":true,"chats":[{"chatId":"42","name":"My phone","sessionId":"a1","sessionTitle":null}]}""",
        "POST /api/assistant/telegram/bind" to "{}",
        "GET /api/project/__assistant__/chat/sessions" to """{"sessions":[{"id":"a1","providerId":"claude","title":"Morning","createdAt":"2025-11-04T00:00:00Z"},{"id":"a2","providerId":"claude","title":"Side quest","createdAt":"2025-11-05T00:00:00Z"}],"hasMore":false}""",
        "GET /api/settings/clawbot/telegram" to """{"configured":true,"enabled":true,"running":true,"botUsername":"ppm_helper_bot","sharedWithNotifications":false,"chats":[{"chatId":"42","name":"My phone"}],"connect":{"active":false,"expiresAt":null,"error":null}}""",
        "GET /api/settings/clawbot" to """{"enabled":true,"show_tool_calls":true,"debounce_ms":2000,"quiet_while_in_ppm":true}""",
        "PUT /api/settings/clawbot" to """{"enabled":true,"show_tool_calls":true,"debounce_ms":5000,"quiet_while_in_ppm":true}""",
        "GET /api/assistant/cards" to """{"cards":[]}""",
        "GET /api/assistant/cards/open" to """{"cards":[]}""",
        "GET /api/assistant/waiting" to """{"total":0,"byProject":{}}""",
    )

    private fun start(mobile: Boolean, tab: String): AppModel {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val line = request.method + " " + request.target
                asked += line
                request.body?.utf8()?.takeIf { it.isNotEmpty() }?.let { bodies += line to it }
                val key = line.substringBefore('?').takeIf { it in routes }
                return key?.let { MockResponse.Builder().addHeader("Connection", "close").body("""{"ok":true,"data":${routes[it]}}""").build() }
                    ?: MockResponse.Builder().code(404).addHeader("Connection", "close").build()
            }
        }
        server.start()
        val model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        val api = ApiClient(server.url("/"), java.util.UUID.randomUUID().toString())
        model.api = api
        model.projects = listOf(ProjectInfo("web", "/w"), ProjectInfo("api", "/a"))
        val store = AssistantStore(api, storeScope)
        store.start()
        val deadline = System.currentTimeMillis() + 10_000
        while (store.view.value.let { it.managers == null || it.telegram == null || !it.mainLoaded } && System.currentTimeMillis() < deadline) Thread.sleep(10)
        model.assistant.use(store, emptyList())
        AssistantSettingsTab.tab = tab
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                Column(Modifier.verticalScroll(rememberScrollState())) { AssistantSettingsSection(model) }
            }
        }
        return model
    }

    @After
    fun stop() {
        storeScope.cancel()
        server.close()
        AssistantSettingsTab.tab = "general"
    }

    private fun waitAsked(line: String) = composeRule.waitUntil(5_000) { asked.any { it.startsWith(line) } }

    private fun waitText(text: String) = composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun waitTag(tag: String) = composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun body(line: String) = Json.parseToJsonElement(bodies.last { it.first.startsWith(line) }.second).jsonObject

    private fun node(text: String): SemanticsNodeInteraction = composeRule.onNode(hasText(text), useUnmergedTree = true)

    @Test
    fun `General saves its own fields only, copies an old memory in on request, and checks an MCP server (tablet)`() {
        start(mobile = false, tab = "general")
        waitTag("asst-instructions")
        composeRule.onNodeWithTag("asst-general-save").assertIsNotEnabled()
        // The old memories arrive by their own request, after the form: wait for them, not only the form.
        waitText("Remembered by PPMBot (1)")
        node("Remembered by PPMBot (1)").performScrollTo().performClick()
        composeRule.onNodeWithTag("asst-memory-copy-7", useUnmergedTree = true).performScrollTo().performClick()
        node("Unsaved changes").assertExists()
        composeRule.onNodeWithTag("asst-add-mcp").performScrollTo().performClick()
        composeRule.onNodeWithTag("asst-mcp-name").performTextReplacement("GitHub")
        composeRule.onNodeWithTag("asst-mcp-command").performTextReplacement("npx")
        composeRule.onNodeWithTag("asst-mcp-done").performClick()
        composeRule.onNodeWithTag("asst-mcp-error", useUnmergedTree = true).assertExists()
        node("Another server is already named \"GitHub\"").assertExists()
        composeRule.onNodeWithTag("asst-mcp-name").performTextReplacement("docs")
        composeRule.onNodeWithTag("asst-mcp-transport-http").performClick()
        composeRule.onNodeWithTag("asst-mcp-url").performTextReplacement("https://mcp.example.com/mcp")
        composeRule.onNodeWithTag("asst-mcp-add-pair").performClick()
        composeRule.onNodeWithTag("asst-mcp-key-0").performTextReplacement("Authorization")
        composeRule.onNodeWithTag("asst-mcp-done").performClick()
        node("Authorization needs a value").assertExists()
        composeRule.onNodeWithTag("asst-mcp-value-0").performTextReplacement("hdr-value-9d2f")
        composeRule.onNodeWithTag("asst-mcp-done").performClick()
        waitTag("asst-mcp-docs")
        composeRule.onNodeWithTag("asst-general-save").performScrollTo().performClick()
        waitAsked("PUT /api/assistant/settings")
        val sent = body("PUT /api/assistant/settings")
        assertEquals("\"Be brief.\\nLikes short answers\"", sent["instructions"].toString())
        assertFalse("digest" in sent)
        assertFalse("act_on_own" in sent)
        assertEquals(2, (sent["mcp_servers"] as kotlinx.serialization.json.JsonArray).size)
        // The value typed for the header is a secret: it never reaches a trace.
        assertEquals("sent [REDACTED]", com.hienlh.ppm.client.TraceRedactor.Global.redact("sent hdr-value-9d2f"))
    }

    @Test
    fun `acting on its own asks first, and the switch saves alone (tablet)`() {
        start(mobile = false, tab = "general")
        waitTag("asst-act-on-own-switch")
        composeRule.onNodeWithTag("asst-act-on-own-switch").performScrollTo().performClick()
        node("Let the Assistant act on its own?").assertExists()
        assertTrue(asked.none { it.startsWith("PUT /api/assistant/settings") })
        composeRule.onNodeWithTag("asst-confirm").performClick()
        waitAsked("PUT /api/assistant/settings")
        val sent = body("PUT /api/assistant/settings")
        assertEquals("true", sent["act_on_own"].toString())
        assertFalse("digest" in sent)
        assertEquals("\"Be brief.\"", sent["instructions"].toString())
    }

    @Test
    fun `the notebook shows the meter and an unverified line, and adds and verifies (phone)`() {
        start(mobile = true, tab = "notebook")
        waitText("Ship on Friday")
        node("14 / 4,000 characters").assertExists()
        composeRule.onNodeWithTag("asst-note-unverified-3", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("asst-note-verify-3", useUnmergedTree = true).performClick()
        waitAsked("PATCH /api/assistant/notes/3")
        assertEquals("""{"verified":true}""", bodies.last { it.first.startsWith("PATCH /api/assistant/notes/3") }.second)
        composeRule.onNodeWithTag("asst-note-add").performScrollTo().performClick()
        composeRule.onNodeWithTag("asst-note-new").performTextReplacement("Report in Vietnamese")
        composeRule.onNodeWithTag("asst-note-new-confirm").performClick()
        waitAsked("POST /api/assistant/notes")
        assertEquals("""{"text":"Report in Vietnamese"}""", bodies.last { it.first.startsWith("POST /api/assistant/notes") }.second)
    }

    @Test
    fun `a manager row shows its rules waiting, and the held change is approved by its hash (tablet)`() {
        start(mobile = false, tab = "managers")
        waitTag("asst-manager-row-web")
        node("QL web").assertExists()
        node("Manager tools arrive the next time this chat starts.").assertExists()
        composeRule.onNodeWithTag("asst-rules-pending-web", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("asst-manager-assign-api", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("asst-manager-rules-web", useUnmergedTree = true).performClick()
        waitTag("asst-rules-change")
        node("+ Push to main").assertExists()
        node("Use bun — under Allowed without asking").assertExists()
        composeRule.onNodeWithTag("asst-rules-approve", useUnmergedTree = true).performClick()
        waitAsked("POST /api/assistant/managers/web/rules/decision")
        assertEquals("""{"action":"approve","hash":"h9"}""", bodies.last { it.first.contains("/rules/decision") }.second)
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("asst-rules-change", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun `a manager's digest switch saves for that project (phone)`() {
        start(mobile = true, tab = "managers")
        waitTag("asst-manager-digest-web")
        composeRule.onNodeWithTag("asst-manager-digest-web").performScrollTo().performClick()
        waitAsked("PATCH /api/assistant/managers/web")
        assertEquals("""{"digest":false}""", bodies.last { it.first.startsWith("PATCH /api/assistant/managers/web") }.second)
    }

    @Test
    fun `sessions show the main one and what Telegram talks to, and the menu sets another as main (phone)`() {
        start(mobile = true, tab = "sessions")
        waitTag("assistant-session-a2")
        composeRule.onNodeWithTag("assistant-session-main", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("assistant-session-telegram", useUnmergedTree = true).assertExists()
        waitText("Talks to “Morning”")
        composeRule.onNodeWithTag("assistant-session-menu-a2").performScrollTo().performClick()
        composeRule.onNodeWithTag("asst-session-set-main", useUnmergedTree = true).performClick()
        waitAsked("PUT /api/assistant/main-session")
        assertEquals("""{"sessionId":"a2"}""", bodies.last { it.first.startsWith("PUT /api/assistant/main-session") }.second)
    }

    @Test
    fun `message grouping is checked before it is saved (tablet)`() {
        start(mobile = false, tab = "sessions")
        waitTag("assistant-telegram-debounce")
        composeRule.onNodeWithTag("assistant-telegram-debounce").performScrollTo().performTextReplacement("99000")
        node("A whole number from 0 to 30,000.").assertExists()
        composeRule.onNodeWithTag("assistant-telegram-debounce-save").assertIsNotEnabled()
        composeRule.onNodeWithTag("assistant-telegram-debounce").performTextReplacement("5000")
        composeRule.onNodeWithTag("assistant-telegram-debounce-save").performClick()
        waitAsked("PUT /api/settings/clawbot")
        assertEquals("""{"debounce_ms":5000}""", bodies.last { it.first.startsWith("PUT /api/settings/clawbot") }.second)
    }

    @Test
    fun `restart news saves without a zone, a time saves with this device's (phone)`() {
        start(mobile = true, tab = "digest")
        waitTag("asst-restart-news-switch")
        composeRule.onNodeWithTag("asst-restart-news-switch").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { bodies.any { it.first.startsWith("PUT /api/assistant/settings") } }
        val news = body("PUT /api/assistant/settings")
        assertEquals("false", news["restart_news"].toString())
        assertFalse("timezone" in news)
        assertFalse("digest" in news)
        // The General fields go back as stored, so this save cannot undo another pane's.
        assertEquals("\"Be brief.\"", news["instructions"].toString())
        bodies.clear()
        Thread.sleep(500)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("asst-digest-switch").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { bodies.any { it.first.startsWith("PUT /api/assistant/settings") } }
        val digest = body("PUT /api/assistant/settings")
        assertEquals("""{"enabled":false,"time":"08:00"}""", digest["digest"].toString())
        assertEquals("\"" + java.time.ZoneId.systemDefault().id + "\"", digest["timezone"].toString())
        assertFalse("restart_news" in digest)
    }
}
