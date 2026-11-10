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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.client.GitBranch
import com.hienlh.ppm.client.GitBranchState
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.kit.KitSpec
import com.hienlh.ppm.design.kit.Sheet
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText

/**
 * Pure helper filtering [branches] to local branches only for the phone Branch sheet
 * (`BR3`, Note N11: remote branches, tags, worktrees, and branch creation stay on tablet).
 */
fun localBranchesForPhone(
    branches: List<GitBranch>,
    currentHead: String? = null,
): List<GitBranch> {
    val locals = branches.filter { !it.remote }
    return locals.sortedWith(
        compareByDescending<GitBranch> { it.current || (currentHead != null && it.name == currentHead) }
            .thenBy { it.name.lowercase() },
    )
}

/**
 * Formats the secondary line under a local branch row in [PhoneBranchSheet]
 * (`"origin/main · up to date"`, `"origin/feat · ↑2 ahead · ↓1 behind"`, `"local branch · no upstream"`).
 */
fun formatPhoneBranchSubtitle(branch: GitBranch): String {
    val tracking = branch.tracking
    val ahead = branch.ahead ?: 0
    val behind = branch.behind ?: 0
    val parts = mutableListOf<String>()
    if (!tracking.isNullOrEmpty()) {
        parts += tracking
    } else {
        parts += "local branch"
    }
    when {
        ahead > 0 && behind > 0 -> parts += "↑$ahead ahead · ↓$behind behind"
        ahead > 0 -> parts += "↑$ahead ahead"
        behind > 0 -> parts += "↓$behind behind"
        !tracking.isNullOrEmpty() -> parts += "up to date"
        else -> parts += "no upstream"
    }
    return parts.joinToString(" · ")
}

/**
 * Phone Branch sheet (`BR3`, Phone 4 Sections 2 & 7, Notes N11 & N25, Requirement 9.5).
 *
 * - Lists local branches only as 64 dp radio rows with ahead/behind counts and a `"current"` badge.
 * - Keeps the sheet open and renders a red error banner inside the sheet when `git checkout` is
 *   refused (for example, uncommitted changes to tracked files would be overwritten).
 * - Footer provides 48 dp `Pull` and `Push` buttons.
 */
@Composable
fun PhoneBranchSheet(
    branches: List<GitBranch>,
    branchState: GitBranchState,
    onSelectBranch: (String) -> Unit,
    onPull: () -> Unit,
    onPush: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    checkoutError: String? = null,
    busyAction: String? = null,
    hasConflicts: Boolean = false,
) {
    val tokens = LocalPpmTokens.current
    val localBranches = remember(branches, branchState.head) {
        localBranchesForPhone(branches, branchState.head)
    }
    val pullLabel = if (branchState.behind > 0) "Pull ↓${branchState.behind}" else "Pull"
    val pushLabel = if (branchState.ahead > 0) "Push ↑${branchState.ahead}" else "Push"
    val pullEnabled = busyAction == null && !hasConflicts && branchState.hasRemote
    val pushEnabled = busyAction == null && branchState.hasRemote
    val buttonShape = RoundedCornerShape(12.dp)

    Sheet(
        title = "Branch",
        subtitle = branchState.head ?: "Detached HEAD",
        onDismiss = onDismiss,
        modifier = modifier.testTag("phone-branch-sheet"),
        footer = {
            // 48 dp Pull button
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(KitSpec.MinTouchTarget)
                    .clip(buttonShape)
                    .background(tokens.panel.color, buttonShape)
                    .cssBorder(tokens.border.color, buttonShape, width = 1.dp)
                    .ppmClickable(
                        enabled = pullEnabled,
                        shape = buttonShape,
                        hoverColor = tokens.surfaceHover.color,
                        onClickLabel = pullLabel,
                        onClick = onPull,
                    )
                    .padding(horizontal = 14.dp)
                    .testTag("phone-branch-pull-button"),
                contentAlignment = Alignment.Center,
            ) {
                val fg = if (pullEnabled) tokens.text.color else tokens.text3.color
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PpmIcon(
                        icon = if (busyAction == "pull") ProductIcon.Loader2 else ProductIcon.ArrowDown,
                        size = 16.dp,
                        tint = fg,
                        spinning = busyAction == "pull",
                    )
                    BasicText(
                        text = pullLabel,
                        style = ppmText(14f, FontWeight.SemiBold, lineHeight = 18f).copy(color = fg),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 48 dp Push button
            val pushPrimary = branchState.ahead > 0
            val pushBg = when {
                !pushEnabled -> tokens.panel.color
                pushPrimary -> tokens.accent.color
                else -> tokens.panel.color
            }
            val pushFg = when {
                !pushEnabled -> tokens.text3.color
                pushPrimary -> tokens.accentFg.color
                else -> tokens.text.color
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(KitSpec.MinTouchTarget)
                    .clip(buttonShape)
                    .background(pushBg, buttonShape)
                    .then(
                        if (!pushPrimary || !pushEnabled) {
                            Modifier.cssBorder(tokens.border.color, buttonShape, width = 1.dp)
                        } else {
                            Modifier
                        },
                    )
                    .ppmClickable(
                        enabled = pushEnabled,
                        shape = buttonShape,
                        hoverColor = tokens.surfaceHover.color,
                        onClickLabel = pushLabel,
                        onClick = onPush,
                    )
                    .padding(horizontal = 14.dp)
                    .testTag("phone-branch-push-button"),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PpmIcon(
                        icon = if (busyAction == "push") ProductIcon.Loader2 else ProductIcon.ArrowUp,
                        size = 16.dp,
                        tint = pushFg,
                        spinning = busyAction == "push",
                    )
                    BasicText(
                        text = pushLabel,
                        style = ppmText(14f, FontWeight.SemiBold, lineHeight = 18f).copy(color = pushFg),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            // In-sheet red error banner when checkout is refused (BR3, Note N25)
            if (!checkoutError.isNullOrEmpty()) {
                val errorShape = RoundedCornerShape(12.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .clip(errorShape)
                        .background(tokens.error.opacity(0.10).color, errorShape)
                        .cssBorder(tokens.error.opacity(0.32).color, errorShape, width = 1.dp)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .testTag("phone-branch-checkout-error"),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PpmIcon(
                        icon = ProductIcon.AlertCircle,
                        size = 18.dp,
                        tint = tokens.error.color,
                    )
                    BasicText(
                        text = checkoutError,
                        style = ppmText(12.5f, FontWeight.Medium, lineHeight = 18f).copy(color = tokens.error.color),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (localBranches.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = "No local branches found",
                        style = ppmText(13.5f, FontWeight.Normal, lineHeight = 18f).copy(color = tokens.text2.color),
                    )
                }
            } else {
                for (branch in localBranches) {
                    val isCurrent = branch.current || (branchState.head != null && branch.name == branchState.head)
                    val subtitle = remember(branch) { formatPhoneBranchSubtitle(branch) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = KitSpec.RowPhoneTwoLineHeight)
                            .background(if (isCurrent) tokens.accentWash.color else Color.Transparent)
                            .cssBorder(tokens.borderSoft.color, width = 1.dp, sides = CssSides.Bottom)
                            .ppmClickable(
                                enabled = busyAction == null && !isCurrent,
                                role = Role.RadioButton,
                                onClickLabel = "Switch to ${branch.name}",
                                onClick = { onSelectBranch(branch.name) },
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .testTag("phone-branch-row-${branch.name}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // 20 dp radio circle
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .cssBorder(
                                    color = if (isCurrent) tokens.accent.color else tokens.border.color,
                                    shape = CircleShape,
                                    width = 2.dp,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isCurrent) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(tokens.accent.color, CircleShape),
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            BasicText(
                                text = branch.name,
                                style = ppmText(
                                    size = 14f,
                                    weight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                                    kind = FontKind.MONO,
                                    lineHeight = 18f,
                                ).copy(color = if (isCurrent) tokens.accent.color else tokens.text.color),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            BasicText(
                                text = subtitle,
                                style = ppmText(12f, FontWeight.Normal, FontKind.MONO, lineHeight = 15f)
                                    .copy(color = tokens.text2.color),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        if (isCurrent) {
                            val badgeShape = RoundedCornerShape(999.dp)
                            Box(
                                modifier = Modifier
                                    .clip(badgeShape)
                                    .background(tokens.accentWash.color, badgeShape)
                                    .cssBorder(tokens.accentWashBorder.color, badgeShape, width = 1.dp)
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                BasicText(
                                    text = "current",
                                    style = ppmText(11f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 13f)
                                        .copy(color = tokens.accent.color),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Sync failed sheet on phone (`SY1`, Phone 4 Section 7, Note N26, Requirement 9.5).
 *
 * Shows a summary of the failed sync operation (`Push`, `Pull`, `Publish`, or `Fetch`), the full
 * git output in a scrollable monospace container, and 48 dp `Copy` + `Retry` buttons.
 */
@Composable
fun PhoneSyncErrorSheet(
    title: String,
    summary: String,
    gitOutput: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    retrying: Boolean = false,
) {
    val tokens = LocalPpmTokens.current
    val clipboard = LocalClipboardManager.current
    val buttonShape = RoundedCornerShape(12.dp)
    val outputShape = RoundedCornerShape(10.dp)

    Sheet(
        title = title,
        onDismiss = onDismiss,
        modifier = modifier.testTag("phone-sync-error-sheet"),
        footer = {
            // 48 dp Copy button
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(KitSpec.MinTouchTarget)
                    .clip(buttonShape)
                    .background(tokens.panel.color, buttonShape)
                    .cssBorder(tokens.border.color, buttonShape, width = 1.dp)
                    .ppmClickable(
                        shape = buttonShape,
                        hoverColor = tokens.surfaceHover.color,
                        onClickLabel = "Copy git output",
                        onClick = { clipboard.setText(AnnotatedString(gitOutput.ifBlank { summary })) },
                    )
                    .padding(horizontal = 14.dp)
                    .semantics { contentDescription = "Copy" }
                    .testTag("phone-sync-error-copy"),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PpmIcon(
                        icon = ProductIcon.Copy,
                        size = 16.dp,
                        tint = tokens.text.color,
                    )
                    BasicText(
                        text = "Copy",
                        style = ppmText(14f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
                    )
                }
            }

            // 48 dp Retry button
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(KitSpec.MinTouchTarget)
                    .clip(buttonShape)
                    .background(tokens.accent.color, buttonShape)
                    .ppmClickable(
                        enabled = !retrying,
                        shape = buttonShape,
                        hoverColor = tokens.accentFg.opacity(0.12).color,
                        onClickLabel = "Retry",
                        onClick = onRetry,
                    )
                    .padding(horizontal = 14.dp)
                    .semantics { contentDescription = "Retry" }
                    .testTag("phone-sync-error-retry"),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PpmIcon(
                        icon = if (retrying) ProductIcon.Loader2 else ProductIcon.RefreshCw,
                        size = 16.dp,
                        tint = tokens.accentFg.color,
                        spinning = retrying,
                    )
                    BasicText(
                        text = if (retrying) "Retrying…" else "Retry",
                        style = ppmText(14f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.accentFg.color),
                    )
                }
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BasicText(
                text = summary,
                style = ppmText(13.5f, FontWeight.Normal, lineHeight = 19f).copy(color = tokens.text2.color),
                modifier = Modifier.testTag("phone-sync-error-summary"),
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp, max = 260.dp)
                    .clip(outputShape)
                    .background(tokens.bgSolid.color, outputShape)
                    .cssBorder(tokens.border.color, outputShape, width = 1.dp)
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("phone-sync-error-output"),
            ) {
                BasicText(
                    text = gitOutput.ifBlank { summary },
                    style = ppmText(11.5f, FontWeight.Normal, FontKind.MONO, lineHeight = 17f)
                        .copy(color = tokens.text.color),
                )
            }
        }
    }
}
