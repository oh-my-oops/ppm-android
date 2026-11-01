package com.hienlh.ppm.app.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.PpmTextField
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FileIcon
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.RegexHighlight
import com.hienlh.ppm.design.SyntaxHighlighter
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssScrollbar
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.FileSearchOptions
import com.hienlh.ppm.state.files.ProjectFileSearch
import kotlinx.coroutines.CancellationException

/** Mounted only while the Search surface is visible; main owns drawer/sidebar navigation. */
@Composable
fun SearchBody(model: AppModel, onNavigate: () -> Unit = {}) {
    val api = model.api
    if (api == null) { FileMessage("Connect to a server to search files."); return }
    val engine = SyntaxHighlighter.get(LocalContext.current)
    val javascript = remember(engine) { SearchJavascript(engine) }
    val controller = remember(model, api, javascript) {
        ProjectFileSearch(api, javascript, model.scope) { report ->
            if (model.api === api && report.paths.isNotEmpty()) {
                model.projects.firstOrNull { it.name == report.project }?.let {
                    model.filesMutated(searchMutationReport(report, it.path))
                }
            }
            val detail = buildString {
                append(report.project).append(" (").append(api.baseUrl.host).append(")")
                report.failedPath?.let { append(" · ").append(it) }
                report.error?.let { append("\n").append(it) }
                if (report.paths.isNotEmpty()) append("\nWritten: ").append(report.paths.joinToString(", "))
            }
            model.toast(ErrorToastMessage(searchReplaceSummary(report), detail,
                kind = if (report.error != null || report.cancelled) ErrorToastMessage.Kind.WARNING else ErrorToastMessage.Kind.SUCCESS))
        }
    }
    DisposableEffect(controller) { onDispose { controller.close() } }
    val project = model.selectedProject
    SideEffect { controller.setProject(project) }
    val state by controller.state.collectAsState()
    val query = remember(controller) { TextFieldState() }
    val replacement = remember(controller) { TextFieldState() }
    val include = remember(controller) { TextFieldState() }
    LaunchedEffect(controller, query) { snapshotFlow { query.text.toString() }.collect(controller::setQuery) }
    LaunchedEffect(controller, replacement) { snapshotFlow { replacement.text.toString() }.collect(controller::setReplacement) }
    LaunchedEffect(controller, include) { snapshotFlow { include.text.toString() }.collect(controller::setInclude) }
    // TextFieldState/project can change before its effect runs: even that frame must hide old hits.
    val current = state.project == project && state.options.query == query.text.toString() && state.options.include == include.text.toString()
    val locked = state.replacing || project == null
    val focus = remember { FocusRequester() }
    LaunchedEffect(project) { if (project != null) focus.requestFocus() }
    val colors = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val list = rememberLazyListState()
    var showWritten by remember { mutableStateOf(false) }
    val results = if (current) state.results else emptyList()
    val rows = remember(results, state.collapsed) { searchBodyRows(results, state.collapsed) }
    Column(Modifier.fillMaxSize().background(colors.bgSolid.color).onPreviewKeyEvent { event ->
        if (locked || event.type != KeyEventType.KeyDown || !event.isAltPressed) false else when (event.key) {
            Key.C -> { controller.setCaseSensitive(!state.options.caseSensitive); true }
            Key.W -> { controller.setWholeWord(!state.options.wholeWord); true }
            Key.R -> { controller.setRegex(!state.options.regex); true }
            else -> false
        }
    }) {
        Column(Modifier.fillMaxWidth().cssBorder(colors.border.color, sides = CssSides.Bottom).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SearchInput(query, if (project == null) "Select a project first" else "Search files…", locked, focus)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchButton("Aa", "Match Case (Alt+C)", !locked, state.options.caseSensitive) { controller.setCaseSensitive(!state.options.caseSensitive) }
                SearchButton("Ab", "Match Whole Word (Alt+W)", !locked, state.options.wholeWord) { controller.setWholeWord(!state.options.wholeWord) }
                SearchButton(".*", "Use Regular Expression (Alt+R)", !locked, state.options.regex) { controller.setRegex(!state.options.regex) }
                if (current && state.regexError != null) BasicText("Invalid regex", style = ppmText(10f, lineHeight = 14f).copy(color = colors.error.color))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SearchInput(replacement, "Replace…", locked) }
                SearchButton("Replace All", "Replace All", !locked && current && state.canReplace) {
                    // Flush TextFieldState synchronously: a tap can precede its snapshot collector.
                    // A changed input clears results and makes replaceAll refuse stale candidates.
                    controller.setProject(model.selectedProject)
                    controller.setQuery(query.text.toString())
                    controller.setInclude(include.text.toString())
                    controller.setReplacement(replacement.text.toString())
                    controller.replaceAll()
                }
            }
            SearchInput(include, "Files to include (e.g. *.ts, src/**)", locked)
            val report = state.replaceReport?.takeIf { current && it.project == project }
            val summary = when {
                state.replacing -> "Replacing…"
                current && state.loading -> "Searching…"
                report != null -> searchReplaceSummary(report)
                current && state.searched && state.results.isEmpty() -> "No results"
                current && state.total > 0 -> searchResultSummary(state.total, state.results.size)
                else -> ""
            }
            BasicText(summary, Modifier.heightIn(min = 14.dp), style = ppmText(10f, lineHeight = 14f).copy(color = colors.text3.color))
            if (current && state.error != null) {
                BasicText("Search failed${state.errorStatus?.let { " ($it)" }.orEmpty()}: ${state.error}",
                    style = ppmText(12f, lineHeight = 16f).copy(color = colors.error.color))
                SearchButton("Retry search", "Retry search", !locked) { controller.retry() }
            }
            if (report?.error != null) BasicText("${report.failedPath}: ${report.error}", style = ppmText(12f, lineHeight = 16f).copy(color = colors.error.color))
            if (!report?.paths.isNullOrEmpty()) SearchButton("Written files (${report!!.paths.size})", "Show written files") { showWritten = !showWritten }
        }
        LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().cssScrollbar(list)) {
            if (showWritten && current) items(state.replaceReport?.paths.orEmpty(), key = { "written:$it" }) { path ->
                BasicText(path, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = ppmText(12f, lineHeight = 16f).copy(color = colors.text2.color))
            }
            items(rows, key = { when (it) {
                is SearchBodyRow.File -> "file:${it.result.file}"
                is SearchBodyRow.Match -> "match:${it.file}:${it.index}"
            } }) { row ->
                when (row) {
                    is SearchBodyRow.File -> Row(
                        Modifier.fillMaxWidth().heightIn(min = if (mobile) 32.dp else 26.dp)
                            .ppmClickable(pressedColor = colors.panel.color, hoverColor = colors.panel.color,
                                onClickLabel = "Toggle ${row.result.file}") { controller.toggleCollapse(row.result.file) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PpmIcon(if (row.result.file in state.collapsed) ProductIcon.ChevronRight else ProductIcon.ChevronDown, 12.dp, colors.text3.color)
                        FileIcon(row.result.file.substringAfterLast('/'), size = 14.dp)
                        BasicText(row.result.file.substringAfterLast('/'), Modifier.weight(1f), style = ppmText(12f, lineHeight = 16f).copy(color = colors.text.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val directory = row.result.file.substringBeforeLast('/', "")
                        if (directory.isNotEmpty()) BasicText(directory, Modifier.weight(1f), style = ppmText(10f, lineHeight = 14f).copy(color = colors.text3.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        BasicText("${row.result.matches.size}", style = ppmText(10f, lineHeight = 14f).copy(color = colors.text3.color))
                    }
                    is SearchBodyRow.Match -> Row(
                        Modifier.fillMaxWidth().heightIn(min = if (mobile) 32.dp else 20.dp)
                            .ppmClickable(pressedColor = colors.accent.opacity(0.1).color, hoverColor = colors.accent.opacity(0.1).color,
                                onClickLabel = "Open ${row.file} at line ${row.match.lineNum}") {
                                // A project switch invalidates clicks too, including the frame before SideEffect.
                                val live = controller.state.value
                                if (project != null && model.selectedProject == project && live.project == project && live.options == state.options &&
                                    live.options.query == query.text.toString() && live.options.include == include.text.toString() && live.searched && !live.loading && !live.pending) {
                                    model.openFileTab(project, row.file, line = row.match.lineNum.toLong())
                                    onNavigate()
                                }
                            }.padding(start = 28.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top,
                    ) {
                        BasicText("${row.match.lineNum}", Modifier.width(28.dp), style = ppmText(10f, lineHeight = 16f).copy(color = colors.text3.color, textAlign = TextAlign.End))
                        SearchMatchText(searchDisplayText(row.match.content), state.options, javascript, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchMatchText(text: String, options: FileSearchOptions, javascript: SearchJavascript, modifier: Modifier) {
    val colors = LocalPpmTokens.current
    val ranges = remember(text, options, javascript) { mutableStateOf(emptyList<RegexHighlight>()) }
    LaunchedEffect(ranges) {
        try { ranges.value = javascript.highlights(text, options) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* A failed highlighter leaves the server's result readable. */ }
    }
    val shown = remember(text, ranges.value, colors.warning) { searchHighlightedText(text, ranges.value, colors.warning.opacity(0.4).color) }
    BasicText(shown, modifier, style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = colors.text2.color),
        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun SearchInput(value: TextFieldState, placeholder: String, locked: Boolean, focus: FocusRequester? = null) {
    val colors = LocalPpmTokens.current
    Row(Modifier.fillMaxWidth().background(colors.panel.color).cssBorder(colors.border.color).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        PpmTextField(value, placeholder, Modifier.weight(1f).semantics { contentDescription = placeholder },
            desktopStyle = ppmText(12f, lineHeight = 16f), focusRequester = focus,
            inputTransformation = if (locked) InputTransformation { revertAllChanges() } else null)
        if (value.text.isNotEmpty()) SearchButton("×", "Clear $placeholder", !locked) { value.setTextAndPlaceCursorAtEnd("") }
    }
}

@Composable
private fun SearchButton(label: String, description: String, enabled: Boolean = true, active: Boolean = false, onClick: () -> Unit) {
    val colors = LocalPpmTokens.current
    Box(Modifier.sizeIn(minWidth = 24.dp, minHeight = if (LocalPpmMobile.current) 32.dp else 24.dp)
        .background(if (active) colors.accent.opacity(0.1).color else Color.Transparent)
        .cssBorder(if (active) colors.accent.color else colors.border.color)
        .semantics { contentDescription = description }
        .ppmClickable(enabled = enabled, pressedColor = colors.panel.color, hoverColor = colors.panel.color, onClickLabel = description, onClick = onClick)
        .padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
        BasicText(label, style = ppmText(12f, lineHeight = 16f).copy(color = if (active) colors.accent.color else if (enabled) colors.text2.color else colors.text3.color))
    }
}
