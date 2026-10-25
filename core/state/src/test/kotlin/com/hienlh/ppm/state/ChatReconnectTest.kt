package com.hienlh.ppm.state

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.protocol.ChatClientMessage
import com.hienlh.ppm.protocol.SessionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A chat whose socket dropped (`ws-client.ts`): it stays usable, what is sent shows at once and
 * goes out after `ready` on the next connection, and nothing is sent twice.
 */
class ChatReconnectTest {
    /** Every chat socket the server accepted, in order, with what each one was sent. */
    private class Server : Dispatcher() {
        val sockets = LinkedBlockingQueue<WebSocket>()
        val received = List(3) { LinkedBlockingQueue<String>() }
        private var upgrades = 0

        override fun dispatch(request: RecordedRequest): MockResponse = when {
            request.headers["Upgrade"].equals("websocket", ignoreCase = true) -> {
                val frames = received[upgrades++]
                MockResponse.Builder().webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            sockets.put(webSocket)
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            frames.put(text)
                        }

                        // Answers the client's close, or the server waits for this socket forever.
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(1000, null)
                        }
                    },
                ).build()
            }
            request.target.contains("/messages") -> MockResponse.Builder().body("""{"ok":true,"data":{"messages":[],"start":0,"total":0}}""").build()
            else -> MockResponse.Builder().code(404).build()
        }
    }

    private fun withServer(block: suspend kotlinx.coroutines.CoroutineScope.(Server, ApiClient) -> Unit) = runBlocking {
        val dispatcher = Server()
        val server = MockWebServer()
        server.dispatcher = dispatcher
        server.start()
        try {
            block(dispatcher, ApiClient(server.url("/"), "t"))
        } finally {
            server.close()
        }
    }

    /** Off the test's thread, which is the one the controller runs on. */
    private suspend fun <T> LinkedBlockingQueue<T>.next(): T? = withContext(Dispatchers.IO) { poll(3, TimeUnit.SECONDS) }

    private suspend fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            delay(10)
        }
    }

    private val ready = ChatClientMessage.Ready.json.toString()

    private fun field(frame: String?, name: String) = frame?.let { Json.parseToJsonElement(it).jsonObject[name]?.jsonPrimitive?.content }

    @Test
    fun `a message sent while the socket is down shows at once and goes out after ready, once`() = withServer { server, api ->
        val chat = ChatController(api, "p", SessionInfo(id = "s-1", providerId = "mock", title = ""), this)
        chat.start()
        val first = server.sockets.next() ?: return@withServer fail("no socket")
        waitFor("the socket") { chat.state.value.connected }
        assertEquals(ready, server.received[0].next())

        first.close(1001, "going away")
        waitFor("the drop") { chat.state.value.connection is ChatController.Connection.Reconnecting }
        chat.send("sent while down")
        chat.setModel("opus")
        // On screen before the socket is back.
        assertEquals("sent while down", chat.state.value.items.last().text)

        assertTrue(server.sockets.next() != null)
        waitFor("the socket again") { chat.state.value.connected }
        assertEquals(ready, server.received[1].next())
        val message = server.received[1].next()
        assertEquals("message" to "sent while down", field(message, "type") to field(message, "content"))
        assertEquals("opus", field(server.received[1].next(), "model"))
        // Nothing went out on the dropped socket, and nothing is left to send again.
        assertNull(server.received[0].poll())
        assertNull(withContext(Dispatchers.IO) { server.received[1].poll(300, TimeUnit.MILLISECONDS) })
        chat.stop()
    }

    @Test
    fun `coming back to a silent socket replaces it, and what is sent meanwhile waits for the new one`() = withServer { server, api ->
        var now = 1_000_000L
        val chat = ChatController(api, "p", SessionInfo(id = "s-1", providerId = "mock", title = ""), this, clock = { now })
        chat.start()
        server.sockets.next() ?: return@withServer fail("no socket")
        waitFor("the socket") { chat.state.value.connected }
        assertEquals(ready, server.received[0].next())

        // Heard from a moment ago: kept.
        chat.resumed()
        assertNull(withContext(Dispatchers.IO) { server.sockets.poll(300, TimeUnit.MILLISECONDS) })

        now += ChatController.SILENCE_LIMIT_MS + 1
        chat.resumed()
        chat.send("after the silence")
        assertTrue(server.sockets.next() != null)
        waitFor("the new socket") { chat.state.value.connected }
        assertEquals(ready, server.received[1].next())
        assertEquals("after the silence", field(server.received[1].next(), "content"))
        assertNull(server.received[0].poll())
        chat.stop()
    }

    @Test
    fun `stopping drops what was waiting for the socket`() = withServer { server, api ->
        val chat = ChatController(api, "p", SessionInfo(id = "s-1", providerId = "mock", title = ""), this)
        chat.start()
        val first = server.sockets.next() ?: return@withServer fail("no socket")
        waitFor("the socket") { chat.state.value.connected }
        first.close(1001, "going away")
        waitFor("the drop") { chat.state.value.connection is ChatController.Connection.Reconnecting }
        chat.send("never sent")
        chat.stop()
        assertNull(withContext(Dispatchers.IO) { server.sockets.poll(600, TimeUnit.MILLISECONDS) })
    }
}
