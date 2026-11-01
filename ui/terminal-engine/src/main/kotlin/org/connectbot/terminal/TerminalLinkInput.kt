package org.connectbot.terminal

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

/**
 * The link to open when a pointer goes up, as xterm's Linkifier decides it (`_handleMouseUp`): it went down on a link and came up on
 * that same link. Only a mouse does that. A finger never opens one: the web page answers every tap in its own `touchend` handler
 * (`terminal-tab.tsx`), so the browser sends no click after it, and a stylus is a touch to Chrome on Android.
 */
internal fun linkToOpen(mouse: Boolean, down: TerminalLink?, up: TerminalLink?): TerminalLink? =
    if (mouse && down != null && down == up) down else null

/** Columns [start] until [end] of the viewport row [row]. */
internal data class LinkSpan(val row: Int, val start: Int, val end: Int)

/**
 * The cells xterm's DOM renderer underlines while [link] is hovered (`DomRenderer._setCellUnderline`), which is the renderer a
 * touch device gets (`use-terminal.ts` leaves WebGL to the others). A row is a row of what is shown: [firstVisibleLine] is the
 * buffer row at the top. A link that starts above the view is underlined from the first column of the first row, and one that ends
 * below it only as far as the column its last row ends at, which is what xterm does.
 */
internal fun hoverSpans(link: TerminalLink, cols: Int, rows: Int, firstVisibleLine: Int): List<LinkSpan> {
    var x = link.startX - 1
    var x2 = link.endX
    var y = link.startY - 1 - firstVisibleLine
    var y2 = link.endY - 1 - firstVisibleLine
    if (y < 0) x = 0
    if (y2 < 0) x2 = 0
    y = y.coerceIn(0, rows - 1)
    y2 = y2.coerceIn(0, rows - 1)
    return (y..y2).map { row -> LinkSpan(row, if (row == y) x else 0, if (row == y2) x2 else cols) }
}

/**
 * The thickness Chrome gives an underline whose `text-decoration-thickness` is `auto`, which is what xterm's DOM renderer leaves it
 * at, for text [fontPx] pixels high: a tenth of that in whole pixels, one at least. Measured in headless Chrome for the web's font at
 * 13 CSS px from 1x to 4x: 1 px up to 19.5 px of font, 2 up to 29.25, 3 at 32.5 to 39, 4 at 42.25 and 45.5, 5 at 52.
 */
internal fun underlineThickness(fontPx: Float): Float = maxOf(1f, floor(fontPx / 10f))

/**
 * The middle of an underline [thickness] thick as Chrome puts it under text whose baseline is [baseline]: its top a gap under the
 * baseline row, half the thickness rounded down and one more (1 px for a 1 px line, 2 for 2 and 3, 3 for 4 and 5, as measured).
 */
internal fun underlineCenter(baseline: Float, thickness: Float): Float = round(baseline) + floor(thickness / 2f) + 1f + thickness / 2f

/**
 * Where a dashed underline [length] long has its dashes, both in pixels, the way Chrome strokes `text-decoration: dashed underline`
 * (xterm's CSS for an OSC 8 cell, `.xterm-underline-5`) when the line is [thickness] thick (`StrokeData::SetupPaintDashPathEffect`).
 * Blink works in whole pixels, so the length is cut to one. A dash is three thicknesses long with a gap of two, or two and one from a
 * thickness of 3 on. A line that cannot hold two dashes is solid. One that holds two and not a gap more has them scaled down to fit.
 * Longer ones fit as many as leave the gap nearest the ideal one, widened or narrowed so a dash ends the line
 * (`SelectBestDashGap`). `terminal-dash-cases.tsv` holds Chrome's own coverage of these for thicknesses 1 to 5 and lengths 1 to 48.
 */
internal fun dashedLine(length: Float, thickness: Float): List<ClosedFloatingPointRange<Float>> {
    val whole = floor(length)
    if (whole <= 0f) return emptyList()
    var dash = thickness * (if (thickness >= 3f) 2f else 3f)
    var gap = thickness * (if (thickness >= 3f) 1f else 2f)
    if (whole <= dash * 2f) return listOf(0f..whole)
    if (whole <= dash * 2f + gap) {
        val scale = whole / (dash * 2f + gap)
        dash *= scale
        gap *= scale
    } else {
        gap = bestDashGap(whole, dash, gap)
    }
    val dashes = ArrayList<ClosedFloatingPointRange<Float>>()
    var start = 0f
    while (start < whole) {
        dashes += start..minOf(start + dash, whole)
        start += dash + gap
    }
    return dashes
}

private fun bestDashGap(strokeLength: Float, dashLength: Float, gapLength: Float): Float {
    // The number of dashes that leaves a gap closest to the ideal one, as many gaps as dashes less one.
    val minDashes = floor((strokeLength + gapLength) / (dashLength + gapLength))
    val maxDashes = minDashes + 1f
    val minGap = (strokeLength - minDashes * dashLength) / (minDashes - 1f)
    val maxGap = (strokeLength - maxDashes * dashLength) / (maxDashes - 1f)
    return if (maxGap <= 0f || abs(minGap - gapLength) < abs(maxGap - gapLength)) minGap else maxGap
}

/**
 * An OSC 8 link's underline across [left] to [right] on the line [centerY], [thickness] thick and dashed as Chrome draws it. Chrome
 * snaps where the line starts to a whole pixel and cuts its length to one ([dashedLine]).
 */
internal fun drawDashedUnderline(canvas: Canvas, paint: Paint, left: Float, right: Float, centerY: Float, thickness: Float) {
    val width = paint.strokeWidth
    paint.strokeWidth = thickness
    val start = round(left)
    for (dash in dashedLine(right - left, thickness)) {
        canvas.drawLine(start + dash.start, centerY, start + dash.endInclusive, centerY, paint)
    }
    paint.strokeWidth = width
}
