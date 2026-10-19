package com.hienlh.ppm.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
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
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ProviderLogo
import com.hienlh.ppm.design.ProviderLogos
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
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
    shape: Shape = RectangleShape,
    enabled: Boolean = true,
    onLongClick: ((Offset) -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current
    val down = remember { PressPoint() }
    return background(if (pressed && enabled) pressedColor else Color.Transparent, shape)
        .then(
            if (onLongClick == null) Modifier else Modifier.pointerInput(Unit) {
                // Watches only: the press itself is still the clickable's.
                awaitEachGesture { down.offset = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position }
            },
        )
        .combinedClickable(
            interactionSource = source,
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

/** `ProviderBadge`: the provider's logo at 16px, or a "?" square for one with none. */
@Composable
fun ProviderBadge(providerId: String, size: Dp = 16.dp) {
    val tokens = LocalPpmTokens.current
    if (ProviderLogos.has(providerId)) {
        ProviderLogo(providerId, size, tokens.text.color)
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

/** The web's default `Button`: accent fill, `primary-foreground` text. */
@Composable
fun PrimaryButton(title: String, modifier: Modifier = Modifier, enabled: Boolean = true, height: Dp = 44.dp, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(tokens.accent.opacity(if (enabled) 1.0 else 0.5).color)
            .ppmClickable(enabled = enabled, pressedColor = Color.Black.copy(alpha = 0.1f), shape = shape, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(title, style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.accentFg.color))
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
            .border(1.dp, tokens.border.color, shape)
            .ppmClickable(pressedColor = tokens.panel2.color, shape = shape, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(title, style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text2.color))
    }
}

/**
 * A text field drawn as the web's inputs are: [placeholder] in the subtle colour while empty, the
 * accent caret. Inputs are 16px on a phone (`globals.css`), so that is the default size.
 */
@Composable
fun PpmTextField(
    state: TextFieldState,
    placeholder: String,
    modifier: Modifier = Modifier,
    style: TextStyle = ppmText(16f, lineHeight = 24f),
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardAction: KeyboardActionHandler? = null,
    lineLimits: TextFieldLineLimits = TextFieldLineLimits.SingleLine,
    inputTransformation: InputTransformation? = null,
    focusRequester: FocusRequester? = null,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    val tokens = LocalPpmTokens.current
    BasicTextField(
        state = state,
        modifier = modifier
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .onFocusChanged { onFocusChanged(it.isFocused) },
        textStyle = style.copy(color = tokens.text.color),
        cursorBrush = SolidColor(tokens.accent.color),
        keyboardOptions = keyboardOptions,
        onKeyboardAction = onKeyboardAction,
        lineLimits = lineLimits,
        inputTransformation = inputTransformation,
        decorator = { field ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (state.text.isEmpty()) {
                    BasicText(placeholder, style = style.copy(color = tokens.text3.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                field()
            }
        },
    )
}

/**
 * The web's `BottomSheet` (`mobile-bottom-sheet.tsx`): a 40% black backdrop, the panel on the
 * bottom edge with a 16px top radius and a drag handle, sliding up in 200 ms; a tap on the
 * backdrop, Back or a swipe down closes it. Drawn in a window of its own, so that it covers the
 * bottom bar and rises with the keyboard. [background] replaces `bg-popover` where a sheet's
 * class does (`bg-background` on the project sheet).
 */
@Composable
fun BottomSheet(onDismiss: () -> Unit, modifier: Modifier = Modifier, background: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    var panelHeight by remember { mutableFloatStateOf(0f) }
    val offset = remember { Animatable(Float.MAX_VALUE) }
    var closing by remember { mutableStateOf(false) }
    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            offset.animateTo(panelHeight, tween(150))
            onDismiss()
        }
    }
    LaunchedEffect(panelHeight) {
        if (panelHeight > 0 && offset.value == Float.MAX_VALUE) {
            offset.snapTo(panelHeight)
            offset.animateTo(0f, tween(200))
        }
    }
    Dialog(
        onDismissRequest = ::close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // The platform's own dim would fade in on its own clock; the backdrop is drawn here.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) { window?.setDimAmount(0f) }
        val shown = if (panelHeight > 0 && offset.value != Float.MAX_VALUE) 1f - (offset.value / panelHeight).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f * shown))
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = ::close),
            )
            val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            Column(
                modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { panelHeight = it.height.toFloat() }
                    .graphicsLayer { translationY = if (offset.value == Float.MAX_VALUE) size.height else offset.value }
                    .clip(shape)
                    .background(background ?: tokens.panel2.color)
                    .border(1.dp, tokens.border.color, shape)
                    .draggable(
                        rememberDraggableState { delta -> scope.launch { offset.snapTo((offset.value + delta).coerceAtLeast(0f)) } },
                        Orientation.Vertical,
                        onDragStopped = { velocity ->
                            if (offset.value > panelHeight * 0.25f || velocity > 1_500f) close() else offset.animateTo(0f, tween(150))
                        },
                    )
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(bottom = 8.dp),
            ) {
                // Drag handle: `pt-3 pb-1`, `w-10 h-1 rounded-full bg-border`.
                Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(40.dp).height(4.dp).clip(CircleShape).background(tokens.border.color))
                }
                content()
            }
        }
        BackHandler(onBack = ::close)
    }
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
 * The web's `AlertDialog`: a centred card with a title, a line of explanation and the two
 * buttons, the confirming one red when it destroys something.
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
    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(tokens.rad.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .ppmShadow(tokens.shadowPanel, shape)
                .clip(shape)
                .background(tokens.panel2.over(tokens.bgSolid).color)
                .border(1.dp, tokens.border.color, shape)
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
    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(tokens.rad.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .ppmShadow(tokens.shadowPanel, shape)
                .clip(shape)
                .background(tokens.panel2.over(tokens.bgSolid).color)
                .border(1.dp, tokens.border.color, shape)
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
                    .border(1.dp, (if (focused) tokens.accent else tokens.border).color, fieldShape)
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
