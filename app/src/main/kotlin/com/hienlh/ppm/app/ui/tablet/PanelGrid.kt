package com.hienlh.ppm.app.ui.tablet

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.EmptyPanel
import com.hienlh.ppm.app.ui.TabBody
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.state.workspace.WorkspaceState
import com.hienlh.ppm.state.workspace.visibleTabs

/** panel-layout.tsx's `DOCK_EXPANDED_BOTTOM` / `DOCK_EXPANDED_SIDE`: a maximized dock's share. */
private const val DOCK_EXPANDED_BOTTOM = 0.70f
private const val DOCK_EXPANDED_SIDE = 0.55f

/** The dock's own bounds while dragged (react-resizable-panels' `minSize="10%" maxSize="85%"`). */
private const val DOCK_MIN = 0.10f
private const val DOCK_MAX = 0.85f

/** A grid pane's `minSize="15%"`. */
private const val PANE_MIN = 0.15f

/**
 * The working area (panel-layout.tsx): the panel grid, and the dock beside or under it. The grid
 * keeps its place in the composition whether the dock shows or not, so showing the dock never
 * rebuilds the panels — on the web it would scroll every chat back to the top.
 */
@Composable
fun PanelArea(model: AppModel) {
    val state = model.layout ?: return
    val position = model.dockPosition
    val expanded = state.dockExpanded
    val visible = state.dock.visible
    // Taken when the dock is placed, maximized or restored (the web freezes `defaultSize` per
    // `${dockPosition}-${dockExpanded}`), then the drag's own.
    var share by remember(position, expanded) {
        mutableFloatStateOf(
            if (expanded) (if (position == "bottom") DOCK_EXPANDED_BOTTOM else DOCK_EXPANDED_SIDE) else (state.dock.height / 100).toFloat(),
        )
    }
    // A saved size that arrives is taken too: the layout first drawn can be the default one, with
    // the project's after it. This device's own save is not, as the layout may have clamped it
    // (a drag can reach 10 %, the layout keeps 15 %) and on the web the drag's size stays.
    var kept by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(state.dock.height) {
        if (!expanded && state.dock.height != kept) share = (state.dock.height / 100).toFloat()
    }
    var extent by remember { mutableIntStateOf(0) }
    val bottom = position == "bottom"
    // The share the drag began with: the dock is that moved by the whole offset, and so back under
    // the pointer once the pointer returns from past a limit.
    val from = remember { FloatArray(1) }
    val dragStart = { from[0] = share }
    val drag: (Float) -> Unit = { moved ->
        if (extent > 0) {
            // Dragging toward the grid grows the dock: up for a bottom dock, toward the middle for a side one.
            val toward = if (position == "left") moved else -moved
            share = (from[0] + toward / extent).coerceIn(DOCK_MIN, DOCK_MAX)
        }
    }
    // A maximized dock's size is not the one to keep: restoring returns to the saved one.
    val dragEnd = { if (!expanded) kept = model.setDockHeight(share * 100.0) }
    val grid = @Composable { m: Modifier -> Box(m) { PanelGrid(model, state) } }
    val dock = @Composable { m: Modifier ->
        Box(m) {
            DockPanel(model, state, if (bottom) Edge.Top else if (position == "left") Edge.Right else Edge.Left)
        }
    }
    if (bottom) {
        Column(Modifier.fillMaxSize().onSizeChanged { extent = it.height }) {
            grid(Modifier.weight(if (visible) 1f - share else 1f).fillMaxWidth())
            if (visible) {
                SplitHandle(vertical = false, onDragStart = dragStart, onDrag = drag, onDragEnd = dragEnd)
                dock(Modifier.weight(share).fillMaxWidth())
            }
        }
    } else {
        Row(Modifier.fillMaxSize().onSizeChanged { extent = it.width }) {
            if (visible && position == "left") {
                dock(Modifier.weight(share).fillMaxHeight())
                SplitHandle(vertical = true, onDragStart = dragStart, onDrag = drag, onDragEnd = dragEnd)
            }
            grid(Modifier.weight(if (visible) 1f - share else 1f).fillMaxHeight())
            if (visible && position == "right") {
                SplitHandle(vertical = true, onDragStart = dragStart, onDrag = drag, onDragEnd = dragEnd)
                dock(Modifier.weight(share).fillMaxHeight())
            }
        }
    }
}

/**
 * The grid: rows of panels, every row and every panel a share of its line that a handle moves,
 * none under 15 %. The shares are not saved, as on the web: they start equal whenever the grid
 * changes shape.
 */
@Composable
private fun PanelGrid(model: AppModel, state: WorkspaceState) {
    val grid = state.grid
    val count = grid.sumOf { it.size }
    if (count == 0) return
    val rows = remember(grid) { equalShares(grid.size) }
    val columns = remember(grid) { grid.map { equalShares(it.size) } }
    var height by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().onSizeChanged { height = it.height }) {
        grid.forEachIndexed { r, row ->
            var width by remember(grid) { mutableIntStateOf(0) }
            Row(Modifier.weight(rows[r]).fillMaxWidth().onSizeChanged { width = it.width }) {
                row.forEachIndexed { c, panelId ->
                    key(panelId) {
                        Box(Modifier.weight(columns[r][c]).fillMaxHeight()) { EditorPanel(model, state, panelId, multiple = count > 1) }
                    }
                    if (c < row.lastIndex) {
                        val from = remember { FloatArray(1) }
                        SplitHandle(
                            vertical = true,
                            onDragStart = { from[0] = columns[r][c] },
                            onDrag = { moved -> if (width > 0) columns[r].place(c, from[0] + moved / width) },
                        )
                    }
                }
            }
            if (r < grid.lastIndex) {
                val from = remember { FloatArray(1) }
                SplitHandle(
                    vertical = false,
                    onDragStart = { from[0] = rows[r] },
                    onDrag = { moved -> if (height > 0) rows.place(r, from[0] + moved / height) },
                )
            }
        }
    }
}

private fun equalShares(n: Int): SnapshotStateList<Float> = mutableStateListOf<Float>().apply { repeat(n) { add(1f / n) } }

/** Gives pane [index] [share] of the whole line, from its neighbour after it, neither under its minimum. */
private fun SnapshotStateList<Float>.place(index: Int, share: Float) {
    val pair = this[index] + this[index + 1]
    val first = share.coerceIn(PANE_MIN, (pair - PANE_MIN).coerceAtLeast(PANE_MIN))
    this[index] = first
    this[index + 1] = pair - first
}

/**
 * One panel (editor-panel.tsx): its tab bar over the tab in front, or the empty panel's tiles and
 * chats. With more than one panel each has a 1 px border, the focused one's in the accent at 30 %.
 * A touch anywhere in it focuses it, without taking the touch from what is under the finger.
 */
@Composable
private fun EditorPanel(model: AppModel, state: WorkspaceState, panelId: String, multiple: Boolean) {
    val panel = state.panels[panelId] ?: return
    val tokens = LocalPpmTokens.current
    val focused = state.focusedPanelId == panelId
    // A panel holding only another project's tab shows none, and counts as empty (`visibleTabs`).
    val tabs = visibleTabs(panel.tabs, state.currentProject)
    val drag = LocalTabDrag.current
    DisposableEffect(drag, panelId) { onDispose { drag?.areas?.remove(panelId) } }
    Column(
        Modifier
            .fillMaxSize()
            .then(
                if (multiple) {
                    Modifier.cssBorder(if (focused) tokens.accent.opacity(0.3).color else Color.Transparent)
                } else {
                    Modifier
                },
            )
            .pointerInput(panelId) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    model.focusPanel(panelId)
                }
            },
    ) {
        TabBar(model, panelId, tabs, panel.activeTabId)
        // The content is where a dragged tab is dropped (`data-panel-drop-zone`).
        Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { drag?.areas?.set(panelId, it.boundsInRoot()) }) {
            val active = tabs.firstOrNull { it.id == panel.activeTabId }
            when {
                tabs.isEmpty() -> EmptyPanel(
                    model,
                    onOpenSession = { model.openTab(it, panelId) },
                    panelId = panelId,
                    onClosePanel = if (multiple) ({ model.closePanel(panelId) }) else null,
                )
                active != null -> key(active.id) { TabBody(model, active) }
            }
            val target = drag?.target
            if (drag?.tab != null && target?.panelId == panelId) DropHighlight(target.zone)
        }
    }
}
