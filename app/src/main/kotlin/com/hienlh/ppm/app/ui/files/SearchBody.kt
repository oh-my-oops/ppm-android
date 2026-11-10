package com.hienlh.ppm.app.ui.files

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.liftsAsTap
import com.hienlh.ppm.app.ui.PpmTextField
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FileIcon
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.LucideIcon
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.RegexHighlight
import com.hienlh.ppm.design.SyntaxHighlighter
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssBorderPx
import com.hienlh.ppm.design.cssScrollbar
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
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
    SearchBody(
        controller = controller,
        project = project,
        javascript = javascript,
        onOpenFile = { file, line ->
            if (project != null && model.selectedProject == project) {
                model.openFileTab(project, file, line = line)
                onNavigate()
            }
        },
        onToast = { message ->
            model.toast(ErrorToastMessage(message, kind = ErrorToastMessage.Kind.INFO))
        },
    )
}

@Composable
internal fun SearchBody(
    controller: ProjectFileSearch,
    project: String?,
    javascript: SearchJavascript? = null,
    onOpenFile: (file: String, line: Long) -> Unit = { _, _ -> },
    onToast: (String) -> Unit = {},
) {
    val state by controller.state.collectAsState()
    val query = remember(controller) { TextFieldState(state.options.query) }
    val replacement = remember(controller) { TextFieldState(state.replacement) }
    val include = remember(controller) { TextFieldState(state.options.include) }
    LaunchedEffect(controller, query) { snapshotFlow { query.text.toString() }.collect(controller::setQuery) }
    LaunchedEffect(controller, replacement) { snapshotFlow { replacement.text.toString() }.collect(controller::setReplacement) }
    LaunchedEffect(controller, include) { snapshotFlow { include.text.toString() }.collect(controller::setInclude) }
    // TextFieldState/project can change before its effect runs: even that frame must hide old hits.
    val current = state.project == project && state.options.query == query.text.toString() && state.options.include == include.text.toString()
    val locked = state.replacing || project == null
    val context = LocalContext.current
    val softwareKeyboard = LocalSoftwareKeyboardController.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val focus = remember { FocusRequester() }
    var quietFocus by remember(project) { mutableStateOf(true) }
    DisposableEffect(context, quietFocus) {
        val window = (context as? Activity)?.window
        if (window != null) {
            val adjust = window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
            val stateFlag = if (quietFocus) {
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            } else {
                WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
            }
            window.setSoftInputMode(adjust or stateFlag)
        }
        onDispose {
            val w = (context as? Activity)?.window
            if (w != null) {
                val adjust = w.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
                w.setSoftInputMode(adjust or WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED)
            }
        }
    }
    LaunchedEffect(project, windowFocused) {
        if (project != null && windowFocused && quietFocus) {
            focus.requestFocus()
            softwareKeyboard?.hide()
        }
    }
    val colors = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val list = rememberLazyListState()
    var showWritten by remember { mutableStateOf(false) }
    var showReplace by remember { mutableStateOf(true) }
    var pendingConfirmation by remember(
        project,
        state.options,
        state.results,
        state.total,
        state.canReplace,
        state.replacing,
    ) { mutableStateOf<ReplaceAllConfirmation?>(null) }
    val activeConfirmation = pendingConfirmation?.takeIf { !locked && current && state.canReplace }
    val results = if (current) state.results else emptyList()
    val rows = remember(results, state.collapsed) { searchBodyRows(results, state.collapsed) }

    fun toggleWholeWord() {
        val next = !state.options.wholeWord
        val hadRegex = state.options.regex
        controller.setWholeWord(next)
        if (next && hadRegex) onToast("Regular expression disabled (incompatible with Match Whole Word)")
    }

    fun toggleRegex() {
        val next = !state.options.regex
        val hadWord = state.options.wholeWord
        controller.setRegex(next)
        if (next && hadWord) onToast("Match Whole Word disabled (incompatible with Regular Expression)")
    }

    fun requestReplaceAllConfirmation() {
        controller.setProject(project)
        controller.setQuery(query.text.toString())
        controller.setInclude(include.text.toString())
        controller.setReplacement(replacement.text.toString())
        val live = controller.state.value
        if (live.canReplace) {
            pendingConfirmation = replaceAllConfirmation(live.results, live.total)
        }
    }

    Column(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        if (locked || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when {
            event.isCtrlPressed && event.isShiftPressed && event.key == Key.H -> {
                showReplace = !showReplace
                true
            }
            event.isCtrlPressed && event.isAltPressed && (event.key == Key.Enter || event.key == Key.NumPadEnter) -> {
                if (current && state.canReplace) {
                    showReplace = true
                    requestReplaceAllConfirmation()
                    true
                } else {
                    false
                }
            }
            event.isAltPressed -> when (event.key) {
                Key.C -> { controller.setCaseSensitive(!state.options.caseSensitive); true }
                Key.W -> { toggleWholeWord(); true }
                Key.R -> { toggleRegex(); true }
                else -> false
            }
            else -> false
        }
    }) {
        Column(Modifier.fillMaxWidth().cssBorder(colors.border.color, sides = CssSides.Bottom).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .semantics { contentDescription = "Toggle Replace" }
                        .ppmClickable(
                            enabled = !locked,
                            pressedColor = colors.panel2.color,
                            hoverColor = colors.panel2.color,
                            onClickLabel = "Toggle Replace",
                        ) { showReplace = !showReplace },
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(if (showReplace) ProductIcon.ChevronDown else ProductIcon.ChevronRight, 12.dp, colors.text3.color)
                }
                Box(Modifier.weight(1f)) {
                    SearchInput(
                        value = query,
                        placeholder = if (project == null) "Select a project first" else "Search files…",
                        locked = locked,
                        leadingIcon = ProductIcon.Search,
                        errorBorder = current && state.regexError != null,
                        focus = focus,
                        quietFocus = quietFocus,
                        onQuietFocusCleared = { quietFocus = false },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchOptionButton(LucideIcon.CaseSensitive, "Match Case (Alt+C)", !locked, state.options.caseSensitive) { controller.setCaseSensitive(!state.options.caseSensitive) }
                SearchOptionButton(LucideIcon.WholeWord, "Match Whole Word (Alt+W)", !locked, state.options.wholeWord) { toggleWholeWord() }
                SearchOptionButton(LucideIcon.Regex, "Use Regular Expression (Alt+R)", !locked, state.options.regex) { toggleRegex() }
                if (current && state.regexError != null) BasicText("Invalid regex", Modifier.padding(start = 4.dp), style = ppmText(10f, lineHeight = 14f).copy(color = colors.error.color))
            }
            if (showReplace) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SearchInput(replacement, "Replace…", locked) }
                    ReplaceAllButton(
                        replacing = state.replacing,
                        enabled = !locked && current && state.canReplace,
                        showLabel = activeConfirmation == null,
                    ) {
                        requestReplaceAllConfirmation()
                    }
                }
                if (activeConfirmation != null) {
                    ReplaceAllConfirmCard(
                        confirmation = activeConfirmation,
                        onCancel = { pendingConfirmation = null },
                        onConfirm = {
                            pendingConfirmation = null
                            controller.replaceAll()
                        },
                    )
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
            val summaryColor = if (!state.replacing && !(current && state.loading) && report != null && report.error == null && !report.cancelled) colors.success.color else colors.text3.color
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(summary, Modifier.weight(1f).heightIn(min = 12.dp), style = ppmText(10f, lineHeight = 12f).copy(color = summaryColor))
                if (current && results.isNotEmpty()) {
                    val allCollapsed = results.all { it.file in state.collapsed }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        SearchButton(
                            label = if (allCollapsed) "Expand all" else "Collapse all",
                            description = if (allCollapsed) "Expand all" else "Collapse all",
                            enabled = !locked,
                        ) {
                            for (result in results) {
                                val isCollapsed = result.file in controller.state.value.collapsed
                                if (allCollapsed && isCollapsed) controller.toggleCollapse(result.file)
                                else if (!allCollapsed && !isCollapsed) controller.toggleCollapse(result.file)
                            }
                        }
                        SearchButton(
                            label = "Clear",
                            description = "Clear search results",
                            enabled = !locked,
                        ) {
                            pendingConfirmation = null
                            query.setTextAndPlaceCursorAtEnd("")
                            controller.setQuery("")
                        }
                    }
                }
            }
            if (current && state.error != null) {
                BasicText("Search failed${state.errorStatus?.let { " ($it)" }.orEmpty()}: ${state.error}",
                    style = ppmText(10f, lineHeight = 14f).copy(color = colors.error.color))
                SearchButton("Retry search", "Retry search", !locked) { controller.retry() }
            }
            if (report?.error != null) BasicText("${report.failedPath}: ${report.error}", style = ppmText(12f, lineHeight = 16f).copy(color = colors.error.color))
            if (!report?.paths.isNullOrEmpty()) SearchButton("Written files (${report.paths.size})", "Show written files") { showWritten = !showWritten }
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
                        Modifier.fillMaxWidth().heightIn(min = if (mobile) 32.dp else 24.dp)
                            .ppmClickable(pressedColor = colors.panel.opacity(0.5).color, hoverColor = colors.panel.opacity(0.5).color,
                                onClickLabel = "Toggle ${row.result.file}") { controller.toggleCollapse(row.result.file) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PpmIcon(if (row.result.file in state.collapsed) ProductIcon.ChevronRight else ProductIcon.ChevronDown, 12.dp, colors.text3.color)
                        FileIcon(row.result.file.substringAfterLast('/'), size = 14.dp)
                        BasicText(row.result.file.substringAfterLast('/'), style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = colors.text.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val directory = row.result.file.substringBeforeLast('/', "")
                        Box(Modifier.weight(1f).padding(start = 4.dp)) {
                            if (directory.isNotEmpty()) BasicText(directory, style = ppmText(10f, lineHeight = 14f).copy(color = colors.text3.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box(Modifier.background(colors.panel.color, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp)) {
                            BasicText("${row.result.matches.size}", style = ppmText(10f, lineHeight = 14f).copy(color = colors.text3.color))
                        }
                    }
                    is SearchBodyRow.Match -> Row(
                        Modifier.fillMaxWidth().heightIn(min = if (mobile) 32.dp else 20.dp)
                            .ppmClickable(pressedColor = colors.accent.opacity(0.1).color, hoverColor = colors.accent.opacity(0.1).color,
                                onClickLabel = "Open ${row.file} at line ${row.match.lineNum}") {
                                // A project switch invalidates clicks too, including the frame before SideEffect.
                                val live = controller.state.value
                                if (project != null && live.project == project && live.options == state.options &&
                                    live.options.query == query.text.toString() && live.options.include == include.text.toString() && live.searched && !live.loading && !live.pending) {
                                    onOpenFile(row.file, row.match.lineNum.toLong())
                                }
                            }.padding(start = 28.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top,
                    ) {
                        BasicText("${row.match.lineNum}", Modifier.width(28.dp), style = ppmText(10f, lineHeight = 16f).copy(color = colors.text3.color, textAlign = TextAlign.End))
                        SearchMatchText(searchDisplayText(row.match.content), state.options, state.replacement, javascript, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
internal fun ReplaceAllConfirmCard(
    confirmation: ReplaceAllConfirmation,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("replace-all-confirm-card")
            .clip(shape)
            .background(colors.panel2.color)
            .cssBorder(colors.warning.mix(colors.border, 0.45).color, shape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            PpmIcon(ProductIcon.AlertTriangle, 14.dp, colors.warning.color, Modifier.padding(top = 1.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BasicText(
                    confirmation.title,
                    Modifier.testTag("replace-all-confirm-title"),
                    style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = colors.text.color),
                )
                BasicText(
                    confirmation.detail,
                    Modifier.testTag("replace-all-confirm-detail"),
                    style = ppmText(11f, lineHeight = 15f).copy(color = colors.text2.color),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .heightIn(min = 30.dp)
                    .testTag("replace-all-cancel")
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.panel.color)
                    .cssBorder(colors.border.color, RoundedCornerShape(6.dp))
                    .semantics { contentDescription = "Cancel Replace All" }
                    .ppmClickable(
                        pressedColor = colors.panel2.color,
                        hoverColor = colors.panel2.color,
                        onClickLabel = "Cancel",
                        onClick = onCancel,
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText("Cancel", style = ppmText(11f, FontWeight.Medium, lineHeight = 15f).copy(color = colors.text.color))
            }
            Box(
                Modifier
                    .heightIn(min = 30.dp)
                    .testTag("replace-all-confirm")
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.accent.color)
                    .semantics { contentDescription = "Confirm Replace All" }
                    .ppmClickable(
                        pressedColor = colors.accent.opacity(0.85).color,
                        hoverColor = colors.accent.opacity(0.9).color,
                        onClickLabel = "Confirm Replace All",
                        onClick = onConfirm,
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText("Replace All", style = ppmText(11f, FontWeight.SemiBold, lineHeight = 15f).copy(color = Color.White))
            }
        }
    }
}

@Composable
private fun SearchMatchText(
    text: String,
    options: FileSearchOptions,
    replacement: String,
    javascript: SearchJavascript?,
    modifier: Modifier,
) {
    val colors = LocalPpmTokens.current
    val ranges = remember(text, options, javascript) { mutableStateOf(emptyList<RegexHighlight>()) }
    LaunchedEffect(ranges) {
        if (javascript == null) return@LaunchedEffect
        try { ranges.value = javascript.highlights(text, options) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* A failed highlighter leaves the server's result readable. */ }
    }
    val shown = remember(text, ranges.value, replacement, colors.warning, colors.error, colors.success) {
        searchReplacementPreviewText(
            text = text,
            highlights = ranges.value,
            replacement = replacement,
            matchHighlight = colors.warning.opacity(0.4).color,
            deleteColor = colors.error.color,
            deleteBackground = colors.error.opacity(0.18).color,
            insertColor = colors.success.color,
            insertBackground = colors.success.opacity(0.18).color,
        )
    }
    BasicText(shown, modifier, style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = colors.text2.color),
        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun SearchInput(
    value: TextFieldState,
    placeholder: String,
    locked: Boolean,
    leadingIcon: ProductIcon? = null,
    errorBorder: Boolean = false,
    focus: FocusRequester? = null,
    quietFocus: Boolean = false,
    onQuietFocusCleared: () -> Unit = {},
) {
    val colors = LocalPpmTokens.current
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(4.dp)
    val ringColor = colors.accent.opacity(0.5).color
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (locked) 0.5f else 1f)
            .drawBehind {
                if (!focused) return@drawBehind
                val band = cssBorderPx(1.dp).toFloat()
                val r = 4.dp.toPx()
                val outer = Path().apply {
                    addRoundRect(RoundRect(Rect(Offset(-band, -band), Offset(size.width + band, size.height + band)), CornerRadius(r + band)))
                }
                val inner = Path().apply {
                    addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(r)))
                }
                drawPath(Path().apply { op(outer, inner, PathOperation.Difference) }, ringColor)
            }
            .clip(shape)
            .background(colors.border.color)
            .cssBorder((if (errorBorder) colors.error else colors.border).color, shape)
            .pointerInput(focus != null) {
                if (focus == null) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    onQuietFocusCleared()
                    if (!locked && liftsAsTap(down, PointerEventPass.Final)) {
                        keyboard?.show()
                    }
                }
            }
            .padding(start = 8.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) PpmIcon(leadingIcon, 14.dp, colors.text3.color)
        PpmTextField(
            value,
            placeholder,
            Modifier.weight(1f).semantics { contentDescription = placeholder },
            desktopStyle = ppmText(12f, lineHeight = 16f),
            keyboardOptions = KeyboardOptions(showKeyboardOnFocus = if (focus != null) !quietFocus else true),
            focusRequester = focus,
            onFocusChanged = {
                focused = it
                if (!it && focus != null) onQuietFocusCleared()
            },
            inputTransformation = if (locked) InputTransformation { revertAllChanges() } else null,
            cssStart = if (leadingIcon != null) 28.dp else 8.dp,
        )
        if (value.text.isNotEmpty()) {
            val clearHover = rememberHover(enabled = !locked)
            Box(
                Modifier
                    .size(16.dp)
                    .semantics { contentDescription = "Clear $placeholder" }
                    .ppmClickable(
                        enabled = !locked,
                        pressedColor = Color.Transparent,
                        hoverColor = Color.Transparent,
                        source = clearHover.source,
                        onClickLabel = "Clear $placeholder",
                    ) { value.setTextAndPlaceCursorAtEnd("") },
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.X, 12.dp, colors.text.mix(colors.text3, clearHover.lit.toDouble()).color)
            }
        }
    }
}

@Composable
private fun SearchOptionButton(
    icon: LucideIcon,
    description: String,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LocalPpmTokens.current
    val shape = RoundedCornerShape(4.dp)
    val hover = rememberHover(enabled = enabled && !active)
    Box(
        Modifier
            .size(24.dp)
            .clip(shape)
            .background(if (active) colors.accent.opacity(0.1).color else Color.Transparent)
            .cssBorder((if (active) colors.accent else colors.border.opacity(1.0 - 0.2 * hover.lit)).color, shape)
            .semantics { contentDescription = description }
            .ppmClickable(
                enabled = enabled,
                pressedColor = colors.panel2.color,
                hoverColor = Color.Transparent,
                shape = shape,
                source = hover.source,
                onClickLabel = description,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(icon, 14.dp, (if (active) colors.accent else colors.text.mix(colors.text3, hover.lit.toDouble())).color)
    }
}

@Composable
private fun ReplaceAllButton(
    replacing: Boolean,
    enabled: Boolean,
    showLabel: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = LocalPpmTokens.current
    val shape = RoundedCornerShape(4.dp)
    val hover = rememberHover(enabled = enabled)
    val tint = colors.text.mix(colors.text3, hover.lit.toDouble()).color
    Row(
        Modifier
            .sizeIn(minWidth = 24.dp, minHeight = 24.dp)
            .testTag("replace-all-trigger")
            .alpha(if (enabled || replacing) 1f else 0.4f)
            .clip(shape)
            .cssBorder(colors.border.opacity(1.0 - 0.2 * hover.lit).color, shape)
            .semantics { contentDescription = "Replace All" }
            .ppmClickable(
                enabled = enabled,
                pressedColor = colors.panel2.color,
                hoverColor = Color.Transparent,
                shape = shape,
                source = hover.source,
                onClickLabel = "Replace All",
                onClick = onClick,
            )
            .padding(horizontal = if (showLabel) 8.dp else 5.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (replacing) {
            val spin by rememberInfiniteTransition(label = "replaceAllSpin").animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart),
                label = "replaceAllRotation",
            )
            PpmIcon(ProductIcon.Loader2, 14.dp, colors.text3.color, Modifier.graphicsLayer { rotationZ = spin })
        } else {
            PpmIcon(LucideIcon.ReplaceAll, 14.dp, tint)
        }
        if (showLabel) {
            BasicText("Replace All", style = ppmText(11f, FontWeight.Medium, lineHeight = 14f).copy(color = tint))
        }
    }
}

@Composable
private fun SearchButton(label: String, description: String, enabled: Boolean = true, active: Boolean = false, onClick: () -> Unit) {
    val colors = LocalPpmTokens.current
    val shape = RoundedCornerShape(4.dp)
    Box(Modifier.sizeIn(minWidth = 24.dp, minHeight = if (LocalPpmMobile.current) 32.dp else 24.dp)
        .clip(shape)
        .background(if (active) colors.accent.opacity(0.1).color else Color.Transparent)
        .cssBorder(if (active) colors.accent.color else colors.border.color, shape)
        .semantics { contentDescription = description }
        .ppmClickable(enabled = enabled, pressedColor = colors.panel.color, hoverColor = colors.panel.color, shape = shape, onClickLabel = description, onClick = onClick)
        .padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
        BasicText(label, style = ppmText(12f, lineHeight = 16f).copy(color = if (active) colors.accent.color else if (enabled) colors.text2.color else colors.text3.color))
    }
}
