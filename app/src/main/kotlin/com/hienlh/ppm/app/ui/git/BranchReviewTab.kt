package com.hienlh.ppm.app.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.client.BranchDiff
import com.hienlh.ppm.client.BranchDiffFile
import com.hienlh.ppm.client.ChangeSide
import com.hienlh.ppm.client.ChangedFile
import com.hienlh.ppm.client.GitBranch
import com.hienlh.ppm.client.gitBranchDiff
import com.hienlh.ppm.client.gitBranches
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FileIcon
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.LucideIcon
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.state.git.BranchReviewState
import com.hienlh.ppm.state.git.GitFileTree
import com.hienlh.ppm.state.git.GitRepoScope
import com.hienlh.ppm.state.git.GitTreeNode
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Branch Review tab (`branch-review` -> `src/web/components/branch-review/branch-review-tab.tsx`).
 */
@Composable
fun BranchReviewBody(model: AppModel, tab: WorkspaceTab) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val scope = rememberCoroutineScope()
    val api = model.api
    val projectName = tab.metaString("projectName")?.takeIf { it.isNotEmpty() } ?: model.selectedProject

    if (projectName == null) {
        Box(
            modifier = Modifier.fillMaxSize().padding(16.dp).testTag("branch-review-no-project"),
            contentAlignment = Alignment.Center,
        ) {
            BasicText("Select a project to review branches", style = PpmFont.sans(13, lineHeight = 18, color = tokens.text3.color))
        }
        return
    }

    LaunchedEffect(projectName, api) {
        model.loadGitRepos(projectName)
    }

    val discovery = model.gitDiscovery[projectName]
    val chosenPath = model.gitChosenRepoPath[projectName]
    val repo = remember(discovery, chosenPath) { GitRepoScope.resolveRepo(discovery, chosenPath) }
    val needsPick = remember(discovery, chosenPath) { GitRepoScope.needsPick(discovery, chosenPath) }
    val noRepo = remember(discovery) { GitRepoScope.hasNoRepo(discovery) }

    var branches by remember(projectName, repo?.path) { mutableStateOf<List<GitBranch>>(emptyList()) }
    var base by remember(projectName, repo?.path) { mutableStateOf("") }
    var head by remember(projectName, repo?.path) { mutableStateOf("") }
    var diff by remember(projectName, repo?.path) { mutableStateOf<BranchDiff?>(null) }
    var selectedPath by remember(projectName, repo?.path) { mutableStateOf<String?>(null) }
    var reviewed by remember(projectName, repo?.path) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var collapsed by remember(projectName, repo?.path) { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember(projectName, repo?.path) { mutableStateOf(false) }
    var error by remember(projectName, repo?.path) { mutableStateOf<String?>(null) }
    var listOpen by remember { mutableStateOf(false) }
    var diffJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(projectName, repo?.path, needsPick, noRepo, api) {
        val client = api ?: return@LaunchedEffect
        if (repo == null || needsPick || noRepo) return@LaunchedEffect
        try {
            val list = client.gitBranches(projectName, repo)
            branches = list
            val current = list.firstOrNull { it.current }?.name
            if (head.isEmpty()) head = current ?: ""
            if (base.isEmpty()) base = BranchReviewState.defaultBase(list, current)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = model.describe(e)
        }
    }

    fun triggerLoadDiff(targetBase: String = base, targetHead: String = head) {
        val client = api ?: return
        if (repo == null || needsPick || noRepo || targetBase.isEmpty() || targetHead.isEmpty()) return
        diffJob?.cancel()
        diffJob = scope.launch {
            loading = true
            error = null
            try {
                val res = client.gitBranchDiff(projectName, base = targetBase, head = targetHead, repo = repo)
                diff = res
                val currentSel = selectedPath
                selectedPath = if (currentSel != null && res.files.any { it.path == currentSel }) {
                    currentSel
                } else {
                    BranchReviewState.firstReviewable(res.files)?.path
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diff = null
                error = model.describe(e)
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(projectName, repo?.path, base, head, needsPick, noRepo, api) {
        if (!needsPick && !noRepo && base.isNotEmpty() && head.isNotEmpty() && api != null) {
            triggerLoadDiff(base, head)
        }
    }

    LaunchedEffect(projectName, repo?.path, api) {
        model.gitChangedEvents.filter { it == projectName }.collectLatest {
            if (base.isNotEmpty() && head.isNotEmpty()) {
                triggerLoadDiff(base, head)
            }
        }
    }

    val stateKey = remember(projectName, base, head) {
        if (base.isNotEmpty() && head.isNotEmpty()) {
            BranchReviewState.reviewKey(projectName, base, head)
        } else {
            null
        }
    }

    LaunchedEffect(stateKey) {
        collapsed = emptySet()
        reviewed = if (stateKey != null) model.branchReviewStore.loadReviewed(stateKey) else emptyMap()
    }

    val currentDiff = remember(diff, base, head) {
        diff?.takeIf { it.base == base && it.head == head }
    }

    LaunchedEffect(stateKey, currentDiff) {
        val k = stateKey ?: return@LaunchedEffect
        val d = currentDiff ?: return@LaunchedEffect
        val pruned = BranchReviewState.pruneReviewed(reviewed, d.files)
        if (pruned.size != reviewed.size) {
            reviewed = pruned
            model.branchReviewStore.saveReviewed(k, pruned)
        }
    }

    fun commitReviewed(next: Map<String, String>) {
        reviewed = next
        if (stateKey != null) {
            model.branchReviewStore.saveReviewed(stateKey, next)
        }
    }

    if (noRepo) {
        GitNoRepo(onRescan = { scope.launch { model.loadGitRepos(projectName, force = true) } })
        return
    }
    if (needsPick && discovery != null) {
        GitRepoChoice(
            repos = discovery.repos,
            onSelect = { picked -> model.chooseGitRepo(projectName, picked.path) },
        )
        return
    }

    val files = currentDiff?.files.orEmpty()
    val byPath = remember(files) { files.associateBy { it.path } }
    val doneCount = remember(reviewed, files) { BranchReviewState.reviewedCount(reviewed, files) }
    val tree = remember(files) {
        GitFileTree.buildTree(
            files.map { f ->
                val status = if (f.status == "T") "M" else f.status
                ChangedFile(
                    path = f.path,
                    oldPath = f.oldPath,
                    unstaged = ChangeSide(added = f.additions, removed = f.deletions, blocks = emptyList()),
                    y = status,
                )
            },
        )
    }
    val selected = selectedPath?.let { byPath[it] }

    fun goToNextUnreviewed() {
        val next = BranchReviewState.nextUnreviewed(reviewed, files, selectedPath)
        if (next != null) {
            selectedPath = next.path
            listOpen = false
        }
    }

    @Composable
    fun FileTreeContent(modifier: Modifier = Modifier) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp)
                .testTag("branch-review-tree"),
        ) {
            if (files.isEmpty() && !loading) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = if (error != null) "—" else "These branches have identical trees.",
                        style = PpmFont.sans(12, lineHeight = 16, color = tokens.text3.color).copy(textAlign = TextAlign.Center),
                    )
                }
            }
            for (node in tree) {
                BranchReviewTreeRow(
                    node = node,
                    depth = 0,
                    byPath = byPath,
                    reviewed = reviewed,
                    selectedPath = selectedPath,
                    collapsed = collapsed,
                    onToggleCollapse = { path ->
                        collapsed = if (path in collapsed) collapsed - path else collapsed + path
                    },
                    onSelect = { path ->
                        selectedPath = path
                        listOpen = false
                    },
                    onToggleReviewed = { file ->
                        commitReviewed(BranchReviewState.toggleReviewed(reviewed, file))
                    },
                )
            }
            val omitted = currentDiff?.omitted ?: 0
            if (omitted > 0) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = "$omitted more ${if (omitted == 1) "file" else "files"} not listed.",
                        style = PpmFont.sans(12, lineHeight = 16, color = tokens.text3.color),
                    )
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .testTag("branch-review-tab"),
    ) {
        if (repo != null && discovery != null && (discovery.repos.size > 1 || !discovery.rootIsRepo)) {
            GitRepoBar(
                repo = repo,
                repos = discovery.repos,
                onSelect = { picked -> model.chooseGitRepo(projectName, picked.path) },
            )
        }

        // Top branch selector bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .edge(Edge.Bottom, tokens.border.color)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BranchSelectButton(
                value = base,
                branches = branches,
                label = "Base branch",
                testTag = "branch-review-base",
                onChange = { base = it },
                modifier = Modifier.weight(1f, fill = false).widthIn(max = 170.dp),
            )
            PpmIcon(ProductIcon.ArrowRight, size = 14.dp, tint = tokens.text3.color)
            BranchSelectButton(
                value = head,
                branches = branches,
                label = "Branch to review",
                testTag = "branch-review-head",
                onChange = { head = it },
                modifier = Modifier.weight(1f, fill = false).widthIn(max = 170.dp),
            )
            Spacer(Modifier.weight(1f))
            BasicText(
                text = "$doneCount/${files.size} reviewed",
                style = PpmFont.mono(11, lineHeight = 14, color = tokens.text2.color),
                modifier = Modifier.testTag("branch-review-progress"),
            )
            val allDone = doneCount == files.size && files.isNotEmpty()
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .alpha(if (files.isEmpty()) 0.4f else 1f)
                    .clickable(enabled = files.isNotEmpty()) {
                        commitReviewed(BranchReviewState.setAllReviewed(reviewed, files, !allDone))
                    }
                    .testTag("branch-review-toggle-all"),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.ListChecks, size = 14.dp, tint = tokens.text2.color)
            }
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { triggerLoadDiff() }
                    .testTag("branch-review-reload"),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(
                    if (loading) ProductIcon.Loader2 else ProductIcon.RefreshCw,
                    size = 14.dp,
                    tint = tokens.text2.color,
                )
            }
        }

        error?.let { err ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tokens.error.opacity(0.1).color)
                    .edge(Edge.Bottom, tokens.error.opacity(0.3).color)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("branch-review-error"),
            ) {
                BasicText(err, style = PpmFont.sans(12, lineHeight = 16, color = tokens.error.color))
            }
        }

        // Main area
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (!mobile) {
                Column(
                    modifier = Modifier
                        .width(288.dp)
                        .fillMaxHeight()
                        .edge(Edge.End, tokens.border.color),
                ) {
                    FileTreeContent(modifier = Modifier.weight(1f))
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                if (selected != null && currentDiff != null) {
                    val projFile = remember(selected.path, repo) {
                        GitRepoScope.projectRelativePath(selected.path, repo)
                    }
                    key("${selected.path}:${currentDiff.mergeBase}:${currentDiff.headCommit}") {
                        GitDiffViewer(
                            model = model,
                            projectName = projectName,
                            filePath = projFile,
                            ref1 = currentDiff.mergeBase,
                            ref2 = currentDiff.headCommit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (loading) {
                            PpmIcon(ProductIcon.Loader2, size = 18.dp, tint = tokens.text3.color)
                        } else {
                            BasicText(
                                text = "Nothing to review.",
                                style = PpmFont.sans(12, lineHeight = 16, color = tokens.text3.color),
                            )
                        }
                    }
                }
            }
        }

        // Bottom bar
        if (mobile) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .edge(Edge.Top, tokens.border.color)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val btnShape = RoundedCornerShape(6.dp)
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(btnShape)
                        .background(tokens.panel2.color)
                        .cssBorder(tokens.border.color, btnShape)
                        .clickable { listOpen = true }
                        .padding(horizontal = 12.dp)
                        .testTag("branch-review-files-btn"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                ) {
                    PpmIcon(ProductIcon.FileText, size = 15.dp, tint = tokens.text.color)
                    BasicText(
                        text = "${files.size} files",
                        style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.text.color),
                    )
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(btnShape)
                        .background(tokens.panel2.color)
                        .cssBorder(tokens.border.color, btnShape)
                        .clickable { goToNextUnreviewed() }
                        .padding(horizontal = 12.dp)
                        .testTag("branch-review-next"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    BasicText(
                        text = "Next unreviewed",
                        style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.text.color),
                    )
                }
            }
        } else if (selected != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .edge(Edge.Top, tokens.border.color)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TruncatedText(
                    text = selected.path,
                    style = PpmFont.mono(11, lineHeight = 15, color = tokens.text2.color),
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .height(26.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { goToNextUnreviewed() }
                        .padding(horizontal = 8.dp)
                        .testTag("branch-review-next"),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = "Next unreviewed",
                        style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.text2.color),
                    )
                }
            }
        }
    }

    if (mobile && listOpen) {
        Dialog(onDismissRequest = { listOpen = false }) {
            val sheetShape = RoundedCornerShape(12.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .clip(sheetShape)
                    .background(tokens.bgSolid.color)
                    .cssBorder(tokens.border.color, sheetShape)
                    .testTag("branch-review-files-sheet"),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .edge(Edge.Bottom, tokens.border.color)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        text = "Changed files (${files.size})",
                        style = PpmFont.sans(13, FontWeight.SemiBold, lineHeight = 18, color = tokens.text.color),
                    )
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { listOpen = false },
                        contentAlignment = Alignment.Center,
                    ) {
                        PpmIcon(ProductIcon.X, size = 14.dp, tint = tokens.text2.color)
                    }
                }
                FileTreeContent(modifier = Modifier.weight(1f, fill = false))
            }
        }
    }
}

@Composable
internal fun BranchReviewTreeRow(
    node: GitTreeNode,
    depth: Int,
    byPath: Map<String, BranchDiffFile>,
    reviewed: Map<String, String>,
    selectedPath: String?,
    collapsed: Set<String>,
    onToggleCollapse: (String) -> Unit,
    onSelect: (String) -> Unit,
    onToggleReviewed: (BranchDiffFile) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val file = if (node.file != null) byPath[node.path] else null
    val indent = (depth * 14).dp

    if (file == null) {
        val isCollapsed = node.path in collapsed
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (mobile) 44.dp else 26.dp)
                .clickable { onToggleCollapse(node.path) }
                .padding(start = indent + 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
                .testTag("branch-review-folder-${node.path}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PpmIcon(
                if (isCollapsed) ProductIcon.ChevronRight else ProductIcon.ChevronDown,
                size = 12.dp,
                tint = tokens.text2.color,
            )
            TruncatedText(
                text = node.name,
                style = PpmFont.sans(12, lineHeight = 16, color = tokens.text2.color),
                modifier = Modifier.weight(1f),
            )
        }
        if (!isCollapsed) {
            for (child in node.children) {
                BranchReviewTreeRow(
                    node = child,
                    depth = depth + 1,
                    byPath = byPath,
                    reviewed = reviewed,
                    selectedPath = selectedPath,
                    collapsed = collapsed,
                    onToggleCollapse = onToggleCollapse,
                    onSelect = onSelect,
                    onToggleReviewed = onToggleReviewed,
                )
            }
        }
        return
    }

    val done = BranchReviewState.isReviewed(reviewed, file)
    val isSelected = selectedPath == file.path
    val statusColor = when (file.status) {
        "M" -> tokens.warning.color
        "A" -> tokens.success.color
        "D" -> tokens.error.color
        "R" -> tokens.accent.color
        "C" -> tokens.accent2.color
        else -> tokens.text3.color
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (mobile) 44.dp else 28.dp)
            .background(if (isSelected) tokens.accent.opacity(0.1).color else Color.Transparent)
            .then(
                if (isSelected) {
                    Modifier.cssBorder(tokens.accent.color, width = 2.dp, sides = CssSides.Start)
                } else {
                    Modifier
                },
            )
            .clickable { onSelect(file.path) }
            .padding(start = indent + 6.dp, end = 8.dp)
            .testTag("branch-review-file-${file.path}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 44dp touch target on mobile, 20dp on tablet; 16dp visual box
        val hitSize = if (mobile) 44.dp else 20.dp
        Box(
            modifier = Modifier
                .size(hitSize)
                .clickable(role = Role.Checkbox) { onToggleReviewed(file) }
                .testTag("branch-review-check-${file.path}"),
            contentAlignment = Alignment.Center,
        ) {
            val cbShape = RoundedCornerShape(4.dp)
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(cbShape)
                    .background(if (done) tokens.accent.color else Color.Transparent)
                    .cssBorder(if (done) tokens.accent.color else tokens.border.color, cbShape),
                contentAlignment = Alignment.Center,
            ) {
                if (done) {
                    PpmIcon(ProductIcon.Check, size = 11.dp, tint = tokens.accentFg.color)
                }
            }
        }

        FileIcon(node.name, size = 14.dp)
        TruncatedText(
            text = node.name,
            style = PpmFont.sans(12, lineHeight = 16, color = tokens.text.color),
            modifier = Modifier.weight(1f),
        )
        BasicText(
            text = file.status,
            style = PpmFont.mono(11, FontWeight.Medium, lineHeight = 14, color = statusColor),
        )
        if (file.binary) {
            BasicText(
                text = "bin",
                style = PpmFont.sans(11, lineHeight = 14, color = tokens.text3.color).copy(fontStyle = FontStyle.Italic),
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                BasicText(
                    text = "+${file.additions}",
                    style = PpmFont.mono(10, lineHeight = 13, color = tokens.success.color),
                )
                BasicText(
                    text = "-${file.deletions}",
                    style = PpmFont.mono(10, lineHeight = 13, color = tokens.error.color),
                )
            }
        }
    }
}

@Composable
internal fun BranchSelectButton(
    value: String,
    branches: List<GitBranch>,
    label: String,
    testTag: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val items = remember(branches) { BranchReviewState.branchItems(branches) }

    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier = modifier
            .height(28.dp)
            .clip(shape)
            .background(tokens.panel2.color)
            .cssBorder(tokens.border.color, shape)
            .clickable {
                query = ""
                open = true
            }
            .padding(horizontal = 8.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PpmIcon(ProductIcon.GitBranch, size = 13.dp, tint = tokens.text2.color)
        TruncatedText(
            text = value.ifEmpty { "Select a branch" },
            style = PpmFont.sans(
                12,
                FontWeight.Medium,
                lineHeight = 16,
                color = if (value.isEmpty()) tokens.text3.color else tokens.text.color,
            ),
            modifier = Modifier.weight(1f, fill = false),
        )
        PpmIcon(ProductIcon.ChevronDown, size = 12.dp, tint = tokens.text3.color)
    }

    if (open) {
        val filtered = remember(items, query) {
            val q = query.trim().lowercase()
            if (q.isEmpty()) items else items.filter { it.label.lowercase().contains(q) }
        }
        val grouped = remember(filtered) { filtered.groupBy { it.group } }

        Dialog(onDismissRequest = { open = false }) {
            val dlgShape = RoundedCornerShape(10.dp)
            Column(
                modifier = Modifier
                    .width(320.dp)
                    .heightIn(max = 420.dp)
                    .clip(dlgShape)
                    .background(tokens.bgSolid.color)
                    .cssBorder(tokens.border.color, dlgShape)
                    .testTag("$testTag-dialog"),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .edge(Edge.Bottom, tokens.border.color)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PpmIcon(ProductIcon.Search, size = 14.dp, tint = tokens.text3.color)
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = PpmFont.sans(12, lineHeight = 16, color = tokens.text.color),
                        cursorBrush = SolidColor(tokens.accent.color),
                        modifier = Modifier.weight(1f).testTag("$testTag-search"),
                        decorationBox = { inner ->
                            if (query.isEmpty()) {
                                BasicText("Search branches ($label)", style = PpmFont.sans(12, lineHeight = 16, color = tokens.text3.color))
                            }
                            inner()
                        },
                    )
                }

                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText("No matching branches", style = PpmFont.sans(12, lineHeight = 16, color = tokens.text3.color))
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f, fill = false).padding(vertical = 4.dp)) {
                        grouped.forEach { (groupName, groupItems) ->
                            item(key = "hdr:$groupName") {
                                BasicText(
                                    text = groupName.uppercase(),
                                    style = PpmFont.sans(10, FontWeight.SemiBold, lineHeight = 14, color = tokens.text3.color),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                )
                            }
                            items(groupItems, key = { "${it.group}:${it.value}" }) { item ->
                                val selected = item.value == value
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(if (selected) tokens.accent.opacity(0.12).color else Color.Transparent)
                                        .clickable {
                                            onChange(item.value)
                                            open = false
                                        }
                                        .padding(horizontal = 12.dp, vertical = 7.dp)
                                        .testTag("$testTag-item-${item.value}"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    PpmIcon(
                                        if (item.remote) ProductIcon.Cloud else ProductIcon.GitBranch,
                                        size = 13.dp,
                                        tint = if (selected) tokens.accent.color else tokens.text2.color,
                                    )
                                    TruncatedText(
                                        text = item.label,
                                        style = PpmFont.sans(
                                            12,
                                            if (selected) FontWeight.Medium else FontWeight.Normal,
                                            lineHeight = 16,
                                            color = tokens.text.color,
                                        ),
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (item.hint != null) {
                                        BasicText(
                                            text = item.hint!!,
                                            style = PpmFont.mono(10, lineHeight = 14, color = tokens.text3.color),
                                        )
                                    }
                                    if (selected) {
                                        PpmIcon(ProductIcon.Check, size = 13.dp, tint = tokens.accent.color)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
