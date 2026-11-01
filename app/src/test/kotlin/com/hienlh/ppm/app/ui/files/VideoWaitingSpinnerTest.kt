package com.hienlh.ppm.app.ui.files

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.media.VideoWaitingSpinner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The spinner over a video that waits for data (video-player.tsx): `size-8 text-white/80
 * animate-spin transition-opacity delay-300 starting:opacity-0`, clear for 300 ms, faded in over 150
 * on the ease Tailwind gives every transition, then turning once a second. Drawn for real (native
 * graphics, 3 pixels to the dp) on a paused clock, so each picture is a frame of a known time, 16 ms
 * apart. A shape's area is what says its size, because a box round a turning ring changes as it turns.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h400dp-xxhdpi")
class VideoWaitingSpinnerTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.testTag("stage").requiredSize(100.dp, 100.dp).background(Color.Black), Alignment.Center) { VideoWaitingSpinner() }
        }
    }

    private fun advanceTo(ms: Long) = compose.mainClock.advanceTimeBy(ms - compose.mainClock.currentTime)

    private fun picture(): PixelMap = compose.onNodeWithTag("stage").captureToImage().toPixelMap()

    /** What 80 percent white shows of a fade of 150 ms that starts at [start]. */
    private fun fadeAt(ms: Long, start: Long) = 0.8f * FastOutSlowInEasing.transform(((ms - start) / 150f).coerceIn(0f, 1f))

    @Test
    fun `it is clear for 300 ms and then fades in over 150 ms on the web's ease to white at 80 percent`() {
        show()

        advanceTo(304)
        assertEquals("the first 300 ms", 0f, picture().peak(), 0.01f)
        val seen = listOf(352L, 384L, 416L).associateWith { ms ->
            advanceTo(ms)
            picture().peak()
        }
        // The wake-up at 300 ms takes the next frame and the fade's first another, so here it starts at 320; a frame either way is the same fade.
        val fits = listOf(304L, 320L, 336L).any { start -> seen.all { (ms, peak) -> abs(peak - fadeAt(ms, start)) <= 0.06f } }
        assertTrue("not the web's fade of 150 ms from 300 ms: " + seen, fits)
        advanceTo(480)
        assertEquals("faded in", 0.8f, picture().peak(), 0.01f)
    }

    @Test
    fun `it is 32 dp and white, and in the middle of what it is over`() {
        show()
        advanceTo(480)

        val image = picture()
        // 833 pixels measured at 32 dp, at any angle it is turned to; 28 dp would be 638 and 36 dp 1054.
        assertEquals("its area", 833.0, image.coverage(0.8f), 833 * 0.04)
        val ink = image.brightest()
        for (channel in listOf(ink.red, ink.green, ink.blue)) assertEquals("white at 80 percent over black, not another colour", 0.8f, channel, 0.02f)
        val box = image.inkBox()
        assertNotNull("nothing was drawn", box)
        // The ring's box swings by a few pixels as it turns, its middle by up to 5.
        assertEquals("centred across", image.width / 2.0, (box!![0] + box[2]) / 2.0, 8.0)
        assertEquals("centred down", image.height / 2.0, (box[1] + box[3]) / 2.0, 8.0)
    }

    @Test
    fun `it turns once a second`() {
        show()
        advanceTo(480)
        val start = picture()
        val area = start.coverage(0.8f)

        advanceTo(480 + 256)
        val quarter = picture()
        advanceTo(480 + 496)
        val half = picture()
        advanceTo(480 + 992)
        val whole = picture()

        // A ring with its gap moved by any angle past 72 degrees differs by half of itself.
        assertTrue("a quarter of a turn changed nothing", start.differenceFrom(quarter, 0.8f) > 0.35 * area)
        assertTrue("half of a turn changed nothing", start.differenceFrom(half, 0.8f) > 0.35 * area)
        assertTrue("a turn did not bring it back", start.differenceFrom(whole, 0.8f) < 0.1 * area)
    }
}
