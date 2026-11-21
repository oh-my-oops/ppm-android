package com.hienlh.ppm.app.ui.phone.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.chat.ChatTranscriptView
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.CHROME_SELECTION_END_HANDLE
import com.hienlh.ppm.design.CHROME_SELECTION_START_HANDLE
import com.hienlh.ppm.design.LocalChromeSelectionMagnifier
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.ChatBlock
import com.hienlh.ppm.state.ChatItem
import com.hienlh.ppm.state.ChatState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An answer's markdown is drawn by ChromeText and selects where it is drawn (ChromeSelection.kt):
 * a long press selects a word with handles and the system's text toolbar, a handle drags the
 * selection on into the next paragraph, a press elsewhere or Back ends it, and a mouse selects by
 * dragging or double-clicking and copies with Ctrl+C. No dialog opens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h840dp-xhdpi")
class PhoneChatSelectionTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = com.hienlh.ppm.app.withComposeActivity(composeRule)

    private val first = "Alpha beta gamma"
    private val second = "Second paragraph here"
    private val answer = "$first\n\n$second"
    private val toolbar = RecordingToolbar()
    private var back: OnBackPressedDispatcher? = null

    /** The system's text toolbar, recording what it was asked to show. */
    private class RecordingToolbar : TextToolbar {
        var onCopy: (() -> Unit)? = null
        var onSelectAll: (() -> Unit)? = null
        var shows = 0

        override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?, onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
            onCopy = onCopyRequested
            onSelectAll = onSelectAllRequested
            shows++
        }

        override fun hide() {
            onCopy = null
            onSelectAll = null
        }

        override val status: TextToolbarStatus get() = if (onCopy != null) TextToolbarStatus.Shown else TextToolbarStatus.Hidden
    }

    private fun showTranscript(mobile: Boolean, markdown: String = answer) {
        val state = ChatState(
            sessionId = "s-1",
            hasLoadedHistory = true,
            items = listOf(
                ChatItem("u1", ChatItem.Role.USER, listOf(ChatBlock.Text("Hello from the user")), Instant.ofEpochSecond(1_700_000_000L)),
                ChatItem("a1", ChatItem.Role.ASSISTANT, listOf(ChatBlock.Text(markdown)), Instant.ofEpochSecond(1_700_000_005L)),
            ),
        )
        composeRule.setContent {
            back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                // Robolectric's stand-in for the platform Magnifier cannot copy the window's pixels.
                CompositionLocalProvider(LocalTextToolbar provides toolbar, LocalChromeSelectionMagnifier provides false) {
                    Box(Modifier.size(400.dp, 700.dp)) {
                        ChatTranscriptView(state, onRespond = { _, _, _ -> }, onLoadEarlier = {}, onReload = {})
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private val clipboard get() = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)

    private fun clip(): String? = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

    private fun paragraph(text: String) = composeRule.onNodeWithText(text, useUnmergedTree = true)

    private fun handlesShown() = composeRule.onAllNodesWithTag(CHROME_SELECTION_END_HANDLE).fetchSemanticsNodes().isNotEmpty()

    /** Long-presses the first word of the answer's first paragraph. */
    private fun longPressFirstWord() {
        paragraph(first).performTouchInput { longClick(Offset(4f, height / 2f)) }
        composeRule.waitForIdle()
    }

    /** Copies through the toolbar's Copy, as a tap on it does, and answers what reached the clipboard. */
    private fun copyFromToolbar(): String? {
        clipboard.setPrimaryClip(ClipData.newPlainText("", "nothing yet"))
        val copy = toolbar.onCopy
        assertNotNull("the text toolbar is not up", copy)
        composeRule.runOnUiThread { copy!!.invoke() }
        composeRule.waitForIdle()
        return clip()
    }

    @Test
    fun longPressOnAnswer_selectsTheWordInPlace_withHandlesAndToolbar_noDialog() {
        showTranscript(mobile = true)

        // A tap leaves the answer alone.
        paragraph(first).performClick()
        composeRule.waitForIdle()
        assertFalse(handlesShown())

        longPressFirstWord()
        assertEquals(0, composeRule.onAllNodesWithTag("select-text-dialog").fetchSemanticsNodes().size)
        composeRule.onNodeWithTag(CHROME_SELECTION_START_HANDLE).assertExists()
        composeRule.onNodeWithTag(CHROME_SELECTION_END_HANDLE).assertExists()
        assertNotNull("Select all is offered while part is selected", toolbar.onSelectAll)
        assertEquals("Alpha", copyFromToolbar())
        // Copy from the toolbar ends the selection, as a TextView's does.
        assertFalse(handlesShown())
        assertNull(toolbar.onCopy)
    }

    @Test
    fun draggingTheEndHandle_extendsTheSelectionIntoTheNextParagraph() {
        showTranscript(mobile = true)
        longPressFirstWord()

        val handle = composeRule.onNodeWithTag(CHROME_SELECTION_END_HANDLE).fetchSemanticsNode().boundsInRoot
        val below = paragraph(second).fetchSemanticsNode().boundsInRoot
        composeRule.onRoot().performTouchInput {
            down(handle.center)
            moveTo(Offset(below.right - 2f, below.center.y))
            moveTo(Offset(below.right - 2f, below.bottom + 80f))
            up()
        }
        composeRule.waitForIdle()
        assertEquals("$first\n$second", copyFromToolbar())
    }

    @Test
    fun selectAll_thenTappingOutside_clearsTheSelection() {
        showTranscript(mobile = true)
        longPressFirstWord()
        composeRule.runOnUiThread { toolbar.onSelectAll!!.invoke() }
        composeRule.waitForIdle()
        // Everything selected: Select all is not offered again.
        assertNull(toolbar.onSelectAll)
        assertNotNull(toolbar.onCopy)

        composeRule.onNodeWithText("Hello from the user", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        assertFalse(handlesShown())
        assertNull("the toolbar is hidden with the selection", toolbar.onCopy)
    }

    @Test
    fun aSelectionSpansHeadingsListItemsInlineCodeAndCodeBlocks_inDocumentOrder() {
        val markdown = "# Title here\n\nIntro line\n\n- item one\n- item `two`\n\n```\nval x = 1\n```\n\nOutro"
        showTranscript(mobile = true, markdown = markdown)
        paragraph("Intro line").performTouchInput { longClick(Offset(4f, height / 2f)) }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { toolbar.onSelectAll!!.invoke() }
        composeRule.waitForIdle()
        assertEquals("Title here\nIntro line\nitem one\nitem two\nval x = 1\nOutro", copyFromToolbar())
    }

    @Test
    fun holdingAHandleAtTheListsTopEdge_scrollsTheListAndTheSelectionFollows() {
        val paragraphs = (1..60).joinToString("\n\n") { "Paragraph $it" }
        showTranscript(mobile = true, markdown = paragraphs)
        // The list opens at its end: the last paragraph is on screen, the first ones are not.
        paragraph("Paragraph 60").performTouchInput { longClick(Offset(4f, height / 2f)) }
        composeRule.waitForIdle()
        val handle = composeRule.onNodeWithTag(CHROME_SELECTION_START_HANDLE).fetchSemanticsNode().boundsInRoot
        val list = composeRule.onNodeWithTag("chat-transcript").fetchSemanticsNode().boundsInRoot
        composeRule.onRoot().performTouchInput {
            down(handle.center)
            moveTo(Offset(handle.center.x, list.top + 2f))
        }
        composeRule.mainClock.advanceTimeBy(1_500)
        composeRule.onRoot().performTouchInput { up() }
        composeRule.waitForIdle()
        val copied = copyFromToolbar()!!
        // About 20 paragraphs fit on screen; the selection now starts well above them.
        val lines = copied.lines().size
        assertTrue("the list did not scroll the selection up: $lines paragraphs", lines >= 35)
        assertTrue(copied.endsWith("Paragraph 59\nParagraph"))
    }

    @Test
    fun back_clearsTheSelection() {
        showTranscript(mobile = true)
        longPressFirstWord()
        assertTrue(handlesShown())
        composeRule.runOnUiThread { back!!.onBackPressed() }
        composeRule.waitForIdle()
        assertFalse(handlesShown())
    }

    @Test
    fun tabletTranscript_sameComponent_longPressSelectsInPlace_andTheHandleExtendsIt() {
        showTranscript(mobile = false)
        longPressFirstWord()
        assertEquals(0, composeRule.onAllNodesWithTag("select-text-dialog").fetchSemanticsNodes().size)
        val handle = composeRule.onNodeWithTag(CHROME_SELECTION_END_HANDLE).fetchSemanticsNode().boundsInRoot
        val below = paragraph(second).fetchSemanticsNode().boundsInRoot
        composeRule.onRoot().performTouchInput {
            down(handle.center)
            moveTo(Offset(below.right - 2f, below.bottom + 80f))
            up()
        }
        composeRule.waitForIdle()
        assertEquals("$first\n$second", copyFromToolbar())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun tabletMouse_dragSelectsAcrossParagraphs_andCtrlCCopies() {
        showTranscript(mobile = false)
        clipboard.setPrimaryClip(ClipData.newPlainText("", "nothing yet"))
        val from = paragraph(first).fetchSemanticsNode().boundsInRoot
        val to = paragraph(second).fetchSemanticsNode().boundsInRoot
        composeRule.onRoot().performMouseInput {
            moveTo(Offset(from.left + 1f, from.center.y))
            press()
            moveTo(Offset(from.center.x, from.center.y))
            moveTo(Offset(to.right - 1f, to.bottom + 40f))
            release()
        }
        composeRule.waitForIdle()
        // A mouse selection has neither handles nor the toolbar.
        assertFalse(handlesShown())
        assertNull(toolbar.onCopy)
        composeRule.onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
        composeRule.waitForIdle()
        assertEquals("$first\n$second", clip())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun tabletMouse_doubleClickSelectsAWord() {
        showTranscript(mobile = false)
        clipboard.setPrimaryClip(ClipData.newPlainText("", "nothing yet"))
        paragraph(second).performMouseInput { doubleClick(Offset(4f, height / 2f)) }
        composeRule.waitForIdle()
        composeRule.onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
        composeRule.waitForIdle()
        assertEquals("Second", clip())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun userMessage_selectsInPlace_withoutTheDialog() {
        showTranscript(mobile = true)
        clipboard.setPrimaryClip(ClipData.newPlainText("", "nothing yet"))
        val nodes = composeRule.onAllNodesWithText("Hello from the user", substring = true, useUnmergedTree = true)
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
        assertEquals("Hello from the user", clip())
        assertEquals(0, composeRule.onAllNodesWithTag("select-text-dialog").fetchSemanticsNodes().size)
    }
}
