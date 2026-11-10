package com.hienlh.ppm.design.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.LucideIcon
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ProviderLogo
import com.hienlh.ppm.design.ProviderLogos
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.vector

/**
 * 36 dp pill chip inside a 48 dp vertical touch target (Foundation Sections 3.7 & 7).
 *
 * Used for model/effort/permission pickers, filter pills, and composer toggles (`✦ AI`, etc.).
 */
@Composable
fun Chip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    providerId: String? = null,
    secondaryLabel: String? = null,
    selected: Boolean = false,
    alert: Boolean = false,
    enabled: Boolean = true,
    showCaret: Boolean = false,
    monoLabel: Boolean = false,
) {
    val tokens = LocalPpmTokens.current
    val pillShape = RoundedCornerShape(999.dp)

    val bgColor = when {
        alert -> tokens.warning.opacity(0.14).color
        selected -> tokens.accentWash.color
        else -> tokens.panel2.color
    }
    val borderColor = when {
        alert -> tokens.warning.opacity(0.40).color
        selected -> tokens.accentWashBorder.color
        else -> tokens.border.color
    }
    val fgColor = when {
        !enabled -> tokens.text3.opacity(0.55).color
        alert -> tokens.warning.color
        selected -> tokens.accent.color
        else -> tokens.text2.color
    }
    val caretColor = when {
        !enabled -> tokens.text3.opacity(0.4).color
        alert -> tokens.warning.color
        selected -> tokens.accent.color
        else -> tokens.text3.color
    }

    Box(
        modifier = modifier
            .minTouchTarget(minWidth = KitSpec.MinTouchTarget, minHeight = KitSpec.ChipHitHeight)
            .clip(pillShape)
            .ppmClickable(
                enabled = enabled,
                shape = pillShape,
                hoverColor = tokens.surfaceHover.color,
                onClickLabel = label,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(KitSpec.ChipVisualHeight)
                .background(bgColor, pillShape)
                .cssBorder(borderColor, pillShape, width = 1.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (providerId != null && ProviderLogos.has(providerId)) {
                ProviderLogo(
                    providerId = providerId,
                    size = 14.dp,
                    foreground = fgColor,
                )
            } else if (icon != null) {
                PpmIcon(
                    vector = icon,
                    size = 14.dp,
                    tint = fgColor,
                )
            }

            BasicText(
                text = label,
                style = ppmText(
                    size = 12.5f,
                    weight = if (selected || alert) FontWeight.SemiBold else FontWeight.Medium,
                    kind = if (monoLabel) FontKind.MONO else FontKind.SANS,
                    lineHeight = 16f,
                ).copy(color = fgColor),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (!secondaryLabel.isNullOrEmpty()) {
                BasicText(
                    text = secondaryLabel,
                    style = ppmText(11.5f, FontWeight.Normal, FontKind.MONO, lineHeight = 14f)
                        .copy(color = caretColor),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (showCaret) {
                PpmIcon(
                    icon = ProductIcon.ChevronDown,
                    size = 12.dp,
                    tint = caretColor,
                )
            }
        }
    }
}

@Composable
fun Chip(
    label: String,
    icon: ProductIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryLabel: String? = null,
    selected: Boolean = false,
    alert: Boolean = false,
    enabled: Boolean = true,
    showCaret: Boolean = false,
    monoLabel: Boolean = false,
) = Chip(
    label = label,
    onClick = onClick,
    modifier = modifier,
    icon = icon.vector,
    secondaryLabel = secondaryLabel,
    selected = selected,
    alert = alert,
    enabled = enabled,
    showCaret = showCaret,
    monoLabel = monoLabel,
)

@Composable
fun Chip(
    label: String,
    icon: LucideIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryLabel: String? = null,
    selected: Boolean = false,
    alert: Boolean = false,
    enabled: Boolean = true,
    showCaret: Boolean = false,
    monoLabel: Boolean = false,
) = Chip(
    label = label,
    onClick = onClick,
    modifier = modifier,
    icon = icon.vector,
    secondaryLabel = secondaryLabel,
    selected = selected,
    alert = alert,
    enabled = enabled,
    showCaret = showCaret,
    monoLabel = monoLabel,
)

/**
 * Account switcher pill button (Foundation Section 3.8): 20 dp avatar circle + account name +
 * usage percentage inside a 48 dp touch target.
 */
@Composable
fun AccountChip(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    usageText: String? = null,
    usageWarning: Boolean = false,
    avatarLetter: String = name.take(1).uppercase().ifEmpty { "A" },
    avatarColor: Color? = null,
    selected: Boolean = false,
) {
    val tokens = LocalPpmTokens.current
    val pillShape = RoundedCornerShape(999.dp)
    val bg = if (selected) tokens.accentWash.color else tokens.panel2.color
    val border = if (selected) tokens.accentWashBorder.color else tokens.border.color

    Box(
        modifier = modifier
            .minTouchTarget(minWidth = KitSpec.MinTouchTarget, minHeight = KitSpec.ChipHitHeight)
            .clip(pillShape)
            .ppmClickable(
                shape = pillShape,
                hoverColor = tokens.surfaceHover.color,
                onClickLabel = "Switch account ($name)",
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(KitSpec.ChipVisualHeight)
                .background(bg, pillShape)
                .cssBorder(border, pillShape, width = 1.dp)
                .padding(start = 6.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(avatarColor ?: tokens.accent.color, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = avatarLetter,
                    style = ppmText(11f, FontWeight.Bold, lineHeight = 13f).copy(color = tokens.accentFg.color),
                )
            }
            BasicText(
                text = name,
                style = ppmText(12.5f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!usageText.isNullOrEmpty()) {
                BasicText(
                    text = usageText,
                    style = ppmText(11.5f, FontWeight.Normal, FontKind.MONO, lineHeight = 14f).copy(
                        color = if (usageWarning) tokens.warning.color else tokens.text2.color,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}
