package com.hienlh.ppm.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** Flex items shrinking as CSS flexbox resolves them, the team header's names and time. */
class FlexShrinkTest {
    private fun assertSizes(expected: List<Float>, actual: List<Float>, delta: Float = 0.001f) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) -> assertEquals(e, a, delta) }
    }

    @Test
    fun `items that fit keep their own widths`() {
        assertSizes(listOf(10f, 5f, 20f), flexShrink(listOf(10f, 5f, 20f), listOf(4f, 5f, 8f), 50f))
    }

    @Test
    fun `each gives up width in proportion to its own`() {
        assertSizes(listOf(80f, 40f), flexShrink(listOf(100f, 50f), listOf(0f, 0f), 120f))
    }

    @Test
    fun `one that would go under its min-content stops there and the rest share what is still over`() {
        val sizes = flexShrink(listOf(100f, 10f, 200f, 50f), listOf(40f, 10f, 150f, 30f), 300f)
        assertSizes(listOf(82.857f, 10f, 165.714f, 41.429f), sizes)
        assertEquals(300f, sizes.sum(), 0.001f)
    }

    @Test
    fun `the web's header beside a long name, as Chrome measured it at 2,625x`() {
        // "team-lead", "→", the long name and "05:15:58 AM" in 373.905 px: the arrow is held at its
        // 12, and the others end at Chrome's 54.554, 252.125 and 55.226.
        val sizes = flexShrink(listOf(58.262f, 12f, 269.26f, 58.98f), listOf(36f, 12f, 230f, 40f), 373.905f)
        assertSizes(listOf(54.554f, 12f, 252.125f, 55.226f), sizes, delta = 0.01f)
    }
}
