package com.hienlh.ppm.state.files

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.RawFile
import com.hienlh.ppm.client.rawFile
import com.hienlh.ppm.client.readFileContent
import com.hienlh.ppm.client.readDocxHtml
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class FileDocumentLoaded(val content: String? = null, val raw: RawFile? = null, val cannotDisplay: Boolean = false)
internal fun interface FileDocumentReader {
    suspend fun read(project: String?, path: String, viewer: FileDocumentViewer): FileDocumentLoaded
}

/**
 * Read-only document owned by one tab; call on the parent's single main thread.
 * Construction is lazy. Hidden/dirty documents retain content and only accumulate invalidations.
 * An invalidated response is discarded, then a fresh read follows if still visible and clean.
 * Reads are serialized because ApiClient shares in-flight GETs: cancelling and immediately
 * restarting would otherwise join the old response again. Close cancels this owner's waiter.
 */
class FileDocumentController internal constructor(
    private val reader: FileDocumentReader,
    project: String?,
    path: String,
    scope: CoroutineScope,
) : AutoCloseable {
    constructor(api: ApiClient, project: String?, path: String, scope: CoroutineScope) : this(
        FileDocumentReader { projectName, filePath, viewer ->
            if (viewer == FileDocumentViewer.IMAGE || viewer == FileDocumentViewer.PDF) {
                require(isAbsolute(filePath) || !projectName.isNullOrEmpty()) { "A relative file path needs a project." }
                FileDocumentLoaded(raw = api.rawFile(projectName.orEmpty(), filePath))
            } else if (viewer == FileDocumentViewer.DOCX) {
                FileDocumentLoaded(content = api.readDocxHtml(projectName, filePath))
            } else {
                val answer = api.readFileContent(projectName, filePath)
                FileDocumentLoaded(content = answer.content.takeIf { answer.isText }, cannotDisplay = !answer.isText)
            }
        }, project, path, scope,
    )

    private val owner = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val mutable = MutableStateFlow(FileDocumentState(project, path))
    val state: StateFlow<FileDocumentState> = mutable.asStateFlow()
    private val current get() = mutable.value
    private var visible = false
    private var readAsText = false
    private var closed = false
    private var epoch = 0
    private var loading: Job? = null

    /** HTML's Code mode opts into a text read; normal previews still delegate without a request. */
    fun show(readAsText: Boolean = false) {
        if (!alive()) return
        if (this.readAsText != readAsText) {
            this.readAsText = readAsText
            epoch++
            mutable.value = current.copy(stale = true, content = null, raw = null, cannotDisplay = false, error = null)
        }
        visible = true
        ensureLoaded()
    }

    fun hide() {
        if (!alive() || !visible) return
        visible = false
        if (loading != null) {
            epoch++
            mutable.value = current.copy(loading = false, stale = true)
        }
    }

    /** Manual retry also covers servers whose watcher missed a change. Hidden refresh stays lazy. */
    fun refresh() {
        if (!alive()) return
        epoch++
        mutable.value = current.copy(stale = true)
        ensureLoaded()
    }

    /** OS Explorer tabs without a project do not subscribe to project watcher events. */
    fun fileChanged(project: String, path: String?) {
        if (current.project == null || project != current.project || path != current.path) return
        refresh()
    }

    /** The watch gap may have missed changes, so revalidate only when visible and clean. */
    fun reconnected() = refresh()

    /** Reserved for an editing owner: never replace its unsaved buffer with a server response. */
    fun setDirty(dirty: Boolean) {
        if (!alive() || dirty == current.dirty) return
        if (dirty && loading != null) epoch++
        mutable.value = current.copy(dirty = dirty, loading = if (dirty) false else current.loading)
        if (!dirty) ensureLoaded()
    }

    private fun ensureLoaded() {
        if (!alive() || !visible || current.dirty || !current.stale) return
        if (loading != null) {
            mutable.value = current.copy(loading = true)
            return
        }
        val viewer = if (readAsText) FileDocumentViewer.TEXT else current.viewer
        if (viewer in delegated) {
            mutable.value = current.copy(loading = false, stale = false, error = null, revision = current.revision + 1)
            return
        }
        val version = epoch
        mutable.value = current.copy(loading = true, error = null)
        val job = owner.launch(start = CoroutineStart.LAZY) {
            try {
                if (!accepts(version)) return@launch
                val answer = reader.read(current.project, current.path, viewer)
                if (accepts(version)) mutable.value = current.copy(
                    content = answer.content, raw = answer.raw, cannotDisplay = answer.cannotDisplay,
                    loading = false, error = null, stale = false, revision = current.revision + 1,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (accepts(version)) mutable.value = current.copy(loading = false, error = error.message ?: "Failed to load file")
            } finally {
                loading = null
                if (version != epoch) ensureLoaded()
                else if (current.loading) mutable.value = current.copy(loading = false)
            }
        }
        loading = job
        job.start()
    }

    private fun alive() = !closed && owner.isActive
    private fun accepts(version: Int) = alive() && visible && !current.dirty && epoch == version

    override fun close() {
        if (closed) return
        closed = true
        visible = false
        epoch++
        owner.cancel()
        mutable.value = current.copy(loading = false)
    }

    private companion object {
        val delegated = setOf(FileDocumentViewer.HTML, FileDocumentViewer.VIDEO,
            FileDocumentViewer.AUDIO, FileDocumentViewer.SQLITE)
        val absolute = Regex("""^(/|[A-Za-z]:[/\\])""")
        fun isAbsolute(path: String) = absolute.containsMatchIn(path)
    }
}
