package com.hienlh.ppm.design.kit

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
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

/**
 * One contextual action on a [ListRow] — reachable by long-press sheet, horizontal swipe, visible
 * `...` button, or mouse right-click at the pointer (Requirements 1.4, 16.1, 16.2).
 */
data class RowAction(
    val id: String,
    val label: String,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
    val tint: Color? = null,
    val shortcut: String? = null,
    val onClick: () -> Unit,
) {
    constructor(
        id: String,
        label: String,
        icon: ProductIcon,
        destructive: Boolean = false,
        tint: Color? = null,
        shortcut: String? = null,
        onClick: () -> Unit,
    ) : this(id, label, icon.vector, destructive, tint, shortcut, onClick)

    constructor(
        id: String,
        label: String,
        icon: LucideIcon,
        destructive: Boolean = false,
        tint: Color? = null,
        shortcut: String? = null,
        onClick: () -> Unit,
    ) : this(id, label, icon.vector, destructive, tint, shortcut, onClick)
}

/**
 * Uppercase section label above a list group (Foundation Section 3.2 & 7: 11 sp SemiBold, +0.07em,
 * horizontal padding 20 dp).
 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        BasicText(
            text = text.uppercase(),
            style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f).copy(
                color = tokens.text3.color,
                letterSpacing = TextUnit(0.77f, TextUnitType.Sp),
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailing != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                content = trailing,
            )
        }
    }
}

/**
 * Standard 1-line (52 dp phone / 48 dp tablet) or 2-line (64 dp phone / 60 dp tablet) list row
 * (Foundation Sections 2, 3.2, 5 & 7).
 *
 * Every action in [actions] is reachable by:
 * 1. Long-press (opens a bottom/anchored action sheet);
 * 2. Horizontal swipe when [swipeActions] is non-empty (reveals up to 2 trailing 72 dp action tiles);
 * 3. Mouse secondary click / right-click (opens the same [actions] menu anchored at the pointer);
 * 4. Optional visible `...` button ([showMoreButton] = true).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleMono: Boolean = false,
    titleMono: Boolean = false,
    meta: String? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    selected: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    compact: Boolean = false,
    dividerBottom: Boolean = false,
    showChevron: Boolean = false,
    showMoreButton: Boolean = false,
    shape: Shape = RoundedCornerShape(0.dp),
    actions: List<RowAction> = emptyList(),
    swipeActions: List<RowAction> = emptyList(),
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    val density = LocalDensity.current
    val hasTwoLines = !subtitle.isNullOrEmpty()
    val minHeight = when {
        compact && hasTwoLines -> KitSpec.RowTabletTwoLineHeight
        compact -> KitSpec.RowTabletOneLineHeight
        hasTwoLines -> KitSpec.RowPhoneTwoLineHeight
        else -> KitSpec.RowPhoneOneLineHeight
    }

    var sheetOpen by remember { mutableStateOf(false) }
    var pointerMenuOffset by remember { mutableStateOf<IntOffset?>(null) }
    val visibleSwipeActions = remember(swipeActions) { swipeActions.take(2) }
    val maxSwipeRevealPx = with(density) { (KitSpec.RowSwipeActionWidth * visibleSwipeActions.size).toPx() }
    var swipeOffsetPx by remember(visibleSwipeActions.size) { mutableFloatStateOf(0f) }

    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val focused by interactionSource.collectIsFocusedAsState()

    val baseBg = when {
        selected -> tokens.accentWash.color
        pressed && enabled -> tokens.surfaceHover.color
        hovered && enabled -> tokens.surfaceHover.color
        else -> Color.Transparent
    }
    val fgTitle = when {
        !enabled -> tokens.text3.opacity(0.55).color
        destructive -> tokens.error.color
        selected -> tokens.accent.color
        else -> tokens.text.color
    }
    val fgIcon = iconTint ?: when {
        !enabled -> tokens.text3.opacity(0.55).color
        destructive -> tokens.error.color
        selected -> tokens.accent.color
        else -> tokens.text2.color
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(shape),
    ) {
        // Revealed swipe action tiles behind the row on the right
        if (visibleSwipeActions.isNotEmpty() && swipeOffsetPx < -2f) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (swipeAction in visibleSwipeActions) {
                    val actionBg = when {
                        swipeAction.tint != null -> swipeAction.tint
                        swipeAction.destructive -> tokens.error.color
                        else -> tokens.accent.color
                    }
                    Column(
                        modifier = Modifier
                            .width(KitSpec.RowSwipeActionWidth)
                            .fillMaxHeight()
                            .background(actionBg)
                            .ppmClickable(
                                pressedColor = Color.White.copy(alpha = 0.18f),
                                hoverColor = Color.White.copy(alpha = 0.12f),
                                onClickLabel = swipeAction.label,
                                onClick = {
                                    swipeOffsetPx = 0f
                                    swipeAction.onClick()
                                },
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                    ) {
                        if (swipeAction.icon != null) {
                            PpmIcon(
                                vector = swipeAction.icon,
                                size = 18.dp,
                                tint = Color.White,
                            )
                        }
                        BasicText(
                            text = swipeAction.label,
                            style = ppmText(11.5f, FontWeight.SemiBold, lineHeight = 14f).copy(color = Color.White),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        // Foreground row content
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .offset { IntOffset(swipeOffsetPx.roundToInt(), 0) }
                .background( if (swipeOffsetPx < -2f && baseBg == Color.Transparent) tokens.panel.color else baseBg, shape)
                .then(
                    if (selected) {
                        Modifier.cssBorder(tokens.accentWashBorder.color, shape, width = 1.dp)
                    } else if (dividerBottom) {
                        Modifier.cssBorder(tokens.borderSoft.color, width = 1.dp, sides = CssSides.Bottom)
                    } else {
                        Modifier
                    },
                )
                .kitFocusRing(focused = focused, shape = shape)
                .hoverable(interactionSource, enabled = enabled)
                // Mouse secondary click (right-click) opens the context menu at the pointer
                .then(
                    if (actions.isNotEmpty() && enabled) {
                        Modifier.pointerInput(actions) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                        val pos = event.changes.firstOrNull()?.position
                                        if (pos != null) {
                                            event.changes.forEach { it.consume() }
                                            pointerMenuOffset = IntOffset(pos.x.roundToInt(), pos.y.roundToInt())
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Modifier
                    },
                )
                // Horizontal swipe for swipeActions
                .then(
                    if (visibleSwipeActions.isNotEmpty() && enabled) {
                        Modifier.pointerInput(visibleSwipeActions, maxSwipeRevealPx) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    swipeOffsetPx = if (swipeOffsetPx < -maxSwipeRevealPx * 0.4f) {
                                        -maxSwipeRevealPx
                                    } else {
                                        0f
                                    }
                                },
                                onDragCancel = { swipeOffsetPx = 0f },
                                onHorizontalDrag = { _, dragAmount ->
                                    swipeOffsetPx = (swipeOffsetPx + dragAmount).coerceIn(-maxSwipeRevealPx, 0f)
                                },
                            )
                        }
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (onClick != null || actions.isNotEmpty()) {
                        Modifier.combinedClickable(
                            interactionSource = interactionSource,
                            indication = null,
                            enabled = enabled,
                            role = Role.Button,
                            onClickLabel = title,
                            onLongClick = if (actions.isNotEmpty()) {
                                { sheetOpen = true }
                            } else {
                                null
                            },
                            onClick = {
                                if (swipeOffsetPx < -4f) {
                                    swipeOffsetPx = 0f
                                } else {
                                    onClick?.invoke()
                                }
                            },
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(
                    start = if (compact) 12.dp else 16.dp,
                    end = if (showMoreButton) 4.dp else if (compact) 12.dp else 16.dp,
                    top = if (hasTwoLines) 10.dp else 6.dp,
                    bottom = if (hasTwoLines) 10.dp else 6.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (leadingContent != null) {
                leadingContent()
            } else if (icon != null) {
                PpmIcon(
                    vector = icon,
                    size = 20.dp,
                    tint = fgIcon,
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
            ) {
                BasicText(
                    text = title,
                    style = ppmText(
                        size = if (compact) 13.5f else 15f,
                        weight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        kind = if (titleMono) FontKind.MONO else FontKind.SANS,
                        lineHeight = if (compact) 18f else 19f,
                    ).copy(color = fgTitle),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrEmpty()) {
                    BasicText(
                        text = subtitle,
                        style = ppmText(
                            size = if (subtitleMono) 12f else 12.5f,
                            weight = FontWeight.Normal,
                            kind = if (subtitleMono) FontKind.MONO else FontKind.SANS,
                            lineHeight = 16f,
                        ).copy(color = tokens.text2.color),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (!meta.isNullOrEmpty()) {
                BasicText(
                    text = meta,
                    style = ppmText(12f, FontWeight.Normal, FontKind.MONO, lineHeight = 15f)
                        .copy(color = tokens.text3.color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (trailingContent != null) {
                trailingContent()
            }

            if (showChevron) {
                PpmIcon(
                    icon = ProductIcon.ChevronRight,
                    size = 16.dp,
                    tint = tokens.text3.color,
                )
            }

            if (showMoreButton && actions.isNotEmpty()) {
                val moreShape = RoundedCornerShape(10.dp)
                Box(
                    modifier = Modifier
                        .size(KitSpec.MinTouchTarget)
                        .clip(moreShape)
                        .ppmClickable(
                            shape = moreShape,
                            hoverColor = tokens.surfaceHover.color,
                            onClickLabel = "More actions for $title",
                            onClick = { sheetOpen = true },
                        )
                        .semantics { contentDescription = "More actions for $title" },
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(
                        icon = ProductIcon.MoreHorizontal,
                        size = 18.dp,
                        tint = tokens.text3.color,
                    )
                }
            }
        }

        // Mouse right-click popup anchored at pointer position
        val menuOffset = pointerMenuOffset
        if (menuOffset != null && actions.isNotEmpty()) {
            RowContextMenuPopup(
                actions = actions,
                offset = menuOffset,
                onDismiss = { pointerMenuOffset = null },
            )
        }
    }

    // Touch long-press / More button action sheet
    if (sheetOpen && actions.isNotEmpty()) {
        RowActionsSheet(
            title = title,
            subtitle = subtitle,
            actions = actions,
            onDismiss = { sheetOpen = false },
        )
    }
}

/**
 * Context menu popup opened at [offset] when the user right-clicks a [ListRow] with a mouse.
 */
@Composable
fun RowContextMenuPopup(
    actions: List<RowAction>,
    offset: IntOffset,
    onDismiss: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val menuShape = RoundedCornerShape(12.dp)
    val itemShape = RoundedCornerShape(8.dp)

    Popup(
        alignment = Alignment.TopStart,
        offset = offset,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 200.dp, max = 280.dp)
                .ppmShadow(tokens.shadowFloat, menuShape)
                .clip(menuShape)
                .background(tokens.panel2.color, menuShape)
                .cssBorder(tokens.border.color, menuShape, width = 1.dp)
                .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (action in actions) {
                val fg = when {
                    action.tint != null -> action.tint
                    action.destructive -> tokens.error.color
                    else -> tokens.text.color
                }
                val iconFg = when {
                    action.tint != null -> action.tint
                    action.destructive -> tokens.error.color
                    else -> tokens.text2.color
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
                        .clip(itemShape)
                        .ppmClickable(
                            shape = itemShape,
                            hoverColor = tokens.surfaceHover.color,
                            onClickLabel = action.label,
                            onClick = {
                                onDismiss()
                                action.onClick()
                            },
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (action.icon != null) {
                        PpmIcon(
                            vector = action.icon,
                            size = 16.dp,
                            tint = iconFg,
                        )
                    }
                    BasicText(
                        text = action.label,
                        style = ppmText(13f, FontWeight.Medium, lineHeight = 17f).copy(color = fg),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (!action.shortcut.isNullOrEmpty()) {
                        BasicText(
                            text = action.shortcut,
                            style = ppmText(11f, FontWeight.Normal, FontKind.MONO, lineHeight = 14f)
                                .copy(color = tokens.text3.color),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Action sheet opened on long-press or `...` tap on a [ListRow].
 */
@Composable
fun RowActionsSheet(
    title: String,
    actions: List<RowAction>,
    onDismiss: () -> Unit,
    subtitle: String? = null,
) {
    Sheet(
        title = title,
        subtitle = subtitle,
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        ) {
            for (action in actions) {
                ListRow(
                    title = action.label,
                    icon = action.icon,
                    iconTint = action.tint,
                    destructive = action.destructive,
                    meta = action.shortcut,
                    onClick = {
                        onDismiss()
                        action.onClick()
                    },
                )
            }
        }
    }
}
