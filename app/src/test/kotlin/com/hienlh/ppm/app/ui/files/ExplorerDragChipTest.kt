package com.hienlh.ppm.app.ui.files

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Point
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The image a drag of tree rows is held by, drawn for real (native graphics, 3 pixels to the dp) and read back: the chip of
 * `drag-ghost.ts`. The chip is the box with the border, `padding: 4px 10px` round the words, `border-radius: 6px`, and the shadow
 * `0 2px 8px rgba(0,0,0,.35)` falls in the 10 dp margin round it. Every figure below is the CSS's, at three pixels to the dp.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class ExplorerDragChipTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val tokens = BuiltInThemes.auroraDark.tokens
    private lateinit var measurer: TextMeasurer
    private lateinit var density: Density

    @Before fun measure() {
        compose.setContent {
            measurer = rememberTextMeasurer()
            density = LocalDensity.current
        }
        compose.waitForIdle()
    }

    private fun chip(label: String) = ExplorerDragChip.of(label, measurer, density, tokens)

    private val fill = tokens.panel2.color
    private val border = tokens.border.color.compositeOver(fill)

    private fun Bitmap.at(x: Int, y: Int) = Color(getPixel(x, y))

    private fun assertColor(message: String, expected: Color, actual: Color) {
        for ((name, want, got) in listOf(Triple("red", expected.red, actual.red), Triple("green", expected.green, actual.green), Triple("blue", expected.blue, actual.blue))) {
            assertEquals("$message, $name", want, got, 0.02f)
        }
        assertEquals("$message, alpha", expected.alpha, actual.alpha, 0.02f)
    }

    /** The chip's box inside the bitmap: 30 px of margin on every side. */
    private fun Bitmap.box() = android.graphics.Rect(30, 30, width - 30, height - 30)

    @Test fun `the pointer is 12 dp in from the corner of the chip, 36 px, past the 30 px of margin`() {
        assertEquals(Point(66, 66), chip("app.kt").touch)
        assertEquals("whatever the words", Point(66, 66), chip("3 items, one of them with a long name.kt").touch)
    }

    @Test fun `the height is a line of 16_8 dp, 4 dp of padding and the 1 dp border, twice, and the margin twice`() {
        val bitmap = chip("app.kt").bitmap
        // 50.4 px of line (the text rounds it up), 12 px of padding and 3 px of border each side, 30 px of margin.
        assertTrue("${bitmap.height}", bitmap.height in 140..142)
        assertEquals("the words do not change the height", bitmap.height, chip("a much longer name than that.kt").bitmap.height)
    }

    @Test fun `the width is the words and 10 dp of padding and the border each side, and the margin`() {
        val short = chip("a").bitmap
        val long = chip("a longer name.kt").bitmap
        assertTrue("longer words, wider chip", long.width > short.width + 60)
        // 30 px of padding and 3 px of border each side of the words, 30 px of margin each side.
        val words = measurer.measure(androidx.compose.ui.text.AnnotatedString("a"), com.hienlh.ppm.design.ppmText(12f, androidx.compose.ui.text.font.FontWeight.Medium, com.hienlh.ppm.design.FontKind.SYSTEM, lineHeight = 16.8f)).size.width
        assertEquals(words + 2 * 33 + 2 * 30, short.width)
    }

    @Test fun `the words are 500 12px on a 16_8 line in the system's sans-serif, the text colour`() {
        // A pixel cannot say which face was used here: every family reads the same face under Robolectric, so the style is pinned itself.
        val style = explorerDragChipTextStyle(tokens)
        assertEquals("weight 500", FontWeight.Medium, style.fontWeight)
        assertEquals("system-ui, not the app's Geist", FontFamily.SansSerif, style.fontFamily)
        assertEquals(12.sp, style.fontSize)
        assertEquals("line-height 1.4", 16.8.sp, style.lineHeight)
        assertEquals(tokens.text.color, style.color)
    }

    @Test fun `the chip is the panel colour inside its border, the border on its edge and nothing beyond its rounded corner`() {
        val bitmap = chip("app.kt").bitmap
        val box = bitmap.box()
        val middle = (box.top + box.bottom) / 2
        assertColor("the padding", fill, bitmap.at(box.left + 3 + 10, middle))
        assertColor("the border, 1 dp: three pixels", border, bitmap.at(box.left + 1, middle))
        assertColor("the border's inner pixel", border, bitmap.at(box.left + 2, middle))
        assertColor("just inside it", fill, bitmap.at(box.left + 4, middle))
        assertColor("the top border", border, bitmap.at(box.centerX(), box.top + 1))
        // 6 dp of radius is 18 px: the corner of the box is not the chip's.
        assertTrue("the corner is outside the rounded chip", bitmap.at(box.left + 1, box.top + 1).alpha < 0.5f)
    }

    @Test fun `the words are drawn in the text colour, inside the padding`() {
        val bitmap = chip("app.kt").bitmap
        val box = bitmap.box()
        // The words' own span, 10 dp of padding and the border in from the box; the rows clear of the 6 dp (18 px) corners.
        val words = box.left + 3 + 30 until box.right - 3 - 30
        var brightest = 0
        val outside = mutableListOf<String>()
        for (y in box.top + 18 until box.bottom - 18) for (x in box.left + 4 until box.right - 4) {
            val pixel = bitmap.getPixel(x, y)
            if (kotlin.math.abs(AndroidColor.red(pixel) / 255f - fill.red) > 0.05f) {
                if (x !in words) outside += "($x,$y)"
                brightest = maxOf(brightest, AndroidColor.red(pixel))
            }
        }
        assertEquals("the words' own colour, where they are solid", tokens.text.color.red * 255, brightest.toFloat(), 3f)
        assertEquals("nothing in the padding, found at ${outside.take(12)}", 0, outside.size)
    }

    @Test fun `the shadow falls below the chip, darker near it than away from it, and not beyond the margin`() {
        val bitmap = chip("app.kt").bitmap
        val box = bitmap.box()
        val x = box.centerX()
        val near = bitmap.at(x, box.bottom + 3)
        val away = bitmap.at(x, box.bottom + 15)
        assertTrue("black", near.red == 0f && near.green == 0f && near.blue == 0f)
        assertTrue("under the chip: ${near.alpha}", near.alpha > 0.1f)
        assertTrue("fading: ${near.alpha} then ${away.alpha}", near.alpha > away.alpha)
        // Offset 2 dp (6 px) down: 3 px above the chip is 9 px outside the shadow's edge, 3 px below it 3 px inside.
        val above = bitmap.at(x, box.top - 3).alpha
        assertTrue("less above than below: $above against ${near.alpha}", above < near.alpha * 0.6f)
        assertEquals("nothing at the bitmap's corner", 0f, bitmap.at(0, 0).alpha, 0.02f)
    }

    @Test fun `the shadow builder tells the window the bitmap's size and the pointer's place, and draws the bitmap`() {
        val chip = chip("app.kt")
        val shadow = ExplorerDragShadow(chip)
        val size = Point()
        val touch = Point()
        shadow.onProvideShadowMetrics(size, touch)
        assertEquals(Point(chip.bitmap.width, chip.bitmap.height), size)
        assertEquals(chip.touch, touch)

        val canvasBitmap = Bitmap.createBitmap(chip.bitmap.width, chip.bitmap.height, Bitmap.Config.ARGB_8888)
        shadow.onDrawShadow(Canvas(canvasBitmap))
        assertTrue("the chip, shadow and all, in place", chip.bitmap.sameAs(canvasBitmap))
        val box = chip.bitmap.box()
        assertEquals(fill.toArgb(), canvasBitmap.getPixel(box.left + 13, box.centerY()))
    }
}
