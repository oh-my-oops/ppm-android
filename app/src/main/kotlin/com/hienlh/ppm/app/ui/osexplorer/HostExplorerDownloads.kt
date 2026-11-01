package com.hienlh.ppm.app.ui.osexplorer

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.HostTransferProgress
import com.hienlh.ppm.client.downloadHostFile
import com.hienlh.ppm.design.*
import com.hienlh.ppm.protocol.HostFileEntry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
internal fun rememberHostDownloads(model: AppModel): HostExplorerDownloads? {
    val api = model.api ?: return null
    val resolver = LocalContext.current.applicationContext.contentResolver
    val scope = rememberCoroutineScope()
    val downloads = remember(api, resolver) { HostExplorerDownloads(model, api, resolver, scope) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        downloads.destination(uri)
    }
    SideEffect { downloads.chooseDestination = picker::launch }
    DisposableEffect(downloads) { onDispose { downloads.cancel() } }
    return downloads
}

internal class HostExplorerDownloads(private val model: AppModel, private val api: ApiClient,
    private val resolver: ContentResolver, private val scope: CoroutineScope) {
    var chooseDestination: (String) -> Unit = {}
    var label by mutableStateOf<String?>(null)
        private set
    var active by mutableStateOf(false)
        private set
    val progress = MutableStateFlow(HostTransferProgress(0, null))
    private var job: Job? = null
    private var pending: CompletableDeferred<Uri?>? = null
    private var pickerOpen = false
    private var deliveredUri: Uri? = null

    fun destination(uri: Uri?) {
        pickerOpen = false
        val reply = pending
        if (reply != null && reply.isActive && model.api === api) {
            deliveredUri = uri
            reply.complete(uri)
        }
        else if (uri != null) model.scope.launch { removePartial(uri) }
    }

    fun download(entries: List<HostFileEntry>) {
        if (active || pickerOpen) return
        val files = entries.filter { it.type == "file" }
        val skipped = entries.size - files.size
        if (skipped > 0) model.toast(ErrorToastMessage(if (skipped == 1) "Folders cannot be downloaded" else "$skipped folders skipped"))
        if (files.isEmpty()) return
        active = true
        job = scope.launch {
            try {
                for (file in files) {
                    ensureActive()
                    if (model.api !== api) break
                    progress.value = HostTransferProgress(0, null)
                    label = "Choose where to save ${file.name}"
                    val reply = CompletableDeferred<Uri?>()
                    pending = reply
                    val uri = try {
                        pickerOpen = true
                        chooseDestination(file.name.replace(Regex("[/\\\\\\x00-\\x1f]"), "_").ifBlank { "file" })
                        reply.await()
                    } catch (error: Exception) {
                        if (error !is CancellationException) pickerOpen = false
                        throw error
                    } finally { pending = null; reply.cancel() }
                    deliveredUri = null
                    if (uri == null) continue
                    var saved = false
                    try {
                        ensureActive()
                        label = "Downloading ${file.name}"
                        withContext(Dispatchers.IO) {
                            val output = requireNotNull(resolver.openOutputStream(uri, "w")) { "Cannot open the chosen destination." }
                            output.use { api.downloadHostFile(file.path, it) { update -> progress.value = update } }
                        }
                        saved = true
                        label = "Saved ${file.name}"
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        label = "Download failed: ${file.name}"
                        model.toast(ErrorToastMessage(label!!, error.message))
                    } finally { if (!saved) removePartial(uri) }
                }
            } catch (cancelled: CancellationException) { label = "Download cancelled"; throw cancelled }
            catch (error: Exception) { label = "Download failed"; model.toast(ErrorToastMessage(label!!, error.message)) }
            finally {
                val unclaimed = deliveredUri
                deliveredUri = null
                if (unclaimed != null) removePartial(unclaimed)
                active = false
            }
        }
    }

    fun cancel() { job?.cancel(); pending?.cancel(); pending = null }
    fun dismiss() { if (!active) label = null }
    private suspend fun removePartial(uri: Uri) = withContext(NonCancellable + Dispatchers.IO) {
        val removed = runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
        if (!removed) withContext(Dispatchers.Main) { model.toast(ErrorToastMessage("Could not remove the incomplete download", "Remove it from the chosen destination.")) }
    }
}

@Composable
internal fun HostDownloadProgress(downloads: HostExplorerDownloads) {
    val label = downloads.label ?: return
    val progress by downloads.progress.collectAsState()
    val tokens = LocalPpmTokens.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            BasicText(label, style = ppmText(12f).copy(color = tokens.text.color))
            if (progress.bytes > 0) BasicText("${hostDisplaySize(progress.bytes)}${progress.total?.let { " / ${hostDisplaySize(it)}" }.orEmpty()}",
                style = ppmText(11f).copy(color = tokens.text3.color))
        }
        HostDialogButton(if (downloads.active) "Cancel" else "Dismiss") {
            if (downloads.active) downloads.cancel() else downloads.dismiss()
        }
    }
}
