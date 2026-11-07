package com.hienlh.ppm.state.androiddevice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure Kotlin models, binary header codec, coordinate transforms, multi-touch slot tracker,
 * logcat filter, AVD validation, and JSON wire helpers for the Android Device / Emulator
 * Controller (`Phase 11c`).
 */

const val ANDROID_PROTOCOL_VERSION: Int = 1
const val ANDROID_VIDEO_HEADER_BYTES: Int = 22
const val MAX_ANDROID_TOUCH_SLOTS: Int = 10

val ANDROID_RECONNECT_BACKOFF_MS: LongArray = longArrayOf(500L, 1_000L, 2_000L, 4_000L, 8_000L)
const val ANDROID_HEARTBEAT_INTERVAL_MS: Long = 5_000L

data class AndroidVideoHeader(
    val version: Int = ANDROID_PROTOCOL_VERSION,
    val keyframe: Boolean,
    val codecConfig: Boolean,
    val sessionGeneration: Int,
    val geometryGeneration: Int,
    val sequence: Long,
    val ptsMs: Double,
    val payloadLength: Int,
)

data class AndroidVideoFramePacket(
    val header: AndroidVideoHeader,
    val payload: ByteArray,
)

/**
 * Encode a 22-byte little-endian binary video header + H.264 Annex-B payload.
 *
 * Layout (`src/shared/android-protocol.ts`):
 * - `0`: u8 version (`1`)
 * - `1`: u8 flags (`bit0 = keyframe`, `bit1 = codecConfig`)
 * - `2..3`: u16 sessionGeneration (LE)
 * - `4..5`: u16 geometryGeneration (LE)
 * - `6..9`: u32 sequence (LE)
 * - `10..17`: f64 ptsMs (LE)
 * - `18..21`: u32 payloadLength (LE)
 * - `22..`: payload
 */
fun encodeAndroidVideoFrame(header: AndroidVideoHeader, payload: ByteArray): ByteArray {
    val out = ByteArray(ANDROID_VIDEO_HEADER_BYTES + payload.size)
    val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
    val flags = (if (header.keyframe) 1 else 0) or (if (header.codecConfig) 2 else 0)
    buf.put((header.version and 0xFF).toByte())
    buf.put((flags and 0xFF).toByte())
    buf.putShort((header.sessionGeneration and 0xFFFF).toShort())
    buf.putShort((header.geometryGeneration and 0xFFFF).toShort())
    buf.putInt((header.sequence and 0xFFFFFFFFL).toInt())
    buf.putDouble(header.ptsMs)
    buf.putInt(payload.size)
    System.arraycopy(payload, 0, out, ANDROID_VIDEO_HEADER_BYTES, payload.size)
    return out
}

/**
 * Decode a binary WebSocket frame from `/ws/android`.
 * Returns `null` if the buffer is shorter than 22 bytes, version != 1, or payload length mismatches.
 */
fun decodeAndroidVideoFrame(bytes: ByteArray): AndroidVideoFramePacket? {
    if (bytes.size < ANDROID_VIDEO_HEADER_BYTES) return null
    val buf = ByteBuffer.wrap(bytes, 0, ANDROID_VIDEO_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    val version = buf.get().toInt() and 0xFF
    if (version != ANDROID_PROTOCOL_VERSION) return null
    val flags = buf.get().toInt() and 0xFF
    val sessionGen = buf.short.toInt() and 0xFFFF
    val geometryGen = buf.short.toInt() and 0xFFFF
    val sequence = buf.int.toLong() and 0xFFFFFFFFL
    val ptsMs = buf.double
    val payloadLen = buf.int
    if (payloadLen < 0 || bytes.size - ANDROID_VIDEO_HEADER_BYTES != payloadLen) return null
    val payload = bytes.copyOfRange(ANDROID_VIDEO_HEADER_BYTES, bytes.size)
    return AndroidVideoFramePacket(
        header = AndroidVideoHeader(
            version = version,
            keyframe = (flags and 1) != 0,
            codecConfig = (flags and 2) != 0,
            sessionGeneration = sessionGen,
            geometryGeneration = geometryGen,
            sequence = sequence,
            ptsMs = ptsMs,
            payloadLength = payloadLen,
        ),
        payload = payload,
    )
}

data class AndroidGeometry(
    val frameWidth: Int,
    val frameHeight: Int,
    val deviceWidth: Int,
    val deviceHeight: Int,
    val rotation: Int,
    val generation: Int,
)

enum class AndroidQuality(
    val wire: String,
    val label: String,
    val maxDimension: Int,
    val bitrateBps: Int,
    val fps: Int,
) {
    LOW("low", "Low", 720, 2_000_000, 30),
    BALANCED("balanced", "Balanced", 1080, 4_000_000, 30),
    HIGH("high", "High", 1440, 8_000_000, 60),
    ;

    companion object {
        fun fromWire(value: String?): AndroidQuality =
            entries.firstOrNull { it.wire == value } ?: BALANCED
    }
}

enum class AndroidKey(val wire: String) {
    BACK("back"),
    HOME("home"),
    APP_SWITCH("app_switch"),
    POWER("power"),
    VOLUME_UP("volume_up"),
    VOLUME_DOWN("volume_down"),
    ENTER("enter"),
    BACKSPACE("backspace"),
    TAB("tab"),
    ESCAPE("escape"),
    DPAD_UP("dpad_up"),
    DPAD_DOWN("dpad_down"),
    DPAD_LEFT("dpad_left"),
    DPAD_RIGHT("dpad_right"),
}

enum class AndroidLogLevel(val wire: String, val rank: Int) {
    V("V", 0),
    D("D", 1),
    I("I", 2),
    W("W", 3),
    E("E", 4),
    F("F", 5),
    ;

    companion object {
        fun fromWire(value: String?): AndroidLogLevel =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: I
    }
}

data class AndroidLogEntry(
    val seq: Long,
    val ts: Long,
    val level: AndroidLogLevel,
    val pid: Int,
    val tid: Int,
    val tag: String,
    val message: String,
)

data class AndroidTouchPoint(
    val slot: Int,
    val x: Int,
    val y: Int,
    val pressure: Float,
)

enum class AndroidTouchPhase(val wire: String) {
    DOWN("down"),
    MOVE("move"),
    UP("up"),
    CANCEL("cancel"),
}

enum class AndroidDeviceStatus(val wire: String) {
    STOPPED("stopped"),
    STARTING("starting"),
    BOOTING("booting"),
    READY("ready"),
    STOPPING("stopping"),
    ERROR("error"),
    ;

    companion object {
        fun fromWire(value: String?): AndroidDeviceStatus =
            entries.firstOrNull { it.wire == value } ?: STOPPED
    }
}

enum class AndroidConnState(val wire: String) {
    CONNECTING("connecting"),
    CONNECTED("connected"),
    RECONNECTING("reconnecting"),
    FAILED("failed"),
}

// ---------------------------------------------------------------------------
// Coordinate transforms (`src/web/components/android/android-coords.ts`)
// ---------------------------------------------------------------------------

data class AndroidRect(
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double,
)

data class AndroidPoint(
    val x: Double,
    val y: Double,
)

data class AndroidIntPoint(
    val x: Int,
    val y: Int,
)

/**
 * Compute the `object-contain` letterbox rectangle inside [boxWidth] x [boxHeight].
 */
fun containedRect(
    boxWidth: Double,
    boxHeight: Double,
    frameWidth: Int,
    frameHeight: Int,
): AndroidRect {
    if (boxWidth <= 0.0 || boxHeight <= 0.0 || frameWidth <= 0 || frameHeight <= 0) {
        return AndroidRect(0.0, 0.0, 0.0, 0.0)
    }
    val scale = min(boxWidth / frameWidth.toDouble(), boxHeight / frameHeight.toDouble())
    val width = frameWidth * scale
    val height = frameHeight * scale
    return AndroidRect(
        left = (boxWidth - width) / 2.0,
        top = (boxHeight - height) / 2.0,
        width = width,
        height = height,
    )
}

/**
 * Map a view-local pointer `(offsetX, offsetY)` to frame coordinates.
 * Returns `null` when the pointer lands on the letterbox bars outside the contained video rect.
 */
fun pointerToFrame(
    boxWidth: Double,
    boxHeight: Double,
    frameWidth: Int,
    frameHeight: Int,
    offsetX: Double,
    offsetY: Double,
): AndroidPoint? {
    val rect = containedRect(boxWidth, boxHeight, frameWidth, frameHeight)
    if (rect.width <= 0.0 || rect.height <= 0.0) return null
    val relX = offsetX - rect.left
    val relY = offsetY - rect.top
    if (relX < 0.0 || relY < 0.0 || relX > rect.width || relY > rect.height) return null
    val fx = floor((relX / rect.width) * frameWidth.toDouble()).toInt()
    val fy = floor((relY / rect.height) * frameHeight.toDouble()).toInt()
    return AndroidPoint(
        x = fx.coerceIn(0, frameWidth - 1).toDouble(),
        y = fy.coerceIn(0, frameHeight - 1).toDouble(),
    )
}

/**
 * Unrotate a frame-space point into the physical device panel coordinates `(0..deviceWidth-1, 0..deviceHeight-1)`.
 */
fun frameToDevice(point: AndroidPoint, geometry: AndroidGeometry): AndroidIntPoint {
    val fw = geometry.frameWidth
    val fh = geometry.frameHeight
    val dw = geometry.deviceWidth
    val dh = geometry.deviceHeight
    if (fw <= 0 || fh <= 0 || dw <= 0 || dh <= 0) return AndroidIntPoint(0, 0)
    val u = (point.x / fw.toDouble()).coerceIn(0.0, 1.0)
    val v = (point.y / fh.toDouble()).coerceIn(0.0, 1.0)
    val pu: Double
    val pv: Double
    when (geometry.rotation) {
        90 -> {
            pu = 1.0 - v
            pv = u
        }
        180 -> {
            pu = 1.0 - u
            pv = 1.0 - v
        }
        270 -> {
            pu = v
            pv = 1.0 - u
        }
        else -> {
            pu = u
            pv = v
        }
    }
    return AndroidIntPoint(
        x = (pu * (dw - 1).coerceAtLeast(0)).roundToInt().coerceIn(0, (dw - 1).coerceAtLeast(0)),
        y = (pv * (dh - 1).coerceAtLeast(0)).roundToInt().coerceIn(0, (dh - 1).coerceAtLeast(0)),
    )
}

/**
 * Map a view-local pointer directly to unrotated device panel coordinates, or `null` if on letterbox bars.
 */
fun pointerToDevice(
    boxWidth: Double,
    boxHeight: Double,
    offsetX: Double,
    offsetY: Double,
    geometry: AndroidGeometry,
): AndroidIntPoint? {
    val framePt = pointerToFrame(
        boxWidth = boxWidth,
        boxHeight = boxHeight,
        frameWidth = geometry.frameWidth,
        frameHeight = geometry.frameHeight,
        offsetX = offsetX,
        offsetY = offsetY,
    ) ?: return null
    return frameToDevice(framePt, geometry)
}

/** Step device rotation clockwise (`0 -> 90 -> 180 -> 270 -> 0`). */
fun nextAndroidRotation(current: Int): Int = when (current) {
    0 -> 90
    90 -> 180
    180 -> 270
    else -> 0
}

// ---------------------------------------------------------------------------
// Multi-touch slot tracker (`src/web/components/android/use-android-touch.ts`)
// ---------------------------------------------------------------------------

/**
 * Tracks up to [MAX_ANDROID_TOUCH_SLOTS] simultaneous fingers (`slot 0..9`).
 *
 * Every touch packet includes ALL active contacts (`pressure = 1f`), and on `UP`/`CANCEL`
 * includes the releasing contact with `pressure = 0f` at its final coordinates.
 */
class AndroidTouchTracker {
    private data class ActiveFinger(
        val slot: Int,
        var x: Int,
        var y: Int,
    )

    private val active = LinkedHashMap<Int, ActiveFinger>()

    val activeCount: Int
        get() = active.size

    private fun allocateSlot(): Int? {
        val used = active.values.mapTo(HashSet()) { it.slot }
        for (slot in 0 until MAX_ANDROID_TOUCH_SLOTS) {
            if (slot !in used) return slot
        }
        return null
    }

    private fun snapshotContacts(releasing: AndroidTouchPoint? = null): List<AndroidTouchPoint> {
        val points = ArrayList<AndroidTouchPoint>(active.size + (if (releasing != null) 1 else 0))
        for (finger in active.values) {
            points.add(AndroidTouchPoint(slot = finger.slot, x = finger.x, y = finger.y, pressure = 1f))
        }
        if (releasing != null) {
            points.add(releasing)
        }
        return points
    }

    fun onDown(pointerId: Int, point: AndroidIntPoint): List<AndroidTouchPoint>? {
        val existing = active[pointerId]
        if (existing != null) {
            existing.x = point.x
            existing.y = point.y
            return snapshotContacts()
        }
        val slot = allocateSlot() ?: return null
        active[pointerId] = ActiveFinger(slot = slot, x = point.x, y = point.y)
        return snapshotContacts()
    }

    fun onMove(updates: List<Pair<Int, AndroidIntPoint>>): List<AndroidTouchPoint>? {
        var changed = false
        for ((pointerId, pt) in updates) {
            val finger = active[pointerId] ?: continue
            finger.x = pt.x
            finger.y = pt.y
            changed = true
        }
        if (!changed || active.isEmpty()) return null
        return snapshotContacts()
    }

    fun onUpOrCancel(pointerId: Int, finalPoint: AndroidIntPoint? = null): List<AndroidTouchPoint>? {
        val finger = active.remove(pointerId) ?: return null
        val x = finalPoint?.x ?: finger.x
        val y = finalPoint?.y ?: finger.y
        return snapshotContacts(
            releasing = AndroidTouchPoint(slot = finger.slot, x = x, y = y, pressure = 0f),
        )
    }

    fun clear(): Boolean {
        val hadActive = active.isNotEmpty()
        active.clear()
        return hadActive
    }
}

// ---------------------------------------------------------------------------
// Keyboard input routing (`src/web/components/android/android-viewer.tsx`)
// ---------------------------------------------------------------------------

enum class AndroidTextInputKind {
    ASCII_TEXT,
    UNICODE_PASTE,
}

/**
 * Pure printable ASCII (`0x20..0x7E`) uses `{"type":"text"}`; any non-ASCII character
 * (e.g. Vietnamese IME input or emoji) routes through `{"type":"paste"}`.
 */
fun classifyAndroidTextInput(text: String): AndroidTextInputKind {
    for (ch in text) {
        val code = ch.code
        if (code < 0x20 || code > 0x7E) return AndroidTextInputKind.UNICODE_PASTE
    }
    return AndroidTextInputKind.ASCII_TEXT
}

// ---------------------------------------------------------------------------
// Logcat filter & ring buffer (`src/web/components/android/android-log-filter.ts`)
// ---------------------------------------------------------------------------

data class AndroidLogFilter(
    val minLevel: AndroidLogLevel = AndroidLogLevel.V,
    val tag: String = "",
    val pid: String = "",
    val search: String = "",
)

val EMPTY_ANDROID_LOG_FILTER = AndroidLogFilter()

fun meetsLogLevel(entry: AndroidLogLevel, min: AndroidLogLevel): Boolean =
    entry.rank >= min.rank

fun matchesAndroidLogFilter(entry: AndroidLogEntry, filter: AndroidLogFilter): Boolean {
    if (!meetsLogLevel(entry.level, filter.minLevel)) return false
    val tagNeedle = filter.tag.trim().lowercase()
    if (tagNeedle.isNotEmpty() && !entry.tag.lowercase().contains(tagNeedle)) return false
    val pidNeedle = filter.pid.trim()
    if (pidNeedle.isNotEmpty()) {
        val pidNum = pidNeedle.toIntOrNull()
        if (pidNum == null || entry.pid != pidNum) return false
    }
    val searchNeedle = filter.search.trim().lowercase()
    if (searchNeedle.isNotEmpty()) {
        val hay = "${entry.tag} ${entry.message}".lowercase()
        if (!hay.contains(searchNeedle)) return false
    }
    return true
}

data class BoundedLogsResult(
    val entries: List<AndroidLogEntry>,
    val dropped: Int,
)

fun appendBoundedLogs(
    existing: List<AndroidLogEntry>,
    incoming: List<AndroidLogEntry>,
    limit: Int,
): BoundedLogsResult {
    if (incoming.isEmpty()) return BoundedLogsResult(existing, 0)
    val safeLimit = limit.coerceAtLeast(1)
    val total = existing.size + incoming.size
    if (total <= safeLimit) {
        return BoundedLogsResult(existing + incoming, 0)
    }
    val drop = total - safeLimit
    val combined = ArrayList<AndroidLogEntry>(safeLimit)
    if (drop < existing.size) {
        combined.addAll(existing.subList(drop, existing.size))
        combined.addAll(incoming)
    } else {
        val incomingStart = drop - existing.size
        combined.addAll(incoming.subList(incomingStart, incoming.size))
    }
    return BoundedLogsResult(combined, drop)
}

private val LOG_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.of("UTC"))

fun formatAndroidLogTime(ts: Long): String =
    LOG_TIME_FORMATTER.format(Instant.ofEpochMilli(ts))

fun formatAndroidLogText(entries: List<AndroidLogEntry>): String =
    entries.joinToString("\n") { e ->
        "${formatAndroidLogTime(e.ts)} ${e.level.wire}/${e.tag}(${e.pid}): ${e.message}"
    }

// ---------------------------------------------------------------------------
// AVD validation & limits (`src/shared/android-avd.ts`)
// ---------------------------------------------------------------------------

val AVD_NAME_PATTERN = Regex("^[A-Za-z0-9._-]{1,64}$")

object AvdLimits {
    const val RAM_MIN_MB = 1024
    const val RAM_MAX_MB = 8192
    const val RAM_DEFAULT_MB = 2048

    const val STORAGE_MIN_MB = 2048
    const val STORAGE_MAX_MB = 32768
    const val STORAGE_DEFAULT_MB = 6144
}

fun validateAvdName(name: String): String? {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "AVD name is required."
    if (trimmed.length > 64) return "AVD name must be 64 characters or fewer."
    if (!AVD_NAME_PATTERN.matches(trimmed)) {
        return "Use letters, numbers, dots, underscores, or hyphens (no spaces)."
    }
    return null
}

fun validateAvdLimits(ramMb: Int?, storageMb: Int?): String? {
    if (ramMb != null && (ramMb < AvdLimits.RAM_MIN_MB || ramMb > AvdLimits.RAM_MAX_MB)) {
        return "RAM must be between ${AvdLimits.RAM_MIN_MB} and ${AvdLimits.RAM_MAX_MB} MB."
    }
    if (storageMb != null && (storageMb < AvdLimits.STORAGE_MIN_MB || storageMb > AvdLimits.STORAGE_MAX_MB)) {
        return "Storage must be between ${AvdLimits.STORAGE_MIN_MB} and ${AvdLimits.STORAGE_MAX_MB} MB."
    }
    return null
}

// ---------------------------------------------------------------------------
// Screenshot & APK helpers (`android-screenshot-download.ts`, `android-apk-upload.ts`)
// ---------------------------------------------------------------------------

private val DISPOSITION_FILENAME_REGEX = Regex("""filename="([^"]+)"""")

fun filenameFromDisposition(header: String?, fallbackAvdId: String = "android"): String {
    if (!header.isNullOrBlank()) {
        val match = DISPOSITION_FILENAME_REGEX.find(header)
        val raw = match?.groupValues?.getOrNull(1)?.trim()
        if (!raw.isNullOrEmpty()) return raw
    }
    val safe = fallbackAvdId.replace(Regex("[^a-zA-Z0-9._-]+"), "-").trim('-').ifEmpty { "android" }
    return "$safe-screenshot.png"
}

// ---------------------------------------------------------------------------
// REST & WebSocket Models + Parsers
// ---------------------------------------------------------------------------

data class AndroidRequirement(
    val id: String,
    val label: String,
    val met: Boolean,
    val detail: String? = null,
    val fix: String? = null,
)

data class AndroidCapabilities(
    val enabled: Boolean = false,
    val ready: Boolean = false,
    val avdCount: Int = 0,
    val accelerationOk: Boolean = false,
    val accelerationDetail: String = "",
    val requirements: List<AndroidRequirement> = emptyList(),
    val encoders: List<String> = emptyList(),
    val authRequired: Boolean = false,
    val canCreateAvd: Boolean = false,
)

data class AndroidRuntimeInfo(
    val serial: String,
    val status: AndroidDeviceStatus,
    val generation: Int,
    val pid: Int? = null,
    val lastError: String? = null,
)

data class AndroidDeviceItem(
    val avdId: String,
    val name: String,
    val target: String? = null,
    val abi: String? = null,
    val deviceProfile: String? = null,
    val path: String? = null,
    val runtime: AndroidRuntimeInfo? = null,
)

data class AndroidOperationStatus(
    val id: String,
    val kind: String,
    val target: String,
    val status: String,
    val step: String,
    val progress: Int? = null,
    val error: String? = null,
    val deviceId: String? = null,
    val logTail: List<String> = emptyList(),
) {
    val isTerminal: Boolean
        get() = status == "succeeded" || status == "failed" || status == "cancelled"
}

data class SystemImageRow(
    val id: String,
    val apiLevel: Int,
    val tag: String,
    val abi: String,
    val displayName: String,
    val installed: Boolean,
)

data class AvdDeviceProfile(
    val id: String,
    val name: String,
    val oem: String? = null,
)

data class ProjectApkItem(
    val relativePath: String,
    val fileName: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
)

data class AndroidSessionTicket(
    val nonce: String,
    val deviceId: String,
    val generation: Int,
    val hardwareKeyboard: Boolean,
)

sealed interface AndroidServerMessage {
    data class Ready(
        val sessionId: String,
        val deviceId: String,
        val generation: Int,
        val controller: Boolean,
        val quality: AndroidQuality,
        val geometry: AndroidGeometry,
        val hardwareKeyboard: Boolean,
    ) : AndroidServerMessage

    data class Codec(
        val codec: String,
        val profile: Int,
        val level: Int,
        val width: Int,
        val height: Int,
        val configBase64: String,
    ) : AndroidServerMessage

    data class Geometry(
        val geometry: AndroidGeometry,
    ) : AndroidServerMessage

    data class Controller(
        val controller: Boolean,
        val reason: String,
    ) : AndroidServerMessage

    data class Quality(
        val quality: AndroidQuality,
    ) : AndroidServerMessage

    data class Status(
        val status: AndroidDeviceStatus,
        val message: String? = null,
    ) : AndroidServerMessage

    data class Log(
        val entries: List<AndroidLogEntry>,
        val dropped: Int? = null,
    ) : AndroidServerMessage

    data class Error(
        val code: String,
        val message: String,
        val retryable: Boolean,
    ) : AndroidServerMessage

    data class Heartbeat(
        val ts: Long,
    ) : AndroidServerMessage
}

private val androidJson = Json { ignoreUnknownKeys = true }

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull
private fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

fun parseAndroidGeometry(obj: JsonObject): AndroidGeometry = AndroidGeometry(
    frameWidth = obj.int("frameWidth") ?: 1080,
    frameHeight = obj.int("frameHeight") ?: 2400,
    deviceWidth = obj.int("deviceWidth") ?: 1080,
    deviceHeight = obj.int("deviceHeight") ?: 2400,
    rotation = when (val r = obj.int("rotation") ?: 0) {
        90, 180, 270 -> r
        else -> 0
    },
    generation = obj.int("generation") ?: 1,
)

fun parseAndroidCapabilities(element: JsonElement): AndroidCapabilities {
    val obj = element as? JsonObject ?: return AndroidCapabilities()
    val reqs = (obj["requirements"] as? JsonArray)?.mapNotNull { item ->
        val ro = item as? JsonObject ?: return@mapNotNull null
        val id = ro.str("id") ?: return@mapNotNull null
        AndroidRequirement(
            id = id,
            label = ro.str("label") ?: id,
            met = ro.bool("met") ?: false,
            detail = ro.str("detail"),
            fix = ro.str("fix"),
        )
    } ?: emptyList()
    val encoders = (obj["encoders"] as? JsonArray)?.mapNotNull {
        it.jsonPrimitive.contentOrNull
    } ?: emptyList()
    return AndroidCapabilities(
        enabled = obj.bool("enabled") ?: false,
        ready = obj.bool("ready") ?: false,
        avdCount = obj.int("avdCount") ?: 0,
        accelerationOk = obj.bool("accelerationOk") ?: false,
        accelerationDetail = obj.str("accelerationDetail") ?: "",
        requirements = reqs,
        encoders = encoders,
        authRequired = obj.bool("authRequired") ?: false,
        canCreateAvd = obj.bool("canCreateAvd") ?: false,
    )
}

fun parseAndroidDevices(element: JsonElement): List<AndroidDeviceItem> {
    val obj = element as? JsonObject
    val arr = when {
        obj != null && obj["devices"] is JsonArray -> obj["devices"]!!.jsonArray
        element is JsonArray -> element
        else -> return emptyList()
    }
    return arr.mapNotNull { item ->
        val d = item as? JsonObject ?: return@mapNotNull null
        val avdId = d.str("avdId") ?: return@mapNotNull null
        val rtObj = d["runtime"] as? JsonObject
        val runtime = if (rtObj != null) {
            val serial = rtObj.str("serial") ?: avdId
            AndroidRuntimeInfo(
                serial = serial,
                status = AndroidDeviceStatus.fromWire(rtObj.str("status")),
                generation = rtObj.int("generation") ?: 1,
                pid = rtObj.int("pid"),
                lastError = rtObj.str("lastError"),
            )
        } else {
            null
        }
        AndroidDeviceItem(
            avdId = avdId,
            name = d.str("name") ?: avdId,
            target = d.str("target"),
            abi = d.str("abi"),
            deviceProfile = d.str("deviceProfile"),
            path = d.str("path"),
            runtime = runtime,
        )
    }
}

fun parseAndroidOperationStatus(element: JsonElement): AndroidOperationStatus? {
    val root = element as? JsonObject ?: return null
    val obj = (root["operation"] as? JsonObject) ?: root
    val id = obj.str("id") ?: return null
    val tail = (obj["logTail"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
    return AndroidOperationStatus(
        id = id,
        kind = obj.str("kind") ?: "",
        target = obj.str("target") ?: "",
        status = obj.str("status") ?: "running",
        step = obj.str("step") ?: "",
        progress = obj.int("progress"),
        error = obj.str("error"),
        deviceId = obj.str("deviceId"),
        logTail = tail,
    )
}

fun parseAndroidSystemImages(element: JsonElement): List<SystemImageRow> {
    val obj = element as? JsonObject
    val arr = (obj?.get("images") as? JsonArray) ?: (element as? JsonArray) ?: return emptyList()
    return arr.mapNotNull { item ->
        val row = item as? JsonObject ?: return@mapNotNull null
        val id = row.str("id") ?: return@mapNotNull null
        SystemImageRow(
            id = id,
            apiLevel = row.int("apiLevel") ?: 0,
            tag = row.str("tag") ?: "default",
            abi = row.str("abi") ?: "",
            displayName = row.str("displayName") ?: id,
            installed = row.bool("installed") ?: false,
        )
    }
}

fun parseAndroidDeviceProfiles(element: JsonElement): List<AvdDeviceProfile> {
    val obj = element as? JsonObject
    val arr = (obj?.get("profiles") as? JsonArray) ?: (element as? JsonArray) ?: return emptyList()
    return arr.mapNotNull { item ->
        val row = item as? JsonObject ?: return@mapNotNull null
        val id = row.str("id") ?: return@mapNotNull null
        AvdDeviceProfile(
            id = id,
            name = row.str("name") ?: id,
            oem = row.str("oem"),
        )
    }
}

fun parseProjectApks(element: JsonElement): List<ProjectApkItem> {
    val obj = element as? JsonObject
    val arr = (obj?.get("apks") as? JsonArray) ?: (element as? JsonArray) ?: return emptyList()
    return arr.mapNotNull { item ->
        val row = item as? JsonObject ?: return@mapNotNull null
        val rel = row.str("relativePath") ?: return@mapNotNull null
        ProjectApkItem(
            relativePath = rel,
            fileName = row.str("fileName") ?: rel.substringAfterLast('/'),
            sizeBytes = row.long("sizeBytes") ?: 0L,
            modifiedAt = row.long("modifiedAt") ?: 0L,
        )
    }
}

fun parseAndroidSessionTicket(element: JsonElement): AndroidSessionTicket? {
    val obj = element as? JsonObject ?: return null
    val nonce = obj.str("nonce") ?: return null
    return AndroidSessionTicket(
        nonce = nonce,
        deviceId = obj.str("deviceId") ?: "",
        generation = obj.int("generation") ?: 1,
        hardwareKeyboard = obj.bool("hardwareKeyboard") ?: true,
    )
}

fun parseAndroidServerMessage(raw: String): AndroidServerMessage? {
    val obj = try {
        androidJson.parseToJsonElement(raw).jsonObject
    } catch (_: Throwable) {
        return null
    }
    return when (obj.str("type")) {
        "ready" -> {
            val geomObj = obj["geometry"] as? JsonObject ?: return null
            AndroidServerMessage.Ready(
                sessionId = obj.str("sessionId") ?: "",
                deviceId = obj.str("deviceId") ?: "",
                generation = obj.int("generation") ?: 1,
                controller = obj.bool("controller") ?: false,
                quality = AndroidQuality.fromWire(obj.str("quality")),
                geometry = parseAndroidGeometry(geomObj),
                hardwareKeyboard = obj.bool("hardwareKeyboard") ?: true,
            )
        }
        "codec" -> AndroidServerMessage.Codec(
            codec = obj.str("codec") ?: "h264",
            profile = obj.int("profile") ?: 66,
            level = obj.int("level") ?: 31,
            width = obj.int("width") ?: 1080,
            height = obj.int("height") ?: 2400,
            configBase64 = obj.str("configBase64") ?: "",
        )
        "geometry" -> {
            val geomObj = obj["geometry"] as? JsonObject ?: return null
            AndroidServerMessage.Geometry(parseAndroidGeometry(geomObj))
        }
        "controller" -> AndroidServerMessage.Controller(
            controller = obj.bool("controller") ?: false,
            reason = obj.str("reason") ?: "initial",
        )
        "quality" -> AndroidServerMessage.Quality(
            quality = AndroidQuality.fromWire(obj.str("quality")),
        )
        "status" -> AndroidServerMessage.Status(
            status = AndroidDeviceStatus.fromWire(obj.str("status")),
            message = obj.str("message"),
        )
        "log" -> {
            val entries = (obj["entries"] as? JsonArray)?.mapNotNull { item ->
                val eo = item as? JsonObject ?: return@mapNotNull null
                AndroidLogEntry(
                    seq = eo.long("seq") ?: 0L,
                    ts = eo.long("ts") ?: 0L,
                    level = AndroidLogLevel.fromWire(eo.str("level")),
                    pid = eo.int("pid") ?: 0,
                    tid = eo.int("tid") ?: 0,
                    tag = eo.str("tag") ?: "",
                    message = eo.str("message") ?: "",
                )
            } ?: emptyList()
            AndroidServerMessage.Log(
                entries = entries,
                dropped = obj.int("dropped"),
            )
        }
        "error" -> AndroidServerMessage.Error(
            code = obj.str("code") ?: "INTERNAL_ERROR",
            message = obj.str("message") ?: "Unknown error",
            retryable = obj.bool("retryable") ?: false,
        )
        "heartbeat" -> AndroidServerMessage.Heartbeat(
            ts = obj.long("ts") ?: 0L,
        )
        else -> null
    }
}

// ---------------------------------------------------------------------------
// Client WebSocket JSON builders (`src/shared/android-protocol.ts`)
// ---------------------------------------------------------------------------

fun buildAndroidAuthJson(nonce: String, quality: AndroidQuality? = null): String =
    buildJsonObject {
        put("type", "auth")
        put("nonce", nonce)
        if (quality != null) put("quality", quality.wire)
    }.toString()

fun buildAndroidTouchJson(
    phase: AndroidTouchPhase,
    points: List<AndroidTouchPoint>,
    geometryGeneration: Int,
): String =
    buildJsonObject {
        put("type", "touch")
        put("phase", phase.wire)
        put("geometryGeneration", geometryGeneration)
        putJsonArray("points") {
            for (pt in points) {
                add(
                    buildJsonObject {
                        put("slot", pt.slot)
                        put("x", pt.x)
                        put("y", pt.y)
                        put("pressure", pt.pressure)
                    },
                )
            }
        }
    }.toString()

fun buildAndroidKeyJson(key: AndroidKey, action: String = "press"): String =
    buildJsonObject {
        put("type", "key")
        put("key", key.wire)
        put("action", action)
    }.toString()

fun buildAndroidTextJson(text: String): String =
    buildJsonObject {
        put("type", "text")
        put("text", text)
    }.toString()

fun buildAndroidPasteJson(text: String): String =
    buildJsonObject {
        put("type", "paste")
        put("text", text)
    }.toString()

fun buildAndroidRotateJson(rotation: Int): String =
    buildJsonObject {
        put("type", "rotate")
        put("rotation", rotation)
    }.toString()

fun buildAndroidSetQualityJson(quality: AndroidQuality): String =
    buildJsonObject {
        put("type", "set-quality")
        put("quality", quality.wire)
    }.toString()

fun buildAndroidTakeControlJson(): String =
    buildJsonObject {
        put("type", "take-control")
    }.toString()

fun buildAndroidSubscribeLogsJson(
    enabled: Boolean,
    minLevel: AndroidLogLevel? = null,
    tag: String? = null,
    pid: Int? = null,
): String =
    buildJsonObject {
        put("type", "subscribe-logs")
        put("enabled", enabled)
        if (minLevel != null) put("minLevel", minLevel.wire)
        if (!tag.isNullOrBlank()) put("tag", tag)
        if (pid != null) put("pid", pid)
    }.toString()

fun buildAndroidRequestKeyframeJson(): String =
    buildJsonObject {
        put("type", "request-keyframe")
    }.toString()

fun buildAndroidHeartbeatJson(): String =
    buildJsonObject {
        put("type", "heartbeat")
    }.toString()

fun buildAndroidInputResetJson(): String =
    buildJsonObject {
        put("type", "input-reset")
    }.toString()
