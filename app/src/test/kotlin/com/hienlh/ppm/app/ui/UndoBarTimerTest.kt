package com.hienlh.ppm.app.ui

import com.hienlh.ppm.design.kit.UndoBarTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoBarTimerTest {
    @Test
    fun `8 second countdown ticks and expires`() {
        val expired = mutableListOf<Any?>()
        val timer = UndoBarTimer(onExpire = { expired += it })

        timer.show("Discarded changes", payload = "id-1")
        assertTrue(timer.active)
        assertEquals(8, timer.remainingSeconds)

        assertFalse(timer.tick(3_000L))
        assertEquals(5, timer.remainingSeconds)

        assertTrue(timer.tick(5_000L))
        assertFalse(timer.active)
        assertEquals(0, timer.remainingSeconds)
        assertEquals(listOf<Any?>("id-1"), expired)
    }

    @Test
    fun `undo action cancels countdown without expiring`() {
        val undone = mutableListOf<Any?>()
        val expired = mutableListOf<Any?>()
        val timer = UndoBarTimer(onUndo = { undone += it }, onExpire = { expired += it })

        timer.show("Discarded changes", payload = "id-2")
        timer.tick(2_500L)
        assertTrue(timer.triggerUndo())
        assertFalse(timer.active)
        assertFalse(timer.tick(6_000L))
        assertEquals(listOf<Any?>("id-2"), undone)
        assertTrue(expired.isEmpty())
    }
}
