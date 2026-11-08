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
import android.os.HandlerThread
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceView
import com.hienlh.ppm.state.remotedesktop.BoundedVideoFrameQueue
import com.hienlh.ppm.state.remotedesktop.H264SpsPps
import com.hienlh.ppm.state.remotedesktop.QueuedVideoFrame
import com.hienlh.ppm.state.remotedesktop.RdSize
import com.hienlh.ppm.state.remotedesktop.buildOpusHeadCsd
import com.hienlh.ppm.state.remotedesktop.buildOpusNanosecondsCsd
import com.hienlh.ppm.state.remotedesktop.extractAnnexBSpsPps
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Low-latency Android [MediaCodec] H.264 Annex-B decoder rendering to a [Surface] on a dedicated
 * background decode thread with a [BoundedVideoFrameQueue], Opus audio player ([AudioTrack]),
 * MP4 stream recorder ([MediaMuxer]), and [PixelCopy] screenshot helper.
 */
open class H264SurfaceDecoder(
    private val onFrameSizeChanged: (RdSize) -> Unit = {},
    private val onStatusChanged: (String, String?) -> Unit = { _, _ -> },
    queueCapacity: Int = 8,
) {
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var activeSpsPps: H264SpsPps? = null
    private var waitingForKeyframe: Boolean = true
    private var ptsUs: Long = 0L
    private var released: Boolean = false

    val frameQueue = BoundedVideoFrameQueue(queueCapacity)
    val frameCount = AtomicInteger(0)
    @Volatile
    var frameSize: RdSize = RdSize(1920, 1080)
        private set

    private val drainRunnable = Runnable { drainQueueOnWorker() }

    private fun ensureWorkerLocked(): Handler? {
        if (released) return null
        val existing = workerHandler
        if (existing != null && workerThread?.isAlive == true) return existing
        val thread = HandlerThread("ppm-rd-video-decoder").apply { start() }
        val handler = Handler(thread.looper)
        workerThread = thread
        workerHandler = handler
        return handler
    }

    private fun postStatus(status: String, error: String?) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onStatusChanged(status, error)
        } else {
            mainHandler.post { onStatusChanged(status, error) }
        }
    }

    private fun postFrameSize(size: RdSize) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onFrameSizeChanged(size)
        } else {
            mainHandler.post { onFrameSizeChanged(size) }
        }
    }

    fun attachSurface(newSurface: Surface?) {
        synchronized(lock) {
            if (released) return
            if (surface === newSurface) return
            surface = newSurface
            frameQueue.clear()
            waitingForKeyframe = true
            ensureWorkerLocked()?.post {
                synchronized(lock) {
                    releaseCodecLocked()
                    waitingForKeyframe = true
                }
            }
        }
    }

    fun configure() {
        synchronized(lock) {
            if (released) return
            frameQueue.clear()
            waitingForKeyframe = true
            ensureWorkerLocked()?.post {
                synchronized(lock) {
                    releaseCodecLocked()
                    waitingForKeyframe = true
                }
            }
            postStatus("ready", null)
        }
    }

    internal val isWorkerAlive: Boolean
        get() = synchronized(lock) { workerThread?.isAlive == true }

    fun reset() {
        synchronized(lock) {
            if (released) return
            frameQueue.clear()
            waitingForKeyframe = true
            ptsUs = 0L
            activeSpsPps = null
            val handler = workerHandler
            val thread = workerThread
            if (handler != null && thread != null && thread.isAlive) {
                handler.removeCallbacksAndMessages(null)
                handler.post {
                    synchronized(lock) {
                        releaseCodecLocked()
                        waitingForKeyframe = true
                        ptsUs = 0L
                        activeSpsPps = null
                    }
                }
            } else {
                releaseCodecLocked()
            }
        }
    }

    fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            frameQueue.clear()
            waitingForKeyframe = true
            ptsUs = 0L
            activeSpsPps = null
            surface = null
            val handler = workerHandler
            val thread = workerThread
            workerHandler = null
            workerThread = null
            if (handler != null && thread != null && thread.isAlive) {
                handler.removeCallbacksAndMessages(null)
                val posted = handler.post {
                    synchronized(lock) {
                        releaseCodecLocked()
                    }
                    thread.quitSafely()
                }
                if (!posted) {
                    releaseCodecLocked()
                    thread.quitSafely()
                }
            } else {
                releaseCodecLocked()
                thread?.quitSafely()
            }
        }
    }

    fun enqueueAccessUnit(data: ByteArray, offset: Int, length: Int, isKeyframe: Boolean): Boolean {
        synchronized(lock) {
            if (released) return false
            val accepted = frameQueue.offer(data, offset, length, isKeyframe)
            if (accepted) {
                ensureWorkerLocked()?.post(drainRunnable)
            }
            return accepted
        }
    }

    fun decodeAccessUnit(data: ByteArray, offset: Int, length: Int, isKeyframe: Boolean) {
        enqueueAccessUnit(data, offset, length, isKeyframe)
    }

    private fun drainQueueOnWorker() {
        while (true) {
            val frame: QueuedVideoFrame = frameQueue.poll() ?: break
            decodeFrameLocked(frame.bytes, frame.offset, frame.length, frame.isKeyframe)
        }
    }

    private fun decodeFrameLocked(data: ByteArray, offset: Int, length: Int, isKeyframe: Boolean) {
        synchronized(lock) {
            val targetSurface = surface
            if (targetSurface == null || !targetSurface.isValid) {
                waitingForKeyframe = true
                frameQueue.markWaitingForKeyframe()
                return
            }
            if (waitingForKeyframe && !isKeyframe) {
                frameQueue.markWaitingForKeyframe()
                return
            }

            val spsPps = if (isKeyframe) extractAnnexBSpsPps(data, offset, length) else null
            if (codec == null || (spsPps != null && activeSpsPps != null && spsPps != activeSpsPps)) {
                if (!startCodecLocked(targetSurface, spsPps)) {
                    waitingForKeyframe = true
                    frameQueue.markWaitingForKeyframe()
                    return
                }
            }

            val activeCodec = codec ?: return
            try {
                drainOutputLocked(activeCodec)
                val inIdx = activeCodec.dequeueInputBuffer(2_000L)
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
                } else {
                    // Could not obtain input buffer in time: drop frame and wait for next keyframe
                    waitingForKeyframe = true
                    frameQueue.markWaitingForKeyframe()
                }
                drainOutputLocked(activeCodec)
            } catch (e: Throwable) {
                // Decoder error or resolution switch: rebuild on next keyframe (<= 1s)
                releaseCodecLocked()
                waitingForKeyframe = true
                frameQueue.markWaitingForKeyframe()
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
            postStatus("ready", null)
            true
        } catch (e: Throwable) {
            postStatus("error", e.message ?: "H.264 decoder failed to start")
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
                            postFrameSize(nextSize)
                        }
                    }
                }
                else -> break
            }
        }
    }

    protected open fun releaseCodecLocked() {
        val old = codec ?: return
        codec = null
        runCatching { old.stop() }
        runCatching { old.release() }
    }
}

/**
 * Low-latency Opus audio decoder (`audio/opus`) + [AudioTrack] playback on a dedicated background
 * audio thread with a bounded packet queue so Main is never blocked.
 */
class OpusAudioPlayer(
    private val maxPendingPackets: Int = 16,
    private val pcmDecoder: ((ByteArray) -> List<ByteArray>)? = null,
) {
    private val lock = Any()
    private val packetQueue = ArrayDeque<ByteArray>(maxPendingPackets)
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var codec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var ptsUs: Long = 0L
    private var released: Boolean = false

    internal fun attachAudioTrack(track: AudioTrack) {
        synchronized(lock) {
            if (released) return
            audioTrack = track
        }
    }

    private val drainAudioRunnable = Runnable { drainAudioOnWorker() }

    private fun ensureWorkerLocked(): Handler? {
        if (released) return null
        val existing = workerHandler
        if (existing != null && workerThread?.isAlive == true) return existing
        val thread = HandlerThread("ppm-rd-audio-decoder").apply { start() }
        val handler = Handler(thread.looper)
        workerThread = thread
        workerHandler = handler
        return handler
    }

    fun configure(channels: Int = 2, sampleRate: Int = 48_000) {
        synchronized(lock) {
            if (released) return
            packetQueue.clear()
            ensureWorkerLocked()?.post {
                synchronized(lock) {
                    if (released) return@post
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
        }
    }

    fun decodePacket(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        val packet = data.copyOfRange(offset, offset + length)
        synchronized(lock) {
            if (released) return
            while (packetQueue.size >= maxPendingPackets) {
                packetQueue.removeFirst()
            }
            packetQueue.addLast(packet)
            ensureWorkerLocked()?.post(drainAudioRunnable)
        }
    }

    private fun drainAudioOnWorker() {
        while (true) {
            val pcmChunks = ArrayList<ByteArray>(2)
            val track: AudioTrack
            synchronized(lock) {
                if (released || packetQueue.isEmpty()) return
                val packet = packetQueue.removeFirst()
                val currentTrack = audioTrack ?: return
                track = currentTrack
                val customDecoder = pcmDecoder
                if (customDecoder != null) {
                    pcmChunks.addAll(customDecoder(packet))
                } else {
                    val mc = codec ?: return
                    try {
                        val inIdx = mc.dequeueInputBuffer(2_000L)
                        if (inIdx >= 0) {
                            val inBuf = mc.getInputBuffer(inIdx)
                            if (inBuf != null) {
                                inBuf.clear()
                                val copyLen = minOf(packet.size, inBuf.remaining())
                                inBuf.put(packet, 0, copyLen)
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
                                    pcmChunks.add(pcm)
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
            for (pcm in pcmChunks) {
                val canWrite = synchronized(lock) { !released && audioTrack === track }
                if (!canWrite) break
                runCatching { writePcmToTrack(track, pcm) }
            }
        }
    }

    internal val isWorkerAlive: Boolean
        get() = synchronized(lock) { workerThread?.isAlive == true }

    internal fun writePcmToTrack(track: AudioTrack, pcm: ByteArray): Int =
        track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)

    fun reset() {
        synchronized(lock) {
            if (released) return
            packetQueue.clear()
            val handler = workerHandler
            val thread = workerThread
            if (handler != null && thread != null && thread.isAlive) {
                handler.removeCallbacksAndMessages(null)
                handler.post {
                    synchronized(lock) {
                        resetLocked()
                    }
                }
            } else {
                resetLocked()
            }
        }
    }

    fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            packetQueue.clear()
            val handler = workerHandler
            val thread = workerThread
            workerHandler = null
            workerThread = null
            if (handler != null && thread != null && thread.isAlive) {
                handler.removeCallbacksAndMessages(null)
                val posted = handler.post {
                    synchronized(lock) {
                        resetLocked()
                    }
                    thread.quitSafely()
                }
                if (!posted) {
                    resetLocked()
                    thread.quitSafely()
                }
            } else {
                resetLocked()
                thread?.quitSafely()
            }
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
 * Video-only (no audio track).
 */
class H264Mp4Recorder(
    private val outputFile: File,
    private val width: Int,
    private val height: Int,
    private val sizeProvider: () -> RdSize = { RdSize(width, height) },
) {
    private val lock = Any()
    private var muxer: MediaMuxer? = null
    private var trackIndex: Int = -1
    private var started: Boolean = false
    private var startNs: Long = 0L

    @Volatile
    var recordedWidth: Int = width.coerceAtLeast(1)
        private set

    @Volatile
    var recordedHeight: Int = height.coerceAtLeast(1)
        private set

    @Volatile
    var isStopped: Boolean = false
        private set

    fun writeAccessUnit(data: ByteArray, offset: Int, length: Int, isKeyframe: Boolean) {
        synchronized(lock) {
            if (isStopped) return
            try {
                if (!started) {
                    if (!isKeyframe) return
                    val spsPps = extractAnnexBSpsPps(data, offset, length) ?: return
                    val liveSize = sizeProvider()
                    val recW = (liveSize.width.takeIf { it > 0 } ?: width).coerceAtLeast(1)
                    val recH = (liveSize.height.takeIf { it > 0 } ?: height).coerceAtLeast(1)
                    recordedWidth = recW
                    recordedHeight = recH
                    val m = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    val format = MediaFormat.createVideoFormat(
                        MediaFormat.MIMETYPE_VIDEO_AVC,
                        recW,
                        recH,
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
            isStopped = true
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
 * Allocates the destination [Bitmap] for a screenshot at [frameSize] when positive, or
 * falls back to [surfaceWidth] × [surfaceHeight].
 */
fun createScreenshotTargetBitmap(
    surfaceWidth: Int,
    surfaceHeight: Int,
    frameSize: RdSize? = null,
): Bitmap? {
    val w = frameSize?.width?.takeIf { it > 0 } ?: surfaceWidth
    val h = frameSize?.height?.takeIf { it > 0 } ?: surfaceHeight
    if (w <= 0 || h <= 0) return null
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
}

/**
 * Captures a PNG [Bitmap] from a live [SurfaceView] via [PixelCopy] scaled to [targetSize]
 * (`frameSize.width` × `frameSize.height`).
 */
fun captureSurfaceViewBitmap(
    surfaceView: SurfaceView,
    targetSize: RdSize? = null,
    onResult: (Bitmap?) -> Unit,
) {
    if (surfaceView.width <= 0 || surfaceView.height <= 0 || !surfaceView.holder.surface.isValid) {
        onResult(null)
        return
    }
    val bitmap = createScreenshotTargetBitmap(surfaceView.width, surfaceView.height, targetSize)
    if (bitmap == null) {
        onResult(null)
        return
    }
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
