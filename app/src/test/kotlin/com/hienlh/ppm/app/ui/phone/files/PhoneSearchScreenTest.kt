package com.hienlh.ppm.app.ui.phone.files

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.hienlh.ppm.app.ui.files.installBlankFileIcons
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.WireJson
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The phone Search screen against a server answering as the search host does (bare JSON). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h840dp-xhdpi")
class PhoneSearchScreenTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val server = MockWebServer()
    private val requests = mutableListOf<RecordedRequest>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: PhoneSearchController
    private val opened = mutableListOf<Pair<String, Int>>()

    private val searchReply = """{"results":[{"file":"src/a.ts","matches":[
        {"line":3,"column":6,"length":3,"before":"const ","cutBefore":false,"text":"foo","after":" = 1;"},
        {"line":8,"column":0,"length":3,"before":"","cutBefore":false,"text":"foo","after":"();"}]}],
        "total":2,"files":1,"limitHit":true,"engine":"rg"}"""

    private fun replaceReply(query: String?): String =
        if (query == null) searchReply
        else searchReply.replace("\"after\":\" = 1;\"}", "\"after\":\" = 1;\",\"replacement\":\"bar\"}")
            .replace("\"after\":\"();\"}", "\"after\":\"();\",\"replacement\":\"bar\"}")

    @Before
    fun start() {
        installBlankFileIcons()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += request }
                val path = request.url.encodedPath
                val body = when {
                    path.endsWith("/files/search") -> replaceReply(request.url.queryParameter("replace"))
                    path.endsWith("/files/replace") -> """{"replaced":2,"files":1,"skipped":[],"failed":[]}"""
                    else -> """{"ok":true,"data":{}}"""
                }
                return MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()
            }
        }
        server.start()
        controller = PhoneSearchController(PhoneSearchBackend.api(ApiClient(server.url("/"), "t")), scope, debounceMs = 0)
    }

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private fun recorded(suffix: String) = synchronized(requests) { requests.filter { it.url.encodedPath.endsWith(suffix) } }

    private fun shown(tag: String) = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun fieldText(): String =
        composeRule.onNodeWithTag("phone-search-query").fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun setContent() = composeRule.setContent {
        PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
            PhoneSearchScreen(controller, "app", onBack = {}, onOpenMatch = { f, l -> opened += f to l }, onCopyPath = {})
        }
    }

    @Test
    fun `a query lists its matches under their file and a tap opens the file at the line`() {
        setContent()
        composeRule.onNodeWithTag("phone-search-query").performTextInput("foo")
        composeRule.waitUntil(5_000) { shown("phone-search-file-src/a.ts") }
        val request = recorded("/files/search").last()
        assertEquals("foo", request.url.queryParameter("q"))
        assertEquals("true", request.url.queryParameter("useIgnore"))
        composeRule.onNodeWithText("2 results in 1 file").assertExists()
        assertTrue(shown("phone-search-banner-limit"))
        composeRule.onNodeWithTag("phone-search-match-src/a.ts:8:0").performClick()
        assertEquals(listOf("src/a.ts" to 8), opened)
        // Collapsing the file hides its matches.
        composeRule.onNodeWithTag("phone-search-file-src/a.ts").performClick()
        composeRule.waitUntil(2_000) { !shown("phone-search-match-src/a.ts:8:0") }
    }

    @Test
    fun `Replace All asks with the web's wording, posts exactly the listed matches and reports`() {
        setContent()
        composeRule.onNodeWithTag("phone-search-query").performTextInput("foo")
        composeRule.onNodeWithTag("phone-search-replace-toggle").performClick()
        composeRule.onNodeWithTag("phone-search-replace").performTextInput("bar")
        composeRule.waitUntil(5_000) { recorded("/files/search").any { it.url.queryParameter("replace") == "bar" } && shown("phone-search-replace-all") }
        composeRule.waitUntil(5_000) { shown("phone-search-replace-src/a.ts:3:6") }
        composeRule.onNodeWithTag("phone-search-replace-all").performClick()
        composeRule.onNodeWithText("Replace 2 occurrences across 1 file with bar?").assertExists()
        composeRule.onNodeWithTag("phone-replace-all-cancel").performClick()
        composeRule.waitUntil(2_000) { !shown("phone-replace-all-sheet") }
        assertTrue(recorded("/files/replace").isEmpty())

        composeRule.onNodeWithTag("phone-search-replace-all").performClick()
        composeRule.onNodeWithTag("phone-replace-all-confirm").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Replaced 2 occurrences in 1 file").fetchSemanticsNodes().isNotEmpty()
        }
        val body = WireJson.parse(recorded("/files/replace").single().body!!.utf8())
        assertEquals(
            WireJson.parse("""{"files":[{"file":"src/a.ts","matches":[{"line":3,"column":6,"text":"foo","replacement":"bar"},{"line":8,"column":0,"text":"foo","replacement":"bar"}]}]}"""),
            body,
        )
        val searchesBefore = recorded("/files/search").size
        composeRule.onNodeWithTag("phone-replace-all-done").performClick()
        // Closing after a replace reads the project again.
        composeRule.waitUntil(5_000) { recorded("/files/search").size > searchesBefore }
    }

    @Test
    fun `keys typed faster than the screen redraws all stay in the field, and a Clear still empties it`() {
        setContent()
        val field = composeRule.onNodeWithTag("phone-search-query")
        // A keyboard commits a key per frame or faster; the query the field reported comes back a frame later.
        composeRule.mainClock.autoAdvance = false
        for (key in "abcdefgh") {
            field.performTextInput(key.toString())
            composeRule.mainClock.advanceTimeByFrame()
        }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals("abcdefgh", fieldText())
        assertEquals("abcdefgh", controller.state.value.fields.query)

        composeRule.runOnIdle { controller.clear() }
        composeRule.waitForIdle()
        assertEquals("", fieldText())
        // A second Clear after more typing empties it too.
        field.performTextInput("xy")
        composeRule.waitForIdle()
        composeRule.runOnIdle { controller.clear() }
        composeRule.waitForIdle()
        assertEquals("", fieldText())
    }
}
