package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.WireJson
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class FileMutationsApiTest {
    private fun ok(data: String) = MockResponse.Builder().addHeader("Content-Type", "application/json")
        .body("""{"ok":true,"data":$data}""").build()
    private fun check(vararg data: String, block: suspend (MockWebServer, ApiClient) -> Unit) = runBlocking {
        val server = MockWebServer()
        data.forEach { server.enqueue(ok(it)) }
        server.start()
        try { block(server, ApiClient(server.url("/"), "secret")) } finally { server.close() }
    }
    private fun request(server: MockWebServer, method: String, path: String, body: String) {
        val request = server.takeRequest()
        assertEquals(method, request.method)
        assertEquals(path, request.target)
        assertEquals("Bearer secret", request.headers["Authorization"])
        assertEquals(WireJson.parse(body), WireJson.parse(request.body!!.utf8()))
    }

    @Test fun `create rename and delete match project routes including JSON DELETE body`() = check(
        """{"created":"a","type":"file"}""", """{"created":"dir","type":"directory"}""",
        """{"renamed":{"from":"a","to":"dir/a"}}""", """{"deleted":"dir"}""",
    ) { server, api ->
        api.createProjectFile("my app/a", "a")
        request(server, "POST", "/api/project/my%20app%2Fa/files/create", """{"path":"a","type":"file"}""")
        api.createProjectFile("p", "dir", isDirectory = true)
        request(server, "POST", "/api/project/p/files/create", """{"path":"dir","type":"directory"}""")
        api.renameProjectFile("p", "a", "dir/a")
        request(server, "POST", "/api/project/p/files/rename", """{"oldPath":"a","newPath":"dir/a"}""")
        api.deleteProjectFile("p", "dir")
        request(server, "DELETE", "/api/project/p/files/delete", """{"path":"dir"}""")
    }

    @Test fun `project transfers decode wrapped acknowledgements and send full destination paths`() = check(
        """{"moved":{"from":"a","to":"d/a"},"future":1}""", """{"copied":{"from":"d/a","to":"b"}}""",
    ) { server, api ->
        val moved = api.moveProjectFile("p", "a", "d/a")
        assertEquals("a", moved.source)
        assertEquals("d/a", moved.destination)
        assertNull(moved.crossDevice)
        assertNull(moved.sourceRemoved)
        request(server, "POST", "/api/project/p/files/move", """{"source":"a","destination":"d/a"}""")
        assertEquals("b", api.copyProjectFile("p", "d/a", "b").destination)
        request(server, "POST", "/api/project/p/files/copy", """{"source":"d/a","destination":"b"}""")
    }

    @Test fun `fs transfers retain copied-but-not-removed state and optional fields`() = check(
        """{"source":"/a","destination":"/b/a","crossDevice":true,"sourceRemoved":false,"future":2}""",
        """{"source":"/a","destination":"/c/a"}""",
    ) { server, api ->
        val moved = api.fsMove("/a", "/b/a")
        assertEquals(true, moved.crossDevice)
        assertEquals(false, moved.sourceRemoved)
        request(server, "POST", "/api/fs/move", """{"source":"/a","destination":"/b/a"}""")
        val copied = api.fsCopy("/a", "/c/a")
        assertNull(copied.sourceRemoved)
        request(server, "POST", "/api/fs/copy", """{"source":"/a","destination":"/c/a"}""")
    }

    @Test fun `write routes Unix and Windows absolute paths to fs and retains exact text`() = check(
        """{"written":"a"}""", "true", "true", "true",
    ) { server, api ->
        api.writeFile("p", "a", "  tiếng Việt\n\"quoted\"\n")
        request(server, "PUT", "/api/project/p/files/write", """{"path":"a","content":"  tiếng Việt\n\"quoted\"\n"}""")
        api.writeFile("ignored", "/tmp/a", "")
        request(server, "PUT", "/api/fs/write", """{"path":"/tmp/a","content":""}""")
        api.writeFile(null, "C:\\repo\\a", "x")
        request(server, "PUT", "/api/fs/write", """{"path":"C:\\repo\\a","content":"x"}""")
        api.writeFile(null, "D:/a", "y")
        request(server, "PUT", "/api/fs/write", """{"path":"D:/a","content":"y"}""")
        try { api.writeFile(null, "a", "bad"); fail("missing project") } catch (_: IllegalArgumentException) { }
        assertEquals(4, server.requestCount)
    }

    @Test fun `malformed transfer acknowledgements are not reported as success`() = check("{}", "{\"copied\":{\"from\":\"a\"}}", "true") { _, api ->
        for (call in listOf<suspend () -> Unit>(
            { api.moveProjectFile("p", "a", "b") }, { api.copyProjectFile("p", "a", "b") }, { api.fsMove("/a", "/b") },
        )) {
            try { call(); fail("malformed acknowledgement") } catch (_: PpmError.BadResponse) { }
        }
    }

    @Test fun `collision keeps server status and code without retry overwrite or confirmation request`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().code(409).addHeader("Content-Type", "application/json")
            .body("""{"ok":false,"error":"Already exists: /b","code":"EEXIST"}""").build())
        server.start()
        try {
            try { ApiClient(server.url("/"), "t").fsCopy("/a", "/b"); fail("collision") }
            catch (e: PpmError.Server) { assertEquals(PpmError.Server(409, "Already exists: /b", "EEXIST"), e) }
            assertEquals(1, server.requestCount)
        } finally { server.close() }
    }
}
