package com.hienlh.ppm.app.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
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
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.protocol.ModelOption
import com.hienlh.ppm.protocol.ProviderInfo
import com.hienlh.ppm.state.PermissionMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The rows of the mode, provider and model pickers have `hover:bg-surface-elevated focus:bg-surface-elevated`
 * on the web, so the one a keyboard has reached is lit as the one a mouse is over. The web's colour is the
 * popover's own fill, which shows nothing, so the app lights both in the hover tint, as it does for the
 * mouse. Drawn for real (native graphics, 3 pixels to the dp), the colour of a row's padding read back, with
 * Tab moving the focus down the rows. The popups themselves cannot open here (Compose reads a string
 * resource for the window's title), so each test draws what the popup holds.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class PickerRowFocusTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private var modes: InputModeManager? = null
    private var popover = Color.Unspecified
    private var lit = Color.Unspecified

    private fun show(panel: @Composable () -> Unit) {
        compose.setContent {
            val tokens = LocalPpmTokens.current
            modes = LocalInputModeManager.current
            popover = tokens.panel2.over(tokens.bgSolid).color
            lit = tokens.surfaceHover.over(tokens.panel2.over(tokens.bgSolid)).color
            // What a keyboard Tabs in from: the box the picker is opened from.
            Column {
                Box(Modifier.size(24.dp).testTag("anchor").focusable())
                panel()
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

    /** The fill under a row's padding, left of where its content starts and clear of the box's border. */
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

    private fun tab() {
        compose.onRoot().performKeyInput { pressKey(Key.Tab) }
        compose.waitForIdle()
    }

    /** Tab to [first], then to [second]: each is lit while it has the focus, and plain once it has not. */
    private fun assertTheFocusedRowIsLit(first: String, second: String) {
        assertFill("$first before any focus", popover, first)
        assertFill("$second before any focus", popover, second)

        tab()
        assertFill("$first with the focus", lit, first)
        assertFill("$second without it", popover, second)

        tab()
        assertFill("$second with the focus", lit, second)
        assertFill("$first once the focus has left it", popover, first)
    }

    @Test
    fun `the mode row the focus is on is lit, and the one it leaves is not`() {
        show { ModePanel(PermissionMode.BYPASS_PERMISSIONS, pick = {}) }

        assertTheFocusedRowIsLit("Ask before edits", "Edit automatically")
    }

    @Test
    fun `the provider row the focus is on is lit, and the one it leaves is not`() {
        show { ProviderPanel("gemini", listOf(ProviderInfo("claude", "claude"), ProviderInfo("codex", "codex"), ProviderInfo("gemini", "gemini")), "", pick = {}) }

        assertTheFocusedRowIsLit("Claude", "Codex")
    }

    @Test
    fun `a control that does not ask to be lit on focus is not, with the focus on it`() {
        show { BasicText("Plain", Modifier.size(48.dp).ppmClickable(hoverColor = Color.Red) {}) }

        val before = fill("Plain")
        tab()
        row("Plain").assertIsFocused()
        assertFill("with the focus on it", before, "Plain")
    }

    @Test
    fun `the model row the focus is on is lit, and the one it leaves is not`() {
        show { ModelPanel(listOf(ModelOption("opus", "Claude Opus"), ModelOption("sonnet", "Claude Sonnet"), ModelOption("haiku", "Claude Haiku")), "haiku", null, pick = {}) }

        assertTheFocusedRowIsLit("Claude Opus", "Claude Sonnet")
    }
}
