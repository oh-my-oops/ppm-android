package com.hienlh.ppm.app.ui.osexplorer

import com.hienlh.ppm.protocol.HostFileEntry
import com.hienlh.ppm.state.osexplorer.HostDirectoryReader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class HostColumn(val path: String, val entries: List<HostFileEntry> = emptyList(), val loading: Boolean = true, val error: String? = null)

/** Independent ancestor reads. The deepest listing always belongs to ExplorerSession. */
internal class HostColumnState(private val reader: HostDirectoryReader, parent: CoroutineScope) : AutoCloseable {
    private class Record(var value: HostColumn, var loaded: Boolean = false, var job: Job? = null, var epoch: Int = 0)
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val records = mutableListOf<Record>()
    private val mutable = MutableStateFlow<List<HostColumn>>(emptyList())
    val state = mutable.asStateFlow()
    private var hidden = false
    private var shown = false
    private var closed = false

    fun sync(paths: List<String>, lastEntries: List<HostFileEntry>, lastLoading: Boolean, showHidden: Boolean, visible: Boolean) {
        if (closed) return
        val old = records.toList()
        records.clear()
        paths.forEachIndexed { index, path ->
            val record = old.getOrNull(index)?.takeIf { it.value.path == path && hidden == showHidden } ?: Record(HostColumn(path))
            if (index == paths.lastIndex) {
                record.epoch++; record.job?.cancel(); record.job = null
                record.value = HostColumn(path, lastEntries, lastLoading)
                record.loaded = !lastLoading
            }
            records += record
        }
        old.filterNot { it in records }.forEach { it.epoch++; it.job?.cancel() }
        hidden = showHidden
        shown = visible
        if (!visible) stopReads()
        publish()
        if (visible) records.dropLast(1).forEach(::load)
    }

    fun hide() { shown = false; stopReads(); publish() }
    fun retry(path: String) {
        records.dropLast(1).firstOrNull { it.value.path == path }?.let { it.loaded = false; load(it) }
    }
    private fun stopReads() {
        records.forEach { record ->
            record.epoch++; record.job?.cancel(); record.job = null
            if (!record.loaded) record.value = record.value.copy(loading = false)
        }
    }
    private fun load(record: Record) {
        if (closed || !shown || record.loaded || record.job != null) return
        val version = ++record.epoch
        record.value = record.value.copy(loading = true, error = null)
        publish()
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = reader.read(record.value.path, hidden)
                if (!closed && shown && record in records && record.epoch == version) {
                    record.loaded = true
                    record.value = HostColumn(record.value.path, result.entries, loading = false)
                    publish()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (!closed && shown && record in records && record.epoch == version) {
                    record.loaded = true // Retry is explicit, rather than looping on a failed ancestor.
                    record.value = record.value.copy(loading = false, error = error.message ?: "Failed to read directory")
                    publish()
                }
            } finally { if (record.epoch == version) record.job = null }
        }
        record.job = task
        task.start()
    }
    private fun publish() { mutable.value = records.map { it.value } }
    override fun close() { closed = true; hide(); scope.cancel() }
}

/** Temporary chain while navigation awaits the server's canonical breadcrumbs, including UNC roots. */
internal fun hostAncestorPaths(path: String): List<String> {
    if (path.startsWith('/')) {
        val parts = path.split('/').filter(String::isNotEmpty)
        return listOf("/") + parts.indices.map { "/" + parts.take(it + 1).joinToString("/") }
    }
    val separator = if ('\\' in path) "\\" else "/"
    if (Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(path)) {
        val root = path.take(2) + separator
        val parts = path.drop(3).split(separator).filter(String::isNotEmpty)
        return listOf(root) + parts.indices.map { root + parts.take(it + 1).joinToString(separator) }
    }
    if (path.startsWith("\\\\")) {
        val parts = path.drop(2).split('\\').filter(String::isNotEmpty)
        if (parts.size < 2) return listOf(path)
        return (2..parts.size).map { "\\\\" + parts.take(it).joinToString("\\") }
    }
    return listOf(path)
}
