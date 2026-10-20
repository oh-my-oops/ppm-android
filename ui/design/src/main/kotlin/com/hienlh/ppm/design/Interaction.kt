package com.hienlh.ppm.design

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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

/** Tailwind's `transition-colors`: 150 ms on `cubic-bezier(0.4, 0, 0.2, 1)`. */
val ColorsFade: FiniteAnimationSpec<Float> = tween(150, easing = FastOutSlowInEasing)

/** 0 to 1 as [on] turns true, and back, faded as `transition-colors` fades a change of look. */
@Composable
fun colorsFade(on: Boolean): Float = animateFloatAsState(if (on) 1f else 0f, ColorsFade, label = "colors").value

/** A mouse over one control: the [source] to give its clickable, and how far its hover look has faded in. */
class Hover(val source: MutableInteractionSource, val lit: Float)

/** A [Hover] for a control whose `hover:` fades in, or [instant] where the web's has no `transition-colors`. */
@Composable
fun rememberHover(enabled: Boolean = true, instant: Boolean = false): Hover {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val on = hovered && enabled
    return Hover(source, if (instant) (if (on) 1f else 0f) else colorsFade(on))
}

/**
 * A web ghost button for touch: what the web lights on hover (`hover:bg-surface`) is lit while
 * the finger is down. No ripple, as the web has none.
 *
 * [hoverColor] is the web's own `hover:bg-…` for a mouse over it, and only where the web has
 * one: Chrome on Android applies `hover:` once a mouse is connected, but never `can-hover:`,
 * which also asks for a fine primary pointer and a touch screen keeps that coarse. It fades in
 * as `transition-colors` fades it, or at once where the web's has none ([fade] false). [source]
 * lets the caller read the hover as well, for a `hover:text-…` ([rememberHover]).
 */
@Composable
fun Modifier.ppmClickable(
    enabled: Boolean = true,
    pressedColor: Color = LocalPpmTokens.current.surfaceHover.color,
    hoverColor: Color = Color.Transparent,
    fade: Boolean = true,
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
    val on = hovered && enabled
    val lit = when {
        hoverColor.alpha == 0f -> 0f
        fade -> colorsFade(on)
        else -> if (on) 1f else 0f
    }
    val fill = when {
        !enabled -> Color.Transparent
        pressed -> pressedColor
        else -> hoverColor.copy(alpha = hoverColor.alpha * lit)
    }
    return background(fill, shape)
        .hoverable(interactions, enabled)
        .clickable(interactions, indication = null, enabled = enabled, onClickLabel = onClickLabel, role = role, onClick = onClick)
}
