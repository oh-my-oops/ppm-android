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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.AppModel.Companion.SETTINGS_TAB
import com.hienlh.ppm.chat.RelativeTime
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ProviderLogo
import com.hienlh.ppm.design.ProviderLogos
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.ProjectInfo
import com.hienlh.ppm.protocol.SessionInfo
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * The phone layout (`md:hidden` in app.tsx): the active tab — or the home panel when none is
 * open — over the bottom nav, with the drawer, the project sheet and the tab switcher it opens.
 * The keyboard covers the nav, as it does over the web's fixed nav.
 */
@Composable
fun PhoneShell(model: AppModel) {
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    var projectsOpen by remember { mutableStateOf(false) }
    var tabsOpen by remember { mutableStateOf(false) }
    var tabMenu by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<SessionInfo?>(null) }
    val density = LocalDensity.current
    val ime = WindowInsets.ime.getBottom(density)
    val navBar = WindowInsets.navigationBars.getBottom(density)
    val nav = with(density) { NAV_HEIGHT.roundToPx() } + navBar
    Backdrop {
        Box(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(bottom = with(density) { maxOf(ime, nav).toDp() }),
        ) {
            val tab = model.activeTab
            when {
                model.activeTabId == SETTINGS_TAB && model.settingsOpen -> SettingsTab(model)
                tab != null -> key(tab.id) { ChatPane(model, tab) }
                else -> EmptyPanel(model, onOpenSession = { model.openTab(it) })
            }
        }
        MobileNav(
            model,
            onMenu = { drawerOpen = true },
            onProjects = { projectsOpen = true },
            onTabs = { tabsOpen = true },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        MobileDrawer(model, drawerOpen) { drawerOpen = false }
    }
    if (projectsOpen) ProjectSheet(model) { projectsOpen = false }
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
            // The fixed cluster, `border-r border-border`; the Terminal button waits for terminals.
            Row(
                Modifier.fillMaxHeight().drawBehind {
                    drawRect(tokens.border.color, Offset(size.width - 1.dp.toPx(), 0f), Size(1.dp.toPx(), size.height))
                }.padding(end = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NavCell("Open menu", onMenu) { PpmIcon(ProductIcon.Menu, 20.dp, tokens.text2.color) }
                NavDivider()
                NavCell("Switch project", onProjects) {
                    val project = model.currentProject
                    if (project != null) ProjectAvatar(model, project, 28.dp) else PpmIcon(ProductIcon.Layers, 20.dp, tokens.text2.color)
                }
                NavDivider()
                NavCell("New tab", { model.scope.launch { model.newChat() } }) { PpmIcon(ProductIcon.Plus, 20.dp, tokens.text2.color) }
            }
            val active = model.activeTabId
            if (active != null) {
                CurrentTabButton(model, active, Modifier.weight(1f), onTabs)
            } else {
                Row(
                    Modifier.weight(1f).fillMaxHeight().ppmClickable(pressedColor = Color.Transparent) { model.scope.launch { model.newChat() } }.padding(horizontal = 12.dp),
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
private fun CurrentTabButton(model: AppModel, active: String, modifier: Modifier, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val chat = model.tabs.firstOrNull { it.id == active }
    val title = if (chat != null) model.title(chat, chat.controller?.state?.value?.title) else "Settings"
    Row(
        modifier
            .fillMaxHeight()
            .background(tokens.panel.color)
            .drawBehind { drawRect(tokens.accent.color, Offset.Zero, Size(size.width, 2.dp.toPx())) }
            .ppmClickable(pressedColor = Color.Transparent, onClick = onClick)
            .padding(start = 12.dp, end = 12.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TabIcon(chat?.session?.providerId, tokens.accent)
        BasicText(
            title,
            Modifier.weight(1f),
            style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.accent.color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        CountChip(model.tabIds.size)
        PpmIcon(ProductIcon.ChevronUp, 14.dp, tokens.text3.color)
    }
}

/** A chat tab's provider logo, or Settings' gear, at 16 px. */
@Composable
private fun TabIcon(providerId: String?, tint: Rgba) {
    when {
        providerId == null -> PpmIcon(ProductIcon.Settings, 16.dp, tint.color)
        ProviderLogos.has(providerId) -> ProviderLogo(providerId, 16.dp, tint.color)
        else -> PpmIcon(ProductIcon.MessageSquare, 16.dp, tint.color)
    }
}

/** `px-1.5 h-[18px] rounded-md border bg-surface-elevated text-[10px] font-mono`. */
@Composable
private fun CountChip(count: Int) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.height(18.dp).clip(shape).background(tokens.panel2.color).border(1.dp, tokens.border.color, shape).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText("$count", style = ppmText(10f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text2.color))
    }
}

// Projects

/**
 * The project's avatar (`ProjectAvatar`): its colour — a picked hex or a palette gradient, else
 * the palette by position — with initials that tell it from the others. An uploaded image is
 * not drawn yet.
 */
@Composable
fun ProjectAvatar(model: AppModel, project: ProjectInfo, size: Dp) {
    val names = model.projects.map { it.name }
    val index = names.indexOf(project.name).coerceAtLeast(0)
    Box(
        Modifier.size(size).clip(CircleShape).background(projectFill(project.color ?: PROJECT_PALETTE[index % PROJECT_PALETTE.size])),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            projectInitials(project.name, names),
            style = ppmText(if (size <= 24.dp) 10f else 11f, FontWeight.Bold, lineHeight = if (size <= 24.dp) 15f else 16.5f).copy(color = Color.White),
            maxLines = 1,
        )
    }
}

/** `project-palette.ts`. */
private val PROJECT_PALETTE = listOf(
    "linear-gradient(135deg, #667eea, #764ba2)",
    "linear-gradient(135deg, #f5576c, #f093fb)",
    "linear-gradient(135deg, #4facfe, #00c6ff)",
    "linear-gradient(135deg, #43e97b, #38f9d7)",
    "linear-gradient(135deg, #fa709a, #fee140)",
    "linear-gradient(135deg, #a18cd1, #6a3de8)",
    "linear-gradient(135deg, #fd7043, #ff8a65)",
    "linear-gradient(135deg, #26c6da, #0097a7)",
    "linear-gradient(135deg, #ab47bc, #7b1fa2)",
    "linear-gradient(135deg, #ef5350, #b71c1c)",
    "linear-gradient(135deg, #1976d2, #42a5f5)",
    "linear-gradient(135deg, #2e7d32, #66bb6a)",
)

private val hexColor = Regex("#[0-9a-fA-F]{3,8}")

/** A CSS `background` of one hex or a 135° two-stop gradient, which runs corner to corner. */
private fun projectFill(color: String): Brush {
    val stops = hexColor.findAll(color).mapNotNull { Rgba.hex(it.value)?.color }.toList()
    return when {
        stops.size >= 2 -> Brush.linearGradient(stops, start = Offset.Zero, end = Offset.Infinite)
        stops.size == 1 -> SolidColor(stops.first())
        else -> SolidColor(Color(0xFF4F86C6))
    }
}

/** `getProjectInitials`: one letter while it is unique, two when that is, else the position. */
internal fun projectInitials(name: String, all: List<String>): String {
    fun words(n: String) = n.split(Regex("[-_.\\s]+")).filter { it.isNotEmpty() }
    fun first(n: String) = (words(n).firstOrNull()?.firstOrNull() ?: n.firstOrNull())?.uppercaseChar()?.toString() ?: ""
    fun two(n: String): String {
        val w = words(n)
        val f = first(n)
        return if (w.size > 1) f + (w[1].firstOrNull()?.uppercaseChar()?.toString() ?: "") else f
    }
    val mine = first(name).ifEmpty { "?" }
    val others = all.filter { it != name }
    if (others.none { first(it) == mine }) return mine
    val pair = two(name)
    if (others.none { two(it) == pair }) return pair
    val index = all.indexOf(name)
    return if (index >= 0) "${index + 1}" else "?"
}

/**
 * The projects (`ProjectBottomSheet`): a search, the order (Recent, Priority, Name), then each
 * project with its path. Adding and editing projects are left out until this app has them.
 */
@Composable
private fun ProjectSheet(model: AppModel, onDismiss: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val query = rememberTextFieldState()
    BottomSheet(onDismiss, background = tokens.bgSolid.color) {
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
            for ((mode, label, icon) in listOf(
                Triple("recent", "Recent", ProductIcon.Clock),
                Triple("priority", "Priority", ProductIcon.ArrowDownUp),
                Triple("name", "Name", ProductIcon.ArrowDownAZ),
            )) {
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
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxList)) {
            items(shown, key = { it.name }) { project ->
                val active = project.name == model.selectedProject
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (active) tokens.panel2.opacity(0.1).color else Color.Transparent)
                        .ppmClickable(pressedColor = tokens.panel2.color) {
                            onDismiss()
                            model.scope.launch { model.openProject(project.name) }
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ProjectAvatar(model, project, 40.dp)
                    Column(Modifier.weight(1f)) {
                        BasicText(project.name, style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            CountChip(model.tabIds.size)
            Spacer(Modifier.weight(1f))
            val segment = RoundedCornerShape(12.dp)
            Row(
                Modifier.clip(segment).background(tokens.bgSolid.color).border(1.dp, tokens.border.color, segment).padding(2.dp),
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
                        val stroke = 1.dp.toPx()
                        drawCircle(
                            tokens.border.color,
                            radius = size.minDimension / 2 - stroke / 2,
                            style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))),
                        )
                    }
                    .ppmClickable(pressedColor = tokens.panel2.color, shape = CircleShape) {
                        onDismiss()
                        model.scope.launch { model.newChat() }
                    },
                contentAlignment = Alignment.Center,
            ) { PpmIcon(ProductIcon.Plus, 16.dp, tokens.text3.color) }
        }
        Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier.fillMaxWidth().height(40.dp).clip(shape).background(tokens.bgSolid.color).border(1.dp, tokens.border.color, shape).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PpmIcon(ProductIcon.Search, 16.dp, tokens.text3.color)
                PpmTextField(query, "Filter tabs...", Modifier.weight(1f))
            }
        }
        val q = query.text.toString().trim().lowercase()
        val entries = model.tabIds.map { id -> id to tabTitle(model, id) }
            .filter { (_, title) -> q.isEmpty() || title.lowercase().contains(q) }
            .let { list ->
                if (model.tabSortMode == "recent") {
                    list.sortedBy { (id, _) -> model.recentTabs.indexOf(id).let { if (it < 0) Int.MAX_VALUE else it } }
                } else {
                    list
                }
            }
        if (entries.isEmpty()) {
            BasicText(
                "No tabs match",
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                style = ppmText(13f, lineHeight = 19.5f).copy(color = tokens.text3.color, textAlign = TextAlign.Center),
            )
        } else {
            LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                items(entries, key = { it.first }) { (id, title) ->
                    TabRow(
                        model, id, title,
                        streaming = id in running,
                        unreadType = unread[id]?.type,
                        unreadManual = unread[id]?.manual == true,
                        onOpen = {
                            model.activate(id)
                            onDismiss()
                        },
                        onLongPress = { onLongPress(id) },
                    )
                }
            }
        }
    }
}

private fun tabTitle(model: AppModel, id: String): String {
    if (id == SETTINGS_TAB) return "Settings"
    val tab = model.tabs.firstOrNull { it.id == id } ?: return id
    return model.title(tab, tab.controller?.state?.value?.title)
}

/**
 * `h-11 rounded-lg pl-3.5 pr-2 gap-2.5`: the tag's colour bar, the icon (typing dots while the
 * turn runs, the unread dot), the title, the active dot and the close button.
 */
@Composable
private fun TabRow(
    model: AppModel,
    id: String,
    title: String,
    streaming: Boolean,
    unreadType: String?,
    unreadManual: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val active = id == model.activeTabId
    val chat = model.tabs.firstOrNull { it.id == id }
    val session = chat?.let { tab -> model.sessions.firstOrNull { it.id == tab.id } ?: tab.session }
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
                    TabIcon(chat?.session?.providerId, if (active) tokens.accent else tokens.text2)
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
                Modifier.size(32.dp).clip(RoundedCornerShape(12.dp)).ppmClickable(pressedColor = tokens.panel2.color) { model.closeTab(id) },
                contentAlignment = Alignment.Center,
            ) { PpmIcon(ProductIcon.X, 16.dp, tokens.text3.color) }
        }
    }
}

/** `tab-typing-dot`: three 2 px dots hopping 1.5 px in turn, a second each. */
@Composable
private fun TypingDots(color: Rgba, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(1.5.dp), verticalAlignment = Alignment.CenterVertically) {
        for (index in 0 until 3) {
            val lift by transition.animateFloat(
                0f, 0f,
                infiniteRepeatable(
                    keyframes {
                        durationMillis = 1_000
                        0f at 0 using EaseInOut
                        -1.5f at 300 using EaseInOut
                        0f at 600 using EaseInOut
                    },
                    initialStartOffset = StartOffset(index * 150),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "dot$index",
            )
            Box(Modifier.graphicsLayer { translationY = lift * density }.size(2.dp).clip(CircleShape).background(color.color))
        }
    }
}

/** The long-press menu of a tab (mobile-nav.tsx): rename, mark unread, its tag, and closing. */
@Composable
private fun TabMenuSheet(model: AppModel, id: String, onDismiss: () -> Unit, onRename: (SessionInfo) -> Unit) {
    val tokens = LocalPpmTokens.current
    val chat = model.tabs.firstOrNull { it.id == id }
    val session = chat?.let { tab -> model.sessions.firstOrNull { it.id == tab.id } ?: tab.session }
    val unread = model.unreadSessions()[id] != null
    val ids = model.tabIds
    BottomSheet(onDismiss) {
        BasicText(
            tabTitle(model, id),
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
                SheetRow("Mark as unread", leading = { FilledCircleIcon(tokens.accent, 16.dp) }) {
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
        SheetRow("Close", ProductIcon.X) {
            onDismiss()
            model.closeTab(id)
        }
        if (ids.any { it != id }) {
            SheetRow("Close Others", ProductIcon.XSquare) {
                onDismiss()
                model.closeOtherTabs(id)
            }
        }
        if (ids.indexOf(id) in 0 until ids.lastIndex) {
            SheetRow("Close to the Right", ProductIcon.ChevronsRight) {
                onDismiss()
                model.closeTabsToRight(id)
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
                    .border(1.dp, (if (focused) tokens.accent else tokens.border).color, shape)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                PpmTextField(
                    title, "Session title", Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    onKeyboardAction = { save() },
                    focusRequester = focus,
                    onFocusChanged = { focused = it },
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
 * project's name, its History, the tab bar and the footer tiles. Only the History tab and the
 * Settings tile are built yet, so they are the ones shown.
 */
@Composable
private fun MobileDrawer(model: AppModel, open: Boolean, onClose: () -> Unit) {
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
                .drawBehind { drawRect(tokens.border.color, Offset(size.width - 1.dp.toPx(), 0f), Size(1.dp.toPx(), size.height)) }
                .padding(end = 1.dp)
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
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val list = model.sidebarList
                val search = model.sidebarSearch
                if (list != null && search != null) {
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
                // The tab bar: 64 px slots, the History tab the one there is.
                Row(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.width(64.dp).padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        PpmIcon(ProductIcon.BotMessageSquare, 16.dp, tokens.accent.color)
                        BasicText("History", style = ppmText(10f, lineHeight = 15f).copy(color = tokens.accent.color))
                    }
                }
                Column(Modifier.fillMaxWidth().line(top = true, tokens.border.color).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .ppmClickable(pressedColor = tokens.panel2.color) {
                                onClose()
                                model.openSettings()
                            }
                            .padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        PpmIcon(ProductIcon.Settings, 16.dp, tokens.text3.color)
                        BasicText("Settings", style = ppmText(10f, lineHeight = 10f).copy(color = tokens.text3.color))
                    }
                    model.info?.version?.let { version ->
                        Box(
                            Modifier.fillMaxWidth().padding(top = 8.dp).line(top = true, tokens.border.color).padding(horizontal = 4.dp).heightIn(min = 44.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            BasicText("v$version", Modifier.padding(horizontal = 4.dp), style = ppmText(11f, lineHeight = 16.5f).copy(color = tokens.text.color))
                        }
                    }
                }
            }
        }
    }
}
