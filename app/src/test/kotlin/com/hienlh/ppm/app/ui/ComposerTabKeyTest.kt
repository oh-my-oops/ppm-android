package com.hienlh.ppm.app.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ChatTab
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tab in the message box. On the web only Shift+Tab is the page's (it cycles the permission mode),
 * so a plain Tab does what it does in any textarea: it leaves for the next control, the mic or
 * Stop, and puts no tab in the text. Compose's multi-line field types one, which left a keyboard
 * with no way out of the box.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposerTabKeyTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val tab = ChatTab("t", SessionInfo("s", "mock", "Chat"), "p", "mock")

    /** The keys [onKey] was asked about, in order, before the box did anything with them. */
    private val asked = mutableListOf<Key>()

    /** The box between two controls that take the focus, as the chips above it and the mic and Send beside it do. */
    private fun show(draft: String = "", onKey: (KeyEvent) -> Boolean = { false }) {
        tab.draft.setTextAndPlaceCursorAtEnd(draft)
        compose.setContent {
            val focus = remember { FocusRequester() }
            Column {
                Box(Modifier.size(24.dp).testTag(BEFORE).focusable())
                ComposerField(
                    tab, focus, remember { mutableStateOf(false) }, remember { mutableStateOf(false) },
                    onKey = { event ->
                        if (event.type == KeyEventType.KeyDown) asked += event.key
                        onKey(event)
                    },
                    send = {}, pasteImage = {}, fileDrop = ChatFileDrop(tab) { _, _ -> },
                    style = ppmText(14f, lineHeight = 14f * 1.625f), padding = 8.dp, cssStart = 0.dp, maxHeight = 90.dp,
                    placeholder = "Ask anything...", enabled = true,
                )
                Box(Modifier.size(24.dp).testTag(AFTER).focusable())
            }
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertIsFocused()
    }

    private fun press(key: Key, shift: Boolean = false) {
        compose.onRoot().performKeyInput {
            if (shift) withKeyDown(Key.ShiftLeft) { pressKey(key) } else pressKey(key)
        }
        compose.waitForIdle()
    }

    @Test
    fun `Tab leaves the box for the next control and types nothing`() {
        show(draft = "hello")

        press(Key.Tab)

        compose.onNodeWithTag(AFTER).assertIsFocused()
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        assertEquals("hello", tab.draft.text.toString())
    }

    @Test
    fun `Tab in an empty box types nothing either`() {
        show()

        press(Key.Tab)

        compose.onNodeWithTag(AFTER).assertIsFocused()
        assertEquals("", tab.draft.text.toString())
    }

    @Test
    fun `a Tab the box's own handler takes stays in the box, its key-up too`() {
        // An open picker's Tab, and Shift+Tab's mode change, are taken by that handler on the key-down
        // and left alone on the key-up, which then reaches the box with the focus still in it.
        show(draft = "/he", onKey = { event -> event.type == KeyEventType.KeyDown && event.key == Key.Tab })

        press(Key.Tab)
        press(Key.Tab, shift = true)

        assertEquals(listOf(Key.Tab, Key.Tab), asked.filter { it == Key.Tab })
        compose.onNode(hasSetTextAction()).assertIsFocused()
        assertEquals("/he", tab.draft.text.toString())
    }

    @Test
    fun `any other key stays in the box`() {
        show(draft = "hello")

        press(Key.A)
        press(Key.Spacebar)
        press(Key.DirectionLeft)

        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.onNodeWithTag(AFTER).assertIsNotFocused()
    }

    private companion object {
        const val BEFORE = "before"
        const val AFTER = "after"
    }
}
