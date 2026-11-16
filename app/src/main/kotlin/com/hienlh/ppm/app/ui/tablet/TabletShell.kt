package com.hienlh.ppm.app.ui.tablet

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.TabletEscapeStack
import com.hienlh.ppm.app.TabletShortcutBridge
import com.hienlh.ppm.app.showSpecOnTablet
import com.hienlh.ppm.app.specs
import com.hienlh.ppm.app.ui.Backdrop
import com.hienlh.ppm.app.ui.CornerChrome
import com.hienlh.ppm.app.ui.DropRouter
import com.hienlh.ppm.app.ui.FeatureBadge
import com.hienlh.ppm.app.ui.FeatureBadgeKind
import com.hienlh.ppm.app.ui.KeyboardPannedPage
import com.hienlh.ppm.app.ui.LocalDropRouter
import com.hienlh.ppm.app.ui.SHADOW_2XL
import com.hienlh.ppm.app.ui.dropRouter
import com.hienlh.ppm.app.ui.windows.WindowLayer
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.WebCursor
import com.hienlh.ppm.design.colorsFade
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.windows.WindowVisualState
import com.hienlh.ppm.state.windows.frontWindowId
import com.hienlh.ppm.state.workspace.closesItsTabs
import kotlin.math.roundToInt

/**
 * Shared state for the tablet shell (`Tablet 2 · Shell, projects, palette, notifications` and
 * `Foundation · Keyboard & Mouse`). Tracks top-layer popovers, portrait slide-over sidebar,
 * keyboard shortcuts sheet (`Ctrl+/`), All Tabs popover (`Ctrl+Tab`), and `Escape` dismissal order.
 */
@Stable
class TabletShellState {
    var isPortrait by mutableStateOf(false)
    var moreMenuOpen by mutableStateOf(false)
    var tabListPanelId by mutableStateOf<String?>(null)
    var shortcutsSheetOpen by mutableStateOf(false)
    var statusPopover by mutableStateOf<String?>(null)
    var railOrderOverride by mutableStateOf<List<String>?>(null)
    var pointerConnected by mutableStateOf(false)
    var projectFlyoutRequest by mutableIntStateOf(0)
        private set

    fun requestProjectFlyout() {
        projectFlyoutRequest++
    }

    fun hasOpenLayer(model: AppModel): Boolean {
        if (model.paletteQuery != null) return true
        if (shortcutsSheetOpen || tabListPanelId != null || moreMenuOpen || statusPopover != null) return true
        if (model.cloudShareOpen || model.branchPickerProject != null) return true
        if (isPortrait && !model.sidebarCollapsed) return true
        val windows = model.windowState.windows
        val frontId = frontWindowId(windows)
        val win = frontId?.let { windows[it] }
        return win != null && win.state != WindowVisualState.Minimized && !closesItsTabs(win)
    }

    /**
     * Dismisses the topmost open tablet layer in order (`Foundation` Section 2b, Requirement 16.3):
     * palette -> shortcuts sheet -> All Tabs popover -> More menu -> status bar popover ->
     * Cloud & Share -> branch picker -> portrait slide-over sidebar -> front floating window.
     */
    fun closeTopLayer(model: AppModel): Boolean {
        if (model.paletteQuery != null) {
            model.closePalette()
            return true
        }
        if (shortcutsSheetOpen) {
            shortcutsSheetOpen = false
            return true
        }
        if (tabListPanelId != null) {
            tabListPanelId = null
            return true
        }
        if (moreMenuOpen) {
            moreMenuOpen = false
            return true
        }
        if (statusPopover != null) {
            statusPopover = null
            return true
        }
        if (model.cloudShareOpen) {
            model.cloudShareOpen = false
            return true
        }
        if (model.branchPickerProject != null) {
            model.closeBranchPicker()
            return true
        }
        if (isPortrait && !model.sidebarCollapsed) {
            model.toggleSidebar()
            return true
        }
        val windows = model.windowState.windows
        val frontId = frontWindowId(windows)
        val win = frontId?.let { windows[it] }
        if (win != null && win.state != WindowVisualState.Minimized && !closesItsTabs(win)) {
            model.closeWindow(win.id)
            return true
        }
        return false
    }
}

val LocalTabletShellState = staticCompositionLocalOf<TabletShellState?> { null }

/**
 * The desktop/tablet layout (app.tsx from `md` up): the sidebar, then the working area — the panel grid
 * with the dock, the floating windows over it — over the status bar, which spans the working area only.
 * In portrait (`< 840 dp` wide or taller than wide), the 60 dp rail stays inline at the left and the
 * sidebar panel slides over the working area above a dim scrim (`R2 · Portrait`).
 */
@Composable
fun TabletShell(model: AppModel, shellState: TabletShellState = remember { TabletShellState() }) {
    val drag = remember { TabDrag() }
    val held = remember { HeldCursor() }
    DisposableEffect(shellState, model) {
        val unregisterEscape = TabletEscapeStack.register { shellState.closeTopLayer(model) }
        val prevShortcuts = TabletShortcutBridge.onToggleShortcutsSheet
        val prevTabSwitcher = TabletShortcutBridge.onOpenTabSwitcher
        TabletShortcutBridge.onToggleShortcutsSheet = {
            shellState.shortcutsSheetOpen = !shellState.shortcutsSheetOpen
            true
        }
        TabletShortcutBridge.onOpenTabSwitcher = { _ ->
            val panelId = model.layout?.focusedPanelId ?: model.gridPanelIds.firstOrNull() ?: "panel-1"
            shellState.tabListPanelId = if (shellState.tabListPanelId != null) null else panelId
            true
        }
        onDispose {
            unregisterEscape()
            if (TabletShortcutBridge.onToggleShortcutsSheet !== prevShortcuts) {
                TabletShortcutBridge.onToggleShortcutsSheet = prevShortcuts
            }
            if (TabletShortcutBridge.onOpenTabSwitcher !== prevTabSwitcher) {
                TabletShortcutBridge.onOpenTabSwitcher = prevTabSwitcher
            }
        }
    }
    BackHandler(enabled = shellState.hasOpenLayer(model)) {
        shellState.closeTopLayer(model)
    }
    TabletSpecRequests(model)
    CompositionLocalProvider(
        LocalTabDrag provides drag,
        LocalHeldCursor provides held,
        LocalTabletShellState provides shellState,
    ) {
        Backdrop(
            Modifier
                .dragCursor(drag, held)
                .pointerInput(shellState) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val type = event.changes.firstOrNull()?.type
                            if (type == PointerType.Mouse || type == PointerType.Stylus) {
                                shellState.pointerConnected = true
                            }
                        }
                    }
                },
        ) {
            KeyboardPannedPage {
                BoxWithConstraints(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
                ) {
                    val portrait = maxWidth < 840.dp || maxHeight > maxWidth
                    SideEffect { shellState.isPortrait = portrait }
                    Row(Modifier.fillMaxSize()) {
                        if (portrait) {
                            Sidebar(model, portraitRailOnly = true)
                        } else {
                            Sidebar(model, portraitRailOnly = false)
                        }
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            Column(Modifier.fillMaxSize()) {
                                WorkingAreaLayers(
                                    Modifier.weight(1f).fillMaxWidth(),
                                    panels = { PanelArea(model) },
                                    windows = { WindowLayer(model) },
                                )
                                StatusBar(model)
                            }
                            if (portrait && !model.sidebarCollapsed) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .zIndex(10f)
                                        .background(Color.Black.copy(alpha = 0.45f))
                                        .semantics { contentDescription = "Close sidebar scrim" }
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) { model.toggleSidebar() },
                                )
                                SidebarSlideOverPanel(
                                    model = model,
                                    modifier = Modifier.align(Alignment.CenterStart).fillMaxHeight().zIndex(11f),
                                )
                            }
                        }
                    }
                }
                CornerChrome(model, mobile = false)
            }
            DropZoneLabelOverlay(drag)
            TabDragGhost(drag)
            if (shellState.shortcutsSheetOpen) {
                KeyboardShortcutsModal(model, onDismiss = { shellState.shortcutsSheetOpen = false })
            }
            // The workflow dialog a chat's chip asked for (`startSpec`).
            TabletSpecStartDialog(model)
        }
    }
}

/**
 * Labelled pill over the active drop zone when dragging a tab (`Tablet 2 · Section 4 TB5`):
 * `"Split left"`, `"Split right"`, `"Split up"`, `"Split down"`, or `"Move here"`.
 */
@Composable
private fun BoxScope.DropZoneLabelOverlay(drag: TabDrag) {
    if (drag.tab == null) return
    val target = drag.target ?: return
    val rect = drag.areas[target.panelId] ?: return
    val tokens = LocalPpmTokens.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val (label, icon) = when (target.zone) {
        DropZone.Left -> "Split left" to ProductIcon.Columns2
        DropZone.Right -> "Split right" to ProductIcon.Columns2
        DropZone.Top -> "Split up" to ProductIcon.PanelBottom
        DropZone.Bottom -> "Split down" to ProductIcon.PanelBottom
        DropZone.Center -> "Move here" to ProductIcon.ArrowRight
    }
    val center = when (target.zone) {
        DropZone.Left -> Offset(rect.left + rect.width / 6f, rect.center.y)
        DropZone.Right -> Offset(rect.right - rect.width / 6f, rect.center.y)
        DropZone.Top -> Offset(rect.center.x, rect.top + rect.height / 6f)
        DropZone.Bottom -> Offset(rect.center.x, rect.bottom - rect.height / 6f)
        DropZone.Center -> rect.center
    }
    val pillShape = RoundedCornerShape(999.dp)
    Box(Modifier.matchParentSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        Row(
            Modifier
                .offset { IntOffset((center.x - origin.x - 54f).roundToInt(), (center.y - origin.y - 16f).roundToInt()) }
                .ppmShadow(tokens.shadowFloat, pillShape)
                .clip(pillShape)
                .background(tokens.panel2.color, pillShape)
                .cssBorder(tokens.accent.color, pillShape)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PpmIcon(icon, 14.dp, tokens.accent.color)
            BasicText(
                label,
                style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.accent.color),
                maxLines = 1,
            )
        }
    }
}

/**
 * Keyboard shortcuts sheet (`Foundation · Section 2b T5 · Keyboard & mouse`, `Ctrl+/`):
 * 640 dp card grouping shortcuts into `Everywhere`, `Tabs`, `Files`, and `Chat`, with `NEW`
 * badges on `Ctrl+/`, `Ctrl+T`, and `Ctrl+W`, plus a footer link to `Settings → Keybindings`.
 */
@Composable
internal fun KeyboardShortcutsModal(model: AppModel, onDismiss: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(16.dp)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .ppmShadow(SHADOW_2XL, shape)
                    .clip(shape)
                    .background(tokens.panel2.color, shape)
                    .cssBorder(tokens.border.color, shape)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {},
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .edge(Edge.Bottom, tokens.border.color)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PpmIcon(ProductIcon.Keyboard, 18.dp, tokens.accent.color)
                    BasicText(
                        "Keyboard shortcuts",
                        style = ppmText(15f, FontWeight.SemiBold, lineHeight = 20f).copy(color = tokens.text.color),
                    )
                    ShortcutKeyCaps(listOf("Ctrl", "/"))
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .semantics { contentDescription = "Close keyboard shortcuts" }
                            .ppmClickable(
                                pressedColor = tokens.panel.color,
                                hoverColor = tokens.panel.color,
                                onClickLabel = "Close keyboard shortcuts",
                                onClick = onDismiss,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        PpmIcon(ProductIcon.X, 16.dp, tokens.text2.color)
                    }
                }
                Row(
                    Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        ShortcutGroupSection(
                            title = "EVERYWHERE",
                            rows = listOf(
                                ShortcutSpecRow("Command palette", listOf("F1")),
                                ShortcutSpecRow("Command palette (double tap)", listOf("Shift", "Shift")),
                                ShortcutSpecRow("Toggle sidebar", listOf("Ctrl", "B")),
                                ShortcutSpecRow("Toggle bottom dock", listOf("Ctrl", "'")),
                                ShortcutSpecRow("Open Settings", listOf("Ctrl", ",")),
                                ShortcutSpecRow("Keyboard shortcuts", listOf("Ctrl", "/"), isNew = true),
                            ),
                        )
                        ShortcutGroupSection(
                            title = "FILES",
                            rows = listOf(
                                ShortcutSpecRow("New untitled file", listOf("Ctrl", "N")),
                                ShortcutSpecRow("Show Source Control", listOf("Ctrl", "Shift", "E")),
                                ShortcutSpecRow("Search in files", listOf("Ctrl", "Shift", "F")),
                                ShortcutSpecRow("Problems panel", listOf("Ctrl", "Shift", "M")),
                                ShortcutSpecRow("Compare files", listOf("Ctrl", "Alt", "D")),
                                ShortcutSpecRow("Toggle word wrap", listOf("Alt", "Z")),
                            ),
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        ShortcutGroupSection(
                            title = "TABS",
                            rows = listOf(
                                ShortcutSpecRow("New tab (opens picker)", listOf("Ctrl", "T"), isNew = true),
                                ShortcutSpecRow("Close current tab", listOf("Ctrl", "W"), isNew = true),
                                ShortcutSpecRow("Next tab", listOf("Alt", "]")),
                                ShortcutSpecRow("Previous tab", listOf("Alt", "[")),
                                ShortcutSpecRow("All Tabs switcher", listOf("Ctrl", "Tab")),
                                ShortcutSpecRow("Switch project 1…9", listOf("Ctrl", "1…9")),
                            ),
                        )
                        ShortcutGroupSection(
                            title = "CHAT",
                            rows = listOf(
                                ShortcutSpecRow("Send message", listOf("Enter")),
                                ShortcutSpecRow("New line in message", listOf("Shift", "Enter")),
                                ShortcutSpecRow("Previous / next prompt", listOf("Alt", "↑ / ↓")),
                                ShortcutSpecRow("Voice input", listOf("Ctrl", "Shift", "V")),
                            ),
                        )
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .edge(Edge.Top, tokens.border.color)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    BasicText(
                        "Press Esc to close",
                        style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
                    )
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .ppmClickable(
                                pressedColor = tokens.panel.color,
                                hoverColor = tokens.panel.color,
                                onClickLabel = "Customize in Settings",
                            ) {
                                onDismiss()
                                model.openSettings("keybindings")
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BasicText(
                            "Customize in Settings → Keybindings",
                            style = ppmText(12.5f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.accent.color),
                        )
                    }
                }
            }
        }
    }
}

private data class ShortcutSpecRow(
    val label: String,
    val keys: List<String>,
    val isNew: Boolean = false,
)

@Composable
private fun ShortcutGroupSection(title: String, rows: List<ShortcutSpecRow>) {
    val tokens = LocalPpmTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(
            title,
            style = ppmText(11f, FontWeight.Bold, lineHeight = 14f).copy(color = tokens.text3.color, letterSpacing = 0.6.sp),
        )
        for (row in rows) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    Modifier.weight(1f).padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BasicText(
                        row.label,
                        style = ppmText(12.5f, lineHeight = 17f).copy(color = tokens.text.color),
                        maxLines = 1,
                    )
                    if (row.isNew) {
                        FeatureBadge(FeatureBadgeKind.NEW)
                    }
                }
                ShortcutKeyCaps(row.keys)
            }
        }
    }
}

@Composable
private fun ShortcutKeyCaps(keys: List<String>) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(5.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        keys.forEachIndexed { idx, key ->
            if (idx > 0) {
                BasicText("+", style = ppmText(10f, lineHeight = 12f).copy(color = tokens.text3.color))
            }
            Box(
                Modifier
                    .clip(shape)
                    .background(tokens.panel.color, shape)
                    .cssBorder(tokens.border.color, shape)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    key,
                    style = ppmText(11f, FontWeight.Medium, FontKind.MONO, lineHeight = 14f).copy(color = tokens.text2.color),
                )
            }
        }
    }
}

/**
 * The working area: the [panels] with the floating [windows] over them, and the [DropRouter] that every place which takes a drag in either
 * answers to, so what is dropped goes to what is on top of where it is let go. The sidebar and the status bar are outside it, and so are
 * the places in them, which are ordinary targets.
 */
@Composable
internal fun WorkingAreaLayers(modifier: Modifier, panels: @Composable () -> Unit, windows: @Composable () -> Unit) {
    val router = remember { DropRouter() }
    CompositionLocalProvider(LocalDropRouter provides router) {
        Box(modifier.dropRouter(router)) {
            panels()
            windows()
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
 * the accent at 30% under a mouse and at 50% while a mouse press that began on it holds, a grip in
 * its middle (always drawn: a touch screen has no hover). It takes one pixel of the layout but a
 * 20 dp strip of touch and mouse, laid over both neighbours.
 */
@Composable
fun SplitHandle(
    vertical: Boolean,
    order: Int,
    onDragStart: () -> Unit = {},
    onDrag: (Float) -> Boolean,
    onDragEnd: () -> Unit = {},
    onDoubleClick: (() -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    val strips = LocalSplitStrips.current
    val density = LocalDensity.current
    val strip = remember(vertical) { SplitStrip(vertical) }
    SideEffect {
        strip.order = order
        strip.reach = with(density) { 10.dp.toPx() }
        strip.gripHalf = with(density) { 6.dp.toPx() }
        strip.onStart = onDragStart
        strip.onDrag = onDrag
        strip.onEnd = onDragEnd
    }
    DisposableEffect(strips, strip) {
        strips.add(strip)
        onDispose { strips.remove(strip) }
    }
    // `transition-colors duration-150` between the three.
    val lit = colorsFade(strip.hovered).toDouble()
    val press = colorsFade(strip.pressed).toDouble()
    val line = tokens.accent.opacity(0.5).mix(tokens.accent.opacity(0.3).mix(tokens.border.opacity(0.3), lit), press)
    Box(
        (if (vertical) Modifier.width(1.dp).fillMaxHeight() else Modifier.height(1.dp).fillMaxWidth())
            .zIndex(1f)
            .background(line.color)
            .onGloballyPositioned { strip.coordinates = it },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            (if (vertical) Modifier.fillMaxHeight().requiredWidth(20.dp) else Modifier.fillMaxWidth().requiredHeight(20.dp))
                .pointerHoverIcon(if (vertical) WebCursor.ResizeHorizontal else WebCursor.ResizeVertical)
                .then(
                    if (onDoubleClick != null) {
                        Modifier.pointerInput(onDoubleClick) {
                            detectTapGestures(onDoubleTap = { onDoubleClick() })
                        }
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            PpmIcon(if (vertical) ProductIcon.GripVertical else ProductIcon.GripHorizontal, 12.dp, tokens.text.opacity(0.5).color)
        }
    }
}

/**
 * The cursor a drag keeps wherever the pointer goes.
 */
@Stable
class HeldCursor {
    var icon by mutableStateOf<PointerIcon?>(null)
}

val LocalHeldCursor = staticCompositionLocalOf { HeldCursor() }

/**
 * A drag along one axis, measured on the screen.
 */
fun Modifier.screenDrag(
    horizontal: Boolean,
    cursor: PointerIcon,
    takes: PointerInputScope.(PointerInputChange) -> Boolean = { true },
    onStart: (PointerInputChange) -> Unit,
    onDrag: (Float) -> Unit,
    onEnd: () -> Unit,
): Modifier = composed {
    val start by rememberUpdatedState(onStart)
    val move by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    val icon by rememberUpdatedState(cursor)
    val accepts by rememberUpdatedState(takes)
    val held = LocalHeldCursor.current
    val placed = remember { PlacedAt() }
    onGloballyPositioned { placed.coordinates = it }.pointerInput(horizontal) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!accepts(down)) return@awaitEachGesture
            var last = placed.coordinates?.localToRoot(down.position) ?: return@awaitEachGesture
            down.consume()
            start(down)
            held.icon = icon
            try {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    val moves = change.pressed || change.type != PointerType.Mouse
                    placed.coordinates?.localToRoot(change.position)?.takeIf { moves }?.let { now ->
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
internal class PlacedAt {
    var coordinates: LayoutCoordinates? = null
}

/**
 * Spec mode's requests on a tablet: a chat started as a spec gets its spec once its first message
 * makes the session, `openSpecs()` shows the sidebar's Specs, and `openSpec()` shows the spec in
 * the pane of its chat. Back closes the workflow dialog.
 */
@Composable
private fun TabletSpecRequests(model: AppModel) {
    val specs = model.specs
    val openTabs = model.openTabs.map { it.first }
    val openTabIds = openTabs.mapTo(LinkedHashSet()) { it.id }
    LaunchedEffect(openTabIds) {
        specs.armPending(openTabs)
    }
    val requested = specs.showRequested
    LaunchedEffect(requested) {
        if (!requested) return@LaunchedEffect
        specs.showRequested = false
        val slug = specs.openSlug
        if (slug == null) model.showSidebarSection("specs") else model.showSpecOnTablet(slug)
    }
    BackHandler(enabled = specs.choosingFor != null) { specs.choosingFor = null }
}
