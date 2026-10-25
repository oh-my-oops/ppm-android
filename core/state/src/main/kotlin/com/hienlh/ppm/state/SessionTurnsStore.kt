package com.hienlh.ppm.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Each session's turns ([SessionTurns]), so the Review tab can name the turn that wrote a block
 * (`session-turns-store.ts` and `use-session-turns.ts`). An open chat publishes what it shows,
 * which puts a turn here the moment it is asked; with no chat open the Review tab fetches the
 * session's messages itself ([follow]) — again only when a block names a call no fetch has placed
 * yet, and not more often than every [REFETCH_MILLIS] while the agent is still writing.
 */
class SessionTurnsStore(private val now: () -> Long = ::monotonicMillis) {
    data class Entry(
        val turns: List<SessionTurn>,
        /** Each call's turn. */
        val byCall: Map<String, SessionTurn>,
        /** An open chat keeps it current; otherwise it is as the last fetch left it. */
        val live: Boolean,
    )

    private val _entries = MutableStateFlow<Map<String, Entry>>(emptyMap())
    val entries: StateFlow<Map<String, Entry>> = _entries.asStateFlow()

    /** By session: when it was last fetched, and the calls the last fetch that landed could not place. */
    private val fetched = HashMap<String, Pair<Long, String?>>()

    private fun signature(turns: List<SessionTurn>) =
        turns.joinToString("|") { "${it.n}:${it.messageId}:${it.calls.size}:${it.calls.lastOrNull().orEmpty()}:${it.prompt.length}" }

    /** Replaces the session's turns, unless they say the same thing. */
    fun publish(sessionId: String, turns: List<SessionTurn>, live: Boolean) {
        val prev = _entries.value[sessionId]
        if (prev != null && prev.live == live && signature(prev.turns) == signature(turns)) return
        _entries.update { it + (sessionId to Entry(turns, SessionTurns.byCall(turns), live)) }
    }

    /** The chat showing the session closed: its turns stay, to be fetched again when they fall behind. */
    fun release(sessionId: String) {
        val prev = _entries.value[sessionId]
        if (prev?.live != true) return
        _entries.update { it + (sessionId to prev.copy(live = false)) }
    }

    /**
     * The Review tab's half: fetches the session's turns with [load] when the blocks on screen
     * name [calls] that no turn here holds and no open chat keeps them current. Run again — the last
     * run cancelled — whenever what it reads changes, as the web's effect is.
     */
    suspend fun follow(sessionId: String, calls: List<String>, load: suspend () -> List<SessionTurn>) {
        val entry = _entries.value[sessionId]
        if (entry?.live == true) return
        val byCall = entry?.byCall.orEmpty()
        val unplaced = calls.filter { it !in byCall }.sorted().joinToString("\n")
        if (entry != null && unplaced.isEmpty()) return
        val last = fetched[sessionId]
        // A fetch already found nothing for these: only a new call is worth asking again for.
        if (entry != null && last?.second == unplaced) return
        if (last != null) delay(maxOf(0L, last.first + REFETCH_MILLIS - now()))
        fetched[sessionId] = now() to last?.second
        val turns = try {
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        val placed = SessionTurns.byCall(turns)
        fetched[sessionId] = now() to unplaced.split("\n").filter { it.isNotEmpty() && it !in placed }.joinToString("\n")
        // A chat that opened meanwhile knows better.
        if (_entries.value[sessionId]?.live == true) return
        publish(sessionId, turns, live = false)
    }

    companion object {
        const val REFETCH_MILLIS = 8_000L
    }
}
