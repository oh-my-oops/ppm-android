package com.hienlh.ppm.state

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.cancelMcpAuth
import com.hienlh.ppm.client.confirmMcpAuth
import com.hienlh.ppm.client.mcpAuthFlow
import com.hienlh.ppm.client.startMcpAuth
import com.hienlh.ppm.client.submitMcpAuthCallback
import com.hienlh.ppm.protocol.McpAuthFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a sign-in asks of the server, so the flow runs in a unit test without one. */
interface McpSignInBackend {
    suspend fun start(server: String): McpAuthFlow
    suspend fun flow(id: String): McpAuthFlow
    suspend fun submitCallback(id: String, url: String): McpAuthFlow
    suspend fun confirm(id: String): McpAuthFlow
    suspend fun cancel(id: String)

    companion object {
        /** The server's, for a server as a chat in [project] sees it. */
        fun api(api: ApiClient, project: String?): McpSignInBackend = object : McpSignInBackend {
            override suspend fun start(server: String) = api.startMcpAuth(server, project)
            override suspend fun flow(id: String) = api.mcpAuthFlow(id)
            override suspend fun submitCallback(id: String, url: String) = api.submitMcpAuthCallback(id, url)
            override suspend fun confirm(id: String) = api.confirmMcpAuth(id)
            override suspend fun cancel(id: String) = api.cancelMcpAuth(id)
        }
    }
}

data class McpSignInState(
    val flow: McpAuthFlow? = null,
    val starting: Boolean = false,
    /** A pasted address or "I've granted access" is on its way. */
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * One MCP sign-in, for as long as its dialog is open (`useMcpSignInFlow`): started at once, read
 * again every two seconds until it settles, and cancelled if the dialog closes first — an
 * abandoned flow would hold a Claude subprocess open for its full ten minutes.
 *
 * Everything runs in [scope], which must outlive the dialog (the app's): a start still out when
 * the dialog closes answers into a flow that is closed, and is cancelled then. Main thread only.
 */
class McpSignInFlow(
    val serverName: String,
    private val backend: McpSignInBackend,
    private val scope: CoroutineScope,
    private val pollMillis: Long = POLL_MILLIS,
    private val onSignedIn: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(McpSignInState())
    val state: StateFlow<McpSignInState> = _state.asStateFlow()

    /** Bumped by every start and by closing, so a start that answers late can neither replace the flow shown nor outlive the dialog. */
    private var generation = 0
    private var reported: String? = null
    private var closed = false
    private var poller: Job? = null
    private var polled: String? = null

    /** Starts the sign-in, or starts it again ("Try again"). */
    fun start() {
        if (closed) return
        val current = ++generation
        _state.value = McpSignInState(starting = true)
        follow()
        scope.launch {
            try {
                val next = backend.start(serverName)
                if (current != generation) {
                    if (next.isOpen) cancelQuietly(next.id)
                    return@launch
                }
                apply(next)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current == generation) _state.update { it.copy(error = message(error)) }
            } finally {
                if (current == generation) _state.update { it.copy(starting = false) }
            }
        }
    }

    /** The dialog went away: whatever is still open is cancelled. */
    fun close() {
        if (closed) return
        closed = true
        generation++
        _state.value.flow?.takeIf { it.isOpen }?.let { cancelQuietly(it.id) }
        poller?.cancel()
        poller = null
        polled = null
    }

    fun submitCallback(url: String) = run { backend.submitCallback(it, url) }

    fun confirm() = run { backend.confirm(it) }

    private fun run(action: suspend (String) -> McpAuthFlow) {
        val current = _state.value.flow ?: return
        _state.update { it.copy(busy = true, error = null) }
        scope.launch {
            try {
                apply(action(current.id))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(error = message(error)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** An outcome never goes back to waiting: a read that left before a submit settled the flow can arrive after it. */
    private fun apply(next: McpAuthFlow) {
        val current = _state.value.flow
        if (current != null && current.id == next.id && !current.isOpen && next.isOpen) return
        _state.update { it.copy(flow = next) }
        follow()
        if (next.status == McpAuthFlow.Status.DONE && reported != next.id) {
            reported = next.id
            onSignedIn(next.serverName)
        }
    }

    /** Reads the open flow again every two seconds, until it settles or another replaces it. */
    private fun follow() {
        val id = _state.value.flow?.takeIf { it.isOpen && !closed }?.id
        if (id == polled) return
        poller?.cancel()
        poller = null
        polled = id
        if (id == null) return
        poller = scope.launch {
            while (true) {
                delay(pollMillis)
                val next = try {
                    backend.flow(id)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // The server no longer knows it (restarted, or cancelled elsewhere): it ends
                    // here too, so the dialog offers "Try again" instead of spinning on.
                    val current = _state.value.flow?.takeIf { it.id == id } ?: return@launch
                    apply(current.copy(status = McpAuthFlow.Status.FAILED, authUrl = null, error = message(error)))
                    return@launch
                }
                apply(next)
            }
        }
    }

    private fun cancelQuietly(id: String) {
        scope.launch {
            try {
                backend.cancel(id)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        const val POLL_MILLIS = 2_000L

        private fun message(error: Exception): String = error.message ?: error.toString()
    }
}
