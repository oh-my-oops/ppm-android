package com.hienlh.ppm.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
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
        1 -> copy(size = 16f, weight = FontWeight.Bold)
        2 -> copy(weight = FontWeight.Bold)
        3 -> copy(weight = FontWeight.SemiBold)
        // Tailwind's reset leaves h4–h6 as plain text; the web's markdown styles skip them.
        else -> this
    }

    companion object {
        /** `text-sm`: an assistant message. */
        val chat = MarkdownStyle(size = 14f, lineHeightRatio = 20f / 14f)
    }
}

/** A message's markdown, drawn as the web's `.markdown-content` draws it; [streaming] while it still grows. */
@Composable
fun MarkdownView(text: String, modifier: Modifier = Modifier, style: MarkdownStyle = MarkdownStyle.chat, streaming: Boolean = false) {
    val blocks = remember(text) { Markdown.parse(text) }
    CompositionLocalProvider(LocalMarkdownStreaming provides streaming) {
        MarkdownBlocks(blocks, style, modifier)
    }
}

/** MdPre's test for a diagram: the web draws it with Mermaid, and as plain code until it can. */
private val MERMAID_KEYWORDS = Regex(
    "^(sequenceDiagram|flowchart|graph\\s|classDiagram|stateDiagram|erDiagram|journey|gantt|pie|quadrantChart|requirementDiagram|" +
        "gitGraph|mindmap|timeline|sankey|xychart|block-beta|packet-beta|architecture-beta|kanban)\\b",
)

private fun MarkdownBlock.Code.isDiagram(): Boolean =
    language == "mermaid" || (language == null && MERMAID_KEYWORDS.containsMatchIn(text.trim()))

/** Vertical margins collapse, as in CSS: the gap between two blocks is the larger of the two. */
internal object MarkdownGaps {
    fun gap(above: MarkdownBlock, below: MarkdownBlock): Int = maxOf(margins(above).second, margins(below).first)

    /** `.markdown-content`'s top and bottom margins, in CSS pixels. */
    fun margins(block: MarkdownBlock): Pair<Int, Int> = when (block) {
        is MarkdownBlock.Paragraph, is MarkdownBlock.ListBlock, is MarkdownBlock.Quote -> 4 to 4
        is MarkdownBlock.Heading -> when (block.level) {
            1 -> 12 to 4
            2 -> 8 to 4
            3 -> 8 to 2
            else -> 0 to 0
        }
        is MarkdownBlock.Code, is MarkdownBlock.Table, MarkdownBlock.Rule -> 8 to 8
    }
}

@Composable
internal fun MarkdownBlocks(blocks: List<MarkdownBlock>, style: MarkdownStyle, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        blocks.forEachIndexed { index, block ->
            val top = if (index == 0) 0 else MarkdownGaps.gap(blocks[index - 1], block)
            MarkdownBlockView(block, style, Modifier.padding(top = top.dp))
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

/** One paragraph of inline markdown: bold, italic, `code`, links, strikethrough. */
@Composable
internal fun InlineMarkdownText(source: String, style: MarkdownStyle, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val uriHandler = LocalUriHandler.current
    val parsed = remember(source) { InlineMarkdown.parse(source) }
    val text = remember(parsed, tokens, uriHandler) { parsed.annotated(tokens, uriHandler) }
    BasicText(
        text,
        modifier,
        style = ppmText(style.size, style.weight, lineHeight = style.lineHeight, italic = style.italic)
            .copy(color = (style.color ?: tokens.text).color),
    )
}

/** `.markdown-content code` is 0.75rem whatever the text around it. */
internal const val INLINE_CODE_SIZE = 12f

internal fun InlineMarkdown.Parsed.annotated(tokens: ThemeTokens, uriHandler: UriHandler): AnnotatedString =
    buildAnnotatedString {
        append(text)
        for (span in spans) {
            when (span.kind) {
                // `.markdown-content strong` is 600, even inside a 700 heading.
                InlineMarkdown.Kind.STRONG -> addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), span.start, span.end)
                InlineMarkdown.Kind.EMPHASIS -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), span.start, span.end)
                InlineMarkdown.Kind.STRIKE -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), span.start, span.end)
                InlineMarkdown.Kind.CODE -> addStyle(
                    SpanStyle(fontFamily = PpmFonts.mono, fontSize = INLINE_CODE_SIZE.sp, background = tokens.bgSolid.color),
                    span.start,
                    span.end,
                )
                InlineMarkdown.Kind.LINK -> {
                    val url = span.url ?: continue
                    addLink(
                        LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = tokens.accent.color))) {
                            // A path or an unknown scheme has nothing to open it; that is no crash.
                            runCatching { uriHandler.openUri(url) }
                        },
                        span.start,
                        span.end,
                    )
                }
            }
        }
    }

@Composable
private fun MarkdownList(list: MarkdownBlock.ListBlock, style: MarkdownStyle, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    val color = (style.color ?: tokens.text).color
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        list.items.forEachIndexed { index, item ->
            Row {
                // An outside marker in the list's 1rem padding; a long number hangs further out.
                if (list.ordered) {
                    BasicText(
                        "${list.start + index}.",
                        Modifier
                            .alignByBaseline()
                            .width(16.dp)
                            .wrapContentWidth(Alignment.End, unbounded = true)
                            .padding(end = 4.dp),
                        style = ppmText(style.size, style.weight, lineHeight = style.lineHeight).copy(color = color),
                        softWrap = false,
                    )
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
 * Chrome's `disc` marker, which it draws rather than sets: a circle `(A·2/3 + 1) / 2` px across,
 * its top `A/2` above the baseline and its left edge `⌊A·2/3⌋ + 7` px before the item's text, A
 * being the font's ascent rounded to a pixel. Geist's own bullet glyph is half that size.
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
                val ascent = (style.size.sp.toDp().value * PpmFonts.SANS_ASCENT).roundToInt()
                val across = (ascent * 2f / 3f + 1f) / 2f
                val left = 16f - ascent * 2 / 3 - 7f
                val center = Offset(left + across / 2, -(ascent / 2f) + across / 2) * density
                drawCircle(color, across / 2 * density, center + Offset(0f, baseline))
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
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
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
