package com.hienlh.ppm.state.files

import com.hienlh.ppm.client.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class FileDocumentWireTest {
    private val token = "auth +/secret"
    private fun json(body: String, code: Int = 200) = MockResponse.Builder()
        .code(code).addHeader("Content-Type", "application/json").body(body).build()
    private fun content(text: String, encoding: String? = null) = json(
        """{"ok":true,"data":{"content":"$text"${encoding?.let { ",\"encoding\":\"$it\"" }.orEmpty()}}}""",
    )

    private fun withServer(block: suspend CoroutineScope.(MockWebServer, ApiClient) -> Unit) = runBlocking {
        MockWebServer().use { server ->
            server.start()
            block(server, ApiClient(server.url("/ppm/"), token))
        }
    }

    private suspend fun loaded(doc: FileDocumentController) = withTimeout(4_000) {
        while (doc.state.value.loading) delay(5)
    }
    private suspend fun request(server: MockWebServer): RecordedRequest = withContext(Dispatchers.IO) {
        checkNotNull(server.takeRequest(3, TimeUnit.SECONDS)) { "No request arrived" }
    }

    @Test fun `text route preserves relative POSIX and Windows paths and bearer authentication`() = withServer { server, api ->
        val project = "app /+雪"
        val paths = listOf("src/a +?&#雪.kt", "/work/a +?&#雪.kt", "C:\\work\\a +?&#雪.kt", "D:/work/a.kt")
        for (path in paths) {
            server.enqueue(content("hello", "utf-8"))
            FileDocumentController(api, project, path, this).use { doc ->
                doc.show(); loaded(doc)
                assertEquals("hello", doc.state.value.content)
                val request = request(server)
                val endpoint = if (path == paths.first()) "/api/project/${ApiClient.escape(project)}/files/read" else "/api/fs/read"
                assertEquals("/ppm$endpoint", request.url.encodedPath)
                assertEquals(path, request.url.queryParameter("path"))
                assertEquals("Bearer $token", request.headers["Authorization"])
                assertNull(request.url.queryParameter("token"))
            }
        }
    }

    @Test fun `base64 and unknown encoding never escape into content and fs text needs no encoding`() = withServer { server, api ->
        FileDocumentController(api, "app", "a.txt", this).use { doc ->
            server.enqueue(content("AAEC", "base64"))
            doc.show(); loaded(doc)
            assertTrue(doc.state.value.cannotDisplay)
            assertNull(doc.state.value.content)
            assertNull(doc.state.value.error)
            assertFalse(doc.state.value.stale)

            server.enqueue(content("AAEC", "unknown"))
            doc.refresh(); loaded(doc)
            assertTrue(doc.state.value.cannotDisplay)
            assertNull(doc.state.value.content)

            server.enqueue(content("plain"))
            doc.refresh(); loaded(doc)
            assertEquals("plain", doc.state.value.content)
            assertFalse(doc.state.value.cannotDisplay)
        }
        server.enqueue(content("host file"))
        FileDocumentController(api, null, "/tmp/a.txt", this).use { doc ->
            doc.show(); loaded(doc)
            assertEquals("host file", doc.state.value.content)
        }
    }

    @Test fun `image and PDF fetch raw bytes at the matching route with header auth`() = withServer { server, api ->
        val bytes = byteArrayOf(0, -1, 10, 13, 42)
        for ((project, path) in listOf("app" to "images/a #.png", null to "/tmp/report.pdf", null to "C:\\pics\\a.jpg")) {
            server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/test")
                .body(Buffer().write(bytes)).build())
            FileDocumentController(api, project, path, this).use { doc ->
                doc.show(); loaded(doc)
                assertArrayEquals(bytes, doc.state.value.raw?.bytes)
                assertEquals("application/test", doc.state.value.raw?.contentType)
                assertNull(doc.state.value.content)
                assertFalse(doc.state.value.cannotDisplay)
                val request = request(server)
                val endpoint = if (project == null) "/api/fs/raw" else "/api/project/app/files/raw"
                assertEquals("/ppm$endpoint", request.url.encodedPath)
                assertEquals(path, request.url.queryParameter("path"))
                assertEquals("Bearer $token", request.headers["Authorization"])
            }
        }
    }

    @Test fun `missing relative project fails locally while malformed or refused responses stay retryable`() = withServer { server, api ->
        for (path in listOf("a.txt", "a.png")) FileDocumentController(api, null, path, this).use { doc ->
            doc.show(); loaded(doc)
            assertEquals("A relative file path needs a project.", doc.state.value.error)
            assertTrue(doc.state.value.stale)
        }
        assertEquals(0, server.requestCount)
        FileDocumentController(api, null, "/tmp/a.txt", this).use { doc ->
            server.enqueue(json("""{"ok":false,"error":"File too large","code":"EFBIG"}""", 413))
            doc.show(); loaded(doc)
            assertEquals("File too large", doc.state.value.error)
            assertTrue(doc.state.value.stale)
            server.enqueue(json("""{"ok":true,"data":{"encoding":"utf-8"}}"""))
            doc.refresh(); loaded(doc)
            assertNotNull(doc.state.value.error)
            assertNull(doc.state.value.content)
            server.enqueue(content("recovered"))
            doc.refresh(); loaded(doc)
            assertEquals("recovered", doc.state.value.content)
            assertNull(doc.state.value.error)
        }
    }

    @Test fun `refresh during a shared GET discards its result and actually sends a fresh request`() = withServer { server, api ->
        val firstReply = CountDownLatch(1)
        val secondReply = CountDownLatch(1)
        val received = LinkedBlockingQueue<RecordedRequest>()
        var count = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val index = ++count
                received.put(request)
                check((if (index == 1) firstReply else secondReply).await(4, TimeUnit.SECONDS))
                return content(if (index == 1) "obsolete" else "fresh")
            }
        }
        FileDocumentController(api, "app", "a.txt", this).use { doc ->
            try {
                doc.show()
                withContext(Dispatchers.IO) { checkNotNull(received.poll(3, TimeUnit.SECONDS)) }
                doc.refresh(); doc.fileChanged("app", "a.txt")
                firstReply.countDown()
                withContext(Dispatchers.IO) { checkNotNull(received.poll(3, TimeUnit.SECONDS)) }
                assertNull(doc.state.value.content)
                assertEquals(0, doc.state.value.revision)
                assertTrue(doc.state.value.loading)
                secondReply.countDown()
                loaded(doc)
                assertEquals("fresh", doc.state.value.content)
                assertEquals(1, doc.state.value.revision)
                assertEquals(2, server.requestCount)
            } finally { firstReply.countDown(); secondReply.countDown() }
        }
    }
}
