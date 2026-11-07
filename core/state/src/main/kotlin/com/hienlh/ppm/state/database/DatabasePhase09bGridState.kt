package com.hienlh.ppm.state.database

import java.text.NumberFormat
import java.util.Base64
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Phase 09b pure state & domain models for the Database Table Grid:
 * - Array-row storage + `uniqueColumnKeys` (`db-grid.ts`)
 * - Exact JSON value preservation (`"5"` != `5`, bigint > 2^53, `NaN`/`±Infinity`, `$binary` preview + hex) (`cell-display.ts`)
 * - Header band geometry, multi-column sort, and column width layout (`header-bands.ts`)
 * - 2D selection ranges, whole rows/columns, and `Rows / Count / Sum` statistics (`selection-stats.ts`)
 * - 9-format Copy As (`textWithHeaders`, `textWithoutHeaders`, `headers`, `csv`, `json`, `jsonLines`, `yaml`, `inserts`, `updates`) + dialect-aware `sqlLiteral` (`copy-as.ts`, `sql-identifiers.ts`, `db-column-kind.ts`)
 * - Infinite scroll `loadMoreDecision`, `Fetch all` chunking (5,000 rows), `RowCountView` (`Rows: N / ~estimate / Many`), and first-page session cache (`load-more.ts`, `use-database.ts`)
 * - Quick Export formats and safe filenames (`db-grid-export.ts`)
 */

const val GRID_PAGE_SIZE = 100
const val FETCH_ALL_CHUNK = 5_000
const val GRID_MAX_LIMIT = 10_000
const val CELL_PREVIEW_TRUNCATE_CHARS = 200
const val FLOATING_PANEL_TAB_WIDTH_DP = 860

// ── 9b.2 Column Keys & Cell Values (`db-grid.ts`, `cell-display.ts`) ─────────

const val NULL_TEXT = "(NULL)"
const val NO_FIELD_TEXT = "(No Field)"
const val HEX_BYTES = 8

/**
 * Keys that tell apart columns sharing a name: the first `id` stays `id`, the next becomes `id (2)`.
 * A key that would collide with a real column name moves on to the next number.
 */
fun uniqueColumnKeys(names: List<String>): List<String> {
    val taken = names.toHashSet()
    val used = HashSet<String>(names.size)
    return names.map { name ->
        if (used.add(name)) {
            name
        } else {
            var n = 2
            while ("$name ($n)" in used || "$name ($n)" in taken) {
                n++
            }
            val key = "$name ($n)"
            used += key
            key
        }
    }
}

sealed interface DbCellValue {
    data object Null : DbCellValue
    data object NoField : DbCellValue
    data class Bool(val value: Boolean) : DbCellValue
    /** Number kept with its exact wire text (`rawText`) so precision is never lost via Double. */
    data class Num(val rawText: String, val asDouble: Double?) : DbCellValue
    /** Plain string value (including bigint > 2^53 strings, ISO dates, or `"NaN"` / `"Infinity"`). */
    data class Text(val value: String) : DbCellValue
    /** Binary value `{ $binary, size, truncated? }`. */
    data class Binary(
        val base64: String,
        val size: Long,
        val truncated: Boolean = false,
    ) : DbCellValue
    data class JsonArr(val elements: List<DbCellValue>, val rawJson: String) : DbCellValue
    data class JsonObj(val rawJson: String) : DbCellValue
}

fun parseDbCellValue(el: JsonElement?): DbCellValue {
    if (el == null || el is JsonNull) return DbCellValue.Null
    return when (el) {
        is JsonPrimitive -> {
            if (el.isString) {
                DbCellValue.Text(el.content)
            } else {
                val b = el.booleanOrNull
                if (b != null) {
                    DbCellValue.Bool(b)
                } else {
                    DbCellValue.Num(rawText = el.content, asDouble = el.doubleOrNull)
                }
            }
        }
        is JsonObject -> {
            val bin = (el["\$binary"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val size = (el["size"] as? JsonPrimitive)?.longOrNull
            if (bin != null && size != null) {
                val trunc = (el["truncated"] as? JsonPrimitive)?.booleanOrNull == true
                DbCellValue.Binary(base64 = bin, size = size, truncated = trunc)
            } else {
                DbCellValue.JsonObj(el.toString())
            }
        }
        is JsonArray -> {
            DbCellValue.JsonArr(
                elements = el.map { parseDbCellValue(it) },
                rawJson = el.toString(),
            )
        }
        else -> DbCellValue.Text(el.toString())
    }
}

/** `512 bytes`, `1 byte`, `12.1 KB`, `3.4 MB`, `1.2 GB`. */
fun formatByteSize(size: Long): String = when {
    size < 1024L -> "$size ${if (size == 1L) "byte" else "bytes"}"
    size < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", size / 1024.0)
    size < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", size / (1024.0 * 1024.0))
    else -> String.format(Locale.US, "%.1f GB", size / (1024.0 * 1024.0 * 1024.0))
}

/** The first [count] bytes of a base64 text, without decoding the rest of it. */
fun leadingBytes(base64: String, count: Int = HEX_BYTES): List<Int> {
    if (base64.isEmpty() || count <= 0) return emptyList()
    val headLen = min(base64.length, ceil(count / 3.0).toInt() * 4)
    val head = base64.substring(0, headLen)
    val decoded = try {
        Base64.getDecoder().decode(head)
    } catch (_: Throwable) {
        return emptyList()
    }
    val limit = min(count, decoded.size)
    return (0 until limit).map { decoded[it].toInt() and 0xFF }
}

/** `12.1 KB · 89 50 4E 47 0D 0A 1A 0A…` — empty bytes are just their size. */
fun formatBinary(value: DbCellValue.Binary): String {
    val sizeText = formatByteSize(value.size)
    if (value.size == 0L) return sizeText
    val hex = leadingBytes(value.base64, HEX_BYTES)
        .joinToString(" ") { it.toString(16).uppercase(Locale.US).padStart(2, '0') }
    if (hex.isEmpty()) return sizeText
    return "$sizeText · $hex${if (value.size > HEX_BYTES) "…" else ""}"
}

/** Display text for a grid cell or form field; truncates to 200 chars when [truncateForGrid] is true. */
fun formatCellDisplayText(value: DbCellValue, truncateForGrid: Boolean = true): String {
    val full = when (value) {
        DbCellValue.NoField -> NO_FIELD_TEXT
        DbCellValue.Null -> NULL_TEXT
        is DbCellValue.Bool -> value.value.toString()
        is DbCellValue.Num -> value.rawText
        is DbCellValue.Text -> value.value
        is DbCellValue.Binary -> formatBinary(value)
        is DbCellValue.JsonArr -> value.rawJson
        is DbCellValue.JsonObj -> value.rawJson
    }
    if (!truncateForGrid || full.length <= CELL_PREVIEW_TRUNCATE_CHARS) return full
    return full.substring(0, CELL_PREVIEW_TRUNCATE_CHARS) + "…"
}

/** What an editor opens with: empty for NULL/NoField, JSON for objects/arrays, raw text otherwise. */
fun formatCellEditText(value: DbCellValue): String = when (value) {
    DbCellValue.Null, DbCellValue.NoField -> ""
    is DbCellValue.Bool -> value.value.toString()
    is DbCellValue.Num -> value.rawText
    is DbCellValue.Text -> value.value
    is DbCellValue.Binary -> formatBinary(value)
    is DbCellValue.JsonArr -> value.rawJson
    is DbCellValue.JsonObj -> value.rawJson
}

/** What Ctrl+C copies from a cell: empty for NULL/NoField, formatted binary for Binary, raw otherwise. */
fun formatCellCopyText(value: DbCellValue): String = when (value) {
    DbCellValue.Null, DbCellValue.NoField -> ""
    is DbCellValue.Binary -> formatBinary(value)
    else -> formatCellEditText(value)
}

// ── 9b.2 Grid Response, Schema & Row Count (`use-database.ts`) ───────────────

data class ResultColumnMeta(
    val name: String,
    val type: String,
)

data class GridColumnSchema(
    val name: String,
    val type: String,
    val nullable: Boolean = true,
    val pk: Boolean = false,
    val defaultValue: String? = null,
    val autoIncrement: Boolean = false,
    val fkTable: String? = null,
    val fkColumn: String? = null,
)

/**
 * Compact page data: [rows] is a list of value arrays (`List<DbCellValue>`) aligned with [columnKeys]
 * so repeated column names survive and 100k rows stay compact in phone RAM.
 */
data class DbGridPageData(
    val rawColumns: List<ResultColumnMeta>,
    val columnKeys: List<String>,
    val rows: List<List<DbCellValue>>,
    val hasMore: Boolean,
    val sql: String = "",
    val rowKey: List<String> = emptyList(),
    val countKey: String = "",
) {
    fun cellAt(colIndex: Int, rowIndex: Int): DbCellValue {
        val row = rows.getOrNull(rowIndex) ?: return DbCellValue.Null
        return row.getOrNull(colIndex) ?: DbCellValue.Null
    }

    fun rowAsMap(rowIndex: Int): Map<String, DbCellValue> {
        val row = rows.getOrNull(rowIndex) ?: return emptyMap()
        val map = LinkedHashMap<String, DbCellValue>(columnKeys.size)
        columnKeys.forEachIndexed { idx, key ->
            map[key] = row.getOrNull(idx) ?: DbCellValue.Null
        }
        return map
    }
}

fun parseGridResponse(obj: JsonObject, countKey: String = ""): DbGridPageData {
    val rawCols = ((obj["columns"] as? JsonArray).orEmpty()).mapNotNull { el ->
        val c = el as? JsonObject ?: return@mapNotNull null
        val name = (c["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val type = (c["type"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        ResultColumnMeta(name = name, type = type)
    }
    val keys = uniqueColumnKeys(rawCols.map { it.name })
    val rows = ((obj["rows"] as? JsonArray).orEmpty()).map { rowEl ->
        val arr = rowEl as? JsonArray ?: return@map List(keys.size) { DbCellValue.Null }
        List(keys.size) { idx -> parseDbCellValue(arr.getOrNull(idx)) }
    }
    val hasMore = (obj["hasMore"] as? JsonPrimitive)?.booleanOrNull == true
    val sql = (obj["sql"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val rowKey = ((obj["rowKey"] as? JsonArray).orEmpty()).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    return DbGridPageData(
        rawColumns = rawCols,
        columnKeys = keys,
        rows = rows,
        hasMore = hasMore,
        sql = sql,
        rowKey = rowKey,
        countKey = countKey,
    )
}

fun parseGridColumnsSchema(arr: JsonArray): List<GridColumnSchema> =
    arr.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val fkObj = o["fk"] as? JsonObject
        GridColumnSchema(
            name = name,
            type = (o["type"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            nullable = (o["nullable"] as? JsonPrimitive)?.booleanOrNull ?: true,
            pk = (o["pk"] as? JsonPrimitive)?.booleanOrNull == true,
            defaultValue = (o["defaultValue"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull,
            autoIncrement = (o["autoIncrement"] as? JsonPrimitive)?.booleanOrNull == true,
            fkTable = (fkObj?.get("table") as? JsonPrimitive)?.contentOrNull,
            fkColumn = (fkObj?.get("column") as? JsonPrimitive)?.contentOrNull,
        )
    }

sealed interface RowTotal {
    data class Exact(val count: Long) : RowTotal
    data class Estimate(val count: Long) : RowTotal
    data class AtLeast(val count: Long) : RowTotal
    data object Many : RowTotal
}

data class RowCountState(
    val key: String,
    val count: Long? = null,
    val estimate: Long? = null,
    val pending: Boolean = false,
    val timedOut: Boolean = false,
    val failed: String? = null,
)

data class RowCountView(
    val text: String,
    val counting: Boolean,
    val canCountExactly: Boolean,
    val title: String? = null,
    val total: RowTotal,
)

private fun fmtCount(n: Long): String = NumberFormat.getIntegerInstance(Locale.US).format(n)

fun computeRowCountView(
    data: DbGridPageData?,
    count: RowCountState?,
): RowCountView? {
    if (data == null) return null
    val loaded = data.rows.size.toLong()
    if (!data.hasMore) {
        return RowCountView(
            text = "Rows: ${fmtCount(loaded)}",
            counting = false,
            canCountExactly = false,
            total = RowTotal.Exact(loaded),
        )
    }
    val c = count?.takeIf { it.key == data.countKey }
    if (c?.count != null) {
        val exact = max(c.count, loaded)
        return RowCountView(
            text = "Rows: ${fmtCount(exact)}",
            counting = false,
            canCountExactly = false,
            total = RowTotal.Exact(exact),
        )
    }
    val estimate = c?.estimate?.takeIf { it > loaded }
    if (c != null && !c.pending && (c.timedOut || c.failed != null)) {
        return RowCountView(
            text = if (estimate != null) "Rows: ~${fmtCount(estimate)}" else "Rows: Many",
            counting = false,
            canCountExactly = true,
            title = if (c.failed != null) {
                "The rows could not be counted: ${c.failed}. Click to try again."
            } else {
                "Counting took too long, so this is not exact. Click to count every row."
            },
            total = if (estimate != null) RowTotal.Estimate(estimate) else RowTotal.Many,
        )
    }
    return RowCountView(
        text = if (estimate != null) "Rows: ~${fmtCount(estimate)}" else "Rows: ${fmtCount(loaded)}+",
        counting = c?.pending == true,
        canCountExactly = false,
        title = if (estimate != null) {
            "The database's estimate, until the rows are counted"
        } else {
            "Rows loaded so far, until they are counted"
        },
        total = if (estimate != null) RowTotal.Estimate(estimate) else RowTotal.AtLeast(loaded),
    )
}

fun reloadKeepLoadedLimit(loadedRows: Int): Int =
    loadedRows.coerceIn(GRID_PAGE_SIZE, GRID_MAX_LIMIT)

// ── 9b.2 Load More Decision & First-Page Cache (`load-more.ts`) ──────────────

enum class LoadMoreDecision {
    LOAD,
    IDLE,
    BLOCKED_BY_NEW_ROWS,
}

fun loadMoreDecision(
    lastVisibleRow: Int,
    loadedRows: Int,
    hasMore: Boolean,
    busy: Boolean,
    newRows: Int,
): LoadMoreDecision {
    if (!hasMore || busy || lastVisibleRow < loadedRows - 1) return LoadMoreDecision.IDLE
    return if (newRows > 0) LoadMoreDecision.BLOCKED_BY_NEW_ROWS else LoadMoreDecision.LOAD
}

data class CachedFirstPage(
    val data: DbGridPageData,
    val schema: List<GridColumnSchema>,
)

object TableFirstPageCache {
    private const val MAX_ENTRIES = 32
    private val map = object : LinkedHashMap<String, CachedFirstPage>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedFirstPage>?): Boolean =
            size > MAX_ENTRIES
    }

    private fun key(dbKey: String, schema: String, table: String): String =
        "ppm-db-$dbKey-$schema.$table-p1"

    @Synchronized
    fun read(dbKey: String, schema: String, table: String): CachedFirstPage? =
        map[key(dbKey, schema, table)]

    @Synchronized
    fun write(dbKey: String, schema: String, table: String, data: DbGridPageData, cols: List<GridColumnSchema>) {
        map[key(dbKey, schema, table)] = CachedFirstPage(data = data, schema = cols)
    }

    @Synchronized
    fun clear() {
        map.clear()
    }
}

// ── 9b.1 & 9b.3 Header Bands, Sort & Column Layout (`header-bands.ts`) ───────

const val TITLE_BAND_DP = 34
const val TITLE_BAND_TOUCH_DP = 44
const val FILTER_BAND_DP = 30
const val MENU_BUTTON_ROOM_PX = 24

fun rowMarkerWidthPx(rows: Int): Int = when {
    rows > 10_000 -> 48
    rows > 1_000 -> 44
    rows > 100 -> 36
    else -> 32
}

enum class TitleIcon {
    HEADER_AUTO,
    HEADER_KEY,
    HEADER_FK,
}

fun titleIconFor(pk: Boolean, hasFk: Boolean, autoIncrement: Boolean = false): TitleIcon? {
    if (pk) return if (autoIncrement) TitleIcon.HEADER_AUTO else TitleIcon.HEADER_KEY
    return if (hasFk) TitleIcon.HEADER_FK else null
}

data class GridSortItem(
    val column: String,
    val dir: String, // "ASC" | "DESC"
)

enum class SortMenuAction {
    SORT_ASC,
    SORT_DESC,
    ADD_ASC,
    ADD_DESC,
    CLEAR_SORT,
}

fun applySortAction(
    current: List<GridSortItem>,
    column: String,
    action: SortMenuAction,
): List<GridSortItem> = when (action) {
    SortMenuAction.SORT_ASC -> listOf(GridSortItem(column, "ASC"))
    SortMenuAction.SORT_DESC -> listOf(GridSortItem(column, "DESC"))
    SortMenuAction.ADD_ASC -> {
        val idx = current.indexOfFirst { it.column == column }
        if (idx >= 0) current.toMutableList().apply { this[idx] = GridSortItem(column, "ASC") }
        else current + GridSortItem(column, "ASC")
    }
    SortMenuAction.ADD_DESC -> {
        val idx = current.indexOfFirst { it.column == column }
        if (idx >= 0) current.toMutableList().apply { this[idx] = GridSortItem(column, "DESC") }
        else current + GridSortItem(column, "DESC")
    }
    SortMenuAction.CLEAR_SORT -> emptyList()
}

data class TitleSortInfo(
    val dir: String,
    val index: Int?,
)

fun sortInfoForColumn(sort: List<GridSortItem>, column: String): TitleSortInfo? {
    val idx = sort.indexOfFirst { it.column == column }
    if (idx < 0) return null
    return TitleSortInfo(
        dir = sort[idx].dir,
        index = if (sort.size > 1) idx + 1 else null,
    )
}

data class TitleLayoutResult(
    val iconX: Float?,
    val nameX: Float,
    val name: String,
    val typeX: Float,
    val type: String,
    val arrowX: Float?,
    val indexX: Float?,
    val index: String,
)

fun fitTextWithEllipsis(
    text: String,
    maxWidth: Float,
    measure: (String) -> Float,
): String {
    if (measure(text) <= maxWidth) return text
    val ellipsis = "…"
    if (measure(ellipsis) > maxWidth) return ""
    var lo = 0
    var hi = text.length
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (measure(text.substring(0, mid) + ellipsis) <= maxWidth) {
            lo = mid
        } else {
            hi = mid - 1
        }
    }
    return if (lo == 0) ellipsis else text.substring(0, lo) + ellipsis
}

fun layoutHeaderTitle(
    name: String,
    type: String,
    notNull: Boolean,
    icon: TitleIcon?,
    sort: TitleSortInfo?,
    widthPx: Float,
    measureName: (String, Boolean) -> Float,
    measureType: (String) -> Float,
    measureIndex: (String) -> Float,
): TitleLayoutResult {
    val pad = 8f
    val iconSize = 14f
    val gap = 5f
    val arrowSize = 10f
    val minType = 18f
    var x = pad
    val end = widthPx - MENU_BUTTON_ROOM_PX
    val iconX = if (icon != null) x else null
    if (icon != null) x += iconSize + gap
    val indexText = sort?.index?.toString().orEmpty()
    val sortWidth = if (sort != null) arrowSize + (if (indexText.isNotEmpty()) 1f + measureIndex(indexText) else 0f) else 0f
    val room = max(0f, end - x - (if (sort != null) gap + sortWidth else 0f))
    val fittedName = fitTextWithEllipsis(name, room) { measureName(it, notNull) }
    val nameWidth = if (fittedName.isNotEmpty()) measureName(fittedName, notNull) else 0f
    val nameX = x
    x += nameWidth
    var fittedType = ""
    var typeX = x
    if (sort == null && type.isNotEmpty() && fittedName == name) {
        val typeRoom = room - nameWidth - gap
        if (typeRoom >= minType) {
            fittedType = fitTextWithEllipsis(type, typeRoom, measureType)
            typeX = x + gap
        }
    }
    val arrowX = if (sort != null) x + gap else null
    val indexX = if (sort != null && indexText.isNotEmpty() && arrowX != null) arrowX + arrowSize + 1f else null
    return TitleLayoutResult(
        iconX = iconX,
        nameX = nameX,
        name = fittedName,
        typeX = typeX,
        type = fittedType,
        arrowX = arrowX,
        indexX = indexX,
        index = indexText,
    )
}

// ── 9b.3 Selection & Selection Stats (`selection-stats.ts`) ──────────────────

data class GridRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

data class GridSelectionState(
    val ranges: List<GridRect> = emptyList(),
    val selectedColumns: Set<Int> = emptySet(),
    val selectedRows: Set<Int> = emptySet(),
    val activeCell: Pair<Int, Int>? = null, // (col, row)
) {
    val isEmpty: Boolean
        get() = ranges.isEmpty() && selectedColumns.isEmpty() && selectedRows.isEmpty() && activeCell == null

    fun EffectiveRanges(): List<GridRect> =
        if (ranges.isNotEmpty()) ranges
        else if (activeCell != null) listOf(GridRect(activeCell.first, activeCell.second, 1, 1))
        else emptyList()
}

data class SelectionStats(
    val rows: Int,
    val count: Int,
    val sum: Double?,
)

private val NUMERIC_STRING_REGEX = Regex("""^[-+]?(\d+\.?\d*|\.\d+)(e[-+]?\d+)?$""", RegexOption.IGNORE_CASE)

fun numericValueOf(value: DbCellValue): Double? = when (value) {
    is DbCellValue.Num -> value.asDouble?.takeIf { it.isFinite() }
    is DbCellValue.Text -> {
        val trimmed = value.value.trim()
        if (trimmed.isNotEmpty() && NUMERIC_STRING_REGEX.matches(trimmed)) {
            trimmed.toDoubleOrNull()?.takeIf { it.isFinite() }
        } else {
            null
        }
    }
    else -> null
}

fun forEachSelectedCell(
    sel: GridSelectionState,
    columns: Int,
    rows: Int,
    visit: (column: Int, row: Int) -> Unit,
) {
    val rects = sel.EffectiveRanges()
    val cols = sel.selectedColumns.sorted()
    val rowList = sel.selectedRows.sorted()
    val kinds = (if (rects.isNotEmpty()) 1 else 0) + (if (cols.isNotEmpty()) 1 else 0) + (if (rowList.isNotEmpty()) 1 else 0)
    val seen = if (rects.size > 1 || kinds > 1) HashSet<Long>() else null
    fun add(c: Int, r: Int) {
        if (c < 0 || r < 0 || c >= columns || r >= rows) return
        if (seen != null) {
            val id = r.toLong() * columns.toLong() + c.toLong()
            if (!seen.add(id)) return
        }
        visit(c, r)
    }
    for (rect in rects) {
        for (r in rect.y until rect.y + rect.height) {
            for (c in rect.x until rect.x + rect.width) {
                add(c, r)
            }
        }
    }
    for (c in cols) {
        for (r in 0 until rows) add(c, r)
    }
    for (r in rowList) {
        for (c in 0 until columns) add(c, r)
    }
}

fun computeSelectionStats(
    sel: GridSelectionState,
    columns: Int,
    rows: Int,
    valueAt: (column: Int, row: Int) -> DbCellValue,
): SelectionStats? {
    val rowSet = HashSet<Int>()
    var count = 0
    var sum = 0.0
    var numbers = 0
    forEachSelectedCell(sel, columns, rows) { c, r ->
        count++
        rowSet += r
        val n = numericValueOf(valueAt(c, r)) ?: return@forEachSelectedCell
        sum += n
        numbers++
    }
    if (count < 2) return null
    val roundedSum = if (numbers > 0) (sum * 1e10).roundToLong() / 1e10 else null
    return SelectionStats(rows = rowSet.size, count = count, sum = roundedSum)
}

fun selectionHasCell(sel: GridSelectionState, column: Int, row: Int): Boolean {
    if (column in sel.selectedColumns || row in sel.selectedRows) return true
    return sel.EffectiveRanges().any { r ->
        column >= r.x && column < r.x + r.width && row >= r.y && row < r.y + r.height
    }
}

data class SelectedBlock(
    val rows: List<Int>,
    val columns: List<Int>,
)

fun selectedBlock(sel: GridSelectionState, columns: Int, rows: Int): SelectedBlock {
    val rowSet = HashSet<Int>()
    val colSet = HashSet<Int>()
    fun inRange(c: Int, r: Int) = c in 0 until columns && r in 0 until rows
    for (rect in sel.EffectiveRanges()) {
        for (r in rect.y until rect.y + rect.height) {
            for (c in rect.x until rect.x + rect.width) {
                if (inRange(c, r)) {
                    rowSet += r
                    colSet += c
                }
            }
        }
    }
    val wholeCols = sel.selectedColumns.filter { it in 0 until columns }
    val wholeRows = sel.selectedRows.filter { it in 0 until rows }
    if (wholeCols.isNotEmpty() && rows > 0) {
        colSet.addAll(wholeCols)
        for (r in 0 until rows) rowSet += r
    }
    if (wholeRows.isNotEmpty() && columns > 0) {
        rowSet.addAll(wholeRows)
        for (c in 0 until columns) colSet += c
    }
    return SelectedBlock(
        rows = rowSet.sorted(),
        columns = colSet.sorted(),
    )
}

fun selectedRowIndices(sel: GridSelectionState, rows: Int): List<Int> {
    val set = HashSet<Int>()
    for (rect in sel.EffectiveRanges()) {
        for (r in rect.y until rect.y + rect.height) {
            if (r in 0 until rows) set += r
        }
    }
    for (r in sel.selectedRows) {
        if (r in 0 until rows) set += r
    }
    return set.sorted()
}

// ── 9b.4 Column Kind, SQL Identifiers & Copy As (`copy-as.ts`) ───────────────

enum class ColumnKind {
    TEXT,
    NUMBER,
    BOOLEAN,
    DATE,
    DATETIME,
    DATETIMETZ,
    TIME,
    BINARY,
    JSON,
    OTHER,
}

private val PG_TEXT_REGEX = Regex("""^(character varying|varchar|character|char|bpchar|text|name|citext)\b""")
private val PG_NUM_REGEX = Regex("""^(smallint|integer|bigint|int[248]?|numeric|decimal|real|double precision|float[48]?|money|oid|smallserial|serial|bigserial)\b""")
private val PG_TIMESTAMPTZ_REGEX = Regex("""^timestamp(\(\d+\))? with time zone$""")
private val MYSQL_BOOL_REGEX = Regex("""^(tinyint\(1\)|bool|boolean|bit\(1\))(\s|$)""")
private val MYSQL_NUM_REGEX = Regex("""^(tinyint|smallint|mediumint|int|integer|bigint|decimal|numeric|dec|fixed|float|double|real|bit|year)\b""")
private val MYSQL_TEXT_REGEX = Regex("""^(char|varchar|tinytext|text|mediumtext|longtext|enum|set|national)\b""")
private val MYSQL_DATETIME_REGEX = Regex("""^(datetime|timestamp)\b""")
private val MYSQL_BINARY_REGEX = Regex("""^(binary|varbinary|tinyblob|blob|mediumblob|longblob)\b""")

fun classifyPostgresType(type: String): ColumnKind {
    val t = type.lowercase(Locale.US)
    if (t.endsWith("[]")) return ColumnKind.OTHER
    if (PG_TEXT_REGEX.containsMatchIn(t)) return ColumnKind.TEXT
    if (PG_NUM_REGEX.containsMatchIn(t)) return ColumnKind.NUMBER
    if (t == "boolean" || t == "bool") return ColumnKind.BOOLEAN
    if (t == "date") return ColumnKind.DATE
    if (PG_TIMESTAMPTZ_REGEX.matches(t) || t == "timestamptz") return ColumnKind.DATETIMETZ
    if (t.startsWith("timestamp")) return ColumnKind.DATETIME
    if (t.startsWith("time")) return ColumnKind.TIME
    if (t == "bytea") return ColumnKind.BINARY
    if (t == "json" || t == "jsonb") return ColumnKind.JSON
    return ColumnKind.OTHER
}

fun classifySqliteType(type: String): ColumnKind {
    val t = type.uppercase(Locale.US)
    if (t.contains("BOOL")) return ColumnKind.BOOLEAN
    if (t == "DATE") return ColumnKind.DATE
    if (t.contains("DATETIME") || t.contains("TIMESTAMP")) return ColumnKind.DATETIME
    if (t == "TIME") return ColumnKind.TIME
    if (t.contains("JSON")) return ColumnKind.JSON
    if (t.contains("INT")) return ColumnKind.NUMBER
    if (t.contains("CHAR") || t.contains("CLOB") || t.contains("TEXT")) return ColumnKind.TEXT
    if (t.isEmpty()) return ColumnKind.OTHER
    if (t.contains("BLOB")) return ColumnKind.BINARY
    if (t.contains("REAL") || t.contains("FLOA") || t.contains("DOUB") || t.contains("NUM") || t.contains("DEC")) {
        return ColumnKind.NUMBER
    }
    return ColumnKind.OTHER
}

fun classifyMysqlType(type: String): ColumnKind {
    val t = type.lowercase(Locale.US).trim()
    if (MYSQL_BOOL_REGEX.containsMatchIn(t)) return ColumnKind.BOOLEAN
    if (MYSQL_NUM_REGEX.containsMatchIn(t)) return ColumnKind.NUMBER
    if (MYSQL_TEXT_REGEX.containsMatchIn(t)) return ColumnKind.TEXT
    if (t == "date") return ColumnKind.DATE
    if (MYSQL_DATETIME_REGEX.containsMatchIn(t)) return ColumnKind.DATETIME
    if (t.startsWith("time")) return ColumnKind.TIME
    if (MYSQL_BINARY_REGEX.containsMatchIn(t)) return ColumnKind.BINARY
    if (t == "json") return ColumnKind.JSON
    return ColumnKind.OTHER
}

fun classifyColumnType(dbType: String, declared: String): ColumnKind = when (dbType.lowercase(Locale.US)) {
    "postgres" -> classifyPostgresType(declared)
    "sqlite" -> classifySqliteType(declared)
    "mysql", "mariadb" -> classifyMysqlType(declared)
    else -> ColumnKind.OTHER
}

fun quoteIdentifier(name: String, dialect: String): String =
    if (dialect == "mysql") "`${name.replace("`", "``")}`"
    else "\"${name.replace("\"", "\"\"")}\""

fun quoteLiteral(value: String, dialect: String): String {
    val text = if (dialect == "mysql") value.replace("\\", "\\\\") else value
    return "'${text.replace("'", "''")}'"
}

private val NUMBER_TEXT_REGEX = Regex("""^-?(\d+\.?\d*|\.\d+)(e[-+]?\d+)?$""", RegexOption.IGNORE_CASE)

private fun base64ToHex(base64: String): String {
    val bytes = try {
        Base64.getDecoder().decode(base64)
    } catch (_: Throwable) {
        ByteArray(0)
    }
    return bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

private fun findTruncatedBinary(items: List<DbCellValue>): DbCellValue.Binary? {
    for (item in items) {
        when (item) {
            is DbCellValue.Binary -> if (item.truncated) return item
            is DbCellValue.JsonArr -> findTruncatedBinary(item.elements)?.let { return it }
            else -> {}
        }
    }
    return null
}

fun postgresArrayText(items: List<DbCellValue>): String =
    "{" + items.joinToString(",") { item ->
        when (item) {
            DbCellValue.Null, DbCellValue.NoField -> "NULL"
            is DbCellValue.JsonArr -> postgresArrayText(item.elements)
            is DbCellValue.Binary -> {
                val elem = "\\x${base64ToHex(item.base64)}"
                "\"${elem.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            }
            is DbCellValue.JsonObj -> {
                "\"${item.rawJson.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            }
            else -> {
                val elem = formatCellEditText(item)
                "\"${elem.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            }
        }
    } + "}"

fun sqlLiteral(value: DbCellValue, dialect: String, kind: ColumnKind? = null): String = when (value) {
    DbCellValue.Null, DbCellValue.NoField -> "NULL"
    is DbCellValue.Bool -> {
        if (dialect == "sqlite") (if (value.value) "1" else "0")
        else (if (value.value) "TRUE" else "FALSE")
    }
    is DbCellValue.Num -> {
        if (value.asDouble != null && value.asDouble.isFinite()) value.rawText
        else quoteLiteral(value.rawText, dialect)
    }
    is DbCellValue.Text -> {
        if (kind == ColumnKind.NUMBER && NUMBER_TEXT_REGEX.matches(value.value)) {
            value.value
        } else {
            quoteLiteral(value.value, dialect)
        }
    }
    is DbCellValue.Binary -> {
        if (value.truncated) {
            "/* ${value.size} bytes, not loaded */"
        } else {
            val hex = base64ToHex(value.base64)
            if (dialect == "postgres") "'\\x$hex'" else "X'$hex'"
        }
    }
    is DbCellValue.JsonArr -> {
        if (dialect == "postgres" && kind != ColumnKind.JSON) {
            val cut = findTruncatedBinary(value.elements)
            if (cut != null) "/* ${cut.size} bytes, not loaded */"
            else quoteLiteral(postgresArrayText(value.elements), "postgres")
        } else {
            quoteLiteral(value.rawJson, dialect)
        }
    }
    is DbCellValue.JsonObj -> quoteLiteral(value.rawJson, dialect)
}

enum class CopyFormat(val id: String, val label: String, val shortName: String) {
    TEXT_WITH_HEADERS("textWithHeaders", "Copy with headers", "With headers"),
    TEXT_WITHOUT_HEADERS("textWithoutHeaders", "Copy without headers", "Without headers"),
    HEADERS("headers", "Copy only headers", "Only Headers"),
    CSV("csv", "Copy as CSV", "CSV"),
    JSON("json", "Copy as JSON", "JSON"),
    JSON_LINES("jsonLines", "Copy as JSON lines/NDJSON", "JSON lines/NDJSON"),
    YAML("yaml", "Copy as YAML", "YAML"),
    INSERTS("inserts", "Copy as SQL INSERTs", "SQL INSERTs"),
    UPDATES("updates", "Copy as SQL UPDATEs", "SQL UPDATEs"),
}

val DEFAULT_COPY_FORMAT = CopyFormat.TEXT_WITHOUT_HEADERS

data class CopySqlTarget(
    val table: String,
    val schema: String? = null,
    val dialect: String = "postgres", // "postgres" | "mysql" | "sqlite"
    val keyColumns: List<String> = emptyList(),
    val kinds: Map<String, ColumnKind> = emptyMap(),
)

fun sqlTableName(target: CopySqlTarget): String {
    val name = quoteIdentifier(target.table, target.dialect)
    return if (target.dialect == "postgres" && !target.schema.isNullOrBlank()) {
        "${quoteIdentifier(target.schema, "postgres")}.$name"
    } else {
        name
    }
}

fun sqlKeyCondition(
    row: Map<String, DbCellValue>,
    keyColumns: List<String>,
    target: CopySqlTarget,
): String =
    keyColumns.joinToString(" AND ") { c ->
        val name = quoteIdentifier(c, target.dialect)
        val v = row[c] ?: DbCellValue.Null
        if (v == DbCellValue.Null || v == DbCellValue.NoField) {
            "$name IS NULL"
        } else {
            "$name=${sqlLiteral(v, target.dialect, target.kinds[c])}"
        }
    }

private const val CRLF = "\r\n"

private fun quoteTabText(text: String): String =
    if (text.any { it == '\t' || it == '\r' || it == '\n' || it == '"' }) {
        "\"${text.replace("\"", "\"\"")}\""
    } else {
        text
    }

private fun quoteCsvCell(text: String): String =
    if (text.any { it == '"' || it == ',' || it == '\r' || it == '\n' }) {
        "\"${text.replace("\"", "\"\"")}\""
    } else {
        text
    }

private fun cellToJsonElement(v: DbCellValue): JsonElement = when (v) {
    DbCellValue.Null, DbCellValue.NoField -> JsonNull
    is DbCellValue.Bool -> JsonPrimitive(v.value)
    is DbCellValue.Num -> {
        val longVal = v.rawText.toLongOrNull()
        val dblVal = v.asDouble
        when {
            longVal != null -> JsonPrimitive(longVal)
            dblVal != null && dblVal.isFinite() -> JsonPrimitive(dblVal)
            else -> JsonPrimitive(v.rawText)
        }
    }
    is DbCellValue.Text -> JsonPrimitive(v.value)
    is DbCellValue.Binary -> buildJsonObject {
        put("\$binary", v.base64)
        put("size", v.size)
        if (v.truncated) put("truncated", true)
    }
    is DbCellValue.JsonArr -> buildJsonArray {
        for (el in v.elements) add(cellToJsonElement(el))
    }
    is DbCellValue.JsonObj -> JsonPrimitive(v.rawJson)
}

private fun yamlScalar(v: DbCellValue): String = when (v) {
    DbCellValue.Null, DbCellValue.NoField -> "null"
    is DbCellValue.Bool -> v.value.toString()
    is DbCellValue.Num -> v.rawText
    is DbCellValue.Text -> {
        val s = v.value
        val needsQuotes = s.isEmpty() ||
            s in setOf("null", "true", "false", "~") ||
            s.any { it == ':' || it == '#' || it == '\n' || it == '\r' || it == '"' || it == '\'' } ||
            NUMBER_TEXT_REGEX.matches(s)
        if (needsQuotes) "'${s.replace("'", "''")}'" else s
    }
    is DbCellValue.Binary -> "{ \$binary: '${v.base64}', size: ${v.size} }"
    is DbCellValue.JsonArr -> v.rawJson
    is DbCellValue.JsonObj -> v.rawJson
}

private fun dumpYamlRows(columns: List<String>, rows: List<Map<String, DbCellValue>>): String {
    if (rows.isEmpty()) return "[]\n"
    val sb = StringBuilder()
    for (row in rows) {
        val presentCols = columns.filter { row[it] != null && row[it] != DbCellValue.NoField }
        if (presentCols.isEmpty()) {
            sb.append("- {}\n")
        } else {
            presentCols.forEachIndexed { idx, col ->
                val prefix = if (idx == 0) "- " else "  "
                val v = row[col] ?: DbCellValue.Null
                sb.append("$prefix$col: ${yamlScalar(v)}\n")
            }
        }
    }
    return sb.toString()
}

/**
 * Formats [rows] and [columns] in [format], matching web's `copy-as.ts`.
 */
fun formatCopy(
    format: CopyFormat,
    columns: List<String>,
    rows: List<Map<String, DbCellValue>>,
    target: CopySqlTarget,
    storedRows: List<Map<String, DbCellValue>>? = null,
): String = when (format) {
    CopyFormat.TEXT_WITH_HEADERS -> {
        val headerLine = columns.joinToString("\t") { quoteTabText(it) }
        val bodyLines = rows.map { row ->
            columns.joinToString("\t") { c -> quoteTabText(formatCellCopyText(row[c] ?: DbCellValue.Null)) }
        }
        (listOf(headerLine) + bodyLines).joinToString(CRLF)
    }
    CopyFormat.TEXT_WITHOUT_HEADERS -> {
        if (rows.size == 1 && columns.size == 1) {
            formatCellCopyText(rows[0][columns[0]] ?: DbCellValue.Null)
        } else {
            rows.joinToString(CRLF) { row ->
                columns.joinToString("\t") { c -> quoteTabText(formatCellCopyText(row[c] ?: DbCellValue.Null)) }
            }
        }
    }
    CopyFormat.HEADERS -> columns.joinToString("\t") { quoteTabText(it) }
    CopyFormat.CSV -> {
        val headerLine = columns.joinToString(",") { quoteCsvCell(it) }
        val bodyLines = rows.map { row ->
            columns.joinToString(",") { c -> quoteCsvCell(formatCellCopyText(row[c] ?: DbCellValue.Null)) }
        }
        (listOf(headerLine) + bodyLines).joinToString(CRLF)
    }
    CopyFormat.JSON -> {
        val arr = rows.map { row ->
            val entries = columns.mapNotNull { c ->
                val v = row[c]
                if (v == null || v == DbCellValue.NoField) null
                else "    \"${c.replace("\"", "\\\"")}\": ${cellToJsonElement(v)}"
            }
            if (entries.isEmpty()) "  {}" else "  {\n${entries.joinToString(",\n")}\n  }"
        }
        if (arr.isEmpty()) "[]" else "[\n${arr.joinToString(",\n")}\n]"
    }
    CopyFormat.JSON_LINES -> {
        rows.joinToString(CRLF) { row ->
            val obj = buildJsonObject {
                for (c in columns) {
                    val v = row[c]
                    if (v != null && v != DbCellValue.NoField) {
                        put(c, cellToJsonElement(v))
                    }
                }
            }
            obj.toString()
        }
    }
    CopyFormat.YAML -> dumpYamlRows(columns, rows)
    CopyFormat.INSERTS -> {
        val table = sqlTableName(target)
        rows.mapNotNull { row ->
            val setCols = columns.filter { row[it] != null && row[it] != DbCellValue.NoField }
            if (setCols.isEmpty()) null
            else {
                val names = setCols.joinToString(", ") { quoteIdentifier(it, target.dialect) }
                val values = setCols.joinToString(", ") { c ->
                    sqlLiteral(row[c] ?: DbCellValue.Null, target.dialect, target.kinds[c])
                }
                "INSERT INTO $table ($names) VALUES ($values);"
            }
        }.joinToString("\n")
    }
    CopyFormat.UPDATES -> {
        val table = sqlTableName(target)
        if (target.keyColumns.isEmpty()) ""
        else {
            rows.mapIndexedNotNull { idx, row ->
                val setCols = columns.filter { row[it] != null && row[it] != DbCellValue.NoField }
                if (setCols.isEmpty()) null
                else {
                    val assignments = setCols.joinToString(", ") { c ->
                        "${quoteIdentifier(c, target.dialect)}=${sqlLiteral(row[c] ?: DbCellValue.Null, target.dialect, target.kinds[c])}"
                    }
                    val stored = storedRows?.getOrNull(idx) ?: row
                    "UPDATE $table SET $assignments WHERE ${sqlKeyCondition(stored, target.keyColumns, target)};"
                }
            }.joinToString("\n")
        }
    }
}

// ── 9b.4 Quick Export Formats & Helpers (`db-grid-export.ts`) ────────────────

data class GridExportFormatSpec(
    val id: String,
    val label: String,
    val extension: String,
    val mimeType: String,
)

val GRID_EXPORT_FORMATS = listOf(
    GridExportFormatSpec("json", "JSON", "json", "application/json"),
    GridExportFormatSpec("jsonl", "JSON lines/NDJSON", "jsonl", "application/x-ndjson"),
    GridExportFormatSpec("sql", "SQL", "sql", "application/sql"),
    GridExportFormatSpec("csv", "CSV file", "csv", "text/csv"),
    GridExportFormatSpec("csvSemicolon", "CSV file (semicolon separated)", "csv", "text/csv"),
    GridExportFormatSpec("csvExcel", "CSV file for MS Excel", "csv", "text/csv"),
    GridExportFormatSpec("tsv", "TSV file (tab separated)", "tsv", "text/tab-separated-values"),
    GridExportFormatSpec("xlsx", "MS Excel", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    GridExportFormatSpec("xml", "XML file", "xml", "application/xml"),
)

private val UNSAFE_FILENAME_REGEX = Regex("""[\u0000-\u001f\u007f/\\:*?"<>|]""")
private val EDGE_DOTS_SPACES_REGEX = Regex("""^[.\s]+|[.\s]+$""")

fun safeFileName(name: String, fallback: String): String =
    name.replace(UNSAFE_FILENAME_REGEX, "_")
        .replace(EDGE_DOTS_SPACES_REGEX, "")
        .ifEmpty { fallback }

fun gridExportFileName(table: String, formatId: String): String {
    val ext = GRID_EXPORT_FORMATS.firstOrNull { it.id == formatId }?.extension ?: "txt"
    return "${safeFileName(table, "export")}.$ext"
}

fun buildGridRequestJson(
    table: String,
    schema: String? = null,
    sort: List<GridSortItem> = emptyList(),
    offset: Int = 0,
    limit: Int = GRID_PAGE_SIZE,
    filters: JsonArray = JsonArray(emptyList()),
    anyColumn: JsonArray = JsonArray(emptyList()),
): JsonObject = buildJsonObject {
    put("table", table)
    if (!schema.isNullOrBlank()) put("schema", schema)
    put("filters", filters)
    put("anyColumn", anyColumn)
    put(
        "sort",
        buildJsonArray {
            for (s in sort) {
                add(
                    buildJsonObject {
                        put("column", s.column)
                        put("dir", s.dir)
                    },
                )
            }
        },
    )
    put("offset", offset)
    put("limit", limit)
}

fun buildGridExportRequestJson(
    table: String,
    schema: String? = null,
    columns: List<String>,
    formatId: String,
    sort: List<GridSortItem> = emptyList(),
    filters: JsonArray = JsonArray(emptyList()),
    anyColumn: JsonArray = JsonArray(emptyList()),
): JsonObject = buildJsonObject {
    put("table", table)
    if (!schema.isNullOrBlank()) put("schema", schema)
    put("filters", filters)
    put("anyColumn", anyColumn)
    put(
        "sort",
        buildJsonArray {
            for (s in sort) {
                add(
                    buildJsonObject {
                        put("column", s.column)
                        put("dir", s.dir)
                    },
                )
            }
        },
    )
    put("columns", buildJsonArray { for (c in columns) add(JsonPrimitive(c)) })
    put("format", formatId)
}

private val CAMEL_SPLIT_REGEX = Regex("""([a-z\d])([A-Z])""")
private val WORD_SPLIT_REGEX = Regex("""[^A-Za-z\d]+|(?<=\d)(?=[A-Za-z])|(?<=[A-Za-z])(?=\d)""")
private val ALL_CAPS_REGEX = Regex("""^[A-Z]+$""")

fun nameCapitals(name: String): String =
    name.replace(CAMEL_SPLIT_REGEX, "$1 $2")
        .split(WORD_SPLIT_REGEX)
        .filter { it.isNotEmpty() }
        .joinToString("") { word -> word[0].uppercaseChar() + word.substring(1) }
        .filter { it in 'A'..'Z' }

private fun isSubsequence(letters: String, text: String): Boolean {
    var at = 0
    for (ch in text) {
        if (at < letters.length && ch == letters[at]) at++
    }
    return at == letters.length
}

/**
 * DBGate's match of a column name against the Column name filter (`form-view-model.ts`):
 * commas separate alternatives, spaces separate words; an ALL-CAPS word matches `nameCapitals`
 * (`CA` -> `created_at`, `UID` -> `userID`).
 */
fun columnNameMatches(filter: String, name: String): Boolean {
    if (filter.isBlank()) return true
    if (name.isEmpty()) return false
    return filter.split(",").any { alt ->
        val words = alt.split(" ").map { it.trim() }.filter { it.isNotEmpty() }
        words.isNotEmpty() && words.all { word ->
            if (ALL_CAPS_REGEX.matches(word)) {
                isSubsequence(word, nameCapitals(name))
            } else {
                name.contains(word, ignoreCase = true)
            }
        }
    }
}

