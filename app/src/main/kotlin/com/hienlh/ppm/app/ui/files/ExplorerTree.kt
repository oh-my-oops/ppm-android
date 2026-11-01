package com.hienlh.ppm.app.ui.files

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import com.hienlh.ppm.app.TextInputFocus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssScrollbar
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.files.FileTreeNode
import com.hienlh.ppm.state.files.ProjectFileTree
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Project explorer only: its owner opens/resets [tree] and wires reconnect/file-change events.
 * [activeFilePath] and the directory [onOpenExplorer] and [onOpenTerminal] get are project-relative (root is "").
 * [onOpenFile] owns viewer routing and closing the phone drawer. Framework icons inherit
 * LocalIconFramework. Additional implemented commands come from [actionsForNode].
 */
@Composable
fun ExplorerTree(
    tree: ProjectFileTree,
    onOpenFile: (FileTreeNode) -> Unit,
    modifier: Modifier = Modifier,
    activeFilePath: String? = null,
    onOpenTerminal: ((String) -> Unit)? = null,
    onOpenExplorer: ((String) -> Unit)? = null,
    actionsForNode: (FileTreeNode) -> List<ExplorerTreeAction> = { emptyList() },
    mutations: ExplorerMutationCallbacks? = null,
    dropModifier: @Composable (directory: String) -> Modifier = { Modifier },
    transferStatus: @Composable () -> Unit = {},
) {
    val state by tree.state.collectAsState()
    // Flatten only when directory content/expansion changes, never for each selection or spinner.
    val rows = remember(state.directories, state.expandedPaths) { state.visibleRows }
    val visiblePaths = remember(rows) { rows.map { it.node.path } }
    val selected = remember(state.selectedFiles) { state.selectedFiles.toSet() }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var revealJob by remember(tree, state.project) { mutableStateOf<Job?>(null) }
    var revealError by remember(tree, state.project) { mutableStateOf<String?>(null) }
    var revealVersion by remember(tree, state.project) { mutableIntStateOf(0) }
    DisposableEffect(tree, state.project) { onDispose { revealJob?.cancel() } }
    LaunchedEffect(state.project) { list.scrollToItem(0) }
    val session = remember(tree, state.project) { ExplorerMutationSession(state.project, scope) }
    val mutationState by session.state.collectAsState()
    val commands = mutations?.takeIf { it.project == state.project }
    val busy = mutationState.busy || commands?.busy == true
    val edit = mutationState.edit.takeIf { commands != null }
    val displayRows = remember(rows, state.expandedPaths, edit) { explorerMutationRows(rows, state.expandedPaths, edit) }
    val field = remember(edit?.id, session) {
        val name = edit?.node?.name.orEmpty()
        TextFieldState(name, TextRange(0, explorerRenameSelectionEnd(name)))
    }
    var rootMenu by remember(tree, state.project) { mutableStateOf<Offset?>(null) }
    val dragOver = remember(tree, state.project) { ExplorerDragOver() }
    val listArea = remember { mutableStateOf<Rect?>(null) }
    // What a dragged row is over at [at] (root pixels), asked of the list's own layout each time because the rows scroll under a held pointer.
    fun siteAt(drag: ExplorerRowDrag, at: Offset): ExplorerDropSite {
        val y = at.y - (listArea.value?.top ?: 0f)
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }
        val node = (item?.let { displayRows.getOrNull(it.index) } as? ExplorerDisplayRow.Node)?.row?.node
            ?: return ExplorerDropSite(drag, ROOT_DROP_KEY, "", collapsedFolder = false)
        return ExplorerDropSite(
            drag, node.path, if (node.isDirectory) node.path else node.path.substringBeforeLast('/', ""),
            collapsedFolder = node.isDirectory && node.path !in state.expandedPaths,
        )
    }
    ExplorerDragAutoScroll(dragOver, list, { listArea.value }) { dragOver.site?.let { dragOver.site = siteAt(it.drag, dragOver.pointer) } }
    ExplorerDragSpringLoad(dragOver) { path -> tree.setExpanded(path, true) }
    val rootOver by remember(dragOver) { derivedStateOf { dragOver.site?.let { it.key == ROOT_DROP_KEY && it.lit } == true } }
    DisposableEffect(session) { onDispose { session.close() } }
    LaunchedEffect(edit?.id) {
        if (edit != null) {
            val index = displayRows.indexOfFirst { it is ExplorerDisplayRow.Input }
            if (index >= 0) list.scrollToItem(index)
        }
    }
    LaunchedEffect(state.focusedPath, displayRows, revealVersion) {
        if (edit != null) return@LaunchedEffect
        val index = displayRows.indexOfFirst { it is ExplorerDisplayRow.Node && it.row.node.path == state.focusedPath }
        if (index < 0) return@LaunchedEffect
        val layout = list.layoutInfo
        val shown = layout.visibleItemsInfo.firstOrNull { it.index == index }
        when {
            shown == null -> list.scrollToItem(index)
            shown.offset < layout.viewportStartOffset -> list.scrollToItem(index)
            shown.offset + shown.size > layout.viewportEndOffset -> {
                list.scrollToItem(index, -(layout.viewportEndOffset - layout.viewportStartOffset - shown.size).coerceAtLeast(0))
            }
        }
    }
    fun create(parent: String, directory: Boolean) {
        if (busy || commands == null) return
        TextInputFocus.asked()
        if (parent.isNotEmpty()) tree.setExpanded(parent, true)
        session.create(parent, directory)
    }
    fun move(drag: ExplorerRowDrag, directory: String) = commands?.let { session.move(drag.path, directory, it) } != null
    Column(modifier.fillMaxSize().explorerMutationKeys(tree, session, commands)) {
        ExplorerMutationToolbar(
            hasProject = state.project != null,
            busy = busy,
            onCreate = commands?.let { { directory -> create("", directory) } },
            refreshing = state.loading,
            revealing = revealJob?.isActive == true,
            onRefresh = { revealJob?.cancel(); revealError = null; tree.refresh() },
            onCollapse = { revealJob?.cancel(); revealError = null; tree.collapseAll() },
            onReveal = activeFilePath?.takeIf { explorerParents(it) != null && state.project != null }?.let { path ->
                {
                    revealJob?.cancel()
                    revealError = null
                    revealJob = scope.launch {
                        try {
                            if (revealExplorerFile(tree, path)) revealVersion++
                            else revealError = "Could not reveal active file. Refresh and try again."
                        } finally { if (revealJob === coroutineContext[Job]) revealJob = null }
                    }
                }
            },
        )
        transferStatus()
        if (state.project == null) {
            ExplorerNote("Select a project to browse files.")
            return@Column
        }
        val rootError = state.error
        if (rootError != null) ExplorerError(rootError) { tree.loadChildren("") }
        if (state.loading && rows.isEmpty()) ExplorerNote("Loading files…")
        revealError?.let { ExplorerNote(it) }
        if (mutationState.pasting) ExplorerNote("Pasting files…")
        mutationState.error?.let { error -> ExplorerError(error, retryLabel = "Dismiss", onRetry = session::clearError) }
        Box(Modifier.weight(1f).fillMaxWidth().then(dropModifier(""))
            .explorerMoveTarget(dragOver, state.project.orEmpty(), commands != null && !busy, ::siteAt, ::move)
            .onGloballyPositioned { listArea.value = it.boundsInRoot() }
            // `bg-primary/5` over the tree while a drag is over its blank area.
            .then(if (rootOver) Modifier.background(LocalPpmTokens.current.accent.opacity(0.05).color) else Modifier)
            .explorerContextGesture(
            onMenu = { rootMenu = it },
            eligible = { at -> list.layoutInfo.visibleItemsInfo.none { item ->
                item.key != "empty" && at.y >= item.offset && at.y < item.offset + item.size
            } },
        )) {
            LazyColumn(Modifier.fillMaxSize().cssScrollbar(list), state = list) {
            if (displayRows.isEmpty() && "" in state.loadedPaths && rootError == null && !state.loading) {
                item(key = "empty") { ExplorerNote("Empty project.") }
            }
            items(displayRows, key = { "${state.project}:${it.key}" }, contentType = { if (it is ExplorerDisplayRow.Input) "input" else "file" }) { display ->
                when (display) {
                    is ExplorerDisplayRow.Input -> ExplorerInlineInput(display, field, busy, mutationState.editError,
                        onSubmit = { if (tree.state.value.project == commands?.project) commands?.let { session.submit(display.edit, field.text.toString(), it) } },
                        onCancel = session::cancelEdit, onEdit = session::edited)
                    is ExplorerDisplayRow.Node -> ExplorerTreeItem(display.row, tree, state, visiblePaths, selected,
                        onOpenFile, onOpenTerminal, onOpenExplorer, actionsForNode, commands, session, busy, ::create, dragOver, dropModifier)
                }
            }
            }
            rootMenu?.let { at ->
                val actions = explorerRootActions(canCreate = commands != null && !busy, onCreate = { directory -> create("", directory) }, onRefresh = tree::refresh)
                ExplorerTreeMenu(at, actions, onDismiss = { rootMenu = null })
            }
        }
    }
    mutationState.deleting?.takeIf { commands != null }?.let { node ->
        ExplorerDeleteDialog(node, busy, mutationState.deleteError,
            onDelete = { commands?.let(session::confirmDelete) }, onDismiss = session::cancelDelete)
    }
}

@Composable
private fun ExplorerNote(text: String) {
    BasicText(text, Modifier.fillMaxWidth().padding(12.dp), style = ppmText(12f, lineHeight = 16f).copy(color = LocalPpmTokens.current.text3.color))
}

@Composable
internal fun ExplorerError(message: String, modifier: Modifier = Modifier, retryLabel: String = "Retry", onRetry: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Column(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        BasicText(message, style = ppmText(12f, lineHeight = 16f).copy(color = tokens.error.color))
        Row(Modifier.ppmClickable(onClickLabel = retryLabel, onClick = onRetry).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PpmIcon(if (retryLabel == "Dismiss") ProductIcon.X else ProductIcon.RefreshCw, 12.dp, tokens.text2.color)
            BasicText(retryLabel, style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color))
        }
    }
}
