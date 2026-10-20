package com.hienlh.ppm.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** use-server-reload.ts and connection-store.ts, as the app reads them. */
class HealthWatchTest {
    @Test
    fun `the overlay shows at the first unanswered check 15 s into an outage`() {
        val watch = HealthWatch()
        assertEquals(10_000, watch.nextDelayMs)
        var now = 0L
        assertFalse(watch.record(HealthCheck.NoAnswer, now))
        assertEquals(0L, watch.downSince)
        assertEquals(2_000, watch.nextDelayMs)
        // Every 2 s: 14 s down is not yet 15.
        while (now < 14_000) {
            now += 2_000
            watch.record(HealthCheck.NoAnswer, now)
            assertFalse("overlay at $now", watch.overlay)
        }
        watch.record(HealthCheck.NoAnswer, 16_000)
        assertTrue(watch.overlay)
        assertEquals(0L, watch.downSince)
    }

    @Test
    fun `an answer after an outage reopens the server and clears the overlay`() {
        val watch = HealthWatch()
        for (t in 0..20_000L step 2_000) watch.record(HealthCheck.NoAnswer, t)
        assertTrue(watch.overlay)
        assertTrue(watch.record(HealthCheck.Up, 22_000))
        assertFalse(watch.overlay)
        assertNull(watch.downSince)
        assertEquals(10_000, watch.nextDelayMs)
        // Up while up: nothing to reopen.
        assertFalse(watch.record(HealthCheck.Up, 32_000))
    }

    @Test
    fun `an error answer is not down, and leaves the overlay as it was`() {
        val watch = HealthWatch()
        assertFalse(watch.record(HealthCheck.Error, 0))
        assertFalse(watch.overlay)
        assertNull(watch.downSince)
        for (t in 0..16_000L step 2_000) watch.record(HealthCheck.NoAnswer, t)
        assertTrue(watch.overlay)
        // A 502 from whatever stands in front: the outage flag goes, the overlay stays.
        watch.record(HealthCheck.Error, 18_000)
        assertTrue(watch.overlay)
        assertEquals(10_000, watch.nextDelayMs)
        // So the answer that follows clears it without a reopen, as the web does not reload then.
        assertFalse(watch.record(HealthCheck.Up, 28_000))
        assertFalse(watch.overlay)
    }

    @Test
    fun `retry reopens only on an answer`() {
        val watch = HealthWatch()
        for (t in 0..16_000L step 2_000) watch.record(HealthCheck.NoAnswer, t)
        assertFalse(watch.retry(HealthCheck.NoAnswer))
        assertFalse(watch.retry(HealthCheck.Error))
        assertTrue(watch.overlay)
        assertTrue(watch.retry(HealthCheck.Up))
        assertFalse(watch.overlay)
    }
}
