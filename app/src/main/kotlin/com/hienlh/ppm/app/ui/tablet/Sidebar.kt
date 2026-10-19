package com.hienlh.ppm.app.ui.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.ProjectAvatar
import com.hienlh.ppm.app.ui.ProjectSheet
import com.hienlh.ppm.app.ui.SessionHistoryPanel
import com.hienlh.ppm.app.ui.unreadSessions
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import kotlin.math.roundToInt

/**
 * The sidebar (sidebar.tsx): the PPM wordmark and the project switcher on top, the section rail
 * and the open section below, its right edge dragged to resize it; folded, only the rail. Chat
 * History is the one section this app has, so it is the one on the rail.
 */
@Composable
fun Sidebar(model: AppModel) {
    val tokens = LocalPpmTokens.current
    var projectsOpen by remember { mutableStateOf(false) }
    if (model.sidebarCollapsed) {
        Column(Modifier.width(RAIL_WIDTH).fillMaxHeight().background(tokens.rail.color).edge(Edge.Right, tokens.border.color)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(BAR_HEIGHT)
                    .edge(Edge.Bottom, tokens.border.color)
                    .ppmClickable(pressedColor = tokens.panel2.color, onClickLabel = "Expand sidebar") { model.toggleSidebar() },
                contentAlignment = Alignment.Center,
            ) { PpmIcon(ProductIcon.PanelLeftOpen, 16.dp, tokens.text2.color) }
            NavSectionRail(model, Modifier.weight(1f))
        }
    } else {
        Box(Modifier.width(model.sidebarWidth.dp).fillMaxHeight().background(tokens.panel.color).edge(Edge.Right, tokens.border.color)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(BAR_HEIGHT).edge(Edge.Bottom, tokens.border.color)) {
                    Wordmark(model)
                    Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) {
                        ProjectSwitcherButton(model, open = projectsOpen) { projectsOpen = true }
                    }
                }
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    NavSectionRail(model, Modifier.edge(Edge.Right, tokens.border.color))
                    Box(Modifier.weight(1f).fillMaxHeight()) { HistorySection(model) }
                }
            }
            ResizeHandle(model, Modifier.align(Alignment.CenterEnd))
        }
    }
    // Until the switcher's own flyout is built, the phone's project sheet.
    if (projectsOpen) ProjectSheet(model) { projectsOpen = false }
}

/** `w-[52px] border-r`, the wordmark that folds the sidebar: `text-sm font-extrabold text-primary tracking-tight`. */
@Composable
private fun Wordmark(model: AppModel) {
    val tokens = LocalPpmTokens.current
    Box(
        Modifier
            .width(RAIL_WIDTH)
            .fillMaxHeight()
            .edge(Edge.Right, tokens.border.color)
            .ppmClickable(pressedColor = tokens.panel2.color, onClickLabel = "Toggle sidebar") { model.toggleSidebar() },
        contentAlignment = Alignment.Center,
    ) {
        BasicText("PPM", style = ppmText(14f, FontWeight.ExtraBold, lineHeight = 14f).copy(color = tokens.accent.color, letterSpacing = (-0.35).sp))
    }
}

/**
 * The project switcher's button: the project's squircle, its name over its path, and the
 * up-down chevrons (`h-8 px-2 rounded-lg gap-[9px]`, lit while its list is open).
 */
@Composable
private fun ProjectSwitcherButton(model: AppModel, open: Boolean, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(12.dp)
    val project = model.currentProject
    Row(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(shape)
            .background(if (open) tokens.panel2.color else Color.Transparent)
            .border(1.dp, if (open) tokens.border.color else Color.Transparent, shape)
            .ppmClickable(pressedColor = tokens.panel2.color, shape = shape, onClickLabel = "Switch project", onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (project != null) {
            Box {
                ProjectAvatar(model, project, 24.dp, square = true)
                // `-top-0.5 -right-0.5 size-2 border-2 border-background`, in the most urgent unread kind's colour.
                model.urgentUnread(project.name, model.unreadSessions())?.let { type ->
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(2.dp, (-2).dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(tokens.notification(type).color)
                            .border(2.dp, tokens.bgSolid.color, CircleShape),
                    )
                }
            }
        } else {
            Box(Modifier.size(24.dp).clip(CircleShape).background(tokens.panel2.color))
        }
        Column(Modifier.weight(1f)) {
            BasicText(
                project?.name ?: "Select project",
                style = ppmText(13f, FontWeight.SemiBold, lineHeight = 16.25f).copy(color = tokens.text.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!project?.path.isNullOrEmpty()) {
                BasicText(
                    project!!.path,
                    style = ppmText(10f, kind = FontKind.MONO, lineHeight = 12.5f).copy(color = tokens.text3.color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        PpmIcon(ProductIcon.ChevronsUpDown, 14.dp, tokens.text3.color)
    }
}

/** The sidebar's right edge (`absolute right-0 w-1 cursor-col-resize`), wider to the finger than to the eye. */
@Composable
private fun ResizeHandle(model: AppModel, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    val density = LocalDensity.current.density
    var held by remember { mutableStateOf(false) }
    // The width the drag has reached, in pixels; the sidebar follows it whole dp by whole dp.
    var reached by remember { mutableFloatStateOf(0f) }
    Box(
        modifier
            .fillMaxHeight()
            .width(12.dp)
            .screenDrag(
                horizontal = true,
                onStart = {
                    held = true
                    reached = model.sidebarWidth * density
                },
                onDrag = { delta ->
                    reached += delta
                    model.resizeSidebar((reached / density).roundToInt())
                },
                onEnd = {
                    held = false
                    model.saveSidebarWidth()
                },
            ),
    ) {
        if (held) Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(4.dp).background(tokens.accent.opacity(0.5).color))
    }
}

/** Chat History, or what to do when there is no project. */
@Composable
private fun HistorySection(model: AppModel) {
    val tokens = LocalPpmTokens.current
    val list = model.sidebarList
    val search = model.sidebarSearch
    if (list != null && search != null) {
        SessionHistoryPanel(model, list, search, onNavigate = {})
    } else {
        Box(Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(16.dp), contentAlignment = Alignment.Center) {
            BasicText(
                "Select a project to view chat history",
                style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color, textAlign = TextAlign.Center),
            )
        }
    }
}

/**
 * The section rail (nav-section-rail.tsx): the sections, then the footer utilities. Tapping a
 * section opens it — unfolding the sidebar — and tapping the open one folds the sidebar.
 */
@Composable
private fun NavSectionRail(model: AppModel, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val unread = model.unreadSessions()
    Column(modifier.width(RAIL_WIDTH).fillMaxHeight()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 3.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // The open section stays lit while the sidebar is folded, as on the web; History is the only one.
            NavItem(
                ProductIcon.BotMessageSquare,
                "Chat History",
                active = true,
                badge = model.unread(model.selectedProject, unread).size,
            ) { model.toggleSidebar() }
        }
        Column(
            Modifier.fillMaxWidth().edge(Edge.Top, tokens.border.color).padding(horizontal = 3.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            FooterUtil(ProductIcon.Settings, "Settings") { model.openSettings() }
        }
    }
}

/**
 * A section (`size-[38px] rounded-[9px]`): the open one on the accent wash, the accent's 2 px bar
 * inset along its left edge and bending with its corners.
 */
@Composable
private fun NavItem(icon: ProductIcon, label: String, active: Boolean, badge: Int, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(9.dp)
    Box(
        Modifier
            .size(38.dp)
            .clip(shape)
            .then(
                if (active) {
                    Modifier.drawBehind {
                        val radius = CornerRadius(9.dp.toPx())
                        val tile = Path().apply { addRoundRect(RoundRect(size.toRect(), radius)) }
                        drawPath(tile, tokens.accentWash.color)
                        // `shadow-[inset_2px_0_0_var(--accent)]`: the tile minus itself moved 2 px right.
                        val moved = Path().apply { addRoundRect(RoundRect(size.toRect().translate(Offset(2.dp.toPx(), 0f)), radius)) }
                        drawPath(Path.combine(PathOperation.Difference, tile, moved), tokens.accent.color)
                    }
                } else {
                    Modifier
                },
            )
            .ppmClickable(pressedColor = tokens.panel2.color, shape = shape, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(icon, 18.dp, (if (active) tokens.accent else tokens.text3).color)
        if (badge > 0) {
            // `absolute top-1 right-1 min-w-[16px] h-4 px-1 rounded-full bg-primary text-[10px] font-medium`.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 4.dp)
                    .height(16.dp)
                    .widthIn(min = 16.dp)
                    .clip(CircleShape)
                    .background(tokens.accent.color)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    if (badge > 99) "99+" else "$badge",
                    style = ppmText(10f, FontWeight.Medium, lineHeight = 10f).copy(color = tokens.accentFg.color),
                )
            }
        }
    }
}

/** A footer utility (`size-8 rounded-[7px]`, a 16 px icon in the subtle colour). */
@Composable
private fun FooterUtil(icon: ProductIcon, label: String, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(7.dp)
    Box(
        Modifier.size(32.dp).clip(shape).ppmClickable(pressedColor = tokens.panel2.color, shape = shape, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { PpmIcon(icon, 16.dp, tokens.text3.color) }
}

/** The rail's width, and the top bar's height, which the tab bars share. */
val RAIL_WIDTH = 52.dp
val BAR_HEIGHT = 41.dp
