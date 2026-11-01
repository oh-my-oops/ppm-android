package com.hienlh.ppm.state.osexplorer

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.state.files.FileClipboardOperation
import com.hienlh.ppm.state.files.FileMutationOperation
import com.hienlh.ppm.state.files.FileMutationOutcome
import com.hienlh.ppm.state.files.FileMutationReport
import com.hienlh.ppm.state.files.FileMutations
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HostFileActionsTest {
    private fun ok(data: String = "true") = MockResponse.Builder().addHeader("Content-Type", "application/json")
        .body("""{"ok":true,"data":""" + data + "}").build()
    private fun error(code: String, status: Int = 409, hint: String? = null) = MockResponse.Builder().code(status)
        .addHeader("Content-Type", "application/json").body(buildJsonObject {
            put("ok", false); put("error", code); put("code", code)
            if (hint != null) put("hint", hint)
        }.toString()).build()
    private fun transfer(source: String, destination: String, removed: Boolean? = null) = ok(buildJsonObject {
        put("source", source); put("destination", destination)
        if (removed != null) { put("crossDevice", true); put("sourceRemoved", removed) }
    }.toString())
    private fun removed(path: String, permanent: Boolean = false) = ok(buildJsonObject {
        put("removed", path); put("permanent", permanent); put("trashed", !permanent)
    }.toString())
    private fun exists(path: String) = ok(buildJsonObject { put("path", path); put("kind", "file") }.toString())
    private class Context(val server: MockWebServer, val actions: HostFileActions, val shared: FileMutations,
                          val changes: MutableList<FileMutationReport>)
    private fun withActions(separator: String = "/", platform: String? = null,
                            action: suspend CoroutineScope.(Context) -> Unit) = runBlocking {
        val server = MockWebServer()
        server.start()
        val api = ApiClient(server.url("/"), "secret")
        val shared = FileMutations(api)
        val changes = mutableListOf<FileMutationReport>()
        val actions = HostFileActions(api, shared, separator, platform, changes::add)
        try { action(Context(server, actions, shared, changes)) } finally { withContext(Dispatchers.IO) { server.close() } }
    }
    private suspend fun request(server: MockWebServer): RecordedRequest = withContext(Dispatchers.IO) {
        checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
    }
    private suspend fun assertRequest(server: MockWebServer, method: String, target: String, body: String) {
        val request = request(server)
        assertEquals(method, request.method); assertEquals(target, request.target)
        assertEquals("Bearer secret", request.headers["Authorization"])
        assertEquals(WireJson.parse(body), WireJson.parse(request.body!!.utf8()))
    }

    @Test fun createRenameAndHostNameValidationUseExistingApis() = withActions("\\", "win32") { c ->
        c.server.enqueue(ok()); c.server.enqueue(ok())
        c.server.enqueue(ok("""{"to":"C:\\d\\renamed.txt"}"""))
        assertEquals(1, c.actions.create("C:\\d", "folder", true).succeeded)
        assertRequest(c.server, "POST", "/api/fs/mkdir", """{"path":"C:\\d\\folder","gitInit":false}""")
        assertEquals(1, c.actions.create("C:\\d", "a.txt").succeeded)
        assertRequest(c.server, "POST", "/api/fs/touch", """{"path":"C:\\d\\a.txt"}""")
        val rename = c.actions.rename("C:\\d\\a.txt", "renamed.txt")
        assertEquals("C:\\d\\renamed.txt", rename.report.changed.single().destination)
        assertRequest(c.server, "POST", "/api/fs/rename", """{"path":"C:\\d\\a.txt","newName":"renamed.txt"}""")
        for (name in listOf("CON.txt", "bad?", "trailing.", "x/y", "")) assertEquals(1, c.actions.create("C:\\d", name).failed)
        assertEquals(3, c.server.requestCount)
        assertTrue(c.changes.all { it.affectedDirectories == setOf("C:\\d") })
        assertFalse(c.actions.state.value.busy)
    }

    @Test fun sameFolderMoveSkipsAndCopyFindsSuffixWithoutPrompt() = withActions { c ->
        val moved = c.actions.transfer(listOf("/d/a.txt"), "/d", HostTransferOperation.MOVE)
        assertEquals(listOf("/d/a.txt"), moved.skipped)
        assertEquals(0, c.server.requestCount)
        c.server.enqueue(exists("/d/a (2).txt"))
        c.server.enqueue(error("EACCES", 403)) // Permission denied is occupied, never a free name.
        c.server.enqueue(error("ENOENT", 404))
        c.server.enqueue(transfer("/d/a.txt", "/d/a (4).txt"))
        val copied = c.actions.transfer(listOf("/d/a.txt"), "/d", HostTransferOperation.COPY,
            HostTransferPrompts(resolveCollision = { fail("same-folder copy must not prompt"); HostCollisionChoice.SKIP }))
        assertEquals(1, copied.succeeded)
        assertEquals("/d/a (4).txt", copied.report.changed.single().destination)
        assertEquals("/api/fs/stat?path=%2Fd%2Fa%20%282%29.txt", request(c.server).target)
        request(c.server); request(c.server)
        assertRequest(c.server, "POST", "/api/fs/copy", """{"source":"/d/a.txt","destination":"/d/a (4).txt"}""")
        assertEquals(2, c.changes.size)
    }

    @Test fun freeNameTriesTwoThroughNinetyNineAndDotfilesKeepWholeName() = withActions { c ->
        c.server.enqueue(error("ENOENT", 404))
        assertEquals(".env (2)", c.actions.freeName("/d", ".env"))
        assertEquals("/api/fs/stat?path=%2Fd%2F.env%20%282%29", request(c.server).target)
        c.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = exists("/occupied")
        }
        val result = c.actions.transfer(listOf("/d/archive.tar.gz"), "/d", HostTransferOperation.COPY)
        assertEquals(1, result.failed)
        assertEquals("No free name available", result.report.failures.single().error)
        assertEquals(99, c.server.requestCount) // Previous dotfile check + 98 numbered candidates.
        assertEquals("/api/fs/stat?path=%2Fd%2Farchive.tar%20%282%29.gz", request(c.server).target)
        repeat(96) { request(c.server) }
        assertEquals("/api/fs/stat?path=%2Fd%2Farchive.tar%20%2899%29.gz", request(c.server).target)
    }

    @Test fun collisionsResolvePerItemAndBatchHooksBoundUiApplyAll() = withActions { c ->
        c.server.enqueue(error("EEXIST")); c.server.enqueue(error("EEXIST"))
        c.server.enqueue(error("ENOENT", 404)); c.server.enqueue(transfer("/src/b", "/dst/b (2)"))
        val calls = mutableListOf<String>()
        var count = 0
        val prompts = HostTransferPrompts(
            resolveCollision = { calls += it.name; if (count++ == 0) HostCollisionChoice.SKIP else HostCollisionChoice.KEEP_BOTH },
            startBatch = { calls += "start" }, endBatch = { calls += "end" },
        )
        val result = c.actions.transfer(listOf("/src/a", "/src/b"), "/dst", HostTransferOperation.COPY, prompts)
        assertEquals(listOf("start", "a", "b", "end"), calls)
        assertEquals(listOf("/src/a"), result.skipped)
        assertEquals(1, result.succeeded); assertEquals(0, result.failed)
        assertEquals(setOf("/src", "/dst"), result.report.affectedDirectories)
        assertEquals(result.report, c.changes.single())
        assertEquals(4, c.server.requestCount)
    }

    @Test fun replaceTrashesFirstAndRecordsRemovedDestinationIfReplacementFails() = withActions { c ->
        c.server.enqueue(error("EEXIST")); c.server.enqueue(removed("/dst/a")); c.server.enqueue(error("EACCES", 403, "Check host permissions"))
        val result = c.actions.transfer(listOf("/src/a"), "/dst", HostTransferOperation.COPY,
            HostTransferPrompts(resolveCollision = { HostCollisionChoice.REPLACE }))
        assertTrue(result.report.partial)
        assertEquals(FileMutationOperation.DELETE, result.report.changed.single().operation)
        assertEquals("/dst/a", result.report.changed.single().path)
        assertEquals("a was removed but the replacement could not be written: Check host permissions", result.report.failures.single().error)
        assertEquals(0, result.succeeded)
        assertRequest(c.server, "POST", "/api/fs/copy", """{"source":"/src/a","destination":"/dst/a"}""")
        assertRequest(c.server, "DELETE", "/api/fs/delete", """{"path":"/dst/a","permanent":false}""")
        assertRequest(c.server, "POST", "/api/fs/copy", """{"source":"/src/a","destination":"/dst/a"}""")
        assertEquals(result.report, c.changes.single())
    }

    @Test fun noTrashReplaceRequiresSeparateConfirmationAndCanBeDeclined() = withActions { c ->
        c.server.enqueue(error("EEXIST")); c.server.enqueue(error("NO_TRASH"))
        var confirms = 0
        val declined = c.actions.transfer(listOf("/src/a"), "/dst", HostTransferOperation.COPY, HostTransferPrompts(
            resolveCollision = { HostCollisionChoice.REPLACE },
            confirmPermanentOverwrite = { confirms++; assertEquals("/dst/a", it.destination); false },
        ))
        assertEquals(1, confirms); assertEquals(listOf("/src/a"), declined.skipped)
        assertEquals(2, c.server.requestCount)
        request(c.server); request(c.server)
        c.server.enqueue(error("EEXIST")); c.server.enqueue(error("NO_TRASH"))
        c.server.enqueue(removed("/dst/a", true)); c.server.enqueue(transfer("/src/a", "/dst/a"))
        val approved = c.actions.transfer(listOf("/src/a"), "/dst", HostTransferOperation.COPY, HostTransferPrompts(
            resolveCollision = { HostCollisionChoice.REPLACE }, confirmPermanentOverwrite = { true },
        ))
        assertEquals(1, approved.succeeded); assertTrue(approved.report.succeeded)
        request(c.server)
        assertRequest(c.server, "DELETE", "/api/fs/delete", """{"path":"/dst/a","permanent":false}""")
        assertRequest(c.server, "DELETE", "/api/fs/delete", """{"path":"/dst/a","permanent":true}""")
        request(c.server)
    }

    @Test fun trashReturnsOnlyNoTrashPathsForLaterPermanentDeleteAndContinuesFailures() = withActions { c ->
        c.server.enqueue(error("NO_TRASH")); c.server.enqueue(removed("/d/b")); c.server.enqueue(error("EPROTECTED", 403))
        val result = c.actions.delete(listOf("/d/a", "/d/b", "/d/c"))
        assertEquals(listOf("/d/a"), result.needsPermanent)
        assertEquals(1, result.succeeded); assertTrue(result.report.partial)
        assertEquals(listOf("NO_TRASH", "EPROTECTED"), result.report.failures.map { it.code })
        assertEquals(3, c.server.requestCount)
        repeat(3) { request(c.server) }
        c.server.enqueue(removed("/d/a", true))
        assertEquals(1, c.actions.delete(result.needsPermanent, permanent = true).succeeded)
        assertRequest(c.server, "DELETE", "/api/fs/delete", """{"path":"/d/a","permanent":true}""")
        assertEquals(2, c.changes.size)
    }

    @Test fun cutRetainsFailuresAndPartialMovesButClearsOnSuccessOrOnlySkips() = withActions { c ->
        c.actions.setClipboard(listOf("/src/a", "/src/b"), FileClipboardOperation.CUT)
        val clipboard = c.shared.state.value.clipboard
        c.server.enqueue(transfer("/src/a", "/dst/a")); c.server.enqueue(error("EACCES", 403))
        assertTrue(c.actions.paste("/dst").report.partial)
        assertSame(clipboard, c.shared.state.value.clipboard)
        c.actions.setClipboard(listOf("/src/c"), FileClipboardOperation.CUT)
        c.server.enqueue(transfer("/src/c", "/dst/c", removed = false))
        val partial = c.actions.paste("/dst")
        assertEquals(FileMutationOutcome.PARTIAL, partial.report.items.single().outcome)
        assertNotNull(c.shared.state.value.clipboard)
        c.actions.setClipboard(listOf("/src/d"), FileClipboardOperation.CUT)
        c.server.enqueue(transfer("/src/d", "/dst/d", removed = true))
        assertEquals(1, c.actions.paste("/dst").succeeded)
        assertNull(c.shared.state.value.clipboard)
        c.actions.setClipboard(listOf("/dst/d"), FileClipboardOperation.CUT)
        assertEquals(1, c.actions.paste("/dst").skipped.size)
        assertNull(c.shared.state.value.clipboard)
    }

    @Test fun clipboardReplacementDuringPromptSurvivesAndCopyIsReusable() = withActions { c ->
        c.actions.setClipboard(listOf("/src/a"), FileClipboardOperation.CUT)
        c.server.enqueue(error("EEXIST"))
        c.actions.paste("/dst", HostTransferPrompts(resolveCollision = {
            c.actions.setClipboard(listOf("/fresh/file"), FileClipboardOperation.COPY)
            HostCollisionChoice.SKIP
        }))
        val newer = c.shared.state.value.clipboard
        assertEquals(listOf("/fresh/file"), newer!!.paths)
        c.server.enqueue(transfer("/fresh/file", "/dst/file"))
        assertEquals(1, c.actions.paste("/dst").succeeded)
        assertSame(newer, c.shared.state.value.clipboard)
        c.actions.setClipboard(emptyList(), FileClipboardOperation.CUT)
        assertSame(newer, c.shared.state.value.clipboard)
    }

    @Test fun windowsSameFolderAndRootParentsUseHostSeparator() = withActions("\\", "win32") { c ->
        c.actions.setClipboard(listOf("C:\\a.txt"), FileClipboardOperation.COPY)
        c.server.enqueue(error("ENOENT", 404)); c.server.enqueue(transfer("C:\\a.txt", "C:\\a (2).txt"))
        val result = c.actions.paste("C:\\")
        assertEquals(setOf("C:\\"), result.report.affectedDirectories)
        assertEquals("C:\\a (2).txt", result.report.changed.single().destination)
        request(c.server)
        assertRequest(c.server, "POST", "/api/fs/copy", """{"source":"C:\\a.txt","destination":"C:\\a (2).txt"}""")
    }

    @Test fun cancellingPromptPublishesKnownWorkInvalidatesAndKeepsCut() = withActions { c ->
        c.actions.setClipboard(listOf("/src/a", "/src/b"), FileClipboardOperation.CUT)
        val clipboard = c.shared.state.value.clipboard
        c.server.enqueue(transfer("/src/a", "/dst/a")); c.server.enqueue(error("EEXIST"))
        val prompted = CompletableDeferred<Unit>()
        var ended = 0
        val job = launch {
            c.actions.paste("/dst", HostTransferPrompts(resolveCollision = { prompted.complete(Unit); awaitCancellation() }, endBatch = { ended++ }))
        }
        withTimeout(3_000) { prompted.await() }
        assertTrue(c.actions.state.value.busy)
        assertEquals(1, c.actions.state.value.result!!.succeeded)
        try { c.actions.create("/d", "overlap"); fail("busy guard") } catch (_: IllegalStateException) { }
        job.cancelAndJoin()
        assertEquals(1, ended)
        val report = c.changes.single()
        assertTrue(report.cancelled); assertTrue(report.partial)
        assertEquals(listOf("/src/a"), report.changed.map { it.path })
        assertEquals(setOf("/src", "/dst"), report.affectedDirectories)
        assertSame(clipboard, c.shared.state.value.clipboard)
        assertFalse(c.actions.state.value.busy)
    }

    @Test fun cancellationAfterTrashKeepsDeletionInReport() = withActions { c ->
        val copying = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        var copies = 0
        c.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "DELETE") return removed("/dst/a")
                if (++copies == 1) return error("EEXIST")
                copying.complete(Unit)
                check(release.await(4, TimeUnit.SECONDS))
                return transfer("/src/a", "/dst/a")
            }
        }
        val job = launch { c.actions.transfer(listOf("/src/a"), "/dst", HostTransferOperation.COPY,
            HostTransferPrompts(resolveCollision = { HostCollisionChoice.REPLACE })) }
        try {
            withTimeout(3_000) { copying.await() }
            job.cancelAndJoin()
            val report = c.changes.single()
            assertTrue(report.cancelled); assertTrue(report.partial)
            assertEquals(FileMutationOperation.DELETE, report.changed.single().operation)
            assertEquals("/dst/a", report.changed.single().path)
            assertFalse(c.actions.state.value.busy)
        } finally { release.countDown(); job.cancelAndJoin() }
    }

    @Test fun cancelFreeNameDoesNotTreatCancellationAsOccupiedAndContinue() = withActions { c ->
        val checking = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        c.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                checking.complete(Unit)
                check(release.await(4, TimeUnit.SECONDS))
                return error("ENOENT", 404)
            }
        }
        val job = launch { c.actions.transfer(listOf("/d/a"), "/d", HostTransferOperation.COPY) }
        try {
            withTimeout(3_000) { checking.await() }
            job.cancelAndJoin()
            assertEquals(1, c.server.requestCount)
            assertTrue(c.changes.single().cancelled)
            assertEquals(setOf("/d"), c.changes.single().affectedDirectories)
        } finally { release.countDown(); job.cancelAndJoin() }
    }
}
