package com.hienlh.ppm.state.terminal

import com.hienlh.ppm.client.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Exercises the public constructor against the real SocketConnection/OkHttp event channel. */
class TerminalSocketTest {
    private class Peer : WebSocketListener() {
        val sockets = LinkedBlockingQueue<WebSocket>()
        val input = LinkedBlockingQueue<String>()
        override fun onOpen(webSocket: WebSocket, response: Response) {
            sockets.put(webSocket)
            webSocket.send("""{"type":"session","id":"server-session"}""")
            webSocket.send("replay: 雪\r\n")
        }
        override fun onMessage(webSocket: WebSocket, text: String) { input.put(text) }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
    }

    private fun withServer(block: suspend CoroutineScope.(MockWebServer, Peer) -> Unit) = runBlocking {
        val server = MockWebServer()
        val peer = Peer()
        repeat(3) { server.enqueue(MockResponse.Builder().webSocketUpgrade(peer).build()) }
        server.start()
        try {
            block(server, peer)
        } finally {
            server.close()
        }
    }

    private suspend fun <T> LinkedBlockingQueue<T>.next(): T = withContext(Dispatchers.IO) {
        checkNotNull(poll(3, TimeUnit.SECONDS)) { "Timed out waiting for a socket/frame" }
    }

    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(3_000) {
        while (!condition()) delay(10)
    }

    @Test
    fun `real socket sends authenticated encoded route and raw input and resets before reconnect replay`() = withServer { server, peer ->
        val values = TerminalValues()
        val delivery = mutableListOf<String>()
        val controller = TerminalController(
            api = ApiClient(server.url("/base/"), "test +/&?#"),
            project = "p /雪",
            tabId = "stable-tab",
            scope = this,
            store = values,
            keyPrefix = "server:",
            cwd = "/tmp/雪 +&",
            onOutput = { delivery += it.toString(Charsets.UTF_8) },
            onReset = { delivery += "RESET" },
        )
        controller.use {
            controller.setVisible(true)
            controller.resize(90, 30)
            controller.start()
            val first = peer.sockets.next()
            waitFor { delivery.size == 1 }
            val request = withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS)!! }
            assertEquals(listOf("base", "ws", "project", "p /雪", "terminal", "new"), request.url.pathSegments)
            assertEquals("test +/&?#", request.url.queryParameter("token"))
            assertEquals("/tmp/雪 +&", request.url.queryParameter("cwd"))
            assertEquals("server-session", values.get("server:ppm:terminal-session:stable-tab"))
            assertEquals("\u0001RESIZE:90,30", peer.input.next())
            assertTrue(controller.sendBytes("echo 雪\r".toByteArray()))
            assertEquals("echo 雪\r", peer.input.next())
            first.send("binary: 雪\r\n".toByteArray().toByteString())
            first.send("\u0001PONG")
            first.send("""{"type":"unknown","text":"never output"}""")
            waitFor { delivery.size == 2 }
            assertEquals(listOf("replay: 雪\r\n", "binary: 雪\r\n"), delivery)

            first.close(1001, "network changed")
            waitFor { controller.state.value.reconnecting }
            assertFalse(controller.send("must not queue"))
            controller.foreground()
            peer.sockets.next()
            waitFor { delivery.size == 4 }
            val reconnect = withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS)!! }
            assertEquals("server-session", reconnect.url.pathSegments.last())
            assertNull(reconnect.url.queryParameter("cwd"))
            assertEquals(listOf("replay: 雪\r\n", "binary: 雪\r\n", "RESET", "replay: 雪\r\n"), delivery)
            assertEquals("\u0001RESIZE:90,30", peer.input.next())
            assertTrue(controller.state.value.connected)
            controller.close()
            delay(1_100) // The cancelled one-second retry must never open a third socket.
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `public constructor accepts metadata and restart creates new without cwd on attach`() = withServer { server, peer ->
        val delivery = mutableListOf<String>()
        val controller = TerminalController(
            api = ApiClient(server.url("/"), "t"), project = "p", tabId = "tab", scope = this,
            store = TerminalValues(), keyPrefix = "server:", cwd = "/tmp/initial",
            initialSessionId = "metadata-session",
            onOutput = { delivery += it.toString(Charsets.UTF_8) }, onReset = { delivery += "RESET" },
        )
        controller.use {
            controller.start()
            peer.sockets.next()
            waitFor { delivery.isNotEmpty() }
            val attach = withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS)!! }
            assertEquals("metadata-session", attach.url.pathSegments.last())
            assertNull(attach.url.queryParameter("cwd"))
            controller.restart()
            peer.sockets.next()
            waitFor { delivery.size == 3 }
            val fresh = withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS)!! }
            assertEquals("new", fresh.url.pathSegments.last())
            assertEquals("/tmp/initial", fresh.url.queryParameter("cwd"))
            assertEquals(listOf("replay: 雪\r\n", "RESET", "replay: 雪\r\n"), delivery)
        }
    }
}
