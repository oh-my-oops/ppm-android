package com.hienlh.ppm.app.ui.osexplorer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.TextInputFocus
import com.hienlh.ppm.app.ui.PpmTextField
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.*
import com.hienlh.ppm.protocol.HostFileEntry
import com.hienlh.ppm.state.osexplorer.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class HostInlineEdit(val directory: String, val entry: HostFileEntry?, val folder: Boolean) {
    val field = TextFieldState(entry?.name.orEmpty(), TextRange(0, entry?.name?.lastIndexOf('.')?.takeIf { it > 0 } ?: entry?.name?.length ?: 0))
}
private fun HostToast.message() = ErrorToastMessage(title, description)
private data class CollisionPrompt(val collision: HostCollision, val reply: CompletableDeferred<Pair<HostCollisionChoice, Boolean>>)
private data class DeletePrompt(val paths: List<String>, val noTrash: Boolean, val reply: CompletableDeferred<Boolean>)

@Composable
internal fun rememberHostExplorerCommands(model: AppModel, session: ExplorerSession, platform: String?, separator: String): HostExplorerCommands? {
    val client = model.api ?: return null
    val files = model.fileMutations ?: return null
    val scope = rememberCoroutineScope()
    val commands = remember(client, files, session, platform, separator) {
        val core = HostFileActions(client, files, separator, platform) { report -> if (model.api === client) model.filesMutated(report) }
        HostExplorerCommands(model::toast, client, session, core, platform, scope)
    }
    DisposableEffect(commands) {
        commands.attach()
        onDispose { commands.detach() }
    }
    return commands
}

internal class HostExplorerCommands(private val toast: (ErrorToastMessage) -> Unit, val api: ApiClient, private val session: ExplorerSession,
                                   val core: HostFileActions, private val platform: String?, private val scope: CoroutineScope) {
    var edit by mutableStateOf<HostInlineEdit?>(null)
        private set
    var inlineError by mutableStateOf<String?>(null)
        private set
    var property by mutableStateOf<HostFileEntry?>(null)
    private var collision by mutableStateOf<CollisionPrompt?>(null)
    private var deletion by mutableStateOf<DeletePrompt?>(null)
    private var running: Job? = null
    private val promptLock = Mutex()
    private var attached = false
    fun attach() { attached = true }
    fun detach() {
        attached = false
        collision?.reply?.complete(HostCollisionChoice.SKIP to false)
        deletion?.reply?.complete(false)
    }
    val busy get() = core.state.value.busy || running?.isActive == true

    fun create(folder: Boolean, directory: String = session.state.value.currentDirectory) { if (!busy) { inlineError = null; edit = HostInlineEdit(directory, null, folder) } }
    fun rename(entry: HostFileEntry) { if (!busy) { inlineError = null; edit = HostInlineEdit(session.state.value.currentDirectory, entry, entry.isDirectory) } }
    fun cancelEdit() { if (!busy) { edit = null; inlineError = null } }
    /**
     * Enter in the name field (`commitInline`): the name is trimmed, a rename to the name it has just closes the field, a name the host
     * refuses (an empty one included) is said under the field, and one that is sent and fails is a toast with the field left open.
     */
    fun commitEdit() {
        val current = edit ?: return
        if (busy || current.field.composition != null) return
        when (val commit = hostNameCommit(current.field.text.toString(), current.entry?.name, platform)) {
            HostNameCommit.Unchanged -> cancelEdit()
            is HostNameCommit.Refused -> inlineError = commit.message
            is HostNameCommit.Send -> {
                inlineError = null
                running = scope.launch {
                    val result = if (current.entry == null) core.create(current.directory, commit.name, current.folder)
                    else core.rename(current.entry.path, commit.name)
                    if (result.failed == 0) edit = null
                    else result.report.failures.firstOrNull()?.let { toast(hostFailureToast(it).message()) }
                }
            }
        }
    }
    fun paste(directory: String = session.state.value.currentDirectory) = work {
        val result = core.paste(directory, prompts())
        report(result, "Pasted")
    }
    fun trash(entries: List<HostFileEntry>) = remove(entries.map { it.path }, permanent = false)
    fun permanentlyDelete(entries: List<HostFileEntry>) = remove(entries.map { it.path }, permanent = true)
    private fun remove(paths: List<String>, permanent: Boolean) = work {
        if (paths.isEmpty() || (permanent && !confirmDelete(paths, false))) return@work
        val result = core.delete(paths, permanent)
        report(result, if (permanent) "Deleted" else "Moved to Trash", omitNoTrash = true)
        if (result.needsPermanent.isNotEmpty() && confirmDelete(result.needsPermanent, true)) {
            report(core.delete(result.needsPermanent, true), "Deleted")
        }
    }
    fun transfer(paths: List<String>, directory: String, copy: Boolean) = work {
        report(core.transfer(paths, directory, if (copy) HostTransferOperation.COPY else HostTransferOperation.MOVE, prompts()), if (copy) "Copied" else "Moved")
    }
    fun prompts(): HostTransferPrompts {
        var all: HostCollisionChoice? = null
        return HostTransferPrompts(resolveCollision = { item ->
            promptLock.withLock {
                if (!attached) return@withLock HostCollisionChoice.SKIP
                all ?: run {
                val prompt = CollisionPrompt(item, CompletableDeferred())
                collision = prompt
                try { prompt.reply.await().also { if (it.second) all = it.first }.first }
                finally { if (collision === prompt) collision = null }
                }
            }
        }, confirmPermanentOverwrite = { confirmDelete(listOf(it.destination), true) })
    }
    private suspend fun confirmDelete(paths: List<String>, noTrash: Boolean): Boolean = promptLock.withLock {
        if (!attached) return@withLock false
        val prompt = DeletePrompt(paths, noTrash, CompletableDeferred())
        deletion = prompt
        try { prompt.reply.await() } finally { if (deletion === prompt) deletion = null }
    }
    private fun work(block: suspend () -> Unit) {
        if (busy) return
        running = scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { toast(ErrorToastMessage("File operation failed", error.message)) }
        }
    }
    private fun report(result: HostFileActionResult, verb: String, omitNoTrash: Boolean = false) {
        val failures = result.report.failures.filterNot { omitNoTrash && it.code == "NO_TRASH" }
        if (failures.isNotEmpty()) toast(ErrorToastMessage("File operation failed", failures.joinToString("\n") { it.error ?: it.path }))
        if (result.succeeded > 0) toast(ErrorToastMessage("$verb ${result.succeeded} item${if (result.succeeded == 1) "" else "s"}", kind = ErrorToastMessage.Kind.SUCCESS))
    }

    @Composable
    fun Dialogs() {
        property?.let { HostFileProperties(api, it, platform) { property = null } }
        collision?.let { prompt ->
            var applyAll by remember(prompt) { mutableStateOf(false) }
            fun answer(choice: HostCollisionChoice) { prompt.reply.complete(choice to applyAll) }
            HostExplorerDialog("A file named “${prompt.collision.name}” already exists",
                "Replace moves the existing item to Trash. Keep both saves the copy with a new name.", { answer(HostCollisionChoice.SKIP) }) {
                BasicText(prompt.collision.destination, style = ppmText(12f).copy(color = LocalPpmTokens.current.text2.color))
                BasicText((if (applyAll) "☑" else "☐") + " Apply to all", Modifier.ppmClickable { applyAll = !applyAll }.padding(vertical = 8.dp),
                    style = ppmText(14f).copy(color = LocalPpmTokens.current.text.color))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HostDialogButton("Skip") { answer(HostCollisionChoice.SKIP) }
                    HostDialogButton("Keep both") { answer(HostCollisionChoice.KEEP_BOTH) }
                    HostDialogButton("Replace") { answer(HostCollisionChoice.REPLACE) }
                }
            }
        }
        deletion?.let { prompt ->
            HostExplorerDialog(if (prompt.noTrash) "This host has no Trash" else "Delete permanently?",
                "These items will be permanently deleted. This cannot be undone.", { prompt.reply.complete(false) }) {
                BasicText(prompt.paths.take(10).joinToString("\n") { it.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\') } +
                    if (prompt.paths.size > 10) "\n…and ${prompt.paths.size - 10} more" else "",
                    style = ppmText(14f).copy(color = LocalPpmTokens.current.text2.color))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HostDialogButton("Cancel") { prompt.reply.complete(false) }
                    HostDialogButton("Delete permanently", danger = true) { prompt.reply.complete(true) }
                }
            }
        }
    }
}

@Composable
internal fun HostInlineName(commands: HostExplorerCommands, edit: HostInlineEdit) {
    val state by commands.core.state.collectAsState()
    val tokens = LocalPpmTokens.current
    val focus = remember(edit) { FocusRequester() }
    LaunchedEffect(edit) { TextInputFocus.asked(); focus.requestFocus() }
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PpmIcon(if (edit.folder) ProductIcon.Folder else ProductIcon.File, 16.dp, tokens.text3.color)
            PpmTextField(edit.field, if (edit.folder) "Folder name" else "File name", Modifier.weight(1f).onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                    Key.Enter, Key.NumPadEnter -> { commands.commitEdit(); true }
                    Key.Escape -> { commands.cancelEdit(); true }
                    else -> false
                }
            }, desktopStyle = ppmText(13f), focusRequester = focus, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                onKeyboardAction = { commands.commitEdit() })
            HostExplorerButton(ProductIcon.Check, "Save name", enabled = !state.busy, onClick = commands::commitEdit)
            HostExplorerButton(ProductIcon.X, "Cancel", enabled = !state.busy, onClick = commands::cancelEdit)
        }
        commands.inlineError?.let { BasicText(it, style = ppmText(12f).copy(color = tokens.error.color)) }
    }
}
