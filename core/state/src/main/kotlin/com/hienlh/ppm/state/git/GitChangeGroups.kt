package com.hienlh.ppm.state.git

import com.hienlh.ppm.client.ChangeSide
import com.hienlh.ppm.client.ChangedFile
import com.hienlh.ppm.client.DiscardRecord
import com.hienlh.ppm.client.GitBranchStatus
import com.hienlh.ppm.client.GitChanges
import com.hienlh.ppm.client.PpmError
import kotlin.math.ceil

/**
 * Which section of the phone/tablet Source Control list a [FileChange] row belongs to
 * (Requirement 9.1, `design.md` Section 2).
 */
enum class GitChangeGroup {
    STAGED,
    CHANGES,
}

/**
 * Contextual actions offered in the long-press sheet for a single changed file on phone
 * (Requirements 9.2, 9.3, 9.4, 9.6 — whole-file operations only).
 */
enum class PhoneFileActionKind {
    OPEN_DIFF,
    OPEN_FILE,
    STAGE_FILE,
    UNSTAGE_FILE,
    DISCARD_CHANGES,
}

/**
 * Capability flags and copy constants for Source Control on phone (Requirement 9.6).
 *
 * Phone intentionally omits hunk/line staging, hunk/line discard, Discard all, Review changes,
 * Git Graph, stash, worktrees, conflict resolution, and blame.
 */
object PhoneGitCapabilities {
    const val ALLOWS_BLOCK_OR_LINE_STAGING: Boolean = false
    const val ALLOWS_BLOCK_OR_LINE_DISCARD: Boolean = false
    const val ALLOWS_DISCARD_ALL: Boolean = false
    const val ALLOWS_REVIEW_CHANGES: Boolean = false
    const val ALLOWS_GIT_GRAPH: Boolean = false
    const val ALLOWS_STASH: Boolean = false
    const val ALLOWS_WORKTREES: Boolean = false
    const val ALLOWS_CONFLICT_RESOLUTION: Boolean = false
    const val ALLOWS_BLAME: Boolean = false

    const val CONFLICT_PAUSE_BANNER: String =
        "Resolve conflicts on a tablet. Commit and Pull are paused until then."

    const val PART_OF_FILE_LABEL: String = "part of the file"
    const val REST_OF_FILE_LABEL: String = "rest of the file"
    const val CONFLICT_LABEL: String = "conflict"

    /**
     * Contextual actions for a single [FileChange] row on phone (`Open diff`, `Open file`,
     * `Stage file` / `Unstage file`, and `Discard changes…`).
     */
    fun actionsFor(change: FileChange): List<PhoneFileActionKind> {
        if (change.conflict) {
            return listOf(
                PhoneFileActionKind.OPEN_DIFF,
                PhoneFileActionKind.OPEN_FILE,
            )
        }
        val stageAction = if (change.group == GitChangeGroup.STAGED) {
            PhoneFileActionKind.UNSTAGE_FILE
        } else {
            PhoneFileActionKind.STAGE_FILE
        }
        return buildList {
            add(PhoneFileActionKind.OPEN_DIFF)
            add(PhoneFileActionKind.OPEN_FILE)
            add(stageAction)
            if (change.canDiscard) {
                add(PhoneFileActionKind.DISCARD_CHANGES)
            }
        }
    }
}

/**
 * One file row inside [GitChangeGroups.staged] or [GitChangeGroups.changes]
 * (Requirements 9.1, 9.2, 9.3, 9.4).
 *
 * When a file is partially staged (has both staged and unstaged hunks), it appears once in
 * [GitChangeGroups.staged] with [partiallyStaged] = `true` (`"part of the file"`) and once in
 * [GitChangeGroups.changes] with [partiallyStaged] = `true` (`"rest of the file"`), each carrying
 * that group's own `+added / -removed` counts.
 */
data class FileChange(
    val path: String,
    val group: GitChangeGroup,
    val partiallyStaged: Boolean,
    val oldPath: String? = null,
    val statusLetter: String = "M",
    val added: Int = 0,
    val removed: Int = 0,
    val lineNote: String? = null,
    val conflict: Boolean = false,
    val untracked: Boolean = false,
    val source: ChangedFile? = null,
) {
    /** Basename of [path] (`"git.ts"` for `"src/server/routes/git.ts"`). */
    val fileName: String
        get() = path.substringAfterLast('/')

    /** Parent folder of [path] (`"src/server/routes"` for `"src/server/routes/git.ts"`, `""` at root). */
    val directoryPath: String
        get() = path.substringBeforeLast('/', "")

    /** True when this row sits in the `Staged` group. */
    val isStaged: Boolean
        get() = group == GitChangeGroup.STAGED

    /** Whether the 22 dp whole-file checkbox is checked on this row. */
    val isChecked: Boolean
        get() = group == GitChangeGroup.STAGED && !conflict

    /**Whether tapping the checkbox can stage/unstage this file. */
    val canToggleStage: Boolean
        get() = !conflict

    /**
     * Whether `"Discard changes…"` is available for this file row.
     * On phone, discarding from the long-press sheet discards the whole file's unstaged changes
     * (or deletes an untracked file). Conflicted files cannot be discarded on phone.
     */
    val canDiscard: Boolean
        get() = !conflict && (group == GitChangeGroup.CHANGES || source?.unstaged != null || untracked)

    /**
     * Note shown in the row subline when a file is split across `Staged` and `Changes` or in conflict:
     * - `"part of the file"` in `Staged`
     * - `"rest of the file"` in `Changes`
     * - `"conflict"` when conflicted
     */
    val partialLabel: String?
        get() = when {
            conflict -> PhoneGitCapabilities.CONFLICT_LABEL
            !partiallyStaged -> null
            group == GitChangeGroup.STAGED -> PhoneGitCapabilities.PART_OF_FILE_LABEL
            else -> PhoneGitCapabilities.REST_OF_FILE_LABEL
        }

    /**
     * Secondary line under [fileName] (`"src/server · part of the file"`, `"src/server"`,
     * `"old.kt → new.kt"`, or `"part of the file"`).
     */
    val subline: String
        get() {
            val base = when {
                !oldPath.isNullOrEmpty() && oldPath != path -> "$oldPath → $path"
                directoryPath.isNotEmpty() -> directoryPath
                else -> ""
            }
            val note = partialLabel
            return when {
                base.isNotEmpty() && !note.isNullOrEmpty() -> "$base · $note"
                !note.isNullOrEmpty() -> note
                base.isNotEmpty() -> base
                untracked -> "untracked"
                else -> path
            }
        }

    /** `+added` / `-removed` pair for this group's side, or `null` when both are 0 or conflicted. */
    val lineCounts: Pair<Int, Int>?
        get() = if (conflict || (added == 0 && removed == 0)) null else added to removed

    /** Scope parameter for `GET /git/hunks` when opening this row's diff. */
    val diffScope: String
        get() = if (group == GitChangeGroup.STAGED) "staged" else "worktree"

    /** Paths to pass to `POST /git/stage` to stage the whole file. */
    val stagePaths: List<String>
        get() = listOf(path)

    /**
     * Paths to pass to `POST /git/unstage` to unstage the whole file (includes [oldPath] on a
     * staged rename so the old path does not remain staged as a deletion).
     */
    val unstagePaths: List<String>
        get() = if (!oldPath.isNullOrEmpty() && oldPath != path) {
            listOf(path, oldPath)
        } else {
            source?.let(GitChangesView::unstagePaths) ?: listOf(path)
        }
}

/**
 * Splits a repository's changed files into `Staged` and `Changes` groups for the phone and tablet
 * Source Control lists (Requirements 9.1, 9.3, `design.md` Section 2).
 */
data class GitChangeGroups(
    val staged: List<FileChange>,
    val changes: List<FileChange>,
) {
    val isEmpty: Boolean
        get() = staged.isEmpty() && changes.isEmpty()

    val stagedCount: Int
        get() = staged.size

    val changesCount: Int
        get() = changes.size

    /** Number of distinct files across both `Staged` and `Changes`. */
    val totalFiles: Int
        get() = (staged.map { it.path } + changes.map { it.path }).distinct().size

    val conflictCount: Int
        get() = changes.count { it.conflict }

    val hasConflicts: Boolean
        get() = conflictCount > 0

    /** Paths that appear in both [staged] and [changes] (`partiallyStaged == true`). */
    val partiallyStagedPaths: Set<String>
        get() {
            val stagedPaths = staged.mapTo( LinkedHashSet() ) { it.path }
            return changes.mapNotNullTo(LinkedHashSet()) { if (it.path in stagedPaths) it.path else null }
        }

    /**
     * Stages the whole file at [path], moving it completely into [staged] (`partiallyStaged = false`)
     * and removing any entry for [path] from [changes] (Requirement 9.3).
     */
    fun stageFile(path: String): GitChangeGroups {
        val existingStaged = staged.find { it.path == path }
        val existingUnstaged = changes.find { it.path == path }
        if (existingUnstaged == null) return this
        if (existingUnstaged.conflict) return this

        val mergedAdded = (existingStaged?.added ?: 0) + existingUnstaged.added
        val mergedRemoved = (existingStaged?.removed ?: 0) + existingUnstaged.removed
        val mergedSource = existingUnstaged.source?.let { src ->
            val combinedSide = ChangeSide(
                added = mergedAdded,
                removed = mergedRemoved,
                blocks = (src.staged?.blocks.orEmpty() + src.unstaged?.blocks.orEmpty()).distinct(),
                whole = src.staged?.whole ?: src.unstaged?.whole,
                changeBlocks = src.staged?.changeBlocks.orEmpty() + src.unstaged?.changeBlocks.orEmpty(),
            )
            src.copy(
                staged = combinedSide,
                unstaged = null,
                untracked = false,
                x = if (src.untracked || src.x == "?" || src.x == ".") "A" else src.x,
                y = ".",
            )
        }
        val stagedEntry = FileChange(
            path = path,
            group = GitChangeGroup.STAGED,
            partiallyStaged = false,
            oldPath = existingStaged?.oldPath ?: existingUnstaged.oldPath,
            statusLetter = existingStaged?.statusLetter ?: existingUnstaged.statusLetter,
            added = mergedAdded,
            removed = mergedRemoved,
            lineNote = existingStaged?.lineNote ?: existingUnstaged.lineNote,
            conflict = false,
            untracked = false,
            source = mergedSource ?: existingStaged?.source ?: existingUnstaged.source,
        )

        val nextStaged = if (existingStaged != null) {
            staged.map { if (it.path == path) stagedEntry else it }
        } else {
            staged + stagedEntry
        }
        val nextChanges = changes.filterNot { it.path == path }
        return GitChangeGroups(staged = nextStaged, changes = nextChanges)
    }

    /**
     * Unstages the whole file at [path], moving it completely into [changes] (`partiallyStaged = false`)
     * and removing any entry for [path] from [staged] (Requirement 9.3).
     */
    fun unstageFile(path: String): GitChangeGroups {
        val existingStaged = staged.find { it.path == path } ?: return this
        val existingUnstaged = changes.find { it.path == path }

        val mergedAdded = existingStaged.added + (existingUnstaged?.added ?: 0)
        val mergedRemoved = existingStaged.removed + (existingUnstaged?.removed ?: 0)
        val wasAddedOnly = existingStaged.statusLetter == "A" && existingUnstaged == null &&
            (existingStaged.source?.untracked == true || existingStaged.source?.x == "A")
        val mergedSource = (existingUnstaged?.source ?: existingStaged.source)?.let { src ->
            val combinedSide = ChangeSide(
                added = mergedAdded,
                removed = mergedRemoved,
                blocks = (src.staged?.blocks.orEmpty() + src.unstaged?.blocks.orEmpty()).distinct(),
                whole = src.unstaged?.whole ?: src.staged?.whole,
                changeBlocks = src.staged?.changeBlocks.orEmpty() + src.unstaged?.changeBlocks.orEmpty(),
            )
            src.copy(
                staged = null,
                unstaged = combinedSide,
                untracked = wasAddedOnly,
                x = if (wasAddedOnly) "?" else ".",
                y = if (wasAddedOnly) "?" else "M",
            )
        }
        val unstagedEntry = FileChange(
            path = path,
            group = GitChangeGroup.CHANGES,
            partiallyStaged = false,
            oldPath = existingUnstaged?.oldPath ?: existingStaged.oldPath,
            statusLetter = existingUnstaged?.statusLetter ?: existingStaged.statusLetter,
            added = mergedAdded,
            removed = mergedRemoved,
            lineNote = existingUnstaged?.lineNote ?: existingStaged.lineNote,
            conflict = false,
            untracked = wasAddedOnly || (existingUnstaged?.untracked == true),
            source = mergedSource,
        )

        val nextStaged = staged.filterNot { it.path == path }
        val nextChanges = if (existingUnstaged != null) {
            changes.map { if (it.path == path) unstagedEntry else it }
        } else {
            changes + unstagedEntry
        }
        return GitChangeGroups(staged = nextStaged, changes = nextChanges)
    }

    /**
     * Toggles the whole file between [staged] and [changes] when its checkbox is tapped
     * (Requirement 9.3).
     */
    fun toggleFile(change: FileChange): GitChangeGroups =
        if (change.group == GitChangeGroup.STAGED) {
            unstageFile(change.path)
        } else {
            stageFile(change.path)
        }

    /**
     * Removes unstaged changes for [path] after a whole-file discard. If [path] had staged changes,
     * its [staged] entry remains with `partiallyStaged = false`.
     */
    fun discardFile(path: String): GitChangeGroups {
        val nextChanges = changes.filterNot { it.path == path }
        val nextStaged = staged.map { entry ->
            if (entry.path == path && entry.partiallyStaged) {
                entry.copy(
                    partiallyStaged = false,
                    source = entry.source?.copy(unstaged = null),
                )
            } else {
                entry
            }
        }
        return GitChangeGroups(staged = nextStaged, changes = nextChanges)
    }

    companion object {
        fun from(changes: GitChanges): GitChangeGroups = from(changes.files)

        /**
         * Splits [files] (`GET /git/changes`) into `Staged` and `Changes` groups.
         *
         * A file with both `staged != null` and (`unstaged != null || untracked`) is listed in
         * **both** groups with `partiallyStaged = true` (Requirement 9.1).
         */
        fun from(files: List<ChangedFile>): GitChangeGroups {
            val stagedList = mutableListOf<FileChange>()
            val changesList = mutableListOf<FileChange>()

            for (file in files) {
                if (file.conflict) {
                    changesList += FileChange(
                        path = file.path,
                        group = GitChangeGroup.CHANGES,
                        partiallyStaged = false,
                        oldPath = file.oldPath,
                        statusLetter = "U",
                        added = 0,
                        removed = 0,
                        lineNote = null,
                        conflict = true,
                        untracked = false,
                        source = file,
                    )
                    continue
                }

                val stagedSide = file.staged
                val unstagedSide = file.unstaged
                val hasStaged = stagedSide != null
                val hasUnstaged = unstagedSide != null || file.untracked
                val isPartial = hasStaged && hasUnstaged

                if (hasStaged && stagedSide != null) {
                    stagedList += FileChange(
                        path = file.path,
                        group = GitChangeGroup.STAGED,
                        partiallyStaged = isPartial,
                        oldPath = file.oldPath,
                        statusLetter = stagedStatusLetter(file),
                        added = stagedSide.added,
                        removed = stagedSide.removed,
                        lineNote = sideLineNote(stagedSide),
                        conflict = false,
                        untracked = false,
                        source = file,
                    )
                }

                if (hasUnstaged) {
                    changesList += FileChange(
                        path = file.path,
                        group = GitChangeGroup.CHANGES,
                        partiallyStaged = isPartial,
                        oldPath = if (hasStaged) null else file.oldPath,
                        statusLetter = unstagedStatusLetter(file),
                        added = unstagedSide?.added ?: 0,
                        removed = unstagedSide?.removed ?: 0,
                        lineNote = sideLineNote(unstagedSide),
                        conflict = false,
                        untracked = file.untracked,
                        source = file,
                    )
                }
            }

            return GitChangeGroups(
                staged = stagedList,
                changes = changesList,
            )
        }

        /**
         * Fallback grouping from lightweight `GET /git/status` payload when full `GitChanges` has
         * not loaded yet. Files present in both `status.staged` and `status.unstaged` are marked
         * `partiallyStaged = true` in both groups.
         */
        fun from(status: GitBranchStatus): GitChangeGroups {
            val stagedByPath = status.staged.associateBy { it.path }
            val unstagedByPath = status.unstaged.associateBy { it.path }
            val untrackedSet = status.untracked.map { it.trimEnd('/') }.toSet()

            val stagedList = status.staged.map { s ->
                val isPartial = s.path in unstagedByPath || s.path in untrackedSet
                val letter = normalizeStatusLetter(s.status, oldPath = s.oldPath, untracked = false)
                FileChange(
                    path = s.path,
                    group = GitChangeGroup.STAGED,
                    partiallyStaged = isPartial,
                    oldPath = s.oldPath,
                    statusLetter = letter,
                )
            }

            val changesList = buildList {
                for (u in status.unstaged) {
                    val isPartial = u.path in stagedByPath
                    val letter = normalizeStatusLetter(u.status, oldPath = u.oldPath, untracked = false)
                    add(
                        FileChange(
                            path = u.path,
                            group = GitChangeGroup.CHANGES,
                            partiallyStaged = isPartial,
                            oldPath = u.oldPath,
                            statusLetter = letter,
                            conflict = letter == "U",
                        ),
                    )
                }
                for (rawPath in status.untracked) {
                    val cleanPath = rawPath.trimEnd('/')
                    if (cleanPath in unstagedByPath) continue
                    val isPartial = cleanPath in stagedByPath
                    add(
                        FileChange(
                            path = cleanPath,
                            group = GitChangeGroup.CHANGES,
                            partiallyStaged = isPartial,
                            statusLetter = "A",
                            untracked = true,
                        ),
                    )
                }
            }

            return GitChangeGroups(
                staged = stagedList,
                changes = changesList,
            )
        }

        private fun stagedStatusLetter(file: ChangedFile): String = when {
            file.x == "A" -> "A"
            file.oldPath != null || file.x == "R" -> "R"
            file.x == "D" -> "D"
            file.x == "C" -> "C"
            else -> GitChangesView.changeLetter(file)
        }

        private fun unstagedStatusLetter(file: ChangedFile): String = when {
            file.untracked -> "A"
            file.y == "D" -> "D"
            file.y == "A" -> "A"
            file.staged == null && file.oldPath != null -> "R"
            else -> "M"
        }

        private fun normalizeStatusLetter(status: String, oldPath: String?, untracked: Boolean): String {
            if (untracked) return "A"
            if (!oldPath.isNullOrEmpty()) return "R"
            return when (status.uppercase()) {
                "A", "?" -> "A"
                "D" -> "D"
                "R" -> "R"
                "C" -> "C"
                "U" -> "U"
                else -> "M"
            }
        }

        private fun sideLineNote(side: ChangeSide?): String? {
            if (side == null) return null
            if (side.added == 0 && side.removed == 0) return side.whole
            return null
        }
    }
}

/**
 * Confirmation dialog state before discarding a single file on phone (Requirement 9.4).
 */
data class DiscardConfirmDialogState(
    val targetPath: String,
    val files: List<ChangedFile>,
    val summary: DiscardSummary,
) {
    val title: String get() = summary.title
    val body: String get() = summary.body
    val confirmLabel: String get() = summary.confirm
}

/**
 * Active 8-second Undo bar state after discarding a file (Requirement 9.4).
 */
data class DiscardUndoBarState(
    val recordId: String,
    val path: String,
    val message: String,
    val remainingMs: Long = DiscardUndoController.UNDO_DURATION_MS,
    val totalDurationMs: Long = DiscardUndoController.UNDO_DURATION_MS,
) {
    val active: Boolean
        get() = recordId.isNotEmpty() && remainingMs > 0L

    val remainingSeconds: Int
        get() = if (!active) 0 else ceil(remainingMs / 1000.0).toInt()
}

/**
 * Outcome of a discard or discard-undo action (Requirement 9.4, `design.md` Section 2).
 */
sealed interface DiscardUndoOutcome {
    data class Discarded(
        val recordId: String,
        val path: String,
        val message: String,
    ) : DiscardUndoOutcome

    data class SkippedTooLarge(
        val path: String,
        val message: String,
    ) : DiscardUndoOutcome

    data class Restored(
        val recordId: String,
        val paths: List<String>,
    ) : DiscardUndoOutcome

    data class Conflict(
        val recordId: String,
        val message: String = DiscardUndoController.CONFLICT_MESSAGE,
    ) : DiscardUndoOutcome

    data class Failed(
        val message: String,
    ) : DiscardUndoOutcome
}

/**
 * Pure JVM-testable controller for the phone Discard → Confirm → 8-second Undo flow
 * (Requirement 9.4, tested by `DiscardUndoTest`).
 *
 * Flow:
 * 1. User selects `"Discard changes…"` from a file's long-press sheet → [requestDiscard] opens
 *    [confirmDialog] with [GitChangesView.discardSummary].
 * 2. User confirms → [confirmDiscard] invokes `POST /git/discard` for that single file:
 *    - When the server returns a [DiscardRecord] whose `id` is non-empty and the file is not in
 *      `skipped`, [undoBar] becomes active for 8 000 ms (`8s` down to `1s`) with message
 *      `"Discarded <filename>"`.
 *    - When the file is over 20 MB (`record == null` or `path in record.skipped`), [undoBar] is
 *      not shown and [warningMessage] reports `"Discarded <filename> (too large to keep an undo snapshot)"`.
 * 3. Calling [tick] advances the 8-second timer; when [DiscardUndoBarState.remainingMs] reaches `0`,
 *    [undoBar] closes automatically.
 * 4. Tapping `Undo` within the 8-second window calls [undo], which invokes `POST /git/discard/undo`:
 *    - On HTTP 200, clears [undoBar] and returns [DiscardUndoOutcome.Restored].
 *    - On HTTP 409 (file modified since discard), clears [undoBar], sets [errorMessage] to
 *      `"The file changed since, so Undo would overwrite it"`, and returns [DiscardUndoOutcome.Conflict].
 */
class DiscardUndoController(
    val durationMs: Long = UNDO_DURATION_MS,
) {
    var confirmDialog: DiscardConfirmDialogState? = null
        private set

    var undoBar: DiscardUndoBarState? = null
        private set

    var warningMessage: String? = null
        private set

    var errorMessage: String? = null
        private set

    /** Opens the confirmation step for [change]. */
    fun requestDiscard(change: FileChange): DiscardConfirmDialogState {
        val changedFile = change.source ?: ChangedFile(
            path = change.path,
            oldPath = change.oldPath,
            staged = if (change.group == GitChangeGroup.STAGED || change.partiallyStaged) {
                ChangeSide(added = change.added, removed = change.removed, blocks = listOf("0"))
            } else {
                null
            },
            unstaged = if (change.group == GitChangeGroup.CHANGES || change.partiallyStaged) {
                ChangeSide(added = change.added, removed = change.removed, blocks = listOf("0"))
            } else {
                null
            },
            conflict = change.conflict,
            x = if (change.group == GitChangeGroup.STAGED) change.statusLetter else ".",
            y = if (change.group == GitChangeGroup.CHANGES) change.statusLetter else ".",
            untracked = change.untracked,
        )
        return requestDiscard(changedFile)
    }

    /** Opens the confirmation step for [file]. */
    fun requestDiscard(file: ChangedFile): DiscardConfirmDialogState {
        val files = listOf(file)
        val summary = GitChangesView.discardSummary(files)
        val state = DiscardConfirmDialogState(
            targetPath = file.path,
            files = files,
            summary = summary,
        )
        confirmDialog = state
        return state
    }

    /** Cancels the pending discard confirmation dialog without discarding anything. */
    fun cancelDiscard() {
        confirmDialog = null
    }

    /**
     * Executes the confirmed single-file discard using [performDiscard] (`POST /git/discard`) and
     * starts the 8-second [undoBar] when a snapshot was recorded.
     */
    suspend fun confirmDiscard(
        performDiscard: suspend (files: List<String>) -> DiscardRecord?,
    ): DiscardUndoOutcome {
        val pending = confirmDialog ?: return DiscardUndoOutcome.Failed("No discard pending")
        confirmDialog = null
        warningMessage = null
        errorMessage = null

        val path = pending.targetPath
        val fileName = path.substringAfterLast('/')
        return try {
            val record = performDiscard(listOf(path))
            onDiscardCompleted(path = path, fileName = fileName, record = record)
        } catch (error: Throwable) {
            val msg = error.message ?: "Discard failed"
            errorMessage = msg
            DiscardUndoOutcome.Failed(msg)
        }
    }

    /**
     * Records the result of `POST /git/discard` directly (synchronous helper for callers/tests that
     * already have the [DiscardRecord]).
     */
    fun recordDiscard(path: String, record: DiscardRecord?): DiscardUndoOutcome {
        confirmDialog = null
        warningMessage = null
        errorMessage = null
        val fileName = path.substringAfterLast('/')
        return onDiscardCompleted(path = path, fileName = fileName, record = record)
    }

    private fun onDiscardCompleted(
        path: String,
        fileName: String,
        record: DiscardRecord?,
    ): DiscardUndoOutcome {
        val skipped = record == null || record.id.isBlank() || path in record.skipped ||
            (record.files.isEmpty() && record.skipped.isNotEmpty())
        if (skipped) {
            undoBar = null
            val warning = "Discarded $fileName (too large to keep an undo snapshot)"
            warningMessage = warning
            return DiscardUndoOutcome.SkippedTooLarge(path = path, message = warning)
        }

        val message = "Discarded $fileName"
        undoBar = DiscardUndoBarState(
            recordId = record!!.id,
            path = path,
            message = message,
            remainingMs = durationMs,
            totalDurationMs = durationMs,
        )
        return DiscardUndoOutcome.Discarded(
            recordId = record.id,
            path = path,
            message = message,
        )
    }

    /**
     * Advances the 8-second countdown by [deltaMs]. Returns `true` if the Undo bar expired on this tick.
     */
    fun tick(deltaMs: Long): Boolean {
        val current = undoBar ?: return false
        if (deltaMs <= 0L) return false
        val nextMs = (current.remainingMs - deltaMs).coerceAtLeast(0L)
        if (nextMs == 0L) {
            undoBar = null
            return true
        }
        undoBar = current.copy(remainingMs = nextMs)
        return false
    }

    /** Dismisses the Undo bar immediately without calling `POST /git/discard/undo`. */
    fun dismissUndoBar() {
        undoBar = null
    }

    /** Clears any active warning or error banner. */
    fun clearMessages() {
        warningMessage = null
        errorMessage = null
    }

    /**
     * Invokes `POST /git/discard/undo` for the currently active [undoBar].
     *
     * If the server responds with HTTP 409 (file modified since discard), sets [errorMessage] to
     * [CONFLICT_MESSAGE] (`"The file changed since, so Undo would overwrite it"`) and returns
     * [DiscardUndoOutcome.Conflict].
     */
    suspend fun undo(
        performUndo: suspend (recordId: String) -> Unit,
    ): DiscardUndoOutcome {
        val activeBar = undoBar ?: return DiscardUndoOutcome.Failed("Undo window has expired")
        undoBar = null
        warningMessage = null
        errorMessage = null
        return try {
            performUndo(activeBar.recordId)
            DiscardUndoOutcome.Restored(
                recordId = activeBar.recordId,
                paths = listOf(activeBar.path),
            )
        } catch (error: PpmError.Server) {
            if (error.status == 409) {
                errorMessage = CONFLICT_MESSAGE
                DiscardUndoOutcome.Conflict(
                    recordId = activeBar.recordId,
                    message = CONFLICT_MESSAGE,
                )
            } else {
                val msg = error.serverMessage.ifBlank { "Undo failed" }
                errorMessage = msg
                DiscardUndoOutcome.Failed(msg)
            }
        } catch (error: Throwable) {
            val msg = error.message ?: "Undo failed"
            if (msg.contains("409") || msg.contains("overwrite", ignoreCase = true) ||
                msg.contains("changed since", ignoreCase = true)
            ) {
                errorMessage = CONFLICT_MESSAGE
                DiscardUndoOutcome.Conflict(
                    recordId = activeBar.recordId,
                    message = CONFLICT_MESSAGE,
                )
            } else {
                errorMessage = msg
                DiscardUndoOutcome.Failed(msg)
            }
        }
    }

    companion object {
        const val UNDO_DURATION_MS: Long = 8_000L
        const val UNDO_DURATION_SECONDS: Int = 8
        const val CONFLICT_MESSAGE: String = "The file changed since, so Undo would overwrite it"
    }
}
