package com.hienlh.ppm.app

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.IntSize

/**
 * The text field holding focus, for the keyboard shortcuts: most of them stand aside while one
 * does, as the web's do for an input or a textarea, and all of them while its IME composes. Where
 * it is says how far the keyboard pans the page ([com.hienlh.ppm.app.ui.KeyboardPannedPage]).
 */
object TextInputFocus {
    private var state by mutableStateOf<TextFieldState?>(null)
    private var nativeOwner by mutableStateOf<Any?>(null)
    private var nativeBounds: ((LayoutCoordinates) -> Rect?)? = null
    private var nativeBlur: (() -> Unit)? = null

    /** Where the focused field is laid out, once it has been. */
    private var placed: LayoutCoordinates? = null

    /** The focus of the window the field is in: each window (the palette's, a dialog's) keeps one of its own. */
    private var window: FocusManager? = null

    /** The field the press going on landed in, focused or not. */
    private var pressedIn: TextFieldState? = null

    /** How many times a field was asked for focus: a tap can tell whether its handler asked. */
    private var asks = 0

    /**
     * The app has been touched or typed in since it started: Chrome's sticky user activation.
     * Measured on Android Chrome, a `focus()` brings the keyboard on a page that has it, however
     * long ago, and never on one that has not.
     */
    var activated = false
        private set

    fun activate() {
        activated = true
    }

    val focused: Boolean get() = state != null || nativeOwner != null

    /** An IME is composing in it (Telex before the word is done, say). */
    val composing: Boolean get() = state?.composition != null

    /** What it holds, null with no field focused. */
    val text: String? get() = state?.text?.toString()

    /** The focused field, to observe: another one is a fresh focus. */
    val field: Any? get() = nativeOwner ?: state

    /** Where the focused field lies in [page], null with none focused or one laid out elsewhere (a sheet's window). */
    fun boundsIn(page: LayoutCoordinates): Rect? {
        if (nativeOwner != null) return nativeBounds?.invoke(page)
        val field = placed?.takeIf { state != null && it.isAttached } ?: return null
        if (!page.isAttached || field.findRootCoordinates() != page.findRootCoordinates()) return null
        return page.localBoundingBoxOf(field, clipBounds = false)
    }

    internal fun take(state: TextFieldState, placed: LayoutCoordinates? = null, window: FocusManager? = null) {
        val before = this.window
        // A page has one focus: a field taking it in another window takes it from the one in
        // this, as the palette's input does from the composer's textarea, which is left with
        // nothing focused once the palette closes.
        if (this.state !== state && before != null && window != null && before !== window) before.clearFocus()
        this.state = state
        nativeOwner = null
        nativeBounds = null
        nativeBlur = null
        this.placed = placed
        this.window = window
    }

    /**
     * A native terminal editor participates in the same focus/keyboard-pan rules as Compose fields.
     * [onBlur] is what it does as a tap on nothing is about to take the focus from it ([blurNative]).
     */
    internal fun takeNative(owner: Any, window: FocusManager, onBlur: () -> Unit = {}, bounds: (LayoutCoordinates) -> Rect?) {
        val before = this.window
        if (field !== owner && before != null && before !== window) before.clearFocus()
        state = null
        placed = null
        nativeOwner = owner
        nativeBounds = bounds
        nativeBlur = onBlur
        this.window = window
    }

    internal fun releaseNative(owner: Any) {
        if (nativeOwner !== owner) return
        nativeOwner = null
        nativeBounds = null
        nativeBlur = null
        window = null
    }

    /**
     * A tap in [window] is about to clear the focus, which a browser's press on nothing does to an input and
     * which hides its keyboard. Compose hides a text field's own as the focus goes; a native editor is told
     * here, because only a view that still holds the focus can hide the keyboard.
     */
    internal fun blurNative(window: FocusManager) {
        if (nativeOwner != null && this.window === window) nativeBlur?.invoke()
    }

    /** Where the focused [state] is laid out now. */
    internal fun place(state: TextFieldState, placed: LayoutCoordinates) {
        if (this.state === state) this.placed = placed
    }

    internal fun release(state: TextFieldState) {
        if (this.state !== state) return
        this.state = null
        placed = null
        window = null
    }

    internal fun pressed(state: TextFieldState) {
        pressedIn = state
    }

    /** A handler wants a field focused, as the web's call `focus()` on their textarea. */
    fun asked() {
        asks++
    }

    /** What a tap leaves behind as it starts: the field focused, and the asks so far. */
    internal class TapStart(val focused: Any?, val asks: Int)

    internal fun tapStarts(): TapStart {
        // Before the fields under the press say which of them it landed in.
        pressedIn = null
        return TapStart(field, asks)
    }

    /** The tap begun at [start] left the focus where it was: not in that field, and nobody asked. */
    internal fun tapLeaves(start: TapStart): Boolean =
        field === start.focused && asks == start.asks && (start.focused == null || pressedIn !== start.focused)
}

/**
 * A tap that is not on the focused field takes the focus from it, as a press does in Chrome:
 * a button takes the focus itself, anything else gives it to the page. It stays where the tap
 * lands in the field, moves it to another field, or has its handler ask for it back
 * ([TextInputFocus.asked]) — Chrome does not hide the keyboard for a focus lost and given back in
 * the same click. A finger decides as it lifts, Chrome's tap; a mouse as it presses, its
 * `mousedown`. A drag or a long press leaves the focus where it is. The keyboard goes with the
 * focus, a native editor's too ([TextInputFocus.blurNative]).
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
        if (taps && TextInputFocus.tapLeaves(start)) {
            TextInputFocus.blurNative(focusManager)
            focusManager.clearFocus()
        }
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

/** Reports this text field, editing [state], to [TextInputFocus] while it has focus — where it is too — and the presses landing in it. */
fun Modifier.tracksTextFocus(state: TextFieldState): Modifier = this then TextFocusElement(state)

private data class TextFocusElement(val state: TextFieldState) : ModifierNodeElement<TextFocusNode>() {
    override fun create() = TextFocusNode(state)

    override fun update(node: TextFocusNode) {
        if (node.state === state) return
        TextInputFocus.release(node.state)
        node.state = state
        if (node.focused) node.take()
    }
}

private class TextFocusNode(var state: TextFieldState) : Modifier.Node(), FocusEventModifierNode, PointerInputModifierNode, LayoutAwareModifierNode, CompositionLocalConsumerModifierNode {
    private var placed: LayoutCoordinates? = null
    var focused = false
        private set

    fun take() = TextInputFocus.take(state, placed, currentValueOf(LocalFocusManager))

    override fun onFocusEvent(focusState: FocusState) {
        focused = focusState.isFocused
        if (focused) take() else TextInputFocus.release(state)
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        placed = coordinates
        // Focused before it was first laid out.
        if (focused) TextInputFocus.place(state, coordinates)
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
