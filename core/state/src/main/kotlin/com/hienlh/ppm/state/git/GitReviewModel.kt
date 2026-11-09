package com.hienlh.ppm.state.git

import com.hienlh.ppm.client.ChangeBlock
import com.hienlh.ppm.client.ChangeHunk
import com.hienlh.ppm.client.ChangeLine
import com.hienlh.ppm.client.ChangedFile
import com.hienlh.ppm.client.FileChangeDetail
import kotlin.math.abs

/** A run of a hunk's lines, with the numbers its first line has on each side (`BlockPart`). */
data class BlockPart(
    val oldStart: Int,
    val newStart: Int,
    val lines: List<ChangeLine>,
)

/** One review block in `GitReviewModel` (`ReviewBlock`). */
data class GitReviewBlock(
    /** `u:`/`s:` and the hunk's fingerprint (`#2` for a second identical hunk), or `d:` and the discard's record. */
    val key: String,
    /** `"open"` | `"staged"` | `"discarded"`. */
    val state: String,
    /** `"staged"` | `"unstaged"`. */
    val side: String,
    val hunk: ChangeBlock?,
    val whole: String?,
    val parts: List<BlockPart>?,
    val anchor: Double,
    val first: Int,
    val last: Int,
    val added: Int,
    val removed: Int,
    val recordId: String? = null,
)

/** A discard made from the Review tab: git no longer lists it, so the tab keeps it with Undo (`DiscardedEntry`). */
data class DiscardedEntry(
    val path: String,
    val recordId: String,
    val hunks: List<ChangeHunk>,
    val whole: String?,
    val added: Int,
    val removed: Int,
)

/** One file's review summary (`FileReview`). */
data class GitFileReview(
    val path: String,
    val file: ChangedFile?,
    val blocks: List<GitReviewBlock>,
    val open: Int,
    val staged: Int,
    val discarded: Int,
    val added: Int,
    val removed: Int,
)

/** Totals across all files in the Review tab (`ReviewTotals`). */
data class GitReviewTotals(
    val files: Int,
    val blocks: Int,
    val staged: Int,
    val discarded: Int,
    val added: Int,
    val removed: Int,
)

/** Focused block coordinate (`ReviewFocus`). */
data class GitReviewFocus(
    val path: String,
    val key: String,
    val anchor: Double? = null,
)

/** Item in the code pane: either a folded unchanged gap or a review block (`PaneItem`). */
sealed interface GitPaneItem {
    val itemKey: String

    data class Gap(override val itemKey: String, val lines: Int) : GitPaneItem

    data class Block(
        val block: GitReviewBlock,
        val index: Int,
        val total: Int,
    ) : GitPaneItem {
        override val itemKey: String get() = block.key
    }
}

/** One numbered line in a `BlockPart` (`ReviewRow`). */
data class GitReviewRow(
    val kind: String,
    val text: String,
    val old: Int?,
    val new: Int?,
    val at: Int,
)

/**
 * Pure functions for the Review changes tab (`src/web/lib/git-review-model.ts`).
 */
object GitReviewModel {
    private val RANK = mapOf("staged" to 0, "open" to 1, "discarded" to 2)
    private val GROUP_ORDER = listOf("changes", "staged", "discarded")
    private val HUNK_KEY_REGEX = Regex("^[us]:([^#]+)(?:#(\\d+))?$")

    fun blockId(path: String, key: String): String = "$path\u0000$key"

    /**
     * Where a block sits in the index version of the file (`blockAnchor` in `src/shared/git-changes.ts`).
     */
    fun blockAnchor(side: String, block: ChangeBlock): Double =
        if (side == "staged") {
            if (block.newLines == 0) block.newStart + 0.5 else block.newStart.toDouble()
        } else {
            if (block.oldLines == 0) block.oldStart + 0.5 else block.oldStart.toDouble()
        }

    private data class SideBlock(
        val block: ChangeBlock,
        val lines: List<ChangeLine>?,
    )

    private data class SideLike(
        val whole: String?,
        val added: Int,
        val removed: Int,
        val blocks: List<SideBlock>,
    )

    private data class IndexSpan(val anchor: Double, val first: Int, val last: Int)

    private fun indexSpan(side: String, b: ChangeBlock): IndexSpan {
        val start = if (side == "staged") b.newStart else b.oldStart
        val count = if (side == "staged") b.newLines else b.oldLines
        return IndexSpan(
            anchor = blockAnchor(side, b),
            first = if (count != 0) start else start + 1,
            last = if (count != 0) start + count - 1 else start,
        )
    }

    private fun sideBlocks(
        name: String,
        side: SideLike?,
        deleted: Boolean,
        withLines: Boolean,
    ): List<GitReviewBlock> {
        if (side == null) return emptyList()
        val prefix = if (name == "staged") "s" else "u"
        val state = if (name == "staged") "staged" else "open"
        if (side.whole != null || deleted || side.blocks.isEmpty()) {
            val whole = if (deleted) "deleted" else side.whole ?: "empty"
            val shown = withLines && whole == "rename" && side.blocks.all { it.lines != null }
            return listOf(
                GitReviewBlock(
                    key = "$prefix:whole",
                    state = state,
                    side = name,
                    hunk = null,
                    whole = whole,
                    parts = if (shown && side.blocks.isNotEmpty()) {
                        side.blocks.map { BlockPart(it.block.oldStart, it.block.newStart, it.lines!!) }
                    } else {
                        null
                    },
                    anchor = 0.0,
                    first = 1,
                    last = 0,
                    added = side.added,
                    removed = side.removed,
                ),
            )
        }
        val seen = HashMap<String, Int>()
        return side.blocks.map { sb ->
            val b = sb.block
            val n = (seen[b.id] ?: 0) + 1
            seen[b.id] = n
            val span = indexSpan(name, b)
            GitReviewBlock(
                key = "$prefix:${b.id}${if (n > 1) "#$n" else ""}",
                state = state,
                side = name,
                hunk = b.copy(),
                whole = null,
                parts = if (withLines && sb.lines != null) listOf(BlockPart(b.oldStart, b.newStart, sb.lines)) else null,
                anchor = span.anchor,
                first = span.first,
                last = span.last,
                added = b.added,
                removed = b.removed,
            )
        }
    }

    private fun discardBlocks(entry: DiscardedEntry): List<GitReviewBlock> {
        if (entry.whole != null || entry.hunks.isEmpty()) {
            return listOf(
                GitReviewBlock(
                    key = "d:${entry.recordId}",
                    state = "discarded",
                    side = "unstaged",
                    hunk = null,
                    whole = entry.whole ?: "empty",
                    parts = null,
                    anchor = 0.0,
                    first = 1,
                    last = 0,
                    added = entry.added,
                    removed = entry.removed,
                    recordId = entry.recordId,
                ),
            )
        }
        return entry.hunks.mapIndexed { i, h ->
            val cb = h.toChangeBlock()
            val span = indexSpan("unstaged", cb)
            GitReviewBlock(
                key = "d:${entry.recordId}:$i",
                state = "discarded",
                side = "unstaged",
                hunk = cb,
                whole = null,
                parts = listOf(BlockPart(h.oldStart, h.newStart, h.lines)),
                anchor = span.anchor,
                first = span.first,
                last = span.last,
                added = h.added,
                removed = h.removed,
                recordId = entry.recordId,
            )
        }
    }

    private fun covered(block: GitReviewBlock, discards: List<DiscardedEntry>): Boolean {
        val hunk = block.hunk ?: return discards.any { it.whole != null || it.hunks.isEmpty() }
        return discards.any { d -> d.hunks.any { h -> h.id == hunk.id && h.oldStart == hunk.oldStart } }
    }

    fun fileBlocks(
        path: String,
        file: ChangedFile?,
        detail: FileChangeDetail?,
        discards: List<DiscardedEntry> = emptyList(),
        pending: Map<String, String> = emptyMap(),
    ): List<GitReviewBlock> {
        val out = mutableListOf<GitReviewBlock>()
        if (file != null) {
            val x = detail?.x ?: file.x
            val y = detail?.y ?: file.y
            val withLines = detail != null
            val staged = if (detail != null) {
                detail.staged?.let { s ->
                    SideLike(
                        whole = s.whole,
                        added = s.added,
                        removed = s.removed,
                        blocks = s.hunks.map { SideBlock(it.toChangeBlock(), it.lines) },
                    )
                }
            } else {
                file.staged?.let { s ->
                    SideLike(
                        whole = s.whole,
                        added = s.added,
                        removed = s.removed,
                        blocks = s.changeBlocks.map { SideBlock(it, null) },
                    )
                }
            }
            val unstaged = if (detail != null) {
                detail.unstaged?.let { u ->
                    SideLike(
                        whole = u.whole,
                        added = u.added,
                        removed = u.removed,
                        blocks = u.hunks.map { SideBlock(it.toChangeBlock(), it.lines) },
                    )
                }
            } else {
                file.unstaged?.let { u ->
                    SideLike(
                        whole = u.whole,
                        added = u.added,
                        removed = u.removed,
                        blocks = u.changeBlocks.map { SideBlock(it, null) },
                    )
                }
            }
            val live = sideBlocks("staged", staged, x == "D", withLines) +
                sideBlocks("unstaged", unstaged, y == "D", withLines)
            for (block in live) {
                val p = pending[blockId(path, block.key)]
                if (p == "discarded" && covered(block, discards)) continue
                out += if (p != null) block.copy(state = p) else block
            }
        }
        for (entry in discards) out += discardBlocks(entry)
        return out
            .mapIndexed { i, block -> i to block }
            .sortedWith { (iA, bA), (iB, bB) ->
                val cmpAnchor = bA.anchor.compareTo(bB.anchor)
                if (cmpAnchor != 0) cmpAnchor
                else {
                    val cmpRank = (RANK[bA.state] ?: 1).compareTo(RANK[bB.state] ?: 1)
                    if (cmpRank != 0) cmpRank else iA.compareTo(iB)
                }
            }
            .map { it.second }
    }

    private fun summarize(path: String, file: ChangedFile?, blocks: List<GitReviewBlock>): GitFileReview {
        var open = 0
        var staged = 0
        var discarded = 0
        var added = 0
        var removed = 0
        for (b in blocks) {
            when (b.state) {
                "open" -> open++
                "staged" -> staged++
                else -> discarded++
            }
            if (b.state != "discarded") {
                added += b.added
                removed += b.removed
            }
        }
        return GitFileReview(
            path = path,
            file = file,
            blocks = blocks,
            open = open,
            staged = staged,
            discarded = discarded,
            added = added,
            removed = removed,
        )
    }

    fun fileReviews(
        files: List<ChangedFile>,
        details: Map<String, FileChangeDetail> = emptyMap(),
        discards: List<DiscardedEntry> = emptyList(),
        pending: Map<String, String> = emptyMap(),
    ): List<GitFileReview> {
        val byPath = discards.groupBy { it.path }
        val out = mutableListOf<GitFileReview>()
        val listed = HashSet<String>()
        for (file in files) {
            if (file.conflict) continue
            listed += file.path
            val blocks = fileBlocks(
                path = file.path,
                file = file,
                detail = details[file.path],
                discards = byPath[file.path].orEmpty(),
                pending = pending,
            )
            if (blocks.isNotEmpty()) out += summarize(file.path, file, blocks)
        }
        val gone = byPath.keys.filter { it !in listed }.sorted()
        for (path in gone) {
            out += summarize(
                path = path,
                file = null,
                blocks = fileBlocks(
                    path = path,
                    file = null,
                    detail = null,
                    discards = byPath[path].orEmpty(),
                    pending = pending,
                ),
            )
        }
        return out
    }

    fun railGroup(review: GitFileReview): String = when {
        review.open > 0 -> "changes"
        review.staged > 0 -> "staged"
        else -> "discarded"
    }

    fun railOrder(reviews: List<GitFileReview>): List<GitFileReview> =
        GROUP_ORDER.flatMap { g -> reviews.filter { railGroup(it) == g } }

    fun reviewTotals(reviews: List<GitFileReview>): GitReviewTotals {
        var files = 0
        var blocks = 0
        var staged = 0
        var discarded = 0
        var added = 0
        var removed = 0
        for (r in reviews) {
            if (r.open + r.staged > 0) files++
            blocks += r.open + r.staged
            staged += r.staged
            discarded += r.discarded
            added += r.added
            removed += r.removed
        }
        return GitReviewTotals(
            files = files,
            blocks = blocks,
            staged = staged,
            discarded = discarded,
            added = added,
            removed = removed,
        )
    }

    private fun sequence(reviews: List<GitFileReview>): List<Pair<String, GitReviewBlock>> =
        railOrder(reviews).flatMap { r -> r.blocks.map { r.path to it } }

    private fun matches(focus: GitReviewFocus?, entry: Pair<String, GitReviewBlock>): Boolean =
        focus != null && entry.first == focus.path && entry.second.key == focus.key

    /** J and K: the block after or before, across files, stopping at either end. */
    fun stepFocus(reviews: List<GitFileReview>, from: GitReviewFocus?, dir: Int): GitReviewFocus? {
        val seq = sequence(reviews)
        if (seq.isEmpty()) return null
        val i = seq.indexOfFirst { matches(from, it) }
        val e = if (i < 0) seq[0] else seq[(i + dir).coerceIn(0, seq.lastIndex)]
        return GitReviewFocus(e.first, e.second.key)
    }

    /** Where an answer moves on to: the next open block, wrapping round, never [from] itself. */
    fun nextOpen(reviews: List<GitFileReview>, from: GitReviewFocus?): GitReviewFocus? {
        val seq = reviews.flatMap { r -> r.blocks.map { r.path to it } }
        if (seq.isEmpty()) return null
        val i = seq.indexOfFirst { matches(from, it) }
        for (k in 1..seq.size) {
            val e = seq[(i + k + seq.size) % seq.size]
            if (e.second.state == "open" && !matches(from, e)) {
                return GitReviewFocus(e.first, e.second.key)
            }
        }
        return null
    }

    fun firstInFile(review: GitFileReview): GitReviewFocus? {
        val block = review.blocks.firstOrNull { it.state == "open" } ?: review.blocks.firstOrNull()
        return block?.let { GitReviewFocus(review.path, it.key) }
    }

    fun nextFileFocus(reviews: List<GitFileReview>, path: String?): GitReviewFocus? {
        val order = railOrder(reviews)
        if (order.isEmpty()) return null
        val i = order.indexOfFirst { it.path == path }
        for (k in 1..order.size) {
            val r = order[(i + k + order.size) % order.size]
            val open = r.blocks.firstOrNull { it.state == "open" }
            if (open != null && r.path != path) return GitReviewFocus(r.path, open.key)
        }
        val next = order[(i + 1 + order.size) % order.size]
        return if (next.path != path) firstInFile(next) else null
    }

    fun resolveFocus(reviews: List<GitFileReview>, want: GitReviewFocus?): GitReviewFocus? {
        if (want != null) {
            val file = reviews.firstOrNull { it.path == want.path }
            if (file != null && file.blocks.any { it.key == want.key }) {
                return GitReviewFocus(want.path, want.key)
            }
            if (file != null && file.blocks.isNotEmpty()) {
                val anchor = want.anchor ?: 0.0
                val near = file.blocks.sortedWith { a, b ->
                    val openCmp = (if (a.state == "open") 0 else 1).compareTo(if (b.state == "open") 0 else 1)
                    if (openCmp != 0) openCmp else abs(a.anchor - anchor).compareTo(abs(b.anchor - anchor))
                }.first()
                return GitReviewFocus(want.path, near.key)
            }
        }
        val seq = sequence(reviews)
        val e = seq.firstOrNull { it.second.state == "open" } ?: seq.firstOrNull()
        return e?.let { GitReviewFocus(it.first, it.second.key) }
    }

    fun paneItems(blocks: List<GitReviewBlock>): List<GitPaneItem> {
        val items = mutableListOf<GitPaneItem>()
        var end = 0
        blocks.forEachIndexed { index, block ->
            if (block.whole == null) {
                val gap = block.first - end - 1
                if (gap > 0) items += GitPaneItem.Gap("gap:${block.key}", gap)
                end = maxOf(end, block.last)
            }
            items += GitPaneItem.Block(block, index, blocks.size)
        }
        return items
    }

    fun numberedRows(part: BlockPart): List<GitReviewRow> {
        var o = part.oldStart
        var n = part.newStart
        return part.lines.mapIndexed { at, line ->
            GitReviewRow(
                kind = line.kind,
                text = line.text,
                old = if (line.kind == "+") null else o++,
                new = if (line.kind == "-") null else n++,
                at = at,
            )
        }
    }

    fun rangeText(block: GitReviewBlock): String {
        val part = block.parts?.firstOrNull()
        if (block.whole != null || part == null) return "Whole file"
        fun span(start: Int, n: Int): String = if (n > 1) "Lines $start–${start + n - 1}" else "Line $start"
        val oldLines = part.lines.count { it.kind != "+" }
        val newLines = part.lines.count { it.kind != "-" }
        if (block.state == "discarded") return if (oldLines > 0) span(part.oldStart, oldLines) else "Whole file"
        return if (newLines > 0) span(part.newStart, newLines) else "${span(part.oldStart, oldLines)}, removed"
    }

    fun pickableLines(block: GitReviewBlock): List<Int> {
        val lines = block.parts?.firstOrNull()?.lines.orEmpty()
        return lines.mapIndexedNotNull { i, l -> if (l.kind == " ") null else i }
    }

    fun pickRequest(block: GitReviewBlock, picked: Set<Int>): List<Int>? {
        val allPickable = pickableLines(block)
        val lines = allPickable.filter { it in picked }
        return if (lines.size == allPickable.size) null else lines
    }

    fun hunkIndex(hunks: List<ChangeBlock>, key: String): Int? {
        val m = HUNK_KEY_REGEX.matchEntire(key) ?: return null
        val id = m.groupValues[1]
        var left = m.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 1
        for (h in hunks) {
            if (h.id == id && --left == 0) return h.index
        }
        return null
    }

    fun hunkIndexFromDetail(hunks: List<ChangeHunk>, key: String): Int? =
        hunkIndex(hunks.map { it.toChangeBlock() }, key)

    fun fileSignature(file: ChangedFile): String {
        fun side(s: com.hienlh.ppm.client.ChangeSide?): String =
            if (s != null) "${s.whole.orEmpty()}:${s.blocks.joinToString(",")}" else "-"
        return "${file.x}${file.y}|${file.oldPath.orEmpty()}|${side(file.staged)}|${side(file.unstaged)}"
    }
}
