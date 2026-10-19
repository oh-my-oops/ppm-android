package com.hienlh.ppm.chat

import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.LinkAnnotation
import com.hienlh.ppm.chat.InlineMarkdown.Kind
import com.hienlh.ppm.chat.InlineMarkdown.Span
import com.hienlh.ppm.design.BuiltInThemes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The markdown rules, without a device (`PPMSelfTest/ChatUITests.swift`, plus the inline parser). */
class MarkdownTest {
    private fun item(text: String) = MarkdownListItem(listOf(MarkdownBlock.Paragraph(text)))

    @Test
    fun `paragraphs, headings and rules`() {
        assertEquals(listOf(MarkdownBlock.Paragraph("a\nb"), MarkdownBlock.Paragraph("c")), Markdown.parse("a\nb\n\nc"))
        assertEquals(listOf(MarkdownBlock.Heading(2, "Title"), MarkdownBlock.Paragraph("#hashtag")), Markdown.parse("## Title ##\n#hashtag"))
        assertEquals(listOf(MarkdownBlock.Heading(1, "Title"), MarkdownBlock.Rule), Markdown.parse("Title\n===\n\n***"))
        // An indented line after a paragraph continues it rather than starting code.
        assertEquals(listOf(MarkdownBlock.Paragraph("para\ncont")), Markdown.parse("para\n    cont"))
    }

    @Test
    fun `code blocks, open and closed`() {
        assertEquals(listOf(MarkdownBlock.Code("swift", "let x = 1", closed = false)), Markdown.parse("```swift\nlet x = 1"))
        assertEquals(listOf(MarkdownBlock.Code("js", "a", closed = true), MarkdownBlock.Paragraph("after")), Markdown.parse("```js\na\n```\nafter"))
        assertEquals(listOf(MarkdownBlock.Code(null, "x", closed = true)), Markdown.parse("~~~\nx\n~~~"))
        assertEquals(listOf(MarkdownBlock.Code(null, "code\nmore", closed = true)), Markdown.parse("    code\n    more"))
        // A backtick fence's info string may not hold a backtick.
        assertEquals(listOf(MarkdownBlock.Paragraph("``` a`b")), Markdown.parse("``` a`b"))
    }

    @Test
    fun lists() {
        assertEquals(listOf(MarkdownBlock.ListBlock(true, 3, listOf(item("x"), item("y")))), Markdown.parse("3. x\n4. y"))
        assertEquals(listOf(MarkdownBlock.ListBlock(false, 1, listOf(item("a"), item("b")))), Markdown.parse("- a\n\n- b"))
        assertEquals(
            listOf(
                MarkdownBlock.ListBlock(
                    false,
                    1,
                    listOf(MarkdownListItem(listOf(MarkdownBlock.Paragraph("a"), MarkdownBlock.ListBlock(false, 1, listOf(item("b")))))),
                ),
            ),
            Markdown.parse("- a\n  - b"),
        )
        assertEquals(
            listOf(
                MarkdownBlock.ListBlock(
                    false,
                    1,
                    listOf(
                        MarkdownListItem(listOf(MarkdownBlock.Paragraph("done")), checked = true),
                        MarkdownListItem(listOf(MarkdownBlock.Paragraph("todo")), checked = false),
                    ),
                ),
            ),
            Markdown.parse("- [x] done\n- [ ] todo"),
        )
        // Only a list starting at 1 may interrupt a paragraph.
        assertEquals(listOf(MarkdownBlock.Paragraph("para\n2. x")), Markdown.parse("para\n2. x"))
    }

    @Test
    fun `quotes and tables`() {
        assertEquals(listOf(MarkdownBlock.Quote(listOf(MarkdownBlock.Paragraph("a\nb")))), Markdown.parse("> a\nb"))
        assertEquals(
            listOf(MarkdownBlock.Table(listOf("a", "b"), listOf(listOf("1", "2")))),
            Markdown.parse("| a | b |\n|---|:-:|\n| 1 | 2 |"),
        )
        assertEquals(
            listOf(MarkdownBlock.Table(listOf("a | b", "c"), listOf(listOf("1", "")))),
            Markdown.parse("| a \\| b | c |\n|---|---|\n| 1 |"),
        )
    }

    @Test
    fun `blocks collapse their margins`() {
        assertEquals(8, MarkdownGaps.gap(MarkdownBlock.Paragraph("a"), MarkdownBlock.Code(null, "", closed = true)))
        assertEquals(12, MarkdownGaps.gap(MarkdownBlock.Paragraph("a"), MarkdownBlock.Heading(1, "")))
        assertEquals(4, MarkdownGaps.gap(MarkdownBlock.Heading(3, ""), MarkdownBlock.Paragraph("a")))
    }

    @Test
    fun `table columns share the line as CSS's auto layout does`() {
        // Room to spare: by content width.
        assertArrayEquals(intArrayOf(60, 80), TableWidths.distribute(intArrayOf(10, 20), intArrayOf(30, 40), 140))
        // Between the narrowest and the widest: each wraps by how much it can.
        assertArrayEquals(intArrayOf(20, 30), TableWidths.distribute(intArrayOf(10, 20), intArrayOf(30, 40), 50))
        // Too narrow even for the longest words: the table scrolls.
        assertArrayEquals(intArrayOf(10, 20), TableWidths.distribute(intArrayOf(10, 20), intArrayOf(30, 40), 20))
    }

    private fun inline(source: String) = InlineMarkdown.parse(source)

    private fun assertInline(source: String, text: String, vararg spans: Span) {
        val parsed = inline(source)
        assertEquals("text of $source", text, parsed.text)
        assertEquals("spans of $source", spans.toList(), parsed.spans)
    }

    @Test
    fun `emphasis follows CommonMark's flanking rules`() {
        assertInline("*foo bar*", "foo bar", Span(0, 7, Kind.EMPHASIS))
        // An opener followed by whitespace opens nothing.
        assertInline("a * foo bar*", "a * foo bar*")
        assertInline("foo*bar*", "foobar", Span(3, 6, Kind.EMPHASIS))
        // `_` does not open or close inside a word.
        assertInline("_foo_bar", "_foo_bar")
        assertInline("snake_case_name", "snake_case_name")
        assertInline("**foo**", "foo", Span(0, 3, Kind.STRONG))
        assertInline("***foo***", "foo", Span(0, 3, Kind.STRONG), Span(0, 3, Kind.EMPHASIS))
        assertInline("*foo**bar**baz*", "foobarbaz", Span(0, 9, Kind.EMPHASIS), Span(3, 6, Kind.STRONG))
        assertInline("**foo*", "*foo", Span(1, 4, Kind.EMPHASIS))
        // The rule of three.
        assertInline("*foo**bar*", "foo**bar", Span(0, 8, Kind.EMPHASIS))
        // Punctuation inside the run still lets it open.
        assertInline("*\"foo\"*", "\"foo\"", Span(0, 5, Kind.EMPHASIS))
    }

    @Test
    fun `code spans, escapes and entities`() {
        assertInline("`a*b*c`", "a*b*c", Span(0, 5, Kind.CODE))
        assertInline("``foo ` bar``", "foo ` bar", Span(0, 9, Kind.CODE))
        assertInline("` `` `", "``", Span(0, 2, Kind.CODE))
        assertInline("`foo", "`foo")
        assertInline("\\*not em\\*", "*not em*")
        assertInline("&amp; &lt;b&gt; &#35; &#x41; &bogus;", "& <b> # A &bogus;")
        // A backslash before a line break is a hard break; the break stays.
        assertInline("a\\\nb", "a\nb")
        assertInline("~~gone~~ ~one~", "gone ~one~", Span(0, 4, Kind.STRIKE))
    }

    @Test
    fun links() {
        assertInline("[ppm](https://ppm.dev)", "ppm", Span(0, 3, Kind.LINK, "https://ppm.dev"))
        assertInline("[a](<b c>) [d](/uri \"title\")", "a d", Span(0, 1, Kind.LINK, "b c"), Span(2, 3, Kind.LINK, "/uri"))
        assertInline("[a [b] c](u)", "a [b] c", Span(0, 7, Kind.LINK, "u"))
        // A link cannot hold another link.
        assertInline("[foo [bar](/uri)](/uri2)", "[foo bar](/uri2)", Span(5, 8, Kind.LINK, "/uri"))
        assertInline("[*a*](u)", "a", Span(0, 1, Kind.EMPHASIS), Span(0, 1, Kind.LINK, "u"))
        assertInline("<https://x.dev> <a@b.co>", "https://x.dev a@b.co", Span(0, 13, Kind.LINK, "https://x.dev"), Span(14, 20, Kind.LINK, "mailto:a@b.co"))
        // Raw HTML stays text; an image is its alt text.
        assertInline("<b>hi</b> ![alt](x.png)", "<b>hi</b> alt")
        assertInline("[not a link]", "[not a link]")
    }

    @Test
    fun `bare URLs become links, minus the sentence's punctuation`() {
        val parsed = inline("see https://ppm.dev/x). and `https://in.code`")
        val links = parsed.spans.filter { it.kind == Kind.LINK }
        assertEquals(listOf("https://ppm.dev/x"), links.map { parsed.text.substring(it.start, it.end) })
        assertEquals(listOf("https://ppm.dev/x"), links.map { it.url })
        val code = parsed.spans.single { it.kind == Kind.CODE }
        assertTrue("inline code must not be linked", links.none { it.start < code.end && code.start < it.end })

        val opened = mutableListOf<String>()
        val handler = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
        val text = parsed.annotated(BuiltInThemes.auroraDark.tokens, handler)
        val codeStyle = text.spanStyles.single { it.start == code.start && it.end == code.end }
        assertEquals("inline code sits on the page background", BuiltInThemes.auroraDark.tokens.bgSolid.color, codeStyle.item.background)
        val link = text.getLinkAnnotations(0, text.length).single().item as LinkAnnotation.Url
        assertEquals("https://ppm.dev/x", link.url)
        assertNotNull(link.linkInteractionListener)
        link.linkInteractionListener!!.onClick(link)
        assertEquals(listOf("https://ppm.dev/x"), opened)
    }
}
