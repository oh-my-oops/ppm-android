package com.hienlh.ppm.app.ui.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
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
    onOpenFile: (FileTreeNode) -> Unit, onOpenTerminal: ((String) -> Unit)?, onOpenExplorer: ((String) -> Unit)?,
    actionsForNode: (FileTreeNode) -> List<ExplorerTreeAction>,
    mutations: ExplorerMutationCallbacks?, session: ExplorerMutationSession, busy: Boolean,
    onCreate: (String, Boolean) -> Unit,
    dragOver: ExplorerDragOver,
    dropModifier: @Composable (String) -> Modifier = { Modifier },
) {
    val node = row.node
    val expanded = node.path in state.expandedPaths
    var menuAt by remember(tree, node.path) { mutableStateOf<Offset?>(null) }
    val dropOver by remember(dragOver, node.path) { derivedStateOf { dragOver.site?.key == node.path } }
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
                dropOver = dropOver,
                dragSource = Modifier.explorerRowDragSource(ExplorerRowDrag(state.project.orEmpty(), node.path, node.isDirectory), mutations != null && !busy),
            )
            val error = state.errors[node.path]
            if (node.isDirectory && error != null) ExplorerError(error, Modifier.padding(start = (row.depth * 16 + 28).dp)) {
                tree.setExpanded(node.path, true)
                tree.loadChildren(node.path)
            }
        }
        menuAt?.let { at ->
            val writes = mutations?.takeIf { !busy }?.let { commands ->
                ExplorerNodeWrites(
                    canPaste = commands.canPaste,
                    create = { directory -> onCreate(node.path, directory) },
                    cut = { session.clipboard(explorerClipboardTargets(node.path, tree.state.value.selectedFiles), true, commands) },
                    copy = { session.clipboard(explorerClipboardTargets(node.path, tree.state.value.selectedFiles), false, commands) },
                    paste = { session.paste(node.path, commands) },
                    rename = { TextInputFocus.asked(); session.rename(node) },
                    delete = { session.askDelete(node) },
                )
            }
            val actions = explorerNodeActions(node, writes, explorerOpenActions(node, onOpenExplorer, onOpenTerminal), actionsForNode(node))
            ExplorerTreeMenu(at, actions, onDismiss = { menuAt = null })
        }
    }
}

/** A folder's entries that open it elsewhere, in the web's order: a File Explorer window on it, then a terminal in it. */
internal fun explorerOpenActions(
    node: FileTreeNode, onOpenExplorer: ((String) -> Unit)?, onOpenTerminal: ((String) -> Unit)?,
): List<ExplorerTreeAction> = buildList {
    if (!node.isDirectory) return@buildList
    if (onOpenExplorer != null) add(ExplorerTreeAction("Open in File Explorer", ProductIcon.FolderOpen) { onOpenExplorer(node.path) })
    if (onOpenTerminal != null) add(ExplorerTreeAction("Open in Terminal", ProductIcon.Terminal) { onOpenTerminal(node.path) })
}
