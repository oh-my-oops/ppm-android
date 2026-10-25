package com.hienlh.ppm.chat

import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import org.junit.Assert.assertEquals
import org.junit.Test

/** Raw HTML against what the web keeps of it, from `android/scripts/gen-html-sanitize-cases.ts`. */
class HtmlSanitizeTest {
    @Test
    fun `raw HTML keeps exactly what rehype-raw and rehype-sanitize keep on the web`() {
        val cases = WireJson.parse(javaClass.getResource("/html-sanitize-cases.json")!!.readText()).array!!
        val failures = cases.mapIndexedNotNull { index, case ->
            val html = case["html"].string!!
            val expected = case["out"].string!!
            val actual = runCatching { serializeHtml(sanitizeHtml(parseHtmlFragment(html))) }.getOrElse { "threw $it" }
            if (actual == expected) null else "#$index ${quote(html)}\n  web: ${quote(expected)}\n  app: ${quote(actual)}"
        }
        assertEquals("${failures.size} of ${cases.size} differ:\n" + failures.take(30).joinToString("\n"), 0, failures.size)
    }

    @Test
    fun `numbers read and print as JavaScript's do`() {
        for ((text, number) in listOf("12" to 12.0, " 7 " to 7.0, "  " to 0.0, "0x1A" to 26.0, "0b11" to 3.0, "0o17" to 15.0,
            "1e3" to 1000.0, ".5" to 0.5, "5." to 5.0, "-Infinity" to Double.NEGATIVE_INFINITY, "﻿3" to 3.0)) {
            assertEquals(text, number, jsNumber(text), 0.0)
        }
        for (text in listOf("1_000", "0x", "+0x1", "1d", "NaN", "50%", "1e", "\u001C1")) assertEquals(text, true, jsNumber(text).isNaN())
        for ((number, text) in listOf(1e21 to "1e+21", 1e20 to "100000000000000000000", 1e-7 to "1e-7", 0.000001 to "0.000001",
            1.5 to "1.5", -0.0 to "0", 123.456 to "123.456", 1.2345e-10 to "1.2345e-10", 2.5e25 to "2.5e+25")) {
            assertEquals(text, jsNumberToString(number))
        }
    }
}
