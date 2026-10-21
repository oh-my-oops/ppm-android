package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.ChatDraft
import com.hienlh.ppm.protocol.DraftAttachment
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `/api/project/:p/chat/drafts/:id` (routes/chat.ts) as the app calls it. */
class DraftsTest {
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
    fun `a draft is read from its session's row, and none is null`() = withServer(
        MockResponse.Builder().body("""{"ok":true,"data":{"content":"hi","attachments":"[]","updatedAt":"x"}}""").build(),
        MockResponse.Builder().body("""{"ok":true,"data":null}""").build(),
    ) { server, api ->
        assertEquals(ChatDraft("hi"), api.draft("my app", "s/1"))
        assertEquals("/api/project/my%20app/chat/drafts/s%2F1", server.takeRequest().target)
        assertNull(api.draft("my app", "s-2"))
    }

    @Test
    fun `a save carries the text with its reply and the files as a JSON string`() = withServer(
        MockResponse.Builder().body("""{"ok":true,"data":{"saved":true}}""").build(),
        MockResponse.Builder().body("""{"ok":true,"data":{"deleted":true}}""").build(),
    ) { server, api ->
        api.saveDraft("web", "s-1", ChatDraft("hi", listOf(DraftAttachment("a.png", "/up/a.png"))))
        val save = server.takeRequest()
        assertEquals("PUT", save.method)
        assertEquals("/api/project/web/chat/drafts/s-1", save.target)
        assertEquals("""{"content":"hi","attachments":"[{\"name\":\"a.png\",\"path\":\"/up/a.png\"}]"}""", save.body?.utf8())
        api.deleteDraft("web", "s-1")
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("/api/project/web/chat/drafts/s-1", delete.target)
    }
}
