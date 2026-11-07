package com.hienlh.ppm.app.ui.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.FocusAnchor
import com.hienlh.ppm.app.FocusStartAnchor
import com.hienlh.ppm.app.focusStartArea
import com.hienlh.ppm.app.ui.FeatureBadge
import com.hienlh.ppm.app.ui.FeatureBadgeKind
import com.hienlh.ppm.app.ui.GroupList
import com.hienlh.ppm.app.ui.ProjectAvatar
import com.hienlh.ppm.app.ui.SessionHistoryPanel
import com.hienlh.ppm.app.ui.unreadSessions
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.WebCursor
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.protocol.ProjectInfo
import com.hienlh.ppm.protocol.SessionUnread
import kotlin.math.roundToInt

/**
 * The sidebar (sidebar.tsx): the PPM wordmark and the project switcher on top, the section rail
 * and the open section below, its right edge dragged to resize it; folded, only the rail. Chat
 * History, Explorer and Teams share the same section rail as the web.
 */
@Composable
fun Sidebar(model: AppModel) {
    val tokens = LocalPpmTokens.current
    // The switcher button on the screen while its flyout is open, and how far down the screen the shell reaches.
    var flyoutAt by remember { mutableStateOf<Rect?>(null) }
    var shellBottom by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    val switcher = remember { Placed() }
    val sidebarAnchor = remember { FocusAnchor() }
    if (model.sidebarCollapsed) {
        Column(Modifier.width(RAIL_WIDTH).fillMaxHeight().focusStartArea(sidebarAnchor).background(tokens.rail.color).edge(Edge.Right, tokens.border.color)) {
            FocusStartAnchor(sidebarAnchor)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(BAR_HEIGHT)
                    .edge(Edge.Bottom, tokens.border.color)
                    .semantics { contentDescription = "Expand sidebar" }
                    .ppmClickable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, onClickLabel = "Expand sidebar") { model.toggleSidebar() },
                contentAlignment = Alignment.Center,
            ) { PpmIcon(ProductIcon.PanelLeftOpen, 16.dp, tokens.text2.color) }
            NavSectionRail(model, Modifier.weight(1f))
        }
    } else {
        val resize = remember { SidebarResize() }
        Box(
            Modifier
                .width(model.sidebarWidth.dp)
                .fillMaxHeight()
                .focusStartArea(sidebarAnchor)
                .onGloballyPositioned { shellBottom = it.positionOnScreen().y + it.size.height }
                .resizesSidebar(model, resize)
                .background(tokens.panel.color)
                .edge(Edge.Right, tokens.border.color),
        ) {
            Column(Modifier.fillMaxSize()) {
                FocusStartAnchor(sidebarAnchor)
                Row(Modifier.fillMaxWidth().height(BAR_HEIGHT).edge(Edge.Bottom, tokens.border.color)) {
                    Wordmark(model)
                    Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) {
                        ProjectSwitcherButton(model, open = flyoutAt != null, Modifier.onGloballyPositioned { switcher.coordinates = it }) {
                            switcher.coordinates?.let { flyoutAt = Rect(it.positionOnScreen(), it.size.toSize()) }
                        }
                    }
                }
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    NavSectionRail(model, Modifier.edge(Edge.Right, tokens.border.color))
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        val sec = model.sidebarSection
                        when {
                            sec == "teams" -> GroupList(model)
                            sec == "explorer" -> com.hienlh.ppm.app.ui.files.ExplorerBody(model)
                            sec == "search" -> com.hienlh.ppm.app.ui.files.SearchBody(model)
                            sec == "git" -> com.hienlh.ppm.app.ui.git.GitStatusPanel(model)
                            sec.startsWith("ext:") -> {
                                val viewId = sec.removePrefix("ext:")
                                val contribs = model.extensions?.contributions?.collectAsState()?.value
                                val viewName = contribs?.views?.values?.flatten()?.firstOrNull { it.id == viewId }?.name ?: viewId
                                com.hienlh.ppm.app.ui.extensions.ExtensionTreeViewPanel(model, viewId, viewName)
                            }
                            else -> HistorySection(model)
                        }
                    }
                }
            }
            ResizeLine(resize, Modifier.align(Alignment.CenterEnd))
        }
    }
    flyoutAt?.let { ProjectFlyout(model, it, shellBottom) { flyoutAt = null } }
}

/** `w-[52px] border-r hover:bg-surface-elevated`, the wordmark that folds the sidebar: `text-sm font-extrabold text-primary tracking-tight`. */
@Composable
private fun Wordmark(model: AppModel) {
    val tokens = LocalPpmTokens.current
    Box(
        Modifier
            .width(RAIL_WIDTH)
            .fillMaxHeight()
            .edge(Edge.Right, tokens.border.color)
            .ppmClickable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, onClickLabel = "Toggle sidebar") { model.toggleSidebar() },
        contentAlignment = Alignment.Center,
    ) {
        BasicText("PPM", style = ppmText(14f, FontWeight.ExtraBold, lineHeight = 14f).copy(color = tokens.accent.color, letterSpacing = (-0.35).sp))
    }
}

/**
 * The project switcher's button: the project's squircle, its name over its path, and the
 * up-down chevrons (`h-8 px-2 rounded-lg gap-[9px]`, lit while its list is open or a mouse is on it).
 */
@Composable
private fun ProjectSwitcherButton(model: AppModel, open: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(12.dp)
    val project = model.currentProject
    Row(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(shape)
            .background(if (open) tokens.panel2.color else Color.Transparent)
            .ppmClickable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, shape = shape, onClickLabel = "Switch project", onClick = onClick)
            // `px-2` inside a border that is there, transparent, while closed.
            .cssBorder(if (open) tokens.border.color else Color.Transparent, shape)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (project != null) {
            SwitcherAvatar(model, project, 24.dp, model.unreadSessions(), square = true)
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

/**
 * The switcher's `Avatar`: the project's avatar with a dot on its corner in the most urgent unread
 * kind's colour (`-top-0.5 -right-0.5 size-2 border-2 border-background`).
 */
@Composable
internal fun SwitcherAvatar(model: AppModel, project: ProjectInfo, size: Dp, unread: Map<String, SessionUnread>, square: Boolean = false) {
    val tokens = LocalPpmTokens.current
    Box {
        ProjectAvatar(model, project, size, square)
        model.urgentUnread(project.name, unread)?.let { type ->
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(2.dp, (-2).dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(tokens.notification(type).color)
                    .cssBorder(tokens.bgSolid.color, CircleShape, width = 2.dp),
            )
        }
    }
}

/** The sidebar's drag of its right edge; held while it runs. */
private class SidebarResize {
    var held by mutableStateOf(false)

    /** The width the drag has reached, in pixels; the sidebar follows it whole dp by whole dp. */
    var reached = 0f
}

/**
 * The sidebar's right edge dragged to resize it. The web's handle is the 4 px line at the edge
 * (`absolute right-0 w-1`), which a mouse has to hit; a finger gets 12 dp of the edge, as the
 * line is too thin to find. Outside those a press belongs to the sidebar's content, so the
 * sidebar box takes the drag itself, seeing each press before its content does.
 */
private fun Modifier.resizesSidebar(model: AppModel, resize: SidebarResize): Modifier = composed {
    val density = LocalDensity.current.density
    screenDrag(
        horizontal = true,
        cursor = WebCursor.ResizeHorizontal,
        // Measured from inside the 1 px border, where `right-0` puts the line.
        takes = { down ->
            val inside = size.width - 1.dp.toPx() - down.position.x
            inside > 0f && inside <= (if (down.type == PointerType.Mouse) 4.dp else 12.dp).toPx()
        },
        onStart = { down ->
            resize.held = true
            // The web uses `clientX` for every pointer type, so the edge comes under the
            // pointer at the first move, including a finger in the wider touch target.
            resize.reached = down.position.x
        },
        onDrag = { delta ->
            resize.reached += delta
            model.resizeSidebar((resize.reached / density).roundToInt())
        },
        onEnd = {
            resize.held = false
            model.saveSidebarWidth()
        },
    )
}

/**
 * The sidebar's resize line (`w-1 cursor-col-resize`): the accent at 30 % under a mouse
 * (`hover:bg-primary/30`), 50 % while a drag holds it. The double arrow shows over the line alone,
 * as on the web.
 */
@Composable
private fun ResizeLine(resize: SidebarResize, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover()
    Box(
        modifier
            .fillMaxHeight()
            .width(4.dp)
            .pointerHoverIcon(WebCursor.ResizeHorizontal)
            .hoverable(hover.source)
            .background(tokens.accent.opacity(if (resize.held) 0.5 else 0.3 * hover.lit).color),
    )
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
 * The section rail (nav-section-rail.tsx): the sections in the order the web's rail was dragged
 * into, then the footer utilities. Tapping a section opens it — unfolding the sidebar — and tapping
 * the open one folds the sidebar. Dragging them into another order is not built yet.
 */
@Composable
private fun NavSectionRail(model: AppModel, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val unread = model.unreadSessions()
    // `w-[52px] border-r`: the width holds the border a caller's [modifier] draws.
    Column(Modifier.width(RAIL_WIDTH).then(modifier).fillMaxHeight()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 3.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // The open section stays lit while the sidebar is folded, as on the web.
            for (section in model.sidebarSections) {
                val active = model.sidebarSection == section
                if (section == "explorer") {
                    NavItem(ProductIcon.FolderOpen, "Explorer", active, badge = 0) { model.pickSidebarSection(section) }
                } else if (section == "search") {
                    NavItem(ProductIcon.Search, "Search", active, badge = 0) { model.pickSidebarSection(section) }
                } else if (section == "git") {
                    NavItem(ProductIcon.GitBranch, "Git", active, badge = model.gitChangeCount(model.selectedProject)) {
                        model.pickSidebarSection(section)
                    }
                } else if (section == "teams") {
                    NavItem(ProductIcon.Users, "Teams", active, badge = 0, featureBadge = FeatureBadgeKind.BETA) { model.pickSidebarSection(section) }
                } else {
                    NavItem(ProductIcon.BotMessageSquare, "Chat History", active, badge = model.unread(model.selectedProject, unread).size) {
                        model.pickSidebarSection(section)
                    }
                }
            }
            val contribs = model.extensions?.contributions?.collectAsState()?.value
            for (view in contribs?.views?.get("sidebar").orEmpty().filter { it.type != "webview" }) {
                val secId = "ext:${view.id}"
                val active = model.sidebarSection == secId
                NavItem(ProductIcon.Puzzle, view.name, active, badge = 0) {
                    model.pickSidebarSection(secId)
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().edge(Edge.Top, tokens.border.color).padding(horizontal = 3.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            NotificationBell(model)
            FooterUtil(ProductIcon.FolderTree, "File Explorer") { model.openExplorer() }
            FooterUtil(ProductIcon.Settings, "Settings") { model.openSettings() }
        }
    }
}

/**
 * A section (`size-[38px] rounded-[9px]`): the open one on the accent wash, the accent's 2 px bar
 * inset along its left edge and bending with its corners; another lit under a mouse
 * (`hover:bg-surface-elevated hover:text-foreground`). A [featureBadge] hangs over its top right
 * corner, 2 px out (`absolute -top-0.5 -right-0.5`).
 */
@Composable
private fun NavItem(icon: ProductIcon, label: String, active: Boolean, badge: Int, featureBadge: FeatureBadgeKind? = null, onClick: () -> Unit) {
    Box(Modifier.size(38.dp)) {
        NavTile(icon, label, active, badge, onClick)
        if (featureBadge != null) FeatureBadge(featureBadge, Modifier.align(Alignment.TopEnd).offset(2.dp, (-2).dp), corner = true)
    }
}

@Composable
private fun NavTile(icon: ProductIcon, label: String, active: Boolean, badge: Int, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(9.dp)
    val hover = rememberHover(enabled = !active)
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
            .semantics { contentDescription = label }
            .ppmClickable(
                pressedColor = tokens.panel2.color,
                hoverColor = if (active) Color.Transparent else tokens.panel2.color,
                shape = shape,
                onClickLabel = label,
                source = hover.source,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(icon, 18.dp, (if (active) tokens.accent else tokens.text.mix(tokens.text3, hover.lit.toDouble())).color)
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

/** A footer utility (`size-8 rounded-[7px]`, a 16 px icon in the subtle colour, lit as a section is). */
@Composable
private fun FooterUtil(icon: ProductIcon, label: String, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(7.dp)
    val hover = rememberHover()
    Box(
        Modifier
            .size(32.dp)
            .clip(shape)
            .semantics { contentDescription = label }
            .ppmClickable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, shape = shape, onClickLabel = label, source = hover.source, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { PpmIcon(icon, 16.dp, tokens.text.mix(tokens.text3, hover.lit.toDouble()).color) }
}

/** The rail's width, and the top bar's height, which the tab bars share. */
val RAIL_WIDTH = 52.dp
val BAR_HEIGHT = 41.dp
