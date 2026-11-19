package com.hienlh.ppm.app.ui.phone.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.schedules
import com.hienlh.ppm.app.ui.BottomSheet
import com.hienlh.ppm.app.ui.pressable
import com.hienlh.ppm.app.ui.projectBackground
import com.hienlh.ppm.app.ui.unreadSessions
import com.hienlh.ppm.client.FeedKind
import com.hienlh.ppm.client.FeedRow
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ThemeTokens
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.kit.Sheet
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.ProjectInfo
import com.hienlh.ppm.state.NotificationFeed
import com.hienlh.ppm.state.displayLabel
import com.hienlh.ppm.state.formatNotificationTime
import com.hienlh.ppm.state.formatUnreadSummary
import com.hienlh.ppm.state.groupFeedRowsByProject
import kotlinx.coroutines.launch

/**
 * Phone notifications bottom sheet (`Phone 2 · Section 5`: `NT1 · Notifications`,
 * `NT2 · All caught up`, and `NT3 · One row's actions`).
 *
 * Reads the persisted notification feed from [NotificationFeed] and falls back to
 * [AppModel.unreadSessions] when connected to an older server.
 */
@Composable
fun NotificationsSheet(
    model: AppModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val events = model.events
    val feed = events?.feed
    LaunchedEffect(feed) {
        feed?.ensureLoaded()
    }
    val feedRows = feed?.rows?.collectAsState()?.value.orEmpty()
    val feedSupported = feed?.supported?.collectAsState()?.value ?: true
    val unreadSessions = model.unreadSessions()

    val rows = remember(feedRows, feedSupported, unreadSessions, model.selectedProject) {
        if (feedSupported && feedRows.isNotEmpty()) {
            feedRows
        } else if (!feedSupported || feedRows.isEmpty()) {
            if (feedRows.isNotEmpty()) {
                feedRows
            } else {
                NotificationFeed.fallbackRowsFromUnread(
                    unreadMap = unreadSessions,
                    defaultProject = model.selectedProject,
                )
            }
        } else {
            emptyList()
        }
    }

    var actionRow by remember { mutableStateOf<FeedRow?>(null) }

    NotificationsSheetContent(
        rows = rows,
        projects = model.projects,
        onDismiss = onDismiss,
        onClearAll = {
            if (feed != null) {
                feed.readAll()
            } else {
                events?.clearAll()
            }
        },
        onOpenRow = { row ->
            if (!row.read) {
                feed?.setRead(row.id, true)
            }
            if (openNotificationTarget(model, row)) {
                onDismiss()
            }
        },
        onLongPressRow = { row ->
            actionRow = row
        },
        onOpenSettings = {
            onDismiss()
            model.openSettings("notifications")
        },
        modifier = modifier,
    )

    val selected = actionRow
    if (selected != null) {
        NotificationRowActionsSheet(
            row = selected,
            onDismiss = { actionRow = null },
            onOpen = {
                val target = selected
                actionRow = null
                if (!target.read) {
                    feed?.setRead(target.id, true)
                }
                if (openNotificationTarget(model, target)) {
                    onDismiss()
                }
            },
            onToggleRead = { wantRead ->
                val target = selected
                actionRow = null
                if (feed != null) {
                    feed.setRead(target.id, wantRead)
                } else if (target.sessionId != null) {
                    if (wantRead) {
                        events?.markRead(target.sessionId!!)
                    } else {
                        events?.markUnread(target.sessionId!!, target.title)
                    }
                }
            },
        )
    }
}

/**
 * Stateless phone notifications sheet content (`NT1 · Notifications` and `NT2 · All caught up`).
 */
@Composable
fun NotificationsSheetContent(
    rows: List<FeedRow>,
    projects: List<ProjectInfo>,
    onDismiss: () -> Unit,
    onClearAll: () -> Unit,
    onOpenRow: (FeedRow) -> Unit,
    onLongPressRow: (FeedRow) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val unreadCount = remember(rows) { rows.count { !it.read } }
    val groups = remember(rows) { groupFeedRowsByProject(rows) }
    val projectNames = remember(projects) { projects.map { it.name } }
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
                        .height(36.dp)
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
        if (rows.isEmpty()) {
            NotificationsEmptyState(tokens = tokens)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll),
            ) {
                for (group in groups) {
                    val idx = projectNames.indexOf(group.project)
                    val known = projects.getOrNull(idx)
                    val dotBrush = if (known != null) {
                        projectBackground(known.color, idx)
                    } else {
                        SolidColor(tokens.text3.color)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(dotBrush),
                        )
                        BasicText(
                            text = group.project.uppercase(),
                            style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f)
                                .copy(color = tokens.text3.color, letterSpacing = 0.07.em),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    for (row in group.rows) {
                        PhoneNotificationRow(
                            row = row,
                            tokens = tokens,
                            onClick = { onOpenRow(row) },
                            onLongPress = { onLongPressRow(row) },
                        )
                    }
                }
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

/**
 * Single notification row in `NT1 · Notifications` (`min-height: 72dp`).
 */
@Composable
internal fun PhoneNotificationRow(
    row: FeedRow,
    tokens: ThemeTokens,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val isUrgentApproval = row.kind == FeedKind.APPROVAL && !row.read
    val dotColor = if (row.kind == FeedKind.APPROVAL) tokens.error.color else tokens.accent.color
    val timeLabel = remember(row.createdAt) { formatNotificationTime(row.createdAt) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .alpha(if (row.read) 0.58f else 1f)
            .cssBorder(tokens.borderSoft.color, width = 1.dp, sides = CssSides.Bottom)
            .pressable(
                pressedColor = tokens.surfaceHover.color,
                hoverColor = tokens.surfaceHover.color,
                onLongClick = { onLongPress() },
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 6 dp unread dot (transparent when read so alignment stays consistent)
        Box(
            modifier = Modifier
                .padding(top = 17.dp)
                .size(6.dp)
                .alpha(if (row.read) 0f else 1f)
                .clip(CircleShape)
                .background(dotColor),
        )

        NotificationKindTile(row = row, tokens = tokens)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicText(
                    text = row.title.ifBlank { row.kind.displayLabel() },
                    modifier = Modifier.weight(1f),
                    style = ppmText(14f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (timeLabel.isNotEmpty()) {
                    BasicText(
                        text = timeLabel,
                        style = ppmText(11.5f, FontWeight.Normal, FontKind.MONO, lineHeight = 15f)
                            .copy(color = tokens.text3.color),
                        maxLines = 1,
                    )
                }
            }

            BasicText(
                text = row.kind.displayLabel(),
                style = ppmText(12.5f, FontWeight.Medium, lineHeight = 16f).copy(
                    color = if (isUrgentApproval) tokens.error.color else tokens.text2.color,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (row.body.isNotBlank()) {
                val useMono = row.kind == FeedKind.APPROVAL && row.body.startsWith("Bash", ignoreCase = true)
                BasicText(
                    text = row.body,
                    style = ppmText(
                        size = 12.5f,
                        weight = FontWeight.Normal,
                        kind = if (useMono) FontKind.MONO else FontKind.SANS,
                        lineHeight = 16f,
                    ).copy(color = tokens.text3.color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (isUrgentApproval) {
            val openShape = RoundedCornerShape(999.dp)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .height(34.dp)
                    .clip(openShape)
                    .background(tokens.accent.color, openShape)
                    .ppmClickable(
                        shape = openShape,
                        pressedColor = tokens.accentFg.color.copy(alpha = 0.16f),
                        onClickLabel = "Open",
                        onClick = onClick,
                    )
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = "Open",
                    style = ppmText(12.5f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.accentFg.color),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * `NT3 · One row's actions` bottom sheet opened on long-press of a notification row on phone.
 */
@Composable
internal fun NotificationRowActionsSheet(
    row: FeedRow,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onToggleRead: (Boolean) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val timeLabel = remember(row.createdAt) { formatNotificationTime(row.createdAt) }
    val subtitle = buildString {
        append(row.project.ifBlank { "Unknown" })
        append(" · ")
        append(row.kind.displayLabel())
        if (timeLabel.isNotEmpty()) {
            append(" · ")
            append(timeLabel)
        }
    }

    BottomSheet(
        onDismiss = onDismiss,
        radius = 24.dp,
        background = tokens.panel2.color,
    ) {
        // .amenu-h header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .cssBorder(tokens.borderSoft.color, width = 1.dp, sides = CssSides.Bottom)
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NotificationKindTile(row = row, tokens = tokens)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                BasicText(
                    text = row.title.ifBlank { row.kind.displayLabel() },
                    style = ppmText(14f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                BasicText(
                    text = subtitle,
                    style = ppmText(12f, FontWeight.Normal, lineHeight = 16f).copy(color = tokens.text2.color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Open chat / Open schedule / Open project
        ActionMenuRow(
            label = notificationOpenLabel(row),
            icon = ProductIcon.MessageSquare,
            trailingIcon = ProductIcon.ArrowRight,
            tokens = tokens,
            onClick = onOpen,
        )

        // Mark as read / Mark as unread
        if (row.read) {
            ActionMenuRow(
                label = "Mark as unread",
                icon = ProductIcon.Bell,
                tokens = tokens,
                onClick = { onToggleRead(false) },
            )
        } else {
            ActionMenuRow(
                label = "Mark as read",
                icon = ProductIcon.Check,
                tokens = tokens,
                onClick = { onToggleRead(true) },
            )
        }
    }
}

@Composable
private fun ActionMenuRow(
    label: String,
    icon: ProductIcon,
    tokens: ThemeTokens,
    trailingIcon: ProductIcon? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .ppmClickable(
                pressedColor = tokens.surfaceHover.color,
                hoverColor = tokens.surfaceHover.color,
                onClickLabel = label,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PpmIcon(icon, 20.dp, tokens.text2.color)
        BasicText(
            text = label,
            modifier = Modifier.weight(1f),
            style = ppmText(15f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text.color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailingIcon != null) {
            PpmIcon(trailingIcon, 16.dp, tokens.text3.color)
        }
    }
}

/**
 * 40 x 40 dp rounded icon tile (`.ntile`) colored by notification kind.
 */
@Composable
internal fun NotificationKindTile(
    row: FeedRow,
    tokens: ThemeTokens = LocalPpmTokens.current,
    size: Dp = 40.dp,
) {
    val shape = RoundedCornerShape(12.dp)
    val isFailedSchedule = row.kind == FeedKind.SCHEDULE && (
        row.title.contains("fail", ignoreCase = true) ||
            row.body.contains("exit", ignoreCase = true) ||
            row.body.contains("error", ignoreCase = true)
        )
    val (bg, fg, icon) = when (row.kind) {
        FeedKind.APPROVAL -> Triple(
            tokens.error.opacity(0.12).color,
            tokens.error.color,
            ProductIcon.ShieldAlert,
        )
        FeedKind.QUESTION -> Triple(
            tokens.warning.opacity(0.14).color,
            tokens.warning.color,
            ProductIcon.CircleHelp,
        )
        FeedKind.DONE -> Triple(
            tokens.success.opacity(0.12).color,
            tokens.success.color,
            ProductIcon.CheckCircle2,
        )
        FeedKind.SCHEDULE -> if (isFailedSchedule) {
            Triple(
                tokens.error.opacity(0.12).color,
                tokens.error.color,
                ProductIcon.CalendarX2,
            )
        } else {
            Triple(
                tokens.accent.opacity(0.12).color,
                tokens.accent.color,
                ProductIcon.CalendarClock,
            )
        }
        FeedKind.OTHER -> Triple(
            tokens.accent.opacity(0.12).color,
            tokens.accent.color,
            ProductIcon.Bell,
        )
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(bg, shape),
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(icon, 20.dp, fg)
    }
}

/**
 * Opens a notification row's session, else the schedule it names in the Schedules window, else
 * switches to its project.
 */
internal fun openNotificationTarget(model: AppModel, row: FeedRow): Boolean {
    when (val target = notificationTarget(row)) {
        is NotificationTarget.Chat -> return model.openUnreadSession(target.sessionId, row.project)
        is NotificationTarget.Schedule -> {
            model.schedules.open(select = target.id)
            return true
        }
        NotificationTarget.Project -> Unit
    }
    val project = model.projects.firstOrNull { it.name == row.project } ?: return false
    model.scope.launch { model.openProject(project.name) }
    return true
}
