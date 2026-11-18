package com.hienlh.ppm.state

import com.hienlh.ppm.client.TurnErrorCodes
import com.hienlh.ppm.client.TurnWindowPage
import com.hienlh.ppm.client.TurnWindowQuery
import com.hienlh.ppm.client.WindowTurn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Which end of the history window a page goes to. */
enum class LoadDirection { OLDER, NEWER }

/**
 * The history window (`history-window-model.ts`): a block of a session's turns shown on their
 * own, after a jump to a turn the live list has not loaded. Never spliced into the live messages,
 * which assume they end at the newest one; the live chat keeps streaming underneath untouched.
 */
sealed interface HistoryView {
    data object Live : HistoryView

    data class Window(
        val turns: List<WindowTurn>,
        val items: List<ChatItem>,
        val hasOlder: Boolean,
        val hasNewer: Boolean,
        val loadingOlder: Boolean = false,
        val loadingNewer: Boolean = false,
        /** The turn the jump asked for: landed on once, after the first layout. */
        val targetTurnId: String,
        /** Bumped by every jump into the window, so asking for the same turn again lands again. */
        val landing: Int,
        val latestTurnId: String?,
        /** The page that last failed and why, so the ends stop asking until the reader retries. */
        val loadError: Pair<LoadDirection, String>? = null,
    ) : HistoryView
}

object HistoryWindowModel {
    /** Most turns the window keeps drawn; past this it drops turns from the far end. */
    const val MAX_WINDOW_TURNS = 30

    /** A compaction marker carries no turn of its own; it goes with the message before it. */
    private fun turnOfEach(items: List<ChatItem>): List<String?> {
        var current: String? = null
        return items.map { item -> (item.turnId ?: current).also { current = it } }
    }

    private fun itemsOf(items: List<ChatItem>, keep: Set<String>): List<ChatItem> {
        val turns = turnOfEach(items)
        return items.filterIndexed { i, _ -> turns[i]?.let { it in keep } == true }
    }

    fun items(page: TurnWindowPage): List<ChatItem> = page.messages.mapNotNull(ChatItem::fromHistory)

    fun enter(view: HistoryView, page: TurnWindowPage, targetTurnId: String): HistoryView.Window = HistoryView.Window(
        turns = page.turns,
        items = items(page),
        hasOlder = page.hasOlder,
        hasNewer = page.hasNewer,
        targetTurnId = targetTurnId,
        landing = if (view is HistoryView.Window) view.landing + 1 else 1,
        latestTurnId = page.latestTurnId,
    )

    fun loading(view: HistoryView, direction: LoadDirection): HistoryView = when (view) {
        is HistoryView.Window -> if (direction == LoadDirection.OLDER) view.copy(loadingOlder = true, loadError = null) else view.copy(loadingNewer = true, loadError = null)
        HistoryView.Live -> view
    }

    fun failed(view: HistoryView, direction: LoadDirection, error: String): HistoryView = when (view) {
        is HistoryView.Window -> if (direction == LoadDirection.OLDER) {
            view.copy(loadingOlder = false, loadError = direction to error)
        } else {
            view.copy(loadingNewer = false, loadError = direction to error)
        }
        HistoryView.Live -> view
    }

    /** Adds a page at one end, then drops turns past the cap from the other end (`grow`). */
    fun loaded(view: HistoryView, direction: LoadDirection, page: TurnWindowPage): HistoryView {
        if (view !is HistoryView.Window) return view
        val have = view.turns.map { it.turnId }.toSet()
        val fresh = page.turns.filter { it.turnId !in have }
        val freshItems = itemsOf(items(page), fresh.map { it.turnId }.toSet())
        val older = direction == LoadDirection.OLDER
        var turns = if (older) fresh + view.turns else view.turns + fresh
        var items = if (older) freshItems + view.items else view.items + freshItems
        var hasOlder = if (older) page.hasOlder else view.hasOlder
        var hasNewer = if (older) view.hasNewer else page.hasNewer
        if (turns.size > MAX_WINDOW_TURNS) {
            // Loading upward drops from the bottom and the reverse: the far end is what the reader left.
            turns = if (older) turns.take(MAX_WINDOW_TURNS) else turns.takeLast(MAX_WINDOW_TURNS)
            items = itemsOf(items, turns.map { it.turnId }.toSet())
            if (older) hasNewer = true else hasOlder = true
        }
        return view.copy(
            turns = turns,
            items = items,
            hasOlder = hasOlder,
            hasNewer = hasNewer,
            loadingOlder = if (older) false else view.loadingOlder,
            loadingNewer = if (older) view.loadingNewer else false,
            latestTurnId = page.latestTurnId,
        )
    }

    /**
     * Whether the live list now holds everything the window shows at its bottom, so the reader can
     * be handed back to it (`canMergeToLive`): the window's last turn there whole — a later turn has
     * started, or it is the newest and nothing streams. Matched by turn id, never message id.
     */
    fun canMergeToLive(view: HistoryView.Window, live: List<ChatItem>, liveStreaming: Boolean): Boolean {
        val last = view.turns.lastOrNull() ?: return false
        val start = live.indexOfFirst { it.turnId == last.turnId }
        if (start < 0 || live[start].role != ChatItem.Role.USER) return false
        for (i in start + 1 until live.size) {
            val m = live[i]
            val turnId = m.turnId
            if (if (turnId != null) turnId != last.turnId else m.role == ChatItem.Role.USER) return true
        }
        return !liveStreaming && last.turnId == view.latestTurnId
    }
}

/**
 * One chat's history window (`use-history-window.ts`): fetches blocks of turns from the turn index
 * and owns the view. Every answer is checked against the generation it was asked in, so a page
 * still in flight when the window is left, re-entered or the chat switches session never lands.
 * Driven from one thread.
 */
class HistoryWindowController(
    private val scope: CoroutineScope,
    private val fetch: suspend (TurnWindowQuery) -> TurnWindowPage,
) {
    enum class OpenResult { OPENED, GONE, FAILED, STALE }

    private val _view = MutableStateFlow<HistoryView>(HistoryView.Live)
    val view: StateFlow<HistoryView> = _view.asStateFlow()

    private var generation = 0
    private val inFlight = HashSet<LoadDirection>()

    fun exit() {
        generation++
        inFlight.clear()
        _view.value = HistoryView.Live
    }

    /** Fetches the block around [turnId] and shows it; answers once it is in or refused. */
    suspend fun open(turnId: String): OpenResult {
        val gen = ++generation
        val page = try {
            withTimeoutOrNull(OPEN_TIMEOUT_MILLIS) { fetch(TurnWindowQuery.Around(turnId)) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (gen != generation) return OpenResult.STALE
            return if (TurnErrorCodes.gone(error)) OpenResult.GONE else OpenResult.FAILED
        }
        if (gen != generation) return OpenResult.STALE
        if (page == null) return OpenResult.FAILED
        _view.value = HistoryWindowModel.enter(_view.value, page, turnId)
        return OpenResult.OPENED
    }

    fun loadOlder() = load(LoadDirection.OLDER, retrying = false)

    fun loadNewer() = load(LoadDirection.NEWER, retrying = false)

    /** Asks again for the page that failed; the ends stop asking after a failure. */
    fun retry(direction: LoadDirection) = load(direction, retrying = true)

    private fun load(direction: LoadDirection, retrying: Boolean) {
        val current = _view.value as? HistoryView.Window ?: return
        if (current.loadError != null && !retrying) return
        val older = direction == LoadDirection.OLDER
        if (older && (!current.hasOlder || current.loadingOlder)) return
        if (!older && (!current.hasNewer || current.loadingNewer)) return
        val edge = (if (older) current.turns.firstOrNull() else current.turns.lastOrNull()) ?: return
        if (!inFlight.add(direction)) return
        val gen = generation
        _view.value = HistoryWindowModel.loading(_view.value, direction)
        scope.launch {
            try {
                val page = fetch(if (older) TurnWindowQuery.Before(edge.turnId, PAGE_TURNS) else TurnWindowQuery.After(edge.turnId, PAGE_TURNS))
                if (gen == generation) _view.value = HistoryWindowModel.loaded(_view.value, direction, page)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (gen == generation) _view.value = HistoryWindowModel.failed(_view.value, direction, error.message ?: "Could not load more turns")
            } finally {
                if (gen == generation) inFlight.remove(direction)
            }
        }
    }

    companion object {
        /** How long a jump waits for its block before it says the turn could not be loaded. */
        const val OPEN_TIMEOUT_MILLIS = 20_000L

        /** Turns fetched per page while scrolling. */
        const val PAGE_TURNS = 3
    }
}
