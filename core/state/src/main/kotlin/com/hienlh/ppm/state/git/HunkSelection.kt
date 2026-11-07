package com.hienlh.ppm.state.git

import com.hienlh.ppm.client.DiffHunk
import com.hienlh.ppm.client.HunkSelectionItem

data class LineNumberPair(val old: Int?, val new: Int?)

/**
 * Pure selection helpers for the hunk/line staging modal (`src/web/components/git/hunk-selection.ts`).
 */
object HunkSelection {
    fun lineKey(hunkIndex: Int, lineIndex: Int): String = "$hunkIndex:$lineIndex"

    /** Indexes of changed (`+` / `-`) lines within a hunk. Context lines are not selectable. */
    fun changedLineIndexes(hunk: DiffHunk): List<Int> {
        val out = mutableListOf<Int>()
        hunk.lines.forEachIndexed { idx, line ->
            if (line.type != " ") out += idx
        }
        return out
    }

    /** Select every changed line across all hunks (initial state when the modal opens). */
    fun allChangedKeys(hunks: List<DiffHunk>): Set<String> {
        val set = LinkedHashSet<String>()
        for (h in hunks) {
            for (idx in changedLineIndexes(h)) {
                set += lineKey(h.index, idx)
            }
        }
        return set
    }

    fun hunkState(hunk: DiffHunk, selected: Set<String>): CheckState {
        val changed = changedLineIndexes(hunk)
        if (changed.isEmpty()) return CheckState.NONE
        var n = 0
        for (idx in changed) {
            if (lineKey(hunk.index, idx) in selected) n++
        }
        if (n == changed.size) return CheckState.ALL
        if (n > 0) return CheckState.SOME
        return CheckState.NONE
    }

    fun toggleHunk(hunk: DiffHunk, selected: Set<String>): Set<String> {
        val next = LinkedHashSet(selected)
        val changed = changedLineIndexes(hunk)
        val allOn = hunkState(hunk, selected) == CheckState.ALL
        for (idx in changed) {
            val k = lineKey(hunk.index, idx)
            if (allOn) next.remove(k) else next.add(k)
        }
        return next
    }

    fun toggleLine(hunkIndex: Int, lineIndex: Int, selected: Set<String>): Set<String> {
        val next = LinkedHashSet(selected)
        val k = lineKey(hunkIndex, lineIndex)
        if (k in next) next.remove(k) else next.add(k)
        return next
    }

    /**
     * Build the request payload for `/git/{stage,unstage,discard}-hunks`:
     * - Every changed line in a hunk selected -> omit `lines` so the server applies the whole hunk fast-path.
     * - Subset selected -> include the 0-based `lines` indexes.
     * - None selected -> hunk omitted.
     */
    fun buildHunkRequest(hunks: List<DiffHunk>, selected: Set<String>): List<HunkSelectionItem> {
        val out = mutableListOf<HunkSelectionItem>()
        for (h in hunks) {
            val changed = changedLineIndexes(h)
            val picked = changed.filter { lineKey(h.index, it) in selected }
            if (picked.isEmpty()) continue
            if (picked.size == changed.size) {
                out += HunkSelectionItem(hunk = h.index, id = h.id)
            } else {
                out += HunkSelectionItem(hunk = h.index, id = h.id, lines = picked)
            }
        }
        return out
    }

    /** Compute old/new line numbers for every line in a hunk. */
    fun lineNumbers(hunk: DiffHunk): List<LineNumberPair> {
        var oldNum = hunk.oldStart
        var newNum = hunk.newStart
        return hunk.lines.map { line ->
            when (line.type) {
                " " -> LineNumberPair(old = oldNum++, new = newNum++)
                "-" -> LineNumberPair(old = oldNum++, new = null)
                else -> LineNumberPair(old = null, new = newNum++)
            }
        }
    }
}
