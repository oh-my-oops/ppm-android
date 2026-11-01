package com.hienlh.ppm.app.ui.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.TextInputFocus
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.state.files.FileTreeNode
import com.hienlh.ppm.state.files.FileTreeRow
import com.hienlh.ppm.state.files.FileTreeState
import com.hienlh.ppm.state.files.ProjectFileTree

@Composable
internal fun ExplorerTreeItem(
    row: FileTreeRow, tree: ProjectFileTree, state: FileTreeState, visiblePaths: List<String>, selected: Set<String>,
    onOpenFile: (FileTreeNode) -> Unit, onOpenTerminal: ((String) -> Unit)?,
    actionsForNode: (FileTreeNode) -> List<ExplorerTreeAction>,
    mutations: ExplorerMutationCallbacks?, session: ExplorerMutationSession, busy: Boolean,
    onCreate: (String, Boolean) -> Unit,
    dropModifier: @Composable (String) -> Modifier = { Modifier },
) {
    val node = row.node
    val expanded = node.path in state.expandedPaths
    var menuAt by remember(tree, node.path) { mutableStateOf<Offset?>(null) }
    fun activate() {
        tree.setFocusedPath(node.path)
        tree.clearSelection()
        if (node.isDirectory) tree.toggleExpand(node.path) else onOpenFile(node)
    }
    Box(dropModifier(if (node.isDirectory) node.path else node.path.substringBeforeLast('/', ""))) {
        Column {
            ExplorerTreeRow(
                row, expanded, expanded && node.path in state.loadingPaths && node.path !in state.loadedPaths,
                node.path in selected, node.path == state.focusedPath, cut = node.path in mutations?.cutPaths.orEmpty(),
                onClick = { toggle, range ->
                    val current = tree.state.value
                    val next = explorerSelection(visiblePaths, current.selectedFiles, current.focusedPath, node.path, toggle, range)
                    tree.setFocusedPath(next.focusedPath)
                    tree.setSelectedFiles(next.paths)
                    if (next.activate) {
                        if (node.isDirectory) tree.toggleExpand(node.path) else onOpenFile(node)
                    }
                },
                onMenu = { menuAt = it },
            )
            val error = state.errors[node.path]
            if (node.isDirectory && error != null) ExplorerError(error, Modifier.padding(start = (row.depth * 16 + 28).dp)) {
                tree.setExpanded(node.path, true)
                tree.loadChildren(node.path)
            }
        }
        menuAt?.let { at ->
            val actions = buildList {
                if (mutations != null && !busy && node.isDirectory) {
                    add(ExplorerTreeAction("New File", ProductIcon.FilePlus) { onCreate(node.path, false) })
                    add(ExplorerTreeAction("New Folder", ProductIcon.FolderPlus) { onCreate(node.path, true) })
                }
                add(ExplorerTreeAction(if (node.isDirectory) { if (expanded) "Collapse" else "Expand" } else "Open",
                    if (node.isDirectory) ProductIcon.FolderOpen else ProductIcon.File, onClick = ::activate))
                if (node.isDirectory && onOpenTerminal != null) {
                    add(ExplorerTreeAction("Open in Terminal", ProductIcon.Terminal) { onOpenTerminal(node.path) })
                }
                if (mutations != null && !busy) {
                    add(ExplorerTreeAction("Cut", ProductIcon.Scissors) {
                        session.clipboard(explorerClipboardTargets(node.path, tree.state.value.selectedFiles), true, mutations)
                    })
                    add(ExplorerTreeAction("Copy", ProductIcon.Copy) {
                        session.clipboard(explorerClipboardTargets(node.path, tree.state.value.selectedFiles), false, mutations)
                    })
                    if (node.isDirectory && mutations.canPaste) {
                        add(ExplorerTreeAction("Paste", ProductIcon.ClipboardPaste) { session.paste(node.path, mutations) })
                    }
                    add(ExplorerTreeAction("Rename", ProductIcon.Pencil) { TextInputFocus.asked(); session.rename(node) })
                    add(ExplorerTreeAction("Delete", ProductIcon.Trash2, destructive = true) { session.askDelete(node) })
                }
                add(ExplorerTreeAction(if (node.path in selected) "Deselect" else "Select", ProductIcon.Check) {
                    tree.setFocusedPath(node.path)
                    tree.toggleFileSelect(node.path)
                })
                addAll(actionsForNode(node))
            }
            ExplorerTreeMenu(node.name, at, actions, onDismiss = { menuAt = null })
        }
    }
}
