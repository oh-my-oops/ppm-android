package com.hienlh.ppm.state.osexplorer

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.hostFilesystem
import com.hienlh.ppm.protocol.HostFilesystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HostFilesystemState(val host: HostFilesystem? = null, val loading: Boolean = false, val error: String? = null)

/** One cache per server; opening several windows never repeats the host's platform probes. */
class HostFilesystemStore(
    private val read: suspend (Boolean) -> HostFilesystem,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    constructor(api: ApiClient, scope: CoroutineScope) : this({ refresh -> api.hostFilesystem(refresh) }, scope)
    private val owner = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val mutable = MutableStateFlow(HostFilesystemState())
    val state: StateFlow<HostFilesystemState> = mutable.asStateFlow()
    private var fetchedAt: Long? = null
    private var closed = false

    fun load(refresh: Boolean = false) {
        if (closed || mutable.value.loading) return
        if (!refresh && fetchedAt?.let { clock() - it < 60_000 } == true) return
        mutable.value = mutable.value.copy(loading = true, error = null)
        owner.launch {
            try {
                val host = read(refresh)
                if (!closed) {
                    fetchedAt = clock()
                    mutable.value = HostFilesystemState(host)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (!closed) mutable.value = mutable.value.copy(loading = false, error = failure.message ?: "Failed to read host info")
            }
        }
    }
    override fun close() { closed = true; owner.cancel() }
}
