package com.hienlh.ppm.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage

/**
 * Whether [color] is drawn in the right third of this row: the check a picker's chosen row ends in, drawn in the accent
 * colour. Its fill cannot say which row is chosen, because the chosen row's fill is the popover's own in every theme.
 * Needs native graphics.
 */
internal fun SemanticsNodeInteraction.endsInAccent(color: Color, tolerance: Float = 0.06f): Boolean {
    val image = captureToImage().toPixelMap()
    for (x in image.width * 2 / 3 until image.width) {
        for (y in 0 until image.height) {
            val pixel = image[x, y]
            if (kotlin.math.abs(pixel.red - color.red) <= tolerance && kotlin.math.abs(pixel.green - color.green) <= tolerance &&
                kotlin.math.abs(pixel.blue - color.blue) <= tolerance
            ) return true
        }
    }
    return false
}
