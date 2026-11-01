package com.hienlh.ppm.state

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Real `/ws/global` transport: release/reconnect watch frames and the lazy file-index wiring. */
class GlobalEventsSocketTest {
    private data class Peer(val socket: WebSocket, val frames: LinkedBlockingQueue<String>)

    private class Server : Dispatcher() {
        val peers = LinkedBlockingQueue<Peer>()
        val upgrades = LinkedBlockingQueue<RecordedRequest>()
        val indexRequests = LinkedBlockingQueue<RecordedRequest>()

        override fun dispatch(request: RecordedRequest): MockResponse = when {
            request.headers["Upgrade"].equals("websocket", ignoreCase = true) -> {
                upgrades.put(request)
                val frames = LinkedBlockingQueue<String>()
                MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        peers.put(Peer(webSocket, frames))
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) { frames.put(text) }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                }).build()
            }
            request.url.encodedPath.endsWith("/files/index") -> {
                indexRequests.put(request)
                MockResponse.Builder().body("""{"ok":true,"data":[{"path":"src/a.ts","name":"a.ts","type":"file"}]}""").build()
            }
            request.url.encodedPath == "/api/chat/sessions/running" ||
                request.url.encodedPath.endsWith("/chat/sessions/unread") ->
                MockResponse.Builder().body("""{"ok":true,"data":[]}""").build()
            else -> MockResponse.Builder().code(404).body("""{"ok":false,"error":"Unexpected test route"}""").build()
        }
    }

    private fun withServer(block: suspend CoroutineScope.(Server, ApiClient) -> Unit) = runBlocking {
        val dispatcher = Server()
        val server = MockWebServer()
        server.dispatcher = dispatcher
        server.start()
        try {
            block(dispatcher, ApiClient(server.url("/"), "test token +/?"))
        } finally {
            server.close()
        }
    }

    private suspend fun <T> LinkedBlockingQueue<T>.next(): T = withContext(Dispatchers.IO) {
        checkNotNull(poll(3, TimeUnit.SECONDS)) { "Timed out waiting for socket traffic" }
    }

    private suspend fun <T> LinkedBlockingQueue<T>.quiet(): T? = withContext(Dispatchers.IO) {
        poll(200, TimeUnit.MILLISECONDS)
    }

    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(3_000) {
        while (!condition()) delay(10)
    }

    private suspend fun Peer.expectWatch(project: String) {
        val frame = WireJson.parse(frames.next())
        assertEquals("watch", frame["type"].string)
        assertEquals(project, frame["projectName"].string)
    }

    @Test
    fun `switching project to null sends an explicit empty watch and reconnect keeps it released`() = withServer { server, api ->
        val events = GlobalEvents(api, this)
        try {
            events.project = "project A /雪"
            events.start()
            val first = server.peers.next()
            val upgrade = server.upgrades.next()
            assertEquals("/ws/global", upgrade.url.encodedPath)
            assertEquals("test token +/?", upgrade.url.queryParameter("token"))
            assertFalse(events.isConnected.value)
            assertNull(first.frames.quiet())

            first.socket.send("""{"type":"global_ready"}""")
            first.expectWatch("project A /雪")
            assertTrue(events.isConnected.value)
            events.project = null
            first.expectWatch("") // Not omitted/null: this is what releases setWatch's previous ref.
            events.project = null
            assertNull(first.frames.quiet())

            first.socket.close(1001, "reconnect")
            waitFor { !events.isConnected.value }
            val next = server.peers.next() // Exercise the real one-second reconnect, not another start().
            assertNull(next.frames.quiet())
            next.socket.send("""{"type":"global_ready"}""")
            next.expectWatch("")
            events.project = "project B"
            next.expectWatch("project B")
            assertNull(next.frames.quiet())
        } finally {
            events.stop()
        }
    }

    @Test
    fun `reconnect waits for global ready and watches the latest project selected while disconnected`() = withServer { server, api ->
        val events = GlobalEvents(api, this)
        try {
            events.project = "old"
            events.start()
            val first = server.peers.next()
            first.socket.send("""{"type":"global_ready"}""")
            first.expectWatch("old")
            first.socket.close(1001, "network changed")
            waitFor { !events.isConnected.value }
            events.project = null
            events.project = "next /雪"
            val next = server.peers.next()
            events.project = "latest"
            assertFalse(events.isConnected.value)
            assertNull(next.frames.quiet())
            next.socket.send("""{"type":"global_ready"}""")
            next.expectWatch("latest")
            assertNull(next.frames.quiet())

            // The same selection must also be restored on an otherwise uneventful reconnect.
            next.socket.close(1001, "network changed again")
            waitFor { !events.isConnected.value }
            val third = server.peers.next()
            third.socket.send("""{"type":"global_ready"}""")
            third.expectWatch("latest")
        } finally {
            events.stop()
        }
    }

    @Test
    fun `file changed carries its optional path but never refetches the index even with an open reader`() = withServer { server, api ->
        val events = GlobalEvents(api, this)
        val index = FileIndexStore(api, this)
        val changed = mutableListOf<Pair<String, String?>>()
        val rebuilt = mutableListOf<String>()
        // The AppModel wiring: only a completed index rebuild may refresh an open picker.
        events.onFileChanged = { project, path -> changed += project to path; index.markStale(project) }
        events.onIndexChanged = { project -> rebuilt += project; index.rebuilt(project) }
        var closeReader: (() -> Unit)? = null
        try {
            index.load("app").join()
            val request = server.indexRequests.next()
            assertEquals("50000", request.url.queryParameter("max"))
            assertEquals(FileIndexStore.Status.READY, index.state.value.status)
            val original = index.state.value
            closeReader = index.openReader("app")
            events.project = "app"
            events.start()
            val peer = server.peers.next()
            peer.socket.send("""{"type":"global_ready"}""")
            peer.expectWatch("app")

            peer.socket.send("""{"type":"file:changed","projectName":"other","path":"other.ts"}""")
            waitFor { changed.size == 1 }
            assertFalse(index.state.value.stale)
            peer.socket.send("""{"type":"file:changed","projectName":"app","path":"src/a.ts"}""")
            peer.socket.send("""{"type":"file:changed","projectName":"app"}""")
            peer.socket.send("""{"type":"file:changed","projectName":"app","path":7}""")
            peer.socket.send("""{"type":"file:changed","path":"no-project.ts"}""")
            // Following frame is a processing barrier for the malformed/no-project event too.
            peer.socket.send("""{"type":"file:changed","projectName":"app","path":"last.ts"}""")
            waitFor { changed.size == 5 }
            assertEquals(listOf("other" to "other.ts", "app" to "src/a.ts", "app" to null, "app" to null, "app" to "last.ts"), changed)
            assertTrue(index.state.value.stale)
            assertEquals(original.entries, index.state.value.entries)
            assertEquals(original.revision, index.state.value.revision)
            assertTrue(rebuilt.isEmpty())
            assertNull("file:changed downloaded the index", server.indexRequests.quiet())

            peer.socket.send("""{"type":"files:index-changed","projectName":"app"}""")
            server.indexRequests.next()
            waitFor { index.state.value.revision == original.revision + 1 }
            assertFalse(index.state.value.stale)
            assertEquals(listOf("app"), rebuilt)
            assertEquals(5, changed.size)

            closeReader.invoke()
            closeReader = null
            peer.socket.send("""{"type":"files:index-changed","projectName":"app"}""")
            waitFor { rebuilt.size == 2 }
            assertTrue(index.state.value.stale)
            assertNull("a closed picker downloaded the rebuilt index", server.indexRequests.quiet())
        } finally {
            closeReader?.invoke()
            events.stop()
        }
    }
}
