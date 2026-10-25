package com.hienlh.ppm.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** What HtmlBoxes makes of a reply's text, which Chrome's boxes alone do not show. */
class HtmlBoxesTest {
    private fun build(markdown: String, open: Map<Int, Boolean> = emptyMap()) =
        HtmlBoxes.build(MarkdownHtml.render(markdown, rawHtml = true), MarkdownStyle.chat, open)

    /** Every line box's text, in order. */
    private fun texts(box: HtmlBox): List<String> = when (box) {
        is FlowBox -> box.children.flatMap(::texts)
        is LinesBox -> listOf(box.text.text)
        is TableBox -> box.rows.flatten().flatMap { texts(it.box) }
        else -> emptyList()
    }

    private fun details(box: HtmlBox): List<FlowBox> = when (box) {
        is FlowBox -> (if (box.details != null) listOf(box) else emptyList()) + box.children.flatMap(::details)
        else -> emptyList()
    }

    @Test
    fun `white space is kept in a paragraph and collapsed elsewhere`() {
        assertEquals(listOf("  two  spaces  ", "collapse spaces"), texts(build("<p>  two  spaces  </p>\n\n<div>  collapse   spaces  </div>")))
    }

    @Test
    fun `a soft line break shows as the web's break and line ending`() {
        assertEquals(listOf("Soft\n\nbreak"), texts(build("Soft\nbreak")))
    }

    @Test
    fun `a line ending that ends the block opens no line`() {
        assertEquals(listOf("a", "b\n"), texts(build("<p>a<br></p>\n\n<p>b<br><br></p>")))
    }

    @Test
    fun `quotes nest as English quotes do`() {
        assertEquals(listOf("“quoted ‘nested’”"), texts(build("<q>quoted <q>nested</q></q>")))
    }

    @Test
    fun `text around a block inside an inline goes on lines of its own`() {
        assertEquals(listOf("x", "block in inline", "y"), texts(build("<b>x<div>block in inline</div>y</b>")))
    }

    @Test
    fun `a details keeps its number whether the one around it is open or not`() {
        val markdown = "<details><summary>a</summary><details><summary>b</summary>x</details></details><details><summary>c</summary>y</details>"
        val closed = details(build(markdown)).map { it.details }
        val opened = details(build(markdown, open = mapOf(0 to true))).map { it.details }
        assertEquals(listOf(0, 2), closed)
        assertEquals(listOf(0, 1, 2), opened)
    }

    @Test
    fun `a summary starts with its disclosure triangle`() {
        assertEquals(listOf("▸ More"), texts(build("<details>\n<summary>More</summary>\n\nHidden\n\n</details>")))
        assertEquals(listOf("▾ More", "Shown"), texts(build("<details open>\n<summary>More</summary>\n\nShown\n\n</details>")))
        assertEquals(listOf("▸ Details"), texts(build("<details>\n<p>No summary</p>\n</details>")))
    }
}
