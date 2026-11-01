package com.hienlh.ppm.state.media

import com.hienlh.ppm.protocol.MediaProbeInfo

/**
 * What a media tab shows when the file cannot be played, in the web's words (audio-preview.tsx,
 * video-preview.tsx, video-player.tsx). An audio file and a video no tool can transcode replace the
 * player: the file-warning icon over a line and nothing else for audio, and for the video what could
 * not be played, how to get ffmpeg, and a way to download it; the web has no Retry on either. A
 * transcode that stopped does not replace it, it is drawn over the video's own area.
 */
sealed interface MediaFailure {
    /** [AUDIO_FAILURE_LINE]. */
    data object Audio : MediaFailure

    /** [codecs] is what the probe found, `video + audio`, empty when it found nothing or failed. */
    data class UnsupportedVideo(val codecs: String) : MediaFailure {
        val line: String get() = unsupportedVideoLine(codecs)
    }

    /**
     * The web's overlay over the video with its [message] and a Retry, the controls still under it.
     * The message is the controller's: [TRANSCODE_FAILURE_LINE] for everything the web can tell
     * apart, and its own words for a server that is busy or has no encoder, which the web cannot see.
     */
    data class TranscodeStopped(val message: String) : MediaFailure
}

/** The failure [state] is in, or null while the media plays, loads or is probed. */
fun mediaFailure(state: MediaPlaybackState): MediaFailure? = when {
    state.source?.kind == MediaKind.AUDIO && state.error != null -> MediaFailure.Audio
    state.source?.kind == MediaKind.VIDEO && state.mode == MediaMode.UNSUPPORTED -> MediaFailure.UnsupportedVideo(mediaCodecs(state.probe))
    state.source?.kind == MediaKind.VIDEO && state.mode == MediaMode.TRANSCODE && state.error != null ->
        MediaFailure.TranscodeStopped(state.error.message)
    else -> null
}

/** `[probe?.video?.codec, probe?.audio?.codec].filter(Boolean).join(" + ")`. */
fun mediaCodecs(probe: MediaProbeInfo?): String =
    listOfNotNull(probe?.video?.codec, probe?.audio?.codec).filter { it.isNotEmpty() }.joinToString(" + ")

const val AUDIO_FAILURE_LINE = "Failed to load audio."

/** What video-player.tsx says when a transcode stops, whatever the cause. */
const val TRANSCODE_FAILURE_LINE = "Transcoding failed. ffmpeg may have stopped or the file is corrupt."

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
