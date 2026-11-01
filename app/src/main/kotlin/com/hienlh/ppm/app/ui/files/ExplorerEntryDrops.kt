package com.hienlh.ppm.app.ui.files

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.DragModifierKeys
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.osexplorer.HostTransferPromptHost
import com.hienlh.ppm.app.ui.osexplorer.reportTransfer
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.protocol.ProjectInfo
import com.hienlh.ppm.state.files.FileMutationReport
import com.hienlh.ppm.state.files.FileMutations
import com.hienlh.ppm.state.osexplorer.HostFileActions
import com.hienlh.ppm.state.osexplorer.HostTransferOperation
import com.hienlh.ppm.state.osexplorer.HostTransferPrompts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Where the tree's blank area and its header send a drop: the web's `usePathDropTarget` on the tree's container, which wraps its header,
 * whose runner is the transfer an explorer window's Paste runs. The dragged rows and the project folder go as absolute paths ([run]:
 * the collision prompt, Replace to Trash first, a refresh of every folder it touched), and either Ctrl or Alt held at the drop makes it
 * a copy ([copyHeld]). A drop the web refuses, rows that are in the folder already moved there or the folder itself or one above it
 * dropped on it, is not taken, silently ([explorerRootDropTaken]). A drop on a row is not this: it keeps the tree's own single-path
 * move. [run] is given the prompts of this drop's batch; [Dialogs] is where they show, and has to be composed once the drops are
 * attached ([attach], [detach]).
 */
class ExplorerEntryDrops internal constructor(
    /** The project folder, absolute: where a drop on the blank area writes, and what the dragged rows' paths are under. */
    val root: String,
    private val scope: CoroutineScope,
    private val copyHeld: () -> Boolean = { DragModifierKeys.copy },
    private val prompts: HostTransferPromptHost = HostTransferPromptHost(),
    private val run: suspend (paths: List<String>, directory: String, copy: Boolean, prompts: HostTransferPrompts) -> Unit,
) {
    /** A drop is being carried out; the tree takes no other, and is busy, until it is done. */
    var running by mutableStateOf(false)
        private set

    fun attach() = prompts.attach()

    fun detach() = prompts.detach()

    @Composable
    fun Dialogs() = prompts.Dialogs()

    /** Whether [drag] let go on the blank area or the header now would be taken, with the modifier keys as they are. */
    internal fun takes(drag: ExplorerRowDrag): Boolean = !running && explorerRootDropTaken(drag, copyHeld(), root)

    /** The drop, with the modifier keys as they are at the moment it is let go. False when it is refused, which hands the rows back. */
    internal fun dropOnRoot(drag: ExplorerRowDrag): Boolean {
        val copy = copyHeld()
        if (running || !explorerRootDropTaken(drag, copy, root)) return false
        running = true
        scope.launch {
            try { run(drag.absolutePaths, root, copy, prompts.prompts()) }
            finally { running = false }
        }
        return true
    }
}

/**
 * ExplorerBody wiring for [ExplorerTree]'s `entryDrops`: transfers through [HostFileActions], the code the OS Explorer window's drops
 * and Paste go through, and tells every tree, window and open file what it changed ([AppModel.filesMutated]). Null until the
 * server, its file service and the project are there.
 */
@Composable
internal fun rememberExplorerEntryDrops(model: AppModel, project: ProjectInfo?): ExplorerEntryDrops? {
    val client = model.api ?: return null
    val files = model.fileMutations ?: return null
    val info = project ?: return null
    val scope = rememberCoroutineScope()
    val drops = remember(client, files, info.path) {
        explorerEntryDrops(client, files, info.path, scope, toast = model::toast) { report -> if (model.api === client) model.filesMutated(report) }
    }
    DisposableEffect(drops) {
        drops.attach()
        onDispose { drops.detach() }
    }
    return drops
}

/**
 * The drops of [root] with the transfer behind them: [HostFileActions] over [client], which reports what it changed to [onChanged]
 * and answers each item that did not go with a toast, through the report the OS Explorer window's Paste and drops use ([reportTransfer]);
 * an error that is not an item's own, a toast of its own. Success is silent, as the web's is.
 */
internal fun explorerEntryDrops(
    client: ApiClient, files: FileMutations, root: String, scope: CoroutineScope,
    copyHeld: () -> Boolean = { DragModifierKeys.copy },
    prompts: HostTransferPromptHost = HostTransferPromptHost(),
    toast: (ErrorToastMessage) -> Unit,
    onChanged: (FileMutationReport) -> Unit,
): ExplorerEntryDrops {
    val core = HostFileActions(client, files, if ('\\' in root) "\\" else "/", onChanged = onChanged)
    return ExplorerEntryDrops(root, scope, copyHeld, prompts) { paths, directory, copy, batch ->
        try {
            reportTransfer(core.transfer(paths, directory, if (copy) HostTransferOperation.COPY else HostTransferOperation.MOVE, batch), announce = false, toast)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { toast(ErrorToastMessage("File operation failed", error.message)) }
    }
}
