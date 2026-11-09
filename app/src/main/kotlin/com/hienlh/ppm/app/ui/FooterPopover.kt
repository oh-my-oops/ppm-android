package com.hienlh.ppm.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.colorsFade
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.webTitle
import kotlinx.coroutines.delay

private const val POPOVER_EXIT_MS = 100L
private val FoldEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

enum class FooterPopoverAlign {
    Left,
    Right,
}

enum class FooterButtonVariant {
    Plain,
    Primary,
    Danger,
    DangerFill,
}

internal class FooterPopoverEntry(
    val align: FooterPopoverAlign,
    val label: String,
    val maxWidth: Dp,
    val closing: Boolean,
    val onClose: () -> Unit,
    val content: @Composable ColumnScope.() -> Unit,
)

/**
 * Anchors desktop/tablet footer popovers to the chat footer's context row so left-chip popovers
 * are never clipped by the horizontal overflow box (`footer-popover.tsx`).
 */
class FooterPopoverHostState {
    internal val entries = mutableStateMapOf<Any, FooterPopoverEntry>()

    val hasOpenPopover: Boolean
        get() = entries.values.any { !it.closing }

    fun dismissAll() {
        entries.values.toList().forEach { if (!it.closing) it.onClose() }
    }
}

val LocalFooterPopoverHost = compositionLocalOf<FooterPopoverHostState?> { null }

/**
 * Renders any active tablet popovers anchored 4dp (`mb-1`) above the context row.
 * Call this inside a `Box` directly above the context row in `ChatPane`.
 */
@Composable
fun FooterPopoverHostSlot(
    state: FooterPopoverHostState,
    modifier: Modifier = Modifier,
) {
    if (state.entries.isEmpty()) return
    Box(
        modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Escape && event.type == KeyEventType.KeyDown && state.hasOpenPopover) {
                    state.dismissAll()
                    true
                } else {
                    false
                }
            },
    ) {
        state.entries.forEach { (_, entry) ->
            Box(
                Modifier.fillMaxWidth(),
                contentAlignment = if (entry.align == FooterPopoverAlign.Left) Alignment.BottomStart else Alignment.BottomEnd,
            ) {
                FooterPopoverSurface(
                    label = entry.label,
                    align = entry.align,
                    maxWidth = entry.maxWidth,
                    closing = entry.closing,
                    onClose = entry.onClose,
                    content = entry.content,
                )
            }
        }
    }
}

/** Transparent full-pane scrim behind the footer that closes open tablet popovers on outside tap. */
@Composable
fun FooterPopoverScrim(state: FooterPopoverHostState, modifier: Modifier = Modifier) {
    if (!state.hasOpenPopover) return
    Box(
        modifier
            .fillMaxSize()
            .testTag("footer-popover-scrim")
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = state::dismissAll,
            ),
    )
}

/**
 * The popover every chip of the chat footer opens (`FooterPopover` in `footer-popover.tsx`):
 * above the context row on a tablet/desktop, a [BottomSheet] on a phone (`LocalPpmMobile`).
 */
@Composable
fun FooterPopover(
    open: Boolean,
    onClose: () -> Unit,
    label: String,
    align: FooterPopoverAlign = FooterPopoverAlign.Left,
    maxWidth: Dp = 360.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val mobile = LocalPpmMobile.current
    val reducedMotion = rememberPrefersReducedMotion()
    val host = LocalFooterPopoverHost.current
    val key = remember { Any() }
    val currentOnClose by rememberUpdatedState(onClose)
    val currentContent by rememberUpdatedState(content)

    if (mobile) {
        DisposableEffect(host, key) {
            host?.entries?.remove(key)
            onDispose { host?.entries?.remove(key) }
        }
        if (!open) return
        val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
        BottomSheet(
            onDismiss = onClose,
            modifier = Modifier.heightIn(max = maxSheetHeight),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .testTag("footer-popover:$label")
                    .semantics { paneTitle = label },
                content = content,
            )
        }
        return
    }

    var keepDuringExit by remember { mutableStateOf(false) }
    val show = open || keepDuringExit
    val closing = !open && keepDuringExit

    LaunchedEffect(open, reducedMotion) {
        if (open) {
            keepDuringExit = !reducedMotion
        } else if (keepDuringExit) {
            if (reducedMotion) {
                keepDuringExit = false
            } else {
                delay(POPOVER_EXIT_MS)
                keepDuringExit = false
            }
        }
    }

    if (host != null) {
        SideEffect {
            if (show) {
                host.entries[key] = FooterPopoverEntry(
                    align = align,
                    label = label,
                    maxWidth = maxWidth,
                    closing = closing,
                    onClose = { currentOnClose() },
                    content = { currentContent() },
                )
            } else {
                host.entries.remove(key)
            }
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        DisposableEffect(host, key) {
            onDispose {
                host.entries.remove(key)
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            }
        }
    } else if (show) {
        FooterPopoverSurface(
            label = label,
            align = align,
            maxWidth = maxWidth,
            closing = closing,
            onClose = onClose,
            content = content,
        )
    }
}

@Composable
private fun FooterPopoverSurface(
    label: String,
    align: FooterPopoverAlign,
    maxWidth: Dp,
    closing: Boolean,
    onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val reducedMotion = rememberPrefersReducedMotion()
    val config = LocalConfiguration.current
    val maxHeight = minOf((config.screenHeightDp * 0.75f).dp, 560.dp)
    val shape = RoundedCornerShape(12.dp)
    val alpha by animateFloatAsState(
        targetValue = if (closing) 0f else 1f,
        animationSpec = tween(if (reducedMotion) 0 else if (closing) 100 else 150),
        label = "popoverAlpha",
    )
    val scale by animateFloatAsState(
        targetValue = if (closing) 0.96f else 1f,
        animationSpec = tween(if (reducedMotion) 0 else if (closing) 100 else 150),
        label = "popoverScale",
    )
    val origin = if (align == FooterPopoverAlign.Left) TransformOrigin(0f, 1f) else TransformOrigin(1f, 1f)

    Column(
        Modifier
            .widthIn(max = maxWidth)
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .testTag("footer-popover:$label")
            .semantics { paneTitle = label }
            .graphicsLayer {
                this.alpha = alpha
                this.scaleX = scale
                this.scaleY = scale
                this.transformOrigin = origin
            }
            .ppmShadow(tokens.shadowFloat, shape)
            .clip(shape)
            .background(tokens.panel2.over(tokens.bgSolid).color)
            .cssBorder(tokens.border.color, shape)
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                    onClose()
                    true
                } else {
                    false
                }
            },
        content = content,
    )
}

/** Header line of a footer popover (`FooterPopoverHeader`). */
@Composable
fun FooterPopoverHeader(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = if (mobile) 48.dp else 40.dp)
            .cssBorder(tokens.borderSoft.color, sides = CssSides.Bottom)
            .padding(horizontal = if (mobile) 16.dp else 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** Action bar at the foot of a footer popover (`FooterPopoverFooter`). */
@Composable
fun FooterPopoverFooter(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    Row(
        modifier
            .fillMaxWidth()
            .cssBorder(tokens.borderSoft.color, sides = CssSides.Top)
            .padding(horizontal = if (mobile) 12.dp else 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (mobile) 8.dp else 4.dp),
        content = content,
    )
}

/**
 * Action button inside a footer popover (`FooterButton` in `footer-popover.tsx`).
 * When [pushEnd] is true inside a [RowScope], pushes the button to the right edge (`ml-auto`).
 */
@Composable
fun RowScope.FooterButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: FooterButtonVariant = FooterButtonVariant.Plain,
    enabled: Boolean = true,
    pushEnd: Boolean = variant == FooterButtonVariant.Primary,
    textColorOverride: Color? = null,
    title: String? = null,
    contentDescription: String? = title,
    testTag: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    if (pushEnd) {
        Spacer(Modifier.weight(1f))
    }
    FooterButtonBox(
        onClick = onClick,
        modifier = modifier,
        variant = variant,
        enabled = enabled,
        textColorOverride = textColorOverride,
        title = title,
        contentDescription = contentDescription,
        testTag = testTag,
        content = content,
    )
}

/** Standalone [FooterButton] outside a [RowScope] (or where `ml-auto` is not desired). */
@Composable
fun FooterButtonBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: FooterButtonVariant = FooterButtonVariant.Plain,
    enabled: Boolean = true,
    textColorOverride: Color? = null,
    title: String? = null,
    contentDescription: String? = title,
    testTag: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val reducedMotion = rememberPrefersReducedMotion()
    val shape = RoundedCornerShape(8.dp)
    val height = if (mobile) 44.dp else 30.dp
    val fontSize = if (mobile) 14f else 12f
    val lineHeight = if (mobile) 20f else 16f

    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val active = enabled && (pressed || (!mobile && hovered))
    val lit = if (reducedMotion) (if (active) 1f else 0f) else colorsFade(active)
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && enabled && !reducedMotion) 0.97f else 1f,
        animationSpec = tween(if (reducedMotion) 0 else 150),
        label = "footerBtnScale",
    )

    val bg = when (variant) {
        FooterButtonVariant.Plain -> tokens.surfaceHover.opacity(lit.toDouble()).color
        FooterButtonVariant.Primary -> tokens.accent.opacity(1.0 - 0.10 * lit).color
        FooterButtonVariant.Danger -> tokens.error.opacity(0.10 * lit).color
        FooterButtonVariant.DangerFill -> tokens.error.opacity(1.0 - 0.10 * lit).color
    }
    val fg = textColorOverride ?: when (variant) {
        FooterButtonVariant.Plain -> tokens.text.mix(tokens.text2, lit.toDouble()).color
        FooterButtonVariant.Primary -> tokens.accentFg.color
        FooterButtonVariant.Danger -> tokens.error.color
        FooterButtonVariant.DangerFill -> Color.White
    }
    val weight = if (variant == FooterButtonVariant.Primary || variant == FooterButtonVariant.DangerFill) FontWeight.Medium else FontWeight.Normal
    val textStyle = ppmText(fontSize, weight, lineHeight = lineHeight).copy(color = fg)

    var btnModifier = modifier
    if (testTag != null) btnModifier = btnModifier.testTag(testTag)
    if (title != null) btnModifier = btnModifier.webTitle(title)
    if (contentDescription != null) {
        btnModifier = btnModifier.semantics { this.contentDescription = contentDescription }
    }

    Row(
        btnModifier
            .height(height)
            .alpha(if (enabled) 1f else 0.5f)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(shape)
            .background(bg)
            .hoverable(interactions, enabled)
            .clickable(
                interactionSource = interactions,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CompositionLocalProvider(
            LocalFooterContentColor provides fg,
            LocalFooterTextStyle provides textStyle,
        ) {
            content()
        }
    }
}

/**
 * Collapsible group that animates open and closed (`FoldGroup` in `switch-account-view.tsx`);
 * instant under reduced motion, unmounted when closed (`inert={!open}`).
 */
@Composable
fun FoldGroup(
    open: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val reducedMotion = rememberPrefersReducedMotion()
    if (reducedMotion) {
        if (open) {
            Box(modifier) { content() }
        }
    } else {
        AnimatedVisibility(
            visible = open,
            modifier = modifier,
            enter = expandVertically(animationSpec = tween(200, easing = FoldEasing)) + fadeIn(animationSpec = tween(150)),
            exit = shrinkVertically(animationSpec = tween(200, easing = FoldEasing)) + fadeOut(animationSpec = tween(150)),
        ) {
            content()
        }
    }
}
