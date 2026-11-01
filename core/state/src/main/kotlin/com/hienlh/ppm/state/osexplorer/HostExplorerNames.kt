package com.hienlh.ppm.state.osexplorer

import com.hienlh.ppm.state.files.FileMutationItem
import com.hienlh.ppm.state.files.FileMutationOperation
import com.hienlh.ppm.state.files.FileTransferFailure

/** What pressing Enter in the inline name field does (`commitInline`, use-explorer-actions.ts). */
sealed interface HostNameCommit {
    /** A rename to the name the entry already has: the field closes and nothing is sent. */
    data object Unchanged : HostNameCommit

    /** The name is refused: [message] goes under the field, which stays open and sends nothing. */
    data class Refused(val message: String) : HostNameCommit

    /** Send [name], the typed name without the spaces round it. */
    data class Send(val name: String) : HostNameCommit
}

/** [typed] in the name field, for a rename of [existing] (null for a new file or folder), on a host of [platform]. */
fun hostNameCommit(typed: String, existing: String?, platform: String?): HostNameCommit {
    val name = typed.trim()
    if (existing != null && name == existing) return HostNameCommit.Unchanged
    validateHostEntryName(name, platform)?.let { return HostNameCommit.Refused(it) }
    return HostNameCommit.Send(name)
}

/** A toast: [title] over a smaller [description]. */
data class HostToast(val title: String, val description: String?)

/**
 * The toast for a failed [item], in the web's words (`createEntry` and `renameEntry`, explorer-actions-mutate.ts, and `transfer()`,
 * explorer-actions-clipboard.ts): what failed as the title and why under it, which is the server's hint when it gave one, else its
 * message. A move or a copy is titled by the entry's name (the source's, also when Keep both saved it under another) and, for a
 * Replace, by the step it stopped at ([FileTransferFailure]).
 */
fun hostFailureToast(item: FileMutationItem): HostToast = HostToast(
    when (item.operation) {
        FileMutationOperation.CREATE_DIRECTORY -> "Could not create folder"
        FileMutationOperation.CREATE_FILE -> "Could not create file"
        FileMutationOperation.RENAME -> "Rename failed"
        FileMutationOperation.MOVE, FileMutationOperation.COPY -> transferFailureTitle(item)
        else -> "File operation failed"
    },
    item.error,
)

private fun transferFailureTitle(item: FileMutationItem): String {
    val name = item.path.split('/', '\\').lastOrNull { it.isNotEmpty() } ?: item.path
    return when (item.transferFailure) {
        FileTransferFailure.EXISTING_NOT_REMOVED -> "Could not remove existing $name"
        FileTransferFailure.REPLACEMENT_NOT_WRITTEN -> "$name was removed but the replacement could not be written"
        null -> (if (item.operation == FileMutationOperation.COPY) "Copy" else "Move") + " failed: " + name
    }
}

/**
 * What Paste and a drop in a window say once something went, `pasteInto` and `transferInto` (use-explorer-actions.ts): "Moved 2
 * items" or "Copied 1 item", for what succeeded. Null when nothing did, or when [result] is not a transfer's. A drop on the places,
 * the breadcrumb and the project tree says nothing, and does not ask for this.
 */
fun hostTransferSuccessToast(result: HostFileActionResult): HostToast? {
    val operation = result.operation ?: return null
    val count = result.succeeded
    if (count <= 0) return null
    return HostToast((if (operation == HostTransferOperation.COPY) "Copied " else "Moved ") + count + " item" + (if (count == 1) "" else "s"), null)
}
