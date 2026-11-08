package com.hienlh.ppm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
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
import com.hienlh.ppm.app.ui.tablet.Edge
import com.hienlh.ppm.app.ui.tablet.edge
import com.hienlh.ppm.client.fsList
import com.hienlh.ppm.design.FileIcon
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
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
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.protocol.FileEntry
import com.hienlh.ppm.state.FileIndexStore
import com.hienlh.ppm.state.formatShortcut
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Tailwind's `shadow-2xl`. */
internal val SHADOW_2XL = ThemeShadow(0.0, 25.0, 50.0, -12.0, Rgba(0.0, 0.0, 0.0, 0.25))

/** One action (`CommandItem`): what it is called, the words that also find it, its shortcut, what it does. */
private fun action(label: String, icon: ProductIcon, words: String, shortcut: String?, run: () -> Unit) =
    // The web searches the words alone, so typing "settings" never finds Settings; the label is searched here too.
    PaletteItem(PaletteGroup.ACTION, label, "$label $words", shortcut = shortcut, icon = icon, action = run)

/**
 * The command palette (command-palette.tsx): a search over the app's actions, the open project's
 * files and, for a path that starts with `/`, `~/` or a drive, the host's own folders; Enter on no
 * match asking AI in a new chat. A `:120` or `:120-140` after a path opens the file there. Chips
 * narrow the list to actions, files or the host's files once more than one kind exists. On the
 * desktop layout a 448 dp card a fifth of the way down; on a phone a sheet on the bottom edge, at
 * most 80% of the screen. ↑ ↓ pick, Enter runs, Esc or a tap outside closes. Database tables and
 * the actions of features this app does not have yet join with those features.
 *
 * What is searched is read from the file list while the palette is open, and ranked off the main
 * thread: the list shown stays until the next one lands, as it does for the web's deferred query.
 */
@Composable
fun CommandPalette(model: AppModel, initialQuery: String, onDismiss: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val query = rememberTextFieldState(initialQuery)
    val focus = remember { FocusRequester() }
    val list = rememberLazyListState()
    var selected by remember { mutableIntStateOf(0) }
    var activeGroups by remember { mutableStateOf(emptySet<PaletteGroup>()) }
    val bindings = model.keyBindings
    val extContribs = model.extensions?.contributions?.collectAsState()?.value
    val androidAvailable = model.androidAvailable
    val actions = remember(model, bindings, extContribs, androidAvailable) {
        fun run(action: () -> Unit): () -> Unit = {
            onDismiss()
            action()
        }
        val builtIn = buildList {
            add(action("New AI Chat", ProductIcon.MessageSquare, "ai assistant claude", formatShortcut(bindings.binding("open-chat")), run { model.newChat() }))
            // A terminal tab in the focused panel (the first on a phone), as the web opens it; the terminal panel is the nav bar's and Ctrl+`'s.
            add(action("New Terminal", ProductIcon.Terminal, "bash shell console", formatShortcut(bindings.binding("open-terminal")), run { model.openTerminal(inDock = false) }))
            add(action("Voice Input", ProductIcon.Mic, "speech microphone dictate voice", formatShortcut(bindings.binding("voice-input")), run { model.toggleVoiceInput() }))
            add(action("Settings", ProductIcon.Settings, "config preferences theme", formatShortcut(bindings.binding("open-settings")), run { model.openSettings() }))
            add(action("Open File Explorer", ProductIcon.AppWindow, "open file explorer finder browse files folders disk drive window", null, run { model.openExplorer() }))
            if (androidAvailable) {
                add(action("Android Devices", ProductIcon.Smartphone, "android emulator avd device virtual phone tablet", null, run { model.openAndroid() }))
            }
            add(action("Remote Desktop", ProductIcon.Monitor, "remote desktop screen share vnc viewer display", null, run { model.openRemoteDesktop() }))
            add(action("System Monitor", ProductIcon.Activity, "cpu memory ram disk network gpu processes services monitor", null, run { model.openSystemMonitor() }))
            add(action("Logs", ProductIcon.ScrollText, "logs output console errors warnings tail", null, run { model.openLogs() }))
            add(action("Report Bug", ProductIcon.Bug, "bug report github issue crash", null, run { model.openLogs("report") }))
            add(action("Forward a Port", ProductIcon.Globe, "port forward tunnel tailscale cloudflare preview", null, run { model.openPortForwarding() }))
        }
        val extItems = extContribs?.commands.orEmpty().map { cmd ->
            val label = if (!cmd.category.isNullOrEmpty()) "${cmd.category}: ${cmd.title}" else cmd.title
            val icon = when (cmd.icon) {
                "git-branch", "git-compare" -> ProductIcon.GitBranch
                "history" -> ProductIcon.History
                "users" -> ProductIcon.Users
                "list-ordered" -> ProductIcon.ListOrdered
                "refresh" -> ProductIcon.RefreshCw
                "edit" -> ProductIcon.Pencil
                "trash" -> ProductIcon.Trash2
                "plus" -> ProductIcon.Plus
                "search" -> ProductIcon.Search
                "database" -> ProductIcon.Database
                "terminal" -> ProductIcon.Terminal
                "settings" -> ProductIcon.Settings
                "file-code" -> ProductIcon.FileCode
                "globe" -> ProductIcon.Globe
                else -> ProductIcon.Puzzle
            }
            action(
                label = label,
                icon = icon,
                words = "${cmd.command} ${cmd.title} ${cmd.category.orEmpty()}",
                shortcut = null,
                run = run { model.dispatchExtCommand(cmd.command) },
            )
        }
        builtIn + extItems
    }
    val text = query.text.toString()
    val typed = remember(text) { PaletteQuery(text) }
    val pathMode = isPathQuery(typed.path)
    val project = model.selectedProject
    val api = model.api

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
    LaunchedEffect(Unit) { focus.requestFocus() }
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
        LaunchedEffect(window) { window?.setDimAmount(0f) }
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            )
            val card: @Composable (Modifier, Boolean) -> Unit = { modifier, showEsc ->
                Column(modifier) {
                    Row(
                        Modifier.fillMaxWidth().edge(Edge.Bottom, tokens.border.color).padding(horizontal = 12.dp, vertical = 10.dp),
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
                                    Key.Enter, Key.NumPadEnter -> enter()
                                    Key.Escape -> onDismiss()
                                    else -> return@onPreviewKeyEvent false
                                }
                                true
                            },
                            desktopStyle = ppmText(14f, lineHeight = 20f),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            onKeyboardAction = { enter() },
                            focusRequester = focus,
                        )
                        if (listing) PpmIcon(ProductIcon.Loader2, 14.dp, tokens.text3.color, spinning = true)
                        if (showEsc) Kbd("ESC", horizontal = 6)
                    }
                    if (pathMode && !listing && filesystemPaths.isEmpty() && text.length < 4) {
                        HintLine("Type a directory path to browse files (e.g. ~/Projects/)")
                    }
                    val status = index?.status ?: FileIndexStore.Status.IDLE
                    if (!pathMode && (status == FileIndexStore.Status.LOADING || status == FileIndexStore.Status.IDLE)) IndexingLine()
                    if (!pathMode && status == FileIndexStore.Status.FAILED) {
                        RetryLine { if (fileStore != null && project != null) fileStore.load(project) }
                    }
                    if (!pathMode && groups.size > 1) FilterChips(groups, counts, activeGroups, ::toggleGroup)
                    if (shown.isEmpty()) {
                        when {
                            listing -> EmptyLine("Searching...")
                            activeGroups.isNotEmpty() && filtered.isNotEmpty() -> EmptyLine("No results in selected filters")
                            text.isNotBlank() -> AskAiRow(text.trim(), ::askAi)
                            else -> EmptyLine("No results")
                        }
                    } else {
                        LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 288.dp).cssScrollbar(list), list, PaddingValues(vertical = 4.dp)) {
                            itemsIndexed(shown) { position, item -> PaletteRow(item, position == selected) { pick(item) } }
                        }
                    }
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
            if (mobile) {
                // On the bottom edge, its background under the navigation bar, and above the keyboard.
                BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
                    val shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                    card(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .widthIn(max = 448.dp)
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
                // `md:pt-[20vh]`, kept where it is when the keyboard comes up: the list gives way instead.
                BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
                    val top = maxHeight * 0.2f
                    val keyboard = WindowInsets.ime.exclude(WindowInsets.navigationBars).asPaddingValues().calculateBottomPadding()
                    val shape = RoundedCornerShape(12.dp)
                    card(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = top)
                            .widthIn(max = 448.dp)
                            .fillMaxWidth()
                            .heightIn(max = (maxHeight - top - keyboard).coerceAtLeast(120.dp))
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
 * A result (`px-3 py-2 text-sm transition-colors`): picked in the `accent` surface at 15% and the
 * text colour, else hovered in `surface-elevated`, every change faded. An action's shortcut sits
 * at the right; a file's path does, in `text-xs text-text-subtle`, at most 200 px and cut off at
 * that, not ended with an ellipsis, as a flex box cuts its text. A file the project ignores is at
 * 60% and says so to a mouse (`title="Gitignored file"`).
 */
@Composable
private fun PaletteRow(item: PaletteItem, selected: Boolean, onPick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val picked = colorsFade(selected).toDouble()
    val lit = colorsFade(hovered && !selected).toDouble()
    val color = tokens.text.mix(tokens.text2, picked).color
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (item.ignored) Modifier.alpha(0.6f).webTitle("Gitignored file") else Modifier)
            .background(tokens.panel2.opacity(0.15).mix(tokens.panel2.opacity(lit), picked).color)
            .hoverable(hover)
            .ppmClickable(pressedColor = tokens.panel2.color, onClick = onPick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (item.group) {
            PaletteGroup.FILE -> FileIcon(item.label)
            else -> PpmIcon(item.icon ?: item.group.icon, 16.dp, color)
        }
        BasicText(
            item.label,
            Modifier.weight(1f),
            style = ppmText(14f, lineHeight = 20f).copy(color = color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        item.hint?.let {
            BasicText(
                it,
                Modifier.widthIn(max = 200.dp),
                style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
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
 * The filter chips (`CommandPaletteFilterChips`): one switch per kind of result that exists, each
 * with how many of the list's results are of it, at half strength when none. `rounded-full border
 * px-2.5 py-1 text-xs font-medium`: on in the accent at 15% with the accent's border, off as a
 * panel with the subtle text, `hover:bg-surface-elevated` faded in. The row scrolls sideways.
 */
@Composable
private fun FilterChips(groups: List<PaletteGroup>, counts: Map<PaletteGroup, Int>, active: Set<PaletteGroup>, onToggle: (PaletteGroup) -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .edge(Edge.Bottom, tokens.border.opacity(0.5).color)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
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
