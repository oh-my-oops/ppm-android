package com.hienlh.ppm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.protocol.ModelOption
import com.hienlh.ppm.state.Effort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The composer's model picker (`ModelThinkingSelector`): its model rows, its Effort rows and its Thinking switch take the
 * keyboard as the mode and provider pickers' rows do, and show where it is: Tab and the arrow keys move over them and the
 * row the focus is on is lit in the hover tint, as a mouse over it lights it. The web's rows only have the hover, which
 * leaves a keyboard with no sign of where it is in this picker, so this is a deliberate difference. Drawn for real (native
 * graphics, 3 pixels to the dp) with the focus moved by keys, the fill under a row's padding read back. The popover cannot
 * open here (Compose reads a string resource for the window's title), so the test draws what it holds; the window's own
 * flags are [PopupWindowFocusTest].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class ComposerModelPickerFocusTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private var modes: InputModeManager? = null

    /** The popover's own fill, the fill of a row the picker has chosen, and each of them with the hover tint over it. */
    private var popover = Color.Unspecified
    private var chosen = Color.Unspecified
    private var lit = Color.Unspecified
    private var litChosen = Color.Unspecified
    private var accent = Color.Unspecified

    private val models = mutableListOf<String>()
    private val efforts = mutableListOf<Effort>()
    private val thinking = mutableListOf<Boolean>()

    private fun show(on: Boolean = false) {
        compose.setContent {
            val tokens = LocalPpmTokens.current
            modes = LocalInputModeManager.current
            popover = tokens.panel2.over(tokens.bgSolid).color
            chosen = tokens.panel2.over(tokens.panel2.over(tokens.bgSolid)).color
            lit = tokens.surfaceHover.over(tokens.panel2.over(tokens.bgSolid)).color
            litChosen = tokens.surfaceHover.over(tokens.panel2.over(tokens.panel2.over(tokens.bgSolid))).color
            accent = tokens.accent.color
            // What a keyboard Tabs in from: the chip the picker is opened from.
            Column {
                Box(Modifier.size(24.dp).testTag("anchor").focusable())
                ModelPickerColumns(
                    models = listOf(ModelOption("opus", "Claude Opus"), ModelOption("sonnet", "Claude Sonnet"), ModelOption("haiku", "Claude Haiku")),
                    status = null, model = "haiku", effort = Effort.HIGH, thinking = on,
                    box = Modifier.width(320.dp).background(popover), inner = Modifier,
                    onModel = { models += it }, onEffort = { efforts += it }, onThinking = { thinking += it },
                )
            }
        }
        compose.waitForIdle()
        // A device is in touch mode until a keyboard's first key takes it out, and a clickable row cannot hold the focus before that.
        compose.runOnUiThread { modes!!.requestInputMode(InputMode.Keyboard) }
        compose.onNodeWithTag("anchor").requestFocus()
        compose.waitForIdle()
        compose.onNodeWithTag("anchor").assertIsFocused()
    }

    private fun row(label: String) = compose.onNode(hasClickAction() and hasText(label))

    private fun fill(label: String): Color {
        val image = row(label).captureToImage().toPixelMap()
        return image[15, image.height / 2]
    }

    private fun assertFill(message: String, expected: Color, label: String) {
        val at = fill(label)
        for ((name, want, got) in listOf(Triple("red", expected.red, at.red), Triple("green", expected.green, at.green), Triple("blue", expected.blue, at.blue))) {
            assertEquals(message + ", " + name, want, got, 0.02f)
        }
    }

    private fun press(key: Key, times: Int = 1) {
        repeat(times) { compose.onRoot().performKeyInput { pressKey(key) } }
        compose.waitForIdle()
    }

    @Test
    fun `the model row the focus is on is lit, and the one it leaves is not`() {
        show()
        assertFill("Opus before any focus", popover, "Opus")

        press(Key.Tab)
        row("Opus").assertIsFocused()
        assertFill("Opus with the focus", lit, "Opus")
        assertFill("Sonnet without it", popover, "Sonnet")

        press(Key.Tab)
        assertFill("Sonnet with the focus", lit, "Sonnet")
        assertFill("Opus once the focus has left it", popover, "Opus")
    }

    @Test
    fun `the chosen model and the chosen effort end in a check, and no other row does`() {
        show()
        for (label in listOf("Haiku", "High")) assertTrue("$label has no check", row(label).endsInAccent(accent))
        for (label in listOf("Opus", "Sonnet", "Low", "Medium", "Extra", "Max")) {
            assertFalse("$label has a check", row(label).endsInAccent(accent))
        }
    }

    @Test
    fun `the chosen model keeps its fill under the focus's tint, and gets it back plain when the focus goes`() {
        show()
        assertFill("Haiku, the chosen one, before any focus", chosen, "Haiku")

        press(Key.Tab, times = 3)
        row("Haiku").assertIsFocused()
        assertFill("Haiku with the focus", litChosen, "Haiku")

        press(Key.Tab)
        assertFill("Haiku once the focus has left it", chosen, "Haiku")
    }

    @Test
    fun `the chosen effort keeps its fill under the focus's tint, and the others stay plain`() {
        show()
        assertFill("High, the chosen one, before any focus", chosen, "High")
        assertFill("Medium, one that is not", popover, "Medium")

        press(Key.Tab, times = 6)
        row("High").assertIsFocused()
        assertFill("High with the focus", litChosen, "High")
    }

    @Test
    fun `tab goes on from the last model to the first Effort row, and lights it`() {
        show()
        press(Key.Tab, times = 4)
        row("Low").assertIsFocused()
        assertFill("Low with the focus", lit, "Low")
        assertFill("Haiku, the model it came from", chosen, "Haiku")
    }

    @Test
    fun `the arrow keys move over the Effort rows, lighting the one they reach`() {
        show()
        press(Key.Tab, times = 4)

        press(Key.DirectionDown)
        row("Medium").assertIsFocused()
        assertFill("Medium with the focus", lit, "Medium")
        assertFill("Low once the focus has left it", popover, "Low")

        press(Key.DirectionUp)
        row("Low").assertIsFocused()
        assertFill("Low with the focus again", lit, "Low")
        assertFill("Medium without it", popover, "Medium")
    }

    @Test
    fun `the Thinking switch is lit while the focus is on it, and the last Effort row is not`() {
        show()
        press(Key.Tab, times = 9)
        row("Thinking").assertIsFocused()
        assertFill("Thinking with the focus", lit, "Thinking")
        assertFill("Max, the Effort row above it", popover, "Max")
    }

    @Test
    fun `Enter on a row the keyboard reached picks it`() {
        show()
        press(Key.Tab, times = 2)
        press(Key.Enter)
        assertEquals(listOf("sonnet"), models)

        press(Key.Tab, times = 3)
        row("Medium").assertIsFocused()
        press(Key.Enter)
        assertEquals(listOf(Effort.MEDIUM), efforts)
    }

    @Test
    fun `Enter on the Thinking switch while it is off asks for it on`() {
        show(on = false)
        press(Key.Tab, times = 9)
        press(Key.Enter)
        assertEquals(listOf(true), thinking)
    }

    @Test
    fun `Enter on the Thinking switch while it is on asks for it off`() {
        show(on = true)
        press(Key.Tab, times = 9)
        press(Key.Enter)
        assertEquals(listOf(false), thinking)
    }
}
