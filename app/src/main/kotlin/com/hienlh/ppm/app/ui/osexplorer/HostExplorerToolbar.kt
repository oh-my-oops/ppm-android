package com.hienlh.ppm.app.ui.osexplorer

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ui.DropdownBelowEnd
import com.hienlh.ppm.app.ui.MenuItem
import com.hienlh.ppm.app.ui.MenuSeparator
import com.hienlh.ppm.app.ui.PpmTextField
import com.hienlh.ppm.design.*
import com.hienlh.ppm.state.osexplorer.*

@Composable
internal fun HostExplorerButton(icon: ProductIcon, label: String, enabled: Boolean = true, active: Boolean = false,
                                modifier: Modifier = Modifier, spinning: Boolean = false, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    Box(modifier.size(if (mobile) 44.dp else 28.dp).alpha(if (enabled) 1f else .3f)
        .clip(RoundedCornerShape(4.dp)).background(if (active) tokens.accent.opacity(.12).color else androidx.compose.ui.graphics.Color.Transparent)
        .semantics { contentDescription = label }.ppmClickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center) {
        PpmIcon(icon, if (mobile) 20.dp else 16.dp, (if (active) tokens.accent else tokens.text2).color, spinning = spinning)
    }
}

/**
 * The window's toolbar (`ExplorerToolbar`): history and Refresh (which turns while the folder loads), the path, the filter,
 * the three views, Sort, Upload and the hidden-files switch, in that order, 4 dp apart and wrapping as its `flex-wrap`
 * does, with a line under it. Escape empties the filter, and puts the folder's own path back in the path field as it
 * closes. What the folder's own menu holds is not here: the empty space of the list opens it, as on the web.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HostExplorerToolbar(session: ExplorerSession, state: ExplorerSessionState, preferences: ExplorerViewPreferences,
                                 canUpload: Boolean, onUpload: (folder: Boolean) -> Unit) {
    val tokens = LocalPpmTokens.current
    var editing by remember { mutableStateOf(false) }
    val path = rememberTextFieldState(state.path)
    val filter = rememberTextFieldState(state.filter)
    val focus = remember { FocusRequester() }
    LaunchedEffect(state.path) { path.setTextAndPlaceCursorAtEnd(state.path) }
    LaunchedEffect(state.filter) { if (filter.text.toString() != state.filter) filter.setTextAndPlaceCursorAtEnd(state.filter) }
    LaunchedEffect(filter) { snapshotFlow { filter.text.toString() }.collect(session::setFilter) }
    LaunchedEffect(editing) { if (editing) focus.requestFocus() }
    fun go() { session.go(path.text.toString().trim()); editing = false }
    FlowRow(Modifier.fillMaxWidth().background(tokens.panel2.color).cssBorder(tokens.border.color, sides = CssSides.Bottom)
        .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            HostExplorerButton(ProductIcon.ArrowLeft, "Back", state.canBack, onClick = session::back)
            HostExplorerButton(ProductIcon.ArrowRight, "Forward", state.canForward, onClick = session::forward)
            HostExplorerButton(ProductIcon.ArrowUp, "Up one level", state.directory?.parent != null, onClick = session::up)
            HostExplorerButton(ProductIcon.RefreshCw, "Refresh", spinning = state.loading, onClick = session::refresh)
        }
        Row(Modifier.widthIn(min = 128.dp, max = 350.dp).height(30.dp).cssBorder(tokens.border.color, RoundedCornerShape(4.dp))
            .background(tokens.panel.color).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (editing) PpmTextField(path, "Path", Modifier.weight(1f).onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { path.setTextAndPlaceCursorAtEnd(state.path); editing = false; true } else false
            }, desktopStyle = ppmText(12f, kind = FontKind.MONO), focusRequester = focus,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), onKeyboardAction = { go() })
            else Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                val crumbs = state.directory?.breadcrumbs.orEmpty()
                if (crumbs.isEmpty()) BasicText(state.path, style = ppmText(12f).copy(color = tokens.text2.color), maxLines = 1)
                crumbs.forEachIndexed { index, crumb ->
                    if (index > 0) PpmIcon(ProductIcon.ChevronRight, 12.dp, tokens.text3.color)
                    BasicText(crumb.name, Modifier.ppmClickable { session.go(crumb.path) }.padding(4.dp),
                        style = ppmText(12f).copy(color = tokens.text2.color), maxLines = 1)
                }
            }
            HostExplorerButton(ProductIcon.PencilLine, if (editing) "Show breadcrumb" else "Edit path") { editing = !editing }
        }
        Row(Modifier.height(30.dp).cssBorder(tokens.border.color, RoundedCornerShape(4.dp)).background(tokens.panel.color)
            .padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            PpmIcon(ProductIcon.Search, 14.dp, tokens.text3.color)
            PpmTextField(filter, "Filter", Modifier.width(96.dp).padding(start = 4.dp).onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { session.setFilter(""); true } else false
            }, desktopStyle = ppmText(12f))
            if (state.filter.isNotEmpty()) HostExplorerButton(ProductIcon.X, "Clear filter") { session.setFilter("") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((mode, icon) in listOf(ExplorerView.LIST to ProductIcon.List, ExplorerView.ICONS to ProductIcon.LayoutGrid,
                ExplorerView.COLUMNS to ProductIcon.Columns3)) {
                HostExplorerButton(icon, hostViewLabel(mode), active = preferences.view == mode) {
                    session.preferences.update(preferences.copy(view = mode))
                }
            }
            HostSortMenu(preferences) { key, descending -> session.preferences.update(preferences.copy(sort = key, descending = descending)) }
            HostUploadMenu(canUpload, onUpload)
            HostExplorerButton(if (preferences.showHidden) ProductIcon.Eye else ProductIcon.EyeOff,
                if (preferences.showHidden) "Hide hidden files" else "Show hidden files", active = preferences.showHidden) {
                session.preferences.update(preferences.copy(showHidden = !preferences.showHidden))
            }
        }
    }
}

/** `ExplorerSortMenu`: a button of arrows opening a dropdown of the key to sort by and the order, under it and level with its right edge. */
@Composable
private fun HostSortMenu(preferences: ExplorerViewPreferences, onSort: (ExplorerSort, Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        HostExplorerButton(ProductIcon.ArrowUpDown, "Sort") { open = true }
        if (open) DropdownBelowEnd(onDismiss = { open = false }) {
            HostSortMenuRows(preferences.sort, preferences.descending) { key, descending ->
                open = false
                onSort(key, descending)
            }
        }
    }
}

/**
 * The Sort dropdown's rows: "Sort by" over a radio for each key, a line, then "Order" over one for each direction. A radio
 * hands on its pick beside the other group's choice in force, and the dropdown closes on it (a Radix radio item selects and closes).
 */
@Composable
internal fun ColumnScope.HostSortMenuRows(sort: ExplorerSort, descending: Boolean, onPick: (ExplorerSort, Boolean) -> Unit) {
    HostMenuLabel("Sort by")
    for (key in ExplorerSort.entries) MenuRadio(key.label, key == sort) { onPick(key, descending) }
    MenuSeparator()
    HostMenuLabel("Order")
    for (order in listOf(false, true)) MenuRadio(hostOrderLabel(order), order == descending) { onPick(sort, order) }
}

/**
 * The Upload button, which holds a dropdown of files and folder: the web's plain button where a browser cannot pick a
 * folder, and this one always can. Dead, as its rows would be, until the upload queue exists ([enabled]).
 */
@Composable
private fun HostUploadMenu(enabled: Boolean, onUpload: (folder: Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        HostExplorerButton(ProductIcon.Upload, "Upload", enabled) { open = true }
        if (open) DropdownBelowEnd(onDismiss = { open = false }) {
            HostUploadMenuRows { folder ->
                open = false
                onUpload(folder)
            }
        }
    }
}

/** The Upload dropdown's two rows; each says whether it is the folder. */
@Composable
internal fun ColumnScope.HostUploadMenuRows(onPick: (folder: Boolean) -> Unit) {
    MenuItem("Upload Files…", onClick = { onPick(false) })
    MenuItem("Upload Folder…", onClick = { onPick(true) })
}
