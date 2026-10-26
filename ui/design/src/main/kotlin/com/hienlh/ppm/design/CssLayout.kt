package com.hienlh.ppm.design

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ParentDataModifierNode
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.node.invalidateParentData
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * How tall a child of a [CssColumn] is as Chrome lays it out: in device pixels with the fraction
 * kept, where Compose rounds every box to whole pixels as it measures it. At 2.625x a 16.5 px
 * label is 43.3125 px and measures 44, a 4 px gap is 10.5 and spaces 11; Chrome keeps the
 * fractions all the way down and snaps each box to pixels only as it paints it, so a long form
 * built of rounded rows ends up 20 px longer than the web's by its middle.
 */
class CssExact internal constructor(internal val fixed: Float = Float.NaN) {
    /** The exact height: [fixed] for a box of a set height, else set as the child is measured. */
    internal var height = fixed

    /** The height the child's own layout reported, so whole pixels added around it still count. */
    internal var reported = -1

    /** Where in its first pixel the child starts, set by the column before it measures it. */
    internal var phase = 0f

    /** The column laid out from [phase], measured again whenever it changes, and the phase it last used. */
    internal var reader: LayoutModifierNode? = null
    internal var measuredPhase = Float.NaN

    /** The child that gives way ([cssFlex]), and whether it also takes all the height left ([cssFlex] `grow`). */
    internal var flex = false
    internal var grow = false

    /** The child of a [CssRow] that shrinks to what the others leave it ([cssShrink]). */
    internal var shrink = false

    /** The exact width, set as the child is measured ([CssText], a [CssRow]); NaN where it is not known. */
    internal var width = Float.NaN

    /** The width the child's own layout reported, so whole pixels added around it still count. */
    internal var reportedWidth = -1
}

/** How wide a child carrying [data] that measured [placeable] is exactly: what it reports, the whole pixels added around it included. */
private fun exactWidth(data: CssExact?, placeable: Placeable): Float =
    if (data == null || data.width.isNaN() || data.reportedWidth < 0) placeable.width.toFloat() else data.width + (placeable.width - data.reportedWidth)

/** How tall a child carrying [data] that measured [placeable] is exactly, as a [CssColumn] counts it. */
private fun exactHeight(data: CssExact?, placeable: Placeable): Float = when {
    data == null || data.height.isNaN() -> placeable.height.toFloat()
    !data.fixed.isNaN() -> data.fixed
    data.reported >= 0 -> data.height + (placeable.height - data.reported)
    else -> data.height
}

/** Tells a [cssPhased] child the [phase] its exact place gives it, measured again if it was measured at another. */
private fun CssExact.givePhase(phase: Float) {
    this.phase = phase
    val reader = reader
    if (reader != null && measuredPhase != phase) reader.invalidateMeasurement()
}

@Composable
fun rememberCssExact(): CssExact = remember { CssExact() }

/**
 * Lends [exact] to a box that is as tall as one of its children, a row around a [CssColumn] given
 * the same [exact], so the column around the row counts the child's exact height.
 */
fun Modifier.cssExact(exact: CssExact): Modifier = this then CssExactElement(exact, reads = false)

/**
 * Tells a box laid out in a [CssColumn] where in its first pixel it starts ([cssPhase], read as it
 * measures), and measures it again whenever that changes: what it places inside rounds from its
 * exact place, as Chrome rounds each box, not from the pixel the box was put on.
 */
fun Modifier.cssPhased(exact: CssExact): Modifier = this then CssExactElement(exact, reads = true)

/** Where in its first pixel a [cssPhased] box starts, from -0.5 to 0.5: read as the box measures. */
fun CssExact.cssPhase(): Float {
    measuredPhase = phase
    return phase
}

/** Tells the [CssColumn] around a [cssPhased] box that it is [height] px tall exactly, though it measured [measured]. */
fun CssExact.cssReport(height: Float, measured: Int) {
    this.height = height
    reported = measured
}

/**
 * Measures a child as a [CssColumn] measures one, for a layout that stacks its children itself
 * (a table's rows): the child's exact top is [at] px down from the layout's first pixel, and it
 * is told where in the pixel that rounds to it starts, measured again whenever that changed.
 * Answers how tall it is exactly — what it reports, the whole pixels added around it included —
 * or what it measures when it knows nothing finer.
 */
fun Measurable.cssMeasure(constraints: Constraints, at: Float): Pair<Placeable, Float> {
    val data = parentData as? CssExact
    if (data != null) {
        data.phase = at - at.roundToInt()
        val reader = data.reader
        if (reader != null && data.measuredPhase != data.phase) reader.invalidateMeasurement()
    }
    val placeable = measure(constraints)
    val height = when {
        data == null || data.height.isNaN() -> placeable.height.toFloat()
        !data.fixed.isNaN() -> data.fixed
        data.reported >= 0 -> data.height + (placeable.height - data.reported)
        else -> data.height
    }
    return placeable to height
}

/**
 * A column laid out as Chrome stacks blocks: each child [gap] under the last and [top] / [bottom]
 * inside its edges, all kept in fractions of a pixel, and each child put on the whole pixel its
 * exact place rounds to. A child that knows its exact height ([cssLines], [cssHeight], another
 * CssColumn) counts at that, sized to the pixels its two edges round to; any other at the height
 * it measures. Children are laid out at the start, as wide as they ask within the column.
 *
 * A column inside one is told where in its first pixel it starts, and measured again when that
 * changes: its rows round from there, so content above it that moves by a fraction can move
 * them by a pixel with nothing else about them changed.
 *
 * In a column of bounded height one child can give way ([cssFlex]), as a `min-h-0 flex-1` box
 * does in a flex column: it is held to what the rest leaves it, so the column never grows past
 * its bound, and is no taller than it asks to be. One that grows takes all of it however little it
 * asks for, and the children under it are measured first, as Chrome sizes them before it: they
 * end on the column's last pixel and start where their exact heights put them above it, the
 * composer's 352.52 px under a chat at 2.625x, and only then learn where in a pixel that is.
 *
 * With [gaps] each child has its own space above it, the first one's included, in place of [gap]:
 * the margins CSS leaves between blocks once it has collapsed them.
 */
@Composable
fun CssColumn(
    modifier: Modifier = Modifier,
    gap: Dp = 0.dp,
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
    exact: CssExact = rememberCssExact(),
    gaps: List<Dp>? = null,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier.then(CssExactElement(exact, reads = true))) { measurables, constraints ->
        val phase = exact.phase
        exact.measuredPhase = phase
        val gapPx = gap.toPx()
        fun above(index: Int) = gaps?.getOrNull(index)?.toPx() ?: if (index > 0 && gaps == null) gapPx else 0f
        val loose = Constraints(maxWidth = constraints.maxWidth, maxHeight = Constraints.Infinity)
        val exacts = measurables.map { it.parentData as? CssExact }
        val flex = if (constraints.hasBoundedHeight) exacts.indexOfFirst { it?.flex == true } else -1
        val grows = flex >= 0 && exacts[flex]?.grow == true
        // Beside one that grows the others are held to the column's height, as a Column holds them.
        val bound = if (grows) loose.copy(maxHeight = constraints.maxHeight) else loose
        val placeables = arrayOfNulls<Placeable>(measurables.size)
        val heights = FloatArray(measurables.size)
        // Where the child that gives way has to end: above the gaps and the children under it,
        // which are measured first when it grows (one of a set height then takes it rounded), else
        // must be of a set height.
        var limit = constraints.maxHeight - bottom.toPx()
        if (flex >= 0) {
            for (index in flex + 1 until measurables.size) {
                if (grows) {
                    val placeable = measurables[index].measure(bound)
                    placeables[index] = placeable
                    heights[index] = exactHeight(exacts[index], placeable)
                    limit -= above(index) + heights[index]
                } else {
                    limit -= above(index) + (exacts[index]?.fixed?.takeUnless { it.isNaN() } ?: 0f)
                }
            }
        }
        val tops = IntArray(measurables.size)
        var at = phase + top.toPx()
        var width = constraints.minWidth
        measurables.forEachIndexed { index, measurable ->
            at += above(index)
            val y = at.roundToInt()
            val data = exacts[index]
            data?.givePhase(at - y)
            val early = placeables[index]
            val placeable = when {
                early != null -> early
                // A box of a set height takes the pixels between its two rounded edges.
                data != null && !data.fixed.isNaN() -> {
                    val height = (at + data.fixed).roundToInt() - y
                    measurable.measure(loose.copy(minHeight = height, maxHeight = height))
                }
                index == flex && grows -> {
                    val height = max(0, limit.roundToInt() - y)
                    measurable.measure(loose.copy(minHeight = height, maxHeight = height))
                }
                index == flex -> measurable.measure(loose.copy(maxHeight = max(0, limit.roundToInt() - y)))
                else -> measurable.measure(bound)
            }
            at += when {
                early != null -> heights[index]
                index == flex && grows -> max(0f, limit - at)
                else -> exactHeight(data, placeable)
            }
            placeables[index] = placeable
            tops[index] = y
            width = max(width, placeable.width)
        }
        at += bottom.toPx()
        exact.height = at - phase
        val height = at.roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight)
        exact.reported = height
        layout(width, height) {
            placeables.forEachIndexed { index, placeable -> placeable!!.place(0, tops[index]) }
        }
    }
}

/**
 * One row of a CSS grid of equal columns [gap] apart, each child a column: laid out as Chrome lays
 * out the tracks, at their exact places in fractions of a pixel, and each given the pixels its two
 * edges round to. Two columns sharing 871 px end the first on 435.5, so Chrome gives that one the
 * odd pixel where a weighted Row gives it to the last. It fills the width it is given, which must
 * be bounded; children are as tall as they measure, at the top.
 *
 * With [bases] the children are `flex-1` items instead, each starting from its base — the borders
 * and paddings a `border-box` item keeps at `flex-basis: 0%` — and given an equal share of what is
 * left: a bordered button beside a borderless one is its borders wider.
 */
@Composable
fun CssGridRow(modifier: Modifier = Modifier, gap: Dp = 0.dp, bases: List<Dp> = emptyList(), content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val gapPx = gap.toPx()
        val base = FloatArray(measurables.size) { bases.getOrNull(it)?.toPx() ?: 0f }
        val track = (width - gapPx * (measurables.size - 1) - base.sum()) / measurables.size
        val lefts = IntArray(measurables.size)
        var at = 0f
        val placeables = measurables.mapIndexed { index, measurable ->
            val end = at + base[index] + track
            lefts[index] = at.roundToInt()
            val columnWidth = max(0, end.roundToInt() - lefts[index])
            at = end + gapPx
            measurable.measure(Constraints(columnWidth, columnWidth, 0, constraints.maxHeight))
        }
        val height = (placeables.maxOfOrNull { it.height } ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            placeables.forEachIndexed { index, placeable -> placeable.placeRelative(lefts[index], 0) }
        }
    }
}

/**
 * A row laid out as Chrome lays out a flex row of content-sized items: each child [gap] after the
 * last and [start] / [end] inside the row's edges, all kept in fractions of a pixel, and each put
 * on the pixel its exact left edge rounds to. A child that knows its exact width ([CssText],
 * another CssRow, [cssWidth]) counts at that, any other at the width it measures. Compose
 * measures a label to the next whole pixel, so a row of them moved each one after the first up to
 * a pixel further right than the one before: Copy 2 px and Reply 3 px right of the web's.
 *
 * Up and down it is as exact: [top] / [bottom] inside its edges, at least [minHeight] (`min-h-*`,
 * 115.5 px for 44 at 2.625x where Compose makes 116), each child [align]ed across its line from its
 * exact height ([cssHeight], a [CssColumn], another CssRow), and the row counted at its exact
 * height by a [CssColumn] around it, which tells it where in its first pixel it starts.
 *
 * With [wrap] (`flex-wrap`) a child that does not fit starts a new line, [gap] under the last. A
 * row given a minimum width wider than its children puts them at its start, or in its middle with
 * [centre] (`justify-center`). In a row that does not wrap one child may take what the others
 * leave ([cssFlex], `flex-1`): it is measured last, as wide as the pixels its exact edges round to.
 * Or, with none of those, one may shrink to fit it ([cssShrink], `min-w-0 truncate`), measured
 * last at most that wide.
 *
 * [alignEnd]: the row sits flush with the end of the box that holds it, which ends on a whole
 * pixel — an `items-end` column's child. Chrome rounds the row's start there, so this row is as
 * wide as from that pixel to the end and places its children from where its exact start falls.
 */
@Composable
fun CssRow(
    modifier: Modifier = Modifier,
    gap: Dp = 0.dp,
    start: Dp = 0.dp,
    end: Dp = 0.dp,
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
    minHeight: Dp = 0.dp,
    wrap: Boolean = false,
    centre: Boolean = false,
    alignEnd: Boolean = false,
    align: CssAlign = CssAlign.Center,
    exact: CssExact = rememberCssExact(),
    content: @Composable () -> Unit,
) {
    Layout(content, modifier.then(CssExactElement(exact, reads = true))) { measurables, constraints ->
        val phase = exact.phase
        exact.measuredPhase = phase
        val gapPx = gap.toPx()
        val startPx = start.toPx()
        val endPx = end.toPx()
        val topPx = top.toPx()
        val bottomPx = bottom.toPx()
        val bounded = constraints.hasBoundedWidth
        val room = if (bounded) max(0f, constraints.maxWidth - startPx - endPx) else Float.POSITIVE_INFINITY
        val tall = if (constraints.hasBoundedHeight) max(0, constraints.maxHeight - (topPx + bottomPx).roundToInt()) else Constraints.Infinity
        val loose = Constraints(maxWidth = if (bounded) room.toInt() else Constraints.Infinity, maxHeight = tall)
        val data = measurables.map { it.parentData as? CssExact }
        val flex = if (bounded && !wrap) data.indexOfFirst { it?.flex == true } else -1
        val shrink = if (bounded && !wrap && flex < 0) data.indexOfFirst { it?.shrink == true } else -1
        // A child of a set size both ways ([cssSize]) is measured last, once its exact edges are
        // known, to the pixels they round to: a 36 px button is 94 or 95 at 2.625x, as there.
        val sized = BooleanArray(measurables.size) { index ->
            index != flex && index != shrink && data[index]?.let { !it.fixed.isNaN() && !it.width.isNaN() } == true
        }
        val measured = arrayOfNulls<Placeable>(measurables.size)
        measurables.forEachIndexed { index, measurable -> if (index != flex && index != shrink && !sized[index]) measured[index] = measurable.measure(loose) }
        val widths = FloatArray(measurables.size) {
            when {
                it == flex || it == shrink -> 0f
                sized[it] -> data[it]!!.width
                else -> exactWidth(data[it], measured[it]!!)
            }
        }
        val left = room - widths.sum() - gapPx * (measurables.size - 1)
        if (flex >= 0) widths[flex] = max(0f, left)
        if (shrink >= 0) {
            measured[shrink] = measurables[shrink].measure(loose.copy(maxWidth = max(0, floor(left).toInt())))
            widths[shrink] = exactWidth(data[shrink], measured[shrink]!!)
        }
        val plan = cssRowPlan(widths, gapPx, startPx, endPx, room, constraints.minWidth, constraints.maxWidth, wrap, centre, alignEnd)
        if (flex >= 0) {
            val px = max(0, (plan.exactX[flex] + widths[flex]).roundToInt() - plan.x[flex])
            measured[flex] = measurables[flex].measure(loose.copy(minWidth = px, maxWidth = px))
        }
        exact.width = plan.exactWidth
        exact.reportedWidth = plan.width
        val heights = FloatArray(measurables.size) { if (sized[it]) data[it]!!.fixed else exactHeight(data[it], measured[it]!!) }
        // A minimum set from outside in whole pixels counts as exact.
        val across = cssRowAcross(plan.lineOf, heights, plan.lines, gapPx, topPx, bottomPx, max(minHeight.toPx(), constraints.minHeight.toFloat()), phase, align)
        measurables.forEachIndexed { index, measurable ->
            if (!sized[index]) return@forEachIndexed
            val width = max(0, (plan.exactX[index] + widths[index]).roundToInt() - plan.x[index])
            val height = max(0, (across.phases[index] + heights[index]).roundToInt())
            measured[index] = measurable.measure(Constraints.fixed(width, height))
        }
        val placeables = measured.map { it!! }
        val height = across.height.coerceIn(constraints.minHeight, constraints.maxHeight)
        exact.height = across.exactHeight
        exact.reported = height
        // Where in a pixel each child starts is known only once all are measured.
        data.forEachIndexed { index, it -> it?.givePhase(across.phases[index]) }
        layout(plan.width, height) {
            placeables.forEachIndexed { index, placeable -> placeable.place(plan.x[index], across.y[index]) }
        }
    }
}

/** How a [CssRow] puts each child across its line: `items-center`, `items-end`, `items-start`. */
enum class CssAlign { Center, End, Start }

/** Where a [CssRow] puts its children up and down: each one's top pixel and where in it it starts, and the row's height. */
internal class CssRowAcross(val y: IntArray, val phases: FloatArray, val height: Int, val exactHeight: Float)

/**
 * [CssRow]'s vertical layout, in device px from the row's first pixel, the row's exact top [phase]
 * into it: lines as tall as their tallest child, [gap] apart and [top] / [bottom] inside the row,
 * which is at least [min]; one line is as tall as the row, as a single-line flex container
 * stretches it.
 */
internal fun cssRowAcross(
    lineOf: IntArray,
    heights: FloatArray,
    lines: Int,
    gap: Float,
    top: Float,
    bottom: Float,
    min: Float,
    phase: Float,
    align: CssAlign,
): CssRowAcross {
    val lineHeights = FloatArray(lines)
    heights.indices.forEach { lineHeights[lineOf[it]] = max(lineHeights[lineOf[it]], heights[it]) }
    val exactHeight = max(min, top + lineHeights.sum() + gap * (lines - 1) + bottom)
    if (lines == 1) lineHeights[0] = exactHeight - top - bottom
    val lineTops = FloatArray(lines)
    var at = top
    for (line in 0 until lines) {
        lineTops[line] = at
        at += lineHeights[line] + gap
    }
    val y = IntArray(heights.size)
    val phases = FloatArray(heights.size)
    heights.indices.forEach { index ->
        val line = lineOf[index]
        val free = lineHeights[line] - heights[index]
        val offset = when (align) {
            CssAlign.Center -> free / 2
            CssAlign.End -> free
            CssAlign.Start -> 0f
        }
        val exactTop = phase + lineTops[line] + offset
        y[index] = exactTop.roundToInt()
        phases[index] = exactTop - y[index]
    }
    return CssRowAcross(y, phases, (phase + exactHeight).roundToInt(), exactHeight)
}

/**
 * Where a [CssRow] puts children of these exact widths: each one's line, left pixel and exact
 * left, and the row's width in pixels and exactly.
 */
internal class CssRowPlan(val lineOf: IntArray, val x: IntArray, val exactX: FloatArray, val lines: Int, val width: Int, val exactWidth: Float)

/** [CssRow]'s horizontal layout, in device px: [room] is what its children have between [start] and [end]. */
internal fun cssRowPlan(
    widths: FloatArray,
    gap: Float,
    start: Float,
    end: Float,
    room: Float,
    minWidth: Int,
    maxWidth: Int,
    wrap: Boolean,
    centre: Boolean,
    alignEnd: Boolean,
): CssRowPlan {
    // Each child's line and its exact left edge in that line.
    val lineOf = IntArray(widths.size)
    val lefts = FloatArray(widths.size)
    var lines = 1
    var at = 0f
    var widest = 0f
    widths.indices.forEach { index ->
        if (index > 0 && wrap && at + gap + widths[index] > room) {
            widest = max(widest, at)
            lines++
            at = 0f
        } else if (index > 0) {
            at += gap
        }
        lineOf[index] = lines - 1
        lefts[index] = at
        at += widths[index]
    }
    widest = max(widest, at)
    // A row that wrapped is as wide as it was offered, as a flex container's fit-content width is.
    val content = if (lines > 1) room else widest
    val exactWidth = max(start + content + end, minWidth.toFloat())
    // Seen from the end, a start Chrome rounds half up is a width rounded half down.
    val width = (if (alignEnd) -(-exactWidth).roundToInt() else exactWidth.roundToInt()).coerceIn(minWidth, maxWidth)
    val phase = if (alignEnd) width - exactWidth else 0f
    val shift = if (centre && lines == 1) (exactWidth - start - content - end) / 2 else 0f
    val exactX = FloatArray(widths.size) { phase + start + shift + lefts[it] }
    return CssRowPlan(lineOf, IntArray(widths.size) { exactX[it].roundToInt() }, exactX, lines, width, exactWidth)
}

/**
 * One line of text that a [CssRow] counts at its exact width, the advances of its glyphs added
 * up as Chrome adds them, where Compose measures it to the next whole pixel.
 */
@Composable
fun CssText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val exact = rememberCssExact()
    val laid = remember { arrayOfNulls<TextLayoutResult>(1) }
    Layout({ BasicText(text, style = style, onTextLayout = { laid[0] = it }, softWrap = false, maxLines = 1) }, modifier.cssExact(exact)) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints)
        // The line's own width: Compose's intrinsic widths are rounded up to whole pixels too.
        exact.width = laid[0]?.let { it.getLineRight(0) - it.getLineLeft(0) }?.coerceAtMost(constraints.maxWidth.toFloat()) ?: Float.NaN
        exact.reportedWidth = placeable.width
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}

private data class CssExactElement(val exact: CssExact, val reads: Boolean) : ModifierNodeElement<CssExactNode>() {
    override fun create() = CssExactNode(exact, reads)

    override fun update(node: CssExactNode) {
        node.release()
        node.exact = exact
        node.reads = reads
        if (node.isAttached) node.claim()
    }
}

private class CssExactNode(var exact: CssExact, var reads: Boolean) : Modifier.Node(), ParentDataModifierNode, LayoutModifierNode {
    override fun Density.modifyParentData(parentData: Any?): Any = exact

    // Only so that the column around can ask for its layout node to be measured again.
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    fun claim() {
        if (reads) exact.reader = this
    }

    fun release() {
        if (exact.reader === this) exact.reader = null
    }

    override fun onAttach() = claim()

    override fun onDetach() = release()
}

/**
 * Text whose lines are [lineHeight] CSS pixels apart: a [CssColumn] counts it as its lines times
 * that, as Chrome does, rather than the whole pixels it measures. Held to [max] (`max-h-*`), it
 * counts as that once it reaches it; a row whose other box is [min] tall, taller than one line,
 * counts as that while it measures no taller. Lines [gap] CSS px apart are the rows of a wrapping
 * flex row (`flex-wrap gap-y-*`), as many as fit what it measures.
 */
fun Modifier.cssLines(lineHeight: Float, max: Dp = Dp.Unspecified, min: Dp = Dp.Unspecified, gap: Float = 0f): Modifier =
    this then CssLinesElement(lineHeight, max, min, gap)

private data class CssLinesElement(val lineHeight: Float, val max: Dp, val min: Dp, val gap: Float) : ModifierNodeElement<CssLinesNode>() {
    override fun create() = CssLinesNode(lineHeight, max, min, gap)

    override fun update(node: CssLinesNode) {
        if (node.lineHeight == lineHeight && node.max == max && node.min == min && node.gap == gap) return
        node.lineHeight = lineHeight
        node.max = max
        node.min = min
        node.gap = gap
        node.invalidateMeasurement()
    }
}

private class CssLinesNode(var lineHeight: Float, var max: Dp, var min: Dp, var gap: Float) : Modifier.Node(), LayoutModifierNode, ParentDataModifierNode {
    private val exact = CssExact()

    override fun Density.modifyParentData(parentData: Any?): Any = exact

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        val line = lineHeight.sp.toPx()
        val gapPx = gap.sp.toPx()
        exact.height = when {
            max.isSpecified && placeable.height >= max.roundToPx() -> max.toPx()
            min.isSpecified && placeable.height <= min.roundToPx() -> min.toPx()
            else -> max(1, ((placeable.height + gapPx) / (line + gapPx)).roundToInt()).let { it * line + (it - 1) * gapPx }
        }
        exact.reported = placeable.height
        return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}

/**
 * Text with runs in another face (`font-mono` numbers in a sentence), drawn as Compose lays it out
 * with its lines [lineHeight] CSS px apart, and counted by a [CssColumn] at the height Chrome gives
 * it: a line holding a run in another face is as tall as both faces' boxes together
 * ([PpmFonts.chromeLineBox]), 2 px taller for Monaspace in Geist at 14 px on 21 at 2.625x. Lines
 * after such a line are not moved down by it, so it suits text whose mixed line is its last.
 */
@Composable
fun CssMixedText(text: AnnotatedString, style: TextStyle, lineHeight: Float, modifier: Modifier = Modifier) {
    val exact = rememberCssExact()
    val laid = remember { arrayOfNulls<TextLayoutResult>(1) }
    Layout({ BasicText(text, style = style, onTextLayout = { laid[0] = it }) }, modifier.cssExact(exact)) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints)
        val result = laid[0]
        val px = style.fontSize.toPx()
        val line = lineHeight.sp.toPx()
        val mono = PpmFonts.MONO_ASCENT to PpmFonts.MONO_DESCENT
        val own = if (style.fontFamily == PpmFonts.mono) mono else PpmFonts.SANS_ASCENT to PpmFonts.SANS_DESCENT
        var height = 0f
        if (result == null) height = placeable.height.toFloat()
        else for (index in 0 until result.lineCount) {
            val start = result.getLineStart(index)
            val end = result.getLineEnd(index)
            val mixed = own !== mono && text.spanStyles.any { it.item.fontFamily == PpmFonts.mono && it.start < end && it.end > start }
            height += PpmFonts.chromeLineBox(px, line, if (mixed) listOf(own, mono) else listOf(own))
        }
        exact.cssReport(height, placeable.height)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}

/**
 * A box at least [min] tall in CSS pixels (`min-h-*`) whose content can wrap: a [CssColumn] counts
 * it at [min] exactly while it measures no taller than [min] rounds to, and once its content makes
 * it taller at what it measures — or, when the tallest child of a row lends its exact height
 * ([lend], the [CssExact] of a [CssColumn] in it), at that. Laid out in a [CssColumn] or a
 * [CssRow] it is as tall as the pixels its exact edges round to there, 115 or 116 for 44 px at
 * 2.625x, as Chrome paints it.
 */
fun Modifier.cssMinHeight(min: Dp, lend: CssExact? = null): Modifier = this then CssMinHeightElement(min, lend)

private data class CssMinHeightElement(val min: Dp, val lend: CssExact?) : ModifierNodeElement<CssMinHeightNode>() {
    override fun create() = CssMinHeightNode(min, lend)

    override fun update(node: CssMinHeightNode) {
        if (node.min == min && node.lend === lend) return
        node.min = min
        node.lend = lend
        node.invalidateMeasurement()
    }
}

private class CssMinHeightNode(var min: Dp, var lend: CssExact?) : Modifier.Node(), LayoutModifierNode, ParentDataModifierNode {
    private val exact = CssExact()

    override fun Density.modifyParentData(parentData: Any?): Any = exact

    override fun onAttach() {
        exact.reader = this
    }

    override fun onDetach() {
        if (exact.reader === this) exact.reader = null
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val floor = (exact.cssPhase() + min.toPx()).roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight)
        val placeable = measurable.measure(constraints.copy(minHeight = floor))
        val lent = lend?.takeIf { it.reported >= 0 && !it.height.isNaN() }
        exact.height = when {
            placeable.height <= floor -> min.toPx()
            lent != null -> lent.height + (placeable.height - lent.reported)
            else -> placeable.height.toFloat()
        }
        exact.reported = placeable.height
        return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}

/**
 * A box at most [max] tall in CSS pixels (`max-h-*`, its border included), scrolling what goes
 * past it: a [CssColumn] counts it at the exact height of the [CssColumn] inside that lends
 * [lend], with the whole pixels added around that one (a border), until that reaches [max], and
 * at [max] from there. The column inside is told where in its first pixel the box starts, so its
 * rows round from there, as Chrome rounds a list's rows from where the list starts.
 */
fun Modifier.cssMaxHeight(max: Dp, lend: CssExact): Modifier = this then CssMaxHeightElement(max, lend)

private data class CssMaxHeightElement(val max: Dp, val lend: CssExact) : ModifierNodeElement<CssMaxHeightNode>() {
    override fun create() = CssMaxHeightNode(max, lend)

    override fun update(node: CssMaxHeightNode) {
        if (node.max == max && node.lend === lend) return
        node.max = max
        node.lend = lend
        node.invalidateMeasurement()
    }
}

private class CssMaxHeightNode(var max: Dp, var lend: CssExact) : Modifier.Node(), LayoutModifierNode, ParentDataModifierNode {
    private val exact = CssExact()

    override fun Density.modifyParentData(parentData: Any?): Any = exact

    override fun onAttach() {
        exact.reader = this
    }

    override fun onDetach() {
        if (exact.reader === this) exact.reader = null
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val phase = exact.cssPhase()
        // Whatever whole pixels lie between this box's top and the column's leave the phase as it is.
        lend.givePhase(phase)
        val cap = (phase + max.toPx()).roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight)
        val placeable = measurable.measure(constraints.copy(maxHeight = cap))
        val inner = if (lend.reported >= 0 && !lend.height.isNaN()) lend.height + (placeable.height - lend.reported) else placeable.height.toFloat()
        exact.height = minOf(inner, max.toPx())
        exact.reported = placeable.height
        return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}

/**
 * The child of a [CssColumn] that gives way (`min-h-0 flex-1`): held to the height the others
 * leave it, a scrolling body under a sheet's head. Children under it must be of a set height
 * ([cssHeight]), which the column counts before it measures them. With [grow] it is as tall as
 * that however little it asks for (`flex-1` filling a column of set height, a chat's transcript),
 * and the children under it may be any height: the column measures them first.
 */
fun Modifier.cssFlex(grow: Boolean = false): Modifier = this then CssFlexElement(grow)

private data class CssFlexElement(val grow: Boolean) : ModifierNodeElement<CssFlexNode>() {
    override fun create() = CssFlexNode(grow)

    override fun update(node: CssFlexNode) {
        if (node.grow == grow) return
        node.grow = grow
        node.invalidateParentData()
    }
}

private class CssFlexNode(var grow: Boolean) : Modifier.Node(), ParentDataModifierNode {
    private val own = CssExact()

    override fun Density.modifyParentData(parentData: Any?): Any = (parentData as? CssExact ?: own).also {
        it.flex = true
        it.grow = grow
    }
}

/**
 * The child of a [CssRow] that shrinks (`min-w-0`, a `truncate` label): measured after the
 * others, at most as wide as they leave it, so it is the one that ellipsizes on a narrow chat.
 */
fun Modifier.cssShrink(): Modifier = this then CssShrinkElement

private data object CssShrinkElement : ModifierNodeElement<CssShrinkNode>() {
    override fun create() = CssShrinkNode()

    override fun update(node: CssShrinkNode) = Unit
}

private class CssShrinkNode : Modifier.Node(), ParentDataModifierNode {
    private val own = CssExact()

    override fun Density.modifyParentData(parentData: Any?): Any = (parentData as? CssExact ?: own).also { it.shrink = true }
}

/**
 * Text set [lineHeight] CSS pixels a line in a [fontSize] px font, laid out as Chrome lays it out
 * even where the line is shorter than the font (`leading-none`): Compose closes such lines up but
 * pads the text so no glyph is cut, which leaves the box the font's height — an 18 px title 23.5
 * px tall, and everything under it 5.5 px low. This box is its lines' height, its first baseline
 * where Chrome puts it ([PpmFonts.chromeBelowBaseline]), the glyphs reaching out of it as there;
 * a [CssColumn] counts it at that height exactly.
 */
fun Modifier.cssTightLines(fontSize: Float, lineHeight: Float, kind: FontKind = FontKind.SANS): Modifier =
    this then CssTightLinesElement(fontSize, lineHeight, kind)

private data class CssTightLinesElement(val fontSize: Float, val lineHeight: Float, val kind: FontKind) : ModifierNodeElement<CssTightLinesNode>() {
    override fun create() = CssTightLinesNode(fontSize, lineHeight, kind)

    override fun update(node: CssTightLinesNode) {
        if (node.fontSize == fontSize && node.lineHeight == lineHeight && node.kind == kind) return
        node.fontSize = fontSize
        node.lineHeight = lineHeight
        node.kind = kind
        node.invalidateMeasurement()
    }
}

private class CssTightLinesNode(var fontSize: Float, var lineHeight: Float, var kind: FontKind) : Modifier.Node(), LayoutModifierNode, ParentDataModifierNode {
    private val exact = CssExact()

    override fun Density.modifyParentData(parentData: Any?): Any = exact

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val line = lineHeight.sp.toPx()
        val first = placeable[FirstBaseline]
        val known = first != AlignmentLine.Unspecified
        val lines = if (known) ((placeable[LastBaseline] - first) / line).roundToInt() + 1 else 1
        val sans = kind == FontKind.SANS
        val below = PpmFonts.chromeBelowBaseline(
            fontSize.sp.toPx(),
            line,
            if (sans) PpmFonts.SANS_ASCENT else PpmFonts.MONO_ASCENT,
            if (sans) PpmFonts.SANS_DESCENT else PpmFonts.MONO_DESCENT,
        )
        exact.height = lines * line
        val height = exact.height.roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight)
        exact.reported = height
        return layout(placeable.width, height) { placeable.place(0, if (known) (line - below - first).roundToInt() else 0) }
    }
}

/**
 * The items of a lazy list stacked as Chrome stacks blocks [gap] apart: each item's exact height,
 * as a [CssColumn] counts it, added to the exact tops above it, and each item as tall as the pixels
 * from its rounded top to the next one's, its gap included. Rounded an item at a time, 43.3 px
 * lines and 10.5 px gaps put every other card a pixel lower than the web's.
 *
 * An item measured before the one above it (a list opened at its end, rows scrolled in from above)
 * starts on a whole pixel, as in a plain list, until that one is measured and it is measured again.
 */
class CssLazyStack internal constructor(internal val gap: Float) {
    internal val tops = HashMap<Int, Float>()
    internal val heights = HashMap<Int, Float>()
}

@Composable
fun rememberCssLazyStack(gap: Dp): CssLazyStack {
    val px = with(LocalDensity.current) { gap.toPx() }
    return remember(px) { CssLazyStack(px) }
}

/** The [index]th item of a lazy list laid out by [stack], its content at the top; the [last] has no gap under it. */
fun Modifier.cssLazyItem(stack: CssLazyStack, index: Int, last: Boolean): Modifier = this then CssLazyItemElement(stack, index, last)

private data class CssLazyItemElement(val stack: CssLazyStack, val index: Int, val last: Boolean) : ModifierNodeElement<CssLazyItemNode>() {
    override fun create() = CssLazyItemNode(stack, index, last)

    override fun update(node: CssLazyItemNode) {
        if (node.stack === stack && node.index == index && node.last == last) return
        node.stack = stack
        node.index = index
        node.last = last
        node.invalidateMeasurement()
    }
}

private class CssLazyItemNode(var stack: CssLazyStack, var index: Int, var last: Boolean) : Modifier.Node(), LayoutModifierNode {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        val data = measurable.parentData as? CssExact
        val exact = when {
            data == null || data.height.isNaN() -> placeable.height.toFloat()
            !data.fixed.isNaN() -> data.fixed
            data.reported >= 0 -> data.height + (placeable.height - data.reported)
            else -> data.height
        }
        val above = stack.tops[index - 1]?.let { top -> stack.heights[index - 1]?.let { top + it + stack.gap } }
        val top = above ?: stack.tops[index] ?: 0f
        stack.tops[index] = top
        stack.heights[index] = exact
        val bottom = top + exact + if (last) 0f else stack.gap
        val height = bottom.roundToInt() - top.roundToInt()
        return layout(placeable.width, height) { placeable.place(0, 0) }
    }
}

/**
 * A box [height] tall in CSS pixels. In a [CssColumn] it takes the pixels its exact edges round
 * to there — 94 or 95 for 36 px at 2.625x, as Chrome paints it — and the column counts the exact
 * height; elsewhere it is [height] rounded. It must be the box's outermost size, nothing that
 * adds height around it.
 */
fun Modifier.cssHeight(height: Dp): Modifier = this then CssHeightElement(height)

private data class CssHeightElement(val height: Dp) : ModifierNodeElement<CssHeightNode>() {
    override fun create() = CssHeightNode(height)

    override fun update(node: CssHeightNode) {
        if (node.height == height) return
        node.height = height
        node.invalidateMeasurement()
    }
}

private class CssHeightNode(var height: Dp) : Modifier.Node(), LayoutModifierNode, ParentDataModifierNode {
    override fun Density.modifyParentData(parentData: Any?): Any = CssExact(height.toPx())

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val px = if (constraints.hasFixedHeight) constraints.maxHeight else height.roundToPx().coerceIn(constraints.minHeight, constraints.maxHeight)
        val placeable = measurable.measure(constraints.copy(minHeight = px, maxHeight = px))
        return layout(placeable.width, px) { placeable.place(0, 0) }
    }
}

/**
 * A text field's box: [padding] above and below lines [lineHeight] CSS px apart, held to [max]. A
 * [CssRow] or [CssColumn] counts it at the padding and its lines exactly — 31.5 + 60 px a line on
 * a phone, where Compose rounds the padding to 16 — and at [max] once it reaches that.
 */
fun Modifier.cssFieldLines(padding: Dp, lineHeight: Float, max: Dp): Modifier = this then CssFieldLinesElement(padding, lineHeight, max)

private data class CssFieldLinesElement(val padding: Dp, val lineHeight: Float, val max: Dp) : ModifierNodeElement<CssFieldLinesNode>() {
    override fun create() = CssFieldLinesNode(padding, lineHeight, max)

    override fun update(node: CssFieldLinesNode) {
        if (node.padding == padding && node.lineHeight == lineHeight && node.max == max) return
        node.padding = padding
        node.lineHeight = lineHeight
        node.max = max
        node.invalidateMeasurement()
    }
}

private class CssFieldLinesNode(var padding: Dp, var lineHeight: Float, var max: Dp) : Modifier.Node(), LayoutModifierNode, ParentDataModifierNode {
    private val exact = CssExact()

    override fun Density.modifyParentData(parentData: Any?): Any = exact

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        val line = lineHeight.sp.toPx()
        val lines = max(1, ((placeable.height - 2 * padding.roundToPx()) / line).roundToInt())
        exact.height = minOf(max.toPx(), 2 * padding.toPx() + lines * line)
        exact.reported = placeable.height
        return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}

/**
 * A box [width] wide in CSS pixels, which a [CssRow] counts at exactly that — a 14 px icon is
 * 36.75 px at 2.625x, where Compose sizes it 37 and a row of four buttons came out a pixel wider
 * than the web's. It measures [width] rounded, and must be the box's outermost width.
 */
fun Modifier.cssWidth(width: Dp): Modifier = this then CssWidthElement(width)

private data class CssWidthElement(val width: Dp) : ModifierNodeElement<CssWidthNode>() {
    override fun create() = CssWidthNode(width)

    override fun update(node: CssWidthNode) {
        if (node.width == width) return
        node.width = width
        node.invalidateMeasurement()
    }
}

private class CssWidthNode(var width: Dp) : Modifier.Node(), LayoutModifierNode, ParentDataModifierNode {
    override fun Density.modifyParentData(parentData: Any?): Any = CssExact().also {
        it.width = width.toPx()
        it.reportedWidth = width.roundToPx()
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val px = width.roundToPx().coerceIn(constraints.minWidth, constraints.maxWidth)
        val placeable = measurable.measure(constraints.copy(minWidth = px, maxWidth = px))
        return layout(px, placeable.height) { placeable.place(0, 0) }
    }
}

/**
 * A square box [size] on each side in CSS pixels — a `size-9` button, a `size-3.5` icon — which a
 * [CssRow] counts at exactly that both ways, 94.5 px for 36 at 2.625x where Compose sizes it 95.
 * In a [CssRow] or a [CssColumn] it takes the pixels its exact edges round to there. It must be
 * the box's outermost size: the row gives it those pixels as its whole size.
 */
fun Modifier.cssSize(size: Dp): Modifier = this then CssSizeElement(size, icon = false)

/**
 * An icon [size] on each side in CSS pixels, laid out as [cssSize] lays out a box, and drawn
 * [size] rounded on each side from the box's corner whatever pixels its edges round to: Chrome
 * draws an `<svg>` at its exact size, so squeezing a 31.5 px icon into the 31 px its rounded
 * edges leave would draw it a pixel narrower and half a pixel in.
 */
fun Modifier.cssIcon(size: Dp): Modifier = this then CssSizeElement(size, icon = true)

private data class CssSizeElement(val size: Dp, val icon: Boolean) : ModifierNodeElement<CssSizeNode>() {
    override fun create() = CssSizeNode(size, icon)

    override fun update(node: CssSizeNode) {
        if (node.size == size && node.icon == icon) return
        node.size = size
        node.icon = icon
        node.invalidateMeasurement()
        node.invalidateParentData()
    }
}

private class CssSizeNode(var size: Dp, var icon: Boolean) : Modifier.Node(), LayoutModifierNode, ParentDataModifierNode {
    override fun Density.modifyParentData(parentData: Any?): Any = CssExact(size.toPx()).also {
        it.width = size.toPx()
        it.reportedWidth = size.roundToPx()
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val width = size.roundToPx().coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = if (constraints.hasFixedHeight) constraints.maxHeight else size.roundToPx().coerceIn(constraints.minHeight, constraints.maxHeight)
        val side = size.roundToPx()
        val placeable = measurable.measure(if (icon) Constraints.fixed(side, side) else Constraints.fixed(width, height))
        return layout(width, height) { placeable.place(0, 0) }
    }
}
