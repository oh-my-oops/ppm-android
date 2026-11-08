package com.hienlh.ppm.state.androiddevice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Pure state, protocol codec, coordinate math, logcat filter, and AVD validation for the
 * Android Device / Emulator Controller (`Phase 11c`).
 *
 * Mirrors:
 * - `src/shared/android-protocol.ts`
 * - `src/shared/android-avd.ts`
 * - `src/server/routes/android.ts`
 * - `src/services/android/android-session.ts`
 * - `src/web/components/android/android-coords.ts`
 * - `src/web/components/android/android-log-filter.ts`
 * - `src/web/components/android/android-screenshot-download.ts`
 * - `src/web/components/android/use-android-session.ts`
 * - `src/web/components/android/use-android-touch.ts`
 * - `src/web/components/android/use-android-keyboard.ts`
 */

private val json = Json { ignoreUnknownKeys = true }

// -----------------------------------------------------------------------------
// 1. Quality presets & connection constants (`src/shared/android-protocol.ts`)
// -----------------------------------------------------------------------------

enum class AndroidQuality(
    val wire: String,
    val label: String,
    val maxHeight: Int,
    val fps: Int,
    val bitrateBps: Int,
    val bitrateLabel: String,
) {
    LOW("low", "Low", 720, 24, 1_500_000, "1.5M"),
    BALANCED("balanced", "Balanced", 1280, 30, 4_000_000, "4M"),
    HIGH("high", "High", 1920, 30, 8_000_000, "8M"),
    ;

    companion object {
        fun fromWire(value: String?): AndroidQuality =
            entries.firstOrNull { it.wire == value } ?: BALANCED
    }
}

const val ANDROID_HEARTBEAT_INTERVAL_MS = 5_000L
const val ANDROID_LEASE_TIMEOUT_MS = 15_000L
val ANDROID_RECONNECT_BACKOFF_MS = listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L)

// -----------------------------------------------------------------------------
// 2. Coordinate math (`src/web/components/android/android-coords.ts`)
// -----------------------------------------------------------------------------

data class AndroidGeometry(
    val width: Int = 720,
    val height: Int = 1600,
    val deviceWidth: Int = 1080,
    val deviceHeight: Int = 2400,
    val rotation: Int = 0,
    val generation: Int = 1,
) {
    val frameWidth: Int get() = width
    val frameHeight: Int get() = height
}

data class AndroidDisplayRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val scale: Float,
)

data class AndroidIntPoint(
    val x: Int,
    val y: Int,
)

/**
 * Computes the letterboxed display rectangle of a `[frameWidth] x [frameHeight]` video frame
 * inside a `[stageWidth] x [stageHeight]` container (`containedRect` in `android-coords.ts`).
 */
fun computeAndroidLetterbox(
    stageWidth: Float,
    stageHeight: Float,
    frameWidth: Int,
    frameHeight: Int,
): AndroidDisplayRect {
    if (stageWidth <= 0f || stageHeight <= 0f || frameWidth <= 0 || frameHeight <= 0) {
        return AndroidDisplayRect(0f, 0f, 0f, 0f, 0f)
    }
    val scale = min(stageWidth / frameWidth.toFloat(), stageHeight / frameHeight.toFloat())
    val w = frameWidth * scale
    val h = frameHeight * scale
    return AndroidDisplayRect(
        left = (stageWidth - w) / 2f,
        top = (stageHeight - h) / 2f,
        width = w,
        height = h,
        scale = scale,
    )
}

/**
 * Maps a pointer offset `(offsetX, offsetY)` in the stage container into frame-space coordinates
 * `[0, frameWidth - 1] x [0, frameHeight - 1]`, or returns `null` when the pointer lands on the
 * letterbox bars (`pointerToFrame` in `android-coords.ts`).
 */
fun pointerToFrame(
    offsetX: Float,
    offsetY: Float,
    rect: AndroidDisplayRect,
    frameWidth: Int,
    frameHeight: Int,
): Pair<Float, Float>? {
    if (rect.scale <= 0f || frameWidth <= 0 || frameHeight <= 0) return null
    val x = (offsetX - rect.left) / rect.scale
    val y = (offsetY - rect.top) / rect.scale
    if (x < 0f || y < 0f || x > frameWidth.toFloat() || y > frameHeight.toFloat()) return null
    return min(x, (frameWidth - 1).toFloat()) to min(y, (frameHeight - 1).toFloat())
}

/**
 * Maps frame-space coordinates `(frameX, frameY)` into physical device-panel coordinates
 * accounting for display rotation (`frameToDevice` in `android-coords.ts`):
 * - `0°`:   `pu = u`,     `pv = v`
 * - `90°`:  `pu = 1 - v`, `pv = u`
 * - `180°`: `pu = 1 - u`, `pv = 1 - v`
 * - `270°`: `pu = v`,     `pv = 1 - u`
 */
fun frameToDevice(
    frameX: Float,
    frameY: Float,
    geometry: AndroidGeometry,
): AndroidIntPoint {
    if (geometry.width <= 0 || geometry.height <= 0) return AndroidIntPoint(0, 0)
    val u = frameX / geometry.width.toFloat()
    val v = frameY / geometry.height.toFloat()
    val rot = ((geometry.rotation % 360) + 360) % 360
    val (pu, pv) = when (rot) {
        90 -> (1f - v) to u
        180 -> (1f - u) to (1f - v)
        270 -> v to (1f - u)
        else -> u to v
    }
    return AndroidIntPoint(
        x = round(pu * geometry.deviceWidth.toFloat()).toInt(),
        y = round(pv * geometry.deviceHeight.toFloat()).toInt(),
    )
}

/**
 * Maps a stage pointer `(offsetX, offsetY)` directly into device-space coordinates, returning
 * `null` if the pointer is outside the active video rect (`pointerToDevice` in `android-coords.ts`).
 */
fun pointerToDevice(
    offsetX: Float,
    offsetY: Float,
    rect: AndroidDisplayRect,
    geometry: AndroidGeometry,
): AndroidIntPoint? {
    val (fx, fy) = pointerToFrame(offsetX, offsetY, rect, geometry.width, geometry.height) ?: return null
    return frameToDevice(fx, fy, geometry)
}

/** `0 -> 90 -> 180 -> 270 -> 0` (`nextRotation` in `android-controls.tsx`). */
fun nextAndroidRotation(current: Int): Int = (((current + 90) % 360) + 360) % 360

// -----------------------------------------------------------------------------
// 3. Multitouch slot tracker (`src/web/components/android/use-android-touch.ts`)
// -----------------------------------------------------------------------------

const val MAX_ANDROID_TOUCH_SLOTS = 10
const val ANDROID_TOUCH_PRESSURE_DOWN = 1

data class AndroidTouchPoint(
    val id: Int,
    val x: Int,
    val y: Int,
    val pressure: Int,
)

/**
 * Tracks up to [MAX_ANDROID_TOUCH_SLOTS] concurrent pointers (`0..9`), emitting the full list of
 * active touches (`pressure = 1`) plus any releasing touch (`pressure = 0`) on every event,
 * matching `use-android-touch.ts`.
 */
class AndroidTouchTracker {
    private data class Contact(val slot: Int, var x: Int, var y: Int)

    private val contacts = LinkedHashMap<Long, Contact>()

    val activeCount: Int get() = contacts.size

    fun onDown(pointerId: Long, point: AndroidIntPoint): List<AndroidTouchPoint>? {
        if (contacts.size >= MAX_ANDROID_TOUCH_SLOTS && !contacts.containsKey(pointerId)) return null
        val used = contacts.values.mapTo( HashSet() ) { it.slot }
        var slot = 0
        while (used.contains(slot)) slot++
        contacts[pointerId] = Contact(slot = slot, x = point.x, y = point.y)
        return snapshot(releasing = null)
    }

    fun onMove(pointerId: Long, point: AndroidIntPoint?): List<AndroidTouchPoint>? {
        val contact = contacts[pointerId] ?: return null
        if (point != null) {
            contact.x = point.x
            contact.y = point.y
        }
        return snapshot(releasing = null)
    }

    fun onUpOrCancel(pointerId: Long, point: AndroidIntPoint? = null): List<AndroidTouchPoint>? {
        val contact = contacts.remove(pointerId) ?: return null
        if (point != null) {
            contact.x = point.x
            contact.y = point.y
        }
        return snapshot(releasing = contact)
    }

    /** Clears all active slots and returns `true` if any finger was down (`input-reset`). */
    fun reset(): Boolean {
        if (contacts.isEmpty()) return false
        contacts.clear()
        return true
    }

    private fun snapshot(releasing: Contact?): List<AndroidTouchPoint> {
        val list = ArrayList<AndroidTouchPoint>(contacts.size + (if (releasing != null) 1 else 0))
        for (c in contacts.values) {
            list.add(AndroidTouchPoint(id = c.slot, x = c.x, y = c.y, pressure = ANDROID_TOUCH_PRESSURE_DOWN))
        }
        if (releasing != null) {
            list.add(AndroidTouchPoint(id = releasing.slot, x = releasing.x, y = releasing.y, pressure = 0))
        }
        return list
    }
}

// -----------------------------------------------------------------------------
// 4. Hardware keys & Keyboard / IME text input (`use-android-keyboard.ts`)
// -----------------------------------------------------------------------------

enum class AndroidHardwareKey(val wire: String, val label: String) {
    HOME("home", "Home"),
    BACK("back", "Back"),
    RECENT("recent", "Recent apps"),
    POWER("power", "Power"),
    VOLUME_UP("volume-up", "Volume up"),
    VOLUME_DOWN("volume-down", "Volume down"),
}

/** W3C key names forwarded via `{type:"key", geometryGeneration, key, action:"press"}`. */
val ANDROID_PASSTHROUGH_KEYS: Set<String> = setOf(
    "Enter", "Backspace", "Delete", "Tab", "Escape",
    "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight",
    "Home", "End", "PageUp", "PageDown",
)

const val MAX_ANDROID_TEXT_LENGTH = 500

sealed interface AndroidTextInputKind {
    data object None : AndroidTextInputKind
    data class Backspace(val count: Int = 1) : AndroidTextInputKind
    data object Enter : AndroidTextInputKind
    data class Commit(val text: String) : AndroidTextInputKind
}

/**
 * Compares previous committed text against current text when IME composition is inactive
 * (`hasComposition == false`) so Telex / Vietnamese / CJK composing does not send partial keys.
 */
fun classifyCommittedText(
    previous: String,
    current: String,
    hasComposition: Boolean,
): AndroidTextInputKind {
    if (hasComposition) return AndroidTextInputKind.None
    if (current == previous) return AndroidTextInputKind.None
    if (previous.isNotEmpty() && current.length < previous.length && previous.startsWith(current)) {
        return AndroidTextInputKind.Backspace(previous.length - current.length)
    }
    val delta = if (current.startsWith(previous)) current.substring(previous.length) else current
    if (delta.isEmpty()) return AndroidTextInputKind.None
    if (delta == "\n") return AndroidTextInputKind.Enter
    return AndroidTextInputKind.Commit(delta.take(MAX_ANDROID_TEXT_LENGTH))
}

// -----------------------------------------------------------------------------
// 5. Binary Video Frame (`src/shared/android-protocol.ts`)
// -----------------------------------------------------------------------------

const val ANDROID_PROTOCOL_VERSION: Int = 1
const val ANDROID_VIDEO_HEADER_BYTES: Int = 22
const val VIDEO_FLAG_KEYFRAME: Int = 1 shl 0
const val VIDEO_FLAG_CODEC_CONFIG: Int = 1 shl 1

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
    val flags = (if (header.keyframe) VIDEO_FLAG_KEYFRAME else 0) or (if (header.codecConfig) VIDEO_FLAG_CODEC_CONFIG else 0)
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
            keyframe = (flags and VIDEO_FLAG_KEYFRAME) != 0,
            codecConfig = (flags and VIDEO_FLAG_CODEC_CONFIG) != 0,
            sessionGeneration = sessionGen,
            geometryGeneration = geometryGen,
            sequence = sequence,
            ptsMs = ptsMs,
            payloadLength = payloadLen,
        ),
        payload = payload,
    )
}

/**
 * Drops delta frames until at least one keyframe has been accepted (`shouldDecodeAccessUnit`
 * in `use-h264-canvas-decoder.ts`).
 */
fun shouldDecodeAndroidAccessUnit(hasPicture: Boolean, isKeyframe: Boolean): Boolean =
    hasPicture || isKeyframe

// -----------------------------------------------------------------------------
// 6. Logcat model & filtering (`src/web/components/android/android-log-filter.ts`)
// -----------------------------------------------------------------------------

enum class AndroidLogLevel(
    val wire: String,
    val shortLabel: String,
    val displayLabel: String,
    val priority: Int,
) {
    VERBOSE("verbose", "V", "Verbose", 0),
    DEBUG("debug", "D", "Debug", 1),
    INFO("info", "I", "Info", 2),
    WARN("warn", "W", "Warn", 3),
    ERROR("error", "E", "Error", 4),
    FATAL("fatal", "F", "Fatal", 5),
    ;

    companion object {
        fun fromWire(value: String?): AndroidLogLevel {
            val v = value?.trim()?.lowercase() ?: return INFO
            return entries.firstOrNull { it.wire == v || it.shortLabel.lowercase() == v } ?: INFO
        }
    }
}

data class AndroidLogEntry(
    val id: Long,
    val timestamp: Long,
    val pid: Int = 0,
    val tid: Int = 0,
    val level: AndroidLogLevel,
    val tag: String,
    val message: String,
)

data class AndroidLogFilter(
    val minimum: AndroidLogLevel = AndroidLogLevel.VERBOSE,
    val text: String = "",
    val pid: Int = 0,
) {
    // Alias for convenience in UI calls
    val minLevel: AndroidLogLevel get() = minimum
}

val EMPTY_ANDROID_LOG_FILTER = AndroidLogFilter()

const val ANDROID_LOGCAT_RING_LIMIT = 1_000
const val ANDROID_LOGCAT_MAX_ROWS = 400

fun filterAndroidLogs(
    entries: List<AndroidLogEntry>,
    filter: AndroidLogFilter,
): List<AndroidLogEntry> {
    val needle = filter.text.trim().lowercase()
    return entries.filter { entry ->
        if (entry.level.priority < filter.minimum.priority) return@filter false
        if (filter.pid > 0 && entry.pid != filter.pid) return@filter false
        if (needle.isNotEmpty()) {
            val hit = entry.tag.lowercase().contains(needle) || entry.message.lowercase().contains(needle)
            if (!hit) return@filter false
        }
        true
    }
}

/**
 * Appends `incoming` log entries to `existing`, deduplicating by monotonic `id` on replay and
 * capping at `max` (`appendLogs` in `android-log-filter.ts:57-70`).
 */
fun appendBoundedLogs(
    existing: List<AndroidLogEntry>,
    incoming: List<AndroidLogEntry>,
    max: Int = ANDROID_LOGCAT_RING_LIMIT,
): List<AndroidLogEntry> {
    if (incoming.isEmpty()) return existing
    val lastId = existing.lastOrNull()?.id ?: 0L
    val fresh = if (lastId > 0L) incoming.filter { it.id > lastId } else incoming
    if (fresh.isEmpty()) return existing
    val merged = existing + fresh
    return if (merged.size > max) merged.subList(merged.size - max, merged.size) else merged
}

private val LOG_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

/** Formats epoch milliseconds as local `HH:mm:ss.SSS` (`formatTime` in `android-logcat-panel.tsx`). */
fun formatAndroidLogTime(ms: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
    runCatching {
        Instant.ofEpochMilli(ms).atZone(zoneId).format(LOG_TIME_FORMATTER)
    }.getOrDefault("00:00:00.000")

/** Formats log entries in `adb logcat -v threadtime` style (`formatLogLines` in `android-log-filter.ts`). */
fun formatAndroidLogText(
    entries: List<AndroidLogEntry>,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String =
    entries.joinToString("\n") { e ->
        val time = formatAndroidLogTime(e.timestamp, zoneId)
        val pid = e.pid.toString().padStart(5, ' ')
        val tid = e.tid.toString().padStart(5, ' ')
        "$time $pid $tid ${e.level.shortLabel} ${e.tag}: ${e.message}"
    }

// -----------------------------------------------------------------------------
// 7. AVD validation (`src/shared/android-avd.ts` & `android-avd-create-dialog.tsx`)
// -----------------------------------------------------------------------------

val AVD_NAME_PATTERN = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,62}$")

object AvdLimits {
    const val NAME_MAX_LEN = 63
    const val RAM_MIN_MB = 512
    const val RAM_MAX_MB = 16384
    const val RAM_DEFAULT_MB = 2048
    const val STORAGE_MIN_MB = 1024
    const val STORAGE_MAX_MB = 131072
    const val STORAGE_DEFAULT_MB = 6144
    const val SD_CARD_MIN_MB = 64
    const val SD_CARD_MAX_MB = 65536
    const val SD_CARD_DEFAULT_MB = 512
}

fun validateAvdName(name: String, existingNames: Collection<String> = emptyList()): String? {
    if (name.isEmpty()) return "AVD name is required"
    if (name.length > AvdLimits.NAME_MAX_LEN) return "AVD name must be ${AvdLimits.NAME_MAX_LEN} characters or fewer"
    if (!AVD_NAME_PATTERN.matches(name)) {
        return "AVD name may only contain letters, digits, dots, underscores and hyphens, starting with a letter or digit"
    }
    if (existingNames.any { it.equals(name, ignoreCase = true) }) {
        return "an AVD with that name already exists"
    }
    return null
}

fun validateAvdRam(ramMb: Int): String? =
    if (ramMb !in AvdLimits.RAM_MIN_MB..AvdLimits.RAM_MAX_MB) {
        "RAM must be between ${AvdLimits.RAM_MIN_MB} and ${AvdLimits.RAM_MAX_MB} MB"
    } else {
        null
    }

fun validateAvdStorage(storageMb: Int): String? =
    if (storageMb !in AvdLimits.STORAGE_MIN_MB..AvdLimits.STORAGE_MAX_MB) {
        "Storage must be between ${AvdLimits.STORAGE_MIN_MB} and ${AvdLimits.STORAGE_MAX_MB} MB"
    } else {
        null
    }

fun validateAvdSdCard(sdCardMb: Int?): String? =
    if (sdCardMb != null && sdCardMb !in AvdLimits.SD_CARD_MIN_MB..AvdLimits.SD_CARD_MAX_MB) {
        "SD card must be between ${AvdLimits.SD_CARD_MIN_MB} and ${AvdLimits.SD_CARD_MAX_MB} MB"
    } else {
        null
    }

// -----------------------------------------------------------------------------
// 8. Screenshot Content-Disposition (`android-screenshot-download.ts`)
// -----------------------------------------------------------------------------

private val UTF8_DISPOSITION_REGEX = Regex("""filename\*=UTF-8''([^;\s]+)""", RegexOption.IGNORE_CASE)
private val QUOTED_DISPOSITION_REGEX = Regex("""filename="([^"]+)"""", RegexOption.IGNORE_CASE)
private val BARE_DISPOSITION_REGEX = Regex("""filename=([^;\s]+)""", RegexOption.IGNORE_CASE)

fun filenameFromDisposition(header: String?, fallback: String = "android-screenshot.png"): String {
    if (header.isNullOrBlank()) return sanitizeFilename(fallback)
    val utf8Match = UTF8_DISPOSITION_REGEX.find(header)
    if (utf8Match != null) {
        val decoded = runCatching { URLDecoder.decode(utf8Match.groupValues[1], "UTF-8") }.getOrNull()
        if (!decoded.isNullOrBlank()) return sanitizeFilename(decoded, fallback)
    }
    val raw = QUOTED_DISPOSITION_REGEX.find(header)?.groupValues?.get(1)
        ?: BARE_DISPOSITION_REGEX.find(header)?.groupValues?.get(1)
        ?: return sanitizeFilename(fallback)
    return sanitizeFilename(raw, fallback)
}

private fun sanitizeFilename(raw: String, fallback: String = "android-screenshot.png"): String {
    val cleaned = raw
        .replace('/', '_')
        .replace('\\', '_')
        .replace("..", "_")
        .filter { it.code >= 32 }
        .trim()
    return cleaned.ifEmpty { fallback }
}

// -----------------------------------------------------------------------------
// 9. REST & WebSocket Models + Parsers (`src/server/routes/android.ts`, `android-protocol.ts`)
// -----------------------------------------------------------------------------

enum class AndroidConnState {
    CONNECTING,
    LIVE,
    RECONNECTING,
    FAILED,
}

data class AndroidRequirement(
    val id: String,
    val label: String,
    val met: Boolean,
    val detail: String = "",
    val fix: String? = null,
)

data class AndroidCapabilities(
    val enabled: Boolean = false,
    val authRequired: Boolean = true,
    val ready: Boolean = false,
    val avdCount: Int = 0,
    val accelerationOk: Boolean = false,
    val accelerationDetail: String = "",
    val encoders: List<String> = emptyList(),
    val canCreateAvd: Boolean = false,
    val sdkRoot: String? = null,
    val requirements: List<AndroidRequirement> = emptyList(),
)

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
            entries.firstOrNull { it.wire == value?.lowercase() } ?: STOPPED
    }
}

data class AndroidRuntimeInfo(
    val deviceId: String,
    val generation: Int = 1,
    val adbSerial: String? = null,
    val ownedByPpm: Boolean = false,
)

data class AndroidDeviceItem(
    val avdId: String,
    val name: String,
    val apiLevel: Int? = null,
    val target: String = "",
    val abi: String? = null,
    val deviceProfile: String? = null,
    val path: String = "",
    val displayWidth: Int? = null,
    val displayHeight: Int? = null,
    val hardwareKeyboard: Boolean = true,
    val lockedByAnotherProcess: Boolean = false,
    val state: AndroidDeviceStatus = AndroidDeviceStatus.STOPPED,
    val runtime: AndroidRuntimeInfo? = null,
) {
    val isRunning: Boolean get() = runtime != null
    val deviceId: String? get() = runtime?.deviceId
}

data class SystemImageRow(
    val id: String,
    val apiLevel: Int? = null,
    val abi: String = "",
    val tag: String = "",
    val tagDisplay: String = "",
    val description: String = "",
    val playStore: Boolean = false,
    val bytes: Long = 0L,
    val hostCompatible: Boolean = true,
)

data class AvdDeviceProfile(
    val id: String,
    val name: String,
    val oem: String = "",
    val kind: String = "phone",
)

data class AndroidOperationStatus(
    val id: String,
    val kind: String = "",
    val state: String,
    val detail: String? = null,
    val startedAt: Long = 0L,
    val endedAt: Long? = null,
    val error: String? = null,
) {
    val isTerminal: Boolean get() = state == "succeeded" || state == "failed" || state == "cancelled"
    val isSucceeded: Boolean get() = state == "succeeded"
}

data class ProjectApkItem(
    val path: String,
    val bytes: Long = 0L,
    val modifiedAt: Long = 0L,
) {
    val fileName: String get() = path.substringAfterLast('/').substringAfterLast('\\')
}

data class AndroidMintedSession(
    val nonce: String,
    val deviceId: String,
    val generation: Int = 1,
    val hardwareKeyboard: Boolean = true,
)

sealed interface AndroidServerMessage {
    data class Ready(
        val sessionId: String,
        val sessionGeneration: Int,
        val geometry: AndroidGeometry,
        val controller: Boolean,
        val codec: String?,
        val encoder: String,
    ) : AndroidServerMessage

    data class Codec(val codec: String) : AndroidServerMessage
    data class Geometry(val geometry: AndroidGeometry) : AndroidServerMessage
    data class Controller(val controller: Boolean, val reason: String? = null) : AndroidServerMessage
    data class Quality(val quality: AndroidQuality) : AndroidServerMessage
    data class Status(val booted: Boolean, val detail: String? = null) : AndroidServerMessage
    data class Log(val entries: List<AndroidLogEntry>) : AndroidServerMessage
    data class Error(val message: String, val fatal: Boolean = false) : AndroidServerMessage
    data object Heartbeat : AndroidServerMessage
    data class Unknown(val type: String) : AndroidServerMessage
}

// -----------------------------------------------------------------------------
// 10. JSON Parsers & Builders
// -----------------------------------------------------------------------------

private fun JsonObject.str(key: String, default: String = ""): String =
    (this[key] as? JsonPrimitive)?.contentOrNull ?: default

private fun JsonObject.strOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() && it != "null" }

private fun JsonObject.int(key: String, default: Int = 0): Int =
    (this[key] as? JsonPrimitive)?.intOrNull ?: default

private fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.long(key: String, default: Long = 0L): Long =
    (this[key] as? JsonPrimitive)?.longOrNull ?: default

private fun JsonObject.bool(key: String, default: Boolean = false): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull ?: default

fun parseAndroidCapabilities(element: JsonElement): AndroidCapabilities {
    val obj = element as? JsonObject ?: return AndroidCapabilities()
    val reqs = (obj["requirements"] as? JsonArray).orEmpty().mapNotNull { item ->
        val r = item as? JsonObject ?: return@mapNotNull null
        val id = r.str("id")
        if (id.isBlank()) return@mapNotNull null
        AndroidRequirement(
            id = id,
            label = r.str("label", id),
            met = r.bool("met", false),
            detail = r.str("detail"),
            fix = r.strOrNull("fix"),
        )
    }
    val sdkObj = obj["sdk"] as? JsonObject
    val encoders = (obj["encoders"] as? JsonArray).orEmpty().mapNotNull {
        (it as? JsonPrimitive)?.contentOrNull
    }
    val avdmanagerObj = sdkObj?.get("avdmanager") as? JsonObject
    val hasAvdmanager = avdmanagerObj?.strOrNull("path") != null
    return AndroidCapabilities(
        enabled = obj.bool("enabled", false),
        authRequired = obj.bool("authRequired", true),
        ready = obj.bool("ready", reqs.isNotEmpty() && reqs.all { it.met }),
        avdCount = obj.int("avdCount", 0),
        accelerationOk = obj.bool("accelerationOk", false),
        accelerationDetail = obj.str("accelerationDetail"),
        encoders = encoders,
        canCreateAvd = obj.bool("canCreateAvd", hasAvdmanager),
        sdkRoot = sdkObj?.strOrNull("root") ?: obj.strOrNull("sdkRoot"),
        requirements = reqs,
    )
}

fun parseAndroidDevices(element: JsonElement): List<AndroidDeviceItem> {
    val arr = when (element) {
        is JsonArray -> element
        is JsonObject -> element["devices"] as? JsonArray ?: JsonArray(emptyList())
        else -> JsonArray(emptyList())
    }
    return arr.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val avdId = obj.str("avdId").ifBlank { obj.str("name") }
        if (avdId.isBlank()) return@mapNotNull null
        val rtObj = obj["runtime"] as? JsonObject
        val runtime = rtObj?.let {
            val deviceId = it.str("deviceId")
            if (deviceId.isBlank()) {
                null
            } else {
                AndroidRuntimeInfo(
                    deviceId = deviceId,
                    generation = it.int("generation", 1),
                    adbSerial = it.strOrNull("adbSerial"),
                    ownedByPpm = it.bool("ownedByPpm", false),
                )
            }
        }
        val state = AndroidDeviceStatus.fromWire(
            obj.strOrNull("state") ?: if (runtime != null) "ready" else "stopped",
        )
        AndroidDeviceItem(
            avdId = avdId,
            name = obj.str("name", avdId),
            apiLevel = obj.intOrNull("apiLevel"),
            target = obj.str("target"),
            abi = obj.strOrNull("abi"),
            deviceProfile = obj.strOrNull("deviceProfile"),
            path = obj.str("path"),
            displayWidth = obj.intOrNull("displayWidth"),
            displayHeight = obj.intOrNull("displayHeight"),
            hardwareKeyboard = obj.bool("hardwareKeyboard", true),
            lockedByAnotherProcess = obj.bool("lockedByAnotherProcess", false),
            state = state,
            runtime = runtime,
        )
    }
}

fun parseAndroidSystemImages(element: JsonElement): List<SystemImageRow> {
    val arr = when (element) {
        is JsonArray -> element
        is JsonObject -> element["images"] as? JsonArray ?: JsonArray(emptyList())
        else -> JsonArray(emptyList())
    }
    return arr.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val id = obj.str("id")
        if (id.isBlank()) return@mapNotNull null
        val tag = obj.str("tag", "default")
        SystemImageRow(
            id = id,
            apiLevel = obj.intOrNull("apiLevel"),
            abi = obj.str("abi"),
            tag = tag,
            tagDisplay = obj.str("tagDisplay", tag),
            description = obj.str("description", id),
            playStore = obj.bool("playStore", tag == "google_apis_playstore"),
            bytes = obj.long("bytes", 0L),
            hostCompatible = obj.bool("hostCompatible", true),
        )
    }
}

fun parseAndroidDeviceProfiles(element: JsonElement): List<AvdDeviceProfile> {
    val arr = when (element) {
        is JsonArray -> element
        is JsonObject -> element["profiles"] as? JsonArray ?: JsonArray(emptyList())
        else -> JsonArray(emptyList())
    }
    return arr.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val id = obj.str("id")
        if (id.isBlank()) return@mapNotNull null
        AvdDeviceProfile(
            id = id,
            name = obj.str("name", id),
            oem = obj.str("oem"),
            kind = obj.str("kind", "phone"),
        )
    }
}

fun parseAndroidOperation(element: JsonElement): AndroidOperationStatus? {
    val obj = element as? JsonObject ?: return null
    val id = obj.str("operationId").ifBlank { obj.str("id") }
    if (id.isBlank()) return null
    val resultObj = obj["result"] as? JsonObject
    val detail = obj.strOrNull("detail") ?: resultObj?.strOrNull("message")
    return AndroidOperationStatus(
        id = id,
        kind = obj.str("kind"),
        state = obj.str("state", "pending"),
        detail = detail,
        startedAt = obj.long("startedAt", 0L),
        endedAt = (obj["endedAt"] as? JsonPrimitive)?.longOrNull,
        error = obj.strOrNull("error"),
    )
}

fun parseProjectApks(element: JsonElement): List<ProjectApkItem> {
    val arr = when (element) {
        is JsonArray -> element
        is JsonObject -> element["apks"] as? JsonArray ?: JsonArray(emptyList())
        else -> JsonArray(emptyList())
    }
    return arr.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val path = obj.str("path")
        if (path.isBlank()) return@mapNotNull null
        ProjectApkItem(
            path = path,
            bytes = obj.long("bytes", 0L),
            modifiedAt = obj.long("modifiedAt", 0L),
        )
    }
}

fun parseAndroidMintedSession(element: JsonElement): AndroidMintedSession? {
    val obj = element as? JsonObject ?: return null
    val nonce = obj.str("nonce")
    if (nonce.isBlank()) return null
    return AndroidMintedSession(
        nonce = nonce,
        deviceId = obj.str("deviceId"),
        generation = obj.int("generation", 1),
        hardwareKeyboard = obj.bool("hardwareKeyboard", true),
    )
}

fun parseAndroidGeometry(obj: JsonObject?): AndroidGeometry {
    if (obj == null) return AndroidGeometry()
    val width = obj.int("width", 720)
    val height = obj.int("height", 1600)
    return AndroidGeometry(
        width = width,
        height = height,
        deviceWidth = obj.int("deviceWidth", width),
        deviceHeight = obj.int("deviceHeight", height),
        rotation = obj.int("rotation", 0),
        generation = obj.int("generation", 1),
    )
}

fun parseAndroidLogEntry(obj: JsonObject?): AndroidLogEntry? {
    if (obj == null) return null
    return AndroidLogEntry(
        id = obj.long("id", 0L),
        timestamp = obj.long("timestamp", 0L),
        pid = obj.int("pid", 0),
        tid = obj.int("tid", 0),
        level = AndroidLogLevel.fromWire(obj.str("level", "info")),
        tag = obj.str("tag"),
        message = obj.str("message"),
    )
}

fun parseAndroidServerMessage(rawJson: String): AndroidServerMessage? {
    val obj = runCatching { json.parseToJsonElement(rawJson) as? JsonObject }.getOrNull() ?: return null
    val type = obj.str("type")
    if (type.isBlank()) return null
    return when (type) {
        "ready" -> AndroidServerMessage.Ready(
            sessionId = obj.str("sessionId"),
            sessionGeneration = obj.int("sessionGeneration", 1),
            geometry = parseAndroidGeometry(obj["geometry"] as? JsonObject),
            controller = obj.bool("controller", false),
            codec = obj.strOrNull("codec"),
            encoder = obj.str("encoder", "software"),
        )
        "codec" -> AndroidServerMessage.Codec(
            codec = obj.str("codec"),
        )
        "geometry" -> AndroidServerMessage.Geometry(
            geometry = parseAndroidGeometry(obj["geometry"] as? JsonObject),
        )
        "controller" -> AndroidServerMessage.Controller(
            controller = obj.bool("controller", false),
            reason = obj.strOrNull("reason"),
        )
        "quality" -> AndroidServerMessage.Quality(
            quality = AndroidQuality.fromWire(obj.str("quality")),
        )
        "status" -> AndroidServerMessage.Status(
            booted = obj.bool("booted", false),
            detail = obj.strOrNull("detail"),
        )
        "log" -> {
            val entries = (obj["entries"] as? JsonArray).orEmpty().mapNotNull {
                parseAndroidLogEntry(it as? JsonObject)
            }
            AndroidServerMessage.Log(entries = entries)
        }
        "error" -> AndroidServerMessage.Error(
            message = obj.str("message", "Unknown error"),
            fatal = obj.bool("fatal", false),
        )
        "heartbeat" -> AndroidServerMessage.Heartbeat
        else -> AndroidServerMessage.Unknown(type)
    }
}

// -----------------------------------------------------------------------------
// 11. Client -> Server JSON Builders (`src/shared/android-protocol.ts`)
// -----------------------------------------------------------------------------

fun buildAndroidAuthJson(
    nonce: String,
    quality: AndroidQuality = AndroidQuality.BALANCED,
): String =
    buildJsonObject {
        put("type", "auth")
        put("nonce", nonce)
        put("quality", quality.wire)
    }.toString()

fun buildAndroidTouchJson(
    geometryGeneration: Int,
    touches: List<AndroidTouchPoint>,
): String =
    buildJsonObject {
        put("type", "touch")
        put("geometryGeneration", geometryGeneration)
        put(
            "touches",
            buildJsonArray {
                for (pt in touches) {
                    add(
                        buildJsonObject {
                            put("id", pt.id)
                            put("x", pt.x)
                            put("y", pt.y)
                            put("pressure", pt.pressure)
                        },
                    )
                }
            },
        )
    }.toString()

fun buildAndroidInputResetJson(): String =
    buildJsonObject {
        put("type", "input-reset")
    }.toString()

fun buildAndroidKeyJson(
    geometryGeneration: Int,
    key: String,
    action: String = "press",
): String =
    buildJsonObject {
        put("type", "key")
        put("geometryGeneration", geometryGeneration)
        put("key", key)
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

fun buildAndroidHardwareKeyJson(key: AndroidHardwareKey): String =
    buildJsonObject {
        put("type", "hardware")
        put("key", key.wire)
    }.toString()

fun buildAndroidRotateJson(rotation: Int): String =
    buildJsonObject {
        put("type", "rotate")
        put("rotation", ((rotation % 360) + 360) % 360)
    }.toString()

fun buildAndroidQualityJson(quality: AndroidQuality): String =
    buildJsonObject {
        put("type", "quality")
        put("quality", quality.wire)
    }.toString()

fun buildAndroidVisibilityJson(visible: Boolean): String =
    buildJsonObject {
        put("type", "visibility")
        put("visible", visible)
    }.toString()

fun buildAndroidTakeControlJson(): String =
    buildJsonObject {
        put("type", "take-control")
    }.toString()

fun buildAndroidLogcatJson(subscribe: Boolean): String =
    buildJsonObject {
        put("type", "logcat")
        put("subscribe", subscribe)
    }.toString()

fun buildAndroidHeartbeatJson(): String =
    buildJsonObject {
        put("type", "heartbeat")
    }.toString()
