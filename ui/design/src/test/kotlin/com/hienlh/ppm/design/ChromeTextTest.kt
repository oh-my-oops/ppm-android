package com.hienlh.ppm.design

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.max

/**
 * Lines stacked as [ChromeText] stacks them, against Chrome 145 on the phone emulator (2.625x):
 * 14 px Geist on `line-height: calc(1.25 / 0.875)`, some lines holding a 12 px Monaspace `code`.
 */
class ChromeTextTest {
    private val ratio = 1.25f / 0.875f

    private fun extent(face: ChromeFace, size: Float, scale: Float) =
        chromeTextExtent(size * scale, size * ratio * scale, face.ascent, face.descent)

    /** [lines] lines of [size] px Geist at [scale], the ones in [code] holding an inline code, as `lay` combines them. */
    private fun stack(lines: Int, scale: Float, size: Float = 14f, code: Set<Int> = emptySet()): ChromeLineBoxes {
        val strut = extent(ChromeFace.Sans, size, scale)
        val mono = extent(ChromeFace.Mono, 12f, scale)
        return chromeLineBoxes(lines) { line ->
            if (line in code) max(strut.first, mono.first) to max(strut.second, mono.second) else strut
        }
    }

    @Test
    fun `14 px lines on 20 are 52_5 px apart, not 53`() {
        val boxes = stack(6, 2.625f)
        assertArrayEquals(floatArrayOf(39f, 91.5f, 144f, 196.5f, 249f, 301.5f), boxes.baselines, 0.001f)
        assertEquals(315f, boxes.height, 0.001f)
    }

    @Test
    fun `a line holding an inline code is 53 px and moves the lines under it`() {
        val boxes = stack(6, 2.625f, code = setOf(0, 2))
        assertArrayEquals(floatArrayOf(39f, 92f, 144.5f, 197.5f, 250f, 302.5f), boxes.baselines, 0.001f)
        assertEquals(316f, boxes.height, 0.001f)
    }

    @Test
    fun `12 px lines are 45 px apart`() {
        val boxes = stack(6, 2.625f, size = 12f)
        assertEquals(33f, boxes.baselines[0], 0.001f)
        for (line in 1 until 6) assertEquals(45f, boxes.baselines[line] - boxes.baselines[line - 1], 0.001f)
    }

    @Test
    fun `a baseline is painted on its pixel rounded half up`() {
        // Painted at 39, 92, 144: 91.5 goes down to 92.
        assertEquals(listOf(39, 92, 144, 197, 249, 302), stack(6, 2.625f).baselines.map { chromePixel(0f, it) })
        // A block starting 525.5 px down paints its first baseline, 564.5, on 565: 40 into pixel 525.
        assertEquals(40, chromePixel(0.5f, 39f))
        assertEquals(92, chromePixel(0.5f, 91.5f))
        assertEquals(145, chromePixel(0.5f, 144f))
    }

    @Test
    fun `line heights are cut to a 64th of a pixel`() {
        // 12 px on 17.14 at 2x is a 34.2857 px line, which Blink holds as 34.28125.
        assertEquals(34.28125f, layoutUnit(12f * ratio * 2f), 0f)
        val (above, below) = extent(ChromeFace.Mono, 12f, 2f)
        assertEquals(23f, above, 0f)
        assertEquals(11.28125f, below, 0f)
    }
}
