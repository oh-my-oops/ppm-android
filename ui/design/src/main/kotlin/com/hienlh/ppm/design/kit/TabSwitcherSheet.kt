package com.hienlh.ppm.design.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.LucideIcon
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.vector
import kotlin.math.abs
import kotlin.math.roundToInt

/** Status dot kind displayed on a [TabCardItem]'s 36 dp icon box. */
enum class TabDotKind {
    None,
    Running,
    Attention,
    Dirty,
    Connected,
}

/** One open tab entry in [TabSwitcherSheet]. */
data class TabCardItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val active: Boolean = false,
    val dotKind: TabDotKind = TabDotKind.None,
    val dotColor: Color? = null,
    /** Pinned: a pin stands where the close button would be, tapping it unpins, and a swipe does not close it. */
    val pinned: Boolean = false,
) {
    constructor(
        id: String,
        title: String,
        subtitle: String,
        icon: ProductIcon,
        active: Boolean = false,
        dotKind: TabDotKind = TabDotKind.None,
        dotColor: Color? = null,
        pinned: Boolean = false,
    ) : this(id, title, subtitle, icon.vector, active, dotKind, dotColor, pinned)

    constructor(
        id: String,
        title: String,
        subtitle: String,
        icon: LucideIcon,
        active: Boolean = false,
        dotKind: TabDotKind = TabDotKind.None,
        dotColor: Color? = null,
        pinned: Boolean = false,
    ) : this(id, title, subtitle, icon.vector, active, dotKind, dotColor, pinned)
}

/** One "New tab" tile at the bottom of [TabSwitcherSheet] (Chat, Terminal, Files, Database). */
data class NewTabOption(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
) {
    constructor(
        id: String,
        label: String,
        icon: ProductIcon,
        onClick: () -> Unit,
    ) : this(id, label, icon.vector, onClick)

    constructor(
        id: String,
        label: String,
        icon: LucideIcon,
        onClick: () -> Unit,
    ) : this(id, label, icon.vector, onClick)
}

/**
 * Tab switcher bottom sheet (Foundation Section 1.3, Requirement 2.3).
 *
 * Displays all open workspace tabs as 64 dp cards with status dots, swipe-or-button close, and a
 * 4-column "New tab" grid at the foot.
 */
@Composable
fun TabSwitcherSheet(
    tabs: List<TabCardItem>,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    newTabOptions: List<NewTabOption> = emptyList(),
    title: String = "Open tabs \u00B7 ${tabs.size}",
    onUnpinTab: (String) -> Unit = {},
    onLongPressTab: ((String) -> Unit)? = null,
) {
    Sheet(
        title = title,
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        TabSwitcherContent(
            tabs = tabs,
            onSelectTab = onSelectTab,
            onCloseTab = onCloseTab,
            newTabOptions = newTabOptions,
            onUnpinTab = onUnpinTab,
            onLongPressTab = onLongPressTab,
        )
    }
}

/**
 * Body content of [TabSwitcherSheet], usable inside a [Sheet] or standalone preview.
 */
@Composable
fun TabSwitcherContent(
    tabs: List<TabCardItem>,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    modifier: Modifier = Modifier,
    newTabOptions: List<NewTabOption> = emptyList(),
    onUnpinTab: (String) -> Unit = {},
    onLongPressTab: ((String) -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (tab in tabs) {
                TabCard(
                    item = tab,
                    onClick = { onSelectTab(tab.id) },
                    onClose = { onCloseTab(tab.id) },
                    onUnpin = { onUnpinTab(tab.id) },
                    onLongClick = onLongPressTab?.let { press -> { press(tab.id) } },
                )
            }
        }

        if (newTabOptions.isNotEmpty()) {
            BasicText(
                text = "NEW TAB",
                style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f).copy(
                    color = tokens.text3.color,
                    letterSpacing = androidx.compose.ui.unit.TextUnit(0.77f, androidx.compose.ui.unit.TextUnitType.Sp),
                ),
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (row in newTabOptions.chunked(4)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (opt in row) {
                            NewTabTile(
                                option = opt,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(4 - row.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 64 dp open-tab card with 36 dp icon box, state dot, swipe-to-close, and 48 dp close button.
 */
@Composable
fun TabCard(
    item: TabCardItem,
    onClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onUnpin: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(KitSpec.TabCardRadius)
    val bgColor = if (item.active) tokens.accentWash.color else tokens.panel.color
    val borderColor = if (item.active) tokens.accentWashBorder.color else tokens.border.color
    val iconBoxShape = RoundedCornerShape(KitSpec.TabCardIconBoxRadius)
    val iconBoxBg = if (item.active) tokens.accent.opacity(0.18).color else tokens.panel2.color
    val iconTint = if (item.active) tokens.accent.color else tokens.text2.color
    val closeShape = RoundedCornerShape(10.dp)

    val resolvedDotColor = item.dotColor ?: when (item.dotKind) {
        TabDotKind.None -> null
        TabDotKind.Running, TabDotKind.Connected -> tokens.success.color
        TabDotKind.Attention -> tokens.error.color
        TabDotKind.Dirty -> tokens.warning.color
    }

    var dragOffsetPx by remember(item.id) { mutableFloatStateOf(0f) }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val hovered by source.collectIsHoveredAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = KitSpec.TabCardMinHeight)
            .offset { IntOffset(dragOffsetPx.roundToInt(), 0) }
            // A pinned tab is closed only by its menu's Close, never by a swipe.
            .pointerInput(item.id, onClose, item.pinned) {
                if (item.pinned) return@pointerInput
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (abs(dragOffsetPx) > 96.dp.toPx()) {
                            onClose()
                        }
                        dragOffsetPx = 0f
                    },
                    onDragCancel = { dragOffsetPx = 0f },
                    onHorizontalDrag = { _, dragAmount ->
                        dragOffsetPx += dragAmount
                    },
                )
            }
            .clip(shape)
            .background(bgColor, shape)
            .cssBorder(borderColor, shape, width = 1.dp)
            .then(
                if (onLongClick == null) {
                    Modifier.ppmClickable(
                        shape = shape,
                        hoverColor = tokens.surfaceHover.color,
                        onClickLabel = item.title,
                        onClick = onClick,
                    )
                } else {
                    // A long press opens the tab's menu (`onTabLongPress` in mobile-nav.tsx).
                    Modifier
                        .background(if (pressed || hovered) tokens.surfaceHover.color else Color.Transparent, shape)
                        .hoverable(source)
                        .combinedClickable(
                            interactionSource = source,
                            indication = null,
                            role = Role.Button,
                            onClickLabel = item.title,
                            onLongClick = onLongClick,
                            onClick = onClick,
                        )
                }
            )
            .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 36 dp icon box + state dot
        Box(modifier = Modifier.size(KitSpec.TabCardIconBoxSize)) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(iconBoxBg, iconBoxShape),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(
                    vector = item.icon,
                    size = 18.dp,
                    tint = iconTint,
                )
            }
            if (resolvedDotColor != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 2.dp, y = 2.dp)
                        .size(9.dp)
                        .background(resolvedDotColor, CircleShape)
                        .cssBorder(tokens.panel2.color, CircleShape, width = 2.dp),
                )
            }
        }

        // Title + subtitle
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            BasicText(
                text = item.title,
                style = ppmText(14.5f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.subtitle.isNotEmpty()) {
                BasicText(
                    text = item.subtitle,
                    style = ppmText(12f, FontWeight.Normal, FontKind.MONO, lineHeight = 15f).copy(color = tokens.text2.color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 48 x 48 dp close button; on a pinned tab the pin stands there, and tapping it unpins (as on desktop).
        val trailingLabel = if (item.pinned) "Unpin ${item.title}" else "Close ${item.title}"
        Box(
            modifier = Modifier
                .size(KitSpec.MinTouchTarget)
                .clip(closeShape)
                .ppmClickable(
                    shape = closeShape,
                    hoverColor = tokens.surfaceHover.color,
                    onClickLabel = trailingLabel,
                    onClick = if (item.pinned) onUnpin else onClose,
                )
                .semantics { contentDescription = trailingLabel },
            contentAlignment = Alignment.Center,
        ) {
            PpmIcon(
                icon = if (item.pinned) ProductIcon.Pin else ProductIcon.X,
                size = 16.dp,
                tint = tokens.text3.color,
            )
        }
    }
}

/** 64 dp tile in the 4-column "New tab" grid at the foot of [TabSwitcherSheet]. */
@Composable
fun NewTabTile(
    option: NewTabOption,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(KitSpec.NewTabTileRadius)

    Column(
        modifier = modifier
            .height(KitSpec.NewTabTileHeight)
            .clip(shape)
            .background(tokens.panel.color, shape)
            .cssBorder(tokens.border.color, shape, width = 1.dp)
            .ppmClickable(
                shape = shape,
                hoverColor = tokens.surfaceHover.color,
                onClickLabel = "New ${option.label} tab",
                onClick = option.onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
    ) {
        PpmIcon(
            vector = option.icon,
            size = 20.dp,
            tint = tokens.accent.color,
        )
        BasicText(
            text = option.label,
            style = ppmText(12f, FontWeight.Medium, lineHeight = 15f).copy(color = tokens.text.color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
