package com.hienlh.ppm.client

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `POST /git/commit-message` (`routes/git.ts`, `git-commit-message.service.ts`): its two kinds of 409. */
class GitCommitMessageApiTest {
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

    private fun failure(code: Int, message: String) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body("""{"ok":false,"error":"$message"}""").build()

    @Test
    fun `an empty index reads as Stage changes first`() = withServer(failure(409, "Nothing is staged")) { _, api ->
        val error = runCatching { api.gitCommitMessage("p") }.exceptionOrNull()!!
        assertTrue(error.isCommitMessageNothingStaged())
        assertEquals("Stage changes first", (error as PpmError.Server).serverMessage)
    }

    @Test
    fun `a default provider that cannot draft keeps the server's words, and is not taken for an empty index`() {
        val words = "Gemini cannot draft a commit message. Make Claude or Codex the default provider in Settings."
        withServer(failure(409, words), failure(409, words)) { _, api ->
            val error = runCatching { api.gitCommitMessage("p") }.exceptionOrNull()!!
            assertFalse(error.isCommitMessageNothingStaged())
            assertEquals(words, (error as PpmError.Server).serverMessage)
            assertEquals(CommitMessageResult.Failed(words, 409), api.gitCommitMessageResult("p"))
        }
    }

    @Test
    fun `a provider that is not available is a failure with the server's words`() =
        withServer(failure(502, "Codex is not available right now")) { _, api ->
            assertEquals(CommitMessageResult.Failed("Codex is not available right now", 502), api.gitCommitMessageResult("p"))
        }
}
