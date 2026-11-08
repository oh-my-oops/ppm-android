package com.hienlh.ppm.state.editor

import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min

/** Maximum UTF-16 code units returned to Android InputConnection IPC calls to prevent Binder TransactionTooLargeException. */
const val MAX_IME_SLICE_CHARS: Int = 4096

data class EditorFindMatch(
    val start: Int,
    val end: Int,
    val groups: List<String?> = emptyList(),
)

data class BracketMatch(
    val openOffset: Int,
    val closeOffset: Int,
)

private data class UndoEntry(
    val start: Int,
    val oldText: String,
    val newText: String,
    val selBeforeStart: Int,
    val selBeforeEnd: Int,
    val selAfterStart: Int,
    val selAfterEnd: Int,
    val timestampMs: Long,
    val coalescable: Boolean,
)

/**
 * Pure-JVM state machine backing the native code editor (Phase 07a):
 * - Piece-table document buffer ([EditorBuffer])
 * - Separate selection `[selectionStart, selectionEnd)` and IME composition `[composingStart, composingEnd)`
 * - Surrogate-safe bounded IME getters (`<= 4096` chars)
 * - Undo/Redo stack with keystroke coalescing
 * - Auto-closing brackets/quotes, smart Enter indent, Tab/Shift+Tab indent/outdent, Toggle Line Comment
 * - Find & Replace with case-sensitivity, whole-word, and regex `$1`..`$9` / `$&` group expansion
 */
class EditorSessionState(
    initialText: String = "",
    var language: String = "plaintext",
    var readOnly: Boolean = false,
) {
    val buffer = EditorBuffer(initialText)

    var selectionStart: Int = 0
        private set
    var selectionEnd: Int = 0
        private set

    var composingStart: Int = -1
        private set
    var composingEnd: Int = -1
        private set

    val hasComposition: Boolean
        get() = composingStart in 0 until composingEnd && composingEnd <= buffer.length

    val selectionMin: Int
        get() = min(selectionStart, selectionEnd)

    val selectionMax: Int
        get() = max(selectionStart, selectionEnd)

    private var batchDepth: Int = 0
    private val undoStack = ArrayList<UndoEntry>()
    private val redoStack = ArrayList<UndoEntry>()

    var indentStyle: IndentStyle = buffer.detectIndentation()

    // =======================================================================
    // Surrogate boundary helpers
    // =======================================================================

    /** Ensures [offset] does not split a UTF-16 surrogate pair by retreating to the high surrogate. */
    fun clampSurrogateStart(offset: Int): Int {
        val c = offset.coerceIn(0, buffer.length)
        if (c > 0 && c < buffer.length && Character.isLowSurrogate(buffer[c]) && Character.isHighSurrogate(buffer[c - 1])) {
            return c - 1
        }
        return c
    }

    /** Ensures [offset] does not split a UTF-16 surrogate pair by advancing past the low surrogate. */
    fun clampSurrogateEnd(offset: Int): Int {
        val c = offset.coerceIn(0, buffer.length)
        if (c > 0 && c < buffer.length && Character.isLowSurrogate(buffer[c]) && Character.isHighSurrogate(buffer[c - 1])) {
            return c + 1
        }
        return c
    }

    // =======================================================================
    // Selection & Composition
    // =======================================================================

    fun setSelection(start: Int, end: Int = start) {
        selectionStart = clampSurrogateStart(start)
        selectionEnd = clampSurrogateEnd(end)
    }

    fun selectAll() {
        selectionStart = 0
        selectionEnd = buffer.length
    }

    fun beginBatchEdit(): Boolean {
        batchDepth++
        return true
    }

    fun endBatchEdit(): Boolean {
        if (batchDepth > 0) batchDepth--
        return batchDepth > 0
    }

    fun setComposingRegion(start: Int, end: Int): Boolean {
        val s = clampSurrogateStart(min(start, end))
        val e = clampSurrogateEnd(max(start, end))
        if (s == e) {
            composingStart = -1
            composingEnd = -1
        } else {
            composingStart = s
            composingEnd = e
        }
        return true
    }

    fun finishComposingText(): Boolean {
        composingStart = -1
        composingEnd = -1
        return true
    }

    /**
     * Android `InputConnection.setComposingText(text, newCursorPosition)`:
     * Replaces current composing region (or selection if none), updates `[composingStart, composingEnd)`,
     * and positions the cursor relative to the new composition.
     */
    fun setComposingText(text: CharSequence, newCursorPosition: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (readOnly) return false
        val norm = text.toString().replace("\r\n", "\n").replace('\r', '\n')
        val replaceStart = if (hasComposition) composingStart else selectionMin
        val replaceEnd = if (hasComposition) composingEnd else selectionMax

        applyEdit(
            start = replaceStart,
            end = replaceEnd,
            replacement = norm,
            nowMs = nowMs,
            coalescable = true,
        )

        val insertEnd = replaceStart + norm.length
        if (norm.isEmpty()) {
            composingStart = -1
            composingEnd = -1
        } else {
            composingStart = replaceStart
            composingEnd = insertEnd
        }

        val cursor = if (newCursorPosition > 0) {
            insertEnd + newCursorPosition - 1
        } else {
            replaceStart + newCursorPosition
        }.coerceIn(0, buffer.length)
        setSelection(cursor, cursor)
        return true
    }

    /**
     * Android `InputConnection.commitText(text, newCursorPosition)`:
     * Replaces current composing region (or selection if none), clears composition, and sets cursor.
     */
    fun commitText(text: CharSequence, newCursorPosition: Int = 1, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (readOnly) return false
        val norm = text.toString().replace("\r\n", "\n").replace('\r', '\n')
        val replaceStart = if (hasComposition) composingStart else selectionMin
        val replaceEnd = if (hasComposition) composingEnd else selectionMax

        val singleChar = norm.length == 1 && norm[0] != '\n' && (replaceEnd - replaceStart) <= 1
        applyEdit(
            start = replaceStart,
            end = replaceEnd,
            replacement = norm,
            nowMs = nowMs,
            coalescable = singleChar,
        )

        composingStart = -1
        composingEnd = -1

        val insertEnd = replaceStart + norm.length
        val cursor = if (newCursorPosition > 0) {
            insertEnd + newCursorPosition - 1
        } else {
            replaceStart + newCursorPosition
        }.coerceIn(0, buffer.length)
        setSelection(cursor, cursor)
        return true
    }

    /**
     * Android `InputConnection.deleteSurroundingText(beforeLength, afterLength)`:
     * Deletes [beforeLength] UTF-16 code units before selection and [afterLength] after selection,
     * clamped to surrogate boundaries so surrogate pairs are never split.
     */
    fun deleteSurroundingText(beforeLength: Int, afterLength: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (readOnly) return false
        val before = max(0, beforeLength)
        val after = max(0, afterLength)
        val selMin = selectionMin
        val selMax = selectionMax

        val delAfterEnd = clampSurrogateEnd((selMax + after).coerceAtMost(buffer.length))
        if (delAfterEnd > selMax) {
            applyEdit(selMax, delAfterEnd, "", nowMs = nowMs, coalescable = true)
        }

        val delBeforeStart = clampSurrogateStart((selMin - before).coerceAtLeast(0))
        if (delBeforeStart < selMin) {
            val removed = selMin - delBeforeStart
            applyEdit(delBeforeStart, selMin, "", nowMs = nowMs, coalescable = true)
            setSelection(selMin - removed, selMax - removed)
        }
        composingStart = -1
        composingEnd = -1
        return true
    }

    /**
     * Android `InputConnection.deleteSurroundingTextInCodePoints(beforeLength, afterLength)`:
     * Counts Unicode code points rather than UTF-16 chars.
     */
    fun deleteSurroundingTextInCodePoints(beforeCodePoints: Int, afterCodePoints: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (readOnly) return false
        val selMin = selectionMin
        val selMax = selectionMax

        var beforeIdx = selMin
        var remainingBefore = max(0, beforeCodePoints)
        while (remainingBefore > 0 && beforeIdx > 0) {
            beforeIdx--
            if (beforeIdx > 0 && Character.isLowSurrogate(buffer[beforeIdx]) && Character.isHighSurrogate(buffer[beforeIdx - 1])) {
                beforeIdx--
            }
            remainingBefore--
        }

        var afterIdx = selMax
        var remainingAfter = max(0, afterCodePoints)
        while (remainingAfter > 0 && afterIdx < buffer.length) {
            val ch = buffer[afterIdx]
            afterIdx += if (Character.isHighSurrogate(ch) && afterIdx + 1 < buffer.length && Character.isLowSurrogate(buffer[afterIdx + 1])) 2 else 1
            remainingAfter--
        }

        return deleteSurroundingText(selMin - beforeIdx, afterIdx - selMax, nowMs)
    }

    // =======================================================================
    // Bounded Binder IPC Slices (<= MAX_IME_SLICE_CHARS, surrogate-safe)
    // =======================================================================

    fun getTextBeforeCursor(n: Int): String {
        val capped = n.coerceIn(0, MAX_IME_SLICE_CHARS)
        val end = selectionMin
        val start = clampSurrogateStart((end - capped).coerceAtLeast(0))
        return buffer.substring(start, end)
    }

    fun getTextAfterCursor(n: Int): String {
        val capped = n.coerceIn(0, MAX_IME_SLICE_CHARS)
        val start = selectionMax
        val end = clampSurrogateEnd((start + capped).coerceAtMost(buffer.length))
        return buffer.substring(start, end)
    }

    fun getSelectedText(): String? {
        val start = selectionMin
        val end = selectionMax
        if (start == end) return null
        val cappedEnd = clampSurrogateEnd(min(end, start + MAX_IME_SLICE_CHARS))
        return buffer.substring(start, cappedEnd)
    }

    // =======================================================================
    // Undo / Redo Stack with Keystroke Coalescing
    // =======================================================================

    private fun applyEdit(
        start: Int,
        end: Int,
        replacement: String,
        nowMs: Long = System.currentTimeMillis(),
        coalescable: Boolean = false,
        selAfterStart: Int = start + replacement.length,
        selAfterEnd: Int = start + replacement.length,
    ) {
        val s = start.coerceIn(0, buffer.length)
        val e = end.coerceIn(s, buffer.length)
        val oldText = buffer.substring(s, e)
        if (oldText == replacement) return

        val beforeSelStart = selectionStart
        val beforeSelEnd = selectionEnd

        buffer.replace(s, e, replacement)
        redoStack.clear()

        val last = undoStack.lastOrNull()
        val canMergeInsert = coalescable &&
            last != null &&
            last.coalescable &&
            last.oldText.isEmpty() &&
            oldText.isEmpty() &&
            s == last.start + last.newText.length &&
            (nowMs - last.timestampMs) <= 400L
        val canMergeDelete = coalescable &&
            last != null &&
            last.coalescable &&
            last.newText.isEmpty() &&
            replacement.isEmpty() &&
            e == last.start &&
            (nowMs - last.timestampMs) <= 400L

        when {
            canMergeInsert && last != null -> {
                undoStack[undoStack.lastIndex] = last.copy(
                    newText = last.newText + replacement,
                    selAfterStart = selAfterStart,
                    selAfterEnd = selAfterEnd,
                    timestampMs = nowMs,
                )
            }
            canMergeDelete && last != null -> {
                undoStack[undoStack.lastIndex] = last.copy(
                    start = s,
                    oldText = oldText + last.oldText,
                    selAfterStart = selAfterStart,
                    selAfterEnd = selAfterEnd,
                    timestampMs = nowMs,
                )
            }
            else -> {
                undoStack.add(
                    UndoEntry(
                        start = s,
                        oldText = oldText,
                        newText = replacement,
                        selBeforeStart = beforeSelStart,
                        selBeforeEnd = beforeSelEnd,
                        selAfterStart = selAfterStart,
                        selAfterEnd = selAfterEnd,
                        timestampMs = nowMs,
                        coalescable = coalescable,
                    ),
                )
            }
        }
        setSelection(selAfterStart, selAfterEnd)
    }

    val canUndo: Boolean get() = !readOnly && undoStack.isNotEmpty()
    val canRedo: Boolean get() = !readOnly && redoStack.isNotEmpty()

    fun undo(): Boolean {
        if (!canUndo) return false
        val entry = undoStack.removeAt(undoStack.lastIndex)
        composingStart = -1
        composingEnd = -1
        buffer.replace(entry.start, entry.start + entry.newText.length, entry.oldText)
        redoStack.add(entry)
        setSelection(entry.selBeforeStart, entry.selBeforeEnd)
        return true
    }

    fun redo(): Boolean {
        if (!canRedo) return false
        val entry = redoStack.removeAt(redoStack.lastIndex)
        composingStart = -1
        composingEnd = -1
        buffer.replace(entry.start, entry.start + entry.oldText.length, entry.newText)
        undoStack.add(entry)
        setSelection(entry.selAfterStart, entry.selAfterEnd)
        return true
    }

    /** Replaces full buffer from external reload (`file:changed`) and resets undo history. */
    fun reloadExternalContent(newText: String) {
        composingStart = -1
        composingEnd = -1
        undoStack.clear()
        redoStack.clear()
        buffer.eol = detectEol(newText, fallback = buffer.eol)
        buffer.replace(0, buffer.length, newText)
        indentStyle = buffer.detectIndentation()
        setSelection(selectionStart.coerceAtMost(buffer.length), selectionEnd.coerceAtMost(buffer.length))
    }

    /** Returns the buffer content serialized with the file's detected line endings (`\r\n`, `\r`, or `\n`). */
    fun toDiskString(): String = buffer.toDiskString()

    // =======================================================================
    // Phase 07a.5 Core Editing Commands
    // =======================================================================

    companion object {
        private val AUTO_PAIRS = mapOf(
            '(' to ')',
            '{' to '}',
            '[' to ']',
            '"' to '"',
            '\'' to '\'',
            '`' to '`',
        )
        private val CLOSING_CHARS = setOf(')', '}', ']', '"', '\'', '`')
    }

    /**
     * Handles typing a character with auto-closing brackets/quotes and selection wrapping.
     */
    fun typeChar(ch: Char, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (readOnly) return false
        val selMin = selectionMin
        val selMax = selectionMax
        val close = AUTO_PAIRS[ch]

        // Wrap non-empty selection with pair
        if (close != null && selMin < selMax) {
            val selected = buffer.substring(selMin, selMax)
            val wrapped = "$ch$selected$close"
            applyEdit(
                start = selMin,
                end = selMax,
                replacement = wrapped,
                nowMs = nowMs,
                coalescable = false,
                selAfterStart = selMin + 1,
                selAfterEnd = selMax + 1,
            )
            return true
        }

        // Skip over existing closing character if typing it at cursor
        if (ch in CLOSING_CHARS && selMin == selMax && selMin < buffer.length && buffer[selMin] == ch && ch !in setOf('"', '\'', '`')) {
            setSelection(selMin + 1, selMin + 1)
            return true
        }

        // Insert auto-pair when cursor is at EOL or before whitespace/closing bracket
        if (close != null && selMin == selMax) {
            val nextChar = if (selMin < buffer.length) buffer[selMin] else '\n'
            if (nextChar.isWhitespace() || nextChar in CLOSING_CHARS) {
                applyEdit(
                    start = selMin,
                    end = selMax,
                    replacement = "$ch$close",
                    nowMs = nowMs,
                    coalescable = false,
                    selAfterStart = selMin + 1,
                    selAfterEnd = selMin + 1,
                )
                return true
            }
        }

        return commitText(ch.toString(), 1, nowMs)
    }

    /**
     * Inserts a newline preserving the current line's leading indentation, plus an extra indent unit
     * when the character before the cursor is `{`, `(`, `[`, or `:`.
     */
    fun insertNewlineWithIndent(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (readOnly) return false
        val selMin = selectionMin
        val selMax = selectionMax
        val pos = buffer.offsetToLineCol(selMin)
        val currentLine = buffer.line(pos.line)
        val leading = currentLine.takeWhile { it == ' ' || it == '\t' }
        val prevChar = if (selMin > 0) buffer[selMin - 1] else ' '
        val nextChar = if (selMax < buffer.length) buffer[selMax] else ' '

        val unit = indentStyle.unit
        if ((prevChar == '{' && nextChar == '}') || (prevChar == '(' && nextChar == ')') || (prevChar == '[' && nextChar == ']')) {
            val middle = "\n$leading$unit"
            val full = "$middle\n$leading"
            val cursor = selMin + middle.length
            applyEdit(selMin, selMax, full, nowMs = nowMs, coalescable = false, selAfterStart = cursor, selAfterEnd = cursor)
            return true
        }

        val extra = if (prevChar in setOf('{', '(', '[', ':')) unit else ""
        val insertion = "\n$leading$extra"
        val cursor = selMin + insertion.length
        applyEdit(selMin, selMax, insertion, nowMs = nowMs, coalescable = false, selAfterStart = cursor, selAfterEnd = cursor)
        return true
    }

    /** Indents the selected lines (or inserts an indent unit at the cursor). */
    fun indentSelection(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (readOnly) return false
        val selMin = selectionMin
        val selMax = selectionMax
        val unit = indentStyle.unit
        val startLine = buffer.offsetToLineCol(selMin).line
        val endLine = buffer.offsetToLineCol(selMax).line
        if (startLine == endLine && selMin == selMax) {
            return commitText(unit, 1, nowMs)
        }
        val blockStart = buffer.lineStart(startLine)
        val blockEnd = buffer.lineEnd(endLine)
        val lines = (startLine..endLine).map { unit + buffer.line(it) }
        val replaced = lines.joinToString("\n")
        applyEdit(
            start = blockStart,
            end = blockEnd,
            replacement = replaced,
            nowMs = nowMs,
            coalescable = false,
            selAfterStart = blockStart,
            selAfterEnd = blockStart + replaced.length,
        )
        return true
    }

    /** Outdents the selected lines by one indent unit. */
    fun outdentSelection(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (readOnly) return false
        val selMin = selectionMin
        val selMax = selectionMax
        val startLine = buffer.offsetToLineCol(selMin).line
        val endLine = buffer.offsetToLineCol(selMax).line
        val blockStart = buffer.lineStart(startLine)
        val blockEnd = buffer.lineEnd(endLine)
        val spaceUnit = " ".repeat(indentStyle.size)
        val lines = (startLine..endLine).map { row ->
            val l = buffer.line(row)
            when {
                l.startsWith("\t") -> l.substring(1)
                l.startsWith(spaceUnit) -> l.substring(spaceUnit.length)
                else -> l.trimStart { it == ' ' }.let { trimmed ->
                    val stripped = min(indentStyle.size, l.length - trimmed.length)
                    l.substring(stripped)
                }
            }
        }
        val replaced = lines.joinToString("\n")
        applyEdit(
            start = blockStart,
            end = blockEnd,
            replacement = replaced,
            nowMs = nowMs,
            coalescable = false,
            selAfterStart = blockStart,
            selAfterEnd = blockStart + replaced.length,
        )
        return true
    }

    /** Toggles line comments (`//`, `#`, `--`) across all selected lines based on [language]. */
    fun toggleLineComment(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (readOnly) return false
        val prefix = lineCommentPrefixForLanguage(language) ?: return false
        val selMin = selectionMin
        val selMax = selectionMax
        val startLine = buffer.offsetToLineCol(selMin).line
        val endLine = buffer.offsetToLineCol(selMax).line
        val blockStart = buffer.lineStart(startLine)
        val blockEnd = buffer.lineEnd(endLine)

        val rawLines = (startLine..endLine).map { buffer.line(it) }
        val nonEmpty = rawLines.filter { it.isNotBlank() }
        val allCommented = nonEmpty.isNotEmpty() && nonEmpty.all { it.trimStart().startsWith(prefix) }

        val updated = rawLines.map { line ->
            if (line.isBlank()) {
                line
            } else if (allCommented) {
                val idx = line.indexOf(prefix)
                val afterIdx = idx + prefix.length
                val stripSpace = if (afterIdx < line.length && line[afterIdx] == ' ') afterIdx + 1 else afterIdx
                line.removeRange(idx, stripSpace)
            } else {
                val leadingLen = line.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                line.substring(0, leadingLen) + "$prefix " + line.substring(leadingLen)
            }
        }
        val replaced = updated.joinToString("\n")
        applyEdit(
            start = blockStart,
            end = blockEnd,
            replacement = replaced,
            nowMs = nowMs,
            coalescable = false,
            selAfterStart = blockStart,
            selAfterEnd = blockStart + replaced.length,
        )
        return true
    }

    /** Moves cursor to 1-based [lineOneBased] and [columnOneBased]. */
    fun goToLine(lineOneBased: Int, columnOneBased: Int = 1): Int {
        val offset = buffer.lineColToOffset((lineOneBased - 1).coerceAtLeast(0), (columnOneBased - 1).coerceAtLeast(0))
        setSelection(offset, offset)
        return offset
    }

    // =======================================================================
    // Find & Replace (with $1..$9 and $& regex group expansion)
    // =======================================================================

    fun findAll(
        query: String,
        caseSensitive: Boolean = false,
        wholeWord: Boolean = false,
        isRegex: Boolean = false,
    ): List<EditorFindMatch> {
        if (query.isEmpty()) return emptyList()
        val text = buffer.toString()
        val rawPattern = if (isRegex) query else Pattern.quote(query)
        val wordPattern = if (wholeWord) "\\b(?:$rawPattern)\\b" else rawPattern
        val flags = if (caseSensitive) Pattern.MULTILINE else (Pattern.MULTILINE or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        val compiled = runCatching { Pattern.compile(wordPattern, flags) }.getOrNull() ?: return emptyList()
        val matcher = compiled.matcher(text)
        val matches = ArrayList<EditorFindMatch>()
        while (matcher.find()) {
            if (matcher.start() == matcher.end()) continue
            val groups = (0..matcher.groupCount()).map { matcher.group(it) }
            matches.add(EditorFindMatch(start = matcher.start(), end = matcher.end(), groups = groups))
        }
        return matches
    }

    /**
     * Expands `$1`..`$9` and `$&` in [replacement] when [isRegex] is true.
     * Fixes the web bug where `$1` outputted the full match (`$0`) instead of capture group 1.
     */
    fun expandReplacement(replacement: String, match: EditorFindMatch, isRegex: Boolean): String {
        if (!isRegex || '$' !in replacement) return replacement
        val out = StringBuilder(replacement.length)
        var i = 0
        while (i < replacement.length) {
            val ch = replacement[i]
            if (ch == '$' && i + 1 < replacement.length) {
                val next = replacement[i + 1]
                when {
                    next == '$' -> {
                        out.append('$')
                        i += 2
                        continue
                    }
                    next == '&' -> {
                        out.append(match.groups.getOrNull(0).orEmpty())
                        i += 2
                        continue
                    }
                    next in '1'..'9' -> {
                        val groupIdx = next - '0'
                        out.append(match.groups.getOrNull(groupIdx).orEmpty())
                        i += 2
                        continue
                    }
                }
            }
            out.append(ch)
            i++
        }
        return out.toString()
    }

    fun replaceMatch(
        match: EditorFindMatch,
        replacement: String,
        isRegex: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (readOnly) return false
        val expanded = expandReplacement(replacement, match, isRegex)
        applyEdit(
            start = match.start,
            end = match.end,
            replacement = expanded,
            nowMs = nowMs,
            coalescable = false,
        )
        return true
    }

    fun replaceAll(
        query: String,
        replacement: String,
        caseSensitive: Boolean = false,
        wholeWord: Boolean = false,
        isRegex: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ): Int {
        if (readOnly) return 0
        val matches = findAll(query, caseSensitive = caseSensitive, wholeWord = wholeWord, isRegex = isRegex)
        if (matches.isEmpty()) return 0
        val full = buffer.toString()
        val sb = StringBuilder(full.length)
        var lastEnd = 0
        for (m in matches) {
            sb.append(full, lastEnd, m.start)
            sb.append(expandReplacement(replacement, m, isRegex))
            lastEnd = m.end
        }
        sb.append(full, lastEnd, full.length)
        applyEdit(
            start = 0,
            end = buffer.length,
            replacement = sb.toString(),
            nowMs = nowMs,
            coalescable = false,
        )
        return matches.size
    }

    // =======================================================================
    // Bracket Matching (bounded window scan)
    // =======================================================================

    fun findMatchingBracket(cursorOffset: Int = selectionMin, maxScanChars: Int = 20_000): BracketMatch? {
        if (buffer.length == 0) return null
        val pairs = mapOf('(' to ')', '{' to '}', '[' to ']')
        val reversePairs = mapOf(')' to '(', '}' to '{', ']' to '[')

        val candidates = listOf(cursorOffset, cursorOffset - 1).filter { it in 0 until buffer.length }
        for (pos in candidates) {
            val ch = buffer[pos]
            if (ch in pairs) {
                val target = pairs.getValue(ch)
                val limit = min(buffer.length, pos + maxScanChars)
                var depth = 0
                for (i in pos until limit) {
                    val c = buffer[i]
                    if (c == ch) depth++
                    else if (c == target) {
                        depth--
                        if (depth == 0) return BracketMatch(openOffset = pos, closeOffset = i)
                    }
                }
            } else if (ch in reversePairs) {
                val target = reversePairs.getValue(ch)
                val limit = max(0, pos - maxScanChars)
                var depth = 0
                for (i in pos downTo limit) {
                    val c = buffer[i]
                    if (c == ch) depth++
                    else if (c == target) {
                        depth--
                        if (depth == 0) return BracketMatch(openOffset = i, closeOffset = pos)
                    }
                }
            }
        }
        return null
    }
}

fun lineCommentPrefixForLanguage(language: String): String? = when (language.lowercase()) {
    "typescript", "javascript", "kotlin", "java", "go", "rust", "c", "cpp", "csharp", "swift", "scss", "php" -> "//"
    "python", "shell", "bash", "yaml", "toml", "ruby", "dotenv", "make", "dockerfile" -> "#"
    "sql", "lua" -> "--"
    else -> null
}
