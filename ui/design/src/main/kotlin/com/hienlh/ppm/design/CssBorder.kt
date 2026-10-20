package com.hienlh.ppm.design

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSimple
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import kotlin.math.floor
import kotlin.math.max

/**
 * A border [width] CSS px wide in device pixels, as Chrome draws and lays it out: floored to
 * whole pixels, and 1 when that would be 0 ("snap as a border width", css-values-4). At the
 * phone's 2.625 a 1 px border is 2 device px and a 2 px one 5; `getComputedStyle` answers
 * 0.761905px for the first.
 */
fun Density.cssBorderPx(width: Dp): Int {
    val px = width.toPx()
    return if (px <= 0f) 0 else max(1, floor(px + 0.001f).toInt())
}

/**
 * A CSS border on the sides asked for: drawn [width] wide as Chrome snaps it (see [cssBorderPx]),
 * and taking that much room inside the box, as `box-sizing: border-box` gives it (Tailwind's
 * preflight puts that on everything) — so a padding after it is the CSS padding alone. Painted
 * where CSS paints it, over the background and under the content. A rounded [shape] is drawn on
 * all four sides; a single side is drawn straight.
 *
 * Compose's own `border` ceils the stroke instead (3 px for 1 dp at 2.625) and takes no room.
 */
fun Modifier.cssBorder(color: Color, shape: Shape = RectangleShape, width: Dp = 1.dp, sides: CssSides = CssSides.All): Modifier {
    fun on(side: Boolean) = if (side) width else 0.dp
    // Two nodes: one that lays out and draws as well draws inside its own inset (measured).
    return this then CssBorderElement(color, shape, width, sides) then
        CssBorderInsetElement(on(sides.start), on(sides.top), on(sides.end), on(sides.bottom))
}

/** Which sides a [cssBorder] is on: `border`, `border-t`, `border-x`… */
@JvmInline
value class CssSides private constructor(private val bits: Int) {
    val top get() = bits and 1 != 0
    val end get() = bits and 2 != 0
    val bottom get() = bits and 4 != 0
    val start get() = bits and 8 != 0

    operator fun plus(other: CssSides) = CssSides(bits or other.bits)

    companion object {
        val Top = CssSides(1)
        val End = CssSides(2)
        val Bottom = CssSides(4)
        val Start = CssSides(8)
        val Horizontal = Start + End
        val Vertical = Top + Bottom
        val All = Horizontal + Vertical
    }
}

/**
 * The room CSS borders of these widths take inside a box, snapped as [cssBorderPx] snaps them,
 * for a border drawn by hand. A side of 0 takes none.
 */
fun Modifier.cssBorderInset(start: Dp = 0.dp, top: Dp = 0.dp, end: Dp = 0.dp, bottom: Dp = 0.dp): Modifier =
    this then CssBorderInsetElement(start, top, end, bottom)

/** [cssBorderInset] on every side. */
fun Modifier.cssBorderInset(all: Dp): Modifier = cssBorderInset(all, all, all, all)

private data class CssBorderInsetElement(val start: Dp, val top: Dp, val end: Dp, val bottom: Dp) : ModifierNodeElement<CssBorderInsetNode>() {
    override fun create() = CssBorderInsetNode(start, top, end, bottom)

    override fun update(node: CssBorderInsetNode) {
        node.start = start
        node.top = top
        node.end = end
        node.bottom = bottom
        node.invalidateMeasurement()
    }
}

private class CssBorderInsetNode(var start: Dp, var top: Dp, var end: Dp, var bottom: Dp) : Modifier.Node(), LayoutModifierNode {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val left = cssBorderPx(start)
        val horizontal = left + cssBorderPx(end)
        val above = cssBorderPx(top)
        val vertical = above + cssBorderPx(bottom)
        val placeable = measurable.measure(constraints.offset(-horizontal, -vertical))
        return layout(constraints.constrainWidth(placeable.width + horizontal), constraints.constrainHeight(placeable.height + vertical)) {
            placeable.placeRelative(left, above)
        }
    }
}

private data class CssBorderElement(val color: Color, val shape: Shape, val width: Dp, val sides: CssSides) : ModifierNodeElement<CssBorderNode>() {
    override fun create() = CssBorderNode(color, shape, width, sides)

    override fun update(node: CssBorderNode) {
        node.color = color
        node.shape = shape
        node.width = width
        node.sides = sides
        node.invalidateDraw()
    }
}

private class CssBorderNode(var color: Color, var shape: Shape, var width: Dp, var sides: CssSides) : Modifier.Node(), DrawModifierNode {
    override fun ContentDrawScope.draw() {
        if (color.alpha > 0f) drawBorder()
        drawContent()
    }

    private fun DrawScope.drawBorder() {
        val w = cssBorderPx(width).toFloat()
        if (w <= 0f || size.minDimension <= 0f) return
        val all = sides == CssSides.All
        when (val outline = shape.createOutline(size, layoutDirection, this)) {
            is Outline.Rounded -> if (all) drawRing(outline.roundRect, w) else drawSides(w)
            is Outline.Generic -> if (all) {
                clipPath(outline.path, ClipOp.Intersect) { drawPath(outline.path, color, style = Stroke(2 * w)) }
            } else {
                drawSides(w)
            }
            is Outline.Rectangle -> drawSides(w)
        }
    }

    /**
     * CSS's rounded border, between the outer curve and one [w] in whose radii are [w] less: a
     * stroke along the middle of the two. Equal corners take the round rect's own stroke, which
     * stays anti-aliased where a filled ring path came out stepped (measured on the emulator).
     */
    private fun DrawScope.drawRing(outer: RoundRect, w: Float) {
        val half = w / 2
        fun CornerRadius.mid() = CornerRadius(max(0f, x - half), max(0f, y - half))
        val mid = RoundRect(
            outer.left + half, outer.top + half, outer.right - half, outer.bottom - half,
            outer.topLeftCornerRadius.mid(), outer.topRightCornerRadius.mid(),
            outer.bottomRightCornerRadius.mid(), outer.bottomLeftCornerRadius.mid(),
        )
        if (mid.isSimple) {
            drawRoundRect(color, Offset(mid.left, mid.top), Size(mid.width, mid.height), mid.topLeftCornerRadius, style = Stroke(w))
        } else {
            drawPath(Path().apply { addRoundRect(mid) }, color, style = Stroke(w))
        }
    }

    private fun DrawScope.drawSides(w: Float) {
        val rtl = layoutDirection == LayoutDirection.Rtl
        val left = if (rtl) sides.end else sides.start
        val right = if (rtl) sides.start else sides.end
        if (sides.top) drawRect(color, Offset.Zero, Size(size.width, w))
        if (sides.bottom) drawRect(color, Offset(0f, size.height - w), Size(size.width, w))
        // Between those, so that a translucent colour is not painted twice in a corner.
        val from = if (sides.top) w else 0f
        val tall = size.height - from - if (sides.bottom) w else 0f
        if (left) drawRect(color, Offset(0f, from), Size(w, tall))
        if (right) drawRect(color, Offset(size.width - w, from), Size(w, tall))
    }
}
