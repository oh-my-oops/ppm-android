package com.hienlh.ppm.client

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.TimeUnit

/** `GET /api/settings/storage` and `GET /api/settings/query-audit`, as Settings → Storage reads them. */
class StorageApiTest {
    private fun json(body: String, status: Int = 200) =
        MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body(body).build()

    private fun <T> withServer(block: suspend (MockWebServer, ApiClient) -> T): T = runBlocking {
        val server = MockWebServer()
        server.start()
        try { block(server, ApiClient(server.url("/"), "t")) } finally { server.close() }
    }

    @Test fun `the overview is read with a fractional mtime and other databases`() = withServer { server, api ->
        server.enqueue(
            json(
                """{"ok":true,"data":{"appDb":{"path":"/h/.ppm/ppm.db","sizeBytes":5242880},
                "backups":{"dir":"/h/.ppm-backups","dbStem":"ppm","count":12,"totalBytes":62914560,
                  "newest":{"name":"ppm-2026-11-11.db","mtimeMs":1762830000123.75},
                  "others":[{"dbStem":"ppm.dev","count":3,"totalBytes":1048576}]},
                "sessionTrace":{"path":"/h/.ppm/session-trace.db","sizeBytes":104857600,"oldestTs":null,"retention_days":14,"max_size_mb":500}}}""",
            ),
        )
        val overview = api.storageOverview()
        assertEquals("/api/settings/storage", server.takeRequest(2, TimeUnit.SECONDS)!!.url.encodedPath)
        assertEquals(StorageOverview(
            appDbPath = "/h/.ppm/ppm.db",
            appDbSizeBytes = 5242880,
            backups = StorageBackups(
                "/h/.ppm-backups", "ppm", 12, 62914560,
                StorageNewestBackup("ppm-2026-11-11.db", 1762830000123),
                listOf(StorageOtherBackups("ppm.dev", 3, 1048576)),
            ),
            sessionTrace = StorageSessionTrace("/h/.ppm/session-trace.db", 104857600, null, 14, 500),
        ), overview)
    }

    @Test fun `no snapshot yet is no newest`() = withServer { server, api ->
        server.enqueue(json("""{"ok":true,"data":{"appDb":{"path":"a","sizeBytes":1},"backups":{"dir":"b","dbStem":"ppm","count":0,"totalBytes":0,"newest":null,"others":[]},"sessionTrace":{"path":"c","sizeBytes":0,"oldestTs":1762830000000,"retention_days":1,"max_size_mb":50}}}"""))
        val overview = api.storageOverview()
        assertNull(overview.backups.newest)
        assertEquals(1762830000000, overview.sessionTrace.oldestTs)
    }

    @Test fun `a server without the route is an error, not an empty overview`() = withServer { server, api ->
        server.enqueue(json("""{"ok":false,"error":"Not found"}""", 404))
        try {
            api.storageOverview()
            fail("expected a refusal")
        } catch (error: PpmError.Server) {
            assertEquals(404, error.status)
        }
    }

    @Test fun `the Query Audit usage is read`() = withServer { server, api ->
        server.enqueue(json("""{"ok":true,"data":{"retention_days":30,"max_size_mb":100,"size_bytes":2048,"entry_count":1234}}"""))
        assertEquals(QueryAuditUsage(30, 100, 2048, 1234), api.queryAuditUsage())
        assertEquals("/api/settings/query-audit", server.takeRequest(2, TimeUnit.SECONDS)!!.url.encodedPath)
    }
}
