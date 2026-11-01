package com.hienlh.ppm.state.files

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.client.copyProjectFile
import com.hienlh.ppm.client.createProjectFile
import com.hienlh.ppm.client.deleteProjectFile
import com.hienlh.ppm.client.fsCopy
import com.hienlh.ppm.client.fsMove
import com.hienlh.ppm.client.moveProjectFile
import com.hienlh.ppm.client.renameProjectFile
import com.hienlh.ppm.client.writeFile
import com.hienlh.ppm.protocol.FileTransferResult
import com.hienlh.ppm.protocol.ProjectInfo
import com.hienlh.ppm.state.workspace.absoluteProjectPath
import com.hienlh.ppm.state.workspace.relativeProjectPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class FileClipboardOperation { CUT, COPY }

/** Shared by the tree and OS explorer windows on ONE server. All paths are host-absolute. */
data class FileClipboard(val paths: List<String>, val operation: FileClipboardOperation, val origin: ProjectInfo? = null)

enum class FileMutationOperation { CREATE_FILE, CREATE_DIRECTORY, RENAME, DELETE, MOVE, COPY, WRITE }
enum class FileMutationOutcome { SUCCEEDED, FAILED, PARTIAL }

/**
 * Which step of a Replace a failed transfer item stopped at, so a UI can say it in its own words (the web's `transfer()` titles them
 * apart). Null for the copy or the move that simply failed, which is the usual case and has none.
 */
enum class FileTransferFailure {
    /** The entry already there could not be removed (Trash first, then, with no Trash and the user's word, for good); nothing was written. */
    EXISTING_NOT_REMOVED,

    /** The entry already there is gone, and what should have replaced it could not be written. */
    REPLACEMENT_NOT_WRITTEN,
}

/** Paths are relative when project is present, otherwise absolute; write follows its path's form. */
data class FileMutationItem(
    val operation: FileMutationOperation,
    val project: String?,
    val path: String,
    val destination: String? = null,
    val outcome: FileMutationOutcome,
    val error: String? = null,
    val httpStatus: Int? = null,
    val code: String? = null,
    val transfer: FileTransferResult? = null,
    /** Only on a failed MOVE or COPY of a Replace, and only when it failed at one of its steps; see [FileTransferFailure]. */
    val transferFailure: FileTransferFailure? = null,
)

data class FileMutationReport(
    val items: List<FileMutationItem> = emptyList(),
    /** Absolute parents attempted, for invalidating every tree/window even after partial failure. */
    val affectedDirectories: Set<String> = emptySet(),
    /** Only acknowledged successes are listed; a cancelled request may still have reached the server. */
    val cancelled: Boolean = false,
) {
    val failures: List<FileMutationItem> get() = items.filter { it.outcome != FileMutationOutcome.SUCCEEDED }
    val changed: List<FileMutationItem> get() = items.filter { it.outcome != FileMutationOutcome.FAILED }
    val succeeded: Boolean get() = !cancelled && failures.isEmpty()
    val partial: Boolean get() = changed.isNotEmpty() && !succeeded
}

data class FileMutationState(
    val clipboard: FileClipboard? = null,
    val busy: Boolean = false,
    val report: FileMutationReport? = null,
) {
    val error: String? get() = report?.failures?.firstOrNull()?.error
}

/**
 * One instance per connected server, shared across projects/windows; call on one main thread.
 * Suspend methods run in the caller's scope. Overlapping mutations are rejected, never queued.
 * Results publish after each acknowledged step; cancellation propagates and always releases busy.
 * This owns no tree/tab state: UI consumes the report to refresh parents and update open tabs.
 */
class FileMutations(private val api: ApiClient) {
    private val mutable = MutableStateFlow(FileMutationState())
    val state: StateFlow<FileMutationState> = mutable.asStateFlow()

    fun setClipboard(clipboard: FileClipboard?) {
        require(clipboard == null || clipboard.paths.all(::absoluteHostPath)) { "Clipboard paths must be absolute." }
        mutable.value = mutable.value.copy(clipboard = clipboard?.copy(paths = clipboard.paths.toList()))
    }
    fun setProjectClipboard(project: ProjectInfo, paths: List<String>, operation: FileClipboardOperation) {
        setClipboard(FileClipboard(paths.map { absoluteProjectPath(project.path, it) }, operation, project))
    }
    fun clearClipboard() = setClipboard(null)
    fun clearReport() { if (!mutable.value.busy) mutable.value = mutable.value.copy(report = null) }

    /** Content needs a second request. A failed write reports the successfully created empty file. */
    suspend fun create(project: ProjectInfo, path: String, isDirectory: Boolean = false, content: String? = null): FileMutationReport {
        require(!isDirectory || content == null) { "A directory cannot have text content." }
        return mutate {
            val operation = if (isDirectory) FileMutationOperation.CREATE_DIRECTORY else FileMutationOperation.CREATE_FILE
            if (step(operation, project, path) { api.createProjectFile(project.name, path, isDirectory); null } && content != null) {
                write(project, path, content)
            }
        }
    }

    suspend fun rename(project: ProjectInfo, oldPath: String, newPath: String): FileMutationReport = mutate {
        // InlineTreeInput skips an unchanged name, avoiding the server's collision error.
        if (oldPath != newPath) step(FileMutationOperation.RENAME, project, oldPath, newPath) {
            api.renameProjectFile(project.name, oldPath, newPath); null
        }
    }

    /** UI confirms first. Always permanent, matching project FileActions rather than OS trash. */
    suspend fun delete(project: ProjectInfo, path: String): FileMutationReport = delete(project, listOf(path))
    suspend fun delete(project: ProjectInfo, paths: List<String>): FileMutationReport {
        val snapshot = paths.toList()
        return mutate { for (path in snapshot) step(FileMutationOperation.DELETE, project, path) { api.deleteProjectFile(project.name, path); null } }
    }

    /** With a project, both paths are relative; with null, both are absolute. Destination includes the name. */
    suspend fun move(project: ProjectInfo?, source: String, destination: String): FileMutationReport = mutate {
        transfer(project, source, destination, move = true)
    }
    suspend fun copy(project: ProjectInfo?, source: String, destination: String): FileMutationReport = mutate {
        transfer(project, source, destination, move = false)
    }

    suspend fun writeFile(project: ProjectInfo?, path: String, content: String): FileMutationReport = writeFiles(project, linkedMapOf(path to content))

    /** Ordered writes stop at the first failure. Report retains every file already written (Replace All). */
    suspend fun writeFiles(project: ProjectInfo?, contents: Map<String, String>): FileMutationReport {
        val snapshot = contents.toList()
        return mutate { for ((path, content) in snapshot) if (!write(project, path, content)) break }
    }

    /**
     * Project targetDirectory is relative (empty = root); null project means an absolute directory.
     * Each source inside the target project uses project routes, regardless of clipboard.origin.
     * Other sources, and all pastes into an OS window, use /api/fs. No collision prompt/retry.
     * Like web, attempt every item and clear CUT after completion, even if some items failed.
     */
    suspend fun paste(project: ProjectInfo?, targetDirectory: String = ""): FileMutationReport {
        require(project != null || absoluteHostPath(targetDirectory)) { "An OS paste needs an absolute directory." }
        val clipboard = mutable.value.clipboard
        return mutate {
            if (clipboard != null) {
                for (source in clipboard.paths) {
                    val name = source.split('/', '\\').lastOrNull { it.isNotEmpty() } ?: source
                    val relative = project?.let { relativeProjectPath(it.path, source) }
                    val destination = if (project != null) {
                        if (targetDirectory.isEmpty()) name else "$targetDirectory/$name"
                    } else absoluteProjectPath(targetDirectory, name)
                    if (relative != null) transfer(project, relative, destination, clipboard.operation == FileClipboardOperation.CUT)
                    else transfer(null, source, if (project == null) destination else absoluteProjectPath(project.path, destination), clipboard.operation == FileClipboardOperation.CUT)
                }
                // Do not clear a newer clipboard that another window replaced during these requests.
                if (clipboard.operation == FileClipboardOperation.CUT && mutable.value.clipboard === clipboard) clearClipboard()
            }
        }
    }

    private suspend fun mutate(action: suspend Batch.() -> Unit): FileMutationReport {
        check(!mutable.value.busy) { "A file mutation is already running." }
        currentCoroutineContext().ensureActive()
        val batch = Batch()
        mutable.value = mutable.value.copy(busy = true, report = batch.report())
        try { batch.action() }
        catch (error: CancellationException) { batch.cancelled = true; throw error }
        finally { mutable.value = mutable.value.copy(busy = false, report = batch.report()) }
        return batch.report()
    }

    private inner class Batch {
        val items = mutableListOf<FileMutationItem>()
        val directories = linkedSetOf<String>()
        var cancelled = false
        fun report() = FileMutationReport(items.toList(), directories.toSet(), cancelled)

        suspend fun write(project: ProjectInfo?, path: String, content: String): Boolean =
            step(FileMutationOperation.WRITE, if (EDITOR_ABSOLUTE.containsMatchIn(path)) null else project, path) {
                api.writeFile(project?.name, path, content); null
            }

        suspend fun transfer(project: ProjectInfo?, source: String, destination: String, move: Boolean): Boolean =
            step(if (move) FileMutationOperation.MOVE else FileMutationOperation.COPY, project, source, destination) {
                if (project != null) {
                    if (move) api.moveProjectFile(project.name, source, destination) else api.copyProjectFile(project.name, source, destination)
                } else {
                    require(absoluteHostPath(source) && absoluteHostPath(destination)) { "Filesystem transfers need absolute paths." }
                    if (move) api.fsMove(source, destination) else api.fsCopy(source, destination)
                }
            }

        suspend fun step(operation: FileMutationOperation, project: ProjectInfo?, path: String, destination: String? = null,
                         request: suspend () -> FileTransferResult?): Boolean {
            currentCoroutineContext().ensureActive()
            listOfNotNull(path, destination).forEach {
                val absolute = if (project == null) it else absoluteProjectPath(project.path, it)
                if (absoluteHostPath(absolute)) directories += hostParent(absolute)
            }
            val result = try {
                val transfer = request()
                val partial = operation == FileMutationOperation.MOVE && transfer?.sourceRemoved == false
                FileMutationItem(operation, project?.name, path, destination,
                    if (partial) FileMutationOutcome.PARTIAL else FileMutationOutcome.SUCCEEDED,
                    error = if (partial) "Copied to ${transfer?.destination}, but the source was not removed." else null,
                    transfer = transfer)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                FileMutationItem(operation, project?.name, path, destination, FileMutationOutcome.FAILED,
                    error.message ?: "File operation failed", (error as? PpmError.Server)?.status, (error as? PpmError.Server)?.code)
            }
            items += result
            mutable.value = mutable.value.copy(report = report())
            return result.outcome == FileMutationOutcome.SUCCEEDED
        }
    }
}

enum class FileDropRejection { INVALID_PATH, SELF, DESCENDANT, SAME_PARENT }

/** Project-tree drag guard only. Paste deliberately lets the server report collisions, as web does. */
fun validateFileDrop(sourcePath: String, targetDirectory: String): FileDropRejection? {
    if (absoluteHostPath(sourcePath) || absoluteHostPath(targetDirectory)) return FileDropRejection.INVALID_PATH
    val source = sourcePath.trimEnd('/')
    val target = targetDirectory.trimEnd('/')
    fun valid(path: String) = !absoluteHostPath(path) && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
    if (source.isEmpty() || !valid(source) || (target.isNotEmpty() && !valid(target))) return FileDropRejection.INVALID_PATH
    if (source == target) return FileDropRejection.SELF
    if (target.startsWith("$source/")) return FileDropRejection.DESCENDANT
    if (parentPath(source) == target) return FileDropRejection.SAME_PARENT
    return null
}

fun fileDropDestination(sourcePath: String, targetDirectory: String): String? {
    if (validateFileDrop(sourcePath, targetDirectory) != null) return null
    val name = sourcePath.trimEnd('/').substringAfterLast('/')
    return targetDirectory.trimEnd('/').let { if (it.isEmpty()) name else "$it/$name" }
}

private val EDITOR_ABSOLUTE = Regex("""^(/|[A-Za-z]:[/\\])""")
private fun absoluteHostPath(path: String) = EDITOR_ABSOLUTE.containsMatchIn(path) || path.startsWith("\\\\")
private fun hostParent(path: String): String {
    val end = path.trimEnd('/', '\\').lastIndexOfAny(charArrayOf('/', '\\'))
    return when {
        end < 0 -> path
        end == 0 || (end == 2 && path.getOrNull(1) == ':') -> path.substring(0, end + 1)
        else -> path.substring(0, end)
    }
}
