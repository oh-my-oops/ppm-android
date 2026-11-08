package com.hienlh.ppm.app.ui.osexplorer

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.PpmTextField
import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.client.fsMkdir
import com.hienlh.ppm.client.fsRemoveDirectory
import com.hienlh.ppm.design.*
import com.hienlh.ppm.state.osexplorer.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Server paths, distinct from Android SAF. Shared by project forms, Save As and DB forms. */
@Composable
fun HostPathPicker(model: AppModel, mode: HostPickerMode, root: String = "~", accept: List<String> = emptyList(),
                   title: String? = null, onSelect: (String) -> Unit, onCancel: () -> Unit) {
    val api = model.api ?: return
    val scope = rememberCoroutineScope()
    val session = remember(api, root) {
        val transient = object : KeyValueStore {
            override fun get(key: String): String? = null
            override fun put(key: String, value: String?) = Unit
        }
        ExplorerSession(api, ExplorerPreferences(transient, ""), scope, root)
    }
    DisposableEffect(session) { session.show(); onDispose { session.close() } }
    val state by session.state.collectAsState()
    val prefs by session.preferences.state.collectAsState()
    val recent = model.hostPickerPreferences?.recent?.collectAsState()?.value.orEmpty()
    val path = rememberTextFieldState(root)
    val filter = rememberTextFieldState()
    var selected by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    val newName = rememberTextFieldState()
    val newFocus = remember { FocusRequester() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var deletePath by remember { mutableStateOf<String?>(null) }
    val mobile = LocalPpmMobile.current
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(12.dp)
    val selectedEntry = state.directory?.entries?.firstOrNull { it.path == selected }
    LaunchedEffect(state.path) { selected = null; filter.setTextAndPlaceCursorAtEnd(""); error = null }
    LaunchedEffect(state.directory?.current) { state.directory?.current?.let { path.setTextAndPlaceCursorAtEnd(it) } }
    LaunchedEffect(creating) { if (creating) newFocus.requestFocus() }
    fun create() {
        val name = newName.text.toString().trim()
        if (busy || name.isEmpty()) return
        validateHostEntryName(name, model.hostPlatform)?.let { error = it; return }
        val dir = state.currentDirectory
        val sep = state.directory?.separator ?: "/"
        val destination = dir + if (dir.endsWith(sep)) name else sep + name
        busy = true
        scope.launch {
            try {
                // Match web `file-browser-picker.tsx:179`: send `{path}` only so the server runs its default `git init`.
                api.fsMkdir(destination, gitInit = null); creating = false; error = null; session.refresh(); selected = destination
                if (model.api === api) model.filesMutated(com.hienlh.ppm.state.files.FileMutationReport(affectedDirectories = setOf(dir)))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = (failure as? PpmError.Server)?.hint ?: failure.message }
            finally { busy = false }
        }
    }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
            val height = maxHeight * if (mobile) .85f else .7f
            Column(Modifier.widthIn(max = 672.dp).fillMaxWidth(if (mobile) .95f else .9f).height(height).clip(shape)
                .background(tokens.panel2.over(tokens.bgSolid).color).cssBorder(tokens.border.color, shape)) {
                BasicText(title ?: when (mode) { HostPickerMode.FILE -> "Select File"; HostPickerMode.FOLDER -> "Select Folder"; else -> "Select File or Folder" },
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = ppmText(14f, FontWeight.SemiBold).copy(color = tokens.text.color))
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (state.directory?.parent != null) HostExplorerButton(ProductIcon.ArrowLeft, "Parent folder", onClick = session::up)
                    PpmTextField(path, "Type path and press Enter", Modifier.weight(1f), desktopStyle = ppmText(12f, kind = FontKind.MONO),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), onKeyboardAction = { session.go(path.text.toString().trim()) })
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    state.directory?.breadcrumbs?.forEachIndexed { index, crumb ->
                        if (index > 0) PpmIcon(ProductIcon.ChevronRight, 12.dp, tokens.text3.color)
                        BasicText(crumb.name, Modifier.ppmClickable { session.go(crumb.path) }.padding(horizontal = 4.dp),
                            style = ppmText(12f).copy(color = tokens.text2.color))
                    }
                }
                Row(Modifier.weight(1f)) {
                    if (!mobile) Column(Modifier.width(144.dp).fillMaxHeight().cssScrollY().padding(8.dp)) {
                        for ((name, target, icon) in listOf(
                            Triple("Home", "~", ProductIcon.Home),
                            Triple("Desktop", "~/Desktop", ProductIcon.Monitor),
                            Triple("Documents", "~/Documents", ProductIcon.FileText),
                            Triple("Downloads", "~/Downloads", ProductIcon.Download),
                        )) {
                            val active = state.currentDirectory.endsWith(name)
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                .background(if (active) tokens.accent.opacity(.1).color else androidx.compose.ui.graphics.Color.Transparent)
                                .ppmClickable { session.go(target) }.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PpmIcon(icon, 14.dp, (if (active) tokens.accent else tokens.text2).color)
                                BasicText(name, style = ppmText(12f).copy(color = (if (active) tokens.accent else tokens.text2).color))
                            }
                        }
                        if (recent.isNotEmpty()) BasicText("Recent", Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
                            style = ppmText(10f, FontWeight.Medium).copy(color = tokens.text3.color))
                        for (target in recent) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                            .ppmClickable { session.go(target) }.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PpmIcon(ProductIcon.Clock, 12.dp, tokens.text2.color)
                            BasicText(target.substringAfterLast('/').substringAfterLast('\\'), Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis, style = ppmText(12f).copy(color = tokens.text2.color))
                        }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        if (creating) Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            PpmTextField(newName, "Folder name", Modifier.weight(1f).onPreviewKeyEvent {
                                if (it.type != KeyEventType.KeyDown || newName.composition != null) false
                                else when (it.key) {
                                    Key.Enter, Key.NumPadEnter -> { create(); true }
                                    Key.Escape -> { creating = false; true }
                                    else -> false
                                }
                            }, focusRequester = newFocus,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), onKeyboardAction = { create() })
                            HostExplorerButton(ProductIcon.Check, "Create folder", !busy) { create() }
                            HostExplorerButton(ProductIcon.X, "Cancel create", !busy) { creating = false }
                        }
                        val failure = error ?: state.error?.hint ?: state.error?.message
                        if (failure != null) BasicText(failure, Modifier.padding(12.dp), style = ppmText(12f).copy(color = tokens.error.color))
                        if (state.loading) BasicText("Loading…", Modifier.padding(12.dp), style = ppmText(12f).copy(color = tokens.text3.color))
                        val entries = state.directory?.entries.orEmpty().filter {
                            it.name.contains(filter.text.toString(), ignoreCase = true) && (it.isDirectory || hostPickerAccepts(it.name, accept))
                        }
                        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                            items(entries, key = { it.path }) { entry ->
                                val selectable = if (entry.isDirectory) mode != HostPickerMode.FILE else mode != HostPickerMode.FOLDER
                                val isSelected = entry.path == selected
                                Row(Modifier.fillMaxWidth().heightIn(min = if (mobile) 44.dp else 30.dp)
                                    .background(if (isSelected) tokens.accent.opacity(.1).color else androidx.compose.ui.graphics.Color.Transparent)
                                    .alpha(if (selectable) 1f else .4f).combinedClickable(enabled = selectable || entry.isDirectory,
                                        onClick = { if (entry.isDirectory && mode == HostPickerMode.FILE) session.go(entry.path) else selected = entry.path },
                                        onDoubleClick = { if (entry.isDirectory) session.go(entry.path) }).padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    HostEntryIcon(entry, Modifier.size(16.dp))
                                    BasicText(entry.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = ppmText(12f, if (entry.isDirectory) FontWeight.Medium else FontWeight.Normal)
                                            .copy(color = (if (isSelected) tokens.accent else tokens.text).color))
                                    BasicText(hostDisplaySize(entry.size), Modifier.width(56.dp),
                                        style = ppmText(10f).copy(color = tokens.text3.color, textAlign = TextAlign.End))
                                    BasicText(hostModified(entry.modified), Modifier.width(56.dp),
                                        style = ppmText(10f).copy(color = tokens.text3.color, textAlign = TextAlign.End))
                                }
                            }
                            if (entries.isEmpty() && !state.loading) item {
                                BasicText(if (filter.text.isEmpty()) "Empty directory" else "No matching entries", Modifier.padding(24.dp), style = ppmText(12f).copy(color = tokens.text3.color))
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HostExplorerButton(ProductIcon.FolderPlus, "New Folder", !busy) { newName.setTextAndPlaceCursorAtEnd(""); creating = true; error = null }
                    HostExplorerButton(ProductIcon.Trash2, "Delete selected folder", !busy && selectedEntry?.isDirectory == true) { deletePath = selected }
                    HostExplorerButton(if (prefs.showHidden) ProductIcon.Eye else ProductIcon.EyeOff, "Show hidden files", active = prefs.showHidden) {
                        session.preferences.update(prefs.copy(showHidden = !prefs.showHidden))
                    }
                    if (accept.isNotEmpty()) BasicText(accept.joinToString(", "),
                        Modifier.background(tokens.surfaceHover.color, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                        style = ppmText(10f).copy(color = tokens.text3.color))
                    PpmTextField(filter, "Filter…", Modifier.weight(1f), desktopStyle = ppmText(11f))
                    HostDialogButton("Cancel", onClick = onCancel)
                    HostDialogButton("Select", selected != null && !busy) {
                        selected?.let { model.hostPickerPreferences?.rememberDirectory(state.currentDirectory); onSelect(it) }
                    }
                }
            }
        }
    }
    deletePath?.let { target -> HostExplorerDialog("Delete folder?", "Move “${target.substringAfterLast('/').substringAfterLast('\\')}” to Trash?", { deletePath = null }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HostDialogButton("Cancel", !busy) { deletePath = null }
            HostDialogButton("Delete", !busy, danger = true) {
                busy = true
                scope.launch {
                    try { api.fsRemoveDirectory(target); selected = null; deletePath = null; session.refresh() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = (failure as? PpmError.Server)?.hint ?: failure.message; deletePath = null }
                    finally { busy = false }
                }
            }
        }
    } }
}
