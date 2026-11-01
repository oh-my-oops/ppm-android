package com.hienlh.ppm.state.osexplorer

/**
 * Why a drop of entries on a folder is refused, the web's `DropRejection` (drop-target-decision.ts) less "not-an-entry-drag", which
 * is a question of what the drag carries and so the UI's. A refused drop is silent: no tint while it hovers, and the drag goes back.
 */
enum class EntryDropRefusal { NO_TARGET, SELF_OR_DESCENDANT, SAME_DIRECTORY }

/** The web's `DropDecision`: taken, as a move or a copy, or refused and why. */
sealed interface EntryDropDecision {
    data class Accept(val operation: HostTransferOperation) : EntryDropDecision
    data class Reject(val reason: EntryDropRefusal) : EntryDropDecision
}

/**
 * The web's `decideDrop`: may the dragged [paths] (absolute) land in [targetDir], and as what. [copy] is Ctrl or Alt held.
 * Refused in this order, as the web does: no folder to write into; a path that is the folder or has the folder inside it (a folder
 * dropped into itself, or the project folder or a parent of it dropped on the project's own tree), which would delete it or nest it
 * for ever; and a move of paths that are all in the folder already, which changes nothing. A copy of those is the one way to make
 * `name (2)`, as every OS does, so it is taken.
 */
fun decideEntryDrop(paths: List<String>, targetDir: String?, copy: Boolean): EntryDropDecision {
    if (targetDir.isNullOrEmpty()) return EntryDropDecision.Reject(EntryDropRefusal.NO_TARGET)
    val operation = if (copy) HostTransferOperation.COPY else HostTransferOperation.MOVE
    if (isSelfOrDescendant(paths, targetDir)) return EntryDropDecision.Reject(EntryDropRefusal.SELF_OR_DESCENDANT)
    if (operation == HostTransferOperation.MOVE && allAlreadyIn(paths, targetDir)) return EntryDropDecision.Reject(EntryDropRefusal.SAME_DIRECTORY)
    return EntryDropDecision.Accept(operation)
}

/** Windows paths are case-insensitive: a drive letter and a separator, or a UNC prefix, is the cheap tell (`isWindowsPath`). */
private fun isWindowsPath(path: String): Boolean {
    if (path.startsWith("\\\\")) return true
    return path.length >= 3 && (path[0] in 'a'..'z' || path[0] in 'A'..'Z') && path[1] == ':' && (path[2] == '\\' || path[2] == '/')
}

/**
 * The web's `normalisePath`: a path in a form to compare, one separator, none at the end, in lower case when it is a Windows path.
 * Never one to send to the server, and nothing in it is resolved (`..` stays).
 */
fun normaliseEntryPath(path: String): String {
    val unified = path.replace('\\', '/').trimEnd('/')
    return if (isWindowsPath(path)) unified.lowercase() else unified
}

/** The web's `isSelfOrDescendant`: [targetDir] is one of [paths] or has one of them above it, so it would go into itself. */
fun isSelfOrDescendant(paths: List<String>, targetDir: String): Boolean {
    val target = normaliseEntryPath(targetDir)
    return paths.any { path ->
        val source = normaliseEntryPath(path)
        target == source || target.startsWith("$source/")
    }
}

/** The web's `allAlreadyIn`: every one of [paths] sits directly in [targetDir], so a move of them would change nothing. */
fun allAlreadyIn(paths: List<String>, targetDir: String): Boolean {
    val target = normaliseEntryPath(targetDir)
    return paths.all { path ->
        val source = normaliseEntryPath(path)
        val cut = source.lastIndexOf('/')
        (if (cut <= 0) source.substring(0, cut + 1) else source.substring(0, cut)) == target
    }
}
