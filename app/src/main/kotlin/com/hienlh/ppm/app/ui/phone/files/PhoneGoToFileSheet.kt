package com.hienlh.ppm.app.ui.phone.files

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.PaletteSearchIndex
import com.hienlh.ppm.app.ui.fileItem
import com.hienlh.ppm.app.ui.loadedTreeFiles
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.kit.KitSpec
import com.hienlh.ppm.design.kit.Sheet
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.FileEntry
import com.hienlh.ppm.state.FileIndexStore
import com.hienlh.ppm.state.workspace.WorkspaceTab
import com.hienlh.ppm.app.tracksTextFocus

/**
 * Go to file… on a phone: the command palette's file search (`CommandPalette`, which the redesigned phone
 * does not show) over the same project file list ([AppModel.fileIndex], the tree's loaded folders until it
 * lands, the server's search for a project too large to send), ranked by the same [PaletteSearchIndex].
 */
@Composable
internal fun PhoneGoToFileSheet(
    model: AppModel,
    project: String,
    onOpen: (path: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val query = rememberTextFieldState()
    val text = query.text.toString().trim()
    val fileStore = model.fileIndex
    DisposableEffect(fileStore, project) {
        val close = fileStore?.openReader(project)
        onDispose { close?.invoke() }
    }
    val index = fileStore?.state?.collectAsState()?.value
    val remote = index?.remote == true
    var remoteFiles by remember { mutableStateOf(emptyList<FileEntry>()) }
    LaunchedEffect(fileStore, text, remote, index?.revision) {
        if (fileStore == null || !remote || text.isEmpty()) {
            remoteFiles = emptyList()
            return@LaunchedEffect
        }
        fileStore.searchRemote(text, directories = false)?.let { remoteFiles = it }
    }
    val ready = index != null && index.status == FileIndexStore.Status.READY && index.project == project
    val indexEntries = index?.entries
    val tree = model.fileTree?.state?.collectAsState()?.value
    val treeFiles = remember(tree?.directories, project) { loadedTreeFiles(tree, project) }
    val indexed = remember(indexEntries) { indexEntries.orEmpty().filter { !it.isDirectory } }
    val files = when {
        remote -> remoteFiles
        ready -> indexed
        else -> treeFiles
    }
    val search = remember(files) { PaletteSearchIndex(files.map(::fileItem)) }
    val results = remember(search, text) {
        if (text.isEmpty()) emptyList() else search.rank(text, limit = 50).map { it.keywords }
    }
    val loading = index == null || index.status == FileIndexStore.Status.LOADING
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Sheet(
        title = "Go to file",
        onDismiss = onDismiss,
        forceBottomSheet = true,
        modifier = Modifier.testTag("phone-go-to-file-sheet"),
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            val fieldShape = RoundedCornerShape(14.dp)
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .fillMaxWidth()
                    .heightIn(min = KitSpec.MinTouchTarget)
                    .clip(fieldShape)
                    .background(tokens.panel.color, fieldShape)
                    .cssBorder(tokens.borderSoft.color, fieldShape, width = 1.dp)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PpmIcon(ProductIcon.Search, 18.dp, tokens.text3.color)
                BasicTextField(
                    state = query,
                    modifier = Modifier.weight(1f).focusRequester(focus).tracksTextFocus(query).testTag("phone-go-to-file-input"),
                    textStyle = ppmText(14.5f, lineHeight = 20f).copy(color = tokens.text.color),
                    cursorBrush = SolidColor(tokens.accent.color),
                    lineLimits = androidx.compose.foundation.text.input.TextFieldLineLimits.SingleLine,
                )
            }
            val note = when {
                text.isEmpty() -> "Type a file name."
                results.isEmpty() && loading -> "Loading the project's files…"
                results.isEmpty() -> "No matching files."
                else -> null
            }
            if (note != null) {
                BasicText(
                    note,
                    Modifier.padding(horizontal = 20.dp, vertical = 14.dp).testTag("phone-go-to-file-note"),
                    style = ppmText(13.5f, lineHeight = 18f).copy(color = tokens.text3.color),
                )
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(results, key = { it }) { path ->
                    PhoneFileResultRow(
                        name = path.substringAfterLast('/'),
                        detail = path.substringBeforeLast('/', ""),
                        onClick = { onOpen(path) },
                        testTag = "phone-go-to-file-result-$path",
                    )
                }
            }
        }
    }
}

/**
 * Recent on a phone: the project's open file tabs, the one visited last first ([recentTabIds] is the phone's
 * visit history, newest first), then the others in their tab order.
 */
@Composable
internal fun PhoneRecentFilesSheet(
    tabs: List<WorkspaceTab>,
    onOpen: (WorkspaceTab) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Sheet(
        title = "Recent",
        onDismiss = onDismiss,
        forceBottomSheet = true,
        modifier = Modifier.testTag("phone-recent-files-sheet"),
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            if (tabs.isEmpty()) {
                BasicText(
                    "No files open.",
                    Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    style = ppmText(13.5f, lineHeight = 18f).copy(color = tokens.text3.color),
                )
            }
            for (tab in tabs) {
                val path = tab.metaString("filePath").orEmpty()
                PhoneFileResultRow(
                    name = tab.title.ifBlank { path.substringAfterLast('/') },
                    detail = path.substringBeforeLast('/', ""),
                    onClick = { onOpen(tab) },
                    testTag = "phone-recent-file-${tab.id}",
                )
            }
        }
    }
}

/** The project's open file tabs, most recently visited first. */
internal fun phoneRecentFileTabs(openTabs: List<WorkspaceTab>, project: String, recentTabIds: List<String>): List<WorkspaceTab> {
    val files = openTabs.filter {
        it.type == "editor" && !it.metaString("filePath").isNullOrBlank() &&
            (it.projectId ?: it.metaString("projectName")) == project
    }
    val rank = recentTabIds.withIndex().associate { (i, id) -> id to i }
    return files.withIndex().sortedWith(compareBy({ rank[it.value.id] ?: Int.MAX_VALUE }, { it.index })).map { it.value }
}

@Composable
private fun PhoneFileResultRow(name: String, detail: String, onClick: () -> Unit, testTag: String) {
    val tokens = LocalPpmTokens.current
    PhoneSheetActionRow(
        icon = ProductIcon.FileCode,
        label = name,
        onClick = onClick,
        testTag = testTag,
        trailingContent = if (detail.isNotEmpty()) {
            {
                BasicText(
                    detail,
                    Modifier.padding(start = 8.dp).fillMaxWidth(0.5f),
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            null
        },
    )
}
