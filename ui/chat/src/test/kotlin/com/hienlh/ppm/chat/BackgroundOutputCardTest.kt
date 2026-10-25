package com.hienlh.ppm.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** The output panel's `<pre class="whitespace-pre-wrap">`, a line at a time. */
class BackgroundOutputCardTest {
    private fun lines(text: String) = outputLines(text).map { it.text.filterNot { c -> c == BREAK || c == JOIN } }

    @Test
    fun `a final newline starts no line, and an empty file is one empty line`() {
        assertEquals(listOf("a", "b"), lines("a\nb\n"))
        assertEquals(listOf("a", ""), lines("a\n\n"))
        assertEquals(listOf(""), lines(""))
        assertEquals(listOf(""), lines("\n"))
    }

    @Test
    fun `a carriage return is the space CSS draws, and CRLF one line break`() {
        assertEquals(listOf("a", "b"), lines("a\r\nb"))
        assertEquals(listOf("10% 50% 100%"), lines("10%\r50%\r100%"))
    }

    @Test
    fun `a line may wrap after a hyphen inside a path, as Chrome wraps it`() {
        val line = outputLines("/src/components/chat/background-command-bar.tsx").single().text
        assertEquals(true, line.contains("background-${BREAK}command"))
    }

    @Test
    fun `a tab reaches the next stop of four columns on its own line`() {
        assertEquals(listOf("a   b", "abcd    e"), lines("a\tb\nabcd\te"))
    }
}
