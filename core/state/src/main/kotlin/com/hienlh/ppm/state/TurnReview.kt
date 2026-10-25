package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.SessionAnswerFile
import com.hienlh.ppm.protocol.SessionFileChange

/*
 * Where one turn's edits stand in the session's review (`turn-review.ts`): an edit is its call's
 * blocks in the session's list (`SessionBlockSummary.calls`), open until each of them is kept,
 * and reverted once none is left. Pure, so the pill and the tray's rows read the same answer.
 */

enum class EditReviewState { OPEN, KEPT, REVERTED }

data class EditReview(
    /** Null where the list cannot say: a session from before blocks named their calls, a binary file. */
    val state: EditReviewState?,
    /** The file as the list has it, at the version an answer has to name. */
    val file: SessionFileChange? = null,
    /** The blocks the edit's call wrote, or that still hold some of it. */
    val keys: List<String> = emptyList(),
    /** Names a revert made from the tray, for Change to undo it. */
    val undoId: String? = null,
)

data class TurnReviewSummary(
    val open: Int = 0,
    val kept: Int = 0,
    val reverted: Int = 0,
    /** Edits the list says anything about; none means the tray shows no review at all. */
    val known: Int = 0,
)

/** What the pill says after the file count: what is left to answer, or how it went. */
data class TurnReviewLabel(val tone: Tone, val text: String) {
    enum class Tone { TODO, KEPT, REVERTED }
}

object TurnReview {
    /** Names one edit in a turn: a call can edit several files (a codex patch), a MultiEdit one file several times. */
    fun editKey(filePath: String, edit: FileEditFragment): String = "$filePath\u0000${edit.toolUseId.orEmpty()}\u0000${edit.editIndex}"

    private fun norm(path: String) = path.replace('\\', '/')

    fun editReviews(
        changes: List<TurnFileChange>,
        files: List<SessionFileChange>,
        /** Reverts made from the tray: edit key to the revert's undo id. */
        reverted: Map<String, String>,
    ): Map<String, EditReview> {
        val byPath = files.associateBy { norm(it.path) }
        val out = LinkedHashMap<String, EditReview>()
        for (change in changes) {
            val file = byPath[norm(change.filePath)]
            // The list can only say an edit is gone once the file's blocks name the calls that wrote them.
            val named = file?.blocks?.any { !it.calls.isNullOrEmpty() } == true
            for (edit in change.edits) {
                val key = editKey(change.filePath, edit)
                val undoId = reverted[key]
                val call = edit.toolUseId
                val blocks = if (call != null) file?.blocks?.filter { it.calls?.contains(call) == true }.orEmpty() else emptyList()
                val state = when {
                    blocks.isNotEmpty() -> if (blocks.all { it.kept }) EditReviewState.KEPT else EditReviewState.OPEN
                    // Compared with what the user marked reviewed: what came before the mark was reviewed.
                    file?.sinceReview == true && call != null -> EditReviewState.KEPT
                    undoId != null || (named && call != null) -> EditReviewState.REVERTED
                    else -> null
                }
                out[key] = EditReview(state, file, blocks.map { it.key }, undoId)
            }
        }
        return out
    }

    fun summary(reviews: Map<String, EditReview>): TurnReviewSummary {
        var open = 0
        var kept = 0
        var reverted = 0
        for (r in reviews.values) {
            when (r.state) {
                EditReviewState.OPEN -> open++
                EditReviewState.KEPT -> kept++
                EditReviewState.REVERTED -> reverted++
                null -> Unit
            }
        }
        return TurnReviewSummary(open, kept, reverted, open + kept + reverted)
    }

    fun label(s: TurnReviewSummary): TurnReviewLabel? = when {
        s.known == 0 -> null
        s.open > 0 -> TurnReviewLabel(TurnReviewLabel.Tone.TODO, "${s.open} edit${if (s.open == 1) "" else "s"} to review")
        s.reverted > 0 -> TurnReviewLabel(TurnReviewLabel.Tone.REVERTED, "${s.reverted} reverted")
        else -> TurnReviewLabel(TurnReviewLabel.Tone.KEPT, "Kept")
    }

    /**
     * The answer that covers [reviews], one entry per file at the version the list drew it: the
     * blocks still open for a keep, every block named for a reopen or a revert.
     */
    fun answerFiles(reviews: List<EditReview>, openOnly: Boolean): List<SessionAnswerFile> {
        val byPath = LinkedHashMap<String, Pair<String, LinkedHashSet<String>>>()
        for (r in reviews) {
            val file = r.file ?: continue
            val open = file.blocks?.filter { !it.kept }?.mapTo(HashSet()) { it.key }.orEmpty()
            val keys = if (openOnly) r.keys.filter { it in open } else r.keys
            if (keys.isEmpty()) continue
            byPath.getOrPut(file.path) { file.version to LinkedHashSet() }.second += keys
        }
        return byPath.map { (path, entry) -> SessionAnswerFile(path, entry.first, entry.second.toList()) }
    }

    /** "3 edits in 2 files". */
    fun editsInFiles(changes: List<TurnFileChange>): String =
        "${plural(changes.sumOf { it.edits.size }, "edit")} in ${plural(changes.size, "file")}"

    fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

    /** Lines added and removed across a turn. */
    fun totals(changes: List<TurnFileChange>): Pair<Int, Int> = changes.sumOf { it.linesAdded } to changes.sumOf { it.linesRemoved }
}

/** The pure half of the session's change list (`session-file-changes.ts`). */
object SessionChangeList {
    /** Lines added and removed across the session; a file that could not be counted adds nothing. */
    fun totals(files: List<SessionFileChange>): Pair<Int, Int> = files.sumOf { it.additions ?: 0 } to files.sumOf { it.deletions ?: 0 }

    /** Relative to the project when the file is inside it, absolute otherwise. */
    fun displayPath(path: String, projectPath: String?): String {
        if (projectPath.isNullOrEmpty()) return path
        val root = projectPath.trimEnd('/', '\\')
        if (path.length > root.length && path.startsWith(root) && (path[root.length] == '/' || path[root.length] == '\\')) {
            return path.substring(root.length + 1)
        }
        return path
    }

    /** At the last separator, either kind: the host may be Windows. */
    fun splitPath(path: String): Pair<String, String> {
        val i = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
        return if (i < 0) path to "" else path.substring(i + 1) to path.substring(0, i)
    }

    fun fileCount(n: Int) = "$n file${if (n == 1) "" else "s"}"

    /** The files left to review, and those marked reviewed as they are now, which the lists hide. */
    fun splitReviewed(files: List<SessionFileChange>): Pair<List<SessionFileChange>, List<SessionFileChange>> = files.partition { !it.reviewed }

    /**
     * Changes whenever what a diff of this file would show does: every write moves the version,
     * the baseline kind moves when a fallback to git HEAD turns into the session's own copy, and a
     * mark moves what a file changed since it was reviewed is diffed against.
     */
    fun changeKey(file: SessionFileChange): String {
        val review = if (file.reviewed) "reviewed" else if (file.sinceReview) "since" else ""
        return "${file.path}\u0000${file.status.wire}\u0000${file.baseline}\u0000${file.version}\u0000$review"
    }

    /** [files] with [paths] marked or unmarked reviewed ahead of the server's answer. */
    fun withReviewed(files: List<SessionFileChange>, paths: Set<String>, reviewed: Boolean): List<SessionFileChange> =
        files.map { if (it.path in paths) it.copy(reviewed = reviewed, sinceReview = false) else it }

    /**
     * [files] with the rows the server pushed after the agent changed them: each replaces its
     * file's row, a file not listed yet goes last (it is the newest), and the [gone] ones drop out.
     */
    fun withPushedChanges(files: List<SessionFileChange>, pushed: List<SessionFileChange>, gone: List<String>): List<SessionFileChange> {
        val byPath = pushed.associateBy { it.path }
        val drop = gone.toHashSet()
        val out = files.filter { it.path !in drop }.mapTo(ArrayList()) { byPath[it.path] ?: it }
        val listed = files.mapTo(HashSet()) { it.path }
        for (f in pushed) if (f.path !in listed) out += f
        return out
    }
}
