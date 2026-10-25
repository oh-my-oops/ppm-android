package com.hienlh.ppm.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTurnsStoreTest {
    private fun turn(n: Int, vararg calls: String) = SessionTurn(n, "m$n", null, "prompt $n", calls.toList())

    @Test
    fun `fetches for a call no turn holds, and for a new one no sooner than eight seconds on`() = runTest {
        val store = SessionTurnsStore { testScheduler.currentTime }
        var loads = 0
        var server = listOf(turn(1, "a"))
        val load: suspend () -> List<SessionTurn> = { loads++; server }
        store.follow("s", listOf("a"), load)
        assertEquals(1, loads)
        assertEquals(1, store.entries.value.getValue("s").byCall.getValue("a").n)
        assertFalse(store.entries.value.getValue("s").live)
        // Every call placed: nothing to ask.
        store.follow("s", listOf("a"), load)
        assertEquals(1, loads)
        // A call the agent made since.
        server = listOf(turn(1, "a"), turn(2, "b"))
        val job = launch { store.follow("s", listOf("a", "b"), load) }
        advanceTimeBy(7_999)
        assertEquals(1, loads)
        advanceTimeBy(2)
        assertEquals(2, loads)
        assertEquals(2, store.entries.value.getValue("s").byCall.getValue("b").n)
        job.join()
    }

    @Test
    fun `a call a fetch could not place is not asked for again`() = runTest {
        val store = SessionTurnsStore { testScheduler.currentTime }
        var loads = 0
        val load: suspend () -> List<SessionTurn> = { loads++; listOf(turn(1, "a")) }
        store.follow("s", listOf("a", "elsewhere"), load)
        advanceTimeBy(60_000)
        store.follow("s", listOf("a", "elsewhere"), load)
        assertEquals(1, loads)
    }

    @Test
    fun `an open chat's turns are not fetched over, and stay when it closes`() = runTest {
        val store = SessionTurnsStore { testScheduler.currentTime }
        var loads = 0
        val load: suspend () -> List<SessionTurn> = { loads++; listOf(turn(1, "a"), turn(2, "b")) }
        store.publish("s", listOf(turn(1, "a")), live = true)
        store.follow("s", listOf("a", "b"), load)
        assertEquals(0, loads)
        store.release("s")
        val kept = store.entries.value.getValue("s")
        assertFalse(kept.live)
        assertEquals(listOf(turn(1, "a")), kept.turns)
        // Closed, it falls behind: the call it never saw is fetched.
        store.follow("s", listOf("a", "b"), load)
        assertEquals(1, loads)
        assertEquals(2, store.entries.value.getValue("s").byCall.getValue("b").n)
    }

    @Test
    fun `a chat that opens while a fetch is out wins over it`() = runTest {
        val store = SessionTurnsStore { testScheduler.currentTime }
        val gate = CompletableDeferred<Unit>()
        val job = launch { store.follow("s", listOf("a")) { gate.await(); listOf(turn(1, "a")) } }
        runCurrent()
        store.publish("s", listOf(turn(1, "a"), turn(2, "b")), live = true)
        gate.complete(Unit)
        job.join()
        val entry = store.entries.value.getValue("s")
        assertTrue(entry.live)
        assertEquals(2, entry.turns.size)
    }

    @Test
    fun `the same turns published again change nothing`() = runTest {
        val store = SessionTurnsStore()
        store.publish("s", listOf(turn(1, "a")), live = true)
        val first = store.entries.value
        store.publish("s", listOf(turn(1, "a")), live = true)
        assertSame(first, store.entries.value)
    }
}
