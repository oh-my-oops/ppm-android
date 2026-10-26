package com.hienlh.ppm.design

import org.junit.Assert.assertEquals
import org.junit.Test

/** The text a [ChromeTextArea] lays out with its marks in, and where an offset into its own text lands there. */
class ChromeTextAreaTest {
    /** A mark before each `/` that follows a letter, as a line breaker might ask for. */
    private fun mark(text: CharSequence, i: Int): Char? = if (text[i] == '/' && text[i - 1].isLetter()) '|' else null

    @Test
    fun `a mark goes before the character that asks for it`() {
        assertEquals("src|/main|/a.kt", markText("src/main/a.kt", ::mark).text)
    }

    @Test
    fun `an offset lands after the marks before its character, the end at the end`() {
        val marked = markText("src/main/a.kt", ::mark)
        assertEquals(listOf(0, 4, 5, 10, 11, 15), listOf(0, 3, 4, 8, 9, 13).map(marked::toLayout))
    }

    @Test
    fun `text that needs no mark is laid out as it is`() {
        val marked = markText("a.kt", ::mark)
        assertEquals("a.kt", marked.text)
        assertEquals(listOf(0, 1, 4), listOf(0, 1, 4).map(marked::toLayout))
    }
}
