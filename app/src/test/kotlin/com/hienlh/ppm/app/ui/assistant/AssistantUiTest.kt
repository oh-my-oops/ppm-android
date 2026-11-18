package com.hienlh.ppm.app.ui.assistant

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.assistant
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.AssistantCard
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.hienlh.ppm.state.assistant.AssistantStore
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

private const val PHONE = "w400dp-h860dp-xxhdpi"
private const val TABLET = "w1280dp-h900dp-xhdpi"

/** The PPM Assistant's screens on a phone and on a tablet, against a scripted server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TABLET)
class AssistantUiTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val server = MockWebServer()
    private val asked = CopyOnWriteArrayList<String>()
    private val bodies = CopyOnWriteArrayList<String>()
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun card(id: String, kind: String, extra: String = "", status: String = "open", payload: String = "{}") =
        """{"id":"$id","kind":"$kind","projectName":"web","sessionId":"m1","title":"Title $id","body":"Body of $id",
            |"payload":$payload,"status":"$status","okOffered":true,"createdAt":1000,"updatedAt":1000$extra}""".trimMargin()

    private val decision = card("d1", "needs_decision")
    private val decision2 = card("d2", "urgent")
    private val approval = card(
        "a1", "approval",
        payload = """{"deciding":{"kind":"bash","title":"Bash","facts":[{"label":"Folder","value":"/w"}],"text":"ls -la","complete":true}}""",
    )
    private val done = card("o1", "done")
    private val rulesCard = card("r1", "rules_change", payload = """{"rulesComplete":true,"rulesHash":"h1","changes":[{"op":"add","line":"Be brief"}]}""")
    private val digest = card("g1", "digest", payload = """{"projects":[{"name":"web","state":"answered","items":["one","two"]}]}""")
    private val answered = card("x1", "needs_decision", status = "answered", extra = ""","answer":{"by":"user","channel":"ppm","at":2000,"ok":true}""")

    private fun answers(open: List<String>, providers: String = "[]"): Map<String, String> = mapOf(
        "GET /api/assistant/cards/open" to """{"cards":[${open.joinToString(",")}]}""",
        "GET /api/assistant/cards" to """{"cards":[${open.joinToString(",")}]}""",
        "GET /api/assistant/waiting" to """{"total":${open.size},"byProject":{"web":${open.size}}}""",
        "GET /api/assistant/managers/web/check" to """{"permissionMode":"bypassPermissions","replaces":{"sessionId":"old","title":"Old chat"},"already":false}""",
        "GET /api/assistant/managers" to """{"managers":[{"projectName":"web","projectPath":"/w","sessionId":"m1","providerId":"claude","phase":"idle","waiting":${open.size}}]}""",
        "GET /api/assistant/main-session" to """{"main":null}""",
        "GET /api/assistant/telegram" to """{"enabled":true,"running":true,"quietWhileInPpm":true,"chats":[]}""",
        "GET /api/assistant/settings" to """{"settings":{"default_provider":"claude"}}""",
        "GET /api/project/__assistant__/chat/providers" to providers,
        "POST /api/assistant/cards/d1/ok" to """{"card":${decision.replace("\"open\"", "\"answered\"")},"to":"manager"}""",
        "POST /api/assistant/cards/d1/reply" to """{"card":${decision.replace("\"open\"", "\"answered\"")},"to":"manager"}""",
        "PUT /api/assistant/managers/web" to "{}",
    )

    private fun start(open: List<String>): Pair<AppModel, AssistantStore> {
        val routes = answers(open)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val line = request.method + " " + request.target
                asked += line
                request.body?.utf8()?.let(bodies::add)
                val key = routes.keys.filter { line.startsWith(it) }.maxByOrNull { it.length }
                // Nothing pooled: a later test's server can come back on this port.
                return key?.let { MockResponse.Builder().addHeader("Connection", "close").body("""{"ok":true,"data":${routes[it]}}""").build() } ?: MockResponse.Builder().code(404).addHeader("Connection", "close").build()
            }
        }
        server.start()
        val model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        // Its own token: GETs in flight are shared by token and address, and a port can come back in a later test.
        val api = ApiClient(server.url("/"), java.util.UUID.randomUUID().toString())
        model.api = api
        // Off the main looper: the store's reads must not wait on Robolectric's paused one.
        val store = AssistantStore(api, storeScope)
        store.start()
        val deadline = System.currentTimeMillis() + 10_000
        while (store.view.value.let { it.open == null || it.managers == null || it.telegram == null || it.cards == null } && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        model.assistant.use(store, emptyList())
        return model to store
    }

    private fun waitRead(store: AssistantStore) = composeRule.waitUntil(20_000) {
        store.view.value.let { it.open != null && it.managers != null && it.telegram != null && it.cards != null }
    }

    private fun waitAsked(line: String) = composeRule.waitUntil(5_000) { asked.any { it.startsWith(line) } }

    private fun node(text: String): SemanticsNodeInteraction = composeRule.onNode(hasText(text), useUnmergedTree = true)

    @After
    fun stop() {
        storeScope.cancel()
        server.close()
    }

    private val assistantTab = WorkspaceTab("assistant", "assistant", "PPM Assistant", null, buildJsonObject { put("projectName", "__assistant__") })

    private fun showTab(mobile: Boolean, open: List<String>): AssistantStore {
        val (model, store) = start(open)
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) { AssistantTabBody(model, assistantTab) }
        }
        waitRead(store)
        return store
    }

    private fun checkTab(mobile: Boolean) {
        val store = showTab(mobile, listOf(decision, done))
        composeRule.onNodeWithTag("assistant-head").assertIsDisplayed()
        node(NO_PROVIDER_TEXT).assertIsDisplayed()
        node("Waiting on you · 2").assertIsDisplayed()
        composeRule.onNodeWithTag("assistant-manager-chip-web").assertIsDisplayed()
        composeRule.onNode(hasContentDescription("Quiet while I'm at PPM", substring = true)).assertIsDisplayed()
        composeRule.onNodeWithTag("assistant-settings").assertIsDisplayed()
        node("Filter: All").performClick()
        node("Filter by project").assertIsDisplayed()
        node(FILTER_EXPLAINER).assertIsDisplayed()
        composeRule.onNodeWithTag("assistant-filter-web").performClick()
        composeRule.waitForIdle()
        assertEquals("web", store.view.value.filter)
        node("Filter: web").assertIsDisplayed()
    }

    @Test
    fun `the tab on a tablet shows its head, chips, filter and waiting cards`() = checkTab(mobile = false)

    @Config(qualifiers = PHONE)
    @Test
    fun `the tab on a phone shows its head, chips, filter and waiting cards`() = checkTab(mobile = true)

    private fun checkCards(mobile: Boolean) {
        val (model, store) = start(listOf(decision, approval, done, rulesCard, digest, answered))
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    for (json in listOf(decision, approval, done, rulesCard, digest, answered)) {
                        StreamCard(model, AssistantCard.from(WireJson.parse(json))!!)
                    }
                }
            }
        }
        waitRead(store)
        node("Needs your decision").assertIsDisplayed()
        node("Needs your approval").performScrollTo().assertIsDisplayed()
        node("Allow").performScrollTo().assertIsDisplayed()
        node("ls -la").performScrollTo().assertIsDisplayed()
        node("Done").performScrollTo().assertIsDisplayed()
        node("Rules change waiting").performScrollTo().assertIsDisplayed()
        node("Approve change").performScrollTo().assertIsDisplayed()
        node("+ Be brief").performScrollTo().assertIsDisplayed()
        node("Morning digest · 1 project, 2 items").performScrollTo().performClick()
        node("2. two").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("assistant-answered-x1").performScrollTo().assertIsDisplayed()
        node("OK").performScrollTo().performClick()
        waitAsked("POST /api/assistant/cards/d1/ok")
        assertTrue(bodies.any { WireJson.parse(it)["deviceId"].string != null })
    }

    @Test
    fun `cards on a tablet draw each kind and send an OK`() = checkCards(mobile = false)

    @Config(qualifiers = PHONE)
    @Test
    fun `cards on a phone draw each kind and send an OK`() = checkCards(mobile = true)

    private fun checkDecisionSet(mobile: Boolean) {
        val (model, store) = start(listOf(decision, decision2))
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                Column(Modifier.verticalScroll(rememberScrollState())) { DecisionSetView(model, store) }
            }
        }
        waitRead(store)
        node("2 decisions waiting").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("assistant-decision-tab-d1").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("assistant-decision-tile-ok").performScrollTo().performClick()
        composeRule.onNodeWithTag("assistant-decision-send-all").performScrollTo().assertIsEnabled()
        node("Send all answered (1/2)").assertIsDisplayed()
        composeRule.onNodeWithTag("assistant-decision-send-all").performClick()
        node("Send 1 answer?").assertIsDisplayed()
        node("No answer yet — keeps waiting").assertIsDisplayed()
        composeRule.onNodeWithTag("assistant-decision-review-send").performClick()
        waitAsked("POST /api/assistant/cards/d1/ok")
    }

    @Test
    fun `the decision set on a tablet answers by tile and sends after a review`() = checkDecisionSet(mobile = false)

    @Config(qualifiers = PHONE)
    @Test
    fun `the decision set on a phone answers by tile and sends after a review`() = checkDecisionSet(mobile = true)

    private fun checkManagerParts(mobile: Boolean) {
        val (model, store) = start(listOf(decision))
        var closed = false
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                Column {
                    ManagerChatBadge(model, "m1")
                    AssistantRailButton(model)
                    SetManagerChatDialog(model, "web", "s9", "My chat") { closed = true }
                }
            }
        }
        waitRead(store)
        composeRule.onNodeWithTag("manager-badge-m1", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNode(hasContentDescription("PPM Assistant · 1 waiting")).assertIsDisplayed()
        node("My chat becomes the web manager: the chat PPM Assistant talks to about this project.").assertIsDisplayed()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Replace manager"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        node("This replaces the current manager, “Old chat”. That chat keeps its history.").assertIsDisplayed()
        node("•  $MANAGER_ASSIGN_BYPASS").assertIsDisplayed()
        composeRule.onNodeWithTag("set-manager-confirm").performClick()
        waitAsked("PUT /api/assistant/managers/web")
        composeRule.waitUntil(5_000) { closed }
    }

    @Test
    fun `manager parts on a tablet, the badge, rail count and the set-as-manager dialog`() = checkManagerParts(mobile = false)

    @Config(qualifiers = PHONE)
    @Test
    fun `manager parts on a phone, the badge, rail count and the set-as-manager dialog`() = checkManagerParts(mobile = true)

    private fun checkReplyBar(mobile: Boolean) {
        val (model, store) = start(listOf(decision))
        var closed = false
        val target = AssistantCard.from(WireJson.parse(decision))!!
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) { CardReplyBar(model, target) { closed = true } }
        }
        waitRead(store)
        node("Replying to card").assertIsDisplayed()
        node("web · Title d1").assertIsDisplayed()
        composeRule.onNode(hasTestTag("assistant-reply-input")).performTextInput("ship it")
        composeRule.onNodeWithTag("assistant-reply-send").performClick()
        waitAsked("POST /api/assistant/cards/d1/reply")
        assertTrue(bodies.any { it.contains("\"text\":\"ship it\"") })
        composeRule.waitUntil(5_000) { closed }
    }

    @Test
    fun `the reply bar on a tablet sends the words to the manager chat`() = checkReplyBar(mobile = false)

    @Config(qualifiers = PHONE)
    @Test
    fun `the reply bar on a phone sends the words to the manager chat`() = checkReplyBar(mobile = true)
}
