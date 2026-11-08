package com.hienlh.ppm.app.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.client.GitRef
import com.hienlh.ppm.client.GitRepo
import com.hienlh.ppm.client.gitCheckout
import com.hienlh.ppm.client.gitCreateBranch
import com.hienlh.ppm.client.gitRefs
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.state.git.GitChangesView
import com.hienlh.ppm.state.git.GitRefPicker
import com.hienlh.ppm.state.git.PickerRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private sealed interface BranchPickerStage {
    data object Pick : BranchPickerStage
    data class Name(val then: String) : BranchPickerStage // "create" | "from"
    data class PickRef(val purpose: String, val name: String? = null) : BranchPickerStage // "from" | "detach"
}

@Composable
internal fun GitBranchPickerModal(
    model: AppModel,
    project: String,
    repo: GitRepo?,
    onClose: () -> Unit,
    onChanged: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val scope = rememberCoroutineScope()
    val api = model.api

    var refs by remember { mutableStateOf<List<GitRef>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf<BranchPickerStage>(BranchPickerStage.Pick) }
    var query by remember { mutableStateOf("") }
    var branchName by remember { mutableStateOf("") }
    var selected by remember { mutableIntStateOf(0) }

    LaunchedEffect(project, repo?.path, api) {
        if (api == null || repo == null) return@LaunchedEffect
        loading = true
        try {
            refs = api.gitRefs(project, repo)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            model.actionError(model.describe(e))
        } finally {
            loading = false
        }
    }

    val rows = remember(refs, query, stage) {
        GitRefPicker.buildRows(refs, query, includeActions = stage is BranchPickerStage.Pick)
    }

    LaunchedEffect(rows) {
        selected = GitRefPicker.firstSelectable(rows)
    }

    fun runGit(block: suspend () -> Unit) {
        if (busy || api == null) return
        busy = true
        scope.launch {
            try {
                block()
                onChanged()
                onClose()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.actionError(model.describe(e))
            } finally {
                busy = false
            }
        }
    }

    fun handleAction(action: String) {
        query = ""
        branchName = ""
        stage = when (action) {
            "create" -> BranchPickerStage.Name("create")
            "create-from" -> BranchPickerStage.Name("from")
            else -> BranchPickerStage.PickRef("detach")
        }
    }

    fun handleRef(ref: GitRef) {
        val currentStage = stage
        if (currentStage is BranchPickerStage.PickRef) {
            if (currentStage.purpose == "from" && !currentStage.name.isNullOrEmpty()) {
                val newName = currentStage.name
                runGit {
                    api?.gitCreateBranch(project, newName, ref.name, repo)
                    model.toast(ErrorToastMessage("Switched to new branch $newName", kind = ErrorToastMessage.Kind.SUCCESS))
                }
            } else {
                runGit {
                    api?.gitCheckout(project, ref.name, "detach", repo)
                    model.toast(ErrorToastMessage("Detached at ${ref.shortHash}", kind = ErrorToastMessage.Kind.SUCCESS))
                }
            }
            return
        }
        if (ref.current) {
            onClose()
            return
        }
        val target = GitRefPicker.checkoutTarget(ref, refs)
        runGit {
            api?.gitCheckout(project, target.ref, target.mode, repo)
        }
    }

    fun submitBranchName() {
        val name = branchName.trim()
        if (name.isEmpty()) return
        val currentStage = stage as? BranchPickerStage.Name ?: return
        if (currentStage.then == "create") {
            runGit {
                api?.gitCreateBranch(project, name, null, repo)
                model.toast(ErrorToastMessage("Switched to new branch $name", kind = ErrorToastMessage.Kind.SUCCESS))
            }
        } else {
            query = ""
            stage = BranchPickerStage.PickRef("from", name)
        }
    }

    fun activateRow(index: Int) {
        when (val row = rows.getOrNull(index)) {
            is PickerRow.Action -> handleAction(row.action)
            is PickerRow.Ref -> handleRef(row.ref)
            else -> Unit
        }
    }

    Dialog(onDismissRequest = onClose) {
        val shape = RoundedCornerShape(if (mobile) 12.dp else 8.dp)
        Column(
            modifier = Modifier
                .width(if (mobile) 360.dp else 440.dp)
                .heightIn(max = if (mobile) 520.dp else 440.dp)
                .clip(shape)
                .background(tokens.bgSolid.color)
                .cssBorder(tokens.border.color, shape)
                .testTag("git-branch-picker"),
        ) {
            val currentStage = stage
            if (currentStage is BranchPickerStage.Name) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .edge(Edge.Bottom, tokens.border.color)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BasicText(
                        text = "← Back",
                        style = PpmFont.sans(12, lineHeight = 16, color = tokens.text2.color),
                        modifier = Modifier.clickable { stage = BranchPickerStage.Pick },
                    )
                    PpmIcon(ProductIcon.GitBranch, size = 14.dp, tint = tokens.accent.color)
                    BasicTextField(
                        value = branchName,
                        onValueChange = { branchName = it },
                        singleLine = true,
                        textStyle = PpmFont.mono(13, lineHeight = 18, color = tokens.text.color),
                        cursorBrush = SolidColor(tokens.accent.color),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("git-branch-name-input")
                            .onPreviewKeyEvent { ev ->
                                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Enter) {
                                    submitBranchName()
                                    true
                                } else {
                                    false
                                }
                            },
                        decorationBox = { inner ->
                            if (branchName.isEmpty()) {
                                BasicText(
                                    "New branch name (e.g. feat/my-change)",
                                    style = PpmFont.mono(12, lineHeight = 18, color = tokens.text3.color),
                                )
                            }
                            inner()
                        },
                    )
                    val canSub = branchName.trim().isNotEmpty() && !busy
                    Box(
                        modifier = Modifier
                            .height(26.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(tokens.accent.color)
                            .alpha(if (canSub) 1f else 0.4f)
                            .clickable(enabled = canSub, onClick = ::submitBranchName)
                            .padding(horizontal = 10.dp)
                            .testTag("git-branch-name-submit"),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            text = if (currentStage.then == "create") "Create" else "Next",
                            style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.accentFg.color),
                        )
                    }
                }
                BasicText(
                    text = if (currentStage.then == "create") {
                        "Press Enter to create and switch to the new branch from HEAD."
                    } else {
                        "Press Enter, then choose the branch, commit, or tag to branch from."
                    },
                    style = PpmFont.sans(12, lineHeight = 16, color = tokens.text3.color),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .edge(Edge.Bottom, tokens.border.color)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (currentStage is BranchPickerStage.PickRef) {
                        BasicText(
                            text = "← Back",
                            style = PpmFont.sans(12, lineHeight = 16, color = tokens.text2.color),
                            modifier = Modifier.clickable { stage = BranchPickerStage.Pick; query = "" },
                        )
                    }
                    PpmIcon(ProductIcon.Search, size = 14.dp, tint = tokens.text3.color)
                    val placeholder = when (currentStage) {
                        is BranchPickerStage.Pick -> "Select a branch or tag to checkout..."
                        is BranchPickerStage.PickRef -> if (currentStage.purpose == "from") {
                            "Select a base for '${currentStage.name}'..."
                        } else {
                            "Select a commit, branch, or tag to checkout detached..."
                        }
                        else -> ""
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = PpmFont.sans(13, lineHeight = 18, color = tokens.text.color),
                        cursorBrush = SolidColor(tokens.accent.color),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("git-branch-search-input")
                            .onPreviewKeyEvent { ev ->
                                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (ev.key) {
                                    Key.DirectionDown -> {
                                        selected = GitRefPicker.moveSelection(rows, selected, 1)
                                        true
                                    }
                                    Key.DirectionUp -> {
                                        selected = GitRefPicker.moveSelection(rows, selected, -1)
                                        true
                                    }
                                    Key.Enter -> {
                                        activateRow(selected)
                                        true
                                    }
                                    else -> false
                                }
                            },
                        decorationBox = { inner ->
                            if (query.isEmpty()) {
                                BasicText(placeholder, style = PpmFont.sans(13, lineHeight = 18, color = tokens.text3.color))
                            }
                            inner()
                        },
                    )
                    if (loading || busy) {
                        PpmIcon(ProductIcon.Loader2, size = 14.dp, tint = tokens.text3.color)
                    } else {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .clickable(onClick = onClose),
                            contentAlignment = Alignment.Center,
                        ) {
                            PpmIcon(ProductIcon.X, size = 14.dp, tint = tokens.text3.color)
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                ) {
                    if (!loading && rows.isEmpty()) {
                        BasicText(
                            text = "No matching branches or tags",
                            style = PpmFont.sans(12, lineHeight = 16, color = tokens.text3.color),
                            modifier = Modifier.padding(vertical = 24.dp).align(Alignment.CenterHorizontally),
                        )
                    }
                    rows.forEachIndexed { i, row ->
                        val active = i == selected
                        when (row) {
                            is PickerRow.Group -> {
                                BasicText(
                                    text = row.label.uppercase(),
                                    style = PpmFont.sans(10, FontWeight.SemiBold, lineHeight = 14, color = tokens.text3.color),
                                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
                                )
                            }
                            is PickerRow.Action -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(if (active) tokens.accent.opacity(0.1).color else Color.Transparent)
                                        .clickable(enabled = !busy) { activateRow(i) }
                                        .padding(horizontal = 12.dp, vertical = 7.dp)
                                        .testTag("git-picker-action-${row.action}"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    PpmIcon(ProductIcon.Plus, size = 14.dp, tint = tokens.accent.color)
                                    BasicText(row.label, style = PpmFont.sans(12, lineHeight = 16, color = tokens.text.color))
                                }
                            }
                            is PickerRow.Ref -> {
                                val r = row.ref
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(if (active) tokens.accent.opacity(0.1).color else Color.Transparent)
                                        .clickable(enabled = !busy) { activateRow(i) }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                        .testTag("git-picker-ref-${r.name}"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    val icon = when {
                                        r.current -> ProductIcon.Check
                                        r.kind == "branch" -> ProductIcon.GitBranch
                                        r.kind == "remote" -> ProductIcon.Cloud
                                        else -> ProductIcon.Tag
                                    }
                                    val iconTint = if (r.current) tokens.accent.color else tokens.text3.color
                                    PpmIcon(icon, size = 14.dp, tint = iconTint)
                                    Column(Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            TruncatedText(
                                                text = r.name,
                                                style = PpmFont.sans(
                                                    12,
                                                    if (r.current) FontWeight.Medium else FontWeight.Normal,
                                                    lineHeight = 16,
                                                    color = if (r.current) tokens.accent.color else tokens.text.color,
                                                ),
                                                modifier = Modifier.weight(1f, fill = false),
                                            )
                                            if (r.behind > 0) {
                                                BasicText("↓${r.behind}", style = PpmFont.mono(10, lineHeight = 12, color = tokens.text3.color))
                                            }
                                            if (r.ahead > 0) {
                                                BasicText("↑${r.ahead}", style = PpmFont.mono(10, lineHeight = 12, color = tokens.text3.color))
                                            }
                                            if (r.upstreamGone) {
                                                BasicText("gone", style = PpmFont.mono(10, lineHeight = 12, color = tokens.warning.color))
                                            }
                                            BasicText("·", style = PpmFont.sans(10, lineHeight = 12, color = tokens.text3.opacity(0.6).color))
                                            BasicText(
                                                text = GitChangesView.formatRelativeTime(r.date),
                                                style = PpmFont.sans(10, lineHeight = 12, color = tokens.text3.color),
                                            )
                                        }
                                        val detail = remember(r) { GitRefPicker.refDetail(r) }
                                        if (detail.isNotEmpty()) {
                                            TruncatedText(
                                                text = detail,
                                                style = PpmFont.sans(11, lineHeight = 14, color = tokens.text3.color),
                                            )
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
}
