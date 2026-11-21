package com.hienlh.ppm.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
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
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
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
import com.hienlh.ppm.design.ChromeCheckbox
import com.hienlh.ppm.design.ChromeFace
import com.hienlh.ppm.design.ChromeStrut
import com.hienlh.ppm.design.ChromeText
import com.hienlh.ppm.design.CssColumn
import com.hienlh.ppm.design.CssExact
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFonts
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ThemeTokens
import com.hienlh.ppm.design.addChromeAtomic
import com.hienlh.ppm.design.addChromeInline
import com.hienlh.ppm.design.chromeScrollbar
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssBorderInset
import com.hienlh.ppm.design.cssBorderPx
import com.hienlh.ppm.design.cssExact
import com.hienlh.ppm.design.cssMeasure
import com.hienlh.ppm.design.cssPhase
import com.hienlh.ppm.design.cssPhased
import com.hienlh.ppm.design.cssReport
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberCssExact
import kotlin.math.floor
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

private fun HtmlFont.face(): ChromeFace = when (this) {
    HtmlFont.SANS -> ChromeFace.Sans
    HtmlFont.MONO -> ChromeFace.Mono
    HtmlFont.SYSTEM_MONO -> ChromeFace.SystemMono
}

private fun HtmlStyle.decoration(): TextDecoration =
    TextDecoration.combine(listOfNotNull(TextDecoration.Underline.takeIf { underline }, TextDecoration.LineThrough.takeIf { lineThrough }))

private fun HtmlAlign.textAlign(): TextAlign = when {
    centred -> TextAlign.Center
    toEnd -> TextAlign.Right
    this == HtmlAlign.JUSTIFY -> TextAlign.Justify
    else -> TextAlign.Start
}

/**
 * A block box, each child [HtmlBox.gap] below the one before; a summary opens and closes its details.
 * Stacked as Chrome stacks blocks, in fractions of a pixel: at 2.625x a paragraph of three lines
 * is 157.5 px and the 4 px under it 10.5, where whole pixels would put the next block one lower.
 */
@Composable
internal fun HtmlFlow(box: FlowBox, drawing: HtmlDrawing, modifier: Modifier = Modifier, lent: CssExact? = null) {
    val tokens = LocalPpmTokens.current
    var outer = modifier.fillMaxWidth()
    val toggles = box.toggles
    if (toggles != null) {
        val opened = box.style.listStyle == ListStyle.DISCLOSURE_OPEN
        outer = outer.ppmClickable(pressedColor = Color.Transparent, onClickLabel = if (opened) "Close" else "Open") { drawing.toggle(toggles, !opened) }
    }
    if (box.borderStart > 0f) outer = outer.cssBorder(tokens.border.color, width = box.borderStart.dp, sides = CssSides.Start)
    val inner = Modifier.padding(start = box.paddingStart.dp)
    val gaps = remember(box) { box.children.map { it.gap.dp } }
    val own = rememberCssExact()
    val exact = lent ?: own
    val marker = box.marker
    if (marker == null) {
        CssColumn(outer.then(inner), top = box.insetTop.dp, bottom = box.insetBottom.dp, exact = exact, gaps = gaps) { HtmlChildren(box, drawing) }
    } else {
        // The item's own box is as tall as its body, which the column around counts exactly.
        HtmlItem(marker, box.style, drawing, outer.cssExact(exact)) {
            CssColumn(inner.fillMaxWidth(), top = box.insetTop.dp, bottom = box.insetBottom.dp, exact = exact, gaps = gaps) { HtmlChildren(box, drawing) }
        }
    }
}

@Composable
private fun HtmlChildren(box: FlowBox, drawing: HtmlDrawing) {
    // The reply's own blocks draw in layers of their own, so the block a streamed reply grows draws again alone.
    val layer = if (box.tag == null) Modifier.graphicsLayer() else Modifier
    for (child in box.children) HtmlBoxView(child, drawing, layer)
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
        // A marker on the item's first line leaves the item as tall as its body: its own line,
        // whole pixels, would make every item of 52.5 px a pixel taller and the list drift down.
        val height = if (baseline == AlignmentLine.Unspecified) max(body.height, mark.height) else body.height
        layout(body.width, height) {
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
                index == 0 && disclosure != null -> {
                    appendInlineContent(if (disclosure) OPEN_TRIANGLE else CLOSED_TRIANGLE, HTML_OBJECT.toString())
                    // Shorter than the text's ascent, it never grows the line; counted all the same.
                    addChromeAtomic(DISCLOSURE * block.size, length - 1, length)
                }
                checked != null -> {
                    appendInlineContent(if (checked) CHECKED_BOX else UNCHECKED_BOX, HTML_OBJECT.toString())
                    addChromeAtomic(CHECKBOX.toFloat(), length - 1, length)
                }
                else -> append(text[index])
            }
        }
        // A code's edges take no run's styles: their size is the block's, whatever is around them.
        for (run in runs) {
            val start = at[run.start]
            val end = at[run.end - 1] + 1
            run.style.spanOver(block, base, tokens)?.let { addStyle(it, start, end) }
            if (run.style.dotted) addStringAnnotation(DOTTED, "${run.style.size};${run.style.font.name};${run.style.color.name}", start, end)
            // Each inline box sizes the lines it is on from its own face and size, its line height
            // the ratio the reply's text inherits; `sub` and `sup` have `line-height: 0`.
            val style = run.style
            if (style.font != block.font || style.size != block.size || style.shift != block.shift) {
                addChromeInline(style.font.face(), style.size, if (style.shift != 0f) 0f else style.size * base.lineHeightRatio, start, end)
            }
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
        baselineShift = if (shift != block.shift) BaselineShift((block.shift - shift) / (size * PpmFonts.SANS_ANDROID_ASCENT)) else null,
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

/**
 * A block's lines, each line box as tall and each baseline where Chrome has them ([ChromeText]): a
 * line holding an inline code or a checkbox grows by what its box reaches past the text's, and
 * lines 52.5 px apart at 2.625x stay 52.5 apart.
 */
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
    val side = with(density) { CHECKBOX.dp.toSp() }
    val checkboxes = if (box.text.boxes.isEmpty()) emptyMap() else remember(side) { checkboxes(side) }
    val inline = if (box.text.disclosure == null) {
        checkboxes
    } else {
        remember(checkboxes, block.size, color, density) { checkboxes + disclosures(with(density) { block.size.dp.toPx() }, color, density) }
    }
    val strut = remember(block, base) { ChromeStrut(block.font.face(), block.size, block.size * base.lineHeightRatio) }
    ChromeText(
        text,
        textStyle(block, base, tokens),
        strut,
        modifier.fillMaxWidth(),
        softWrap = block.whiteSpace != WhiteSpace.NOWRAP && block.whiteSpace != WhiteSpace.PRE,
        inlineContent = inline,
        behind = if (!drawn) {
            null
        } else {
            { lines ->
                val shown = lines.result
                drawMarks(shown, marks, lines::exactBaseline)
                drawCodeBoxes(shown, codes, tokens, lines::exactBaseline)
                drawDottedUnderlines(shown, files, color, fonts, lineBaseline = lines::baseline)
                for ((item, ranges) in dotted) {
                    val (size, font, tint) = item.split(';')
                    drawDottedUnderlines(shown, ranges, HtmlColor.valueOf(tint).rgba(base, tokens).color, fonts, size.toFloat(), HtmlFont.valueOf(font).family(), lines::baseline)
                }
            }
        },
    )
}

/** A checkbox's side, CSS px: Chrome's on Android. */
private const val CHECKBOX = 16

/** A summary's disclosure triangle stands in a square this many ems on a side. */
private const val DISCLOSURE = 0.66f

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
 * it (`ListMarkerPainter`, measured at 100px) and lays it out (`ListMarker`): a triangle in a
 * square 0.66 em on a side standing on the baseline, then a margin of 0.4 em, each a LayoutUnit
 * (cut down to 1/64 px). Chrome takes the margin from the font's size before the page is scaled
 * to the screen, so the marker is narrower on a denser one: 24.06 px at 14px and 2x, 29.84 px at
 * 2.625. Shorter than the ascent, it leaves the line be. Android rounds a placeholder up to whole
 * pixels, so the marker is given the nearest whole width (24.06 px would push the text a pixel
 * right) and the square is drawn at its own size.
 */
private fun disclosures(fontPx: Float, color: Color, density: Density): Map<String, InlineTextContent> {
    val side = DISCLOSURE * fontPx
    val advance = layoutUnit(side) + layoutUnit(0.4f * fontPx / density.density)
    val width = with(density) { (advance.roundToInt() - 0.01f).toSp() }
    val placeholder = Placeholder(width, with(density) { side.toSp() }, PlaceholderVerticalAlign.AboveBaseline)
    return mapOf(
        OPEN_TRIANGLE to InlineTextContent(placeholder) { HtmlDisclosure(true, side, color) },
        CLOSED_TRIANGLE to InlineTextContent(placeholder) { HtmlDisclosure(false, side, color) },
    )
}

/** Blink's `LayoutUnit(float)`: [px] cut down to a 64th of a pixel. */
private fun layoutUnit(px: Float) = floor(px * 64f) / 64f

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

/** Chrome's own disabled checkbox, as a task list item's is. */
@Composable
private fun HtmlCheckbox(checked: Boolean) {
    ChromeCheckbox(checked, Modifier.fillMaxSize(), enabled = false)
}

/**
 * `mark`'s background: Chrome's `Mark` over the text's own box on each line, from the font's
 * ascent above the baseline to its descent below, as Chrome rounds them; [lineBaseline] says where
 * each line's is, exactly, and each edge is snapped to its nearest pixel from there.
 */
private fun DrawScope.drawMarks(layout: TextLayoutResult, marks: List<AnnotatedString.Range<String>>, lineBaseline: (Int) -> Float) {
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
            val top = (lineBaseline(line) - ascent).roundToInt().toFloat()
            val bottom = (lineBaseline(line) + descent).roundToInt().toFloat()
            drawRect(MARK_BACKGROUND, Offset(left, top), Size(right - left, bottom - top))
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
 * web's auto table layout sizes them, a cell spanning the columns its `colspan` says. Its rows
 * stack exactly, as tall as their tallest cell — 52.28 px at 2x, where whole pixels make the
 * table under a pixel short — and the column around counts the table at that.
 */
@Composable
private fun HtmlTable(box: TableBox, drawing: HtmlDrawing, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    val grid = remember(box) { TableGrid(box) }
    val exact = rememberCssExact()
    val edges = remember(grid) { TableEdges() }
    val policy = remember(grid, exact, edges) { HtmlTableLayout(grid, exact, edges) }
    val scroll = rememberScrollState()
    val border = tokens.border.color
    val header = tokens.bgSolid.color
    Layout(
        content = {
            grid.cells.forEachIndexed { index, cell -> HtmlCell(cell, grid.closesEnd[index], grid.closesBottom[index], drawing) }
        },
        // The scroller passes the line's width on as its minimum, its maximum being endless. Its thumb
        // runs along the bottom of the margins it keeps inside.
        modifier
            .cssPhased(exact)
            .chromeScrollbar(scroll, vertical = false, border)
            .padding(vertical = 8.dp)
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .drawBehind { edges.laid?.let { (xs, ys) -> drawTable(grid, xs, ys, border, header) } },
        policy,
    )
}

/**
 * A `th` or `td`: `padding: 0.25rem 0.5rem` inside the room its collapsed borders take — its top
 * and start edges, and its end or bottom where no cell follows — which the table draws, with a
 * header's background, across the whole of the cell's row.
 */
@Composable
private fun HtmlCell(cell: TableCell, closesEnd: Boolean, closesBottom: Boolean, drawing: HtmlDrawing) {
    // The padding above and below counted with the lines, exactly: 10.5 px each at 2.625x,
    // which padding would round to 11 and make every row a pixel taller than Chrome's.
    val exact = rememberCssExact()
    CssColumn(
        Modifier
            .cssExact(exact)
            .cssBorderInset(start = 1.dp, top = 1.dp, end = if (closesEnd) 1.dp else 0.dp, bottom = if (closesBottom) 1.dp else 0.dp)
            .padding(horizontal = 8.dp),
        top = 4.dp,
        bottom = 4.dp,
        exact = exact,
    ) { HtmlFlow(cell.box, drawing) }
}

/** Where a table's column edges and row edges were last laid out, whole pixels, for [drawTable]. */
private class TableEdges {
    var laid by mutableStateOf<Pair<IntArray, IntArray>?>(null, referentialEqualityPolicy())
}

/** Each header's background over its whole cell, then every cell's borders over the backgrounds, as Chrome paints collapsed borders. */
private fun DrawScope.drawTable(grid: TableGrid, xs: IntArray, ys: IntArray, border: Color, header: Color) {
    val line = cssBorderPx(1.dp).toFloat()
    for (pass in 0..1) {
        grid.cells.forEachIndexed { index, cell ->
            val left = xs[grid.column[index]].toFloat()
            val width = xs[grid.column[index] + grid.span[index]] - left
            val top = ys[grid.row[index]].toFloat()
            val height = ys[grid.row[index] + 1] - top
            if (pass == 0) {
                if (cell.header) drawRect(header, Offset(left, top), Size(width, height))
                return@forEachIndexed
            }
            drawRect(border, Offset(left, top), Size(width, line))
            drawRect(border, Offset(left, top), Size(line, height))
            if (grid.closesEnd[index]) drawRect(border, Offset(left + width - line, top), Size(line, height))
            if (grid.closesBottom[index]) drawRect(border, Offset(left, top + height - line), Size(width, line))
        }
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
 * [TableGrid]'s cells between the edges [TableWidths] puts its columns at to fill the width the
 * scroller passes on as its minimum, every cell as tall as its row. Its intrinsic sizes are every
 * column at its content's narrowest, or widest, as CSS sizes a table inside a box that sizes itself
 * from it. A cell counts one border, its start one; the table's end border is the table's own.
 */
private class HtmlTableLayout(private val grid: TableGrid, private val exact: CssExact, private val edges: TableEdges) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val xs = edges(measurables, constraints.minWidth.takeIf { it > 0 })
        val phase = exact.cssPhase()
        // Row by row, each cell measured where its row starts exactly and as tall as it is.
        val tops = FloatArray(grid.rows + 1)
        val placeables = ArrayList<Placeable>(measurables.size)
        val heights = FloatArray(measurables.size)
        var index = 0
        for (row in 0 until grid.rows) {
            var tallest = 0f
            while (index < measurables.size && grid.row[index] == row) {
                val width = spanWidth(index, xs)
                val (placeable, height) = measurables[index].cssMeasure(Constraints(minWidth = width, maxWidth = width), phase + tops[row])
                placeables += placeable
                heights[index] = height
                tallest = max(tallest, height)
                index++
            }
            tops[row + 1] = tops[row] + tallest
        }
        val ys = IntArray(grid.rows + 1) { (phase + tops[it]).roundToInt() }
        edges.laid = xs to ys
        exact.cssReport(tops[grid.rows], ys[grid.rows])
        return layout(xs[grid.columns], ys[grid.rows]) {
            placeables.forEachIndexed { cell, placeable ->
                val row = grid.row[cell]
                val room = tops[row + 1] - tops[row] - heights[cell]
                val down = when (grid.cells[cell].valign) {
                    CellAlign.TOP -> 0f
                    CellAlign.MIDDLE -> room / 2
                    CellAlign.BOTTOM -> room
                }
                placeable.place(xs[grid.column[cell]], (phase + tops[row] + down).roundToInt())
            }
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int {
        val border = cssBorderPx(1.dp)
        return columnWidths(measurables, border) { it.minIntrinsicWidth(Constraints.Infinity) }.sum() + border
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int {
        val border = cssBorderPx(1.dp)
        return columnWidths(measurables, border) { it.maxIntrinsicWidth(Constraints.Infinity) }.sum() + border
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) =
        heights(measurables, edges(measurables, width.takeIf { it != Constraints.Infinity })).sum()

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) =
        minIntrinsicHeight(measurables, width)

    private fun spanWidth(index: Int, xs: IntArray) = xs[grid.column[index] + grid.span[index]] - xs[grid.column[index]]

    /** Where each column's start border begins, then the table's end, filling [fill]; with nothing to fill, each column at its widest. */
    private fun IntrinsicMeasureScope.edges(cells: List<IntrinsicMeasurable>, fill: Int?): IntArray {
        val border = cssBorderPx(1.dp)
        val min = columnWidths(cells, border) { it.minIntrinsicWidth(Constraints.Infinity) }
        val max = columnWidths(cells, border) { it.maxIntrinsicWidth(Constraints.Infinity) }
        return TableWidths.edges(min, max, fill ?: (max.sum() + border), border)
    }

    /**
     * The largest [size] of each column's cells, less the end [border] a cell closing its row
     * draws: the cells in one column first, then what a cell spanning several needs past them,
     * shared out evenly.
     */
    private inline fun columnWidths(cells: List<IntrinsicMeasurable>, border: Int, size: (IntrinsicMeasurable) -> Int): IntArray {
        val widths = IntArray(grid.columns)
        val needs = IntArray(cells.size) { size(cells[it]) - if (grid.closesEnd[it]) border else 0 }
        needs.forEachIndexed { index, need ->
            if (grid.span[index] == 1) widths[grid.column[index]] = max(widths[grid.column[index]], need)
        }
        needs.forEachIndexed { index, need ->
            val span = grid.span[index]
            if (span == 1) return@forEachIndexed
            val from = grid.column[index]
            val short = need - (from until from + span).sumOf { widths[it] }
            if (short > 0) for (k in 0 until span) widths[from + k] += short / span + if (k < short % span) 1 else 0
        }
        return widths
    }

    private fun heights(cells: List<IntrinsicMeasurable>, xs: IntArray): IntArray {
        val heights = IntArray(grid.rows)
        cells.forEachIndexed { index, cell ->
            val row = grid.row[index]
            heights[row] = max(heights[row], cell.minIntrinsicHeight(spanWidth(index, xs)))
        }
        return heights
    }
}
