package com.hienlh.ppm.app.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.GroupTab
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.state.GroupChat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tab in a field of several lines, which on the web is a textarea: it goes on to the next control
 * and Shift+Tab back to the one before, and neither puts a tab in the text. Compose's own field
 * types one. Every such field in the app is checked where it is drawn.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TabMovesFocusTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val scope = CoroutineScope(Job())

    @After
    fun stop() {
        scope.coroutineContext[Job]!!.cancel()
    }

    /** [field], focused, between two controls that take the focus, as the rest of a form is. */
    private fun show(field: @Composable () -> Unit) {
        compose.setContent {
            Column {
                Box(Modifier.size(24.dp).testTag(BEFORE).focusable())
                field()
                Box(Modifier.size(24.dp).testTag(AFTER).focusable())
            }
        }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).requestFocus()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertIsFocused()
    }

    private fun press(key: Key, shift: Boolean = false) {
        compose.onRoot().performKeyInput {
            if (shift) withKeyDown(Key.ShiftLeft) { pressKey(key) } else pressKey(key)
        }
        compose.waitForIdle()
    }

    /**
     * Tab moves on to what [next] checks, then Shift+Tab (from the box again) moves back, and the
     * text is as it was. By default the next control is the one after the field.
     */
    private fun assertTabsMoveTheFocus(draft: TextFieldState, next: () -> Unit = { compose.onNodeWithTag(AFTER).assertIsFocused() }) {
        press(Key.Tab)
        next()
        assertEquals("hello", draft.text.toString())

        compose.onNode(hasSetTextAction()).requestFocus()
        compose.waitForIdle()
        press(Key.Tab, shift = true)
        compose.onNodeWithTag(BEFORE).assertIsFocused()
        assertEquals("hello", draft.text.toString())
    }

    @Test
    fun `a field of several lines passes the focus on with Tab and back with Shift+Tab, typing nothing`() {
        val draft = TextFieldState().also { it.setTextAndPlaceCursorAtEnd("hello") }
        show { PpmTextField(draft, "Ask anything...", Modifier.size(200.dp, 80.dp), lineLimits = TextFieldLineLimits.MultiLine()) }
        assertTabsMoveTheFocus(draft)
    }

    @Test
    fun `a Tab the caller's own handler takes stays in the field`() {
        val draft = TextFieldState().also { it.setTextAndPlaceCursorAtEnd("hello") }
        var heard = 0
        show {
            PpmTextField(
                draft, "Ask anything...",
                Modifier.size(200.dp, 80.dp).onPreviewKeyEvent { event ->
                    (event.type == KeyEventType.KeyDown && event.key == Key.Tab).also { if (it) heard++ }
                },
                lineLimits = TextFieldLineLimits.MultiLine(),
            )
        }

        press(Key.Tab)

        assertEquals(1, heard)
        compose.onNode(hasSetTextAction()).assertIsFocused()
        assertEquals("hello", draft.text.toString())
    }

    @Test
    fun `the AI panel's textarea does the same`() {
        val draft = TextFieldState().also { it.setTextAndPlaceCursorAtEnd("hello") }
        show { FormTextArea(draft, "Enter additional instructions...") }
        assertTabsMoveTheFocus(draft)
    }

    @Test
    fun `the group chat's message box does the same`() {
        val chat = GroupChat(ApiClient("http://127.0.0.1:9".toHttpUrl(), ""), "p", "g", scope)
        val group = GroupTab(chat)
        group.draft.setTextAndPlaceCursorAtEnd("hello")
        show { GroupMessageBox(group, mobile = false) }
        // The Send button follows the box, and is the next control a Tab reaches, as on the web.
        assertTabsMoveTheFocus(group.draft, next = { compose.onNode(isFocused()).assertHasClickAction() })
    }

    private companion object {
        const val BEFORE = "before"
        const val AFTER = "after"
    }
}
