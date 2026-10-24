package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.CodexAccount
import com.hienlh.ppm.protocol.UsageInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the Codex usage panel shows (codex-usage-panel.tsx): the managed accounts and each one's
 * quota. Only the newest read lands, and a poll stays out of the way of a refresh, whose live
 * read it would otherwise overwrite with the cached one. Main thread only.
 */
class CodexAccountsData(private val source: Source) {
    interface Source {
        suspend fun accounts(): List<CodexAccount>

        suspend fun usages(): Map<String, UsageInfo>

        suspend fun setStatus(id: String, status: String)

        suspend fun setDailyGuard(id: String, enabled: Boolean)
    }

    data class State(
        val accounts: List<CodexAccount> = emptyList(),
        val usages: Map<String, UsageInfo> = emptyMap(),
        val loading: Boolean = false,
        /** The account whose switch or Daily guard is mid-flight. */
        val togglingId: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var request = 0
    private var refreshing = false

    /** The accounts, then their quota; a failure keeps what was there. */
    suspend fun load() {
        if (refreshing) return
        val mine = ++request
        _state.update { it.copy(loading = true) }
        try {
            val accounts = source.accounts()
            if (mine != request) return
            _state.update { it.copy(accounts = accounts) }
            val usages = source.usages()
            if (mine == request) _state.update { it.copy(usages = usages) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            if (mine == request) _state.update { it.copy(loading = false) }
        }
    }

    /** [refreshChat] makes the server read every account live first; then the quota is read again. */
    suspend fun reload(refreshChat: suspend () -> Unit) {
        val mine = ++request
        refreshing = true
        _state.update { it.copy(loading = true) }
        try {
            refreshChat()
            val usages = source.usages()
            if (mine == request) _state.update { it.copy(usages = usages) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            refreshing = false
            if (mine == request) _state.update { it.copy(loading = false) }
        }
    }

    /** Switches an account on or off, then reads the accounts again; the server's refusal, or null. */
    suspend fun toggle(id: String, status: String): String? =
        change(id, "Could not change the account") { source.setStatus(id, if (status == "disabled") "active" else "disabled") }

    /** Turns an account's Daily guard the other way; the server's refusal, or null. */
    suspend fun toggleDailyGuard(id: String, enabled: Boolean): String? =
        change(id, "Could not change Daily guard") { source.setDailyGuard(id, !enabled) }

    private suspend fun change(id: String, fallback: String, patch: suspend () -> Unit): String? {
        _state.update { it.copy(togglingId = id) }
        return try {
            patch()
            val accounts = source.accounts()
            _state.update { it.copy(accounts = accounts) }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message?.takeIf { it.isNotEmpty() } ?: fallback
        } finally {
            _state.update { it.copy(togglingId = null) }
        }
    }

    companion object {
        /** How often an open panel reads again, as the chat header does. */
        const val POLL_MILLIS = 120_000L
    }
}
