package com.hienlh.ppm.app.ui.phone.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.chat.ChatTranscriptView
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.ChatBlock
import com.hienlh.ppm.state.ChatItem
import com.hienlh.ppm.state.ChatState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An answer's markdown is drawn by ChromeText, which a SelectionContainer cannot select: a long
 * press on it opens the block in a dialog where the same text is BasicText and selects natively.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h840dp-xhdpi")
class PhoneChatSelectionTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = com.hienlh.ppm.app.withComposeActivity(composeRule)

    private val answer = "Answer text to **copy** with `code`"

    private fun showTranscript(mobile: Boolean) {
        val state = ChatState(
            sessionId = "s-1",
            hasLoadedHistory = true,
            items = listOf(
                ChatItem("u1", ChatItem.Role.USER, listOf(ChatBlock.Text("Hello from the user")), Instant.ofEpochSecond(1_700_000_000L)),
                ChatItem("a1", ChatItem.Role.ASSISTANT, listOf(ChatBlock.Text(answer)), Instant.ofEpochSecond(1_700_000_005L)),
            ),
        )
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                Box(Modifier.size(400.dp, 700.dp)) {
                    ChatTranscriptView(state, onRespond = { _, _, _ -> }, onLoadEarlier = {}, onReload = {})
                }
            }
        }
        composeRule.waitForIdle()
    }

    private val clipboard get() = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)

    /**
     * Selects the last node showing [text] from its start to its end with a mouse drag (a touch
     * selection raises handles, whose popup needs Android resources a unit test does not carry),
     * copies it with Ctrl+C, and answers what reached the clipboard.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun dragSelectAndCopy(text: String): String? {
        clipboard.setPrimaryClip(ClipData.newPlainText("", "nothing yet"))
        val nodes = composeRule.onAllNodesWithText(text, substring = true, useUnmergedTree = true)
        val node = nodes[nodes.fetchSemanticsNodes().lastIndex]
        node.performMouseInput {
            moveTo(Offset(1f, height / 2f))
            press()
            moveTo(Offset(width - 1f, height / 2f))
            release()
        }
        composeRule.waitForIdle()
        node.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
        composeRule.waitForIdle()
        return clipboard.primaryClip?.getItemAt(0)?.text?.toString()
    }

    @Test
    fun longPressOnAnswer_opensSelectableText_whoseTextSelectsNatively_andCopyAllCopiesTheBlock() {
        showTranscript(mobile = true)

        // A tap leaves the answer alone; a long press opens it selectable.
        composeRule.onNodeWithText("Answer text to", substring = true, useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithTag("select-text-dialog").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("Answer text to", substring = true, useUnmergedTree = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("select-text-dialog").assertExists()

        // Copy all puts the block's text on the clipboard.
        composeRule.onNodeWithText("Copy all", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        assertEquals(answer, clipboard.primaryClip?.getItemAt(0)?.text?.toString())

        // In the dialog the same text is BasicText in a SelectionContainer: part of it selects and copies.
        val copied = dragSelectAndCopy("Answer text to")
        assertTrue("the dialog's text did not select: $copied", copied != null && copied.startsWith("Answer text to copy"))
    }

    @Test
    fun tabletTranscript_sameComponent_longPressOnAnswerOpensSelectableText() {
        showTranscript(mobile = false)
        composeRule.onNodeWithText("Answer text to", substring = true, useUnmergedTree = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("select-text-dialog").assertExists()
    }

    @Test
    fun userMessage_selectsInPlace_withoutTheDialog() {
        showTranscript(mobile = true)
        assertEquals("Hello from the user", dragSelectAndCopy("Hello from the user"))
        assertEquals(0, composeRule.onAllNodesWithTag("select-text-dialog").fetchSemanticsNodes().size)
    }
}
