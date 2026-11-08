package com.hienlh.ppm.app.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.client.ChangedFile
import com.hienlh.ppm.client.GitChanges
import com.hienlh.ppm.client.gitChanges
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
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.state.git.CheckState
import com.hienlh.ppm.state.git.GitChangesView
import com.hienlh.ppm.state.git.GitFileTree
import com.hienlh.ppm.state.git.GitRepoScope
import com.hienlh.ppm.state.git.GitTreeNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

private data class ConfirmState(
    val title: String,
    val detail: String,
    val confirmLabel: String,
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
            BasicText("Select a project to view Git status", style = PpmFont.sans(12, lineHeight = 16, color = tokens.text3.color))
        }
        return
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

    var changes by remember(project, repo?.path) { mutableStateOf(model.gitChangesCache[project]) }
    var loading by remember(project, repo?.path) { mutableStateOf(changes == null) }
    var error by remember(project, repo?.path) { mutableStateOf<String?>(null) }
    var busyAction by remember { mutableStateOf<String?>(null) }
    var refreshSignal by remember { mutableIntStateOf(0) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<ConfirmState?>(null) }
    var hunkTarget by remember { mutableStateOf<HunkModalTarget?>(null) }

    val drafts by model.commitDrafts.drafts.collectAsState()
    val commitMessage = drafts[com.hienlh.ppm.state.git.CommitDraftSync.draftKey(project, repo?.path)] ?: ""

    LaunchedEffect(project, repo?.path, api) {
        if (api != null && repo != null && !needsPick && !noRepo) {
            model.commitDrafts.load(api, project, repo)
        }
    }

    suspend fun loadChanges() {
        val client = api ?: return
        if (repo == null || needsPick || noRepo) {
            loading = false
            return
        }
        try {
            val fresh = client.gitChanges(project, repo)
            changes = fresh
            error = null
            model.applyGitChanges(project, repo, fresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = model.describe(e)
        } finally {
            loading = false
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
            while (true) {
                if (model.windowVisible) {
                    loadChanges()
                }
                delay(5_000)
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
    val conflicts = remember(files) { files.filter { it.conflict != null } }
    val normalFiles = remember(files) { files.filter { it.conflict == null } }
    val totals = remember(files) { GitChangesView.changeTotals(files) }
    val branch = changes?.branch ?: com.hienlh.ppm.client.GitBranchState(null, null, null, false, 0, 0)
    val operation = changes?.operation
    val lastCommit = changes?.lastCommit
    val mode = remember(branch) { GitChangesView.syncMode(branch) }

    fun runGit(key: String, fn: suspend () -> Unit) {
        if (busyAction != null || api == null) return
        busyAction = key
        scope.launch {
            try {
                fn()
                loadChanges()
                refreshSignal++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.actionError(model.describe(e))
            } finally {
                busyAction = null
            }
        }
    }

    fun toggleFile(file: ChangedFile) {
        if (file.conflict != null || api == null) return
        val state = GitChangesView.fileCheckState(file)
        scope.launch {
            try {
                if (state == CheckState.ALL) {
                    api.gitUnstage(project, GitChangesView.unstagePaths(file), repo)
                } else {
                    api.gitStage(project, listOf(file.path), repo)
                }
                loadChanges()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.actionError(model.describe(e))
            }
        }
    }

    fun toggleBatch(batch: List<ChangedFile>) {
        val eligible = batch.filter { it.conflict == null }
        if (eligible.isEmpty() || api == null) return
        val state = GitChangesView.allCheckState(eligible)
        scope.launch {
            try {
                if (state == CheckState.ALL) {
                    api.gitUnstage(project, eligible.flatMap(GitChangesView::unstagePaths), repo)
                } else {
                    val paths = eligible
                        .filter { GitChangesView.fileCheckState(it) != CheckState.ALL }
                        .map { it.path }
                    api.gitStage(project, paths, repo)
                }
                loadChanges()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.actionError(model.describe(e))
            }
        }
    }

    fun performDiscard(paths: List<String>, what: String) {
        runGit("discard") {
            val record = api?.gitDiscard(project, paths, repo) ?: return@runGit
            if (record.skipped.isNotEmpty()) {
                model.toast(
                    ErrorToastMessage(
                        "Discarded $what — ${record.skipped.size} large file(s) cannot be undone",
                        kind = ErrorToastMessage.Kind.WARNING,
                    ),
                )
            } else {
                model.toast(
                    ErrorToastMessage(
                        title = "Discarded changes to $what",
                        kind = ErrorToastMessage.Kind.SUCCESS,
                        durationMillis = 8_000L,
                        action = ErrorToastMessage.Action("Undo") {
                            scope.launch {
                                try {
                                    api?.gitDiscardUndo(project, record.id, repo)
                                    loadChanges()
                                    model.toast(ErrorToastMessage("Restored discarded changes", kind = ErrorToastMessage.Kind.SUCCESS))
                                } catch (e: Exception) {
                                    model.actionError(model.describe(e))
                                }
                            }
                        },
                    ),
                )
            }
        }
    }

    fun askDiscardFiles(targets: List<ChangedFile>, label: String? = null) {
        val eligible = targets.filter(GitChangesView::hasUnstaged)
        if (eligible.isEmpty()) return
        val summary = GitChangesView.discardSummary(eligible)
        val what = label ?: if (eligible.size == 1) {
            eligible[0].path.substringAfterLast('/')
        } else {
            "${eligible.size} files"
        }
        confirm = ConfirmState(
            title = "Discard changes to $what?",
            detail = "This will $summary. You can undo within a few minutes from the notification.",
            confirmLabel = "Discard",
            destructive = true,
            run = { performDiscard(eligible.map { it.path }, what) },
        )
    }

    fun markResolved(file: ChangedFile) {
        val client = api ?: return
        val stageNow = {
            runGit("resolve") {
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
                        detail = "The file still contains <<<<<<<, =======, or >>>>>>> lines. Stage it as resolved anyway?",
                        confirmLabel = "Mark as resolved",
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

    fun undoCommit(hash: String) {
        if (busyAction != null) {
            model.toast(ErrorToastMessage("Wait for the current Git operation to finish", kind = ErrorToastMessage.Kind.WARNING))
            return
        }
        runGit("undo-commit") {
            api?.gitUndoCommit(project, hash, repo)
            model.toast(ErrorToastMessage("Undid commit ${hash.take(7)} — changes are back in your list", kind = ErrorToastMessage.Kind.SUCCESS))
        }
    }

    fun handleCommit(amend: Boolean, signoff: Boolean, andPush: Boolean) {
        val msg = commitMessage.trim()
        if (msg.isEmpty() || api == null) return
        val key = when {
            andPush -> "commit-push"
            amend -> "amend"
            else -> "commit"
        }
        val count = totals.commitCount
        runGit(key) {
            model.commitDrafts.flush(api, project, repo)
            val res = api.gitCommit(project, msg, amend = amend, signoff = signoff, repo = repo)
            val hash = res.hash
            val short = hash.take(7)
            model.commitDrafts.consumed(project, repo, commitMessage)
            if (andPush) {
                if (mode == "publish") {
                    api.gitPublish(project, repo)
                    model.toast(ErrorToastMessage("Committed $short and published branch", kind = ErrorToastMessage.Kind.SUCCESS))
                } else {
                    api.gitPush(project, repo = repo)
                    model.toast(ErrorToastMessage("Committed $short and pushed", kind = ErrorToastMessage.Kind.SUCCESS))
                }
            } else if (amend) {
                model.toast(ErrorToastMessage("Amended $short", kind = ErrorToastMessage.Kind.SUCCESS))
            } else {
                model.toast(
                    ErrorToastMessage(
                        title = "Committed $short · $count ${if (count == 1) "file" else "files"}",
                        kind = ErrorToastMessage.Kind.SUCCESS,
                        durationMillis = 8_000L,
                        action = ErrorToastMessage.Action("Undo") { undoCommit(hash) },
                    ),
                )
            }
        }
    }

    fun handleSync() {
        when (mode) {
            "publish" -> runGit("publish") {
                api?.gitPublish(project, repo)
                model.toast(ErrorToastMessage("Published ${branch.head ?: "branch"}", kind = ErrorToastMessage.Kind.SUCCESS))
            }
            "pull" -> runGit("pull") {
                api?.gitPull(project, rebase = false, repo = repo)
                model.toast(ErrorToastMessage("Pulled latest commits", kind = ErrorToastMessage.Kind.SUCCESS))
            }
            "push" -> runGit("push") {
                api?.gitPush(project, repo = repo)
                model.toast(ErrorToastMessage("Pushed ${branch.ahead} ${if (branch.ahead == 1) "commit" else "commits"}", kind = ErrorToastMessage.Kind.SUCCESS))
            }
            "sync" -> runGit("sync") {
                api?.gitPull(project, rebase = false, repo = repo)
                api?.gitPush(project, repo = repo)
                model.toast(ErrorToastMessage("Synced with remote", kind = ErrorToastMessage.Kind.SUCCESS))
            }
            "synced" -> runGit("fetch") {
                api?.gitFetch(project, repo = repo)
                val fresh = api?.gitChanges(project, repo)
                if (fresh != null) {
                    changes = fresh
                    model.applyGitChanges(project, repo, fresh)
                }
                val behind = fresh?.branch?.behind ?: 0
                model.toast(
                    ErrorToastMessage(
                        if (behind > 0) "Fetched — $behind new ${if (behind == 1) "commit" else "commits"} to pull" else "Fetched — nothing new",
                        kind = ErrorToastMessage.Kind.SUCCESS,
                    ),
                )
            }
        }
    }

    fun handleStashAll() {
        runGit("stash") {
            val entry = api?.gitStash(project, includeUntracked = true, repo = repo) ?: return@runGit
            model.toast(
                ErrorToastMessage(
                    title = "Stashed changes as stash@{${entry.index}}",
                    kind = ErrorToastMessage.Kind.SUCCESS,
                    durationMillis = 8_000L,
                    action = ErrorToastMessage.Action("Undo") {
                        runGit("stash-pop") {
                            api?.gitStashAction(project, "pop", entry.index, entry.hash, repo)
                        }
                    },
                ),
            )
        }
    }

    fun openReview(file: ChangedFile? = null) {
        if (file?.conflict != null) {
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

    fun openHistory() {
        model.openGitHistoryTab(project)
        onNavigate?.invoke()
    }

    fun openGitGraph() {
        model.dispatchExtCommand("git-graph.view")
        onNavigate?.invoke()
    }

    fun openBranchReview() {
        model.openBranchReviewTab(project)
        onNavigate?.invoke()
    }

    val showComposer = files.isNotEmpty() && (operation == null || operation.kind == "merge")

    Column(
        modifier = Modifier.fillMaxSize().testTag("git-status-panel"),
    ) {
        // Panel header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .edge(Edge.Bottom, tokens.border.color)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BasicText(
                text = "Source Control",
                style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.text2.color),
                modifier = Modifier.weight(1f),
            )
            // Flat / Tree view toggle
            val isTree = model.gitStatusViewMode == "tree"
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(4.dp))
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
            // Git Graph button
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = ::openGitGraph)
                    .testTag("git-graph-button"),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.GitBranch, size = 14.dp, tint = tokens.text2.color)
            }
            // History button
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = ::openHistory)
                    .testTag("git-history-button"),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.History, size = 14.dp, tint = tokens.text2.color)
            }
            // More menu
            Box {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { moreMenuOpen = !moreMenuOpen }
                        .testTag("git-more-button"),
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(ProductIcon.MoreHorizontal, size = 14.dp, tint = tokens.text2.color)
                }
                if (moreMenuOpen) {
                    Popup(
                        alignment = Alignment.BottomEnd,
                        onDismissRequest = { moreMenuOpen = false },
                        properties = PopupProperties(focusable = true),
                    ) {
                        val popShape = RoundedCornerShape(6.dp)
                        val discardable = remember(files) { files.filter(GitChangesView::hasUnstaged) }
                        Column(
                            modifier = Modifier
                                .width(196.dp)
                                .clip(popShape)
                                .background(tokens.bgSolid.color)
                                .cssBorder(tokens.border.color, popShape)
                                .padding(vertical = 4.dp),
                        ) {
                            MoreMenuItem(ProductIcon.RefreshCw, "Fetch", enabled = busyAction == null) {
                                moreMenuOpen = false
                                runGit("fetch") {
                                    api?.gitFetch(project, repo = repo)
                                    model.toast(ErrorToastMessage("Fetched from remote", kind = ErrorToastMessage.Kind.SUCCESS))
                                }
                            }
                            MoreMenuItem(
                                ProductIcon.ArrowDown,
                                "Pull with rebase",
                                enabled = busyAction == null && branch.head != null && branch.upstream != null,
                            ) {
                                moreMenuOpen = false
                                runGit("pull-rebase") {
                                    api?.gitPull(project, rebase = true, repo = repo)
                                    model.toast(ErrorToastMessage("Pulled with rebase", kind = ErrorToastMessage.Kind.SUCCESS))
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
                                    askDiscardFiles(discardable, "all files")
                                }
                            }
                        }
                    }
                }
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
                        busyAction = busyAction,
                        onOpenPicker = { model.openBranchPicker(project) },
                        onSync = ::handleSync,
                    )
                }

                // Operation banner
                if (operation != null) {
                    val noun = GitChangesView.OPERATION_NOUN[operation.kind] ?: operation.kind
                    GitOperationBanner(
                        operation = operation,
                        branch = branch,
                        conflicts = conflicts.size,
                        busyAction = busyAction,
                        onContinue = {
                            runGit("op:continue") {
                                api?.gitOperation(project, "continue", repo)
                                model.toast(ErrorToastMessage("Finished $noun", kind = ErrorToastMessage.Kind.SUCCESS))
                            }
                        },
                        onAbort = {
                            confirm = ConfirmState(
                                title = "Abort $noun?",
                                detail = "The repository will return to the state before the $noun started.",
                                confirmLabel = "Abort $noun",
                                destructive = true,
                                run = {
                                    runGit("op:abort") {
                                        api?.gitOperation(project, "abort", repo)
                                        model.toast(ErrorToastMessage("Aborted $noun", kind = ErrorToastMessage.Kind.SUCCESS))
                                    }
                                },
                            )
                        },
                    )
                }

                // Tablet composer sits above the change list
                if (!mobile && showComposer) {
                    GitCommitComposer(
                        message = commitMessage,
                        onMessageChange = { model.commitDrafts.setMessage(api, project, repo, it) },
                        totals = totals,
                        operation = operation,
                        lastCommit = lastCommit,
                        syncMode = mode,
                        busyAction = busyAction,
                        onCommit = ::handleCommit,
                        onUndoLast = { lastCommit?.let { undoCommit(it.hash) } },
                        isBottom = false,
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
                                BasicText("Reading repository…", style = PpmFont.sans(12, lineHeight = 16, color = tokens.text3.color))
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
                                        .height(26.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .cssBorder(tokens.border.color, RoundedCornerShape(6.dp))
                                        .clickable(onClick = ::triggerRefresh)
                                        .padding(horizontal = 10.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    BasicText("Try again", style = PpmFont.sans(12, lineHeight = 16, color = tokens.text.color))
                                }
                            }
                        }
                        files.isEmpty() -> {
                            CleanState(
                                last = lastCommit,
                                operation = operation,
                                busy = busyAction != null,
                                onUndo = { lastCommit?.let { undoCommit(it.hash) } },
                                onOpenHistory = ::openHistory,
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
                                    onToggleFile = ::toggleFile,
                                    onToggleBatch = ::toggleBatch,
                                    onOpen = ::openReview,
                                    onOpenDiff = ::openDiff,
                                    onOpenFile = ::openFile,
                                    onDiscard = ::askDiscardFiles,
                                    onStageLines = { f, s -> hunkTarget = HunkModalTarget(f, s) },
                                    onMarkResolved = ::markResolved,
                                    onReviewAll = { openReview(conflicts.firstOrNull()) },
                                )
                            }
                            if (normalFiles.isNotEmpty()) {
                                ChangeGroupSection(
                                    model = model,
                                    label = if (operation?.kind == "merge") "Merged" else "Changes",
                                    files = normalFiles,
                                    errorTone = false,
                                    showCheckAll = operation == null,
                                    viewMode = model.gitStatusViewMode,
                                    onToggleFile = ::toggleFile,
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
                            if (changes?.truncated == true) {
                                BasicText(
                                    text = "Only the first ${files.size} changes are listed.",
                                    style = PpmFont.sans(11, lineHeight = 14, color = tokens.text3.color),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }

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

                // Phone composer sits in the bottom thumb-zone
                if (mobile && showComposer) {
                    GitCommitComposer(
                        message = commitMessage,
                        onMessageChange = { model.commitDrafts.setMessage(api, project, repo, it) },
                        totals = totals,
                        operation = operation,
                        lastCommit = lastCommit,
                        syncMode = mode,
                        busyAction = busyAction,
                        onCommit = ::handleCommit,
                        onUndoLast = { lastCommit?.let { undoCommit(it.hash) } },
                        isBottom = true,
                    )
                }
            }
        }
    }

    confirm?.let { c ->
        GitConfirm(
            title = c.title,
            detail = c.detail,
            confirmLabel = c.confirmLabel,
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
    val fg = if (destructive) tokens.error.color else tokens.text.color
    val iconColor = if (destructive) tokens.error.color else tokens.text3.color
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
    onToggleFile: (ChangedFile) -> Unit,
    onToggleBatch: (List<ChangedFile>) -> Unit,
    onOpen: (ChangedFile) -> Unit,
    onOpenDiff: (ChangedFile) -> Unit,
    onOpenFile: (ChangedFile) -> Unit,
    onDiscard: (List<ChangedFile>, String?) -> Unit,
    onStageLines: (ChangedFile, String) -> Unit,
    onMarkResolved: (ChangedFile) -> Unit,
    onReviewAll: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var open by remember { mutableStateOf(true) }
    val allState = remember(files) { GitChangesView.allCheckState(files) }

    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable { open = !open },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PpmIcon(
                    if (open) ProductIcon.ChevronDown else ProductIcon.ChevronRight,
                    size = 14.dp,
                    tint = tokens.text2.color,
                )
                BasicText(
                    text = label.uppercase(),
                    style = PpmFont.sans(
                        11,
                        FontWeight.SemiBold,
                        lineHeight = 14,
                        color = if (errorTone) tokens.error.color else tokens.text2.color,
                    ),
                )
                CountChip(files.size, errorTone = errorTone)
            }
            Box(
                modifier = Modifier
                    .height(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onReviewAll)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText("Review", style = PpmFont.sans(11, lineHeight = 14, color = tokens.text2.color))
            }
            if (showCheckAll) {
                CheckCell(
                    state = allState,
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
                        onToggleFile = onToggleFile,
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
                        onToggle = onToggleFile,
                        onOpen = onOpen,
                        onOpenDiff = onOpenDiff,
                        onOpenFile = onOpenFile,
                        onDiscard = { onDiscard(listOf(it), null) },
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
    onToggleFile: (ChangedFile) -> Unit,
    onToggleBatch: (List<ChangedFile>) -> Unit,
    onOpen: (ChangedFile) -> Unit,
    onOpenDiff: (ChangedFile) -> Unit,
    onOpenFile: (ChangedFile) -> Unit,
    onDiscard: (List<ChangedFile>, String?) -> Unit,
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
            onToggle = onToggleFile,
            onOpen = onOpen,
            onOpenDiff = onOpenDiff,
            onOpenFile = onOpenFile,
            onDiscard = { onDiscard(listOf(it), null) },
            onStageLines = onStageLines,
            onMarkResolved = onMarkResolved,
        )
        return
    }

    val tokens = LocalPpmTokens.current
    val open = closedPaths[node.path] != true
    val leafFiles = remember(node) { GitFileTree.collectFiles(node) }
    val folderState = remember(leafFiles) { GitChangesView.allCheckState(leafFiles) }
    val canDiscard = remember(leafFiles) { leafFiles.any(GitChangesView::hasUnstaged) }
    val (prefix, leaf) = remember(node.name) { GitFileTree.splitFolderName(node.name) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .clickable { closedPaths[node.path] = open }
            .padding(start = (depth * 12 + 6).dp, end = 8.dp)
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
            TruncatedText(leaf, style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.text2.color))
        }
        if (canDiscard) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onDiscard(leafFiles, node.name) },
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.Trash2, size = 12.dp, tint = tokens.text3.color)
            }
        }
        CountChip(node.fileCount)
        CheckCell(
            state = folderState,
            disabled = leafFiles.all { it.conflict != null },
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
                onToggleFile = onToggleFile,
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
    onToggle: (ChangedFile) -> Unit,
    onOpen: (ChangedFile) -> Unit,
    onOpenDiff: (ChangedFile) -> Unit,
    onOpenFile: (ChangedFile) -> Unit,
    onDiscard: (ChangedFile) -> Unit,
    onStageLines: (ChangedFile, String) -> Unit,
    onMarkResolved: (ChangedFile) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val (name, dir) = remember(file.path) { GitChangesView.splitPath(file.path) }
    val state = remember(file) { GitChangesView.fileCheckState(file) }
    val letter = remember(file) { GitChangesView.changeLetter(file) }
    val conflict = file.conflict != null
    var menuOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .rightClick { menuOpen = true }
                .combinedClickable(
                    onClick = { onOpen(file) },
                    onLongClick = { menuOpen = true },
                )
                .padding(start = (depth * 12 + 8).dp, end = 8.dp)
                .testTag("git-change-row-${file.path}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FileIcon(
                name = name,
                size = 14.dp,
            )
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TruncatedText(
                    text = name,
                    style = PpmFont.sans(
                        12,
                        lineHeight = 16,
                        color = if (letter == "D") tokens.text2.color else tokens.text.color,
                    ).copy(
                        textDecoration = if (letter == "D") TextDecoration.LineThrough else TextDecoration.None,
                    ),
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!nested && dir.isNotEmpty()) {
                    TruncatedText(
                        text = dir,
                        style = PpmFont.sans(11, lineHeight = 14, color = tokens.text3.color),
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BlockDots(file)
                LineCounts(file)
            }
            StatusTile(file)
            CheckCell(
                state = state,
                disabled = conflict,
                tag = "git-change-check-${file.path}",
                onToggle = { onToggle(file) },
            )
        }

        if (menuOpen) {
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
                    if (conflict) {
                        ContextRow("Resolve conflict") { menuOpen = false; onOpen(file) }
                        ContextRow("Open file") { menuOpen = false; onOpenFile(file) }
                        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(tokens.border.color))
                        ContextRow("Mark as resolved") { menuOpen = false; onMarkResolved(file) }
                    } else {
                        ContextRow("Review changes") { menuOpen = false; onOpen(file) }
                        ContextRow("Open diff") { menuOpen = false; onOpenDiff(file) }
                        ContextRow("Open file") { menuOpen = false; onOpenFile(file) }
                        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(tokens.border.color))
                        if (state != CheckState.ALL) {
                            ContextRow("Stage file") { menuOpen = false; onToggle(file) }
                        }
                        if (state != CheckState.NONE) {
                            ContextRow("Unstage file") { menuOpen = false; onToggle(file) }
                        }
                        if (file.unstaged != null) {
                            ContextRow("Stage lines…") { menuOpen = false; onStageLines(file, "worktree") }
                        }
                        if (file.staged != null) {
                            ContextRow("Unstage lines…") { menuOpen = false; onStageLines(file, "index") }
                        }
                        if (GitChangesView.hasUnstaged(file)) {
                            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(tokens.border.color))
                            ContextRow("Discard changes…", destructive = true) { menuOpen = false; onDiscard(file) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextRow(label: String, destructive: Boolean = false, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = label,
            style = PpmFont.sans(12, lineHeight = 16, color = if (destructive) tokens.error.color else tokens.text.color),
        )
    }
}
