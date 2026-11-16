package com.hienlh.ppm.app.ui

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.colorsFade
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.state.LinkedSessionStatus

/** Override for tests simulating `prefers-reduced-motion: reduce`. */
val LocalReducedMotion = compositionLocalOf { false }

/** Local foreground colour inside a footer chip or ghost button. */
val LocalFooterContentColor = compositionLocalOf { Color.Unspecified }

/** Local text style inside a footer chip or ghost button. */
val LocalFooterTextStyle = compositionLocalOf { ppmText(11.5f, lineHeight = 16f) }

/** True when the user or test environment disables animations (`motion-reduce`). */
@Composable
fun rememberPrefersReducedMotion(): Boolean {
    if (LocalReducedMotion.current) return true
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            val resolver = context.contentResolver
            val transition = Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f)
            val animator = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            transition == 0f || animator == 0f
        }.getOrDefault(false)
    }
}

enum class FooterChipTone {
    Default,
    AlertBorder,
    Danger,
    /** `border-warning/40 text-warning`: something in it needs a glance (the schedules chip). */
    Warning,
}

/**
 * A bordered chip on the left of the chat footer's context row (`FOOTER_CHIP` in `footer-chip.ts`):
 * 28dp desktop, 32dp narrow tablet (`@max-[420px]/chat`), 26dp visual / >=44dp hit target on phone.
 */
@Composable
fun FooterChip(
    narrow: Boolean,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    tone: FooterChipTone = FooterChipTone.Default,
    gap: Dp? = null,
    title: String? = null,
    contentDescription: String? = title,
    testTag: String? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val reducedMotion = rememberPrefersReducedMotion()
    val visualHeight = when {
        mobile -> 26.dp
        narrow -> 32.dp
        else -> 28.dp
    }
    val padX = when {
        mobile -> 5.dp
        narrow -> 8.dp
        else -> 10.dp
    }
    val itemGap = gap ?: 6.dp

    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val hoverLit = if (onClick != null && !mobile) colorsFade(hovered) else 0f
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && onClick != null && !reducedMotion) 0.97f else 1f,
        animationSpec = tween(if (reducedMotion) 0 else 150),
        label = "chipScale",
    )

    val bg = when {
        expanded -> tokens.accentWash.color
        tone == FooterChipTone.Danger -> tokens.error.opacity(0.10).color
        pressed && onClick != null -> tokens.surfaceHover.over(tokens.panel2).color
        else -> tokens.panel2.color
    }
    val border = when {
        expanded -> tokens.accentWashBorder.color
        tone == FooterChipTone.Danger -> tokens.error.opacity(0.35 + 0.25 * hoverLit).color
        tone == FooterChipTone.AlertBorder -> tokens.error.opacity(0.35 + 0.25 * hoverLit).color
        tone == FooterChipTone.Warning -> tokens.warning.opacity(0.4).color
        else -> tokens.border.mix(tokens.borderSoft, hoverLit.toDouble()).color
    }
    val fg = when {
        expanded -> tokens.text.color
        tone == FooterChipTone.Danger -> tokens.error.color
        tone == FooterChipTone.Warning -> tokens.warning.color
        hoverLit > 0f -> tokens.text.mix(tokens.text2, hoverLit.toDouble()).color
        else -> tokens.text2.color
    }
    val textStyle: TextStyle = ppmText(11.5f, lineHeight = 16f).copy(color = fg)

    var outer = modifier.height(visualHeight)
    if (testTag != null) outer = outer.testTag(testTag)
    if (title != null) outer = outer.webTitle(title)
    if (contentDescription != null || expanded) {
        outer = outer.semantics {
            if (contentDescription != null) this.contentDescription = contentDescription
            if (onClick != null) this.stateDescription = if (expanded) "expanded" else "collapsed"
        }
    }
    if (onClick != null) {
        outer = outer
            .hoverable(interactions)
            .clickable(
                interactionSource = interactions,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
    }

    Box(outer, contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .height(visualHeight)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .clip(CircleShape)
                .background(bg)
                .cssBorder(border, CircleShape)
                .padding(horizontal = padX),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(itemGap),
        ) {
            CompositionLocalProvider(
                LocalFooterContentColor provides fg,
                LocalFooterTextStyle provides textStyle,
            ) {
                content()
            }
        }
    }
}

/**
 * The right-hand pill cluster (`FOOTER_CLUSTER` in `footer-chip.ts`): shares height, background,
 * border and radius with [FooterChip].
 */
@Composable
fun FooterCluster(
    narrow: Boolean,
    modifier: Modifier = Modifier,
    testTag: String? = "footer-cluster",
    content: @Composable RowScope.() -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val height = when {
        mobile -> 26.dp
        narrow -> 32.dp
        else -> 28.dp
    }
    val padX = if (mobile) 1.dp else 2.dp
    Row(
        modifier
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .height(height)
            .clip(CircleShape)
            .background(tokens.panel2.color)
            .cssBorder(tokens.borderSoft.color, CircleShape)
            .padding(horizontal = padX),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * Borderless button inside [FooterCluster] (`FOOTER_GHOST` in `footer-chip.ts`): usage, History, ···.
 */
@Composable
fun FooterGhostButton(
    narrow: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    title: String? = null,
    contentDescription: String? = title,
    testTag: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val reducedMotion = rememberPrefersReducedMotion()
    val visualHeight = when {
        mobile -> 22.dp
        narrow -> 28.dp
        else -> 24.dp
    }
    val minWidth = when {
        mobile -> 22.dp
        narrow -> 30.dp
        else -> 26.dp
    }
    val padX = if (mobile) 4.dp else 6.dp

    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val active = expanded || pressed || (!mobile && hovered)
    val lit = if (reducedMotion) (if (active) 1f else 0f) else colorsFade(active)
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && !reducedMotion) 0.97f else 1f,
        animationSpec = tween(if (reducedMotion) 0 else 150),
        label = "ghostScale",
    )

    val bg = tokens.surfaceHover.opacity(lit.toDouble()).color
    val fg = tokens.text.mix(tokens.text3, lit.toDouble()).color
    val textStyle = ppmText(11f, lineHeight = 16f).copy(color = fg)

    var outer = modifier.height(visualHeight)
    if (testTag != null) outer = outer.testTag(testTag)
    if (title != null) outer = outer.webTitle(title)
    if (contentDescription != null) {
        outer = outer.semantics {
            this.contentDescription = contentDescription
            this.stateDescription = if (expanded) "expanded" else "collapsed"
        }
    }
    outer = outer
        .hoverable(interactions)
        .clickable(
            interactionSource = interactions,
            indication = null,
            role = Role.Button,
            onClick = onClick,
        )

    Box(outer, contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .defaultMinSize(minWidth = minWidth)
                .height(visualHeight)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .clip(CircleShape)
                .background(bg)
                .padding(horizontal = padX),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            CompositionLocalProvider(
                LocalFooterContentColor provides fg,
                LocalFooterTextStyle provides textStyle,
            ) {
                content()
            }
        }
    }
}

/**
 * A linked session's live status as an 8dp dot (`LinkedStatusDot` in `linked-session-chip.tsx`):
 * pulsing green while running (static under reduced motion), grey when idle, red when waiting on
 * the person, blue when an answer is unread.
 */
@Composable
fun LinkedStatusDot(status: LinkedSessionStatus, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val reducedMotion = rememberPrefersReducedMotion()
    val dotColor = when (status) {
        LinkedSessionStatus.RUNNING -> tokens.success
        LinkedSessionStatus.IDLE -> tokens.text3
        LinkedSessionStatus.NEEDS_APPROVAL -> tokens.error
        LinkedSessionStatus.NEW_MESSAGE -> tokens.info
    }
    val tagged = modifier.size(8.dp).testTag("linked-dot:${status.wire}")
    if (status == LinkedSessionStatus.RUNNING && !reducedMotion) {
        val ping by rememberInfiniteTransition(label = "linkedPing").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                keyframes {
                    durationMillis = 1_000
                    0f at 0 using CubicBezierEasing(0f, 0f, 0.2f, 1f)
                    1f at 750
                },
            ),
            label = "linkedPingValue",
        )
        Canvas(tagged) {
            val radius = size.minDimension / 2f
            drawCircle(dotColor.opacity(0.55 * (1.0 - ping)).color, radius * (1f + ping))
            drawCircle(dotColor.color, radius)
        }
    } else {
        Box(tagged.background(dotColor.color, CircleShape))
    }
}
