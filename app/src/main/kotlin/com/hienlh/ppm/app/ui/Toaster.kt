package com.hienlh.ppm.app.ui

import android.os.SystemClock
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.hienlh.ppm.app.AppToast
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.liftsAsTap
import com.hienlh.ppm.design.CssColumn
import com.hienlh.ppm.design.CssRow
import com.hienlh.ppm.design.CssText
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ThemeShadow
import com.hienlh.ppm.design.WebCursor
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssBorderInset
import com.hienlh.ppm.design.cssFlex
import com.hienlh.ppm.design.cssLines
import com.hienlh.ppm.design.cssMeasure
import com.hienlh.ppm.design.cssWidth
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What sonner's `Toaster` keeps beside the toasts: whether the stack is spread out, a press on
 * it, and each toast's own height.
 */
@Stable
internal class ToasterState {
    /** Spread out (`expanded`): each toast at its own height, below the one in front of it. */
    var expanded by mutableStateOf(false)

    /** A pointer down on the toasts (`interacting`): they stay spread out, and no clock runs. */
    var interacting by mutableStateOf(false)
        private set

    /** Each toast's height laid out with nothing squeezing it (`--initial-height`), in px, fractions kept. */
    val heights = mutableStateMapOf<Long, Float>()

    /** What of the toasts a mouse is over: a toast, its close button, the strip above it. */
    private val hovered = mutableSetOf<Any>()

    /** Touches that went down on the toasts, until they end. */
    private val touches = mutableSetOf<PointerId>()

    fun mouseOver(region: Any) {
        hovered += region
        expanded = true
    }

    /** `mouseleave`: off the last of them, the stack folds back, unless a press holds it. */
    fun mouseOff(region: Any) {
        if (hovered.remove(region) && hovered.isEmpty() && !interacting) expanded = false
    }

    fun pressed(id: PointerId) {
        touches += id
        interacting = true
    }

    fun released() {
        interacting = false
    }

    /**
     * A touch ended, anywhere. After a tap a browser sends mouse events that put the mouse where
     * the finger was: over the toasts that spreads them out, anywhere else it folds them back.
     */
    fun touchEnded(id: PointerId, tap: Boolean) {
        val onToasts = touches.remove(id)
        if (!tap) return
        if (onToasts) expanded = true else if (!interacting) expanded = false
    }
}

/** Every touch's end, wherever it lands, for [ToasterState.touchEnded]: on an ancestor of the toaster. */
internal fun Modifier.toasterTaps(state: ToasterState): Modifier = pointerInput(state) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.type == PointerType.Mouse) return@awaitEachGesture
        // After every handler under it, so that a toast has seen the touch go down on it.
        state.touchEnded(down.id, liftsAsTap(down, PointerEventPass.Final))
    }
}

/**
 * The web's toaster: sonner 2.0.7 as PPM sets it up, `closeButton`, `top-center` on a phone and
 * `bottom-left` on the desktop layout. The newest toast is in front with two more peeking out
 * behind it; a mouse over them or a tap on them spreads them out, and while they are spread out,
 * pressed or out of sight no toast's four seconds run. Up to 600 px wide a phone's toasts span
 * the window 16 px in from its sides and 16 px down; wider, they are 356 px wide, 24 px from the
 * edge they sit on.
 */
@Composable
internal fun Toaster(toasts: List<AppToast>, state: ToasterState, mobile: Boolean, narrow: Boolean, dark: Boolean, onGone: (AppToast) -> Unit) {
    val hidden = !LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.STARTED)
    val listed = toasts.toList()
    // Folded once one toast or none is left, looked at on every change to the list as sonner does.
    LaunchedEffect(listed) { if (listed.size <= 1) state.expanded = false }
    val placement = when {
        !mobile -> Modifier.safeDrawingPadding().padding(24.dp)
        narrow -> Modifier.statusBarsPadding().padding(16.dp)
        else -> Modifier.statusBarsPadding().padding(top = 24.dp)
    }
    val gap = with(LocalDensity.current) { GAP.toPx() }
    val heights = state.heights
    // `heights`: the toasts still up whose height is known, in the stack's order.
    val measured = listed.filter { !it.dismissed && heights[it.id] != null }
    val frontHeight = measured.firstOrNull()?.let { heights.getValue(it.id) } ?: 0f
    Box(Modifier.fillMaxSize().then(placement), contentAlignment = if (mobile) Alignment.TopCenter else Alignment.BottomStart) {
        listed.forEachIndexed { index, toast ->
            key(toast.id) {
                val before = measured.indexOf(toast)
                // `heightIndex * gap + toastsHeightBefore`: a gap above the front for a toast not in `heights`.
                val offset = before * gap + (0 until before).fold(0f) { sum, it -> sum + heights.getValue(measured[it].id) }
                ToastSlot(toast, index, listed.size, offset, frontHeight, state, mobile, narrow, dark, hidden) {
                    heights.remove(toast.id)
                    onGone(toast)
                }
            }
        }
    }
}

/**
 * One toast in the stack (sonner's `Toast`): where sonner's rules put it for where it is in the
 * stack, moved there over sonner's transitions, its clock, its swipe, and its leaving.
 */
@Composable
private fun ToastSlot(
    toast: AppToast,
    index: Int,
    count: Int,
    offset: Float,
    frontHeight: Float,
    state: ToasterState,
    mobile: Boolean,
    narrow: Boolean,
    dark: Boolean,
    hidden: Boolean,
    onGone: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val gap = with(LocalDensity.current) { GAP.toPx() }
    val message = toast.message
    val expanded = state.expanded
    // `mounted`: drawn once where it comes in from, then moved into place.
    var mounted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { mounted = true }
    val removed = toast.dismissed
    val front = index == 0
    val visible = index < VISIBLE_TOASTS
    val behind = !expanded && !front
    val top = mobile
    val lift = if (top) 1f else -1f
    val swipe = remember { ToastSwipe() }
    // `offsetBeforeRemove`: where it stood in the stack, kept while it leaves.
    val kept = remember { floatArrayOf(0f) }
    if (!removed) kept[0] = offset
    val at = if (removed) kept[0] else offset
    val leaving = removed && !swipe.out
    // `--y`: translateY in px, translateY in its own height, scale. sonner's rules, the most specific first.
    val y = when {
        leaving && front -> floatArrayOf(0f, -lift, 1f)
        leaving && expanded -> floatArrayOf(lift * at, -lift, 1f)
        leaving -> floatArrayOf(0f, 0.4f, 1f)
        behind -> floatArrayOf(lift * gap * index, 0f, 1f - 0.05f * index)
        mounted && expanded -> floatArrayOf(lift * at, 0f, 1f)
        mounted -> floatArrayOf(0f, 0f, 1f)
        else -> floatArrayOf(0f, -lift, 1f)
    }
    val opacity = if (leaving || !visible || !mounted) 0f else 1f
    // `--front-toast-height` behind the front, `--initial-height` spread out, `auto` (null) else.
    val height: Float? = when {
        behind -> frontHeight
        mounted && expanded -> state.heights[toast.id]
        else -> null
    }
    val transform = remember { CssTransition(*y) }
    val alpha = remember { CssTransition(opacity) }
    val boxHeight = remember { CssTransition(0f) }
    val shownAuto = remember { booleanArrayOf(true) }
    // `[data-front=false][data-expanded=false] > *`: what it says is hidden behind the front.
    val children = remember { CssTransition(if (behind) 0f else 1f) }
    val close = remember { CssTransition(if (behind) 0f else 1f) }
    // `transition: transform .4s, opacity .4s, height .4s`; one leaving from behind the front drops
    // over `transform .5s, opacity .2s`, height left out; none while a finger is on it.
    val dropping = leaving && !front && !expanded
    val still = swipe.down
    SideEffect {
        transform.to(y, if (still) 0 else if (dropping) 500 else 400)
        alpha.to(floatArrayOf(opacity), if (still) 0 else if (dropping) 200 else 400)
        // `auto` is no length to move from or to.
        if (height != null) boxHeight.to(floatArrayOf(height), if (still || dropping || shownAuto[0]) 0 else 400)
        shownAuto[0] = height == null
        children.to(floatArrayOf(if (behind) 0f else 1f), 400)
        // The close button's own `transition: opacity .1s`.
        close.to(floatArrayOf(if (behind) 0f else 1f), 100)
    }
    LaunchedEffect(Unit) {
        for (transition in listOf(transform, alpha, boxHeight, children, close, swipe.shift, swipe.fade)) launch { transition.run() }
    }

    // Four seconds, or the toast's own `duration`, held while the toasts are spread out, pressed or out of sight; a persistent one stays.
    val left = remember { longArrayOf(message.durationMillis ?: TOAST_LIFETIME) }
    val paused = expanded || state.interacting || hidden
    LaunchedEffect(paused, message, removed) {
        if (paused || message.persistent || removed) return@LaunchedEffect
        val start = SystemClock.uptimeMillis()
        try {
            delay(left[0])
            toast.dismiss()
        } finally {
            left[0] -= SystemClock.uptimeMillis() - start
        }
    }
    // Off the stack once it has had `TIME_BEFORE_UNMOUNT` to leave.
    if (removed) {
        LaunchedEffect(Unit) {
            delay(TIME_BEFORE_UNMOUNT)
            onGone()
        }
    }

    val shape = RectangleShape
    val reach = if (mobile) 12.dp else 0.dp
    // `[data-visible=false]`: `pointer-events: none`.
    val interactive = visible
    Layout(
        content = {
            // The toast's own box, as tall as the stack makes it: sonner's surface, border and
            // shadow. `--border-radius: var(--radius)`, and PPM defines no `--radius`: sonner's
            // corners compute to 0. The web's quirk, kept.
            Box(Modifier.ppmShadow(TOAST_SHADOW, shape).background(tokens.panel2.color, shape).cssBorder(tokens.border.color, shape))
            ToastBody(
                message,
                dark,
                interactive,
                onAction = { action ->
                    action.run()
                    toast.dismiss(byAction = true)
                },
                Modifier.graphicsLayer { this.alpha = children[0] },
            )
            ToastClose(
                dark,
                mobile,
                interactive,
                onClose = toast::dismiss,
                Modifier
                    .graphicsLayer { this.alpha = close[0] }
                    .then(if (interactive) Modifier.toasterRegion(state, toast.id to CLOSE) else Modifier),
            )
            // `[data-expanded=true]::after`: the gap above a spread-out toast still counts as on it.
            if (expanded && interactive) Box(Modifier.toasterRegion(state, toast.id to STRIP))
        },
        modifier = (if (mobile && narrow) Modifier.fillMaxWidth() else Modifier.width(TOAST_WIDTH))
            .zIndex((count - index).toFloat())
            .graphicsLayer {
                val shift = transform[0] + transform[1] * size.height
                // Chrome puts a box moved by a translation alone on whole pixels.
                translationY = if (transform[2] == 1f) shift.roundToInt().toFloat() else shift
                scaleX = transform[2]
                scaleY = transform[2]
                this.alpha = alpha[0]
                // Faded without a layer of its own, which would cut the shadow and the close button off at its edges.
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .then(if (interactive) Modifier.toasterRegion(state, toast.id to BOX).toastSwipe(swipe, top, toast) else Modifier)
            .graphicsLayer {
                translationX = swipe.shift[0]
                translationY = swipe.shift[1]
                this.alpha = swipe.fade[0]
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        // Its content at its own height, centred in the box (`align-items: center`) when the box is not.
        val (body, natural) = measurables[1].cssMeasure(Constraints.fixedWidth(width), at = 0f)
        if (state.heights[toast.id] != natural) state.heights[toast.id] = natural
        val boxPx = if (height == null) body.height else boxHeight[0].roundToInt()
        val surface = measurables[0].measure(Constraints.fixed(width, boxPx))
        val closeButton = measurables[2].measure(Constraints())
        val strip = measurables.getOrNull(3)?.measure(Constraints.fixed(width, (GAP + 1.dp).roundToPx()))
        layout(width, boxPx) {
            surface.place(0, 0)
            body.place(0, ((boxPx - body.height) / 2f).roundToInt())
            // `closeButton`: a 20 px circle on the top-left corner, `translate(-35%, -35%)`; on a phone
            // it takes taps 12 px around it, a 44 px target (globals.css).
            val corner = (-6).dp.roundToPx() - reach.roundToPx()
            closeButton.place(corner, corner)
            strip?.place(0, -strip.height)
        }
    }
}

/** The icon, the title and the description under it, and the action at the end. */
@Composable
private fun ToastBody(message: ErrorToastMessage, dark: Boolean, interactive: Boolean, onAction: (ErrorToastMessage.Action) -> Unit, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    // `--normal-bg`, the popover colour: `--panel-2`.
    val surface = tokens.panel2
    // The icons PPM gives sonner (components/ui/sonner.tsx).
    val icon = when (message.kind) {
        ErrorToastMessage.Kind.INFO -> ProductIcon.InfoIcon
        ErrorToastMessage.Kind.SUCCESS -> ProductIcon.CircleCheckIcon
        ErrorToastMessage.Kind.WARNING -> ProductIcon.TriangleAlertIcon
        ErrorToastMessage.Kind.PLAIN -> null
        ErrorToastMessage.Kind.ERROR -> ProductIcon.OctagonXIcon
    }
    // sonner's flex row, `gap: 6px` and `padding: 16px` (less the 3 px the icon's `margin-left: -3px`
    // takes back), kept in Chrome's fractions: each rounded on its own put the text 147 px in at
    // 2.625x where Chrome puts it at 146.4. The toast counts the row at its exact height.
    CssRow(
        modifier.fillMaxWidth().cssBorderInset(1.dp),
        gap = 6.dp,
        start = if (icon != null) 13.dp else 16.dp,
        end = 16.dp,
        top = 16.dp,
        bottom = 16.dp,
    ) {
        // `[data-icon]`: 16 px and `margin-right: 4px`, its svg `margin-left: -1px`.
        if (icon != null) {
            Box(Modifier.cssWidth(20.dp)) {
                PpmIcon(icon, 16.dp, tokens.text.color, Modifier.offset(x = (-1).dp))
            }
        }
        // `[data-content]`, `gap: 2px`: the title at 500 on a 1.5 line, the description at 400
        // on 1.4, in sonner's own grey. As tall as Chrome makes those lines, 192.2 px for both at
        // 2.625x, where each rounded up on its own would make the toast a pixel taller.
        CssColumn(Modifier.cssFlex(), gap = 2.dp) {
            BasicText(message.title, Modifier.cssLines(19.5f), style = sonnerText(13f, FontWeight.Medium, lineHeight = 19.5f).copy(color = tokens.text.color))
            message.description?.let {
                BasicText(it, Modifier.cssLines(18.2f), style = sonnerText(13f, FontWeight.Normal, lineHeight = 18.2f).copy(color = (if (dark) SONNER_DESCRIPTION_DARK else SONNER_DESCRIPTION).color))
            }
        }
        // `[data-cancel]`: secondary action button (`toastOpts.cancel` in `use-extension-ws.ts`).
        message.cancel?.let { cancel ->
            CssRow(
                Modifier
                    .height(24.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(tokens.border.color)
                    .then(
                        if (interactive) {
                            Modifier
                                .pointerHoverIcon(WebCursor.Pointer)
                                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { onAction(cancel) }
                        } else {
                            Modifier
                        },
                    ),
                start = 8.dp,
                end = 8.dp,
            ) {
                CssText(cancel.label, sonnerText(12f, FontWeight.Medium, lineHeight = 18f).copy(color = tokens.text.color))
            }
        }
        // `[data-button]`, `margin-left: auto`: 24 tall, the toast's colours swapped. It does what it
        // says, then closes the toast.
        message.action?.let { action ->
            CssRow(
                Modifier
                    .height(24.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(tokens.text.color)
                    .then(
                        if (interactive) {
                            Modifier
                                .pointerHoverIcon(WebCursor.Pointer)
                                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { onAction(action) }
                        } else {
                            Modifier
                        },
                    ),
                start = 8.dp,
                end = 8.dp,
            ) {
                CssText(action.label, sonnerText(12f, FontWeight.Medium, lineHeight = 18f).copy(color = surface.color))
            }
        }
    }
}

/**
 * `closeButton`: sonner's own colours under a mouse (and a finger), faded over its 200 ms `ease`,
 * and its `cursor: pointer`. On a phone it takes taps 12 px around it.
 */
@Composable
private fun ToastClose(dark: Boolean, mobile: Boolean, interactive: Boolean, onClose: () -> Unit, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    val surface = tokens.panel2
    val hover = rememberHover(instant = true)
    val colors = SonnerClose.of(dark, tokens.border, tokens.text)
    val lit by animateFloatAsState(hover.lit, tween(200, easing = SONNER_EASE), label = "close")
    val pressed by hover.source.collectIsPressedAsState()
    val reach = if (mobile) 12.dp else 0.dp
    Box(
        modifier
            .then(
                if (interactive) {
                    Modifier
                        .pointerHoverIcon(WebCursor.Pointer)
                        .hoverable(hover.source)
                        .clickable(hover.source, indication = null, onClickLabel = "Close toast", role = Role.Button, onClick = onClose)
                } else {
                    Modifier
                },
            )
            .padding(reach)
            .size(20.dp)
            .clip(CircleShape)
            .background((if (pressed) colors.hoverFill else colors.hoverFill.mix(surface, lit.toDouble())).color)
            .cssBorder(colors.hoverBorder.mix(colors.border, lit.toDouble()).color, CircleShape),
        contentAlignment = Alignment.Center,
    ) { PpmIcon(ProductIcon.X, 12.dp, colors.icon.color) }
}

/**
 * A part of the toasts that counts as on them: a mouse over it spreads them out and off the last
 * part folds them back, and a press on it holds them (`onMouseEnter` / `onMouseLeave` /
 * `onPointerDown` / `onPointerUp` on sonner's list).
 */
private fun Modifier.toasterRegion(state: ToasterState, region: Any): Modifier = pointerInput(state, region) {
    try {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                when (event.type) {
                    PointerEventType.Enter, PointerEventType.Move -> if (event.changes.any { it.type == PointerType.Mouse }) state.mouseOver(region)
                    PointerEventType.Exit -> state.mouseOff(region)
                    PointerEventType.Press -> event.changes.forEach { if (it.changedToDownIgnoreConsumed()) state.pressed(it.id) }
                    PointerEventType.Release -> state.released()
                }
            }
        }
    } finally {
        // Gone from under the mouse with it.
        state.mouseOff(region)
    }
}

/** A finger dragging a toast (`swiping`), how far, and whether it flung the toast away (`swipeOut`). */
@Stable
private class ToastSwipe {
    var down by mutableStateOf(false)
    var out by mutableStateOf(false)

    /** `--swipe-amount-x` / `-y`, then the swipe-out keyframes, in px. */
    val shift = CssTransition(0f, 0f)

    /** The swipe-out keyframes' opacity. */
    val fade = CssTransition(1f)
}

/**
 * sonner's swipe: the toast follows a finger toward the edges its position names (`top-center`
 * up, `bottom-left` down and left) and only a little the other way, and goes once it is dragged
 * [SWIPE_THRESHOLD] px or flung faster than 0.11 px a millisecond; short of that it slides back.
 * A press that starts on one of its buttons is the button's.
 */
private fun Modifier.toastSwipe(swipe: ToastSwipe, top: Boolean, toast: AppToast): Modifier = pointerInput(swipe) {
    awaitEachGesture {
        val down = awaitFirstDown()
        if (swipe.out) return@awaitEachGesture
        swipe.down = true
        var horizontal: Boolean? = null
        var amountX = 0f
        var amountY = 0f
        try {
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (!change.pressed) {
                    val amount = if (horizontal == true) amountX else amountY
                    val velocity = abs(amount) / (change.uptimeMillis - down.uptimeMillis).coerceAtLeast(1)
                    if (abs(amount) >= SWIPE_THRESHOLD || velocity > SWIPE_VELOCITY) {
                        // Out through the side it was swiped to, its own size further, as it fades.
                        swipe.out = true
                        toast.dismiss()
                        val shift = if (horizontal == true) {
                            floatArrayOf(swipe.shift[0] + (if (amountX > 0) size.width else -size.width), swipe.shift[1])
                        } else {
                            floatArrayOf(swipe.shift[0], swipe.shift[1] + (if (amountY > 0) size.height else -size.height))
                        }
                        swipe.shift.to(shift, 200, EASE_OUT)
                        swipe.fade.to(floatArrayOf(0f), 200, EASE_OUT)
                    }
                    return@awaitEachGesture
                }
                val dx = (change.position.x - down.position.x) / density
                val dy = (change.position.y - down.position.y) / density
                if (horizontal == null && (abs(dx) > 1 || abs(dy) > 1)) horizontal = abs(dx) > abs(dy)
                when (horizontal) {
                    false -> amountY = if (if (top) dy < 0 else dy > 0) dy else dampened(dy)
                    // `top-center` names no side; `bottom-left` the left one.
                    true -> amountX = if (top) 0f else if (dx < 0) dx else dampened(dx)
                    null -> Unit
                }
                swipe.shift.to(floatArrayOf(amountX * density, amountY * density), 0)
            }
        } finally {
            if (!swipe.out) {
                swipe.down = false
                swipe.shift.to(floatArrayOf(0f, 0f), 400)
            }
        }
    }
}

/** A drag the wrong way, held back the further it goes. */
private fun dampened(delta: Float): Float {
    val held = delta / (1.5f + abs(delta) / 20f)
    return if (abs(held) < abs(delta)) held else delta
}

/**
 * One CSS property under `transition`, run as Chrome runs it: a new value is reached from where
 * the property stands over the duration the new style gives it, a change back to where the
 * running transition started is shortened as CSS shortens a reversal, and a duration of 0 — the
 * property left out of `transition`, or `transition: none` — sets it at once. [run] moves it frame
 * by frame; a property of several numbers (a transform) moves them together.
 */
@Stable
private class CssTransition(vararg initial: Float) {
    private val shown = Array(initial.size) { mutableFloatStateOf(initial[it]) }
    private var start = initial.copyOf()
    private var end = initial.copyOf()
    private var reversingStart = initial.copyOf()
    private var factor = 1f
    private var easing: Easing = SONNER_EASE
    private var nanos = 0L
    private var began = -1L
    private var running = false
    private val wake = Channel<Unit>(Channel.CONFLATED)

    operator fun get(i: Int): Float = shown[i].floatValue

    fun to(target: FloatArray, millis: Int, easing: Easing = SONNER_EASE) {
        val now = FloatArray(shown.size) { shown[it].floatValue }
        if (millis <= 0) {
            running = false
            for (i in shown.indices) shown[i].floatValue = target[i]
            start = target.copyOf()
            end = target.copyOf()
            reversingStart = target.copyOf()
            factor = 1f
            return
        }
        if (target.contentEquals(if (running) end else now)) return
        if (running && target.contentEquals(reversingStart)) {
            factor = abs(progress(now) * factor + (1 - factor)).coerceIn(0f, 1f)
            reversingStart = end
        } else {
            factor = 1f
            reversingStart = now
        }
        start = now
        end = target.copyOf()
        this.easing = easing
        nanos = (millis * factor * 1_000_000).toLong()
        began = -1L
        running = true
        wake.trySend(Unit)
    }

    /** How far the running transition has come, as its timing function puts it. */
    private fun progress(now: FloatArray): Float {
        for (i in now.indices) {
            val span = end[i] - start[i]
            if (span != 0f) return (now[i] - start[i]) / span
        }
        return 1f
    }

    suspend fun run() {
        while (true) {
            if (!running) wake.receive()
            withFrameNanos { frame ->
                if (running) {
                    if (began < 0) began = frame
                    val t = if (nanos <= 0) 1f else ((frame - began).toFloat() / nanos).coerceIn(0f, 1f)
                    val eased = easing.transform(t)
                    for (i in shown.indices) shown[i].floatValue = start[i] + (end[i] - start[i]) * eased
                    if (t >= 1f) running = false
                }
            }
        }
    }
}

/**
 * sonner's close button: on a light toaster its own greys (`--gray4` ring, `--gray12` glyph,
 * `--gray2` / `--gray5` under a mouse); on a dark one the border and text PPM passes it, and its
 * own `--normal-bg-hover` / `--normal-border-hover`, which PPM does not.
 */
private class SonnerClose(val border: Rgba, val icon: Rgba, val hoverFill: Rgba, val hoverBorder: Rgba) {
    companion object {
        fun of(dark: Boolean, border: Rgba, text: Rgba) =
            if (dark) SonnerClose(border, text, grey(0.12), grey(0.25)) else SonnerClose(grey(0.93), grey(0.09), grey(0.973), grey(0.909))

        private fun grey(lightness: Double) = Rgba(lightness, lightness, lightness, 1.0)
    }
}

/** CSS `ease`. */
private val SONNER_EASE = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

/** CSS `ease-out`, the swipe-out keyframes'. */
private val EASE_OUT = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/**
 * sonner sets its own `font-family: ui-sans-serif, system-ui, …` on the toaster, so the web's
 * toasts are not in Geist: Chrome on Android draws them in the system font, on that font's
 * baseline (set on Geist's, a 13 px title sat 2 px low at 2x).
 */
private fun sonnerText(size: Float, weight: FontWeight, lineHeight: Float) = ppmText(size, weight, FontKind.SYSTEM, lineHeight)

/** `[data-description]`: `#3f3f3f`, and `hsl(0, 0%, 91%)` on a dark toaster. */
private val SONNER_DESCRIPTION = Rgba(63 / 255.0, 63 / 255.0, 63 / 255.0, 1.0)
private val SONNER_DESCRIPTION_DARK = Rgba(0.91, 0.91, 0.91, 1.0)

/** sonner's `0 4px 12px rgba(0, 0, 0, .1)`. */
private val TOAST_SHADOW = ThemeShadow(0.0, 4.0, 12.0, 0.0, Rgba(0.0, 0.0, 0.0, 0.1))

/** sonner's `VISIBLE_TOASTS_AMOUNT`, `GAP`, `TOAST_WIDTH`, `TOAST_LIFETIME`, `TIME_BEFORE_UNMOUNT` and swipe thresholds. */
private const val VISIBLE_TOASTS = 3
private val GAP = 14.dp
private val TOAST_WIDTH = 356.dp
private const val TOAST_LIFETIME = 4_000L
private const val TIME_BEFORE_UNMOUNT = 200L
private const val SWIPE_THRESHOLD = 45f
private const val SWIPE_VELOCITY = 0.11f

/** The parts of a toast a mouse can be over. */
private const val BOX = "box"
private const val CLOSE = "close"
private const val STRIP = "strip"
