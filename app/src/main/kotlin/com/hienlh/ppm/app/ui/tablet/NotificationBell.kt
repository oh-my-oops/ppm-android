package com.hienlh.ppm.app.ui.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.PlacedPopup
import com.hienlh.ppm.app.ui.ShadowLg
import com.hienlh.ppm.app.ui.phone.notifications.NotificationsEmptyState
import com.hienlh.ppm.app.ui.phone.notifications.UnreadSessionGroups
import com.hienlh.ppm.app.ui.phone.notifications.openUnreadBellSession
import com.hienlh.ppm.app.ui.unreadSessions
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ThemeTokens
import com.hienlh.ppm.design.chromeScrollbar
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.kit.HoverTooltip
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.protocol.ProjectInfo
import com.hienlh.ppm.protocol.SessionUnread
import com.hienlh.ppm.state.formatUnreadSummary
import com.hienlh.ppm.state.groupUnreadSessions

/**
 * The rail's bell (`notification-bell-popover.tsx`): `size-8 rounded-md`, the number of unread
 * sessions in a red badge over its corner, and a card beside it listing them by project.
 */
@Composable
internal fun NotificationBell(model: AppModel) {
    val events = model.events
    NotificationBellView(
        unread = model.unreadSessions(),
        projects = model.projects,
        onClearAll = { events?.clearAll() },
        onOpenSession = { sessionId, projectName -> model.openUnreadBellSession(sessionId, projectName) },
        onOpenSettings = { model.openSettings("notifications") },
    )
}

/** Stateless bell: [onOpenSession] answers whether the chat opened, which closes the card. */
@Composable
internal fun NotificationBellView(
    unread: Map<String, SessionUnread>,
    projects: List<ProjectInfo>,
    onClearAll: () -> Unit,
    onOpenSession: (sessionId: String, projectName: String) -> Boolean,
    onOpenSettings: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val unreadCount = unread.size
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val hover = rememberHover(enabled = !open)

    Box(Modifier.size(32.dp)) {
        HoverTooltip(label = "Notifications", enabled = !open) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(shape)
                    .background(if (open) tokens.accent.opacity(0.1).color else Color.Transparent)
                    .semantics { contentDescription = "Notifications" }
                    .ppmClickable(
                        pressedColor = if (open) Color.Transparent else tokens.panel2.color,
                        hoverColor = if (open) Color.Transparent else tokens.panel2.color,
                        shape = shape,
                        onClickLabel = "Notifications",
                        source = hover.source,
                    ) { open = !open },
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(
                    ProductIcon.Bell,
                    16.dp,
                    (if (open) tokens.accent else tokens.text.mix(tokens.text3, hover.lit.toDouble())).color,
                )
            }
        }
        if (unreadCount > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(4.dp, (-4).dp)
                    .height(16.dp)
                    .widthIn(min = 16.dp)
                    .clip(CircleShape)
                    .background(tokens.error.color)
                    .padding(horizontal = 4.dp)
                    .testTag("notification-bell-badge"),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    "$unreadCount",
                    style = ppmText(9f, FontWeight.Bold, lineHeight = 13.5f).copy(color = Color.White),
                )
            }
        }
        if (open) {
            val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
            val place = remember(gap) {
                { anchor: IntRect, window: IntSize, box: IntSize ->
                    val x = (anchor.right + gap).coerceIn(0, maxOf(0, window.width - box.width))
                    val y = (anchor.bottom - box.height).coerceIn(8, maxOf(8, window.height - box.height - 8))
                    IntOffset(x, y)
                }
            }
            PlacedPopup(place, onDismiss = { open = false }) {
                NotificationBellCard(
                    unread = unread,
                    projects = projects,
                    tokens = tokens,
                    onClearAll = {
                        onClearAll()
                        open = false
                    },
                    onOpenSession = { sessionId, projectName ->
                        if (onOpenSession(sessionId, projectName)) open = false
                    },
                    onOpenSettings = {
                        open = false
                        onOpenSettings()
                    },
                    close = { open = false },
                )
            }
        }
    }
}

/**
 * The card: "Notifications" over "N unread sessions" / "No unread" with Clear all and Close, the
 * unread sessions grouped by project (or "All caught up"), and Notification settings at the foot.
 */
@Composable
internal fun NotificationBellCard(
    unread: Map<String, SessionUnread>,
    projects: List<ProjectInfo>,
    tokens: ThemeTokens,
    onClearAll: () -> Unit,
    onOpenSession: (sessionId: String, projectName: String) -> Unit,
    onOpenSettings: () -> Unit,
    close: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val scroll = rememberScrollState()
    val unreadCount = unread.size
    val groups = remember(unread) { groupUnreadSessions(unread) }

    Column(
        Modifier
            .width(420.dp)
            .heightIn(max = 520.dp)
            .then(ShadowLg.fold(Modifier as Modifier) { m, s -> m.ppmShadow(s, shape) })
            .clip(shape)
            .background(tokens.panel2.over(tokens.bgSolid).color)
            .cssBorder(tokens.border.color, shape)
            .testTag("notification-bell-card"),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .cssBorder(tokens.borderSoft.color, sides = CssSides.Bottom)
                .padding(start = 16.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                BasicText(
                    "Notifications",
                    style = ppmText(15f, FontWeight.SemiBold, lineHeight = 19f).copy(color = tokens.text.color),
                    maxLines = 1,
                )
                BasicText(
                    formatUnreadSummary(unreadCount),
                    style = ppmText(11.5f, FontWeight.Normal, lineHeight = 15f).copy(color = tokens.text2.color),
                    maxLines = 1,
                )
            }
            if (unreadCount > 0) {
                val clearHover = rememberHover()
                val clearShape = RoundedCornerShape(999.dp)
                val clearColor = tokens.text.mix(tokens.text2, clearHover.lit.toDouble()).color
                Row(
                    Modifier
                        .height(30.dp)
                        .clip(clearShape)
                        .ppmClickable(
                            pressedColor = tokens.surfaceHover.color,
                            hoverColor = tokens.surfaceHover.color,
                            shape = clearShape,
                            source = clearHover.source,
                            onClickLabel = "Clear all",
                            onClick = onClearAll,
                        )
                        .padding(horizontal = 10.dp)
                        .semantics { contentDescription = "Clear all" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PpmIcon(ProductIcon.BellOff, 14.dp, clearColor)
                    BasicText(
                        "Clear all",
                        style = ppmText(12f, FontWeight.SemiBold, lineHeight = 15f).copy(color = clearColor),
                    )
                }
            }
            val closeShape = RoundedCornerShape(10.dp)
            HoverTooltip(label = "Close") {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(closeShape)
                        .ppmClickable(
                            pressedColor = tokens.surfaceHover.color,
                            hoverColor = tokens.surfaceHover.color,
                            shape = closeShape,
                            onClickLabel = "Close",
                            onClick = close,
                        )
                        .semantics { contentDescription = "Close" },
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(ProductIcon.X, 16.dp, tokens.text2.color)
                }
            }
        }

        if (unreadCount == 0) {
            NotificationsEmptyState(tokens = tokens, compact = true)
        } else {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .chromeScrollbar(scroll, vertical = true, tokens.border.color, border = 1.dp)
                    .verticalScroll(scroll),
            ) {
                UnreadSessionGroups(groups = groups, projects = projects, mobile = false, onOpen = onOpenSession)
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .cssBorder(tokens.borderSoft.color, sides = CssSides.Top)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val btnShape = RoundedCornerShape(12.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clip(btnShape)
                    .background(tokens.panel.color, btnShape)
                    .cssBorder(tokens.border.color, btnShape)
                    .ppmClickable(
                        pressedColor = tokens.surfaceHover.color,
                        hoverColor = tokens.surfaceHover.color,
                        shape = btnShape,
                        onClickLabel = "Notification settings",
                        onClick = onOpenSettings,
                    )
                    .padding(horizontal = 14.dp)
                    .semantics { contentDescription = "Notification settings" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                PpmIcon(ProductIcon.Settings, 15.dp, tokens.text.color)
                BasicText(
                    "Notification settings",
                    style = ppmText(12.5f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color),
                )
            }
        }
    }
}
