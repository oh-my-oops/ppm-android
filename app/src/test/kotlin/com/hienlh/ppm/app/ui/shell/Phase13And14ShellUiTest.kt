package com.hienlh.ppm.app.ui.shell

import androidx.compose.foundation.text.BasicText
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
import com.hienlh.ppm.state.shell.HingeBounds
import com.hienlh.ppm.state.shell.HingeOcclusion
import com.hienlh.ppm.state.shell.HingeOrientation
import com.hienlh.ppm.state.shell.OnboardingGoalId
import com.hienlh.ppm.state.shell.OnboardingState
import com.hienlh.ppm.state.shell.PreviousExitCrashInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1024dp-h768dp")
class Phase13And14ShellUiTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    @Test
    fun `FoldablePostureLayout renders book and tabletop splits around hinge`() {
        var hinge by mutableStateOf<HingeBounds?>(
            HingeBounds(
                leftPx = 500,
                topPx = 0,
                rightPx = 524,
                bottomPx = 768,
                orientation = HingeOrientation.VERTICAL,
                isSeparating = true,
                occlusion = HingeOcclusion.FULL,
            ),
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                FoldablePostureLayout(
                    hinge = hinge,
                    windowWidthPx = 1024,
                    windowHeightPx = 768,
                    primaryPane = { BasicText("Primary Pane Content") },
                    secondaryPane = { BasicText("Secondary Pane Content") },
                )
            }
        }

        composeRule.onNodeWithTag("foldable-posture-book").assertIsDisplayed()
        composeRule.onNodeWithText("Primary Pane Content").assertIsDisplayed()
        composeRule.onNodeWithText("Secondary Pane Content").assertIsDisplayed()

        // Switch to tabletop posture
        hinge = HingeBounds(
            leftPx = 0,
            topPx = 360,
            rightPx = 1024,
            bottomPx = 380,
            orientation = HingeOrientation.HORIZONTAL,
            isSeparating = true,
            occlusion = HingeOcclusion.NONE,
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("foldable-posture-tabletop").assertIsDisplayed()
        composeRule.onNodeWithText("Primary Pane Content").assertIsDisplayed()
        composeRule.onNodeWithText("Secondary Pane Content").assertIsDisplayed()
    }

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
    fun `PreviousExitCrashCard displays crash reason and copies markdown report`() {
        var copiedMarkdown: String? = null
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
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("Previous session exited unexpectedly (REASON_CRASH)").assertIsDisplayed()
        composeRule.onNodeWithText("Copy Crash Report").performClick()
        assertTrue(copiedMarkdown?.contains("REASON_CRASH") == true)
    }
}
