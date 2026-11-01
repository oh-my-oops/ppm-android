package org.connectbot.terminal

import java.text.Normalizer

/** Only text emitted by one IME editor, never text read from the terminal screen. */
internal class TerminalImeHistory(private val limit: Int = LIMIT) {
    var text: String = ""
        private set

    data class Rewrite(val deleteCodePoints: Int, val append: String, val boundary: Boolean)

    fun update(editorText: String): Rewrite {
        val next = Normalizer.normalize(editorText, Normalizer.Form.NFC)
        var common = 0
        while (common < minOf(text.length, next.length) && text[common] == next[common]) common++
        if (!unicodeBoundary(text, common) || !unicodeBoundary(next, common)) common--
        val boundary = editorText.length > limit || next.any { it < ' ' || it == '\u007f' }
        val result = Rewrite(text.codePointCount(common, text.length), next.substring(common), boundary)
        // Update before calling any terminal callback, including a reentrant close/restart.
        text = if (boundary) "" else next
        return result
    }

    fun clear() { text = "" }

    companion object { const val LIMIT = 2048 }
}

internal fun unicodeBoundary(text: CharSequence, index: Int): Boolean = index in 0..text.length &&
    !(index > 0 && index < text.length && text[index - 1].isHighSurrogate() && text[index].isLowSurrogate())

internal fun validImeUnicode(text: CharSequence): Boolean {
    var i = 0
    while (i < text.length) {
        val char = text[i++]
        if (char.isHighSurrogate()) {
            if (i == text.length || !text[i++].isLowSurrogate()) return false
        } else if (char.isLowSurrogate()) return false
    }
    return true
}
