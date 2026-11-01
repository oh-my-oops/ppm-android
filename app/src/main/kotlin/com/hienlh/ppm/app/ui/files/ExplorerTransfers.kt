package com.hienlh.ppm.app.ui.files

import android.app.Activity
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.DragEvent
import android.webkit.MimeTypeMap
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.PROJECT_UPLOAD_MAX_FILES
import com.hienlh.ppm.client.ProjectUploadFailure
import com.hienlh.ppm.client.ProjectUploadSource
import com.hienlh.ppm.client.downloadProjectFile
import com.hienlh.ppm.client.uploadProjectFiles
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.protocol.ProjectInfo
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.files.FileTreeNode
import com.hienlh.ppm.state.workspace.absoluteProjectPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

/**
 * Main wiring: actionsForNode = transfers::actionsForNode,
 * dropModifier = { transfers.dropModifier(it) }, transferStatus = { ExplorerTransferStatus(transfers) }.
 * [onUploadFinished] must be bound to this server and refresh the project/directory even after
 * failure/cancel: the server can have written earlier files without returning their names.
 */
@Composable
fun rememberExplorerTransfers(
    api: ApiClient, project: ProjectInfo, onUploadFinished: (project: String, directory: String) -> Unit,
): ExplorerTransfers {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val session = remember(api, project.name, project.path) { ExplorerTransferSession(scope) }
    var pending by rememberSaveable(api.baseUrl.toString(), project.name, project.path) { mutableStateOf<String?>(null) }
    DisposableEffect(session) { onDispose { session.close() } }
    val picker = rememberLauncherForActivityResult(CreateExplorerDocument()) { uri ->
        val node = pending?.let(::decodeDownloadTarget)
        pending = null
        if (uri == null) session.cancel()
        else if (node == null) scope.launch { removeDownload(context, uri) }
        else {
            // Restore a picker result after Activity recreation; no token existed before this.
            if (session.state.value.phase != ExplorerTransferPhase.CHOOSING) session.choose(node)
            val job = session.chosen { target, progress ->
                try {
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(uri, "w") ?: throw IOException("Cannot open the chosen destination.")
                        output.use { api.downloadProjectFile(project.name, target.path, target.isDirectory, it, progress) }
                    }
                    "Saved ${explorerDownloadName(target)}."
                } catch (error: Exception) {
                    val removed = removeDownload(context, uri)
                    if (error is CancellationException) {
                        if (!removed) throw CancellationException("Transfer cancelled. Could not remove the partial download.").apply { initCause(error) }
                        throw error
                    }
                    throw IOException((error.message ?: "Download failed") + if (removed) "" else " Could not remove the partial download.", error)
                }
            }
            if (job == null) scope.launch { removeDownload(context, uri) }
        }
    }
    return remember(api, project, session, context, activity, clipboard) {
        ExplorerTransfers(api, project, session, context, activity, clipboard, scope,
            choose = { node ->
                if (session.choose(node)) {
                    pending = encodeDownloadTarget(node)
                    try { picker.launch(explorerDownloadName(node) to explorerDownloadMime(node)) }
                    catch (error: Exception) { pending = null; session.failed(error.message ?: "The system file picker is unavailable.") }
                }
            },
            cancelChoice = { pending = null }, onUploadFinished = onUploadFinished)
    }
}

class ExplorerTransfers internal constructor(
    private val api: ApiClient, private val project: ProjectInfo, private val session: ExplorerTransferSession,
    private val context: Context, private val activity: Activity?, private val clipboard: Clipboard, private val scope: CoroutineScope,
    private val choose: (FileTreeNode) -> Unit, private val cancelChoice: () -> Unit,
    private val onUploadFinished: (String, String) -> Unit,
) {
    val state get() = session.state
    fun cancel() { cancelChoice(); session.cancel() }
    fun dismiss() = session.dismiss()

    fun actionsForNode(node: FileTreeNode): List<ExplorerTreeAction> = buildList {
        add(ExplorerTreeAction("Copy Path", ProductIcon.Copy) { copy(node.path) })
        add(ExplorerTreeAction("Copy Full Path", ProductIcon.Copy) { copy(absoluteProjectPath(project.path, node.path)) })
        if (!state.value.busy) add(ExplorerTreeAction(if (node.isDirectory) "Download as Zip" else "Download", ProductIcon.Download) { choose(node) })
    }

    private fun copy(path: String) { scope.launch {
        try { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("File path", path))) }
        catch (error: Exception) { session.failed(error.message ?: "Cannot copy the path.") }
    } }

    /** Attach to root and each directory target; phone has no upload target or upload button. */
    @Composable
    fun dropModifier(directory: String): Modifier {
        if (LocalPpmMobile.current || activity == null) return Modifier
        val target = remember(this, directory) { ExplorerExternalDrop { drop(directory, it) } }
        val tokens = LocalPpmTokens.current
        return Modifier.dragAndDropTarget(
            shouldStartDragAndDrop = { event ->
                val native = event.toAndroidDragEvent()
                !state.value.busy && native.localState == null && native.clipDescription != null
            }, target = target,
        ).then(if (target.over) Modifier.background(tokens.accentWash.color) else Modifier)
    }

    private fun drop(directory: String, event: DragEvent): Boolean {
        val clip = event.clipData ?: return false
        if (event.localState != null || state.value.busy) return false
        val uris = (0 until clip.itemCount).map { clip.getItemAt(it).uri }
        if (uris.isEmpty() || uris.any { it?.scheme != ContentResolver.SCHEME_CONTENT }) return false
        if (uris.size > PROJECT_UPLOAD_MAX_FILES) { session.failed("Drop at most $PROJECT_UPLOAD_MAX_FILES files."); return true }
        val permission = try { activity?.requestDragAndDropPermissions(event) }
        catch (error: Exception) { session.failed(error.message ?: "Cannot read the dropped files."); return true }
        val job = session.upload(if (uris.size == 1) "file" else "${uris.size} files", work = { progress ->
            val sources = withContext(Dispatchers.IO) { uris.map { uploadSource(context, it!!) } }
            try {
                val files = api.uploadProjectFiles(project.name, directory, sources, progress)
                "Uploaded ${if (files.size == 1) files.first().name else "${files.size} files"}."
            } catch (error: ProjectUploadFailure) {
                throw IOException((error.message ?: "Upload failed") + if (error.mayHaveWritten) " Earlier files may already have been saved." else "", error)
            }
        }, finished = {
            try { onUploadFinished(project.name, directory) } finally { permission?.release() }
        })
        if (job == null) permission?.release()
        return true
    }
}

private class ExplorerExternalDrop(private val drop: (DragEvent) -> Boolean) : DragAndDropTarget {
    var over by mutableStateOf(false)
        private set
    override fun onEntered(event: DragAndDropEvent) { over = true }
    override fun onExited(event: DragAndDropEvent) { over = false }
    override fun onEnded(event: DragAndDropEvent) { over = false }
    override fun onDrop(event: DragAndDropEvent): Boolean { over = false; return drop(event.toAndroidDragEvent()) }
}

private class CreateExplorerDocument : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>) = Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE).setType(input.second).putExtra(Intent.EXTRA_TITLE, input.first)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data.takeIf { resultCode == Activity.RESULT_OK }
}

private suspend fun removeDownload(context: Context, uri: Uri): Boolean = withContext(NonCancellable + Dispatchers.IO) {
    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
}

private fun uploadSource(context: Context, uri: Uri): ProjectUploadSource {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri)
    require(mime != DocumentsContract.Document.MIME_TYPE_DIR) { "Drop files, not folders." }
    var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
    var size: Long? = null
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn)
            if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn).takeIf { it >= 0 }
        }
    }
    return ProjectUploadSource(name, mime, size) { resolver.openInputStream(uri) ?: throw IOException("Cannot read $name.") }
}

internal fun explorerDownloadName(node: FileTreeNode): String {
    val name = node.name.replace(Regex("[/\\\\\\x00-\\x1f]"), "_").takeUnless { it.isBlank() || it == "." || it == ".." }
        ?: if (node.isDirectory) "folder" else "file"
    return if (node.isDirectory) "$name.zip" else name
}
private fun explorerDownloadMime(node: FileTreeNode): String = if (node.isDirectory) "application/zip"
    else MimeTypeMap.getSingleton().getMimeTypeFromExtension(node.name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

internal fun encodeDownloadTarget(node: FileTreeNode): String = buildJsonObject {
    put("name", node.name); put("path", node.path); put("directory", node.isDirectory)
}.toString()
internal fun decodeDownloadTarget(value: String): FileTreeNode? {
    val data = WireJson.parseOrNull(value) ?: return null
    return FileTreeNode(data["name"].string ?: return null, data["path"].string ?: return null, data["directory"].bool ?: return null, false)
}
