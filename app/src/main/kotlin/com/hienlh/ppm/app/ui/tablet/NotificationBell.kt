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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.ContextMenuPopup
import com.hienlh.ppm.app.ui.MenuIcon
import com.hienlh.ppm.app.ui.MenuItem
import com.hienlh.ppm.app.ui.MenuSeparator
import com.hienlh.ppm.app.ui.PlacedPopup
import com.hienlh.ppm.app.ui.ShadowLg
import com.hienlh.ppm.app.ui.phone.notifications.NotificationKindTile
import com.hienlh.ppm.app.ui.phone.notifications.NotificationsEmptyState
import com.hienlh.ppm.app.ui.phone.notifications.openNotificationTarget
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
import com.hienlh.ppm.design.chromeScrollbar
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.kit.HoverTooltip
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.state.NotificationFeed
import com.hienlh.ppm.state.displayLabel
import com.hienlh.ppm.state.formatNotificationTime
import com.hienlh.ppm.state.formatUnreadSummary
import com.hienlh.ppm.state.groupFeedRowsByProject

/**
 * The rail's bell (`Tablet 2 · Section 8` & `Phone 2 · TN1`): `size-8 rounded-md`, the count of
 * unread notifications in a red badge over its corner, and a 420 dp anchored card beside it
 * with read/unread state, hover `×` to mark read, and long-press/right-click context menu.
 */
@Composable
internal fun NotificationBell(model: AppModel) {
    val tokens = LocalPpmTokens.current
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
    val unreadCount = remember(rows, feedSupported, unreadSessions) {
        if (feedSupported && rows.isNotEmpty()) {
            rows.count { !it.read }
        } else {
            rows.count { !it.read }.coerceAtLeast(unreadSessions.size)
        }
    }

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
                    .padding(horizontal = 4.dp),
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
                BellPopover(
                    model = model,
                    rows = rows,
                    unreadCount = unreadCount,
                    tokens = tokens,
                    close = { open = false },
                )
            }
        }
    }
}

/**
 * 420 dp anchored notification card (`Tablet 2 · Section 8` & `Phone 2 · TN1`):
 * - 52 dp header with `"Notifications"`, `"N unread sessions"` / `"No unread"`, `"Clear all"` with
 *   [ProductIcon.BellOff], and Close `×`.
 * - Grouped notification rows with 40 dp kind tile, unread dot, hover `×` button to mark read, and
 *   right-click / long-press context menu.
 * - Footer with `"Notification settings"` button.
 */
@Composable
private fun BellPopover(
    model: AppModel,
    rows: List<FeedRow>,
    unreadCount: Int,
    tokens: ThemeTokens,
    close: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val scroll = rememberScrollState()
    val events = model.events
    val feed = events?.feed
    val groups = remember(rows) { groupFeedRowsByProject(rows) }
    val projectNames = remember(model.projects) { model.projects.map { it.name } }

    Column(
        Modifier
            .width(420.dp)
            .heightIn(max = 520.dp)
            .then(ShadowLg.fold(Modifier as Modifier) { m, s -> m.ppmShadow(s, shape) })
            .clip(shape)
            .background(tokens.panel2.over(tokens.bgSolid).color)
            .cssBorder(tokens.border.color, shape),
    ) {
        // 52 dp card header (.popd-h)
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .cssBorder(tokens.borderSoft.color, sides = CssSides.Bottom)
                .padding(start = 16.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
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
                        ) {
                            if (feed != null) {
                                feed.readAll()
                            } else {
                                events?.clearAll()
                            }
                        }
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

        // Scrollable body
        if (rows.isEmpty()) {
            NotificationsEmptyState(tokens = tokens, compact = true)
        } else {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .chromeScrollbar(scroll, vertical = true, tokens.border.color, border = 1.dp)
                    .verticalScroll(scroll),
            ) {
                for (group in groups) {
                    val index = projectNames.indexOf(group.project)
                    val known = model.projects.getOrNull(index)
                    val fill = if (known != null) {
                        projectBackground(known.color, index)
                    } else {
                        SolidColor(tokens.text3.color)
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(fill))
                        BasicText(
                            group.project.uppercase(),
                            style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f)
                                .copy(color = tokens.text3.color, letterSpacing = 0.07.em),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    for (row in group.rows) {
                        TabletNotificationRow(
                            model = model,
                            row = row,
                            tokens = tokens,
                            closePopover = close,
                        )
                    }
                }
            }
        }

        // Footer with Notification settings button
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
                    ) {
                        close()
                        model.openSettings("notifications")
                    }
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

@Composable
private fun TabletNotificationRow(
    model: AppModel,
    row: FeedRow,
    tokens: ThemeTokens,
    closePopover: () -> Unit,
) {
    val events = model.events
    val feed = events?.feed
    val rowHover = rememberHover()
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    val isUrgentApproval = row.kind == FeedKind.APPROVAL && !row.read
    val dotColor = if (row.kind == FeedKind.APPROVAL) tokens.error.color else tokens.accent.color
    val timeLabel = remember(row.createdAt) { formatNotificationTime(row.createdAt) }
    val subtitleText = remember(row.kind, row.body) { tabletNotificationKindLine(row) }

    fun openRow() {
        if (!row.read) {
            feed?.setRead(row.id, true)
        }
        if (openNotificationTarget(model, row)) {
            closePopover()
        }
    }

    fun toggleRead(wantRead: Boolean) {
        if (feed != null) {
            feed.setRead(row.id, wantRead)
        } else if (row.sessionId != null) {
            if (wantRead) {
                events?.markRead(row.sessionId!!)
            } else {
                events?.markUnread(row.sessionId!!, row.title)
            }
        }
    }

    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .alpha(if (row.read) 0.58f else 1f)
                .cssBorder(tokens.borderSoft.color, sides = CssSides.Bottom)
                .pressable(
                    pressedColor = tokens.surfaceHover.color,
                    hoverColor = tokens.surfaceHover.color,
                    source = rowHover.source,
                    onLongClick = { offset -> menuAt = offset },
                    onClick = ::openRow,
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 6 dp unread dot
            Box(
                Modifier
                    .size(6.dp)
                    .alpha(if (row.read) 0f else 1f)
                    .clip(CircleShape)
                    .background(dotColor),
            )

            NotificationKindTile(row = row, tokens = tokens)

            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BasicText(
                        row.title.ifBlank { row.kind.displayLabel() },
                        Modifier.weight(1f),
                        style = ppmText(13.5f, FontWeight.SemiBold, lineHeight = 17.5f).copy(color = tokens.text.color),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (timeLabel.isNotEmpty()) {
                        BasicText(
                            timeLabel,
                            style = ppmText(11.5f, FontWeight.Normal, FontKind.MONO, lineHeight = 14.5f)
                                .copy(color = tokens.text3.color),
                            maxLines = 1,
                        )
                    }
                }
                BasicText(
                    subtitleText,
                    style = ppmText(12f, FontWeight.Medium, lineHeight = 15.5f).copy(
                        color = if (isUrgentApproval) tokens.error.color else tokens.text2.color,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (isUrgentApproval) {
                val openShape = RoundedCornerShape(999.dp)
                Box(
                    Modifier
                        .height(30.dp)
                        .clip(openShape)
                        .background(tokens.accent.color, openShape)
                        .ppmClickable(
                            pressedColor = tokens.accentFg.color.copy(alpha = 0.16f),
                            shape = openShape,
                            onClickLabel = "Open",
                            onClick = ::openRow,
                        )
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        "Open",
                        style = ppmText(12f, FontWeight.SemiBold, lineHeight = 15f).copy(color = tokens.accentFg.color),
                    )
                }
            }

            // Hover `×` button to mark as read (or toggle read state)
            val xShape = RoundedCornerShape(8.dp)
            val xTooltip = if (row.read) "Mark as unread" else "Mark as read"
            HoverTooltip(label = xTooltip) {
                Box(
                    Modifier
                        .size(28.dp)
                        .alpha(if (rowHover.lit > 0f || !row.read) (0.35f + 0.65f * rowHover.lit) else 0f)
                        .clip(xShape)
                        .ppmClickable(
                            pressedColor = tokens.panel.color,
                            hoverColor = tokens.panel.color,
                            shape = xShape,
                            onClickLabel = xTooltip,
                        ) {
                            toggleRead(!row.read)
                        }
                        .semantics { contentDescription = xTooltip },
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(ProductIcon.X, 14.dp, tokens.text2.color)
                }
            }
        }

        val at = menuAt
        if (at != null) {
            ContextMenuPopup(at = at, onDismiss = { menuAt = null }) {
                MenuItem(
                    label = "Open",
                    onClick = {
                        menuAt = null
                        openRow()
                    },
                    icon = { MenuIcon(ProductIcon.MessageSquare) },
                )
                if (row.read) {
                    MenuItem(
                        label = "Mark as unread",
                        onClick = {
                            menuAt = null
                            toggleRead(false)
                        },
                        icon = { MenuIcon(ProductIcon.Bell) },
                    )
                } else {
                    MenuItem(
                        label = "Mark as read",
                        onClick = {
                            menuAt = null
                            toggleRead(true)
                        },
                        icon = { MenuIcon(ProductIcon.Check) },
                    )
                }
                MenuSeparator()
                MenuItem(
                    label = "Open in window",
                    onClick = {
                        menuAt = null
                        openRow()
                    },
                    icon = { MenuIcon(ProductIcon.AppWindow) },
                )
            }
        }
    }
}

/**
 * Formats the secondary kind/detail line for a row in the 420 dp tablet notification card
 * (`"Approval waiting · Bash"`, `"Schedule · exit 2 · db-backup"`, `"Question waiting"`, `"Turn complete"`).
 */
internal fun tabletNotificationKindLine(row: FeedRow): String {
    val label = row.kind.displayLabel()
    val body = row.body.trim()
    if (body.isEmpty()) return label
    return when (row.kind) {
        FeedKind.APPROVAL -> {
            val toolPrefix = body.substringBefore(":").trim()
            if (toolPrefix.isNotEmpty() && toolPrefix.length <= 24) {
                "$label · $toolPrefix"
            } else {
                "$label · $body"
            }
        }
        FeedKind.SCHEDULE -> "$label · $body"
        else -> label
    }
}
