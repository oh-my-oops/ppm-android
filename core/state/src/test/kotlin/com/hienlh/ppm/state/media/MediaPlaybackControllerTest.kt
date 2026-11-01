package com.hienlh.ppm.state.media

import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.protocol.MediaProbeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaPlaybackControllerTest {
    private class Backend : MediaBackend {
        val probes = mutableListOf<MediaSource>()
        val raw = mutableListOf<MediaSource>()
        val streams = mutableListOf<Triple<MediaSource, Double, String>>()
        val stops = mutableListOf<Pair<MediaSource, String>>()
        var probing: suspend (MediaSource) -> MediaProbeInfo = { MediaProbeInfo(true, duration = 100.0) }
        var stopping: suspend () -> Unit = { }
        override fun raw(source: MediaSource) = "https://example.test/raw".toHttpUrl().also { raw += source }
        override fun transcode(source: MediaSource, start: Double, sid: String) =
            ("https://example.test/transcode?start=" + start + "&sid=" + sid).toHttpUrl()
                .also { streams += Triple(source, start, sid) }
        override suspend fun probe(source: MediaSource): MediaProbeInfo { probes += source; return probing(source) }
        override suspend fun stop(source: MediaSource, sid: String) { stops += source to sid; stopping() }
    }
    private fun controller(backend: Backend, scope: CoroutineScope): MediaPlaybackController {
        var id = 0
        return MediaPlaybackController(backend, scope) { "session_" + ++id }
    }

    @Test fun extensionRoutingMatchesWebForDotfilesAndWindowsPaths() = runTest {
        val backend = Backend()
        val media = controller(backend, backgroundScope)
        for (path in listOf("/folder.mp4/.mov", "C:\\folder.webm\\noext", "MP4")) {
            media.setSource(MediaSource("p", path))
            assertEquals(MediaMode.PROBING, media.state.value.mode)
        }
        media.setSource(MediaSource("p", ".hidden.MP4"))
        assertEquals(MediaMode.NATIVE, media.state.value.mode)
        assertTrue(backend.probes.isEmpty())
        media.close(); runCurrent()
    }

    @Test fun nativeVideoIsLazyAndFallsBackOnlyOnFailure() = runTest {
        val backend = Backend()
        val media = controller(backend, backgroundScope)
        media.setSource(MediaSource("p", "CLIP.MP4"))
        runCurrent()
        assertTrue(backend.raw.isEmpty()); assertTrue(backend.probes.isEmpty())
        media.show()
        val native = media.state.value.playback!!
        assertEquals(MediaMode.NATIVE, native.mode)
        assertEquals(MediaPreload.METADATA, native.preload)
        assertTrue(media.state.value.playWhenReady)
        media.onProgress(native.id, 5.0, 20.0, 12.0, playing = true)
        assertEquals(0.25, media.state.value.progress!!, 0.0)
        assertEquals(19.5, media.seekTo(30.0)!!, 0.0)
        media.onPlayerError(native.id)
        assertEquals(MediaMode.PROBING, media.state.value.mode)
        runCurrent()
        assertEquals(1, backend.probes.size)
        assertEquals(MediaMode.TRANSCODE, media.state.value.mode)
        assertEquals(0.0, media.state.value.positionSeconds, 0.0)
        media.close(); runCurrent(); media.awaitCleanup()
        assertEquals(1, backend.stops.size)
    }

    @Test fun audioNeverProbesOrTranscodesAndStartsWithoutAutoplay() = runTest {
        val backend = Backend()
        val media = controller(backend, backgroundScope)
        media.setSource(MediaSource(null, "/music/song.flac", MediaKind.AUDIO)); media.show()
        assertEquals(MediaPreload.METADATA, media.state.value.playback!!.preload)
        assertFalse(media.state.value.playWhenReady)
        media.onPlayerError(media.state.value.playback!!.id)
        assertEquals("Failed to load audio.", media.state.value.error!!.message)
        media.retry()
        assertNotNull(media.state.value.playback)
        assertEquals(2, backend.raw.size)
        runCurrent()
        assertTrue(backend.probes.isEmpty()); assertTrue(backend.streams.isEmpty())
        media.close(); runCurrent()
        assertTrue(backend.stops.isEmpty())
    }

    @Test fun transcodeSeekOffsetRetryAndStaleEvents() = runTest {
        val backend = Backend()
        val media = controller(backend, backgroundScope)
        media.setSource(MediaSource("p", "v.mkv")); media.show(); runCurrent()
        val first = media.state.value.playback!!
        assertEquals(MediaPreload.AUTO, first.preload)
        media.onProgress(first.id, 10.0, Double.POSITIVE_INFINITY, 15.0, playing = true)
        assertNull(media.seekTo(10.49)); assertEquals(1, backend.streams.size)
        assertNull(media.seekTo(10.5)); assertEquals(2, backend.streams.size)
        val second = media.state.value.playback!!
        assertEquals(first.sessionId, second.sessionId)
        media.onProgress(second.id, 2.0, 999.0, 5.0, playing = true)
        assertEquals(12.5, media.state.value.positionSeconds, 0.0)
        assertEquals(15.5, media.state.value.bufferedSeconds, 0.0)
        assertEquals(100.0, media.state.value.durationSeconds!!, 0.0)
        media.onProgress(first.id, 99.0)
        media.onPlayerError(first.id, 503)
        assertEquals(second, media.state.value.playback)
        media.setPlayWhenReady(false)
        media.retry()
        assertEquals(10.501, media.state.value.streamStartSeconds, 0.0000001)
        assertFalse(media.state.value.playWhenReady)
        assertEquals(first.sessionId, media.state.value.playback!!.sessionId)
        assertTrue(backend.stops.isEmpty()) // Same sid replaces the server job on stream GET.
        media.close(); runCurrent()
        assertEquals(listOf(MediaSource("p", "v.mkv") to first.sessionId), backend.stops)
    }

    @Test fun hideForegroundAndSourceSwitchCleanOriginalSessions() = runTest {
        val backend = Backend()
        val delayedStop = CompletableDeferred<Unit>()
        backend.stopping = { delayedStop.await() }
        val media = controller(backend, backgroundScope)
        val original = MediaSource("p", "v.mkv")
        media.setSource(original); media.show(); runCurrent()
        val first = media.state.value.playback!!
        media.onProgress(first.id, 8.0)
        media.hide(); runCurrent()
        assertFalse(media.state.value.active); assertNull(media.state.value.playback)
        assertEquals(listOf(original to first.sessionId), backend.stops)
        media.show()
        val resumed = media.state.value.playback!!
        assertNotEquals(first.sessionId, resumed.sessionId)
        assertEquals(8.0, resumed.streamStartSeconds, 0.0)
        delayedStop.complete(Unit); runCurrent()
        assertEquals(resumed, media.state.value.playback)
        media.setForeground(false); runCurrent()
        assertNull(media.state.value.playback)
        media.show(); assertNull(media.state.value.playback)
        media.setForeground(true)
        val third = media.state.value.playback!!
        media.setSource(MediaSource(null, "/new.mp4")); runCurrent()
        assertEquals(MediaMode.NATIVE, media.state.value.mode)
        assertEquals(listOf(first.sessionId, resumed.sessionId, third.sessionId), backend.stops.map { it.second })
        assertTrue(backend.stops.all { it.first == original })
        media.close(); runCurrent()
        assertEquals(3, backend.stops.size)
    }

    @Test fun parentCancellationStillSendsCleanupAndCloseIsIdempotent() = runTest {
        val backend = Backend()
        val parent = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob())
        val release = CompletableDeferred<Unit>()
        backend.stopping = { release.await() }
        val media = controller(backend, parent)
        media.setSource(MediaSource(null, "/v.mkv")); media.show(); runCurrent()
        parent.cancel(); runCurrent()
        assertFalse(media.state.value.active); assertNull(media.state.value.playback)
        assertEquals(1, backend.stops.size)
        media.close(); media.show(); media.retry()
        release.complete(Unit); runCurrent(); media.awaitCleanup()
        assertEquals(1, backend.stops.size)
        assertNull(media.state.value.cleanupError)
    }

    @Test fun staleUncancellableProbeCannotReplaceNewSource() = runTest {
        val backend = Backend()
        val reply = CompletableDeferred<MediaProbeInfo>()
        backend.probing = { withContext(NonCancellable) { reply.await() } }
        val media = controller(backend, backgroundScope)
        media.setSource(MediaSource("old", "v.mkv")); media.show(); runCurrent()
        media.hide()
        media.setSource(MediaSource("new", "v.mp4")); media.show()
        val newPlayback = media.state.value.playback
        reply.complete(MediaProbeInfo(true, duration = 9.0)); runCurrent()
        assertEquals(newPlayback, media.state.value.playback)
        assertTrue(backend.streams.isEmpty())
        media.close(); runCurrent()
    }

    @Test fun unsupportedProbeAnd501And503RemainDistinctWithRetry() = runTest {
        val backend = Backend()
        backend.probing = { MediaProbeInfo(false) }
        val media = controller(backend, backgroundScope)
        media.setSource(MediaSource("p", "v.mkv")); media.show(); runCurrent()
        assertEquals(MediaMode.UNSUPPORTED, media.state.value.mode)
        assertNull(media.state.value.playback)
        assertTrue(media.state.value.error!!.message.contains("ffmpeg"))
        backend.probing = { throw PpmError.Server(403, "Forbidden") }
        media.retry(); runCurrent()
        assertEquals(403, media.state.value.error!!.httpStatus)
        assertEquals("Forbidden", media.state.value.error!!.message)
        backend.probing = { MediaProbeInfo(true) }
        media.retry(); runCurrent()
        for (status in listOf(501, 503)) {
            val playback = media.state.value.playback!!
            media.onPlayerError(playback.id, status); runCurrent()
            assertEquals(status, media.state.value.error!!.httpStatus)
            assertEquals(status == 501, media.state.value.error!!.ffmpegUnavailable)
            assertEquals(status == 503, media.state.value.error!!.serverBusy)
            assertNull(media.state.value.playback)
            media.retry()
            assertNotEquals(playback.sessionId, media.state.value.playback!!.sessionId)
        }
        media.close(); runCurrent()
        assertEquals(3, backend.stops.size)
    }

    @Test fun cleanupTimeoutIsBoundedAndSeparateFromPlaybackError() = runTest {
        val backend = Backend()
        backend.stopping = { awaitCancellation() }
        val media = controller(backend, backgroundScope)
        media.setSource(MediaSource("p", "v.mkv")); media.show(); runCurrent()
        media.hide(); runCurrent()
        advanceTimeBy(5_001); runCurrent(); media.awaitCleanup()
        assertNotNull(media.state.value.cleanupError)
        assertNull(media.state.value.error)
        media.close(); runCurrent()
    }

    @Test fun unknownDurationAndInvalidProgressStaySafe() = runTest {
        val backend = Backend()
        backend.probing = { MediaProbeInfo(true) }
        val media = controller(backend, backgroundScope)
        media.setSource(MediaSource("p", "v.avi")); media.show(); runCurrent()
        val id = media.state.value.playback!!.id
        media.onProgress(id, 9.0, 300.0, Double.NaN)
        assertNull(media.state.value.durationSeconds); assertNull(media.state.value.progress)
        assertEquals(9.0, media.state.value.bufferedSeconds, 0.0)
        val before = media.state.value
        media.onProgress(id, Double.NaN); media.seekTo(Double.NaN)
        assertEquals(before, media.state.value)
        assertEquals(0.0, clampMediaSeek(2.0, 0.3), 0.0)
        assertEquals(99.5, clampMediaSeek(200.0, 100.0), 0.0)
        assertEquals(0.0, clampMediaSeek(-10.0, null), 0.0)
        media.close(); runCurrent()
    }
}
