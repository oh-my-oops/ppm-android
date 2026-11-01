package com.hienlh.ppm.state.media

import com.hienlh.ppm.client.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class MediaPlaybackWireTest {
    @Test fun realClientLifecycleProbesAndDeletesWithoutPreflightStreamGet() = runBlocking {
        val server = MockWebServer()
        fun answer(data: String) = server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json")
            .body("""{"ok":true,"data":""" + data + "}").build())
        answer("""{"transcodable":true,"duration":50}""")
        answer("""{"stopped":false}""")
        answer("""{"transcodable":true,"duration":60}""")
        answer("""{"stopped":true}""")
        server.start()
        val parent = CoroutineScope(coroutineContext + SupervisorJob())
        val media = MediaPlaybackController(ApiClient(server.url("/prefix/"), "credential"), parent)
        suspend fun playback() = withTimeout(3_000) { media.state.first { it.playback != null }.playback!! }
        suspend fun request() = withContext(Dispatchers.IO) { checkNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
        try {
            media.setSource(MediaSource("my app", "first.mkv")); media.show()
            val first = playback()
            assertEquals("/prefix/api/project/my%20app/files/probe?path=first.mkv", request().target)
            assertEquals("credential", first.url.queryParameter("token"))
            media.hide(); media.awaitCleanup()
            val stopFirst = request()
            assertEquals("DELETE", stopFirst.method)
            assertEquals("/prefix/api/project/my%20app/files/transcode?sid=" + first.sessionId, stopFirst.target)
            assertEquals("Bearer credential", stopFirst.headers["Authorization"])

            media.setSource(MediaSource(null, "/external/second.mkv")); media.show()
            val second = playback()
            assertEquals("/prefix/api/fs/probe?path=%2Fexternal%2Fsecond.mkv", request().target)
            assertNotEquals(first.sessionId, second.sessionId)
            parent.cancel()
            withTimeout(3_000) { media.state.first { !it.active } }
            media.awaitCleanup()
            val stopSecond = request()
            assertEquals("DELETE", stopSecond.method)
            assertEquals("/prefix/api/fs/transcode?sid=" + second.sessionId, stopSecond.target)
            assertEquals(4, server.requestCount) // A player, not core, opens the generated stream URL.
            assertNull(media.state.value.cleanupError)
            assertFalse(first.toString().contains("credential"))
        } finally {
            media.close(); media.awaitCleanup(); parent.cancel()
            withContext(Dispatchers.IO) { server.close() }
        }
    }
}
