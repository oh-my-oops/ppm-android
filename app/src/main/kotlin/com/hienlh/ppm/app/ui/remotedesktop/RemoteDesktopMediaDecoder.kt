package com.hienlh.ppm.app.ui.remotedesktop

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceView
import com.hienlh.ppm.state.remotedesktop.H264SpsPps
import com.hienlh.ppm.state.remotedesktop.RdSize
import com.hienlh.ppm.state.remotedesktop.buildOpusHeadCsd
import com.hienlh.ppm.state.remotedesktop.buildOpusNanosecondsCsd
import com.hienlh.ppm.state.remotedesktop.extractAnnexBSpsPps
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Low-latency Android [MediaCodec] H.264 Annex-B decoder rendering to a [Surface],
 * Opus audio player ([AudioTrack]), MP4 stream recorder ([MediaMuxer]), and [PixelCopy] screenshot helper.
 */
class H264SurfaceDecoder(
    private val onFrameSizeChanged: (RdSize) -> Unit = {},
    private val onStatusChanged: (String, String?) -> Unit = { _, _ -> },
) {
    private val lock = Any()
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var activeSpsPps: H264SpsPps? = null
    private var waitingForKeyframe: Boolean = true
    private var ptsUs: Long = 0L

    val frameCount = AtomicInteger(0)
    @Volatile
    var frameSize: RdSize = RdSize(1920, 1080)
        private set

    fun attachSurface(newSurface: Surface?) {
        synchronized(lock) {
            if (surface === newSurface) return
            surface = newSurface
            releaseCodecLocked()
            waitingForKeyframe = true
        }
    }

    fun configure() {
        synchronized(lock) {
            releaseCodecLocked()
            waitingForKeyframe = true
            onStatusChanged("ready", null)
        }
    }

    fun reset() {
        synchronized(lock) {
            releaseCodecLocked()
            activeSpsPps = null
            waitingForKeyframe = true
            ptsUs = 0L
        }
    }

    fun decodeAccessUnit(data: ByteArray, offset: Int, length: Int, isKeyframe: Boolean) {
        synchronized(lock) {
            val targetSurface = surface
            if (targetSurface == null || !targetSurface.isValid) {
                waitingForKeyframe = true
                return
            }
            if (waitingForKeyframe && !isKeyframe) {
                return
            }

            val spsPps = if (isKeyframe) extractAnnexBSpsPps(data, offset, length) else null
            if (codec == null || (spsPps != null && activeSpsPps != null && spsPps != activeSpsPps)) {
                if (!startCodecLocked(targetSurface, spsPps)) {
                    waitingForKeyframe = true
                    return
                }
            }

            val activeCodec = codec ?: return
            try {
                val inIdx = activeCodec.dequeueInputBuffer(10_000L)
                if (inIdx >= 0) {
                    val inBuf = activeCodec.getInputBuffer(inIdx)
                    if (inBuf != null) {
                        inBuf.clear()
                        val copyLen = minOf(length, inBuf.remaining())
                        inBuf.put(data, offset, copyLen)
                        val flags = if (isKeyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        ptsUs += 16_667L
                        activeCodec.queueInputBuffer(inIdx, 0, copyLen, ptsUs, flags)
                        waitingForKeyframe = false
                    }
                }
                drainOutputLocked(activeCodec)
            } catch (e: Throwable) {
                // Decoder error or resolution switch: rebuild on next keyframe (<= 1s)
                releaseCodecLocked()
                waitingForKeyframe = true
            }
        }
    }

    private fun startCodecLocked(targetSurface: Surface, spsPps: H264SpsPps?): Boolean {
        releaseCodecLocked()
        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, frameSize.width, frameSize.height)
            if (spsPps != null) {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(spsPps.sps))
                format.setByteBuffer("csd-1", ByteBuffer.wrap(spsPps.pps))
                activeSpsPps = spsPps
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)

            val mc = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mc.configure(format, targetSurface, null, 0)
            mc.start()
            codec = mc
            onStatusChanged("ready", null)
            true
        } catch (e: Throwable) {
            onStatusChanged("error", e.message ?: "H.264 decoder failed to start")
            false
        }
    }

    private fun drainOutputLocked(activeCodec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIdx = activeCodec.dequeueOutputBuffer(info, 0L)
            when {
                outIdx >= 0 -> {
                    activeCodec.releaseOutputBuffer(outIdx, true)
                    frameCount.incrementAndGet()
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val fmt = activeCodec.outputFormat
                    val w = if (fmt.containsKey("crop-right") && fmt.containsKey("crop-left")) {
                        fmt.getInteger("crop-right") - fmt.getInteger("crop-left") + 1
                    } else {
                        fmt.getInteger(MediaFormat.KEY_WIDTH)
                    }
                    val h = if (fmt.containsKey("crop-bottom") && fmt.containsKey("crop-top")) {
                        fmt.getInteger("crop-bottom") - fmt.getInteger("crop-top") + 1
                    } else {
                        fmt.getInteger(MediaFormat.KEY_HEIGHT)
                    }
                    if (w > 0 && h > 0) {
                        val nextSize = RdSize(w, h)
                        if (nextSize != frameSize) {
                            frameSize = nextSize
                            onFrameSizeChanged(nextSize)
                        }
                    }
                }
                else -> break
            }
        }
    }

    private fun releaseCodecLocked() {
        val old = codec ?: return
        codec = null
        runCatching { old.stop() }
        runCatching { old.release() }
    }
}

/**
 * Low-latency Opus audio decoder (`audio/opus`) + [AudioTrack] playback for Remote Desktop audio frames.
 */
class OpusAudioPlayer {
    private val lock = Any()
    private var codec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var ptsUs: Long = 0L

    fun configure(channels: Int = 2, sampleRate: Int = 48_000) {
        synchronized(lock) {
            resetLocked()
            runCatching {
                val ch = channels.coerceIn(1, 2)
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, ch)
                format.setByteBuffer("csd-0", ByteBuffer.wrap(buildOpusHeadCsd(ch, sampleRate)))
                format.setByteBuffer("csd-1", ByteBuffer.wrap(buildOpusNanosecondsCsd(80_000_000L)))
                format.setByteBuffer("csd-2", ByteBuffer.wrap(buildOpusNanosecondsCsd(80_000_000L)))

                val mc = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
                mc.configure(format, null, null, 0)
                mc.start()
                codec = mc

                val channelMask = if (ch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
                    .coerceAtLeast(4096)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelMask)
                            .build(),
                    )
                    .setBufferSizeInBytes(minBuf)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
                track.play()
                audioTrack = track
            }
        }
    }

    fun decodePacket(data: ByteArray, offset: Int, length: Int) {
        synchronized(lock) {
            val mc = codec ?: return
            val track = audioTrack ?: return
            try {
                val inIdx = mc.dequeueInputBuffer(5_000L)
                if (inIdx >= 0) {
                    val inBuf = mc.getInputBuffer(inIdx)
                    if (inBuf != null) {
                        inBuf.clear()
                        val copyLen = minOf(length, inBuf.remaining())
                        inBuf.put(data, offset, copyLen)
                        ptsUs += 20_000L
                        mc.queueInputBuffer(inIdx, 0, copyLen, ptsUs, 0)
                    }
                }
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val outIdx = mc.dequeueOutputBuffer(info, 0L)
                    if (outIdx >= 0) {
                        val outBuf = mc.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            val pcm = ByteArray(info.size)
                            outBuf.position(info.offset)
                            outBuf.get(pcm, 0, info.size)
                            track.write(pcm, 0, pcm.size)
                        }
                        mc.releaseOutputBuffer(outIdx, false)
                    } else {
                        break
                    }
                }
            } catch (_: Throwable) {
                // Ignore transient packet errors
            }
        }
    }

    fun reset() {
        synchronized(lock) {
            resetLocked()
        }
    }

    private fun resetLocked() {
        val oldCodec = codec
        codec = null
        runCatching { oldCodec?.stop() }
        runCatching { oldCodec?.release() }

        val oldTrack = audioTrack
        audioTrack = null
        runCatching { oldTrack?.stop() }
        runCatching { oldTrack?.release() }
        ptsUs = 0L
    }
}

/**
 * Direct H.264 Annex-B stream muxer into MP4 using [MediaMuxer] without re-encoding.
 */
class H264Mp4Recorder(
    private val outputFile: File,
    private val width: Int,
    private val height: Int,
) {
    private val lock = Any()
    private var muxer: MediaMuxer? = null
    private var trackIndex: Int = -1
    private var started: Boolean = false
    private var startNs: Long = 0L

    fun writeAccessUnit(data: ByteArray, offset: Int, length: Int, isKeyframe: Boolean) {
        synchronized(lock) {
            try {
                if (!started) {
                    if (!isKeyframe) return
                    val spsPps = extractAnnexBSpsPps(data, offset, length) ?: return
                    val m = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    val format = MediaFormat.createVideoFormat(
                        MediaFormat.MIMETYPE_VIDEO_AVC,
                        width.coerceAtLeast(640),
                        height.coerceAtLeast(480),
                    )
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(spsPps.sps))
                    format.setByteBuffer("csd-1", ByteBuffer.wrap(spsPps.pps))
                    trackIndex = m.addTrack(format)
                    m.start()
                    muxer = m
                    started = true
                    startNs = System.nanoTime()
                }
                val m = muxer ?: return
                val buf = ByteBuffer.wrap(data, offset, length)
                val info = MediaCodec.BufferInfo().apply {
                    this.offset = offset
                    this.size = length
                    this.presentationTimeUs = (System.nanoTime() - startNs) / 1_000L
                    this.flags = if (isKeyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                }
                m.writeSampleData(trackIndex, buf, info)
            } catch (_: Throwable) {
                // Ignore muxer glitches
            }
        }
    }

    fun stop(): File? {
        synchronized(lock) {
            val m = muxer
            muxer = null
            val wasStarted = started
            started = false
            if (m != null && wasStarted) {
                runCatching { m.stop() }
            }
            runCatching { m?.release() }
            return if (wasStarted && outputFile.exists() && outputFile.length() > 0L) outputFile else null
        }
    }
}

/**
 * Captures a PNG [Bitmap] from a live [SurfaceView] via [PixelCopy].
 */
fun captureSurfaceViewBitmap(surfaceView: SurfaceView, onResult: (Bitmap?) -> Unit) {
    val w = surfaceView.width
    val h = surfaceView.height
    if (w <= 0 || h <= 0 || !surfaceView.holder.surface.isValid) {
        onResult(null)
        return
    }
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    try {
        PixelCopy.request(
            surfaceView,
            bitmap,
            { copyResult ->
                if (copyResult == PixelCopy.SUCCESS) onResult(bitmap) else onResult(null)
            },
            Handler(Looper.getMainLooper()),
        )
    } catch (_: Throwable) {
        onResult(null)
    }
}
