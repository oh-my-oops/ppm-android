package com.hienlh.ppm.app.ui

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.hienlh.ppm.app.TextInputFocus
import com.hienlh.ppm.app.tracksTextFocus
import com.hienlh.ppm.design.CssColumn
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTheme
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFonts
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ThemeShadow
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssBorderInset
import com.hienlh.ppm.design.cssBorderPx
import com.hienlh.ppm.design.cssHeight
import com.hienlh.ppm.design.cssLines
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Tailwind's `shadow-xs`. */
internal val ShadowXs = ThemeShadow(0.0, 1.0, 2.0, 0.0, Rgba(0.0, 0.0, 0.0, 0.05))

/** Tailwind's `shadow-sm`, by its larger layer. */
private val ShadowSm = ThemeShadow(0.0, 1.0, 3.0, 0.0, Rgba(0.0, 0.0, 0.0, 0.1))

/**
 * What tapping a field's label does, as `htmlFor` ties a label to its control: it opens a select,
 * focuses an input, flips a switch. The control fills in [activate] as it is composed.
 */
class LabelTarget {
    internal var activate: () -> Unit = {}
}

@Composable
fun rememberLabelTarget(): LabelTarget = remember { LabelTarget() }

/** A compact panel's `Label` (`text-[11px] font-medium`; the size class takes `leading-none` away, so 11/16.5). */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier, target: LabelTarget? = null) {
    val tokens = LocalPpmTokens.current
    BasicText(
        text,
        (if (target == null) modifier else modifier.clickable(interactionSource = null, indication = null) { target.activate() }).cssLines(16.5f),
        style = ppmText(11f, FontWeight.Medium, lineHeight = 16.5f).copy(color = tokens.text.color),
    )
}

/** The line under a compact field (`text-[9px] text-muted-foreground`). */
@Composable
fun HelpText(text: String, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    BasicText(text, modifier.cssLines(13.5f), style = ppmText(9f, lineHeight = 13.5f).copy(color = tokens.text2.color))
}

/** One choice of a select: what is saved, and what it says. */
data class SelectOption(val value: String, val label: String)

/**
 * The web's compact `Select` (ui/select.tsx with `h-7 text-[11px]`, which `data-[size=default]:h-9`
 * outranks, so 36 tall): the chosen label and a chevron in a bordered box, and on a tap Radix's
 * list with the chosen row over the box ([SelectList]). A mouse opens it as it presses, a finger
 * as it lifts. [onValueChange] is not called for the value already chosen. With no option
 * matching [value] the box is empty, or says [placeholder] when there is no value at all.
 */
@Composable
fun SelectField(
    value: String?,
    options: List<SelectOption>,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String? = null,
    label: LabelTarget? = null,
) {
    val tokens = LocalPpmTokens.current
    val dark = LocalPpmTheme.current.isDark
    var open by remember { mutableStateOf(false) }
    val bounds = remember { arrayOf(Rect.Zero) }
    SideEffect { label?.activate = { if (enabled) open = true } }
    val shape = RoundedCornerShape(8.dp)
    // `dark:bg-input/30 dark:hover:bg-input/50`, not among the colours it transitions.
    val hover = rememberHover(enabled = enabled, instant = true)
    val chosen = options.firstOrNull { it.value == value }
    Box(modifier.cssHeight(36.dp).onGloballyPositioned { bounds[0] = it.boundsInWindow() }) {
        Row(
            Modifier
                .fillMaxSize()
                .alpha(if (enabled) 1f else 0.5f)
                .ppmShadow(ShadowXs, shape)
                .clip(shape)
                .background(if (dark) tokens.border.opacity(0.3 + 0.2 * hover.lit).color else Color.Transparent)
                .cssBorder(tokens.border.color, shape)
                .opensOnMousePress(enabled) { open = true }
                .ppmClickable(enabled = enabled, pressedColor = Color.Transparent, source = hover.source) { open = true }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                chosen?.label ?: if (value == null) placeholder.orEmpty() else "",
                Modifier.weight(1f).padding(end = 8.dp),
                style = ppmText(11f, lineHeight = 16.5f).copy(color = (if (chosen == null) tokens.text2 else tokens.text).color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            PpmIcon(ProductIcon.ChevronDown, 16.dp, tokens.text2.color, Modifier.alpha(0.5f))
        }
        if (open && options.isNotEmpty()) {
            SelectList(
                bounds[0],
                value,
                options,
                onPick = { picked ->
                    open = false
                    if (picked != value) onValueChange(picked)
                },
                onDismiss = { open = false },
            )
        }
    }
}

/** A mouse's primary button opens it as it goes down, as Radix's `pointerdown` does for a mouse only. */
@Composable
private fun Modifier.opensOnMousePress(enabled: Boolean, open: () -> Unit): Modifier {
    val current by rememberUpdatedState(open)
    return pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.type == PointerType.Mouse && currentEvent.buttons.isPrimaryPressed) current()
        }
    }
}

/**
 * Where Radix's item-aligned `SelectContent` goes (SelectItemAlignedPosition), in pixels: its
 * wrapper pinned by [top] or by [bottom], [height] tall as its style says (the used height is
 * [usedHeight]), and the list scrolled to [scroll] as it opens.
 */
internal data class SelectPlacement(
    val top: Float?,
    val bottom: Float?,
    val height: Float,
    val minHeight: Float,
    val maxHeight: Float,
    val scroll: Float,
) {
    /** `height` within `min-height` and `max-height`, the minimum winning as CSS has it. */
    fun usedHeight(height: Float = this.height): Float = max(min(height, maxHeight), minHeight)
}

/**
 * Radix's placement of a select's list so that the chosen row's middle is level with the
 * trigger's: under it when the rows above the chosen one fit above the trigger's middle, the
 * wrapper's bottom pinned 10 px off the viewport's; otherwise pinned at the top, the list
 * scrolled to bring the row level. [border] is the content's top border as Radix reads it,
 * `parseInt` of its computed width, so 0 for a 1 px border at 2.625x. A scroll button that
 * appears makes Radix place it again, once, with the button in the way ([button] tall).
 */
internal fun placeSelect(
    triggerTop: Float,
    triggerHeight: Float,
    viewTop: Float,
    viewBottom: Float,
    item: Float,
    pad: Float,
    margin: Float,
    button: Float,
    border: Float,
    count: Int,
    selected: Int,
): SelectPlacement {
    val available = viewBottom - viewTop - 2 * margin
    val full = border + pad + count * item + pad + border
    val minHeight = min(item * 5, full)
    val topToTriggerMiddle = triggerTop + triggerHeight / 2 - viewTop - margin
    val triggerMiddleToBottom = available - topToTriggerMiddle
    val half = item / 2
    val toItemMiddle = border + pad + selected * item + half
    if (toItemMiddle <= topToTriggerMiddle) {
        val last = selected == count - 1
        fun height(offsetBottom: Float) = toItemMiddle + max(triggerMiddleToBottom, half + (if (last) pad else 0f) + offsetBottom + border)
        var height = height(0f)
        // Clipped, it shows the scroll-down button under the rows.
        if (max(min(height, available), minHeight) < full) height = height(button)
        return SelectPlacement(null, viewBottom - margin, height, minHeight, available, 0f)
    }
    val first = selected == 0
    // The list starts scrolled, so the scroll-up button shows above it.
    val height = max(topToTriggerMiddle, border + button + (if (first) pad else 0f) + half) + (full - toItemMiddle)
    return SelectPlacement(viewTop + margin, null, height, minHeight, available, toItemMiddle - topToTriggerMiddle + button)
}

/**
 * Radix's open select list (`SelectContent`, item-aligned): opaque `bg-popover` outlined in the
 * text colour, `rounded-md shadow-md`, rows `py-1.5 pl-2 pr-8 text-sm` with the chosen one's
 * check in `text-muted-foreground`. It fades and grows in from 95% over 150 ms and goes at once,
 * as Radix unmounts it. Rows light in `bg-accent`, the list's own fill, so no row ever shows lit.
 * Scrolling it first makes it taller, up to the viewport, as Radix's expand-on-scroll does; the
 * arrows at its ends scroll it a row every 50 ms while pressed. A tap outside only closes it.
 */
@Composable
private fun SelectList(
    trigger: Rect,
    value: String?,
    options: List<SelectOption>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val openedIn = remember { window }
    // Radix closes it when the viewport is resized.
    if (window != openedIn) LaunchedEffect(Unit) { onDismiss() }
    // The page's viewport: under the status bar, and down to the screen's bottom, as Chrome's
    // reaches under the gesture bar.
    val viewTop = WindowInsets.statusBars.getTop(density).toFloat()
    val item = with(density) { 32.dp.toPx() }
    val margin = with(density) { 10.dp.toPx() }
    val placement = remember {
        with(density) {
            val border = floor(cssBorderPx(1.dp) / density.density) * density.density
            val selected = options.indexOfFirst { it.value == value }.coerceAtLeast(0)
            placeSelect(trigger.top, trigger.height, viewTop, window.height.toFloat(), item, 4.dp.toPx(), margin, 24.dp.toPx(), border, options.size, selected)
        }
    }
    val scroll = rememberScrollState(placement.scroll.roundToInt())
    var cssHeight by remember { mutableFloatStateOf(placement.height) }
    var pinnedEnd by remember { mutableStateOf(false) }
    LaunchedEffect(scroll) {
        // Radix turns it on a frame after placing the list.
        withFrameNanos {}
        var previous = scroll.value
        snapshotFlow { scroll.value }.collect { now ->
            val by = abs(now - previous).toFloat()
            val before = max(placement.minHeight, cssHeight)
            if (by > 0 && before < placement.maxHeight) {
                val next = before + by
                cssHeight = min(placement.maxHeight, next)
                if (placement.bottom != null) {
                    // Pinned to the bottom, it grows upwards and stays scrolled to the top.
                    scroll.dispatchRawDelta(max(0f, next - cssHeight) - scroll.value)
                    pinnedEnd = true
                }
            }
            previous = scroll.value
        }
    }
    val used = placement.usedHeight(cssHeight)
    val place = remember(used, pinnedEnd) {
        { _: IntRect, area: IntSize, box: IntSize ->
            val edge = margin.roundToInt()
            val x = trigger.left.roundToInt().coerceIn(edge, max(edge, area.width - edge - box.width))
            val y = when {
                placement.top != null -> placement.top
                pinnedEnd -> placement.bottom!! - box.height
                else -> placement.bottom!! - used
            }
            IntOffset(x, y.roundToInt())
        }
    }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(150, easing = CssEase)) }
    val shape = RoundedCornerShape(8.dp)
    PlacedPopup(place, onDismiss) {
        Column(
            Modifier
                .graphicsLayer {
                    alpha = appear.value
                    scaleX = 0.95f + 0.05f * appear.value
                    scaleY = scaleX
                }
                .width(IntrinsicSize.Max)
                .widthIn(min = with(density) { trigger.width.toDp() })
                .heightIn(max = with(density) { used.toDp() })
                .ppmShadow(shadowMd, shape)
                .clip(shape)
                .background(tokens.panel2.over(tokens.bgSolid).color)
                .cssBorder(tokens.text.color, shape),
        ) {
            if (scroll.canScrollBackward) ScrollButton(up = true, scroll, item)
            Column(Modifier.weight(1f, fill = false).verticalScroll(scroll).padding(4.dp)) {
                for (option in options) {
                    Box(Modifier.fillMaxWidth().height(32.dp).ppmClickable(pressedColor = Color.Transparent) { onPick(option.value) }) {
                        BasicText(
                            option.label,
                            Modifier.align(Alignment.CenterStart).padding(start = 8.dp, end = 32.dp),
                            style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text.color),
                        )
                        if (option.value == value) {
                            // `absolute right-2 size-3.5`, the 16 px check overflowing it evenly.
                            Box(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).size(14.dp), contentAlignment = Alignment.Center) {
                                PpmIcon(ProductIcon.Check, 16.dp, tokens.text2.color, Modifier.requiredSize(16.dp))
                            }
                        }
                    }
                }
            }
            if (scroll.canScrollForward) ScrollButton(up = false, scroll, item)
        }
    }
}

/**
 * `SelectScrollUpButton` / `SelectScrollDownButton` (`py-1`, a 16 px chevron): pressed, or with
 * a mouse over it, it scrolls the list a row every 50 ms until the pointer leaves.
 */
@Composable
private fun ScrollButton(up: Boolean, scroll: ScrollState, step: Float) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .fillMaxWidth()
            .pointerInput(up) {
                awaitPointerEventScope {
                    var job: Job? = null
                    while (true) {
                        val event = awaitPointerEvent()
                        val mouse = event.changes.any { it.type == PointerType.Mouse }
                        val starts = event.type == PointerEventType.Press || (event.type == PointerEventType.Move && mouse)
                        val ends = event.type == PointerEventType.Exit || (event.type == PointerEventType.Release && !mouse)
                        if (starts && job == null) {
                            job = scope.launch {
                                while (true) {
                                    delay(50)
                                    scroll.dispatchRawDelta(if (up) -step else step)
                                }
                            }
                        } else if (ends) {
                            job?.cancel()
                            job = null
                        }
                    }
                }
            }
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(if (up) ProductIcon.ChevronUp else ProductIcon.ChevronDown, 16.dp, tokens.text.color)
    }
}

/**
 * The web's `Switch` (`h-5 w-9 rounded-full border-2 border-transparent shadow-sm`): the accent
 * when on, `bg-input` when off, a 16 px `bg-background` thumb with `shadow-lg` sliding 16 px; both
 * move over 150 ms. Half opacity while not [enabled].
 */
@Composable
fun PpmSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true, label: LabelTarget? = null) {
    val tokens = LocalPpmTokens.current
    val on by animateFloatAsState(if (checked) 1f else 0f, tween(150, easing = FastOutSlowInEasing), label = "switch")
    SideEffect { label?.activate = { if (enabled) onCheckedChange(!checked) } }
    Box(
        Modifier
            .size(36.dp, 20.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .ppmShadow(ShadowSm, CircleShape)
            .clip(CircleShape)
            .background(tokens.accent.mix(tokens.border, on.toDouble()).color)
            .ppmClickable(enabled = enabled, pressedColor = Color.Transparent, role = Role.Switch) { onCheckedChange(!checked) }
            .cssBorderInset(2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = 16.dp * on)
                .size(16.dp)
                .ppmShadow(shadowLg, CircleShape)
                .clip(CircleShape)
                .background(tokens.bgSolid.color),
        )
    }
}

/** What an input takes: its keyboard, the characters it accepts, its face. */
enum class InputKind { Url, Integer, Decimal, Password }

/** `<input type=number>` takes the characters of a number and nothing else. */
private val NumberCharacters = InputTransformation {
    if (!asCharSequence().all { it.isDigit() || it in ".-+eE" }) revertAllChanges()
}

/**
 * The web's `Input` (`rounded-lg border bg-surface px-3`, the border the accent while focused) at
 * [height]: 16/24 on a phone (globals.css), and `md:text-sm`, 14/20, from there; the API key's
 * `font-mono` with its text hidden as a password field hides it. Selected text sits on the accent
 * (`selection:bg-primary`).
 */
@Composable
fun FormInput(
    state: TextFieldState,
    placeholder: String,
    modifier: Modifier = Modifier,
    kind: InputKind = InputKind.Url,
    height: Dp = 28.dp,
    label: LabelTarget? = null,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    SideEffect {
        label?.activate = {
            TextInputFocus.asked()
            focus.requestFocus()
        }
    }
    val face = if (kind == InputKind.Password) FontKind.MONO else FontKind.SANS
    val style = if (mobile) ppmText(16f, kind = face, lineHeight = 24f) else ppmText(14f, kind = face, lineHeight = 20f)
    fun focusChanged(now: Boolean) {
        if (now == focused) return
        focused = now
        onFocusChanged(now)
    }
    val shape = RoundedCornerShape(12.dp)
    val selection = LocalTextSelectionColors.current
    Box(
        modifier
            .fillMaxWidth()
            .cssHeight(height)
            .clip(shape)
            .background(tokens.panel.color)
            .cssBorder((if (focused) tokens.accent else tokens.border).color, shape)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(selection.handleColor, tokens.accent.color)) {
            if (kind == InputKind.Password) {
                BasicSecureTextField(
                    state = state,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .onFocusChanged { focusChanged(it.isFocused) }
                        .tracksTextFocus(state),
                    textStyle = style.copy(color = tokens.text.color),
                    cursorBrush = SolidColor(tokens.accent.color),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    decorator = { field ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (state.text.isEmpty()) {
                                BasicText(placeholder, style = style.copy(color = tokens.text2.color), softWrap = false, maxLines = 1, overflow = TextOverflow.Clip)
                            }
                            field()
                        }
                    },
                )
            } else {
                PpmTextField(
                    state,
                    placeholder,
                    Modifier.fillMaxWidth(),
                    style = style,
                    placeholderColor = tokens.text2.color,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = when (kind) {
                            InputKind.Integer -> KeyboardType.Number
                            InputKind.Decimal -> KeyboardType.Decimal
                            else -> KeyboardType.Uri
                        },
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Next,
                    ),
                    inputTransformation = if (kind == InputKind.Url) null else NumberCharacters,
                    focusRequester = focus,
                    onFocusChanged = ::focusChanged,
                    placeholderOverflow = TextOverflow.Clip,
                )
            }
        }
    }
}

/**
 * The AI panel's `textarea` (`rows={3} rounded-md border-input bg-background px-3 py-2`): three
 * lines tall at 16/24 on a phone, 11/16.5 elsewhere, scrolling inside past that. Focused, it wears
 * `ring-2 ring-offset-2`: a 2 px band of the page colour, then 2 px of the accent. Chrome's
 * resizer sits in its corner, and dragging it changes the height (`resize: vertical`). A textarea
 * is inline-block, so it sits on the baseline of the panel's 16/24 line, and the part of that
 * line under the baseline stays under it: 18 px at 2.625x.
 */
@Composable
fun FormTextArea(
    state: TextFieldState,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: LabelTarget? = null,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val density = LocalDensity.current
    val fontSize = if (mobile) 16f else 11f
    val line = fontSize * 1.5f
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    SideEffect {
        label?.activate = {
            TextInputFocus.asked()
            focus.requestFocus()
        }
    }
    val border = with(density) { cssBorderPx(1.dp) }
    val natural = with(density) { (3 * line).sp.toPx() + 16.dp.toPx() } + 2 * border
    // Blink stops a resize at 15 px of content.
    val smallest = with(density) { 15.dp.toPx() + 16.dp.toPx() } + 2 * border
    var resized by remember { mutableStateOf<Float?>(null) }
    val height = resized ?: natural
    val shape = RoundedCornerShape(8.dp)
    val strut = with(density) { PpmFonts.chromeBelowBaseline(16.sp.toPx(), 24.sp.toPx(), PpmFonts.SANS_ASCENT, PpmFonts.SANS_DESCENT).toDp() }
    CssColumn(modifier, bottom = strut) {
        Box(
            Modifier
                .fillMaxWidth()
                .cssHeight(with(density) { height.toDp() })
                .drawBehind {
                    if (!focused) return@drawBehind
                    val radius = 8.dp.toPx()
                    val band = 2.dp.toPx()
                    drawRoundRect(tokens.accent.color, Offset(-2 * band, -2 * band), Size(size.width + 4 * band, size.height + 4 * band), CornerRadius(radius + 2 * band))
                    drawRoundRect(tokens.bgSolid.color, Offset(-band, -band), Size(size.width + 2 * band, size.height + 2 * band), CornerRadius(radius + band))
                }
                .clip(shape)
                .background(tokens.bgSolid.color)
                .cssBorder(tokens.border.color, shape),
        ) {
            PpmTextField(
                state,
                placeholder,
                Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                style = ppmText(fontSize, lineHeight = line),
                placeholderColor = tokens.text2.color,
                lineLimits = TextFieldLineLimits.MultiLine(),
                focusRequester = focus,
                onFocusChanged = {
                    if (it != focused) {
                        focused = it
                        onFocusChanged(it)
                    }
                },
            )
            ResizeGrip { dy -> resized = max(smallest, height + dy) }
        }
    }
}

/**
 * Blink's resizer (`DrawPlatformResizerImage`): two strokes from the corner square's top right
 * to its bottom left, and from halfway down its right side to halfway along its bottom, each
 * black at 60% with a white one at 60% a stroke's width below it. Measured in Chrome at 2.625x,
 * the square is about 7.5 px, 1 px in from the padding box's corner.
 */
@Composable
private fun BoxScope.ResizeGrip(onDrag: (Float) -> Unit) {
    val drag by rememberUpdatedState(onDrag)
    Box(
        Modifier
            .align(Alignment.BottomEnd)
            .size(24.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, dy ->
                    change.consume()
                    drag(dy)
                }
            }
            .drawBehind {
                val e = ceil(density).toFloat()
                val side = 7.5.dp.toPx()
                val right = size.width - 1.dp.toPx()
                val bottom = size.height - 1.dp.toPx()
                val left = right - side
                val top = bottom - side
                val stroke = Stroke(e, cap = StrokeCap.Butt)
                fun lines(dx: Float, dy: Float, color: Color) {
                    val path = Path().apply {
                        moveTo(right - e, top + dy)
                        lineTo(left + dx, bottom - e)
                        moveTo(right - e, top + side / 2 + dy)
                        lineTo(right - side / 2 + dx, bottom - e)
                    }
                    drawPath(path, color, style = stroke)
                }
                lines(0f, 0f, Color.Black.copy(alpha = 0.6f))
                lines(e, e, Color.White.copy(alpha = 0.6f))
            },
    )
}

/**
 * A native `<select>` as Chrome draws the Codex context form's (`min-h-11 sm:min-h-0 h-7 rounded-md
 * border-input bg-background px-3`): 44 tall on a phone, 28 from 640 px; 16 px text on a phone
 * (globals.css), 11 px elsewhere, on a `line-height: normal` line; Chrome's own 4 px before the
 * text and its chevron at the end. Focused it wears `ring-2` in the accent, and it keeps the focus
 * once its list closes, as Chrome's does. A tap opens Chrome's list ([ChromeSelectDialog]).
 */
@Composable
fun NativeSelect(
    value: String,
    options: List<SelectOption>,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: LabelTarget? = null,
) {
    val tokens = LocalPpmTokens.current
    val density = LocalDensity.current
    val mobile = LocalPpmMobile.current
    val wide = with(density) { LocalWindowInfo.current.containerSize.width.toDp() } >= 640.dp
    val fontSize = if (mobile) 16f else 11f
    var open by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    fun takeFocus() {
        TextInputFocus.asked()
        focus.requestFocus()
    }
    SideEffect { label?.activate = ::takeFocus }
    val shape = RoundedCornerShape(8.dp)
    val text = tokens.text.color
    Box(
        modifier
            .fillMaxWidth()
            .cssHeight(if (wide) 28.dp else 44.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .drawBehind {
                if (!focused) return@drawBehind
                val band = 2.dp.toPx()
                drawRoundRect(tokens.accent.color, Offset(-band, -band), Size(size.width + 2 * band, size.height + 2 * band), CornerRadius(8.dp.toPx() + band))
            }
            .clip(shape)
            .background(tokens.bgSolid.color)
            .cssBorder(tokens.border.color, shape)
            .drawWithContent {
                drawContent()
                // Chrome's menulist arrow (ThemePainterDefault::SetupMenuListArrow, NativeThemeBase::PaintMenuList),
                // sized by the zoom and not the font: a chevron 8 px wide and half as tall in whole device
                // pixels, centred in the 15 px arrow box inside the right border, stroked 2 device px.
                // This draws inside the border, so its right edge is the one Chrome measures from.
                val zoom = this.density
                val box = 15 * zoom
                val across = 8 * zoom
                val arrow = across.toInt()
                val x = (size.width.toInt() - (box + across) / 2).toInt().toFloat()
                val tall = arrow / 2
                val y = (size.height.toInt() / 2 - tall / 2).toFloat()
                val path = Path().apply {
                    moveTo(x, y)
                    lineTo(x + arrow / 2, y + tall)
                    lineTo(x + arrow, y)
                }
                drawPath(path, text, style = Stroke(2f))
            }
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused }
            .focusable(enabled)
            .ppmClickable(enabled = enabled, pressedColor = Color.Transparent) {
                takeFocus()
                open = true
            }
            .padding(start = 16.dp, end = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            options.firstOrNull { it.value == value }?.label.orEmpty(),
            style = ppmText(fontSize, lineHeight = fontSize * 1.3f).copy(color = text),
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
    if (open) {
        ChromeSelectDialog(
            value,
            options,
            onPick = {
                open = false
                focus.requestFocus()
                if (it != value) onChange(it)
            },
            onDismiss = {
                open = false
                focus.requestFocus()
            },
        )
    }
}

/** The colours of Chrome's select list: Material You's, from the system palette where it has one. */
private class ChromeListColors(val surface: Color, val text: Color, val ring: Color, val checked: Color, val divider: Color)

@Composable
private fun chromeListColors(): ChromeListColors {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    return remember(dark) {
        if (Build.VERSION.SDK_INT >= 34) {
            fun c(id: Int) = Color(context.getColor(id))
            if (dark) {
                ChromeListColors(
                    c(android.R.color.system_surface_container_high_dark),
                    c(android.R.color.system_on_surface_dark),
                    c(android.R.color.system_on_surface_variant_dark),
                    c(android.R.color.system_primary_dark),
                    c(android.R.color.system_outline_variant_dark),
                )
            } else {
                ChromeListColors(
                    c(android.R.color.system_surface_container_high_light),
                    c(android.R.color.system_on_surface_light),
                    c(android.R.color.system_on_surface_variant_light),
                    c(android.R.color.system_primary_light),
                    c(android.R.color.system_outline_variant_light),
                )
            }
        } else if (dark) {
            // As measured on the emulator's Chrome 156.
            ChromeListColors(Color(0xFF1D1F26), Color(0xFFE4E5F0), Color(0xFFA9AAB5), Color(0xFFB8C6EE), Color(0xFF383A40))
        } else {
            ChromeListColors(Color(0xFFECE6F0), Color(0xFF1D1B20), Color(0xFF49454F), Color(0xFF6750A4), Color(0xFFCAC4D0))
        }
    }
}

/**
 * The list Chrome on a phone opens for a `<select>` (SelectPopupDialog), as measured at 2.625x:
 * a card 95% of the screen wide less a pixel each side, centred between the system bars, with
 * 24 dp corners and a 6 dp shadow and nothing dimmed behind it; 64 dp rows in the system font at
 * 22 sp, 1 dp dividers, the chosen row's radio filled in the accent. A row closes it with that
 * value, a tap outside or Back without one.
 */
@Composable
private fun ChromeSelectDialog(value: String, options: List<SelectOption>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = chromeListColors()
    val density = LocalDensity.current
    val screen = LocalWindowInfo.current.containerSize.width
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) { window?.setDimAmount(0f) }
        Box(
            Modifier
                .fillMaxSize()
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .windowInsetsPadding(WindowInsets.systemBars),
            contentAlignment = Alignment.Center,
        ) {
            val shape = RoundedCornerShape(24.dp)
            Column(
                Modifier
                    .width(with(density) { (floor(0.95f * screen) - 2).toDp() })
                    .shadow(6.dp, shape)
                    .clip(shape)
                    .background(colors.surface)
                    .pointerInput(Unit) { detectTapGestures {} }
                    .verticalScroll(rememberScrollState()),
            ) {
                options.forEachIndexed { index, option ->
                    if (index > 0) Box(Modifier.fillMaxWidth().height(with(density) { 1.dp.roundToPx().toDp() }).background(colors.divider))
                    ChromeSelectRow(option.label, option.value == value, colors) { onPick(option.value) }
                }
            }
        }
    }
}

@Composable
private fun ChromeSelectRow(label: String, checked: Boolean, colors: ChromeListColors, onClick: () -> Unit) {
    val density = LocalDensity.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    // A TextView with its font padding, centred in the row: Roboto's yMax over yMin.
    val baseline = with(density) { ((64.dp.toPx() - 1.327f * 22.sp.toPx()) / 2 + 1.056f * 22.sp.toPx()).toDp() }
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(if (pressed) colors.text.copy(alpha = 0.1f) else Color.Transparent)
            .clickable(source, indication = null, onClick = onClick)
            .padding(start = 12.dp, end = 13.dp),
    ) {
        BasicText(
            label,
            Modifier.weight(1f).paddingFromBaseline(top = baseline),
            style = TextStyle(fontFamily = FontFamily.Default, fontSize = 22.sp, color = colors.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            Modifier
                .align(Alignment.CenterVertically)
                .size(20.dp)
                .drawBehind {
                    val stroke = 2.dp.toPx()
                    val color = if (checked) colors.checked else colors.ring
                    drawCircle(color, radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
                    if (checked) drawCircle(colors.checked, radius = 5.dp.toPx())
                },
        )
    }
}
