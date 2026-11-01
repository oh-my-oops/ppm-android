package com.hienlh.ppm.app.ui.osexplorer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ui.BottomSheet
import com.hienlh.ppm.state.osexplorer.HostUploadBatch
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.client.HostUploadSource
import com.hienlh.ppm.design.*
import com.hienlh.ppm.state.osexplorer.HostUploads
import kotlinx.coroutines.*

private data class UploadSelection(val directory: String, val queue: HostUploads,
                                   val commands: HostExplorerCommands)

internal class HostUploadPicker(val files: (String) -> Unit, val folder: (String) -> Unit)

/** Destination is captured before launching SAF, so navigation cannot redirect an upload. */
@Composable
internal fun rememberHostUploadPicker(model: AppModel, queue: HostUploads?, commands: HostExplorerCommands?): HostUploadPicker {
    val resolver = LocalContext.current.applicationContext.contentResolver
    val scope = rememberCoroutineScope()
    var selection by remember(model.api) { mutableStateOf<UploadSelection?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val request = selection
        selection = null
        if (uris.isNotEmpty() && request != null && model.api === request.commands.api) scope.launch {
            try {
                val sources = withContext(Dispatchers.IO) {
                    uris.distinct().map { uploadDocument(resolver, it) }
                }
                if (model.api === request.commands.api) request.queue.enqueue(request.directory, sources, request.commands.prompts())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { model.toast(ErrorToastMessage("Upload failed", error.message)) }
        }
    }
    var folderSelection by remember(model.api) { mutableStateOf<UploadSelection?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val request = folderSelection
        folderSelection = null
        if (uri != null && request != null && model.api === request.commands.api) scanJob = scope.launch {
            scanning = true
            try {
                val sources = withContext(Dispatchers.IO) { uploadDocumentTree(resolver, uri) }
                if (model.api === request.commands.api) {
                    if (sources.isEmpty()) model.toast(ErrorToastMessage("This folder contains no files"))
                    else request.queue.enqueue(request.directory, sources, request.commands.prompts())
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { model.toast(ErrorToastMessage("Folder upload failed", error.message)) }
            finally { scanning = false }
        }
    }
    if (scanning) HostExplorerDialog("Reading folder…", "Collecting files to upload", { scanJob?.cancel() }) {
        androidx.compose.foundation.text.BasicText("Upload will begin after the folder has been read.",
            style = ppmText(12f).copy(color = LocalPpmTokens.current.text2.color))
        HostDialogButton("Cancel") { scanJob?.cancel() }
    }
    return HostUploadPicker(files = { directory ->
        selection = if (queue != null && commands != null) UploadSelection(directory, queue, commands) else null
        try { launcher.launch(arrayOf("*/*")) }
        catch (error: Exception) { selection = null; model.toast(ErrorToastMessage("Cannot open file picker", error.message)) }
    }, folder = { directory ->
        folderSelection = if (queue != null && commands != null) UploadSelection(directory, queue, commands) else null
        try { folderLauncher.launch(null) }
        catch (error: Exception) { folderSelection = null; model.toast(ErrorToastMessage("Cannot open folder picker", error.message)) }
    })
}

@Composable
internal fun HostUploadProgress(queue: HostUploads) {
    val batches by queue.state.collectAsState()
    if (batches.isEmpty()) return
    val tokens = LocalPpmTokens.current
    var expanded by remember { mutableStateOf(false) }
    val items = batches.flatMap { it.items }
    val active = items.count { it.active }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        BasicText(if (active > 0) "Uploading $active file${if (active == 1) "" else "s"}…" else "Uploads finished",
            Modifier.ppmClickable { expanded = !expanded }.padding(vertical = 8.dp), style = ppmText(12f).copy(color = tokens.text.color))
        HostDialogButton(if (expanded) "Hide" else "Details") { expanded = !expanded }
    }
    if (expanded) {
        if (LocalPpmMobile.current) BottomSheet(onDismiss = { expanded = false }, modifier = Modifier.fillMaxHeight(.65f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                BasicText("Uploads", Modifier.padding(vertical = 12.dp), style = ppmText(15f).copy(color = tokens.text.color))
                HostDialogButton("Close") { expanded = false }
            }
            HostUploadDetails(queue, batches, Modifier.weight(1f))
        } else HostUploadDetails(queue, batches, Modifier.heightIn(max = 180.dp))
    }
}

@Composable
private fun HostUploadDetails(queue: HostUploads, batches: List<HostUploadBatch>, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    LazyColumn(modifier.fillMaxWidth()) {
        for (batch in batches) {
            item(key = "batch-${batch.id}") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    BasicText(batch.directory, Modifier.weight(1f), style = ppmText(11f).copy(color = tokens.text3.color))
                    HostDialogButton(if (batch.settled) "Dismiss" else "Cancel all") {
                        if (batch.settled) queue.dismiss(batch.id) else queue.cancelBatch(batch.id)
                    }
                }
            }
            items(batch.items, key = { "${batch.id}-${it.id}" }) { file ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        BasicText(file.relativePath, style = ppmText(12f).copy(color = tokens.text.color))
                        BasicText(file.error ?: "${file.phase.name.lowercase()} · ${hostDisplaySize(file.bytes)}${file.size?.let { " / ${hostDisplaySize(it)}" }.orEmpty()}",
                            style = ppmText(11f).copy(color = if (file.error != null) tokens.error.color else tokens.text3.color))
                    }
                    if (file.active) HostDialogButton("Cancel") { queue.cancelItem(batch.id, file.id) }
                }
            }
        }
    }
}
