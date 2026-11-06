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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class HostInlineEdit(val directory: String, val entry: HostFileEntry?, val folder: Boolean) {
    val field = TextFieldState(entry?.name.orEmpty(), TextRange(0, entry?.name?.lastIndexOf('.')?.takeIf { it > 0 } ?: entry?.name?.length ?: 0))
}
private fun HostToast.message() = ErrorToastMessage(title, description)

/**
 * What a finished transfer tells the user, one function for every surface that runs one, as the web's `transfer()` is: a toast for each
 * item that did not go, in its words ([hostFailureToast]), and when [announce] one for what did, "Moved 2 items" ([hostTransferSuccessToast]).
 * A window announces, as `pasteInto` and `transferInto` do. The project tree's blank area is silent about what went, as the web's
 * `transferRunner` is, and so will the places and the breadcrumb be.
 */
internal fun reportTransfer(result: HostFileActionResult, announce: Boolean, toast: (ErrorToastMessage) -> Unit) {
    result.report.failures.forEach { toast(hostFailureToast(it).message()) }
    if (announce) hostTransferSuccessToast(result)?.let { toast(ErrorToastMessage(it.title, it.description, kind = ErrorToastMessage.Kind.SUCCESS)) }
}

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
    private var running: Job? = null
    private val promptHost = HostTransferPromptHost()
    fun attach() = promptHost.attach()
    fun detach() = promptHost.detach()
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
        reportTransfer(core.paste(directory, prompts()), announce = true, toast)
    }
    fun trash(entries: List<HostFileEntry>) = remove(entries.map { it.path }, permanent = false)
    fun permanentlyDelete(entries: List<HostFileEntry>) = remove(entries.map { it.path }, permanent = true)
    private fun remove(paths: List<String>, permanent: Boolean) = work {
        if (paths.isEmpty() || (permanent && !confirmDelete(paths))) return@work
        val result = core.delete(paths, permanent)
        report(result, if (permanent) "Deleted" else "Moved to Trash", omitNoTrash = true)
        if (result.needsPermanent.isNotEmpty() && confirmDelete(result.needsPermanent)) {
            report(core.delete(result.needsPermanent, true), "Deleted")
        }
    }
    fun transfer(paths: List<String>, directory: String, copy: Boolean) = work {
        reportTransfer(core.transfer(paths, directory, if (copy) HostTransferOperation.COPY else HostTransferOperation.MOVE, prompts()), announce = true, toast)
    }
    fun prompts(): HostTransferPrompts = promptHost.prompts()
    private suspend fun confirmDelete(paths: List<String>): Boolean = promptHost.confirmDelete(paths)
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
        promptHost.Dialogs()
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
