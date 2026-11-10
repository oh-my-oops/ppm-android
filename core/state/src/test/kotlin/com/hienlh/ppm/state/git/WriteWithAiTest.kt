package com.hienlh.ppm.state.git

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.ApiClientGit
import com.hienlh.ppm.client.CommitMessageResult
import com.hienlh.ppm.client.CommitMessageUnsupported
import com.hienlh.ppm.client.GitRepo
import com.hienlh.ppm.client.commitMessage
import com.hienlh.ppm.client.gitCommitMessageResult
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [WriteWithAi], [WriteWithAiController], and [ApiClientGit]
 * (Requirements 10.1–10.5, `design.md` Section 2).
 */
class WriteWithAiTest {

    @Test
    fun `state transitions Idle to Writing to Written lock Commit while writing and leave message editable`() {
        val controller = WriteWithAiController(
            initialMessage = "wip draft",
            initialStagedCount = 2,
        )
        assertEquals(WriteWithAi.Idle, controller.state)
        assertTrue(controller.isVisible)
        assertTrue(controller.isEnabled)
        assertNull(controller.disabledReason)
        assertTrue(controller.isCommitEnabled)

        assertTrue(controller.startWriting())
        assertEquals(WriteWithAi.Writing, controller.state)
        assertTrue(controller.isCommitLocked)
        assertFalse(controller.isCommitEnabled)
        assertFalse(controller.isEnabled)
        assertEquals(
            "Writing a message from 2 staged files…",
            controller.state.writingLabel(stagedCount = controller.stagedCount, tablet = false),
        )
        assertEquals(
            "Writing from 2 staged files…",
            controller.state.writingLabel(stagedCount = controller.stagedCount, tablet = true),
        )

        controller.onGenerated("  feat(git): add Write with AI button  \n")
        assertEquals(
            WriteWithAi.Written(
                message = "feat(git): add Write with AI button",
                previousMessage = "wip draft",
                stagedCount = 2,
            ),
            controller.state,
        )
        assertEquals("feat(git): add Write with AI button", controller.commitMessage)
        assertFalse(controller.isCommitLocked)
        assertTrue(controller.isCommitEnabled)

        // Editing the generated message keeps it editable and returns state to Idle without committing
        controller.updateMessage("feat(git): add Write with AI button on phone and tablet")
        assertEquals("feat(git): add Write with AI button on phone and tablet", controller.commitMessage)
        assertEquals(WriteWithAi.Idle, controller.state)
    }

    @Test
    fun `nothing staged disables Write with AI with Stage changes first reason`() {
        val controller = WriteWithAiController(
            initialMessage = "",
            initialStagedCount = 0,
        )
        assertFalse(controller.isEnabled)
        assertEquals("Stage changes first", controller.disabledReason)
        assertFalse(controller.startWriting())
        assertEquals(WriteWithAi.Idle, controller.state)
        assertEquals("Stage changes first", controller.disabledReason)

        // Staging a file re-enables Write with AI and clears the disabled reason
        controller.updateStagedCount(1)
        assertTrue(controller.isEnabled)
        assertNull(controller.disabledReason)
    }

    @Test
    fun `generation failure keeps user typed message and Retry succeeds`() = runBlocking {
        val controller = WriteWithAiController(
            initialMessage = "fix: keep my hand-typed draft",
            initialStagedCount = 1,
        )
        var attempts = 0
        val failedState = controller.generate {
            attempts++
            throw IllegalStateException("The AI provider timed out")
        }

        assertEquals(1, attempts)
        assertEquals(
            WriteWithAi.Failed(
                message = "The AI provider timed out",
                lastMessage = "fix: keep my hand-typed draft",
            ),
            failedState,
        )
        assertEquals("fix: keep my hand-typed draft", controller.commitMessage)
        assertTrue(controller.state.canRetry)

        val retriedState = controller.retry {
            attempts++
            "fix(git): preserve typed draft when AI fails"
        }
        assertEquals(2, attempts)
        assertEquals(
            WriteWithAi.Written(
                message = "fix(git): preserve typed draft when AI fails",
                previousMessage = "fix: keep my hand-typed draft",
                stagedCount = 1,
            ),
            retriedState,
        )
        assertEquals("fix(git): preserve typed draft when AI fails", controller.commitMessage)

        // Undo restores the user's previous hand-typed draft
        assertTrue(controller.undoWritten())
        assertEquals("fix: keep my hand-typed draft", controller.commitMessage)
        assertEquals(WriteWithAi.Idle, controller.state)
    }

    @Test
    fun `ApiClientGit handles 200 generated message 409 nothing staged 502 AI error and 404 unsupported endpoint`() = runBlocking {
        val server = MockWebServer()
        // 1. 200 OK
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("""{"ok":true,"data":{"message":"feat(git): draft commit message from staged diff"}}""")
                .build(),
        )
        // 2. 409 Nothing is staged
        server.enqueue(
            MockResponse.Builder()
                .code(409)
                .addHeader("Content-Type", "application/json")
                .body("""{"ok":false,"error":"Nothing is staged"}""")
                .build(),
        )
        // 3. 502 Provider failure
        server.enqueue(
            MockResponse.Builder()
                .code(502)
                .addHeader("Content-Type", "application/json")
                .body("""{"ok":false,"error":"The AI provider is not available"}""")
                .build(),
        )
        // 4. 404 Older server without endpoint
        server.enqueue(
            MockResponse.Builder()
                .code(404)
                .addHeader("Content-Type", "application/json")
                .body("""{"ok":false,"error":"Not found"}""")
                .build(),
        )
        server.start()
        try {
            val api = ApiClient(server.url("/"), "token")
            val subRepo = GitRepo(path = "/work/ppm/sub", name = "sub", relative = "sub")

            // 1. 200 OK via ApiClientGit.commitMessage
            val okResult = ApiClientGit(api).commitMessage("ppm", subRepo)
            assertTrue(okResult.isSuccess)
            assertEquals("feat(git): draft commit message from staged diff", okResult.getOrNull())
            val req1 = server.takeRequest()
            assertEquals("POST", req1.method)
            assertEquals("/api/project/ppm/git/commit-message?repo=%2Fwork%2Fppm%2Fsub", req1.target)

            // 2. 409 Nothing is staged -> locks button with "Stage changes first"
            val controller = WriteWithAiController(
                initialMessage = "existing draft",
                initialStagedCount = 1,
            )
            controller.generate(api, "ppm")
            assertEquals(WriteWithAi.Idle, controller.state)
            assertFalse(controller.isEnabled)
            assertEquals("Stage changes first", controller.disabledReason)
            assertEquals("existing draft", controller.commitMessage)

            // 3. 502 AI error -> Failed state, keeps "existing draft"
            controller.updateStagedCount(2)
            val stateAfter502 = controller.generate(api, "ppm")
            assertEquals(
                WriteWithAi.Failed(
                    message = "The AI provider is not available",
                    lastMessage = "existing draft",
                ),
                stateAfter502,
            )
            assertEquals("existing draft", controller.commitMessage)

            // 4. 404 Not found -> hides button (isVisible = false) and keeps "existing draft"
            val unsupportedCheck = api.commitMessage("ppm")
            assertTrue(unsupportedCheck.isFailure)
            assertTrue(unsupportedCheck.exceptionOrNull() is CommitMessageUnsupported)
            controller.onFailure(unsupportedCheck.exceptionOrNull()!!)
            assertFalse(controller.isVisible)
            assertFalse(controller.isEnabled)
            assertEquals("existing draft", controller.commitMessage)

            // Also verify gitCommitMessageResult on 404
            server.enqueue(
                MockResponse.Builder()
                    .code(404)
                    .addHeader("Content-Type", "application/json")
                    .body("""{"ok":false,"error":"Route not found"}""")
                    .build(),
            )
            val result404 = api.gitCommitMessageResult("ppm")
            assertTrue(result404 is CommitMessageResult.Unsupported)
        } finally {
            server.close()
        }
    }
}
