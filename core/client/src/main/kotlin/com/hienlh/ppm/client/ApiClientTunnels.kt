package com.hienlh.ppm.client

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Unified tunnel list (`GET /api/tunnels`). */
suspend fun ApiClient.tunnelsList(force: Boolean = false): JsonElement =
    call("GET", "/api/tunnels", if (force) listOf("force" to "1") else emptyList())

/** Tailscale forward transport availability (`GET /api/tunnels/transports`). */
suspend fun ApiClient.tunnelsTransports(): JsonElement =
    call("GET", "/api/tunnels/transports")

/** Start forwarding a localhost port (`POST /api/tunnels`). */
suspend fun ApiClient.tunnelStart(port: Int, via: String = "cloudflare"): JsonElement =
    call("POST", "/api/tunnels", body = buildJsonObject {
        put("port", port)
        put("via", via)
    })

/** Stop a tunnel by PID (`DELETE /api/tunnels/:pid`). */
suspend fun ApiClient.tunnelStop(pid: Int): JsonElement =
    call("DELETE", "/api/tunnels/$pid")

/** Named Cloudflare tunnel status (`GET /api/tunnel/named/status`). */
suspend fun ApiClient.namedTunnelStatus(): JsonElement =
    call("GET", "/api/tunnel/named/status")
