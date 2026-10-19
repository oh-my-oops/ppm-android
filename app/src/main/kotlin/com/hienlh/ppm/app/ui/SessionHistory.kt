package com.hienlh.ppm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.chat.RelativeTime
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFormat
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.ChatSearchHit
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.state.ChatSearch
import com.hienlh.ppm.state.HistoryList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        }
        // Search + bulk delete + refresh: `flex items-center gap-1 px-2 py-2`.
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val shape = RoundedCornerShape(8.dp)
            var focused by remember { mutableStateOf(false) }
            // `py-1.5 text-xs` outside the phone layout, its 1 px border counted as CSS counts it.
            val vertical = if (LocalPpmMobile.current) 6.dp else 7.dp
            Row(
                Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(tokens.panel2.color)
                    .border(1.dp, if (focused) tokens.accent.opacity(0.5).color else tokens.border.color, shape)
                    .padding(start = 8.dp, end = 8.dp, top = vertical, bottom = vertical),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PpmIcon(ProductIcon.Search, 14.dp, tokens.text3.color)
                PpmTextField(query, "Filter sessions…", Modifier.weight(1f), desktopStyle = ppmText(12f, lineHeight = 16f), onFocusChanged = { focused = it })
            }
            SquareIconButton(ProductIcon.CalendarX2, enabled = !model.deletingOld) { model.askDeleteOld = true }
            var refreshing by remember { mutableStateOf(false) }
            SquareIconButton(ProductIcon.RefreshCw, spinning = refreshing || state.loading, enabled = !refreshing) {
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
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(sessions, key = { it.id }) { session ->
                    val entry = unread[session.id]
                    SidebarSessionRow(model, session, entry?.type, entry != null) {
                        model.openTab(session)
                        onNavigate()
                    }
                }
                if (state.hasMore(model.hasMoreSessions)) {
                    item(key = "more") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .ppmClickable(enabled = !state.loadingMore, pressedColor = tokens.panel2.color) {
                                    scope.launch { list.loadMore(model.sessions, model.hasMoreSessions) }
                                }
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (state.loadingMore) PpmIcon(ProductIcon.Loader2, 12.dp, tokens.text3.color, spinning = true)
                            BasicText(if (state.loadingMore) "Loading..." else "Load more", style = ppmText(11f, lineHeight = 16.5f).copy(color = tokens.text3.color))
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

/** `flex size-6 items-center justify-center rounded`, a 14 px icon in the subtle colour. */
@Composable
private fun SquareIconButton(icon: ProductIcon, spinning: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.5f)
            .size(24.dp)
            .clip(RoundedCornerShape(4.dp))
            .ppmClickable(enabled = enabled, pressedColor = tokens.panel2.color, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(icon, 14.dp, tokens.text3.color, spinning = spinning)
    }
}

/** `rounded-full border px-2 py-0.5 text-[11px]`: All in the accent, a tag in its colour at `20` hex alpha. */
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
            .border(1.dp, border.color, CircleShape)
            .ppmClickable(pressedColor = tokens.panel2.color, shape = CircleShape, onClick = onClick)
            // The padding plus the 1 px border, which CSS counts into the box and `border()` does not.
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (color != null) Box(Modifier.size(8.dp).clip(CircleShape).background(color.color))
        BasicText(label, style = ppmText(11f, lineHeight = 16.5f).copy(color = text.color), maxLines = 1)
    }
}

/**
 * `px-2 py-1.5 gap-2`: provider, tag dot, the title at 13 px over its age, then pin, rename and
 * delete. Unread: tinted by what happened last and in semibold.
 */
@Composable
private fun SidebarSessionRow(model: AppModel, session: SessionInfo, unreadType: String?, isUnread: Boolean, open: () -> Unit) {
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
                .pressable(pressedColor = tokens.panel2.color, enabled = !renaming, onLongClick = { menuAt = it }, onClick = open)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProviderBadge(session.providerId)
            session.tag?.let { TagDot(it.color) }
            if (renaming) {
                InlineRename(model, session, Modifier.weight(1f)) { renaming = false }
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
                    session.updatedAt?.let { RelativeTime(it, ppmText(11f, lineHeight = 16.5f).copy(color = tokens.text3.color)) }
                }
                HistoryIcon(if (session.pinned) ProductIcon.PinOff else ProductIcon.Pin, if (session.pinned) tokens.accent else tokens.text3) {
                    model.scope.launch { model.togglePin(session) }
                }
                // Rename and delete share one span, so no gap between them.
                Row {
                    HistoryIcon(ProductIcon.Pencil, tokens.text3) { renaming = true }
                    HistoryIcon(ProductIcon.Trash2, tokens.text3) { model.confirmDeleteSession = session }
                }
            }
        }
        menuAt?.let { at ->
            SessionContextMenu(model, session, at, onDismiss = { menuAt = null }, onRename = { renaming = true }, onDelete = { model.confirmDeleteSession = session })
        }
    }
}

@Composable
private fun HistoryIcon(icon: ProductIcon, tint: Rgba, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Box(Modifier.clip(RoundedCornerShape(4.dp)).ppmClickable(pressedColor = tokens.panel2.color, onClick = onClick).padding(2.dp)) {
        PpmIcon(icon, 12.dp, tint.color)
    }
}

/** Title and message matches (`SearchResultsList`), with the indexing the search set off. */
@Composable
private fun SearchResults(model: AppModel, page: com.hienlh.ppm.protocol.ChatSearchPage?, loading: Boolean, onNavigate: () -> Unit, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    val hits = page?.hits.orEmpty()
    val note = ppmText(11f, lineHeight = 16.5f).copy(color = tokens.text3.color)
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
            else -> LazyColumn(Modifier.fillMaxWidth()) {
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
        Modifier.fillMaxWidth().ppmClickable(pressedColor = tokens.panel2.color, onClick = open).padding(horizontal = 8.dp, vertical = 6.dp),
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
