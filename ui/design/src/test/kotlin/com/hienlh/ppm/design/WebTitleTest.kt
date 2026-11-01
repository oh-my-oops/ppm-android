package com.hienlh.ppm.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What an element's `title` leaves on the one tooltip the Compose view holds for the whole window. */
class WebTitleTest {
    private var shown: CharSequence? = null

    private fun element() = WebTitleSlot({ shown }, { shown = it })

    @Test
    fun `a title shows while the pointer is on its element and is taken back when it leaves`() {
        val chip = element()
        chip.enter("Priority: Queue (click to cycle)")
        assertEquals("Priority: Queue (click to cycle)", shown)
        chip.exit()
        assertNull(shown)
    }

    @Test
    fun `a title that changes under a resting pointer is the one shown next, and still goes when the pointer leaves`() {
        val chip = element()
        chip.enter("Priority: Queue (click to cycle)")
        chip.retitle("Priority: Later (click to cycle)")
        assertEquals("Priority: Later (click to cycle)", shown)
        chip.exit()
        assertNull("the old title stayed on the whole window", shown)
    }

    @Test
    fun `a title that changes while the pointer is elsewhere puts nothing up`() {
        val chip = element()
        chip.retitle("Mark reviewed")
        assertNull(shown)
        chip.enter("Mark reviewed")
        chip.exit()
        chip.retitle("Reviewed")
        assertNull(shown)
    }

    @Test
    fun `leaving an element does not take back the title the next one already set`() {
        val first = element()
        val second = element()
        first.enter("First")
        second.enter("Second")
        first.exit()
        assertEquals("Second", shown)
        second.exit()
        assertNull(shown)
    }
}
