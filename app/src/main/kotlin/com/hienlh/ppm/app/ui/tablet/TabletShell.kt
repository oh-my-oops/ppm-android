package com.hienlh.ppm.app.ui.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.Backdrop
import com.hienlh.ppm.app.ui.CornerChrome
import com.hienlh.ppm.app.ui.KeyboardPannedPage
import com.hienlh.ppm.app.ui.windows.WindowLayer
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.WebCursor
import com.hienlh.ppm.design.colorsFade
import com.hienlh.ppm.design.cssBorder

/**
 * The desktop layout (app.tsx from `md` up): the sidebar, then the working area — the panel grid
 * with the dock, the floating windows over it — over the status bar, which spans the working area only.
 * The keyboard pans it all up as Chrome pans its view over the page ([KeyboardPannedPage]): under
 * a message box the status bar sits on the keyboard.
 */
@Composable
fun TabletShell(model: AppModel) {
    val drag = remember { TabDrag() }
    val held = remember { HeldCursor() }
    CompositionLocalProvider(LocalTabDrag provides drag, LocalHeldCursor provides held) {
        Backdrop(Modifier.dragCursor(drag, held)) {
            KeyboardPannedPage {
                // Clear of the system bars, not of the keyboard: that one pans the page.
                Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
                    Sidebar(model)
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            PanelArea(model)
                            WindowLayer(model)
                        }
                        StatusBar(model)
                    }
                }
                CornerChrome(model, mobile = false)
            }
            TabDragGhost(drag)
        }
    }
}

enum class Edge { Top, Bottom, Left, Right }

/** A CSS 1 px border on one [edge] ([cssBorder]): drawn inside the box, the content moved off it (`border-box`). */
fun Modifier.edge(edge: Edge, color: Color): Modifier = cssBorder(
    color,
    sides = when (edge) {
        Edge.Top -> CssSides.Top
        Edge.Bottom -> CssSides.Bottom
        Edge.Left -> CssSides.Start
        Edge.Right -> CssSides.End
    },
)

/**
 * The 1 px line between two resizable panes (panel-layout.tsx's `ResizeHandle`): `bg-border/30`,
 * the accent at 30% under a mouse and at 50% while held, a grip in its middle (always drawn: a
 * touch screen has no hover). It takes one pixel of the layout but a 20 dp strip of touch and
 * mouse, laid over both neighbours: react-resizable-panels widens a separator to
 * `resizeTargetMinimumSize.coarse`, as Chrome on Android reports a coarse pointer, and shows the
 * double arrow (`ew-resize` / `ns-resize`) over all of it. Only the pixel itself lights, as CSS's
 * `:hover` covers the line and not the grab area around it. [vertical] is a line between
 * side-by-side panes. [onDragStart] comes at the press, and [onDrag] gets how far the pointer has
 * moved since, in pixels: react-resizable-panels moves the layout the drag began with by the whole
 * offset, so a line held at its limit while the pointer runs on comes back under the pointer.
 */
@Composable
fun SplitHandle(vertical: Boolean, onDragStart: () -> Unit = {}, onDrag: (Float) -> Unit, onDragEnd: () -> Unit = {}) {
    val tokens = LocalPpmTokens.current
    var held by remember { mutableStateOf(false) }
    val moved = remember { FloatArray(1) }
    var onLine by remember { mutableStateOf(false) }
    // `transition-colors duration-150` between the three.
    val lit = colorsFade(onLine || held).toDouble()
    val press = colorsFade(held).toDouble()
    val line = tokens.accent.opacity(0.5).mix(tokens.accent.opacity(0.3).mix(tokens.border.opacity(0.3), lit), press)
    val cursor = if (vertical) WebCursor.ResizeHorizontal else WebCursor.ResizeVertical
    Box(
        (if (vertical) Modifier.width(1.dp).fillMaxHeight() else Modifier.height(1.dp).fillMaxWidth())
            .zIndex(1f)
            .background(line.color),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            (if (vertical) Modifier.fillMaxHeight().requiredWidth(20.dp) else Modifier.fillMaxWidth().requiredHeight(20.dp))
                .pointerHoverIcon(cursor)
                .pointerInput(vertical) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull() ?: continue
                            // A mouse moving with no button down; a finger never hovers.
                            if (change.pressed) continue
                            val at = if (vertical) change.position.x else change.position.y
                            val middle = (if (vertical) size.width else size.height) / 2f
                            val half = 0.5.dp.toPx()
                            onLine = event.type != PointerEventType.Exit && at >= middle - half && at < middle + half
                        }
                    }
                }
                .screenDrag(
                    horizontal = vertical,
                    cursor = cursor,
                    onStart = {
                        held = true
                        moved[0] = 0f
                        onDragStart()
                    },
                    onDrag = { delta ->
                        moved[0] += delta
                        onDrag(moved[0])
                    },
                    onEnd = {
                        held = false
                        onDragEnd()
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            PpmIcon(if (vertical) ProductIcon.GripVertical else ProductIcon.GripHorizontal, 12.dp, tokens.text.opacity(0.5).color)
        }
    }
}

/**
 * The cursor a drag keeps wherever the pointer goes. The web's handles keep theirs past their own
 * edge — react-resizable-panels sets its cursor on `*` with `!important`, the sidebar sets it on
 * the page and holds the pointer, and a window's edge keeps it too — while a Compose icon shows
 * only over its own box, so the pointer turned back into the arrow once a drag outran its handle.
 * The shell lays this one over everything.
 */
@Stable
class HeldCursor {
    var icon by mutableStateOf<PointerIcon?>(null)
}

val LocalHeldCursor = staticCompositionLocalOf { HeldCursor() }

/**
 * A drag along one axis, measured on the screen. A handle moves with the edge it drags, and
 * `draggable` measures in the handle's own coordinates, so it saw the finger move only about half
 * as far (a 100 dp swipe moved the dock 52 dp). As on the web, the edge follows from the first
 * pixel, with no touch slop. [horizontal] drags along x; [onDrag] gets each move in pixels;
 * [cursor] is held until the drag ends.
 */
fun Modifier.screenDrag(
    horizontal: Boolean,
    cursor: PointerIcon,
    onStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onEnd: () -> Unit,
): Modifier = composed {
    val start by rememberUpdatedState(onStart)
    val move by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    val icon by rememberUpdatedState(cursor)
    val held = LocalHeldCursor.current
    val placed = remember { PlacedAt() }
    onGloballyPositioned { placed.coordinates = it }.pointerInput(horizontal) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var last = placed.coordinates?.localToRoot(down.position) ?: return@awaitEachGesture
            down.consume()
            start()
            held.icon = icon
            try {
                // Every event of this finger up to and including its release, which can carry the
                // last stretch of the move: `drag()` drops it (a 100 dp drag came out 95 dp).
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    placed.coordinates?.localToRoot(change.position)?.let { now ->
                        val delta = if (horizontal) now.x - last.x else now.y - last.y
                        last = now
                        if (delta != 0f) move(delta)
                    }
                    change.consume()
                    if (!change.pressed) break
                }
            } finally {
                held.icon = null
                end()
            }
        }
    }
}

/** Where a handle was last laid out; read by its gesture, never by composition. */
private class PlacedAt {
    var coordinates: LayoutCoordinates? = null
}

