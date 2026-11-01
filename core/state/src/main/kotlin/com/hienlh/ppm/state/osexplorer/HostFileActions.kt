package com.hienlh.ppm.state.osexplorer

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.client.fsCopy
import com.hienlh.ppm.client.fsDelete
import com.hienlh.ppm.client.fsInfo
import com.hienlh.ppm.client.fsMkdir
import com.hienlh.ppm.client.fsMove
import com.hienlh.ppm.client.fsRename
import com.hienlh.ppm.client.fsTouch
import com.hienlh.ppm.state.files.FileClipboard
import com.hienlh.ppm.state.files.FileClipboardOperation
import com.hienlh.ppm.state.files.FileMutationItem
import com.hienlh.ppm.state.files.FileMutationOperation
import com.hienlh.ppm.state.files.FileMutationOutcome
import com.hienlh.ppm.state.files.FileMutationReport
import com.hienlh.ppm.state.files.FileMutations
import com.hienlh.ppm.state.files.FileTransferFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class HostTransferOperation { COPY, MOVE }
enum class HostCollisionChoice { SKIP, KEEP_BOTH, REPLACE }
data class HostCollision(val source: String, val destination: String, val name: String)

/** One UI prompt context per batch. Apply-to-all choices belong to the UI, never this owner. */
data class HostTransferPrompts(
    val resolveCollision: suspend (HostCollision) -> HostCollisionChoice = { HostCollisionChoice.SKIP },
    val confirmPermanentOverwrite: suspend (HostCollision) -> Boolean = { false },
    val startBatch: () -> Unit = {},
    val endBatch: () -> Unit = {},
)

data class HostFileActionResult(
    val report: FileMutationReport = FileMutationReport(),
    /** Completed input entries, excluding the preparatory removal in a Replace operation. */
    val succeeded: Int = 0,
    val skipped: List<String> = emptyList(),
    /** Trash refused these paths. UI may separately confirm a new delete(permanent = true). */
    val needsPermanent: List<String> = emptyList(),
    /** What a [HostFileActions.transfer] or [HostFileActions.paste] did with its entries (a paste of a cut moves); null for anything else. */
    val operation: HostTransferOperation? = null,
) {
    val failed: Int get() = report.failures.size
}

data class HostFileActionState(val busy: Boolean = false, val result: HostFileActionResult? = null) {
    val error: String? get() = result?.report?.failures?.firstOrNull()?.error
}

/**
 * One explorer window's writes, on one main thread; no jobs/scope owned here.
 * Clipboard is the same FileMutations instance used by project trees and other windows.
 * Calls suspend in the UI owner's scope. Cancellation propagates after publishing partial work.
 * onChanged runs once in finally for every accepted batch, including failures/cancellation.
 * onChanged and prompt start/end hooks must not throw. Wire onChanged to main.filesMutated.
 */
class HostFileActions(
    private val api: ApiClient,
    private val sharedFiles: FileMutations,
    val separator: String,
    private val platform: String? = null,
    private val onChanged: (FileMutationReport) -> Unit = {},
) {
    init { require(separator == "/" || separator == "\\") { "Expected the host path separator." } }
    private val mutable = MutableStateFlow(HostFileActionState())
    val state: StateFlow<HostFileActionState> = mutable.asStateFlow()

    fun setClipboard(paths: List<String>, operation: FileClipboardOperation) {
        if (paths.isNotEmpty()) sharedFiles.setClipboard(FileClipboard(paths.toList(), operation))
    }
    fun clearResult() { if (!mutable.value.busy) mutable.value = mutable.value.copy(result = null) }

    suspend fun create(directory: String, name: String, isDirectory: Boolean = false): HostFileActionResult = batch {
        val path = join(directory, name)
        touched += directory
        val operation = if (isDirectory) FileMutationOperation.CREATE_DIRECTORY else FileMutationOperation.CREATE_FILE
        try {
            validateName(name)
            if (isDirectory) api.fsMkdir(path) else api.fsTouch(path)
            succeeded++
            record(FileMutationItem(operation, null, path, outcome = FileMutationOutcome.SUCCEEDED))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { failure(operation, path, null, error) }
    }

    suspend fun rename(path: String, newName: String): HostFileActionResult = batch {
        touched += parent(path)
        var destination = join(parent(path), newName)
        try {
            validateName(newName)
            destination = api.fsRename(path, newName)
            touched += parent(destination)
            succeeded++
            record(FileMutationItem(FileMutationOperation.RENAME, null, path, destination, FileMutationOutcome.SUCCEEDED))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { failure(FileMutationOperation.RENAME, path, destination, error) }
    }

    /** Default is Trash. NO_TRASH is returned for separate UI confirmation, never retried permanently. */
    suspend fun delete(paths: List<String>, permanent: Boolean = false): HostFileActionResult {
        val snapshot = paths.toList()
        return batch {
            for (path in snapshot) {
                currentCoroutineContext().ensureActive()
                touched += parent(path)
                try {
                    remove(path, permanent, countAsSuccess = true)
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    if (!permanent && (error as? PpmError.Server)?.code == "NO_TRASH") needsPermanent += path
                    failure(FileMutationOperation.DELETE, path, null, error)
                }
            }
        }
    }

    suspend fun transfer(paths: List<String>, destinationDirectory: String, operation: HostTransferOperation,
                         prompts: HostTransferPrompts = HostTransferPrompts()): HostFileActionResult {
        val snapshot = paths.toList()
        return batch(prompts) { transferEntries(snapshot, destinationDirectory, operation, prompts) }
    }

    /**
     * A CUT is consumed once nothing failed, and only if it is still the same clipboard. A skipped entry is not a failure, as in the
     * web's `pasteInto`; one that was copied but whose source could not be removed ([FileMutationOutcome.PARTIAL]) is, there it is not.
     */
    suspend fun paste(destinationDirectory: String, prompts: HostTransferPrompts = HostTransferPrompts()): HostFileActionResult {
        val clipboard = sharedFiles.state.value.clipboard
        return batch(prompts) {
            if (clipboard != null) {
                transferEntries(clipboard.paths, destinationDirectory,
                    if (clipboard.operation == FileClipboardOperation.CUT) HostTransferOperation.MOVE else HostTransferOperation.COPY, prompts)
                if (clipboard.operation == FileClipboardOperation.CUT && result().failed == 0 &&
                    sharedFiles.state.value.clipboard === clipboard) sharedFiles.clearClipboard()
            }
        }
    }

    /** Returns a bare candidate name, testing suffixes 2 through 99 inclusive. Only ENOENT is free. */
    suspend fun freeName(directory: String, name: String): String? {
        for (number in 2..99) {
            currentCoroutineContext().ensureActive()
            val candidate = suffixedName(name, number)
            try { api.fsInfo(join(directory, candidate)) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { if ((error as? PpmError.Server)?.code == "ENOENT") return candidate }
        }
        return null
    }

    private suspend fun batch(prompts: HostTransferPrompts? = null, action: suspend Batch.() -> Unit): HostFileActionResult {
        check(!mutable.value.busy) { "A host file action is already running." }
        currentCoroutineContext().ensureActive()
        val batch = Batch()
        mutable.value = HostFileActionState(busy = true, result = batch.result())
        try {
            prompts?.startBatch()
            batch.action()
        } catch (error: CancellationException) { batch.cancelled = true; throw error }
        finally {
            try { prompts?.endBatch() }
            finally {
                val result = batch.result()
                mutable.value = HostFileActionState(result = result)
                onChanged(result.report)
            }
        }
        return batch.result()
    }

    private inner class Batch {
        val items = mutableListOf<FileMutationItem>()
        val touched = linkedSetOf<String>()
        val skipped = mutableListOf<String>()
        val needsPermanent = mutableListOf<String>()
        var succeeded = 0
        var cancelled = false
        var operation: HostTransferOperation? = null
        fun result() = HostFileActionResult(FileMutationReport(items.toList(), touched.toSet(), cancelled),
            succeeded, skipped.toList(), needsPermanent.toList(), operation)
        fun record(item: FileMutationItem) {
            items += item
            mutable.value = mutable.value.copy(result = result())
        }
        fun skip(path: String) {
            skipped += path
            mutable.value = mutable.value.copy(result = result())
        }
        fun failure(operation: FileMutationOperation, path: String, destination: String?, error: Exception, step: FileTransferFailure? = null) {
            val server = error as? PpmError.Server
            record(FileMutationItem(operation, null, path, destination, FileMutationOutcome.FAILED,
                server?.hint ?: error.message ?: "File operation failed", server?.status, server?.code, transferFailure = step))
        }

        suspend fun remove(path: String, permanent: Boolean, countAsSuccess: Boolean = false) {
            currentCoroutineContext().ensureActive()
            val result = api.fsDelete(path, permanent)
            touched += parent(result.removed)
            if (countAsSuccess) succeeded++
            // Preserve this acknowledged change even if a following replacement/copy fails or is cancelled.
            record(FileMutationItem(FileMutationOperation.DELETE, null, result.removed, outcome = FileMutationOutcome.SUCCEEDED))
        }

        suspend fun transferEntries(paths: List<String>, directory: String, operation: HostTransferOperation, prompts: HostTransferPrompts) {
            this.operation = operation
            touched += directory
            for (source in paths) {
                currentCoroutineContext().ensureActive()
                touched += parent(source)
                transferOne(source, directory, operation, prompts)
            }
        }

        suspend fun transferOne(source: String, directory: String, operation: HostTransferOperation, prompts: HostTransferPrompts) {
            val moving = operation == HostTransferOperation.MOVE
            val mutation = if (moving) FileMutationOperation.MOVE else FileMutationOperation.COPY
            val name = source.split('/', '\\').lastOrNull { it.isNotEmpty() } ?: source
            val sameDirectory = parent(source) == directory
            if (moving && sameDirectory) { skip(source); return }
            var destination = join(directory, name)
            // Which step of a Replace a failure belongs to; null until there is one, and for every other way an item can fail.
            var step: FileTransferFailure? = null
            try {
                if (sameDirectory) {
                    destination = join(directory, freeName(directory, name) ?: throw IllegalStateException("No free name available"))
                    runTransfer(source, destination, moving)
                    return
                }
                try { runTransfer(source, destination, moving); return }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    if ((error as? PpmError.Server)?.code != "EEXIST") throw error
                }
                val collision = HostCollision(source, destination, name)
                when (prompts.resolveCollision(collision)) {
                    HostCollisionChoice.SKIP -> { skip(source); return }
                    HostCollisionChoice.KEEP_BOTH ->
                        destination = join(directory, freeName(directory, name) ?: throw IllegalStateException("No free name available"))
                    HostCollisionChoice.REPLACE -> {
                        step = FileTransferFailure.EXISTING_NOT_REMOVED
                        try { remove(destination, permanent = false) }
                        catch (error: CancellationException) { throw error }
                        catch (error: Exception) {
                            if ((error as? PpmError.Server)?.code != "NO_TRASH") throw error
                            if (!prompts.confirmPermanentOverwrite(collision)) { skip(source); return }
                            remove(destination, permanent = true)
                        }
                        step = FileTransferFailure.REPLACEMENT_NOT_WRITTEN
                    }
                }
                runTransfer(source, destination, moving)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { failure(mutation, source, destination, error, step) }
        }

        suspend fun runTransfer(source: String, destination: String, moving: Boolean) {
            currentCoroutineContext().ensureActive()
            val result = if (moving) api.fsMove(source, destination) else api.fsCopy(source, destination)
            val partial = moving && result.sourceRemoved == false
            if (!partial) succeeded++
            record(FileMutationItem(if (moving) FileMutationOperation.MOVE else FileMutationOperation.COPY,
                null, source, result.destination, if (partial) FileMutationOutcome.PARTIAL else FileMutationOutcome.SUCCEEDED,
                error = if (partial) "Copied to " + result.destination + ", but the source was not removed." else null,
                transfer = result))
        }
    }

    private fun validateName(name: String) { validateHostEntryName(name, platform)?.let { throw IllegalArgumentException(it) } }
    private fun join(directory: String, name: String) = directory + (if (directory.endsWith(separator)) "" else separator) + name
    private fun parent(path: String): String {
        val trimmed = if (path.length > separator.length) path.removeSuffix(separator) else path
        val index = trimmed.lastIndexOf(separator)
        if (index < 0) return trimmed
        if (index == 0) return separator
        val head = trimmed.substring(0, index)
        return if (head.endsWith(':')) head + separator else head
    }
}

private fun suffixedName(name: String, number: Int): String {
    val dot = name.lastIndexOf('.')
    return if (dot <= 0) name + " (" + number + ")" else name.substring(0, dot) + " (" + number + ")" + name.substring(dot)
}
