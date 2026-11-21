package com.hienlh.ppm.app.ui.specs

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.tablet.TABLET_SPEC_TAG
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * A task's run in flight on the task list (`TestingPill` in spec-tasks.tsx): its red or green tests,
 * or the whole suite a green task's `task_green` call still waits on, with the time it has taken;
 * and a green task with no pass count says "Green".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h900dp")
class SpecTaskTestingTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val server = MockWebServer()

    @After
    fun stop() {
        server.close()
    }

    private fun task(id: String, status: String, done: Boolean, run: String, testing: String) =
        """{"id":"$id","title":"Task $id","optional":false,"status":"$status","done":$done,"next":"finished","wave":0,
            "deps":[],"files":[],"requirements":[],"tests":[],"run":$run,"testing":$testing}"""

    private fun view(): String {
        val started = Instant.now().minusSeconds(65).toString()
        val green = """{"status":"done","sessionId":null,"green":{"outcome":"pass","failing":[],"passed":null,"failed":0}}"""
        val tasks = listOf(
            task("1", "done", true, green, """{"kind":"suite","startedAt":"$started"}"""),
            task("2", "red", false, "null", """{"kind":"green","startedAt":"$started"}"""),
            task("3", "done", true, green, "null"),
        ).joinToString(",")
        val approved = """{"status":"approved","file":"specs/login/x.md","issues":[],"text":"# x","submitted":false}"""
        return """{"slug":"login","title":"Login flow","kind":"feature","workflow":"requirements-first","checks":"thorough",
            "current":"execution","order":["requirements","design","tasks"],
            "phases":{"requirements":$approved,"design":$approved,"tasks":$approved},
            "waves":{"waves":[["1","2","3"]],"moved":[],"optional":[],"issues":[]},"tasks":[$tasks],
            "suite":null,"run":null,"runLive":false,"sessionId":"chat-1","updatedAt":"2026-11-21T08:00:00.000Z"}"""
    }

    private fun model(): AppModel {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val data = if (request.url.encodedPath == "/api/project/ppm/specs/login") view() else "null"
                return MockResponse.Builder().addHeader("Content-Type", "application/json").body("""{"ok":true,"data":$data}""").build()
            }
        }
        server.start()
        return AppModel(ApplicationProvider.getApplicationContext<Application>()).apply {
            api = ApiClient(server.url("/"), "secret")
            selectedProject = "ppm"
        }
    }

    private fun waitForText(text: String) = composeRule.waitUntil(5_000L) {
        composeRule.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForTag(tag: String) = composeRule.waitUntil(5_000L) {
        composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun assertPills(tag: String) {
        waitForTag("$tag-task-testing:1")
        // About 65 s in: the minutes and the tens of seconds are certain, the last digit is not.
        waitForText("Green · checking whole suite · 1m 0")
        waitForText("Testing green · 1m 0")
        composeRule.onNodeWithTag("$tag-task-testing:2").assertExists()
        // A runner that reported no pass count: "Green", never "Green · 0 pass".
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodes(hasText("Green", substring = false), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    @Config(qualifiers = "w400dp-h840dp-xhdpi")
    fun `on a phone a task shows the run PPM has in flight for it`() {
        val model = model()
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                SpecDetail(model, "ppm", "login", onOpenChat = { _, _ -> })
            }
        }
        assertPills("phone-spec")
    }

    @Test
    fun `on a tablet a task shows the run PPM has in flight for it`() {
        val model = model()
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                CompositionLocalProvider(LocalSpecTag provides TABLET_SPEC_TAG) {
                    Box(Modifier.size(width = 700.dp, height = 800.dp)) {
                        SpecDetail(model, "ppm", "login", onOpenChat = { _, _ -> }, inOwnChat = true)
                    }
                }
            }
        }
        assertPills(TABLET_SPEC_TAG)
    }
}
