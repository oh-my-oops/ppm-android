package com.hienlh.ppm.state

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.protocol.SessionInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

/** A chat with no session yet (`useChat(null, …)`): nothing to load or connect until its first message makes one. */
class NewChatControllerTest {
    private fun withServer(vararg responses: MockResponse, block: suspend CoroutineScope.(MockWebServer, ApiClient) -> Unit) = runBlocking {
        val server = MockWebServer()
        responses.forEach(server::enqueue)
        server.start()
        try {
            block(server, ApiClient(server.url("/"), "t"))
        } finally {
            server.close()
        }
    }

    /** Waits off the test's thread, which is the one the controller runs on. */
    private suspend fun MockWebServer.next(): RecordedRequest = withContext(Dispatchers.IO) { takeRequest(2, TimeUnit.SECONDS)!! }

    private val emptyHistory = MockResponse.Builder().body("""{"ok":true,"data":{"messages":[],"start":0,"total":0}}""").build()

    @Test
    fun `a new chat asks nothing, and may still change provider, until it adopts its session`() = withServer(emptyHistory) { server, api ->
        val chat = ChatController(api, "p", null, this, providerId = "mock")
        chat.start()
        chat.reload()
        chat.loadEarlier()
        delay(100)
        assertEquals(0, server.requestCount)
        assertNull(chat.state.value.sessionId)
        assertEquals("mock", chat.state.value.providerId)
        // Picks made before the session ride on its first message.
        chat.setModel("opus")
        chat.chooseProvider("codex")
        assertEquals("codex", chat.state.value.providerId)
        assertEquals("opus", chat.state.value.model)

        chat.adopt(SessionInfo(id = "s-1", providerId = "codex", title = "Hello"))
        val history = server.next()
        assertEquals("GET /api/project/p/chat/sessions/s-1/messages?providerId=codex&limit=50", "${history.method} ${history.target}")
        assertEquals("s-1", chat.state.value.sessionId)
        // A session keeps its own provider, and is adopted once.
        chat.chooseProvider("claude")
        chat.adopt(SessionInfo(id = "s-2", providerId = "claude", title = ""))
        assertEquals("codex", chat.state.value.providerId)
        assertEquals("s-1", chat.state.value.sessionId)
        chat.stop()
    }
}
