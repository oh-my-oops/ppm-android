package com.hienlh.ppm.app.ui.phone.files

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.FileIcon
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.LucideIcon
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ThemeTokens
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.kit.AppBar
import com.hienlh.ppm.design.kit.AppBarAction
import com.hienlh.ppm.design.kit.Chip
import com.hienlh.ppm.design.kit.KitSpec
import com.hienlh.ppm.design.kit.Sheet
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.FileSearchMatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import com.hienlh.ppm.app.tracksTextFocus

/**
 * Search in the phone's Files area: the web's Search panel in its phone layout (`search-panel.tsx`
 * with `mobile`): the field, Match Case / Whole Word / Regex and Replace as chips under it, ··· for
 * files to include / exclude and the ignore files, a status line with Replace All, the banners, and
 * the matches grouped by file. Tapping a match opens the file at its line.
 */
@Composable
internal fun PhoneSearchScreen(
    controller: PhoneSearchController,
    project: String?,
    onBack: () -> Unit,
    onOpenMatch: (file: String, line: Int) -> Unit,
    onCopyPath: (file: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    LaunchedEffect(controller, project) { controller.setProject(project) }
    val st by controller.state.collectAsState()
    val fields = st.fields
    var replaceAllOpen by remember { mutableStateOf(false) }
    var fileSheet by remember { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxSize().testTag("phone-search-screen")) {
        AppBar(
            title = "Search",
            subline = project,
            onBack = onBack,
            actions = listOf(
                AppBarAction(
                    icon = ProductIcon.MoreHorizontal,
                    label = "Toggle Search Details",
                    badgeDotColor = if (fields.filtersActive) tokens.accent.color else null,
                    enabled = project != null,
                    onClick = controller::toggleDetails,
                ),
                AppBarAction(ProductIcon.XCircle, "Clear Search Results", enabled = project != null, onClick = controller::clear),
            ),
        )

        Column(
            Modifier.fillMaxWidth().cssBorder(tokens.border.color, sides = com.hienlh.ppm.design.CssSides.Bottom)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SearchField(
                key = project,
                value = fields.query,
                placeholder = if (project != null) "Search" else "Select a project first",
                label = "Search",
                enabled = project != null,
                error = st.fieldError != null,
                leading = ProductIcon.Search,
                testTag = "phone-search-query",
                onSearch = controller::searchNow,
                onChange = { q -> controller.change { it.copy(query = q) } },
            )
            st.fieldError?.let {
                BasicText(it, Modifier.testTag("phone-search-field-error"), style = ppmText(12f, lineHeight = 16f).copy(color = tokens.error.color))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Match Case", LucideIcon.CaseSensitive, controller::toggleCaseSensitive, Modifier.testTag("phone-search-case"), selected = fields.caseSensitive, enabled = project != null)
                Chip("Whole Word", LucideIcon.WholeWord, controller::toggleWholeWord, Modifier.testTag("phone-search-word"), selected = fields.wholeWord, enabled = project != null)
                Chip("Regex", LucideIcon.Regex, controller::toggleRegex, Modifier.testTag("phone-search-regex"), selected = fields.regex, enabled = project != null)
                Chip("Replace", LucideIcon.ReplaceAll, controller::toggleReplace, Modifier.testTag("phone-search-replace-toggle"), selected = fields.showReplace, enabled = project != null)
            }
            if (fields.showReplace) {
                SearchField(
                    key = project,
                    value = fields.replace,
                    placeholder = "Replace",
                    label = "Replace",
                    enabled = project != null,
                    testTag = "phone-search-replace",
                    onChange = { r -> controller.change { it.copy(replace = r) } },
                    trailing = {
                        Chip("AB", controller::togglePreserveCase, Modifier.testTag("phone-search-preserve-case").semantics { contentDescription = "Preserve Case" }, selected = fields.preserveCase)
                    },
                )
            }
            if (fields.showDetails) {
                SearchField(
                    key = project, value = fields.include, placeholder = "e.g. *.ts, src/**/include", label = "Files to include",
                    enabled = project != null, testTag = "phone-search-include",
                    onChange = { v -> controller.change { it.copy(include = v) } },
                )
                SearchField(
                    key = project, value = fields.exclude, placeholder = "e.g. *.test.ts, **/fixtures/**", label = "Files to exclude",
                    enabled = project != null, testTag = "phone-search-exclude",
                    onChange = { v -> controller.change { it.copy(exclude = v) } },
                )
                Chip(
                    "Use Exclude Settings and Ignore Files", ProductIcon.EyeOff, controller::toggleUseIgnore,
                    Modifier.testTag("phone-search-use-ignore"), selected = fields.useIgnore,
                )
            }
        }

        StatusLine(st, project, onReplaceAll = { if (st.canReplaceAll) replaceAllOpen = true })

        val response = st.response
        if (response?.limitHit == true) {
            PhoneFilesBanner(ProductIcon.AlertTriangle,
                "The result set only contains a subset of all matches. Be more specific in your search to narrow down the results.",
                Modifier.testTag("phone-search-banner-limit"))
        }
        if (response?.incomplete == "timeout") {
            PhoneFilesBanner(ProductIcon.AlertTriangle, "Search stopped after 30 s and may be incomplete. Narrow the files to include and",
                Modifier.testTag("phone-search-banner-timeout"), actionLabel = "Search again", onAction = controller::searchNow)
        }
        if (response?.engine == "grep") {
            PhoneFilesBanner(ProductIcon.Info,
                "Searched with GNU grep: ripgrep is not installed here, so match columns and replacements are computed in PPM.",
                Modifier.testTag("phone-search-banner-grep"))
        }
        if (response?.replaceUnavailable == true && fields.showReplace) {
            PhoneFilesBanner(ProductIcon.AlertTriangle, "grep cannot compute replacements for this pattern. Install ripgrep to replace it.",
                Modifier.testTag("phone-search-banner-replace-unavailable"))
        }
        st.bannerError?.let { error ->
            PhoneFilesBanner(ProductIcon.AlertCircle, if (error.status == 0) "Search failed: ${error.message}" else error.message,
                Modifier.testTag("phone-search-banner-error"), isError = true, actionLabel = "Retry", onAction = controller::searchNow)
        }

        val rows = remember(response, st.dismissed, st.collapsed) { phoneSearchRows(st) }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("phone-search-results")) {
            items(rows, key = { it.id }) { row ->
                when (row) {
                    is PhoneSearchRow.File -> FileRow(row, onToggle = { controller.toggleCollapsed(row.file) }, onMore = { fileSheet = row.file })
                    is PhoneSearchRow.Match -> MatchRow(
                        row = row,
                        showReplace = fields.showReplace,
                        tokens = tokens,
                        onOpen = { onOpenMatch(row.file, row.match.line) },
                        onReplace = { controller.replace(PhoneReplaceScope.Match(row.id)) },
                        onDismiss = { controller.dismiss(row.id) },
                    )
                }
            }
        }
    }

    fileSheet?.let { file ->
        Sheet(title = file.substringAfterLast('/'), subtitle = file, onDismiss = { fileSheet = null }, forceBottomSheet = true,
            modifier = Modifier.testTag("phone-search-file-sheet")) {
            Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                if (fields.showReplace) {
                    PhoneSheetActionRow(ProductIcon.Pencil, "Replace All in File", {
                        fileSheet = null
                        controller.replace(PhoneReplaceScope.File(file))
                    }, testTag = "phone-search-file-replace")
                }
                PhoneSheetActionRow(ProductIcon.Copy, "Copy Path", { fileSheet = null; onCopyPath(file) }, testTag = "phone-search-file-copy")
                PhoneSheetActionRow(ProductIcon.X, "Dismiss", { fileSheet = null; controller.dismiss(file) }, testTag = "phone-search-file-dismiss")
            }
        }
    }

    if (replaceAllOpen && project != null) {
        PhoneReplaceAllSheet(
            controller = controller,
            project = project,
            onSearchAgain = { replaceAllOpen = false; controller.searchNow() },
            onClose = { replaced ->
                replaceAllOpen = false
                // What was replaced is no longer what the list shows: read the project again.
                if (replaced) controller.searchNow()
            },
        )
    }
}

internal sealed interface PhoneSearchRow {
    val id: String
    data class File(override val id: String, val file: String, val count: Int, val collapsed: Boolean) : PhoneSearchRow
    data class Match(override val id: String, val file: String, val match: FileSearchMatch) : PhoneSearchRow
}

/** The list view of `buildRows`: each file with its folder, then its matches unless collapsed. */
internal fun phoneSearchRows(st: PhoneSearchState): List<PhoneSearchRow> = buildList {
    for (result in st.response?.results.orEmpty()) {
        val matches = visibleSearchMatches(result, st.dismissed)
        if (matches.isEmpty()) continue
        val collapsed = result.file in st.collapsed
        add(PhoneSearchRow.File("file:${result.file}", result.file, matches.size, collapsed))
        if (!collapsed) for (m in matches) add(PhoneSearchRow.Match(searchMatchKey(result.file, m), result.file, m))
    }
}

@Composable
private fun SearchField(
    key: Any?,
    value: String,
    placeholder: String,
    label: String,
    enabled: Boolean,
    testTag: String,
    onChange: (String) -> Unit,
    error: Boolean = false,
    leading: ProductIcon? = null,
    onSearch: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    val state = remember(key, label) { TextFieldState(value) }
    // What the field reported and has not yet seen come back as [value]: the stored query trails the field by a
    // frame, and putting that older text back dropped every key typed in between ("abcdefgh" became "aceg").
    val reported = remember(state) { ArrayDeque<String>() }
    // A Clear from the toolbar empties the stored query: follow it into the field.
    LaunchedEffect(value) {
        val echo = reported.indexOf(value)
        if (echo >= 0) repeat(echo + 1) { reported.removeFirst() }
        else if (state.text.toString() != value) state.edit { replace(0, length, value) }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.drop(1).collect {
            reported.addLast(it)
            onChange(it)
        }
    }
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = KitSpec.MinTouchTarget).clip(shape)
            .background(tokens.panel.color, shape)
            .cssBorder((if (error) tokens.error else tokens.borderSoft).color, shape, width = 1.dp)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (leading != null) PpmIcon(leading, 16.dp, tokens.text3.color)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (state.text.isEmpty()) {
                BasicText(placeholder, style = ppmText(16f, lineHeight = 22f).copy(color = tokens.text3.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                state = state,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().tracksTextFocus(state).testTag(testTag).semantics { contentDescription = label },
                textStyle = ppmText(16f, lineHeight = 22f).copy(color = tokens.text.color),
                cursorBrush = SolidColor(tokens.accent.color),
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = if (onSearch != null) ImeAction.Search else ImeAction.Done),
                onKeyboardAction = { onSearch?.invoke() },
            )
        }
        trailing?.invoke()
    }
}

@Composable
private fun StatusLine(st: PhoneSearchState, project: String?, onReplaceAll: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val style = ppmText(12.5f, lineHeight = 17f)
    FlowRow(
        Modifier.fillMaxWidth().heightIn(min = 26.dp).padding(horizontal = 14.dp, vertical = 4.dp).testTag("phone-search-status"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        if (project == null) BasicText("Select a project to search its files.", style = style.copy(color = tokens.text2.color))
        if (st.status == PhoneSearchStatus.SEARCHING) BasicText("Searching…", style = style.copy(color = tokens.text2.color))
        if (st.status != PhoneSearchStatus.SEARCHING && st.response != null) {
            BasicText(searchResultSummary(st.counts), Modifier.testTag("phone-search-summary"), style = style.copy(color = tokens.text2.color))
        }
        if (st.fields.showReplace && st.status != PhoneSearchStatus.SEARCHING && st.counts.total > 0) {
            val enabled = st.canReplaceAll
            BasicText(
                "· Replace All",
                Modifier.heightIn(min = KitSpec.MinTouchTarget).ppmClickable(enabled = enabled, onClickLabel = "Replace All", onClick = onReplaceAll)
                    .testTag("phone-search-replace-all"),
                style = style.copy(color = if (enabled) tokens.accent.color else tokens.text3.color, textDecoration = TextDecoration.Underline),
            )
        }
        if (st.replacing) BasicText("Replacing…", style = style.copy(color = tokens.text2.color))
        st.note?.let { note ->
            val color = when (note.tone) {
                PhoneSearchTone.SUCCESS -> tokens.success
                PhoneSearchTone.WARNING -> tokens.warning
                PhoneSearchTone.ERROR -> tokens.error
            }.color
            BasicText(note.text, Modifier.testTag("phone-search-note"), style = style.copy(color = color))
        }
    }
}

@Composable
private fun FileRow(row: PhoneSearchRow.File, onToggle: () -> Unit, onMore: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val name = row.file.substringAfterLast('/')
    val dir = row.file.substringBeforeLast('/', "")
    Row(
        Modifier.fillMaxWidth().heightIn(min = KitSpec.MinTouchTarget)
            .ppmClickable(onClickLabel = if (row.collapsed) "Expand" else "Collapse", onClick = onToggle)
            .padding(start = 12.dp, end = 4.dp)
            .testTag("phone-search-file-${row.file}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PpmIcon(if (row.collapsed) ProductIcon.ChevronRight else ProductIcon.ChevronDown, 14.dp, tokens.text3.color)
        FileIcon(name, size = 16.dp)
        BasicText(name, style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text.color), maxLines = 1)
        BasicText(dir, Modifier.weight(1f), style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
        val badge = RoundedCornerShape(999.dp)
        BasicText(
            "${row.count}",
            Modifier.clip(badge).cssBorder(tokens.border.color, badge, width = 1.dp).padding(horizontal = 6.dp),
            style = ppmText(10f, lineHeight = 14f).copy(color = tokens.text2.color),
        )
        IconAction(ProductIcon.MoreHorizontal, "More actions", "phone-search-file-more-${row.file}", onMore)
    }
}

@Composable
private fun MatchRow(
    row: PhoneSearchRow.Match,
    showReplace: Boolean,
    tokens: ThemeTokens,
    onOpen: () -> Unit,
    onReplace: () -> Unit,
    onDismiss: () -> Unit,
) {
    val m = row.match
    Row(
        Modifier.fillMaxWidth().heightIn(min = KitSpec.MinTouchTarget)
            .ppmClickable(onClickLabel = "Open ${row.file} at line ${m.line}", onClick = onOpen)
            .padding(start = 36.dp, end = 4.dp)
            .testTag("phone-search-match-${row.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            matchPreview(m, showReplace, tokens),
            Modifier.weight(1f),
            style = ppmText(13f, kind = FontKind.MONO, lineHeight = 18f).copy(color = tokens.text2.color),
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        if (showReplace && m.replacement != null) IconAction(LucideIcon.ReplaceAll, "Replace", "phone-search-replace-${row.id}", onReplace)
        IconAction(ProductIcon.X, "Dismiss", "phone-search-dismiss-${row.id}", onDismiss)
    }
}

/**
 * The slice of the line the server cut (`before` · match · `after`, "…" when `before` was cut),
 * highlighting exactly the server's `text`; with a replacement, the old text struck through beside
 * the new one. An old server's row is its whole line, unhighlighted.
 */
internal fun matchPreview(m: FileSearchMatch, showReplace: Boolean, tokens: ThemeTokens): AnnotatedString = buildAnnotatedString {
    if (m.cutBefore) withStyle(SpanStyle(color = tokens.text3.color)) { append("…") }
    append(m.before)
    if (m.text.isNotEmpty()) {
        val replacement = m.replacement
        if (showReplace && replacement != null) {
            withStyle(SpanStyle(background = tokens.error.opacity(0.22).color, color = tokens.text.color, textDecoration = TextDecoration.LineThrough)) { append(m.text) }
            withStyle(SpanStyle(background = tokens.success.opacity(0.22).color, color = tokens.text.color)) { append(replacement) }
        } else {
            withStyle(SpanStyle(background = tokens.warning.opacity(0.4).color, color = tokens.text.color)) { append(m.text) }
        }
    }
    append(m.after)
}

@Composable
private fun IconAction(icon: ProductIcon, label: String, testTag: String, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Box(
        Modifier.size(KitSpec.MinTouchTarget).clip(RoundedCornerShape(10.dp))
            .ppmClickable(onClickLabel = label, onClick = onClick).semantics { contentDescription = label }.testTag(testTag),
        contentAlignment = Alignment.Center,
    ) { PpmIcon(icon, 18.dp, tokens.text2.color) }
}

@Composable
private fun IconAction(icon: LucideIcon, label: String, testTag: String, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Box(
        Modifier.size(KitSpec.MinTouchTarget).clip(RoundedCornerShape(10.dp))
            .ppmClickable(onClickLabel = label, onClick = onClick).semantics { contentDescription = label }.testTag(testTag),
        contentAlignment = Alignment.Center,
    ) { PpmIcon(icon, 18.dp, tokens.text2.color) }
}

private data class ReplaceOutcome(val replaced: Int, val files: Int, val lines: List<Triple<String, Boolean, String>>)

/**
 * Replace All (`replace-all-dialog.tsx`): asks first, counting what the list shows and how many
 * dismissed matches stay as they are; sends exactly the listed matches, never a file with unsaved
 * edits; then reports every file skipped or failed. A refused request keeps the question open.
 */
@Composable
private fun PhoneReplaceAllSheet(
    controller: PhoneSearchController,
    project: String,
    onSearchAgain: () -> Unit,
    onClose: (replaced: Boolean) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val st by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var outcome by remember { mutableStateOf<ReplaceOutcome?>(null) }
    val listed = st.counts
    val dismissedCount = st.response?.results.orEmpty().sumOf { it.matches.size } - listed.total

    fun replace() {
        if (running) return
        running = true
        error = null
        val built = controller.replaceAllFiles()
        val unsavedLines = built.unsaved.map { Triple(it, false, "Skipped: unsaved changes in an open tab. Save or discard them, then replace again.") }
        scope.launch {
            try {
                val report = controller.replaceAll(project, built.files)
                outcome = ReplaceOutcome(
                    report.replaced, report.files,
                    unsavedLines + report.skipped.map { Triple(it.file, false, "Skipped: changed on disk since the search.") } +
                        report.failed.map { Triple(it.file, true, "Failed: ${it.reason}.") },
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                error = e.message ?: "Replace failed"
            } finally {
                running = false
            }
        }
    }

    val done = outcome
    val title = if (done != null) {
        "Replaced ${plural(done.replaced, "occurrence", "occurrences")} in ${plural(done.files, "file", "files")}"
    } else {
        "Replace All"
    }
    Sheet(title = title, onDismiss = { if (!running) onClose(done != null) }, forceBottomSheet = true, modifier = Modifier.testTag("phone-replace-all-sheet")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (done != null) {
                for ((file, failed, why) in done.lines) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PpmIcon(if (failed) ProductIcon.AlertCircle else ProductIcon.AlertTriangle, 16.dp, (if (failed) tokens.error else tokens.warning).color)
                        Column {
                            BasicText(file, style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text.color))
                            BasicText(why, style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhoneSheetButton("Search again", primary = false, enabled = true, testTag = "phone-replace-all-search-again", modifier = Modifier.weight(1f), onClick = onSearchAgain)
                    PhoneSheetButton("Done", primary = true, enabled = true, testTag = "phone-replace-all-done", modifier = Modifier.weight(1f), onClick = { onClose(true) })
                }
            } else {
                val with = if (st.fields.replace.isEmpty()) "with nothing" else "with ${st.fields.replace}"
                BasicText(
                    "Replace ${plural(listed.total, "occurrence", "occurrences")} across ${plural(listed.files, "file", "files")} $with?",
                    Modifier.testTag("phone-replace-all-question"),
                    style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text.color),
                )
                val dismissedNote = if (dismissedCount > 0) "${plural(dismissedCount, "dismissed match is", "dismissed matches are")} left as they are. " else ""
                BasicText(
                    dismissedNote + "Open editor tabs are updated; files with unsaved changes are skipped.",
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
                )
                error?.let { BasicText(it, Modifier.testTag("phone-replace-all-error"), style = ppmText(12f, lineHeight = 16f).copy(color = tokens.error.color)) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhoneSheetButton("Cancel", primary = false, enabled = !running, testTag = "phone-replace-all-cancel", modifier = Modifier.weight(1f), onClick = { onClose(false) })
                    PhoneSheetButton(if (running) "Replacing…" else "Replace", primary = true, enabled = !running && listed.total > 0,
                        testTag = "phone-replace-all-confirm", modifier = Modifier.weight(1f), onClick = ::replace)
                }
            }
        }
    }
}
