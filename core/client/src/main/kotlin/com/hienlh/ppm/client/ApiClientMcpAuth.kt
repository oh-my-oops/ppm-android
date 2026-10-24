package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.McpAuthFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl

/* Signing in to an MCP server (`api-mcp-auth.ts`). */

private const val FLOWS = "/api/mcp-auth/flows"

/**
 * `POST /api/mcp-auth/start`: begins a sign-in as a chat in [project] would see the server. The
 * origin lets the sign-in return to PPM itself; the server uses it only when it is https and the
 * host this request arrived on.
 */
suspend fun ApiClient.startMcpAuth(server: String, project: String?): McpAuthFlow =
    flow(
        call(
            "POST", "/api/mcp-auth/start",
            body = buildJsonObject {
                put("server", server)
                project?.let { put("project", it) }
                put("origin", origin)
            },
        ),
    )

suspend fun ApiClient.mcpAuthFlow(id: String): McpAuthFlow = flow(call("GET", "$FLOWS/${ApiClient.escape(id)}"))

/** The address the browser ended on after approving, pasted. */
suspend fun ApiClient.submitMcpAuthCallback(id: String, url: String): McpAuthFlow =
    flow(call("POST", "$FLOWS/${ApiClient.escape(id)}/callback", body = buildJsonObject { put("url", url) }))

/** "I've granted access", for a server that never redirects back. */
suspend fun ApiClient.confirmMcpAuth(id: String): McpAuthFlow =
    flow(call("POST", "$FLOWS/${ApiClient.escape(id)}/confirm", body = JsonObject(emptyMap())))

suspend fun ApiClient.cancelMcpAuth(id: String) {
    call("DELETE", "$FLOWS/${ApiClient.escape(id)}")
}

/**
 * `window.location.origin` of the page this app stands in for: the server's address without its
 * path, its scheme and host in lower case and a default port left out.
 */
val ApiClient.origin: String
    get() {
        val host = if (baseUrl.host.contains(':')) "[${baseUrl.host}]" else baseUrl.host
        val port = if (baseUrl.port == HttpUrl.defaultPort(baseUrl.scheme)) "" else ":${baseUrl.port}"
        return "${baseUrl.scheme}://$host$port"
    }

private fun flow(json: JsonElement): McpAuthFlow = McpAuthFlow.from(json) ?: throw PpmError.BadResponse("The server sent no sign-in.")
