package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.SessionFileChange

/*
 * The pure half of the block-by-block Review tab (`session-review-model.ts`): which blocks each
 * file has and how each was answered, where a block reverted in this sitting still sits in the
 * file, and where focus goes after an answer.
 *
 * The server's list names every block of every file by key and says which are kept. A block
 * reverted here is no longer a change at all — its lines are back on disk — so the list forgets
 * it. The tab keeps it ([RevertedBlock]) while it is open and shows it where it sits, as the
 * lines it put back: what stays is what a decided block shows, kept or reverted.
 */

enum class BlockState { OPEN, KEPT, REVERTED }

data class ReviewFocus(val path: String, val key: String)

/** A block reverted in this sitting. */
data class RevertedBlock(
    val key: String,
    /** Its rows as drawn: what the revert took away, and what it put back. */
    val rows: List<ReviewBlocks.Row>,
    /** The base lines it covers, which the revert put back: 0-based, end exclusive. */
    val oldFrom: Int,
    val oldTo: Int,
    val added: Int,
    val removed: Int,
    /** What the block was cut against (`SessionFileChange.base`). */
    val base: String,
    /** The file's version when the block was drawn: a diff still at it was read before the revert. */
    val drawnVersion: String,
    /** Names the revert for Undo; null until the server has answered. */
    val undoId: String? = null,
    /** The calls that wrote it, as the list named them while it was there. */
    val calls: List<String>? = null,
)

/** A file no longer listed because every change in it was reverted here. */
data class RevertedFile(
    /** As it was last drawn. */
    val file: SessionFileChange,
    /** The text it is back to, which is also what it was compared with; null for one answered whole. */
    val text: String?,
    /** Names the revert for Undo. */
    val undoId: String? = null,
)

data class ReviewBlockState(val key: String, val state: BlockState)

data class FileReview(
    val path: String,
    val file: SessionFileChange,
    /** In file order. A file answered whole has one block, keyed [SessionReviewModel.WHOLE_FILE]. */
    val blocks: List<ReviewBlockState>,
    val open: Int,
    /** Every change in it was reverted here: the server no longer lists it. */
    val gone: Boolean,
)

data class ReviewProgress(val total: Int, val kept: Int, val reverted: Int, val open: Int)

/** How a finished file ended, as the rail says it. */
data class FileOutcome(val tone: Tone, val label: String) {
    enum class Tone { KEPT, REVERTED, MIXED }
}

sealed interface PaneItem {
    data class Block(
        val key: String,
        val rows: List<ReviewBlocks.Row>,
        val state: BlockState,
        val added: Int,
        val removed: Int,
        /** The base lines it covers: 0-based, end exclusive. */
        val oldFrom: Int,
        val oldTo: Int,
        /** 0-based place among the file's blocks. */
        val index: Int,
        val undoId: String? = null,
        /** The calls that wrote it (`SessionBlockSummary.calls`), for the turns it names. */
        val calls: List<String>? = null,
    ) : PaneItem

    /** Unchanged lines of the file as it is now: 0-based, end exclusive. */
    data class Gap(val from: Int, val to: Int) : PaneItem
}

class PaneModel(
    val items: List<PaneItem>,
    /** The file as it is now, line by line, for the unchanged lines a gap opens up. */
    val lines: List<String>,
    /** Each line's number in the base, 1-based, or 0 for one that is not there. */
    val baseLine: IntArray,
    /** How many blocks the file has, reverted ones included. */
    val total: Int,
    /** Reverted blocks that no longer belong here: the agent wrote over them, or the base moved. */
    val stale: List<String>,
)

object SessionReviewModel {
    /** The key of the one block a file answered whole has: binary, too large, or too slow to diff. */
    const val WHOLE_FILE = "*"

    fun answerKey(path: String, key: String) = "$path\u0000$key"

    private class Placed(val key: String, var state: BlockState, val at: Int)

    /**
     * Every file of the review in [order] — the order paths were first seen in, so a file whose
     * changes were all reverted keeps its place — with each block's state. [pending] holds the
     * answers on their way, by [answerKey]: shown at once, replaced by the answer.
     */
    fun fileReviews(
        order: List<String>,
        files: List<SessionFileChange>,
        reverted: Map<String, List<RevertedBlock>>,
        gone: Map<String, RevertedFile>,
        pending: Map<String, BlockState> = emptyMap(),
    ): List<FileReview> {
        val listed = files.associateBy { it.path }
        val out = mutableListOf<FileReview>()
        for (path in order) {
            val file = listed[path]
            val goneFile = if (file == null) gone[path] else null
            if (file == null && goneFile == null) continue
            val ghosts = reverted[path].orEmpty()
            val blocks: MutableList<Placed>
            if (file != null) {
                blocks = file.blocks?.mapTo(mutableListOf()) { Placed(it.key, if (it.kept) BlockState.KEPT else BlockState.OPEN, ReviewBlocks.blockStart(it.key)) }
                    ?: mutableListOf(Placed(WHOLE_FILE, if (file.reviewed) BlockState.KEPT else BlockState.OPEN, 0))
                val keys = blocks.associateBy { it.key }
                for (g in ghosts) {
                    if (g.base != file.base) continue
                    val listedBlock = keys[g.key]
                    // Still listed: either the list was read before the revert, or the agent wrote the lines again.
                    if (listedBlock != null) {
                        if (file.version == g.drawnVersion) listedBlock.state = BlockState.REVERTED
                    } else {
                        blocks += Placed(g.key, BlockState.REVERTED, g.oldFrom)
                    }
                }
            } else {
                blocks = if (ghosts.isNotEmpty()) ghosts.mapTo(mutableListOf()) { Placed(it.key, BlockState.REVERTED, it.oldFrom) }
                else mutableListOf(Placed(WHOLE_FILE, BlockState.REVERTED, 0))
            }
            blocks.sortBy { it.at }
            val states = blocks.map { ReviewBlockState(it.key, pending[answerKey(path, it.key)] ?: it.state) }
            out += FileReview(path, file ?: goneFile!!.file, states, states.count { it.state == BlockState.OPEN }, gone = file == null)
        }
        return out
    }

    fun reviewProgress(reviews: List<FileReview>): ReviewProgress {
        var kept = 0
        var reverted = 0
        var open = 0
        for (r in reviews) {
            for (b in r.blocks) {
                when (b.state) {
                    BlockState.KEPT -> kept++
                    BlockState.REVERTED -> reverted++
                    BlockState.OPEN -> open++
                }
            }
        }
        return ReviewProgress(kept + reverted + open, kept, reverted, open)
    }

    /** Files with a block left to answer first, then the ones that are done: the rail's order. */
    fun railOrder(reviews: List<FileReview>): List<FileReview> = reviews.filter { it.open > 0 } + reviews.filter { it.open == 0 }

    fun fileOutcome(r: FileReview): FileOutcome {
        val kept = r.blocks.count { it.state == BlockState.KEPT }
        val reverted = r.blocks.size - kept
        if (reverted == 0) return FileOutcome(FileOutcome.Tone.KEPT, "Kept")
        if (kept == 0) return FileOutcome(FileOutcome.Tone.REVERTED, "Reverted")
        return FileOutcome(FileOutcome.Tone.MIXED, "$kept kept · $reverted reverted")
    }

    /**
     * The next open block after [from]: the rest of its file first, then the files after it,
     * wrapping round, and last the blocks before it in its own file. Null when none is open.
     */
    fun nextOpen(reviews: List<FileReview>, from: ReviewFocus?): ReviewFocus? {
        if (reviews.isEmpty()) return null
        val start = if (from != null) maxOf(0, reviews.indexOfFirst { it.path == from.path }) else 0
        val own = reviews[start]
        val at = if (from != null && own.path == from.path) own.blocks.indexOfFirst { it.key == from.key } else -1
        for (n in reviews.indices) {
            val r = reviews[(start + n) % reviews.size]
            val first = if (n == 0) at + 1 else 0
            for (i in first until r.blocks.size) if (r.blocks[i].state == BlockState.OPEN) return ReviewFocus(r.path, r.blocks[i].key)
        }
        for (i in 0..at) if (own.blocks[i].state == BlockState.OPEN) return ReviewFocus(own.path, own.blocks[i].key)
        return null
    }

    /** The block [step] places from [from] among every block, in the rail's order, wrapping. */
    fun stepBlock(reviews: List<FileReview>, from: ReviewFocus?, step: Int): ReviewFocus? {
        val all = railOrder(reviews).flatMap { r -> r.blocks.map { ReviewFocus(r.path, it.key) } }
        if (all.isEmpty()) return null
        val i = if (from != null) all.indexOf(from) else -1
        if (i < 0) return all[if (step == 1) 0 else all.size - 1]
        return all[(i + step + all.size) % all.size]
    }

    /** Where focus lands in a file: its first open block, else its first block. */
    fun firstInFile(review: FileReview): ReviewFocus? {
        val block = review.blocks.firstOrNull { it.state == BlockState.OPEN } ?: review.blocks.firstOrNull()
        return block?.let { ReviewFocus(review.path, it.key) }
    }

    private class Entry(
        val key: String,
        val rows: List<ReviewBlocks.Row>,
        val state: BlockState,
        val added: Int,
        val removed: Int,
        val oldFrom: Int,
        val oldTo: Int,
        val start: Int,
        val end: Int,
        val undoId: String?,
        val calls: List<String>?,
    )

    /**
     * The file in focus as the pane draws it: its blocks — each open, kept or reverted — with the
     * unchanged lines between them. Null when the diff is too slow to cut into blocks.
     *
     * [kept] holds the keys of the blocks kept, as the diff's own summary says; [pending] the
     * answers on their way, by block key; [calls] the calls that wrote each block, as the list says.
     */
    fun paneModel(
        original: String,
        modified: String,
        version: String,
        base: String?,
        kept: Set<String>,
        reverted: List<RevertedBlock>,
        pending: Map<String, BlockState> = emptyMap(),
        calls: Map<String, List<String>> = emptyMap(),
    ): PaneModel? {
        val cut = cutFile(original, modified) ?: return null
        val entries = mutableListOf<Entry>()
        val stale = mutableListOf<String>()
        val ghosts = reverted.associateBy { it.key }
        val keys = HashSet<String>()
        for (b in cut.diff.blocks) {
            keys += b.key
            val ghost = ghosts[b.key]
            var state = if (b.key in kept) BlockState.KEPT else BlockState.OPEN
            if (ghost != null) {
                // Read before the revert landed; otherwise the agent wrote these lines again since.
                if (ghost.drawnVersion == version) state = BlockState.REVERTED else stale += ghost.key
            }
            entries += Entry(
                b.key, b.rows, state, b.added, b.removed, b.oldFrom, b.oldTo, b.newFrom, b.newTo,
                undoId = if (ghost?.drawnVersion == version) ghost.undoId else null,
                calls = calls[b.key] ?: ghost?.calls,
            )
        }
        for (g in reverted) {
            if (g.key in keys) continue
            val start = if (g.base == base) placeReverted(g, cut.map, original) else -1
            val end = start + (g.oldTo - g.oldFrom)
            if (start < 0 || entries.any { it.start < end && start < it.end }) {
                stale += g.key
                continue
            }
            entries += Entry(g.key, g.rows, BlockState.REVERTED, g.added, g.removed, g.oldFrom, g.oldTo, start, end, g.undoId, g.calls)
        }
        entries.sortWith(compareBy<Entry> { it.start }.thenBy { it.end })

        val items = mutableListOf<PaneItem>()
        var cursor = 0
        entries.forEachIndexed { index, e ->
            if (e.start > cursor) items += PaneItem.Gap(cursor, e.start)
            items += PaneItem.Block(e.key, e.rows, pending[e.key] ?: e.state, e.added, e.removed, e.oldFrom, e.oldTo, index, e.undoId, e.calls)
            cursor = maxOf(cursor, e.end)
        }
        if (cursor < cut.diff.newLines) items += PaneItem.Gap(cursor, cut.diff.newLines)
        return PaneModel(items, cut.lines, cut.baseLine, entries.size, stale)
    }

    private class Cut(val diff: ReviewBlocks.Diff, val map: IntArray, val lines: List<String>, val baseLine: IntArray)

    // The pane is redrawn on every answer while its file stays the same, and a diff that ran out
    // of time would otherwise run out of time again each time: the last few files are kept,
    // failures too.
    private class CutEntry(val original: String, val modified: String, val cut: Cut?)

    private val cuts = ArrayDeque<CutEntry>()
    private const val CUTS_KEPT = 4

    private fun cutFile(original: String, modified: String): Cut? {
        synchronized(cuts) {
            cuts.firstOrNull { it.original == original && it.modified == modified }?.let { return it.cut }
        }
        val diff = ReviewBlocks.computeBlocks(original, modified)
        val map = diff?.let { ReviewBlocks.lineMap(original, modified) }
        val cut = if (diff != null && map != null) {
            val baseLine = IntArray(diff.newLines)
            map.forEachIndexed { o, n -> if (n >= 0) baseLine[n] = o + 1 }
            val lines = ReviewBlocks.splitLines(modified).map { it.removeSuffix("\n").removeSuffix("\r") }
            Cut(diff, map, lines, baseLine)
        } else {
            null
        }
        synchronized(cuts) {
            cuts.addFirst(CutEntry(original, modified, cut))
            while (cuts.size > CUTS_KEPT) cuts.removeLast()
        }
        return cut
    }

    /** Where a reverted block's base lines sit in the file now, or -1 when they are not all there, in order. */
    private fun placeReverted(g: RevertedBlock, map: IntArray, original: String): Int {
        if (g.oldFrom == g.oldTo) return if (original.isEmpty()) 0 else -1
        val start = map.getOrElse(g.oldFrom) { -1 }
        if (start < 0) return -1
        for (i in g.oldFrom until g.oldTo) if (map.getOrElse(i) { -1 } != start + i - g.oldFrom) return -1
        return start
    }

    /** The rows a block shows: the diff while it is open, the lines that stay once it is answered. */
    fun shownRows(rows: List<ReviewBlocks.Row>, state: BlockState): List<ReviewBlocks.Row> = when (state) {
        BlockState.KEPT -> rows.filter { it.kind != ReviewBlocks.Kind.REMOVED }
        BlockState.REVERTED -> rows.filter { it.kind != ReviewBlocks.Kind.ADDED }
        BlockState.OPEN -> rows
    }

    /** `/[\w$]/` without the `u` flag: ASCII letters, digits, `_` and `$`. */
    private fun isWord(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '$'

    /**
     * The part of each changed line that changed, where a removed line and an added one pair up
     * (the n-th removed line of a run with the n-th added line after it): between their common
     * start and end, widened to whole words. Null for a line with no partner.
     */
    fun changedSpans(rows: List<ReviewBlocks.Row>): List<IntRange?> {
        val out = arrayOfNulls<IntRange>(rows.size)
        var i = 0
        while (i < rows.size) {
            if (rows[i].kind != ReviewBlocks.Kind.REMOVED) {
                i++
                continue
            }
            val del = i
            while (i < rows.size && rows[i].kind == ReviewBlocks.Kind.REMOVED) i++
            val add = i
            while (i < rows.size && rows[i].kind == ReviewBlocks.Kind.ADDED) i++
            for (j in 0 until minOf(add - del, i - add)) {
                val a = rows[del + j].text
                val b = rows[add + j].text
                var pre = 0
                while (pre < a.length && pre < b.length && a[pre] == b[pre]) pre++
                while (pre > 0 && isWord(a[pre - 1])) pre--
                var suf = 0
                while (suf < a.length - pre && suf < b.length - pre && a[a.length - 1 - suf] == b[b.length - 1 - suf]) suf++
                while (suf > 0 && isWord(a[a.length - suf])) suf--
                if (a.length - suf > pre) out[del + j] = pre until a.length - suf
                if (b.length - suf > pre) out[add + j] = pre until b.length - suf
            }
        }
        return out.toList()
    }
}
