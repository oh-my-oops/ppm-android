package com.hienlh.ppm.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalParkGateTest {
    private class FakeClock {
        private data class Task(val at: Long, val job: Job, val block: () -> Unit)
        private val tasks = mutableListOf<Task>()
        var now = 0L
            private set

        fun schedule(delayMs: Long, block: () -> Unit): Job = Job().also { job ->
            tasks += Task(now + delayMs, job, block)
        }

        fun advanceBy(ms: Long) {
            val target = now + ms
            while (true) {
                val next = tasks.filter { it.at <= target }.minByOrNull { it.at } ?: break
                tasks.remove(next)
                now = next.at
                if (next.job.isActive) {
                    next.block()
                }
            }
            now = target
        }
    }

    @Test
    fun `starts parked, builds on first show, and ignores hiding before ever shown`() {
        val clock = FakeClock()
        val events = mutableListOf<String>()
        val gate = TerminalParkGate(
            scope = CoroutineScope(Dispatchers.Unconfined),
            graceMs = 150L,
            onShow = { events += "show" },
            onPark = { events += "park" },
            schedule = clock::schedule,
        )
        assertTrue(gate.parked)
        assertFalse(gate.shown)

        gate.setShown(false)
        clock.advanceBy(500)
        assertEquals(emptyList<String>(), events)

        gate.setShown(true)
        assertEquals(listOf("show"), events)
        assertTrue(gate.shown)
        assertFalse(gate.parked)

        gate.setShown(true)
        assertEquals(listOf("show"), events)
    }

    @Test
    fun `a hide and show within the grace window cancels the park so a tab or dock move does not rebuild`() {
        val clock = FakeClock()
        val events = mutableListOf<String>()
        val gate = TerminalParkGate(
            scope = CoroutineScope(Dispatchers.Unconfined),
            graceMs = 150L,
            onShow = { events += "show" },
            onPark = { events += "park" },
            schedule = clock::schedule,
        )
        gate.setShown(true)
        assertEquals(listOf("show"), events)

        // Dispose and recompose in the same frame or within 149 ms: never parks, never rebuilds.
        gate.setShown(false)
        clock.advanceBy(149)
        assertFalse(gate.parked)
        gate.setShown(true)
        clock.advanceBy(500)
        assertEquals(listOf("show"), events)
        assertTrue(gate.shown)
        assertFalse(gate.parked)
    }

    @Test
    fun `staying hidden past the grace window parks once, and showing again rebuilds`() {
        val clock = FakeClock()
        val events = mutableListOf<String>()
        val gate = TerminalParkGate(
            scope = CoroutineScope(Dispatchers.Unconfined),
            graceMs = 150L,
            onShow = { events += "show" },
            onPark = { events += "park" },
            schedule = clock::schedule,
        )
        gate.setShown(true)
        gate.setShown(false)
        clock.advanceBy(149)
        assertEquals(listOf("show"), events)

        clock.advanceBy(1)
        assertEquals(listOf("show", "park"), events)
        assertTrue(gate.parked)
        assertFalse(gate.shown)

        gate.setShown(true)
        assertEquals(listOf("show", "park", "show"), events)
        assertFalse(gate.parked)
        assertTrue(gate.shown)
    }

    @Test
    fun `refuses to park when canPark is false so trimmed rings, alternate screen and mouse modes keep their live emulator`() {
        val clock = FakeClock()
        val events = mutableListOf<String>()
        var allowPark = false
        val gate = TerminalParkGate(
            scope = CoroutineScope(Dispatchers.Unconfined),
            graceMs = 150L,
            canPark = { allowPark },
            onShow = { events += "show" },
            onPark = { events += "park" },
            schedule = clock::schedule,
        )
        gate.setShown(true)
        assertEquals(listOf("show"), events)

        gate.setShown(false)
        clock.advanceBy(200)
        assertEquals("does not park while canPark is false", listOf("show"), events)
        assertFalse(gate.parked)
        assertFalse(gate.shown)

        // Showing again reuses the live emulator without calling onShow again.
        gate.setShown(true)
        assertEquals(listOf("show"), events)
        assertTrue(gate.shown)
        assertFalse(gate.parked)

        // Once the condition clears (e.g. leaving alternate screen), a subsequent hide parks normally.
        allowPark = true
        gate.setShown(false)
        clock.advanceBy(150)
        assertEquals(listOf("show", "park"), events)
        assertTrue(gate.parked)
    }
}
