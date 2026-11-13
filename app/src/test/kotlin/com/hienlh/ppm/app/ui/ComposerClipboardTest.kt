package com.hienlh.ppm.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.AndroidClipboard
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.junit4.createComposeRule
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.chat.textareaClipboard
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The composer's clipboard strips the layout marks from a copy, and is still an [AndroidClipboard]: a text field's
 * context menu (a long press, a right click) asks the clipboard for Android's own manager to see whether there is
 * something to paste, and Compose throws on any other kind, which closed the app on the tablet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposerClipboardTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    @Test
    fun theComposersClipboardHandsOverAndroidsManager() {
        var original: Clipboard? = null
        var provided: Clipboard? = null
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                original = LocalClipboard.current
                CompositionLocalProvider(textareaClipboard()) {
                    provided = LocalClipboard.current
                }
            }
        }
        composeRule.waitForIdle()

        val clipboard = provided
        assertTrue("the composer's clipboard is an AndroidClipboard: $clipboard", clipboard is AndroidClipboard)
        assertSame((original as AndroidClipboard).clipboardManager, (clipboard as AndroidClipboard).clipboardManager)
    }
}
