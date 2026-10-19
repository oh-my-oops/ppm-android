package com.hienlh.ppm.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
 */
@Composable
fun Modifier.ppmClickable(
    enabled: Boolean = true,
    pressedColor: Color = LocalPpmTokens.current.surfaceHover.color,
    shape: Shape = RectangleShape,
    role: Role = Role.Button,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    return background(if (pressed && enabled) pressedColor else Color.Transparent, shape)
        .clickable(source, indication = null, enabled = enabled, onClickLabel = onClickLabel, role = role, onClick = onClick)
}
