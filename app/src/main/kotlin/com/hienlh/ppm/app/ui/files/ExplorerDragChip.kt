package com.hienlh.ppm.app.ui.files

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.view.View
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.ThemeTokens
import com.hienlh.ppm.design.ppmText
import kotlin.math.ceil
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Canvas as ComposeCanvas

/**
 * The image a drag of tree rows is held by, drawn as `drag-ghost.ts` styles its ghost: a chip with the entry's name (or "N items")
 * in `500 12px/1.4` of the text colour on `--panel-2`, `padding: 4px 10px`, a 1px `--border`, `border-radius: 6px` and `box-shadow:
 * 0 2px 8px rgba(0,0,0,.35)`. The system's sans-serif, which `system-ui` names: the ghost sits in `document.body`, where the
 * explorer skins' `--x-font` does not reach. The browser holds it 12 px in from its corner, and [touch] is where that is in
 * [bitmap], which has [SHADOW_MARGIN_DP] round the chip for the shadow to fall in.
 *
 * Drawn once, into a bitmap: the platform draws a drag shadow onto its own surface, which may be a hardware canvas, where a
 * shadow layer is not drawn, and a bitmap is a canvas that always draws it.
 */
internal class ExplorerDragChip private constructor(val bitmap: Bitmap, val touch: Point) {
    companion object {
        const val SHADOW_MARGIN_DP = 10f

        /** `setDragImage(ghost, 12, 12)`. */
        private const val TOUCH_DP = 12f

        /** Skia's blur sigma for a shadow layer of radius r is `0.57735 r + 0.5`; CSS's is half the blur radius (8 px, so 4). */
        private const val SKIA_SIGMA_SLOPE = 0.57735f
        private const val SKIA_SIGMA_OFFSET = 0.5f
        private val SHADOW = Color(0f, 0f, 0f, 0.35f)

        fun of(label: String, measurer: TextMeasurer, density: Density, tokens: ThemeTokens): ExplorerDragChip {
            val text = measurer.measure(
                AnnotatedString(label), explorerDragChipTextStyle(tokens), overflow = TextOverflow.Clip, softWrap = false, maxLines = 1,
            )
            fun px(dp: Float) = dp * density.density
            val margin = ceil(px(SHADOW_MARGIN_DP))
            val border = px(1f)
            val radius = px(6f)
            val padX = px(10f)
            val padY = px(4f)
            val chipWidth = text.size.width + 2 * (padX + border)
            val chipHeight = text.size.height + 2 * (padY + border)
            val width = ceil(chipWidth + 2 * margin).toInt()
            val height = ceil(chipHeight + 2 * margin).toInt()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            CanvasDrawScope().draw(density, LayoutDirection.Ltr, ComposeCanvas(Canvas(bitmap)), Size(width.toFloat(), height.toFloat())) {
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = tokens.panel2.color.toArgb()
                    setShadowLayer((px(4f) - SKIA_SIGMA_OFFSET) / SKIA_SIGMA_SLOPE, 0f, px(2f), SHADOW.toArgb())
                }
                drawIntoCanvas { it.nativeCanvas.drawRoundRect(RectF(margin, margin, margin + chipWidth, margin + chipHeight), radius, radius, fill) }
                // A CSS border is inside the box: the stroke runs along the path inset by half its width.
                drawRoundRect(
                    tokens.border.color, Offset(margin + border / 2, margin + border / 2), Size(chipWidth - border, chipHeight - border),
                    CornerRadius(radius - border / 2), Stroke(border),
                )
                drawText(text, topLeft = Offset(margin + border + padX, margin + border + padY))
            }
            val touch = (margin + px(TOUCH_DP)).roundToInt()
            return ExplorerDragChip(bitmap, Point(touch, touch))
        }
    }
}

/** The chip's words: `500 12px/1.4` of the text colour, in the system's sans-serif. */
internal fun explorerDragChipTextStyle(tokens: ThemeTokens): TextStyle =
    ppmText(12f, FontWeight.Medium, FontKind.SYSTEM, lineHeight = 16.8f).copy(color = tokens.text.color)

/** Hands [chip] to Android as the drag's shadow, held where the browser holds its ghost. */
internal class ExplorerDragShadow(private val chip: ExplorerDragChip) : View.DragShadowBuilder() {
    override fun onProvideShadowMetrics(size: Point, touch: Point) {
        size.set(chip.bitmap.width, chip.bitmap.height)
        touch.set(chip.touch.x, chip.touch.y)
    }

    override fun onDrawShadow(canvas: Canvas) {
        canvas.drawBitmap(chip.bitmap, 0f, 0f, null)
    }
}
