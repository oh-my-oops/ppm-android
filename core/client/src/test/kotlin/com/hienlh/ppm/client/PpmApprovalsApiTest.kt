package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.SessionCollabAnswer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** PPM's own approvals over HTTP (`answerCollabApproval`, the `db_execute` card's POST). */
class PpmApprovalsApiTest {
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
    fun `a session tool's card is answered at its approvals route`() = withServer(ok("""{"answer":"allow_session"}""")) { server, api ->
        api.answerCollabApproval("req/1 2", SessionCollabAnswer.ALLOW_SESSION)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/sessions-collab/approvals/req%2F1%202", request.target)
        assertEquals(Json.parseToJsonElement("""{"answer":"allow_session"}"""), Json.parseToJsonElement(request.body!!.utf8()))
    }

    @Test
    fun `a database change carries the password only when approved`() = withServer(ok("""{"approved":true}"""), ok("""{"approved":false}""")) { server, api ->
        api.answerDbApproval("db-1", approved = true, password = "s3cret")
        val approve = server.takeRequest()
        assertEquals("/api/db/ai-approvals/db-1", approve.target)
        assertEquals(Json.parseToJsonElement("""{"approved":true,"password":"s3cret"}"""), Json.parseToJsonElement(approve.body!!.utf8()))

        api.answerDbApproval("db-1", approved = false, password = "s3cret")
        assertEquals(Json.parseToJsonElement("""{"approved":false}"""), Json.parseToJsonElement(server.takeRequest().body!!.utf8()))
    }

    @Test
    fun `a wrong password is the server's own message`() = withServer(
        MockResponse.Builder().code(403).body("""{"ok":false,"error":"Wrong PPM password."}""").build(),
    ) { _, api ->
        try {
            api.answerDbApproval("db-1", approved = true, password = "nope")
            fail("expected a failure")
        } catch (e: PpmError.Server) {
            assertEquals(403, e.status)
            assertTrue(e.message!!.contains("Wrong PPM password"))
        }
    }
}
