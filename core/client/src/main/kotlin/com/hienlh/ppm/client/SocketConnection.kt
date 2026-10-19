package com.hienlh.ppm.client

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One WebSocket connection, as a channel of events. It never reconnects by itself: the owner sees
 * [Event.Closed] and decides, because what a reconnect must do (send `ready`, reload) is protocol,
 * not transport.
 */
class SocketConnection(url: String, client: OkHttpClient = PpmHttp.socketClient) {
    sealed interface Event {
        data object Opened : Event
        data class Text(val text: String) : Event
        data class Binary(val bytes: ByteString) : Event
        data class Closed(val reason: String?) : Event
    }

    private val channel = Channel<Event>(Channel.UNLIMITED)
    private val finished = AtomicBoolean(false)

    @Volatile
    private var socket: WebSocket? = null

    /** Ends with [Event.Closed], exactly once. */
    val events: ReceiveChannel<Event> get() = channel

    init {
        socket = client.newWebSocket(Request.Builder().url(url).build(), Listener())
    }

    /** False when the connection is gone or OkHttp's 16 MB outgoing queue is full; the socket is then closed. */
    fun send(text: String): Boolean {
        val sent = socket?.send(text) == true
        if (!sent) finish("The message could not be sent.")
        return sent
    }

    fun close() = finish(null)

    private fun finish(reason: String?, webSocket: WebSocket? = socket) {
        if (!finished.compareAndSet(false, true)) return
        if (webSocket != null && !webSocket.close(1000, null)) webSocket.cancel()
        channel.trySend(Event.Closed(reason))
        channel.close()
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            channel.trySend(Event.Opened)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            channel.trySend(Event.Text(text))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            channel.trySend(Event.Binary(bytes))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            finish("The server closed the connection ($code).", webSocket)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            finish("The server closed the connection ($code).", webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val reason = when {
                response != null && response.code == 401 -> "The password was refused."
                response != null -> "The server answered HTTP ${response.code}."
                t is IOException -> describe(t)
                else -> t.message ?: "The connection ended."
            }
            finish(reason, webSocket)
        }
    }
}
