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

    /** The child that gives way ([cssFlex]). */
    internal var flex = false
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
 * its bound, and is no taller than it asks to be.
 */
@Composable
fun CssColumn(
    modifier: Modifier = Modifier,
    gap: Dp = 0.dp,
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
    exact: CssExact = rememberCssExact(),
    content: @Composable () -> Unit,
) {
    Layout(content, modifier.then(CssExactElement(exact, reads = true))) { measurables, constraints ->
        val phase = exact.phase
        exact.measuredPhase = phase
        val gapPx = gap.toPx()
        val loose = Constraints(maxWidth = constraints.maxWidth, maxHeight = Constraints.Infinity)
        val exacts = measurables.map { it.parentData as? CssExact }
        val flex = if (constraints.hasBoundedHeight) exacts.indexOfFirst { it?.flex == true } else -1
        // Where the child that gives way has to end: above the gaps and set heights under it.
        var limit = constraints.maxHeight - bottom.toPx()
        if (flex >= 0) for (index in flex + 1 until measurables.size) limit -= gapPx + (exacts[index]?.fixed?.takeUnless { it.isNaN() } ?: 0f)
        val placeables = ArrayList<Placeable>(measurables.size)
        val tops = IntArray(measurables.size)
        var at = phase + top.toPx()
        var width = constraints.minWidth
        measurables.forEachIndexed { index, measurable ->
            if (index > 0) at += gapPx
            val y = at.roundToInt()
            val data = exacts[index]
            if (data != null) {
                data.phase = at - y
                val reader = data.reader
                if (reader != null && data.measuredPhase != data.phase) reader.invalidateMeasurement()
            }
            val placeable = if (data != null && !data.fixed.isNaN()) {
                // A box of a set height takes the pixels between its two rounded edges.
                val height = (at + data.fixed).roundToInt() - y
                measurable.measure(loose.copy(minHeight = height, maxHeight = height))
            } else if (index == flex) {
                measurable.measure(loose.copy(maxHeight = max(0, limit.roundToInt() - y)))
            } else {
                measurable.measure(loose)
            }
            at += when {
                data == null || data.height.isNaN() -> placeable.height.toFloat()
                !data.fixed.isNaN() -> data.fixed
                data.reported >= 0 -> data.height + (placeable.height - data.reported)
                else -> data.height
            }
            placeables += placeable
            tops[index] = y
            width = max(width, placeable.width)
        }
        at += bottom.toPx()
        exact.height = at - phase
        val height = at.roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight)
        exact.reported = height
        layout(width, height) {
            placeables.forEachIndexed { index, placeable -> placeable.place(0, tops[index]) }
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
 * counts as that while it measures no taller.
 */
fun Modifier.cssLines(lineHeight: Float, max: Dp = Dp.Unspecified, min: Dp = Dp.Unspecified): Modifier = this then CssLinesElement(lineHeight, max, min)

private data class CssLinesElement(val lineHeight: Float, val max: Dp, val min: Dp) : ModifierNodeElement<CssLinesNode>() {
    override fun create() = CssLinesNode(lineHeight, max, min)

    override fun update(node: CssLinesNode) {
        if (node.lineHeight == lineHeight && node.max == max && node.min == min) return
        node.lineHeight = lineHeight
        node.max = max
        node.min = min
        node.invalidateMeasurement()
    }
}

private class CssLinesNode(var lineHeight: Float, var max: Dp, var min: Dp) : Modifier.Node(), LayoutModifierNode, ParentDataModifierNode {
    private val exact = CssExact()

    override fun Density.modifyParentData(parentData: Any?): Any = exact

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        val line = lineHeight.sp.toPx()
        exact.height = when {
            max.isSpecified && placeable.height >= max.roundToPx() -> max.toPx()
            min.isSpecified && placeable.height <= min.roundToPx() -> min.toPx()
            else -> max(1, (placeable.height / line).roundToInt()) * line
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
 * ([lend], the [CssExact] of a [CssColumn] in it), at that.
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

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val floor = min.roundToPx().coerceIn(constraints.minHeight, constraints.maxHeight)
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
 * The child of a [CssColumn] that gives way (`min-h-0 flex-1`): held to the height the others
 * leave it, a scrolling body under a sheet's head. Children under it must be of a set height
 * ([cssHeight]), which the column counts before it measures them.
 */
fun Modifier.cssFlex(): Modifier = this then CssFlexElement

private data object CssFlexElement : ModifierNodeElement<CssFlexNode>() {
    override fun create() = CssFlexNode()

    override fun update(node: CssFlexNode) = Unit
}

private class CssFlexNode : Modifier.Node(), ParentDataModifierNode {
    private val own = CssExact()

    override fun Density.modifyParentData(parentData: Any?): Any = (parentData as? CssExact ?: own).also { it.flex = true }
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
