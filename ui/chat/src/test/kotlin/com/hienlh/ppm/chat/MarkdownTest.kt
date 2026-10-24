package com.hienlh.ppm.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.unit.em
import com.hienlh.ppm.chat.InlineMarkdown.Kind
import com.hienlh.ppm.chat.InlineMarkdown.Span
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmFonts
import com.hienlh.ppm.state.workspace.SourceLine
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
    fun `blocks collapse their margins as Chrome does`() {
        // Measured in Chrome: the gaps between the blocks, then what collapses out of the message.
        val first = Markdown.parse(
            "Intro.\n\n```ts\nconst a = 1;\n```\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n> One.\n>\n> Two.\n\n---\n\n" +
                "1. First\n2. Second\n   - nested one\n   - nested two\n3. Third\n\n- loose one\n\n- loose two\n\nLast.",
        )
        assertEquals(listOf(0, 8, 8, 4, 8, 8, 4, 4), MarkdownGaps.tops(first))
        assertEquals(0 to 0, MarkdownGaps.ends(first))
        val ordered = first[5] as MarkdownBlock.ListBlock
        // An item holding a list ends with that list's margin.
        assertEquals(listOf(0, 2, 4), MarkdownGaps.itemTops(ordered.items))
        assertEquals(listOf(0, 4), MarkdownGaps.tops(ordered.items[1].blocks))
        // The quote's paragraphs drop their outer margins and keep the one between them.
        assertEquals(listOf(0, 4), MarkdownGaps.tops((first[3] as MarkdownBlock.Quote).blocks))
        // A table keeps its margins inside its scroller: nothing collapses out, 8 more inside.
        assertEquals(0 to 8, MarkdownGaps.ends(Markdown.parse("| a |\n|---|\n| 1 |\n\n```\ncode\n```")))
        assertEquals(4 to 8, MarkdownGaps.ends(Markdown.parse("> A quote first.\n\n### Three\n\nText.\n\n---")))
        assertEquals(8 to 4, MarkdownGaps.ends(Markdown.parse("```\ncode\n```\n\n- item\n\n## Two\n\n- last")))
        assertEquals(12 to 4, MarkdownGaps.ends(Markdown.parse("# One\n\n- last")))
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
        // Raw HTML stays text; an image is a slot, drawn as a block of its own.
        assertInline("<b>hi</b> ![alt](x.png)", "<b>hi</b> $IMAGE_SLOT")
        assertInline("[not a link]", "[not a link]")
    }

    @Test
    fun `an image is a slot in the text, with its source and its alt as plain text`() {
        val parsed = inline("Before ![a *b*](/tmp/x.png) after, **bold ![](http://h/y.png) still**")
        assertEquals("Before $IMAGE_SLOT after, bold $IMAGE_SLOT still", parsed.text)
        assertEquals(listOf(InlineMarkdown.Image("/tmp/x.png", "a b"), InlineMarkdown.Image("http://h/y.png", "")), parsed.images)
        // The bold runs on across the picture, as the `strong` holds the `<img>`.
        assertEquals(listOf(Span(16, 28, Kind.STRONG)), parsed.spans)
        // A bare address ends where a picture starts.
        assertEquals(listOf(Span(0, 12, Kind.LINK, "http://x.dev")), inline("http://x.dev![a](b.png)").spans)
    }

    @Test
    fun `an answer's pictures are found in every block, in the page's order`() {
        val text = "![a](a.png)\n\n- item ![b](b.png)\n\n> ![c](c.png)\n\n| h |\n|---|\n| ![d](d.png) |\n\n```\n![e](e.png)\n```"
        assertEquals(listOf("a.png", "b.png", "c.png", "d.png"), markdownImages(text).map { it.url })
    }

    @Test
    fun `a picture is read from the server, from the web, or not at all`() {
        assertEquals(MarkdownImageSource.LOCAL, markdownImageSource("/tmp/x.png"))
        assertEquals(MarkdownImageSource.LOCAL, markdownImageSource("C:\\shots\\x.png"))
        assertEquals(MarkdownImageSource.WEB, markdownImageSource("HTTPS://x.dev/a.png"))
        for (src in listOf("images/x.png", "data:image/png;base64,AAAA", "file:///tmp/x.png", "")) {
            assertEquals(src, MarkdownImageSource.NONE, markdownImageSource(src))
        }
    }

    @Test
    fun `the spans around and after a code box take in its edges`() {
        val handler = object : UriHandler {
            override fun openUri(uri: String) = Unit
        }
        val text = inline("**`a`** and *b `c` d*").annotated(BuiltInThemes.auroraDark.tokens, handler, 14f, 0.5.em)
        assertEquals("${CODE_EDGE}a$CODE_EDGE and b ${CODE_EDGE}c$CODE_EDGE d", text.text)
        val weights = text.spanStyles.filter { it.item.fontWeight != null }.map { it.start to it.end }
        assertEquals(listOf(0 to 3), weights)
        val italics = text.spanStyles.filter { it.item.fontStyle != null }.map { it.start to it.end }
        assertEquals(listOf(8 to 15), italics)
        val code = text.spanStyles.filter { it.item.fontFamily == PpmFonts.mono }.map { text.text.substring(it.start, it.end) }
        assertEquals(listOf("a", "c"), code)
        // Each edge is set in a font of its own, as wide as it is given.
        val edges = text.spanStyles.filter { it.item.fontFamily == PpmFonts.codeEdge }
        assertEquals(listOf(0, 2, 10, 12), edges.map { it.start })
        assertTrue(edges.all { it.end == it.start + 1 && it.item.fontSize == 0.5.em })
    }

    @Test
    fun `a line break between a code and the text around it goes outside the code's box`() {
        val handler = object : UriHandler {
            override fun openUri(uri: String) = Unit
        }
        val e = CODE_EDGE
        val j = JOIN
        val b = BREAK
        val text = inline("a/`b` `c/`d `e-`f x-`y` `→`").annotated(BuiltInThemes.auroraDark.tokens, handler, 14f, 1.em)
        // An edge reads as a letter to Android's line breaker: `/` would end a line before it.
        assertEquals("a/$j${e}b$e ${e}c/$j${e}d ${e}e-$e${b}f x-$b${e}y$e $e$j→$j$e", text.text)
        val boxes = text.getStringAnnotations(CODE_BOX, 0, text.length).map { text.text.substring(it.start, it.end) }
        assertEquals(listOf("${e}b$e", "${e}c/$j$e", "${e}e-$e", "${e}y$e", "$e$j→$j$e"), boxes)
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
        val text = parsed.annotated(BuiltInThemes.auroraDark.tokens, handler, 14f, 1.em)
        // The code's box takes room on either side of it, and is drawn rather than set as a background.
        val box = text.getStringAnnotations(CODE_BOX, 0, text.length).single()
        assertEquals("${CODE_EDGE}https:$JOIN//${JOIN}in.code$CODE_EDGE", text.text.substring(box.start, box.end))
        val codeStyle = text.spanStyles.single { it.start == box.start + 1 && it.end == box.end - 1 }
        assertEquals(PpmFonts.mono, codeStyle.item.fontFamily)
        assertEquals((12f / 14f).em, codeStyle.item.fontSize)
        assertEquals(Color.Unspecified, codeStyle.item.background)
        val link = text.getLinkAnnotations(0, text.length).single().item as LinkAnnotation.Url
        assertEquals("https://ppm.dev/x", link.url)
        assertNotNull(link.linkInteractionListener)
        link.linkInteractionListener!!.onClick(link)
        assertEquals(listOf("https://ppm.dev/x"), opened)
    }

    @Test
    fun `a file's code and a link to a file open it, and an unsafe link opens nothing`() {
        val opened = mutableListOf<String>()
        val handler = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
        val files = mutableListOf<Pair<String, SourceLine?>>()
        val tabs = object : ChatTabs {
            override fun openFile(path: String) = Unit

            override fun openDiff(path: String, original: String, modified: String) = Unit

            override fun openToolFile(tool: String, path: String, line: Int?) = Unit

            override fun openFileOrSearch(path: String, line: SourceLine?) {
                files += path to line
            }
        }
        val source = "`src/a.ts:12` `bun test` [doc](docs/my%20guide.md#setup) [x](javascript:alert(1)) [web](<https://x.dev/a b>)"
        val text = inline(source).annotated(BuiltInThemes.auroraDark.tokens, handler, 14f, 1.em, tabs)
        // Only the path is underlined, and the tap takes its box in, edges and all.
        val underlined = text.getStringAnnotations(FILE_CODE, 0, text.length).map { text.text.substring(it.start, it.end) }
        assertEquals(listOf("src/${JOIN}a.ts:12"), underlined)
        val links = text.getLinkAnnotations(0, text.length)
        assertEquals("${CODE_EDGE}src/${JOIN}a.ts:12$CODE_EDGE", text.text.substring(links[0].start, links[0].end))
        assertEquals(listOf("src/${JOIN}a.ts:12", "doc", "x", "web"), links.map { text.text.substring(it.start, it.end).trim(CODE_EDGE) })
        for (link in links) link.item.linkInteractionListener!!.onClick(link.item)
        assertEquals(listOf("src/a.ts" to SourceLine(12), "docs/my guide.md" to null), files)
        assertEquals(listOf("https://x.dev/a%20b"), opened)
    }
}
