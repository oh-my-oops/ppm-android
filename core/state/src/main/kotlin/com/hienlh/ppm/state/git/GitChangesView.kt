package com.hienlh.ppm.state.git

import com.hienlh.ppm.client.ChangedFile
import com.hienlh.ppm.client.GitBranchState
import com.hienlh.ppm.client.GitBranchStatus
import com.hienlh.ppm.client.GitChanges
import com.hienlh.ppm.client.GitFileChange
import com.hienlh.ppm.client.GitOperation
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class CheckState {
    ALL,
    SOME,
    NONE,
}

data class BlockDotsResult(
    val dots: List<Boolean>,
    val blocks: List<String>,
    val overflow: Int,
    val staged: Boolean,
)

data class ChangeTotals(
    val files: Int,
    val filesStaged: Int,
    val blocks: Int,
    val blocksStaged: Int,
    val conflicts: Int,
) {
    val total: Int get() = files
    val staged: Int get() = filesStaged
    val commitCount: Int get() = filesStaged
}

data class DiscardSummary(
    val title: String,
    val body: String,
    val confirm: String,
)

/**
 * Pure derivations for the Source Control panel (`src/web/lib/git-changes-view.ts` and
 * `src/web/stores/git-status-store.ts`).
 */
object GitChangesView {
    private val CONFLICT_MARKER_RE = Regex("""^(<{7} |={7}$|>{7} )""", RegexOption.MULTILINE)

    val OPERATION_NOUN: Map<String, String> = mapOf(
        "merge" to "merge",
        "rebase" to "rebase",
        "cherry-pick" to "cherry-pick",
        "revert" to "revert",
        "am" to "patch",
    )

    private val STATUS_PRIORITY: Map<String, Int> = mapOf(
        "D" to 6,
        "A" to 5,
        "M" to 4,
        "R" to 3,
        "C" to 2,
        "U" to 1,
    )

    fun fileCheckState(file: ChangedFile): CheckState {
        if (file.conflict != null) return CheckState.NONE
        if (file.staged != null && file.unstaged != null) return CheckState.SOME
        if (file.staged != null) return CheckState.ALL
        return CheckState.NONE
    }

    fun allCheckState(files: List<ChangedFile>): CheckState {
        val eligible = files.filter { it.conflict == null }
        if (eligible.isEmpty()) return CheckState.NONE
        var staged = 0
        var partial = 0
        for (f in eligible) {
            when (fileCheckState(f)) {
                CheckState.ALL -> staged++
                CheckState.SOME -> partial++
                CheckState.NONE -> Unit
            }
        }
        if (staged == eligible.size) return CheckState.ALL
        if (staged > 0 || partial > 0) return CheckState.SOME
        return CheckState.NONE
    }

    /** Primary status letter shown in the 14px tile (`U`, `A`, `M`, `D`, `R`). */
    fun changeLetter(file: ChangedFile): String {
        if (file.conflict != null) return "U"
        if (file.untracked || file.x == "A") return "A"
        if (file.oldPath != null) return "R"
        if (file.x == "D" || file.y == "D") return "D"
        return "M"
    }

    /** True if the text still contains Git conflict markers at the start of a line. */
    fun hasConflictMarkers(text: String): Boolean = CONFLICT_MARKER_RE.containsMatchIn(text)

    /** Combined line additions/deletions across staged + unstaged sides (`added` to `removed`). */
    fun changeCounts(file: ChangedFile): Pair<Int, Int>? {
        if (file.conflict != null) return null
        val whole = file.unstaged?.whole ?: file.staged?.whole
        if (whole != null && (file.staged?.added ?: 0) + (file.unstaged?.added ?: 0) == 0 &&
            (file.staged?.removed ?: 0) + (file.unstaged?.removed ?: 0) == 0
        ) {
            return null
        }
        val added = (file.staged?.added ?: 0) + (file.unstaged?.added ?: 0)
        val removed = (file.staged?.removed ?: 0) + (file.unstaged?.removed ?: 0)
        if (added == 0 && removed == 0) return null
        return added to removed
    }

    /** Short explanation shown in place of `+N -M` when no line counts exist. */
    fun lineNote(file: ChangedFile): String? {
        if (file.conflict != null || changeCounts(file) != null) return null
        return file.unstaged?.whole ?: file.staged?.whole
    }

    private fun blockAnchor(side: String, block: com.hienlh.ppm.client.ChangeBlock): Double =
        if (side == "staged") {
            if (block.newLines == 0) block.newStart + 0.5 else block.newStart.toDouble()
        } else {
            if (block.oldLines == 0) block.oldStart + 0.5 else block.oldStart.toDouble()
        }

    /** Block-dot sequence (max [max] dots, then `+N` overflow). */
    fun blockDots(file: ChangedFile, max: Int = 6): BlockDotsResult {
        val raw = mutableListOf<Pair<Double, Boolean>>()
        fun add(name: String, side: com.hienlh.ppm.client.ChangeSide?) {
            if (side == null) return
            val isStaged = name == "staged"
            if (side.whole != null || side.changeBlocks.isEmpty()) {
                raw += 0.0 to isStaged
            } else {
                for (block in side.changeBlocks) {
                    raw += blockAnchor(name, block) to isStaged
                }
            }
        }
        add("staged", file.staged)
        add("unstaged", file.unstaged)
        val sorted = raw.sortedBy { it.first }.map { it.second }
        val shown = if (sorted.size <= max) sorted else sorted.take(max - 1)
        val overflow = if (sorted.size <= max) 0 else sorted.size - (max - 1)
        return BlockDotsResult(
            dots = shown,
            blocks = shown.map { if (it) "staged" else "unstaged" },
            overflow = overflow,
            staged = file.staged != null,
        )
    }

    /** Whether a file has unstaged changes that can be discarded. */
    fun hasUnstaged(file: ChangedFile): Boolean =
        file.unstaged != null && file.conflict == null

    /**
     * Paths to pass to `POST /git/unstage` for a single file.
     * A staged rename records the old path in HEAD and the new path in the index;
     * `git reset HEAD -- <new>` alone leaves `<old>` staged as a deletion.
     */
    fun unstagePaths(file: ChangedFile): List<String> =
        if (!file.oldPath.isNullOrEmpty() && file.oldPath != file.path) {
            listOf(file.path, file.oldPath!!)
        } else {
            listOf(file.path)
        }

    fun changeTotals(files: List<ChangedFile>): ChangeTotals {
        var filesCount = 0
        var filesStaged = 0
        var blocks = 0
        var blocksStaged = 0
        var conflicts = 0
        for (file in files) {
            filesCount++
            if (file.conflict != null) {
                conflicts++
                continue
            }
            val staged = file.staged
            if (staged != null) {
                filesStaged++
                val count = if (staged.whole != null || staged.changeBlocks.isEmpty()) 1 else staged.changeBlocks.size
                blocks += count
                blocksStaged += count
            }
            val unstaged = file.unstaged
            if (unstaged != null) {
                val count = if (unstaged.whole != null || unstaged.changeBlocks.isEmpty()) 1 else unstaged.changeBlocks.size
                blocks += count
            }
        }
        return ChangeTotals(
            files = filesCount,
            filesStaged = filesStaged,
            blocks = blocks,
            blocksStaged = blocksStaged,
            conflicts = conflicts,
        )
    }

    /** `"publish"` | `"sync"` | `"pull"` | `"push"` | `"synced"` | `null` */
    fun syncMode(branch: GitBranchState): String? {
        if (branch.head == null || !branch.hasRemote) return null // detached, unborn, or no remote
        if (branch.upstream == null || branch.upstreamGone) return "publish"
        if (branch.ahead > 0 && branch.behind > 0) return "sync"
        if (branch.behind > 0) return "pull"
        if (branch.ahead > 0) return "push"
        return "synced"
    }

    /** Sync button becomes primary when the working tree is clean and there is work to move. */
    fun syncIsPrimary(mode: String?, fileCount: Int): Boolean =
        fileCount == 0 && mode != null && mode != "synced"

    private fun plural(n: Int, word: String): String = "$n ${if (n == 1) word else "${word}s"}"

    fun commitLabel(totals: ChangeTotals, amend: Boolean = false): String =
        if (totals.filesStaged > 0) "Commit ${plural(totals.filesStaged, "file")}" else "Commit"

    /** Muted hint below the Commit button explaining what will happen. */
    fun commitHint(totals: ChangeTotals, message: String, keys: String = "⌘↵"): String {
        if (totals.files == 0) return "Nothing to commit"
        if (totals.conflicts > 0) return "Resolve ${plural(totals.conflicts, "conflict")} to commit"
        if (totals.filesStaged == 0) return "Tick a file or stage a block to commit"
        if (message.trim().isEmpty()) return "Write a message to commit"
        return "${totals.blocksStaged} of ${plural(totals.blocks, "block")} staged · $keys commits"
    }

    fun canCommit(
        totals: ChangeTotals,
        message: String,
        operation: GitOperation? = null,
        amend: Boolean = false,
        hasLastCommit: Boolean = false,
        lastCommitPushed: Boolean = false,
    ): Boolean {
        if (operation != null && operation.kind != "merge") return false
        if (amend) {
            return hasLastCommit && !lastCommitPushed && (totals.filesStaged > 0 || message.trim().isNotEmpty())
        }
        return totals.filesStaged > 0 && message.trim().isNotEmpty() && totals.conflicts == 0
    }

    /** Summary for a discard confirmation modal (`discardSummary` in `src/web/lib/git-changes-view.ts`). */
    fun discardSummary(files: List<ChangedFile>): DiscardSummary {
        val added = files.count { it.untracked || it.x == "A" }
        val modified = files.size - added
        val single = files.singleOrNull()
        val name = single?.path?.substringAfterLast('/')

        if (single != null && name != null) {
            return if (added == 1) {
                DiscardSummary(
                    title = "Delete \"$name\"?",
                    body = "This file is not in any commit. Deleting it removes it from disk.",
                    confirm = "Delete file",
                )
            } else {
                DiscardSummary(
                    title = "Discard changes in \"$name\"?",
                    body = "Uncommitted edits in this file will be replaced with the last commit.",
                    confirm = "Discard changes",
                )
            }
        }

        val parts = mutableListOf<String>()
        if (modified > 0) parts += "discard edits in $modified ${if (modified == 1) "file" else "files"}"
        if (added > 0) parts += "delete $added untracked ${if (added == 1) "file" else "files"}"
        return DiscardSummary(
            title = "Discard ${files.size} changes?",
            body = "This will ${parts.joinToString(" and ")}.",
            confirm = "Discard ${files.size} files",
        )
    }

    fun operationTitle(op: GitOperation, branch: GitBranchState): String {
        val onto = branch.head ?: "HEAD"
        val target = op.head
        return when (op.kind) {
            "merge" -> if (target != null) "Merging $target into $onto" else "Merging into $onto"
            "rebase" -> {
                val step = if (op.step != null && op.total != null) " (${op.step} of ${op.total})" else ""
                val into = if (target != null) " onto $target" else ""
                "Rebasing$into$step"
            }
            "cherry-pick" -> if (target != null) "Cherry-picking $target" else "Cherry-picking"
            "revert" -> if (target != null) "Reverting $target" else "Reverting"
            "am" -> "Applying patch"
            else -> op.kind
        }
    }

    /** Split `"src/web/app.tsx"` into `Pair("app.tsx", "src/web")`. */
    fun splitPath(path: String): Pair<String, String> {
        val idx = path.lastIndexOf('/')
        if (idx == -1) return path to ""
        return path.substring(idx + 1) to path.substring(0, idx)
    }

    /** Convert [GitChanges] (`GET /git/changes`) to [GitBranchStatus] for the shared store (`changesToStatus`). */
    fun changesToStatus(changes: GitChanges): GitBranchStatus {
        val staged = mutableListOf<GitFileChange>()
        val unstaged = mutableListOf<GitFileChange>()
        val untracked = mutableListOf<String>()

        fun letter(code: String): String = when (code) {
            "A" -> "A"
            "D" -> "D"
            "R" -> "R"
            else -> "M"
        }

        for (file in changes.files) {
            if (file.untracked) {
                untracked += file.path
                continue
            }
            if (file.conflict != null) {
                unstaged += GitFileChange(path = file.path, status = "M")
                continue
            }
            if (file.staged != null) {
                staged += GitFileChange(path = file.path, status = letter(file.x), oldPath = file.oldPath)
            }
            if (file.unstaged != null) {
                unstaged += GitFileChange(path = file.path, status = letter(file.y), oldPath = file.oldPath)
            }
        }
        return GitBranchStatus(
            branch = changes.branch.head ?: "HEAD",
            tracking = changes.branch.upstream,
            ahead = changes.branch.ahead,
            behind = changes.branch.behind,
            staged = staged,
            unstaged = unstaged,
            untracked = untracked,
        )
    }

    /**
     * Build a map of `filePath -> statusLetter` (`"M"`, `"A"`, `"D"`, `"R"`, `"C"`, `"U"`)
     * for Explorer file rows (`buildFileStatusMap` in `git-status-store.ts`).
     * Priority: staged > unstaged > untracked.
     */
    fun buildFileStatusMap(status: GitBranchStatus): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (u in status.untracked) {
            map[u.trimEnd('/')] = "U"
        }
        for (change in status.unstaged) {
            val s = if (change.status == "?") "U" else change.status.uppercase()
            if (s in STATUS_PRIORITY) map[change.path] = s
        }
        for (change in status.staged) {
            val s = if (change.status == "?") "U" else change.status.uppercase()
            if (s in STATUS_PRIORITY) map[change.path] = s
        }
        return map
    }

    /**
     * Propagate file statuses up to all parent folders so collapsed folders show an indicator
     * (`buildFolderStatusMap` in `git-status-store.ts`).
     */
    fun buildFolderStatusMap(fileStatuses: Map<String, String>): Map<String, String> {
        val folders = LinkedHashMap<String, String>()
        for ((filePath, status) in fileStatuses) {
            val parts = filePath.split('/')
            val statusPriority = STATUS_PRIORITY[status] ?: 0
            // Also mark the entry itself if it is an untracked directory (`git status` returns dir/).
            val existingSelf = folders[filePath]
            if (existingSelf == null || statusPriority > (STATUS_PRIORITY[existingSelf] ?: 0)) {
                folders[filePath] = status
            }
            for (i in 1 until parts.size) {
                val dirPath = parts.subList(0, i).joinToString("/")
                val existing = folders[dirPath]
                if (existing == null || statusPriority > (STATUS_PRIORITY[existing] ?: 0)) {
                    folders[dirPath] = status
                }
            }
        }
        return folders
    }

    private val SHORT_DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM d", Locale.US)

    /**
     * Format an ISO timestamp as a short English relative time string (`formatRelativeDate` on web):
     * `"just now"`, `"5m ago"`, `"3h ago"`, `"2d ago"`, `"Apr 21"`.
     */
    fun formatRelativeTime(iso: String, nowMs: Long = System.currentTimeMillis()): String {
        if (iso.isBlank()) return ""
        val epochMs = runCatching { Instant.parse(iso).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }
            .getOrNull() ?: return ""
        val diffMs = nowMs - epochMs
        val diffMin = diffMs / 60_000L
        if (diffMin < 1L) return "just now"
        if (diffMin < 60L) return "${diffMin}m ago"
        val diffHr = diffMin / 60L
        if (diffHr < 24L) return "${diffHr}h ago"
        val diffDay = diffHr / 24L
        if (diffDay < 7L) return "${diffDay}d ago"
        return SHORT_DATE_FORMATTER.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
    }
}
