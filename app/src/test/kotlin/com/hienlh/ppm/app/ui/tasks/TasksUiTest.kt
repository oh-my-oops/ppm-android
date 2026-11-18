package com.hienlh.ppm.app.ui.tasks

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.TaskTarget
import com.hienlh.ppm.app.tasks
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.app.ui.LocalReducedMotion
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val TABLET = "w900dp-h1200dp-xxhdpi"
private const val PHONE = "w400dp-h840dp-xhdpi"

/**
 * PPM Tasks against a scripted server, on a tablet and on a phone: the sidebar list, a task's
 * page, its quick view, the task strip of a chat that works on tasks and the manager chat's chip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TABLET)
class TasksUiTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val server = MockWebServer()
    private val tasks = TaskServer()

    @After
    fun stop() {
        server.close()
    }

    private fun model(): AppModel {
        server.dispatcher = tasks
        server.start()
        return AppModel(ApplicationProvider.getApplicationContext<Application>()).apply {
            api = ApiClient(server.url("/"), "secret")
            selectedProject = "ppm"
        }
    }

    private fun show(mobile: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) = composeRule.setContent {
        PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
            CompositionLocalProvider(LocalReducedMotion provides true) { Column { content() } }
        }
    }

    private fun waitForTag(tag: String) = composeRule.waitUntil(5_000L) {
        composeRule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForText(text: String) = composeRule.waitUntil(5_000L) {
        composeRule.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForRequest(line: String): String {
        composeRule.waitUntil(5_000L) { tasks.request(line) != null }
        return tasks.request(line)!!
    }

    /** Types into the text field inside the box tagged [tag]. */
    private fun typeInto(tag: String, text: String) =
        composeRule.onNode(hasSetTextAction() and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))), useUnmergedTree = true).performTextInput(text)

    private fun taskTab(id: String) = WorkspaceTab(
        id = "task:$id",
        type = "task",
        title = "Task",
        metadata = buildJsonObject {
            put("taskId", id)
            put("projectName", "ppm")
        },
    )

    @Test
    fun `the tablet list groups the project's tasks by urgency, each row its code and title`() {
        val model = model()
        show(mobile = false) { TasksPanel(model) }
        waitForTag("task-row-$TASK_A")
        composeRule.onNodeWithTag("task-group-now").assertExists()
        composeRule.onNodeWithTag("task-group-today").assertExists()
        composeRule.onNodeWithText("Fix login", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Write docs", useUnmergedTree = true).assertExists()
    }

    @Test
    @Config(qualifiers = PHONE)
    fun `on a phone a task picked from the list opens its page in a sheet, with Done in the thumb zone`() {
        val model = model()
        show(mobile = true) { TasksPanel(model) }
        waitForTag("task-row-$TASK_B")
        composeRule.onNodeWithTag("task-row-$TASK_B").performClick()
        waitForTag("task-detail-phone")
        waitForText("Write docs")
        composeRule.onNodeWithTag("task-tab-details").assertExists()
        composeRule.onNodeWithTag("task-mark-done").performClick()
        val body = WireJson.parse(waitForRequest("POST /api/tasks/$TASK_B/close"))
        assertEquals("done", body["closedAs"].string)
        assertEquals(1, body["revision"].int)
    }

    @Test
    fun `the tablet task tab shows the page, Mark done closes it and an AI change is undone from history`() {
        val model = model()
        show(mobile = false) { TaskTabBody(model, taskTab(TASK_A)) }
        waitForTag("task-detail")
        waitForText("Fix login")
        composeRule.onNodeWithText("Ship it", substring = true, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("task-working").assertExists()
        composeRule.onNodeWithText("Login chat", substring = true, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("task-mark-done").performClick()
        waitForRequest("POST /api/tasks/$TASK_A/close")

        waitForTag("task-undo-5")
        composeRule.onNodeWithTag("task-undo-5").performScrollTo().performClick()
        waitForRequest("POST /api/tasks/history/5/undo")
    }

    @Test
    fun `a decision is chosen from its card and sent with the task's revision`() {
        val model = model()
        show(mobile = false) { TaskTabBody(model, taskTab(TASK_C)) }
        waitForTag("task-card-decision")
        composeRule.onNodeWithText("Which vendor?", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Your choice is sent to the working chat (start one first) word for word and recorded in history.", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Choose B", useUnmergedTree = true).performClick()
        val body = WireJson.parse(waitForRequest("POST /api/tasks/$TASK_C/decide"))
        assertEquals("b", body["optionId"].string)
    }

    @Test
    @Config(qualifiers = PHONE)
    fun `the phone page splits Details and Activity, and the status opens the project's statuses`() {
        val model = model()
        show(mobile = true) { TaskTabBody(model, taskTab(TASK_A)) }
        waitForTag("task-detail-phone")
        composeRule.onNodeWithTag("task-tab-activity").performClick()
        waitForTag("task-history-5")
        composeRule.onNodeWithText("Release is today", substring = true, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("task-status-button").performClick()
        waitForTag("task-status-grid")
        waitForTag("status-done")
        composeRule.onNodeWithTag("status-done").performClick()
        val body = WireJson.parse(waitForRequest("POST /api/tasks/$TASK_A/close"))
        assertEquals("done", body["statusId"].string)
    }

    @Test
    fun `a chat working on two tasks shows the first and +1, and the strip opens the quick view`() {
        val model = model()
        show(mobile = false) { ChatTaskStrip(model, "chat-1", "ppm") }
        waitForTag("chat-task-strip")
        composeRule.onNodeWithText("Fix login", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("+1", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("chat-task-strip-open").performClick()
        assertEquals(TASK_A, model.tasks.peek?.id)
    }

    @Test
    @Config(qualifiers = PHONE)
    fun `on a phone +N lists every task the chat works on, and a row opens its quick view`() {
        val model = model()
        show(mobile = true) { ChatTaskStrip(model, "chat-1", "ppm") }
        waitForTag("chat-task-strip-more")
        composeRule.onNodeWithTag("chat-task-strip-more").performClick()
        waitForTag("chat-task-strip-row-$TASK_B")
        composeRule.onNodeWithText("Tasks this chat works on", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("chat-task-strip-row-$TASK_B").performClick()
        assertEquals(TASK_B, model.tasks.peek?.id)
    }

    @Test
    fun `a chat working on no task shows no strip`() {
        val model = model()
        show(mobile = false) { ChatTaskStrip(model, "chat-none", "ppm") }
        composeRule.waitUntil(5_000L) { tasks.seen.any { it.first == "GET /api/tasks" } }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithTag("chat-task-strip").fetchSemanticsNodes().size)
    }

    @Test
    fun `the tablet quick view shows the fields as chips, saves the next step and opens the task's window`() {
        val model = model()
        model.tasks.peek = TaskTarget(TASK_A, "ppm", "Fix login", 12)
        show(mobile = false) { model.tasks.peek?.let { TaskPeekHost(model, it) } }
        waitForTag("task-peek-popover")
        waitForTag("task-peek-chip-urgency")
        composeRule.onNodeWithTag("task-peek-chip-assignee").assertExists()
        composeRule.onNodeWithText("Ship it", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("task-peek-next-step").performClick()
        typeInto("task-peek-next-step-input", " now")
        composeRule.onNodeWithTag("task-peek-next-step-save").performClick()
        val body = WireJson.parse(waitForRequest("PATCH /api/tasks/$TASK_A"))
        assertEquals("Ship it now", body["nextStep"].string)
        composeRule.onNodeWithText("Open in window", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("task-peek-close").performClick()
        assertNull(model.tasks.peek)
    }

    @Test
    @Config(qualifiers = PHONE)
    fun `the phone quick view is a sheet with Full screen, and its next step saves`() {
        val model = model()
        model.tasks.peek = TaskTarget(TASK_A, "ppm", "Fix login", 12)
        show(mobile = true) { model.tasks.peek?.let { TaskPeekHost(model, it) } }
        waitForTag("task-peek-sheet")
        waitForTag("task-peek-next-step")
        composeRule.onNodeWithText("Full screen", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("task-peek-next-step").performClick()
        typeInto("task-peek-next-step-input", " today")
        composeRule.onNodeWithTag("task-peek-next-step-save").performClick()
        val body = WireJson.parse(waitForRequest("PATCH /api/tasks/$TASK_A"))
        assertEquals("Ship it today", body["nextStep"].string)
    }

    @Test
    fun `a manager chat shows the Tasks chip and its what-to-do-first list, another chat none`() {
        val model = model()
        show(mobile = false) {
            ManagerTasksChip(model, "mgr-1", narrow = false)
            ManagerTasksChip(model, "chat-1", narrow = false)
        }
        waitForTag("manager-tasks-chip")
        assertEquals(1, composeRule.onAllNodesWithTag("manager-tasks-chip").fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("tasks-now-dot", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("manager-tasks-chip").performClick()
        waitForTag("manager-task-$TASK_A")
        composeRule.onNodeWithText("ppm · what to do first", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("manager-task-$TASK_C").assertExists()
        composeRule.onNodeWithText("Show in sidebar", useUnmergedTree = true).assertExists()
    }

    @Test
    @Config(qualifiers = PHONE)
    fun `on a phone the manager chip drops its words and its list opens a task's quick view in place`() {
        val model = model()
        show(mobile = true) { ManagerTasksChip(model, "mgr-1", narrow = true) }
        waitForTag("manager-tasks-chip")
        assertEquals(0, composeRule.onAllNodes(hasText("tasks"), useUnmergedTree = true).fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("manager-tasks-chip").performClick()
        waitForTag("manager-task-$TASK_C")
        composeRule.onNodeWithText("Pick a vendor", useUnmergedTree = true).performClick()
        waitForTag("task-peek-back")
        waitForTag("task-peek-chips")
        composeRule.onNodeWithTag("task-peek-back").performClick()
        waitForTag("manager-tasks-list")
    }

    @Test
    fun `New task creates the task in the project and names it`() {
        val model = model()
        show(mobile = false) { TasksPanel(model) }
        waitForTag("task-row-$TASK_A")
        composeRule.onNodeWithTag("tasks-new").performClick()
        waitForTag("task-new-dialog")
        typeInto("task-new-title", "Plan the release")
        composeRule.onNodeWithTag("task-new-create").performClick()
        val body = WireJson.parse(waitForRequest("POST /api/tasks"))
        assertEquals("ppm", body["project"].string)
        assertEquals("Plan the release", body["title"].string)
    }

    @Test
    @Config(qualifiers = PHONE)
    fun `on a phone New task opens the same form`() {
        val model = model()
        show(mobile = true) { TasksPanel(model) }
        waitForTag("task-row-$TASK_A")
        composeRule.onNodeWithTag("tasks-new").performClick()
        waitForTag("task-new-title")
        typeInto("task-new-title", "Phone task")
        composeRule.onNodeWithTag("task-new-create").performClick()
        assertEquals("Phone task", WireJson.parse(waitForRequest("POST /api/tasks"))["title"].string)
    }
}
