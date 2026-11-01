package com.hienlh.ppm.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.leavesTextOnTap
import com.hienlh.ppm.app.tracksTextFocus
import com.hienlh.ppm.design.ChromeCaret
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ProviderLogo
import com.hienlh.ppm.design.ProviderLogos
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.chromeCaret
import com.hienlh.ppm.design.colorsFade
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssBorderInset
import com.hienlh.ppm.design.cssBorderPx
import com.hienlh.ppm.design.cssHeight
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberChromeCaret
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.design.themeBackground
import com.hienlh.ppm.protocol.SessionUnread
import kotlinx.coroutines.launch

/**
 * A tap target drawn as the web's touch buttons are: [pressedColor] under the finger instead of a
 * ripple, and a long press (with the system's haptic tick) where the web opens its context menu.
 * The long press is told where the finger is, since a context menu opens there.
 */
@Composable
fun Modifier.pressable(
    pressedColor: Color = LocalPpmTokens.current.surfaceHover.color,
    hoverColor: Color = Color.Transparent,
    fade: Boolean = true,
    shape: Shape = RectangleShape,
    enabled: Boolean = true,
    source: MutableInteractionSource? = null,
    onLongClick: ((Offset) -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val own = remember { MutableInteractionSource() }
    val interactions = source ?: own
    val pressed by interactions.collectIsPressedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val haptics = LocalHapticFeedback.current
    val down = remember { PressPoint() }
    val menu by rememberUpdatedState(onLongClick)
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
        .then(
            if (onLongClick == null) Modifier else Modifier.pointerInput(enabled) {
                awaitEachGesture {
                    val press = awaitPointerEvent(PointerEventPass.Initial)
                    val first = press.changes.firstOrNull { it.changedToDownIgnoreConsumed() } ?: return@awaitEachGesture
                    // Watches only, the press itself being the clickable's — but a mouse's right
                    // button is no click: it opens the menu the web opens on `contextmenu`.
                    down.offset = first.position
                    if (enabled && first.type == PointerType.Mouse && press.buttons.isSecondaryPressed) {
                        first.consume()
                        menu?.invoke(first.position)
                    }
                }
            },
        )
        .combinedClickable(
            interactionSource = interactions,
            indication = null,
            enabled = enabled,
            role = Role.Button,
            onLongClick = onLongClick?.let { long -> { haptics.performHapticFeedback(HapticFeedbackType.LongPress); long(down.offset) } },
            onClick = onClick,
        )
}

private class PressPoint {
    var offset = Offset.Zero
}

/**
 * Where the web listens for `contextmenu` on something that is no button: a right click, or a
 * finger held still for the long-press time, as Chrome turns one into `contextmenu`; [open] is told
 * where, in the element. A held finger's release is kept from what is under it, which would
 * otherwise take it as a tap.
 */
@Composable
fun Modifier.contextMenuGesture(open: (Offset) -> Unit): Modifier {
    val current by rememberUpdatedState(open)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val press = awaitPointerEvent(PointerEventPass.Initial)
                val down = press.changes.firstOrNull { it.changedToDownIgnoreConsumed() } ?: continue
                if (down.type == PointerType.Mouse) {
                    if (press.buttons.isSecondaryPressed) {
                        down.consume()
                        current(down.position)
                    }
                    continue
                }
                val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                    }
                }
                if (ended != null) continue
                current(down.position)
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
    }
}

/**
 * `ProviderBadge`: the provider's logo at 16px, or a "?" square for one with none. Cursor's is
 * drawn in [foreground], the text colour around it (`currentColor`).
 */
@Composable
fun ProviderBadge(providerId: String, size: Dp = 16.dp, foreground: Color = LocalPpmTokens.current.text.color) {
    val tokens = LocalPpmTokens.current
    if (ProviderLogos.has(providerId)) {
        ProviderLogo(providerId, size, foreground)
    } else {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(4.dp)).background(tokens.panel2.color),
            contentAlignment = Alignment.Center,
        ) {
            BasicText("?", style = ppmText(10f, FontWeight.Bold, lineHeight = 15f).copy(color = tokens.text3.color))
        }
    }
}

/** A tag's colour dot (`size-2 rounded-full`). */
@Composable
fun TagDot(color: String, size: Dp = 8.dp) {
    val tokens = LocalPpmTokens.current
    Box(Modifier.size(size).clip(CircleShape).background((Rgba.hex(color) ?: tokens.text3).color))
}

/**
 * The web's default `Button`: accent fill, `primary-foreground` text, all of it at half opacity
 * while disabled. [hover] fades in its `hover:bg-primary/90`, for the places the web draws one;
 * [radius], [fontSize] and [lineHeight] are its class's own where it sets them.
 */
@Composable
fun PrimaryButton(
    title: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 44.dp,
    hover: Boolean = false,
    radius: Dp = 12.dp,
    fontSize: Float = 14f,
    lineHeight: Float = 20f,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(radius)
    val hovered = rememberHover(enabled = hover && enabled)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(tokens.accent.opacity(1 - 0.1 * hovered.lit).color)
            .ppmClickable(enabled = enabled, pressedColor = Color.Black.copy(alpha = 0.1f), shape = shape, source = hovered.source, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(title, style = ppmText(fontSize, FontWeight.Medium, lineHeight = lineHeight).copy(color = tokens.accentFg.color))
    }
}

/** `h-11 rounded-xl border border-border` with no fill: the sheets' Cancel. */
@Composable
fun SecondaryButton(title: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .ppmClickable(pressedColor = tokens.panel2.color, shape = shape, onClick = onClick)
            .cssBorder(tokens.border.color, shape),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(title, style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text2.color))
    }
}

/**
 * What a Tab does in a textarea: it goes on to the control that takes the focus next and types
 * nothing, where Compose's field of several lines types a tab. Shift+Tab is left alone: the field
 * types nothing for it, and the default traversal takes the focus back, as it does on the web.
 * Put it after the handlers that have a use of Tab of their own, which hear it first: an open
 * picker's Tab picks, and the message box's Shift+Tab changes the permission mode.
 */
@Composable
internal fun Modifier.tabMovesFocus(): Modifier {
    val focusManager = LocalFocusManager.current
    return onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || event.key != Key.Tab || event.isShiftPressed) return@onPreviewKeyEvent false
        focusManager.moveFocus(FocusDirection.Next)
        true
    }
}

/**
 * A text field drawn as the web's inputs are: [placeholder] in the subtle colour while empty (or
 * the field's own [placeholderColor]), Chrome's caret ([ChromeCaret]). Inputs are 16px on a phone (`globals.css`),
 * so that is the default size; outside the phone layout a field takes its class's own size,
 * [desktopStyle], where one is given. [cssStart] is the CSS length before the text, for the caret.
 */
@Composable
fun PpmTextField(
    state: TextFieldState,
    placeholder: String,
    modifier: Modifier = Modifier,
    style: TextStyle = ppmText(16f, lineHeight = 24f),
    desktopStyle: TextStyle? = null,
    placeholderColor: Color? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardAction: KeyboardActionHandler? = null,
    lineLimits: TextFieldLineLimits = TextFieldLineLimits.SingleLine,
    inputTransformation: InputTransformation? = null,
    focusRequester: FocusRequester? = null,
    onFocusChanged: (Boolean) -> Unit = {},
    placeholderOverflow: TextOverflow = TextOverflow.Ellipsis,
    cssStart: Dp = 0.dp,
) {
    val tokens = LocalPpmTokens.current
    val style = desktopStyle?.takeUnless { LocalPpmMobile.current } ?: style
    val multiline = lineLimits is TextFieldLineLimits.MultiLine
    val textStyle = style.copy(color = tokens.text.color)
    val caret = rememberChromeCaret(state, textStyle, multiline, cssStart)
    BasicTextField(
        state = state,
        modifier = modifier
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .onFocusChanged { onFocusChanged(it.isFocused) }
            .tracksTextFocus(state)
            .let { if (multiline) it.tabMovesFocus() else it },
        textStyle = textStyle,
        cursorBrush = caret.brush,
        keyboardOptions = keyboardOptions,
        onKeyboardAction = onKeyboardAction,
        lineLimits = lineLimits,
        inputTransformation = inputTransformation,
        onTextLayout = caret.onTextLayout,
        interactionSource = caret.interactions,
        scrollState = caret.scroll,
        decorator = { field ->
            // A textarea's text and placeholder start at its top and wrap; an input's sit in its
            // middle on one line, cut at its edge as Chrome cuts it unless told otherwise.
            Box(contentAlignment = if (multiline) Alignment.TopStart else Alignment.CenterStart) {
                if (state.text.isEmpty()) {
                    val placeholderStyle = style.copy(color = placeholderColor ?: tokens.text3.color)
                    when {
                        multiline -> BasicText(placeholder, style = placeholderStyle)
                        placeholderOverflow == TextOverflow.Clip -> BasicText(placeholder, style = placeholderStyle, softWrap = false, maxLines = 1, overflow = TextOverflow.Clip)
                        else -> BasicText(placeholder, style = placeholderStyle, maxLines = 1, overflow = placeholderOverflow)
                    }
                }
                Box(Modifier.chromeCaret(caret)) { field() }
            }
        },
    )
}

/**
 * Where in its first pixel a [BottomSheet]'s content starts as Chrome lays it out: the handle's
 * 20dp is 52.5 px at 2.625x and drawn as 53, so the content is half a pixel higher than where it is
 * put. A [CssColumn] given it as its `top` stacks its rows from there.
 */
val LocalSheetContentTop = staticCompositionLocalOf { 0.dp }

/** What a test finds the sheet's drag handle by. */
internal const val SHEET_HANDLE_TAG = "sheet-handle"

/**
 * The web's `BottomSheet` (`mobile-bottom-sheet.tsx`): a 40% black backdrop, the panel on the
 * bottom edge with a 16px top radius and a drag handle, sliding up in 200 ms; a tap on the
 * backdrop, Back or a swipe down closes it. Drawn in a window of its own, so that it covers the
 * bottom bar and rises with the keyboard. [background] replaces `bg-popover` where a sheet's
 * class does (`bg-background` on the project sheet). While not [dismissible] (an `onClose` that
 * ignores the call), it stays, and a swipe springs back. [bottomPadding] is the panel's
 * `pb-[max(0.5rem,env(safe-area-inset-bottom))]`, or the keyboard's height while it is up — a
 * sheet whose class drops the padding (`p-0`) keeps only the navigation bar's, which Chrome's
 * page never reaches under. [overlay] is drawn over the sheet and its backdrop, where the page's
 * fixed corners stay above a sheet given a lower `zIndex`. [radius] is the panel's top corners'
 * (`rounded-t-2xl`, or a class's own). [inset] is a class's padding on the other three sides
 * (`p-2`), the handle inside it. A swipe down starts anywhere on the panel; with [swipeFromContent]
 * false it starts on the handle alone, as where the sheet's content stops the touches before the
 * panel's swipe handlers see them (the explorer's, while a name is being typed).
 */
@Composable
fun BottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    radius: Dp = 16.dp,
    background: Color? = null,
    dismissible: Boolean = true,
    bottomPadding: WindowInsets = WindowInsets.ime.union(WindowInsets.navigationBars).union(WindowInsets(bottom = 8.dp)),
    inset: Dp = 0.dp,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    swipeFromContent: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    var panelHeight by remember { mutableFloatStateOf(0f) }
    val offset = remember { Animatable(Float.MAX_VALUE) }
    var closing by remember { mutableStateOf(false) }
    fun close() {
        if (closing || !dismissible) return
        closing = true
        scope.launch {
            offset.animateTo(panelHeight, tween(150))
            onDismiss()
        }
    }
    LaunchedEffect(panelHeight) {
        if (panelHeight <= 0f || closing) return@LaunchedEffect
        // Below the edge until it is measured, then up. The effect can be started again before
        // the rise is done (it was, on the first frames of a sheet), so it always finishes it.
        if (offset.value == Float.MAX_VALUE) offset.snapTo(panelHeight)
        offset.animateTo(0f, tween(200))
    }
    if (closedByConnectionLost(onDismiss)) return
    Dialog(
        onDismissRequest = ::close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // The platform's own dim would fade in on its own clock; the backdrop is drawn here.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) { window?.setDimAmount(0f) }
        val focusManager = LocalFocusManager.current
        val shown = if (panelHeight > 0 && offset.value != Float.MAX_VALUE) 1f - (offset.value / panelHeight).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f * shown))
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = ::close),
            )
            val shape = RoundedCornerShape(topStart = radius, topEnd = radius)
            val border = tokens.border.color
            val swipe = Modifier.draggable(
                rememberDraggableState { delta -> scope.launch { offset.snapTo((offset.value + delta).coerceAtLeast(0f)) } },
                Orientation.Vertical,
                onDragStopped = { velocity ->
                    if (dismissible && (offset.value > panelHeight * 0.25f || velocity > 1_500f)) close() else offset.animateTo(0f, tween(150))
                },
            )
            Column(
                modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { panelHeight = it.height.toFloat() }
                    .graphicsLayer { translationY = if (offset.value == Float.MAX_VALUE) size.height else offset.value }
                    .clip(shape)
                    .background(background ?: tokens.panel2.color)
                    .drawWithCache {
                        val line = topBorder(size, radius.toPx(), cssBorderPx(1.dp).toFloat())
                        onDrawBehind { drawPath(line, border) }
                    }
                    .then(if (swipeFromContent) swipe else Modifier)
                    // A tap off the focused field takes the focus from it, as on the page.
                    .leavesTextOnTap(focusManager)
                    .cssBorderInset(top = 1.dp)
                    .padding(start = inset, top = inset, end = inset)
                    .windowInsetsPadding(bottomPadding),
            ) {
                // Drag handle: `pt-3 pb-1`, `w-10 h-1 rounded-full bg-border`. 20dp rounded once, as Chrome
                // snaps it: 53 px at 2.625x, where its three parts rounded apiece come to 54.
                Box(Modifier.fillMaxWidth().cssHeight(20.dp).testTag(SHEET_HANDLE_TAG).then(if (swipeFromContent) Modifier else swipe).padding(top = 12.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(40.dp).height(4.dp).clip(CircleShape).background(tokens.border.color))
                }
                val contentTop = with(LocalDensity.current) { (20.dp.toPx() - 20.dp.roundToPx()).toDp() }
                CompositionLocalProvider(LocalSheetContentTop provides contentTop) { content() }
            }
            overlay?.invoke(this)
        }
        BackHandler(onBack = ::close)
    }
}

/**
 * `border-t` on a box with rounded top corners, as CSS draws it: the line follows the corners and
 * thins to nothing where they meet the sides, which have no border. [radius] and [width] in pixels.
 */
private fun topBorder(size: Size, radius: Float, width: Float): Path {
    val outer = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), topLeft = CornerRadius(radius), topRight = CornerRadius(radius))) }
    // The padding edge: its corners keep their width and lose the border's height.
    val inner = Path().apply {
        val corner = CornerRadius(radius, (radius - width).coerceAtLeast(0f))
        addRoundRect(RoundRect(Rect(Offset(0f, width), size), topLeft = corner, topRight = corner))
    }
    return Path().apply { op(outer, inner, PathOperation.Difference) }
}

/** A sheet's menu row (`px-3 py-2.5 text-sm`): icon, label, pressed tint. */
@Composable
fun SheetRow(
    label: String,
    icon: ProductIcon? = null,
    destructive: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val color = if (destructive) tokens.error else tokens.text
    Row(
        Modifier
            .fillMaxWidth()
            .ppmClickable(pressedColor = tokens.panel2.color, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        leading?.invoke()
        if (icon != null) PpmIcon(icon, 16.dp, color.color)
        BasicText(
            label,
            Modifier.weight(1f),
            style = ppmText(14f, lineHeight = 20f).copy(color = color.color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.invoke(this)
    }
}

/** A sheet's section label (`px-3 pt-2 pb-1 text-xs text-text-secondary`). */
@Composable
fun SheetLabel(text: String, icon: ProductIcon? = null) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) PpmIcon(icon, 14.dp, tokens.text2.color)
        BasicText(text, style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color))
    }
}

/** `h-px bg-border mx-2`. */
@Composable
fun SheetDivider() {
    val tokens = LocalPpmTokens.current
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).height(1.dp).background(tokens.border.color))
}

/**
 * What the web asks with `window.confirm`, which the browser draws itself: a centred card with a
 * title, a line of explanation and the two buttons, the confirming one red when it destroys
 * something.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirm: String,
    destructive: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    if (closedByConnectionLost(onDismiss)) return
    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(tokens.rad.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .ppmShadow(tokens.shadowPanel, shape)
                .clip(shape)
                .background(tokens.panel2.over(tokens.bgSolid).color)
                .cssBorder(tokens.border.color, shape)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText(title, style = ppmText(18f, FontWeight.SemiBold, lineHeight = 28f).copy(color = tokens.text.color))
            BasicText(message, style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text2.color))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Cancel", Modifier.weight(1f), onClick = onDismiss)
                val danger = if (destructive) tokens.error else tokens.accent
                val buttonShape = RoundedCornerShape(12.dp)
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(buttonShape)
                        .background(danger.color)
                        .ppmClickable(pressedColor = Color.Black.copy(alpha = 0.1f), shape = buttonShape) {
                            onDismiss()
                            onConfirm()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(confirm, style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = Color.White))
                }
            }
        }
    }
}

/** The web's `window.prompt`, drawn as [ConfirmDialog] is: the question, a field holding [initial], Cancel and OK. */
@Composable
fun PromptDialog(
    message: String,
    initial: String,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val value = remember { TextFieldState(initial) }
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun confirm() {
        onDismiss()
        onConfirm(value.text.toString())
    }
    if (closedByConnectionLost(onDismiss)) return
    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(tokens.rad.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .ppmShadow(tokens.shadowPanel, shape)
                .clip(shape)
                .background(tokens.panel2.over(tokens.bgSolid).color)
                .cssBorder(tokens.border.color, shape)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText(message, style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text.color))
            val fieldShape = RoundedCornerShape(12.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(fieldShape)
                    .background(tokens.bgSolid.color)
                        .cssBorder((if (focused) tokens.accent else tokens.border).color, fieldShape)
                    // The rename sheet's field: `px-3`.
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                PpmTextField(
                    value, "", Modifier.fillMaxWidth(),
                    keyboardOptions = keyboardOptions,
                    onKeyboardAction = { confirm() },
                    focusRequester = focus,
                    onFocusChanged = { focused = it },
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Cancel", Modifier.weight(1f), onClick = onDismiss)
                PrimaryButton("OK", Modifier.weight(1f), onClick = ::confirm)
            }
        }
    }
}

/** A box filling the screen with the theme's backdrop, as `.app-backdrop` does. */
@Composable
fun Backdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val tokens = LocalPpmTokens.current
    Box(modifier.fillMaxSize().themeBackground(tokens.background), content = content)
}

/** The unread sessions of every project, kept current; empty while no server is open. */
@Composable
fun AppModel.unreadSessions(): Map<String, SessionUnread> = events?.unread?.collectAsState()?.value.orEmpty()

/** The sessions with a turn running, kept current. */
@Composable
fun AppModel.runningSessions(): Set<String> = events?.running?.collectAsState()?.value.orEmpty()

/**
 * The screen is kept on (use-wake-lock.ts): a turn runs on any project of the open server and
 * `keepScreenAwake` is on. Android lets go of it by itself while the app is out of sight.
 */
@Composable
fun AppModel.screenKeptAwake(): Boolean = keepScreenAwake && runningSessions().isNotEmpty()
