package com.hienlh.ppm.app.ui.git

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.client.FileFullDiff
import com.hienlh.ppm.client.GitRepo
import com.hienlh.ppm.client.filesCompare
import com.hienlh.ppm.client.gitDiff
import com.hienlh.ppm.client.gitFileBlob
import com.hienlh.ppm.client.gitFileFullDiff
import com.hienlh.ppm.client.rawFile
import com.hienlh.ppm.design.HighlightedCode
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTheme
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.LucideIcon
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.SyntaxHighlighter
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.state.ReviewBlocks
import com.hienlh.ppm.state.SessionReviewModel
import com.hienlh.ppm.state.git.GitLogRefBadges
import com.hienlh.ppm.state.git.FileGitScopeKind
import com.hienlh.ppm.state.git.GitRepoScope
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * 1-column unified diff tab (`git-diff` -> `src/web/components/editor/diff-viewer.tsx`).
 */
@Composable
fun GitDiffBody(model: AppModel, tab: WorkspaceTab) {
    GitDiffViewer(
        model = model,
        projectName = tab.metaString("projectName")?.takeIf { it.isNotEmpty() } ?: model.selectedProject,
        filePath = tab.metaString("filePath")?.takeIf { it.isNotEmpty() },
        ref1 = tab.metaString("ref1")?.takeIf { it.isNotEmpty() },
        ref2 = tab.metaString("ref2")?.takeIf { it.isNotEmpty() },
        file1 = tab.metaString("file1")?.takeIf { it.isNotEmpty() },
        file2 = tab.metaString("file2")?.takeIf { it.isNotEmpty() },
        inlineOriginal = tab.metaString("original"),
        inlineModified = tab.metaString("modified"),
    )
}

@Composable
fun GitDiffViewer(
    model: AppModel,
    projectName: String?,
    filePath: String? = null,
    ref1: String? = null,
    ref2: String? = null,
    file1: String? = null,
    file2: String? = null,
    inlineOriginal: String? = null,
    inlineModified: String? = null,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val api = model.api
    val isInline = inlineOriginal != null || inlineModified != null
    val isFileCompare = !file1.isNullOrEmpty() && !file2.isNullOrEmpty()

    var diffText by remember(projectName, filePath, ref1, ref2, file1, file2) { mutableStateOf<String?>(null) }
    var fileContents by remember(projectName, filePath, ref1, ref2, file1, file2) {
        mutableStateOf<Pair<String, String>?>(null)
    }
    var fullFileDiff by remember(projectName, filePath, ref1, ref2, file1, file2) {
        mutableStateOf<FileFullDiff?>(null)
    }
    var binaryMode by remember(filePath, ref1, ref2) { mutableStateOf("preview") }
    var openAsText by remember(filePath, ref1, ref2) { mutableStateOf(false) }
    var loading by remember(projectName, filePath, ref1, ref2, file1, file2, isInline) { mutableStateOf(!isInline) }
    var error by remember(projectName, filePath, ref1, ref2, file1, file2) { mutableStateOf<String?>(null) }
    var wordWrap by remember { mutableStateOf(mobile) }

    LaunchedEffect(projectName, api) {
        if (projectName != null && !isInline) {
            model.loadGitRepos(projectName)
        }
    }

    val discovery = projectName?.let { model.gitDiscovery[it] }
    val chosenPath = projectName?.let { model.gitChosenRepoPath[it] }
    val selectedRepo = remember(discovery, chosenPath) { GitRepoScope.resolveRepo(discovery, chosenPath) }

    val fileScope = remember(filePath, discovery) {
        if (filePath == null) null
        else GitRepoScope.fileGitScope(discovery, filePath)
    }

    LaunchedEffect(projectName, filePath, ref1, ref2, file1, file2, isInline, openAsText, discovery, selectedRepo?.path, api) {
        if (isInline) {
            loading = false
            return@LaunchedEffect
        }
        val client = api ?: return@LaunchedEffect
        val proj = projectName ?: return@LaunchedEffect
        loading = true
        error = null

        try {
            when {
                isFileCompare -> {
                    val cmp = client.filesCompare(proj, file1!!, file2!!)
                    fileContents = cmp
                    fullFileDiff = null
                    diffText = null
                }
                filePath != null -> {
                    val scope = GitRepoScope.fileGitScope(discovery, filePath)
                    if (scope == null) {
                        // Discovery has not finished yet
                        return@LaunchedEffect
                    }
                    if (scope.kind == FileGitScopeKind.NO_REPO) {
                        loading = false
                        return@LaunchedEffect
                    }
                    fullFileDiff = client.gitFileFullDiff(
                        project = proj,
                        file = scope.filePath,
                        ref = ref1 ?: "HEAD",
                        ref2 = ref2,
                        text = openAsText,
                        repo = scope.repo,
                    )
                    fileContents = null
                    diffText = null
                }
                else -> {
                    diffText = client.gitDiff(proj, ref1 = ref1, ref2 = ref2, repo = selectedRepo)
                    fullFileDiff = null
                    fileContents = null
                }
            }
            loading = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = model.describe(e)
            loading = false
        }
    }

    if (projectName == null && !isInline) {
        Box(
            modifier = modifier.fillMaxSize().padding(16.dp).testTag("git-diff-no-project"),
            contentAlignment = Alignment.Center,
        ) {
            BasicText("No project selected.", style = PpmFont.sans(13, lineHeight = 18, color = tokens.text3.color))
        }
        return
    }

    if (loading) {
        Box(
            modifier = modifier.fillMaxSize().padding(16.dp).testTag("git-diff-loading"),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PpmIcon(ProductIcon.Loader2, size = 18.dp, tint = tokens.text3.color)
                BasicText("Loading diff...", style = PpmFont.sans(13, lineHeight = 18, color = tokens.text3.color))
            }
        }
        return
    }

    error?.let { err ->
        Box(
            modifier = modifier.fillMaxSize().padding(16.dp).testTag("git-diff-error"),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(err, style = PpmFont.sans(13, lineHeight = 18, color = tokens.error.color))
        }
        return
    }

    val full = fullFileDiff
    val scopePair = if (fileScope != null && fileScope.kind == FileGitScopeKind.REPO) {
        fileScope.filePath to fileScope.repo
    } else {
        null
    }
    if (full != null && full.binary && !openAsText && filePath != null && projectName != null && scopePair != null) {
        val canPreview = remember(filePath) { GitLogRefBadges.canPreviewBinary(filePath) }
        Column(modifier = modifier.fillMaxSize().background(tokens.bgSolid.color).testTag("git-diff-binary")) {
            if (canPreview) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .edge(Edge.Bottom, tokens.border.color)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BinaryViewSwitcher(
                        mode = binaryMode,
                        onChange = { next ->
                            binaryMode = next
                            if (next == "preview") openAsText = false
                        },
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (binaryMode == "text" || !canPreview) {
                    BinaryPlaceholder(onOpenAnyway = { openAsText = true })
                } else {
                    BinaryImagePreview(
                        model = model,
                        projectName = projectName,
                        projectFilePath = filePath,
                        repoFilePath = scopePair.first,
                        repo = scopePair.second,
                        ref1 = ref1,
                        ref2 = ref2,
                        originalSize = full.originalSize,
                        modifiedSize = full.modifiedSize,
                        onOpenAnyway = { openAsText = true },
                    )
                }
            }
        }
        return
    }

    val (original, modified) = remember(isInline, inlineOriginal, inlineModified, isFileCompare, fileContents, fullFileDiff, diffText) {
        when {
            isInline -> (inlineOriginal ?: "") to (inlineModified ?: "")
            isFileCompare && fileContents != null -> fileContents!!
            fullFileDiff != null -> fullFileDiff!!.original to fullFileDiff!!.modified
            diffText != null -> GitLogRefBadges.parseDiff(diffText!!)
            else -> "" to ""
        }
    }

    if (!isInline && !isFileCompare && fullFileDiff == null && original.isEmpty() && modified.isEmpty()) {
        NoContentChanges(filePath = filePath, modifier = modifier)
        return
    }

    val diff = remember(original, modified) {
        ReviewBlocks.computeBlocks(original, modified)
    }

    if (diff != null && diff.blocks.isEmpty()) {
        NoContentChanges(filePath = filePath, modifier = modifier)
        return
    }

    val langPath = filePath ?: file2 ?: file1
    val language = remember(langPath) { gitDiffLanguage(langPath) }
    val canPreview = remember(filePath) { filePath != null && GitLogRefBadges.canPreviewBinary(filePath) }

    Column(modifier = modifier.fillMaxSize().background(tokens.bgSolid.color).testTag("git-diff-viewer")) {
        // Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .edge(Edge.Bottom, tokens.border.color)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (filePath != null) {
                TruncatedText(
                    text = filePath,
                    style = PpmFont.mono(11, lineHeight = 14, color = tokens.text2.color),
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (full?.binary == true && canPreview) {
                BinaryViewSwitcher(
                    mode = "text",
                    onChange = { next ->
                        binaryMode = next
                        if (next == "preview") openAsText = false
                    },
                )
            }
            val wrapBtnSize = if (mobile) 36.dp else 24.dp
            Box(
                modifier = Modifier
                    .size(wrapBtnSize)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (wordWrap) tokens.panel2.color else Color.Transparent)
                    .clickable { wordWrap = !wordWrap }
                    .testTag("git-diff-wrap-toggle"),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(
                    ProductIcon.WrapText,
                    size = 14.dp,
                    tint = if (wordWrap) tokens.text.color else tokens.text3.color,
                )
            }
        }

        // 2-column side-by-side diff on tablet (`!mobile`), 1-column unified diff on phone (`mobile`)
        val openedGaps = remember(original, modified) { mutableStateMapOf<Int, Boolean>() }
        val oldLines = remember(original) { ReviewBlocks.splitLines(original).map(::stripDiffEol) }
        val newLines = remember(modified) { ReviewBlocks.splitLines(modified).map(::stripDiffEol) }
        val oldFileTokens = rememberShikiTokenLines(oldLines.joinToString("\n"), language)
        val newFileTokens = if (modified == original) oldFileTokens else rememberShikiTokenLines(newLines.joinToString("\n"), language)

        if (mobile) {
            val entries = remember(diff, original, modified, openedGaps.toMap()) {
                buildUnifiedDiffEntries(diff, original, modified, newLines, openedGaps)
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("git-diff-rows"),
            ) {
                for (entry in entries) {
                    when (entry) {
                        is UnifiedDiffEntry.Gap -> {
                            item(key = entry.key) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(tokens.panel2.opacity(0.45).color)
                                        .edge(Edge.Top, tokens.border.opacity(0.5).color)
                                        .edge(Edge.Bottom, tokens.border.opacity(0.5).color)
                                        .clickable { openedGaps[entry.fromNew] = true }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                        .testTag("git-diff-gap-${entry.fromNew}"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    PpmIcon(ProductIcon.ChevronDown, size = 12.dp, tint = tokens.text3.color)
                                    BasicText(
                                        text = "${entry.count} unchanged ${if (entry.count == 1) "line" else "lines"}",
                                        style = PpmFont.sans(11, lineHeight = 14, color = tokens.text3.color),
                                    )
                                }
                            }
                        }
                        is UnifiedDiffEntry.Rows -> {
                            val wordSpans = SessionReviewModel.changedSpans(entry.rows)
                            items(
                                count = entry.rows.size,
                                key = { idx -> "${entry.key}:$idx" },
                                contentType = { "unified-diff-row" },
                            ) { idx ->
                                val row = entry.rows[idx]
                                val tokenLine = if (row.kind == ReviewBlocks.Kind.REMOVED) {
                                    row.o?.let { oldFileTokens?.getOrNull(it - 1) }
                                } else {
                                    row.n?.let { newFileTokens?.getOrNull(it - 1) }
                                }
                                GitUnifiedDiffSingleRow(
                                    row = row,
                                    tokenLine = tokenLine,
                                    changedRange = wordSpans.getOrNull(idx),
                                    wordWrap = wordWrap,
                                    compact = true,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            val sbsEntries = remember(diff, original, modified, openedGaps.toMap()) {
                com.hienlh.ppm.state.editor.buildSideBySideDiffEntries(
                    diff = diff,
                    original = original,
                    modified = modified,
                    openedGaps = openedGaps,
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("git-diff-rows"),
            ) {
                for (entry in sbsEntries) {
                    when (entry) {
                        is com.hienlh.ppm.state.editor.SideBySideDiffEntry.Gap -> {
                            item(key = entry.key) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(tokens.panel2.opacity(0.45).color)
                                        .edge(Edge.Top, tokens.border.opacity(0.5).color)
                                        .edge(Edge.Bottom, tokens.border.opacity(0.5).color)
                                        .clickable { openedGaps[entry.fromNew] = true }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                        .testTag("git-diff-gap-${entry.fromNew}"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    PpmIcon(ProductIcon.ChevronDown, size = 12.dp, tint = tokens.text3.color)
                                    BasicText(
                                        text = "${entry.count} unchanged ${if (entry.count == 1) "line" else "lines"}",
                                        style = PpmFont.sans(11, lineHeight = 14, color = tokens.text3.color),
                                    )
                                }
                            }
                        }
                        is com.hienlh.ppm.state.editor.SideBySideDiffEntry.Rows -> {
                            items(
                                count = entry.rows.size,
                                key = { idx -> "${entry.key}:$idx" },
                                contentType = { "sbs-diff-row" },
                            ) { idx ->
                                val row = entry.rows[idx]
                                val leftTok = row.leftLineNumber?.let { oldFileTokens?.getOrNull(it - 1) }
                                val rightTok = row.rightLineNumber?.let { newFileTokens?.getOrNull(it - 1) }
                                com.hienlh.ppm.app.ui.editor.GitSideBySideDiffRowItem(
                                    row = row,
                                    wordWrap = wordWrap,
                                    leftTokens = leftTok,
                                    rightTokens = rightTok,
                                    modifier = if (idx == 0) Modifier.testTag("git-diff-side-by-side-block") else Modifier,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private sealed interface UnifiedDiffEntry {
    val key: String

    data class Gap(
        val fromNew: Int,
        val count: Int,
    ) : UnifiedDiffEntry {
        override val key: String get() = "gap:$fromNew"
    }

    data class Rows(
        override val key: String,
        val rows: List<ReviewBlocks.Row>,
    ) : UnifiedDiffEntry
}

private fun buildUnifiedDiffEntries(
    diff: ReviewBlocks.Diff?,
    original: String,
    modified: String,
    newLines: List<String>,
    openedGaps: Map<Int, Boolean>,
): List<UnifiedDiffEntry> {
    if (diff == null) {
        val oldLines = ReviewBlocks.splitLines(original).map(::stripDiffEol)
        val rows = buildList {
            oldLines.forEachIndexed { idx, line ->
                add(ReviewBlocks.Row(ReviewBlocks.Kind.REMOVED, line, o = idx + 1, n = null))
            }
            newLines.forEachIndexed { idx, line ->
                add(ReviewBlocks.Row(ReviewBlocks.Kind.ADDED, line, o = null, n = idx + 1))
            }
        }
        return listOf(UnifiedDiffEntry.Rows("wholesale", rows))
    }

    val out = mutableListOf<UnifiedDiffEntry>()
    var curOld = 0
    var curNew = 0
    for (block in diff.blocks) {
        val gapCount = block.newFrom - curNew
        if (gapCount > 0) {
            if (openedGaps[curNew] == true) {
                val contextRows = (0 until gapCount).map { k ->
                    val oLine = curOld + k + 1
                    val nLine = curNew + k + 1
                    ReviewBlocks.Row(
                        kind = ReviewBlocks.Kind.CONTEXT,
                        text = newLines.getOrElse(curNew + k) { "" },
                        o = oLine,
                        n = nLine,
                    )
                }
                out += UnifiedDiffEntry.Rows("ctx:$curNew", contextRows)
            } else {
                out += UnifiedDiffEntry.Gap(fromNew = curNew, count = gapCount)
            }
        }
        out += UnifiedDiffEntry.Rows("block:${block.key}", block.rows)
        curOld = block.oldTo
        curNew = block.newTo
    }
    val tailCount = newLines.size - curNew
    if (tailCount > 0) {
        if (openedGaps[curNew] == true) {
            val tailRows = (0 until tailCount).map { k ->
                ReviewBlocks.Row(
                    kind = ReviewBlocks.Kind.CONTEXT,
                    text = newLines.getOrElse(curNew + k) { "" },
                    o = curOld + k + 1,
                    n = curNew + k + 1,
                )
            }
            out += UnifiedDiffEntry.Rows("ctx-tail:$curNew", tailRows)
        } else {
            out += UnifiedDiffEntry.Gap(fromNew = curNew, count = tailCount)
        }
    }
    return out
}

internal fun stripDiffEol(line: String): String = when {
    line.endsWith("\r\n") -> line.dropLast(2)
    line.endsWith("\n") -> line.dropLast(1)
    else -> line
}

@Composable
private fun NoContentChanges(filePath: String?, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp).testTag("git-diff-empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PpmIcon(ProductIcon.FileCode, size = 32.dp, tint = tokens.text3.color)
        Spacer(Modifier.height(8.dp))
        BasicText("No content changes", style = PpmFont.sans(13, lineHeight = 18, color = tokens.text2.color))
        if (!filePath.isNullOrEmpty()) {
            Spacer(Modifier.height(4.dp))
            BasicText(filePath, style = PpmFont.mono(11, lineHeight = 15, color = tokens.text3.color))
        }
    }
}

@Composable
internal fun BinaryViewSwitcher(
    mode: String,
    onChange: (String) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(4.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .cssBorder(tokens.border.color, shape)
            .testTag("binary-view-switcher"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val items = listOf("preview" to "Image Preview", "text" to "Text Editor")
        items.forEach { (id, label) ->
            val active = mode == id
            Box(
                modifier = Modifier
                    .background(if (active) tokens.panel2.color else Color.Transparent)
                    .clickable { onChange(id) }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("binary-mode-$id"),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = label,
                    style = PpmFont.sans(
                        11,
                        if (active) FontWeight.Medium else FontWeight.Normal,
                        lineHeight = 14,
                        color = if (active) tokens.text.color else tokens.text3.color,
                    ),
                )
            }
        }
    }
}

@Composable
internal fun BinaryPlaceholder(onOpenAnyway: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag("binary-placeholder"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PpmIcon(ProductIcon.AlertTriangle, size = 36.dp, tint = tokens.warning.color)
        Spacer(Modifier.height(12.dp))
        BasicText(
            text = "The file is not displayed in the text editor because it is either binary or uses an unsupported text encoding.",
            style = PpmFont.sans(13, lineHeight = 19, color = tokens.text2.color).copy(textAlign = TextAlign.Center),
            modifier = Modifier.widthIn(max = 420.dp),
        )
        Spacer(Modifier.height(16.dp))
        val btnShape = RoundedCornerShape(6.dp)
        Box(
            modifier = Modifier
                .height(36.dp)
                .clip(btnShape)
                .background(tokens.accent.color)
                .clickable(onClick = onOpenAnyway)
                .padding(horizontal = 18.dp)
                .testTag("binary-open-anyway"),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = "Open Anyway",
                style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.accentFg.color),
            )
        }
    }
}

@Composable
private fun BinaryImagePreview(
    model: AppModel,
    projectName: String,
    projectFilePath: String,
    repoFilePath: String,
    repo: GitRepo?,
    ref1: String?,
    ref2: String?,
    originalSize: Long?,
    modifiedSize: Long?,
    onOpenAnyway: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val api = model.api

    val hasOrig = originalSize != null
    val hasMod = modifiedSize != null
    if (!hasOrig && !hasMod) {
        BinaryPlaceholder(onOpenAnyway = onOpenAnyway)
        return
    }

    val origLabel = GitLogRefBadges.shortRef(ref1 ?: "HEAD")
    val modLabel = if (ref2 != null) GitLogRefBadges.shortRef(ref2) else "Working Tree"

    @Composable
    fun SidePane(label: String, size: Long?, isOriginal: Boolean, modifier: Modifier = Modifier) {
        var bitmap by remember(projectName, repoFilePath, repo?.path, ref1, ref2, isOriginal) { mutableStateOf<ImageBitmap?>(null) }
        var failed by remember(projectName, repoFilePath, repo?.path, ref1, ref2, isOriginal) { mutableStateOf(false) }

        LaunchedEffect(projectName, repoFilePath, projectFilePath, repo?.path, ref1, ref2, isOriginal, api) {
            val client = api ?: return@LaunchedEffect
            failed = false
            try {
                val bytes = if (isOriginal) {
                    client.gitFileBlob(projectName, repoFilePath, ref1 ?: "HEAD", repo).bytes
                } else if (ref2 != null) {
                    client.gitFileBlob(projectName, repoFilePath, ref2, repo).bytes
                } else {
                    client.rawFile(projectName, projectFilePath).bytes
                }
                val decoded = withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
                if (decoded != null) bitmap = decoded else failed = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
            }
        }

        Column(modifier = modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .edge(Edge.Bottom, tokens.border.color)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TruncatedText(
                    text = label,
                    style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.text2.color),
                    modifier = Modifier.weight(1f),
                )
                if (size != null) {
                    BasicText(
                        text = GitLogRefBadges.formatBytes(size),
                        style = PpmFont.sans(11, lineHeight = 14, color = tokens.text3.color),
                    )
                }
            }
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().background(tokens.panel.color).padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    failed -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            PpmIcon(ProductIcon.AlertTriangle, size = 24.dp, tint = tokens.text3.color)
                            Spacer(Modifier.height(6.dp))
                            BasicText("Failed to load.", style = PpmFont.sans(12, lineHeight = 16, color = tokens.text3.color))
                        }
                    }
                    bitmap == null -> {
                        PpmIcon(ProductIcon.Loader2, size = 20.dp, tint = tokens.text3.color)
                    }
                    else -> {
                        Image(
                            bitmap = bitmap!!,
                            contentDescription = label,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

    if (hasOrig && hasMod) {
        if (mobile) {
            Column(Modifier.fillMaxSize()) {
                SidePane(origLabel, originalSize, isOriginal = true, modifier = Modifier.weight(1f))
                Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.border.color))
                SidePane(modLabel, modifiedSize, isOriginal = false, modifier = Modifier.weight(1f))
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                SidePane(origLabel, originalSize, isOriginal = true, modifier = Modifier.weight(1f))
                Box(Modifier.fillMaxHeight().width(1.dp).background(tokens.border.color))
                SidePane(modLabel, modifiedSize, isOriginal = false, modifier = Modifier.weight(1f))
            }
        }
    } else if (hasOrig) {
        SidePane(origLabel, originalSize, isOriginal = true)
    } else {
        SidePane(modLabel, modifiedSize, isOriginal = false)
    }
}

/**
 * Shared 1-column unified diff rows renderer with Shiki syntax highlighting and word-level diff
 * spans (`SessionReviewModel.changedSpans`). Used by `GitDiffTab`, `BranchReviewTab`, and `GitReviewTab`.
 */
@Composable
internal fun GitUnifiedDiffRows(
    rows: List<ReviewBlocks.Row>,
    language: String?,
    wordWrap: Boolean = true,
    compact: Boolean = false,
    picking: Boolean = false,
    pickedIndices: Set<Int> = emptySet(),
    rowLineIndices: List<Int>? = null,
    onToggleLine: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val oldText = remember(rows) {
        rows.filter { it.kind != ReviewBlocks.Kind.ADDED }.joinToString("\n") { it.text }
    }
    val newText = remember(rows) {
        rows.filter { it.kind != ReviewBlocks.Kind.REMOVED }.joinToString("\n") { it.text }
    }
    val oldTokens = rememberShikiTokenLines(oldText, language)
    val newTokens = if (newText == oldText) oldTokens else rememberShikiTokenLines(newText, language)
    val wordSpans = remember(rows) { SessionReviewModel.changedSpans(rows) }

    val addBg = tokens.success.opacity(0.14).color
    val delBg = tokens.error.opacity(0.14).color
    val addWordBg = tokens.success.opacity(0.36).color
    val delWordBg = tokens.error.opacity(0.36).color

    val gutterWidth = if (compact) 30.dp else 38.dp
    val signWidth = if (picking) 26.dp else if (compact) 16.dp else 20.dp
    val fontSize = if (compact) 11 else 12
    val lineHeight = if (compact) 18 else 20

    val scrollMod = if (wordWrap) Modifier else Modifier.horizontalScroll(rememberScrollState())

    Column(modifier = modifier.fillMaxWidth().then(scrollMod)) {
        var oIdx = 0
        var nIdx = 0
        rows.forEachIndexed { idx, row ->
            val tokenLine = if (row.kind == ReviewBlocks.Kind.REMOVED) {
                oldTokens?.getOrNull(oIdx)
            } else {
                newTokens?.getOrNull(nIdx)
            }
            if (row.kind != ReviewBlocks.Kind.ADDED) oIdx++
            if (row.kind != ReviewBlocks.Kind.REMOVED) nIdx++

            val isAdd = row.kind == ReviewBlocks.Kind.ADDED
            val isDel = row.kind == ReviewBlocks.Kind.REMOVED
            val lineIndex = rowLineIndices?.getOrNull(idx) ?: idx
            val pickable = picking && (isAdd || isDel)
            val isPicked = pickable && lineIndex in pickedIndices

            GitUnifiedDiffSingleRow(
                row = row,
                tokenLine = tokenLine,
                changedRange = wordSpans.getOrNull(idx),
                wordWrap = wordWrap,
                compact = compact,
                picking = picking,
                isPicked = isPicked,
                lineIndex = lineIndex,
                onToggleLine = onToggleLine,
            )
        }
    }
}

@Composable
internal fun GitUnifiedDiffSingleRow(
    row: ReviewBlocks.Row,
    tokenLine: AnnotatedString?,
    changedRange: IntRange?,
    wordWrap: Boolean = true,
    compact: Boolean = false,
    picking: Boolean = false,
    isPicked: Boolean = false,
    lineIndex: Int = 0,
    onToggleLine: ((Int) -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    val addBg = tokens.success.opacity(0.14).color
    val delBg = tokens.error.opacity(0.14).color
    val addWordBg = tokens.success.opacity(0.36).color
    val delWordBg = tokens.error.opacity(0.36).color

    val gutterWidth = if (compact) 30.dp else 38.dp
    val signWidth = if (picking) 26.dp else if (compact) 16.dp else 20.dp
    val fontSize = if (compact) 11 else 12
    val lineHeight = if (compact) 18 else 20

    val isAdd = row.kind == ReviewBlocks.Kind.ADDED
    val isDel = row.kind == ReviewBlocks.Kind.REMOVED
    val pickable = picking && (isAdd || isDel)

    val rowBg = when {
        isPicked -> tokens.accent.opacity(0.22).color
        isAdd -> addBg
        isDel -> delBg
        else -> Color.Transparent
    }

    val annotated = remember(row.text, tokenLine, changedRange, isAdd, isDel) {
        buildDiffLineAnnotated(
            text = row.text,
            tokens = tokenLine,
            changedRange = changedRange,
            wordHighlight = if (isAdd) addWordBg else if (isDel) delWordBg else Color.Transparent,
        )
    }

    val scrollMod = if (wordWrap) Modifier else Modifier.horizontalScroll(rememberScrollState())

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .then(
                if (pickable && onToggleLine != null) {
                    Modifier.clickable(role = Role.Checkbox) { onToggleLine(lineIndex) }
                } else {
                    Modifier
                },
            )
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Old line number
        Box(
            modifier = Modifier.width(gutterWidth).padding(end = 6.dp),
            contentAlignment = Alignment.TopEnd,
        ) {
            BasicText(
                text = row.o?.toString() ?: "",
                style = PpmFont.mono(fontSize - 1, lineHeight = lineHeight, color = tokens.text3.color),
                maxLines = 1,
            )
        }
        // New line number
        Box(
            modifier = Modifier.width(gutterWidth).padding(end = 6.dp),
            contentAlignment = Alignment.TopEnd,
        ) {
            BasicText(
                text = row.n?.toString() ?: "",
                style = PpmFont.mono(fontSize - 1, lineHeight = lineHeight, color = tokens.text3.color),
                maxLines = 1,
            )
        }
        // Sign or line-pick checkbox
        Box(
            modifier = Modifier.width(signWidth),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (pickable) {
                val cbShape = RoundedCornerShape(3.dp)
                Box(
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .size(13.dp)
                        .clip(cbShape)
                        .background(if (isPicked) tokens.accent.color else Color.Transparent)
                        .cssBorder(if (isPicked) tokens.accent.color else tokens.text3.opacity(0.6).color, cbShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isPicked) {
                        PpmIcon(ProductIcon.Check, size = 9.dp, tint = tokens.accentFg.color)
                    }
                }
            } else {
                val sign = when (row.kind) {
                    ReviewBlocks.Kind.ADDED -> "+"
                    ReviewBlocks.Kind.REMOVED -> "−"
                    ReviewBlocks.Kind.CONTEXT -> " "
                }
                val signColor = when (row.kind) {
                    ReviewBlocks.Kind.ADDED -> tokens.success.color
                    ReviewBlocks.Kind.REMOVED -> tokens.error.color
                    ReviewBlocks.Kind.CONTEXT -> tokens.text3.color
                }
                BasicText(
                    text = sign,
                    style = PpmFont.mono(fontSize, lineHeight = lineHeight, color = signColor),
                    maxLines = 1,
                )
            }
        }
        // Code
        Box(modifier = Modifier.weight(1f).then(scrollMod)) {
            BasicText(
                text = annotated,
                style = PpmFont.mono(fontSize, lineHeight = lineHeight, color = tokens.text.color),
                softWrap = wordWrap,
                modifier = Modifier.padding(end = 10.dp),
            )
        }
    }
}

internal fun buildDiffLineAnnotated(
    text: String,
    tokens: AnnotatedString?,
    changedRange: IntRange?,
    wordHighlight: Color,
): AnnotatedString {
    val expanded = text.replace("\t", "    ")
    if (tokens == null && (changedRange == null || changedRange.isEmpty())) {
        return AnnotatedString(expanded)
    }
    return buildAnnotatedString {
        append(expanded)
        if (tokens != null && tokens.text.length == text.length) {
            // Apply token colors when no tabs shifted offsets, or map offsets across tabs
            var shift = 0
            val map = IntArray(text.length + 1)
            for (i in text.indices) {
                map[i] = i + shift
                if (text[i] == '\t') shift += 3
            }
            map[text.length] = text.length + shift
            for (span in tokens.spanStyles) {
                val s = map[span.start.coerceIn(0, text.length)]
                val e = map[span.end.coerceIn(0, text.length)]
                if (e > s) addStyle(span.item, s, e)
            }
        }
        if (changedRange != null && !changedRange.isEmpty() && wordHighlight != Color.Transparent) {
            var shift = 0
            val map = IntArray(text.length + 1)
            for (i in text.indices) {
                map[i] = i + shift
                if (text[i] == '\t') shift += 3
            }
            map[text.length] = text.length + shift
            val from = map[changedRange.first.coerceIn(0, text.length)]
            val to = map[(changedRange.last + 1).coerceIn(0, text.length)]
            if (to > from) {
                addStyle(SpanStyle(background = wordHighlight), from, to)
            }
        }
    }
}

@Composable
internal fun rememberShikiTokenLines(text: String, language: String?): List<AnnotatedString>? {
    if (text.isEmpty() || language == null) return null
    val theme = LocalPpmTheme.current.shikiTheme
    val highlighter = SyntaxHighlighter.get(LocalContext.current)
    val ready = remember(text, language, theme) { highlighter.cached(text, language, theme)?.let(::splitHighlightedLines) }
    if (ready != null) return ready
    val loaded by produceState<List<AnnotatedString>?>(null, text, language, theme) {
        value = highlighter.highlight(text, language, theme)?.let(::splitHighlightedLines)
    }
    return loaded
}

private fun splitHighlightedLines(code: HighlightedCode): List<AnnotatedString> {
    val text = code.text.text
    val starts = mutableListOf(0)
    text.forEachIndexed { i, c -> if (c == '\n') starts += i + 1 }
    val spans = List(starts.size) { mutableListOf<AnnotatedString.Range<SpanStyle>>() }
    for (span in code.text.spanStyles) {
        if (span.end <= span.start) continue
        var line = starts.binarySearch(span.start)
        if (line < 0) line = -line - 2
        val base = starts[line]
        val lineEnd = (starts.getOrNull(line + 1) ?: (text.length + 1)) - 1
        val style = SpanStyle(color = span.item.color, fontStyle = span.item.fontStyle.takeIf { it == FontStyle.Italic })
        spans[line] += AnnotatedString.Range(style, span.start - base, min(span.end, lineEnd) - base)
    }
    return starts.mapIndexed { i, s ->
        val e = (starts.getOrNull(i + 1) ?: (text.length + 1)) - 1
        AnnotatedString(text.substring(s, max(s, e)), spans[i])
    }
}

internal fun gitDiffLanguage(path: String?): String? {
    if (path.isNullOrBlank()) return null
    val name = path.substring(max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1).lowercase()
    if (name == "dockerfile" || name.endsWith(".dockerfile")) return "docker"
    if (name == "makefile") return "make"
    val dot = name.lastIndexOf('.')
    return if (dot > 0 && dot < name.lastIndex) name.substring(dot + 1) else null
}
