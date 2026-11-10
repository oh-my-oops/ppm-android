package com.hienlh.ppm.design.kit

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoBarTimerTest {
    @Test
    fun `default countdown is 8 seconds and minimum touch target is 48 dp`() {
        assertEquals(8_000L, UndoBarTimer.DEFAULT_DURATION_MS)
        assertEquals(8, UndoBarTimer.DEFAULT_SECONDS)
        assertEquals(48.dp, MinTouchTarget)
        assertEquals(48.dp, KitSpec.MinTouchTarget)
    }

    @Test
    fun `counts down 8 seconds and fires onExpire at zero`() {
        val expired = mutableListOf<Any?>()
        val undone = mutableListOf<Any?>()
        val timer = UndoBarTimer(
            onUndo = { undone += it },
            onExpire = { expired += it },
        )

        assertFalse(timer.active)
        assertEquals(0, timer.remainingSeconds)

        timer.show(message = "Discarded Root.kt", payload = "journal-1")
        assertTrue(timer.active)
        assertEquals("Discarded Root.kt", timer.message)
        assertEquals(8_000L, timer.remainingMs)
        assertEquals(8, timer.remainingSeconds)
        assertEquals(1.0f, timer.progress, 0.001f)

        // After 500 ms: still 8s (ceil(7.5) = 8)
        assertFalse(timer.tick(500L))
        assertEquals(7_500L, timer.remainingMs)
        assertEquals(8, timer.remainingSeconds)

        // After another 500 ms (1000 ms elapsed): 7s remaining
        assertFalse(timer.tick(500L))
        assertEquals(7_000L, timer.remainingMs)
        assertEquals(7, timer.remainingSeconds)

        // Advance 6 000 ms (7 000 ms elapsed): 1s remaining
        assertFalse(timer.tick(6_000L))
        assertTrue(timer.active)
        assertEquals(1_000L, timer.remainingMs)
        assertEquals(1, timer.remainingSeconds)

        // Final 1 000 ms (8 000 ms total): expires
        assertTrue(timer.tick(1_000L))
        assertFalse(timer.active)
        assertEquals(0L, timer.remainingMs)
        assertEquals(0, timer.remainingSeconds)
        assertNull(timer.payload)
        assertEquals(listOf<Any?>("journal-1"), expired)
        assertTrue(undone.isEmpty())

        // Extra ticks after expiry do nothing
        assertFalse(timer.tick(1_000L))
        assertEquals(1, expired.size)
    }

    @Test
    fun `triggerUndo invokes onUndo and prevents onExpire`() {
        val expired = mutableListOf<Any?>()
        val undone = mutableListOf<Any?>()
        val timer = UndoBarTimer(
            onUndo = { undone += it },
            onExpire = { expired += it },
        )

        timer.show(message = "Discarded Storage.kt", payload = "journal-42")
        timer.tick(3_200L)
        assertTrue(timer.active)
        assertEquals(5, timer.remainingSeconds)

        assertTrue(timer.triggerUndo())
        assertFalse(timer.active)
        assertEquals(0L, timer.remainingMs)
        assertEquals(0, timer.remainingSeconds)
        assertEquals(listOf<Any?>("journal-42"), undone)
        assertTrue(expired.isEmpty())

        // Subsequent ticks or second triggerUndo do not fire callbacks
        assertFalse(timer.tick(5_000L))
        assertFalse(timer.triggerUndo())
        assertEquals(1, undone.size)
        assertTrue(expired.isEmpty())
    }

    @Test
    fun `showing a new undo item resets the 8 second timer`() {
        val expired = mutableListOf<Any?>()
        val timer = UndoBarTimer(onExpire = { expired += it })

        timer.show(message = "First discard", payload = "j1")
        timer.tick(6_500L)
        assertEquals(2, timer.remainingSeconds)

        // New discard restarts full 8 s window
        timer.show(message = "Second discard", payload = "j2")
        assertTrue(timer.active)
        assertEquals("Second discard", timer.message)
        assertEquals(8_000L, timer.remainingMs)
        assertEquals(8, timer.remainingSeconds)
        assertTrue(expired.isEmpty())

        timer.tick(8_000L)
        assertFalse(timer.active)
        assertEquals(listOf<Any?>("j2"), expired)
    }

    @Test
    fun `dismiss hides the bar without calling onUndo or onExpire`() {
        var undoCalled = false
        var expireCalled = false
        val timer = UndoBarTimer(
            onUndo = { undoCalled = true },
            onExpire = { expireCalled = true },
        )

        timer.show(message = "Discarded file")
        timer.tick(2_000L)
        timer.dismiss()

        assertFalse(timer.active)
        assertEquals(0, timer.remainingSeconds)
        assertFalse(timer.tick(8_000L))
        assertFalse(undoCalled)
        assertFalse(expireCalled)
    }
}
