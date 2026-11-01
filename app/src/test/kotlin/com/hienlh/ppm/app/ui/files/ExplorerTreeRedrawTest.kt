package com.hienlh.ppm.app.ui.files

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The real project tree, rows that are drag sources and all, after a tap: a folder that was tapped open is drawn open at once, in
 * the primary colour and the medium weight, and drawn shut again by the next tap. Every row used to be played back from the
 * picture its drag source made of it when it was first drawn, so none of that showed. Drawn for real, at three pixels to the dp.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
internal class ExplorerTreeRedrawTest : ExplorerTreeDragTestBase() {
    private fun label(text: String): IntArray {
        val image = compose.onNodeWithText(text).captureToImage().asAndroidBitmap()
        return IntArray(image.width * image.height).also { image.getPixels(it, 0, image.width, 0, 0, image.width, image.height) }
    }

    @Test
    fun `a folder tapped open is drawn open at once, and shut again by the next tap`() {
        show(expand = emptyList())
        val shut = label("docs")

        compose.onNodeWithText("docs").performClick()
        compose.waitForIdle()
        assertTrue("the tap opened it", "docs" in expanded())
        val open = label("docs")
        assertFalse("the label is as it was drawn shut", shut.contentEquals(open))

        compose.onNodeWithText("docs").performClick()
        compose.waitForIdle()
        assertFalse("the tap shut it", "docs" in expanded())
        assertFalse("the label is as it was drawn open", open.contentEquals(label("docs")))
    }
}
