package com.hienlh.ppm.state.git

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.GitRepo
import com.hienlh.ppm.client.WRITE_WITH_AI_NOTHING_STAGED_MESSAGE
import com.hienlh.ppm.client.gitCommitMessage
import com.hienlh.ppm.client.isCommitMessageNothingStaged
import com.hienlh.ppm.client.isCommitMessageUnsupported
import kotlinx.coroutines.CancellationException

/**
 * State machine for drafting a git commit message with AI (`Phone 4 · Git & Terminal` SC7 / states,
 * `Tablet 4` SC1–SC3, Requirements 10.1–10.5).
 *
 * States:
 * - [Idle]: ready to generate when at least one file/hunk is staged.
 * - [Writing]: request in flight (`POST /api/project/:name/git/commit-message`); Commit is locked.
 * - [Written]: AI message placed in the commit box, left editable, never auto-committed.
 * - [Failed]: generation failed; preserves [lastMessage] (what the user had typed) and offers Retry.
 */
sealed interface WriteWithAi {
    data object Idle : WriteWithAi
    data object Writing : WriteWithAi
    data class Written(
        val message: String,
        val previousMessage: String = "",
        val stagedCount: Int = 0,
    ) : WriteWithAi
    data class Failed(
        val message: String,
        val lastMessage: String = "",
    ) : WriteWithAi

    val isWriting: Boolean
        get() = this is Writing

    val isWritten: Boolean
        get() = this is Written

    val isFailed: Boolean
        get() = this is Failed

    val errorMessage: String?
        get() = (this as? Failed)?.message

    val canRetry: Boolean
        get() = this is Failed

    /** Requirement 10.2: Commit must stay disabled while the message is being written. */
    fun locksCommit(): Boolean = this is Writing

    /** Status / placeholder text shown while [Writing]. */
    fun writingLabel(stagedCount: Int, tablet: Boolean = false): String = when {
        stagedCount <= 0 -> "Writing commit message…"
        tablet -> "Writing from $stagedCount staged ${if (stagedCount == 1) "file" else "files"}…"
        else -> "Writing a message from $stagedCount staged ${if (stagedCount == 1) "file" else "files"}…"
    }

    /** Hint line shown after [Written] (`Phone 4` states & `Tablet 4` SC3). */
    fun writtenHint(
        stagedCount: Int = (this as? Written)?.stagedCount ?: 0,
        tablet: Boolean = false,
    ): String =
        if (tablet && stagedCount > 0) {
            "Written from $stagedCount staged ${if (stagedCount == 1) "file" else "files"}"
        } else {
            "Written by the AI — edit before committing"
        }

    companion object {
        /** Reason shown when nothing is staged (`design.md` Error Handling, Requirement 10.4). */
        const val STAGE_CHANGES_FIRST_REASON: String = WRITE_WITH_AI_NOTHING_STAGED_MESSAGE

        /**
         * Returns the reason why Write with AI is disabled, or `null` when it can be tapped.
         * When nothing is staged (`stagedCount <= 0`), returns `"Stage changes first"` (Requirement 10.4).
         */
        fun disabledReason(
            stagedCount: Int,
            conflictCount: Int = 0,
            busyAction: String? = null,
        ): String? = when {
            conflictCount > 0 -> "Resolve $conflictCount ${if (conflictCount == 1) "conflict" else "conflicts"} first"
            stagedCount <= 0 -> STAGE_CHANGES_FIRST_REASON
            busyAction != null -> "Wait for $busyAction to finish"
            else -> null
        }

        /**
         * Whether the Write with AI button is enabled (`Requirement 10.2, 10.4`).
         */
        fun isButtonEnabled(
            state: WriteWithAi,
            stagedCount: Int,
            conflictCount: Int = 0,
            busyAction: String? = null,
            endpointSupported: Boolean = true,
        ): Boolean =
            endpointSupported &&
                state !is Writing &&
                busyAction == null &&
                conflictCount == 0 &&
                stagedCount > 0

        /**
         * Whether the Commit action is locked because AI generation or a git operation is running (`Requirement 10.2`).
         */
        fun isCommitLocked(
            state: WriteWithAi,
            busyAction: String? = null,
        ): Boolean = state is Writing || busyAction != null

        /**
         * Whether the user can commit given [state], [stagedCount], [conflictCount], and [message] (`Requirement 10.2, 10.3`).
         */
        fun canCommit(
            state: WriteWithAi,
            stagedCount: Int,
            conflictCount: Int = 0,
            message: String,
            busyAction: String? = null,
        ): Boolean {
            if (isCommitLocked(state, busyAction)) return false
            if (conflictCount > 0) return false
            return stagedCount > 0 && message.trim().isNotEmpty()
        }
    }
}

/**
 * Pure JVM-testable controller driving the [WriteWithAi] state machine, commit draft preservation,
 * Retry, Undo, 409 `"Stage changes first"` lock reason, and 404 endpoint hiding (Requirements 10.1–10.5).
 */
class WriteWithAiController(
    initialMessage: String = "",
    initialStagedCount: Int = 0,
    initialConflictCount: Int = 0,
    initialEndpointSupported: Boolean = true,
) {
    var state: WriteWithAi = WriteWithAi.Idle
        private set

    var commitMessage: String = initialMessage
        private set

    var stagedCount: Int = initialStagedCount
        private set

    var conflictCount: Int = initialConflictCount
        private set

    /**
     * `true` when the server supports `POST /git/commit-message`; becomes `false` when the server
     * answers HTTP 404 (`design.md` Error Handling: hide Write with AI button).
     */
    var endpointSupported: Boolean = initialEndpointSupported
        private set

    private var explicitLockedReason: String? = null
    private var lastGenerator: (suspend () -> String)? = null

    /** Whether the Write with AI button should be rendered (`false` when server returned 404). */
    val isVisible: Boolean
        get() = endpointSupported

    /** Whether the Write with AI button is enabled (`false` when writing, nothing staged, or conflicts). */
    val isEnabled: Boolean
        get() = WriteWithAi.isButtonEnabled(
            state = state,
            stagedCount = stagedCount,
            conflictCount = conflictCount,
            endpointSupported = endpointSupported,
        )

    /** Why the Write with AI button is disabled (`"Stage changes first"` when nothing is staged). */
    val disabledReason: String?
        get() {
            if (!endpointSupported) return null
            return explicitLockedReason ?: WriteWithAi.disabledReason(
                stagedCount = stagedCount,
                conflictCount = conflictCount,
            )
        }

    /** Whether the Commit button is locked (`true` while [WriteWithAi.Writing]). */
    val isCommitLocked: Boolean
        get() = state.locksCommit()

    /** Whether Commit is enabled for the current draft and staged state. */
    val isCommitEnabled: Boolean
        get() = WriteWithAi.canCommit(
            state = state,
            stagedCount = stagedCount,
            conflictCount = conflictCount,
            message = commitMessage,
        )

    /**
     * Update the commit message as the user types (`Requirement 10.3`: written message remains editable).
     * Editing a [WriteWithAi.Written] draft transitions [state] back to [WriteWithAi.Idle].
     */
    fun updateMessage(nextMessage: String) {
        commitMessage = nextMessage
        val current = state
        if (current is WriteWithAi.Written && nextMessage != current.message) {
            state = WriteWithAi.Idle
        }
    }

    /**
     * Update staged and conflict counts from `GitChangeGroups`.
     */
    fun updateStagedCount(count: Int, conflicts: Int = this.conflictCount) {
        stagedCount = count
        conflictCount = conflicts
        if (count > 0 && explicitLockedReason == WriteWithAi.STAGE_CHANGES_FIRST_REASON) {
            explicitLockedReason = null
        }
    }

    /**
     * Mark the endpoint as supported or unsupported (e.g. `false` on HTTP 404).
     */
    fun setEndpointSupported(supported: Boolean) {
        endpointSupported = supported
        if (!supported) {
            state = WriteWithAi.Idle
            explicitLockedReason = null
        }
    }

    /**
     * Transition to [WriteWithAi.Writing] if allowed. Returns `false` and records
     * `"Stage changes first"` when nothing is staged.
     */
    fun startWriting(): Boolean {
        if (!endpointSupported || state is WriteWithAi.Writing) return false
        if (conflictCount > 0) {
            explicitLockedReason = WriteWithAi.disabledReason(stagedCount, conflictCount)
            return false
        }
        if (stagedCount <= 0) {
            explicitLockedReason = WriteWithAi.STAGE_CHANGES_FIRST_REASON
            state = WriteWithAi.Idle
            return false
        }
        explicitLockedReason = null
        state = WriteWithAi.Writing
        return true
    }

    /**
     * Complete generation with [generatedMessage], placing it in [commitMessage] without committing.
     */
    fun onGenerated(generatedMessage: String) {
        val trimmed = generatedMessage.trim()
        if (trimmed.isEmpty()) {
            state = WriteWithAi.Failed(
                message = "The AI returned an empty commit message",
                lastMessage = commitMessage,
            )
            return
        }
        val previous = commitMessage
        commitMessage = trimmed
        explicitLockedReason = null
        state = WriteWithAi.Written(
            message = trimmed,
            previousMessage = previous,
            stagedCount = stagedCount,
        )
    }

    /**
     * Handle a failed generation attempt while keeping whatever the user had typed in [commitMessage].
     * - HTTP 404 (`CommitMessageUnsupported`) hides the button ([endpointSupported] = `false`).
     * - HTTP 409 (`Nothing is staged`) locks the button with `"Stage changes first"`.
     * - HTTP 502 / network errors enter [WriteWithAi.Failed] with Retry.
     */
    fun onFailure(error: Throwable, preservedDraft: String = commitMessage) {
        commitMessage = preservedDraft
        when {
            error.isCommitMessageUnsupported() -> {
                endpointSupported = false
                explicitLockedReason = null
                state = WriteWithAi.Idle
            }
            error.isCommitMessageNothingStaged() -> {
                stagedCount = 0
                explicitLockedReason = WriteWithAi.STAGE_CHANGES_FIRST_REASON
                state = WriteWithAi.Idle
            }
            else -> {
                state = WriteWithAi.Failed(
                    message = error.message?.takeIf { it.isNotBlank() } ?: "Failed to generate commit message",
                    lastMessage = preservedDraft,
                )
            }
        }
    }

    /**
     * Directly transition to [WriteWithAi.Failed] while preserving [commitMessage].
     */
    fun onFailed(errorMessage: String) {
        state = WriteWithAi.Failed(
            message = errorMessage.ifBlank { "Failed to generate commit message" },
            lastMessage = commitMessage,
        )
    }

    /**
     * Run [call] to draft a commit message from the staged diff (`Requirements 10.1–10.5`).
     * Never commits.
     */
    suspend fun generate(call: suspend () -> String): WriteWithAi {
        lastGenerator = call
        if (!endpointSupported) return state
        if (conflictCount > 0) {
            explicitLockedReason = WriteWithAi.disabledReason(stagedCount, conflictCount)
            return state
        }
        if (stagedCount <= 0) {
            explicitLockedReason = WriteWithAi.STAGE_CHANGES_FIRST_REASON
            state = WriteWithAi.Idle
            return state
        }
        val preservedDraft = commitMessage
        explicitLockedReason = null
        state = WriteWithAi.Writing
        return try {
            val generated = call()
            onGenerated(generated)
            state
        } catch (cancelled: CancellationException) {
            commitMessage = preservedDraft
            state = WriteWithAi.Idle
            throw cancelled
        } catch (error: Throwable) {
            onFailure(error, preservedDraft)
            state
        }
    }

    /**
     * Run `POST /api/project/:name/git/commit-message` against [api] (`Requirement 10.1, 10.6`).
     */
    suspend fun generate(
        api: ApiClient,
        project: String,
        repo: GitRepo? = null,
    ): WriteWithAi = generate { api.gitCommitMessage(project, repo) }

    /**
     * Retry after [WriteWithAi.Failed] (`Requirement 10.5`).
     */
    suspend fun retry(call: (suspend () -> String)? = lastGenerator): WriteWithAi {
        val target = call ?: return state
        return generate(target)
    }

    /**
     * Retry against [api] after [WriteWithAi.Failed].
     */
    suspend fun retry(
        api: ApiClient,
        project: String,
        repo: GitRepo? = null,
    ): WriteWithAi = generate(api, project, repo)

    /**
     * Restore the draft that was in the commit box before [WriteWithAi.Written] (`Tablet 4` SC3 Undo).
     */
    fun undoWritten(): Boolean {
        val written = state as? WriteWithAi.Written ?: return false
        commitMessage = written.previousMessage
        state = WriteWithAi.Idle
        return true
    }

    /**
     * Dismiss the [WriteWithAi.Failed] banner while keeping [commitMessage].
     */
    fun dismissError() {
        if (state is WriteWithAi.Failed) {
            state = WriteWithAi.Idle
        }
    }

    /**
     * Cancel an in-flight [WriteWithAi.Writing] state (`Phone 4` / `Tablet 4` Stop).
     */
    fun cancelWriting() {
        if (state is WriteWithAi.Writing) {
            state = WriteWithAi.Idle
        }
    }
}
