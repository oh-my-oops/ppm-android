package com.hienlh.ppm.client

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

/** `PUT /api/settings/session-trace` (`updateSessionTraceLimits`). */
class SessionTraceLimitsApiTest {
    @Test
    fun `both limits are sent and the trace comes back as it now stands`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse.Builder().body(
                """{"ok":true,"data":{"path":"/p/session-trace.db","sizeBytes":1048576,"oldestTs":1700000000000,"retention_days":14,"max_size_mb":200}}""",
            ).build(),
        )
        server.start()
        try {
            val trace = ApiClient(server.url("/"), "tok").updateSessionTraceLimits(14, 200)
            val sent = server.takeRequest()
            assertEquals("PUT /api/settings/session-trace", sent.method + " " + sent.target)
            assertEquals(Json.parseToJsonElement("""{"retention_days":14,"max_size_mb":200}"""), Json.parseToJsonElement(sent.body!!.utf8()))
            assertEquals(StorageSessionTrace("/p/session-trace.db", 1_048_576, 1_700_000_000_000, 14, 200), trace)
        } finally {
            server.close()
        }
    }
}
