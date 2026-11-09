package com.hienlh.ppm.state

import org.junit.Assert.assertEquals
import org.junit.Test

/** Ports `connection-chip.test.tsx` and `message-input.tsx` reconnect stage and placeholder rules. */
class ReconnectIndicatorTest {
    @Test
    fun `stays hidden while connected or within the grace delay, then moves to reconnecting and lost`() {
        assertEquals(ReconnectStage.HIDDEN, reconnectStageAt(reconnecting = false, elapsedMs = 0L))
        assertEquals(ReconnectStage.HIDDEN, reconnectStageAt(reconnecting = false, elapsedMs = 15_000L))
        assertEquals(ReconnectStage.HIDDEN, reconnectStageAt(reconnecting = true, elapsedMs = 0L))
        assertEquals(ReconnectStage.HIDDEN, reconnectStageAt(reconnecting = true, elapsedMs = RECONNECT_SHOW_DELAY_MS - 1L))
        assertEquals(ReconnectStage.RECONNECTING, reconnectStageAt(reconnecting = true, elapsedMs = RECONNECT_SHOW_DELAY_MS))
        assertEquals(ReconnectStage.RECONNECTING, reconnectStageAt(reconnecting = true, elapsedMs = RECONNECT_LOST_AFTER_MS - 1L))
        assertEquals(ReconnectStage.LOST, reconnectStageAt(reconnecting = true, elapsedMs = RECONNECT_LOST_AFTER_MS))
        assertEquals(ReconnectStage.LOST, reconnectStageAt(reconnecting = true, elapsedMs = 30_000L))
    }

    @Test
    fun `composerPlaceholder reflects reconnect stage before running state`() {
        assertEquals("Ask anything...", composerPlaceholder(ReconnectStage.HIDDEN, running = false))
        assertEquals("Follow-up or Stop...", composerPlaceholder(ReconnectStage.HIDDEN, running = true))
        assertEquals("Will send when reconnected...", composerPlaceholder(ReconnectStage.RECONNECTING, running = false))
        assertEquals("Will send when reconnected...", composerPlaceholder(ReconnectStage.RECONNECTING, running = true))
        assertEquals("Will send when reconnected...", composerPlaceholder(ReconnectStage.LOST, running = false))
    }
}
