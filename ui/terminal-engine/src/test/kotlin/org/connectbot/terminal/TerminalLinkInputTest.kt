package org.connectbot.terminal

import android.graphics.Canvas
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.floor

/**
 * What the terminal does with a link, apart from finding it: which pointer opens it, which cells are underlined while it is
 * hovered, and how an OSC 8 link's dashes are laid. The first is the rule the web's terminal follows (xterm's Linkifier, a mouse
 * click) and the second the DOM renderer's, which a touch device gets; a finger and a stylus never open a link.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalLinkInputTest {
    private val link = TerminalLink("https://example.com/a", 5, 3, 25, 3)
    private val other = TerminalLink("https://example.com/b", 30, 3, 50, 3)

    @Test
    fun `a mouse that went down on a link and came up on it opens it`() {
        assertEquals(link, linkToOpen(true, link, link))
    }

    @Test
    fun `a link is the same link by its text and its range, not by the object that was looked up`() {
        assertEquals(link, linkToOpen(true, link, link.copy()))
        assertNull(linkToOpen(true, link, link.copy(endX = 26)))
        assertNull(linkToOpen(true, link, link.copy(startY = 4, endY = 4)))
        assertNull(linkToOpen(true, link, link.copy(url = "https://example.com/c")))
    }

    @Test
    fun `a finger never opens one, whatever it went down and came up on`() {
        assertNull(linkToOpen(false, link, link))
        assertNull(linkToOpen(false, link, other))
        assertNull(linkToOpen(false, null, link))
        assertNull(linkToOpen(false, link, null))
    }

    @Test
    fun `down on one link and up on another opens neither`() {
        assertNull(linkToOpen(true, link, other))
        assertNull(linkToOpen(true, other, link))
    }

    @Test
    fun `down on a link and up off it opens nothing, nor does up on a link that was not gone down on`() {
        assertNull(linkToOpen(true, link, null))
        assertNull(linkToOpen(true, null, link))
        assertNull(linkToOpen(true, null, null))
    }

    @Test
    fun `an OSC 8 link answers as a plain one does, and says which it is`() {
        val osc = TerminalLink("https://example.com/", 1, 1, 6, 1, osc8 = true)
        assertEquals(osc, linkToOpen(true, osc, osc.copy()))
        assertTrue(linkToOpen(true, osc, osc)!!.osc8)
        assertTrue(!linkToOpen(true, link, link)!!.osc8)
        assertNull(linkToOpen(false, osc, osc))
    }

    @Test
    fun `a link covers the cells from its first to its last, both included`() {
        val cols = 80
        assertTrue(link.covers(cols, 5, 3))
        assertTrue(link.covers(cols, 25, 3))
        assertTrue(!link.covers(cols, 4, 3))
        assertTrue(!link.covers(cols, 26, 3))
        assertTrue(!link.covers(cols, 15, 2))
        assertTrue(!link.covers(cols, 15, 4))
    }

    @Test
    fun `a link over several rows covers the end of the first, all of the middle and the start of the last`() {
        val cols = 80
        val wrapped = TerminalLink("https://example.com/long", 70, 3, 10, 5)
        assertTrue(!wrapped.covers(cols, 69, 3))
        assertTrue(wrapped.covers(cols, 70, 3))
        assertTrue(wrapped.covers(cols, 80, 3))
        assertTrue(wrapped.covers(cols, 1, 4))
        assertTrue(wrapped.covers(cols, 80, 4))
        assertTrue(wrapped.covers(cols, 1, 5))
        assertTrue(wrapped.covers(cols, 10, 5))
        assertTrue(!wrapped.covers(cols, 11, 5))
    }

    @Test
    fun `a hovered link in one row is underlined from its first cell to its last`() {
        assertEquals(listOf(LinkSpan(2, 4, 25)), hoverSpans(link, cols = 80, rows = 24, firstVisibleLine = 0))
    }

    @Test
    fun `a hovered link in a scrolled view is counted from the row at the top`() {
        // The link is on row 13 (1-based) of the buffer and the view starts at row index 10: it is the third row shown.
        assertEquals(listOf(LinkSpan(2, 4, 25)), hoverSpans(link.copy(startY = 13, endY = 13), cols = 80, rows = 24, firstVisibleLine = 10))
    }

    @Test
    fun `a hovered link over three rows is underlined to the margin, across, and to where it ends`() {
        val wrapped = TerminalLink("https://example.com/long", 70, 3, 10, 5)
        assertEquals(
            listOf(LinkSpan(2, 69, 80), LinkSpan(3, 0, 80), LinkSpan(4, 0, 10)),
            hoverSpans(wrapped, cols = 80, rows = 24, firstVisibleLine = 0),
        )
    }

    @Test
    fun `a hovered link that starts above the view is underlined from the first column of the first row`() {
        val above = TerminalLink("https://example.com/long", 70, 8, 10, 11)
        // Rows 8 to 11 (1-based), the view starts at row index 9: the link's first row is two above the view.
        assertEquals(
            listOf(LinkSpan(0, 0, 80), LinkSpan(1, 0, 10)),
            hoverSpans(above, cols = 80, rows = 24, firstVisibleLine = 9),
        )
        // Starting and ending above it leaves one empty span on the first row: xterm sets both columns to 0.
        assertEquals(listOf(LinkSpan(0, 0, 0)), hoverSpans(above, cols = 80, rows = 24, firstVisibleLine = 30))
    }

    @Test
    fun `a hovered link that ends below the view is underlined to the column its last row ends at, which is the web's quirk`() {
        val below = TerminalLink("https://example.com/long", 70, 23, 10, 26)
        // The view shows rows 1 to 24: the link starts on its row 23 and ends on 26, which is not shown.
        assertEquals(
            listOf(LinkSpan(22, 69, 80), LinkSpan(23, 0, 10)),
            hoverSpans(below, cols = 80, rows = 24, firstVisibleLine = 0),
        )
        // Starting below it as well: both ends clamp to the last row, and the span from where the link starts to where it ends has
        // its start past its end, so nothing is underlined there.
        assertEquals(listOf(LinkSpan(23, 69, 10)), hoverSpans(TerminalLink("x", 70, 30, 10, 33), cols = 80, rows = 24, firstVisibleLine = 0))
    }

    private fun assertDashes(expected: List<Pair<Float, Float>>, actual: List<ClosedFloatingPointRange<Float>>) {
        assertEquals("$actual", expected.size, actual.size)
        expected.zip(actual).forEachIndexed { i, (want, got) ->
            assertTrue("dash $i starts at ${got.start}, not ${want.first}", abs(got.start - want.first) < 0.001f)
            assertTrue("dash $i ends at ${got.endInclusive}, not ${want.second}", abs(got.endInclusive - want.second) < 0.001f)
        }
    }

    @Test
    fun `a line too short for two dashes is solid, and an empty one is nothing`() {
        assertEquals(emptyList<ClosedFloatingPointRange<Float>>(), dashedLine(0f, 1f))
        assertEquals(emptyList<ClosedFloatingPointRange<Float>>(), dashedLine(-4f, 1f))
        assertEquals(emptyList<ClosedFloatingPointRange<Float>>(), dashedLine(0.9f, 1f))
        assertDashes(listOf(0f to 1f), dashedLine(1f, 1f))
        assertDashes(listOf(0f to 6f), dashedLine(6f, 1f))
        // Two dashes of 3 px are 6 px long: 2 px thick ones are 12, and from 3 px of thickness on they are 6 px each as well.
        assertDashes(listOf(0f to 12f), dashedLine(12f, 2f))
        assertDashes(listOf(0f to 12f), dashedLine(12f, 3f))
    }

    @Test
    fun `Blink works in whole pixels, so a line is as long as the pixels it holds`() {
        assertDashes(listOf(0f to 6f), dashedLine(6.9f, 1f))
        assertDashes(dashedLine(7f, 1f).map { it.start to it.endInclusive }, dashedLine(7.9f, 1f))
        assertDashes(dashedLine(30f, 1f).map { it.start to it.endInclusive }, dashedLine(30.99f, 1f))
    }

    @Test
    fun `a line that holds two dashes and not a gap more has them scaled down to fit`() {
        // 7 px of 1 px dashes: scaled by 7/8, the dashes 2.625 and the gap 1.75, which is what Chrome draws.
        assertDashes(listOf(0f to 2.625f, 4.375f to 7f), dashedLine(7f, 1f))
        assertDashes(listOf(0f to 3f, 5f to 8f), dashedLine(8f, 1f))
        // 13 px of 3 px ones, whose dashes are 6 and gap 3: 13/15.
        assertDashes(listOf(0f to 5.2f, 7.8f to 13f), dashedLine(13f, 3f))
    }

    @Test
    fun `a longer line has the dashes that leave the gap nearest the ideal one, widened or narrowed so a dash ends it`() {
        // 20: four dashes with a gap of 2 and two thirds against five with a gap of 1.25.
        assertDashes(listOf(0f to 3f, 5.6667f to 8.6667f, 11.3333f to 14.3333f, 17f to 20f), dashedLine(20f, 1f))
        // 30: six dashes need gaps of 2.4, seven gaps of 1.5.
        assertDashes(listOf(0f to 3f, 5.4f to 8.4f, 10.8f to 13.8f, 16.2f to 19.2f, 21.6f to 24.6f, 27f to 30f), dashedLine(30f, 1f))
        // 11: two dashes with a gap of 5 against three with a gap of 1: the second is nearer two.
        assertDashes(listOf(0f to 3f, 4f to 7f, 8f to 11f), dashedLine(11f, 1f))
        // 9: three dashes would leave no gap at all, so two with a gap of 3.
        assertDashes(listOf(0f to 3f, 6f to 9f), dashedLine(9f, 1f))
        // 21 px of 3 px lines: dashes 6 and gap 3 ideally, 1.5 for three of them.
        assertDashes(listOf(0f to 6f, 7.5f to 13.5f, 15f to 21f), dashedLine(21f, 3f))
    }

    @Test
    fun `every dashed line starts at its start, ends at its end, and has dashes of one length that never touch`() {
        for (thickness in 1..5) {
            val dash = thickness * (if (thickness >= 3) 2 else 3).toFloat()
            var length = 2 * dash + 3f * thickness
            while (length < 400f) {
                val dashes = dashedLine(length, thickness.toFloat())
                assertTrue("two dashes at least for $length", dashes.size >= 2)
                assertEquals("starts for $length", 0f, dashes.first().start, 0.0001f)
                assertEquals("ends for $length", floor(length), dashes.last().endInclusive, 0.001f)
                dashes.forEachIndexed { i, one ->
                    assertEquals("dash $i of $length at $thickness", dash, one.endInclusive - one.start, 0.01f)
                    if (i > 0) assertTrue("dashes $i of $length at $thickness touch", one.start > dashes[i - 1].endInclusive)
                }
                length += 0.37f
            }
        }
    }

    /** The coverage of each pixel column from 0 to [columns], given the dashes as Chrome's own picture is counted. */
    private fun coverage(dashes: List<ClosedFloatingPointRange<Float>>, columns: Int): List<Float> = List(columns) { x ->
        dashes.sumOf { (minOf(it.endInclusive, x + 1f) - maxOf(it.start, x.toFloat())).coerceAtLeast(0f).toDouble() }.toFloat()
    }

    @Test
    fun `the dashes cover the pixels as Chrome's own do, for thicknesses 1 to 5 and every length up to 48 pixels`() {
        val cases = checkNotNull(javaClass.getResource("/terminal-dash-cases.tsv")) { "terminal-dash-cases.tsv is missing" }.readText().lines()
            .filter { it.startsWith("case\t") }.map { it.split('\t') }
        assertEquals(5 * 48, cases.size)
        val wrong = cases.filter { (_, thickness, length, ink) ->
            val chrome = ink.split(',').map { it.toFloat() }
            val mine = coverage(dashedLine(length.toFloat(), thickness.toFloat()), chrome.size)
            // Skia's anti-aliasing is off by up to 0.02 of a pixel at an edge that falls inside one (0.18 where 0.2 is geometry).
            chrome.indices.any { abs(chrome[it] - mine[it]) > 0.035f }
        }
        assertEquals(
            "thickness and length that differ: " + wrong.take(12).joinToString { "${it[1]}/${it[2]}" },
            0,
            wrong.size,
        )
        // Solid, scaled, and widened and narrowed gaps are all in there.
        assertTrue(cases.count { c -> c[3].split(',').count { it == "1.00" } == c[2].toInt() } > 15)
        assertTrue(cases.any { it[3].contains("0.62") })
    }

    @Test
    fun `an underline is a tenth of the font thick, in whole pixels and one at least, as Chrome draws the web's`() {
        // 13 CSS px of font at the device scale factors Chrome was measured at: 1, 1.25, 1.5, 1.75, 2, 2.25, 2.5, 2.625, 2.75, 3, 3.25, 3.5, 4.
        val measured = listOf(13f to 1f, 16.25f to 1f, 19.5f to 1f, 22.75f to 2f, 26f to 2f, 29.25f to 2f, 32.5f to 3f, 34.125f to 3f, 35.75f to 3f, 39f to 3f, 42.25f to 4f, 45.5f to 4f, 52f to 5f)
        for ((font, thickness) in measured) assertEquals("font $font", thickness, underlineThickness(font), 0f)
        assertEquals(1f, underlineThickness(4f), 0f)
    }

    @Test
    fun `an underline's top is a gap under the baseline row that grows with its thickness, as measured in Chrome`() {
        // First underline row under the baseline row at those thicknesses: 1, 2, 2 (for 3), 3 (for 4 and 5); the centre is half a thickness further.
        val measured = listOf(1f to 1f, 2f to 2f, 3f to 2f, 4f to 3f, 5f to 3f)
        for ((thickness, gap) in measured) assertEquals("thickness $thickness", 100f + gap + thickness / 2f, underlineCenter(100f, thickness), 0f)
        // The baseline snaps to a pixel.
        assertEquals(underlineCenter(100f, 3f), underlineCenter(100.4f, 3f), 0f)
        assertEquals(underlineCenter(101f, 3f), underlineCenter(100.6f, 3f), 0f)
    }

    private fun strokes(canvas: Canvas) = mockingDetails(canvas).invocations
        .filter { it.method.name == "drawLine" }
        .map { call -> call.arguments.take(4).map { (it as Float) } }

    @Test
    fun `the dashes are drawn where they fall, from the line's start snapped to a pixel, on its centre, and as thick as it is`() {
        val canvas = mock(Canvas::class.java)
        val paint = Paint()
        paint.strokeWidth = 1f
        // 21.16 px wide from 26.25 is a line of 21 px from 26: the three dashes of 6 px, the middle one from 7.5.
        drawDashedUnderline(canvas, paint, left = 26.25f, right = 47.41f, centerY = 46.5f, thickness = 3f)
        assertEquals(listOf(listOf(26f, 46.5f, 32f, 46.5f), listOf(33.5f, 46.5f, 39.5f, 46.5f), listOf(41f, 46.5f, 47f, 46.5f)), strokes(canvas))
        // The paint is the caller's again afterwards.
        assertEquals(1f, paint.strokeWidth, 0f)
    }

    @Test
    fun `the line is as thick as the underline is while it is drawn`() {
        val seen = mutableListOf<Float>()
        val paint = Paint()
        val canvas = object : Canvas() {
            override fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, p: Paint) {
                seen += p.strokeWidth
            }
        }
        paint.strokeWidth = 1f
        drawDashedUnderline(canvas, paint, left = 0f, right = 21f, centerY = 5f, thickness = 3f)
        assertEquals(listOf(3f, 3f, 3f), seen)
    }

    @Test
    fun `a link too short for dashes is one stroke across it, and an empty one draws nothing`() {
        val canvas = mock(Canvas::class.java)
        drawDashedUnderline(canvas, Paint(), left = 4f, right = 9f, centerY = 9.5f, thickness = 1f)
        assertEquals(listOf(listOf(4f, 9.5f, 9f, 9.5f)), strokes(canvas))
        val none = mock(Canvas::class.java)
        drawDashedUnderline(none, Paint(), left = 4f, right = 4.8f, centerY = 9.5f, thickness = 1f)
        assertEquals(emptyList<List<Float>>(), strokes(none))
    }
}
