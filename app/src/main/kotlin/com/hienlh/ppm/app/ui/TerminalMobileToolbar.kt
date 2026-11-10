package com.hienlh.ppm.app.ui

import android.os.Build
import android.view.ViewConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ui.phone.terminal.PhoneTerminalKeyItem
import com.hienlh.ppm.app.ui.phone.terminal.buildPhoneTerminalKeyItems
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.colorsFade
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.kit.KitSpec
import com.hienlh.ppm.design.kit.isHardwareKeyboardConnected
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.terminal.KeyRepeat
import com.hienlh.ppm.state.terminal.MobileKey
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Shared state allowing the tablet dock header or terminal status bar to toggle the key row
 * visible even when a physical hardware keyboard is attached (`Tablet 4` Note 45 & Note 76).
 */
@Stable
class TerminalKeyRowOverrideState(initialForceVisible: Boolean = false) {
    var forceVisible by mutableStateOf(initialForceVisible)

    fun toggle() {
        forceVisible = !forceVisible
    }
}

val LocalTerminalKeyRowOverride = compositionLocalOf<TerminalKeyRowOverrideState?> { null }

/**
 * Width threshold on tablet at or above which the key row's leading tools (`Select text`, `Copy`,
 * `Paste`, `Links`, `Send to Chat`) show text labels beside their icons (`Tablet 4` Notes 71 & 82).
 * Narrower side docks (e.g. 420 dp) fold the tools to 44 dp icon buttons.
 */
internal const val TABLET_WIDE_TOOLBAR_DP = 680f

/**
 * Returns whether the terminal key row should be visible given hardware keyboard state and
 * user override (`Tablet 4` Notes 45 & 76).
 */
internal fun shouldShowTerminalKeyRow(
    hardwareKeyboardConnected: Boolean,
    hideWhenHardwareKeyboardConnected: Boolean = true,
    forceVisible: Boolean = false,
): Boolean = !hideWhenHardwareKeyboardConnected || !hardwareKeyboardConnected || forceVisible

/**
 * terminal-mobile-toolbar.tsx: the clipboard actions plus the keys a soft keyboard does not offer
 * (Tab, Esc, Ctrl, arrows, ^C), in a row that scrolls sideways under an edge fade saying there is
 * more past the right edge.
 *
 * - On phone ([phoneKeyRowMode] = true, `Phone 4` Notes 31, 32, 35, Requirement 12.1): renders the
 *   48 dp `KeyRow` (`Esc`, `Tab`, `Ctrl`, `↑`, `↓`, `←`, `→`, `^C`, `Paste`), and in [selectMode]
 *   prepends `Select text` (highlighted) and `Copy` before a divider.
 * - On tablet ([phoneKeyRowMode] = false, `Tablet 4` Notes 45, 71, 76, 82, Requirement 12.3):
 *   renders the tools (`Select text`, `Copy`, `Paste`, `Links`, `Send to Chat`) with labels when
 *   wide (`>= 680 dp`) or compact icons when in a side dock (`< 680 dp`), followed by a divider
 *   and the terminal keys (`Tab`, `Esc`, `Ctrl`, `↑`, `↓`, `←`, `→`, `^C`).
 *
 * A key fires when the finger lifts, as the web's click does, so a drag that scrolls the row types
 * nothing. [onKey] is told when it is [repeated]: a held arrow fires again, and only its first
 * press is a fresh one (the terminal takes the focus, the soft keyboard's text is flushed).
 */
@Composable
fun TerminalMobileToolbar(
    ctrlMode: Boolean,
    onToggleCtrl: () -> Unit,
    selectMode: Boolean,
    onToggleSelect: () -> Unit,
    onKey: (key: MobileKey, repeated: Boolean) -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onSendToChat: () -> Unit,
    onOpenLinks: () -> Unit,
    modifier: Modifier = Modifier,
    phoneKeyRowMode: Boolean = false,
    hideWhenHardwareKeyboardConnected: Boolean = true,
    forceVisible: Boolean = LocalTerminalKeyRowOverride.current?.forceVisible ?: false,
) {
    val hwKeyboard = isHardwareKeyboardConnected()
    if (!shouldShowTerminalKeyRow(hwKeyboard, hideWhenHardwareKeyboardConnected, forceVisible)) {
        return
    }

    val tokens = LocalPpmTokens.current
    val repeat = remember { systemKeyRepeat() }

    if (phoneKeyRowMode) {
        val items = remember(selectMode, ctrlMode) {
            buildPhoneTerminalKeyItems(selectMode = selectMode, ctrlArmed = ctrlMode)
        }
        Box(
            modifier
                .fillMaxWidth()
                .height(KitSpec.KeyRowHeight)
                .background(tokens.panel2.color)
                .cssBorder(tokens.border.color, sides = CssSides.Top)
                .testTag("terminal-key-row"),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(KitSpec.KeyRowHeight)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(KitSpec.KeyButtonGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (item in items) {
                    when (item) {
                        PhoneTerminalKeyItem.Divider -> {
                            Box(
                                Modifier
                                    .padding(horizontal = 2.dp)
                                    .width(1.dp)
                                    .height(24.dp)
                                    .background(tokens.border.color),
                            )
                        }
                        is PhoneTerminalKeyItem.KeyButton -> {
                            val mobileKey = item.mobileKey
                            PhoneKeyRowButton(
                                label = item.label,
                                icon = item.icon,
                                highlighted = item.highlighted,
                                repeat = repeat.takeIf { item.repeats && mobileKey != null },
                                onRepeat = { first ->
                                    if (mobileKey != null) onKey(mobileKey, !first)
                                },
                                onClick = {
                                    when {
                                        item.id == "select_text" -> onToggleSelect()
                                        item.id == "copy" -> onCopy()
                                        item.id == "paste" -> onPaste()
                                        mobileKey == MobileKey.Ctrl -> onToggleCtrl()
                                        mobileKey != null -> onKey(mobileKey, false)
                                    }
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.width(24.dp))
            }
            Box(Modifier.matchParentSize()) {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .width(28.dp)
                        .fillMaxHeight()
                        .background(Brush.horizontalGradient(listOf(Color.Transparent, tokens.panel2.color))),
                )
            }
        }
        return
    }

    // Tablet & standard toolbar mode (`Tablet 4` Notes 71 & 82)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, sides = CssSides.Top)
            .testTag("terminal-mobile-toolbar"),
    ) {
        val showToolLabels = maxWidth >= TABLET_WIDE_TOOLBAR_DP.dp
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolbarButton(
                label = "Select text",
                icon = ProductIcon.TextSelect,
                showIconLabel = showToolLabels,
                highlighted = selectMode,
                onClick = onToggleSelect,
            )
            ToolbarButton(
                label = "Copy",
                icon = ProductIcon.Copy,
                showIconLabel = showToolLabels,
                onClick = onCopy,
            )
            ToolbarButton(
                label = "Paste",
                icon = ProductIcon.ClipboardPaste,
                showIconLabel = showToolLabels,
                onClick = onPaste,
            )
            ToolbarButton(
                label = "Links",
                icon = ProductIcon.Link,
                showIconLabel = showToolLabels,
                onClick = onOpenLinks,
            )
            ToolbarButton(
                label = "Send to Chat",
                icon = ProductIcon.MessageSquare,
                showIconLabel = showToolLabels,
                onClick = onSendToChat,
            )
            Box(
                Modifier
                    .padding(horizontal = 2.dp)
                    .width(1.dp)
                    .height(24.dp)
                    .background(tokens.border.color),
            )
            for (key in MobileKey.entries) {
                ToolbarButton(
                    label = key.label,
                    highlighted = key == MobileKey.Ctrl && ctrlMode,
                    repeat = repeat.takeIf { key.repeats },
                    onRepeat = { first -> onKey(key, !first) },
                    onClick = { if (key == MobileKey.Ctrl) onToggleCtrl() else onKey(key, false) },
                )
            }
            // Trailing spacer so the last key clears the fade instead of hiding under it.
            Spacer(Modifier.width(24.dp))
        }
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(32.dp)
                    .fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, tokens.panel.color))),
            )
        }
    }
}

/**
 * Phone 4 key button (44 x 40 dp visual key centered in the 48 dp bar, 10 dp radius, 13.5 sp
 * Monaspace SemiBold, with sticky highlight and held key repeat support).
 */
@Composable
private fun PhoneKeyRowButton(
    label: String,
    icon: ProductIcon? = null,
    highlighted: Boolean = false,
    repeat: KeyRepeat? = null,
    onRepeat: (first: Boolean) -> Unit = {},
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val lit = colorsFade(highlighted || pressed)
    val bgColor = if (highlighted) {
        tokens.accentWash.color
    } else {
        lerp(tokens.panel.color, tokens.accent.color, lit)
    }
    val borderColor = if (highlighted) tokens.accentWashBorder.color else tokens.border.color
    val fgColor = if (highlighted) {
        tokens.accent.color
    } else {
        lerp(tokens.text.color, tokens.accentFg.color, lit)
    }
    val repeated = remember { BooleanArray(1) }
    val currentOnRepeat by rememberUpdatedState(onRepeat)
    val keyShape = RoundedCornerShape(KitSpec.KeyButtonRadius)

    Box(
        modifier = Modifier
            .height(KitSpec.KeyRowHeight)
            .widthIn(min = KitSpec.KeyButtonMinWidth)
            .clip(keyShape)
            .then(
                if (repeat == null) {
                    Modifier
                } else {
                    Modifier.pointerInput(repeat) {
                        holdToRepeat(repeat, repeated) { first -> currentOnRepeat(first) }
                    }
                },
            )
            .ppmClickable(
                onClickLabel = label,
                pressedColor = Color.Transparent,
                source = source,
            ) {
                if (!repeated[0]) onClick()
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .widthIn(min = KitSpec.KeyButtonMinWidth)
                .height(KitSpec.KeyButtonHeight)
                .background(bgColor, keyShape)
                .cssBorder(borderColor, keyShape, width = 1.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        ) {
            if (icon != null) {
                PpmIcon(icon, 15.dp, fgColor)
            }
            BasicText(
                text = label,
                style = ppmText(
                    size = if (icon != null) 12.5f else 13.5f,
                    weight = FontWeight.SemiBold,
                    kind = if (icon != null) FontKind.SANS else FontKind.MONO,
                    lineHeight = 16f,
                ).copy(color = fgColor),
                maxLines = 1,
            )
        }
    }
}

/**
 * `BUTTON_BASE`: a 44 px target, `bg-surface-elevated text-text-primary`, and `bg-primary
 * text-primary-foreground` while the finger is on it (`active:`) or the button is [highlighted],
 * the two fading as `transition-colors` fades them. A label is a key's, in the mono face; an [icon]
 * is an action's (with optional [showIconLabel] on wide tablet docks).
 *
 * With a [repeat] the button also fires [onRepeat] for as long as a finger is held on it, once the
 * timeout is out and then at the interval. `first` tells the one that follows the timeout from the
 * rest, and the tap that comes of the same press when the finger lifts is dropped once any fired.
 */
@Composable
private fun ToolbarButton(
    label: String,
    icon: ProductIcon? = null,
    showIconLabel: Boolean = false,
    highlighted: Boolean = false,
    repeat: KeyRepeat? = null,
    onRepeat: (first: Boolean) -> Unit = {},
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val lit = colorsFade(highlighted || pressed)
    val color = lerp(tokens.text.color, tokens.accentFg.color, lit)
    val repeated = remember { BooleanArray(1) }
    val currentOnRepeat by rememberUpdatedState(onRepeat)
    val hasLabelWithIcon = icon != null && showIconLabel
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .then(if (hasLabelWithIcon) Modifier.widthIn(min = 44.dp) else Modifier.width(44.dp))
            .clip(RoundedCornerShape(6.dp))
            .background(lerp(tokens.panel2.color, tokens.accent.color, lit))
            .then(
                if (repeat == null) {
                    Modifier
                } else {
                    Modifier.pointerInput(repeat) {
                        holdToRepeat(repeat, repeated) { first -> currentOnRepeat(first) }
                    }
                },
            )
            // The fade above is the pressed look, so the clickable adds none of its own.
            .ppmClickable(onClickLabel = label, pressedColor = Color.Transparent, source = source) {
                if (!repeated[0]) onClick()
            }
            .padding(horizontal = if (hasLabelWithIcon) 10.dp else 0.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) {
            PpmIcon(icon, 16.dp, color)
            if (showIconLabel) {
                BasicText(
                    label,
                    style = ppmText(12f, FontWeight.Medium, FontKind.SANS, lineHeight = 16f).copy(color = color),
                    maxLines = 1,
                )
            }
        } else {
            BasicText(
                label,
                style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = color),
                maxLines = 1,
            )
        }
    }
}

/**
 * Presses again for as long as the finger that just went down stays put: [repeat]'s timeout after
 * it went down, then at its interval. A key does not repeat on the web (its row fires on click only),
 * so this is the app's own addition, and [repeated] says it happened so the click on lift is dropped.
 *
 * It only watches. The clickable under it decides what is a tap and what is the row scrolling, and a
 * finger that moves past the touch slop, lifts or is joined by another is no longer a hold here.
 */
private suspend fun PointerInputScope.holdToRepeat(
    repeat: KeyRepeat,
    repeated: BooleanArray,
    press: (first: Boolean) -> Unit,
) = coroutineScope {
    awaitEachGesture {
        // Initial, as TextInputFocus does: whatever consumed the press under this does not matter.
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        repeated[0] = false
        val ticker = launch {
            repeat.whileHeld {
                val first = !repeated[0]
                repeated[0] = true
                press(first)
            }
        }
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed || event.changes.size > 1 || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
            }
        } finally {
            ticker.cancel()
        }
    }
}

/**
 * Android's own key repeat: `ViewConfiguration`'s timeout and delay from API 28, which are the
 * long-press timeout and AOSP's 50 ms before that, and so follow the user's "touch and hold delay".
 */
internal fun systemKeyRepeat(): KeyRepeat =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        KeyRepeat(ViewConfiguration.getKeyRepeatTimeout().toLong(), ViewConfiguration.getKeyRepeatDelay().toLong())
    } else {
        KeyRepeat(ViewConfiguration.getLongPressTimeout().toLong(), KeyRepeat.DEFAULT_INTERVAL_MILLIS)
    }
