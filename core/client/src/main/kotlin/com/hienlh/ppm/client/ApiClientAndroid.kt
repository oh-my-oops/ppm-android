package com.hienlh.ppm.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/**
 * Android Device / Emulator Controller REST & WebSocket client (`Phase 11c`).
 *
 * All requests omit the `Origin` header so the server's browser cross-origin guard allows native
 * Android client traffic.
 */

data class AndroidScreenshotResponse(
    val pngBytes: ByteArray,
    val contentDisposition: String?,
    val dimensionsHeader: String?,
)

/** Host Android SDK / emulator capabilities (`GET /api/android/capabilities`). */
suspend fun ApiClient.androidCapabilities(): JsonElement =
    call("GET", "/api/android/capabilities")

/** Configured AVDs and running devices (`GET /api/android/devices`). */
suspend fun ApiClient.androidDevices(): JsonElement =
    call("GET", "/api/android/devices")

/** Start an AVD (`POST /api/android/avds/:avdId/start`). */
suspend fun ApiClient.androidStartAvd(avdId: String): JsonElement =
    call(
        method = "POST",
        path = "/api/android/avds/${ApiClient.escape(avdId)}/start",
        body = buildJsonObject {},
    )

/** Poll an async operation (`GET /api/android/operations/:id`). */
suspend fun ApiClient.androidOperationStatus(operationId: String): JsonElement =
    call("GET", "/api/android/operations/${ApiClient.escape(operationId)}")

/** Cancel an async operation (`POST /api/android/operations/:id/cancel`). */
suspend fun ApiClient.androidCancelOperation(operationId: String): JsonElement =
    call(
        method = "POST",
        path = "/api/android/operations/${ApiClient.escape(operationId)}/cancel",
        body = buildJsonObject {},
    )

/** Stop a running emulator (`POST /api/android/devices/:deviceId/stop`). */
suspend fun ApiClient.androidStopDevice(
    deviceId: String,
    generation: Int? = null,
): JsonElement {
    val query = if (generation != null) listOf("generation" to generation.toString()) else emptyList()
    return call(
        method = "POST",
        path = "/api/android/devices/${ApiClient.escape(deviceId)}/stop",
        query = query,
        body = buildJsonObject {
            if (generation != null) put("generation", generation)
        },
    )
}

/** Emulator boot / runtime log tail (`GET /api/android/devices/:deviceId/log`). */
suspend fun ApiClient.androidDeviceBootLog(deviceId: String): JsonElement =
    call("GET", "/api/android/devices/${ApiClient.escape(deviceId)}/log")

/** Mint a single-use 30s session nonce (`POST /api/android/devices/:deviceId/sessions`). */
suspend fun ApiClient.androidCreateSession(deviceId: String): JsonElement =
    call(
        method = "POST",
        path = "/api/android/devices/${ApiClient.escape(deviceId)}/sessions",
        body = buildJsonObject {},
    )

/** List Android SDK system images (`GET /api/android/system-images`). */
suspend fun ApiClient.androidSystemImages(): JsonElement =
    call("GET", "/api/android/system-images")

/** List AVD hardware profiles (`GET /api/android/device-profiles`). */
suspend fun ApiClient.androidDeviceProfiles(): JsonElement =
    call("GET", "/api/android/device-profiles")

/** Start creating an AVD (downloads system image if needed) (`POST /api/android/avds`). */
suspend fun ApiClient.androidCreateAvd(
    name: String,
    systemImageId: String,
    deviceProfileId: String,
    ramMb: Int? = null,
    storageMb: Int? = null,
): JsonElement =
    call(
        method = "POST",
        path = "/api/android/avds",
        body = buildJsonObject {
            put("name", name)
            put("systemImageId", systemImageId)
            put("deviceProfileId", deviceProfileId)
            if (ramMb != null) put("ramMb", ramMb)
            if (storageMb != null) put("storageMb", storageMb)
        },
    )

/** Wipe an AVD's user data (`POST /api/android/avds/:avdId/wipe`). */
suspend fun ApiClient.androidWipeAvd(avdId: String): JsonElement =
    call(
        method = "POST",
        path = "/api/android/avds/${ApiClient.escape(avdId)}/wipe",
        body = buildJsonObject {},
    )

/** Delete an AVD permanently (`DELETE /api/android/avds/:avdId`). */
suspend fun ApiClient.androidDeleteAvd(avdId: String): JsonElement =
    call("DELETE", "/api/android/avds/${ApiClient.escape(avdId)}")

/** Read the device's clipboard (`GET /api/android/devices/:deviceId/clipboard`). */
suspend fun ApiClient.androidGetClipboard(deviceId: String): JsonElement =
    call("GET", "/api/android/devices/${ApiClient.escape(deviceId)}/clipboard")

/** Write text to the device's clipboard (`POST /api/android/devices/:deviceId/clipboard`). */
suspend fun ApiClient.androidSetClipboard(deviceId: String, text: String): JsonElement =
    call(
        method = "POST",
        path = "/api/android/devices/${ApiClient.escape(deviceId)}/clipboard",
        body = buildJsonObject { put("text", text) },
    )

/** Capture a PNG screenshot (`GET /api/android/devices/:deviceId/screenshot`). */
suspend fun ApiClient.androidTakeScreenshot(deviceId: String): AndroidScreenshotResponse {
    val req = request("GET", "/api/android/devices/${ApiClient.escape(deviceId)}/screenshot")
    val response = PpmHttp.client.newCall(req).await()
    return withContext(Dispatchers.IO) {
        response.use { res ->
            if (res.code !in 200..299) {
                ApiClient.unwrap(res.code, res.header("Content-Type"), res.body.string())
            }
            AndroidScreenshotResponse(
                pngBytes = res.body.bytes(),
                contentDisposition = res.header("Content-Disposition"),
                dimensionsHeader = res.header("X-Android-Screenshot-Size"),
            )
        }
    }
}

/** List built `.apk` files in a project (`GET /api/android/projects/:project/apks`). */
suspend fun ApiClient.androidProjectApks(projectName: String): JsonElement =
    call("GET", "/api/android/projects/${ApiClient.escape(projectName)}/apks")

/** Install a project `.apk` directly on the server (`POST /api/android/devices/:deviceId/apk/project`). */
suspend fun ApiClient.androidInstallProjectApk(
    deviceId: String,
    projectName: String,
    relativePath: String,
): JsonElement =
    call(
        method = "POST",
        path = "/api/android/devices/${ApiClient.escape(deviceId)}/apk/project",
        body = buildJsonObject {
            put("projectName", projectName)
            put("relativePath", relativePath)
        },
    )

/**
 * Stream an `.apk` binary upload (`POST /api/android/devices/:deviceId/apk?name=...`) with
 * byte-level progress reporting and coroutine cancellation support.
 */
suspend fun ApiClient.androidUploadApk(
    deviceId: String,
    fileName: String,
    contentLength: Long,
    openStream: () -> InputStream,
    onProgress: (loaded: Long, total: Long) -> Unit = { _, _ -> },
): JsonElement = withContext(Dispatchers.IO) {
    val body = object : RequestBody() {
        override fun contentType() = "application/vnd.android.package-archive".toMediaType()
        override fun contentLength(): Long = if (contentLength >= 0L) contentLength else -1L
        override fun isOneShot(): Boolean = true

        override fun writeTo(sink: BufferedSink) {
            openStream().use { input ->
                val buf = ByteArray(16 * 1024)
                var loaded = 0L
                val total = if (contentLength > 0L) contentLength else 0L
                onProgress(0L, total)
                while (true) {
                    val read = input.read(buf)
                    if (read < 0) break
                    sink.write(buf, 0, read)
                    sink.flush()
                    loaded += read
                    onProgress(loaded, total)
                }
            }
        }
    }
    val req = request(
        method = "POST",
        path = "/api/android/devices/${ApiClient.escape(deviceId)}/apk",
        query = listOf("name" to fileName),
        body = body,
    )
    val response = PpmHttp.streamClient.newCall(req).await()
    response.use { res ->
        val text = res.body.string()
        ApiClient.unwrap(res.code, res.header("Content-Type"), text)
    }
}

/** The authenticated `/ws/android?token=…` WebSocket URL. */
fun ApiClient.androidSocketUrl(): String =
    socketUrl("/ws/android", emptyList())

/** Open a WebSocket to `/ws/android?token=…` without an `Origin` header. */
fun ApiClient.openAndroidSocket(listener: WebSocketListener): WebSocket {
    val req = Request.Builder()
        .url(androidSocketUrl())
        .header("x-ppm-client", PpmHttp.CLIENT_NAME)
        .build()
    return PpmHttp.socketClient.newWebSocket(req, listener)
}

/** Open a channel-based [SocketConnection] to `/ws/android?token=…`. */
fun ApiClient.openAndroidConnection(): SocketConnection =
    SocketConnection(androidSocketUrl())
