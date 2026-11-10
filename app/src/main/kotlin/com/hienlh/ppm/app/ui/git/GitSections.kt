package com.hienlh.ppm.app.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.client.GitBranch
import com.hienlh.ppm.client.GitRepo
import com.hienlh.ppm.client.GitWorktree
import com.hienlh.ppm.client.StashEntry
import com.hienlh.ppm.client.gitBranches
import com.hienlh.ppm.client.gitStashAction
import com.hienlh.ppm.client.gitStashes
import com.hienlh.ppm.client.gitWorktreeAdd
import com.hienlh.ppm.client.gitWorktreePrune
import com.hienlh.ppm.client.gitWorktreeRemove
import com.hienlh.ppm.client.gitWorktrees
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.state.git.GitChangesView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun GitStashSection(
    model: AppModel,
    project: String,
    repo: GitRepo?,
    count: Int,
    onChanged: () -> Unit,
) {
    if (count == 0) return
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    val api = model.api

    var open by remember { mutableStateOf(false) }
    var stashes by remember { mutableStateOf<List<StashEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var busyKey by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<StashEntry?>(null) }
    var confirmDrop by remember { mutableStateOf<StashEntry?>(null) }

    suspend fun loadStashes() {
        val client = api ?: return
        loading = true
        try {
            stashes = client.gitStashes(project, repo)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            loading = false
        }
    }

    LaunchedEffect(open, count, project, repo?.path, api) {
        if (open) loadStashes()
    }

    fun runStash(action: String, entry: StashEntry) {
        val client = api ?: return
        if (busyKey != null) return
        busyKey = "$action:${entry.index}"
        scope.launch {
            try {
                val res = client.gitStashAction(project, action, entry.index, entry.hash, repo)
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
                onChanged()
                loadStashes()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.toast(
                    ErrorToastMessage(
                        title = "Could not $action the stash",
                        description = model.describe(e),
                        kind = ErrorToastMessage.Kind.ERROR,
                    ),
                )
            } finally {
                busyKey = null
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .edge(Edge.Top, tokens.border.color)
            .testTag("git-stash-section"),
    ) {
        val mobile = LocalPpmMobile.current
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (mobile) 44.dp else 32.dp)
                .clickable { open = !open }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PpmIcon(
                if (open) ProductIcon.ChevronDown else ProductIcon.ChevronRight,
                size = 14.dp,
                tint = tokens.text3.color,
            )
            BasicText(
                text = "STASHES",
                style = PpmFont.sans(11, FontWeight.SemiBold, lineHeight = 14, color = tokens.text3.color)
                    .copy(letterSpacing = 0.07.em),
            )
            CountChip(count)
            Spacer(Modifier.weight(1f))
        }

        if (open) {
            Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                if (loading && stashes.isEmpty()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PpmIcon(ProductIcon.Loader2, size = 12.dp, tint = tokens.text3.color)
                        BasicText("Loading stashes…", style = PpmFont.sans(11, lineHeight = 14, color = tokens.text3.color))
                    }
                }
                for (s in stashes) {
                    Box(Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { menuFor = s },
                                    onLongClick = { menuFor = s },
                                )
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .testTag("git-stash-row-${s.index}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PpmIcon(ProductIcon.Archive, size = 14.dp, tint = tokens.text3.color)
                            Column(Modifier.weight(1f)) {
                                TruncatedText(s.message, style = PpmFont.sans(12, lineHeight = 16, color = tokens.text.color))
                                BasicText(
                                    text = "stash@{${s.index}} · ${GitChangesView.formatRelativeTime(s.date)}",
                                    style = PpmFont.mono(10, lineHeight = 14, color = tokens.text3.color),
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .height(22.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .cssBorder(tokens.border.color, RoundedCornerShape(4.dp))
                                    .alpha(if (busyKey != null) 0.4f else 1f)
                                    .clickable(enabled = busyKey == null) { runStash("apply", s) }
                                    .padding(horizontal = 6.dp)
                                    .testTag("git-stash-apply-${s.index}"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                PpmIcon(ProductIcon.RotateCcw, size = 11.dp, tint = tokens.text2.color)
                                BasicText("Apply", style = PpmFont.sans(11, lineHeight = 14, color = tokens.text2.color))
                            }
                        }

                        if (menuFor?.hash == s.hash && menuFor?.index == s.index) {
                            Popup(
                                alignment = Alignment.BottomEnd,
                                onDismissRequest = { menuFor = null },
                                properties = PopupProperties(focusable = true),
                            ) {
                                val shape = RoundedCornerShape(6.dp)
                                Column(
                                    modifier = Modifier
                                        .width(160.dp)
                                        .clip(shape)
                                        .background(tokens.bgSolid.color)
                                        .cssBorder(tokens.border.color, shape)
                                        .padding(vertical = 4.dp),
                                ) {
                                    StashMenuRow("Apply") {
                                        menuFor = null
                                        runStash("apply", s)
                                    }
                                    StashMenuRow("Pop") {
                                        menuFor = null
                                        runStash("pop", s)
                                    }
                                    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(tokens.border.color))
                                    StashMenuRow("Drop…", destructive = true) {
                                        menuFor = null
                                        confirmDrop = s
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    confirmDrop?.let { target ->
        GitConfirm(
            title = "Drop “${target.message.ifEmpty { "stash@{${target.index}}" }}”?",
            detail = "The stash is deleted for good: nothing in PPM can bring it back.",
            confirmLabel = "Drop stash",
            destructive = true,
            onConfirm = {
                confirmDrop = null
                runStash("drop", target)
            },
            onCancel = { confirmDrop = null },
        )
    }
}

@Composable
private fun StashMenuRow(label: String, destructive: Boolean = false, onClick: () -> Unit) {
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

private val HOME_PREFIX_RE = Regex("^(/home/[^/]+|/Users/[^/]+)")

@Composable
internal fun GitWorktreePanel(
    model: AppModel,
    project: String,
    repo: GitRepo?,
    activePath: String?,
    refreshSignal: Int,
) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    val api = model.api

    var expanded by remember { mutableStateOf(false) }
    var worktrees by remember { mutableStateOf<List<GitWorktree>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var createOpen by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<GitWorktree?>(null) }
    var removing by remember { mutableStateOf(false) }

    suspend fun loadWorktrees() {
        val client = api ?: return
        loading = true
        try {
            worktrees = client.gitWorktrees(project, repo)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            loading = false
        }
    }

    LaunchedEffect(project, repo?.path, refreshSignal, api) {
        loadWorktrees()
    }

    fun prune() {
        val client = api ?: return
        scope.launch {
            try {
                client.gitWorktreePrune(project, repo)
                model.toast(ErrorToastMessage("Pruned stale worktrees", kind = ErrorToastMessage.Kind.SUCCESS))
                loadWorktrees()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.actionError(model.describe(e))
            }
        }
    }

    fun removeWorktree(force: Boolean) {
        val target = removeTarget ?: return
        val client = api ?: return
        removing = true
        scope.launch {
            try {
                client.gitWorktreeRemove(project, target.path, force, repo)
                model.toast(ErrorToastMessage("Worktree removed", kind = ErrorToastMessage.Kind.SUCCESS))
                removeTarget = null
                loadWorktrees()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.actionError(model.describe(e))
            } finally {
                removing = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .edge(Edge.Top, tokens.border.color)
            .testTag("git-worktree-section"),
    ) {
        val mobile = LocalPpmMobile.current
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (mobile) 44.dp else 32.dp)
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PpmIcon(
                if (expanded) ProductIcon.ChevronDown else ProductIcon.ChevronRight,
                size = 14.dp,
                tint = tokens.text3.color,
            )
            BasicText(
                text = "WORKTREES",
                style = PpmFont.sans(11, FontWeight.SemiBold, lineHeight = 14, color = tokens.text3.color)
                    .copy(letterSpacing = 0.07.em),
            )
            if (worktrees.isNotEmpty()) {
                CountChip(worktrees.size)
            }
            Spacer(Modifier.weight(1f))
            if (expanded) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = ::prune),
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(ProductIcon.RefreshCw, size = 12.dp, tint = tokens.text2.color)
                }
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { createOpen = true }
                        .testTag("git-worktree-add-button"),
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(ProductIcon.Plus, size = 12.dp, tint = tokens.text2.color)
                }
            }
        }

        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                if (loading && worktrees.isEmpty()) {
                    Row(
                        modifier = Modifier.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PpmIcon(ProductIcon.Loader2, size = 12.dp, tint = tokens.text3.color)
                        BasicText("Loading worktrees…", style = PpmFont.sans(11, lineHeight = 14, color = tokens.text3.color))
                    }
                } else if (worktrees.isEmpty()) {
                    BasicText(
                        "No worktrees found",
                        style = PpmFont.sans(11, lineHeight = 14, color = tokens.text3.color),
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                } else {
                    for (wt in worktrees) {
                        val isActive = activePath != null && wt.path == activePath
                        val shortPath = wt.path.replace(HOME_PREFIX_RE, "~")
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isActive) tokens.accent.opacity(0.1).color else Color.Transparent)
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .testTag("git-worktree-row-${wt.branch ?: wt.head.take(7)}"),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                PpmIcon(
                                    ProductIcon.GitBranch,
                                    size = 13.dp,
                                    tint = if (isActive) tokens.accent.color else tokens.text3.color,
                                )
                                TruncatedText(
                                    text = wt.branch ?: wt.head.take(7),
                                    style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.text.color),
                                    modifier = Modifier.weight(1f),
                                )
                                if (isActive) {
                                    PpmIcon(ProductIcon.Check, size = 12.dp, tint = tokens.accent.color)
                                }
                                if (wt.isMain) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(tokens.panel2.color)
                                            .padding(horizontal = 5.dp, vertical = 1.dp),
                                    ) {
                                        BasicText("main", style = PpmFont.mono(10, lineHeight = 12, color = tokens.text3.color))
                                    }
                                }
                                if (wt.isLocked) {
                                    PpmIcon(ProductIcon.Lock, size = 12.dp, tint = tokens.warning.color)
                                }
                                if (wt.prunable) {
                                    PpmIcon(ProductIcon.AlertTriangle, size = 12.dp, tint = tokens.error.color)
                                }
                                if (!wt.isMain) {
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .clickable { removeTarget = wt },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        PpmIcon(ProductIcon.Trash2, size = 12.dp, tint = tokens.error.color)
                                    }
                                }
                            }
                            TruncatedText(
                                text = shortPath,
                                style = PpmFont.mono(10, lineHeight = 14, color = tokens.text3.color),
                                modifier = Modifier.padding(start = 19.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (createOpen) {
        CreateWorktreeDialog(
            model = model,
            project = project,
            repo = repo,
            onClose = { createOpen = false },
            onCreated = {
                createOpen = false
                scope.launch { loadWorktrees() }
            },
        )
    }

    removeTarget?.let { target ->
        Dialog(onDismissRequest = { if (!removing) removeTarget = null }) {
            val shape = RoundedCornerShape(8.dp)
            Column(
                modifier = Modifier
                    .width(340.dp)
                    .clip(shape)
                    .background(tokens.bgSolid.color)
                    .cssBorder(tokens.border.color, shape)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicText("Remove Worktree", style = PpmFont.sans(13, FontWeight.SemiBold, lineHeight = 18, color = tokens.text.color))
                BasicText(
                    "Remove worktree at ${target.path}${if (target.branch != null) " (${target.branch})" else ""}?",
                    style = PpmFont.sans(12, lineHeight = 16, color = tokens.text2.color),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    Box(
                        modifier = Modifier
                            .height(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .cssBorder(tokens.border.color, RoundedCornerShape(6.dp))
                            .clickable(enabled = !removing) { removeTarget = null }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText("Cancel", style = PpmFont.sans(12, lineHeight = 16, color = tokens.text.color))
                    }
                    Box(
                        modifier = Modifier
                            .height(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .cssBorder(tokens.error.opacity(0.5).color, RoundedCornerShape(6.dp))
                            .clickable(enabled = !removing) { removeWorktree(true) }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText("Force Remove", style = PpmFont.sans(12, lineHeight = 16, color = tokens.error.color))
                    }
                    Box(
                        modifier = Modifier
                            .height(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(tokens.error.color)
                            .clickable(enabled = !removing) { removeWorktree(false) }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText("Remove", style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = Color.White))
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateWorktreeDialog(
    model: AppModel,
    project: String,
    repo: GitRepo?,
    onClose: () -> Unit,
    onCreated: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    val api = model.api

    var mode by remember { mutableStateOf("new") } // "new" | "existing"
    var path by remember { mutableStateOf("") }
    var newBranch by remember { mutableStateOf("") }
    var selectedBranch by remember { mutableStateOf("") }
    var branches by remember { mutableStateOf<List<GitBranch>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(project, repo?.path, api) {
        if (api == null) return@LaunchedEffect
        try {
            branches = api.gitBranches(project, repo).filter { !it.name.startsWith("remotes/") }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    fun submit() {
        val client = api ?: return
        val trimPath = path.trim()
        if (trimPath.isEmpty() || loading) return
        if (mode == "new" && newBranch.trim().isEmpty()) return
        if (mode == "existing" && selectedBranch.isEmpty()) return
        loading = true
        scope.launch {
            try {
                client.gitWorktreeAdd(
                    project = project,
                    path = trimPath,
                    branch = if (mode == "existing") selectedBranch else null,
                    newBranch = if (mode == "new") newBranch.trim() else null,
                    repo = repo,
                )
                model.toast(ErrorToastMessage("Worktree created at $trimPath", kind = ErrorToastMessage.Kind.SUCCESS))
                onCreated()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.actionError(model.describe(e))
            } finally {
                loading = false
            }
        }
    }

    Dialog(onDismissRequest = onClose) {
        val shape = RoundedCornerShape(8.dp)
        Column(
            modifier = Modifier
                .width(360.dp)
                .clip(shape)
                .background(tokens.bgSolid.color)
                .cssBorder(tokens.border.color, shape)
                .padding(14.dp)
                .testTag("git-create-worktree-dialog"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BasicText("Create Git Worktree", style = PpmFont.sans(13, FontWeight.SemiBold, lineHeight = 18, color = tokens.text.color))
            BasicText(
                "Create a new working tree for parallel branch work.",
                style = PpmFont.sans(11, lineHeight = 14, color = tokens.text3.color),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val newActive = mode == "new"
                Box(
                    modifier = Modifier
                        .height(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (newActive) tokens.accent.color else Color.Transparent)
                        .then(if (!newActive) Modifier.cssBorder(tokens.border.color, RoundedCornerShape(6.dp)) else Modifier)
                        .clickable { mode = "new" }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        "New Branch",
                        style = PpmFont.sans(11, FontWeight.Medium, lineHeight = 14, color = if (newActive) tokens.accentFg.color else tokens.text2.color),
                    )
                }
                val exActive = mode == "existing"
                Box(
                    modifier = Modifier
                        .height(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (exActive) tokens.accent.color else Color.Transparent)
                        .then(if (!exActive) Modifier.cssBorder(tokens.border.color, RoundedCornerShape(6.dp)) else Modifier)
                        .clickable { mode = "existing" }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        "Existing Branch",
                        style = PpmFont.sans(11, FontWeight.Medium, lineHeight = 14, color = if (exActive) tokens.accentFg.color else tokens.text2.color),
                    )
                }
            }

            if (mode == "new") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BasicText("Branch name", style = PpmFont.sans(11, FontWeight.Medium, lineHeight = 14, color = tokens.text2.color))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(30.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(tokens.panel2.color)
                            .cssBorder(tokens.border.color, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        BasicTextField(
                            value = newBranch,
                            onValueChange = { next ->
                                val prevAuto = "../${newBranch.replace('/', '-')}"
                                newBranch = next
                                if (path.isEmpty() || path == prevAuto) {
                                    path = if (next.isNotEmpty()) "../${next.replace('/', '-')}" else ""
                                }
                            },
                            singleLine = true,
                            textStyle = PpmFont.mono(12, lineHeight = 16, color = tokens.text.color),
                            cursorBrush = SolidColor(tokens.accent.color),
                            modifier = Modifier.fillMaxWidth().testTag("git-worktree-branch-input"),
                            decorationBox = { inner ->
                                if (newBranch.isEmpty()) {
                                    BasicText("feat/my-feature", style = PpmFont.mono(12, lineHeight = 16, color = tokens.text3.color))
                                }
                                inner()
                            },
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BasicText("Branch", style = PpmFont.sans(11, FontWeight.Medium, lineHeight = 14, color = tokens.text2.color))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 120.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(tokens.panel2.color)
                            .cssBorder(tokens.border.color, RoundedCornerShape(6.dp))
                            .verticalScroll(rememberScrollState()),
                    ) {
                        for (b in branches) {
                            val sel = b.name == selectedBranch
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (sel) tokens.accent.opacity(0.15).color else Color.Transparent)
                                    .clickable {
                                        val prevAuto = "../${selectedBranch.replace('/', '-')}"
                                        selectedBranch = b.name
                                        if (path.isEmpty() || path == prevAuto) {
                                            path = "../${b.name.replace('/', '-')}"
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                BasicText(
                                    text = b.name + if (b.current) " (current)" else "",
                                    style = PpmFont.mono(11, lineHeight = 14, color = if (sel) tokens.accent.color else tokens.text.color),
                                )
                            }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText("Worktree path", style = PpmFont.sans(11, FontWeight.Medium, lineHeight = 14, color = tokens.text2.color))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(tokens.panel2.color)
                        .cssBorder(tokens.border.color, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        value = path,
                        onValueChange = { path = it },
                        singleLine = true,
                        textStyle = PpmFont.mono(12, lineHeight = 16, color = tokens.text.color),
                        cursorBrush = SolidColor(tokens.accent.color),
                        modifier = Modifier.fillMaxWidth().testTag("git-worktree-path-input"),
                        decorationBox = { inner ->
                            if (path.isEmpty()) {
                                BasicText("../project-feat", style = PpmFont.mono(12, lineHeight = 16, color = tokens.text3.color))
                            }
                            inner()
                        },
                    )
                }
                BasicText(
                    "Relative to project root or absolute path",
                    style = PpmFont.sans(10, lineHeight = 12, color = tokens.text3.color),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                Box(
                    modifier = Modifier
                        .height(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .cssBorder(tokens.border.color, RoundedCornerShape(6.dp))
                        .clickable(onClick = onClose)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText("Cancel", style = PpmFont.sans(12, lineHeight = 16, color = tokens.text.color))
                }
                Box(
                    modifier = Modifier
                        .height(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(tokens.accent.color)
                        .alpha(if (loading) 0.4f else 1f)
                        .clickable(enabled = !loading, onClick = ::submit)
                        .padding(horizontal = 12.dp)
                        .testTag("git-worktree-create-submit"),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText("Create", style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.accentFg.color))
                }
            }
        }
    }
}
