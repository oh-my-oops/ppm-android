package com.hienlh.ppm.state.terminal

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.SocketConnection
import kotlinx.coroutines.channels.ReceiveChannel

/** The existing transport, with a small seam for testing the controller's timers. */
internal interface TerminalConnection {
    val events: ReceiveChannel<SocketConnection.Event>
    fun send(text: String): Boolean
    fun close()
}

internal class TerminalSocket(url: String) : TerminalConnection {
    private val socket = SocketConnection(url)
    override val events get() = socket.events
    override fun send(text: String) = socket.send(text)
    override fun close() = socket.close()
}

/** Same base-path and query-token rules as ApiClient's other sockets. */
internal fun terminalSocketUrl(api: ApiClient, project: String, sessionId: String, cwd: String?): String {
    val builder = api.baseUrl.newBuilder()
        .encodedPath(
            api.baseUrl.encodedPath.trimEnd('/') +
                "/ws/project/${ApiClient.escape(project)}/terminal/${ApiClient.escape(sessionId)}",
        )
        .query(null)
        .fragment(null)
        .addQueryParameter("token", api.token)
    if (sessionId == "new" && !cwd.isNullOrEmpty()) builder.addQueryParameter("cwd", cwd)
    val http = builder.build().toString()
    return if (http.startsWith("https:")) "wss:" + http.removePrefix("https:") else "ws:" + http.removePrefix("http:")
}
