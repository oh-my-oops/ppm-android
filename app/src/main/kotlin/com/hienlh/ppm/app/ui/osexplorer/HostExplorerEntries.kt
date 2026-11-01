package com.hienlh.ppm.app.ui.osexplorer

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.*
import com.hienlh.ppm.protocol.HostFileEntry
import com.hienlh.ppm.state.files.FileDocumentClassifier
import com.hienlh.ppm.state.osexplorer.*
import java.time.Instant
import java.util.Locale

@Composable
internal fun HostExplorerEntries(api: com.hienlh.ppm.client.ApiClient?, session: ExplorerSession, entries: List<HostFileEntry>, state: ExplorerSessionState,
                                 preferences: ExplorerViewPreferences, mobile: Boolean, selectMode: Boolean, windows: Boolean, commands: HostExplorerCommands?,
                                 cut: Set<String>, onFocus: () -> Unit, onOpen: (HostFileEntry) -> Unit,
                                 onMenu: (List<HostFileEntry>, Offset) -> Unit,
                                 dropModifier: @Composable (String) -> Modifier = { Modifier }) {
    val tokens = LocalPpmTokens.current
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
        LazyVerticalGrid(GridCells.Adaptive(if (mobile) 100.dp else 112.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
            items(entries, key = { it.path }) { entry ->
                val edit = commands?.edit
                if (edit?.entry?.path == entry.path) { HostInlineName(commands, edit); return@items }
                val selected = entry.path in state.selection
                Column(Modifier.padding(4.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (selected) tokens.accent.opacity(.12).color else Color.Transparent)
                    .alpha(if (entry.path in cut) .4f else 1f)
                    .then(if (entry.isDirectory) dropModifier(entry.path) else Modifier)
                    .hostEntryInput(entry, selected, { toggle, range -> tap(entry, toggle, range) }, { onOpen(entry) }, { menu(entry, it) })
                    .padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (api != null) HostFileThumbnail(api, entry, 48.dp) else HostEntryIcon(entry, Modifier.size(48.dp))
                    BasicText(entry.name, Modifier.padding(top = 6.dp).fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color, textAlign = TextAlign.Center))
                }
            }
        }
    } else {
        val scroll = rememberLazyListState()
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
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = scroll) {
                itemsIndexed(entries, key = { _, entry -> entry.path }) { index, entry ->
                    val edit = commands?.edit
                    if (edit?.entry?.path == entry.path) { HostInlineName(commands, edit); return@itemsIndexed }
                    val selected = entry.path in state.selection
                    Row(Modifier.fillMaxWidth().height(if (mobile) 48.dp else 32.dp)
                        .background(when { selected -> tokens.accent.opacity(.12).color
                            !windows && index % 2 == 1 -> tokens.panel2.opacity(.4).color; else -> Color.Transparent })
                        .alpha(if (entry.path in cut) .4f else 1f)
                    .then(if (entry.isDirectory) dropModifier(entry.path) else Modifier)
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
                if (entries.isEmpty() && !state.loading) item {
                    BasicText(if (state.filter.isNotEmpty()) "No entries match the filter." else "This folder is empty.", Modifier.padding(12.dp),
                        style = ppmText(12f).copy(color = tokens.text3.color))
                }
            }
        }
    }
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
