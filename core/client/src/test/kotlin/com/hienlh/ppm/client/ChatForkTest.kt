package com.hienlh.ppm.client

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

/** `POST …/sessions/:id/fork` as `chat-tab.tsx` calls it for Fork and for Edit. */
class ChatForkTest {
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

    private fun forked(id: String) = MockResponse.Builder().code(201).body(
        """{"ok":true,"data":{"id":"$id","providerId":"codex","title":"Hello","projectName":"my app",
            "projectPath":"/p","createdAt":"2026-10-25T08:00:00.000Z","forkedFrom":"s 1"}}""",
    ).build()

    @Test
    fun `a fork names the message it continues from, an edit says so, the first message names none`() = withServer(
        forked("s-2"),
        forked("s-3"),
        forked("s-4"),
    ) { server, api ->
        val fork = api.forkSession("my app", "s 1", "codex", messageId = "uuid-9", edit = false)
        assertEquals(listOf("s-2", "codex", "Hello"), listOf(fork.id, fork.providerId, fork.title))
        val first = server.takeRequest()
        assertEquals("POST /api/project/my%20app/chat/sessions/s%201/fork?providerId=codex", "${first.method} ${first.target}")
        assertEquals("""{"messageId":"uuid-9"}""", first.body?.utf8())
        assertEquals("s-3", api.forkSession("my app", "s 1", "codex", messageId = "uuid-9", edit = true).id)
        assertEquals("/api/project/my%20app/chat/sessions/s%201/fork?providerId=codex&mode=edit", server.takeRequest().target)
        api.forkSession("my app", "s 1", "claude", messageId = null, edit = false)
        val fresh = server.takeRequest()
        assertEquals("/api/project/my%20app/chat/sessions/s%201/fork?providerId=claude", fresh.target)
        assertEquals("{}", fresh.body?.utf8())
    }

    @Test
    fun `a fork the server refuses fails with its message`() = withServer(
        MockResponse.Builder().code(400).body("""{"ok":false,"error":"Cannot fork at message: No message found with uuid x"}""").build(),
    ) { _, api ->
        val error = try {
            api.forkSession("p", "s", "claude", messageId = "x", edit = false)
            null
        } catch (error: PpmError) {
            error
        }
        assertEquals(PpmError.Server(400, "Cannot fork at message: No message found with uuid x"), error)
    }
}
