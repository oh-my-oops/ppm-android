package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.McpAuthFlow
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The requests an MCP sign-in makes (`api-mcp-auth.ts`), and a background command's output read. */
class McpAuthTest {
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

    private val waiting = """{"id":"f/1","serverName":"linear","status":"waiting","authUrl":"https://auth.test/a","callbackExpected":true,"redirectScheme":"localhost"}"""

    @Test
    fun `the origin sent is the server's address without its path`() {
        assertEquals("https://ppm.example.com", ApiClient("https://PPM.Example.com:443/base/".toHttpUrl(), "").origin)
        assertEquals("http://100.74.55.81:8927", ApiClient("http://100.74.55.81:8927".toHttpUrl(), "").origin)
        assertEquals("https://x.trycloudflare.com", ApiClient("https://x.trycloudflare.com".toHttpUrl(), "").origin)
        assertEquals("http://10.0.2.2:8938", ApiClient("http://10.0.2.2:8938/".toHttpUrl(), "").origin)
        assertEquals("http://[::1]:8080", ApiClient("http://[::1]:8080".toHttpUrl(), "").origin)
    }

    @Test
    fun `a sign-in starts with the server, the project and the origin, then is read, answered and cancelled by id`() = withServer(
        ok(waiting), ok(waiting), ok(waiting), ok(waiting), ok("null"),
    ) { server, api ->
        val flow = api.startMcpAuth("linear", "demo")
        assertEquals(McpAuthFlow.Status.WAITING, flow.status)
        val start = server.takeRequest()
        assertEquals("POST /api/mcp-auth/start", "${start.method} ${start.target}")
        assertEquals("""{"server":"linear","project":"demo","origin":"${api.origin}"}""", start.body?.utf8())

        api.mcpAuthFlow(flow.id)
        assertEquals("GET /api/mcp-auth/flows/f%2F1", server.takeRequest().let { "${it.method} ${it.target}" })
        api.submitMcpAuthCallback(flow.id, "http://localhost:1/callback?code=x")
        val callback = server.takeRequest()
        assertEquals("POST /api/mcp-auth/flows/f%2F1/callback", "${callback.method} ${callback.target}")
        assertEquals("""{"url":"http://localhost:1/callback?code=x"}""", callback.body?.utf8())
        api.confirmMcpAuth(flow.id)
        val confirm = server.takeRequest()
        assertEquals("POST /api/mcp-auth/flows/f%2F1/confirm", "${confirm.method} ${confirm.target}")
        assertEquals("{}", confirm.body?.utf8())
        api.cancelMcpAuth(flow.id)
        assertEquals("DELETE /api/mcp-auth/flows/f%2F1", server.takeRequest().let { "${it.method} ${it.target}" })
    }

    @Test
    fun `a start without a project leaves it out, and an answer that is no flow is refused`() = withServer(ok("""{"id":"x"}""")) { server, api ->
        assertThrows(PpmError.BadResponse::class.java) { runBlocking { api.startMcpAuth("claude.ai Gmail", null) } }
        assertEquals("""{"server":"claude.ai Gmail","origin":"${api.origin}"}""", server.takeRequest().body?.utf8())
    }

    @Test
    fun `a command's output is read from anywhere on the host`() = withServer(
        ok("""{"content":"${'$'} bun dev\nready\n","path":"/tmp/b1.output"}"""),
        MockResponse.Builder().code(400).body("""{"ok":false,"error":"File too large (>5MB)"}""").build(),
    ) { server, api ->
        assertEquals("\$ bun dev\nready\n", api.fsRead("/tmp/claude-1000/tasks/b1.output"))
        assertEquals("GET /api/fs/read?path=%2Ftmp%2Fclaude-1000%2Ftasks%2Fb1.output", server.takeRequest().let { "${it.method} ${it.target}" })
        val error = assertThrows(PpmError.Server::class.java) { runBlocking { api.fsRead("/tmp/big.output") } }
        assertEquals("File too large (>5MB)", error.message)
    }
}
