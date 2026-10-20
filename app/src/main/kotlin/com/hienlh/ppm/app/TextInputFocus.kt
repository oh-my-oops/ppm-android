package com.hienlh.ppm.app

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.node.ModifierNodeElement

/**
 * The text field holding focus, for the keyboard shortcuts: most of them stand aside while one
 * does, as the web's do for an input or a textarea, and all of them while its IME composes.
 */
object TextInputFocus {
    private var state: TextFieldState? = null

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
}

/** Reports this text field, editing [state], to [TextInputFocus] while it has focus. */
fun Modifier.tracksTextFocus(state: TextFieldState): Modifier = this then TextFocusElement(state)

private data class TextFocusElement(val state: TextFieldState) : ModifierNodeElement<TextFocusNode>() {
    override fun create() = TextFocusNode(state)

    override fun update(node: TextFocusNode) {
        if (node.state === state) return
        TextInputFocus.release(node.state)
        node.state = state
    }
}

private class TextFocusNode(var state: TextFieldState) : Modifier.Node(), FocusEventModifierNode {
    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.isFocused) TextInputFocus.take(state) else TextInputFocus.release(state)
    }

    // A field removed while focused says nothing more.
    override fun onDetach() {
        TextInputFocus.release(state)
    }
}
