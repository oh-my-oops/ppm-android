package com.hienlh.ppm.design.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.LucideIcon
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.vector

/** Connection dot state rendered in the [AppBar] subline when present. */
enum class AppBarConnectionState {
    None,
    Connected,
    Reconnecting,
    Disconnected,
}

/** One trailing action button in [AppBar] (at most [KitSpec.APP_BAR_MAX_ACTIONS] = 2 are shown). */
data class AppBarAction(
    val icon: ImageVector,
    val label: String,
    val badgeDotColor: Color? = null,
    val tint: Color? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
) {
    constructor(
        icon: ProductIcon,
        label: String,
        badgeDotColor: Color? = null,
        tint: Color? = null,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ) : this(icon.vector, label, badgeDotColor, tint, enabled, onClick)

    constructor(
        icon: LucideIcon,
        label: String,
        badgeDotColor: Color? = null,
        tint: Color? = null,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ) : this(icon.vector, label, badgeDotColor, tint, enabled, onClick)
}

/**
 * Top app bar for phone screens and pushed pages (Foundation Section 1.1).
 *
 * - Root mode ([onBack] == null): 48 dp ☰ drawer button (with optional 9 dp attention dot),
 *   tappable title + subline that opens the tab switcher ([onTitleClick]), and up to 2 trailing
 *   48 dp action buttons.
 * - Pushed page mode ([onBack] != null): 48 dp Back arrow, static title (+ optional subline), and
 *   up to 2 trailing 48 dp action buttons.
 */
@Composable
fun AppBar(
    title: String,
    modifier: Modifier = Modifier,
    subline: String? = null,
    sublineMono: Boolean = false,
    connectionState: AppBarConnectionState = AppBarConnectionState.None,
    hasUnreadAttention: Boolean = false,
    drawerDotColor: Color? = null,
    onDrawerClick: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    actions: List<AppBarAction> = emptyList(),
    statusBarsPadding: Boolean = false,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    val slotShape = RoundedCornerShape(12.dp)
    val titleShape = RoundedCornerShape(10.dp)
    val dotColor = drawerDotColor ?: if (hasUnreadAttention) tokens.error.color else null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(tokens.panel2.color)
            .then(if (statusBarsPadding) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier)
            .cssBorder(tokens.border.color, width = 1.dp, sides = CssSides.Bottom),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(KitSpec.AppBarHeight)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Leading slot: Back button (pushed page) or ☰ menu button (root)
            if (onBack != null) {
                Box(
                    modifier = Modifier
                        .size(KitSpec.AppBarSlotSize)
                        .clip(slotShape)
                        .ppmClickable(
                            shape = slotShape,
                            hoverColor = tokens.surfaceHover.color,
                            onClickLabel = "Back",
                            onClick = onBack,
                        )
                        .semantics { contentDescription = "Back" },
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(
                        icon = ProductIcon.ArrowLeft,
                        size = KitSpec.AppBarIconSize,
                        tint = tokens.text.color,
                    )
                }
            } else if (onDrawerClick != null) {
                Box(
                    modifier = Modifier
                        .size(KitSpec.AppBarSlotSize)
                        .clip(slotShape)
                        .ppmClickable(
                            shape = slotShape,
                            hoverColor = tokens.surfaceHover.color,
                            onClickLabel = "Open navigation drawer",
                            onClick = onDrawerClick,
                        )
                        .semantics { contentDescription = "Open navigation drawer" },
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(
                        icon = ProductIcon.Menu,
                        size = KitSpec.AppBarIconSize,
                        tint = tokens.text.color,
                    )
                    if (dotColor != null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = (-10).dp, y = 11.dp)
                                .size(KitSpec.AppBarBadgeDotSize)
                                .background(dotColor, CircleShape)
                                .cssBorder(tokens.panel2.color, CircleShape, width = 2.dp),
                        )
                    }
                }
            }

            // Center title area
            val titleModifier = Modifier
                .weight(1f)
                .minTouchTarget(minHeight = KitSpec.MinTouchTarget)
                .clip(titleShape)
                .then(
                    if (onTitleClick != null) {
                        Modifier.ppmClickable(
                            shape = titleShape,
                            hoverColor = tokens.surfaceHover.color,
                            onClickLabel = "Switch open tab",
                            onClick = onTitleClick,
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 8.dp, vertical = 4.dp)

            Column(
                modifier = titleModifier,
                verticalArrangement = Arrangement.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    BasicText(
                        text = title,
                        style = ppmText(15f, FontWeight.SemiBold, lineHeight = 19f).copy(color = tokens.text.color),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (onTitleClick != null) {
                        PpmIcon(
                            icon = ProductIcon.ChevronDown,
                            size = 14.dp,
                            tint = tokens.text3.color,
                        )
                    }
                }
                if (!subline.isNullOrEmpty() || connectionState != AppBarConnectionState.None) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        val connColor = when (connectionState) {
                            AppBarConnectionState.None -> null
                            AppBarConnectionState.Connected -> tokens.success.color
                            AppBarConnectionState.Reconnecting -> tokens.warning.color
                            AppBarConnectionState.Disconnected -> tokens.error.color
                        }
                        if (connColor != null) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(connColor, CircleShape),
                            )
                        }
                        if (!subline.isNullOrEmpty()) {
                            BasicText(
                                text = subline,
                                style = ppmText(
                                    size = 12f,
                                    weight = FontWeight.Normal,
                                    kind = if (sublineMono) FontKind.MONO else FontKind.SANS,
                                    lineHeight = 15f,
                                ).copy(color = tokens.text2.color),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // Trailing action slots (capped at 2 per Requirement 2.1 & Foundation Section 1.1)
            for (action in actions.take(KitSpec.APP_BAR_MAX_ACTIONS)) {
                Box(
                    modifier = Modifier
                        .size(KitSpec.AppBarSlotSize)
                        .clip(slotShape)
                        .ppmClickable(
                            enabled = action.enabled,
                            shape = slotShape,
                            hoverColor = tokens.surfaceHover.color,
                            onClickLabel = action.label,
                            onClick = action.onClick,
                        )
                        .semantics { contentDescription = action.label },
                    contentAlignment = Alignment.Center,
                ) {
                    val iconTint = when {
                        !action.enabled -> tokens.text3.opacity(0.45).color
                        action.tint != null -> action.tint
                        else -> tokens.text.color
                    }
                    PpmIcon(
                        vector = action.icon,
                        size = KitSpec.AppBarIconSize,
                        tint = iconTint,
                    )
                    if (action.badgeDotColor != null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = (-12).dp, y = 12.dp)
                                .size(KitSpec.AppBarSlotDotSize)
                                .background(action.badgeDotColor, CircleShape)
                                .cssBorder(tokens.panel2.color, CircleShape, width = 2.dp),
                        )
                    }
                }
            }

            if (trailingContent != null) {
                trailingContent()
            }
        }
    }
}
