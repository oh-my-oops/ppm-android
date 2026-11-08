package com.hienlh.ppm.app.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.shell.OnboardingGoalId
import com.hienlh.ppm.state.shell.OnboardingState
import com.hienlh.ppm.state.shell.PreviousExitCrashInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1024dp-h768dp")
class Phase13And14ShellUiTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    @Test
    fun `LearnPpmDialog switches goals, inserts suggested prompt without auto-sending, and navigates Quick Orientation`() {
        var state by mutableStateOf(OnboardingState(active = true, selectedGoal = OnboardingGoalId.AI))
        var insertedPrompt: String? = null

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                LearnPpmDialog(
                    state = state,
                    onStateChange = { state = it },
                    onRefreshEvidence = {},
                    onInsertSuggestedPrompt = { insertedPrompt = it },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("Learn PPM").assertIsDisplayed()
        composeRule.onNodeWithText("Draft a prompt in the composer").assertIsDisplayed()

        // Click "Insert suggested prompt into composer (does not auto-send)"
        composeRule.onNodeWithTag("learn-ppm-insert-prompt-ai.send").performScrollTo().performClick()
        assertTrue(insertedPrompt?.contains("Summarize the architecture") == true)

        // Switch goal to Develop & Ship
        composeRule.onNodeWithTag("learn-ppm-goal-developer").performClick()
        composeRule.onNodeWithText("Open a Terminal session").assertIsDisplayed()

        // Open Quick Orientation 2-page modal
        composeRule.onNodeWithTag("learn-ppm-quick-orientation-btn").performClick()
        composeRule.onNodeWithText("Page 1 of 2").assertIsDisplayed()
        composeRule.onNodeWithText("Next").performClick()
        composeRule.onNodeWithText("Page 2 of 2").assertIsDisplayed()
        composeRule.onNodeWithText("Finish").performClick()
        assertEquals(null, state.quickOrientationPage)
    }

    @Test
    fun `PreviousExitCrashCard displays crash reason, copies markdown report, and triggers dismiss`() {
        var copiedMarkdown: String? = null
        var dismissed = false
        val crash = PreviousExitCrashInfo(
            reasonCode = 4,
            reasonName = "REASON_CRASH",
            timestampMs = 1762560000000L,
            processName = "com.hienlh.ppm",
            description = "IllegalStateException: unexpected state",
            traceSnippet = "java.lang.IllegalStateException: unexpected state",
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                PreviousExitCrashCard(
                    crashInfo = crash,
                    onCopyReport = { copiedMarkdown = it },
                    onDismiss = { dismissed = true },
                )
            }
        }

        composeRule.onNodeWithText("Previous session exited unexpectedly (REASON_CRASH)").assertIsDisplayed()
        composeRule.onNodeWithTag("previous-exit-crash-copy").performClick()
        assertTrue(copiedMarkdown?.contains("REASON_CRASH") == true)

        composeRule.onNodeWithTag("previous-exit-crash-dismiss").performClick()
        assertTrue(dismissed)

        // Verify SharedPreferences persistence helpers for Learn PPM state and crash dismissal
        val appContext = RuntimeEnvironment.getApplication()
        assertNull(getDismissedCrashTimestamp(appContext))
        saveDismissedCrashTimestamp(appContext, crash.timestampMs)
        assertEquals(crash.timestampMs, getDismissedCrashTimestamp(appContext))

        val savedState = OnboardingState(
            active = true,
            selectedGoal = OnboardingGoalId.DEVELOPER,
            completedSteps = setOf("developer.project", "developer.terminal"),
        )
        saveOnboardingStateToPrefs(appContext, savedState)
        val loadedState = loadOnboardingStateFromPrefs(appContext)
        assertEquals(OnboardingGoalId.DEVELOPER, loadedState.selectedGoal)
        assertEquals(setOf("developer.project", "developer.terminal"), loadedState.completedSteps)
    }
}
