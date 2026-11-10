package com.hienlh.ppm.app.ui.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.FocusAnchor
import com.hienlh.ppm.app.FocusStartAnchor
import com.hienlh.ppm.app.LocalRedesign
import com.hienlh.ppm.app.focusStartArea
import com.hienlh.ppm.app.ui.DropdownBelowEnd
import com.hienlh.ppm.app.ui.DropdownBelowStart
import com.hienlh.ppm.app.ui.LocalTerminalKeyRowOverride
import com.hienlh.ppm.app.ui.MenuIcon
import com.hienlh.ppm.app.ui.MenuItem
import com.hienlh.ppm.app.ui.TabBody
import com.hienlh.ppm.app.ui.TabIcon
import com.hienlh.ppm.app.ui.TerminalKeyRowOverrideState
import com.hienlh.ppm.app.ui.tabTitle
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.state.workspace.DOCK_PANEL_ID
import com.hienlh.ppm.state.workspace.WorkspaceState
import com.hienlh.ppm.state.workspace.WorkspaceTab

private val POSITIONS = listOf(
    Triple("left", "Left", ProductIcon.PanelLeft),
    Triple("bottom", "Bottom", ProductIcon.PanelBottom),
    Triple("right", "Right", ProductIcon.PanelRight),
)

/** `DOCK_PILL_CAP`: a side dock shows this many pills, the active one among them, the rest behind "+N". */
internal const val DOCK_PILL_CAP = 2

/**
 * Returns the ordered dock positions (`left`, `bottom`, `right`) with their display labels and icons
 * (`Tablet 4 · Section 8`, Note 80).
 */
internal fun dockPositionOptions(): List<Triple<String, String, ProductIcon>> = POSITIONS

/**
 * Splits [tabIds] into `visible to overflow` for the dock header (`Tablet 4` Note 79):
 * - On a bottom dock (`!sideDock`) or when `tabIds.size <= pillCap`, every pill is visible.
 * - On a side dock (`sideDock == true`), caps visible pills at [pillCap] (default 2), always
 *   keeping [activeId] visible, and places the rest into `overflow` (`+N`).
 */
internal fun visibleDockPillIds(
    tabIds: List<String>,
    activeId: String?,
    sideDock: Boolean,
    pillCap: Int = DOCK_PILL_CAP,
): Pair<List<String>, List<String>> {
    if (!sideDock || tabIds.size <= pillCap) {
        return tabIds to emptyList()
    }
    val keep = linkedSetOf<String>()
    if (activeId != null && activeId in tabIds) keep += activeId
    for (id in tabIds) {
        if (keep.size < pillCap) keep += id
    }
    val visible = tabIds.filter { it in keep }
    val overflow = tabIds.filter { it !in keep }
    return visible to overflow
}

/**
 * The dock (dock-panel.tsx): one panel shared by every project, of which it shows the open
 * project's tabs — its header of pills over the tab in front, with a new-terminal button.
 */
@Composable
fun DockPanel(model: AppModel, state: WorkspaceState, borderEdge: Edge) {
    val tokens = LocalPpmTokens.current
    val tabs = model.dockTabs
    val activeId = state.dockPanel?.activeTabId ?: tabs.lastOrNull()?.id
    val dockAnchor = remember { FocusAnchor() }
    val bodyAnchor = remember { FocusAnchor() }
    val keyRowOverride = remember { TerminalKeyRowOverrideState() }

    CompositionLocalProvider(LocalTerminalKeyRowOverride provides keyRowOverride) {
        Column(
            Modifier
                .fillMaxSize()
                .focusStartArea(dockAnchor)
                .background(tokens.panel.color)
                .edge(borderEdge, tokens.border.color)
                .testTag("tablet-dock-panel"),
        ) {
            FocusStartAnchor(dockAnchor)
            DockHeader(
                model = model,
                state = state,
                tabs = tabs.map { it.id to it },
                activeId = activeId,
                keyRowOverride = keyRowOverride,
            )
            Box(Modifier.weight(1f).fillMaxWidth().focusStartArea(bodyAnchor)) {
                FocusStartAnchor(bodyAnchor)
                val active = tabs.firstOrNull { it.id == activeId } ?: tabs.lastOrNull()
                if (active != null) {
                    key(active.id) { TabBody(model, active) }
                } else {
                    DockEmptyState(onOpenTerminal = { model.openTerminal(inDock = true) })
                }
            }
        }
    }
}

/**
 * Empty state shown when the dock has no open terminals (`Tablet 4 · Section 8 · State: Empty dock`).
 */
@Composable
internal fun DockEmptyState(
    onOpenTerminal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val buttonShape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .padding(24.dp)
            .testTag("dock-empty-state"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PpmIcon(ProductIcon.TerminalSquare, 28.dp, tokens.text3.color)
            BasicText(
                text = "No terminals",
                style = ppmText(14f, FontWeight.SemiBold, lineHeight = 19f).copy(color = tokens.text.color),
            )
            BasicText(
                text = "Open one here, or let the AI type into one.",
                style = ppmText(12.5f, FontWeight.Normal, lineHeight = 17f)
                    .copy(color = tokens.text3.color, textAlign = TextAlign.Center),
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .height(40.dp)
                    .clip(buttonShape)
                    .background(tokens.accent.color, buttonShape)
                    .ppmClickable(
                        shape = buttonShape,
                        onClickLabel = "Open Terminal",
                        onClick = onOpenTerminal,
                    )
                    .padding(horizontal = 16.dp)
                    .testTag("dock-empty-open-terminal"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PpmIcon(ProductIcon.Plus, 16.dp, tokens.accentFg.color)
                BasicText(
                    text = "Open Terminal",
                    style = ppmText(13f, FontWeight.SemiBold, lineHeight = 17f).copy(color = tokens.accentFg.color),
                )
            }
        }
    }
}

/**
 * dock-header.tsx: the pills, the position menu, a divider, Maximize / Restore and Hide. At the
 * bottom every pill is labelled; on a side, only the active one, two pills at most and "+N" for
 * the rest.
 */
@Composable
private fun DockHeader(
    model: AppModel,
    state: WorkspaceState,
    tabs: List<Pair<String, WorkspaceTab>>,
    activeId: String?,
    keyRowOverride: TerminalKeyRowOverrideState,
) {
    val tokens = LocalPpmTokens.current
    val redesign = LocalRedesign.current
    val side = model.wide && model.dockPosition != "bottom"
    val ids = tabs.map { it.first }
    val byId = tabs.toMap()
    val (visible, overflow) = visibleDockPillIds(ids, activeId, side, DOCK_PILL_CAP)
    val headerHeight = if (redesign) 42.dp else 32.dp
    val pillHeight = if (redesign) 32.dp else 24.dp

    Row(
        Modifier
            .fillMaxWidth()
            .height(headerHeight)
            .cssBorder(tokens.border.color, sides = CssSides.Bottom)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (id in visible) {
                val tab = byId[id] ?: continue
                val active = id == activeId
                DockPill(
                    model = model,
                    tab = tab,
                    active = active,
                    showLabel = !side || active,
                    pillHeight = pillHeight,
                )
            }
            if (overflow.isNotEmpty()) {
                var open by remember { mutableStateOf(false) }
                Box {
                    val shape = CircleShape
                    Box(
                        Modifier
                            .height(pillHeight)
                            .clip(shape)
                            .ppmClickable(
                                pressedColor = tokens.panel2.color,
                                hoverColor = tokens.panel2.color,
                                fade = false,
                                onClickLabel = "More terminals (+${overflow.size})",
                            ) { open = true }
                            .cssBorder(tokens.border.color, shape)
                            .padding(horizontal = 10.dp)
                            .testTag("dock-overflow-pill"),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            "+${overflow.size}",
                            style = ppmText(11f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 15f)
                                .copy(color = tokens.text2.color),
                        )
                    }
                    if (open) {
                        DropdownBelowStart(onDismiss = { open = false }, minWidth = 180.dp) {
                            for (id in overflow) {
                                val tab = byId[id] ?: continue
                                MenuItem(
                                    tabTitle(model, tab),
                                    onClick = {
                                        open = false
                                        model.activate(id, DOCK_PANEL_ID)
                                    },
                                    icon = { TabIcon(tab, tokens.text2, 14.dp) },
                                    iconEnd = 0.dp,
                                )
                            }
                        }
                    }
                }
            }
        }
        HeaderButton(ProductIcon.Plus, "New terminal") { model.openTerminal(inDock = true) }
        HeaderButton(
            icon = ProductIcon.Keyboard,
            label = if (keyRowOverride.forceVisible) "Hide the key row" else "Show the key row",
            active = keyRowOverride.forceVisible,
        ) {
            keyRowOverride.toggle()
        }
        if (model.wide) PositionMenu(model, state)
        Box(Modifier.width(1.dp).height(16.dp).background(tokens.border.color))
        HeaderButton(
            icon = if (state.dockExpanded) ProductIcon.Minimize2 else ProductIcon.Maximize2,
            label = if (state.dockExpanded) "Restore panel" else "Maximize panel",
            active = state.dockExpanded,
        ) {
            model.toggleDockExpanded()
        }
        HeaderButton(ProductIcon.ChevronDown, "Hide panel") { model.setDockVisible(false) }
    }
}

/**
 * `h-6 rounded-full border` (or 32 dp in redesign): the active pill in the accent on its wash, a
 * label only where there is room; another lit under a mouse (`hover:bg-surface-elevated`), its
 * close button's X too.
 */
@Composable
private fun DockPill(
    model: AppModel,
    tab: WorkspaceTab,
    active: Boolean,
    showLabel: Boolean,
    pillHeight: androidx.compose.ui.unit.Dp = 24.dp,
) {
    val tokens = LocalPpmTokens.current
    val shape = CircleShape
    val color = if (active) tokens.accent else tokens.text2
    val terminalSession = remember(model, tab.id) {
        if (tab.type == "terminal") model.terminalOf(tab.id) else null
    }
    val terminalState = terminalSession?.controller?.state?.collectAsState()?.value

    Row(
        Modifier
            .height(pillHeight)
            .clip(shape)
            .background(if (active) tokens.accent.opacity(0.12).color else Color.Transparent)
            .ppmClickable(
                pressedColor = tokens.panel2.color,
                hoverColor = if (active) Color.Transparent else tokens.panel2.color,
            ) { model.activate(tab.id, DOCK_PANEL_ID) }
            .cssBorder((if (active) tokens.accent else tokens.border).color, shape)
            // `px-2.5`, `px-1.5` without a label; the close button's `-mr-1` takes 4 px back.
            .padding(
                start = if (showLabel) 10.dp else 8.dp,
                end = if (!showLabel) 8.dp else if (tab.closable) 6.dp else 10.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TabIcon(tab, color, 13.dp)
        if (terminalState != null) {
            val dotColor = when {
                terminalState.exited -> tokens.error.color
                terminalState.reconnecting -> tokens.warning.color
                terminalState.connected -> tokens.success.color
                else -> tokens.text3.color
            }
            Box(Modifier.size(6.dp).background(dotColor, CircleShape))
        }
        if (showLabel) {
            BasicText(
                tabTitle(model, tab),
                Modifier.widthIn(max = 132.dp),
                style = ppmText(11.5f, FontWeight.Medium, lineHeight = 16.5f).copy(color = color.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (tab.closable) {
                val hover = rememberHover(instant = true)
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .ppmClickable(
                            pressedColor = tokens.panel2.color,
                            hoverColor = tokens.panel2.color,
                            fade = false,
                            onClickLabel = "Close ${tab.title}",
                            source = hover.source,
                        ) { model.closeTab(tab.id, DOCK_PANEL_ID) }
                        .semantics { contentDescription = "Close ${tab.title}" },
                    contentAlignment = Alignment.Center,
                ) { PpmIcon(ProductIcon.X, 12.dp, tokens.text.mix(color, hover.lit.toDouble()).color) }
            }
        }
    }
}

/** The position menu's trigger (`h-6 px-1.5 rounded`: the position's icon and a small chevron) and its menu. */
@Composable
private fun PositionMenu(model: AppModel, state: WorkspaceState) {
    val tokens = LocalPpmTokens.current
    var open by remember { mutableStateOf(false) }
    val current = POSITIONS.firstOrNull { it.first == model.dockPosition } ?: POSITIONS[1]
    val hover = rememberHover(instant = true)
    val tint = tokens.text.mix(tokens.text3, hover.lit.toDouble()).color
    Box {
        Row(
            Modifier
                .height(32.dp)
                .clip(RoundedCornerShape(6.dp))
                .ppmClickable(
                    pressedColor = tokens.panel2.color,
                    hoverColor = tokens.panel2.color,
                    fade = false,
                    onClickLabel = "Panel position",
                    source = hover.source,
                ) { open = true }
                .padding(horizontal = 6.dp)
                .semantics { contentDescription = "Panel position" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            PpmIcon(current.third, 15.dp, tint)
            PpmIcon(ProductIcon.ChevronDown, 10.dp, tint)
        }
        if (open) {
            DropdownBelowEnd(onDismiss = { open = false }, minWidth = 180.dp) {
                // `DropdownMenuLabel text-[10px] uppercase tracking-wide text-text-subtle`.
                BasicText(
                    "PANEL POSITION",
                    Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    style = ppmText(10f, FontWeight.Medium, lineHeight = 15f)
                        .copy(color = tokens.text3.color, letterSpacing = 0.025.em),
                )
                for ((position, label, icon) in POSITIONS) {
                    val on = position == model.dockPosition
                    Box(Modifier.background(if (on) tokens.accent.opacity(0.1).color else Color.Transparent, RoundedCornerShape(6.dp))) {
                        MenuItem(
                            label,
                            onClick = {
                                open = false
                                model.pickDockPosition(position)
                            },
                            icon = { MenuIcon(icon, if (on) tokens.accent else null) },
                            iconEnd = 0.dp,
                            color = if (on) tokens.accent else null,
                            trailing = if (on) ({ PpmIcon(ProductIcon.Check, 14.dp, tokens.accent.color) }) else null,
                        )
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .height(1.dp)
                        .background(tokens.border.color),
                )
                MenuItem(
                    if (state.dockExpanded) "Restore panel" else "Maximize panel",
                    onClick = {
                        open = false
                        model.toggleDockExpanded()
                    },
                    icon = {
                        MenuIcon(
                            if (state.dockExpanded) ProductIcon.Minimize2 else ProductIcon.Maximize2,
                            null,
                        )
                    },
                    iconEnd = 0.dp,
                )
                MenuItem(
                    "Hide panel",
                    onClick = {
                        open = false
                        model.setDockVisible(false)
                    },
                    icon = { MenuIcon(ProductIcon.ChevronDown, null) },
                    iconEnd = 0.dp,
                    trailing = {
                        BasicText(
                            "Ctrl+'",
                            style = ppmText(10.5f, FontWeight.Normal, FontKind.MONO, lineHeight = 14f)
                                .copy(color = tokens.text3.color),
                        )
                    },
                )
            }
        }
    }
}

/** `size-8 rounded text-text-subtle hover:bg-surface-elevated hover:text-foreground`, a 15 px icon. */
@Composable
private fun HeaderButton(
    icon: ProductIcon,
    label: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover(instant = true)
    val bgColor = if (active) tokens.accent.opacity(0.14).color else Color.Transparent
    val iconColor = if (active) {
        tokens.accent.color
    } else {
        tokens.text.mix(tokens.text3, hover.lit.toDouble()).color
    }
    Box(
        Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor, RoundedCornerShape(6.dp))
            .ppmClickable(
                pressedColor = tokens.panel2.color,
                hoverColor = tokens.panel2.color,
                fade = false,
                onClickLabel = label,
                source = hover.source,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { PpmIcon(icon, 15.dp, iconColor) }
}
