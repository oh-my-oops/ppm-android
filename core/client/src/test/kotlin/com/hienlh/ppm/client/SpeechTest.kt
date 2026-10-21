package com.hienlh.ppm.client

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** The `/api/speech` routes (routes/speech.ts) as the mic and Settings → Voice call them. */
class SpeechTest {
    private fun <T> withServer(vararg responses: MockResponse, block: suspend (MockWebServer, ApiClient) -> T): T = runBlocking {
        val server = MockWebServer()
        responses.forEach(server::enqueue)
        server.start()
        try {
            block(server, ApiClient(server.url("/"), "t"))
        } finally {
            server.close()
        }
    }

    @Test
    fun `a recording is posted as audio-wav in Vietnamese, and what was heard comes back`() = withServer(
        MockResponse.Builder().body("""{"ok":true,"data":{"text":"xin chào","ms":812}}""").build(),
        MockResponse.Builder().code(409).body("""{"ok":false,"error":"Whisper is not installed on this host"}""").build(),
    ) { server, api ->
        val wav = byteArrayOf(82, 73, 70, 70, 0, 1)
        assertEquals("xin chào", api.transcribe(wav))
        val post = server.takeRequest()
        assertEquals("POST /api/speech/transcribe?lang=vi", "${post.method} ${post.target}")
        assertEquals("audio/wav", post.headers["Content-Type"])
        assertEquals("Bearer t", post.headers["Authorization"])
        assertArrayEquals(wav, post.body?.toByteArray())
        try {
            api.transcribe(wav)
            fail("409 must throw")
        } catch (error: PpmError.Server) {
            assertEquals("Whisper is not installed on this host", error.message)
        }
    }

    @Test
    fun `an install names its model, and the status follows it`() = withServer(
        MockResponse.Builder().body("""{"ok":true,"data":{"modelId":"small-q5_1","phase":"binary","receivedBytes":0,"totalBytes":0,"done":false,"error":null}}""").build(),
        MockResponse.Builder().body("""{"ok":true,"data":{"installable":true,"version":"1.9.4","ready":false,"models":[],"install":{"modelId":"small-q5_1","phase":"model","receivedBytes":1,"totalBytes":4,"done":false,"error":null}}}""").build(),
        MockResponse.Builder().body("""{"ok":true,"data":{"installable":true,"version":"1.9.4","ready":false,"models":[],"install":null}}""").build(),
    ) { server, api ->
        api.installSpeech("small-q5_1")
        val install = server.takeRequest()
        assertEquals("POST /api/speech/install", "${install.method} ${install.target}")
        assertEquals("""{"model":"small-q5_1"}""", install.body?.utf8())
        assertEquals(25, api.speechStatus().install?.percent)
        assertEquals("GET /api/speech/status", server.takeRequest().let { "${it.method} ${it.target}" })
        api.uninstallSpeech()
        assertEquals("POST /api/speech/uninstall", server.takeRequest().let { "${it.method} ${it.target}" })
    }
}
