package com.hienlh.ppm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.chat.RelativeTime
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFormat
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssScrollY
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.protocol.ProjectTags
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.SessionUnread
import com.hienlh.ppm.state.HistoryList
import kotlinx.coroutines.launch

/**
 * What a panel with no tab shows (`EmptyPanel` in editor-panel.tsx): the tiles that open a tab
 * (Terminal, AI Chat, New File), Close Panel when there is another panel ([onClosePanel]), then the
 * project's chats. On the desktop layout the new tab opens in [panelId]; a phone has only the one.
 */
@Composable
fun EmptyPanel(
    model: AppModel,
    onOpenSession: (SessionInfo) -> Unit,
    modifier: Modifier = Modifier,
    panelId: String? = null,
    onClosePanel: (() -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    val minimalMode = model.info?.minimalMode == true
    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewport = maxHeight
        Column(Modifier.fillMaxSize().cssScrollY()) {
            Column(
                Modifier.fillMaxWidth().heightIn(min = viewport).padding(horizontal = 16.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
            ) {
                val load = model.projectsLoad
                when {
                    load is AppModel.Load.Failed -> Notice(load.message)
                    load == AppModel.Load.Loaded && model.projects.isEmpty() -> Notice("No projects on this server yet. Add one in the PPM web app.")
                    else -> {
                        BasicText("Open a tab to get started", style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text2.color))
                        // `grid grid-cols-3 gap-2 w-full max-w-sm`.
                        BoxWithConstraints(Modifier.widthIn(max = 384.dp).fillMaxWidth()) {
                            val tileWidth = (maxWidth - 16.dp) / 3
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!minimalMode) {
                                    QuickOpenTile(ProductIcon.Terminal, "Terminal", Modifier.width(tileWidth)) {
                                        model.openTerminal(inDock = false, panelId = panelId)
                                    }
                                }
                                QuickOpenTile(ProductIcon.MessageSquare, "AI Chat", Modifier.width(tileWidth)) {
                                    model.newChat(panelId = panelId)
                                }
                                QuickOpenTile(ProductIcon.FilePlus, "New File", Modifier.width(tileWidth)) {
                                    model.openNewFile(panelId = panelId)
                                }
                            }
                        }
                        if (onClosePanel != null && !minimalMode) {
                            // `px-4 py-3.5 rounded-md text-xs text-text-subtle hover:text-foreground`, an X before the words.
                            val hover = rememberHover()
                            val text = tokens.text.mix(tokens.text3, hover.lit.toDouble()).color
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .pressable(pressedColor = tokens.panel2.color, shape = RoundedCornerShape(8.dp), source = hover.source, onClick = onClosePanel)
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                PpmIcon(ProductIcon.X, 14.dp, text)
                                BasicText("Close Panel", style = ppmText(12f, lineHeight = 16f).copy(color = text))
                            }
                        }
                        val list = model.homeList
                        if (list != null) SessionListPanel(model, list, onOpenSession)
                    }
                }
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    val tokens = LocalPpmTokens.current
    BasicText(text, style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text2.color, textAlign = TextAlign.Center))
}

/** `px-2 py-3 rounded-md border bg-surface hover:bg-surface-elevated text-xs`, the icon at 20px over the label. */
@Composable
private fun QuickOpenTile(icon: ProductIcon, label: String, modifier: Modifier, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier
            .clip(shape)
            .background(tokens.panel.color)
            .pressable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, shape = shape, onClick = onClick)
            .cssBorder(tokens.border.color, shape)
            .padding(horizontal = 9.dp, vertical = 13.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PpmIcon(icon, 20.dp, tokens.text.color)
        BasicText(label, style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text.color), maxLines = 1)
    }
}

private const val MAX_RECENT_SESSIONS = 5
private const val FETCH_SESSIONS_LIMIT = 20

/**
 * The chats under the tiles, and under an empty chat's welcome (`session-list-panel.tsx`): a
 * title search, the tag chips, then the pinned chats and the five most recent, with Show more. A
 * long press opens the chat's menu.
 */
@Composable
fun SessionListPanel(
    model: AppModel,
    list: HistoryList,
    onOpen: (SessionInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val state by list.state.collectAsState()
    val unread = model.unreadSessions()
    val query = remember(list) { TextFieldState(list.query) }
    LaunchedEffect(query, list) { snapshotFlow { query.text.toString() }.collect { list.query = it } }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var selectedTag by rememberSaveable { mutableStateOf<Int?>(null) }

    val sessions = state.sessions(model.sessions).take(FETCH_SESSIONS_LIMIT)
    if (sessions.isEmpty() && query.text.isEmpty()) return
    val filtered = selectedTag?.let { tag -> sessions.filter { it.tag?.id == tag } } ?: sessions
    val pinned = filtered.filter { it.pinned }
    val allRecent = filtered.filter { !it.pinned }
    val recent = if (showAll) allRecent else allRecent.take(MAX_RECENT_SESSIONS)

    Column(modifier.fillMaxWidth()) {
        SearchBox(query, "Search chats...")
        Box(Modifier.padding(top = 12.dp)) {
            TagChipBar(model.tags, sessions.size, selectedTag) { selectedTag = it }
        }
        if (pinned.isNotEmpty()) {
            SessionGroup("Pinned", pinned, unread, model, onOpen)
        }
        if (recent.isNotEmpty()) {
            SessionGroup("Recent chats", recent, unread, model, onOpen, syncing = model.syncingSessions)
            if (allRecent.size > MAX_RECENT_SESSIONS) {
                // `text-text-subtle hover:text-text-primary`.
                val hover = rememberHover()
                val text = tokens.text.mix(tokens.text3, hover.lit.toDouble()).color
                Row(
                    Modifier
                        .fillMaxWidth()
                        .pressable(pressedColor = Color.Transparent, source = hover.source) { showAll = !showAll }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PpmIcon(if (showAll) ProductIcon.ChevronUp else ProductIcon.ChevronDown, 12.dp, text)
                    BasicText(
                        if (showAll) "Show less" else "Show more (${allRecent.size - MAX_RECENT_SESSIONS})",
                        style = ppmText(11f, lineHeight = 16.5f).copy(color = text),
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionGroup(
    title: String,
    sessions: List<SessionInfo>,
    unread: Map<String, SessionUnread>,
    model: AppModel,
    onOpen: (SessionInfo) -> Unit,
    syncing: Boolean = false,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            BasicText(title, style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color))
            if (syncing) PpmIcon(ProductIcon.Loader2, 10.dp, tokens.text3.color, Modifier.padding(start = 6.dp), spinning = true)
        }
        Column(Modifier.fillMaxWidth().clip(shape).background(tokens.panel.color).cssBorder(tokens.border.color, shape)) {
            sessions.forEachIndexed { index, session ->
                SessionRow(model, session, unread[session.id], last = index == sessions.lastIndex, onOpen = { onOpen(session) })
            }
        }
    }
}

/**
 * `px-3 py-2.5 gap-2.5`: provider, tag dot, title (semibold and tinted while unread), age, and the
 * pin — always shown, as Chrome on a touch screen never applies the `can-hover:` that hides it.
 * A mouse lights the row (`hover:bg-surface-elevated`) and the pin. A long press opens the chat's
 * menu, without the rename and delete the web leaves out here.
 */
@Composable
private fun SessionRow(model: AppModel, session: SessionInfo, unread: SessionUnread?, last: Boolean, onOpen: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val tint = unread?.let { tokens.notification(it.type).opacity(0.1).color } ?: Color.Transparent
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    Column(Modifier.fillMaxWidth().background(tint)) {
        menuAt?.let { at -> SessionContextMenu(model, session, at, onDismiss = { menuAt = null }) }
        Row(
            Modifier
                .fillMaxWidth()
                .pressable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, onLongClick = { menuAt = it }, onClick = onOpen)
                // `border-b border-border/50 last:border-0`.
                .then(if (last) Modifier else Modifier.cssBorder(tokens.border.opacity(0.5).color, sides = CssSides.Bottom))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ProviderBadge(session.providerId)
            session.tag?.let { TagDot(it.color) }
            BasicText(
                PpmFormat.collapsedWhitespace(session.title).ifEmpty { "Untitled" },
                Modifier.weight(1f),
                style = ppmText(12f, if (unread != null) FontWeight.SemiBold else FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            session.updatedAt?.let { RelativeTime(it, ppmText(10f, lineHeight = 15f).copy(color = tokens.text3.color)) }
            // `hover:text-primary/70` pinned, `hover:text-text-primary` not.
            val pin = rememberHover()
            Box(
                Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)).pressable(pressedColor = tokens.panel2.color, source = pin.source) {
                    model.scope.launch { model.togglePin(session) }
                },
                contentAlignment = Alignment.Center,
            ) {
                val tint = if (session.pinned) tokens.accent.opacity(0.7).mix(tokens.accent, pin.lit.toDouble()) else tokens.text.mix(tokens.text3, pin.lit.toDouble())
                PpmIcon(if (session.pinned) ProductIcon.PinOff else ProductIcon.Pin, 12.dp, tint.color)
            }
        }
    }
}

/**
 * `rounded-md border bg-surface pl-8 pr-8` with the search icon inside on the left and, once there
 * is text, a clear button on the right (`absolute right-2`).
 */
@Composable
fun SearchBox(state: TextFieldState, placeholder: String, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    var focused by remember { mutableStateOf(false) }
    // `py-1.5 text-xs` and the border; on a phone inputs are 16 px but keep `text-xs`'s 4/3 line.
    val ring = tokens.accent.opacity(0.5).color
    Box(
        modifier
            .fillMaxWidth()
            .drawBehind {
                // `focus:ring-1 ring-primary/50`: a pixel outside the border, which stays.
                if (focused) {
                    val px = 1.dp.toPx()
                    drawRoundRect(
                        ring,
                        Offset(-px / 2, -px / 2),
                        Size(size.width + px, size.height + px),
                        CornerRadius(8.dp.toPx() + px / 2),
                        style = Stroke(px),
                    )
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // The input has the border and `pl-8 pr-8`; the icon sits `left-2.5` from its outer edge.
        Box(Modifier.fillMaxWidth().clip(shape).background(tokens.panel.color).cssBorder(tokens.border.color, shape)) {
            PpmTextField(
                state,
                placeholder,
                Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 6.dp),
                style = ppmText(16f, lineHeight = 64f / 3),
                desktopStyle = ppmText(12f, lineHeight = 16f),
                onFocusChanged = { focused = it },
            )
        }
        PpmIcon(ProductIcon.Search, 14.dp, tokens.text3.color, Modifier.padding(start = 10.dp))
        if (state.text.isNotEmpty()) {
            // The 14 px X 8 px in from the edge, as the web places it, in a 24 dp target;
            // `text-text-subtle hover:text-text-primary`, at once.
            val hover = rememberHover(instant = true)
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 3.dp)
                    .size(24.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .pressable(pressedColor = tokens.panel2.color, source = hover.source) { state.clearText() },
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.X, 14.dp, tokens.text.mix(tokens.text3, hover.lit.toDouble()).color)
            }
        }
    }
}

/** `TagChipBar`: All, then each tag with its count; the picked one in its colour, the others lit under a mouse if [hover]. */
@Composable
fun TagChipBar(tags: ProjectTags, total: Int, selected: Int?, vertical: Dp = 6.dp, hover: Boolean = true, onSelect: (Int?) -> Unit) {
    if (tags.tags.isEmpty()) return
    val tokens = LocalPpmTokens.current
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = vertical),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TagChip("All ($total)", selected == null, null, hover) { onSelect(null) }
        for (tag in tags.tags) {
            TagChip("${tag.name} (${tags.counts[tag.id] ?: 0})", selected == tag.id, Rgba.hex(tag.color) ?: tokens.text3, hover) {
                onSelect(if (selected == tag.id) null else tag.id)
            }
        }
    }
}

/**
 * `rounded-md border px-2 py-1 text-[10px]`: All in the accent at 20% when on, a tag in its colour
 * at 12.5%; one not picked lit under a mouse (`hover:bg-surface-elevated`).
 */
@Composable
private fun TagChip(label: String, selected: Boolean, color: Rgba?, hover: Boolean, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    val tint = color ?: tokens.accent
    val background = when {
        !selected -> tokens.panel
        color == null -> tint.opacity(0.2)
        else -> tint.opacity(0.125)
    }
    Row(
        Modifier
            .clip(shape)
            .background(background.color)
            .pressable(pressedColor = tokens.panel2.color, hoverColor = if (selected || !hover) Color.Transparent else tokens.panel2.color, shape = shape, onClick = onClick)
            .cssBorder(if (selected) tint.color else tokens.border.color, shape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (color != null) Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color.color))
        BasicText(label, style = ppmText(10f, lineHeight = 15f).copy(color = if (selected) tint.color else tokens.text2.color), maxLines = 1)
    }
}
