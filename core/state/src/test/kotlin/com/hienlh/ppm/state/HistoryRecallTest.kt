package com.hienlh.ppm.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The composer's ↑/↓ recall, case for case with `tests/unit/web/message-history-recall.test.ts`. */
class HistoryRecallTest {
    private val history = listOf("oldest", "middle", "newest")

    @Test
    fun `first ArrowUp from a draft recalls the newest message`() {
        assertEquals(HistoryStep(0, "newest"), stepHistory(history, -1, 1))
    }

    @Test
    fun `walks backwards through the transcript`() {
        assertEquals(HistoryStep(1, "middle"), stepHistory(history, 0, 1))
        assertEquals(HistoryStep(2, "oldest"), stepHistory(history, 1, 1))
    }

    @Test
    fun `stops at the oldest message`() {
        assertNull(stepHistory(history, 2, 1))
    }

    @Test
    fun `ArrowDown walks back toward the newest`() {
        assertEquals(HistoryStep(1, "middle"), stepHistory(history, 2, -1))
    }

    @Test
    fun `ArrowDown past the newest clears the input`() {
        assertEquals(HistoryStep(-1, ""), stepHistory(history, 0, -1))
    }

    @Test
    fun `stops below the draft position`() {
        assertNull(stepHistory(history, -1, -1))
    }

    @Test
    fun `does nothing when the session has no user messages`() {
        assertNull(stepHistory(emptyList(), -1, 1))
    }

    @Test
    fun `handles a single-message session`() {
        assertEquals(HistoryStep(0, "only"), stepHistory(listOf("only"), -1, 1))
        assertNull(stepHistory(listOf("only"), 0, 1))
    }
}
