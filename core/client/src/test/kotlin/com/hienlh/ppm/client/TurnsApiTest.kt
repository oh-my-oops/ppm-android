package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.TurnLinkTarget
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.TimeUnit

/** The chat socket's per-tab id, the turn routes and the settings echo header. */
class TurnsApiTest {
    private val sid = "0f8e2a4c-1111-4000-8000-000000000001"
    private val tid = "7a1b9c3d-2222-4000-8000-000000000002"

    private fun json(body: String, status: Int = 200) =
        MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body(body).build()

    private fun <T> withServer(block: suspend (MockWebServer, ApiClient) -> T): T = runBlocking {
        val server = MockWebServer()
        server.start()
        try { block(server, ApiClient(server.url("/"), "t")) } finally { server.close() }
    }

    @Test fun `the chat socket names its tab when it has a valid id`() {
        val api = ApiClient(okhttp3.HttpUrl.Builder().scheme("http").host("h").port(8080).build(), "t")
        val id = ChatClientId.mint()
        assertTrue(ChatClientId.isValid(id))
        assertTrue(api.chatSocketUrl("p", "s", "claude", id).contains("clientId=$id"))
        assertFalse(api.chatSocketUrl("p", "s", "claude").contains("clientId"))
        // A malformed id is left out rather than sent.
        assertFalse(api.chatSocketUrl("p", "s", "claude", "bad id").contains("clientId"))
        assertFalse(ChatClientId.isValid("short"))
    }

    @Test fun `a message's turn is resolved by its record uuid`() = withServer { server, api ->
        server.enqueue(json("""{"ok":true,"data":{"resolved":{"turnId":"$tid","n":3}}}"""))
        assertEquals(tid, api.resolveTurnId("a b", sid, "claude", "uuid-1"))
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/api/project/a%20b/chat/sessions/$sid/turns", request.url.encodedPath)
        assertEquals("claude", request.url.queryParameter("providerId"))
        assertEquals("uuid-1", request.url.queryParameter("resolve"))
        // Nothing known, or an id that is not one: null.
        server.enqueue(json("""{"ok":true,"data":{"resolved":null}}"""))
        assertNull(api.resolveTurnId("p", sid, "claude", "uuid-2"))
        server.enqueue(json("""{"ok":true,"data":{"resolved":{"turnId":"no"}}}"""))
        assertNull(api.resolveTurnId("p", sid, "claude", "uuid-3"))
    }

    @Test fun `a pasted link resolves to the reference its chip sends`() = withServer { server, api ->
        server.enqueue(json("""{"ok":true,"data":{"sessionId":"$sid","n":4,"startedAt":"2026-11-18T08:00:00.000Z","prompt":"hello","sessionTitle":"Login"}}"""))
        val ref = api.turnReference(TurnLinkTarget("a/b", "codex", sid, "u:$tid"))!!
        assertEquals("Login", ref.title)
        assertEquals("a/b", ref.projectName)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/api/project/a%2Fb/chat/sessions/$sid/turns/u:$tid", request.url.encodedPath.replace("%3A", ":"))
        assertEquals("codex", request.url.queryParameter("providerId"))
    }

    @Test fun `a turn the session no longer has is gone, any other failure is not`() = withServer { server, api ->
        server.enqueue(json("""{"ok":false,"error":"Turn not found","code":"not_found"}""", 404))
        try {
            api.turnReference(TurnLinkTarget("p", "claude", sid, tid))
            fail("expected a refusal")
        } catch (error: PpmError.Server) {
            assertTrue(TurnErrorCodes.gone(error))
        }
        server.enqueue(json("""{"ok":false,"error":"boom"}""", 500))
        try {
            api.turnReference(TurnLinkTarget("p", "claude", sid, tid))
            fail("expected a refusal")
        } catch (error: PpmError.Server) {
            assertFalse(TurnErrorCodes.gone(error))
        }
    }

    @Test fun `a history window page asks around, after or before a turn`() = withServer { server, api ->
        val page = """{"ok":true,"data":{"sessionId":"$sid","turns":[{"turnId":"$tid","n":2,"status":"done"}],
            |"messages":[{"id":"t:rollout-0","role":"user","content":"hi","turnId":"$tid","turnN":2}],
            |"hasOlder":true,"hasNewer":false,"latestTurnId":"$tid"}}""".trimMargin()
        server.enqueue(json(page))
        val around = api.turnWindow("p", sid, "claude", TurnWindowQuery.Around(tid))
        assertEquals(listOf(WindowTurn(tid, 2, false)), around.turns)
        assertEquals(tid, around.messages.single().turnId)
        assertTrue(around.hasOlder)
        assertEquals(tid, around.latestTurnId)
        assertEquals(tid, server.takeRequest(2, TimeUnit.SECONDS)!!.url.queryParameter("turn"))
        server.enqueue(json(page))
        api.turnWindow("p", sid, "claude", TurnWindowQuery.After(tid, 3))
        server.takeRequest(2, TimeUnit.SECONDS)!!.url.let {
            assertEquals("/api/project/p/chat/sessions/$sid/turn-window", it.encodedPath)
            assertEquals(tid, it.queryParameter("afterTurn"))
            assertEquals("3", it.queryParameter("count"))
        }
        server.enqueue(json(page))
        api.turnWindow("p", sid, "claude", TurnWindowQuery.Before(tid, 3))
        assertEquals(tid, server.takeRequest(2, TimeUnit.SECONDS)!!.url.queryParameter("beforeTurn"))
    }

    @Test fun `a settings write names this device, a read does not`() = withServer { server, api ->
        server.enqueue(json("""{"ok":true,"data":{}}"""))
        api.extensionContributions()
        assertNull(server.takeRequest(2, TimeUnit.SECONDS)!!.headers[PpmHttp.SETTINGS_CLIENT_HEADER])
        server.enqueue(json("""{"ok":true,"data":{"label":"x"}}"""))
        api.renameAccount("a", "x")
        assertEquals(PpmHttp.SETTINGS_CLIENT_ID, server.takeRequest(2, TimeUnit.SECONDS)!!.headers[PpmHttp.SETTINGS_CLIENT_HEADER])
    }
}
