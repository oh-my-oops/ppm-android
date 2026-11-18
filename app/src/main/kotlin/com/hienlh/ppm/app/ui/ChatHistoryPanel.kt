package com.hienlh.ppm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ChatTab
import com.hienlh.ppm.chat.RelativeTime
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFormat
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.cssScrollY
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.state.HistoryList
import kotlinx.coroutines.launch

/**
 * Session history inside the footer's History popover/sheet (`ChatHistoryPanel` in
 * `chat-history-panel.tsx`).
 */
@Composable
fun ChatHistoryPanel(
    model: AppModel,
    tab: ChatTab,
    list: HistoryList,
    historyFilter: String? = null,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val state by list.state.collectAsState()
    val scope = rememberCoroutineScope()
    val lineColor = tokens.border.opacity(0.3).color
    val query = remember(list) { TextFieldState(list.query) }
    LaunchedEffect(query, list) { snapshotFlow { query.text.toString() }.collect { list.query = it } }
    var refreshing by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth().testTag("chat-history-panel")) {
        Row(
            Modifier
                .fillMaxWidth()
                .line(top = false, lineColor)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PpmIcon(ProductIcon.Search, 12.dp, tokens.text3.color)
            PpmTextField(
                query,
                "Search sessions...",
                Modifier.weight(1f),
                desktopStyle = ppmText(11f, lineHeight = 16.5f),
            )
            HistoryIconButton(
                icon = ProductIcon.CalendarX2,
                hoverTint = tokens.error,
                enabled = !model.deletingOld,
                title = "Delete old sessions...",
            ) {
                model.askDeleteOld = true
            }
            HistoryIconButton(
                icon = ProductIcon.RefreshCw,
                hoverTint = tokens.text2,
                spinning = refreshing || state.loading || model.deletingOld,
                enabled = !refreshing,
                title = "Refresh",
            ) {
                scope.launch {
                    refreshing = true
                    model.refresh(list)
                    refreshing = false
                }
            }
        }

        val all = state.sessions(model.sessions)
        if (model.tags.tags.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().line(top = false, lineColor)) {
                TagChipBar(model.tags, all.size, state.tag, vertical = 4.dp, hover = false) { list.tag = it }
            }
        }
        val sessions = state.shown(all)
        val unread = model.unreadSessions()
        val listModifier = if (mobile) {
            Modifier.fillMaxWidth().cssScrollY()
        } else {
            Modifier.fillMaxWidth().heightIn(max = 260.dp).cssScrollY()
        }

        Column(listModifier) {
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
                        ChatHistoryRow(
                            model = model,
                            projectName = tab.project,
                            session = session,
                            unreadType = unread[session.id]?.type,
                            isUnread = unread.containsKey(session.id),
                            onOpen = {
                                model.replaceTab(tab.tabId, session)
                                onClose()
                            },
                        )
                    }
                    if (state.hasMore(model.hasMoreSessions)) {
                        val hover = rememberHover()
                        val text = tokens.text2.mix(tokens.text3, hover.lit.toDouble()).color
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .ppmClickable(
                                    enabled = !state.loadingMore,
                                    pressedColor = tokens.panel2.color,
                                    hoverColor = tokens.panel2.color,
                                    source = hover.source,
                                    hoverWhileDisabled = true,
                                ) {
                                    scope.launch { list.loadMore(model.sessions, model.hasMoreSessions) }
                                }
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (state.loadingMore) PpmIcon(ProductIcon.Loader2, 12.dp, text, spinning = true)
                            BasicText(
                                if (state.loadingMore) "Loading..." else "Load more",
                                style = ppmText(11f, lineHeight = 16.5f).copy(color = text),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryIconButton(
    icon: ProductIcon,
    hoverTint: Rgba,
    spinning: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 12.dp,
    title: String? = null,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover()
    var mod = Modifier
        .alpha(if (enabled) 1f else 0.5f)
        .clip(RoundedCornerShape(4.dp))
    if (title != null) mod = mod.webTitle(title)
    Box(
        mod
            .ppmClickable(
                enabled = enabled,
                pressedColor = tokens.panel2.color,
                source = hover.source,
                hoverWhileDisabled = true,
                onClick = onClick,
            )
            .padding(2.dp),
    ) {
        PpmIcon(icon, size, hoverTint.mix(tokens.text3, hover.lit.toDouble()).color, spinning = spinning)
    }
}

@Composable
private fun ChatHistoryRow(
    model: AppModel,
    projectName: String,
    session: SessionInfo,
    unreadType: String?,
    isUnread: Boolean,
    onOpen: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var renaming by remember { mutableStateOf(false) }
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    val color = if (isUnread) tokens.text else tokens.text2
    val bot = session.title.startsWith("[PPM]")
    val title = if (bot) session.title.drop(7) else session.title
    val hover = rememberHover()

    Box(
        Modifier
            .fillMaxWidth()
            .background(if (isUnread) tokens.notification(unreadType).opacity(0.1).color else Color.Transparent)
            .background(tokens.panel2.opacity(hover.lit.toDouble()).color)
            .hoverable(hover.source)
            .testTag("history-row:${session.id}"),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .pressable(
                    pressedColor = tokens.panel2.color,
                    enabled = !renaming,
                    onLongClick = { menuAt = it },
                    onClick = onOpen,
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProviderBadge(session.providerId)
            session.tag?.let { TagDot(it.color) }
            SpawnedSessionBadge(model = model, session = session, projectName = projectName)
            if (renaming) {
                InlineRename(model, session, RenameField.HistoryBar, Modifier.weight(1f)) { renaming = false }
            } else {
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (bot) PpmIcon(ProductIcon.Bot, 12.dp, tokens.text2.color)
                    BasicText(
                        PpmFormat.collapsedWhitespace(title).ifEmpty { "Untitled" },
                        style = ppmText(11f, if (isUnread) FontWeight.Medium else FontWeight.Normal, lineHeight = 16.5f).copy(
                            color = color.color,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    com.hienlh.ppm.app.ui.assistant.ManagerChatBadge(model, session.id)
                }
                HistoryRowActionIcon(
                    icon = if (session.pinned) ProductIcon.PinOff else ProductIcon.Pin,
                    tint = if (session.pinned) tokens.accent else tokens.text3,
                    hoverTint = if (session.pinned) tokens.accent.opacity(0.7) else tokens.text2,
                    fade = true,
                    title = if (session.pinned) "Unpin session" else "Pin session",
                ) {
                    model.scope.launch { model.togglePin(session) }
                }
                HistoryRowActionIcon(
                    icon = ProductIcon.Pencil,
                    tint = tokens.text3,
                    hoverTint = tokens.text2,
                    title = "Rename session",
                ) {
                    renaming = true
                }
                HistoryRowActionIcon(
                    icon = ProductIcon.Trash2,
                    tint = tokens.text3,
                    hoverTint = tokens.error,
                    hoverFill = tokens.error.opacity(0.2),
                    title = "Delete session",
                ) {
                    model.confirmDeleteSession = session
                }
                session.updatedAt?.let {
                    Box(Modifier.width(64.dp), contentAlignment = Alignment.CenterEnd) {
                        RelativeTime(it, ppmText(10f, lineHeight = 15f).copy(color = tokens.text3.color))
                    }
                }
            }
        }
        menuAt?.let { at ->
            SessionContextMenu(
                model,
                session,
                at,
                onDismiss = { menuAt = null },
                onRename = { renaming = true },
                onDelete = { model.confirmDeleteSession = session },
            )
        }
    }
}

@Composable
private fun HistoryRowActionIcon(
    icon: ProductIcon,
    tint: Rgba,
    hoverTint: Rgba,
    hoverFill: Rgba? = null,
    fade: Boolean = false,
    title: String? = null,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover(instant = !fade)
    var mod = Modifier.clip(RoundedCornerShape(4.dp))
    if (title != null) mod = mod.webTitle(title)
    Box(
        mod
            .ppmClickable(
                pressedColor = tokens.panel2.color,
                hoverColor = hoverFill?.color ?: Color.Transparent,
                fade = fade,
                source = hover.source,
                onClick = onClick,
            )
            .padding(2.dp),
    ) {
        PpmIcon(icon, 12.dp, hoverTint.mix(tint, hover.lit.toDouble()).color)
    }
}

/**
 * Badge on a session created by another session (`SpawnedSessionBadge` in `spawned-session-badge.tsx`).
 */
@Composable
fun SpawnedSessionBadge(
    model: AppModel,
    session: SessionInfo,
    projectName: String,
    modifier: Modifier = Modifier,
) {
    val parentId = session.spawnedBy?.takeIf { it.isNotEmpty() } ?: return
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    var sheetOpen by remember { mutableStateOf(false) }

    val parent = model.sessions.firstOrNull { it.id == parentId }
    val parentTitle = (session.parentTitle ?: parent?.title)?.trim().takeUnless { it.isNullOrEmpty() }
    val label = "Created by ${parentTitle ?: "another session"}"

    fun openParent() {
        model.openLinkedSession(
            projectName = projectName,
            sessionId = parentId,
            providerId = parent?.providerId ?: session.providerId,
            title = parentTitle ?: parentId.take(8),
        )
    }

    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier
            .size(20.dp)
            .clip(shape)
            .webTitle(label)
            .semantics { contentDescription = label }
            .ppmClickable(
                pressedColor = tokens.info.opacity(0.10).color,
                hoverColor = tokens.info.opacity(0.10).color,
                shape = shape,
                role = Role.Button,
                onClick = {
                    if (mobile) sheetOpen = true else openParent()
                },
            )
            .testTag("spawned-badge:$parentId"),
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(ProductIcon.GitBranch, 14.dp, tokens.info.color)
    }

    if (sheetOpen) {
        BottomSheet(onDismiss = { sheetOpen = false }, radius = 14.dp) {
            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PpmIcon(ProductIcon.GitBranch, 16.dp, tokens.info.color)
                    BasicText(
                        label,
                        style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text.color),
                    )
                }
                BasicText(
                    "An AI in that session created this one. You can open, chat into and stop either.",
                    style = ppmText(12f, lineHeight = 18f).copy(color = tokens.text3.color),
                )
                val btnShape = RoundedCornerShape(8.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 44.dp)
                        .clip(btnShape)
                        .background(tokens.accent.color)
                        .ppmClickable(
                            pressedColor = tokens.accent.opacity(0.85).color,
                            hoverColor = tokens.accent.opacity(0.90).color,
                            shape = btnShape,
                            role = Role.Button,
                            onClick = {
                                sheetOpen = false
                                openParent()
                            },
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PpmIcon(ProductIcon.ExternalLink, 16.dp, Color.White)
                    BasicText(
                        "Open the session that created it",
                        style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = Color.White),
                    )
                }
            }
        }
    }
}
