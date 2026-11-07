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
    val blocks: List<String>,
    val overflow: Int,
    val staged: Boolean,
)

data class ChangeTotals(
    val total: Int,
    val staged: Int,
    val unstaged: Int,
    val conflicts: Int,
    /** Files that will be included if Commit is pressed right now. */
    val commitCount: Int,
    /** True when pressing Commit will auto-stage all files (`staged == 0`). */
    val implicitAll: Boolean,
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
        // Prefer worktree status when partially staged so user sees what is still changing.
        return (file.unstaged?.status ?: file.staged?.status ?: "M")
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

    /** Block-dot sequence (max [max] dots, then `+N` overflow). */
    fun blockDots(file: ChangedFile, max: Int = 6): BlockDotsResult {
        val raw = buildList {
            file.staged?.blocks?.let(::addAll)
            file.unstaged?.blocks?.let(::addAll)
        }
        val blocks = raw.take(max)
        val overflow = maxOf(0, raw.size - max)
        return BlockDotsResult(blocks = blocks, overflow = overflow, staged = file.staged != null)
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
        var staged = 0
        var unstaged = 0
        var conflicts = 0
        for (f in files) {
            if (f.conflict != null) {
                conflicts++
                continue
            }
            if (f.staged != null) staged++
            if (f.unstaged != null) unstaged++
        }
        val nonConflict = files.size - conflicts
        val implicitAll = staged == 0 && nonConflict > 0
        val commitCount = if (implicitAll) nonConflict else staged
        return ChangeTotals(
            total = files.size,
            staged = staged,
            unstaged = unstaged,
            conflicts = conflicts,
            commitCount = commitCount,
            implicitAll = implicitAll,
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

    fun commitLabel(totals: ChangeTotals, amend: Boolean = false): String {
        if (amend && totals.commitCount == 0) return "Amend last commit"
        val verb = if (amend) "Amend" else "Commit"
        val n = totals.commitCount
        if (n == 0) return verb
        return "$verb $n ${if (n == 1) "file" else "files"}"
    }

    /** Muted hint below the Commit button explaining what will happen. */
    fun commitHint(totals: ChangeTotals, message: String): String? {
        if (totals.conflicts > 0) {
            val n = totals.conflicts
            return "Resolve $n ${if (n == 1) "conflict" else "conflicts"} before committing"
        }
        if (totals.total == 0) return null
        if (message.trim().isEmpty()) return "Write a commit message above"
        if (!totals.implicitAll && totals.unstaged > 0) {
            return "${totals.staged} of ${totals.total} selected — unchecked files stay uncommitted"
        }
        return null
    }

    fun canCommit(
        totals: ChangeTotals,
        message: String,
        operation: GitOperation?,
        amend: Boolean = false,
        hasLastCommit: Boolean = false,
    ): Boolean {
        if (totals.conflicts > 0) return false
        if (message.trim().isEmpty()) return false
        if (operation != null && operation.kind != "merge") return false
        if (amend) return hasLastCommit && (totals.commitCount > 0 || totals.total == 0)
        return totals.commitCount > 0
    }

    /** Summary text for a discard confirmation modal. */
    fun discardSummary(files: List<ChangedFile>): String {
        var tracked = 0
        var untracked = 0
        for (f in files) {
            if (f.unstaged?.status == "A" && f.staged == null) untracked++
            else if (f.unstaged != null) tracked++
        }
        val parts = mutableListOf<String>()
        if (tracked > 0) parts += "revert $tracked modified ${if (tracked == 1) "file" else "files"}"
        if (untracked > 0) parts += "delete $untracked new ${if (untracked == 1) "file" else "files"}"
        return if (parts.isNotEmpty()) parts.joinToString(" and ") else "discard changes"
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

    /** Convert [GitChanges] (`GET /git/changes`) to [GitBranchStatus] for the shared store. */
    fun changesToStatus(changes: GitChanges): GitBranchStatus {
        val staged = mutableListOf<GitFileChange>()
        val unstaged = mutableListOf<GitFileChange>()
        val untracked = mutableListOf<String>()
        for (file in changes.files) {
            if (file.conflict != null) {
                unstaged += GitFileChange(path = file.path, status = "U")
                continue
            }
            val stagedSide = file.staged
            if (stagedSide != null) {
                staged += GitFileChange(path = file.path, status = stagedSide.status, oldPath = file.oldPath)
            }
            val unstagedSide = file.unstaged
            if (unstagedSide != null) {
                if (unstagedSide.status == "A" && stagedSide == null) {
                    untracked += file.path
                } else {
                    unstaged += GitFileChange(path = file.path, status = unstagedSide.status, oldPath = file.oldPath)
                }
            }
        }
        return GitBranchStatus(
            branch = changes.branch.head ?: changes.branch.oid?.take(7) ?: "",
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
     */
    fun buildFileStatusMap(status: GitBranchStatus): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (u in status.untracked) {
            map[u.trimEnd('/')] = "U"
        }
        for (change in status.unstaged) {
            val s = change.status.uppercase()
            if (s in STATUS_PRIORITY) map[change.path] = s
        }
        for (change in status.staged) {
            if (change.path !in map) {
                val s = change.status.uppercase()
                if (s in STATUS_PRIORITY) map[change.path] = s
            }
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
