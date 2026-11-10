package com.hienlh.ppm.app.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.LocalRedesign
import com.hienlh.ppm.client.FileHunks
import com.hienlh.ppm.client.GitRepo
import com.hienlh.ppm.client.gitDiscardHunks
import com.hienlh.ppm.client.gitHunks
import com.hienlh.ppm.client.gitStageHunks
import com.hienlh.ppm.client.gitUnstageHunks
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.state.git.CheckState
import com.hienlh.ppm.state.git.HunkSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun GitHunkStageDialog(
    model: AppModel,
    project: String,
    repo: GitRepo?,
    filePath: String,
    /** `"worktree"` or `"index"` */
    scope: String,
    onClose: () -> Unit,
    onApplied: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val redesign = LocalRedesign.current
    val coroutineScope = rememberCoroutineScope()
    val api = model.api

    var data by remember { mutableStateOf<FileHunks?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var rangeMode by remember { mutableStateOf(false) }
    var lastClickedLine by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var focusedLineKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(project, repo?.path, filePath, scope, api) {
        if (api == null) return@LaunchedEffect
        loading = true
        try {
            val loaded = api.gitHunks(project, filePath, scope, repo)
            data = loaded
            selected = HunkSelection.allChangedKeys(loaded.hunks)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            model.actionError(model.describe(e))
        } finally {
            loading = false
        }
    }

    val hunks = data?.hunks.orEmpty()
    val allKeys = remember(hunks) { HunkSelection.allChangedKeys(hunks) }
    val orderedChangedKeys = remember(allKeys) { allKeys.toList() }
    val totalChanged = allKeys.size
    val selectedCount = selected.size
    val allState = when {
        totalChanged == 0 || selectedCount == 0 -> CheckState.NONE
        selectedCount == totalChanged -> CheckState.ALL
        else -> CheckState.SOME
    }
    val actionLabel = if (scope == "worktree") "Stage" else "Unstage"
    val fileName = filePath.substringAfterLast('/')

    fun handleLineClick(hunkIndex: Int, lineIndex: Int) {
        confirmDiscard = false
        val prev = lastClickedLine
        val hunk = hunks.firstOrNull { it.index == hunkIndex }
        if (rangeMode && prev != null && prev.first == hunkIndex && hunk != null) {
            val lo = minOf(prev.second, lineIndex)
            val hi = maxOf(prev.second, lineIndex)
            val rangeKeys = (lo..hi).mapNotNull { idx ->
                val l = hunk.lines.getOrNull(idx)
                if (l != null && l.type != " ") HunkSelection.lineKey(hunkIndex, idx) else null
            }.toSet()
            val targetAdd = HunkSelection.lineKey(hunkIndex, lineIndex) !in selected
            selected = if (targetAdd) selected + rangeKeys else selected - rangeKeys
        } else {
            selected = HunkSelection.toggleLine(hunkIndex, lineIndex, selected)
        }
        lastClickedLine = hunkIndex to lineIndex
        focusedLineKey = HunkSelection.lineKey(hunkIndex, lineIndex)
    }

    fun toggleAll() {
        confirmDiscard = false
        selected = if (allState == CheckState.ALL) emptySet() else allKeys
    }

    fun apply(mode: String) {
        if (busy || api == null) return
        val items = HunkSelection.buildHunkRequest(hunks, selected)
        if (items.isEmpty()) return
        busy = true
        coroutineScope.launch {
            try {
                when (mode) {
                    "stage" -> api.gitStageHunks(project, filePath, items, repo)
                    "unstage" -> api.gitUnstageHunks(project, filePath, items, repo)
                    "discard" -> api.gitDiscardHunks(project, filePath, items, repo)
                }
                onApplied()
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

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = !mobile),
    ) {
        val shape = if (mobile) {
            RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        } else {
            RoundedCornerShape(8.dp)
        }
        val dialogWidth = if (redesign) 720.dp else 640.dp
        Box(
            modifier = if (mobile) Modifier.fillMaxSize() else Modifier,
            contentAlignment = if (mobile) Alignment.BottomCenter else Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .then(if (mobile) Modifier.fillMaxWidth() else Modifier.width(dialogWidth))
                    .heightIn(max = if (mobile) 560.dp else 520.dp)
                    .clip(shape)
                    .background(tokens.bgSolid.color)
                    .cssBorder(tokens.border.color, shape)
                    .focusTarget()
                    .onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (ev.key) {
                            Key.Escape -> {
                                onClose()
                                true
                            }
                            Key.DirectionDown -> {
                                if (orderedChangedKeys.isEmpty()) return@onPreviewKeyEvent false
                                val idx = orderedChangedKeys.indexOf(focusedLineKey)
                                val next = if (idx < 0) 0 else (idx + 1).coerceAtMost(orderedChangedKeys.lastIndex)
                                focusedLineKey = orderedChangedKeys[next]
                                true
                            }
                            Key.DirectionUp -> {
                                if (orderedChangedKeys.isEmpty()) return@onPreviewKeyEvent false
                                val idx = orderedChangedKeys.indexOf(focusedLineKey)
                                val prev = if (idx <= 0) 0 else idx - 1
                                focusedLineKey = orderedChangedKeys[prev]
                                true
                            }
                            Key.Spacebar -> {
                                val fk = focusedLineKey ?: orderedChangedKeys.firstOrNull() ?: return@onPreviewKeyEvent false
                                val parts = fk.split(":")
                                val hIdx = parts.getOrNull(0)?.toIntOrNull() ?: return@onPreviewKeyEvent false
                                val lIdx = parts.getOrNull(1)?.toIntOrNull() ?: return@onPreviewKeyEvent false
                                handleLineClick(hIdx, lIdx)
                                true
                            }
                            Key.Enter, Key.NumPadEnter -> {
                                if (!busy && selectedCount > 0) {
                                    apply(if (scope == "worktree") "stage" else "unstage")
                                    true
                                } else {
                                    false
                                }
                            }
                            else -> false
                        }
                    }
                    .testTag("git-hunk-stage-dialog"),
            ) {
            // Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .edge(Edge.Bottom, tokens.border.color)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        TruncatedText(
                            text = fileName,
                            style = PpmFont.sans(13, FontWeight.SemiBold, lineHeight = 18, color = tokens.text.color),
                        )
                        if (fileName != filePath) {
                            TruncatedText(
                                text = filePath,
                                style = PpmFont.mono(10, lineHeight = 14, color = tokens.text3.color),
                            )
                        }
                    }
                    if (scope == "worktree" && totalChanged > 0) {
                        Box(
                            modifier = Modifier
                                .size(if (mobile) 44.dp else 32.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .alpha(if (selectedCount == 0 || busy) 0.4f else 1f)
                                .semantics { contentDescription = "Discard selected lines" }
                                .clickable(enabled = selectedCount > 0 && !busy) { confirmDiscard = !confirmDiscard }
                                .testTag("git-hunk-discard-toggle"),
                            contentAlignment = Alignment.Center,
                        ) {
                            PpmIcon(ProductIcon.Trash2, size = 14.dp, tint = tokens.error.color)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(if (mobile) 44.dp else 32.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onClose),
                        contentAlignment = Alignment.Center,
                    ) {
                        PpmIcon(ProductIcon.X, size = 16.dp, tint = tokens.text3.color)
                    }
                }

                if (totalChanged > 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(if (mobile) 44.dp else 28.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CheckCell(
                            state = allState,
                            tag = "git-hunk-toggle-all",
                            onToggle = ::toggleAll,
                        )
                        BasicText(
                            text = "$selectedCount of $totalChanged ${if (totalChanged == 1) "line" else "lines"} selected",
                            style = PpmFont.sans(11, lineHeight = 14, color = tokens.text2.color),
                            modifier = Modifier.weight(1f).clickable(onClick = ::toggleAll),
                        )
                        Box(
                            modifier = Modifier
                                .height(if (mobile) 32.dp else 24.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (rangeMode) tokens.accent.opacity(0.14).color else Color.Transparent)
                                .cssBorder(
                                    if (rangeMode) tokens.accent.color else tokens.border.color,
                                    RoundedCornerShape(6.dp),
                                )
                                .clickable { rangeMode = !rangeMode }
                                .padding(horizontal = 8.dp)
                                .testTag("git-hunk-range-toggle"),
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText(
                                text = "Range",
                                style = PpmFont.sans(
                                    11,
                                    FontWeight.Medium,
                                    lineHeight = 14,
                                    color = if (rangeMode) tokens.accent.color else tokens.text2.color,
                                ),
                            )
                        }
                    }
                }
            }

            // Body
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                when {
                    loading -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        ) {
                            PpmIcon(ProductIcon.Loader2, size = 18.dp, tint = tokens.text3.color)
                            BasicText(
                                "Loading diff…",
                                style = PpmFont.sans(12, lineHeight = 16, color = tokens.text3.color),
                            )
                        }
                    }
                    data?.binary == true -> {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText(
                                "This is a binary file — it can only be staged whole.",
                                style = PpmFont.sans(12, lineHeight = 16, color = tokens.text3.color),
                            )
                        }
                    }
                    hunks.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText(
                                "No ${if (scope == "index") "staged" else "unstaged"} changes in this file.",
                                style = PpmFont.sans(12, lineHeight = 16, color = tokens.text3.color),
                            )
                        }
                    }
                    else -> {
                        Column(Modifier.fillMaxWidth()) {
                            for (hunk in hunks) {
                                val hState = HunkSelection.hunkState(hunk, selected)
                                val numbers = remember(hunk) { HunkSelection.lineNumbers(hunk) }

                                // Hunk header
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(if (mobile) 44.dp else 28.dp)
                                        .background(tokens.panel2.color)
                                        .edge(Edge.Bottom, tokens.border.color)
                                        .clickable {
                                            confirmDiscard = false
                                            selected = HunkSelection.toggleHunk(hunk, selected)
                                        }
                                        .padding(horizontal = 10.dp)
                                        .testTag("git-hunk-header-${hunk.index}"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CheckCell(
                                        state = hState,
                                        onToggle = {
                                            confirmDiscard = false
                                            selected = HunkSelection.toggleHunk(hunk, selected)
                                        },
                                    )
                                    TruncatedText(
                                        text = hunk.header,
                                        style = PpmFont.mono(11, lineHeight = 14, color = tokens.text2.color),
                                        modifier = Modifier.weight(1f),
                                    )
                                }

                                // Hunk lines
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .edge(Edge.Bottom, tokens.border.color)
                                        .horizontalScroll(rememberScrollState()),
                                ) {
                                    hunk.lines.forEachIndexed { idx, line ->
                                        val isChanged = line.type != " "
                                        val key = HunkSelection.lineKey(hunk.index, idx)
                                        val checked = isChanged && key in selected
                                        val num = numbers[idx]
                                        val rowBg = when (line.type) {
                                            "+" -> if (checked) tokens.success.opacity(0.18).color else tokens.success.opacity(0.07).color
                                            "-" -> if (checked) tokens.error.opacity(0.18).color else tokens.error.opacity(0.07).color
                                            else -> Color.Transparent
                                        }
                                        val textColor = when (line.type) {
                                            "+" -> tokens.success.color
                                            "-" -> tokens.error.color
                                            else -> tokens.text2.color
                                        }
                                        val lineHeightDp = when {
                                            isChanged && (mobile || redesign) -> 44.dp
                                            !isChanged && redesign && !mobile -> 26.dp
                                            else -> 22.dp
                                        }
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(lineHeightDp)
                                                .background(rowBg)
                                                .then(
                                                    if (isChanged) {
                                                        Modifier.clickable {
                                                            handleLineClick(hunk.index, idx)
                                                        }
                                                    } else {
                                                        Modifier
                                                    },
                                                )
                                                .padding(horizontal = 10.dp)
                                                .testTag("git-hunk-line-${hunk.index}-$idx"),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Box(
                                                modifier = Modifier.width(24.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                if (isChanged) {
                                                    CheckCell(
                                                        state = if (checked) CheckState.ALL else CheckState.NONE,
                                                        onToggle = {
                                                            handleLineClick(hunk.index, idx)
                                                        },
                                                    )
                                                }
                                            }
                                            BasicText(
                                                text = num.old?.toString() ?: "",
                                                style = PpmFont.mono(10, lineHeight = 14, color = tokens.text3.color).copy(textAlign = TextAlign.End),
                                                modifier = Modifier.width(32.dp).padding(end = 4.dp),
                                            )
                                            BasicText(
                                                text = num.new?.toString() ?: "",
                                                style = PpmFont.mono(10, lineHeight = 14, color = tokens.text3.color).copy(textAlign = TextAlign.End),
                                                modifier = Modifier.width(32.dp).padding(end = 6.dp),
                                            )
                                            BasicText(
                                                text = line.type,
                                                style = PpmFont.mono(11, FontWeight.Bold, lineHeight = 14, color = textColor),
                                                modifier = Modifier.width(14.dp),
                                            )
                                            BasicText(
                                                text = line.content.ifEmpty { " " },
                                                style = PpmFont.mono(11, lineHeight = 16, color = tokens.text.color),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Footer
            val footerBtnHeight = if (mobile) 44.dp else 36.dp
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .edge(Edge.Top, tokens.border.color)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (confirmDiscard) {
                    Box(
                        modifier = Modifier
                            .height(footerBtnHeight)
                            .clip(RoundedCornerShape(6.dp))
                            .cssBorder(tokens.border.color, RoundedCornerShape(6.dp))
                            .clickable(enabled = !busy) { confirmDiscard = false }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText("Cancel", style = PpmFont.sans(12, lineHeight = 16, color = tokens.text.color))
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(footerBtnHeight)
                            .clip(RoundedCornerShape(6.dp))
                            .background(tokens.error.color)
                            .alpha(if (busy) 0.4f else 1f)
                            .clickable(enabled = !busy) { apply("discard") }
                            .padding(horizontal = 12.dp)
                            .testTag("git-hunk-discard-confirm"),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (busy) {
                            PpmIcon(ProductIcon.Loader2, size = 14.dp, tint = Color.White)
                        } else {
                            BasicText(
                                "Discard $selectedCount ${if (selectedCount == 1) "line" else "lines"} — cannot be undone",
                                style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = Color.White),
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .height(footerBtnHeight)
                            .clip(RoundedCornerShape(6.dp))
                            .cssBorder(tokens.border.color, RoundedCornerShape(6.dp))
                            .clickable(enabled = !busy, onClick = onClose)
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText("Cancel", style = PpmFont.sans(12, lineHeight = 16, color = tokens.text.color))
                    }
                    val canApply = !busy && selectedCount > 0
                    Box(
                        modifier = Modifier
                            .height(footerBtnHeight)
                            .clip(RoundedCornerShape(6.dp))
                            .background(tokens.accent.color)
                            .alpha(if (canApply) 1f else 0.4f)
                            .clickable(enabled = canApply) { apply(if (scope == "worktree") "stage" else "unstage") }
                            .padding(horizontal = 12.dp)
                            .testTag("git-hunk-apply-button"),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            text = "$actionLabel $selectedCount ${if (selectedCount == 1) "line" else "lines"}",
                            style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.accentFg.color),
                        )
                    }
                }
            }
            }
        }
    }
}
