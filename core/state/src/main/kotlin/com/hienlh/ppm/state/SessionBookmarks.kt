package com.hienlh.ppm.state

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.deleteSessionBookmark
import com.hienlh.ppm.client.putSessionBookmark
import com.hienlh.ppm.client.sessionBookmarks
import com.hienlh.ppm.protocol.SessionBookmark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant

/** What the bookmarks of a chat ask of the server, so the store runs in a unit test without one. */
interface SessionBookmarksBackend {
    suspend fun list(project: String, sessionId: String): List<SessionBookmark>
    suspend fun put(project: String, sessionId: String, turnId: String, turnN: Int?, label: String): List<SessionBookmark>
    suspend fun delete(project: String, sessionId: String, turnId: String): List<SessionBookmark>

    companion object {
        fun api(api: ApiClient): SessionBookmarksBackend = object : SessionBookmarksBackend {
            override suspend fun list(project: String, sessionId: String) = api.sessionBookmarks(project, sessionId)
            override suspend fun put(project: String, sessionId: String, turnId: String, turnN: Int?, label: String) =
                api.putSessionBookmark(project, sessionId, turnId, turnN, label)
            override suspend fun delete(project: String, sessionId: String, turnId: String) = api.deleteSessionBookmark(project, sessionId, turnId)
        }
    }
}

/**
 * Each open chat's bookmarked turns (`session-bookmarks-store.ts`): loaded once per chat, changed
 * at once, and put back with [onError] when the server refuses the change. A server that has no
 * bookmarks route leaves the chat's list unloaded (null), so nothing shows. One thread.
 */
class SessionBookmarks(
    private val backend: () -> SessionBookmarksBackend?,
    private val scope: CoroutineScope,
    private val onError: (String) -> Unit = {},
    private val now: () -> Instant = Instant::now,
) {
    private val _lists = MutableStateFlow<Map<String, List<SessionBookmark>>>(emptyMap())

    /** By [key]: a chat's list, absent until it loads. */
    val lists: StateFlow<Map<String, List<SessionBookmark>>> = _lists.asStateFlow()

    private val loading = HashSet<String>()

    fun list(project: String, sessionId: String): List<SessionBookmark>? = _lists.value[key(project, sessionId)]

    /** Asks for the chat's list the first time it is wanted; a failed ask is tried again next time. */
    fun load(project: String, sessionId: String) {
        val key = key(project, sessionId)
        if (_lists.value.containsKey(key) || !loading.add(key)) return
        val server = backend() ?: return run { loading.remove(key) }
        scope.launch {
            try {
                val list = server.list(project, sessionId)
                _lists.update { if (it.containsKey(key)) it else it + (key to list) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                loading.remove(key)
            }
        }
    }

    /** Bookmarks the turn, or takes its bookmark off when it has one. */
    fun toggle(project: String, sessionId: String, turnId: String, turnN: Int?, label: String) {
        val key = key(project, sessionId)
        val list = _lists.value[key].orEmpty()
        if (list.any { it.turnId == turnId }) return remove(project, sessionId, turnId)
        val next = (list + SessionBookmark(turnId, turnN, label, now().toString()))
            .sortedBy { it.turnN ?: Int.MAX_VALUE }
        save(key, next) { it.put(project, sessionId, turnId, turnN, label) }
    }

    fun remove(project: String, sessionId: String, turnId: String) {
        val key = key(project, sessionId)
        val next = _lists.value[key].orEmpty().filter { it.turnId != turnId }
        save(key, next) { it.delete(project, sessionId, turnId) }
    }

    private fun save(key: String, next: List<SessionBookmark>, call: suspend (SessionBookmarksBackend) -> List<SessionBookmark>) {
        val server = backend() ?: return onError("Could not save the bookmark")
        val before = _lists.value[key]
        _lists.update { it + (key to next) }
        scope.launch {
            try {
                val saved = call(server)
                _lists.update { it + (key to saved) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _lists.update { it + (key to (before ?: emptyList())) }
                onError("Could not save the bookmark")
            }
        }
    }

    /** Forgets every list: another server. */
    fun clear() {
        _lists.value = emptyMap()
    }

    companion object {
        fun key(project: String, sessionId: String) = "$project\n$sessionId"
    }
}
