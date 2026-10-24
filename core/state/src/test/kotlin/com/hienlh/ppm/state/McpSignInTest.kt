package com.hienlh.ppm.state

import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.protocol.McpAuthFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** A server's side of a sign-in, scripted: what a start answers, what each read answers. */
private class FakeMcpAuth(var startAnswer: () -> McpAuthFlow) : McpSignInBackend {
    var readAnswer: (() -> McpAuthFlow)? = null
    var submitAnswer: (() -> McpAuthFlow)? = null
    /** Held until completed, to answer a start after the dialog closed. */
    var holdStart: CompletableDeferred<Unit>? = null
    var reads = 0
    val cancelled = mutableListOf<String>()
    val submitted = mutableListOf<String>()

    override suspend fun start(server: String): McpAuthFlow {
        holdStart?.await()
        return startAnswer()
    }

    override suspend fun flow(id: String): McpAuthFlow {
        reads++
        val answer = readAnswer ?: throw PpmError.Server(404, "This sign-in is no longer active. Start again.")
        return answer()
    }

    override suspend fun submitCallback(id: String, url: String): McpAuthFlow {
        submitted += url
        return (submitAnswer ?: startAnswer)()
    }

    override suspend fun confirm(id: String): McpAuthFlow = submitCallback(id, "confirm")

    override suspend fun cancel(id: String) {
        cancelled += id
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class McpSignInTest {
    private val waiting = McpAuthFlow(
        "f1", "linear", McpAuthFlow.Status.WAITING, "https://auth.test/a?state=s",
        callbackExpected = true, redirectScheme = McpAuthFlow.RedirectScheme.LOCALHOST,
    )
    private val done = waiting.copy(status = McpAuthFlow.Status.DONE, authUrl = null)

    private fun TestScope.flow(server: FakeMcpAuth, signedIn: MutableList<String> = mutableListOf()) =
        McpSignInFlow("linear", server, backgroundScope, pollMillis = 10) { signedIn += it }

    @Test
    fun `started at once, read until it settles, signed in once`() = runTest {
        val server = FakeMcpAuth { waiting }
        val signedIn = mutableListOf<String>()
        val flow = flow(server, signedIn)
        flow.start()
        assertEquals(true, flow.state.value.starting)
        runCurrent()
        assertEquals(waiting, flow.state.value.flow)
        assertFalse(flow.state.value.starting)
        server.readAnswer = { waiting }
        advanceTimeBy(21)
        assertEquals(2, server.reads)
        server.readAnswer = { done }
        advanceTimeBy(10)
        assertEquals(McpAuthFlow.Status.DONE, flow.state.value.flow?.status)
        val reads = server.reads
        advanceTimeBy(50)
        assertEquals(reads, server.reads)
        assertEquals(listOf("linear"), signedIn)
        flow.close()
        runCurrent()
        assertEquals(emptyList<String>(), server.cancelled)
    }

    @Test
    fun `a failed start says why, and Try again starts over`() = runTest {
        val server = FakeMcpAuth { throw PpmError.Server(500, "Could not start the sign-in: boom") }
        val flow = flow(server)
        flow.start()
        runCurrent()
        assertNull(flow.state.value.flow)
        assertEquals("Could not start the sign-in: boom", flow.state.value.error)
        assertFalse(flow.state.value.starting)
        server.startAnswer = { waiting }
        server.readAnswer = { waiting }
        flow.start()
        assertNull(flow.state.value.error)
        runCurrent()
        assertEquals(waiting, flow.state.value.flow)
        flow.close()
        runCurrent()
        assertEquals(listOf("f1"), server.cancelled)
    }

    @Test
    fun `closing cancels an open flow and stops reading it`() = runTest {
        val server = FakeMcpAuth { waiting }
        server.readAnswer = { waiting }
        val flow = flow(server)
        flow.start()
        advanceTimeBy(11)
        assertEquals(1, server.reads)
        flow.close()
        runCurrent()
        assertEquals(listOf("f1"), server.cancelled)
        advanceTimeBy(50)
        assertEquals(1, server.reads)
        // Closed is closed: a second close sends nothing more, and a start does nothing.
        flow.close()
        flow.start()
        advanceTimeBy(50)
        assertEquals(listOf("f1"), server.cancelled)
        assertEquals(1, server.reads)
    }

    @Test
    fun `a start that answers after the dialog closed is cancelled`() = runTest {
        val server = FakeMcpAuth { waiting }
        server.holdStart = CompletableDeferred()
        val flow = flow(server)
        flow.start()
        runCurrent()
        flow.close()
        server.holdStart!!.complete(Unit)
        runCurrent()
        assertNull(flow.state.value.flow)
        assertEquals(listOf("f1"), server.cancelled)
        advanceTimeBy(50)
        assertEquals(0, server.reads)
    }

    @Test
    fun `a pasted address, and an outcome never goes back to waiting`() = runTest {
        val server = FakeMcpAuth { waiting }
        server.readAnswer = { waiting }
        val signedIn = mutableListOf<String>()
        val flow = flow(server, signedIn)
        flow.start()
        runCurrent()
        // A refused address leaves the flow waiting, with the reason.
        server.submitAnswer = { throw PpmError.Server(500, "Invalid callback") }
        flow.submitCallback("http://localhost:1/callback?code=x")
        assertEquals(true, flow.state.value.busy)
        runCurrent()
        assertEquals("Invalid callback", flow.state.value.error)
        assertFalse(flow.state.value.busy)
        assertEquals(McpAuthFlow.Status.WAITING, flow.state.value.flow?.status)
        server.submitAnswer = { done }
        flow.submitCallback("http://localhost:1/callback?code=y")
        assertNull(flow.state.value.error)
        runCurrent()
        assertEquals(McpAuthFlow.Status.DONE, flow.state.value.flow?.status)
        assertEquals(listOf("http://localhost:1/callback?code=x", "http://localhost:1/callback?code=y"), server.submitted)
        advanceTimeBy(40)
        assertEquals(McpAuthFlow.Status.DONE, flow.state.value.flow?.status)
        assertEquals(1, signedIn.size)
        flow.close()
    }

    @Test
    fun `an answer that says waiting after the flow settled is ignored`() = runTest {
        val slowSubmit = CompletableDeferred<Unit>()
        val server = FakeMcpAuth { waiting }
        server.readAnswer = { waiting }
        val held = object : McpSignInBackend by server {
            override suspend fun submitCallback(id: String, url: String): McpAuthFlow {
                slowSubmit.await()
                return waiting
            }
        }
        val flow = McpSignInFlow("linear", held, backgroundScope, pollMillis = 10)
        flow.start()
        runCurrent()
        // The pasted address is out when a read says the sign-in finished; its late answer still says waiting.
        flow.submitCallback("http://localhost:1/callback?code=x")
        runCurrent()
        server.readAnswer = { done }
        advanceTimeBy(11)
        assertEquals(McpAuthFlow.Status.DONE, flow.state.value.flow?.status)
        slowSubmit.complete(Unit)
        runCurrent()
        assertEquals(McpAuthFlow.Status.DONE, flow.state.value.flow?.status)
        assertFalse(flow.state.value.busy)
        flow.close()
    }

    @Test
    fun `a flow the server forgot ends as failed, with its words`() = runTest {
        val server = FakeMcpAuth { waiting }
        val flow = flow(server)
        flow.start()
        advanceTimeBy(11)
        assertEquals(McpAuthFlow.Status.FAILED, flow.state.value.flow?.status)
        assertEquals("This sign-in is no longer active. Start again.", flow.state.value.flow?.error)
        assertNull(flow.state.value.flow?.authUrl)
        val reads = server.reads
        advanceTimeBy(40)
        assertEquals(reads, server.reads)
        flow.close()
        runCurrent()
        // Failed is not open: nothing to cancel.
        assertEquals(emptyList<String>(), server.cancelled)
    }
}
