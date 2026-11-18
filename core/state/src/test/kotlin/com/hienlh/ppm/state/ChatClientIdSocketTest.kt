package com.hienlh.ppm.state

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.ChatClientId
import com.hienlh.ppm.protocol.SessionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The chat socket names its tab with one `?clientId=` for the tab's life (`chat-client-id.ts`). */
class ChatClientIdSocketTest {
    private class Server : Dispatcher() {
        val sockets = LinkedBlockingQueue<WebSocket>()
        val clientIds = LinkedBlockingQueue<String>()

        override fun dispatch(request: RecordedRequest): MockResponse = when {
            request.headers["Upgrade"].equals("websocket", ignoreCase = true) -> {
                clientIds.put(request.url.queryParameter("clientId") ?: "<none>")
                MockResponse.Builder().webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            sockets.put(webSocket)
                        }

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

    private suspend fun <T> LinkedBlockingQueue<T>.next(): T? = withContext(Dispatchers.IO) { poll(3, TimeUnit.SECONDS) }

    private suspend fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            delay(10)
        }
    }

    @Test
    fun `every connection of one chat carries the same client id, a reconnect included`() = runBlocking {
        val server = Server()
        val web = MockWebServer()
        web.dispatcher = server
        web.start()
        try {
            val chat = ChatController(ApiClient(web.url("/"), "t"), "p", SessionInfo(id = "s-1", providerId = "mock", title = ""), this)
            assertTrue(ChatClientId.isValid(chat.clientId))
            chat.start()
            assertEquals(chat.clientId, server.clientIds.next())
            val first = server.sockets.next() ?: return@runBlocking fail("no socket")
            waitFor("the socket") { chat.state.value.connected }

            first.close(1001, "going away")
            waitFor("the drop") { chat.state.value.connection is ChatController.Connection.Reconnecting }
            assertEquals(chat.clientId, server.clientIds.next())
            waitFor("the socket again") { chat.state.value.connected }
            chat.stop()

            // Another tab's controller gets an id of its own; a given one is kept.
            val other = ChatController(ApiClient(web.url("/"), "t"), "p", SessionInfo(id = "s-1", providerId = "mock", title = ""), this)
            assertTrue(other.clientId != chat.clientId)
            other.stop()
            val given = ChatClientId.mint()
            val handed = ChatController(ApiClient(web.url("/"), "t"), "p", SessionInfo(id = "s-1", providerId = "mock", title = ""), this, clientId = given)
            assertEquals(given, handed.clientId)
            handed.stop()
        } finally {
            web.close()
        }
    }
}
