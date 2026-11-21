package com.hienlh.ppm.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.LightboxRequest
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.GalleryImage
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.design.ViewerImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max

/**
 * A screenshot of the whole screen opened in the lightbox (the tablet report: "too big, over the close button, I had
 * to zoom out to see it"): it opens fitted between the close button's row and the toolbar, keeping its shape, at
 * "100%"; and at any zoom the close button and the toolbar stay clear of it and take a tap. Phone and tablet, both
 * orientations, the picture always the screen's own size in pixels.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ImageLightboxFitTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private var closed = false

    @Test
    @Config(qualifiers = "w400dp-h800dp-xxhdpi")
    fun phonePortrait_aScreenshotOpensFittedBetweenCloseAndToolbar() = opensFitted(mobile = true, 1200, 2400)

    @Test
    @Config(qualifiers = "w800dp-h400dp-xxhdpi")
    fun phoneLandscape_aScreenshotOpensFittedBetweenCloseAndToolbar() = opensFitted(mobile = true, 2400, 1200)

    @Test
    @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    fun tabletPortrait_aScreenshotOpensFittedBetweenCloseAndToolbar() = opensFitted(mobile = false, 1600, 2560)

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun tabletLandscape_aScreenshotOpensFittedBetweenCloseAndToolbar() = opensFitted(mobile = false, 2560, 1600)

    @Test
    @Config(qualifiers = "w400dp-h800dp-xxhdpi")
    fun phone_zoomedIn_closeAndToolbarStayClearAndTappable() = staysClearWhenZoomed(mobile = true, 1200, 2400)

    @Test
    @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    fun tablet_zoomedIn_closeAndToolbarStayClearAndTappable() = staysClearWhenZoomed(mobile = false, 1600, 2560)

    @Test
    @Config(qualifiers = "w400dp-h800dp-xxhdpi")
    fun phone_aTapOnTheCloseButtonsRowCloses_asOneOnTheDarkAroundThePicture() {
        open(mobile = true, 1200, 2400)
        // The middle of the row, clear of the button at its end.
        composeRule.onRoot().performTouchInput { click(Offset(centerX, 30.dp.toPx())) }
        composeRule.waitForIdle()
        assertTrue("a tap on the row above the picture did not close", closed)
    }

    private fun opensFitted(mobile: Boolean, width: Int, height: Int) {
        open(mobile, width, height)
        val image = imageBounds()
        val close = bounds("Close")
        val toolbarTop = toolbarTop()
        val screen = composeRule.onRoot().getBoundsInRoot()

        assertTrue("picture top ${image.top} is under the close button (bottom ${close.bottom})", image.top >= close.bottom)
        assertTrue("picture bottom ${image.bottom} reaches the toolbar (top $toolbarTop)", image.bottom <= toolbarTop)
        assertTrue("picture is wider than the screen: $image in $screen", image.left >= screen.left && image.right <= screen.right)
        // Its own shape: a picture cut by the stage's edge would not keep it.
        val ratio = width.toFloat() / height
        val shown = (image.right - image.left).value / (image.bottom - image.top).value
        assertTrue("picture drawn at $shown, file is $ratio", abs(shown - ratio) / ratio < 0.02f)
        // Fitted: as large as the room lets it be, along one side.
        val room = max((image.bottom - image.top).value / (toolbarTop - close.bottom).value, (image.right - image.left).value / (screen.right - screen.left).value)
        assertTrue("picture fills only $room of the room it has", room > 0.85f)
        composeRule.onNodeWithText("100%").assertExists()

        composeRule.onNodeWithContentDescription("Close").assertHasClickAction().performTouchInput { click() }
        composeRule.waitForIdle()
        assertTrue("the close button did not close the lightbox", closed)
    }

    private fun staysClearWhenZoomed(mobile: Boolean, width: Int, height: Int) {
        open(mobile, width, height)
        repeat(6) { composeRule.onNodeWithContentDescription("Zoom in (+)").performTouchInput { click() } }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("381%").assertExists()
        val image = imageBounds()
        assertTrue("zoomed picture top ${image.top} is under the close button", image.top >= bounds("Close").bottom)
        assertTrue("zoomed picture reaches the toolbar", image.bottom <= toolbarTop())

        composeRule.onNodeWithContentDescription("Zoom out (-)").performTouchInput { click() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("305%").assertExists()
        composeRule.onNodeWithContentDescription("Close").performTouchInput { click() }
        composeRule.waitForIdle()
        assertTrue("the close button did not close the zoomed lightbox", closed)
    }

    private fun open(mobile: Boolean, width: Int, height: Int) {
        val bytes = png(width, height)
        val request = LightboxRequest(listOf(GalleryImage("shot", NAME) { ViewerImage(NAME, bytes, "image/png") }), 0)
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                ImageLightbox(request, onClose = { closed = true }, onDownload = {}, onCopy = { false })
            }
        }
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithContentDescription(NAME).fetchSemanticsNodes().isNotEmpty() }
        composeRule.waitForIdle()
        assertEquals(false, closed)
    }

    private fun imageBounds() = composeRule.onNodeWithContentDescription(NAME).getBoundsInRoot()

    private fun bounds(label: String) = composeRule.onNodeWithContentDescription(label).getBoundsInRoot()

    /** The top of the toolbar's buttons, the highest of them should the row wrap. */
    private fun toolbarTop(): Dp = TOOLBAR.map { bounds(it).top }.minOf { it }

    /** A light screenshot, as a screen in a light theme is: what a white close button vanished against. */
    private fun png(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(android.graphics.Color.WHITE)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    private companion object {
        const val NAME = "screenshot.png"
        val TOOLBAR = listOf("Zoom out (-)", "Zoom in (+)", "Fit to screen (0)", "Actual size (1)", "Rotate left (Shift+R)", "Flip vertically (V)", "Download", "Copy image")
    }
}
