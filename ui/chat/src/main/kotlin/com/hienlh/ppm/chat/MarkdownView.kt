package com.hienlh.ppm.chat

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hienlh.ppm.design.PpmFonts
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ThemeTokens
import com.hienlh.ppm.design.cssBorderPx
import com.hienlh.ppm.design.ppmText
import kotlin.math.ceil
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

    companion object {
        /** `text-sm`: an assistant message. */
        val chat = MarkdownStyle(size = 14f, lineHeightRatio = 20f / 14f)
    }
}

/**
 * `MarkdownRenderer`: [text] as the web's react-markdown pipeline makes it, its raw HTML through
 * the web's allow-list when [rawHtml] and shown as text when not, drawn as `.markdown-content`
 * draws it; [streaming] while it still grows. The web holds it in a flex item or a scroller, which
 * stops the margins collapsing out of its first and last block, so they show. A `details` opened
 * or closed stays so while the text grows, as the DOM keeps it. While it grows, the blocks it had
 * are kept as they were and only the ones that changed are parsed, built and drawn again.
 */
@Composable
fun MarkdownView(
    text: String,
    modifier: Modifier = Modifier,
    style: MarkdownStyle = MarkdownStyle.chat,
    streaming: Boolean = false,
    rawHtml: Boolean = true,
) {
    val blocks = remember { MarkdownHtml.Blocks() }
    val kept = remember { KeptBoxes() }
    val nodes = remember(text, rawHtml) { blocks.render(text, rawHtml) }
    var opened by remember { mutableStateOf(emptyMap<Int, Boolean>()) }
    val root = remember(nodes, style, opened) { HtmlBoxes.build(nodes, style, opened, kept = kept) }
    val drawing = remember(style) { HtmlDrawing(style) { id, open -> opened = opened + (id to open) } }
    CompositionLocalProvider(LocalMarkdownStreaming provides streaming) {
        HtmlFlow(root, drawing, modifier)
    }
}

/**
 * The web's `MarkdownContent`: [text] without teammate messages and with runs of blank lines
 * closed up ([markdownContent]), and nothing at all when that leaves nothing.
 */
@Composable
fun MarkdownContent(
    text: String,
    modifier: Modifier = Modifier,
    style: MarkdownStyle = MarkdownStyle.chat,
    streaming: Boolean = false,
    rawHtml: Boolean = true,
) {
    val content = remember(text) { markdownContent(text) }
    if (content.isEmpty()) return
    MarkdownView(content, modifier, style, streaming, rawHtml)
}

/** `.markdown-content code` is 0.75rem whatever the text around it. */
internal const val INLINE_CODE_SIZE = 12f

/**
 * What an inline code's box stands on at either side, so that its padding and border take room on
 * the line as Chrome gives them: a private-use character, which a line breaker takes for a letter,
 * so that a line ends neither between it and the code nor anywhere a letter would not let it. It is
 * set in [PpmFonts.codeEdge], as wide as the side, and not an inline placeholder: StaticLayout
 * swaps a placeholder's character for U+FFFC, which a line may end on either side of.
 */
internal const val CODE_EDGE = ''

/** Annotates each inline code's box: the code and the [CODE_EDGE]s around it. */
internal const val CODE_BOX = "ppm.code-box"

/** What [RichText.annotated] puts in the text for the layout alone, which a copy leaves out. */
internal const val MARKDOWN_MARKS = "$CODE_EDGE$BREAK$JOIN"

/** Annotates the text of an inline code that names a file, which is underlined with dots. */
internal const val FILE_CODE = "ppm.file-code"

/** One side of a code's box: `padding-inline: 0.25rem` and the 1px border, 12.5 px at 2.625. */
internal fun Density.codeEdgePx(): Float = 4.dp.toPx() + cssBorderPx(1.dp)

/**
 * `:not(pre) > code`'s box: the page's background, a 1px border rounded 4px, `padding: 0.125rem
 * 0.25rem`. Chrome draws one piece of it on each line the code runs over (`box-decoration-break:
 * slice`), with no border or rounding where it goes on, and above and below it reaches past the
 * code's own ascent and descent by its padding and border, as any inline box does.
 */
internal fun DrawScope.drawCodeBoxes(layout: TextLayoutResult, boxes: List<AnnotatedString.Range<String>>, tokens: ThemeTokens) {
    val border = cssBorderPx(1.dp).toFloat()
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
            // A side is its edge's outer end, where Chrome's is. Spaces where the line breaks hang
            // inside the piece. Chrome snaps it to whole pixels.
            val left = if (line == first) layout.getBoundingBox(box.start).left else layout.getBoundingBox(start).left
            val right = if (line == last) {
                layout.getBoundingBox(box.end - 1).right
            } else {
                (if (visible > start) layout.getBoundingBox(visible - 1).right else left) + (end - visible) * PpmFonts.MONO_ADVANCE * size
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

/**
 * `text-decoration: underline dotted` under text of [size] CSS px in [family] (`MdCode`'s under a
 * file's code, `abbr[title]`'s), as Chrome draws it: a tenth of the size thick, a pixel at least,
 * in square dots that long with gaps as long from where the text starts on each line. It starts
 * under the baseline by half its thickness rounded up to a whole CSS pixel, then snapped to a
 * device pixel, and is centred on the whole device pixels below that. Where a glyph's ink crosses
 * it, it is left out (`text-decoration-skip-ink: auto`).
 */
internal fun DrawScope.drawDottedUnderlines(
    layout: TextLayoutResult,
    codes: List<AnnotatedString.Range<String>>,
    color: Color,
    fonts: FontFamily.Resolver,
    size: Float = INLINE_CODE_SIZE,
    family: FontFamily = PpmFonts.mono,
) {
    if (codes.isEmpty()) return
    val css = maxOf(1f, size / 10f)
    val thickness = css.sp.toPx()
    val dot = thickness.roundToInt().coerceAtLeast(1).toFloat()
    val gap = ceil(css / 2f).sp.toPx()
    val glyphs = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = fonts.resolve(family).value as Typeface
        textSize = size.sp.toPx()
    }
    for (code in codes) {
        val first = layout.getLineForOffset(code.start)
        val last = layout.getLineForOffset(code.end - 1)
        for (line in first..last) {
            val start = maxOf(code.start, layout.getLineStart(line))
            val end = minOf(code.end, layout.getLineEnd(line, visibleEnd = true))
            if (end <= start) continue
            val right = layout.getBoundingBox(end - 1).right
            val baseline = layout.getLineBaseline(line)
            val top = (baseline + gap).roundToInt() + (dot - thickness) / 2
            // Chrome measures the ink against the line a stroke [dot] wide would cover, which sits
            // half a pixel up when its width truncates to an odd number, less half a pixel at the
            // top and at the bottom.
            val middle = top + thickness / 2 - if (thickness.toInt() % 2 == 1) 0.5f else 0f
            val skips = inkSkips(layout, start, end, baseline, glyphs, middle - dot / 2 + 0.5f, middle + dot / 2 - 0.5f, thickness)
            clipPath(skips, ClipOp.Difference) {
                var x = floor(layout.getBoundingBox(start).left)
                while (x < right) {
                    drawRect(color, Offset(x, top), Size(minOf(dot, right - x), thickness))
                    x += 2 * dot
                }
            }
        }
    }
}

/**
 * What Chrome leaves out of an underline under the characters from [start] to [end], set in
 * [glyphs] on [baseline] (`ClipDecorationsStripe`): over each glyph's ink between [upper] and
 * [lower], and [dilation] further either side and a pixel above and below, in whole pixels.
 */
private fun inkSkips(layout: TextLayoutResult, start: Int, end: Int, baseline: Float, glyphs: Paint, upper: Float, lower: Float, dilation: Float): Path {
    val text = layout.layoutInput.text.text
    val band = android.graphics.Path()
    band.addRect(layout.getBoundingBox(start).left - glyphs.textSize, upper, layout.getBoundingBox(end - 1).right + glyphs.textSize, lower, android.graphics.Path.Direction.CW)
    val glyph = android.graphics.Path()
    val ink = RectF()
    val skips = Path()
    for (index in start until end) {
        glyphs.getTextPath(text, index, index + 1, layout.getBoundingBox(index).left, baseline, glyph)
        if (!glyph.op(band, android.graphics.Path.Op.INTERSECT) || glyph.isEmpty) continue
        glyph.computeBounds(ink, true)
        val left = (ink.left - dilation).roundToInt().toFloat()
        val right = (ink.right + dilation).roundToInt().toFloat()
        skips.addRect(Rect(left, (upper - 1).roundToInt().toFloat(), right, (lower + 1).roundToInt().toFloat()))
    }
    return skips
}

/**
 * An ordered item's marker, ending on the item's text to the fraction of a pixel as Chrome's does:
 * Compose gives a text a whole number of pixels, rounding up, so the end alone would leave it short.
 */
@Composable
internal fun OrdinalMarker(text: String, style: MarkdownStyle, color: Color, modifier: Modifier) {
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
fun DiscMarker(style: MarkdownStyle, color: Color, modifier: Modifier) {
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
