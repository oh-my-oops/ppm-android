package com.hienlh.ppm.app.ui.files

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import kotlin.math.abs

/** A pixel counts as ink once any of its channels is above this. */
private const val INK = 0.02f

/** The brightest any channel of [this] gets, in [rows]. */
internal fun PixelMap.peak(rows: IntRange = 0 until height): Float {
    var best = 0f
    for (y in rows) for (x in 0 until width) {
        val at = this[x, y]
        best = maxOf(best, at.red, at.green, at.blue)
    }
    return best
}

/** The pixel with the most red in it, in [rows]. */
internal fun PixelMap.brightest(rows: IntRange = 0 until height): Color {
    var best = Color.Black
    for (y in rows) for (x in 0 until width) if (this[x, y].red > best.red) best = this[x, y]
    return best
}

/**
 * The area of what is drawn in [rows] in pixels, with a pixel only part covered counting part: the
 * red of each, over the red [full] ink has on black. A shape's area stays the same as it turns,
 * which a box round it does not.
 */
internal fun PixelMap.coverage(full: Float, rows: IntRange = 0 until height): Double {
    var sum = 0.0
    for (y in rows) for (x in 0 until width) sum += this[x, y].red / full.toDouble()
    return sum
}

/** How much of [full] ink is drawn differently in [other], in the unit of [coverage]. */
internal fun PixelMap.differenceFrom(other: PixelMap, full: Float): Double {
    var sum = 0.0
    for (y in 0 until height) for (x in 0 until width) sum += abs(this[x, y].red - other[x, y].red) / full.toDouble()
    return sum
}

/** The box round every pixel with ink in [rows], as left, top, right, bottom; null when there is none. */
internal fun PixelMap.inkBox(rows: IntRange = 0 until height): List<Int>? {
    var left = Int.MAX_VALUE
    var top = Int.MAX_VALUE
    var right = -1
    var bottom = -1
    for (y in rows) for (x in 0 until width) {
        val at = this[x, y]
        if (maxOf(at.red, at.green, at.blue) > INK) {
            left = minOf(left, x)
            right = maxOf(right, x)
            top = minOf(top, y)
            bottom = maxOf(bottom, y)
        }
    }
    return if (right < 0) null else listOf(left, top, right, bottom)
}

/** The runs of rows that have ink on them, top to bottom. */
internal fun PixelMap.inkBands(): List<IntRange> {
    val bands = mutableListOf<IntRange>()
    var start = -1
    for (y in 0..height) {
        val any = y < height && (0 until width).any { x -> maxOf(this[x, y].red, this[x, y].green, this[x, y].blue) > INK }
        if (any && start < 0) start = y
        if (!any && start >= 0) {
            bands += start until y
            start = -1
        }
    }
    return bands
}
