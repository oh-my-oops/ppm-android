package com.hienlh.ppm.app.ui.osexplorer

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.BottomSheet
import com.hienlh.ppm.app.ui.files.ExplorerTreeAction
import com.hienlh.ppm.app.ui.files.ExplorerTreeMenu
import com.hienlh.ppm.design.*
import com.hienlh.ppm.protocol.HostFileEntry
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.files.FileClipboard
import com.hienlh.ppm.state.files.FileClipboardOperation
import com.hienlh.ppm.state.files.FileDocumentClassifier
import com.hienlh.ppm.state.osexplorer.*
import com.hienlh.ppm.state.windows.WindowRuntimeState
import com.hienlh.ppm.state.windows.WindowVisualState
import kotlinx.coroutines.awaitCancellation

@Composable
fun HostExplorerWindow(model: AppModel, window: WindowRuntimeState) {
    val path = window.payload["path"].string ?: "~"
    HostExplorerBody(model, window.id, path, visible = window.state != WindowVisualState.Minimized)
}

@Composable
fun HostExplorerSheet(model: AppModel, path: String) {
    BottomSheet(onDismiss = model::closeExplorerSheet, modifier = Modifier.fillMaxHeight(), radius = 0.dp) {
        HostExplorerBody(model, "phone-explorer", path)
    }
}

private data class HostMenu(val entries: List<HostFileEntry>, val at: Offset, val preferences: Boolean = false, val directory: String? = null)

@Composable
private fun HostExplorerBody(model: AppModel, id: String, path: String, visible: Boolean = true) {
    val session = remember(model.api, id) { model.hostExplorer(id, path) } ?: return
    val hostStore = model.hostFilesystem ?: return
    val host by hostStore.state.collectAsState()
    val state by session.state.collectAsState()
    val preferences by session.preferences.state.collectAsState()
    val commands = rememberHostExplorerCommands(model, session, host.host?.platform, state.directory?.separator ?: host.host?.separator ?: "/")
    val uploadQueue = remember(model.api, host.host?.separator, state.directory?.separator) {
        (state.directory?.separator ?: host.host?.separator)?.let(model::hostUploads)
    }
    val uploadFiles = rememberHostUploadPicker(model, uploadQueue, commands)
    val downloads = rememberHostDownloads(model)
    val dropModifier: @Composable (String) -> Modifier = { directory -> hostExternalDrop(model, uploadQueue, commands, directory) }
    val clipboard = model.fileMutations?.state?.collectAsState()?.value?.clipboard
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(session, visible, lifecycle) {
        if (visible) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            session.show(); hostStore.load()
            try { awaitCancellation() } finally { session.hide() }
        } else session.hide()
    }
    DisposableEffect(session) { onDispose { session.hide() } }
    val mobile = LocalPpmMobile.current
    val windows = model.windowsSkin
    val tokens = LocalPpmTokens.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val systemClipboard = remember(context) { context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager }
    fun copyText(value: String) { systemClipboard.setPrimaryClip(android.content.ClipData.newPlainText("File path", value)) }
    val entries = remember(state.directory, state.filter, preferences) { session.visibleEntries() }
    var selectMode by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<HostMenu?>(null) }
    var menuAt by remember { mutableStateOf(Offset.Zero) }
    val focus = remember { FocusRequester() }
    var typed by remember { mutableStateOf("") }
    var typedAt by remember { mutableLongStateOf(0) }
    fun open(entry: HostFileEntry) {
        if (entry.isDirectory) session.go(entry.path)
        else if (FileDocumentClassifier.canOpenInPpm(entry.name)) model.openHostFile(entry.path)
    }
    fun selected(): List<HostFileEntry> = entries.filter { it.path in session.state.value.selection }
    fun copy(cut: Boolean) {
        val paths = selected().map { it.path }
        if (paths.isNotEmpty()) model.fileMutations?.setClipboard(FileClipboard(paths, if (cut) FileClipboardOperation.CUT else FileClipboardOperation.COPY))
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(tokens.panel.color)) {
        val showPlaces = !mobile && maxWidth >= 640.dp
        Column(Modifier.fillMaxSize()) {
            if (mobile) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(state.directory?.breadcrumbs?.lastOrNull()?.name ?: state.path, Modifier.weight(1f),
                        style = ppmText(15f, FontWeight.SemiBold).copy(color = tokens.text.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    HostExplorerButton(ProductIcon.X, "Close file explorer", onClick = model::closeExplorerSheet)
                }
                HostExplorerPlaces(session, host.host, windows, mobile = true, dropModifier = dropModifier)
            } else HostExplorerToolbar(session, state, preferences) { menu = HostMenu(emptyList(), menuAt) }
            Row(Modifier.weight(1f)) {
                if (showPlaces) HostExplorerPlaces(session, host.host, windows, mobile = false, dropModifier = dropModifier)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    state.error?.let { failure ->
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            BasicText(failure.message, style = ppmText(12f).copy(color = tokens.error.color))
                            failure.hint?.let { BasicText(it, style = ppmText(12f).copy(color = tokens.text2.color)) }
                            BasicText("Retry", Modifier.ppmClickable { session.refresh() }.padding(vertical = 8.dp),
                                style = ppmText(12f).copy(color = tokens.accent.color))
                        }
                    }
                    if (state.loading && state.directory == null) BasicText("Loading…", Modifier.padding(12.dp), style = ppmText(12f).copy(color = tokens.text3.color))
                    commands?.edit?.takeIf { it.entry == null }?.let { HostInlineName(commands, it) }
                    Box(Modifier.weight(1f).fillMaxWidth().then(dropModifier(state.currentDirectory)).onGloballyPositioned { menuAt = it.positionInWindow() + Offset(20f, 20f) }
                        .focusRequester(focus).onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown || com.hienlh.ppm.app.TextInputFocus.focused) return@onKeyEvent false
                            val ctrl = event.isCtrlPressed || event.isMetaPressed
                            if (event.isAltPressed) when (event.key) {
                                Key.DirectionLeft -> { session.back(); return@onKeyEvent true }
                                Key.DirectionRight -> { session.forward(); return@onKeyEvent true }
                                Key.DirectionUp -> { session.up(); return@onKeyEvent true }
                            }
                            when {
                                ctrl && event.key == Key.A -> session.selectAll()
                                ctrl && event.key == Key.C -> copy(false)
                                ctrl && event.key == Key.X -> copy(true)
                                ctrl && event.key == Key.V -> commands?.paste()
                                event.key == Key.F2 -> selected().singleOrNull()?.let { commands?.rename(it) }
                                event.key == Key.Delete -> if (event.isShiftPressed) commands?.permanentlyDelete(selected()) else commands?.trash(selected())
                                event.key == Key.Escape -> { session.clearSelection(); selectMode = false }
                                event.key == Key.Backspace -> session.up()
                                event.key == Key.Enter || event.key == Key.NumPadEnter -> selected().firstOrNull()?.let(::open)
                                event.key in listOf(Key.DirectionDown, Key.DirectionUp, Key.MoveHome, Key.MoveEnd) -> {
                                    val current = entries.indexOfFirst { it.path == session.state.value.anchor }
                                    val next = when (event.key) { Key.MoveHome -> 0; Key.MoveEnd -> entries.lastIndex
                                        Key.DirectionUp -> (current - 1).coerceAtLeast(0); else -> (current + 1).coerceAtMost(entries.lastIndex) }
                                    entries.getOrNull(next)?.let { session.select(it.path, range = event.isShiftPressed) }
                                }
                                !ctrl && !event.isAltPressed && event.nativeKeyEvent.unicodeChar >= 32 -> {
                                    val now = System.currentTimeMillis()
                                    typed = (if (now - typedAt < 700) typed else "") + event.nativeKeyEvent.unicodeChar.toChar().lowercaseChar()
                                    typedAt = now
                                    entries.firstOrNull { it.name.lowercase().startsWith(typed) }?.let { session.select(it.path) }
                                }
                                else -> return@onKeyEvent false
                            }
                            true
                        }.focusable()) {
                        if (preferences.view == ExplorerView.COLUMNS && model.api != null) HostExplorerColumns(
                            model.api!!, session, entries, state, preferences, mobile,
                            onSelect = { entry -> session.select(entry.path, toggle = mobile && selectMode) },
                            dropModifier = dropModifier,
                            onOpen = ::open,
                            onMenu = { targets, at, directory -> menu = HostMenu(targets, at, directory = directory) },
                            cut = if (clipboard?.operation == FileClipboardOperation.CUT) clipboard.paths.toSet() else emptySet(), commands = commands,
                        ) else HostExplorerEntries(model.api, session, entries, state, preferences, mobile, selectMode, windows, commands,
                            if (clipboard?.operation == FileClipboardOperation.CUT) clipboard.paths.toSet() else emptySet(),
                            onFocus = { focus.requestFocus() }, onOpen = ::open,
                            onMenu = { targets, at -> menu = HostMenu(targets, at) }, dropModifier = dropModifier)
                    }
                }
            }
            uploadQueue?.let { HostUploadProgress(it) }
            downloads?.let { HostDownloadProgress(it) }
            val count = entries.count { it.path in state.selection }
            BasicText(buildString {
                append("${entries.size}${if (state.directory?.truncated == true) "+" else ""} item${if (entries.size == 1) "" else "s"}")
                if (count > 0) { append(" · $count selected"); val bytes = hostSelectionSize(entries.filter { it.path in state.selection }); if (bytes > 0) append(" · ${hostDisplaySize(bytes)}") }
            }, Modifier.fillMaxWidth().background(tokens.panel2.color).padding(horizontal = 8.dp, vertical = 4.dp),
                style = ppmText(11f).copy(color = tokens.text3.color))
            if (mobile) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                HostExplorerButton(if (selectMode) ProductIcon.X else ProductIcon.ArrowLeft, if (selectMode) "Cancel selection" else "Back",
                    enabled = selectMode || state.canBack) { if (selectMode) selectMode = false else session.back() }
                if (selectMode) {
                    HostExplorerButton(ProductIcon.Scissors, "Cut", enabled = count > 0) { copy(true) }
                    HostExplorerButton(ProductIcon.Copy, "Copy", enabled = count > 0) { copy(false) }
                    HostExplorerButton(ProductIcon.Trash2, "Trash", enabled = count > 0) { commands?.trash(selected()) }
                    HostExplorerButton(ProductIcon.MoreHorizontal, "Selection actions", enabled = count > 0) { menu = HostMenu(selected(), menuAt) }
                } else {
                    HostExplorerButton(ProductIcon.Plus, "New") { menu = HostMenu(emptyList(), menuAt) }
                    HostExplorerButton(ProductIcon.ClipboardPaste, "Paste", enabled = clipboard != null) { commands?.paste() }
                    HostExplorerButton(ProductIcon.Check, "Select") { selectMode = true }
                    HostExplorerButton(ProductIcon.MoreHorizontal, "More") { menu = HostMenu(emptyList(), menuAt, preferences = true) }
                }
            }
        }
    }
    commands?.Dialogs()
    menu?.let { request ->
        val actions = buildList {
            if (!request.preferences && request.entries.isEmpty() && uploadQueue != null) {
                add(ExplorerTreeAction("Upload files") { uploadFiles.files(request.directory ?: state.currentDirectory) })
                add(ExplorerTreeAction("Upload folder") { uploadFiles.folder(request.directory ?: state.currentDirectory) })
            }
            if (!request.preferences) addAll(commands?.menu(request.entries, clipboard != null, request.directory ?: state.currentDirectory).orEmpty())
            if (request.preferences) {
                for (mode in ExplorerView.entries) add(ExplorerTreeAction("${mode.name.lowercase().replaceFirstChar(Char::uppercaseChar)} view") { session.preferences.update(preferences.copy(view = mode)) })
                for (sort in ExplorerSort.entries) add(ExplorerTreeAction("Sort by ${sort.name.lowercase()}") { session.preferences.update(preferences.copy(sort = sort)) })
                add(ExplorerTreeAction(if (preferences.descending) "Ascending" else "Descending") { session.preferences.update(preferences.copy(descending = !preferences.descending)) })
                add(ExplorerTreeAction(if (preferences.showHidden) "Hide hidden files" else "Show hidden files") { session.preferences.update(preferences.copy(showHidden = !preferences.showHidden)) })
            } else if (request.entries.isNotEmpty()) {
                val entry = request.entries.first()
                if (downloads != null) add(ExplorerTreeAction("Download") { downloads.download(request.entries) })
                add(ExplorerTreeAction("Copy Path", ProductIcon.Copy) { copyText(request.entries.joinToString("\n") { it.path }) })
                add(ExplorerTreeAction("Copy Name", ProductIcon.Copy) { copyText(request.entries.joinToString("\n") { it.name }) })
                add(ExplorerTreeAction("Open", ProductIcon.FolderOpen) { open(entry) })
                if (entry.isDirectory) {
                    add(ExplorerTreeAction("Open in New Window", ProductIcon.FolderOpen) { model.openExplorer(entry.path) })
                    add(ExplorerTreeAction("Open in Terminal", ProductIcon.Terminal) { model.openTerminalAt(entry.path) })
                }
                add(ExplorerTreeAction("Cut (${request.entries.size})", ProductIcon.Scissors) { model.fileMutations?.setClipboard(FileClipboard(request.entries.map { it.path }, FileClipboardOperation.CUT)) })
                add(ExplorerTreeAction("Copy (${request.entries.size})", ProductIcon.Copy) { model.fileMutations?.setClipboard(FileClipboard(request.entries.map { it.path }, FileClipboardOperation.COPY)) })
                if (entry.isDirectory) {
                    val pinned = session.preferences.pins.value.any { it.path == entry.path }
                    add(ExplorerTreeAction(if (pinned) "Unpin" else "Pin", ProductIcon.Pin) {
                        if (pinned) session.preferences.unpin(entry.path) else session.preferences.pin(entry.name, entry.path)
                    })
                }
            } else {
                add(ExplorerTreeAction("Copy Path", ProductIcon.Copy) { copyText(request.directory ?: state.currentDirectory) })
                add(ExplorerTreeAction("Properties", ProductIcon.Info) { commands?.property = HostFileEntry(state.directory?.breadcrumbs?.lastOrNull()?.name ?: state.path,
                    state.currentDirectory, "directory", "directory") })
                add(ExplorerTreeAction("Open in Terminal", ProductIcon.Terminal) { model.openTerminalAt(request.directory ?: state.currentDirectory) })
                add(ExplorerTreeAction("View and sort", ProductIcon.List) { menu = request.copy(preferences = true) })
                add(ExplorerTreeAction("Refresh", ProductIcon.RefreshCw, onClick = session::refresh))
            }
        }
        ExplorerTreeMenu(if (request.entries.size == 1) request.entries.first().name else "File explorer", request.at, actions) { menu = null }
    }
}
