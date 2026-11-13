package com.hienlh.ppm.client

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `GET /git/index-base` (`routes/git.ts`): the file as the index holds it, for the editor's change bars. */
class GitIndexBaseApiTest {
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
    fun `reads the three answers, and asks with the blob already held and the repository`() = withServer(
        ok("""{"state":"ok","oid":"abc123","content":"a\nb\n"}"""),
        ok("""{"state":"same","oid":"abc123"}"""),
        ok("""{"state":"none","reason":"binary","oid":"def456"}"""),
        ok("""{"state":"none","reason":"untracked"}"""),
    ) { server, api ->
        assertEquals(IndexBase.Ok("abc123", "a\nb\n"), api.gitIndexBase("my web", "src/App.kt"))
        assertEquals("/api/project/my%20web/git/index-base?path=src%2FApp.kt", server.takeRequest().target)

        val nested = GitRepo("/w/web/api", "api", "api")
        assertEquals(IndexBase.Same("abc123"), api.gitIndexBase("my web", "src/App.kt", known = "abc123", repo = nested))
        assertEquals("/api/project/my%20web/git/index-base?path=src%2FApp.kt&known=abc123&repo=%2Fw%2Fweb%2Fapi", server.takeRequest().target)

        assertEquals(IndexBase.None("binary", "def456"), api.gitIndexBase("my web", "logo.png"))
        assertEquals(IndexBase.None("untracked"), api.gitIndexBase("my web", "new.txt"))
    }

    @Test
    fun `an answer of an unknown kind is a bad response, not a base`() = withServer(ok("""{"state":"later"}""")) { _, api ->
        val error = runCatching { api.gitIndexBase("p", "a.txt") }.exceptionOrNull()
        assertTrue("$error", error is PpmError.BadResponse)
    }
}
