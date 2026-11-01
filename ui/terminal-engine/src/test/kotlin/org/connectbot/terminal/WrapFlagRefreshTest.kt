package org.connectbot.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which cells are read for a damaged screen, besides the damaged ones, so that the flag of a row that wrapped in a later
 * write is read too (see [refreshRowsAbove]). The flags themselves come from libvterm's JNI; the real thing is
 * `EngineInstrumentation.verifyWrapFlagsAcrossWrites`.
 */
class WrapFlagRefreshTest {
    private val rows = 6
    private val cols = 80

    private fun none() = IntArray(rows * 2) { if (it % 2 == 0) cols else 0 }

    private fun IntArray.damage(row: Int, start: Int = 0, end: Int = cols) = also { it[row * 2] = start; it[row * 2 + 1] = end }

    private fun IntArray.range(row: Int) = this[row * 2] to this[row * 2 + 1]

    private fun refresh(ranges: IntArray, width: (Int, Int) -> Int = { _, _ -> 1 }, stored: (Int) -> Int = { cols }) =
        ranges.also { refreshRowsAbove(it, rows, cols, stored, width) }

    @Test
    fun `a row written to gets the cell with the flag of the row above it`() {
        val ranges = refresh(none().damage(3, 4, 9))
        assertEquals((cols - 1) to cols, ranges.range(2))
        assertEquals(4 to 9, ranges.range(3))
        for (row in listOf(0, 1, 4, 5)) assertEquals("row $row", cols to 0, ranges.range(row))
    }

    @Test
    fun `the first row has nothing above it`() {
        val ranges = refresh(none().damage(0, 0, 3))
        assertEquals(0 to 3, ranges.range(0))
        for (row in 1 until rows) assertEquals("row $row", cols to 0, ranges.range(row))
    }

    @Test
    fun `the second row asks for the first, the one on top`() {
        val ranges = refresh(none().damage(1, 2, 6))
        assertEquals((cols - 1) to cols, ranges.range(0))
        assertEquals(2 to 6, ranges.range(1))
        for (row in 2 until rows) assertEquals("row $row", cols to 0, ranges.range(row))
    }

    @Test
    fun `a row read only for its flag does not ask for the row above it as well`() {
        val ranges = refresh(none().damage(4, 0, 2))
        assertEquals((cols - 1) to cols, ranges.range(3))
        assertEquals("row 2", cols to 0, ranges.range(2))
    }

    @Test
    fun `two rows written in a row ask for the one above the first only`() {
        val ranges = refresh(none().damage(2, 0, 5).damage(3, 10, 12))
        assertEquals((cols - 1) to cols, ranges.range(1))
        assertEquals(0 to 5, ranges.range(2))
        assertEquals(10 to 12, ranges.range(3))
        assertEquals("row 0", cols to 0, ranges.range(0))
    }

    @Test
    fun `a row above that is read anyway keeps its own range`() {
        val ranges = refresh(none().damage(2, 3, 7).damage(3, 0, 2))
        assertEquals(3 to 7, ranges.range(2))
    }

    @Test
    fun `nothing is added when nothing is damaged`() {
        val ranges = refresh(none())
        for (row in 0 until rows) assertEquals("row $row", cols to 0, ranges.range(row))
    }

    @Test
    fun `a full redraw adds nothing`() {
        val ranges = refresh(IntArray(rows * 2) { if (it % 2 == 0) 0 else cols })
        for (row in 0 until rows) assertEquals("row $row", 0 to cols, ranges.range(row))
    }

    @Test
    fun `the second half of a wide character brings its first half`() {
        val ranges = refresh(none().damage(2, 0, 1), width = { row, col -> if (row == 1 && col == cols - 1) 0 else 1 })
        assertEquals((cols - 2) to cols, ranges.range(1))
    }

    @Test
    fun `a row stored at another width is read whole, like a damaged one`() {
        val ranges = refresh(none().damage(2, 0, 1), stored = { if (it == 1) cols - 10 else cols })
        assertEquals(0 to cols, ranges.range(1))
    }

    @Test
    fun `a single column screen asks for its only cell`() {
        val one = IntArray(rows * 2) { if (it % 2 == 0) 1 else 0 }.also { it[4] = 0; it[5] = 1 }
        refreshRowsAbove(one, rows, 1, { 1 }) { _, _ -> 0 }
        assertEquals(0 to 1, one[2] to one[3])
    }
}
