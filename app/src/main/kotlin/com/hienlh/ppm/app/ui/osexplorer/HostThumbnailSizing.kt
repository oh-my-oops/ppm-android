package com.hienlh.ppm.app.ui.osexplorer

import kotlin.math.roundToInt

internal fun hostThumbnailSize(width: Int, height: Int, pixels: Int): Pair<Int, Int> {
    require(width > 0 && height > 0 && pixels > 0) { "Invalid image bounds" }
    val scale = minOf(1.0, pixels.toDouble() / maxOf(width, height))
    return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
}

internal fun hostThumbnailSample(width: Int, height: Int, pixels: Int): Int {
    require(width > 0 && height > 0 && pixels > 0) { "Invalid image bounds" }
    var sample = 1
    while (maxOf(width, height) / sample / 2 >= pixels) sample *= 2
    return sample
}
