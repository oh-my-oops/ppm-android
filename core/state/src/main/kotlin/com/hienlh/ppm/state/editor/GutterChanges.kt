package com.hienlh.ppm.state.editor

import com.hienlh.ppm.state.JsDiff

/**
 * Which lines of the editor's buffer differ from the file in git's index, in the three kinds
 * VS Code's gutter draws (`src/web/lib/gutter-changes.ts`): lines added, lines modified, and
 * lines deleted (a marker between the lines that were either side of them).
 *
 * The lines both sides share at the start and at the end are stepped over first, which leaves an
 * ordinary edit with a middle of a few lines for the real diff (jsdiff's, as `diffArrays`). Only a
 * middle the diff cannot finish within its budget is reported coarsely, as one modified range
 * covering it — never as lines it did not check. The web's budget is 50 ms; here it is steps of
 * the same diff, which a time limit stands in for there.
 */
object GutterChanges {
    enum class Kind { ADDED, MODIFIED, DELETED }

    /**
     * 1-based lines of the buffer, inclusive. For [Kind.DELETED] both are the line the removed
     * lines followed, and 0 means they were above the first line.
     */
    data class Change(val kind: Kind, val startLine: Int, val endLine: Int)

    /** Steps the diff gets before the middle is reported whole. */
    const val DEFAULT_WORK = 1_000_000

    /** Past this the buffer is not diffed (`MAX_LINES` in `use-gutter-changes.ts`). */
    const val MAX_LINES = 100_000

    /**
     * The index's text as Monaco would split it: on `\r\n`, `\r` or `\n`, with no byte-order mark,
     * and a final newline leaving an empty last line.
     */
    fun splitBaseLines(text: String): List<String> =
        (if (text.isNotEmpty() && text[0] == '﻿') text.substring(1) else text).split(LINE_BREAK)

    private val LINE_BREAK = Regex("\r\n|\r|\n")

    /**
     * What one line's gutter draws: a bar ([bar], added or modified), and a deletion wedge on the
     * boundary below it ([deletedBelow]) or, for lines deleted from the top of the file, above
     * line 1 ([deletedAbove]).
     */
    data class RowMark(val bar: Kind? = null, val deletedBelow: Boolean = false, val deletedAbove: Boolean = false)

    /**
     * [changes] by 0-based row of a buffer of [lineCount] lines, clamped to it as
     * `toDecorations` clamps Monaco's ranges.
     */
    fun rowMarks(changes: List<Change>, lineCount: Int): Map<Int, RowMark> {
        if (lineCount <= 0) return emptyMap()
        val marks = HashMap<Int, RowMark>()
        for (change in changes) {
            val start = change.startLine.coerceIn(1, lineCount)
            if (change.kind == Kind.DELETED) {
                val row = start - 1
                val mark = marks[row] ?: RowMark()
                marks[row] = if (change.startLine == 0) mark.copy(deletedAbove = true) else mark.copy(deletedBelow = true)
                continue
            }
            val end = change.endLine.coerceIn(1, lineCount)
            for (line in start..end) {
                val row = line - 1
                marks[row] = (marks[row] ?: RowMark()).copy(bar = change.kind)
            }
        }
        return marks
    }

    fun compute(base: List<String>, current: List<String>, work: Int = DEFAULT_WORK): List<Change> {
        val shorter = minOf(base.size, current.size)
        var head = 0
        while (head < shorter && base[head] == current[head]) head++
        var tail = 0
        while (tail < shorter - head && base[base.size - 1 - tail] == current[current.size - 1 - tail]) tail++

        val removed = base.size - head - tail
        val added = current.size - head - tail
        if (removed == 0 && added == 0) return emptyList()
        if (removed == 0) return listOf(Change(Kind.ADDED, head + 1, head + added))
        if (added == 0) return listOf(Change(Kind.DELETED, head, head))

        val runs = JsDiff.runs(base.subList(head, base.size - tail), current.subList(head, current.size - tail), work)
            ?: return listOf(Change(Kind.MODIFIED, head + 1, head + added))

        val changes = mutableListOf<Change>()
        // Lines of the buffer passed so far, i.e. the 1-based line a change ends on.
        var line = head
        var pendingRemoved = 0
        var pendingAdded = 0
        fun flush() {
            if (pendingAdded > 0) {
                val kind = if (pendingRemoved > 0) Kind.MODIFIED else Kind.ADDED
                changes += Change(kind, line - pendingAdded + 1, line)
            } else if (pendingRemoved > 0) {
                changes += Change(Kind.DELETED, line, line)
            }
            pendingRemoved = 0
            pendingAdded = 0
        }
        for (run in runs) {
            when {
                run.added -> {
                    pendingAdded += run.count
                    line += run.count
                }
                run.removed -> pendingRemoved += run.count
                else -> {
                    flush()
                    line += run.count
                }
            }
        }
        flush()
        return changes
    }
}
