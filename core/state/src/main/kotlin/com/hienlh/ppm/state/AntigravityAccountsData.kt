package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.AntigravityAccount
import com.hienlh.ppm.protocol.UsageInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the Antigravity usage panel shows (antigravity-usage-panel.tsx): the managed accounts and
 * each one's quota. Only the newest read lands, and a poll stays out of the way of a refresh.
 * Main thread only.
 */
class AntigravityAccountsData(private val source: Source) {
    interface Source {
        suspend fun accounts(): List<AntigravityAccount>

        suspend fun usages(): Map<String, UsageInfo>

        suspend fun setStatus(id: String, status: String)
    }

    data class State(
        val accounts: List<AntigravityAccount> = emptyList(),
        val usages: Map<String, UsageInfo> = emptyMap(),
        val loading: Boolean = false,
        /** The account whose switch is mid-flight. */
        val togglingId: String? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var request = 0
    private var refreshing = false

    fun setError(message: String?) {
        _state.update { it.copy(error = message) }
    }

    /** The accounts, then their quota; a failure reports its message on the active request. */
    suspend fun load() {
        if (refreshing) return
        val mine = ++request
        _state.update { it.copy(loading = true) }
        try {
            val accounts = source.accounts()
            if (mine != request) return
            _state.update { it.copy(accounts = accounts) }
            val usages = source.usages()
            if (mine == request) _state.update { it.copy(usages = usages, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (mine == request) {
                _state.update { it.copy(error = e.message?.takeIf { msg -> msg.isNotEmpty() } ?: "Failed to load Antigravity usage") }
            }
        } finally {
            if (mine == request) _state.update { it.copy(loading = false) }
        }
    }

    /** [refreshChat] makes the server read live first; then both the accounts and their quota are read again. */
    suspend fun reload(refreshChat: suspend () -> Unit) {
        val mine = ++request
        refreshing = true
        _state.update { it.copy(loading = true) }
        try {
            refreshChat()
            val accounts = source.accounts()
            val usages = source.usages()
            if (mine == request) _state.update { it.copy(accounts = accounts, usages = usages, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (mine == request) {
                _state.update { it.copy(error = e.message?.takeIf { msg -> msg.isNotEmpty() } ?: "Failed to reload Antigravity usage") }
            }
        } finally {
            refreshing = false
            if (mine == request) _state.update { it.copy(loading = false) }
        }
    }

    /** Switches an account on or off, then reads the accounts again; sets [State.error] on failure and returns it, or null. */
    suspend fun toggle(id: String, status: String): String? {
        _state.update { it.copy(togglingId = id, error = null) }
        return try {
            source.setStatus(id, if (status == "disabled") "active" else "disabled")
            val accounts = source.accounts()
            _state.update { it.copy(accounts = accounts) }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message?.takeIf { it.isNotEmpty() } ?: "Could not change the account"
            _state.update { it.copy(error = msg) }
            msg
        } finally {
            _state.update { it.copy(togglingId = null) }
        }
    }

    companion object {
        /** How often an open panel reads again, as the chat header does. */
        const val POLL_MILLIS = 120_000L
    }
}
