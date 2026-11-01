package com.hienlh.ppm.client

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DocumentApiTest {
    @Test fun `Word conversion routes relative and absolute paths with auth and preserves HTML for sanitizer`() = runBlocking {
        val server = MockWebServer()
        repeat(2) { server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json")
            .body("""{"ok":true,"data":{"html":"<p>Việt</p><script>unsafe()</script>"}}""").build()) }
        server.start()
        try {
            val api = ApiClient(server.url("/"), "scratch-token")
            assertEquals("<p>Việt</p><script>unsafe()</script>", api.readDocxHtml("my project", "docs/a b.docx"))
            val request = server.takeRequest()
            assertEquals("/api/project/my%20project/files/docx-html?path=docs%2Fa%20b.docx", request.target)
            assertEquals("Bearer scratch-token", request.headers["Authorization"])
            api.readDocxHtml(null, "C:\\docs\\a.docx")
            assertEquals("/api/fs/docx-html?path=C%3A%5Cdocs%5Ca.docx", server.takeRequest().target)
            try { api.readDocxHtml(null, "relative.docx"); fail("relative path needs a project") } catch (_: IllegalArgumentException) { }
            assertEquals(2, server.requestCount)
        } finally { server.close() }
    }
}
