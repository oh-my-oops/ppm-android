package com.hienlh.ppm.state.terminal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The held arrow's schedule, on virtual time: the finger's own timing is the toolbar's, this is when it fires. */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyRepeatTest {
    /** Moves the clock to [millis] from the start and lets whatever is due at that moment run. */
    private fun TestScope.at(millis: Long) {
        advanceTimeBy(millis - currentTime)
        runCurrent()
    }

    @Test
    fun `nothing fires before the timeout, then once at it and every interval after`() = runTest {
        val fired = mutableListOf<Long>()
        val held = launch { KeyRepeat(timeoutMillis = 500, intervalMillis = 50).whileHeld { fired += currentTime } }

        at(499)
        assertEquals(emptyList<Long>(), fired)
        at(500)
        assertEquals(listOf(500L), fired)
        at(649)
        assertEquals(listOf(500L, 550L, 600L), fired)
        at(650)
        assertEquals(listOf(500L, 550L, 600L, 650L), fired)
        held.cancel()
    }

    @Test
    fun `a lift stops it, before the first repeat and after any of them`() = runTest {
        val early = mutableListOf<Long>()
        val lifted = launch { KeyRepeat(500, 50).whileHeld { early += currentTime } }
        at(499)
        lifted.cancel()
        at(5_000)
        assertEquals("a tap, not a hold: the lift before the timeout fires nothing here", emptyList<Long>(), early)

        val late = mutableListOf<Long>()
        val start = currentTime
        val held = launch { KeyRepeat(500, 50).whileHeld { late += currentTime - start } }
        at(start + 600)
        assertEquals(listOf(500L, 550L, 600L), late)
        held.cancel()
        at(start + 5_000)
        assertEquals(3, late.size)
    }

    @Test
    fun `the numbers are Android's own, so a slower system setting slows the repeat`() = runTest {
        val fired = mutableListOf<Long>()
        val held = launch { KeyRepeat(timeoutMillis = 1_000, intervalMillis = 200).whileHeld { fired += currentTime } }
        at(999)
        assertEquals(emptyList<Long>(), fired)
        at(1_400)
        assertEquals(listOf(1_000L, 1_200L, 1_400L), fired)
        held.cancel()
    }

    @Test
    fun `a repeat needs a positive interval, and cannot start in the past`() {
        assertThrows(IllegalArgumentException::class.java) { KeyRepeat(500, 0) }
        assertThrows(IllegalArgumentException::class.java) { KeyRepeat(-1, 50) }
        assertEquals(50L, KeyRepeat.DEFAULT_INTERVAL_MILLIS)
    }
}
