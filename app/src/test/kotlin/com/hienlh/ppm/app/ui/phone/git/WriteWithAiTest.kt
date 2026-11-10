package com.hienlh.ppm.app.ui.phone.git

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.git.WriteWithAi
import com.hienlh.ppm.state.git.WriteWithAiController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h840dp-xhdpi")
class WriteWithAiTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    @Test
    fun phoneCommitBox_disablesWriteWithAiWithStageChangesFirstWhenNothingStaged_andHidesWhenUnsupported() {
        var stagedCount by mutableStateOf(0)
        var aiAvailable by mutableStateOf(true)
        var message by mutableStateOf("")
        var writeWithAiClicks = 0

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                CommitBox(
                    message = message,
                    onMessageChange = { message = it },
                    stagedCount = stagedCount,
                    totalChangedFiles = 2,
                    aiAvailable = aiAvailable,
                    aiState = WriteWithAi.Idle,
                    onWriteWithAiClick = { writeWithAiClicks++ },
                )
            }
        }

        // Requirement 10.4: When nothing is staged, Write with AI is disabled with "Stage changes first".
        composeRule.onNodeWithTag("phone-git-ai-chip")
            .assertIsDisplayed()
            .assertIsNotEnabled()
            .assertContentDescriptionContains("Stage changes first", substring = true)
        composeRule.onNodeWithTag("phone-git-ai-disabled-reason")
            .assertIsDisplayed()
            .assertTextContains("Stage changes first")

        // Stage a file -> Write with AI becomes enabled and disabled reason disappears.
        stagedCount = 2
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("phone-git-ai-chip").assertIsEnabled()
        assertEquals(0, composeRule.onAllNodesWithTag("phone-git-ai-disabled-reason").fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("phone-git-ai-chip").performClick()
        assertEquals(1, writeWithAiClicks)

        // When server does not have the endpoint (404), button is hidden.
        aiAvailable = false
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithTag("phone-git-ai-chip").fetchSemanticsNodes().size)
    }

    @Test
    fun phoneCommitBox_locksCommitWhileWriting_leavesWrittenEditableNeverAutoCommits_andKeepsTextWithRetryOnFailed() {
        var message by mutableStateOf("wip draft message")
        var aiState by mutableStateOf<WriteWithAi>(WriteWithAi.Idle)
        var commitClicks = 0
        var retryClicks = 0
        var undoClicks = 0

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                CommitBox(
                    message = message,
                    onMessageChange = { message = it },
                    stagedCount = 2,
                    totalChangedFiles = 3,
                    aiAvailable = true,
                    aiState = aiState,
                    onWriteWithAiClick = {
                        aiState = WriteWithAi.Writing
                    },
                    onRetryWriteWithAi = {
                        retryClicks++
                        aiState = WriteWithAi.Written(
                            message = "feat(git): add Write with AI button",
                            previousMessage = message,
                            stagedCount = 2,
                        )
                        message = "feat(git): add Write with AI button"
                    },
                    onUndoWriteWithAi = {
                        undoClicks++
                        message = "wip draft message"
                        aiState = WriteWithAi.Idle
                    },
                    onCommitClick = { commitClicks++ },
                )
            }
        }

        // Idle with staged files + draft message -> Commit is enabled.
        composeRule.onNodeWithTag("phone-git-commit-button").assertIsEnabled()

        // Tap Write with AI -> transitions to Writing and locks Commit (Requirement 10.2).
        composeRule.onNodeWithTag("phone-git-ai-chip").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("phone-git-commit-button").assertIsNotEnabled()
        composeRule.onNodeWithTag("phone-git-commit-input").assertIsNotEnabled()
        composeRule.onNodeWithTag("phone-git-commit-hint")
            .assertTextContains("Writing a message from 2 staged files…")
        assertEquals(0, commitClicks)

        // Fail generation -> keeps user's typed text and offers Retry (Requirement 10.5).
        aiState = WriteWithAi.Failed(
            message = "Rate limit exceeded",
            lastMessage = message,
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("phone-git-ai-error").assertIsDisplayed()
        composeRule.onNodeWithText("Rate limit exceeded").assertIsDisplayed()
        composeRule.onNodeWithTag("phone-git-commit-input").assertTextContains("wip draft message")
        // Commit is unlocked again because user still has staged files + non-empty preserved draft.
        composeRule.onNodeWithTag("phone-git-commit-button").assertIsEnabled()

        // Tap Retry -> transitions to Written, populates commit message, leaves editable, never commits automatically (Requirement 10.3).
        composeRule.onNodeWithTag("phone-git-ai-retry").performClick()
        composeRule.waitForIdle()
        assertEquals(1, retryClicks)
        assertEquals(0, commitClicks)
        composeRule.onNodeWithTag("phone-git-commit-input")
            .assertIsEnabled()
            .assertTextContains("feat(git): add Write with AI button")
        composeRule.onNodeWithTag("phone-git-commit-hint")
            .assertTextContains("Written by the AI — edit before committing")

        // Edit generated message before committing.
        composeRule.onNodeWithTag("phone-git-commit-input")
            .performTextReplacement("feat(git): add Write with AI button (reviewed)")
        composeRule.waitForIdle()
        assertEquals("feat(git): add Write with AI button (reviewed)", message)
        assertEquals(0, commitClicks)

        // Undo restores previous draft.
        composeRule.onNodeWithTag("phone-git-ai-undo").performClick()
        composeRule.waitForIdle()
        assertEquals(1, undoClicks)
        assertEquals("wip draft message", message)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w1024dp-h768dp-xhdpi")
    fun tabletCommitBox_supportsWriteWithAiStatesAndLocksSplitCommitWhileWriting() {
        val controller = WriteWithAiController(
            initialMessage = "draft tablet commit",
            initialStagedCount = 0,
        )
        var messageState by mutableStateOf(controller.commitMessage)
        var stagedState by mutableStateOf(controller.stagedCount)
        var aiState by mutableStateOf(controller.state)
        var aiVisible by mutableStateOf(controller.isVisible)
        var commitCount = 0

        fun syncFromController() {
            messageState = controller.commitMessage
            stagedState = controller.stagedCount
            aiState = controller.state
            aiVisible = controller.isVisible
        }

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                CommitBox(
                    message = messageState,
                    onMessageChange = {
                        controller.updateMessage(it)
                        syncFromController()
                    },
                    stagedCount = stagedState,
                    totalChangedFiles = 2,
                    aiAvailable = aiVisible,
                    aiState = aiState,
                    isTablet = true,
                    onWriteWithAiClick = {
                        if (controller.startWriting()) {
                            syncFromController()
                        }
                    },
                    onRetryWriteWithAi = {
                        if (controller.startWriting()) {
                            controller.onGenerated("fix(tablet): polish commit box")
                            syncFromController()
                        }
                    },
                    onCommitClick = { commitCount++ },
                )
            }
        }

        // Tablet commit box rendered; nothing staged -> AI disabled with "Stage changes first".
        composeRule.onNodeWithTag("tablet-git-commit-box").assertIsDisplayed()
        composeRule.onNodeWithTag("phone-git-ai-chip")
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeRule.onNodeWithTag("phone-git-ai-disabled-reason")
            .assertTextContains("Stage changes first")

        // Stage 2 files -> AI enabled and split Commit button shows "Commit 2 files".
        controller.updateStagedCount(count = 2)
        syncFromController()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("phone-git-ai-chip").assertIsEnabled()
        composeRule.onNodeWithTag("phone-git-commit-button")
            .assertIsEnabled()
            .assertTextContains("Commit 2 files")

        // Start writing -> locks both primary Commit button and split dropdown chevron button.
        composeRule.onNodeWithTag("phone-git-ai-chip").performClick()
        composeRule.waitForIdle()
        assertTrue(aiState is WriteWithAi.Writing)
        composeRule.onNodeWithTag("phone-git-commit-button").assertIsNotEnabled()
        composeRule.onNodeWithTag("tablet-git-commit-more-button").assertIsNotEnabled()

        // Failure keeps user's draft text and shows Retry.
        controller.onFailed("Claude timed out")
        syncFromController()
        composeRule.waitForIdle()
        assertEquals("draft tablet commit", messageState)
        composeRule.onNodeWithTag("phone-git-ai-error").assertIsDisplayed()
        composeRule.onNodeWithTag("phone-git-ai-retry").performClick()
        composeRule.waitForIdle()

        // Retry succeeds -> Written state, chip label becomes "Write again" on tablet, never auto-commits.
        assertTrue(aiState is WriteWithAi.Written)
        assertEquals("fix(tablet): polish commit box", messageState)
        assertFalse(commitCount > 0)
        composeRule.onNodeWithTag("phone-git-ai-chip").assertTextContains("Write again")
        composeRule.onNodeWithTag("phone-git-commit-hint")
            .assertTextContains("Written from 2 staged files")
    }
}
