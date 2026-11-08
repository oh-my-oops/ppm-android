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

    var changes by remember(project, repo?.path) { mutableStateOf(model.gitChangesCache[project]) }
    var loading by remember(project, repo?.path) { mutableStateOf(changes == null) }
    var error by remember(project, repo?.path) { mutableStateOf<String?>(null) }
    var busyAction by remember { mutableStateOf<String?>(null) }
    var refreshSignal by remember { mutableIntStateOf(0) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<ConfirmState?>(null) }
    var hunkTarget by remember { mutableStateOf<HunkModalTarget?>(null) }
    var changesRequestId by remember { mutableIntStateOf(0) }
    val writeMutex = remember { kotlinx.coroutines.sync.Mutex() }

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
        val reqId = ++changesRequestId
        val reqProject = project
        val reqRepo = repo
        try {
            val fresh = client.gitChanges(reqProject, reqRepo)
            if (reqId != changesRequestId) return
            changes = fresh
            error = null
            model.applyGitChanges(reqProject, reqRepo, fresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (reqId != changesRequestId) return
            error = model.describe(e)
        } finally {
            if (reqId == changesRequestId) loading = false
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
        val client = api ?: return
        scope.launch {
            writeMutex.lock()
            busyAction = key
            try {
                if (model.api === client) {
                    fn()
                    loadChanges()
                    refreshSignal++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.actionError(model.describe(e))
            } finally {
                busyAction = null
                writeMutex.unlock()
            }
        }
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
                        detail = "Marking it resolved stages the file as it is, markers and all.",
                        confirmLabel = "Mark resolved",
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
        if (file.conflict != null) {
            markResolved(file)
            return
        }
        runGit("stage") {
            api?.gitStage(project, listOf(file.path), repo)
        }
    }

    fun unstageFile(file: ChangedFile) {
        if (file.conflict != null) return
        runGit("unstage") {
            api?.gitUnstage(project, GitChangesView.unstagePaths(file), repo)
        }
    }

    fun toggleFile(file: ChangedFile) {
        if (file.conflict != null) {
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
        val eligible = batch.filter { it.conflict == null }
        if (eligible.isEmpty() || api == null) return
        val state = GitChangesView.allCheckState(eligible)
        if (state == CheckState.ALL) {
            runGit("unstage") {
                api.gitUnstage(project, eligible.flatMap(GitChangesView::unstagePaths), repo)
            }
        } else {
            val paths = eligible.map { it.path }
            runGit("stage") {
                api.gitStage(project, paths, repo)
            }
        }
    }

    fun performDiscard(paths: List<String>, what: String) {
        runGit("discard") {
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
                            model.scope.launch {
                                try {
                                    api?.gitDiscardUndo(project, record.id, repo)
                                    model.notifyGitChanged(project)
                                    model.toast(ErrorToastMessage("Restored $kept", kind = ErrorToastMessage.Kind.SUCCESS))
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    model.actionError(model.describe(e))
                                }
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
        val client = api ?: return
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
        model.scope.launch {
            writeMutex.lock()
            busyAction = "undo-commit"
            try {
                client.gitUndoCommit(project, hash, repo)
                model.commitDrafts.load(client, project, repo)
                model.notifyGitChanged(project)
                model.toast(ErrorToastMessage("Commit undone — its changes are staged again", kind = ErrorToastMessage.Kind.INFO))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.actionError(model.describe(e))
            } finally {
                busyAction = null
                writeMutex.unlock()
            }
        }
    }

    fun handleCommit(amend: Boolean, signoff: Boolean, andPush: Boolean) {
        val msg = commitMessage.trim()
        if ((!amend && msg.isEmpty()) || (amend && msg.isEmpty() && totals.filesStaged == 0) || api == null) return
        val key = when {
            andPush -> "commit-push"
            amend -> "amend"
            else -> "commit"
        }
        val count = totals.filesStaged
        runGit(key) {
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
            if (andPush) {
                if (mode == "publish") {
                    api.gitPublish(project, repo)
                    model.toast(ErrorToastMessage("Published ${branch.head ?: "branch"}", kind = ErrorToastMessage.Kind.SUCCESS))
                } else {
                    api.gitPush(project, repo = repo)
                    val upstream = branch.upstream ?: "the remote"
                    model.toast(ErrorToastMessage("Pushed to $upstream", kind = ErrorToastMessage.Kind.SUCCESS))
                }
            }
        }
    }

    fun handleSync() {
        val upstream = branch.upstream ?: "the remote"
        when (mode) {
            "publish" -> runGit("publish") {
                api?.gitPublish(project, repo)
                model.toast(ErrorToastMessage("Published ${branch.head ?: "branch"}", kind = ErrorToastMessage.Kind.SUCCESS))
            }
            "pull" -> runGit("pull") {
                api?.gitPull(project, rebase = false, repo = repo)
                model.toast(ErrorToastMessage("Pulled ${branch.behind} ${if (branch.behind == 1) "commit" else "commits"} from $upstream", kind = ErrorToastMessage.Kind.SUCCESS))
            }
            "push" -> runGit("push") {
                api?.gitPush(project, repo = repo)
                model.toast(
                    ErrorToastMessage(
                        if (branch.ahead > 0) "Pushed ${branch.ahead} ${if (branch.ahead == 1) "commit" else "commits"} to $upstream" else "Pushed to $upstream",
                        kind = ErrorToastMessage.Kind.SUCCESS,
                    ),
                )
            }
            "sync" -> runGit("sync") {
                api?.gitPull(project, rebase = false, repo = repo)
                api?.gitPush(project, repo = repo)
                model.toast(ErrorToastMessage("In sync with $upstream", kind = ErrorToastMessage.Kind.SUCCESS))
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
                        kind = ErrorToastMessage.Kind.INFO,
                    ),
                )
            }
        }
    }

    fun handleStashAll() {
        val fileCount = totals.files
        runGit("stash") {
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
                        model.scope.launch {
                            writeMutex.lock()
                            busyAction = "stash-pop"
                            try {
                                api?.gitStashAction(project, "pop", entry.index, entry.hash, repo)
                                model.notifyGitChanged(project)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                model.actionError(model.describe(e))
                            } finally {
                                busyAction = null
                                writeMutex.unlock()
                            }
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

    val showComposer = files.isNotEmpty() && operation == null

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
                    val discardable = remember(files) { files.filter(GitChangesView::hasUnstaged) }
                    @Composable
                    fun MoreMenuItems() {
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
                                askDiscardFiles(discardable)
                            }
                        }
                    }
                    if (mobile) {
                        androidx.compose.ui.window.Dialog(onDismissRequest = { moreMenuOpen = false }) {
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
                                    onStageFile = ::stageFile,
                                    onUnstageFile = ::unstageFile,
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
    onStageFile: (ChangedFile) -> Unit = onToggleFile,
    onUnstageFile: (ChangedFile) -> Unit = onToggleFile,
    onToggleBatch: (List<ChangedFile>) -> Unit,
    onOpen: (ChangedFile) -> Unit,
    onOpenDiff: (ChangedFile) -> Unit,
    onOpenFile: (ChangedFile) -> Unit,
    onDiscard: (List<ChangedFile>) -> Unit,
    onStageLines: (ChangedFile, String) -> Unit,
    onMarkResolved: (ChangedFile) -> Unit,
    onReviewAll: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    var open by remember { mutableStateOf(true) }
    val allState = remember(files) { GitChangesView.allCheckState(files) }

    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (mobile) 44.dp else 26.dp)
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
                    .height(if (mobile) 44.dp else 20.dp)
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
            .height(if (mobile) 44.dp else 26.dp)
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
            StartTruncatedText(leaf, style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.text2.color))
        }
        if (canDiscard) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onDiscard(leafFiles) },
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
    val conflict = file.conflict != null
    var menuOpen by remember { mutableStateOf(false) }

    @Composable
    fun MenuItems() {
        if (conflict) {
            ContextRow("Resolve conflict", mobile = mobile) { menuOpen = false; onOpen(file) }
            ContextRow("Open file", mobile = mobile) { menuOpen = false; onOpenFile(file) }
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(tokens.border.color))
            ContextRow("Mark as resolved", mobile = mobile) { menuOpen = false; onMarkResolved(file) }
        } else {
            ContextRow("Review changes", mobile = mobile) { menuOpen = false; onOpen(file) }
            ContextRow("Open diff", mobile = mobile) { menuOpen = false; onOpenDiff(file) }
            ContextRow("Open file", mobile = mobile) { menuOpen = false; onOpenFile(file) }
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(tokens.border.color))
            if (state != CheckState.ALL) {
                ContextRow("Stage file", mobile = mobile) { menuOpen = false; onStage(file) }
            }
            if (state != CheckState.NONE) {
                ContextRow("Unstage file", mobile = mobile) { menuOpen = false; onUnstage(file) }
            }
            if (file.unstaged != null) {
                ContextRow("Stage lines…", mobile = mobile) { menuOpen = false; onStageLines(file, "worktree") }
            }
            if (file.staged != null) {
                ContextRow("Unstage lines…", mobile = mobile) { menuOpen = false; onStageLines(file, "index") }
            }
            if (GitChangesView.hasUnstaged(file)) {
                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(tokens.border.color))
                ContextRow("Discard changes…", destructive = true, mobile = mobile) { menuOpen = false; onDiscard(file) }
            }
        }
    }

    Box(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (mobile) {
                        Modifier.padding(vertical = 3.dp).height(44.dp)
                    } else {
                        Modifier.height(28.dp)
                    },
                )
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
            if (mobile && !nested && dir.isNotEmpty()) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                ) {
                    StartTruncatedText(
                        text = name,
                        style = PpmFont.sans(
                            12,
                            lineHeight = 16,
                            color = if (letter == "D") tokens.text2.color else tokens.text.color,
                        ).copy(
                            textDecoration = if (letter == "D") TextDecoration.LineThrough else TextDecoration.None,
                        ),
                    )
                    StartTruncatedText(
                        text = dir,
                        style = PpmFont.sans(11, lineHeight = 14, color = tokens.text3.color),
                    )
                }
            } else {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StartTruncatedText(
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
                        StartTruncatedText(
                            text = dir,
                            style = PpmFont.sans(11, lineHeight = 14, color = tokens.text3.color),
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BlockDots(file)
                    LineCounts(file)
                }
                if (!mobile) {
                    if (GitChangesView.hasUnstaged(file)) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { onDiscard(file) }
                                .testTag("git-row-discard-${file.path}"),
                            contentAlignment = Alignment.Center,
                        ) {
                            PpmIcon(ProductIcon.Trash2, size = 12.dp, tint = tokens.text3.color)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onOpenFile(file) }
                            .testTag("git-row-open-file-${file.path}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        PpmIcon(ProductIcon.FileCode, size = 12.dp, tint = tokens.text3.color)
                    }
                }
            }
            StatusTile(file)
            CheckCell(
                state = state,
                disabled = false,
                tag = "git-change-check-${file.path}",
                onToggle = { onToggle(file) },
            )
        }

        if (menuOpen) {
            if (mobile) {
                androidx.compose.ui.window.Dialog(onDismissRequest = { menuOpen = false }) {
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
private fun ContextRow(label: String, destructive: Boolean = false, mobile: Boolean = false, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (mobile) 44.dp else 28.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = label,
            style = PpmFont.sans(if (mobile) 14 else 12, lineHeight = if (mobile) 18 else 16, color = if (destructive) tokens.error.color else tokens.text.color),
        )
    }
}
