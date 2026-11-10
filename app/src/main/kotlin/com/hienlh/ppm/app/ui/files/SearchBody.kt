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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
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
    val results = if (current) state.results else emptyList()
    val rows = remember(results, state.collapsed) { searchBodyRows(results, state.collapsed) }
    Column(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        if (locked || event.type != KeyEventType.KeyDown || !event.isAltPressed) false else when (event.key) {
            Key.C -> { controller.setCaseSensitive(!state.options.caseSensitive); true }
            Key.W -> { controller.setWholeWord(!state.options.wholeWord); true }
            Key.R -> { controller.setRegex(!state.options.regex); true }
            else -> false
        }
    }) {
        Column(Modifier.fillMaxWidth().cssBorder(colors.border.color, sides = CssSides.Bottom).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchOptionButton(LucideIcon.CaseSensitive, "Match Case (Alt+C)", !locked, state.options.caseSensitive) { controller.setCaseSensitive(!state.options.caseSensitive) }
                SearchOptionButton(LucideIcon.WholeWord, "Match Whole Word (Alt+W)", !locked, state.options.wholeWord) { controller.setWholeWord(!state.options.wholeWord) }
                SearchOptionButton(LucideIcon.Regex, "Use Regular Expression (Alt+R)", !locked, state.options.regex) { controller.setRegex(!state.options.regex) }
                if (current && state.regexError != null) BasicText("Invalid regex", Modifier.padding(start = 4.dp), style = ppmText(10f, lineHeight = 14f).copy(color = colors.error.color))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SearchInput(replacement, "Replace…", locked) }
                ReplaceAllButton(replacing = state.replacing, enabled = !locked && current && state.canReplace) {
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
            val summaryColor = if (!state.replacing && !(current && state.loading) && report != null && report.error == null && !report.cancelled) colors.success.color else colors.text3.color
            BasicText(summary, Modifier.heightIn(min = 12.dp), style = ppmText(10f, lineHeight = 12f).copy(color = summaryColor))
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
private fun ReplaceAllButton(replacing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalPpmTokens.current
    val shape = RoundedCornerShape(4.dp)
    val hover = rememberHover(enabled = enabled)
    Box(
        Modifier
            .size(24.dp)
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
            ),
        contentAlignment = Alignment.Center,
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
            PpmIcon(LucideIcon.ReplaceAll, 14.dp, colors.text.mix(colors.text3, hover.lit.toDouble()).color)
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
