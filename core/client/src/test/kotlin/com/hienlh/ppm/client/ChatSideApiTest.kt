package com.hienlh.ppm.client

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The context meter's breakdown and `/btw`'s side question (`GET …/context-usage`, `POST …/side-question`). */
class ChatSideApiTest {
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
    fun `the breakdown is asked of the session, and a session with no live Claude has none`() = withServer(
        MockResponse.Builder().body(
            """{"ok":true,"data":{"usage":{"model":"opus","percentage":42,"totalTokens":84000,"maxTokens":200000,
                "rows":[{"id":"messages","name":"Messages","tokens":60000,"kind":"used"}]}}}""",
        ).build(),
        MockResponse.Builder().body("""{"ok":true,"data":{"usage":null}}""").build(),
    ) { server, api ->
        val usage = api.contextUsage("my app", "s/1")!!
        assertEquals(42, usage.percentage)
        assertEquals("Messages", usage.rows.single().name)
        assertEquals(
            "GET /api/project/my%20app/chat/sessions/s%2F1/context-usage?detail=full",
            server.takeRequest().let { "${it.method} ${it.target}" },
        )
        assertNull(api.contextUsage("p", "s2", detail = "summary"))
        assertEquals("/api/project/p/chat/sessions/s2/context-usage?detail=summary", server.takeRequest().target)
    }

    @Test
    fun `a side question posts the question and hands back the answer`() = withServer(
        MockResponse.Builder().body("""{"ok":true,"data":{"response":"It is in src/app.ts."}}""").build(),
        MockResponse.Builder().body("""{"ok":true,"data":{"response":null}}""").build(),
    ) { server, api ->
        assertEquals("It is in src/app.ts.", api.askSideQuestion("p", "s1", "where is the app?"))
        val request = server.takeRequest()
        assertEquals("POST /api/project/p/chat/sessions/s1/side-question", "${request.method} ${request.target}")
        assertEquals("""{"question":"where is the app?"}""", request.body?.utf8())
        assertNull(api.askSideQuestion("p", "s1", "anything?"))
    }

    @Test
    fun `no live Claude process is the server's own words`() = withServer(
        MockResponse.Builder().code(409).body(
            """{"ok":false,"error":"This chat's Claude process is not running. Send a message first, then ask with /btw."}""",
        ).build(),
    ) { _, api ->
        try {
            api.askSideQuestion("p", "s1", "q")
            fail("expected a refusal")
        } catch (e: PpmError.Server) {
            assertEquals(409, e.status)
            assertTrue(e.message!!.startsWith("This chat's Claude process is not running."))
        }
    }
}
