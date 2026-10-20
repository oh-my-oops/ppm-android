package com.hienlh.ppm.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role

/**
 * A web ghost button for touch: what the web lights on hover (`hover:bg-surface`) is lit while
 * the finger is down. No ripple, as the web has none.
 *
 * [hoverColor] is the web's own `hover:bg-…` for a mouse over it, and only where the web has
 * one: Chrome on Android applies `hover:` once a mouse is connected, but never `can-hover:`,
 * which also asks for a fine primary pointer and a touch screen keeps that coarse. [source]
 * lets the caller read the hover as well, for a `hover:text-…`.
 */
@Composable
fun Modifier.ppmClickable(
    enabled: Boolean = true,
    pressedColor: Color = LocalPpmTokens.current.surfaceHover.color,
    hoverColor: Color = Color.Transparent,
    shape: Shape = RectangleShape,
    role: Role = Role.Button,
    onClickLabel: String? = null,
    source: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier {
    val own = remember { MutableInteractionSource() }
    val interactions = source ?: own
    val pressed by interactions.collectIsPressedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val fill = when {
        !enabled -> Color.Transparent
        pressed -> pressedColor
        hovered -> hoverColor
        else -> Color.Transparent
    }
    return background(fill, shape)
        .hoverable(interactions, enabled)
        .clickable(interactions, indication = null, enabled = enabled, onClickLabel = onClickLabel, role = role, onClick = onClick)
}
