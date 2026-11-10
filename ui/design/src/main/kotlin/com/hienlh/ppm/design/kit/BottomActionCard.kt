package com.hienlh.ppm.design.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.LucideIcon
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.vector

/**
 * Composer-shaped bottom action card (Foundation Sections 1.4 & 3.1, Requirement 2.4).
 *
 * Sits in the thumb zone above the system navigation bar and rises above the soft keyboard via
 * `WindowInsets.ime.union(WindowInsets.navigationBars)`. Used for Chat composer, Source Control
 * commit box, Database SQL runner, Files filter, and Schedules Run-now bar.
 */
@Composable
fun BottomActionCard(
    modifier: Modifier = Modifier,
    applyWindowInsets: Boolean = true,
    topContent: (@Composable ColumnScope.() -> Unit)? = null,
    chipsContent: (@Composable RowScope.() -> Unit)? = null,
    primaryAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val radius = (tokens.rad + 4.0).dp
    val cardShape = RoundedCornerShape(radius)
    val backdropBrush = Brush.verticalGradient(
        0f to Color.Transparent,
        0.35f to tokens.panel2.opacity(0.92).color,
        1f to tokens.panel2.color,
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(backdropBrush)
            .then(
                if (applyWindowInsets) {
                    Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                } else {
                    Modifier
                },
            )
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .ppmShadow(tokens.shadowFloat, cardShape)
                .clip(cardShape)
                .background(tokens.panel.color, cardShape)
                .cssBorder(tokens.border.color, cardShape, width = 1.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (topContent != null) {
                topContent()
            }

            content()

            if (chipsContent != null || primaryAction != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (chipsContent != null) {
                            chipsContent()
                        }
                    }
                    if (primaryAction != null) {
                        primaryAction()
                    }
                }
            }
        }
    }
}

/**
 * 48 dp primary pill (or round when [label] is null) action button inside [BottomActionCard]
 * (Send, Stop, Commit, Run, etc.).
 */
@Composable
fun ActionPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    icon: ImageVector? = null,
    contentDescriptionLabel: String = label ?: "Run action",
    enabled: Boolean = true,
    destructive: Boolean = false,
    spinningIcon: Boolean = false,
) {
    val tokens = LocalPpmTokens.current
    val isIconOnly = label.isNullOrEmpty()
    val shape = if (isIconOnly) CircleShape else RoundedCornerShape(KitSpec.ActionPrimaryPillRadius)
    val bgColor = when {
        !enabled -> tokens.panel2.color
        destructive -> tokens.error.color
        else -> tokens.accent.color
    }
    val fgColor = when {
        !enabled -> tokens.text3.color
        destructive -> Color.White
        else -> tokens.accentFg.color
    }

    Row(
        modifier = modifier
            .then(
                if (isIconOnly) {
                    Modifier.size(KitSpec.ActionPrimaryHeight)
                } else {
                    Modifier
                        .height(KitSpec.ActionPrimaryHeight)
                        .minTouchTarget(minWidth = KitSpec.MinTouchTarget, minHeight = KitSpec.ActionPrimaryHeight)
                },
            )
            .clip(shape)
            .background(bgColor, shape)
            .then(
                if (!enabled) {
                    Modifier.cssBorder(tokens.border.color, shape, width = 1.dp)
                } else {
                    Modifier
                },
            )
            .ppmClickable(
                enabled = enabled,
                shape = shape,
                pressedColor = fgColor.copy(alpha = 0.16f),
                hoverColor = fgColor.copy(alpha = 0.10f),
                onClickLabel = contentDescriptionLabel,
                onClick = onClick,
            )
            .then(if (isIconOnly) Modifier else Modifier.padding(horizontal = 18.dp))
            .semantics { contentDescription = contentDescriptionLabel },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) {
            PpmIcon(
                vector = icon,
                size = 18.dp,
                tint = fgColor,
                spinning = spinningIcon,
            )
        }
        if (!isIconOnly) {
            BasicText(
                text = label,
                style = ppmText(14f, FontWeight.SemiBold, lineHeight = 18f).copy(color = fgColor),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun ActionPrimaryButton(
    icon: ProductIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    contentDescriptionLabel: String = label ?: icon.name,
    enabled: Boolean = true,
    destructive: Boolean = false,
    spinningIcon: Boolean = false,
) = ActionPrimaryButton(
    onClick = onClick,
    modifier = modifier,
    label = label,
    icon = icon.vector,
    contentDescriptionLabel = contentDescriptionLabel,
    enabled = enabled,
    destructive = destructive,
    spinningIcon = spinningIcon,
)

@Composable
fun ActionPrimaryButton(
    icon: LucideIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    contentDescriptionLabel: String = label ?: icon.name,
    enabled: Boolean = true,
    destructive: Boolean = false,
    spinningIcon: Boolean = false,
) = ActionPrimaryButton(
    onClick = onClick,
    modifier = modifier,
    label = label,
    icon = icon.vector,
    contentDescriptionLabel = contentDescriptionLabel,
    enabled = enabled,
    destructive = destructive,
    spinningIcon = spinningIcon,
)

/**
 * Secondary icon button inside [BottomActionCard] (e.g. `+` attach, voice mic): 36 dp visual circle
 * centered in a 48 dp touch target.
 */
@Composable
fun ActionIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
) {
    val tokens = LocalPpmTokens.current
    val pillShape = CircleShape
    val bg = if (active) tokens.accentWash.color else tokens.panel2.color
    val border = if (active) tokens.accentWashBorder.color else tokens.border.color
    val fg = when {
        !enabled -> tokens.text3.opacity(0.5).color
        active -> tokens.accent.color
        else -> tokens.text2.color
    }

    Box(
        modifier = modifier
            .size(KitSpec.MinTouchTarget)
            .clip(pillShape)
            .ppmClickable(
                enabled = enabled,
                shape = pillShape,
                hoverColor = tokens.surfaceHover.color,
                onClickLabel = label,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(KitSpec.ChipVisualHeight)
                .background(bg, pillShape)
                .cssBorder(border, pillShape, width = 1.dp),
            contentAlignment = Alignment.Center,
        ) {
            PpmIcon(
                vector = icon,
                size = 16.dp,
                tint = fg,
            )
        }
    }
}
