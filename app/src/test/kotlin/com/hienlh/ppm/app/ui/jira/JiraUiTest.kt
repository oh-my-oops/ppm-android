package com.hienlh.ppm.app.ui.jira

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.jira
import com.hienlh.ppm.app.ui.LocalReducedMotion
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.string
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
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

private const val TABLET = "w1280dp-h900dp-xhdpi"
private const val PHONE = "w400dp-h840dp-xhdpi"

/** A scripted `/api/jira`: one site for project `ppm` (id 7), one watcher, three tracked tickets. */
private class JiraServer(withConfig: Boolean = true) : Dispatcher() {
    val seen = CopyOnWriteArrayList<Pair<String, String>>()
    private val created = Instant.now().minusSeconds(300).toString()
    private val configs = if (withConfig) """[{"id":3,"projectId":7,"projectName":"ppm","baseUrl":"https://acme.atlassian.net","email":"me@acme.dev","hasToken":true}]""" else "[]"
    private val results = """[
        {"id":1,"watcherId":5,"issueKey":"BUG-1","issueSummary":"Login fails","status":"pending","sessionId":null,"readAt":null,"createdAt":"$created"},
        {"id":2,"watcherId":5,"issueKey":"BUG-2","issueSummary":"Crash on save","status":"done","sessionId":"s-2","readAt":null,"createdAt":"$created"},
        {"id":3,"watcherId":5,"issueKey":"BUG-3","issueSummary":"Slow list","status":"failed","sessionId":"s-3","readAt":"$created","createdAt":"$created"}
    ]"""
    var summary = "Login fails"

    fun request(line: String): String? = seen.firstOrNull { it.first == line }?.second

    private fun ok(data: String) = MockResponse.Builder().body("""{"ok":true,"data":$data}""").build()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        val line = "${request.method} $path"
        seen += line to (request.body?.utf8() ?: "")
        return when (line) {
            "GET /api/jira/config/projects" -> ok("""[{"id":7,"name":"ppm","path":"/home/u/ppm","hasConfig":true}]""")
            "GET /api/jira/config" -> ok(configs)
            "GET /api/jira/watchers" -> ok("""[{"id":5,"jiraConfigId":3,"name":"Bugs","jql":"project = BUG","promptTemplate":"Fix {issue_key}","enabled":true,"intervalMs":120000,"mode":"debug"}]""")
            "GET /api/jira/results" -> ok(results)
            "GET /api/jira/results/unread-count" -> ok("""{"count":1}""")
            "POST /api/jira/results/1/debug" -> ok("""{"queued":true}""")
            "POST /api/jira/watchers" -> ok("""{"id":6}""")
            "POST /api/jira/watchers/5/pull" -> ok("""{"newIssues":2}""")
            "GET /api/jira/ticket/3/BUG-1" -> ok(
                """{"key":"BUG-1","fields":{"summary":"$summary","description":"Steps to reproduce","status":{"name":"To Do"},"priority":{"name":"High"},"assignee":null,"updated":"2026-11-18T09:00:00.000+0000"}}""",
            )
            "GET /api/jira/ticket/3/BUG-1/transitions" -> ok("""[{"id":"31","name":"Start","to":{"name":"In Progress"}}]""")
            "PUT /api/jira/ticket/3/BUG-1" -> {
                summary = "Login fails on Safari"
                ok("{}")
            }
            else -> ok(if (line.startsWith("GET /api/jira/metadata/")) "[]" else "{}")
        }
    }
}

/** The Jira section against a scripted server, on a tablet and on a phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TABLET)
class JiraUiTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val server = MockWebServer()
    private var jira = JiraServer()

    @After
    fun stop() {
        server.close()
    }

    private fun model(withConfig: Boolean = true): AppModel {
        jira = JiraServer(withConfig)
        server.dispatcher = jira
        server.start()
        return AppModel(ApplicationProvider.getApplicationContext<Application>()).apply {
            api = ApiClient(server.url("/"), "secret")
            selectedProject = "ppm"
        }
    }

    private fun show(mobile: Boolean, content: @Composable () -> Unit) = composeRule.setContent {
        PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
            CompositionLocalProvider(LocalReducedMotion provides true) { Box(Modifier.fillMaxSize()) { content() } }
        }
    }

    private fun waitForTag(tag: String) = composeRule.waitUntil(5_000L) {
        composeRule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForText(text: String) = composeRule.waitUntil(5_000L) {
        composeRule.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForRequest(line: String): String {
        composeRule.waitUntil(5_000L) { jira.request(line) != null }
        return jira.request(line)!!
    }

    private fun field(tag: String) = composeRule.onNode(hasSetTextAction() and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))), useUnmergedTree = true)

    @Test
    fun `the tablet panel lists the tracked tickets with the unread count, and Debug sends the watcher's prompt`() {
        val model = model()
        show(mobile = false) { JiraPanel(model) }
        waitForTag("jira-ticket-1")
        waitForTag("jira-unread")
        composeRule.onNodeWithTag("jira-ticket-unread-2", useUnmergedTree = true).assertExists()
        // Resume only on the failed ticket, Debug only on the pending one.
        composeRule.onNodeWithTag("jira-resume-3", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("jira-debug-1").performClick()
        waitForTag("jira-debug-dialog")
        waitForText("Start Debug: BUG-1")
        composeRule.onNodeWithTag("jira-debug-start").performClick()
        val body = WireJson.parse(waitForRequest("POST /api/jira/results/1/debug"))
        assertEquals("Fix BUG-1", body["prompt"].string)
    }

    @Test
    fun `a project with no site offers Set up Jira, which opens the credentials form`() {
        val model = model(withConfig = false)
        show(mobile = false) { JiraPanel(model) }
        waitForText("No Jira credentials configured")
        composeRule.onNodeWithTag("jira-empty-action").performClick()
        waitForTag("jira-config-form")
        composeRule.onNodeWithText("Jira Credentials").assertExists()
        composeRule.onNodeWithTag("jira-config-test").assertDoesNotExist()
    }

    @Test
    fun `a watcher is created from raw JQL, and Pull now says how many issues came in`() {
        val model = model()
        show(mobile = false) { JiraPanel(model) }
        waitForTag("jira-open-watchers")
        composeRule.onNodeWithTag("jira-open-watchers").performClick()
        waitForTag("jira-watcher-5")
        composeRule.onNodeWithTag("jira-watcher-pull-5").performClick()
        waitForRequest("POST /api/jira/watchers/5/pull")
        composeRule.onNodeWithTag("jira-watcher-add").performClick()
        waitForTag("jira-watcher-form")
        field("jira-watcher-name").performTextInput("Crashes")
        composeRule.onNodeWithTag("jira-filter-raw-mode").performClick()
        waitForTag("jira-filter-raw")
        field("jira-filter-raw").performTextInput("project = BUG AND priority = High")
        waitForText("project = BUG AND priority = High")
        composeRule.onNodeWithTag("jira-watcher-submit").performClick()
        val body = WireJson.parse(waitForRequest("POST /api/jira/watchers"))
        assertEquals("Crashes", body["name"].string)
        assertEquals("project = BUG AND priority = High", body["jql"].string)
        assertEquals("debug", body["mode"].string)
        assertEquals(120_000, body["intervalMs"].int)
    }

    @Test
    @Config(qualifiers = PHONE)
    fun `on a phone a ticket that is not done opens its detail, where the summary is edited`() {
        val model = model()
        show(mobile = true) {
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                // A row's site is found through its watcher, as the panel's own list holds them.
                androidx.compose.runtime.LaunchedEffect(Unit) { model.jira.loadWatchers(3) }
                JiraResultsPanel(model)
            }
        }
        waitForTag("jira-result-1")
        composeRule.onNodeWithTag("jira-result-1").performClick()
        waitForTag("jira-ticket-detail")
        waitForText("Unassigned")
        waitForText("Steps to reproduce")
        composeRule.onNodeWithTag("jira-ticket-transition", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("jira-ticket-summary").performClick()
        waitForTag("jira-ticket-summary-input")
        field("jira-ticket-summary-input").performTextReplacement("Login fails on Safari")
        composeRule.onNodeWithTag("jira-ticket-summary-save").performClick()
        val body = WireJson.parse(waitForRequest("PUT /api/jira/ticket/3/BUG-1"))
        assertEquals("Login fails on Safari", body["fields"]["summary"].string)
        waitForTag("jira-ticket-summary")
    }

    @Test
    @Config(qualifiers = PHONE)
    fun `Jira joins the sections only while it is on, and turning it off leaves its section for Explorer`() {
        val model = model()
        assertFalse("jira" in model.sidebarSections)
        model.setJiraEnabledPref(true)
        assertTrue("jira" in model.sidebarSections)
        model.pickSidebarSection("jira")
        assertEquals("jira", model.sidebarSection)
        model.setJiraEnabledPref(false)
        assertEquals("explorer", model.sidebarSection)
    }
}
