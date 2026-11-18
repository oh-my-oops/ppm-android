package com.hienlh.ppm.state.schedules

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What every view of the schedules shows (`useSchedulesOverviewStore`'s state). */
data class SchedulesFeedState(
    val list: List<ScheduleOverview> = emptyList(),
    val loaded: Boolean = false,
    /** Set when the last read failed and nothing was ever read. */
    val error: String? = null,
    val now: Long = 0,
)

/**
 * Every schedule as the Schedules window lists it (`schedules-overview-store.ts`), shared by the
 * rail's button, the drawer's tile and the window, so one read feeds all of them. A burst of
 * changes shares one read, then one more after it; a read that started before a local write must
 * not put old rows back. Driven from one thread, as every controller here.
 */
class SchedulesFeed(
    private val scope: CoroutineScope,
    private val fetch: suspend () -> List<ScheduleOverview>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(SchedulesFeedState(now = clock()))
    val state: StateFlow<SchedulesFeedState> = _state.asStateFlow()

    private var inFlight: Job? = null

    /** A change arrived while a read was out: that read may predate it, so read again when it lands. */
    private var dirty = false

    /** Bumped by every local write. */
    private var writes = 0

    fun refetch() {
        if (inFlight?.isActive == true) {
            dirty = true
            return
        }
        val startedAfter = writes
        inFlight = scope.launch {
            try {
                val list = fetch()
                if (startedAfter != writes) dirty = true
                else _state.update { it.copy(list = list, loaded = true, error = null, now = clock()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!_state.value.loaded) _state.update { it.copy(error = e.message?.ifBlank { null } ?: "Could not read the schedules") }
            } finally {
                inFlight = null
                if (dirty) {
                    dirty = false
                    refetch()
                }
            }
        }
    }

    /** Swaps one row for the server's answer to a write, without waiting for the next read. */
    fun put(row: ScheduleOverview) {
        writes++
        _state.update { s ->
            s.copy(list = if (s.list.any { it.id == row.id }) s.list.map { if (it.id == row.id) row else it } else s.list + row)
        }
    }

    fun remove(id: Int) {
        writes++
        _state.update { s -> s.copy(list = s.list.filter { it.id != id }) }
    }

    /** Moves `now` so "in 8h 10m" and the 24-hour count stay true without a read. */
    fun tick() = _state.update { it.copy(now = clock()) }

    /** Another server: what was read from the one before is not this one's. */
    fun clear() {
        inFlight?.cancel()
        inFlight = null
        dirty = false
        writes++
        _state.value = SchedulesFeedState(now = clock())
    }
}
