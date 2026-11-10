package com.hienlh.ppm.app.ui.phone.files

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.kit.Chip
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText

/**
 * Mode for `Phone 3 · D4 — Binary image diff` (Notes 31, 32).
 */
enum class PhoneImageDiffMode(val label: String) {
    IMAGE_PREVIEW("Image Preview"),
    TEXT_EDITOR("Text Editor"),
}

/**
 * Metadata and optional bytes for one side (`HEAD` or `Working Tree`) of a binary image diff (`D4`).
 */
data class PhoneImageRevisionSpec(
    val title: String,
    val dimensionsLabel: String? = null,
    val sizeLabel: String? = null,
    val deltaLabel: String? = null,
    val bytes: ByteArray? = null,
    val isAddedSide: Boolean = false,
) {
    val headerSummary: String
        get() = buildList {
            add(title)
            if (!dimensionsLabel.isNullOrBlank()) add(dimensionsLabel)
            if (!sizeLabel.isNullOrBlank()) {
                val withDelta = if (!deltaLabel.isNullOrBlank()) "$sizeLabel ($deltaLabel)" else sizeLabel
                add(withDelta)
            }
        }.joinToString(" · ")
}

/**
 * Stateless Phone 3 · Section 3 Diff screen (`D1` inline unified diff, `D4` binary image diff,
 * and all diff states: `Other binary`, `No content changes`, `Loading`).
 *
 * Implements Notes 28, 29, 30, 31, 32 and Requirement 8.1:
 * - Always renders inline on a phone (side-by-side is tablet-only).
 * - Folds unchanged context runs into 36 dp `"Show N unchanged lines"` rows (`dfold3`), and
 *   renders hunk headers at 40 dp (`dhunk3`).
 * - Header (`diffbar3`, 40 dp) shows `HEAD -> Working Tree`, `+added` / `−removed` counts (or
 *   `Image Preview` / `Text Editor` toggle for binary image diffs), and the `Open the file` button.
 */
@Composable
fun PhoneDiffScreen(
    filePath: String,
    diffText: String,
    modifier: Modifier = Modifier,
    leftRefLabel: String = "HEAD",
    rightRefLabel: String = "Working Tree",
    loading: Boolean = false,
    isBinary: Boolean = false,
    isImageBinary: Boolean = false,
    headImage: PhoneImageRevisionSpec = PhoneImageRevisionSpec(title = leftRefLabel),
    workingTreeImage: PhoneImageRevisionSpec = PhoneImageRevisionSpec(
        title = rightRefLabel,
        isAddedSide = true,
    ),
    onOpenFile: () -> Unit = {},
    onOpenAnyway: () -> Unit = {},
) {
    val tokens = LocalPpmTokens.current
    var expandedFolds by remember(filePath, diffText) { mutableStateOf(emptySet<Int>()) }
    var forceOpenBinaryAsText by remember(filePath, isBinary) { mutableStateOf(false) }
    var imageDiffMode by remember(filePath) { mutableStateOf(PhoneImageDiffMode.IMAGE_PREVIEW) }

    val parsed = remember(diffText, expandedFolds) {
        parsePhoneInlineDiff(
            diffText = diffText,
            contextLines = 3,
            expandedFoldIds = expandedFolds,
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.panel2.color)
            .testTag("phone-diff-screen"),
    ) {
        if (loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("phone-diff-loading"),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PpmIcon(ProductIcon.Loader2, 16.dp, tokens.text2.color, spinning = true)
                    BasicText(
                        text = "Loading diff...",
                        style = ppmText(13.5f, lineHeight = 18f).copy(color = tokens.text2.color),
                    )
                }
            }
            return@Column
        }

        // Diff header bar (`diffbar3`, 40 dp, Notes 30 & 32)
        PhoneDiffHeaderBar(
            leftRefLabel = leftRefLabel,
            rightRefLabel = rightRefLabel,
            addedCount = parsed.addedCount,
            removedCount = parsed.removedCount,
            showImageModeToggle = isImageBinary,
            imageDiffMode = imageDiffMode,
            onSelectImageDiffMode = { imageDiffMode = it },
            onOpenFile = onOpenFile,
        )

        when {
            isImageBinary && imageDiffMode == PhoneImageDiffMode.IMAGE_PREVIEW -> {
                PhoneBinaryImageDiffBody(
                    headImage = headImage,
                    workingTreeImage = workingTreeImage,
                    modifier = Modifier.weight(1f),
                )
            }
            isBinary && !isImageBinary && !forceOpenBinaryAsText -> {
                PhoneFilesEmptyBlock(
                    icon = ProductIcon.FileWarning,
                    title = "Binary file cannot be diffed as text.",
                    retryLabel = "Open Anyway",
                    onRetry = {
                        forceOpenBinaryAsText = true
                        onOpenAnyway()
                    },
                    testTag = "phone-diff-binary-warning",
                )
            }
            parsed.isEmpty -> {
                PhoneFilesEmptyBlock(
                    icon = ProductIcon.Check,
                    title = "No content changes",
                    testTag = "phone-diff-no-changes",
                )
            }
            else -> {
                PhoneInlineDiffBody(
                    rows = parsed.rows,
                    onExpandFold = { foldId ->
                        expandedFolds = expandedFolds + foldId
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 40 dp diff header bar (`diffbar3`, Notes 30 & 32):
 * `HEAD -> Working Tree`, `+added` / `−removed` counts or `Image Preview` / `Text Editor` toggle,
 * and the `Open the file` icon button (`i-file-code`).
 */
@Composable
fun PhoneDiffHeaderBar(
    leftRefLabel: String,
    rightRefLabel: String,
    addedCount: Int,
    removedCount: Int,
    showImageModeToggle: Boolean,
    imageDiffMode: PhoneImageDiffMode,
    onSelectImageDiffMode: (PhoneImageDiffMode) -> Unit,
    onOpenFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(tokens.panel.color)
            .cssBorder(tokens.borderSoft.color, width = 1.dp, sides = CssSides.Bottom)
            .padding(horizontal = 12.dp)
            .testTag("phone-diff-header-bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(
            text = leftRefLabel,
            style = ppmText(12f, FontWeight.Normal, FontKind.MONO, lineHeight = 16f)
                .copy(color = tokens.text2.color),
            maxLines = 1,
        )
        PpmIcon(ProductIcon.ArrowRight, 13.dp, tokens.text3.color)
        BasicText(
            text = rightRefLabel,
            style = ppmText(12f, FontWeight.Normal, FontKind.MONO, lineHeight = 16f)
                .copy(color = tokens.text2.color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.weight(1f))

        if (showImageModeToggle) {
            PhoneSegmentedControl(
                options = listOf(
                    PhoneImageDiffMode.IMAGE_PREVIEW.label to PhoneImageDiffMode.IMAGE_PREVIEW,
                    PhoneImageDiffMode.TEXT_EDITOR.label to PhoneImageDiffMode.TEXT_EDITOR,
                ),
                selected = imageDiffMode,
                onSelect = onSelectImageDiffMode,
                modifier = Modifier.testTag("phone-diff-image-mode-toggle"),
            )
        } else {
            if (addedCount > 0 || removedCount == 0) {
                BasicText(
                    text = "+$addedCount",
                    style = ppmText(12f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 16f)
                        .copy(color = tokens.success.color),
                    maxLines = 1,
                    modifier = Modifier.testTag("phone-diff-added-count"),
                )
            }
            if (removedCount > 0 || addedCount == 0) {
                BasicText(
                    text = "−$removedCount",
                    style = ppmText(12f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 16f)
                        .copy(color = tokens.error.color),
                    maxLines = 1,
                    modifier = Modifier.testTag("phone-diff-removed-count"),
                )
            }
        }

        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .semantics { contentDescription = "Open the file" }
                .ppmClickable(
                    shape = CircleShape,
                    onClickLabel = "Open the file",
                    onClick = onOpenFile,
                )
                .testTag("phone-diff-open-file"),
            contentAlignment = Alignment.Center,
        ) {
            PpmIcon(ProductIcon.FileCode, 18.dp, tokens.text2.color)
        }
    }
}

/**
 * Inline unified diff list (`dcode3`, Notes 28 & 29) with 40 dp hunk headers (`dhunk3`) and
 * 36 dp folded context rows (`dfold3`).
 */
@Composable
internal fun PhoneInlineDiffBody(
    rows: List<PhoneDiffRow>,
    onExpandFold: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val listState = rememberLazyListState()
    val horizontalScroll = rememberScrollState()

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .horizontalScroll(horizontalScroll)
            .testTag("phone-diff-inline-list"),
    ) {
        items(rows.size) { index ->
            when (val row = rows[index]) {
                is PhoneDiffRow.HunkHeader -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 40.dp)
                            .background(tokens.accentWash.color)
                            .cssBorder(tokens.borderSoft.color, width = 1.dp, sides = CssSides.Vertical)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("phone-diff-hunk-header"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            text = row.header,
                            style = ppmText(11.5f, FontWeight.Normal, FontKind.MONO, lineHeight = 16f)
                                .copy(color = tokens.text2.color),
                            maxLines = 1,
                        )
                    }
                }
                is PhoneDiffRow.FoldedContext -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .background(tokens.panel.color)
                            .cssBorder(tokens.borderSoft.color, width = 1.dp, sides = CssSides.Vertical)
                            .ppmClickable(
                                onClickLabel = row.label,
                                onClick = { onExpandFold(row.foldId) },
                            )
                            .padding(horizontal = 12.dp)
                            .testTag("phone-diff-fold-${row.foldId}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PpmIcon(ProductIcon.ChevronsUpDown, 14.dp, tokens.text2.color)
                        BasicText(
                            text = row.label,
                            style = ppmText(11.5f, FontWeight.Normal, FontKind.SANS, lineHeight = 16f)
                                .copy(color = tokens.text2.color),
                            maxLines = 1,
                        )
                    }
                }
                is PhoneDiffRow.CodeLine -> {
                    val rowBg = when (row.kind) {
                        PhoneDiffRow.CodeLine.Kind.ADDED -> tokens.success.opacity(0.14).color
                        PhoneDiffRow.CodeLine.Kind.REMOVED -> tokens.error.opacity(0.14).color
                        PhoneDiffRow.CodeLine.Kind.CONTEXT -> Color.Transparent
                    }
                    val markerColor = when (row.kind) {
                        PhoneDiffRow.CodeLine.Kind.ADDED -> tokens.success.color
                        PhoneDiffRow.CodeLine.Kind.REMOVED -> tokens.error.color
                        PhoneDiffRow.CodeLine.Kind.CONTEXT -> tokens.text3.color
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(rowBg)
                            .padding(end = 10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        BasicText(
                            text = row.oldLineNumber?.toString().orEmpty(),
                            modifier = Modifier
                                .width(28.dp)
                                .padding(end = 6.dp),
                            style = ppmText(11f, FontWeight.Normal, FontKind.MONO, lineHeight = 19.5f)
                                .copy(color = tokens.text3.color, textAlign = TextAlign.End),
                            maxLines = 1,
                        )
                        BasicText(
                            text = row.newLineNumber?.toString().orEmpty(),
                            modifier = Modifier
                                .width(28.dp)
                                .padding(end = 6.dp),
                            style = ppmText(11f, FontWeight.Normal, FontKind.MONO, lineHeight = 19.5f)
                                .copy(color = tokens.text3.color, textAlign = TextAlign.End),
                            maxLines = 1,
                        )
                        BasicText(
                            text = row.kind.marker,
                            modifier = Modifier.width(14.dp),
                            style = ppmText(12.5f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 19.5f)
                                .copy(color = markerColor, textAlign = TextAlign.Center),
                            maxLines = 1,
                        )
                        BasicText(
                            text = row.text,
                            style = ppmText(12.5f, FontWeight.Normal, FontKind.MONO, lineHeight = 19.5f)
                                .copy(color = tokens.text.color),
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Stacked `HEAD` and `Working Tree` image comparison cards (`Phone 3 · D4`, Note 31).
 */
@Composable
internal fun PhoneBinaryImageDiffBody(
    headImage: PhoneImageRevisionSpec,
    workingTreeImage: PhoneImageRevisionSpec,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("phone-diff-image-preview"),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PhoneImageRevisionCard(
                spec = headImage,
                testTag = "phone-diff-image-head",
            )
        }
        item {
            PhoneImageRevisionCard(
                spec = workingTreeImage,
                testTag = "phone-diff-image-working-tree",
            )
        }
    }
}

@Composable
private fun PhoneImageRevisionCard(
    spec: PhoneImageRevisionSpec,
    testTag: String,
) {
    val tokens = LocalPpmTokens.current
    val cardShape = RoundedCornerShape(12.dp)
    val borderColor = if (spec.isAddedSide) {
        tokens.success.opacity(0.4).color
    } else {
        tokens.border.color
    }
    val bitmap = remember(spec.bytes) {
        val raw = spec.bytes ?: return@remember null
        runCatching { BitmapFactory.decodeByteArray(raw, 0, raw.size)?.asImageBitmap() }.getOrNull()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(tokens.panel.color, cardShape)
            .cssBorder(borderColor, cardShape, width = 1.dp)
            .padding(10.dp)
            .testTag(testTag),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(
            text = spec.headerSummary,
            style = ppmText(12f, FontWeight.Normal, FontKind.MONO, lineHeight = 16f).copy(
                color = if (spec.isAddedSide) tokens.success.color else tokens.text2.color,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(tokens.bgSolid.color, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = spec.title,
                    modifier = Modifier.size(64.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                PpmIcon(
                    icon = ProductIcon.Image,
                    size = 36.dp,
                    tint = if (spec.isAddedSide) tokens.success.color else tokens.accent.color,
                )
            }
        }
    }
}
