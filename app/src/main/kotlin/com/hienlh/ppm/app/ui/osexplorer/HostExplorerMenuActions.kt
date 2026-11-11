package com.hienlh.ppm.app.ui.osexplorer

import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.protocol.HostFileEntry
import com.hienlh.ppm.state.files.FileClipboard
import com.hienlh.ppm.state.files.FileClipboardOperation
import com.hienlh.ppm.state.osexplorer.ExplorerSession
import com.hienlh.ppm.state.osexplorer.HostMenuCommand
import com.hienlh.ppm.state.osexplorer.hostCopied

/**
 * What each row of the explorer's menu does (`use-explorer-actions.ts`). [run] is given the
 * entries the menu was opened on, none for the folder's own menu, and [directory], the folder the
 * menu belongs to: the one the window shows, or the Column view's column it was opened in.
 */
internal class HostExplorerMenuActions(
    private val model: AppModel,
    private val session: ExplorerSession,
    private val commands: HostExplorerCommands?,
    private val uploads: HostUploadPicker,
    private val downloads: HostExplorerDownloads?,
    private val copyToClipboard: (String) -> Unit,
    private val open: (HostFileEntry) -> Unit,
) {
    fun run(command: HostMenuCommand, targets: List<HostFileEntry>, directory: String) {
        // Rows of one entry only are offered with one; the rest act on all of them.
        val single = targets.singleOrNull()
        when (command) {
            HostMenuCommand.NEW_FILE -> commands?.create(false, directory)
            HostMenuCommand.NEW_FOLDER -> commands?.create(true, directory)
            HostMenuCommand.PASTE -> commands?.paste(single?.path ?: directory)
            HostMenuCommand.UPLOAD_FILES -> uploads.files(directory)
            HostMenuCommand.UPLOAD_FOLDER -> uploads.folder(directory)
            HostMenuCommand.OPEN_IN_TERMINAL -> model.openTerminalAt(single?.takeIf { it.isDirectory }?.path ?: directory)
            HostMenuCommand.OPEN -> single?.let(open)
            HostMenuCommand.OPEN_IN_NEW_WINDOW -> single?.let { model.openExplorer(it.path) }
            HostMenuCommand.CUT -> clipboard(targets, FileClipboardOperation.CUT)
            HostMenuCommand.COPY -> clipboard(targets, FileClipboardOperation.COPY)
            HostMenuCommand.RENAME -> single?.let { commands?.rename(it) }
            HostMenuCommand.TRASH -> commands?.trash(targets)
            HostMenuCommand.DELETE_PERMANENTLY -> commands?.permanentlyDelete(targets)
            HostMenuCommand.COPY_PATH, HostMenuCommand.COPY_NAME -> {
                val copied = hostCopied(command, targets, directory)
                copyToClipboard(copied.text)
                model.toast(ErrorToastMessage(copied.message, kind = ErrorToastMessage.Kind.SUCCESS))
            }
            HostMenuCommand.DOWNLOAD -> downloads?.download(targets)
            HostMenuCommand.TOGGLE_PIN -> single?.let { folder ->
                if (session.preferences.pins.value.any { it.path == folder.path }) session.preferences.unpin(folder.path)
                else session.preferences.pin(folder.name, folder.path)
            }
            HostMenuCommand.PROPERTIES -> commands?.property = single
        }
    }

    private fun clipboard(targets: List<HostFileEntry>, operation: FileClipboardOperation) {
        if (targets.isEmpty()) return
        model.fileMutations?.setClipboard(FileClipboard(targets.map { it.path }, operation))
    }
}
