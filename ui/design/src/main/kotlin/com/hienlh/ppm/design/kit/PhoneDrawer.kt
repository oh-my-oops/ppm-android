package com.hienlh.ppm.design.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.LucideIcon
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.vector
import kotlin.math.roundToInt

/** Summary of the active project and server shown in the top card of [PhoneDrawer]. */
data class DrawerProjectInfo(
    val name: String,
    val subline: String,
    val avatarLetter: String = name.take(1).uppercase().ifEmpty { "P" },
    val avatarColor: Color? = null,
    val serverConnected: Boolean = true,
)

/** One workspace navigation row in [PhoneDrawer] (Chat, Files, Source Control, Terminal, Database). */
data class DrawerNavItem(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val selected: Boolean = false,
    val countBadge: String? = null,
    val badgeAlert: Boolean = false,
    val dotColor: Color? = null,
    val onClick: () -> Unit,
) {
    constructor(
        id: String,
        label: String,
        icon: ProductIcon,
        selected: Boolean = false,
        countBadge: String? = null,
        badgeAlert: Boolean = false,
        dotColor: Color? = null,
        onClick: () -> Unit,
    ) : this(id, label, icon.vector, selected, countBadge, badgeAlert, dotColor, onClick)

    constructor(
        id: String,
        label: String,
        icon: LucideIcon,
        selected: Boolean = false,
        countBadge: String? = null,
        badgeAlert: Boolean = false,
        dotColor: Color? = null,
        onClick: () -> Unit,
    ) : this(id, label, icon.vector, selected, countBadge, badgeAlert, dotColor, onClick)
}

/** One compact 2x2 tool tile in [PhoneDrawer] (Monitor, Logs, Ports, Cloud & Share). */
data class DrawerToolTileItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val dotColor: Color? = null,
    val subtitleColor: Color? = null,
    val onClick: () -> Unit,
) {
    constructor(
        id: String,
        title: String,
        subtitle: String,
        icon: ProductIcon,
        dotColor: Color? = null,
        subtitleColor: Color? = null,
        onClick: () -> Unit,
    ) : this(id, title, subtitle, icon.vector, dotColor, subtitleColor, onClick)

    constructor(
        id: String,
        title: String,
        subtitle: String,
        icon: LucideIcon,
        dotColor: Color? = null,
        subtitleColor: Color? = null,
        onClick: () -> Unit,
    ) : this(id, title, subtitle, icon.vector, dotColor, subtitleColor, onClick)
}

/**
 * Modal phone navigation drawer (Foundation Section 1.2, Requirement 2.2).
 *
 * When [open] is true, draws a 52% black scrim over the viewport and the 320 dp left drawer sheet
 * with swipe-left-to-dismiss.
 */
@Composable
fun PhoneDrawer(
    open: Boolean,
    project: DrawerProjectInfo?,
    workspaceItems: List<DrawerNavItem>,
    toolTiles: List<DrawerToolTileItem>,
    onProjectClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    unreadNotifications: Int = 0,
    notificationsSelected: Boolean = false,
    settingsSelected: Boolean = false,
    settingsMeta: String? = null,
    systemInsets: Boolean = true,
) {
    if (!open) return
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val maxDrawerWidth = (maxWidth - 40.dp).coerceAtLeast(240.dp).coerceAtMost(KitSpec.DrawerWidth)
        // Scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0f, 0f, 0f, 0.52f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "Close navigation drawer",
                    onClick = onDismiss,
                ),
        )
        // Drawer panel
        PhoneDrawerContent(
            project = project,
            workspaceItems = workspaceItems,
            toolTiles = toolTiles,
            onProjectClick = onProjectClick,
            onNotificationsClick = onNotificationsClick,
            onSettingsClick = onSettingsClick,
            unreadNotifications = unreadNotifications,
            notificationsSelected = notificationsSelected,
            settingsSelected = settingsSelected,
            settingsMeta = settingsMeta,
            systemInsets = systemInsets,
            modifier = Modifier
                .width(maxDrawerWidth)
                .fillMaxHeight()
                .offset { IntOffset(dragOffsetPx.roundToInt().coerceAtMost(0), 0) }
                .pointerInput(onDismiss) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (dragOffsetPx < -64.dp.toPx()) onDismiss()
                            dragOffsetPx = 0f
                        },
                        onDragCancel = { dragOffsetPx = 0f },
                        onHorizontalDrag = { _, dragAmount ->
                            dragOffsetPx = (dragOffsetPx + dragAmount).coerceAtMost(0f)
                        },
                    )
                },
        )
    }
}

/**
 * Standalone content of the phone navigation drawer (320 dp wide, 24 dp right corners).
 */
@Composable
fun PhoneDrawerContent(
    project: DrawerProjectInfo?,
    workspaceItems: List<DrawerNavItem>,
    toolTiles: List<DrawerToolTileItem>,
    onProjectClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    unreadNotifications: Int = 0,
    notificationsSelected: Boolean = false,
    settingsSelected: Boolean = false,
    settingsMeta: String? = null,
    systemInsets: Boolean = false,
) {
    val tokens = LocalPpmTokens.current
    val drawerShape = RoundedCornerShape(
        topStart = 0.dp,
        bottomStart = 0.dp,
        topEnd = KitSpec.DrawerCornerRadius,
        bottomEnd = KitSpec.DrawerCornerRadius,
    )

    Column(
        modifier = modifier
            .widthIn(max = KitSpec.DrawerWidth)
            .ppmShadow(tokens.shadowFloat, drawerShape)
            .clip(drawerShape)
            .background(tokens.panel2.color, drawerShape)
            .cssBorder(tokens.border.color, drawerShape, width = 1.dp)
            .then(if (systemInsets) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier)
            .then(if (systemInsets) Modifier.windowInsetsPadding(WindowInsets.navigationBars) else Modifier),
    ) {
        // Project switcher card (64 dp min height, 16 dp radius)
        val projShape = RoundedCornerShape(KitSpec.DrawerProjectCardRadius)
        Row(
            modifier = Modifier
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 6.dp)
                .fillMaxWidth()
                .heightIn(min = KitSpec.DrawerProjectCardHeight)
                .clip(projShape)
                .background(tokens.panel.color, projShape)
                .cssBorder(tokens.border.color, projShape, width = 1.dp)
                .ppmClickable(
                    shape = projShape,
                    hoverColor = tokens.surfaceHover.color,
                    onClickLabel = "Switch project or server",
                    onClick = onProjectClick,
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val avatarShape = RoundedCornerShape(10.dp)
            val avatarTint = project?.avatarColor ?: tokens.accent.color
            val avatarBg = project?.avatarColor?.copy(alpha = 0.16f) ?: tokens.accentWash.color
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(avatarBg, avatarShape),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = project?.avatarLetter ?: "P",
                    style = ppmText(15f, FontWeight.Bold).copy(color = avatarTint),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                BasicText(
                    text = project?.name ?: "Select a project",
                    style = ppmText(15f, FontWeight.SemiBold, lineHeight = 19f).copy(color = tokens.text.color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (project != null) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(
                                    if (project.serverConnected) tokens.success.color else tokens.error.color,
                                    CircleShape,
                                ),
                        )
                    }
                    BasicText(
                        text = project?.subline ?: "No server connected",
                        style = ppmText(12f, FontWeight.Normal, FontKind.MONO, lineHeight = 15f).copy(color = tokens.text2.color),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            PpmIcon(
                icon = ProductIcon.ChevronsUpDown,
                size = 18.dp,
                tint = tokens.text3.color,
            )
        }

        // Scrollable workspace + tools body
        Column(
            modifier = Modifier
                .weight(1f, fill = true)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            DrawerSectionHeader("WORKSPACE")
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (item in workspaceItems) {
                    DrawerPillRow(
                        label = item.label,
                        icon = item.icon,
                        selected = item.selected,
                        countBadge = item.countBadge,
                        badgeAlert = item.badgeAlert,
                        dotColor = item.dotColor,
                        onClick = item.onClick,
                    )
                }
            }

            if (toolTiles.isNotEmpty()) {
                DrawerSectionHeader("TOOLS")
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (pair in toolTiles.chunked(2)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            for (tile in pair) {
                                DrawerToolTile(
                                    tile = tile,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (pair.size == 1) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        // Footer: Notifications + Settings
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .cssBorder(tokens.borderSoft.color, width = 1.dp, sides = CssSides.Top)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            DrawerPillRow(
                label = "Notifications",
                icon = ProductIcon.Bell.vector,
                selected = notificationsSelected,
                countBadge = if (unreadNotifications > 0) unreadNotifications.toString() else null,
                badgeAlert = unreadNotifications > 0,
                onClick = onNotificationsClick,
            )
            DrawerPillRow(
                label = "Settings",
                icon = ProductIcon.Settings.vector,
                selected = settingsSelected,
                trailingMeta = settingsMeta,
                onClick = onSettingsClick,
            )
        }
    }
}

@Composable
private fun DrawerSectionHeader(title: String) {
    val tokens = LocalPpmTokens.current
    BasicText(
        text = title.uppercase(),
        style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f).copy(
            color = tokens.text3.color,
            letterSpacing = androidx.compose.ui.unit.TextUnit(0.77f, androidx.compose.ui.unit.TextUnitType.Sp),
        ),
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
    )
}

/** 52 dp pill row used in [PhoneDrawer] for workspace items and footer items. */
@Composable
fun DrawerPillRow(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    countBadge: String? = null,
    badgeAlert: Boolean = false,
    dotColor: Color? = null,
    trailingMeta: String? = null,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(KitSpec.DrawerRowRadius)
    val bgColor = if (selected) tokens.accentWash.color else Color.Transparent
    val fgColor = if (selected) tokens.accent.color else tokens.text.color
    val iconColor = if (selected) tokens.accent.color else tokens.text2.color

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(KitSpec.DrawerRowHeight)
            .clip(shape)
            .background(bgColor, shape)
            .then(
                if (selected) {
                    Modifier.cssBorder(tokens.accentWashBorder.color, shape, width = 1.dp)
                } else {
                    Modifier
                },
            )
            .ppmClickable(
                shape = shape,
                hoverColor = tokens.surfaceHover.color,
                onClickLabel = label,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PpmIcon(
            vector = icon,
            size = 20.dp,
            tint = iconColor,
        )
        BasicText(
            text = label,
            style = ppmText(
                size = 15f,
                weight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                lineHeight = 19f,
            ).copy(color = fgColor),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (dotColor != null) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(dotColor, CircleShape),
            )
        }
        if (!countBadge.isNullOrEmpty()) {
            val badgeShape = RoundedCornerShape(999.dp)
            val badgeBg = when {
                badgeAlert -> tokens.error.color
                selected -> tokens.accent.opacity(0.18).color
                else -> tokens.panel.color
            }
            val badgeFg = when {
                badgeAlert -> Color.White
                selected -> tokens.accent.color
                else -> tokens.text2.color
            }
            Box(
                modifier = Modifier
                    .height(KitSpec.DrawerBadgeHeight)
                    .minTouchTarget(minWidth = 20.dp, minHeight = KitSpec.DrawerBadgeHeight)
                    .background(badgeBg, badgeShape)
                    .then(
                        if (!badgeAlert && !selected) {
                            Modifier.cssBorder(tokens.border.color, badgeShape, width = 1.dp)
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = countBadge,
                    style = ppmText(11.5f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 14f).copy(color = badgeFg),
                )
            }
        }
        if (!trailingMeta.isNullOrEmpty()) {
            BasicText(
                text = trailingMeta,
                style = ppmText(11.5f, FontWeight.Normal, FontKind.MONO, lineHeight = 14f).copy(color = tokens.text3.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 72 dp compact tool tile used in [PhoneDrawer]'s 2x2 Tools grid. */
@Composable
fun DrawerToolTile(
    tile: DrawerToolTileItem,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(KitSpec.DrawerToolTileRadius)

    Column(
        modifier = modifier
            .height(KitSpec.DrawerToolTileHeight)
            .clip(shape)
            .background(tokens.panel.color, shape)
            .cssBorder(tokens.border.color, shape, width = 1.dp)
            .ppmClickable(
                shape = shape,
                hoverColor = tokens.surfaceHover.color,
                onClickLabel = tile.title,
                onClick = tile.onClick,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PpmIcon(
                vector = tile.icon,
                size = 18.dp,
                tint = tokens.text2.color,
            )
            if (tile.dotColor != null) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(tile.dotColor, CircleShape),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            BasicText(
                text = tile.title,
                style = ppmText(13f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BasicText(
                text = tile.subtitle,
                style = ppmText(11.5f, FontWeight.Normal, FontKind.MONO, lineHeight = 14f).copy(
                    color = tile.subtitleColor ?: tokens.text2.color,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
