package com.hienlh.ppm.app.ui.phone.git

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.client.DiffHunk
import com.hienlh.ppm.client.FileHunks
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.kit.ActionPrimaryButton
import com.hienlh.ppm.design.kit.AppBar
import com.hienlh.ppm.design.kit.AppBarAction
import com.hienlh.ppm.design.kit.BottomActionCard
import com.hienlh.ppm.design.kit.Chip
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.git.FileChange
import com.hienlh.ppm.state.git.GitChangeGroup

/**
 * Unified read-only file diff screen pushed from phone Source Control (`Foundation N17`,
 * Requirements 9.2, 9.3, 9.6).
 *
 * - Renders inline unified diff hunks; no line checkboxes on a phone.
 * - Bottom [BottomActionCard] has a 48 dp `"Stage file"` / `"Unstage file"` primary button and, when
 *   [onStageHunk] is given, `"Whole file"` / `"By hunk"` chips; By hunk puts Stage / Unstage on each hunk.
 */
@Composable
fun PhoneGitDiffScreen(
    change: FileChange,
    fileHunks: FileHunks?,
    loading: Boolean,
    errorMessage: String? = null,
    busyStaging: Boolean = false,
    onBack: () -> Unit,
    onToggleWholeFileStage: (FileChange) -> Unit,
    onOpenFile: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    onStageHunk: ((FileChange, DiffHunk) -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    // "Whole file" stages the file from the bottom button; "By hunk" puts Stage / Unstage on every hunk
    // (the old hunk dialog's whole-hunk selection, `POST /git/stage-hunks` / `unstage-hunks`).
    var byHunk by remember(change.path, change.group) { mutableStateOf(false) }
    val hunkStaging = onStageHunk != null && !change.conflict && fileHunks?.binary != true
    val scopeLabel = if (change.group == GitChangeGroup.STAGED) "Staged" else "Working Tree"
    val countsSuffix = change.lineCounts?.let { (add, del) -> " · +$add −$del" }.orEmpty()
    val sublineBase = change.directoryPath.ifEmpty { change.path }
    val subline = "$sublineBase · $scopeLabel$countsSuffix"
    val primaryLabel = when {
        busyStaging && change.isStaged -> "Unstaging…"
        busyStaging -> "Staging…"
        change.isStaged -> "Unstage file"
        else -> "Stage file"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.panel2.color)
            .testTag("phone-git-diff-screen"),
    ) {
        AppBar(
            title = change.fileName,
            subline = subline,
            sublineMono = true,
            onBack = onBack,
            actions = if (onOpenFile != null) {
                listOf(
                    AppBarAction(
                        icon = ProductIcon.FileCode,
                        label = "Open file",
                        onClick = { onOpenFile(change.path) },
                    ),
                )
            } else {
                emptyList()
            },
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("phone-git-diff-loading"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PpmIcon(
                                icon = ProductIcon.Loader2,
                                size = 18.dp,
                                tint = tokens.text2.color,
                                spinning = true,
                            )
                            BasicText(
                                text = "Loading diff…",
                                style = ppmText(13.5f, FontWeight.Normal, lineHeight = 18f)
                                    .copy(color = tokens.text2.color),
                            )
                        }
                    }
                }
                !errorMessage.isNullOrEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                            .testTag("phone-git-diff-error"),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            text = errorMessage,
                            style = ppmText(13.5f, FontWeight.Medium, lineHeight = 19f)
                                .copy(color = tokens.error.color, textAlign = TextAlign.Center),
                        )
                    }
                }
                fileHunks?.binary == true -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                            .testTag("phone-git-diff-binary"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PpmIcon(
                                icon = ProductIcon.FileWarning,
                                size = 28.dp,
                                tint = tokens.text3.color,
                            )
                            BasicText(
                                text = "Binary file changed",
                                style = ppmText(14f, FontWeight.Medium, lineHeight = 19f)
                                    .copy(color = tokens.text2.color),
                            )
                        }
                    }
                }
                fileHunks == null || fileHunks.hunks.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                            .testTag("phone-git-diff-empty"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PpmIcon(
                                icon = ProductIcon.CheckCircle2,
                                size = 28.dp,
                                tint = tokens.text3.color,
                            )
                            BasicText(
                                text = change.lineNote ?: "No inline text changes",
                                style = ppmText(14f, FontWeight.Normal, lineHeight = 19f)
                                    .copy(color = tokens.text2.color),
                            )
                        }
                    }
                }
                else -> {
                    PhoneGitHunksList(
                        hunks = fileHunks.hunks,
                        hunkActionLabel = if (byHunk && hunkStaging) {
                            if (change.isStaged) "Unstage hunk" else "Stage hunk"
                        } else {
                            null
                        },
                        hunkActionEnabled = !busyStaging,
                        onHunkAction = { hunk -> onStageHunk?.invoke(change, hunk) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        if (!change.conflict) {
            BottomActionCard(
                applyWindowInsets = false,
                chipsContent = if (hunkStaging) {
                    {
                        Chip(
                            label = "Whole file",
                            icon = ProductIcon.FileCode,
                            selected = !byHunk,
                            onClick = { byHunk = false },
                            modifier = Modifier.testTag("phone-git-diff-whole-file-chip"),
                        )
                        Chip(
                            label = "By hunk",
                            icon = ProductIcon.ListChecks,
                            selected = byHunk,
                            onClick = { byHunk = true },
                            modifier = Modifier.testTag("phone-git-diff-by-hunk-chip"),
                        )
                    }
                } else {
                    null
                },
                primaryAction = {
                    ActionPrimaryButton(
                        label = primaryLabel,
                        icon = when {
                            busyStaging -> ProductIcon.Loader2
                            change.isStaged -> ProductIcon.Minus
                            else -> ProductIcon.Check
                        },
                        spinningIcon = busyStaging,
                        enabled = !busyStaging && change.canToggleStage,
                        onClick = { onToggleWholeFileStage(change) },
                        modifier = Modifier.testTag("phone-git-diff-stage-button"),
                    )
                },
            ) {}
        }
    }
}

@Composable
private fun PhoneGitHunksList(
    hunks: List<DiffHunk>,
    modifier: Modifier = Modifier,
    hunkActionLabel: String? = null,
    hunkActionEnabled: Boolean = true,
    onHunkAction: (DiffHunk) -> Unit = {},
) {
    val tokens = LocalPpmTokens.current
    val horizontalScroll = rememberScrollState()

    LazyColumn(
        modifier = modifier
            .horizontalScroll(horizontalScroll)
            .testTag("phone-git-diff-hunks"),
    ) {
        for (hunk in hunks) {
            item(key = "header-${hunk.id}-${hunk.index}") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
                        .background(tokens.accentWash.color)
                        .cssBorder(tokens.borderSoft.color, width = 1.dp, sides = CssSides.Vertical)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("phone-git-diff-hunk-header-${hunk.index}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        text = hunk.header,
                        style = ppmText(11.5f, FontWeight.Normal, FontKind.MONO, lineHeight = 16f)
                            .copy(color = tokens.text2.color),
                        maxLines = 1,
                    )
                    if (hunkActionLabel != null) {
                        Spacer(Modifier.width(12.dp))
                        Chip(
                            label = hunkActionLabel,
                            onClick = { if (hunkActionEnabled) onHunkAction(hunk) },
                            modifier = Modifier.testTag("phone-git-diff-hunk-action-${hunk.index}"),
                        )
                    }
                }
            }
            items(
                count = hunk.lines.size,
                key = { idx -> "line-${hunk.id}-${hunk.index}-$idx" },
            ) { lineIdx ->
                val line = hunk.lines[lineIdx]
                val rowBg = when (line.type) {
                    "+" -> tokens.success.opacity(0.14).color
                    "-" -> tokens.error.opacity(0.14).color
                    else -> Color.Transparent
                }
                val markerColor = when (line.type) {
                    "+" -> tokens.success.color
                    "-" -> tokens.error.color
                    else -> tokens.text3.color
                }
                val marker = when (line.type) {
                    "+" -> "+"
                    "-" -> "−"
                    else -> " "
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(rowBg)
                        .padding(end = 12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    BasicText(
                        text = line.oldNumber?.toString().orEmpty(),
                        modifier = Modifier
                            .width(30.dp)
                            .padding(end = 6.dp),
                        style = ppmText(11f, FontWeight.Normal, FontKind.MONO, lineHeight = 19.5f)
                            .copy(color = tokens.text3.color, textAlign = TextAlign.End),
                        maxLines = 1,
                    )
                    BasicText(
                        text = line.newNumber?.toString().orEmpty(),
                        modifier = Modifier
                            .width(30.dp)
                            .padding(end = 6.dp),
                        style = ppmText(11f, FontWeight.Normal, FontKind.MONO, lineHeight = 19.5f)
                            .copy(color = tokens.text3.color, textAlign = TextAlign.End),
                        maxLines = 1,
                    )
                    BasicText(
                        text = marker,
                        modifier = Modifier.width(14.dp),
                        style = ppmText(12.5f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 19.5f)
                            .copy(color = markerColor, textAlign = TextAlign.Center),
                        maxLines = 1,
                    )
                    BasicText(
                        text = line.content,
                        style = ppmText(12.5f, FontWeight.Normal, FontKind.MONO, lineHeight = 19.5f)
                            .copy(color = tokens.text.color),
                        softWrap = false,
                    )
                }
            }
        }
    }
}
