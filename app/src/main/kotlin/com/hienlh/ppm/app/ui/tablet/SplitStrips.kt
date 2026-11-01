package com.hienlh.ppm.app.ui.tablet

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.toSize
import com.hienlh.ppm.design.WebCursor

/**
 * The dividers of the working area, hit-tested together as react-resizable-panels does it: the
 * library widens every separator to a 20 px region (`resizeTargetMinimumSize.coarse`) and looks
 * for the pointer in all of them at once. Where one divider runs into another at a right angle —
 * the panels' line into the dock's edge, a column's line into a row's — the two regions overlap,
 * Chrome shows `move` there, and a press drags both, each along its own axis. A Compose box hands
 * a pointer to the topmost box alone, so the area ([splitStrips]) does the hit test itself, before
 * any divider sees the event, and drives every divider's drag from there.
 */
@Stable
class SplitStrips {
    private val strips = mutableListOf<SplitStrip>()

    /** `move` while a mouse rests where two dividers cross, laid over the dividers' own arrows. */
    var junction by mutableStateOf<PointerIcon?>(null)
        private set

    fun add(strip: SplitStrip) {
        strips += strip
    }

    fun remove(strip: SplitStrip) {
        strips -= strip
    }

    /**
     * The dividers a pointer at [at] (root pixels) takes, as the library's `ve()` finds them: every
     * region holding the point, less those a grip covers. Over a separator's grip the grip is the
     * pointer's target, and a divider it lies over from earlier in the page is turned away (`Tt()`):
     * the dock's grip, which a 50/50 split puts on the panels' line, shows and drags the dock alone.
     */
    fun hits(at: Offset): List<SplitStrip> {
        val inside = strips.filter { it.region()?.holds(at) == true }
        val grip = inside.filter { it.grip()?.contains(at) == true }.maxByOrNull { it.order } ?: return inside
        return inside.filter { it === grip || it.order > grip.order }
    }

    /** The separator under [at], its line or its grip, a later one over an earlier: CSS's `:hover` and `:active` target. */
    fun elementAt(at: Offset): SplitStrip? = strips.filter { it.element(at) }.maxByOrNull { it.order }

    /** A mouse at [at], or gone ([at] null): the line under it lights, and a junction shows `move` unless a button is down. */
    fun hover(at: Offset?, pressed: Boolean) {
        val over = at?.let { elementAt(it) }
        strips.forEach { it.hovered = it === over }
        if (!pressed) junction = if (at != null && crosses(hits(at))) WebCursor.Move else null
    }
}

val LocalSplitStrips = staticCompositionLocalOf { SplitStrips() }

/** One divider as the area sees it: where its line lies, which way it runs, what dragging it does. */
@Stable
class SplitStrip(val vertical: Boolean) {
    internal var coordinates: LayoutCoordinates? = null

    /** Where the divider comes in the page: a later one is drawn over an earlier one. */
    internal var order = 0

    /** Half the 20 dp region across the line, and half the 12 dp grip, in pixels. */
    internal var reach = 0f
    internal var gripHalf = 0f
    internal var onStart: () -> Unit = {}
    internal var onDrag: (Float) -> Boolean = { false }
    internal var onEnd: () -> Unit = {}

    /** A mouse is over the line or its grip (`hover:bg-primary/30`). */
    var hovered by mutableStateOf(false)
        internal set

    /** A mouse press began on the line or its grip, which keeps `:active` until the release (`active:bg-primary/50`). */
    var pressed by mutableStateOf(false)
        internal set

    internal fun line(): Rect? = coordinates?.takeIf { it.isAttached }?.let { Rect(it.localToRoot(Offset.Zero), it.size.toSize()) }

    internal fun region(): Rect? = line()?.let {
        if (vertical) Rect(it.center.x - reach, it.top, it.center.x + reach, it.bottom) else Rect(it.left, it.center.y - reach, it.right, it.center.y + reach)
    }

    internal fun grip(): Rect? = line()?.let { Rect(it.center.x - gripHalf, it.center.y - gripHalf, it.center.x + gripHalf, it.center.y + gripHalf) }

    internal fun element(at: Offset): Boolean = line()?.contains(at) == true || grip()?.contains(at) == true
}

/** The library's region test: both edges count (`qe()`), where CSS's own hit test leaves the far one out. */
private fun Rect.holds(at: Offset) = at.x >= left && at.x <= right && at.y >= top && at.y <= bottom

private fun crosses(strips: List<SplitStrip>) = strips.any { it.vertical } && strips.any { !it.vertical }

/**
 * react-resizable-panels' cursor for a drag of [taken] (`_t()`), as Chrome on Android draws it: a
 * divider's double arrow, or at a junction `move`, narrowed to the way the lines can still go
 * once a limit holds one (`e-resize`, `n-resize`) or both (a diagonal). [leftward] and [upward]
 * say which way a held line was pushed, null while it moves.
 */
private fun dragCursor(taken: List<SplitStrip>, leftward: Boolean?, upward: Boolean?): PointerIcon = when {
    !crosses(taken) -> if (taken.first().vertical) WebCursor.ResizeHorizontal else WebCursor.ResizeVertical
    leftward != null && upward != null -> if (leftward == upward) WebCursor.ResizeTopLeft else WebCursor.ResizeTopRight
    leftward != null -> WebCursor.ResizeHorizontal
    upward != null -> WebCursor.ResizeVertical
    else -> WebCursor.Move
}

/**
 * The pointer handling of [strips] over the area they divide: the junction's cursor, which line a
 * mouse lights, and every divider drag. As on the web the lines follow from the first pixel, a
 * mouse's other buttons drag nothing, and [held] keeps the drag's cursor wherever the pointer goes.
 * Only a mouse lights a line: Chrome on Android applies `hover:` only while a mouse is plugged in,
 * and a finger's press is a tap it cancels as soon as the finger moves, `:active` with it.
 */
fun Modifier.splitStrips(strips: SplitStrips, held: HeldCursor): Modifier = composed {
    val placed = remember { PlacedAt() }
    val junction = strips.junction
    onGloballyPositioned { placed.coordinates = it }
        .pointerHoverIcon(junction ?: WebCursor.Default, overrideDescendants = junction != null)
        .pointerInput(strips) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue
                    if (change.type != PointerType.Mouse) continue
                    val at = if (event.type == PointerEventType.Exit) null else placed.coordinates?.localToRoot(change.position)
                    strips.hover(at, pressed = change.pressed)
                }
            }
        }
        .pointerInput(strips, held) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                val origin = placed.coordinates?.localToRoot(down.position) ?: return@awaitEachGesture
                val taken = strips.hits(origin)
                if (taken.isEmpty()) return@awaitEachGesture
                down.consume()
                val target = if (down.type == PointerType.Mouse) strips.elementAt(origin) else null
                taken.forEach {
                    it.pressed = it === target
                    it.onStart()
                }
                var leftward: Boolean? = null
                var upward: Boolean? = null
                held.icon = dragCursor(taken, null, null)
                var last = origin
                try {
                    // Every event of this pointer up to and including its release, which can carry
                    // the last stretch of a finger's move. A mouse's release moves nothing: the
                    // library reads only `pointermove`, and the emulator puts a mouse's release a
                    // few pixels from its last move.
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                        val moves = change.pressed || change.type != PointerType.Mouse
                        placed.coordinates?.localToRoot(change.position)?.takeIf { moves }?.let { now ->
                            // Each line moves by the whole offset from the press along its own axis,
                            // and reports whether a limit holds it.
                            if (now.x != last.x) {
                                val blocked = taken.filter { it.vertical }.map { it.onDrag(now.x - origin.x) }.any { it }
                                leftward = if (blocked && now.x != origin.x) now.x < origin.x else null
                            }
                            if (now.y != last.y) {
                                val blocked = taken.filter { !it.vertical }.map { it.onDrag(now.y - origin.y) }.any { it }
                                upward = if (blocked && now.y != origin.y) now.y < origin.y else null
                            }
                            last = now
                            held.icon = dragCursor(taken, leftward, upward)
                        }
                        change.consume()
                        if (!change.pressed) break
                    }
                } finally {
                    held.icon = null
                    taken.forEach {
                        it.pressed = false
                        it.onEnd()
                    }
                }
            }
        }
}
