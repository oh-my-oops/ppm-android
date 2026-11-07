package com.hienlh.ppm.state.database

import java.nio.charset.StandardCharsets
import java.text.NumberFormat
import java.util.Base64
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Phase 09d pure state & domain models for Database Editing, Changeset, Save Preview,
 * Form View, Cell Data Viewer, JSON Document, Master/Detail References, and Generate SQL:
 * - `grid-changeset.ts`, `db-changeset.ts`, `change-marks.ts`, `grid-save-model.ts`
 * - `form-view-model.ts`, `selection-commands.ts`
 * - `cell-data-formats.ts`, `json-document.ts`
 * - `references.ts`, `reference-filter.ts`, `generate-sql.ts`
 */

// ── 9d.1 Changeset & Undo/Redo History (`grid-changeset.ts`, `db-changeset.ts`) ──

const val CHANGESET_MAX_OPERATIONS = 10_000
const val NEW_ROW_PREFIX = "__new_"
const val HISTORY_LIMIT = 200

fun isNewRowId(rowId: String): Boolean = rowId.startsWith(NEW_ROW_PREFIX)

private val prettyJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

/**
 * Exact equality check for two [DbCellValue] instances, matching `sameValue` in `grid-changeset.ts`:
 * - `"5"` ([DbCellValue.Text]) != `5` ([DbCellValue.Num])
 * - [DbCellValue.Null] != [DbCellValue.NoField]
 * - [DbCellValue.Num] compares exact `rawText` (or equal finite `asDouble` when representations match)
 */
fun sameDbCellValue(a: DbCellValue, b: DbCellValue): Boolean {
    if (a === b) return true
    return when {
        a is DbCellValue.Null && b is DbCellValue.Null -> true
        a is DbCellValue.NoField && b is DbCellValue.NoField -> true
        a is DbCellValue.Bool && b is DbCellValue.Bool -> a.value == b.value
        a is DbCellValue.Text && b is DbCellValue.Text -> a.value == b.value
        a is DbCellValue.Num && b is DbCellValue.Num -> {
            if (a.rawText == b.rawText) true
            else if (a.asDouble != null && b.asDouble != null && a.rawText.length <= 15 && b.rawText.length <= 15) {
                a.asDouble == b.asDouble
            } else {
                false
            }
        }
        a is DbCellValue.Binary && b is DbCellValue.Binary ->
            a.base64 == b.base64 && a.size == b.size && a.truncated == b.truncated
        a is DbCellValue.JsonArr && b is DbCellValue.JsonArr ->
            a.elements.size == b.elements.size &&
                a.elements.indices.all { idx -> sameDbCellValue(a.elements[idx], b.elements[idx]) }
        a is DbCellValue.JsonObj && b is DbCellValue.JsonObj -> {
            if (a.rawJson == b.rawJson) true
            else {
                val objA = runCatching { Json.parseToJsonElement(a.rawJson) as? JsonObject }.getOrNull()
                val objB = runCatching { Json.parseToJsonElement(b.rawJson) as? JsonObject }.getOrNull()
                if (objA != null && objB != null) sameJsonElements(objA, objB) else false
            }
        }
        else -> false
    }
}

private fun sameJsonElements(a: JsonElement, b: JsonElement): Boolean = when {
    a is JsonNull && b is JsonNull -> true
    a is JsonPrimitive && b is JsonPrimitive ->
        a.isString == b.isString && a.content == b.content
    a is JsonArray && b is JsonArray ->
        a.size == b.size && a.indices.all { sameJsonElements(a[it], b[it]) }
    a is JsonObject && b is JsonObject ->
        a.keys == b.keys && a.keys.all { k -> sameJsonElements(a.getValue(k), b.getValue(k)) }
    else -> false
}

data class PendingEdit(
    val pkVal: String,
    val col: String,
    val newVal: DbCellValue,
    val key: Map<String, DbCellValue> = emptyMap(),
    val original: DbCellValue = DbCellValue.NoField,
)

data class GridChangeset(
    val cells: Map<String, Map<String, PendingEdit>> = emptyMap(),
    val inserted: List<String> = emptyList(),
    val deleted: Set<String> = emptySet(),
)

val EMPTY_CHANGESET = GridChangeset()

data class CellChange(
    val rowId: String,
    val col: String,
    val value: DbCellValue,
    val key: Map<String, DbCellValue> = emptyMap(),
    val original: DbCellValue = DbCellValue.NoField,
)

/**
 * Apply one or more cell edits (`editCells` in `grid-changeset.ts`).
 * Putting a saved row's cell back to its [CellChange.original] value removes the pending edit.
 */
fun editCells(cs: GridChangeset, changes: List<CellChange>): GridChangeset {
    if (changes.isEmpty()) return cs
    val cells = LinkedHashMap(cs.cells)
    for (c in changes) {
        if (c.rowId in cs.deleted) continue
        val prevRow = cells[c.rowId]
        val row = if (prevRow != null) LinkedHashMap(prevRow) else LinkedHashMap()
        if (!isNewRowId(c.rowId)) {
            val existing = row[c.col]
            val original = if (existing != null && existing.original != DbCellValue.NoField) {
                existing.original
            } else {
                c.original
            }
            if (original != DbCellValue.NoField && sameDbCellValue(c.value, original)) {
                row.remove(c.col)
                if (row.isEmpty()) cells.remove(c.rowId) else cells[c.rowId] = row
                continue
            }
            val key = if (c.key.isNotEmpty()) c.key else existing?.key.orEmpty()
            row[c.col] = PendingEdit(
                pkVal = c.rowId,
                col = c.col,
                newVal = c.value,
                key = key,
                original = original,
            )
        } else {
            row[c.col] = PendingEdit(
                pkVal = c.rowId,
                col = c.col,
                newVal = c.value,
            )
        }
        cells[c.rowId] = row
    }
    return cs.copy(cells = cells)
}

data class NewRow(
    val rowId: String,
    val values: Map<String, DbCellValue> = emptyMap(),
)

/** Append new rows at the bottom of the grid (`addRows` in `grid-changeset.ts`). */
fun addRows(cs: GridChangeset, rows: List<NewRow>): GridChangeset {
    if (rows.isEmpty()) return cs
    val cells = LinkedHashMap(cs.cells)
    val inserted = cs.inserted.toMutableList()
    for (r in rows) {
        inserted += r.rowId
        val rowEdits = LinkedHashMap<String, PendingEdit>()
        for ((col, value) in r.values) {
            if (value == DbCellValue.NoField) continue
            rowEdits[col] = PendingEdit(pkVal = r.rowId, col = col, newVal = value)
        }
        if (rowEdits.isNotEmpty()) cells[r.rowId] = rowEdits
    }
    return cs.copy(cells = cells, inserted = inserted)
}

data class CloneColumn(
    val name: String,
    val pk: Boolean,
    val autoIncrement: Boolean = false,
)

/**
 * Values a cloned row inherits from its source row (`cloneValues` in `grid-changeset.ts`):
 * - Auto-increment PK columns are left untouched (`NoField`) so the DB assigns a new key.
 * - Truncated binary cells (`truncated == true`) are omitted so a 64-byte preview never overwrites full bytes.
 */
fun cloneValues(
    row: Map<String, DbCellValue>,
    columns: List<CloneColumn>,
): Map<String, DbCellValue> {
    val values = LinkedHashMap<String, DbCellValue>()
    for (col in columns) {
        if (col.pk && col.autoIncrement) continue
        val v = row[col.name] ?: continue
        if (v == DbCellValue.NoField) continue
        if (v is DbCellValue.Binary && v.truncated) continue
        values[col.name] = v
    }
    return values
}

/**
 * Mark saved rows for deletion or remove unsaved new rows (`deleteRows` in `grid-changeset.ts`).
 */
fun deleteRows(cs: GridChangeset, rowIds: List<String>): GridChangeset {
    if (rowIds.isEmpty()) return cs
    val cells = LinkedHashMap(cs.cells)
    val deleted = LinkedHashSet(cs.deleted)
    val dropInserted = HashSet<String>()
    for (id in rowIds) {
        cells.remove(id)
        if (isNewRowId(id)) {
            dropInserted += id
        } else {
            deleted += id
        }
    }
    val inserted = if (dropInserted.isNotEmpty()) {
        cs.inserted.filter { it !in dropInserted }
    } else {
        cs.inserted
    }
    return GridChangeset(cells = cells, inserted = inserted, deleted = deleted)
}

/**
 * Revert every pending change on the given rows (`revertRows` in `grid-changeset.ts`).
 */
fun revertRows(cs: GridChangeset, rowIds: List<String>): GridChangeset {
    if (rowIds.isEmpty()) return cs
    val cells = LinkedHashMap(cs.cells)
    val deleted = LinkedHashSet(cs.deleted)
    val dropInserted = HashSet<String>()
    for (id in rowIds) {
        cells.remove(id)
        deleted.remove(id)
        if (isNewRowId(id)) dropInserted += id
    }
    val inserted = if (dropInserted.isNotEmpty()) {
        cs.inserted.filter { it !in dropInserted }
    } else {
        cs.inserted
    }
    return GridChangeset(cells = cells, inserted = inserted, deleted = deleted)
}

data class LockColumn(
    val pk: Boolean,
    val autoIncrement: Boolean = false,
)

/**
 * Whether a cell cannot be edited (`isCellLocked` in `grid-changeset.ts`):
 * - Any cell in a deleted row
 * - An auto-increment PK cell in a new row
 * - Any PK cell in an existing saved row
 */
fun isCellLocked(column: LockColumn, rowId: String, cs: GridChangeset): Boolean {
    if (rowId in cs.deleted) return true
    if (isNewRowId(rowId)) return column.pk && column.autoIncrement
    return column.pk
}

fun isEmptyChangeset(cs: GridChangeset): Boolean =
    cs.cells.isEmpty() && cs.inserted.isEmpty() && cs.deleted.isEmpty()

/** Count of rows that will be written when saved (`changedRowCount` in `grid-changeset.ts`). */
fun changedRowCount(cs: GridChangeset): Int {
    val rows = HashSet<String>(cs.deleted)
    for ((rowId, edits) in cs.cells) {
        if (edits.isNotEmpty()) rows += rowId
    }
    return rows.size
}

data class GridUpdateItem(
    val key: Map<String, DbCellValue>,
    val set: Map<String, DbCellValue>,
)

data class GridChangesPayload(
    val inserts: List<Map<String, DbCellValue>>,
    val updates: List<GridUpdateItem>,
    val deletes: List<Map<String, DbCellValue>>,
) {
    val totalOperations: Int
        get() = inserts.size + updates.size + deletes.size
}

/**
 * Build the changeset payload for `/changeset/preview` and `/changeset/apply` (`toGridChanges` in `grid-changeset.ts`).
 * - New rows with no edited cells are omitted so an untouched row never sends `INSERT DEFAULT VALUES`.
 * - Untouched cells in new rows are omitted so the database fills defaults.
 */
fun toGridChanges(
    cs: GridChangeset,
    rowKeys: Map<String, Map<String, DbCellValue>> = emptyMap(),
): GridChangesPayload {
    val inserts = mutableListOf<Map<String, DbCellValue>>()
    for (rowId in cs.inserted) {
        val edits = cs.cells[rowId]
        if (edits.isNullOrEmpty()) continue
        val row = LinkedHashMap<String, DbCellValue>()
        for ((col, edit) in edits) {
            if (edit.newVal != DbCellValue.NoField) {
                row[col] = edit.newVal
            }
        }
        if (row.isNotEmpty()) inserts += row
    }

    val updates = mutableListOf<GridUpdateItem>()
    for ((rowId, edits) in cs.cells) {
        if (isNewRowId(rowId) || rowId in cs.deleted) continue
        val list = edits.values.toList()
        if (list.isEmpty()) continue
        val set = LinkedHashMap<String, DbCellValue>()
        for (e in list) {
            if (e.newVal != DbCellValue.NoField) {
                set[e.col] = e.newVal
            }
        }
        val firstKey = list.first().key
        val key = if (firstKey.isNotEmpty()) firstKey else rowKeys[rowId].orEmpty()
        if (key.isEmpty() || set.isEmpty()) continue
        updates += GridUpdateItem(key = key, set = set)
    }

    val deletes = mutableListOf<Map<String, DbCellValue>>()
    for (rowId in cs.deleted) {
        val key = rowKeys[rowId]
        if (!key.isNullOrEmpty()) deletes += key
    }

    return GridChangesPayload(inserts = inserts, updates = updates, deletes = deletes)
}

/** Convert [DbCellValue] to wire JSON for `/changeset/preview`, `/changeset/apply`, and `/grid/cell`. */
fun dbCellValueToWireJson(v: DbCellValue): JsonElement = when (v) {
    DbCellValue.Null, DbCellValue.NoField -> JsonNull
    is DbCellValue.Bool -> JsonPrimitive(v.value)
    is DbCellValue.Num -> {
        val trimmed = v.rawText.trim()
        val longVal = if (trimmed.length <= 15) trimmed.toLongOrNull() else null
        val dblVal = if (trimmed.length <= 15) v.asDouble else null
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
        for (el in v.elements) add(dbCellValueToWireJson(el))
    }
    is DbCellValue.JsonObj ->
        runCatching { Json.parseToJsonElement(v.rawJson) }.getOrElse { JsonPrimitive(v.rawJson) }
}

private fun rowMapToWireJson(map: Map<String, DbCellValue>): JsonObject = buildJsonObject {
    for ((k, v) in map) {
        if (v != DbCellValue.NoField) {
            put(k, dbCellValueToWireJson(v))
        }
    }
}

fun buildChangesetRequestJson(
    table: String,
    schema: String? = null,
    payload: GridChangesPayload,
    cascade: List<TableRef> = emptyList(),
): JsonObject = buildJsonObject {
    put("table", table)
    if (!schema.isNullOrBlank()) put("schema", schema)
    put("inserts", buildJsonArray { for (row in payload.inserts) add(rowMapToWireJson(row)) })
    put(
        "updates",
        buildJsonArray {
            for (u in payload.updates) {
                add(
                    buildJsonObject {
                        put("key", rowMapToWireJson(u.key))
                        put("set", rowMapToWireJson(u.set))
                    },
                )
            }
        },
    )
    put(
        "deletes",
        buildJsonArray {
            for (k in payload.deletes) {
                add(buildJsonObject { put("key", rowMapToWireJson(k)) })
            }
        },
    )
    if (cascade.isNotEmpty()) {
        put(
            "cascade",
            buildJsonArray {
                for (ref in cascade) {
                    add(
                        buildJsonObject {
                            if (!ref.schema.isNullOrBlank()) put("schema", ref.schema) else put("schema", JsonNull)
                            put("name", ref.name)
                        },
                    )
                }
            },
        )
    }
}

data class ChangesetHistory(
    val present: GridChangeset = EMPTY_CHANGESET,
    val past: List<GridChangeset> = emptyList(),
    val future: List<GridChangeset> = emptyList(),
)

fun recordChange(h: ChangesetHistory, next: GridChangeset): ChangesetHistory {
    if (next == h.present) return h
    val past = (h.past + h.present).let {
        if (it.size > HISTORY_LIMIT) it.subList(it.size - HISTORY_LIMIT, it.size) else it
    }
    return ChangesetHistory(present = next, past = past, future = emptyList())
}

fun undoChange(h: ChangesetHistory): ChangesetHistory {
    if (h.past.isEmpty()) return h
    val previous = h.past.last()
    return ChangesetHistory(
        present = previous,
        past = h.past.dropLast(1),
        future = listOf(h.present) + h.future,
    )
}

fun redoChange(h: ChangesetHistory): ChangesetHistory {
    if (h.future.isEmpty()) return h
    val next = h.future.first()
    return ChangesetHistory(
        present = next,
        past = h.past + h.present,
        future = h.future.drop(1),
    )
}

// ── 9d.1 Row & Cell Change Marks (`change-marks.ts`) ─────────────────────────

enum class RowChange {
    INSERTED,
    DELETED,
    MODIFIED,
}

enum class CellMark {
    INSERTED,
    DELETED,
    MODIFIED,
    MODIFIED_ROW,
    UNCHANGED,
}

fun rowChange(cs: GridChangeset, rowId: String): RowChange? {
    if (rowId in cs.deleted) return RowChange.DELETED
    if (isNewRowId(rowId)) return RowChange.INSERTED
    val edits = cs.cells[rowId]
    if (!edits.isNullOrEmpty()) return RowChange.MODIFIED
    return null
}

fun cellMark(cs: GridChangeset, rowId: String, col: String): CellMark {
    if (rowId in cs.deleted) return CellMark.DELETED
    if (isNewRowId(rowId)) return CellMark.INSERTED
    val edits = cs.cells[rowId]
    if (edits.isNullOrEmpty()) return CellMark.UNCHANGED
    return if (col in edits) CellMark.MODIFIED else CellMark.MODIFIED_ROW
}

// ── 9d.1 Save Dialog Model (`grid-save-model.ts`, `db-changeset.ts`) ─────────

data class TableRef(
    val schema: String?,
    val name: String,
)

data class ChangesetReference(
    val table: TableRef,
    val count: Int,
    val paths: List<String>,
    val script: String,
)

data class ChangesetPreview(
    val statementCount: Int,
    val script: String,
    val references: List<ChangesetReference>,
)

data class ChangesetApplyResult(
    val inserted: Int,
    val updated: Int,
    val deleted: Int,
    val cascaded: Int,
    val executionTimeMs: Int,
)

data class ChangesetFailure(
    val statementIndex: Int,
    val kind: String, // "insert" | "update" | "delete" | "cascade"
    val sql: String,
    val table: TableRef,
    val affected: Int? = null,
)

fun parseTableRef(obj: JsonObject?): TableRef = TableRef(
    schema = (obj?.get("schema") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull,
    name = (obj?.get("name") as? JsonPrimitive)?.contentOrNull.orEmpty(),
)

fun parseChangesetPreview(obj: JsonObject): ChangesetPreview {
    val count = (obj["statementCount"] as? JsonPrimitive)?.intOrNull ?: 0
    val script = (obj["script"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val refs = ((obj["references"] as? JsonArray).orEmpty()).mapNotNull { el ->
        val r = el as? JsonObject ?: return@mapNotNull null
        val tableObj = r["table"] as? JsonObject ?: return@mapNotNull null
        val paths = ((r["paths"] as? JsonArray).orEmpty()).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ChangesetReference(
            table = parseTableRef(tableObj),
            count = (r["count"] as? JsonPrimitive)?.intOrNull ?: 0,
            paths = paths,
            script = (r["script"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        )
    }
    return ChangesetPreview(statementCount = count, script = script, references = refs)
}

fun parseChangesetApplyResult(obj: JsonObject): ChangesetApplyResult = ChangesetApplyResult(
    inserted = (obj["inserted"] as? JsonPrimitive)?.intOrNull ?: 0,
    updated = (obj["updated"] as? JsonPrimitive)?.intOrNull ?: 0,
    deleted = (obj["deleted"] as? JsonPrimitive)?.intOrNull ?: 0,
    cascaded = (obj["cascaded"] as? JsonPrimitive)?.intOrNull ?: 0,
    executionTimeMs = (obj["executionTimeMs"] as? JsonPrimitive)?.intOrNull ?: 0,
)

fun parseChangesetFailure(el: JsonElement?): ChangesetFailure? {
    val obj = el as? JsonObject ?: return null
    val sql = (obj["sql"] as? JsonPrimitive)?.contentOrNull ?: return null
    return ChangesetFailure(
        statementIndex = (obj["statementIndex"] as? JsonPrimitive)?.intOrNull ?: 0,
        kind = (obj["kind"] as? JsonPrimitive)?.contentOrNull ?: "update",
        sql = sql,
        table = parseTableRef(obj["table"] as? JsonObject),
        affected = (obj["affected"] as? JsonPrimitive)?.intOrNull,
    )
}

/** Stable key for a referenced table in the Save dialog's checked set (`refKey` in `grid-save-model.ts`). */
fun refKey(ref: TableRef): String = "${ref.schema.orEmpty()}.${ref.name}"

/**
 * Tables to pass as `cascade` in `ChangesetRequest`, in the server's deepest-first order
 * (`cascadeTables` in `grid-save-model.ts`).
 */
fun cascadeTables(references: List<ChangesetReference>, checked: Set<String>): List<TableRef> =
    references.filter { refKey(it.table) in checked }.map { it.table }

/**
 * SQL shown in the Save dialog and opened by "Open script" (`saveScript` in `grid-save-model.ts`):
 * ticked cascade DELETEs first in deepest-first order, then the changeset's own statements.
 */
fun saveScript(preview: ChangesetPreview, checked: Set<String>): String {
    val cascade = preview.references
        .filter { refKey(it.table) in checked && it.script.isNotEmpty() }
        .map { it.script }
    return (cascade + preview.script).filter { it.isNotEmpty() }.joinToString("\n")
}

/** `"orders (3)"` or `"sales.orders (3)"` (`refLabel` in `grid-save-model.ts`). */
fun refLabel(ref: ChangesetReference, defaultSchema: String? = "public"): String {
    val showSchema = !ref.table.schema.isNullOrBlank() && ref.table.schema != defaultSchema
    val name = if (showSchema) "${ref.table.schema}.${ref.table.name}" else ref.table.name
    return "$name (${NumberFormat.getIntegerInstance(Locale.US).format(ref.count)})"
}

/** `"orders.user_id -> users.id"` (`refPaths` in `grid-save-model.ts`). */
fun refPaths(ref: ChangesetReference): String = ref.paths.joinToString("\n")

/**
 * Toast text after a save finishes (`savedText` in `grid-save-model.ts`).
 */
fun savedText(result: ChangesetApplyResult): String {
    val parts = mutableListOf<String>()
    if (result.inserted > 0) parts += "${result.inserted} inserted"
    if (result.updated > 0) parts += "${result.updated} updated"
    if (result.deleted > 0) parts += "${result.deleted} deleted"
    if (result.cascaded > 0) parts += "${result.cascaded} cascaded"
    return if (parts.isNotEmpty()) "Saved (${parts.joinToString(", ")})" else "Saved"
}

/**
 * "Don't ask again" only skips the Save dialog when `preview.references` is empty (`phase-09d-db-sua.md` §9d.1).
 */
fun shouldBypassSaveDialog(dontAskAgain: Boolean, preview: ChangesetPreview): Boolean =
    dontAskAgain && preview.references.isEmpty()

// ── 9d.2 & 9d.5 Form View & Cell Parsing (`form-view-model.ts`) ──────────────

const val FORM_ROW_HEIGHT = 27
private const val MIN_FIELDS_PER_COLUMN = 5

fun fieldsPerColumn(heightPx: Int): Int {
    if (heightPx <= 0) return MIN_FIELDS_PER_COLUMN
    return max(MIN_FIELDS_PER_COLUMN, floor(heightPx.toDouble() / FORM_ROW_HEIGHT).toInt())
}

fun <T> formChunks(items: List<T>, perColumn: Int): List<List<T>> {
    val step = max(1, perColumn)
    val out = mutableListOf<List<T>>()
    var i = 0
    while (i < items.size) {
        out += items.subList(i, min(items.size, i + step))
        i += step
    }
    return out
}

data class FormCell(
    val col: Int,
    val row: Int,
)

fun isValueCell(col: Int): Boolean = col >= 0 && col % 2 == 1

fun fieldOfCell(cell: FormCell, perColumn: Int, count: Int): Int? {
    if (!isValueCell(cell.col) || cell.row < 0 || perColumn <= 0) return null
    val chunk = (cell.col - 1) / 2
    if (cell.row >= perColumn) return null
    val index = chunk * perColumn + cell.row
    return if (index < count) index else null
}

fun cellOfField(index: Int, perColumn: Int): FormCell {
    val step = max(1, perColumn)
    val chunk = index / step
    return FormCell(col = chunk * 2 + 1, row = index % step)
}

fun clampFormCell(cell: FormCell, chunkLengths: List<Int>): FormCell? {
    if (chunkLengths.isEmpty()) return null
    val maxCol = chunkLengths.size * 2 - 1
    val col = cell.col.coerceIn(0, maxCol)
    val len = chunkLengths[col / 2]
    if (len <= 0) return null
    val row = cell.row.coerceIn(0, len - 1)
    return FormCell(col = col, row = row)
}

fun moveFormCell(
    cell: FormCell,
    dc: Int,
    dr: Int,
    chunkLengths: List<Int>,
): FormCell? = clampFormCell(FormCell(col = cell.col + dc, row = cell.row + dr), chunkLengths)

enum class FormNavigation {
    FIRST,
    PREVIOUS,
    NEXT,
    LAST,
}

fun navigateFormRow(current: Int, rowCount: Int, action: FormNavigation): Int {
    if (rowCount <= 0) return 0
    val last = rowCount - 1
    val clamped = current.coerceIn(0, last)
    return when (action) {
        FormNavigation.FIRST -> 0
        FormNavigation.PREVIOUS -> max(0, clamped - 1)
        FormNavigation.NEXT -> min(last, clamped + 1)
        FormNavigation.LAST -> last
    }
}

fun formRowLabel(rowIndex: Int, rowCount: Int): String =
    if (rowCount <= 0) "0 / 0"
    else "${NumberFormat.getIntegerInstance(Locale.US).format(rowIndex + 1)} / ${NumberFormat.getIntegerInstance(Locale.US).format(rowCount)}"

enum class FormFieldKind {
    TEXT,
    NUMBER,
    BOOLEAN,
    JSON,
    BINARY,
}

fun formFieldKindFor(kind: ColumnKind): FormFieldKind = when (kind) {
    ColumnKind.NUMBER -> FormFieldKind.NUMBER
    ColumnKind.BOOLEAN -> FormFieldKind.BOOLEAN
    ColumnKind.JSON -> FormFieldKind.JSON
    ColumnKind.BINARY -> FormFieldKind.BINARY
    else -> FormFieldKind.TEXT
}

sealed interface FormParse {
    data class Valid(val value: DbCellValue) : FormParse
    data object Invalid : FormParse
}

private val FORM_NUMBER_REGEX = Regex("""^-?(?:\d+\.?\d*|\.\d+)(?:e[+-]?\d+)?$""", RegexOption.IGNORE_CASE)
private val DIGITS_ONLY_REGEX = Regex("""\D""")

/**
 * Parse text typed into a Form View or inline cell editor (`parseFormText` in `form-view-model.ts`).
 * Numbers with > 15 significant digits stay strings ([DbCellValue.Text]) so 64-bit bigints never lose precision.
 */
fun parseFormText(text: String, kind: FormFieldKind): FormParse {
    if (kind == FormFieldKind.BINARY) return FormParse.Invalid
    if (kind == FormFieldKind.BOOLEAN) {
        return when (text.trim().lowercase(Locale.US)) {
            "true", "1" -> FormParse.Valid(DbCellValue.Bool(true))
            "false", "0" -> FormParse.Valid(DbCellValue.Bool(false))
            else -> FormParse.Invalid
        }
    }
    if (kind == FormFieldKind.NUMBER) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !FORM_NUMBER_REGEX.matches(trimmed)) return FormParse.Invalid
        val digits = trimmed.replace(DIGITS_ONLY_REGEX, "").length
        val n = trimmed.toDoubleOrNull()
        val value = if (digits > 15 || n == null || !n.isFinite()) {
            DbCellValue.Text(trimmed)
        } else {
            DbCellValue.Num(rawText = trimmed, asDouble = n)
        }
        return FormParse.Valid(value)
    }
    if (kind == FormFieldKind.JSON) {
        return try {
            val el = Json.parseToJsonElement(text)
            FormParse.Valid(parseDbCellValue(el))
        } catch (_: Throwable) {
            FormParse.Invalid
        }
    }
    return FormParse.Valid(DbCellValue.Text(text))
}

fun formEditText(value: DbCellValue): String = formatCellEditText(value)

fun formDisplayText(value: DbCellValue): String = formatCellDisplayText(value, truncateForGrid = true)

fun formCopyText(value: DbCellValue): String = formatCellCopyText(value)

/**
 * Multi-cell TSV paste (`\n` and `\t` split) starting at `(startCol, startRow)` on visible columns/rows.
 * Locked cells (`isCellLocked`) are skipped automatically.
 */
fun parseTsvPasteChanges(
    clipboardText: String,
    startCol: Int,
    startRow: Int,
    visibleColumnKeys: List<String>,
    rowIds: List<String>,
    columnSchemasByKey: Map<String, GridColumnSchema>,
    dbType: String,
    changeset: GridChangeset,
    rowKeyForRow: (rowIndex: Int) -> Map<String, DbCellValue>,
    storedValueAt: (colKey: String, rowIndex: Int) -> DbCellValue,
): List<CellChange> {
    if (clipboardText.isEmpty() || visibleColumnKeys.isEmpty() || rowIds.isEmpty()) return emptyList()
    val lines = clipboardText.trimEnd('\r', '\n').split(Regex("""\r?\n"""))
    val matrix = lines.map { it.split('\t') }
    val changes = mutableListOf<CellChange>()
    for ((rOffset, cells) in matrix.withIndex()) {
        val rowIndex = startRow + rOffset
        val rowId = rowIds.getOrNull(rowIndex) ?: break
        val keyMap = rowKeyForRow(rowIndex)
        for ((cOffset, rawCell) in cells.withIndex()) {
            val colIndex = startCol + cOffset
            val colKey = visibleColumnKeys.getOrNull(colIndex) ?: break
            val schema = columnSchemasByKey[colKey]
            val lockCol = LockColumn(
                pk = schema?.pk == true,
                autoIncrement = schema?.autoIncrement == true,
            )
            if (isCellLocked(lockCol, rowId, changeset)) continue
            val colKind = if (schema != null) classifyColumnType(dbType, schema.type) else ColumnKind.TEXT
            val fieldKind = formFieldKindFor(colKind)
            if (fieldKind == FormFieldKind.BINARY) continue
            val parsedValue: DbCellValue = when {
                rawCell.equals("NULL", ignoreCase = true) || rawCell == NULL_TEXT -> DbCellValue.Null
                rawCell.isEmpty() && fieldKind != FormFieldKind.TEXT && schema?.nullable != false -> DbCellValue.Null
                else -> when (val p = parseFormText(rawCell, fieldKind)) {
                    is FormParse.Valid -> p.value
                    FormParse.Invalid -> continue
                }
            }
            val original = if (isNewRowId(rowId)) DbCellValue.NoField else storedValueAt(colKey, rowIndex)
            changes += CellChange(
                rowId = rowId,
                col = colKey,
                value = parsedValue,
                key = keyMap,
                original = original,
            )
        }
    }
    return changes
}

// ── 9d.2 Selection Commands & Save Cell to File (`selection-commands.ts`) ─────

data class SelectedValue(
    val column: String,
    val kind: ColumnKind,
    val value: DbCellValue,
)

sealed interface SelectedValueFilters {
    data class Ready(val filters: Map<String, String>) : SelectedValueFilters
    data class Refused(val message: String) : SelectedValueFilters
}

private const val MAX_FILTER_VALUES = 200

fun selectedValueFilters(cells: List<SelectedValue>): SelectedValueFilters {
    if (cells.isEmpty()) return SelectedValueFilters.Refused("Select at least one cell to filter by its value")
    if (cells.size > MAX_FILTER_VALUES) {
        return SelectedValueFilters.Refused("Select at most $MAX_FILTER_VALUES cells to filter by value")
    }
    val byCol = LinkedHashMap<String, Pair<ColumnKind, MutableList<DbCellValue>>>()
    for (cell in cells) {
        if (cell.kind == ColumnKind.BINARY || cell.value is DbCellValue.Binary) {
            return SelectedValueFilters.Refused("Binary columns cannot be filtered by value")
        }
        if (cell.value == DbCellValue.NoField) continue
        val bucket = byCol.getOrPut(cell.column) { cell.kind to mutableListOf() }
        bucket.second += cell.value
    }
    if (byCol.isEmpty()) return SelectedValueFilters.Refused("Select at least one cell with a value")
    val filters = LinkedHashMap<String, String>()
    for ((col, pair) in byCol) {
        filters[col] = pickedCellValuesFilter(pair.first, pair.second)
    }
    return SelectedValueFilters.Ready(filters)
}

fun selectedColumnIndices(sel: GridSelectionState, columnCount: Int): List<Int> {
    val cols = HashSet<Int>()
    for (rect in sel.EffectiveRanges()) {
        for (c in rect.x until rect.x + rect.width) {
            if (c in 0 until columnCount) cols += c
        }
    }
    for (c in sel.selectedColumns) {
        if (c in 0 until columnCount) cols += c
    }
    return cols.sorted()
}

fun cellFileBase(table: String?, column: String): String {
    val raw = if (!table.isNullOrBlank()) "$table-$column" else column
    return safeFileName(raw, "cell")
}

fun cellFileName(table: String?, column: String, value: DbCellValue): String {
    val base = cellFileBase(table, column)
    if (value is DbCellValue.Binary) {
        val kind = imageType(leadingBytes(value.base64, IMAGE_HEADER_BYTES))
        val ext = when (kind) {
            "jpeg" -> "jpg"
            null -> "bin"
            else -> kind
        }
        return "$base.$ext"
    }
    if (value is DbCellValue.JsonArr || value is DbCellValue.JsonObj) return "$base.json"
    if (value is DbCellValue.Text) {
        val trimmed = value.value.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            if (runCatching { Json.parseToJsonElement(trimmed) }.isSuccess) {
                return "$base.json"
            }
        }
        if (Regex("""^<!doctype\s+html\b|^<html\b""", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) {
            return "$base.html"
        }
        if (Regex("""^<\?xml\b""", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) {
            return "$base.xml"
        }
    }
    return "$base.txt"
}

sealed interface CellFileResult {
    data class Ready(
        val bytes: ByteArray,
        val fileName: String,
        val mimeType: String,
    ) : CellFileResult

    data class NeedsServerFetch(
        val fileName: String,
    ) : CellFileResult

    data class Empty(
        val message: String,
    ) : CellFileResult
}

fun cellFile(table: String?, column: String, value: DbCellValue): CellFileResult {
    if (value == DbCellValue.Null) return CellFileResult.Empty("This cell is NULL — there is nothing to save")
    if (value == DbCellValue.NoField) return CellFileResult.Empty("This cell has no value yet")
    val fileName = cellFileName(table, column, value)
    if (value is DbCellValue.Binary) {
        if (value.truncated) return CellFileResult.NeedsServerFetch(fileName)
        val decoded = runCatching { Base64.getDecoder().decode(value.base64) }.getOrDefault(ByteArray(0))
        return CellFileResult.Ready(
            bytes = decoded,
            fileName = fileName,
            mimeType = "application/octet-stream",
        )
    }
    val text = when (value) {
        is DbCellValue.JsonArr ->
            runCatching { prettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(value.rawJson)) }
                .getOrDefault(value.rawJson)
        is DbCellValue.JsonObj ->
            runCatching { prettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(value.rawJson)) }
                .getOrDefault(value.rawJson)
        else -> formatCellCopyText(value)
    }
    val mime = if (fileName.endsWith(".json")) "application/json;charset=utf-8" else "text/plain;charset=utf-8"
    return CellFileResult.Ready(
        bytes = text.toByteArray(StandardCharsets.UTF_8),
        fileName = fileName,
        mimeType = mime,
    )
}

fun buildGridCellTicketRequestJson(
    table: String,
    schema: String? = null,
    column: String,
    key: Map<String, DbCellValue>,
    fileName: String,
): JsonObject = buildJsonObject {
    put("table", table)
    if (!schema.isNullOrBlank()) put("schema", schema)
    put("column", column)
    put("key", rowMapToWireJson(key))
    put("fileName", fileName)
}

// ── 9d.4 Cell Data Viewer (`cell-data-formats.ts`) ───────────────────────────

enum class CellDataFormat(
    val id: String,
    val label: String,
    val shortLabel: String,
    val multipleCells: Boolean = false,
) {
    TEXT_WRAP("textWrap", "Text (wrap)", "Text (wrap)", multipleCells = true),
    TEXT("text", "Text (no wrap)", "Text", multipleCells = true),
    FORM("form", "Form", "Form"),
    JSON("json", "Json", "Json", multipleCells = true),
    JSON_EXPANDED("jsonExpanded", "Json - expanded", "Json - exp", multipleCells = true),
    JSON_ROW("jsonRow", "Json - Row", "Json - Row", multipleCells = true),
    PICTURE("picture", "Picture", "Picture"),
    HTML("html", "HTML", "HTML"),
    XML("xml", "XML", "XML"),
}

val CELL_DATA_FORMATS: List<CellDataFormat> = CellDataFormat.entries

sealed interface CellDataChoice {
    data object Autodetect : CellDataChoice
    data class Explicit(val format: CellDataFormat) : CellDataChoice
}

fun choiceTitle(choice: CellDataChoice, detected: CellDataFormat): String = when (choice) {
    CellDataChoice.Autodetect -> "${detected.shortLabel} (detected)"
    is CellDataChoice.Explicit -> choice.format.shortLabel
}

const val CELL_DATA_MAX_CELLS = 200
const val IMAGE_HEADER_BYTES = 16

data class CellDataCell(
    val column: Int,
    val row: Int,
    val columnKey: String,
    val columnName: String,
    val value: DbCellValue,
)

data class CellDataSelection(
    val cells: List<CellDataCell>,
    val totalSelected: Int,
)

fun collectCellData(
    sel: GridSelectionState,
    columnKeys: List<String>,
    columnNames: List<String>,
    rowCount: Int,
    valueAt: (column: Int, row: Int) -> DbCellValue,
): CellDataSelection {
    val cells = mutableListOf<CellDataCell>()
    var totalSelected = 0
    forEachSelectedCell(sel, columnKeys.size, rowCount) { c, r ->
        totalSelected++
        if (cells.size < CELL_DATA_MAX_CELLS) {
            cells += CellDataCell(
                column = c,
                row = r,
                columnKey = columnKeys[c],
                columnName = columnNames.getOrElse(c) { columnKeys[c] },
                value = valueAt(c, r),
            )
        }
    }
    return CellDataSelection(cells = cells, totalSelected = totalSelected)
}

fun rowsOfCells(cells: List<CellDataCell>): List<Int> {
    val seen = LinkedHashSet<Int>()
    for (c in cells) seen += c.row
    return seen.toList()
}

/**
 * Inspect first bytes for PNG, JPEG, GIF, WEBP, BMP, ICO, or AVIF (`imageType` in `cell-data-formats.ts`).
 */
fun imageType(bytes: List<Int>): String? {
    if (bytes.size >= 8 &&
        bytes[0] == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47 &&
        bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A
    ) return "png"
    if (bytes.size >= 3 && bytes[0] == 0xFF && bytes[1] == 0xD8 && bytes[2] == 0xFF) return "jpeg"
    if (bytes.size >= 6 &&
        bytes[0] == 0x47 && bytes[1] == 0x49 && bytes[2] == 0x46 && bytes[3] == 0x38 &&
        (bytes[4] == 0x37 || bytes[4] == 0x39) && bytes[5] == 0x61
    ) return "gif"
    if (bytes.size >= 12 &&
        bytes[0] == 0x52 && bytes[1] == 0x49 && bytes[2] == 0x46 && bytes[3] == 0x46 &&
        bytes[8] == 0x57 && bytes[9] == 0x45 && bytes[10] == 0x42 && bytes[11] == 0x50
    ) return "webp"
    if (bytes.size >= 2 && bytes[0] == 0x42 && bytes[1] == 0x4D) return "bmp"
    if (bytes.size >= 4 &&
        bytes[0] == 0x00 && bytes[1] == 0x00 && (bytes[2] == 0x01 || bytes[2] == 0x02) && bytes[3] == 0x00
    ) return "ico"
    if (bytes.size >= 12 &&
        bytes[4] == 0x66 && bytes[5] == 0x74 && bytes[6] == 0x79 && bytes[7] == 0x70 &&
        bytes[8] == 0x61 && bytes[9] == 0x76 && bytes[10] == 0x69 && (bytes[11] == 0x66 || bytes[11] == 0x73)
    ) return "avif"
    return null
}

private val DATA_IMAGE_URL_REGEX =
    Regex("""^data:image/(?:png|jpeg|jpg|gif|webp|bmp|svg\+xml|x-icon|avif);base64,[A-Za-z0-9+/=\s]+$""", RegexOption.IGNORE_CASE)

private val HTML_SNIFF_REGEX = Regex(
    """^<!doctype\s+html\b|^<(?:html|head|body|div|p|span|table|ul|ol|li|article|section|header|footer|main|h[1-6]|a|img|form)\b""",
    RegexOption.IGNORE_CASE,
)

private val XML_SNIFF_REGEX = Regex(
    """^<\?xml\b|^<([A-Za-z_][\w:.-]*)(?:\s[^>]*)?>[\s\S]*</\1>\s*$""",
)

fun readJson(value: DbCellValue): JsonElement? = when (value) {
    DbCellValue.Null, DbCellValue.NoField, is DbCellValue.Binary -> null
    is DbCellValue.JsonArr -> runCatching { Json.parseToJsonElement(value.rawJson) }.getOrNull()
    is DbCellValue.JsonObj -> runCatching { Json.parseToJsonElement(value.rawJson) }.getOrNull()
    is DbCellValue.Text -> {
        val trimmed = value.value.trim()
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]"))
        ) {
            runCatching { Json.parseToJsonElement(trimmed) }.getOrNull()
        } else {
            null
        }
    }
    else -> null
}

fun autodetectFormat(cells: List<CellDataCell>): CellDataFormat {
    if (cells.size != 1) return CellDataFormat.TEXT_WRAP
    val value = cells[0].value
    if (value == DbCellValue.Null || value == DbCellValue.NoField) return CellDataFormat.TEXT_WRAP
    if (value is DbCellValue.Binary) {
        return if (imageType(leadingBytes(value.base64, IMAGE_HEADER_BYTES)) != null) {
            CellDataFormat.PICTURE
        } else {
            CellDataFormat.TEXT
        }
    }
    if (value is DbCellValue.JsonArr || value is DbCellValue.JsonObj) return CellDataFormat.JSON
    if (value is DbCellValue.Text) {
        val trimmed = value.value.trim()
        if (DATA_IMAGE_URL_REGEX.matches(trimmed)) return CellDataFormat.PICTURE
        if (readJson(value) != null) return CellDataFormat.JSON
        if (HTML_SNIFF_REGEX.containsMatchIn(trimmed)) return CellDataFormat.HTML
        if (XML_SNIFF_REGEX.containsMatchIn(trimmed)) return CellDataFormat.XML
    }
    return CellDataFormat.TEXT_WRAP
}

fun cellDataMessage(format: CellDataFormat, cells: List<CellDataCell>): String? {
    if (cells.isEmpty()) return "No Selected Data"
    if (cells.size > 1 && !format.multipleCells) return "Must be selected one cell"
    if ((format == CellDataFormat.JSON || format == CellDataFormat.JSON_EXPANDED || format == CellDataFormat.FORM) &&
        cells.any { readJson(it.value) == null }
    ) {
        return "Value is not valid JSON"
    }
    if (format == CellDataFormat.FORM) {
        val parsed = readJson(cells[0].value)
        if (parsed !is JsonObject) return "Value is not JSON object"
    }
    if (format == CellDataFormat.PICTURE && pictureUrl(cells[0].value) == null) {
        return "Value is not a recognized picture"
    }
    return null
}

fun binaryHex(value: DbCellValue.Binary): String {
    if (value.size == 0L || value.base64.isEmpty()) return "-- (0 bytes)"
    val bytes = runCatching { Base64.getDecoder().decode(value.base64) }.getOrDefault(ByteArray(0))
    val lines = mutableListOf<String>()
    var offset = 0
    while (offset < bytes.size) {
        val sliceEnd = min(bytes.size, offset + 16)
        val slice = bytes.sliceArray(offset until sliceEnd)
        val addr = offset.toString(16).uppercase(Locale.US).padStart(4, '0')
        val left = slice.take(8).joinToString(" ") { (it.toInt() and 0xFF).toString(16).uppercase(Locale.US).padStart(2, '0') }
        val right = slice.drop(8).joinToString(" ") { (it.toInt() and 0xFF).toString(16).uppercase(Locale.US).padStart(2, '0') }
        val hex = (if (right.isNotEmpty()) "$left  $right" else left).padEnd(48, ' ')
        val ascii = slice.map { b ->
            val code = b.toInt() and 0xFF
            if (code in 0x20..0x7E) code.toChar() else '.'
        }.joinToString("")
        lines += "$addr  $hex  $ascii"
        offset += 16
    }
    if (value.truncated) {
        lines += "… (${formatByteSize(value.size)} total, first ${bytes.size} bytes loaded)"
    }
    return lines.joinToString("\n")
}

fun cellText(value: DbCellValue, pretty: Boolean = false): String {
    if (value == DbCellValue.Null) return NULL_TEXT
    if (value == DbCellValue.NoField) return NO_FIELD_TEXT
    if (value is DbCellValue.Binary) return binaryHex(value)
    if (pretty) {
        val parsed = readJson(value)
        if (parsed != null) {
            return runCatching { prettyJson.encodeToString(JsonElement.serializer(), parsed) }
                .getOrDefault(formatCellEditText(value))
        }
    }
    return formatCellEditText(value)
}

fun cellsText(cells: List<CellDataCell>, pretty: Boolean = false): String {
    if (cells.size == 1) return cellText(cells[0].value, pretty)
    val lines = mutableListOf<String>()
    var row = mutableListOf<String>()
    var lastRow: Int? = null
    for (cell in cells) {
        if (lastRow != null && cell.row != lastRow) {
            lines += row.joinToString("\t")
            row = mutableListOf()
        }
        row += cellText(cell.value, pretty)
        lastRow = cell.row
    }
    if (row.isNotEmpty()) lines += row.joinToString("\t")
    return lines.joinToString("\n")
}

fun rowsJson(
    rowIndices: List<Int>,
    columnKeys: List<String>,
    columnNames: List<String>,
    valueAt: (column: Int, row: Int) -> DbCellValue,
): String {
    val rows = rowIndices.map { r ->
        val usedKeys = HashSet<String>()
        buildJsonObject {
            for (c in columnKeys.indices) {
                val v = valueAt(c, r)
                if (v == DbCellValue.NoField) continue
                val key = columnNames.getOrElse(c) { columnKeys[c] }
                val keyName = if (!usedKeys.add(key)) columnKeys[c].also { usedKeys.add(it) } else key
                put(keyName, dbCellValueToWireJson(v))
            }
        }
    }
    val element: JsonElement = if (rows.size == 1) rows[0] else JsonArray(rows)
    return prettyJson.encodeToString(JsonElement.serializer(), element)
}

data class CellDataField(
    val key: String,
    val value: String,
    val isNull: Boolean,
)

fun formJsonValue(value: JsonElement): Pair<String, Boolean> = when (value) {
    is JsonNull -> NULL_TEXT to true
    is JsonPrimitive -> value.content to false
    else -> value.toString() to false
}

fun cellDataFields(cell: CellDataCell): List<CellDataField> {
    val parsed = readJson(cell.value) as? JsonObject ?: return emptyList()
    return parsed.entries.map { (key, v) ->
        val (text, isNull) = formJsonValue(v)
        CellDataField(key = key, value = text, isNull = isNull)
    }
}

fun bytesRead(base64: String): Int {
    val clean = base64.replace(Regex("""\s+"""), "")
    if (clean.isEmpty()) return 0
    val pad = when {
        clean.endsWith("==") -> 2
        clean.endsWith("=") -> 1
        else -> 0
    }
    return max(0, floor((clean.length * 3.0) / 4.0).toInt() - pad)
}

fun pictureUrl(value: DbCellValue): String? {
    if (value is DbCellValue.Binary) {
        val kind = imageType(leadingBytes(value.base64, IMAGE_HEADER_BYTES)) ?: return null
        val mime = if (kind == "ico") "image/x-icon" else "image/$kind"
        return "data:$mime;base64,${value.base64}"
    }
    if (value is DbCellValue.Text) {
        val trimmed = value.value.trim()
        if (DATA_IMAGE_URL_REGEX.matches(trimmed)) return trimmed
    }
    return null
}

const val HTML_FRAME_POLICY =
    "default-src 'none'; img-src data:; font-src data:; style-src 'unsafe-inline'; form-action 'none'; base-uri 'none'"

private val META_OR_LINK_TAG_REGEX = Regex("""<(?:meta|link)\b[^>]*>""", RegexOption.IGNORE_CASE)

/**
 * Wrap cell text for the locked-down HTML preview (`htmlDocument` in `cell-data-formats.ts`).
 * Loops `<meta>` and `<link>` tag removal until clean so nested `<me<meta>ta>` cannot survive.
 */
fun htmlDocument(source: String): String {
    var body = source
    while (true) {
        val next = body.replace(META_OR_LINK_TAG_REGEX, "")
        if (next == body) break
        body = next
    }
    return buildString {
        append("<!doctype html><meta charset=\"utf-8\">")
        append("<meta http-equiv=\"Content-Security-Policy\" content=\"")
        append(HTML_FRAME_POLICY)
        append("\">")
        append("<style>:root{color-scheme:light dark;font:13px/1.45 system-ui,sans-serif;padding:10px;margin:0;overflow-wrap:anywhere}table{border-collapse:collapse}td,th{border:1px solid #8884;padding:4px 6px}</style>")
        append(body)
    }
}

// ── 9d.4 JSON Document View & Editor (`json-document.ts`) ────────────────────

data class DocumentColumn(
    val key: String,
    val pk: Boolean,
    val autoIncrement: Boolean = false,
)

sealed interface JsonDocumentsResult {
    data class Ok(val docs: List<JsonObject>) : JsonDocumentsResult
    data class Err(val error: String) : JsonDocumentsResult
}

/**
 * Parse one JSON object, a JSON array of objects, or JSON Lines / NDJSON (`readJsonDocuments` in `json-document.ts`).
 */
fun readJsonDocuments(text: String, single: Boolean = false): JsonDocumentsResult {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return JsonDocumentsResult.Err("Enter a JSON object")
    try {
        val parsed = Json.parseToJsonElement(trimmed)
        if (parsed is JsonObject) return JsonDocumentsResult.Ok(listOf(parsed))
        if (!single && parsed is JsonArray) {
            if (parsed.isEmpty()) return JsonDocumentsResult.Err("The JSON array has no documents")
            val docs = mutableListOf<JsonObject>()
            for ((idx, item) in parsed.withIndex()) {
                val obj = item as? JsonObject
                    ?: return JsonDocumentsResult.Err("Item ${idx + 1} is not a JSON object")
                docs += obj
            }
            return JsonDocumentsResult.Ok(docs)
        }
        return JsonDocumentsResult.Err(
            if (single) "Row document must be a JSON object"
            else "Enter a JSON object, an array of objects, or one object per line",
        )
    } catch (firstErr: Throwable) {
        if (single || !trimmed.contains('\n')) {
            return JsonDocumentsResult.Err(firstErr.message ?: "Invalid JSON")
        }
    }

    val docs = mutableListOf<JsonObject>()
    val lines = trimmed.split(Regex("""\r?\n"""))
    for ((idx, rawLine) in lines.withIndex()) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        val parsed = try {
            Json.parseToJsonElement(line)
        } catch (err: Throwable) {
            return JsonDocumentsResult.Err("Line ${idx + 1}: ${err.message ?: "Invalid JSON"}")
        }
        val obj = parsed as? JsonObject
            ?: return JsonDocumentsResult.Err("Line ${idx + 1} is not a JSON object")
        docs += obj
    }
    return if (docs.isNotEmpty()) {
        JsonDocumentsResult.Ok(docs)
    } else {
        JsonDocumentsResult.Err("Enter a JSON object")
    }
}

/** Format a row's values as an indented JSON document (`rowDocumentText` in `json-document.ts`). */
fun rowDocumentText(columns: List<String>, row: Map<String, DbCellValue>): String {
    val obj = buildJsonObject {
        for (col in columns) {
            val v = row[col]
            if (v != null && v != DbCellValue.NoField) {
                put(col, dbCellValueToWireJson(v))
            }
        }
    }
    return prettyJson.encodeToString(JsonObject.serializer(), obj)
}

/**
 * Turn an edited JSON document back into [CellChange]s for a single row (`documentChanges` in `json-document.ts`).
 */
fun documentChanges(
    rowId: String,
    columns: List<DocumentColumn>,
    currentRow: Map<String, DbCellValue>,
    storedRow: Map<String, DbCellValue>,
    key: Map<String, DbCellValue>,
    doc: JsonObject,
    changeset: GridChangeset,
): Result<List<CellChange>> {
    val byKey = columns.associateBy { it.key }
    for (name in doc.keys) {
        if (name !in byKey) {
            return Result.failure(IllegalArgumentException("Unknown column \"$name\""))
        }
    }
    val changes = mutableListOf<CellChange>()
    for (col in columns) {
        if (col.key !in doc) continue
        val nextValue = parseDbCellValue(doc[col.key])
        val currentValue = currentRow[col.key] ?: DbCellValue.Null
        if (sameDbCellValue(nextValue, currentValue)) continue
        if (isCellLocked(LockColumn(pk = col.pk, autoIncrement = col.autoIncrement), rowId, changeset)) {
            return Result.failure(
                IllegalArgumentException("Column \"${col.key}\" is part of the row key and cannot be changed here"),
            )
        }
        val original = if (isNewRowId(rowId)) DbCellValue.NoField else (storedRow[col.key] ?: DbCellValue.Null)
        changes += CellChange(
            rowId = rowId,
            col = col.key,
            value = nextValue,
            key = key,
            original = original,
        )
    }
    return Result.success(changes)
}

/**
 * Validate a new row's JSON document against the table's columns (`newRowValues` in `json-document.ts`).
 */
fun newRowValues(
    columns: List<DocumentColumn>,
    doc: JsonObject,
): Result<Map<String, DbCellValue>> {
    val byKey = columns.associateBy { it.key }
    val values = LinkedHashMap<String, DbCellValue>()
    for ((name, rawEl) in doc) {
        val col = byKey[name]
            ?: return Result.failure(IllegalArgumentException("Unknown column \"$name\""))
        if (col.pk && col.autoIncrement) continue
        values[name] = parseDbCellValue(rawEl)
    }
    return Result.success(values)
}

// ── 9d.4 References & Master/Detail Filtering (`references.ts`, `reference-filter.ts`) ──

data class GridReference(
    val direction: String, // "out" (References tables) | "in" (Dependent tables)
    val constraintName: String,
    val columns: List<String>,
    val targetSchema: String?,
    val targetTable: String,
    val targetColumns: List<String>,
)

data class TableReferences(
    val outgoing: List<GridReference> = emptyList(),
    val incoming: List<GridReference> = emptyList(),
)

private fun qualifyRefTable(schema: String?, table: String, defaultSchema: String?): String =
    if (!schema.isNullOrBlank() && schema != defaultSchema) "$schema.$table" else table

fun tableReferences(structureJson: JsonObject?, defaultSchema: String? = "public"): TableReferences {
    if (structureJson == null) return TableReferences()
    val fks = (structureJson["foreignKeys"] as? JsonArray).orEmpty()
    val refs = (structureJson["referencedBy"] as? JsonArray).orEmpty()

    val outgoing = fks.mapNotNull { el ->
        val fk = el as? JsonObject ?: return@mapNotNull null
        val refTable = (fk["referencedTable"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        GridReference(
            direction = "out",
            constraintName = (fk["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            columns = ((fk["columns"] as? JsonArray).orEmpty()).mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            targetSchema = (fk["referencedSchema"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull,
            targetTable = refTable,
            targetColumns = ((fk["referencedColumns"] as? JsonArray).orEmpty()).mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        )
    }.sortedWith { a, b ->
        val qa = qualifyRefTable(a.targetSchema, a.targetTable, defaultSchema)
        val qb = qualifyRefTable(b.targetSchema, b.targetTable, defaultSchema)
        val cmp = qa.compareTo(qb)
        if (cmp != 0) cmp else a.columns.joinToString(",").compareTo(b.columns.joinToString(","))
    }

    val incoming = refs.mapNotNull { el ->
        val fk = el as? JsonObject ?: return@mapNotNull null
        val srcTable = (fk["table"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        GridReference(
            direction = "in",
            constraintName = (fk["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            columns = ((fk["referencedColumns"] as? JsonArray).orEmpty()).mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            targetSchema = (fk["schema"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull,
            targetTable = srcTable,
            targetColumns = ((fk["columns"] as? JsonArray).orEmpty()).mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        )
    }.sortedWith { a, b ->
        val qa = qualifyRefTable(a.targetSchema, a.targetTable, defaultSchema)
        val qb = qualifyRefTable(b.targetSchema, b.targetTable, defaultSchema)
        val cmp = qa.compareTo(qb)
        if (cmp != 0) cmp else a.targetColumns.joinToString(",").compareTo(b.targetColumns.joinToString(","))
    }

    return TableReferences(outgoing = outgoing, incoming = incoming)
}

fun hasReferences(refs: TableReferences): Boolean =
    refs.outgoing.isNotEmpty() || refs.incoming.isNotEmpty()

fun referenceId(ref: GridReference): String {
    val target = "${ref.targetSchema.orEmpty()}.${ref.targetTable}"
    val cols = "${ref.columns.joinToString(",")}->${ref.targetColumns.joinToString(",")}"
    return "${ref.direction}:$target:${ref.constraintName}:$cols"
}

fun referenceJoin(ref: GridReference): String {
    val pairs = ref.columns.mapIndexed { idx, col ->
        val target = ref.targetColumns.getOrElse(idx) { "?" }
        if (ref.direction == "out") "$col -> $target" else "$target -> $col"
    }
    return pairs.joinToString(", ")
}

fun referencesMatching(list: List<GridReference>, filter: String, defaultSchema: String? = "public"): List<GridReference> {
    if (filter.isBlank()) return list
    return list.filter { ref ->
        val tableLabel = qualifyRefTable(ref.targetSchema, ref.targetTable, defaultSchema)
        columnNameMatches(filter, tableLabel) ||
            columnNameMatches(filter, ref.targetTable) ||
            ref.columns.any { columnNameMatches(filter, it) } ||
            ref.targetColumns.any { columnNameMatches(filter, it) }
    }
}

fun keyValues(rowIndices: List<Int>, valueAt: (row: Int) -> DbCellValue): List<DbCellValue> {
    val values = mutableListOf<DbCellValue>()
    val seen = HashSet<String>()
    for (r in rowIndices) {
        val v = valueAt(r)
        if (v == DbCellValue.Null || v == DbCellValue.NoField || v is DbCellValue.Binary) continue
        val key = when (v) {
            is DbCellValue.Text -> "s:${v.value}"
            is DbCellValue.Num -> "n:${v.rawText}"
            is DbCellValue.Bool -> "b:${v.value}"
            else -> "j:$v"
        }
        if (seen.add(key)) values += v
    }
    return values
}

fun keyValuesFilter(values: List<DbCellValue>, kind: ColumnKind = ColumnKind.OTHER): String =
    if (values.isEmpty()) "NULL" else pickedCellValuesFilter(kind, values)

/**
 * Column filters for the detail grid below the master table (`detailKeyFilters` in `reference-filter.ts`).
 * When no master row is selected (or the master key is NULL), filters by `NULL` so the detail grid stays empty.
 */
fun detailKeyFilters(
    ref: GridReference,
    selectedMasterRows: List<Int>,
    masterValue: (columnName: String, row: Int) -> DbCellValue,
    targetKind: (targetColumn: String) -> ColumnKind = { ColumnKind.OTHER },
): Map<String, String> {
    val filters = LinkedHashMap<String, String>()
    val count = min(ref.columns.size, ref.targetColumns.size)
    for (i in 0 until count) {
        val sourceCol = ref.columns[i]
        val targetCol = ref.targetColumns[i]
        val values = keyValues(selectedMasterRows) { r -> masterValue(sourceCol, r) }
        filters[targetCol] = keyValuesFilter(values, targetKind(targetCol))
    }
    return filters
}

/**
 * Column filter for opening a referenced parent row in its own table tab (`referencedRowFilters` in `reference-filter.ts`).
 */
fun referencedRowFilters(
    fkColumn: String,
    value: DbCellValue,
    kind: ColumnKind = ColumnKind.OTHER,
): Map<String, String>? {
    if (value == DbCellValue.Null || value == DbCellValue.NoField || value is DbCellValue.Binary) return null
    val filter = keyValuesFilter(listOf(value), kind)
    if (filter == "NULL") return null
    return mapOf(fkColumn to filter)
}

// ── 9d.4 Generate SQL Dialog (`generate-sql.ts`) ─────────────────────────────

enum class GeneratedStatement(val id: String, val label: String) {
    INSERT("INSERT", "INSERT"),
    UPDATE("UPDATE", "UPDATE"),
    DELETE("DELETE", "DELETE"),
}

val STATEMENT_TYPES: List<GeneratedStatement> = GeneratedStatement.entries

fun takesValues(statement: GeneratedStatement): Boolean =
    statement == GeneratedStatement.INSERT || statement == GeneratedStatement.UPDATE

fun takesWhere(statement: GeneratedStatement): Boolean =
    statement == GeneratedStatement.UPDATE || statement == GeneratedStatement.DELETE

data class SqlSourceRow(
    val values: Map<String, DbCellValue>,
    val where: Map<String, DbCellValue>,
)

data class GeneratedSql(
    val sql: String,
    val preview: String,
    val statementCount: Int,
    val truncated: Boolean,
    val notice: String? = null,
)

const val GENERATED_SQL_MAX_CHARS = 250_000
const val PREVIEW_STATEMENTS = 200

private fun buildWhereClause(
    row: Map<String, DbCellValue>,
    whereColumns: List<String>,
    target: CopySqlTarget,
): String =
    whereColumns.joinToString(" AND ") { c ->
        val name = quoteIdentifier(c, target.dialect)
        val v = row[c] ?: DbCellValue.Null
        if (v == DbCellValue.Null || v == DbCellValue.NoField) {
            "$name IS NULL"
        } else {
            "$name = ${sqlLiteral(v, target.dialect, target.kinds[c])}"
        }
    }

private fun oneGeneratedStatement(
    statement: GeneratedStatement,
    row: SqlSourceRow,
    valueColumns: List<String>,
    whereColumns: List<String>,
    table: String,
    target: CopySqlTarget,
): String? = when (statement) {
    GeneratedStatement.INSERT -> {
        val cols = valueColumns.filter { row.values[it] != null && row.values[it] != DbCellValue.NoField }
        if (cols.isEmpty()) null
        else {
            val names = cols.joinToString(", ") { quoteIdentifier(it, target.dialect) }
            val vals = cols.joinToString(", ") { c ->
                sqlLiteral(row.values[c] ?: DbCellValue.Null, target.dialect, target.kinds[c])
            }
            "INSERT INTO $table ($names) VALUES ($vals);"
        }
    }
    GeneratedStatement.UPDATE -> {
        val cols = valueColumns.filter { row.values[it] != null && row.values[it] != DbCellValue.NoField }
        if (cols.isEmpty()) null
        else {
            val assignments = cols.joinToString(", ") { c ->
                "${quoteIdentifier(c, target.dialect)} = ${sqlLiteral(row.values[c] ?: DbCellValue.Null, target.dialect, target.kinds[c])}"
            }
            "UPDATE $table SET $assignments WHERE ${buildWhereClause(row.where, whereColumns, target)};"
        }
    }
    GeneratedStatement.DELETE -> {
        "DELETE FROM $table WHERE ${buildWhereClause(row.where, whereColumns, target)};"
    }
}

fun generateSql(
    statement: GeneratedStatement,
    rows: List<SqlSourceRow>,
    valueColumns: List<String>,
    whereColumns: List<String>,
    target: CopySqlTarget,
    maxChars: Int = GENERATED_SQL_MAX_CHARS,
): GeneratedSql {
    if (rows.isEmpty()) {
        return GeneratedSql(sql = "", preview = "", statementCount = 0, truncated = false, notice = "No rows to generate SQL from.")
    }
    if (takesValues(statement) && valueColumns.isEmpty()) {
        return GeneratedSql(sql = "", preview = "", statementCount = 0, truncated = false, notice = "Check at least one value column.")
    }
    if (takesWhere(statement) && whereColumns.isEmpty()) {
        return GeneratedSql(sql = "", preview = "", statementCount = 0, truncated = false, notice = "Check at least one WHERE column.")
    }

    val table = sqlTableName(target)
    val lines = mutableListOf<String>()
    val previewLines = mutableListOf<String>()
    var length = 0
    var truncated = false

    for (row in rows) {
        val line = oneGeneratedStatement(statement, row, valueColumns, whereColumns, table, target) ?: continue
        val added = if (lines.isEmpty()) line.length else line.length + 1
        if (lines.isNotEmpty() && length + added > maxChars) {
            truncated = true
            break
        }
        lines += line
        if (previewLines.size < PREVIEW_STATEMENTS) previewLines += line
        length += added
    }

    if (lines.isEmpty()) {
        return GeneratedSql(sql = "", preview = "", statementCount = 0, truncated = false, notice = "No rows have values for the checked columns.")
    }
    val sql = lines.joinToString("\n")
    val preview = if (lines.size > PREVIEW_STATEMENTS) {
        val more = NumberFormat.getIntegerInstance(Locale.US).format(lines.size - PREVIEW_STATEMENTS)
        previewLines.joinToString("\n") + "\n-- … $more more statement(s); Copy or Open in Query tab includes all ${NumberFormat.getIntegerInstance(Locale.US).format(lines.size)}"
    } else {
        sql
    }
    return GeneratedSql(
        sql = sql,
        preview = preview,
        statementCount = lines.size,
        truncated = truncated,
    )
}
