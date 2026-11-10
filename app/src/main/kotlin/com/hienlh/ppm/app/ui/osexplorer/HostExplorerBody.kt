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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.BottomSheet
import com.hienlh.ppm.app.ui.CornerChrome
import com.hienlh.ppm.client.KeyValueStore
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
import com.hienlh.ppm.state.workspace.NewTab
import com.hienlh.ppm.state.workspace.WorkspaceStore
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal const val OS_EXPLORER_TAB_META_KEY = "osExplorer"
internal const val OS_EXPLORER_PATH_META_KEY = "osExplorerPath"
internal const val OS_EXPLORER_URI_PREFIX = "os-explorer://"

internal fun osExplorerTabTitle(path: String): String {
    val trimmed = path.trim().trimEnd('/', '\\')
    val name = trimmed.substringAfterLast('/').substringAfterLast('\\')
    return when {
        name.isNotEmpty() -> name
        path.isNotBlank() -> path.trim()
        else -> "Files"
    }
}

internal fun isOsExplorerTab(tab: WorkspaceTab): Boolean =
    tab.type == "os-explorer" ||
        tab.type == "explorer" ||
        tab.metadata[OS_EXPLORER_TAB_META_KEY]?.jsonPrimitive?.booleanOrNull == true ||
        tab.metaString(OS_EXPLORER_PATH_META_KEY) != null ||
        tab.metaString("filePath")?.startsWith(OS_EXPLORER_URI_PREFIX) == true

internal fun osExplorerTabPath(tab: WorkspaceTab, defaultPath: String = "~"): String {
    tab.metaString(OS_EXPLORER_PATH_META_KEY)?.takeIf { it.isNotEmpty() }?.let { return it }
    tab.metaString("path")?.takeIf { it.isNotEmpty() }?.let { return it }
    val rawFile = tab.metaString("filePath")
    if (rawFile != null && rawFile.startsWith(OS_EXPLORER_URI_PREFIX)) {
        val stripped = rawFile.removePrefix(OS_EXPLORER_URI_PREFIX).substringBefore('#')
        if (stripped.isNotEmpty()) return stripped
    }
    return defaultPath
}

internal fun osExplorerNewTab(
    path: String,
    projectId: String? = null,
    newInstance: Boolean = false,
): NewTab {
    val resolved = path.ifBlank { "~" }
    val key = if (newInstance) "$OS_EXPLORER_URI_PREFIX$resolved#${System.nanoTime()}" else "$OS_EXPLORER_URI_PREFIX$resolved"
    return NewTab(
        type = "editor",
        title = osExplorerTabTitle(resolved),
        projectId = projectId,
        metadata = buildJsonObject {
            put(OS_EXPLORER_TAB_META_KEY, true)
            put(OS_EXPLORER_PATH_META_KEY, resolved)
            put("filePath", key)
            if (projectId != null) put("projectName", projectId)
        },
    )
}

internal fun openOsExplorerTab(
    store: WorkspaceStore,
    path: String = "~",
    projectId: String? = store.state.value.currentProject,
    newInstance: Boolean = false,
): String = store.openTab(osExplorerNewTab(path, projectId, newInstance))

internal fun openOsExplorerTab(
    model: AppModel,
    path: String = model.hostFilesystem?.state?.value?.host?.home ?: "~",
    newInstance: Boolean = false,
) {
    val resolved = path.ifBlank { model.hostFilesystem?.state?.value?.host?.home ?: "~" }
    if (!model.wide || model.layout == null) {
        model.openExplorer(resolved)
        return
    }
    val tab = osExplorerNewTab(resolved, model.selectedProject, newInstance)
    model.openWorkspaceTab(
        type = tab.type,
        title = tab.title,
        metadata = tab.metadata,
        projectId = tab.projectId,
    )
}

@Composable
internal fun HostExplorerTab(model: AppModel, tab: WorkspaceTab) {
    val client = model.api ?: return
    val preferences = model.explorerPreferences ?: remember(client) {
        ExplorerPreferences(
            object : KeyValueStore {
                private val values = mutableMapOf<String, String>()
                override fun get(key: String): String? = values[key]
                override fun put(key: String, value: String?) {
                    if (value == null) values.remove(key) else values[key] = value
                }
            },
            "explorer.tab.",
        )
    }
    val initialPath = remember(tab.id) {
        osExplorerTabPath(tab, model.hostFilesystem?.state?.value?.host?.home ?: "~")
    }
    val session = remember(client, preferences, tab.id) {
        ExplorerSession(client, preferences, model.scope, initialPath) { next ->
            model.updateTabTitleAndMetadata(
                tab.id,
                title = osExplorerTabTitle(next),
                OS_EXPLORER_PATH_META_KEY to JsonPrimitive(next),
            )
        }
    }
    DisposableEffect(session) { onDispose { session.close() } }
    HostExplorerSessionBody(model, session, visible = true)
}

@Composable
fun HostExplorerWindow(model: AppModel, window: WindowRuntimeState) {
    val path = window.payload["path"].string ?: "~"
    HostExplorerBody(model, window.id, path, visible = window.state != WindowVisualState.Minimized)
}

/**
 * The explorer on a phone (`MobileExplorerSheet`): the sheet over the whole of the app's area, `h-[var(--sheet-vh)]`
 * and `p-0`, with the panel's rounded top corners and line, the handle above the title bar, and a swipe down that
 * closes it, which stops while a name is being typed. Its `zIndex` is 45, so the page's fixed corners stay over it.
 * The toolbar sits on the navigation bar, with no 0.5rem under it, and on the keyboard while one is up.
 */
@Composable
fun HostExplorerSheet(model: AppModel, path: String) {
    var renaming by remember { mutableStateOf(false) }
    BottomSheet(
        onDismiss = model::closeExplorerSheet,
        modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars).fillMaxHeight(),
        bottomPadding = WindowInsets.ime.union(WindowInsets.navigationBars),
        overlay = { CornerChrome(model, mobile = true) },
        swipeFromContent = !renaming,
    ) {
        HostExplorerBody(model, "phone-explorer", path, onRenaming = { renaming = it })
    }
}

private data class HostMenu(val entries: List<HostFileEntry>, val at: Offset, val directory: String? = null)

@Composable
private fun HostExplorerBody(model: AppModel, id: String, path: String, visible: Boolean = true, onRenaming: (Boolean) -> Unit = {}) {
    val session = remember(model.api, id) { model.hostExplorer(id, path) } ?: return
    HostExplorerSessionBody(model, session, visible, onRenaming)
}

@Composable
internal fun HostExplorerSessionBody(
    model: AppModel,
    session: ExplorerSession,
    visible: Boolean = true,
    onRenaming: (Boolean) -> Unit = {},
) {
    val hostStore = model.hostFilesystem ?: remember(model.api) {
        model.api?.let { HostFilesystemStore(it, model.scope) }
    } ?: return
    val host by hostStore.state.collectAsState()
    val state by session.state.collectAsState()
    val preferences by session.preferences.state.collectAsState()
    val commands = rememberHostExplorerCommands(model, session, host.host?.platform, state.directory?.separator ?: host.host?.separator ?: "/")
    val uploadQueue = remember(model.api, host.host?.separator, state.directory?.separator) {
        (state.directory?.separator ?: host.host?.separator)?.let(model::hostUploads)
    }
    // `mobile-explorer-sheet.tsx` stops the sheet's swipe while an entry is being renamed (not while one is created).
    val renaming = commands?.edit?.entry != null
    LaunchedEffect(renaming) { onRenaming(renaming) }
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
    var placesDrawerOpen by remember { mutableStateOf(false) }
    // Going to another folder clears the selection, and Select mode follows it, or the bar is left on Cut, Copy and Trash with nothing to act on.
    LaunchedEffect(state.path) {
        selectMode = false
        placesDrawerOpen = false
    }
    var menu by remember { mutableStateOf<HostMenu?>(null) }
    val focus = remember { FocusRequester() }
    val typeAhead = remember { HostTypeAhead() }
    val gridColumns = remember { HostGridColumns() }
    fun open(entry: HostFileEntry) {
        if (entry.isDirectory) session.go(entry.path)
        else if (FileDocumentClassifier.canOpenInPpm(entry.name)) model.openHostFile(entry.path)
    }
    val menuActions = HostExplorerMenuActions(model, session, commands, uploadFiles, downloads, ::copyText, ::open)
    fun copy(targets: List<HostFileEntry>, cut: Boolean) {
        if (targets.isNotEmpty()) model.fileMutations?.setClipboard(
            FileClipboard(targets.map { it.path }, if (cut) FileClipboardOperation.CUT else FileClipboardOperation.COPY))
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(tokens.panel.color)) {
        val showPlaces = !mobile && maxWidth >= 640.dp
        val canTogglePlaces = !mobile && maxWidth < 640.dp
        Column(Modifier.fillMaxSize()) {
            if (mobile) {
                HostPhoneTopBar(hostFolderName(state.path, state.directory?.breadcrumbs.orEmpty()), model::closeExplorerSheet)
                HostPhonePlaces(session, host.host, windows, dropModifier)
            } else HostExplorerToolbar(
                session = session,
                state = state,
                preferences = preferences,
                canUpload = uploadQueue != null,
                commands = commands,
                onTogglePlaces = if (canTogglePlaces) ({ placesDrawerOpen = !placesDrawerOpen }) else null,
                placesOpen = placesDrawerOpen,
            ) { folder ->
                menuActions.run(if (folder) HostMenuCommand.UPLOAD_FOLDER else HostMenuCommand.UPLOAD_FILES, emptyList(), state.currentDirectory)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Row(Modifier.fillMaxSize()) {
                    if (showPlaces) HostExplorerPlaces(session, host.host, windows, dropModifier, commands = commands)
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
                        if (preferences.view != ExplorerView.COLUMNS) {
                            commands?.edit?.takeIf { it.entry == null }?.let { HostInlineName(commands, it) }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth().then(dropModifier(state.currentDirectory))
                            .focusRequester(focus).onKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown || com.hienlh.ppm.app.TextInputFocus.focused) return@onKeyEvent false
                                val now = session.state.value
                                val columns = if (preferences.view == ExplorerView.ICONS) gridColumns.count else null
                                val command = hostKeyCommand(hostKeyPress(event), entries, now.selection, now.anchor, columns, typeAhead,
                                    System.currentTimeMillis()) ?: return@onKeyEvent false
                                when (command) {
                                    HostKeyCommand.Handled -> Unit
                                    HostKeyCommand.Back -> session.back()
                                    HostKeyCommand.Forward -> session.forward()
                                    HostKeyCommand.Up -> session.up()
                                    HostKeyCommand.Paste -> commands?.paste()
                                    HostKeyCommand.SelectAll -> session.selectAll()
                                    HostKeyCommand.ClearSelection -> { session.clearSelection(); selectMode = false }
                                    is HostKeyCommand.Select -> session.select(command.path, range = command.range)
                                    is HostKeyCommand.Open -> open(command.entry)
                                    is HostKeyCommand.Rename -> commands?.rename(command.entry)
                                    is HostKeyCommand.Copy -> copy(command.entries, cut = false)
                                    is HostKeyCommand.Cut -> copy(command.entries, cut = true)
                                    is HostKeyCommand.Trash -> commands?.trash(command.entries)
                                    is HostKeyCommand.DeletePermanently -> commands?.permanentlyDelete(command.entries)
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
                                onMenu = { targets, at -> menu = HostMenu(targets, at) }, dropModifier = dropModifier, gridColumns = gridColumns)
                        }
                    }
                }
                if (canTogglePlaces && placesDrawerOpen) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxHeight().cssBorder(tokens.border.color, sides = CssSides.End)) {
                            HostExplorerPlaces(session, host.host, windows, dropModifier, commands = commands)
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.32f))
                                .ppmClickable(onClickLabel = "Close Places") { placesDrawerOpen = false },
                        )
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
