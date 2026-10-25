package com.hienlh.ppm.chat

import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import org.junit.Assert.assertEquals
import org.junit.Test

/** Markdown against the HTML the web makes of it, from `android/scripts/gen-markdown-html-cases.ts`. */
class MarkdownHtmlTest {
    @Test
    fun `markdown becomes the HTML react-markdown builds before its url transform`() {
        val cases = WireJson.parse(javaClass.getResource("/markdown-html-cases.json")!!.readText()).array!!
        val failures = cases.mapIndexedNotNull { index, case ->
            val markdown = case["md"].string!!
            val expected = case["out"].string!!
            val raw = case["raw"].bool == true
            val actual = runCatching { serializeHtml(MarkdownHtml.html(markdown)) }.getOrElse { "threw $it" }
            val found = runCatching { MarkdownHtml.of(markdown) != null }.getOrDefault(!raw)
            when {
                actual != expected -> "#$index ${quote(markdown)}\n  web: ${quote(expected)}\n  app: ${quote(actual)}"
                found != raw -> "#$index ${quote(markdown)}: raw HTML ${if (raw) "missed" else "seen where there is none"}"
                else -> null
            }
        }
        assertEquals("${failures.size} of ${cases.size} differ:\n" + failures.take(40).joinToString("\n"), 0, failures.size)
    }
}
