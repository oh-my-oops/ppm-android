package com.hienlh.ppm.app.ui

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where the lightbox's picture is (`ImageTransform` in use-image-transform.ts). [scale] is of the
 * picture's laid-out size, so 1 is "fits"; [x] and [y] are its pan from the middle of the stage;
 * [rotation] is a multiple of 90, always 0, 90, 180 or 270, applied after the mirroring.
 */
internal data class ViewTransform(
    val scale: Float = 1f,
    val x: Float = 0f,
    val y: Float = 0f,
    val rotation: Int = 0,
    val flipX: Boolean = false,
    val flipY: Boolean = false,
) {
    /** The readout under the picture: the zoom as a percentage of the fitted size. */
    val percent: Int get() = (scale * 100).roundToInt()

    val quarterTurned: Boolean get() = rotation % 180 == 90
}

/**
 * The picture's laid-out size ([width] × [height], which no transform changes), the stage's, and
 * the file's own width in pixels, which is one per dp at "actual size" as it is one per CSS pixel
 * on the web.
 */
internal data class StageLayout(val width: Float, val height: Float, val stageWidth: Float, val stageHeight: Float, val naturalWidth: Float)

internal const val MIN_SCALE = 0.1f
internal const val MAX_SCALE = 20f

/** Where a double tap takes a picture at its fitted size. */
internal const val DOUBLE_TAP_SCALE = 2.5f

/** How far the picture may travel either way before its edge crosses the stage's. */
internal fun ViewTransform.panLimits(layout: StageLayout): Pair<Float, Float> {
    val w = (if (quarterTurned) layout.height else layout.width) * scale
    val h = (if (quarterTurned) layout.width else layout.height) * scale
    return max(0f, (w - layout.stageWidth) / 2) to max(0f, (h - layout.stageHeight) / 2)
}

/** The pan pulled back inside the limits, as after every change of scale or turn (`settle`). */
internal fun ViewTransform.settled(layout: StageLayout): ViewTransform {
    val (maxX, maxY) = panLimits(layout)
    // No room to move is the middle: a plain 0, never the -0 that coercing a negative pan gives.
    fun held(at: Float, limit: Float) = if (limit == 0f) 0f else at.coerceIn(-limit, limit)
    return copy(x = held(x, maxX), y = held(y, maxY))
}

/** Moved by a drag or a trackpad's scroll, as far as the limits let it. */
internal fun ViewTransform.panned(dx: Float, dy: Float, layout: StageLayout): ViewTransform = copy(x = x + dx, y = y + dy).settled(layout)

/**
 * Zoomed to [next] while one point of the picture holds still (`zoomAt`): [originX] and [originY]
 * are that point from the stage's middle, which is the middle itself by default.
 */
internal fun ViewTransform.zoomedTo(next: Float, layout: StageLayout, originX: Float = 0f, originY: Float = 0f): ViewTransform {
    val target = next.coerceIn(MIN_SCALE, MAX_SCALE)
    val ratio = target / scale
    return copy(scale = target, x = originX - (originX - x) * ratio, y = originY - (originY - y) * ratio).settled(layout)
}

/**
 * Scaled so its turned box fits the stage, and centred (`fit`). Unturned or half turned the layout
 * fitted it already, so that is 1; a quarter turn swaps the sides that have to fit.
 */
internal fun ViewTransform.fitted(layout: StageLayout): ViewTransform {
    val fit = if (quarterTurned && layout.width > 0 && layout.height > 0) {
        min(layout.stageWidth / layout.height, layout.stageHeight / layout.width)
    } else {
        1f
    }
    return copy(scale = fit, x = 0f, y = 0f).settled(layout)
}

/** One pixel of the file per dp, about the middle (`actualSize`). */
internal fun ViewTransform.actualSize(layout: StageLayout): ViewTransform =
    if (layout.width > 0 && layout.naturalWidth > 0) zoomedTo(layout.naturalWidth / layout.width, layout) else this

/** Turned by [degrees], then fitted again: what someone turning a picture wants is to see all of it. */
internal fun ViewTransform.rotated(degrees: Int, layout: StageLayout): ViewTransform =
    copy(rotation = ((rotation + degrees) % 360 + 360) % 360).fitted(layout)

/** Mirrored left to right, or top to bottom. */
internal fun ViewTransform.flipped(horizontal: Boolean): ViewTransform = if (horizontal) copy(flipX = !flipX) else copy(flipY = !flipY)

/** A double tap (`onDoubleClick`): back to fitted when zoomed in, else in to 2.5x about [originX], [originY]. */
internal fun ViewTransform.doubleTapped(layout: StageLayout, originX: Float, originY: Float): ViewTransform =
    if (scale > 1.05f) fitted(layout) else zoomedTo(DOUBLE_TAP_SCALE, layout, originX, originY)

/**
 * The picture's laid-out size in a [stageWidth] × [stageHeight] stage: its own, one pixel per dp,
 * or scaled down to fit when it is larger (`max-h-full max-w-full object-contain`), never up.
 */
internal fun fittedSize(naturalWidth: Float, naturalHeight: Float, stageWidth: Float, stageHeight: Float): Pair<Float, Float> {
    if (naturalWidth <= 0 || naturalHeight <= 0) return 0f to 0f
    val shrink = min(1f, min(stageWidth / naturalWidth, stageHeight / naturalHeight))
    return naturalWidth * shrink to naturalHeight * shrink
}

/**
 * Whether a wheel's step looks like a mouse wheel rather than a trackpad (`looksLikeMouseWheel`),
 * for [deltaX] and [deltaY] in the web's pixels: a wheel moves in whole notches on one axis, a
 * trackpad in small and often fractional steps on both. A bare wheel zooms; a trackpad pans.
 */
internal fun looksLikeMouseWheel(deltaX: Float, deltaY: Float): Boolean {
    if (deltaX != 0f) return false
    return deltaY == deltaY.roundToInt().toFloat() && kotlin.math.abs(deltaY) >= 40
}

/** The zoom one wheel step gives: exponential, so every notch scales by the same proportion. */
internal fun wheelZoom(deltaY: Float): Float = kotlin.math.exp(-deltaY / 300f)
