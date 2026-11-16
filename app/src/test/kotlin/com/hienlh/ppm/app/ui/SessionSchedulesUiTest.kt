package com.hienlh.ppm.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.app.ui.phone.tools.PhoneSchedulesScreen
import com.hienlh.ppm.app.ui.phone.tools.PhoneSchedulesState
import com.hienlh.ppm.chat.ScheduleRunLine
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.ScheduleRunReference
import com.hienlh.ppm.state.settings.ScheduleProjectOption
import com.hienlh.ppm.state.settings.ScheduleRowState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The chat's schedule chip (`session-schedules-chip.tsx`) and a schedule's run line (`schedule-run-line.tsx`). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h1200dp-xxhdpi")
class SessionSchedulesUiTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val daily = ScheduleRowState(
        id = 4,
        name = "Morning check",
        kind = "session",
        cronExpr = "0 8 * * *",
        timezone = "Europe/London",
        targetSessionId = "abcdef12",
        nextFireAt = "2099-01-02T08:00:00.000Z",
    )

    private class FakeActions(var refuse: String? = null) : SessionScheduleActions {
        val calls = mutableListOf<String>()

        override suspend fun setEnabled(id: Int, enabled: Boolean) {
            calls += "enabled $id $enabled"
            refuse?.let { throw IllegalStateException(it) }
        }

        override suspend fun delete(id: Int) {
            calls += "delete $id"
        }
    }

    @Test
    fun theChip_isAbsentWithoutSchedules_countsThem_andPausesAndDeletesFromItsPopover() {
        var schedules by mutableStateOf(emptyList<ScheduleRowState>())
        val actions = FakeActions()
        var refreshes = 0
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    Column {
                        SessionSchedulesChip(schedules, narrow = false, actions = actions, onRefresh = { refreshes++ }, onError = {})
                    }
                }
            }
        }
        composeRule.onNodeWithTag("session-schedules-chip").assertDoesNotExist()

        schedules = listOf(daily, daily.copy(id = 5, name = "Once", recurring = false, enabled = false))
        composeRule.onNodeWithTag("session-schedules-chip").assertIsDisplayed()
        composeRule.onNodeWithText("2").assertIsDisplayed()
        composeRule.onNodeWithText("schedules").assertIsDisplayed()
        composeRule.onNodeWithTag("session-schedules-attention", useUnmergedTree = true).assertDoesNotExist()

        composeRule.onNodeWithTag("session-schedules-chip").performClick()
        assertEquals(1, refreshes)
        composeRule.onNodeWithText("2 schedules").assertIsDisplayed()
        composeRule.onNodeWithText("Daily at 08:00 · Repeats").assertIsDisplayed()
        composeRule.onNodeWithText("Daily at 08:00 · Runs once").assertIsDisplayed()
        composeRule.onNodeWithText("Off").assertIsDisplayed()
        composeRule.onNodeWithText("Not scheduled").assertIsDisplayed()
        composeRule.onNodeWithText("Active").assertIsDisplayed()

        composeRule.onNode(hasTestTag("session-schedule-toggle") and hasAnyAncestor(hasTestTag("session-schedule-4"))).performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasTestTag("session-schedule-toggle") and hasAnyAncestor(hasTestTag("session-schedule-5"))).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("enabled 4 false", "enabled 5 true"), actions.calls)
        assertEquals(3, refreshes)

        // Delete asks once more in place.
        composeRule.onNode(hasTestTag("session-schedule-delete") and hasAnyAncestor(hasTestTag("session-schedule-4"))).performClick()
        composeRule.onNodeWithText("Delete this schedule?").assertIsDisplayed()
        composeRule.onNodeWithTag("session-schedule-confirm-delete").performClick()
        composeRule.waitForIdle()
        assertEquals("delete 4", actions.calls.last())
        composeRule.onNodeWithText("Delete this schedule?").assertDoesNotExist()
    }

    @Test
    fun aScheduleWaitingOrPaused_marksTheChip_andARefusalIsSaidInTheServersWords() {
        val actions = FakeActions(refuse = "Its chat cannot take schedules")
        val errors = mutableListOf<String>()
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    Column {
                        SessionSchedulesChip(
                            listOf(daily.copy(enabled = false, pausedReason = "Its chat was deleted")),
                            narrow = false,
                            actions = actions,
                            onRefresh = {},
                            onError = { errors += it },
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag("session-schedules-attention", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("schedule").assertIsDisplayed()
        composeRule.onNodeWithTag("session-schedules-chip").performClick()
        composeRule.onNodeWithText("Paused").assertIsDisplayed()
        composeRule.onNodeWithText("Its chat was deleted").assertIsDisplayed()
        composeRule.onNodeWithText("Resume").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("enabled 4 true"), actions.calls)
        assertEquals(listOf("Its chat cannot take schedules"), errors)
    }

    @Test
    fun onAPhone_theChipOpensASheetListingTheChatsSchedules() {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    Column {
                        SessionSchedulesChip(
                            listOf(daily.copy(pendingSince = "2026-11-16T08:00:00.000Z", heldAfterStop = true)),
                            narrow = true,
                            actions = FakeActions(),
                            onRefresh = {},
                            onError = {},
                        )
                    }
                }
            }
        }
        // The noun is left out on a phone; the count says it.
        composeRule.onNodeWithText("schedule").assertDoesNotExist()
        composeRule.onNodeWithTag("session-schedules-chip").performClick()
        composeRule.onNodeWithTag("footer-popover:Schedules for this chat").assertExists()
        composeRule.onNodeWithText("Held after Stop").assertExists()
        composeRule.onNodeWithText("Due now", substring = true).assertDoesNotExist()
    }

    private val run = ScheduleRunReference(
        scheduleId = 9,
        name = "Morning check",
        cron = "0 8 * * 1-5",
        timezone = "Europe/London",
        scheduledFor = "2026-11-16T08:00:00.000Z",
        firedAt = "2026-11-16T08:03:00.000Z",
        catchUp = true,
        coalesced = false,
    )

    @Test
    fun aRunLine_saysWhichScheduleRanAndWhen_andOpensToItsPrompt() {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                ScheduleRunLine(run, "Check the open PRs.", now = Instant.parse("2026-11-16T12:00:00Z"))
            }
        }
        composeRule.onNodeWithText("Schedule").assertIsDisplayed()
        composeRule.onNodeWithText("Morning check").assertIsDisplayed()
        composeRule.onNodeWithText("ran · 08:03 AM (London)").assertIsDisplayed()
        composeRule.onNodeWithText("· sent late").assertIsDisplayed()
        composeRule.onNodeWithTag("schedule-run-panel").assertDoesNotExist()

        composeRule.onNodeWithTag("schedule-run-toggle").performClick()
        composeRule.onNodeWithText("Check the open PRs.").assertIsDisplayed()
        composeRule.onNodeWithText("Weekdays at 08:00 · 0 8 * * 1-5 · London · due 08:00 AM (London)").assertIsDisplayed()
        composeRule.onNodeWithTag("schedule-run-toggle").performClick()
        composeRule.onNodeWithTag("schedule-run-panel").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp-xxhdpi")
    fun onANarrowScreen_theLineKeepsTheName_andTheNoteMovesIntoThePanel() {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                ScheduleRunLine(run.copy(timezone = null, scheduledFor = run.firedAt, coalesced = true), "", now = Instant.parse("2026-11-16T12:00:00Z"))
            }
        }
        composeRule.onNodeWithText("Schedule").assertDoesNotExist()
        composeRule.onNodeWithText("Morning check").assertIsDisplayed()
        composeRule.onNodeWithText("· sent late · later fires folded in").assertDoesNotExist()
        composeRule.onNodeWithTag("schedule-run-toggle").performClick()
        composeRule.onNodeWithText("(empty prompt)").assertIsDisplayed()
        composeRule.onNodeWithText("Weekdays at 08:00 · 0 8 * * 1-5 · the PPM host's time zone · sent late · later fires folded in").assertIsDisplayed()
    }

    private val projectOptions = listOf(
        ScheduleProjectOption(name = "ppm", path = "/p/ppm"),
        ScheduleProjectOption(name = "web", path = "/p/web"),
    )

    @Test
    fun theCreateForm_chatKind_picksAChatOfTheProject_andSendsASessionSchedule() {
        var created: JsonObject? = null
        val asked = mutableListOf<String>()
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                ScheduleCreateFormContent(
                    projects = projectOptions,
                    onDismiss = {},
                    onSubmit = { created = it },
                    loadChats = { project ->
                        asked += project
                        if (project == "ppm") listOf(ScheduleChatOption("abcdef12", "Release prep")) else emptyList()
                    },
                )
            }
        }
        composeRule.onNodeWithTag("schedule-kind-session").performClick()
        composeRule.onNodeWithTag("schedule-chat-abcdef12").performScrollTo().performClick()
        composeRule.onNodeWithText("Sent into this chat on its own settings, once it is idle.").assertExists()
        composeRule.onNodeWithTag("schedule-form-cron").performScrollTo().performTextReplacement("0 8 * * 1-5")
        composeRule.onNodeWithText("Weekdays at 08:00").assertExists()

        // A zone that is not one is said under the field, and holds the form back.
        composeRule.onNodeWithTag("schedule-form-timezone").performScrollTo().performTextReplacement("Mars/Base")
        composeRule.onNodeWithText("timezone must be an IANA time zone such as Europe/London").assertExists()
        composeRule.onNodeWithTag("schedule-form-timezone").performTextReplacement("Europe/London")
        composeRule.onNodeWithText("Blank reads the cron in the PPM host's own time zone.").assertExists()
        composeRule.onNodeWithTag("schedule-form-run-once").performScrollTo().performClick()
        composeRule.onNodeWithTag("schedule-form-prompt").performScrollTo().performTextReplacement("Check the open PRs.")
        composeRule.onNodeWithTag("schedule-form-submit").performScrollTo().performClick()

        val body = created!!
        assertEquals(JsonPrimitive("session"), body["kind"])
        assertEquals(JsonPrimitive("abcdef12"), body["target_session_id"])
        assertEquals(JsonPrimitive("Europe/London"), body["timezone"])
        assertEquals(JsonPrimitive(false), body["recurring"])
        assertEquals(JsonPrimitive("0 8 * * 1-5"), body["cron_expr"])
        assertNull(body["mode"])

        // Another project's chats are read again, and the chat picked before no longer counts.
        created = null
        composeRule.onNodeWithTag("schedule-project-web").performScrollTo().performClick()
        composeRule.onNodeWithText("No chats in this project").assertExists()
        composeRule.onNodeWithText("Pick a chat").assertExists()
        assertEquals(listOf("ppm", "web"), asked)
    }

    @Test
    fun aChatScheduleRow_namesItsChat_opensIt_andOffersResumeWhenPaused() {
        val opened = mutableListOf<Int>()
        val toggled = mutableListOf<String>()
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SchedulesSettingsContent(
                        schedules = listOf(
                            daily.copy(
                                projectPath = "/p/ppm",
                                targetTitle = "Release prep",
                                enabled = false,
                                pausedReason = "Its chat was deleted",
                            ),
                        ),
                        projects = projectOptions,
                        onToggleEnabledOnly = {},
                        onRunNow = {},
                        onToggleScheduleEnabled = { id, on -> toggled += "$id $on" },
                        onDeleteSchedule = {},
                        onCreateSchedule = {},
                        onExportTemplate = {},
                        onImportTemplate = { _, _ -> },
                        onOpenLogs = {},
                        onCloseLogs = {},
                        onOpenChat = { opened += it.id },
                    )
                }
            }
        }
        composeRule.onNodeWithText("Release prep").performScrollTo().performClick()
        assertEquals(listOf(4), opened)
        composeRule.onNodeWithText("· ppm").assertExists()
        composeRule.onNodeWithText("Paused").assertExists()
        composeRule.onNodeWithText("Its chat was deleted").assertExists()
        composeRule.onNodeWithTag("schedule-resume-4").performScrollTo().performClick()
        assertEquals(listOf("4 true"), toggled)
        composeRule.onNodeWithText("Export").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w412dp-h1200dp-xxhdpi")
    fun onAPhone_aChatScheduleRow_saysWhenAndWhere_andNamesItsChat() {
        val opened = mutableListOf<Int>()
        val toggled = mutableListOf<String>()
        val row = daily.copy(
            projectPath = "/p/ppm",
            targetTitle = "Release prep",
            enabled = true,
            pendingSince = "2026-11-16T08:00:00.000Z",
        )
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneSchedulesScreen(
                    state = PhoneSchedulesState(initialSchedules = listOf(row)),
                    onRunOrRestart = {},
                    onToggleEnabled = { r, on -> toggled += "${r.id} $on" },
                    projects = projectOptions,
                    onOpenChat = { opened += it.id },
                )
            }
        }
        composeRule.onNodeWithText("0 8 * * * · London · repeats", substring = true).assertExists()
        composeRule.onNodeWithTag("phone-schedule-status-4").assertExists()
        composeRule.onNodeWithText("Waiting for the chat").assertExists()
        composeRule.onNodeWithText("Due since", substring = true).assertExists()
        composeRule.onNodeWithText("Release prep").performScrollTo().performClick()
        assertEquals(listOf(4), opened)
        composeRule.onNodeWithTag("schedule-resume-4").assertDoesNotExist()
        assertEquals(emptyList<String>(), toggled)
    }
}
