package com.hienlh.ppm.app.ui.osexplorer

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.BottomSheet
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
    // The web's class is `p-0`: the toolbar sits on the navigation bar, or on the keyboard, with no 0.5rem under it.
    BottomSheet(onDismiss = model::closeExplorerSheet, modifier = Modifier.fillMaxHeight(), radius = 0.dp,
        bottomPadding = WindowInsets.ime.union(WindowInsets.navigationBars)) {
        HostExplorerBody(model, "phone-explorer", path)
    }
}

private data class HostMenu(val entries: List<HostFileEntry>, val at: Offset, val directory: String? = null)

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
    // Going to another folder clears the selection, and Select mode follows it, or the bar is left on Cut, Copy and Trash with nothing to act on.
    LaunchedEffect(state.path) { selectMode = false }
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
    val menuActions = HostExplorerMenuActions(model, session, commands, uploadFiles, downloads, ::copyText, ::open)
    fun copy(cut: Boolean) {
        val paths = selected().map { it.path }
        if (paths.isNotEmpty()) model.fileMutations?.setClipboard(FileClipboard(paths, if (cut) FileClipboardOperation.CUT else FileClipboardOperation.COPY))
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(tokens.panel.color)) {
        val showPlaces = !mobile && maxWidth >= 640.dp
        Column(Modifier.fillMaxSize()) {
            if (mobile) {
                HostPhoneTopBar(hostFolderName(state.path, state.directory?.breadcrumbs.orEmpty()), model::closeExplorerSheet)
                HostPhonePlaces(session, host.host, windows, dropModifier)
            } else HostExplorerToolbar(session, state, preferences) { menu = HostMenu(emptyList(), menuAt) }
            Row(Modifier.weight(1f)) {
                if (showPlaces) HostExplorerPlaces(session, host.host, windows, dropModifier)
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
            if (mobile) HostPhoneToolbar(
                state, entries, preferences, selectMode,
                hasClipboard = clipboard?.paths?.isNotEmpty() == true,
                canUpload = uploadQueue != null,
                onSelectMode = { selectMode = it },
                onBack = { session.back() },
                onPreferences = { session.preferences.update(it) },
                onCommand = { command, targets -> menuActions.run(command, targets, state.currentDirectory) },
            ) else {
                // The web draws no status bar on a phone: the toolbar is the last thing under the list.
                val count = entries.count { it.path in state.selection }
                BasicText(buildString {
                    append("${entries.size}${if (state.directory?.truncated == true) "+" else ""} item${if (entries.size == 1) "" else "s"}")
                    if (count > 0) { append(" · $count selected"); val bytes = hostSelectionSize(entries.filter { it.path in state.selection }); if (bytes > 0) append(" · ${hostDisplaySize(bytes)}") }
                }, Modifier.fillMaxWidth().background(tokens.panel2.color).padding(horizontal = 8.dp, vertical = 4.dp),
                    style = ppmText(11f).copy(color = tokens.text3.color))
            }
        }
    }
    commands?.Dialogs()
    menu?.let { request ->
        val directory = request.directory ?: state.currentDirectory
        val items = hostEntryMenu(request.entries, HostMenuContext(
            phone = mobile,
            hasClipboard = clipboard?.paths?.isNotEmpty() == true,
            pinned = session.preferences.pins.value.map { it.path }.toSet(),
            sort = preferences.sort,
            descending = preferences.descending,
            canUpload = uploadQueue != null,
        ))
        HostExplorerMenu(items, request.at,
            onCommand = { menuActions.run(it, request.entries, directory) },
            onSort = { key, descending -> session.preferences.update(preferences.copy(sort = key, descending = descending)) },
            onDismiss = { menu = null })
    }
}
