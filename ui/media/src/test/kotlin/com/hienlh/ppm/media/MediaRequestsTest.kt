package com.hienlh.ppm.media

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.rawMediaUrl
import com.hienlh.ppm.client.transcodeMediaUrl
import com.hienlh.ppm.state.media.MediaKind
import com.hienlh.ppm.state.media.MediaSource
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MediaRequestsTest {
    @Test fun `only selected server media endpoints receive bearer auth and URI never holds token`() {
        val api = ApiClient("https://host.test/root/".toHttpUrl(), "secret")
        val raw = mediaRequest(api, api.rawMediaUrl("project #", "clip &.mp4"))
        assertEquals("Bearer secret", raw.header("Authorization"))
        assertNull(raw.url.queryParameter("token"))
        assertEquals("clip &.mp4", raw.url.queryParameter("path"))
        assertEquals("/root/api/project/project%20%23/files/raw", raw.url.encodedPath)
        val transcoded = mediaRequest(api, api.transcodeMediaUrl(null, "/tmp/movie.mkv", 12.5, "session_1"))
        assertEquals("12.500", transcoded.url.queryParameter("start"))
        assertEquals("session_1", transcoded.url.queryParameter("sid"))
        assertNull(transcoded.url.queryParameter("token"))
        val forbidden = listOf("http://host.test/root/api/fs/raw", "https://other.test/root/api/fs/raw", "https://host.test:444/root/api/fs/raw",
            "https://host.test/api/fs/raw", "https://host.test/root/api/fs/read", "https://user@host.test/root/api/fs/raw", "https://host.test/root/api/fs/raw#bad")
        forbidden.forEach { url -> assertThrows(IllegalArgumentException::class.java) { mediaRequest(api, url.toHttpUrl()) } }
    }

    @Test fun `compatibility transport removes Range and refuses redirect without forwarding credentials`() {
        val seen = AtomicReference<com.sun.net.httpserver.Headers>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var redirected = false
        server.createContext("/api/fs/raw") { exchange ->
            seen.set(exchange.requestHeaders)
            exchange.responseHeaders.add("Location", "/leak")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        server.createContext("/leak") { exchange -> redirected = true; exchange.sendResponseHeaders(200, -1); exchange.close() }
        server.start()
        try {
            val api = ApiClient("http://127.0.0.1:${server.address.port}/".toHttpUrl(), "wire-secret")
            val request = mediaRequest(api, api.rawMediaUrl(null, "/movie.mp4")).newBuilder().header("Range", "bytes=2-5").build()
            mediaHttp.newCall(request).execute().use { assertEquals(302, it.code) }
            assertNull(seen.get().getFirst("Range"))
            assertEquals("identity", seen.get().getFirst("Accept-Encoding"))
            assertEquals("Bearer wire-secret", seen.get().getFirst("Authorization"))
            assertFalse(redirected)
        } finally { server.stop(0) }
    }

    @Test fun `download streams exact bytes and HTTP failures never copy error documents`() = runBlocking {
        val bytes = ByteArray(200_123) { (it % 251).toByte() }
        val requestPath = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/fs/raw") { exchange ->
            requestPath.set(exchange.requestURI.toString())
            assertEquals("Bearer download-secret", exchange.requestHeaders.getFirst("Authorization"))
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/project/demo/files/raw") { exchange -> exchange.sendResponseHeaders(503, 5); exchange.responseBody.use { it.write("error".toByteArray()) } }
        server.start()
        try {
            val api = ApiClient("http://127.0.0.1:${server.address.port}/".toHttpUrl(), "download-secret")
            val output = ByteArrayOutputStream()
            downloadMedia(api, MediaSource(null, "/song.mp3", MediaKind.AUDIO), output)
            assertArrayEquals(bytes, output.toByteArray())
            assertFalse(requestPath.get().contains("token"))
            val failed = ByteArrayOutputStream()
            try { downloadMedia(api, MediaSource("demo", "movie.mp4"), failed); fail("expected HTTP error") }
            catch (error: java.io.IOException) { assertEquals("Download failed (HTTP 503).", error.message) }
            assertEquals(0, failed.size())
        } finally { server.stop(0) }
    }

    @Test fun `cancellation interrupts a blocked stream instead of waiting for read timeout`() = runBlocking {
        val streaming = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/fs/raw") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write(42); exchange.responseBody.flush(); streaming.countDown()
                release.await(5, TimeUnit.SECONDS)
            } finally { exchange.close() }
        }
        server.start()
        try {
            val api = ApiClient("http://127.0.0.1:${server.address.port}/".toHttpUrl(), "secret")
            val job = launch(Dispatchers.Default) { downloadMedia(api, MediaSource(null, "/song.mp3"), ByteArrayOutputStream()) }
            assertTrue(streaming.await(3, TimeUnit.SECONDS))
            withTimeout(2_000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        } finally { release.countDown(); server.stop(0) }
    }

    @Test fun `only the server's own refusals, the 4xx, are final`() {
        for (status in listOf(400, 401, 403, 404, 410, 416, 429, 499)) assertTrue("status " + status, isClientError(status))
        for (status in listOf(null, 200, 206, 301, 399, 500, 502, 503, 504)) assertFalse("status " + status, isClientError(status))
    }
}
