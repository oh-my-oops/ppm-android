package com.hienlh.ppm.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.LiveBackgroundTask
import com.hienlh.ppm.state.SessionCollabState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The activity chip's Tasks tab with a direct Stop per task (`activity-chip.tsx`, web 48e2da7bc). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h1200dp-xxhdpi")
class ActivityChipTasksTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val shell = LiveBackgroundTask("b1", "local_bash", "bun run dev")
    private val agent = LiveBackgroundTask("a1", "local_agent", "", subagentType = "Explore")

    private fun show(mobile: Boolean, tasks: () -> List<LiveBackgroundTask>, stopped: MutableList<String>) {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    ActivityChip(
                        sessionId = "s1",
                        running = emptyList(),
                        teamMembers = emptyList(),
                        items = emptyList(),
                        sessions = emptyList(),
                        collab = SessionCollabState(),
                        streamingSessions = emptySet(),
                        unreads = emptyMap(),
                        unlinkedForChat = emptyMap(),
                        narrow = mobile,
                        onOpenAgent = {},
                        onOpenLinkedSession = {},
                        onSetSessionsUnlinked = { _, _, _ -> },
                        onStopChildSessions = {},
                        liveTasks = tasks(),
                        onStopTask = { stopped += it },
                    )
                }
            }
        }
    }

    @Test
    fun tablet_tasksAloneShowTheChip_openOnTasks_andStopSendsTheTaskId() {
        var tasks by mutableStateOf(listOf(shell, agent))
        val stopped = mutableListOf<String>()
        show(mobile = false, tasks = { tasks }, stopped = stopped)

        composeRule.onNodeWithTag("activity-chip").assertIsDisplayed()
            .assertContentDescriptionEquals("2 background tasks")
        composeRule.onNodeWithTag("activity-part:tasks", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("tasks").assertIsDisplayed()

        composeRule.onNodeWithTag("activity-chip").performClick()
        composeRule.onNodeWithTag("activity-tab:tasks").assertIsSelected()
        composeRule.onNodeWithText("bun run dev").assertIsDisplayed()
        composeRule.onNodeWithText("Command").assertIsDisplayed()
        // No description: the task id, and its kind with the agent's type.
        composeRule.onNodeWithText("a1").assertIsDisplayed()
        composeRule.onNodeWithText("Agent · Explore").assertIsDisplayed()

        composeRule.onNodeWithTag("stop-task:b1").assertContentDescriptionEquals("Stop bun run dev").performClick()
        assertEquals(listOf("b1"), stopped)
        composeRule.onNodeWithText("Stopping…").assertIsDisplayed()
        // A second press while it stops sends nothing more.
        composeRule.onNodeWithTag("stop-task:b1").performClick()
        assertEquals(listOf("b1"), stopped)

        // The CLI's next list drops the row.
        tasks = listOf(agent)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("bun run dev").assertDoesNotExist()
        composeRule.onNodeWithTag("activity-chip").assertContentDescriptionEquals("1 background task")

        // The last one gone: no chip, and no popover left behind.
        tasks = emptyList()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("activity-chip").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xxhdpi")
    fun phone_tasksTabInTheSheet_withoutTheWord() {
        val stopped = mutableListOf<String>()
        show(mobile = true, tasks = { listOf(shell) }, stopped = stopped)

        composeRule.onNodeWithTag("activity-part:tasks", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("task").assertDoesNotExist()
        composeRule.onNodeWithTag("activity-chip").performClick()
        composeRule.onNodeWithTag("footer-popover:Agents, background tasks and linked sessions").assertIsDisplayed()
        composeRule.onNodeWithTag("activity-tab:tasks").assertIsSelected()
        composeRule.onNodeWithTag("stop-task:b1").performClick()
        assertEquals(listOf("b1"), stopped)
    }
}
