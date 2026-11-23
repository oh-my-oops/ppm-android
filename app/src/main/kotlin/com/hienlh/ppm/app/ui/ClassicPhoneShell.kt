package com.hienlh.ppm.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Ease
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.phone.phoneTabMenuItems
import com.hienlh.ppm.chat.RelativeTime
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssBorderPx
import com.hienlh.ppm.design.cssScrollbar
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.ProjectInfo
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.state.workspace.WorkspaceTab
import com.hienlh.ppm.state.workspace.tabSessionId
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * The phone layout (`md:hidden` in app.tsx): the active tab — or the home panel when none is
 * open — over the bottom nav, with the drawer, the project sheet and the tab switcher it opens.
 * The keyboard pans all of it up as Chrome pans its view over the page, which keeps its height
 * ([KeyboardPannedPage]): the nav rides on the keyboard while the message box has focus.
 */
@Composable
fun ClassicPhoneShell(model: AppModel) {
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    // The drawer's own section (`activeTab`), kept while it is closed as the web's mounted drawer keeps it (`initialTab ?? "explorer"`).
    var drawerSection by rememberSaveable { mutableStateOf<String?>("explorer") }
    LaunchedEffect(model.mobileDrawerOpenRequest) {
        val req = model.mobileDrawerOpenRequest ?: return@LaunchedEffect
        drawerSection = req
        drawerOpen = true
        model.mobileDrawerOpenRequest = null
    }
    var projectsOpen by remember { mutableStateOf(false) }
    var tabsOpen by remember { mutableStateOf(false) }
    var tabMenu by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<SessionInfo?>(null) }
    val density = LocalDensity.current
    val navBar = WindowInsets.navigationBars.getBottom(density)
    val nav = with(density) { NAV_HEIGHT.roundToPx() } + navBar
    // A phone shows one panel, but editor-panel.tsx frames it as one of several while the grid
    // holds more: 1 px, the accent at 30 % when it is the focused one. It moves everything inside
    // by a pixel, so a line wraps where the web's does only with it.
    val tokens = LocalPpmTokens.current
    val frame = model.layout?.takeIf { it.gridPanelIds.size > 1 }?.let { layout ->
        val focused = layout.focusedPanelId in layout.gridPanelIds
        Modifier.cssBorder(if (focused) tokens.accent.opacity(0.3).color else Color.Transparent)
    } ?: Modifier
    // Files dragged over the chat: its overlay comes after the web's fixed corners, so they go
    // under it, blurred with the chat, inside the overlay's `rounded-lg`.
    val covered = model.activeTab?.let { model.chat(it.id) }?.filesOver == true
    Backdrop {
        KeyboardPannedPage {
            if (covered) CornerChrome(model, mobile = true, Modifier.clip(RoundedCornerShape(12.dp)).blur(13.dp))
            Box(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(bottom = with(density) { nav.toDp() })
                    .then(frame),
            ) {
                val tab = model.activeTab
                if (tab != null) key(tab.id) { TabBody(model, tab) } else EmptyPanel(model, onOpenSession = { model.openTab(it) })
            }
            MobileNav(
                model,
                onMenu = { drawerOpen = true },
                onProjects = { projectsOpen = true },
                onTabs = { tabsOpen = true },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            if (!covered) CornerChrome(model, mobile = true)
            MobileDrawer(model, drawerOpen, drawerSection, onSection = { drawerSection = it }) { drawerOpen = false }
        }
    }
    model.layout?.takeIf { it.dock.visible }?.let { state ->
        val keyboard = WindowInsets.ime.getBottom(density)
        val screen = LocalConfiguration.current.screenHeightDp.dp
        BottomSheet(
            { model.setDockVisible(false) },
            Modifier.height(if (keyboard > 0) screen else screen * if (state.dockExpanded) 0.92f else 0.6f),
        ) {
            com.hienlh.ppm.app.ui.tablet.DockPanel(model, state, com.hienlh.ppm.app.ui.tablet.Edge.Top)
        }
    }
    if (projectsOpen || model.phoneProjectRename != null) ProjectSheet(model) {
        projectsOpen = false
        model.phoneProjectRename = null
    }
    if (tabsOpen) TabSwitcherSheet(model, onDismiss = { tabsOpen = false }, onLongPress = { tabMenu = it })
    tabMenu?.let { id -> TabMenuSheet(model, id, onDismiss = { tabMenu = null }, onRename = { renaming = it }) }
    renaming?.let { session -> RenameSheet(model, session) { renaming = null } }
}

private val NAV_HEIGHT = 48.dp

/** `h-12 bg-background border-t border-border`: Menu | Project | + | the current tab. */
@Composable
private fun MobileNav(model: AppModel, onMenu: () -> Unit, onProjects: () -> Unit, onTabs: () -> Unit, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    Column(modifier.fillMaxWidth().background(tokens.bgSolid.color).line(top = true, tokens.border.color)) {
        Row(Modifier.fillMaxWidth().height(NAV_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
            // The fixed cluster, `border-r border-border`.
            Row(
                Modifier.fillMaxHeight().cssBorder(tokens.border.color, sides = CssSides.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NavCell("Open menu", onMenu) { PpmIcon(ProductIcon.Menu, 20.dp, tokens.text2.color) }
                NavDivider()
                NavCell("Switch project", onProjects) {
                    val project = model.currentProject
                    if (project != null) ProjectAvatar(model, project, 28.dp) else PpmIcon(ProductIcon.Layers, 20.dp, tokens.text2.color)
                }
                NavDivider()
                NavCell("Terminal", { model.setDockVisible(model.layout?.dock?.visible != true) }) { PpmIcon(ProductIcon.Terminal, 20.dp, tokens.text2.color) }
                NavDivider()
                NavCell("New tab", { model.openPalette() }) { PpmIcon(ProductIcon.Plus, 20.dp, tokens.text2.color) }
            }
            val active = model.activeTab
            if (active != null) {
                CurrentTabButton(model, active, Modifier.weight(1f), onTabs)
            } else {
                Row(
                    Modifier.weight(1f).fillMaxHeight().ppmClickable(pressedColor = Color.Transparent) { model.openPalette() }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PpmIcon(ProductIcon.Plus, 16.dp, tokens.text2.color)
                    BasicText("New Tab", style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color))
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun NavCell(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(NAV_HEIGHT).ppmClickable(pressedColor = Color.Transparent, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun NavDivider() {
    val tokens = LocalPpmTokens.current
    Box(Modifier.width(1.dp).fillMaxHeight().background(tokens.border.color))
}

/** `flex-1 h-12 px-3 border-t-2 border-primary bg-surface`: icon, title, the count of tabs, a chevron. */
@Composable
private fun CurrentTabButton(model: AppModel, active: WorkspaceTab, modifier: Modifier, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val title = tabTitle(model, active)
    Row(
        modifier
            .fillMaxHeight()
            .background(tokens.panel.color)
            .ppmClickable(pressedColor = Color.Transparent, onClick = onClick)
            .cssBorder(tokens.accent.color, width = 2.dp, sides = CssSides.Top)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TabIcon(active, tokens.accent)
        BasicText(
            title,
            Modifier.weight(1f),
            style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.accent.color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        CountChip(model.openTabs.size)
        PpmIcon(ProductIcon.ChevronUp, 14.dp, tokens.text3.color)
    }
}

/** `px-1.5 h-[18px] rounded-md border bg-surface-elevated text-[10px] font-mono`. */
@Composable
private fun CountChip(count: Int) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.height(18.dp).clip(shape).background(tokens.panel2.color).cssBorder(tokens.border.color, shape).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText("$count", style = ppmText(10f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text2.color))
    }
}

// Projects

/**
 * The projects (`ProjectBottomSheet`): a search, the order (Recent, Priority, Name), then each
 * project with its path and an inline rename field.
 */
@Composable
internal fun ProjectSheet(model: AppModel, onDismiss: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val query = rememberTextFieldState()
    val list = rememberLazyListState()
    if (model.projectActions != null) return
    val screen = LocalConfiguration.current.screenHeightDp.dp
    val status = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() }
    BottomSheet(onDismiss, Modifier.heightIn(max = screen - status), background = tokens.bgSolid.color) {
        Row(
            Modifier.fillMaxWidth().line(top = false, tokens.border.color).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText("Projects", Modifier.weight(1f), style = ppmText(14f, FontWeight.SemiBold, lineHeight = 20f).copy(color = tokens.text.color))
            Box(
                Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).ppmClickable(pressedColor = tokens.panel2.color, onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) { PpmIcon(ProductIcon.X, 16.dp, tokens.text.color) }
        }
        Row(
            Modifier.fillMaxWidth().line(top = false, tokens.border.color).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PpmIcon(ProductIcon.Search, 16.dp, tokens.text3.color)
            PpmTextField(query, "Search projects…", Modifier.weight(1f))
        }
        Row(
            Modifier.fillMaxWidth().line(top = false, tokens.border.color).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for ((mode, label, icon) in PROJECT_SORTS) {
                val on = model.projectSortMode == mode
                val color = if (on) tokens.accent else tokens.text3
                Row(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (on) tokens.accent.opacity(0.12).color else Color.Transparent)
                        .ppmClickable(pressedColor = tokens.panel2.color) { model.pickProjectSort(mode) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PpmIcon(icon, 14.dp, color.color)
                    BasicText(label, style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = color.color))
                }
            }
        }
        val q = query.text.toString().trim().lowercase()
        val shown = model.sortedProjects.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.path.lowercase().contains(q) }
        val maxList = LocalConfiguration.current.screenHeightDp.dp * 0.6f
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = maxList).cssScrollbar(list), list) {
            items(shown, key = { it.name }) { project ->
                val active = project.name == model.selectedProject
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (active) tokens.panel2.opacity(0.1).color else Color.Transparent)
                        .pressable(pressedColor = tokens.panel2.color, onLongClick = {
                            model.projectActionsCanReorder = query.text.isBlank(); model.projectActions = project
                        }, onClick = {
                            if (model.phoneProjectRename != project.name) {
                                onDismiss()
                                model.scope.launch { model.openProject(project.name) }
                            }
                        })
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ProjectAvatar(model, project, 40.dp)
                    Column(Modifier.weight(1f)) {
                        if (model.phoneProjectRename == project.name) {
                            com.hienlh.ppm.app.ui.projects.InlineProjectRename(model, project)
                        } else BasicText(project.name, style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        BasicText(project.path, style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val opened = model.projectOpenedAt[project.name]
                    if (opened != null && !active) {
                        RelativeTime(Instant.ofEpochMilli(opened), ppmText(10f, lineHeight = 15f).copy(color = tokens.text3.color))
                    }
                    if (active) PpmIcon(ProductIcon.Check, 16.dp, tokens.accent.color)
                }
            }
            if (shown.isEmpty()) {
                item {
                    BasicText(
                        "No projects found",
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                        style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text3.color, textAlign = TextAlign.Center),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().ppmClickable { onDismiss(); model.addingProject = true }
            .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PpmIcon(ProductIcon.Plus, 20.dp, tokens.text2.color)
            BasicText("Add Project", style = ppmText(14f).copy(color = tokens.text2.color))
        }
        Row(
            Modifier.fillMaxWidth().line(top = true, tokens.border.color).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).ppmClickable(pressedColor = Color.Transparent) {
                    onDismiss()
                    model.openSettings()
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PpmIcon(ProductIcon.Settings, 16.dp, tokens.text2.color)
                BasicText("Settings", style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text2.color))
            }
            model.info?.version?.let { BasicText("v$it", style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color)) }
        }
    }
}

// Tabs

/**
 * Open Tabs (`MobileTabSwitcherSheet`): a filter, Default or Recent order, and each tab with its
 * unread dot and a close button; a long press opens the tab's menu.
 */
@Composable
private fun TabSwitcherSheet(model: AppModel, onDismiss: () -> Unit, onLongPress: (String) -> Unit) {
    val tokens = LocalPpmTokens.current
    val query = rememberTextFieldState()
    val running = model.runningSessions()
    val unread = model.unreadSessions()
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.72f
    BottomSheet(onDismiss, Modifier.heightIn(max = maxHeight)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText("Open Tabs", style = ppmText(13f, FontWeight.SemiBold, lineHeight = 19.5f).copy(color = tokens.text.color))
            CountChip(model.openTabs.size)
            Spacer(Modifier.weight(1f))
            val segment = RoundedCornerShape(12.dp)
            Row(
                Modifier.clip(segment).background(tokens.bgSolid.color).cssBorder(tokens.border.color, segment).padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for ((mode, label) in listOf("default" to "Default", "recent" to "Recent")) {
                    val on = model.tabSortMode == mode
                    Box(
                        Modifier
                            .height(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (on) tokens.accent.opacity(0.1).color else Color.Transparent)
                            .ppmClickable(pressedColor = tokens.panel2.color) { model.pickTabSort(mode) }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(label, style = ppmText(11f, FontWeight.Medium, lineHeight = 16.5f).copy(color = (if (on) tokens.accent else tokens.text2).color))
                    }
                }
            }
            // `size-8 rounded-full border border-dashed`.
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .drawBehind {
                        val stroke = cssBorderPx(1.dp).toFloat()
                        drawCircle(
                            tokens.border.color,
                            radius = size.minDimension / 2 - stroke / 2,
                            style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))),
                        )
                    }
                    .ppmClickable(pressedColor = tokens.panel2.color, shape = CircleShape) {
                        onDismiss()
                        model.openPalette()
                    },
                contentAlignment = Alignment.Center,
            ) { PpmIcon(ProductIcon.Plus, 16.dp, tokens.text3.color) }
        }
        Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier.fillMaxWidth().height(40.dp).clip(shape).background(tokens.bgSolid.color).cssBorder(tokens.border.color, shape).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PpmIcon(ProductIcon.Search, 16.dp, tokens.text3.color)
                PpmTextField(query, "Filter tabs...", Modifier.weight(1f))
            }
        }
        val q = query.text.toString().trim().lowercase()
        // `buildTabSwitcherGroups`: matched by title or kind; one group per grid panel, or one
        // header-less list in Recent order.
        val matching = model.openTabs.filter { (tab, _) -> q.isEmpty() || tabTitle(model, tab).lowercase().contains(q) || tab.type.lowercase().contains(q) }
        val groups = if (model.tabSortMode == "recent") {
            if (matching.isEmpty()) emptyList() else listOf("" to model.recentOrder(matching))
        } else {
            model.gridPanelIds.mapIndexedNotNull { index, panelId ->
                matching.filter { it.second == panelId }.takeIf { it.isNotEmpty() }?.let { "Panel ${index + 1}" to it }
            }
        }
        if (groups.isEmpty()) {
            BasicText(
                "No tabs match",
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                style = ppmText(13f, lineHeight = 19.5f).copy(color = tokens.text3.color, textAlign = TextAlign.Center),
            )
        } else {
            // The web's scroller is the padded box: its thumb runs down the padding's far edge.
            val list = rememberLazyListState()
            LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth().cssScrollbar(list).padding(start = 8.dp, end = 8.dp, bottom = 8.dp), list) {
                for ((label, tabs) in groups) {
                    if (label.isNotEmpty()) item(key = "group\u0000$label") { GroupHeader(label, tabs.size) }
                    items(tabs, key = { (tab, panelId) -> "${tab.id}\u0000$panelId" }) { (tab, _) ->
                        val sessionId = tabSessionId(tab)
                        val entry = sessionId?.let { unread[it] }
                        TabRow(
                            model, tab, tabTitle(model, tab),
                            streaming = sessionId != null && sessionId in running,
                            unreadType = entry?.type,
                            unreadManual = entry?.manual == true,
                            onOpen = {
                                model.activate(tab.id)
                                onDismiss()
                            },
                            onLongPress = { onLongPress(tab.id) },
                        )
                    }
                }
            }
        }
    }
}

/** `flex items-center gap-1.5 px-2 pt-2 pb-1`: the panel's columns icon, "PANEL N" and its count. */
@Composable
private fun GroupHeader(label: String, count: Int) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PpmIcon(ProductIcon.Columns2, 12.dp, tokens.text3.color)
        BasicText(
            label.uppercase(),
            style = ppmText(11f, FontWeight.SemiBold, lineHeight = 16.5f).copy(color = tokens.text3.color, letterSpacing = 0.05.em),
        )
        BasicText("$count", style = ppmText(10f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text3.color))
    }
}

/**
 * `h-11 rounded-lg pl-3.5 pr-2 gap-2.5`: the tag's colour bar, the icon (typing dots while the
 * turn runs, the unread dot), the title, the active dot and the close button.
 */
@Composable
private fun TabRow(
    model: AppModel,
    tab: WorkspaceTab,
    title: String,
    streaming: Boolean,
    unreadType: String?,
    unreadManual: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val active = tab.id == model.activeTabId
    val chat = model.chat(tab.id)
    val session = chat?.session?.let { s -> model.sessions.firstOrNull { it.id == s.id } ?: s }
    // The tag's colour, else the project's when it is a plain colour (a palette gradient is not one).
    val bar = session?.tag?.color?.let(Rgba::hex) ?: chat?.let { model.currentProject?.color?.takeIf { it.startsWith("#") }?.let(Rgba::hex) }
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .background(if (active) tokens.accent.opacity(0.1).color else Color.Transparent)
            .pressable(pressedColor = tokens.panel2.color, shape = shape, onLongClick = { onLongPress() }, onClick = onOpen),
    ) {
        if (bar != null) {
            // `w-[3px] rounded-r-sm`: CSS shrinks the 6 px radius to the bar's width.
            Box(
                Modifier.align(Alignment.CenterStart).padding(vertical = 8.dp).width(3.dp).fillMaxHeight().drawBehind {
                    val round = CornerRadius(size.width)
                    drawPath(
                        Path().apply { addRoundRect(RoundRect(size.toRect(), topRight = round, bottomRight = round)) },
                        bar.color,
                    )
                },
            )
        }
        Row(
            Modifier.fillMaxSize().padding(start = 14.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box {
                if (streaming) {
                    PpmIcon(ProductIcon.MessageCircle, 16.dp, tokens.warning.color)
                    TypingDots(tokens.warning, Modifier.align(Alignment.Center))
                } else {
                    // The sheet's text is the page's own, 16 px on 24.
                    TabIcon(tab, if (active) tokens.accent else tokens.text2, line = IconLine(16f, 24f))
                    if (unreadType != null && (!active || unreadManual)) {
                        Box(Modifier.align(Alignment.TopEnd).offset(4.dp, (-4).dp).size(8.dp).clip(CircleShape).background(tokens.notification(unreadType).color))
                    }
                }
            }
            BasicText(
                title,
                Modifier.weight(1f),
                style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = (if (active) tokens.accent else tokens.text).color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (active) Box(Modifier.size(6.dp).clip(CircleShape).background(tokens.accent.color))
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(12.dp)).ppmClickable(pressedColor = tokens.panel2.color) { model.closeTab(tab.id) },
                contentAlignment = Alignment.Center,
            ) { PpmIcon(ProductIcon.X, 16.dp, tokens.text3.color) }
        }
    }
}

/** The long-press menu of a tab (mobile-nav.tsx): rename, mark unread, its tag, and closing. */
@Composable
private fun TabMenuSheet(model: AppModel, id: String, onDismiss: () -> Unit, onRename: (SessionInfo) -> Unit) {
    val tokens = LocalPpmTokens.current
    val tab = model.openTabs.firstOrNull { it.first.id == id }?.first
    val chat = model.chat(id)
    val session = chat?.session?.let { s -> model.sessions.firstOrNull { it.id == s.id } ?: s }
    val unread = session != null && model.unreadSessions()[session.id] != null
    BottomSheet(onDismiss) {
        BasicText(
            tab?.let { tabTitle(model, it) } ?: "",
            Modifier.fillMaxWidth().line(top = false, tokens.border.color).padding(horizontal = 12.dp, vertical = 8.dp),
            style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (session != null) {
            SheetRow("Rename", ProductIcon.Pencil) {
                onDismiss()
                onRename(session)
            }
            if (!unread) {
                SheetRow("Mark as unread", leading = { PpmIcon(ProductIcon.Circle, 16.dp, tokens.accent.color) }) {
                    onDismiss()
                    model.markUnread(session)
                }
            }
            if (model.tags.tags.isNotEmpty()) {
                SheetLabel("Set Tag", ProductIcon.Tag)
                for (tag in model.tags.tags) {
                    SheetRow(
                        tag.name,
                        leading = { TagDot(tag.color, 10.dp) },
                        trailing = if (session.tag?.id == tag.id) ({ PpmIcon(ProductIcon.Check, 14.dp, tokens.text.color) }) else null,
                    ) {
                        onDismiss()
                        model.scope.launch { model.setTag(tag, session) }
                    }
                }
                if (session.tag != null) {
                    SheetRow("Remove tag") {
                        onDismiss()
                        model.scope.launch { model.setTag(null, session) }
                    }
                }
                SheetDivider()
            }
        }
        // Pin / Unpin and the closes, which leave pinned tabs open (`mobile-nav.tsx`), over the tab's panel.
        if (tab != null) {
            val panelId = model.openTabs.firstOrNull { it.first.id == id }?.second
            val panelTabs = model.openTabs.filter { it.second == panelId }.map { it.first }
            for (item in phoneTabMenuItems(tab, panelTabs)) {
                SheetRow(item.label, item.icon) {
                    onDismiss()
                    when {
                        item.bulk != null -> model.bulkCloseTabs(id, item.bulk)
                        item.id == "close" -> model.closeTab(id)
                        else -> model.setTabPinned(id, !tab.pinned)
                    }
                }
            }
        }
    }
}

/** Rename chat (`px-4 pt-1 pb-3`): the title in a 44 px field, Cancel and Save. */
@Composable
private fun RenameSheet(model: AppModel, session: SessionInfo, onDismiss: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val title = remember { TextFieldState(session.title) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun save() {
        val text = title.text.toString()
        onDismiss()
        model.scope.launch { model.rename(session, text) }
    }
    BottomSheet(onDismiss) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) {
            BasicText("Rename chat", Modifier.padding(bottom = 8.dp), style = ppmText(13f, FontWeight.SemiBold, lineHeight = 19.5f).copy(color = tokens.text.color))
            var focused by remember { mutableStateOf(false) }
            val shape = RoundedCornerShape(12.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(shape)
                    .background(tokens.bgSolid.color)
                    .cssBorder((if (focused) tokens.accent else tokens.border).color, shape)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                PpmTextField(
                    title, "Session title", Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    onKeyboardAction = { save() },
                    focusRequester = focus,
                    onFocusChanged = { focused = it },
                    cssStart = 12.dp,
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Cancel", Modifier.weight(1f), onClick = onDismiss)
                PrimaryButton("Save", Modifier.weight(1f), onClick = ::save)
            }
        }
    }
}

// The drawer

/**
 * The drawer (`MobileDrawer`): 90% of the width from the left over a 50% black backdrop — the
 * project's name, the open section, the tab bar and the footer tiles. History and Teams are the
 * sections built yet, and the Settings tile the only tile, so they are the ones shown. The drawer
 * keeps a section of its own ([section]), not the desktop sidebar's; one it does not have — at
 * first, the web's Explorer — gives way to the first in the rail's order.
 */
@Composable
private fun MobileDrawer(model: AppModel, open: Boolean, section: String?, onSection: (String) -> Unit, onClose: () -> Unit) {
    val tokens = LocalPpmTokens.current
    // The whole drawer fades in 200 ms while the panel slides in 300 ms, both ways.
    val shown by animateFloatAsState(if (open) 1f else 0f, tween(200, easing = Ease), label = "drawerFade")
    val slid by animateFloatAsState(if (open) 1f else 0f, tween(300, easing = EaseOut), label = "drawerSlide")
    if (!open && shown == 0f && slid == 0f) return
    BackHandler(enabled = open, onBack = onClose)
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer { alpha = shown }) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        )
        val width = maxWidth * 0.9f
        Column(
            Modifier
                .width(width)
                .fillMaxHeight()
                .graphicsLayer { translationX = -(1f - slid) * size.width }
                .background(tokens.bgSolid.color)
                .cssBorder(tokens.border.color, sides = CssSides.End)
                // Taps on the panel stay on it.
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().line(top = false, tokens.border.color).padding(start = 16.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    model.selectedProject ?: "PPM",
                    Modifier.weight(1f),
                    style = ppmText(14f, FontWeight.Bold, lineHeight = 20f).copy(color = tokens.accent.color, letterSpacing = (-0.35).sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).ppmClickable(pressedColor = tokens.panel2.color, onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { PpmIcon(ProductIcon.X, 16.dp, tokens.text.color) }
            }
            val contribs = model.extensions?.contributions?.collectAsState()?.value
            val extViews = (contribs?.views?.get("sidebar") ?: contribs?.views?.get("explorer")).orEmpty()
            val sections = model.sidebarSections + extViews.map { "ext:${it.id}" }
            val shown = section?.takeIf { it in sections } ?: sections.first()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val list = model.sidebarList
                val search = model.sidebarSearch
                // The web's GroupList closes nothing as it opens a group, which leaves the tab it opened behind the drawer.
                if (shown == "explorer") {
                    com.hienlh.ppm.app.ui.files.ExplorerBody(model, onNavigate = onClose)
                } else if (shown == "search") {
                    com.hienlh.ppm.app.ui.files.SearchBody(model, onNavigate = onClose)
                } else if (shown == "git") {
                    com.hienlh.ppm.app.ui.git.GitStatusPanel(model, onNavigate = onClose)
                } else if (shown == "database") {
                    com.hienlh.ppm.app.ui.database.DatabaseSidebarPane(model, onNavigate = onClose)
                } else if (shown == "designs") {
                    com.hienlh.ppm.app.ui.design.DesignsSidebarPane(model, onNavigate = onClose)
                } else if (shown == "tunnels") {
                    com.hienlh.ppm.app.ui.tunnels.PortForwardingPanel(model, onNavigate = onClose)
                } else if (shown == "ai-resources") {
                    AiResourcesSidebarPanel(model, onNavigate = onClose)
                } else if (shown == "tasks" || shown == "specs" || shown == "jira") {
                    // Sections added after this layout, drawn by the tablet sidebar's panels. They take no
                    // onNavigate, so the drawer closes once one of them opens a tab.
                    val openedFrom = remember { model.activeTab?.id }
                    LaunchedEffect(model.activeTab?.id) { if (model.activeTab?.id != openedFrom) onClose() }
                    when (shown) {
                        "tasks" -> com.hienlh.ppm.app.ui.tasks.TasksPanel(model)
                        "specs" -> com.hienlh.ppm.app.ui.tablet.SpecsSidebarPanel(model)
                        else -> com.hienlh.ppm.app.ui.jira.JiraPanel(model)
                    }
                } else if (shown.startsWith("ext:")) {
                    val viewId = shown.removePrefix("ext:")
                    val contribs = model.extensions?.contributions?.collectAsState()?.value
                    val viewName = contribs?.views?.values?.flatten()?.firstOrNull { it.id == viewId }?.name ?: viewId
                    com.hienlh.ppm.app.ui.extensions.ExtensionTreeViewPanel(model, viewId, viewName)
                } else if (shown == "teams" && model.currentProject != null) {
                    GroupList(model, onNavigate = onClose)
                } else if (shown == "history" && list != null && search != null) {
                    SessionHistoryPanel(model, list, search, onNavigate = onClose)
                } else {
                    BasicText(
                        "Select a project from the bottom nav bar",
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                        style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color, textAlign = TextAlign.Center),
                    )
                }
            }
            Column(Modifier.fillMaxWidth().line(top = true, tokens.border.color).navigationBarsPadding()) {
                var moreOpen by remember { mutableStateOf(false) }
                fun tabIcon(id: String): ProductIcon = when {
                    id == "explorer" -> ProductIcon.FolderOpen
                    id == "search" -> ProductIcon.Search
                    id == "git" -> ProductIcon.GitBranch
                    id == "database" -> ProductIcon.Database
                    id == "designs" -> ProductIcon.Palette
                    id == "teams" -> ProductIcon.Users
                    id == "tunnels" -> ProductIcon.Globe
                    id == "ai-resources" -> ProductIcon.Sparkles
                    id == "tasks" -> ProductIcon.ListTodo
                    id == "specs" -> ProductIcon.ClipboardList
                    id == "jira" -> ProductIcon.Bug
                    id.startsWith("ext:") -> ProductIcon.Puzzle
                    else -> ProductIcon.BotMessageSquare
                }
                fun tabShortLabel(id: String): String = when {
                    id == "explorer" -> "Explorer"
                    id == "search" -> "Search"
                    id == "git" -> "Git"
                    id == "database" -> "Database"
                    id == "designs" -> "Designs"
                    id == "teams" -> "Teams"
                    id == "tunnels" -> "Ports"
                    id == "ai-resources" -> "AI"
                    id == "tasks" -> "Tasks"
                    id == "specs" -> "Specs"
                    id == "jira" -> "Jira"
                    id.startsWith("ext:") -> extViews.firstOrNull { it.id == id.removePrefix("ext:") }?.name ?: id.removePrefix("ext:")
                    else -> "History"
                }
                fun tabFullLabel(id: String): String = when {
                    id == "history" -> "Chat History"
                    id == "tunnels" -> "Port Forwarding"
                    id == "ai-resources" -> "AI Resources"
                    else -> tabShortLabel(id)
                }
                fun tabBadge(id: String): FeatureBadgeKind? = when (id) {
                    "teams" -> FeatureBadgeKind.BETA
                    "designs" -> FeatureBadgeKind.NEW
                    else -> null
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val slotCount = maxOf(1, (maxWidth / 64.dp).toInt())
                    val needMore = sections.size > slotCount
                    val visibleCount = if (needMore) maxOf(1, slotCount - 1) else sections.size
                    val baseVisible = sections.take(visibleCount)
                    val activeInOverflow = needMore && shown !in baseVisible && shown in sections
                    val visible = if (activeInOverflow) sections.take(maxOf(0, visibleCount - 1)) + shown else baseVisible
                    val overflow = sections.filter { it !in visible }
                    Row(Modifier.fillMaxWidth()) {
                        for (id in visible) {
                            DrawerTabSlot(tabIcon(id), tabShortLabel(id), active = id == shown, badge = tabBadge(id)) { onSection(id) }
                        }
                        if (needMore) {
                            DrawerTabSlot(ProductIcon.MoreHorizontal, "More", active = activeInOverflow, badge = null) { moreOpen = true }
                        }
                    }
                    if (moreOpen) {
                        BottomSheet(onDismiss = { moreOpen = false }) {
                            Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 12.dp)) {
                                BasicText(
                                    "More tabs",
                                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
                                )
                                for (id in overflow) {
                                    val rowColor = (if (id == shown) tokens.accent else tokens.text).color
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .ppmClickable(pressedColor = tokens.panel2.color) { onSection(id); moreOpen = false }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        PpmIcon(tabIcon(id), 16.dp, rowColor)
                                        BasicText(tabFullLabel(id), style = ppmText(14f, lineHeight = 20f).copy(color = rowColor))
                                        tabBadge(id)?.let { FeatureBadge(it) }
                                    }
                                }
                            }
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().line(top = true, tokens.border.color).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (model.info?.minimalMode != true) {
                            DrawerFooterTile(ProductIcon.FolderTree, "Files", modifier = Modifier.weight(1f), badge = FeatureBadgeKind.NEW) { onClose(); model.openExplorer() }
                            if (model.remoteDesktopAvailable) {
                                DrawerFooterTile(ProductIcon.MonitorSmartphone, "Remote", modifier = Modifier.weight(1f), badge = FeatureBadgeKind.BETA) { onClose(); model.openRemoteDesktop() }
                            }
                            if (model.androidAvailable) {
                                DrawerFooterTile(ProductIcon.Smartphone, "Android", modifier = Modifier.weight(1f), badge = FeatureBadgeKind.BETA) { onClose(); model.openAndroid() }
                            }
                            DrawerFooterTile(ProductIcon.Cloud, "Cloud", modifier = Modifier.weight(1f)) { model.cloudShareOpen = true }
                        }
                        DrawerFooterTile(ProductIcon.Settings, "Settings", modifier = Modifier.weight(1f)) { onClose(); model.openSettings() }
                        if (model.info?.minimalMode != true) {
                            DrawerFooterTile(ProductIcon.ScrollText, "Logs", modifier = Modifier.weight(1f), alertDot = model.showLogsBadgeDot) { onClose(); model.openLogs() }
                        }
                    }
                    // `mt-2 border-t border-border px-1 text-[11px]`, there with or without the version.
                    Box(Modifier.fillMaxWidth().padding(top = 8.dp).line(top = true, tokens.border.color).padding(horizontal = 4.dp)) {
                        UpgradeButton(
                            model,
                            ppmText(11f, lineHeight = 16.5f).copy(color = tokens.text.color),
                            Modifier.fillMaxWidth(),
                            alignStart = true,
                            minHeight = 44.dp,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A slot of the drawer's tab bar: `relative flex flex-col items-center gap-0.5 py-2.5 text-[10px]`,
 * 64 px wide, the icon over the short label, `text-primary` when open and `text-text-secondary`
 * else, the feature tag in its corner without a shadow (`top-1 right-2 shadow-none`).
 */
@Composable
private fun DrawerTabSlot(icon: ProductIcon, label: String, active: Boolean, badge: FeatureBadgeKind?, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val color = (if (active) tokens.accent else tokens.text2).color
    Box(Modifier.width(64.dp).clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            PpmIcon(icon, 16.dp, color)
            BasicText(label, style = ppmText(10f, lineHeight = 15f).copy(color = color))
        }
        if (badge != null) FeatureBadge(badge, Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 8.dp), corner = true, shadow = false)
    }
}

@Composable
private fun DrawerFooterTile(
    icon: ProductIcon,
    label: String,
    modifier: Modifier = Modifier,
    badge: FeatureBadgeKind? = null,
    alertDot: Boolean = false,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .ppmClickable(pressedColor = tokens.panel2.color, onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            PpmIcon(icon, 16.dp, tokens.text3.color)
            if (badge != null) {
                FeatureBadge(badge, Modifier.align(Alignment.TopEnd).offset(12.dp, (-8).dp), corner = true)
            }
            if (alertDot) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(4.dp, (-2).dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(tokens.error.color),
                )
            }
        }
        BasicText(label, style = ppmText(10f, lineHeight = 10f).copy(color = tokens.text3.color), maxLines = 1)
    }
}
