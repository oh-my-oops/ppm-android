package com.hienlh.ppm.app.ui.osexplorer

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.state.osexplorer.HostKey
import com.hienlh.ppm.state.osexplorer.HostKeyPress
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** How the keys the explorer listens for reach its key table: real key events from the test input, read by [hostKeyPress]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h400dp")
class HostExplorerKeyboardTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val presses = mutableListOf<HostKeyPress>()

    private fun listening() {
        val focus = FocusRequester()
        compose.setContent {
            Box(Modifier.size(100.dp).testTag("keys").focusRequester(focus).onKeyEvent {
                if (it.type == KeyEventType.KeyDown) presses += hostKeyPress(it)
                true
            }.focusable())
            androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
        }
        compose.waitForIdle()
    }

    private fun keys() = compose.onNodeWithTag("keys")

    private fun press(key: Key) {
        presses.clear()
        keys().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun `each key the explorer reads arrives as its own, and types no character`() {
        listening()
        val named = listOf(
            Key.DirectionUp to HostKey.UP, Key.DirectionDown to HostKey.DOWN, Key.DirectionLeft to HostKey.LEFT,
            Key.DirectionRight to HostKey.RIGHT, Key.MoveHome to HostKey.HOME, Key.MoveEnd to HostKey.END,
            Key.Backspace to HostKey.BACKSPACE, Key.Enter to HostKey.ENTER, Key.NumPadEnter to HostKey.ENTER,
            Key.F2 to HostKey.F2, Key.Delete to HostKey.DELETE, Key.Escape to HostKey.ESCAPE,
        )
        for ((key, expected) in named) {
            press(key)
            assertEquals("$key", listOf(HostKeyPress(expected)), presses.toList())
        }
    }

    @Test
    fun `a letter arrives with the character it types, and the four shortcut letters with their own names`() {
        listening()
        press(Key.B)
        assertEquals(listOf(HostKeyPress(HostKey.OTHER, 'b')), presses.toList())
        press(Key.A)
        assertEquals(listOf(HostKeyPress(HostKey.A, 'a')), presses.toList())
        press(Key.C)
        assertEquals(HostKey.C, presses.single().key)
        press(Key.X)
        assertEquals(HostKey.X, presses.single().key)
        press(Key.V)
        assertEquals(HostKey.V, presses.single().key)
    }

    @Test
    fun `Control, Command, Shift and Alt arrive as modifiers, and Command counts as Control`() {
        listening()
        keys().performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.C); keyUp(Key.CtrlLeft) }
        assertEquals(HostKeyPress(HostKey.C, presses.last().char, ctrl = true), presses.last())
        presses.clear()
        keys().performKeyInput { keyDown(Key.MetaLeft); pressKey(Key.V); keyUp(Key.MetaLeft) }
        assertEquals(HostKeyPress(HostKey.V, presses.last().char, ctrl = true), presses.last())
        presses.clear()
        keys().performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.Delete); keyUp(Key.ShiftLeft) }
        assertEquals(HostKeyPress(HostKey.DELETE, shift = true), presses.last())
        presses.clear()
        keys().performKeyInput { keyDown(Key.AltLeft); pressKey(Key.DirectionLeft); keyUp(Key.AltLeft) }
        assertEquals(HostKeyPress(HostKey.LEFT, alt = true), presses.last())
    }
}
