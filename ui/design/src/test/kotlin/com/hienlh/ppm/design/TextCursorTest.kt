package com.hienlh.ppm.design

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a mouse sees Chrome's I-beam: over a text node's fragment, which spans its glyphs across
 * and the font's ascent and descent down, never over what stands in the line that is not text.
 * Numbers are 14 px Geist at 2.625x (36.75 px: a box 37 above the baseline and 11 below, in a
 * line 52.5 px tall) and 12 px Monaspace (31.5 px: 25 above and 7 below).
 */
class TextCursorTest {
    private val sans = 14f * 2.625f
    private val mono = 12f * 2.625f

    @Test
    fun `a fragment is the font's box around the baseline, not the line's`() {
        // The line box starts 39 px over the baseline; the half-leading above the fragment is no text.
        assertFalse(overFragment(61f, 100f, sans, ChromeFace.Sans))
        assertFalse(overFragment(62.9f, 100f, sans, ChromeFace.Sans))
        assertTrue(overFragment(63f, 100f, sans, ChromeFace.Sans))
        assertTrue(overFragment(110.9f, 100f, sans, ChromeFace.Sans))
        assertFalse(overFragment(111f, 100f, sans, ChromeFace.Sans))
    }

    @Test
    fun `a code's fragment is shorter above the baseline than the text around it`() {
        assertTrue(overFragment(75f, 100f, mono, ChromeFace.Mono))
        assertFalse(overFragment(74.9f, 100f, mono, ChromeFace.Mono))
        assertTrue(overFragment(106.9f, 100f, mono, ChromeFace.Mono))
        assertFalse(overFragment(107f, 100f, mono, ChromeFace.Mono))
        // 30 px up is in the sans fragment beside it, and out of the code's own.
        assertTrue(overFragment(70f, 100f, sans, ChromeFace.Sans))
        assertFalse(overFragment(70f, 100f, mono, ChromeFace.Mono))
    }

    @Test
    fun `a face is told from the font family`() {
        assertSame(ChromeFace.Mono, chromeFaceOf(PpmFonts.mono))
        assertSame(ChromeFace.Sans, chromeFaceOf(PpmFonts.sans))
        assertSame(ChromeFace.Sans, chromeFaceOf(null))
    }

    @Test
    fun `the characters of a text, styled or not, are runs`() {
        val text = buildAnnotatedString {
            append("ab")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("cd") }
        }
        for (offset in 0 until 4) assertTrue(text.isTextRun(offset))
        assertFalse(text.isTextRun(-1))
        assertFalse(text.isTextRun(4))
    }

    @Test
    fun `a checkbox or a chip in the line is no text`() {
        val inline = buildAnnotatedString {
            append("a")
            appendInlineContent("box", "x")
            append("b")
        }
        assertTrue(inline.isTextRun(0))
        assertFalse(inline.isTextRun(1))
        assertTrue(inline.isTextRun(2))
        val atomic = buildAnnotatedString {
            append("axb")
            addChromeAtomic(16f, 1, 2)
        }
        assertTrue(atomic.isTextRun(0))
        assertFalse(atomic.isTextRun(1))
        assertTrue(atomic.isTextRun(2))
    }

    @Test
    fun `a link keeps its hand`() {
        val text = buildAnnotatedString {
            append("see ")
            withLink(LinkAnnotation.Url("https://example.com")) { append("here") }
            append(" now")
        }
        assertTrue(text.isTextRun(3))
        for (offset in 4 until 8) assertFalse(text.isTextRun(offset))
        assertTrue(text.isTextRun(8))
    }

    @Test
    fun `the padding an inline code stands for is no text`() {
        val edge = 0xE000.toChar().toString()
        val text = buildAnnotatedString {
            append(edge)
            append("code")
            append(edge)
            addStyle(SpanStyle(fontFamily = PpmFonts.codeEdge), 0, 1)
            addStyle(SpanStyle(fontFamily = PpmFonts.codeEdge), 5, 6)
        }
        assertFalse(text.isTextRun(0))
        for (offset in 1..4) assertTrue(text.isTextRun(offset))
        assertFalse(text.isTextRun(5))
        // The same private-use character with no edge font is whatever a fallback font draws for it.
        assertTrue(buildAnnotatedString { append(edge) }.isTextRun(0))
    }
}
