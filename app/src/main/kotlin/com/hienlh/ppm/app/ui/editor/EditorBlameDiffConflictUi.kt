package com.hienlh.ppm.app.ui.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.client.gitBlame
import com.hienlh.ppm.client.gitCommitLine
import com.hienlh.ppm.client.readProjectFile
import com.hienlh.ppm.client.writeFile
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.vector
import com.hienlh.ppm.state.ReviewBlocks
import com.hienlh.ppm.state.editor.BlameCommitInfo
import com.hienlh.ppm.state.editor.BlameLineDetailState
import com.hienlh.ppm.state.editor.BlameResultState
import com.hienlh.ppm.state.editor.BoundedCommitLineCache
import com.hienlh.ppm.state.editor.CompareCandidate
import com.hienlh.ppm.state.editor.CompareSelection
import com.hienlh.ppm.state.editor.CompareStoreState
import com.hienlh.ppm.state.editor.ConflictRegion
import com.hienlh.ppm.state.editor.ConflictResolutionAction
import com.hienlh.ppm.state.editor.EditorSessionState
import com.hienlh.ppm.state.editor.SideBySideDiffRow
import com.hienlh.ppm.state.editor.authorColourHex
import com.hienlh.ppm.state.editor.authorInitials
import com.hienlh.ppm.state.editor.conflictEditorLanguage
import com.hienlh.ppm.state.editor.formatAbsoluteTime
import com.hienlh.ppm.state.editor.formatBlameAnnotation
import com.hienlh.ppm.state.editor.formatRelativeTime
import com.hienlh.ppm.state.editor.isUncommittedHash
import com.hienlh.ppm.state.editor.parseBlameLineDetail
import com.hienlh.ppm.state.editor.parseBlameResult
import com.hienlh.ppm.state.editor.parseConflicts
import com.hienlh.ppm.state.editor.resolveConflictRegion
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ── 7d.1 Inline Blame Toggle, Annotation & Hover Card ──────────────────────

object SharedBlamePreference {
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    val commitLineCache = BoundedCommitLineCache(maxSize = 200)

    fun setEnabled(value: Boolean) {
        _enabled.value = value
    }
}

@Composable
fun InlineBlameToolbarChip(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (enabled) tokens.accent.opacity(0.16).color else Color.Transparent)
            .ppmClickable(
                pressedColor = tokens.panel.color,
                hoverColor = tokens.panel.color,
                onClickLabel = "Toggle inline blame (Alt+B)",
                onClick = { onToggle(!enabled) },
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .testTag("editor-blame-toggle"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PpmIcon(
            ProductIcon.Users.vector,
            12.dp,
            if (enabled) tokens.accent.color else tokens.text3.color,
        )
        BasicText(
            "Blame",
            style = ppmText(11f, lineHeight = 14f).copy(
                color = if (enabled) tokens.accent.color else tokens.text3.color,
            ),
        )
    }
}

@Composable
fun InlineBlameAnnotationStrip(
    commit: BlameCommitInfo,
    detail: BlameLineDetailState?,
    filePath: String,
    projectPath: String?,
    onActionCommand: (String, List<String>) -> Unit = { _, _ -> },
) {
    val tokens = LocalPpmTokens.current
    val context = LocalContext.current
    var hoverExpanded by remember(commit.hash) { mutableStateOf(false) }
    val annotation = remember(commit) { formatBlameAnnotation(commit) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, sides = CssSides.Bottom),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !isUncommittedHash(commit.hash)) {
                    hoverExpanded = !hoverExpanded
                }
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .testTag("inline-blame-strip"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                annotation,
                modifier = Modifier.weight(1f),
                style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(
                    color = tokens.text3.color,
                    fontStyle = FontStyle.Italic,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!isUncommittedHash(commit.hash)) {
                BasicText(
                    if (hoverExpanded) "Hide details" else "Details",
                    style = ppmText(10f, FontWeight.Medium, lineHeight = 14f).copy(color = tokens.accent.color),
                )
            }
        }

        if (hoverExpanded && !isUncommittedHash(commit.hash)) {
            BlameHoverCard(
                commit = commit,
                detail = detail,
                filePath = filePath,
                projectPath = projectPath,
                onCopySha = { sha ->
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("Commit SHA", sha))
                    onActionCommand("ppm.blame.copySha", listOf(sha))
                },
                onActionCommand = onActionCommand,
            )
        }
    }
}

@Composable
internal fun BlameHoverCard(
    commit: BlameCommitInfo,
    detail: BlameLineDetailState?,
    filePath: String,
    projectPath: String?,
    nowMs: Long = System.currentTimeMillis(),
    onCopySha: (String) -> Unit = {},
    onActionCommand: (String, List<String>) -> Unit = { _, _ -> },
) {
    val tokens = LocalPpmTokens.current
    val author = commit.author.ifBlank { "Unknown" }
    val shortSha = commit.hash.take(7)
    val avatarColor = remember(author) {
        try {
            Color(android.graphics.Color.parseColor("#${authorColourHex(author)}"))
        } catch (_: Throwable) {
            tokens.accent.color
        }
    }
    val whenLabel = remember(commit.authorTime, nowMs) {
        if (commit.authorTime > 0L) {
            "${formatRelativeTime(commit.authorTime * 1000L, nowMs)} (${formatAbsoluteTime(commit.authorTime * 1000L)})"
        } else {
            ""
        }
    }
    val fullMessage = (detail?.message?.takeIf { it.isNotBlank() } ?: commit.summary).trim()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.bgSolid.color)
            .cssBorder(tokens.border.color, sides = CssSides.Top)
            .padding(12.dp)
            .testTag("blame-hover-card"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Row 1: Initials avatar + Author + Relative & Absolute Time
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(avatarColor),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    authorInitials(author),
                    style = ppmText(9f, FontWeight.Bold, lineHeight = 11f).copy(color = Color.White),
                )
            }
            BasicText(
                author,
                style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color),
            )
            if (whenLabel.isNotEmpty()) {
                BasicText(
                    whenLabel,
                    modifier = Modifier.testTag("blame-hover-time"),
                    style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color),
                )
            }
        }

        // Row 2: Commit message (verbatim, no HTML interpretation)
        if (fullMessage.isNotEmpty()) {
            BasicText(
                fullMessage,
                modifier = Modifier.testTag("blame-hover-message"),
                style = ppmText(12f, lineHeight = 17f).copy(color = tokens.text2.color),
            )
        }

        // Row 3: 4 Action buttons (`Copy SHA`, `File History`, `Blame`, `Graph`)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .cssBorder(tokens.border.color, sides = CssSides.Top)
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BlameActionChip(
                label = shortSha,
                tag = "blame-action-copy-sha",
                onClick = { onCopySha(commit.hash) },
            )
            if (!projectPath.isNullOrBlank()) {
                BlameActionChip(
                    label = "File History",
                    tag = "blame-action-file-history",
                    onClick = { onActionCommand("ppm.blame.fileHistory", listOf(projectPath, filePath)) },
                )
                BlameActionChip(
                    label = "Blame",
                    tag = "blame-action-blame-file",
                    onClick = { onActionCommand("ppm.blame.blameFile", listOf(projectPath, filePath)) },
                )
                BlameActionChip(
                    label = "Graph",
                    tag = "blame-action-show-graph",
                    onClick = { onActionCommand("ppm.blame.showInGraph", listOf(projectPath)) },
                )
            }
        }

        // Row 4: Line diff (`-removed` / `+added`)
        val diffLines = remember(detail) {
            buildList {
                detail?.removed?.forEach { add("-$it") }
                detail?.added?.forEach { add("+$it") }
            }
        }
        if (diffLines.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(tokens.panel.color)
                    .cssBorder(tokens.border.color)
                    .padding(8.dp)
                    .testTag("blame-hover-diff"),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (dLine in diffLines) {
                    val isRem = dLine.startsWith("-")
                    BasicText(
                        dLine,
                        style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(
                            color = if (isRem) tokens.error.color else tokens.success.color,
                        ),
                    )
                }
                val verb = if ((detail?.removed?.size ?: 0) == 0) "added in" else "in"
                BasicText(
                    "Changes $verb $shortSha",
                    modifier = Modifier.padding(top = 4.dp),
                    style = ppmText(10f, lineHeight = 14f).copy(color = tokens.text3.color),
                )
            }
        }
    }
}

@Composable
private fun BlameActionChip(
    label: String,
    tag: String,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag(tag),
    ) {
        BasicText(
            label,
            style = ppmText(11f, FontWeight.Medium, lineHeight = 14f).copy(color = tokens.accent.color),
        )
    }
}

// ── 7d.2 2-Column Side-by-Side Diff Rows (Tablet `git-diff`) ───────────────

@Composable
fun GitSideBySideDiffRows(
    rows: List<SideBySideDiffRow>,
    wordWrap: Boolean,
    language: String? = null,
    oldTokens: List<androidx.compose.ui.text.AnnotatedString>? = null,
    newTokens: List<androidx.compose.ui.text.AnnotatedString>? = null,
    modifier: Modifier = Modifier,
) {
    val computedOldTokens = oldTokens ?: if (language != null) {
        val leftDoc = remember(rows) { rows.mapNotNull { it.leftText }.joinToString("\n") }
        com.hienlh.ppm.app.ui.git.rememberShikiTokenLines(leftDoc, language)
    } else null
    val computedNewTokens = newTokens ?: if (language != null) {
        val rightDoc = remember(rows) { rows.mapNotNull { it.rightText }.joinToString("\n") }
        com.hienlh.ppm.app.ui.git.rememberShikiTokenLines(rightDoc, language)
    } else null

    Column(modifier = modifier.fillMaxWidth().testTag("git-diff-side-by-side-block")) {
        var leftNonNullIdx = 0
        var rightNonNullIdx = 0
        for (row in rows) {
            val leftTok = if (oldTokens != null) {
                row.leftLineNumber?.let { oldTokens.getOrNull(it - 1) }
            } else if (row.leftText != null) {
                computedOldTokens?.getOrNull(leftNonNullIdx++)
            } else null
            val rightTok = if (newTokens != null) {
                row.rightLineNumber?.let { newTokens.getOrNull(it - 1) }
            } else if (row.rightText != null) {
                computedNewTokens?.getOrNull(rightNonNullIdx++)
            } else null

            GitSideBySideDiffRowItem(
                row = row,
                wordWrap = wordWrap,
                leftTokens = leftTok,
                rightTokens = rightTok,
            )
        }
    }
}

@Composable
fun GitSideBySideDiffRowItem(
    row: SideBySideDiffRow,
    wordWrap: Boolean,
    leftTokens: androidx.compose.ui.text.AnnotatedString? = null,
    rightTokens: androidx.compose.ui.text.AnnotatedString? = null,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val addBg = tokens.success.opacity(0.14).color
    val delBg = tokens.error.opacity(0.14).color
    val emptyBg = tokens.panel.opacity(0.45).color

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        // Left column (Original)
        SideDiffCell(
            lineNumber = row.leftLineNumber,
            text = row.leftText,
            tokenLine = leftTokens,
            kind = row.leftKind,
            addBg = addBg,
            delBg = delBg,
            emptyBg = emptyBg,
            wordWrap = wordWrap,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .width(1.dp)
                .heightIn(min = 20.dp)
                .background(tokens.border.color),
        )
        // Right column (Modified)
        SideDiffCell(
            lineNumber = row.rightLineNumber,
            text = row.rightText,
            tokenLine = rightTokens,
            kind = row.rightKind,
            addBg = addBg,
            delBg = delBg,
            emptyBg = emptyBg,
            wordWrap = wordWrap,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SideDiffCell(
    lineNumber: Int?,
    text: String?,
    tokenLine: androidx.compose.ui.text.AnnotatedString?,
    kind: ReviewBlocks.Kind?,
    addBg: Color,
    delBg: Color,
    emptyBg: Color,
    wordWrap: Boolean,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val bg = when (kind) {
        ReviewBlocks.Kind.REMOVED -> delBg
        ReviewBlocks.Kind.ADDED -> addBg
        ReviewBlocks.Kind.CONTEXT -> Color.Transparent
        null -> emptyBg
    }
    val sign = when (kind) {
        ReviewBlocks.Kind.REMOVED -> "-"
        ReviewBlocks.Kind.ADDED -> "+"
        else -> " "
    }
    val scrollMod = if (wordWrap) Modifier else Modifier.horizontalScroll(rememberScrollState())
    val annotated = remember(text, tokenLine) {
        com.hienlh.ppm.app.ui.git.buildDiffLineAnnotated(
            text = text ?: "",
            tokens = tokenLine,
            changedRange = null,
            wordHighlight = Color.Transparent,
        )
    }

    Row(
        modifier = modifier
            .background(bg)
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.Top,
    ) {
        BasicText(
            text = lineNumber?.toString() ?: "",
            modifier = Modifier
                .width(38.dp)
                .padding(end = 6.dp),
            style = ppmText(11f, kind = FontKind.MONO, lineHeight = 18f).copy(
                color = tokens.text3.color,
                textAlign = TextAlign.End,
            ),
        )
        BasicText(
            text = sign,
            modifier = Modifier.width(14.dp),
            style = ppmText(11f, kind = FontKind.MONO, lineHeight = 18f).copy(
                color = when (kind) {
                    ReviewBlocks.Kind.REMOVED -> tokens.error.color
                    ReviewBlocks.Kind.ADDED -> tokens.success.color
                    else -> tokens.text3.color
                },
            ),
        )
        Box(modifier = Modifier.weight(1f).then(scrollMod)) {
            BasicText(
                text = annotated,
                style = ppmText(12f, kind = FontKind.MONO, lineHeight = 18f).copy(color = tokens.text.color),
                softWrap = wordWrap,
            )
        }
    }
}

// ── 7d.3 Conflict Editor Tab (`conflict-editor`) ───────────────────────────

@Composable
fun ConflictEditorTabBody(model: AppModel, tab: WorkspaceTab) {
    val api = model.api
    val filePath = tab.metaString("filePath").orEmpty()
    val projectName = tab.metaString("projectName")?.takeIf { it.isNotBlank() } ?: model.selectedProject
    val scope = rememberCoroutineScope()

    var content by remember(projectName, filePath) { mutableStateOf<String?>(null) }
    var loading by remember(projectName, filePath) { mutableStateOf(true) }
    var error by remember(projectName, filePath) { mutableStateOf<String?>(null) }
    var saveError by remember(projectName, filePath) { mutableStateOf<String?>(null) }
    var haltedOn4xx by remember(projectName, filePath) { mutableStateOf(false) }

    LaunchedEffect(api, projectName, filePath) {
        if (api == null || projectName.isNullOrBlank() || filePath.isBlank()) {
            error = "Missing project or file path"
            loading = false
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            val loaded = api.readProjectFile(projectName, filePath)
            content = loaded
            loading = false
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (err: Throwable) {
            error = err.message ?: "Failed to load file"
            loading = false
        }
    }

    suspend fun saveConflictToServer(newText: String) {
        content = newText
        val client = api ?: return
        val proj = projectName?.takeIf { it.isNotBlank() } ?: return
        if (filePath.isBlank()) return
        try {
            client.writeFile(proj, filePath, newText)
            saveError = null
            haltedOn4xx = false
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (err: Throwable) {
            val msg = err.message ?: "Failed to save $filePath"
            saveError = msg
            val is4xx = (err is com.hienlh.ppm.client.PpmError.Server && err.status in 400..499) ||
                err is com.hienlh.ppm.client.PpmError.Unauthorized ||
                err is IllegalArgumentException
            if (is4xx) {
                haltedOn4xx = true
            }
            model.toast(
                com.hienlh.ppm.app.ErrorToastMessage(
                    title = "Failed to save $filePath",
                    description = msg,
                ),
            )
            throw err
        }
    }

    val tokens = LocalPpmTokens.current
    if (loading) {
        Box(Modifier.fillMaxSize().background(tokens.bgSolid.color), contentAlignment = Alignment.Center) {
            BasicText("Loading conflict editor…", style = ppmText(13f, lineHeight = 18f).copy(color = tokens.text3.color))
        }
        return
    }
    if (error != null || content == null) {
        Box(Modifier.fillMaxSize().background(tokens.bgSolid.color), contentAlignment = Alignment.Center) {
            BasicText(error ?: "Unable to open file", style = ppmText(13f, lineHeight = 18f).copy(color = tokens.error.color))
        }
        return
    }

    ConflictEditorContent(
        fileName = filePath.substringAfterLast('/').substringAfterLast('\\').ifBlank { "unknown" },
        initialContent = content!!,
        saveError = saveError,
        haltedOn4xx = haltedOn4xx,
        onSaveSuspend = { newText -> saveConflictToServer(newText) },
        onSaveContent = { newText ->
            model.scope.launch {
                try {
                    saveConflictToServer(newText)
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    // Error state and toast already recorded by saveConflictToServer
                }
            }
        },
    )
}

@Composable
internal fun ConflictEditorContent(
    fileName: String,
    initialContent: String,
    saveError: String? = null,
    haltedOn4xx: Boolean = false,
    onSaveSuspend: (suspend (String) -> Unit)? = null,
    onSaveContent: (String) -> Unit = {},
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val scope = rememberCoroutineScope()
    val language = remember(fileName) { conflictEditorLanguage(fileName) }
    val session = remember(fileName) {
        EditorSessionState(initialText = initialContent, language = language)
    }
    var editTick by remember { mutableLongStateOf(0L) }
    var wrap by remember { mutableStateOf(mobile) }

    val currentText = remember(editTick) { session.buffer.toString() }
    val regions = remember(currentText) { parseConflicts(currentText) }
    var lastSavedDiskText by remember(fileName) { mutableStateOf(session.buffer.toDiskString()) }
    var localSaveError by remember(fileName) { mutableStateOf<String?>(null) }
    var localHaltedOn4xx by remember(fileName) { mutableStateOf(false) }

    suspend fun performSave(diskText: String) {
        try {
            if (onSaveSuspend != null) {
                onSaveSuspend(diskText)
            } else {
                onSaveContent(diskText)
            }
            lastSavedDiskText = diskText
            localSaveError = null
            localHaltedOn4xx = false
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (err: Throwable) {
            localSaveError = err.message ?: "Failed to save file"
            val is4xx = (err is com.hienlh.ppm.client.PpmError.Server && err.status in 400..499) ||
                err is com.hienlh.ppm.client.PpmError.Unauthorized ||
                err is IllegalArgumentException
            if (is4xx) {
                localHaltedOn4xx = true
            }
        }
    }

    // Autosave manual edits after 1000 ms (fixes web bug where manual typing in ConflictEditor is lost)
    LaunchedEffect(editTick, haltedOn4xx, localHaltedOn4xx) {
        if (editTick <= 0L || haltedOn4xx || localHaltedOn4xx) return@LaunchedEffect
        delay(1000L)
        if (haltedOn4xx || localHaltedOn4xx) return@LaunchedEffect
        val diskText = session.buffer.toDiskString()
        if (diskText != lastSavedDiskText) {
            performSave(diskText)
        }
    }

    // Flush unsaved manual edits when the conflict editor is disposed before the 1000 ms debounce completes
    androidx.compose.runtime.DisposableEffect(session) {
        onDispose {
            val diskText = session.buffer.toDiskString()
            if (diskText != lastSavedDiskText && !haltedOn4xx && !localHaltedOn4xx) {
                lastSavedDiskText = diskText
                onSaveContent(diskText)
            }
        }
    }

    fun applyResolution(region: ConflictRegion, action: ConflictResolutionAction) {
        val updated = resolveConflictRegion(session.buffer.toDiskString(), region.id, action)
        session.reloadExternalContent(updated)
        editTick++
        if (onSaveSuspend != null) {
            scope.launch { performSave(updated) }
        } else {
            lastSavedDiskText = updated
            onSaveContent(updated)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .testTag("conflict-editor"),
    ) {
        val currentSaveError = saveError ?: localSaveError
        if (!currentSaveError.isNullOrBlank()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(tokens.error.opacity(0.14).color)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("editor-save-error-banner"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    currentSaveError,
                    style = ppmText(11f).copy(color = tokens.error.color),
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(tokens.accent.color)
                        .ppmClickable { scope.launch { performSave(session.buffer.toDiskString()) } }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("conflict-editor-retry-save-btn"),
                ) {
                    BasicText(
                        "Retry Save",
                        style = ppmText(11f, FontWeight.Medium, lineHeight = 14f).copy(color = Color.White),
                    )
                }
            }
        }
        // Status header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(tokens.panel.color)
                .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText(
                fileName,
                style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color),
            )
            BasicText("—", style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color))
            if (regions.isNotEmpty()) {
                BasicText(
                    "${regions.size} conflict${if (regions.size == 1) "" else "s"} remaining",
                    modifier = Modifier.testTag("conflict-remaining-badge"),
                    style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.error.color),
                )
            } else {
                BasicText(
                    "All conflicts resolved",
                    modifier = Modifier.testTag("conflict-resolved-badge"),
                    style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.success.color),
                )
            }
        }

        // Conflict Region Action Cards
        if (regions.isNotEmpty() && mobile) {
            // A phone stacks each conflict's label over its three accept buttons, 44 dp tall and a third
            // of the width each, and scrolls the cards in at most a third of the screen so the file stays in view.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .background(tokens.panel.opacity(0.6).color)
                    .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("conflict-region-list-phone"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (region in regions) {
                    ConflictRegionPhoneCard(
                        region = region,
                        onResolve = { action -> applyResolution(region, action) },
                    )
                }
            }
        } else if (regions.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tokens.panel.opacity(0.6).color)
                    .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (region in regions) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(tokens.bgSolid.color)
                            .cssBorder(tokens.border.color)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("conflict-region-${region.id}"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            BasicText(
                                "Lines ${region.startLine}–${region.endLine} (${region.currentLabel.ifBlank { "HEAD" }} ↔ ${region.incomingLabel.ifBlank { "incoming" }})",
                                style = ppmText(11f, FontWeight.SemiBold, lineHeight = 15f).copy(color = tokens.text.color),
                            )
                            val baseText = region.baseContent
                            if (baseText != null) {
                                BasicText(
                                    "diff3 base (${region.baseLabel?.ifBlank { "base" } ?: "base"}): ${baseText.lines().size} line(s)",
                                    style = ppmText(10f, kind = FontKind.MONO, lineHeight = 14f).copy(color = tokens.text3.color),
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ConflictActionButton(
                                label = "Accept Current",
                                color = tokens.success.color,
                                tag = "conflict-accept-current-${region.id}",
                                onClick = { applyResolution(region, ConflictResolutionAction.CURRENT) },
                            )
                            ConflictActionButton(
                                label = "Accept Incoming",
                                color = tokens.accent.color,
                                tag = "conflict-accept-incoming-${region.id}",
                                onClick = { applyResolution(region, ConflictResolutionAction.INCOMING) },
                            )
                            ConflictActionButton(
                                label = "Accept Both",
                                color = tokens.warning.color,
                                tag = "conflict-accept-both-${region.id}",
                                onClick = { applyResolution(region, ConflictResolutionAction.BOTH) },
                            )
                        }
                    }
                }
            }
        }

        // Underlying editable NativeCodeEditorCanvas
        NativeCodeEditorCanvas(
            session = session,
            editTick = editTick,
            onMutated = { editTick++ },
            isDirty = false,
            wrap = wrap,
            onToggleWrap = { wrap = !wrap },
            onSaveNow = {
                val diskText = session.buffer.toDiskString()
                lastSavedDiskText = diskText
                onSaveContent(diskText)
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

/** One conflict on a phone: which lines and sides, then Accept Current / Incoming / Both side by side, 44 dp tall. */
@Composable
private fun ConflictRegionPhoneCard(
    region: ConflictRegion,
    onResolve: (ConflictResolutionAction) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(tokens.bgSolid.color)
            .cssBorder(tokens.border.color, RoundedCornerShape(10.dp))
            .padding(10.dp)
            .testTag("conflict-region-${region.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(
            "Lines ${region.startLine}–${region.endLine} (${region.currentLabel.ifBlank { "HEAD" }} ↔ ${region.incomingLabel.ifBlank { "incoming" }})",
            style = ppmText(13f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
        )
        val baseText = region.baseContent
        if (baseText != null) {
            BasicText(
                "diff3 base (${region.baseLabel?.ifBlank { "base" } ?: "base"}): ${baseText.lines().size} line(s)",
                style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text3.color),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ConflictActionButton(
                label = "Accept Current",
                color = tokens.success.color,
                tag = "conflict-accept-current-${region.id}",
                onClick = { onResolve(ConflictResolutionAction.CURRENT) },
                modifier = Modifier.weight(1f).heightIn(min = 44.dp),
            )
            ConflictActionButton(
                label = "Accept Incoming",
                color = tokens.accent.color,
                tag = "conflict-accept-incoming-${region.id}",
                onClick = { onResolve(ConflictResolutionAction.INCOMING) },
                modifier = Modifier.weight(1f).heightIn(min = 44.dp),
            )
            ConflictActionButton(
                label = "Accept Both",
                color = tokens.warning.color,
                tag = "conflict-accept-both-${region.id}",
                onClick = { onResolve(ConflictResolutionAction.BOTH) },
                modifier = Modifier.weight(1f).heightIn(min = 44.dp),
            )
        }
    }
}

@Composable
private fun ConflictActionButton(
    label: String,
    color: Color,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = ppmText(11f, FontWeight.Medium, lineHeight = 14f).copy(color = color, textAlign = TextAlign.Center),
        )
    }
}

// ── 7d.4 File Compare Picker Dialog Content (`compare-picker.tsx`) ─────────

object SharedCompareStore {
    val store = CompareStoreState()
}

@Composable
internal fun CompareFilesDialogContent(
    initialSelectionA: CompareSelection?,
    candidates: List<CompareCandidate>,
    onPickPair: (CompareSelection, CompareCandidate) -> Unit,
    onClose: () -> Unit = {},
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    var localA by remember(initialSelectionA) { mutableStateOf(initialSelectionA) }
    var query by remember { mutableStateOf("") }

    val filtered = remember(candidates, query) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) candidates.take(50)
        else candidates.filter {
            it.label.lowercase().contains(needle) || it.path.lowercase().contains(needle)
        }.take(50)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color)
            .testTag("compare-picker-dialog"),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PpmIcon(ProductIcon.FileCode.vector, 16.dp, tokens.text.color)
                BasicText(
                    "Compare Files",
                    style = ppmText(14f, FontWeight.SemiBold, lineHeight = 20f).copy(color = tokens.text.color),
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onClose)
                    .padding(4.dp)
                    .testTag("compare-picker-close"),
            ) {
                PpmIcon(ProductIcon.X.vector, 14.dp, tokens.text3.color)
            }
        }

        // Side A chip or instructions
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            val currentA = localA
            if (currentA != null) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(tokens.bgSolid.color)
                        .cssBorder(tokens.border.color)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("compare-side-a-chip"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PpmIcon(ProductIcon.FileCode.vector, 13.dp, tokens.text2.color)
                    BasicText(
                        currentA.label,
                        style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text.color),
                    )
                    Box(
                        modifier = Modifier
                            .clickable { localA = null }
                            .padding(2.dp)
                            .testTag("compare-clear-a"),
                    ) {
                        PpmIcon(ProductIcon.X.vector, 12.dp, tokens.text3.color)
                    }
                }
            } else {
                BasicText(
                    "Pick first file, then second.",
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
                )
            }
        }

        // Search input
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .cssBorder(tokens.border.color, sides = CssSides.Top + CssSides.Bottom)
                .background(tokens.bgSolid.color)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            if (query.isEmpty()) {
                BasicText(
                    if (localA != null) "Search for file B..." else "Search for file A...",
                    style = ppmText(13f, lineHeight = 18f).copy(color = tokens.text3.color),
                )
            }
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = ppmText(13f, lineHeight = 18f).copy(color = tokens.text.color),
                cursorBrush = SolidColor(tokens.accent.color),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("compare-search-input"),
            )
        }

        // Candidate list
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp),
        ) {
            if (filtered.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            if (candidates.isEmpty()) "No files available" else "No matches",
                            style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
                        )
                    }
                }
            } else {
                itemsIndexed(filtered, key = { _, c -> c.id }) { idx, candidate ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = if (mobile) 44.dp else 32.dp)
                            .clickable {
                                val currentA = localA
                                if (currentA == null) {
                                    localA = CompareSelection(
                                        filePath = candidate.path,
                                        projectName = "",
                                        dirtyContent = candidate.dirtyContent,
                                        label = candidate.label,
                                    )
                                    query = ""
                                } else {
                                    onPickPair(currentA, candidate)
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag("compare-candidate-$idx"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PpmIcon(ProductIcon.FileCode.vector, 14.dp, tokens.text2.color)
                        BasicText(
                            candidate.label,
                            style = ppmText(13f, lineHeight = 18f).copy(color = tokens.text.color),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.weight(1f))
                        BasicText(
                            if (candidate.source == "tab") "open" else candidate.path,
                            style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
