package com.hienlh.ppm.design

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.magnifier
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/*
 * Selecting [ChromeText] where it is drawn. Compose's own selection cannot be reused: its
 * SelectionContainer selects only the BasicText inside it, through a registrar and a `Selectable`
 * that are internal to Compose, and a MultiParagraph puts every line where Compose has it, not
 * where ChromeText moved it. So this is a small selection of its own, Android's in look and feel:
 *
 * - a [ChromeSelectionArea] (one per transcript) holds at most one selection, draws its two
 *   teardrop handles above everything in it, drags them (with the magnifier, and scrolling the
 *   list near its edges), shows the system's text toolbar (Copy, Select all), and ends the
 *   selection on a press anywhere else, a scroll, or Back;
 * - a host ([rememberChromeSelectionHost], [chromeSelectionHost], [ChromeSelectionBlocks]) is a
 *   region that selects as one, like a page in a browser: one answer's markdown, whose paragraphs,
 *   list items, headings, table cells and code blocks are each a ChromeText, in document order
 *   (top, then left). A long press on a word selects it, a mouse selects by dragging, a double
 *   click selects a word and a triple click a block;
 * - each ChromeText in a host registers itself and draws its own part of the selection under its
 *   glyphs, on its lines as they are drawn ([ChromeTextLayout.drawSelection]).
 *
 * A selection is two places, each a block and an offset in it; while a handle moves, only the
 * blocks whose part changed draw again, and nothing recomposes.
 */

/** The transcript's selection, for the hosts in it; null where nothing selects ChromeText. */
val LocalChromeSelectionArea = staticCompositionLocalOf<ChromeSelectionAreaState?> { null }

/**
 * Whether a dragged handle raises the platform's magnifier. Off only where no window's pixels can
 * be copied: a JVM test, whose stand-in for the platform Magnifier fails to show or dismiss one.
 */
val LocalChromeSelectionMagnifier = staticCompositionLocalOf { true }

/** The host the ChromeText under it is a block of. */
internal val LocalChromeSelectionHost = staticCompositionLocalOf<ChromeSelectionHost?> { null }

/** Test tags of the two handles. */
const val CHROME_SELECTION_START_HANDLE = "chrome-selection-start-handle"
const val CHROME_SELECTION_END_HANDLE = "chrome-selection-end-handle"

/** Compose's own handle: 25 dp a side. */
private val HANDLE = 25.dp

/** How far from a handle's middle a press still takes it: a 48 dp target. */
private val HANDLE_REACH = 24.dp

/** How close to the area's top or bottom a dragged handle scrolls it, and how fast at most, per frame. */
private val EDGE_ZONE = 40.dp
private val EDGE_SPEED = 16.dp

private const val NONE = 0
private const val PRESS = 1
private const val START = 2
private const val END = 3

/** A ChromeText in a host: its text, where it is, its layout, and the part of it selected. */
internal class ChromeSelectable(val host: ChromeSelectionHost, private val state: ChromeTextState) {
    var text: AnnotatedString = AnnotatedString("")
    var slots: List<AnnotatedString.Range<String>> = emptyList()
    var highlight: Color = Color.Unspecified
    var coordinates: LayoutCoordinates? = null

    /** The part selected, [ChromeSelectionModel.pack]ed, read as the ChromeText draws. */
    val selected = mutableLongStateOf(ChromeSelectionModel.NONE)

    val layout: ChromeTextLayout? get() = state.layout

    val length: Int get() = text.length

    /** Inline boxes' placeholders, which a copy leaves out. */
    fun skipped(): List<TextRange> = slots.map { TextRange(it.start, it.end) }

    fun placed(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        host.area.moved(host)
    }

    fun attached(): LayoutCoordinates? = coordinates?.takeIf { it.isAttached && state.layout != null }
}

/** Where a point went: block [block], [index] in document order, caret place [offset]. */
internal class ChromeHit(val index: Int, val block: ChromeSelectable, val offset: Int)

/** A word (or a whole block) a press selects: [range] of [block]. */
internal class ChromeWord(val block: ChromeSelectable, val range: TextRange)

/** A region whose ChromeText selects as one: one answer's markdown. */
@Stable
class ChromeSelectionHost internal constructor(internal val area: ChromeSelectionAreaState) {
    internal var clipboard: Clipboard? = null
    internal var haptics: HapticFeedback? = null
    internal var coordinates: LayoutCoordinates? = null
    private val blocks = ArrayList<ChromeSelectable>()
    private var sorted = true
    private var boxes = FloatArray(0)
    private var clicks = 0
    private var lastClickAt = 0L
    private var lastClickAtPosition = Offset.Zero

    internal fun add(block: ChromeSelectable) {
        blocks.add(block)
        sorted = false
    }

    internal fun remove(block: ChromeSelectable) {
        blocks.remove(block)
        block.selected.longValue = ChromeSelectionModel.NONE
        area.blockRemoved(this, block)
    }

    /** The blocks in document order: top, then left, where they are in this host. */
    internal fun ordered(): List<ChromeSelectable> {
        if (!sorted) sort()
        return blocks
    }

    private fun sort() {
        val host = coordinates?.takeIf { it.isAttached } ?: return
        var all = true
        val at = HashMap<ChromeSelectable, Offset>(blocks.size)
        for (block in blocks) {
            val c = block.coordinates?.takeIf { it.isAttached }
            if (c == null) all = false
            at[block] = if (c == null) Offset(Float.MAX_VALUE, Float.MAX_VALUE) else host.localPositionOf(c, Offset.Zero)
        }
        blocks.sortWith(compareBy<ChromeSelectable>({ at.getValue(it).y }, { at.getValue(it).x }))
        // A block not placed yet is sorted again once it is.
        sorted = all
    }

    /** The word under [position] (this host's px), on a glyph; null off the text. */
    internal fun wordAt(position: Offset): ChromeWord? {
        val block = blockUnder(position) ?: return null
        val local = block.coordinates!!.localPositionOf(coordinates!!, position)
        val character = block.layout!!.characterAt(local)
        if (character < 0) return null
        return ChromeWord(block, ChromeSelectionModel.wordAt(block.text.text, character))
    }

    /** The whole block under [position], as a triple click selects it. */
    internal fun blockAt(position: Offset): ChromeWord? {
        val block = blockUnder(position) ?: return null
        return ChromeWord(block, TextRange(0, block.length))
    }

    private fun blockUnder(position: Offset): ChromeSelectable? {
        val host = coordinates?.takeIf { it.isAttached } ?: return null
        for (block in ordered()) {
            val c = block.attached() ?: continue
            if (host.localBoundingBoxOf(c, clipBounds = false).contains(position)) return block
        }
        return null
    }

    /**
     * The caret place a point [position] (this host's px) goes to, as a dragged handle or mouse
     * takes it: the nearest block ([ChromeSelectionModel.blockAt]); above it its start, below it
     * its end, on it the place nearest on the line drawn there.
     */
    internal fun nearest(position: Offset): ChromeHit? {
        val host = coordinates?.takeIf { it.isAttached } ?: return null
        val list = ordered()
        if (boxes.size < list.size * 4) boxes = FloatArray(list.size * 4)
        for ((i, block) in list.withIndex()) {
            val c = block.attached()
            if (c == null) {
                boxes.fill(FAR, i * 4, i * 4 + 4)
                continue
            }
            val box = host.localBoundingBoxOf(c, clipBounds = false)
            boxes[i * 4] = box.left
            boxes[i * 4 + 1] = box.top
            boxes[i * 4 + 2] = box.right
            boxes[i * 4 + 3] = box.bottom
        }
        val index = ChromeSelectionModel.blockAt(boxes, list.size, position.x, position.y)
        if (index < 0 || boxes[index * 4] == FAR) return null
        val block = list[index]
        val offset = when {
            position.y < boxes[index * 4 + 1] -> 0
            position.y >= boxes[index * 4 + 3] -> block.length
            else -> block.layout!!.offsetAt(block.coordinates!!.localPositionOf(host, position))
        }
        return ChromeHit(index, block, offset.coerceIn(0, block.length))
    }

    /** How many presses in a row [position] at [time] makes, a double click being 2. */
    internal fun click(time: Long, position: Offset, timeout: Long, slop: Float): Int {
        clicks = if (time - lastClickAt <= timeout && (position - lastClickAtPosition).getDistance() <= slop) clicks + 1 else 1
        lastClickAt = time
        lastClickAtPosition = position
        return clicks
    }

    internal fun plainText(start: ChromeSelectable, startOffset: Int, end: ChromeSelectable, endOffset: Int): String {
        val list = ordered()
        val first = list.indexOf(start)
        val last = list.indexOf(end)
        if (first < 0 || last < 0) return ""
        return ChromeSelectionModel.plainText(list.map { it.text.text }, ChromeAnchor(first, startOffset), ChromeAnchor(last, endOffset)) { list[it].skipped() }
    }

    private companion object {
        const val FAR = Float.MAX_VALUE / 4
    }
}

/** The one selection of a [ChromeSelectionArea]: which host, its two ends, its handles, its toolbar. */
@Stable
class ChromeSelectionAreaState internal constructor(private val scope: CoroutineScope) {
    internal var coordinates: LayoutCoordinates? = null
    internal var scroll: ScrollableState? = null
    internal var toolbar: TextToolbar? = null
    internal var density: Density = Density(1f)
    internal val focus = FocusRequester()

    /** The host selected in; null with nothing selected. */
    var host by mutableStateOf<ChromeSelectionHost?>(null)
        private set

    /** Whether the selection was made by touch, which shows handles and the toolbar; a mouse's shows neither. */
    internal var touch by mutableStateOf(false)

    /** What a pointer is doing to it: [PRESS] (a long press still down), [START] or [END] (a handle dragged), or nothing. */
    internal var gesture by mutableIntStateOf(NONE)

    private var start: ChromeSelectable? = null
    private var startOffset = 0
    private var end: ChromeSelectable? = null
    private var endOffset = 0

    // The handles' points, in this area's px: each end's x on the bottom of its line.
    internal val startX = mutableFloatStateOf(0f)
    internal val startY = mutableFloatStateOf(0f)
    internal val endX = mutableFloatStateOf(0f)
    internal val endY = mutableFloatStateOf(0f)
    internal val startShown = mutableStateOf(false)
    internal val endShown = mutableStateOf(false)
    private var startHalf = 0f
    private var endHalf = 0f
    private val magnifierX = mutableFloatStateOf(Float.NaN)
    private val magnifierY = mutableFloatStateOf(Float.NaN)

    /** Bumped whenever the selection or where it is drawn changes, which the toolbar follows. */
    private var tick by mutableIntStateOf(0)
    private var toolbarRect = Rect.Zero
    private var toolbarShown = false
    private var grab = Offset.Zero
    private var pointer = Offset.Zero
    private var autoScroll: Job? = null

    /** The text selected, as a copy puts it on the clipboard; null with nothing selected. */
    val selectedText: String?
        get() {
            val host = host ?: return null
            return host.plainText(start ?: return null, startOffset, end ?: return null, endOffset)
        }

    /** Selects [a] at [aOffset] to [b] at [bOffset] (either way round) in [host]. */
    internal fun select(host: ChromeSelectionHost, a: ChromeSelectable, aOffset: Int, b: ChromeSelectable, bOffset: Int, touch: Boolean) {
        val list = host.ordered()
        val ai = list.indexOf(a)
        val bi = list.indexOf(b)
        if (ai < 0 || bi < 0) return
        val forward = ChromeAnchor(ai, aOffset) <= ChromeAnchor(bi, bOffset)
        if (this.host !== host) this.host?.let(::clearRanges)
        start = if (forward) a else b
        startOffset = if (forward) aOffset else bOffset
        end = if (forward) b else a
        endOffset = if (forward) bOffset else aOffset
        this.host = host
        this.touch = touch
        apply()
    }

    /** Every block's part of the selection, then where the handles go. */
    private fun apply() {
        val host = host ?: return
        val list = host.ordered()
        val first = list.indexOf(start)
        val last = list.indexOf(end)
        if (first < 0 || last < 0) return clear()
        val from = ChromeAnchor(first, startOffset)
        val to = ChromeAnchor(last, endOffset)
        var changed = false
        for (i in list.indices) {
            val block = list[i]
            val part = ChromeSelectionModel.rangeIn(i, block.length, from, to)
            if (block.selected.longValue != part) {
                block.selected.longValue = part
                changed = true
            }
        }
        refresh()
        // The toolbar offers Select all by what is selected, which can change with the handles where they were.
        if (changed) tick++
    }

    private fun clearRanges(host: ChromeSelectionHost) {
        for (block in host.ordered()) block.selected.longValue = ChromeSelectionModel.NONE
    }

    /** Ends the selection. */
    fun clear() {
        val host = host ?: return
        clearRanges(host)
        this.host = null
        start = null
        end = null
        gesture = NONE
        touch = false
        magnifierX.floatValue = Float.NaN
        autoScroll?.cancel()
        hideToolbar()
        tick++
    }

    internal fun selectAll() {
        val host = host ?: return
        val list = host.ordered()
        if (list.isEmpty()) return
        start = list.first()
        startOffset = 0
        end = list.last()
        endOffset = list.last().length
        apply()
    }

    private fun allSelected(): Boolean {
        val list = host?.ordered() ?: return true
        return list.isEmpty() || (start === list.first() && startOffset == 0 && end === list.last() && endOffset >= list.last().length)
    }

    /** Puts the selected text on the clipboard; from the toolbar the selection ends, as a TextView's does. */
    internal fun copy(thenClear: Boolean) {
        val text = selectedText ?: return
        val clipboard = host?.clipboard ?: return
        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("text", text))) }
        if (thenClear) clear()
    }

    /** Where the handles and the toolbar go, after the selection, or what it is in, changed or moved. */
    internal fun refresh() {
        if (host == null) return
        val area = coordinates?.takeIf { it.isAttached } ?: return
        val s = start ?: return
        val e = end ?: return
        val sl = s.layout
        val el = e.layout
        val sc = s.attached()
        val ec = e.attached()
        if (sl == null || el == null || sc == null || ec == null) {
            if (startShown.value || endShown.value) {
                startShown.value = false
                endShown.value = false
                tick++
            }
            return
        }
        val so = startOffset.coerceIn(0, s.length)
        val sLine = sl.result.getLineForOffset(so)
        val sPoint = area.localPositionOf(sc, Offset(sl.startX(so), sl.bandBottom(sLine)))
        val sTop = area.localPositionOf(sc, Offset(0f, sl.bandTop(sLine))).y
        val eo = endOffset.coerceIn(0, e.length)
        val eLine = el.endLine(if (s === e) so else 0, eo)
        val ePoint = area.localPositionOf(ec, Offset(el.endX(eo, eLine), el.bandBottom(eLine)))
        val eTop = area.localPositionOf(ec, Offset(0f, el.bandTop(eLine))).y
        startHalf = (sPoint.y - sTop) / 2
        endHalf = (ePoint.y - eTop) / 2
        // The toolbar stands over what is selected, inside what of it is on screen.
        val oneLine = sTop == eTop
        val left = if (oneLine) min(sPoint.x, ePoint.x) else 0f
        val right = if (oneLine) max(sPoint.x, ePoint.x) else area.size.width.toFloat()
        val top = sTop.coerceIn(0f, area.size.height.toFloat())
        val bottom = ePoint.y.coerceIn(top, area.size.height.toFloat())
        val rect = Rect(area.localToRoot(Offset(left, top)), area.localToRoot(Offset(right, bottom)))
        val startVisible = visible(area, sc, sPoint)
        val endVisible = visible(area, ec, ePoint)
        // Called whenever anything in the host is placed: what did not move writes nothing, or
        // the handles it moves would be placed again, and it called again, for good.
        if (startX.floatValue == sPoint.x && startY.floatValue == sPoint.y && endX.floatValue == ePoint.x && endY.floatValue == ePoint.y &&
            startShown.value == startVisible && endShown.value == endVisible && toolbarRect == rect
        ) {
            return
        }
        startX.floatValue = sPoint.x
        startY.floatValue = sPoint.y
        endX.floatValue = ePoint.x
        endY.floatValue = ePoint.y
        startShown.value = startVisible
        endShown.value = endVisible
        toolbarRect = rect
        tick++
    }

    /** Whether [point] of a block at [block] is on screen: inside what of the block its scrolling parents show. */
    private fun visible(area: LayoutCoordinates, block: LayoutCoordinates, point: Offset): Boolean {
        val shown = area.localBoundingBoxOf(block, clipBounds = true)
        return point.x >= shown.left - 1 && point.x <= shown.right + 1 && point.y >= shown.top && point.y <= shown.bottom + 1 &&
            point.y >= 0 && point.y <= area.size.height + 1
    }

    internal fun moved(host: ChromeSelectionHost) {
        if (this.host === host) refresh()
    }

    internal fun blockRemoved(host: ChromeSelectionHost, block: ChromeSelectable) {
        if (this.host !== host) return
        if (block === start || block === end) clear() else apply()
    }

    internal fun hostGone(host: ChromeSelectionHost) {
        if (this.host === host) clear()
    }

    // Handles

    /** The handle a press at [position] (this area's px) takes, or [NONE]. */
    internal fun handleAt(position: Offset): Int {
        if (host == null || !touch) return NONE
        val half = with(density) { HANDLE.toPx() } / 2
        var best = NONE
        var nearest = with(density) { HANDLE_REACH.toPx() }
        if (startShown.value) {
            val d = (position - Offset(startX.floatValue - half, startY.floatValue + half)).getDistance()
            if (d <= nearest) {
                best = START
                nearest = d
            }
        }
        if (endShown.value) {
            val d = (position - Offset(endX.floatValue + half, endY.floatValue + half)).getDistance()
            if (d <= nearest) best = END
        }
        return best
    }

    internal fun startDrag(handle: Int, position: Offset) {
        grab = position - if (handle == START) Offset(startX.floatValue, startY.floatValue) else Offset(endX.floatValue, endY.floatValue)
        pointer = position
        gesture = handle
    }

    /**
     * A handle dragged to [position] (this area's px): its end goes to the caret place on the line
     * the handle hangs from, keeping where on the handle it was taken. The selection never
     * collapses: an end dragged past the other stops a character after it.
     */
    internal fun dragTo(position: Offset) {
        pointer = position
        val which = gesture
        if (which != START && which != END) return
        if (autoScroll?.isActive != true && edgeSpeed(position.y) != 0f) startAutoScroll()
        val host = host ?: return
        val area = coordinates?.takeIf { it.isAttached } ?: return
        val hostAt = host.coordinates?.takeIf { it.isAttached } ?: return
        val half = if (which == START) startHalf else endHalf
        val target = position - grab - Offset(0f, half)
        val hit = host.nearest(hostAt.localPositionOf(area, target)) ?: return
        val list = host.ordered()
        val s = start ?: return
        val e = end ?: return
        val at = ChromeAnchor(hit.index, hit.offset)
        var block = hit.block
        var offset = hit.offset
        if (which == END) {
            if (at <= ChromeAnchor(list.indexOf(s), startOffset)) {
                block = s
                offset = min(startOffset + 1, s.length)
            }
            if (block !== e || offset != endOffset) {
                end = block
                endOffset = offset
                apply()
                host.haptics?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        } else {
            if (at >= ChromeAnchor(list.indexOf(e), endOffset)) {
                block = e
                offset = max(endOffset - 1, 0)
            }
            if (block !== s || offset != startOffset) {
                start = block
                startOffset = offset
                apply()
                host.haptics?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
        // The magnifier shows the line the handle hangs from, at the finger.
        magnifierX.floatValue = target.x
        magnifierY.floatValue = (if (which == START) startY.floatValue - startHalf else endY.floatValue - endHalf)
    }

    internal fun endDrag() {
        if (gesture == START || gesture == END) gesture = NONE
        magnifierX.floatValue = Float.NaN
        autoScroll?.cancel()
        tick++
    }

    internal fun magnifierSource(): Offset {
        val x = magnifierX.floatValue
        val g = gesture
        return if ((g == START || g == END) && !x.isNaN()) Offset(x, magnifierY.floatValue) else Offset.Unspecified
    }

    /**
     * While a handle is held near the area's top or bottom, the list scrolls a step a frame, and
     * the handle's end follows what passes under it; it stops once the handle leaves the edge or
     * the list can go no further, so a handle held still asks for no frames.
     */
    private fun startAutoScroll() {
        autoScroll?.cancel()
        autoScroll = scope.launch {
            while (gesture == START || gesture == END) {
                val speed = edgeSpeed(pointer.y)
                if (speed == 0f || (scroll?.dispatchRawDelta(speed) ?: 0f) == 0f) break
                // Laid out at the new place by the next frame: then the end goes to what is under the handle.
                withFrameNanos { }
                dragTo(pointer)
            }
        }
    }

    private fun edgeSpeed(y: Float): Float {
        val area = coordinates ?: return 0f
        val zone = with(density) { EDGE_ZONE.toPx() }
        val fastest = with(density) { EDGE_SPEED.toPx() }
        val height = area.size.height.toFloat()
        return when {
            y < zone -> -fastest * ((zone - y) / zone).coerceAtMost(1f)
            y > height - zone -> fastest * ((y - (height - zone)) / zone).coerceAtMost(1f)
            else -> 0f
        }
    }

    // Toolbar, keys, focus

    /** The toolbar is up while a touch selection is not being changed: [tick] then, or -1. */
    internal fun toolbarWanted(): Int = if (host != null && touch && gesture == NONE) tick else -1

    internal fun syncToolbar(wanted: Int) {
        if (wanted < 0) return hideToolbar()
        val toolbar = toolbar ?: return
        toolbar.showMenu(
            rect = toolbarRect,
            onCopyRequested = { copy(thenClear = true) },
            onPasteRequested = null,
            onCutRequested = null,
            onSelectAllRequested = if (allSelected()) null else ({ selectAll() }),
        )
        toolbarShown = true
    }

    private fun hideToolbar() {
        if (!toolbarShown) return
        toolbarShown = false
        toolbar?.hide()
    }

    /** Takes the keyboard, for Ctrl+C, once a mouse selected something. */
    internal fun focusForKeys() {
        runCatching { focus.requestFocus() }
    }

    internal fun key(event: KeyEvent): Boolean {
        if (host == null || event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return false
        return when (event.key) {
            Key.C -> {
                copy(thenClear = false)
                true
            }
            Key.A -> {
                selectAll()
                true
            }
            else -> false
        }
    }
}

/**
 * Where [content]'s ChromeText can be selected in place (a transcript): at most one selection, in
 * one host, with its handles drawn over [content], its magnifier, the system's text toolbar, and
 * [scroll] scrolled while a handle is held near the top or bottom.
 */
@Composable
fun ChromeSelectionArea(scroll: ScrollableState?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val area = remember(scope) { ChromeSelectionAreaState(scope) }
    val toolbar = LocalTextToolbar.current
    val density = LocalDensity.current
    SideEffect {
        area.scroll = scroll
        area.toolbar = toolbar
        area.density = density
    }
    Box(
        modifier
            .onPlaced { area.coordinates = it }
            .pointerInput(area) { areaGestures(area) }
            .then(if (LocalChromeSelectionMagnifier.current) Modifier.magnifier(sourceCenter = { area.magnifierSource() }, magnifierCenter = { Offset.Unspecified }) else Modifier)
            .onKeyEvent { area.key(it) }
            .focusRequester(area.focus)
            .focusProperties { canFocus = area.host != null }
            .focusTarget(),
    ) {
        CompositionLocalProvider(LocalChromeSelectionArea provides area, content = content)
        SelectionHandles(area)
    }
    SelectionBack(area)
    LaunchedEffect(area) {
        snapshotFlow { area.toolbarWanted() }.collect { area.syncToolbar(it) }
    }
    DisposableEffect(area) { onDispose { area.clear() } }
}

/** Back ends a selection before it does anything else. */
@Composable
private fun SelectionBack(area: ChromeSelectionAreaState) {
    BackHandler(enabled = area.host != null) { area.clear() }
}

/**
 * A press on a handle drags it; any other press ends the selection and goes on to whatever it
 * presses (a tap, a scroll, a new long press). Seen before anything inside, so a handle over a
 * button or a link takes the press.
 */
private suspend fun PointerInputScope.areaGestures(area: ChromeSelectionAreaState) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    if (area.host == null) return@awaitEachGesture
    val handle = if (down.type == PointerType.Mouse) NONE else area.handleAt(down.position)
    if (handle == NONE) {
        if (down.type != PointerType.Mouse || currentEvent.buttons.isPrimaryPressed) area.clear()
        return@awaitEachGesture
    }
    down.consume()
    area.startDrag(handle, down.position)
    try {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach(PointerInputChange::consume)
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null || !change.pressed) break
            area.dragTo(change.position)
        }
    } finally {
        area.endDrag()
    }
}

/** The two teardrops, in the selection colours, while a touch selection is up. */
@Composable
private fun SelectionHandles(area: ChromeSelectionAreaState) {
    if (area.host == null || !area.touch) return
    val color = LocalTextSelectionColors.current.handleColor
    SelectionHandle(area, start = true, color)
    SelectionHandle(area, start = false, color)
}

/**
 * A handle: a circle with the corner toward the text square, hanging under its end's line —
 * left of the start, right of the end. It moves in layout alone, so dragging it recomposes nothing.
 */
@Composable
private fun SelectionHandle(area: ChromeSelectionAreaState, start: Boolean, color: Color) {
    val x = if (start) area.startX else area.endX
    val y = if (start) area.startY else area.endY
    val shown = if (start) area.startShown else area.endShown
    Box(
        Modifier
            .offset {
                val side = HANDLE.roundToPx()
                IntOffset((if (start) x.floatValue - side else x.floatValue).roundToInt(), y.floatValue.roundToInt())
            }
            .size(HANDLE)
            .graphicsLayer { alpha = if (shown.value) 1f else 0f }
            .testTag(if (start) CHROME_SELECTION_START_HANDLE else CHROME_SELECTION_END_HANDLE)
            .drawBehind {
                val r = size.width / 2
                drawCircle(color, r, center)
                drawRect(color, Offset(if (start) r else 0f, 0f), Size(r, r))
            },
    )
}

/**
 * A host for the ChromeText of one region that selects as one (an answer's markdown), in the
 * [ChromeSelectionArea] around; null where there is none. Give it to [chromeSelectionHost] on the
 * region and to [ChromeSelectionBlocks] around its content.
 */
@Composable
fun rememberChromeSelectionHost(): ChromeSelectionHost? {
    val area = LocalChromeSelectionArea.current ?: return null
    val host = remember(area) { ChromeSelectionHost(area) }
    DisposableEffect(host) { onDispose { area.hostGone(host) } }
    return host
}

/** The region [host] selects in: a long press on a word, a mouse drag, a double or triple click. */
fun Modifier.chromeSelectionHost(host: ChromeSelectionHost?): Modifier = if (host == null) {
    this
} else {
    this
        .onGloballyPositioned {
            host.coordinates = it
            host.area.moved(host)
        }
        .pointerInput(host) { hostGestures(host) }
}

/** [content]'s ChromeText as [host]'s blocks; copies go to the clipboard [content] has. */
@Composable
fun ChromeSelectionBlocks(host: ChromeSelectionHost?, content: @Composable () -> Unit) {
    if (host == null) return content()
    val clipboard = LocalClipboard.current
    val haptics = LocalHapticFeedback.current
    SideEffect {
        host.clipboard = clipboard
        host.haptics = haptics
    }
    CompositionLocalProvider(LocalChromeSelectionHost provides host, content = content)
}

private suspend fun PointerInputScope.hostGestures(host: ChromeSelectionHost) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    if (down.type == PointerType.Mouse) {
        if (currentEvent.buttons.isPrimaryPressed) mouseSelect(host, down)
    } else if (!down.isConsumed) {
        touchSelect(host, down)
    }
}

/**
 * A long press on a word selects it, as Android does: nothing is taken from the press until it
 * has lasted the long-press timeout without moving past the touch slop, so a tap, a link and a
 * scroll go on as before. Off the text it leaves the press alone. Moving on before lifting extends
 * the selection from the word.
 */
private suspend fun AwaitPointerEventScope.touchSelect(host: ChromeSelectionHost, down: PointerInputChange) {
    val endedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        var ended = false
        while (!ended) {
            val change = awaitPointerEvent(PointerEventPass.Main).changes.firstOrNull { it.id == down.id }
            ended = change == null || !change.pressed || change.isConsumed ||
                (change.position - down.position).getDistance() > viewConfiguration.touchSlop
        }
        true
    }
    if (endedEarly != null) return
    val word = host.wordAt(down.position) ?: return
    val area = host.area
    area.select(host, word.block, word.range.min, word.block, word.range.max, touch = true)
    area.gesture = PRESS
    host.haptics?.performHapticFeedback(HapticFeedbackType.LongPress)
    try {
        // The rest of this press is the selection's: its release clicks nothing under it.
        do {
            val event = awaitPointerEvent(PointerEventPass.Main)
            event.changes.forEach(PointerInputChange::consume)
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change != null && change.pressed) extendFromWord(host, word, change.position)
        } while (event.changes.any { it.pressed })
    } finally {
        if (area.gesture == PRESS) area.gesture = NONE
    }
}

private fun extendFromWord(host: ChromeSelectionHost, word: ChromeWord, position: Offset) {
    val hit = host.nearest(position) ?: return
    val index = host.ordered().indexOf(word.block)
    val at = ChromeAnchor(hit.index, hit.offset)
    val area = host.area
    when {
        at > ChromeAnchor(index, word.range.max) -> area.select(host, word.block, word.range.min, hit.block, hit.offset, touch = true)
        at < ChromeAnchor(index, word.range.min) -> area.select(host, hit.block, hit.offset, word.block, word.range.max, touch = true)
        else -> area.select(host, word.block, word.range.min, word.block, word.range.max, touch = true)
    }
}

/** A mouse: press and drag selects, a double click a word, a triple click a block; then Ctrl+C copies. */
private suspend fun AwaitPointerEventScope.mouseSelect(host: ChromeSelectionHost, down: PointerInputChange) {
    val area = host.area
    val clicks = host.click(down.uptimeMillis, down.position, viewConfiguration.doubleTapTimeoutMillis, viewConfiguration.touchSlop)
    if (clicks >= 2) {
        val word = (if (clicks == 2) host.wordAt(down.position) else host.blockAt(down.position)) ?: return
        area.select(host, word.block, word.range.min, word.block, word.range.max, touch = false)
        area.focusForKeys()
        down.consume()
        do {
            val event = awaitPointerEvent()
            event.changes.forEach(PointerInputChange::consume)
        } while (event.changes.any { it.pressed })
        return
    }
    val anchor = host.nearest(down.position) ?: return
    var selecting = false
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        if (!change.pressed) break
        if (!selecting && (change.position - down.position).getDistance() < viewConfiguration.touchSlop / 2) continue
        selecting = true
        change.consume()
        val hit = host.nearest(change.position) ?: continue
        area.select(host, anchor.block, anchor.offset, hit.block, hit.offset, touch = false)
    }
    if (selecting) area.focusForKeys()
}
