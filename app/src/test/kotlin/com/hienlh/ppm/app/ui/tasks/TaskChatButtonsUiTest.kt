package com.hienlh.ppm.app.ui.tasks

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.TaskTarget
import com.hienlh.ppm.app.tasks
import com.hienlh.ppm.app.ui.LocalReducedMotion
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.TaskDto
import com.hienlh.ppm.protocol.TaskStatusDef
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockWebServer
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

private const val TABLET = "w900dp-h1200dp-xxhdpi"
private const val PHONE = "w400dp-h840dp-xhdpi"
private const val NARROW_PHONE = "w320dp-h700dp-xhdpi"

/** Worked on by the manager chat itself, never handed to a child chat. */
private const val TASK_M = "44444444-4444-4444-8444-444444444444"

/** Done, with a result recorded by Claude. */
private const val TASK_R = "55555555-5555-4555-8555-555555555555"

private fun managerWorking(state: String) = """{"kind":"manager","session":{"sessionId":"mgr-1","state":"$state","title":"Manager","providerId":"claude"}}"""

/**
 * Open chat and Ask manager, one button per chat, in the quick view and on the task's page (web
 * 1cd089cb3, 0dc5c4ce1, 6cc2e1bab); a result's who-and-when beside Edit (8991eb2a2, 357206cfa);
 * and the task surfaces at the chip row's 26 dp on a phone (6fe4d8a15).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TABLET)
class TaskChatButtonsUiTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val server = MockWebServer()

    @After
    fun stop() {
        server.close()
    }

    private fun model(tasks: TaskServer = TaskServer()): AppModel {
        tasks.tasks[TASK_M] = taskJson(TASK_M, 15, "Plan the release", working = managerWorking("idle"))
        tasks.tasks[TASK_R] = taskJson(TASK_R, 16, "Ship the fix", baseGroup = "closed", statusId = "done", statusName = "Done")
            .replace("\"result\":null", "\"result\":{\"text\":\"Shipped in 0.25.8\",\"at\":1700000000000,\"by\":\"Claude in the login chat of the ppm project\"}")
        server.dispatcher = tasks
        server.start()
        val model = AppModel(ApplicationProvider.getApplicationContext<Application>()).apply {
            api = ApiClient(server.url("/"), "secret")
            selectedProject = "ppm"
        }
        // Read on the main looper, which runs once there is content: [show] waits for it.
        model.tasks.refreshManagers()
        return model
    }

    private fun show(model: AppModel, mobile: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                CompositionLocalProvider(LocalReducedMotion provides true) { Column { content() } }
            }
        }
        composeRule.waitUntil(5_000L) { model.tasks.managers != null }
    }

    private fun count(tag: String) = composeRule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun waitForTag(tag: String) = composeRule.waitUntil(5_000L) { count(tag) > 0 }

    private fun peek(model: AppModel, id: String, mobile: Boolean) {
        model.tasks.peek = TaskTarget(id, "ppm", "Task", null)
        show(model, mobile) { model.tasks.peek?.let { TaskPeekHost(model, it) } }
        waitForTag("task-peek-chips")
    }

    private fun page(model: AppModel, id: String, mobile: Boolean) {
        show(model, mobile) { TaskTabBody(model, WorkspaceTab(id = "task:$id", type = "task", title = "Task", metadata = buildJsonObject { put("taskId", id); put("projectName", "ppm") })) }
        waitForTag(if (mobile) "task-detail-phone" else "task-detail")
    }

    /** Whether [text] was cut: its last line ends in "…". */
    private fun cut(text: String, substring: Boolean = false): Boolean {
        val node = composeRule.onNode(hasText(text, substring = substring), useUnmergedTree = true).fetchSemanticsNode()
        val out = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
        val layout = out.first()
        val cut = layout.isLineEllipsized(layout.lineCount - 1)
        if (cut) println("cut: '$text' lines=" + layout.lineCount + " size=" + layout.size + " width=" + node.size.width)
        return cut
    }

    private fun height(tag: String) = composeRule.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot().height

    // The quick view.

    @Test
    fun `a task handed to a chat shows Open chat and Ask manager in its quick view (tablet)`() {
        val model = model()
        peek(model, TASK_A, mobile = false)
        composeRule.onNodeWithTag("task-peek-chat").assertIsEnabled()
        composeRule.onNodeWithTag("task-peek-ask-manager").assertIsEnabled()
    }

    @Test
    @Config(qualifiers = PHONE)
    fun `a task nobody was handed shows Ask manager alone in its quick view (phone)`() {
        val model = model()
        peek(model, TASK_B, mobile = true)
        assertEquals(0, count("task-peek-chat"))
        composeRule.onNodeWithTag("task-peek-ask-manager").assertIsEnabled()
    }

    @Test
    fun `a task the manager chat works on shows Ask manager alone, never Open chat on the same chat (tablet)`() {
        val model = model()
        peek(model, TASK_M, mobile = false)
        assertEquals(0, count("task-peek-chat"))
        composeRule.onNodeWithTag("task-peek-ask-manager").assertIsEnabled()
    }

    @Test
    @Config(qualifiers = PHONE)
    fun `Ask manager is disabled when the manager chat working on the task was deleted (phone)`() {
        val server = TaskServer(managers = """{"managers":[]}""")
        val model = model(server)
        server.tasks[TASK_M] = taskJson(TASK_M, 15, "Plan the release", working = managerWorking("gone"))
        peek(model, TASK_M, mobile = true)
        assertEquals(0, count("task-peek-chat"))
        composeRule.onNodeWithTag("task-peek-ask-manager").assertIsNotEnabled()
    }

    @Test
    fun `a project with no manager chat shows no Ask manager (tablet)`() {
        val model = model(TaskServer(managers = """{"managers":[]}"""))
        peek(model, TASK_A, mobile = false)
        composeRule.onNodeWithTag("task-peek-chat").assertExists()
        assertEquals(0, count("task-peek-ask-manager"))
    }

    @Test
    @Config(qualifiers = NARROW_PHONE)
    fun `a narrow quick view keeps Open chat and Ask manager whole (phone)`() {
        val model = model()
        peek(model, TASK_A, mobile = true)
        waitForTag("task-peek-ask-manager")
        assertFalse("Open chat is cut", cut("Open chat"))
        assertFalse("Ask manager is cut", cut("Ask manager"))
        assertFalse("Full screen is cut", cut("Full screen"))
    }

    // The task's page.

    @Test
    @Config(qualifiers = PHONE)
    fun `on a phone the working chat block offers Open chat and Ask manager for a handed task`() {
        val model = model()
        page(model, TASK_A, mobile = true)
        waitForTag("task-working-ask-manager")
        composeRule.onNodeWithTag("task-open-chat", useUnmergedTree = true).assertExists()
    }

    @Test
    @Config(qualifiers = PHONE)
    fun `on a phone the manager chat's own task offers Ask manager instead of Open chat`() {
        val model = model()
        page(model, TASK_M, mobile = true)
        waitForTag("task-working-ask-manager")
        assertEquals(0, count("task-open-chat"))
    }

    @Test
    fun `on a tablet the manager chat's own task has Ask manager in the header only`() {
        val model = model()
        page(model, TASK_M, mobile = false)
        composeRule.waitUntil(5_000L) { composeRule.onAllNodes(hasText("Ask manager"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, composeRule.onAllNodes(hasText("Ask manager"), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, count("task-open-chat"))
        assertEquals(0, count("task-working-ask-manager"))
    }

    // A result's who and when.

    @Test
    fun `on a tablet a result's who-and-when sits on Edit's line, cut before it`() {
        val model = model()
        page(model, TASK_R, mobile = false)
        waitForTag("task-result-meta")
        val meta = composeRule.onNodeWithTag("task-result-meta", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val edit = composeRule.onNodeWithTag("task-result-edit", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("meta ends before Edit", meta.right <= edit.left)
        assertTrue("same line as Edit", meta.top < edit.bottom && edit.top < meta.bottom)
    }

    @Test
    @Config(qualifiers = NARROW_PHONE)
    fun `on a phone a result's who-and-when is shown whole under Result, beside Edit`() {
        val model = model()
        page(model, TASK_R, mobile = true)
        waitForTag("task-result-meta")
        assertFalse("the who-and-when is cut", cut("Claude in the login chat of the ppm project", substring = true))
        val meta = composeRule.onNodeWithTag("task-result-meta", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val title = composeRule.onNode(hasText("Result"), useUnmergedTree = true).getUnclippedBoundsInRoot()
        val edit = composeRule.onNodeWithTag("task-result-edit", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("under Result", meta.top >= title.bottom)
        assertTrue("beside Edit", meta.right <= edit.left)
    }

    // 26 dp on a phone.

    @Test
    @Config(qualifiers = PHONE)
    fun `on a phone the page's status and Done are 26 dp`() {
        val model = model()
        page(model, TASK_A, mobile = true)
        composeRule.onNodeWithTag("task-status-button").assertHeightIsEqualTo(26.dp)
        composeRule.onNodeWithTag("task-mark-done").assertHeightIsEqualTo(26.dp)
    }

    @Test
    @Config(qualifiers = PHONE)
    fun `on a phone a task row's working chat chip is at least 26 dp and below 44`() {
        val model = model()
        show(model, mobile = true) { TasksPanel(model) }
        waitForTag("task-session-chip")
        val h = composeRule.onAllNodes(hasTestTag("task-session-chip"), useUnmergedTree = true)[0].getUnclippedBoundsInRoot().height
        assertTrue("chip $h", h >= 26.dp && h < 44.dp)
    }

    @Test
    fun `on a tablet a task row's working chat chip keeps its 20 dp`() {
        val model = model()
        show(model, mobile = false) { TasksPanel(model) }
        waitForTag("task-session-chip")
        val h = composeRule.onAllNodes(hasTestTag("task-session-chip"), useUnmergedTree = true)[0].getUnclippedBoundsInRoot().height
        assertTrue("chip $h", h >= 20.dp && h < 26.dp)
    }

    @Test
    @Config(qualifiers = PHONE)
    fun `the phone long-press sheet's statuses, rows and urgency buttons are at the chip row's height`() {
        val model = model()
        val task = TaskDto.from(WireJson.parse(taskJson(TASK_B, 13, "Write docs", urgency = "week")))!!
        val statuses = listOf(
            TaskStatusDef("todo", "To do", "not_started", null, 0),
            TaskStatusDef("doing", "In progress", "active", null, 1),
            TaskStatusDef("done", "Done", "closed", "done", 2),
        )
        show(model, mobile = true) { TaskRowActionSheet(task, false, statuses, null, null, true, rememberTaskActions(model)) {} }
        waitForTag("task-action-sheet")
        for (s in statuses) {
            val h = height("status-${s.id}")
            assertTrue("status ${s.id} $h", h >= 26.dp && h < 44.dp)
        }
        for (label in listOf("Urgency", "Reorder…", "Ask manager about this")) {
            val h = composeRule.onNode(hasText(label, substring = true) and hasClickAction()).getUnclippedBoundsInRoot().height
            assertTrue("row $label $h", h >= 26.dp && h < 44.dp)
        }
        composeRule.onNode(hasText("Urgency", substring = true) and hasClickAction()).performClick()
        waitForTag("urgency-now")
        composeRule.onNodeWithTag("urgency-now").assertHeightIsAtLeast(26.dp)
        assertTrue("urgency button", height("urgency-now") < 44.dp)
    }
}
