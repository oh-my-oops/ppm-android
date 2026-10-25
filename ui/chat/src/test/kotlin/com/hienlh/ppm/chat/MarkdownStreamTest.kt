package com.hienlh.ppm.chat

import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/** A reply rendered as it streams in, keeping what it can of each render before, against the reply rendered whole. */
class MarkdownStreamTest {
    private val json = WireJson.parse(javaClass.getResource("/markdown-html-cases.json")!!.readText()).array!!
    private val cases = json.map { it["md"].string!! }

    /**
     * Every case on its own, and the cases as one reply, where blocks of every kind follow each
     * other: all of them, and those without raw HTML, which is the reply whose blocks are kept.
     */
    private val replies = cases + cases.joinToString("\n\n") + json.filter { it["raw"].bool != true }.joinToString("\n\n") { it["md"].string!! }

    /** [text] as it streams in, some characters at a time. */
    private fun prefixes(text: String): List<String> {
        val step = if (text.length > 2000) 37 else 1
        return (1..text.length step step).map { text.substring(0, it) } + text
    }

    @Test
    fun `a streamed reply renders as the whole reply does`() {
        for (rawHtml in listOf(true, false)) {
            for (reply in replies) {
                val blocks = MarkdownHtml.Blocks()
                for (text in prefixes(reply)) {
                    assertEquals("${quote(text)} (raw HTML ${if (rawHtml) "on" else "off"})", MarkdownHtml.render(text, rawHtml), blocks.render(text, rawHtml))
                }
            }
        }
    }

    @Test
    fun `boxes kept from the build before are the boxes a new build makes`() {
        for (reply in replies) {
            val blocks = MarkdownHtml.Blocks()
            val kept = KeptBoxes()
            for (text in prefixes(reply)) {
                val nodes = blocks.render(text, rawHtml = true)
                for (open in listOf(emptyMap(), mapOf(0 to true))) {
                    val fresh = dump(HtmlBoxes.build(nodes, MarkdownStyle.chat, open))
                    assertEquals(quote(text), fresh, dump(HtmlBoxes.build(nodes, MarkdownStyle.chat, open, kept = kept)))
                }
            }
        }
    }

    @Test
    fun `a block the reply grows past keeps its nodes and its box`() {
        val blocks = MarkdownHtml.Blocks()
        val kept = KeptBoxes()
        val before = blocks.render("# Title\n\nFirst paragraph.\n\nSecond", rawHtml = true)
        val after = blocks.render("# Title\n\nFirst paragraph.\n\nSecond one", rawHtml = true)
        assertSame(before[0], after[0])
        assertSame(before[2], after[2])
        assertNotSame(before[4], after[4])
        val first = HtmlBoxes.build(before, MarkdownStyle.chat, kept = kept)
        val second = HtmlBoxes.build(after, MarkdownStyle.chat, kept = kept)
        assertSame(first.children[0], second.children[0])
        assertSame(first.children[1], second.children[1])
        assertNotSame(first.children[2], second.children[2])
    }

    @Test
    fun `raw HTML in a reply has it parsed whole`() {
        val blocks = MarkdownHtml.Blocks()
        val text = "<details>\n<summary>More</summary>\n\nHidden\n\n</details>"
        assertEquals(MarkdownHtml.render(text, rawHtml = true), blocks.render(text, rawHtml = true))
        assertEquals(1, blocks.render(text, rawHtml = true).count { it is SafeHtml.Element })
    }

    /** Everything a box holds, so that two trees compare as text. */
    private fun dump(box: HtmlBox, indent: String = ""): String = buildString {
        append(indent).append(box::class.simpleName).append(" gap=").append(box.gap)
        when (box) {
            is FlowBox -> {
                append(" tag=${box.tag} path=${box.path} style=${box.style} margins=${box.marginTop},${box.marginBottom}")
                append(" padding=${box.paddingStart} border=${box.borderStart} marker=${box.marker} details=${box.details}")
                append(" toggles=${box.toggles} contained=${box.contained} insets=${box.insetTop},${box.insetBottom}\n")
                box.children.forEach { append(dump(it, "$indent  ")) }
            }
            is LinesBox -> {
                val text = box.text
                append(" style=${box.style} text=${quote(text.text)} disclosure=${text.disclosure} boxes=${text.boxes} marks=${text.marks}")
                append(" runs=${text.runs.map { "${it.start}-${it.end}:${it.style}" }}")
                append(" codes=${text.codes.map { "${it.start}-${it.end}:${it.file}" }}")
                append(" links=${text.links.map { "${it.start}-${it.end}:${it.href}" }}\n")
            }
            is CodeBox -> append(" path=${box.path} language=${box.language} diagram=${box.diagram} code=${quote(box.code)}\n")
            is ImageBox -> append(" path=${box.path} src=${box.src} alt=${box.alt} size=${box.width},${box.widthPercent},${box.height} style=${box.style}\n")
            is RuleBox -> append(" path=${box.path}\n")
            is TableBox -> {
                append(" path=${box.path} columns=${box.columns}\n")
                box.rows.forEach { row ->
                    row.forEach { cell ->
                        append("$indent  cell header=${cell.header} colspan=${cell.colspan} valign=${cell.valign}\n")
                        append(dump(cell.box, "$indent    "))
                    }
                }
            }
        }
    }
}
