package com.hienlh.ppm.app.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.selectAll
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ChatTab
import com.hienlh.ppm.chat.CONTINUE_AFTER_STOP
import com.hienlh.ppm.chat.ChatTranscriptView
import com.hienlh.ppm.chat.RelativeTime
import com.hienlh.ppm.chat.TurnStopBar
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFormat
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.state.ChatController
import com.hienlh.ppm.state.ChatState
import com.hienlh.ppm.state.HistoryList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One chat tab (`chat-tab.tsx`): the conversation, what the server said about the last message,
 * why the last turn stopped, and the bottom toolbar — History and the connection — over the
 * message box.
 */
@Composable
fun ChatPane(model: AppModel, tab: ChatTab, modifier: Modifier = Modifier) {
    val chat = tab.controller ?: return
    val state by chat.state.collectAsState()
    val tokens = LocalPpmTokens.current
    var historyOpen by remember(tab.tabId) { mutableStateOf(false) }
    // The title the server gives a new chat goes into every list and onto its tab, as the web's tab carries it.
    LaunchedEffect(state.title) {
        state.title?.takeIf { it.isNotEmpty() }?.let {
            model.renameSession(tab.session.id, it)
            model.retitleTab(tab.tabId, it)
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        // The chat's own `@container`: under 420 px the chips drop their words.
        val narrow = maxWidth <= 420.dp
        Column(Modifier.fillMaxSize()) {
            ChatTranscriptView(
                state,
                onRespond = { request, approved, data -> chat.respond(request, approved, data) },
                onLoadOlder = { chat.loadOlder() },
                onReload = { chat.reload() },
                modifier = Modifier.weight(1f),
            )
            state.notice?.let { NoticeBar(it) { chat.dismissNotice() } }
            val stop = state.turnStop
            if (stop != null && !state.isRunning) TurnStopBar(stop, onContinue = { chat.send(CONTINUE_AFTER_STOP) })
            // `border-t border-border bg-panel`, around the toolbar and the message box.
            Column(Modifier.fillMaxWidth().background(tokens.panel.color).line(top = true, tokens.border.color)) {
                Column(Modifier.fillMaxWidth().line(top = false, tokens.border.opacity(0.5).color)) {
                    ChatToolbar(model, tab, chat, state, historyOpen, narrow) { historyOpen = !historyOpen }
                    val list = tab.historyList
                    if (historyOpen && list != null) ChatHistoryDropdown(model, tab, list) { historyOpen = false }
                }
                MobileComposer(model, tab, chat, state, narrow)
            }
        }
    }
}

/** A CSS border on one edge: drawn inside the box, the content moved off it by the same pixel. */
fun Modifier.line(top: Boolean, color: Color): Modifier = drawBehind {
    val y = if (top) 0f else size.height - 1.dp.toPx()
    drawRect(color, Offset(0f, y), Size(size.width, 1.dp.toPx()))
}.padding(top = if (top) 1.dp else 0.dp, bottom = if (top) 0.dp else 1.dp)

/** Something the server said about the last message, e.g. that it was refused. */
@Composable
private fun NoticeBar(text: String, dismiss: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(tokens.warning.opacity(0.1).color)
            .line(top = true, tokens.warning.opacity(0.2).color)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PpmIcon(ProductIcon.AlertCircle, 14.dp, tokens.warning.color)
        BasicText(text, Modifier.weight(1f), style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text.color))
        Box(
            Modifier.clip(RoundedCornerShape(4.dp)).ppmClickable(pressedColor = tokens.panel2.color, onClick = dismiss).padding(2.dp),
        ) {
            PpmIcon(ProductIcon.X, 12.dp, tokens.text3.color)
        }
    }
}

/**
 * The row over the message box (`chat-history-bar.tsx`): History on the left; on the right the
 * reload button with the connection dot, then the overflow menu. AI settings and usage are left
 * out until this app has them.
 */
@Composable
private fun ChatToolbar(model: AppModel, tab: ChatTab, chat: ChatController, state: ChatState, historyOpen: Boolean, narrow: Boolean, toggleHistory: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            ToolbarChip(ProductIcon.History, if (narrow) null else "History", active = historyOpen, onClick = toggleHistory)
        }
        Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ReloadButton(state.canSend) { chat.reload() }
            OverflowButton(model, tab)
        }
    }
}

/** `px-1.5 py-0.5 rounded text-[11px]`, accent-tinted while its panel is open. */
@Composable
private fun ToolbarChip(icon: ProductIcon, title: String?, active: Boolean, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(4.dp)
    val color = if (active) tokens.accent else tokens.text2
    Row(
        Modifier
            .clip(shape)
            .background(if (active) tokens.accent.opacity(0.1).color else Color.Transparent)
            .ppmClickable(pressedColor = tokens.panel2.color, shape = shape, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(12.dp, 16.5.dp), contentAlignment = Alignment.Center) { PpmIcon(icon, 12.dp, color.color) }
        if (title != null) BasicText(title, style = ppmText(11f, lineHeight = 16.5f).copy(color = color.color))
    }
}

/**
 * Reads the messages again; its dot is the connection — green, or a pulsing red while the
 * socket is down.
 */
@Composable
private fun ReloadButton(connected: Boolean, reload: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    var spinning by remember { mutableStateOf(false) }
    val pulse = rememberInfiniteTransition(label = "pulse")
    // `animate-pulse`: down to half and back every 2 s.
    val dim by pulse.animateFloat(
        1f, 0.5f,
        infiniteRepeatable(tween(1_000, easing = CubicBezierEasing(0.4f, 0f, 0.6f, 1f)), RepeatMode.Reverse),
        label = "dot",
    )
    Box(
        Modifier.size(16.dp).ppmClickable(pressedColor = Color.Transparent) {
            reload()
            spinning = true
            scope.launch {
                delay(600)
                spinning = false
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(ProductIcon.RefreshCw, 12.dp, if (connected) tokens.text2.opacity(0.6).color else tokens.error.color, spinning = spinning)
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 2.dp, y = (-2).dp)
                .size(6.dp)
                .alpha(if (connected) 1f else dim)
                .clip(CircleShape)
                .background((if (connected) tokens.success else tokens.error).color),
        )
    }
}

/** Session actions that do not earn a place of their own (`chat-toolbar-overflow-menu.tsx`). */
@Composable
private fun OverflowButton(model: AppModel, tab: ChatTab) {
    val tokens = LocalPpmTokens.current
    val unread = model.unreadSessions()[tab.session.id] != null
    var open by remember { mutableStateOf(false) }
    Box(
        Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)).ppmClickable(pressedColor = tokens.panel2.color) { open = true },
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(ProductIcon.MoreHorizontal, 14.dp, tokens.text3.color)
        if (open) {
            DropdownAboveEnd(onDismiss = { open = false }, minWidth = 190.dp) {
                if (unread) {
                    MenuItem("Mark as read", onClick = {
                        open = false
                        model.markRead(tab.session.id)
                    }, icon = { MenuIcon(ProductIcon.BellOff, tokens.warning) }, vertical = 12.dp)
                } else {
                    MenuItem("Mark as unread", onClick = {
                        open = false
                        model.markUnread(model.sessions.firstOrNull { it.id == tab.session.id } ?: tab.session)
                    }, icon = { FilledCircleIcon(tokens.accent) }, vertical = 12.dp)
                }
            }
        }
    }
}

/**
 * The chat's own History panel (`border-t border-border/30 bg-surface`): a title search, the tag
 * chips, then the sessions in the dense list the web shows here; picking one shows it in this
 * tab. Tag management is left out until this app has it.
 */
@Composable
private fun ChatHistoryDropdown(model: AppModel, tab: ChatTab, list: HistoryList, close: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val state by list.state.collectAsState()
    val scope = rememberCoroutineScope()
    val line = tokens.border.opacity(0.3).color
    val query = remember(list) { TextFieldState(list.query) }
    LaunchedEffect(query, list) { snapshotFlow { query.text.toString() }.collect { list.query = it } }
    var refreshing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(tokens.panel.color).line(top = true, line)) {
        // `flex items-center gap-1.5 px-2 py-1 border-b border-border/30`.
        Row(
            Modifier.fillMaxWidth().line(top = false, line).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PpmIcon(ProductIcon.Search, 12.dp, tokens.text3.color)
            // A phone's inputs are 16 px whatever the class says.
            PpmTextField(query, "Search sessions...", Modifier.weight(1f))
            SmallIconButton(ProductIcon.CalendarX2, enabled = !model.deletingOld) { model.askDeleteOld = true }
            SmallIconButton(ProductIcon.RefreshCw, spinning = refreshing || state.loading || model.deletingOld, enabled = !refreshing) {
                scope.launch {
                    refreshing = true
                    model.refresh(list)
                    refreshing = false
                }
            }
        }
        val all = state.sessions(model.sessions)
        if (model.tags.tags.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().line(top = false, line)) {
                TagChipBar(model.tags, all.size, state.tag, vertical = 4.dp) { list.tag = it }
            }
        }
        val sessions = state.shown(all)
        val unread = model.unreadSessions()
        Column(Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
            when {
                (state.loading || (state.searchPage == null && model.sessionsLoad == AppModel.Load.Loading)) && all.isEmpty() ->
                    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        PpmIcon(ProductIcon.Loader2, 14.dp, tokens.text3.color, spinning = true)
                    }
                sessions.isEmpty() -> BasicText(
                    if (query.text.isEmpty()) "No sessions yet" else "No matching sessions",
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    style = ppmText(11f, lineHeight = 16.5f).copy(color = tokens.text3.color, textAlign = TextAlign.Center),
                )
                else -> {
                    for (session in sessions) {
                        HistoryBarRow(model, session, unread[session.id]?.type, unread.containsKey(session.id)) {
                            model.replaceTab(tab.tabId, session)
                            close()
                        }
                    }
                    if (state.hasMore(model.hasMoreSessions)) {
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

/** `p-0.5 rounded` around a 12 px icon in the subtle colour. */
@Composable
private fun SmallIconButton(icon: ProductIcon, spinning: Boolean = false, enabled: Boolean = true, size: Dp = 12.dp, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.5f)
            .clip(RoundedCornerShape(4.dp))
            .ppmClickable(enabled = enabled, pressedColor = tokens.panel2.color, onClick = onClick)
            .padding(2.dp),
    ) {
        PpmIcon(icon, size, tokens.text3.color, spinning = spinning)
    }
}

/**
 * `px-3 py-1.5 gap-2`, 11 px: provider, tag dot, title, then pin, rename and delete — always
 * shown on a touch screen, where nothing hovers — and the age. Unread: tinted by what happened
 * last, in the text colour at medium weight. A long press opens the chat's menu.
 */
@Composable
private fun HistoryBarRow(model: AppModel, session: SessionInfo, unreadType: String?, isUnread: Boolean, open: () -> Unit) {
    val tokens = LocalPpmTokens.current
    var renaming by remember { mutableStateOf(false) }
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    val color = if (isUnread) tokens.text else tokens.text2
    val bot = session.title.startsWith("[PPM]")
    val title = if (bot) session.title.drop(7) else session.title
    Box(Modifier.fillMaxWidth().background(if (isUnread) tokens.notification(unreadType).opacity(0.1).color else Color.Transparent)) {
        Row(
            Modifier
                .fillMaxWidth()
                .pressable(pressedColor = tokens.panel2.color, enabled = !renaming, onLongClick = { menuAt = it }, onClick = open)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProviderBadge(session.providerId)
            session.tag?.let { TagDot(it.color) }
            if (renaming) {
                InlineRename(model, session, Modifier.weight(1f)) { renaming = false }
            } else {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (bot) PpmIcon(ProductIcon.Bot, 12.dp, tokens.text2.color)
                    BasicText(
                        PpmFormat.collapsedWhitespace(title).ifEmpty { "Untitled" },
                        style = ppmText(11f, if (isUnread) FontWeight.Medium else FontWeight.Normal, lineHeight = 16.5f).copy(color = color.color),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                RowIcon(if (session.pinned) ProductIcon.PinOff else ProductIcon.Pin, if (session.pinned) tokens.accent else tokens.text3) {
                    model.scope.launch { model.togglePin(session) }
                }
                RowIcon(ProductIcon.Pencil, tokens.text3) { renaming = true }
                RowIcon(ProductIcon.Trash2, tokens.text3) { model.confirmDeleteSession = session }
                session.updatedAt?.let {
                    Box(Modifier.width(64.dp), contentAlignment = Alignment.CenterEnd) {
                        RelativeTime(it, ppmText(10f, lineHeight = 15f).copy(color = tokens.text3.color))
                    }
                }
            }
        }
        menuAt?.let { at ->
            SessionContextMenu(model, session, at, onDismiss = { menuAt = null }, onRename = { renaming = true }, onDelete = { model.confirmDeleteSession = session })
        }
    }
}

/** `p-0.5 rounded` with a 12 px icon. */
@Composable
private fun RowIcon(icon: ProductIcon, tint: Rgba, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Box(Modifier.clip(RoundedCornerShape(4.dp)).ppmClickable(pressedColor = tokens.panel2.color, onClick = onClick).padding(2.dp)) {
        PpmIcon(icon, 12.dp, tint.color)
    }
}

/**
 * Renaming in place (`bg-surface-elevated px-1 py-0.5 rounded border focus:border-primary`):
 * Return or leaving the field saves, the check saves, the cross gives up.
 */
@Composable
fun InlineRename(model: AppModel, session: SessionInfo, modifier: Modifier = Modifier, onDone: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val state = rememberTextFieldState(session.title)
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    fun finish(save: Boolean) {
        if (finished) return
        finished = true
        val title = state.text.toString()
        onDone()
        if (save) model.scope.launch { model.rename(session, title) }
    }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        state.edit { selectAll() }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val shape = RoundedCornerShape(4.dp)
        BasicTextField(
            state,
            Modifier
                .weight(1f)
                .focusRequester(focus)
                .onFocusChanged {
                    if (it.isFocused) focused = true else if (focused) finish(save = true)
                }
                .clip(shape)
                .background(tokens.panel2.color)
                .border(1.dp, (if (focused) tokens.accent else tokens.border).color, shape)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            textStyle = ppmText(16f, lineHeight = 24f).copy(color = tokens.text.color),
            cursorBrush = SolidColor(tokens.accent.color),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            onKeyboardAction = { finish(save = true) },
            lineLimits = TextFieldLineLimits.SingleLine,
        )
        RowIcon(ProductIcon.Check, tokens.success) { finish(save = true) }
        RowIcon(ProductIcon.X, tokens.text3) { finish(save = false) }
    }
}
