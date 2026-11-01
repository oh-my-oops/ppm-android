package com.hienlh.ppm.state.media

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.client.newMediaSessionId
import com.hienlh.ppm.client.probeMedia
import com.hienlh.ppm.client.rawMediaUrl
import com.hienlh.ppm.client.requireValidMediaSessionId
import com.hienlh.ppm.client.stopMediaTranscode
import com.hienlh.ppm.client.transcodeMediaUrl
import com.hienlh.ppm.protocol.MediaProbeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import kotlin.math.abs

internal interface MediaBackend {
    fun raw(source: MediaSource): HttpUrl
    fun transcode(source: MediaSource, start: Double, sid: String): HttpUrl
    suspend fun probe(source: MediaSource): MediaProbeInfo
    suspend fun stop(source: MediaSource, sid: String)
}

/**
 * One player/tab, driven on the parent's single main thread. Starts hidden; show() enables loading.
 * Media3 owns the stream GET and decoding. Replace/release its item when playback changes/nulls;
 * feed callbacks with playback.id, and respect playWhenReady separately from observed playing.
 * No server progress endpoint exists: position/buffering are measured by the player.
 */
class MediaPlaybackController internal constructor(
    private val backend: MediaBackend,
    parent: CoroutineScope,
    private val newSession: () -> String = ::newMediaSessionId,
) : AutoCloseable {
    constructor(api: ApiClient, parent: CoroutineScope) : this(object : MediaBackend {
        override fun raw(source: MediaSource) = api.rawMediaUrl(source.project, source.path)
        override fun transcode(source: MediaSource, start: Double, sid: String) = api.transcodeMediaUrl(source.project, source.path, start, sid)
        override suspend fun probe(source: MediaSource) = api.probeMedia(source.project, source.path)
        override suspend fun stop(source: MediaSource, sid: String) { api.stopMediaTranscode(source.project, source.path, sid) }
    }, parent)

    private val owner = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    // Only bounded DELETEs use this context. They must outlive an already-cancelled UI owner.
    private val cleanup = CoroutineScope(parent.coroutineContext + NonCancellable)
    private val pendingStops = mutableSetOf<Job>()
    private val mutable = MutableStateFlow(MediaPlaybackState())
    val state: StateFlow<MediaPlaybackState> = mutable.asStateFlow()
    private val current get() = mutable.value
    private var visible = false
    private var foreground = true
    private var closed = false
    private var generation = 0L
    private var playbackId = 0L
    private var probing: Job? = null
    private data class Session(val source: MediaSource, val id: String)
    private var session: Session? = null

    init {
        owner.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { close() }
        }
    }

    fun setSource(source: MediaSource?) {
        if (!alive() || source == current.source) return
        deactivate()
        mutable.value = MediaPlaybackState(source = source,
            mode = when {
                source == null -> MediaMode.IDLE
                source.kind == MediaKind.AUDIO || isNativeVideo(source.path) -> MediaMode.NATIVE
                else -> MediaMode.PROBING
            }, playWhenReady = source?.kind == MediaKind.VIDEO)
        activate()
    }

    fun show() { if (alive()) { visible = true; activate() } }
    fun hide() { if (alive()) { visible = false; deactivate() } }
    fun setForeground(value: Boolean) {
        if (!alive() || foreground == value) return
        foreground = value
        if (value) activate() else deactivate()
    }
    fun setPlayWhenReady(value: Boolean) { if (alive()) mutable.value = current.copy(playWhenReady = value) }

    /** Local stream seconds from Media3; transcode display time is streamStart + elapsed. */
    fun onProgress(id: Long, elapsedSeconds: Double, durationSeconds: Double? = null,
                   bufferedSeconds: Double = elapsedSeconds, playing: Boolean = false, waiting: Boolean = false) {
        if (!accepts(id) || !elapsedSeconds.isFinite()) return
        val duration = if (current.mode == MediaMode.TRANSCODE) current.probe?.duration else
            durationSeconds?.takeIf { it.isFinite() && it > 0 } ?: current.durationSeconds
        val offset = if (current.mode == MediaMode.TRANSCODE) current.streamStartSeconds else 0.0
        fun position(seconds: Double) = (offset + seconds.coerceAtLeast(0.0)).let { if (duration == null) it else it.coerceAtMost(duration) }
        mutable.value = current.copy(positionSeconds = position(elapsedSeconds), durationSeconds = duration,
            bufferedSeconds = position(bufferedSeconds.takeIf { it.isFinite() } ?: elapsedSeconds), playing = playing, waiting = waiting)
    }

    fun onEnded(id: Long) {
        if (accepts(id)) mutable.value = current.copy(playWhenReady = false, playing = false, waiting = false)
    }

    /** Pass HTTP status from Media3's data-source exception; do not issue another stream GET to discover it. */
    fun onPlayerError(id: Long, httpStatus: Int? = null, message: String? = null) {
        if (!accepts(id)) return
        if (current.mode == MediaMode.NATIVE && current.source?.kind == MediaKind.VIDEO) {
            startProbe()
        } else {
            stopSession()
            val fallback = when (httpStatus) {
                501 -> "ffmpeg is not installed or has no working H.264 encoder. Install ffmpeg on the PPM server and restart PPM."
                503 -> "The PPM server is busy transcoding other videos. Try again shortly."
                else -> if (current.mode == MediaMode.TRANSCODE) TRANSCODE_FAILURE_LINE else "Failed to load audio."
            }
            mutable.value = current.copy(playback = null, playing = false, waiting = false,
                error = MediaPlaybackError(message?.takeIf { it.isNotBlank() } ?: fallback, httpStatus))
        }
    }

    /**
     * Returns a native seek position for the UI to pass to player.seekTo(). A transcode seek
     * instead publishes a new playback URL and returns null. Nudges under 0.5s do nothing.
     */
    fun seekTo(seconds: Double): Double? {
        if (!alive() || !current.active || current.playback == null || !seconds.isFinite()) return null
        val target = clampMediaSeek(seconds, current.durationSeconds)
        if (current.mode == MediaMode.NATIVE) {
            mutable.value = current.copy(positionSeconds = target)
            return target
        }
        if (current.mode == MediaMode.TRANSCODE && abs(target - current.positionSeconds) >= 0.5) issueTranscode(target)
        return null
    }

    /** Web transcode retry nudges the previous stream start by 0.001, not the elapsed position. */
    fun retry() {
        if (!alive() || !current.active) return
        when (current.mode) {
            MediaMode.TRANSCODE -> issueTranscode(current.streamStartSeconds + 0.001)
            MediaMode.UNSUPPORTED, MediaMode.PROBING -> startProbe()
            MediaMode.NATIVE -> issueNative()
            MediaMode.IDLE -> Unit
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        deactivate()
        owner.cancel()
    }

    /** For a disposing owner/test that can await cleanup; close() itself is non-suspending. */
    suspend fun awaitCleanup() { while (pendingStops.isNotEmpty()) pendingStops.toList().forEach { it.join() } }

    private fun alive() = !closed && owner.isActive
    private fun accepts(id: Long) = alive() && current.active && current.playback?.id == id
    private fun activate() {
        if (!alive() || !visible || !foreground || current.source == null) return
        mutable.value = current.copy(active = true)
        if (current.playback != null || probing != null) return
        when (current.mode) {
            MediaMode.NATIVE -> if (current.error == null) issueNative()
            MediaMode.PROBING -> startProbe()
            MediaMode.TRANSCODE -> if (current.error == null) issueTranscode(current.positionSeconds)
            else -> Unit
        }
    }

    private fun deactivate() {
        generation++
        probing?.cancel(); probing = null
        stopSession()
        mutable.value = current.copy(active = false, playback = null, playing = false, waiting = false)
    }

    private fun startProbe() {
        val source = current.source ?: return
        probing?.cancel()
        val token = ++generation
        mutable.value = current.copy(mode = MediaMode.PROBING, playback = null, error = null, playing = false, waiting = true)
        val job = owner.launch(start = CoroutineStart.LAZY) {
            try {
                val result = backend.probe(source)
                if (alive() && current.active && generation == token) {
                    mutable.value = current.copy(probe = result, durationSeconds = result.duration, positionSeconds = 0.0)
                    if (result.transcodable) issueTranscode(0.0)
                    else mutable.value = current.copy(mode = MediaMode.UNSUPPORTED, waiting = false,
                        error = MediaPlaybackError("This video cannot be played. Install ffmpeg on the PPM server and restart PPM, or download the file."))
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (alive() && current.active && generation == token) mutable.value = current.copy(mode = MediaMode.UNSUPPORTED,
                    waiting = false, error = MediaPlaybackError(error.message ?: "The media probe failed.", (error as? PpmError.Server)?.status))
            } finally { if (generation == token) probing = null }
        }
        probing = job
        job.start()
    }

    private fun issueNative() {
        val source = current.source ?: return
        mutable.value = current.copy(mode = MediaMode.NATIVE, error = null, waiting = true, playing = false,
            playback = MediaPlayback(++playbackId, backend.raw(source), MediaMode.NATIVE,
                initialPositionSeconds = current.positionSeconds))
    }

    private fun issueTranscode(start: Double) {
        val source = current.source ?: return
        val owned = session ?: Session(source, newSession().also(::requireValidMediaSessionId)).also { session = it }
        val playback = MediaPlayback(++playbackId, backend.transcode(source, start, owned.id), MediaMode.TRANSCODE,
            owned.id, start, preload = MediaPreload.AUTO)
        mutable.value = current.copy(mode = MediaMode.TRANSCODE, playback = playback, streamStartSeconds = start,
            positionSeconds = start, bufferedSeconds = start, durationSeconds = current.probe?.duration,
            waiting = true, playing = false, error = null)
    }

    private fun stopSession() {
        val old = session ?: return
        session = null
        // A new visible lifetime receives a fresh sid, so a late DELETE cannot kill its stream.
        val job = cleanup.launch(start = CoroutineStart.LAZY) {
            try { withTimeout(5_000) { backend.stop(old.source, old.id) } }
            catch (error: Exception) {
                mutable.value = current.copy(cleanupError = MediaCleanupError(old.source, old.id, error.message ?: "Could not stop transcoding."))
            } finally { pendingStops.remove(coroutineContext[Job]) }
        }
        pendingStops += job
        job.start()
    }

    private companion object {
        val nativeContainers = setOf("mp4", "m4v", "webm", "ogg", "ogv", "mov")
        fun isNativeVideo(path: String): Boolean {
            val name = path.substringAfterLast('/').substringAfterLast('\\')
            val dot = name.lastIndexOf('.')
            return dot > 0 && name.substring(dot + 1).lowercase() in nativeContainers
        }
    }
}

/** Web clampSeek: leave half a second before EOF, including native seeks. */
fun clampMediaSeek(seconds: Double, duration: Double?): Double =
    if (!seconds.isFinite()) 0.0 else seconds.coerceAtLeast(0.0).let {
        if (duration == null || !duration.isFinite() || duration <= 0) it else it.coerceAtMost((duration - 0.5).coerceAtLeast(0.0))
    }
