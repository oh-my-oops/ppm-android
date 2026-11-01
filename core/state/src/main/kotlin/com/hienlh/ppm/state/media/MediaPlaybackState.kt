package com.hienlh.ppm.state.media

import com.hienlh.ppm.protocol.MediaProbeInfo
import okhttp3.HttpUrl

enum class MediaKind { VIDEO, AUDIO }
data class MediaSource(val project: String?, val path: String, val kind: MediaKind = MediaKind.VIDEO)
enum class MediaMode { IDLE, NATIVE, PROBING, TRANSCODE, UNSUPPORTED }
enum class MediaPreload { METADATA, AUTO }

/** A new id means replace the player's item; events must carry this id to reject stale callbacks. */
data class MediaPlayback(
    val id: Long,
    val url: HttpUrl,
    val mode: MediaMode,
    val sessionId: String? = null,
    val streamStartSeconds: Double = 0.0,
    /** Native resume offset. A transcoded stream always starts at local position zero. */
    val initialPositionSeconds: Double = 0.0,
    val preload: MediaPreload = MediaPreload.METADATA,
) {
    // The URL contains a credential. Keep diagnostics useful without rendering it accidentally.
    override fun toString() = "MediaPlayback(id=$id, mode=$mode, start=$streamStartSeconds, preload=$preload)"
}

data class MediaPlaybackError(val message: String, val httpStatus: Int? = null) {
    val ffmpegUnavailable: Boolean get() = httpStatus == 501
    val serverBusy: Boolean get() = httpStatus == 503
}
data class MediaCleanupError(val source: MediaSource, val sessionId: String, val message: String)

data class MediaPlaybackState(
    val source: MediaSource? = null,
    val active: Boolean = false,
    val mode: MediaMode = MediaMode.IDLE,
    val playback: MediaPlayback? = null,
    val probe: MediaProbeInfo? = null,
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double? = null,
    val bufferedSeconds: Double = 0.0,
    val streamStartSeconds: Double = 0.0,
    val playWhenReady: Boolean = false,
    val playing: Boolean = false,
    val waiting: Boolean = false,
    val error: MediaPlaybackError? = null,
    val cleanupError: MediaCleanupError? = null,
) {
    val progress: Double? get() = durationSeconds?.takeIf { it > 0 }?.let { (positionSeconds / it).coerceIn(0.0, 1.0) }
}
