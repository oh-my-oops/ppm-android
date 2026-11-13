package com.hienlh.ppm.client

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Spending a free reset (`claude-limit-resets-button.tsx`, `codex-reset-credit-button.tsx`) and
 * Settings → Memory's routes (`api-memory.ts`, `memory-embedding-source.tsx`), against a fake server.
 */
class ResetsAndMemoryApiTest {
    private fun json(body: String, status: Int = 200) =
        MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body(body).build()

    private fun <T> withServer(block: suspend (MockWebServer, ApiClient) -> T): T = runBlocking {
        val server = MockWebServer()
        server.start()
        try { block(server, ApiClient(server.url("/"), "t")) } finally { server.close() }
    }

    @Test fun `a Claude reset is claimed by its grant id`() = withServer { server, api ->
        server.enqueue(json("""{"ok":true,"data":{"result":"reset","kind":"full","resetsLeft":1,"weeklyResetsAt":"2026-11-16T05:00:00.000Z","usage":{}}}"""))
        val answer = api.spendClaudeLimitReset("a b", "grant_1")
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/api/accounts/a%20b/limit-reset", request.url.encodedPath)
        assertEquals("""{"grantId":"grant_1"}""", request.body?.utf8())
        assertEquals("reset", answer.result)
        assertEquals("full", answer.kind)
        assertEquals(1, answer.resetsLeft)
    }

    @Test fun `a refused Claude reset brings the server's reason`() = withServer { server, api ->
        server.enqueue(json("""{"ok":false,"error":"That reset can't be used right now."}""", 409))
        try {
            api.spendClaudeLimitReset("acc", "g")
            fail("expected a refusal")
        } catch (e: PpmError.Server) {
            assertEquals("That reset can't be used right now.", e.message)
        }
    }

    @Test fun `a Codex reset names the credit picked, or none`() = withServer { server, api ->
        server.enqueue(json("""{"ok":true,"data":{"outcome":"reset"}}"""))
        server.enqueue(json("""{"ok":true,"data":{"outcome":"nothingToReset"}}"""))
        assertEquals("reset", api.spendCodexResetCredit("c1", "credit-9"))
        assertEquals("nothingToReset", api.spendCodexResetCredit("c1"))
        val picked = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/api/codex-accounts/c1/reset-credit", picked.url.encodedPath)
        assertEquals("""{"creditId":"credit-9"}""", picked.body?.utf8())
        assertEquals("{}", server.takeRequest(2, TimeUnit.SECONDS)!!.body?.utf8())
    }

    @Test fun `memory status is read for the active project`() = withServer { server, api ->
        server.enqueue(
            json(
                """{"ok":true,"data":{"enabled":true,"root":"~/.ppm/memory","counts":{"project":3,"global":1,"archived":2},
                "providers":{"claude":{"count":3,"syncedAt":"2026-11-13T08:00:00.000Z","error":null,"skipped":[],"redacted":0},
                  "codex":{"count":0,"syncedAt":null,"error":"boom","skipped":[],"redacted":0}},
                "embedding":{"source":"api","has_api_key":true,"api_url":"https://x/v1","api_model":"m","ready":true,"reason":null,
                  "vectors":4,"total":6,"running":true,"install":{"state":"installing","step":"model","bytes":10,"totalBytes":20}}}}""",
            ),
        )
        val status = api.memoryStatus("my app")
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/api/memory/status", request.url.encodedPath)
        assertEquals("my app", request.url.queryParameter("project"))
        assertTrue(status.enabled)
        assertEquals(3, status.projectCount)
        assertEquals(2, status.archivedCount)
        assertEquals("boom", status.providers["codex"]?.error)
        assertEquals("api", status.embedding.source)
        assertTrue(status.embedding.hasApiKey)
        assertEquals(4, status.embedding.vectors)
        assertEquals("installing", status.embedding.install.state)
        assertEquals(20L, status.embedding.install.totalBytes)
    }

    @Test fun `memory settings, sync, install and test go to their routes`() = withServer { server, api ->
        server.enqueue(json("""{"ok":true,"data":{"enabled":false,"embedding":{"source":"off","has_api_key":false}}}"""))
        server.enqueue(json("""{"ok":true,"data":{"results":[]}}"""))
        server.enqueue(json("""{"ok":true,"data":{"enabled":true,"embedding":{"source":"local","has_api_key":false}}}"""))
        server.enqueue(json("""{"ok":true,"data":{"state":"installing","step":"packages","bytes":0,"totalBytes":0}}"""))
        server.enqueue(json("""{"ok":true,"data":{"state":"not-installed"}}"""))
        server.enqueue(json("""{"ok":true,"data":{"ok":false,"error":"401 [REDACTED]"}}"""))

        assertFalse(api.setMemoryEnabled(false))
        api.syncMemoryNow()
        api.setMemoryEmbedding(buildJsonObject { put("source", "local") })
        assertEquals("installing", api.installMemoryModel().state)
        assertEquals("not-installed", api.removeMemoryModel().state)
        val test = api.testMemoryEmbedding(buildJsonObject { put("source", "api"); put("api_url", "https://x/v1"); put("api_model", "m") })
        assertFalse(test.ok)
        assertEquals("401 [REDACTED]", test.error)

        val seen = List(6) { server.takeRequest(2, TimeUnit.SECONDS)!! }
        assertEquals(
            listOf(
                "PUT /api/memory/settings",
                "POST /api/memory/sync",
                "PUT /api/memory/settings",
                "POST /api/memory/embedding/install",
                "DELETE /api/memory/embedding/install",
                "POST /api/memory/embedding/test",
            ),
            seen.map { "${it.method} ${it.url.encodedPath}" },
        )
        assertEquals("""{"enabled":false}""", seen[0].body?.utf8())
        assertEquals("""{"embedding":{"source":"local"}}""", seen[2].body?.utf8())
        assertEquals("""{"source":"api","api_url":"https://x/v1","api_model":"m"}""", seen[5].body?.utf8())
    }
}
