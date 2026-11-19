package com.hienlh.ppm.app.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.LocalRedesign
import com.hienlh.ppm.client.ChangedFile
import com.hienlh.ppm.client.GitChanges
import com.hienlh.ppm.client.StashEntry
import com.hienlh.ppm.client.gitChanges
import com.hienlh.ppm.client.gitCommitMessage
import com.hienlh.ppm.client.gitCommit
import com.hienlh.ppm.client.gitDiscard
import com.hienlh.ppm.client.gitDiscardUndo
import com.hienlh.ppm.client.gitFetch
import com.hienlh.ppm.client.gitOperation
import com.hienlh.ppm.client.gitPublish
import com.hienlh.ppm.client.gitPull
import com.hienlh.ppm.client.gitPush
import com.hienlh.ppm.client.gitStage
import com.hienlh.ppm.client.gitStash
import com.hienlh.ppm.client.gitStashAction
import com.hienlh.ppm.client.gitUndoCommit
import com.hienlh.ppm.client.gitUnstage
import com.hienlh.ppm.client.readProjectFile
import com.hienlh.ppm.design.FileIcon
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.LucideIcon
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.state.git.CheckState
import com.hienlh.ppm.state.git.GitChangeGroups
import com.hienlh.ppm.state.git.GitChangesView
import com.hienlh.ppm.state.git.GitFileTree
import com.hienlh.ppm.state.git.GitRepoScope
import com.hienlh.ppm.state.git.GitTreeNode
import com.hienlh.ppm.state.git.WriteWithAi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

private data class ConfirmState(
    val title: String,
    val detail: String,
    val confirmLabel: String,
    val confirmIcon: ProductIcon = ProductIcon.Trash2,
    val destructive: Boolean = true,
    val run: () -> Unit,
)

private data class HunkModalTarget(
    val file: ChangedFile,
    val scope: String, // "worktree" | "index"
)

@Composable
fun GitStatusPanel(
    model: AppModel,
    onNavigate: (() -> Unit)? = null,
    useChangeGroups: Boolean = LocalRedesign.current,
    isOffline: Boolean = false,
    touchPlaceholder: Boolean = LocalRedesign.current,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val scope = rememberCoroutineScope()
    val project = model.selectedProject
    val api = model.api

    if (project == null) {
        Box(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText("No project selected.", style = PpmFont.sans(12, lineHeight = 16, color = tokens.text3.color))
        }
        return
    }

    androidx.compose.runtime.DisposableEffect(project) {
        model.onGitStatusPanelMounted(project)
        onDispose { model.onGitStatusPanelUnmounted(project) }
    }

    var repoLooking by remember(project) { mutableStateOf(project !in model.gitDiscovery) }
    LaunchedEffect(project, api) {
        if (project !in model.gitDiscovery) repoLooking = true
        try {
            model.loadGitRepos(project)
        } finally {
            repoLooking = false
        }
    }

    val discovery = model.gitDiscovery[project]
    val chosenPath = model.gitChosenRepoPath[project]
    val repo = remember(discovery, chosenPath) { GitRepoScope.resolveRepo(discovery, chosenPath) }
    val needsPick = remember(discovery, chosenPath) { GitRepoScope.needsPick(discovery, chosenPath) }
    val noRepo = remember(discovery) { GitRepoScope.hasNoRepo(discovery) }

    var changes by remember(project, repo?.path) { mutableStateOf(model.cachedGitChanges(project, repo?.path)) }
    var loading by remember(project, repo?.path) { mutableStateOf(changes == null) }
    var error by remember(project, repo?.path) { mutableStateOf<String?>(null) }
    var busyAction by remember { mutableStateOf<String?>(null) }
    var refreshSignal by remember { mutableIntStateOf(0) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<ConfirmState?>(null) }
    var hunkTarget by remember { mutableStateOf<HunkModalTarget?>(null) }
    var changesRequestId by remember(project, repo?.path) { mutableIntStateOf(0) }
    val currentRepoKey by rememberUpdatedState(model.gitChangesKey(project, repo?.path))
    var newestLoad by remember(project, repo?.path) {
        mutableStateOf<Pair<Int, CompletableDeferred<GitChanges?>>?>(null)
    }
    DisposableEffect(project, repo?.path) {
        onDispose { changesRequestId++ }
    }
    val writeMutex = remember { kotlinx.coroutines.sync.Mutex() }

    val drafts by model.commitDrafts.drafts.collectAsState()
    val commitMessage = drafts[com.hienlh.ppm.state.git.CommitDraftSync.draftKey(project, repo?.path)] ?: ""

    LaunchedEffect(project, repo?.path, api) {
        if (api != null && repo != null && !needsPick && !noRepo) {
            model.commitDrafts.load(api, project, repo)
        }
    }

    suspend fun loadChanges(): GitChanges? {
        val client = api ?: return null
        if (repo == null || needsPick || noRepo) {
            loading = false
            return null
        }
        val reqId = ++changesRequestId
        val reqProject = project
        val reqRepo = repo
        val reqKey = model.gitChangesKey(reqProject, reqRepo.path)
        val done = CompletableDeferred<GitChanges?>()
        newestLoad = reqId to done
        suspend fun overtaken(): GitChanges? {
            val next = newestLoad
            return if (next != null && next.first == changesRequestId && currentRepoKey == reqKey && next.second !== done) {
                next.second.await()
            } else {
                null
            }
        }
        loading = true
        try {
            val fresh = client.gitChanges(reqProject, reqRepo)
            if (reqId != changesRequestId || currentRepoKey != reqKey) {
                done.complete(null)
                return overtaken()
            }
            changes = fresh
            error = null
            model.applyGitChanges(reqProject, reqRepo, fresh)
            done.complete(fresh)
            return fresh
        } catch (e: CancellationException) {
            done.complete(null)
            throw e
        } catch (e: Exception) {
            if (reqId != changesRequestId || currentRepoKey != reqKey) {
                done.complete(null)
                return overtaken()
            }
            error = model.describe(e)
            done.complete(null)
            return null
        } finally {
            if (!done.isCompleted) done.complete(null)
            if (reqId == changesRequestId && currentRepoKey == reqKey) loading = false
        }
    }

    LaunchedEffect(project, repo?.path, needsPick, noRepo, refreshSignal, api) {
        if (api == null || repo == null || needsPick || noRepo) return@LaunchedEffect
        coroutineScope {
            launch {
                model.gitChangedEvents.filter { it == project }.collectLatest {
                    delay(300)
                    loadChanges()
                }
            }
            launch {
                snapshotFlow { model.windowVisible }
                    .drop(1)
                    .filter { it }
                    .collectLatest {
                        delay(300)
                        loadChanges()
                    }
            }
            loadChanges()
            while (true) {
                delay(5_000)
                if (model.windowVisible) {
                    loadChanges()
                }
            }
        }
    }

    fun triggerRefresh() {
        refreshSignal++
        scope.launch {
            if (repo == null) {
                repoLooking = true
                try {
                    model.loadGitRepos(project, force = true)
                } finally {
                    repoLooking = false
                }
            }
            loadChanges()
        }
    }

    val files = changes?.files.orEmpty()
    val conflicts = remember(files) { files.filter { it.conflict } }
    val normalFiles = remember(files) { files.filter { !it.conflict } }
    val totals = remember(files) { GitChangesView.changeTotals(files) }
    val branch = changes?.branch ?: com.hienlh.ppm.client.GitBranchState(null, null, null, false, 0, 0)
    val operation = changes?.operation
    val lastCommit = changes?.lastCommit
    val mode = remember(branch) { GitChangesView.syncMode(branch) }

    fun runGit(
        key: String,
        failureTitle: String,
        onAfterRefresh: ((GitChanges?) -> Unit)? = null,
        fn: suspend () -> Unit,
    ) {
        val client = api ?: return
        scope.launch {
            writeMutex.lock()
            busyAction = key
            var succeeded = false
            try {
                if (model.api === client) {
                    fn()
                    succeeded = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.toast(
                    ErrorToastMessage(
                        title = failureTitle,
                        description = model.describe(e),
                        kind = ErrorToastMessage.Kind.ERROR,
                    ),
                )
            } finally {
                busyAction = null
                writeMutex.unlock()
                val next = if (model.api === client) loadChanges() else null
                if (succeeded) {
                    onAfterRefresh?.invoke(next)
                }
            }
        }
    }

    fun markResolved(file: ChangedFile) {
        val client = api ?: return
        val stageNow = {
            runGit("stage", "Could not stage") {
                client.gitStage(project, listOf(file.path), repo)
            }
        }
        val fullPath = GitRepoScope.projectRelativePath(file.path, repo)
        scope.launch {
            try {
                val content = client.readProjectFile(project, fullPath)
                if (GitChangesView.hasConflictMarkers(content)) {
                    val name = file.path.substringAfterLast('/')
                    confirm = ConfirmState(
                        title = "$name still has conflict markers",
                        detail = "Marking it resolved stages the file as it is, markers and all.",
                        confirmLabel = "Mark resolved",
                        confirmIcon = ProductIcon.Check,
                        destructive = true,
                        run = stageNow,
                    )
                    return@launch
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            stageNow()
        }
    }

    fun stageFile(file: ChangedFile) {
        if (file.conflict) {
            markResolved(file)
            return
        }
        runGit("stage", "Could not stage") {
            api?.gitStage(project, listOf(file.path), repo)
        }
    }

    fun unstageFile(file: ChangedFile) {
        if (file.conflict) return
        runGit("unstage", "Could not unstage") {
            api?.gitUnstage(project, GitChangesView.unstagePaths(file), repo)
        }
    }

    fun toggleFile(file: ChangedFile) {
        if (file.conflict) {
            markResolved(file)
            return
        }
        if (GitChangesView.fileCheckState(file) == CheckState.ALL) {
            unstageFile(file)
        } else {
            stageFile(file)
        }
    }

    fun toggleBatch(batch: List<ChangedFile>) {
        val eligible = batch.filter { !it.conflict }
        if (eligible.isEmpty() || api == null) return
        val state = GitChangesView.allCheckState(eligible)
        if (state == CheckState.ALL) {
            runGit("unstage", "Could not unstage") {
                api.gitUnstage(project, eligible.flatMap(GitChangesView::unstagePaths), repo)
            }
        } else {
            val paths = eligible.map { it.path }
            runGit("stage", "Could not stage") {
                api.gitStage(project, paths, repo)
            }
        }
    }

    fun performDiscard(paths: List<String>, what: String) {
        runGit("discard", "Could not discard") {
            val record = api?.gitDiscard(project, paths, repo) ?: return@runGit
            if (record.files.isNotEmpty()) {
                val kept = if (record.files.size == 1) {
                    record.files[0].substringAfterLast('/')
                } else {
                    "${record.files.size} files"
                }
                model.toast(
                    ErrorToastMessage(
                        title = "Discarded changes to $what",
                        kind = ErrorToastMessage.Kind.SUCCESS,
                        durationMillis = 8_000L,
                        action = ErrorToastMessage.Action("Undo") {
                            runGit("undo", "Could not undo the discard") {
                                api?.gitDiscardUndo(project, record.id, repo)
                                model.notifyGitChanged(project)
                                model.toast(ErrorToastMessage("Restored $kept", kind = ErrorToastMessage.Kind.SUCCESS))
                            }
                        },
                    ),
                )
            }
            if (record.skipped.isNotEmpty()) {
                model.toast(
                    ErrorToastMessage(
                        title = "${record.skipped.joinToString(", ")} could not be kept, so it cannot be restored",
                        description = "Files over 20 MB are discarded without a copy.",
                        kind = ErrorToastMessage.Kind.WARNING,
                    ),
                )
            }
        }
    }

    fun askDiscardFiles(targets: List<ChangedFile>) {
        val eligible = targets.filter(GitChangesView::hasUnstaged)
        if (eligible.isEmpty()) return
        val summary = GitChangesView.discardSummary(eligible)
        val what = if (eligible.size == 1) {
            eligible[0].path.substringAfterLast('/')
        } else {
            "${eligible.size} files"
        }
        confirm = ConfirmState(
            title = summary.title,
            detail = summary.body,
            confirmLabel = summary.confirm,
            destructive = true,
            run = { performDiscard(eligible.map { it.path }, what) },
        )
    }

    fun undoCommit(hash: String) {
        if (busyAction != null || writeMutex.isLocked) {
            model.toast(
                ErrorToastMessage(
                    title = "Wait for git to finish",
                    description = "Undo the commit once it is done.",
                    kind = ErrorToastMessage.Kind.WARNING,
                ),
            )
            return
        }
        runGit(
            key = "undo-commit",
            failureTitle = "Could not undo the commit",
            onAfterRefresh = {
                api?.let { client -> scope.launch { model.commitDrafts.load(client, project, repo) } }
            },
        ) {
            api?.gitUndoCommit(project, hash, repo)
            model.notifyGitChanged(project)
            model.toast(ErrorToastMessage("Commit undone — its changes are staged again", kind = ErrorToastMessage.Kind.INFO))
        }
    }

    fun handleSync(syncMode: String? = mode, branchOverride: com.hienlh.ppm.client.GitBranchState = branch) {
        val upstream = branchOverride.upstream ?: "the remote"
        when (syncMode) {
            "publish" -> runGit("publish", "Publish failed") {
                val res = api?.gitPublish(project, repo) ?: return@runGit
                model.toast(ErrorToastMessage("Published ${res.branch} to ${res.remote}", kind = ErrorToastMessage.Kind.SUCCESS))
            }
            "pull" -> runGit("pull", "Pull failed") {
                api?.gitPull(project, rebase = false, repo = repo)
                model.toast(ErrorToastMessage("Pulled ${branchOverride.behind} ${if (branchOverride.behind == 1) "commit" else "commits"} from $upstream", kind = ErrorToastMessage.Kind.SUCCESS))
            }
            "push" -> runGit("push", "Push failed") {
                api?.gitPush(project, repo = repo)
                model.toast(
                    ErrorToastMessage(
                        if (branchOverride.ahead > 0) "Pushed ${branchOverride.ahead} ${if (branchOverride.ahead == 1) "commit" else "commits"} to $upstream" else "Pushed to $upstream",
                        kind = ErrorToastMessage.Kind.SUCCESS,
                    ),
                )
            }
            "sync" -> runGit("sync", "Sync failed") {
                api?.gitPull(project, rebase = false, repo = repo)
                api?.gitPush(project, repo = repo)
                model.toast(ErrorToastMessage("In sync with $upstream", kind = ErrorToastMessage.Kind.SUCCESS))
            }
            "synced" -> runGit(
                key = "fetch",
                failureTitle = "Fetch failed",
                onAfterRefresh = { next ->
                    val behind = next?.branch?.behind ?: 0
                    model.toast(
                        ErrorToastMessage(
                            if (behind > 0) "Fetched — $behind new ${if (behind == 1) "commit" else "commits"} to pull" else "Fetched — nothing new",
                            kind = ErrorToastMessage.Kind.INFO,
                        ),
                    )
                },
            ) {
                api?.gitFetch(project, repo = repo)
            }
        }
    }

    fun handleCommit(amend: Boolean, signoff: Boolean, andPush: Boolean) {
        val msg = commitMessage.trim()
        if ((!amend && msg.isEmpty()) || (amend && msg.isEmpty() && totals.filesStaged == 0) || api == null) return
        val count = totals.filesStaged
        val beforeBranch = branch
        val beforeMode = mode
        runGit(
            key = "commit",
            failureTitle = if (amend) "Could not amend the commit" else "Could not commit",
            onAfterRefresh = {
                if (andPush) {
                    val targetMode = if (beforeMode == "publish") "publish" else "push"
                    handleSync(targetMode, beforeBranch.copy(ahead = beforeBranch.ahead + 1))
                }
            },
        ) {
            model.commitDrafts.flush(api, project, repo)
            val res = api.gitCommit(project, msg, amend = amend, signoff = signoff, repo = repo)
            val hash = res.hash
            val short = hash.take(7)
            model.commitDrafts.consumed(project, repo, commitMessage)
            if (amend) {
                model.toast(ErrorToastMessage("Amended $short", kind = ErrorToastMessage.Kind.INFO))
            } else {
                model.toast(
                    ErrorToastMessage(
                        title = "Committed $short · $count ${if (count == 1) "file" else "files"}",
                        kind = ErrorToastMessage.Kind.INFO,
                        durationMillis = 8_000L,
                        action = ErrorToastMessage.Action("Undo") { undoCommit(hash) },
                    ),
                )
            }
        }
    }

    fun stashAction(action: String, stash: StashEntry) {
        runGit("stash-$action", "Could not $action the stash") {
            val res = api?.gitStashAction(project, action, stash.index, stash.hash, repo) ?: return@runGit
            model.notifyGitChanged(project)
            val what = when (action) {
                "apply" -> "Stash applied"
                "pop" -> "Stash applied and dropped"
                else -> "Stash dropped"
            }
            if (!res.indexRestored) {
                model.toast(
                    ErrorToastMessage(
                        title = what,
                        description = "Its staged changes no longer fit the index, so they came back unstaged.",
                        kind = ErrorToastMessage.Kind.WARNING,
                    ),
                )
            } else {
                model.toast(ErrorToastMessage(title = what, kind = ErrorToastMessage.Kind.SUCCESS))
            }
        }
    }

    fun handleStashAll() {
        val fileCount = totals.files
        runGit("stash", "Could not stash") {
            val entry = api?.gitStash(project, includeUntracked = true, repo = repo)
            if (entry == null) {
                model.toast(
                    ErrorToastMessage(
                        title = "Nothing was stashed",
                        description = "git found no change it could save.",
                        kind = ErrorToastMessage.Kind.INFO,
                    ),
                )
                return@runGit
            }
            model.toast(
                ErrorToastMessage(
                    title = "Stashed $fileCount ${if (fileCount == 1) "file" else "files"}",
                    kind = ErrorToastMessage.Kind.INFO,
                    durationMillis = 8_000L,
                    action = ErrorToastMessage.Action("Undo") {
                        stashAction("pop", entry)
                    },
                ),
            )
        }
    }

    fun openReview(file: ChangedFile? = null) {
        if (file?.conflict == true) {
            model.openGitConflictTab(project, GitRepoScope.projectRelativePath(file.path, repo))
        } else {
            model.openGitReview(project, file?.path, repo?.takeIf { it.relative != "." }?.path)
        }
        onNavigate?.invoke()
    }

    fun openDiff(file: ChangedFile) {
        model.openGitDiffFileTab(project, GitRepoScope.projectRelativePath(file.path, repo))
        onNavigate?.invoke()
    }

    fun openFile(file: ChangedFile) {
        model.openProjectEditorTab(project, GitRepoScope.projectRelativePath(file.path, repo))
        onNavigate?.invoke()
    }

    val extContributions = model.extensions?.contributions?.collectAsState()?.value
    val gitGraphAvailable = remember(extContributions) {
        extContributions?.commands?.any { it.command == "git-graph.view" } == true
    }
    val activeProjectPath = model.projects.firstOrNull { it.name == project }?.path
    val gitRoot = repo?.path ?: activeProjectPath

    fun openGraph() {
        if (gitGraphAvailable) {
            val args = if (!gitRoot.isNullOrEmpty()) listOf(JsonPrimitive(gitRoot)) else emptyList()
            model.dispatchExtCommand("git-graph.view", args = args)
        } else {
            model.openGitHistoryTab(project)
        }
        onNavigate?.invoke()
    }

    fun openBranchReview() {
        model.openBranchReviewTab(project)
        onNavigate?.invoke()
    }

    val changeGroups = remember(files) { GitChangeGroups.from(files) }
    var aiLoading by remember(project, repo?.path) { mutableStateOf(false) }
    var shortcutsOpen by remember { mutableStateOf(false) }
    var focusedRowKey by remember(project, repo?.path) { mutableStateOf<String?>(null) }

    // The sparkle button in the box (`draftMessage` in `git-commit-composer.tsx`): asks for a
    // message from what is staged in this repository and puts it in its shared draft; never commits.
    fun handleWriteWithAi() {
        val client = api ?: return
        if (aiLoading || totals.filesStaged == 0) return
        val repoAsked = repo
        val before = model.commitDrafts.message(project, repoAsked)
        scope.launch {
            aiLoading = true
            try {
                val msg = client.gitCommitMessage(project, repoAsked)
                when {
                    // Typed into while the AI worked: keep what was typed rather than overwrite it.
                    !WriteWithAi.draftFits(before, model.commitDrafts.message(project, repoAsked)) -> model.toast(
                        ErrorToastMessage(WriteWithAi.DRAFT_NOT_USED_TITLE, WriteWithAi.DRAFT_NOT_USED_DESCRIPTION, kind = ErrorToastMessage.Kind.PLAIN),
                    )
                    msg.isNotEmpty() -> model.commitDrafts.setMessage(client, project, repoAsked, msg)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.toast(ErrorToastMessage(WriteWithAi.DRAFT_FAILED_TITLE, model.describe(e)))
            } finally {
                aiLoading = false
            }
        }
    }

    val orderedRowEntries = remember(conflicts, normalFiles, changeGroups, useChangeGroups, operation) {
        buildList<Pair<String, ChangedFile>> {
            for (c in conflicts) add("conflict:${c.path}" to c)
            if (useChangeGroups && operation == null) {
                for (s in changeGroups.staged) add("staged:${s.path}" to s.raw)
                for (ch in changeGroups.changes) add("changes:${ch.path}" to ch.raw)
            } else {
                for (f in normalFiles) add("changes:${f.path}" to f)
            }
        }
    }

    val showComposer = files.isNotEmpty() && operation == null
    val composerAtBottom = mobile || useChangeGroups
    val headerIconSize = if (mobile) 44.dp else 24.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusTarget()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    ev.utf16CodePoint == '?'.code -> {
                        shortcutsOpen = true
                        true
                    }
                    ev.key == Key.DirectionDown -> {
                        if (orderedRowEntries.isEmpty()) return@onPreviewKeyEvent false
                        val idx = orderedRowEntries.indexOfFirst { it.first == focusedRowKey }
                        val nextIdx = if (idx < 0) 0 else (idx + 1).coerceAtMost(orderedRowEntries.lastIndex)
                        focusedRowKey = orderedRowEntries[nextIdx].first
                        true
                    }
                    ev.key == Key.DirectionUp -> {
                        if (orderedRowEntries.isEmpty()) return@onPreviewKeyEvent false
                        val idx = orderedRowEntries.indexOfFirst { it.first == focusedRowKey }
                        val prevIdx = if (idx <= 0) 0 else idx - 1
                        focusedRowKey = orderedRowEntries[prevIdx].first
                        true
                    }
                    ev.key == Key.Spacebar -> {
                        val entry = orderedRowEntries.firstOrNull { it.first == focusedRowKey } ?: return@onPreviewKeyEvent false
                        val (key, file) = entry
                        when {
                            key.startsWith("staged:") -> unstageFile(file)
                            key.startsWith("changes:") -> stageFile(file)
                            else -> toggleFile(file)
                        }
                        true
                    }
                    ev.key == Key.Enter || ev.key == Key.NumPadEnter -> {
                        val entry = orderedRowEntries.firstOrNull { it.first == focusedRowKey } ?: return@onPreviewKeyEvent false
                        openReview(entry.second)
                        true
                    }
                    ev.key == Key.Delete || ev.key == Key.Backspace -> {
                        val entry = orderedRowEntries.firstOrNull { it.first == focusedRowKey } ?: return@onPreviewKeyEvent false
                        if (GitChangesView.hasUnstaged(entry.second)) {
                            askDiscardFiles(listOf(entry.second))
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }
            .testTag("git-status-panel"),
    ) {
        // Panel header (`SidebarHeader` in `git-status-panel.tsx:580`)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .edge(Edge.Bottom, tokens.border.color)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PpmIcon(ProductIcon.GitBranch, size = 16.dp, tint = tokens.accent.color)
            BasicText(
                text = "SOURCE CONTROL",
                style = PpmFont.sans(12, FontWeight.SemiBold, lineHeight = 16, color = tokens.text2.color)
                    .copy(letterSpacing = 0.025.em),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Flat / Tree view toggle
            val isTree = model.gitStatusViewMode == "tree"
            Box(
                modifier = Modifier
                    .size(headerIconSize)
                    .clip(RoundedCornerShape(4.dp))
                    .semantics { contentDescription = if (isTree) "Show as a list" else "Group by folder" }
                    .clickable { model.setGitViewMode(if (isTree) "flat" else "tree") }
                    .testTag("git-view-mode-toggle"),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(
                    if (isTree) ProductIcon.List else ProductIcon.FolderTree,
                    size = 14.dp,
                    tint = tokens.text2.color,
                )
            }
            // History / Git Graph button
            Box(
                modifier = Modifier
                    .size(headerIconSize)
                    .clip(RoundedCornerShape(4.dp))
                    .semantics { contentDescription = if (gitGraphAvailable) "Open Git Graph (⌘G)" else "View Git Log" }
                    .clickable(onClick = ::openGraph)
                    .testTag("git-history-button"),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.History, size = 14.dp, tint = tokens.text2.color)
            }
            // Keyboard & Touch Shortcuts button (?)
            Box(
                modifier = Modifier
                    .size(headerIconSize)
                    .clip(RoundedCornerShape(4.dp))
                    .semantics { contentDescription = "Keyboard & Touch Shortcuts" }
                    .clickable { shortcutsOpen = true }
                    .testTag("git-shortcuts-button"),
                contentAlignment = Alignment.Center,
            ) {
                BasicText("?", style = PpmFont.mono(12, FontWeight.SemiBold, lineHeight = 16, color = tokens.text2.color))
            }
            // More menu
            Box {
                Box(
                    modifier = Modifier
                        .size(headerIconSize)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { moreMenuOpen = !moreMenuOpen }
                        .testTag("git-more-button"),
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(ProductIcon.MoreHorizontal, size = 14.dp, tint = tokens.text2.color)
                }
                if (moreMenuOpen) {
                    val discardable = remember(files) { files.filter(GitChangesView::hasUnstaged) }
                    @Composable
                    fun MoreMenuItems() {
                        MoreMenuItem(ProductIcon.RefreshCw, "Fetch", enabled = busyAction == null && branch.hasRemote) {
                            moreMenuOpen = false
                            handleSync("synced")
                        }
                        MoreMenuItem(
                            ProductIcon.ArrowDown,
                            "Pull with rebase",
                            enabled = busyAction == null && branch.upstream != null && !branch.upstreamGone,
                        ) {
                            moreMenuOpen = false
                            runGit("pull", "Pull failed") {
                                api?.gitPull(project, rebase = true, repo = repo)
                            }
                        }
                        MoreMenuItem(
                            ProductIcon.Archive,
                            "Stash all changes",
                            enabled = busyAction == null && files.isNotEmpty(),
                        ) {
                            moreMenuOpen = false
                            handleStashAll()
                        }
                        MoreMenuItem(ProductIcon.GitMerge, "Review branch…", enabled = true) {
                            moreMenuOpen = false
                            openBranchReview()
                        }
                        MoreMenuItem(ProductIcon.RefreshCw, "Refresh", enabled = true) {
                            moreMenuOpen = false
                            triggerRefresh()
                        }
                        if (discardable.isNotEmpty()) {
                            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(tokens.border.color))
                            MoreMenuItem(
                                ProductIcon.Trash2,
                                "Discard all changes…",
                                enabled = busyAction == null,
                                destructive = true,
                            ) {
                                moreMenuOpen = false
                                askDiscardFiles(discardable)
                            }
                        }
                    }
                    if (mobile) {
                        androidx.compose.ui.window.Dialog(
                            onDismissRequest = { moreMenuOpen = false },
                            properties = DialogProperties(usePlatformDefaultWidth = false),
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize().clickable { moreMenuOpen = false },
                                contentAlignment = Alignment.BottomCenter,
                            ) {
                                val sheetShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(sheetShape)
                                        .background(tokens.bgSolid.color)
                                        .cssBorder(tokens.border.color, sheetShape)
                                        .padding(vertical = 8.dp)
                                        .testTag("git-more-bottom-sheet"),
                                ) {
                                    MoreMenuItems()
                                }
                            }
                        }
                    } else {
                        Popup(
                            alignment = Alignment.BottomEnd,
                            onDismissRequest = { moreMenuOpen = false },
                            properties = PopupProperties(focusable = true),
                        ) {
                            val popShape = RoundedCornerShape(6.dp)
                            Column(
                                modifier = Modifier
                                    .width(196.dp)
                                    .clip(popShape)
                                    .background(tokens.bgSolid.color)
                                    .cssBorder(tokens.border.color, popShape)
                                    .padding(vertical = 4.dp),
                            ) {
                                MoreMenuItems()
                            }
                        }
                    }
                }
            }
        }

        if (isOffline) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tokens.warning.opacity(0.12).color)
                    .edge(Edge.Bottom, tokens.warning.opacity(0.3).color)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("git-offline-banner"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PpmIcon(ProductIcon.Loader2, size = 13.dp, tint = tokens.warning.color)
                BasicText(
                    text = "Reconnecting… · Staging and commit wait for the connection",
                    style = PpmFont.sans(11, FontWeight.Medium, lineHeight = 14, color = tokens.warning.color),
                )
            }
        }

        // Repo bar when project has nested repos and one is selected
        if (discovery != null && !discovery.rootIsRepo && repo != null) {
            GitRepoBar(
                repo = repo,
                repos = discovery.repos,
                onSelect = { chosen -> model.chooseGitRepo(project, chosen.path) },
            )
        }

        when {
            noRepo -> {
                GitNoRepo(onRescan = { scope.launch { model.loadGitRepos(project, force = true) } })
            }
            needsPick && discovery != null -> {
                GitRepoChoice(
                    repos = discovery.repos,
                    onSelect = { chosen -> model.chooseGitRepo(project, chosen.path) },
                )
            }
            repo == null -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp).testTag("git-looking-for-repo"),
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PpmIcon(ProductIcon.Loader2, size = 16.dp, tint = tokens.text3.color)
                        BasicText("Looking for a repository...", style = PpmFont.sans(13, lineHeight = 18, color = tokens.text3.color))
                    }
                    if (!repoLooking) {
                        Box(
                            modifier = Modifier
                                .height(26.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .cssBorder(tokens.border.color, RoundedCornerShape(6.dp))
                                .clickable(onClick = ::triggerRefresh)
                                .padding(horizontal = 10.dp)
                                .testTag("git-try-again"),
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText("Try again", style = PpmFont.sans(12, lineHeight = 16, color = tokens.text.color))
                        }
                    }
                }
            }
            else -> {
                // Branch row
                if (changes != null) {
                    GitBranchRow(
                        branch = branch,
                        operation = operation,
                        fileCount = files.size,
                        busyAction = if (isOffline) "offline" else busyAction,
                        onOpenPicker = { model.openBranchPicker(project) },
                        onSync = { handleSync(mode) },
                    )
                    val pollError = error
                    if (pollError != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(tokens.error.color.copy(alpha = 0.10f))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .testTag("git-poll-error-banner"),
                        ) {
                            BasicText(
                                text = pollError,
                                style = PpmFont.sans(12, lineHeight = 16, color = tokens.error.color),
                            )
                        }
                    }
                }

                // Operation banner
                if (operation != null) {
                    val noun = GitChangesView.OPERATION_NOUN[operation.kind] ?: operation.kind
                    GitOperationBanner(
                        operation = operation,
                        branch = branch,
                        conflicts = conflicts.size,
                        busyAction = if (isOffline) "offline" else busyAction,
                        onContinue = {
                            runGit("continue", "Could not continue the $noun") {
                                api?.gitOperation(project, "continue", repo)
                                model.toast(ErrorToastMessage("The $noun is finished", kind = ErrorToastMessage.Kind.SUCCESS))
                            }
                        },
                        onSkip = if (operation.kind == "rebase") {
                            {
                                runGit("skip", "Could not skip the commit") {
                                    api?.gitOperation(project, "skip", repo)
                                }
                            }
                        } else {
                            null
                        },
                        onAbort = {
                            confirm = ConfirmState(
                                title = "Abort the $noun?",
                                detail = "This puts the branch back where it was before the $noun started. Conflicts you resolved so far are lost.",
                                confirmLabel = "Abort $noun",
                                confirmIcon = ProductIcon.X,
                                destructive = true,
                                run = {
                                    runGit("abort", "Could not abort the $noun") {
                                        api?.gitOperation(project, "abort", repo)
                                    }
                                },
                            )
                        },
                    )
                }

                // Classic desktop composer sits above the change list when !composerAtBottom
                if (!composerAtBottom && showComposer) {
                    GitCommitComposer(
                        message = commitMessage,
                        onMessageChange = { model.commitDrafts.setMessage(api, project, repo, it) },
                        totals = totals,
                        operation = operation,
                        lastCommit = lastCommit,
                        syncMode = mode,
                        busyAction = if (isOffline) "offline" else busyAction,
                        onCommit = ::handleCommit,
                        onUndoLast = { lastCommit?.let { undoCommit(it.hash) } },
                        isBottom = false,
                        branch = branch.head,
                        onWriteWithAi = ::handleWriteWithAi,
                        aiLoading = aiLoading,
                        touchPlaceholder = touchPlaceholder,
                    )
                }

                @Composable
                fun Folds() {
                    // Stashes fold
                    GitStashSection(
                        model = model,
                        project = project,
                        repo = repo,
                        count = changes?.stashes ?: 0,
                        onChanged = ::triggerRefresh,
                    )

                    // Worktrees fold
                    GitWorktreePanel(
                        model = model,
                        project = project,
                        repo = repo,
                        activePath = repo?.path ?: discovery?.root,
                        refreshSignal = refreshSignal,
                    )
                }

                // Scrollable body
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    when {
                        loading && changes == null -> {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                PpmIcon(ProductIcon.Loader2, size = 14.dp, tint = tokens.text3.color)
                                BasicText("Loading git status...", style = PpmFont.sans(12, lineHeight = 16, color = tokens.text3.color))
                            }
                        }
                        error != null && changes == null -> {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                BasicText(error ?: "", style = PpmFont.sans(12, lineHeight = 16, color = tokens.error.color))
                                Box(
                                    modifier = Modifier
                                        .height(if (mobile) 44.dp else 28.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .cssBorder(tokens.border.color, RoundedCornerShape(6.dp))
                                        .clickable(onClick = ::triggerRefresh)
                                        .padding(horizontal = 10.dp)
                                        .testTag("git-retry"),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    BasicText("Retry", style = PpmFont.sans(12, lineHeight = 16, color = tokens.text.color))
                                }
                            }
                        }
                        files.isEmpty() -> {
                            CleanState(
                                last = lastCommit,
                                operation = operation,
                                busy = busyAction != null || isOffline,
                                onUndo = { lastCommit?.let { undoCommit(it.hash) } },
                                onOpenHistory = ::openGraph,
                            )
                        }
                        else -> {
                            if (conflicts.isNotEmpty()) {
                                ChangeGroupSection(
                                    model = model,
                                    label = "Conflicts",
                                    files = conflicts,
                                    errorTone = true,
                                    showCheckAll = false,
                                    viewMode = model.gitStatusViewMode,
                                    busy = busyAction != null || isOffline,
                                    onToggleFile = ::toggleFile,
                                    onStageFile = ::stageFile,
                                    onUnstageFile = ::unstageFile,
                                    onToggleBatch = ::toggleBatch,
                                    onOpen = ::openReview,
                                    onOpenDiff = ::openDiff,
                                    onOpenFile = ::openFile,
                                    onDiscard = ::askDiscardFiles,
                                    onStageLines = { f, s -> hunkTarget = HunkModalTarget(f, s) },
                                    onMarkResolved = ::markResolved,
                                    onReviewAll = null,
                                )
                            }
                            if (normalFiles.isNotEmpty()) {
                                if (useChangeGroups && operation == null) {
                                    GitStagedAndChangesGroups(
                                        model = model,
                                        groups = changeGroups,
                                        viewMode = model.gitStatusViewMode,
                                        busy = busyAction != null || isOffline,
                                        focusedRowKey = focusedRowKey,
                                        onFocusRow = { focusedRowKey = it },
                                        onToggleFile = ::toggleFile,
                                        onStageFile = ::stageFile,
                                        onUnstageFile = ::unstageFile,
                                        onToggleBatch = ::toggleBatch,
                                        onOpen = ::openReview,
                                        onOpenDiff = ::openDiff,
                                        onOpenFile = ::openFile,
                                        onDiscard = ::askDiscardFiles,
                                        onStageLines = { f, s -> hunkTarget = HunkModalTarget(f, s) },
                                        onMarkResolved = ::markResolved,
                                        onReviewAll = { openReview(null) },
                                    )
                                } else {
                                    ChangeGroupSection(
                                        model = model,
                                        label = if (operation?.kind == "merge") "Merged" else "Changes",
                                        files = normalFiles,
                                        errorTone = false,
                                        showCheckAll = operation == null,
                                        viewMode = model.gitStatusViewMode,
                                        busy = busyAction != null || isOffline,
                                        onToggleFile = ::toggleFile,
                                        onStageFile = ::stageFile,
                                        onUnstageFile = ::unstageFile,
                                        onToggleBatch = ::toggleBatch,
                                        onOpen = ::openReview,
                                        onOpenDiff = ::openDiff,
                                        onOpenFile = ::openFile,
                                        onDiscard = ::askDiscardFiles,
                                        onStageLines = { f, s -> hunkTarget = HunkModalTarget(f, s) },
                                        onMarkResolved = ::markResolved,
                                        onReviewAll = { openReview(null) },
                                    )
                                }
                            }
                            if (changes?.truncated == true) {
                                BasicText(
                                    text = "Only the first ${files.size} changes are listed.",
                                    style = PpmFont.sans(11, lineHeight = 14, color = tokens.text3.color),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }

                    if (mobile && changes != null) {
                        Folds()
                    }
                }

                // On desktop/tablet (`!isMobile`), folds sit pinned at the bottom of the panel (`git-status-panel.tsx:705`)
                if (!mobile && changes != null) {
                    Folds()
                }

                // Bottom-anchored commit composer (phone thumb-zone + Tablet 4 Section 2 bottom composer)
                if (composerAtBottom && showComposer) {
                    GitCommitComposer(
                        message = commitMessage,
                        onMessageChange = { model.commitDrafts.setMessage(api, project, repo, it) },
                        totals = totals,
                        operation = operation,
                        lastCommit = lastCommit,
                        syncMode = mode,
                        busyAction = if (isOffline) "offline" else busyAction,
                        onCommit = ::handleCommit,
                        onUndoLast = { lastCommit?.let { undoCommit(it.hash) } },
                        isBottom = true,
                        branch = branch.head,
                        onWriteWithAi = ::handleWriteWithAi,
                        aiLoading = aiLoading,
                        touchPlaceholder = touchPlaceholder,
                    )
                }
            }
        }
    }

    if (shortcutsOpen) {
        GitShortcutsSheet(onDismiss = { shortcutsOpen = false })
    }

    confirm?.let { c ->
        GitConfirm(
            title = c.title,
            detail = c.detail,
            confirmLabel = c.confirmLabel,
            confirmIcon = c.confirmIcon,
            destructive = c.destructive,
            onConfirm = {
                val run = c.run
                confirm = null
                run()
            },
            onCancel = { confirm = null },
        )
    }

    hunkTarget?.let { target ->
        GitHunkStageDialog(
            model = model,
            project = project,
            repo = repo,
            filePath = target.file.path,
            scope = target.scope,
            onClose = { hunkTarget = null },
            onApplied = ::triggerRefresh,
        )
    }
}

@Composable
private fun MoreMenuItem(
    icon: ProductIcon,
    label: String,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val fg = if (destructive) tokens.error.color else tokens.text.color
    val iconColor = if (destructive) tokens.error.color else tokens.text3.color
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (mobile) 44.dp else 28.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PpmIcon(icon, size = 14.dp, tint = iconColor)
        BasicText(label, style = PpmFont.sans(12, lineHeight = 16, color = fg))
    }
}

@Composable
internal fun ChangeGroupSection(
    model: AppModel,
    label: String,
    files: List<ChangedFile>,
    errorTone: Boolean,
    showCheckAll: Boolean,
    viewMode: String,
    busy: Boolean = false,
    onToggleFile: (ChangedFile) -> Unit,
    onStageFile: (ChangedFile) -> Unit = onToggleFile,
    onUnstageFile: (ChangedFile) -> Unit = onToggleFile,
    onToggleBatch: (List<ChangedFile>) -> Unit,
    onOpen: (ChangedFile) -> Unit,
    onOpenDiff: (ChangedFile) -> Unit,
    onOpenFile: (ChangedFile) -> Unit,
    onDiscard: (List<ChangedFile>) -> Unit,
    onStageLines: (ChangedFile, String) -> Unit,
    onMarkResolved: (ChangedFile) -> Unit,
    onReviewAll: (() -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    var open by remember { mutableStateOf(true) }
    val allState = remember(files) { GitChangesView.allCheckState(files) }

    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (mobile) 44.dp else if (errorTone) 30.dp else 34.dp)
                .padding(start = 10.dp, end = if (showCheckAll) 0.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (errorTone) {
                    PpmIcon(
                        ProductIcon.AlertCircle,
                        size = 14.dp,
                        tint = tokens.error.color,
                    )
                }
                BasicText(
                    text = label.uppercase(),
                    style = PpmFont.sans(
                        11,
                        FontWeight.SemiBold,
                        lineHeight = 14,
                        color = if (errorTone) tokens.error.color else tokens.text3.color,
                    ).copy(letterSpacing = 0.07.em),
                )
                CountChip(files.size, errorTone = errorTone)
            }
            if (onReviewAll != null) {
                Row(
                    modifier = Modifier
                        .height(if (mobile) 44.dp else 24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .semantics { contentDescription = "Review block by block" }
                        .clickable(onClick = onReviewAll)
                        .padding(horizontal = 6.dp)
                        .testTag("git-group-review-$label"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PpmIcon(LucideIcon.FileDiff, size = 14.dp, tint = tokens.text2.color)
                    BasicText("Review", style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.text2.color))
                }
            }
            if (showCheckAll) {
                CheckCell(
                    state = allState,
                    disabled = busy,
                    tag = "git-group-check-all-$label",
                    onToggle = { onToggleBatch(files) },
                )
            }
        }

        if (open) {
            if (viewMode == "tree") {
                val tree = remember(files) { GitFileTree.buildTree(files) }
                val closedPaths = remember { mutableStateMapOf<String, Boolean>() }
                for (node in tree) {
                    GitTreeNodeView(
                        model = model,
                        node = node,
                        depth = 0,
                        closedPaths = closedPaths,
                        busy = busy,
                        onToggleFile = onToggleFile,
                        onStageFile = onStageFile,
                        onUnstageFile = onUnstageFile,
                        onToggleBatch = onToggleBatch,
                        onOpen = onOpen,
                        onOpenDiff = onOpenDiff,
                        onOpenFile = onOpenFile,
                        onDiscard = onDiscard,
                        onStageLines = onStageLines,
                        onMarkResolved = onMarkResolved,
                    )
                }
            } else {
                for (file in files) {
                    GitChangeRow(
                        model = model,
                        file = file,
                        depth = 0,
                        nested = false,
                        busy = busy,
                        onToggle = onToggleFile,
                        onStage = onStageFile,
                        onUnstage = onUnstageFile,
                        onOpen = onOpen,
                        onOpenDiff = onOpenDiff,
                        onOpenFile = onOpenFile,
                        onDiscard = { onDiscard(listOf(it)) },
                        onStageLines = onStageLines,
                        onMarkResolved = onMarkResolved,
                    )
                }
            }
        }
    }
}

@Composable
private fun GitTreeNodeView(
    model: AppModel,
    node: GitTreeNode,
    depth: Int,
    closedPaths: MutableMap<String, Boolean>,
    busy: Boolean = false,
    onToggleFile: (ChangedFile) -> Unit,
    onStageFile: (ChangedFile) -> Unit,
    onUnstageFile: (ChangedFile) -> Unit,
    onToggleBatch: (List<ChangedFile>) -> Unit,
    onOpen: (ChangedFile) -> Unit,
    onOpenDiff: (ChangedFile) -> Unit,
    onOpenFile: (ChangedFile) -> Unit,
    onDiscard: (List<ChangedFile>) -> Unit,
    onStageLines: (ChangedFile, String) -> Unit,
    onMarkResolved: (ChangedFile) -> Unit,
) {
    val file = node.file
    if (file != null) {
        GitChangeRow(
            model = model,
            file = file,
            depth = depth,
            nested = true,
            busy = busy,
            onToggle = onToggleFile,
            onStage = onStageFile,
            onUnstage = onUnstageFile,
            onOpen = onOpen,
            onOpenDiff = onOpenDiff,
            onOpenFile = onOpenFile,
            onDiscard = { onDiscard(listOf(it)) },
            onStageLines = onStageLines,
            onMarkResolved = onMarkResolved,
        )
        return
    }

    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val open = closedPaths[node.path] != true
    val leafFiles = remember(node) { GitFileTree.collectFiles(node) }
    val folderState = remember(leafFiles) { GitChangesView.allCheckState(leafFiles) }
    val canDiscard = remember(leafFiles) { leafFiles.any(GitChangesView::hasUnstaged) }
    val (prefix, leaf) = remember(node.name) { GitFileTree.splitFolderName(node.name) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (mobile) 44.dp else 28.dp)
            .clickable { closedPaths[node.path] = open }
            .padding(start = (depth * 12 + 6).dp)
            .testTag("git-tree-folder-${node.path}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PpmIcon(
            if (open) ProductIcon.ChevronDown else ProductIcon.ChevronRight,
            size = 12.dp,
            tint = tokens.text3.color,
        )
        PpmIcon(
            if (open) ProductIcon.FolderOpen else ProductIcon.Folder,
            size = 14.dp,
            tint = tokens.accent.opacity(0.8).color,
        )
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (prefix.isNotEmpty()) {
                BasicText(prefix, style = PpmFont.sans(12, lineHeight = 16, color = tokens.text3.color))
            }
            StartTruncatedText(leaf, style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.text2.color))
        }
        if (canDiscard) {
            Box(
                modifier = Modifier
                    .size(if (mobile) 44.dp else 24.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .alpha(if (busy) 0.4f else 1f)
                    .clickable(enabled = !busy) { onDiscard(leafFiles) }
                    .testTag("git-folder-discard-${node.path}"),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.Trash2, size = 12.dp, tint = tokens.text3.color)
            }
        }
        CountChip(node.fileCount)
        CheckCell(
            state = folderState,
            disabled = busy || leafFiles.all { it.conflict },
            onToggle = { onToggleBatch(leafFiles) },
        )
    }

    if (open) {
        for (child in node.children) {
            GitTreeNodeView(
                model = model,
                node = child,
                depth = depth + 1,
                closedPaths = closedPaths,
                busy = busy,
                onToggleFile = onToggleFile,
                onStageFile = onStageFile,
                onUnstageFile = onUnstageFile,
                onToggleBatch = onToggleBatch,
                onOpen = onOpen,
                onOpenDiff = onOpenDiff,
                onOpenFile = onOpenFile,
                onDiscard = onDiscard,
                onStageLines = onStageLines,
                onMarkResolved = onMarkResolved,
            )
        }
    }
}

@Composable
internal fun GitChangeRow(
    model: AppModel,
    file: ChangedFile,
    depth: Int = 0,
    nested: Boolean = false,
    busy: Boolean = false,
    onToggle: (ChangedFile) -> Unit,
    onStage: (ChangedFile) -> Unit = onToggle,
    onUnstage: (ChangedFile) -> Unit = onToggle,
    onOpen: (ChangedFile) -> Unit,
    onOpenDiff: (ChangedFile) -> Unit,
    onOpenFile: (ChangedFile) -> Unit,
    onDiscard: (ChangedFile) -> Unit,
    onStageLines: (ChangedFile, String) -> Unit,
    onMarkResolved: (ChangedFile) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val (name, dir) = remember(file.path) { GitChangesView.splitPath(file.path) }
    val state = remember(file) { GitChangesView.fileCheckState(file) }
    val letter = remember(file) { GitChangesView.changeLetter(file) }
    val discardable = remember(file) { GitChangesView.hasUnstaged(file) }
    val conflict = file.conflict
    var menuOpen by remember { mutableStateOf(false) }

    @Composable
    fun MenuItems() {
        if (conflict) {
            ContextRow("Resolve conflict", mobile = mobile) { menuOpen = false; onOpen(file) }
            ContextRow("Open file", mobile = mobile) { menuOpen = false; onOpenFile(file) }
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(tokens.border.color))
            ContextRow("Mark as resolved", enabled = !busy, mobile = mobile) { menuOpen = false; onMarkResolved(file) }
        } else {
            ContextRow("Review changes", mobile = mobile) { menuOpen = false; onOpen(file) }
            ContextRow("Open diff", mobile = mobile) { menuOpen = false; onOpenDiff(file) }
            ContextRow("Open file", mobile = mobile) { menuOpen = false; onOpenFile(file) }
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(tokens.border.color))
            if (state != CheckState.ALL) {
                ContextRow("Stage file", enabled = !busy, mobile = mobile) { menuOpen = false; onStage(file) }
            }
            if (state != CheckState.NONE) {
                ContextRow("Unstage file", enabled = !busy, mobile = mobile) { menuOpen = false; onUnstage(file) }
            }
            if (file.unstaged != null) {
                ContextRow("Stage lines…", enabled = !busy, mobile = mobile) { menuOpen = false; onStageLines(file, "worktree") }
            }
            if (file.staged != null) {
                ContextRow("Unstage lines…", enabled = !busy, mobile = mobile) { menuOpen = false; onStageLines(file, "index") }
            }
            if (discardable) {
                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(tokens.border.color))
                ContextRow("Discard changes…", enabled = !busy, destructive = true, mobile = mobile) { menuOpen = false; onDiscard(file) }
            }
        }
    }

    val rowHover = remember { MutableInteractionSource() }
    val rowHovered by rowHover.collectIsHoveredAsState()

    Box(
        Modifier
            .fillMaxWidth()
            .hoverable(rowHover)
            .background(if (!mobile && rowHovered) tokens.panel2.color else Color.Transparent),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (mobile) 56.dp else 40.dp)
                .rightClick { menuOpen = true }
                .combinedClickable(
                    onClick = { onOpen(file) },
                    onLongClick = { menuOpen = true },
                )
                .padding(start = (depth * 12 + 10).dp)
                .testTag("git-change-row-${file.path}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FileIcon(
                name = name,
                size = 16.dp,
            )
            Column(
                modifier = Modifier.weight(1f).padding(vertical = 3.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                StartTruncatedText(
                    text = name,
                    style = PpmFont.sans(
                        if (mobile) 14 else 13,
                        FontWeight.Medium,
                        lineHeight = if (mobile) 18 else 17,
                        color = if (letter == "D") tokens.text2.color else tokens.text.color,
                    ).copy(
                        textDecoration = if (letter == "D") TextDecoration.LineThrough else TextDecoration.None,
                    ),
                )
                if (!nested && dir.isNotEmpty()) {
                    StartTruncatedText(
                        text = dir,
                        style = PpmFont.sans(if (mobile) 12 else 11, lineHeight = 14, color = tokens.text3.color),
                    )
                }
            }
            if (conflict) {
                Box(
                    modifier = Modifier
                        .height(if (mobile) 28.dp else 22.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(tokens.error.opacity(0.12).color)
                        .clickable { onOpen(file) }
                        .padding(horizontal = 6.dp)
                        .testTag("git-resolve-${file.path}"),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = "Resolve",
                        style = PpmFont.sans(11, FontWeight.Medium, lineHeight = 14, color = tokens.error.color),
                    )
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    BlockDots(file)
                    LineCounts(file)
                }
            }
            StatusTile(file)
            CheckCell(
                state = state,
                disabled = busy,
                tag = "git-change-check-${file.path}",
                onToggle = { onToggle(file) },
            )
        }

        // Pointer-only row shortcuts (`git-change-row.tsx:131-159`): hidden (`alpha = 0f`) until hovered, overlaid before CheckCell.
        if (!mobile && !conflict) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 30.dp)
                    .alpha(if (rowHovered) 1f else 0f)
                    .background(if (rowHovered) tokens.panel2.color else Color.Transparent)
                    .padding(start = 6.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .semantics { contentDescription = "Open $name" }
                        .clickable { onOpenFile(file) }
                        .testTag("git-row-open-file-${file.path}"),
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(ProductIcon.FileText, size = 14.dp, tint = tokens.text3.color)
                }
                val canDiscardRow = discardable && !busy
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .alpha(if (canDiscardRow) 1f else 0.4f)
                        .semantics { contentDescription = "Discard changes to $name" }
                        .clickable(enabled = canDiscardRow) { onDiscard(file) }
                        .testTag("git-row-discard-${file.path}"),
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(ProductIcon.Trash2, size = 14.dp, tint = tokens.text3.color)
                }
            }
        }

        if (menuOpen) {
            if (mobile) {
                androidx.compose.ui.window.Dialog(
                    onDismissRequest = { menuOpen = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false),
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().clickable { menuOpen = false },
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        val sheetShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(sheetShape)
                                .background(tokens.bgSolid.color)
                                .cssBorder(tokens.border.color, sheetShape)
                                .padding(vertical = 8.dp)
                                .testTag("git-change-context-menu"),
                        ) {
                            MenuItems()
                        }
                    }
                }
            } else {
                Popup(
                    alignment = Alignment.BottomEnd,
                    onDismissRequest = { menuOpen = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    val popShape = RoundedCornerShape(6.dp)
                    Column(
                        modifier = Modifier
                            .width(196.dp)
                            .clip(popShape)
                            .background(tokens.bgSolid.color)
                            .cssBorder(tokens.border.color, popShape)
                            .padding(vertical = 4.dp)
                            .testTag("git-change-context-menu"),
                    ) {
                        MenuItems()
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextRow(
    label: String,
    enabled: Boolean = true,
    destructive: Boolean = false,
    mobile: Boolean = false,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (mobile) 44.dp else 28.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = label,
            style = PpmFont.sans(if (mobile) 14 else 12, lineHeight = if (mobile) 18 else 16, color = if (destructive) tokens.error.color else tokens.text.color),
        )
    }
}
