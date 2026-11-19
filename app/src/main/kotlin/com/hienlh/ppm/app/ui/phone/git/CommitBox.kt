package com.hienlh.ppm.app.ui.phone.git

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.hienlh.ppm.client.GitBranchState
import com.hienlh.ppm.client.GitOperation
import com.hienlh.ppm.client.LastCommit
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.kit.ActionPrimaryButton
import com.hienlh.ppm.design.kit.BottomActionCard
import com.hienlh.ppm.design.kit.Chip
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.git.GitChangesView
import com.hienlh.ppm.state.git.WriteWithAi
import com.hienlh.ppm.state.git.WriteWithAiController
import com.hienlh.ppm.app.asksForTextFocus

/**
 * Derived state for the 5-mode Sync chip in the phone [CommitBox]
 * (`Publish`, `Sync ↓M ↑N`, `Pull ↓M`, `Push ↑N`, `Synced` — Phone 4 Section 2, Note N9).
 */
data class PhoneSyncChipState(
    val mode: String,
    val label: String,
    val secondaryLabel: String? = null,
    val isPrimary: Boolean = false,
    val enabled: Boolean = true,
)

/**
 * Pure helper computing the 5-mode Sync chip state from [branch] (`GET /git/changes`).
 */
fun phoneSyncChipState(
    branch: GitBranchState,
    totalChangedFiles: Int,
    conflictCount: Int = 0,
    busyAction: String? = null,
): PhoneSyncChipState? {
    val mode = GitChangesView.syncMode(branch) ?: return null
    val isPrimary = GitChangesView.syncIsPrimary(mode, totalChangedFiles)
    val pausedByConflict = conflictCount > 0 && (mode == "pull" || mode == "sync")
    val enabled = busyAction == null && !pausedByConflict && mode != "synced"
    return when (mode) {
        "publish" -> PhoneSyncChipState(
            mode = mode,
            label = "Publish",
            secondaryLabel = null,
            isPrimary = isPrimary,
            enabled = enabled,
        )
        "sync" -> PhoneSyncChipState(
            mode = mode,
            label = "Sync",
            secondaryLabel = "↓${branch.behind} ↑${branch.ahead}",
            isPrimary = isPrimary,
            enabled = enabled,
        )
        "pull" -> PhoneSyncChipState(
            mode = mode,
            label = "Pull",
            secondaryLabel = "↓${branch.behind}",
            isPrimary = isPrimary,
            enabled = enabled,
        )
        "push" -> PhoneSyncChipState(
            mode = mode,
            label = "Push",
            secondaryLabel = "↑${branch.ahead}",
            isPrimary = isPrimary,
            enabled = enabled,
        )
        else -> PhoneSyncChipState(
            mode = "synced",
            label = "Synced",
            secondaryLabel = null,
            isPrimary = false,
            enabled = busyAction == null,
        )
    }
}

/**
 * Short status hint rendered below the commit message input on phone (Phone 4 Section 2, Note N7,
 * and Requirement 10 Write with AI states).
 */
fun phoneCommitHint(
    stagedCount: Int,
    totalChangedFiles: Int,
    conflictCount: Int,
    message: String,
    aiState: WriteWithAi = WriteWithAi.Idle,
    isTablet: Boolean = false,
): String {
    if (conflictCount > 0) {
        val noun = if (conflictCount == 1) "conflict" else "conflicts"
        return "Resolve $conflictCount $noun to commit"
    }
    if (aiState is WriteWithAi.Writing) {
        return aiState.writingLabel(stagedCount = stagedCount, tablet = isTablet)
    }
    if (aiState is WriteWithAi.Written && stagedCount > 0 && message.trim().isNotEmpty()) {
        return aiState.writtenHint(stagedCount = stagedCount, tablet = isTablet)
    }
    if (totalChangedFiles == 0 && stagedCount == 0) return "Nothing to commit"
    if (stagedCount == 0) {
        return if (isTablet) {
            "Tick a file or stage a block to commit"
        } else {
            "Tick a file to stage"
        }
    }
    val filesLabel = "$stagedCount ${if (stagedCount == 1) "file" else "files"} staged"
    if (message.trim().isEmpty()) return "$filesLabel · write a message to commit"
    return "$filesLabel · ready to commit"
}

/**
 * Whether the `Commit` button in [CommitBox] is enabled (Requirement 9.5, Requirement 10.2, Note N10).
 *
 * Returns `false` while [aiWriting] or `aiState is WriteWithAi.Writing` (Requirement 10.2).
 */
fun phoneCanCommit(
    stagedCount: Int,
    conflictCount: Int,
    message: String,
    operation: GitOperation? = null,
    busyAction: String? = null,
    aiWriting: Boolean = false,
    aiState: WriteWithAi = WriteWithAi.Idle,
): Boolean {
    if (busyAction != null) return false
    if (aiWriting || aiState is WriteWithAi.Writing) return false
    if (conflictCount > 0) return false
    if (operation != null && operation.kind != "merge") return false
    return stagedCount > 0 && message.trim().isNotEmpty()
}

/**
 * Source Control bottom commit card for phone and tablet (`SC2`, `SC5`, `SC7`, `CF2`, `Tablet 4`
 * `SC1`–`SC3`, Requirements 9.5 & 10.1–10.5).
 *
 * Uses [BottomActionCard] with:
 * - Multi-line commit message field growing up to 140 dp (`Commit message…` / `"Message — or let the AI write it"`)
 * - Status hint line (`"2 files staged · write a message to commit"`, `"Written by the AI — edit before committing"`, etc.)
 * - `✦ AI` chip (`Write with AI`), with states [WriteWithAi.Idle], [WriteWithAi.Writing], [WriteWithAi.Written], [WriteWithAi.Failed]:
 *   - Hidden when [aiAvailable] is `false` (e.g. server returned HTTP 404)
 *   - Disabled with reason `"Stage changes first"` when [stagedCount] is `0` (Requirement 10.4)
 *   - Locks `Commit` while [WriteWithAi.Writing] (Requirement 10.2)
 *   - Keeps typed [message] and shows error banner with `Retry` on [WriteWithAi.Failed] (Requirement 10.5)
 *   - Leaves generated message editable and never commits automatically on [WriteWithAi.Written] (Requirement 10.3)
 * - 5-mode Sync chip on phone (`Publish`, `Sync ↓M ↑N`, `Pull ↓M`, `Push ↑N`, `Synced`)
 * - Plain 48 dp `Commit` pill button on phone; split `Commit N files ▾` button on tablet (`isTablet = true`)
 */
@Composable
fun CommitBox(
    message: String,
    onMessageChange: (String) -> Unit,
    stagedCount: Int,
    totalChangedFiles: Int,
    modifier: Modifier = Modifier,
    conflictCount: Int = 0,
    branchState: GitBranchState = GitBranchState(
        head = null,
        oid = null,
        upstream = null,
        upstreamGone = false,
        ahead = 0,
        behind = 0,
    ),
    operation: GitOperation? = null,
    busyAction: String? = null,
    aiAvailable: Boolean = true,
    aiLoading: Boolean = false,
    aiErrorMessage: String? = null,
    aiState: WriteWithAi = WriteWithAi.Idle,
    onWriteWithAiClick: () -> Unit = {},
    onRetryWriteWithAi: (() -> Unit)? = null,
    onUndoWriteWithAi: (() -> Unit)? = null,
    onDismissAiError: () -> Unit = {},
    onSyncClick: (String) -> Unit = {},
    onCommitClick: () -> Unit = {},
    isTablet: Boolean = !LocalPpmMobile.current,
    lastCommit: LastCommit? = null,
    onCommitOptionClick: ((amend: Boolean, signoff: Boolean, andPush: Boolean) -> Unit)? = null,
    onUndoLastCommitClick: (() -> Unit)? = null,
    applyWindowInsets: Boolean = false,
) {
    val tokens = LocalPpmTokens.current
    var tabletCommitMenuOpen by remember { mutableStateOf(false) }

    val effectiveAiState = remember(aiState, aiLoading, aiErrorMessage, message) {
        when {
            aiState !is WriteWithAi.Idle -> aiState
            aiLoading -> WriteWithAi.Writing
            !aiErrorMessage.isNullOrEmpty() -> WriteWithAi.Failed(aiErrorMessage, message)
            else -> WriteWithAi.Idle
        }
    }
    val isAiWriting = effectiveAiState is WriteWithAi.Writing || aiLoading
    val isAiWritten = effectiveAiState is WriteWithAi.Written
    val activeErrorMessage = (effectiveAiState as? WriteWithAi.Failed)?.message ?: aiErrorMessage

    val aiDisabledReason = remember(aiAvailable, stagedCount, conflictCount, busyAction) {
        if (!aiAvailable) {
            null
        } else {
            WriteWithAi.disabledReason(
                stagedCount = stagedCount,
                conflictCount = conflictCount,
                busyAction = busyAction,
            )
        }
    }
    val aiEnabled = remember(aiAvailable, effectiveAiState, isAiWriting, stagedCount, conflictCount, busyAction) {
        WriteWithAi.isButtonEnabled(
            state = if (isAiWriting) WriteWithAi.Writing else effectiveAiState,
            stagedCount = stagedCount,
            conflictCount = conflictCount,
            busyAction = busyAction,
            endpointSupported = aiAvailable,
        )
    }

    val hintText = remember(stagedCount, totalChangedFiles, conflictCount, message, effectiveAiState, isTablet) {
        phoneCommitHint(
            stagedCount = stagedCount,
            totalChangedFiles = totalChangedFiles,
            conflictCount = conflictCount,
            message = message,
            aiState = effectiveAiState,
            isTablet = isTablet,
        )
    }
    val canCommit = remember(stagedCount, conflictCount, message, operation, busyAction, isAiWriting, effectiveAiState) {
        phoneCanCommit(
            stagedCount = stagedCount,
            conflictCount = conflictCount,
            message = message,
            operation = operation,
            busyAction = busyAction,
            aiWriting = isAiWriting,
            aiState = effectiveAiState,
        )
    }
    val syncState = remember(branchState, totalChangedFiles, conflictCount, busyAction) {
        phoneSyncChipState(
            branch = branchState,
            totalChangedFiles = totalChangedFiles,
            conflictCount = conflictCount,
            busyAction = busyAction,
        )
    }
    val isCommitting = busyAction == "commit"
    val isSyncing = busyAction == "sync" || busyAction == "pull" || busyAction == "push" ||
        busyAction == "publish" || busyAction == "fetch"

    val commitButtonLabel = when {
        isCommitting -> "Committing…"
        isTablet && stagedCount > 0 -> "Commit $stagedCount ${if (stagedCount == 1) "file" else "files"}"
        else -> "Commit"
    }

    BottomActionCard(
        modifier = modifier.testTag(if (isTablet) "tablet-git-commit-box" else "phone-git-commit-box"),
        applyWindowInsets = applyWindowInsets,
        topContent = if (!activeErrorMessage.isNullOrEmpty()) {
            {
                val bannerShape = RoundedCornerShape(10.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(bannerShape)
                        .background(tokens.error.opacity(0.12).color, bannerShape)
                        .cssBorder(tokens.error.opacity(0.35).color, bannerShape, width = 1.dp)
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                        .testTag("phone-git-ai-error"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PpmIcon(
                        icon = ProductIcon.AlertCircle,
                        size = 15.dp,
                        tint = tokens.error.color,
                    )
                    BasicText(
                        text = activeErrorMessage,
                        style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.error.color),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .ppmClickable(
                                onClickLabel = "Dismiss AI error",
                                onClick = onDismissAiError,
                            )
                            .testTag("phone-git-ai-dismiss"),
                    )
                    val retryShape = RoundedCornerShape(6.dp)
                    Box(
                        modifier = Modifier
                            .clip(retryShape)
                            .background(tokens.error.opacity(0.18).color, retryShape)
                            .ppmClickable(
                                shape = retryShape,
                                onClickLabel = "Retry Write with AI",
                                onClick = onRetryWriteWithAi ?: onWriteWithAiClick,
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("phone-git-ai-retry"),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            text = "Retry",
                            style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.error.color),
                        )
                    }
                }
            }
        } else {
            null
        },
        chipsContent = {
            if (aiAvailable) {
                val aiLabel = when {
                    isAiWriting -> "Writing…"
                    isTablet && isAiWritten -> "Write again"
                    else -> "AI"
                }
                val aiIcon = when {
                    isAiWriting -> ProductIcon.Loader2
                    isTablet && isAiWritten -> ProductIcon.RefreshCw
                    else -> ProductIcon.Sparkles
                }
                // The web's button name (`aria-label`), with why it is off when it is.
                val aiSemanticsLabel = when {
                    isAiWriting -> "Writing commit message with AI"
                    aiDisabledReason != null -> WriteWithAi.DRAFT_BUTTON_LABEL + " — " + aiDisabledReason
                    else -> WriteWithAi.DRAFT_BUTTON_LABEL
                }
                Chip(
                    label = aiLabel,
                    icon = aiIcon,
                    selected = isAiWriting || isAiWritten,
                    enabled = aiEnabled,
                    onClick = onWriteWithAiClick,
                    modifier = Modifier
                        .semantics { contentDescription = aiSemanticsLabel }
                        .testTag("phone-git-ai-chip"),
                )
            }
            if (!isTablet && syncState != null) {
                val syncIcon = when {
                    isSyncing -> ProductIcon.Loader2
                    syncState.mode == "publish" -> ProductIcon.CloudUpload
                    syncState.mode == "pull" -> ProductIcon.ArrowDown
                    syncState.mode == "push" -> ProductIcon.ArrowUp
                    syncState.mode == "sync" -> ProductIcon.ArrowDownUp
                    else -> ProductIcon.Check
                }
                Chip(
                    label = if (isSyncing) "${syncState.label}…" else syncState.label,
                    icon = syncIcon,
                    secondaryLabel = syncState.secondaryLabel,
                    selected = syncState.isPrimary,
                    enabled = syncState.enabled,
                    onClick = {
                        if (syncState.mode == "synced") {
                            onSyncClick("fetch")
                        } else {
                            onSyncClick(syncState.mode)
                        }
                    },
                    modifier = Modifier.testTag("phone-git-sync-chip"),
                )
            }
        },
        primaryAction = {
            if (isTablet) {
                Box {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        ActionPrimaryButton(
                            label = commitButtonLabel,
                            icon = if (isCommitting) ProductIcon.Loader2 else ProductIcon.Check,
                            spinningIcon = isCommitting,
                            enabled = canCommit,
                            onClick = {
                                if (onCommitOptionClick != null) {
                                    onCommitOptionClick(false, false, false)
                                } else {
                                    onCommitClick()
                                }
                            },
                            modifier = Modifier.testTag("phone-git-commit-button"),
                        )
                        val moreEnabled = !isAiWriting && busyAction == null
                        val moreShape = RoundedCornerShape(12.dp)
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(48.dp)
                                .alpha(if (moreEnabled) 1f else 0.45f)
                                .clip(moreShape)
                                .background(
                                    if (moreEnabled) tokens.accent.color else tokens.surfaceHover.color,
                                    moreShape,
                                )
                                .cssBorder(tokens.accentFg.opacity(0.2).color, sides = CssSides.Start)
                                .ppmClickable(
                                    enabled = moreEnabled,
                                    shape = moreShape,
                                    onClickLabel = "More commit actions",
                                    onClick = { tabletCommitMenuOpen = !tabletCommitMenuOpen },
                                )
                                .testTag("tablet-git-commit-more-button"),
                            contentAlignment = Alignment.Center,
                        ) {
                            PpmIcon(
                                icon = ProductIcon.ChevronDown,
                                size = 14.dp,
                                tint = if (moreEnabled) tokens.accentFg.color else tokens.text3.color,
                            )
                        }
                    }

                    if (tabletCommitMenuOpen) {
                        Popup(
                            alignment = Alignment.TopEnd,
                            onDismissRequest = { tabletCommitMenuOpen = false },
                            properties = PopupProperties(focusable = true),
                        ) {
                            val popShape = RoundedCornerShape(10.dp)
                            val rewritable = lastCommit != null && !lastCommit.pushed
                            val canAmend = !isAiWriting && busyAction == null && rewritable &&
                                (stagedCount > 0 || message.trim().isNotEmpty())
                            val canUndoLast = !isAiWriting && busyAction == null && rewritable &&
                                lastCommit?.hasParent == true && operation == null
                            Column(
                                modifier = Modifier
                                    .width(216.dp)
                                    .clip(popShape)
                                    .background(tokens.bgSolid.color, popShape)
                                    .cssBorder(tokens.border.color, popShape)
                                    .padding(vertical = 4.dp)
                                    .testTag("tablet-git-commit-more-menu"),
                            ) {
                                TabletCommitMenuItem(
                                    label = "Commit and push",
                                    enabled = canCommit,
                                    tag = "tablet-git-menu-commit-push",
                                ) {
                                    tabletCommitMenuOpen = false
                                    if (onCommitOptionClick != null) {
                                        onCommitOptionClick(false, false, true)
                                    } else {
                                        onCommitClick()
                                    }
                                }
                                TabletCommitMenuItem(
                                    label = "Amend last commit",
                                    enabled = canAmend,
                                    tag = "tablet-git-menu-amend",
                                ) {
                                    tabletCommitMenuOpen = false
                                    onCommitOptionClick?.invoke(true, false, false)
                                }
                                TabletCommitMenuItem(
                                    label = "Commit with sign-off",
                                    enabled = canCommit,
                                    tag = "tablet-git-menu-signoff",
                                ) {
                                    tabletCommitMenuOpen = false
                                    if (onCommitOptionClick != null) {
                                        onCommitOptionClick(false, true, false)
                                    } else {
                                        onCommitClick()
                                    }
                                }
                                TabletCommitMenuItem(
                                    label = "Undo last commit",
                                    enabled = canUndoLast,
                                    tag = "tablet-git-menu-undo",
                                ) {
                                    tabletCommitMenuOpen = false
                                    onUndoLastCommitClick?.invoke()
                                }
                            }
                        }
                    }
                }
            } else {
                ActionPrimaryButton(
                    label = commitButtonLabel,
                    icon = if (isCommitting) ProductIcon.Loader2 else ProductIcon.Check,
                    spinningIcon = isCommitting,
                    enabled = canCommit,
                    onClick = onCommitClick,
                    modifier = Modifier.testTag("phone-git-commit-button"),
                )
            }
        },
    ) {
        BasicTextField(
            value = message,
            onValueChange = onMessageChange,
            enabled = !isCommitting && !isAiWriting,
            textStyle = ppmText(14f, FontWeight.Normal, lineHeight = 20f).copy(color = tokens.text.color),
            cursorBrush = SolidColor(tokens.accent.color),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 24.dp, max = 140.dp)
                .asksForTextFocus()
                .testTag("phone-git-commit-input"),
            decorationBox = { innerTextField ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (message.isEmpty()) {
                        val placeholder = when {
                            isAiWriting -> effectiveAiState.writingLabel(stagedCount = stagedCount, tablet = isTablet)
                            isTablet -> "Message — or let the AI write it"
                            else -> "Commit message…"
                        }
                        BasicText(
                            text = placeholder,
                            style = ppmText(14f, FontWeight.Normal, lineHeight = 20f).copy(color = tokens.text3.color),
                        )
                    }
                    innerTextField()
                }
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText(
                text = hintText,
                style = ppmText(12f, FontWeight.Normal, lineHeight = 16f).copy(
                    color = when {
                        conflictCount > 0 -> tokens.warning.color
                        isAiWritten -> tokens.accent.color
                        else -> tokens.text2.color
                    },
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .testTag("phone-git-commit-hint"),
            )

            if (isAiWritten && onUndoWriteWithAi != null) {
                val undoShape = RoundedCornerShape(4.dp)
                Box(
                    modifier = Modifier
                        .clip(undoShape)
                        .ppmClickable(
                            shape = undoShape,
                            onClickLabel = "Undo AI commit message",
                            onClick = onUndoWriteWithAi,
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .testTag("phone-git-ai-undo"),
                ) {
                    BasicText(
                        text = "Undo",
                        style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.accent.color),
                    )
                }
            }

            if (aiAvailable && !isAiWriting && aiDisabledReason == WriteWithAi.STAGE_CHANGES_FIRST_REASON) {
                BasicText(
                    text = WriteWithAi.STAGE_CHANGES_FIRST_REASON,
                    style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text3.color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("phone-git-ai-disabled-reason"),
                )
            }
        }
    }
}

/**
 * Overload of [CommitBox] bound directly to a [WriteWithAiController].
 */
@Composable
fun CommitBox(
    controller: WriteWithAiController,
    totalChangedFiles: Int,
    modifier: Modifier = Modifier,
    branchState: GitBranchState = GitBranchState(
        head = null,
        oid = null,
        upstream = null,
        upstreamGone = false,
        ahead = 0,
        behind = 0,
    ),
    operation: GitOperation? = null,
    busyAction: String? = null,
    onWriteWithAiClick: () -> Unit = {},
    onRetryWriteWithAi: (() -> Unit)? = null,
    onSyncClick: (String) -> Unit = {},
    onCommitClick: () -> Unit = {},
    isTablet: Boolean = !LocalPpmMobile.current,
    lastCommit: LastCommit? = null,
    onCommitOptionClick: ((amend: Boolean, signoff: Boolean, andPush: Boolean) -> Unit)? = null,
    onUndoLastCommitClick: (() -> Unit)? = null,
    applyWindowInsets: Boolean = false,
) {
    CommitBox(
        message = controller.commitMessage,
        onMessageChange = controller::updateMessage,
        stagedCount = controller.stagedCount,
        totalChangedFiles = totalChangedFiles,
        modifier = modifier,
        conflictCount = controller.conflictCount,
        branchState = branchState,
        operation = operation,
        busyAction = busyAction,
        aiAvailable = controller.isVisible,
        aiLoading = controller.state is WriteWithAi.Writing,
        aiErrorMessage = controller.state.errorMessage,
        aiState = controller.state,
        onWriteWithAiClick = onWriteWithAiClick,
        onRetryWriteWithAi = onRetryWriteWithAi ?: onWriteWithAiClick,
        onUndoWriteWithAi = { controller.undoWritten() },
        onDismissAiError = controller::dismissError,
        onSyncClick = onSyncClick,
        onCommitClick = onCommitClick,
        isTablet = isTablet,
        lastCommit = lastCommit,
        onCommitOptionClick = onCommitOptionClick,
        onUndoLastCommitClick = onUndoLastCommitClick,
        applyWindowInsets = applyWindowInsets,
    )
}

@Composable
private fun TabletCommitMenuItem(
    label: String,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .ppmClickable(
                enabled = enabled,
                onClickLabel = label,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = label,
            style = ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(color = tokens.text.color),
        )
    }
}
