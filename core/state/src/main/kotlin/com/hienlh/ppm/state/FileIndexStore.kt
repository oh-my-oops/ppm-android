package com.hienlh.ppm.state

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.fileIndex
import com.hienlh.ppm.client.searchFileIndex
import com.hienlh.ppm.protocol.FileEntry
import com.hienlh.ppm.protocol.FileIndexAnswer
import com.hienlh.ppm.protocol.jsLowercase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the `@` picker lists (`filtered` in file-picker.tsx): with nothing typed the first fifty
 * entries; else the first fifty whose path or name holds what is typed, in any case, in the
 * list's own order. Compared by UTF-16 unit, as JavaScript's `includes` compares.
 */
class FilePickerList(entries: List<FileEntry>, query: String) {
    val items: List<FileEntry> = if (query.isEmpty()) {
        entries.take(LIMIT)
    } else {
        val needle = jsLowercase(query)
        val found = ArrayList<FileEntry>()
        for (entry in entries) {
            if (jsLowercase(entry.path).contains(needle) || jsLowercase(entry.name).contains(needle)) {
                found += entry
                if (found.size == LIMIT) break
            }
        }
        found
    }

    /** ArrowUp (-1) or ArrowDown (+1) from [index], round from either end. */
    fun step(index: Int, delta: Int): Int = stepRound(index, delta, items.size)

    companion object {
        const val LIMIT = 50
    }
}

/**
 * The open project's flat file list (the index half of the web's file-store): read when something
 * is about to show it, kept while files change, marked stale by a change and read again only when
 * next shown — on a large project it is a download of megabytes. One project at a time, as the app
 * has one open. Driven from one thread, as every controller here is.
 */
class FileIndexStore(private val api: ApiClient, private val scope: CoroutineScope) {
    enum class Status { IDLE, LOADING, READY, FAILED }

    data class State(
        /** The project the list is of, or is being read for. */
        val project: String? = null,
        val entries: List<FileEntry> = emptyList(),
        val status: Status = Status.IDLE,
        /** Files changed since the list was read: it stays, and is read again before it is next shown. */
        val stale: Boolean = false,
        /** The list is too long to send: it is searched on the server ([searchRemote]). */
        val remote: Boolean = false,
        /** Moves each time a list lands, so a search on the server asks again. */
        val revision: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Moves whenever the list is dropped or replaced, so an answer still on its way cannot refill it. */
    private var epoch = 0

    /** Counts changes, so an answer can tell whether one arrived while it was read. */
    private var changes = 0
    private var loading: Job? = null

    /** Pickers showing the list now. Only while one is open does a rebuild cost a download. */
    private var readers = 0

    /** Reads [project]'s list; a read already on its way for it is joined rather than repeated. A list on screen stays there while its replacement is read. */
    fun load(project: String): Job {
        loading?.takeIf { it.isActive && _state.value.project == project }?.let { return it }
        val epoch = ++epoch
        val changesSeen = changes
        val current = _state.value
        if (current.project != project) {
            _state.value = State(project = project, status = Status.LOADING, revision = current.revision)
        } else if (current.status != Status.READY) {
            _state.value = current.copy(status = Status.LOADING)
        }
        val job = scope.launch {
            val answer = try {
                api.fileIndex(project, REMOTE_FROM)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A failed refresh keeps the list it was replacing; only a first read reports it.
                if (epoch == this@FileIndexStore.epoch && _state.value.status != Status.READY) {
                    _state.value = _state.value.copy(status = Status.FAILED)
                }
                return@launch
            }
            if (epoch != this@FileIndexStore.epoch) return@launch
            val state = _state.value
            _state.value = when (answer) {
                is FileIndexAnswer.Entries -> state.copy(entries = answer.entries, remote = false)
                is FileIndexAnswer.TooLarge -> state.copy(entries = emptyList(), remote = true)
            }.copy(status = Status.READY, revision = state.revision + 1, stale = changes != changesSeen)
        }
        loading = job
        return job
    }

    /** Reads the list if it is missing, failed or stale: for a view about to show it. */
    fun ensure(project: String) {
        val state = _state.value
        if (state.project == project && state.status == Status.READY && !state.stale) return
        load(project)
    }

    /** For a view showing the list for as long as it is open: [ensure], and a rebuild on the server is read at once until the returned close is called. */
    fun openReader(project: String): () -> Unit {
        readers++
        ensure(project)
        var open = true
        return {
            if (open) {
                open = false
                readers--
            }
        }
    }

    /** `files:index-changed`: the server rebuilt [project]'s list and it names other paths. */
    fun rebuilt(project: String) {
        markStale(project)
        if (readers == 0 || project != _state.value.project) return
        // A read already on its way may carry the list from before the rebuild: it lands stale.
        val running = loading?.takeIf { it.isActive }
        if (running == null) {
            load(project)
            return
        }
        scope.launch {
            running.join()
            if (readers > 0 && project == _state.value.project) ensure(project)
        }
    }

    /** `file:changed`: keep the list, but read it again before it is next shown. */
    fun markStale(project: String) {
        if (project != _state.value.project) return
        changes++
        if (!_state.value.stale) _state.value = _state.value.copy(stale = true)
    }

    /** Another project was opened: its list is not this one. */
    fun reset() {
        epoch++
        loading = null
        _state.value = State(revision = _state.value.revision)
    }

    /** The best entries for [query] from the server, for a list too long to hold; null when the search failed, so what is shown stays. */
    suspend fun searchRemote(query: String): List<FileEntry>? {
        val state = _state.value
        val project = state.project ?: return null
        if (!state.remote) return null
        return try {
            api.searchFileIndex(project, query, directories = true, limit = REMOTE_LIMIT)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** From this many entries the list is searched on the server (`REMOTE_FILE_SEARCH_FROM_ENTRIES`). */
        const val REMOTE_FROM = 50_000

        /** How many entries a search on the server answers (`REMOTE_FILE_SEARCH_LIMIT`). */
        const val REMOTE_LIMIT = 100
    }
}
