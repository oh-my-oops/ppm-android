package com.hienlh.ppm.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.hienlh.ppm.state.SessionTurn
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewCodeTest {
    private fun marksOut(text: String) = text.filterNot { it == BREAK || it == JOIN }

    @Test
    fun `a file's language is its extension, Dockerfile and Makefile by name`() {
        assertEquals("ts", reviewLanguage("/p/src/a.ts"))
        assertEquals("py", reviewLanguage("C:\\work\\Tool.PY"))
        assertEquals("docker", reviewLanguage("/p/Dockerfile"))
        assertEquals("docker", reviewLanguage("/p/api.dockerfile"))
        assertEquals("make", reviewLanguage("/p/Makefile"))
        assertNull(reviewLanguage("/p/.env"))
        assertNull(reviewLanguage("/p/README"))
    }

    @Test
    fun `tabs go to the next stop of four columns`() {
        assertEquals("    x", marksOut(reviewCodeLine("\tx", null, null).first.text))
        assertEquals("ab  c", marksOut(reviewCodeLine("ab\tc", null, null).first.text))
        assertEquals("abcd    e", marksOut(reviewCodeLine("abcd\te", null, null).first.text))
    }

    @Test
    fun `the changed span is a piece per token it crosses, past the tabs and marks put in`() {
        val text = "\tconst total = a + b;"
        val red = SpanStyle(color = Color.Red)
        val blue = SpanStyle(color = Color.Blue)
        // `\tconst`, ` total`, ` = a + b;`
        val tokens = AnnotatedString(text, listOf(AnnotatedString.Range(red, 0, 6), AnnotatedString.Range(blue, 6, 12)))
        val (code, pieces) = reviewCodeLine(text, tokens, 7 until 16)
        assertEquals(listOf("total", " = a"), pieces.map { marksOut(code.text.substring(it.first, it.last + 1)) })
        assertEquals("    const", marksOut(code.text.substring(code.spanStyles[0].start, code.spanStyles[0].end)))
    }

    @Test
    fun `tokens that do not add up to the line are dropped, and the span is one piece`() {
        val tokens = AnnotatedString("other", listOf(AnnotatedString.Range(SpanStyle(color = Color.Red), 0, 5)))
        val (code, pieces) = reviewCodeLine("let x = 1", tokens, 4 until 9)
        assertEquals(emptyList<AnnotatedString.Range<SpanStyle>>(), code.spanStyles)
        assertEquals(listOf("x = 1"), pieces.map { marksOut(code.text.substring(it.first, it.last + 1)) })
    }

    @Test
    fun `a block names each turn once, oldest first, by the first call it made`() {
        val early = SessionTurn(1, "m1", Instant.parse("2026-10-25T09:00:00Z"), "first", listOf("a", "b"))
        val late = SessionTurn(2, "m2", Instant.parse("2026-10-25T10:00:00Z"), "second", listOf("c"))
        val turns = mapOf("a" to early, "b" to early, "c" to late)
        assertEquals(listOf(TurnChip(early, "b"), TurnChip(late, "c")), blockTurns(listOf("c", "b", "a", "unknown"), turns))
        assertEquals(emptyList<TurnChip>(), blockTurns(null, turns))
    }
}
