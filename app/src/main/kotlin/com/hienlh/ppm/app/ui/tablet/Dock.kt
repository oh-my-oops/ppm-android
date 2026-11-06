package com.hienlh.ppm.app.ui.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.FocusAnchor
import com.hienlh.ppm.app.FocusStartAnchor
import com.hienlh.ppm.app.focusStartArea
import com.hienlh.ppm.app.ui.DropdownBelowEnd
import com.hienlh.ppm.app.ui.DropdownBelowStart
import com.hienlh.ppm.app.ui.MenuIcon
import com.hienlh.ppm.app.ui.MenuItem
import com.hienlh.ppm.app.ui.TabBody
import com.hienlh.ppm.app.ui.TabIcon
import com.hienlh.ppm.app.ui.tabTitle
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

private val POSITIONS = listOf(
    Triple("left", "Left", ProductIcon.PanelLeft),
    Triple("bottom", "Bottom", ProductIcon.PanelBottom),
    Triple("right", "Right", ProductIcon.PanelRight),
)

/** `DOCK_PILL_CAP`: a side dock shows this many pills, the active one among them, the rest behind "+N". */
private const val PILL_CAP = 2

/**
 * The dock (dock-panel.tsx): one panel shared by every project, of which it shows the open
 * project's tabs — its header of pills over the tab in front, with a new-terminal button.
 */
@Composable
fun DockPanel(model: AppModel, state: WorkspaceState, borderEdge: Edge) {
    val tokens = LocalPpmTokens.current
    val tabs = model.dockTabs
    val activeId = state.dockPanel?.activeTabId
    val dockAnchor = remember { FocusAnchor() }
    val bodyAnchor = remember { FocusAnchor() }
    Column(Modifier.fillMaxSize().focusStartArea(dockAnchor).background(tokens.panel.color).edge(borderEdge, tokens.border.color)) {
        FocusStartAnchor(dockAnchor)
        DockHeader(model, state, tabs.map { it.id to it }, activeId)
        Box(Modifier.weight(1f).fillMaxWidth().focusStartArea(bodyAnchor)) {
            FocusStartAnchor(bodyAnchor)
            val active = tabs.firstOrNull { it.id == activeId }
            if (active != null) key(active.id) { TabBody(model, active) }
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
    tabs: List<Pair<String, com.hienlh.ppm.state.workspace.WorkspaceTab>>,
    activeId: String?,
) {
    val tokens = LocalPpmTokens.current
    val side = model.wide && model.dockPosition != "bottom"
    val ids = tabs.map { it.first }
    val byId = tabs.toMap()
    val visible = if (!side || ids.size <= PILL_CAP) {
        ids
    } else {
        val keep = linkedSetOf<String>()
        if (activeId != null && activeId in ids) keep += activeId
        for (id in ids) if (keep.size < PILL_CAP) keep += id
        ids.filter { it in keep }
    }
    val overflow = ids.filter { it !in visible }
    Row(
        Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (id in visible) {
                val tab = byId[id] ?: continue
                val active = id == activeId
                DockPill(model, tab, active, showLabel = !side || active)
            }
            if (overflow.isNotEmpty()) {
                var open by remember { mutableStateOf(false) }
                Box {
                    val shape = CircleShape
                    Box(
                        Modifier
                            .height(24.dp)
                            .clip(shape)
                            .ppmClickable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, fade = false) { open = true }
                            .cssBorder(tokens.border.color, shape)
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) { BasicText("+${overflow.size}", style = ppmText(10f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text2.color)) }
                    if (open) {
                        DropdownBelowStart(onDismiss = { open = false }, minWidth = 160.dp) {
                            for (id in overflow) {
                                val tab = byId[id] ?: continue
                                MenuItem(tabTitle(model, tab), onClick = {
                                    open = false
                                    model.activate(id, DOCK_PANEL_ID)
                                }, icon = { TabIcon(tab, tokens.text2, 14.dp) }, iconEnd = 0.dp)
                            }
                        }
                    }
                }
            }
        }
        HeaderButton(ProductIcon.Plus, "New terminal") { model.openTerminal(inDock = true) }
        if (model.wide) PositionMenu(model)
        Box(Modifier.width(1.dp).height(16.dp).background(tokens.border.color))
        HeaderButton(if (state.dockExpanded) ProductIcon.Minimize2 else ProductIcon.Maximize2, if (state.dockExpanded) "Restore panel" else "Maximize panel") {
            model.toggleDockExpanded()
        }
        HeaderButton(ProductIcon.ChevronDown, "Hide panel") { model.setDockVisible(false) }
    }
}

/**
 * `h-6 rounded-full border`: the active pill in the accent on its wash, a label only where there is
 * room; another lit under a mouse (`hover:bg-surface-elevated`), its close button's X too.
 */
@Composable
private fun DockPill(model: AppModel, tab: com.hienlh.ppm.state.workspace.WorkspaceTab, active: Boolean, showLabel: Boolean) {
    val tokens = LocalPpmTokens.current
    val shape = CircleShape
    val color = if (active) tokens.accent else tokens.text2
    Row(
        Modifier
            .height(24.dp)
            .clip(shape)
            .background(if (active) tokens.accent.opacity(0.1).color else Color.Transparent)
            .ppmClickable(pressedColor = tokens.panel2.color, hoverColor = if (active) Color.Transparent else tokens.panel2.color) { model.activate(tab.id, DOCK_PANEL_ID) }
            .cssBorder((if (active) tokens.accent else tokens.border).color, shape)
            // `px-2.5`, `px-1.5` without a label; the close button's `-mr-1` takes 4 px back.
            .padding(start = if (showLabel) 10.dp else 6.dp, end = if (!showLabel) 6.dp else if (tab.closable) 6.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TabIcon(tab, color, 12.dp)
        if (showLabel) {
            BasicText(
                tabTitle(model, tab),
                Modifier.widthIn(max = 120.dp),
                style = ppmText(11f, FontWeight.Medium, lineHeight = 16.5f).copy(color = color.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (tab.closable) {
                val hover = rememberHover(instant = true)
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .ppmClickable(
                            pressedColor = tokens.panel2.color,
                            hoverColor = tokens.panel2.color,
                            fade = false,
                            onClickLabel = "Close ${tab.title}",
                            source = hover.source,
                        ) { model.closeTab(tab.id, DOCK_PANEL_ID) },
                    contentAlignment = Alignment.Center,
                ) { PpmIcon(ProductIcon.X, 12.dp, tokens.text.mix(color, hover.lit.toDouble()).color) }
            }
        }
    }
}

/** The position menu's trigger (`h-6 px-1.5 rounded`: the position's icon and a small chevron) and its menu. */
@Composable
private fun PositionMenu(model: AppModel) {
    val tokens = LocalPpmTokens.current
    var open by remember { mutableStateOf(false) }
    val current = POSITIONS.firstOrNull { it.first == model.dockPosition } ?: POSITIONS[1]
    val hover = rememberHover(instant = true)
    val tint = tokens.text.mix(tokens.text3, hover.lit.toDouble()).color
    Box {
        Row(
            Modifier
                .height(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .ppmClickable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, fade = false, onClickLabel = "Panel position", source = hover.source) { open = true }
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            PpmIcon(current.third, 14.dp, tint)
            PpmIcon(ProductIcon.ChevronDown, 10.dp, tint)
        }
        if (open) {
            DropdownBelowEnd(onDismiss = { open = false }) {
                // `DropdownMenuLabel text-[10px] uppercase tracking-wide text-text-subtle`.
                BasicText(
                    "PANEL POSITION",
                    Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    style = ppmText(10f, FontWeight.Medium, lineHeight = 15f).copy(color = tokens.text3.color, letterSpacing = 0.025.em),
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
            }
        }
    }
}

/** `size-7 rounded text-text-subtle hover:bg-surface-elevated hover:text-foreground`, a 14 px icon. */
@Composable
private fun HeaderButton(icon: ProductIcon, label: String, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover(instant = true)
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(4.dp))
            .ppmClickable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, fade = false, onClickLabel = label, source = hover.source, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { PpmIcon(icon, 14.dp, tokens.text.mix(tokens.text3, hover.lit.toDouble()).color) }
}
