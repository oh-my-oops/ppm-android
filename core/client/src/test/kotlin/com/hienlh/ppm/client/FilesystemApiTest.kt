package com.hienlh.ppm.client

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class FilesystemApiTest {
    private fun response(body: String, status: Int = 200) = MockResponse.Builder().code(status)
        .addHeader("Content-Type", "application/json").body(body).build()

    @Test fun `browse retains truncation symlinks and the host separator with encoded paths`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(response("""{"ok":true,"data":{"entries":[{"name":"linked","path":"C:\\tmp\\linked","type":"directory","kind":"symlink","size":12,"modified":"2026-01-01T00:00:00Z"}],"current":"C:\\tmp","parent":"C:\\","breadcrumbs":[{"name":"C:","path":"C:\\"}],"sep":"\\","truncated":true}}"""))
        server.start()
        try {
            val result = ApiClient(server.url("/"), "test-token").fsBrowse("C:\\a b & c", true)
            assertEquals("\\", result.separator); assertTrue(result.truncated)
            assertTrue(result.entries.single().isDirectory); assertEquals("symlink", result.entries.single().kind)
            val request = server.takeRequest()
            assertEquals("/api/fs/browse?path=C%3A%5Ca%20b%20%26%20c&showHidden=true", request.target)
            assertEquals("Bearer test-token", request.headers["Authorization"])
        } finally { server.close() }
    }

    @Test fun `mkdir opts out of git and delete opts into trash`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(response("""{"ok":true,"data":{"path":"/tmp/new","gitInitialized":false}}"""))
        server.enqueue(response("""{"ok":true,"data":{"removed":"/tmp/new","trashed":true,"permanent":false}}"""))
        server.start()
        try {
            val api = ApiClient(server.url("/"), "test-token")
            api.fsMkdir("/tmp/new")
            assertEquals("""{"path":"/tmp/new","gitInit":false}""", server.takeRequest().body!!.utf8())
            val result = api.fsDelete("/tmp/new")
            assertTrue(result.trashed); assertFalse(result.permanent)
            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("""{"path":"/tmp/new","permanent":false}""", request.body!!.utf8())
        } finally { server.close() }
    }

    @Test fun `filesystem failures preserve machine code and remediation hint`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(response("""{"ok":false,"error":"Access denied","code":"EACCES","hint":"Grant Full Disk Access"}""", 403))
        server.start()
        try {
            try { ApiClient(server.url("/"), "test-token").fsBrowse("/private"); fail("expected refusal") }
            catch (error: PpmError.Server) {
                assertEquals(403, error.status); assertEquals("EACCES", error.code)
                assertEquals("Grant Full Disk Access", error.hint)
            }
        } finally { server.close() }
    }

    @Test fun `host facts preserve known folders pins drives and nonfatal warnings`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(response("""{"ok":true,"data":{"platform":"linux","sep":"/","homedir":"/home/test","hostname":"ppm","drives":[{"name":"Root","path":"/","kind":"fixed"}],"knownFolders":[{"key":"documents","name":"Documents","path":"/home/test/Documents"}],"pinned":[{"name":"Work","path":"/srv/work","source":"gtk"}],"warnings":["Bookmarks unavailable"]}}"""))
        server.start()
        try {
            val host = ApiClient(server.url("/"), "test-token").hostFilesystem(true)
            assertEquals("documents", host.knownFolders.single().key)
            assertEquals("gtk", host.pinned.single().source)
            assertEquals("fixed", host.drives.single().kind)
            assertEquals(listOf("Bookmarks unavailable"), host.warnings)
            assertEquals("/api/system/host?refresh=true", server.takeRequest().target)
        } finally { server.close() }
    }
}
