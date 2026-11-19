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
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.Dp
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

    private fun start(open: List<String>, extra: Map<String, String> = emptyMap()): Pair<AppModel, AssistantStore> {
        val routes = answers(open) + extra
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

    private fun height(node: SemanticsNodeInteraction): Dp = node.getUnclippedBoundsInRoot().height

    /** A control's whole box: its press area sits inside a 1 dp CSS border, which takes layout space as in CSS. */
    private fun boxHeight(node: SemanticsNodeInteraction): Dp = height(node) + 2.dp

    /** The set's tabs are the waiting cards: one sent leaves at once, a line says so and the next opens (web 1fe1af822, e5ab663a1). */
    private fun checkSentCardLeaves(mobile: Boolean) {
        val (model, store) = start(listOf(decision, decision2))
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                Column(Modifier.verticalScroll(rememberScrollState())) { DecisionSetView(model, store) }
            }
        }
        waitRead(store)
        node("2 decisions waiting").performClick()
        composeRule.onNodeWithTag("assistant-decision-tab-d1").performClick()
        composeRule.onNodeWithTag("assistant-decision-tile-ok").performScrollTo().performClick()
        composeRule.onNodeWithTag("assistant-decision-send-one").performScrollTo().performClick()
        waitAsked("POST /api/assistant/cards/d1/ok")
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasTestTag("assistant-decision-tab-d1")).fetchSemanticsNodes().isEmpty() }
        node("1 decision waiting").assertIsDisplayed()
        composeRule.onNodeWithTag("assistant-decision-tab-d2").assertIsDisplayed()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasTestTag("assistant-decision-flash")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNode(hasText("Sent “Title d1”", substring = true), useUnmergedTree = true).assertIsDisplayed()
        assertEquals("Send all answered (0/1)", composeRule.onNodeWithTag("assistant-decision-send-all").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text })
        assertTrue(store.view.value.decisions.picks.isEmpty())
    }

    @Test
    fun `the decision set on a tablet drops a sent card at once and says it was sent`() = checkSentCardLeaves(mobile = false)

    @Config(qualifiers = PHONE)
    @Test
    fun `the decision set on a phone drops a sent card at once and says it was sent`() = checkSentCardLeaves(mobile = true)

    /** The manager chat's Waiting bar counts its cards, not its entries: two decisions are one entry and a 2 (web a6ab212c2). */
    private fun checkWaitingBar(mobile: Boolean): AppModel {
        val (model, store) = start(listOf(decision, decision2), extra = mapOf("GET /api/assistant/managers" to """{"managers":[{"projectName":"web","projectPath":"/w","sessionId":"m1","providerId":"claude","phase":"idle","waiting":5}]}"""))
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) { Column { ManagerChatWaitingCards(model, "m1") } }
        }
        waitRead(store)
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasTestTag("manager-waiting-toggle")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNode(hasText("2") and hasAnyAncestor(hasTestTag("manager-waiting-count")), useUnmergedTree = true).assertExists()
        return model
    }

    @Test
    fun `the waiting bar on a tablet counts each decision and keeps its 44 dp row`() {
        checkWaitingBar(mobile = false)
        assertEquals(44.dp, height(composeRule.onNodeWithTag("manager-waiting-toggle")))
    }

    @Config(qualifiers = PHONE)
    @Test
    fun `the waiting bar on a phone counts each decision on a 26 dp row and opens a sheet with the count`() {
        checkWaitingBar(mobile = true)
        composeRule.onNodeWithTag("manager-waiting-toggle").assertHeightIsEqualTo(26.dp)
        composeRule.onNodeWithTag("manager-waiting-toggle").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasTestTag("assistant-decision-set"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        // The row's badge and the sheet's head both say 2.
        assertEquals(2, composeRule.onAllNodes(hasText("2") and hasAnyAncestor(hasTestTag("assistant-waiting-badge")), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    /** The filter pill and the manager chips: 26 dp on a phone, 28 on a tablet (web 6fe4d8a15). */
    private fun checkChipRow(mobile: Boolean, expected: Dp) {
        showTab(mobile, listOf(decision, done))
        composeRule.onNodeWithTag("assistant-manager-chip-web").assertHeightIsEqualTo(expected)
        assertEquals(expected, boxHeight(composeRule.onNode(hasText("Filter: All") and hasClickAction())))
    }

    @Test
    fun `the chip row on a tablet is 28 dp`() = checkChipRow(mobile = false, expected = 28.dp)

    @Config(qualifiers = PHONE)
    @Test
    fun `the chip row on a phone is 26 dp`() = checkChipRow(mobile = true, expected = 26.dp)

    private val chose = card("c1", "needs_decision", payload = """{"options":[{"id":"a","label":"Acme"},{"id":"b","label":"Globex"}]}""")

    /** A card's buttons are the chip row's height on a phone; a card's choices at least 28 dp, 26 on a phone. */
    private fun checkCardSizes(mobile: Boolean) {
        val (model, store) = start(listOf(decision))
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    StreamCard(model, AssistantCard.from(WireJson.parse(decision))!!)
                    StreamCard(model, AssistantCard.from(WireJson.parse(chose))!!)
                }
            }
        }
        waitRead(store)
        // OK is filled, with no border: its press area is its whole box.
        assertEquals(if (mobile) 26.dp else 32.dp, height(composeRule.onNode(hasText("OK") and hasClickAction())))
        assertEquals(if (mobile) 26.dp else 32.dp, boxHeight(composeRule.onAllNodes(hasText("Reply") and hasClickAction())[0]))
        for (id in listOf("a", "b")) {
            val h = boxHeight(composeRule.onNodeWithTag("assistant-card-choice-$id"))
            assertTrue("choice $id $h", h >= (if (mobile) 26.dp else 28.dp) && h < 44.dp)
        }
    }

    @Test
    fun `card buttons on a tablet keep 32 dp and choices are at least 28`() = checkCardSizes(mobile = false)

    @Config(qualifiers = PHONE)
    @Test
    fun `card buttons on a phone are 26 dp and choices at least 26`() = checkCardSizes(mobile = true)
}
