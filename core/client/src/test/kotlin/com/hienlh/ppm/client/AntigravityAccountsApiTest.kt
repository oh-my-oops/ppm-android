package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.AntigravityAccount
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** MockWebServer contract tests for `/api/antigravity-accounts` routes (`src/server/routes/antigravity-accounts.ts`). */
class AntigravityAccountsApiTest {
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

    private val accountsPayload = """
        {"ok":true,"data":{
          "strategy":"lowest-usage",
          "accounts":[
            {"id":"default","label":"Alice","email":"alice@example.com","planType":"Pro","home":"","status":"active","addedAt":"2026-11-09 10:00:00"},
            {"id":"ag-1","label":"Work","email":"work@example.com","planType":null,"home":"/home/u/.ppm/antigravity-accounts/ag-1","status":"disabled","addedAt":"2026-11-09 11:00:00"}
          ]
        }}
    """.trimIndent()

    @Test
    fun `lists accounts, overview strategy, and per-account usage buckets`() = withServer(
        MockResponse.Builder().body(accountsPayload).build(),
        MockResponse.Builder().body(accountsPayload).build(),
        MockResponse.Builder().body(
            """
            {"ok":true,"data":{
              "default":{
                "fiveHour":0.25,
                "sevenDay":0.4,
                "lastFetchedAt":"2026-11-09T12:00:00.000Z",
                "session":{"utilization":0.25,"resetsAt":"2026-11-09T16:00:00.000Z","resetsInMinutes":null,"resetsInHours":null,"windowHours":5},
                "weekly":{"utilization":0.4,"resetsAt":"2026-11-15T12:00:00.000Z","resetsInMinutes":null,"resetsInHours":null,"windowHours":168},
                "weeklyScoped":[
                  {"label":"Claude · 5-Hour","utilization":0.6,"resetsAt":"2026-11-09T15:00:00.000Z","resetsInMinutes":null,"resetsInHours":null,"windowHours":5}
                ]
              }
            }}
            """.trimIndent(),
        ).build(),
    ) { server, api ->
        val overview = api.antigravityAccountsOverview()
        assertEquals("lowest-usage", overview["strategy"].string)
        assertEquals("GET /api/antigravity-accounts", server.takeRequest().let { "${it.method} ${it.target}" })

        val accounts = api.antigravityAccounts()
        assertEquals(2, accounts.size)
        assertEquals(
            AntigravityAccount("default", "Alice", "alice@example.com", "Pro", "", "active", "2026-11-09 10:00:00"),
            accounts[0],
        )
        assertTrue(accounts[0].isDefault)
        assertEquals("Alice · Pro", accounts[0].displayLabel)
        assertFalse(accounts[1].isDefault)
        assertEquals("Work", accounts[1].displayLabel)
        assertEquals("GET /api/antigravity-accounts", server.takeRequest().let { "${it.method} ${it.target}" })

        val usages = api.antigravityUsages()
        assertEquals("GET /api/antigravity-accounts/usage", server.takeRequest().let { "${it.method} ${it.target}" })
        val u = usages.getValue("default")
        assertEquals(0.25, u.fiveHour!!, 0.0)
        assertEquals("Claude · 5-Hour", u.weeklyScoped.single().label)
    }

    @Test
    fun `updates strategy, patches status, and deletes an account`() = withServer(
        MockResponse.Builder().body("""{"ok":true,"data":{"strategy":"fill-first"}}""").build(),
        MockResponse.Builder().body(
            """{"ok":true,"data":{"id":"ag/1","label":"Work","email":null,"planType":"Google AI Ultra","home":"/h","status":"disabled","addedAt":"2026-11-09"}}""",
        ).build(),
        MockResponse.Builder().body("""{"ok":true,"data":{"removed":true}}""").build(),
    ) { server, api ->
        val stratResp = api.antigravitySetStrategy("fill-first")
        assertEquals("fill-first", stratResp["strategy"].string)
        val stratReq = server.takeRequest()
        assertEquals("PUT /api/antigravity-accounts/strategy", "${stratReq.method} ${stratReq.target}")
        assertEquals("""{"strategy":"fill-first"}""", stratReq.body?.utf8())

        val updated = api.setAntigravityAccountStatus("ag/1", "disabled")
        assertEquals("disabled", updated?.status)
        assertEquals("Work · Google AI Ultra", updated?.displayLabel)
        val patchReq = server.takeRequest()
        assertEquals("PATCH /api/antigravity-accounts/ag%2F1", "${patchReq.method} ${patchReq.target}")
        assertEquals("""{"status":"disabled"}""", patchReq.body?.utf8())

        api.antigravityDeleteAccount("ag/1")
        val delReq = server.takeRequest()
        assertEquals("DELETE /api/antigravity-accounts/ag%2F1", "${delReq.method} ${delReq.target}")
    }

    @Test
    fun `drives the Google OAuth PKCE login lifecycle and surfaces server errors`() = withServer(
        MockResponse.Builder().body("""{"ok":true,"data":{"id":"flow-1","authUrl":"https://accounts.google.com/o/oauth2/auth?client_id=1"}}""").build(),
        MockResponse.Builder().body("""{"ok":true,"data":{"state":"pending"}}""").build(),
        MockResponse.Builder().body(
            """{"ok":true,"data":{"state":"done","account":{"id":"ag-2","label":"Personal","email":"p@g.com","planType":"Pro","home":"/h2","status":"active","addedAt":"now"}}}""",
        ).build(),
        MockResponse.Builder().code(400).body("""{"ok":false,"error":"Invalid authorization code"}""").build(),
        MockResponse.Builder().body("""{"ok":true,"data":{"cancelled":true}}""").build(),
    ) { server, api ->
        val started = api.antigravityStartLogin(label = "Personal", accountId = "ag-2")
        assertEquals("flow-1", started["id"].string)
        val startReq = server.takeRequest()
        assertEquals("POST /api/antigravity-accounts/login", "${startReq.method} ${startReq.target}")
        assertEquals("""{"label":"Personal","accountId":"ag-2"}""", startReq.body?.utf8())

        val pending = api.antigravityLoginStatus("flow-1")
        assertEquals("pending", pending["state"].string)
        assertEquals("GET /api/antigravity-accounts/login/flow-1/status", server.takeRequest().let { "${it.method} ${it.target}" })

        val done = api.antigravitySubmitLoginCode("flow-1", "https://antigravity.google/oauth-callback?code=4/abc")
        assertEquals("done", done["state"].string)
        assertEquals("ag-2", done["account"]["id"].string)
        val cbReq = server.takeRequest()
        assertEquals("POST /api/antigravity-accounts/login/flow-1/callback", "${cbReq.method} ${cbReq.target}")
        assertEquals("""{"code":"https://antigravity.google/oauth-callback?code=4/abc"}""", cbReq.body?.utf8())

        try {
            api.antigravitySubmitLoginCode("flow-1", "bad")
            fail("Expected PpmError.Server")
        } catch (e: PpmError.Server) {
            assertEquals(400, e.status)
            assertEquals("Invalid authorization code", e.message)
        }
        server.takeRequest()

        api.antigravityCancelLogin("flow-1")
        assertEquals("DELETE /api/antigravity-accounts/login/flow-1", server.takeRequest().let { "${it.method} ${it.target}" })
    }
}
