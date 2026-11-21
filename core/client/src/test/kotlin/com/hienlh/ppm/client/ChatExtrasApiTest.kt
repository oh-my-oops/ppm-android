package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.SessionBookmark
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.WireJson
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Archive, bookmarks and the Markdown export, as `use-session-history.ts`, `session-bookmarks-store.ts` and `chat-export.ts` call them. */
class ChatExtrasApiTest {
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

    @Test
    fun `archive puts the state, kept, or null to forget it`() = withServer(ok("{}"), ok("{}"), ok("{}")) { server, api ->
        api.setSessionArchive("my app", "s 1", "archived")
        val first = server.takeRequest()
        assertEquals("PUT /api/project/my%20app/chat/sessions/s%201/archive", "${first.method} ${first.target}")
        assertEquals("""{"state":"archived"}""", first.body?.utf8())
        api.setSessionArchive("my app", "s 1", "kept")
        assertEquals("""{"state":"kept"}""", server.takeRequest().body?.utf8())
        api.setSessionArchive("my app", "s 1", null)
        assertEquals("""{"state":null}""", server.takeRequest().body?.utf8())
    }

    @Test
    fun `a session reads its archive state and manager mark, anything else is none`() {
        val archived = SessionInfo.from(WireJson.parseOrNull("""{"id":"a","archive":"archived","manager":true}""")!!)!!
        assertEquals("archived", archived.archive)
        assertTrue(archived.manager)
        val odd = SessionInfo.from(WireJson.parseOrNull("""{"id":"b","archive":"maybe"}""")!!)!!
        assertNull(odd.archive)
        assertFalse(odd.manager)
    }

    @Test
    fun `bookmarks are listed, put with their turn number and label, and deleted`() = withServer(
        ok("""[{"turnId":"t-1","turnN":3,"label":"Fix it","createdAt":"2026-11-20T10:00:00.000Z"}]"""),
        ok("""[{"turnId":"t-1","turnN":3,"label":"Fix it","createdAt":"x"},{"turnId":"t-2","label":"Next","createdAt":"y"}]"""),
        ok("[]"),
    ) { server, api ->
        assertEquals(listOf(SessionBookmark("t-1", 3, "Fix it", "2026-11-20T10:00:00.000Z")), api.sessionBookmarks("p", "s"))
        assertEquals("GET /api/project/p/chat/sessions/s/bookmarks", server.takeRequest().let { "${it.method} ${it.target}" })
        val list = api.putSessionBookmark("p", "s", "t-2", null, "Next")
        assertEquals(listOf("t-1", "t-2"), list.map { it.turnId })
        val put = server.takeRequest()
        assertEquals("PUT /api/project/p/chat/sessions/s/bookmarks/t-2", "${put.method} ${put.target}")
        assertEquals("""{"label":"Next"}""", put.body?.utf8())
        assertEquals(emptyList<SessionBookmark>(), api.deleteSessionBookmark("p", "s", "t-1"))
        assertEquals("DELETE /api/project/p/chat/sessions/s/bookmarks/t-1", server.takeRequest().let { "${it.method} ${it.target}" })
    }

    @Test
    fun `a bookmark label is the prompt's first line, cut at 200`() {
        assertEquals("Second", SessionBookmark.labelOf("\n  \n  Second  \nthird"))
        val long = "x".repeat(250)
        assertEquals("x".repeat(199) + "…", SessionBookmark.labelOf(long))
    }

    @Test
    fun `the export is saved under the name the server chose, its UTF-8 form first`() = withServer(
        MockResponse.Builder()
            .addHeader("Content-Type", "text/markdown; charset=utf-8")
            .addHeader("Content-Disposition", "attachment; filename=\"s_a-chat.md\"; filename*=UTF-8''s%E1%BB%ADa-chat.md")
            .body("# Sửa chat\n")
            .build(),
    ) { server, api ->
        val export = api.exportChatMarkdown("p", "s", "codex")
        assertEquals("sửa-chat.md", export.fileName)
        assertEquals("# Sửa chat\n", export.markdown)
        assertEquals("/api/project/p/chat/sessions/s/export.md?providerId=codex", server.takeRequest().target)
    }

    @Test
    fun `an export the server refuses fails with its error, or the status`() = withServer(
        MockResponse.Builder().code(400).body("""{"ok":false,"error":"Session not found"}""").build(),
        MockResponse.Builder().code(404).body("<html></html>").build(),
    ) { _, api ->
        fun fails(block: suspend () -> Unit): PpmError? = runBlocking {
            try {
                block()
                null
            } catch (e: PpmError) {
                e
            }
        }
        assertEquals(PpmError.Server(400, "Session not found"), fails { api.exportChatMarkdown("p", "s", "claude") })
        assertEquals(PpmError.Server(404, "Export failed (404)"), fails { api.exportChatMarkdown("p", "s", "claude") })
    }

    @Test
    fun `a disposition with no name falls back`() {
        assertEquals("chat.md", dispositionFileName(null, "chat.md"))
        assertEquals("a.md", dispositionFileName("attachment; filename=\"a.md\"", "chat.md"))
    }
}
