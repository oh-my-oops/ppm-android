package com.hienlh.ppm.app.ui.windows

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.PlacedPopup
import com.hienlh.ppm.app.ui.PpmTextField
import com.hienlh.ppm.app.ui.contextMenuGesture
import com.hienlh.ppm.app.ui.tablet.Edge
import com.hienlh.ppm.app.ui.tablet.edge
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ThemeShadow
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssScrollY
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.windows.DockMode
import com.hienlh.ppm.state.windows.WindowKind
import com.hienlh.ppm.state.windows.WindowRuntimeState
import com.hienlh.ppm.state.windows.WindowStore
import com.hienlh.ppm.state.windows.WindowVisualState
import com.hienlh.ppm.state.windows.frontWindowId
import com.hienlh.ppm.state.windows.planDock
import com.hienlh.ppm.state.windows.windowsInOpenOrder
import kotlin.math.max
import kotlin.math.min

/** Past this many chips the row stops being scannable; the rest goes into `+N`. */
private const val MAX_CHIPS = 8
private const val CHIP_GAP = 2.0

/** A chip without its title: the 16 px tile and 5 px either side. */
private const val ICON_CHIP_WIDTH = 26.0

/** `max-w-[168px]`. */
private const val CHIP_MAX_WIDTH = 168.0

/** A chip around its title: `pl-1`, the tile, `gap-1.5`, then `pr-2`. */
private const val CHIP_TITLE_ROOM = 4.0 + 16.0 + 6.0 + 8.0

/** `+N` around its count: the border, `pl-2`, `gap-0.5`, the chevron, `pr-1.5`, the border. */
private const val MORE_ROOM = 1.0 + 8.0 + 2.0 + 12.0 + 6.0 + 1.0

private val CHIP_SHAPE = RoundedCornerShape(8.dp)
private val CHIP_TEXT = ppmText(11.5f, FontWeight.Medium, lineHeight = 11.5f)
private val MORE_TEXT = ppmText(11f, FontWeight.SemiBold, lineHeight = 11f)

/** `transition-colors` and its kin: Tailwind's 150 ms ease. */
private val EASE = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

/** `shadow-lg`, two layers. */
private val SHADOW_LG = listOf(
    ThemeShadow(0.0, 10.0, 15.0, -3.0, Rgba(0.0, 0.0, 0.0, 0.1)),
    ThemeShadow(0.0, 4.0, 6.0, -4.0, Rgba(0.0, 0.0, 0.0, 0.1)),
)

private enum class ChipState { Front, Behind, Min }

/**
 * window-dock.tsx: the status bar's window dock, one chip per floating window in the order they
 * were opened, like a taskbar. In front: tinted, with a long accent bar; open behind: a short
 * bar; minimized: no bar and a dimmed tile; a pulsing dot while an AI turn runs in the window.
 *
 * Tapping the chip in front minimizes its window; any other brings its window forward, from the
 * dock if need be. Squeezed, chips give up their titles and then fold into `+N` ([planDock]);
 * `+N`, or a finger held anywhere on the dock (a right click with a mouse), lists every window.
 * The caller leaves it out while there is no window, as the web's renders nothing.
 */
@Composable
internal fun WindowDock(model: AppModel, modifier: Modifier = Modifier) {
    val store = model.windows ?: return
    val windows = if (model.layout != null) {
        model.windowState.windows.filterValues { it.kind != WindowKind.Explorer }
    } else {
        model.windowState.windows
    }
    val list = windowsInOpenOrder(windows)
    if (list.isEmpty()) return
    val tokens = LocalPpmTokens.current
    val frontId = frontWindowId(windows)
    val frontIndex = list.indexOfFirst { it.id == frontId }
    val metas = list.map { windowMeta(model, it) }
    val titles = list.mapIndexed { i, win -> metas[i].title?.takeIf { it.isNotEmpty() } ?: windowTitle(win.kind, win.payload) }
    var listOpen by remember { mutableStateOf(false) }
    val toggle = remember { MoreToggle() }

    // What `planDock` works from, which the web reads off a hidden row of full-size chips: each
    // chip with its title, and `+N` counting every window, the widest it can get.
    val measurer = rememberTextMeasurer()
    val fullWidths = titles.map { min(CHIP_MAX_WIDTH, CHIP_TITLE_ROOM + measurer.width(it, CHIP_TEXT)) }
    // +2: the button's own left margin.
    val moreWidth = MORE_ROOM + measurer.width("+${list.size}", MORE_TEXT) + 2

    BoxWithConstraints(
        modifier
            .fillMaxHeight()
            .edge(Edge.Left, tokens.borderSoft.color)
            .contextMenuGesture { _ -> listOpen = true },
    ) {
        val layout = planDock(
            // Inside the border and past `pl-2.5`.
            available = maxWidth.value - 10.0,
            fullWidths = fullWidths,
            iconWidth = ICON_CHIP_WIDTH,
            gap = CHIP_GAP,
            moreWidth = moreWidth,
            frontIndex = frontIndex,
            maxChips = MAX_CHIPS,
        )
        val hidden = layout.hidden.toSet()
        Row(
            Modifier.fillMaxSize().clipToBounds().padding(start = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CHIP_GAP.dp),
        ) {
            list.forEachIndexed { i, win ->
                if (i in hidden) return@forEachIndexed
                val state = when {
                    win.id == frontId -> ChipState.Front
                    win.state == WindowVisualState.Minimized -> ChipState.Min
                    else -> ChipState.Behind
                }
                key(win.id) {
                    DockChip(
                        meta = metas[i],
                        title = titles[i],
                        state = state,
                        showTitle = layout.mode == DockMode.Full || (layout.mode == DockMode.Compact && state == ChipState.Front),
                        busy = metas[i].busy,
                        // The front chip is the one that gives way first, so it is never folded away.
                        modifier = if (state == ChipState.Front) Modifier.weight(1f, fill = false) else Modifier,
                        onClick = { store.activateFromDock(win.id) },
                    )
                }
            }
            if (hidden.isNotEmpty()) {
                MoreButton(
                    count = hidden.size,
                    busy = list.indices.any { it in hidden && metas[it].busy },
                    expanded = listOpen,
                    modifier = Modifier.trackPress(toggle),
                    onClick = { if (toggle.closedAt < toggle.pressedAt) listOpen = !listOpen },
                )
            }
        }
        if (listOpen) {
            WindowDockList(model, store, list, frontId, onDismiss = {
                toggle.closedAt = SystemClock.uptimeMillis()
                listOpen = false
            })
        }
    }
}

/**
 * `+N` toggles the list, but a press on it while the list is open is also a press outside the
 * list, which closes it first; the web keeps that one from closing it (`onPointerDownOutside`).
 * Here `+N`'s click asks whether the list closed after its own press began, which holds in
 * whichever order the two arrive. A click with no press (a keyboard, TalkBack) just toggles.
 */
private class MoreToggle {
    /** Uptime (ms) the list last closed. */
    var closedAt = 0L

    /** Uptime (ms) the press now on `+N` began, or none. */
    var pressedAt = Long.MAX_VALUE
}

/** Keeps [MoreToggle.pressedAt] while a press is on, past the click its release makes. */
private fun Modifier.trackPress(toggle: MoreToggle): Modifier = pointerInput(toggle) {
    awaitEachGesture {
        toggle.pressedAt = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).uptimeMillis
        try {
            // The click comes in the release's Main pass; this sees the release after it.
            do {
                val event = awaitPointerEvent(PointerEventPass.Final)
            } while (event.changes.any { it.pressed })
        } finally {
            toggle.pressedAt = Long.MAX_VALUE
        }
    }
}

/** The width [text] takes in [style], in dp, unrounded as Chrome keeps it. */
private fun TextMeasurer.width(text: String, style: TextStyle): Double {
    val layout = measure(text, style, maxLines = 1, softWrap = false)
    return with(layout.layoutInput.density) { (layout.getLineRight(0) - layout.getLineLeft(0)).toDp().value.toDouble() }
}

@Composable
private fun DockChip(
    meta: WindowMeta,
    title: String,
    state: ChipState,
    showTitle: Boolean,
    busy: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val front = state == ChipState.Front
    val label = title + (if (state == ChipState.Min) " (minimized)" else "") + (if (busy) " · working" else "")
    val background by animateColorAsState(if (front) tokens.accentWash.color else Color.Transparent, tween(150, easing = EASE), label = "chip")
    val textColor = when (state) {
        ChipState.Front -> tokens.text
        ChipState.Behind -> tokens.text2
        ChipState.Min -> tokens.text3
    }
    val color by animateColorAsState(textColor.color, tween(150, easing = EASE), label = "chipText")
    Box(
        modifier
            .height(22.dp)
            .widthIn(min = if (front) ICON_CHIP_WIDTH.dp else 0.dp, max = CHIP_MAX_WIDTH.dp)
            .background(background, CHIP_SHAPE)
            .semantics { contentDescription = label }
            // A touch screen has no hover, and the web's chip no pressed look.
            .ppmClickable(pressedColor = Color.Transparent, onClickLabel = label, onClick = onClick),
    ) {
        Row(
            Modifier.fillMaxHeight().padding(start = if (showTitle) 4.dp else 5.dp, end = if (showTitle) 8.dp else 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            WindowTile(meta.icon, meta.tone, TileSize.Small, filled = meta.filled, muted = state == ChipState.Min) {
                if (busy) WindowBusyDot(Modifier.align(Alignment.TopEnd).offset(3.dp, (-3).dp))
            }
            if (showTitle) {
                BasicText(
                    title,
                    style = CHIP_TEXT.copy(
                        color = color,
                        fontStyle = if (state == ChipState.Min) FontStyle.Italic else FontStyle.Normal,
                    ),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (state != ChipState.Min) {
            val width by animateDpAsState(if (front) 18.dp else 6.dp, tween(150, easing = EASE), label = "bar")
            val barColor by animateColorAsState((if (front) tokens.accent else tokens.text3).color, tween(150, easing = EASE), label = "barColor")
            Box(Modifier.align(Alignment.BottomCenter).size(width, 2.dp).background(barColor, CircleShape))
        }
    }
}

@Composable
private fun MoreButton(count: Int, busy: Boolean, expanded: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val label = "$count more window${if (count == 1) "" else "s"}"
    val border by animateColorAsState((if (expanded) tokens.accentWashBorder else tokens.borderSoft).color, tween(150, easing = EASE), label = "moreBorder")
    val background by animateColorAsState((if (expanded) tokens.accentWash else tokens.panel2).color, tween(150, easing = EASE), label = "more")
    val color by animateColorAsState((if (expanded) tokens.text else tokens.text2).color, tween(150, easing = EASE), label = "moreText")
    val turn by animateFloatAsState(if (expanded) 180f else 0f, tween(150, easing = EASE), label = "chevron")
    Box(Modifier.padding(start = 2.dp)) {
        Row(
            modifier
                .height(22.dp)
                .background(background, CHIP_SHAPE)
                .ppmClickable(pressedColor = Color.Transparent, onClickLabel = label, onClick = onClick)
                .cssBorder(border, CHIP_SHAPE)
                .padding(start = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            BasicText("+$count", style = MORE_TEXT.copy(color = color), maxLines = 1)
            PpmIcon(ProductIcon.ChevronUp, 12.dp, color, Modifier.rotate(turn))
        }
        // `-right-0.5 -top-0.5` from inside the border: a pixel past the chip's edge.
        if (busy) WindowBusyDot(Modifier.align(Alignment.TopEnd).offset(1.dp, (-1).dp))
    }
}

/**
 * window-dock-list.tsx: every window in one list over the dock's right end — search, the open
 * ones and the minimized ones in two groups, a close button per row (always shown: a touch
 * screen has no hover to reveal it), and "Minimize all". A press anywhere else closes it and
 * still does what it would have: a chip pressed past it comes forward.
 */
@Composable
private fun WindowDockList(
    model: AppModel,
    store: WindowStore,
    windows: List<WindowRuntimeState>,
    frontId: String?,
    onDismiss: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val density = LocalDensity.current
    val windowSize = with(density) {
        val size = LocalWindowInfo.current.containerSize
        Size(size.width.toDp().value, size.height.toDp().value)
    }
    // `side="top" align="end" sideOffset={6} collisionPadding={8}`.
    val gap = with(density) { 6.dp.roundToPx() }
    val pad = with(density) { 8.dp.roundToPx() }
    val place = remember(gap, pad) {
        { anchor: IntRect, window: IntSize, box: IntSize ->
            val x = (anchor.right - box.width).coerceIn(pad, max(pad, window.width - pad - box.width))
            IntOffset(x, max(pad, anchor.top - gap - box.height))
        }
    }
    val query = rememberTextFieldState()
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val shown = windows.filter { it.state != WindowVisualState.Minimized }
    val minimized = windows.filter { it.state == WindowVisualState.Minimized }
    val metas = (shown + minimized).associate { it.id to windowMeta(model, it) }
    fun titleOf(win: WindowRuntimeState) = metas[win.id]?.title?.takeIf { it.isNotEmpty() } ?: windowTitle(win.kind, win.payload)
    val q = query.text.toString().trim().lowercase()
    val matching = (shown + minimized).filter { q.isEmpty() || "${titleOf(it)} ${metas[it.id]?.kindLabel}".lowercase().contains(q) }.map { it.id }.toSet()
    val shape = RoundedCornerShape(12.dp)

    PlacedPopup(place, onDismiss, passThrough = true) {
        Column(
            Modifier
                .width(min(380.0, windowSize.width - 24.0).dp)
                .heightIn(max = min(440.0, windowSize.height * 0.7).dp)
                .let { SHADOW_LG.fold(it) { m, s -> m.ppmShadow(s, shape) } }
                .clip(shape)
                .background(tokens.panel2.color)
                .cssBorder(tokens.border.color, shape),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicText("Windows", style = ppmText(13f, FontWeight.SemiBold, lineHeight = 19.5f).copy(color = tokens.text.color))
                BasicText("${windows.size}", style = ppmText(11f, kind = FontKind.MONO, lineHeight = 16.5f).copy(color = tokens.text3.color))
                Spacer(Modifier.weight(1f))
                BasicText(
                    "Minimize all",
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .ppmClickable(pressedColor = Color.Transparent) {
                            store.minimizeAll()
                            onDismiss()
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.accent.color),
                )
            }
            val fieldShape = RoundedCornerShape(12.dp)
            Row(
                Modifier
                    .padding(start = 10.dp, end = 10.dp, bottom = 6.dp)
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(tokens.panel.color, fieldShape)
                    .cssBorder((if (focused) tokens.accentWashBorder else tokens.borderSoft).color, fieldShape)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PpmIcon(ProductIcon.Search, 14.dp, tokens.text3.color)
                PpmTextField(
                    query,
                    "Find a window…",
                    Modifier.weight(1f).onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            onDismiss()
                            true
                        } else {
                            false
                        }
                    },
                    style = ppmText(12.5f, lineHeight = 18.75f),
                    focusRequester = focus,
                    onFocusChanged = { focused = it },
                )
            }
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .cssScrollY()
                    .padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 8.dp),
            ) {
                for ((label, group) in listOf("Open" to shown, "Minimized" to minimized)) {
                    val rows = group.filter { it.id in matching }
                    if (rows.isEmpty()) continue
                    BasicText(
                        "$label · ${rows.size}".uppercase(),
                        Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                        style = ppmText(10.5f, FontWeight.SemiBold, lineHeight = 15.75f).copy(color = tokens.text3.color, letterSpacing = 0.06.em),
                    )
                    for (win in rows) {
                        key(win.id) {
                            val front = win.id == frontId
                            WindowRow(
                                meta = metas.getValue(win.id),
                                title = titleOf(win),
                                front = front,
                                minimized = win.state == WindowVisualState.Minimized,
                                onPick = {
                                    if (!front) store.focus(win.id)
                                    onDismiss()
                                },
                                onClose = { model.closeWindow(win.id) },
                            )
                        }
                    }
                }
                if (matching.isEmpty()) {
                    BasicText(
                        "No window matches",
                        Modifier.fillMaxWidth().padding(16.dp),
                        style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color, textAlign = TextAlign.Center),
                    )
                }
            }
        }
    }
}

@Composable
private fun WindowRow(meta: WindowMeta, title: String, front: Boolean, minimized: Boolean, onPick: () -> Unit, onClose: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val sub = when {
        meta.busy -> "${meta.kindLabel} · working"
        front -> "${meta.kindLabel} · in front"
        else -> meta.kindLabel
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .background(if (front) tokens.accentWash.color else Color.Transparent, RoundedCornerShape(12.dp))
            .drawBehind {
                if (!front) return@drawBehind
                // `absolute bottom-2 left-0 top-2 w-[3px] rounded-r-[3px] bg-primary`.
                val w = 3.dp.toPx()
                val inset = 8.dp.toPx()
                clipRect(right = w) {
                    drawRoundRect(tokens.accent.color, Offset(-w, inset), Size(2 * w, size.height - 2 * inset), CornerRadius(w))
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .ppmClickable(pressedColor = Color.Transparent, onClick = onPick)
                .padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            WindowTile(meta.icon, meta.tone, TileSize.Large, filled = meta.filled, muted = minimized)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BasicText(
                    title,
                    style = ppmText(12.5f, FontWeight.Medium, lineHeight = 18.75f).copy(color = tokens.text.color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                BasicText(
                    sub,
                    style = ppmText(11f, lineHeight = 16.5f).copy(color = (if (meta.busy) tokens.success else tokens.text3).color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            Modifier
                .padding(end = 6.dp)
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .ppmClickable(pressedColor = Color.Transparent, onClickLabel = "Close $title", onClick = onClose),
            contentAlignment = Alignment.Center,
        ) { PpmIcon(ProductIcon.X, 14.dp, tokens.text3.color) }
    }
}
