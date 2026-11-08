package com.hienlh.ppm.app.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.SettingsButton
import com.hienlh.ppm.app.ui.SettingsTextInput
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.extensions.WebviewPanelUI
import com.hienlh.ppm.state.git.GIT_GRAPH_VIEW_BLAME
import com.hienlh.ppm.state.git.GIT_GRAPH_VIEW_COMPARE
import com.hienlh.ppm.state.git.GIT_GRAPH_VIEW_FILE_HISTORY
import com.hienlh.ppm.state.git.GIT_GRAPH_VIEW_REBASE
import com.hienlh.ppm.state.git.GIT_GRAPH_VIEW_REFLOG
import com.hienlh.ppm.state.git.GIT_REBASE_ACTIONS
import com.hienlh.ppm.state.git.GIT_REFLOG_ACTION_KINDS
import com.hienlh.ppm.state.git.GitBlameData
import com.hienlh.ppm.state.git.GitBlameExtMessage
import com.hienlh.ppm.state.git.GitBlameWebviewMessages
import com.hienlh.ppm.state.git.GitCompareCounts
import com.hienlh.ppm.state.git.GitCompareExtMessage
import com.hienlh.ppm.state.git.GitCompareFileChange
import com.hienlh.ppm.state.git.GitCompareRefEntry
import com.hienlh.ppm.state.git.GitCompareWebviewMessages
import com.hienlh.ppm.state.git.GitFileHistoryEntry
import com.hienlh.ppm.state.git.GitFileHistoryExtMessage
import com.hienlh.ppm.state.git.GitFileHistoryWebviewMessages
import com.hienlh.ppm.state.git.GitRebaseExtMessage
import com.hienlh.ppm.state.git.GitRebasePlanEntry
import com.hienlh.ppm.state.git.GitRebaseWebviewMessages
import com.hienlh.ppm.state.git.GitReflogEntry
import com.hienlh.ppm.state.git.GitReflogExtMessage
import com.hienlh.ppm.state.git.GitReflogWebviewMessages
import com.hienlh.ppm.state.git.blameHeatColorRgb
import com.hienlh.ppm.state.git.filterReflogEntries
import com.hienlh.ppm.state.git.formatGitGraphDate
import com.hienlh.ppm.state.git.gitGraphAuthorInitials
import com.hienlh.ppm.state.git.isGitBlameUncommittedHash
import com.hienlh.ppm.state.git.moveRebasePlanItem
import com.hienlh.ppm.state.git.parseGitBlameExtMessage
import com.hienlh.ppm.state.git.parseGitCompareExtMessage
import com.hienlh.ppm.state.git.parseGitFileHistoryExtMessage
import com.hienlh.ppm.state.git.parseGitRebaseExtMessage
import com.hienlh.ppm.state.git.parseGitReflogExtMessage
import com.hienlh.ppm.state.git.validateRebasePlanNewestFirst
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.serialization.json.JsonObject

/**
 * Phase 08d: Native Compose UI for the 5 Git Graph insights panels:
 * - `git-graph.blame` ([GitBlameNativeContent])
 * - `git-graph.fileHistory` ([GitFileHistoryNativeContent])
 * - `git-graph.compare` ([GitCompareNativeContent])
 * - `git-graph.interactiveRebase` ([GitInteractiveRebaseNativeContent])
 * - `git-graph.reflog` ([GitReflogNativeContent])
 */

@Composable
fun GitInsightsNativePanelBody(
    model: AppModel,
    tab: WorkspaceTab,
    panel: WebviewPanelUI,
    viewType: String,
    modifier: Modifier = Modifier,
) {
    when (viewType) {
        GIT_GRAPH_VIEW_BLAME -> GitBlameNativePanelBody(model = model, panel = panel, modifier = modifier)
        GIT_GRAPH_VIEW_FILE_HISTORY -> GitFileHistoryNativePanelBody(model = model, panel = panel, modifier = modifier)
        GIT_GRAPH_VIEW_COMPARE -> GitCompareNativePanelBody(model = model, panel = panel, modifier = modifier)
        GIT_GRAPH_VIEW_REBASE -> GitInteractiveRebaseNativePanelBody(model = model, panel = panel, modifier = modifier)
        GIT_GRAPH_VIEW_REFLOG -> GitReflogNativePanelBody(model = model, tab = tab, panel = panel, modifier = modifier)
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// Shared Helpers (Avatar badge, File Picker Modal, Modal Overlay)
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun GitInsightAvatar(
    author: String,
    email: String,
    sizeDp: Int = 18,
) {
    val tokens = LocalPpmTokens.current
    val initials = remember(author) { gitGraphAuthorInitials(author) }
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(tokens.accent.color.copy(alpha = 0.25f)),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            initials,
            style = ppmText((sizeDp * 0.5f), FontWeight.Bold).copy(color = tokens.text.color),
        )
    }
}

@Composable
private fun GitInsightModalOverlay(
    testTag: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(16.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 280.dp, max = 520.dp)
                .heightIn(max = 460.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(tokens.bgSolid.color)
                .cssBorder(tokens.border.color)
                .padding(14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                SettingsButton(label = "✕", onClick = onDismiss)
            }
            content()
        }
    }
}

@Composable
private fun GitInsightFilePickerModal(
    testTagPrefix: String,
    files: List<String>,
    total: Int,
    onRequestFiles: (String) -> Unit,
    onChooseFile: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        onRequestFiles("")
    }

    GitInsightModalOverlay(
        testTag = "$testTagPrefix-file-picker",
        onDismiss = onDismiss,
    ) {
        BasicText(
            "Choose a tracked file ($total total)",
            style = ppmText(13f, FontWeight.SemiBold).copy(color = tokens.text.color),
        )
        SettingsTextInput(
            value = query,
            onValueChange = {
                query = it
                onRequestFiles(it)
            },
            placeholder = "Filter files by path…",
            modifier = Modifier.fillMaxWidth().testTag("$testTagPrefix-file-query"),
        )
        if (files.isEmpty()) {
            BasicText(
                "No matching files.",
                style = ppmText(11f).copy(color = tokens.text3.color),
            )
        } else {
            for (path in files.take(100)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            onDismiss()
                            onChooseFile(path)
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("$testTagPrefix-file-item-$path"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        path,
                        style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text.color),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 8d.1 Blame Panel (`git-graph.blame`)
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun GitBlameNativePanelBody(
    model: AppModel,
    panel: WebviewPanelUI,
    modifier: Modifier = Modifier,
) {
    val ext = model.extensions
    var loading by remember(panel.id) { mutableStateOf(false) }
    var errorMessage by remember(panel.id) { mutableStateOf<String?>(null) }
    var blameData by remember(panel.id) { mutableStateOf<GitBlameData?>(null) }
    var fileList by remember(panel.id) { mutableStateOf<List<String>>(emptyList()) }
    var totalFiles by remember(panel.id) { mutableStateOf(0) }
    var pickerOpen by remember(panel.id) { mutableStateOf(false) }

    fun sendToExt(msg: JsonObject) {
        ext?.sendWebviewMessage(panel.id, msg)
    }

    LaunchedEffect(panel.id, ext) {
        if (ext == null) return@LaunchedEffect
        sendToExt(GitBlameWebviewMessages.ready())
        ext.webviewMessages.collect { (msgPanelId, rawMsg) ->
            if (msgPanelId != panel.id) return@collect
            when (val msg = parseGitBlameExtMessage(rawMsg)) {
                is GitBlameExtMessage.Init -> {
                    if (msg.target == null) {
                        pickerOpen = true
                    }
                }
                is GitBlameExtMessage.Loading -> {
                    loading = true
                    errorMessage = null
                }
                is GitBlameExtMessage.LoadFiles -> {
                    fileList = msg.files
                    totalFiles = msg.total
                }
                is GitBlameExtMessage.LoadBlame -> {
                    loading = false
                    errorMessage = null
                    blameData = msg.data
                }
                is GitBlameExtMessage.ErrorMessage -> {
                    loading = false
                    errorMessage = msg.message
                }
                null -> Unit
            }
        }
    }

    GitBlameNativeContent(
        blameData = blameData,
        loading = loading,
        errorMessage = errorMessage,
        fileList = fileList,
        totalFiles = totalFiles,
        pickerOpen = pickerOpen,
        onSetPickerOpen = { pickerOpen = it },
        onSendMessage = { sendToExt(it) },
        modifier = modifier,
    )
}

@Composable
internal fun GitBlameNativeContent(
    blameData: GitBlameData?,
    loading: Boolean = false,
    errorMessage: String? = null,
    fileList: List<String> = emptyList(),
    totalFiles: Int = 0,
    pickerOpen: Boolean = false,
    onSetPickerOpen: (Boolean) -> Unit = {},
    onSendMessage: (JsonObject) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val listState = rememberLazyListState()

    var selectedHash by remember(blameData?.filePath, blameData?.rev) { mutableStateOf<String?>(null) }
    var selectedLine by remember(blameData?.filePath, blameData?.rev) { mutableStateOf<Int?>(null) }

    val focusLine = blameData?.focusLine
    LaunchedEffect(blameData, focusLine) {
        if (blameData != null && focusLine != null && focusLine > 0) {
            val idx = blameData.lines.indexOfFirst { it.finalLine == focusLine }
            if (idx >= 0) {
                listState.scrollToItem(idx)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .testTag("git-blame-root"),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Toolbar (`git-blame-toolbar`)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tokens.panel.color)
                    .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("git-blame-toolbar"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsButton(
                    label = "Open file…",
                    modifier = Modifier.testTag("git-blame-pick-btn"),
                    onClick = { onSetPickerOpen(!pickerOpen) },
                )
                BasicText(
                    blameData?.filePath?.ifEmpty { "No file" } ?: "No file",
                    style = ppmText(12f, FontWeight.SemiBold, FontKind.MONO).copy(color = tokens.text.color),
                    modifier = Modifier.testTag("git-blame-file-label"),
                )
                if (!blameData?.rev.isNullOrEmpty()) {
                    BasicText(
                        "at ${blameData?.rev?.take(7)}",
                        style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.accent.color),
                        modifier = Modifier.testTag("git-blame-rev-label"),
                    )
                }
                Spacer(Modifier.weight(1f))
                val hasFile = !blameData?.filePath.isNullOrEmpty()
                SettingsButton(
                    label = "History",
                    modifier = Modifier.testTag("git-blame-history-btn"),
                    onClick = {
                        val fp = blameData?.filePath
                        if (!fp.isNullOrEmpty()) {
                            onSendMessage(GitBlameWebviewMessages.openHistory(fp))
                        }
                    },
                )
                SettingsButton(
                    label = "Reload",
                    modifier = Modifier.testTag("git-blame-reload-btn"),
                    onClick = {
                        if (hasFile) {
                            onSendMessage(
                                GitBlameWebviewMessages.requestBlame(
                                    filePath = blameData!!.filePath,
                                    rev = blameData.rev,
                                ),
                            )
                        }
                    },
                )
            }

            // Error Banner
            if (!errorMessage.isNullOrEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.error.opacity(0.14).color)
                        .cssBorder(tokens.error.color, sides = CssSides.Bottom)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("git-blame-error-banner"),
                ) {
                    BasicText(
                        errorMessage,
                        style = ppmText(11f).copy(color = tokens.error.color),
                    )
                }
            }

            // Main Blame Lines List
            val lines = blameData?.lines.orEmpty()
            if (loading) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BasicText("Loading blame…", style = ppmText(12f).copy(color = tokens.text3.color))
                }
            } else if (blameData == null || blameData.filePath.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BasicText(
                        "Choose a file to see who last touched each line.",
                        style = ppmText(12f).copy(color = tokens.text3.color),
                    )
                }
            } else if (lines.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BasicText(
                        "This file has no lines to blame.",
                        style = ppmText(12f).copy(color = tokens.text3.color),
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("git-blame-list"),
                ) {
                    itemsIndexed(lines, key = { _, line -> line.finalLine }) { idx, line ->
                        val prevHash = lines.getOrNull(idx - 1)?.hash
                        val repeat = line.hash == prevHash
                        val uncommitted = isGitBlameUncommittedHash(line.hash)
                        val commitInfo = blameData.commits[line.hash]
                        val weight = blameData.weights[line.hash] ?: 0.5
                        val (r, g, b) = blameHeatColorRgb(weight)
                        val heatColor = if (uncommitted) tokens.warning.color else Color(r, g, b)
                        val isFocused = focusLine == line.finalLine || selectedLine == line.finalLine

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isFocused) tokens.accent.opacity(0.15).color else Color.Transparent,
                                )
                                .testTag("git-blame-row-${line.finalLine}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 4dp Age Heatmap Strip
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .height(22.dp)
                                    .background(heatColor)
                                    .testTag("git-blame-heat-${line.finalLine}"),
                            )
                            // Gutter (`190dp` on tablet, `118dp` on mobile)
                            Row(
                                modifier = Modifier
                                    .width(if (mobile) 118.dp else 190.dp)
                                    .height(22.dp)
                                    .cssBorder(tokens.border.color, sides = CssSides.End)
                                    .clickable {
                                        if (!uncommitted && commitInfo != null) {
                                            selectedHash = line.hash
                                            selectedLine = line.finalLine
                                        }
                                    }
                                    .padding(horizontal = 6.dp)
                                    .alpha(if (repeat && !isFocused) 0.25f else 1f)
                                    .testTag("git-blame-gutter-${line.finalLine}"),
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (!uncommitted) {
                                    GitInsightAvatar(
                                        author = commitInfo?.author.orEmpty(),
                                        email = commitInfo?.authorMail.orEmpty(),
                                        sizeDp = 14,
                                    )
                                }
                                BasicText(
                                    if (uncommitted) "Uncommitted" else (commitInfo?.author?.ifEmpty { "?" } ?: "?"),
                                    style = ppmText(
                                        10f,
                                        kind = FontKind.MONO,
                                    ).copy(
                                        color = if (uncommitted) tokens.warning.color else tokens.text2.color,
                                        fontStyle = if (uncommitted) FontStyle.Italic else FontStyle.Normal,
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (!mobile && !uncommitted) {
                                    BasicText(
                                        line.hash.take(7),
                                        style = ppmText(10f, kind = FontKind.MONO).copy(color = tokens.text3.color),
                                    )
                                }
                            }
                            // Line number
                            Box(
                                modifier = Modifier
                                    .width(if (mobile) 40.dp else 52.dp)
                                    .height(22.dp)
                                    .cssBorder(tokens.border.color, sides = CssSides.End)
                                    .padding(horizontal = 6.dp),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                BasicText(
                                    line.finalLine.toString(),
                                    style = ppmText(10f, kind = FontKind.MONO).copy(color = tokens.text3.color),
                                    modifier = Modifier.testTag("git-blame-lineno-${line.finalLine}"),
                                )
                            }
                            // Code line
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(22.dp)
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                BasicText(
                                    line.content.ifEmpty { " " },
                                    style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text.color),
                                    maxLines = 1,
                                    overflow = TextOverflow.Clip,
                                    modifier = Modifier.testTag("git-blame-code-${line.finalLine}"),
                                )
                            }
                        }
                    }
                }
            }

            // Selected Commit Detail Strip (`git-blame-detail`)
            val activeCommit = selectedHash?.let { blameData?.commits?.get(it) }
            if (activeCommit != null && blameData != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.panel.color)
                        .cssBorder(tokens.border.color, sides = CssSides.Top)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("git-blame-detail"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GitInsightAvatar(activeCommit.author, activeCommit.authorMail, sizeDp = 18)
                        BasicText(
                            activeCommit.summary.ifEmpty { "(no subject)" },
                            style = ppmText(12f, FontWeight.SemiBold).copy(color = tokens.text.color),
                        )
                    }
                    val renamedNote = if (!activeCommit.filename.isNullOrEmpty() && activeCommit.filename != blameData.filePath) {
                        " · was ${activeCommit.filename}"
                    } else {
                        ""
                    }
                    BasicText(
                        "${activeCommit.author.ifEmpty { "?" }} <${activeCommit.authorMail}> · ${formatGitGraphDate(activeCommit.authorTime, "relative")} · ${activeCommit.hash.take(10)}$renamedNote",
                        style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text3.color),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SettingsButton(
                            label = "Open this commit's diff",
                            modifier = Modifier.testTag("git-blame-act-diff"),
                            onClick = {
                                onSendMessage(GitBlameWebviewMessages.openDiff(blameData.filePath, activeCommit.hash))
                            },
                        )
                        SettingsButton(
                            label = "Blame before this commit",
                            modifier = Modifier.testTag("git-blame-act-prev"),
                            onClick = {
                                onSendMessage(GitBlameWebviewMessages.blamePrevious(blameData.filePath, activeCommit.hash))
                            },
                        )
                        val ln = selectedLine
                        if (ln != null) {
                            SettingsButton(
                                label = "History of line $ln",
                                modifier = Modifier.testTag("git-blame-act-line-history"),
                                onClick = {
                                    onSendMessage(GitBlameWebviewMessages.openLineHistory(blameData.filePath, ln))
                                },
                            )
                        }
                        SettingsButton(
                            label = "Close",
                            modifier = Modifier.testTag("git-blame-act-close"),
                            onClick = {
                                selectedHash = null
                                selectedLine = null
                            },
                        )
                    }
                }
            }
        }

        if (pickerOpen) {
            GitInsightFilePickerModal(
                testTagPrefix = "git-blame",
                files = fileList,
                total = totalFiles,
                onRequestFiles = { q -> onSendMessage(GitBlameWebviewMessages.requestFiles(q)) },
                onChooseFile = { fp -> onSendMessage(GitBlameWebviewMessages.requestBlame(fp)) },
                onDismiss = { onSetPickerOpen(false) },
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 8d.2 File History Panel (`git-graph.fileHistory`)
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun GitFileHistoryNativePanelBody(
    model: AppModel,
    panel: WebviewPanelUI,
    modifier: Modifier = Modifier,
) {
    val ext = model.extensions
    var loading by remember(panel.id) { mutableStateOf(false) }
    var errorMessage by remember(panel.id) { mutableStateOf<String?>(null) }
    var filePath by remember(panel.id) { mutableStateOf<String?>(null) }
    var lineStart by remember(panel.id) { mutableStateOf<Int?>(null) }
    var lineEnd by remember(panel.id) { mutableStateOf<Int?>(null) }
    var entries by remember(panel.id) { mutableStateOf<List<GitFileHistoryEntry>>(emptyList()) }
    var hasMore by remember(panel.id) { mutableStateOf(false) }
    var fileList by remember(panel.id) { mutableStateOf<List<String>>(emptyList()) }
    var totalFiles by remember(panel.id) { mutableStateOf(0) }
    var pickerOpen by remember(panel.id) { mutableStateOf(false) }

    fun sendToExt(msg: JsonObject) {
        ext?.sendWebviewMessage(panel.id, msg)
    }

    LaunchedEffect(panel.id, ext) {
        if (ext == null) return@LaunchedEffect
        sendToExt(GitFileHistoryWebviewMessages.ready())
        ext.webviewMessages.collect { (msgPanelId, rawMsg) ->
            if (msgPanelId != panel.id) return@collect
            when (val msg = parseGitFileHistoryExtMessage(rawMsg)) {
                is GitFileHistoryExtMessage.Init -> {
                    if (msg.target == null) {
                        pickerOpen = true
                    }
                }
                is GitFileHistoryExtMessage.Loading -> {
                    loading = true
                    errorMessage = null
                }
                is GitFileHistoryExtMessage.LoadFiles -> {
                    fileList = msg.files
                    totalFiles = msg.total
                }
                is GitFileHistoryExtMessage.LoadHistory -> {
                    loading = false
                    errorMessage = null
                    filePath = msg.filePath
                    lineStart = msg.lineStart
                    lineEnd = msg.lineEnd
                    entries = if (msg.append) entries + msg.entries else msg.entries
                    hasMore = msg.hasMore
                }
                is GitFileHistoryExtMessage.ErrorMessage -> {
                    loading = false
                    errorMessage = msg.message
                }
                null -> Unit
            }
        }
    }

    GitFileHistoryNativeContent(
        filePath = filePath,
        lineStart = lineStart,
        lineEnd = lineEnd,
        entries = entries,
        hasMore = hasMore,
        loading = loading,
        errorMessage = errorMessage,
        fileList = fileList,
        totalFiles = totalFiles,
        pickerOpen = pickerOpen,
        onSetPickerOpen = { pickerOpen = it },
        onSendMessage = { sendToExt(it) },
        modifier = modifier,
    )
}

@Composable
internal fun GitFileHistoryNativeContent(
    filePath: String?,
    lineStart: Int? = null,
    lineEnd: Int? = null,
    entries: List<GitFileHistoryEntry> = emptyList(),
    hasMore: Boolean = false,
    loading: Boolean = false,
    errorMessage: String? = null,
    fileList: List<String> = emptyList(),
    totalFiles: Int = 0,
    pickerOpen: Boolean = false,
    onSetPickerOpen: (Boolean) -> Unit = {},
    onSendMessage: (JsonObject) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    var linesMode by remember(lineStart, lineEnd) { mutableStateOf(lineStart != null && lineEnd != null) }
    var startInput by remember(lineStart) { mutableStateOf(lineStart?.toString().orEmpty()) }
    var endInput by remember(lineEnd) { mutableStateOf(lineEnd?.toString().orEmpty()) }
    var localError by remember { mutableStateOf<String?>(null) }

    val displayError = localError ?: errorMessage

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .testTag("git-fh-root"),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Toolbar (`git-fh-toolbar`)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tokens.panel.color)
                    .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("git-fh-toolbar"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsButton(
                    label = "Open file…",
                    modifier = Modifier.testTag("git-fh-pick-btn"),
                    onClick = { onSetPickerOpen(!pickerOpen) },
                )
                val labelText = if (!filePath.isNullOrEmpty()) {
                    filePath + (if (lineStart != null && lineEnd != null) " :$lineStart–$lineEnd" else "")
                } else {
                    "No file"
                }
                BasicText(
                    labelText,
                    style = ppmText(12f, FontWeight.SemiBold, FontKind.MONO).copy(color = tokens.text.color),
                    modifier = Modifier.testTag("git-fh-file-label"),
                )
                Spacer(Modifier.weight(1f))
                SettingsButton(
                    label = "${if (linesMode) "☑" else "☐"} Lines",
                    primary = linesMode,
                    modifier = Modifier.testTag("git-fh-lines-toggle"),
                    onClick = {
                        linesMode = !linesMode
                        if (!linesMode && !filePath.isNullOrEmpty()) {
                            localError = null
                            onSendMessage(GitFileHistoryWebviewMessages.requestHistory(filePath = filePath, append = false))
                        }
                    },
                )
                if (linesMode) {
                    SettingsTextInput(
                        value = startInput,
                        onValueChange = { startInput = it },
                        placeholder = "from",
                        modifier = Modifier.width(64.dp).testTag("git-fh-line-start"),
                    )
                    SettingsTextInput(
                        value = endInput,
                        onValueChange = { endInput = it },
                        placeholder = "to",
                        modifier = Modifier.width(64.dp).testTag("git-fh-line-end"),
                    )
                    SettingsButton(
                        label = "Apply",
                        primary = true,
                        modifier = Modifier.testTag("git-fh-apply-lines-btn"),
                        onClick = {
                            val fp = filePath ?: return@SettingsButton
                            val a = startInput.trim().toIntOrNull()
                            val b = endInput.trim().toIntOrNull()
                            if (a == null || b == null || a < 1 || b < 1) {
                                localError = "Enter two line numbers of 1 or more."
                            } else {
                                localError = null
                                onSendMessage(
                                    GitFileHistoryWebviewMessages.requestHistory(
                                        filePath = fp,
                                        append = false,
                                        lineStart = a,
                                        lineEnd = b,
                                    ),
                                )
                            }
                        },
                    )
                }
                SettingsButton(
                    label = "Blame",
                    modifier = Modifier.testTag("git-fh-blame-btn"),
                    onClick = {
                        if (!filePath.isNullOrEmpty()) {
                            onSendMessage(GitFileHistoryWebviewMessages.openBlame(filePath))
                        }
                    },
                )
                SettingsButton(
                    label = "Reload",
                    modifier = Modifier.testTag("git-fh-reload-btn"),
                    onClick = {
                        if (!filePath.isNullOrEmpty()) {
                            onSendMessage(
                                GitFileHistoryWebviewMessages.requestHistory(
                                    filePath = filePath,
                                    append = false,
                                    lineStart = lineStart,
                                    lineEnd = lineEnd,
                                ),
                            )
                        }
                    },
                )
            }

            if (!displayError.isNullOrEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.error.opacity(0.14).color)
                        .cssBorder(tokens.error.color, sides = CssSides.Bottom)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("git-fh-error-banner"),
                ) {
                    BasicText(displayError, style = ppmText(11f).copy(color = tokens.error.color))
                }
            }

            if (loading && entries.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BasicText("Loading file history…", style = ppmText(12f).copy(color = tokens.text3.color))
                }
            } else if (filePath.isNullOrEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BasicText(
                        "Choose a file to see every commit that touched it.",
                        style = ppmText(12f).copy(color = tokens.text3.color),
                    )
                }
            } else if (entries.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BasicText(
                        "No commits found for this file.",
                        style = ppmText(12f).copy(color = tokens.text3.color),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("git-fh-list"),
                ) {
                    items(entries, key = { it.hash }) { e ->
                        val effectivePath = e.path.ifEmpty { filePath }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                                .clickable {
                                    onSendMessage(GitFileHistoryWebviewMessages.openDiff(effectivePath, e.hash))
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .testTag("git-fh-row-${e.hash}"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                BasicText(
                                    e.subject,
                                    style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text.color),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    GitInsightAvatar(e.author, e.authorEmail, sizeDp = 14)
                                    BasicText(e.author, style = ppmText(10f).copy(color = tokens.text3.color))
                                    BasicText(
                                        formatGitGraphDate(e.authorDate, "relative"),
                                        style = ppmText(10f).copy(color = tokens.text3.color),
                                    )
                                    BasicText(
                                        e.hash.take(7),
                                        style = ppmText(10f, kind = FontKind.MONO).copy(color = tokens.text3.color),
                                    )
                                    if (e.status.isNotEmpty()) {
                                        BasicText(
                                            e.status,
                                            style = ppmText(9f, FontWeight.Bold, FontKind.MONO).copy(color = tokens.accent.color),
                                        )
                                    }
                                    if (!e.oldPath.isNullOrEmpty()) {
                                        BasicText(
                                            "renamed from ${e.oldPath}",
                                            style = ppmText(10f, kind = FontKind.MONO).copy(color = tokens.accent.color),
                                        )
                                    }
                                }
                            }
                            SettingsButton(
                                label = "Blame",
                                modifier = Modifier.testTag("git-fh-row-blame-${e.hash}"),
                                onClick = {
                                    onSendMessage(GitFileHistoryWebviewMessages.openBlame(effectivePath, e.hash))
                                },
                            )
                        }
                    }
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (hasMore) {
                                SettingsButton(
                                    label = "Load more",
                                    modifier = Modifier.testTag("git-fh-load-more-btn"),
                                    onClick = {
                                        onSendMessage(
                                            GitFileHistoryWebviewMessages.requestHistory(
                                                filePath = filePath,
                                                append = true,
                                                skip = entries.size,
                                                lineStart = lineStart,
                                                lineEnd = lineEnd,
                                            ),
                                        )
                                    },
                                )
                            } else {
                                BasicText("End of history", style = ppmText(10f).copy(color = tokens.text3.color))
                            }
                        }
                    }
                }
            }
        }

        if (pickerOpen) {
            GitInsightFilePickerModal(
                testTagPrefix = "git-fh",
                files = fileList,
                total = totalFiles,
                onRequestFiles = { q -> onSendMessage(GitFileHistoryWebviewMessages.requestFiles(q)) },
                onChooseFile = { fp ->
                    linesMode = false
                    onSendMessage(GitFileHistoryWebviewMessages.requestHistory(filePath = fp, append = false))
                },
                onDismiss = { onSetPickerOpen(false) },
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 8d.3 Compare Panel (`git-graph.compare`)
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun GitCompareNativePanelBody(
    model: AppModel,
    panel: WebviewPanelUI,
    modifier: Modifier = Modifier,
) {
    val ext = model.extensions
    var refs by remember(panel.id) { mutableStateOf<List<GitCompareRefEntry>>(emptyList()) }
    var ref1 by remember(panel.id) { mutableStateOf("") }
    var ref2 by remember(panel.id) { mutableStateOf("") }
    var mode by remember(panel.id) { mutableStateOf("three-dot") }
    var commits by remember(panel.id) { mutableStateOf<List<GitFileHistoryEntry>>(emptyList()) }
    var files by remember(panel.id) { mutableStateOf<List<GitCompareFileChange>>(emptyList()) }
    var counts by remember(panel.id) { mutableStateOf<GitCompareCounts?>(null) }
    var truncated by remember(panel.id) { mutableStateOf(false) }
    var loading by remember(panel.id) { mutableStateOf(false) }
    var errorMessage by remember(panel.id) { mutableStateOf<String?>(null) }

    fun sendToExt(msg: JsonObject) {
        ext?.sendWebviewMessage(panel.id, msg)
    }

    LaunchedEffect(panel.id, ext) {
        if (ext == null) return@LaunchedEffect
        sendToExt(GitCompareWebviewMessages.ready())
        ext.webviewMessages.collect { (msgPanelId, rawMsg) ->
            if (msgPanelId != panel.id) return@collect
            when (val msg = parseGitCompareExtMessage(rawMsg)) {
                is GitCompareExtMessage.Init -> {
                    refs = msg.refs
                    val mainRef = msg.refs.firstOrNull { it.kind == "head" && (it.name == "main" || it.name == "master") }
                    val r1 = msg.target?.ref1
                        ?: if (mainRef != null && mainRef.name != msg.current) mainRef.name else msg.refs.firstOrNull()?.name.orEmpty()
                    val r2 = msg.target?.ref2 ?: msg.current ?: msg.refs.getOrNull(1)?.name.orEmpty()
                    ref1 = r1
                    ref2 = r2
                    if (r1.isNotEmpty() && r2.isNotEmpty() && r1 != r2) {
                        sendToExt(GitCompareWebviewMessages.requestCompare(r1, r2, mode))
                    }
                }
                is GitCompareExtMessage.Loading -> {
                    loading = true
                    errorMessage = null
                }
                is GitCompareExtMessage.LoadCompare -> {
                    loading = false
                    errorMessage = null
                    ref1 = msg.ref1
                    ref2 = msg.ref2
                    mode = msg.mode
                    commits = msg.commits
                    files = msg.files
                    counts = msg.counts
                    truncated = msg.truncated
                }
                is GitCompareExtMessage.ErrorMessage -> {
                    loading = false
                    errorMessage = msg.message
                }
                null -> Unit
            }
        }
    }

    GitCompareNativeContent(
        refs = refs,
        ref1 = ref1,
        ref2 = ref2,
        mode = mode,
        commits = commits,
        files = files,
        counts = counts,
        truncated = truncated,
        loading = loading,
        errorMessage = errorMessage,
        onSelectRef1 = { ref1 = it },
        onSelectRef2 = { ref2 = it },
        onSelectMode = { nextMode ->
            mode = nextMode
            if (ref1.isNotEmpty() && ref2.isNotEmpty() && ref1 != ref2) {
                sendToExt(GitCompareWebviewMessages.requestCompare(ref1, ref2, nextMode))
            }
        },
        onSendMessage = { sendToExt(it) },
        modifier = modifier,
    )
}

@Composable
internal fun GitCompareNativeContent(
    refs: List<GitCompareRefEntry>,
    ref1: String,
    ref2: String,
    mode: String = "three-dot",
    commits: List<GitFileHistoryEntry> = emptyList(),
    files: List<GitCompareFileChange> = emptyList(),
    counts: GitCompareCounts? = null,
    truncated: Boolean = false,
    loading: Boolean = false,
    errorMessage: String? = null,
    onSelectRef1: (String) -> Unit = {},
    onSelectRef2: (String) -> Unit = {},
    onSelectMode: (String) -> Unit = {},
    onSendMessage: (JsonObject) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    var pickingSlot by remember { mutableStateOf<Int?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }

    fun triggerCompare(a: String = ref1, b: String = ref2, m: String = mode) {
        if (a.isEmpty() || b.isEmpty()) {
            localError = "Pick two refs to compare."
            return
        }
        if (a == b) {
            localError = "Pick two different refs."
            return
        }
        localError = null
        onSendMessage(GitCompareWebviewMessages.requestCompare(a, b, m))
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .testTag("git-compare-root"),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Toolbar (`git-compare-toolbar`)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tokens.panel.color)
                    .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("git-compare-toolbar"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsButton(
                    label = ref1.ifEmpty { "Select ref 1…" },
                    modifier = Modifier.testTag("git-compare-ref1-btn"),
                    onClick = { pickingSlot = 1 },
                )
                BasicText("→", style = ppmText(12f).copy(color = tokens.text3.color))
                SettingsButton(
                    label = ref2.ifEmpty { "Select ref 2…" },
                    modifier = Modifier.testTag("git-compare-ref2-btn"),
                    onClick = { pickingSlot = 2 },
                )
                SettingsButton(
                    label = "⇄",
                    modifier = Modifier.testTag("git-compare-swap-btn"),
                    onClick = {
                        val old1 = ref1
                        val old2 = ref2
                        onSelectRef1(old2)
                        onSelectRef2(old1)
                        if (old1.isNotEmpty() && old2.isNotEmpty() && old1 != old2) {
                            triggerCompare(old2, old1, mode)
                        }
                    },
                )
                SettingsButton(
                    label = "Compare",
                    primary = true,
                    modifier = Modifier.testTag("git-compare-run-btn"),
                    onClick = { triggerCompare() },
                )
                Spacer(Modifier.weight(1f))
                SettingsButton(
                    label = if (mode == "three-dot") "Since divergence (…)" else "Direct diff (..)",
                    modifier = Modifier.testTag("git-compare-mode-btn"),
                    onClick = {
                        val next = if (mode == "three-dot") "two-dot" else "three-dot"
                        onSelectMode(next)
                    },
                )
                if (counts != null) {
                    BasicText(
                        "${counts.behind} behind · ${counts.ahead} ahead",
                        style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text3.color),
                        modifier = Modifier.testTag("git-compare-counts"),
                    )
                }
            }

            val err = localError ?: errorMessage
            if (!err.isNullOrEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.error.opacity(0.14).color)
                        .cssBorder(tokens.error.color, sides = CssSides.Bottom)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("git-compare-error-banner"),
                ) {
                    BasicText(err, style = ppmText(11f).copy(color = tokens.error.color))
                }
            }

            @Composable
            fun CommitsPane(paneModifier: Modifier) {
                Column(modifier = paneModifier) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(tokens.panel.color)
                            .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        BasicText(
                            "Commits (${commits.size})",
                            style = ppmText(11f, FontWeight.SemiBold).copy(color = tokens.text.color),
                        )
                    }
                    if (commits.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                            BasicText(
                                if (loading) "Comparing…" else "No commits in this range.",
                                style = ppmText(11f).copy(color = tokens.text3.color),
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("git-compare-commits-list"),
                        ) {
                            items(commits, key = { it.hash }) { c ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                        .testTag("git-compare-commit-${c.hash}"),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    BasicText(
                                        c.subject,
                                        style = ppmText(12f).copy(color = tokens.text.color),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        GitInsightAvatar(c.author, c.authorEmail, sizeDp = 14)
                                        BasicText(c.author, style = ppmText(10f).copy(color = tokens.text3.color))
                                        BasicText(
                                            formatGitGraphDate(c.authorDate, "relative"),
                                            style = ppmText(10f).copy(color = tokens.text3.color),
                                        )
                                        BasicText(
                                            c.hash.take(7),
                                            style = ppmText(10f, kind = FontKind.MONO).copy(color = tokens.text3.color),
                                        )
                                    }
                                }
                            }
                            if (truncated) {
                                item {
                                    BasicText(
                                        "Showing the first ${commits.size} commits.",
                                        style = ppmText(10f).copy(color = tokens.text3.color),
                                        modifier = Modifier.padding(10.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            @Composable
            fun FilesPane(paneModifier: Modifier) {
                Column(modifier = paneModifier) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(tokens.panel.color)
                            .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        BasicText(
                            "Changed files ${if (files.isNotEmpty()) "(${files.size})" else ""}",
                            style = ppmText(11f, FontWeight.SemiBold).copy(color = tokens.text.color),
                        )
                    }
                    if (files.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                            BasicText(
                                if (loading) "Loading files…" else "These refs have identical trees.",
                                style = ppmText(11f).copy(color = tokens.text3.color),
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("git-compare-files-list"),
                        ) {
                            items(files, key = { it.path }) { f ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                                        .clickable {
                                            onSendMessage(
                                                GitCompareWebviewMessages.openDiff(
                                                    filePath = f.path,
                                                    ref1 = ref1,
                                                    ref2 = ref2,
                                                    mode = mode,
                                                ),
                                            )
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                        .testTag("git-compare-file-${f.path}"),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    BasicText(
                                        f.path,
                                        style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text.color),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (!f.oldPath.isNullOrEmpty()) {
                                        BasicText(
                                            "renamed",
                                            style = ppmText(10f, kind = FontKind.MONO).copy(color = tokens.accent.color),
                                        )
                                    }
                                    if (f.binary) {
                                        BasicText(
                                            "binary",
                                            style = ppmText(10f, kind = FontKind.MONO).copy(
                                                color = tokens.text3.color,
                                                fontStyle = FontStyle.Italic,
                                            ),
                                        )
                                    } else {
                                        BasicText(
                                            "+${f.additions}",
                                            style = ppmText(10f, kind = FontKind.MONO).copy(color = tokens.success.color),
                                        )
                                        BasicText(
                                            "-${f.deletions}",
                                            style = ppmText(10f, kind = FontKind.MONO).copy(color = tokens.error.color),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (mobile) {
                Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    CommitsPane(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .cssBorder(tokens.border.color, sides = CssSides.Bottom),
                    )
                    FilesPane(Modifier.weight(1f).fillMaxWidth())
                }
            } else {
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    CommitsPane(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .cssBorder(tokens.border.color, sides = CssSides.End),
                    )
                    FilesPane(Modifier.weight(1f).fillMaxHeight())
                }
            }
        }

        val slot = pickingSlot
        if (slot != null) {
            GitInsightModalOverlay(
                testTag = "git-compare-ref$slot-menu",
                onDismiss = { pickingSlot = null },
            ) {
                BasicText(
                    "Choose Ref $slot",
                    style = ppmText(13f, FontWeight.SemiBold).copy(color = tokens.text.color),
                )
                for ((label, kind) in listOf("Branches" to "head", "Remotes" to "remote", "Tags" to "tag")) {
                    val group = refs.filter { it.kind == kind }
                    if (group.isNotEmpty()) {
                        BasicText(
                            label,
                            style = ppmText(11f, FontWeight.SemiBold).copy(color = tokens.text3.color),
                        )
                        for (r in group) {
                            SettingsButton(
                                label = r.name,
                                modifier = Modifier.fillMaxWidth().testTag("git-compare-ref-opt-${r.name}"),
                                onClick = {
                                    pickingSlot = null
                                    if (slot == 1) onSelectRef1(r.name) else onSelectRef2(r.name)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 8d.4 Interactive Rebase Panel (`git-graph.interactiveRebase`)
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun GitInteractiveRebaseNativePanelBody(
    model: AppModel,
    panel: WebviewPanelUI,
    modifier: Modifier = Modifier,
) {
    val ext = model.extensions
    var inProgress by remember(panel.id) { mutableStateOf<String?>(null) }
    var progressText by remember(panel.id) { mutableStateOf<String?>(null) }
    var branch by remember(panel.id) { mutableStateOf<String?>(null) }
    var upstream by remember(panel.id) { mutableStateOf<String?>(null) }
    var base by remember(panel.id) { mutableStateOf<String?>(null) }
    var plan by remember(panel.id) { mutableStateOf<List<GitRebasePlanEntry>>(emptyList()) }
    var running by remember(panel.id) { mutableStateOf(false) }
    var outputText by remember(panel.id) { mutableStateOf<String?>(null) }
    var outputOk by remember(panel.id) { mutableStateOf(true) }
    var errorMessage by remember(panel.id) { mutableStateOf<String?>(null) }

    fun sendToExt(msg: JsonObject) {
        ext?.sendWebviewMessage(panel.id, msg)
    }

    LaunchedEffect(panel.id, ext) {
        if (ext == null) return@LaunchedEffect
        sendToExt(GitRebaseWebviewMessages.ready())
        ext.webviewMessages.collect { (msgPanelId, rawMsg) ->
            if (msgPanelId != panel.id) return@collect
            when (val msg = parseGitRebaseExtMessage(rawMsg)) {
                is GitRebaseExtMessage.Running -> {
                    running = true
                    errorMessage = null
                    outputText = null
                }
                is GitRebaseExtMessage.LoadState -> {
                    running = false
                    inProgress = msg.inProgress
                    progressText = msg.progress
                    branch = msg.branch
                    upstream = msg.upstream
                    base = msg.base
                    plan = msg.commits
                }
                is GitRebaseExtMessage.RebaseResult -> {
                    running = false
                    outputOk = msg.ok
                    outputText = msg.output.ifEmpty { if (msg.ok) "Rebase finished." else "Rebase failed." }
                    model.refreshGitStatus()
                }
                is GitRebaseExtMessage.ErrorMessage -> {
                    running = false
                    errorMessage = msg.message
                }
                null -> Unit
            }
        }
    }

    GitInteractiveRebaseNativeContent(
        inProgress = inProgress,
        progressText = progressText,
        branch = branch,
        upstream = upstream,
        base = base,
        plan = plan,
        running = running,
        outputText = outputText,
        outputOk = outputOk,
        errorMessage = errorMessage,
        onUpdatePlan = { plan = it },
        onSendMessage = { sendToExt(it) },
        modifier = modifier,
    )
}

@Composable
internal fun GitInteractiveRebaseNativeContent(
    inProgress: String? = null,
    progressText: String? = null,
    branch: String? = null,
    upstream: String? = null,
    base: String? = null,
    plan: List<GitRebasePlanEntry> = emptyList(),
    running: Boolean = false,
    outputText: String? = null,
    outputOk: Boolean = true,
    errorMessage: String? = null,
    onUpdatePlan: (List<GitRebasePlanEntry>) -> Unit = {},
    onSendMessage: (JsonObject) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    var countInput by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .testTag("git-rebase-root"),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Toolbar (`git-rebase-toolbar`)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tokens.panel.color)
                    .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("git-rebase-toolbar"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    branch?.ifEmpty { "(detached HEAD)" } ?: "(detached HEAD)",
                    style = ppmText(12f, FontWeight.SemiBold, FontKind.MONO).copy(color = tokens.text.color),
                    modifier = Modifier.testTag("git-rebase-branch-label"),
                )
                if (!base.isNullOrEmpty()) {
                    BasicText(
                        "onto ${base.take(7)}${if (!upstream.isNullOrEmpty()) " · upstream $upstream" else ""}",
                        style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text3.color),
                        modifier = Modifier.testTag("git-rebase-base-label"),
                    )
                }
                Spacer(Modifier.weight(1f))
                BasicText("Last", style = ppmText(11f).copy(color = tokens.text3.color))
                SettingsTextInput(
                    value = countInput,
                    onValueChange = { countInput = it },
                    placeholder = "n",
                    modifier = Modifier.width(54.dp).testTag("git-rebase-count-input"),
                )
                SettingsButton(
                    label = "Load",
                    modifier = Modifier.testTag("git-rebase-load-count-btn"),
                    onClick = {
                        val n = countInput.trim().toIntOrNull()
                        if (n == null || n < 1) {
                            localError = "Enter how many recent commits to rebase."
                        } else {
                            localError = null
                            onSendMessage(GitRebaseWebviewMessages.requestPlan(count = n))
                        }
                    },
                )
                SettingsButton(
                    label = "Reset plan",
                    modifier = Modifier.testTag("git-rebase-reset-btn"),
                    onClick = {
                        localError = null
                        onUpdatePlan(plan.map { it.copy(action = "pick", message = null) })
                        onSendMessage(GitRebaseWebviewMessages.requestPlan(base = base))
                    },
                )
                SettingsButton(
                    label = "Start rebase",
                    primary = true,
                    modifier = Modifier.testTag("git-rebase-run-btn"),
                    onClick = {
                        val validationErr = validateRebasePlanNewestFirst(base, plan)
                        if (validationErr != null) {
                            localError = validationErr
                        } else {
                            localError = null
                            onSendMessage(GitRebaseWebviewMessages.runRebase(base = base!!, entries = plan))
                        }
                    },
                )
            }

            // In-Progress Rebase Banner (`git-rebase-progress-banner`)
            if (!inProgress.isNullOrEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.warning.opacity(0.14).color)
                        .cssBorder(tokens.warning.color, sides = CssSides.Bottom)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("git-rebase-progress-banner"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val bannerMsg = if (inProgress == "rebase") {
                        "Rebase in progress${if (!progressText.isNullOrEmpty()) " ($progressText)" else ""} — resolve the conflicts, then Continue."
                    } else {
                        "A $inProgress is in progress — finish or abort it before rebasing."
                    }
                    BasicText(
                        bannerMsg,
                        style = ppmText(11f, FontWeight.Medium).copy(color = tokens.text.color),
                        modifier = Modifier.weight(1f),
                    )
                    SettingsButton(
                        label = "Continue",
                        primary = true,
                        modifier = Modifier.testTag("git-rebase-continue-btn"),
                        onClick = { onSendMessage(GitRebaseWebviewMessages.rebaseAction("continue")) },
                    )
                    SettingsButton(
                        label = "Skip",
                        modifier = Modifier.testTag("git-rebase-skip-btn"),
                        onClick = { onSendMessage(GitRebaseWebviewMessages.rebaseAction("skip")) },
                    )
                    SettingsButton(
                        label = "Abort",
                        modifier = Modifier.testTag("git-rebase-abort-btn"),
                        onClick = { onSendMessage(GitRebaseWebviewMessages.rebaseAction("abort")) },
                    )
                }
            }

            val err = localError ?: errorMessage
            if (!err.isNullOrEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.error.opacity(0.14).color)
                        .cssBorder(tokens.error.color, sides = CssSides.Bottom)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("git-rebase-error-banner"),
                ) {
                    BasicText(err, style = ppmText(11f).copy(color = tokens.error.color))
                }
            }

            // Plan List
            if (running) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BasicText("Running interactive rebase…", style = ppmText(12f).copy(color = tokens.text3.color))
                }
            } else if (!inProgress.isNullOrEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BasicText(
                        "A rebase is already in progress. Resolve the conflict in the editor, then Continue — or Abort to go back.",
                        style = ppmText(12f).copy(color = tokens.text3.color),
                    )
                }
            } else if (plan.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BasicText(
                        if (!base.isNullOrEmpty()) {
                            "This branch has no commits above its base."
                        } else {
                            "No upstream branch to compare against. Enter how many recent commits to rebase."
                        },
                        style = ppmText(12f).copy(color = tokens.text3.color),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("git-rebase-list"),
                ) {
                    itemsIndexed(plan, key = { _, item -> item.hash }) { idx, item ->
                        val isDrop = item.action == "drop"
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                                .alpha(if (isDrop) 0.55f else 1f)
                                .testTag("git-rebase-row-$idx"),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                BasicText("⠿", style = ppmText(14f).copy(color = tokens.text3.color))
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    SettingsButton(
                                        label = "▲",
                                        modifier = Modifier.testTag("git-rebase-up-$idx"),
                                        onClick = {
                                            if (idx > 0) {
                                                onUpdatePlan(moveRebasePlanItem(plan, idx, idx - 1))
                                            }
                                        },
                                    )
                                    SettingsButton(
                                        label = "▼",
                                        modifier = Modifier.testTag("git-rebase-down-$idx"),
                                        onClick = {
                                            if (idx < plan.lastIndex) {
                                                onUpdatePlan(moveRebasePlanItem(plan, idx, idx + 1))
                                            }
                                        },
                                    )
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    BasicText(
                                        item.subject,
                                        style = ppmText(12f, FontWeight.Medium).copy(
                                            color = tokens.text.color,
                                            textDecoration = if (isDrop) TextDecoration.LineThrough else TextDecoration.None,
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        GitInsightAvatar(item.author, item.authorEmail, sizeDp = 14)
                                        BasicText(item.author, style = ppmText(10f).copy(color = tokens.text3.color))
                                        BasicText(
                                            formatGitGraphDate(item.authorDate, "relative"),
                                            style = ppmText(10f).copy(color = tokens.text3.color),
                                        )
                                        BasicText(
                                            item.hash.take(7),
                                            style = ppmText(10f, kind = FontKind.MONO).copy(color = tokens.text3.color),
                                        )
                                    }
                                }
                                // Action pills (`pick`, `reword`, `edit`, `squash`, `fixup`, `drop`)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    for (act in GIT_REBASE_ACTIONS) {
                                        SettingsButton(
                                            label = act,
                                            primary = item.action == act,
                                            modifier = Modifier.testTag("git-rebase-action-$idx-$act"),
                                            onClick = {
                                                val updated = plan.toMutableList()
                                                val seededMsg = if (act == "reword" && item.message == null) item.subject else item.message
                                                updated[idx] = item.copy(action = act, message = seededMsg)
                                                onUpdatePlan(updated)
                                            },
                                        )
                                    }
                                }
                            }
                            if (item.action == "reword") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(tokens.panel.color)
                                        .padding(start = 34.dp, end = 10.dp, top = 6.dp, bottom = 8.dp),
                                ) {
                                    SettingsTextInput(
                                        value = item.message ?: item.subject,
                                        onValueChange = { newMsg ->
                                            val updated = plan.toMutableList()
                                            updated[idx] = item.copy(message = newMsg)
                                            onUpdatePlan(updated)
                                        },
                                        placeholder = "New commit message",
                                        modifier = Modifier.fillMaxWidth().testTag("git-rebase-reword-input-$idx"),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Output Footer
            if (!outputText.isNullOrEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.panel.color)
                        .cssBorder(tokens.border.color, sides = CssSides.Top)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .testTag("git-rebase-output"),
                ) {
                    BasicText(
                        outputText,
                        style = ppmText(10f, kind = FontKind.MONO).copy(
                            color = if (outputOk) tokens.success.color else tokens.error.color,
                        ),
                    )
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 8d.5 Reflog Panel (`git-graph.reflog`)
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun GitReflogNativePanelBody(
    model: AppModel,
    tab: WorkspaceTab,
    panel: WebviewPanelUI,
    modifier: Modifier = Modifier,
) {
    val ext = model.extensions
    val projectName = tab.metaString("projectName") ?: tab.projectId ?: model.selectedProject
    val cachedChanges = projectName?.let { model.cachedGitChanges(it) }
    val hasDirtyWorkingTree = remember(projectName, cachedChanges) {
        cachedChanges?.files?.isNotEmpty() == true
    }

    var entries by remember(panel.id) { mutableStateOf<List<GitReflogEntry>>(emptyList()) }
    var branch by remember(panel.id) { mutableStateOf<String?>(null) }
    var truncated by remember(panel.id) { mutableStateOf(false) }
    var outputText by remember(panel.id) { mutableStateOf<String?>(null) }
    var errorMessage by remember(panel.id) { mutableStateOf<String?>(null) }

    fun sendToExt(msg: JsonObject) {
        ext?.sendWebviewMessage(panel.id, msg)
    }

    LaunchedEffect(panel.id, ext) {
        if (ext == null) return@LaunchedEffect
        sendToExt(GitReflogWebviewMessages.ready())
        ext.webviewMessages.collect { (msgPanelId, rawMsg) ->
            if (msgPanelId != panel.id) return@collect
            when (val msg = parseGitReflogExtMessage(rawMsg)) {
                is GitReflogExtMessage.LoadEntries -> {
                    errorMessage = null
                    entries = msg.entries
                    branch = msg.branch
                    truncated = msg.truncated
                }
                is GitReflogExtMessage.ActionResult -> {
                    errorMessage = null
                    outputText = msg.message + (if (msg.output.isNotEmpty()) "\n${msg.output}" else "")
                    model.refreshGitStatus(projectName)
                }
                is GitReflogExtMessage.ErrorMessage -> {
                    errorMessage = msg.message
                }
                null -> Unit
            }
        }
    }

    GitReflogNativeContent(
        entries = entries,
        branch = branch,
        truncated = truncated,
        hasDirtyWorkingTree = hasDirtyWorkingTree,
        outputText = outputText,
        errorMessage = errorMessage,
        onSendMessage = { sendToExt(it) },
        modifier = modifier,
    )
}

@Composable
internal fun GitReflogNativeContent(
    entries: List<GitReflogEntry>,
    branch: String? = null,
    truncated: Boolean = false,
    hasDirtyWorkingTree: Boolean = false,
    outputText: String? = null,
    errorMessage: String? = null,
    onSendMessage: (JsonObject) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    var filterText by remember { mutableStateOf("") }
    var selectedKind by remember { mutableStateOf("") }
    var kindMenuOpen by remember { mutableStateOf(false) }
    var pendingSelector by remember { mutableStateOf<String?>(null) }
    var pendingActionKind by remember { mutableStateOf<String?>(null) } // "branch" | "reset"
    var pendingHash by remember { mutableStateOf("") }
    var confirmInput by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    val visibleEntries = remember(entries, filterText, selectedKind) {
        filterReflogEntries(entries, filterText, selectedKind)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .testTag("git-reflog-root"),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Toolbar (`git-reflog-toolbar`)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tokens.panel.color)
                    .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("git-reflog-toolbar"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    "Reflog",
                    style = ppmText(12f, FontWeight.SemiBold).copy(color = tokens.text.color),
                )
                if (!branch.isNullOrEmpty()) {
                    BasicText(
                        "HEAD → $branch",
                        style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text3.color),
                        modifier = Modifier.testTag("git-reflog-branch-label"),
                    )
                }
                Spacer(Modifier.weight(1f))
                SettingsTextInput(
                    value = filterText,
                    onValueChange = { filterText = it },
                    placeholder = "Filter",
                    modifier = Modifier.width(140.dp).testTag("git-reflog-filter-input"),
                )
                SettingsButton(
                    label = if (selectedKind.isEmpty()) "All actions ▾" else "$selectedKind ▾",
                    modifier = Modifier.testTag("git-reflog-kind-btn"),
                    onClick = { kindMenuOpen = true },
                )
                SettingsButton(
                    label = "Refresh",
                    modifier = Modifier.testTag("git-reflog-refresh-btn"),
                    onClick = {
                        localError = null
                        onSendMessage(GitReflogWebviewMessages.refresh())
                    },
                )
            }

            val err = localError ?: errorMessage
            if (!err.isNullOrEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.error.opacity(0.14).color)
                        .cssBorder(tokens.error.color, sides = CssSides.Bottom)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("git-reflog-error-banner"),
                ) {
                    BasicText(err, style = ppmText(11f).copy(color = tokens.error.color))
                }
            }

            if (visibleEntries.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BasicText(
                        if (entries.isNotEmpty()) {
                            "Nothing in the reflog matches this filter."
                        } else {
                            "This repository has no reflog yet."
                        },
                        style = ppmText(12f).copy(color = tokens.text3.color),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("git-reflog-list"),
                ) {
                    items(visibleEntries, key = { it.selector }) { e ->
                        val shortHash = e.hash.take(7)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                                .testTag("git-reflog-row-${e.selector}"),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                BasicText(
                                    e.selector,
                                    style = ppmText(10f, kind = FontKind.MONO).copy(color = tokens.text3.color),
                                    modifier = Modifier.width(68.dp),
                                )
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    BasicText(
                                        e.action,
                                        style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text.color),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (e.subject.isNotEmpty() && e.subject != e.action) {
                                        BasicText(
                                            e.subject,
                                            style = ppmText(11f).copy(color = tokens.text3.color),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        GitInsightAvatar(e.author, e.authorEmail, sizeDp = 14)
                                        BasicText(e.author, style = ppmText(10f).copy(color = tokens.text3.color))
                                        BasicText(
                                            formatGitGraphDate(e.authorDate, "relative"),
                                            style = ppmText(10f).copy(color = tokens.text3.color),
                                        )
                                        BasicText(
                                            shortHash,
                                            style = ppmText(10f, kind = FontKind.MONO).copy(color = tokens.text3.color),
                                        )
                                        BasicText(
                                            e.kind.ifEmpty { "other" },
                                            style = ppmText(9f, FontWeight.SemiBold, FontKind.MONO).copy(color = tokens.accent.color),
                                        )
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    SettingsButton(
                                        label = "Branch here",
                                        modifier = Modifier.testTag("git-reflog-branch-btn-${e.selector}"),
                                        onClick = {
                                            localError = null
                                            pendingSelector = e.selector
                                            pendingActionKind = "branch"
                                            pendingHash = e.hash
                                            confirmInput = ""
                                        },
                                    )
                                    SettingsButton(
                                        label = "Checkout",
                                        modifier = Modifier.testTag("git-reflog-checkout-btn-${e.selector}"),
                                        onClick = {
                                            localError = null
                                            onSendMessage(GitReflogWebviewMessages.checkout(e.selector))
                                        },
                                    )
                                    SettingsButton(
                                        label = "Reset --hard",
                                        modifier = Modifier.testTag("git-reflog-reset-btn-${e.selector}"),
                                        onClick = {
                                            localError = null
                                            pendingSelector = e.selector
                                            pendingActionKind = "reset"
                                            pendingHash = e.hash
                                            confirmInput = ""
                                        },
                                    )
                                }
                            }

                            // Inline Confirmation Row (`rl-confirm` in `reflog-view.ts`)
                            if (pendingSelector == e.selector && pendingActionKind != null) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(tokens.panel.color)
                                        .padding(horizontal = 10.dp, vertical = 8.dp)
                                        .testTag("git-reflog-confirm"),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (pendingActionKind == "branch") {
                                        BasicText(
                                            "New branch at $shortHash",
                                            style = ppmText(11f).copy(color = tokens.text2.color),
                                        )
                                        SettingsTextInput(
                                            value = confirmInput,
                                            onValueChange = { confirmInput = it },
                                            placeholder = "branch-name",
                                            modifier = Modifier.weight(1f).testTag("git-reflog-confirm-input"),
                                        )
                                        SettingsButton(
                                            label = "Create",
                                            primary = true,
                                            modifier = Modifier.testTag("git-reflog-do-branch-btn"),
                                            onClick = {
                                                val name = confirmInput.trim()
                                                if (name.isEmpty()) {
                                                    localError = "Enter a name for the new branch."
                                                } else {
                                                    localError = null
                                                    pendingSelector = null
                                                    pendingActionKind = null
                                                    onSendMessage(GitReflogWebviewMessages.createBranch(e.selector, name))
                                                }
                                            },
                                        )
                                    } else {
                                        BasicText(
                                            "Moves ${branch ?: "HEAD"} to $shortHash and rewrites working tree. Type $shortHash to confirm:",
                                            style = ppmText(11f).copy(color = tokens.error.color),
                                            modifier = Modifier.weight(1f),
                                        )
                                        SettingsTextInput(
                                            value = confirmInput,
                                            onValueChange = { confirmInput = it },
                                            placeholder = shortHash,
                                            modifier = Modifier.width(110.dp).testTag("git-reflog-confirm-input"),
                                        )
                                        SettingsButton(
                                            label = "Reset --hard",
                                            primary = true,
                                            modifier = Modifier.testTag("git-reflog-do-reset-btn"),
                                            onClick = {
                                                val typed = confirmInput.trim()
                                                if (typed != shortHash) {
                                                    localError = "That did not match $shortHash — nothing was changed."
                                                } else if (hasDirtyWorkingTree) {
                                                    localError = "This would discard uncommitted changes, which nothing can recover. Commit or stash them first."
                                                } else {
                                                    localError = null
                                                    pendingSelector = null
                                                    pendingActionKind = null
                                                    onSendMessage(GitReflogWebviewMessages.resetHard(e.selector))
                                                }
                                            },
                                        )
                                    }
                                    SettingsButton(
                                        label = "Cancel",
                                        modifier = Modifier.testTag("git-reflog-cancel-btn"),
                                        onClick = {
                                            pendingSelector = null
                                            pendingActionKind = null
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (!outputText.isNullOrEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.panel.color)
                        .cssBorder(tokens.border.color, sides = CssSides.Top)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .testTag("git-reflog-output"),
                ) {
                    BasicText(
                        outputText,
                        style = ppmText(10f, kind = FontKind.MONO).copy(color = tokens.success.color),
                    )
                }
            }
        }

        if (kindMenuOpen) {
            GitInsightModalOverlay(
                testTag = "git-reflog-kind-menu",
                onDismiss = { kindMenuOpen = false },
            ) {
                BasicText(
                    "Filter by action",
                    style = ppmText(13f, FontWeight.SemiBold).copy(color = tokens.text.color),
                )
                for (k in GIT_REFLOG_ACTION_KINDS) {
                    val label = if (k.isEmpty()) "All actions" else k
                    SettingsButton(
                        label = label,
                        primary = selectedKind == k,
                        modifier = Modifier.fillMaxWidth().testTag("git-reflog-kind-opt-${if (k.isEmpty()) "all" else k}"),
                        onClick = {
                            selectedKind = k
                            kindMenuOpen = false
                        },
                    )
                }
            }
        }
    }
}
