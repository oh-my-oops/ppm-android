package com.hienlh.ppm.app.ui.osexplorer

import android.content.ContentResolver
import android.provider.DocumentsContract
import android.view.DragEvent
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.dropZone
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.state.osexplorer.HostUploads
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/** Permission belongs to the batch, not to the hovered row or the window composition. */
@Composable
internal fun hostExternalDrop(model: AppModel, queue: HostUploads?, commands: HostExplorerCommands?, directory: String): Modifier {
    val activity = LocalActivity.current ?: return Modifier
    val resolver = LocalContext.current.applicationContext.contentResolver
    if (queue == null || commands == null) return Modifier
    val target = remember(activity, resolver, queue, commands, directory) {
        HostExternalDrop { event ->
            val clip = event.clipData
            if (clip == null || event.localState != null || model.api !== commands.api) false
            else {
                val uris = (0 until clip.itemCount).map { clip.getItemAt(it).uri }.distinct()
                if (uris.isEmpty() || uris.any { it?.scheme != ContentResolver.SCHEME_CONTENT }) false
                else {
                    try {
                        val permission = activity.requestDragAndDropPermissions(event)
                        val released = AtomicBoolean(false)
                        fun release() { if (released.compareAndSet(false, true)) permission?.release() }
                        var handedToQueue = false
                        val job = model.scope.launch {
                            try {
                                val sources = withContext(Dispatchers.IO) {
                                    uris.flatMap { uri ->
                                        ensureActive()
                                        val document = requireNotNull(uri)
                                        if (DocumentsContract.isTreeUri(document)) uploadDocumentTree(resolver, document)
                                        else {
                                            require(resolver.getType(document) != DocumentsContract.Document.MIME_TYPE_DIR) {
                                                "Use Upload folder to select this folder."
                                            }
                                            listOf(uploadDocument(resolver, document))
                                        }
                                    }
                                }
                                if (model.api === commands.api && sources.isNotEmpty()) {
                                    queue.enqueue(directory, sources, commands.prompts(), finished = ::release)
                                    handedToQueue = true
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { model.toast(ErrorToastMessage("Upload failed", error.message)) }
                            finally { if (!handedToQueue) release() }
                        }
                        // Covers cancellation before the coroutine body starts as well.
                        job.invokeOnCompletion { if (!handedToQueue) release() }
                    } catch (error: Exception) { model.toast(ErrorToastMessage("Cannot read dropped files", error.message)) }
                    true
                }
            }
        }
    }
    val tokens = LocalPpmTokens.current
    return Modifier.dropZone(shouldStartDragAndDrop = {
        val event = it.toAndroidDragEvent()
        event.localState == null && event.clipDescription != null
    }, target = target).then(if (target.over) Modifier.background(tokens.accentWash.color) else Modifier)
}

private class HostExternalDrop(private val receive: (DragEvent) -> Boolean) : DragAndDropTarget {
    var over by mutableStateOf(false)
        private set
    override fun onEntered(event: DragAndDropEvent) { over = true }
    override fun onExited(event: DragAndDropEvent) { over = false }
    override fun onEnded(event: DragAndDropEvent) { over = false }
    override fun onDrop(event: DragAndDropEvent): Boolean { over = false; return receive(event.toAndroidDragEvent()) }
}
