package com.hienlh.ppm.app.ui.phone.files

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.TextInputFocus
import com.hienlh.ppm.app.ui.editor.SharedCompareStore
import com.hienlh.ppm.app.ui.files.ExplorerDeleteDialog
import com.hienlh.ppm.app.ui.files.ExplorerDisplayRow
import com.hienlh.ppm.app.ui.files.ExplorerInlineEdit
import com.hienlh.ppm.app.ui.files.ExplorerInlineInput
import com.hienlh.ppm.app.ui.files.ExplorerMutationCallbacks
import com.hienlh.ppm.app.ui.files.ExplorerMutationSession
import com.hienlh.ppm.app.ui.files.ExplorerMutationState
import com.hienlh.ppm.app.ui.files.ExplorerTransferStatus
import com.hienlh.ppm.app.ui.files.ExplorerTransfers
import com.hienlh.ppm.app.ui.files.explorerRenameSelectionEnd
import com.hienlh.ppm.app.ui.files.rememberExplorerMutationCallbacks
import com.hienlh.ppm.app.ui.files.rememberExplorerTransfers
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.kit.KitSpec
import com.hienlh.ppm.state.editor.CompareSelection
import com.hienlh.ppm.state.editor.formatCompareTabTitle
import com.hienlh.ppm.state.files.FileMutationReport
import com.hienlh.ppm.state.files.FileTreeNode
import com.hienlh.ppm.state.files.ProjectFileTree
import com.hienlh.ppm.state.workspace.absoluteProjectPath
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * What the phone tree changes files with, built from the tablet tree's own pieces (`file-tree.tsx` below `md`):
 * [ExplorerMutationSession] holds the name being typed, the delete being confirmed and the paste running,
 * [rememberExplorerMutationCallbacks] sends them, and [ExplorerTransfers] uploads. Upload Files… picks the files
 * through the system's document picker, since a phone cannot drop them on the tree. [commands] is null while the
 * tree may not change files (no project, no connection).
 */
internal class PhoneTreeWrites(
    private val tree: ProjectFileTree,
    val commands: ExplorerMutationCallbacks?,
    val session: ExplorerMutationSession,
    val state: ExplorerMutationState,
    val transfers: ExplorerTransfers?,
    private val transferBusy: Boolean,
    val field: TextFieldState,
    private val pickUpload: (directory: String) -> Unit,
) {
    val busy: Boolean get() = state.busy || commands?.busy == true || transferBusy
    val canWrite: Boolean get() = commands != null && !busy
    val canPaste: Boolean get() = commands?.canPaste == true

    /** The name being typed, while the tree may change files. */
    val edit: ExplorerInlineEdit? get() = state.edit.takeIf { commands != null }

    /** New File / New Folder in [parent] ("" is the project folder), which opens to show the name being typed. */
    fun create(parent: String, directory: Boolean) {
        if (!canWrite) return
        TextInputFocus.asked()
        if (parent.isNotEmpty()) tree.setExpanded(parent, true)
        session.create(parent, directory)
    }

    fun rename(node: FileTreeNode) {
        if (!canWrite) return
        TextInputFocus.asked()
        session.rename(node)
    }

    fun delete(node: FileTreeNode) {
        if (canWrite) session.askDelete(node)
    }

    /** Cut or Copy: the one entry long-pressed, as the web's menu does with nothing else selected. */
    fun clipboard(path: String, cut: Boolean) {
        commands?.let { session.clipboard(listOf(path), cut, it) }
    }

    fun paste(directory: String) {
        val commands = commands ?: return
        if (directory.isNotEmpty()) tree.setExpanded(directory, true)
        session.paste(directory, commands)
    }

    fun upload(directory: String) {
        if (transfers != null && !busy) pickUpload(directory)
    }

    fun submit(edit: ExplorerInlineEdit) {
        val commands = commands ?: return
        if (tree.state.value.project == commands.project) session.submit(edit, field.text.toString(), commands)
    }
}

@Composable
internal fun rememberPhoneTreeWrites(model: AppModel, tree: ProjectFileTree, project: String): PhoneTreeWrites {
    val scope = rememberCoroutineScope()
    val mutations = model.fileMutations
    val info = model.currentProject?.takeIf { it.name == project }
    val callbacks = if (mutations != null && info != null) {
        rememberExplorerMutationCallbacks(mutations, info) { report: FileMutationReport ->
            if (model.fileMutations === mutations) model.filesMutated(report)
        }
    } else {
        null
    }
    val client = model.api
    val transfers = if (client != null && info != null) {
        rememberExplorerTransfers(client, info) { name, directory ->
            if (model.api === client) {
                model.fileIndex?.markStale(name)
                model.fileTree?.invalidateFolder(name, directory)
                model.filesMutated(FileMutationReport(affectedDirectories = setOf(absoluteProjectPath(info.path, directory))))
            }
        }
    } else {
        null
    }
    val transferBusy = transfers?.state?.collectAsState()?.value?.busy == true
    val session = remember(tree, project) { ExplorerMutationSession(project, scope) }
    DisposableEffect(session) { onDispose { session.close() } }
    val state by session.state.collectAsState()
    val field = remember(state.edit?.id, session) {
        val name = state.edit?.node?.name.orEmpty()
        TextFieldState(name, TextRange(0, explorerRenameSelectionEnd(name)))
    }
    val currentTransfers by rememberUpdatedState(transfers)
    var uploadInto by rememberSaveable(project) { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        val directory = uploadInto
        uploadInto = null
        if (directory != null && uris.isNotEmpty()) {
            if (directory.isNotEmpty()) tree.setExpanded(directory, true)
            currentTransfers?.upload(directory, uris)
        }
    }
    return PhoneTreeWrites(
        tree = tree,
        commands = callbacks?.takeIf { it.project == project },
        session = session,
        state = state,
        transfers = transfers,
        transferBusy = transferBusy,
        field = field,
        pickUpload = { directory ->
            uploadInto = directory
            try {
                picker.launch(arrayOf("*/*"))
            } catch (error: Exception) {
                uploadInto = null
                model.toast(ErrorToastMessage(error.message ?: "The system file picker is unavailable."))
            }
        },
    )
}

/** The name being typed, in a 44 dp row of the phone tree (the web's `InlineTreeInput`, 16 px text below `md`). */
@Composable
internal fun PhoneTreeInlineRow(row: ExplorerDisplayRow.Input, writes: PhoneTreeWrites) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = KitSpec.MinTouchTarget)
            .padding(start = 8.dp)
            .testTag("phone-tree-inline-input"),
        contentAlignment = Alignment.CenterStart,
    ) {
        ExplorerInlineInput(
            row, writes.field, writes.busy, writes.state.editError,
            onSubmit = { writes.submit(row.edit) },
            onCancel = writes.session::cancelEdit,
            onEdit = writes.session::edited,
        )
    }
}

/** Above the rows: an upload under way or done, a paste running, and what a paste or a move could not do. */
@Composable
internal fun ColumnScope.PhoneTreeWriteNotices(writes: PhoneTreeWrites) {
    writes.transfers?.let { ExplorerTransferStatus(it, Modifier.padding(horizontal = 8.dp).testTag("phone-files-transfer-status")) }
    if (writes.state.pasting) {
        PhoneFilesBanner(
            icon = ProductIcon.Loader2,
            title = "Pasting files…",
            modifier = Modifier.testTag("phone-files-pasting"),
        )
    }
    writes.state.error?.let { error ->
        PhoneFilesBanner(
            icon = ProductIcon.AlertCircle,
            title = error,
            isError = true,
            actionLabel = "Dismiss",
            onAction = writes.session::clearError,
            modifier = Modifier.testTag("phone-files-write-error"),
        )
    }
}

/** Delete File / Delete Folder, confirmed as the tablet's tree confirms it. */
@Composable
internal fun PhoneTreeWriteDialogs(writes: PhoneTreeWrites) {
    val node = writes.state.deleting?.takeIf { writes.commands != null } ?: return
    ExplorerDeleteDialog(
        node, writes.busy, writes.state.deleteError,
        onDelete = { writes.commands?.let(writes.session::confirmDelete) },
        onDismiss = writes.session::cancelDelete,
    )
}

/** The file picked with Select for Compare, when it is another file of [project]. */
internal fun phoneCompareSelection(project: String, path: String): CompareSelection? =
    SharedCompareStore.store.selection?.takeIf { it.projectName == project && it.filePath != path }

/** Select for Compare (`compare-store.ts`): this file is the first side of the next compare. */
internal fun phoneSelectForCompare(project: String, node: FileTreeNode) {
    SharedCompareStore.store.selectForCompare(CompareSelection(filePath = node.path, projectName = project, label = node.name))
}

/** Compare with Selected (`openCompareTab`): a `git-diff` tab of the two files, the selected one first. */
internal fun phoneCompareWithSelected(model: AppModel, project: String, node: FileTreeNode) {
    val selected = phoneCompareSelection(project, node.path) ?: return
    model.openWorkspaceTab(
        type = "git-diff",
        title = formatCompareTabTitle(selected.filePath, node.path),
        projectId = project,
        metadata = buildJsonObject {
            put("projectName", JsonPrimitive(project))
            put("file1", JsonPrimitive(selected.filePath))
            put("file2", JsonPrimitive(node.path))
        },
    )
    SharedCompareStore.store.clearSelection()
}
