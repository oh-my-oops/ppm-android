package com.hienlh.ppm.media

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.round

internal val mediaSpeeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)

internal fun stepMediaSpeed(speed: Float, direction: Int): Float {
    val index = mediaSpeeds.indexOf(speed).takeIf { it >= 0 } ?: 3
    return mediaSpeeds[(index + direction).coerceIn(mediaSpeeds.indices)]
}

internal data class VideoTransform(val rotation: Int = 0, val flipH: Boolean = false, val flipV: Boolean = false) {
    fun rotate(direction: Int) = copy(rotation = Math.floorMod(rotation + direction * 90, 360))
    val identity get() = this == VideoTransform()
    // Flip in the video's own axes, before rotation, as CSS rotate() scaleX() scaleY().
    fun fittedSize(width: Float, height: Float, aspect: Float): Pair<Float, Float> {
        if (width <= 0 || height <= 0 || !aspect.isFinite() || aspect <= 0) return 0f to 0f
        val quarterTurn = rotation == 90 || rotation == 270
        val maxW = if (quarterTurn) height else width
        val maxH = if (quarterTurn) width else height
        val w = min(maxW, maxH * aspect)
        return w to w / aspect
    }
}

internal data class MediaVolume(val level: Float = 1f, val muted: Boolean = false) {
    val effective get() = if (muted) 0f else level
    fun by(delta: Float) = copy(level = (round((level + delta) * 100) / 100).coerceIn(0f, 1f), muted = false)
    fun slider(value: Float) = value.coerceIn(0f, 1f).let { copy(level = it, muted = it == 0f) }
}

internal fun mediaClock(seconds: Double): String {
    val s = if (seconds.isFinite()) floor(seconds.coerceAtLeast(0.0)).toLong() else 0L
    val hours = s / 3600
    val minutes = ((s % 3600) / 60).toString().let { if (hours > 0) it.padStart(2, '0') else it }
    return (if (hours > 0) "$hours:" else "") + minutes + ":" + (s % 60).toString().padStart(2, '0')
}

internal fun mediaSeekBar(position: Double, duration: Double): Double = floor(position.coerceIn(0.0, duration) * 10) / 10

/**
 * A duration Media3 reports in microseconds, as seconds to the nearest millisecond; null while it is not known (a negative value,
 * `C.TIME_UNSET`). A gapless MP3 comes out a sample short, 3,999,977 µs for what ffprobe and Chrome say is 4.000000 s, and the
 * millisecond `ExoPlayer.duration` truncates that to 3999, which [mediaClock] floors to 0:03 where Chrome's controls show 0:04.
 */
internal fun mediaDurationSeconds(durationUs: Long): Double? = durationUs.takeIf { it > 0 }?.let { round(it / 1000.0) / 1000.0 }

/** Where the player is, in seconds: the end of the file, as an HTML media element's `currentTime` is, once it has ended, else [positionMs]. */
internal fun mediaElapsedSeconds(positionMs: Long, ended: Boolean, duration: Double?): Double =
    if (ended && duration != null) duration else positionMs / 1000.0

internal sealed interface MediaAction {
    data object Play : MediaAction
    data object Mute : MediaAction
    data object Fullscreen : MediaAction
    data class Seek(val seconds: Double) : MediaAction
    data class Volume(val delta: Float) : MediaAction
    data class Speed(val direction: Int) : MediaAction
    data class Rotate(val direction: Int) : MediaAction
}

/** DOM key names keep this web mapping independent of Android's key-code table. */
internal fun mediaShortcut(key: String, shift: Boolean = false, modified: Boolean = false): MediaAction? {
    if (modified) return null
    return when (key) {
        " ", "k", "K" -> MediaAction.Play
        "ArrowLeft" -> MediaAction.Seek(if (shift) -10.0 else -5.0)
        "ArrowRight" -> MediaAction.Seek(if (shift) 10.0 else 5.0)
        "j", "J" -> MediaAction.Seek(-10.0)
        "l", "L" -> MediaAction.Seek(10.0)
        "ArrowUp" -> MediaAction.Volume(0.1f)
        "ArrowDown" -> MediaAction.Volume(-0.1f)
        "m", "M" -> MediaAction.Mute
        "f", "F" -> MediaAction.Fullscreen
        "<", "," -> MediaAction.Speed(-1)
        ">", "." -> MediaAction.Speed(1)
        "r" -> MediaAction.Rotate(if (shift) -1 else 1)
        "R" -> MediaAction.Rotate(-1)
        else -> null
    }
}

/** Load enough to discover tracks and become ready; never buffer a whole paused audio file. */
internal class MetadataPreload(private val metadataOnly: Boolean) {
    @Volatile var ready = false
    @Volatile var requestedPlay = false
    fun allowLoading() = !metadataOnly || requestedPlay || !ready
}
