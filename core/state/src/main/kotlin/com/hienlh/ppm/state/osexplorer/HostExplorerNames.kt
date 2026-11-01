package com.hienlh.ppm.state.osexplorer

import com.hienlh.ppm.state.files.FileMutationItem
import com.hienlh.ppm.state.files.FileMutationOperation

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
 * The toast for a failed [item], in the web's words (`createEntry` and `renameEntry`, explorer-actions-mutate.ts): what failed
 * as the title and why under it, which is the server's hint when it gave one, else its message.
 */
fun hostFailureToast(item: FileMutationItem): HostToast = HostToast(
    when (item.operation) {
        FileMutationOperation.CREATE_DIRECTORY -> "Could not create folder"
        FileMutationOperation.CREATE_FILE -> "Could not create file"
        FileMutationOperation.RENAME -> "Rename failed"
        else -> "File operation failed"
    },
    item.error,
)
