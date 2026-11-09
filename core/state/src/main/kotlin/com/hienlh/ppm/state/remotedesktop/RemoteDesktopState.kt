package com.hienlh.ppm.state.remotedesktop

import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure Kotlin state & logic for Phase 11b Remote Desktop:
 * - Quality ladder & RustDesk custom quality (`remote-desktop-quality.ts`, `remote-desktop-custom-quality.ts`)
 * - View style & piecewise custom scale slider (`remote-desktop-view-style.ts`)
 * - Coordinate mapping & letterbox (`remote-desktop-coords.ts`)
 * - Touch gesture classifiers & 1/2-finger trackers (`remote-desktop-gesture-classifiers.ts`, `remote-desktop-touch-gesture-tracker.ts`)
 * - Capabilities normalization, host resolution collapse, codec labels, stats (`use-remote-desktop-readiness.ts`, `remote-desktop-resolution-list.ts`, `remote-desktop-codec-labels.ts`, `remote-desktop-stats.ts`)
 * - Android KeyEvent -> W3C physical `KeyboardEvent.code` table & mobile key bar
 * - H.264 Annex-B SPS/PPS extraction & Opus CSD header builders
 */

// ── 1. Quality Ladder & Custom Quality ────────────────────────────────

data class QualityRung(
    val id: String,
    val label: String,
    val ratio: Double,
)

val QUALITY_PRESETS: Map<String, QualityRung> = linkedMapOf(
    "best" to QualityRung("best", "Good image quality", 1.5),
    "balanced" to QualityRung("balanced", "Balanced", 0.67),
    "low" to QualityRung("low", "Optimize reaction time", 0.5),
)

val QUALITY_PRESET_ORDER: List<String> = listOf("best", "balanced", "low")
const val DEFAULT_PRESET_ID = "balanced"
const val DEFAULT_FPS = 30

fun parsePresetId(value: String?): String? =
    if (value != null && QUALITY_PRESETS.containsKey(value)) value else null

fun parseQualityChoice(value: String?): String =
    if (value == "custom") "custom" else parsePresetId(value) ?: DEFAULT_PRESET_ID

fun nextQualityChoice(current: String): String {
    if (current == "custom") return QUALITY_PRESET_ORDER.first()
    val idx = QUALITY_PRESET_ORDER.indexOf(current)
    return if (idx < 0) DEFAULT_PRESET_ID else QUALITY_PRESET_ORDER[(idx + 1) % QUALITY_PRESET_ORDER.size]
}

fun shortQualityLabel(choice: String): String = when (choice) {
    "best" -> "Good"
    "balanced" -> "Balanced"
    "low" -> "Reaction"
    "custom" -> "Custom"
    else -> "Balanced"
}

const val CUSTOM_QUALITY_MIN_PERCENT = 10
const val CUSTOM_QUALITY_DEFAULT_PERCENT = 50
const val CUSTOM_QUALITY_MAX_PERCENT = 100
const val CUSTOM_QUALITY_MAX_MORE_PERCENT = 2000

const val CUSTOM_FPS_MIN = 5
const val CUSTOM_FPS_DEFAULT = 30
const val CUSTOM_FPS_MAX = 120

private const val BR_MIN = 0.2
private const val BR_MAX = 40.0

private val RESOLUTION_BITRATE_PRESETS: List<Triple<Int, Int, Int>> = listOf(
    Triple(640, 480, 400),
    Triple(800, 600, 500),
    Triple(1024, 768, 800),
    Triple(1280, 720, 1000),
    Triple(1366, 768, 1100),
    Triple(1440, 900, 1300),
    Triple(1600, 900, 1500),
    Triple(1920, 1080, 2073),
    Triple(2048, 1080, 2200),
    Triple(2560, 1440, 3000),
    Triple(3440, 1440, 4000),
    Triple(3840, 2160, 5000),
    Triple(7680, 4320, 12000),
)

fun baseBitrateKbps(width: Int, height: Int): Int {
    val pixels = max(1L, width.toLong() * height.toLong())
    var best = RESOLUTION_BITRATE_PRESETS[7]
    var bestDistance = Long.MAX_VALUE
    for (preset in RESOLUTION_BITRATE_PRESETS) {
        val pPixels = preset.first.toLong() * preset.second.toLong()
        val distance = abs(pPixels - pixels)
        if (distance < bestDistance) {
            bestDistance = distance
            best = preset
        }
    }
    val presetPixels = best.first.toDouble() * best.second.toDouble()
    return max(1, (best.third * (pixels.toDouble() / presetPixels)).roundToInt())
}

fun customQualityRatio(percent: Int): Double =
    ((percent * 2.0) / 100.0).coerceIn(BR_MIN, BR_MAX)

fun clampRatio(ratio: Double): Double =
    if (!ratio.isFinite()) BR_MIN else ratio.coerceIn(BR_MIN, BR_MAX)

fun ratioBitrateKbps(ratio: Double, width: Int, height: Int): Int =
    max(1, (baseBitrateKbps(width, height) * clampRatio(ratio)).roundToInt())

fun customBitrateKbps(percent: Int, width: Int, height: Int): Int =
    ratioBitrateKbps(customQualityRatio(percent), width, height)

fun clampCustomQualityPercent(value: Int?, allowMore: Boolean = false): Int {
    val maxVal = if (allowMore) CUSTOM_QUALITY_MAX_MORE_PERCENT else CUSTOM_QUALITY_MAX_PERCENT
    return (value ?: CUSTOM_QUALITY_DEFAULT_PERCENT).coerceIn(CUSTOM_QUALITY_MIN_PERCENT, maxVal)
}

fun clampCustomFps(value: Int?): Int =
    (value ?: CUSTOM_FPS_DEFAULT).coerceIn(CUSTOM_FPS_MIN, CUSTOM_FPS_MAX)

// ── 2. View Style & Scale ─────────────────────────────────────────────

const val DEFAULT_VIEW_STYLE = "adaptive"
val VIEW_STYLE_ORDER: List<String> = listOf("original", "adaptive", "custom")
val VIEW_STYLE_LABELS: Map<String, String> = mapOf(
    "original" to "Scale original",
    "adaptive" to "Scale adaptive",
    "custom" to "Scale custom",
)

const val CUSTOM_SCALE_MIN_PERCENT = 5
const val CUSTOM_SCALE_MAX_PERCENT = 1000
const val CUSTOM_SCALE_PIVOT_PERCENT = 100
const val CUSTOM_SCALE_PIVOT_POS = 1.0 / 3.0
const val CUSTOM_SCALE_DETENT = 0.006
const val CUSTOM_SCALE_NUDGE_PERCENT = 1
const val CUSTOM_SCALE_MIN = CUSTOM_SCALE_MIN_PERCENT / 100.0
const val CUSTOM_SCALE_MAX = CUSTOM_SCALE_MAX_PERCENT / 100.0
const val DEFAULT_CUSTOM_SCALE = 1.0

fun clampScalePercent(percent: Int): Int =
    percent.coerceIn(CUSTOM_SCALE_MIN_PERCENT, CUSTOM_SCALE_MAX_PERCENT)

fun scalePosToPercent(pos: Double): Int {
    if (!pos.isFinite() || pos <= 0.0) return CUSTOM_SCALE_MIN_PERCENT
    if (pos >= 1.0) return CUSTOM_SCALE_MAX_PERCENT
    return if (pos <= CUSTOM_SCALE_PIVOT_POS) {
        val q = pos / CUSTOM_SCALE_PIVOT_POS
        clampScalePercent((CUSTOM_SCALE_MIN_PERCENT + q * (CUSTOM_SCALE_PIVOT_PERCENT - CUSTOM_SCALE_MIN_PERCENT)).roundToInt())
    } else {
        val q = (pos - CUSTOM_SCALE_PIVOT_POS) / (1.0 - CUSTOM_SCALE_PIVOT_POS)
        clampScalePercent((CUSTOM_SCALE_PIVOT_PERCENT + q * (CUSTOM_SCALE_MAX_PERCENT - CUSTOM_SCALE_PIVOT_PERCENT)).roundToInt())
    }
}

fun scalePercentToPos(percent: Int): Double {
    val p = clampScalePercent(percent).toDouble()
    return if (p <= CUSTOM_SCALE_PIVOT_PERCENT) {
        val q = (p - CUSTOM_SCALE_MIN_PERCENT) / (CUSTOM_SCALE_PIVOT_PERCENT - CUSTOM_SCALE_MIN_PERCENT)
        q * CUSTOM_SCALE_PIVOT_POS
    } else {
        val q = (p - CUSTOM_SCALE_PIVOT_PERCENT) / (CUSTOM_SCALE_MAX_PERCENT - CUSTOM_SCALE_PIVOT_PERCENT)
        CUSTOM_SCALE_PIVOT_POS + q * (1.0 - CUSTOM_SCALE_PIVOT_POS)
    }
}

fun snapScalePos(pos: Double): Double {
    if (!pos.isFinite()) return CUSTOM_SCALE_PIVOT_POS
    if (abs(pos - CUSTOM_SCALE_PIVOT_POS) <= CUSTOM_SCALE_DETENT) return CUSTOM_SCALE_PIVOT_POS
    return pos.coerceIn(0.0, 1.0)
}

fun parseViewStyle(value: String?): String =
    if (value != null && VIEW_STYLE_LABELS.containsKey(value)) value else DEFAULT_VIEW_STYLE

fun clampCustomScale(value: Double?): Double =
    if (value == null || !value.isFinite()) DEFAULT_CUSTOM_SCALE else value.coerceIn(CUSTOM_SCALE_MIN, CUSTOM_SCALE_MAX)

fun stepCustomScale(current: Double, direction: Int): Double {
    val step = if (direction >= 0) 1 else -1
    val next = clampScalePercent((clampCustomScale(current) * 100.0).roundToInt() + step * CUSTOM_SCALE_NUDGE_PERCENT)
    return next / 100.0
}

data class RdSize(val width: Int, val height: Int)

fun canvasCssSize(style: String, customScale: Double, natural: RdSize): RdSize? {
    if (style == "adaptive") return null
    if (natural.width <= 0 || natural.height <= 0) return null
    val scale = if (style == "original") 1.0 else clampCustomScale(customScale)
    return RdSize(
        width = (natural.width * scale).roundToInt(),
        height = (natural.height * scale).roundToInt(),
    )
}

fun viewStyleShortLabel(style: String, customScale: Double): String = when (style) {
    "adaptive" -> "Fit"
    "original" -> "1:1"
    else -> "${(clampCustomScale(customScale) * 100.0).roundToInt()}%"
}

// ── 3. Coordinate Mapping & Letterbox ─────────────────────────────────

data class RdFraction(val xFrac: Double, val yFrac: Double)

data class RdRect(
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double,
)

data class ZoomPanTransform(
    val scale: Double = 1.0,
    val panX: Double = 0.0,
    val panY: Double = 0.0,
)

val IDENTITY_TRANSFORM = ZoomPanTransform(1.0, 0.0, 0.0)

fun fractionFromPoint(clientX: Double, clientY: Double, rect: RdRect): RdFraction {
    val xFrac = if (rect.width > 0.0) (clientX - rect.left) / rect.width else 0.0
    val yFrac = if (rect.height > 0.0) (clientY - rect.top) / rect.height else 0.0
    return RdFraction(xFrac.coerceIn(0.0, 1.0), yFrac.coerceIn(0.0, 1.0))
}

fun fractionFromZoomedPoint(
    clientX: Double,
    clientY: Double,
    rect: RdRect,
    transform: ZoomPanTransform,
): RdFraction {
    val scale = if (transform.scale > 0.0) transform.scale else 1.0
    val cx = rect.left + rect.width / 2.0
    val cy = rect.top + rect.height / 2.0
    val baseX = cx + (clientX - cx - transform.panX) / scale
    val baseY = cy + (clientY - cy - transform.panY) / scale
    return fractionFromPoint(baseX, baseY, rect)
}

fun letterboxedContentRect(box: RdRect, contentWidth: Int, contentHeight: Int): RdRect {
    if (contentWidth <= 0 || contentHeight <= 0 || box.width <= 0.0 || box.height <= 0.0) return box
    val boxAspect = box.width / box.height
    val contentAspect = contentWidth.toDouble() / contentHeight.toDouble()
    return if (contentAspect > boxAspect) {
        val height = box.width / contentAspect
        RdRect(
            left = box.left,
            top = box.top + (box.height - height) / 2.0,
            width = box.width,
            height = height,
        )
    } else {
        val width = box.height * contentAspect
        RdRect(
            left = box.left + (box.width - width) / 2.0,
            top = box.top,
            width = width,
            height = box.height,
        )
    }
}

/**
 * On-screen video rectangle inside the stage container for both `SurfaceView` sizing and
 * pointer coordinate mapping across `adaptive`, `original`, and `custom` scale modes.
 */
fun computeStageVideoRect(
    containerWidthPx: Double,
    containerHeightPx: Double,
    frameSize: RdSize,
    viewStyle: String = DEFAULT_VIEW_STYLE,
    customScale: Double = DEFAULT_CUSTOM_SCALE,
    mobile: Boolean = false,
    densityScale: Double = 1.0,
): RdRect {
    val outer = RdRect(
        left = 0.0,
        top = 0.0,
        width = containerWidthPx.coerceAtLeast(1.0),
        height = containerHeightPx.coerceAtLeast(1.0),
    )
    val css = if (!mobile) canvasCssSize(viewStyle, customScale, frameSize) else null
    if (css == null) {
        return letterboxedContentRect(outer, frameSize.width, frameSize.height)
    }
    val wPx = (css.width * densityScale.coerceAtLeast(0.1)).coerceAtLeast(1.0)
    val hPx = (css.height * densityScale.coerceAtLeast(0.1)).coerceAtLeast(1.0)
    val left = (outer.width - wPx) / 2.0
    val top = (outer.height - hPx) / 2.0
    return RdRect(left = left, top = top, width = wPx, height = hPx)
}

/**
 * Bounded access-unit queue for off-main H.264 decoding.
 * Drops delta frames when waiting for a keyframe or when at capacity, and sets
 * [waitingForKeyframe] = `true` whenever a delta frame is dropped so subsequent
 * deltas in the same GOP are not fed to the decoder until the next keyframe arrives.
 */
class QueuedVideoFrame(
    val bytes: ByteArray,
    val offset: Int,
    val length: Int,
    val isKeyframe: Boolean,
)

class BoundedVideoFrameQueue(val capacity: Int = 8) {
    private val lock = Any()
    private val queue = ArrayDeque<QueuedVideoFrame>(capacity.coerceAtLeast(1))

    @Volatile
    var waitingForKeyframe: Boolean = true
        private set

    @Volatile
    var droppedDeltas: Int = 0
        private set

    val size: Int
        get() = synchronized(lock) { queue.size }

    fun offer(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset, isKeyframe: Boolean): Boolean {
        synchronized(lock) {
            if (!isKeyframe) {
                if (waitingForKeyframe) {
                    droppedDeltas++
                    return false
                }
                if (queue.size >= capacity) {
                    waitingForKeyframe = true
                    droppedDeltas++
                    return false
                }
                queue.addLast(QueuedVideoFrame(bytes, offset, length, false))
                return true
            }
            while (queue.isNotEmpty()) {
                val removed = queue.removeFirst()
                if (!removed.isKeyframe) droppedDeltas++
            }
            waitingForKeyframe = false
            queue.addLast(QueuedVideoFrame(bytes, offset, length, true))
            return true
        }
    }

    fun poll(): QueuedVideoFrame? = synchronized(lock) {
        if (queue.isEmpty()) null else queue.removeFirst()
    }

    fun markWaitingForKeyframe() {
        synchronized(lock) {
            while (queue.isNotEmpty() && !queue.first().isKeyframe) {
                queue.removeFirst()
                droppedDeltas++
            }
            waitingForKeyframe = queue.none { it.isKeyframe }
        }
    }

    fun clear() {
        synchronized(lock) {
            queue.clear()
            waitingForKeyframe = true
        }
    }
}

// ── 4. Touch Gesture Classifiers & Trackers ───────────────────────────

data class PointerSample(
    val x: Double,
    val y: Double,
    val t: Long,
)

data class RdPoint(val x: Double, val y: Double)

const val TAP_MOVE_THRESHOLD_PX = 10.0
const val TAP_MAX_DURATION_MS = 300L
const val TWO_FINGER_THRESHOLD_PX = 15.0
const val SCALE_MIN = 1.0
const val SCALE_MAX = 4.0
const val WHEEL_PIXELS_PER_NOTCH = 40.0
const val WHEEL_DELTA = 120.0

fun isTapGesture(
    start: PointerSample,
    end: PointerSample,
    moveThresholdPx: Double = TAP_MOVE_THRESHOLD_PX,
    maxDurationMs: Long = TAP_MAX_DURATION_MS,
): Boolean {
    val dist = hypot(end.x - start.x, end.y - start.y)
    val duration = end.t - start.t
    return dist <= moveThresholdPx && duration <= maxDurationMs
}

fun classifyTwoFingerGesture(
    startDistance: Double,
    currentDistance: Double,
    distanceThresholdPx: Double = TWO_FINGER_THRESHOLD_PX,
): String = if (abs(currentDistance - startDistance) > distanceThresholdPx) "pinch" else "scroll"

fun touchDistance(a: RdPoint, b: RdPoint): Double = hypot(b.x - a.x, b.y - a.y)

fun touchMidpoint(a: RdPoint, b: RdPoint): RdPoint = RdPoint((a.x + b.x) / 2.0, (a.y + b.y) / 2.0)

fun pointerJsonMessage(xFrac: Double, yFrac: Double, button: String?, down: Boolean?): JsonElement =
    buildJsonObject {
        put("type", "pointer")
        put("xFrac", xFrac)
        put("yFrac", yFrac)
        put("button", button?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
        put("down", down?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
    }

fun clickMessages(xFrac: Double, yFrac: Double, button: String): List<JsonElement> = listOf(
    pointerJsonMessage(xFrac, yFrac, button, true),
    pointerJsonMessage(xFrac, yFrac, button, false),
)

class SingleTrack(
    val start: PointerSample,
    var last: RdPoint,
    var moved: Boolean,
    val rect: RdRect,
)

data class SingleMoveResult(
    val message: JsonElement? = null,
    val cursor: RdFraction? = null,
    val sendCursorMove: Boolean = false,
)

fun beginSingleTouch(x: Double, y: Double, rect: RdRect, nowMs: Long = System.currentTimeMillis()): SingleTrack =
    SingleTrack(
        start = PointerSample(x, y, nowMs),
        last = RdPoint(x, y),
        moved = false,
        rect = rect,
    )

fun advanceSingleTouch(
    track: SingleTrack,
    x: Double,
    y: Double,
    mode: String,
    transform: ZoomPanTransform,
    cursor: RdFraction,
    densityScale: Double = 1.0,
): SingleMoveResult {
    val tapThresholdPx = TAP_MOVE_THRESHOLD_PX * densityScale.coerceAtLeast(0.1)
    val dist = hypot(x - track.start.x, y - track.start.y)
    if (mode == "touch") {
        if (!track.moved && dist > tapThresholdPx) {
            track.moved = true
            val start = fractionFromZoomedPoint(track.start.x, track.start.y, track.rect, transform)
            track.last = RdPoint(x, y)
            return SingleMoveResult(message = pointerJsonMessage(start.xFrac, start.yFrac, "left", true))
        }
        track.last = RdPoint(x, y)
        if (!track.moved) return SingleMoveResult()
        val frac = fractionFromZoomedPoint(x, y, track.rect, transform)
        return SingleMoveResult(message = pointerJsonMessage(frac.xFrac, frac.yFrac, null, null))
    }

    val scale = if (transform.scale > 0.0) transform.scale else 1.0
    val dx = (x - track.last.x) / scale
    val dy = (y - track.last.y) / scale
    if (dist > tapThresholdPx) track.moved = true
    track.last = RdPoint(x, y)
    val next = RdFraction(
        xFrac = (cursor.xFrac + if (track.rect.width > 0.0) dx / track.rect.width else 0.0).coerceIn(0.0, 1.0),
        yFrac = (cursor.yFrac + if (track.rect.height > 0.0) dy / track.rect.height else 0.0).coerceIn(0.0, 1.0),
    )
    return SingleMoveResult(cursor = next, sendCursorMove = true)
}

fun resolveSingleTouchEnd(
    track: SingleTrack,
    x: Double,
    y: Double,
    mode: String,
    transform: ZoomPanTransform,
    cursor: RdFraction,
    nowMs: Long = System.currentTimeMillis(),
    densityScale: Double = 1.0,
): List<JsonElement> {
    val tapThresholdPx = TAP_MOVE_THRESHOLD_PX * densityScale.coerceAtLeast(0.1)
    val end = PointerSample(x, y, nowMs)
    val tapped = isTapGesture(track.start, end, tapThresholdPx, TAP_MAX_DURATION_MS)
    if (mode == "touch") {
        val frac = fractionFromZoomedPoint(x, y, track.rect, transform)
        if (!track.moved && tapped) return clickMessages(frac.xFrac, frac.yFrac, "left")
        if (track.moved) return listOf(pointerJsonMessage(frac.xFrac, frac.yFrac, "left", false))
        return emptyList()
    }
    if (!track.moved && tapped) return clickMessages(cursor.xFrac, cursor.yFrac, "left")
    return emptyList()
}

/**
 * Releases a held left button when a touch gesture is cancelled mid-drag in `"touch"` mode
 * (`use-remote-desktop-touch.ts:197-207`).
 */
fun resolveSingleTouchCancel(
    track: SingleTrack?,
    mode: String,
    transform: ZoomPanTransform,
    cursor: RdFraction,
    densityScale: Double = 1.0,
): List<JsonElement> {
    if (track == null || !track.moved || mode != "touch") return emptyList()
    return resolveSingleTouchEnd(
        track = track,
        x = track.last.x,
        y = track.last.y,
        mode = "touch",
        transform = transform,
        cursor = cursor,
        densityScale = densityScale,
    )
}

class TwoFingerTrack(
    val startDistance: Double,
    val startMid: RdPoint,
    var lastMid: RdPoint,
    val startScale: Double,
    val startTime: Long,
    var gesture: String? = null,
)

data class TwoFingerMoveResult(
    val transform: ZoomPanTransform? = null,
    val wheelDeltaPx: Double? = null,
)

fun beginTwoFingerTouch(
    pa: RdPoint,
    pb: RdPoint,
    currentScale: Double,
    nowMs: Long = System.currentTimeMillis(),
): TwoFingerTrack {
    val mid = touchMidpoint(pa, pb)
    return TwoFingerTrack(
        startDistance = touchDistance(pa, pb),
        startMid = mid,
        lastMid = mid,
        startScale = currentScale,
        startTime = nowMs,
        gesture = null,
    )
}

fun advanceTwoFingerTouch(
    track: TwoFingerTrack,
    pa: RdPoint,
    pb: RdPoint,
    currentTransform: ZoomPanTransform,
    densityScale: Double = 1.0,
): TwoFingerMoveResult {
    val twoFingerThresholdPx = TWO_FINGER_THRESHOLD_PX * densityScale.coerceAtLeast(0.1)
    val currentDistance = touchDistance(pa, pb)
    val currentMid = touchMidpoint(pa, pb)

    if (track.gesture == null) {
        val distDiff = abs(currentDistance - track.startDistance)
        val midDist = touchDistance(track.startMid, currentMid)
        if (distDiff > twoFingerThresholdPx) {
            track.gesture = classifyTwoFingerGesture(track.startDistance, currentDistance, twoFingerThresholdPx)
        } else if (midDist > twoFingerThresholdPx) {
            track.gesture = "scroll"
        } else {
            track.lastMid = currentMid
            return TwoFingerMoveResult()
        }
    }

    val result = when {
        track.gesture == "pinch" -> {
            val ratio = if (track.startDistance > 0.0) currentDistance / track.startDistance else 1.0
            val scale = (track.startScale * ratio).coerceIn(SCALE_MIN, SCALE_MAX)
            TwoFingerMoveResult(transform = ZoomPanTransform(scale, currentTransform.panX, currentTransform.panY))
        }
        currentTransform.scale > 1.01 -> {
            val dx = currentMid.x - track.lastMid.x
            val dy = currentMid.y - track.lastMid.y
            TwoFingerMoveResult(
                transform = ZoomPanTransform(
                    scale = currentTransform.scale,
                    panX = currentTransform.panX + dx,
                    panY = currentTransform.panY + dy,
                ),
            )
        }
        else -> TwoFingerMoveResult(wheelDeltaPx = currentMid.y - track.lastMid.y)
    }
    track.lastMid = currentMid
    return result
}

fun wheelDeltaForTouchDrag(wheelDeltaPx: Double, densityScale: Double = 1.0): Double {
    val notchPx = WHEEL_PIXELS_PER_NOTCH * densityScale.coerceAtLeast(0.1)
    return -(wheelDeltaPx / notchPx) * WHEEL_DELTA
}

/**
 * Maps Android `MotionEvent` button bitmasks (`BUTTON_PRIMARY` = 1, `BUTTON_SECONDARY` = 2,
 * `BUTTON_TERTIARY` = 4, `BUTTON_STYLUS_PRIMARY` = 32, `BUTTON_STYLUS_SECONDARY` = 64) to the
 * remote-desktop protocol button (`"left"`, `"right"`, or `null` for middle/other, matching
 * web `toButton` in `use-remote-input-capture.ts:42`).
 */
fun androidMouseButtonToProtocol(buttonBits: Int): String? = when {
    (buttonBits and (1 or 32)) != 0 -> "left"
    (buttonBits and (2 or 64)) != 0 -> "right"
    else -> null
}

/**
 * Coalesces pointer move messages to at most one per animation frame (`use-remote-input-capture.ts:51-79`),
 * flushing any pending move immediately before a button down/up message so clicks land at the
 * exact press/release coordinates.
 */
class PointerMoveCoalescer(
    private val send: (JsonElement) -> Unit,
    private val scheduleFrame: (() -> Unit) -> Unit = { it() },
) {
    private var pendingMove: JsonElement? = null
    private var frameScheduled: Boolean = false

    val hasPendingMove: Boolean
        get() = pendingMove != null

    fun enqueueMove(xFrac: Double, yFrac: Double) {
        pendingMove = pointerJsonMessage(xFrac, yFrac, null, null)
        if (!frameScheduled) {
            frameScheduled = true
            scheduleFrame(::flush)
        }
    }

    fun flush() {
        frameScheduled = false
        val msg = pendingMove ?: return
        pendingMove = null
        send(msg)
    }

    fun sendImmediate(msg: JsonElement) {
        flush()
        send(msg)
    }

    fun sendImmediateAll(msgs: Iterable<JsonElement>) {
        flush()
        for (m in msgs) send(m)
    }

    fun cancel() {
        pendingMove = null
        frameScheduled = false
    }
}

fun resolveTwoFingerEnd(
    track: TwoFingerTrack,
    rect: RdRect,
    transform: ZoomPanTransform,
    nowMs: Long = System.currentTimeMillis(),
): List<JsonElement> {
    if (track.gesture == null && nowMs - track.startTime <= TAP_MAX_DURATION_MS) {
        val frac = fractionFromZoomedPoint(track.lastMid.x, track.lastMid.y, rect, transform)
        return clickMessages(frac.xFrac, frac.yFrac, "right")
    }
    return emptyList()
}

// ── 5. Capabilities, Resolutions, Codec Labels & Stats ────────────────

sealed class RequirementAction {
    abstract val label: String
    data class Terminal(override val label: String, val command: String) : RequirementAction()
    data class Install(override val label: String) : RequirementAction()
    data class Link(override val label: String, val url: String) : RequirementAction()
    data class Host(override val label: String, val action: String) : RequirementAction()

    companion object {
        fun from(json: JsonElement?): RequirementAction? {
            val kind = json["kind"].string ?: return null
            val label = json["label"].string ?: return null
            return when (kind) {
                "terminal" -> Terminal(label, json["command"].string ?: "")
                "install" -> Install(label)
                "link" -> Link(label, json["url"].string ?: "")
                "host" -> Host(label, json["action"].string ?: "request")
                else -> null
            }
        }
    }
}

data class RemoteDesktopRequirement(
    val id: String,
    val ok: Boolean,
    val gates: String,
    val title: String,
    val detail: String,
    val actions: List<RequirementAction>,
) {
    companion object {
        fun from(json: JsonElement?): RemoteDesktopRequirement? {
            val id = json["id"].string ?: return null
            return RemoteDesktopRequirement(
                id = id,
                ok = json["ok"].bool ?: false,
                gates = json["gates"].string ?: "video",
                title = json["title"].string ?: id,
                detail = json["detail"].string ?: "",
                actions = json["actions"].array.orEmpty().mapNotNull(RequirementAction::from),
            )
        }
    }
}

data class RemoteDisplay(
    val id: String,
    val label: String,
    val primary: Boolean,
    val width: Int,
    val height: Int,
) {
    companion object {
        fun from(json: JsonElement?): RemoteDisplay? {
            val id = json["id"].string ?: return null
            return RemoteDisplay(
                id = id,
                label = json["label"].string ?: id,
                primary = json["primary"].bool ?: false,
                width = json["width"].int ?: 0,
                height = json["height"].int ?: 0,
            )
        }
    }
}

data class AudioSupport(val available: Boolean = false, val reason: String? = null) {
    companion object {
        fun from(json: JsonElement?): AudioSupport =
            if (json?.obj == null) AudioSupport()
            else AudioSupport(
                available = json["available"].bool ?: false,
                reason = json["reason"].string,
            )
    }
}

data class PrivacySupport(
    val available: Boolean = false,
    val reason: String? = null,
    val canBlank: Boolean = false,
) {
    companion object {
        fun from(json: JsonElement?): PrivacySupport =
            if (json?.obj == null) PrivacySupport()
            else PrivacySupport(
                available = json["available"].bool ?: false,
                reason = json["reason"].string,
                canBlank = json["canBlank"].bool ?: false,
            )
    }
}

data class HostMode(
    val id: String,
    val width: Int,
    val height: Int,
    val refresh: Int,
    val current: Boolean,
    val preferred: Boolean,
) {
    companion object {
        fun from(json: JsonElement?): HostMode? {
            val id = json["id"].string ?: return null
            return HostMode(
                id = id,
                width = json["width"].int ?: 0,
                height = json["height"].int ?: 0,
                refresh = json["refresh"].int ?: 0,
                current = json["current"].bool ?: false,
                preferred = json["preferred"].bool ?: false,
            )
        }
    }
}

data class HostResolutions(
    val output: String? = null,
    val modes: List<HostMode> = emptyList(),
    val reason: String? = null,
) {
    companion object {
        fun from(json: JsonElement?): HostResolutions =
            if (json?.obj == null) HostResolutions()
            else HostResolutions(
                output = json["output"].string,
                modes = json["modes"].array.orEmpty().mapNotNull(HostMode::from),
                reason = json["reason"].string,
            )
    }
}

data class ClipboardSupport(
    val available: Boolean = false,
    val action: RequirementAction? = null,
) {
    companion object {
        fun from(json: JsonElement?): ClipboardSupport =
            if (json?.obj == null) ClipboardSupport()
            else ClipboardSupport(
                available = json["available"].bool ?: false,
                action = RequirementAction.from(json["action"]),
            )
    }
}

data class RemoteDesktopCapabilities(
    val displays: List<RemoteDisplay> = emptyList(),
    val encoders: List<String> = emptyList(),
    val ffmpegAvailable: Boolean = false,
    val videoAvailable: Boolean = false,
    val inputAvailable: Boolean = false,
    val authRequired: Boolean = true,
    val platform: String = "",
    val platformSupported: Boolean = true,
    val requirements: List<RemoteDesktopRequirement> = emptyList(),
    val videoReady: Boolean = false,
    val inputReady: Boolean = false,
    val clipboard: ClipboardSupport = ClipboardSupport(),
    val audio: AudioSupport = AudioSupport(),
    val privacy: PrivacySupport = PrivacySupport(),
    val resolutions: HostResolutions = HostResolutions(),
) {
    companion object {
        fun from(json: JsonElement?): RemoteDesktopCapabilities {
            if (json == null) return RemoteDesktopCapabilities()
            val videoAvail = json["videoAvailable"].bool ?: false
            val inputAvail = json["inputAvailable"].bool ?: false
            return RemoteDesktopCapabilities(
                displays = json["displays"].array.orEmpty().mapNotNull(RemoteDisplay::from),
                encoders = json["encoders"].array.orEmpty().mapNotNull { it.string },
                ffmpegAvailable = json["ffmpegAvailable"].bool ?: false,
                videoAvailable = videoAvail,
                inputAvailable = inputAvail,
                authRequired = json["authRequired"].bool ?: true,
                platform = json["platform"].string ?: "",
                platformSupported = json["platformSupported"].bool ?: true,
                requirements = json["requirements"].array.orEmpty().mapNotNull(RemoteDesktopRequirement::from),
                videoReady = json["videoReady"].bool ?: videoAvail,
                inputReady = json["inputReady"].bool ?: inputAvail,
                clipboard = ClipboardSupport.from(json["clipboard"]),
                audio = AudioSupport.from(json["audio"]),
                privacy = PrivacySupport.from(json["privacy"]),
                resolutions = HostResolutions.from(json["resolutions"]),
            )
        }
    }
}

data class ResolutionChoice(
    val id: String,
    val width: Int,
    val height: Int,
    val refresh: Int,
    val current: Boolean,
    val preferred: Boolean,
)

private fun beatsMode(candidate: HostMode, held: HostMode): Boolean {
    if (candidate.current != held.current) return candidate.current
    if (candidate.preferred != held.preferred) return candidate.preferred
    return candidate.refresh > held.refresh
}

fun resolutionChoices(modes: List<HostMode>): List<ResolutionChoice> {
    val bySize = LinkedHashMap<String, HostMode>()
    for (mode in modes) {
        if (mode.width <= 0 || mode.height <= 0) continue
        val key = "${mode.width}x${mode.height}"
        val held = bySize[key]
        if (held == null || beatsMode(mode, held)) {
            bySize[key] = mode
        }
    }
    return bySize.values
        .sortedWith(
            compareByDescending<HostMode> { it.width.toLong() * it.height.toLong() }
                .thenByDescending { it.refresh },
        )
        .map { m ->
            ResolutionChoice(
                id = m.id,
                width = m.width,
                height = m.height,
                refresh = m.refresh,
                current = m.current,
                preferred = m.preferred,
            )
        }
}

fun fitLocalMode(
    modes: List<HostMode>,
    local: RdSize?,
    currentModeId: String?,
): HostMode? {
    if (local == null || local.width <= 0 || local.height <= 0) return null
    val group = modes.filter { it.width == local.width && it.height == local.height }
    if (group.isEmpty()) return null
    val live = modes.firstOrNull { it.id == currentModeId } ?: modes.firstOrNull { it.current }
    if (live != null && live.width == local.width && live.height == local.height) return null
    return group.reduce { held, m -> if (beatsMode(m, held)) m else held }
}

fun resolutionLabel(choice: ResolutionChoice): String {
    val size = "${choice.width} × ${choice.height}"
    return if (choice.refresh > 0) "$size · ${choice.refresh} Hz" else size
}

private val CODEC_LABELS: Map<String, String> = mapOf(
    "h264_nvenc" to "NVIDIA NVENC",
    "h264_qsv" to "Intel QuickSync",
    "h264_vaapi" to "VAAPI (GPU)",
    "h264_amf" to "AMD AMF",
    "h264_videotoolbox" to "Apple VideoToolbox",
    "libx264" to "Software (x264)",
)

fun codecLabel(encoder: String): String = CODEC_LABELS[encoder] ?: encoder

fun isHardwareCodec(encoder: String): Boolean =
    encoder != "libx264" && CODEC_LABELS.containsKey(encoder)

data class RemoteDesktopStatsSample(
    val frameCount: Int,
    val totalBytes: Long,
    val atMs: Long,
)

data class RemoteDesktopStats(
    val fps: Double = 0.0,
    val kbps: Double = 0.0,
)

fun computeRemoteDesktopStats(
    prev: RemoteDesktopStatsSample,
    next: RemoteDesktopStatsSample,
): RemoteDesktopStats {
    val elapsedMs = next.atMs - prev.atMs
    if (elapsedMs <= 0L) return RemoteDesktopStats()
    val elapsedSec = elapsedMs / 1000.0
    val frames = max(0, next.frameCount - prev.frameCount)
    val bytes = max(0L, next.totalBytes - prev.totalBytes)
    return RemoteDesktopStats(
        fps = frames / elapsedSec,
        kbps = bytes / 1024.0 / elapsedSec,
    )
}

// ── 6. Android KeyCode -> W3C Physical Code & Mobile Key Bar ──────────

data class RdKeyItem(val label: String, val code: String)
data class RdComboItem(val label: String, val codes: List<String>)

val RD_MODIFIERS: List<RdKeyItem> = listOf(
    RdKeyItem("Ctrl", "ControlLeft"),
    RdKeyItem("Alt", "AltLeft"),
    RdKeyItem("Shift", "ShiftLeft"),
    RdKeyItem("Win", "MetaLeft"),
)

val RD_FUNCTION_KEYS: List<RdKeyItem> = listOf(
    RdKeyItem("Esc", "Escape"),
    RdKeyItem("Tab", "Tab"),
    RdKeyItem("←", "ArrowLeft"),
    RdKeyItem("↑", "ArrowUp"),
    RdKeyItem("↓", "ArrowDown"),
    RdKeyItem("→", "ArrowRight"),
)

val RD_COMBOS: List<RdComboItem> = listOf(
    RdComboItem("Ctrl+C", listOf("ControlLeft", "KeyC")),
    RdComboItem("Ctrl+V", listOf("ControlLeft", "KeyV")),
    RdComboItem("Ctrl+Z", listOf("ControlLeft", "KeyZ")),
    RdComboItem("Ctrl+A", listOf("ControlLeft", "KeyA")),
    RdComboItem("Alt+Tab", listOf("AltLeft", "Tab")),
    RdComboItem("Ctrl+Alt+Del", listOf("ControlLeft", "AltLeft", "Delete")),
)

fun isCopyChord(codes: List<String>): Boolean {
    val mod = "ControlLeft" in codes || "MetaLeft" in codes || "ControlRight" in codes || "MetaRight" in codes
    return mod && ("KeyC" in codes || "KeyX" in codes)
}

fun isPasteKeyCombo(code: String, ctrl: Boolean, meta: Boolean, alt: Boolean = false): Boolean =
    !alt && (ctrl || meta) && code == "KeyV"

fun isCopyKeyCombo(code: String, ctrl: Boolean, meta: Boolean, alt: Boolean = false): Boolean =
    !alt && (ctrl || meta) && (code == "KeyC" || code == "KeyX")

/** Two hours (`use-remote-desktop-recorder.ts:17`). */
const val MAX_RECORDING_MS: Long = 2L * 60L * 60L * 1000L

/** `m:ss`, or `h:mm:ss` past an hour (`remote-desktop-toolbar.tsx:418-423`). */
fun formatRemoteDesktopElapsed(totalSec: Int): String {
    val clamped = max(0, totalSec)
    val h = clamped / 3600
    val m = (clamped % 3600) / 60
    val sec = (clamped % 60).toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$sec" else "$m:$sec"
}

/**
 * Two zero-width spaces (`\u200B\u200B`) with the cursor placed at index 1 so a soft keyboard's
 * backward delete (`deleteSurroundingText(1, 0)`) removes index 0 and forward delete
 * (`deleteSurroundingText(0, 1)`) removes index 1.
 */
const val SOFT_KEYBOARD_SENTINEL = "\u200B\u200B"

/** Maximum Unicode characters per `{type:"text"}` message when chunking long soft-keyboard commits. */
const val SOFT_KEYBOARD_TEXT_CHUNK_SIZE = 512

private fun keyTapMessages(code: String): List<JsonElement> = listOf(
    buildJsonObject { put("type", "key"); put("code", code); put("down", true) },
    buildJsonObject { put("type", "key"); put("code", code); put("down", false) },
)

/**
 * Port of `forwardBeforeInput` (`use-remote-desktop-virtual-keyboard.ts:31-51`) with chunking
 * for long `insertText` payloads. Returns `null` when the input type is an active composition
 * (`insertCompositionText` etc.) that must wait for `compositionend`.
 */
fun forwardBeforeInput(
    inputType: String,
    data: String?,
    chunkSize: Int = SOFT_KEYBOARD_TEXT_CHUNK_SIZE,
): List<JsonElement>? {
    val step = chunkSize.coerceAtLeast(1)
    return when (inputType) {
        "insertText" -> {
            if (data.isNullOrEmpty()) emptyList()
            else data.chunked(step).map { chunk ->
                buildJsonObject { put("type", "text"); put("text", chunk) }
            }
        }
        "insertLineBreak", "insertParagraph" -> keyTapMessages("Enter")
        "deleteContentBackward" -> keyTapMessages("Backspace")
        "deleteContentForward" -> keyTapMessages("Delete")
        else -> null
    }
}

internal fun chunkByCodePoints(text: String, maxCodePoints: Int = SOFT_KEYBOARD_TEXT_CHUNK_SIZE): List<String> {
    if (text.isEmpty()) return emptyList()
    val step = maxCodePoints.coerceAtLeast(1)
    val out = ArrayList<String>()
    var index = 0
    while (index < text.length) {
        val remainingCodePoints = text.codePointCount(index, text.length)
        val end = if (remainingCodePoints <= step) {
            text.length
        } else {
            text.offsetByCodePoints(index, step)
        }
        out += text.substring(index, end)
        index = end
    }
    return out
}

/**
 * Resolves an Android soft-keyboard `TextFieldValue` update (`use-remote-desktop-virtual-keyboard.ts`):
 * - While [hasActiveComposition] is `true` (Telex / CJK / predictive IME composition in flight),
 *   returns `null` so the caller keeps the composing buffer untouched and sends nothing yet.
 * - Once [hasActiveComposition] is `false` (`compositionend` or direct non-composing input),
 *   returns the committed `{type:"text"}` chunks (split on Unicode code-point boundaries) and/or
 *   `Enter` / `Backspace` / `Delete` key taps, after which the caller resets the field to
 *   [SOFT_KEYBOARD_SENTINEL] with cursor at 1.
 */
fun resolveSoftKeyboardCommit(
    text: String,
    selectionStart: Int = 1,
    hasActiveComposition: Boolean = false,
    chunkSize: Int = SOFT_KEYBOARD_TEXT_CHUNK_SIZE,
): List<JsonElement>? {
    if (hasActiveComposition) return null
    val committed = text.replace("\u200B", "")
    if (committed.isNotEmpty()) {
        val out = ArrayList<JsonElement>()
        val normalized = committed.replace("\r\n", "\n").replace('\r', '\n')
        val step = chunkSize.coerceAtLeast(1)
        val parts = normalized.split('\n')
        for ((idx, part) in parts.withIndex()) {
            if (part.isNotEmpty()) {
                for (chunk in chunkByCodePoints(part, step)) {
                    out += buildJsonObject { put("type", "text"); put("text", chunk) }
                }
            }
            if (idx < parts.lastIndex) {
                out += keyTapMessages("Enter")
            }
        }
        return out
    }
    val sentinelCount = text.count { it == '\u200B' }
    if (sentinelCount < 2) {
        return if (sentinelCount == 1 && selectionStart >= 1) {
            keyTapMessages("Delete")
        } else {
            keyTapMessages("Backspace")
        }
    }
    return emptyList()
}

/**
 * Maps Android `android.view.KeyEvent.KEYCODE_*` constants to W3C physical `KeyboardEvent.code`
 * strings expected by the host's evdev/VK/CG key map.
 */
private val ANDROID_KEYCODE_TO_W3C: Map<Int, String> = mapOf(
    7 to "Digit0", 8 to "Digit1", 9 to "Digit2", 10 to "Digit3", 11 to "Digit4",
    12 to "Digit5", 13 to "Digit6", 14 to "Digit7", 15 to "Digit8", 16 to "Digit9",
    19 to "ArrowUp", 20 to "ArrowDown", 21 to "ArrowLeft", 22 to "ArrowRight",
    29 to "KeyA", 30 to "KeyB", 31 to "KeyC", 32 to "KeyD", 33 to "KeyE",
    34 to "KeyF", 35 to "KeyG", 36 to "KeyH", 37 to "KeyI", 38 to "KeyJ",
    39 to "KeyK", 40 to "KeyL", 41 to "KeyM", 42 to "KeyN", 43 to "KeyO",
    44 to "KeyP", 45 to "KeyQ", 46 to "KeyR", 47 to "KeyS", 48 to "KeyT",
    49 to "KeyU", 50 to "KeyV", 51 to "KeyW", 52 to "KeyX", 53 to "KeyY", 54 to "KeyZ",
    55 to "Comma", 56 to "Period",
    57 to "AltLeft", 58 to "AltRight",
    59 to "ShiftLeft", 60 to "ShiftRight",
    61 to "Tab", 62 to "Space", 66 to "Enter", 67 to "Backspace",
    68 to "Backquote", 69 to "Minus", 70 to "Equal",
    71 to "BracketLeft", 72 to "BracketRight", 73 to "Backslash",
    74 to "Semicolon", 75 to "Quote", 76 to "Slash",
    92 to "PageUp", 93 to "PageDown",
    111 to "Escape", 112 to "Delete",
    113 to "ControlLeft", 114 to "ControlRight",
    115 to "CapsLock", 116 to "ScrollLock",
    117 to "MetaLeft", 118 to "MetaRight",
    120 to "PrintScreen", 121 to "Pause",
    122 to "Home", 123 to "End", 124 to "Insert",
    131 to "F1", 132 to "F2", 133 to "F3", 134 to "F4", 135 to "F5", 136 to "F6",
    137 to "F7", 138 to "F8", 139 to "F9", 140 to "F10", 141 to "F11", 142 to "F12",
    143 to "NumLock",
    144 to "Numpad0", 145 to "Numpad1", 146 to "Numpad2", 147 to "Numpad3", 148 to "Numpad4",
    149 to "Numpad5", 150 to "Numpad6", 151 to "Numpad7", 152 to "Numpad8", 153 to "Numpad9",
    154 to "NumpadDivide", 155 to "NumpadMultiply", 156 to "NumpadSubtract", 157 to "NumpadAdd",
    158 to "NumpadDecimal", 160 to "NumpadEnter",
)

fun androidKeyCodeToW3cCode(keyCode: Int): String? = ANDROID_KEYCODE_TO_W3C[keyCode]

// ── 7. Binary Frame & MediaCodec CSD Helpers ──────────────────────────

const val RD_FRAME_DELTA: Byte = 0
const val RD_FRAME_KEY: Byte = 1
const val RD_FRAME_AUDIO: Byte = 2

data class H264SpsPps(
    val sps: ByteArray,
    val pps: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is H264SpsPps) return false
        return sps.contentEquals(other.sps) && pps.contentEquals(other.pps)
    }

    override fun hashCode(): Int = 31 * sps.contentHashCode() + pps.contentHashCode()
}

/**
 * Extracts SPS (`nal_unit_type == 7`) and PPS (`nal_unit_type == 8`) NAL units (each prefixed
 * with standard 4-byte Annex-B start code `00 00 00 01`) from an Annex-B access unit.
 */
fun extractAnnexBSpsPps(
    data: ByteArray,
    offset: Int = 0,
    length: Int = data.size - offset,
): H264SpsPps? {
    val end = min(data.size, offset + length)
    var sps: ByteArray? = null
    var pps: ByteArray? = null

    fun findStartCode(from: Int): Pair<Int, Int>? {
        var i = from
        while (i + 2 < end) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) return i to (i + 3)
                if (i + 3 < end && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) return i to (i + 4)
            }
            i++
        }
        return null
    }

    var cur = findStartCode(offset)
    while (cur != null) {
        val nalPayloadStart = cur.second
        val next = findStartCode(nalPayloadStart)
        val nalPayloadEnd = next?.first ?: end
        if (nalPayloadStart < nalPayloadEnd) {
            val nalType = data[nalPayloadStart].toInt() and 0x1F
            if (nalType == 7 || nalType == 8) {
                val payloadLen = nalPayloadEnd - nalPayloadStart
                val withStartCode = ByteArray(4 + payloadLen)
                withStartCode[3] = 1
                System.arraycopy(data, nalPayloadStart, withStartCode, 4, payloadLen)
                if (nalType == 7 && sps == null) sps = withStartCode
                if (nalType == 8 && pps == null) pps = withStartCode
            }
        }
        cur = next
    }
    return if (sps != null && pps != null) H264SpsPps(sps!!, pps!!) else null
}

/**
 * Builds the 19-byte RFC 7845 `OpusHead` (`csd-0`) buffer required by Android `MediaCodec`
 * for `audio/opus`.
 */
fun buildOpusHeadCsd(channels: Int = 2, sampleRate: Int = 48_000, preSkipSamples: Int = 3840): ByteArray {
    val buf = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
    buf.put("OpusHead".toByteArray(Charsets.US_ASCII))
    buf.put(1.toByte()) // Version 1
    buf.put(channels.coerceIn(1, 2).toByte())
    buf.putShort(preSkipSamples.toShort())
    buf.putInt(sampleRate)
    buf.putShort(0.toShort()) // Output gain = 0 dB
    buf.put(0.toByte()) // Channel mapping family 0
    return buf.array()
}

/** Builds an 8-byte native-endian nanosecond buffer for `csd-1` (pre-skip) and `csd-2` (seek pre-roll). */
fun buildOpusNanosecondsCsd(nanoseconds: Long = 80_000_000L): ByteArray =
    ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(nanoseconds).array()
