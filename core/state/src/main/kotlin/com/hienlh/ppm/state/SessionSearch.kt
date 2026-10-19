package com.hienlh.ppm.state

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.searchChats
import com.hienlh.ppm.protocol.ChatSearchPage
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.sortedForList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What one [HistoryList] shows, given the project's shared first page. */
data class HistoryListState(
    /** What is typed; it takes effect 300 ms after the last change. */
    val query: String = "",
    /** The tag chip picked: only sessions with that tag are shown. */
    val tag: Int? = null,
    /** The search's first page while a search is in effect (empty until it answers); null without one. */
    val searchPage: List<SessionInfo>? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    /** What "Load more" brought. */
    val extra: List<SessionInfo> = emptyList(),
    /** What the server last said of more pages, once this list asked it. */
    val pageHasMore: Boolean? = null,
) {
    /** Every session of the list, given the project's shared first page. */
    fun sessions(shared: List<SessionInfo>): List<SessionInfo> {
        val base = searchPage ?: shared
        val known = base.mapTo(HashSet()) { it.id }
        return (base + extra.filter { it.id !in known }).sortedForList()
    }

    /** Those under the tag chip. */
    fun shown(sessions: List<SessionInfo>): List<SessionInfo> {
        val tag = tag ?: return sessions
        return sessions.filter { it.tag?.id == tag }
    }

    fun hasMore(shared: Boolean): Boolean = pageHasMore ?: (if (searchPage == null) shared else false)
}

/**
 * One list of a project's chats, as each list on the web keeps its own (the sidebar's
 * `useSessionHistory`, every chat's `chat-history-bar.tsx`): the project's first page, which all
 * lists share, or its own title search (`?q=`), plus whatever its "Load more" brought. Nothing
 * here changes the shared page or another list. Driven from one thread.
 */
class HistoryList(private val api: ApiClient, private val project: String, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(HistoryListState())
    val state: StateFlow<HistoryListState> = _state.asStateFlow()

    private var pending: Job? = null

    /** Bumped whenever the query in effect changes, so an older answer is dropped. */
    private var generation = 0

    /** The query in effect: what was typed, once typing stopped. */
    private var applied = ""

    var query: String
        get() = _state.value.query
        set(value) {
            if (value == _state.value.query) return
            _state.update { it.copy(query = value) }
            schedule()
        }

    var tag: Int?
        get() = _state.value.tag
        set(value) = _state.update { it.copy(tag = value) }

    fun sessions(shared: List<SessionInfo>) = _state.value.sessions(shared)

    fun shown(sessions: List<SessionInfo>) = _state.value.shown(sessions)

    fun hasMore(shared: Boolean) = _state.value.hasMore(shared)

    /**
     * Back to the first page (the refresh button): a search is asked again at once. Without one,
     * the caller reads the shared page again.
     */
    suspend fun reload() {
        pending?.cancel()
        apply(query.trim())
    }

    suspend fun loadMore(shared: List<SessionInfo>, sharedHasMore: Boolean) {
        if (!hasMore(sharedHasMore) || _state.value.loadingMore) return
        val mine = generation
        // The server puts every pinned session on the first page, so they are not counted.
        val offset = sessions(shared).count { !it.pinned }
        _state.update { it.copy(loadingMore = true) }
        try {
            val page = quietly { api.sessions(project, offset = offset, search = applied.ifEmpty { null }) }
            if (page == null || mine != generation) return
            _state.update { state ->
                val known = ((state.searchPage ?: shared) + state.extra).mapTo(HashSet()) { it.id }
                state.copy(extra = state.extra + page.sessions.filter { it.id !in known }, pageHasMore = page.hasMore)
            }
        } finally {
            _state.update { it.copy(loadingMore = false) }
        }
    }

    /** Applies a change made to sessions everywhere else. */
    fun patch(matches: (SessionInfo) -> Boolean, change: (SessionInfo) -> SessionInfo) {
        _state.update { state ->
            state.copy(
                searchPage = state.searchPage?.map { if (matches(it)) change(it) else it },
                extra = state.extra.map { if (matches(it)) change(it) else it },
            )
        }
    }

    fun remove(id: String) {
        _state.update { state ->
            state.copy(searchPage = state.searchPage?.filter { it.id != id }, extra = state.extra.filter { it.id != id })
        }
    }

    private fun schedule() {
        pending?.cancel()
        val query = query.trim()
        pending = scope.launch {
            delay(300)
            apply(query)
        }
    }

    private suspend fun apply(query: String) {
        generation++
        val mine = generation
        applied = query
        _state.update { it.copy(extra = emptyList(), pageHasMore = null) }
        if (query.isEmpty()) {
            _state.update { it.copy(searchPage = null, loading = false) }
            return
        }
        _state.update { it.copy(searchPage = it.searchPage ?: emptyList(), loading = true) }
        try {
            // A failed search keeps what is shown, as on the web.
            val page = quietly { api.sessions(project, search = query) }
            if (page == null || mine != generation) return
            _state.update { it.copy(searchPage = page.sessions, pageHasMore = page.hasMore) }
        } finally {
            if (mine == generation) _state.update { it.copy(loading = false) }
        }
    }
}

data class ChatSearchState(
    /** What is typed; the server is asked 300 ms after the last change. */
    val query: String = "",
    /** The answer for what is typed; null while nothing is. */
    val page: ChatSearchPage? = null,
    /**
     * From a keystroke until its first answer; asking again does not count, or an empty list
     * would blink every second.
     */
    val loading: Boolean = false,
)

/**
 * The sidebar's search over titles and messages (`GET …/chat/search`, `useChatSearch`). The
 * server indexes messages as they are searched, and an answer tells of the indexing it set off
 * only from the next answer on — so this asks again every second until two answers agree and
 * nothing is being indexed, and matches show up as the index fills rather than on the next
 * keystroke. Driven from one thread.
 */
class ChatSearch(private val api: ApiClient, private val project: String, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(ChatSearchState())
    val state: StateFlow<ChatSearchState> = _state.asStateFlow()

    private var pending: Job? = null

    var query: String
        get() = _state.value.query
        set(value) {
            if (value == _state.value.query) return
            _state.update { it.copy(query = value) }
            start(300)
        }

    /** Asks again now (the refresh button). */
    fun reload() = start(0)

    private fun start(wait: Long) {
        pending?.cancel()
        val query = query.trim()
        if (query.isEmpty()) {
            _state.update { it.copy(page = null, loading = false) }
            return
        }
        _state.update { it.copy(loading = true) }
        pending = scope.launch {
            delay(wait)
            var last: ChatSearchPage? = null
            while (true) {
                val answer = ask(query)
                if (!answer.indexing && answer == last) break
                last = answer
                delay(1_000)
            }
        }
    }

    private suspend fun ask(query: String): ChatSearchPage {
        // A failed search shows no matches, as on the web.
        val answer = quietly { api.searchChats(project, query) } ?: ChatSearchPage()
        _state.update { it.copy(page = answer, loading = false) }
        return answer
    }
}

/** Null when [block] fails; a cancellation still cancels. */
internal suspend fun <T> quietly(block: suspend () -> T): T? = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    null
}
