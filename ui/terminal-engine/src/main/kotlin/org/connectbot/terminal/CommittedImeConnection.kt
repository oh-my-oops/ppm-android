package org.connectbot.terminal

import android.os.Build
import android.text.Selection
import android.text.SpannedString
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.TextAttribute
import androidx.annotation.RequiresApi

/**
 * A real Android editor for preedit and a bounded tail of this editor's own sent text.
 * Gboard Telex revises commitText via replaceText. Such edits erase/rewrite only that tail;
 * terminal echo is never imported. Raw keys and owner changes end the editable generation.
 * All calls, including [handleRawKeyEvent] and [closeConnection], belong on the UI thread.
 */
internal class CommittedImeConnection(
    target: View,
    private val onCommit: (text: String, applyModifiers: Boolean) -> Unit,
    private val onKey: (KeyEvent) -> Boolean,
    private val onDelete: () -> Unit = { onKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)) },
    private val onPreedit: (String) -> Unit = {},
    private val onSelection: (start: Int, end: Int, composingStart: Int, composingEnd: Int) -> Unit = { _, _, _, _ -> },
    private val onExtractedText: (token: Int, text: ExtractedText) -> Unit = { _, _ -> },
    private val onPaste: (() -> Unit)? = null,
    private val onInvalidate: () -> Unit = {},
    private val rawMode: Boolean = false,
    private val hasShortcutModifiers: () -> Boolean = { false },
) : BaseInputConnection(target, true) {
    private var active = true
    private var batchDepth = 0
    private var changed = false
    private var extractedToken: Int? = null
    private val history = TerminalImeHistory()
    private var committedEditable = ""
    val isActive: Boolean get() = active

    /** Only unsent text belongs in the overlay, not the retained sent prefix. */
    val preedit: String get() {
        val buffer = editable ?: return ""
        if (buffer.toString() == committedEditable) return ""
        val a = getComposingSpanStart(buffer)
        val b = getComposingSpanEnd(buffer)
        if (a >= 0 && b > a) return buffer.subSequence(a, b).toString()
        val common = committedEditable.commonPrefixWith(buffer.toString()).length
        return buffer.substring(common)
    }
    val selectionStart: Int get() = Selection.getSelectionStart(editable).coerceAtLeast(0)
    val selectionEnd: Int get() = Selection.getSelectionEnd(editable).coerceAtLeast(0)

    /** Finalize composition and retire editable context before input injected by the host. */
    fun finishBeforeExternalInput() {
        if (!active) return
        flushPending()
        retire()
    }

    override fun beginBatchEdit(): Boolean {
        if (!active) return false
        batchDepth++
        return true
    }

    override fun endBatchEdit(): Boolean {
        if (!active) return false
        if (batchDepth > 0) batchDepth--
        if (batchDepth == 0 && changed) publish()
        return batchDepth > 0
    }

    private inline fun edit(action: () -> Boolean): Boolean {
        if (!active) return false
        beginBatchEdit()
        try {
            return action().also { changed = true }
        } finally {
            endBatchEdit()
        }
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val value = text?.toString().orEmpty()
        if (!validImeUnicode(value) || value.length > MAX_COMPOSITION) return false
        if (rawMode) return commitText(value, newCursorPosition)
        return edit { super.setComposingText(value, newCursorPosition).also { repairSelection() } }
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean = edit {
        val buffer = editable ?: return@edit false
        if (!unicodeBoundary(buffer, start) || !unicodeBoundary(buffer, end)) return@edit false
        super.setComposingRegion(start, end)
    }

    override fun setSelection(start: Int, end: Int): Boolean = edit {
        val buffer = editable ?: return@edit false
        if (!unicodeBoundary(buffer, start) || !unicodeBoundary(buffer, end)) return@edit false
        super.setSelection(start, end)
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val value = text?.toString().orEmpty()
        if (!validImeUnicode(value)) return false
        return edit {
            val wasComposing = preedit.isNotEmpty()
            if (rawMode || (!wasComposing && hasShortcutModifiers())) {
                flushPending()
                closeConnection()
                if (value.isNotEmpty()) onCommit(value, true)
                onInvalidate()
                return@edit true
            }
            super.commitText(value, newCursorPosition)
            repairSelection()
            sendEdit(applyModifiers = !wasComposing)
            true
        }
    }

    override fun finishComposingText(): Boolean = edit {
        super.finishComposingText()
        sendEdit(applyModifiers = false)
        true
    }

    /** Finalize without Enter, before a raw key or a change of shortcut/input mode. */
    fun flushPending(): Boolean = finishComposingText()

    private fun sendEdit(applyModifiers: Boolean) {
        val text = editable?.toString().orEmpty()
        val rewrite = history.update(text)
        committedEditable = text
        if (rewrite.boundary) closeConnection()
        repeat(rewrite.deleteCodePoints) { onDelete() }
        if (rewrite.append.isNotEmpty()) onCommit(rewrite.append, applyModifiers && rewrite.deleteCodePoints == 0)
        if (rewrite.boundary) onInvalidate()
    }

    private fun retire() {
        if (!active) return
        closeConnection()
        onInvalidate()
    }

    private fun repairSelection() {
        val buffer = editable ?: return
        val a = Selection.getSelectionStart(buffer)
        val b = Selection.getSelectionEnd(buffer)
        if (a >= 0 && b >= 0) Selection.setSelection(buffer,
            if (unicodeBoundary(buffer, a)) a else a + 1,
            if (unicodeBoundary(buffer, b)) b else b + 1)
    }

    private fun clearLocal() {
        history.clear()
        committedEditable = ""
        editable?.let {
            removeComposingSpans(it)
            it.clear()
            Selection.setSelection(it, 0)
        }
        changed = true
    }

    /**
     * IME-consumed candidate keys never get here. A forwarded terminal key finalizes preedit
     * first; modifier presses and key-up events alone must not terminate composition.
     */
    fun handleRawKeyEvent(event: KeyEvent): Boolean {
        if (!active) return false
        val boundary = (event.action == KeyEvent.ACTION_DOWN && !KeyEvent.isModifierKey(event.keyCode)) ||
            event.action == KeyEvent.ACTION_MULTIPLE
        val endedEditor = boundary && (editable?.isNotEmpty() == true || rawMode)
        if (boundary) flushPending()
        if (endedEditor) closeConnection()
        val handled = onKey(event)
        if (endedEditor) {
            // A raw Enter/navigation key can beat the IME's final commit. Retire this editor,
            // rather than dropping a later identical word by string-based replay guessing.
            onInvalidate()
        }
        return handled
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean = handleRawKeyEvent(event)

    override fun performEditorAction(editorAction: Int): Boolean {
        if (editorAction !in listOf(EditorInfo.IME_ACTION_NONE, EditorInfo.IME_ACTION_UNSPECIFIED,
                EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_SEND)) return false
        return sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
    }

    override fun performContextMenuAction(id: Int): Boolean {
        if (!active || id !in listOf(android.R.id.paste, android.R.id.pasteAsPlainText)) return false
        val paste = onPaste ?: return false
        flushPending()
        closeConnection()
        paste()
        onInvalidate()
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
        deleteSurrounding(beforeLength, afterLength, codePoints = false)

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
        deleteSurrounding(beforeLength, afterLength, codePoints = true)

    private fun deleteSurrounding(before: Int, after: Int, codePoints: Boolean): Boolean = edit {
        if (before !in 0..MAX_DELETE || after !in 0..MAX_DELETE) return@edit false
        val buffer = editable ?: return@edit false
        val pendingEdit = buffer.toString() != committedEditable
        if (buffer.isEmpty()) {
            // With no locally editable text these are explicit terminal erase keys. In
            // particular (0, 0) is a no-op, not a speculative backspace as in legacy mode.
            repeat(before) { onKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)) }
            repeat(after) { onKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_FORWARD_DEL)) }
        } else if (codePoints) {
            super.deleteSurroundingTextInCodePoints(before, after)
        } else {
            // BaseInputConnection excludes the selection AND the composing region. Keep that
            // contract, but expand a UTF-16 deletion boundary rather than splitting an emoji.
            var a = minOf(Selection.getSelectionStart(buffer), Selection.getSelectionEnd(buffer))
            var b = maxOf(Selection.getSelectionStart(buffer), Selection.getSelectionEnd(buffer))
            if (a < 0) return@edit false
            val ca = getComposingSpanStart(buffer)
            val cb = getComposingSpanEnd(buffer)
            if (ca >= 0 && cb >= 0) { a = minOf(a, ca, cb); b = maxOf(b, ca, cb) }
            var from = (a - before).coerceAtLeast(0)
            var to = (b + after).coerceAtMost(buffer.length)
            if (!unicodeBoundary(buffer, from)) from--
            if (!unicodeBoundary(buffer, to)) to++
            buffer.delete(b, to)
            buffer.delete(from, a)
        }
        if (!pendingEdit && (buffer.isNotEmpty() || committedEditable.isNotEmpty())) sendEdit(false)
        true
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun replaceText(start: Int, end: Int, text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean = edit {
        val buffer = editable ?: return@edit false
        // Never clamp stale offsets from committed terminal history into a fresh insertion.
        if (!unicodeBoundary(buffer, start) || !unicodeBoundary(buffer, end) || !validImeUnicode(text)) return@edit false
        super.replaceText(start, end, text, newCursorPosition, textAttribute)
        repairSelection()
        sendEdit(applyModifiers = false)
        true
    }

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
        if (!active) return null
        if (request != null && flags and InputConnection.GET_EXTRACTED_TEXT_MONITOR != 0) extractedToken = request.token
        return extracted(flags and InputConnection.GET_TEXT_WITH_STYLES != 0)
    }

    private fun extracted(styles: Boolean = false): ExtractedText = ExtractedText().apply {
        val buffer = editable!!
        text = if (styles) SpannedString(buffer) else buffer.toString()
        startOffset = 0
        partialStartOffset = -1
        partialEndOffset = -1
        selectionStart = Selection.getSelectionStart(buffer).coerceAtLeast(0)
        selectionEnd = Selection.getSelectionEnd(buffer).coerceAtLeast(0)
        flags = if ('\n' in buffer) 0 else ExtractedText.FLAG_SINGLE_LINE
    }

    private fun publish() {
        changed = false
        val buffer = editable!!
        onPreedit(preedit)
        onSelection(Selection.getSelectionStart(buffer).coerceAtLeast(0), Selection.getSelectionEnd(buffer).coerceAtLeast(0),
            getComposingSpanStart(buffer), getComposingSpanEnd(buffer))
        extractedToken?.let { onExtractedText(it, extracted()) }
    }

    /** Invalidation cancels unsent text; late IME callbacks must never reach the old terminal. */
    override fun closeConnection() {
        if (!active) return
        active = false
        batchDepth = 0
        clearLocal()
        publish()
        extractedToken = null
        // Base calls finishComposingText virtually; the inactive guard keeps it from sending.
        super.closeConnection()
    }

    companion object {
        private const val MAX_DELETE = 4096
        private const val MAX_COMPOSITION = 4096
    }
}
