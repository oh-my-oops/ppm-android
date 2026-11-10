package com.hienlh.ppm.app.ui.phone.git

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.FileHunks
import com.hienlh.ppm.client.GitBranch
import com.hienlh.ppm.client.GitBranchState
import com.hienlh.ppm.client.GitBranchStatus
import com.hienlh.ppm.client.GitChanges
import com.hienlh.ppm.client.GitRepo
import com.hienlh.ppm.client.GitRepos
import com.hienlh.ppm.client.LastCommit
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.client.gitBranches
import com.hienlh.ppm.client.gitChanges
import com.hienlh.ppm.client.gitCheckout
import com.hienlh.ppm.client.gitCommit
import com.hienlh.ppm.client.gitCommitDraft
import com.hienlh.ppm.client.gitDiscard
import com.hienlh.ppm.client.gitDiscardUndo
import com.hienlh.ppm.client.gitFetch
import com.hienlh.ppm.client.gitHunks
import com.hienlh.ppm.client.gitPublish
import com.hienlh.ppm.client.gitPull
import com.hienlh.ppm.client.gitPush
import com.hienlh.ppm.client.gitRepos
import com.hienlh.ppm.client.gitStage
import com.hienlh.ppm.client.gitUnstage
import com.hienlh.ppm.client.gitUndoCommit
import com.hienlh.ppm.client.putGitCommitDraft
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.LucideIcon
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.kit.AppBar
import com.hienlh.ppm.design.kit.AppBarAction
import com.hienlh.ppm.design.kit.ConfirmDialog
import com.hienlh.ppm.design.kit.KitSpec
import com.hienlh.ppm.design.kit.ListRow
import com.hienlh.ppm.design.kit.RowAction
import com.hienlh.ppm.design.kit.RowActionsSheet
import com.hienlh.ppm.design.kit.SectionLabel
import com.hienlh.ppm.design.kit.Sheet
import com.hienlh.ppm.design.kit.UndoBar
import com.hienlh.ppm.design.kit.minTouchTarget
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.vector
import com.hienlh.ppm.state.git.DiscardUndoController
import com.hienlh.ppm.state.git.DiscardUndoOutcome
import com.hienlh.ppm.state.git.FileChange
import com.hienlh.ppm.state.git.GitChangeGroup
import com.hienlh.ppm.state.git.GitChangeGroups
import com.hienlh.ppm.state.git.GitChangesView
import com.hienlh.ppm.state.git.PhoneFileActionKind
import com.hienlh.ppm.state.git.PhoneGitCapabilities
import com.hienlh.ppm.state.git.WriteWithAi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Details of a failed Sync/Push/Pull/Publish/Fetch action shown in [PhoneSyncErrorSheet] (`SY1`).
 */
data class PhoneSyncFailureState(
    val action: String,
    val title: String,
    val summary: String,
    val gitOutput: String,
)

/**
 * Formats the AppBar subline for phone Source Control (`"main · ↑1 · 3 changed"`, `"main · Clean"`).
 */
fun formatPhoneGitSubline(
    branch: GitBranchState,
    groups: GitChangeGroups,
): String {
    val head = branch.head ?: (branch.oid?.take(7)?.let { "$it (detached)" } ?: "main")
    val parts = mutableListOf(head)
    val aheadBehind = buildList {
        if (branch.ahead > 0) add("↑${branch.ahead}")
        if (branch.behind > 0) add("↓${branch.behind}")
    }.joinToString(" ")
    if (aheadBehind.isNotEmpty()) {
        parts += aheadBehind
    }
    when {
        groups.hasConflicts -> {
            val c = groups.conflictCount
            parts += "$c ${if (c == 1) "conflict" else "conflicts"}"
        }
        groups.totalFiles > 0 -> {
            parts += "${groups.totalFiles} changed"
        }
        else -> {
            parts += "Clean"
        }
    }
    return parts.joinToString(" · ")
}

/**
 * Stateful Phone Source Control screen (`Phone 4 · Git & Terminal`, Sections 1, 2, 7, 8;
 * Requirements 9.1–9.6).
 *
 * Connects to [api] for [projectName] (or operates on [initialChanges] when [api] is null in
 * previews/tests) and renders:
 * - Multi-repo picker (`SC6`) and repo switcher bar when a project has multiple repositories
 * - `Staged` and `Changes` groups via [GitChangeGroups] with partial staging rows in both groups
 * - Pushed [PhoneGitDiffScreen] (`Foundation N17`) when tapping a file row
 * - Whole-file staging/unstaging via 48 dp checkbox tap or long-press sheet
 * - Single-file Discard with confirmation dialog and 8-second [UndoBar] (`POST /git/discard/undo`)
 * - [PhoneBranchSheet] (`BR3`) with local-only radio rows and in-sheet red checkout error banner
 * - [PhoneSyncErrorSheet] (`SY1`) with full git output, Copy, and Retry
 * - [CommitBox] at the bottom with growing message field, `✦ AI` chip slot, 5-mode Sync chip, and
 *   plain `Commit` button.
 */
@Composable
fun PhoneSourceControlScreen(
    projectName: String,
    api: ApiClient?,
    modifier: Modifier = Modifier,
    initialStatus: GitBranchStatus? = null,
    initialChanges: GitChanges? = null,
    initialBranches: List<GitBranch> = emptyList(),
    showAppBar: Boolean = false,
    aiAvailable: Boolean = true,
    onWriteWithAi: (suspend (GitRepo?) -> String?)? = null,
    onOpenFile: ((String) -> Unit)? = null,
    onDrawerClick: (() -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    onStatusChanged: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var reloadTick by remember { mutableIntStateOf(0) }

    var reposState by remember(projectName) { mutableStateOf<GitRepos?>(null) }
    var selectedRepo by remember(projectName) { mutableStateOf<GitRepo?>(null) }
    var loading by remember(projectName) { mutableStateOf(api != null && initialChanges == null) }
    var changes by remember(projectName, initialChanges) { mutableStateOf(initialChanges) }
    var branches by remember(projectName, initialBranches) { mutableStateOf(initialBranches) }
    var commitMessage by remember(projectName) { mutableStateOf("") }
    var draftLoaded by remember(projectName) { mutableStateOf(false) }

    var busyAction by remember { mutableStateOf<String?>(null) }
    var bannerError by remember { mutableStateOf<String?>(null) }
    var bannerInfo by remember { mutableStateOf<String?>(null) }

    var branchSheetOpen by remember { mutableStateOf(false) }
    var checkoutError by remember { mutableStateOf<String?>(null) }
    var syncFailure by remember { mutableStateOf<PhoneSyncFailureState?>(null) }

    var activeDiffChange by remember { mutableStateOf<FileChange?>(null) }
    var activeDiffHunks by remember { mutableStateOf<FileHunks?>(null) }
    var diffLoading by remember { mutableStateOf(false) }
    var diffError by remember { mutableStateOf<String?>(null) }

    var aiState by remember { mutableStateOf<WriteWithAi>(WriteWithAi.Idle) }

    val discardController = remember(projectName) { DiscardUndoController() }
    var discardUiTick by remember { mutableIntStateOf(0) }

    // 100 ms ticker driving the 8-second Discard Undo bar countdown
    val activeUndoBar = remember(discardUiTick, discardController.undoBar) { discardController.undoBar }
    LaunchedEffect(activeUndoBar?.recordId, activeUndoBar?.remainingMs) {
        val bar = activeUndoBar
        if (bar != null && bar.active) {
            val step = minOf(100L, bar.remainingMs)
            delay(step)
            discardController.tick(step)
            discardUiTick++
        }
    }

    val resolvedRepo: GitRepo? = selectedRepo ?: reposState?.resolved

    suspend fun refreshGitState(targetRepo: GitRepo? = resolvedRepo) {
        val client = api ?: return
        try {
            val loadedRepos = reposState ?: client.gitRepos(projectName).also { reposState = it }
            val effectiveRepo = targetRepo ?: selectedRepo ?: loadedRepos.resolved
            if (effectiveRepo == null && loadedRepos.repos.size > 1 && !loadedRepos.rootIsRepo) {
                loading = false
                return
            }
            if (loadedRepos.repos.isEmpty() && !loadedRepos.rootIsRepo) {
                loading = false
                return
            }
            val nextChanges = client.gitChanges(projectName, effectiveRepo)
            changes = nextChanges
            if (!draftLoaded) {
                draftLoaded = true
                runCatching { client.gitCommitDraft(projectName, effectiveRepo) }
                    .getOrNull()
                    ?.let { draft ->
                        if (commitMessage.isEmpty() && draft.message.isNotEmpty()) {
                            commitMessage = draft.message
                        }
                    }
            }
            runCatching { client.gitBranches(projectName, effectiveRepo) }
                .getOrNull()
                ?.let { branches = it }
            loading = false
        } catch (error: Throwable) {
            loading = false
            bannerError = error.message ?: "Couldn't load Source Control state."
        }
    }

    LaunchedEffect(projectName, api, selectedRepo?.path, reloadTick) {
        if (api != null) {
            refreshGitState(selectedRepo ?: reposState?.resolved)
        }
    }

    // Load diff hunks whenever a file row is opened in PhoneGitDiffScreen
    LaunchedEffect(activeDiffChange?.path, activeDiffChange?.group, resolvedRepo?.path) {
        val target = activeDiffChange ?: return@LaunchedEffect
        val client = api ?: return@LaunchedEffect
        diffLoading = true
        diffError = null
        try {
            activeDiffHunks = client.gitHunks(
                project = projectName,
                path = target.path,
                scope = target.diffScope,
                repo = resolvedRepo,
            )
            diffLoading = false
        } catch (error: Throwable) {
            diffLoading = false
            diffError = error.message ?: "Failed to load diff"
        }
    }

    val groups = remember(changes, initialStatus) {
        when {
            changes != null -> GitChangeGroups.from(changes!!)
            initialStatus != null -> GitChangeGroups.from(initialStatus)
            else -> GitChangeGroups(staged = emptyList(), changes = emptyList())
        }
    }

    val branchState = remember(changes, initialStatus) {
        changes?.branch ?: GitBranchState(
            head = initialStatus?.branch ?: "main",
            oid = null,
            upstream = initialStatus?.tracking,
            upstreamGone = false,
            ahead = initialStatus?.ahead ?: 0,
            behind = initialStatus?.behind ?: 0,
            hasRemote = true,
        )
    }

    fun handleToggleFileStage(change: FileChange, closeDiffOnSuccess: Boolean = false) {
        if (!change.canToggleStage) return
        val client = api
        if (client == null) {
            val current = changes ?: return
            val nextGroups = groups.toggleFile(change)
            val updatedFiles = (nextGroups.staged.mapNotNull { it.source } + nextGroups.changes.mapNotNull { it.source })
                .distinctBy { it.path }
            changes = current.copy(files = updatedFiles)
            if (closeDiffOnSuccess) activeDiffChange = null
            return
        }
        scope.launch {
            busyAction = "stage"
            bannerError = null
            try {
                if (change.group == GitChangeGroup.STAGED) {
                    client.gitUnstage(projectName, change.unstagePaths, resolvedRepo)
                } else {
                    client.gitStage(projectName, change.stagePaths, resolvedRepo)
                }
                refreshGitState()
                onStatusChanged()
                if (closeDiffOnSuccess) {
                    activeDiffChange = null
                } else if (activeDiffChange?.path == change.path) {
                    val flippedGroup = if (change.group == GitChangeGroup.STAGED) {
                        GitChangeGroup.CHANGES
                    } else {
                        GitChangeGroup.STAGED
                    }
                    activeDiffChange = change.copy(
                        group = flippedGroup,
                        partiallyStaged = false,
                    )
                }
            } catch (error: Throwable) {
                bannerError = error.message ?: "Failed to update file stage state"
            } finally {
                busyAction = null
            }
        }
    }

    fun handleStageAll() {
        val paths = groups.changes.filter { !it.conflict }.map { it.path }.distinct()
        if (paths.isEmpty()) return
        val client = api ?: return
        scope.launch {
            busyAction = "stage"
            bannerError = null
            try {
                client.gitStage(projectName, paths, resolvedRepo)
                refreshGitState()
                onStatusChanged()
            } catch (error: Throwable) {
                bannerError = error.message ?: "Failed to stage files"
            } finally {
                busyAction = null
            }
        }
    }

    fun handleUnstageAll() {
        val paths = groups.staged.flatMap { it.unstagePaths }.distinct()
        if (paths.isEmpty()) return
        val client = api ?: return
        scope.launch {
            busyAction = "unstage"
            bannerError = null
            try {
                client.gitUnstage(projectName, paths, resolvedRepo)
                refreshGitState()
                onStatusChanged()
            } catch (error: Throwable) {
                bannerError = error.message ?: "Failed to unstage files"
            } finally {
                busyAction = null
            }
        }
    }

    fun handleConfirmDiscard() {
        val client = api
        scope.launch {
            busyAction = "discard"
            bannerError = null
            val outcome = discardController.confirmDiscard { files ->
                if (client != null) {
                    client.gitDiscard(projectName, files, resolvedRepo)
                } else {
                    com.hienlh.ppm.client.DiscardRecord(
                        id = "local-discard-1",
                        createdAt = System.currentTimeMillis(),
                        files = files,
                        skipped = emptyList(),
                    )
                }
            }
            discardUiTick++
            when (outcome) {
                is DiscardUndoOutcome.Discarded -> {
                    if (client != null) {
                        refreshGitState()
                        onStatusChanged()
                    }
                }
                is DiscardUndoOutcome.SkippedTooLarge -> {
                    bannerInfo = outcome.message
                    if (client != null) {
                        refreshGitState()
                        onStatusChanged()
                    }
                }
                is DiscardUndoOutcome.Failed -> {
                    bannerError = outcome.message
                }
                else -> Unit
            }
            busyAction = null
        }
    }

    fun handleUndoDiscard() {
        val client = api
        scope.launch {
            busyAction = "undo-discard"
            bannerError = null
            val outcome = discardController.undo { recordId ->
                client?.gitDiscardUndo(projectName, recordId, resolvedRepo)
            }
            discardUiTick++
            when (outcome) {
                is DiscardUndoOutcome.Restored -> {
                    bannerInfo = "Restored ${outcome.paths.firstOrNull()?.substringAfterLast('/') ?: "file"}"
                    if (client != null) {
                        refreshGitState()
                        onStatusChanged()
                    }
                }
                is DiscardUndoOutcome.Conflict -> {
                    bannerError = outcome.message
                }
                is DiscardUndoOutcome.Failed -> {
                    bannerError = outcome.message
                }
                else -> Unit
            }
            busyAction = null
        }
    }

    fun handleCommit() {
        val trimmed = commitMessage.trim()
        if (trimmed.isEmpty() || groups.stagedCount == 0 || groups.hasConflicts) return
        val client = api ?: return
        scope.launch {
            busyAction = "commit"
            bannerError = null
            try {
                client.gitCommit(projectName, trimmed, repo = resolvedRepo)
                commitMessage = ""
                runCatching { client.putGitCommitDraft(projectName, "", repo = resolvedRepo) }
                refreshGitState()
                onStatusChanged()
            } catch (error: Throwable) {
                bannerError = error.message ?: "Commit failed"
            } finally {
                busyAction = null
            }
        }
    }

    fun handleUndoLastCommit(commit: LastCommit) {
        if (!commit.hasParent) return
        val client = api ?: return
        scope.launch {
            busyAction = "undo-commit"
            bannerError = null
            try {
                client.gitUndoCommit(projectName, commit.hash, resolvedRepo)
                if (commitMessage.isBlank() && commit.subject.isNotBlank()) {
                    commitMessage = commit.subject
                }
                refreshGitState()
                onStatusChanged()
            } catch (error: Throwable) {
                bannerError = error.message ?: "Failed to undo commit"
            } finally {
                busyAction = null
            }
        }
    }

    fun handleSyncAction(action: String) {
        val client = api ?: return
        scope.launch {
            busyAction = action
            bannerError = null
            try {
                when (action) {
                    "publish" -> client.gitPublish(projectName, resolvedRepo)
                    "pull" -> client.gitPull(projectName, rebase = false, repo = resolvedRepo)
                    "push" -> client.gitPush(projectName, repo = resolvedRepo)
                    "sync" -> {
                        if (branchState.behind > 0) {
                            client.gitPull(projectName, rebase = false, repo = resolvedRepo)
                        }
                        if (branchState.ahead > 0) {
                            client.gitPush(projectName, repo = resolvedRepo)
                        }
                    }
                    "fetch" -> client.gitFetch(projectName, prune = true, repo = resolvedRepo)
                }
                syncFailure = null
                refreshGitState()
                onStatusChanged()
            } catch (error: PpmError.Server) {
                val title = when (action) {
                    "push" -> "Push failed"
                    "pull" -> "Pull failed"
                    "publish" -> "Publish failed"
                    "fetch" -> "Fetch failed"
                    else -> "Sync failed"
                }
                syncFailure = PhoneSyncFailureState(
                    action = action,
                    title = title,
                    summary = error.serverMessage,
                    gitOutput = error.hint ?: error.serverMessage,
                )
            } catch (error: Throwable) {
                val msg = error.message ?: "Sync failed"
                syncFailure = PhoneSyncFailureState(
                    action = action,
                    title = "Sync failed",
                    summary = msg,
                    gitOutput = msg,
                )
            } finally {
                busyAction = null
            }
        }
    }

    fun handleCheckoutBranch(branchName: String) {
        val client = api ?: return
        scope.launch {
            busyAction = "checkout"
            checkoutError = null
            try {
                client.gitCheckout(projectName, branchName, repo = resolvedRepo)
                branchSheetOpen = false
                refreshGitState()
                onStatusChanged()
            } catch (error: PpmError.Server) {
                // Keep Branch sheet open and show red error banner in place (BR3, Note N25)
                checkoutError = error.hint?.let { "${error.serverMessage}\n$it" } ?: error.serverMessage
            } catch (error: Throwable) {
                checkoutError = error.message ?: "git checkout refused"
            } finally {
                busyAction = null
            }
        }
    }

    fun handleWriteWithAi() {
        val generator = onWriteWithAi
        if (generator == null) {
            aiState = WriteWithAi.Failed("AI commit message generator is not configured.", commitMessage)
            return
        }
        scope.launch {
            aiState = WriteWithAi.Writing
            try {
                val generated = generator(resolvedRepo)
                if (!generated.isNullOrBlank()) {
                    commitMessage = generated.trim()
                    aiState = WriteWithAi.Written(commitMessage)
                } else {
                    aiState = WriteWithAi.Idle
                }
            } catch (error: Throwable) {
                aiState = WriteWithAi.Failed(error.message ?: "Failed to generate commit message", commitMessage)
            }
        }
    }

    // Pushed file diff page (`Foundation N17`, Requirement 9.2)
    val diffTarget = activeDiffChange
    if (diffTarget != null) {
        PhoneGitDiffScreen(
            change = diffTarget,
            fileHunks = activeDiffHunks,
            loading = diffLoading,
            errorMessage = diffError,
            busyStaging = busyAction == "stage" || busyAction == "unstage",
            onBack = {
                activeDiffChange = null
                activeDiffHunks = null
                diffError = null
            },
            onToggleWholeFileStage = { target ->
                handleToggleFileStage(target, closeDiffOnSuccess = true)
            },
            onOpenFile = onOpenFile,
            modifier = modifier,
        )
        return
    }

    PhoneSourceControlContent(
        groups = groups,
        branchState = branchState,
        operation = changes?.operation,
        lastCommit = changes?.lastCommit,
        repos = reposState,
        selectedRepo = resolvedRepo,
        branches = branches,
        loading = loading,
        busyAction = busyAction,
        commitMessage = commitMessage,
        onCommitMessageChange = { next ->
            commitMessage = next
        },
        aiAvailable = aiAvailable,
        aiLoading = aiState is WriteWithAi.Writing,
        aiErrorMessage = (aiState as? WriteWithAi.Failed)?.message,
        onDismissAiError = { aiState = WriteWithAi.Idle },
        onWriteWithAiClick = ::handleWriteWithAi,
        bannerError = bannerError ?: discardController.errorMessage,
        bannerInfo = bannerInfo ?: discardController.warningMessage,
        onDismissBanner = {
            bannerError = null
            bannerInfo = null
            discardController.clearMessages()
            discardUiTick++
        },
        showAppBar = showAppBar,
        onDrawerClick = onDrawerClick,
        onTitleClick = onTitleClick,
        onSelectRepo = { repo ->
            selectedRepo = repo
            draftLoaded = false
            reloadTick++
        },
        onRescanRepos = {
            reposState = null
            reloadTick++
        },
        onOpenBranchSheet = {
            checkoutError = null
            branchSheetOpen = true
        },
        onRefresh = {
            handleSyncAction("fetch")
        },
        onOpenDiff = { change ->
            activeDiffChange = change
        },
        onOpenFile = onOpenFile,
        onToggleFileStage = { change ->
            handleToggleFileStage(change, closeDiffOnSuccess = false)
        },
        onStageAll = ::handleStageAll,
        onUnstageAll = ::handleUnstageAll,
        onRequestDiscard = { change ->
            discardController.requestDiscard(change)
            discardUiTick++
        },
        confirmDiscardDialog = remember(discardUiTick, discardController.confirmDialog) {
            discardController.confirmDialog
        },
        onConfirmDiscard = ::handleConfirmDiscard,
        onCancelDiscard = {
            discardController.cancelDiscard()
            discardUiTick++
        },
        undoBarState = activeUndoBar,
        onUndoDiscard = ::handleUndoDiscard,
        onCommitClick = ::handleCommit,
        onUndoLastCommit = ::handleUndoLastCommit,
        onSyncClick = ::handleSyncAction,
        branchSheetOpen = branchSheetOpen,
        checkoutError = checkoutError,
        onSelectBranch = ::handleCheckoutBranch,
        onDismissBranchSheet = {
            branchSheetOpen = false
            checkoutError = null
        },
        syncFailure = syncFailure,
        onRetrySyncFailure = {
            val failed = syncFailure ?: return@PhoneSourceControlContent
            handleSyncAction(failed.action)
        },
        onDismissSyncFailure = { syncFailure = null },
        modifier = modifier,
    )
}

/**
 * Stateless Phone Source Control screen content (`SC1`–`SC8`, `BR3`, `SY1`, `CF2`), usable directly
 * in unit/UI tests and previews without an [ApiClient].
 */
@Composable
fun PhoneSourceControlContent(
    groups: GitChangeGroups,
    branchState: GitBranchState,
    modifier: Modifier = Modifier,
    operation: com.hienlh.ppm.client.GitOperation? = null,
    lastCommit: LastCommit? = null,
    repos: GitRepos? = null,
    selectedRepo: GitRepo? = null,
    branches: List<GitBranch> = emptyList(),
    loading: Boolean = false,
    busyAction: String? = null,
    commitMessage: String = "",
    onCommitMessageChange: (String) -> Unit = {},
    aiAvailable: Boolean = true,
    aiLoading: Boolean = false,
    aiErrorMessage: String? = null,
    onDismissAiError: () -> Unit = {},
    onWriteWithAiClick: () -> Unit = {},
    bannerError: String? = null,
    bannerInfo: String? = null,
    onDismissBanner: () -> Unit = {},
    showAppBar: Boolean = false,
    onDrawerClick: (() -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    onSelectRepo: (GitRepo) -> Unit = {},
    onRescanRepos: () -> Unit = {},
    onOpenBranchSheet: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onOpenDiff: (FileChange) -> Unit = {},
    onOpenFile: ((String) -> Unit)? = null,
    onToggleFileStage: (FileChange) -> Unit = {},
    onStageAll: () -> Unit = {},
    onUnstageAll: () -> Unit = {},
    onRequestDiscard: (FileChange) -> Unit = {},
    confirmDiscardDialog: com.hienlh.ppm.state.git.DiscardConfirmDialogState? = null,
    onConfirmDiscard: () -> Unit = {},
    onCancelDiscard: () -> Unit = {},
    undoBarState: com.hienlh.ppm.state.git.DiscardUndoBarState? = null,
    onUndoDiscard: () -> Unit = {},
    onCommitClick: () -> Unit = {},
    onUndoLastCommit: (LastCommit) -> Unit = {},
    onSyncClick: (String) -> Unit = {},
    branchSheetOpen: Boolean = false,
    checkoutError: String? = null,
    onSelectBranch: (String) -> Unit = {},
    onDismissBranchSheet: () -> Unit = {},
    syncFailure: PhoneSyncFailureState? = null,
    onRetrySyncFailure: () -> Unit = {},
    onDismissSyncFailure: () -> Unit = {},
) {
    val tokens = LocalPpmTokens.current
    var repoPickerSheetOpen by remember { mutableStateOf(false) }
    var actionSheetTarget by remember { mutableStateOf<FileChange?>(null) }

    val subline = remember(branchState, groups) {
        formatPhoneGitSubline(branchState, groups)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.panel2.color)
            .testTag("phone-source-control-screen"),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (showAppBar) {
                AppBar(
                    title = "Source Control",
                    subline = subline,
                    sublineMono = true,
                    onDrawerClick = onDrawerClick,
                    onTitleClick = onTitleClick,
                    actions = listOf(
                        AppBarAction(
                            icon = ProductIcon.GitBranch,
                            label = "Switch branch",
                            onClick = onOpenBranchSheet,
                        ),
                        AppBarAction(
                            icon = ProductIcon.RefreshCw,
                            label = "Fetch and refresh",
                            onClick = onRefresh,
                        ),
                    ),
                )
            } else {
                // Compact branch & fetch header bar when PhoneShell renders the top AppBar
                PhoneBranchHeaderBar(
                    branchState = branchState,
                    groups = groups,
                    busyAction = busyAction,
                    onOpenBranchSheet = onOpenBranchSheet,
                    onRefresh = onRefresh,
                )
            }

            // Multi-repo switcher bar when the project contains 2+ repositories and one is selected
            if (repos != null && repos.repos.size > 1 && selectedRepo != null) {
                PhoneRepoSwitcherStrip(
                    selectedRepo = selectedRepo,
                    repoCount = repos.repos.size,
                    onClick = { repoPickerSheetOpen = true },
                )
            }

            when {
                // Multi-repo project with no repository chosen yet (`SC6`)
                repos != null && repos.repos.size > 1 && !repos.rootIsRepo && selectedRepo == null -> {
                    PhoneMultiRepoPicker(
                        repos = repos.repos,
                        onSelectRepo = onSelectRepo,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Project folder has no git repositories at all (`SC1`)
                repos != null && repos.repos.isEmpty() && !repos.rootIsRepo -> {
                    PhoneNoGitRepoState(
                        onRescan = onRescanRepos,
                        modifier = Modifier.weight(1f),
                    )
                }
                loading -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .testTag("phone-git-loading"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PpmIcon(
                                icon = ProductIcon.Loader2,
                                size = 18.dp,
                                tint = tokens.text2.color,
                                spinning = true,
                            )
                            BasicText(
                                text = "Loading changes…",
                                style = ppmText(13.5f, FontWeight.Normal, lineHeight = 18f)
                                    .copy(color = tokens.text2.color),
                            )
                        }
                    }
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("phone-git-list"),
                            contentPadding = PaddingValues(bottom = if (undoBarState?.active == true) 80.dp else 16.dp),
                        ) {
                            // Conflict pause warning banner (`CF2`, Requirement 9.6, Note N28)
                            if (groups.hasConflicts || operation != null) {
                                item(key = "conflict-banner") {
                                    PhoneConflictPauseBanner(
                                        conflictCount = groups.conflictCount,
                                        operation = operation,
                                    )
                                }
                            }

                            // Error / info banner (e.g., 409 Undo conflict or >20 MB skipped snapshot)
                            if (!bannerError.isNullOrEmpty() || !bannerInfo.isNullOrEmpty()) {
                                item(key = "status-banner") {
                                    val isErr = !bannerError.isNullOrEmpty()
                                    val text = bannerError ?: bannerInfo.orEmpty()
                                    val tone = if (isErr) tokens.error else tokens.warning
                                    val shape = RoundedCornerShape(12.dp)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 8.dp)
                                            .clip(shape)
                                            .background(tone.opacity(0.12).color, shape)
                                            .cssBorder(tone.opacity(0.35).color, shape, width = 1.dp)
                                            .ppmClickable(
                                                shape = shape,
                                                onClickLabel = "Dismiss message",
                                                onClick = onDismissBanner,
                                            )
                                            .padding(horizontal = 12.dp, vertical = 10.dp)
                                            .testTag("phone-git-banner"),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        PpmIcon(
                                            icon = if (isErr) ProductIcon.AlertCircle else ProductIcon.AlertTriangle,
                                            size = 18.dp,
                                            tint = tone.color,
                                        )
                                        BasicText(
                                            text = text,
                                            style = ppmText(13f, FontWeight.Medium, lineHeight = 18f)
                                                .copy(color = tone.color),
                                            modifier = Modifier.weight(1f),
                                        )
                                        PpmIcon(
                                            icon = ProductIcon.X,
                                            size = 16.dp,
                                            tint = tone.color,
                                        )
                                    }
                                }
                            }

                            // STAGED group (`Requirement 9.1`)
                            if (groups.staged.isNotEmpty()) {
                                item(key = "header-staged") {
                                    SectionLabel(
                                        text = "STAGED (${groups.stagedCount})",
                                        modifier = Modifier.testTag("phone-git-section-staged"),
                                        trailing = {
                                            val pillShape = RoundedCornerShape(8.dp)
                                            Box(
                                                modifier = Modifier
                                                    .clip(pillShape)
                                                    .ppmClickable(
                                                        enabled = busyAction == null,
                                                        shape = pillShape,
                                                        onClickLabel = "Unstage all",
                                                        onClick = onUnstageAll,
                                                    )
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                                    .testTag("phone-git-unstage-all"),
                                            ) {
                                                BasicText(
                                                    text = "Unstage all",
                                                    style = ppmText(11.5f, FontWeight.SemiBold, lineHeight = 14f)
                                                        .copy(color = tokens.accent.color),
                                                )
                                            }
                                        },
                                    )
                                }
                                items(
                                    count = groups.staged.size,
                                    key = { idx -> "staged-${groups.staged[idx].path}" },
                                ) { idx ->
                                    val item = groups.staged[idx]
                                    PhoneGitChangeRow(
                                        change = item,
                                        onRowClick = { onOpenDiff(item) },
                                        onToggleCheckbox = { onToggleFileStage(item) },
                                        onOpenActionSheet = { actionSheetTarget = item },
                                    )
                                }
                            }

                            // CHANGES group (`Requirement 9.1`)
                            if (groups.changes.isNotEmpty()) {
                                item(key = "header-changes") {
                                    val canStageAll = groups.changes.any { !it.conflict }
                                    SectionLabel(
                                        text = "CHANGES (${groups.changesCount})",
                                        modifier = Modifier.testTag("phone-git-section-changes"),
                                        trailing = if (canStageAll) {
                                            {
                                                val pillShape = RoundedCornerShape(8.dp)
                                                Box(
                                                    modifier = Modifier
                                                        .clip(pillShape)
                                                        .ppmClickable(
                                                            enabled = busyAction == null,
                                                            shape = pillShape,
                                                            onClickLabel = "Stage all",
                                                            onClick = onStageAll,
                                                        )
                                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                                        .testTag("phone-git-stage-all"),
                                                ) {
                                                    BasicText(
                                                        text = "Stage all",
                                                        style = ppmText(11.5f, FontWeight.SemiBold, lineHeight = 14f)
                                                            .copy(color = tokens.accent.color),
                                                    )
                                                }
                                            }
                                        } else {
                                            null
                                        },
                                    )
                                }
                                items(
                                    count = groups.changes.size,
                                    key = { idx -> "changes-${groups.changes[idx].path}" },
                                ) { idx ->
                                    val item = groups.changes[idx]
                                    PhoneGitChangeRow(
                                        change = item,
                                        onRowClick = { onOpenDiff(item) },
                                        onToggleCheckbox = { onToggleFileStage(item) },
                                        onOpenActionSheet = { actionSheetTarget = item },
                                    )
                                }
                            }

                            // Clean working tree empty state (`SC5`)
                            if (groups.isEmpty) {
                                item(key = "clean-tree") {
                                    PhoneCleanWorkingTreeState(
                                        branchState = branchState,
                                    )
                                }
                            }

                            // NOT PUSHED section (`SC2`)
                            if (lastCommit != null && !lastCommit.pushed) {
                                val unpushedCount = branchState.ahead.coerceAtLeast(1)
                                item(key = "header-not-pushed") {
                                    SectionLabel(
                                        text = "NOT PUSHED ($unpushedCount)",
                                        modifier = Modifier.testTag("phone-git-section-not-pushed"),
                                    )
                                }
                                item(key = "not-pushed-commit-${lastCommit.hash}") {
                                    PhoneUnpushedCommitRow(
                                        commit = lastCommit,
                                        busy = busyAction != null,
                                        onUndoCommit = { onUndoLastCommit(lastCommit) },
                                    )
                                }
                            }
                        }

                        // Floating 8-second UndoBar above CommitBox (`SC4`, Requirement 9.4)
                        if (undoBarState != null && undoBarState.active) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                    .testTag("phone-git-undo-bar"),
                            ) {
                                UndoBar(
                                    message = undoBarState.message,
                                    actionLabel = "Undo",
                                    remainingSeconds = undoBarState.remainingSeconds,
                                    onAction = onUndoDiscard,
                                )
                            }
                        }
                    }

                    // Bottom CommitBox (`SC2`, `SC5`, `SC7`, `CF2`)
                    CommitBox(
                        message = commitMessage,
                        onMessageChange = onCommitMessageChange,
                        stagedCount = groups.stagedCount,
                        totalChangedFiles = groups.totalFiles,
                        conflictCount = groups.conflictCount,
                        branchState = branchState,
                        operation = operation,
                        busyAction = busyAction,
                        aiAvailable = aiAvailable,
                        aiLoading = aiLoading,
                        aiErrorMessage = aiErrorMessage,
                        onWriteWithAiClick = onWriteWithAiClick,
                        onDismissAiError = onDismissAiError,
                        onSyncClick = onSyncClick,
                        onCommitClick = onCommitClick,
                    )
                }
            }
        }

        // File long-press contextual sheet (`SC8`, Requirements 9.2, 9.3, 9.4, 9.6)
        val sheetChange = actionSheetTarget
        if (sheetChange != null) {
            val rowActions = remember(sheetChange, onOpenFile) {
                buildPhoneFileRowActions(
                    change = sheetChange,
                    onOpenDiff = { onOpenDiff(sheetChange) },
                    onOpenFile = onOpenFile?.let { open -> { open(sheetChange.path) } },
                    onToggleStage = { onToggleFileStage(sheetChange) },
                    onDiscard = { onRequestDiscard(sheetChange) },
                )
            }
            RowActionsSheet(
                title = sheetChange.fileName,
                subtitle = sheetChange.subline,
                actions = rowActions,
                onDismiss = { actionSheetTarget = null },
            )
        }

        // Discard confirmation dialog (`ConfirmDialog`, Requirement 9.4)
        if (confirmDiscardDialog != null) {
            ConfirmDialog(
                title = confirmDiscardDialog.title,
                message = confirmDiscardDialog.body,
                confirmLabel = confirmDiscardDialog.confirmLabel,
                destructive = true,
                onConfirm = onConfirmDiscard,
                onDismiss = onCancelDiscard,
            )
        }

        // Branch switcher sheet (`BR3`, Requirement 9.5)
        if (branchSheetOpen) {
            PhoneBranchSheet(
                branches = branches,
                branchState = branchState,
                checkoutError = checkoutError,
                busyAction = busyAction,
                hasConflicts = groups.hasConflicts,
                onSelectBranch = onSelectBranch,
                onPull = {
                    onDismissBranchSheet()
                    onSyncClick("pull")
                },
                onPush = {
                    onDismissBranchSheet()
                    onSyncClick("push")
                },
                onDismiss = onDismissBranchSheet,
            )
        }

        // Sync error sheet (`SY1`, Requirement 9.5)
        if (syncFailure != null) {
            PhoneSyncErrorSheet(
                title = syncFailure.title,
                summary = syncFailure.summary,
                gitOutput = syncFailure.gitOutput,
                retrying = busyAction != null,
                onRetry = onRetrySyncFailure,
                onDismiss = onDismissSyncFailure,
            )
        }

        // Multi-repo switcher sheet
        if (repoPickerSheetOpen && repos != null) {
            Sheet(
                title = "Switch repository",
                subtitle = "${repos.repos.size} repositories in project",
                onDismiss = { repoPickerSheetOpen = false },
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    for (repo in repos.repos) {
                        val isCurrent = repo.path == selectedRepo?.path
                        ListRow(
                            title = repo.name,
                            subtitle = repo.relative,
                            subtitleMono = true,
                            icon = LucideIcon.FolderGit2.vector,
                            selected = isCurrent,
                            onClick = {
                                repoPickerSheetOpen = false
                                onSelectRepo(repo)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Builds the contextual [RowAction] list for a single [FileChange] row on phone (`SC8`).
 * Enforces Requirement 9.6 (whole-file actions only).
 */
internal fun buildPhoneFileRowActions(
    change: FileChange,
    onOpenDiff: () -> Unit,
    onOpenFile: (() -> Unit)?,
    onToggleStage: () -> Unit,
    onDiscard: () -> Unit,
): List<RowAction> {
    val allowedKinds = PhoneGitCapabilities.actionsFor(change)
    return buildList {
        for (kind in allowedKinds) {
            when (kind) {
                PhoneFileActionKind.OPEN_DIFF -> add(
                    RowAction(
                        id = "open-diff",
                        label = "Open diff",
                        icon = LucideIcon.FileDiff,
                        onClick = onOpenDiff,
                    ),
                )
                PhoneFileActionKind.OPEN_FILE -> if (onOpenFile != null) {
                    add(
                        RowAction(
                            id = "open-file",
                            label = "Open file",
                            icon = ProductIcon.FileCode,
                            onClick = onOpenFile,
                        ),
                    )
                }
                PhoneFileActionKind.STAGE_FILE -> add(
                    RowAction(
                        id = "stage-file",
                        label = "Stage file",
                        icon = ProductIcon.Plus,
                        onClick = onToggleStage,
                    ),
                )
                PhoneFileActionKind.UNSTAGE_FILE -> add(
                    RowAction(
                        id = "unstage-file",
                        label = "Unstage file",
                        icon = ProductIcon.Minus,
                        onClick = onToggleStage,
                    ),
                )
                PhoneFileActionKind.DISCARD_CHANGES -> add(
                    RowAction(
                        id = "discard-changes",
                        label = "Discard changes…",
                        icon = ProductIcon.RotateCcw,
                        destructive = true,
                        onClick = onDiscard,
                    ),
                )
            }
        }
    }
}

/**
 * Compact header bar showing the current branch pill (`main ▾`), ahead/behind status, and a 48 dp
 * Fetch/Refresh button when `PhoneShell` renders the outer AppBar.
 */
@Composable
private fun PhoneBranchHeaderBar(
    branchState: GitBranchState,
    groups: GitChangeGroups,
    busyAction: String?,
    onOpenBranchSheet: () -> Unit,
    onRefresh: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val branchLabel = branchState.head ?: (branchState.oid?.take(7)?.let { "$it (detached)" } ?: "main")
    val pillShape = RoundedCornerShape(999.dp)
    val slotShape = RoundedCornerShape(12.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(tokens.panel.color)
            .cssBorder(tokens.borderSoft.color, width = 1.dp, sides = CssSides.Bottom)
            .padding(start = 12.dp, end = 4.dp)
            .testTag("phone-git-branch-bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .minTouchTarget(minHeight = 40.dp)
                .clip(pillShape)
                .background(tokens.panel2.color, pillShape)
                .cssBorder(tokens.border.color, pillShape, width = 1.dp)
                .ppmClickable(
                    shape = pillShape,
                    hoverColor = tokens.surfaceHover.color,
                    onClickLabel = "Switch branch ($branchLabel)",
                    onClick = onOpenBranchSheet,
                )
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("phone-git-branch-button"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PpmIcon(
                icon = ProductIcon.GitBranch,
                size = 15.dp,
                tint = tokens.accent.color,
            )
            BasicText(
                text = branchLabel,
                style = ppmText(13f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 16f)
                    .copy(color = tokens.text.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (branchState.ahead > 0 || branchState.behind > 0) {
                val counts = buildList {
                    if (branchState.ahead > 0) add("↑${branchState.ahead}")
                    if (branchState.behind > 0) add("↓${branchState.behind}")
                }.joinToString(" ")
                BasicText(
                    text = counts,
                    style = ppmText(11.5f, FontWeight.Normal, FontKind.MONO, lineHeight = 14f)
                        .copy(color = tokens.text2.color),
                    maxLines = 1,
                )
            }
            PpmIcon(
                icon = ProductIcon.ChevronDown,
                size = 13.dp,
                tint = tokens.text3.color,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        BasicText(
            text = when {
                groups.hasConflicts -> "${groups.conflictCount} conflict"
                groups.totalFiles > 0 -> "${groups.totalFiles} changed"
                else -> "Clean"
            },
            style = ppmText(12f, FontWeight.Normal, FontKind.MONO, lineHeight = 15f)
                .copy(color = if (groups.hasConflicts) tokens.warning.color else tokens.text2.color),
            maxLines = 1,
        )

        Box(
            modifier = Modifier
                .size(KitSpec.MinTouchTarget)
                .clip(slotShape)
                .ppmClickable(
                    enabled = busyAction == null,
                    shape = slotShape,
                    hoverColor = tokens.surfaceHover.color,
                    onClickLabel = "Fetch and refresh",
                    onClick = onRefresh,
                )
                .semantics { contentDescription = "Fetch and refresh" }
                .testTag("phone-git-refresh-button"),
            contentAlignment = Alignment.Center,
        ) {
            PpmIcon(
                icon = if (busyAction == "fetch") ProductIcon.Loader2 else ProductIcon.RefreshCw,
                size = 18.dp,
                tint = tokens.text2.color,
                spinning = busyAction == "fetch",
            )
        }
    }
}

/**
 * 64 dp file row in `Staged` or `Changes` (`SC2`, `SC7`, `CF2`, Foundation Note N16,
 * Requirements 9.1, 9.2, 9.3, 9.4).
 *
 * - Leading 48 dp touch cell toggles whole-file staging (`POST /git/stage` or `POST /git/unstage`).
 * - Tapping the row body opens [PhoneGitDiffScreen].
 * - Long-pressing the row opens the contextual action sheet (`Open diff`, `Open file`,
 *   `Stage file` / `Unstage file`, `Discard changes…`).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PhoneGitChangeRow(
    change: FileChange,
    onRowClick: () -> Unit,
    onToggleCheckbox: () -> Unit,
    onOpenActionSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val interactionSource = remember { MutableInteractionSource() }
    val statusTone = when {
        change.conflict || change.statusLetter == "D" || change.statusLetter == "U" -> tokens.error
        change.statusLetter == "A" -> tokens.success
        change.statusLetter == "R" -> tokens.accent
        else -> tokens.warning
    }
    val counts = change.lineCounts

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = KitSpec.RowPhoneTwoLineHeight)
            .cssBorder(tokens.borderSoft.color, width = 1.dp, sides = CssSides.Bottom)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClickLabel = "Open diff for ${change.fileName}",
                onLongClick = onOpenActionSheet,
                onClick = onRowClick,
            )
            .padding(end = 16.dp)
            .testTag("phone-git-row-${change.group.name.lowercase()}-${change.path}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 48 dp x 64 dp whole-file checkbox touch cell (Foundation Note N16)
        Box(
            modifier = Modifier
                .width(KitSpec.MinTouchTarget)
                .heightIn(min = KitSpec.RowPhoneTwoLineHeight)
                .alpha(if (change.canToggleStage) 1f else 0.55f)
                .ppmClickable(
                    enabled = change.canToggleStage,
                    role = Role.Checkbox,
                    onClickLabel = if (change.isStaged) "Unstage ${change.fileName}" else "Stage ${change.fileName}",
                    onClick = onToggleCheckbox,
                )
                .testTag("phone-git-check-${change.group.name.lowercase()}-${change.path}"),
            contentAlignment = Alignment.Center,
        ) {
            if (change.conflict) {
                PpmIcon(
                    icon = ProductIcon.TriangleAlert,
                    size = 18.dp,
                    tint = tokens.warning.color,
                )
            } else {
                val boxShape = RoundedCornerShape(6.dp)
                val checked = change.isChecked
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(boxShape)
                        .background(if (checked) tokens.accent.color else Color.Transparent, boxShape)
                        .cssBorder(
                            color = if (checked) tokens.accent.color else tokens.border.color,
                            shape = boxShape,
                            width = 1.5.dp,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (checked) {
                        PpmIcon(
                            icon = ProductIcon.Check,
                            size = 14.dp,
                            tint = tokens.accentFg.color,
                        )
                    }
                }
            }
        }

        // 22 dp status letter badge (`M`, `A`, `D`, `R`, `U`)
        val statusShape = RoundedCornerShape(6.dp)
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(statusShape)
                .background(statusTone.opacity(0.14).color, statusShape),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = change.statusLetter,
                style = ppmText(11f, FontWeight.Bold, FontKind.MONO, lineHeight = 13f)
                    .copy(color = statusTone.color),
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        // File name + subline (`"src/server · part of the file"` / `"rest of the file"`)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            BasicText(
                text = change.fileName,
                style = ppmText(14f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 18f)
                    .copy(color = tokens.text.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BasicText(
                text = change.subline,
                style = ppmText(12f, FontWeight.Normal, FontKind.MONO, lineHeight = 15f).copy(
                    color = when {
                        change.conflict -> tokens.warning.color
                        change.partiallyStaged -> tokens.accent.color
                        else -> tokens.text2.color
                    },
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("phone-git-subline-${change.group.name.lowercase()}-${change.path}"),
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Trailing +added / −removed counts for this group's side
        val noteText = change.lineNote
        if (counts != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.testTag("phone-git-counts-${change.group.name.lowercase()}-${change.path}"),
            ) {
                if (counts.first > 0) {
                    BasicText(
                        text = "+${counts.first}",
                        style = ppmText(12f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 15f)
                            .copy(color = tokens.success.color),
                        maxLines = 1,
                    )
                }
                if (counts.second > 0) {
                    BasicText(
                        text = "−${counts.second}",
                        style = ppmText(12f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 15f)
                            .copy(color = tokens.error.color),
                        maxLines = 1,
                    )
                }
            }
        } else if (!noteText.isNullOrEmpty()) {
            BasicText(
                text = noteText,
                style = ppmText(11.5f, FontWeight.Normal, FontKind.MONO, lineHeight = 14f)
                    .copy(color = tokens.text3.color),
                maxLines = 1,
            )
        }
    }
}

/**
 * Warning banner shown on phone when merge/rebase conflicts exist (`CF2`, Requirement 9.6, Note N28).
 */
@Composable
private fun PhoneConflictPauseBanner(
    conflictCount: Int,
    operation: com.hienlh.ppm.client.GitOperation?,
) {
    val tokens = LocalPpmTokens.current
    val bannerShape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(bannerShape)
            .background(tokens.warning.opacity(0.12).color, bannerShape)
            .cssBorder(tokens.warning.opacity(0.38).color, bannerShape, width = 1.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("phone-git-conflict-banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PpmIcon(
            icon = ProductIcon.TriangleAlert,
            size = 18.dp,
            tint = tokens.warning.color,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (operation != null) {
                BasicText(
                    text = GitChangesView.operationTitle(operation, null),
                    style = ppmText(12.5f, FontWeight.SemiBold, lineHeight = 16f)
                        .copy(color = tokens.warning.color),
                )
            }
            BasicText(
                text = PhoneGitCapabilities.CONFLICT_PAUSE_BANNER,
                style = ppmText(12.5f, FontWeight.Medium, lineHeight = 17f)
                    .copy(color = tokens.warning.color),
            )
        }
    }
}

/**
 * Row inside `NOT PUSHED` showing the most recent unpushed commit and an `Undo` button (`SC2`).
 */
@Composable
private fun PhoneUnpushedCommitRow(
    commit: LastCommit,
    busy: Boolean,
    onUndoCommit: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val relTime = remember(commit.date) { GitChangesView.formatRelativeTime(commit.date) }
    val subtitle = buildList {
        add(commit.shortHash)
        if (relTime.isNotEmpty()) add(relTime)
    }.joinToString(" · ")
    val undoShape = RoundedCornerShape(10.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = KitSpec.RowPhoneTwoLineHeight)
            .cssBorder(tokens.borderSoft.color, width = 1.dp, sides = CssSides.Bottom)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("phone-git-unpushed-commit"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PpmIcon(
            vector = LucideIcon.GitCommitHorizontal.vector,
            size = 18.dp,
            tint = tokens.accent.color,
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            BasicText(
                text = commit.subject.ifBlank { "(no commit message)" },
                style = ppmText(14f, FontWeight.Medium, lineHeight = 18f).copy(color = tokens.text.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BasicText(
                text = subtitle,
                style = ppmText(12f, FontWeight.Normal, FontKind.MONO, lineHeight = 15f)
                    .copy(color = tokens.text2.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (commit.hasParent) {
            Box(
                modifier = Modifier
                    .minTouchTarget(minWidth = 64.dp, minHeight = KitSpec.MinTouchTarget)
                    .clip(undoShape)
                    .background(tokens.panel.color, undoShape)
                    .cssBorder(tokens.border.color, undoShape, width = 1.dp)
                    .ppmClickable(
                        enabled = !busy,
                        shape = undoShape,
                        hoverColor = tokens.surfaceHover.color,
                        onClickLabel = "Undo commit",
                        onClick = onUndoCommit,
                    )
                    .padding(horizontal = 12.dp)
                    .testTag("phone-git-undo-commit"),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = "Undo",
                    style = ppmText(12.5f, FontWeight.SemiBold, lineHeight = 16f)
                        .copy(color = if (busy) tokens.text3.color else tokens.text.color),
                )
            }
        }
    }
}

@Composable
private fun PhoneCleanWorkingTreeState(
    branchState: GitBranchState,
) {
    val tokens = LocalPpmTokens.current
    val subtitle = when {
        branchState.ahead > 0 && branchState.behind > 0 ->
            "↑${branchState.ahead} ahead · ↓${branchState.behind} behind ${branchState.upstream ?: "remote"}"
        branchState.ahead > 0 ->
            "${branchState.ahead} ${if (branchState.ahead == 1) "commit" else "commits"} ready to push"
        branchState.behind > 0 ->
            "${branchState.behind} ${if (branchState.behind == 1) "commit" else "commits"} to pull"
        !branchState.upstream.isNullOrEmpty() ->
            "Up to date with ${branchState.upstream}"
        else ->
            "No uncommitted changes"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 48.dp)
            .testTag("phone-git-clean-state"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PpmIcon(
            icon = ProductIcon.CheckCircle2,
            size = 32.dp,
            tint = tokens.success.color,
        )
        BasicText(
            text = "Working tree clean",
            style = ppmText(15f, FontWeight.SemiBold, lineHeight = 20f).copy(color = tokens.text.color),
        )
        BasicText(
            text = subtitle,
            style = ppmText(13f, FontWeight.Normal, lineHeight = 18f)
                .copy(color = tokens.text2.color, textAlign = TextAlign.Center),
        )
    }
}

@Composable
private fun PhoneRepoSwitcherStrip(
    selectedRepo: GitRepo,
    repoCount: Int,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(tokens.panel.color)
            .cssBorder(tokens.borderSoft.color, width = 1.dp, sides = CssSides.Bottom)
            .ppmClickable(
                hoverColor = tokens.surfaceHover.color,
                onClickLabel = "Switch repository (${selectedRepo.name})",
                onClick = onClick,
            )
            .padding(horizontal = 16.dp)
            .testTag("phone-git-repo-strip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PpmIcon(
            vector = LucideIcon.FolderGit2.vector,
            size = 16.dp,
            tint = tokens.accent.color,
        )
        BasicText(
            text = selectedRepo.name,
            style = ppmText(13f, FontWeight.SemiBold, lineHeight = 17f).copy(color = tokens.text.color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        BasicText(
            text = "$repoCount repos",
            style = ppmText(11.5f, FontWeight.Normal, FontKind.MONO, lineHeight = 14f)
                .copy(color = tokens.text3.color),
        )
        PpmIcon(
            icon = ProductIcon.ChevronDown,
            size = 14.dp,
            tint = tokens.text3.color,
        )
    }
}

/**
 * Multi-repo selection view (`SC6`, Phone 4 Section 2, Note N6).
 */
@Composable
private fun PhoneMultiRepoPicker(
    repos: List<GitRepo>,
    onSelectRepo: (GitRepo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val cardShape = RoundedCornerShape(14.dp)

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag("phone-git-multi-repo-picker"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            Column(
                modifier = Modifier.padding(bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                BasicText(
                    text = "${repos.size} repositories in this folder",
                    style = ppmText(16f, FontWeight.SemiBold, lineHeight = 21f).copy(color = tokens.text.color),
                )
                BasicText(
                    text = "Pick a repository to view its changes and branches.",
                    style = ppmText(13f, FontWeight.Normal, lineHeight = 18f).copy(color = tokens.text2.color),
                )
            }
        }
        items(
            count = repos.size,
            key = { idx -> repos[idx].path },
        ) { idx ->
            val repo = repos[idx]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = KitSpec.RowPhoneTwoLineHeight)
                    .clip(cardShape)
                    .background(tokens.panel.color, cardShape)
                    .cssBorder(tokens.border.color, cardShape, width = 1.dp)
                    .ppmClickable(
                        shape = cardShape,
                        hoverColor = tokens.surfaceHover.color,
                        onClickLabel = "Open repository ${repo.name}",
                        onClick = { onSelectRepo(repo) },
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag("phone-git-repo-card-${repo.name}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PpmIcon(
                    vector = LucideIcon.FolderGit2.vector,
                    size = 20.dp,
                    tint = tokens.accent.color,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    BasicText(
                        text = repo.name,
                        style = ppmText(14.5f, FontWeight.SemiBold, lineHeight = 19f)
                            .copy(color = tokens.text.color),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    BasicText(
                        text = repo.relative,
                        style = ppmText(12f, FontWeight.Normal, FontKind.MONO, lineHeight = 15f)
                            .copy(color = tokens.text2.color),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                PpmIcon(
                    icon = ProductIcon.ChevronRight,
                    size = 16.dp,
                    tint = tokens.text3.color,
                )
            }
        }
    }
}

@Composable
private fun PhoneNoGitRepoState(
    onRescan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val buttonShape = RoundedCornerShape(12.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp)
            .testTag("phone-git-no-repo"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PpmIcon(
            vector = LucideIcon.FolderGit2.vector,
            size = 36.dp,
            tint = tokens.text3.color,
        )
        Spacer(modifier = Modifier.height(12.dp))
        BasicText(
            text = "No git repository in this project, or in any folder two levels below it.",
            style = ppmText(13.5f, FontWeight.Normal, lineHeight = 19f)
                .copy(color = tokens.text2.color, textAlign = TextAlign.Center),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .height(KitSpec.MinTouchTarget)
                .clip(buttonShape)
                .background(tokens.panel.color, buttonShape)
                .cssBorder(tokens.border.color, buttonShape, width = 1.dp)
                .ppmClickable(
                    shape = buttonShape,
                    hoverColor = tokens.surfaceHover.color,
                    onClickLabel = "Scan again",
                    onClick = onRescan,
                )
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PpmIcon(
                icon = ProductIcon.RefreshCw,
                size = 16.dp,
                tint = tokens.text.color,
            )
            BasicText(
                text = "Scan again",
                style = ppmText(13.5f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
            )
        }
    }
}
