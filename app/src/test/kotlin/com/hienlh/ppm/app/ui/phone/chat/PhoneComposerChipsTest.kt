package com.hienlh.ppm.app.ui.phone.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The model and permission-mode chips sit in the composer's own bar, beside Send, as on the web. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp")
class PhoneComposerChipsTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val rules: org.junit.rules.RuleChain = com.hienlh.ppm.app.withComposeActivity(composeRule)

    @Test
    fun modelAndModeChips_sitInTheComposerBarBesideSend_fitAPhone_andOpenTheirSheets() {
        var modelSheet = 0
        var modesSheet = 0
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneChatScreen(
                    composerState = TextFieldState(""),
                    modelLabel = "Sonnet 5.5",
                    effortLabel = "High",
                    permissionModeId = "bypassPermissions",
                    permissionModeLabel = "Bypass",
                    onOpenAttachSheet = {},
                    onToggleVoiceRecording = {},
                    onSendMessage = { _, _ -> },
                    onStopTurn = {},
                    onScrollToWaitingCard = null,
                    onOpenModelEffortSheet = { modelSheet++ },
                    onOpenModesSheet = { modesSheet++ },
                )
            }
        }

        val model = composeRule.onNodeWithText("Sonnet 5.5").assertIsDisplayed().getBoundsInRoot()
        val mode = composeRule.onNodeWithContentDescription("Permission mode: Bypass").assertIsDisplayed().getBoundsInRoot()
        val send = composeRule.onNodeWithContentDescription("Send message").getBoundsInRoot()

        // One row with Send: same vertical centre band, model before mode before Send, nothing past Send.
        assertTrue("model chip shares Send's row", model.top < send.bottom && model.bottom > send.top)
        assertTrue("mode chip shares Send's row", mode.top < send.bottom && mode.bottom > send.top)
        assertTrue("model, then mode", model.right <= mode.left)
        assertTrue("the mode chip is not cut by Send", mode.right <= send.left)
        assertTrue("the mode chip is icon-only", mode.right - mode.left < 60.dp)

        composeRule.onNodeWithText("Sonnet 5.5").performClick()
        composeRule.onNodeWithContentDescription("Permission mode: Bypass").performClick()
        assertEquals(1, modelSheet)
        assertEquals(1, modesSheet)
    }

    @Test
    fun longTextWrapsAndTheComposerGrows() {
        val state = TextFieldState("")
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneChatScreen(
                    composerState = state,
                    modelLabel = "Sonnet 5.5",
                    onSendMessage = { _, _ -> },
                    onStopTurn = {},
                    onScrollToWaitingCard = null,
                )
            }
        }
        val field = composeRule.onNode(hasSetTextAction())
        val oneLine = field.getBoundsInRoot().let { it.bottom - it.top }
        // Robolectric does not measure glyph widths, so a long line never wraps here (it does on a device);
        // lines a single-line field would keep on one line show the same difference.
        composeRule.runOnIdle {
            state.setTextAndPlaceCursorAtEnd("first line\nsecond line\nthird line")
        }
        val wrapped = field.getBoundsInRoot().let { it.bottom - it.top }
        assertTrue("the field grows when the text wraps ($oneLine -> $wrapped)", wrapped > oneLine + 10.dp)
    }
}
