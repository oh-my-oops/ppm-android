package com.hienlh.ppm.app.ui.files

import com.hienlh.ppm.client.ProjectTransferProgress
import com.hienlh.ppm.state.files.FileTreeNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ExplorerTransferPhase { IDLE, CHOOSING, DOWNLOADING, UPLOADING, DONE, FAILED, CANCELLED }
data class ExplorerTransferState(
    val phase: ExplorerTransferPhase = ExplorerTransferPhase.IDLE,
    val name: String = "",
    val progress: ProjectTransferProgress = ProjectTransferProgress(0, null),
    val message: String? = null,
) {
    val busy: Boolean get() = phase in setOf(ExplorerTransferPhase.CHOOSING, ExplorerTransferPhase.DOWNLOADING, ExplorerTransferPhase.UPLOADING)
}

/** Picker first, then IO. The view owns this lifetime; stale completions cannot reopen it. */
internal class ExplorerTransferSession(parent: CoroutineScope) {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val mutable = MutableStateFlow(ExplorerTransferState())
    val state = mutable.asStateFlow()
    private var waiting: FileTreeNode? = null
    private var job: Job? = null
    private var closed = false
    private var generation = 0L

    fun choose(node: FileTreeNode): Boolean {
        if (closed || mutable.value.busy) return false
        waiting = node
        mutable.value = ExplorerTransferState(ExplorerTransferPhase.CHOOSING, node.name, message = "Choose where to save ${node.name}.")
        return true
    }

    fun chosen(work: suspend (FileTreeNode, (ProjectTransferProgress) -> Unit) -> String): Job? {
        val node = waiting ?: return null
        waiting = null
        return start(ExplorerTransferPhase.DOWNLOADING, node.name, { progress -> work(node, progress) })
    }

    fun upload(name: String, work: suspend ((ProjectTransferProgress) -> Unit) -> String, finished: () -> Unit): Job? {
        if (closed || mutable.value.busy) return null
        return start(ExplorerTransferPhase.UPLOADING, name, work, finished)
    }

    private fun start(phase: ExplorerTransferPhase, name: String, work: suspend ((ProjectTransferProgress) -> Unit) -> String, finished: () -> Unit = {}): Job? {
        if (closed) return null
        val owner = ++generation
        mutable.value = ExplorerTransferState(phase, name)
        val task = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val message = work { progress ->
                    mutable.update { state -> if (!closed && owner == generation && state.phase == phase) state.copy(progress = progress) else state }
                }
                if (!closed && owner == generation) mutable.value = mutable.value.copy(phase = ExplorerTransferPhase.DONE, message = message)
            } catch (error: CancellationException) {
                if (!closed && owner == generation) mutable.value = mutable.value.copy(phase = ExplorerTransferPhase.CANCELLED,
                    message = error.message?.takeIf { it.startsWith("Transfer cancelled.") } ?: "Transfer cancelled.")
                throw error
            } catch (error: Exception) {
                if (!closed && owner == generation) mutable.value = mutable.value.copy(phase = ExplorerTransferPhase.FAILED, message = error.message ?: "Transfer failed")
            } finally { finished() }
        }
        job = task
        return task
    }

    fun failed(message: String) {
        if (!closed && job?.isActive != true) {
            waiting = null
            mutable.value = ExplorerTransferState(ExplorerTransferPhase.FAILED, message = message)
        }
    }
    fun cancel() {
        if (mutable.value.phase == ExplorerTransferPhase.CHOOSING) {
            waiting = null
            mutable.value = ExplorerTransferState()
        } else job?.cancel()
    }
    fun dismiss() { if (!mutable.value.busy) mutable.value = ExplorerTransferState() }
    fun close() { closed = true; waiting = null; generation++; scope.cancel(); mutable.value = ExplorerTransferState() }
}
