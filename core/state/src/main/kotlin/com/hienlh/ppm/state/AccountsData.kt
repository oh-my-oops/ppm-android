package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.AccountInfo
import com.hienlh.ppm.protocol.AccountUsageEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What an accounts panel shows (use-accounts-data.ts): every Claude account's windows, the
 * accounts themselves, and which one the rotation picks next — each applied on its own, as one
 * endpoint failing (usage often does) must not blank the rest — and which accounts' figures just
 * changed, lit for a moment. Main thread only; [scope] times the light.
 */
class AccountsData(private val scope: CoroutineScope, private val source: Source) {
    interface Source {
        suspend fun usages(): List<AccountUsageEntry>

        suspend fun accounts(): List<AccountInfo>

        suspend fun active(): AccountInfo?

        suspend fun setStatus(id: String, status: String)
    }

    data class State(
        val usages: List<AccountUsageEntry> = emptyList(),
        val accounts: List<AccountInfo> = emptyList(),
        val activeAccountId: String? = null,
        /** Only the very first load, so a refresh does not blank the list. */
        val initialLoading: Boolean = true,
        val refreshing: Boolean = false,
        /** Accounts whose utilisation changed on the last refresh. */
        val flashIds: Set<String> = emptySet(),
        /** The account whose switch is mid-flight. */
        val togglingId: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var previous: List<AccountUsageEntry> = emptyList()
    private var flash: Job? = null

    suspend fun reload() {
        val isRefresh = previous.isNotEmpty()
        _state.update { if (isRefresh) it.copy(refreshing = true) else it.copy(initialLoading = true) }
        val (usages, accounts, active) = coroutineScope {
            val usages = async { settle { source.usages() } }
            val accounts = async { settle { source.accounts() } }
            val active = async { settle { source.active() } }
            Triple(usages.await(), accounts.await(), active.await())
        }
        usages.getOrNull()?.let { next ->
            if (isRefresh) {
                val before = previous.associate { it.accountId to utilisationKey(it) }
                val changed = next.filter { before[it.accountId] != utilisationKey(it) }.map { it.accountId }.toSet()
                if (changed.isNotEmpty()) {
                    _state.update { it.copy(flashIds = changed) }
                    flash?.cancel()
                    flash = scope.launch {
                        delay(FLASH_MILLIS)
                        _state.update { it.copy(flashIds = emptySet()) }
                    }
                }
            }
            previous = next
            _state.update { it.copy(usages = next) }
        }
        accounts.getOrNull()?.let { list -> _state.update { it.copy(accounts = list) } }
        if (active.isSuccess) _state.update { it.copy(activeAccountId = active.getOrNull()?.id) }
        _state.update { it.copy(initialLoading = false, refreshing = false) }
    }

    /**
     * Switches an account on or off, then reloads. Answers the server's message rather than
     * throwing: enabling a parked account makes the server prove its token first, which can take
     * most of a minute and fail with words that say what to do next.
     */
    suspend fun toggle(id: String, status: String): String? {
        _state.update { it.copy(togglingId = id) }
        var error: String? = null
        try {
            source.setStatus(id, if (status == "disabled") "active" else "disabled")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message
        }
        _state.update { it.copy(togglingId = null) }
        reload()
        return error
    }

    /** One result of `Promise.allSettled`: the value, or the failure that stands in for it. */
    private suspend fun <T> settle(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    companion object {
        const val FLASH_MILLIS = 1_500L

        /** Utilisation across every window, as one comparable value (`utilisationKey`). */
        private fun utilisationKey(entry: AccountUsageEntry): List<Any?> {
            val u = entry.usage
            return listOf(u.session?.utilization, u.weekly?.utilization, u.weeklyOpus?.utilization, u.weeklySonnet?.utilization) +
                u.weeklyScoped.map { "${it.label}:${it.utilization}" }
        }
    }
}
