package com.hienlh.ppm.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFonts
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ThemeTokens
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssBorderInset
import com.hienlh.ppm.design.cssBorderPx
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * The boxes HtmlBoxes builds of a reply, drawn as Chrome draws the web's `.markdown-content`:
 * blocks stacked by their collapsed margins, an item's marker out in its list's padding, tables
 * in the web's auto layout, and each run of lines one text with each run's styles over it.
 */

/** What every box of one reply is drawn with: the text it is set in, and how a summary opens or closes its details. */
@Immutable
internal class HtmlDrawing(val base: MarkdownStyle, val toggle: (Int, Boolean) -> Unit) {
    /** [style] as the markdown views set text. */
    fun text(style: HtmlStyle, tokens: ThemeTokens) = MarkdownStyle(
        size = style.size,
        lineHeightRatio = base.lineHeightRatio,
        weight = FontWeight(style.weight),
        italic = style.italic,
        color = style.color.rgba(base, tokens),
    )
}

/** Chrome's `MarkText` and `Mark`, whatever the theme. */
private val MARK_TEXT = Rgba(0.0, 0.0, 0.0, 1.0)
private val MARK_BACKGROUND = Color(0xFFFFFF00)

private fun HtmlColor.rgba(base: MarkdownStyle, tokens: ThemeTokens): Rgba = when (this) {
    HtmlColor.TEXT -> base.color ?: tokens.text
    HtmlColor.SECONDARY -> tokens.text2
    HtmlColor.LINK -> tokens.accent
    HtmlColor.MARK -> MARK_TEXT
}

private fun HtmlFont.family(): FontFamily = when (this) {
    HtmlFont.SANS -> PpmFonts.sans
    HtmlFont.MONO -> PpmFonts.mono
    HtmlFont.SYSTEM_MONO -> FontFamily.Monospace
}

private fun HtmlStyle.decoration(): TextDecoration =
    TextDecoration.combine(listOfNotNull(TextDecoration.Underline.takeIf { underline }, TextDecoration.LineThrough.takeIf { lineThrough }))

private fun HtmlAlign.textAlign(): TextAlign = when {
    centred -> TextAlign.Center
    toEnd -> TextAlign.Right
    this == HtmlAlign.JUSTIFY -> TextAlign.Justify
    else -> TextAlign.Start
}

/** A block box, each child [HtmlBox.gap] below the one before; a summary opens and closes its details. */
@Composable
internal fun HtmlFlow(box: FlowBox, drawing: HtmlDrawing, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    var outer = modifier.fillMaxWidth()
    val toggles = box.toggles
    if (toggles != null) {
        val opened = box.style.listStyle == ListStyle.DISCLOSURE_OPEN
        outer = outer.ppmClickable(pressedColor = Color.Transparent, onClickLabel = if (opened) "Close" else "Open") { drawing.toggle(toggles, !opened) }
    }
    if (box.borderStart > 0f) outer = outer.cssBorder(tokens.border.color, width = box.borderStart.dp, sides = CssSides.Start)
    val inner = Modifier.padding(start = box.paddingStart.dp, top = box.insetTop.dp, bottom = box.insetBottom.dp)
    val marker = box.marker
    if (marker == null) {
        Column(outer.then(inner)) { HtmlChildren(box, drawing) }
    } else {
        HtmlItem(marker, box.style, drawing, outer) {
            Column(inner.fillMaxWidth()) { HtmlChildren(box, drawing) }
        }
    }
}

@Composable
private fun HtmlChildren(box: FlowBox, drawing: HtmlDrawing) {
    for (child in box.children) HtmlBoxView(child, drawing, Modifier.padding(top = child.gap.dp))
}

@Composable
private fun HtmlBoxView(box: HtmlBox, drawing: HtmlDrawing, modifier: Modifier) {
    when (box) {
        is FlowBox -> HtmlFlow(box, drawing, modifier)
        is LinesBox -> HtmlLines(box, drawing, modifier)
        is CodeBox -> CodeBlockView(box.code, box.language, modifier, highlight = !box.diagram)
        is ImageBox -> HtmlImage(box, drawing, modifier)
        is RuleBox -> Box(modifier.fillMaxWidth().cssBorder(LocalPpmTokens.current.border.color, sides = CssSides.Top))
        is TableBox -> HtmlTable(box, drawing, modifier)
    }
}

/**
 * An item and its `outside` marker: the item's box where the list's padding leaves it, the marker
 * in that padding on the item's first baseline, or a line of its own when the item has none.
 */
@Composable
private fun HtmlItem(marker: HtmlMarker, style: HtmlStyle, drawing: HtmlDrawing, modifier: Modifier, content: @Composable () -> Unit) {
    val tokens = LocalPpmTokens.current
    val text = drawing.text(style, tokens)
    val color = style.color.rgba(drawing.base, tokens).color
    Layout(
        content = {
            content()
            when (marker) {
                HtmlMarker.Disc -> DiscMarker(text, color, Modifier)
                is HtmlMarker.Ordinal -> OrdinalMarker("${marker.value}. ", text, color, Modifier)
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val body = measurables[0].measure(constraints)
        val mark = measurables[1].measure(Constraints())
        val baseline = body[FirstBaseline]
        val y = if (baseline == AlignmentLine.Unspecified) 0 else baseline - mark[FirstBaseline]
        layout(body.width, max(body.height, y + mark.height)) {
            body.place(0, 0)
            mark.place(-mark.width, y)
        }
    }
}

/** Annotates the text of an `abbr[title]`, underlined with dots: its size, font and colour, as `size;FONT;COLOR`. */
private const val DOTTED = "ppm.dotted"

/** Annotates the text of a `mark`, with its size. */
private const val MARK = "ppm.mark"

/** The inline content a checkbox is, ticked or not, and a summary's disclosure triangle, down or not. */
private const val CHECKED_BOX = "ppm.checkbox.checked"
private const val UNCHECKED_BOX = "ppm.checkbox"
private const val OPEN_TRIANGLE = "ppm.disclosure.open"
private const val CLOSED_TRIANGLE = "ppm.disclosure"

/**
 * [this] for Android to set as Chrome sets it in a block of [block]'s text: each run's styles over
 * its characters, an inline code's box on [CODE_EDGE]s [edge] em wide, lines ending where Chrome
 * ends them ([breakMark]), a checkbox or disclosure triangle an inline box. A link opens what it
 * names, a file through [tabs]; a code that names a file opens it (`MdLink`, `MdCode`).
 */
internal fun RichText.annotated(
    block: HtmlStyle,
    base: MarkdownStyle,
    tokens: ThemeTokens,
    uriHandler: UriHandler,
    edge: TextUnit,
    tabs: ChatTabs? = null,
): AnnotatedString {
    val codeStarts = codes.mapTo(HashSet()) { it.start }
    val codeEnds = codes.mapTo(HashSet()) { it.end }
    val checkboxes = boxes.toMap()
    // Where a range starting at each offset starts, taking in a code's start edge; where one
    // ending there ends, taking in a code's end edge; and where the character itself is.
    val starts = IntArray(text.length + 1)
    val ends = IntArray(text.length + 1)
    val at = IntArray(text.length + 1)
    return buildAnnotatedString {
        for (index in 0..text.length) {
            val codeEnd = index in codeEnds
            val codeStart = index in codeStarts
            if (codeEnd) {
                if (joinsCodeEnd(text[index - 1])) append(JOIN)
                append(CODE_EDGE)
            }
            ends[index] = length
            breakMark(text, index, codeEnd, codeStart)?.let { append(it) }
            starts[index] = length
            if (codeStart) {
                append(CODE_EDGE)
                if (joinsCodeStart(text[index])) append(JOIN)
            }
            at[index] = length
            if (index == text.length) break
            val checked = checkboxes[index]
            when {
                index == 0 && disclosure != null -> appendInlineContent(if (disclosure) OPEN_TRIANGLE else CLOSED_TRIANGLE, HTML_OBJECT.toString())
                checked != null -> appendInlineContent(if (checked) CHECKED_BOX else UNCHECKED_BOX, HTML_OBJECT.toString())
                else -> append(text[index])
            }
        }
        // A code's edges take no run's styles: their size is the block's, whatever is around them.
        for (run in runs) {
            val start = at[run.start]
            val end = at[run.end - 1] + 1
            run.style.spanOver(block, base, tokens)?.let { addStyle(it, start, end) }
            if (run.style.dotted) addStringAnnotation(DOTTED, "${run.style.size};${run.style.font.name};${run.style.color.name}", start, end)
        }
        val edgeStyle = SpanStyle(fontFamily = PpmFonts.codeEdge, fontSize = edge)
        for (code in codes) {
            val start = starts[code.start]
            val end = ends[code.end]
            addStringAnnotation(CODE_BOX, "", start, end)
            addStyle(edgeStyle, start, start + 1)
            addStyle(edgeStyle, end - 1, end)
            // The whole `<code>` is what a tap lands on, its padding included.
            val file = code.file?.let(FileTargets::inlineCode) ?: continue
            addLink(LinkAnnotation.Clickable(FILE_CODE) { tabs?.openFileOrSearch(file.path, file.line) }, start, end)
            addStringAnnotation(FILE_CODE, "", start + 1, end - 1)
        }
        for (link in links) {
            val file = FileTargets.link(link.href)
            val open = FileTargets.url(link.href)
            addLink(
                // `a:hover { text-decoration: underline }`.
                LinkAnnotation.Url(open ?: link.href, TextLinkStyles(hoveredStyle = SpanStyle(textDecoration = TextDecoration.Underline))) {
                    when {
                        file != null -> tabs?.openFileOrSearch(file.path, file.line)
                        // No app may take the scheme; that is no crash.
                        open != null -> runCatching { uriHandler.openUri(open) }
                    }
                },
                starts[link.start],
                ends[link.end],
            )
        }
        for (mark in marks) {
            val size = runs.first { mark.first < it.end }.style.size
            addStringAnnotation(MARK, size.toString(), starts[mark.first], ends[mark.last + 1])
        }
    }
}

/** What a run sets differently from the [block] it is in; null when nothing. */
private fun HtmlStyle.spanOver(block: HtmlStyle, base: MarkdownStyle, tokens: ThemeTokens): SpanStyle? {
    if (this == block) return null
    return SpanStyle(
        color = if (color != block.color) color.rgba(base, tokens).color else Color.Unspecified,
        // In em: a span's size in sp is rounded to a whole pixel.
        fontSize = if (size != block.size) (size / block.size).em else TextUnit.Unspecified,
        fontWeight = if (weight != block.weight) FontWeight(weight) else null,
        fontStyle = if (italic != block.italic) (if (italic) FontStyle.Italic else FontStyle.Normal) else null,
        fontFamily = if (font != block.font) font.family() else null,
        textDecoration = if (underline != block.underline || lineThrough != block.lineThrough) decoration() else null,
        // `sub` and `sup` move their text and leave the line; Android shifts by a fraction of the ascent, up.
        baselineShift = if (shift != block.shift) BaselineShift((block.shift - shift) / (size * PpmFonts.SANS_ASCENT)) else null,
    )
}

/** A block of [block]'s text. */
private fun textStyle(block: HtmlStyle, base: MarkdownStyle, tokens: ThemeTokens): TextStyle {
    val kind = if (block.font == HtmlFont.MONO) FontKind.MONO else FontKind.SANS
    return ppmText(block.size, FontWeight(block.weight), kind, lineHeight = block.size * base.lineHeightRatio, italic = block.italic).copy(
        color = block.color.rgba(base, tokens).color,
        fontFamily = block.font.family(),
        textAlign = block.align.textAlign(),
        textDecoration = block.decoration(),
    )
}

/** A block's lines. */
@Composable
private fun HtmlLines(box: LinesBox, drawing: HtmlDrawing, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    val uriHandler = LocalUriHandler.current
    val tabs = LocalChatTabs.current
    val density = LocalDensity.current
    val fonts = LocalFontFamilyResolver.current
    val block = box.style
    val base = drawing.base
    val edge = with(density) { (codeEdgePx() / block.size.sp.toPx()).em }
    val text = remember(box.text, block, base, tokens, uriHandler, tabs, edge) { box.text.annotated(block, base, tokens, uriHandler, edge, tabs) }
    val codes = remember(text) { text.getStringAnnotations(CODE_BOX, 0, text.length) }
    val files = remember(text) { text.getStringAnnotations(FILE_CODE, 0, text.length) }
    val dotted = remember(text) { text.getStringAnnotations(DOTTED, 0, text.length).groupBy { it.item } }
    val marks = remember(text) { text.getStringAnnotations(MARK, 0, text.length) }
    val drawn = codes.isNotEmpty() || dotted.isNotEmpty() || marks.isNotEmpty()
    val color = block.color.rgba(base, tokens).color
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val side = with(density) { CHECKBOX.dp.toSp() }
    val checkboxes = if (box.text.boxes.isEmpty()) emptyMap() else remember(side) { checkboxes(side) }
    val inline = if (box.text.disclosure == null) {
        checkboxes
    } else {
        remember(checkboxes, block.size, color, density) { checkboxes + disclosures(with(density) { block.size.dp.toPx() }, color, density) }
    }
    val style = textStyle(block, base, tokens)
    val measurer = rememberTextMeasurer()
    BasicText(
        text,
        modifier
            .fillMaxWidth()
            .then(if (checkboxes.isEmpty()) Modifier else Modifier.plainDepthUnderLastLine(measurer, style))
            .then(
                if (!drawn) {
                    Modifier
                } else {
                    Modifier.drawBehind {
                        val shown = layout ?: return@drawBehind
                        drawMarks(shown, marks)
                        drawCodeBoxes(shown, codes, tokens)
                        drawDottedUnderlines(shown, files, color, fonts)
                        for ((item, ranges) in dotted) {
                            val (size, font, tint) = item.split(';')
                            drawDottedUnderlines(shown, ranges, HtmlColor.valueOf(tint).rgba(base, tokens).color, fonts, size.toFloat(), HtmlFont.valueOf(font).family())
                        }
                    }
                },
            ),
        style = style,
        onTextLayout = { if (drawn) layout = it },
        softWrap = block.whiteSpace != WhiteSpace.NOWRAP && block.whiteSpace != WhiteSpace.PRE,
        inlineContent = inline,
    )
}

/**
 * Compose gives every line of a paragraph the ascent and descent it works out for the first one
 * (`LineHeightStyleSpan`), so a checkbox that makes the first line taller moves every baseline down
 * by as much and takes it off the last line's depth: a task wrapping onto a second line came out
 * 3 px short at 2x. Chrome grows that first line alone. The baselines already stand where Chrome's do,
 * so this gives the last one back the depth a line of plain text has under it.
 */
private fun Modifier.plainDepthUnderLastLine(measurer: TextMeasurer, style: TextStyle): Modifier = layout { measurable, constraints ->
    val lines = measurable.measure(constraints)
    val plain = measurer.measure("x", style)
    val depth = (plain.size.height - plain.firstBaseline).roundToInt()
    val last = lines[LastBaseline]
    val height = if (last == AlignmentLine.Unspecified) lines.height else max(lines.height, last + depth)
    layout(lines.width, height) { lines.place(0, 0) }
}

/** A checkbox's side, CSS px: Chrome's on Android. */
private const val CHECKBOX = 16

private fun checkboxes(side: TextUnit): Map<String, InlineTextContent> {
    // `vertical-align: baseline`: an inline-block with no line in it stands its bottom on the baseline.
    val placeholder = Placeholder(side, side, PlaceholderVerticalAlign.AboveBaseline)
    return mapOf(
        CHECKED_BOX to InlineTextContent(placeholder) { HtmlCheckbox(true) },
        UNCHECKED_BOX to InlineTextContent(placeholder) { HtmlCheckbox(false) },
    )
}

/**
 * A summary's `::marker` for `disclosure-open`/`-closed` in a font of [fontPx], as Chrome paints
 * it (`ListMarkerPainter`, measured at 100px): a triangle in a square 0.66 em on a side standing
 * on the baseline, in a marker 0.86 em wide. Shorter than the ascent, it leaves the line be.
 * Android rounds a placeholder up to whole pixels, so the marker is given the nearest whole width
 * (24.06 px at 14px and 2x would push the text a pixel right) and the square is drawn at its own size.
 */
private fun disclosures(fontPx: Float, color: Color, density: Density): Map<String, InlineTextContent> {
    val side = 0.66f * fontPx
    val width = with(density) { ((0.86f * fontPx).roundToInt() - 0.01f).toSp() }
    val placeholder = Placeholder(width, with(density) { side.toSp() }, PlaceholderVerticalAlign.AboveBaseline)
    return mapOf(
        OPEN_TRIANGLE to InlineTextContent(placeholder) { HtmlDisclosure(true, side, color) },
        CLOSED_TRIANGLE to InlineTextContent(placeholder) { HtmlDisclosure(false, side, color) },
    )
}

/** `GetCanonicalDisclosurePath`: down or right, in a square [side] px on a side whose bottom is the baseline. */
@Composable
private fun HtmlDisclosure(open: Boolean, side: Float, color: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val top = size.height - side
        val path = Path().apply {
            if (open) {
                moveTo(0f, top + 0.07f * side)
                lineTo(0.5f * side, top + 0.93f * side)
                lineTo(side, top + 0.07f * side)
            } else {
                moveTo(0f, top)
                lineTo(0.86f * side, top + 0.5f * side)
                lineTo(0f, top + side)
            }
            close()
        }
        drawPath(path, color)
    }
}

/**
 * Chrome's own disabled checkbox (`NativeThemeBase::PaintCheckbox`) in its light colours, the web
 * setting no `color-scheme`: a grey layer that lightens whatever is under it, the faded white
 * background, then a 1px faded grey border rounded 2px, or ticked, a faded grey fill and tick.
 */
@Composable
private fun HtmlCheckbox(checked: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        val line = cssBorderPx(1.dp).toFloat()
        val radius = CornerRadius(2.dp.toPx())
        val inset = line * 0.2f
        val inner = Size(size.width - 2 * inset, size.height - 2 * inset)
        drawRoundRect(LIGHTEN_LAYER, Offset(inset, inset), inner, radius)
        drawRoundRect(DISABLED_WHITE, Offset(inset, inset), inner, radius)
        if (checked) {
            drawRoundRect(DISABLED_GREY, Offset(inset, inset), inner, radius)
            val w = size.width
            val h = size.height
            val tick = Path().apply {
                moveTo(w * 0.2f, h * 0.5f)
                lineTo(w * 0.4f, h * 0.7f)
                lineTo(w * 0.8f, h * 0.2f)
            }
            drawPath(tick, DISABLED_WHITE, style = Stroke(h * 0.6f * 0.16f, cap = StrokeCap.Butt, join = StrokeJoin.Miter))
        } else {
            drawRoundRect(DISABLED_GREY, Offset(line / 2, line / 2), Size(size.width - line, size.height - line), radius, style = Stroke(line))
        }
    }
}

private val LIGHTEN_LAYER = Color(169, 169, 169, 0x33)
private val DISABLED_GREY = Color(118, 118, 118, 0x4D)
private val DISABLED_WHITE = Color(255, 255, 255, 0x99)

/**
 * `mark`'s background: Chrome's `Mark` over the text's own box on each line, from the font's
 * ascent above the baseline to its descent below, as Chrome rounds them.
 */
private fun DrawScope.drawMarks(layout: TextLayoutResult, marks: List<AnnotatedString.Range<String>>) {
    for (mark in marks) {
        val (ascent, descent) = PpmFonts.chromeAscentDescent(mark.item.toFloat().sp.toPx(), PpmFonts.SANS_ASCENT, PpmFonts.SANS_DESCENT)
        val first = layout.getLineForOffset(mark.start)
        val last = layout.getLineForOffset(mark.end - 1)
        for (line in first..last) {
            val start = maxOf(mark.start, layout.getLineStart(line))
            val end = minOf(mark.end, layout.getLineEnd(line, visibleEnd = true))
            if (end <= start) continue
            val left = layout.getBoundingBox(start).left
            val right = layout.getBoundingBox(end - 1).right
            val baseline = layout.getLineBaseline(line)
            drawRect(MARK_BACKGROUND, Offset(left, baseline - ascent), Size(right - left, ascent + descent))
        }
    }
}

/**
 * `MdImage` in its block, which the `-webkit-` alignment of the text around it moves: Chrome
 * centres a block in a `div align="center"`, and leaves one at the start in `text-align: center`.
 */
@Composable
private fun HtmlImage(box: ImageBox, drawing: HtmlDrawing, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    val align = when (box.style.align) {
        HtmlAlign.WEBKIT_CENTER -> Alignment.CenterHorizontally
        HtmlAlign.WEBKIT_RIGHT -> Alignment.End
        else -> Alignment.Start
    }
    val size = if (box.width == null && box.height == null) null else ImageSize(box.width, box.widthPercent, box.height)
    Column(modifier.fillMaxWidth(), horizontalAlignment = align) {
        MarkdownImage(InlineMarkdown.Image(box.src, box.alt), drawing.text(box.style, tokens), size)
    }
}

/**
 * `MdTable`: the table in a scroller that keeps its `0.5rem` margins inside, its columns as the
 * web's auto table layout sizes them, a cell spanning the columns its `colspan` says.
 */
@Composable
private fun HtmlTable(box: TableBox, drawing: HtmlDrawing, modifier: Modifier) {
    val grid = remember(box) { TableGrid(box) }
    val policy = remember(grid) { HtmlTableLayout(grid) }
    Layout(
        content = {
            grid.cells.forEachIndexed { index, cell -> HtmlCell(cell, grid.closesEnd[index], grid.closesBottom[index], drawing) }
        },
        // The scroller passes the line's width on as its minimum, its maximum being endless.
        modifier.padding(vertical = 8.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
        policy,
    )
}

/**
 * A `th` or `td`: `padding: 0.25rem 0.5rem`, a header on the page's background. Collapsed borders:
 * each cell draws its top and start edges, and its end or bottom where no cell follows.
 */
@Composable
private fun HtmlCell(cell: TableCell, closesEnd: Boolean, closesBottom: Boolean, drawing: HtmlDrawing) {
    val tokens = LocalPpmTokens.current
    val border = tokens.border.color
    Box(
        Modifier
            .then(if (cell.header) Modifier.background(tokens.bgSolid.color) else Modifier)
            .drawBehind {
                val line = cssBorderPx(1.dp).toFloat()
                drawRect(border, size = Size(size.width, line))
                drawRect(border, size = Size(line, size.height))
                if (closesEnd) drawRect(border, Offset(size.width - line, 0f), Size(line, size.height))
                if (closesBottom) drawRect(border, Offset(0f, size.height - line), Size(size.width, line))
            }
            .cssBorderInset(start = 1.dp, top = 1.dp, end = if (closesEnd) 1.dp else 0.dp, bottom = if (closesBottom) 1.dp else 0.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = when (cell.valign) {
            CellAlign.TOP -> Alignment.TopStart
            CellAlign.MIDDLE -> Alignment.CenterStart
            CellAlign.BOTTOM -> Alignment.BottomStart
        },
    ) {
        HtmlFlow(cell.box, drawing)
    }
}

/** Where each of a table's cells sits: its row, its first column, how many it spans, and which of its edges close the table. */
private class TableGrid(box: TableBox) {
    val columns = box.columns
    val rows = box.rows.size
    val cells = box.rows.flatten()
    val row = IntArray(cells.size)
    val column = IntArray(cells.size)
    val span = IntArray(cells.size)
    val closesEnd = BooleanArray(cells.size)
    val closesBottom = BooleanArray(cells.size)

    init {
        // The columns each row reaches; a row with fewer cells leaves the rest of the grid empty.
        val reach = box.rows.map { cells -> cells.sumOf { it.colspan } }
        var index = 0
        box.rows.forEachIndexed { r, cells ->
            var c = 0
            for (cell in cells) {
                row[index] = r
                column[index] = c
                span[index] = cell.colspan
                c += cell.colspan
                closesEnd[index] = c == reach[r]
                closesBottom[index] = r == box.rows.lastIndex || reach[r + 1] < c
                index++
            }
        }
    }
}

/**
 * [TableGrid]'s cells at the widths [TableWidths] shares out to fill the width the scroller passes
 * on as its minimum, every cell as tall as its row. Its intrinsic sizes are every column at its
 * content's narrowest, or widest, as CSS sizes a table inside a box that sizes itself from it.
 */
private class HtmlTableLayout(private val grid: TableGrid) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val widths = widths(measurables, constraints.minWidth.takeIf { it > 0 })
        val heights = heights(measurables, widths)
        val xs = IntArray(grid.columns + 1).also { for (c in 0 until grid.columns) it[c + 1] = it[c] + widths[c] }
        val ys = IntArray(grid.rows + 1).also { for (r in 0 until grid.rows) it[r + 1] = it[r] + heights[r] }
        val placeables = measurables.mapIndexed { index, cell ->
            cell.measure(Constraints.fixed(spanWidth(index, widths), heights[grid.row[index]]))
        }
        return layout(xs[grid.columns], ys[grid.rows]) {
            placeables.forEachIndexed { index, cell -> cell.place(xs[grid.column[index]], ys[grid.row[index]]) }
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
        columnWidths(measurables) { it.minIntrinsicWidth(Constraints.Infinity) }.sum()

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
        columnWidths(measurables) { it.maxIntrinsicWidth(Constraints.Infinity) }.sum()

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) =
        heights(measurables, widths(measurables, width.takeIf { it != Constraints.Infinity })).sum()

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) =
        minIntrinsicHeight(measurables, width)

    private fun spanWidth(index: Int, widths: IntArray) = (grid.column[index] until grid.column[index] + grid.span[index]).sumOf { widths[it] }

    /** Each column's width, filling [fill]; with nothing to fill, each at its widest. */
    private fun widths(cells: List<IntrinsicMeasurable>, fill: Int?): IntArray {
        val min = columnWidths(cells) { it.minIntrinsicWidth(Constraints.Infinity) }
        val max = columnWidths(cells) { it.maxIntrinsicWidth(Constraints.Infinity) }
        return TableWidths.distribute(min, max, fill ?: max.sum())
    }

    /**
     * The largest [size] of each column's cells: the cells in one column first, then what a cell
     * spanning several needs past them, shared out evenly.
     */
    private inline fun columnWidths(cells: List<IntrinsicMeasurable>, size: (IntrinsicMeasurable) -> Int): IntArray {
        val widths = IntArray(grid.columns)
        cells.forEachIndexed { index, cell ->
            if (grid.span[index] == 1) widths[grid.column[index]] = max(widths[grid.column[index]], size(cell))
        }
        cells.forEachIndexed { index, cell ->
            val span = grid.span[index]
            if (span == 1) return@forEachIndexed
            val from = grid.column[index]
            val short = size(cell) - (from until from + span).sumOf { widths[it] }
            if (short > 0) for (k in 0 until span) widths[from + k] += short / span + if (k < short % span) 1 else 0
        }
        return widths
    }

    private fun heights(cells: List<IntrinsicMeasurable>, widths: IntArray): IntArray {
        val heights = IntArray(grid.rows)
        cells.forEachIndexed { index, cell ->
            val row = grid.row[index]
            heights[row] = max(heights[row], cell.minIntrinsicHeight(spanWidth(index, widths)))
        }
        return heights
    }
}
