package com.hienlh.ppm.app.ui.phone.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.unreadSessions
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ThemeTokens
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.kit.Sheet
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.ProjectInfo
import com.hienlh.ppm.protocol.SessionUnread
import com.hienlh.ppm.state.formatUnreadSummary
import com.hienlh.ppm.state.groupUnreadSessions

/**
 * Phone notifications bottom sheet: the web bell's list (`notification-bell-popover.tsx`) as a
 * sheet — the unread sessions of every project, grouped under their project, each opening its chat.
 */
@Composable
fun NotificationsSheet(
    model: AppModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val events = model.events
    NotificationsSheetContent(
        unread = model.unreadSessions(),
        projects = model.projects,
        onDismiss = onDismiss,
        onClearAll = {
            events?.clearAll()
            onDismiss()
        },
        onOpenSession = { sessionId, projectName ->
            if (model.openUnreadBellSession(sessionId, projectName)) onDismiss()
        },
        onOpenSettings = {
            onDismiss()
            model.openSettings("notifications")
        },
        modifier = modifier,
    )
}

/**
 * Stateless phone notifications sheet: "N unread sessions" / "No unread" with Clear all, the
 * sessions grouped by project, or "All caught up".
 */
@Composable
fun NotificationsSheetContent(
    unread: Map<String, SessionUnread>,
    projects: List<ProjectInfo>,
    onDismiss: () -> Unit,
    onClearAll: () -> Unit,
    onOpenSession: (sessionId: String, projectName: String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val unreadCount = unread.size
    val groups = remember(unread) { groupUnreadSessions(unread) }
    val scroll = rememberScrollState()

    Sheet(
        title = "Notifications",
        subtitle = formatUnreadSummary(unreadCount),
        onDismiss = onDismiss,
        forceBottomSheet = true,
        modifier = modifier,
        headerActions = {
            if (unreadCount > 0) {
                val pillShape = RoundedCornerShape(999.dp)
                Row(
                    modifier = Modifier
                        .height(44.dp)
                        .clip(pillShape)
                        .ppmClickable(
                            shape = pillShape,
                            pressedColor = tokens.surfaceHover.color,
                            hoverColor = tokens.surfaceHover.color,
                            onClickLabel = "Clear all",
                            onClick = onClearAll,
                        )
                        .padding(horizontal = 10.dp)
                        .semantics { contentDescription = "Clear all" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PpmIcon(ProductIcon.BellOff, 14.dp, tokens.text2.color)
                    BasicText(
                        text = "Clear all",
                        style = ppmText(12.5f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text2.color),
                        maxLines = 1,
                    )
                }
            }
        },
        footer = {
            val btnShape = RoundedCornerShape(12.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(btnShape)
                    .background(tokens.panel.color, btnShape)
                    .cssBorder(tokens.border.color, btnShape, width = 1.dp)
                    .ppmClickable(
                        shape = btnShape,
                        pressedColor = tokens.surfaceHover.color,
                        hoverColor = tokens.surfaceHover.color,
                        onClickLabel = "Notification settings",
                        onClick = onOpenSettings,
                    )
                    .padding(horizontal = 14.dp)
                    .semantics { contentDescription = "Notification settings" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                PpmIcon(ProductIcon.Settings, 18.dp, tokens.text.color)
                BasicText(
                    text = "Notification settings",
                    style = ppmText(14f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
                    maxLines = 1,
                )
            }
        },
    ) {
        if (unreadCount == 0) {
            NotificationsEmptyState(tokens = tokens)
        } else {
            Column(Modifier.fillMaxWidth().verticalScroll(scroll)) {
                UnreadSessionGroups(groups = groups, projects = projects, mobile = true, onOpen = onOpenSession)
            }
        }
    }
}

/**
 * `NT2 · All caught up` empty state inside the phone notifications sheet.
 */
@Composable
internal fun NotificationsEmptyState(
    tokens: ThemeTokens = LocalPpmTokens.current,
    compact: Boolean = false,
) {
    val orbSize = if (compact) 56.dp else 88.dp
    val iconSize = if (compact) 26.dp else 36.dp
    val titleSize = if (compact) 15f else 18f
    val copySize = if (compact) 12.5f else 13.5f
    val copyText = if (compact) {
        "Chats that finish, ask or wait for you show up here."
    } else {
        "Chats that finish a turn, ask a question or wait for approval show up here across every project."
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = 24.dp,
                vertical = if (compact) 28.dp else 36.dp,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(orbSize)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            tokens.accentWash.color,
                            tokens.panel.color,
                        ),
                    ),
                    CircleShape,
                )
                .cssBorder(tokens.borderSoft.color, CircleShape, width = 1.dp),
            contentAlignment = Alignment.Center,
        ) {
            PpmIcon(ProductIcon.CheckCircle2, iconSize, tokens.accent.color)
        }
        BasicText(
            text = "All caught up",
            style = ppmText(titleSize, FontWeight.SemiBold, lineHeight = titleSize + 5f)
                .copy(color = tokens.text.color, textAlign = TextAlign.Center),
        )
        BasicText(
            text = copyText,
            style = ppmText(copySize, FontWeight.Normal, lineHeight = copySize + 6f)
                .copy(color = tokens.text2.color, textAlign = TextAlign.Center),
        )
    }
}
