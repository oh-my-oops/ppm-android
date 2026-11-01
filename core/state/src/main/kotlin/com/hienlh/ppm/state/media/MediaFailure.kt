package com.hienlh.ppm.state.media

import com.hienlh.ppm.protocol.MediaProbeInfo

/**
 * What a media tab shows in place of its player when the file cannot be played, in the web's words
 * (audio-preview.tsx, video-preview.tsx): the file-warning icon over a line and nothing else for an
 * audio file, and for a video what could not be played, how to get ffmpeg, and a way to download it.
 * The web has no Retry on either.
 */
sealed interface MediaFailure {
    /** [AUDIO_FAILURE_LINE]. */
    data object Audio : MediaFailure

    /** [codecs] is what the probe found, `video + audio`, empty when it found nothing or failed. */
    data class UnsupportedVideo(val codecs: String) : MediaFailure {
        val line: String get() = unsupportedVideoLine(codecs)
    }
}

/**
 * The failure [state] is in, or null while the media plays or loads, or fails in a way the player
 * words itself (a transcode that stopped, which the web shows over the video with a Retry).
 */
fun mediaFailure(state: MediaPlaybackState): MediaFailure? = when {
    state.source?.kind == MediaKind.AUDIO && state.error != null -> MediaFailure.Audio
    state.source?.kind == MediaKind.VIDEO && state.mode == MediaMode.UNSUPPORTED -> MediaFailure.UnsupportedVideo(mediaCodecs(state.probe))
    else -> null
}

/** `[probe?.video?.codec, probe?.audio?.codec].filter(Boolean).join(" + ")`. */
fun mediaCodecs(probe: MediaProbeInfo?): String =
    listOfNotNull(probe?.video?.codec, probe?.audio?.codec).filter { it.isNotEmpty() }.joinToString(" + ")

const val AUDIO_FAILURE_LINE = "Failed to load audio."

fun unsupportedVideoLine(codecs: String) =
    "This video cannot be played in the browser" + (if (codecs.isEmpty()) "" else " ($codecs)") + "."

/** A run of the hint under the line; [code] is the web's `<code>`, drawn monospace. */
data class MediaHintPart(val text: String, val code: Boolean = false)

val UNSUPPORTED_VIDEO_HINT: List<MediaHintPart> = listOf(
    MediaHintPart("Install "), MediaHintPart("ffmpeg", code = true),
    MediaHintPart(" on the PPM server (e.g. "),
    MediaHintPart("winget install ffmpeg", code = true), MediaHintPart(", "),
    MediaHintPart("brew install ffmpeg", code = true), MediaHintPart(", "),
    MediaHintPart("apt install ffmpeg", code = true),
    MediaHintPart(") and restart PPM to transcode it on the fly."),
)
