package com.hienlh.ppm.app.ui.files

import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.LocalWindowContentVisible
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.media.MediaFilePlayer
import com.hienlh.ppm.media.downloadMedia
import com.hienlh.ppm.state.files.FileDocumentClassifier
import com.hienlh.ppm.state.files.FileDocumentViewer
import com.hienlh.ppm.state.media.MediaKind
import com.hienlh.ppm.state.media.MediaSource
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** FileBody routes AUDIO/VIDEO here directly; do not open a FileDocument/raw byte buffer first. */
@Composable
fun FileMediaView(model: AppModel, tab: WorkspaceTab) {
    val api = model.api
    val path = tab.metaString("filePath")
    if (api == null || path.isNullOrEmpty()) { FileMessage("Media unavailable", "Connect to the server to open this file."); return }
    val source = MediaSource(tab.projectId ?: tab.metaString("projectName"), path,
        if (FileDocumentClassifier.classify(path) == FileDocumentViewer.AUDIO) MediaKind.AUDIO else MediaKind.VIDEO)
    val context = LocalContext.current.applicationContext
    var pending by remember { mutableStateOf<Pair<ApiClient, MediaSource>?>(null) }
    var downloading by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val target = pending
        pending = null
        if (uri != null) model.scope.launch {
            downloading = true
            try {
                if (target == null) throw IOException("The download was interrupted. Choose Download again.")
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri, "w") ?: throw IOException("Cannot open the chosen destination.")
                    output.use { downloadMedia(target.first, target.second, it) }
                }
                model.toast(ErrorToastMessage("Download saved", target.second.path.substringAfterLast('/'), kind = ErrorToastMessage.Kind.SUCCESS))
            } catch (error: Exception) {
                val removed = withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
                }
                if (error is CancellationException) throw error
                model.toast(ErrorToastMessage("Download failed", "Try again." + if (removed) "" else " Remove the partial file from the chosen destination."))
            } finally { downloading = false }
        }
    }
    MediaFilePlayer(api, source, Modifier.fillMaxSize(), visible = LocalWindowContentVisible.current,
        onDownload = if (downloading || pending != null) null else ({
        pending = api to source
        val filename = path.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\x00-\\x1f]"), "_")
        try { picker.launch(filename.ifBlank { "media" }) }
        catch (_: Exception) { pending = null; model.toast(ErrorToastMessage("The system file picker is unavailable.")) }
    }))
}
