package com.hienlh.ppm.app.ui.osexplorer

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.hienlh.ppm.app.TextInputFocus
import com.hienlh.ppm.app.ui.LocalWindowContentVisible
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.fsBrowse
import com.hienlh.ppm.design.*
import com.hienlh.ppm.protocol.HostFileEntry
import com.hienlh.ppm.state.files.FileDocumentClassifier
import com.hienlh.ppm.state.osexplorer.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Miller columns share navigation/history with List and Icons. Parent owns file actions and selection. */
@Composable
internal fun HostExplorerColumns(
    api: ApiClient, session: ExplorerSession, entries: List<HostFileEntry>, state: ExplorerSessionState,
    preferences: ExplorerViewPreferences, mobile: Boolean,
    onSelect: (HostFileEntry) -> Unit, onOpen: (HostFileEntry) -> Unit,
    onMenu: (List<HostFileEntry>, Offset, String) -> Unit,
    dropModifier: @Composable (String) -> Modifier = { Modifier },
    modifier: Modifier = Modifier, cut: Set<String> = emptySet(), commands: HostExplorerCommands? = null,
) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    val controller = remember(api, session) { HostColumnState(HostDirectoryReader { path, hidden -> api.fsBrowse(path, hidden) }, scope) }
    val cached by controller.state.collectAsState()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val visible = LocalWindowContentVisible.current && lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val paths = state.directory?.breadcrumbs?.map { it.path }?.takeIf { it.isNotEmpty() } ?: hostAncestorPaths(state.path)
    LaunchedEffect(controller, paths, entries, state.loading, preferences.showHidden, visible) {
        controller.sync(paths, entries, state.loading, preferences.showHidden, visible)
    }
    DisposableEffect(controller) { onDispose { controller.close() } }
    // Never draw stale sibling listings in the frame before the sync effect runs.
    val columns = paths.mapIndexed { index, path ->
        if (index == paths.lastIndex) HostColumn(path, entries, state.loading, state.error?.message)
        else cached.getOrNull(index)?.takeIf { it.path == path } ?: HostColumn(path)
    }
    var focused by remember(session) { mutableIntStateOf(paths.lastIndex) }
    var localAnchor by remember(session) { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val horizontal = rememberLazyListState()
    LaunchedEffect(paths.size) { focused = paths.lastIndex }
    LaunchedEffect(state.path) { localAnchor = null }
    val activeIndex = focused.coerceIn(0, paths.lastIndex.coerceAtLeast(0))
    val anchor = state.anchor ?: localAnchor
    fun selectedPath(index: Int) = if (index == paths.lastIndex) anchor else paths.getOrNull(index + 1)
    fun select(index: Int, entry: HostFileEntry) {
        focused = index
        focus.requestFocus()
        if (entry.isDirectory) {
            val existing = paths.indexOf(entry.path)
            if (entry.path == state.currentDirectory && existing >= 0) focused = existing
            else session.go(entry.path)
        } else { localAnchor = entry.path; onSelect(entry) }
    }
    fun menu(index: Int, entry: HostFileEntry?, at: Offset) {
        if (entry != null && !entry.isDirectory) { localAnchor = entry.path; onSelect(entry) }
        focused = index
        onMenu(listOfNotNull(entry), at, paths[index])
    }
    BoxWithConstraints(modifier.fillMaxSize().focusRequester(focus).onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || event.isAltPressed || event.isCtrlPressed || event.isMetaPressed || TextInputFocus.focused) return@onKeyEvent false
        val column = columns.getOrNull(activeIndex) ?: return@onKeyEvent false
        val current = column.entries.indexOfFirst { it.path == selectedPath(activeIndex) }
        when (event.key) {
            Key.DirectionLeft -> if (activeIndex > 0) focused = activeIndex - 1 else return@onKeyEvent false
            Key.DirectionRight -> if (activeIndex < paths.lastIndex) focused = activeIndex + 1 else return@onKeyEvent false
            Key.DirectionUp, Key.DirectionDown -> {
                val next = if (current < 0) 0 else current + if (event.key == Key.DirectionUp) -1 else 1
                column.entries.getOrNull(next.coerceIn(0, column.entries.lastIndex.coerceAtLeast(0)))?.let { select(activeIndex, it) }
            }
            Key.Enter, Key.NumPadEnter -> column.entries.getOrNull(current)?.let(onOpen) ?: return@onKeyEvent false
            else -> return@onKeyEvent false
        }
        true
    }.focusable()) {
        val preview = !mobile && maxWidth >= 720.dp
        val previewWidth = maxOf(240.dp, maxWidth - 220.dp * paths.size)
        LaunchedEffect(paths.size, preview, mobile) {
            if (!mobile && paths.isNotEmpty()) horizontal.animateScrollToItem(if (preview) paths.size else paths.lastIndex)
        }
        @Composable fun column(index: Int, fullWidth: Boolean) {
            val value = columns[index]
            HostColumnView(value, selectedPath(index), index == activeIndex, mobile, fullWidth, cut, commands, dropModifier,
                onSelect = { entry ->
                    select(index, entry)
                    if (mobile && !entry.isDirectory) {
                        if (FileDocumentClassifier.canOpenInPpm(entry.name)) onOpen(entry) else menu(index, entry, Offset.Zero)
                    }
                }, onOpen = onOpen, onMenu = { entry, at -> menu(index, entry, at) },
                onRetry = { if (index == paths.lastIndex) session.refresh() else controller.retry(value.path) })
        }
        if (mobile) Column(Modifier.fillMaxSize()) {
            if (activeIndex > 0) Row(Modifier.fillMaxWidth().height(44.dp).cssBorder(tokens.border.color, sides = CssSides.Bottom)
                .ppmClickable(onClickLabel = "Back", onClick = { focused = activeIndex - 1; focus.requestFocus() }).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PpmIcon(ProductIcon.ArrowLeft, 16.dp, tokens.text2.color)
                BasicText("Back", style = ppmText(14f).copy(color = tokens.text2.color))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { if (columns.isNotEmpty()) key(columns[activeIndex].path) { column(activeIndex, true) } }
        } else LazyRow(Modifier.fillMaxSize().cssScrollbar(horizontal), state = horizontal) {
            itemsIndexed(paths, key = { _, path -> path }) { index, _ -> column(index, false) }
            if (preview) item(key = "preview") {
                val selected = columns.asSequence().flatMap { it.entries.asSequence() }.firstOrNull { it.path == anchor }
                HostColumnPreview(api, selected, Modifier.width(previewWidth).fillMaxHeight(), onOpen)
            }
        }
    }
}

@Composable
private fun HostColumnView(column: HostColumn, selected: String?, focused: Boolean, mobile: Boolean, fullWidth: Boolean,
                           cut: Set<String>, commands: HostExplorerCommands?, dropModifier: @Composable (String) -> Modifier, onSelect: (HostFileEntry) -> Unit, onOpen: (HostFileEntry) -> Unit,
                           onMenu: (HostFileEntry?, Offset) -> Unit, onRetry: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val scroll = rememberLazyListState()
    LaunchedEffect(selected, column.entries) {
        val index = column.entries.indexOfFirst { it.path == selected }
        if (index >= 0 && scroll.layoutInfo.visibleItemsInfo.none { it.index == index }) scroll.animateScrollToItem(index)
    }
    LazyColumn((if (fullWidth) Modifier.fillMaxWidth() else Modifier.width(220.dp).cssBorder(tokens.border.color, sides = CssSides.End))
        .fillMaxHeight().background(if (focused) tokens.panel2.opacity(.4).color else Color.Transparent)
        .then(dropModifier(column.path)).semantics { contentDescription = column.path }.cssScrollbar(scroll)
        .hostColumnInput({}, {}, { onMenu(null, it) }), state = scroll) {
        if (column.loading && column.entries.isEmpty()) item { HostColumnMessage("Loading…") }
        column.error?.let { error -> item {
            BasicText(error, Modifier.padding(8.dp), style = ppmText(12f).copy(color = tokens.error.color))
            BasicText("Retry", Modifier.ppmClickable(onClick = onRetry).padding(8.dp), style = ppmText(12f).copy(color = tokens.accent.color))
        } }
        if (!column.loading && column.error == null && column.entries.isEmpty()) item { HostColumnMessage("Empty") }
        items(column.entries, key = { it.path }) { entry ->
            val edit = commands?.edit
            if (edit?.entry?.path == entry.path) HostInlineName(commands, edit)
            else Row(Modifier.fillMaxWidth().height(if (mobile) 48.dp else 32.dp)
                .background(if (entry.path == selected) tokens.accentWash.color else Color.Transparent)
                .alpha(if (entry.path in cut) .4f else 1f)
                .then(if (entry.isDirectory) dropModifier(entry.path) else Modifier)
                .semantics { this.selected = entry.path == selected; contentDescription = entry.name }
                .hostColumnInput({ onSelect(entry) }, { onOpen(entry) }, { onMenu(entry, it) })
                .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HostEntryIcon(entry, Modifier.size(16.dp))
                BasicText(entry.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = ppmText(13f).copy(color = (if (entry.path == selected) tokens.text else tokens.text2).color))
                if (entry.isDirectory) PpmIcon(ProductIcon.ChevronRight, 12.dp, tokens.text3.color)
            }
        }
    }
}

@Composable
private fun HostColumnMessage(message: String) = BasicText(message, Modifier.padding(8.dp), style = ppmText(12f).copy(color = LocalPpmTokens.current.text3.color))

@Composable
private fun HostColumnPreview(api: ApiClient, entry: HostFileEntry?, modifier: Modifier, onOpen: (HostFileEntry) -> Unit) {
    val tokens = LocalPpmTokens.current
    Box(modifier.background(tokens.panel2.opacity(.3).color), contentAlignment = Alignment.Center) {
        if (entry == null) HostColumnMessage("Select a file to preview")
        else Column(Modifier.fillMaxSize().cssScrollY(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HostFileThumbnail(api, entry, 96.dp)
            BasicText(entry.name, Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = ppmText(14f, FontWeight.Medium).copy(color = tokens.text.color))
            val modified = runCatching { DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(entry.modified)) }.getOrDefault(entry.modified)
            val metadata = listOf("Kind" to if (entry.isDirectory) "Folder" else FileDocumentClassifier.extensionOf(entry.name).ifEmpty { "File" }) +
                (if (entry.isDirectory) emptyList() else listOf("Size" to hostDisplaySize(entry.size))) + ("Modified" to modified)
            metadata.forEach { (name, value) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicText(name, style = ppmText(12f).copy(color = tokens.text3.color))
                    Spacer(Modifier.weight(1f))
                    BasicText(value, maxLines = 1, overflow = TextOverflow.Ellipsis, style = ppmText(12f).copy(color = tokens.text2.color))
                }
            }
            if (FileDocumentClassifier.canOpenInPpm(entry.name)) BasicText("Open", Modifier.fillMaxWidth().heightIn(min = 44.dp)
                .background(tokens.panel2.color).cssBorder(tokens.border.color).ppmClickable { onOpen(entry) }.padding(12.dp),
                style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text.color))
        }
    }
}

private fun Modifier.hostColumnInput(onSelect: () -> Unit, onOpen: () -> Unit, onMenu: (Offset) -> Unit): Modifier = composed {
    var origin by remember { mutableStateOf(Offset.Zero) }
    var position by remember { mutableStateOf(Offset.Zero) }
    val menu by rememberUpdatedState(onMenu)
    onGloballyPositioned { origin = it.positionInWindow() }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press) {
                        position = origin + (event.changes.firstOrNull()?.position ?: Offset.Zero)
                        if (event.buttons.isSecondaryPressed && event.changes.none { it.isConsumed }) {
                            event.changes.forEach { it.consume() }; menu(position)
                        }
                    }
                }
            }
        }.combinedClickable(onClick = onSelect, onDoubleClick = onOpen, onLongClick = { menu(position) })
}
