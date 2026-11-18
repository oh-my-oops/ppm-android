package com.hienlh.ppm.client

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Jira panel's ticket and metadata requests, and the Schedules window's Dismiss. */
class JiraPanelApiTest {
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

    private fun MockWebServer.next() = takeRequest().let { "${it.method} ${it.target}" to it.body?.utf8().orEmpty() }

    @Test
    fun `a ticket is read, renamed and moved through its own path`() = withServer(
        ok("""{"key":"PPM-1"}"""), ok("[]"), ok("""{"updated":true}"""), ok("""{"transitioned":true}"""),
    ) { server, api ->
        api.jiraGetTicket(3, "PPM-1")
        assertEquals("GET /api/jira/ticket/3/PPM-1" to "", server.next())
        api.jiraTicketTransitions(3, "PPM-1")
        assertEquals("GET /api/jira/ticket/3/PPM-1/transitions" to "", server.next())
        api.jiraUpdateTicketSummary(3, "PPM-1", "New title")
        assertEquals("PUT /api/jira/ticket/3/PPM-1" to """{"fields":{"summary":"New title"}}""", server.next())
        api.jiraTransitionTicket(3, "PPM-1", "31")
        assertEquals("POST /api/jira/ticket/3/PPM-1/transition" to """{"transitionId":"31"}""", server.next())
    }

    @Test
    fun `the filter builder asks for each field's choices`() = withServer(ok("[]"), ok("[]")) { server, api ->
        api.jiraMetadata(3, "projects")
        assertEquals("GET /api/jira/metadata/3/projects", server.next().first)
        api.jiraMetadata(3, "issuetype")
        assertEquals("GET /api/jira/metadata/3/issuetype", server.next().first)
    }

    @Test
    fun `an edited watcher sends every field, an emptied template as null`() = withServer(ok("{}")) { server, api ->
        api.jiraEditWatcher(5, "Bugs", "project = PPM", null, 60_000, "debug")
        assertEquals(
            "PUT /api/jira/watchers/5" to """{"name":"Bugs","jql":"project = PPM","promptTemplate":null,"intervalMs":60000,"mode":"debug"}""",
            server.next(),
        )
    }

    @Test
    fun `dismiss names the problem, and null brings the dot back`() = withServer(ok("{}"), ok("{}")) { server, api ->
        api.schedulesDismiss(4, "failed:9")
        assertEquals("POST /api/schedules/4/dismiss" to """{"key":"failed:9"}""", server.next())
        api.schedulesDismiss(4, null)
        assertEquals("POST /api/schedules/4/dismiss" to """{"key":null}""", server.next())
    }
}
