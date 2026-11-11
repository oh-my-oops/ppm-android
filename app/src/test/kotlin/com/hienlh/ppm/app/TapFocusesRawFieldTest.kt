package com.hienlh.ppm.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A raw `BasicTextField` under `Root`'s [leavesTextOnTap] keeps the focus its tap gave it. Without
 * [tracksTextFocus] / [asksForTextFocus] the root cleared it right after the tap: the phone's Search, Go to file,
 * commit box and friends showed the keyboard and typed into nothing (measured on the emulator).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class TapFocusesRawFieldTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val state = TextFieldState()

    @After
    fun release() {
        TextInputFocus.release(state)
    }

    private fun show() {
        composeRule.setContent {
            val focusManager = LocalFocusManager.current
            var value by remember { mutableStateOf("") }
            Column(Modifier.fillMaxSize().leavesTextOnTap(focusManager)) {
                BasicTextField(state = state, modifier = Modifier.fillMaxWidth().tracksTextFocus(state).testTag("state-field"))
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth().asksForTextFocus().testTag("value-field"),
                )
            }
        }
    }

    @Test
    fun `a tap on a state field under the tap-to-blur root leaves it focused`() {
        show()
        composeRule.onNodeWithTag("state-field").performTouchInput { click() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("state-field").assertIsFocused()
        // Robolectric's tap lands the state field's own focus request after the root's clear, so focus alone does not
        // show the bug here; on a device it did. What keeps it focused there is the root knowing the field is the one.
        composeRule.runOnIdle { assertSame(state, TextInputFocus.field) }
    }

    @Test
    fun `a tap on a value field under the tap-to-blur root leaves it focused`() {
        show()
        composeRule.onNodeWithTag("value-field").performTouchInput { click() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("value-field").assertIsFocused()
    }
}
