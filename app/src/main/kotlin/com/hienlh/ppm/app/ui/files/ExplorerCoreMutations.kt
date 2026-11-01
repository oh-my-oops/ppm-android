package com.hienlh.ppm.app.ui.files

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.hienlh.ppm.protocol.ProjectInfo
import com.hienlh.ppm.state.files.FileClipboardOperation
import com.hienlh.ppm.state.files.FileMutationOutcome
import com.hienlh.ppm.state.files.FileMutationReport
import com.hienlh.ppm.state.files.FileMutations
import com.hienlh.ppm.state.workspace.relativeProjectPath

/**
 * ExplorerBody wiring: pass this result to ExplorerTree(mutations = ...).
 * [onReport] runs for success, failure and cancellation; main invalidates affectedDirectories
 * and reconciles tabs there. Keep it non-throwing and bound to this server instance, including
 * when a request finishes during a project/server switch. Clipboard remains shared in [mutations].
 */
@Composable
fun rememberExplorerMutationCallbacks(
    mutations: FileMutations,
    project: ProjectInfo,
    onReport: (FileMutationReport) -> Unit,
): ExplorerMutationCallbacks {
    val state by mutations.state.collectAsState()
    suspend fun run(operation: suspend () -> FileMutationReport) {
        val before = mutations.state.value.report
        try {
            val result = operation()
            explorerMutationError(result)?.let { throw IllegalStateException(it) }
        } finally {
            // A rejected overlapping request has no report of its own.
            mutations.state.value.report?.takeIf { it !== before }?.let(onReport)
        }
    }
    return ExplorerMutationCallbacks(
        project = project.name,
        create = { parent, name, directory -> run { mutations.create(project, childPath(parent, name), directory) } },
        rename = { path, name -> run { mutations.rename(project, path, childPath(path.substringBeforeLast('/', ""), name)) } },
        delete = { path -> run { mutations.delete(project, path) } },
        cut = { paths -> mutations.setProjectClipboard(project, paths, FileClipboardOperation.CUT) },
        copy = { paths -> mutations.setProjectClipboard(project, paths, FileClipboardOperation.COPY) },
        paste = { directory -> run { mutations.paste(project, directory) } },
        cutPaths = state.clipboard?.takeIf { it.operation == FileClipboardOperation.CUT }?.paths
            ?.mapNotNull { relativeProjectPath(project.path, it) }?.toSet().orEmpty(),
        canPaste = state.clipboard?.paths?.isNotEmpty() == true,
        busy = state.busy,
    )
}

private fun childPath(parent: String, name: String) = if (parent.isEmpty()) name else "$parent/$name"

/** Reports can succeed partly; never discard failures just because some files changed. */
internal fun explorerMutationError(report: FileMutationReport): String? {
    if (report.succeeded) return null
    val completed = report.items.count { it.outcome == FileMutationOutcome.SUCCEEDED }
    return buildString {
        if (report.partial || completed > 0) append("$completed completed; ${report.failures.size} incomplete.\n")
        if (report.cancelled) append("Operation cancelled. Some changes may already have reached the server.\n")
        append(report.failures.joinToString("\n") { "${it.path}: ${it.error ?: "File operation failed"}" })
    }.trimEnd()
}
