package com.hienlh.ppm.client

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** `GET /api/fs/raw` and `GET /api/project/:name/files/raw`, as `useBlobUrl` reads them. */
class FilesTest {
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

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a)

    @Test
    fun `an absolute path is read from the machine, with its type`() = withServer(
        MockResponse.Builder().addHeader("Content-Type", "image/png").body(Buffer().write(png)).build(),
    ) { server, api ->
        val file = api.rawFile("my app", "/tmp/shots/a1.png")
        assertArrayEquals(png, file.bytes)
        assertEquals("image/png", file.contentType)
        val request = server.takeRequest()
        assertEquals("/api/fs/raw?path=%2Ftmp%2Fshots%2Fa1.png", request.target)
        assertEquals("Bearer t", request.headers["Authorization"])
    }

    @Test
    fun `a relative path is read from inside the project, and a Windows drive counts as absolute`() = withServer(
        MockResponse.Builder().body(Buffer().write(png)).build(),
        MockResponse.Builder().body(Buffer().write(png)).build(),
    ) { server, api ->
        api.rawFile("my app", "docs/a.png")
        assertEquals("/api/project/my%20app/files/raw?path=docs%2Fa.png", server.takeRequest().target)
        api.rawFile("my app", "C:\\shots\\a.png")
        assertEquals("/api/fs/raw?path=C%3A%5Cshots%5Ca.png", server.takeRequest().target)
    }

    @Test
    fun `a refusal keeps its status, whether or not it says why`() = withServer(
        MockResponse.Builder().code(403).addHeader("Content-Type", "application/json").body("""{"ok":false,"error":"Access denied"}""").build(),
        MockResponse.Builder().code(502).addHeader("Content-Type", "text/plain").body("Bad gateway").build(),
    ) { _, api ->
        for (expected in listOf(PpmError.Server(403, "Access denied"), PpmError.Server(502, "The server answered HTTP 502."))) {
            try {
                api.rawFile("web", "/tmp/a.png")
                fail("an error answer must throw")
            } catch (error: PpmError) {
                assertEquals(expected, error)
            }
        }
    }
}
