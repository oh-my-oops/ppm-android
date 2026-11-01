package com.hienlh.ppm.state.osexplorer

import com.hienlh.ppm.protocol.HostFileEntry
import com.hienlh.ppm.state.files.FileDocumentClassifier

/** What a row of the host explorer's menus does; the UI binds each to its handler. */
enum class HostMenuCommand {
    NEW_FILE, NEW_FOLDER, PASTE, UPLOAD_FILES, UPLOAD_FOLDER, OPEN_IN_TERMINAL, COPY_PATH,
    OPEN, OPEN_IN_NEW_WINDOW, CUT, COPY, RENAME, TRASH, DELETE_PERMANENTLY, COPY_NAME, DOWNLOAD, TOGGLE_PIN, PROPERTIES,
}

sealed interface HostMenuItem {
    data class Row(val command: HostMenuCommand, val label: String, val enabled: Boolean = true, val destructive: Boolean = false) : HostMenuItem
    data object Separator : HostMenuItem

    /** The "Sort by" submenu, drawn as radios on [key] and then on [descending]. */
    data class SortBy(val key: ExplorerSort, val descending: Boolean) : HostMenuItem
}

/** What a menu depends on besides the entries it acts on. */
data class HostMenuContext(
    /** The phone's sheet: it has no second window to open a folder in. */
    val phone: Boolean,
    val hasClipboard: Boolean,
    /** Paths pinned to the sidebar. */
    val pinned: Set<String> = emptySet(),
    val sort: ExplorerSort = ExplorerSort.NAME,
    val descending: Boolean = false,
    /** False until the upload queue exists (it needs the host's path separator): the rows would pick files and drop them. */
    val canUpload: Boolean = true,
    val supportsFolderUpload: Boolean = true,
)

/** The labels sort has everywhere (sort-options.ts): the menu, the list header, the toolbar and the phone's More sheet. */
val ExplorerSort.label: String
    get() = when (this) {
        ExplorerSort.NAME -> "Name"
        ExplorerSort.SIZE -> "Size"
        ExplorerSort.MODIFIED -> "Modified"
        ExplorerSort.KIND -> "Kind"
    }

fun hostOrderLabel(descending: Boolean): String = if (descending) "Descending" else "Ascending"

private val separator = HostMenuItem.Separator

private fun row(command: HostMenuCommand, label: String, enabled: Boolean = true, destructive: Boolean = false) =
    HostMenuItem.Row(command, label, enabled, destructive)

/**
 * The menu of a right-click or long press on [targets] (explorer-context-menu.tsx), in the web's
 * order and under its conditions, its dividers too: it opens a multi-selection's menu, and a
 * file nothing opens, on one, and so does this.
 */
fun hostEntryMenu(targets: List<HostFileEntry>, context: HostMenuContext): List<HostMenuItem> {
    if (targets.isEmpty()) return hostBackgroundMenu(context)
    val single = targets.singleOrNull()
    val onlyDirectories = targets.all { it.isDirectory }
    val count = targets.size
    val suffix = if (count > 1) " $count items" else ""
    return buildList {
        if (single != null && (single.isDirectory || FileDocumentClassifier.canOpenInPpm(single.name))) add(row(HostMenuCommand.OPEN, "Open"))
        if (onlyDirectories && single != null && !context.phone) add(row(HostMenuCommand.OPEN_IN_NEW_WINDOW, "Open in New Window"))
        if (onlyDirectories && single != null) add(row(HostMenuCommand.OPEN_IN_TERMINAL, "Open in Terminal"))
        add(separator)
        add(row(HostMenuCommand.CUT, "Cut$suffix"))
        add(row(HostMenuCommand.COPY, "Copy$suffix"))
        if (onlyDirectories && single != null) add(row(HostMenuCommand.PASTE, "Paste", enabled = context.hasClipboard))
        add(separator)
        if (single != null) add(row(HostMenuCommand.RENAME, "Rename"))
        add(row(HostMenuCommand.TRASH, "Move to Trash$suffix"))
        add(row(HostMenuCommand.DELETE_PERMANENTLY, "Delete permanently", destructive = true))
        add(separator)
        add(row(HostMenuCommand.COPY_PATH, "Copy Path"))
        add(row(HostMenuCommand.COPY_NAME, "Copy Name"))
        if (targets.any { it.type == "file" }) add(row(HostMenuCommand.DOWNLOAD, "Download"))
        if (onlyDirectories && single != null) {
            add(row(HostMenuCommand.TOGGLE_PIN, if (single.path in context.pinned) "Unpin from Sidebar" else "Pin to Sidebar"))
        }
        if (single != null) {
            add(separator)
            add(row(HostMenuCommand.PROPERTIES, "Properties"))
        }
    }.tidy()
}

/** The menu of the folder's own empty space (explorer-context-menu.tsx with no targets). */
fun hostBackgroundMenu(context: HostMenuContext): List<HostMenuItem> = buildList {
    add(row(HostMenuCommand.NEW_FILE, "New File"))
    add(row(HostMenuCommand.NEW_FOLDER, "New Folder"))
    add(separator)
    add(row(HostMenuCommand.PASTE, "Paste", enabled = context.hasClipboard))
    add(separator)
    if (context.canUpload) {
        add(row(HostMenuCommand.UPLOAD_FILES, "Upload Files…"))
        if (context.supportsFolderUpload) add(row(HostMenuCommand.UPLOAD_FOLDER, "Upload Folder…"))
        add(separator)
    }
    add(row(HostMenuCommand.OPEN_IN_TERMINAL, "Open in Terminal"))
    add(separator)
    add(HostMenuItem.SortBy(context.sort, context.descending))
    add(separator)
    add(row(HostMenuCommand.COPY_PATH, "Copy Path"))
}.tidy()

/**
 * A divider never repeats and never ends a menu, which is what the web's menus come to wherever
 * they have all their groups; one the folder has no uploads for would otherwise stand twice.
 */
private fun List<HostMenuItem>.tidy(): List<HostMenuItem> {
    val kept = ArrayList<HostMenuItem>(size)
    for (item in this) if (item != separator || kept.lastOrNull() != separator) kept.add(item)
    if (kept.lastOrNull() == separator) kept.removeAt(kept.lastIndex)
    return kept
}

/** `Path copied`, `3 paths copied`, `Name copied`, `2 names copied` (copyTextLines in explorer-actions-open.ts). */
fun hostCopiedMessage(count: Int, noun: String): String =
    if (count == 1) "$noun copied" else "$count ${noun.lowercase()}s copied"

/** What Copy Path or Copy Name puts on the clipboard, and the toast that says it was. */
data class HostCopied(val text: String, val message: String)

/**
 * Copy Path and Copy Name: one value per line. The folder's own menu has no targets, and copies the
 * path of the folder it opened on (`copyPath([currentDir])`).
 */
fun hostCopied(command: HostMenuCommand, targets: List<HostFileEntry>, directory: String): HostCopied {
    val names = command == HostMenuCommand.COPY_NAME
    val values = when {
        names -> targets.map { it.name }
        targets.isEmpty() -> listOf(directory)
        else -> targets.map { it.path }
    }
    return HostCopied(values.joinToString("\n"), hostCopiedMessage(values.size, if (names) "Name" else "Path"))
}
