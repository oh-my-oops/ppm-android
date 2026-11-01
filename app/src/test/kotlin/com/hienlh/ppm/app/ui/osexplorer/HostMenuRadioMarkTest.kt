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
 * The mark of the chosen radio as it is painted. The web's is `CircleIcon className="size-2 fill-current"`, and that icon is
 * Fluent's `circle`, an outline whose two circles are wound against each other: it draws a ring 6.4 across and 0.4 thick, a
 * hollow one, however it is filled. Nothing but pixels can tell that from a dot, so this reads them (native graphics, at 3
 * pixels to the dp: a ring 1.2 pixels thick is then drawn dark where it is, which at 1 pixel to the dp it would barely be).
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
    fun `the chosen radio is drawn as a ring 6 dp across with nothing in it, as the web draws it, not as a dot`() {
        rows()
        val ink = ink("Name")
        assertTrue("the chosen radio drew nothing", ink.isNotEmpty())
        val wide = ink.maxOf { it.first } - ink.minOf { it.first } + 1
        val tall = ink.maxOf { it.second } - ink.minOf { it.second } + 1
        // 8 dp x 16/20 = 6.4 dp, 19.2 pixels, and up to a pixel more where the edge is only half covered.
        assertTrue("$wide pixels wide", wide in 17..21)
        assertTrue("$tall pixels tall", tall in 17..21)
        val centre = (ink.minOf { it.first } + ink.maxOf { it.first }) / 2.0 to (ink.minOf { it.second } + ink.maxOf { it.second }) / 2.0
        assertEquals("the middle of the 14 dp slot, 15 dp in", 15.0 * perDp, centre.first, 2.0)
        val inside = ink.count { hypot(it.first - centre.first, it.second - centre.second) < 7.0 }
        assertEquals("pixels inside the ring", 0, inside)
    }

    @Test
    fun `a radio that is not chosen draws no mark`() {
        rows()
        assertEquals(emptyList<Pair<Int, Int>>(), ink("Size"))
    }
}
