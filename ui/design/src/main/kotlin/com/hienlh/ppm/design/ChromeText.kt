package com.hienlh.ppm.design

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Text whose lines stand where Chrome stands them. Compose gives every line of a paragraph the
 * height and the place in it of its first line's font, rounded up to a whole pixel: lines 14 px
 * on 20 at 2.625x come out 53 px apart where Chrome's are 52.5, a line holding a Monaspace code
 * is no taller than the rest where Chrome's grows, and a checkbox on a later line overlaps the one
 * above. Chrome sizes each line box from the inline boxes on it (`NGLineHeightMetrics`), stacks
 * them in fractions of a pixel and only rounds each glyph's baseline, half up, as it paints it
 * (measured on Chrome 145 at 2.625x: baselines 39, 91.5, 144 drawn on 39, 92, 144).
 */

/** A face as Chrome reads it to size an inline box: its ascent and its descent, in ems. */
@Immutable
class ChromeFace(val ascent: Float, val descent: Float) {
    companion object {
        val Sans = ChromeFace(PpmFonts.SANS_ASCENT, PpmFonts.SANS_DESCENT)
        val Mono = ChromeFace(PpmFonts.MONO_ASCENT, PpmFonts.MONO_DESCENT)

        /** The system's `monospace`, which Chrome takes from the same file Android does. */
        val SystemMono: ChromeFace by lazy {
            val metrics = Paint().apply { typeface = Typeface.MONOSPACE; textSize = 1000f }.fontMetrics
            ChromeFace(-metrics.ascent / 1000f, metrics.descent / 1000f)
        }
    }
}

/** A block's own inline box, which every one of its lines holds: text in [face] at [size] CSS px on lines [lineHeight] CSS px. */
@Immutable
class ChromeStrut(val face: ChromeFace, val size: Float, val lineHeight: Float)

/**
 * Annotates the characters of an inline box that sizes the lines it is on other than its block
 * does: text in another face or size (an inline `code`, a `sub`), or an atomic box (a checkbox).
 */
const val CHROME_INLINE = "ppm.chrome.inline"

/** Annotates [start] to [end] as text in [face] at [size] CSS px on lines [lineHeight] CSS px (0 for `line-height: 0`). */
fun AnnotatedString.Builder.addChromeInline(face: ChromeFace, size: Float, lineHeight: Float, start: Int, end: Int) =
    addStringAnnotation(CHROME_INLINE, "${face.ascent};${face.descent};$size;$lineHeight", start, end)

/** Annotates [start] to [end] as an atomic inline box standing [above] CSS px over the baseline and nothing under it. */
fun AnnotatedString.Builder.addChromeAtomic(above: Float, start: Int, end: Int) =
    addStringAnnotation(CHROME_INLINE, "$ATOMIC;$above", start, end)

private const val ATOMIC = "atomic"

/** An inline box as [CHROME_INLINE] annotates it, CSS px; [face] null for an atomic box [size] px over the baseline. */
private class ChromeInline(val start: Int, val end: Int, val face: ChromeFace?, val size: Float, val lineHeight: Float)

private fun inlines(text: AnnotatedString): List<ChromeInline> = text.getStringAnnotations(CHROME_INLINE, 0, text.length).map {
    val parts = it.item.split(';')
    if (parts[0] == ATOMIC) {
        ChromeInline(it.start, it.end, null, parts[1].toFloat(), 0f)
    } else {
        ChromeInline(it.start, it.end, ChromeFace(parts[0].toFloat(), parts[1].toFloat()), parts[2].toFloat(), parts[3].toFloat())
    }
}

/** Blink's `LayoutUnit(float)`: [px] cut down to a 64th of a pixel. */
internal fun layoutUnit(px: Float) = floor(px * 64f) / 64f

/**
 * How far a box of text [px] px in a face of [ascent] and [descent] ems, on lines [line] px tall,
 * reaches above and below the baseline: the font's box as [PpmFonts.chromeAscentDescent] rounds
 * it, with the half of the leading above floored to a whole pixel and the rest below
 * (`CalculateLeadingSpace`). A line of `line-height: 0` gives a box the line never notices.
 */
internal fun chromeTextExtent(px: Float, line: Float, ascent: Float, descent: Float): Pair<Float, Float> {
    val (up, down) = PpmFonts.chromeAscentDescent(px, ascent, descent)
    val box = layoutUnit(line)
    val top = up + floor((box - up - down) / 2)
    return top to box - top
}

/**
 * A block's lines as Chrome stacks them, device px down from the block's top: where each line box
 * starts ([tops], then where the last one ends) and where its baseline is ([baselines]), exact.
 */
internal class ChromeLineBoxes(val tops: FloatArray, val baselines: FloatArray) {
    val height: Float get() = tops.last()
}

/** [lines] lines, each reaching [extent] above and below its baseline, stacked one under the other. */
internal fun chromeLineBoxes(lines: Int, extent: (Int) -> Pair<Float, Float>): ChromeLineBoxes {
    val tops = FloatArray(lines + 1)
    val baselines = FloatArray(lines)
    for (line in 0 until lines) {
        val (above, below) = extent(line)
        baselines[line] = tops[line] + above
        tops[line + 1] = tops[line] + above + below
    }
    return ChromeLineBoxes(tops, baselines)
}

/** The whole pixel Chrome paints a baseline (or a box's edge) on that is [exact] px down from a box starting [phase] into its first pixel. */
internal fun chromePixel(phase: Float, exact: Float): Int = floor(phase + exact + 0.5f).toInt()

/**
 * What a [ChromeText] laid out: [result], the paragraph as Compose breaks it into lines and sets
 * them across, and where each of its lines is drawn instead — [baseline] — once moved to where
 * Chrome puts it. Positions across are [result]'s.
 */
class ChromeTextLayout internal constructor(
    val result: TextLayoutResult,
    private val shifts: FloatArray,
    /** The pixel each line's band starts on, the first open above. */
    private val edges: IntArray,
    /** Each line laid out alone, when the lines move by different amounts; null when [result] moves as one. */
    private val lines: Array<TextLayoutResult>?,
    private val lineTops: FloatArray?,
    /** Where each line's baseline is before it is put on a pixel, px from the box's top pixel. */
    private val exactBaselines: FloatArray,
    /** How tall the box is, whole pixels, and exactly. */
    val height: Int,
    val exactHeight: Float,
) {
    val lineCount: Int get() = result.lineCount

    /** Where line [line]'s baseline is drawn, px from the box's top. */
    fun baseline(line: Int): Float = result.getLineBaseline(line) + shifts[line]

    /**
     * Where line [line]'s baseline is exactly, a fraction of a pixel off the one it is drawn on:
     * what Chrome snaps the edges of the boxes around the text from, each to its nearest pixel.
     */
    fun exactBaseline(line: Int): Float = exactBaselines[line]

    /** How far line [line] is drawn below where [result] has it. */
    fun shift(line: Int): Float = shifts[line]

    /** The line whose band, down to where the next starts, holds [y]. */
    fun lineAt(y: Float): Int {
        var line = 0
        while (line + 1 < edges.size && edges[line + 1] <= y) line++
        return line
    }

    /** The character under [position], or -1 when there is none. */
    fun characterAt(position: Offset): Int {
        val line = lineAt(position.y)
        val y = (result.getLineTop(line) + result.getLineBottom(line)) / 2
        val offset = result.getOffsetForPosition(Offset(position.x, y))
        for (candidate in intArrayOf(offset, offset - 1)) {
            if (candidate < result.getLineStart(line) || candidate >= result.getLineEnd(line)) continue
            val box = result.getBoundingBox(candidate)
            if (position.x >= box.left && position.x < box.right) return candidate
        }
        return -1
    }

    internal fun draw(scope: DrawScope, justify: Boolean) = with(scope) {
        when {
            lines != null && lineTops != null -> lines.forEachIndexed { index, line -> drawText(line, topLeft = Offset(0f, lineTops[index])) }
            // Justified lines are only right laid out together: each band of the paragraph moved on its own.
            justify && shifts.any { it != shifts[0] } -> for (line in shifts.indices) {
                val top = if (line == 0) -BIG else edges[line].toFloat()
                val bottom = if (line == shifts.lastIndex) BIG else edges[line + 1].toFloat()
                clipRect(-BIG, top, BIG, bottom) { translate(0f, shifts[line]) { drawText(result) } }
            }
            else -> drawText(result, topLeft = Offset(0f, shifts.firstOrNull() ?: 0f))
        }
    }

    private companion object {
        const val BIG = 1e7f
    }
}

/** The annotation `appendInlineContent` marks a placeholder with, naming its [InlineTextContent]. */
private const val INLINE_CONTENT = "androidx.compose.foundation.text.inlineContent"

/**
 * [text] in [style], lines broken as Compose breaks them, each line box as tall and its baseline
 * where Chrome has them for a block whose own box is [strut]: a line holding text in another face
 * or size, or an atomic box ([CHROME_INLINE]), grows as Chrome grows it, and lines stack in
 * fractions of a pixel from where [exact] says the box starts, each baseline on the pixel Chrome
 * paints it on. A [CssColumn] counts the box at its exact height. Links open as [BasicText]'s do,
 * a mouse over one takes its hovered style and the hand; [inlineContent] fills the placeholders
 * the text names; [behind] draws under the text, given where its lines went.
 */
@Composable
fun ChromeText(
    text: AnnotatedString,
    style: TextStyle,
    strut: ChromeStrut,
    modifier: Modifier = Modifier,
    softWrap: Boolean = true,
    inlineContent: Map<String, InlineTextContent> = emptyMap(),
    exact: CssExact = rememberCssExact(),
    behind: (DrawScope.(ChromeTextLayout) -> Unit)? = null,
) {
    val measurer = rememberChromeTextMeasurer()
    val uriHandler = LocalUriHandler.current
    val boxes = remember(text) { inlines(text) }
    val links = remember(text) { text.getLinkAnnotations(0, text.length) }
    val slots = remember(text) { text.getStringAnnotations(INLINE_CONTENT, 0, text.length) }
    val placeholders = remember(slots, inlineContent) {
        slots.mapNotNull { slot -> inlineContent[slot.item]?.let { AnnotatedString.Range(it.placeholder, slot.start, slot.end) } }
    }
    var hovered by remember { mutableStateOf<AnnotatedString.Range<LinkAnnotation>?>(null) }
    // What is laid out: the text's runs alone, so a line unchanged is the same input as before
    // whatever the links around it, and a hovered link's style over it.
    val laid = remember(text, hovered) {
        val link = hovered
        val hover = (link?.item?.styles?.hoveredStyle)?.let { listOf(AnnotatedString.Range(it, link.start, link.end)) }.orEmpty()
        AnnotatedString(text.text, text.spanStyles + hover, text.paragraphStyles)
    }
    val state = remember { ChromeTextState() }
    val policy = remember(measurer, laid, style, strut, softWrap, placeholders, boxes, exact) {
        ChromeTextPolicy(measurer, laid, style, strut, softWrap, placeholders, boxes, exact, state)
    }
    val justify = style.textAlign == TextAlign.Justify
    Layout(
        content = {
            for (slot in slots) {
                val content = inlineContent[slot.item] ?: continue
                Box { content.children(text.text.substring(slot.start, slot.end)) }
            }
        },
        modifier = modifier
            .cssPhased(exact)
            .semantics { this.text = text }
            .then(if (links.isEmpty()) Modifier else Modifier.chromeLinks(links, state, uriHandler) { hovered = it })
            .then(if (hovered != null) Modifier.pointerHoverIcon(WebCursor.Pointer) else Modifier)
            .drawBehind {
                val layout = state.layout ?: return@drawBehind
                behind?.invoke(this, layout)
                layout.draw(this, justify)
            },
        measurePolicy = policy,
    )
}

/** The layout a [ChromeText] last measured, which it draws and hit-tests. */
private class ChromeTextState {
    var layout by mutableStateOf<ChromeTextLayout?>(null, referentialEqualityPolicy())
}

/** Opens a link tapped as [BasicText] opens one, and tells [hover] which link a mouse is over. */
private fun Modifier.chromeLinks(
    links: List<AnnotatedString.Range<LinkAnnotation>>,
    state: ChromeTextState,
    uriHandler: UriHandler,
    hover: (AnnotatedString.Range<LinkAnnotation>?) -> Unit,
): Modifier {
    fun linkAt(position: Offset): AnnotatedString.Range<LinkAnnotation>? {
        val layout = state.layout ?: return null
        val character = layout.characterAt(position)
        if (character < 0) return null
        return links.lastOrNull { character >= it.start && character < it.end }
    }
    return pointerInput(links) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val link = linkAt(down.position) ?: return@awaitEachGesture
            val up = waitForUpOrCancellation() ?: return@awaitEachGesture
            if (linkAt(up.position) != link) return@awaitEachGesture
            up.consume()
            when (val item = link.item) {
                is LinkAnnotation.Url -> item.linkInteractionListener?.onClick(item) ?: runCatching { uriHandler.openUri(item.url) }
                is LinkAnnotation.Clickable -> item.linkInteractionListener?.onClick(item)
            }
        }
    }.pointerInput(links) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: continue
                if (change.type != PointerType.Mouse) continue
                hover(if (event.type == PointerEventType.Exit) null else linkAt(change.position))
            }
        }
    }
}

private class ChromeTextPolicy(
    private val measurer: TextMeasurer,
    private val text: AnnotatedString,
    private val style: TextStyle,
    private val strut: ChromeStrut,
    private val softWrap: Boolean,
    private val placeholders: List<AnnotatedString.Range<Placeholder>>,
    private val boxes: List<ChromeInline>,
    private val exact: CssExact,
    private val state: ChromeTextState,
) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val phase = exact.cssPhase()
        val laid = lay(Constraints(minWidth = constraints.minWidth, maxWidth = constraints.maxWidth), phase)
        exact.cssReport(laid.exactHeight, laid.height)
        state.layout = laid
        val result = laid.result
        val rects = result.placeholderRects
        val placeables = measurables.mapIndexed { index, child ->
            val rect = rects.getOrNull(index)
            child.measure(if (rect == null) Constraints.fixed(0, 0) else Constraints(maxWidth = floor(rect.width).toInt(), maxHeight = floor(rect.height).toInt()))
        }
        val lines = mapOf<AlignmentLine, Int>(FirstBaseline to laid.baseline(0).roundToInt(), LastBaseline to laid.baseline(result.lineCount - 1).roundToInt())
        return layout(result.size.width, laid.height.coerceIn(constraints.minHeight, constraints.maxHeight), lines) {
            placeables.forEachIndexed { index, placeable ->
                val rect = rects.getOrNull(index) ?: return@forEachIndexed
                val line = result.getLineForOffset(placeholders[index].start)
                placeable.place(rect.left.roundToInt(), (rect.top + laid.shift(line)).roundToInt())
            }
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
        ceilPx(paragraph(Constraints()).multiParagraph.intrinsics.minIntrinsicWidth)

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
        ceilPx(paragraph(Constraints()).multiParagraph.intrinsics.maxIntrinsicWidth)

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) =
        lay(Constraints(maxWidth = width), 0f).height

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) =
        lay(Constraints(maxWidth = width), 0f).height

    private fun ceilPx(px: Float) = kotlin.math.ceil(px).toInt()

    private fun paragraph(constraints: Constraints, line: AnnotatedString = text, lineStyle: TextStyle = style, at: List<AnnotatedString.Range<Placeholder>> = placeholders) =
        measurer.measure(line, lineStyle, TextOverflow.Visible, softWrap, constraints = constraints, placeholders = at)

    private fun Density.lay(constraints: Constraints, phase: Float): ChromeTextLayout {
        val result = paragraph(constraints)
        val count = result.lineCount
        val strutExtent = chromeTextExtent(strut.size.sp.toPx(), strut.lineHeight.sp.toPx(), strut.face.ascent, strut.face.descent)
        val boxes = chromeLineBoxes(count) { line ->
            val start = result.getLineStart(line)
            val end = result.getLineEnd(line)
            var (above, below) = strutExtent
            for (box in this@ChromeTextPolicy.boxes) {
                if (box.end <= start || box.start >= end) continue
                val (up, down) = if (box.face == null) {
                    layoutUnit(box.size.dp.toPx()) to 0f
                } else {
                    chromeTextExtent(box.size.sp.toPx(), box.lineHeight.sp.toPx(), box.face.ascent, box.face.descent)
                }
                above = max(above, up)
                below = max(below, down)
            }
            above to below
        }
        val shifts = FloatArray(count) { chromePixel(phase, boxes.baselines[it]) - result.getLineBaseline(it) }
        val edges = IntArray(count) { chromePixel(phase, boxes.tops[it]) }
        val exactBaselines = FloatArray(count) { phase + boxes.baselines[it] }
        val height = chromePixel(phase, boxes.height)
        val moved = shifts.any { it != shifts[0] }
        if (!moved || style.textAlign == TextAlign.Justify) {
            return ChromeTextLayout(result, shifts, edges, null, null, exactBaselines, height, boxes.height)
        }
        // Lines that move by different amounts are each laid out and drawn alone, on the same
        // width so that they sit across as they do in the paragraph.
        val width = Constraints.fixedWidth(result.size.width)
        val lines = Array(count) { line ->
            val start = result.getLineStart(line)
            var end = result.getLineEnd(line)
            if (end > start && text[end - 1] == '\n') end--
            val direction = if (result.getParagraphDirection(start) == ResolvedTextDirection.Rtl) TextDirection.Rtl else TextDirection.Ltr
            val at = placeholders.filter { it.start >= start && it.end <= end }.map { AnnotatedString.Range(it.item, it.start - start, it.end - start) }
            paragraph(width, text.subSequence(start, end), style.copy(textDirection = direction), at)
        }
        val tops = FloatArray(count) { line -> (chromePixel(phase, boxes.baselines[line]) - lines[line].firstBaseline).roundToInt().toFloat() }
        return ChromeTextLayout(result, shifts, edges, lines, tops, exactBaselines, height, boxes.height)
    }
}

/**
 * One measurer for every [ChromeText] on a screen, so that a line laid out once — a reply's
 * earlier lines while it streams, a message scrolled back to — is not laid out again.
 */
@Composable
private fun rememberChromeTextMeasurer(): TextMeasurer {
    val resolver = LocalFontFamilyResolver.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    return remember(resolver, density, direction) { ChromeMeasurers.get(resolver, density, direction) }
}

private object ChromeMeasurers {
    private val measurers = object : LinkedHashMap<Triple<FontFamily.Resolver, Density, LayoutDirection>, TextMeasurer>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<FontFamily.Resolver, Density, LayoutDirection>, TextMeasurer>?) = size > 4
    }

    fun get(resolver: FontFamily.Resolver, density: Density, direction: LayoutDirection): TextMeasurer =
        measurers.getOrPut(Triple(resolver, density, direction)) { TextMeasurer(resolver, density, direction, cacheSize = 1024) }
}
