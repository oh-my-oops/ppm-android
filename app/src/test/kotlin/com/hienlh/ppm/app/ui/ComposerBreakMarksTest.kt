package com.hienlh.ppm.app.ui

import android.app.Application
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.ChatTab
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The message box lays its text out with invisible marks in it (U+200B and U+FEFF, [com.hienlh.ppm.chat.textareaBreakMark])
 * so that Android ends its lines where Chrome ends a textarea's. What is edited and told the IME holds none of them, but what
 * a copy or a cut takes, and what an accessibility service reads and does to the caret, is the text as laid out, so the
 * composer gives its field a clipboard and semantics that leave them out. These pin who gets to see the marks: the text a
 * send reads, what a copy or a cut puts on the clipboard (which the user pastes elsewhere), and what an accessibility service
 * reads of the field and does to its caret.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposerBreakMarksTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val tab = ChatTab("t", SessionInfo("s", "mock", "Chat"), "p", "mock")

    /** What [ChatTab.submit] would have read when the keyboard's action key was pressed. */
    private val sent = mutableListOf<String>()
    private var view: View? = null

    /** Text that gets both marks: a JOIN after each slash and a BREAK before the space that precedes a closing mark. */
    private val typed = "/skills see /home/user/dir/file.txt and https://example.com/a/b?c=d"

    private fun show(draft: String = "", enabled: Boolean = true) {
        tab.draft.setTextAndPlaceCursorAtEnd(draft)
        compose.setContent {
            view = LocalView.current
            val focus = remember { FocusRequester() }
            ComposerField(
                tab, focus, remember { mutableStateOf(false) }, remember { mutableStateOf(false) },
                onKey = { false },
                send = { sent += tab.draft.text.toString() }, pasteImage = {}, fileDrop = ChatFileDrop(tab) { _, _ -> },
                style = ppmText(14f, lineHeight = 14f * 1.625f), padding = 8.dp, cssStart = 0.dp, maxHeight = 90.dp,
                placeholder = "Ask anything...", enabled = enabled,
            )
        }
        compose.waitForIdle()
    }

    private fun clipboard(): String? = ApplicationProvider.getApplicationContext<Application>()
        .getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()

    private fun select(range: TextRange) {
        compose.onNode(hasSetTextAction()).performTextInputSelection(range)
        compose.waitForIdle()
    }

    @Test
    fun `what is typed is what a send reads, with no mark in it`() {
        show()

        compose.onNode(hasSetTextAction()).performTextInput(typed)
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()

        assertEquals(listOf(typed), sent)
    }

    @Test
    fun `a copy puts the text on the clipboard without the marks`() {
        show(typed)
        select(TextRange(0, typed.length))

        compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.CopyText)
        compose.waitForIdle()

        assertEquals(typed, clipboard())
    }

    @Test
    fun `a cut puts the part it took on the clipboard without the marks and leaves the rest`() {
        show(typed)
        // The first twelve characters, "/skills see ", hold a JOIN after the slash and a BREAK before the space after "see".
        select(TextRange(0, 12))

        compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.CutText)
        compose.waitForIdle()

        assertEquals("/skills see ", clipboard())
        assertEquals(typed.substring(12), tab.draft.text.toString())
    }

    /** The accessibility node of the field, found by its text so that a disabled field, which has no set-text action, is too. */
    private fun nodeId() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).fetchSemanticsNode().id

    private fun info() = view!!.accessibilityNodeProvider.createAccessibilityNodeInfo(nodeId())!!

    private fun act(action: Int, vararg arguments: Pair<String, Int>) = view!!.accessibilityNodeProvider.performAction(
        nodeId(), action, Bundle().apply { arguments.forEach { putInt(it.first, it.second) } },
    ).also { compose.waitForIdle() }

    private fun setSelection(start: Int, end: Int = start) = act(
        AccessibilityNodeInfo.ACTION_SET_SELECTION,
        AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT to start,
        AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT to end,
    )

    @Test
    fun `an accessibility service reads the typed text`() {
        show(typed)

        assertEquals(typed, info().text.toString())
    }

    @Test
    fun `an accessibility service reads text typed after the field was shown`() {
        show()

        compose.onNode(hasSetTextAction()).performTextInput(typed)

        assertEquals(typed, info().text.toString())
    }

    @Test
    fun `an accessibility service is told the caret at its place in the typed text`() {
        show(typed)

        assertEquals(typed.length, info().textSelectionStart)
        assertEquals(typed.length, info().textSelectionEnd)
    }

    @Test
    fun `an accessibility service is told a selected range at its place in the typed text`() {
        show(typed)
        select(TextRange(2, 6))

        assertEquals(2, info().textSelectionStart)
        assertEquals(6, info().textSelectionEnd)
    }

    @Test
    fun `a caret an accessibility service sets lands where it says in the typed text`() {
        show(typed)

        assertTrue(setSelection(3))

        assertEquals(TextRange(3), tab.draft.selection)
    }

    @Test
    fun `a range an accessibility service selects is that range of the typed text`() {
        show(typed)

        assertTrue(setSelection(2, 6))

        assertEquals(TextRange(2, 6), tab.draft.selection)
    }

    @Test
    fun `moving the caret a character at a time by accessibility steps over every character`() {
        show(typed)
        setSelection(0)

        val stops = (1..5).map {
            act(
                AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY,
                AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT to AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER,
            )
            tab.draft.selection.start
        }

        assertEquals(listOf(1, 2, 3, 4, 5), stops)
    }

    @Test
    fun `a caret or a range reaching outside the typed text is refused and the selection stays`() {
        show(typed)
        val outside = typed.length + 5 // Inside the text as laid out, which is longer than what was typed by its marks.

        assertFalse(setSelection(outside))
        assertFalse(setSelection(2, outside))
        assertFalse(setSelection(outside, 2))
        assertFalse(setSelection(-1, 2))

        assertEquals(TextRange(typed.length), tab.draft.selection)
    }

    /**
     * The action itself, called as Compose does: through the accessibility provider a disabled node is refused before
     * the action is reached (it answers true and moves nothing), so only a direct call sees the field's own check.
     */
    @Test
    fun `a disabled field refuses to set the caret`() {
        show(typed, enabled = false)
        val setSelection = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).fetchSemanticsNode()
            .config[SemanticsActions.SetSelection].action!!

        val done = compose.runOnUiThread { setSelection(3, 3, false) }

        assertFalse(done)
        assertEquals(TextRange(typed.length), tab.draft.selection)
    }
}
