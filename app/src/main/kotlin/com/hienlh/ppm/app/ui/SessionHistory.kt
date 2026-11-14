package com.hienlh.ppm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.PrefsStore
import com.hienlh.ppm.app.specs
import com.hienlh.ppm.client.chatHistoryViewMode
import com.hienlh.ppm.client.putChatHistoryViewMode
import com.hienlh.ppm.client.specs
import com.hienlh.ppm.chat.RelativeTime
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFormat
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssScrollbar
import com.hienlh.ppm.design.kit.Sheet
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.protocol.ChatSearchHit
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.SessionTag
import com.hienlh.ppm.client.SpecSummary
import com.hienlh.ppm.state.ChatSearch
import com.hienlh.ppm.state.HistoryList
import com.hienlh.ppm.state.SessionTree
import com.hienlh.ppm.state.SessionTreeNode
import com.hienlh.ppm.state.SessionTreeRoot
import com.hienlh.ppm.state.SpecBarCell
import com.hienlh.ppm.state.SpecFamily
import com.hienlh.ppm.state.workspace.tabSessionId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The History tab of the drawer (`SessionHistoryList`, sidebar variant): a header that says when
 * the list syncs, a filter that also finds messages, the tag chips, then the chats. [onNavigate]
 * fires once one is opened, so the drawer can close.
 */
@Composable
fun SessionHistoryPanel(model: AppModel, list: HistoryList, search: ChatSearch, onNavigate: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val state by list.state.collectAsState()
    val found by search.state.collectAsState()
    val scope = rememberCoroutineScope()
    val query = remember(list) { TextFieldState(list.query) }
    LaunchedEffect(query, list, search) {
        snapshotFlow { query.text.toString() }.collect {
            list.query = it
            search.query = it
        }
    }
    val unread = model.unreadSessions()
    val mobile = LocalPpmMobile.current
    val viewMode = rememberHistoryViewMode(model)
    val project = model.selectedProject
    // The specs whose chats show their progress in the tree; none from a server without the field.
    var specList by remember(project) { mutableStateOf<List<SpecSummary>>(emptyList()) }
    val tree = viewMode.mode == "tree"
    LaunchedEffect(model.api, project, tree, model.sessions.size) {
        val api = model.api ?: return@LaunchedEffect
        if (!tree || project == null) return@LaunchedEffect
        specList = try {
            api.specs(project)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }
    Column(modifier.fillMaxSize()) {
        // `SidebarHeader`: `flex items-center gap-1 px-2 py-2 border-b border-border`.
        Row(
            Modifier.fillMaxWidth().line(top = false, tokens.border.color).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PpmIcon(ProductIcon.BotMessageSquare, 16.dp, tokens.accent.color)
            BasicText(
                "CHAT HISTORY",
                Modifier.weight(1f),
                style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text2.color, letterSpacing = 0.3.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            SyncIndicator(model.syncingSessions, model.sessionsSyncError)
            // The list/tree switch, where Source Control keeps its own.
            if (mobile) {
                val label = if (viewMode.mode == "tree") "Show as a list" else "Show as a tree"
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .ppmClickable(onClickLabel = label) { viewMode.toggle() }
                        .semantics { contentDescription = label }
                        .testTag("history-view-toggle"),
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(if (viewMode.mode == "tree") ProductIcon.List else ProductIcon.FolderTree, 16.dp, tokens.text2.color)
                }
            } else {
                // `Button variant="ghost" size="icon-xs"`: 24 px, a 14 px icon, `dark:hover:bg-accent/50`.
                val label = if (tree) "Show as a list" else "Show as a tree"
                val hover = rememberHover(instant = true)
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .webTitle(if (tree) "Show as a list" else "Group under the chat that opened them")
                        .ppmClickable(
                            pressedColor = tokens.panel2.opacity(0.5).color,
                            hoverColor = tokens.panel2.opacity(0.5).color,
                            fade = false,
                            source = hover.source,
                            onClickLabel = label,
                        ) { viewMode.toggle() }
                        .semantics { contentDescription = label }
                        .testTag("history-view-toggle"),
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(if (tree) ProductIcon.List else ProductIcon.FolderTree, 14.dp, tokens.text.color)
                }
            }
        }
        // Search + bulk delete + refresh: `flex items-center gap-1 px-2 py-2`.
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val shape = RoundedCornerShape(8.dp)
            var focused by remember { mutableStateOf(false) }
            // The input has the border and `py-1.5 pl-7 pr-2 text-xs` (on a phone 16 px with
            // `text-xs`'s 4/3 line); the icon sits `left-2` from its outer edge.
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(tokens.panel2.color)
                        .cssBorder(if (focused) tokens.accent.opacity(0.5).color else tokens.border.color, shape),
                ) {
                    PpmTextField(
                        query,
                        "Filter sessions…",
                        Modifier.fillMaxWidth().padding(start = 28.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        style = ppmText(16f, lineHeight = 64f / 3),
                        desktopStyle = ppmText(12f, lineHeight = 16f),
                        onFocusChanged = { focused = it },
                        cssStart = 28.dp,
                    )
                }
                PpmIcon(ProductIcon.Search, 14.dp, tokens.text3.color, Modifier.padding(start = 8.dp))
            }
            SquareIconButton(ProductIcon.CalendarX2, tokens.error, enabled = !model.deletingOld) { model.askDeleteOld = true }
            var refreshing by remember { mutableStateOf(false) }
            SquareIconButton(ProductIcon.RefreshCw, tokens.text, spinning = refreshing || state.loading, enabled = !refreshing) {
                scope.launch {
                    refreshing = true
                    model.refresh(list)
                    if (query.text.isNotBlank()) search.reload()
                    refreshing = false
                }
            }
        }
        val all = state.sessions(model.sessions)
        if (model.tags.tags.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PillChip("All (${all.size})", state.tag == null, null) { list.tag = null }
                for (tag in model.tags.tags) {
                    PillChip("${tag.name} (${model.tags.counts[tag.id] ?: 0})", state.tag == tag.id, Rgba.hex(tag.color) ?: tokens.text3) {
                        list.tag = if (state.tag == tag.id) null else tag.id
                    }
                }
            }
        }
        if (query.text.isNotBlank()) {
            SearchResults(model, found.page, found.loading, onNavigate, Modifier.weight(1f))
            return@Column
        }
        val sessions = state.shown(all)
        val sessionList = rememberLazyListState()
        when {
            state.loading && all.isEmpty() || (state.searchPage == null && model.sessionsLoad == AppModel.Load.Loading && all.isEmpty()) ->
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    PpmIcon(ProductIcon.Loader2, 14.dp, tokens.text3.color, spinning = true)
                }
            sessions.isEmpty() -> BasicText(
                "No sessions yet",
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                style = ppmText(11f, lineHeight = 16.5f).copy(color = tokens.text3.color, textAlign = TextAlign.Center),
            )
            mobile -> {
                // The tree is built over the list sorted by activity, so each family sits where its newest member would.
                val roots = remember(sessions, tree) {
                    if (tree) SessionTree.build(sessions.sortedWith(compareByDescending<SessionInfo> { it.pinned }.thenByDescending { it.lastActive ?: Instant.EPOCH })) else emptyList()
                }
                val groups = remember(sessions, roots, tree) {
                    if (tree) HistoryByDay.groupFamiliesByDay(roots) else HistoryByDay.groupSessionsByDay(sessions)
                }
                val running = model.events?.running?.collectAsState()?.value.orEmpty()
                PhoneSessionHistoryList(
                    families = remember(roots) { roots.associateBy { it.id } },
                    specByChat = remember(specList) { SpecFamily.byChat(specList) },
                    runningSessionIds = running,
                    onOpenStandIn = { root ->
                        val title = root.parentTitle?.trim().takeUnless { it.isNullOrEmpty() } ?: "Chat"
                        project?.let { model.openLinkedSession(it, root.id, root.children.firstOrNull()?.session?.providerId ?: "", title) }
                        onNavigate()
                    },
                    spawnedBadge = { session -> project?.let { SpawnedSessionBadge(model, session, it) } },
                    groups = groups,
                    unreadTypes = unread.mapValues { it.value.type },
                    tags = model.tags.tags,
                    hasMore = state.hasMore(model.hasMoreSessions),
                    loadingMore = state.loadingMore,
                    onOpenSession = { session ->
                        model.openTab(session)
                        onNavigate()
                    },
                    onTogglePin = { session ->
                        model.scope.launch { model.togglePin(session) }
                    },
                    onMarkUnread = { session ->
                        model.markUnread(session)
                    },
                    onRenameSession = { session, newTitle ->
                        model.scope.launch { model.rename(session, newTitle) }
                    },
                    onSetTag = { session, tag ->
                        model.scope.launch { model.setTag(tag, session) }
                    },
                    onDeleteSession = { session ->
                        model.confirmDeleteSession = session
                    },
                    onLoadMore = {
                        scope.launch { list.loadMore(model.sessions, model.hasMoreSessions) }
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    renderInlineRename = { session, done ->
                        InlineRename(model, session, RenameField.HistoryList, Modifier.weight(1f), done)
                    },
                )
            }
            else -> {
                // The web's desktop sidebar: families in the list's order, no day headers.
                val roots = remember(sessions, tree) { if (tree) SessionTree.build(sessions) else emptyList() }
                val specByChat = remember(specList) { SpecFamily.byChat(specList) }
                val running = model.events?.running?.collectAsState()?.value.orEmpty()
                // Families the user opened or closed; the rest follow the open chat.
                var expanded by remember(project) { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
                var showAll by remember(project) { mutableStateOf<Set<String>>(emptySet()) }
                val activeSessionId = tabSessionId(model.activeTab)
                val openSession = { session: SessionInfo ->
                    model.openTab(session)
                    onNavigate()
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().cssScrollbar(sessionList), sessionList) {
                    if (tree) {
                        items(roots, key = { "f:" + it.id }) { root ->
                            SidebarHistoryFamily(
                                model = model,
                                project = project,
                                lines = HistoryTreeLines.lines(root, expanded, showAll, activeSessionId, running, unread.keys, specByChat),
                                unread = unread,
                                running = running,
                                onToggle = { line -> expanded = expanded + (line.parentId to !line.open) },
                                onShowAll = { parentId -> showAll = showAll + parentId },
                                onOpen = openSession,
                                onOpenStandIn = {
                                    val title = root.parentTitle?.trim().takeUnless { it.isNullOrEmpty() } ?: "Chat"
                                    project?.let { model.openLinkedSession(it, root.id, root.children.firstOrNull()?.session?.providerId ?: "", title) }
                                    onNavigate()
                                },
                            )
                        }
                    } else {
                        items(sessions, key = { it.id }) { session ->
                            val entry = unread[session.id]
                            SidebarSessionRow(
                                model,
                                session,
                                entry?.type,
                                entry != null,
                                badge = project?.let { p -> { SpawnedSessionBadge(model, session, p) } },
                            ) { openSession(session) }
                        }
                    }
                    if (state.hasMore(model.hasMoreSessions)) {
                        item(key = "more") {
                            // `hover:text-text-secondary hover:bg-surface-elevated`.
                            val hover = rememberHover(enabled = !state.loadingMore)
                            val text = tokens.text2.mix(tokens.text3, hover.lit.toDouble()).color
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .ppmClickable(enabled = !state.loadingMore, pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, source = hover.source) {
                                        scope.launch { list.loadMore(model.sessions, model.hasMoreSessions) }
                                    }
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (state.loadingMore) PpmIcon(ProductIcon.Loader2, 12.dp, text, spinning = true)
                                BasicText(if (state.loadingMore) "Loading..." else "Load more", style = ppmText(11f, lineHeight = 16.5f).copy(color = text))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * "Syncing…" while the shared page is read in the background (kept up at least 300 ms, so a
 * quick read does not blink), and for 4 s after a failed one, that the list is the saved one.
 */
@Composable
private fun SyncIndicator(syncing: Boolean, error: String?) {
    val tokens = LocalPpmTokens.current
    var visible by remember { mutableStateOf(false) }
    var shownAt by remember { mutableStateOf(0L) }
    LaunchedEffect(syncing) {
        if (syncing) {
            shownAt = System.currentTimeMillis()
            visible = true
        } else if (visible) {
            delay(maxOf(0L, 300L - (System.currentTimeMillis() - shownAt)))
            visible = false
        }
    }
    var showError by remember { mutableStateOf(false) }
    LaunchedEffect(error) {
        if (error == null) return@LaunchedEffect
        showError = true
        delay(4_000)
        showError = false
    }
    if (!visible && !showError) return
    val style = ppmText(12f, lineHeight = 16f).copy(color = tokens.textDim.color)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (visible) {
            PpmIcon(ProductIcon.RefreshCw, 12.dp, tokens.textDim.color, spinning = true)
            BasicText("Syncing…", style = style)
        } else {
            BasicText("Offline — showing saved list", style = style)
        }
    }
}

/**
 * `flex size-6 items-center justify-center rounded`, a 14 px icon in the subtle colour; under a
 * mouse `hover:bg-surface-elevated` and the icon in [hoverTint], at once.
 */
@Composable
private fun SquareIconButton(icon: ProductIcon, hoverTint: Rgba, spinning: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover(enabled, instant = true)
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.5f)
            .size(24.dp)
            .clip(RoundedCornerShape(4.dp))
            .ppmClickable(enabled = enabled, pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, fade = false, source = hover.source, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(icon, 14.dp, hoverTint.mix(tokens.text3, hover.lit.toDouble()).color, spinning = spinning)
    }
}

/**
 * `rounded-full border px-2 py-0.5 text-[11px]`: All in the accent, a tag in its colour at `20`
 * hex alpha; one not picked lit under a mouse (`hover:bg-surface-elevated`).
 */
@Composable
private fun PillChip(label: String, selected: Boolean, color: Rgba?, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val text = when {
        selected && color == null -> tokens.accent
        selected -> color!!
        else -> tokens.text3
    }
    val border = when {
        selected && color == null -> tokens.accent.opacity(0.4)
        selected -> color!!
        else -> tokens.border
    }
    val fill = when {
        !selected -> Color.Transparent
        color == null -> tokens.accent.opacity(0.1).color
        else -> color.opacity(0x20 / 255.0).color
    }
    Row(
        Modifier
            .clip(CircleShape)
            .background(fill)
            .ppmClickable(
                pressedColor = tokens.panel2.color,
                hoverColor = if (selected) Color.Transparent else tokens.panel2.color,
                shape = CircleShape,
                onClick = onClick,
            )
            .cssBorder(border.color, CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (color != null) Box(Modifier.size(8.dp).clip(CircleShape).background(color.color))
        BasicText(label, style = ppmText(11f, lineHeight = 16.5f).copy(color = text.color), maxLines = 1)
    }
}

/**
 * `px-2 py-1.5 gap-2`: provider, tag dot, the title at 13 px over its age, then pin, rename and
 * delete. Unread: tinted by what happened last and in semibold. A mouse over it lights it in
 * `surface-elevated`, over the tint too, as `hover:` outranks the tint's class.
 */
@Composable
internal fun SidebarSessionRow(
    model: AppModel,
    session: SessionInfo,
    unreadType: String?,
    isUnread: Boolean,
    /** `px-2`, or `indent(depth)` under the top of its family. */
    start: Dp = 8.dp,
    /** The "Created by" mark, on a top-level row. */
    badge: (@Composable () -> Unit)? = null,
    /** In place of the time line under the title (a spec chat's progress). */
    meta: (@Composable () -> Unit)? = null,
    /** At the row's end (a spec chat's task-chats chevron). */
    trailing: (@Composable RowScope.() -> Unit)? = null,
    open: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var renaming by remember { mutableStateOf(false) }
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    val bot = session.title.startsWith("[PPM]")
    val title = if (bot) session.title.drop(7) else session.title
    val color = if (isUnread) tokens.text else tokens.text2
    Box(Modifier.fillMaxWidth().background(if (isUnread) tokens.notification(unreadType).opacity(0.1).color else Color.Transparent)) {
        Row(
            Modifier
                .fillMaxWidth()
                .pressable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, enabled = !renaming, onLongClick = { menuAt = it }, onClick = open)
                .padding(start = start, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProviderBadge(session.providerId)
            session.tag?.let { TagDot(it.color) }
            badge?.invoke()
            if (renaming) {
                InlineRename(model, session, RenameField.HistoryList, Modifier.weight(1f)) { renaming = false }
            } else {
                // The title is a button inside a line of the body's 16/24 text, so never under 24 px.
                Column(Modifier.weight(1f).heightIn(min = 24.dp), verticalArrangement = Arrangement.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (bot) PpmIcon(ProductIcon.Bot, 14.dp, tokens.text2.color)
                        BasicText(
                            PpmFormat.collapsedWhitespace(title).ifEmpty { "Untitled" },
                            style = ppmText(13f, if (isUnread) FontWeight.SemiBold else FontWeight.Medium, lineHeight = 19.5f).copy(color = color.color),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (meta != null) meta() else session.updatedAt?.let { RelativeTime(it, ppmText(11f, lineHeight = 16.5f).copy(color = tokens.text3.color)) }
                }
                if (session.pinned) {
                    HistoryIcon(ProductIcon.PinOff, tokens.accent, tokens.accent.opacity(0.7)) { model.scope.launch { model.togglePin(session) } }
                } else {
                    HistoryIcon(ProductIcon.Pin, tokens.text3, tokens.text2) { model.scope.launch { model.togglePin(session) } }
                }
                // Rename and delete share one span, so no gap between them.
                Row {
                    HistoryIcon(ProductIcon.Pencil, tokens.text3, tokens.text2) { renaming = true }
                    HistoryIcon(ProductIcon.Trash2, tokens.text3, tokens.error, tokens.error.opacity(0.2).color) { model.confirmDeleteSession = session }
                }
                trailing?.invoke(this)
            }
        }
        menuAt?.let { at ->
            SessionContextMenu(model, session, at, onDismiss = { menuAt = null }, onRename = { renaming = true }, onDelete = { model.confirmDeleteSession = session })
        }
    }
}

/** `p-0.5 rounded` with a 12 px icon, in [hoverTint] under a mouse, on [hoverFill] where the web lights one. */
@Composable
private fun HistoryIcon(icon: ProductIcon, tint: Rgba, hoverTint: Rgba, hoverFill: Color = Color.Transparent, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover()
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .ppmClickable(pressedColor = tokens.panel2.color, hoverColor = hoverFill, source = hover.source, onClick = onClick)
            .padding(2.dp),
    ) {
        PpmIcon(icon, 12.dp, hoverTint.mix(tint, hover.lit.toDouble()).color)
    }
}

/** Title and message matches (`SearchResultsList`), with the indexing the search set off. */
@Composable
private fun SearchResults(model: AppModel, page: com.hienlh.ppm.protocol.ChatSearchPage?, loading: Boolean, onNavigate: () -> Unit, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    val hits = page?.hits.orEmpty()
    val note = ppmText(11f, lineHeight = 16.5f).copy(color = tokens.text3.color)
    val hitList = rememberLazyListState()
    Column(modifier.fillMaxWidth()) {
        if (page?.indexing == true) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PpmIcon(ProductIcon.Loader2, 10.dp, tokens.text3.color, spinning = true)
                BasicText("Indexing ${page.indexed}/${page.total}…", style = ppmText(10f, lineHeight = 15f).copy(color = tokens.text3.color))
            }
        }
        when {
            loading && hits.isEmpty() -> Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                PpmIcon(ProductIcon.Loader2, 14.dp, tokens.text3.color, spinning = true)
            }
            hits.isEmpty() -> BasicText(
                if (page?.indexing == true) "No matches yet — indexing…" else "No matches",
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                style = note.copy(textAlign = TextAlign.Center),
            )
            else -> LazyColumn(Modifier.fillMaxWidth().cssScrollbar(hitList), hitList) {
                items(hits, key = { it.sessionId }) { hit ->
                    SearchHitRow(hit) {
                        model.open(hit)
                        onNavigate()
                    }
                }
            }
        }
    }
}

/** `px-2 py-1.5 gap-2`: the title at 13 px, and for a message match the words around it, the match marked. */
@Composable
private fun SearchHitRow(hit: ChatSearchHit, open: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier.fillMaxWidth().ppmClickable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, onClick = open).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ProviderBadge(hit.providerId)
        Column(Modifier.weight(1f)) {
            BasicText(
                hit.title?.ifEmpty { null } ?: "Untitled",
                style = ppmText(13f, FontWeight.Medium, lineHeight = 19.5f).copy(color = tokens.text.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (hit.matchedInContent && hit.snippet.isNotEmpty()) {
                val marked = SpanStyle(background = tokens.warning.opacity(0.3).color, color = tokens.text.color)
                BasicText(
                    buildAnnotatedString {
                        for (run in ChatSearchHit.runs(hit.snippet)) {
                            if (run.marked) withStyle(marked) { append(run.text) } else append(run.text)
                        }
                    },
                    style = ppmText(11f, lineHeight = 16.5f).copy(color = tokens.text3.color),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Day-group bucket classification for chat history (`designs/ppm-mobile-tablet-redesign/phone/1-chat/index.html`,
 * Section 7 G1, Requirement 4.3).
 */
enum class SessionDayBucketKind {
    PINNED,
    TODAY,
    YESTERDAY,
    DATED,
}

/**
 * A group of sessions in the phone Chat History list (`Pinned`, `Today`, `Yesterday`, or a date header).
 */
data class SessionDayGroup(
    val key: String,
    val label: String,
    val kind: SessionDayBucketKind,
    val date: LocalDate? = null,
    val sessions: List<SessionInfo>,
)

/**
 * Row action types available via left-swipe (`76 dp` buttons) and long-press sheet in phone Chat History
 * (`designs/ppm-mobile-tablet-redesign/phone/1-chat/index.html`, Section 7 G2).
 */
enum class SessionRowActionKind {
    PIN,
    UNPIN,
    RENAME,
    DELETE,
    MARK_UNREAD,
    SET_TAG,
}

/**
 * Descriptor for a single row action on a [SessionInfo] item.
 */
data class HistoryRowAction(
    val kind: SessionRowActionKind,
    val label: String,
    val destructive: Boolean = false,
)

/**
 * Live status state displayed on a session row (`Running · now`, `Needs you · 2 min ago`, or relative time).
 */
enum class SessionLiveState {
    IDLE,
    RUNNING,
    NEEDS_YOU,
    UNREAD,
}

/**
 * Pure day-grouping and row-action helpers for Chat History (Requirement 4.3, Phone 1 Section 7 G1–G3).
 */
object HistoryByDay {
    private val sameYearFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val otherYearFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

    /**
     * Groups [sessions] into:
     * 1. `Pinned` (at top, if any pinned session exists), sorted by `lastActive` descending.
     * 2. `Today`, `Yesterday`, and dated groups (`MMM d` or `MMM d, yyyy`) for unpinned sessions,
     *    evaluated in [zoneId] relative to [now].
     */
    fun groupSessionsByDay(
        sessions: List<SessionInfo>,
        now: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<SessionDayGroup> = groupByDay(sessions.map { it to it.lastActive }, now, zoneId)

    /**
     * Chat History's tree grouped as [groupSessionsByDay] groups sessions: one entry per family,
     * standing for its top chat ([SessionTreeRoot.session], or a stand-in named by its children's
     * `parentTitle` when the creator is not in the list) and placed by its most recently active
     * member, so a spec whose task chats still run stays near the top though its own chat went quiet.
     */
    fun groupFamiliesByDay(
        roots: List<SessionTreeRoot>,
        now: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<SessionDayGroup> = groupByDay(
        roots.map { root -> familyRepresentative(root) to root.members.mapNotNull { it.lastActive }.maxOrNull() },
        now,
        zoneId,
    )

    /** The row a family stands under: its top chat, or a stand-in for a creator not in the list. */
    fun familyRepresentative(root: SessionTreeRoot): SessionInfo = root.session ?: SessionInfo(
        id = root.id,
        providerId = root.children.firstOrNull()?.session?.providerId ?: "claude",
        title = root.parentTitle?.trim().takeUnless { it.isNullOrEmpty() } ?: "Another session",
    )

    /** [entries] (a row and when its family was last active) in Pinned, Today, Yesterday and dated groups. */
    private fun groupByDay(
        entries: List<Pair<SessionInfo, Instant?>>,
        now: Instant,
        zoneId: ZoneId,
    ): List<SessionDayGroup> {
        if (entries.isEmpty()) return emptyList()
        val today = now.atZone(zoneId).toLocalDate()
        val yesterday = today.minusDays(1)
        val activity = entries.associate { it.first.id to it.second }

        val comparator = compareByDescending<SessionInfo> { activity[it.id] ?: Instant.EPOCH }
            .thenBy { it.id }

        val sessions = entries.map { it.first }
        val pinned = sessions.filter { it.pinned }.sortedWith(comparator)
        val unpinned = sessions.filterNot { it.pinned }.sortedWith(comparator)

        val groups = mutableListOf<SessionDayGroup>()
        if (pinned.isNotEmpty()) {
            groups += SessionDayGroup(
                key = "pinned",
                label = "Pinned",
                kind = SessionDayBucketKind.PINNED,
                date = null,
                sessions = pinned,
            )
        }

        val byDate = LinkedHashMap<LocalDate?, MutableList<SessionInfo>>()
        for (session in unpinned) {
            val localDate = activity[session.id]?.atZone(zoneId)?.toLocalDate()
            byDate.getOrPut(localDate) { mutableListOf() }.add(session)
        }

        for ((date, daySessions) in byDate) {
            val group = when (date) {
                today -> SessionDayGroup(
                    key = "today",
                    label = "Today",
                    kind = SessionDayBucketKind.TODAY,
                    date = date,
                    sessions = daySessions,
                )
                yesterday -> SessionDayGroup(
                    key = "yesterday",
                    label = "Yesterday",
                    kind = SessionDayBucketKind.YESTERDAY,
                    date = date,
                    sessions = daySessions,
                )
                null -> SessionDayGroup(
                    key = "earlier",
                    label = "Earlier",
                    kind = SessionDayBucketKind.DATED,
                    date = null,
                    sessions = daySessions,
                )
                else -> SessionDayGroup(
                    key = date.toString(),
                    label = formatDayHeader(date, today),
                    kind = SessionDayBucketKind.DATED,
                    date = date,
                    sessions = daySessions,
                )
            }
            groups += group
        }

        return groups
    }

    /** Formats a historical [date] header relative to [today]. */
    fun formatDayHeader(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> if (date.year == today.year) {
            date.format(sameYearFormatter)
        } else {
            date.format(otherYearFormatter)
        }
    }

    /**
     * Returns the 3 left-swipe actions (`76 dp` each) for a session row (Phone 1 Section 7 G2):
     * `Pin` / `Unpin`, `Rename`, and `Delete`.
     */
    fun swipeActionsForSession(session: SessionInfo): List<HistoryRowAction> = listOf(
        if (session.pinned) {
            HistoryRowAction(SessionRowActionKind.UNPIN, "Unpin")
        } else {
            HistoryRowAction(SessionRowActionKind.PIN, "Pin")
        },
        HistoryRowAction(SessionRowActionKind.RENAME, "Rename"),
        HistoryRowAction(SessionRowActionKind.DELETE, "Delete", destructive = true),
    )

    /**
     * Returns the long-press action sheet rows for a session row (Phone 1 Section 7 G2):
     * `Pin` / `Unpin`, `Mark as unread` (when not already unread), `Rename`, `Set Tag`, and `Delete`.
     */
    fun longPressActionsForSession(
        session: SessionInfo,
        isUnread: Boolean = false,
        hasTags: Boolean = true,
    ): List<HistoryRowAction> = buildList {
        add(
            if (session.pinned) {
                HistoryRowAction(SessionRowActionKind.UNPIN, "Unpin")
            } else {
                HistoryRowAction(SessionRowActionKind.PIN, "Pin")
            },
        )
        if (!isUnread) {
            add(HistoryRowAction(SessionRowActionKind.MARK_UNREAD, "Mark as unread"))
        }
        add(HistoryRowAction(SessionRowActionKind.RENAME, "Rename"))
        if (hasTags) {
            add(HistoryRowAction(SessionRowActionKind.SET_TAG, "Set Tag"))
        }
        add(HistoryRowAction(SessionRowActionKind.DELETE, "Delete", destructive = true))
    }

    /** Formats the subtitle line of a 68 dp phone session row (Phone 1 Section 7 G1). */
    fun formatSessionSubtitle(
        liveState: SessionLiveState,
        relativeAge: String,
    ): String = when (liveState) {
        SessionLiveState.RUNNING -> "Running · now"
        SessionLiveState.NEEDS_YOU -> if (relativeAge.isNotEmpty()) "Needs you · $relativeAge" else "Needs you"
        SessionLiveState.UNREAD, SessionLiveState.IDLE -> relativeAge.ifEmpty { "Just now" }
    }
}

/** One line of the phone's Chat History tree: a chat, the toggle over a family, or "Show N more". */
sealed interface HistoryLine {
    val key: String

    /**
     * A chat [depth] levels under the top of its family. [standIn]: the creator is not in the list,
     * so the line stands for it. [spec]: the spec this chat drives, whose progress the line shows.
     * [task]: a task chat under its spec, with its task's state in [taskCell].
     */
    data class Chat(
        val session: SessionInfo,
        val depth: Int,
        val standIn: Boolean = false,
        val spec: SpecSummary? = null,
        val task: Boolean = false,
        val taskCell: SpecBarCell? = null,
    ) : HistoryLine {
        override val key: String get() = "s:" + session.id
    }

    /** "N sub-chats" (or a spec's task chats) under [parentId], with how many run and how many are unread. */
    data class Toggle(
        val parentId: String,
        val count: Int,
        val running: Int,
        val unread: Int,
        val depth: Int,
        val open: Boolean,
        val taskChats: Boolean = false,
    ) : HistoryLine {
        override val key: String get() = "t:$parentId"
    }

    /** The spec's task chats past the first few: "Show N more". */
    data class More(val parentId: String, val hidden: Int) : HistoryLine {
        override val key: String get() = "m:$parentId"
    }
}

/**
 * The lines one family of Chat History's tree draws (`renderRoot` in session-history-list.tsx):
 * its top chat, then its sub-chats behind a toggle, collapsed until asked for or while one of them
 * is the open chat. A spec's chat shows its progress, and its task chats come running first, then
 * failed, then the rest, the first [TASK_CHATS_SHOWN] of them until "Show N more".
 */
object HistoryTreeLines {
    /** Task chats listed under an open spec before "Show N more". */
    const val TASK_CHATS_SHOWN = 7

    fun lines(
        root: SessionTreeRoot,
        expanded: Map<String, Boolean> = emptyMap(),
        showAll: Set<String> = emptySet(),
        activeSessionId: String? = null,
        running: Set<String> = emptySet(),
        unread: Set<String> = emptySet(),
        specByChat: Map<String, SpecSummary> = emptyMap(),
    ): List<HistoryLine> {
        val out = mutableListOf<HistoryLine>()
        val spec = root.session?.let { specByChat[it.id] }?.takeIf { root.children.isNotEmpty() }
        out += HistoryLine.Chat(HistoryByDay.familyRepresentative(root), 0, standIn = root.session == null, spec = spec)

        fun isOpen(parentId: String, all: List<SessionInfo>) = expanded[parentId] ?: all.any { it.id == activeSessionId }

        fun children(parentId: String, kids: List<SessionTreeNode>, depth: Int) {
            if (kids.isEmpty()) return
            val all = SessionTree.descendantsOf(kids)
            val open = isOpen(parentId, all)
            out += HistoryLine.Toggle(parentId, all.size, all.count { it.id in running }, all.count { it.id in unread }, depth, open)
            if (!open) return
            for (n in kids) {
                out += HistoryLine.Chat(n.session, depth)
                children(n.session.id, n.children, depth + 1)
            }
        }

        if (spec == null) {
            children(root.id, root.children, 1)
            return out
        }
        val all = root.descendants
        val open = isOpen(root.id, all)
        out += HistoryLine.Toggle(root.id, all.size, all.count { it.id in running }, all.count { it.id in unread }, 1, open, taskChats = true)
        if (!open) return out
        val cells = SpecFamily.cellBySession(spec)
        val ordered = SpecFamily.orderTaskChats(root.children, cells)
        // The open chat is never hidden behind "Show more".
        val everything = root.id in showAll || ordered.indexOfFirst { it.session.id == activeSessionId } >= TASK_CHATS_SHOWN
        val shown = if (everything) ordered else ordered.take(TASK_CHATS_SHOWN)
        for (n in shown) {
            out += HistoryLine.Chat(n.session, 1, task = true, taskCell = cells[n.session.id])
            children(n.session.id, n.children, 2)
        }
        if (ordered.size > shown.size) out += HistoryLine.More(root.id, ordered.size - shown.size)
        return out
    }
}

/** Width of each swipe-revealed action button in phone Chat History (Phone 1 G2 = 76 dp). */
val PhoneHistorySwipeActionWidth = 76.dp

/** Height of each two-line session row in phone Chat History (Phone 1 G1 = 68 dp). */
val PhoneHistoryRowHeight = 68.dp

/**
 * Day-grouped phone Chat History list (`Pinned`, `Today`, `Yesterday`, dated groups) with
 * 68 dp rows, left-swipe row actions (`Pin`/`Unpin`, `Rename`, `Delete`), and long-press sheet
 * (`designs/ppm-mobile-tablet-redesign/phone/1-chat/index.html`, Section 7 G1–G3).
 */
@Composable
fun PhoneSessionHistoryList(
    groups: List<SessionDayGroup>,
    onOpenSession: (SessionInfo) -> Unit,
    onTogglePin: (SessionInfo) -> Unit,
    onDeleteSession: (SessionInfo) -> Unit,
    modifier: Modifier = Modifier,
    unreadTypes: Map<String, String?> = emptyMap(),
    runningSessionIds: Set<String> = emptySet(),
    needsYouSessionIds: Set<String> = emptySet(),
    activeSessionId: String? = null,
    tags: List<SessionTag> = emptyList(),
    hasMore: Boolean = false,
    loadingMore: Boolean = false,
    onMarkUnread: (SessionInfo) -> Unit = {},
    onRenameSession: (SessionInfo, String) -> Unit = { _, _ -> },
    onSetTag: (SessionInfo, SessionTag?) -> Unit = { _, _ -> },
    onLoadMore: () -> Unit = {},
    renderInlineRename: (@Composable (SessionInfo, () -> Unit) -> Unit)? = null,
    /** Tree mode: each group entry's family by its id (`SessionTree`); empty draws the flat list. */
    families: Map<String, SessionTreeRoot> = emptyMap(),
    /** The specs whose chats show their progress, by chat id (`SpecFamily.byChat`). */
    specByChat: Map<String, SpecSummary> = emptyMap(),
    /** A family whose creator is not in the list: open that creator. */
    onOpenStandIn: (SessionTreeRoot) -> Unit = {},
    /** The "Created by" mark of a chat an AI created, on a top-level row. */
    spawnedBadge: (@Composable (SessionInfo) -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    val listState = rememberLazyListState()
    var swipedSessionId by remember { mutableStateOf<String?>(null) }
    var renamingSessionId by remember { mutableStateOf<String?>(null) }
    var sheetSession by remember { mutableStateOf<SessionInfo?>(null) }
    // Families the user opened or closed; the rest follow the open chat.
    var familyOpen by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var showAll by remember { mutableStateOf<Set<String>>(emptySet()) }
    val unreadIds = unreadTypes.keys

    Box(modifier = modifier) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .cssScrollbar(listState),
            state = listState,
        ) {
            groups.forEach { group ->
                item(key = "header_${group.key}") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (group.kind == SessionDayBucketKind.PINNED) {
                            PpmIcon(ProductIcon.Pin, 11.dp, tokens.text3.color)
                        }
                        BasicText(
                            text = group.label.uppercase(Locale.US),
                            style = ppmText(11f, FontWeight.Bold, lineHeight = 14f)
                                .copy(color = tokens.text3.color, letterSpacing = 0.6.sp),
                        )
                    }
                }
                val lines = group.sessions.flatMap { top ->
                    val family = families[top.id]
                    if (family == null) {
                        listOf(HistoryLine.Chat(top, 0))
                    } else {
                        HistoryTreeLines.lines(family, familyOpen, showAll, activeSessionId, runningSessionIds, unreadIds, specByChat)
                    }
                }
                items(lines, key = { "${group.key}_${it.key}" }) { line ->
                    when (line) {
                        is HistoryLine.Toggle -> PhoneHistoryFamilyToggle(line) {
                            familyOpen = familyOpen + (line.parentId to !line.open)
                        }
                        is HistoryLine.More -> PhoneHistoryShowMore(line) { showAll = showAll + line.parentId }
                        is HistoryLine.Chat -> when {
                            line.standIn -> families[line.session.id]?.let { root ->
                                PhoneHistoryStandInRow(line.session) { onOpenStandIn(root) }
                            }
                            line.task -> PhoneHistoryTaskRow(
                                session = line.session,
                                cell = line.taskCell,
                                running = line.session.id in runningSessionIds,
                                unreadType = unreadTypes[line.session.id],
                                unread = line.session.id in unreadIds,
                                selected = line.session.id == activeSessionId,
                                onOpen = { onOpenSession(line.session) },
                                onLongPress = { sheetSession = line.session },
                            )
                            else -> {
                                val session = line.session
                                val unreadType = unreadTypes[session.id]
                                val isUnread = unreadTypes.containsKey(session.id)
                                val liveState = when {
                                    session.id in needsYouSessionIds ||
                                        unreadType == "approval_request" ||
                                        unreadType == "question" -> SessionLiveState.NEEDS_YOU
                                    session.id in runningSessionIds -> SessionLiveState.RUNNING
                                    isUnread -> SessionLiveState.UNREAD
                                    else -> SessionLiveState.IDLE
                                }
                                PhoneSessionHistoryRow(
                                    session = session,
                                    liveState = liveState,
                                    unreadType = unreadType,
                                    selected = session.id == activeSessionId,
                                    swipeExpanded = swipedSessionId == session.id,
                                    onSwipeExpandChange = { expanded ->
                                        swipedSessionId = if (expanded) session.id else if (swipedSessionId == session.id) null else swipedSessionId
                                    },
                                    renaming = renamingSessionId == session.id,
                                    onStartRename = {
                                        swipedSessionId = null
                                        renamingSessionId = session.id
                                    },
                                    onFinishRename = { renamingSessionId = null },
                                    onOpen = {
                                        swipedSessionId = null
                                        onOpenSession(session)
                                    },
                                    onLongPress = {
                                        swipedSessionId = null
                                        sheetSession = session
                                    },
                                    onTogglePin = {
                                        swipedSessionId = null
                                        onTogglePin(session)
                                    },
                                    onDelete = {
                                        swipedSessionId = null
                                        onDeleteSession(session)
                                    },
                                    renderInlineRename = renderInlineRename,
                                    indent = (line.depth * 16).dp,
                                    spec = line.spec,
                                    badge = if (line.depth == 0 && spawnedBadge != null) {
                                        { spawnedBadge(session) }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (hasMore) {
                item(key = "load_more") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .ppmClickable(
                                enabled = !loadingMore,
                                pressedColor = tokens.panel2.color,
                                hoverColor = tokens.panel2.color,
                                onClickLabel = "Load more",
                                onClick = onLoadMore,
                            ),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (loadingMore) {
                            PpmIcon(ProductIcon.Loader2, 14.dp, tokens.text2.color, spinning = true)
                        }
                        BasicText(
                            text = if (loadingMore) "Loading..." else "Load more",
                            style = ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(color = tokens.text2.color),
                        )
                    }
                }
            }
        }

        val activeSheetSession = sheetSession
        if (activeSheetSession != null) {
            PhoneSessionActionSheet(
                session = activeSheetSession,
                isUnread = unreadTypes.containsKey(activeSheetSession.id),
                tags = tags,
                onDismiss = { sheetSession = null },
                onTogglePin = {
                    val target = activeSheetSession
                    sheetSession = null
                    onTogglePin(target)
                },
                onMarkUnread = {
                    val target = activeSheetSession
                    sheetSession = null
                    onMarkUnread(target)
                },
                onRename = {
                    val target = activeSheetSession
                    sheetSession = null
                    renamingSessionId = target.id
                },
                onSetTag = { tag ->
                    val target = activeSheetSession
                    sheetSession = null
                    onSetTag(target, tag)
                },
                onDelete = {
                    val target = activeSheetSession
                    sheetSession = null
                    onDeleteSession(target)
                },
            )
        }
    }
}

/**
 * 68 dp two-line phone session row with left-swipe 76 dp action tray (`Pin`/`Unpin`, `Rename`, `Delete`)
 * and long-press sheet trigger (Phone 1 Section 7 G1 & G2).
 */
@Composable
fun PhoneSessionHistoryRow(
    session: SessionInfo,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    onStartRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    liveState: SessionLiveState = SessionLiveState.IDLE,
    unreadType: String? = null,
    selected: Boolean = false,
    swipeExpanded: Boolean = false,
    onSwipeExpandChange: (Boolean) -> Unit = {},
    renaming: Boolean = false,
    onFinishRename: () -> Unit = {},
    onLongPress: () -> Unit = {},
    renderInlineRename: (@Composable (SessionInfo, () -> Unit) -> Unit)? = null,
    /** How far under the top of its family the row sits. */
    indent: Dp = 0.dp,
    /** The spec this chat drives: its progress takes the time line's place. */
    spec: SpecSummary? = null,
    /** The "Created by" mark, after the provider. */
    badge: (@Composable () -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    val density = LocalDensity.current
    val actions = remember(session.pinned) { HistoryByDay.swipeActionsForSession(session) }
    val totalRevealWidth = PhoneHistorySwipeActionWidth * actions.size
    val maxRevealPx = with(density) { totalRevealWidth.toPx() }
    var dragOffsetPx by remember(session.id) {
        mutableFloatStateOf(if (swipeExpanded) -maxRevealPx else 0f)
    }
    LaunchedEffect(swipeExpanded, maxRevealPx) {
        dragOffsetPx = if (swipeExpanded) -maxRevealPx else 0f
    }

    val bot = session.title.startsWith("[PPM]")
    val cleanTitle = PpmFormat.collapsedWhitespace(if (bot) session.title.drop(7) else session.title).ifEmpty { "Untitled" }
    val isUnread = liveState != SessionLiveState.IDLE
    val rowBg = when {
        selected -> tokens.accentWash.color
        liveState == SessionLiveState.NEEDS_YOU -> tokens.warning.opacity(0.08).color
        liveState == SessionLiveState.UNREAD -> tokens.notification(unreadType).opacity(0.10).color
        else -> tokens.bgSolid.color
    }
    val statusDotColor = when (liveState) {
        SessionLiveState.RUNNING -> tokens.success.color
        SessionLiveState.NEEDS_YOU -> tokens.warning.color
        SessionLiveState.UNREAD -> tokens.notification(unreadType).color
        SessionLiveState.IDLE -> if (selected) tokens.accent.color else null
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(PhoneHistoryRowHeight)
            .clip(RoundedCornerShape(0.dp)),
    ) {
        // Swipe action tray behind the foreground row (revealed when swiped left)
        if (swipeExpanded || dragOffsetPx < -1f) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions.forEach { action ->
                    val actionBg = when (action.kind) {
                        SessionRowActionKind.PIN, SessionRowActionKind.UNPIN -> tokens.accent.color
                        SessionRowActionKind.RENAME -> tokens.panel2.color
                        SessionRowActionKind.DELETE -> tokens.error.color
                        else -> tokens.panel2.color
                    }
                    val actionFg = when (action.kind) {
                        SessionRowActionKind.PIN, SessionRowActionKind.UNPIN -> tokens.accentFg.color
                        SessionRowActionKind.DELETE -> Color.White
                        else -> tokens.text.color
                    }
                    val actionIcon = when (action.kind) {
                        SessionRowActionKind.PIN -> ProductIcon.Pin
                        SessionRowActionKind.UNPIN -> ProductIcon.PinOff
                        SessionRowActionKind.RENAME -> ProductIcon.Pencil
                        SessionRowActionKind.DELETE -> ProductIcon.Trash2
                        else -> ProductIcon.Circle
                    }
                    Column(
                        modifier = Modifier
                            .width(PhoneHistorySwipeActionWidth)
                            .fillMaxHeight()
                            .background(actionBg)
                            .ppmClickable(
                                onClickLabel = action.label,
                                onClick = {
                                    when (action.kind) {
                                        SessionRowActionKind.PIN, SessionRowActionKind.UNPIN -> onTogglePin()
                                        SessionRowActionKind.RENAME -> onStartRename()
                                        SessionRowActionKind.DELETE -> onDelete()
                                        else -> {}
                                    }
                                },
                            )
                            .semantics { contentDescription = action.label },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    ) {
                        PpmIcon(actionIcon, 16.dp, actionFg)
                        BasicText(
                            text = action.label,
                            style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f).copy(color = actionFg),
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        // Foreground row content
        Row(
            modifier = Modifier
                .offset { IntOffset(dragOffsetPx.roundToInt(), 0) }
                .fillMaxSize()
                .background(rowBg)
                .cssBorder(tokens.borderSoft.color, sides = CssSides.Bottom)
                .pointerInput(session.id, maxRevealPx) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val shouldExpand = dragOffsetPx < -maxRevealPx * 0.35f
                            dragOffsetPx = if (shouldExpand) -maxRevealPx else 0f
                            onSwipeExpandChange(shouldExpand)
                        },
                        onDragCancel = {
                            dragOffsetPx = if (swipeExpanded) -maxRevealPx else 0f
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            val next = (dragOffsetPx + dragAmount).coerceIn(-maxRevealPx, 0f)
                            dragOffsetPx = next
                        },
                    )
                }
                .pressable(
                    pressedColor = tokens.surfaceHover.color,
                    hoverColor = tokens.surfaceHover.color,
                    enabled = !renaming,
                    onLongClick = { onLongPress() },
                    onClick = {
                        if (swipeExpanded) {
                            onSwipeExpandChange(false)
                        } else {
                            onOpen()
                        }
                    },
                )
                .padding(start = 16.dp + indent, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProviderBadge(session.providerId)
            badge?.invoke()

            if (renaming && renderInlineRename != null) {
                renderInlineRename(session, onFinishRename)
            } else {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (bot) {
                            PpmIcon(ProductIcon.Bot, 14.dp, tokens.text2.color)
                        }
                        BasicText(
                            text = cleanTitle,
                            modifier = Modifier.weight(1f, fill = false),
                            style = ppmText(
                                size = 14f,
                                weight = if (isUnread || selected) FontWeight.SemiBold else FontWeight.Medium,
                                lineHeight = 19f,
                            ).copy(color = tokens.text.color),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        session.tag?.let { tag ->
                            val tagColor = Rgba.hex(tag.color) ?: tokens.accent
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(tagColor.opacity(0.14).color)
                                    .cssBorder(tagColor.opacity(0.35).color, RoundedCornerShape(999.dp))
                                    .padding(horizontal = 7.dp, vertical = 1.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                BasicText(
                                    text = tag.name,
                                    style = ppmText(10.5f, FontWeight.SemiBold, lineHeight = 14f).copy(color = tagColor.color),
                                    maxLines = 1,
                                )
                            }
                        }
                    }

                    if (spec != null) {
                        PhoneSpecProgress(spec)
                    } else Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val subColor = when (liveState) {
                            SessionLiveState.RUNNING -> tokens.success.color
                            SessionLiveState.NEEDS_YOU -> tokens.warning.color
                            else -> tokens.text3.color
                        }
                        val subWeight = when (liveState) {
                            SessionLiveState.RUNNING, SessionLiveState.NEEDS_YOU -> FontWeight.Medium
                            else -> FontWeight.Normal
                        }
                        when (liveState) {
                            SessionLiveState.RUNNING -> BasicText(
                                text = "Running · now",
                                style = ppmText(12f, subWeight, lineHeight = 16f).copy(color = subColor),
                                maxLines = 1,
                            )
                            SessionLiveState.NEEDS_YOU -> {
                                BasicText(
                                    text = "Needs you",
                                    style = ppmText(12f, subWeight, lineHeight = 16f).copy(color = subColor),
                                    maxLines = 1,
                                )
                                session.lastActive?.let { instant ->
                                    BasicText(
                                        text = "·",
                                        style = ppmText(12f, subWeight, lineHeight = 16f).copy(color = subColor),
                                    )
                                    RelativeTime(instant, ppmText(12f, subWeight, lineHeight = 16f).copy(color = subColor))
                                }
                            }
                            else -> {
                                val instant = session.lastActive
                                if (instant != null) {
                                    RelativeTime(instant, ppmText(12f, subWeight, lineHeight = 16f).copy(color = subColor))
                                } else {
                                    BasicText(
                                        text = "Just now",
                                        style = ppmText(12f, subWeight, lineHeight = 16f).copy(color = subColor),
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }

                if (statusDotColor != null) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(statusDotColor),
                    )
                }
            }
        }
    }
}

/**
 * Long-press bottom sheet for a session row in phone Chat History (Phone 1 Section 7 G2):
 * `Pin` / `Unpin`, `Mark as unread`, `Rename`, `Set Tag`, and a divider before red `Delete`.
 */
@Composable
fun PhoneSessionActionSheet(
    session: SessionInfo,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onMarkUnread: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    isUnread: Boolean = false,
    tags: List<SessionTag> = emptyList(),
    onSetTag: (SessionTag?) -> Unit = {},
) {
    val tokens = LocalPpmTokens.current
    var pickingTag by remember { mutableStateOf(false) }
    val cleanTitle = PpmFormat.collapsedWhitespace(
        if (session.title.startsWith("[PPM]")) session.title.drop(7) else session.title,
    ).ifEmpty { "Untitled" }

    Sheet(
        title = if (pickingTag) "Set Tag" else cleanTitle,
        subtitle = if (pickingTag) cleanTitle else null,
        onDismiss = onDismiss,
        onBack = if (pickingTag) ({ pickingTag = false }) else null,
        forceBottomSheet = true,
        modifier = modifier,
    ) {
        if (pickingTag) {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                tags.forEach { tag ->
                    val tagColor = Rgba.hex(tag.color) ?: tokens.accent
                    val isSelected = session.tag?.id == tag.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .ppmClickable(
                                hoverColor = tokens.surfaceHover.color,
                                onClickLabel = tag.name,
                                onClick = { onSetTag(tag) },
                            )
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(tagColor.color),
                        )
                        BasicText(
                            text = tag.name,
                            modifier = Modifier.weight(1f),
                            style = ppmText(14f, FontWeight.Medium, lineHeight = 19f).copy(color = tokens.text.color),
                        )
                        if (isSelected) {
                            PpmIcon(ProductIcon.Check, 18.dp, tokens.accent.color)
                        }
                    }
                }
                if (session.tag != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .height(1.dp)
                            .background(tokens.borderSoft.color),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .ppmClickable(
                                hoverColor = tokens.surfaceHover.color,
                                onClickLabel = "Remove tag",
                                onClick = { onSetTag(null) },
                            )
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PpmIcon(ProductIcon.X, 18.dp, tokens.text2.color)
                        BasicText(
                            text = "Remove tag",
                            style = ppmText(14f, FontWeight.Medium, lineHeight = 19f).copy(color = tokens.text2.color),
                        )
                    }
                }
            }
        } else {
            val actions = remember(session.pinned, isUnread, tags.isNotEmpty()) {
                HistoryByDay.longPressActionsForSession(
                    session = session,
                    isUnread = isUnread,
                    hasTags = true,
                )
            }
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                actions.forEach { action ->
                    if (action.destructive) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .height(1.dp)
                                .background(tokens.borderSoft.color),
                        )
                    }
                    val rowColor = if (action.destructive) tokens.error.color else tokens.text.color
                    val iconTint = if (action.destructive) tokens.error.color else tokens.text2.color
                    val rowIcon = when (action.kind) {
                        SessionRowActionKind.PIN -> ProductIcon.Pin
                        SessionRowActionKind.UNPIN -> ProductIcon.PinOff
                        SessionRowActionKind.MARK_UNREAD -> ProductIcon.Circle
                        SessionRowActionKind.RENAME -> ProductIcon.Pencil
                        SessionRowActionKind.SET_TAG -> ProductIcon.Tag
                        SessionRowActionKind.DELETE -> ProductIcon.Trash2
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .ppmClickable(
                                hoverColor = tokens.surfaceHover.color,
                                onClickLabel = action.label,
                                onClick = {
                                    when (action.kind) {
                                        SessionRowActionKind.PIN, SessionRowActionKind.UNPIN -> onTogglePin()
                                        SessionRowActionKind.MARK_UNREAD -> onMarkUnread()
                                        SessionRowActionKind.RENAME -> onRename()
                                        SessionRowActionKind.SET_TAG -> {
                                            if (tags.isNotEmpty()) {
                                                pickingTag = true
                                            } else {
                                                onSetTag(null)
                                            }
                                        }
                                        SessionRowActionKind.DELETE -> onDelete()
                                    }
                                },
                            )
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        PpmIcon(rowIcon, 18.dp, iconTint)
                        BasicText(
                            text = action.label,
                            modifier = Modifier.weight(1f),
                            style = ppmText(14f, FontWeight.Medium, lineHeight = 19f).copy(color = rowColor),
                        )
                        if (action.kind == SessionRowActionKind.SET_TAG) {
                            session.tag?.let { tag ->
                                BasicText(
                                    text = tag.name,
                                    style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text3.color),
                                )
                            }
                            PpmIcon(ProductIcon.ChevronRight, 16.dp, tokens.text3.color)
                        }
                    }
                }
            }
        }
    }
}


/** A spec chat's progress in place of its time line: a cell per required task, then the counts (`renderSpecFamily`). */
@Composable
internal fun PhoneSpecProgress(spec: SpecSummary) {
    val tokens = LocalPpmTokens.current
    val p = SpecFamily.progress(spec)
    Column(Modifier.fillMaxWidth().testTag("history-spec-progress:" + spec.slug), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(999.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (cell in SpecFamily.bar(spec)) {
                val color = when (cell) {
                    SpecBarCell.DONE -> tokens.success.color
                    SpecBarCell.RUNNING -> tokens.info.color
                    SpecBarCell.FAILED -> tokens.error.color
                    SpecBarCell.TODO -> tokens.border.color
                }
                Box(Modifier.weight(1f).fillMaxHeight().background(color))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            val style = ppmText(12f, lineHeight = 16f)
            BasicText("" + p.done + "/" + p.total + " done", style = style.copy(color = tokens.text2.color), maxLines = 1)
            if (p.running > 0) BasicText("· " + p.running + " running", style = style.copy(color = tokens.info.color), maxLines = 1)
            if (p.failed > 0) BasicText("· " + p.failed + " failed", style = style.copy(color = tokens.error.color), maxLines = 1)
        }
    }
}

/** "N sub-chats · k running · u unread" over a family, opening or closing it. */
@Composable
internal fun PhoneHistoryFamilyToggle(line: HistoryLine.Toggle, onToggle: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val noun = if (line.taskChats) "task chat" else "sub-chat"
    val label = "" + line.count + " " + noun + (if (line.count == 1) "" else "s")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .ppmClickable(onClickLabel = if (line.open) "Hide $label" else "Show $label", onClick = onToggle)
            .padding(start = 16.dp + (line.depth * 16).dp, end = 16.dp)
            .semantics { stateDescription = if (line.open) "Expanded" else "Collapsed" }
            .testTag("history-family-toggle:" + line.parentId),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PpmIcon(if (line.open) ProductIcon.ChevronDown else ProductIcon.ChevronRight, 14.dp, tokens.text3.color)
        val style = ppmText(12f, lineHeight = 16f)
        BasicText(label, style = style.copy(color = tokens.text3.color))
        if (line.running > 0) BasicText("· " + line.running + " running", style = style.copy(color = tokens.info.color))
        if (line.unread > 0) BasicText("· " + line.unread + " unread", style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color))
    }
}

/** The spec's task chats past the first few. */
@Composable
internal fun PhoneHistoryShowMore(line: HistoryLine.More, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    BasicText(
        "Show " + line.hidden + " more",
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .ppmClickable(onClickLabel = "Show " + line.hidden + " more", onClick = onClick)
            .padding(start = 48.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)
            .testTag("history-family-more:" + line.parentId),
        style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
    )
}

/** A family whose creator is not in the list (a later page, another tag): the row stands for that creator. */
@Composable
internal fun PhoneHistoryStandInRow(session: SessionInfo, onOpen: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .ppmClickable(onClickLabel = "Open " + session.title, onClick = onOpen)
            .cssBorder(tokens.borderSoft.color, sides = CssSides.Bottom)
            .padding(horizontal = 16.dp)
            .testTag("history-stand-in:" + session.id),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PpmIcon(ProductIcon.GitBranch, 16.dp, tokens.info.color)
        BasicText(
            session.title,
            Modifier.weight(1f),
            style = ppmText(14f, FontWeight.Medium, lineHeight = 19f).copy(color = tokens.text2.color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A task chat under its spec: one line, its task's state in front (`renderTaskRow`). */
@Composable
internal fun PhoneHistoryTaskRow(
    session: SessionInfo,
    cell: SpecBarCell?,
    running: Boolean,
    unreadType: String?,
    unread: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val bg = when {
        selected -> tokens.accentWash.color
        unread -> tokens.notification(unreadType).opacity(0.10).color
        else -> Color.Transparent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .background(bg)
            .pressable(pressedColor = tokens.surfaceHover.color, hoverColor = tokens.surfaceHover.color, onLongClick = { onLongPress() }, onClick = onOpen)
            .padding(start = 32.dp, end = 16.dp)
            .testTag("history-task-chat:" + session.id),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            running || cell == SpecBarCell.RUNNING -> PpmIcon(ProductIcon.Loader2, 14.dp, tokens.info.color, spinning = true)
            cell == SpecBarCell.FAILED -> PpmIcon(ProductIcon.CircleX, 14.dp, tokens.error.color)
            cell == SpecBarCell.DONE -> PpmIcon(ProductIcon.Check, 14.dp, tokens.success.color)
            else -> Box(Modifier.size(14.dp))
        }
        val color = when {
            unread || selected -> tokens.text.color
            cell == SpecBarCell.DONE -> tokens.text3.color
            else -> tokens.text2.color
        }
        BasicText(
            PpmFormat.collapsedWhitespace(session.title).ifEmpty { "Untitled" },
            Modifier.weight(1f),
            style = ppmText(13f, if (unread) FontWeight.SemiBold else FontWeight.Normal, lineHeight = 18f).copy(color = color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        session.lastActive?.let { RelativeTime(it, ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color)) }
    }
}

/** History's list or tree, and the switch between them. */
@Stable
internal class HistoryViewMode(private val model: AppModel, private val prefs: PrefsStore) {
    /** `tree` (the web's default) or `flat`. */
    val mode: String get() = model.specs.historyViewMode ?: prefs.get(HISTORY_VIEW_MODE_KEY)?.takeIf { it == "flat" || it == "tree" } ?: "tree"

    /** Switches, keeps it on this device for the next start, and saves it for every device (`chatHistoryViewMode`). */
    fun toggle() {
        val next = if (mode == "tree") "flat" else "tree"
        model.specs.historyViewMode = next
        prefs.put(HISTORY_VIEW_MODE_KEY, next)
        val api = model.api ?: return
        model.scope.launch {
            try {
                api.putChatHistoryViewMode(next)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }
}

private const val HISTORY_VIEW_MODE_KEY = "chatHistoryViewMode"

/** The choice as this device last had it, then as the server has it (another device may have changed it). */
@Composable
internal fun rememberHistoryViewMode(model: AppModel): HistoryViewMode {
    val context = LocalContext.current
    val view = remember(model, context) { HistoryViewMode(model, PrefsStore(context)) }
    LaunchedEffect(model.api) {
        val api = model.api ?: return@LaunchedEffect
        val saved = try {
            api.chatHistoryViewMode()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return@LaunchedEffect
        model.specs.historyViewMode = saved
        PrefsStore(context).put(HISTORY_VIEW_MODE_KEY, saved)
    }
    return view
}
