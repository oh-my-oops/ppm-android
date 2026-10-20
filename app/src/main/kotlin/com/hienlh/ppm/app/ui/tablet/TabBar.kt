package com.hienlh.ppm.app.ui.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.ContextMenuPopup
import com.hienlh.ppm.app.ui.MenuIcon
import com.hienlh.ppm.app.ui.MenuItem
import com.hienlh.ppm.app.ui.MenuSeparator
import com.hienlh.ppm.app.ui.SetTagMenuItem
import com.hienlh.ppm.app.ui.TabIcon
import com.hienlh.ppm.app.ui.TypingDots
import com.hienlh.ppm.app.ui.pressable
import com.hienlh.ppm.app.ui.runningSessions
import com.hienlh.ppm.app.ui.tabTitle
import com.hienlh.ppm.app.ui.unreadSessions
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.SessionUnread
import com.hienlh.ppm.state.workspace.WorkspaceTab
import com.hienlh.ppm.state.workspace.tabSessionId
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** `TYPE_PRIORITY` in use-tab-overflow.ts: the most urgent unread wins an arrow's dot. */
private val UNREAD_PRIORITY = mapOf("done" to 0, "question" to 1, "approval_request" to 2)

/**
 * A panel's tab bar (tab-bar.tsx, default style): 41 px with its bottom border, the tabs scrolling
 * sideways with the "+" held at their end, and an arrow on each side with more tabs past it —
 * dotted when one of those has an unread chat. A tab added to the bar, and the active one when
 * the bar first shows, is scrolled into view.
 */
@Composable
fun TabBar(model: AppModel, panelId: String, tabs: List<WorkspaceTab>, activeTabId: String?) {
    val tokens = LocalPpmTokens.current
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val unread = model.unreadSessions()
    val running = model.runningSessions()
    val drag = LocalTabDrag.current
    DisposableEffect(drag, panelId) { onDispose { drag?.bars?.remove(panelId) } }
    // Each tab's span in the scrolled row, in pixels, to tell which ones are out of view.
    val spans = remember { mutableStateMapOf<String, IntRange>() }
    var viewport by remember { mutableIntStateOf(0) }
    // From 0, as the web's `prevTabCount`: a bar's first frame brings its active tab into view too.
    var shownCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(tabs.size, activeTabId) {
        val grew = tabs.size > shownCount
        shownCount = tabs.size
        if (!grew || activeTabId == null) return@LaunchedEffect
        // The new tab is placed in the frame after the one that added it.
        val span = withTimeoutOrNull(1_000) { snapshotFlow { spans[activeTabId] }.filterNotNull().first() } ?: return@LaunchedEffect
        when {
            span.first < scroll.value -> scroll.animateScrollTo(span.first)
            span.last > scroll.value + viewport -> scroll.animateScrollTo(span.last - viewport)
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .edge(Edge.Bottom, tokens.border.color)
            .onGloballyPositioned { coordinates -> drag?.bars?.set(panelId, coordinates.boundsInRoot()) },
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f, fill = false).fillMaxHeight().onSizeChanged { viewport = it.width }.horizontalScroll(scroll),
            ) {
                for (tab in tabs) {
                    val sessionId = tabSessionId(tab)
                    val entry = sessionId?.let { unread[it] }?.takeIf { it.count > 0 }
                    TabChip(
                        model,
                        tab,
                        panelId,
                        active = tab.id == activeTabId,
                        unread = entry,
                        streaming = tab.type == "chat" && sessionId != null && sessionId in running,
                        modifier = Modifier.onPlaced { spans[tab.id] = it.positionInParent().x.toInt().let { x -> x..x + it.size.width } },
                        onSelect = { model.activate(tab.id, panelId) },
                        onClose = { model.closeTab(tab.id, panelId) },
                    )
                }
                // A mouse dropping past the last tab: `w-0.5 h-6 bg-primary rounded-full`.
                if (drag?.insert == BarInsert(panelId, null)) {
                    Box(Modifier.align(Alignment.CenterVertically).width(2.dp).height(24.dp).background(tokens.accent.color, CircleShape))
                }
            }
            // "+": the command palette on the web, which this app does not have yet; a new chat here until it does.
            Box(
                Modifier
                    .width(40.dp)
                    .fillMaxHeight()
                    .ppmClickable(pressedColor = Color.Transparent, onClickLabel = "New chat") {
                        model.focusPanel(panelId)
                        model.scope.launch { model.newChat(panelId = panelId) }
                    }
                    .padding(top = 2.dp),
                contentAlignment = Alignment.Center,
            ) { PpmIcon(ProductIcon.Plus, 16.dp, tokens.text2.color) }
        }
        val hidden = hiddenUnread(tabs, spans, unread, scroll.value, scroll.value + viewport)
        if (scroll.value > 1) {
            ScrollArrow(left = true, dot = hidden.first, Modifier.align(Alignment.CenterStart)) {
                scope.launch { scroll.animateScrollTo(scroll.value - with(density) { 150.dp.roundToPx() }) }
            }
        }
        if (scroll.value < scroll.maxValue - 1) {
            ScrollArrow(left = false, dot = hidden.second, Modifier.align(Alignment.CenterEnd).padding(end = 40.dp)) {
                scope.launch { scroll.animateScrollTo(scroll.value + with(density) { 150.dp.roundToPx() }) }
            }
        }
    }
}

/** `getHiddenUnreadDirection`: the most urgent unread kind among the tabs wholly past each edge. */
private fun hiddenUnread(
    tabs: List<WorkspaceTab>,
    spans: Map<String, IntRange>,
    unread: Map<String, SessionUnread>,
    viewLeft: Int,
    viewRight: Int,
): Pair<String?, String?> {
    var left: String? = null
    var right: String? = null
    for (tab in tabs) {
        if (tab.type != "chat") continue
        val entry = tabSessionId(tab)?.let { unread[it] }?.takeIf { it.count > 0 } ?: continue
        val span = spans[tab.id] ?: continue
        val priority = UNREAD_PRIORITY[entry.type] ?: 0
        if (span.last <= viewLeft && priority > (left?.let { UNREAD_PRIORITY[it] ?: 0 } ?: -1)) left = entry.type
        if (span.first >= viewRight && priority > (right?.let { UNREAD_PRIORITY[it] ?: 0 } ?: -1)) right = entry.type
    }
    return left to right
}

/** `size-8` over a fade from the background, the chevron dotted for an unread tab out that way. */
@Composable
private fun ScrollArrow(left: Boolean, dot: String?, modifier: Modifier, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val bg = tokens.bgSolid.color
    val fade = if (left) listOf(bg, bg, Color.Transparent) else listOf(Color.Transparent, bg, bg)
    Box(
        modifier
            .size(32.dp)
            .background(Brush.horizontalGradient(fade))
            .ppmClickable(pressedColor = Color.Transparent, onClickLabel = if (left) "Scroll tabs left" else "Scroll tabs right", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box {
            PpmIcon(if (left) ProductIcon.ChevronLeft else ProductIcon.ChevronRight, 16.dp, tokens.text2.color)
            if (dot != null) {
                Box(
                    Modifier
                        .align(if (left) Alignment.TopEnd else Alignment.TopStart)
                        .offset(if (left) 2.dp else (-2).dp, (-4).dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(tokens.notification(dot).color),
                )
            }
        }
    }
}

/**
 * A tab (draggable-tab.tsx, default style): `px-3 h-[41px] gap-1 text-xs`, a 2 px top border in
 * the accent and the raised panel colour when active, a soft 1 px line on its right. Its icon
 * types while the chat's turn runs and carries the unread dot otherwise; a tag shows as a bar
 * along its left edge. The close button stays visible: a touch screen has no hover to show it.
 */
@Composable
private fun TabChip(
    model: AppModel,
    tab: WorkspaceTab,
    panelId: String,
    active: Boolean,
    unread: SessionUnread?,
    streaming: Boolean,
    modifier: Modifier,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val color = if (active) tokens.accent else tokens.text2
    val chat = model.chat(tab.id)
    val tag = chat?.let { c -> model.sessions.firstOrNull { it.id == c.session.id }?.tag?.color }?.let(Rgba::hex)
    val title = tabTitle(model, tab)
    val drag = LocalTabDrag.current
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    val snapshot = rememberGraphicsLayer()
    DisposableEffect(drag, tab.id) { onDispose { drag?.chips?.remove(tab.id) } }
    Box(
        modifier
            .fillMaxHeight()
            .onGloballyPositioned { drag?.chips?.set(tab.id, panelId to Rect(it.positionInRoot(), it.size.toSize())) }
            .then(if (drag == null) Modifier else Modifier.tabGestures(drag, model, { DraggedTab(tab.id, panelId, title, snapshot) }) { menuAt = it }),
    ) {
        // The tab as a mouse drag shows it: everything but the drop mark.
        Box(
            Modifier.fillMaxHeight().drawWithContent {
                snapshot.record { this@drawWithContent.drawContent() }
                drawLayer(snapshot)
            },
        ) {
            Row(
                Modifier
                    .fillMaxHeight()
                    .background(if (active) tokens.panel2.color else Color.Transparent)
                    .drawBehind {
                        if (active) drawRect(tokens.accent.color, Offset.Zero, Size(size.width, 2.dp.toPx()))
                        drawRect(tokens.borderSoft.color, Offset(size.width - 1.dp.toPx(), 0f), Size(1.dp.toPx(), size.height))
                    }
                    .pressable(pressedColor = if (active) Color.Transparent else tokens.panel.opacity(0.6).color, onClick = onSelect)
                    .padding(start = 12.dp, end = 13.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box {
                    if (streaming) {
                        PpmIcon(ProductIcon.MessageCircle, 16.dp, tokens.warning.color)
                        TypingDots(tokens.warning, Modifier.align(Alignment.Center))
                    } else {
                        TabIcon(tab, color)
                        if (unread != null && (!active || unread.manual)) {
                            Box(Modifier.align(Alignment.TopEnd).offset(4.dp, (-4).dp).size(8.dp).clip(CircleShape).background(tokens.notification(unread.type).color))
                        }
                    }
                }
                BasicText(
                    title,
                    Modifier.widthIn(max = 120.dp),
                    style = ppmText(12f, lineHeight = 16f).copy(color = color.color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (tab.closable) {
                    // `ml-1 rounded-sm p-0.5`, the X at 12 px.
                    Box(
                        Modifier
                            .padding(start = 4.dp)
                            .size(16.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .ppmClickable(pressedColor = tokens.panel2.color, onClickLabel = "Close tab", onClick = onClose),
                        contentAlignment = Alignment.Center,
                    ) { PpmIcon(ProductIcon.X, 12.dp, color.color) }
                }
            }
            if (tag != null) {
                // `absolute left-0 top-2 bottom-2 w-[3px] rounded-r-full`.
                Box(
                    Modifier.align(Alignment.CenterStart).padding(vertical = 8.dp).width(3.dp).fillMaxHeight().drawBehind {
                        val round = CornerRadius(size.width)
                        drawPath(Path().apply { addRoundRect(RoundRect(size.toRect(), topRight = round, bottomRight = round)) }, tag.color)
                    },
                )
            }
        }
        if (drag?.insert == BarInsert(panelId, tab.id)) {
            // A mouse dropping before this tab: `absolute left-0 top-1 bottom-1 w-0.5 bg-primary rounded-full`.
            Box(Modifier.align(Alignment.CenterStart).padding(vertical = 4.dp).width(2.dp).fillMaxHeight().background(tokens.accent.color, CircleShape))
        }
        menuAt?.let { at -> TabMenu(model, tab, panelId, at) { menuAt = null } }
    }
}

/**
 * A tab's menu (draggable-tab.tsx, with tab-bar.tsx's items for a chat): Mark as unread while the
 * chat has none and Set Tag while the project has tags, then Close, Close Others and Close to the
 * Right. Its window, dock and file items come with windows, terminals and the editor.
 */
@Composable
private fun TabMenu(model: AppModel, tab: WorkspaceTab, panelId: String, at: Offset, onDismiss: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val sessionId = tabSessionId(tab)
    val unread = sessionId?.let { model.unreadSessions()[it] }?.takeIf { it.count > 0 }
    val session = sessionId?.let { id -> model.sessions.firstOrNull { it.id == id } }
    fun pick(action: () -> Unit) {
        onDismiss()
        action()
    }
    ContextMenuPopup(at, onDismiss) {
        if (sessionId != null && unread == null) {
            MenuItem("Mark as unread", onClick = { pick { model.markUnread(sessionId, tab.title) } }, icon = { MenuIcon(ProductIcon.Circle, tokens.accent) })
            MenuSeparator()
        }
        if (session != null && model.tags.tags.isNotEmpty()) {
            SetTagMenuItem(model, session, onDismiss)
            MenuSeparator()
        }
        if (tab.closable) MenuItem("Close", onClick = { pick { model.closeTab(tab.id, panelId) } })
        MenuItem("Close Others", onClick = { pick { model.closeOtherTabs(tab.id) } })
        MenuItem("Close to the Right", onClick = { pick { model.closeTabsToRight(tab.id) } })
    }
}
