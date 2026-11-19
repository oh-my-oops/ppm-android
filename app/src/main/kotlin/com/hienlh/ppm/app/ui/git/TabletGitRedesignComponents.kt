package com.hienlh.ppm.app.ui.git

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.client.ChangeSide
import com.hienlh.ppm.client.ChangedFile
import com.hienlh.ppm.design.FileIcon
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.LucideIcon
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.state.git.CheckState
import com.hienlh.ppm.state.git.FileChange
import com.hienlh.ppm.state.git.GitChangeGroup
import com.hienlh.ppm.state.git.GitChangeGroups
import com.hienlh.ppm.state.git.GitChangesView

internal val FileChange.raw: ChangedFile
    get() = source ?: ChangedFile(
        path = path,
        oldPath = oldPath,
        staged = if (isStaged) ChangeSide(added, removed, emptyList()) else null,
        unstaged = if (!isStaged) ChangeSide(added, removed, emptyList()) else null,
        conflict = conflict,
        x = if (isStaged) statusLetter else " ",
        y = if (!isStaged) statusLetter else " ",
        untracked = untracked,
    )

internal val FileChange.dirPath: String
    get() = directoryPath

internal fun ChangedFile.stagedBlockSummary(): String? {
    val stagedBlocks = staged?.blocks?.size ?: 0
    val unstagedBlocks = unstaged?.blocks?.size ?: 0
    val totalBlocks = stagedBlocks + unstagedBlocks
    if (totalBlocks <= 0) return null
    val partial = staged != null && (unstaged != null || untracked)
    return when {
        partial && stagedBlocks > 0 -> "$stagedBlocks of $totalBlocks blocks"
        stagedBlocks > 1 -> "$stagedBlocks blocks"
        else -> null
    }
}

internal fun ChangedFile.changesBlockSummary(): String? {
    val stagedBlocks = staged?.blocks?.size ?: 0
    val unstagedBlocks = unstaged?.blocks?.size ?: 0
    val totalBlocks = stagedBlocks + unstagedBlocks
    if (totalBlocks <= 0) return null
    val partial = staged != null && (unstaged != null || untracked)
    return when {
        partial && unstagedBlocks > 0 -> "$unstagedBlocks ${if (unstagedBlocks == 1) "block" else "blocks"} left"
        unstagedBlocks > 1 -> "$unstagedBlocks blocks"
        else -> null
    }
}

internal val FileChange.blockSummary: String?
    get() = if (group == GitChangeGroup.STAGED) raw.stagedBlockSummary() else raw.changesBlockSummary()

/**
 * Tablet 4 Section 1: Renders `Staged` and `Changes` groups using [GitChangeGroups],
 * supporting partially staged files appearing in both groups with per-side `+added / -removed`
 * counts and block summaries (`"2 of 3 blocks"` / `"1 block left"`), `52.dp` two-line rows,
 * `48.dp` tick hit targets, and `Discard all` on the `Changes` group header.
 */
@Composable
internal fun GitStagedAndChangesGroups(
    model: AppModel,
    groups: GitChangeGroups,
    normalFiles: List<ChangedFile> = (groups.staged.map { it.raw } + groups.changes.map { it.raw }).distinctBy { it.path },
    isMerge: Boolean = false,
    showCheckAll: Boolean = true,
    viewMode: String = "flat",
    busy: Boolean = false,
    focusedKey: String? = null,
    focusedRowKey: String? = focusedKey,
    onFocusRow: ((String) -> Unit)? = null,
    collapseChanges: Boolean = false,
    onToggleFile: (ChangedFile) -> Unit,
    onStageFile: (ChangedFile) -> Unit,
    onUnstageFile: (ChangedFile) -> Unit,
    onToggleBatch: (List<ChangedFile>) -> Unit,
    onStageAllChanges: (List<ChangedFile>) -> Unit = onToggleBatch,
    onUnstageAllStaged: (List<ChangedFile>) -> Unit = onToggleBatch,
    onOpen: (ChangedFile) -> Unit,
    onOpenDiff: (ChangedFile) -> Unit,
    onOpenFile: (ChangedFile) -> Unit,
    onDiscard: (List<ChangedFile>) -> Unit,
    onStageLines: (ChangedFile, String) -> Unit,
    onMarkResolved: (ChangedFile) -> Unit,
    onOpenBlame: ((ChangedFile) -> Unit)? = null,
    onOpenFileHistory: ((ChangedFile) -> Unit)? = null,
    onReviewAll: (() -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    val effectiveFocusedKey = focusedRowKey ?: focusedKey
    var stagedOpen by remember { mutableStateOf(true) }
    var changesOpen by remember(collapseChanges) { mutableStateOf(!collapseChanges) }

    if (viewMode == "tree") {
        ChangeGroupSection(
            model = model,
            label = if (isMerge) "Merged" else "Changes",
            files = normalFiles,
            errorTone = false,
            showCheckAll = showCheckAll,
            viewMode = viewMode,
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
            onReviewAll = onReviewAll,
        )
        return
    }

    // ── 1. STAGED GROUP ──────────────────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .testTag("git-group-Staged"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clickable { stagedOpen = !stagedOpen }
                .padding(start = 10.dp, end = if (showCheckAll) 0.dp else 10.dp)
                .testTag("git-group-header-Staged"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PpmIcon(
                if (stagedOpen) ProductIcon.ChevronDown else ProductIcon.ChevronRight,
                size = 12.dp,
                tint = tokens.text3.color,
            )
            BasicText(
                text = "STAGED",
                style = PpmFont.sans(
                    11,
                    FontWeight.SemiBold,
                    lineHeight = 14,
                    color = tokens.text3.color,
                ).copy(letterSpacing = 0.07.em),
            )
            CountChip(groups.staged.size)
            Spacer(Modifier.weight(1f))
            if (showCheckAll) {
                val stagedCheckState = if (groups.staged.isNotEmpty()) CheckState.ALL else CheckState.NONE
                TabletGroupCheckCell(
                    state = stagedCheckState,
                    disabled = busy || groups.staged.isEmpty(),
                    contentDesc = "Unstage everything",
                    tag = "git-group-check-all-Staged",
                    onToggle = {
                        if (groups.staged.isNotEmpty()) {
                            onUnstageAllStaged(groups.staged.map { it.raw })
                        }
                    },
                )
            }
        }

        if (stagedOpen) {
            if (groups.staged.isEmpty()) {
                BasicText(
                    text = "Stage files or blocks to include them in the next commit",
                    style = PpmFont.sans(11, lineHeight = 15, color = tokens.text3.color),
                    modifier = Modifier
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                        .testTag("git-staged-empty-hint"),
                )
            } else {
                for (fc in groups.staged) {
                    val rowKey = "staged:${fc.path}"
                    TabletFileChangeRow(
                        fc = fc,
                        isFocused = effectiveFocusedKey == rowKey,
                        busy = busy,
                        onToggle = {
                            onFocusRow?.invoke(rowKey)
                            onUnstageFile(fc.raw)
                        },
                        onStage = { onStageFile(fc.raw) },
                        onUnstage = { onUnstageFile(fc.raw) },
                        onOpen = {
                            onFocusRow?.invoke(rowKey)
                            onOpen(fc.raw)
                        },
                        onOpenDiff = { onOpenDiff(fc.raw) },
                        onOpenFile = { onOpenFile(fc.raw) },
                        onDiscard = { onDiscard(listOf(fc.raw)) },
                        onStageLines = { scope -> onStageLines(fc.raw, scope) },
                        onOpenBlame = onOpenBlame?.let { cb -> { cb(fc.raw) } },
                        onOpenFileHistory = onOpenFileHistory?.let { cb -> { cb(fc.raw) } },
                    )
                }
            }
        }
    }

    // ── 2. CHANGES GROUP ─────────────────────────────────────────────────────
    val changesLabel = if (isMerge) "Merged" else "Changes"
    val unstagedDiscardable = remember(groups.changes) {
        groups.changes.map { it.raw }.filter(GitChangesView::hasUnstaged)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .testTag("git-group-$changesLabel"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clickable { changesOpen = !changesOpen }
                .padding(start = 10.dp, end = if (showCheckAll) 0.dp else 10.dp)
                .testTag("git-group-header-$changesLabel"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PpmIcon(
                if (changesOpen) ProductIcon.ChevronDown else ProductIcon.ChevronRight,
                size = 12.dp,
                tint = tokens.text3.color,
            )
            BasicText(
                text = changesLabel.uppercase(),
                style = PpmFont.sans(
                    11,
                    FontWeight.SemiBold,
                    lineHeight = 14,
                    color = tokens.text3.color,
                ).copy(letterSpacing = 0.07.em),
            )
            CountChip(groups.changes.size)
            Spacer(Modifier.weight(1f))
            if (unstagedDiscardable.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .alpha(if (busy) 0.4f else 1f)
                        .semantics { contentDescription = "Discard all unstaged changes" }
                        .clickable(enabled = !busy) { onDiscard(unstagedDiscardable) }
                        .testTag("git-group-discard-all-$changesLabel"),
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(ProductIcon.RotateCcw, size = 14.dp, tint = tokens.text2.color)
                }
            }
            if (onReviewAll != null) {
                Row(
                    modifier = Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .semantics { contentDescription = "Review block by block" }
                        .clickable(onClick = onReviewAll)
                        .padding(horizontal = 8.dp)
                        .testTag("git-group-review-$changesLabel"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PpmIcon(LucideIcon.FileDiff, size = 14.dp, tint = tokens.text2.color)
                    BasicText("Review", style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.text2.color))
                }
            }
            if (showCheckAll) {
                val changesCheckState = when {
                    groups.changes.isEmpty() && groups.staged.isNotEmpty() -> CheckState.ALL
                    groups.changes.any { it.partiallyStaged } -> CheckState.SOME
                    else -> CheckState.NONE
                }
                TabletGroupCheckCell(
                    state = changesCheckState,
                    disabled = busy || (groups.changes.isEmpty() && groups.staged.isEmpty()),
                    contentDesc = if (groups.changes.isEmpty()) "Unstage everything" else "Stage everything",
                    tag = "git-group-check-all-$changesLabel",
                    onToggle = {
                        if (groups.changes.isNotEmpty()) {
                            onStageAllChanges(groups.changes.map { it.raw })
                        } else if (groups.staged.isNotEmpty()) {
                            onUnstageAllStaged(groups.staged.map { it.raw })
                        }
                    },
                )
            }
        }

        if (changesOpen) {
            for (fc in groups.changes) {
                val rowKey = "changes:${fc.path}"
                TabletFileChangeRow(
                    fc = fc,
                    isFocused = effectiveFocusedKey == rowKey,
                    busy = busy,
                    onToggle = {
                        onFocusRow?.invoke(rowKey)
                        onStageFile(fc.raw)
                    },
                    onStage = { onStageFile(fc.raw) },
                    onUnstage = { onUnstageFile(fc.raw) },
                    onOpen = {
                        onFocusRow?.invoke(rowKey)
                        onOpen(fc.raw)
                    },
                    onOpenDiff = { onOpenDiff(fc.raw) },
                    onOpenFile = { onOpenFile(fc.raw) },
                    onDiscard = { onDiscard(listOf(fc.raw)) },
                    onStageLines = { scope -> onStageLines(fc.raw, scope) },
                    onOpenBlame = onOpenBlame?.let { cb -> { cb(fc.raw) } },
                    onOpenFileHistory = onOpenFileHistory?.let { cb -> { cb(fc.raw) } },
                )
            }
        }
    }
}

@Composable
private fun TabletGroupCheckCell(
    state: CheckState,
    disabled: Boolean,
    contentDesc: String,
    tag: String,
    onToggle: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Box(
        modifier = Modifier
            .size(48.dp)
            .testTag(tag)
            .semantics { contentDescription = contentDesc }
            .alpha(if (disabled) 0.4f else 1f)
            .clickable(enabled = !disabled, role = Role.Checkbox, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        val checked = state != CheckState.NONE
        val shape = RoundedCornerShape(5.dp)
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(shape)
                .background(if (checked) tokens.accent.color else Color.Transparent)
                .cssBorder(if (checked) tokens.accent.color else tokens.text3.opacity(0.6).color, shape),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                CheckState.ALL -> PpmIcon(ProductIcon.Check, size = 11.dp, tint = tokens.accentFg.color)
                CheckState.SOME -> PpmIcon(ProductIcon.Minus, size = 11.dp, tint = tokens.accentFg.color)
                CheckState.NONE -> Unit
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabletFileChangeRow(
    fc: FileChange,
    isFocused: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onStage: () -> Unit,
    onUnstage: () -> Unit,
    onOpen: () -> Unit,
    onOpenDiff: () -> Unit,
    onOpenFile: () -> Unit,
    onDiscard: () -> Unit,
    onStageLines: (String) -> Unit,
    onOpenBlame: (() -> Unit)? = null,
    onOpenFileHistory: (() -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val isStagedGroup = fc.group == GitChangeGroup.STAGED
    val letter = fc.statusLetter
    val discardable = remember(fc.raw) { GitChangesView.hasUnstaged(fc.raw) }
    var menuOpen by remember { mutableStateOf(false) }

    val rowHover = remember { MutableInteractionSource() }
    val rowHovered by rowHover.collectIsHoveredAsState()

    val outerTag = if (isStagedGroup) "git-staged-row-${fc.path}" else "git-changes-row-${fc.path}"
    val includeLegacyRowTag = !isStagedGroup || !fc.partiallyStaged

    @Composable
    fun MenuItems() {
        RowMenuItem("Review changes") { menuOpen = false; onOpen() }
        RowMenuItem("Open diff") { menuOpen = false; onOpenDiff() }
        RowMenuItem("Open file") { menuOpen = false; onOpenFile() }
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(tokens.border.color))
        if (!isStagedGroup) {
            RowMenuItem(
                if (fc.partiallyStaged) "Stage remaining blocks" else "Stage file",
                enabled = !busy,
            ) {
                menuOpen = false
                onStage()
            }
            if (fc.raw.unstaged != null) {
                RowMenuItem("Stage / unstage lines…", enabled = !busy) {
                    menuOpen = false
                    onStageLines("worktree")
                }
            }
        } else {
            RowMenuItem(
                if (fc.partiallyStaged) "Unstage staged blocks" else "Unstage file",
                enabled = !busy,
            ) {
                menuOpen = false
                onUnstage()
            }
            if (fc.raw.staged != null) {
                RowMenuItem("Unstage lines…", enabled = !busy) {
                    menuOpen = false
                    onStageLines("index")
                }
            }
        }
        if (onOpenFileHistory != null) {
            RowMenuItem("File history") {
                menuOpen = false
                onOpenFileHistory()
            }
        }
        if (onOpenBlame != null) {
            RowMenuItem("Blame") {
                menuOpen = false
                onOpenBlame()
            }
        }
        if (discardable && !isStagedGroup) {
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(tokens.border.color))
            RowMenuItem("Discard changes…", enabled = !busy, destructive = true) {
                menuOpen = false
                onDiscard()
            }
        }
    }

    val bgColor = when {
        isFocused -> tokens.accent.opacity(0.14).color
        !mobile && rowHovered -> tokens.panel2.color
        else -> Color.Transparent
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(rowHover)
            .background(bgColor)
            .testTag(outerTag),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .rightClick { menuOpen = true }
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = { menuOpen = true },
                )
                .padding(start = 4.dp, end = 6.dp)
                .then(if (includeLegacyRowTag) Modifier.testTag("git-change-row-${fc.path}") else Modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 48dp tick hit target on the left (or right check compatibility)
            val checkState = when {
                isStagedGroup -> CheckState.ALL
                fc.partiallyStaged -> CheckState.SOME
                else -> CheckState.NONE
            }
            val primaryCheckTag = if (isStagedGroup && fc.partiallyStaged) {
                "git-staged-check-${fc.path}"
            } else {
                "git-change-check-${fc.path}"
            }
            Box(
                modifier = if (isStagedGroup && !fc.partiallyStaged) {
                    Modifier.testTag("git-staged-check-${fc.path}")
                } else {
                    Modifier
                },
            ) {
                TabletGroupCheckCell(
                    state = checkState,
                    disabled = busy,
                    contentDesc = if (isStagedGroup) "Unstage ${fc.fileName}" else "Stage ${fc.fileName}",
                    tag = primaryCheckTag,
                    onToggle = onToggle,
                )
            }

            FileIcon(
                name = fc.fileName,
                size = 16.dp,
            )

            // Two-line file name + counts/blocks/folder
            Column(
                modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StartTruncatedText(
                        text = fc.fileName,
                        style = PpmFont.sans(
                            13,
                            FontWeight.Medium,
                            lineHeight = 17,
                            color = if (letter == "D") tokens.text2.color else tokens.text.color,
                        ).copy(
                            textDecoration = if (letter == "D") TextDecoration.LineThrough else TextDecoration.None,
                        ),
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (fc.partiallyStaged) {
                        val badgeText = if (isStagedGroup) "part of the file" else "rest of the file"
                        val badgeTag = if (isStagedGroup) {
                            "git-partial-badge-staged-${fc.path}"
                        } else {
                            "git-partial-badge-changes-${fc.path}"
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(tokens.accent.opacity(0.14).color)
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                                .testTag(badgeTag),
                        ) {
                            BasicText(
                                text = badgeText,
                                style = PpmFont.sans(10, FontWeight.Medium, lineHeight = 13, color = tokens.accent.color),
                            )
                        }
                    }
                }

                // Second line: +added -removed · blockSummary · dirPath
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val countsTag = if (isStagedGroup) "git-staged-counts-${fc.path}" else "git-changes-counts-${fc.path}"
                    if (fc.added > 0 || fc.removed > 0) {
                        Row(
                            modifier = Modifier.testTag(countsTag),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (fc.added > 0) {
                                BasicText(
                                    text = "+${fc.added}",
                                    style = PpmFont.mono(11, lineHeight = 14, color = tokens.success.color),
                                )
                            }
                            if (fc.removed > 0) {
                                BasicText(
                                    text = "−${fc.removed}",
                                    style = PpmFont.mono(11, lineHeight = 14, color = tokens.error.color),
                                )
                            }
                        }
                    } else {
                        val note = remember(fc.raw) { GitChangesView.lineNote(fc.raw) }
                        if (note != null) {
                            BasicText(
                                text = note,
                                style = PpmFont.mono(10, lineHeight = 12, color = tokens.text3.color),
                            )
                        }
                    }

                    val blockSummary = fc.blockSummary
                    if (blockSummary != null) {
                        val blockTag = if (isStagedGroup) "git-staged-blocks-${fc.path}" else "git-changes-blocks-${fc.path}"
                        BasicText(
                            text = blockSummary,
                            style = PpmFont.mono(10, lineHeight = 13, color = tokens.accent.color),
                            modifier = Modifier.testTag(blockTag),
                        )
                    }

                    if (!isStagedGroup) {
                        BlockDots(fc.raw)
                    }

                    if (fc.dirPath.isNotEmpty()) {
                        StartTruncatedText(
                            text = fc.dirPath,
                            style = PpmFont.sans(11, lineHeight = 14, color = tokens.text3.color),
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
            }

            // Row action buttons (Open file, Discard)
            if (includeLegacyRowTag) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .semantics { contentDescription = "Open ${fc.fileName}" }
                            .clickable(onClick = onOpenFile)
                            .testTag("git-row-open-file-${fc.path}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        PpmIcon(ProductIcon.FileText, size = 14.dp, tint = tokens.text3.color)
                    }
                    val canDiscardRow = discardable && !busy && !isStagedGroup
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .alpha(if (canDiscardRow) 1f else 0.4f)
                            .semantics { contentDescription = "Discard changes to ${fc.fileName}" }
                            .clickable(enabled = canDiscardRow, onClick = onDiscard)
                            .testTag("git-row-discard-${fc.path}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        PpmIcon(ProductIcon.Trash2, size = 14.dp, tint = tokens.text3.color)
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .semantics { contentDescription = "Open ${fc.fileName}" }
                        .clickable(onClick = onOpenFile)
                        .testTag("git-staged-open-file-${fc.path}"),
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(ProductIcon.FileText, size = 14.dp, tint = tokens.text3.color)
                }
            }

            StatusTile(fc.raw)
        }

        if (menuOpen) {
            Popup(
                alignment = Alignment.BottomEnd,
                onDismissRequest = { menuOpen = false },
                properties = PopupProperties(focusable = true),
            ) {
                val popShape = RoundedCornerShape(8.dp)
                Column(
                    modifier = Modifier
                        .width(220.dp)
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

@Composable
private fun RowMenuItem(
    label: String,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 38.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = label,
            style = PpmFont.sans(12, lineHeight = 16, color = if (destructive) tokens.error.color else tokens.text.color),
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// Tablet 4 Section 5: Conflict Hunk Bar & Conflict Editor Stepper (`Notes 35–36`)
// ══════════════════════════════════════════════════════════════════════════════

enum class ConflictResolutionChoice {
    CURRENT,
    INCOMING,
    BOTH,
}

data class ConflictRegionItem(
    val index: Int,
    val currentLabel: String = "Current (HEAD)",
    val incomingLabel: String = "Incoming",
    val currentLines: List<String> = emptyList(),
    val incomingLines: List<String> = emptyList(),
    val resolvedChoice: ConflictResolutionChoice? = null,
)

/**
 * Tablet 4 Section 5 (`Notes 35–36`) and Section 10:
 * Conflict resolution view with `36.dp` tall `Accept Current`, `Accept Incoming`, and `Accept Both`
 * buttons (`8.dp` gap so a finger never taps the wrong side), plus the bottom conflict stepper bar
 * (`Previous conflict`, `Next conflict`, `"Conflict i of N · saved on every accept"`, and
 * `"Mark as resolved"` button) and keyboard shortcuts (`1`, `2`, `3`, `[`, `]`, `Ctrl+Enter`).
 */
@Composable
fun GitConflictResolutionView(
    filePath: String,
    conflicts: List<ConflictRegionItem>,
    onAccept: (conflictIndex: Int, choice: ConflictResolutionChoice) -> Unit,
    onMarkResolved: () -> Unit,
    onOpenShortcuts: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    var activeConflictIdx by remember(conflicts.size) {
        mutableIntStateOf(conflicts.indexOfFirst { it.resolvedChoice == null }.coerceAtLeast(0))
    }
    val remainingCount = conflicts.count { it.resolvedChoice == null }
    val allResolved = conflicts.isNotEmpty() && remainingCount == 0
    val focusRequester = remember { FocusRequester() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val currentItem = conflicts.getOrNull(activeConflictIdx)
                when {
                    ev.key == Key.One || ev.key == Key.NumPad1 -> {
                        if (currentItem != null) onAccept(currentItem.index, ConflictResolutionChoice.CURRENT)
                        true
                    }
                    ev.key == Key.Two || ev.key == Key.NumPad2 -> {
                        if (currentItem != null) onAccept(currentItem.index, ConflictResolutionChoice.INCOMING)
                        true
                    }
                    ev.key == Key.Three || ev.key == Key.NumPad3 -> {
                        if (currentItem != null) onAccept(currentItem.index, ConflictResolutionChoice.BOTH)
                        true
                    }
                    ev.key == Key.LeftBracket -> {
                        if (conflicts.isNotEmpty()) {
                            activeConflictIdx = (activeConflictIdx - 1 + conflicts.size) % conflicts.size
                        }
                        true
                    }
                    ev.key == Key.RightBracket -> {
                        if (conflicts.isNotEmpty()) {
                            activeConflictIdx = (activeConflictIdx + 1) % conflicts.size
                        }
                        true
                    }
                    ev.key == Key.Enter && (ev.isCtrlPressed || ev.isMetaPressed) -> {
                        if (allResolved) onMarkResolved()
                        true
                    }
                    else -> false
                }
            }
            .testTag("git-conflict-resolution-view"),
    ) {
        // Header bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(tokens.panel.color)
                .edge(Edge.Bottom, tokens.border.color)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText(
                text = filePath,
                style = PpmFont.mono(12, FontWeight.SemiBold, lineHeight = 16, color = tokens.text.color),
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (allResolved) tokens.success.opacity(0.14).color else tokens.error.opacity(0.14).color)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .testTag("git-conflict-remaining-badge"),
            ) {
                BasicText(
                    text = if (allResolved) {
                        "0 conflicts remaining"
                    } else {
                        "$remainingCount of ${conflicts.size} ${if (conflicts.size == 1) "conflict" else "conflicts"} remaining"
                    },
                    style = PpmFont.sans(
                        11,
                        FontWeight.SemiBold,
                        lineHeight = 14,
                        color = if (allResolved) tokens.success.color else tokens.error.color,
                    ),
                )
            }
            if (onOpenShortcuts != null) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onOpenShortcuts)
                        .testTag("git-conflict-shortcuts-btn"),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText("?", style = PpmFont.mono(12, FontWeight.Bold, color = tokens.text2.color))
                }
            }
        }

        // Conflict blocks list
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            conflicts.forEachIndexed { idx, item ->
                val isSelected = idx == activeConflictIdx
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .cssBorder(
                            if (isSelected) tokens.accent.color else tokens.border.color,
                            RoundedCornerShape(8.dp),
                        )
                        .clickable { activeConflictIdx = idx }
                        .testTag("git-conflict-card-${item.index}"),
                ) {
                    // Note 35: 36dp action buttons with 8dp gap
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(tokens.panel2.color)
                            .edge(Edge.Bottom, tokens.border.color)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("git-conflict-hunk-bar-${item.index}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BasicText(
                            text = "Conflict ${idx + 1} of ${conflicts.size}",
                            style = PpmFont.mono(11, FontWeight.SemiBold, lineHeight = 14, color = tokens.text2.color),
                        )
                        if (item.resolvedChoice != null) {
                            BasicText(
                                text = "· Accepted ${item.resolvedChoice.name.lowercase()}",
                                style = PpmFont.sans(11, FontWeight.Medium, lineHeight = 14, color = tokens.success.color),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        ConflictActionButton(
                            label = "Accept Current",
                            selected = item.resolvedChoice == ConflictResolutionChoice.CURRENT,
                            accentColor = tokens.success.color,
                            tag = "git-conflict-accept-current-${item.index}",
                            onClick = {
                                activeConflictIdx = idx
                                onAccept(item.index, ConflictResolutionChoice.CURRENT)
                            },
                        )
                        ConflictActionButton(
                            label = "Accept Incoming",
                            selected = item.resolvedChoice == ConflictResolutionChoice.INCOMING,
                            accentColor = tokens.accent.color,
                            tag = "git-conflict-accept-incoming-${item.index}",
                            onClick = {
                                activeConflictIdx = idx
                                onAccept(item.index, ConflictResolutionChoice.INCOMING)
                            },
                        )
                        ConflictActionButton(
                            label = "Accept Both",
                            selected = item.resolvedChoice == ConflictResolutionChoice.BOTH,
                            accentColor = tokens.text2.color,
                            tag = "git-conflict-accept-both-${item.index}",
                            onClick = {
                                activeConflictIdx = idx
                                onAccept(item.index, ConflictResolutionChoice.BOTH)
                            },
                        )
                    }

                    // Current (ours) lines
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(tokens.success.opacity(0.08).color)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        BasicText(
                            text = "<<<<<<< ${item.currentLabel}",
                            style = PpmFont.mono(10, FontWeight.SemiBold, lineHeight = 14, color = tokens.success.color),
                        )
                        for (line in item.currentLines) {
                            BasicText(
                                text = line,
                                style = PpmFont.mono(12, lineHeight = 18, color = tokens.text.color),
                            )
                        }
                    }

                    // Incoming (theirs) lines
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(tokens.accent.opacity(0.08).color)
                            .edge(Edge.Top, tokens.border.color)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        BasicText(
                            text = "======= ${item.incomingLabel}",
                            style = PpmFont.mono(10, FontWeight.SemiBold, lineHeight = 14, color = tokens.accent.color),
                        )
                        for (line in item.incomingLines) {
                            BasicText(
                                text = line,
                                style = PpmFont.mono(12, lineHeight = 18, color = tokens.text.color),
                            )
                        }
                        BasicText(
                            text = ">>>>>>>",
                            style = PpmFont.mono(10, FontWeight.SemiBold, lineHeight = 14, color = tokens.accent.color),
                        )
                    }
                }
            }
        }

        // Note 36: Bottom Conflict Stepper Bar (`48.dp`)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(tokens.panel.color)
                .edge(Edge.Top, tokens.border.color)
                .padding(horizontal = 12.dp)
                .testTag("git-conflict-stepper-bar"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .cssBorder(tokens.border.color, RoundedCornerShape(6.dp))
                    .clickable(enabled = conflicts.size > 1) {
                        if (conflicts.isNotEmpty()) {
                            activeConflictIdx = (activeConflictIdx - 1 + conflicts.size) % conflicts.size
                        }
                    }
                    .padding(horizontal = 10.dp)
                    .testTag("git-conflict-prev-btn"),
                contentAlignment = Alignment.Center,
            ) {
                BasicText("↑ Previous conflict", style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.text.color))
            }
            Box(
                modifier = Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .cssBorder(tokens.border.color, RoundedCornerShape(6.dp))
                    .clickable(enabled = conflicts.size > 1) {
                        if (conflicts.isNotEmpty()) {
                            activeConflictIdx = (activeConflictIdx + 1) % conflicts.size
                        }
                    }
                    .padding(horizontal = 10.dp)
                    .testTag("git-conflict-next-btn"),
                contentAlignment = Alignment.Center,
            ) {
                BasicText("↓ Next conflict", style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.text.color))
            }
            BasicText(
                text = if (allResolved) {
                    "All ${conflicts.size} conflicts resolved · file saved"
                } else {
                    "Conflict ${activeConflictIdx + 1} of ${conflicts.size} · saved on every accept"
                },
                style = PpmFont.mono(11, lineHeight = 14, color = if (allResolved) tokens.success.color else tokens.text3.color),
                modifier = Modifier.weight(1f).testTag("git-conflict-status-text"),
            )
            Box(
                modifier = Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (allResolved) tokens.success.color else tokens.panel2.color)
                    .alpha(if (allResolved) 1f else 0.5f)
                    .clickable(enabled = allResolved, onClick = onMarkResolved)
                    .padding(horizontal = 12.dp)
                    .testTag("git-conflict-mark-resolved-btn"),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = "✓ Mark as resolved",
                    style = PpmFont.sans(
                        12,
                        FontWeight.SemiBold,
                        lineHeight = 16,
                        color = if (allResolved) Color.White else tokens.text3.color,
                    ),
                )
            }
        }
    }
}

@Composable
private fun ConflictActionButton(
    label: String,
    selected: Boolean,
    accentColor: Color,
    tag: String,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(shape)
            .background(if (selected) accentColor.copy(alpha = 0.22f) else accentColor.copy(alpha = 0.12f))
            .cssBorder(if (selected) accentColor else accentColor.copy(alpha = 0.35f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = if (selected) accentColor else tokens.text.color),
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// Tablet 4 Section 10: Keyboard Shortcuts & Touch Twins Sheet (`SH1`)
// ══════════════════════════════════════════════════════════════════════════════

data class GitShortcutRowSpec(
    val keys: String,
    val action: String,
    val touchTwin: String,
)

internal val TABLET_GIT_SHORTCUT_GROUPS: List<Pair<String, List<GitShortcutRowSpec>>> = listOf(
    "Source Control" to listOf(
        GitShortcutRowSpec("↑ / ↓", "Move through files", "Tap a file row"),
        GitShortcutRowSpec("Space", "Stage or unstage file", "Tap the 48 dp tick box"),
        GitShortcutRowSpec("Enter", "Open in Review changes", "Tap the file row"),
        GitShortcutRowSpec("Del", "Discard file changes", "Tap the undo icon on the row"),
        GitShortcutRowSpec("Ctrl+Enter", "Commit staged changes", "Tap Commit"),
        GitShortcutRowSpec("Ctrl+Alt+P", "Commit and push", "Tap ▾ next to Commit → Commit and push"),
        GitShortcutRowSpec("Shift+F10", "File context menu", "Long-press the file row"),
    ),
    "Review changes" to listOf(
        GitShortcutRowSpec("Y", "Stage block and advance", "Tap Stage block"),
        GitShortcutRowSpec("N", "Discard block", "Tap Discard"),
        GitShortcutRowSpec("L", "Stage / unstage lines", "Tap Lines…"),
        GitShortcutRowSpec("[ / ]", "Previous / next block", "Scroll or tap block header"),
        GitShortcutRowSpec("J / K", "Next / previous file", "Tap Next file / Previous file"),
        GitShortcutRowSpec("U", "Unstage block", "Tap Unstage"),
    ),
    "Conflict editor" to listOf(
        GitShortcutRowSpec("1", "Accept Current (ours)", "Tap Accept Current"),
        GitShortcutRowSpec("2", "Accept Incoming (theirs)", "Tap Accept Incoming"),
        GitShortcutRowSpec("3", "Accept Both", "Tap Accept Both"),
        GitShortcutRowSpec("[ / ]", "Previous / next conflict", "Tap Previous / Next"),
        GitShortcutRowSpec("Ctrl+Enter", "Mark as resolved", "Tap Mark as resolved"),
    ),
    "Git Graph" to listOf(
        GitShortcutRowSpec("↑ / ↓", "Select commit", "Tap a commit row"),
        GitShortcutRowSpec("Enter", "Open commit details", "Tap a commit row"),
        GitShortcutRowSpec("Ctrl+F", "Find in commits", "Tap Find"),
        GitShortcutRowSpec("Esc", "Close details, Find, or menu", "Tap × or outside"),
    ),
)

@Composable
fun GitShortcutsSheet(
    onDismiss: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val shape = RoundedCornerShape(12.dp)
        Column(
            modifier = Modifier
                .widthIn(min = 360.dp, max = 720.dp)
                .heightIn(max = 560.dp)
                .clip(shape)
                .background(tokens.bgSolid.color)
                .cssBorder(tokens.border.color, shape)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
                .testTag("git-shortcuts-sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    text = "Keyboard shortcuts & Touch twins",
                    style = PpmFont.sans(14, FontWeight.SemiBold, lineHeight = 18, color = tokens.text.color),
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onDismiss)
                        .testTag("git-shortcuts-close-btn"),
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(ProductIcon.X, size = 16.dp, tint = tokens.text3.color)
                }
            }

            for ((groupTitle, items) in TABLET_GIT_SHORTCUT_GROUPS) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(tokens.panel.color)
                        .cssBorder(tokens.border.color, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BasicText(
                        text = groupTitle.uppercase(),
                        style = PpmFont.sans(11, FontWeight.SemiBold, lineHeight = 14, color = tokens.accent.color),
                    )
                    for (item in items) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(88.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(tokens.panel2.color)
                                    .cssBorder(tokens.border.color, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                BasicText(
                                    text = item.keys,
                                    style = PpmFont.mono(10, FontWeight.Medium, lineHeight = 14, color = tokens.text.color),
                                )
                            }
                            BasicText(
                                text = item.action,
                                style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.text.color),
                                modifier = Modifier.weight(1f),
                            )
                            BasicText(
                                text = item.touchTwin,
                                style = PpmFont.sans(11, lineHeight = 15, color = tokens.text3.color),
                            )
                        }
                    }
                }
            }
        }
    }
}
