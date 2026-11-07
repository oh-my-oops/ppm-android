package com.hienlh.ppm.app.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.client.ChangeHunk
import com.hienlh.ppm.client.ChangedFile
import com.hienlh.ppm.client.FileChangeDetail
import com.hienlh.ppm.client.GitChanges
import com.hienlh.ppm.client.HunkSelectionItem
import com.hienlh.ppm.client.gitChanges
import com.hienlh.ppm.client.gitCommit
import com.hienlh.ppm.client.gitDiscard
import com.hienlh.ppm.client.gitDiscardHunks
import com.hienlh.ppm.client.gitDiscardUndo
import com.hienlh.ppm.client.gitFileChanges
import com.hienlh.ppm.client.gitPublish
import com.hienlh.ppm.client.gitPush
import com.hienlh.ppm.client.gitStage
import com.hienlh.ppm.client.gitStageHunks
import com.hienlh.ppm.client.gitUndoCommit
import com.hienlh.ppm.client.gitUnstage
import com.hienlh.ppm.client.gitUnstageHunks
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FileIcon
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.LucideIcon
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.state.ReviewBlocks
import com.hienlh.ppm.state.git.ChangeTotals
import com.hienlh.ppm.state.git.CommitDraftSync
import com.hienlh.ppm.state.git.DiscardedEntry
import com.hienlh.ppm.state.git.GitChangesView
import com.hienlh.ppm.state.git.GitFileReview
import com.hienlh.ppm.state.git.GitPaneItem
import com.hienlh.ppm.state.git.GitRepoScope
import com.hienlh.ppm.state.git.GitReviewBlock
import com.hienlh.ppm.state.git.GitReviewFocus
import com.hienlh.ppm.state.git.GitReviewModel
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

private const val REVIEW_TOAST_MS = 7_000L

internal data class ReviewToastData(
    val id: Long,
    val text: String,
    val undo: (() -> Unit)? = null,
)

private data class ReviewConfirmState(
    val title: String,
    val detail: String,
    val confirmLabel: String,
    val onConfirm: () -> Unit,
)

private fun pluralize(n: Int, word: String): String = "$n ${if (n == 1) word else "${word}s"}"

/**
 * Review changes tab (`git-review` -> `src/web/components/git-review/git-review-tab.tsx`).
 */
@Composable
fun GitReviewBody(model: AppModel, tab: WorkspaceTab) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val scope = rememberCoroutineScope()
    val api = model.api
    val projectName = tab.metaString("projectName")?.takeIf { it.isNotEmpty() } ?: model.selectedProject

    val selectObj = tab.metadata["select"] as? JsonObject
    val selectPath = (selectObj?.get("path") as? JsonPrimitive)?.contentOrNull
    val selectRepo = (selectObj?.get("repo") as? JsonPrimitive)?.contentOrNull
    val selectAt = (selectObj?.get("at") as? JsonPrimitive)?.longOrNull

    if (projectName == null) {
        Box(
            modifier = Modifier.fillMaxSize().padding(16.dp).testTag("git-review-no-project"),
            contentAlignment = Alignment.Center,
        ) {
            BasicText("No project selected.", style = PpmFont.sans(13, lineHeight = 18, color = tokens.text3.color))
        }
        return
    }

    LaunchedEffect(projectName, api) {
        model.loadGitRepos(projectName)
    }

    // Apply `select.repo` once and drop it from tab metadata (`git-review-tab.tsx` L49-54).
    LaunchedEffect(projectName, selectRepo, tab.id) {
        if (!selectRepo.isNullOrEmpty()) {
            model.chooseGitRepo(projectName, selectRepo)
            val rest = buildJsonObject {
                if (!selectPath.isNullOrEmpty()) put("path", JsonPrimitive(selectPath))
                if (selectAt != null) put("at", JsonPrimitive(selectAt))
            }
            model.patchTabMetadata(tab.id, "select" to rest)
        }
    }

    val discovery = model.gitDiscovery[projectName]
    val chosenPath = model.gitChosenRepoPath[projectName]
    val repo = remember(discovery, chosenPath) { GitRepoScope.resolveRepo(discovery, chosenPath) }
    val needsPick = remember(discovery, chosenPath) { GitRepoScope.needsPick(discovery, chosenPath) }
    val noRepo = remember(discovery) { GitRepoScope.hasNoRepo(discovery) }

    val held = !selectRepo.isNullOrEmpty() && selectRepo != repo?.path

    var changes by remember(projectName, repo?.path) { mutableStateOf(model.gitChangesCache[projectName]) }
    var error by remember(projectName, repo?.path) { mutableStateOf<String?>(null) }
    val details = remember(projectName, repo?.path) { mutableStateMapOf<String, FileChangeDetail>() }
    val detailSigs = remember(projectName, repo?.path) { mutableMapOf<String, String>() }
    val writingPaths = remember(projectName, repo?.path) { mutableSetOf<String>() }
    var discards by remember(projectName, repo?.path) { mutableStateOf<List<DiscardedEntry>>(emptyList()) }
    var pending by remember(projectName, repo?.path) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var want by remember(projectName, repo?.path) { mutableStateOf<GitReviewFocus?>(null) }
    var picking by remember(projectName, repo?.path) { mutableStateOf<Triple<String, String, Set<Int>>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var toast by remember(projectName, repo?.path) { mutableStateOf<ReviewToastData?>(null) }
    var toastSeq by remember { mutableLongStateOf(0L) }
    val writeMutex = remember { Mutex() }

    val drafts by model.commitDrafts.drafts.collectAsState()
    val commitMessage = drafts[CommitDraftSync.draftKey(projectName, repo?.path)] ?: ""

    LaunchedEffect(projectName, repo?.path, needsPick, noRepo, api) {
        if (api != null && !needsPick && !noRepo) {
            model.commitDrafts.load(api, projectName, repo)
        }
    }

    suspend fun refreshChanges(): GitChanges? {
        val client = api ?: return null
        if (needsPick || noRepo) return null
        return try {
            val fresh = client.gitChanges(projectName, repo)
            changes = fresh
            error = null
            model.applyGitChanges(projectName, repo, fresh)
            fresh
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = model.describe(e)
            null
        }
    }

    LaunchedEffect(projectName, repo?.path, needsPick, noRepo, api) {
        if (!needsPick && !noRepo && api != null) {
            refreshChanges()
        }
    }

    LaunchedEffect(projectName, repo?.path, api) {
        model.gitChangedEvents.filter { it == projectName }.collectLatest {
            if (writeMutex.isLocked) return@collectLatest
            refreshChanges()
        }
    }

    // Update tab title to `Changes · <branch ?? "detached HEAD">`
    val branch = changes?.branch?.head
    LaunchedEffect(tab.id, branch, changes != null) {
        if (changes != null) {
            model.retitleTab(tab.id, "Changes · ${branch ?: "detached HEAD"}")
        }
    }

    // Auto-dismiss review toast after 7 seconds
    LaunchedEffect(toast?.id) {
        val cur = toast ?: return@LaunchedEffect
        delay(REVIEW_TOAST_MS)
        if (toast?.id == cur.id) toast = null
    }

    fun showToast(text: String, undo: (() -> Unit)? = null) {
        toast = ReviewToastData(id = ++toastSeq, text = text, undo = undo)
    }

    val files = changes?.files.orEmpty()
    val detailsSnapshot = details.toMap()
    val reviews = remember(files, detailsSnapshot, discards, pending) {
        GitReviewModel.fileReviews(
            files = files,
            details = detailsSnapshot,
            discards = discards,
            pending = pending,
        )
    }
    val conflicts = remember(files) { files.filter { it.conflict != null } }
    val totals = remember(reviews) { GitReviewModel.reviewTotals(reviews) }
    val commitTotals = remember(files) { GitChangesView.changeTotals(files) }

    val focus = remember(reviews, want) { GitReviewModel.resolveFocus(reviews, want) }
    val focusedTriple = remember(reviews, focus) {
        val f = focus ?: return@remember null
        val rev = reviews.firstOrNull { it.path == f.path } ?: return@remember null
        val idx = rev.blocks.indexOfFirst { it.key == f.key }
        if (idx >= 0) Triple(rev, rev.blocks[idx], idx) else null
    }
    val items = remember(focusedTriple) {
        focusedTriple?.let { GitReviewModel.paneItems(it.first.blocks) }.orEmpty()
    }

    // Keep `want` aligned with resolved `focus`
    LaunchedEffect(focus?.path, focus?.key, focusedTriple?.second?.anchor) {
        if (focus != null && (want == null || want?.path != focus.path || want?.key != focus.key)) {
            want = focus.copy(anchor = focusedTriple?.second?.anchor)
        }
    }

    val pickSet = remember(picking, focus) {
        val p = picking
        if (p != null && focus != null && p.first == focus.path && p.second == focus.key) p.third else null
    }
    LaunchedEffect(picking, pickSet) {
        if (picking != null && pickSet == null) picking = null
    }

    // Handle initial/updated `select` metadata
    var appliedSelectAt by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(selectPath, selectAt, held, reviews, changes != null) {
        if (held || selectPath.isNullOrEmpty() || selectAt == appliedSelectAt || changes == null) return@LaunchedEffect
        val rev = reviews.firstOrNull { it.path == selectPath }
        appliedSelectAt = selectAt
        val target = rev?.let { GitReviewModel.firstInFile(it) }
        if (target != null) want = target
    }

    // Prefetch `gitFileChanges` for focused file, `nextOpen` file, and `nextFileFocus` file
    val wantedPaths = remember(reviews, focus) {
        val set = LinkedHashSet<String>()
        if (focus != null) set += focus.path
        GitReviewModel.nextOpen(reviews, focus)?.path?.let { set += it }
        GitReviewModel.nextFileFocus(reviews, focus?.path)?.path?.let { set += it }
        set.toList()
    }

    LaunchedEffect(files, wantedPaths, repo?.path, api) {
        val client = api ?: return@LaunchedEffect
        val byPath = files.associateBy { it.path }
        val stale = details.keys.filter { p ->
            val f = byPath[p]
            f == null || (detailSigs[p] != GitReviewModel.fileSignature(f) && p !in wantedPaths)
        }
        for (p in stale) {
            detailSigs.remove(p)
            details.remove(p)
        }
        for (p in wantedPaths) {
            val f = byPath[p] ?: continue
            if (f.conflict != null || p in writingPaths) continue
            val sig = GitReviewModel.fileSignature(f)
            if (detailSigs[p] == sig && p in details) continue
            detailSigs[p] = sig
            launch {
                try {
                    val d = client.gitFileChanges(projectName, f.path, f.oldPath, repo)
                    if (detailSigs[p] == sig && p !in writingPaths) {
                        details[p] = d
                    }
                } catch (_: Exception) {
                    if (detailSigs[p] == sig) detailSigs.remove(p)
                }
            }
        }
    }

    // Serial write queue (`enqueue` + `settle`)
    fun <T> enqueue(
        name: String,
        paths: List<String>,
        failure: String,
        fn: suspend () -> T,
    ) {
        scope.launch {
            writeMutex.withLock {
                busy = name
                writingPaths.addAll(paths)
                try {
                    fn()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    model.toast(ErrorToastMessage(failure, model.describe(e)))
                } finally {
                    writingPaths.removeAll(paths.toSet())
                    busy = null
                }
            }
        }
    }

    suspend fun settle(paths: List<String>, then: (() -> Unit)? = null) {
        val client = api ?: return
        val currentByPath = changes?.files.orEmpty().associateBy { it.path }
        coroutineScope {
            val freshDeferred = async { refreshChanges() }
            val detailDeferreds = paths.map { p ->
                async {
                    val f = currentByPath[p]
                    runCatching { client.gitFileChanges(projectName, p, f?.oldPath, repo) }.getOrNull()
                }
            }
            val fresh = freshDeferred.await()
            val readDetails = detailDeferreds.map { it.await() }
            val listed = (fresh ?: changes)?.files.orEmpty().associateBy { it.path }
            paths.forEachIndexed { idx, p ->
                val d = readDetails[idx]
                val f = listed[p]
                if (d != null && f != null) {
                    details[p] = d
                    detailSigs[p] = GitReviewModel.fileSignature(f)
                } else {
                    details.remove(p)
                    detailSigs.remove(p)
                }
            }
            then?.invoke()
            model.notifyGitChanged(projectName)
        }
    }

    fun optimistic(path: String, keys: List<String>, state: String, moveOn: Boolean): () -> Unit {
        val ids = keys.map { GitReviewModel.blockId(path, it) }
        val nextMap = LinkedHashMap(pending).apply {
            for (id in ids) put(id, state)
        }
        pending = nextMap
        if (moveOn && focus != null && focus.path == path && focus.key in keys) {
            val nextReviews = GitReviewModel.fileReviews(files, details.toMap(), discards, nextMap)
            val after = GitReviewModel.nextOpen(nextReviews, focus)
            if (after != null) want = after
        }
        return {
            val cleaned = LinkedHashMap(pending)
            for (id in ids) cleaned.remove(id)
            pending = cleaned
        }
    }

    suspend fun currentIndex(path: String, block: GitReviewBlock): Int {
        val client = api ?: error("Not connected")
        val detail = details[path] ?: run {
            val f = changes?.files?.firstOrNull { it.path == path }
            client.gitFileChanges(projectName, path, f?.oldPath, repo).also { details[path] = it }
        }
        val hunks = (if (block.side == "staged") detail.staged?.hunks else detail.unstaged?.hunks).orEmpty()
        return GitReviewModel.hunkIndexFromDetail(hunks, block.key)
            ?: error("This block changed since it was shown. Look at it again, then answer.")
    }

    fun shownHunks(path: String, blocks: List<GitReviewBlock>): List<ChangeHunk> {
        val hunks = details[path]?.unstaged?.hunks.orEmpty()
        return blocks.mapNotNull { b ->
            val idx = if (b.hunk != null) GitReviewModel.hunkIndexFromDetail(hunks, b.key) else null
            if (idx != null) hunks.firstOrNull { it.index == idx } else null
        }
    }

    fun undoDiscard(recordId: String) {
        val entry = discards.firstOrNull { it.recordId == recordId } ?: return
        val client = api ?: return
        toast = null
        enqueue("undo", listOf(entry.path), "Could not undo the discard") {
            client.gitDiscardUndo(projectName, recordId, repo)
            val first = entry.hunks.firstOrNull()
            settle(listOf(entry.path)) {
                discards = discards.filterNot { it.recordId == recordId }
                want = GitReviewFocus(
                    path = entry.path,
                    key = if (first != null) "u:${first.id}" else "u:whole",
                    anchor = first?.oldStart?.toDouble() ?: 0.0,
                )
            }
            val fileName = GitChangesView.splitPath(entry.path).second
            showToast(
                "Restored ${if (entry.hunks.size > 1) pluralize(entry.hunks.size, "block") else "the block"} in $fileName",
            )
        }
    }

    fun stageBlock(path: String, key: String) {
        val client = api ?: return
        val rev = reviews.firstOrNull { it.path == path } ?: return
        val block = rev.blocks.firstOrNull { it.key == key } ?: return
        if (rev.file == null || block.state != "open") return
        val clear = optimistic(path, listOf(key), "staged", moveOn = true)
        enqueue("stage", listOf(path), "Could not stage") {
            try {
                val hunk = block.hunk
                if (hunk == null) {
                    client.gitStage(projectName, listOf(path), repo)
                } else {
                    val idx = currentIndex(path, block)
                    client.gitStageHunks(projectName, path, listOf(HunkSelectionItem(hunk = idx, id = hunk.id)), repo)
                }
            } finally {
                settle(listOf(path), clear)
            }
        }
    }

    fun unstageBlock(path: String, key: String) {
        val client = api ?: return
        val rev = reviews.firstOrNull { it.path == path } ?: return
        val block = rev.blocks.firstOrNull { it.key == key } ?: return
        val file = rev.file ?: return
        if (block.state != "staged") return
        want = GitReviewFocus(path, key, block.anchor)
        val clear = optimistic(path, listOf(key), "open", moveOn = false)
        enqueue("unstage", listOf(path), "Could not unstage") {
            try {
                val hunk = block.hunk
                if (hunk == null) {
                    client.gitUnstage(projectName, GitChangesView.unstagePaths(file), repo)
                } else {
                    val idx = currentIndex(path, block)
                    client.gitUnstageHunks(projectName, path, listOf(HunkSelectionItem(hunk = idx, id = hunk.id)), repo)
                }
            } finally {
                settle(listOf(path), clear)
            }
        }
    }

    fun discardBlock(path: String, key: String) {
        val client = api ?: return
        val rev = reviews.firstOrNull { it.path == path } ?: return
        val block = rev.blocks.firstOrNull { it.key == key } ?: return
        val file = rev.file ?: return
        if (block.state != "open") return
        val n = rev.blocks.indexOf(block) + 1
        val of = rev.blocks.size
        val clear = optimistic(path, listOf(key), "discarded", moveOn = true)
        enqueue("discard", listOf(path), "Could not discard") {
            try {
                val hunk = block.hunk
                val entry: DiscardedEntry? = if (hunk == null || file.untracked) {
                    val shown = shownHunks(path, listOf(block))
                    val res = client.gitDiscard(projectName, listOf(path), repo)
                    if (res.files.isNotEmpty() && res.id.isNotEmpty()) {
                        DiscardedEntry(
                            path = path,
                            recordId = res.id,
                            hunks = shown,
                            whole = if (shown.isNotEmpty()) null else block.whole,
                            added = block.added,
                            removed = block.removed,
                        )
                    } else {
                        null
                    }
                } else {
                    val idx = currentIndex(path, block)
                    val shown = details[path]?.unstaged?.hunks?.firstOrNull { it.index == idx }
                    val res = client.gitDiscardHunks(
                        projectName,
                        path,
                        listOf(HunkSelectionItem(hunk = idx, id = hunk.id)),
                        repo,
                    )
                    if (res.id.isNotEmpty()) {
                        DiscardedEntry(
                            path = path,
                            recordId = res.id,
                            hunks = listOfNotNull(shown),
                            whole = null,
                            added = block.added,
                            removed = block.removed,
                        )
                    } else {
                        null
                    }
                }
                if (entry != null) discards = discards + entry
                val fileName = GitChangesView.splitPath(path).second
                if (entry != null) {
                    showToast("Discarded block $n of $of in $fileName") { undoDiscard(entry.recordId) }
                } else {
                    model.toast(
                        ErrorToastMessage(
                            "Discarded block $n of $fileName",
                            "No copy of it could be kept, so this cannot be undone.",
                            kind = ErrorToastMessage.Kind.WARNING,
                        ),
                    )
                }
            } finally {
                settle(listOf(path), clear)
            }
        }
    }

    fun stageFile(path: String) {
        val client = api ?: return
        val rev = reviews.firstOrNull { it.path == path } ?: return
        val openBlocks = rev.blocks.filter { it.state == "open" }
        if (openBlocks.isEmpty()) return
        val clear = optimistic(path, openBlocks.map { it.key }, "staged", moveOn = false)
        if (focus?.path == path) {
            GitReviewModel.nextFileFocus(reviews, path)?.let { want = it }
        }
        enqueue("stage", listOf(path), "Could not stage") {
            try {
                client.gitStage(projectName, listOf(path), repo)
            } finally {
                settle(listOf(path), clear)
            }
        }
    }

    fun discardFile(path: String) {
        val client = api ?: return
        val rev = reviews.firstOrNull { it.path == path } ?: return
        val openBlocks = rev.blocks.filter { it.state == "open" }
        if (rev.file == null || openBlocks.isEmpty()) return
        val clear = optimistic(path, openBlocks.map { it.key }, "discarded", moveOn = true)
        enqueue("discard", listOf(path), "Could not discard") {
            try {
                val shown = shownHunks(path, openBlocks)
                val whole = if (shown.size == openBlocks.size) null else openBlocks.firstNotNullOfOrNull { it.whole } ?: "empty"
                val added = openBlocks.sumOf { it.added }
                val removed = openBlocks.sumOf { it.removed }
                val res = client.gitDiscard(projectName, listOf(path), repo)
                val entry = if (res.files.isNotEmpty() && res.id.isNotEmpty()) {
                    DiscardedEntry(
                        path = path,
                        recordId = res.id,
                        hunks = if (whole != null) emptyList() else shown,
                        whole = whole,
                        added = added,
                        removed = removed,
                    )
                } else {
                    null
                }
                if (entry != null) discards = discards + entry
                val fileName = GitChangesView.splitPath(path).second
                if (entry != null) {
                    showToast("Discarded ${pluralize(openBlocks.size, "block")} in $fileName") { undoDiscard(entry.recordId) }
                } else {
                    model.toast(
                        ErrorToastMessage(
                            "Discarded the changes to $fileName",
                            "No copy of them could be kept, so this cannot be undone.",
                            kind = ErrorToastMessage.Kind.WARNING,
                        ),
                    )
                }
            } finally {
                settle(listOf(path), clear)
            }
        }
    }

    fun stageAll() {
        val client = api ?: return
        val targets = reviews.filter { it.file != null && it.open > 0 }
        if (targets.isEmpty()) return
        val clears = targets.map { r ->
            optimistic(r.path, r.blocks.filter { it.state == "open" }.map { it.key }, "staged", moveOn = false)
        }
        enqueue("stage", targets.map { it.path }, "Could not stage") {
            try {
                client.gitStage(projectName, targets.map { it.path }, repo)
                showToast("Staged ${pluralize(targets.size, "file")}")
            } finally {
                settle(listOfNotNull(focus?.path)) {
                    clears.forEach { it.invoke() }
                }
            }
        }
    }

    fun startPick(key: String? = null) {
        val cur = focusedTriple ?: return
        val path = cur.first.path
        val block = if (key != null) cur.first.blocks.firstOrNull { it.key == key } else cur.second
        if (block == null || block.state != "open" || block.hunk == null || block.parts == null) return
        want = GitReviewFocus(path, block.key, block.anchor)
        picking = Triple(path, block.key, emptySet())
    }

    fun togglePickLine(line: Int) {
        val cur = picking ?: return
        val next = if (line in cur.third) cur.third - line else cur.third + line
        picking = Triple(cur.first, cur.second, next)
    }

    fun setPick(lines: List<Int>?) {
        val cur = picking ?: return
        picking = if (lines != null) Triple(cur.first, cur.second, lines.toSet()) else null
    }

    fun stagePicked() {
        val client = api ?: return
        val cur = focusedTriple ?: return
        val picked = pickSet ?: return
        val block = cur.second
        val hunk = block.hunk ?: return
        val lines = GitReviewModel.pickRequest(block, picked)
        if (lines != null && lines.isEmpty()) return
        val path = cur.first.path
        val count = lines?.size ?: picked.size
        enqueue("stage-lines", listOf(path), "Could not stage those lines") {
            try {
                val idx = currentIndex(path, block)
                client.gitStageHunks(
                    projectName,
                    path,
                    listOf(HunkSelectionItem(hunk = idx, id = hunk.id, lines = lines)),
                    repo,
                )
                showToast(
                    if (lines != null) "Staged ${pluralize(count, "line")} — the rest of the block stays open"
                    else "Staged the block",
                )
            } finally {
                settle(listOf(path)) {
                    picking = null
                    want = GitReviewFocus(path, block.key, block.anchor)
                }
            }
        }
    }

    fun undoCommit(hash: String? = changes?.lastCommit?.hash) {
        val client = api ?: return
        val h = hash ?: return
        enqueue("undo-commit", emptyList(), "Could not undo the commit") {
            client.gitUndoCommit(projectName, h, repo)
            settle(listOfNotNull(focus?.path))
            showToast("Commit undone — its changes are staged again")
        }
    }

    fun runCommit(amend: Boolean, signoff: Boolean, andPush: Boolean, onSuccess: (() -> Unit)? = null) {
        val client = api ?: return
        val before = changes
        val stagedFiles = commitTotals.commitCount
        val msg = commitMessage.trim()
        if (msg.isEmpty()) return
        enqueue("commit", emptyList(), if (amend) "Could not amend the commit" else "Could not commit") {
            val res = client.gitCommit(projectName, msg, amend = amend, signoff = signoff, repo = repo)
            model.commitDrafts.consumed(projectName, repo, msg)
            settle(listOfNotNull(focus?.path))
            val short = res.hash.take(7)
            if (amend) {
                showToast("Amended $short")
            } else {
                showToast("Committed $short · ${pluralize(stagedFiles, "file")}") { undoCommit(res.hash) }
            }
            if (andPush && before != null) {
                val publish = GitChangesView.syncMode(before.branch) == "publish"
                try {
                    if (publish) client.gitPublish(projectName, repo = repo)
                    else client.gitPush(projectName, repo = repo)
                    refreshChanges()
                    showToast(
                        if (publish) "Published ${before.branch.head ?: "the branch"}"
                        else "Pushed $short to ${before.branch.upstream ?: "the remote"}",
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    model.toast(ErrorToastMessage(if (publish) "Publish failed" else "Push failed", model.describe(e)))
                }
            }
            onSuccess?.invoke()
        }
    }

    if (noRepo || needsPick) {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp).testTag("git-review-repo-notice"),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = if (noRepo) "This project is not a git repository." else "Choose a repository in Source Control first.",
                style = PpmFont.sans(13, lineHeight = 18, color = tokens.text3.color).copy(textAlign = TextAlign.Center),
            )
        }
        return
    }

    if (changes == null) {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp).testTag("git-review-loading"),
            contentAlignment = Alignment.Center,
        ) {
            if (error != null) {
                BasicText(error!!, style = PpmFont.sans(13, lineHeight = 18, color = tokens.error.color).copy(textAlign = TextAlign.Center))
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PpmIcon(ProductIcon.Loader2, size = 18.dp, tint = tokens.text3.color)
                    BasicText("Reading the changes…", style = PpmFont.sans(13, lineHeight = 18, color = tokens.text3.color))
                }
            }
        }
        return
    }

    val openEditorFile = { repoRelPath: String ->
        model.openProjectEditorTab(projectName, GitRepoScope.projectRelativePath(repoRelPath, repo))
    }
    val openConflictFile = { file: ChangedFile ->
        model.openGitConflictTab(projectName, GitRepoScope.projectRelativePath(file.path, repo))
    }
    val openGraph = {
        model.openGitHistoryTab(projectName)
    }

    if (mobile) {
        GitReviewPhoneLayout(
            branch = branch,
            changes = changes!!,
            error = error,
            reviews = reviews,
            conflicts = conflicts,
            totals = totals,
            commitTotals = commitTotals,
            focusedTriple = focusedTriple,
            busy = busy,
            toast = toast,
            commitMessage = commitMessage,
            onCommitMessageChange = { next ->
                model.commitDrafts.setMessage(api, projectName, repo, next)
            },
            onDismissToast = { toast = null },
            onFocusFile = { p ->
                reviews.firstOrNull { it.path == p }?.let { GitReviewModel.firstInFile(it) }?.let { want = it }
            },
            onStep = { dir -> GitReviewModel.stepFocus(reviews, focus, dir)?.let { want = it } },
            onStage = ::stageBlock,
            onDiscard = ::discardBlock,
            onUnstage = ::unstageBlock,
            onUndoDiscard = ::undoDiscard,
            onStageFile = ::stageFile,
            onDiscardFile = ::discardFile,
            onCommit = ::runCommit,
            onUndoCommit = { undoCommit() },
            onOpenFile = openEditorFile,
            onResolveConflict = openConflictFile,
            onOpenGraph = openGraph,
        )
    } else {
        GitReviewDesktopLayout(
            branch = branch,
            changes = changes!!,
            error = error,
            reviews = reviews,
            conflicts = conflicts,
            totals = totals,
            commitTotals = commitTotals,
            focus = focus,
            focusedTriple = focusedTriple,
            items = items,
            pickSet = pickSet,
            busy = busy,
            toast = toast,
            commitMessage = commitMessage,
            onCommitMessageChange = { next ->
                model.commitDrafts.setMessage(api, projectName, repo, next)
            },
            onDismissToast = { toast = null },
            onFocusBlock = { p, k -> want = GitReviewFocus(p, k) },
            onFocusFile = { p ->
                reviews.firstOrNull { it.path == p }?.let { GitReviewModel.firstInFile(it) }?.let { want = it }
            },
            onStep = { dir -> GitReviewModel.stepFocus(reviews, focus, dir)?.let { want = it } },
            onNextFile = { GitReviewModel.nextFileFocus(reviews, focus?.path)?.let { want = it } },
            onStage = ::stageBlock,
            onDiscard = ::discardBlock,
            onUnstage = ::unstageBlock,
            onUndoDiscard = ::undoDiscard,
            onStageFile = ::stageFile,
            onDiscardFile = ::discardFile,
            onStageAll = ::stageAll,
            onStartPick = ::startPick,
            onTogglePickLine = ::togglePickLine,
            onSetPick = ::setPick,
            onStagePicked = ::stagePicked,
            onCommit = { amend, signoff, andPush -> runCommit(amend, signoff, andPush) },
            onUndoCommit = { undoCommit() },
            onOpenFile = openEditorFile,
            onResolveConflict = openConflictFile,
            onOpenGraph = openGraph,
        )
    }
}

@Composable
private fun GitReviewDesktopLayout(
    branch: String?,
    changes: GitChanges,
    error: String?,
    reviews: List<GitFileReview>,
    conflicts: List<ChangedFile>,
    totals: com.hienlh.ppm.state.git.GitReviewTotals,
    commitTotals: ChangeTotals,
    focus: GitReviewFocus?,
    focusedTriple: Triple<GitFileReview, GitReviewBlock, Int>?,
    items: List<GitPaneItem>,
    pickSet: Set<Int>?,
    busy: String?,
    toast: ReviewToastData?,
    commitMessage: String,
    onCommitMessageChange: (String) -> Unit,
    onDismissToast: () -> Unit,
    onFocusBlock: (String, String) -> Unit,
    onFocusFile: (String) -> Unit,
    onStep: (Int) -> Unit,
    onNextFile: () -> Unit,
    onStage: (String, String) -> Unit,
    onDiscard: (String, String) -> Unit,
    onUnstage: (String, String) -> Unit,
    onUndoDiscard: (String) -> Unit,
    onStageFile: (String) -> Unit,
    onDiscardFile: (String) -> Unit,
    onStageAll: () -> Unit,
    onStartPick: (String?) -> Unit,
    onTogglePickLine: (Int) -> Unit,
    onSetPick: (List<Int>?) -> Unit,
    onStagePicked: () -> Unit,
    onCommit: (Boolean, Boolean, Boolean) -> Unit,
    onUndoCommit: () -> Unit,
    onOpenFile: (String) -> Unit,
    onResolveConflict: (ChangedFile) -> Unit,
    onOpenGraph: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var showKeys by remember { mutableStateOf(false) }
    var filesDrawerOpen by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<ReviewConfirmState?>(null) }

    val openCount = totals.blocks - totals.staged
    val operation = changes.operation
    val syncMode = remember(changes.branch) { GitChangesView.syncMode(changes.branch) }

    if (reviews.isEmpty() && conflicts.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(tokens.bgSolid.color)
                .testTag("git-review"),
        ) {
            Column(Modifier.fillMaxSize()) {
                if (error != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(tokens.error.opacity(0.1).color)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        BasicText(error, style = PpmFont.sans(12, lineHeight = 16, color = tokens.error.color))
                    }
                }
                ReviewEmptyView(
                    conflicts = 0,
                    onGraph = onOpenGraph,
                    modifier = Modifier.weight(1f),
                )
            }
            if (toast != null) {
                ReviewToastOverlay(
                    toast = toast,
                    mobile = false,
                    onClose = onDismissToast,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                )
            }
        }
        return
    }

    @Composable
    fun RailContent(onFilePicked: (String) -> Unit) {
        Column(modifier = Modifier.fillMaxSize().background(tokens.panel.color)) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 4.dp),
            ) {
                ReviewFileListView(
                    reviews = reviews,
                    conflicts = conflicts,
                    currentPath = focusedTriple?.first?.path,
                    mobile = false,
                    onPick = onFilePicked,
                    onResolve = onResolveConflict,
                )
            }
            if (totals.files > 0 && operation == null) {
                GitCommitComposer(
                    message = commitMessage,
                    onMessageChange = onCommitMessageChange,
                    totals = commitTotals,
                    operation = null,
                    lastCommit = changes.lastCommit,
                    syncMode = syncMode,
                    busyAction = busy,
                    onCommit = onCommit,
                    onUndoLast = onUndoCommit,
                    isBottom = true,
                )
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown || ev.isCtrlPressed || ev.isMetaPressed || ev.isAltPressed) {
                    return@onPreviewKeyEvent false
                }
                val cur = focusedTriple
                val block = cur?.second
                val isOpen = block?.state == "open"
                when (ev.key) {
                    Key.J -> {
                        onStep(1)
                        true
                    }
                    Key.K -> {
                        onStep(-1)
                        true
                    }
                    Key.Y -> if (isOpen && cur != null) {
                        if (pickSet != null) onStagePicked() else onStage(cur.first.path, block.key)
                        true
                    } else {
                        false
                    }
                    Key.N -> if (isOpen && cur != null && pickSet == null) {
                        onDiscard(cur.first.path, block.key)
                        true
                    } else {
                        false
                    }
                    Key.L -> if (isOpen && block?.hunk != null) {
                        if (pickSet != null) onSetPick(null) else onStartPick(null)
                        true
                    } else {
                        false
                    }
                    Key.Escape -> if (pickSet != null || filesDrawerOpen) {
                        onSetPick(null)
                        filesDrawerOpen = false
                        true
                    } else {
                        false
                    }
                    else -> false
                }
            }
            .testTag("git-review"),
    ) {
        val wideSplit = maxWidth >= 760.dp

        Column(Modifier.fillMaxSize()) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tokens.panel.color)
                    .edge(Edge.Bottom, tokens.border.color)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column {
                    BasicText(
                        text = "Review changes",
                        style = PpmFont.sans(14, FontWeight.SemiBold, lineHeight = 20, color = tokens.text.color),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PpmIcon(ProductIcon.GitBranch, size = 12.dp, tint = tokens.text3.color)
                        BasicText(
                            text = branch ?: "detached HEAD",
                            style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.text2.color),
                        )
                        BasicText(
                            text = "· ${pluralize(totals.files, "file")}",
                            style = PpmFont.sans(12, lineHeight = 16, color = tokens.text3.color),
                        )
                        ReviewCountsInline(added = totals.added, removed = totals.removed)
                    }
                }

                if (totals.blocks + totals.discarded > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ReviewProgressStrip(reviews = reviews)
                        BasicText(
                            text = "${totals.staged} of ${totals.blocks} blocks staged",
                            style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.text.color),
                        )
                        BasicText(
                            text = if (openCount > 0) "· $openCount to decide" else "· all decided",
                            style = PpmFont.sans(12, lineHeight = 16, color = tokens.text3.color),
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (!wideSplit) {
                        val btnShape = RoundedCornerShape(6.dp)
                        Row(
                            modifier = Modifier
                                .height(28.dp)
                                .clip(btnShape)
                                .clickable { filesDrawerOpen = !filesDrawerOpen }
                                .padding(horizontal = 8.dp)
                                .testTag("git-review-files-toggle"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            PpmIcon(ProductIcon.FolderTree, size = 14.dp, tint = tokens.text2.color)
                            BasicText(
                                text = pluralize(reviews.size + conflicts.size, "file"),
                                style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.text2.color),
                            )
                        }
                    }

                    val stageShape = RoundedCornerShape(6.dp)
                    Row(
                        modifier = Modifier
                            .height(28.dp)
                            .clip(stageShape)
                            .cssBorder(tokens.border.color, stageShape)
                            .alpha(if (openCount > 0) 1f else 0.45f)
                            .clickable(enabled = openCount > 0, onClick = onStageAll)
                            .padding(horizontal = 10.dp)
                            .testTag("git-review-stage-all"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PpmIcon(ProductIcon.Check, size = 13.dp, tint = tokens.text.color)
                        BasicText(
                            text = "Stage all",
                            style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.text.color),
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (showKeys) tokens.panel2.color else Color.Transparent)
                            .clickable { showKeys = !showKeys }
                            .testTag("git-review-shortcuts-toggle"),
                        contentAlignment = Alignment.Center,
                    ) {
                        PpmIcon(
                            ProductIcon.Keyboard,
                            size = 15.dp,
                            tint = if (showKeys) tokens.text.color else tokens.text3.color,
                        )
                    }
                }
            }

            if (showKeys) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.panel.color)
                        .edge(Edge.Bottom, tokens.border.color)
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .testTag("git-review-shortcuts-bar"),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ShortcutLegendItem(keys = listOf("J", "K"), label = "next / previous block")
                    ShortcutLegendItem(keys = listOf("Y"), label = "stage")
                    ShortcutLegendItem(keys = listOf("N"), label = "discard")
                    ShortcutLegendItem(keys = listOf("L"), label = "pick lines")
                    ShortcutLegendItem(keys = listOf("Ctrl", "↵"), label = "commit")
                }
            }

            if (error != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.error.opacity(0.1).color)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    BasicText(error, style = PpmFont.sans(12, lineHeight = 16, color = tokens.error.color))
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                Row(Modifier.fillMaxSize()) {
                    if (wideSplit) {
                        Box(
                            modifier = Modifier
                                .width(272.dp)
                                .fillMaxHeight()
                                .edge(Edge.End, tokens.border.color),
                        ) {
                            RailContent { path ->
                                onFocusFile(path)
                                filesDrawerOpen = false
                            }
                        }
                    }

                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        if (focusedTriple != null) {
                            val (fileRev, focusedBlock, focusedIdx) = focusedTriple
                            DesktopFileHeader(
                                review = fileRev,
                                onOpenFile = { onOpenFile(fileRev.path) },
                            )
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                val (_, fileName) = remember(fileRev.path) { GitChangesView.splitPath(fileRev.path) }
                                val lang = remember(fileRev.path) { gitDiffLanguage(fileRev.path) }
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(top = 8.dp, bottom = 20.dp)
                                        .testTag("git-review-code"),
                                ) {
                                    items(items, key = { it.itemKey }) { item ->
                                        when (item) {
                                            is GitPaneItem.Gap -> {
                                                ReviewGapRow(lines = item.lines, compact = false)
                                            }
                                            is GitPaneItem.Block -> {
                                                val isFocused = item.block.key == focusedBlock.key
                                                ReviewBlockCardView(
                                                    name = fileName,
                                                    oldPath = fileRev.file?.oldPath,
                                                    block = item.block,
                                                    index = item.index,
                                                    total = item.total,
                                                    focused = isFocused,
                                                    pick = if (isFocused) pickSet else null,
                                                    compact = false,
                                                    lang = lang,
                                                    busy = busy != null,
                                                    onFocus = { onFocusBlock(fileRev.path, item.block.key) },
                                                    onStage = { onStage(fileRev.path, item.block.key) },
                                                    onDiscard = { onDiscard(fileRev.path, item.block.key) },
                                                    onUnstage = { onUnstage(fileRev.path, item.block.key) },
                                                    onUndoDiscard = onUndoDiscard,
                                                    onStartPick = { onStartPick(item.block.key) },
                                                    onTogglePickLine = onTogglePickLine,
                                                    onSetPick = onSetPick,
                                                    onStagePicked = onStagePicked,
                                                )
                                            }
                                        }
                                    }
                                }
                                if (toast != null) {
                                    ReviewToastOverlay(
                                        toast = toast,
                                        mobile = false,
                                        onClose = onDismissToast,
                                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                                    )
                                }
                            }
                            DesktopBottomBar(
                                review = fileRev,
                                blockIndex = focusedIdx,
                                hasNextFile = reviews.size > 1,
                                onStep = onStep,
                                onDiscardFile = {
                                    val f = fileRev.file ?: return@DesktopBottomBar
                                    val sum = GitChangesView.discardSummary(listOf(f))
                                    val what = f.path.substringAfterLast('/')
                                    confirm = ReviewConfirmState(
                                        title = "Discard changes to $what?",
                                        detail = "This will $sum. You can undo within a few minutes from the notification.",
                                        confirmLabel = "Discard",
                                        onConfirm = { onDiscardFile(fileRev.path) },
                                    )
                                },
                                onStageFile = { onStageFile(fileRev.path) },
                                onNextFile = onNextFile,
                            )
                        } else {
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                ReviewEmptyView(
                                    conflicts = conflicts.size,
                                    onGraph = onOpenGraph,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                if (toast != null) {
                                    ReviewToastOverlay(
                                        toast = toast,
                                        mobile = false,
                                        onClose = onDismissToast,
                                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                if (!wideSplit && filesDrawerOpen) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.3f))
                            .clickable { filesDrawerOpen = false },
                    )
                    Box(
                        modifier = Modifier
                            .width(288.dp)
                            .fillMaxHeight()
                            .edge(Edge.End, tokens.border.color),
                    ) {
                        RailContent { path ->
                            onFocusFile(path)
                            filesDrawerOpen = false
                        }
                    }
                }
            }
        }
    }

    confirm?.let { c ->
        GitConfirm(
            title = c.title,
            detail = c.detail,
            confirmLabel = c.confirmLabel,
            destructive = true,
            onConfirm = {
                confirm = null
                c.onConfirm()
            },
            onCancel = { confirm = null },
        )
    }
}

@Composable
private fun GitReviewPhoneLayout(
    branch: String?,
    changes: GitChanges,
    error: String?,
    reviews: List<GitFileReview>,
    conflicts: List<ChangedFile>,
    totals: com.hienlh.ppm.state.git.GitReviewTotals,
    commitTotals: ChangeTotals,
    focusedTriple: Triple<GitFileReview, GitReviewBlock, Int>?,
    busy: String?,
    toast: ReviewToastData?,
    commitMessage: String,
    onCommitMessageChange: (String) -> Unit,
    onDismissToast: () -> Unit,
    onFocusFile: (String) -> Unit,
    onStep: (Int) -> Unit,
    onStage: (String, String) -> Unit,
    onDiscard: (String, String) -> Unit,
    onUnstage: (String, String) -> Unit,
    onUndoDiscard: (String) -> Unit,
    onStageFile: (String) -> Unit,
    onDiscardFile: (String) -> Unit,
    onCommit: (Boolean, Boolean, Boolean, (() -> Unit)?) -> Unit,
    onUndoCommit: () -> Unit,
    onOpenFile: (String) -> Unit,
    onResolveConflict: (ChangedFile) -> Unit,
    onOpenGraph: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var sheet by remember { mutableStateOf<String?>(null) } // "files" | "actions"
    var confirm by remember { mutableStateOf<ReviewConfirmState?>(null) }

    val openCount = totals.blocks - totals.staged
    val operation = changes.operation
    val syncMode = remember(changes.branch) { GitChangesView.syncMode(changes.branch) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .testTag("git-review"),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(tokens.panel.color)
                .edge(Edge.Bottom, tokens.border.color)
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                BasicText(
                    text = "Review changes",
                    style = PpmFont.sans(15, FontWeight.SemiBold, lineHeight = 19, color = tokens.text.color),
                )
                BasicText(
                    text = if (totals.blocks > 0) {
                        "${totals.staged} of ${pluralize(totals.blocks, "block")} staged"
                    } else {
                        branch ?: "Nothing to review"
                    },
                    style = PpmFont.sans(11, lineHeight = 14, color = tokens.text3.color),
                )
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { sheet = "files" }
                    .testTag("git-review-phone-files-btn"),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.List, size = 20.dp, tint = tokens.text2.color)
            }
        }

        // 3dp progress bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(tokens.text.opacity(0.1).color),
        ) {
            val frac = if (totals.blocks > 0) (totals.staged.toFloat() / totals.blocks.toFloat()).coerceIn(0f, 1f) else 0f
            Box(
                modifier = Modifier
                    .fillMaxWidth(frac)
                    .fillMaxHeight()
                    .background(tokens.accent.color),
            )
        }

        if (error != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tokens.error.opacity(0.1).color)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                BasicText(error, style = PpmFont.sans(12, lineHeight = 16, color = tokens.error.color))
            }
        }

        if (focusedTriple != null) {
            val (fileRev, block, blockIdx) = focusedTriple
            val (dir, fileName) = remember(fileRev.path) { GitChangesView.splitPath(fileRev.path) }
            val deleted = fileRev.file?.x == "D" || fileRev.file?.y == "D"
            val lang = remember(fileRev.path) { gitDiffLanguage(fileRev.path) }

            // Phone file header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .background(tokens.panel.color)
                    .edge(Edge.Bottom, tokens.border.color)
                    .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FileIcon(fileName, size = 16.dp)
                Column(Modifier.weight(1f)) {
                    TruncatedText(
                        text = fileName,
                        style = PpmFont.sans(
                            14,
                            FontWeight.SemiBold,
                            lineHeight = 18,
                            color = if (deleted) tokens.text2.color else tokens.text.color,
                        ).copy(textDecoration = if (deleted) TextDecoration.LineThrough else TextDecoration.None),
                    )
                    TruncatedText(
                        text = listOfNotNull(
                            "Block ${blockIdx + 1} of ${fileRev.blocks.size}",
                            dir.takeIf { it.isNotEmpty() },
                        ).joinToString(" · "),
                        style = PpmFont.sans(11, lineHeight = 14, color = tokens.text3.color),
                    )
                }
                fileRev.file?.let { StatusTile(it) }
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { sheet = "actions" }
                        .testTag("git-review-phone-actions-btn"),
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(ProductIcon.MoreVertical, size = 18.dp, tint = tokens.text2.color)
                }
            }

            // Single block card
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp, bottom = 12.dp),
                ) {
                    ReviewBlockCardView(
                        name = fileName,
                        oldPath = fileRev.file?.oldPath,
                        block = block,
                        index = blockIdx,
                        total = fileRev.blocks.size,
                        focused = block.state == "open",
                        pick = null,
                        compact = true,
                        lang = lang,
                        busy = busy != null,
                        onFocus = {},
                        onStage = { onStage(fileRev.path, block.key) },
                        onDiscard = { onDiscard(fileRev.path, block.key) },
                        onUnstage = { onUnstage(fileRev.path, block.key) },
                        onUndoDiscard = onUndoDiscard,
                        onStartPick = {},
                        onTogglePickLine = {},
                        onSetPick = {},
                        onStagePicked = {},
                    )
                }
                if (toast != null) {
                    ReviewToastOverlay(
                        toast = toast,
                        mobile = true,
                        onClose = onDismissToast,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }
            }

            // Bottom thumb bar
            val btnShape = RoundedCornerShape(10.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tokens.panel.color)
                    .edge(Edge.Top, tokens.border.color)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(btnShape)
                        .background(tokens.panel2.color)
                        .cssBorder(tokens.border.color, btnShape)
                        .clickable { onStep(-1) }
                        .testTag("git-review-phone-prev"),
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(ProductIcon.ChevronLeft, size = 20.dp, tint = tokens.text.color)
                }

                when (block.state) {
                    "open" -> {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(btnShape)
                                .background(tokens.panel2.color)
                                .cssBorder(tokens.border.color, btnShape)
                                .clickable { onDiscard(fileRev.path, block.key) }
                                .testTag("git-review-phone-discard"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        ) {
                            PpmIcon(ProductIcon.Trash2, size = 18.dp, tint = tokens.text.color)
                            BasicText("Discard", style = PpmFont.sans(14, FontWeight.Medium, lineHeight = 18, color = tokens.text.color))
                        }
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(btnShape)
                                .background(tokens.accent.color)
                                .clickable { onStage(fileRev.path, block.key) }
                                .testTag("git-review-phone-stage"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        ) {
                            PpmIcon(ProductIcon.Check, size = 18.dp, tint = tokens.accentFg.color)
                            BasicText("Stage", style = PpmFont.sans(14, FontWeight.Medium, lineHeight = 18, color = tokens.accentFg.color))
                        }
                    }
                    "staged" -> {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(btnShape)
                                .background(tokens.panel2.color)
                                .cssBorder(tokens.border.color, btnShape)
                                .clickable { onUnstage(fileRev.path, block.key) }
                                .testTag("git-review-phone-unstage"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        ) {
                            PpmIcon(ProductIcon.Undo2, size = 18.dp, tint = tokens.text.color)
                            BasicText("Unstage", style = PpmFont.sans(14, FontWeight.Medium, lineHeight = 18, color = tokens.text.color))
                        }
                    }
                    else -> {
                        val canUndo = block.recordId != null && busy == null
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(btnShape)
                                .background(tokens.panel2.color)
                                .cssBorder(tokens.border.color, btnShape)
                                .alpha(if (canUndo) 1f else 0.45f)
                                .clickable(enabled = canUndo) { block.recordId?.let(onUndoDiscard) }
                                .testTag("git-review-phone-undo-discard"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        ) {
                            PpmIcon(ProductIcon.Undo2, size = 18.dp, tint = tokens.text.color)
                            BasicText("Undo discard", style = PpmFont.sans(14, FontWeight.Medium, lineHeight = 18, color = tokens.text.color))
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(btnShape)
                        .background(tokens.panel2.color)
                        .cssBorder(tokens.border.color, btnShape)
                        .clickable { onStep(1) }
                        .testTag("git-review-phone-next"),
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(ProductIcon.ChevronRight, size = 20.dp, tint = tokens.text.color)
                }
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ReviewEmptyView(
                    conflicts = conflicts.size,
                    onGraph = onOpenGraph,
                    modifier = Modifier.fillMaxSize(),
                )
                if (toast != null) {
                    ReviewToastOverlay(
                        toast = toast,
                        mobile = true,
                        onClose = onDismissToast,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }

    if (sheet == "files") {
        Dialog(onDismissRequest = { sheet = null }) {
            val dlgShape = RoundedCornerShape(12.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .clip(dlgShape)
                    .background(tokens.bgSolid.color)
                    .cssBorder(tokens.border.color, dlgShape)
                    .testTag("git-review-phone-files-sheet"),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .edge(Edge.Bottom, tokens.border.color)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        BasicText(
                            text = "Changes · ${branch ?: "detached HEAD"}",
                            style = PpmFont.sans(15, FontWeight.SemiBold, lineHeight = 20, color = tokens.text.color),
                        )
                        if (totals.blocks > 0) {
                            BasicText(
                                text = "${totals.staged} of ${pluralize(totals.blocks, "block")} staged · ${if (openCount > 0) "$openCount to decide" else "all decided"}",
                                style = PpmFont.sans(12, lineHeight = 16, color = tokens.text3.color),
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { sheet = null },
                        contentAlignment = Alignment.Center,
                    ) {
                        PpmIcon(ProductIcon.X, size = 18.dp, tint = tokens.text2.color)
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    ReviewFileListView(
                        reviews = reviews,
                        conflicts = conflicts,
                        currentPath = focusedTriple?.first?.path,
                        mobile = true,
                        onPick = { p ->
                            onFocusFile(p)
                            sheet = null
                        },
                        onResolve = { f ->
                            sheet = null
                            onResolveConflict(f)
                        },
                    )
                }
                if (totals.files > 0 && operation == null) {
                    GitCommitComposer(
                        message = commitMessage,
                        onMessageChange = onCommitMessageChange,
                        totals = commitTotals,
                        operation = null,
                        lastCommit = changes.lastCommit,
                        syncMode = syncMode,
                        busyAction = busy,
                        onCommit = { amend, signoff, andPush ->
                            onCommit(amend, signoff, andPush) { sheet = null }
                        },
                        onUndoLast = onUndoCommit,
                        isBottom = true,
                    )
                }
            }
        }
    }

    if (sheet == "actions" && focusedTriple != null) {
        val fileRev = focusedTriple.first
        val (_, fileName) = GitChangesView.splitPath(fileRev.path)
        Dialog(onDismissRequest = { sheet = null }) {
            val dlgShape = RoundedCornerShape(12.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(dlgShape)
                    .background(tokens.bgSolid.color)
                    .cssBorder(tokens.border.color, dlgShape)
                    .testTag("git-review-phone-actions-sheet"),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .edge(Edge.Bottom, tokens.border.color)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TruncatedText(
                        text = fileName,
                        style = PpmFont.sans(15, FontWeight.SemiBold, lineHeight = 20, color = tokens.text.color),
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { sheet = null },
                        contentAlignment = Alignment.Center,
                    ) {
                        PpmIcon(ProductIcon.X, size = 18.dp, tint = tokens.text2.color)
                    }
                }
                val rawFile = fileRev.file
                if (rawFile != null && rawFile.y != "D") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clickable {
                                sheet = null
                                onOpenFile(fileRev.path)
                            }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PpmIcon(ProductIcon.ExternalLink, size = 18.dp, tint = tokens.text.color)
                        BasicText("Open file", style = PpmFont.sans(14, lineHeight = 18, color = tokens.text.color))
                    }
                }
                if (fileRev.open > 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clickable {
                                sheet = null
                                onStageFile(fileRev.path)
                            }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PpmIcon(ProductIcon.Check, size = 18.dp, tint = tokens.text.color)
                        BasicText("Stage file", style = PpmFont.sans(14, lineHeight = 18, color = tokens.text.color))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clickable {
                                sheet = null
                                val f = fileRev.file ?: return@clickable
                                val sum = GitChangesView.discardSummary(listOf(f))
                                val what = f.path.substringAfterLast('/')
                                confirm = ReviewConfirmState(
                                    title = "Discard changes to $what?",
                                    detail = "This will $sum. You can undo within a few minutes from the notification.",
                                    confirmLabel = "Discard",
                                    onConfirm = { onDiscardFile(fileRev.path) },
                                )
                            }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PpmIcon(ProductIcon.Trash2, size = 18.dp, tint = tokens.error.color)
                        BasicText("Discard file…", style = PpmFont.sans(14, lineHeight = 18, color = tokens.error.color))
                    }
                }
            }
        }
    }

    confirm?.let { c ->
        GitConfirm(
            title = c.title,
            detail = c.detail,
            confirmLabel = c.confirmLabel,
            destructive = true,
            onConfirm = {
                confirm = null
                c.onConfirm()
            },
            onCancel = { confirm = null },
        )
    }
}

@Composable
private fun DesktopFileHeader(
    review: GitFileReview,
    onOpenFile: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val (dir, name) = remember(review.path) { GitChangesView.splitPath(review.path) }
    val listed = review.file
    val deleted = listed?.x == "D" || listed?.y == "D"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .edge(Edge.Bottom, tokens.border.color)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FileIcon(name, size = 16.dp)
        BasicText(
            text = name,
            style = PpmFont.sans(
                14,
                FontWeight.SemiBold,
                lineHeight = 18,
                color = if (deleted) tokens.text2.color else tokens.text.color,
            ).copy(textDecoration = if (deleted) TextDecoration.LineThrough else TextDecoration.None),
        )
        if (dir.isNotEmpty()) {
            TruncatedText(
                text = dir,
                style = PpmFont.sans(12, lineHeight = 16, color = tokens.text3.color),
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (listed != null) {
            StatusTile(listed)
        }
        ReviewCountsInline(added = review.added, removed = review.removed)
        Spacer(Modifier.weight(1f))
        if (listed != null && listed.y != "D") {
            Row(
                modifier = Modifier
                    .height(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onOpenFile)
                    .padding(horizontal = 8.dp)
                    .testTag("git-review-open-file"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PpmIcon(ProductIcon.ExternalLink, size = 13.dp, tint = tokens.text2.color)
                BasicText("Open file", style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.text2.color))
            }
        }
    }
}

@Composable
private fun DesktopBottomBar(
    review: GitFileReview,
    blockIndex: Int,
    hasNextFile: Boolean,
    onStep: (Int) -> Unit,
    onDiscardFile: () -> Unit,
    onStageFile: () -> Unit,
    onNextFile: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val open = review.open

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.panel.color)
            .edge(Edge.Top, tokens.border.color)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(tokens.text.opacity(0.06).color)
                .padding(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onStep(-1) }
                    .testTag("git-review-prev-block"),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.ChevronUp, size = 14.dp, tint = tokens.text2.color)
            }
            BasicText(
                text = "Block ${blockIndex + 1} of ${review.blocks.size}",
                style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.text2.color).copy(textAlign = TextAlign.Center),
                modifier = Modifier.widthIn(min = 92.dp).padding(horizontal = 6.dp),
            )
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onStep(1) }
                    .testTag("git-review-next-block"),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.ChevronDown, size = 14.dp, tint = tokens.text2.color)
            }
        }

        BasicText(
            text = if (open > 0) "$open to decide in this file" else "This file is done",
            style = PpmFont.sans(12, lineHeight = 16, color = tokens.text2.color),
        )
        Spacer(Modifier.weight(1f))

        val canActFile = open > 0 && review.file != null
        Row(
            modifier = Modifier
                .height(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .alpha(if (canActFile) 1f else 0.45f)
                .clickable(enabled = canActFile, onClick = onDiscardFile)
                .padding(horizontal = 10.dp)
                .testTag("git-review-discard-file"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PpmIcon(ProductIcon.Trash2, size = 13.dp, tint = tokens.error.color)
            BasicText("Discard file…", style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.error.color))
        }

        val btnShape = RoundedCornerShape(6.dp)
        Row(
            modifier = Modifier
                .height(28.dp)
                .clip(btnShape)
                .cssBorder(tokens.border.color, btnShape)
                .alpha(if (open > 0) 1f else 0.45f)
                .clickable(enabled = open > 0, onClick = onStageFile)
                .padding(horizontal = 10.dp)
                .testTag("git-review-stage-file"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PpmIcon(ProductIcon.Check, size = 13.dp, tint = tokens.text.color)
            BasicText("Stage file", style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.text.color))
        }

        Row(
            modifier = Modifier
                .height(28.dp)
                .clip(btnShape)
                .background(tokens.accent.color)
                .alpha(if (hasNextFile) 1f else 0.45f)
                .clickable(enabled = hasNextFile, onClick = onNextFile)
                .padding(horizontal = 10.dp)
                .testTag("git-review-next-file"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BasicText("Next file", style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.accentFg.color))
            PpmIcon(ProductIcon.ArrowRight, size = 13.dp, tint = tokens.accentFg.color)
        }
    }
}

@Composable
internal fun ReviewGapRow(lines: Int, compact: Boolean) {
    val tokens = LocalPpmTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .padding(start = if (compact) 38.dp else 54.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.width(12.dp).height(1.dp).background(tokens.border.color))
        BasicText(
            text = pluralize(lines, "unchanged line"),
            style = PpmFont.sans(11, FontWeight.Medium, lineHeight = 14, color = tokens.text3.color),
        )
        Box(Modifier.weight(1f).height(1.dp).background(tokens.border.color))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReviewBlockCardView(
    name: String,
    oldPath: String?,
    block: GitReviewBlock,
    index: Int,
    total: Int,
    focused: Boolean,
    pick: Set<Int>?,
    compact: Boolean,
    lang: String?,
    busy: Boolean,
    onFocus: () -> Unit,
    onStage: () -> Unit,
    onDiscard: () -> Unit,
    onUnstage: () -> Unit,
    onUndoDiscard: (String) -> Unit,
    onStartPick: () -> Unit,
    onTogglePickLine: (Int) -> Unit,
    onSetPick: (List<Int>?) -> Unit,
    onStagePicked: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val range = remember(block) { GitReviewModel.rangeText(block) }
    val staged = block.state == "staged"
    val gone = block.state == "discarded"
    val isPicking = pick != null && focused && block.state == "open"
    val pickable = remember(block, isPicking) { if (isPicking) GitReviewModel.pickableLines(block) else emptyList() }

    val cardShape = RoundedCornerShape(8.dp)
    val borderColor = when {
        focused -> tokens.accent.opacity(0.6).color
        staged -> tokens.accent.opacity(0.45).color
        else -> tokens.border.color
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = 6.dp)
            .clip(cardShape)
            .background(tokens.bgSolid.color)
            .cssBorder(borderColor, cardShape, width = if (focused) 1.5.dp else 1.dp)
            .clickable(enabled = !focused && !compact, onClick = onFocus)
            .testTag("git-review-block-${block.key}"),
    ) {
        // Block Header Bar
        val headBg = when {
            staged || gone -> Color.Transparent
            focused -> tokens.accent.opacity(0.07).color
            else -> tokens.panel.color
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (staged || gone) 32.dp else 38.dp)
                .background(headBg)
                .then(if (!staged && !gone) Modifier.edge(Edge.Bottom, tokens.border.color) else Modifier)
                .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                gone -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        PpmIcon(ProductIcon.Trash2, size = 14.dp, tint = tokens.error.color)
                        BasicText("Discarded", style = PpmFont.sans(12, FontWeight.SemiBold, lineHeight = 16, color = tokens.error.color))
                    }
                    BasicText(range, style = PpmFont.sans(12, lineHeight = 16, color = tokens.text3.color))
                    Spacer(Modifier.weight(1f))
                    if (!compact && block.recordId != null) {
                        Row(
                            modifier = Modifier
                                .height(24.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .alpha(if (busy) 0.45f else 1f)
                                .clickable(enabled = !busy) { onUndoDiscard(block.recordId!!) }
                                .padding(horizontal = 6.dp)
                                .testTag("git-review-block-undo-${block.key}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            PpmIcon(ProductIcon.Undo2, size = 14.dp, tint = tokens.text2.color)
                            BasicText("Undo", style = PpmFont.sans(11, FontWeight.Medium, lineHeight = 15, color = tokens.text2.color))
                        }
                    }
                }
                staged -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        PpmIcon(ProductIcon.Check, size = 14.dp, tint = tokens.accent.color)
                        BasicText("Staged", style = PpmFont.sans(12, FontWeight.SemiBold, lineHeight = 16, color = tokens.accent.color))
                    }
                    BasicText(range, style = PpmFont.sans(12, lineHeight = 16, color = tokens.text3.color))
                    ReviewCountsInline(added = block.added, removed = block.removed)
                    Spacer(Modifier.weight(1f))
                    if (!compact) {
                        Row(
                            modifier = Modifier
                                .height(24.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .clickable(onClick = onUnstage)
                                .padding(horizontal = 6.dp)
                                .testTag("git-review-block-unstage-${block.key}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            PpmIcon(ProductIcon.Undo2, size = 14.dp, tint = tokens.text2.color)
                            BasicText("Unstage", style = PpmFont.sans(11, FontWeight.Medium, lineHeight = 15, color = tokens.text2.color))
                        }
                    }
                }
                isPicking -> {
                    BasicText("Pick lines", style = PpmFont.sans(12, FontWeight.SemiBold, lineHeight = 16, color = tokens.text2.color))
                    BasicText("${pick.size} of ${pickable.size} selected", style = PpmFont.sans(12, lineHeight = 16, color = tokens.text3.color))
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .height(24.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .clickable { onSetPick(pickable) }
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText("All", style = PpmFont.sans(11, FontWeight.Medium, lineHeight = 15, color = tokens.text2.color))
                    }
                    Box(
                        modifier = Modifier
                            .height(24.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .clickable { onSetPick(emptyList()) }
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText("None", style = PpmFont.sans(11, FontWeight.Medium, lineHeight = 15, color = tokens.text2.color))
                    }
                    val btnShape = RoundedCornerShape(7.dp)
                    Row(
                        modifier = Modifier
                            .height(28.dp)
                            .clip(btnShape)
                            .background(tokens.bgSolid.color)
                            .cssBorder(tokens.border.color, btnShape)
                            .clickable { onSetPick(null) }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BasicText("Cancel", style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.text.color))
                        KbdPill("Esc")
                    }
                    Row(
                        modifier = Modifier
                            .height(28.dp)
                            .clip(btnShape)
                            .background(tokens.accent.color)
                            .alpha(if (pick.isNotEmpty()) 1f else 0.45f)
                            .clickable(enabled = pick.isNotEmpty(), onClick = onStagePicked)
                            .padding(horizontal = 8.dp)
                            .testTag("git-review-stage-picked"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PpmIcon(if (busy) ProductIcon.Loader2 else ProductIcon.Check, size = 14.dp, tint = tokens.accentFg.color)
                        BasicText(
                            text = "Stage ${pluralize(pick.size, "line")}",
                            style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.accentFg.color),
                        )
                    }
                }
                else -> {
                    BasicText(
                        text = "Block ${index + 1} of $total",
                        style = PpmFont.sans(12, FontWeight.SemiBold, lineHeight = 16, color = tokens.text2.color),
                    )
                    BasicText(range, style = PpmFont.sans(12, lineHeight = 16, color = tokens.text3.color))
                    ReviewCountsInline(added = block.added, removed = block.removed)
                    Spacer(Modifier.weight(1f))
                    if (!compact) {
                        if (block.hunk != null && block.parts != null) {
                            Row(
                                modifier = Modifier
                                    .height(24.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .clickable(onClick = onStartPick)
                                    .padding(horizontal = 6.dp)
                                    .testTag("git-review-block-lines-${block.key}"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                BasicText("Lines…", style = PpmFont.sans(11, FontWeight.Medium, lineHeight = 15, color = tokens.text2.color))
                            }
                        }
                        val btnShape = RoundedCornerShape(7.dp)
                        Row(
                            modifier = Modifier
                                .height(28.dp)
                                .clip(btnShape)
                                .background(tokens.bgSolid.color)
                                .cssBorder(tokens.border.color, btnShape)
                                .clickable(onClick = onDiscard)
                                .padding(horizontal = 8.dp)
                                .testTag("git-review-block-discard-${block.key}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            PpmIcon(ProductIcon.Trash2, size = 13.dp, tint = tokens.text.color)
                            BasicText("Discard", style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.text.color))
                            if (focused) KbdPill("N")
                        }
                        Row(
                            modifier = Modifier
                                .height(28.dp)
                                .clip(btnShape)
                                .background(tokens.accent.color)
                                .clickable(onClick = onStage)
                                .padding(horizontal = 8.dp)
                                .testTag("git-review-block-stage-${block.key}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            PpmIcon(ProductIcon.Check, size = 13.dp, tint = tokens.accentFg.color)
                            BasicText("Stage", style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.accentFg.color))
                            if (focused) KbdPill("Y", light = true)
                        }
                    }
                }
            }
        }

        // Block Body
        val leftStripe = when {
            staged -> tokens.accent.color
            gone -> tokens.error.opacity(0.6).color
            else -> null
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (leftStripe != null) {
                        Modifier.cssBorder(leftStripe, width = 2.dp, sides = CssSides.Start)
                    } else {
                        Modifier
                    },
                )
                .padding(vertical = 2.dp),
        ) {
            if (block.whole != null) {
                WholeFileNoteView(name = name, oldPath = oldPath, block = block)
                block.parts?.forEachIndexed { i, part ->
                    val rows = remember(part) {
                        GitReviewModel.numberedRows(part).map { r ->
                            val kind = when (r.kind) {
                                "+" -> ReviewBlocks.Kind.ADDED
                                "-" -> ReviewBlocks.Kind.REMOVED
                                else -> ReviewBlocks.Kind.CONTEXT
                            }
                            ReviewBlocks.Row(kind = kind, text = r.text, o = r.old, n = r.new)
                        }
                    }
                    if (i > 0) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(tokens.border.color))
                    }
                    GitUnifiedDiffRows(
                        rows = rows,
                        language = lang,
                        wordWrap = true,
                        compact = compact,
                    )
                }
            } else {
                val part = block.parts?.firstOrNull()
                if (part == null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PpmIcon(ProductIcon.Loader2, size = 14.dp, tint = tokens.text3.color)
                        BasicText("Loading the lines…", style = PpmFont.sans(12, lineHeight = 16, color = tokens.text3.color))
                    }
                } else {
                    val numbered = remember(part) { GitReviewModel.numberedRows(part) }
                    val rows = remember(numbered) {
                        numbered.map { r ->
                            val kind = when (r.kind) {
                                "+" -> ReviewBlocks.Kind.ADDED
                                "-" -> ReviewBlocks.Kind.REMOVED
                                else -> ReviewBlocks.Kind.CONTEXT
                            }
                            ReviewBlocks.Row(kind = kind, text = r.text, o = r.old, n = r.new)
                        }
                    }
                    val indices = remember(numbered) { numbered.map { it.at } }
                    GitUnifiedDiffRows(
                        rows = rows,
                        language = lang,
                        wordWrap = true,
                        compact = compact,
                        picking = isPicking,
                        pickedIndices = pick.orEmpty(),
                        rowLineIndices = indices,
                        onToggleLine = onTogglePickLine,
                    )
                }
            }
        }
    }
}

@Composable
private fun WholeFileNoteView(
    name: String,
    oldPath: String?,
    block: GitReviewBlock,
) {
    val tokens = LocalPpmTokens.current
    val lines = if (block.removed > 0) "all ${pluralize(block.removed, "line")}" else "an empty file"
    val message = if (block.state == "discarded") {
        if (block.whole == "deleted") "$name is back. Undo deletes it again."
        else "The changes to $name are discarded. Undo puts them back."
    } else {
        when (block.whole) {
            "deleted" -> if (block.state == "staged") {
                "$name is deleted — $lines. The next commit deletes it."
            } else {
                "$name is deleted — $lines. Stage it to delete the file in the next commit, or discard to bring it back."
            }
            "binary" -> "$name is a binary file: git cannot show it as lines, so it is staged or discarded whole."
            "large" -> "$name is too big to split into blocks, so it is staged or discarded whole."
            "rename" -> "Renamed from ${oldPath ?: "another path"}. git takes a rename as one change${if (!block.parts.isNullOrEmpty()) ", edits included" else ""}."
            "submodule" -> "$name is a submodule: what changed is the commit it points to."
            "mode" -> "Only the file mode of $name changed."
            "type" -> "$name changed kind — a file became a link, or the other way round."
            "unread" -> "$name was not read: a new folder, or past the number of new files PPM lists."
            else -> "$name is an empty file."
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (block.whole == "deleted" || block.state == "discarded") {
            PpmIcon(
                ProductIcon.Trash2,
                size = 18.dp,
                tint = if (block.state == "discarded") tokens.text3.color else tokens.error.color,
            )
        }
        BasicText(message, style = PpmFont.sans(13, lineHeight = 19, color = tokens.text2.color))
    }
}

@Composable
internal fun ReviewFileListView(
    reviews: List<GitFileReview>,
    conflicts: List<ChangedFile>,
    currentPath: String?,
    mobile: Boolean,
    onPick: (String) -> Unit,
    onResolve: (ChangedFile) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val groups = listOf(
        "changes" to "Changes",
        "staged" to "Staged",
        "discarded" to "Discarded",
    ).map { (id, label) -> Triple(id, label, reviews.filter { GitReviewModel.railGroup(it) == id }) }

    if (conflicts.isNotEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (mobile) 40.dp else 32.dp)
                .padding(horizontal = if (mobile) 16.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PpmIcon(ProductIcon.AlertTriangle, size = 14.dp, tint = tokens.error.color)
            BasicText(
                text = "CONFLICTS",
                style = PpmFont.sans(10, FontWeight.SemiBold, lineHeight = 14, color = tokens.error.color),
            )
            CountChip(conflicts.size, errorTone = true)
        }
        for (file in conflicts) {
            val (dir, name) = remember(file.path) { GitChangesView.splitPath(file.path) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (mobile) 56.dp else 42.dp)
                    .clickable { onResolve(file) }
                    .padding(horizontal = if (mobile) 16.dp else 12.dp, vertical = 4.dp)
                    .testTag("git-review-conflict-${file.path}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FileIcon(name, size = if (mobile) 18.dp else 16.dp)
                Column(Modifier.weight(1f)) {
                    TruncatedText(
                        text = name,
                        style = PpmFont.sans(if (mobile) 14 else 13, FontWeight.Medium, lineHeight = 18, color = tokens.text.color),
                    )
                    if (dir.isNotEmpty()) {
                        TruncatedText(
                            text = dir,
                            style = PpmFont.sans(if (mobile) 12 else 11, lineHeight = 15, color = tokens.text3.color),
                        )
                    }
                }
                BasicText("Resolve", style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.accent.color))
            }
        }
    }

    for ((groupId, groupLabel, list) in groups) {
        if (list.isEmpty()) continue
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (mobile) 40.dp else 32.dp)
                .padding(horizontal = if (mobile) 16.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BasicText(
                text = groupLabel.uppercase(),
                style = PpmFont.sans(10, FontWeight.SemiBold, lineHeight = 14, color = tokens.text3.color),
            )
            CountChip(list.size)
        }
        for (rev in list) {
            val isCurrent = rev.path == currentPath
            val (dir, name) = remember(rev.path) { GitChangesView.splitPath(rev.path) }
            val deleted = rev.file?.x == "D" || rev.file?.y == "D"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (mobile) 56.dp else 42.dp)
                    .background(if (isCurrent) tokens.accent.opacity(0.1).color else Color.Transparent)
                    .then(
                        if (isCurrent) {
                            Modifier.cssBorder(tokens.accent.color, width = 2.dp, sides = CssSides.Start)
                        } else {
                            Modifier
                        },
                    )
                    .clickable { onPick(rev.path) }
                    .padding(horizontal = if (mobile) 16.dp else 12.dp, vertical = 4.dp)
                    .testTag("git-review-file-${rev.path}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FileIcon(name, size = if (mobile) 18.dp else 16.dp)
                Column(Modifier.weight(1f)) {
                    TruncatedText(
                        text = name,
                        style = PpmFont.sans(
                            if (mobile) 14 else 13,
                            if (groupId == "changes") FontWeight.Medium else FontWeight.Normal,
                            lineHeight = 18,
                            color = if (groupId == "changes") tokens.text.color else tokens.text2.color,
                        ).copy(textDecoration = if (deleted) TextDecoration.LineThrough else TextDecoration.None),
                    )
                    if (dir.isNotEmpty()) {
                        TruncatedText(
                            text = dir,
                            style = PpmFont.sans(if (mobile) 12 else 11, lineHeight = 15, color = tokens.text3.color),
                        )
                    }
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ReviewDotsRow(blocks = rev.blocks)
                    when (groupId) {
                        "staged" -> BasicText("Staged", style = PpmFont.sans(10, FontWeight.SemiBold, lineHeight = 13, color = tokens.accent.color))
                        "discarded" -> BasicText("Discarded", style = PpmFont.sans(10, FontWeight.SemiBold, lineHeight = 13, color = tokens.error.color))
                        else -> BasicText("${rev.open} left", style = PpmFont.mono(10, FontWeight.Medium, lineHeight = 13, color = tokens.text3.color))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewDotsRow(blocks: List<GitReviewBlock>) {
    val tokens = LocalPpmTokens.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        blocks.take(6).forEach { b ->
            val color = when (b.state) {
                "staged" -> tokens.accent.color
                "discarded" -> tokens.error.opacity(0.75).color
                else -> tokens.text3.opacity(0.85).color
            }
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (b.state == "staged") color else Color.Transparent)
                    .cssBorder(color, CircleShape, width = 1.5.dp),
            )
        }
        if (blocks.size > 6) {
            BasicText(
                text = "+${blocks.size - 6}",
                style = PpmFont.mono(10, lineHeight = 12, color = tokens.text3.color),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewProgressStrip(reviews: List<GitFileReview>) {
    val tokens = LocalPpmTokens.current
    val blocks = remember(reviews) { GitReviewModel.railOrder(reviews).flatMap { it.blocks } }
    if (blocks.isEmpty()) return
    if (blocks.size > 40) {
        val stagedFrac = (blocks.count { it.state == "staged" }.toFloat() / blocks.size.toFloat()).coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .width(120.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(tokens.text.opacity(0.13).color),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(stagedFrac)
                    .fillMaxHeight()
                    .background(tokens.accent.color),
            )
        }
        return
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        blocks.forEach { b ->
            val c = when (b.state) {
                "staged" -> tokens.accent.color
                "discarded" -> tokens.error.opacity(0.6).color
                else -> tokens.text.opacity(0.13).color
            }
            Box(
                modifier = Modifier
                    .width(10.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(c),
            )
        }
    }
}

@Composable
private fun ReviewCountsInline(added: Int, removed: Int) {
    val tokens = LocalPpmTokens.current
    if (added == 0 && removed == 0) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (added > 0) {
            BasicText("+$added", style = PpmFont.mono(11, lineHeight = 14, color = tokens.success.color))
        }
        if (removed > 0) {
            BasicText("−$removed", style = PpmFont.mono(11, lineHeight = 14, color = tokens.error.color))
        }
    }
}

@Composable
internal fun ReviewToastOverlay(
    toast: ReviewToastData,
    mobile: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(if (mobile) 12.dp else 10.dp)
    Row(
        modifier = modifier
            .then(if (mobile) Modifier.fillMaxWidth() else Modifier.widthIn(max = 520.dp))
            .clip(shape)
            .background(tokens.panel2.color)
            .cssBorder(tokens.border.color, shape)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
            .testTag("git-review-toast"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PpmIcon(ProductIcon.Check, size = 16.dp, tint = tokens.accent.color)
        TruncatedText(
            text = toast.text,
            style = PpmFont.sans(13, lineHeight = 18, color = tokens.text.color),
            modifier = Modifier.weight(1f, fill = mobile),
        )
        if (toast.undo != null) {
            Row(
                modifier = Modifier
                    .height(if (mobile) 36.dp else 26.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        onClose()
                        toast.undo.invoke()
                    }
                    .padding(horizontal = 8.dp)
                    .testTag("git-review-toast-undo"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PpmIcon(ProductIcon.Undo2, size = 14.dp, tint = tokens.accent.color)
                BasicText("Undo", style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.accent.color))
            }
        }
        Box(
            modifier = Modifier
                .size(if (mobile) 36.dp else 24.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            PpmIcon(ProductIcon.X, size = 14.dp, tint = tokens.text3.color)
        }
    }
}

@Composable
internal fun ReviewEmptyView(
    conflicts: Int,
    onGraph: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    Column(
        modifier = modifier.padding(32.dp).testTag("git-review-empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(tokens.accent.opacity(0.12).color),
            contentAlignment = Alignment.Center,
        ) {
            PpmIcon(ProductIcon.Check, size = 20.dp, tint = tokens.accent.color)
        }
        Spacer(Modifier.height(14.dp))
        BasicText(
            text = if (conflicts > 0) "${pluralize(conflicts, "conflict")} to resolve" else "Nothing left to review",
            style = PpmFont.sans(15, FontWeight.SemiBold, lineHeight = 20, color = tokens.text.color),
        )
        Spacer(Modifier.height(6.dp))
        BasicText(
            text = if (conflicts > 0) {
                "A conflicted file has no blocks to review until it is resolved: open it from the list."
            } else {
                "The working tree matches the last commit. What you commit here shows up at the top of the Git Graph."
            },
            style = PpmFont.sans(13, lineHeight = 19, color = tokens.text2.color).copy(textAlign = TextAlign.Center),
            modifier = Modifier.widthIn(max = 360.dp),
        )
        Spacer(Modifier.height(16.dp))
        val btnShape = RoundedCornerShape(6.dp)
        Row(
            modifier = Modifier
                .height(34.dp)
                .clip(btnShape)
                .cssBorder(tokens.border.color, btnShape)
                .clickable(onClick = onGraph)
                .padding(horizontal = 12.dp)
                .testTag("git-review-open-graph"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PpmIcon(LucideIcon.GitCommitHorizontal, size = 14.dp, tint = tokens.text.color)
            BasicText("Open Git Graph", style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.text.color))
        }
    }
}

@Composable
private fun ShortcutLegendItem(keys: List<String>, label: String) {
    val tokens = LocalPpmTokens.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        keys.forEach { k -> KbdPill(k) }
        BasicText(label, style = PpmFont.sans(11, lineHeight = 15, color = tokens.text3.color))
    }
}

@Composable
private fun KbdPill(text: String, light: Boolean = false) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (light) Color.White.copy(alpha = 0.15f) else tokens.panel2.color)
            .cssBorder(if (light) Color.White.copy(alpha = 0.3f) else tokens.border.color, shape)
            .padding(horizontal = 4.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style = PpmFont.mono(10, FontWeight.Medium, lineHeight = 13, color = if (light) tokens.accentFg.color else tokens.text2.color),
        )
    }
}
