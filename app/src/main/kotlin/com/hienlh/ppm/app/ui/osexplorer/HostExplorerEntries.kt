package com.hienlh.ppm.app.ui.osexplorer

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ui.files.HostEntryDrag
import com.hienlh.ppm.app.ui.files.explorerEntryDragSource
import com.hienlh.ppm.design.*
import com.hienlh.ppm.protocol.HostFileEntry
import com.hienlh.ppm.state.files.FileDocumentClassifier
import com.hienlh.ppm.state.osexplorer.*
import java.time.Instant
import java.util.Locale

/** `p-2` of the Icons view's scrolling grid (icons-view.tsx), which the drop's hit test has to count from. */
private val GRID_PADDING = 8.dp

@Composable
internal fun HostExplorerEntries(api: com.hienlh.ppm.client.ApiClient?, session: ExplorerSession, entries: List<HostFileEntry>, state: ExplorerSessionState,
                                 preferences: ExplorerViewPreferences, mobile: Boolean, selectMode: Boolean, windows: Boolean, commands: HostExplorerCommands?,
                                 cut: Set<String>, onFocus: () -> Unit, onOpen: (HostFileEntry) -> Unit,
                                 onMenu: (List<HostFileEntry>, Offset) -> Unit,
                                 dropModifier: @Composable (String) -> Modifier = { Modifier },
                                 gridColumns: HostGridColumns = HostGridColumns()) {
    val tokens = LocalPpmTokens.current
    // A name being typed for a new file or folder takes the place of the "empty" line, as in the web's views.
    val creating = commands?.edit?.let { it.entry == null } == true
    val drops = rememberHostEntryDrops(commands, mobile)
    // A row or tile drags what a menu on it would act on (`menuTargets`): the selection it is part of, else itself.
    fun dragOf(entry: HostFileEntry) = HostEntryDrag(
        if (entry.path in state.selection) entries.filter { it.path in state.selection }.map { it.path } else listOf(entry.path))
    fun menu(entry: HostFileEntry, at: Offset) {
        if (entry.path !in state.selection) session.select(entry.path)
        onMenu(if (entry.path in state.selection) entries.filter { it.path in state.selection } else listOf(entry), at)
    }
    fun tap(entry: HostFileEntry, toggle: Boolean, range: Boolean) {
        onFocus()
        when {
            mobile && selectMode -> session.select(entry.path, toggle = true)
            mobile && (entry.isDirectory || FileDocumentClassifier.canOpenInPpm(entry.name)) -> onOpen(entry)
            mobile -> menu(entry, Offset.Zero)
            else -> session.select(entry.path, toggle, range)
        }
    }
    if (preferences.view == ExplorerView.ICONS) {
        val grid = rememberLazyGridState()
        val cells = remember(mobile, gridColumns) { HostRecordedCells(GridCells.Adaptive(if (mobile) 100.dp else 112.dp), gridColumns) }
        // Keep the cursor row in view when the arrow keys moved it, as the list does.
        LaunchedEffect(state.anchor) {
            val index = entries.indexOfFirst { it.path == state.anchor }
            if (index >= 0 && grid.layoutInfo.visibleItemsInfo.none { it.index == index }) grid.animateScrollToItem(index)
        }
        val padding = with(LocalDensity.current) { GRID_PADDING.toPx() }
        val inset = with(LocalDensity.current) { 4.dp.toPx() }
        val gridDrops = rememberHostListDrops(drops, state.currentDirectory) { at -> grid.entryAt(at, padding, inset, entries) }
        LazyVerticalGrid(cells, Modifier.fillMaxSize().hostBackgroundMenu(session::clearSelection) { onMenu(emptyList(), it) }
            .then(gridDrops.target).then(gridDrops.list()),
            state = grid, contentPadding = PaddingValues(GRID_PADDING)) {
            items(entries, key = { it.path }) { entry ->
                val edit = commands?.edit
                if (edit?.entry?.path == entry.path) { HostInlineName(commands, edit); return@items }
                val selected = entry.path in state.selection
                Column(Modifier.padding(4.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (selected) tokens.accent.opacity(.12).color else Color.Transparent)
                    .alpha(if (entry.path in cut) .4f else 1f)
                    .then(gridDrops.row(entry, RoundedCornerShape(6.dp)))
                    .then(if (entry.isDirectory) dropModifier(entry.path) else Modifier)
                    .explorerEntryDragSource(drops != null) { dragOf(entry) }
                    .hostEntryInput(entry, selected, { toggle, range -> tap(entry, toggle, range) }, { onOpen(entry) }, { menu(entry, it) })
                    .padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (api != null) HostFileThumbnail(api, entry, 48.dp) else HostEntryIcon(entry, Modifier.size(48.dp))
                    BasicText(entry.name, Modifier.padding(top = 6.dp).fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color, textAlign = TextAlign.Center))
                }
            }
            if (entries.isEmpty() && !state.loading && !creating) item(span = { GridItemSpan(maxLineSpan) }) { HostEmptyMessage(state.filter) }
        }
    } else {
        val scroll = rememberLazyListState()
        val listDrops = rememberHostListDrops(drops, state.currentDirectory) { at -> scroll.entryAt(at.y, entries) }
        LaunchedEffect(state.anchor) {
            val index = entries.indexOfFirst { it.path == state.anchor }
            if (index >= 0 && scroll.layoutInfo.visibleItemsInfo.none { it.index == index }) scroll.animateScrollToItem(index)
        }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().background(tokens.panel2.color).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (sort in ExplorerSort.entries) {
                    if (mobile && sort in listOf(ExplorerSort.MODIFIED, ExplorerSort.KIND)) continue
                    val modifier = if (sort == ExplorerSort.NAME) Modifier.weight(1f) else Modifier.width(when (sort) {
                        ExplorerSort.SIZE -> 80.dp; ExplorerSort.MODIFIED -> 96.dp; else -> 64.dp
                    })
                    BasicText(sort.name.lowercase().replaceFirstChar(Char::uppercaseChar) +
                        if (preferences.sort == sort) (if (preferences.descending) " ↓" else " ↑") else "",
                        modifier.ppmClickable { session.preferences.update(preferences.copy(sort = sort,
                            descending = if (preferences.sort == sort) !preferences.descending else false)) },
                        style = ppmText(11f, FontWeight.Medium).copy(color = tokens.text3.color,
                            textAlign = if (sort == ExplorerSort.NAME) TextAlign.Start else TextAlign.End))
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().hostBackgroundMenu(session::clearSelection) { onMenu(emptyList(), it) }
                .then(listDrops.target).then(listDrops.list()), state = scroll) {
                itemsIndexed(entries, key = { _, entry -> entry.path }) { index, entry ->
                    val edit = commands?.edit
                    if (edit?.entry?.path == entry.path) { HostInlineName(commands, edit); return@itemsIndexed }
                    val selected = entry.path in state.selection
                    Row(Modifier.fillMaxWidth().height(if (mobile) 48.dp else 32.dp)
                        .background(when { selected -> tokens.accent.opacity(.12).color
                            !windows && index % 2 == 1 -> tokens.panel2.opacity(.4).color; else -> Color.Transparent })
                        .alpha(if (entry.path in cut) .4f else 1f)
                        .then(listDrops.row(entry))
                    .then(if (entry.isDirectory) dropModifier(entry.path) else Modifier)
                        .explorerEntryDragSource(drops != null) { dragOf(entry) }
                        .hostEntryInput(entry, selected, { toggle, range -> tap(entry, toggle, range) }, { onOpen(entry) }, { menu(entry, it) })
                        .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (mobile && selectMode) PpmIcon(if (selected) ProductIcon.Check else ProductIcon.Square, 20.dp, tokens.accent.color)
                        HostEntryIcon(entry, Modifier.size(16.dp))
                        BasicText(entry.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = ppmText(13f).copy(color = (if (selected) tokens.text else tokens.text2).color))
                        BasicText(if (entry.isDirectory) "" else hostDisplaySize(entry.size), Modifier.width(80.dp),
                            style = ppmText(13f).copy(color = tokens.text3.color, textAlign = TextAlign.End))
                        if (!mobile) {
                            BasicText(hostModified(entry.modified), Modifier.width(96.dp), style = ppmText(13f).copy(color = tokens.text3.color, textAlign = TextAlign.End))
                            BasicText(if (entry.isDirectory) "Folder" else entry.name.substringAfterLast('.', "File"), Modifier.width(64.dp),
                                maxLines = 1, overflow = TextOverflow.Ellipsis, style = ppmText(13f).copy(color = tokens.text3.color, textAlign = TextAlign.End))
                        }
                    }
                }
                if (entries.isEmpty() && !state.loading && !creating) item { HostEmptyMessage(state.filter) }
            }
        }
    }
}

/** The line a folder with nothing to show gets (`p.p-3.text-xs.text-text-subtle` in list-view.tsx and icons-view.tsx). */
@Composable
private fun HostEmptyMessage(filter: String) {
    BasicText(if (filter.isNotEmpty()) "No entries match the filter." else "This folder is empty.", Modifier.padding(12.dp),
        style = ppmText(12f).copy(color = LocalPpmTokens.current.text3.color))
}

@Composable
internal fun HostEntryIcon(entry: HostFileEntry, modifier: Modifier) {
    if (entry.isDirectory) Box(modifier, contentAlignment = Alignment.Center) { PpmIcon(ProductIcon.Folder, 16.dp, LocalPpmTokens.current.accent.color) }
    else FileIcon(entry.name, modifier)
}

/** Preserve Ctrl/Shift and the actual pointer location while letting lazy lists arbitrate scroll. */
private fun Modifier.hostEntryInput(entry: HostFileEntry, selected: Boolean, onTap: (Boolean, Boolean) -> Unit,
                                    onOpen: () -> Unit, onMenu: (Offset) -> Unit): Modifier = composed {
    var origin by remember { mutableStateOf(Offset.Zero) }
    var at by remember { mutableStateOf(Offset.Zero) }
    var toggle by remember { mutableStateOf(false) }
    var range by remember { mutableStateOf(false) }
    val menu by rememberUpdatedState(onMenu)
    onGloballyPositioned { origin = it.positionInWindow() }
        .semantics { this.selected = selected; contentDescription = entry.name }
        .pointerInput(entry.path) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press) {
                        at = origin + (event.changes.firstOrNull()?.position ?: Offset.Zero)
                        toggle = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
                        range = event.keyboardModifiers.isShiftPressed
                        if (event.buttons.isSecondaryPressed) { event.changes.forEach { it.consume() }; menu(at) }
                    }
                }
            }
        }
        .combinedClickable(onClick = { onTap(toggle, range) }, onDoubleClick = onOpen, onLongClick = { menu(at) })
}

/**
 * What the space the rows leave does, in the list and in the grid (`onMouseDown` and `backgroundLongPress` round the web's
 * views, and the `ContextMenuTrigger`): a press on it empties the selection ([onClear]), a right click or a press held
 * on a finger opens the folder's own menu ([onMenu]), and a right click does both, as it does there. A row claims its own
 * right click before this looks (at the initial pass, where this waits for the main one), and its touch never reaches
 * the press here, so a row's menu and the folder's do not both open and a row's tap does not clear the selection it set.
 */
internal fun Modifier.hostBackgroundMenu(onClear: () -> Unit, onMenu: (Offset) -> Unit): Modifier = composed {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val menu by rememberUpdatedState(onMenu)
    val clear by rememberUpdatedState(onClear)
    onGloballyPositioned { origin = it.positionInWindow() }
        .pointerInput(Unit) { detectTapGestures(onLongPress = { menu(origin + it) }, onTap = { clear() }) }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed && event.changes.none { it.isConsumed }) {
                        event.changes.forEach { it.consume() }
                        clear()
                        menu(origin + event.changes.first().position)
                    }
                }
            }
        }
}

internal fun hostDisplaySize(bytes: Long?): String = when {
    bytes == null -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
}

private fun hostModified(iso: String): String {
    val age = runCatching { (System.currentTimeMillis() - Instant.parse(iso).toEpochMilli()) / 60_000 }.getOrNull() ?: return ""
    return when { age < 1 -> "now"; age < 60 -> "${age}m ago"; age < 1440 -> "${age / 60}h ago"; else -> "${age / 1440}d ago" }
}
