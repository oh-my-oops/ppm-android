package com.hienlh.ppm.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The AI settings fields read numbers as the web's `<input type=number>` and `parseInt`/`parseFloat` do. */
class AiSettingsInputTest {
    @Test
    fun `a number field reports only a valid floating-point number`() {
        assertEquals("12", htmlNumberValue("12"))
        assertEquals("-0.5", htmlNumberValue("-0.5"))
        assertEquals(".5", htmlNumberValue(".5"))
        assertEquals("1e3", htmlNumberValue("1e3"))
        assertEquals("2E-2", htmlNumberValue("2E-2"))
        assertEquals("", htmlNumberValue("1."))
        assertEquals("", htmlNumberValue("+5"))
        assertEquals("", htmlNumberValue("1e"))
        assertEquals("", htmlNumberValue("--1"))
        assertEquals("", htmlNumberValue(""))
    }

    @Test
    fun `parseInt takes the leading whole number and parseFloat the leading number`() {
        assertEquals(12.0, jsParseInt("12.7")!!, 0.0)
        assertEquals(1.0, jsParseInt("1e3")!!, 0.0)
        assertEquals(-5.0, jsParseInt("-5")!!, 0.0)
        assertNull(jsParseInt(".5"))
        assertNull(jsParseInt(""))
        assertEquals(12.7, jsParseFloat("12.7")!!, 0.0)
        assertEquals(1000.0, jsParseFloat("1e3")!!, 0.0)
        assertEquals(0.5, jsParseFloat(".5")!!, 0.0)
        assertNull(jsParseFloat(""))
    }

    @Test
    fun `a number goes out as JSON writes it`() {
        assertEquals(100L, wireNumber(100.0))
        assertEquals(0L, wireNumber(-0.0))
        assertEquals(2.5, wireNumber(2.5))
        assertNull(wireNumber(null))
        assertNull(wireNumber(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `capitalize puts each word's first letter in capitals`() {
        assertEquals("Codex", capitalized("codex"))
        assertEquals("My-Provider", capitalized("my-provider"))
        assertEquals("My_provider", capitalized("my_provider"))
    }
}
