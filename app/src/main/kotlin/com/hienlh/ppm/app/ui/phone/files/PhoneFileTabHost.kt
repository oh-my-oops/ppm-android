package com.hienlh.ppm.app.ui.phone.files

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.editor.EditableFileCodeView
import com.hienlh.ppm.app.ui.editor.EditableFileHandle
import com.hienlh.ppm.app.ui.files.CreateExplorerDocument
import com.hienlh.ppm.app.ui.files.explorerDownloadMime
import com.hienlh.ppm.app.ui.files.explorerDownloadName
import com.hienlh.ppm.app.ui.files.fileBufferContent
import com.hienlh.ppm.app.ui.files.fileCodeLanguage
import com.hienlh.ppm.app.ui.files.removeDownload
import com.hienlh.ppm.client.downloadProjectFile
import com.hienlh.ppm.client.listProjectDirectory
import com.hienlh.ppm.protocol.FileDirectoryEntry
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.state.files.FileTreeNode
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException

private val ABSOLUTE_PATH = Regex("""^(/|[A-Za-z]:[/\\])""")

/**
 * A file tab on a phone, wired as the old `FileBody` wires it: the tab's [com.hienlh.ppm.state.files.FileDocumentController]
 * reads the file, [EditableFileCodeView] edits and saves it (autosave, Save, the changed-on-disk choice), and the
 * screen around it shows that view's state through an [EditableFileHandle].
 */
@Composable
fun PhoneFileTabHost(model: AppModel, tab: WorkspaceTab, modifier: Modifier = Modifier) {
    val path = tab.metaString("filePath").orEmpty()
    val project = tab.projectId ?: tab.metaString("projectName")
    val buffer = fileBufferContent(tab)
    // A workbook is read sheet by sheet by the server (`xlsx-preview.tsx`), never as the tab's file content.
    if (buffer == null && isPhoneXlsxPath(path) && tab.metadata["isUntitled"].bool != true) {
        PhoneXlsxViewerHost(model, project, path, modifier)
        return
    }
    val isUntitled = tab.metadata["isUntitled"].bool == true
    val api = model.api
    val controller = if (buffer == null) remember(model, api, tab.id, path, project) { model.fileDocument(tab) } else null
    DisposableEffect(controller) {
        controller?.show(readAsText = false)
        onDispose { controller?.hide() }
    }
    val docState = controller?.state?.collectAsState()?.value
    val content = docState?.content ?: buffer
    val raw = docState?.raw?.bytes
    val hasData = content != null || raw != null || docState?.cannotDisplay == true
    val readOnly = (buffer != null && !isUntitled) || tab.metadata["readOnly"].bool == true
    val metaLang = tab.metaString("inlineLanguage") ?: tab.metaString("language")
    val isProjectFile = !isUntitled && buffer == null && path.isNotBlank() && !project.isNullOrBlank()
    val isRelativeProjectFile = isProjectFile && !ABSOLUTE_PATH.containsMatchIn(path)
    val displayPath = path.ifBlank { tab.title.ifBlank { "Untitled" } }
    val context = LocalContext.current

    var wrap by remember { mutableStateOf(true) }
    val handle = remember(tab.id, path, project) { EditableFileHandle() }
    val download = rememberPhoneFileDownload(model)
    val offlineSeconds = rememberOfflineSeconds(model.connectionLost)
    var siblingsOf by remember(tab.id, path) { mutableStateOf<String?>(null) }
    // The editor's change bars ask git only once the repository holding the file is known; the
    // tablet's status bar reads it for the open project, and a phone reads it here (`useGitRepo`).
    LaunchedEffect(api, project, isRelativeProjectFile) {
        if (isRelativeProjectFile) model.loadGitRepos(project!!)
    }

    Box(modifier.fillMaxSize()) {
        PhoneFileTabScreen(
            filePath = displayPath,
            textContent = content.orEmpty(),
            binaryBytes = raw,
            sizeBytes = raw?.size?.toLong() ?: content?.toByteArray()?.size?.toLong(),
            cannotDisplayBinary = docState?.cannotDisplay == true,
            dirty = handle.dirty,
            saving = handle.saving,
            saveError = handle.saveError,
            changedOnDisk = handle.conflict,
            readOnly = readOnly,
            languageLabel = phoneEditorLanguageLabel(path.ifBlank { tab.title }, metaLang),
            cursorLine = handle.cursorLine,
            cursorColumn = handle.cursorColumn,
            wordWrap = wrap,
            offlineSeconds = offlineSeconds.takeIf { handle.dirty },
            loading = docState?.loading == true && !hasData,
            error = docState?.error?.takeIf { !hasData },
            onToggleWordWrap = { wrap = !wrap },
            onSave = handle::save,
            onDiscardAndReload = handle::discardAndReload,
            onOverwriteDisk = handle::overwriteDisk,
            onRetry = { controller?.refresh() },
            onReloadFromDisk = controller?.let { { it.refresh() } },
            onDownload = if (isRelativeProjectFile && api != null) {
                { download(project!!, path, false) }
            } else {
                null
            },
            onCopyPath = if (path.isNotBlank()) {
                {
                    copyTextToClipboard(context, "Path", path)
                    model.toast(ErrorToastMessage("Copied path", kind = ErrorToastMessage.Kind.INFO))
                }
            } else {
                null
            },
            onCloseTab = { model.closeTab(tab.id) },
            // A file with git's conflict markers opens the conflict editor, as Source Control's Resolve does.
            onResolveConflicts = if (isRelativeProjectFile) ({ model.openGitConflictTab(project!!, path) }) else null,
            onBreadcrumbSegmentClick = { crumb ->
                if (isRelativeProjectFile) siblingsOf = crumb.relativePath.substringBeforeLast('/', "")
            },
            editorBodyContent = if (content != null) {
                {
                    EditableFileCodeView(
                        model = model,
                        project = project,
                        path = path.ifBlank { null },
                        initialContent = content,
                        initialLanguage = fileCodeLanguage(path, metaLang),
                        wrap = wrap,
                        onToggleWrap = { wrap = !wrap },
                        isUntitled = isUntitled,
                        readOnly = readOnly,
                        useWebHeader = true,
                        onDirtyChange = { dirty -> controller?.setDirty(dirty) },
                        handle = handle,
                    )
                }
            } else {
                null
            },
        )

        val directory = siblingsOf
        if (directory != null && project != null) {
            PhoneBreadcrumbSiblings(
                model = model,
                project = project,
                directory = directory,
                activeName = path.substringAfterLast('/'),
                onOpen = { entryPath, name ->
                    siblingsOf = null
                    model.updateTabTitleAndMetadata(
                        tabId = tab.id,
                        title = name,
                        "filePath" to JsonPrimitive(entryPath),
                        "projectName" to JsonPrimitive(project),
                    )
                },
                onEnter = { siblingsOf = it },
                onDismiss = { siblingsOf = null },
            )
        }
    }
}

/** The folder's entries (`EditorBreadcrumbBar`'s segment list): a file replaces the tab's file, as it does there; a folder opens its own list. */
@Composable
private fun PhoneBreadcrumbSiblings(
    model: AppModel,
    project: String,
    directory: String,
    activeName: String,
    onOpen: (path: String, name: String) -> Unit,
    onEnter: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val api = model.api
    var entries by remember(project, directory) { mutableStateOf<List<FileDirectoryEntry>?>(null) }
    LaunchedEffect(api, project, directory) {
        entries = null
        entries = if (api == null) emptyList() else try {
            api.listProjectDirectory(project, directory)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            model.toast(ErrorToastMessage("Could not list ${directory.ifEmpty { project }}", error.message))
            emptyList()
        }
    }
    PhoneBreadcrumbSiblingsSheet(
        segmentLabel = directory.substringAfterLast('/').ifEmpty { project },
        entries = entries,
        activeEntryName = activeName,
        onSelectEntry = { entry ->
            val entryPath = if (directory.isEmpty()) entry.name else "$directory/${entry.name}"
            if (entry.isDirectory) onEnter(entryPath) else onOpen(entryPath, entry.name)
        },
        onDismiss = onDismiss,
    )
}

/**
 * Saves a project file or folder (as a zip) where the user picks, through the system's Create Document
 * screen, as the old editor's Download (`FileBody`'s `downloadLauncher`) does.
 */
@Composable
internal fun rememberPhoneFileDownload(model: AppModel): (project: String, path: String, directory: Boolean) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentModel by rememberUpdatedState(model)
    var pending by remember { mutableStateOf<Triple<String, String, Boolean>?>(null) }
    val launcher = rememberLauncherForActivityResult(CreateExplorerDocument()) { uri ->
        val target = pending
        pending = null
        val client = currentModel.api
        if (uri == null || target == null || client == null) return@rememberLauncherForActivityResult
        val (project, path, directory) = target
        val name = explorerDownloadName(FileTreeNode(path.substringAfterLast('/').ifBlank { "file" }, path, directory, false))
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val out = context.contentResolver.openOutputStream(uri, "w")
                        ?: throw IOException("Cannot open the chosen destination.")
                    out.use { client.downloadProjectFile(project, path, directory, it) {} }
                }
                currentModel.toast(ErrorToastMessage("Saved $name.", kind = ErrorToastMessage.Kind.INFO))
            } catch (error: CancellationException) {
                removeDownload(context, uri)
                throw error
            } catch (error: Exception) {
                removeDownload(context, uri)
                currentModel.toast(ErrorToastMessage("Download failed", error.message))
            }
        }
    }
    return remember(launcher) {
        { project, path, directory ->
            val node = FileTreeNode(path.substringAfterLast('/').ifBlank { "file" }, path, directory, false)
            pending = Triple(project, path, directory)
            try {
                launcher.launch(explorerDownloadName(node) to explorerDownloadMime(node))
            } catch (error: Exception) {
                pending = null
                currentModel.toast(ErrorToastMessage(error.message ?: "The system file picker is unavailable."))
            }
        }
    }
}

/** Whole seconds since [lost] turned true, ticking once a second, or null while connected. */
@Composable
internal fun rememberOfflineSeconds(lost: Boolean): Int? {
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(lost) {
        seconds = 0
        if (!lost) return@LaunchedEffect
        val start = System.currentTimeMillis()
        while (true) {
            seconds = ((System.currentTimeMillis() - start) / 1000).toInt()
            delay(1_000)
        }
    }
    return if (lost) seconds else null
}
