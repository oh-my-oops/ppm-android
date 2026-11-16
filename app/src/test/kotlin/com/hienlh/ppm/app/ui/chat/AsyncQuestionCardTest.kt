package com.hienlh.ppm.app.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.chat.AsyncQuestionCard
import com.hienlh.ppm.chat.AsyncQuestions
import com.hienlh.ppm.chat.LocalAsyncQuestions
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.ChatQuestions
import com.hienlh.ppm.protocol.QueuedMessage
import com.hienlh.ppm.protocol.Question
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A question Codex asked mid-turn (`async-question-card.test.tsx`): once answered it is closed at
 * once, stays closed when the turn's events regroup and the card is drawn again, and is closed
 * while its reply waits in the session's queue, so the same question is never answered twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class AsyncQuestionCardTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val questions = listOf(
        Question("Which database?", null, listOf(Question.Option("Postgres", null), Question.Option("SQLite", null)), multiSelect = false, id = "db"),
    )

    private fun noneShown(text: String) = composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()

    @Test
    fun theAnswerShowsAsSentAtOnce_whileTheReplyWaitsToReachTheConversation() {
        val sent = mutableListOf<String>()
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                CompositionLocalProvider(LocalAsyncQuestions provides AsyncQuestions(emptyList(), emptyList()) { sent += it }) {
                    AsyncQuestionCard("ask-pending", questions)
                }
            }
        }
        composeRule.onNodeWithText("Skip").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(ChatQuestions.formatReply("ask-pending", questions, null)), sent)
        composeRule.onNodeWithText("Response sent").assertExists()
        assertTrue("the form is gone", noneShown("Skip"))
    }

    @Test
    fun itStaysClosedWhenDrawnAgainBeforeTheReplyReachesTheConversation() {
        val sent = mutableListOf<String>()
        var shown by mutableStateOf(true)
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                CompositionLocalProvider(LocalAsyncQuestions provides AsyncQuestions(emptyList(), emptyList()) { sent += it }) {
                    if (shown) AsyncQuestionCard("ask-remount", questions)
                }
            }
        }
        composeRule.onNodeWithText("Skip").performClick()
        composeRule.runOnIdle { shown = false }
        composeRule.runOnIdle { shown = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Response sent").assertExists()
        assertTrue("the form is gone", noneShown("Skip"))
        assertEquals(1, sent.size)
    }

    @Test
    fun itIsClosedWhileItsReplyWaitsInTheQueue() {
        val reply = ChatQuestions.formatReply("ask-queued", questions, mapOf("db" to "SQLite"))
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                CompositionLocalProvider(LocalAsyncQuestions provides AsyncQuestions(emptyList(), listOf(QueuedMessage("q1", reply, 0))) {}) {
                    AsyncQuestionCard("ask-queued", questions)
                }
            }
        }
        composeRule.onNodeWithText("Response sent").assertExists()
        composeRule.onNodeWithText("SQLite").assertExists()
        assertTrue("the form is gone", noneShown("Skip"))
    }
}
