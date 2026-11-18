package com.hienlh.ppm.app.ui.schedules

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.LocalReducedMotion
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

private const val TABLET = "w1280dp-h900dp-xhdpi"
private const val PHONE = "w400dp-h840dp-xhdpi"

private val SQLITE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

private fun iso(offsetMs: Long) = Instant.ofEpochMilli(System.currentTimeMillis() + offsetMs).toString()

private fun stamp(offsetMs: Long) = SQLITE.format(Instant.ofEpochMilli(System.currentTimeMillis() + offsetMs))

/** One overview on the wire (`ScheduleOverview`), plain defaults. */
private fun overview(
    id: Int,
    name: String,
    enabled: Boolean = true,
    next: String? = null,
    lastRun: String = "null",
    dismissed: String? = null,
    mode: String = "default",
    task: String = "null",
) = """{"id":$id,"name":"$name","kind":"prompt","mode":"tick","cron_expr":"0 9 * * *","provider_id":"claude",
    "project_path":"/home/u/ppm","prompt":"Write the report for $name","script":null,"permission_mode":"$mode","timeout_ms":600000,
    "enabled":${if (enabled) 1 else 0},"session_id":null,"next_fire_at":${next?.let { "\"$it\"" } ?: "null"},"updated_at":"${stamp(-3_600_000)}",
    "target_session_id":null,"target_title":null,"timezone":null,"recurring":1,"pending_since":null,"paused_reason":null,
    "attention_dismissed":${dismissed?.let { "\"$it\"" } ?: "null"},"status":{"state":"idle"},"last_run":$lastRun,
    "project_name":"ppm","effective_permission_mode":"$mode","next_fires":[],"task":$task}"""

/** A scripted `/api/schedules`: three schedules, recording every request with its body. */
private class SchedulesServer : Dispatcher() {
    val seen = CopyOnWriteArrayList<Pair<String, String>>()
    private val failedRun = """{"id":9,"status":"error","started_at":"${stamp(-7_200_000)}","ended_at":"${stamp(-7_100_000)}","error":"Token expired","session_id":"run-chat"}"""
    var rows = linkedMapOf(
        1 to overview(1, "Nightly report", next = iso(2 * 3_600_000), mode = "bypassPermissions",
            task = """{"id":"t1","ref":"ppm/12","title":"Report","project":"ppm","closed":false}"""),
        2 to overview(2, "Broken sync", next = iso(48 * 3_600_000), lastRun = failedRun),
        3 to overview(3, "Old job", enabled = false),
    )

    fun request(line: String): String? = seen.firstOrNull { it.first == line }?.second

    private fun ok(data: String) = MockResponse.Builder().body("""{"ok":true,"data":$data}""").build()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        val line = "${request.method} $path"
        seen += line to (request.body?.utf8() ?: "")
        return when {
            line == "GET /api/schedules" -> ok(rows.values.joinToString(",", "[", "]"))
            line == "POST /api/schedules/2/dismiss" -> {
                rows[2] = overview(2, "Broken sync", next = iso(48 * 3_600_000), lastRun = failedRun, dismissed = "failed:9")
                ok(rows.getValue(2))
            }
            line.endsWith("/run-now") -> ok("""{"runId":31,"wasRunning":false}""")
            line.matches(Regex("GET /api/schedules/\\d+/runs")) -> ok(if (path.contains("/2/")) "[$failedRun]".replace("\"id\":9", "\"id\":9,\"schedule_id\":2") else "[]")
            line == "DELETE /api/schedules/3" -> {
                rows.remove(3)
                ok("""{"deleted":true}""")
            }
            line.startsWith("PATCH /api/schedules/") -> ok(rows.getValue(path.substringAfterLast('/').toInt()).replace("\"enabled\":1", "\"enabled\":0"))
            else -> ok("[]")
        }
    }
}

/** The Schedules window against a scripted server, on a tablet and on a phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TABLET)
class SchedulesUiTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val server = MockWebServer()
    private val schedules = SchedulesServer()

    @After
    fun stop() {
        server.close()
    }

    private fun model(): AppModel {
        server.dispatcher = schedules
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

    private fun waitForNoTag(tag: String) = composeRule.waitUntil(5_000L) {
        composeRule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
    }

    private fun waitForText(text: String) = composeRule.waitUntil(5_000L) {
        composeRule.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForRequest(line: String): String {
        composeRule.waitUntil(5_000L) { schedules.request(line) != null }
        return schedules.request(line)!!
    }

    @Test
    fun `the tablet window groups every schedule, and a problem is dismissed from the detail beside the list`() {
        val model = model()
        show(mobile = false) { SchedulesApp(model, phone = false) }
        waitForTag("schedule-row-2")
        composeRule.onNodeWithTag("schedules-group-attention").assertExists()
        composeRule.onNodeWithTag("schedules-group-upcoming").assertExists()
        composeRule.onNodeWithTag("schedules-group-off").assertExists()
        waitForText("need attention")
        composeRule.onNodeWithTag("schedule-permission-bypassPermissions", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("schedule-row-2").performClick()
        waitForTag("schedules-detail-pane")
        waitForText("Token expired")
        composeRule.onNodeWithTag("schedule-dismiss").performClick()
        assertEquals("""{"key":"failed:9"}""", waitForRequest("POST /api/schedules/2/dismiss"))
        // The server's answer goes into the list at once: no red dot, and the row leaves Needs attention.
        waitForNoTag("schedule-problem")
        waitForNoTag("schedules-group-attention")
    }

    @Test
    fun `a chip narrows the list, and Run now on a row starts a run`() {
        val model = model()
        show(mobile = false) { SchedulesApp(model, phone = false) }
        waitForTag("schedule-row-1")
        composeRule.onNodeWithTag("schedules-chip-off").performClick()
        waitForNoTag("schedule-row-1")
        composeRule.onNodeWithTag("schedule-row-3").assertExists()
        composeRule.onNodeWithTag("schedules-chip-all").performClick()
        waitForTag("schedule-row-1")
        composeRule.onNodeWithTag("schedule-row-1").performClick()
        waitForTag("schedule-run-now")
        composeRule.onNodeWithTag("schedule-run-now").performClick()
        waitForRequest("POST /api/schedules/1/run-now")
    }

    @Test
    fun `the rail button counts runs in the next 24 hours and shows a dot for a problem`() {
        val model = model()
        show(mobile = false) { SchedulesRailButton(model) }
        waitForTag("rail-schedules-count")
        composeRule.onNodeWithTag("rail-schedules-dot", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription("Schedules · 1 in the next 24 h · 1 needs attention").assertExists()
    }

    @Test
    @Config(qualifiers = PHONE)
    fun `on a phone a card opens its details in a sheet, with the Bypass warning and its task`() {
        val model = model()
        show(mobile = true) { SchedulesApp(model, phone = true) }
        waitForTag("schedule-card-1")
        composeRule.onNodeWithTag("schedule-card-1").performClick()
        waitForTag("schedule-detail-1")
        composeRule.onNodeWithTag("schedule-bypass-warning").assertExists()
        composeRule.onNodeWithTag("schedule-open-task").assertExists()
        waitForText("Write the report for Nightly report")
    }

    @Test
    @Config(qualifiers = PHONE)
    fun `on a phone a schedule is deleted from its details after the confirmation`() {
        val model = model()
        show(mobile = true) { SchedulesApp(model, phone = true) }
        waitForTag("schedule-card-3")
        composeRule.onNodeWithTag("schedule-card-3").performClick()
        waitForTag("schedule-detail-3")
        composeRule.onNodeWithContentDescription("Delete").performClick()
        waitForTag("schedule-delete-confirm")
        composeRule.onNodeWithTag("schedule-delete-confirm").performClick()
        waitForRequest("DELETE /api/schedules/3")
        waitForNoTag("schedule-card-3")
    }
}
