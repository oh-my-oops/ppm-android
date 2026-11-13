package com.hienlh.ppm.state.git

import com.hienlh.ppm.client.ChangeLine
import com.hienlh.ppm.state.editor.SideBySideDiffEntry
import com.hienlh.ppm.state.editor.SideBySideDiffRow

/**
 * The Review changes tab side by side, as pure functions (`src/web/lib/git-review-side-by-side.ts`):
 * where each block's bar goes in the whole-file diff (HEAD on the left, the working tree on the
 * right), and which block a line of the right-hand file belongs to.
 *
 * The diff numbers the right side by the working tree, but the blocks come from two diffs with
 * their own numbering: an open block's new side *is* the working tree, while a staged block
 * (HEAD → index) and a discarded one (whose old side is the index) are numbered by the index.
 * Those are moved into working-tree lines through the open blocks, which are exactly the
 * differences between the index and the working tree.
 */
object GitReviewSideBySide {
    /** Whole-file blocks the side-by-side view can place. Any other (binary, rename, …) falls back to the block list. */
    private val PLACEABLE_WHOLE = setOf("deleted")

    /** Whether a file's blocks can be shown side by side, or need the block list. */
    fun supported(blocks: List<GitReviewBlock>): Boolean =
        blocks.isNotEmpty() && blocks.all { it.whole == null || it.whole in PLACEABLE_WHOLE }

    /** An open block's hunk (index → working tree); [lines], when known, map lines inside it exactly. */
    data class Hunk(
        val oldStart: Int,
        val oldLines: Int,
        val newStart: Int,
        val newLines: Int,
        val lines: List<ChangeLine>? = null,
    )

    /** The open blocks' hunks in index order: what moves an index line. */
    private fun worktreeHunks(blocks: List<GitReviewBlock>): List<Hunk> =
        blocks
            .filter { it.side == "unstaged" && it.state != "discarded" && it.hunk != null }
            .map { b -> b.hunk!!.let { Hunk(it.oldStart, it.oldLines, it.newStart, it.newLines, b.parts?.firstOrNull()?.lines) } }
            .sortedBy { it.oldStart }

    /**
     * The working-tree line that index line [line] became. A line the working tree removed maps to
     * where it was: the line that now follows that place.
     */
    fun indexToWorktree(line: Int, hunks: List<Hunk>): Int {
        var delta = 0
        for (h in hunks) {
            // A hunk with no index lines inserts after `oldStart`.
            if (h.oldLines == 0) {
                if (h.oldStart < line) {
                    delta += h.newLines
                    continue
                }
                break
            }
            val oldEnd = h.oldStart + h.oldLines - 1
            if (oldEnd < line) {
                delta += h.newLines - h.oldLines
                continue
            }
            if (line < h.oldStart) break
            // Inside the hunk: walk its lines when they are known, else take its start.
            val lines = h.lines ?: return h.newStart
            var o = h.oldStart
            var n = h.newStart
            for (l in lines) {
                if (l.kind == "+") {
                    n++
                    continue
                }
                if (o == line) return n
                o++
                if (l.kind == " ") n++
            }
            return n
        }
        return line + delta
    }

    /**
     * Where a block's change starts on the new side of its own diff: the number the first changed
     * line has there, or, for lines only removed, the number of the line that follows them.
     * Without lines, the hunk's start (context included).
     */
    private fun changeTop(block: GitReviewBlock): Int {
        val part = block.parts?.firstOrNull()
        val hunk = block.hunk
        if (part == null) return if (hunk != null) (if (hunk.newLines != 0) hunk.newStart else hunk.newStart + 1) else 1
        var n = part.newStart
        for (l in part.lines) {
            if (l.kind != " ") return n
            n++
        }
        return n
    }

    /** Where a block's change starts on the old side of its own diff (a discard: its index lines). */
    private fun changeTopOld(block: GitReviewBlock): Int {
        val part = block.parts?.firstOrNull()
        val hunk = block.hunk
        if (part == null) return if (hunk != null) (if (hunk.oldLines != 0) hunk.oldStart else hunk.oldStart + 1) else 1
        var o = part.oldStart
        for (l in part.lines) {
            if (l.kind != " ") return o
            o++
        }
        return o
    }

    /**
     * A block's place in the working tree. The bar goes above [line]; [first]..[last] are the lines
     * the block covers, context included, `last < first` when none.
     */
    data class Place(val key: String, val line: Int, val first: Int, val last: Int)

    /** Each block's place in the working tree, in the order the blocks are listed. */
    fun placeBlocks(blocks: List<GitReviewBlock>): List<Place> {
        val hunks = worktreeHunks(blocks)
        return blocks.map { block ->
            val h = block.hunk
            if (block.whole != null || h == null) return@map Place(block.key, 1, 1, 0)
            if (block.side == "unstaged" && block.state != "discarded") {
                return@map Place(block.key, changeTop(block), h.newStart, h.newStart + h.newLines - 1)
            }
            // Staged: numbered by the index on its new side. Discarded: by the index on its old side.
            val discarded = block.state == "discarded"
            val top = if (discarded) changeTopOld(block) else changeTop(block)
            val start = if (discarded) h.oldStart else h.newStart
            val count = if (discarded) h.oldLines else h.newLines
            val first = indexToWorktree(start, hunks)
            Place(
                key = block.key,
                line = indexToWorktree(top, hunks),
                first = first,
                last = if (count != 0) indexToWorktree(start + count - 1, hunks) else first - 1,
            )
        }
    }

    /** One row of the side-by-side list: a block's bar, a folded unchanged stretch, or a line pair. */
    sealed interface Item {
        val key: String

        data class Bar(val blockKey: String, val index: Int) : Item {
            override val key: String get() = "bar:$blockKey"
        }

        data class Gap(val gap: SideBySideDiffEntry.Gap) : Item {
            override val key: String get() = gap.key
        }

        data class Row(val row: SideBySideDiffRow) : Item {
            override val key: String get() = row.key
        }
    }

    /**
     * The whole-file diff's rows with each block's bar above its place, as the web puts a view zone
     * after line `place.line - 1` of the right-hand editor: before the first row at or past that
     * working-tree line. Lines only on the left (removed) count as the right-hand line that follows
     * them, so a bar goes above a change's removed lines too; a place inside a folded stretch puts
     * its bar on the fold's edge, where Monaco draws a zone whose line is hidden. Bars on one line
     * keep the blocks' order.
     */
    fun layout(entries: List<SideBySideDiffEntry>, places: List<Place>): List<Item> {
        val flat = mutableListOf<Item>()
        for (entry in entries) {
            when (entry) {
                is SideBySideDiffEntry.Gap -> flat += Item.Gap(entry)
                is SideBySideDiffEntry.Rows -> entry.rows.forEach { flat += Item.Row(it) }
            }
        }
        // The last working-tree line each item reaches; a left-only row takes the next right-hand line.
        val ends = IntArray(flat.size)
        var next = (
            flat.maxOfOrNull {
                when (it) {
                    is Item.Gap -> it.gap.fromNew + it.gap.count
                    is Item.Row -> it.row.rightLineNumber ?: 0
                    is Item.Bar -> 0
                }
            } ?: 0
            ) + 1
        for (i in flat.indices.reversed()) {
            when (val item = flat[i]) {
                is Item.Gap -> {
                    ends[i] = item.gap.fromNew + item.gap.count
                    next = item.gap.fromNew + 1
                }
                is Item.Row -> {
                    val right = item.row.rightLineNumber
                    if (right != null) next = right
                    ends[i] = next
                }
                is Item.Bar -> Unit
            }
        }
        val pending = places.withIndex().sortedWith(compareBy({ it.value.line }, { it.index })).toMutableList()
        val out = mutableListOf<Item>()
        flat.forEachIndexed { i, item ->
            while (pending.isNotEmpty() && pending.first().value.line <= ends[i]) {
                val p = pending.removeAt(0)
                out += Item.Bar(p.value.key, p.index)
            }
            out += item
        }
        pending.forEach { out += Item.Bar(it.value.key, it.index) }
        return out
    }

    /** The block a working-tree line belongs to: the innermost one covering it, an open one first. */
    fun blockAtLine(blocks: List<GitReviewBlock>, places: List<Place>, line: Int): String? {
        var best: Triple<String, Int, Boolean>? = null
        places.forEachIndexed { i, place ->
            if (line < place.first || line > place.last) return@forEachIndexed
            val open = blocks.getOrNull(i)?.state == "open"
            val size = place.last - place.first
            val b = best
            if (b == null || (open && !b.third) || (open == b.third && size < b.second)) best = Triple(place.key, size, open)
        }
        return best?.first
    }
}
