package com.hienlh.ppm.app.ui.files

import com.hienlh.ppm.state.files.FileTreeNode
import com.hienlh.ppm.state.files.fileDropDestination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class ExplorerInlineEdit(val id: Long, val parent: String, val directory: Boolean, val node: FileTreeNode? = null)
internal data class ExplorerMutationState(
    val edit: ExplorerInlineEdit? = null,
    val deleting: FileTreeNode? = null,
    val busy: Boolean = false,
    val editError: String? = null,
    val deleteError: String? = null,
    val error: String? = null,
    val pasting: Boolean = false,
)

/** A mounted tree owns prompts only; FileMutations owns writes, clipboard and invalidation. */
internal class ExplorerMutationSession(val project: String?, parent: CoroutineScope) {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val mutable = MutableStateFlow(ExplorerMutationState())
    val state = mutable.asStateFlow()
    private var nextId = 0L
    private var closed = false

    fun create(parent: String, directory: Boolean) {
        if (!available()) return
        mutable.value = ExplorerMutationState(edit = ExplorerInlineEdit(++nextId, parent, directory))
    }

    fun rename(node: FileTreeNode) {
        if (!available()) return
        mutable.value = ExplorerMutationState(edit = ExplorerInlineEdit(++nextId, node.path.substringBeforeLast('/', ""), node.isDirectory, node))
    }

    fun askDelete(node: FileTreeNode) {
        if (available()) mutable.value = ExplorerMutationState(deleting = node)
    }

    fun cancelEdit() { if (available()) mutable.value = mutable.value.copy(edit = null, editError = null) }
    fun cancelDelete() { if (available()) mutable.value = mutable.value.copy(deleting = null, deleteError = null) }
    fun clearError() { mutable.value = mutable.value.copy(error = null) }
    fun edited() { if (available()) mutable.value = mutable.value.copy(editError = null) }

    fun submit(edit: ExplorerInlineEdit, value: String, callbacks: ExplorerMutationCallbacks): Job? {
        if (!available(callbacks) || mutable.value.edit != edit) return null
        val name = value.trim()
        if (name.isEmpty() || name == edit.node?.name) { cancelEdit(); return null }
        mutable.value = mutable.value.copy(busy = true, editError = null)
        return scope.launch {
            try {
                val node = edit.node
                if (node == null) callbacks.create(edit.parent, name, edit.directory) else callbacks.rename(node.path, name)
                if (!closed) mutable.value = mutable.value.copy(edit = null)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (!closed) mutable.value = mutable.value.copy(editError = error.message ?: "Could not save name") }
            finally { if (!closed) mutable.value = mutable.value.copy(busy = false) }
        }
    }

    fun confirmDelete(callbacks: ExplorerMutationCallbacks): Job? {
        val node = mutable.value.deleting ?: return null
        if (!available(callbacks)) return null
        mutable.value = mutable.value.copy(busy = true, deleteError = null)
        return scope.launch {
            try {
                callbacks.delete(node.path)
                if (!closed) mutable.value = mutable.value.copy(deleting = null)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (!closed) mutable.value = mutable.value.copy(deleteError = error.message ?: "Could not delete ${node.name}") }
            finally { if (!closed) mutable.value = mutable.value.copy(busy = false) }
        }
    }

    fun paste(directory: String, callbacks: ExplorerMutationCallbacks): Job? {
        if (!available(callbacks) || !callbacks.canPaste) return null
        mutable.value = mutable.value.copy(busy = true, pasting = true, error = null)
        return scope.launch {
            try { callbacks.paste(directory) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (!closed) mutable.value = mutable.value.copy(error = error.message ?: "Could not paste files") }
            finally { if (!closed) mutable.value = mutable.value.copy(busy = false, pasting = false) }
        }
    }

    /** A dragged row dropped on [directory] ("" is the root): null, and nothing sent, for the drops the web refuses (itself, a child of its own, its own folder). */
    fun move(source: String, directory: String, callbacks: ExplorerMutationCallbacks): Job? {
        val destination = fileDropDestination(source, directory) ?: return null
        if (!available(callbacks)) return null
        mutable.value = mutable.value.copy(busy = true, error = null)
        return scope.launch {
            try { callbacks.move(source, destination) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (!closed) mutable.value = mutable.value.copy(error = error.message ?: "Could not move ${source.substringAfterLast('/')}") }
            finally { if (!closed) mutable.value = mutable.value.copy(busy = false) }
        }
    }

    fun clipboard(paths: List<String>, cut: Boolean, callbacks: ExplorerMutationCallbacks) {
        if (!available(callbacks) || paths.isEmpty()) return
        try {
            if (cut) callbacks.cut(paths) else callbacks.copy(paths)
            mutable.value = mutable.value.copy(error = null)
        } catch (error: Exception) { mutable.value = mutable.value.copy(error = error.message ?: "Could not copy files") }
    }

    private fun available(callbacks: ExplorerMutationCallbacks? = null) =
        !closed && project != null && !mutable.value.busy && (callbacks == null || (callbacks.project == project && !callbacks.busy))

    fun close() { closed = true; scope.cancel(); mutable.value = ExplorerMutationState() }
}
