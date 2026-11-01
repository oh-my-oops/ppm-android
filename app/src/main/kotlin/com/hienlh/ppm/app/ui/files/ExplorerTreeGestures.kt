package com.hienlh.ppm.app.ui.files

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

/** Blank-area callers filter row hits so a held row never opens two menus. */
@Composable
internal fun Modifier.explorerContextGesture(
    onMenu: (Offset) -> Unit,
    eligible: (Offset) -> Boolean = { true },
    onPress: (PointerKeyboardModifiers) -> Unit = {},
): Modifier {
    val menu by rememberUpdatedState(onMenu)
    val accepts by rememberUpdatedState(eligible)
    val pressed by rememberUpdatedState(onPress)
    val haptics = LocalHapticFeedback.current
    return pointerInput(Unit) {
        awaitEachGesture {
            val press = awaitPointerEvent(PointerEventPass.Initial)
            val down = press.changes.firstOrNull { it.changedToDownIgnoreConsumed() } ?: return@awaitEachGesture
            if (!accepts(down.position)) return@awaitEachGesture
            pressed(press.keyboardModifiers)
            if (down.type == PointerType.Mouse) {
                if (press.buttons.isSecondaryPressed) { down.consume(); menu(down.position) }
                return@awaitEachGesture
            }
            val ended = withTimeoutOrNull(400L) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed || event.changes.count { it.pressed } > 1 ||
                        (change.position - down.position).getDistance() > 10.dp.toPx() ||
                        (change.isConsumed && change.position != change.previousPosition)) break
                }
            }
            if (ended != null || !accepts(down.position)) return@awaitEachGesture
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            menu(down.position)
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
}
