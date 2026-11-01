package com.hienlh.ppm.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.LocalPpmTokens
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The colour of OK in the question an OSC 8 link asks. xterm asks it with the browser's own `confirm`, whose OK is the dialog's plain
 * default button; the app's confirmation draws a destructive one in the error colour (a Delete) and every other one in the accent, so
 * this question must be the second kind. Only pixels tell the two apart.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h400dp-xxhdpi")
class Osc8LinkConfirmTintTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val tokens = BuiltInThemes.auroraLight.tokens

    private fun distance(a: Color, b: Color) = maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))

    @Test
    fun `OK is painted in the accent and not in the red of a destructive confirmation`() {
        compose.setContent {
            CompositionLocalProvider(LocalPpmTokens provides tokens) {
                Osc8LinkConfirm("https://example.com/target", open = {}, onDismiss = {})
            }
        }
        assertTrue("the accent and the error colour differ", distance(tokens.accent.color, tokens.error.color) > 0.1f)
        val image = compose.onNodeWithText("OK").captureToImage().toPixelMap()
        // Left of the label and clear of the rounded corners: the button's own fill.
        val fill = image[12, image.height / 2]
        assertEquals(0f, distance(fill, tokens.accent.color), 0.02f)
    }
}
