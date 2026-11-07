package com.hienlh.ppm.app.ui.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.workspace.absoluteProjectPath

/** Mounted only while the sidebar/drawer shows Explorer; the recursive index stays untouched. */
@Composable
fun ExplorerBody(model: AppModel, onNavigate: () -> Unit = {}) {
    val tree = model.fileTree
    val project = model.selectedProject
    if (tree == null || project == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
            BasicText("Select a project to browse files.", style = ppmText(12f).copy(color = LocalPpmTokens.current.text2.color))
        }
        return
    }
    DisposableEffect(tree, project) {
        tree.open(project)
        onDispose { if (tree.state.value.project == project) tree.reset() }
    }
    val active = model.activeTab?.takeIf { (it.projectId ?: it.metaString("projectName")) == project }
    val mutations = model.fileMutations
    val info = model.currentProject
    val callbacks = if (mutations != null && info != null) rememberExplorerMutationCallbacks(mutations, info) { report ->
        if (model.fileMutations === mutations) model.filesMutated(report)
    } else null
    val client = model.api
    val transfers = if (client != null && info != null) rememberExplorerTransfers(client, info) { name, directory ->
        if (model.api === client) {
            model.fileIndex?.markStale(name)
            model.fileTree?.invalidateFolder(name, directory)
            model.filesMutated(com.hienlh.ppm.state.files.FileMutationReport(affectedDirectories = setOf(
                com.hienlh.ppm.state.workspace.absoluteProjectPath(info.path, directory))))
        }
    } else null
    val entryDrops = rememberExplorerEntryDrops(model, info)
    ExplorerTree(
        tree = tree,
        onOpenFile = { node -> model.openFileTab(project, node.path); onNavigate() },
        activeFilePath = active?.metaString("filePath"),
        mutations = callbacks,
        entryDrops = entryDrops,
        actionsForNode = { node -> transfers?.actionsForNode(node).orEmpty() },
        dropModifier = { directory -> transfers?.dropModifier(directory) ?: Modifier },
        transferStatus = { transfers?.let { ExplorerTransferStatus(it) } },
        gitFileStatuses = model.gitFileStatuses[project].orEmpty(),
        gitFolderStatuses = model.gitFolderStatuses[project].orEmpty(),
        onOpenTerminal = model.currentProject?.path?.let { root ->
            { directory ->
                model.openTerminalAt(absoluteProjectPath(root, directory), project)
                onNavigate()
            }
        },
        onOpenExplorer = model.currentProject?.path?.let { root ->
            { directory ->
                model.openExplorer(absoluteProjectPath(root, directory))
                onNavigate()
            }
        },
    )
    entryDrops?.Dialogs()
}
