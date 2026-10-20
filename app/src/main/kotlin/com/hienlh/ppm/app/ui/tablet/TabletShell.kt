package com.hienlh.ppm.app.ui.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.Backdrop
import com.hienlh.ppm.app.ui.CornerChrome
import com.hienlh.ppm.app.ui.windows.WindowLayer
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon

/**
 * The desktop layout (app.tsx from `md` up): the sidebar, then the working area — the panel grid
 * with the dock, the floating windows over it — over the status bar, which spans the working area only.
 */
@Composable
fun TabletShell(model: AppModel) {
    val drag = remember { TabDrag() }
    CompositionLocalProvider(LocalTabDrag provides drag) {
        Backdrop(Modifier.dragCursor(drag)) {
            Row(Modifier.fillMaxSize().safeDrawingPadding()) {
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
            TabDragGhost(drag)
        }
    }
}

enum class Edge { Top, Bottom, Left, Right }

/** A CSS 1 px border on one [edge]: drawn inside the box, the content moved off it (`border-box`). */
fun Modifier.edge(edge: Edge, color: Color): Modifier = drawBehind {
    val px = 1.dp.toPx()
    when (edge) {
        Edge.Top -> drawRect(color, Offset.Zero, Size(size.width, px))
        Edge.Bottom -> drawRect(color, Offset(0f, size.height - px), Size(size.width, px))
        Edge.Left -> drawRect(color, Offset.Zero, Size(px, size.height))
        Edge.Right -> drawRect(color, Offset(size.width - px, 0f), Size(px, size.height))
    }
}.padding(
    top = if (edge == Edge.Top) 1.dp else 0.dp,
    bottom = if (edge == Edge.Bottom) 1.dp else 0.dp,
    start = if (edge == Edge.Left) 1.dp else 0.dp,
    end = if (edge == Edge.Right) 1.dp else 0.dp,
)

/**
 * The 1 px line between two resizable panes (panel-layout.tsx's `ResizeHandle`): `bg-border/30`,
 * the accent while held, a grip in its middle (always drawn: a touch screen has no hover). It
 * takes one pixel of the layout but a 16 dp strip of touch, laid over both neighbours.
 * [vertical] is a line between side-by-side panes; [onDrag] gets each move in pixels.
 */
@Composable
fun SplitHandle(vertical: Boolean, onDrag: (Float) -> Unit, onDragEnd: () -> Unit = {}) {
    val tokens = LocalPpmTokens.current
    var held by remember { mutableStateOf(false) }
    val line = if (held) tokens.accent.opacity(0.5) else tokens.border.opacity(0.3)
    Box(
        (if (vertical) Modifier.width(1.dp).fillMaxHeight() else Modifier.height(1.dp).fillMaxWidth())
            .zIndex(1f)
            .background(line.color),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            (if (vertical) Modifier.fillMaxHeight().requiredWidth(16.dp) else Modifier.fillMaxWidth().requiredHeight(16.dp))
                .screenDrag(
                    horizontal = vertical,
                    onStart = { held = true },
                    onDrag = onDrag,
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
 * A drag along one axis, measured on the screen. A handle moves with the edge it drags, and
 * `draggable` measures in the handle's own coordinates, so it saw the finger move only about half
 * as far (a 100 dp swipe moved the dock 52 dp). As on the web, the edge follows from the first
 * pixel, with no touch slop. [horizontal] drags along x; [onDrag] gets each move in pixels.
 */
fun Modifier.screenDrag(horizontal: Boolean, onStart: () -> Unit, onDrag: (Float) -> Unit, onEnd: () -> Unit): Modifier = composed {
    val start by rememberUpdatedState(onStart)
    val move by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    val placed = remember { PlacedAt() }
    onGloballyPositioned { placed.coordinates = it }.pointerInput(horizontal) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var last = placed.coordinates?.localToRoot(down.position) ?: return@awaitEachGesture
            down.consume()
            start()
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
                end()
            }
        }
    }
}

/** Where a handle was last laid out; read by its gesture, never by composition. */
private class PlacedAt {
    var coordinates: LayoutCoordinates? = null
}

