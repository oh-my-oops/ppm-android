package com.hienlh.ppm.design

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * A checkbox as Chrome paints one (`NativeThemeBase::PaintCheckbox`) in its light colours, the web
 * setting no `color-scheme`, filling the space it is given. Enabled: a white box in a 1px grey
 * border rounded 2px (darker under a mouse), or ticked, filled with [accent] (`accent-color`;
 * Chrome's blue without one) and the tick in whichever of white and near-black stands out on it.
 * Disabled, whatever the accent: a grey layer that lightens whatever is under it, the faded white
 * background, then a faded grey border, or ticked, a faded grey fill and tick.
 */
@Composable
fun ChromeCheckbox(checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, accent: Color? = null, hovered: Boolean = false) {
    Canvas(modifier) {
        val line = cssBorderPx(1.dp).toFloat()
        val radius = CornerRadius(2.dp.toPx())
        val inset = line * 0.2f
        val inner = Size(size.width - 2 * inset, size.height - 2 * inset)
        val fill = accent ?: CHROME_ACCENT
        if (!enabled) drawRoundRect(LIGHTEN_LAYER, Offset(inset, inset), inner, radius)
        drawRoundRect(if (enabled) Color.White else DISABLED_WHITE, Offset(inset, inset), inner, radius)
        if (checked) {
            drawRoundRect(if (enabled) fill else DISABLED_GREY, Offset(inset, inset), inner, radius)
            val w = size.width
            val h = size.height
            val tick = Path().apply {
                moveTo(w * 0.2f, h * 0.5f)
                lineTo(w * 0.4f, h * 0.7f)
                lineTo(w * 0.8f, h * 0.2f)
            }
            val ink = when {
                !enabled -> DISABLED_WHITE
                // `GetColorWithMaxContrast`: white on a dark accent, Google Grey 900 on a light one.
                fill.luminance() < LUMINANCE_MIDPOINT -> Color.White
                else -> GREY_900
            }
            drawPath(tick, ink, style = Stroke(h * 0.16f, cap = StrokeCap.Butt, join = StrokeJoin.Miter))
        } else {
            val border = when {
                !enabled -> DISABLED_GREY
                hovered -> BORDER_HOVERED
                else -> BORDER
            }
            drawRoundRect(border, Offset(line / 2, line / 2), Size(size.width - line, size.height - line), radius, style = Stroke(line))
        }
    }
}

private val CHROME_ACCENT = Color(0xFF0075FF)
private val BORDER = Color(0xFF767676)
private val BORDER_HOVERED = Color(0xFF4F4F4F)
private val GREY_900 = Color(0xFF202124)
private val LIGHTEN_LAYER = Color(169, 169, 169, 0x33)
private val DISABLED_GREY = Color(118, 118, 118, 0x4D)
private val DISABLED_WHITE = Color(255, 255, 255, 0x99)

/** The relative luminance as far from white's contrast as from Google Grey 900's. */
private const val LUMINANCE_MIDPOINT = 0.211692f
