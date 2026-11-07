package com.hienlh.ppm.app.ui.database

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ui.SettingsButton
import com.hienlh.ppm.app.ui.SettingsTextInput
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.database. ColumnKind
import com.hienlh.ppm.state.database.ConditionInput
import com.hienlh.ppm.state.database.DbCellValue
import com.hienlh.ppm.state.database.FilterDialogRequest
import com.hienlh.ppm.state.database.FilterState
import com.hienlh.ppm.state.database.FilterableColumn
import com.hienlh.ppm.state.database.FunnelAction
import com.hienlh.ppm.state.database.FunnelItem
import com.hienlh.ppm.state.database.FunnelKind
import com.hienlh.ppm.state.database.GRID_VALUES_LIMIT
import com.hienlh.ppm.state.database.GridColumnSchema
import com.hienlh.ppm.state.database.GridFilters
import com.hienlh.ppm.state.database.GridSortItem
import com.hienlh.ppm.state.database.LOOKUP_ROWS
import com.hienlh.ppm.state.database.LinesMode
import com.hienlh.ppm.state.database.SortMenuAction
import com.hienlh.ppm.state.database.canChooseValues
import com.hienlh.ppm.state.database.cellValueKey
import com.hienlh.ppm.state.database.columnFilterState
import com.hienlh.ppm.state.database.columnNameMatches
import com.hienlh.ppm.state.database.conditionChoices
import com.hienlh.ppm.state.database.descriptionColumn
import com.hienlh.ppm.state.database.filterSyntax
import com.hienlh.ppm.state.database.funnelItems
import com.hienlh.ppm.state.database.linesFilter
import com.hienlh.ppm.state.database.multiFilterState
import com.hienlh.ppm.state.database.pickedCellValuesFilter
import com.hienlh.ppm.state.database.setFilterText
import com.hienlh.ppm.state.database.takesValue
import com.hienlh.ppm.state.database.toFunnelKind
import com.hienlh.ppm.state.database.valueText
import com.hienlh.ppm.state.database.withColumnFilter
import com.hienlh.ppm.state.database.withFilterOff
import com.hienlh.ppm.state.database.withMultiFilter

// ============================================================================
// Phase 09c: Database — DBGate Filter Row, Funnel Menu, Filter Dialogs & Sheets
// ============================================================================

private const val LOOKUP_DESC_PREF_FILE = "ppm_db_lookup_prefs"

data class LookupValueListResult(
    val values: List<DbCellValue> = emptyList(),
    val hasMore: Boolean = false,
    val error: String? = null,
)

data class LookupTableRowsResult(
    val columns: List<FilterableColumn> = emptyList(),
    val rows: List<Map<String, DbCellValue>> = emptyList(),
    val hasMore: Boolean = false,
    val error: String? = null,
)

sealed interface ActiveFilterModal {
    data class SetFilter(
        val column: String?, // null = Multi column filter
        val request: FilterDialogRequest.ConditionDialog,
    ) : ActiveFilterModal

    data class MultiValues(
        val column: String?, // null = Multi column filter
    ) : ActiveFilterModal

    data class ChooseValue(
        val column: String,
        val kind: ColumnKind,
    ) : ActiveFilterModal

    data class FkLookup(
        val column: String,
        val kind: ColumnKind,
        val fkTable: String,
        val fkSchema: String?,
        val fkColumn: String,
    ) : ActiveFilterModal
}

/**
 * 9c.2 & 9c.3 Single DBGate Filter Cell (`filter-row.tsx` + `column-filter-cell.ts`).
 *
 * - Runs `read(draft)` synchronously on every keystroke (< 1 ms) to tint the cell green (`Ok`) or red (`Bad`).
 * - Multi-line paste automatically converts lines into a comma-separated OR list via `linesFilter(LinesMode.IS, text)`.
 * - Commits on Enter, focus blur, Escape (clears), or `×` click.
 * - Shows `⋮` (Choose value) or `⋯` (FK Lookup) and `▽` (Funnel) when empty, or `×` when non-empty.
 */
@Composable
fun FilterCellBox(
    tagSuffix: String,
    value: String,
    off: Boolean = false,
    serverError: String? = null,
    placeholder: String = "Filter",
    panelSize: Boolean = false,
    read: (String) -> FilterState,
    onCommit: (String) -> Unit,
    onOpenChooseValues: (() -> Unit)? = null,
    onOpenLookup: (() -> Unit)? = null,
    onToggleFunnel: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    var draft by remember(value) { mutableStateOf(value) }
    var hasFocus by remember { mutableStateOf(false) }

    val displayState = remember(draft, value, off, serverError) {
        if (draft != value) {
            read(draft)
        } else if (!serverError.isNullOrBlank() && draft.trim().isNotEmpty()) {
            FilterState.Bad(com.hienlh.ppm.state.database.FilterSyntaxError(serverError, 0, 0))
        } else {
            val st = read(draft)
            if (st is FilterState.Ok && off) FilterState.Off else st
        }
    }

    val borderColor = when (displayState) {
        is FilterState.Ok -> tokens.success.opacity(0.65).color
        is FilterState.Bad -> tokens.error.opacity(0.75).color
        is FilterState.Off -> tokens.border.color
        is FilterState.Empty -> if (hasFocus) tokens.accent.color else tokens.border.color
    }
    val bgColor = when (displayState) {
        is FilterState.Ok -> tokens.success.opacity(0.12).color
        is FilterState.Bad -> tokens.error.opacity(0.12).color
        is FilterState.Off, is FilterState.Empty -> tokens.bgSolid.color
    }
    val textColor = when (displayState) {
        is FilterState.Ok -> tokens.success.color
        is FilterState.Bad -> tokens.error.color
        is FilterState.Off -> tokens.text3.color
        is FilterState.Empty -> tokens.text.color
    }

    fun commitText(next: String) {
        draft = next
        onCommit(next)
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (panelSize) 26.dp else 22.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(bgColor)
                .cssBorder(borderColor)
                .padding(horizontal = 4.dp)
                .testTag("db-filter-box-$tagSuffix"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (draft.isEmpty()) {
                    BasicText(
                        placeholder,
                        style = ppmText(11f, kind = FontKind.MONO, lineHeight = 14f).copy(
                            color = tokens.text3.opacity(0.7).color,
                        ),
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = draft,
                    onValueChange = { incoming ->
                        if (incoming.contains('\n')) {
                            val converted = linesFilter(LinesMode.IS, incoming)
                            if (converted.isNotEmpty()) {
                                commitText(converted)
                            }
                        } else {
                            draft = incoming
                        }
                    },
                    singleLine = true,
                    textStyle = ppmText(11f, kind = FontKind.MONO, lineHeight = 14f).copy(
                        color = textColor,
                        textDecoration = if (displayState is FilterState.Off) TextDecoration.LineThrough else TextDecoration.None,
                    ),
                    cursorBrush = SolidColor(tokens.accent.color),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = { commitText(draft) },
                        onDone = { commitText(draft) },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { st ->
                            val wasFocused = hasFocus
                            hasFocus = st.isFocused
                            if (wasFocused && !st.isFocused && draft != value) {
                                onCommit(draft)
                            }
                        }
                        .onPreviewKeyEvent { ev ->
                            if (ev.type == KeyEventType.KeyDown) {
                                when (ev.key) {
                                    Key.Enter, Key.NumPadEnter -> {
                                        commitText(draft)
                                        true
                                    }
                                    Key.Escape -> {
                                        commitText("")
                                        true
                                    }
                                    else -> false
                                }
                            } else {
                                false
                            }
                        }
                        .testTag("db-filter-input-$tagSuffix"),
                )
            }

            if (draft.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .clickable { commitText("") }
                        .padding(horizontal = 3.dp, vertical = 1.dp)
                        .testTag("db-filter-clear-$tagSuffix"),
                ) {
                    BasicText(
                        "×",
                        style = ppmText(11f, FontWeight.Bold, lineHeight = 13f).copy(color = tokens.text3.color),
                    )
                }
            } else {
                if (onOpenLookup != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .clickable { onOpenLookup() }
                            .padding(horizontal = 3.dp, vertical = 1.dp)
                            .testTag("db-filter-lookup-btn-$tagSuffix"),
                    ) {
                        BasicText(
                            "⋯",
                            style = ppmText(10f, FontWeight.Bold, lineHeight = 12f).copy(color = tokens.text3.color),
                        )
                    }
                } else if (onOpenChooseValues != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .clickable { onOpenChooseValues() }
                            .padding(horizontal = 3.dp, vertical = 1.dp)
                            .testTag("db-filter-choose-btn-$tagSuffix"),
                    ) {
                        BasicText(
                            "⋮",
                            style = ppmText(10f, FontWeight.Bold, lineHeight = 12f).copy(color = tokens.text3.color),
                        )
                    }
                }
                if (onToggleFunnel != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .clickable { onToggleFunnel() }
                            .padding(horizontal = 3.dp, vertical = 1.dp)
                            .testTag("db-filter-funnel-btn-$tagSuffix"),
                    ) {
                        BasicText(
                            "▽",
                            style = ppmText(10f, lineHeight = 12f).copy(color = tokens.text3.color),
                        )
                    }
                }
            }
        }

        if (displayState is FilterState.Bad) {
            BasicText(
                displayState.error.message,
                style = ppmText(10f, lineHeight = 13f).copy(color = tokens.error.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 1.dp, start = 2.dp)
                    .testTag("db-filter-error-$tagSuffix"),
            )
        }
    }
}

/**
 * 9c.2 Funnel Menu Strip (`filter-funnel-menu.ts`) opened for a column or multi-column filter.
 */
@Composable
fun FunnelDropdownBar(
    targetLabel: String,
    tagSuffix: String,
    funnelKind: FunnelKind,
    onWriteFilter: (String) -> Unit,
    onOpenDialog: (FilterDialogRequest) -> Unit,
    onClose: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val items = remember(funnelKind) { funnelItems(funnelKind) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, sides = CssSides.Bottom)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("db-funnel-menu-$tagSuffix"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            "Filter ($targetLabel):",
            style = ppmText(11f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 14f).copy(color = tokens.text.color),
        )
        items.forEachIndexed { idx, item ->
            when (item) {
                FunnelItem.Separator -> {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(16.dp)
                            .background(tokens.border.color),
                    )
                }
                is FunnelItem.Entry -> {
                    SettingsButton(
                        label = item.label,
                        modifier = Modifier.testTag("db-funnel-item-$tagSuffix-${item.label}"),
                        onClick = {
                            onClose()
                            when (val act = item.action) {
                                is FunnelAction.WriteText -> onWriteFilter(act.text)
                                is FunnelAction.OpenDialog -> onOpenDialog(act.request)
                            }
                        },
                    )
                }
            }
        }
        SettingsButton(label = "Close", onClick = onClose)
    }
}

/**
 * 9c.3 Set Filter Dialog (`set-filter-dialog.tsx`) — 2 conditions joined by And / Or.
 */
@Composable
fun SetFilterDialog(
    column: String?,
    request: FilterDialogRequest.ConditionDialog,
    onSubmit: (String) -> Unit,
    onClose: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val focusManager = LocalFocusManager.current
    val choices = remember(request.kind) { conditionChoices(request.kind) }

    var firstOp by remember(request) { mutableStateOf(request.first) }
    var firstVal by remember(request) { mutableStateOf("") }
    var joinMode by remember(request) { mutableStateOf("and") }
    var secondOp by remember(request) { mutableStateOf(request.second) }
    var secondVal by remember(request) { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.panel2.color)
            .cssBorder(tokens.border.color, sides = CssSides.Bottom)
            .padding(12.dp)
            .testTag("db-set-filter-dialog"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                "Set filter (${column ?: "All columns"}) — Show rows where:",
                style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color),
            )
            SettingsButton(
                label = "×",
                onClick = {
                    focusManager.clearFocus()
                    onClose()
                },
            )
        }

        // First condition
        BasicText("First condition:", style = ppmText(11f, lineHeight = 14f).copy(color = tokens.text2.color))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (choice in choices) {
                SettingsButton(
                    label = choice.label,
                    primary = firstOp == choice.op,
                    modifier = Modifier.testTag("db-set-filter-first-op-${choice.op}"),
                    onClick = { firstOp = choice.op },
                )
            }
        }
        if (takesValue(firstOp)) {
            SettingsTextInput(
                value = firstVal,
                onValueChange = { firstVal = it },
                placeholder = "First value",
                mono = firstOp == "sql" || firstOp == "sqlRight",
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("db-set-filter-first-value"),
            )
        }

        // And / Or join selector
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SettingsButton(
                label = "And",
                primary = joinMode == "and",
                modifier = Modifier.testTag("db-set-filter-join-and"),
                onClick = { joinMode = "and" },
            )
            SettingsButton(
                label = "Or",
                primary = joinMode == "or",
                modifier = Modifier.testTag("db-set-filter-join-or"),
                onClick = { joinMode = "or" },
            )
        }

        // Second condition
        BasicText("Second condition:", style = ppmText(11f, lineHeight = 14f).copy(color = tokens.text2.color))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (choice in choices) {
                SettingsButton(
                    label = choice.label,
                    primary = secondOp == choice.op,
                    modifier = Modifier.testTag("db-set-filter-second-op-${choice.op}"),
                    onClick = { secondOp = choice.op },
                )
            }
        }
        if (takesValue(secondOp)) {
            SettingsTextInput(
                value = secondVal,
                onValueChange = { secondVal = it },
                placeholder = "Second value",
                mono = secondOp == "sql" || secondOp == "sqlRight",
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("db-set-filter-second-value"),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsButton(
                label = "OK",
                primary = true,
                modifier = Modifier.testTag("db-set-filter-ok-btn"),
                onClick = {
                    val text = setFilterText(
                        kind = request.kind,
                        first = ConditionInput(firstOp, firstVal),
                        join = joinMode,
                        second = ConditionInput(secondOp, secondVal),
                    )
                    focusManager.clearFocus()
                    onClose()
                    if (text.isNotEmpty()) onSubmit(text)
                },
            )
            SettingsButton(
                label = "Cancel",
                onClick = {
                    focusManager.clearFocus()
                    onClose()
                },
            )
        }
    }
}

/**
 * 9c.3 Filter Multiple Values Dialog (`filter-multiple-values-dialog.tsx`).
 */
@Composable
fun FilterMultipleValuesDialog(
    column: String?,
    onSubmit: (String) -> Unit,
    onClose: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val focusManager = LocalFocusManager.current
    var linesText by remember { mutableStateOf("") }
    var selectedMode by remember { mutableStateOf(LinesMode.IS) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.panel2.color)
            .cssBorder(tokens.border.color, sides = CssSides.Bottom)
            .padding(12.dp)
            .testTag("db-multi-values-dialog"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                "Filter multiple values (${column ?: "All columns"}) — One value per line",
                style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color),
            )
            SettingsButton(
                label = "×",
                onClick = {
                    focusManager.clearFocus()
                    onClose()
                },
            )
        }

        SettingsTextInput(
            value = linesText,
            onValueChange = { linesText = it },
            placeholder = "value1\nvalue2\nvalue3",
            singleLine = false,
            mono = true,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp, max = 180.dp)
                .testTag("db-multi-values-input"),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (mode in LinesMode.entries) {
                SettingsButton(
                    label = mode.label,
                    primary = selectedMode == mode,
                    modifier = Modifier.testTag("db-multi-values-mode-${mode.id}"),
                    onClick = { selectedMode = mode },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsButton(
                label = "OK",
                primary = true,
                modifier = Modifier.testTag("db-multi-values-ok-btn"),
                onClick = {
                    val built = linesFilter(selectedMode, linesText)
                    focusManager.clearFocus()
                    onClose()
                    if (built.isNotEmpty()) onSubmit(built)
                },
            )
            SettingsButton(
                label = "Cancel",
                onClick = {
                    focusManager.clearFocus()
                    onClose()
                },
            )
        }
    }
}

/**
 * 9c.3 ⋮ Choose Value Dialog (`value-lookup-dialog.tsx`) — up to 100 distinct values from `POST DB/grid/values`.
 */
@Composable
fun ValueLookupDialog(
    column: String,
    kind: ColumnKind,
    onLoadValues: suspend (search: String) -> LookupValueListResult,
    onSubmit: (String) -> Unit,
    onClose: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val focusManager = LocalFocusManager.current
    var search by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<LookupValueListResult?>(null) }
    val pickedMap = remember { mutableStateMapOf<String, DbCellValue>() }
    val pickedOrder = remember { mutableStateListOf<String>() }

    LaunchedEffect(column, search) {
        loading = true
        result = onLoadValues(search)
        loading = false
    }

    val values = result?.values.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.panel2.color)
            .cssBorder(tokens.border.color, sides = CssSides.Bottom)
            .padding(12.dp)
            .testTag("db-choose-value-dialog"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                "Choose value from $column",
                style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color),
            )
            SettingsButton(
                label = "×",
                onClick = {
                    focusManager.clearFocus()
                    onClose()
                },
            )
        }

        SettingsTextInput(
            value = search,
            onValueChange = { search = it },
            placeholder = "Search values",
            modifier = Modifier
                .fillMaxWidth()
                .testTag("db-choose-value-search"),
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp, max = 200.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(tokens.panel.color)
                .cssBorder(tokens.border.color),
        ) {
            items(values, key = { cellValueKey(it) }) { item ->
                val key = cellValueKey(item)
                val isChecked = pickedMap.containsKey(key)
                val label = if (item is DbCellValue.Null) "NULL" else valueText(item)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (isChecked) {
                                pickedMap.remove(key)
                                pickedOrder.remove(key)
                            } else {
                                pickedMap[key] = item
                                pickedOrder.add(key)
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("db-choose-value-row-$label"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        if (isChecked) "☑" else "☐",
                        style = ppmText(12f, FontWeight.Bold, lineHeight = 15f).copy(
                            color = if (isChecked) tokens.accent.color else tokens.text3.color,
                        ),
                    )
                    BasicText(
                        label,
                        style = ppmText(12f, kind = FontKind.MONO, lineHeight = 15f).copy(
                            color = if (item is DbCellValue.Null) tokens.text3.color else tokens.text.color,
                        ),
                    )
                }
            }
        }

        val err = result?.error
        if (loading && result == null) {
            BasicText("Loading…", style = ppmText(11f, lineHeight = 14f).copy(color = tokens.text3.color))
        } else if (!err.isNullOrBlank()) {
            BasicText(err, style = ppmText(11f, lineHeight = 14f).copy(color = tokens.error.color))
        } else if (result?.hasMore == true) {
            BasicText(
                "Showing the first $GRID_VALUES_LIMIT values. Search to find the others.",
                style = ppmText(11f, lineHeight = 14f).copy(color = tokens.text3.color),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsButton(
                label = "OK (${pickedMap.size})",
                primary = true,
                modifier = Modifier.testTag("db-choose-value-ok-btn"),
                onClick = {
                    val orderedValues = pickedOrder.mapNotNull { pickedMap[it] }
                    val filterTxt = pickedCellValuesFilter(kind, orderedValues)
                    focusManager.clearFocus()
                    onClose()
                    if (filterTxt.isNotEmpty()) onSubmit(filterTxt)
                },
            )
            SettingsButton(
                label = "Cancel",
                onClick = {
                    focusManager.clearFocus()
                    onClose()
                },
            )
        }
    }
}

/**
 * 9c.3 ⋯ FK Dictionary Lookup Dialog (`dictionary-lookup-dialog.tsx`).
 *
 * - Queries the referenced table and remembers the chosen Description column per table in SharedPreferences.
 */
@Composable
fun DictionaryLookupDialog(
    fkTable: String,
    fkSchema: String?,
    fkColumn: String,
    kind: ColumnKind,
    onLoadLookup: suspend (search: String, chosenDescription: String?) -> LookupTableRowsResult,
    onSubmit: (String) -> Unit,
    onClose: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val prefs = remember(context) {
        context.getSharedPreferences(LOOKUP_DESC_PREF_FILE, Context.MODE_PRIVATE)
    }
    val prefKey = remember(fkSchema, fkTable) { "desc:${fkSchema.orEmpty()}.$fkTable" }

    var chosenDesc by remember(prefKey) {
        mutableStateOf(prefs.getString(prefKey, null))
    }
    var customizing by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<LookupTableRowsResult?>(null) }
    val pickedMap = remember { mutableStateMapOf<String, DbCellValue>() }
    val pickedOrder = remember { mutableStateListOf<String>() }

    LaunchedEffect(fkTable, fkSchema, fkColumn, search, chosenDesc) {
        loading = true
        result = onLoadLookup(search, chosenDesc)
        loading = false
    }

    val allCols = result?.columns.orEmpty()
    val effectiveDescCol = remember(allCols, fkColumn, chosenDesc) {
        descriptionColumn(allCols, fkColumn, chosenDesc)
    }
    val rows = result?.rows.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.panel2.color)
            .cssBorder(tokens.border.color, sides = CssSides.Bottom)
            .padding(12.dp)
            .testTag("db-fk-lookup-dialog"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                "Lookup from $fkTable",
                style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingsButton(
                    label = "Customize",
                    primary = customizing,
                    modifier = Modifier.testTag("db-fk-lookup-customize-btn"),
                    onClick = { customizing = !customizing },
                )
                SettingsButton(
                    label = "×",
                    onClick = {
                        focusManager.clearFocus()
                        onClose()
                    },
                )
            }
        }

        SettingsTextInput(
            value = search,
            onValueChange = { search = it },
            placeholder = "Search $fkTable",
            modifier = Modifier
                .fillMaxWidth()
                .testTag("db-fk-lookup-search"),
        )

        if (customizing && allCols.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText("Description:", style = ppmText(11f, lineHeight = 14f).copy(color = tokens.text2.color))
                for (c in allCols.filter { it.name != fkColumn }) {
                    SettingsButton(
                        label = c.name,
                        primary = effectiveDescCol == c.name,
                        modifier = Modifier.testTag("db-fk-lookup-desc-col-${c.name}"),
                        onClick = {
                            chosenDesc = c.name
                            prefs.edit().putString(prefKey, c.name).apply()
                        },
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp, max = 200.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(tokens.panel.color)
                .cssBorder(tokens.border.color),
        ) {
            items(rows.size) { rIdx ->
                val row = rows[rIdx]
                val pkVal = row[fkColumn] ?: DbCellValue.Null
                val key = cellValueKey(pkVal)
                val isChecked = pickedMap.containsKey(key)
                val pkLabel = if (pkVal is DbCellValue.Null) "NULL" else valueText(pkVal)
                val descVal = effectiveDescCol?.let { row[it] }
                val descLabel = if (descVal == null || descVal is DbCellValue.Null) "" else valueText(descVal)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (isChecked) {
                                pickedMap.remove(key)
                                pickedOrder.remove(key)
                            } else {
                                pickedMap[key] = pkVal
                                pickedOrder.add(key)
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("db-fk-lookup-row-$pkLabel"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        if (isChecked) "☑" else "☐",
                        style = ppmText(12f, FontWeight.Bold, lineHeight = 15f).copy(
                            color = if (isChecked) tokens.accent.color else tokens.text3.color,
                        ),
                    )
                    BasicText(
                        pkLabel,
                        style = ppmText(12f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 15f).copy(color = tokens.text.color),
                        modifier = Modifier.width(84.dp),
                    )
                    if (descLabel.isNotEmpty()) {
                        BasicText(
                            descLabel,
                            style = ppmText(12f, lineHeight = 15f).copy(color = tokens.text2.color),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        if (result?.hasMore == true) {
            BasicText(
                "Showing the first $LOOKUP_ROWS rows. Search to find the others.",
                style = ppmText(11f, lineHeight = 14f).copy(color = tokens.text3.color),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsButton(
                label = "OK (${pickedMap.size})",
                primary = true,
                modifier = Modifier.testTag("db-fk-lookup-ok-btn"),
                onClick = {
                    val orderedValues = pickedOrder.mapNotNull { pickedMap[it] }
                    val filterTxt = pickedCellValuesFilter(kind, orderedValues)
                    focusManager.clearFocus()
                    onClose()
                    if (filterTxt.isNotEmpty()) onSubmit(filterTxt)
                },
            )
            SettingsButton(
                label = "Cancel",
                onClick = {
                    focusManager.clearFocus()
                    onClose()
                },
            )
        }
    }
}

/**
 * 9c.3 Filters Side Panel (`filters-panel.tsx`) — Multi column filter + active/toggled column filters.
 */
@Composable
fun FiltersSidePanel(
    filters: GridFilters,
    filterableCols: List<FilterableColumn>,
    schemaMap: Map<String, GridColumnSchema>,
    serverErrors: Map<String, String> = emptyMap(),
    onChangeFilters: (GridFilters) -> Unit,
    onOpenChooseValues: (String, ColumnKind) -> Unit,
    onOpenLookup: (String, ColumnKind, String, String?, String) -> Unit,
    onOpenFunnelFor: (String?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current

    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, sides = CssSides.Start)
            .padding(10.dp)
            .testTag("db-filters-side-panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                "Filters",
                style = ppmText(12f, FontWeight.SemiBold, lineHeight = 15f).copy(color = tokens.text.color),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SettingsButton(
                    label = "Clear all",
                    modifier = Modifier.testTag("db-filters-clear-all-btn"),
                    onClick = { onChangeFilters(GridFilters()) },
                )
                SettingsButton(label = "×", onClick = onClose)
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 1. Multi column filter block
            val multi = filters.multi
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        "Multi column filter",
                        style = ppmText(11f, FontWeight.Medium, lineHeight = 14f).copy(color = tokens.text2.color),
                    )
                    if (multi != null && multi.text.trim().isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            BasicText(
                                if (!multi.off) "☑" else "☐",
                                modifier = Modifier
                                    .clickable { onChangeFilters(withFilterOff(filters, null, !multi.off)) }
                                    .testTag("db-filter-panel-toggle-multi"),
                                style = ppmText(12f, FontWeight.Bold, lineHeight = 14f).copy(
                                    color = if (!multi.off) tokens.accent.color else tokens.text3.color,
                                ),
                            )
                            BasicText(
                                "×",
                                modifier = Modifier
                                    .clickable { onChangeFilters(withMultiFilter(filters, "")) }
                                    .testTag("db-filter-panel-remove-multi"),
                                style = ppmText(12f, FontWeight.Bold, lineHeight = 14f).copy(color = tokens.text3.color),
                            )
                        }
                    }
                }
                FilterCellBox(
                    tagSuffix = "panel-multi",
                    value = multi?.text.orEmpty(),
                    off = multi?.off == true,
                    placeholder = "Filter any column",
                    panelSize = true,
                    read = { txt -> multiFilterState( com.hienlh.ppm.state.database.FilterText(txt), filterableCols) },
                    onCommit = { txt -> onChangeFilters(withMultiFilter(filters, txt)) },
                    onToggleFunnel = { onOpenFunnelFor(null) },
                )
            }

            // 2. Active / switched-off column filters in table column order
            for (col in filterableCols.filter { filters.columns.containsKey(it.name) }) {
                val ft = filters.columns[col.name] ?: continue
                val colSchema = schemaMap[col.name]
                val fkTable = colSchema?.fkTable
                val fkCol = colSchema?.fkColumn ?: "id"

                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (colSchema?.pk == true) {
                                BasicText("🔑", style = ppmText(10f, lineHeight = 13f))
                            } else if (fkTable != null) {
                                BasicText("🔗", style = ppmText(10f, lineHeight = 13f))
                            }
                            BasicText(
                                col.name,
                                style = ppmText(
                                    11f,
                                    if (colSchema?.nullable == false) FontWeight.SemiBold else FontWeight.Normal,
                                    FontKind.MONO,
                                    lineHeight = 14f,
                                ).copy(color = if (colSchema?.nullable == false) tokens.text.color else tokens.text2.color),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            BasicText(
                                if (!ft.off) "☑" else "☐",
                                modifier = Modifier
                                    .clickable { onChangeFilters(withFilterOff(filters, col.name, !ft.off)) }
                                    .testTag("db-filter-panel-toggle-${col.name}"),
                                style = ppmText(12f, FontWeight.Bold, lineHeight = 14f).copy(
                                    color = if (!ft.off) tokens.accent.color else tokens.text3.color,
                                ),
                            )
                            BasicText(
                                "×",
                                modifier = Modifier
                                    .clickable { onChangeFilters(withColumnFilter(filters, col.name, "")) }
                                    .testTag("db-filter-panel-remove-${col.name}"),
                                style = ppmText(12f, FontWeight.Bold, lineHeight = 14f).copy(color = tokens.text3.color),
                            )
                        }
                    }

                    FilterCellBox(
                        tagSuffix = "panel-${col.name}",
                        value = ft.text,
                        off = ft.off,
                        serverError = serverErrors[col.name],
                        panelSize = true,
                        read = { txt -> columnFilterState(com.hienlh.ppm.state.database.FilterText(txt), col.kind) },
                        onCommit = { txt -> onChangeFilters(withColumnFilter(filters, col.name, txt)) },
                        onOpenChooseValues = if (fkTable == null && canChooseValues(col.kind)) {
                            { onOpenChooseValues(col.name, col.kind) }
                        } else {
                            null
                        },
                        onOpenLookup = if (fkTable != null) {
                            { onOpenLookup(col.name, col.kind, fkTable, colSchema.fkSchema, fkCol) }
                        } else {
                            null
                        },
                        onToggleFunnel = { onOpenFunnelFor(col.name) },
                    )
                }
            }
        }
    }
}

/**
 * 9c.4 Phone Filter Chips Bar (`filter-chips.tsx`) above the grid on mobile.
 */
@Composable
fun PhoneFilterChipsBar(
    filters: GridFilters,
    filterableCols: List<FilterableColumn>,
    sort: List<GridSortItem>,
    hiddenCount: Int,
    serverErrors: Map<String, String> = emptyMap(),
    onOpenColumnSheet: (String?) -> Unit,
    onOpenColumnsAndFiltersSheet: () -> Unit,
    onChangeFilters: (GridFilters) -> Unit,
) {
    val tokens = LocalPpmTokens.current

    data class ChipItem(
        val column: String?, // null = any column
        val text: String,
        val stateName: String,
    )

    val chips = remember(filters, filterableCols, serverErrors) {
        val list = mutableListOf<ChipItem>()
        for (c in filterableCols) {
            val ft = filters.columns[c.name] ?: continue
            val st = columnFilterState(ft, c.kind)
            if (st !is FilterState.Empty) {
                val refused = st is FilterState.Ok && serverErrors.containsKey(c.name)
                list.add(ChipItem(c.name, ft.text, if (refused) "bad" else st.stateName))
            }
        }
        val m = filters.multi
        if (m != null) {
            val mst = multiFilterState(m, filterableCols)
            if (mst !is FilterState.Empty) {
                list.add(ChipItem(null, m.text, mst.stateName))
            }
        }
        list
    }

    if (chips.isEmpty() && sort.isEmpty() && hiddenCount == 0) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, sides = CssSides.Bottom)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("db-phone-filter-chips"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hiddenCount > 0) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(tokens.panel2.color)
                    .cssBorder(tokens.border.color)
                    .clickable { onOpenColumnsAndFiltersSheet() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .testTag("db-phone-chip-hidden-cols"),
            ) {
                BasicText(
                    "Columns · $hiddenCount hidden",
                    style = ppmText(11f, FontWeight.Medium, lineHeight = 14f).copy(color = tokens.text2.color),
                )
            }
        }

        for (chip in chips) {
            val chipName = chip.column ?: "any column"
            val chipTag = chip.column ?: "multi"
            val borderCol = when (chip.stateName) {
                "ok" -> tokens.success.opacity(0.55).color
                "bad" -> tokens.error.opacity(0.65).color
                else -> tokens.border.color
            }
            val bgCol = when (chip.stateName) {
                "ok" -> tokens.success.opacity(0.12).color
                "bad" -> tokens.error.opacity(0.12).color
                else -> tokens.panel2.color
            }
            val fgCol = when (chip.stateName) {
                "ok" -> tokens.success.color
                "bad" -> tokens.error.color
                else -> tokens.text3.color
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(bgCol)
                    .cssBorder(borderCol)
                    .testTag("db-phone-filter-chip-$chipTag"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .clickable { onOpenColumnSheet(chip.column) }
                        .padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        if (chip.stateName == "bad") "⚠" else "▽",
                        style = ppmText(10f, lineHeight = 13f).copy(color = fgCol),
                    )
                    BasicText(
                        chipName,
                        style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f).copy(color = fgCol),
                    )
                    BasicText(
                        chip.text,
                        style = ppmText(11f, kind = FontKind.MONO, lineHeight = 14f).copy(
                            color = fgCol,
                            textDecoration = if (chip.stateName == "off") TextDecoration.LineThrough else TextDecoration.None,
                        ),
                        maxLines = 1,
                    )
                }
                Box(
                    modifier = Modifier
                        .clickable {
                            val next = if (chip.column == null) {
                                withMultiFilter(filters, "")
                            } else {
                                withColumnFilter(filters, chip.column, "")
                            }
                            onChangeFilters(next)
                        }
                        .padding(start = 4.dp, end = 8.dp, top = 5.dp, bottom = 5.dp)
                        .testTag("db-phone-filter-chip-remove-$chipTag"),
                ) {
                    BasicText("×", style = ppmText(11f, FontWeight.Bold, lineHeight = 14f).copy(color = fgCol))
                }
            }
        }

        if (sort.isNotEmpty()) {
            val sortLabel = sort.joinToString(", ") { "${it.column} ${if (it.dir == "ASC") "↑" else "↓"}" }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(tokens.panel2.color)
                    .cssBorder(tokens.border.color)
                    .clickable { onOpenColumnSheet(sort.first().column) }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .testTag("db-phone-chip-sort"),
            ) {
                BasicText(
                    "↕ $sortLabel",
                    style = ppmText(11f, FontWeight.Medium, FontKind.MONO, lineHeight = 14f).copy(color = tokens.text2.color),
                )
            }
        }
    }
}

/**
 * 9c.4 Phone Column Filter & Sort Bottom Sheet (`filter-sheet.tsx`).
 *
 * - Opened by tapping a column header or `⌄` on phone, or tapping a filter chip.
 * - Does **not** re-focus any underlying field when closed (`focusManager.clearFocus()`).
 */
@Composable
fun PhoneColumnFilterSheet(
    column: String?, // null = Multi column filter
    filters: GridFilters,
    filterableCols: List<FilterableColumn>,
    schemaMap: Map<String, GridColumnSchema>,
    sort: List<GridSortItem>,
    serverErrors: Map<String, String> = emptyMap(),
    onChangeFilters: (GridFilters) -> Unit,
    onSortAction: (String, SortMenuAction) -> Unit,
    onCopyColumnName: (String) -> Unit,
    onHideColumn: (String) -> Unit,
    onOpenDialog: (String?, FilterDialogRequest) -> Unit,
    onOpenChooseValues: (String, ColumnKind) -> Unit,
    onOpenLookup: (String, ColumnKind, String, String?, String) -> Unit,
    onClose: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val focusManager = LocalFocusManager.current

    val colKind = remember(column, filterableCols) {
        if (column == null) null else filterableCols.firstOrNull { it.name == column }?.kind ?: ColumnKind.OTHER
    }
    val committed = if (column == null) filters.multi else filters.columns[column]
    var draft by remember(column, committed?.text) { mutableStateOf(committed?.text.orEmpty()) }
    val colSchema = if (column == null) null else schemaMap[column]

    val readState = remember(draft, colKind, filterableCols) {
        if (colKind == null) {
            multiFilterState(com.hienlh.ppm.state.database.FilterText(draft), filterableCols)
        } else {
            columnFilterState(com.hienlh.ppm.state.database.FilterText(draft), colKind)
        }
    }
    val unchanged = draft == committed?.text.orEmpty()
    val refused = if (unchanged && readState is FilterState.Ok && column != null) serverErrors[column] else null
    val statusText = when {
        !refused.isNullOrBlank() -> "Refused: $refused"
        readState is FilterState.Bad -> "${readState.error.message} — it would not be applied"
        unchanged && readState is FilterState.Ok && committed?.off == true -> "Switched off · Apply switches it on"
        readState is FilterState.Ok -> "Understood · Apply filters the rows"
        column == null -> "No filter on any column"
        else -> "No filter on this column"
    }
    val statusColor = when {
        !refused.isNullOrBlank() || readState is FilterState.Bad -> tokens.error.color
        readState is FilterState.Ok && !(unchanged && committed?.off == true) -> tokens.success.color
        else -> tokens.text3.color
    }

    fun closeWithoutRefocus() {
        focusManager.clearFocus()
        onClose()
    }

    fun commitText(text: String) {
        closeWithoutRefocus()
        val next = if (column == null) withMultiFilter(filters, text) else withColumnFilter(filters, column, text)
        onChangeFilters(next)
    }

    val funnelKind = if (colKind == null) FunnelKind.MULTI else filterSyntax(colKind).toFunnelKind()
    val presetItems = remember(funnelKind) {
        funnelItems(funnelKind)
            .filterIsInstance<FunnelItem.Entry>()
            .filter { it.label != "Clear Filter" }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.panel2.color)
            .cssBorder(tokens.border.color, sides = CssSides.Top)
            .padding(12.dp)
            .testTag("db-phone-column-filter-sheet"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                BasicText(
                    column ?: "Multi column filter",
                    style = ppmText(14f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
                )
                val subtitle = if (colSchema != null) {
                    buildString {
                        append(colSchema.type)
                        if (!colSchema.nullable) append(" · NOT NULL")
                        if (colSchema.fkTable != null) append(" · → ${colSchema.fkTable}.${colSchema.fkColumn ?: "id"}")
                    }
                } else {
                    "Every column of the table reads it"
                }
                BasicText(
                    subtitle,
                    style = ppmText(11f, lineHeight = 14f).copy(color = tokens.text3.color),
                )
            }
            SettingsButton(label = "×", onClick = { closeWithoutRefocus() })
        }

        // Filter Input + Picker button (⋮ or ⋯)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsTextInput(
                value = draft,
                onValueChange = { incoming ->
                    if (incoming.contains('\n')) {
                        val built = linesFilter(LinesMode.IS, incoming)
                        if (built.isNotEmpty()) draft = built
                    } else {
                        draft = incoming
                    }
                },
                placeholder = "Filter",
                mono = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("db-phone-filter-sheet-input"),
            )
            if (column != null && colKind != null) {
                val fkTable = colSchema?.fkTable
                if (fkTable != null) {
                    SettingsButton(
                        label = "⋯",
                        modifier = Modifier.testTag("db-phone-filter-sheet-lookup-btn"),
                        onClick = {
                            closeWithoutRefocus()
                            onOpenLookup(column, colKind, fkTable, colSchema.fkSchema, colSchema.fkColumn ?: "id")
                        },
                    )
                } else if (canChooseValues(colKind)) {
                    SettingsButton(
                        label = "⋮",
                        modifier = Modifier.testTag("db-phone-filter-sheet-choose-btn"),
                        onClick = {
                            closeWithoutRefocus()
                            onOpenChooseValues(column, colKind)
                        },
                    )
                }
            }
        }

        BasicText(
            statusText,
            style = ppmText(11f, lineHeight = 14f).copy(color = statusColor),
            modifier = Modifier.testTag("db-phone-filter-sheet-status"),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (column != null) {
                BasicText("SORT & COLUMN", style = ppmText(10f, FontWeight.SemiBold, lineHeight = 13f).copy(color = tokens.text3.color))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SettingsButton(
                        label = "Sort ascending",
                        modifier = Modifier.testTag("db-phone-sheet-sort-asc"),
                        onClick = {
                            closeWithoutRefocus()
                            onSortAction(column, SortMenuAction.SORT_ASC)
                        },
                    )
                    SettingsButton(
                        label = "Sort descending",
                        modifier = Modifier.testTag("db-phone-sheet-sort-desc"),
                        onClick = {
                            closeWithoutRefocus()
                            onSortAction(column, SortMenuAction.SORT_DESC)
                        },
                    )
                    if (sort.isNotEmpty()) {
                        SettingsButton(
                            label = "Add sort ↑",
                            onClick = {
                                closeWithoutRefocus()
                                onSortAction(column, SortMenuAction.ADD_ASC)
                            },
                        )
                        SettingsButton(
                            label = "Add sort ↓",
                            onClick = {
                                closeWithoutRefocus()
                                onSortAction(column, SortMenuAction.ADD_DESC)
                            },
                        )
                        SettingsButton(
                            label = "Clear sort",
                            onClick = {
                                closeWithoutRefocus()
                                onSortAction(column, SortMenuAction.CLEAR_SORT)
                            },
                        )
                    }
                    SettingsButton(
                        label = "Copy name",
                        onClick = {
                            closeWithoutRefocus()
                            onCopyColumnName(column)
                        },
                    )
                    SettingsButton(
                        label = "Hide column",
                        onClick = {
                            closeWithoutRefocus()
                            onHideColumn(column)
                        },
                    )
                }
            }

            BasicText("FILTER OPTIONS", style = ppmText(10f, FontWeight.SemiBold, lineHeight = 13f).copy(color = tokens.text3.color))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (entry in presetItems) {
                    SettingsButton(
                        label = entry.label,
                        modifier = Modifier.testTag("db-phone-sheet-funnel-${entry.label}"),
                        onClick = {
                            when (val act = entry.action) {
                                is FunnelAction.WriteText -> commitText(act.text)
                                is FunnelAction.OpenDialog -> {
                                    closeWithoutRefocus()
                                    onOpenDialog(column, act.request)
                                }
                            }
                        },
                    )
                }
            }
        }

        // Footer actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsButton(
                label = "Clear Filter",
                modifier = Modifier.testTag("db-phone-filter-clear-btn"),
                onClick = { commitText("") },
            )
            SettingsButton(
                label = "Apply",
                primary = true,
                enabled = readState !is FilterState.Bad,
                modifier = Modifier.testTag("db-phone-filter-apply-btn"),
                onClick = {
                    if (readState !is FilterState.Bad) {
                        commitText(draft)
                    }
                },
            )
        }
    }
}

/**
 * 9c.4 Phone Unified "Columns and filters" Bottom Sheet (`columns-sheet.tsx`).
 */
@Composable
fun PhoneColumnsAndFiltersSheet(
    tableName: String,
    columns: List<String>,
    hiddenColumns: Set<String>,
    schemaMap: Map<String, GridColumnSchema>,
    filters: GridFilters,
    filterableCols: List<FilterableColumn>,
    onToggleColumnVisibility: (String) -> Unit,
    onShowAllColumns: () -> Unit,
    onChangeFilters: (GridFilters) -> Unit,
    onOpenColumnFilterSheet: (String?) -> Unit,
    onClose: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val focusManager = LocalFocusManager.current
    var colSearch by remember { mutableStateOf("") }

    val fkReferences = remember(schemaMap) {
        schemaMap.values.filter { it.fkTable != null }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.panel2.color)
            .cssBorder(tokens.border.color, sides = CssSides.Top)
            .padding(12.dp)
            .testTag("db-phone-columns-and-filters-sheet"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                BasicText(
                    "Columns and filters",
                    style = ppmText(14f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
                )
                BasicText(
                    "$tableName · ${columns.size - hiddenColumns.size} of ${columns.size} columns shown",
                    style = ppmText(11f, lineHeight = 14f).copy(color = tokens.text3.color),
                )
            }
            SettingsButton(
                label = "Done",
                primary = true,
                modifier = Modifier.testTag("db-phone-cols-filters-done-btn"),
                onClick = {
                    focusManager.clearFocus()
                    onClose()
                },
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 1. Filters section
            BasicText("FILTERS", style = ppmText(10f, FontWeight.SemiBold, lineHeight = 13f).copy(color = tokens.text3.color))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(tokens.panel.color)
                    .clickable {
                        focusManager.clearFocus()
                        onClose()
                        onOpenColumnFilterSheet(null)
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .testTag("db-phone-sheet-multi-filter-row"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    "Multi column filter",
                    style = ppmText(12f, FontWeight.Medium, lineHeight = 15f).copy(color = tokens.text.color),
                )
                BasicText(
                    filters.multi?.text?.takeIf { it.isNotBlank() } ?: "Tap to add",
                    style = ppmText(11f, kind = FontKind.MONO, lineHeight = 14f).copy(
                        color = if (filters.multi?.text.isNullOrBlank()) tokens.text3.color else tokens.success.color,
                    ),
                )
            }

            for (c in filterableCols.filter { filters.columns.containsKey(it.name) }) {
                val ft = filters.columns[c.name] ?: continue
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(tokens.panel.color)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                focusManager.clearFocus()
                                onClose()
                                onOpenColumnFilterSheet(c.name)
                            },
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            c.name,
                            style = ppmText(12f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 15f).copy(color = tokens.text.color),
                        )
                        BasicText(
                            ft.text,
                            style = ppmText(11f, kind = FontKind.MONO, lineHeight = 14f).copy(
                                color = if (ft.off) tokens.text3.color else tokens.success.color,
                                textDecoration = if (ft.off) TextDecoration.LineThrough else TextDecoration.None,
                            ),
                            maxLines = 1,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        BasicText(
                            if (!ft.off) "☑" else "☐",
                            modifier = Modifier.clickable { onChangeFilters(withFilterOff(filters, c.name, !ft.off)) },
                            style = ppmText(12f, FontWeight.Bold, lineHeight = 15f).copy(
                                color = if (!ft.off) tokens.accent.color else tokens.text3.color,
                            ),
                        )
                        BasicText(
                            "×",
                            modifier = Modifier.clickable { onChangeFilters(withColumnFilter(filters, c.name, "")) },
                            style = ppmText(12f, FontWeight.Bold, lineHeight = 15f).copy(color = tokens.text3.color),
                        )
                    }
                }
            }

            // 2. Columns section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText("COLUMNS", style = ppmText(10f, FontWeight.SemiBold, lineHeight = 13f).copy(color = tokens.text3.color))
                SettingsButton(label = "Show all", onClick = onShowAllColumns)
            }
            SettingsTextInput(
                value = colSearch,
                onValueChange = { colSearch = it },
                placeholder = "Search columns (e.g. CA -> created_at)",
                modifier = Modifier.fillMaxWidth(),
            )
            for (col in columns.filter { columnNameMatches(colSearch, it) }) {
                val isVisible = col !in hiddenColumns
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleColumnVisibility(col) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        if (isVisible) "☑" else "☐",
                        style = ppmText(12f, FontWeight.Bold, lineHeight = 15f).copy(
                            color = if (isVisible) tokens.accent.color else tokens.text3.color,
                        ),
                    )
                    BasicText(
                        col,
                        style = ppmText(12f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text.color),
                    )
                }
            }

            // 3. References section
            if (fkReferences.isNotEmpty()) {
                BasicText("REFERENCES", style = ppmText(10f, FontWeight.SemiBold, lineHeight = 13f).copy(color = tokens.text3.color))
                for (ref in fkReferences) {
                    BasicText(
                        "🔗 ${ref.name} → ${ref.fkTable}.${ref.fkColumn ?: "id"}",
                        style = ppmText(11f, kind = FontKind.MONO, lineHeight = 14f).copy(color = tokens.accent.color),
                    )
                }
            }
        }
    }
}
