package com.hienlh.ppm.app.ui.database

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.SettingsButton
import com.hienlh.ppm.app.ui.SettingsTextInput
import com.hienlh.ppm.client.DbDriverMissingException
import com.hienlh.ppm.client.DbLoginCoordinator
import com.hienlh.ppm.client.DbLoginFailedException
import com.hienlh.ppm.client.DbLoginPromptInfo
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.client.dbChangesetApply
import com.hienlh.ppm.client.dbChangesetPreview
import com.hienlh.ppm.client.dbDownloadGridExportTicket
import com.hienlh.ppm.client.dbGridCellTicket
import com.hienlh.ppm.client.dbGridCount
import com.hienlh.ppm.client.dbGridExportTicket
import com.hienlh.ppm.client.dbGridPage
import com.hienlh.ppm.client.dbGridValues
import com.hienlh.ppm.client.dbInstallMissingDriver
import com.hienlh.ppm.client.dbLoginConnection
import com.hienlh.ppm.client.dbTableSchema
import com.hienlh.ppm.client.dbTableStructure
import com.hienlh.ppm.client.dbUpdateConnection
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.long
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.database.CHANGESET_MAX_OPERATIONS
import com.hienlh.ppm.state.database.CellChange
import com.hienlh.ppm.state.database.CellFileResult
import com.hienlh.ppm.state.database.ChangesetApplyResult
import com.hienlh.ppm.state.database.ChangesetFailure
import com.hienlh.ppm.state.database.ChangesetHistory
import com.hienlh.ppm.state.database.ChangesetPreview
import com.hienlh.ppm.state.database.CloneColumn
import com.hienlh.ppm.state.database.ColumnKind
import com.hienlh.ppm.state.database.CopyFormat
import com.hienlh.ppm.state.database.CopySqlTarget
import com.hienlh.ppm.state.database.DEFAULT_COPY_FORMAT
import com.hienlh.ppm.state.database.DbCellValue
import com.hienlh.ppm.state.database.DbGridPageData
import com.hienlh.ppm.state.database.DocumentColumn
import com.hienlh.ppm.state.database.EMPTY_CHANGESET
import com.hienlh.ppm.state.database.FETCH_ALL_CHUNK
import com.hienlh.ppm.state.database.FILTER_BAND_DP
import com.hienlh.ppm.state.database.FLOATING_PANEL_TAB_WIDTH_DP
import com.hienlh.ppm.state.database.FilterDialogRequest
import com.hienlh.ppm.state.database.FilterRequest
import com.hienlh.ppm.state.database.FilterText
import com.hienlh.ppm.state.database.FilterableColumn
import com.hienlh.ppm.state.database.FormParse
import com.hienlh.ppm.state.database.FunnelKind
import com.hienlh.ppm.state.database.GRID_EXPORT_FORMATS
import com.hienlh.ppm.state.database.GRID_PAGE_SIZE
import com.hienlh.ppm.state.database.GridChangesPayload
import com.hienlh.ppm.state.database.GridChangeset
import com.hienlh.ppm.state.database.GridColumnSchema
import com.hienlh.ppm.state.database.GridFilters
import com.hienlh.ppm.state.database.GridRect
import com.hienlh.ppm.state.database.GridReference
import com.hienlh.ppm.state.database.GridSelectionState
import com.hienlh.ppm.state.database.GridSortItem
import com.hienlh.ppm.state.database.LoadMoreDecision
import com.hienlh.ppm.state.database.LockColumn
import com.hienlh.ppm.state.database.NEW_ROW_PREFIX
import com.hienlh.ppm.state.database.NO_FILTERS
import com.hienlh.ppm.state.database.NewRow
import com.hienlh.ppm.state.database.ResultColumnMeta
import com.hienlh.ppm.state.database.RowCountState
import com.hienlh.ppm.state.database.RowCountView
import com.hienlh.ppm.state.database.SortMenuAction
import com.hienlh.ppm.state.database.SqlSourceRow
import com.hienlh.ppm.state.database.TITLE_BAND_DP
import com.hienlh.ppm.state.database.TITLE_BAND_TOUCH_DP
import com.hienlh.ppm.state.database.TableFirstPageCache
import com.hienlh.ppm.state.database.TableRef
import com.hienlh.ppm.state.database.TableReferences
import com.hienlh.ppm.state.database.TableScope
import com.hienlh.ppm.state.database.TitleIcon
import com.hienlh.ppm.state.database.addRows
import com.hienlh.ppm.state.database.applySortAction
import com.hienlh.ppm.state.database.buildChangesetRequestJson
import com.hienlh.ppm.state.database.buildGridCellTicketRequestJson
import com.hienlh.ppm.state.database.buildGridExportRequestJson
import com.hienlh.ppm.state.database.buildGridRequestJson
import com.hienlh.ppm.state.database.canChooseValues
import com.hienlh.ppm.state.database.cellFile
import com.hienlh.ppm.state.database.changedRowCount
import com.hienlh.ppm.state.database.classifyColumnType
import com.hienlh.ppm.state.database.cloneValues
import com.hienlh.ppm.state.database.collectCellData
import com.hienlh.ppm.state.database.EMPTY_CHANGESET
import com.hienlh.ppm.state.database.changedRowCount
import com.hienlh.ppm.state.database.columnFilterState
import com.hienlh.ppm.state.database.columnNameMatches
import com.hienlh.ppm.state.database.computeRowCountView
import com.hienlh.ppm.state.database.computeSelectionStats
import com.hienlh.ppm.state.database.decodeGridFiltersJson
import com.hienlh.ppm.state.database.deleteRows
import com.hienlh.ppm.state.database.descriptionColumn
import com.hienlh.ppm.state.database.detailKeyFilters
import com.hienlh.ppm.state.database.editCells
import com.hienlh.ppm.state.database.encodeGridFiltersJson
import com.hienlh.ppm.state.database.filterRequest
import com.hienlh.ppm.state.database.filterSyntax
import com.hienlh.ppm.state.database.filterableColumns
import com.hienlh.ppm.state.database.formEditText
import com.hienlh.ppm.state.database.formFieldKindFor
import com.hienlh.ppm.state.database.formatCellCopyText
import com.hienlh.ppm.state.database.formatCellDisplayText
import com.hienlh.ppm.state.database.formatCopy
import com.hienlh.ppm.state.database.gridExportFileName
import com.hienlh.ppm.state.database.hasFilters
import com.hienlh.ppm.state.database.hasReferences
import com.hienlh.ppm.state.database.isCellLocked
import com.hienlh.ppm.state.database.isEmptyChangeset
import com.hienlh.ppm.state.database.isNewRowId
import com.hienlh.ppm.state.database.loadMoreDecision
import com.hienlh.ppm.state.database.lookupRowsRequest
import com.hienlh.ppm.state.database.lookupSearch
import com.hienlh.ppm.state.database.parseChangesetApplyResult
import com.hienlh.ppm.state.database.parseChangesetFailure
import com.hienlh.ppm.state.database.parseChangesetPreview
import com.hienlh.ppm.state.database.parseDbCellValue
import com.hienlh.ppm.state.database.parseFormText
import com.hienlh.ppm.state.database.parseGridColumnsSchema
import com.hienlh.ppm.state.database.parseGridResponse
import com.hienlh.ppm.state.database.parseTsvPasteChanges
import com.hienlh.ppm.state.database.recordChange
import com.hienlh.ppm.state.database.redoChange
import com.hienlh.ppm.state.database.referencedRowFilters
import com.hienlh.ppm.state.database.refusedFilters
import com.hienlh.ppm.state.database.reloadKeepLoadedLimit
import com.hienlh.ppm.state.database.revertRows
import com.hienlh.ppm.state.database.rowDocumentText
import com.hienlh.ppm.state.database.rowIdForRowKey
import com.hienlh.ppm.state.database.rowMarkerWidthPx
import com.hienlh.ppm.state.database.savedText
import com.hienlh.ppm.state.database.selectedBlock
import com.hienlh.ppm.state.database.selectedRowIndices
import com.hienlh.ppm.state.database.selectionHasCell
import com.hienlh.ppm.state.database.setUnsavedGridChangeset
import com.hienlh.ppm.state.database.setUnsavedGridRows
import com.hienlh.ppm.state.database.shouldBypassSaveDialog
import com.hienlh.ppm.state.database.sortInfoForColumn
import com.hienlh.ppm.state.database.tableReferences
import com.hienlh.ppm.state.database.unsavedGridChangeset
import com.hienlh.ppm.state.database.titleIconFor
import com.hienlh.ppm.state.database.toFunnelKind
import com.hienlh.ppm.state.database.toGridChanges
import com.hienlh.ppm.state.database.undoChange
import com.hienlh.ppm.state.database.valuesRequest
import com.hienlh.ppm.state.database.withColumnFilter
import com.hienlh.ppm.state.database.withMultiFilter
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val COPY_FORMAT_PREF_FILE = "ppm_db_grid_prefs"
private const val COPY_FORMAT_PREF_KEY = "ppm-db-copy-format"
private const val FETCH_ALL_DONT_ASK_KEY = "ppm-db-fetch-all-dont-ask"
private const val SAVE_DONT_ASK_KEY = "ppm-db-save-dont-ask"
private const val DEFAULT_COL_WIDTH_DP = 150
private const val MIN_COL_WIDTH_DP = 64
private const val GRID_ROW_HEIGHT_DP = 28

/**
 * Flattened opaque palette for the 2D Canvas data grid (`glide-grid-theme.ts`),
 * including edit highlight colors ready for Phase 09d.
 */
data class GridThemeColors(
    val bgCell: Color,
    val bgCellEven: Color,
    val bgHeader: Color,
    val bgHeaderSelected: Color,
    val bgSelectedCell: Color,
    val bgModifiedCell: Color,
    val bgInsertedRow: Color,
    val bgDeletedRow: Color,
    val borderColor: Color,
    val textDark: Color,
    val textMedium: Color,
    val textLight: Color,
    val textNull: Color,
    val accentColor: Color,
    val accentFg: Color,
    val deletedStrikeColor: Color,
)

@Composable
fun rememberGridThemeColors(): GridThemeColors {
    val tokens = LocalPpmTokens.current
    return remember(tokens) {
        GridThemeColors(
            bgCell = tokens.bgSolid.color,
            bgCellEven = tokens.panel.opacity(0.35).color,
            bgHeader = tokens.panel2.color,
            bgHeaderSelected = tokens.accent.opacity(0.22).color,
            bgSelectedCell = tokens.accent.opacity(0.18).color,
            bgModifiedCell = Color(0x33F59E0B), // amber/yellow tint for edited cells (09d)
            bgInsertedRow = Color(0x2610B981),  // green tint for new rows (09d)
            bgDeletedRow = Color(0x26EF4444),   // red tint for deleted rows (09d)
            borderColor = tokens.border.color,
            textDark = tokens.text.color,
            textMedium = tokens.text2.color,
            textLight = tokens.text3.color,
            textNull = tokens.text3.opacity(0.65).color,
            accentColor = tokens.accent.color,
            accentFg = tokens.text.color,
            deletedStrikeColor = tokens.error.color,
        )
    }
}

/**
 * Row/cell edit status provider hook (used by Phase 09d changeset editor).
 */
data class GridRowEditStatus(
    val isInserted: Boolean = false,
    val isDeleted: Boolean = false,
    val modifiedColumns: Set<String> = emptySet(),
)

/**
 * 9b.1 Reusable 2D Virtualized Canvas Data Grid (`ui/grid`).
 *
 * - Only visible rows are composed via `LazyColumn`.
 * - Each visible row draws all of its cells inside a single `Canvas` + `TextMeasurer` (with LRU layout cache),
 *   **never** creating one Composable per cell.
 * - Fixed top column header band and fixed left row-number gutter.
 */
@Composable
fun NativeDataGrid(
    columns: List<String>,
    rows: List<List<DbCellValue>>,
    schemaMap: Map<String, GridColumnSchema>,
    hiddenColumns: Set<String> = emptySet(),
    sort: List<GridSortItem> = emptyList(),
    selection: GridSelectionState = GridSelectionState(),
    columnWidthsDp: Map<String, Int> = emptyMap(),
    hasMore: Boolean = false,
    loadingMore: Boolean = false,
    newRowsCount: Int = 0,
    rowEditStatus: (Int) -> GridRowEditStatus = { GridRowEditStatus() },
    showFilterRow: Boolean = true,
    filters: GridFilters = NO_FILTERS,
    filterableCols: List<FilterableColumn> = emptyList(),
    serverFilterErrors: Map<String, String> = emptyMap(),
    onChangeFilters: (GridFilters) -> Unit = {},
    onOpenChooseValues: (String, ColumnKind) -> Unit = { _, _ -> },
    onOpenLookup: (String, ColumnKind, String, String?, String) -> Unit = { _, _, _, _, _ -> },
    onOpenFunnelFor: (String?) -> Unit = {},
    onPhoneOpenColumnSheet: ((String) -> Unit)? = null,
    onSelectionChange: (GridSelectionState) -> Unit = {},
    onSortAction: (String, SortMenuAction) -> Unit = { _, _ -> },
    onCopyColumnName: (String) -> Unit = {},
    onResizeColumn: (String, Int) -> Unit = { _, _ -> },
    onLoadMore: () -> Unit = {},
    onCellTap: (Int, Int) -> Unit = { _, _ -> },
    onCellLongPressPhone: (Int, Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val density = LocalDensity.current
    val gridColors = rememberGridThemeColors()
    val textMeasurer = rememberTextMeasurer(cacheSize = 512)

    val visibleColIndices = remember(columns, hiddenColumns) {
        columns.indices.filter { columns[it] !in hiddenColumns }
    }
    val visibleColumns = remember(columns, visibleColIndices) {
        visibleColIndices.map { columns[it] }
    }

    val markerWidthDp = remember(rows.size) { rowMarkerWidthPx(rows.size).dp }
    val headerHeightDp = if (mobile) TITLE_BAND_TOUCH_DP.dp else TITLE_BAND_DP.dp
    val rowHeightDp = GRID_ROW_HEIGHT_DP.dp

    val horizontalScroll = rememberScrollState()
    val listState = rememberLazyListState()

    // Trigger loadMore when the last loaded row scrolls into view
    val lastVisibleRow by remember {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }
    }
    LaunchedEffect(lastVisibleRow, rows.size, hasMore, loadingMore, newRowsCount) {
        val decision = loadMoreDecision(
            lastVisibleRow = lastVisibleRow,
            loadedRows = rows.size,
            hasMore = hasMore,
            busy = loadingMore,
            newRows = newRowsCount,
        )
        if (decision == LoadMoreDecision.LOAD) {
            onLoadMore()
        }
    }

    var openHeaderMenuCol by remember { mutableStateOf<String?>(null) }

    val cellTextStyle = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f)
    val rowNumStyle = ppmText(11f, kind = FontKind.MONO, lineHeight = 14f).copy(color = gridColors.textLight)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(gridColors.bgCell)
            .testTag("native-data-grid"),
    ) {
        // 1. Fixed Top Header Band (Corner + Horizontally Scrollable Column Headers)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(headerHeightDp)
                .background(gridColors.bgHeader)
                .cssBorder(gridColors.borderColor, sides = CssSides.Bottom),
        ) {
            // Top-left corner cell (click = select all)
            Box(
                modifier = Modifier
                    .width(markerWidthDp)
                    .fillMaxHeight()
                    .background(gridColors.bgHeader)
                    .cssBorder(gridColors.borderColor, sides = CssSides.End)
                    .clickable {
                        if (visibleColumns.isNotEmpty() && rows.isNotEmpty()) {
                            onSelectionChange(
                                GridSelectionState(
                                    ranges = listOf(GridRect(0, 0, visibleColumns.size, rows.size)),
                                    activeCell = 0 to 0,
                                ),
                            )
                        }
                    }
                    .testTag("grid-corner-select-all"),
                contentAlignment = Alignment.Center,
            ) {
                BasicText("#", style = rowNumStyle)
            }

            // Column headers strip
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .horizontalScroll(horizontalScroll),
            ) {
                visibleColumns.forEachIndexed { visIdx, colName ->
                    val colWidthDp = (columnWidthsDp[colName] ?: DEFAULT_COL_WIDTH_DP).coerceAtLeast(MIN_COL_WIDTH_DP)
                    val colSchema = schemaMap[colName]
                    val notNull = colSchema?.nullable == false
                    val icon = titleIconFor(
                        pk = colSchema?.pk == true,
                        hasFk = colSchema?.fkTable != null,
                        autoIncrement = colSchema?.autoIncrement == true,
                    )
                    val sortInfo = sortInfoForColumn(sort, colName)
                    val isColSelected = visIdx in selection.selectedColumns

                    Box(
                        modifier = Modifier
                            .width(colWidthDp.dp)
                            .fillMaxHeight()
                            .background(if (isColSelected) gridColors.bgHeaderSelected else gridColors.bgHeader)
                            .cssBorder(gridColors.borderColor, sides = CssSides.End)
                            .clickable {
                                if (mobile && onPhoneOpenColumnSheet != null) {
                                    onPhoneOpenColumnSheet(colName)
                                } else {
                                    onSelectionChange(
                                        selection.copy(
                                            selectedColumns = setOf(visIdx),
                                            selectedRows = emptySet(),
                                            ranges = emptyList(),
                                            activeCell = visIdx to 0,
                                        ),
                                    )
                                }
                            }
                            .testTag("grid-col-header-$colName"),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 6.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (icon != null) {
                                BasicText(
                                    when (icon) {
                                        TitleIcon.HEADER_AUTO -> "#"
                                        TitleIcon.HEADER_KEY -> "🔑"
                                        TitleIcon.HEADER_FK -> "🔗"
                                    },
                                    style = ppmText(10f, lineHeight = 13f).copy(color = gridColors.accentColor),
                                )
                            }
                            BasicText(
                                colName,
                                style = ppmText(
                                    12f,
                                    if (notNull) FontWeight.Bold else FontWeight.Medium,
                                    lineHeight = 15f,
                                ).copy(color = if (notNull) gridColors.textDark else gridColors.textMedium),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (sortInfo != null) {
                                val arrow = if (sortInfo.dir == "ASC") "↑" else "↓"
                                val idxSuffix = sortInfo.index?.toString().orEmpty()
                                BasicText(
                                    "$arrow$idxSuffix",
                                    style = ppmText(11f, FontWeight.Bold, FontKind.MONO, lineHeight = 14f)
                                        .copy(color = gridColors.accentColor),
                                    modifier = Modifier.testTag("grid-col-sort-indicator-$colName"),
                                )
                            } else if (!colSchema?.type.isNullOrBlank()) {
                                BasicText(
                                    colSchema?.type.orEmpty(),
                                    style = ppmText(10f, kind = FontKind.MONO, lineHeight = 13f)
                                        .copy(color = gridColors.textLight),
                                    maxLines = 1,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable {
                                        if (mobile && onPhoneOpenColumnSheet != null) {
                                            onPhoneOpenColumnSheet(colName)
                                        } else {
                                            openHeaderMenuCol = if (openHeaderMenuCol == colName) null else colName
                                        }
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                    .testTag("grid-col-menu-btn-$colName"),
                            ) {
                                BasicText(
                                    "⌄",
                                    style = ppmText(11f, FontWeight.Bold, lineHeight = 13f)
                                        .copy(color = gridColors.textMedium),
                                )
                            }
                        }

                        // Right-edge column width resize handle
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .width(6.dp)
                                .fillMaxHeight()
                                .pointerInput(colName, colWidthDp) {
                                    detectHorizontalDragGestures { change, dragAmount ->
                                        change.consume()
                                        val deltaDp = (dragAmount / density.density).toInt()
                                        if (deltaDp != 0) {
                                            val nextWidth = (colWidthDp + deltaDp).coerceIn(MIN_COL_WIDTH_DP, 600)
                                            onResizeColumn(colName, nextWidth)
                                        }
                                    }
                                },
                        )
                    }
                }
            }
        }

        // Column ⌄ Sort Dropdown Bar when open
        val menuCol = openHeaderMenuCol
        if (menuCol != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tokens.panel.color)
                    .cssBorder(gridColors.borderColor, sides = CssSides.Bottom)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("grid-col-sort-menu-$menuCol"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    "Column: $menuCol",
                    style = ppmText(11f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 14f).copy(color = tokens.text.color),
                )
                SettingsButton(
                    label = "Sort ascending",
                    modifier = Modifier.testTag("grid-sort-asc-$menuCol"),
                    onClick = {
                        onSortAction(menuCol, SortMenuAction.SORT_ASC)
                        openHeaderMenuCol = null
                    },
                )
                SettingsButton(
                    label = "Sort descending",
                    modifier = Modifier.testTag("grid-sort-desc-$menuCol"),
                    onClick = {
                        onSortAction(menuCol, SortMenuAction.SORT_DESC)
                        openHeaderMenuCol = null
                    },
                )
                SettingsButton(
                    label = "Add to sort ↑",
                    modifier = Modifier.testTag("grid-sort-add-asc-$menuCol"),
                    onClick = {
                        onSortAction(menuCol, SortMenuAction.ADD_ASC)
                        openHeaderMenuCol = null
                    },
                )
                SettingsButton(
                    label = "Add to sort ↓",
                    modifier = Modifier.testTag("grid-sort-add-desc-$menuCol"),
                    onClick = {
                        onSortAction(menuCol, SortMenuAction.ADD_DESC)
                        openHeaderMenuCol = null
                    },
                )
                SettingsButton(
                    label = "Clear sort",
                    modifier = Modifier.testTag("grid-sort-clear-$menuCol"),
                    onClick = {
                        onSortAction(menuCol, SortMenuAction.CLEAR_SORT)
                        openHeaderMenuCol = null
                    },
                )
                SettingsButton(
                    label = "Copy column name",
                    modifier = Modifier.testTag("grid-copy-col-name-$menuCol"),
                    onClick = {
                        onCopyColumnName(menuCol)
                        openHeaderMenuCol = null
                    },
                )
            }
        }

        // 1b. Tablet DBGate Filter Row (scrolls horizontally in lockstep with column headers and grid cells)
        if (!mobile && showFilterRow && visibleColumns.isNotEmpty()) {
            val kindByCol = remember(filterableCols) {
                filterableCols.associate { it.name to it.kind }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(FILTER_BAND_DP.dp)
                    .background(gridColors.bgHeader)
                    .cssBorder(gridColors.borderColor, sides = CssSides.Bottom)
                    .testTag("db-tablet-filter-row"),
            ) {
                // Left gutter filter icon / clear-all button
                Box(
                    modifier = Modifier
                        .width(markerWidthDp)
                        .fillMaxHeight()
                        .background(gridColors.bgHeader)
                        .cssBorder(gridColors.borderColor, sides = CssSides.End)
                        .clickable(enabled = hasFilters(filters)) {
                            onChangeFilters(NO_FILTERS)
                        }
                        .testTag("grid-filter-row-corner"),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        if (hasFilters(filters)) "▽×" else "▽",
                        style = rowNumStyle.copy(
                            color = if (hasFilters(filters)) gridColors.accentColor else gridColors.textLight,
                        ),
                    )
                }

                // Horizontally scrollable filter cells aligned 1:1 with visible columns
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .horizontalScroll(horizontalScroll),
                ) {
                    visibleColumns.forEach { colName ->
                        val colWidthDp = (columnWidthsDp[colName] ?: DEFAULT_COL_WIDTH_DP).coerceAtLeast(MIN_COL_WIDTH_DP)
                        val colSchema = schemaMap[colName]
                        val colKind = kindByCol[colName] ?: ColumnKind.OTHER
                        val fkTable = colSchema?.fkTable
                        val fkCol = colSchema?.fkColumn ?: "id"
                        val currentFilter = filters.columns[colName]

                        Box(
                            modifier = Modifier
                                .width(colWidthDp.dp)
                                .fillMaxHeight()
                                .background(gridColors.bgHeader)
                                .cssBorder(gridColors.borderColor, sides = CssSides.End)
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            FilterCellBox(
                                tagSuffix = colName,
                                value = currentFilter?.text.orEmpty(),
                                off = currentFilter?.off == true,
                                serverError = serverFilterErrors[colName],
                                read = { txt -> columnFilterState(FilterText(txt), colKind) },
                                onCommit = { txt -> onChangeFilters(withColumnFilter(filters, colName, txt)) },
                                onOpenChooseValues = if (fkTable == null && canChooseValues(colKind)) {
                                    { onOpenChooseValues(colName, colKind) }
                                } else {
                                    null
                                },
                                onOpenLookup = if (fkTable != null) {
                                    { onOpenLookup(colName, colKind, fkTable, colSchema.fkSchema, fkCol) }
                                } else {
                                    null
                                },
                                onToggleFunnel = { onOpenFunnelFor(colName) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }

        // 2. Virtualized Rows Body (Fixed Left Row Gutter + Single Canvas per Row)
        val colWidthsPx = remember(visibleColumns, columnWidthsDp, density.density) {
            visibleColumns.map { colName ->
                val wDp = (columnWidthsDp[colName] ?: DEFAULT_COL_WIDTH_DP).coerceAtLeast(MIN_COL_WIDTH_DP)
                wDp * density.density
            }
        }
        val totalWidthDp = remember(visibleColumns, columnWidthsDp) {
            visibleColumns.sumOf { colName ->
                (columnWidthsDp[colName] ?: DEFAULT_COL_WIDTH_DP).coerceAtLeast(MIN_COL_WIDTH_DP)
            }.coerceAtLeast(1).dp
        }

        if (visibleColumns.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("grid-all-columns-hidden"),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    "Every column is hidden.",
                    style = ppmText(12f, lineHeight = 16f).copy(color = gridColors.textLight),
                )
            }
        } else if (rows.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("grid-no-rows-loaded"),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    "No rows loaded",
                    style = ppmText(12f, lineHeight = 16f).copy(color = gridColors.textLight),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("grid-rows-lazy-column"),
            ) {
                items(
                    count = rows.size,
                    key = { it },
                ) { rowIndex ->
                    val rowValues = rows[rowIndex]
                    val editStatus = rowEditStatus(rowIndex)
                    val isRowSelected = rowIndex in selection.selectedRows

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(rowHeightDp),
                    ) {
                        // Fixed Row Number Gutter
                        Box(
                            modifier = Modifier
                                .width(markerWidthDp)
                                .fillMaxHeight()
                                .background(
                                    if (isRowSelected) gridColors.bgHeaderSelected else gridColors.bgHeader,
                                )
                                .cssBorder(gridColors.borderColor, sides = CssSides.End + CssSides.Bottom)
                                .clickable {
                                    onSelectionChange(
                                        selection.copy(
                                            selectedRows = setOf(rowIndex),
                                            selectedColumns = emptySet(),
                                            ranges = emptyList(),
                                            activeCell = 0 to rowIndex,
                                        ),
                                    )
                                }
                                .testTag("grid-row-marker-$rowIndex"),
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText((rowIndex + 1).toString(), style = rowNumStyle)
                        }

                        // Row Cells Canvas (draws all cells of this row on one Canvas)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .horizontalScroll(horizontalScroll),
                        ) {
                            Canvas(
                                modifier = Modifier
                                    .width(totalWidthDp)
                                    .fillMaxHeight()
                                    .pointerInput(rowIndex, colWidthsPx, mobile) {
                                        var longPressFired = false
                                        detectTapGestures(
                                            onPress = {
                                                longPressFired = false
                                                tryAwaitRelease()
                                            },
                                            onLongPress = { offset ->
                                                if (mobile) {
                                                    longPressFired = true
                                                    val colIdx = hitTestColumnIndex(offset.x, colWidthsPx)
                                                    if (colIdx >= 0) {
                                                        onCellLongPressPhone(colIdx, rowIndex)
                                                    }
                                                }
                                            },
                                            onTap = { offset ->
                                                // Releasing finger after long-press menu opens must NOT count as a tap
                                                if (longPressFired) return@detectTapGestures
                                                val colIdx = hitTestColumnIndex(offset.x, colWidthsPx)
                                                if (colIdx >= 0) {
                                                    onSelectionChange(
                                                        selection.copy(
                                                            activeCell = colIdx to rowIndex,
                                                            ranges = listOf(GridRect(colIdx, rowIndex, 1, 1)),
                                                            selectedColumns = emptySet(),
                                                            selectedRows = emptySet(),
                                                        ),
                                                    )
                                                    onCellTap(colIdx, rowIndex)
                                                }
                                            },
                                        )
                                    }
                                    .testTag("grid-row-canvas-$rowIndex"),
                            ) {
                                val rowH = size.height
                                val baseBg = when {
                                    editStatus.isDeleted -> gridColors.bgDeletedRow
                                    editStatus.isInserted -> gridColors.bgInsertedRow
                                    rowIndex % 2 == 1 -> gridColors.bgCellEven
                                    else -> gridColors.bgCell
                                }
                                drawRect(color = baseBg, size = size)

                                var xOffset = 0f
                                visibleColIndices.forEachIndexed { visIdx, rawColIdx ->
                                    val colW = colWidthsPx.getOrElse(visIdx) { DEFAULT_COL_WIDTH_DP * density.density }
                                    val colName = columns[rawColIdx]
                                    val cellVal = rowValues.getOrNull(rawColIdx) ?: DbCellValue.Null
                                    val cellSelected = selectionHasCell(selection, visIdx, rowIndex)
                                    val cellModified = colName in editStatus.modifiedColumns

                                    if (cellSelected) {
                                        drawRect(
                                            color = gridColors.bgSelectedCell,
                                            topLeft = Offset(xOffset, 0f),
                                            size = Size(colW, rowH),
                                        )
                                    } else if (cellModified) {
                                        drawRect(
                                            color = gridColors.bgModifiedCell,
                                            topLeft = Offset(xOffset, 0f),
                                            size = Size(colW, rowH),
                                        )
                                    }

                                    // Cell text (truncated to 200 chars as on web)
                                    val displayStr = formatCellDisplayText(cellVal, truncateForGrid = true)
                                    val textColor = when (cellVal) {
                                        DbCellValue.Null, DbCellValue.NoField -> gridColors.textNull
                                        is DbCellValue.Binary -> gridColors.accentColor
                                        else -> gridColors.textDark
                                    }
                                    val padPx = 6f * density.density
                                    val maxTextWidthPx = (colW - padPx * 2).toInt().coerceAtLeast(0)
                                    if (maxTextWidthPx > 4 && displayStr.isNotEmpty()) {
                                        clipRect(
                                            left = xOffset + padPx,
                                            top = 0f,
                                            right = xOffset + colW - padPx,
                                            bottom = rowH,
                                        ) {
                                            val layoutResult: TextLayoutResult = textMeasurer.measure(
                                                text = displayStr.replace('\n', '↵').replace('\r', ' '),
                                                style = cellTextStyle.copy(color = textColor),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                constraints = Constraints(maxWidth = maxTextWidthPx),
                                            )
                                            val textY = ((rowH - layoutResult.size.height) / 2f).coerceAtLeast(0f)
                                            drawText(
                                                textLayoutResult = layoutResult,
                                                topLeft = Offset(xOffset + padPx, textY),
                                            )
                                        }
                                    }

                                    if (editStatus.isDeleted) {
                                        drawLine(
                                            color = gridColors.deletedStrikeColor,
                                            start = Offset(xOffset + 4f, rowH / 2f),
                                            end = Offset(xOffset + colW - 4f, rowH / 2f),
                                            strokeWidth = 1.5f,
                                        )
                                    }

                                    // Right border of cell
                                    drawLine(
                                        color = gridColors.borderColor,
                                        start = Offset(xOffset + colW, 0f),
                                        end = Offset(xOffset + colW, rowH),
                                        strokeWidth = 1f,
                                    )
                                    xOffset += colW
                                }

                                // Bottom border of row
                                drawLine(
                                    color = gridColors.borderColor,
                                    start = Offset(0f, rowH - 1f),
                                    end = Offset(size.width, rowH - 1f),
                                    strokeWidth = 1f,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun hitTestColumnIndex(x: Float, colWidthsPx: List<Float>): Int {
    if (x < 0f) return -1
    var acc = 0f
    colWidthsPx.forEachIndexed { idx, w ->
        if (x >= acc && x < acc + w) return idx
        acc += w
    }
    return -1
}

// ── 9b.2–9b.5 Database Table Tab (`table-tab.tsx`) ──────────────────────────

@Composable
fun DatabaseTableTabBody(
    model: AppModel,
    tab: WorkspaceTab,
) {
    val api = model.api
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val meta = tab.metadata
    val connId = meta["connectionId"]?.int?.toString()
        ?: meta["connectionId"]?.string
        ?: "file"
    val connName = meta["connectionName"]?.string ?: "Database"
    val database = meta["database"]?.string
    val tableName = meta["tableName"]?.string ?: tab.title.ifBlank { "table" }
    val schemaName = meta["schema"]?.string.orEmpty()
    val filePath = meta["filePath"]?.string
    val project = meta["project"]?.string
    val dbType = meta["dbType"]?.string ?: "postgres"

    val cacheDbKey = "$connId:${database.orEmpty()}:${filePath.orEmpty()}"
    val cachedFirst = remember(cacheDbKey, schemaName, tableName) {
        TableFirstPageCache.read(cacheDbKey, schemaName, tableName)
    }

    var pageData by remember(cacheDbKey, schemaName, tableName) {
        mutableStateOf(cachedFirst?.data)
    }
    var schemaList by remember(cacheDbKey, schemaName, tableName) {
        mutableStateOf(cachedFirst?.schema.orEmpty())
    }
    var structureJson by remember(cacheDbKey, schemaName, tableName) {
        mutableStateOf<JsonObject?>(null)
    }
    var rowCountState by remember { mutableStateOf<RowCountState?>(null) }
    var sort by remember { mutableStateOf<List<GridSortItem>>(emptyList()) }
    var filters by remember(cacheDbKey, schemaName, tableName) {
        mutableStateOf(decodeGridFiltersJson(meta["filters"]?.string))
    }
    var serverFilterErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(cachedFirst == null) }
    var loadingMore by remember { mutableStateOf(false) }
    var fetchingAllCount by remember { mutableStateOf<Int?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var missingDriverId by remember { mutableStateOf<String?>(null) }
    var readonlyEnabled by remember { mutableStateOf(meta["readonly"]?.bool ?: true) }
    var autoRefreshSec by remember { mutableIntStateOf(0) }
    val initialChangeset = remember(tab.id) {
        unsavedGridChangeset(tab.id)
    }

    val schemaMapForBody = remember(schemaList) { schemaList.associateBy { it.name } }
    val columnNames = pageData?.columnKeys.orEmpty()
    val filterableCols = remember(columnNames, schemaList, filters.columns.keys, dbType) {
        val names = when {
            columnNames.isNotEmpty() -> columnNames
            schemaList.isNotEmpty() -> schemaList.map { it.name }
            else -> filters.columns.keys.toList()
        }
        val schemaForFilter = names.map { col ->
            schemaMapForBody[col] ?: GridColumnSchema(name = col, type = "")
        }
        filterableColumns(schemaForFilter, dbType)
    }
    val activeFilterReq = remember(filters, filterableCols) {
        filterRequest(filters, filterableCols)
    }

    // 428 Login Coordinator
    var activeLoginPrompt by remember { mutableStateOf<DbLoginPromptInfo?>(null) }
    var activeLoginDeferred by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var loginErrorDetails by remember { mutableStateOf<String?>(null) }
    val loginCoordinator = remember {
        DbLoginCoordinator { prompt ->
            val deferred = CompletableDeferred<Boolean>()
            loginError = null
            loginErrorDetails = null
            activeLoginDeferred = deferred
            activeLoginPrompt = prompt
            deferred.await()
        }
    }

    // Sequence guard so stale responses for an older sort/filter view are dropped
    var fetchSeq by remember { mutableIntStateOf(0) }

    suspend fun loadFirstPage(keepLoaded: Boolean = false, withStructure: Boolean = false, silent: Boolean = false) {
        val client = api ?: return
        val seq = ++fetchSeq
        if (!silent) loading = true
        val loadedCount = pageData?.rows?.size ?: 0
        val limit = if (keepLoaded) reloadKeepLoadedLimit(loadedCount) else GRID_PAGE_SIZE
        val countKey = "$tableName:$schemaName"
        val reqJson = buildGridRequestJson(
            table = tableName,
            schema = schemaName.ifBlank { null },
            sort = sort,
            filters = activeFilterReq.toFiltersJsonArray(),
            anyColumn = activeFilterReq.toAnyColumnJsonArray(),
            offset = 0,
            limit = limit,
        )
        runCatching {
            val gridObj = client.dbGridPage(
                connectionId = connId,
                body = reqJson,
                database = database,
                path = filePath,
                project = project,
                loginCoordinator = loginCoordinator,
            )
            val colsArr = if (withStructure || schemaList.isEmpty()) {
                client.dbTableSchema(
                    connectionId = connId,
                    table = tableName,
                    schema = schemaName.ifBlank { null },
                    database = database,
                    path = filePath,
                    project = project,
                    loginCoordinator = loginCoordinator,
                )
            } else {
                null
            }
            val structObj = if (withStructure || structureJson == null) {
                runCatching {
                    client.dbTableStructure(
                        connectionId = connId,
                        table = tableName,
                        schema = schemaName.ifBlank { null },
                        database = database,
                        path = filePath,
                        project = project,
                        loginCoordinator = loginCoordinator,
                    )
                }.getOrNull()
            } else {
                null
            }
            withContext(Dispatchers.Default) {
                val parsed = parseGridResponse(gridObj, countKey = countKey)
                val parsedCols = colsArr?.let(::parseGridColumnsSchema)
                Triple(parsed, parsedCols, structObj)
            }
        }.onSuccess { (parsed, parsedCols, structObj) ->
            if (seq != fetchSeq) return
            missingDriverId = null
            errorMessage = null
            serverFilterErrors = emptyMap()
            pageData = parsed
            val nextCols = parsedCols ?: schemaList
            if (parsedCols != null) schemaList = nextCols
            if (structObj != null) structureJson = structObj
            if (sort.isEmpty() && !hasFilters(filters) && limit == GRID_PAGE_SIZE && nextCols.isNotEmpty()) {
                TableFirstPageCache.write(cacheDbKey, schemaName, tableName, parsed, nextCols)
            }
            if (!parsed.hasMore) {
                rowCountState = RowCountState(key = countKey, count = parsed.rows.size.toLong(), pending = false)
            } else {
                rowCountState = RowCountState(key = countKey, pending = true)
                scope.launch {
                    runCatching {
                        client.dbGridCount(
                            connectionId = connId,
                            body = reqJson,
                            exact = false,
                            database = database,
                            path = filePath,
                            project = project,
                            loginCoordinator = loginCoordinator,
                        )
                    }.onSuccess { cntObj ->
                        if (seq == fetchSeq) {
                            rowCountState = RowCountState(
                                key = countKey,
                                count = cntObj["count"].long,
                                estimate = cntObj["estimate"].long,
                                pending = false,
                                timedOut = cntObj["timedOut"].bool == true,
                            )
                        }
                    }.onFailure { err ->
                        if (seq == fetchSeq) {
                            rowCountState = RowCountState(
                                key = countKey,
                                pending = false,
                                failed = err.message ?: "Count failed",
                            )
                        }
                    }
                }
            }
        }.onFailure { err ->
            if (seq != fetchSeq || silent) return@onFailure
            if (err is DbDriverMissingException) {
                missingDriverId = err.driverId
            }
            if (err is PpmError.Server) {
                val blamed = refusedFilters(activeFilterReq, err.status, err.message ?: "Filter refused").orEmpty()
                if (blamed.isNotEmpty()) {
                    serverFilterErrors = blamed
                }
            }
            errorMessage = err.message ?: "Failed to load table data"
        }
        if (seq == fetchSeq) loading = false
    }

    var initialLoadCompleted by remember { mutableStateOf(false) }

    LaunchedEffect(api, connId, database, tableName, schemaName) {
        if (api != null) {
            loadFirstPage(withStructure = true)
            initialLoadCompleted = true
        }
    }

    LaunchedEffect(activeFilterReq) {
        if (initialLoadCompleted && api != null) {
            loadFirstPage()
        }
    }

    // Silent auto-refresh timer (1–60 s)
    LaunchedEffect(autoRefreshSec, api, connId, tableName) {
        if (autoRefreshSec > 0 && api != null) {
            while (true) {
                delay(autoRefreshSec * 1_000L)
                loadFirstPage(keepLoaded = true, silent = true)
            }
        }
    }

    // SAF Export launcher: pick file destination FIRST, then request single-use 30 s ticket & download via OkHttp
    var pendingExportFormat by remember { mutableStateOf<String?>(null) }
    var pendingExportColumns by remember { mutableStateOf<List<String>>(emptyList()) }
    var exportStatusMessage by remember { mutableStateOf<String?>(null) }

    val exportDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri: Uri? ->
        val formatId = pendingExportFormat
        val cols = pendingExportColumns
        pendingExportFormat = null
        if (uri != null && formatId != null && api != null) {
            scope.launch {
                exportStatusMessage = "Exporting ${gridExportFileName(tableName, formatId)}…"
                runCatching {
                    val reqBody = buildGridExportRequestJson(
                        table = tableName,
                        schema = schemaName.ifBlank { null },
                        columns = cols,
                        formatId = formatId,
                        sort = sort,
                        filters = activeFilterReq.toFiltersJsonArray(),
                        anyColumn = activeFilterReq.toAnyColumnJsonArray(),
                    )
                    val ticketObj = api.dbGridExportTicket(
                        connectionId = connId,
                        body = reqBody,
                        database = database,
                        path = filePath,
                        project = project,
                        loginCoordinator = loginCoordinator,
                    )
                    val ticket = ticketObj["ticket"].string ?: error("Missing export ticket")
                    val bytes = api.dbDownloadGridExportTicket(ticket)
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(bytes)
                        } ?: error("Could not open destination file")
                    }
                    ticketObj["fileName"].string ?: gridExportFileName(tableName, formatId)
                }.onSuccess { fileName ->
                    exportStatusMessage = "Exported $fileName"
                }.onFailure { err ->
                    exportStatusMessage = "Export failed: ${err.message}"
                }
            }
        }
    }

    // SAF Save Cell to File launcher: pick file destination FIRST, then write local bytes or fetch `/grid/cell` ticket
    var pendingCellFileReq by remember { mutableStateOf<Triple<String, Map<String, DbCellValue>, CellFileResult>?>(null) }
    val saveCellFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri: Uri? ->
        val req = pendingCellFileReq
        pendingCellFileReq = null
        if (uri != null && req != null) {
            val (colName, rowKeyMap, cellFileRes) = req
            scope.launch {
                runCatching {
                    val bytes = when (cellFileRes) {
                        is CellFileResult.Ready -> cellFileRes.bytes
                        is CellFileResult.NeedsServerFetch -> {
                            val client = api ?: error("Not connected")
                            val ticketReq = buildGridCellTicketRequestJson(
                                table = tableName,
                                schema = schemaName.ifBlank { null },
                                column = colName,
                                key = rowKeyMap,
                                fileName = cellFileRes.fileName,
                            )
                            val ticketObj = client.dbGridCellTicket(
                                connectionId = connId,
                                body = ticketReq,
                                database = database,
                                path = filePath,
                                project = project,
                                loginCoordinator = loginCoordinator,
                            )
                            val ticket = ticketObj["ticket"].string ?: error("Missing cell ticket")
                            client.dbDownloadGridExportTicket(ticket)
                        }
                        is CellFileResult.Empty -> error(cellFileRes.message)
                    }
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(bytes)
                        } ?: error("Could not open destination file")
                    }
                }.onSuccess {
                    exportStatusMessage = "Saved cell to file"
                }.onFailure { err ->
                    exportStatusMessage = "Save cell failed: ${err.message}"
                }
            }
        }
    }

    val tableRefs = remember(structureJson, schemaName) {
        tableReferences(structureJson, schemaName.ifBlank { "public" })
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val prompt = activeLoginPrompt
        if (prompt != null) {
            DbLoginDialogContent(
                prompt = prompt,
                errorMessage = loginError,
                errorDetails = loginErrorDetails,
                onSubmit = { user, pass ->
                    val client = api ?: return@DbLoginDialogContent
                    val cid = prompt.connectionId ?: return@DbLoginDialogContent
                    scope.launch {
                        runCatching {
                            client.dbLoginConnection(cid, password = pass, user = user)
                        }.onSuccess {
                            activeLoginPrompt = null
                            activeLoginDeferred?.complete(true)
                        }.onFailure { err ->
                            loginError = err.message ?: "Login failed"
                            loginErrorDetails = (err as? DbLoginFailedException)?.details?.takeIf { it.isNotBlank() }
                        }
                    }
                },
                onCancel = {
                    activeLoginPrompt = null
                    activeLoginDeferred?.complete(false)
                },
            )
        }

        DatabaseTableTabContent(
            tableName = tableName,
            schemaName = schemaName,
            connectionName = connName,
            dbType = dbType,
            pageData = pageData,
            schemaList = schemaList,
            rowCountView = computeRowCountView(pageData, rowCountState),
            sort = sort,
            initialFilters = filters,
            serverFilterErrors = serverFilterErrors,
            loading = loading,
            loadingMore = loadingMore,
            fetchingAllCount = fetchingAllCount,
            errorMessage = errorMessage,
            missingDriverId = missingDriverId,
            exportStatusMessage = exportStatusMessage,
            readonlyEnabled = readonlyEnabled,
            autoRefreshSec = autoRefreshSec,
            onRefresh = { withStructure ->
                scope.launch { loadFirstPage(withStructure = withStructure) }
            },
            onSetAutoRefreshSec = { autoRefreshSec = it },
            onToggleReadonly = { nextReadonly ->
                readonlyEnabled = nextReadonly
                val cidInt = connId.toIntOrNull()
                if (cidInt != null && api != null) {
                    scope.launch {
                        runCatching {
                            api.dbUpdateConnection(
                                cidInt,
                                buildJsonObject { put("readonly", if (nextReadonly) 1 else 0) },
                            )
                        }
                    }
                }
            },
            onSortAction = { col, action ->
                val nextSort = applySortAction(sort, col, action)
                sort = nextSort
                scope.launch { loadFirstPage() }
            },
            onFiltersChanged = { nextFilters ->
                filters = nextFilters
                serverFilterErrors = emptyMap()
            },
            onLoadValues = { colName, _, searchText ->
                val client = api
                if (client == null) {
                    LookupValueListResult(error = "Not connected")
                } else {
                    val reqData = valuesRequest(
                        scope = TableScope(table = tableName, schema = schemaName.ifBlank { null }),
                        column = colName,
                        search = searchText,
                        filters = filters,
                        columns = filterableCols,
                    )
                    runCatching {
                        client.dbGridValues(
                            connectionId = connId,
                            body = reqData.toJsonObject(),
                            database = database,
                            path = filePath,
                            project = project,
                            loginCoordinator = loginCoordinator,
                        )
                    }.fold(
                        onSuccess = { obj ->
                            withContext(Dispatchers.Default) {
                                val valsArr = obj["values"] as? JsonArray
                                val list = valsArr?.map { el -> parseDbCellValue(el) }.orEmpty()
                                LookupValueListResult(
                                    values = list,
                                    hasMore = obj["hasMore"].bool == true,
                                )
                            }
                        },
                        onFailure = { err ->
                            LookupValueListResult(error = err.message ?: "Failed to load values")
                        },
                    )
                }
            },
            onLoadLookup = { fkTable, fkSchema, fkCol, searchText, chosenDesc ->
                val client = api
                if (client == null) {
                    LookupTableRowsResult(error = "Not connected")
                } else {
                    runCatching {
                        val fkSchemaArr = client.dbTableSchema(
                            connectionId = connId,
                            table = fkTable,
                            schema = fkSchema?.ifBlank { null },
                            database = database,
                            path = filePath,
                            project = project,
                            loginCoordinator = loginCoordinator,
                        )
                        val fkSchemaCols = withContext(Dispatchers.Default) { parseGridColumnsSchema(fkSchemaArr) }
                        val fkFilterableCols = filterableColumns(fkSchemaCols, dbType)
                        val descCol = descriptionColumn(fkFilterableCols, fkCol, chosenDesc)
                        val searchCols = fkFilterableCols.filter { it.name == fkCol || it.name == descCol }
                            .ifEmpty { fkFilterableCols }
                        val reqJson = lookupRowsRequest(
                            scope = TableScope(table = fkTable, schema = fkSchema?.ifBlank { null }),
                            keyColumn = fkCol,
                            anyColumn = lookupSearch(searchText, searchCols).orEmpty(),
                        )
                        val gridObj = client.dbGridPage(
                            connectionId = connId,
                            body = reqJson,
                            database = database,
                            path = filePath,
                            project = project,
                            loginCoordinator = loginCoordinator,
                        )
                        val (parsed, rowsList) = withContext(Dispatchers.Default) {
                            val p = parseGridResponse(gridObj, countKey = "$fkTable:${fkSchema.orEmpty()}")
                            p to p.rows.indices.map { idx -> p.rowAsMap(idx) }
                        }
                        LookupTableRowsResult(
                            columns = fkFilterableCols,
                            rows = rowsList,
                            hasMore = parsed.hasMore,
                        )
                    }.getOrElse { err ->
                        LookupTableRowsResult(error = err.message ?: "Failed to load lookup rows")
                    }
                }
            },
            onLoadMore = {
                val cur = pageData ?: return@DatabaseTableTabContent
                val client = api ?: return@DatabaseTableTabContent
                if (!cur.hasMore || loadingMore) return@DatabaseTableTabContent
                val seq = fetchSeq
                loadingMore = true
                scope.launch {
                    val reqJson = buildGridRequestJson(
                        table = tableName,
                        schema = schemaName.ifBlank { null },
                        sort = sort,
                        filters = activeFilterReq.toFiltersJsonArray(),
                        anyColumn = activeFilterReq.toAnyColumnJsonArray(),
                        offset = cur.rows.size,
                        limit = GRID_PAGE_SIZE,
                    )
                    runCatching {
                        val moreObj = client.dbGridPage(
                            connectionId = connId,
                            body = reqJson,
                            database = database,
                            path = filePath,
                            project = project,
                            loginCoordinator = loginCoordinator,
                        )
                        withContext(Dispatchers.Default) {
                            parseGridResponse(moreObj, countKey = cur.countKey)
                        }
                    }.onSuccess { nextPart ->
                        if (seq == fetchSeq) {
                            val merged = cur.copy(
                                rows = cur.rows + nextPart.rows,
                                hasMore = nextPart.hasMore,
                            )
                            pageData = merged
                            if (!merged.hasMore) {
                                rowCountState = RowCountState(
                                    key = cur.countKey,
                                    count = merged.rows.size.toLong(),
                                    pending = false,
                                )
                            }
                        }
                    }
                    if (seq == fetchSeq) loadingMore = false
                }
            },
            onFetchAll = {
                val client = api ?: return@DatabaseTableTabContent
                val seq = fetchSeq
                scope.launch {
                    var cur = pageData ?: return@launch
                    fetchingAllCount = cur.rows.size
                    while (seq == fetchSeq && cur.hasMore) {
                        val reqJson = buildGridRequestJson(
                            table = tableName,
                            schema = schemaName.ifBlank { null },
                            sort = sort,
                            filters = activeFilterReq.toFiltersJsonArray(),
                            anyColumn = activeFilterReq.toAnyColumnJsonArray(),
                            offset = cur.rows.size,
                            limit = FETCH_ALL_CHUNK,
                        )
                        val part = runCatching {
                            val chunkObj = client.dbGridPage(
                                connectionId = connId,
                                body = reqJson,
                                database = database,
                                path = filePath,
                                project = project,
                                loginCoordinator = loginCoordinator,
                            )
                            withContext(Dispatchers.Default) {
                                parseGridResponse(chunkObj, countKey = cur.countKey)
                            }
                        }.getOrNull() ?: break
                        if (seq != fetchSeq) break
                        cur = cur.copy(
                            rows = cur.rows + part.rows,
                            hasMore = part.hasMore,
                        )
                        pageData = cur
                        fetchingAllCount = cur.rows.size
                        if (!cur.hasMore) {
                            rowCountState = RowCountState(
                                key = cur.countKey,
                                count = cur.rows.size.toLong(),
                                pending = false,
                            )
                        }
                    }
                    if (seq == fetchSeq) fetchingAllCount = null
                }
            },
            onCountExactly = {
                val cur = pageData ?: return@DatabaseTableTabContent
                val client = api ?: return@DatabaseTableTabContent
                val seq = fetchSeq
                rowCountState = RowCountState(key = cur.countKey, pending = true)
                scope.launch {
                    val reqJson = buildGridRequestJson(
                        table = tableName,
                        schema = schemaName.ifBlank { null },
                        sort = sort,
                        filters = activeFilterReq.toFiltersJsonArray(),
                        anyColumn = activeFilterReq.toAnyColumnJsonArray(),
                    )
                    runCatching {
                        client.dbGridCount(
                            connectionId = connId,
                            body = reqJson,
                            exact = true,
                            database = database,
                            path = filePath,
                            project = project,
                            loginCoordinator = loginCoordinator,
                        )
                    }.onSuccess { cntObj ->
                        if (seq == fetchSeq) {
                            rowCountState = RowCountState(
                                key = cur.countKey,
                                count = cntObj["count"].long,
                                estimate = cntObj["estimate"].long,
                                pending = false,
                                timedOut = cntObj["timedOut"].bool == true,
                            )
                        }
                    }
                }
            },
            onStartQuickExport = { formatId, visibleCols ->
                pendingExportFormat = formatId
                pendingExportColumns = visibleCols
                exportDocumentLauncher.launch(gridExportFileName(tableName, formatId))
            },
            onInstallMissingDriver = { driverId ->
                val client = api ?: return@DatabaseTableTabContent
                scope.launch {
                    runCatching { client.dbInstallMissingDriver(driverId) }.onSuccess {
                        missingDriverId = null
                        loadFirstPage(withStructure = true)
                    }
                }
            },
            tableReferences = tableRefs,
            onPreviewChangeset = { payload ->
                val client = api ?: error("Not connected")
                val body = buildChangesetRequestJson(
                    table = tableName,
                    schema = schemaName.ifBlank { null },
                    payload = payload,
                )
                val obj = client.dbChangesetPreview(
                    connectionId = connId,
                    body = body,
                    database = database,
                    path = filePath,
                    project = project,
                    loginCoordinator = loginCoordinator,
                )
                parseChangesetPreview(obj)
            },
            onApplyChangeset = { payload, cascade ->
                val client = api ?: error("Not connected")
                val body = buildChangesetRequestJson(
                    table = tableName,
                    schema = schemaName.ifBlank { null },
                    payload = payload,
                    cascade = cascade,
                )
                val obj = client.dbChangesetApply(
                    connectionId = connId,
                    body = body,
                    database = database,
                    path = filePath,
                    project = project,
                    loginCoordinator = loginCoordinator,
                )
                val res = parseChangesetApplyResult(obj)
                loadFirstPage(keepLoaded = true)
                res
            },
            onSaveCellToFile = { colName, rowKeyMap, cellVal ->
                val res = cellFile(tableName, colName, cellVal)
                when (res) {
                    is CellFileResult.Empty -> exportStatusMessage = res.message
                    is CellFileResult.Ready -> {
                        pendingCellFileReq = Triple(colName, rowKeyMap, res)
                        saveCellFileLauncher.launch(res.fileName)
                    }
                    is CellFileResult.NeedsServerFetch -> {
                        pendingCellFileReq = Triple(colName, rowKeyMap, res)
                        saveCellFileLauncher.launch(res.fileName)
                    }
                }
            },
            onOpenQueryTab = { sql ->
                model.openWorkspaceTab(
                    type = "db-query",
                    title = "$tableName.sql",
                    metadata = buildJsonObject {
                        put("connectionId", connId)
                        put("connectionName", connName)
                        if (!database.isNullOrBlank()) put("database", database)
                        if (!filePath.isNullOrBlank()) put("filePath", filePath)
                        if (!project.isNullOrBlank()) put("project", project)
                        put("dbType", dbType)
                        put("sql", sql)
                        put("currentSql", sql)
                        put("openedSql", sql)
                    },
                )
            },
            initialChangeset = initialChangeset,
            onChangesetChanged = { cs ->
                val count = changedRowCount(cs)
                setUnsavedGridChangeset(tab.id, cs)
                model.patchTabMetadata(
                    tab.id,
                    "unsavedGridRows" to if (count > 0) JsonPrimitive(count) else null,
                )
            },
            onOpenReferencedTableTab = { refTable, refSchema, refFilters ->
                model.openWorkspaceTab(
                    type = "db-table",
                    title = refTable,
                    metadata = buildJsonObject {
                        put("connectionId", connId)
                        put("connectionName", connName)
                        if (!database.isNullOrBlank()) put("database", database)
                        if (!filePath.isNullOrBlank()) put("filePath", filePath)
                        if (!project.isNullOrBlank()) put("project", project)
                        put("dbType", dbType)
                        put("tableName", refTable)
                        put("schema", refSchema ?: schemaName)
                        put("readonly", readonlyEnabled)
                        put(
                            "filters",
                            encodeGridFiltersJson(
                                GridFilters(
                                    columns = refFilters.mapValues { FilterText(it.value) },
                                ),
                            ),
                        )
                    },
                )
            },
        )
    }
}

private data class FunnelTarget(val column: String?) // null = Multi column filter
private data class CellFkLookupTarget(
    val rowIndex: Int,
    val column: String,
    val fkTable: String,
    val fkSchema: String?,
    val fkColumn: String,
)

@Composable
internal fun DatabaseTableTabContent(
    tableName: String,
    schemaName: String = "public",
    connectionName: String = "Postgres",
    dbType: String = "postgres",
    pageData: DbGridPageData?,
    schemaList: List<GridColumnSchema>,
    rowCountView: RowCountView?,
    sort: List<GridSortItem> = emptyList(),
    initialFilters: GridFilters = NO_FILTERS,
    serverFilterErrors: Map<String, String> = emptyMap(),
    loading: Boolean = false,
    loadingMore: Boolean = false,
    fetchingAllCount: Int? = null,
    errorMessage: String? = null,
    missingDriverId: String? = null,
    exportStatusMessage: String? = null,
    readonlyEnabled: Boolean = true,
    autoRefreshSec: Int = 0,
    tableReferences: TableReferences = TableReferences(),
    onRefresh: (Boolean) -> Unit = {},
    onSetAutoRefreshSec: (Int) -> Unit = {},
    onToggleReadonly: (Boolean) -> Unit = {},
    onSortAction: (String, SortMenuAction) -> Unit = { _, _ -> },
    onFiltersChanged: (GridFilters) -> Unit = {},
    onLoadValues: suspend (column: String, kind: ColumnKind, search: String) -> LookupValueListResult = { _, _, _ ->
        LookupValueListResult()
    },
    onLoadLookup: suspend (
        fkTable: String,
        fkSchema: String?,
        fkColumn: String,
        search: String,
        chosenDescription: String?,
    ) -> LookupTableRowsResult = { _, _, _, _, _ ->
        LookupTableRowsResult()
    },
    onPreviewChangeset: suspend (GridChangesPayload) -> ChangesetPreview = { payload ->
        ChangesetPreview(
            statementCount = payload.totalOperations,
            script = "-- ${payload.totalOperations} statement(s)",
            references = emptyList(),
        )
    },
    onApplyChangeset: suspend (GridChangesPayload, List<TableRef>) -> ChangesetApplyResult = { payload, cascade ->
        ChangesetApplyResult(
            inserted = payload.inserts.size,
            updated = payload.updates.size,
            deleted = payload.deletes.size,
            cascaded = cascade.size,
            executionTimeMs = 1,
        )
    },
    onSaveCellToFile: (colName: String, rowKeyMap: Map<String, DbCellValue>, value: DbCellValue) -> Unit = { _, _, _ -> },
    onOpenQueryTab: (String) -> Unit = {},
    onOpenReferencedTableTab: (table: String, schema: String?, filters: Map<String, String>) -> Unit = { _, _, _ -> },
    onLoadMore: () -> Unit = {},
    onFetchAll: () -> Unit = {},
    onCountExactly: () -> Unit = {},
    onStartQuickExport: (String, List<String>) -> Unit = { _, _ -> },
    onInstallMissingDriver: (String) -> Unit = {},
    initialChangeset: GridChangeset = EMPTY_CHANGESET,
    onChangesetDirtyChanged: (Boolean) -> Unit = {},
    onChangesetChanged: (GridChangeset) -> Unit = {},
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val prefs = remember(context) {
        context.getSharedPreferences(COPY_FORMAT_PREF_FILE, Context.MODE_PRIVATE)
    }
    var defaultCopyFormat by remember {
        val savedId = prefs.getString(COPY_FORMAT_PREF_KEY, DEFAULT_COPY_FORMAT.id)
        val found = CopyFormat.entries.firstOrNull { it.id == savedId } ?: DEFAULT_COPY_FORMAT
        mutableStateOf(found)
    }
    var dontAskFetchAll by remember {
        mutableStateOf(prefs.getBoolean(FETCH_ALL_DONT_ASK_KEY, false))
    }
    var dontAskSave by remember {
        mutableStateOf(prefs.getBoolean(SAVE_DONT_ASK_KEY, false))
    }

    var filters by remember(initialFilters) { mutableStateOf(initialFilters) }
    fun updateFilters(next: GridFilters) {
        filters = next
        onFiltersChanged(next)
    }

    var selection by remember { mutableStateOf(GridSelectionState()) }
    var hiddenColumns by remember { mutableStateOf<Set<String>>(emptySet()) }
    val columnWidthsDp = remember { mutableStateMapOf<String, Int>() }
    var showColumnsPanel by remember { mutableStateOf(false) }
    var showFiltersPanel by remember { mutableStateOf(false) }
    var showCellDataPanel by remember { mutableStateOf(false) }
    var showReferencesPanel by remember { mutableStateOf(false) }
    var activeReference by remember { mutableStateOf<GridReference?>(null) }
    var showFormView by remember { mutableStateOf(false) }
    var columnSearchQuery by remember { mutableStateOf("") }

    var openFunnelTarget by remember { mutableStateOf<FunnelTarget?>(null) }
    var activeFilterModal by remember { mutableStateOf<ActiveFilterModal?>(null) }
    var cellFkLookupTarget by remember { mutableStateOf<CellFkLookupTarget?>(null) }
    var phoneFilterSheetTarget by remember { mutableStateOf<FunnelTarget?>(null) }
    var showPhoneColsAndFiltersSheet by remember { mutableStateOf(false) }

    var showCopyMenu by remember { mutableStateOf(false) }
    var showExportMenu by remember { mutableStateOf(false) }
    var showRefreshMenu by remember { mutableStateOf(false) }
    var showJsonDocMenu by remember { mutableStateOf(false) }
    var showEditRowJsonDialog by remember { mutableStateOf(false) }
    var showAddJsonDocDialog by remember { mutableStateOf(false) }
    var showGenerateSqlDialog by remember { mutableStateOf(false) }
    var showFetchAllConfirm by remember { mutableStateOf(false) }
    var showPhoneOverflowMenu by remember { mutableStateOf(false) }
    var phoneLongPressCell by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var phoneSheetSubmenu by remember { mutableStateOf<String?>(null) }

    // Phase 09d Changeset & Editing State
    var history by remember(initialChangeset) { mutableStateOf(ChangesetHistory(present = initialChangeset)) }
    val changeset = history.present
    LaunchedEffect(changeset) {
        onChangesetDirtyChanged(!isEmptyChangeset(changeset))
        onChangesetChanged(changeset)
    }
    var newRowSeq by remember { mutableIntStateOf(0) }
    var inlineEditTarget by remember { mutableStateOf<Pair<Int, Int>?>(null) } // (visColIdx, rowIdx)
    var phoneFormRowIndex by remember { mutableStateOf<Int?>(null) }
    var activeSavePreview by remember { mutableStateOf<ChangesetPreview?>(null) }
    var saveDialogError by remember { mutableStateOf<String?>(null) }
    var saveConflictFailure by remember { mutableStateOf<ChangesetFailure?>(null) }
    var savingChanges by remember { mutableStateOf(false) }
    var saveToastMessage by remember { mutableStateOf<String?>(null) }

    val columns = pageData?.columnKeys.orEmpty()
    val storedRows = pageData?.rows.orEmpty()
    val visibleColumns = remember(columns, hiddenColumns) {
        columns.filter { it !in hiddenColumns }
    }
    val schemaMap = remember(schemaList) {
        schemaList.associateBy { it.name }
    }
    val columnKinds = remember(columns, schemaMap, dbType) {
        columns.associateWith { col ->
            val declared = schemaMap[col]?.type.orEmpty()
            classifyColumnType(dbType, declared)
        }
    }
    val filterableCols = remember(columns, schemaMap, dbType) {
        filterableColumns(
            columns.map { col -> schemaMap[col] ?: GridColumnSchema(name = col, type = "") },
            dbType,
        )
    }

    val keyCols = remember(pageData?.rowKey, schemaList) {
        pageData?.rowKey.orEmpty().ifEmpty {
            schemaList.filter { it.pk }.map { it.name }
        }
    }
    val hasRowKey = keyCols.isNotEmpty() && keyCols.all { it in columns }
    val canEditTable = !readonlyEnabled && hasRowKey

    fun rowKeyMapFor(rowIndex: Int): Map<String, DbCellValue> {
        if (rowIndex !in storedRows.indices) return emptyMap()
        val stored = storedRows[rowIndex]
        val out = LinkedHashMap<String, DbCellValue>()
        for (kCol in keyCols) {
            val cIdx = columns.indexOf(kCol)
            if (cIdx >= 0) {
                out[kCol] = stored.getOrElse(cIdx) { DbCellValue.Null }
            }
        }
        return out
    }

    val storedRowIds = remember(storedRows, columns, keyCols) {
        List(storedRows.size) { rIdx ->
            rowIdForRowKey(rowKeyMapFor(rIdx), keyCols, rIdx)
        }
    }

    // Combined rows: stored rows (with pending cell edits overlaid) + inserted new rows
    val rowIds = remember(storedRowIds, changeset.inserted) {
        storedRowIds + changeset.inserted
    }
    val rows = remember(storedRows, storedRowIds, columns, changeset) {
        val modifiedStored = storedRows.mapIndexed { rIdx, baseRow ->
            val rId = storedRowIds.getOrElse(rIdx) { "row_$rIdx" }
            val rowEdits = changeset.cells[rId]
            if (rowEdits.isNullOrEmpty()) baseRow
            else columns.mapIndexed { cIdx, colName ->
                rowEdits[colName]?.newVal ?: baseRow.getOrElse(cIdx) { DbCellValue.Null }
            }
        }
        val appendedNew = changeset.inserted.map { newId ->
            val rowEdits = changeset.cells[newId].orEmpty()
            columns.map { colName ->
                rowEdits[colName]?.newVal ?: DbCellValue.NoField
            }
        }
        modifiedStored + appendedNew
    }

    fun rowIdAt(rowIndex: Int): String = rowIds.getOrElse(rowIndex) { "row_$rowIndex" }

    fun storedValueAt(colKey: String, rowIndex: Int): DbCellValue {
        if (rowIndex !in storedRows.indices) return DbCellValue.NoField
        val cIdx = columns.indexOf(colKey)
        if (cIdx < 0) return DbCellValue.Null
        return storedRows[rowIndex].getOrElse(cIdx) { DbCellValue.Null }
    }

    fun currentValueAt(colKey: String, rowIndex: Int): DbCellValue {
        val r = rows.getOrNull(rowIndex) ?: return DbCellValue.Null
        val cIdx = columns.indexOf(colKey)
        if (cIdx < 0) return DbCellValue.Null
        return r.getOrElse(cIdx) { DbCellValue.Null }
    }

    fun currentRowMap(rowIndex: Int): Map<String, DbCellValue> {
        val r = rows.getOrNull(rowIndex) ?: return emptyMap()
        return columns.mapIndexed { idx, col -> col to (r.getOrElse(idx) { DbCellValue.Null }) }.toMap()
    }

    fun storedRowMap(rowIndex: Int): Map<String, DbCellValue> {
        if (rowIndex !in storedRows.indices) return emptyMap()
        val r = storedRows[rowIndex]
        return columns.mapIndexed { idx, col -> col to (r.getOrElse(idx) { DbCellValue.Null }) }.toMap()
    }

    val allRowKeysMap = remember(storedRows, storedRowIds, columns, keyCols) {
        val map = LinkedHashMap<String, Map<String, DbCellValue>>()
        for (rIdx in storedRows.indices) {
            val keyMap = rowKeyMapFor(rIdx)
            val rId = storedRowIds.getOrElse(rIdx) { "row_$rIdx" }
            map[rId] = keyMap
            map["row_$rIdx"] = keyMap
        }
        map
    }

    fun pushChangeset(next: GridChangeset) {
        history = recordChange(history, next)
    }

    fun commitSingleCell(rowIndex: Int, colKey: String, newValue: DbCellValue) {
        if (!canEditTable) return
        val rId = rowIdAt(rowIndex)
        val sch = schemaMap[colKey]
        if (isCellLocked(LockColumn(pk = sch?.pk == true, autoIncrement = sch?.autoIncrement == true), rId, changeset)) {
            return
        }
        val orig = if (isNewRowId(rId)) DbCellValue.NoField else storedValueAt(colKey, rowIndex)
        val next = editCells(
            changeset,
            listOf(
                CellChange(
                    rowId = rId,
                    col = colKey,
                    value = newValue,
                    key = rowKeyMapFor(rowIndex),
                    original = orig,
                ),
            ),
        )
        pushChangeset(next)
    }

    fun insertBlankRow(): Int {
        val newId = "$NEW_ROW_PREFIX${++newRowSeq}"
        val next = addRows(changeset, listOf(NewRow(rowId = newId)))
        pushChangeset(next)
        val newIdx = storedRows.size + next.inserted.size - 1
        selection = GridSelectionState(
            activeCell = 0 to newIdx,
            ranges = listOf(GridRect(0, newIdx, 1, 1)),
        )
        return newIdx
    }

    fun cloneSelectedRows() {
        val targetRows = selectedRowIndices(selection, rows.size).ifEmpty {
            listOfNotNull(selection.activeCell?.second)
        }
        if (targetRows.isEmpty()) return
        val cloneCols = columns.map { col ->
            val sch = schemaMap[col]
            CloneColumn(name = col, pk = sch?.pk == true, autoIncrement = sch?.autoIncrement == true)
        }
        val toAdd = targetRows.map { rIdx ->
            val vals = cloneValues(currentRowMap(rIdx), cloneCols)
            NewRow(rowId = "$NEW_ROW_PREFIX${++newRowSeq}", values = vals)
        }
        pushChangeset(addRows(changeset, toAdd))
    }

    fun deleteSelectedRows() {
        val targetRows = selectedRowIndices(selection, rows.size).ifEmpty {
            listOfNotNull(selection.activeCell?.second)
        }
        if (targetRows.isEmpty()) return
        val keysByRowId = targetRows.associate { rIdx -> rowIdAt(rIdx) to rowKeyMapFor(rIdx) }
        pushChangeset(deleteRows(changeset, targetRows.map { rowIdAt(it) }, keysByRowId))
    }

    fun revertSelectedRows() {
        val targetRows = selectedRowIndices(selection, rows.size).ifEmpty {
            listOfNotNull(selection.activeCell?.second)
        }
        if (targetRows.isEmpty()) return
        pushChangeset(revertRows(changeset, targetRows.map { rowIdAt(it) }))
    }

    fun triggerSaveFlow() {
        val payload = toGridChanges(changeset, allRowKeysMap)
        if (payload.hasMissingRowKey) {
            saveDialogError = "Cannot save: missing primary key for one or more rows"
            return
        }
        if (payload.totalOperations == 0) {
            saveToastMessage = "No changes to save"
            return
        }
        if (payload.totalOperations > CHANGESET_MAX_OPERATIONS) {
            saveDialogError = "Changeset exceeds maximum of $CHANGESET_MAX_OPERATIONS operations"
            return
        }
        saveDialogError = null
        saveConflictFailure = null
        scope.launch {
            runCatching { onPreviewChangeset(payload) }.onSuccess { preview ->
                if (shouldBypassSaveDialog(dontAskSave, preview)) {
                    savingChanges = true
                    runCatching { onApplyChangeset(payload, emptyList()) }.onSuccess { res ->
                        savingChanges = false
                        history = ChangesetHistory()
                        saveToastMessage = savedText(res)
                    }.onFailure { err ->
                        savingChanges = false
                        activeSavePreview = preview
                        saveDialogError = err.message ?: "Save failed"
                        if (err is PpmError.Server) {
                            saveConflictFailure = parseChangesetFailure(err.data)
                        }
                    }
                } else {
                    activeSavePreview = preview
                }
            }.onFailure { err ->
                saveDialogError = err.message ?: "Failed to preview changes"
            }
        }
    }

    val selectionStats = remember(selection, visibleColumns, rows, columns) {
        if (pageData == null) null
        else {
            val visColIndices = columns.indices.filter { columns[it] !in hiddenColumns }
            computeSelectionStats(selection, visibleColumns.size, rows.size) { visCol, r ->
                val rawCol = visColIndices.getOrElse(visCol) { 0 }
                rows.getOrNull(r)?.getOrNull(rawCol) ?: DbCellValue.Null
            }
        }
    }

    val dialect = remember(dbType) {
        when (dbType.lowercase()) {
            "mysql", "mariadb" -> "mysql"
            "sqlite" -> "sqlite"
            else -> "postgres"
        }
    }

    fun copySelectionInFormat(format: CopyFormat) {
        val data = pageData ?: return
        val block = if (selection.isEmpty) {
            selectedBlock(
                GridSelectionState(ranges = listOf(GridRect(0, 0, visibleColumns.size, rows.size))),
                visibleColumns.size,
                rows.size,
            )
        } else {
            selectedBlock(selection, visibleColumns.size, rows.size)
        }
        val copyCols = block.columns.mapNotNull { visIdx -> visibleColumns.getOrNull(visIdx) }
        val copyRows = block.rows.map { rIdx -> currentRowMap(rIdx) }
        val copyStored = block.rows.map { rIdx -> storedRowMap(rIdx).ifEmpty { currentRowMap(rIdx) } }
        val effectiveKeys = keyCols.ifEmpty { listOfNotNull(columns.firstOrNull()) }
        val text = formatCopy(
            format = format,
            columns = copyCols,
            rows = copyRows,
            target = CopySqlTarget(
                table = tableName,
                schema = schemaName.ifBlank { null },
                dialect = dialect,
                keyColumns = effectiveKeys,
                kinds = columnKinds,
            ),
            storedRows = copyStored,
        )
        copyPlainToClipboard(context, "PPM Grid Copy", text)
    }

    val changedCount = remember(changeset) { changedRowCount(changeset) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .testTag("db-table-tab"),
    ) {
        val tabWidthDp = maxWidth.value.toInt()
        val floatSidePanels = tabWidthDp < FLOATING_PANEL_TAB_WIDTH_DP

        Column(modifier = Modifier.fillMaxSize()) {
            // 1. Header / Toolbar (Phone 52 dp header vs Tablet DBGate toolbar)
            if (mobile) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .background(tokens.panel2.color)
                        .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                        .padding(horizontal = 12.dp)
                        .testTag("db-table-phone-header"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        BasicText(
                            tableName,
                            style = ppmText(13f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            BasicText(
                                connectionName,
                                style = ppmText(11f, lineHeight = 14f).copy(color = tokens.text3.color),
                            )
                            if (rowCountView != null) {
                                BasicText(
                                    "· ${rowCountView.text}",
                                    modifier = Modifier
                                        .clickable(enabled = rowCountView.canCountExactly) { onCountExactly() }
                                        .testTag("db-row-count-badge"),
                                    style = ppmText(11f, FontWeight.Medium, FontKind.MONO, lineHeight = 14f).copy(
                                        color = if (rowCountView.canCountExactly) tokens.accent.color else tokens.text2.color,
                                    ),
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        SettingsButton(
                            label = "⋯",
                            modifier = Modifier.testTag("db-phone-overflow-btn"),
                            onClick = { showPhoneOverflowMenu = !showPhoneOverflowMenu },
                        )
                    }
                }

                if (showPhoneOverflowMenu) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(tokens.panel.color)
                            .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("db-phone-overflow-menu"),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SettingsButton(label = "Refresh", onClick = { onRefresh(false); showPhoneOverflowMenu = false })
                        SettingsButton(
                            label = "Fetch all",
                            modifier = Modifier.testTag("db-fetch-all-btn"),
                            onClick = {
                                showPhoneOverflowMenu = false
                                if (dontAskFetchAll) onFetchAll() else showFetchAllConfirm = true
                            },
                        )
                        SettingsButton(label = "Copy ▾", onClick = { showCopyMenu = !showCopyMenu })
                        SettingsButton(label = "Export ▾", onClick = { showExportMenu = !showExportMenu })
                        SettingsButton(
                            label = "Generate SQL",
                            modifier = Modifier.testTag("db-generate-sql-btn"),
                            onClick = {
                                showPhoneOverflowMenu = false
                                showGenerateSqlDialog = true
                            },
                        )
                        if (hasReferences(tableReferences)) {
                            SettingsButton(
                                label = "References",
                                primary = showReferencesPanel || activeReference != null,
                                modifier = Modifier.testTag("db-references-panel-btn"),
                                onClick = {
                                    showPhoneOverflowMenu = false
                                    showReferencesPanel = !showReferencesPanel
                                },
                            )
                        }
                        SettingsButton(label = "Columns (${visibleColumns.size}/${columns.size})", onClick = { showColumnsPanel = !showColumnsPanel })
                        SettingsButton(
                            label = if (readonlyEnabled) "🔒 Read-only" else "✏️ Editable",
                            primary = !readonlyEnabled,
                            modifier = Modifier.testTag("db-readonly-toggle-btn"),
                            onClick = { onToggleReadonly(!readonlyEnabled) },
                        )
                    }
                }
            } else {
                // Tablet / Desktop Toolbar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.panel2.color)
                        .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("db-table-tablet-toolbar"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingsButton(
                        label = if (autoRefreshSec > 0) "Refresh (${autoRefreshSec}s) ▾" else "Refresh ▾",
                        modifier = Modifier.testTag("db-refresh-menu-btn"),
                        onClick = { showRefreshMenu = !showRefreshMenu },
                    )
                    if (pageData?.hasMore == true) {
                        SettingsButton(
                            label = if (fetchingAllCount != null) "Fetching ($fetchingAllCount)…" else "Fetch all",
                            modifier = Modifier.testTag("db-fetch-all-btn"),
                            onClick = {
                                if (dontAskFetchAll) onFetchAll() else showFetchAllConfirm = true
                            },
                        )
                    }
                    if (!readonlyEnabled && hasRowKey) {
                        SettingsButton(
                            label = if (changedCount > 0) "Save ($changedCount)" else "Save",
                            primary = changedCount > 0,
                            modifier = Modifier.testTag("db-save-changes-btn"),
                            onClick = { triggerSaveFlow() },
                        )
                        SettingsButton(
                            label = "+ Insert row",
                            modifier = Modifier.testTag("db-insert-row-btn"),
                            onClick = {
                                val rIdx = insertBlankRow()
                                inlineEditTarget = 0 to rIdx
                            },
                        )
                        SettingsButton(
                            label = "Clone row",
                            modifier = Modifier.testTag("db-clone-row-btn"),
                            onClick = { cloneSelectedRows() },
                        )
                        SettingsButton(
                            label = "Delete row",
                            modifier = Modifier.testTag("db-delete-row-btn"),
                            onClick = { deleteSelectedRows() },
                        )
                        SettingsButton(
                            label = "Edit cell",
                            modifier = Modifier.testTag("db-edit-cell-btn"),
                            onClick = {
                                val act = selection.activeCell ?: (0 to 0)
                                inlineEditTarget = act
                            },
                        )
                        SettingsButton(
                            label = "Set NULL",
                            modifier = Modifier.testTag("db-set-null-btn"),
                            onClick = {
                                val act = selection.activeCell ?: return@SettingsButton
                                val col = visibleColumns.getOrNull(act.first) ?: return@SettingsButton
                                commitSingleCell(act.second, col, DbCellValue.Null)
                            },
                        )
                        SettingsButton(
                            label = "Revert row",
                            modifier = Modifier.testTag("db-revert-row-btn"),
                            onClick = { revertSelectedRows() },
                        )
                        if (!isEmptyChangeset(changeset)) {
                            SettingsButton(
                                label = "Revert all",
                                modifier = Modifier.testTag("db-revert-all-btn"),
                                onClick = { pushChangeset(EMPTY_CHANGESET) },
                            )
                        }
                        SettingsButton(
                            label = "Undo",
                            enabled = history.past.isNotEmpty(),
                            modifier = Modifier.testTag("db-undo-btn"),
                            onClick = { history = undoChange(history) },
                        )
                        SettingsButton(
                            label = "Redo",
                            enabled = history.future.isNotEmpty(),
                            modifier = Modifier.testTag("db-redo-btn"),
                            onClick = { history = redoChange(history) },
                        )
                        SettingsButton(
                            label = "JSON doc ▾",
                            modifier = Modifier.testTag("db-json-doc-menu-btn"),
                            onClick = { showJsonDocMenu = !showJsonDocMenu },
                        )
                    }
                    SettingsButton(
                        label = if (showFormView) "Grid view" else "Form view",
                        primary = showFormView,
                        modifier = Modifier.testTag("db-form-view-toggle-btn"),
                        onClick = { showFormView = !showFormView },
                    )
                    SettingsButton(
                        label = "Copy ▾",
                        modifier = Modifier.testTag("db-copy-menu-btn"),
                        onClick = { showCopyMenu = !showCopyMenu },
                    )
                    SettingsButton(
                        label = "Export ▾",
                        modifier = Modifier.testTag("db-export-menu-btn"),
                        onClick = { showExportMenu = !showExportMenu },
                    )
                    SettingsButton(
                        label = "Generate SQL",
                        modifier = Modifier.testTag("db-generate-sql-btn"),
                        onClick = { showGenerateSqlDialog = !showGenerateSqlDialog },
                    )
                    SettingsButton(
                        label = "Columns (${visibleColumns.size}/${columns.size})",
                        primary = showColumnsPanel,
                        modifier = Modifier.testTag("db-columns-panel-btn"),
                        onClick = { showColumnsPanel = !showColumnsPanel },
                    )
                    val activeFilterCount = (if (filters.multi != null) 1 else 0) + filters.columns.size
                    SettingsButton(
                        label = if (activeFilterCount > 0) "Filters ($activeFilterCount)" else "Filters",
                        primary = showFiltersPanel || hasFilters(filters),
                        modifier = Modifier.testTag("db-filters-panel-btn"),
                        onClick = { showFiltersPanel = !showFiltersPanel },
                    )
                    SettingsButton(
                        label = "Cell data",
                        primary = showCellDataPanel,
                        modifier = Modifier.testTag("db-cell-data-panel-btn"),
                        onClick = { showCellDataPanel = !showCellDataPanel },
                    )
                    if (hasReferences(tableReferences)) {
                        SettingsButton(
                            label = "References",
                            primary = showReferencesPanel || activeReference != null,
                            modifier = Modifier.testTag("db-references-panel-btn"),
                            onClick = { showReferencesPanel = !showReferencesPanel },
                        )
                    }
                    SettingsButton(
                        label = if (readonlyEnabled) "🔒 Read-only" else "✏️ Editable",
                        primary = !readonlyEnabled,
                        modifier = Modifier.testTag("db-readonly-toggle-btn"),
                        onClick = { onToggleReadonly(!readonlyEnabled) },
                    )

                    if (rowCountView != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(tokens.panel.color)
                                .cssBorder(if (rowCountView.canCountExactly) tokens.accent.color else tokens.border.color)
                                .clickable(enabled = rowCountView.canCountExactly) { onCountExactly() }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag("db-row-count-badge"),
                        ) {
                            BasicText(
                                rowCountView.text,
                                style = ppmText(11f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 14f).copy(
                                    color = if (rowCountView.canCountExactly) tokens.accent.color else tokens.text.color,
                                ),
                            )
                        }
                    }
                }
            }

            // JSON Document Dropdown Menu (`Edit row as JSON` / `Add JSON document(s)`)
            if (showJsonDocMenu) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.panel.color)
                        .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("db-json-doc-dropdown"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SettingsButton(
                        label = "Edit row as JSON document",
                        modifier = Modifier.testTag("db-open-edit-row-json-btn"),
                        onClick = {
                            showJsonDocMenu = false
                            showEditRowJsonDialog = true
                        },
                    )
                    SettingsButton(
                        label = "Add JSON document(s)",
                        modifier = Modifier.testTag("db-open-add-json-doc-btn"),
                        onClick = {
                            showJsonDocMenu = false
                            showAddJsonDocDialog = true
                        },
                    )
                }
            }

            // Refresh Dropdown Strip
            if (showRefreshMenu) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.panel.color)
                        .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("db-refresh-dropdown"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SettingsButton(label = "Refresh now", onClick = { onRefresh(false); showRefreshMenu = false })
                    SettingsButton(label = "Refresh with structure", onClick = { onRefresh(true); showRefreshMenu = false })
                    for (sec in listOf(0, 5, 15, 30, 60)) {
                        SettingsButton(
                            label = if (sec == 0) "Auto: Off" else "Auto: ${sec}s",
                            primary = autoRefreshSec == sec,
                            onClick = { onSetAutoRefreshSec(sec); showRefreshMenu = false },
                        )
                    }
                }
            }

            // Copy Advanced Dropdown Strip
            if (showCopyMenu) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.panel.color)
                        .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                        .padding(10.dp)
                        .testTag("db-copy-dropdown"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BasicText(
                        "Copy Selection (Default Ctrl+C format: ${defaultCopyFormat.shortName})",
                        style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f).copy(color = tokens.text2.color),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (fmt in CopyFormat.entries) {
                            SettingsButton(
                                label = fmt.label,
                                primary = defaultCopyFormat == fmt,
                                modifier = Modifier.testTag("db-copy-format-${fmt.id}"),
                                onClick = {
                                    copySelectionInFormat(fmt)
                                    defaultCopyFormat = fmt
                                    prefs.edit().putString(COPY_FORMAT_PREF_KEY, fmt.id).apply()
                                    showCopyMenu = false
                                },
                            )
                        }
                    }
                }
            }

            // Quick Export Dropdown Strip
            if (showExportMenu) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.panel.color)
                        .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("db-export-dropdown"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (spec in GRID_EXPORT_FORMATS) {
                        SettingsButton(
                            label = spec.label,
                            modifier = Modifier.testTag("db-export-format-${spec.id}"),
                            onClick = {
                                showExportMenu = false
                                onStartQuickExport(spec.id, visibleColumns)
                            },
                        )
                    }
                }
            }

            // Fetch All confirmation banner
            if (showFetchAllConfirm) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.warning.opacity(0.14).color)
                        .cssBorder(tokens.warning.color, sides = CssSides.Bottom)
                        .padding(10.dp)
                        .testTag("db-fetch-all-confirm-banner"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        "Fetch all remaining rows in 5,000-row batches?",
                        style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text.color),
                        modifier = Modifier.weight(1f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SettingsButton(
                            label = "Don't ask again & Fetch",
                            onClick = {
                                dontAskFetchAll = true
                                prefs.edit().putBoolean(FETCH_ALL_DONT_ASK_KEY, true).apply()
                                showFetchAllConfirm = false
                                onFetchAll()
                            },
                        )
                        SettingsButton(
                            label = "Fetch all",
                            primary = true,
                            modifier = Modifier.testTag("db-fetch-all-confirm-btn"),
                            onClick = {
                                showFetchAllConfirm = false
                                onFetchAll()
                            },
                        )
                        SettingsButton(
                            label = "Cancel",
                            onClick = { showFetchAllConfirm = false },
                        )
                    }
                }
            }

            // Driver missing or Error notice
            if (missingDriverId != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.warning.opacity(0.14).color)
                        .padding(10.dp)
                        .testTag("db-grid-missing-driver-banner"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        errorMessage ?: "Database driver $missingDriverId is not installed.",
                        style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text.color),
                        modifier = Modifier.weight(1f),
                    )
                    SettingsButton(
                        label = "Install $missingDriverId",
                        primary = true,
                        onClick = { onInstallMissingDriver(missingDriverId) },
                    )
                }
            } else if (errorMessage != null || (saveDialogError != null && activeSavePreview == null)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.error.opacity(0.14).color)
                        .padding(10.dp)
                        .testTag("db-grid-error-banner"),
                ) {
                    BasicText(
                        errorMessage ?: saveDialogError.orEmpty(),
                        style = ppmText(11f, lineHeight = 15f).copy(color = tokens.error.color),
                    )
                }
            }

            val statusNote = saveToastMessage ?: exportStatusMessage
            if (statusNote != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.panel2.color)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .testTag("db-grid-export-status"),
                ) {
                    BasicText(
                        statusNote,
                        style = ppmText(11f, kind = FontKind.MONO, lineHeight = 14f).copy(color = tokens.accent.color),
                    )
                }
            }

            // 9d.1 Save Changes Preview & CASCADE Dialog
            val savePreview = activeSavePreview
            if (savePreview != null) {
                GridSaveDialog(
                    preview = savePreview,
                    defaultSchema = schemaName.ifBlank { "public" },
                    dontAskAgainInitial = dontAskSave,
                    saving = savingChanges,
                    errorMessage = saveDialogError,
                    conflictFailure = saveConflictFailure,
                    onConfirmSave = { cascade, nextDontAsk ->
                        dontAskSave = nextDontAsk
                        prefs.edit().putBoolean(SAVE_DONT_ASK_KEY, nextDontAsk).apply()
                        val payload = toGridChanges(changeset, allRowKeysMap)
                        if (payload.hasMissingRowKey) {
                            saveDialogError = "Cannot save: missing primary key for one or more rows"
                            return@GridSaveDialog
                        }
                        savingChanges = true
                        saveDialogError = null
                        saveConflictFailure = null
                        scope.launch {
                            runCatching { onApplyChangeset(payload, cascade) }.onSuccess { res ->
                                savingChanges = false
                                activeSavePreview = null
                                history = ChangesetHistory()
                                saveToastMessage = savedText(res)
                            }.onFailure { err ->
                                savingChanges = false
                                saveDialogError = err.message ?: "Save failed"
                                if (err is PpmError.Server) {
                                    saveConflictFailure = parseChangesetFailure(err.data)
                                }
                            }
                        }
                    },
                    onOpenScript = { sql ->
                        onOpenQueryTab(sql)
                    },
                    onClose = {
                        activeSavePreview = null
                        saveDialogError = null
                        saveConflictFailure = null
                    },
                )
            }

            // 9d.2 Tablet Inline Cell Editor Bar
            val editTarget = inlineEditTarget
            if (!mobile && editTarget != null && canEditTable) {
                val editCol = visibleColumns.getOrNull(editTarget.first)
                val editRowIdx = editTarget.second
                if (editCol != null && editRowIdx in rows.indices) {
                    val sch = schemaMap[editCol]
                    val colKind = columnKinds[editCol] ?: ColumnKind.TEXT
                    val curVal = currentValueAt(editCol, editRowIdx)
                    val fkTable = sch?.fkTable
                    val fkCol = sch?.fkColumn ?: "id"
                    TabletInlineCellEditorBar(
                        columnName = editCol,
                        columnType = sch?.type.orEmpty(),
                        fieldKind = formFieldKindFor(colKind),
                        nullable = sch?.nullable != false,
                        hasFk = !fkTable.isNullOrBlank(),
                        initialText = formEditText(curVal),
                        onCommitValue = { nextVal ->
                            commitSingleCell(editRowIdx, editCol, nextVal)
                            inlineEditTarget = null
                        },
                        onOpenFkPicker = {
                            if (!fkTable.isNullOrBlank()) {
                                cellFkLookupTarget = CellFkLookupTarget(
                                    rowIndex = editRowIdx,
                                    column = editCol,
                                    fkTable = fkTable,
                                    fkSchema = sch.fkSchema,
                                    fkColumn = fkCol,
                                )
                            }
                        },
                        onJumpToFkRow = if (!fkTable.isNullOrBlank() && curVal != DbCellValue.Null && curVal != DbCellValue.NoField) {
                            {
                                val f = referencedRowFilters(fkCol, curVal, colKind)
                                if (f != null) {
                                    onOpenReferencedTableTab(fkTable, sch.fkSchema, f)
                                }
                            }
                        } else {
                            null
                        },
                        onCancel = { inlineEditTarget = null },
                    )
                }
            }

            // 9d.4 JSON Document Dialogs
            if (showEditRowJsonDialog) {
                val activeRowIdx = selection.activeCell?.second ?: 0
                if (activeRowIdx in rows.indices) {
                    val docCols = columns.map { col ->
                        val sch = schemaMap[col]
                        DocumentColumn(key = col, pk = sch?.pk == true, autoIncrement = sch?.autoIncrement == true)
                    }
                    val curMap = currentRowMap(activeRowIdx)
                    EditRowJsonDialog(
                        rowId = rowIdAt(activeRowIdx),
                        columns = docCols,
                        currentRow = curMap,
                        storedRow = storedRowMap(activeRowIdx),
                        key = rowKeyMapFor(activeRowIdx),
                        changeset = changeset,
                        initialJsonText = rowDocumentText(columns, curMap),
                        onApplyChanges = { changes ->
                            pushChangeset(editCells(changeset, changes))
                        },
                        onClose = { showEditRowJsonDialog = false },
                    )
                }
            }

            if (showAddJsonDocDialog) {
                val docCols = columns.map { col ->
                    val sch = schemaMap[col]
                    DocumentColumn(key = col, pk = sch?.pk == true, autoIncrement = sch?.autoIncrement == true)
                }
                AddJsonDocumentDialog(
                    columns = docCols,
                    nextRowId = { "$NEW_ROW_PREFIX${++newRowSeq}" },
                    onAddRows = { added ->
                        pushChangeset(addRows(changeset, added))
                    },
                    onClose = { showAddJsonDocDialog = false },
                )
            }

            // 9d.4 Generate SQL Dialog
            if (showGenerateSqlDialog) {
                val targetRows = selectedRowIndices(selection, rows.size).ifEmpty { rows.indices.toList() }
                val sqlRows = targetRows.map { rIdx ->
                    val cur = currentRowMap(rIdx)
                    val st = storedRowMap(rIdx).ifEmpty { cur }
                    SqlSourceRow(values = cur, where = st)
                }
                GenerateSqlDialog(
                    columns = columns,
                    keyColumns = keyCols,
                    rows = sqlRows,
                    target = CopySqlTarget(
                        table = tableName,
                        schema = schemaName.ifBlank { null },
                        dialect = dialect,
                        keyColumns = keyCols,
                        kinds = columnKinds,
                    ),
                    onCopySql = { sql -> copyPlainToClipboard(context, "Generated SQL", sql) },
                    onOpenInQueryTab = { sql -> onOpenQueryTab(sql) },
                    onClose = { showGenerateSqlDialog = false },
                )
            }

            // Cell FK Value Picker Dialog (when picking an FK value for a cell edit)
            val fkCellTarget = cellFkLookupTarget
            if (fkCellTarget != null) {
                DictionaryLookupDialog(
                    fkTable = fkCellTarget.fkTable,
                    fkSchema = fkCellTarget.fkSchema,
                    fkColumn = fkCellTarget.fkColumn,
                    kind = columnKinds[fkCellTarget.column] ?: ColumnKind.OTHER,
                    onLoadLookup = { search, chosenDesc ->
                        onLoadLookup(fkCellTarget.fkTable, fkCellTarget.fkSchema, fkCellTarget.fkColumn, search, chosenDesc)
                    },
                    onSubmit = { filterExpr ->
                        val rawPicked = filterExpr.substringBefore(",").removePrefix("=").trim().removeSurrounding("\"")
                        val colKind = columnKinds[fkCellTarget.column] ?: ColumnKind.TEXT
                        val parsed = parseFormText(rawPicked, formFieldKindFor(colKind))
                        val nextVal = if (parsed is FormParse.Valid) parsed.value else DbCellValue.Text(rawPicked)
                        commitSingleCell(fkCellTarget.rowIndex, fkCellTarget.column, nextVal)
                        inlineEditTarget = null
                    },
                    onClose = { cellFkLookupTarget = null },
                )
            }

            // Funnel Dropdown Strip (opened from filter row ▽ or Filters side panel ▽)
            val funnelTarget = openFunnelTarget
            if (funnelTarget != null) {
                val targetCol = funnelTarget.column
                val fKind = if (targetCol == null) {
                    FunnelKind.MULTI
                } else {
                    val cKind = columnKinds[targetCol] ?: ColumnKind.OTHER
                    filterSyntax(cKind).toFunnelKind()
                }
                FunnelDropdownBar(
                    targetLabel = targetCol ?: "All columns",
                    tagSuffix = targetCol ?: "multi",
                    funnelKind = fKind,
                    onWriteFilter = { text ->
                        val next = if (targetCol == null) {
                            withMultiFilter(filters, text)
                        } else {
                            withColumnFilter(filters, targetCol, text)
                        }
                        updateFilters(next)
                    },
                    onOpenDialog = { req ->
                        activeFilterModal = when (req) {
                            is FilterDialogRequest.ConditionDialog -> ActiveFilterModal.SetFilter(targetCol, req)
                            is FilterDialogRequest.LinesDialog -> ActiveFilterModal.MultiValues(targetCol)
                        }
                    },
                    onClose = { openFunnelTarget = null },
                )
            }

            // Active Filter Dialogs (SetFilter / FilterMultipleValues / ChooseValue / FkLookup)
            when (val modal = activeFilterModal) {
                is ActiveFilterModal.SetFilter -> {
                    SetFilterDialog(
                        column = modal.column,
                        request = modal.request,
                        onSubmit = { text ->
                            val next = if (modal.column == null) {
                                withMultiFilter(filters, text)
                            } else {
                                withColumnFilter(filters, modal.column, text)
                            }
                            updateFilters(next)
                        },
                        onClose = { activeFilterModal = null },
                    )
                }
                is ActiveFilterModal.MultiValues -> {
                    FilterMultipleValuesDialog(
                        column = modal.column,
                        onSubmit = { text ->
                            val next = if (modal.column == null) {
                                withMultiFilter(filters, text)
                            } else {
                                withColumnFilter(filters, modal.column, text)
                            }
                            updateFilters(next)
                        },
                        onClose = { activeFilterModal = null },
                    )
                }
                is ActiveFilterModal.ChooseValue -> {
                    ValueLookupDialog(
                        column = modal.column,
                        kind = modal.kind,
                        onLoadValues = { search -> onLoadValues(modal.column, modal.kind, search) },
                        onSubmit = { text ->
                            updateFilters(withColumnFilter(filters, modal.column, text))
                        },
                        onClose = { activeFilterModal = null },
                    )
                }
                is ActiveFilterModal.FkLookup -> {
                    DictionaryLookupDialog(
                        fkTable = modal.fkTable,
                        fkSchema = modal.fkSchema,
                        fkColumn = modal.fkColumn,
                        kind = modal.kind,
                        onLoadLookup = { search, chosenDesc ->
                            onLoadLookup(modal.fkTable, modal.fkSchema, modal.fkColumn, search, chosenDesc)
                        },
                        onSubmit = { text ->
                            updateFilters(withColumnFilter(filters, modal.column, text))
                        },
                        onClose = { activeFilterModal = null },
                    )
                }
                null -> Unit
            }

            // Phone Filter Chips Bar above grid
            if (mobile) {
                PhoneFilterChipsBar(
                    filters = filters,
                    filterableCols = filterableCols,
                    sort = sort,
                    hiddenCount = hiddenColumns.size,
                    serverErrors = serverFilterErrors,
                    onOpenColumnSheet = { col ->
                        phoneFilterSheetTarget = FunnelTarget(col)
                    },
                    onOpenColumnsAndFiltersSheet = {
                        showPhoneColsAndFiltersSheet = true
                    },
                    onChangeFilters = { updateFilters(it) },
                )
            }

            // 2. Main Grid + Side Panels + Master/Detail Split View
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        // Docked Columns Panel when wide enough
                        if (showColumnsPanel && !floatSidePanels) {
                            GridColumnsSidePanel(
                                columns = columns,
                                schemaMap = schemaMap,
                                hiddenColumns = hiddenColumns,
                                searchQuery = columnSearchQuery,
                                onSearchQueryChange = { columnSearchQuery = it },
                                onToggleColumn = { col ->
                                    hiddenColumns = if (col in hiddenColumns) hiddenColumns - col else hiddenColumns + col
                                },
                                onShowAll = { hiddenColumns = emptySet() },
                                modifier = Modifier.width(240.dp).fillMaxHeight(),
                            )
                        }

                        // 2D Data Grid or Tablet Form View
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            if (loading && pageData == null) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    BasicText(
                                        "Loading table data…",
                                        style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
                                    )
                                }
                            } else if (showFormView && !mobile) {
                                val formRowIdx = (selection.activeCell?.second ?: 0).coerceIn(0, (rows.size - 1).coerceAtLeast(0))
                                DatabaseFormView(
                                    columns = visibleColumns,
                                    schemaMap = schemaMap,
                                    dbType = dbType,
                                    rowIndex = formRowIdx,
                                    rowCount = rows.size,
                                    rowId = rowIdAt(formRowIdx),
                                    changeset = changeset,
                                    readonly = !canEditTable,
                                    valueAt = { colKey -> currentValueAt(colKey, formRowIdx) },
                                    onNavigateRow = { nextIdx ->
                                        selection = selection.copy(
                                            activeCell = (selection.activeCell?.first ?: 0) to nextIdx,
                                            ranges = listOf(GridRect(selection.activeCell?.first ?: 0, nextIdx, 1, 1)),
                                        )
                                    },
                                    onEditCell = { colKey, nextVal ->
                                        commitSingleCell(formRowIdx, colKey, nextVal)
                                    },
                                    onOpenFkPicker = { colKey, sch ->
                                        val fkT = sch.fkTable ?: return@DatabaseFormView
                                        cellFkLookupTarget = CellFkLookupTarget(
                                            rowIndex = formRowIdx,
                                            column = colKey,
                                            fkTable = fkT,
                                            fkSchema = sch.fkSchema,
                                            fkColumn = sch.fkColumn ?: "id",
                                        )
                                    },
                                    onJumpToFkRow = { _, sch, v ->
                                        val fkT = sch.fkTable ?: return@DatabaseFormView
                                        val fkC = sch.fkColumn ?: "id"
                                        val f = referencedRowFilters(fkC, v, classifyColumnType(dbType, sch.type))
                                        if (f != null) onOpenReferencedTableTab(fkT, sch.fkSchema, f)
                                    },
                                    onCloseFormView = { showFormView = false },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                NativeDataGrid(
                                    columns = columns,
                                    rows = rows,
                                    schemaMap = schemaMap,
                                    hiddenColumns = hiddenColumns,
                                    sort = sort,
                                    filters = filters,
                                    filterableCols = filterableCols,
                                    serverFilterErrors = serverFilterErrors,
                                    selection = selection,
                                    columnWidthsDp = columnWidthsDp,
                                    hasMore = pageData?.hasMore == true,
                                    loadingMore = loadingMore,
                                    newRowsCount = changeset.inserted.size,
                                    rowEditStatus = { rIdx ->
                                        val rId = rowIdAt(rIdx)
                                        GridRowEditStatus(
                                            isInserted = isNewRowId(rId),
                                            isDeleted = rId in changeset.deleted,
                                            modifiedColumns = changeset.cells[rId]?.keys.orEmpty(),
                                        )
                                    },
                                    onSelectionChange = { selection = it },
                                    onSortAction = onSortAction,
                                    onCopyColumnName = { colName ->
                                        copyPlainToClipboard(context, "Column Name", colName)
                                    },
                                    onResizeColumn = { col, w -> columnWidthsDp[col] = w },
                                    onChangeFilters = { next ->
                                        updateFilters(next)
                                    },
                                    onOpenChooseValues = { col, kind ->
                                        activeFilterModal = ActiveFilterModal.ChooseValue(col, kind)
                                    },
                                    onOpenLookup = { col, kind, fkTable, fkSchema, fkCol ->
                                        activeFilterModal = ActiveFilterModal.FkLookup(col, kind, fkTable, fkSchema, fkCol)
                                    },
                                    onOpenFunnelFor = { col ->
                                        openFunnelTarget = if (openFunnelTarget?.column == col && openFunnelTarget != null) {
                                            null
                                        } else {
                                            FunnelTarget(col)
                                        }
                                    },
                                    onPhoneOpenColumnSheet = { col ->
                                        phoneFilterSheetTarget = FunnelTarget(col)
                                    },
                                    onLoadMore = onLoadMore,
                                    onCellTap = { visColIdx, rowIdx ->
                                        if (mobile) {
                                            phoneFormRowIndex = rowIdx
                                        } else if (canEditTable && selection.activeCell == (visColIdx to rowIdx)) {
                                            inlineEditTarget = visColIdx to rowIdx
                                        }
                                    },
                                    onCellLongPressPhone = { visColIdx, rowIdx ->
                                        selection = selection.copy(
                                            activeCell = visColIdx to rowIdx,
                                            ranges = listOf(GridRect(visColIdx, rowIdx, 1, 1)),
                                        )
                                        phoneSheetSubmenu = null
                                        phoneLongPressCell = visColIdx to rowIdx
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }

                            // Tablet Selection Stats Pill (`Rows: X · Count: Y · Sum: Z`)
                            if (!mobile && selectionStats != null) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(10.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(tokens.panel2.color)
                                        .cssBorder(tokens.border.color)
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                        .testTag("db-selection-stats-pill"),
                                ) {
                                    val sumPart = if (selectionStats.sum != null) " · Sum: ${selectionStats.sum}" else ""
                                    BasicText(
                                        "Rows: ${selectionStats.rows} · Count: ${selectionStats.count}$sumPart",
                                        style = ppmText(11f, FontWeight.Medium, FontKind.MONO, lineHeight = 14f)
                                            .copy(color = tokens.text.color),
                                    )
                                }
                            }
                        }

                        // Docked Filters Panel when wide enough
                        if (showFiltersPanel && !floatSidePanels) {
                            FiltersSidePanel(
                                filters = filters,
                                filterableCols = filterableCols,
                                schemaMap = schemaMap,
                                serverErrors = serverFilterErrors,
                                onChangeFilters = { updateFilters(it) },
                                onOpenChooseValues = { col, kind ->
                                    activeFilterModal = ActiveFilterModal.ChooseValue(col, kind)
                                },
                                onOpenLookup = { col, kind, fkTable, fkSchema, fkCol ->
                                    activeFilterModal = ActiveFilterModal.FkLookup(col, kind, fkTable, fkSchema, fkCol)
                                },
                                onOpenFunnelFor = { col ->
                                    openFunnelTarget = FunnelTarget(col)
                                },
                                onClose = { showFiltersPanel = false },
                                modifier = Modifier.width(260.dp).fillMaxHeight(),
                            )
                        }

                        // Docked References Panel when wide enough
                        if (showReferencesPanel && !floatSidePanels) {
                            ReferencesSidePanel(
                                references = tableReferences,
                                activeReference = activeReference,
                                defaultSchema = schemaName.ifBlank { "public" },
                                onSelectReference = { activeReference = it },
                                onClose = { showReferencesPanel = false },
                                modifier = Modifier.width(260.dp).fillMaxHeight(),
                            )
                        }

                        // Docked Cell Data Panel when wide enough
                        if (showCellDataPanel && !floatSidePanels) {
                            val cellSel = remember(selection, visibleColumns, rows) {
                                collectCellData(selection, visibleColumns, visibleColumns, rows.size) { c, r ->
                                    val colKey = visibleColumns.getOrElse(c) { "" }
                                    currentValueAt(colKey, r)
                                }
                            }
                            val single = cellSel.cells.singleOrNull()
                            DatabaseCellDataSidePanel(
                                cells = cellSel.cells,
                                columnKeys = visibleColumns,
                                columnNames = visibleColumns,
                                valueAt = { c, r ->
                                    val colKey = visibleColumns.getOrElse(c) { "" }
                                    currentValueAt(colKey, r)
                                },
                                canEditSingleCell = canEditTable && single != null && !isCellLocked(
                                    LockColumn(
                                        pk = schemaMap[single.columnKey]?.pk == true,
                                        autoIncrement = schemaMap[single.columnKey]?.autoIncrement == true,
                                    ),
                                    rowIdAt(single.row),
                                    changeset,
                                ),
                                onApplySingleCellEdit = { nextVal ->
                                    if (single != null) {
                                        commitSingleCell(single.row, single.columnKey, nextVal)
                                    }
                                },
                                onSaveCellToFile = {
                                    if (single != null) {
                                        onSaveCellToFile(single.columnKey, rowKeyMapFor(single.row), single.value)
                                    }
                                },
                                onCopyText = { txt -> copyPlainToClipboard(context, "Cell Data", txt) },
                                onClose = { showCellDataPanel = false },
                                modifier = Modifier.width(280.dp).fillMaxHeight(),
                            )
                        }
                    }

                    // 9d.4 Master/Detail Split Pane below Master Grid (45% bottom height on phone)
                    val detailRef = activeReference
                    if (detailRef != null) {
                        val masterRows = remember(selection, rows.size) {
                            selectedRowIndices(selection, rows.size).ifEmpty {
                                listOfNotNull(selection.activeCell?.second)
                            }
                        }
                        val detailFiltersMap = remember(detailRef, masterRows, rows) {
                            detailKeyFilters(
                                ref = detailRef,
                                selectedMasterRows = masterRows,
                                masterValue = { colName, r -> currentValueAt(colName, r) },
                            )
                        }
                        Column(
                            modifier = (if (mobile) Modifier.fillMaxHeight(0.45f) else Modifier.height(200.dp))
                                .fillMaxWidth()
                                .background(tokens.panel.color)
                                .cssBorder(tokens.accent.color, sides = CssSides.Top)
                                .padding(8.dp)
                                .testTag("db-master-detail-pane"),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    BasicText(
                                        "Detail: ${detailRef.targetTable} (${detailFiltersMap.entries.joinToString(", ") { "${it.key}: ${it.value}" }})",
                                        style = ppmText(11f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 14f)
                                            .copy(color = tokens.text.color),
                                        modifier = Modifier.testTag("db-master-detail-header"),
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    SettingsButton(
                                        label = "Open in tab",
                                        modifier = Modifier.testTag("db-master-detail-open-tab-btn"),
                                        onClick = {
                                            onOpenReferencedTableTab(
                                                detailRef.targetTable,
                                                detailRef.targetSchema,
                                                detailFiltersMap,
                                            )
                                        },
                                    )
                                    SettingsButton(
                                        label = "✕",
                                        modifier = Modifier.testTag("db-master-detail-close-btn"),
                                        onClick = { activeReference = null },
                                    )
                                }
                            }
                            if (masterRows.isEmpty() || detailFiltersMap.values.all { it == "NULL" }) {
                                Box(
                                    modifier = Modifier.fillMaxSize().testTag("db-master-detail-empty"),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    BasicText(
                                        "Select a master row with a non-NULL key to view related rows in ${detailRef.targetTable}.",
                                        style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color),
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxSize().testTag("db-master-detail-active"),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    BasicText(
                                        "Filtering ${detailRef.targetTable} where ${detailFiltersMap.entries.joinToString(" AND ") { "${it.key} ${it.value}" }}",
                                        style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.accent.color),
                                    )
                                }
                            }
                        }
                    }
                }

                // Floating Columns Panel when tab width < 860 dp
                if (showColumnsPanel && floatSidePanels) {
                    GridColumnsSidePanel(
                        columns = columns,
                        schemaMap = schemaMap,
                        hiddenColumns = hiddenColumns,
                        searchQuery = columnSearchQuery,
                        onSearchQueryChange = { columnSearchQuery = it },
                        onToggleColumn = { col ->
                            hiddenColumns = if (col in hiddenColumns) hiddenColumns - col else hiddenColumns + col
                        },
                        onShowAll = { hiddenColumns = emptySet() },
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .width(240.dp)
                            .fillMaxHeight(),
                    )
                }

                // Floating Filters Panel when tab width < 860 dp
                if (showFiltersPanel && floatSidePanels) {
                    FiltersSidePanel(
                        filters = filters,
                        filterableCols = filterableCols,
                        schemaMap = schemaMap,
                        serverErrors = serverFilterErrors,
                        onChangeFilters = { updateFilters(it) },
                        onOpenChooseValues = { col, kind ->
                            activeFilterModal = ActiveFilterModal.ChooseValue(col, kind)
                        },
                        onOpenLookup = { col, kind, fkTable, fkSchema, fkCol ->
                            activeFilterModal = ActiveFilterModal.FkLookup(col, kind, fkTable, fkSchema, fkCol)
                        },
                        onOpenFunnelFor = { col ->
                            openFunnelTarget = FunnelTarget(col)
                        },
                        onClose = { showFiltersPanel = false },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(260.dp)
                            .fillMaxHeight(),
                    )
                }

                // Floating References Panel when tab width < 860 dp
                if (showReferencesPanel && floatSidePanels) {
                    ReferencesSidePanel(
                        references = tableReferences,
                        activeReference = activeReference,
                        defaultSchema = schemaName.ifBlank { "public" },
                        onSelectReference = { activeReference = it },
                        onClose = { showReferencesPanel = false },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(260.dp)
                            .fillMaxHeight(),
                    )
                }

                // Floating Cell Data Panel when tab width < 860 dp
                if (showCellDataPanel && floatSidePanels) {
                    val cellSel = remember(selection, visibleColumns, rows) {
                        collectCellData(selection, visibleColumns, visibleColumns, rows.size) { c, r ->
                            val colKey = visibleColumns.getOrElse(c) { "" }
                            currentValueAt(colKey, r)
                        }
                    }
                    val single = cellSel.cells.singleOrNull()
                    DatabaseCellDataSidePanel(
                        cells = cellSel.cells,
                        columnKeys = visibleColumns,
                        columnNames = visibleColumns,
                        valueAt = { c, r ->
                            val colKey = visibleColumns.getOrElse(c) { "" }
                            currentValueAt(colKey, r)
                        },
                        canEditSingleCell = canEditTable && single != null && !isCellLocked(
                            LockColumn(
                                pk = schemaMap[single.columnKey]?.pk == true,
                                autoIncrement = schemaMap[single.columnKey]?.autoIncrement == true,
                            ),
                            rowIdAt(single.row),
                            changeset,
                        ),
                        onApplySingleCellEdit = { nextVal ->
                            if (single != null) {
                                commitSingleCell(single.row, single.columnKey, nextVal)
                            }
                        },
                        onSaveCellToFile = {
                            if (single != null) {
                                onSaveCellToFile(single.columnKey, rowKeyMapFor(single.row), single.value)
                            }
                        },
                        onCopyText = { txt -> copyPlainToClipboard(context, "Cell Data", txt) },
                        onClose = { showCellDataPanel = false },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(280.dp)
                            .fillMaxHeight(),
                    )
                }

                // Phone Column Filter & Sort Bottom Sheet
                val phoneSheetTarget = phoneFilterSheetTarget
                if (mobile && phoneSheetTarget != null) {
                    Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                        PhoneColumnFilterSheet(
                            column = phoneSheetTarget.column,
                            filters = filters,
                            filterableCols = filterableCols,
                            schemaMap = schemaMap,
                            sort = sort,
                            serverErrors = serverFilterErrors,
                            onChangeFilters = { updateFilters(it) },
                            onSortAction = onSortAction,
                            onCopyColumnName = { colName ->
                                copyPlainToClipboard(context, "Column Name", colName)
                            },
                            onHideColumn = { colName ->
                                hiddenColumns = hiddenColumns + colName
                            },
                            onOpenDialog = { col, req ->
                                activeFilterModal = when (req) {
                                    is FilterDialogRequest.ConditionDialog -> ActiveFilterModal.SetFilter(col, req)
                                    is FilterDialogRequest.LinesDialog -> ActiveFilterModal.MultiValues(col)
                                }
                            },
                            onOpenChooseValues = { col, kind ->
                                activeFilterModal = ActiveFilterModal.ChooseValue(col, kind)
                            },
                            onOpenLookup = { col, kind, fkTable, fkSchema, fkCol ->
                                activeFilterModal = ActiveFilterModal.FkLookup(col, kind, fkTable, fkSchema, fkCol)
                            },
                            onClose = { phoneFilterSheetTarget = null },
                        )
                    }
                }

                // Phone Columns & Filters Bottom Sheet
                if (mobile && showPhoneColsAndFiltersSheet) {
                    Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                        PhoneColumnsAndFiltersSheet(
                            tableName = tableName,
                            columns = columns,
                            hiddenColumns = hiddenColumns,
                            schemaMap = schemaMap,
                            filters = filters,
                            filterableCols = filterableCols,
                            onToggleColumnVisibility = { col ->
                                hiddenColumns = if (col in hiddenColumns) hiddenColumns - col else hiddenColumns + col
                            },
                            onShowAllColumns = { hiddenColumns = emptySet() },
                            onChangeFilters = { updateFilters(it) },
                            onOpenColumnFilterSheet = { col ->
                                phoneFilterSheetTarget = FunnelTarget(col)
                            },
                            onClose = { showPhoneColsAndFiltersSheet = false },
                        )
                    }
                }

                // 9d.5 Phone Row Form Bottom Sheet (opened when tapping a cell or + New row on phone)
                val activePhoneRow = phoneFormRowIndex
                if (mobile && activePhoneRow != null && activePhoneRow in rows.indices) {
                    Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                        PhoneRowFormSheet(
                            rowIndex = activePhoneRow,
                            rowCount = rows.size,
                            rowId = rowIdAt(activePhoneRow),
                            columns = columns,
                            schemaMap = schemaMap,
                            dbType = dbType,
                            changeset = changeset,
                            readonly = !canEditTable,
                            valueAt = { colKey -> currentValueAt(colKey, activePhoneRow) },
                            onCommitField = { rIdx, colKey, nextVal ->
                                commitSingleCell(rIdx, colKey, nextVal)
                            },
                            onNavigateRow = { nextIdx ->
                                phoneFormRowIndex = nextIdx.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
                            },
                            onDeleteRow = {
                                val rId = rowIdAt(activePhoneRow)
                                pushChangeset(
                                    deleteRows(
                                        changeset,
                                        listOf(rId),
                                        mapOf(rId to rowKeyMapFor(activePhoneRow)),
                                    ),
                                )
                                phoneFormRowIndex = null
                            },
                            onRevertRow = {
                                pushChangeset(revertRows(changeset, listOf(rowIdAt(activePhoneRow))))
                            },
                            onOpenFkPicker = { colKey, sch ->
                                val fkT = sch.fkTable ?: return@PhoneRowFormSheet
                                cellFkLookupTarget = CellFkLookupTarget(
                                    rowIndex = activePhoneRow,
                                    column = colKey,
                                    fkTable = fkT,
                                    fkSchema = sch.fkSchema,
                                    fkColumn = sch.fkColumn ?: "id",
                                )
                            },
                            onJumpToFkRow = { _, sch, v ->
                                val fkT = sch.fkTable ?: return@PhoneRowFormSheet
                                val fkC = sch.fkColumn ?: "id"
                                val f = referencedRowFilters(fkC, v, classifyColumnType(dbType, sch.type))
                                if (f != null) onOpenReferencedTableTab(fkT, sch.fkSchema, f)
                            },
                            onSaveChanges = {
                                phoneFormRowIndex = null
                                triggerSaveFlow()
                            },
                            onClose = { phoneFormRowIndex = null },
                        )
                    }
                }

                // Phone Long-Press Cell Bottom Sheet Menu (with in-place submenu + Back)
                val phoneCell = phoneLongPressCell
                if (mobile && phoneCell != null) {
                    val colName = visibleColumns.getOrNull(phoneCell.first).orEmpty()
                    val cellVal = currentValueAt(colName, phoneCell.second)
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(tokens.panel.color)
                            .cssBorder(tokens.border.color, sides = CssSides.Top)
                            .padding(12.dp)
                            .testTag("db-phone-cell-sheet"),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (phoneSheetSubmenu == "copyAs") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SettingsButton(
                                    label = "← Back",
                                    modifier = Modifier.testTag("db-phone-sheet-back"),
                                    onClick = { phoneSheetSubmenu = null },
                                )
                                BasicText(
                                    "Copy advanced",
                                    style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color),
                                )
                            }
                            for (fmt in CopyFormat.entries) {
                                SettingsButton(
                                    label = fmt.label,
                                    modifier = Modifier.fillMaxWidth().testTag("db-phone-copy-${fmt.id}"),
                                    onClick = {
                                        copySelectionInFormat(fmt)
                                        phoneLongPressCell = null
                                    },
                                )
                            }
                        } else {
                            BasicText(
                                "$colName · Row ${phoneCell.second + 1}: ${formatCellDisplayText(cellVal)}",
                                style = ppmText(11f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 15f)
                                    .copy(color = tokens.text.color),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                SettingsButton(
                                    label = "Edit row",
                                    primary = true,
                                    modifier = Modifier.testTag("db-phone-edit-row-btn"),
                                    onClick = {
                                        phoneFormRowIndex = phoneCell.second
                                        phoneLongPressCell = null
                                    },
                                )
                                SettingsButton(
                                    label = "Copy cell",
                                    modifier = Modifier.testTag("db-phone-copy-cell-btn"),
                                    onClick = {
                                        copyPlainToClipboard(context, colName, formatCellCopyText(cellVal))
                                        phoneLongPressCell = null
                                    },
                                )
                                SettingsButton(
                                    label = "Copy advanced ▸",
                                    modifier = Modifier.testTag("db-phone-copy-advanced-btn"),
                                    onClick = { phoneSheetSubmenu = "copyAs" },
                                )
                                SettingsButton(
                                    label = "Cell data",
                                    onClick = {
                                        showCellDataPanel = true
                                        phoneLongPressCell = null
                                    },
                                )
                                SettingsButton(
                                    label = "Close",
                                    onClick = { phoneLongPressCell = null },
                                )
                            }
                        }
                    }
                }
            }

            // 3. Phone Bottom Action Bar (`New row` / `Filters` / `Save`)
            if (mobile) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.panel2.color)
                        .cssBorder(tokens.border.color, sides = CssSides.Top)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("db-phone-bottom-bar"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingsButton(
                        label = "+ New row",
                        enabled = canEditTable,
                        modifier = Modifier.testTag("db-phone-new-row-btn"),
                        onClick = {
                            if (canEditTable) {
                                val rIdx = insertBlankRow()
                                phoneFormRowIndex = rIdx
                            }
                        },
                    )
                    SettingsButton(
                        label = "Filters",
                        primary = showPhoneColsAndFiltersSheet || hasFilters(filters),
                        modifier = Modifier.testTag("db-phone-filters-btn"),
                        onClick = { showPhoneColsAndFiltersSheet = !showPhoneColsAndFiltersSheet },
                    )
                    SettingsButton(
                        label = if (changedCount > 0) "Save ($changedCount)" else "Save",
                        primary = changedCount > 0,
                        enabled = canEditTable,
                        modifier = Modifier.testTag("db-phone-save-btn"),
                        onClick = { triggerSaveFlow() },
                    )
                }
            }
        }
    }
}

@Composable
private fun GridColumnsSidePanel(
    columns: List<String>,
    schemaMap: Map<String, GridColumnSchema>,
    hiddenColumns: Set<String>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onToggleColumn: (String) -> Unit,
    onShowAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val filteredCols = remember(columns, searchQuery) {
        columns.filter { columnNameMatches(searchQuery, it) }
    }

    Column(
        modifier = modifier
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, sides = CssSides.End)
            .padding(8.dp)
            .testTag("db-columns-side-panel"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                "COLUMNS (${columns.size - hiddenColumns.size}/${columns.size})",
                style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f).copy(color = tokens.text2.color),
            )
            if (hiddenColumns.isNotEmpty()) {
                SettingsButton(
                    label = "Show all",
                    modifier = Modifier.testTag("db-columns-show-all"),
                    onClick = onShowAll,
                )
            }
        }
        SettingsTextInput(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = "Filter columns (e.g. CA, id)…",
            mono = true,
            modifier = Modifier.fillMaxWidth().testTag("db-columns-search-input"),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (col in filteredCols) {
                val visible = col !in hiddenColumns
                val sch = schemaMap[col]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (visible) tokens.panel2.color else Color.Transparent)
                        .clickable { onToggleColumn(col) }
                        .padding(horizontal = 6.dp, vertical = 5.dp)
                        .testTag("db-col-toggle-$col"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BasicText(
                        if (visible) "☑" else "☐",
                        style = ppmText(12f, lineHeight = 15f).copy(
                            color = if (visible) tokens.accent.color else tokens.text3.color,
                        ),
                    )
                    BasicText(
                        col,
                        style = ppmText(
                            11f,
                            if (sch?.nullable == false) FontWeight.Bold else FontWeight.Normal,
                            FontKind.MONO,
                            lineHeight = 14f,
                        ).copy(color = tokens.text.color),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!sch?.type.isNullOrBlank()) {
                        BasicText(
                            sch?.type.orEmpty(),
                            style = ppmText(10f, kind = FontKind.MONO, lineHeight = 13f).copy(color = tokens.text3.color),
                        )
                    }
                    if (!sch?.fkTable.isNullOrBlank()) {
                        BasicText(
                            "→ ${sch?.fkTable}",
                            style = ppmText(10f, kind = FontKind.MONO, lineHeight = 13f).copy(color = tokens.accent.color),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GridCellDataSidePanel(
    columnName: String?,
    value: DbCellValue,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val fullText = remember(value) { formatCellDisplayText(value, truncateForGrid = false) }

    Column(
        modifier = modifier
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, sides = CssSides.Start)
            .padding(8.dp)
            .testTag("db-cell-data-side-panel"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                "CELL DATA${if (columnName != null) " · $columnName" else ""}",
                style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f).copy(color = tokens.text2.color),
            )
            SettingsButton(label = "✕", onClick = onClose)
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(tokens.bgSolid.color)
                .cssBorder(tokens.border.color)
                .verticalScroll(rememberScrollState())
                .padding(8.dp)
                .testTag("db-cell-data-content"),
        ) {
            BasicText(
                fullText,
                style = ppmText(11f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text.color),
            )
        }
    }
}

private fun copyPlainToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}
