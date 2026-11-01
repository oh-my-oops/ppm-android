package org.connectbot.terminal

import android.text.Editable
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputConnectionWrapper

/**
 * Android may call onCreateInputConnection and discard the result, or close the old handle
 * after opening its successor. Each handle has its own lifetime; the view owns the editor.
 */
internal class TerminalImeConnectionHandle(target: View, editor: CommittedImeConnection) :
    InputConnectionWrapper(editor, true) {
    private val closed = object : BaseInputConnection(target, true) {
        override fun getEditable(): Editable? = null
        override fun beginBatchEdit() = false
        override fun endBatchEdit() = false
        override fun commitText(text: CharSequence?, newCursorPosition: Int) = false
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int) = false
        override fun setComposingRegion(start: Int, end: Int) = false
        override fun finishComposingText() = false
        override fun sendKeyEvent(event: KeyEvent) = false
        override fun performEditorAction(editorAction: Int) = false
        override fun performContextMenuAction(id: Int) = false
    }

    override fun closeConnection() { setTarget(closed) }
}
