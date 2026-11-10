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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText

/**
 * Floating "Needs you" pill shown above the chat composer when an approval or question card is
 * waiting off-screen (Requirement 4.2, Foundation Section 3.4).
 *
 * Tapping scrolls the transcript to the waiting card.
 */
@Composable
fun NeedsYouPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Needs you",
    count: Int = 1,
    pointUp: Boolean = false,
    dotColor: Color? = null,
) {
    val tokens = LocalPpmTokens.current
    val pillShape = RoundedCornerShape(999.dp)
    val accentDot = dotColor ?: tokens.error.color
    val borderColor = accentDot.copy(alpha = 0.45f)
    val displayText = if (count > 1) "$label \u00B7 $count" else label

    Box(
        modifier = modifier
            .minTouchTarget(minWidth = KitSpec.MinTouchTarget, minHeight = KitSpec.NeedsYouPillHitHeight)
            .clip(pillShape)
            .ppmClickable(
                shape = pillShape,
                hoverColor = tokens.surfaceHover.color,
                onClickLabel = displayText,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(KitSpec.NeedsYouPillVisualHeight)
                .ppmShadow(tokens.shadowFloat, pillShape)
                .clip(pillShape)
                .background(tokens.panel2.color, pillShape)
                .cssBorder(borderColor, pillShape, width = 1.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(accentDot, CircleShape),
            )
            BasicText(
                text = displayText,
                style = ppmText(12.5f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color),
                maxLines = 1,
            )
            PpmIcon(
                icon = if (pointUp) ProductIcon.ArrowUp else ProductIcon.ArrowDown,
                size = 14.dp,
                tint = accentDot,
            )
        }
    }
}
