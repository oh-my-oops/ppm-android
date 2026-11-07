package com.hienlh.ppm.client

import kotlinx.serialization.json.JsonElement
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Remote Desktop REST & WebSocket client (`Phase 11b`).
 *
 * Importantly, no `Origin` header is sent on any request or WebSocket upgrade so the server's
 * cross-origin guard allows native app requests.
 */

/** Host capabilities and readiness checklist (`GET /api/remote-desktop/capabilities`). */
suspend fun ApiClient.remoteDesktopCapabilities(): JsonElement =
    call("GET", "/api/remote-desktop/capabilities")

/** Current ffmpeg installer status (`GET /api/remote-desktop/requirements/ffmpeg/install`). */
suspend fun ApiClient.remoteDesktopFfmpegInstallStatus(): JsonElement =
    call("GET", "/api/remote-desktop/requirements/ffmpeg/install")

/** Start ffmpeg auto-install (`POST /api/remote-desktop/requirements/ffmpeg/install`). */
suspend fun ApiClient.remoteDesktopFfmpegInstallStart(): JsonElement =
    call("POST", "/api/remote-desktop/requirements/ffmpeg/install")

/** Run a host-side requirement action (`request` or `open-settings`). */
suspend fun ApiClient.remoteDesktopRequirementAction(id: String, action: String): JsonElement =
    call("POST", "/api/remote-desktop/requirements/${ApiClient.escape(id)}/${ApiClient.escape(action)}")

/** Mint a single-use 30s session nonce (`POST /api/remote-desktop/session` -> `{ nonce, wsPath }`). */
suspend fun ApiClient.remoteDesktopMintSession(): JsonElement =
    call("POST", "/api/remote-desktop/session")

/** MediaMTX WebRTC relay status (`GET /api/remote-desktop/relay`). */
suspend fun ApiClient.remoteDesktopRelayStatus(): JsonElement =
    call("GET", "/api/remote-desktop/relay")

/** Start installing the MediaMTX relay (`POST /api/remote-desktop/relay/install`). */
suspend fun ApiClient.remoteDesktopRelayInstall(): JsonElement =
    call("POST", "/api/remote-desktop/relay/install")

/** Uninstall the bundled MediaMTX relay (`POST /api/remote-desktop/relay/uninstall`). */
suspend fun ApiClient.remoteDesktopRelayUninstall(): JsonElement =
    call("POST", "/api/remote-desktop/relay/uninstall")

/** The authenticated `/ws/remote-desktop?token=…` WebSocket URL. */
fun ApiClient.remoteDesktopSocketUrl(): String =
    socketUrl("/ws/remote-desktop", emptyList())

/** Open a WebSocket to `/ws/remote-desktop?token=…` without an `Origin` header. */
fun ApiClient.openRemoteDesktopSocket(listener: WebSocketListener): WebSocket {
    val req = Request.Builder()
        .url(remoteDesktopSocketUrl())
        .header("x-ppm-client", PpmHttp.CLIENT_NAME)
        .build()
    return PpmHttp.socketClient.newWebSocket(req, listener)
}

/** Open a channel-based [SocketConnection] to `/ws/remote-desktop?token=…`. */
fun ApiClient.openRemoteDesktopConnection(): SocketConnection =
    SocketConnection(remoteDesktopSocketUrl())
