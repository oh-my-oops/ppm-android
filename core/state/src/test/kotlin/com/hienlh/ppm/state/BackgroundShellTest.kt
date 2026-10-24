package com.hienlh.ppm.state

import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.protocol.BackgroundShell
import com.hienlh.ppm.protocol.ChatFrame
import com.hienlh.ppm.protocol.SessionPhase
import com.hienlh.ppm.protocol.SessionStateFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Background commands and the MCP list in a chat (`PPMSelfTest/BackgroundShellTests.swift`, `McpSignInTests.swift`). */
class BackgroundShellTest {
    private val shell = BackgroundShell("b1", "bun dev", "/tmp/claude-1000/tasks/b1.output", "t1", "running", 1_760_000_000_000.0)

    @Test
    fun `the chat keeps the whole registry each time, stopped commands included`() {
        val chat = ChatTranscript()
        assertEquals(emptyList<ChatEffect>(), chat.handle(ChatFrame.BackgroundRegistry(listOf(shell))))
        assertEquals(listOf(shell), chat.backgroundShells)
        chat.handle(ChatFrame.BackgroundRegistry(listOf(shell.copy(status = "stopped"))))
        assertEquals(listOf(true), chat.backgroundShells.map { it.isStopped })
        chat.handle(ChatFrame.BackgroundRegistry(emptyList()))
        assertEquals(emptyList<BackgroundShell>(), chat.backgroundShells)
    }

    @Test
    fun `the needs-sign-in list follows mcp_status and every greeting`() {
        val chat = ChatTranscript()
        chat.handle(ChatFrame.McpStatus(listOf("linear")))
        assertEquals(listOf("linear"), chat.mcpNeedsAuth)
        // Even a greeting that comes while a sent message is unanswered.
        chat.send("hi")
        chat.handle(ChatFrame.SessionState(SessionStateFrame(phase = SessionPhase.IDLE, mcpNeedsAuth = listOf("github"))))
        assertEquals(listOf("github"), chat.mcpNeedsAuth)
        chat.handle(ChatFrame.SessionState(SessionStateFrame(phase = SessionPhase.STREAMING)))
        assertEquals(emptyList<String>(), chat.mcpNeedsAuth)
    }

    @Test
    fun `only the last 500,000 UTF-16 units are kept, as slice(-MAX_CHARS) keeps them`() {
        val kept = BackgroundOutput.trimmed("a".repeat(500_001) + "b")
        assertEquals(500_000, kept.length)
        assertEquals('b', kept.last())
        assertEquals("short", BackgroundOutput.trimmed("short"))
        // A cut through an emoji leaves its second half, drawn as a replacement mark.
        val split = BackgroundOutput.trimmed("x\uD83D\uDE00" + "a".repeat(499_999))
        assertEquals(500_000, split.length)
        assertEquals('\uFFFD', split.first())
    }

    @Test
    fun `a file link names a tracked command by its output file or its stem`() {
        val other = shell.copy(shellId = "c2", outputPath = "/tmp/c2.output")
        val shells = sequenceOf(other, shell)
        assertEquals(shell, BackgroundOutput.findByOutput("b1.output", shells))
        assertEquals(shell, BackgroundOutput.findByOutput("/tmp/claude-1000/tasks/b1.output", shells))
        assertEquals(shell, BackgroundOutput.findByOutput("C:\\tasks\\b1.output", shells))
        assertEquals(other, BackgroundOutput.findByOutput("c2", shells))
        assertNull(BackgroundOutput.findByOutput("zz.output", shells))
        assertNull(BackgroundOutput.findByOutput("", shells))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the box says Loading, then the output, then why a read failed`() = runTest {
        val output = BackgroundOutput()
        assertEquals("(no output yet)", output.shown.value.text)
        val seen = mutableListOf<String>()
        output.follow("/tmp/b1.output", running = false) {
            seen += output.shown.value.text
            "\$ bun dev\nready\n"
        }
        assertEquals(listOf("Loading…"), seen)
        assertEquals("\$ bun dev\nready\n", output.shown.value.text)
        assertFalse(output.shown.value.loading)

        output.follow("/tmp/b1.output", running = false) { throw PpmError.Server(400, "File too large (>5MB)") }
        assertEquals("Failed to read output: File too large (>5MB)", output.shown.value.text)

        // The failure stays up while the next read is out, as the web's panel keeps its error.
        output.follow("/tmp/b2.output", running = false) {
            seen += output.shown.value.text
            ""
        }
        assertEquals("Failed to read output: File too large (>5MB)", seen.last())
        assertEquals("(no output yet)", output.shown.value.text)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a running command is read again every second and a half until the panel lets go`() = runTest {
        val output = BackgroundOutput()
        var reads = 0
        val gate = CompletableDeferred<Unit>()
        val job = launch {
            output.follow("/tmp/b1.output", running = true) {
                reads++
                if (reads == 1) gate.await()
                "tick $reads"
            }
        }
        runCurrent()
        assertTrue(output.shown.value.loading)
        gate.complete(Unit)
        runCurrent()
        assertEquals("tick 1", output.shown.value.content)
        advanceTimeBy(BackgroundOutput.POLL_MILLIS - 1)
        assertEquals(1, reads)
        advanceTimeBy(2)
        assertEquals("tick 2", output.shown.value.content)
        job.cancel()
        advanceTimeBy(BackgroundOutput.POLL_MILLIS * 3)
        assertEquals(2, reads)
    }
}
