package com.hienlh.ppm.app.ui.files

import com.hienlh.ppm.state.files.FileTreeRow

internal sealed interface ExplorerDisplayRow {
    val key: String
    data class Node(val row: FileTreeRow) : ExplorerDisplayRow { override val key = "node:${row.node.path}" }
    data class Input(val edit: ExplorerInlineEdit, val depth: Int) : ExplorerDisplayRow { override val key = "input:${edit.id}" }
}

/** Insert an input before children, or replace a renamed row while retaining its children. */
internal fun explorerMutationRows(rows: List<FileTreeRow>, expanded: Set<String>, edit: ExplorerInlineEdit?): List<ExplorerDisplayRow> = buildList {
    if (edit != null && edit.node == null && edit.parent.isEmpty()) add(ExplorerDisplayRow.Input(edit, 0))
    for (row in rows) {
        if (edit?.node?.path == row.node.path) add(ExplorerDisplayRow.Input(edit, row.depth))
        else add(ExplorerDisplayRow.Node(row))
        if (edit != null && edit.node == null && edit.parent == row.node.path && row.node.path in expanded) {
            add(ExplorerDisplayRow.Input(edit, row.depth + 1))
        }
    }
}

/** Web selection offsets are UTF-16, as TextFieldState's are; dotfiles select the whole name. */
internal fun explorerRenameSelectionEnd(name: String): Int = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length

/** A menu on a selected entry copies the selection; an unselected entry copies only itself. */
internal fun explorerClipboardTargets(path: String, selected: List<String>): List<String> =
    if (path in selected) selected.toList() else listOf(path)
