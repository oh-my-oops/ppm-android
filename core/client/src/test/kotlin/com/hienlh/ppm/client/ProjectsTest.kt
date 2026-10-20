package com.hienlh.ppm.client

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** `routes/projects.ts`: the order of the projects and their avatars. */
class ProjectsTest {
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
    fun `a new order is the whole list of names`() = withServer(
        MockResponse.Builder().body("""{"ok":true,"data":{"reordered":2}}""").build(),
    ) { server, api ->
        api.reorderProjects(listOf("web", "api server"))
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/projects/reorder", request.target)
        assertEquals("""{"order":["web","api server"]}""", request.body?.utf8())
    }

    @Test
    fun `an avatar comes as bytes, addressed by its version`() {
        val webp = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x00, 0x7f)
        withServer(
            MockResponse.Builder().addHeader("Content-Type", "image/webp").body(Buffer().write(webp)).build(),
        ) { server, api ->
            assertArrayEquals(webp, api.projectImage("my app", "81082d41.webp"))
            val request = server.takeRequest()
            assertEquals("/api/projects/my%20app/image?v=81082d41.webp", request.target)
            assertEquals("Bearer t", request.headers["Authorization"])
        }
    }

    @Test
    fun `a project with no avatar is the server's error`() = withServer(
        MockResponse.Builder().code(404).addHeader("Content-Type", "application/json").body("""{"ok":false,"error":"No image"}""").build(),
    ) { _, api ->
        try {
            api.projectImage("web", "gone.webp")
            fail("a 404 must throw")
        } catch (error: PpmError) {
            assertEquals(PpmError.Server(404, "No image"), error)
        }
    }
}
