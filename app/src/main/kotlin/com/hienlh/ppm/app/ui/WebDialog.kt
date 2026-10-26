package com.hienlh.ppm.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isFinite
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.hienlh.ppm.app.leavesTextOnTap
import com.hienlh.ppm.design.CssColumn
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.WebCursor
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssBorderInset
import com.hienlh.ppm.design.cssExact
import com.hienlh.ppm.design.cssLines
import com.hienlh.ppm.design.cssMeasure
import com.hienlh.ppm.design.cssTightLines
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberCssExact
import com.hienlh.ppm.design.rememberHover
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The web's `Button` at its default size, `rounded-md text-sm font-medium`: either `default`
 * (the accent) or `outline`, half-faded while it cannot be used. It is `px-4`, and `px-3` around
 * an icon (`has-[>svg]:px-3`) unless the icon spins: the web wraps a spinner in a span, which the
 * rule does not see ([padding] where it does). [height] is the caller's (`min-h-11`, `h-11 md:h-9`),
 * [gap] the icon's distance from the label (the button's own `gap-2`, or a class's), [iconSize]
 * the icon's own `size-*` where it has one.
 */
@Composable
internal fun DialogButton(
    label: String,
    primary: Boolean,
    dark: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    icon: ProductIcon? = null,
    spinning: Boolean = false,
    height: Dp = 44.dp,
    gap: Dp = 6.dp,
    padding: Dp = if (icon != null && !spinning) 12.dp else 16.dp,
    iconSize: Dp = 16.dp,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    val hover = rememberHover(enabled = enabled)
    val content = if (primary) tokens.accentFg.color else tokens.text.color
    Row(
        modifier
            .height(height)
            .alpha(if (enabled) 1f else 0.5f)
            .then(if (primary) Modifier else Modifier.ppmShadow(SHADOW_XS, shape))
            .clip(shape)
            .background(if (primary) tokens.accent.opacity(1 - 0.1 * hover.lit).color else outlineFill(tokens, dark, hover.lit.toDouble()))
            .ppmClickable(
                enabled = enabled,
                pressedColor = if (primary) Color.Black.copy(alpha = 0.1f) else tokens.panel2.color,
                shape = shape,
                source = hover.source,
                onClick = onClick,
            )
            .then(if (primary) Modifier else Modifier.cssBorder(outlineBorder(tokens, dark), shape))
            .padding(horizontal = padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
    ) {
        if (icon != null) PpmIcon(icon, iconSize, content, spinning = spinning)
        if (label.isNotEmpty()) BasicText(label, style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = content), maxLines = 1, softWrap = false)
    }
}

/**
 * `rounded-xs focus:ring-2 focus:ring-offset-2`: two box-shadows outside the box, 2 px of [offset]
 * (`ring-offset-background`) and 2 px of [ring] around that, their corners widened by the spread
 * as CSS widens a radius smaller than the spread (1 + (r / spread − 1)³ of it).
 */
private fun Modifier.focusRing(ring: Color, offset: Color): Modifier = drawBehind {
    val px = 1.dp.toPx()
    val radius = 2 * px
    fun spread(s: Float) = radius + s * (1 + (minOf(radius / s, 1f) - 1).let { it * it * it })
    val outer = spread(4 * px)
    val inner = spread(2 * px)
    drawRoundRect(ring, Offset(-3 * px, -3 * px), Size(size.width + 6 * px, size.height + 6 * px), CornerRadius((outer + inner) / 2), style = Stroke(2 * px))
    drawRoundRect(offset, Offset(-px, -px), Size(size.width + 2 * px, size.height + 2 * px), CornerRadius((inner + radius) / 2), style = Stroke(2 * px))
}

/** CSS's `ease`, what tw-animate-css enters with. */
internal val CssEase = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

/** `DialogTitle`: `text-lg leading-none font-semibold`. */
internal val DialogTitleStyle = ppmText(18f, FontWeight.SemiBold, lineHeight = 18f)

/**
 * The web's `Dialog` around `DialogContent` (shadcn): a 50% black backdrop, a card at most
 * [widest] (`sm:max-w-lg`, or the caller's `sm:max-w-*`) and 16 in from each side,
 * `rounded-lg border bg-background p-6 shadow-lg`, the header (`DialogHeader`: [title] in
 * [titleStyle] and the [description] under it, `gap-2`, clear of the close cross) 16 over
 * [content]; a null [title] is one read only to a screen reader (`sr-only`), which takes no room. A
 * bare `border` is Tailwind 4's `currentColor`, the text's colour, as the web sets
 * no default border colour. It fades in and grows from 95%; it goes at once, as the web unmounts it.
 * [tallest] is a `max-h-[…]` of the card's, under which a [content] that scrolls scrolls: it is
 * the child that gives way, `Modifier.cssFlex()`, as the web's `min-h-0 flex-1` is.
 *
 * Radix gives the focus to the first thing in the card that takes it, and the cross comes after
 * [content]: when nothing in [content] takes it as the dialog opens ([closeFocused]), the cross
 * has it, and wears its `focus:ring-2 ring-offset-2` until a press inside the card moves it on.
 */
@Composable
internal fun WebDialog(
    title: String?,
    onDismiss: () -> Unit,
    widest: Dp = 512.dp,
    titleStyle: TextStyle = DialogTitleStyle,
    description: String? = null,
    closeFocused: Boolean = false,
    tallest: Dp = Dp.Infinity,
    content: @Composable () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    if (closedByConnectionLost(onDismiss)) return
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // The platform's own dim would fade on its own clock; the backdrop is drawn here.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) { window?.setDimAmount(0f) }
        val focusManager = LocalFocusManager.current
        val backdrop = remember { Animatable(0f) }
        val card = remember { Animatable(0f) }
        var ringed by remember { mutableStateOf(closeFocused) }
        LaunchedEffect(Unit) { launch { backdrop.animateTo(1f, tween(150, easing = CssEase)) }; card.animateTo(1f, tween(200, easing = CssEase)) }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f * backdrop.value))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        ) {
            // Centred where the page is, between the system bars, as the web's is in its viewport.
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
                val shape = RoundedCornerShape(12.dp)
                val width = minOf(maxWidth - 32.dp, widest)
                val exact = rememberCssExact()
                Layout(
                    content = {
                        // The card's shadow, background and border, with room around them for the shadow,
                        // which the layer they are drawn in would cut off.
                        Box(
                            Modifier
                                .padding(SHADOW_ROOM)
                                .then(ShadowLg.fold(Modifier as Modifier) { m, s -> m.ppmShadow(s, shape) })
                                .clip(shape)
                                .background(tokens.bgSolid.color)
                                .cssBorder(tokens.text.color, shape),
                        )
                        Box(
                            Modifier
                                .cssExact(exact)
                                .width(width)
                                .heightIn(max = tallest)
                                .clip(shape)
                                // A tap on the card stays on it rather than reaching the backdrop.
                                .pointerInput(Unit) { detectTapGestures {} }
                                // A tap off the focused field takes the focus from it, as on the page.
                                .leavesTextOnTap(focusManager)
                                // Any press inside takes the focus from the cross: to a button, or the card.
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) ringed = false
                                        }
                                    }
                                }
                                .cssBorderInset(1.dp),
                        ) {
                            // `grid gap-4 p-6`: a body that scrolls is the one that gives way (`cssFlex`), as its `min-h-0 flex-1` does.
                            CssColumn(Modifier.fillMaxWidth().padding(horizontal = 24.dp), gap = 16.dp, top = 24.dp, bottom = 24.dp, exact = exact) {
                                // `flex flex-col gap-2 sm:text-left`, and `pr-8` for the cross.
                                if (title != null) {
                                    CssColumn(Modifier.padding(end = 32.dp), gap = 8.dp) {
                                        BasicText(title, Modifier.cssTightLines(titleStyle.fontSize.value, titleStyle.lineHeight.value), style = titleStyle.copy(color = tokens.text.color))
                                        // `DialogDescription`: `text-sm text-muted-foreground`.
                                        if (description != null) BasicText(description, Modifier.cssLines(20f), style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text2.color))
                                    }
                                }
                                content()
                            }
                            // `absolute top-4 right-4 rounded-xs opacity-70 hover:opacity-100`, a 16 px cross.
                            val hover = rememberHover()
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    // The opacity draws the cross in a layer of its own, which cuts off
                                    // whatever reaches out of it: the layer takes in the ring's 4 px.
                                    .padding(top = 11.dp, end = 11.dp)
                                    .alpha(0.7f + 0.3f * hover.lit)
                                    .padding(5.dp)
                                    .then(if (ringed) Modifier.focusRing(tokens.accent.color, tokens.bgSolid.color) else Modifier)
                                    .pointerHoverIcon(WebCursor.Pointer)
                                    .ppmClickable(pressedColor = Color.Transparent, source = hover.source, onClickLabel = "Close", onClick = onDismiss),
                            ) {
                                PpmIcon(ProductIcon.X, 16.dp, tokens.text.color)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                ) { measurables, constraints ->
                    val (body, measured) = measurables[1].cssMeasure(
                        Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight),
                        at = 0f,
                    )
                    val height = when {
                        // Held at its `max-h-*`: that length as Chrome keeps it, in 64ths of a CSS px.
                        tallest.isFinite && body.height >= tallest.roundToPx() -> floor(tallest.value * 64) / 64 * density
                        // Held between the system bars, which the web's runs past.
                        body.height >= constraints.maxHeight -> body.height.toFloat()
                        else -> measured
                    }
                    // `top-[50%] left-[50%] translate-x-[-50%] translate-y-[-50%]`, in the fractions it comes to.
                    val left = (constraints.maxWidth - width.toPx()) / 2
                    val top = (constraints.maxHeight - height) / 2
                    val room = SHADOW_ROOM.roundToPx()
                    val chrome = measurables[0].measure(Constraints.fixed(body.width + 2 * room, height.roundToInt() + 2 * room))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        // Chrome moves the card by the fraction its translate comes to, which spreads the
                        // box's edges over two rows, but rounds the text in it to whole pixels and snaps a
                        // body that scrolls whole: the box is drawn in a layer moved by the fraction, what
                        // is in it from the pixel the card's top rounds to.
                        chrome.placeWithLayer(floor(left).toInt() - room, floor(top).toInt() - room) {
                            translationX = left - floor(left)
                            translationY = top - floor(top)
                            alpha = card.value
                            scaleX = 0.95f + 0.05f * card.value
                            scaleY = scaleX
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                        body.placeWithLayer(left.roundToInt(), top.roundToInt()) {
                            alpha = card.value
                            scaleX = 0.95f + 0.05f * card.value
                            scaleY = scaleX
                        }
                    }
                }
            }
        }
    }
}

/** Room around a dialog's box in its layer for `shadow-lg`, whose deepest blur reaches 29.5 px under it. */
private val SHADOW_ROOM = 32.dp
