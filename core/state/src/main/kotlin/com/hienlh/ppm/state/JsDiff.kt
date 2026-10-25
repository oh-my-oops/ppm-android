package com.hienlh.ppm.state

/**
 * jsdiff 9's `diffLines` (and the `Diff.diff` under every diff of it), as the web runs it: the
 * Edit preview diffs lines and words with it, and the change tray counts each edit's lines.
 */
object JsDiff {
    /** `diffLines`' tokens: each line with the line break that ends it, and none for an empty text. */
    fun lines(text: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        while (true) {
            val end = text.indexOf('\n', start)
            if (end < 0) break
            out += text.substring(start, end + 1)
            start = end + 1
        }
        if (start < text.length) out += text.substring(start)
        return out
    }

    /** The lines `diffLines(old, new)` marks added and removed (`countLines`). */
    fun countLines(old: String, new: String): Pair<Int, Int> {
        val (removed, inserted) = diff(lines(old), lines(new))
        return inserted.count { it } to removed.count { it }
    }

    /**
     * Which items of [a] are removed and which of [b] are inserted, as jsdiff's `Diff.diff`
     * finds them. Past [WORK] steps — thousands of lines rewritten — the part between the shared
     * head and tail is one replacement instead: jsdiff would go on, and the web with it, for as
     * long as it takes.
     */
    fun <T> diff(a: List<T>, b: List<T>): Pair<BooleanArray, BooleanArray> = jsdiff(a, b) ?: replaced(a, b)

    private const val WORK = 4_000_000

    /** A run of jsdiff's change objects: [count] items kept, added or removed. */
    private class Component(val count: Int, val added: Boolean, val removed: Boolean, val previous: Component?)

    private class Path(var oldPos: Int, var last: Component?)

    /** `Diff.diffWithOptionsObj` with no options, line for line: its Myers variant, pruned diagonals and ties included. */
    private fun <T> jsdiff(a: List<T>, b: List<T>): Pair<BooleanArray, BooleanArray>? {
        val oldLen = a.size
        val newLen = b.size
        val maxEditLength = oldLen + newLen
        val offset = maxEditLength + 1
        val bestPath = arrayOfNulls<Path>(2 * maxEditLength + 3)
        var work = 0

        // Follows the diagonal while both sides agree; answers the new side's position.
        fun extractCommon(path: Path, diagonal: Int): Int {
            var oldPos = path.oldPos
            var newPos = oldPos - diagonal
            var common = 0
            while (newPos + 1 < newLen && oldPos + 1 < oldLen && a[oldPos + 1] == b[newPos + 1]) {
                newPos++
                oldPos++
                common++
            }
            work += common
            if (common > 0) path.last = Component(common, added = false, removed = false, previous = path.last)
            path.oldPos = oldPos
            return newPos
        }

        fun addToPath(path: Path, added: Boolean, removed: Boolean, oldPosInc: Int): Path {
            val last = path.last
            return if (last != null && last.added == added && last.removed == removed) {
                Path(path.oldPos + oldPosInc, Component(last.count + 1, added, removed, last.previous))
            } else {
                Path(path.oldPos + oldPosInc, Component(1, added, removed, last))
            }
        }

        val first = Path(-1, null)
        bestPath[offset] = first
        var newPos = extractCommon(first, 0)
        if (first.oldPos + 1 >= oldLen && newPos + 1 >= newLen) return marks(first.last, oldLen, newLen)
        var minDiagonal = Int.MIN_VALUE
        var maxDiagonal = Int.MAX_VALUE
        for (editLength in 1..maxEditLength) {
            var diagonal = maxOf(minDiagonal, -editLength)
            // jsdiff reads its bound again on every step: a path reaching the right edge narrows it.
            while (diagonal <= minOf(maxDiagonal, editLength)) {
                if (++work > WORK) return null
                val removePath = bestPath[offset + diagonal - 1]
                val addPath = bestPath[offset + diagonal + 1]
                if (removePath != null) bestPath[offset + diagonal - 1] = null
                val canAdd = addPath != null && addPath.oldPos - diagonal in 0 until newLen
                val canRemove = removePath != null && removePath.oldPos + 1 < oldLen
                if (!canAdd && !canRemove) {
                    bestPath[offset + diagonal] = null
                    diagonal += 2
                    continue
                }
                val base = if (!canRemove || (canAdd && removePath!!.oldPos < addPath!!.oldPos)) {
                    addToPath(addPath!!, added = true, removed = false, oldPosInc = 0)
                } else {
                    addToPath(removePath!!, added = false, removed = true, oldPosInc = 1)
                }
                newPos = extractCommon(base, diagonal)
                if (base.oldPos + 1 >= oldLen && newPos + 1 >= newLen) return marks(base.last, oldLen, newLen)
                bestPath[offset + diagonal] = base
                if (base.oldPos + 1 >= oldLen) maxDiagonal = minOf(maxDiagonal, diagonal - 1)
                if (newPos + 1 >= newLen) minDiagonal = maxOf(minDiagonal, diagonal + 1)
                diagonal += 2
            }
        }
        return null
    }

    /** `buildValues`, as marks: the change objects from the first, each run of items it covers. */
    private fun marks(last: Component?, oldLen: Int, newLen: Int): Pair<BooleanArray, BooleanArray> {
        val removed = BooleanArray(oldLen)
        val inserted = BooleanArray(newLen)
        var oldPos = 0
        var newPos = 0
        for (component in generateSequence(last) { it.previous }.toList().asReversed()) {
            when {
                component.removed -> repeat(component.count) { removed[oldPos++] = true }
                component.added -> repeat(component.count) { inserted[newPos++] = true }
                else -> {
                    oldPos += component.count
                    newPos += component.count
                }
            }
        }
        return removed to inserted
    }

    /** Everything between the shared head and tail removed, and replaced. */
    private fun <T> replaced(a: List<T>, b: List<T>): Pair<BooleanArray, BooleanArray> {
        var start = 0
        while (start < a.size && start < b.size && a[start] == b[start]) start++
        var endA = a.size
        var endB = b.size
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) {
            endA--
            endB--
        }
        return BooleanArray(a.size) { it in start until endA } to BooleanArray(b.size) { it in start until endB }
    }
}
