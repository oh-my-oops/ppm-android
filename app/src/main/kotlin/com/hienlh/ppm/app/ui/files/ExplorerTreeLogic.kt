package com.hienlh.ppm.app.ui.files

import com.hienlh.ppm.state.files.ProjectFileTree

internal data class ExplorerSelection(val focusedPath: String?, val paths: List<String>, val activate: Boolean)

/** tree-node.tsx: Ctrl/Meta wins over Shift; a range keeps its original focus anchor. */
internal fun explorerSelection(
    visible: List<String>, selected: List<String>, focused: String?, target: String,
    toggle: Boolean = false, range: Boolean = false,
): ExplorerSelection = when {
    toggle -> ExplorerSelection(target, if (target in selected) selected - target else selected + target, false)
    range && focused != null -> {
        val from = visible.indexOf(focused)
        val to = visible.indexOf(target)
        ExplorerSelection(focused, if (from < 0 || to < 0) selected else visible.subList(minOf(from, to), maxOf(from, to) + 1), false)
    }
    else -> ExplorerSelection(target, emptyList(), true)
}

/** Only project-relative paths belong in this tree. The shell normalizes absolute editor paths. */
internal fun explorerParents(path: String): List<String>? {
    if (path.isEmpty() || path.startsWith('/') || path.startsWith('\\') || Regex("^[A-Za-z]:").containsMatchIn(path)) return null
    val parts = path.split('/')
    if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
    return (1 until parts.size).map { parts.take(it).joinToString("/") }
}

/** Load ancestors in order, promoting prefetch; stop on error, project change or a user collapse. */
internal suspend fun revealExplorerFile(tree: ProjectFileTree, path: String): Boolean {
    val parents = explorerParents(path) ?: return false
    val project = tree.state.value.project ?: return false
    tree.loadChildren("")?.join()
    if (tree.state.value.project != project || "" !in tree.state.value.loadedPaths) return false
    for (parent in parents) {
        if (tree.state.value.node(parent)?.isDirectory != true) return false
        tree.setExpanded(parent, true)
        tree.loadChildren(parent)?.join()
        val state = tree.state.value
        if (state.project != project || parent !in state.loadedPaths || parent !in state.expandedPaths) return false
    }
    if (parents.any { it !in tree.state.value.expandedPaths } || tree.state.value.node(path)?.isDirectory != false) return false
    tree.setFocusedPath(path)
    return true
}
