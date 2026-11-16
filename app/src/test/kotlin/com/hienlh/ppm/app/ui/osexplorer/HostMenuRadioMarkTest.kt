package com.hienlh.ppm.app.ui.osexplorer

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.hienlh.ppm.app.ui.MenuBox
import com.hienlh.ppm.app.withComposeActivity
import kotlin.math.abs
import kotlin.math.hypot
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
 * The mark of the chosen radio as it is painted: the web's `RadioDot`, `block size-2 rounded-full bg-current`, a filled dot
 * 8 across. It was `CircleIcon className="size-2 fill-current"`, Fluent's `circle`, whose two circles are wound against each
 * other, so it drew a hollow ring however it was filled. Nothing but pixels can tell a ring from a dot, so this reads them
 * (native graphics, at 3 pixels to the dp).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h400dp-xxhdpi")
class HostMenuRadioMarkTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val perDp = 3

    /** Every pixel of the row named [label], left of where its label starts, that is not the menu's own fill, as (x, y). */
    private fun ink(label: String): List<Pair<Int, Int>> {
        val row = compose.onNodeWithText(label).captureToImage().toPixelMap()
        val fill = row[2, 2]
        val found = mutableListOf<Pair<Int, Int>>()
        for (y in 0 until row.height) for (x in 0 until 30 * perDp) {
            val at = row[x, y]
            if (maxOf(abs(at.red - fill.red), abs(at.green - fill.green), abs(at.blue - fill.blue)) > 0.25f) found += x to y
        }
        return found
    }

    private fun rows() = compose.setContent {
        MenuBox {
            MenuRadio("Name", selected = true) {}
            MenuRadio("Size", selected = false) {}
        }
    }

    @Test
    fun `the chosen radio is drawn as a filled dot 8 dp across, as the web's RadioDot`() {
        rows()
        val ink = ink("Name")
        assertTrue("the chosen radio drew nothing", ink.isNotEmpty())
        val wide = ink.maxOf { it.first } - ink.minOf { it.first } + 1
        val tall = ink.maxOf { it.second } - ink.minOf { it.second } + 1
        // 8 dp, 24 pixels, and up to a pixel more where the edge is only half covered.
        assertTrue("$wide pixels wide", wide in 23..26)
        assertTrue("$tall pixels tall", tall in 23..26)
        val centre = (ink.minOf { it.first } + ink.maxOf { it.first }) / 2.0 to (ink.minOf { it.second } + ink.maxOf { it.second }) / 2.0
        assertEquals("the middle of the 14 dp slot, 15 dp in", 15.0 * perDp, centre.first, 2.0)
        // Every pixel well inside the edge is inked: a dot, not a ring.
        val core = (0 until 30 * perDp).flatMap { x -> (0 until 60 * perDp).map { y -> x to y } }
            .filter { hypot(it.first - centre.first, it.second - centre.second) < 9.0 }
        assertEquals("pixels inside the dot left blank", emptyList<Pair<Int, Int>>(), core.filter { it !in ink })
    }

    @Test
    fun `a radio that is not chosen draws no mark`() {
        rows()
        assertEquals(emptyList<Pair<Int, Int>>(), ink("Size"))
    }
}
