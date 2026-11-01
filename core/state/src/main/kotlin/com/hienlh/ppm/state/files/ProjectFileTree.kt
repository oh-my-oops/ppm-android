package com.hienlh.ppm.state.files

import com.hienlh.ppm.protocol.FileDirectoryEntry
import com.hienlh.ppm.protocol.FileDirectoryResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Lazy project directories, independent of FileIndexStore. Call on the owner's single thread.
 * [open] is the first network work; [reset] detaches a hidden/unselected tree, [close] disposes it.
 * Main wires path-bearing file changes to [onFileChanged] and global_ready to [reconnected].
 * Neither hook reads the recursive tree or the flat file index.
 */
class ProjectFileTree(
    private val backend: FileTreeBackend,
    parent: CoroutineScope,
    private val persistence: FileTreePersistence? = null,
    idleMillis: Long = 200,
) {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val mutable = MutableStateFlow(FileTreeState())
    val state: StateFlow<FileTreeState> = mutable.asStateFlow()
    private val current get() = mutable.value
    private var epoch = 0
    private class Load(val epoch: Int, var prefetch: Boolean, val batch: Boolean = false) { lateinit var job: Job }
    private val inflight = mutableMapOf<String, Load>()
    private val changed = linkedSetOf<String>()
    private var changeTimer: Job? = null
    private var restoreAfterChange = false
    private val prefetch = FileTreePrefetch(scope, idleMillis,
        { it in current.loadedPaths || it in inflight }, { loadChildren(it, prefetch = true) })

    /** Restore both kinds of selection for this server/project, without eagerly indexing it. */
    fun open(project: String): Job? {
        if (current.project == project) return null
        reset()
        mutable.value = FileTreeState(project = project,
            expandedPaths = persistence?.expanded(project).orEmpty(),
            selectedFiles = persistence?.selected(project).orEmpty())
        return if (current.expandedPaths.isEmpty()) loadChildren("")
        else loadPathsBatch(listOf("") + current.expandedPaths)
    }

    /** Explicit expansion promotes an in-flight prefetch; collapse never cancels or reopens it. */
    fun toggleExpand(path: String) = setExpanded(path, path !in current.expandedPaths)

    fun setExpanded(path: String, expanded: Boolean) {
        if (path.isEmpty() || current.node(path)?.isDirectory != true) return
        mutable.value = current.copy(expandedPaths = if (expanded) current.expandedPaths + path else current.expandedPaths - path)
        saveExpanded()
        if (expanded) {
            if (path in current.loadedPaths) prefetch.schedule(current.directories[path].orEmpty())
            else loadChildren(path)
        }
    }

    fun collapseAll() { mutable.value = current.copy(expandedPaths = emptySet()); saveExpanded() }
    fun setFocusedPath(path: String?) { mutable.value = current.copy(focusedPath = path) }
    fun setSelectedFiles(paths: List<String>) {
        mutable.value = current.copy(selectedFiles = paths.distinct())
        current.project?.let { persistence?.saveSelected(it, current.selectedFiles) }
    }
    fun toggleFileSelect(path: String) = setSelectedFiles(
        if (path in current.selectedFiles) current.selectedFiles - path else current.selectedFiles + path)
    fun clearSelection() = setSelectedFiles(emptyList())

    /** Idempotent and joinable. A failed directory stays retryable, with its old children intact. */
    fun loadChildren(path: String, prefetch: Boolean = false): Job? {
        val project = current.project ?: return null
        if (path.isNotEmpty() && current.node(path)?.isDirectory != true) return null
        if (path in current.loadedPaths) return null
        inflight[path]?.let { if (!prefetch) it.prefetch = false; return it.job }
        val load = Load(epoch, prefetch)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val entries = backend.list(project, path)
                if (owns(path, load)) {
                    accept(path, entries)
                    if (!load.prefetch) this@ProjectFileTree.prefetch.schedule(current.directories[path].orEmpty())
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (owns(path, load)) fail(path, error.message ?: "Failed to load files") }
            finally { finish(path, load) }
        }
        load.job = job
        reserve(path, load)
        job.start()
        return job
    }

    /** Parent-first, at most 50 per POST, including root; 50 saved folders therefore use two POSTs. */
    fun loadPathsBatch(paths: List<String>): Job? {
        val project = current.project ?: return null
        val joined = paths.mapNotNull { inflight[it]?.job }.distinct()
        val wanted = paths.distinct().filter { it !in current.loadedPaths && it !in inflight }.sortedBy(::pathDepth)
        if (wanted.isEmpty()) return if (joined.isEmpty()) null else scope.launch { joined.forEach { it.join() } }
        val loads = wanted.associateWith { Load(epoch, prefetch = false, batch = true) }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                joined.forEach { it.join() }
                for (chunk in wanted.chunked(50)) {
                    val live = chunk.filter { owns(it, loads.getValue(it)) }
                    if (live.isEmpty()) continue
                    val results = backend.listBatch(project, live).associateBy { it.path }
                    for (path in live) {
                        val load = loads.getValue(path)
                        if (!owns(path, load)) continue
                        val answer = results[path] ?: FileDirectoryResult(path, error = "Directory missing from batch response")
                        val entries = answer.entries
                        if (answer.error != null || entries == null) {
                            fail(path, answer.error ?: "The directory list could not be read.")
                            mutable.value = current.copy(expandedPaths = current.expandedPaths - path)
                            saveExpanded()
                        } else accept(path, entries)
                        finish(path, load)
                    }
                }
                if (loads.values.first().epoch == epoch) prefetch.schedule(current.tree)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                loads.forEach { (path, load) -> if (owns(path, load)) fail(path, error.message ?: "Failed to load files") }
            } finally { loads.forEach { (path, load) -> finish(path, load) } }
        }
        loads.forEach { (path, load) -> load.job = job; reserve(path, load) }
        job.start()
        return job
    }

    /** Manual Refresh / reconnect: retain visible content, revalidate root and expanded levels. */
    fun refresh(): Job? {
        if (current.project == null) return null
        abandonLoads()
        mutable.value = current.copy(loadedPaths = emptySet(), loadingPaths = emptySet(), errors = emptyMap())
        return loadPathsBatch(listOf("") + current.expandedPaths)
    }
    fun reconnected(): Job? = refresh()

    /** Refresh only an already-loaded (or currently loading) parent. Collapsed levels go stale. */
    fun invalidateFolder(project: String, path: String): Job? {
        if (project != current.project || (path !in current.loadedPaths && path !in inflight && path !in current.directories)) return null
        if (inflight[path]?.batch == true) return refresh()
        invalidate(path)
        return if (path.isEmpty() || path in current.expandedPaths) loadChildren(path) else null
    }

    /** Debounce 300 ms, retaining every affected parent in a burst, never fetching an index. */
    fun onFileChanged(project: String, path: String?) {
        if (project != current.project) return
        val parent = parentPath(path.orEmpty())
        if (!restoreAfterChange && parent !in current.loadedPaths && parent !in inflight && parent !in current.directories) return
        if (inflight[parent]?.batch == true) {
            // A restore is one parent-first snapshot. Keeping its children after rejecting a
            // parent would lose expansion or merge them under the wrong directory incarnation.
            abandonLoads()
            mutable.value = current.copy(loadedPaths = emptySet(), loadingPaths = emptySet())
            restoreAfterChange = true
        } else invalidate(parent) // Reject reads started before the event, even cancelled late answers.
        changed += parent
        changeTimer?.cancel()
        changeTimer = scope.launch {
            delay(300)
            changeTimer = null
            if (restoreAfterChange) {
                refresh()
                return@launch
            }
            val paths = changed.toList()
            changed.clear()
            for (folder in paths) if (folder.isEmpty() || folder in current.expandedPaths) loadChildren(folder)
        }
    }

    fun reset() {
        abandonLoads()
        mutable.value = FileTreeState()
    }
    fun close() { reset(); scope.cancel() }

    private fun saveExpanded() { current.project?.let { persistence?.saveExpanded(it, current.expandedPaths) } }
    private fun owns(path: String, load: Load) = load.epoch == epoch && inflight[path] === load
    private fun reserve(path: String, load: Load) {
        inflight[path] = load
        mutable.value = current.copy(loadingPaths = current.loadingPaths + path, errors = current.errors - path)
    }
    private fun finish(path: String, load: Load) {
        if (!owns(path, load)) return
        inflight.remove(path)
        mutable.value = current.copy(loadingPaths = current.loadingPaths - path)
    }
    private fun fail(path: String, message: String) { mutable.value = current.copy(errors = current.errors + (path to message)) }
    private fun invalidate(path: String) {
        val load = inflight.remove(path)
        if (load != null && !load.batch) load.job.cancel()
        mutable.value = current.copy(loadedPaths = current.loadedPaths - path, loadingPaths = current.loadingPaths - path)
    }
    private fun abandonLoads() {
        epoch++
        prefetch.cancel()
        changeTimer?.cancel()
        changeTimer = null
        restoreAfterChange = false
        changed.clear()
        val jobs = inflight.values.map { it.job }.distinct()
        inflight.clear()
        jobs.forEach { it.cancel() }
    }

    private fun accept(path: String, entries: List<FileDirectoryEntry>) {
        // A deleted/changed parent cannot accept a late child listing (including directory symlinks).
        if (path.isNotEmpty() && current.node(path)?.isDirectory != true) {
            mutable.value = current.copy(expandedPaths = current.expandedPaths - path)
            saveExpanded()
            return
        }
        val children = nodes(entries, path)
        val directories = children.filter { it.isDirectory }.mapTo(hashSetOf()) { it.path }
        val removed = current.directories[path].orEmpty().filter { it.isDirectory && it.path !in directories }.map { it.path }
        fun kept(candidate: String) = removed.none { below(candidate, it) }
        inflight.keys.filterNot(::kept).toList().forEach(::invalidate)
        mutable.value = current.copy(
            directories = current.directories.filterKeys(::kept) + (path to children),
            loadedPaths = current.loadedPaths.filterTo(linkedSetOf(), ::kept) + path,
            errors = current.errors.filterKeys(::kept) - path,
        )
    }
}
