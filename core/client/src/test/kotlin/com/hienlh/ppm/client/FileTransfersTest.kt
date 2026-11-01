package com.hienlh.ppm.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class FileTransfersTest {
    private fun json(data: String, status: Int = 200) = MockResponse.Builder().code(status).addHeader("Content-Type", "application/json")
        .body("""{"ok":true,"data":$data}""").build()
    private fun token(value: String = "fresh-token") = json("""{"token":"$value"}""")
    private fun withServer(block: suspend (MockWebServer, ApiClient) -> Unit) = runBlocking {
        val server = MockWebServer()
        server.start()
        try { block(server, ApiClient(server.url("/"), "secret")) } finally { server.close() }
    }

    @Test fun `download mints fresh token then streams encoded raw without password or Range`() = withServer { server, api ->
        server.enqueue(token("first")); server.enqueue(MockResponse.Builder().body("x".repeat(2 * 1024 * 1024)).build())
        server.enqueue(token("second")); server.enqueue(MockResponse.Builder().body("").build())
        val output = CountingOutput()
        val progress = mutableListOf<ProjectTransferProgress>()
        assertEquals(2L * 1024 * 1024, api.downloadProjectFile("my project", "a & b.txt", false, output) { progress += it })
        assertTrue(output.closed)
        assertTrue(output.largestWrite <= 64 * 1024)
        assertEquals(ProjectTransferProgress(output.bytes, output.bytes), progress.last())
        val mint = server.takeRequest()
        assertEquals("POST", mint.method)
        assertEquals("/api/project/my%20project/files/download/token", mint.target)
        assertEquals("Bearer secret", mint.headers["Authorization"])
        val get = server.takeRequest()
        assertEquals("/api/project/my%20project/files/raw?path=a%20%26%20b.txt&download=true&dl_token=first", get.target)
        assertNull(get.headers["Authorization"])
        assertNull(get.headers["Range"])
        val empty = CountingOutput()
        assertEquals(0L, api.downloadProjectFile("my project", "empty", false, empty))
        server.takeRequest()
        assertTrue(server.takeRequest().target.endsWith("dl_token=second"))
        assertTrue(empty.closed)
    }

    @Test fun `zip has indeterminate progress and root uses dot`() = withServer { server, api ->
        server.enqueue(token()); server.enqueue(MockResponse.Builder().chunkedBody("zip bytes", 2).build())
        val progress = mutableListOf<ProjectTransferProgress>()
        assertEquals(9L, api.downloadProjectFile("p", "", true, CountingOutput()) { progress += it })
        assertTrue(progress.all { it.total == null })
        server.takeRequest()
        assertEquals("/api/project/p/files/download/zip?path=.&dl_token=fresh-token", server.takeRequest().target)
    }

    @Test fun `destination commit failure is not reported as a successful download`() = withServer { server, api ->
        server.enqueue(token()); server.enqueue(MockResponse.Builder().body("abc").build())
        var closes = 0
        val output = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun close() { if (++closes == 1) throw IOException("Provider could not commit file") }
        }
        try { api.downloadProjectFile("p", "a", false, output); fail("close failure") }
        catch (error: IOException) { assertEquals("Provider could not commit file", error.message) }
        assertEquals(1, closes)
    }

    @Test fun `missing token and rejected token never write error JSON into destination`() = withServer { server, api ->
        server.enqueue(json("{}"))
        val missing = CountingOutput()
        try { api.downloadProjectFile("p", "x", false, missing); fail("missing token") }
        catch (_: PpmError.BadResponse) { }
        assertTrue(missing.closed)
        assertEquals(0L, missing.bytes)
        server.enqueue(token())
        server.enqueue(MockResponse.Builder().code(401).body("Unauthorized").build())
        val rejected = CountingOutput()
        try { api.downloadProjectFile("p", "x", false, rejected); fail("expired token") }
        catch (error: PpmError.Server) { assertEquals(401, error.status); assertTrue(error.message!!.contains("token")) }
        assertEquals(0L, rejected.bytes)
        assertTrue(rejected.closed)
        assertEquals(3, server.requestCount)
    }

    @Test fun `cancel after headers stops a blocked download and closes destination`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(token())
        server.enqueue(MockResponse.Builder().body("x".repeat(100)).throttleBody(1, 1, TimeUnit.SECONDS).build())
        server.start()
        val first = CompletableDeferred<Unit>()
        val output = CountingOutput()
        try {
            val job = launch { ApiClient(server.url("/"), "t").downloadProjectFile("p", "slow", false, output) {
                if (it.bytes > 0) first.complete(Unit)
            } }
            withTimeout(3_000) { first.await() }
            withTimeout(2_000) { job.cancelAndJoin() }
            assertTrue(output.closed)
            assertTrue(output.bytes < 100)
        } finally { server.close() }
    }

    @Test fun `multipart streams repeated files plus targetDir and retains typed results`() = withServer { server, api ->
        server.enqueue(json("""{"uploaded":[{"name":"a.txt","path":"dir/a.txt","size":3},{"name":"b.txt","path":"dir/b.txt","size":2}]}""", 201))
        val first = TrackedInput("abc".toByteArray())
        val second = TrackedInput("de".toByteArray())
        val progress = mutableListOf<ProjectTransferProgress>()
        val result = api.uploadProjectFiles("my project", "dir", listOf(
            ProjectUploadSource("a.txt", "text/plain", 3) { first }, ProjectUploadSource("b.txt", null, 2) { second },
        )) { progress += it }
        assertEquals(listOf(ProjectUploadedFile("a.txt", "dir/a.txt", 3), ProjectUploadedFile("b.txt", "dir/b.txt", 2)), result)
        assertTrue(first.closed && second.closed)
        assertEquals(ProjectTransferProgress(5, 5), progress.last())
        val request = server.takeRequest()
        assertEquals("/api/project/my%20project/files/upload", request.target)
        assertEquals("Bearer secret", request.headers["Authorization"])
        val body = request.body!!.utf8()
        assertTrue(body.contains("name=\"targetDir\"\r\n"))
        assertTrue(body.contains("\r\n\r\ndir\r\n"))
        assertEquals(2, Regex("name=\"files\"").findAll(body).count())
        assertTrue(body.contains("\r\n\r\nabc\r\n"))
    }

    @Test fun `count and known size limits reject before opening streams or HTTP`() = withServer { server, api ->
        var opened = 0
        val source = ProjectUploadSource("large", null, PROJECT_UPLOAD_MAX_BYTES + 1) { opened++; ByteArrayInputStream(byteArrayOf()) }
        for (files in listOf(emptyList(), listOf(source), List(21) { source.copy(size = 1) })) {
            try { api.uploadProjectFiles("p", "", files); fail("invalid upload") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, opened)
        assertEquals(0, server.requestCount)
    }

    @Test fun `unknown length uploads use chunking and no fabricated total`() = withServer { server, api ->
        server.enqueue(json("""{"uploaded":[{"name":"a","path":"a","size":3}]}"""))
        val progress = mutableListOf<ProjectTransferProgress>()
        api.uploadProjectFiles("p", "", listOf(ProjectUploadSource("a", null, null) { ByteArrayInputStream("abc".toByteArray()) })) { progress += it }
        assertTrue(progress.all { it.total == null })
        assertEquals(3L, progress.last().bytes)
        assertEquals("chunked", server.takeRequest().headers["Transfer-Encoding"])
    }

    @Test fun `failed upload preserves partial write uncertainty and never retries POST`() = withServer { server, api ->
        server.enqueue(MockResponse.Builder().code(400).addHeader("Content-Type", "application/json")
            .body("""{"ok":false,"error":"Second file failed after the first was saved"}""").build())
        val input = TrackedInput(byteArrayOf(1, 2))
        try { api.uploadProjectFiles("p", "", listOf(ProjectUploadSource("a", null, 2) { input })); fail("failed upload") }
        catch (error: ProjectUploadFailure) {
            assertTrue(error.mayHaveWritten)
            assertEquals(400, (error.cause as PpmError.Server).status)
        }
        assertTrue(input.closed)
        assertEquals(1, server.requestCount)
    }

    @Test fun `unknown provider size cannot stream past the fifty MB bound`() = withServer { server, api ->
        server.bodyLimit = 1024
        server.enqueue(json("""{"uploaded":[]}"""))
        var closed = false
        var remaining = PROJECT_UPLOAD_MAX_BYTES + 1
        val generated = object : InputStream() {
            override fun read(): Int = if (remaining-- > 0) 0 else -1
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (remaining == 0L) return -1
                val count = minOf(length.toLong(), remaining).toInt()
                remaining -= count
                return count
            }
            override fun close() { closed = true }
        }
        try {
            withTimeout(10_000) { api.uploadProjectFiles("p", "", listOf(ProjectUploadSource("cloud", null, null) { generated })) }
            fail("streaming bound")
        } catch (error: ProjectUploadFailure) { assertTrue(error.message!!.contains("50 MB")) }
        assertTrue(closed)
    }

    @Test fun `cancellation closes a blocked upload provider stream`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(json("""{"uploaded":[]}"""))
        server.start()
        val started = CompletableDeferred<Unit>()
        val input = BlockingInput { started.complete(Unit) }
        try {
            val job = launch { ApiClient(server.url("/"), "t").uploadProjectFiles("p", "", listOf(ProjectUploadSource("cloud", null, null) { input })) }
            withTimeout(3_000) { started.await() }
            withTimeout(2_000) { job.cancelAndJoin() }
            assertTrue(input.closed.get())
        } finally { input.close(); server.close() }
    }

    private class CountingOutput : OutputStream() {
        var bytes = 0L
        var largestWrite = 0
        @Volatile var closed = false
        override fun write(value: Int) { bytes++ }
        override fun write(data: ByteArray, offset: Int, length: Int) { bytes += length; largestWrite = maxOf(largestWrite, length) }
        override fun close() { closed = true }
    }
    private class TrackedInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() { closed = true; super.close() }
    }
    private class BlockingInput(val start: () -> Unit) : InputStream() {
        val closed = AtomicBoolean()
        private val gate = CountDownLatch(1)
        override fun read(): Int { start(); gate.await(5, TimeUnit.SECONDS); return -1 }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = read()
        override fun close() { closed.set(true); gate.countDown() }
    }
}
