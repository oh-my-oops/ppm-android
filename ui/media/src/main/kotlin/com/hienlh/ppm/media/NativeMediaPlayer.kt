package com.hienlh.ppm.media

import android.content.Context
import android.view.TextureView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.VideoSize
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.state.media.MediaKind
import com.hienlh.ppm.state.media.MediaPlaybackController
import com.hienlh.ppm.state.media.MediaPlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Main-thread bridge. A retired player can never mutate its replacement or detach its surface. */
internal class NativeMediaPlayer(
    private val context: Context,
    private val api: ApiClient,
    private val controller: MediaPlaybackController,
    scope: CoroutineScope,
) : AutoCloseable {
    private var player: ExoPlayer? = null
    private var id: Long? = null
    private var texture: TextureView? = null
    private var closed = false
    private var preload: MetadataPreload? = null
    private var speed = 1f
    private var volume = 1f
    private val window = Timeline.Window()
    var aspect by mutableStateOf(16f / 9f)
        private set
    private val ticker = scope.launch {
        while (isActive) { progress(); delay(200) }
    }

    fun update(state: MediaPlaybackState) {
        if (closed) return
        val playback = state.playback
        if (!state.active || playback == null) { release(); return }
        if (id != playback.id) {
            release()
            val request = try { mediaRequest(api, playback.url) } catch (_: IllegalArgumentException) {
                controller.onPlayerError(playback.id, message = "Invalid media source.")
                return
            }
            val policy = MetadataPreload(state.source?.kind == MediaKind.AUDIO)
            policy.requestedPlay = state.playWhenReady
            preload = policy
            // A subclass, not `LoadControl by DefaultLoadControl()`: Kotlin forwards only the abstract methods of a Java
            // interface, media3's other ones default to a throw ("getBackBufferDurationUs not implemented"), and the
            // ExoPlayer constructor calls the first of them, so no player was ever built.
            val loadControl = object : DefaultLoadControl() {
                override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
                    policy.allowLoading() && super.shouldContinueLoading(parameters)
            }
            val next = ExoPlayer.Builder(context).setLoadControl(loadControl).build()
            player = next
            id = playback.id
            fun current() = !closed && player === next && id == playback.id
            next.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (!current()) return
                    policy.ready = player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_ENDED
                    texture?.keepScreenOn = player.isPlaying
                    progress()
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (current() && playbackState == Player.STATE_ENDED) controller.onEnded(playback.id)
                }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (current() && !playWhenReady && (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                            reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)) controller.setPlayWhenReady(false)
                }
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (current() && videoSize.width > 0 && videoSize.height > 0)
                        aspect = videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio / videoSize.height
                }
                override fun onPlayerError(error: PlaybackException) {
                    if (current()) controller.onPlayerError(playback.id, mediaStatus(error))
                }
            })
            next.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(if (state.source?.kind == MediaKind.AUDIO) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            next.setHandleAudioBecomingNoisy(true)
            next.setPlaybackSpeed(speed)
            next.volume = volume
            texture?.let(next::setVideoTextureView)
            val factory = OkHttpDataSource.Factory(mediaHttp).setDefaultRequestProperties(request.headers.toMap())
            val media = ProgressiveMediaSource.Factory(factory).setLoadErrorHandlingPolicy(NoRetryOnClientError())
                .createMediaSource(MediaItem.fromUri(request.url.toString()))
            next.setMediaSource(media, (playback.initialPositionSeconds * 1000).toLong())
            next.playWhenReady = state.playWhenReady
            next.prepare()
        }
        preload?.requestedPlay = state.playWhenReady
        player?.playWhenReady = state.playWhenReady
    }

    fun settings(speed: Float, volume: Float) {
        this.speed = speed
        this.volume = volume
        player?.let { if (it.playbackParameters.speed != speed) it.setPlaybackSpeed(speed); it.volume = volume }
    }

    fun attach(view: TextureView) {
        if (closed || texture === view) return
        texture?.keepScreenOn = false
        texture = view
        player?.setVideoTextureView(view)
        view.keepScreenOn = player?.isPlaying == true
    }

    fun detach(view: TextureView) {
        view.keepScreenOn = false
        if (texture !== view) return
        player?.clearVideoTextureView(view)
        texture = null
    }

    fun seek(seconds: Double) {
        controller.seekTo(seconds)?.let { player?.seekTo((it * 1000).toLong()) }
    }

    fun progress() {
        val current = player ?: return
        val token = id ?: return
        // The timeline's microseconds, not ExoPlayer.duration's truncated milliseconds: see mediaDurationSeconds.
        val timeline = current.currentTimeline
        val duration = if (timeline.isEmpty) null else mediaDurationSeconds(timeline.getWindow(current.currentMediaItemIndex, window).durationUs)
        controller.onProgress(token, mediaElapsedSeconds(current.currentPosition, current.playbackState == Player.STATE_ENDED, duration), duration,
            current.bufferedPosition / 1000.0, current.isPlaying, current.playbackState == Player.STATE_BUFFERING)
    }

    /** Capture before hide/background, since the controller rejects progress after deactivation. */
    fun suspendPlayback() { progress(); release() }

    private fun release() {
        val old = player
        player = null; id = null; preload = null
        texture?.keepScreenOn = false
        old?.release()
    }

    override fun close() {
        if (closed) return
        suspendPlayback()
        closed = true
        ticker.cancel()
        texture = null
    }
}
