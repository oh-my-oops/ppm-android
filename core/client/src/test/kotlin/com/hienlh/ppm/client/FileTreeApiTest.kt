package com.hienlh.ppm.client

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class FileTreeApiTest {
    private fun response(data: String) = MockResponse.Builder().addHeader("Content-Type", "application/json")
        .body("""{"ok":true,"data":$data}""").build()
    private fun check(vararg data: String, block: suspend (MockWebServer, ApiClient) -> Unit) = runBlocking {
        val server = MockWebServer()
        data.forEach { server.enqueue(response(it)) }
        server.start()
        try { block(server, ApiClient(server.url("/"), "secret")) } finally { server.close() }
    }

    @Test fun `directory listing keeps ignore flags and treats server symlinks as files`() = check(
        """[{"name":"src","type":"directory","isIgnored":true,"future":1},{"name":"linked-dir","type":"file"}]""",
    ) { server, api ->
        val entries = api.listProjectDirectory("my app", "dir & docs")
        assertTrue(entries[0].isDirectory)
        assertTrue(entries[0].isIgnored)
        assertFalse(entries[1].isDirectory)
        val request = server.takeRequest()
        assertEquals("/api/project/my%20app/files/list?path=dir%20%26%20docs", request.target)
        assertEquals("Bearer secret", request.headers["Authorization"])
    }

    @Test fun `batch retains per-path errors and encodes root in the body`() = check(
        """[{"path":"missing","error":"Directory not found"},{"path":"","entries":[]}]""",
    ) { server, api ->
        val result = api.listProjectDirectories("p", listOf("", "missing"))
        assertEquals("Directory not found", result[0].error)
        assertNull(result[0].entries)
        assertEquals(emptyList<Any>(), result[1].entries)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/project/p/files/list-batch", request.target)
        assertEquals("""{"paths":["","missing"]}""", request.body!!.utf8())
        for (paths in listOf(emptyList(), List(51) { "$it" })) {
            try { api.listProjectDirectories("p", paths); fail("invalid batch") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun `typed reads retain binary and unknown encoding without decoding as text`() = check(
        """{"content":"YQBi","encoding":"base64"}""",
        """{"content":"future","encoding":"new-codec"}""",
        """{"content":"old API","encoding":"utf-8"}""",
    ) { server, api ->
        val binary = api.readFileContent("p", "image.bin")
        assertEquals("YQBi", binary.content)
        assertTrue(binary.isBinary)
        assertFalse(binary.isText)
        assertEquals("/api/project/p/files/read?path=image.bin", server.takeRequest().target)
        val unknown = api.readProjectFileContent("p", "a")
        assertFalse(unknown.isBinary)
        assertFalse(unknown.isText)
        assertEquals("old API", api.readProjectFile("p", "a"))
    }

    @Test fun `absolute Unix and Windows reads need no project and default missing encoding to text`() = check(
        """{"content":"unix"}""", """{"content":"win"}""",
    ) { server, api ->
        assertTrue(api.readFileContent(null, "/tmp/a b").isText)
        assertEquals("/api/fs/read?path=%2Ftmp%2Fa%20b", server.takeRequest().target)
        assertEquals("win", api.readFileContent(null, "C:\\repo\\a").content)
        assertEquals("/api/fs/read?path=C%3A%5Crepo%5Ca", server.takeRequest().target)
        try { api.readFileContent(null, "relative"); fail("missing project") } catch (_: IllegalArgumentException) { }
    }

    @Test fun `malformed responses are errors not empty directories or empty text`() = check("{}", "[]", "{}") { _, api ->
        for (read in listOf<suspend () -> Any>({ api.listProjectDirectory("p") }, { api.readFileContent("p", "x") }, { api.listProjectDirectories("p", listOf("")) })) {
            try { read(); fail("malformed response") } catch (_: PpmError.BadResponse) { }
        }
    }

    @Test fun `permission and size failures preserve server status`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().code(403).addHeader("Content-Type", "application/json").body("""{"ok":false,"error":"Access denied"}""").build())
        server.enqueue(MockResponse.Builder().code(400).addHeader("Content-Type", "application/json").body("""{"ok":false,"error":"File too large (>5MB)","code":"EFBIG"}""").build())
        server.start()
        try {
            val api = ApiClient(server.url("/"), "t")
            try { api.listProjectDirectory("p", "a..b"); fail("403") }
            catch (e: PpmError.Server) { assertEquals(PpmError.Server(403, "Access denied"), e) }
            try { api.readFileContent(null, "/big"); fail("400") }
            catch (e: PpmError.Server) { assertEquals(PpmError.Server(400, "File too large (>5MB)", "EFBIG"), e) }
        } finally { server.close() }
    }
}
