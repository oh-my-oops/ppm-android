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
import com.hienlh.ppm.app.ui.PpmTextField
import com.hienlh.ppm.design.*
import com.hienlh.ppm.state.osexplorer.*

@Composable
internal fun HostExplorerButton(icon: ProductIcon, label: String, enabled: Boolean = true, active: Boolean = false,
                                modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    Box(modifier.size(if (mobile) 44.dp else 28.dp).alpha(if (enabled) 1f else .3f)
        .clip(RoundedCornerShape(4.dp)).background(if (active) tokens.accent.opacity(.12).color else androidx.compose.ui.graphics.Color.Transparent)
        .semantics { contentDescription = label }.ppmClickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center) {
        PpmIcon(icon, if (mobile) 20.dp else 16.dp, (if (active) tokens.accent else tokens.text2).color)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HostExplorerToolbar(session: ExplorerSession, state: ExplorerSessionState, preferences: ExplorerViewPreferences,
                                 onMore: () -> Unit) {
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
    FlowRow(Modifier.fillMaxWidth().background(tokens.panel2.color).padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HostExplorerButton(ProductIcon.ArrowLeft, "Back", state.canBack, onClick = session::back)
            HostExplorerButton(ProductIcon.ArrowRight, "Forward", state.canForward, onClick = session::forward)
            HostExplorerButton(ProductIcon.ArrowUp, "Up one level", state.directory?.parent != null, onClick = session::up)
            HostExplorerButton(ProductIcon.RefreshCw, "Refresh", onClick = session::refresh)
        }
        Row(Modifier.widthIn(min = 128.dp, max = 350.dp).height(30.dp).cssBorder(tokens.border.color, RoundedCornerShape(4.dp))
            .background(tokens.panel.color).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (editing) PpmTextField(path, "Path", Modifier.weight(1f).onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { editing = false; true } else false
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
            PpmTextField(filter, "Filter", Modifier.width(96.dp).padding(start = 4.dp), desktopStyle = ppmText(12f))
            if (state.filter.isNotEmpty()) HostExplorerButton(ProductIcon.X, "Clear filter") { session.setFilter("") }
        }
        Row {
            for ((mode, icon) in listOf(ExplorerView.LIST to ProductIcon.List, ExplorerView.ICONS to ProductIcon.LayoutGrid,
                ExplorerView.COLUMNS to ProductIcon.Columns3)) {
                HostExplorerButton(icon, "${mode.name.lowercase().replaceFirstChar(Char::uppercaseChar)} view", active = preferences.view == mode) {
                    session.preferences.update(preferences.copy(view = mode))
                }
            }
            HostExplorerButton(if (preferences.showHidden) ProductIcon.Eye else ProductIcon.EyeOff,
                if (preferences.showHidden) "Hide hidden files" else "Show hidden files", active = preferences.showHidden) {
                session.preferences.update(preferences.copy(showHidden = !preferences.showHidden))
            }
            HostExplorerButton(ProductIcon.MoreHorizontal, "File actions", onClick = onMore)
        }
    }
}
