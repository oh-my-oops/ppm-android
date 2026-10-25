package com.hienlh.ppm.chat

import com.hienlh.ppm.chat.InlineMarkdown.Inline
import com.hienlh.ppm.chat.InlineMarkdown.Kind

/**
 * A reply's markdown as the HTML the web shows once it carries raw HTML: mdast-util-to-hast's
 * elements and the line breaks it puts between them, the raw HTML parsed in among them as
 * rehype-raw parses it, then rehype-sanitize with `markdownSanitizeSchema` and PPM's prefix on
 * `#fragment` links. react-markdown's `urlTransform` is left to whatever draws the result.
 */
internal object MarkdownHtml {
    /** null when [markdown] holds no raw HTML: the markdown views draw that themselves. */
    fun of(markdown: String): List<SafeHtml>? {
        if ('<' !in markdown) return null
        val converter = Converter()
        val hast = converter.root(Markdown.parse(markdown))
        return if (converter.raw) clean(hast) else null
    }

    /** What [of] gives, for markdown with or without raw HTML. */
    fun html(markdown: String): List<SafeHtml> = clean(Converter().root(Markdown.parse(markdown)))

    /**
     * The tree MarkdownRenderer draws. Without [rawHtml] nothing is parsed or sanitised: react-markdown
     * shows each piece of raw HTML as the text it is.
     */
    fun render(markdown: String, rawHtml: Boolean): List<SafeHtml> {
        val hast = Converter().root(Markdown.parse(markdown))
        return if (rawHtml) clean(hast) else sanitizeHtml(parseHast(hast.map(::asText)))
    }

    private fun asText(node: Hast): Hast = when (node) {
        is Hast.Raw -> Hast.Text(node.html)
        is Hast.Element -> Hast.Element(node.name, node.attrs, node.children.map(::asText))
        is Hast.Text -> node
    }

    private fun clean(hast: List<Hast>): List<SafeHtml> = prefixFragments(sanitizeHtml(parseHast(hast)))

    /** `rehypePrefixFragmentLinks`: a link to `#x` points at the id the sanitiser gave `x`. */
    private fun prefixFragments(nodes: List<SafeHtml>): List<SafeHtml> = nodes.map { node ->
        if (node !is SafeHtml.Element) return@map node
        val href = (node.properties["href"] as? HtmlValue.Text)?.value
        val properties = if (node.name == "a" && href != null && href.length > 1 && href.startsWith("#")) {
            node.properties + ("href" to HtmlValue.Text("#${HtmlSchema.CLOBBER_PREFIX}${href.substring(1)}"))
        } else {
            node.properties
        }
        SafeHtml.Element(node.name, properties, prefixFragments(node.children))
    }

    /** mdast-util-to-hast over the app's markdown blocks; [raw] says whether any raw HTML was met. */
    private class Converter {
        var raw = false

        fun root(blocks: List<MarkdownBlock>): List<Hast> = wrap(blocks.map(::block), loose = false)

        private fun block(block: MarkdownBlock): Hast = when (block) {
            is MarkdownBlock.Paragraph -> element("p", inline(block.text))
            is MarkdownBlock.Heading -> element("h${block.level}", inline(block.text))
            is MarkdownBlock.Code -> {
                val language = block.language?.let { listOf(HtmlAttr("class", "language-$it")) }.orEmpty()
                element("pre", listOf(Hast.Element("code", language, listOf(Hast.Text(if (block.text.isEmpty()) "" else block.text + "\n")))))
            }
            is MarkdownBlock.ListBlock -> list(block)
            is MarkdownBlock.Quote -> element("blockquote", wrap(block.blocks.map(::block), loose = true))
            is MarkdownBlock.Table -> table(block)
            MarkdownBlock.Rule -> element("hr")
            is MarkdownBlock.Html -> {
                raw = true
                Hast.Raw(block.text)
            }
        }

        private fun list(list: MarkdownBlock.ListBlock): Hast {
            val attrs = mutableListOf<HtmlAttr>()
            if (list.ordered && list.start != 1) attrs += HtmlAttr("start", list.start.toString())
            if (list.items.any { it.checked != null }) attrs += HtmlAttr("class", "contains-task-list")
            return Hast.Element(if (list.ordered) "ol" else "ul", attrs, wrap(list.items.map { item(it, list.loose) }, loose = true))
        }

        /** A tight list's paragraphs give up their `<p>`; a task item's box opens its first one. */
        private fun item(item: MarkdownListItem, loose: Boolean): Hast {
            val results = item.blocks.map(::block).toMutableList()
            val attrs = mutableListOf<HtmlAttr>()
            if (item.checked != null) {
                val box = Hast.Element(
                    "input",
                    listOfNotNull(HtmlAttr("type", "checkbox"), if (item.checked) HtmlAttr("checked", "") else null, HtmlAttr("disabled", "")),
                    emptyList(),
                )
                val head = results.firstOrNull()
                if (head.isParagraph()) {
                    head as Hast.Element
                    results[0] = Hast.Element("p", head.attrs, listOf(box) + (if (head.children.isEmpty()) emptyList() else listOf(Hast.Text(" "))) + head.children)
                } else {
                    results.add(0, element("p", listOf(box)))
                }
                attrs += HtmlAttr("class", "task-list-item")
            }
            val children = mutableListOf<Hast>()
            results.forEachIndexed { index, child ->
                if (loose || index != 0 || !child.isParagraph()) children += Hast.Text("\n")
                if (child.isParagraph() && !loose) children += (child as Hast.Element).children else children += child
            }
            val tail = results.lastOrNull()
            if (tail != null && (loose || !tail.isParagraph())) children += Hast.Text("\n")
            return Hast.Element("li", attrs, children)
        }

        private fun table(table: MarkdownBlock.Table): Hast {
            val columns = table.aligns.size
            fun row(cells: List<String>, tag: String): Hast {
                val elements = (0 until columns).map { index ->
                    val align = table.aligns[index]?.let { listOf(HtmlAttr("align", it)) }.orEmpty()
                    Hast.Element(tag, align, cells.getOrNull(index)?.let(::inline).orEmpty())
                }
                return element("tr", wrap(elements, loose = true))
            }
            val content = mutableListOf(element("thead", wrap(listOf(row(table.header, "th")), loose = true)))
            if (table.rows.isNotEmpty()) content += element("tbody", wrap(table.rows.map { row(it, "td") }, loose = true))
            return element("table", wrap(content, loose = true))
        }

        private fun inline(text: String): List<Hast> = phrasing(InlineMarkdown.tree(text))

        private fun phrasing(nodes: List<Inline>): List<Hast> {
            val out = mutableListOf<Hast>()
            for (node in nodes) {
                when (node) {
                    is Inline.Text -> breaks(node.text, out)
                    is Inline.Code -> out += element("code", listOf(Hast.Text(node.text)))
                    is Inline.Html -> {
                        raw = true
                        out += Hast.Raw(node.html)
                    }
                    is Inline.Styled -> out += element(
                        when (node.kind) {
                            Kind.STRONG -> "strong"
                            Kind.EMPHASIS -> "em"
                            else -> "del"
                        },
                        phrasing(node.children),
                    )
                    is Inline.Link -> out += Hast.Element(
                        "a",
                        listOfNotNull(HtmlAttr("href", normalizeUri(node.url)), node.title?.let { HtmlAttr("title", it) }),
                        phrasing(node.children),
                    )
                    is Inline.Image -> out += Hast.Element(
                        "img",
                        listOfNotNull(HtmlAttr("src", normalizeUri(node.url)), HtmlAttr("alt", node.alt), node.title?.let { HtmlAttr("title", it) }),
                        emptyList(),
                    )
                }
            }
            return out
        }

        /** remark-breaks: every line ending is a `<br>`, which mdast-util-to-hast follows with one. */
        private fun breaks(text: String, out: MutableList<Hast>) {
            var start = 0
            while (true) {
                val end = text.indexOf('\n', start)
                val piece = text.substring(start, if (end < 0) text.length else end)
                if (piece.isNotEmpty()) out += Hast.Text(piece)
                if (end < 0) return
                out += element("br")
                out += Hast.Text("\n")
                start = end + 1
            }
        }

        private fun element(name: String, children: List<Hast> = emptyList()) = Hast.Element(name, emptyList(), children)

        private fun Hast?.isParagraph() = this is Hast.Element && name == "p"

        /** mdast-util-to-hast's `wrap`: a line break between the nodes, and around them when [loose]. */
        private fun wrap(nodes: List<Hast>, loose: Boolean): List<Hast> {
            val out = mutableListOf<Hast>()
            if (loose) out += Hast.Text("\n")
            nodes.forEachIndexed { index, node ->
                if (index > 0) out += Hast.Text("\n")
                out += node
            }
            if (loose && nodes.isNotEmpty()) out += Hast.Text("\n")
            return out
        }
    }
}

private val TEAMMATE_MESSAGE = Regex("<teammate-message[^>]*>[\\s\\S]*?</teammate-message>")
private val BLANK_LINES = Regex("\n{3,}")

/** MarkdownContent's cleaning: teammate messages out, blank lines down to one, the ends trimmed. Empty: nothing is drawn. */
internal fun markdownContent(text: String): String = text.replace(TEAMMATE_MESSAGE, "").replace(BLANK_LINES, "\n\n").jsTrim()
