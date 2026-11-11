package com.hienlh.ppm.app.ui.phone.git

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.phone.files.PhoneDiffScreen
import com.hienlh.ppm.app.ui.phone.files.PhoneImageRevisionSpec
import com.hienlh.ppm.client.FileFullDiff
import com.hienlh.ppm.client.filesCompare
import com.hienlh.ppm.client.gitDiff
import com.hienlh.ppm.client.gitFileBlob
import com.hienlh.ppm.client.gitFileFullDiff
import com.hienlh.ppm.client.rawFile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.ReviewBlocks
import com.hienlh.ppm.state.git.FileGitScopeKind
import com.hienlh.ppm.state.git.GitLogRefBadges
import com.hienlh.ppm.state.git.GitRepoScope
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.coroutines.CancellationException

/**
 * A `git-diff` tab on a phone, loaded as the old `GitDiffViewer` loads it: two files compared
 * (`file1`/`file2`, `/files/compare`), one file against a ref or the working tree (`filePath`,
 * `/git/file-full-diff`, in the repository that holds it), or a whole ref range (`/git/diff`). An inline
 * `original`/`modified` pair, or a unified `diff`, is shown as given.
 */
@Composable
fun PhoneGitDiffTabHost(
    model: AppModel,
    tab: WorkspaceTab,
    onOpenFile: (project: String, path: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val project = tab.metaString("projectName")?.takeIf { it.isNotEmpty() } ?: tab.projectId ?: model.selectedProject
    val filePath = tab.metaString("filePath")?.takeIf { it.isNotEmpty() }
    val ref1 = tab.metaString("ref1")?.takeIf { it.isNotEmpty() }
    val ref2 = tab.metaString("ref2")?.takeIf { it.isNotEmpty() }
    val file1 = tab.metaString("file1")?.takeIf { it.isNotEmpty() }
    val file2 = tab.metaString("file2")?.takeIf { it.isNotEmpty() }
    val inlineDiff = tab.metaString("diff")
    val inlineOriginal = tab.metaString("original")
    val inlineModified = tab.metaString("modified")
    val isInline = inlineDiff != null || inlineOriginal != null || inlineModified != null
    val isCompare = file1 != null && file2 != null
    val api = model.api

    // Two files compared need no repository; everything else is scoped to one.
    val needsRepo = !isInline && !isCompare
    LaunchedEffect(project, api) {
        if (project != null && needsRepo) model.loadGitRepos(project)
    }
    val discovery = project?.takeIf { needsRepo }?.let { model.gitDiscovery[it] }
    val chosen = project?.let { model.gitChosenRepoPath[it] }
    val selectedRepo = remember(discovery, chosen) { GitRepoScope.resolveRepo(discovery, chosen) }
    val fileScope = remember(filePath, discovery) { filePath?.let { GitRepoScope.fileGitScope(discovery, it) } }

    var diffText by remember(tab.id) { mutableStateOf<String?>(null) }
    var full by remember(tab.id) { mutableStateOf<FileFullDiff?>(null) }
    var openAsText by remember(tab.id) { mutableStateOf(false) }
    var loading by remember(tab.id) { mutableStateOf(!isInline) }
    var error by remember(tab.id) { mutableStateOf<String?>(null) }
    var noRepo by remember(tab.id) { mutableStateOf(false) }

    LaunchedEffect(project, filePath, ref1, ref2, file1, file2, openAsText, discovery, selectedRepo?.path, api) {
        if (isInline) {
            loading = false
            return@LaunchedEffect
        }
        val client = api ?: return@LaunchedEffect
        val proj = project ?: return@LaunchedEffect
        error = null
        try {
            when {
                isCompare -> {
                    loading = true
                    val (original, modified) = client.filesCompare(proj, file1!!, file2!!)
                    diffText = unifiedDiffText(original, modified)
                }
                filePath != null -> {
                    // Discovery has not answered yet: keep loading until it does.
                    val scope = fileScope ?: return@LaunchedEffect
                    if (scope.kind == FileGitScopeKind.NO_REPO) {
                        noRepo = true
                        loading = false
                        return@LaunchedEffect
                    }
                    loading = true
                    val answer = client.gitFileFullDiff(proj, scope.filePath, ref1 ?: "HEAD", ref2, openAsText, scope.repo)
                    full = answer
                    diffText = if (answer.binary && !openAsText) "" else unifiedDiffText(answer.original, answer.modified)
                }
                else -> {
                    loading = true
                    diffText = client.gitDiff(proj, ref1 = ref1, ref2 = ref2, repo = selectedRepo)
                }
            }
            loading = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = model.describe(failure)
            loading = false
        }
    }

    val tokens = LocalPpmTokens.current
    if (project == null && !isInline) {
        DiffNote("No project selected.", modifier)
        return
    }
    if (noRepo) {
        DiffNote("This file is not in a git repository.", modifier)
        return
    }
    val failure = error
    if (failure != null) {
        Box(modifier.fillMaxSize().padding(24.dp).testTag("phone-diff-error"), contentAlignment = Alignment.Center) {
            BasicText(failure, style = ppmText(13.5f, lineHeight = 19f).copy(color = tokens.error.color))
        }
        return
    }

    val text = when {
        inlineDiff != null -> inlineDiff
        inlineOriginal != null || inlineModified != null -> unifiedDiffText(inlineOriginal.orEmpty(), inlineModified.orEmpty())
        else -> diffText.orEmpty()
    }
    val leftLabel = when {
        isCompare -> file1!!.substringAfterLast('/')
        else -> GitLogRefBadges.shortRef(ref1 ?: "HEAD")
    }
    val rightLabel = when {
        isCompare -> file2!!.substringAfterLast('/')
        ref2 != null -> GitLogRefBadges.shortRef(ref2)
        else -> "Working Tree"
    }
    val binary = full?.binary == true && !openAsText
    val imageBinary = binary && filePath != null && GitLogRefBadges.canPreviewBinary(filePath)
    val scope = fileScope?.takeIf { it.kind == FileGitScopeKind.REPO }
    var headBytes by remember(tab.id, ref1, ref2) { mutableStateOf<ByteArray?>(null) }
    var treeBytes by remember(tab.id, ref1, ref2) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(imageBinary, scope?.filePath, scope?.repo?.path, ref1, ref2, api) {
        val client = api ?: return@LaunchedEffect
        if (!imageBinary || scope == null || project == null || filePath == null) return@LaunchedEffect
        headBytes = runCatching { client.gitFileBlob(project, scope.filePath, ref1 ?: "HEAD", scope.repo).bytes }.getOrNull()
        treeBytes = runCatching {
            if (ref2 != null) client.gitFileBlob(project, scope.filePath, ref2, scope.repo).bytes else client.rawFile(project, filePath).bytes
        }.getOrNull()
    }

    PhoneDiffScreen(
        filePath = filePath ?: file2 ?: tab.title,
        diffText = text,
        leftRefLabel = leftLabel,
        rightRefLabel = rightLabel,
        loading = loading,
        isBinary = binary,
        isImageBinary = imageBinary,
        headImage = PhoneImageRevisionSpec(title = leftLabel, sizeLabel = full?.originalSize?.let(::byteLabel), bytes = headBytes),
        workingTreeImage = PhoneImageRevisionSpec(title = rightLabel, sizeLabel = full?.modifiedSize?.let(::byteLabel), bytes = treeBytes, isAddedSide = true),
        onOpenFile = {
            val target = filePath ?: file2
            if (project != null && target != null) onOpenFile(project, target)
        },
        onOpenAnyway = { openAsText = true },
        modifier = modifier,
    )
}

@Composable
private fun DiffNote(text: String, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    Box(modifier.fillMaxSize().padding(24.dp).testTag("phone-diff-note"), contentAlignment = Alignment.Center) {
        BasicText(text, style = ppmText(13.5f, lineHeight = 19f).copy(color = tokens.text3.color))
    }
}

private fun byteLabel(bytes: Long): String = com.hienlh.ppm.app.ui.phone.files.formatPhoneFileSize(bytes)

/**
 * [original] → [modified] as unified diff text for [PhoneDiffScreen]: git's hunks with three lines of
 * context ([ReviewBlocks], the web's own line diff). A pair too large to diff in time is shown as the
 * whole file replaced, as the web answers it whole.
 */
internal fun unifiedDiffText(original: String, modified: String): String {
    val diff = ReviewBlocks.computeBlocks(original, modified)
    val out = StringBuilder()
    if (diff == null) {
        val old = ReviewBlocks.splitLines(original).map { it.trimEnd('\n', '\r') }
        val now = ReviewBlocks.splitLines(modified).map { it.trimEnd('\n', '\r') }
        out.append("@@ -1,${old.size} +1,${now.size} @@\n")
        old.forEach { out.append('-').append(it).append('\n') }
        now.forEach { out.append('+').append(it).append('\n') }
        return out.toString()
    }
    for (block in diff.blocks) {
        out.append("@@ -${block.oldFrom + 1},${block.oldTo - block.oldFrom} +${block.newFrom + 1},${block.newTo - block.newFrom} @@\n")
        for (row in block.rows) out.append(row.kind.sign).append(row.text).append('\n')
    }
    return out.toString()
}
