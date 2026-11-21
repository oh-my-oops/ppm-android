package com.hienlh.ppm.client

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** The rules "Always in this project" saved, listed and removed from Settings (`/api/settings/approval-rules`). */
class ApprovalRulesApiTest {
    private fun withServer(vararg responses: MockResponse, block: suspend (MockWebServer, ApiClient) -> Unit) = runBlocking {
        val server = MockWebServer()
        responses.forEach(server::enqueue)
        server.start()
        try {
            block(server, ApiClient(server.url("/"), "tok"))
        } finally {
            server.close()
        }
    }

    private fun ok(data: String) = MockResponse.Builder().body("""{"ok":true,"data":$data}""").build()

    @Test
    fun `lists every project's rules, an exact command titled by itself`() = withServer(
        ok(
            """[
              {"projectPath":"/p/a","key":"bash:git status","label":"`git status` commands","savedAt":"2026-11-20T09:00:00.000Z"},
              {"projectPath":"/p/b","key":"bash-exact:ls | wc -l","label":"this exact command","savedAt":"2026-11-20T09:01:00.000Z"},
              {"key":"broken"}
            ]""",
        ),
    ) { server, api ->
        val rules = api.approvalRules()
        assertEquals("/api/settings/approval-rules", server.takeRequest().target)
        assertEquals(2, rules.size)
        assertEquals(SavedApprovalRule("/p/a", "bash:git status", "`git status` commands", "2026-11-20T09:00:00.000Z"), rules[0])
        assertEquals("`git status` commands", rules[0].title)
        assertEquals("ls | wc -l", rules[1].title)
    }

    @Test
    fun `removes a rule by its project and key, in the body`() = withServer(ok("""{"removed":true}""")) { server, api ->
        api.removeApprovalRule("/p/a", "bash:git status")
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/settings/approval-rules", request.target)
        assertEquals(Json.parseToJsonElement("""{"projectPath":"/p/a","key":"bash:git status"}"""), Json.parseToJsonElement(request.body!!.utf8()))
    }

    @Test
    fun `a server without the route is a 404 the caller can hide`() = withServer(
        MockResponse.Builder().code(404).body("""{"ok":false,"error":"Not found"}""").build(),
    ) { _, api ->
        try {
            api.approvalRules()
            fail("expected a failure")
        } catch (e: PpmError) {
            // The section stays hidden.
        }
    }
}
