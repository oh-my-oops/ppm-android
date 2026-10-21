package com.hienlh.ppm.app

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.unit.IntSize

/**
 * The text field holding focus, for the keyboard shortcuts: most of them stand aside while one
 * does, as the web's do for an input or a textarea, and all of them while its IME composes.
 */
object TextInputFocus {
    private var state: TextFieldState? = null

    /** The field the press going on landed in, focused or not. */
    private var pressedIn: TextFieldState? = null

    /** How many times a field was asked for focus: a tap can tell whether its handler asked. */
    private var asks = 0

    val focused: Boolean get() = state != null

    /** An IME is composing in it (Telex before the word is done, say). */
    val composing: Boolean get() = state?.composition != null

    /** What it holds, null with no field focused. */
    val text: String? get() = state?.text?.toString()

    internal fun take(state: TextFieldState) {
        this.state = state
    }

    internal fun release(state: TextFieldState) {
        if (this.state === state) this.state = null
    }

    internal fun pressed(state: TextFieldState) {
        pressedIn = state
    }

    /** A handler wants a field focused, as the web's call `focus()` on their textarea. */
    fun asked() {
        asks++
    }

    /** What a tap leaves behind as it starts: the field focused, and the asks so far. */
    internal class TapStart(val focused: TextFieldState?, val asks: Int)

    internal fun tapStarts(): TapStart {
        // Before the fields under the press say which of them it landed in.
        pressedIn = null
        return TapStart(state, asks)
    }

    /** The tap begun at [start] left the focus where it was: not in that field, and nobody asked. */
    internal fun tapLeaves(start: TapStart): Boolean =
        state === start.focused && asks == start.asks && (start.focused == null || pressedIn !== start.focused)
}

/**
 * A tap that is not on the focused field takes the focus from it, as a press does in Chrome:
 * a button takes the focus itself, anything else gives it to the page. It stays where the tap
 * lands in the field, moves it to another field, or has its handler ask for it back
 * ([TextInputFocus.asked]) — Chrome does not hide the keyboard for a focus lost and given back in
 * the same click. A finger decides as it lifts, Chrome's tap; a mouse as it presses, its
 * `mousedown`. A drag or a long press leaves the focus where it is.
 */
fun Modifier.leavesTextOnTap(focusManager: FocusManager): Modifier = pointerInput(focusManager) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val start = TextInputFocus.tapStarts()
        // After every handler under it, which may have asked for the focus back.
        val taps = if (down.type == PointerType.Mouse) {
            awaitPointerEvent(PointerEventPass.Final)
            true
        } else {
            liftsAsTap(down, PointerEventPass.Final)
        }
        if (taps && TextInputFocus.tapLeaves(start)) focusManager.clearFocus()
    }
}

/**
 * Whether the pointer that went [down] comes up as a tap: before a long press would start and
 * within the touch slop, one finger only. What took the events under it does not matter.
 */
suspend fun AwaitPointerEventScope.liftsAsTap(down: PointerInputChange, pass: PointerEventPass = PointerEventPass.Main): Boolean {
    var tap = true
    while (true) {
        val event = awaitPointerEvent(pass)
        val change = event.changes.firstOrNull { it.id == down.id } ?: return false
        if (event.changes.size > 1 || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) tap = false
        if (!change.pressed) return tap && change.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis
    }
}

/**
 * Every tap in it runs [action], those its buttons take included, as a click bubbles up to an
 * `onClick` on the web's container.
 */
fun Modifier.onEveryTap(action: () -> Unit): Modifier = this then EveryTapElement(action)

private data class EveryTapElement(val action: () -> Unit) : ModifierNodeElement<EveryTapNode>() {
    override fun create() = EveryTapNode(action)

    override fun update(node: EveryTapNode) {
        node.action = action
    }
}

private class EveryTapNode(var action: () -> Unit) : DelegatingNode(), PointerInputModifierNode {
    private val input = delegate(
        SuspendingPointerInputModifierNode {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (liftsAsTap(down)) action()
            }
        },
    )

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) = input.onPointerEvent(pointerEvent, pass, bounds)

    override fun onCancelPointerInput() = input.onCancelPointerInput()
}

/** Reports this text field, editing [state], to [TextInputFocus] while it has focus, and the presses landing in it. */
fun Modifier.tracksTextFocus(state: TextFieldState): Modifier = this then TextFocusElement(state)

private data class TextFocusElement(val state: TextFieldState) : ModifierNodeElement<TextFocusNode>() {
    override fun create() = TextFocusNode(state)

    override fun update(node: TextFocusNode) {
        if (node.state === state) return
        TextInputFocus.release(node.state)
        node.state = state
    }
}

private class TextFocusNode(var state: TextFieldState) : Modifier.Node(), FocusEventModifierNode, PointerInputModifierNode {
    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.isFocused) TextInputFocus.take(state) else TextInputFocus.release(state)
    }

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass == PointerEventPass.Initial && pointerEvent.type == PointerEventType.Press) TextInputFocus.pressed(state)
    }

    override fun onCancelPointerInput() = Unit

    // A field removed while focused says nothing more.
    override fun onDetach() {
        TextInputFocus.release(state)
    }
}
