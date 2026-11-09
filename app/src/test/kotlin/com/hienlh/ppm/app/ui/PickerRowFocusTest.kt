package com.hienlh.ppm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.FocusAnchor
import com.hienlh.ppm.app.FocusStartAnchor
import com.hienlh.ppm.app.focusStartArea
import com.hienlh.ppm.app.leavesTextOnTap
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
        show {
            Box(Modifier.background(popover)) {
                ProviderChoiceBar("gemini", listOf(ProviderInfo("claude", "claude"), ProviderInfo("codex", "codex"), ProviderInfo("gemini", "gemini")), "", pick = {})
            }
        }

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

    @Test
    fun `a popover's start anchor holds the view's focus on start, lights nothing on a letter key, and steps aside once Tab enters the rows`() {
        var composeView: android.view.View? = null
        compose.setContent {
            val tokens = LocalPpmTokens.current
            val anchor = remember { FocusAnchor() }
            composeView = LocalView.current
            modes = LocalInputModeManager.current
            popover = tokens.panel2.over(tokens.bgSolid).color
            lit = tokens.surfaceHover.over(tokens.panel2.over(tokens.bgSolid)).color
            Box {
                FocusStartAnchor(anchor, parkOnStart = true)
                ModePanel(PermissionMode.BYPASS_PERMISSIONS, pick = {})
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread { modes!!.requestInputMode(InputMode.Keyboard) }
        assertEquals(true, composeView!!.hasFocus())
        assertFill("Ask before edits before any key", popover, "Ask before edits")

        compose.onRoot().performKeyInput { pressKey(Key.A) }
        compose.waitForIdle()
        assertFill("Ask before edits after a letter key", popover, "Ask before edits")

        tab()
        row("Ask before edits").assertIsFocused()
        assertFill("Ask before edits on first Tab", lit, "Ask before edits")

        // Tab through the remaining rows and wrap around: the anchor must not take a stop of its own.
        tab()
        row("Edit automatically").assertIsFocused()
        tab()
        row("Plan mode").assertIsFocused()
        tab()
        row("Bypass permissions").assertIsFocused()
        tab()
        row("Ask before edits").assertIsFocused()
    }

    @Test
    fun `a tap in a pane's body parks focus at the body's anchor so the first Tab starts in the body instead of scrolling the tab strip`() {
        var stripScroll = 0
        var composeView: android.view.View? = null
        compose.setContent {
            val focusManager = LocalFocusManager.current
            val rootAnchor = remember { FocusAnchor() }
            val panelAnchor = remember { FocusAnchor() }
            val bodyAnchor = remember { FocusAnchor() }
            val scroll = rememberScrollState(initial = 300)
            composeView = LocalView.current
            modes = LocalInputModeManager.current
            stripScroll = scroll.value
            Column(Modifier.leavesTextOnTap(focusManager, rootAnchor).focusStartArea(panelAnchor)) {
                FocusStartAnchor(rootAnchor, parkOnStart = true)
                FocusStartAnchor(panelAnchor)
                Row(Modifier.width(120.dp).horizontalScroll(scroll)) {
                    for (i in 1..6) {
                        Box(Modifier.size(80.dp, 32.dp).testTag("tab-$i").ppmClickable {})
                    }
                }
                Column(Modifier.size(200.dp).testTag("body").focusStartArea(bodyAnchor)) {
                    FocusStartAnchor(bodyAnchor)
                    Box(Modifier.size(80.dp, 32.dp).testTag("body-button").ppmClickable {})
                }
            }
        }
        compose.waitForIdle()
        assertEquals(300, stripScroll)

        compose.onNodeWithTag("body").performTouchInput { click() }
        compose.waitForIdle()
        assertEquals(true, composeView!!.hasFocus())
        assertEquals(300, stripScroll)

        compose.runOnUiThread { modes!!.requestInputMode(InputMode.Keyboard) }
        tab()
        compose.onNodeWithTag("body-button").assertIsFocused()
        assertEquals(300, stripScroll)
    }
}
