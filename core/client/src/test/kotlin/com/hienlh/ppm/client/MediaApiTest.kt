package com.hienlh.ppm.client

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class MediaApiTest {
    private fun ok(data: String) = MockResponse.Builder().addHeader("Content-Type", "application/json")
        .body("""{"ok":true,"data":""" + data + "}").build()
    private fun withServer(vararg responses: MockResponse, block: suspend (MockWebServer, ApiClient) -> Unit) = runBlocking {
        val server = MockWebServer()
        responses.forEach(server::enqueue)
        server.start()
        try { block(server, ApiClient(server.url("/"), "secret")) } finally { server.close() }
    }

    @Test fun rawUrlEscapesProjectPathAndCredential() {
        val api = ApiClient("https://example.test/ppm/".toHttpUrl(), "a +&?#雪")
        val relative = api.rawMediaUrl("my project/a", "video +?#雪.mp4")
        assertEquals("/ppm/api/project/my%20project%2Fa/files/raw", relative.encodedPath)
        assertEquals("video +?#雪.mp4", relative.queryParameter("path"))
        assertEquals("a +&?#雪", relative.queryParameter("token"))
        for (path in listOf("/tmp/a b.mov", "C:\\video\\a.mp4", "D:/a.wav")) {
            val url = api.rawMediaUrl(null, path)
            assertEquals("/ppm/api/fs/raw", url.encodedPath)
            assertEquals(path, url.queryParameter("path"))
        }
        assertNull(ApiClient(api.baseUrl, "").rawMediaUrl("p", "a").queryParameter("token"))
        try { api.rawMediaUrl(null, "relative.mp4"); fail("relative needs project") } catch (_: IllegalArgumentException) { }
    }

    @Test fun transcodePrecisionAndSessionValidation() {
        val api = ApiClient("https://example.test".toHttpUrl(), "token")
        val locale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            val url = api.transcodeMediaUrl("p", "v.mkv", 12.3456, "sid_1-A")
            assertEquals("12.346", url.queryParameter("start"))
            assertEquals("sid_1-A", url.queryParameter("sid"))
            assertEquals("token", url.queryParameter("token"))
            assertNull(api.transcodeMediaUrl("p", "v.mkv", sessionId = "a").queryParameter("start"))
        } finally { Locale.setDefault(locale) }
        for (sid in listOf("", "x".repeat(65), "a/b", "a b", "a\n", "雪")) {
            assertFalse(isValidMediaSessionId(sid))
            try { api.transcodeMediaUrl("p", "v", sessionId = sid); fail("invalid sid") } catch (_: IllegalArgumentException) { }
        }
        assertTrue(isValidMediaSessionId("a".repeat(64)))
        assertEquals(100, List(100) { newMediaSessionId().also { assertTrue(isValidMediaSessionId(it)) } }.toSet().size)
        for (start in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            try { api.transcodeMediaUrl("p", "v", start, "sid"); fail("invalid start") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun probePreservesNullableFactsAndUnknownFields() = withServer(
        ok("""{"transcodable":true,"encoder":"libx264","duration":42.25,"container":"matroska","video":{"codec":"hevc","width":1920,"height":1080},"audio":{"codec":"ac3"},"future":1}"""),
        ok("""{"transcodable":false,"encoder":null,"duration":null,"container":null,"video":null,"audio":null}"""),
        ok("""{"transcodable":true,"duration":-1,"video":{"codec":"x","width":-1,"height":4294967296},"audio":{}}"""),
    ) { server, api ->
        val info = api.probeMedia("my app", "a.mkv")
        assertTrue(info.transcodable)
        assertEquals(42.25, info.duration!!, 0.0)
        val video = info.video!!
        assertEquals("hevc", video.codec)
        assertEquals(1920, video.width)
        assertEquals("ac3", info.audio!!.codec)
        val request = server.takeRequest()
        assertEquals("/api/project/my%20app/files/probe?path=a.mkv", request.target)
        assertEquals("Bearer secret", request.headers["Authorization"])
        val unknown = api.probeMedia(null, "/tmp/unknown")
        assertFalse(unknown.transcodable)
        assertNull(unknown.duration); assertNull(unknown.video); assertNull(unknown.audio)
        assertEquals("/api/fs/probe?path=%2Ftmp%2Funknown", server.takeRequest().target)
        val invalid = api.probeMedia("p", "a")
        val invalidVideo = invalid.video!!
        assertNull(invalid.duration); assertNull(invalidVideo.width); assertNull(invalidVideo.height); assertNull(invalid.audio)
    }

    @Test fun cleanupUsesDeleteBearerAndOriginalRoute() = withServer(
        ok("""{"stopped":true}"""), ok("""{"stopped":false}"""),
    ) { server, api ->
        assertTrue(api.stopMediaTranscode("my app", "v.mkv", "sid_1"))
        val project = server.takeRequest()
        assertEquals("DELETE", project.method)
        assertEquals("/api/project/my%20app/files/transcode?sid=sid_1", project.target)
        assertEquals("Bearer secret", project.headers["Authorization"])
        assertFalse(project.target!!.contains("token="))
        assertFalse(api.stopMediaTranscode(null, "C:\\v.mkv", "sid_2"))
        assertEquals("/api/fs/transcode?sid=sid_2", server.takeRequest().target)
        try { api.stopMediaTranscode("p", "v", ""); fail("invalid stop") } catch (_: IllegalArgumentException) { }
        assertEquals(2, server.requestCount)
    }

    @Test fun malformedProbeAndStopAreErrors() = withServer(ok("{}"), ok("""{"stopped":"true"}""")) { _, api ->
        try { api.probeMedia("p", "v"); fail("malformed probe") } catch (_: PpmError.BadResponse) { }
        try { api.stopMediaTranscode("p", "v", "sid"); fail("malformed stop") } catch (_: PpmError.BadResponse) { }
    }

    @Test fun httpErrorsKeepStatus() = withServer(
        MockResponse.Builder().code(501).addHeader("Content-Type", "application/json").body("""{"ok":false,"error":"No encoder"}""").build(),
        MockResponse.Builder().code(503).addHeader("Content-Type", "application/json").body("""{"ok":false,"error":"Busy"}""").build(),
    ) { _, api ->
        try { api.probeMedia("p", "v"); fail("501") }
        catch (e: PpmError.Server) { assertEquals(501, e.status); assertEquals("No encoder", e.message) }
        try { api.stopMediaTranscode("p", "v", "sid"); fail("503") }
        catch (e: PpmError.Server) { assertEquals(503, e.status); assertEquals("Busy", e.message) }
    }
}
