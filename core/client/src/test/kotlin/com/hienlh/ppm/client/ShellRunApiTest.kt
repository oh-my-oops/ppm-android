package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.WireJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.TimeUnit

/** `POST /api/project/:name/shell/run`, the Run button on a chat's shell code block (routes/code-block-run.ts). */
class ShellRunApiTest {
    private fun json(body: String, status: Int = 200) =
        MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body(body).build()

    private fun <T> withServer(block: suspend (MockWebServer, ApiClient) -> T): T = runBlocking {
        val server = MockWebServer()
        server.start()
        try { block(server, ApiClient(server.url("/"), "t")) } finally { server.close() }
    }

    @Test fun `posts the command to the project's run route and reads what it printed`() = withServer { server, api ->
        server.enqueue(json("""{"ok":true,"data":{"output":"MASTER_PASSWORD=x\n","exitCode":0,"timedOut":false,"stopped":false,"durationMs":42}}"""))
        val result = api.runShellCommand("my app", "grep '^MASTER_PASSWORD=' .env")
        assertEquals(ShellRunResult("MASTER_PASSWORD=x\n", 0, timedOut = false, stopped = false, durationMs = 42), result)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/api/project/my%20app/shell/run", request.url.encodedPath)
        assertEquals("Bearer t", request.headers["Authorization"])
        assertEquals(WireJson.parse("""{"command":"grep '^MASTER_PASSWORD=' .env"}"""), WireJson.parse(request.body!!.utf8()))
    }

    @Test fun `a command that never started has no exit code, and the limit and a stop are read`() = withServer { server, api ->
        server.enqueue(json("""{"ok":true,"data":{"output":"","exitCode":null,"timedOut":false,"stopped":false,"durationMs":3}}"""))
        assertNull(api.runShellCommand("p", "nope").exitCode)
        server.enqueue(json("""{"ok":true,"data":{"output":"partial","exitCode":null,"timedOut":true,"stopped":false,"durationMs":120000}}"""))
        val limited = api.runShellCommand("p", "sleep 999")
        assertTrue(limited.timedOut)
        assertEquals(120_000L, limited.durationMs)
        server.enqueue(json("""{"ok":true,"data":{"output":"","exitCode":2,"timedOut":false,"stopped":true,"durationMs":7}}"""))
        val stopped = api.runShellCommand("p", "x")
        assertTrue(stopped.stopped)
        assertEquals(2, stopped.exitCode)
    }

    @Test fun `a refusal keeps the server's words`() = withServer { server, api ->
        server.enqueue(json("""{"ok":false,"error":"Nothing to run"}""", 400))
        try {
            api.runShellCommand("p", " ")
            fail("expected a refusal")
        } catch (error: PpmError.Server) {
            assertEquals(400, error.status)
            assertEquals("Nothing to run", error.message)
        }
    }

    @Test fun `the read waits out the server's two minute limit`() {
        assertTrue(shellRunClient.readTimeoutMillis >= 150_000)
    }

    @Test fun `cancelling the coroutine cancels the call`() = withServer { server, api ->
        server.enqueue(
            MockResponse.Builder().addHeader("Content-Type", "application/json").headersDelay(10, TimeUnit.SECONDS)
                .body("""{"ok":true,"data":{"output":"","exitCode":0,"timedOut":false,"stopped":false,"durationMs":1}}""").build(),
        )
        var finished = false
        coroutineScope {
            val job = launch(Dispatchers.IO) {
                api.runShellCommand("p", "sleep 10")
                finished = true
            }
            server.takeRequest(5, TimeUnit.SECONDS)!!
            job.cancel()
            // The call was cancelled rather than left waiting ten seconds for its answer.
            withTimeout(5_000) { job.join() }
            assertTrue(job.isCancelled)
        }
        assertFalse(finished)
    }
}
