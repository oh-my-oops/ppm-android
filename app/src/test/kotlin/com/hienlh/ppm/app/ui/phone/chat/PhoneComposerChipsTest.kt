package com.hienlh.ppm.app.ui.phone.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasContentDescription
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
    fun theBarHoldsPlusMicModelAndASmallSend_andThePermissionModeMovedIntoThePlusSheet() {
        var modelSheet = 0
        var attachSheet = 0
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneChatScreen(
                    composerState = TextFieldState("hi"),
                    modelLabel = "Sonnet 5.5",
                    effortLabel = "High",
                    permissionModeId = "bypassPermissions",
                    onOpenAttachSheet = { attachSheet++ },
                    onToggleVoiceRecording = {},
                    onSendMessage = {},
                    onStopTurn = {},
                    onScrollToWaitingCard = null,
                    onOpenModelEffortSheet = { modelSheet++ },
                )
            }
        }

        val model = composeRule.onNodeWithText("Sonnet 5.5").assertIsDisplayed().getBoundsInRoot()
        val send = composeRule.onNodeWithContentDescription("Send message").assertIsDisplayed().getBoundsInRoot()
        assertTrue("model chip shares Send's row", model.top < send.bottom && model.bottom > send.top)
        assertTrue("model chip before Send", model.right <= send.left)

        // As Claude's app: no permission chip in the bar; the + says when Bypass is on.
        assertTrue(composeRule.onAllNodes(hasContentDescription("Permission mode", substring = true)).fetchSemanticsNodes().isEmpty())
        composeRule.onNode(hasContentDescription("Attach") and hasStateDescription("Bypass permissions on")).assertIsDisplayed()

        // Send is drawn as small as + and the mic (36 dp), with the same 48 dp touch target.
        val circle = composeRule.onNodeWithTag("phone-send-circle", useUnmergedTree = true).getBoundsInRoot()
        assertEquals(36f, (circle.right - circle.left).value, 0.5f)
        assertEquals(48f, (send.right - send.left).value, 0.5f)

        composeRule.onNodeWithText("Sonnet 5.5").performClick()
        composeRule.onNodeWithContentDescription("Attach").performClick()
        assertEquals(1, modelSheet)
        assertEquals(1, attachSheet)
    }

    @Test
    fun thePlusSheetIsAddToChat_withCameraPhotosFiles_andThePermissionModeRow() {
        var modeOpened = 0
        var dismissed = 0
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneAttachSheet(
                    onPickPhotos = {},
                    onOpenCamera = {},
                    onPickFiles = {},
                    onDismiss = { dismissed++ },
                    permissionModeLabel = "Bypass",
                    permissionModeAlert = true,
                    onOpenPermissionMode = { modeOpened++ },
                )
            }
        }
        composeRule.onNodeWithText("Add to Chat").assertIsDisplayed()
        val camera = composeRule.onNodeWithText("Camera").getBoundsInRoot()
        val photos = composeRule.onNodeWithText("Photos").getBoundsInRoot()
        val files = composeRule.onNodeWithText("Files").getBoundsInRoot()
        assertTrue("Camera, Photos, Files in that order", camera.right <= photos.left && photos.right <= files.left)

        composeRule.onNodeWithText("Permission mode").assertIsDisplayed()
        composeRule.onNodeWithText("Bypass").assertIsDisplayed()
        composeRule.onNodeWithTag("phone-attach-permission-mode").performClick()
        assertEquals(1, modeOpened)
        assertEquals(1, dismissed)
    }

    @Test
    fun longTextWrapsAndTheComposerGrows() {
        val state = TextFieldState("")
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneChatScreen(
                    composerState = state,
                    modelLabel = "Sonnet 5.5",
                    onSendMessage = {},
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
