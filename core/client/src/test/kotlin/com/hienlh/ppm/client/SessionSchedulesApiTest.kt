package com.hienlh.ppm.client

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The requests a chat's schedules make (`use-session-schedules.ts`, `session-schedules-chip.tsx`). */
class SessionSchedulesApiTest {
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

    private fun ok(data: String) = MockResponse.Builder().body("""{"ok":true,"data":$data}""").build()

    @Test
    fun `a chat's schedules are asked for by its id, and every schedule without one`() = withServer(ok("[]"), ok("[]"), ok("[]")) { server, api ->
        api.schedulesList(session = "abc_DEF-123")
        assertEquals("GET /api/schedules?session=abc_DEF-123", server.takeRequest().let { "${it.method} ${it.target}" })
        api.schedulesList()
        assertEquals("GET /api/schedules", server.takeRequest().let { "${it.method} ${it.target}" })
        api.schedulesList(enabledOnly = true, session = "abc_DEF-123")
        assertEquals("GET /api/schedules?session=abc_DEF-123&enabledOnly=true", server.takeRequest().let { "${it.method} ${it.target}" })
    }

    @Test
    fun `pause, resume and delete go to the schedule itself, and a refusal carries the server's sentence`() = withServer(
        ok("{}"),
        ok("true"),
        MockResponse.Builder().code(400).body("""{"ok":false,"error":"Its chat cannot take schedules","problems":["x"]}""").build(),
    ) { server, api ->
        api.schedulesUpdate(4, buildJsonObject { put("enabled", false) })
        val patch = server.takeRequest()
        assertEquals("PATCH /api/schedules/4", "${patch.method} ${patch.target}")
        assertEquals("""{"enabled":false}""", patch.body?.utf8())
        api.schedulesDelete(4)
        assertEquals("DELETE /api/schedules/4", server.takeRequest().let { "${it.method} ${it.target}" })
        val refused = runCatching { api.schedulesUpdate(4, buildJsonObject { put("enabled", true) }) }.exceptionOrNull()
        assertTrue(refused is PpmError.Server)
        assertEquals("Its chat cannot take schedules", refused?.message)
    }
}
