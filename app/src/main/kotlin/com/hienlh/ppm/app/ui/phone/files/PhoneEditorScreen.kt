package com.hienlh.ppm.app.ui.phone.files

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.hienlh.ppm.design.kit.BottomActionCard
import com.hienlh.ppm.design.kit.Chip
import com.hienlh.ppm.design.kit.KitSpec
import com.hienlh.ppm.design.kit.Sheet
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.FileDirectoryEntry
import kotlinx.serialization.json.Json

private val PrettyJson = Json { prettyPrint = true }

/**
 * Stateless Phone 3 · Section 2 Editor screen (`E1`, `E2`, `E9`, `E11`, and all editor states:
 * Notes 11, 14, 16, 23, 25, 27, 67, 68, 69, 70 & Requirement 8.1).
 */
@Composable
fun PhoneEditorScreen(
    filePath: String,
    content: String,
    modifier: Modifier = Modifier,
    languageLabel: String = "TypeScript React",
    cursorLine: Int = 1,
    cursorColumn: Int = 1,
    dirty: Boolean = false,
    saving: Boolean = false,
    saveError: String? = null,
    changedOnDisk: Boolean = false,
    changedOnDiskSubtitle: String = CHANGED_ON_DISK_SUBTITLE,
    readOnly: Boolean = false,
    isReadOnlyCell: Boolean = false,
    typing: Boolean = false,
    wordWrap: Boolean = true,
    loading: Boolean = false,
    loadError: String? = null,
    offlineSeconds: Int? = null,
    selectedText: String? = null,
    onToggleWordWrap: () -> Unit = {},
    onSave: () -> Unit = {},
    onDiscardAndReload: () -> Unit = {},
    onOverwriteDisk: () -> Unit = {},
    onReloadFromDisk: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    onCopyPath: (() -> Unit)? = null,
    onCloseTab: (() -> Unit)? = null,
    onRetryLoad: () -> Unit = {},
    onKeyPress: (PhoneEditorKey) -> Unit = {},
    onSelectionAction: (PhoneSelectionAction) -> Unit = {},
    onBreadcrumbSegmentClick: (PhonePathCrumb) -> Unit = {},
    editorBodyContent: (@Composable () -> Unit)? = null,
    onResolveConflicts: (() -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    val context = LocalContext.current
    val conflictCount = remember(content) { countMergeConflicts(content) }
    val effectiveReadOnly = readOnly || isReadOnlyCell || conflictCount > 0
    var beautifyCell by remember(content) { mutableStateOf(true) }

    val displayContent = remember(content, isReadOnlyCell, beautifyCell) {
        if (isReadOnlyCell && !beautifyCell) {
            runCatching {
                val parsed = Json.parseToJsonElement(content)
                Json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), parsed)
            }.getOrDefault(content.lines().joinToString(" ") { it.trim() })
        } else if (isReadOnlyCell && beautifyCell) {
            runCatching {
                val parsed = Json.parseToJsonElement(content)
                PrettyJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), parsed)
            }.getOrDefault(content)
        } else {
            content
        }
    }

    val crumbs = remember(filePath) { buildPhoneEditorBreadcrumbs(filePath) }
    var overflowOpen by remember { mutableStateOf(false) }
    val overflowActions = remember(onReloadFromDisk, onDownload, onCopyPath) {
        PhoneEditorOverflowAction.phoneMenu().filter { action ->
            when (action) {
                PhoneEditorOverflowAction.WORD_WRAP -> true
                PhoneEditorOverflowAction.RELOAD_FROM_DISK -> onReloadFromDisk != null
                PhoneEditorOverflowAction.DOWNLOAD -> onDownload != null
                PhoneEditorOverflowAction.COPY_PATH -> onCopyPath != null
            }
        }
    }

    val saveState: PhoneEditorSaveState = remember(
        dirty,
        saving,
        saveError,
        changedOnDisk,
        languageLabel,
        cursorLine,
        cursorColumn,
    ) {
        when {
            saveError != null -> PhoneEditorSaveState.Failed(message = "Failed to save file", detail = saveError)
            saving -> PhoneEditorSaveState.Saving
            dirty -> PhoneEditorSaveState.Edited(changedOnDisk = changedOnDisk)
            else -> PhoneEditorSaveState.Saved(
                languageLabel = languageLabel,
                line = cursorLine,
                column = cursorColumn,
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.panel2.color)
            .testTag("phone-editor-screen"),
    ) {
        // File loading / error states (Section 2 — Editor states)
        if (loading) {
            if (crumbs.isNotEmpty()) {
                PhoneTreeBreadcrumbBar(crumbs = crumbs, onCrumbClick = onBreadcrumbSegmentClick)
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 10.dp)
                    .testTag("phone-editor-loading"),
            ) {
                PhoneTreePlaceholderRow(depth = 0, widthDp = 200)
                PhoneTreePlaceholderRow(depth = 0, widthDp = 150)
                PhoneTreePlaceholderRow(depth = 0, widthDp = 240)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PpmIcon(ProductIcon.Loader2, 14.dp, tokens.text2.color, spinning = true)
                    BasicText(
                        text = "Loading file...",
                        style = ppmText(13.5f, lineHeight = 18f).copy(color = tokens.text2.color),
                    )
                }
            }
            return@Column
        }

        if (loadError != null) {
            PhoneFilesEmptyBlock(
                icon = ProductIcon.FileWarning,
                isError = true,
                title = "Failed to load file",
                monoDetail = loadError,
                secondaryLabel = onCloseTab?.let { "Close tab" },
                onSecondary = onCloseTab,
                retryLabel = "Retry",
                onRetry = onRetryLoad,
                testTag = "phone-editor-load-error",
            )
            return@Column
        }

        // Breadcrumb row (`t-ed-crumb`, Note 11) when reading a project file
        if (!typing && !isReadOnlyCell && crumbs.isNotEmpty()) {
            PhoneTreeBreadcrumbBar(
                crumbs = crumbs,
                onCrumbClick = onBreadcrumbSegmentClick,
                onMore = { overflowOpen = true },
                moreLabel = "File actions",
            )
        }

        // Offline banner (`Editor — states: Offline`)
        if (offlineSeconds != null) {
            PhoneFilesBanner(
                icon = ProductIcon.WifiOff,
                title = "Offline for $offlineSeconds s",
                subtitle = "Unsaved edits stay here and save when you're back.",
                isError = true,
                actionLabel = "Retry",
                onAction = onSave,
                modifier = Modifier.testTag("phone-editor-offline-banner"),
            )
        }

        // Merge conflict banner (`Editor — states: Merge conflict`, Note 70)
        if (conflictCount > 0) {
            PhoneFilesBanner(
                icon = ProductIcon.GitMerge,
                title = formatMergeConflictBannerTitle(conflictCount),
                subtitle = MERGE_CONFLICT_RESOLVE_SUBTITLE,
                isError = false,
                actionLabel = if (onResolveConflicts != null) "Resolve" else null,
                onAction = onResolveConflicts,
                modifier = Modifier.testTag("phone-editor-conflict-banner"),
            )
        }

        // Changed on disk while dirty (`E9`, Note 23)
        if (changedOnDisk && dirty) {
            PhoneChangedOnDiskBanner(
                subtitle = changedOnDiskSubtitle,
                onDiscardAndReload = onDiscardAndReload,
                onOverwriteDisk = onOverwriteDisk,
            )
        }

        // Failed save banner (`Editor — states: Save button failed`, Note 27)
        if (saveState is PhoneEditorSaveState.Failed) {
            PhoneFilesBanner(
                icon = ProductIcon.AlertCircle,
                title = saveState.message,
                subtitle = saveState.detail,
                isError = true,
                actionLabel = "Retry Save",
                onAction = onSave,
                modifier = Modifier.testTag("phone-editor-save-error-banner"),
            )
        }

        // Main code area (`12.5 dp Monaspace Argon, line height 1.6, gutter 40 dp`)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(
                    if (effectiveReadOnly) {
                        tokens.text.opacity(0.025).over(tokens.panel2).color
                    } else {
                        tokens.panel2.color
                    },
                ),
        ) {
            if (editorBodyContent != null) {
                editorBodyContent()
            } else {
                PhoneCodeLinesView(
                    content = displayContent,
                    cursorLine = cursorLine,
                    wordWrap = wordWrap,
                    readOnly = effectiveReadOnly,
                )
            }

            if (!selectedText.isNullOrEmpty()) {
                PhoneEditorSelectionToolbar(
                    readOnly = effectiveReadOnly,
                    onAction = onSelectionAction,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
        }

        // Bottom area:
        // - E2: While typing in an editable file, the bottom card steps aside and the key row rides the keyboard (Note 16).
        // - E11: Read-only cell shows Beautify / Raw + Copy (Note 25).
        // - E1 / E9: Reading / edited state shows the bottom Save card (Note 14 & 67).
        when {
            typing && !effectiveReadOnly -> {
                PhoneEditorKeyRow(
                    wordWrap = wordWrap,
                    dirty = dirty,
                    onKeyPress = { key ->
                        if (key.id == "wrap") {
                            onToggleWordWrap()
                        } else {
                            onKeyPress(key)
                        }
                    },
                    onSave = onSave,
                )
            }
            isReadOnlyCell -> {
                BottomActionCard(
                    modifier = Modifier.testTag("phone-editor-readonly-card"),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        PhoneSegmentedControl(
                            options = listOf("Beautify" to true, "Raw" to false),
                            selected = beautifyCell,
                            onSelect = { beautifyCell = it },
                            modifier = Modifier.testTag("phone-editor-beautify-toggle"),
                        )
                        Chip(
                            label = "Copy",
                            icon = ProductIcon.Copy,
                            onClick = {
                                copyTextToClipboard(context, "Cell content", displayContent)
                            },
                            modifier = Modifier.testTag("phone-editor-copy-cell"),
                        )
                    }
                }
            }
            !effectiveReadOnly -> {
                PhoneEditorBottomSaveCard(
                    saveState = saveState,
                    onSave = onSave,
                )
            }
        }
    }
    if (overflowOpen) {
        PhoneEditorOverflowSheet(
            fileName = filePath.substringAfterLast('/').ifBlank { filePath },
            wordWrap = wordWrap,
            actions = overflowActions,
            onDismiss = { overflowOpen = false },
            onAction = { action ->
                overflowOpen = false
                when (action) {
                    PhoneEditorOverflowAction.WORD_WRAP -> onToggleWordWrap()
                    PhoneEditorOverflowAction.RELOAD_FROM_DISK -> onReloadFromDisk?.invoke()
                    PhoneEditorOverflowAction.DOWNLOAD -> onDownload?.invoke()
                    PhoneEditorOverflowAction.COPY_PATH -> onCopyPath?.invoke()
                }
            },
        )
    }
    }
}

/** What the changed-on-disk banner says when the caller knows no more about the change. */
internal const val CHANGED_ON_DISK_SUBTITLE = "Discard & Reload loses your edits; Overwrite Disk replaces the file with them."

/**
 * Native 12.5 dp Monaspace code lines view with 40 dp line-number gutter (`code3`, Section 8).
 */
@Composable
internal fun PhoneCodeLinesView(
    content: String,
    cursorLine: Int,
    wordWrap: Boolean,
    readOnly: Boolean,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val lines = remember(content) { content.replace("\r\n", "\n").split('\n') }
    val listState = rememberLazyListState()
    val horizontalScroll = rememberScrollState()

    val codeContainerModifier = if (wordWrap) {
        modifier.fillMaxSize()
    } else {
        modifier.fillMaxSize().horizontalScroll(horizontalScroll)
    }

    LazyColumn(
        state = listState,
        modifier = codeContainerModifier.testTag("phone-editor-code-lines"),
        contentPadding = PaddingValues(vertical = 6.dp),
    ) {
        items(lines.size) { index ->
            val lineNum = index + 1
            val isCursorLine = !readOnly && lineNum == cursorLine
            val rowBg = if (isCursorLine) tokens.accent.opacity(0.06).color else Color.Transparent

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(rowBg)
                    .padding(end = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                BasicText(
                    text = lineNum.toString(),
                    modifier = Modifier
                        .width(40.dp)
                        .padding(end = 12.dp),
                    style = ppmText(11.5f, FontWeight.Normal, FontKind.MONO, lineHeight = 20f).copy(
                        color = if (isCursorLine) tokens.text.color else tokens.text3.color,
                        textAlign = TextAlign.End,
                    ),
                    maxLines = 1,
                )
                BasicText(
                    text = lines[index],
                    modifier = Modifier.weight(1f, fill = wordWrap),
                    style = ppmText(12.5f, FontWeight.Normal, FontKind.MONO, lineHeight = 20f)
                        .copy(color = tokens.text.color),
                    softWrap = wordWrap,
                )
            }
        }
    }
}

/**
 * Changed-on-disk warning banner + `Discard & Reload` and `Overwrite Disk` buttons (`Phone 3 · E9`, Note 23).
 */
@Composable
fun PhoneChangedOnDiskBanner(
    subtitle: String = CHANGED_ON_DISK_SUBTITLE,
    onDiscardAndReload: () -> Unit,
    onOverwriteDisk: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    Column(modifier = modifier.fillMaxWidth().testTag("phone-editor-changed-on-disk")) {
        PhoneFilesBanner(
            icon = ProductIcon.TriangleAlert,
            title = "File changed on disk while you have unsaved edits.",
            subtitle = subtitle,
            isError = false,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val btnShape = RoundedCornerShape(12.dp)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(btnShape)
                    .background(tokens.panel2.color, btnShape)
                    .cssBorder(tokens.border.color, btnShape, width = 1.dp)
                    .ppmClickable(
                        shape = btnShape,
                        onClickLabel = "Discard & Reload",
                        onClick = onDiscardAndReload,
                    )
                    .padding(horizontal = 12.dp)
                    .testTag("phone-editor-discard-reload"),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PpmIcon(ProductIcon.RefreshCw, 16.dp, tokens.text.color)
                BasicText(
                    text = "Discard & Reload",
                    style = ppmText(13f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
                    maxLines = 1,
                )
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(btnShape)
                    .background(tokens.panel2.color, btnShape)
                    .cssBorder(tokens.border.color, btnShape, width = 1.dp)
                    .ppmClickable(
                        shape = btnShape,
                        onClickLabel = "Overwrite Disk",
                        onClick = onOverwriteDisk,
                    )
                    .padding(horizontal = 12.dp)
                    .testTag("phone-editor-overwrite-disk"),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PpmIcon(ProductIcon.Save, 16.dp, tokens.text.color)
                BasicText(
                    text = "Overwrite Disk",
                    style = ppmText(13f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Bottom action card for the phone editor (`Phone 3 · E1 & E9`, Notes 14, 27, 67).
 *
 * Shows the current [PhoneEditorSaveState] (`Saved`, `Edited`, `Saving…`) and places the 48 dp
 * round Save button in the thumb corner where Send sits.
 */
@Composable
fun PhoneEditorBottomSaveCard(
    saveState: PhoneEditorSaveState,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    BottomActionCard(
        modifier = modifier.testTag("phone-editor-bottom-save-card"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                BasicText(
                    text = saveState.primaryLabel,
                    style = ppmText(14f, FontWeight.SemiBold, lineHeight = 18f)
                        .copy(color = tokens.text.color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subline = when (saveState) {
                    is PhoneEditorSaveState.Saved -> saveState.subline
                    is PhoneEditorSaveState.Edited -> saveState.subline
                    PhoneEditorSaveState.Saving -> null
                    is PhoneEditorSaveState.Failed -> saveState.detail
                }
                if (!subline.isNullOrBlank()) {
                    BasicText(
                        text = subline,
                        style = ppmText(12f, FontWeight.Normal, lineHeight = 16f)
                            .copy(color = tokens.text3.color),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            val isActiveSave = saveState.saveButtonEnabled
            val isSaving = saveState is PhoneEditorSaveState.Saving
            val buttonBg = if (isActiveSave || isSaving) tokens.accent.color else tokens.surfaceHover.color
            val buttonFg = if (isActiveSave || isSaving) tokens.accentFg.color else tokens.text3.color
            val ariaLabel = when (saveState) {
                is PhoneEditorSaveState.Saved -> "Saved — nothing to save"
                is PhoneEditorSaveState.Edited -> "Save"
                PhoneEditorSaveState.Saving -> "Saving"
                is PhoneEditorSaveState.Failed -> "Save"
            }

            Box(
                modifier = Modifier
                    .size(KitSpec.ActionPrimaryHeight)
                    .clip(CircleShape)
                    .background(buttonBg, CircleShape)
                    .semantics { contentDescription = ariaLabel }
                    .ppmClickable(
                        enabled = isActiveSave,
                        shape = CircleShape,
                        onClickLabel = ariaLabel,
                        onClick = onSave,
                    )
                    .testTag("phone-editor-save-button"),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    isSaving -> PpmIcon(ProductIcon.Loader2, 20.dp, buttonFg, spinning = true)
                    isActiveSave -> PpmIcon(ProductIcon.Save, 20.dp, buttonFg)
                    else -> PpmIcon(ProductIcon.Check, 20.dp, buttonFg)
                }
            }
        }
    }
}

/**
 * 48 dp phone editor key row (`Phone 3 · E2`, `keys2 pin3`, `t-ekeys-p`, Note 16):
 * scrollable editor keys (`Paste`, `Undo`, `Redo`, `Wrap`, `Tab`, and symbols) with `Save`
 * pinned at the right end while [dirty] is true.
 */
@Composable
fun PhoneEditorKeyRow(
    wordWrap: Boolean,
    dirty: Boolean,
    onKeyPress: (PhoneEditorKey) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    keys: List<PhoneEditorKey> = PhoneEditorKeys,
) {
    val tokens = LocalPpmTokens.current
    val keyShape = RoundedCornerShape(KitSpec.KeyButtonRadius)
    val scroll = rememberScrollState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(KitSpec.KeyRowHeight)
            .background(tokens.panel2.color)
            .cssBorder(tokens.border.color, width = 1.dp, sides = CssSides.Top)
            .padding(horizontal = 8.dp)
            .testTag("phone-editor-key-row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(scroll),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (key in keys) {
                val isLit = key.id == "wrap" && wordWrap
                val bgColor = if (isLit) tokens.accentWash.color else tokens.panel.color
                val borderColor = if (isLit) tokens.accentWashBorder.color else tokens.border.color
                val fgColor = if (isLit) tokens.accent.color else tokens.text.color
                val minWidth = if (key.isSymbol) 36.dp else KitSpec.KeyButtonMinWidth

                Box(
                    modifier = Modifier
                        .height(KitSpec.KeyButtonHeight)
                        .widthIn(min = minWidth)
                        .clip(keyShape)
                        .background(bgColor, keyShape)
                        .cssBorder(borderColor, keyShape, width = 1.dp)
                        .semantics { contentDescription = key.label }
                        .ppmClickable(
                            shape = keyShape,
                            onClickLabel = key.label,
                            onClick = { onKeyPress(key) },
                        )
                        .padding(horizontal = if (key.isSymbol) 6.dp else 10.dp)
                        .testTag("phone-editor-key-${key.id}"),
                    contentAlignment = Alignment.Center,
                ) {
                    val icon = when (key.id) {
                        "paste" -> ProductIcon.ClipboardPaste
                        "undo" -> ProductIcon.Undo2
                        "redo" -> ProductIcon.Redo2
                        "wrap" -> ProductIcon.WrapText
                        else -> null
                    }
                    if (icon != null) {
                        PpmIcon(icon, 18.dp, fgColor)
                    } else {
                        BasicText(
                            text = key.label,
                            style = ppmText(13.5f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 16f)
                                .copy(color = fgColor),
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        if (dirty) {
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(24.dp)
                    .background(tokens.border.color),
            )
            Box(
                modifier = Modifier
                    .height(KitSpec.KeyButtonHeight)
                    .widthIn(min = KitSpec.KeyButtonMinWidth)
                    .clip(keyShape)
                    .background(tokens.accent.color, keyShape)
                    .semantics { contentDescription = "Save" }
                    .ppmClickable(
                        shape = keyShape,
                        onClickLabel = "Save",
                        onClick = onSave,
                    )
                    .padding(horizontal = 10.dp)
                    .testTag("phone-editor-key-save"),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.Save, 18.dp, tokens.accentFg.color)
            }
        }
    }
}

/**
 * Floating text-selection toolbar (`seltb3` in `wave3.css`, Note 68):
 * `Cut`, `Copy`, `Paste`, `Select all`, and `"Add to chat"`.
 */
@Composable
fun PhoneEditorSelectionToolbar(
    readOnly: Boolean,
    onAction: (PhoneSelectionAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val actions = remember(readOnly) { PhoneSelectionAction.actionsFor(readOnly = readOnly) }
    val barShape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .ppmShadow(shadow = tokens.shadowFloat, shape = barShape)
            .clip(barShape)
            .background(Color(0xFF1F2328), barShape)
            .padding(4.dp)
            .testTag("phone-editor-selection-toolbar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (action in actions) {
            val itemShape = RoundedCornerShape(10.dp)
            val textColor = if (action.isAiAction) Color(0xFFB9C6FF) else Color.White
            Row(
                modifier = Modifier
                    .height(44.dp)
                    .clip(itemShape)
                    .ppmClickable(
                        shape = itemShape,
                        onClickLabel = action.label,
                        onClick = { onAction(action) },
                    )
                    .padding(horizontal = 10.dp)
                    .testTag("phone-editor-selection-${action.id}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (action.isAiAction) {
                    PpmIcon(ProductIcon.MessageCircle, 16.dp, textColor)
                }
                BasicText(
                    text = action.label,
                    style = ppmText(13f, FontWeight.Medium, lineHeight = 16f).copy(color = textColor),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Phone editor `⋮` overflow sheet (`Phone 3 · Section 2`, Note 69):
 * keeps exactly 4 rows (`Word wrap`, `Reload from disk`, `Download`, `Copy Path`).
 */
@Composable
fun PhoneEditorOverflowSheet(
    fileName: String,
    wordWrap: Boolean,
    onDismiss: () -> Unit,
    onAction: (PhoneEditorOverflowAction) -> Unit,
    modifier: Modifier = Modifier,
    actions: List<PhoneEditorOverflowAction> = PhoneEditorOverflowAction.phoneMenu(),
) {
    val tokens = LocalPpmTokens.current
    Sheet(
        title = fileName,
        onDismiss = onDismiss,
        forceBottomSheet = true,
        modifier = modifier.testTag("phone-editor-overflow-sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
        ) {
            for (action in actions) {
                val icon = when (action) {
                    PhoneEditorOverflowAction.WORD_WRAP -> ProductIcon.WrapText
                    PhoneEditorOverflowAction.RELOAD_FROM_DISK -> ProductIcon.RefreshCw
                    PhoneEditorOverflowAction.DOWNLOAD -> ProductIcon.Download
                    PhoneEditorOverflowAction.COPY_PATH -> ProductIcon.Link
                }
                PhoneSheetActionRow(
                    icon = icon,
                    label = action.label,
                    onClick = { onAction(action) },
                    testTag = "phone-editor-overflow-${action.id}",
                    trailingContent = if (action == PhoneEditorOverflowAction.WORD_WRAP) {
                        {
                            val pillShape = RoundedCornerShape(999.dp)
                            Box(
                                modifier = Modifier
                                    .width(44.dp)
                                    .height(26.dp)
                                    .clip(pillShape)
                                    .background(
                                        if (wordWrap) tokens.accent.color else tokens.border.color,
                                        pillShape,
                                    )
                                    .padding(3.dp),
                                contentAlignment = if (wordWrap) Alignment.CenterEnd else Alignment.CenterStart,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .background(Color.White, CircleShape),
                                )
                            }
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/**
 * Sibling picker bottom sheet opened when tapping a segment in the editor's breadcrumb row
 * (`Phone 3 · E1`, Note 11: folders first, `"Loading…"` while fetching).
 */
@Composable
fun PhoneBreadcrumbSiblingsSheet(
    segmentLabel: String,
    entries: List<FileDirectoryEntry>?,
    activeEntryName: String?,
    onSelectEntry: (FileDirectoryEntry) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val sorted = remember(entries) {
        entries?.sortedWith(compareBy<FileDirectoryEntry> { !it.isDirectory }.thenBy { it.name })
    }
    Sheet(
        title = segmentLabel,
        onDismiss = onDismiss,
        forceBottomSheet = true,
        modifier = modifier.testTag("phone-breadcrumb-siblings-sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
        ) {
            when {
                sorted == null -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PpmIcon(ProductIcon.Loader2, 16.dp, tokens.text2.color, spinning = true)
                        BasicText(
                            text = "Loading…",
                            style = ppmText(14f, lineHeight = 18f).copy(color = tokens.text2.color),
                        )
                    }
                }
                sorted.isEmpty() -> {
                    BasicText(
                        text = "Empty",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                        style = ppmText(14f, lineHeight = 18f).copy(color = tokens.text3.color),
                    )
                }
                else -> {
                    for (entry in sorted) {
                        val isActive = entry.name == activeEntryName
                        PhoneSheetActionRow(
                            icon = if (entry.isDirectory) ProductIcon.Folder else ProductIcon.File,
                            label = entry.name,
                            onClick = { onSelectEntry(entry) },
                            trailingContent = if (isActive) {
                                { PpmIcon(ProductIcon.Check, 16.dp, tokens.accent.color) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Reusable segmented control (`segm3` in `wave3.css`).
 */
@Composable
internal fun <T> PhoneSegmentedControl(
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    icons: Map<T, ProductIcon> = emptyMap(),
    fillWidth: Boolean = false,
) {
    val tokens = LocalPpmTokens.current
    val outerShape = RoundedCornerShape(12.dp)
    val innerShape = RoundedCornerShape(9.dp)

    Row(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .clip(outerShape)
            .background(tokens.surfaceHover.color, outerShape)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((label, value) in options) {
            val isOn = value == selected
            val icon = icons[value]
            Row(
                modifier = Modifier
                    .then(if (fillWidth) Modifier.weight(1f) else Modifier)
                    .height(36.dp)
                    .clip(innerShape)
                    .background(if (isOn) tokens.panel2.color else Color.Transparent, innerShape)
                    .ppmClickable(
                        shape = innerShape,
                        onClickLabel = label,
                        onClick = { onSelect(value) },
                    )
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                if (icon != null) {
                    PpmIcon(
                        icon = icon,
                        size = 16.dp,
                        tint = if (isOn) tokens.text.color else tokens.text2.color,
                    )
                }
                BasicText(
                    text = label,
                    style = ppmText(13f, FontWeight.Medium, lineHeight = 16f).copy(
                        color = if (isOn) tokens.text.color else tokens.text2.color,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}
