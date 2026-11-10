package com.hienlh.ppm.app.ui.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.DropdownAboveEnd
import com.hienlh.ppm.app.ui.MenuItem
import com.hienlh.ppm.app.ui.ThemeSwatch
import com.hienlh.ppm.app.ui.UpgradeButton
import com.hienlh.ppm.app.ui.editor.SharedProblemsRegistry
import com.hienlh.ppm.app.ui.extensions.ExtensionStatusBarItems
import com.hienlh.ppm.app.ui.screenKeptAwake
import com.hienlh.ppm.app.ui.windows.WindowDock
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.kit.HoverTooltip
import com.hienlh.ppm.design.kit.kitFocusRing
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max

/**
 * The status bar (status-bar.tsx): `40 dp` tall (`bg-panel border-t border-border-soft
 * text-[11px] font-mono text-text-3`). On the left: connection status, the open project's branch,
 * problems count, DB row count (when active tab is a DB tab), extension items, and the dock's
 * toggle; in the middle the floating windows' dock; on the right extension items, wake lock,
 * CPU/memory resources, theme picker, and server version.
 */
@Composable
fun StatusBar(model: AppModel) {
    val tokens = LocalPpmTokens.current
    val shellState = LocalTabletShellState.current
    var localPopover by remember { mutableStateOf<String?>(null) }
    val activePopover = if (shellState != null) shellState.statusPopover else localPopover
    fun setActivePopover(id: String?) {
        if (shellState != null) {
            shellState.statusPopover = id
        } else {
            localPopover = id
        }
    }

    val text = ppmText(11f, kind = FontKind.MONO, lineHeight = 16.5f).copy(color = tokens.text3.color)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(tokens.panel.color)
            .edge(Edge.Top, tokens.borderSoft.color),
    ) {
        // The bar is its own `@container`: under 48rem the theme keeps only its icon, under 36rem
        // the branch's counts give way.
        val compact = maxWidth < 768.dp
        val narrow = maxWidth < 576.dp
        StatusBarRow(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            left = {
                // The side that gives way: the branch name ellipsizes, then the group clips.
                Row(
                    Modifier.clipToBounds(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ConnectionStatusItem(
                        model = model,
                        text = text,
                        open = activePopover == "connection",
                        onToggle = { setActivePopover(if (activePopover == "connection") null else "connection") },
                        onDismiss = { if (activePopover == "connection") setActivePopover(null) },
                    )
                    GitStatus(model, text, narrow)
                    ProblemsStatus(model, text)
                    DbRowsStatus(model, text)
                    ExtensionStatusBarItems(model, "left")
                    DockToggle(model, text)
                }
            },
            // The floating windows' taskbar takes the free middle.
            dock = if (model.windowState.windows.isNotEmpty()) ({ WindowDock(model) }) else null,
            right = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ExtensionStatusBarItems(model, "right")
                    if (model.screenKeptAwake()) {
                        Awake(
                            model = model,
                            text = text,
                            open = activePopover == "wakelock",
                            onToggle = { setActivePopover(if (activePopover == "wakelock") null else "wakelock") },
                            onDismiss = { if (activePopover == "wakelock") setActivePopover(null) },
                        )
                    }
                    model.api?.let { Resources(model, text) { model.openSystemMonitor() } }
                    ThemePicker(
                        model = model,
                        text = text,
                        compact = compact,
                        open = activePopover == "theme",
                        onToggle = { setActivePopover(if (activePopover == "theme") null else "theme") },
                        onDismiss = { if (activePopover == "theme") setActivePopover(null) },
                    )
                    UpgradeButton(model, text, prefix = !compact)
                }
            },
        )
    }
}

/**
 * The bar's row (`flex justify-between gap-3`): the right group never shrinks, the left one takes
 * what it needs of the rest and clips past it, and the window [dock], when there is one, every
 * dp between them (`flex-1 basis-0`); without it the two groups sit at either end.
 */
@Composable
private fun StatusBarRow(modifier: Modifier, left: @Composable () -> Unit, dock: (@Composable () -> Unit)?, right: @Composable () -> Unit) {
    Layout(
        content = {
            Box(Modifier.layoutId(LEFT)) { left() }
            if (dock != null) Box(Modifier.layoutId(DOCK)) { dock() }
            Box(Modifier.layoutId(RIGHT)) { right() }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val gap = 12.dp.roundToPx()
        val loose = Constraints(maxWidth = width, maxHeight = height)
        val right = measurables.first { it.layoutId == RIGHT }.measure(loose)
        val dockMeasurable = measurables.firstOrNull { it.layoutId == DOCK }
        val gaps = if (dockMeasurable != null) 2 * gap else gap
        val left = measurables.first { it.layoutId == LEFT }.measure(loose.copy(maxWidth = max(0, width - right.width - gaps)))
        val dock = dockMeasurable?.measure(Constraints.fixed(max(0, width - right.width - left.width - gaps), height))
        layout(width, height) {
            left.placeRelative(0, (height - left.height) / 2)
            dock?.placeRelative(left.width + gap, 0)
            right.placeRelative(width - right.width, (height - right.height) / 2)
        }
    }
}

private const val LEFT = "left"
private const val DOCK = "dock"
private const val RIGHT = "right"

/**
 * Item 1 (`ConnectionStatusItem`): live connection dot + label (`Live` / `Reconnecting…` / `Offline · Retry`),
 * opening the 388 dp Connection popover (`SB2`).
 */
@Composable
private fun ConnectionStatusItem(
    model: AppModel,
    text: TextStyle,
    open: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val density = LocalDensity.current
    val connected = model.api != null && !model.connectionLost
    val reconnecting = model.api != null && model.connectionLost
    val dotColor = when {
        connected -> tokens.success.color
        reconnecting -> tokens.warning.color
        else -> tokens.error.color
    }
    val label = when {
        connected -> "Live"
        reconnecting -> "Reconnecting…"
        else -> "Offline · Retry"
    }
    val hover = rememberHover()
    val lit = tokens.text.mix(tokens.text3, hover.lit.toDouble())
    Box {
        HoverTooltip("Server connection") {
            Row(
                Modifier
                    .heightIn(min = 32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .semantics { contentDescription = "Server connection" }
                    .kitFocusRing(false, RoundedCornerShape(6.dp))
                    .ppmClickable(
                        pressedColor = tokens.panel2.opacity(0.25).color,
                        hoverColor = tokens.panel2.opacity(0.2).color,
                        onClickLabel = "Server connection",
                        source = hover.source,
                        onClick = onToggle,
                    )
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
                BasicText(
                    label,
                    style = text.copy(color = if (connected) lit.color else dotColor),
                    maxLines = 1,
                )
            }
        }
        if (open) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, with(density) { (-230).dp.roundToPx() }),
                onDismissRequest = onDismiss,
                properties = PopupProperties(focusable = true),
            ) {
                ConnectionPopoverCard(
                    model = model,
                    connected = connected,
                    dotColor = dotColor,
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

@Composable
internal fun ConnectionPopoverCard(
    model: AppModel,
    connected: Boolean = model.api != null,
    dotColor: androidx.compose.ui.graphics.Color = LocalPpmTokens.current.success.color,
    onDismiss: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .width(388.dp)
            .ppmShadow(tokens.shadowFloat, shape)
            .clip(shape)
            .background(tokens.panel2.color)
            .cssBorder(tokens.border.color, shape = shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BasicText(
                "Connection",
                style = ppmText(14f, FontWeight.SemiBold, lineHeight = 20f).copy(color = tokens.text.color),
            )
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .ppmClickable(
                        hoverColor = tokens.panel2.color,
                        pressedColor = tokens.panel2.color,
                        onClickLabel = "Close",
                        onClick = onDismiss,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.X, 14.dp, tokens.text2.color)
            }
        }
        ConnectionInfoRow(
            label = "Server",
            value = model.serverName,
            badge = if (connected) "Connected" else "Offline",
            badgeColor = dotColor,
        )
        ConnectionInfoRow(
            label = "Address",
            value = model.api?.baseUrl?.toString() ?: model.store.selected?.url ?: "—",
        )
        ConnectionInfoRow(
            label = "Version",
            value = model.info?.version?.let { "v$it" } ?: "—",
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val btnShape = RoundedCornerShape(8.dp)
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(btnShape)
                    .background(tokens.panel2.color)
                    .cssBorder(tokens.borderSoft.color, shape = btnShape)
                    .ppmClickable(onClickLabel = "Reconnect now") {
                        onDismiss()
                        model.becameActive()
                    },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    "Reconnect now",
                    style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(btnShape)
                    .background(tokens.panel2.color)
                    .cssBorder(tokens.borderSoft.color, shape = btnShape)
                    .ppmClickable(onClickLabel = "Switch server") {
                        onDismiss()
                        model.openSettings("servers")
                    },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    "Switch server",
                    style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
                )
            }
        }
    }
}

@Composable
private fun ConnectionInfoRow(
    label: String,
    value: String,
    badge: String? = null,
    badgeColor: androidx.compose.ui.graphics.Color? = null,
) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        BasicText(
            label,
            style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BasicText(
                value,
                Modifier.widthIn(max = 220.dp),
                style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (badge != null && badgeColor != null) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(badgeColor.copy(alpha = 0.16f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    BasicText(
                        badge,
                        style = ppmText(10f, FontWeight.SemiBold, kind = FontKind.MONO, lineHeight = 14f).copy(color = badgeColor),
                    )
                }
            }
        }
    }
}

/**
 * The open project's branch (`GitStatus`) in the accent, then how far it is ahead of and behind
 * its upstream, or "synced". Read while the bar shows and the app is in front.
 */
@Composable
private fun GitStatus(model: AppModel, text: TextStyle, narrow: Boolean) {
    val tokens = LocalPpmTokens.current
    val project = model.selectedProject ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model.api, project, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.watchGit(project) }
    }
    val git = model.gitBranches[project] ?: return
    val branch = git.branch ?: return
    val synced = git.tracking != null && git.ahead == 0 && git.behind == 0
    HoverTooltip("Checkout branch") {
        Row(
            Modifier
                .heightIn(min = 32.dp)
                .clip(RoundedCornerShape(6.dp))
                .kitFocusRing(false, RoundedCornerShape(6.dp))
                .ppmClickable { model.openBranchPicker(project) }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PpmIcon(ProductIcon.GitBranch, 12.dp, tokens.accent.color)
                BasicText(branch, Modifier.widthIn(max = 140.dp), style = text.copy(color = tokens.accent.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!narrow && (git.ahead > 0 || git.behind > 0)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (git.ahead > 0) GitCount(ProductIcon.ArrowUp, git.ahead, text)
                    if (git.behind > 0) GitCount(ProductIcon.ArrowDown, git.behind, text)
                }
            }
            if (!narrow && synced) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PpmIcon(ProductIcon.Check, 12.dp, tokens.success.color)
                    BasicText("synced", style = text.copy(color = tokens.success.color), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun GitCount(icon: ProductIcon, count: Int, text: TextStyle) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        PpmIcon(icon, 12.dp, text.color)
        BasicText("$count", style = text, maxLines = 1)
    }
}

/**
 * Error and warning counts for the open project (`ProblemsStatus`, problems-status.tsx): shown
 * even at zero, opening the Problems panel in the dock when clicked.
 */
@Composable
private fun ProblemsStatus(model: AppModel, text: TextStyle) {
    val tokens = LocalPpmTokens.current
    val rev by SharedProblemsRegistry.revision.collectAsState()
    val totals = remember(model.selectedProject, rev) { SharedProblemsRegistry.problemCounts(model.selectedProject) }
    val hover = rememberHover()
    val lit = tokens.text.mix(tokens.text3, hover.lit.toDouble())
    val errColor = if (totals.errors > 0) tokens.error.color else lit.color
    val warnColor = if (totals.warnings > 0) tokens.warning.color else lit.color
    HoverTooltip("Problems", shortcut = "Ctrl+Shift+M") {
        Row(
            Modifier
                .heightIn(min = 32.dp)
                .clip(RoundedCornerShape(6.dp))
                .kitFocusRing(false, RoundedCornerShape(6.dp))
                .ppmClickable(
                    pressedColor = tokens.panel2.opacity(0.15).color,
                    hoverColor = tokens.panel2.opacity(0.15).color,
                    onClickLabel = "Open the Problems panel",
                    source = hover.source,
                ) { model.openProblems() }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PpmIcon(ProductIcon.CircleX, 11.dp, errColor)
                BasicText("${totals.errors}", style = text.copy(color = errColor), maxLines = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PpmIcon(ProductIcon.AlertTriangle, 11.dp, warnColor)
                BasicText("${totals.warnings}", style = text.copy(color = warnColor), maxLines = 1)
            }
        }
    }
}

/** Item 4 (`DbRowsStatus`): shown when the active tab is a DB tab with row count metadata. */
@Composable
private fun DbRowsStatus(model: AppModel, text: TextStyle) {
    val tokens = LocalPpmTokens.current
    val active = model.activeTab ?: return
    val isDbTab = active.type == "sqlite" || active.type == "postgres" || active.type == "database"
    val rowCount = active.metadata["rowCount"].string ?: active.metadata["rows"].string
    if (!isDbTab && rowCount == null) return
    val label = if (rowCount != null) "$rowCount rows" else active.title
    Row(
        Modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(6.dp))
            .ppmClickable(
                pressedColor = tokens.panel2.opacity(0.15).color,
                hoverColor = tokens.panel2.opacity(0.15).color,
                onClickLabel = "Database tab",
            ) { model.activate(active.id) }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PpmIcon(ProductIcon.Database, 11.dp, tokens.text2.color)
        BasicText(label, style = text.copy(color = tokens.text2.color), maxLines = 1)
    }
}

/**
 * The dock's toggle: its icon and the open project's dock tab count, in the accent while the dock
 * shows, and under a mouse `hover:bg-accent/15` (with the text lit while the dock is hidden).
 */
@Composable
private fun DockToggle(model: AppModel, text: TextStyle) {
    val tokens = LocalPpmTokens.current
    val visible = model.layout?.dock?.visible == true
    val count = model.dockTabs.size
    val hover = rememberHover()
    val color = if (visible) tokens.accent else tokens.text.mix(tokens.text3, hover.lit.toDouble())
    HoverTooltip(if (visible) "Hide panel" else "Show panel", shortcut = "Ctrl+J") {
        Row(
            Modifier
                .heightIn(min = 32.dp)
                .clip(RoundedCornerShape(6.dp))
                .kitFocusRing(false, RoundedCornerShape(6.dp))
                .ppmClickable(
                    pressedColor = tokens.panel2.opacity(0.15).color,
                    hoverColor = tokens.panel2.opacity(0.15).color,
                    onClickLabel = if (visible) "Hide panel" else "Show panel",
                    source = hover.source,
                ) { model.setDockVisible(!visible) }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PpmIcon(ProductIcon.PanelBottom, 11.dp, color.color)
            if (count > 0) BasicText("$count", style = text.copy(color = color.color))
        }
    }
}

/** `WakeLockStatusBarItem`: the screen is being kept on while a turn runs; tap opens the Wake Lock popover. */
@Composable
private fun Awake(
    model: AppModel,
    text: TextStyle,
    open: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val color = tokens.warning.color
    Box {
        HoverTooltip("Screen kept awake") {
            Row(
                Modifier
                    .heightIn(min = 32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .kitFocusRing(false, RoundedCornerShape(6.dp))
                    .ppmClickable(
                        pressedColor = tokens.panel2.opacity(0.25).color,
                        hoverColor = tokens.panel2.opacity(0.2).color,
                        onClickLabel = "Screen kept awake",
                        onClick = onToggle,
                    )
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PpmIcon(ProductIcon.Sun, 12.dp, color)
                BasicText("awake", style = text.copy(color = color), maxLines = 1)
            }
        }
        if (open) {
            DropdownAboveEnd(onDismiss = onDismiss, minWidth = 280.dp) {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BasicText(
                        "Screen is being kept on while this turn runs",
                        style = ppmText(12f, lineHeight = 18f).copy(color = tokens.text.color),
                    )
                    val btnShape = RoundedCornerShape(8.dp)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .clip(btnShape)
                            .background(tokens.panel2.color)
                            .cssBorder(tokens.borderSoft.color, shape = btnShape)
                            .ppmClickable(onClickLabel = "Let it sleep") {
                                onDismiss()
                                model.setKeepScreenAwakePref(false)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            "Let it sleep",
                            style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
                        )
                    }
                }
            }
        }
    }
}

/**
 * `ResourceStatusBar compact`: `CPU N%` in green, amber over 50 and red over 80, then `MEM`. Silent
 * until the first sample, and while the app is in the background the stream is let go.
 */
@Composable
private fun Resources(model: AppModel, text: TextStyle, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model.api, lifecycle) {
        if (model.api == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val release = model.retainResourceMonitor(wantProcesses = false)
            try {
                awaitCancellation()
            } finally {
                release()
            }
        }
    }
    if (!model.sharedMetricsConnected) return
    val snap = model.sharedMetricsSnapshot ?: return
    val cpuTotal = snap.system.cpu.total
    val memoryUsedMB = snap.system.mem.usedMB
    val cpu = when {
        cpuTotal > 80 -> tokens.error
        cpuTotal > 50 -> tokens.warning
        else -> tokens.success
    }
    // `toFixed`: a point whatever the locale.
    val mem = if (memoryUsedMB < 1024) "%.0fM".format(Locale.ROOT, memoryUsedMB) else "%.1fG".format(Locale.ROOT, memoryUsedMB / 1024)
    HoverTooltip("System Monitor") {
        Row(
            Modifier
                .heightIn(min = 32.dp)
                .clip(RoundedCornerShape(6.dp))
                .kitFocusRing(false, RoundedCornerShape(6.dp))
                .ppmClickable(
                    pressedColor = tokens.panel2.opacity(0.4).color,
                    hoverColor = tokens.panel2.opacity(0.4).color,
                    onClickLabel = "System Monitor",
                    onClick = onClick,
                )
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText("CPU %.0f%%".format(Locale.ROOT, cpuTotal), style = text.copy(color = cpu.color), maxLines = 1)
            BasicText("MEM $mem", style = text.copy(color = tokens.text2.color), maxLines = 1)
        }
    }
}

/**
 * The palette and the theme's name (only the palette on a narrow bar), lit under a mouse
 * (`hover:bg-accent/40 hover:text-text-primary`); opens the 360 dp six-theme picker (`SB1`).
 */
@Composable
private fun ThemePicker(
    model: AppModel,
    text: TextStyle,
    compact: Boolean,
    open: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val active = model.theme
    val hover = rememberHover()
    val lit = tokens.text.mix(tokens.text3, hover.lit.toDouble())
    Box {
        HoverTooltip("Color theme") {
            Row(
                Modifier
                    .heightIn(min = 32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .kitFocusRing(false, RoundedCornerShape(6.dp))
                    .ppmClickable(
                        pressedColor = tokens.panel2.opacity(0.4).color,
                        hoverColor = tokens.panel2.opacity(0.4).color,
                        onClickLabel = "Theme",
                        source = hover.source,
                        onClick = onToggle,
                    )
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PpmIcon(ProductIcon.Palette, 11.dp, (if (open) tokens.text else lit).color)
                if (!compact) {
                    BasicText(active.name, Modifier.widthIn(max = 90.dp), style = text.copy(color = lit.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (open) {
            DropdownAboveEnd(onDismiss = onDismiss, minWidth = 360.dp) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    BasicText(
                        "Theme",
                        style = ppmText(14f, FontWeight.SemiBold, lineHeight = 20f).copy(color = tokens.text.color),
                    )
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .ppmClickable(
                                hoverColor = tokens.panel2.color,
                                pressedColor = tokens.panel2.color,
                                onClickLabel = "Close",
                                onClick = onDismiss,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        PpmIcon(ProductIcon.X, 14.dp, tokens.text2.color)
                    }
                }
                for (theme in BuiltInThemes.all) {
                    val on = theme.id == active.id
                    MenuItem(
                        theme.name,
                        onClick = {
                            onDismiss()
                            model.scope.launch { model.pickTheme(theme) }
                        },
                        icon = { ThemeSwatch(theme) },
                        iconEnd = 0.dp,
                        trailing = { PpmIcon(ProductIcon.Check, 16.dp, tokens.text.color, Modifier.alpha(if (on) 1f else 0f)) },
                    )
                }
            }
        }
    }
}
