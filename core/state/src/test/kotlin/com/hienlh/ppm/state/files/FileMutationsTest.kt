package com.hienlh.ppm.state.files

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.PpmHttp
import com.hienlh.ppm.protocol.ProjectInfo
import com.hienlh.ppm.protocol.WireJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.QueueDispatcher
import mockwebserver3.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class FileMutationsTest {
    private val project = ProjectInfo("app", "/work/app")
    private fun ok(data: String = "true") = MockResponse.Builder().addHeader("Content-Type", "application/json")
        .body("""{"ok":true,"data":$data}""").build()
    private fun refused() = MockResponse.Builder().code(409).addHeader("Content-Type", "application/json")
        .body("""{"ok":false,"error":"Already exists","code":"EEXIST"}""").build()
    /**
     * A request nobody queued an answer for gets a 404 at once. MockWebServer's own dispatcher holds
     * it until the server closes, and a mutation waits 600 s for an answer: one request more than a
     * test expected sat a gate for ten minutes. A test that still waits is stopped after [timeoutMs],
     * with what the server and the HTTP client were holding a tenth of the time before: the timeout
     * cancels the call, and a cancelled call holds nothing.
     */
    private fun withServer(timeoutMs: Long = 20_000, block: suspend CoroutineScope.(MockWebServer, FileMutations) -> Unit) = runBlocking {
        val server = MockWebServer()
        server.dispatcher = QueueDispatcher().apply { setFailFast(true) }
        server.start()
        val held = AtomicReference("")
        val watch = launch(Dispatchers.IO) { delay(timeoutMs - timeoutMs / 10); held.set(heldBy(server)) }
        try {
            try { withTimeout(timeoutMs) { block(server, FileMutations(ApiClient(server.url("/"), "secret"))) } }
            catch (e: TimeoutCancellationException) { throw AssertionError("Still waiting after $timeoutMs ms: ${held.get()}", e) }
        } finally { watch.cancel(); withContext(Dispatchers.IO) { server.close() } }
    }
    private fun heldBy(server: MockWebServer): String {
        val calls = PpmHttp.client.dispatcher
        return "the server was sent ${server.requestCount} request(s); the HTTP client holds " +
            "running ${calls.runningCalls().map { it.request().url }} and queued ${calls.queuedCalls().map { it.request().url }}"
    }
    private suspend fun request(server: MockWebServer): RecordedRequest = withContext(Dispatchers.IO) {
        checkNotNull(server.takeRequest(3, TimeUnit.SECONDS)) { "No request arrived" }
    }
    private suspend fun assertRequest(server: MockWebServer, method: String, path: String, body: String) {
        val r = request(server)
        assertEquals(method, r.method)
        assertEquals(path, r.target)
        assertEquals(WireJson.parse(body), WireJson.parse(r.body!!.utf8()))
    }

    @Test fun `create content reports created empty file when subsequent write fails`() = withServer { server, files ->
        server.enqueue(ok()); server.enqueue(refused())
        val result = files.create(project, "src/new.txt", content = "text")
        assertTrue(result.partial)
        assertEquals(listOf(FileMutationOperation.CREATE_FILE), result.changed.map { it.operation })
        assertEquals(listOf(FileMutationOperation.WRITE), result.failures.map { it.operation })
        assertEquals("EEXIST", result.failures.single().code)
        assertEquals(409, result.failures.single().httpStatus)
        assertEquals(setOf("/work/app/src"), result.affectedDirectories)
        assertEquals("Already exists", files.state.value.error)
        assertFalse(files.state.value.busy)
        assertRequest(server, "POST", "/api/project/app/files/create", """{"path":"src/new.txt","type":"file"}""")
        assertRequest(server, "PUT", "/api/project/app/files/write", """{"path":"src/new.txt","content":"text"}""")
        files.clearReport()
        assertNull(files.state.value.error)
    }

    @Test fun `create collision never writes over existing file and directory has no content write`() = withServer { server, files ->
        server.enqueue(refused())
        assertFalse(files.create(project, "taken", content = "must not overwrite").succeeded)
        assertEquals(1, server.requestCount)
        request(server)
        server.enqueue(ok())
        assertTrue(files.create(project, "folder", isDirectory = true).succeeded)
        assertRequest(server, "POST", "/api/project/app/files/create", """{"path":"folder","type":"directory"}""")
        assertEquals(2, server.requestCount)
        try { files.create(project, "folder", true, "text"); fail("directory content") } catch (_: IllegalArgumentException) { }
    }

    @Test fun `paste continues per item routes by membership and clears cut after partial failure`() = withServer { server, files ->
        server.enqueue(ok("""{"moved":{"from":"src/a","to":"dest/a"}}"""))
        server.enqueue(refused())
        server.enqueue(ok("""{"source":"/work/other/c","destination":"/work/app/dest/c","sourceRemoved":true}"""))
        // Deliberately wrong origin: actual path membership decides the route, as web does.
        files.setClipboard(FileClipboard(listOf("/work/app/src/a", "/work/app/src/b", "/work/other/c"),
            FileClipboardOperation.CUT, ProjectInfo("other", "/work/other")))
        val result = files.paste(project, "dest")
        assertTrue(result.partial)
        assertEquals(3, result.items.size)
        assertEquals(listOf("src/a", "/work/other/c"), result.changed.map { it.path })
        assertEquals("src/b", result.failures.single().path)
        assertNull(files.state.value.clipboard)
        assertEquals(setOf("/work/app/src", "/work/app/dest", "/work/other"), result.affectedDirectories)
        assertRequest(server, "POST", "/api/project/app/files/move", """{"source":"src/a","destination":"dest/a"}""")
        assertRequest(server, "POST", "/api/project/app/files/move", """{"source":"src/b","destination":"dest/b"}""")
        assertRequest(server, "POST", "/api/fs/move", """{"source":"/work/other/c","destination":"/work/app/dest/c"}""")
    }

    @Test fun `Windows clipboard uses native separators and keeps copy for reuse`() = withServer { server, files ->
        val windows = ProjectInfo("win", "C:\\repo")
        val paths = mutableListOf("src/a")
        files.setProjectClipboard(windows, paths, FileClipboardOperation.COPY)
        paths += "later"
        assertEquals(listOf("C:\\repo\\src\\a"), files.state.value.clipboard!!.paths)
        server.enqueue(ok("""{"copied":{"from":"src/a","to":"out/a"}}"""))
        assertTrue(files.paste(windows, "out").succeeded)
        assertRequest(server, "POST", "/api/project/win/files/copy", """{"source":"src/a","destination":"out/a"}""")
        assertNotNull(files.state.value.clipboard)
        server.enqueue(ok("""{"source":"C:\\repo\\src\\a","destination":"D:\\target\\a"}"""))
        assertEquals(setOf("C:\\repo\\src", "D:\\target"), files.paste(null, "D:\\target").affectedDirectories)
        assertRequest(server, "POST", "/api/fs/copy", """{"source":"C:\\repo\\src\\a","destination":"D:\\target\\a"}""")
    }

    @Test fun `similar root prefixes route externally and root destination includes source filename`() = withServer { server, files ->
        files.setClipboard(FileClipboard(listOf("/work/application/a"), FileClipboardOperation.COPY))
        server.enqueue(ok("""{"source":"/work/application/a","destination":"/work/app/a"}"""))
        assertTrue(files.paste(project).succeeded)
        assertRequest(server, "POST", "/api/fs/copy", """{"source":"/work/application/a","destination":"/work/app/a"}""")
        try { files.setClipboard(FileClipboard(listOf("relative"), FileClipboardOperation.COPY)); fail("relative clipboard") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun `cross device move with source retained is explicit partial success`() = withServer { server, files ->
        server.enqueue(ok("""{"source":"/a","destination":"/mnt/a","crossDevice":true,"sourceRemoved":false}"""))
        val result = files.move(null, "/a", "/mnt/a")
        assertTrue(result.partial)
        assertEquals(FileMutationOutcome.PARTIAL, result.items.single().outcome)
        assertEquals(false, result.items.single().transfer!!.sourceRemoved)
        assertTrue(result.items.single().error!!.contains("source was not removed"))
        assertEquals(setOf("/", "/mnt"), result.affectedDirectories)
        assertEquals(1, server.requestCount) // Never retries delete on the caller's behalf.
    }

    @Test fun `ordered writes stop after failure preserve confirmed writes and allow retry`() = withServer { server, files ->
        server.enqueue(ok()); server.enqueue(refused())
        val result = files.writeFiles(project, linkedMapOf("one" to "a", "two" to "b", "three" to "c"))
        assertTrue(result.partial)
        assertEquals(listOf("one"), result.changed.map { it.path })
        assertEquals(listOf("two"), result.failures.map { it.path })
        assertEquals(2, server.requestCount)
        request(server); request(server)
        server.enqueue(ok())
        assertTrue(files.writeFile(null, "/tmp/a", "").succeeded)
        assertNull(files.state.value.error)
        assertRequest(server, "PUT", "/api/fs/write", """{"path":"/tmp/a","content":""}""")
    }

    @Test fun `permanent batch delete continues after failures and unchanged rename is local noop`() = withServer { server, files ->
        assertTrue(files.rename(project, "a", "a").succeeded)
        assertEquals(0, server.requestCount)
        server.enqueue(refused()); server.enqueue(ok())
        val result = files.delete(project, listOf("a", "folder"))
        assertTrue(result.partial)
        assertEquals(listOf("folder"), result.changed.map { it.path })
        assertRequest(server, "DELETE", "/api/project/app/files/delete", """{"path":"a"}""")
        assertRequest(server, "DELETE", "/api/project/app/files/delete", """{"path":"folder"}""")
    }

    @Test fun `busy rejects overlapping writes and finishing paste preserves newer clipboard`() = withServer { server, files ->
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                check(release.await(4, TimeUnit.SECONDS))
                return ok("""{"moved":{"from":"a","to":"out/a"}}""")
            }
        }
        files.setProjectClipboard(project, listOf("a"), FileClipboardOperation.CUT)
        val paste = async { files.paste(project, "out") }
        try {
            request(server)
            assertTrue(files.state.value.busy)
            try { files.create(project, "other"); fail("overlapping mutation") } catch (_: IllegalStateException) { }
            assertEquals(1, server.requestCount)
            files.setProjectClipboard(project, listOf("new"), FileClipboardOperation.COPY)
            release.countDown()
            assertTrue(paste.await().succeeded)
            assertEquals(listOf("/work/app/new"), files.state.value.clipboard!!.paths)
            assertFalse(files.state.value.busy)
        } finally { release.countDown(); paste.cancelAndJoin() }
    }

    @Test fun `cancellation preserves confirmed partial work and releases busy without swallowing cancellation`() = withServer { server, files ->
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.target!!.endsWith("/write")) check(release.await(4, TimeUnit.SECONDS))
                return ok()
            }
        }
        val create = launch { files.create(project, "a", content = "text") }
        try {
            request(server); request(server)
            assertEquals(FileMutationOperation.CREATE_FILE, files.state.value.report!!.changed.single().operation)
            create.cancelAndJoin()
            assertTrue(create.isCancelled)
            val report = files.state.value.report!!
            assertTrue(report.cancelled)
            assertTrue(report.partial)
            assertEquals(1, report.changed.size)
            assertFalse(files.state.value.busy)
            assertEquals(2, server.requestCount)
        } finally { release.countDown(); create.cancelAndJoin() }
    }

    @Test fun `a request nobody queued an answer for fails at once instead of waiting out the read timeout`() = withServer(timeoutMs = 5_000) { server, files ->
        val result = files.delete(project, listOf("a"))
        assertFalse(result.succeeded)
        assertEquals(1, server.requestCount)
    }
}
