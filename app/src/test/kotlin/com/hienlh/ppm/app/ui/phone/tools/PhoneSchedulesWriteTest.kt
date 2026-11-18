package com.hienlh.ppm.app.ui.phone.tools

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.hienlh.ppm.app.ui.SchedulesSettingsContent
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.settings.ScheduleProjectOption
import com.hienlh.ppm.state.settings.ScheduleRowState
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** New, Edit, Import and Delete of a schedule (`schedules-settings-section.tsx`, `schedule-form.tsx`), on a phone and a tablet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp-xxhdpi")
class PhoneSchedulesWriteTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val projects = listOf(ScheduleProjectOption(name = "app", path = "/p/app"))
    private val nightly = ScheduleRowState(
        id = 1,
        name = "Nightly backup",
        kind = "script",
        cronExpr = "0 2 * * *",
        projectPath = "/p/app",
        script = "./backup.sh",
        timeoutMs = 60_000,
    )

    @Test
    fun `a phone creates, edits, imports and deletes a schedule in sheets`() {
        val state = PhoneSchedulesState(initialSchedules = listOf(nightly))
        var created: JsonObject? = null
        var updated: Pair<Int, JsonObject>? = null
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneSchedulesScreen(
                    state = state,
                    projects = projects,
                    onRunOrRestart = {},
                    onToggleEnabled = { _, _ -> },
                    onCreate = { created = it },
                    onUpdate = { row, body -> updated = row.id to body },
                    onImport = { _, _, _ -> },
                )
            }
        }
        assertEquals(0, composeRule.onAllNodesWithText("on a tablet", substring = true).fetchSemanticsNodes().size)

        // New schedule: the form in a bottom sheet.
        composeRule.onNodeWithTag("phone-schedules-new-btn").performScrollTo().performClick()
        composeRule.onNodeWithTag("phone-schedule-form-sheet").assertExists()
        composeRule.onNodeWithTag("schedule-form-name").performTextReplacement("Daily review")
        composeRule.onNodeWithTag("schedule-form-prompt").performTextReplacement("Review the open PRs")
        composeRule.onNodeWithTag("schedule-form-submit").performScrollTo().performClick()
        assertEquals("Daily review", created?.get("name")?.string)
        assertEquals("prompt", created?.get("kind")?.string)
        assertEquals("/p/app", created?.get("project_path")?.string)

        // Edit the script of the expanded schedule.
        composeRule.onNodeWithTag("phone-schedule-edit-1").performScrollTo().performClick()
        composeRule.onNodeWithTag("schedule-form-submit").assertTextContains("Save changes")
        composeRule.onNodeWithTag("schedule-form-script").performTextReplacement("./backup.sh --full")
        composeRule.onNodeWithTag("schedule-form-submit").performScrollTo().performClick()
        assertEquals(1, updated?.first)
        assertEquals("./backup.sh --full", updated?.second?.get("script")?.string)
        assertFalse(updated!!.second.containsKey("kind"))

        // Import opens its sheet.
        composeRule.onNodeWithTag("phone-schedules-import-btn").performScrollTo().performClick()
        composeRule.onNodeWithTag("phone-schedule-import-sheet").assertExists()
    }

    @Test
    fun `a phone deletes the expanded schedule`() {
        val state = PhoneSchedulesState(initialSchedules = listOf(nightly))
        var deleted: Int? = null
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneSchedulesScreen(
                    state = state,
                    projects = projects,
                    onRunOrRestart = {},
                    onToggleEnabled = { _, _ -> },
                    onDelete = { deleted = it.id },
                )
            }
        }
        composeRule.onNodeWithTag("phone-schedule-delete-1").performScrollTo().performClick()
        assertEquals(1, deleted)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w1280dp-h800dp-xxhdpi")
    fun `a tablet edits a schedule on the same form`() {
        var updated: Pair<Int, JsonObject>? = null
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                SchedulesSettingsContent(
                    schedules = listOf(nightly),
                    projects = projects,
                    onToggleEnabledOnly = {},
                    onRunNow = {},
                    onToggleScheduleEnabled = { _, _ -> },
                    onDeleteSchedule = {},
                    onCreateSchedule = {},
                    onUpdateSchedule = { id, body -> updated = id to body },
                    onExportTemplate = {},
                    onImportTemplate = { _, _ -> },
                    onOpenLogs = {},
                    onCloseLogs = {},
                )
            }
        }
        composeRule.onNodeWithTag("schedule-edit-1").performClick()
        assertTrue(composeRule.onAllNodesWithText("Edit: Nightly backup").fetchSemanticsNodes().isNotEmpty())
        composeRule.onNodeWithTag("schedule-form-cron").performTextReplacement("30 3 * * *")
        composeRule.onNodeWithTag("schedule-form-submit").performClick()
        assertEquals(1, updated?.first)
        assertEquals("30 3 * * *", updated?.second?.get("cron_expr")?.string)
        assertEquals("60000", updated?.second?.get("timeout_ms")?.toString())
    }
}
