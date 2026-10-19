package com.hienlh.ppm.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
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
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFonts
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ThemeTokens
import com.hienlh.ppm.design.ppmText

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

/** A message's markdown, drawn as the web's `.markdown-content` draws it. */
@Composable
fun MarkdownView(text: String, modifier: Modifier = Modifier, style: MarkdownStyle = MarkdownStyle.chat) {
    val blocks = remember(text) { Markdown.parse(text) }
    MarkdownBlocks(blocks, style, modifier)
}

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
        is MarkdownBlock.Code -> CodeBlockView(block.text, modifier)
        is MarkdownBlock.ListBlock -> MarkdownList(block, style, modifier)
        is MarkdownBlock.Quote -> {
            val border = tokens.border.color
            MarkdownBlocks(
                block.blocks,
                style.copy(italic = true, color = tokens.text2),
                modifier
                    .drawBehind { drawRect(border, size = Size(2.dp.toPx(), size.height)) }
                    .padding(start = 14.dp),
            )
        }
        is MarkdownBlock.Table -> MarkdownTable(block.header, block.rows, style, modifier)
        MarkdownBlock.Rule -> Box(modifier.fillMaxWidth().height(1.dp).background(tokens.border.color))
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
                BasicText(
                    if (list.ordered) "${list.start + index}." else "•",
                    Modifier
                        .alignByBaseline()
                        .width(16.dp)
                        .wrapContentWidth(Alignment.End, unbounded = true)
                        .padding(end = if (list.ordered) 4.dp else 6.dp),
                    style = ppmText(style.size, style.weight, lineHeight = style.lineHeight).copy(color = color),
                    softWrap = false,
                )
                if (item.checked != null) {
                    TaskBox(item.checked, Modifier.alignBy { it.measuredHeight - 1 }.padding(end = 5.dp))
                }
                MarkdownBlocks(item.blocks, style, Modifier.alignByBaseline().weight(1f))
            }
        }
    }
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
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val available = constraints.maxWidth
        Box(Modifier.horizontalScroll(rememberScrollState())) {
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
            ) { measurables, _ ->
                val columns = header.size
                val minWidths = IntArray(columns)
                val maxWidths = IntArray(columns)
                measurables.forEachIndexed { index, cell ->
                    val column = index % columns
                    minWidths[column] = maxOf(minWidths[column], cell.minIntrinsicWidth(Constraints.Infinity))
                    maxWidths[column] = maxOf(maxWidths[column], cell.maxIntrinsicWidth(Constraints.Infinity))
                }
                val widths = TableWidths.distribute(minWidths, maxWidths, available)
                val heights = IntArray(cells.size)
                measurables.forEachIndexed { index, cell ->
                    val row = index / columns
                    heights[row] = maxOf(heights[row], cell.minIntrinsicHeight(widths[index % columns]))
                }
                val placeables = measurables.mapIndexed { index, cell ->
                    cell.measure(Constraints.fixed(widths[index % columns], heights[index / columns]))
                }
                layout(widths.sum(), heights.sum()) {
                    var y = 0
                    for (row in cells.indices) {
                        var x = 0
                        for (column in 0 until columns) {
                            placeables[row * columns + column].place(x, y)
                            x += widths[column]
                        }
                        y += heights[row]
                    }
                }
            }
        }
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
                val line = 1.dp.toPx()
                drawRect(border, size = Size(size.width, line))
                drawRect(border, size = Size(line, size.height))
                if (lastColumn) drawRect(border, Offset(size.width - line, 0f), Size(line, size.height))
                if (lastRow) drawRect(border, Offset(0f, size.height - line), Size(size.width, line))
            }
            .padding(start = 1.dp, top = 1.dp, end = if (lastColumn) 1.dp else 0.dp, bottom = if (lastRow) 1.dp else 0.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        // `td` is `vertical-align: middle`, with `text-align: left`.
        contentAlignment = Alignment.CenterStart,
    ) {
        InlineMarkdownText(text, style)
    }
}
