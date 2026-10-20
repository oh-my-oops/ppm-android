package com.hienlh.ppm.app.ui.tablet

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ThemeShadow
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.workspace.MAX_ROWS
import com.hienlh.ppm.state.workspace.SplitDirection
import com.hienlh.ppm.state.workspace.findPanelPosition
import com.hienlh.ppm.state.workspace.maxColumns
import kotlin.math.abs
import kotlin.math.roundToInt

/** use-touch-tab-drag.ts: how long a finger rests on a tab to pick it up, and how far it may stray meanwhile. */
private const val HOLD_MS = 200L
private val HOLD_TOLERANCE = 10.dp

/** A panel's edge quarter is a split; the rest, a move (`T = 0.25`). */
private const val EDGE = 0.25f

enum class DropZone(val split: SplitDirection?) {
    Left(SplitDirection.Left),
    Right(SplitDirection.Right),
    Top(SplitDirection.Up),
    Bottom(SplitDirection.Down),
    Center(null),
}

/** The tab picked up; for a mouse, also how it looks ([snapshot]) and where in it the pointer took it ([grab]). */
data class DraggedTab(
    val id: String,
    val panelId: String,
    val label: String,
    val snapshot: GraphicsLayer? = null,
    val grab: Offset = Offset.Zero,
)

data class DropTarget(val panelId: String, val zone: DropZone)

/** Where a mouse would put the tab among a bar's tabs: the bar's panel, and the tab it goes before (null: the end). */
data class BarInsert(val panelId: String, val beforeTabId: String?)

/**
 * A tab being dragged to another place (use-touch-tab-drag.ts, split-drop-overlay.tsx). A finger
 * resting 200 ms on a tab picks it up — one that moves first is scrolling the bar — and a label
 * follows it. Over a panel, its edge quarter splits the panel that way while the grid has room
 * (three columns, three rows) and the rest moves the tab into it. A mouse picks the tab up once
 * it moves, as a browser starts a drag, and can also drop it among a bar's tabs (use-tab-drag.ts);
 * the web's touch drag has no such drop, so a finger has none either. The dock takes no drop.
 */
@Stable
class TabDrag {
    var tab by mutableStateOf<DraggedTab?>(null)
        private set

    /** The finger, in the window's coordinates. */
    var at by mutableStateOf(Offset.Zero)
        private set

    var target by mutableStateOf<DropTarget?>(null)
        private set

    var insert by mutableStateOf<BarInsert?>(null)
        private set

    var byMouse by mutableStateOf(false)
        private set

    /** Each grid panel's content area in the window (`data-panel-drop-zone`). */
    internal val areas = mutableMapOf<String, Rect>()

    /** Each tab bar in the window, and each tab's span in it, for a mouse's drop among them. */
    internal val bars = mutableMapOf<String, Rect>()
    internal val chips = mutableMapOf<String, Pair<String, Rect>>()

    internal fun start(dragged: DraggedTab, point: Offset, mouse: Boolean) {
        tab = dragged
        at = point
        byMouse = mouse
    }

    internal fun move(point: Offset, grid: List<List<String>>) {
        at = point
        val area = areas.entries.firstOrNull { it.value.contains(point) }
        target = area?.let { (panelId, rect) -> DropTarget(panelId, zoneAt(rect, point, grid, panelId)) }
        insert = if (byMouse && area == null) barInsert(point) else null
    }

    internal fun drop(model: AppModel) {
        val dragged = tab ?: return
        val target = target
        val insert = insert
        cancel()
        when {
            insert != null -> model.dropTab(dragged.id, dragged.panelId, insert.panelId, insert.beforeTabId)
            target == null -> Unit
            target.zone.split != null -> model.splitPanel(target.zone.split, dragged.id, dragged.panelId, target.panelId)
            target.panelId != dragged.panelId -> model.moveTab(dragged.id, dragged.panelId, target.panelId)
        }
    }

    internal fun cancel() {
        tab = null
        target = null
        insert = null
    }

    /**
     * The tab a point is before in the bar under it: the one it is on, or the next when past that
     * one's middle. Wrapped tabs are read a row at a time, so past the end of a row is before the
     * first tab of the next.
     */
    private fun barInsert(point: Offset): BarInsert? {
        val panelId = bars.entries.firstOrNull { it.value.contains(point) }?.key ?: return null
        val spans = chips.entries.filter { it.value.first == panelId }.map { it.key to it.value.second }
            .sortedWith(compareBy({ it.second.top }, { it.second.left }))
        val top = spans.lastOrNull { it.second.top <= point.y }?.second?.top ?: spans.firstOrNull()?.second?.top
        val row = spans.filter { it.second.top == top }
        val on = row.firstOrNull { point.x < it.second.right }
        val before = when {
            on == null -> row.lastOrNull()?.let { spans.getOrNull(spans.indexOf(it) + 1)?.first }
            point.x < on.second.center.x -> on.first
            else -> spans.getOrNull(spans.indexOf(on) + 1)?.first
        }
        return BarInsert(panelId, before)
    }
}

val LocalTabDrag = staticCompositionLocalOf<TabDrag?> { null }

/** `detectZone`: a split only where the grid has room for one more column in this row, or one more row. */
private fun zoneAt(rect: Rect, point: Offset, grid: List<List<String>>, panelId: String): DropZone {
    val rx = (point.x - rect.left) / rect.width
    val ry = (point.y - rect.top) / rect.height
    val position = findPanelPosition(grid, panelId)
    val canSplitH = position != null && (grid.getOrNull(position.row)?.size ?: 0) < maxColumns(false)
    val canSplitV = grid.size < MAX_ROWS
    return when {
        rx < EDGE && canSplitH -> DropZone.Left
        rx > 1 - EDGE && canSplitH -> DropZone.Right
        ry < EDGE && canSplitV -> DropZone.Top
        ry > 1 - EDGE && canSplitV -> DropZone.Bottom
        else -> DropZone.Center
    }
}

/**
 * A tab's gestures besides the tap: picking it up (see [TabDrag]) and its menu — a finger held
 * still for the system's long press, as Chrome opens the web's, or a mouse's right button.
 */
fun Modifier.tabGestures(drag: TabDrag, model: AppModel, tab: () -> DraggedTab, onMenu: (Offset) -> Unit): Modifier = pickUpGestures(
    drag,
    onPickUp = { grab, at, mouse -> drag.start(tab().copy(grab = grab), at, mouse) },
    onMove = { drag.move(it, model.layout?.grid.orEmpty()) },
    onDrop = { drag.drop(model) },
    onCancel = { drag.cancel() },
    onMenu = onMenu,
)

/**
 * Picking something up to drag it, as a tab or a project row is: a finger resting [HOLD_MS] on
 * it, or a mouse pressing it and moving. [onPickUp] gets where in it the pointer took it and where
 * that is in the root, [onMove] the pointer in the root until [onDrop] (lifted) or [onCancel]. A
 * finger then held still for the system's long press opens [onMenu] instead, and so does a mouse's
 * right button; with no menu, a finger held still just keeps it. It watches ahead of what it is on
 * and of any scroll around it, and takes the touch only once it has picked something up, so until
 * then a tap is still a tap and a swipe still scrolls.
 */
internal fun Modifier.pickUpGestures(
    key: Any?,
    onPickUp: (grab: Offset, at: Offset, mouse: Boolean) -> Unit,
    onMove: (Offset) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit,
    onMenu: ((Offset) -> Unit)? = null,
): Modifier = composed {
    val haptics = LocalHapticFeedback.current
    val pickUp by rememberUpdatedState(onPickUp)
    val move by rememberUpdatedState(onMove)
    val drop by rememberUpdatedState(onDrop)
    val cancel by rememberUpdatedState(onCancel)
    val menu by rememberUpdatedState(onMenu)
    val placed = remember { Placed() }
    onGloballyPositioned { placed.coordinates = it }.pointerInput(key) {
        awaitEachGesture {
            val press = awaitPointerEvent(PointerEventPass.Initial)
            val down = press.changes.firstOrNull { it.changedToDownIgnoreConsumed() } ?: return@awaitEachGesture
            val mouse = down.type == PointerType.Mouse
            if (mouse && press.buttons.isSecondaryPressed) {
                menu?.let {
                    down.consume()
                    it(down.position)
                }
                return@awaitEachGesture
            }
            if (mouse && !press.buttons.isPrimaryPressed) return@awaitEachGesture
            val picked = if (mouse) awaitMouseMove(down) else awaitHold(down)
            if (!picked) return@awaitEachGesture
            val start = placed.coordinates?.localToRoot(down.position) ?: return@awaitEachGesture
            pickUp(down.position, start, mouse)
            val menuAt = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
            var still = !mouse && menu != null
            var done = false
            try {
                while (true) {
                    val event = if (still) {
                        val left = menuAt - SystemClock.uptimeMillis()
                        if (left > 0) withTimeoutOrNull(left) { awaitPointerEvent(PointerEventPass.Initial) } else null
                    } else {
                        awaitPointerEvent(PointerEventPass.Initial)
                    }
                    if (event == null) {
                        // Held still: the long press opens the menu. As on the web what follows the
                        // finger stays until it lifts, and what it picked up is put down where it was.
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu?.invoke(down.position)
                        consumeUntilUp(down.id)
                        break
                    }
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (still && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) still = false
                    placed.coordinates?.localToRoot(change.position)?.let(move)
                    if (!change.pressed) {
                        done = true
                        drop()
                        break
                    }
                }
            } finally {
                if (!done) cancel()
            }
        }
    }
}

internal class Placed {
    var coordinates: LayoutCoordinates? = null
}

/** A finger that rests [HOLD_MS] within [HOLD_TOLERANCE] picks up what it is on; one lifted, moved or scrolling first does not. */
private suspend fun AwaitPointerEventScope.awaitHold(down: PointerInputChange): Boolean {
    val tolerance = HOLD_TOLERANCE.toPx()
    val outcome = withTimeoutOrNull(HOLD_MS) {
        var kept: Boolean? = null
        while (kept == null) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
            val moved = change?.let { it.position - down.position }
            if (change == null || !change.pressed || moved == null || abs(moved.x) > tolerance || abs(moved.y) > tolerance) {
                kept = false
            } else if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.id == down.id && it.isConsumed }) {
                // The bar has begun to scroll under it.
                kept = false
            }
        }
        kept
    }
    return outcome ?: true
}

/** A mouse picks up what it pressed as soon as it moves past the touch slop with the button down. */
private suspend fun AwaitPointerEventScope.awaitMouseMove(down: PointerInputChange): Boolean {
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: return false
        if (!change.pressed) return false
        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) return true
    }
}

private suspend fun AwaitPointerEventScope.consumeUntilUp(id: PointerId) {
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == id } ?: return
        change.consume()
        if (!change.pressed) return
    }
}

/** The ghost's `box-shadow: 0 4px 16px rgba(0,0,0,0.35)`. */
private val ghostShadow = ThemeShadow(0.0, 4.0, 16.0, 0.0, Rgba(0.0, 0.0, 0.0, 0.35))

/**
 * What follows the pointer. A finger gets the label (`showGhost`): the tab's title, white on
 * near-black, its middle on the finger and its bottom just above it (`translate(-50%, -130%)`).
 * A mouse gets the browser's drag image ([DragImage]).
 */
@Composable
fun BoxScope.TabDragGhost(drag: TabDrag) {
    val tab = drag.tab ?: return
    var origin by remember { mutableStateOf(Offset.Zero) }
    val shape = RoundedCornerShape(8.dp)
    val snapshot = tab.snapshot
    Box(Modifier.matchParentSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        if (drag.byMouse && snapshot != null) {
            DragImage(snapshot, drag.at - origin, tab.grab)
            return@Box
        }
        BasicText(
            tab.label,
            Modifier
                .offset { IntOffset((drag.at.x - origin.x).roundToInt(), (drag.at.y - origin.y).roundToInt()) }
                .layout { measurable, _ ->
                    val placeable = measurable.measure(Constraints())
                    layout(0, 0) { placeable.place(-placeable.width / 2, (-1.3f * placeable.height).roundToInt()) }
                }
                // `opacity: 0.95` without an offscreen layer, which would cut the shadow off at the label's edge.
                .graphicsLayer(alpha = 0.95f, compositingStrategy = CompositingStrategy.ModulateAlpha)
                .ppmShadow(ghostShadow, shape)
                .background(Color(30, 30, 30).copy(alpha = 0.92f), shape)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            style = ppmText(12f, lineHeight = 18f).copy(color = Color.White),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * A browser's drag image: what is dragged ([snapshot]) at 70 % — measured from Chrome on this
 * tablet —, held at [grab], where the pointer took it, under the pointer at [at].
 */
@Composable
internal fun DragImage(snapshot: GraphicsLayer, at: Offset, grab: Offset) {
    val size = with(LocalDensity.current) { DpSize(snapshot.size.width.toDp(), snapshot.size.height.toDp()) }
    Box(
        Modifier
            .offset { IntOffset((at.x - grab.x).roundToInt(), (at.y - grab.y).roundToInt()) }
            .size(size)
            .graphicsLayer(alpha = 0.7f)
            .drawBehind { drawLayer(snapshot) },
    )
}

/**
 * Android's drag pointer, the closed hand Chrome shows while a mouse drags the tab. Attached all
 * along: an icon that changes under the pointer shows at once, one attached under it waits for
 * the pointer to come in.
 */
fun Modifier.dragCursor(drag: TabDrag): Modifier = dragCursor(drag.tab != null && drag.byMouse)

/** The same over anything a mouse is [dragging]. */
fun Modifier.dragCursor(dragging: Boolean): Modifier =
    pointerHoverIcon(if (dragging) grabbing else PointerIcon.Default, overrideDescendants = dragging)

private val grabbing = PointerIcon(android.view.PointerIcon.TYPE_GRABBING)

/**
 * split-drop-overlay.tsx over a panel's content: the third a split would give the tab, washed in
 * the accent, or for a move the whole panel, dashed.
 */
@Composable
fun BoxScope.DropHighlight(zone: DropZone) {
    val tokens = LocalPpmTokens.current
    val line = tokens.accent.opacity(0.3).color
    val radius = 8.dp
    if (zone == DropZone.Center) {
        Box(
            Modifier
                .matchParentSize()
                .background(tokens.accent.opacity(0.05).color, RoundedCornerShape(radius))
                .dashedBorder(2.dp, line, radius),
        )
        return
    }
    val (place, shape) = when (zone) {
        DropZone.Left -> Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(1f / 3) to
            RoundedCornerShape(topStart = radius, bottomStart = radius)
        DropZone.Right -> Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(1f / 3) to
            RoundedCornerShape(topEnd = radius, bottomEnd = radius)
        DropZone.Top -> Modifier.align(Alignment.TopCenter).fillMaxWidth().fillMaxHeight(1f / 3) to
            RoundedCornerShape(topStart = radius, topEnd = radius)
        else -> Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(1f / 3) to
            RoundedCornerShape(bottomStart = radius, bottomEnd = radius)
    }
    Box(place.background(tokens.accent.opacity(0.1).color, shape).border(2.dp, line, shape))
}

/**
 * `border-dashed` as Chrome draws it at 3 device pixels or more: dashes two widths long with one
 * width between along each side, and one dash on the middle of each rounded corner.
 */
private fun Modifier.dashedBorder(width: Dp, color: Color, radius: Dp) = drawBehind {
    val w = width.toPx()
    val half = w / 2
    val r = radius.toPx() - half
    val left = half
    val top = half
    val right = size.width - half
    val bottom = size.height - half
    val arc = Size(2 * r, 2 * r)
    val stroke = Stroke(w)
    val sweep = Math.toDegrees((2 * w / r).toDouble()).toFloat()
    drawArc(color, 225f - sweep / 2, sweep, false, Offset(left, top), arc, style = stroke)
    drawArc(color, 315f - sweep / 2, sweep, false, Offset(right - 2 * r, top), arc, style = stroke)
    drawArc(color, 45f - sweep / 2, sweep, false, Offset(right - 2 * r, bottom - 2 * r), arc, style = stroke)
    drawArc(color, 135f - sweep / 2, sweep, false, Offset(left, bottom - 2 * r), arc, style = stroke)
    val dashes = PathEffect.dashPathEffect(floatArrayOf(2 * w, w))
    drawLine(color, Offset(left + r, top), Offset(right - r, top), w, pathEffect = dashes)
    drawLine(color, Offset(right, top + r), Offset(right, bottom - r), w, pathEffect = dashes)
    drawLine(color, Offset(right - r, bottom), Offset(left + r, bottom), w, pathEffect = dashes)
    drawLine(color, Offset(left, bottom - r), Offset(left, top + r), w, pathEffect = dashes)
}
