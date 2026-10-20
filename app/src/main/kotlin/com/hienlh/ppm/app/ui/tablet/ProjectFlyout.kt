package com.hienlh.ppm.app.ui.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.PROJECT_SORTS
import com.hienlh.ppm.app.ui.PpmTextField
import com.hienlh.ppm.app.ui.unreadSessions
import com.hienlh.ppm.chat.RelativeTime
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.ProjectInfo
import com.hienlh.ppm.protocol.SessionUnread
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.roundToInt

/** `w-[340px] max-h-[680px]`, 4 px under the button. */
private val FLYOUT_WIDTH = 340.dp
private val FLYOUT_MAX_HEIGHT = 680.dp
private val FLYOUT_GAP = 4.dp

/** How near the list's top or bottom a dragged row scrolls it, and how far a frame at the very edge. */
private val AUTOSCROLL_BAND = 24.dp
private val AUTOSCROLL_STEP = 10.dp

/**
 * The project switcher's flyout (project-switcher.tsx): under the button, a search that takes the
 * keyboard, the order (Recent, Priority, Name), then each project with its path and when it was
 * last opened. ↑ ↓ move the highlight and Enter opens that project, as a tap on one does; Esc,
 * Back or a tap anywhere else closes it. In the Priority order with nothing searched, a row
 * dragged onto another takes its place — a finger rests on it first, a mouse just moves — and
 * the server keeps the new order. [anchor] is the button on the screen; [limit], how far down the
 * screen the shell reaches, which the list stops at rather than going under the keyboard. Adding
 * a project, the row's menu and "open in a new browser tab" are left out until this app has them.
 */
@Composable
fun ProjectFlyout(model: AppModel, anchor: Rect, limit: Float, onDismiss: () -> Unit) {
    Popup(
        popupPositionProvider = WindowOrigin,
        onDismissRequest = onDismiss,
        // Unclipped and as big as the app's window, so that the shadow is not cut off.
        properties = PopupProperties(focusable = true, clippingEnabled = false),
    ) {
        FlyoutLayer(model, anchor, limit, onDismiss)
    }
}

private object WindowOrigin : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
        IntOffset.Zero
}

/** The row being dragged (`dragIdx`); for what follows the pointer, how it looks and where the pointer took it. */
@Stable
private class RowDrag {
    var name by mutableStateOf<String?>(null)

    /** The pointer, in the root's coordinates. */
    var at by mutableStateOf(Offset.Zero)
    var byMouse by mutableStateOf(false)
    var snapshot: GraphicsLayer? = null
    var grab = Offset.Zero
}

/** The window-wide layer: the backdrop (`fixed inset-0`) that a tap closes the flyout on, the flyout, and what a drag carries. */
@Composable
private fun FlyoutLayer(model: AppModel, anchor: Rect, limit: Float, onDismiss: () -> Unit) {
    val density = LocalDensity.current
    val drag = remember { RowDrag() }
    val dismiss by rememberUpdatedState(onDismiss)
    var onScreen by remember { mutableStateOf<Offset?>(null) }
    var inRoot by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val gap = with(density) { FLYOUT_GAP.toPx() }
    val place = onScreen?.let { IntOffset((anchor.left - it.x).roundToInt(), (anchor.bottom + gap - it.y).roundToInt()) }
    val placed by rememberUpdatedState(place)
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                onScreen = it.positionOnScreen()
                inRoot = it.positionInRoot()
            }
            .dragCursor(drag.name != null && drag.byMouse)
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    val at = placed ?: return@detectTapGestures
                    if (!IntRect(at, size).contains(tap.round())) dismiss()
                }
            },
    ) {
        if (place == null) return@Box
        val room = with(density) { (limit - anchor.bottom - gap).toDp() }
        Flyout(
            model,
            drag,
            Modifier.offset { place }.width(FLYOUT_WIDTH).heightIn(max = room.coerceIn(0.dp, FLYOUT_MAX_HEIGHT)).onSizeChanged { size = it },
            onDismiss,
        )
        RowDragImage(drag, inRoot)
    }
}

@Composable
private fun Flyout(model: AppModel, drag: RowDrag, modifier: Modifier, onDismiss: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val density = LocalDensity.current
    val query = rememberTextFieldState()
    val q = query.text.toString().trim().lowercase()
    val shown = model.sortedProjects.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.path.lowercase().contains(q) }
    val draggable = model.projectSortMode == "priority" && q.isEmpty()
    // Back to the first row whenever the list changes, as `setHighlightIdx(0)` on query and sort.
    var highlight by remember(q, model.projectSortMode) { mutableIntStateOf(0) }
    val unread = model.unreadSessions()
    val scroll = rememberScrollState()
    val focus = remember { FocusRequester() }
    // Each row and the list's own box, in the root's coordinates: where a drop lands, and what scrolls into view.
    val rows = remember { mutableStateMapOf<String, Rect>() }
    var list by remember { mutableStateOf(Rect.Zero) }
    val current by rememberUpdatedState(shown)
    val target by remember {
        derivedStateOf {
            val from = drag.name ?: return@derivedStateOf null
            val at = drag.at
            if (!list.contains(at)) return@derivedStateOf null
            current.firstOrNull { rows[it.name]?.contains(at) == true }?.name?.takeIf { it != from }
        }
    }
    val shape = RoundedCornerShape(12.dp)

    fun open(project: ProjectInfo) {
        onDismiss()
        model.scope.launch { model.openProject(project.name) }
    }

    fun openHighlighted() {
        (shown.getOrNull(highlight) ?: shown.firstOrNull())?.let(::open)
    }

    /** `onDrop`: the dragged project out of its place and into the target's (`splice` twice). */
    fun drop() {
        val from = drag.name
        val to = target
        drag.name = null
        if (from == null || to == null) return
        val names = model.projects.map { it.name }.toMutableList()
        val index = names.indexOf(to)
        if (index < 0 || !names.remove(from)) return
        names.add(index, from)
        model.reorderProjects(names)
    }

    LaunchedEffect(Unit) { focus.requestFocus() }

    // `scrollIntoView({ block: "nearest" })` on the highlighted row.
    LaunchedEffect(highlight) {
        val row = shown.getOrNull(highlight)?.let { rows[it.name] } ?: return@LaunchedEffect
        when {
            row.top < list.top -> scroll.scrollBy(row.top - list.top)
            row.bottom > list.bottom -> scroll.scrollBy(row.bottom - list.bottom)
        }
    }

    // A row held near the list's top or bottom scrolls it, as a browser scrolls under a drag.
    val dragging = drag.name != null
    LaunchedEffect(dragging) {
        if (!dragging) return@LaunchedEffect
        val band = with(density) { AUTOSCROLL_BAND.toPx() }
        val step = with(density) { AUTOSCROLL_STEP.toPx() }
        while (true) {
            withFrameNanos {}
            val at = drag.at
            if (at.x < list.left || at.x > list.right) continue
            val depth = when {
                at.y >= list.top && at.y < list.top + band -> at.y - (list.top + band)
                at.y <= list.bottom && at.y > list.bottom - band -> at.y - (list.bottom - band)
                else -> 0f
            }
            if (depth != 0f) scroll.scrollBy(depth / band * step)
        }
    }

    Column(
        modifier
            .ppmShadow(tokens.shadowPanel, shape)
            .clip(shape)
            .background(tokens.panel2.over(tokens.bgSolid).color)
            .border(1.dp, tokens.border.color, shape)
            // Inside the 1 px border, which CSS counts into the box and `border()` does not.
            .padding(1.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().edge(Edge.Bottom, tokens.border.color).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PpmIcon(ProductIcon.Search, 14.dp, tokens.text3.color)
            PpmTextField(
                query,
                "Search projects…",
                Modifier.weight(1f).onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> highlight = minOf(highlight + 1, shown.size - 1)
                        Key.DirectionUp -> highlight = maxOf(highlight - 1, 0)
                        Key.Enter, Key.NumPadEnter -> openHighlighted()
                        Key.Escape -> onDismiss()
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                },
                style = ppmText(12f, lineHeight = 16f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                onKeyboardAction = { openHighlighted() },
                focusRequester = focus,
            )
        }
        Row(
            Modifier.fillMaxWidth().edge(Edge.Bottom, tokens.border.color).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for ((mode, label, icon) in PROJECT_SORTS) {
                val on = model.projectSortMode == mode
                val color = (if (on) tokens.accent else tokens.text3).color
                val pill = RoundedCornerShape(6.dp)
                Row(
                    Modifier
                        .weight(1f)
                        .clip(pill)
                        .background(if (on) tokens.accent.opacity(0.12).color else Color.Transparent)
                        .ppmClickable(pressedColor = Color.Transparent, onClickLabel = "Sort by ${label.lowercase()}") { model.pickProjectSort(mode) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                ) {
                    PpmIcon(icon, 12.dp, color)
                    BasicText(label, style = ppmText(11f, FontWeight.Medium, lineHeight = 16.5f).copy(color = color))
                }
            }
        }
        Column(
            Modifier
                .weight(1f, fill = false)
                .onGloballyPositioned { list = it.boundsInRoot() }
                .verticalScroll(scroll)
                .padding(6.dp),
        ) {
            shown.forEachIndexed { index, project ->
                key(project.name) {
                    DisposableEffect(Unit) { onDispose { rows.remove(project.name) } }
                    ProjectRow(
                        model,
                        project,
                        unread,
                        highlighted = index == highlight,
                        target = target == project.name,
                        drag = drag,
                        draggable = draggable,
                        onPlaced = { rows[project.name] = it },
                        onHover = { highlight = index },
                        onDrop = ::drop,
                        onOpen = { open(project) },
                    )
                }
            }
            if (shown.isEmpty()) {
                BasicText(
                    "No projects found",
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp),
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color, textAlign = TextAlign.Center),
                )
            }
        }
    }
}

/**
 * A project (`rounded-lg`): on the accent's wash while it is the open one, else on
 * `surface-elevated` while highlighted (a mouse over it highlights it); its avatar, name and path
 * open it, and how long ago it was opened sits at the end. While dragged it fades to 40 %, and the
 * row it would take the place of wears a 2 px accent ring.
 */
@Composable
private fun ProjectRow(
    model: AppModel,
    project: ProjectInfo,
    unread: Map<String, SessionUnread>,
    highlighted: Boolean,
    target: Boolean,
    drag: RowDrag,
    draggable: Boolean,
    onPlaced: (Rect) -> Unit,
    onHover: () -> Unit,
    onDrop: () -> Unit,
    onOpen: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    val active = project.name == model.selectedProject
    val snapshot = rememberGraphicsLayer()
    val hover by rememberUpdatedState(onHover)
    Row(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { onPlaced(Rect(it.positionInRoot(), it.size.toSize())) }
            .drawBehind {
                // `ring-2 ring-primary`: 2 px outside the row, bending with its corners.
                if (target) {
                    val px = 1.dp.toPx()
                    drawRoundRect(
                        tokens.accent.color,
                        Offset(-px, -px),
                        Size(size.width + 2 * px, size.height + 2 * px),
                        CornerRadius(9.dp.toPx()),
                        style = Stroke(2 * px),
                    )
                }
            }
            .graphicsLayer { alpha = if (drag.name == project.name) 0.4f else 1f }
            // What a drag carries, as a browser's drag image is: the row as it looks before it fades.
            .drawWithContent {
                snapshot.record { this@drawWithContent.drawContent() }
                drawLayer(snapshot)
            }
            .background(
                when {
                    active -> tokens.accent.opacity(0.12).color
                    highlighted -> tokens.panel2.color
                    else -> Color.Transparent
                },
                shape,
            )
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        if (awaitPointerEvent().type == PointerEventType.Enter) hover()
                    }
                }
            }
            .then(
                if (draggable) {
                    Modifier.pickUpGestures(
                        drag,
                        onPickUp = { grab, at, mouse ->
                            drag.snapshot = snapshot
                            drag.grab = grab
                            drag.at = at
                            drag.byMouse = mouse
                            drag.name = project.name
                        },
                        onMove = { drag.at = it },
                        onDrop = onDrop,
                        onCancel = { drag.name = null },
                    )
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .ppmClickable(pressedColor = Color.Transparent, onClick = onOpen)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SwitcherAvatar(model, project, 26.dp, unread)
            Column {
                BasicText(
                    project.name,
                    style = ppmText(13f, if (active) FontWeight.SemiBold else FontWeight.Normal, lineHeight = 19.5f)
                        .copy(color = (if (active) tokens.accent else tokens.text).color),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                BasicText(
                    project.path,
                    style = ppmText(10f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text3.color),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        model.projectOpenedAt[project.name]?.let {
            RelativeTime(Instant.ofEpochMilli(it), ppmText(10f, lineHeight = 15f).copy(color = tokens.text3.color), Modifier.padding(end = 8.dp))
        }
    }
}

/** The dragged row's image under the pointer; on its own, so that a move redraws only it. */
@Composable
private fun RowDragImage(drag: RowDrag, origin: Offset) {
    val snapshot = drag.snapshot
    if (drag.name == null || snapshot == null) return
    DragImage(snapshot, drag.at - origin, drag.grab)
}
