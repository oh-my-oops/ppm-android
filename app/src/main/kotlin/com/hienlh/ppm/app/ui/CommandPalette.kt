package com.hienlh.ppm.app.ui

import android.content.res.Configuration
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.LocalRedesign
import com.hienlh.ppm.app.liftsAsTap
import com.hienlh.ppm.app.ui.editor.DeviceLspPreference
import com.hienlh.ppm.app.ui.editor.SharedCompareStore
import com.hienlh.ppm.app.ui.extensions.ExtensionIcons
import com.hienlh.ppm.app.ui.tablet.Edge
import com.hienlh.ppm.app.ui.tablet.LocalTabletShellState
import com.hienlh.ppm.app.ui.tablet.edge
import com.hienlh.ppm.client.designList
import com.hienlh.ppm.client.fsList
import com.hienlh.ppm.design.FileIcon
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.LucideIcon
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ThemeShadow
import com.hienlh.ppm.design.WebCursor
import com.hienlh.ppm.design.colorsFade
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssScrollbar
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.design.vector
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.protocol.FileEntry
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.state.workspace.BulkClose
import com.hienlh.ppm.state.FileIndexStore
import com.hienlh.ppm.state.database.nextQueryNumber
import com.hienlh.ppm.state.database.queryTabMetadata
import com.hienlh.ppm.state.design.DesignSummary
import com.hienlh.ppm.state.editor.CompareSelection
import com.hienlh.ppm.state.formatShortcut
import com.hienlh.ppm.state.workspace.SourceLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Tailwind's `shadow-2xl`. */
internal val SHADOW_2XL = ThemeShadow(0.0, 25.0, 50.0, -12.0, Rgba(0.0, 0.0, 0.0, 0.25))

/**
 * Cycles through `All` (`emptySet()`) and each group in [groups] when `Tab` / `Shift+Tab` is
 * pressed inside the command palette search field (`Requirement 11.5`).
 */
internal fun cyclePaletteFilter(
    groups: List<PaletteGroup>,
    active: Set<PaletteGroup>,
    forward: Boolean = true,
): Set<PaletteGroup> {
    if (groups.size <= 1) return emptySet()
    val slots: List<Set<PaletteGroup>> = listOf(emptySet<PaletteGroup>()) + groups.map { setOf(it) }
    val currentIndex = slots.indexOfFirst { it == active }.takeIf { it >= 0 } ?: 0
    val nextIndex = if (forward) {
        (currentIndex + 1) % slots.size
    } else {
        (currentIndex - 1 + slots.size) % slots.size
    }
    return slots[nextIndex]
}

/**
 * Whether desktop-only actions (`Turn On/Off Language Server`) are shown in the Command Palette:
 * hidden on phone, and hidden on pure-touch tablet unless a hardware keyboard or pointer is connected.
 */
internal fun shouldShowDesktopOnlyPaletteActions(
    mobile: Boolean,
    hasHardwareKeyboard: Boolean,
    hasPointer: Boolean,
): Boolean = !mobile && (hasHardwareKeyboard || hasPointer)

/** One action (`CommandItem`): what it is called, the words that also find it, its shortcut, what it does. */
private fun action(
    label: String,
    icon: ProductIcon? = null,
    iconVector: ImageVector? = null,
    words: String,
    hint: String? = null,
    shortcut: String? = null,
    run: () -> Unit,
) =
    // The web searches the words alone, so typing "settings" never finds Settings; the label is searched here too.
    PaletteItem(PaletteGroup.ACTION, label, "$label $words", hint = hint, shortcut = shortcut, icon = icon, iconVector = iconVector, action = run)

/**
 * The command palette (command-palette.tsx): a search over the app's actions, the open project's
 * files and, for a path that starts with `/`, `~/` or a drive, the host's own folders; Enter on no
 * match asking AI in a new chat. A `:120` or `:120-140` after a path opens the file there. Chips
 * narrow the list to actions, files or the host's files once more than one kind exists. On the
 * tablet layout a 640 dp card (flipping its search input to the bottom when the soft keyboard is
 * up on touch). ↑ ↓ pick, Tab cycles filters, Enter runs, Esc or a tap outside closes.
 */
@Composable
fun CommandPalette(model: AppModel, initialQuery: String, onDismiss: () -> Unit) {
    val sizeClass = LocalSizeClass.current
    val redesign = LocalRedesign.current
    // On redesigned phone (`< 600 dp`), Command Palette is replaced by the Search tab.
    if (sizeClass.isPhone && redesign) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val configuration = LocalConfiguration.current
    val hasHardwareKeyboard = configuration.keyboard != Configuration.KEYBOARD_NOKEYS
    val hasPointer = LocalTabletShellState.current?.pointerConnected == true
    val showDesktopOnlyActions = if (redesign) {
        shouldShowDesktopOnlyPaletteActions(mobile = mobile, hasHardwareKeyboard = hasHardwareKeyboard, hasPointer = hasPointer)
    } else {
        !mobile
    }
    val query = rememberTextFieldState(initialQuery)
    val focus = remember { FocusRequester() }
    var quietFocus by remember { mutableStateOf(true) }
    val softwareKeyboard = LocalSoftwareKeyboardController.current
    val list = rememberLazyListState()
    var selected by remember { mutableIntStateOf(0) }
    var activeGroups by remember { mutableStateOf(emptySet<PaletteGroup>()) }
    val bindings = model.keyBindings
    val extContribs = model.extensions?.contributions?.collectAsState()?.value
    val lspEnabled by DeviceLspPreference.enabled.collectAsState()
    val project = model.selectedProject
    val api = model.api
    var designs by remember(project) { mutableStateOf(emptyList<DesignSummary>()) }
    LaunchedEffect(project, api) {
        if (project == null || api == null) {
            designs = emptyList()
            return@LaunchedEffect
        }
        val raw = runCatching { api.designList(project) }.getOrNull()
        val arr = raw?.get("designs")?.array ?: raw?.array.orEmpty()
        designs = arr.mapNotNull { DesignSummary.fromJson(it) }
    }
    val actions = remember(model, bindings, extContribs, mobile, showDesktopOnlyActions, lspEnabled, project, designs) {
        fun run(action: () -> Unit): () -> Unit = {
            onDismiss()
            action()
        }
        val builtIn = buildList {
            add(action("New AI Chat", ProductIcon.MessageSquare, words = "ai assistant claude", shortcut = formatShortcut(bindings.binding("open-chat")), run = run { model.newChat() }))
            add(action("New File", ProductIcon.FilePlus, words = "create untitled blank empty", shortcut = formatShortcut(bindings.binding("new-file")), run = run { model.openNewFile() }))
            add(action("New DB Query", ProductIcon.Database, words = "sql database query scratchpad new", run = run {
                val existingTabs = model.layout?.panels?.values?.flatMap { it.tabs }.orEmpty()
                val qNum = nextQueryNumber(existingTabs)
                model.openWorkspaceTab(type = "db-query", title = "Query $qNum", metadata = queryTabMetadata(sql = "", queryNumber = qNum))
            }))
            // A terminal tab in the focused panel (the first on a phone), as the web opens it; the terminal panel is the nav bar's and Ctrl+`'s.
            add(action("New Terminal", ProductIcon.Terminal, words = "bash shell console", shortcut = formatShortcut(bindings.binding("open-terminal")), run = run { model.openTerminal(inDock = false) }))
            add(action("Remote Access", ProductIcon.MonitorSmartphone, words = "remote access tunnel cloudflare tailscale public link share url phone domain", run = run { model.openSettings("remote-access") }))
            add(action("Open Web Preview / Forward a Port", ProductIcon.Globe, words = "forward port forwarding localhost web preview browser tab open website tunnel cloudflare tailscale dev server url", run = run { model.openPortForwarding() }))
            add(action("PPM Cloud & Share", ProductIcon.Cloud, words = "cloud permanent link alias share phone remote device qr sign in login", run = run { model.cloudShareOpen = true }))
            add(action("New connection…", ProductIcon.Database, words = "database connection postgres pg mysql mariadb sqlite add", run = run {
                model.openWorkspaceTab(type = "db-connection", title = "New Connection", metadata = buildJsonObject {})
            }))
            add(action("Voice Input", ProductIcon.Mic, words = "speech microphone dictate voice", shortcut = formatShortcut(bindings.binding("voice-input")), run = run { model.toggleVoiceInput() }))
            add(action("Git Status", iconVector = LucideIcon.GitCommitHorizontal.vector, words = "changes diff staged", shortcut = formatShortcut(bindings.binding("open-git-status")), run = run { model.showSidebarSection("git") }))
            add(action("Pin / Unpin Tab", ProductIcon.Pin, words = "pin unpin sticky keep tab", shortcut = formatShortcut(bindings.binding("toggle-pin-tab")), run = run { model.togglePinActiveTab() }))
            add(action("Close Saved Tabs", ProductIcon.X, words = "close saved tabs editors", shortcut = formatShortcut(bindings.binding("close-saved-tabs")), run = run { model.bulkCloseInFocusedPanel(BulkClose.SAVED) }))
            add(action("Close All Tabs", ProductIcon.X, words = "close all tabs editors", shortcut = formatShortcut(bindings.binding("close-all-tabs")), run = run { model.bulkCloseInFocusedPanel(BulkClose.ALL) }))
            add(action("Problems", ProductIcon.CircleX, words = "errors warnings diagnostics lint typescript", shortcut = formatShortcut(bindings.binding("open-problems")), run = run { model.openProblems() }))
            add(action(
                label = "Toggle Word Wrap",
                icon = ProductIcon.WrapText,
                words = "wrap unwrap word lines editor soft",
                shortcut = if (mobile) null else "Alt+Z",
                run = run { model.setWordWrapPref(!(if (model.wide) model.wordWrap else model.mobileWordWrap)) },
            ))
            add(action(
                label = "Compare Files...",
                icon = ProductIcon.Columns2,
                words = "diff compare two files select",
                shortcut = formatShortcut(bindings.binding("compare-files")),
                run = run {
                    val focusedPanel = model.layout?.let { it.panels[it.focusedPanelId] }
                    val activeTab = focusedPanel?.tabs?.firstOrNull { it.id == focusedPanel.activeTabId }
                    val fp = activeTab?.takeIf { it.type == "editor" }?.let { it.metaString("filePath") ?: it.metaString("path") }
                    val proj = activeTab?.metaString("projectName") ?: project
                    if (!fp.isNullOrBlank() && !proj.isNullOrBlank()) {
                        SharedCompareStore.store.selectForCompare(
                            CompareSelection(
                                filePath = fp,
                                projectName = proj,
                                dirtyContent = activeTab.metaString("unsavedContent"),
                            ),
                        )
                    }
                    model.comparePickerOpen = true
                },
            ))
            if (showDesktopOnlyActions) {
                add(action(
                    label = if (lspEnabled) "Turn Off Language Server" else "Turn On Language Server",
                    icon = ProductIcon.Zap,
                    words = "lsp language server completions intellisense hover definition typescript pyright gopls",
                    hint = "This device",
                    run = run {
                        val next = !DeviceLspPreference.enabled.value
                        DeviceLspPreference.setEnabled(next)
                        model.setLspEnabledPref(next)
                    },
                ))
            }
            add(action("Settings", ProductIcon.Settings, words = "config preferences theme", shortcut = formatShortcut(bindings.binding("open-settings")), run = run { model.openSettings() }))
            add(action("Open File Explorer", ProductIcon.AppWindow, words = "open file explorer finder browse files folders disk drive window", run = run { model.openExplorer() }))
            add(action("System Monitor", ProductIcon.Cpu, words = "task manager activity monitor cpu memory ram disk network gpu processes services apps performance resources", run = run { model.openSystemMonitor() }))
            add(action("Logs", ProductIcon.ScrollText, words = "logs server errors warnings debug tail ppm.log browser console cloudflared", run = run { model.openLogs() }))
            add(action("Report a Bug", ProductIcon.Bug, words = "report bug issue github feedback problem crash", run = run { model.openLogs("report") }))
        }
        val designItems = if (project == null) emptyList() else buildList {
            add(action("New Design…", ProductIcon.Plus, words = "design mockup prototype page slides deck presentation canvas create", run = run { model.newDesignProject = project }))
            add(action("Designs", ProductIcon.Palette, words = "design list canvas", run = run { model.showSidebarSection("designs") }))
            for (d in designs) {
                add(action(
                    label = "Open Design: ${d.title}",
                    icon = ProductIcon.Palette,
                    words = "design ${d.slug} ${d.kind.wire}",
                    hint = "designs/${d.slug}",
                    run = run { model.openDesignTab(slug = d.slug, title = d.title, projectName = project) },
                ))
            }
        }
        val dbItems = if (mobile) emptyList() else listOf(
            action(
                label = "Export database",
                icon = ProductIcon.ArrowRightFromLine,
                words = "database export tables csv json excel xlsx xml sql ndjson zip file",
                run = run {
                    model.openWorkspaceTab(
                        type = "db-impexp",
                        title = "Export",
                        metadata = buildJsonObject {
                            put("impexpId", "default")
                            put("mode", "export")
                        },
                    )
                },
            ),
            action(
                label = "Import data",
                icon = ProductIcon.Upload,
                words = "database import upload csv json ndjson file table",
                run = run {
                    model.openWorkspaceTab(
                        type = "db-impexp",
                        title = "Import",
                        metadata = buildJsonObject {
                            put("impexpId", "default")
                            put("mode", "import")
                        },
                    )
                },
            ),
        )
        val extKbs = extContribs?.keybindings.orEmpty()
        val extItems = extContribs?.commands.orEmpty().map { cmd ->
            val kb = extKbs.firstOrNull { it.command == cmd.command }
            val overrideCombo = if (kb != null) bindings.binding("ext:${kb.command}") else ""
            val shortcutCombo = overrideCombo.ifEmpty { kb?.key.orEmpty() }
            action(
                label = cmd.title,
                iconVector = ExtensionIcons.icon(cmd.icon) ?: ProductIcon.Puzzle.vector,
                words = "extension ${cmd.command} ${cmd.category.orEmpty()}",
                hint = cmd.category,
                shortcut = formatShortcut(shortcutCombo),
                run = run { model.dispatchExtCommand(cmd.command) },
            )
        }
        builtIn + designItems + dbItems + extItems
    }
    val text = query.text.toString()
    val typed = remember(text) { PaletteQuery(text) }
    val pathMode = isPathQuery(typed.path)

    // The project's files: read as the palette opens, or again if files changed since, and kept
    // current while it stays open. Until they land, whatever the explorer's tree has read.
    val fileStore = model.fileIndex
    DisposableEffect(fileStore, project) {
        val close = if (fileStore != null && project != null) fileStore.openReader(project) else null
        onDispose { close?.invoke() }
    }
    val index = fileStore?.state?.collectAsState()?.value
    val indexRemote = index?.remote == true
    // A project too long to send is searched on the server as the query changes; the answer is
    // ranked here with everything else, and what was last answered stays until the next lands.
    var remoteFiles by remember { mutableStateOf(emptyList<FileEntry>()) }
    val searching = indexRemote && typed.path.isNotBlank() && !pathMode
    LaunchedEffect(fileStore, typed.path, searching, index?.revision) {
        if (fileStore == null || !searching) {
            remoteFiles = emptyList()
            return@LaunchedEffect
        }
        // A failed search keeps what is shown.
        fileStore.searchRemote(typed.path, directories = false)?.let { remoteFiles = it }
    }
    val indexReady = index != null && index.status == FileIndexStore.Status.READY && index.project == project
    val indexEntries = index?.entries
    val indexedFiles = remember(indexEntries) { indexEntries.orEmpty().filter { !it.isDirectory } }
    val tree = model.fileTree?.state?.collectAsState()?.value
    val treeFiles = remember(tree?.directories, project) { loadedTreeFiles(tree, project) }
    val files = when {
        indexRemote -> remoteFiles
        indexReady -> indexedFiles
        else -> treeFiles
    }

    // The host's files for a path query: its folder is listed once however much of the name follows.
    var filesystemPaths by remember { mutableStateOf(emptyList<String>()) }
    var listing by remember { mutableStateOf(false) }
    val listed = remember { HashMap<String, List<String>>() }
    val directory = if (pathMode) listedDirectory(typed.path) else null
    LaunchedEffect(directory, api) {
        listing = false
        if (directory == null || api == null) {
            filesystemPaths = emptyList()
            return@LaunchedEffect
        }
        listed[directory]?.let {
            filesystemPaths = it
            return@LaunchedEffect
        }
        listing = true
        filesystemPaths = try {
            api.fsList(directory).also { listed[directory] = it }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
        listing = false
    }
    val filesystemItems = remember(filesystemPaths) { filesystemPaths.map(::filesystemItem) }

    // The search, kept off the main thread: a project holds up to fifty thousand files.
    val searchIndex by produceState(remember(actions) { PaletteSearchIndex(actions) }, actions, files) {
        value = withContext(Dispatchers.Default) { PaletteSearchIndex(actions + files.map(::fileItem)) }
    }
    val ranking = searchIndex
    val ranked by produceState(PaletteRanked("", actions), actions, ranking, filesystemItems, typed.path) {
        value = withContext(Dispatchers.Default) { PaletteRanked(typed.path, paletteResults(ranking, actions, filesystemItems, typed.path)) }
    }
    val filtered = ranked.items
    // The list on screen can still be the one for the text before; an Enter must not run its first row.
    val answered = ranked.answers(typed.path, listing)
    val groups = availableGroups(hasActions = actions.isNotEmpty(), hasFiles = files.isNotEmpty(), hasFilesystem = filesystemPaths.isNotEmpty())
    val counts = remember(filtered) { groupCounts(filtered) }
    val shown = remember(filtered, activeGroups) { visibleResults(filtered, activeGroups) }
    LaunchedEffect(shown.size) { selected = selected.coerceAtMost(maxOf(shown.size - 1, 0)) }
    // `scrollIntoView({ block: "nearest" })`.
    LaunchedEffect(selected) {
        val info = list.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == selected }
        when {
            item == null || item.offset < info.viewportStartOffset -> list.scrollToItem(selected)
            item.offset + item.size > info.viewportEndOffset -> list.scrollBy((item.offset + item.size - info.viewportEndOffset).toFloat())
        }
    }
    // Read when a file is picked, not closed over per row: the line is the query's as it stands.
    val line by rememberUpdatedState(typed.line)
    fun pick(item: PaletteItem) {
        val run = item.action
        if (run != null) {
            run()
            return
        }
        onDismiss()
        // The tab is of the project, the path as listed: relative from its list, absolute from the host's.
        if (project != null) model.openFileTab(project, item.keywords, line?.start, line?.end) else model.openHostFile(item.keywords)
    }
    fun toggleGroup(group: PaletteGroup) {
        activeGroups = if (group in activeGroups) activeGroups - group else activeGroups + group
        selected = 0
    }
    fun clearGroups() {
        activeGroups = emptySet()
        selected = 0
    }
    fun askAi() {
        val message = text.trim()
        if (message.isEmpty()) return
        onDismiss()
        model.newChat(pendingMessage = message)
    }
    // The web's list follows the text within a frame, so its Enter runs on the list of what is typed. Here an Enter that
    // comes before that list lands waits for it, and typing on drops it.
    val enterGate = remember { PaletteEnter() }
    fun act() {
        // `selected` is clamped by an effect that may not have run yet for a list that has just landed.
        val item = shown.getOrNull(selected.coerceAtMost(shown.lastIndex))
        if (item != null) pick(item) else askAi()
    }
    fun enter() {
        if (enterGate.press(text, answered)) act()
    }
    LaunchedEffect(text, answered, ranked) {
        if (enterGate.due(text, answered)) act()
    }

    if (closedByConnectionLost(onDismiss)) return
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.setDimAmount(0f)
            window?.setSoftInputMode(
                if (quietFocus) WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                else WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED,
            )
        }
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(windowFocused) {
            if (windowFocused && quietFocus) {
                focus.requestFocus()
            }
        }
        val imeBottom = WindowInsets.ime.exclude(WindowInsets.navigationBars).asPaddingValues().calculateBottomPadding()
        val keyboardUpOnTouch = redesign && !mobile && imeBottom > 0.dp && !quietFocus && !hasHardwareKeyboard

        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            )
            val searchRow: @Composable (Boolean, Boolean) -> Unit = { showEsc, bottomBorder ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (bottomBorder) Modifier.edge(Edge.Bottom, tokens.border.color) else Modifier.edge(Edge.Top, tokens.border.color))
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                quietFocus = false
                                if (liftsAsTap(down, PointerEventPass.Final)) {
                                    softwareKeyboard?.show()
                                }
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PpmIcon(ProductIcon.Search, 16.dp, tokens.text3.color)
                    PpmTextField(
                        query,
                        "Search actions & files... (type / or ~/ for filesystem)",
                        Modifier.weight(1f).onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val count = shown.size
                            when (event.key) {
                                Key.DirectionDown -> if (count > 0) selected = (selected + 1) % count
                                Key.DirectionUp -> if (count > 0) selected = (selected - 1 + count) % count
                                Key.Tab -> {
                                    if (!pathMode && groups.size > 1) {
                                        activeGroups = cyclePaletteFilter(groups, activeGroups, forward = !event.isShiftPressed)
                                        selected = 0
                                    }
                                }
                                Key.Enter, Key.NumPadEnter -> enter()
                                Key.Escape -> onDismiss()
                                else -> return@onPreviewKeyEvent false
                            }
                            true
                        },
                        desktopStyle = ppmText(14f, lineHeight = 20f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, showKeyboardOnFocus = !quietFocus),
                        onKeyboardAction = { enter() },
                        focusRequester = focus,
                        onFocusChanged = { if (!it) quietFocus = false },
                    )
                    if (listing) PpmIcon(ProductIcon.Loader2, 14.dp, tokens.text3.color, spinning = true)
                    if (showEsc) Kbd("ESC", horizontal = 6)
                }
            }
            val resultsSection: @Composable () -> Unit = {
                if (pathMode && !listing && filesystemPaths.isEmpty() && text.length < 4) {
                    HintLine("Type a directory path to browse files (e.g. ~/Projects/)")
                }
                val status = index?.status ?: FileIndexStore.Status.IDLE
                if (!pathMode && (status == FileIndexStore.Status.LOADING || status == FileIndexStore.Status.IDLE)) IndexingLine()
                if (!pathMode && status == FileIndexStore.Status.FAILED) {
                    RetryLine { if (fileStore != null && project != null) fileStore.load(project) }
                }
                if (shown.isEmpty()) {
                    when {
                        listing -> EmptyLine("Searching...")
                        activeGroups.isNotEmpty() && filtered.isNotEmpty() -> FilteredOutEmptyState(
                            otherCount = filtered.size,
                            onShowAll = ::clearGroups,
                        )
                        text.isNotBlank() -> AskAiRow(text.trim(), ::askAi)
                        else -> EmptyLine("No results")
                    }
                } else {
                    LazyColumn(
                        Modifier.heightIn(max = 320.dp).cssScrollbar(list),
                        list,
                        PaddingValues(vertical = 4.dp),
                    ) {
                        itemsIndexed(shown) { position, item ->
                            PaletteRow(
                                item = item,
                                selected = position == selected,
                                targetLine = typed.line,
                                onFillPath = if (item.group == PaletteGroup.FILESYSTEM) {
                                    { query.setTextAndPlaceCursorAtEnd(item.keywords) }
                                } else {
                                    null
                                },
                                onPick = { pick(item) },
                            )
                        }
                    }
                }
            }
            val card: @Composable (Modifier, Boolean) -> Unit = { modifier, showEsc ->
                Column(modifier) {
                    if (keyboardUpOnTouch) {
                        if (!pathMode && groups.size > 1) {
                            FilterChips(groups, counts, filtered.size, activeGroups, ::toggleGroup, ::clearGroups)
                        }
                        resultsSection()
                        searchRow(showEsc, false)
                    } else {
                        searchRow(showEsc, true)
                        if (!pathMode && groups.size > 1) {
                            FilterChips(groups, counts, filtered.size, activeGroups, ::toggleGroup, ::clearGroups)
                        }
                        resultsSection()
                        Row(
                            Modifier.fillMaxWidth().edge(Edge.Top, tokens.border.color).padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                        ) {
                            val hint = ppmText(10f, lineHeight = 15f).copy(color = tokens.text3.color)
                            BasicText("Press", style = hint)
                            Kbd("Shift", horizontal = 4)
                            Kbd("Shift", horizontal = 4)
                            BasicText("to open this palette", style = hint)
                        }
                    }
                }
            }
            val maxCardWidth = if (redesign) 640.dp else 448.dp
            if (mobile) {
                // On the bottom edge, its background under the navigation bar, and above the keyboard.
                BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
                    val shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                    card(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .widthIn(max = maxCardWidth)
                            .fillMaxWidth()
                            .heightIn(max = maxHeight * 0.8f)
                            .ppmShadow(SHADOW_2XL, shape)
                            .clip(shape)
                            .background(tokens.bgSolid.color)
                            .cssBorder(tokens.border.color, shape)
                            .navigationBarsPadding()
                            .imePadding(),
                        maxWidth >= 640.dp,
                    )
                }
            } else {
                // `md:pt-[14vh]`, or docked above the keyboard when flipped on touch.
                BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
                    val top = if (keyboardUpOnTouch) 16.dp else maxHeight * 0.16f
                    val shape = RoundedCornerShape(12.dp)
                    card(
                        Modifier
                            .align(if (keyboardUpOnTouch) Alignment.BottomCenter else Alignment.TopCenter)
                            .padding(
                                top = top,
                                bottom = if (keyboardUpOnTouch) imeBottom + 8.dp else 0.dp,
                                start = 16.dp,
                                end = 16.dp,
                            )
                            .widthIn(max = maxCardWidth)
                            .fillMaxWidth()
                            .heightIn(max = (maxHeight - top - imeBottom).coerceAtLeast(160.dp))
                            .ppmShadow(SHADOW_2XL, shape)
                            .clip(shape)
                            .background(tokens.bgSolid.color)
                            .cssBorder(tokens.border.color, shape),
                        true,
                    )
                }
            }
        }
    }
}

/**
 * A result (`56 dp` min height on tablet redesign): picked in the `accent` surface at 15% and the
 * text colour, else hovered in `surface-elevated`. Supports line-target badges (`CP2`) and
 * filesystem `↖` path-fill (`CP3`).
 */
@Composable
private fun PaletteRow(
    item: PaletteItem,
    selected: Boolean,
    targetLine: SourceLine? = null,
    onFillPath: (() -> Unit)? = null,
    onPick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val redesign = LocalRedesign.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val picked = colorsFade(selected).toDouble()
    val lit = colorsFade(hovered && !selected).toDouble()
    val color = tokens.text.mix(tokens.text2, picked).color
    val showLineJump = targetLine != null && item.group != PaletteGroup.ACTION
    val displayHint = when {
        showLineJump && item.hint != null -> "${item.hint} · line ${targetLine!!.start}"
        showLineJump -> "line ${targetLine!!.start}"
        else -> item.hint
    }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (redesign) Modifier.heightIn(min = 56.dp) else Modifier)
            .then(if (item.ignored) Modifier.alpha(0.6f).webTitle("Gitignored file") else Modifier)
            .background(tokens.panel2.opacity(0.15).mix(tokens.panel2.opacity(lit), picked).color)
            .hoverable(hover)
            .ppmClickable(pressedColor = tokens.panel2.color, onClick = onPick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            item.group == PaletteGroup.FILE -> FileIcon(item.label)
            item.iconVector != null -> PpmIcon(item.iconVector, 16.dp, color)
            else -> PpmIcon(item.icon ?: item.group.icon, 16.dp, color)
        }
        BasicText(
            item.label,
            Modifier.weight(1f),
            style = ppmText(14f, lineHeight = 20f).copy(color = color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        displayHint?.let {
            BasicText(
                it,
                Modifier.widthIn(max = 220.dp),
                style = ppmText(12f, lineHeight = 16f).copy(color = if (showLineJump && selected) tokens.accent.color else tokens.text3.color),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
        if (showLineJump && selected) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(tokens.accent.opacity(0.16).color)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                BasicText(
                    "Go to line",
                    style = ppmText(10f, FontWeight.SemiBold, kind = FontKind.MONO, lineHeight = 14f).copy(color = tokens.accent.color),
                )
            }
        }
        if (onFillPath != null) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .semantics { contentDescription = "Fill path" }
                    .ppmClickable(
                        hoverColor = tokens.panel2.color,
                        pressedColor = tokens.panel2.color,
                        onClickLabel = "Fill path",
                        onClick = onFillPath,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.ArrowUpRight, 14.dp, tokens.text2.color)
            }
        }
        item.shortcut?.takeIf { it.isNotEmpty() }?.let { Kbd(it, horizontal = 6) }
    }
}

/** A line of help under the search box (`px-3 py-2 text-xs text-text-subtle border-b border-border/50`). */
@Composable
private fun HintLine(text: String) {
    val tokens = LocalPpmTokens.current
    BasicText(
        text,
        Modifier.fillMaxWidth().edge(Edge.Bottom, tokens.border.opacity(0.5).color).padding(horizontal = 12.dp, vertical = 8.dp),
        style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
    )
}

/**
 * "Indexing project…" while the project's file list is read (`px-3 py-1.5 gap-1.5`, a 12 px
 * spinner, `text-[11px]` italic in the subtle colour).
 */
@Composable
private fun IndexingLine() {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier.fillMaxWidth().edge(Edge.Bottom, tokens.border.opacity(0.5).color).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PpmIcon(ProductIcon.Loader2, 12.dp, tokens.text3.color, spinning = true)
        BasicText("Indexing project…", style = ppmText(11f, lineHeight = 16.5f, italic = true).copy(color = tokens.text3.color))
    }
}

/** "Failed to build file index — retry": `retry` is the accent, underlined under a mouse. */
@Composable
private fun RetryLine(onRetry: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover(instant = true)
    val small = ppmText(11f, lineHeight = 16.5f)
    Row(
        Modifier.fillMaxWidth().edge(Edge.Bottom, tokens.border.opacity(0.5).color).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText("Failed to build file index —", style = small.copy(color = tokens.text3.color))
        BasicText(
            "retry",
            Modifier.ppmClickable(pressedColor = Color.Transparent, source = hover.source, onClick = onRetry),
            style = small.copy(color = tokens.accent.color, textDecoration = if (hover.lit > 0f) TextDecoration.Underline else null),
        )
    }
}

/**
 * The filter chips (`CommandPaletteFilterChips`): an "All" chip plus one switch per kind of result
 * that exists, each with how many of the list's results are of it.
 */
@Composable
private fun FilterChips(
    groups: List<PaletteGroup>,
    counts: Map<PaletteGroup, Int>,
    totalCount: Int,
    active: Set<PaletteGroup>,
    onToggle: (PaletteGroup) -> Unit,
    onClearAll: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val allSelected = active.isEmpty()
    val allHover = rememberHover()
    val allText = if (allSelected) tokens.accent else tokens.text3
    Row(
        Modifier
            .fillMaxWidth()
            .edge(Edge.Bottom, tokens.border.opacity(0.5).color)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier
                .clip(CircleShape)
                .background((if (allSelected) tokens.accent.opacity(0.15) else tokens.panel.mix(tokens.panel2, allHover.lit.toDouble())).color)
                .cssBorder((if (allSelected) tokens.accent else tokens.border).color, CircleShape)
                .pointerHoverIcon(WebCursor.Default)
                .ppmClickable(pressedColor = Color.Transparent, shape = CircleShape, role = Role.Switch, source = allHover.source) { onClearAll() }
                .semantics {
                    toggleableState = ToggleableState(allSelected)
                    contentDescription = "All results"
                }
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BasicText("All", style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = allText.color), maxLines = 1)
            BasicText("($totalCount)", Modifier.alpha(0.7f), style = ppmText(10f, lineHeight = 13.33f).copy(color = allText.color), maxLines = 1)
        }
        for (group in groups) {
            val count = counts[group] ?: 0
            val on = group in active
            val hover = rememberHover()
            val shape = CircleShape
            val text = if (on) tokens.accent else tokens.text3
            val name = "Filter by ${group.label}"
            Row(
                Modifier
                    .alpha(if (count == 0) 0.5f else 1f)
                    .clip(shape)
                    .background((if (on) tokens.accent.opacity(0.15) else tokens.panel.mix(tokens.panel2, hover.lit.toDouble())).color)
                    .cssBorder((if (on) tokens.accent else tokens.border).color, shape)
                    .pointerHoverIcon(WebCursor.Default)
                    .ppmClickable(pressedColor = Color.Transparent, shape = shape, role = Role.Switch, source = hover.source) { onToggle(group) }
                    .semantics {
                        toggleableState = ToggleableState(on)
                        contentDescription = name
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PpmIcon(group.icon, 12.dp, text.color)
                BasicText(group.label, style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = text.color), maxLines = 1)
                BasicText("($count)", Modifier.alpha(0.7f), style = ppmText(10f, lineHeight = 13.33f).copy(color = text.color), maxLines = 1)
            }
        }
    }
}

@Composable
private fun FilteredOutEmptyState(
    otherCount: Int,
    onShowAll: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(
            "No results in selected filters",
            style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text2.color, textAlign = TextAlign.Center),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText(
                "$otherCount results in other filters.",
                style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
            )
            val btnShape = RoundedCornerShape(6.dp)
            Box(
                Modifier
                    .clip(btnShape)
                    .background(tokens.accent.opacity(0.15).color)
                    .cssBorder(tokens.accent.opacity(0.4).color, btnShape)
                    .ppmClickable(onClickLabel = "Show all", onClick = onShowAll)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                BasicText(
                    "Show all",
                    style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.accent.color),
                )
            }
        }
    }
}

/**
 * "Ask AI: …" (`px-3 py-3`), the query in the text colour, cut at 60 characters;
 * `hover:bg-accent/15 hover:text-text-primary` faded in.
 */
@Composable
private fun AskAiRow(query: String, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover()
    val wash = tokens.panel2.opacity(0.15).color
    Row(
        Modifier
            .fillMaxWidth()
            .ppmClickable(pressedColor = wash, hoverColor = wash, source = hover.source, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PpmIcon(ProductIcon.MessageSquare, 16.dp, tokens.accent.color)
        BasicText(
            buildAnnotatedString {
                append("Ask AI: ")
                withStyle(SpanStyle(color = tokens.text.color, fontWeight = FontWeight.Medium)) { append(query.take(60)) }
            },
            style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text.mix(tokens.text2, hover.lit.toDouble()).color),
        )
    }
}

@Composable
private fun EmptyLine(text: String) {
    val tokens = LocalPpmTokens.current
    BasicText(
        text,
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
        style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text3.color, textAlign = TextAlign.Center),
    )
}

/** `rounded border border-border bg-surface py-0.5 text-[10px] text-text-subtle font-mono`, [horizontal] being its `px`. */
@Composable
private fun Kbd(text: String, horizontal: Int) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(4.dp)
    BasicText(
        text,
        Modifier
            .clip(shape)
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, shape)
            .padding(horizontal = horizontal.dp, vertical = 2.dp),
        style = ppmText(10f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text3.color),
        maxLines = 1,
    )
}
