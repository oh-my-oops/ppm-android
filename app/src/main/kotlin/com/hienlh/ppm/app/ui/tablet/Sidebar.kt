package com.hienlh.ppm.app.ui.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.client.putUiPrefs
import com.hienlh.ppm.app.FocusAnchor
import com.hienlh.ppm.app.FocusStartAnchor
import com.hienlh.ppm.app.focusStartArea
import com.hienlh.ppm.app.ui.CloudSharePopover
import com.hienlh.ppm.app.ui.ContextMenuPopup
import com.hienlh.ppm.app.ui.FeatureBadge
import com.hienlh.ppm.app.ui.FeatureBadgeKind
import com.hienlh.ppm.app.ui.GroupList
import com.hienlh.ppm.app.ui.MenuItem
import com.hienlh.ppm.app.ui.MenuSeparator
import com.hienlh.ppm.app.ui.PlacedPopup
import com.hienlh.ppm.app.ui.ProjectAvatar
import com.hienlh.ppm.app.ui.SessionHistoryPanel
import com.hienlh.ppm.app.ui.contextMenuGesture
import com.hienlh.ppm.app.ui.unreadSessions
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.WebCursor
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.kit.HoverTooltip
import com.hienlh.ppm.design.kit.kitFocusRing
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.protocol.ProjectInfo
import com.hienlh.ppm.protocol.SessionUnread
import com.hienlh.ppm.state.formatShortcut
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.math.roundToInt

/**
 * The sidebar (`sidebar.tsx`, `Tablet 2 · Section 3: Rail and sidebar in every state`):
 * 60 dp rail, 48 dp header, resizable 200–600 dp (double-click handle resets to 336 dp).
 * In portrait (`[portraitRailOnly] == true`), only the 60 dp rail is inline and the section body
 * slides over a scrim ([SidebarSlideOverPanel]).
 */
@Composable
fun Sidebar(model: AppModel, portraitRailOnly: Boolean = false) {
    val tokens = LocalPpmTokens.current
    val shellState = LocalTabletShellState.current
    var flyoutAt by remember { mutableStateOf<Rect?>(null) }
    var shellBottom by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    val switcher = remember { Placed() }
    val sidebarAnchor = remember { FocusAnchor() }
    val flyoutReq = shellState?.projectFlyoutRequest ?: 0
    LaunchedEffect(flyoutReq) {
        if (flyoutReq > 0) {
            val coords = switcher.coordinates
            flyoutAt = if (coords != null) {
                Rect(coords.positionOnScreen(), coords.size.toSize())
            } else {
                Rect(Offset(68f, 52f), androidx.compose.ui.geometry.Size(260f, 40f))
            }
        }
    }
    if (model.sidebarCollapsed || portraitRailOnly) {
        Column(
            Modifier
                .width(RAIL_WIDTH)
                .fillMaxHeight()
                .focusStartArea(sidebarAnchor)
                .onGloballyPositioned { shellBottom = it.positionOnScreen().y + it.size.height }
                .background(tokens.rail.color)
                .edge(Edge.Right, tokens.border.color),
        ) {
            FocusStartAnchor(sidebarAnchor)
            val expandShortcut = formatShortcut(model.keyBindings.binding("toggle-sidebar")).ifEmpty { "Ctrl+B" }
            HoverTooltip(
                label = if (model.sidebarCollapsed) "Expand sidebar" else "Collapse sidebar",
                shortcut = expandShortcut,
            ) {
                var focused by remember { mutableStateOf(false) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(BAR_HEIGHT)
                        .edge(Edge.Bottom, tokens.border.color)
                        .onFocusChanged { focused = it.isFocused }
                        .kitFocusRing(focused)
                        .semantics { contentDescription = if (model.sidebarCollapsed) "Expand sidebar" else "Toggle sidebar" }
                        .ppmClickable(
                            pressedColor = tokens.panel2.color,
                            hoverColor = tokens.panel2.color,
                            onClickLabel = if (model.sidebarCollapsed) "Expand sidebar" else "Toggle sidebar",
                        ) { model.toggleSidebar() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (model.sidebarCollapsed) {
                        PpmIcon(ProductIcon.PanelLeftOpen, 18.dp, tokens.text2.color)
                    } else {
                        BasicText(
                            "PPM",
                            style = ppmText(14f, FontWeight.ExtraBold, lineHeight = 14f).copy(color = tokens.accent.color, letterSpacing = (-0.35).sp),
                        )
                    }
                }
            }
            NavSectionRail(model, Modifier.weight(1f), portraitRailOnly = portraitRailOnly)
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
                    NavSectionRail(model, Modifier.edge(Edge.Right, tokens.border.color), portraitRailOnly = false)
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        SidebarSectionBody(
                            model = model,
                            onChooseProject = {
                                val coords = switcher.coordinates
                                flyoutAt = if (coords != null) {
                                    Rect(coords.positionOnScreen(), coords.size.toSize())
                                } else {
                                    Rect(Offset(68f, 52f), androidx.compose.ui.geometry.Size(260f, 40f))
                                }
                            },
                        )
                    }
                }
            }
            ResizeLine(
                resize = resize,
                modifier = Modifier.align(Alignment.CenterEnd),
                onResetWidth = {
                    model.resizeSidebar(SIDEBAR_REDESIGN_DEFAULT_WIDTH)
                    model.saveSidebarWidth()
                },
            )
            if (resize.held) {
                SidebarWidthReadout(
                    widthDp = model.sidebarWidth,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 56.dp, end = 12.dp),
                )
            }
        }
    }
    flyoutAt?.let { ProjectFlyout(model, it, shellBottom) { flyoutAt = null } }
}

/**
 * Slide-over sidebar panel for portrait tablet (`R2 · Portrait — sidebar slides over a scrim`):
 * 360 dp wide panel next to the 60 dp inline rail, with project switcher header and active section content.
 */
@Composable
internal fun SidebarSlideOverPanel(model: AppModel, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    var flyoutAt by remember { mutableStateOf<Rect?>(null) }
    var shellBottom by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    val switcher = remember { Placed() }
    Column(
        modifier
            .width(360.dp)
            .onGloballyPositioned { shellBottom = it.positionOnScreen().y + it.size.height }
            .ppmShadow(tokens.shadowFloat, RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp))
            .background(tokens.panel.color)
            .edge(Edge.Right, tokens.border.color),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(BAR_HEIGHT)
                .edge(Edge.Bottom, tokens.border.color)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.weight(1f)) {
                ProjectSwitcherButton(model, open = flyoutAt != null, Modifier.onGloballyPositioned { switcher.coordinates = it }) {
                    switcher.coordinates?.let { flyoutAt = Rect(it.positionOnScreen(), it.size.toSize()) }
                }
            }
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .semantics { contentDescription = "Close sidebar" }
                    .ppmClickable(
                        pressedColor = tokens.panel2.color,
                        hoverColor = tokens.panel2.color,
                        onClickLabel = "Close sidebar",
                    ) { model.toggleSidebar() },
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.X, 16.dp, tokens.text2.color)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            SidebarSectionBody(
                model = model,
                onChooseProject = {
                    switcher.coordinates?.let { flyoutAt = Rect(it.positionOnScreen(), it.size.toSize()) }
                },
            )
        }
    }
    flyoutAt?.let { ProjectFlyout(model, it, shellBottom) { flyoutAt = null } }
}

@Composable
private fun SidebarSectionBody(model: AppModel, onChooseProject: () -> Unit) {
    val sec = model.sidebarSection
    when {
        sec == "teams" -> GroupList(model)
        sec == "explorer" -> com.hienlh.ppm.app.ui.files.ExplorerBody(model)
        sec == "search" -> com.hienlh.ppm.app.ui.files.SearchBody(model)
        sec == "git" -> com.hienlh.ppm.app.ui.git.GitStatusPanel(model)
        sec == "database" -> com.hienlh.ppm.app.ui.database.DatabaseSidebarPane(model)
        sec == "designs" -> com.hienlh.ppm.app.ui.design.DesignsSidebarPane(model)
        sec == "tunnels" -> com.hienlh.ppm.app.ui.tunnels.PortForwardingPanel(model)
        sec == "ai-resources" -> com.hienlh.ppm.app.ui.AiResourcesSidebarPanel(model)
        sec.startsWith("ext:") -> {
            val viewId = sec.removePrefix("ext:")
            val contribs = model.extensions?.contributions?.collectAsState()?.value
            val viewName = contribs?.views?.values?.flatten()?.firstOrNull { it.id == viewId }?.name ?: viewId
            com.hienlh.ppm.app.ui.extensions.ExtensionTreeViewPanel(model, viewId, viewName)
        }
        else -> HistorySection(model, onChooseProject = onChooseProject)
    }
}

/** `w-[60px] border-r hover:bg-surface-elevated`, the wordmark that folds the sidebar. */
@Composable
private fun Wordmark(model: AppModel) {
    val tokens = LocalPpmTokens.current
    val shortcut = formatShortcut(model.keyBindings.binding("toggle-sidebar")).ifEmpty { "Ctrl+B" }
    HoverTooltip(label = "Collapse sidebar", shortcut = shortcut) {
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
}

/**
 * The project switcher's button: the project's squircle, its name over its path, and the
 * up-down chevrons (`h-9 px-2 rounded-xl gap-[9px]`, lit while its list is open or a mouse is on it).
 */
@Composable
private fun ProjectSwitcherButton(model: AppModel, open: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(12.dp)
    val project = model.currentProject
    Row(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(shape)
            .background(if (open) tokens.panel2.color else Color.Transparent)
            .ppmClickable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, shape = shape, onClickLabel = "Switch project", onClick = onClick)
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
 * The sidebar's right edge dragged to resize it (200–600 dp, 12 dp hit zone).
 */
private fun Modifier.resizesSidebar(model: AppModel, resize: SidebarResize): Modifier = composed {
    val density = LocalDensity.current.density
    screenDrag(
        horizontal = true,
        cursor = WebCursor.ResizeHorizontal,
        takes = { down ->
            val inside = size.width - 1.dp.toPx() - down.position.x
            inside > 0f && inside <= (if (down.type == PointerType.Mouse) 6.dp else 12.dp).toPx()
        },
        onStart = { down ->
            resize.held = true
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
 * The sidebar's resize handle (`12 dp` touch hit zone, `6 × 40 dp` grip pill, double-tap/click resets to 336 dp).
 */
@Composable
private fun ResizeLine(resize: SidebarResize, modifier: Modifier, onResetWidth: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover()
    Box(
        modifier
            .fillMaxHeight()
            .width(12.dp)
            .pointerHoverIcon(WebCursor.ResizeHorizontal)
            .hoverable(hover.source)
            .pointerInput(onResetWidth) {
                detectTapGestures(onDoubleTap = { onResetWidth() })
            },
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(2.dp)
                .background(tokens.accent.opacity(if (resize.held) 0.7 else 0.35 * hover.lit).color),
        )
        Box(
            Modifier
                .width(6.dp)
                .height(40.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(
                    if (resize.held) tokens.accent.color
                    else tokens.border.mix(tokens.accent, hover.lit.toDouble()).opacity(0.6).color,
                ),
        )
    }
}

@Composable
private fun SidebarWidthReadout(widthDp: Int, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .ppmShadow(tokens.shadowFloat, shape)
            .clip(shape)
            .background(tokens.text.color, shape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        BasicText(
            "$widthDp dp · double-click resets",
            style = ppmText(11f, FontWeight.Medium, FontKind.MONO, lineHeight = 14f).copy(color = tokens.panel2.color),
        )
    }
}

/** Chat History, or empty state with "Choose project" CTA when there is no project (`R5`). */
@Composable
private fun HistorySection(model: AppModel, onChooseProject: () -> Unit = {}) {
    val tokens = LocalPpmTokens.current
    val list = model.sidebarList
    val search = model.sidebarSearch
    if (list != null && search != null) {
        SessionHistoryPanel(model, list, search, onNavigate = {})
    } else {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(tokens.panel2.color),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.FolderOpen, 20.dp, tokens.text3.color)
            }
            BasicText(
                "No project selected",
                style = ppmText(13f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color, textAlign = TextAlign.Center),
            )
            BasicText(
                "Select a project to view chat history",
                style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color, textAlign = TextAlign.Center),
            )
            val btnShape = RoundedCornerShape(999.dp)
            Box(
                Modifier
                    .height(36.dp)
                    .clip(btnShape)
                    .background(tokens.accent.color, btnShape)
                    .ppmClickable(
                        pressedColor = tokens.accent.opacity(0.85).color,
                        hoverColor = tokens.accent.opacity(0.9).color,
                        shape = btnShape,
                        onClickLabel = "Choose project",
                        onClick = onChooseProject,
                    )
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    "Choose project",
                    style = ppmText(12.5f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.accentFg.color),
                )
            }
        }
    }
}

/**
 * Default built-in sidebar sections in web catalog order (`BUILTIN_SIDEBAR_TABS`).
 */
internal val DEFAULT_RAIL_SECTIONS = listOf(
    "history",
    "teams",
    "designs",
    "explorer",
    "search",
    "git",
    "database",
    "tunnels",
    "ai-resources",
)

/**
 * Resolves the effective sidebar section order, applying [overrideOrder] when present over [model]'s order.
 */
internal fun effectiveSidebarSections(model: AppModel, overrideOrder: List<String>? = null): List<String> {
    val base = model.sidebarSections
    if (overrideOrder.isNullOrEmpty()) return base
    return (overrideOrder.filter { it in base } + base).distinct()
}

/**
 * Moves [section] by [delta] (-1 = Move up, +1 = Move down) inside [sections].
 */
internal fun moveRailSection(sections: List<String>, section: String, delta: Int): List<String> {
    val idx = sections.indexOf(section)
    if (idx < 0) return sections
    val target = (idx + delta).coerceIn(0, sections.lastIndex)
    if (target == idx) return sections
    val mut = sections.toMutableList()
    mut.removeAt(idx)
    mut.add(target, section)
    return mut
}

/**
 * Applies a new rail section order (or empty list to reset) to [model] and [shellState],
 * updating `model.sidebarTabOrder` and syncing to `/api/settings/ui-prefs`.
 */
internal fun applyRailSectionOrder(model: AppModel, shellState: TabletShellState?, newOrder: List<String>) {
    shellState?.railOrderOverride = newOrder.ifEmpty { null }
    runCatching {
        val setter = AppModel::class.java.getDeclaredMethod("setSidebarTabOrder", List::class.java)
        setter.isAccessible = true
        setter.invoke(model, newOrder)
    }
    val api = model.api ?: return
    model.scope.launch {
        runCatching {
            api.putUiPrefs(
                buildJsonObject {
                    put("sidebarTabOrder", JsonArray(newOrder.map(::JsonPrimitive)))
                },
            )
        }
    }
}

/**
 * The section rail (`nav-section-rail.tsx`, `Tablet 2 · Section 3`):
 * 60 dp wide, 48 dp touch items, top group scrolling under a bottom fade, long-press / right-click
 * context menu (`Move up`, `Move down`, `Reset rail order`), and landscape `More` footer menu (`R1`)
 * vs portrait 7-item footer (`R2`).
 */
@Composable
private fun NavSectionRail(model: AppModel, modifier: Modifier = Modifier, portraitRailOnly: Boolean = false) {
    val tokens = LocalPpmTokens.current
    val shellState = LocalTabletShellState.current
    val unread = model.unreadSessions()
    val scroll = rememberScrollState()
    val sections = effectiveSidebarSections(model, shellState?.railOrderOverride)
    var reorderMenuFor by remember { mutableStateOf<Pair<String, Offset>?>(null) }
    val foldFooter = shellState != null && !shellState.isPortrait && !portraitRailOnly

    Column(Modifier.width(RAIL_WIDTH).then(modifier).fillMaxHeight().background(tokens.rail.color)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (section in sections) {
                    val active = model.sidebarSection == section
                    val (icon, label, badge, featBadge, shortcut) = when (section) {
                        "explorer" -> RailSectionSpec(ProductIcon.FolderOpen, "Explorer", 0, null, null)
                        "search" -> RailSectionSpec(
                            ProductIcon.Search,
                            "Search",
                            0,
                            null,
                            formatShortcut(model.keyBindings.binding("open-search")).ifEmpty { "Ctrl+Shift+F" },
                        )
                        "git" -> RailSectionSpec(
                            ProductIcon.GitBranch,
                            "Git",
                            model.gitChangeCount(model.selectedProject),
                            null,
                            formatShortcut(model.keyBindings.binding("open-git-status")).ifEmpty { "Ctrl+Shift+E" },
                        )
                        "database" -> RailSectionSpec(ProductIcon.Database, "Database", 0, null, null)
                        "designs" -> RailSectionSpec(ProductIcon.Palette, "Designs", 0, FeatureBadgeKind.NEW, null)
                        "teams" -> RailSectionSpec(ProductIcon.Users, "Teams", 0, FeatureBadgeKind.BETA, null)
                        "tunnels" -> RailSectionSpec(ProductIcon.Globe, "Port Forwarding", 0, null, null)
                        "ai-resources" -> RailSectionSpec(ProductIcon.Sparkles, "AI Resources", 0, null, null)
                        else -> RailSectionSpec(
                            ProductIcon.BotMessageSquare,
                            "Chat History",
                            model.unread(model.selectedProject, unread).size,
                            null,
                            null,
                        )
                    }
                    NavItem(
                        icon = icon,
                        label = label,
                        active = active,
                        badge = badge,
                        featureBadge = featBadge,
                        shortcut = shortcut,
                        onContextMenu = { at -> reorderMenuFor = section to at },
                    ) {
                        model.pickSidebarSection(section)
                    }
                }
                val contribs = model.extensions?.contributions?.collectAsState()?.value
                for (view in (contribs?.views?.get("sidebar") ?: contribs?.views?.get("explorer")).orEmpty()) {
                    val secId = "ext:${view.id}"
                    val active = model.sidebarSection == secId
                    NavItem(ProductIcon.Puzzle, view.name, active, badge = 0) {
                        model.pickSidebarSection(secId)
                    }
                }
            }
            if (scroll.canScrollForward) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(24.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, tokens.rail.color))),
                )
            }
            reorderMenuFor?.let { (section, at) ->
                val idx = sections.indexOf(section)
                ContextMenuPopup(at, onDismiss = { reorderMenuFor = null }) {
                    if (idx > 0) {
                        MenuItem(
                            "Move up",
                            onClick = {
                                reorderMenuFor = null
                                applyRailSectionOrder(model, shellState, moveRailSection(sections, section, -1))
                            },
                        )
                    }
                    if (idx >= 0 && idx < sections.lastIndex) {
                        MenuItem(
                            "Move down",
                            onClick = {
                                reorderMenuFor = null
                                applyRailSectionOrder(model, shellState, moveRailSection(sections, section, +1))
                            },
                        )
                    }
                    MenuSeparator()
                    MenuItem(
                        "Reset rail order",
                        onClick = {
                            reorderMenuFor = null
                            applyRailSectionOrder(model, shellState, emptyList())
                        },
                    )
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().edge(Edge.Top, tokens.border.color).padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            NotificationBell(model)
            if (foldFooter) {
                if (model.info?.minimalMode != true) {
                    MoreFooterButton(model, shellState!!)
                }
                val settingsShortcut = formatShortcut(model.keyBindings.binding("open-settings")).ifEmpty { "Ctrl+," }
                FooterUtil(ProductIcon.Settings, "Settings", shortcut = settingsShortcut) { model.openSettings() }
                if (model.cloudShareOpen && !LocalPpmMobile.current) {
                    val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
                    val place = remember(gap) {
                        { anchor: IntRect, _: IntSize, box: IntSize ->
                            IntOffset(anchor.right + gap, anchor.bottom - box.height)
                        }
                    }
                    PlacedPopup(place, onDismiss = { model.cloudShareOpen = false }) {
                        CloudSharePopover(model, onClose = { model.cloudShareOpen = false })
                    }
                }
            } else {
                if (model.info?.minimalMode != true) {
                    CloudShareButton(model)
                    FooterUtil(ProductIcon.FolderTree, "File Explorer", featureBadge = FeatureBadgeKind.NEW) { model.openExplorer() }
                    if (model.remoteDesktopAvailable) {
                        FooterUtil(
                            ProductIcon.MonitorSmartphone,
                            "Remote Desktop",
                            featureBadge = FeatureBadgeKind.BETA,
                            modifier = Modifier.testTag("sidebar-remote-desktop"),
                        ) { model.openRemoteDesktop() }
                    }
                    if (model.androidAvailable) {
                        FooterUtil(ProductIcon.Smartphone, "Android", featureBadge = FeatureBadgeKind.BETA) { model.openAndroid() }
                    }
                    FooterUtil(ProductIcon.ScrollText, "Logs", dot = model.showLogsBadgeDot) { model.openLogs() }
                }
                val settingsShortcut = formatShortcut(model.keyBindings.binding("open-settings")).ifEmpty { "Ctrl+," }
                FooterUtil(ProductIcon.Settings, "Settings", shortcut = settingsShortcut) { model.openSettings() }
            }
        }
    }
}

private data class RailSectionSpec(
    val icon: ProductIcon,
    val label: String,
    val badge: Int,
    val featureBadge: FeatureBadgeKind?,
    val shortcut: String?,
)

/**
 * Landscape rail footer `More` button (`R1 · Landscape — 5 rare utilities folded into More`):
 * folds `Cloud & Share`, `File Explorer` (`NEW`), `Remote Desktop` (`BETA`), `Android` (`BETA`),
 * and `Logs` (with red bug count pill) into a 280 dp popover menu. Carries a red dot when Logs has bugs.
 */
@Composable
private fun MoreFooterButton(model: AppModel, shellState: TabletShellState) {
    val tokens = LocalPpmTokens.current
    val open = shellState.moreMenuOpen
    val shape = RoundedCornerShape(10.dp)
    val hover = rememberHover(enabled = !open)
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        HoverTooltip(label = "More tools", enabled = !open) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(shape)
                    .background(if (open) tokens.accent.opacity(0.12).color else Color.Transparent)
                    .semantics { contentDescription = "More" }
                    .ppmClickable(
                        pressedColor = tokens.panel2.color,
                        hoverColor = if (open) Color.Transparent else tokens.panel2.color,
                        shape = shape,
                        onClickLabel = "More",
                        source = hover.source,
                    ) { shellState.moreMenuOpen = !open },
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(
                    ProductIcon.MoreHorizontal,
                    18.dp,
                    (if (open) tokens.accent else tokens.text.mix(tokens.text3, hover.lit.toDouble())).color,
                )
                if (model.showLogsBadgeDot) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 6.dp, end = 6.dp)
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(tokens.error.color),
                    )
                }
            }
        }
        if (open) {
            val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
            val place = remember(gap) {
                { anchor: IntRect, _: IntSize, box: IntSize ->
                    IntOffset(anchor.right + gap, anchor.bottom - box.height)
                }
            }
            val popupShape = RoundedCornerShape(14.dp)
            PlacedPopup(place, onDismiss = { shellState.moreMenuOpen = false }) {
                Column(
                    Modifier
                        .width(280.dp)
                        .ppmShadow(tokens.shadowFloat, popupShape)
                        .clip(popupShape)
                        .background(tokens.panel2.color, popupShape)
                        .cssBorder(tokens.border.color, popupShape)
                        .padding(6.dp),
                ) {
                    MoreMenuRow(
                        icon = ProductIcon.Cloud,
                        label = "Cloud & Share",
                        onClick = {
                            shellState.moreMenuOpen = false
                            model.cloudShareOpen = true
                        },
                    )
                    MoreMenuRow(
                        icon = ProductIcon.FolderTree,
                        label = "File Explorer",
                        featureBadge = FeatureBadgeKind.NEW,
                        onClick = {
                            shellState.moreMenuOpen = false
                            model.openExplorer()
                        },
                    )
                    if (model.remoteDesktopAvailable) {
                        MoreMenuRow(
                            icon = ProductIcon.MonitorSmartphone,
                            label = "Remote Desktop",
                            featureBadge = FeatureBadgeKind.BETA,
                            modifier = Modifier.testTag("sidebar-remote-desktop"),
                            onClick = {
                                shellState.moreMenuOpen = false
                                model.openRemoteDesktop()
                            },
                        )
                    }
                    if (model.androidAvailable) {
                        MoreMenuRow(
                            icon = ProductIcon.Smartphone,
                            label = "Android",
                            featureBadge = FeatureBadgeKind.BETA,
                            onClick = {
                                shellState.moreMenuOpen = false
                                model.openAndroid()
                            },
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .height(1.dp)
                            .background(tokens.borderSoft.color),
                    )
                    MoreMenuRow(
                        icon = ProductIcon.ScrollText,
                        label = "Logs",
                        bugCount = if (model.showLogsBadgeDot) model.logsLikelyBugs else 0,
                        onClick = {
                            shellState.moreMenuOpen = false
                            model.openLogs()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MoreMenuRow(
    icon: ProductIcon,
    label: String,
    modifier: Modifier = Modifier,
    featureBadge: FeatureBadgeKind? = null,
    bugCount: Int = 0,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .semantics { contentDescription = label }
            .ppmClickable(
                pressedColor = tokens.panel.color,
                hoverColor = tokens.panel.color,
                shape = shape,
                onClickLabel = label,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PpmIcon(icon, 18.dp, tokens.text2.color)
        BasicText(
            label,
            Modifier.weight(1f),
            style = ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(color = tokens.text.color),
            maxLines = 1,
        )
        if (featureBadge != null) {
            FeatureBadge(featureBadge)
        }
        if (bugCount > 0) {
            Box(
                Modifier
                    .height(18.dp)
                    .widthIn(min = 18.dp)
                    .clip(CircleShape)
                    .background(tokens.error.color)
                    .padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    if (bugCount > 99) "99+" else "$bugCount",
                    style = ppmText(10f, FontWeight.SemiBold, lineHeight = 12f).copy(color = Color.White),
                )
            }
        }
    }
}

@Composable
private fun CloudShareButton(model: AppModel) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val open = model.cloudShareOpen
    val shape = RoundedCornerShape(10.dp)
    val hover = rememberHover(enabled = !open)
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        HoverTooltip(label = "Cloud & Share", enabled = !open) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(shape)
                    .background(if (open) tokens.accent.opacity(0.1).color else Color.Transparent)
                    .semantics { contentDescription = "Cloud & Share" }
                    .ppmClickable(
                        pressedColor = if (open) Color.Transparent else tokens.panel2.color,
                        hoverColor = if (open) Color.Transparent else tokens.panel2.color,
                        shape = shape,
                        onClickLabel = "Cloud & Share",
                        source = hover.source,
                    ) { model.cloudShareOpen = !open },
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.Cloud, 18.dp, (if (open) tokens.accent else tokens.text.mix(tokens.text3, hover.lit.toDouble())).color)
            }
        }
        if (open && !mobile) {
            val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
            val place = remember(gap) {
                { anchor: IntRect, _: IntSize, box: IntSize ->
                    IntOffset(anchor.right + gap, anchor.bottom - box.height)
                }
            }
            PlacedPopup(place, onDismiss = { model.cloudShareOpen = false }) {
                CloudSharePopover(model, onClose = { model.cloudShareOpen = false })
            }
        }
    }
}

/**
 * A section in the 60 dp rail (`48 dp` touch target, `40 dp` visual tile, `12 dp` radius):
 * the open one on the accent wash with a left accent bar, hover tooltip, keyboard focus ring,
 * and long-press / right-click context menu for rail reordering.
 */
@Composable
private fun NavItem(
    icon: ProductIcon,
    label: String,
    active: Boolean,
    badge: Int,
    featureBadge: FeatureBadgeKind? = null,
    shortcut: String? = null,
    onContextMenu: ((Offset) -> Unit)? = null,
    onClick: () -> Unit,
) {
    HoverTooltip(label = label, shortcut = shortcut) {
        Box(
            Modifier
                .size(48.dp)
                .then(if (onContextMenu != null) Modifier.contextMenuGesture(onContextMenu) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            NavTile(icon, label, active, badge, onClick)
            if (featureBadge != null) FeatureBadge(featureBadge, Modifier.align(Alignment.TopEnd).offset((-2).dp, 2.dp), corner = true)
        }
    }
}

@Composable
private fun NavTile(icon: ProductIcon, label: String, active: Boolean, badge: Int, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(12.dp)
    val hover = rememberHover(enabled = !active)
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(40.dp)
            .clip(shape)
            .onFocusChanged { focused = it.isFocused }
            .kitFocusRing(focused, shape)
            .then(
                if (active) {
                    Modifier.drawBehind {
                        val radius = CornerRadius(12.dp.toPx())
                        val tile = Path().apply { addRoundRect(RoundRect(size.toRect(), radius)) }
                        drawPath(tile, tokens.accentWash.color)
                        val moved = Path().apply { addRoundRect(RoundRect(size.toRect().translate(Offset(3.dp.toPx(), 0f)), radius)) }
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
            )
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(icon, 20.dp, (if (active) tokens.accent else tokens.text.mix(tokens.text3, hover.lit.toDouble())).color)
        if (badge > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 3.dp, end = 3.dp)
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

/** A footer utility (`48 dp` touch target, `40 dp` visual tile, `10 dp` radius). */
@Composable
private fun FooterUtil(
    icon: ProductIcon,
    label: String,
    modifier: Modifier = Modifier,
    dot: Boolean = false,
    featureBadge: FeatureBadgeKind? = null,
    shortcut: String? = null,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(10.dp)
    val hover = rememberHover()
    var focused by remember { mutableStateOf(false) }
    HoverTooltip(label = label, shortcut = shortcut) {
        Box(modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(shape)
                    .onFocusChanged { focused = it.isFocused }
                    .kitFocusRing(focused, shape)
                    .semantics { contentDescription = label }
                    .ppmClickable(
                        pressedColor = tokens.panel2.color,
                        hoverColor = tokens.panel2.color,
                        shape = shape,
                        onClickLabel = label,
                        source = hover.source,
                        onClick = onClick,
                    )
                    .focusable(),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(icon, 18.dp, tokens.text.mix(tokens.text3, hover.lit.toDouble()).color)
                if (dot) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 6.dp, end = 6.dp)
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(tokens.error.color),
                    )
                }
            }
            if (featureBadge != null) FeatureBadge(featureBadge, Modifier.align(Alignment.TopEnd).offset((-2).dp, 2.dp), corner = true)
        }
    }
}

/** The rail's width (60 dp), the top bar's height (48 dp), and the default tablet sidebar width (336 dp). */
val RAIL_WIDTH = 60.dp
val BAR_HEIGHT = 48.dp
const val SIDEBAR_REDESIGN_DEFAULT_WIDTH = 336
