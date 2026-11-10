package com.hienlh.ppm.app.ui.phone.git

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.client.GitBranchState
import com.hienlh.ppm.client.GitOperation
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
 * Short status hint rendered below the commit message input on phone (Phone 4 Section 2, Note N7).
 */
fun phoneCommitHint(
    stagedCount: Int,
    totalChangedFiles: Int,
    conflictCount: Int,
    message: String,
): String {
    if (conflictCount > 0) {
        val noun = if (conflictCount == 1) "conflict" else "conflicts"
        return "Resolve $conflictCount $noun on a tablet"
    }
    if (totalChangedFiles == 0) return "Nothing to commit"
    if (stagedCount == 0) return "Tick a file to stage"
    val filesLabel = "$stagedCount ${if (stagedCount == 1) "file" else "files"} staged"
    if (message.trim().isEmpty()) return "$filesLabel · write a message to commit"
    return "$filesLabel · ready to commit"
}

/**
 * Whether the plain `Commit` pill button in [CommitBox] is enabled (Requirement 9.5, Note N10).
 */
fun phoneCanCommit(
    stagedCount: Int,
    conflictCount: Int,
    message: String,
    operation: GitOperation? = null,
    busyAction: String? = null,
): Boolean {
    if (busyAction != null) return false
    if (conflictCount > 0) return false
    if (operation != null && operation.kind != "merge") return false
    return stagedCount > 0 && message.trim().isNotEmpty()
}

/**
 * Phone Source Control bottom commit card (`SC2`, `SC5`, `SC7`, `CF2`, Foundation N6 & Phone 4
 * Notes N7–N10).
 *
 * Uses [BottomActionCard] with:
 * - Multi-line commit message field growing up to 140 dp (`Commit message…`)
 * - Status hint line (`"2 files staged · write a message to commit"`, `"Tick a file to stage"`, etc.)
 * - `✦ AI` chip (`Write with AI`), hidden when [aiAvailable] is `false`
 * - 5-mode Sync chip (`Publish`, `Sync ↓M ↑N`, `Pull ↓M`, `Push ↑N`, `Synced`)
 * - Plain 48 dp `Commit` pill button (never a split dropdown on phone)
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
    onWriteWithAiClick: () -> Unit = {},
    onDismissAiError: () -> Unit = {},
    onSyncClick: (String) -> Unit = {},
    onCommitClick: () -> Unit = {},
    applyWindowInsets: Boolean = false,
) {
    val tokens = LocalPpmTokens.current
    val hintText = remember(stagedCount, totalChangedFiles, conflictCount, message) {
        phoneCommitHint(
            stagedCount = stagedCount,
            totalChangedFiles = totalChangedFiles,
            conflictCount = conflictCount,
            message = message,
        )
    }
    val canCommit = remember(stagedCount, conflictCount, message, operation, busyAction) {
        phoneCanCommit(
            stagedCount = stagedCount,
            conflictCount = conflictCount,
            message = message,
            operation = operation,
            busyAction = busyAction,
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

    BottomActionCard(
        modifier = modifier.testTag("phone-git-commit-box"),
        applyWindowInsets = applyWindowInsets,
        topContent = if (!aiErrorMessage.isNullOrEmpty()) {
            {
                val bannerShape = RoundedCornerShape(10.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(bannerShape)
                        .background(tokens.error.opacity(0.12).color, bannerShape)
                        .cssBorder(tokens.error.opacity(0.35).color, bannerShape, width = 1.dp)
                        .ppmClickable(
                            shape = bannerShape,
                            onClickLabel = "Dismiss AI error",
                            onClick = onDismissAiError,
                        )
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
                        text = aiErrorMessage,
                        style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.error.color),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            null
        },
        chipsContent = {
            if (aiAvailable) {
                val aiEnabled = !aiLoading && busyAction == null && totalChangedFiles > 0 && conflictCount == 0
                Chip(
                    label = if (aiLoading) "Writing…" else "AI",
                    icon = if (aiLoading) ProductIcon.Loader2 else ProductIcon.Sparkles,
                    selected = aiLoading,
                    enabled = aiEnabled,
                    onClick = onWriteWithAiClick,
                    modifier = Modifier.testTag("phone-git-ai-chip"),
                )
            }
            if (syncState != null) {
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
            ActionPrimaryButton(
                label = if (isCommitting) "Committing…" else "Commit",
                icon = if (isCommitting) ProductIcon.Loader2 else ProductIcon.Check,
                spinningIcon = isCommitting,
                enabled = canCommit,
                onClick = onCommitClick,
                modifier = Modifier.testTag("phone-git-commit-button"),
            )
        },
    ) {
        BasicTextField(
            value = message,
            onValueChange = onMessageChange,
            enabled = !isCommitting && !aiLoading,
            textStyle = ppmText(14f, FontWeight.Normal, lineHeight = 20f).copy(color = tokens.text.color),
            cursorBrush = SolidColor(tokens.accent.color),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 24.dp, max = 140.dp)
                .testTag("phone-git-commit-input"),
            decorationBox = { innerTextField ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (message.isEmpty()) {
                        BasicText(
                            text = if (aiLoading) "Writing commit message…" else "Commit message…",
                            style = ppmText(14f, FontWeight.Normal, lineHeight = 20f).copy(color = tokens.text3.color),
                        )
                    }
                    innerTextField()
                }
            },
        )

        BasicText(
            text = hintText,
            style = ppmText(12f, FontWeight.Normal, lineHeight = 16f).copy(
                color = if (conflictCount > 0) tokens.warning.color else tokens.text2.color,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("phone-git-commit-hint"),
        )
    }
}
