package com.hienlh.ppm.state.media

import com.hienlh.ppm.protocol.MediaAudioInfo
import com.hienlh.ppm.protocol.MediaProbeInfo
import com.hienlh.ppm.protocol.MediaVideoInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The words and the choice of screen for a media file that cannot play are the web's (audio-preview.tsx, video-preview.tsx). */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaFailureTest {
    private val audio = MediaSource("p", "a.mp3", MediaKind.AUDIO)
    private val video = MediaSource("p", "clip.mkv", MediaKind.VIDEO)
    private val probe = MediaProbeInfo(transcodable = false, video = MediaVideoInfo("hevc"), audio = MediaAudioInfo("aac"))

    @Test fun `the codecs are the video's and the audio's joined by a plus, as the web joins them`() {
        assertEquals("hevc + aac", mediaCodecs(probe))
        assertEquals("hevc", mediaCodecs(MediaProbeInfo(false, video = MediaVideoInfo("hevc"))))
        assertEquals("aac", mediaCodecs(MediaProbeInfo(false, audio = MediaAudioInfo("aac"))))
        assertEquals("", mediaCodecs(MediaProbeInfo(false)))
        assertEquals("", mediaCodecs(null))
        assertEquals("aac", mediaCodecs(MediaProbeInfo(false, video = MediaVideoInfo(""), audio = MediaAudioInfo("aac"))))
    }

    @Test fun `an unsupported video's line names the codecs in brackets only when there are some`() {
        assertEquals("This video cannot be played in the browser (hevc + aac).", unsupportedVideoLine("hevc + aac"))
        assertEquals("This video cannot be played in the browser.", unsupportedVideoLine(""))
        assertEquals("This video cannot be played in the browser (vp9).", MediaFailure.UnsupportedVideo("vp9").line)
    }

    @Test fun `the hint is the web's sentence with ffmpeg and the three install commands as code`() {
        assertEquals(
            "Install ffmpeg on the PPM server (e.g. winget install ffmpeg, brew install ffmpeg, apt install ffmpeg) " +
                "and restart PPM to transcode it on the fly.",
            UNSUPPORTED_VIDEO_HINT.joinToString("") { it.text },
        )
        assertEquals(
            listOf("ffmpeg", "winget install ffmpeg", "brew install ffmpeg", "apt install ffmpeg"),
            UNSUPPORTED_VIDEO_HINT.filter { it.code }.map { it.text },
        )
        assertEquals("Failed to load audio.", AUDIO_FAILURE_LINE)
    }

    @Test fun `an audio file with an error is the audio failure, whatever the error says`() {
        for (message in listOf("Failed to load audio.", "Source error", "Response code: 404")) {
            val state = MediaPlaybackState(source = audio, active = true, mode = MediaMode.NATIVE, error = MediaPlaybackError(message, 404))
            assertEquals(MediaFailure.Audio, mediaFailure(state))
        }
    }

    @Test fun `an audio file that plays, loads or waits is not a failure`() {
        assertNull(mediaFailure(MediaPlaybackState(source = audio, active = true, mode = MediaMode.NATIVE)))
        assertNull(mediaFailure(MediaPlaybackState(source = audio, active = true, mode = MediaMode.NATIVE, waiting = true, playing = false)))
        assertNull(mediaFailure(MediaPlaybackState()))
    }

    @Test fun `a video the probe could not transcode is the unsupported screen with the codecs it found`() {
        val state = MediaPlaybackState(source = video, active = true, mode = MediaMode.UNSUPPORTED, probe = probe,
            error = MediaPlaybackError("This video cannot be played."))
        assertEquals(MediaFailure.UnsupportedVideo("hevc + aac"), mediaFailure(state))
        assertEquals(MediaFailure.UnsupportedVideo(""), mediaFailure(state.copy(probe = null)))
    }

    @Test fun `a video that is probing, transcoding or whose transcode stopped is not the unsupported screen`() {
        for (mode in listOf(MediaMode.PROBING, MediaMode.TRANSCODE, MediaMode.NATIVE, MediaMode.IDLE)) {
            assertNull(mediaFailure(MediaPlaybackState(source = video, active = true, mode = mode)))
        }
        val stopped = MediaPlaybackState(source = video, active = true, mode = MediaMode.TRANSCODE,
            error = MediaPlaybackError("Transcoding failed. ffmpeg may have stopped or the file is corrupt."))
        assertNull(mediaFailure(stopped))
    }

    private class Backend(val probing: suspend () -> MediaProbeInfo) : MediaBackend {
        override fun raw(source: MediaSource) = "https://example.test/raw".toHttpUrl()
        override fun transcode(source: MediaSource, start: Double, sid: String) = "https://example.test/transcode".toHttpUrl()
        override suspend fun probe(source: MediaSource) = probing()
        override suspend fun stop(source: MediaSource, sid: String) = Unit
    }

    @Test fun `the controller's own unsupported and failed-probe states are the unsupported screen, with and without codecs`() = runTest {
        for (codecs in listOf("hevc + aac", "")) {
            val media = MediaPlaybackController(Backend { if (codecs.isEmpty()) throw IllegalStateException("probe failed") else probe }, backgroundScope) { "session" }
            media.setSource(video)
            media.show()
            runCurrent()
            assertEquals(MediaMode.UNSUPPORTED, media.state.value.mode)
            assertEquals(MediaFailure.UnsupportedVideo(codecs), mediaFailure(media.state.value))
            media.close(); runCurrent()
        }
    }

    @Test fun `the controller's audio player error is the audio failure`() = runTest {
        val media = MediaPlaybackController(Backend { probe }, backgroundScope) { "session" }
        media.setSource(audio)
        media.show()
        val playback = media.state.value.playback!!
        assertNull(mediaFailure(media.state.value))

        media.onPlayerError(playback.id, httpStatus = 404, message = "Response code: 404")

        assertEquals(MediaFailure.Audio, mediaFailure(media.state.value))
        media.close(); runCurrent()
    }
}
