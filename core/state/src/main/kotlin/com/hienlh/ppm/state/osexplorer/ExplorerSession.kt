package com.hienlh.ppm.state.osexplorer

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.client.fsBrowse
import com.hienlh.ppm.protocol.HostDirectory
import com.hienlh.ppm.protocol.HostFileEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class ExplorerFailure(val message: String, val code: String? = null, val hint: String? = null)
data class ExplorerSessionState(
    val path: String,
    val history: List<String> = listOf(path),
    val historyIndex: Int = 0,
    val directory: HostDirectory? = null,
    val loading: Boolean = false,
    val stale: Boolean = true,
    val error: ExplorerFailure? = null,
    val selection: Set<String> = emptySet(),
    val anchor: String? = null,
    val filter: String = "",
) {
    val canBack get() = historyIndex > 0
    val canForward get() = historyIndex < history.lastIndex
    val currentDirectory get() = directory?.current ?: path
}

fun interface HostDirectoryReader { suspend fun read(path: String, hidden: Boolean): HostDirectory }

/** One window or phone sheet. Main-thread calls; stale requests never replace newer navigation. */
class ExplorerSession(
    private val reader: HostDirectoryReader,
    val preferences: ExplorerPreferences,
    scope: CoroutineScope,
    initialPath: String = "~",
    private val onPath: (String) -> Unit = {},
) : AutoCloseable {
    constructor(api: ApiClient, preferences: ExplorerPreferences, scope: CoroutineScope, initialPath: String = "~", onPath: (String) -> Unit = {}) :
        this(HostDirectoryReader { path, hidden -> api.fsBrowse(path, hidden) }, preferences, scope, initialPath, onPath)

    private val owner = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val mutable = MutableStateFlow(ExplorerSessionState(initialPath.ifEmpty { "~" }))
    val state: StateFlow<ExplorerSessionState> = mutable.asStateFlow()
    private val current get() = mutable.value
    private var load: Job? = null
    private var epoch = 0
    private var shown = false
    private var closed = false

    init {
        owner.launch {
            preferences.state.map { it.showHidden }.distinctUntilChanged().collect { refresh() }
        }
    }
    fun show() { if (!closed) { shown = true; if (current.stale) refresh() } }
    fun hide() { shown = false; epoch++; load?.cancel(); load = null; mutable.value = current.copy(loading = false, stale = current.stale || current.loading) }

    fun go(path: String, replace: Boolean = false) {
        if (closed || path.isEmpty() || path == current.path) return
        val history = (current.history.take(current.historyIndex + if (replace) 0 else 1) + path).takeLast(100)
        navigate(path, history, history.lastIndex)
    }
    fun back() { if (current.canBack) step(-1) }
    fun forward() { if (current.canForward) step(1) }
    fun up() { current.directory?.parent?.let { go(it) } }
    private fun step(delta: Int) {
        val index = current.historyIndex + delta
        current.history.getOrNull(index)?.let { navigate(it, current.history, index) }
    }
    private fun navigate(path: String, history: List<String>, index: Int) {
        mutable.value = ExplorerSessionState(path, history, index)
        onPath(path)
        refresh()
    }
    fun setFilter(filter: String) { mutable.value = current.copy(filter = filter) }
    fun visibleEntries(): List<HostFileEntry> = sortHostEntries(current.directory?.entries.orEmpty(), current.filter, preferences.state.value)

    /** A click on a visible row, as the web's `applySelection` takes it: [toggle] wins over [range] and moves the anchor to the row, [range] extends from the anchor and leaves it, neither selects the row alone. */
    fun select(path: String, toggle: Boolean = false, range: Boolean = false) {
        val order = visibleEntries().map { it.path }
        if (path in order) apply(order, HostSelectionEvent.Click(path, shift = range, ctrl = toggle))
    }
    fun selectAll() = apply(visibleEntries().map { it.path }, HostSelectionEvent.SelectAll)
    fun clearSelection() = apply(emptyList(), HostSelectionEvent.Clear)
    private fun apply(order: List<String>, event: HostSelectionEvent) {
        val next = applyHostSelection(HostSelection(current.selection, current.anchor), order, event)
        mutable.value = current.copy(selection = next.selection, anchor = next.anchor)
    }

    fun directoryChanged(path: String) { if (path == current.path || path == current.directory?.current) refresh() }
    fun refresh() {
        if (closed) return
        val version = ++epoch
        load?.cancel(); load = null
        mutable.value = current.copy(stale = true, loading = false)
        if (!shown) return
        val path = current.path
        val hidden = preferences.state.value.showHidden
        mutable.value = current.copy(loading = true, error = null)
        val job = owner.launch(start = CoroutineStart.LAZY) {
            try {
                val directory = reader.read(path, hidden)
                if (version == epoch && !closed && shown) mutable.value = current.copy(directory = directory,
                    loading = false, stale = false, error = null, selection = current.selection.intersect(directory.entries.map { it.path }.toSet()))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (version == epoch && !closed && shown) mutable.value = current.copy(directory = null, loading = false, stale = true,
                    error = ExplorerFailure(failure.message ?: "Failed to read directory", (failure as? PpmError.Server)?.code, (failure as? PpmError.Server)?.hint))
            } finally { if (version == epoch) load = null }
        }
        load = job; job.start()
    }
    override fun close() {
        if (closed) return
        closed = true; shown = false; epoch++; owner.cancel(); load = null
        mutable.value = current.copy(loading = false)
    }
}
