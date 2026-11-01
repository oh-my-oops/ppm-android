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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.colorsFade
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.terminal.KeyRepeat
import com.hienlh.ppm.state.terminal.MobileKey
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * terminal-mobile-toolbar.tsx: the clipboard actions plus the keys a soft keyboard does not offer
 * (Tab, Esc, Ctrl, arrows, ^C), in a row that scrolls sideways under an edge fade saying there is
 * more past the right edge.
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
) {
    val tokens = LocalPpmTokens.current
    val repeat = remember { systemKeyRepeat() }
    // Chrome Android offers this row on touch tablets too, not only below 768dp.
    Box(Modifier.fillMaxWidth().background(tokens.panel.color).cssBorder(tokens.border.color, sides = CssSides.Top)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolbarButton("Select text", ProductIcon.TextSelect, highlighted = selectMode, onClick = onToggleSelect)
            ToolbarButton("Copy", ProductIcon.Copy, onClick = onCopy)
            ToolbarButton("Paste", ProductIcon.ClipboardPaste, onClick = onPaste)
            ToolbarButton("Links", ProductIcon.Link, onClick = onOpenLinks)
            ToolbarButton("Send to Chat", ProductIcon.MessageSquare, onClick = onSendToChat)
            Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(24.dp).background(tokens.border.color))
            for (key in MobileKey.entries) {
                ToolbarButton(
                    key.label,
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
                Modifier.align(Alignment.CenterEnd).width(32.dp).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, tokens.panel.color))),
            )
        }
    }
}

/**
 * `BUTTON_BASE`: a 44 px target, `bg-surface-elevated text-text-primary`, and `bg-primary
 * text-primary-foreground` while the finger is on it (`active:`) or the button is [highlighted],
 * the two fading as `transition-colors` fades them. A label is a key's, in the mono face; an [icon]
 * is an action's.
 *
 * With a [repeat] the button also fires [onRepeat] for as long as a finger is held on it, once the
 * timeout is out and then at the interval. `first` tells the one that follows the timeout from the
 * rest, and the tap that comes of the same press when the finger lifts is dropped once any fired.
 */
@Composable
private fun ToolbarButton(
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
    val color = lerp(tokens.text.color, tokens.accentFg.color, lit)
    val repeated = remember { BooleanArray(1) }
    val currentOnRepeat by rememberUpdatedState(onRepeat)
    Row(
        Modifier.heightIn(min = 44.dp).width(44.dp).clip(RoundedCornerShape(4.dp))
            .background(lerp(tokens.panel2.color, tokens.accent.color, lit))
            .then(if (repeat == null) Modifier else Modifier.pointerInput(repeat) { holdToRepeat(repeat, repeated) { first -> currentOnRepeat(first) } })
            // The fade above is the pressed look, so the clickable adds none of its own.
            .ppmClickable(onClickLabel = label, pressedColor = Color.Transparent, source = source) {
                if (!repeated[0]) onClick()
            },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) PpmIcon(icon, 16.dp, color)
        else BasicText(label, style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = color))
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
private suspend fun PointerInputScope.holdToRepeat(repeat: KeyRepeat, repeated: BooleanArray, press: (first: Boolean) -> Unit) = coroutineScope {
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
