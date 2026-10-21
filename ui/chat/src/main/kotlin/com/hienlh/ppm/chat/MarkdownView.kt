package com.hienlh.ppm.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFonts
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ThemeTokens
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssBorderInset
import com.hienlh.ppm.design.cssBorderPx
import com.hienlh.ppm.design.ppmText
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * How markdown text is set. Line heights are ratios, as Tailwind's are: a 12px table cell inside
 * 14px/20px text gets 12 × 20/14.
 */
@Immutable
data class MarkdownStyle(
    val size: Float,
    val lineHeightRatio: Float,
    val weight: FontWeight = FontWeight.Normal,
    val italic: Boolean = false,
    /** Null: the theme's text colour. */
    val color: Rgba? = null,
) {
    val lineHeight: Float get() = size * lineHeightRatio

    fun heading(level: Int): MarkdownStyle = when (level) {
        // `1rem` and `0.875rem`, whatever size the text around them is.
        1 -> copy(size = 16f, weight = FontWeight.Bold)
        2 -> copy(size = 14f, weight = FontWeight.Bold)
        3 -> copy(size = 14f, weight = FontWeight.SemiBold)
        // Tailwind's reset leaves h4–h6 as plain text; the web's markdown styles skip them.
        else -> this
    }

    companion object {
        /** `text-sm`: an assistant message. */
        val chat = MarkdownStyle(size = 14f, lineHeightRatio = 20f / 14f)
    }
}

/**
 * A message's markdown, drawn as the web's `.markdown-content` draws it; [streaming] while it still
 * grows. The web holds it in a flex item or a scroller, which stops the margins collapsing out of
 * its first and last block, so they show.
 */
@Composable
fun MarkdownView(text: String, modifier: Modifier = Modifier, style: MarkdownStyle = MarkdownStyle.chat, streaming: Boolean = false) {
    val blocks = remember(text) { Markdown.parse(text) }
    val (top, bottom) = remember(blocks) { MarkdownGaps.ends(blocks) }
    CompositionLocalProvider(LocalMarkdownStreaming provides streaming) {
        MarkdownBlocks(blocks, style, modifier.padding(top = top.dp, bottom = bottom.dp))
    }
}

/** MdPre's test for a diagram: the web draws it with Mermaid, and as plain code until it can. */
private val MERMAID_KEYWORDS = Regex(
    "^(sequenceDiagram|flowchart|graph\\s|classDiagram|stateDiagram|erDiagram|journey|gantt|pie|quadrantChart|requirementDiagram|" +
        "gitGraph|mindmap|timeline|sankey|xychart|block-beta|packet-beta|architecture-beta|kanban)\\b",
)

private fun MarkdownBlock.Code.isDiagram(): Boolean =
    language == "mermaid" || (language == null && MERMAID_KEYWORDS.containsMatchIn(text.trim()))

/**
 * `.markdown-content`'s vertical margins, in CSS pixels, collapsing as CSS collapses them: where two
 * meet the larger wins, and a first or last child's passes out through a list, an item or a quote,
 * which have no padding or border above or below to stop it. Only a paragraph drops its own at
 * either end of what holds it (`p:first-child`, `p:last-child`).
 */
internal object MarkdownGaps {
    /** `margin: 0.5rem 0` on a table, kept inside the `overflow-x-auto` that wraps it. */
    const val TABLE_INSET = 8

    /** The space above each of [blocks]; the first one's margin collapses out of them. */
    fun tops(blocks: List<MarkdownBlock>): List<Int> =
        blocks.indices.map { if (it == 0) 0 else maxOf(margins(blocks, it - 1).second, margins(blocks, it).first) }

    /** The margins that collapse out of the top and the bottom of [blocks]. */
    fun ends(blocks: List<MarkdownBlock>): Pair<Int, Int> =
        if (blocks.isEmpty()) 0 to 0 else margins(blocks, 0).first to margins(blocks, blocks.lastIndex).second

    /** The space above each of a list's [items]. */
    fun itemTops(items: List<MarkdownListItem>): List<Int> =
        items.indices.map { if (it == 0) 0 else maxOf(item(items[it - 1]).second, item(items[it]).first) }

    /** The top and bottom margins of the [index]th of [blocks], with what collapses out of its children. */
    fun margins(blocks: List<MarkdownBlock>, index: Int): Pair<Int, Int> = when (val block = blocks[index]) {
        is MarkdownBlock.Paragraph -> (if (index == 0) 0 else 4) to (if (index == blocks.lastIndex) 0 else 4)
        is MarkdownBlock.Heading -> when (block.level) {
            1 -> 12 to 4
            2 -> 8 to 4
            3 -> 8 to 2
            else -> 0 to 0
        }
        is MarkdownBlock.ListBlock ->
            maxOf(4, block.items.firstOrNull()?.let { item(it).first } ?: 0) to maxOf(4, block.items.lastOrNull()?.let { item(it).second } ?: 0)
        is MarkdownBlock.Quote -> ends(block.blocks).let { (top, bottom) -> maxOf(4, top) to maxOf(4, bottom) }
        is MarkdownBlock.Code, MarkdownBlock.Rule -> 8 to 8
        // Its scroller holds the table's own ([TABLE_INSET]) and has none to meet another block's.
        is MarkdownBlock.Table -> 0 to 0
    }

    /** An `li`'s, `0.125rem` each, with its first and last child's. */
    private fun item(item: MarkdownListItem): Pair<Int, Int> = ends(item.blocks).let { (top, bottom) -> maxOf(2, top) to maxOf(2, bottom) }
}

@Composable
internal fun MarkdownBlocks(blocks: List<MarkdownBlock>, style: MarkdownStyle, modifier: Modifier = Modifier) {
    val tops = remember(blocks) { MarkdownGaps.tops(blocks) }
    Column(modifier.fillMaxWidth()) {
        blocks.forEachIndexed { index, block ->
            MarkdownBlockView(block, style, Modifier.padding(top = tops[index].dp))
        }
    }
}

@Composable
private fun MarkdownBlockView(block: MarkdownBlock, style: MarkdownStyle, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    when (block) {
        is MarkdownBlock.Paragraph -> InlineMarkdownText(block.text, style, modifier)
        is MarkdownBlock.Heading -> InlineMarkdownText(block.text, style.heading(block.level), modifier)
        is MarkdownBlock.Code -> CodeBlockView(block.text, block.language, modifier, highlight = !block.isDiagram())
        is MarkdownBlock.ListBlock -> MarkdownList(block, style, modifier)
        is MarkdownBlock.Quote -> {
            val border = tokens.border.color
            MarkdownBlocks(
                block.blocks,
                style.copy(italic = true, color = tokens.text2),
                modifier
                    .cssBorder(border, width = 2.dp, sides = CssSides.Start)
                    .padding(start = 12.dp),
            )
        }
        is MarkdownBlock.Table -> MarkdownTable(block.header, block.rows, style, modifier)
        MarkdownBlock.Rule -> Box(modifier.fillMaxWidth().cssBorder(tokens.border.color, sides = CssSides.Top))
    }
}

/** One paragraph of inline markdown: bold, italic, `code`, links, strikethrough, and pictures between its lines. */
@Composable
internal fun InlineMarkdownText(source: String, style: MarkdownStyle, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val uriHandler = LocalUriHandler.current
    val parsed = remember(source) { InlineMarkdown.parse(source) }
    val text = remember(parsed, tokens, uriHandler, style.size) { parsed.annotated(tokens, uriHandler, style.size) }
    if (parsed.images.isEmpty()) {
        InlineText(text, style, modifier)
        return
    }
    // Each `<img>` is a block, which cuts the paragraph's lines: the text either side of one keeps
    // lines of its own, a break included, and whitespace alone none.
    val pieces = remember(text) { text.cutAt(IMAGE_SLOT) }
    val slots = remember(parsed) { parsed.text.indices.filter { parsed.text[it] == IMAGE_SLOT } }
    Column(modifier) {
        pieces.forEachIndexed { index, piece ->
            if (piece.isNotBlank() || '\n' in piece) InlineText(piece, style)
            parsed.images.getOrNull(index)?.let { MarkdownImage(it, style.around(parsed.spans, slots[index], tokens)) }
        }
    }
}

/** [this] cut at every [at], each piece keeping its styles. */
private fun AnnotatedString.cutAt(at: Char): List<AnnotatedString> {
    val pieces = mutableListOf<AnnotatedString>()
    var start = 0
    for (index in indices) {
        if (this[index] != at) continue
        pieces += subSequence(start, index)
        start = index + 1
    }
    pieces += subSequence(start, length)
    return pieces
}

@Composable
private fun InlineText(text: AnnotatedString, style: MarkdownStyle, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val boxes = remember(text) { text.getStringAnnotations(CODE_BOX, 0, text.length) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    BasicText(
        text,
        if (boxes.isEmpty()) modifier else modifier.drawBehind { layout?.let { drawCodeBoxes(it, boxes, tokens) } },
        style = ppmText(style.size, style.weight, lineHeight = style.lineHeight, italic = style.italic)
            .copy(color = (style.color ?: tokens.text).color),
        onTextLayout = { if (boxes.isNotEmpty()) layout = it },
        inlineContent = codeEdges(),
    )
}

/** `.markdown-content code` is 0.75rem whatever the text around it. */
internal const val INLINE_CODE_SIZE = 12f

/**
 * What an inline code's box stands on at either side, so that its padding and border take room on
 * the line as Chrome gives them: a private-use character, which a line breaker takes for a letter,
 * so that a line ends neither between it and the code nor anywhere a letter would not let it.
 */
internal const val CODE_EDGE = ''

/** The placeholders over the [CODE_EDGE]s before a code and after it. */
private const val CODE_START_ID = "code-start"
private const val CODE_END_ID = "code-end"

/** Annotates each inline code's box: the code and the [CODE_EDGE]s around it. */
internal const val CODE_BOX = "ppm.code-box"

/** One side of a code's box: `padding-inline: 0.25rem` and the 1px border, 12.5 px at 2.625. */
private fun Density.codeEdgePx(): Float = 4.dp.toPx() + cssBorderPx(1.dp)

/**
 * The [CODE_EDGE]s' placeholders. Android makes one a whole number of pixels, rounding up, so the
 * pair share the two sides' width instead: what follows a code starts where Chrome starts it.
 */
@Composable
private fun codeEdges(): Map<String, InlineTextContent> {
    val density = LocalDensity.current
    return remember(density) {
        with(density) {
            val edge = codeEdgePx()
            val start = floor(edge)
            fun placeholder(px: Float) = InlineTextContent(Placeholder((px - 0.01f).toSp(), 0.sp, PlaceholderVerticalAlign.AboveBaseline)) {}
            mapOf(CODE_START_ID to placeholder(start), CODE_END_ID to placeholder(2 * edge - start))
        }
    }
}

/** The text with its spans, for text of [size] CSS pixels. */
internal fun InlineMarkdown.Parsed.annotated(tokens: ThemeTokens, uriHandler: UriHandler, size: Float): AnnotatedString {
    val code = spans.filter { it.kind == InlineMarkdown.Kind.CODE }.sortedBy { it.start }
    // Where an offset of the text lands once every code has an edge before and after it.
    fun at(offset: Int) = offset + code.sumOf { if (it.end <= offset) 2 else if (it.start < offset) 1 else 0 }
    return buildAnnotatedString {
        var last = 0
        for (span in code) {
            append(text, last, span.start)
            val start = length
            appendInlineContent(CODE_START_ID, CODE_EDGE.toString())
            append(text, span.start, span.end)
            appendInlineContent(CODE_END_ID, CODE_EDGE.toString())
            addStringAnnotation(CODE_BOX, "", start, length)
            last = span.end
        }
        append(text, last, text.length)
        for (span in spans) {
            val start = at(span.start)
            val end = at(span.end)
            when (span.kind) {
                // `.markdown-content strong` is 600, even inside a 700 heading.
                InlineMarkdown.Kind.STRONG -> addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), start, end)
                InlineMarkdown.Kind.EMPHASIS -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
                InlineMarkdown.Kind.STRIKE -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, end)
                // In em: a span's size in sp is rounded to a whole pixel, 31.5 to 32 at 2.625, and every character set wider.
                InlineMarkdown.Kind.CODE -> addStyle(SpanStyle(fontFamily = PpmFonts.mono, fontSize = (INLINE_CODE_SIZE / size).em), start + 1, end - 1)
                InlineMarkdown.Kind.LINK -> {
                    val url = span.url ?: continue
                    addLink(
                        LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = tokens.accent.color))) {
                            // A path or an unknown scheme has nothing to open it; that is no crash.
                            runCatching { uriHandler.openUri(url) }
                        },
                        start,
                        end,
                    )
                }
            }
        }
    }
}

/**
 * `:not(pre) > code`'s box: the page's background, a 1px border rounded 4px, `padding: 0.125rem
 * 0.25rem`. Chrome draws one piece of it on each line the code runs over (`box-decoration-break:
 * slice`), with no border or rounding where it goes on, and above and below it reaches past the
 * code's own ascent and descent by its padding and border, as any inline box does.
 */
private fun DrawScope.drawCodeBoxes(layout: TextLayoutResult, boxes: List<AnnotatedString.Range<String>>, tokens: ThemeTokens) {
    val border = cssBorderPx(1.dp).toFloat()
    val edge = codeEdgePx()
    val size = INLINE_CODE_SIZE.sp.toPx()
    val (ascent, descent) = PpmFonts.chromeAscentDescent(size, PpmFonts.MONO_ASCENT, PpmFonts.MONO_DESCENT)
    val above = ascent + 2.dp.toPx() + border
    val below = descent + 2.dp.toPx() + border
    val radius = 4.dp.toPx()
    // A box that goes on is drawn this much wider, so that the piece kept has neither its border nor its corners.
    val reach = radius + border
    for (box in boxes) {
        val first = layout.getLineForOffset(box.start)
        val last = layout.getLineForOffset(box.end - 1)
        for (line in first..last) {
            val start = maxOf(box.start, layout.getLineStart(line))
            val end = minOf(box.end, layout.getLineEnd(line))
            val visible = minOf(end, maxOf(start, layout.getLineEnd(line, visibleEnd = true)))
            val baseline = layout.getLineBaseline(line)
            // A side is its edge's outer end, where Chrome's is: the two edges are as wide as Chrome's
            // together. Alone on a line the box went on to, the end edge is half a pixel over, so that
            // side is measured from the code. Spaces where the line breaks hang inside the piece.
            // Chrome snaps it to whole pixels.
            val left = if (line == first) layout.getBoundingBox(box.start).left else layout.getBoundingBox(start).left
            val right = if (line == last) {
                if (line == first) layout.getBoundingBox(box.end - 1).right else layout.getBoundingBox(box.end - 2).right + edge
            } else {
                // On the first line the code starts where its start edge, a whole pixel, ends: short of Chrome's.
                val short = if (line == first) edge - layout.getBoundingBox(box.start).width else 0f
                (if (visible > start) layout.getBoundingBox(visible - 1).right else left) + (end - visible) * PpmFonts.MONO_ADVANCE * size + short
            }
            val x0 = left.roundToInt().toFloat()
            val x1 = right.roundToInt().toFloat()
            val top = (baseline - above).roundToInt().toFloat()
            val bottom = (baseline + below).roundToInt().toFloat()
            val from = if (line > first) x0 - reach else x0
            val to = if (line < last) x1 + reach else x1
            clipRect(x0, top, x1, bottom) {
                drawRoundRect(tokens.bgSolid.color, Offset(from, top), Size(to - from, bottom - top), CornerRadius(radius))
                drawRoundRect(
                    tokens.border.color,
                    Offset(from + border / 2, top + border / 2),
                    Size(to - from - border, bottom - top - border),
                    CornerRadius(radius - border / 2),
                    style = Stroke(border),
                )
            }
        }
    }
}

@Composable
private fun MarkdownList(list: MarkdownBlock.ListBlock, style: MarkdownStyle, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    val color = (style.color ?: tokens.text).color
    val tops = remember(list) { MarkdownGaps.itemTops(list.items) }
    Column(modifier) {
        list.items.forEachIndexed { index, item ->
            Row(Modifier.padding(top = tops[index].dp)) {
                // An outside marker in the list's 1rem padding, ending at the text; a long number hangs
                // further out. Chrome's `::marker` is "1. ", space and all, in `tabular-nums`.
                if (list.ordered) {
                    OrdinalMarker("${list.start + index}. ", style, color, Modifier.alignByBaseline())
                } else {
                    DiscMarker(style, color, Modifier.alignByBaseline())
                }
                if (item.checked != null) {
                    TaskBox(item.checked, Modifier.alignBy { it.measuredHeight - 1 }.padding(end = 5.dp))
                }
                MarkdownBlocks(item.blocks, style, Modifier.alignByBaseline().weight(1f))
            }
        }
    }
}

/**
 * An ordered item's marker, ending on the item's text to the fraction of a pixel as Chrome's does:
 * Compose gives a text a whole number of pixels, rounding up, so the end alone would leave it short.
 */
@Composable
private fun OrdinalMarker(text: String, style: MarkdownStyle, color: Color, modifier: Modifier) {
    var short by remember { mutableFloatStateOf(0f) }
    BasicText(
        text,
        modifier
            .width(16.dp)
            .wrapContentWidth(Alignment.End, unbounded = true)
            .drawWithContent { translate(left = short) { this@drawWithContent.drawContent() } },
        style = ppmText(style.size, style.weight, lineHeight = style.lineHeight).copy(color = color, fontFeatureSettings = "tnum"),
        onTextLayout = { short = it.size.width - it.getLineRight(0) },
        softWrap = false,
    )
}

/**
 * Chrome's `disc` marker, which it draws rather than sets (`ListMarker`): with T = ⌊A·2/3⌋, a
 * circle `⌊(T + 1) / 2⌋` across, its left edge `T + 7` before the item's text and its top
 * `A − ⌊3(A − T) / 2⌋` above the baseline, A being the font's ascent rounded to a pixel. Device
 * pixels, every one: Chrome on Android lays out in them (zoom for DSF), so the 7 stays 7 on any
 * screen. Geist's own bullet glyph is half that size.
 */
@Composable
private fun DiscMarker(style: MarkdownStyle, color: Color, modifier: Modifier) {
    var baseline by remember { mutableFloatStateOf(0f) }
    // A line of the item's text with nothing in it, for the baseline to align to and draw on.
    BasicText(
        BREAK.toString(),
        modifier
            .width(16.dp)
            .drawBehind {
                val ascent = (style.size.sp.toPx() * PpmFonts.SANS_ASCENT).roundToInt()
                val third = ascent * 2 / 3
                val across = (third + 1) / 2
                val left = size.width - third - 7
                val top = baseline - ascent + 3 * (ascent - third) / 2
                drawCircle(color, across / 2f, Offset(left + across / 2f, top + across / 2f))
            },
        style = ppmText(style.size, style.weight, lineHeight = style.lineHeight),
        onTextLayout = { baseline = it.firstBaseline },
    )
}

/** A GFM task item's (disabled) checkbox. */
@Composable
private fun TaskBox(checked: Boolean, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(3.dp)
    val box = if (checked) {
        Modifier.background(tokens.accent.color, shape)
    } else {
        Modifier.border(1.dp, tokens.text3.color, shape)
    }
    Box(modifier) {
        Box(box.size(13.dp), contentAlignment = Alignment.Center) {
            if (checked) PpmIcon(ProductIcon.Check, 10.dp, tokens.accentFg.color)
        }
    }
}

/**
 * A GFM table as the web's auto table layout sets it: columns as wide as their content, the
 * table filling the line, scrolling sideways (`overflow-x-auto`) only when even the narrowest
 * columns do not fit.
 */
@Composable
private fun MarkdownTable(header: List<String>, rows: List<List<String>>, style: MarkdownStyle, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    val cellStyle = style.copy(size = 12f)
    val cells = listOf(header) + rows
    val policy = remember(header.size) { TableLayout(header.size) }
    Layout(
        content = {
            cells.forEachIndexed { row, values ->
                values.forEachIndexed { column, value ->
                    TableCell(
                        value,
                        style = if (row == 0) cellStyle.copy(weight = FontWeight.Medium) else cellStyle,
                        background = if (row == 0) tokens.bgSolid.color else Color.Transparent,
                        border = tokens.border.color,
                        lastColumn = column == header.lastIndex,
                        lastRow = row == cells.lastIndex,
                    )
                }
            }
        },
        // The scroller passes the line's width on as its minimum, its maximum being endless.
        modifier.padding(vertical = MarkdownGaps.TABLE_INSET.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
        policy,
    )
}

/**
 * Rows of [columns] cells, the columns sized by [TableWidths] to fill the width the scroller
 * passes on as its minimum. It answers intrinsic sizes too, as CSS does for a table whose width
 * is a percentage of a box that sizes itself from it (a badge that shrinks to fit): every column
 * at its content's narrowest, or widest.
 */
private class TableLayout(private val columns: Int) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val widths = widths(measurables, constraints.minWidth.takeIf { it > 0 })
        val heights = heights(measurables, widths)
        val placeables = measurables.mapIndexed { index, cell ->
            cell.measure(Constraints.fixed(widths[index % columns], heights[index / columns]))
        }
        return layout(widths.sum(), heights.sum()) {
            var y = 0
            for (row in heights.indices) {
                var x = 0
                for (column in 0 until columns) {
                    placeables[row * columns + column].place(x, y)
                    x += widths[column]
                }
                y += heights[row]
            }
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
        IntArray(columns).also { fold(measurables, it) { cell -> cell.minIntrinsicWidth(Constraints.Infinity) } }.sum()

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
        IntArray(columns).also { fold(measurables, it) { cell -> cell.maxIntrinsicWidth(Constraints.Infinity) } }.sum()

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) =
        heights(measurables, widths(measurables, width.takeIf { it != Constraints.Infinity })).sum()

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) =
        minIntrinsicHeight(measurables, width)

    /** Each column's width, filling [fill]; with nothing to fill, each at its widest. */
    private fun widths(cells: List<IntrinsicMeasurable>, fill: Int?): IntArray {
        val min = IntArray(columns).also { fold(cells, it) { cell -> cell.minIntrinsicWidth(Constraints.Infinity) } }
        val max = IntArray(columns).also { fold(cells, it) { cell -> cell.maxIntrinsicWidth(Constraints.Infinity) } }
        return TableWidths.distribute(min, max, fill ?: max.sum())
    }

    private fun heights(cells: List<IntrinsicMeasurable>, widths: IntArray): IntArray {
        val heights = IntArray((cells.size + columns - 1) / columns)
        cells.forEachIndexed { index, cell ->
            val row = index / columns
            heights[row] = maxOf(heights[row], cell.minIntrinsicHeight(widths[index % columns]))
        }
        return heights
    }

    /** The largest of [size] over each column's cells, into [into]. */
    private inline fun fold(cells: List<IntrinsicMeasurable>, into: IntArray, size: (IntrinsicMeasurable) -> Int) {
        cells.forEachIndexed { index, cell -> into[index % columns] = maxOf(into[index % columns], size(cell)) }
    }
}

/** CSS's auto table layout, for a table of `width: 100%`. */
internal object TableWidths {
    fun distribute(min: IntArray, max: IntArray, available: Int): IntArray {
        val minSum = min.sum()
        val maxSum = max.sum()
        return when {
            // Wider than the content: the extra goes to each column by its content's width.
            maxSum <= available -> {
                if (maxSum == 0) return IntArray(max.size) { available / max.size.coerceAtLeast(1) }
                val widths = IntArray(max.size) { (max[it].toLong() * available / maxSum).toInt() }
                widths[widths.lastIndex] += available - widths.sum()
                widths
            }
            // Between: each column gives up room by how much it could wrap.
            minSum < available -> {
                val room = available - minSum
                val wrap = maxSum - minSum
                val widths = IntArray(max.size) { min[it] + ((max[it] - min[it]).toLong() * room / wrap).toInt() }
                widths[widths.lastIndex] += available - widths.sum()
                widths
            }
            else -> min.copyOf()
        }
    }
}

@Composable
private fun TableCell(
    text: String,
    style: MarkdownStyle,
    background: Color,
    border: Color,
    lastColumn: Boolean,
    lastRow: Boolean,
) {
    Box(
        Modifier
            .background(background)
            // Collapsed borders: each cell draws its top and left edges; the last row and column
            // close the table.
            .drawBehind {
                val line = cssBorderPx(1.dp).toFloat()
                drawRect(border, size = Size(size.width, line))
                drawRect(border, size = Size(line, size.height))
                if (lastColumn) drawRect(border, Offset(size.width - line, 0f), Size(line, size.height))
                if (lastRow) drawRect(border, Offset(0f, size.height - line), Size(size.width, line))
            }
            .cssBorderInset(start = 1.dp, top = 1.dp, end = if (lastColumn) 1.dp else 0.dp, bottom = if (lastRow) 1.dp else 0.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        // `td` is `vertical-align: middle`, with `text-align: left`.
        contentAlignment = Alignment.CenterStart,
    ) {
        InlineMarkdownText(text, style)
    }
}
