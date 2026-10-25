package com.hienlh.ppm.chat

import kotlin.math.max

/*
 * A reply's HTML as the boxes Chrome lays it out in under the web's styles: Tailwind's preflight,
 * `.markdown-content` in globals.css, Chrome's own defaults, the presentational attributes the
 * sanitizer lets through and the inline styles react-markdown and its components add. Blocks
 * carry the margins CSS leaves between them once it has collapsed them; each run of inline
 * content is the text a line box shows, white space dealt with as CSS deals with it, and the
 * styles over it.
 */

internal enum class HtmlFont { SANS, MONO, SYSTEM_MONO }

/** Resolved against the theme when drawn: the text's own colour, a quote's, a link's, `mark`'s. */
internal enum class HtmlColor { TEXT, SECONDARY, LINK, MARK }

/**
 * `text-align`. The `-webkit-` values are what `align` on a `div` or a `p` sets, and move the
 * block boxes inside too — a picture in a centred `div` is centred, one in a `text-align: center`
 * paragraph is not.
 */
internal enum class HtmlAlign {
    START, LEFT, RIGHT, CENTER, JUSTIFY, WEBKIT_LEFT, WEBKIT_RIGHT, WEBKIT_CENTER;

    val centred get() = this == CENTER || this == WEBKIT_CENTER
    val toEnd get() = this == RIGHT || this == WEBKIT_RIGHT
}

internal enum class WhiteSpace { NORMAL, NOWRAP, PRE_WRAP, PRE }

internal enum class ListStyle { DISC, DECIMAL, DISCLOSURE_OPEN, DISCLOSURE_CLOSED }

/** `vertical-align` of a table cell. */
internal enum class CellAlign { TOP, MIDDLE, BOTTOM }

/**
 * What an element's text is set in, CSS px: the inherited properties, and the decorations an
 * inline box hands to the text inside it, which nothing inside can take off again.
 */
internal data class HtmlStyle(
    val size: Float,
    val weight: Int,
    val italic: Boolean,
    val font: HtmlFont = HtmlFont.SANS,
    val color: HtmlColor = HtmlColor.TEXT,
    val underline: Boolean = false,
    val lineThrough: Boolean = false,
    /** `abbr[title]`'s `underline dotted`. */
    val dotted: Boolean = false,
    /** How far `sub` and `sup` move their text down, CSS px; up is negative. */
    val shift: Float = 0f,
    val align: HtmlAlign = HtmlAlign.START,
    val whiteSpace: WhiteSpace = WhiteSpace.NORMAL,
    val listStyle: ListStyle = ListStyle.DISC,
)

internal sealed class HtmlBox {
    /** The space above it in what holds it, CSS px: the margins between it and the box before it, collapsed. */
    var gap = 0f
}

/** An item's marker outside its box: `disc` or `decimal`, numbered [value]. */
internal sealed interface HtmlMarker {
    data object Disc : HtmlMarker
    data class Ordinal(val value: Int) : HtmlMarker
}

/**
 * A block box holding block boxes: an element ([tag]; null for the reply itself) or an item.
 * [path] names the element by its element children's indices from the reply's root, as the
 * DOM has them without the wrappers the web's components add.
 */
internal class FlowBox(
    val tag: String?,
    val path: String,
    val style: HtmlStyle,
    val children: List<HtmlBox>,
    /** Its own margins, before any collapse into or out of it. */
    val marginTop: Float = 0f,
    val marginBottom: Float = 0f,
    /** `padding-left`: a list's 1rem, a quote's 0.75rem. */
    val paddingStart: Float = 0f,
    /** `border-left`: a quote's 2px. */
    val borderStart: Float = 0f,
    val marker: HtmlMarker? = null,
    /** A `details` element's number in the reply, which its summary toggles. */
    val details: Int? = null,
    /** A summary: the details it opens and closes. */
    val toggles: Int? = null,
    /** Its children's margins stay inside it (a table cell; the reply, in its flex item). */
    val contained: Boolean = false,
) : HtmlBox() {
    /** The space inside it above its first child and below its last, where margins could not pass out. */
    var insetTop = 0f
    var insetBottom = 0f
}

/** Lines of inline content in the block whose style is [style]. */
internal class LinesBox(val text: RichText, val style: HtmlStyle) : HtmlBox()

/** `MdPre`: [code] without the line ending the `code` element's text ends with. */
internal class CodeBox(val path: String, val code: String, val language: String?, val diagram: Boolean) : HtmlBox()

/**
 * `MdImage`, a block (`display: block` in the preflight). [width] is the `width` attribute's, CSS px
 * or percent; [height] the `height` attribute's, CSS px.
 */
internal class ImageBox(
    val path: String,
    val src: String,
    val alt: String,
    val width: Float?,
    val widthPercent: Boolean,
    val height: Float?,
    /** The text around it, which its alt text is set in, and whose `-webkit-` alignment moves it. */
    val style: HtmlStyle,
) : HtmlBox()

internal class RuleBox(val path: String) : HtmlBox()

/** `MdTable`'s scroller and the table in it: rows of cells, `thead` first and `tfoot` last. */
internal class TableBox(val path: String, val rows: List<List<TableCell>>, val columns: Int) : HtmlBox()

internal class TableCell(val box: FlowBox, val header: Boolean, val colspan: Int, val valign: CellAlign)

/** Text and what runs over it; [runs] cover all of [text], in order. */
internal class RichText(
    val text: String,
    val runs: List<RichRun>,
    val codes: List<RichCode>,
    val links: List<RichLink>,
    val marks: List<IntRange>,
    /** Each checkbox's place in [text], an [HTML_OBJECT], and whether it is ticked. */
    val boxes: List<Pair<Int, Boolean>>,
    /** Set when [text] starts with a summary's disclosure triangle, an [HTML_OBJECT]: whether it points down, its `details` open. */
    val disclosure: Boolean? = null,
)

internal class RichRun(val start: Int, val end: Int, val style: HtmlStyle)

/** An inline `code`'s box from [start] to [end]; [file] is the text MdCode reads a file target from, if it may. */
internal class RichCode(val start: Int, val end: Int, val file: String?)

internal class RichLink(val start: Int, val end: Int, val href: String)

/** Stands in the text for an inline box: a checkbox, or a summary's disclosure triangle. */
internal const val HTML_OBJECT = '￼'

/** What a `details` with no `summary` shows, Chrome's own. */
internal const val DEFAULT_SUMMARY = "Details"

internal object HtmlBoxes {
    /**
     * The reply's root box for text set as [base] says. [open] holds the details opened or closed
     * since, by number; the rest are as their `open` attribute says. [trace] collects every
     * element's style by path.
     */
    fun build(
        nodes: List<SafeHtml>,
        base: MarkdownStyle,
        open: Map<Int, Boolean> = emptyMap(),
        trace: MutableMap<String, HtmlStyle>? = null,
    ): FlowBox {
        val style = HtmlStyle(size = base.size, weight = base.weight.weight, italic = base.italic)
        val builder = Builder(open, trace)
        val root = FlowBox(null, "", style, builder.blocks(nodes, style, ""), contained = true)
        Margins.collapse(root)
        return root
    }
}

private enum class Display { BLOCK, INLINE, NONE }

private val BLOCKS = setOf(
    "p", "div", "section", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "ul", "ol", "li", "dl", "dt", "dd", "hr",
    "table", "details", "summary", "img",
)

private fun display(node: SafeHtml.Element): Display = when {
    // The schema keeps `sr-only` on an `h2` for footnotes' heading, which only a screen reader reads.
    node.name == "source" || (node.name == "h2" && "sr-only" in node.classes()) -> Display.NONE
    node.name in BLOCKS -> Display.BLOCK
    else -> Display.INLINE
}

private fun SafeHtml.Element.text(name: String): String? = when (val value = properties[name]) {
    null -> null
    else -> jsString(value)
}

private fun SafeHtml.Element.number(name: String): Double? = when (val value = properties[name]) {
    is HtmlValue.Number -> value.value
    is HtmlValue.Text -> value.value.trim().toDoubleOrNull()
    else -> null
}

private fun SafeHtml.Element.classes(): List<String> = when (val value = properties["className"]) {
    is HtmlValue.Many -> value.items.map(::jsString)
    is HtmlValue.Text -> value.value.split(' ')
    else -> emptyList()
}

/** The text of [node] and everything in it. */
private fun textContent(node: SafeHtml): String = when (node) {
    is SafeHtml.Text -> node.text
    is SafeHtml.Element -> node.children.joinToString("") { textContent(it) }
}

/** `font-weight: bolder`. */
private fun bolder(weight: Int) = when {
    weight < 350 -> 400
    weight < 550 -> 700
    else -> 900
}

/** MdPre's test for a diagram. */
private val MERMAID = Regex(
    "^(sequenceDiagram|flowchart|graph\\s|classDiagram|stateDiagram|erDiagram|journey|gantt|pie|quadrantChart|requirementDiagram|" +
        "gitGraph|mindmap|timeline|sankey|xychart|block-beta|packet-beta|architecture-beta|kanban)\\b",
)

private class Builder(private val open: Map<Int, Boolean>, private val trace: MutableMap<String, HtmlStyle>?) {
    private var detailsSeen = 0

    /** The block boxes of [nodes], the children of the element at [path], a block container set in [style]. */
    fun blocks(nodes: List<SafeHtml>, style: HtmlStyle, path: String, skip: SafeHtml? = null): List<HtmlBox> {
        val out = ArrayList<HtmlBox>()
        val run = InlineRun(style)
        children(nodes, style, path, run, out, skip)
        run.finish()?.let(out::add)
        return out
    }

    private fun children(nodes: List<SafeHtml>, style: HtmlStyle, path: String, run: InlineRun, out: MutableList<HtmlBox>, skip: SafeHtml? = null) {
        val count = nodes.count { it is SafeHtml.Element }
        var index = -1
        for (node in nodes) {
            when (node) {
                is SafeHtml.Text -> run.text(node.text, style)
                is SafeHtml.Element -> {
                    index++
                    if (node === skip) continue
                    val at = if (path.isEmpty()) "$index" else "$path/$index"
                    when (display(node)) {
                        Display.NONE -> Unit
                        Display.BLOCK -> {
                            run.finish()?.let(out::add)
                            out += block(node, style, at, first = index == 0, last = index == count - 1)
                        }
                        Display.INLINE -> inline(node, style, at, run, out)
                    }
                }
            }
        }
    }

    private fun inline(node: SafeHtml.Element, parent: HtmlStyle, path: String, run: InlineRun, out: MutableList<HtmlBox>) {
        val style = inlineStyle(node, parent)
        trace?.put(path, style)
        when (node.name) {
            "br" -> run.br()
            // The schema lets `type` through as `checkbox` only; any other input is left out.
            "input" -> if (node.text("type") == "checkbox") run.checkbox(node.properties["checked"] != null, style)
            "q" -> {
                // `quotes: auto` in English: “ ” outside, ‘ ’ one level in.
                val depth = run.quoteDepth++
                run.generated(if (depth % 2 == 0) "“" else "‘", style)
                children(node.children, style, path, run, out)
                run.generated(if (depth % 2 == 0) "”" else "’", style)
                run.quoteDepth--
            }
            else -> {
                val code = node.name == "code"
                val file = if (code && node.properties["className"] == null && node.children.all { it is SafeHtml.Text }) textContent(node).trim() else null
                val href = if (node.name == "a") node.text("href") else null
                val handle = when {
                    code -> run.openCode(file)
                    href != null -> run.openLink(href)
                    node.name == "mark" -> run.openMark()
                    else -> null
                }
                children(node.children, style, path, run, out)
                handle?.let(run::close)
            }
        }
    }

    private fun inlineStyle(node: SafeHtml.Element, parent: HtmlStyle): HtmlStyle = when (node.name) {
        "a" -> parent.copy(color = HtmlColor.LINK)
        "b" -> parent.copy(weight = bolder(parent.weight))
        "strong" -> parent.copy(weight = 600)
        "em", "i", "var" -> parent.copy(italic = true)
        "code" -> parent.copy(font = HtmlFont.MONO, size = 12f)
        "kbd", "samp" -> parent.copy(font = HtmlFont.MONO)
        "tt" -> parent.copy(font = HtmlFont.SYSTEM_MONO)
        "small" -> parent.copy(size = parent.size * 0.8f)
        "sub" -> (parent.size * 0.75f).let { parent.copy(size = it, shift = parent.shift + it * 0.25f) }
        "sup" -> (parent.size * 0.75f).let { parent.copy(size = it, shift = parent.shift - it * 0.5f) }
        "u", "ins" -> parent.copy(underline = true)
        "s", "strike", "del" -> parent.copy(lineThrough = true)
        "abbr" -> if (node.properties["title"] != null) parent.copy(dotted = true) else parent
        "mark" -> parent.copy(color = HtmlColor.MARK)
        // Ruby is shown as browsers without it show it, its text after the base; at Chrome's size.
        "rt" -> parent.copy(size = parent.size * 0.5f)
        else -> parent
    }

    private fun block(node: SafeHtml.Element, parent: HtmlStyle, path: String, first: Boolean, last: Boolean): HtmlBox {
        val style = blockStyle(node, parent)
        trace?.put(path, style)
        return when (node.name) {
            "pre" -> pre(node, path)
            "hr" -> RuleBox(path)
            "img" -> image(node, parent, path)
            "table" -> table(node, style, path)
            "details" -> details(node, style, path)
            "summary" -> FlowBox("summary", path, style, blocks(node.children, style, path), marker = HtmlMarker.Disc)
            "li" -> FlowBox("li", path, style, blocks(node.children, style, path), 2f, 2f, marker = HtmlMarker.Disc)
            "ul", "ol" -> list(node, style, path)
            else -> {
                val (top, bottom) = margins(node.name, first, last)
                val padding = if (node.name == "blockquote") 12f else 0f
                val border = if (node.name == "blockquote") 2f else 0f
                FlowBox(node.name, path, style, blocks(node.children, style, path), top, bottom, padding, border)
            }
        }
    }

    private fun blockStyle(node: SafeHtml.Element, parent: HtmlStyle): HtmlStyle {
        val align = node.text("align")?.lowercase()
        return when (node.name) {
            "p" -> parent.copy(whiteSpace = WhiteSpace.PRE_WRAP, align = webkitAlign(align) ?: parent.align)
            "div" -> parent.copy(align = webkitAlign(align) ?: parent.align)
            "h1" -> parent.copy(size = 16f, weight = 700, align = plainAlign(align) ?: parent.align)
            "h2" -> parent.copy(size = 14f, weight = 700, align = plainAlign(align) ?: parent.align)
            "h3" -> parent.copy(size = 14f, weight = 600, align = plainAlign(align) ?: parent.align)
            "h4", "h5", "h6" -> parent.copy(align = plainAlign(align) ?: parent.align)
            "blockquote" -> parent.copy(italic = true, color = HtmlColor.SECONDARY)
            "pre" -> parent.copy(font = HtmlFont.MONO, size = 12f, whiteSpace = WhiteSpace.PRE)
            "ul" -> parent.copy(listStyle = ListStyle.DISC)
            "ol" -> parent.copy(listStyle = ListStyle.DECIMAL)
            "table" -> parent.copy(size = 12f)
            else -> parent
        }
    }

    /** What `align` on a `div` or a `p` sets. */
    private fun webkitAlign(value: String?) = when (value) {
        "center", "middle" -> HtmlAlign.WEBKIT_CENTER
        "left" -> HtmlAlign.WEBKIT_LEFT
        "right" -> HtmlAlign.WEBKIT_RIGHT
        "justify" -> HtmlAlign.JUSTIFY
        else -> null
    }

    /** What `align` on a heading sets, and a table cell's `align` once react-markdown makes it a style. */
    private fun plainAlign(value: String?) = when (value) {
        "center" -> HtmlAlign.CENTER
        "left" -> HtmlAlign.LEFT
        "right" -> HtmlAlign.RIGHT
        "justify" -> HtmlAlign.JUSTIFY
        else -> null
    }

    private fun margins(name: String, first: Boolean, last: Boolean): Pair<Float, Float> = when (name) {
        // `p:first-child` and `p:last-child` drop theirs.
        "p" -> (if (first) 0f else 4f) to (if (last) 0f else 4f)
        "h1" -> 12f to 4f
        "h2" -> 8f to 4f
        "h3" -> 8f to 2f
        "blockquote" -> 4f to 4f
        else -> 0f to 0f
    }

    private fun list(node: SafeHtml.Element, style: HtmlStyle, path: String): FlowBox {
        val children = blocks(node.children, style, path)
        // `ol start` and `li value` set the count; each item after counts on from the one before.
        var next = node.number("start")?.toInt() ?: 1
        val items = children.map { child ->
            if (child !is FlowBox || child.tag != "li") return@map child
            val element = node.children.filterIsInstance<SafeHtml.Element>().getOrNull(child.path.substringAfterLast('/').toInt())
            val value = element?.number("value")?.toInt() ?: next
            next = value + 1
            val marker = if (child.style.listStyle == ListStyle.DECIMAL) HtmlMarker.Ordinal(value) else HtmlMarker.Disc
            FlowBox("li", child.path, child.style, child.children, child.marginTop, child.marginBottom, marker = marker)
        }
        return FlowBox(node.name, path, style, items, 4f, 4f, paddingStart = 16f)
    }

    private fun details(node: SafeHtml.Element, style: HtmlStyle, path: String): FlowBox {
        val id = detailsSeen++
        val opened = open[id] ?: (node.properties["open"] != null)
        val elements = node.children.filterIsInstance<SafeHtml.Element>()
        val index = elements.indexOfFirst { it.name == "summary" }
        val summary = elements.getOrNull(index)
        val disclosure = if (opened) ListStyle.DISCLOSURE_OPEN else ListStyle.DISCLOSURE_CLOSED
        val head = if (summary != null) {
            val at = "$path/$index"
            val summaryStyle = style.copy(listStyle = disclosure)
            trace?.put(at, summaryStyle)
            FlowBox("summary", at, summaryStyle, withDisclosure(blocks(summary.children, summaryStyle, at), summaryStyle, opened), toggles = id)
        } else {
            val summaryStyle = style.copy(listStyle = disclosure)
            val run = InlineRun(summaryStyle)
            run.text(DEFAULT_SUMMARY, summaryStyle)
            FlowBox("summary", "$path/summary", summaryStyle, withDisclosure(listOfNotNull(run.finish()), summaryStyle, opened), toggles = id)
        }
        // Closed, what follows the summary is not laid out at all; the details in it keep their numbers.
        val body = if (opened) {
            blocks(node.children, style, path, skip = summary)
        } else {
            detailsSeen += node.children.sumOf(::countDetails)
            emptyList()
        }
        return FlowBox("details", path, style, listOf(head) + body, details = id)
    }

    private fun countDetails(node: SafeHtml): Int = when (node) {
        is SafeHtml.Text -> 0
        is SafeHtml.Element -> (if (node.name == "details") 1 else 0) + node.children.sumOf(::countDetails)
    }

    /**
     * The disclosure triangle, `list-style-position: inside`: the first thing on the summary's first
     * line, or a line of its own before a block. Chrome paints it as a shape, not a glyph.
     */
    private fun withDisclosure(children: List<HtmlBox>, style: HtmlStyle, opened: Boolean): List<HtmlBox> {
        val first = children.firstOrNull()
        if (first is LinesBox) return listOf(first.withTriangle(opened, style)) + children.drop(1)
        val triangle = RichText(HTML_OBJECT.toString(), listOf(RichRun(0, 1, style)), emptyList(), emptyList(), emptyList(), emptyList(), disclosure = opened)
        return listOf(LinesBox(triangle, style)) + children
    }

    private fun pre(node: SafeHtml.Element, path: String): CodeBox {
        val first = node.children.firstOrNull()
        val code = first as? SafeHtml.Element
        val language = if (code?.name == "code") code.classes().firstOrNull { it.startsWith("language-") }?.removePrefix("language-") else null
        // MdPre reads only the first child; all of it is what a `pre` shows.
        val text = textContent(node).removeSuffix("\n")
        val diagram = language == "mermaid" || (language == null && MERMAID.containsMatchIn(text.trim()))
        return CodeBox(path, text, language, diagram)
    }

    private fun image(node: SafeHtml.Element, parent: HtmlStyle, path: String): ImageBox {
        val src = node.text("src").orEmpty()
        val alt = node.text("alt").orEmpty().ifEmpty { basename(src) }
        val (width, percent) = when (val value = node.properties["width"]) {
            is HtmlValue.Number -> value.value.toFloat() to false
            is HtmlValue.Text -> value.value.trim().let { text ->
                if (text.endsWith("%")) text.dropLast(1).toFloatOrNull() to true else text.toFloatOrNull() to false
            }
            else -> null to false
        }
        return ImageBox(path, src, alt, width, percent, node.number("height")?.toFloat(), parent)
    }

    private fun table(node: SafeHtml.Element, style: HtmlStyle, path: String): TableBox {
        val head = ArrayList<List<TableCell>>()
        val body = ArrayList<List<TableCell>>()
        val foot = ArrayList<List<TableCell>>()
        val sections = node.children.filterIsInstance<SafeHtml.Element>()
        sections.forEachIndexed { index, section ->
            val at = "$path/$index"
            when (section.name) {
                "tr" -> body += row(section, style, at, null)
                "thead", "tbody", "tfoot" -> {
                    trace?.put(at, style)
                    val rows = section.children.filterIsInstance<SafeHtml.Element>()
                    val target = when (section.name) {
                        "thead" -> head
                        "tfoot" -> foot
                        else -> body
                    }
                    rows.forEachIndexed { r, tr -> if (tr.name == "tr") target += row(tr, style, "$at/$r", section) }
                }
            }
        }
        val rows = head + body + foot
        val columns = rows.maxOfOrNull { cells -> cells.sumOf { it.colspan } } ?: 0
        return TableBox(path, rows, columns)
    }

    private fun row(row: SafeHtml.Element, style: HtmlStyle, path: String, section: SafeHtml.Element?): List<TableCell> {
        trace?.put(path, style)
        return row.children.filterIsInstance<SafeHtml.Element>().mapIndexedNotNull { index, cell ->
            if (cell.name != "td" && cell.name != "th") return@mapIndexedNotNull null
            val at = "$path/$index"
            val header = cell.name == "th"
            // `.markdown-content th, td { text-align: left }`, unless react-markdown turned `align` into a style.
            val cellStyle = style.copy(
                weight = if (header) 500 else style.weight,
                align = plainAlign(cell.text("align")?.lowercase()) ?: HtmlAlign.LEFT,
                whiteSpace = if (cell.properties["noWrap"] != null) WhiteSpace.NOWRAP else style.whiteSpace,
            )
            trace?.put(at, cellStyle)
            val valign = (cell.text("vAlign") ?: row.text("vAlign") ?: section?.text("vAlign"))?.lowercase()
            TableCell(
                FlowBox(cell.name, at, cellStyle, blocks(cell.children, cellStyle, at), contained = true),
                header,
                max(1, cell.number("colSpan")?.toInt() ?: 1),
                when (valign) {
                    "top" -> CellAlign.TOP
                    "bottom" -> CellAlign.BOTTOM
                    else -> CellAlign.MIDDLE
                },
            )
        }
    }
}

private fun LinesBox.withTriangle(opened: Boolean, style: HtmlStyle): LinesBox {
    val runs = listOf(RichRun(0, 1, style)) + text.runs.map { RichRun(it.start + 1, it.end + 1, it.style) }
    return LinesBox(
        RichText(
            HTML_OBJECT + text.text,
            runs,
            text.codes.map { RichCode(it.start + 1, it.end + 1, it.file) },
            text.links.map { RichLink(it.start + 1, it.end + 1, it.href) },
            text.marks.map { (it.first + 1)..(it.last + 1) },
            text.boxes.map { (it.first + 1) to it.second },
            disclosure = opened,
        ),
        this.style,
    )
}

/**
 * One anonymous run of inline content as CSS builds its lines: spaces collapse across elements
 * and go at the start and the end of a line unless the white space is preserved, and a forced
 * break that ends the block opens no line after it.
 */
private class InlineRun(private val block: HtmlStyle) {
    private val text = StringBuilder()
    private val runs = ArrayList<RichRun>()
    private val codes = ArrayList<RichCode>()
    private val links = ArrayList<RichLink>()
    private val marks = ArrayList<IntRange>()
    private val boxes = ArrayList<Pair<Int, Boolean>>()
    private val openRanges = ArrayList<Open>()
    private var lineStart = true
    private var collapsibleSpace = false
    private var content = false
    var quoteDepth = 0

    class Open(val kind: Int, var start: Int, val data: String?)

    fun text(value: String, style: HtmlStyle) {
        val preserve = style.whiteSpace == WhiteSpace.PRE_WRAP || style.whiteSpace == WhiteSpace.PRE
        for (c in value) {
            when {
                preserve -> {
                    emit(c, style)
                    lineStart = c == '\n'
                    collapsibleSpace = false
                    content = true
                }
                c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C' -> {
                    if (!lineStart && !collapsibleSpace) {
                        emit(' ', style)
                        collapsibleSpace = true
                    }
                }
                else -> {
                    emit(c, style)
                    lineStart = false
                    collapsibleSpace = false
                    content = true
                }
            }
        }
    }

    /** Text CSS generates (quotes, a marker): no white space in it. */
    fun generated(value: String, style: HtmlStyle) {
        for (c in value) emit(c, style)
        lineStart = false
        collapsibleSpace = value.endsWith(' ')
        content = true
    }

    fun br() {
        dropTrailingSpace()
        emit('\n', null)
        lineStart = true
        content = true
    }

    fun checkbox(checked: Boolean, style: HtmlStyle) {
        boxes += text.length to checked
        emit(HTML_OBJECT, style)
        lineStart = false
        collapsibleSpace = false
        content = true
    }

    fun openCode(file: String?) = open(CODE, file)

    fun openLink(href: String) = open(LINK, href)

    fun openMark() = open(MARK, null)

    private fun open(kind: Int, data: String?) = Open(kind, text.length, data).also(openRanges::add)

    fun close(open: Open) {
        openRanges.remove(open)
        record(open, text.length)
    }

    private fun record(open: Open, end: Int) {
        if (end <= open.start) return
        when (open.kind) {
            CODE -> codes += RichCode(open.start, end, open.data)
            LINK -> links += RichLink(open.start, end, open.data!!)
            else -> marks += open.start until end
        }
    }

    /** Appends [c]; a break ([style] null) takes the style of what comes before it. */
    private fun emit(c: Char, style: HtmlStyle?) {
        val at = text.length
        text.append(c)
        val last = runs.lastOrNull()
        val runStyle = style ?: last?.style ?: block
        if (last != null && last.end == at && last.style == runStyle) {
            runs[runs.lastIndex] = RichRun(last.start, at + 1, runStyle)
        } else {
            runs += RichRun(at, at + 1, runStyle)
        }
    }

    private fun dropTrailingSpace() {
        if (!collapsibleSpace) return
        truncate(text.length - 1)
        collapsibleSpace = false
    }

    private fun truncate(length: Int) {
        text.setLength(length)
        val tail = runs.removeAt(runs.lastIndex)
        if (tail.end - 1 > tail.start) runs += RichRun(tail.start, tail.end - 1, tail.style)
        fun IntRange.clip() = first until minOf(last + 1, length)
        codes.replaceAll { RichCode(it.start, minOf(it.end, length), it.file) }
        codes.removeAll { it.end <= it.start }
        links.replaceAll { RichLink(it.start, minOf(it.end, length), it.href) }
        links.removeAll { it.end <= it.start }
        marks.replaceAll { it.clip() }
        marks.removeAll { it.isEmpty() }
        boxes.removeAll { it.first >= length }
        openRanges.forEach { if (it.start > length) it.start = length }
    }

    /** The lines so far, if anything makes one; what is still open goes on in the next run. */
    fun finish(): LinesBox? {
        dropTrailingSpace()
        for (open in openRanges) record(open, text.length)
        val made = content
        if (made && text.endsWith('\n')) truncate(text.length - 1)
        val box = if (made) LinesBox(RichText(text.toString(), runs.toList(), codes.toList(), links.toList(), marks.toList(), boxes.toList()), block) else null
        text.setLength(0)
        runs.clear()
        codes.clear()
        links.clear()
        marks.clear()
        boxes.clear()
        openRanges.forEach { it.start = 0 }
        lineStart = true
        collapsibleSpace = false
        content = false
        return box
    }

    private companion object {
        const val CODE = 0
        const val LINK = 1
        const val MARK = 2
    }
}

/**
 * CSS's collapsing of vertical margins, for margins that are none of them negative: where two
 * meet the larger wins, a first or last child's passes out through a box with no padding or
 * border on that side, and a box with nothing in it lets its own pass through it.
 */
internal object Margins {
    private class Collapsed(val top: Float, val bottom: Float, val empty: Boolean)

    fun collapse(root: FlowBox) {
        flow(root)
    }

    private fun collapse(box: HtmlBox): Collapsed = when (box) {
        is FlowBox -> flow(box)
        is LinesBox, is ImageBox -> Collapsed(0f, 0f, false)
        is CodeBox, is RuleBox -> Collapsed(8f, 8f, false)
        is TableBox -> {
            box.rows.forEach { row -> row.forEach { flow(it.box) } }
            Collapsed(0f, 0f, false)
        }
    }

    private fun flow(box: FlowBox): Collapsed {
        var running = 0f
        var top = box.marginTop
        var any = false
        for (child in box.children) {
            val c = collapse(child)
            if (c.empty) {
                running = max(running, max(c.top, c.bottom))
                child.gap = 0f
                continue
            }
            if (!any) {
                any = true
                if (box.contained) {
                    box.insetTop = max(running, c.top)
                } else {
                    top = max(top, max(running, c.top))
                }
                child.gap = 0f
            } else {
                child.gap = max(running, c.top)
            }
            running = c.bottom
        }
        // An item's marker makes a line of its own; a table cell keeps its padding.
        if (!any && box.marker == null && !box.contained) {
            val through = max(max(top, box.marginBottom), running)
            return Collapsed(through, through, true)
        }
        if (box.contained) {
            box.insetBottom = running
            return Collapsed(box.marginTop, box.marginBottom, false)
        }
        return Collapsed(top, max(box.marginBottom, running), false)
    }
}
