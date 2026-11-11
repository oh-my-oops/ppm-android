package com.hienlh.ppm.app.ui.windows

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.FocusAnchor
import com.hienlh.ppm.app.FocusStartAnchor
import com.hienlh.ppm.app.LocalRedesign
import com.hienlh.ppm.app.focusStartArea
import com.hienlh.ppm.app.ui.LocalDropLayer
import com.hienlh.ppm.app.ui.RefusingDrop
import com.hienlh.ppm.app.ui.SettingsWindowContent
import com.hienlh.ppm.app.ui.TabBody
import com.hienlh.ppm.app.ui.dropZone
import com.hienlh.ppm.app.ui.tablet.LocalHeldCursor
import com.hienlh.ppm.chat.AgentSessionContent
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ThemeShadow
import com.hienlh.ppm.design.WebCursor
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssBorderPx
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.AgentSessionPayload
import com.hienlh.ppm.state.windows.Bounds
import com.hienlh.ppm.state.windows.ResizeHandle
import com.hienlh.ppm.state.windows.WindowKind
import com.hienlh.ppm.state.windows.WindowRuntimeState
import com.hienlh.ppm.state.windows.WindowStore
import com.hienlh.ppm.state.windows.WindowVisualState
import com.hienlh.ppm.state.windows.WindowsState
import com.hienlh.ppm.state.windows.applyResize
import com.hienlh.ppm.state.windows.clampRect
import com.hienlh.ppm.state.windows.displayedRect
import com.hienlh.ppm.state.windows.frontWindowId
import com.hienlh.ppm.state.windows.nudgeRect
import com.hienlh.ppm.state.windows.snapRect
import com.hienlh.ppm.state.windows.wantsSnap
import com.hienlh.ppm.state.windows.windowsInRankOrder
import com.hienlh.ppm.state.workspace.closesItsTabs
import kotlin.math.abs
import kotlin.math.floor
import com.hienlh.ppm.state.windows.Rect as WindowRect

/**
 * window-layer.tsx: the floating windows over the working area, on a tablet only. It covers the
 * panels and stops at the status bar, so a maximized window never hides the dock that brings the
 * others back; windows are clamped against it and clipped to it.
 */
@Composable
fun WindowLayer(model: AppModel) {
    val store = model.windows ?: return
    WindowLayer(model, store, model.windowState)
}

/** [WindowLayer] over the store and the state it draws, and what fills each window ([content]), which is how a test gives it windows. */
@Composable
internal fun WindowLayer(
    model: AppModel,
    store: WindowStore,
    state: WindowsState,
    content: @Composable (WindowRuntimeState) -> Unit = { WindowContent(model, it) },
) {
    // The keyboard does not shrink it: the page pans instead, as Chrome's does (KeyboardPannedPage).
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val bounds = Bounds(cssRound(maxWidth.value.toDouble()), cssRound(maxHeight.value.toDouble()))
        // Measured before the saved windows come back, so they return clamped to the real size.
        LaunchedEffect(store, bounds) {
            store.setBounds(bounds)
            store.restoreAll(bounds)
        }
        if (state.snapPreviewId != null) SnapGhost(bounds)
        val frontId = frontWindowId(state.windows)
        // The drop layer of each is above the one behind it, as the window is drawn; the panels are layer 0.
        for ((index, win) in windowsInRankOrder(state.windows).withIndex()) {
            key(win.id) { FloatingWindow(model, store, win, bounds, focused = win.id == frontId, layer = index + 1, content = content) }
        }
    }
}

/** `Math.round`, which CSS pixels go through before a window is placed. */
private fun cssRound(value: Double): Double = floor(value + 0.5)

/** How far each resize strip reaches outside the frame: half of the web's 12 px touch strip. */
private val OVERHANG = 6.dp

/** use-gesture's `filterTaps`: a press turns into a drag once it has moved 3 px along either axis. */
private val DRAG_THRESHOLD = 3.dp

/** `shadow-2xl shadow-black/40`: the window in front. */
private val SHADOW_FRONT = ThemeShadow(0.0, 25.0, 50.0, -12.0, Rgba(0.0, 0.0, 0.0, 0.4))

/** `shadow-lg shadow-black/20`, two layers: any window behind it. */
private val SHADOW_BEHIND = ThemeShadow(0.0, 10.0, 15.0, -3.0, Rgba(0.0, 0.0, 0.0, 0.2))
private val SHADOW_BEHIND_NEAR = ThemeShadow(0.0, 4.0, 6.0, -4.0, Rgba(0.0, 0.0, 0.0, 0.2))

/**
 * floating-window.tsx: one window — its frame, the skinned titlebar, eight resize strips while it
 * floats, and the body. A gesture moves the window on screen only; the store is written once, as
 * the finger lifts. A minimized window stays composed, so its content keeps its state, but is
 * neither laid out nor drawn: the dock brings it back.
 */
@Composable
private fun FloatingWindow(
    model: AppModel,
    store: WindowStore,
    win: WindowRuntimeState,
    bounds: Bounds,
    focused: Boolean,
    layer: Int,
    content: @Composable (WindowRuntimeState) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val redesign = LocalRedesign.current
    val portraitFullPane = redesign && bounds.h > bounds.w && bounds.w > 0 && bounds.h > 0
    val target = if (portraitFullPane) WindowRect(0.0, 0.0, bounds.w, bounds.h) else displayedRect(win.state, win.rect, bounds)
    val minimized = win.state == WindowVisualState.Minimized
    val maximized = win.state == WindowVisualState.Maximized || portraitFullPane
    val snapped = win.state == WindowVisualState.Snapped && !portraitFullPane
    // Only a normal window is moved and resized by hand; the others are placed by the layer.
    val floating = win.state == WindowVisualState.Normal && !portraitFullPane

    // What is drawn: the store's rect eased into place (`transition-[transform,width,height]
    // duration-150`), or the live one while a finger holds the window.
    var shown by remember { mutableStateOf(target) }
    var gesturing by remember { mutableStateOf(false) }
    var hidden by remember { mutableStateOf(minimized) }
    LaunchedEffect(target, gesturing, minimized) {
        when {
            minimized -> {
                shown = target
                hidden = true
            }
            // Shown again: CSS starts no transition out of `hidden`.
            hidden -> {
                shown = target
                hidden = false
            }
            !gesturing && shown != target -> {
                val from = shown
                animate(0f, 1f, animationSpec = tween(150, easing = FastOutSlowInEasing)) { t, _ -> shown = lerp(from, target, t) }
            }
        }
    }
    // `@max-[560px]/window`: the subtitle gives way in a narrow window.
    val narrow by remember { derivedStateOf { shown.w - 2 <= 560 } }

    // A window closed mid-drag never sees its gesture end, so it takes its snap preview along.
    DisposableEffect(store, win.id) {
        onDispose { if (store.state.value.snapPreviewId == win.id) store.setSnapPreview(null) }
    }

    val close = { model.closeWindow(win.id) }
    val toggleMaximize = { store.setState(win.id, if (maximized) WindowVisualState.Normal else WindowVisualState.Maximized) }
    val toggleSnap = { store.setState(win.id, if (snapped) WindowVisualState.Normal else WindowVisualState.Snapped) }

    // The titlebar: a drag moves the window (past the right edge it snaps there), a double tap
    // maximizes or restores, and with the focus a hardware keyboard nudges, maximizes, closes.
    val titlebarFocus = remember { FocusRequester() }
    var lastTapUp by remember { mutableLongStateOf(Long.MIN_VALUE / 2) }
    val doubleTapTimeout = LocalViewConfiguration.current.doubleTapTimeoutMillis
    var dragBase by remember { mutableStateOf(target) }
    var armed by remember { mutableStateOf(false) }
    val titlebarModifier = Modifier
        .windowPointer(
            canDrag = floating,
            // A titlebar takes the focus as the web's `tabIndex=0` bar does, so its press is not a
            // tap on nothing, which would hand the focus straight back (Root.kt).
            takesPress = true,
            onPress = { titlebarFocus.requestFocus() },
            onTap = { down, up ->
                if (down - lastTapUp <= doubleTapTimeout) {
                    lastTapUp = Long.MIN_VALUE / 2
                    toggleMaximize()
                } else {
                    lastTapUp = up
                }
            },
            onDragStart = {
                dragBase = target
                armed = false
                gesturing = true
            },
            onDrag = { dx, dy ->
                val moved = dragBase.copy(x = dragBase.x + dx, y = dragBase.y + dy)
                val nowArmed = wantsSnap(moved, dragBase, bounds)
                if (nowArmed != armed) {
                    armed = nowArmed
                    store.setSnapPreview(if (nowArmed) win.id else null)
                }
                shown = clampRect(moved, bounds)
            },
            onDragEnd = {
                if (armed) {
                    store.setSnapPreview(null)
                    store.setState(win.id, WindowVisualState.Snapped)
                } else {
                    store.move(win.id, shown.x, shown.y)
                }
                gesturing = false
            },
        )
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            val step = if (event.isShiftPressed) 1.0 else 10.0
            val (dx, dy) = when (event.key) {
                Key.Escape -> {
                    // Escape is a canvas's "deselect": a window whose closing closes its content stays.
                    if (!closesItsTabs(win)) close()
                    return@onKeyEvent true
                }
                Key.Enter, Key.NumPadEnter -> {
                    if (!event.isAltPressed) return@onKeyEvent false
                    toggleMaximize()
                    return@onKeyEvent true
                }
                Key.DirectionLeft -> -step to 0.0
                Key.DirectionRight -> step to 0.0
                Key.DirectionUp -> 0.0 to -step
                Key.DirectionDown -> 0.0 to step
                else -> return@onKeyEvent false
            }
            // Nudging a maximized or snapped window would fight the layer.
            if (!floating) return@onKeyEvent false
            // From the store's rect, not the eased one: keys repeat faster than a nudge settles.
            val rect = store.state.value.windows[win.id]?.rect ?: return@onKeyEvent true
            val next = nudgeRect(rect, dx, dy, bounds)
            store.move(win.id, next.x, next.y)
            true
        }
        .focusRequester(titlebarFocus)
        .focusable()

    // Placed by the layer against its edges, a window has square corners, like a tiled one.
    val frameShape = if (maximized || snapped) RectangleShape else RoundedCornerShape(8.dp)
    Box(
        Modifier.layout { measurable, constraints ->
            if (minimized) return@layout layout(0, 0) {}
            val r = shown
            val pad = OVERHANG.roundToPx()
            val placeable = measurable.measure(
                androidx.compose.ui.unit.Constraints.fixed(cssRound(r.w).dp.roundToPx() + pad * 2, cssRound(r.h).dp.roundToPx() + pad * 2),
            )
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.place(cssRound(r.x).dp.roundToPx() - pad, cssRound(r.y).dp.roundToPx() - pad)
            }
        }
            // The whole of the window, its resize strips and its titlebar too, takes every drag and refuses every drop, so that what is under
            // a window is not reached through it. A zone of its content is inside this one, and so smaller: it has the drop where it is.
            .dropZone(shouldStartDragAndDrop = { true }, target = RefusingDrop, visible = !minimized, layer = layer),
    ) {
        val windowAnchor = remember { FocusAnchor() }
        Column(
            Modifier
                .padding(OVERHANG)
                .fillMaxSize()
                .focusStartArea(windowAnchor)
                .then(if (focused) Modifier.ppmShadow(SHADOW_FRONT, frameShape) else Modifier.ppmShadow(SHADOW_BEHIND_NEAR, frameShape).ppmShadow(SHADOW_BEHIND, frameShape))
                .background(tokens.panel.color, frameShape)
                // Any press inside raises the window, its content's included.
                .raiseOnPress(store, win.id)
                .cssBorder(tokens.border.color, frameShape),
        ) {
            FocusStartAnchor(windowAnchor)
            WindowTitlebar(
                windowsSkin = model.windowsSkin,
                win = win,
                meta = windowMeta(model, win),
                focused = focused,
                showSubtitle = !narrow,
                modifier = titlebarModifier,
                onMinimize = { store.setState(win.id, WindowVisualState.Minimized) },
                onToggleMaximize = toggleMaximize,
                onToggleSnap = toggleSnap,
                onClose = close,
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                    .background(tokens.panel.color),
            ) {
                androidx.compose.runtime.CompositionLocalProvider(com.hienlh.ppm.app.ui.LocalWindowContentVisible provides !minimized, LocalDropLayer provides layer) {
                    content(win)
                }
                if (floating && focused && redesign) {
                    val gripColor = tokens.text3.opacity(0.45).color
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .size(12.dp)
                            .drawBehind {
                                val stroke = 1.5.dp.toPx()
                                drawLine(
                                    color = gripColor,
                                    start = Offset(size.width * 0.35f, size.height),
                                    end = Offset(size.width, size.height * 0.35f),
                                    strokeWidth = stroke,
                                )
                                drawLine(
                                    color = gripColor,
                                    start = Offset(size.width * 0.7f, size.height),
                                    end = Offset(size.width, size.height * 0.7f),
                                    strokeWidth = stroke,
                                )
                            },
                    )
                }
            }
        }
        if (floating) {
            var resizeBase by remember { mutableStateOf(target) }
            val held = LocalHeldCursor.current
            for (handle in ResizeHandle.entries) {
                ResizeStrip(
                    handle,
                    Modifier.windowPointer(
                        canDrag = true,
                        onPress = { store.focus(win.id) },
                        onDragStart = {
                            resizeBase = target
                            gesturing = true
                            held.icon = handle.cursor
                        },
                        onDrag = { dx, dy -> shown = clampRect(applyResize(resizeBase, handle, dx, dy), bounds) },
                        onDragEnd = {
                            held.icon = null
                            store.resize(win.id, shown)
                            gesturing = false
                        },
                    ),
                )
            }
        }
    }
}

/** What fills a window's body, by its kind (window-content-registry.tsx). */
@Composable
private fun WindowContent(model: AppModel, win: WindowRuntimeState) {
    when (win.kind) {
        WindowKind.Explorer -> com.hienlh.ppm.app.ui.osexplorer.HostExplorerWindow(model, win)
        WindowKind.Settings -> SettingsWindowContent(model, win)
        WindowKind.SystemMonitor -> com.hienlh.ppm.app.ui.sysmon.SystemMonitorWindowContent(model, win)
        WindowKind.Logs -> com.hienlh.ppm.app.ui.logs.LogsWindowContent(model, win)
        WindowKind.RemoteDesktop -> com.hienlh.ppm.app.ui.remotedesktop.RemoteDesktopWindow(model, win.id)
        WindowKind.TabHost -> TabHostWindowContent(model, win)
        WindowKind.AgentSession -> {
            val payload = remember(win.payload) { AgentSessionPayload.from(win.payload) }
            AgentSessionContent(payload, model.events, payload?.let { model.agentFallbacks[it.key] })
        }
        else -> {
            val tokens = LocalPpmTokens.current
            BasicText("Not available in this app yet.", Modifier.padding(12.dp), style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color))
        }
    }
}

/**
 * tab-host-window-content.tsx: a detached tab, shown as its panel showed it. A window brought
 * back from last time can come before its tab does, and says "Loading…" until then — but not
 * once a tab was here: the one on its way back to the grid leaves a moment before the window.
 */
@Composable
private fun TabHostWindowContent(model: AppModel, win: WindowRuntimeState) {
    val tab = hostedTab(model, win.id)
    val seen = remember { BooleanArray(1) }
    if (tab != null) seen[0] = true
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (tab != null) {
            key(tab.id) { TabBody(model, tab) }
        } else if (!seen[0]) {
            val tokens = LocalPpmTokens.current
            BasicText("Loading…", style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color))
        }
    }
}

/**
 * window-resize-handles.tsx at touch size: a 12 px strip centred on each edge, stopping 20 px
 * short of the corners, and a 20 px square on each corner — invisible, so the window looks the
 * same. In the frame's box widened by [OVERHANG] on every side. Chrome on a touch tablet takes the
 * touch sizes for a mouse too (`pointer: coarse`), and draws each handle's `n-resize` … `sw-resize`
 * as one of four double arrows.
 */
@Composable
private fun BoxScope.ResizeStrip(handle: ResizeHandle, gesture: Modifier) {
    val edge = OVERHANG * 2
    val corner = 20.dp
    val along = corner + OVERHANG
    val placed = when (handle) {
        ResizeHandle.N -> Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = along).height(edge)
        ResizeHandle.S -> Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = along).height(edge)
        ResizeHandle.W -> Modifier.align(Alignment.CenterStart).fillMaxHeight().padding(vertical = along).width(edge)
        ResizeHandle.E -> Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = along).width(edge)
        ResizeHandle.NW -> Modifier.align(Alignment.TopStart).size(corner)
        ResizeHandle.NE -> Modifier.align(Alignment.TopEnd).size(corner)
        ResizeHandle.SW -> Modifier.align(Alignment.BottomStart).size(corner)
        ResizeHandle.SE -> Modifier.align(Alignment.BottomEnd).size(corner)
    }
    Box(placed.pointerHoverIcon(handle.cursor).then(gesture))
}

/** The double arrow over a handle, and over the whole screen while it is dragged. */
private val ResizeHandle.cursor: PointerIcon
    get() = when (this) {
        ResizeHandle.N, ResizeHandle.S -> WebCursor.ResizeVertical
        ResizeHandle.W, ResizeHandle.E -> WebCursor.ResizeHorizontal
        ResizeHandle.NW, ResizeHandle.SE -> WebCursor.ResizeTopLeft
        ResizeHandle.NE, ResizeHandle.SW -> WebCursor.ResizeTopRight
    }

/** Where a window dragged past the right edge lands when let go: `rounded border-2 border-dashed border-primary bg-accent-wash`. */
@Composable
private fun SnapGhost(bounds: Bounds) {
    val tokens = LocalPpmTokens.current
    val r = snapRect(bounds)
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .offset(r.x.dp, r.y.dp)
            .size(r.w.dp, r.h.dp)
            .background(tokens.accentWash.color, shape)
            .drawBehind {
                val width = cssBorderPx(2.dp).toFloat()
                // Chrome's dashes for a border this thick: twice as long as it is wide, a width apart.
                drawRoundRect(
                    tokens.accent.color,
                    topLeft = Offset(width / 2, width / 2),
                    size = Size(size.width - width, size.height - width),
                    cornerRadius = CornerRadius(4.dp.toPx() - width / 2),
                    style = Stroke(width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(width * 2, width))),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val pillShape = RoundedCornerShape(999.dp)
        Box(
            Modifier
                .clip(pillShape)
                .background(tokens.panel2.color)
                .cssBorder(tokens.accent.opacity(0.5).color, pillShape)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            BasicText(
                "Release to snap right",
                style = ppmText(12f, androidx.compose.ui.text.font.FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.accent.color),
            )
        }
    }
}

private fun lerp(from: WindowRect, to: WindowRect, t: Float): WindowRect = WindowRect(
    from.x + (to.x - from.x) * t,
    from.y + (to.y - from.y) * t,
    from.w + (to.w - from.w) * t,
    from.h + (to.h - from.h) * t,
)

/** Raises the window at any press inside it, before its content sees the press. */
private fun Modifier.raiseOnPress(store: WindowStore, id: String): Modifier = pointerInput(store, id) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        store.focus(id)
    }
}

/**
 * A press that becomes a drag past [DRAG_THRESHOLD], measured on the screen: the window moves
 * under the finger, and in the strip's own coordinates the finger would barely move at all. The
 * displacement handed to [onDrag] is in dp from where the press began, as the web's
 * `gestureDisplacement` takes it from the pointer's start, so it never drifts. A press released
 * before it moves far enough is a tap: [onTap] gets when it went down and came up. With
 * [takesPress] nothing behind sees the press even when it stays a tap.
 */
private fun Modifier.windowPointer(
    canDrag: Boolean,
    takesPress: Boolean = false,
    onPress: () -> Unit = {},
    onTap: (downMillis: Long, upMillis: Long) -> Unit = { _, _ -> },
    onDragStart: () -> Unit,
    onDrag: (dx: Double, dy: Double) -> Unit,
    onDragEnd: () -> Unit,
): Modifier = composed {
    val press by rememberUpdatedState(onPress)
    val tap by rememberUpdatedState(onTap)
    val start by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    val placed = remember { PlacedAt() }
    onGloballyPositioned { placed.coordinates = it }.pointerInput(canDrag, takesPress) {
        val threshold = DRAG_THRESHOLD.toPx()
        awaitEachGesture {
            val down = awaitFirstDown()
            val origin = placed.coordinates?.localToRoot(down.position) ?: return@awaitEachGesture
            if (takesPress) down.consume()
            press()
            var dragging = false
            try {
                // Every event of this finger up to and including its release, which can carry the last stretch of the move.
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    val moved = (placed.coordinates?.localToRoot(change.position) ?: break) - origin
                    if (!dragging && canDrag && (abs(moved.x) >= threshold || abs(moved.y) >= threshold)) {
                        dragging = true
                        start()
                    }
                    if (dragging || takesPress) change.consume()
                    if (dragging) drag((moved.x / density).toDouble(), (moved.y / density).toDouble())
                    if (!change.pressed) {
                        if (!dragging) tap(down.uptimeMillis, change.uptimeMillis)
                        break
                    }
                }
            } finally {
                if (dragging) end()
            }
        }
    }
}

/** Where a strip was last laid out; read by its gesture, never by composition. */
private class PlacedAt {
    var coordinates: LayoutCoordinates? = null
}
