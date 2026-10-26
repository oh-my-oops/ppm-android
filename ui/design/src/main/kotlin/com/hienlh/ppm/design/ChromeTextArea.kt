package com.hienlh.ppm.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ParentDataModifierNode
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A textarea as Chrome paints it, over a BasicTextField that keeps the editing. Compose sets a
 * field's lines whole pixels apart (`LineHeightStyleSpan` ceils): 14 px on 22.75 at 2x is 46 px a
 * line where Chrome's are 45.5, the third line a pixel low and the fourth two. Chrome also
 * scrolls a textarea's padding with its text, so a line scrolled up shows in the padding over it
 * until it leaves the box, and scrolls only as far as the caret needs to be in view: typing on the
 * fourth line of the 90 px composer scrolls it 13 px, where Compose brings the whole line box into
 * its view inside the padding, 36.
 *
 * So the field is laid out as tall as its text, the text transparent, in a box that scrolls as
 * Chrome scrolls the textarea ([scroll]), and its text, selection, placeholder and caret are drawn
 * over it where Chrome draws them: each line on the pixel its exact baseline rounds to from where
 * the box exactly starts. A field hands its BasicTextField its style with no colour, the
 * [selectionColors] (through `LocalTextSelectionColors`), [brush], [onTextLayout], [interactions]
 * and [fieldScroll], sits on [Modifier.chromeTextArea], and its decorator holds the inner field in
 * [ChromeTextAreaBox].
 */
@Stable
class ChromeTextArea internal constructor(internal val state: TextFieldState, internal val caret: ChromeCaret) {
    val interactions: MutableInteractionSource get() = caret.interactions
    val brush: Brush get() = caret.brush
    val onTextLayout: Density.(getResult: () -> TextLayoutResult?) -> Unit get() = caret.onTextLayout

    /** The field's own scroll, which stays at its start: the field is as tall as its text. */
    val fieldScroll: ScrollState get() = caret.scroll

    /** How far down the textarea is scrolled, device px, as Chrome scrolls it. */
    val scroll = ScrollState(0)

    internal var style by mutableStateOf(TextStyle.Default)
    internal var padding by mutableStateOf(0.dp)

    /** Where in its first pixel the box exactly starts, as the row or column around it places it. */
    internal var phase by mutableFloatStateOf(0f)
    internal val exact = CssExact()

    internal val result: TextLayoutResult? get() = caret.layout?.invoke()

    /** What the field draws its selection in: nothing, as this draws it; the handles as [colors] has them. */
    fun selectionColors(colors: TextSelectionColors) = TextSelectionColors(colors.handleColor, Color.Transparent)
}

/** A textarea's lines as Chrome stacks them, device px down its content: [padding] over the first, [pitch] apart. */
internal class ChromeAreaLines(
    val padding: Float,
    val pitch: Float,
    /** The baseline under a line's top. */
    val above: Float,
    /** The caret under a line's top, and how tall it is. */
    val caretTop: Float,
    val caretHeight: Float,
) {
    fun top(line: Int) = padding + line * pitch

    /** How tall [lines] lines are with the padding above and below them. */
    fun height(lines: Int) = 2 * padding + lines * pitch
}

internal fun Density.chromeAreaLines(style: TextStyle, padding: Dp): ChromeAreaLines {
    val mono = style.fontFamily == PpmFonts.mono
    val ascent = if (mono) PpmFonts.MONO_ASCENT else PpmFonts.SANS_ASCENT
    val descent = if (mono) PpmFonts.MONO_DESCENT else PpmFonts.SANS_DESCENT
    val px = style.fontSize.toPx()
    val line = style.lineHeight.toPx()
    val (above, below) = chromeTextExtent(px, line, ascent, descent)
    val (up, down) = PpmFonts.chromeAscentDescent(px, ascent, descent)
    return ChromeAreaLines(padding.toPx(), above + below, above, floor((line - up - down) / 2), up + down)
}

/**
 * Where Chrome scrolls a textarea at [scroll] whose caret reaches from [top] to [bottom] px down
 * its content, in a view [view] px tall that scrolls up to [max]: no further than that takes to
 * bring the caret into view (`ScrollAlignment::ToEdgeIfNeeded`), on the pixel that rounds to.
 */
internal fun chromeRevealScroll(top: Float, bottom: Float, scroll: Int, view: Int, max: Int): Int {
    val target = when {
        top < scroll -> top
        bottom > scroll + view -> bottom - view
        else -> return scroll
    }
    return floor(target + 0.5f).toInt().coerceIn(0, max(0, max))
}

/**
 * The textarea of the field editing [state] in [style] (its colour the text's), [padding] above and
 * below its lines; [cssStart] is the CSS length from a whole pixel to where its text starts, for
 * the caret ([rememberChromeCaret]). While the field has focus, a caret moved or text typed scrolls
 * it as Chrome scrolls it; the text it opens with is shown from its top, as the web shows a draft.
 */
@Composable
fun rememberChromeTextArea(state: TextFieldState, style: TextStyle, padding: Dp, cssStart: Dp = 0.dp): ChromeTextArea {
    val caret = rememberChromeCaret(state, style, multiline = true, cssStart = cssStart)
    val area = remember(caret) { ChromeTextArea(state, caret) }
    val density = LocalDensity.current
    val focused by caret.interactions.collectIsFocusedAsState()
    val focusedNow by rememberUpdatedState(focused)
    SideEffect {
        area.style = style
        area.padding = padding
    }
    LaunchedEffect(area, density) {
        var seen: Pair<TextRange, String>? = null
        snapshotFlow {
            val result = area.result
            val text = state.text.toString()
            // Once the text is laid out: the caret's line is in the layout of the text it is in.
            if (result == null || result.layoutInput.text.text != text) null else Triple(state.selection, text, result)
        }.collect { laid ->
            if (laid == null) return@collect
            val (selection, text, result) = laid
            val before = seen
            seen = selection to text
            if (before == null || before == seen || !focusedNow) return@collect
            val lines = with(density) { chromeAreaLines(area.style, area.padding) }
            val top = lines.top(result.getLineForOffset(selection.end.coerceIn(0, text.length))) + lines.caretTop
            val scroll = area.scroll
            scroll.scrollTo(chromeRevealScroll(top, top + lines.caretHeight, scroll.value, scroll.viewportSize, scroll.maxValue))
        }
    }
    return area
}

/**
 * The field's box as a [CssRow] or [CssColumn] counts it, outermost on the BasicTextField: the
 * padding and its lines exactly — 16 + 45.5 px a line at 2x — held to [max] (`max-h-*`). It reads
 * where in its first pixel the box starts, which its lines round from.
 */
fun Modifier.chromeTextArea(area: ChromeTextArea, max: Dp): Modifier = this then ChromeTextAreaElement(area, max)

private data class ChromeTextAreaElement(val area: ChromeTextArea, val max: Dp) : ModifierNodeElement<ChromeTextAreaNode>() {
    override fun create() = ChromeTextAreaNode(area, max)

    override fun update(node: ChromeTextAreaNode) {
        node.release()
        node.area = area
        node.max = max
        if (node.isAttached) node.claim()
        node.invalidateMeasurement()
    }
}

private class ChromeTextAreaNode(var area: ChromeTextArea, var max: Dp) : Modifier.Node(), LayoutModifierNode, ParentDataModifierNode {
    override fun Density.modifyParentData(parentData: Any?): Any = area.exact

    override fun onAttach() = claim()

    override fun onDetach() = release()

    fun claim() {
        area.exact.reader = this
    }

    fun release() {
        if (area.exact.reader === this) area.exact.reader = null
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        area.phase = area.exact.cssPhase()
        val placeable = measurable.measure(constraints)
        val lines = area.result?.lineCount ?: 1
        area.exact.cssReport(min(max.toPx(), chromeAreaLines(area.style, area.padding).height(lines)), placeable.height)
        return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}

/** A box that scrolls only as Chrome scrolls a textarea: the field asking for its cursor to be brought into view moves nothing here. */
@OptIn(ExperimentalFoundationApi::class)
private val StayPut = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/**
 * The inside of a [ChromeTextArea]'s field, its decorator's [field]: the box that scrolls, with
 * [placeholder] (one line, in [placeholderColor]) while there is no text, the selection in
 * [selectionColor] and Chrome's overlay scrollbar in [scrollbarColor] while the text overflows.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChromeTextAreaBox(
    area: ChromeTextArea,
    placeholder: String?,
    placeholderColor: Color,
    selectionColor: Color,
    scrollbarColor: Color,
    modifier: Modifier = Modifier,
    field: @Composable () -> Unit,
) {
    val measurer = rememberTextMeasurer()
    CompositionLocalProvider(LocalBringIntoViewSpec provides StayPut) {
        Box(
            modifier
                .drawWithContent {
                    drawContent()
                    val lines = chromeAreaLines(area.style, area.padding)
                    val phase = area.phase
                    val scrolled = area.scroll.value
                    with(area.caret) { drawCaret { line -> phase + lines.top(line) - scrolled } }
                }
                // The caret blinks on a layer of its own, which draws the text no more often.
                .graphicsLayer()
                .chromeScrollbar(area.scroll, vertical = true, color = scrollbarColor)
                .drawWithCache {
                    val hint = placeholder?.let {
                        val width = size.width.toInt().coerceAtLeast(0)
                        measurer.measure(it, area.style.copy(color = placeholderColor), TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = width))
                    }
                    // A space's advance, between two letters: Android gives one at a line's end none.
                    val space = measurer.measure("x x", area.style).let { it.getHorizontalPosition(2, true) - it.getHorizontalPosition(1, true) }
                    onDrawWithContent {
                        drawContent()
                        drawArea(area, hint, space, selectionColor)
                    }
                }
                .verticalScroll(area.scroll),
        ) {
            Layout(content = { Box(Modifier.padding(vertical = area.padding)) { field() } }) { measurables, constraints ->
                val placeable = measurables.single().measure(constraints)
                // As tall as Chrome makes the content, whatever Compose's whole-pixel lines add up to.
                val lines = area.result?.lineCount ?: 1
                val height = chromePixel(area.phase, chromeAreaLines(area.style, area.padding).height(lines))
                layout(placeable.width, max(0, height)) { placeable.place(0, 0) }
            }
        }
    }
}

/**
 * Draws [area]'s selection and text, or [hint] while it has no text, each line where Chrome paints
 * it. Chrome's selection covers the characters selected on each line, its edges rounded from where
 * the text exactly starts, and paints a line break [space] wide: a newline once selected, a wrap
 * once the selection runs on past it (LayoutNG's `SelectSoftLineBreak`), and draws the selected
 * text black. Android's selection path runs a line the selection continues past to the field's
 * edge and adds the next line's margin.
 */
private fun DrawScope.drawArea(area: ChromeTextArea, hint: TextLayoutResult?, space: Float, selectionColor: Color) {
    val result = area.result ?: return
    val lines = chromeAreaLines(area.style, area.padding)
    val phase = area.phase
    val scrolled = area.scroll.value

    // The pixel Chrome paints what is [exact] px down the content on, in this box as it is scrolled.
    fun pixel(exact: Float) = (chromePixel(phase, exact) - scrolled).toFloat()
    // Where Chrome's text starts, against this field's whole pixel: its glyphs sit there too.
    val start = area.caret.cssStart.let { it.toPx() - it.roundToPx() }

    clipRect {
        if (result.layoutInput.text.isEmpty()) {
            if (hint != null) drawText(hint, topLeft = Offset(start, pixel(lines.top(0) + lines.above) - hint.getLineBaseline(0)))
            return@clipRect
        }
        val count = result.lineCount
        // Only the lines in view: each draw of the paragraph draws every line it has.
        val first = max(0, floor((scrolled - phase - lines.padding) / lines.pitch).toInt())
        val last = min(count - 1, floor((scrolled + size.height - phase - lines.padding) / lines.pitch).toInt())
        if (first > last) return@clipRect
        val selection = area.state.selection
        val selected = arrayOfNulls<Rect>(last - first + 1)
        if (!selection.collapsed) {
            val text = result.layoutInput.text
            for (line in first..last) {
                val end = result.getLineEnd(line)
                val from = max(selection.min, result.getLineStart(line))
                val to = min(selection.max, end)
                if (from >= to) continue
                val broken = line < count - 1 && to == end && (text[end - 1] == '\n' || selection.max > end)
                val left = floor(start + result.chromeX(line, from, space) + 0.5f)
                val right = floor(start + result.chromeX(line, to, space) + (if (broken) space else 0f) + 0.5f)
                // Each line's from where its top rounds to, its height rounded on its own (46 px at
                // 2x, measured at four phases): a line on a half pixel overlaps the next by a row,
                // which the translucent selection paints twice, as Chrome's does.
                val top = pixel(lines.top(line))
                val rect = Rect(min(left, right), top, max(left, right), top + floor(lines.pitch + 0.5f))
                drawRect(selectionColor, rect.topLeft, rect.size)
                selected[line - first] = rect
            }
        }
        val color = area.style.color
        for (line in first..last) {
            // Each line in the band down to where the next starts, moved onto its own baseline.
            val top = if (line == 0) -BIG else pixel(lines.top(line))
            val bottom = if (line == count - 1) BIG else pixel(lines.top(line + 1))
            val shift = pixel(lines.top(line) + lines.above) - result.getLineBaseline(line)
            clipRect(-BIG, top, BIG, bottom) {
                val rect = selected[line - first]
                if (rect == null) {
                    translate(start, shift) { drawText(result, color = color) }
                } else {
                    // Chrome draws the selected text black, Android's colour for it where a page
                    // styles no `::selection`, and the rest of the line clipped around it: black
                    // drawn over the plain text would leave its antialiased edges showing.
                    clipRect(rect.left, rect.top, rect.right, rect.bottom, ClipOp.Difference) {
                        translate(start, shift) { drawText(result, color = color) }
                    }
                    clipRect(rect.left, rect.top, rect.right, rect.bottom) {
                        translate(start, shift) { drawText(result, color = Color.Black) }
                    }
                }
            }
        }
    }
}

/**
 * Where [offset] sits on [line] as Chrome lays a pre-wrap textarea out, px into the line: the end
 * of a line that ends in a newline is where the newline starts, and the spaces a line wraps at keep
 * their width ([space] each), where Android gives them none.
 */
private fun TextLayoutResult.chromeX(line: Int, offset: Int, space: Float): Float {
    val text = layoutInput.text
    val start = getLineStart(line)
    val end = getLineEnd(line)
    if (line == lineCount - 1) return getHorizontalPosition(offset, usePrimaryDirection = true)
    if (text[end - 1] == '\n') return getHorizontalPosition(min(offset, end - 1), usePrimaryDirection = true)
    var visible = end
    while (visible > start && text[visible - 1] == ' ') visible--
    if (offset < visible) return getHorizontalPosition(offset, usePrimaryDirection = true)
    val edge = if (visible == start) getHorizontalPosition(start, usePrimaryDirection = true) else getBoundingBox(visible - 1).right
    return edge + (offset - visible) * space
}

private const val BIG = 1e7f
