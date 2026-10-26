package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.ImageAudit
import com.hienlh.ppm.protocol.SessionDebugInfo
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The requests session-debug-dialog.tsx and the bell's Clear all make, and what their answers read as. */
class SessionDebugApiTest {
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

    private fun ok(data: String) = MockResponse.Builder().body("""{"ok":true,"data":$data}""").build()

    private val base = "/api/project/my%20app/chat/sessions/s1"
    private val audit = """{"total":3,"oversized":1,"bytes":2048,"oversizedBytes":1024,"largestSide":9000,"attachments":1,"oversizedAttachments":0,"limit":8000}"""

    @Test
    fun `debug facts, turn history and the image audit are read field by field`() = withServer(
        ok("""{"ppmSessionId":"s1","sdkSessionId":"s1","providerId":"claude","jsonlPath":"/h/.claude/p/s1.jsonl","projectPath":"/p","jsonlSizeBytes":1048576,"jsonlLines":42}"""),
        ok("""{"ppmSessionId":"s1","sdkSessionId":"s1","jsonlPath":null,"projectPath":"","jsonlSizeBytes":null,"jsonlLines":null}"""),
        ok("""{"turns":[
            {"id":7,"recordedAt":"2026-10-26 03:04:05","usage":{"model":"m","inputTokens":10,"outputTokens":5,"cacheReadTokens":900,"cacheWriteTokens":90,"contextWindow":0,"costUsd":0.0123,"cacheHitRate":0.9,"coldStart":true,"coldReason":"idle_timeout","accountId":"acc-1","accountLabel":"me@x"}},
            {"id":6,"recordedAt":"2026-10-26 03:00:00","usage":{"model":"m","inputTokens":1,"outputTokens":1,"cacheReadTokens":0,"cacheWriteTokens":0,"costUsd":0,"cacheHitRate":0,"coldStart":false}},
            {"id":5,"recordedAt":"x","usage":null}
        ]}"""),
        ok(audit),
    ) { server, api ->
        assertEquals(SessionDebugInfo("s1", "s1", "/h/.claude/p/s1.jsonl", "/p", 1_048_576, 42), api.sessionDebug("my app", "s1"))
        assertEquals("GET $base/debug?project=my%20app", server.takeRequest().let { "${it.method} ${it.target}" })
        assertEquals(SessionDebugInfo("s1", "s1", null, "", null, null), api.sessionDebug("my app", "s1"))
        server.takeRequest()

        val turns = api.sessionTurnUsage("my app", "s1")
        assertEquals("GET $base/usage?project=my%20app", server.takeRequest().let { "${it.method} ${it.target}" })
        assertEquals(listOf(7L, 6L), turns.map { it.id })
        val first = turns[0].usage
        assertEquals("2026-10-26 03:04:05", turns[0].recordedAt)
        assertEquals("acc-1" to "me@x", first.accountId to first.accountLabel)
        assertEquals(1000L to 100L, first.prefixTokens to first.uncachedPrefixTokens)
        assertEquals("idle_timeout", first.coldReason)
        assertNull(turns[1].usage.accountId)

        assertEquals(ImageAudit(3, 1, 2048, 1024, 9000, 1, 0, 8000), api.sessionImages("my app", "s1"))
        assertEquals("GET $base/images?project=my%20app", server.takeRequest().let { "${it.method} ${it.target}" })
    }

    @Test
    fun `strip sends its mode and the attachments switch, and Clear all sends every id at once`() = withServer(
        ok("""{"removed":2,"bytesFreed":4096,"remaining":$audit}"""),
        ok("""{"removed":0,"bytesFreed":0,"remaining":$audit}"""),
        ok("""{"cleared":2}"""),
    ) { server, api ->
        val stripped = api.stripSessionImages("my app", "s1", all = false, includeAttachments = true)
        assertEquals(2 to 4096L, stripped.removed to stripped.bytesFreed)
        assertEquals(3, stripped.remaining.total)
        server.takeRequest().let {
            assertEquals("POST $base/images/strip?project=my%20app", "${it.method} ${it.target}")
            assertEquals("""{"mode":"oversized","includeAttachments":true}""", it.body?.utf8())
        }
        api.stripSessionImages("my app", "s1", all = true, includeAttachments = false)
        assertEquals("""{"mode":"all","includeAttachments":false}""", server.takeRequest().body?.utf8())

        api.markAllRead(listOf("a", "b"))
        server.takeRequest().let {
            assertEquals("POST /api/chat/sessions/read", "${it.method} ${it.target}")
            assertEquals("""{"sessionIds":["a","b"]}""", it.body?.utf8())
        }
    }
}
