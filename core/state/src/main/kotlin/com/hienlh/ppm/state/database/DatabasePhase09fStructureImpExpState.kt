package com.hienlh.ppm.state.database

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
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
 * Phase 09f pure state & domain models for Database Structure View/Editor, DDL Save Preview/Apply,
 * Import / Export Wizard & Jobs, and SQLite Viewer:
 * - `src/shared/db-structure.ts`
 * - `src/shared/db-table-model.ts`
 * - `src/shared/db-structure-change.ts`
 * - `src/web/components/database/structure/structure-model.ts`
 * - `src/web/components/database/table-editor/table-editor-model.ts`
 * - `src/web/components/database/table-editor/structure-save-model.ts`
 * - `src/shared/db-impexp.ts`
 * - `src/web/components/database/impexp/impexp-state.ts`
 */

// ── 9f.1 Database Structure Models (`src/shared/db-structure.ts`, `structure-model.ts`) ──

data class DbStructureColumn(
    val ordinal: Int,
    val name: String,
    val type: String,
    val nullable: Boolean,
    val defaultValue: String? = null,
    val generated: String? = null,
    val autoIncrement: Boolean = false,
    val unsigned: Boolean? = null,
    val zeroFill: Boolean? = null,
    val comment: String? = null,
    val collation: String? = null,
    val charset: String? = null,
)

data class DbPrimaryKey(
    val name: String?,
    val columns: List<String>,
)

data class DbIndexKey(
    val column: String,
    val order: String = "ASC", // "ASC" | "DESC"
    val expression: Boolean? = null,
)

data class DbIndex(
    val name: String,
    val unique: Boolean,
    val primary: Boolean,
    val type: String? = null,
    val keys: List<DbIndexKey>,
    val filter: String? = null,
)

data class DbUniqueConstraint(
    val name: String?,
    val columns: List<String>,
)

data class DbForeignKey(
    val name: String?,
    val schema: String?,
    val table: String,
    val columns: List<String>,
    val referencedSchema: String?,
    val referencedTable: String,
    val referencedColumns: List<String>,
    val onUpdate: String? = null,
    val onDelete: String? = null,
)

data class DbCheckConstraint(
    val name: String?,
    val definition: String,
)

data class DbStructureObjectRef(
    val schema: String?,
    val name: String,
    val kind: String = "table", // "table" | "view" | "matview" | "procedure" | "function" | "trigger" | "sequence"
)

data class DbDependency(
    val direction: String, // "uses" | "usedBy"
    val `object`: DbStructureObjectRef,
    val via: String? = null,
)

data class DbTableStructure(
    val dialect: String, // "postgres" | "mysql" | "sqlite"
    val database: String? = null,
    val schema: String? = null,
    val table: String,
    val kind: String = "table", // "table" | "view" | "matview"
    val comment: String? = null,
    val engine: String? = null,
    val withoutRowId: Boolean? = null,
    val strict: Boolean? = null,
    val columns: List<DbStructureColumn> = emptyList(),
    val primaryKey: DbPrimaryKey? = null,
    val indexes: List<DbIndex> = emptyList(),
    val uniques: List<DbUniqueConstraint> = emptyList(),
    val foreignKeys: List<DbForeignKey> = emptyList(),
    val referencedBy: List<DbForeignKey> = emptyList(),
    val checks: List<DbCheckConstraint> = emptyList(),
    val dependencies: List<DbDependency> = emptyList(),
)

private fun JsonObject.strOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

private fun JsonObject.boolOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull

private fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull

private fun JsonObject.strList(key: String): List<String> =
    ((this[key] as? JsonArray).orEmpty()).mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.contentOrNull }

fun parseDbForeignKey(obj: JsonObject): DbForeignKey? {
    val table = obj.strOrNull("table") ?: ""
    val referencedTable = obj.strOrNull("referencedTable") ?: obj.strOrNull("refTable") ?: return null
    val cols = obj.strList("columns")
    val refCols = obj.strList("referencedColumns").ifEmpty { obj.strList("refColumns") }
    return DbForeignKey(
        name = obj.strOrNull("name"),
        schema = obj.strOrNull("schema"),
        table = table,
        columns = cols,
        referencedSchema = obj.strOrNull("referencedSchema"),
        referencedTable = referencedTable,
        referencedColumns = refCols,
        onUpdate = obj.strOrNull("onUpdate"),
        onDelete = obj.strOrNull("onDelete"),
    )
}

fun parseDbTableStructure(json: JsonObject): DbTableStructure {
    val dialect = json.strOrNull("dialect") ?: "postgres"
    val table = json.strOrNull("table").orEmpty()
    val columns = ((json["columns"] as? JsonArray).orEmpty()).mapIndexedNotNull { idx, el ->
        val c = el as? JsonObject ?: return@mapIndexedNotNull null
        val name = c.strOrNull("name") ?: return@mapIndexedNotNull null
        DbStructureColumn(
            ordinal = c.intOrNull("ordinal") ?: (idx + 1),
            name = name,
            type = c.strOrNull("type").orEmpty(),
            nullable = c.boolOrNull("nullable") ?: true,
            defaultValue = c.strOrNull("defaultValue"),
            generated = c.strOrNull("generated"),
            autoIncrement = c.boolOrNull("autoIncrement") ?: false,
            unsigned = c.boolOrNull("unsigned"),
            zeroFill = c.boolOrNull("zeroFill"),
            comment = c.strOrNull("comment"),
            collation = c.strOrNull("collation"),
            charset = c.strOrNull("charset"),
        )
    }
    val pkObj = json["primaryKey"] as? JsonObject
    val primaryKey = if (pkObj != null) {
        val pkCols = pkObj.strList("columns")
        if (pkCols.isNotEmpty() || pkObj.strOrNull("name") != null) {
            DbPrimaryKey(name = pkObj.strOrNull("name"), columns = pkCols)
        } else null
    } else null

    val indexes = ((json["indexes"] as? JsonArray).orEmpty()).mapNotNull { el ->
        val idxObj = el as? JsonObject ?: return@mapNotNull null
        val name = idxObj.strOrNull("name") ?: return@mapNotNull null
        val keys = ((idxObj["keys"] as? JsonArray).orEmpty()).mapNotNull { kEl ->
            val kObj = kEl as? JsonObject ?: return@mapNotNull null
            val col = kObj.strOrNull("column") ?: return@mapNotNull null
            DbIndexKey(
                column = col,
                order = if (kObj.strOrNull("order") == "DESC") "DESC" else "ASC",
                expression = kObj.boolOrNull("expression"),
            )
        }
        DbIndex(
            name = name,
            unique = idxObj.boolOrNull("unique") ?: false,
            primary = idxObj.boolOrNull("primary") ?: false,
            type = idxObj.strOrNull("type"),
            keys = keys,
            filter = idxObj.strOrNull("filter"),
        )
    }

    val uniques = ((json["uniques"] as? JsonArray).orEmpty()).mapNotNull { el ->
        val uObj = el as? JsonObject ?: return@mapNotNull null
        DbUniqueConstraint(
            name = uObj.strOrNull("name"),
            columns = uObj.strList("columns"),
        )
    }

    val foreignKeys = ((json["foreignKeys"] as? JsonArray).orEmpty()).mapNotNull { el ->
        (el as? JsonObject)?.let(::parseDbForeignKey)
    }

    val referencedBy = ((json["referencedBy"] as? JsonArray).orEmpty()).mapNotNull { el ->
        (el as? JsonObject)?.let(::parseDbForeignKey)
    }

    val checks = ((json["checks"] as? JsonArray).orEmpty()).mapNotNull { el ->
        val chObj = el as? JsonObject ?: return@mapNotNull null
        val def = chObj.strOrNull("definition") ?: return@mapNotNull null
        DbCheckConstraint(name = chObj.strOrNull("name"), definition = def)
    }

    val dependencies = ((json["dependencies"] as? JsonArray).orEmpty()).mapNotNull { el ->
        val depObj = el as? JsonObject ?: return@mapNotNull null
        val dir = depObj.strOrNull("direction") ?: return@mapNotNull null
        val objJson = depObj["object"] as? JsonObject ?: return@mapNotNull null
        val objName = objJson.strOrNull("name") ?: return@mapNotNull null
        DbDependency(
            direction = dir,
            `object` = DbStructureObjectRef(
                schema = objJson.strOrNull("schema"),
                name = objName,
                kind = objJson.strOrNull("kind") ?: "table",
            ),
            via = depObj.strOrNull("via"),
        )
    }

    return DbTableStructure(
        dialect = dialect,
        database = json.strOrNull("database"),
        schema = json.strOrNull("schema"),
        table = table,
        kind = json.strOrNull("kind") ?: "table",
        comment = json.strOrNull("comment"),
        engine = json.strOrNull("engine"),
        withoutRowId = json.boolOrNull("withoutRowId"),
        strict = json.boolOrNull("strict"),
        columns = columns,
        primaryKey = primaryKey,
        indexes = indexes,
        uniques = uniques,
        foreignKeys = foreignKeys,
        referencedBy = referencedBy,
        checks = checks,
        dependencies = dependencies,
    )
}

// ── `structure-model.ts` read-only view helpers ──

data class StructureColumnRow(
    val ordinal: Int,
    val name: String,
    val dataType: String,
    val notNull: Boolean,
    val isPrimaryKey: Boolean,
    val isAutoIncrement: Boolean,
    val isUnsigned: Boolean,
    val isZeroFill: Boolean,
    val defaultValue: String,
    val computedExpression: String,
    val comment: String,
)

data class StructureForeignKeyRow(
    val constraintName: String,
    val columns: String,
    val referencedSchema: String?,
    val referencedTable: String,
    val referencedLabel: String,
    val onUpdate: String,
    val onDelete: String,
)

fun columnRows(structure: DbTableStructure): List<StructureColumnRow> {
    val pkSet = structure.primaryKey?.columns?.toSet().orEmpty()
    return structure.columns.map { col ->
        StructureColumnRow(
            ordinal = col.ordinal,
            name = col.name,
            dataType = col.type,
            notNull = !col.nullable,
            isPrimaryKey = col.name in pkSet,
            isAutoIncrement = col.autoIncrement,
            isUnsigned = col.unsigned == true,
            isZeroFill = col.zeroFill == true,
            defaultValue = col.defaultValue.orEmpty(),
            computedExpression = col.generated.orEmpty(),
            comment = col.comment.orEmpty(),
        )
    }
}

fun foreignKeyRow(fk: DbForeignKey, currentSchema: String?): StructureForeignKeyRow {
    val pairText = fk.columns.mapIndexed { i, col ->
        "$col -> ${fk.referencedColumns.getOrNull(i) ?: "?"}"
    }.joinToString(", ")
    val showSchema = fk.referencedSchema != null && fk.referencedSchema != currentSchema
    val referencedLabel = if (showSchema) "${fk.referencedSchema}.${fk.referencedTable}" else fk.referencedTable
    return StructureForeignKeyRow(
        constraintName = fk.name.orEmpty(),
        columns = pairText,
        referencedSchema = fk.referencedSchema,
        referencedTable = fk.referencedTable,
        referencedLabel = referencedLabel,
        onUpdate = fk.onUpdate.orEmpty(),
        onDelete = fk.onDelete.orEmpty(),
    )
}

fun columnNames(columns: List<DbStructureColumn>, indices: Set<Int>): String {
    val list = if (indices.isNotEmpty()) {
        columns.filterIndexed { i, _ -> i in indices }
    } else {
        columns
    }
    return list.joinToString(", ") { it.name }
}

fun columnDefinition(col: DbStructureColumn, isPrimaryKey: Boolean): String {
    val parts = mutableListOf(col.name, col.type)
    if (col.unsigned == true && !Regex("""\bunsigned\b""", RegexOption.IGNORE_CASE).containsMatchIn(col.type)) {
        parts += "UNSIGNED"
    }
    if (col.zeroFill == true && !Regex("""\bzerofill\b""", RegexOption.IGNORE_CASE).containsMatchIn(col.type)) {
        parts += "ZEROFILL"
    }
    if (isPrimaryKey) parts += "PRIMARY KEY"
    if (!col.nullable) parts += "NOT NULL"
    if (col.autoIncrement) parts += "AUTO_INCREMENT"
    if (!col.defaultValue.isNullOrEmpty()) parts += "DEFAULT ${col.defaultValue}"
    if (!col.generated.isNullOrEmpty()) parts += "AS (${col.generated})"
    if (!col.comment.isNullOrEmpty()) parts += "COMMENT '${col.comment.replace("'", "''")}'"
    return parts.joinToString(" ")
}

fun columnDefinitions(structure: DbTableStructure, indices: Set<Int>): String {
    val pkSet = structure.primaryKey?.columns?.toSet().orEmpty()
    val list = if (indices.isNotEmpty()) {
        structure.columns.filterIndexed { i, _ -> i in indices }
    } else {
        structure.columns
    }
    return list.joinToString("\n") { col -> columnDefinition(col, col.name in pkSet) }
}

// ── 9f.2 Editable Table Model (`src/shared/db-table-model.ts`, `table-editor-model.ts`) ──

data class TableModelColumn(
    val id: String,
    val name: String,
    val dataType: String,
    val notNull: Boolean = false,
    val autoIncrement: Boolean = false,
    val isUnsigned: Boolean = false,
    val isZeroFill: Boolean = false,
    val defaultValue: String = "",
    val computedExpression: String = "",
    val comment: String = "",
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("dataType", dataType)
        put("notNull", notNull)
        put("autoIncrement", autoIncrement)
        put("isUnsigned", isUnsigned)
        put("isZeroFill", isZeroFill)
        put("defaultValue", defaultValue)
        put("computedExpression", computedExpression)
        put("comment", comment)
    }
}

data class TableModelPrimaryKey(
    val constraintName: String = "",
    val columnIds: List<String> = emptyList(),
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("constraintName", constraintName)
        put("columnIds", buildJsonArray { columnIds.forEach { add(JsonPrimitive(it)) } })
    }
}

data class TableModelIndexColumn(
    val columnId: String,
    val order: String = "ASC", // "ASC" | "DESC"
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("columnId", columnId)
        put("order", order)
    }
}

data class TableModelIndex(
    val id: String,
    val constraintName: String,
    val isUnique: Boolean = false,
    val indexType: String = "",
    val filter: String = "",
    val columns: List<TableModelIndexColumn> = emptyList(),
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("id", id)
        put("constraintName", constraintName)
        put("isUnique", isUnique)
        put("indexType", indexType)
        put("filter", filter)
        put("columns", buildJsonArray { columns.forEach { add(it.toJsonObject()) } })
    }
}

data class TableModelUnique(
    val id: String,
    val constraintName: String,
    val columnIds: List<String> = emptyList(),
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("id", id)
        put("constraintName", constraintName)
        put("columnIds", buildJsonArray { columnIds.forEach { add(JsonPrimitive(it)) } })
    }
}

data class TableModelForeignKey(
    val id: String,
    val constraintName: String,
    val columnIds: List<String> = emptyList(),
    val referencedSchema: String? = null,
    val referencedTable: String = "",
    val referencedColumns: List<String> = emptyList(),
    val onUpdateAction: String = "",
    val onDeleteAction: String = "",
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("id", id)
        put("constraintName", constraintName)
        put("columnIds", buildJsonArray { columnIds.forEach { add(JsonPrimitive(it)) } })
        put("referencedSchema", referencedSchema?.let(::JsonPrimitive) ?: JsonNull)
        put("referencedTable", referencedTable)
        put("referencedColumns", buildJsonArray { referencedColumns.forEach { add(JsonPrimitive(it)) } })
        put("onUpdateAction", onUpdateAction)
        put("onDeleteAction", onDeleteAction)
    }
}

data class TableModel(
    val dialect: String, // "postgres" | "mysql" | "sqlite"
    val schema: String? = null,
    val name: String,
    val comment: String = "",
    val tableEngine: String = "",
    val withoutRowId: Boolean = false,
    val strict: Boolean = false,
    val columns: List<TableModelColumn> = emptyList(),
    val primaryKey: TableModelPrimaryKey? = null,
    val indexes: List<TableModelIndex> = emptyList(),
    val uniques: List<TableModelUnique> = emptyList(),
    val foreignKeys: List<TableModelForeignKey> = emptyList(),
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("dialect", dialect)
        put("schema", schema?.let(::JsonPrimitive) ?: JsonNull)
        put("name", name)
        put("comment", comment)
        put("tableEngine", tableEngine)
        put("withoutRowId", withoutRowId)
        put("strict", strict)
        put("columns", buildJsonArray { columns.forEach { add(it.toJsonObject()) } })
        put("primaryKey", primaryKey?.toJsonObject() ?: JsonNull)
        put("indexes", buildJsonArray { indexes.forEach { add(it.toJsonObject()) } })
        put("uniques", buildJsonArray { uniques.forEach { add(it.toJsonObject()) } })
        put("foreignKeys", buildJsonArray { foreignKeys.forEach { add(it.toJsonObject()) } })
    }
}

enum class TableModelSection(val wire: String) {
    TABLE("table"),
    COLUMNS("columns"),
    PRIMARY_KEY("primaryKey"),
    INDEXES("indexes"),
    UNIQUES("uniques"),
    FOREIGN_KEYS("foreignKeys"),
}

data class ModelProblem(
    val section: TableModelSection,
    val itemId: String? = null,
    val message: String,
)

fun baseColumnId(name: String): String = "c:$name"

private fun uniqueSignature(columns: List<String>): String = columns.joinToString("\u0000")

fun ownIndexes(structure: DbTableStructure): List<DbIndex> {
    val uniqueSets = structure.uniques.map { uniqueSignature(it.columns) }.toSet()
    val uniqueNames = structure.uniques.mapNotNull { it.name }.toSet()
    return structure.indexes.filter { idx ->
        if (idx.primary) return@filter false
        if (idx.name in uniqueNames) return@filter false
        if (
            idx.unique &&
            idx.filter.isNullOrEmpty() &&
            idx.keys.all { it.expression != true && it.order == "ASC" } &&
            uniqueSignature(idx.keys.map { it.column }) in uniqueSets
        ) {
            return@filter false
        }
        true
    }
}

data class SplitMysqlTypeResult(
    val dataType: String,
    val isUnsigned: Boolean,
    val isZeroFill: Boolean,
)

private val ZEROFILL_RE = Regex("""\bzerofill\b""", RegexOption.IGNORE_CASE)
private val UNSIGNED_RE = Regex("""\bunsigned\b""", RegexOption.IGNORE_CASE)
private val MULTI_SPACE_RE = Regex("""\s+""")

fun splitMysqlType(col: DbStructureColumn): SplitMysqlTypeResult {
    var dataType = col.type
    val zeroFillInType = ZEROFILL_RE.containsMatchIn(dataType)
    val unsignedInType = UNSIGNED_RE.containsMatchIn(dataType)
    if (zeroFillInType || unsignedInType) {
        dataType = dataType
            .replace(ZEROFILL_RE, "")
            .replace(UNSIGNED_RE, "")
            .replace(MULTI_SPACE_RE, " ")
            .trim()
    }
    val isZeroFill = col.zeroFill == true || zeroFillInType
    val isUnsigned = col.unsigned == true || unsignedInType || isZeroFill
    return SplitMysqlTypeResult(
        dataType = dataType,
        isUnsigned = isUnsigned,
        isZeroFill = isZeroFill,
    )
}

fun declaredType(col: TableModelColumn, dialect: String): String {
    var type = col.dataType.trim()
    if (dialect != "mysql") return type
    if ((col.isUnsigned || col.isZeroFill) && !UNSIGNED_RE.containsMatchIn(type)) {
        type = "$type unsigned"
    }
    if (col.isZeroFill && !ZEROFILL_RE.containsMatchIn(type)) {
        type = "$type zerofill"
    }
    return type
}

fun modelFromStructure(structure: DbTableStructure): TableModel {
    val idByName = HashMap<String, String>()
    val columns = structure.columns.map { col ->
        val id = baseColumnId(col.name)
        idByName[col.name] = id
        val split = if (structure.dialect == "mysql") {
            splitMysqlType(col)
        } else {
            SplitMysqlTypeResult(
                dataType = col.type,
                isUnsigned = col.unsigned == true,
                isZeroFill = col.zeroFill == true,
            )
        }
        TableModelColumn(
            id = id,
            name = col.name,
            dataType = split.dataType,
            notNull = !col.nullable,
            autoIncrement = col.autoIncrement,
            isUnsigned = split.isUnsigned,
            isZeroFill = split.isZeroFill,
            defaultValue = col.defaultValue.orEmpty(),
            computedExpression = col.generated.orEmpty(),
            comment = col.comment.orEmpty(),
        )
    }

    val colId: (String) -> String = { name -> idByName[name] ?: baseColumnId(name) }

    val primaryKey = if (structure.primaryKey != null && structure.primaryKey.columns.isNotEmpty()) {
        TableModelPrimaryKey(
            constraintName = structure.primaryKey.name.orEmpty(),
            columnIds = structure.primaryKey.columns.map(colId),
        )
    } else null

    val indexes = ownIndexes(structure).map { idx ->
        TableModelIndex(
            id = "idx:${idx.name}",
            constraintName = idx.name,
            isUnique = idx.unique,
            indexType = idx.type.orEmpty(),
            filter = idx.filter.orEmpty(),
            columns = idx.keys
                .filter { it.expression != true }
                .map { key -> TableModelIndexColumn(columnId = colId(key.column), order = key.order) },
        )
    }

    val uniques = structure.uniques.mapIndexed { i, u ->
        TableModelUnique(
            id = "uq:${u.name ?: "${i + 1}:${u.columns.joinToString(",")}"}",
            constraintName = u.name.orEmpty(),
            columnIds = u.columns.map(colId),
        )
    }

    val foreignKeys = structure.foreignKeys.mapIndexed { i, fk ->
        TableModelForeignKey(
            id = "fk:${fk.name ?: "${i + 1}:${fk.columns.joinToString(",")}"}",
            constraintName = fk.name.orEmpty(),
            columnIds = fk.columns.map(colId),
            referencedSchema = fk.referencedSchema,
            referencedTable = fk.referencedTable,
            referencedColumns = fk.referencedColumns.toList(),
            onUpdateAction = fk.onUpdate.orEmpty(),
            onDeleteAction = fk.onDelete.orEmpty(),
        )
    }

    return TableModel(
        dialect = structure.dialect,
        schema = structure.schema,
        name = structure.table,
        comment = structure.comment.orEmpty(),
        tableEngine = structure.engine.orEmpty(),
        withoutRowId = structure.withoutRowId == true,
        strict = structure.strict == true,
        columns = columns,
        primaryKey = primaryKey,
        indexes = indexes,
        uniques = uniques,
        foreignKeys = foreignKeys,
    )
}

private var nextFallbackIdCounter = 1L

fun newItemId(prefix: String): String {
    val uid = runCatching { UUID.randomUUID().toString() }
        .getOrElse { "${System.currentTimeMillis().toString(36)}_${(nextFallbackIdCounter++).toString(36)}" }
    return "$prefix:$uid"
}

fun defaultColumnType(dialect: String): String = when (dialect) {
    "postgres" -> "integer"
    "mysql" -> "int"
    else -> "INTEGER"
}

fun blankColumn(dialect: String, index: Int, id: String = newItemId("col")): TableModelColumn =
    TableModelColumn(
        id = id,
        name = "column_$index",
        dataType = defaultColumnType(dialect),
        notNull = index == 1,
        autoIncrement = false,
        isUnsigned = false,
        isZeroFill = false,
        defaultValue = "",
        computedExpression = "",
        comment = "",
    )

fun newTableModel(dialect: String, schema: String?): TableModel {
    val first = blankColumn(dialect, 1, "col:initial_1").copy(name = "id", notNull = true)
    return TableModel(
        dialect = dialect,
        schema = schema,
        name = "new_table",
        comment = "",
        tableEngine = "",
        withoutRowId = false,
        strict = false,
        columns = listOf(first),
        primaryKey = TableModelPrimaryKey(constraintName = "", columnIds = listOf(first.id)),
        indexes = emptyList(),
        uniques = emptyList(),
        foreignKeys = emptyList(),
    )
}

fun columnById(model: TableModel, id: String): TableModelColumn? =
    model.columns.firstOrNull { it.id == id }

fun columnName(model: TableModel, id: String): String =
    columnById(model, id)?.name ?: id.removePrefix("c:")

fun isPrimaryKeyColumn(model: TableModel, columnId: String): Boolean =
    model.primaryKey?.columnIds?.contains(columnId) == true

fun isSelfReference(model: TableModel, fk: TableModelForeignKey): Boolean =
    fk.referencedTable == model.name && (fk.referencedSchema ?: null) == (model.schema ?: null)

fun setPrimaryKeyMember(model: TableModel, columnId: String, include: Boolean): TableModel {
    val current = model.primaryKey?.columnIds.orEmpty()
    val has = columnId in current
    if (include == has) return model
    val nextIds = if (include) current + columnId else current.filter { it != columnId }
    if (nextIds.isEmpty()) return model.copy(primaryKey = null)
    return model.copy(
        primaryKey = TableModelPrimaryKey(
            constraintName = model.primaryKey?.constraintName.orEmpty(),
            columnIds = nextIds,
        ),
    )
}

fun upsertColumn(
    model: TableModel,
    column: TableModelColumn,
    primaryKeyMember: Boolean? = null,
): TableModel {
    val idx = model.columns.indexOfFirst { it.id == column.id }
    val prev = if (idx >= 0) model.columns[idx] else null
    val columns = if (idx >= 0) {
        model.columns.mapIndexed { i, c -> if (i == idx) column else c }
    } else {
        model.columns + column
    }
    val foreignKeys = if (prev != null && prev.name != column.name) {
        model.foreignKeys.map { fk ->
            if (isSelfReference(model, fk)) {
                fk.copy(referencedColumns = fk.referencedColumns.map { c -> if (c == prev.name) column.name else c })
            } else fk
        }
    } else model.foreignKeys

    val next = model.copy(columns = columns, foreignKeys = foreignKeys)
    return if (primaryKeyMember == null) next else setPrimaryKeyMember(next, column.id, primaryKeyMember)
}

fun removeColumns(model: TableModel, ids: Set<String>): TableModel {
    if (ids.isEmpty()) return model
    val columns = model.columns.filter { it.id !in ids }
    val pkIds = model.primaryKey?.columnIds?.filter { it !in ids }.orEmpty()
    val primaryKey = if (pkIds.isNotEmpty()) {
        TableModelPrimaryKey(
            constraintName = model.primaryKey?.constraintName.orEmpty(),
            columnIds = pkIds,
        )
    } else null

    val indexes = model.indexes
        .map { idx -> idx.copy(columns = idx.columns.filter { it.columnId !in ids }) }
        .filter { it.columns.isNotEmpty() }

    val uniques = model.uniques
        .map { u -> u.copy(columnIds = u.columnIds.filter { it !in ids }) }
        .filter { it.columnIds.isNotEmpty() }

    val foreignKeys = model.foreignKeys
        .map { fk ->
            val keptCols = mutableListOf<String>()
            val keptRefs = mutableListOf<String>()
            fk.columnIds.forEachIndexed { i, id ->
                if (id !in ids) {
                    keptCols += id
                    keptRefs += fk.referencedColumns.getOrNull(i).orEmpty()
                }
            }
            fk.copy(columnIds = keptCols, referencedColumns = keptRefs)
        }
        .filter { it.columnIds.isNotEmpty() }

    return model.copy(
        columns = columns,
        primaryKey = primaryKey,
        indexes = indexes,
        uniques = uniques,
        foreignKeys = foreignKeys,
    )
}

fun <T> upsertListItem(list: List<T>, item: T, idOf: (T) -> String): List<T> {
    val idx = list.indexOfFirst { idOf(it) == idOf(item) }
    return if (idx >= 0) {
        list.mapIndexed { i, v -> if (i == idx) item else v }
    } else {
        list + item
    }
}

fun upsertIndex(model: TableModel, index: TableModelIndex): TableModel =
    model.copy(indexes = upsertListItem(model.indexes, index) { it.id })

fun upsertUnique(model: TableModel, unique: TableModelUnique): TableModel =
    model.copy(uniques = upsertListItem(model.uniques, unique) { it.id })

fun upsertForeignKey(model: TableModel, fk: TableModelForeignKey): TableModel =
    model.copy(foreignKeys = upsertListItem(model.foreignKeys, fk) { it.id })

fun <T> removeItem(list: List<T>, id: String, idOf: (T) -> String): List<T> =
    list.filter { idOf(it) != id }

private fun trimOrEmpty(value: String): String = value.trim()

private fun samePrimaryKey(a: TableModelPrimaryKey?, b: TableModelPrimaryKey?): Boolean {
    val aEmpty = a == null || a.columnIds.isEmpty()
    val bEmpty = b == null || b.columnIds.isEmpty()
    if (aEmpty && bEmpty) return true
    if (a == null || b == null) return false
    return trimOrEmpty(a.constraintName) == trimOrEmpty(b.constraintName) &&
        a.columnIds == b.columnIds
}

fun sameTableModel(a: TableModel, b: TableModel): Boolean {
    if (
        a.dialect != b.dialect ||
        (a.schema ?: null) != (b.schema ?: null) ||
        trimOrEmpty(a.name) != trimOrEmpty(b.name) ||
        a.comment != b.comment ||
        trimOrEmpty(a.tableEngine) != trimOrEmpty(b.tableEngine) ||
        a.withoutRowId != b.withoutRowId ||
        a.strict != b.strict
    ) {
        return false
    }
    if (a.columns.size != b.columns.size) return false
    for (i in a.columns.indices) {
        val ca = a.columns[i]
        val cb = b.columns[i]
        if (
            ca.id != cb.id ||
            trimOrEmpty(ca.name) != trimOrEmpty(cb.name) ||
            trimOrEmpty(ca.dataType) != trimOrEmpty(cb.dataType) ||
            ca.notNull != cb.notNull ||
            ca.autoIncrement != cb.autoIncrement ||
            ca.isUnsigned != cb.isUnsigned ||
            ca.isZeroFill != cb.isZeroFill ||
            trimOrEmpty(ca.defaultValue) != trimOrEmpty(cb.defaultValue) ||
            trimOrEmpty(ca.computedExpression) != trimOrEmpty(cb.computedExpression) ||
            ca.comment != cb.comment
        ) {
            return false
        }
    }
    if (!samePrimaryKey(a.primaryKey, b.primaryKey)) return false
    if (a.indexes.size != b.indexes.size) return false
    for (i in a.indexes.indices) {
        val ia = a.indexes[i]
        val ib = b.indexes[i]
        if (
            ia.id != ib.id ||
            trimOrEmpty(ia.constraintName) != trimOrEmpty(ib.constraintName) ||
            ia.isUnique != ib.isUnique ||
            trimOrEmpty(ia.indexType) != trimOrEmpty(ib.indexType) ||
            trimOrEmpty(ia.filter) != trimOrEmpty(ib.filter) ||
            ia.columns != ib.columns
        ) {
            return false
        }
    }
    if (a.uniques.size != b.uniques.size) return false
    for (i in a.uniques.indices) {
        val ua = a.uniques[i]
        val ub = b.uniques[i]
        if (
            ua.id != ub.id ||
            trimOrEmpty(ua.constraintName) != trimOrEmpty(ub.constraintName) ||
            ua.columnIds != ub.columnIds
        ) {
            return false
        }
    }
    if (a.foreignKeys.size != b.foreignKeys.size) return false
    for (i in a.foreignKeys.indices) {
        val fa = a.foreignKeys[i]
        val fb = b.foreignKeys[i]
        if (
            fa.id != fb.id ||
            trimOrEmpty(fa.constraintName) != trimOrEmpty(fb.constraintName) ||
            (fa.referencedSchema ?: null) != (fb.referencedSchema ?: null) ||
            trimOrEmpty(fa.referencedTable) != trimOrEmpty(fb.referencedTable) ||
            trimOrEmpty(fa.onUpdateAction) != trimOrEmpty(fb.onUpdateAction) ||
            trimOrEmpty(fa.onDeleteAction) != trimOrEmpty(fb.onDeleteAction) ||
            fa.columnIds != fb.columnIds ||
            fa.referencedColumns.map(::trimOrEmpty) != fb.referencedColumns.map(::trimOrEmpty)
        ) {
            return false
        }
    }
    return true
}

private data class NamedKeyEntry(val id: String, val name: String)

private fun namedKeyEntries(model: TableModel): List<NamedKeyEntry> {
    val entries = mutableListOf<NamedKeyEntry>()
    val pkName = model.primaryKey?.constraintName?.trim().orEmpty()
    if (pkName.isNotEmpty() && model.primaryKey?.columnIds?.isNotEmpty() == true) {
        entries += NamedKeyEntry(id = "pk", name = pkName)
    }
    for (idx in model.indexes) {
        val name = idx.constraintName.trim()
        if (name.isNotEmpty()) entries += NamedKeyEntry(id = idx.id, name = name)
    }
    for (uq in model.uniques) {
        val name = uq.constraintName.trim()
        if (name.isNotEmpty()) entries += NamedKeyEntry(id = uq.id, name = name)
    }
    for (fk in model.foreignKeys) {
        val name = fk.constraintName.trim()
        if (name.isNotEmpty()) entries += NamedKeyEntry(id = fk.id, name = name)
    }
    return entries
}

fun keyNameTaken(model: TableModel, name: String, exceptId: String? = null): Boolean {
    val needle = name.trim().lowercase()
    if (needle.isEmpty()) return false
    return namedKeyEntries(model).any { it.id != exceptId && it.name.lowercase() == needle }
}

fun columnProblems(
    model: TableModel,
    draft: TableModelColumn,
    editingId: String? = null,
): List<String> {
    val problems = mutableListOf<String>()
    val name = draft.name.trim()
    if (name.isEmpty()) problems += "Column name is required"
    if (draft.dataType.trim().isEmpty()) problems += "Data type is required"
    if (name.isNotEmpty()) {
        val lower = name.lowercase()
        val clash = model.columns.any { c -> c.id != (editingId ?: draft.id) && c.name.trim().lowercase() == lower }
        if (clash) problems += "Column \"$name\" already exists"
    }
    if (draft.autoIncrement && draft.computedExpression.trim().isNotEmpty()) {
        problems += "A column cannot be both autoincrement and computed"
    }
    return problems
}

fun keyProblems(
    label: String,
    constraintName: String,
    columnIds: List<String>,
    requireName: Boolean = false,
    model: TableModel? = null,
    editingId: String? = null,
): List<String> {
    val problems = mutableListOf<String>()
    val trimmed = constraintName.trim()
    if (requireName && trimmed.isEmpty()) {
        problems += "$label name is required"
    }
    if (trimmed.isNotEmpty() && model != null && keyNameTaken(model, trimmed, editingId)) {
        problems += "Constraint or index \"$trimmed\" already exists on this table"
    }
    if (columnIds.isEmpty()) {
        problems += "$label needs at least one column"
    }
    val seen = HashSet<String>()
    for (id in columnIds) {
        if (!seen.add(id)) {
            problems += "$label cannot list the same column twice"
            break
        }
    }
    return problems
}

fun foreignKeyProblems(
    fk: TableModelForeignKey,
    model: TableModel? = null,
    editingId: String? = null,
): List<String> {
    val problems = keyProblems("Foreign key", fk.constraintName, fk.columnIds, false, model, editingId ?: fk.id).toMutableList()
    if (fk.referencedTable.trim().isEmpty()) {
        problems += "Referenced table is required"
    }
    if (fk.referencedColumns.size != fk.columnIds.size || fk.referencedColumns.any { it.trim().isEmpty() }) {
        problems += "Each foreign key column needs a referenced column"
    }
    return problems
}

fun tableModelProblems(model: TableModel): List<ModelProblem> {
    val problems = mutableListOf<ModelProblem>()
    if (model.name.trim().isEmpty()) {
        problems += ModelProblem(section = TableModelSection.TABLE, message = "Table name is required")
    }
    if (model.columns.isEmpty()) {
        problems += ModelProblem(section = TableModelSection.COLUMNS, message = "Table needs at least one column")
    }
    val colIds = model.columns.map { it.id }.toSet()
    for (col in model.columns) {
        for (msg in columnProblems(model, col, col.id)) {
            problems += ModelProblem(section = TableModelSection.COLUMNS, itemId = col.id, message = msg)
        }
    }
    val pk = model.primaryKey
    if (pk != null && pk.columnIds.isNotEmpty()) {
        for (msg in keyProblems("Primary key", pk.constraintName, pk.columnIds, false, model, "pk")) {
            problems += ModelProblem(section = TableModelSection.PRIMARY_KEY, message = msg)
        }
        if (pk.columnIds.any { it !in colIds }) {
            problems += ModelProblem(section = TableModelSection.PRIMARY_KEY, message = "Primary key references a removed column")
        }
    }
    for (idx in model.indexes) {
        val ids = idx.columns.map { it.columnId }
        for (msg in keyProblems("Index", idx.constraintName, ids, true, model, idx.id)) {
            problems += ModelProblem(section = TableModelSection.INDEXES, itemId = idx.id, message = msg)
        }
        if (ids.any { it !in colIds }) {
            problems += ModelProblem(section = TableModelSection.INDEXES, itemId = idx.id, message = "Index references a removed column")
        }
    }
    for (uq in model.uniques) {
        for (msg in keyProblems("Unique constraint", uq.constraintName, uq.columnIds, false, model, uq.id)) {
            problems += ModelProblem(section = TableModelSection.UNIQUES, itemId = uq.id, message = msg)
        }
        if (uq.columnIds.any { it !in colIds }) {
            problems += ModelProblem(section = TableModelSection.UNIQUES, itemId = uq.id, message = "Unique constraint references a removed column")
        }
    }
    for (fk in model.foreignKeys) {
        for (msg in foreignKeyProblems(fk, model, fk.id)) {
            problems += ModelProblem(section = TableModelSection.FOREIGN_KEYS, itemId = fk.id, message = msg)
        }
        if (fk.columnIds.any { it !in colIds }) {
            problems += ModelProblem(section = TableModelSection.FOREIGN_KEYS, itemId = fk.id, message = "Foreign key references a removed column")
        }
    }
    return problems
}

private val NON_IDENT_RE = Regex("""[^a-zA-Z0-9_]+""")
private val EDGE_UNDERSCORE_RE = Regex("""^_+|_+$""")

fun autoConstraintName(
    kind: String, // "pk" | "idx" | "uq" | "fk"
    table: String,
    columnNames: List<String>,
): String {
    val clean: (String) -> String = { s ->
        s.trim().replace(NON_IDENT_RE, "_").replace(EDGE_UNDERSCORE_RE, "")
    }
    val t = clean(table).ifEmpty { "table" }
    val cols = columnNames.map(clean).filter { it.isNotEmpty() }.joinToString("_")
    return if (kind == "pk") "${t}_pkey" else listOf(t, cols, kind).filter { it.isNotEmpty() }.joinToString("_")
}

// ── `table-editor-model.ts` UI helpers & dialect metadata ──

data class EditorColumnRow(
    val id: String,
    val ordinal: Int,
    val name: String,
    val dataType: String,
    val notNull: Boolean,
    val isPrimaryKey: Boolean,
    val isAutoIncrement: Boolean,
    val isUnsigned: Boolean,
    val isZeroFill: Boolean,
    val defaultValue: String,
    val computedExpression: String,
    val comment: String,
)

data class EditorKeyRow(
    val id: String,
    val constraintName: String,
    val columns: String,
    val detail: String = "",
)

data class EditorForeignKeyRow(
    val id: String,
    val constraintName: String,
    val columns: String,
    val referencedSchema: String?,
    val referencedTable: String,
    val referencedLabel: String,
    val onUpdate: String,
    val onDelete: String,
)

val POSTGRES_TYPES = listOf(
    "integer", "bigint", "smallint", "serial", "bigserial",
    "numeric(10,2)", "real", "double precision",
    "text", "varchar(255)", "char(1)",
    "boolean", "uuid", "jsonb", "json", "bytea",
    "timestamptz", "timestamp", "date", "time",
)

val MYSQL_TYPES = listOf(
    "int", "bigint", "smallint", "tinyint", "tinyint(1)",
    "decimal(10,2)", "float", "double",
    "varchar(255)", "char(36)", "text", "longtext",
    "json", "blob",
    "datetime", "timestamp", "date", "time",
)

val SQLITE_TYPES = listOf(
    "INTEGER", "TEXT", "REAL", "NUMERIC", "BLOB",
)

val MYSQL_ENGINES = listOf(
    "InnoDB", "MyISAM", "MEMORY", "CSV", "ARCHIVE",
)

val MARIADB_ENGINES = listOf(
    "InnoDB", "Aria", "MyISAM", "MEMORY", "CSV", "ARCHIVE",
)

val FK_ACTIONS = listOf(
    "", "NO ACTION", "RESTRICT", "CASCADE", "SET NULL", "SET DEFAULT",
)

val MYSQL_INDEX_TYPES = listOf(
    "", "BTREE", "FULLTEXT", "SPATIAL", "HASH",
)

fun dataTypesFor(dialect: String): List<String> = when (dialect) {
    "postgres" -> POSTGRES_TYPES
    "mysql" -> MYSQL_TYPES
    else -> SQLITE_TYPES
}

fun enginesFor(dbType: String?): List<String> = when (dbType) {
    "mariadb" -> MARIADB_ENGINES
    "mysql" -> MYSQL_ENGINES
    else -> emptyList()
}

fun primaryKeyHasName(dialect: String): Boolean = dialect != "mysql"

fun editorColumnRows(model: TableModel): List<EditorColumnRow> {
    val pkSet = model.primaryKey?.columnIds?.toSet().orEmpty()
    return model.columns.mapIndexed { idx, col ->
        EditorColumnRow(
            id = col.id,
            ordinal = idx + 1,
            name = col.name,
            dataType = col.dataType,
            notNull = col.notNull,
            isPrimaryKey = col.id in pkSet,
            isAutoIncrement = col.autoIncrement,
            isUnsigned = col.isUnsigned,
            isZeroFill = col.isZeroFill,
            defaultValue = col.defaultValue,
            computedExpression = col.computedExpression,
            comment = col.comment,
        )
    }
}

fun primaryKeyRow(model: TableModel): EditorKeyRow? {
    val pk = model.primaryKey ?: return null
    if (pk.columnIds.isEmpty()) return null
    return EditorKeyRow(
        id = "pk",
        constraintName = pk.constraintName.ifEmpty { "(primary key)" },
        columns = pk.columnIds.joinToString(", ") { id -> columnName(model, id) },
    )
}

fun uniqueRows(model: TableModel): List<EditorKeyRow> =
    model.uniques.map { uq ->
        EditorKeyRow(
            id = uq.id,
            constraintName = uq.constraintName.ifEmpty { "(unnamed)" },
            columns = uq.columnIds.joinToString(", ") { id -> columnName(model, id) },
        )
    }

fun indexRows(model: TableModel): List<EditorKeyRow> =
    model.indexes.map { idx ->
        val cols = idx.columns.joinToString(", ") { c ->
            val name = columnName(model, c.columnId)
            if (c.order == "DESC") "$name DESC" else name
        }
        val details = mutableListOf<String>()
        if (idx.isUnique) details += "UNIQUE"
        if (idx.indexType.isNotEmpty()) details += idx.indexType
        if (idx.filter.isNotEmpty()) details += "WHERE ${idx.filter}"
        EditorKeyRow(
            id = idx.id,
            constraintName = idx.constraintName,
            columns = cols,
            detail = details.joinToString(" · "),
        )
    }

fun foreignKeyRows(model: TableModel): List<EditorForeignKeyRow> =
    model.foreignKeys.map { fk ->
        val pairs = fk.columnIds.mapIndexed { i, id ->
            "${columnName(model, id)} -> ${fk.referencedColumns.getOrNull(i) ?: "?"}"
        }.joinToString(", ")
        val showSchema = fk.referencedSchema != null && fk.referencedSchema != model.schema
        val referencedLabel = if (showSchema) "${fk.referencedSchema}.${fk.referencedTable}" else fk.referencedTable
        EditorForeignKeyRow(
            id = fk.id,
            constraintName = fk.constraintName.ifEmpty { "(unnamed)" },
            columns = pairs,
            referencedSchema = fk.referencedSchema,
            referencedTable = fk.referencedTable,
            referencedLabel = referencedLabel,
            onUpdate = fk.onUpdateAction,
            onDelete = fk.onDeleteAction,
        )
    }

fun modelColumnDefinitions(model: TableModel, selectedIds: Set<String>): String {
    val pkSet = model.primaryKey?.columnIds?.toSet().orEmpty()
    val cols = if (selectedIds.isNotEmpty()) {
        model.columns.filter { it.id in selectedIds }
    } else {
        model.columns
    }
    val fakeStructure = DbTableStructure(
        dialect = model.dialect,
        schema = model.schema,
        table = model.name,
        kind = "table",
        columns = cols.mapIndexed { i, c ->
            DbStructureColumn(
                ordinal = i + 1,
                name = c.name,
                type = c.dataType,
                nullable = !c.notNull,
                defaultValue = c.defaultValue.ifEmpty { null },
                generated = c.computedExpression.ifEmpty { null },
                autoIncrement = c.autoIncrement,
                unsigned = c.isUnsigned,
                zeroFill = c.isZeroFill,
                comment = c.comment.ifEmpty { null },
            )
        },
        primaryKey = TableModelPrimaryKey(
            constraintName = model.primaryKey?.constraintName.orEmpty(),
            columnIds = model.primaryKey?.columnIds.orEmpty(),
        ).takeIf { it.columnIds.isNotEmpty() }?.let { pk ->
            DbPrimaryKey(
                name = pk.constraintName.ifEmpty { null },
                columns = cols.filter { it.id in pkSet }.map { it.name },
            )
        },
    )
    return columnDefinitions(fakeStructure, emptySet())
}

fun modelColumnNames(model: TableModel, selectedIds: Set<String>): String {
    val cols = if (selectedIds.isNotEmpty()) {
        model.columns.filter { it.id in selectedIds }
    } else {
        model.columns
    }
    return cols.joinToString(", ") { it.name }
}

fun fkActionChoices(fk: TableModelForeignKey): List<String> {
    val set = LinkedHashSet(FK_ACTIONS)
    if (fk.onUpdateAction.isNotEmpty()) set += fk.onUpdateAction
    if (fk.onDeleteAction.isNotEmpty()) set += fk.onDeleteAction
    return set.toList()
}

private val BTREE_RE = Regex("""^btree$""", RegexOption.IGNORE_CASE)

fun mysqlIndexType(indexType: String): String {
    val trimmed = indexType.trim()
    return if (BTREE_RE.matches(trimmed)) "" else trimmed
}

fun mysqlIndexTypeChoices(indexType: String): List<String> {
    val current = mysqlIndexType(indexType)
    val set = LinkedHashSet(MYSQL_INDEX_TYPES)
    if (current.isNotEmpty() && set.none { it.equals(current, ignoreCase = true) }) {
        set += current
    }
    return set.toList()
}

fun withMysqlIndexType(value: String): Pair<String, Boolean?> {
    val normalized = mysqlIndexType(value)
    return if (normalized.equals("FULLTEXT", ignoreCase = true) || normalized.equals("SPATIAL", ignoreCase = true)) {
        normalized to false
    } else {
        normalized to null
    }
}

private val COLUMN_NUM_RE = Regex("""^column_(\d+)$""", RegexOption.IGNORE_CASE)

fun nextColumnNumber(model: TableModel): Int {
    var maxNum = model.columns.size
    for (col in model.columns) {
        val m = COLUMN_NUM_RE.matchEntire(col.name.trim())
        if (m != null) {
            val n = m.groupValues[1].toIntOrNull() ?: 0
            if (n > maxNum) maxNum = n
        }
    }
    return maxNum + 1
}

fun withTableName(model: TableModel, name: String): TableModel {
    val foreignKeys = model.foreignKeys.map { fk ->
        if (isSelfReference(model, fk)) fk.copy(referencedTable = name) else fk
    }
    return model.copy(name = name, foreignKeys = foreignKeys)
}

data class ReferencedTableChoice(
    val schema: String?,
    val name: String,
    val label: String,
    val value: String,
)

fun encodeTableChoice(schema: String?, name: String): String =
    "${schema.orEmpty()}\u0000$name"

fun decodeTableChoice(value: String): Pair<String?, String> {
    val idx = value.indexOf('\u0000')
    if (idx < 0) return null to value
    val schema = value.substring(0, idx).ifEmpty { null }
    return schema to value.substring(idx + 1)
}

fun referencedTableChoices(
    objects: List<DbObjectItem>,
    currentSchema: String?,
    model: TableModel? = null,
    fk: TableModelForeignKey? = null,
): List<ReferencedTableChoice> {
    val choices = mutableListOf<ReferencedTableChoice>()
    val seen = HashSet<String>()
    val push = { schema: String?, name: String ->
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) {
            val value = encodeTableChoice(schema, trimmed)
            if (seen.add(value)) {
                val showSchema = schema != null && schema != currentSchema
                choices += ReferencedTableChoice(
                    schema = schema,
                    name = trimmed,
                    label = if (showSchema) "$schema.$trimmed" else trimmed,
                    value = value,
                )
            }
        }
    }
    if (model != null) push(model.schema, model.name)
    if (fk != null && fk.referencedTable.trim().isNotEmpty()) {
        push(fk.referencedSchema, fk.referencedTable)
    }
    for (obj in objects) {
        if (obj.kind == "table") push(obj.schema, obj.name)
    }
    return choices.sortedWith { a, b -> a.label.compareTo(b.label, ignoreCase = true) }
}

// ── 9f.2 Structure DDL Preview / Apply (`src/shared/db-structure-change.ts`, `structure-save-model.ts`) ──

sealed interface StructureChange {
    val database: String?
    fun toJsonObject(): JsonObject

    data class Alter(
        override val database: String? = null,
        val target: TableModel,
    ) : StructureChange {
        override fun toJsonObject(): JsonObject = buildJsonObject {
            put("kind", "alter")
            if (database != null) put("database", database)
            put("target", target.toJsonObject())
        }
    }

    data class Create(
        override val database: String? = null,
        val target: TableModel,
    ) : StructureChange {
        override fun toJsonObject(): JsonObject = buildJsonObject {
            put("kind", "create")
            if (database != null) put("database", database)
            put("target", target.toJsonObject())
        }
    }

    data class DropTable(
        override val database: String? = null,
        val schema: String? = null,
        val table: String,
    ) : StructureChange {
        override fun toJsonObject(): JsonObject = buildJsonObject {
            put("kind", "dropTable")
            if (database != null) put("database", database)
            put("schema", schema?.let(::JsonPrimitive) ?: JsonNull)
            put("table", table)
        }
    }

    data class TruncateTable(
        override val database: String? = null,
        val schema: String? = null,
        val table: String,
    ) : StructureChange {
        override fun toJsonObject(): JsonObject = buildJsonObject {
            put("kind", "truncateTable")
            if (database != null) put("database", database)
            put("schema", schema?.let(::JsonPrimitive) ?: JsonNull)
            put("table", table)
        }
    }

    data class RenameTable(
        override val database: String? = null,
        val schema: String? = null,
        val table: String,
        val newName: String,
    ) : StructureChange {
        override fun toJsonObject(): JsonObject = buildJsonObject {
            put("kind", "renameTable")
            if (database != null) put("database", database)
            put("schema", schema?.let(::JsonPrimitive) ?: JsonNull)
            put("table", table)
            put("newName", newName)
        }
    }

    data class BackupTable(
        override val database: String? = null,
        val schema: String? = null,
        val table: String,
        val backupName: String,
    ) : StructureChange {
        override fun toJsonObject(): JsonObject = buildJsonObject {
            put("kind", "backupTable")
            if (database != null) put("database", database)
            put("schema", schema?.let(::JsonPrimitive) ?: JsonNull)
            put("table", table)
            put("backupName", backupName)
        }
    }

    data class RenameColumn(
        override val database: String? = null,
        val schema: String? = null,
        val table: String,
        val column: String,
        val newName: String,
    ) : StructureChange {
        override fun toJsonObject(): JsonObject = buildJsonObject {
            put("kind", "renameColumn")
            if (database != null) put("database", database)
            put("schema", schema?.let(::JsonPrimitive) ?: JsonNull)
            put("table", table)
            put("column", column)
            put("newName", newName)
        }
    }

    data class DropColumn(
        override val database: String? = null,
        val schema: String? = null,
        val table: String,
        val column: String,
    ) : StructureChange {
        override fun toJsonObject(): JsonObject = buildJsonObject {
            put("kind", "dropColumn")
            if (database != null) put("database", database)
            put("schema", schema?.let(::JsonPrimitive) ?: JsonNull)
            put("table", table)
            put("column", column)
        }
    }
}

private val BACKUP_TIMESTAMP_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US).withZone(ZoneOffset.UTC)

fun backupTableName(table: String, atEpochMillis: Long = System.currentTimeMillis()): String {
    val stamp = BACKUP_TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(atEpochMillis))
    return "${table}_backup_$stamp"
}

data class StructureStatement(
    val sql: String,
    val recreatesTable: Boolean = false,
)

data class StructurePreview(
    val dialect: String,
    val transactionalDdl: Boolean,
    val recreate: Boolean,
    val statements: List<StructureStatement>,
    val sql: String,
    val warnings: List<String> = emptyList(),
)

data class StructureFailure(
    val error: String,
    val failedIndex: Int,
    val ranCount: Int,
    val rolledBack: Boolean,
    val statements: List<String>,
)

enum class StatementOutcome(val wire: String, val label: String) {
    RAN("ran", "Ran"),
    ROLLED_BACK("rolledBack", "Rolled back"),
    FAILED("failed", "Failed"),
    SKIPPED("skipped", "Did not run"),
}

val OUTCOME_LABEL: Map<StatementOutcome, String> = StatementOutcome.entries.associateWith { it.label }

data class StatementReport(
    val index: Int,
    val sql: String,
    val outcome: StatementOutcome,
)

fun parseStructurePreview(json: JsonObject): StructurePreview? {
    val dialect = json.strOrNull("dialect") ?: return null
    val sql = (json["sql"] as? JsonPrimitive)?.contentOrNull ?: return null
    val statementsArr = json["statements"] as? JsonArray ?: return null
    val statements = statementsArr.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val stmtSql = obj.strOrNull("sql") ?: return@mapNotNull null
        StructureStatement(
            sql = stmtSql,
            recreatesTable = obj.boolOrNull("recreatesTable") ?: false,
        )
    }
    return StructurePreview(
        dialect = dialect,
        transactionalDdl = json.boolOrNull("transactionalDdl") ?: (dialect != "mysql"),
        recreate = json.boolOrNull("recreate") ?: false,
        statements = statements,
        sql = sql,
        warnings = json.strList("warnings"),
    )
}

fun newerPreviewOf(payload: JsonObject?): StructurePreview? {
    if (payload == null) return null
    val previewObj = payload["preview"] as? JsonObject ?: return null
    return parseStructurePreview(previewObj)
}

fun failureOf(payload: JsonObject?): StructureFailure? {
    if (payload == null) return null
    val error = payload.strOrNull("error") ?: return null
    val failedIndex = payload.intOrNull("failedIndex") ?: return null
    val statements = payload.strList("statements")
    if (statements.isEmpty()) return null
    return StructureFailure(
        error = error,
        failedIndex = failedIndex,
        ranCount = payload.intOrNull("ranCount") ?: 0,
        rolledBack = payload.boolOrNull("rolledBack") ?: false,
        statements = statements,
    )
}

fun statementReports(failure: StructureFailure): List<StatementReport> =
    failure.statements.mapIndexed { index, sql ->
        val outcome = when {
            index == failure.failedIndex -> StatementOutcome.FAILED
            index < failure.ranCount -> if (failure.rolledBack) StatementOutcome.ROLLED_BACK else StatementOutcome.RAN
            else -> StatementOutcome.SKIPPED
        }
        StatementReport(index = index, sql = sql, outcome = outcome)
    }

// ── 9f.3 Database Import / Export (`src/shared/db-impexp.ts`, `impexp-state.ts`) ──

enum class ImpExpEndpointKind(val wire: String) {
    DATABASE("database"),
    CSV("csv"),
    JSON("json"),
    JSONL("jsonl"),
    SQL("sql"),
    XML("xml"),
    XLSX("xlsx"),
    DBF("dbf"),
    QUERY("query"),
}

data class ImpExpFormatOption(
    val id: String,
    val label: String,
    val ext: String,
    val exportable: Boolean,
    val importable: Boolean,
)

val IMPEXP_FILE_FORMATS: List<ImpExpFormatOption> = listOf(
    ImpExpFormatOption(id = "csv", label = "CSV files", ext = ".csv", exportable = true, importable = true),
    ImpExpFormatOption(id = "xlsx", label = "Excel files", ext = ".xlsx", exportable = true, importable = true),
    ImpExpFormatOption(id = "json", label = "JSON files", ext = ".json", exportable = true, importable = true),
    ImpExpFormatOption(id = "jsonl", label = "JSON lines / NDJSON files", ext = ".jsonl", exportable = true, importable = true),
    ImpExpFormatOption(id = "xml", label = "XML files", ext = ".xml", exportable = true, importable = true),
    ImpExpFormatOption(id = "dbf", label = "DBF files", ext = ".dbf", exportable = false, importable = true),
    ImpExpFormatOption(id = "sql", label = "SQL dump", ext = ".sql", exportable = true, importable = false),
)

fun importFormatOfFileName(fileName: String): String? {
    val lower = fileName.lowercase()
    if (lower.endsWith(".csv") || lower.endsWith(".tsv")) return "csv"
    if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) return "xlsx"
    if (lower.endsWith(".jsonl") || lower.endsWith(".ndjson")) return "jsonl"
    if (lower.endsWith(".json")) return "json"
    if (lower.endsWith(".xml")) return "xml"
    if (lower.endsWith(".dbf")) return "dbf"
    return null
}

data class LabeledChoice(val id: String, val label: String)

val CSV_DELIMITERS: List<LabeledChoice> = listOf(
    LabeledChoice(",", "Comma (,)"),
    LabeledChoice(";", "Semicolon (;)"),
    LabeledChoice("\t", "Tab"),
    LabeledChoice("|", "Pipe (|)"),
)

val CSV_RECORD_DELIMITERS: List<LabeledChoice> = listOf(
    LabeledChoice("\n", "LF (\\n)"),
    LabeledChoice("\r\n", "CRLF (\\r\\n)"),
)

val CSV_BOOLEAN_FORMATS: List<LabeledChoice> = listOf(
    LabeledChoice("true_false", "true / false"),
    LabeledChoice("1_0", "1 / 0"),
    LabeledChoice("yes_no", "yes / no"),
)

data class CsvWriteOptions(
    val delimiter: String = ",",
    val quoted: Boolean = false,
    val header: Boolean = true,
    val recordDelimiter: String = "\n",
    val booleanFormat: String = "true_false",
    val bom: Boolean = false,
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("delimiter", delimiter)
        put("quoted", quoted)
        put("header", header)
        put("recordDelimiter", recordDelimiter)
        put("booleanFormat", booleanFormat)
        put("bom", bom)
    }
}

data class CsvReadOptions(
    val delimiter: String = "auto",
    val header: Boolean = true,
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("delimiter", delimiter)
        put("header", header)
    }
}

data class JsonOptions(
    val rootField: String = "",
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("rootField", rootField)
    }
}

data class XmlWriteOptions(
    val rootElement: String = "rows",
    val rowElement: String = "row",
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("rootElement", rootElement)
        put("rowElement", rowElement)
    }
}

data class ExportFormatOptions(
    val csv: CsvWriteOptions = CsvWriteOptions(),
    val json: JsonOptions = JsonOptions(),
    val xml: XmlWriteOptions = XmlWriteOptions(),
    val xlsxInSingleFile: Boolean = false,
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("csv", csv.toJsonObject())
        put("json", json.toJsonObject())
        put("xml", xml.toJsonObject())
        put("xlsxInSingleFile", xlsxInSingleFile)
    }
}

data class ImportFormatOptions(
    val csv: CsvReadOptions = CsvReadOptions(),
    val json: JsonOptions = JsonOptions(),
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("csv", csv.toJsonObject())
        put("json", json.toJsonObject())
    }
}

val DEFAULT_EXPORT_OPTIONS = ExportFormatOptions()
val DEFAULT_IMPORT_OPTIONS = ImportFormatOptions()

data class ColumnMapEntry(
    val source: String,
    val dst: String,
    val ignore: Boolean = false,
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("source", source)
        put("dst", dst)
        put("ignore", ignore)
    }
}

fun columnMapProblem(columnMap: List<ColumnMapEntry>?): String? {
    if (columnMap.isNullOrEmpty()) return null
    val active = columnMap.filter { !it.ignore }
    if (active.isEmpty()) return "Keep at least one column mapped"
    val seen = HashSet<String>()
    for (entry in active) {
        val dst = entry.dst.trim()
        if (dst.isEmpty()) return "Source column \"${entry.source}\" has an empty destination column name"
        if (!seen.add(dst)) return "Destination column \"$dst\" is mapped more than once"
    }
    return null
}

const val IMPORT_MAX_FILE_BYTES: Long = 128L * 1024L * 1024L

val IMPORT_ACTIONS: List<LabeledChoice> = listOf(
    LabeledChoice("append", "Append data"),
    LabeledChoice("createTable", "Create table"),
    LabeledChoice("truncateAppend", "Truncate and import"),
    LabeledChoice("dropCreate", "Drop and create table"),
    LabeledChoice("insertUpdate", "Insert or update (upsert)"),
)

fun outputFileNameProblem(name: String): String? {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "Output file name is required"
    if (trimmed == "." || trimmed == "..") return "Output file name is not valid"
    if (trimmed.contains('/') || trimmed.contains('\\') || trimmed.contains("..")) {
        return "Output file name cannot contain a folder path"
    }
    if (trimmed.any { it.code < 0x20 || it in "<>:\"|?*" }) {
        return "Output file name contains invalid characters"
    }
    return null
}

private val NON_FILE_CHARS_RE = Regex("""[^\w.-]+""")
private val EDGE_UNDERSCORES_RE = Regex("""^_+|_+$""")
private val KNOWN_EXPORT_EXT_RE = Regex("""\.(csv|tsv|xlsx|xls|jsonl|ndjson|json|xml|dbf|sql)$""", RegexOption.IGNORE_CASE)

fun defaultExportFileName(sourceName: String, format: String): String {
    val clean = sourceName.trim().replace(NON_FILE_CHARS_RE, "_").replace(EDGE_UNDERSCORES_RE, "").ifEmpty { "data" }
    val ext = IMPEXP_FILE_FORMATS.firstOrNull { it.id == format }?.ext ?: ".$format"
    return if (clean.lowercase().endsWith(ext)) clean else "$clean$ext"
}

fun defaultZipName(database: String? = null): String {
    val base = (database ?: "export").trim().replace(NON_FILE_CHARS_RE, "_").replace(EDGE_UNDERSCORES_RE, "").ifEmpty { "export" }
    return "$base.zip"
}

fun importSourceName(fileName: String): String {
    val base = fileName.trim().replace('\\', '/').substringAfterLast('/')
    val stripped = base.replace(KNOWN_EXPORT_EXT_RE, "")
    return stripped.replace(Regex("""[^\w]+"""), "_").replace(EDGE_UNDERSCORES_RE, "").ifEmpty { "imported_data" }
}

data class ImpExpJobItemStatus(
    val source: String,
    val target: String,
    val state: String, // "pending" | "running" | "done" | "error" | "canceled"
    val rowsProcessed: Long = 0L,
    val totalRows: Long? = null,
    val error: String? = null,
)

data class ImpExpMessage(
    val id: Int,
    val at: Long,
    val level: String, // "info" | "warn" | "error" | "debug"
    val item: String? = null,
    val text: String,
)

data class ImpExpOutputFile(
    val name: String,
    val size: Long,
    val mimeType: String,
)

data class ImpExpJobStatus(
    val id: String,
    val kind: String, // "export" | "import"
    val state: String, // "running" | "done" | "error" | "canceled"
    val startedAt: Long,
    val finishedAt: Long? = null,
    val currentItem: String? = null,
    val items: List<ImpExpJobItemStatus> = emptyList(),
    val messages: List<ImpExpMessage> = emptyList(),
    val nextMessageId: Int = 1,
    val files: List<ImpExpOutputFile> = emptyList(),
    val zipFile: ImpExpOutputFile? = null,
)

data class ImportPreviewResult(
    val sources: List<String>,
    val source: String,
    val columns: List<String>,
    val rows: List<Map<String, DbCellValue>>,
    val truncated: Boolean = false,
)

fun parseImpExpJobStatus(json: JsonObject): ImpExpJobStatus {
    val items = ((json["items"] as? JsonArray).orEmpty()).mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        ImpExpJobItemStatus(
            source = o.strOrNull("source").orEmpty(),
            target = o.strOrNull("target").orEmpty(),
            state = o.strOrNull("state") ?: "pending",
            rowsProcessed = (o["rowsProcessed"] as? JsonPrimitive)?.longOrNull ?: 0L,
            totalRows = (o["totalRows"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull,
            error = o.strOrNull("error"),
        )
    }
    val messages = ((json["messages"] as? JsonArray).orEmpty()).mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        ImpExpMessage(
            id = o.intOrNull("id") ?: 0,
            at = (o["at"] as? JsonPrimitive)?.longOrNull ?: 0L,
            level = o.strOrNull("level") ?: "info",
            item = o.strOrNull("item"),
            text = o.strOrNull("text").orEmpty(),
        )
    }
    val files = ((json["files"] as? JsonArray).orEmpty()).mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        ImpExpOutputFile(
            name = o.strOrNull("name").orEmpty(),
            size = (o["size"] as? JsonPrimitive)?.longOrNull ?: 0L,
            mimeType = o.strOrNull("mimeType") ?: "application/octet-stream",
        )
    }
    val zipObj = json["zipFile"] as? JsonObject
    val zipFile = zipObj?.let {
        ImpExpOutputFile(
            name = it.strOrNull("name").orEmpty(),
            size = (it["size"] as? JsonPrimitive)?.longOrNull ?: 0L,
            mimeType = it.strOrNull("mimeType") ?: "application/zip",
        )
    }
    return ImpExpJobStatus(
        id = json.strOrNull("id").orEmpty(),
        kind = json.strOrNull("kind") ?: "export",
        state = json.strOrNull("state") ?: "running",
        startedAt = (json["startedAt"] as? JsonPrimitive)?.longOrNull ?: System.currentTimeMillis(),
        finishedAt = (json["finishedAt"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull,
        currentItem = json.strOrNull("currentItem"),
        items = items,
        messages = messages,
        nextMessageId = json.intOrNull("nextMessageId") ?: (messages.maxOfOrNull { it.id + 1 } ?: 1),
        files = files,
        zipFile = zipFile,
    )
}

fun parseImportPreview(json: JsonObject): ImportPreviewResult {
    val sources = json.strList("sources")
    val source = json.strOrNull("source").orEmpty()
    val columns = json.strList("columns")
    val rows = ((json["rows"] as? JsonArray).orEmpty()).mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val rowMap = LinkedHashMap<String, DbCellValue>()
        for ((k, v) in obj) {
            rowMap[k] = parseDbCellValue(v)
        }
        rowMap
    }
    return ImportPreviewResult(
        sources = sources,
        source = source,
        columns = columns,
        rows = rows,
        truncated = json.boolOrNull("truncated") ?: false,
    )
}

// ── `impexp-state.ts` Wizard State & Pure Reducers ──

const val TEMPLATE_SOURCE = "__template__"
const val QUERY_SOURCE = "Query"

data class ImpExpDatabase(
    val connectionId: Int?,
    val database: String? = null,
    val schema: String? = null,
)

data class ImpExpUpload(
    val localId: String,
    val fileName: String,
    val size: Long,
    val format: String?, // ImportFileFormat?
    val state: String, // "uploading" | "ready" | "error"
    val progress: Double = 0.0,
    val uploadId: String? = null,
    val sources: List<String> = emptyList(),
    val error: String? = null,
)

data class ImpExpRow(
    val id: String,
    val checked: Boolean = true,
    val source: String,
    val sourceLabel: String,
    val uploadLocalId: String? = null,
    val sheetName: String? = null,
    val target: String = "",
    val action: String = "createTable", // ImportAction
    val columnMap: List<ColumnMapEntry>? = null,
)

data class ImpExpForm(
    val sourceType: String, // ImpExpEndpointKind wire
    val targetType: String, // ImpExpEndpointKind wire
    val sourceDb: ImpExpDatabase,
    val targetDb: ImpExpDatabase,
    val sourceTables: List<String> = emptyList(),
    val querySql: String = "",
    val queryTitle: String = "",
    val uploads: List<ImpExpUpload> = emptyList(),
    val rows: List<ImpExpRow> = emptyList(),
    val archiveAsZip: Boolean = false,
    val zipFileName: String = "export.zip",
    val exportOptions: ExportFormatOptions = DEFAULT_EXPORT_OPTIONS,
    val importOptions: ImportFormatOptions = DEFAULT_IMPORT_OPTIONS,
)

private var nextImpExpIdCounter = 1L

fun uid(prefix: String): String =
    "$prefix-${(nextImpExpIdCounter++).toString(36)}-${System.currentTimeMillis().toString(36)}"

fun templateRow(): ImpExpRow = ImpExpRow(
    id = "row-template",
    checked = false,
    source = TEMPLATE_SOURCE,
    sourceLabel = "(all tables)",
    target = "",
    action = "createTable",
)

fun newImpExpForm(current: ImpExpDatabase): ImpExpForm = ImpExpForm(
    sourceType = "database",
    targetType = "csv",
    sourceDb = current.copy(),
    targetDb = current.copy(),
    sourceTables = emptyList(),
    querySql = "",
    queryTitle = "",
    uploads = emptyList(),
    rows = listOf(templateRow()),
    archiveAsZip = false,
    zipFileName = defaultZipName(current.database),
    exportOptions = DEFAULT_EXPORT_OPTIONS.copy(
        csv = DEFAULT_EXPORT_OPTIONS.csv.copy(),
        json = DEFAULT_EXPORT_OPTIONS.json.copy(),
        xml = DEFAULT_EXPORT_OPTIONS.xml.copy(),
    ),
    importOptions = DEFAULT_IMPORT_OPTIONS.copy(
        csv = DEFAULT_IMPORT_OPTIONS.csv.copy(),
        json = DEFAULT_IMPORT_OPTIONS.json.copy(),
    ),
)

fun isImportForm(form: ImpExpForm): Boolean = form.targetType == "database"

fun defaultRowTarget(form: ImpExpForm, row: ImpExpRow): String {
    if (row.source == TEMPLATE_SOURCE) return ""
    if (form.targetType == "database") return row.source
    return defaultExportFileName(row.source, form.targetType)
}

fun rowTarget(form: ImpExpForm, row: ImpExpRow): String {
    val trimmed = row.target.trim()
    return trimmed.ifEmpty { defaultRowTarget(form, row) }
}

private fun syncDatabaseRows(
    form: ImpExpForm,
    tables: List<String>,
    prevRows: List<ImpExpRow> = form.rows,
): List<ImpExpRow> {
    val bySource = prevRows.filter { it.source != TEMPLATE_SOURCE }.associateBy { it.source }
    val template = prevRows.firstOrNull { it.source == TEMPLATE_SOURCE } ?: templateRow()
    val next = mutableListOf(template)
    for (name in tables) {
        val prev = bySource[name]
        next += if (prev != null) {
            prev.copy(checked = true)
        } else {
            ImpExpRow(
                id = uid("row"),
                checked = true,
                source = name,
                sourceLabel = name,
                target = "",
                action = template.action,
            )
        }
    }
    return next
}

private fun syncQueryRows(form: ImpExpForm, title: String = form.queryTitle): List<ImpExpRow> {
    val name = title.trim().ifEmpty { QUERY_SOURCE }
    val prev = form.rows.firstOrNull { it.source != TEMPLATE_SOURCE }
    return listOf(
        ImpExpRow(
            id = prev?.id ?: uid("row"),
            checked = true,
            source = name,
            sourceLabel = QUERY_SOURCE,
            target = prev?.target.orEmpty(),
            action = prev?.action ?: "createTable",
            columnMap = prev?.columnMap,
        ),
    )
}

private fun syncUploadRows(form: ImpExpForm, uploads: List<ImpExpUpload> = form.uploads): List<ImpExpRow> {
    val prevByKey = form.rows
        .filter { it.uploadLocalId != null }
        .associateBy { "${it.uploadLocalId}:${it.sheetName ?: ""}" }
    val template = form.rows.firstOrNull { it.source == TEMPLATE_SOURCE } ?: templateRow()
    val next = mutableListOf(template)
    for (u in uploads) {
        val sheets = if (u.format == "xlsx" && u.sources.isNotEmpty()) u.sources else listOf<String?>(null)
        for (sheet in sheets) {
            val key = "${u.localId}:${sheet ?: ""}"
            val prev = prevByKey[key]
            val source = if (!sheet.isNullOrEmpty()) importSourceName(sheet) else importSourceName(u.fileName)
            val label = if (!sheet.isNullOrEmpty()) "${u.fileName} · $sheet" else u.fileName
            next += if (prev != null) {
                prev.copy(source = source, sourceLabel = label)
            } else {
                ImpExpRow(
                    id = uid("row"),
                    checked = true,
                    source = source,
                    sourceLabel = label,
                    uploadLocalId = u.localId,
                    sheetName = sheet,
                    target = "",
                    action = template.action,
                )
            }
        }
    }
    return next
}

fun gridExportForm(
    current: ImpExpDatabase,
    format: String,
    table: String? = null,
    sql: String? = null,
    title: String? = null,
): ImpExpForm {
    val base = newImpExpForm(current).copy(targetType = format)
    if (!sql.isNullOrEmpty()) {
        val qTitle = title?.ifEmpty { null } ?: table?.ifEmpty { null } ?: QUERY_SOURCE
        val next = base.copy(
            sourceType = "query",
            querySql = sql,
            queryTitle = qTitle,
        )
        return next.copy(rows = syncQueryRows(next, qTitle))
    }
    val tables = if (!table.isNullOrEmpty()) listOf(table) else emptyList()
    val next = base.copy(sourceType = "database", sourceTables = tables)
    return next.copy(rows = syncDatabaseRows(next, tables))
}

fun databaseExportForm(current: ImpExpDatabase, table: String? = null): ImpExpForm =
    gridExportForm(current, format = "csv", table = table)

fun importIntoForm(current: ImpExpDatabase, table: String? = null): ImpExpForm {
    val base = newImpExpForm(current)
    val template = templateRow().copy(
        target = table.orEmpty(),
        action = if (!table.isNullOrEmpty()) "append" else "createTable",
    )
    return base.copy(
        sourceType = "csv",
        targetType = "database",
        targetDb = current.copy(),
        rows = listOf(template),
    )
}

fun setSourceType(form: ImpExpForm, sourceType: String): ImpExpForm {
    var targetType = form.targetType
    if (sourceType != "database" && sourceType != "query") {
        targetType = "database"
    } else if (targetType == "database" && sourceType != "database" && sourceType != "query") {
        targetType = "csv"
    }
    val next = form.copy(sourceType = sourceType, targetType = targetType)
    return when (sourceType) {
        "database" -> next.copy(rows = syncDatabaseRows(next, next.sourceTables))
        "query" -> next.copy(rows = syncQueryRows(next))
        else -> {
            val matchingUploads = next.uploads.filter { it.format == sourceType }
            next.copy(rows = syncUploadRows(next, matchingUploads))
        }
    }
}

fun setTargetType(form: ImpExpForm, targetType: String): ImpExpForm {
    var sourceType = form.sourceType
    if (targetType != "database" && sourceType != "database" && sourceType != "query") {
        sourceType = "database"
    }
    val prevDefaultTargets = form.rows.associate { r -> r.id to defaultRowTarget(form, r) }
    val next = form.copy(sourceType = sourceType, targetType = targetType)
    val baseRows = if (sourceType != form.sourceType) {
        syncDatabaseRows(next, next.sourceTables)
    } else {
        next.rows
    }
    val rows = baseRows.map { r ->
        if (r.target.isEmpty() || r.target == prevDefaultTargets[r.id]) {
            r.copy(target = "")
        } else {
            r
        }
    }
    return next.copy(rows = rows)
}

fun setDatabase(form: ImpExpForm, side: String, patchConnectionId: Int? = form.sourceDb.connectionId, patchDatabase: String? = null, patchSchema: String? = null, updateConn: Boolean = false, updateDb: Boolean = false, updateSchema: Boolean = false): ImpExpForm {
    val cur = if (side == "source") form.sourceDb else form.targetDb
    val db = cur.copy(
        connectionId = if (updateConn) patchConnectionId else cur.connectionId,
        database = if (updateDb) patchDatabase else cur.database,
        schema = if (updateSchema) patchSchema else cur.schema,
    )
    if (side == "source") {
        val dbChanged = db.connectionId != form.sourceDb.connectionId ||
            db.database != form.sourceDb.database ||
            db.schema != form.sourceDb.schema
        val sourceTables = if (dbChanged) emptyList() else form.sourceTables
        val next = form.copy(
            sourceDb = db,
            sourceTables = sourceTables,
            zipFileName = if (form.zipFileName == defaultZipName(form.sourceDb.database)) {
                defaultZipName(db.database)
            } else {
                form.zipFileName
            },
        )
        return if (dbChanged && next.sourceType == "database") {
            next.copy(rows = syncDatabaseRows(next, emptyList(), listOf(templateRow())))
        } else {
            next
        }
    }
    return form.copy(targetDb = db)
}

fun setTables(form: ImpExpForm, tables: List<String>): ImpExpForm {
    val unique = tables.distinct()
    val next = form.copy(sourceTables = unique)
    return if (next.sourceType == "database") {
        next.copy(rows = syncDatabaseRows(next, unique))
    } else {
        next
    }
}

fun addTables(form: ImpExpForm, tables: List<String>): ImpExpForm =
    setTables(form, form.sourceTables + tables)

fun removeRow(form: ImpExpForm, rowId: String): ImpExpForm {
    val row = form.rows.firstOrNull { it.id == rowId }
    if (row == null || row.source == TEMPLATE_SOURCE) return form
    if (form.sourceType == "database") {
        return setTables(form, form.sourceTables.filter { it != row.source })
    }
    val rows = form.rows.filter { it.id != rowId }
    val uploads = if (row.uploadLocalId != null) {
        val stillUsed = rows.any { it.uploadLocalId == row.uploadLocalId }
        if (stillUsed) form.uploads else form.uploads.filter { it.localId != row.uploadLocalId }
    } else {
        form.uploads
    }
    return form.copy(rows = rows, uploads = uploads)
}

fun updateRow(
    form: ImpExpForm,
    rowId: String,
    checked: Boolean? = null,
    target: String? = null,
    action: String? = null,
    columnMap: List<ColumnMapEntry>? = null,
    updateColumnMap: Boolean = false,
): ImpExpForm {
    val row = form.rows.firstOrNull { it.id == rowId } ?: return form
    if (row.source == TEMPLATE_SOURCE) {
        return form.copy(
            rows = form.rows.map { r ->
                if (r.id == rowId) {
                    r.copy(
                        checked = checked ?: r.checked,
                        target = target ?: r.target,
                        action = action ?: r.action,
                    )
                } else {
                    r.copy(
                        action = action ?: r.action,
                        target = if (target != null && r.target.isEmpty()) target else r.target,
                    )
                }
            },
        )
    }
    return form.copy(
        rows = form.rows.map { r ->
            if (r.id == rowId) {
                r.copy(
                    checked = checked ?: r.checked,
                    target = target ?: r.target,
                    action = action ?: r.action,
                    columnMap = if (updateColumnMap) columnMap else r.columnMap,
                )
            } else r
        },
    )
}

fun addUploads(form: ImpExpForm, added: List<ImpExpUpload>): ImpExpForm {
    val firstFormat = added.firstOrNull { it.format != null }?.format
    val sourceType = if (form.sourceType == "database" || form.sourceType == "query") {
        firstFormat ?: "csv"
    } else if (firstFormat != null && form.uploads.isEmpty()) {
        firstFormat
    } else {
        form.sourceType
    }
    val uploads = form.uploads + added
    val next = form.copy(
        sourceType = sourceType,
        targetType = "database",
        uploads = uploads,
    )
    return next.copy(
        rows = syncUploadRows(next, uploads.filter { it.format == null || it.format == sourceType }),
    )
}

fun patchUpload(
    form: ImpExpForm,
    localId: String,
    state: String? = null,
    progress: Double? = null,
    uploadId: String? = null,
    sources: List<String>? = null,
    error: String? = null,
): ImpExpForm {
    val uploads = form.uploads.map { u ->
        if (u.localId == localId) {
            u.copy(
                state = state ?: u.state,
                progress = progress ?: u.progress,
                uploadId = uploadId ?: u.uploadId,
                sources = sources ?: u.sources,
                error = error ?: u.error,
            )
        } else u
    }
    val next = form.copy(uploads = uploads)
    return if (next.sourceType != "database" && next.sourceType != "query") {
        next.copy(
            rows = syncUploadRows(next, uploads.filter { it.format == null || it.format == next.sourceType }),
        )
    } else {
        next
    }
}

fun activeRows(form: ImpExpForm): List<ImpExpRow> =
    form.rows.filter { it.source != TEMPLATE_SOURCE && it.checked }

fun rowSourceLabel(form: ImpExpForm, row: ImpExpRow): String {
    if (row.source == TEMPLATE_SOURCE) return "(template)"
    if (form.sourceType == "query") return QUERY_SOURCE
    return row.sourceLabel
}

fun columnsLinkText(row: ImpExpRow): String {
    val map = row.columnMap
    if (map.isNullOrEmpty()) return "all"
    val kept = map.count { !it.ignore }
    val renamed = map.any { !it.ignore && it.dst.trim() != it.source }
    if (kept == map.size && !renamed) return "all (${map.size})"
    return "$kept / ${map.size}"
}

fun impExpTitle(form: ImpExpForm): String {
    if (form.targetType == "database") {
        val count = activeRows(form).size
        return if (count > 0) "Import ($count)" else "Import"
    }
    if (form.sourceType == "query") return "Export query"
    val count = activeRows(form).size
    return if (count > 0) "Export ($count)" else "Export"
}

fun uploadProblem(fileName: String, size: Long): String? {
    val format = importFormatOfFileName(fileName)
        ?: return "\"$fileName\" does not have a supported import extension (.csv, .tsv, .xlsx, .xls, .json, .jsonl, .ndjson, .xml, .dbf)"
    if (size > IMPORT_MAX_FILE_BYTES) {
        val mb = (IMPORT_MAX_FILE_BYTES / (1024L * 1024L)).toInt()
        return "\"$fileName\" ($format) is larger than the $mb MB import limit"
    }
    return null
}

fun runBlocker(form: ImpExpForm, targetReadonly: Boolean = false): String? {
    val rows = activeRows(form)
    if (rows.isEmpty()) {
        return when (form.sourceType) {
            "database" -> "Choose at least one table or view"
            "query" -> "Query export has no target row"
            else -> "Add at least one file to import"
        }
    }
    if (form.sourceType == "database" && form.sourceDb.connectionId == null) {
        return "Choose a source database connection"
    }
    if (form.sourceType == "query" && form.querySql.trim().isEmpty()) {
        return "Enter a SQL SELECT statement to export"
    }
    if (form.targetType == "database") {
        if (form.targetDb.connectionId == null) return "Choose a target database connection"
        if (targetReadonly) return "Target database connection is read-only"
    }
    if (form.sourceType != "database" && form.sourceType != "query") {
        val uploadById = form.uploads.associateBy { it.localId }
        for (row in rows) {
            val u = row.uploadLocalId?.let { uploadById[it] } ?: return "Upload for \"${row.sourceLabel}\" is missing"
            if (u.state == "uploading") return "Wait for \"${u.fileName}\" to finish uploading"
            if (u.state == "error" || u.uploadId == null) return u.error ?: "\"${u.fileName}\" failed to upload"
            if (u.format != form.sourceType) {
                return "\"${u.fileName}\" is not a ${form.sourceType.uppercase()} file"
            }
        }
    }
    val seenFileNames = HashSet<String>()
    for (row in rows) {
        val target = rowTarget(form, row)
        if (target.isEmpty()) return "Target is missing for \"${row.sourceLabel}\""
        if (form.targetType != "database") {
            val fileProblem = outputFileNameProblem(target)
            if (fileProblem != null) return "${row.sourceLabel}: $fileProblem"
            val key = target.lowercase()
            if (!seenFileNames.add(key)) {
                return "Two export items use the same output file name \"$target\""
            }
        }
        val mapProblem = columnMapProblem(row.columnMap)
        if (mapProblem != null) return "${row.sourceLabel}: $mapProblem"
    }
    if (form.targetType != "database" && form.archiveAsZip) {
        val zipProblem = outputFileNameProblem(form.zipFileName)
        if (zipProblem != null) return zipProblem
    }
    return null
}

fun exportRequest(form: ImpExpForm): JsonObject {
    val rows = activeRows(form)
    val sourceObj = if (form.sourceType == "query") {
        buildJsonObject {
            put("kind", "query")
            put("sql", form.querySql)
            put("title", form.queryTitle.trim().ifEmpty { QUERY_SOURCE })
            if (form.sourceDb.database != null) put("database", form.sourceDb.database)
        }
    } else {
        buildJsonObject {
            put("kind", "tables")
            if (form.sourceDb.database != null) put("database", form.sourceDb.database)
            if (form.sourceDb.schema != null) put("schema", form.sourceDb.schema)
            put(
                "items",
                buildJsonArray {
                    for (r in rows) {
                        add(
                            buildJsonObject {
                                put("sourceName", r.source)
                                put("outputName", rowTarget(form, r))
                                if (!r.columnMap.isNullOrEmpty()) {
                                    put("columnMap", buildJsonArray { r.columnMap.forEach { add(it.toJsonObject()) } })
                                }
                            },
                        )
                    }
                },
            )
        }
    }
    val first = rows.firstOrNull()
    val zipName = form.zipFileName.trim().ifEmpty { defaultZipName(form.sourceDb.database) }
    return buildJsonObject {
        put("source", sourceObj)
        put("targetKind", form.targetType)
        if (form.sourceType == "query" && first != null) {
            put("queryOutputName", rowTarget(form, first))
            if (!first.columnMap.isNullOrEmpty()) {
                put("queryColumnMap", buildJsonArray { first.columnMap.forEach { add(it.toJsonObject()) } })
            }
        }
        if (form.targetType != "database") {
            put("formatOptions", form.exportOptions.toJsonObject())
            put("archiveAsZip", form.archiveAsZip)
            put("zipFileName", if (zipName.lowercase().endsWith(".zip")) zipName else "$zipName.zip")
        } else {
            put("targetConnectionId", form.targetDb.connectionId ?: 0)
            if (form.targetDb.database != null) put("targetDatabase", form.targetDb.database)
            if (form.targetDb.schema != null) put("targetSchema", form.targetDb.schema)
            put(
                "tableTargets",
                buildJsonArray {
                    for (r in rows) {
                        add(
                            buildJsonObject {
                                put("sourceName", r.source)
                                put("targetTable", rowTarget(form, r))
                                put("action", r.action)
                                if (!r.columnMap.isNullOrEmpty()) {
                                    put("columnMap", buildJsonArray { r.columnMap.forEach { add(it.toJsonObject()) } })
                                }
                            },
                        )
                    }
                },
            )
        }
    }
}

fun importRequest(form: ImpExpForm): JsonObject {
    val rows = activeRows(form)
    val uploadById = form.uploads.associateBy { it.localId }
    return buildJsonObject {
        put("sourceFormat", form.sourceType)
        if (form.targetDb.database != null) put("database", form.targetDb.database)
        if (form.targetDb.schema != null) put("schema", form.targetDb.schema)
        put(
            "items",
            buildJsonArray {
                for (r in rows) {
                    val u = r.uploadLocalId?.let { uploadById[it] }
                    add(
                        buildJsonObject {
                            put("uploadId", u?.uploadId.orEmpty())
                            if (r.sheetName != null) put("sheetName", r.sheetName)
                            put("sourceLabel", r.sourceLabel)
                            put("targetTable", rowTarget(form, r))
                            put("action", r.action)
                            if (!r.columnMap.isNullOrEmpty()) {
                                put("columnMap", buildJsonArray { r.columnMap.forEach { add(it.toJsonObject()) } })
                            }
                        },
                    )
                }
            },
        )
        put("formatOptions", form.importOptions.toJsonObject())
    }
}

fun itemStatusText(item: ImpExpJobItemStatus?): String {
    if (item == null) return ""
    val numFmt = NumberFormat.getIntegerInstance(Locale.US)
    val rows = if (item.rowsProcessed > 0) "${numFmt.format(item.rowsProcessed)} rows" else ""
    return when (item.state) {
        "pending" -> "Waiting"
        "running" -> if (rows.isNotEmpty()) "Running ($rows)" else "Running..."
        "done" -> if (rows.isNotEmpty()) "Done ($rows)" else "Done"
        "canceled" -> if (rows.isNotEmpty()) "Stopped ($rows)" else "Stopped"
        "error" -> item.error ?: "Error"
        else -> ""
    }
}

val MESSAGE_LEVELS = listOf("error", "warn", "info", "debug")
val DEFAULT_SHOWN_LEVELS = setOf("error", "warn", "info")

fun filterMessages(
    messages: List<ImpExpMessage>,
    levels: Set<String>,
    filterText: String,
): List<ImpExpMessage> {
    val q = filterText.trim().lowercase()
    return messages.filter { m ->
        if (m.level !in levels) return@filter false
        if (q.isEmpty()) return@filter true
        val itemMatch = m.item?.lowercase()?.contains(q) == true
        itemMatch || m.text.lowercase().contains(q)
    }
}

fun formatImpExpDuration(startedAt: Long, finishedAt: Long? = null, now: Long = System.currentTimeMillis()): String {
    val ms = max(0L, (finishedAt ?: now) - startedAt)
    if (ms < 1000L) return "${ms}ms"
    val sec = ms / 1000.0
    if (sec < 60.0) return String.format(Locale.US, "%.1fs", sec)
    val min = floor(sec / 60.0).toInt()
    val rem = (sec - min * 60).roundToInt()
    return "${min}m ${rem}s"
}

private val CLOCK_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US).withZone(ZoneOffset.UTC)

fun messageClock(at: Long): String = CLOCK_FORMATTER.format(Instant.ofEpochMilli(at))

fun messageLines(messages: List<ImpExpMessage>): String =
    messages.joinToString("\n") { m ->
        val itemPart = if (!m.item.isNullOrEmpty()) " [${m.item}]" else ""
        "${messageClock(m.at)} ${m.level.uppercase()}$itemPart ${m.text}"
    }

fun levelCounts(messages: List<ImpExpMessage>): Map<String, Int> {
    val counts = mutableMapOf("error" to 0, "warn" to 0, "info" to 0, "debug" to 0)
    for (m in messages) {
        counts[m.level] = (counts[m.level] ?: 0) + 1
    }
    return counts
}

data class ImpExpJobRef(
    val id: String,
    val rowOrder: List<String>,
)

fun itemsByRow(form: ImpExpForm, job: ImpExpJobRef?, status: ImpExpJobStatus?): Map<String, ImpExpJobItemStatus> {
    val map = LinkedHashMap<String, ImpExpJobItemStatus>()
    if (status == null) return map
    val ids = job?.rowOrder ?: activeRows(form).map { it.id }
    status.items.forEachIndexed { idx, item ->
        val rowId = ids.getOrNull(idx)
        if (rowId != null) map[rowId] = item
    }
    return map
}

fun startingStatus(
    id: String,
    form: ImpExpForm,
    preserved: List<ImpExpMessage> = emptyList(),
    now: Long = System.currentTimeMillis(),
): ImpExpJobStatus {
    val items = activeRows(form).map { r ->
        ImpExpJobItemStatus(
            source = rowSourceLabel(form, r),
            target = rowTarget(form, r),
            state = "pending",
            rowsProcessed = 0L,
        )
    }
    return ImpExpJobStatus(
        id = id,
        kind = if (isImportForm(form)) "import" else "export",
        state = "running",
        startedAt = now,
        currentItem = items.firstOrNull()?.source,
        items = items,
        messages = preserved,
        nextMessageId = 1,
        files = emptyList(),
    )
}

fun resetColumnMap(sources: List<String>): List<ColumnMapEntry> =
    sources.map { s -> ColumnMapEntry(source = s, dst = s, ignore = false) }

fun sameColumnMap(entries: List<ColumnMapEntry>, sources: List<String>): Boolean =
    entries.size == sources.size &&
        entries.indices.all { i -> !entries[i].ignore && entries[i].source == sources[i] && entries[i].dst.trim() == sources[i] }

fun confirmedColumnMap(entries: List<ColumnMapEntry>, sources: List<String>): List<ColumnMapEntry>? {
    if (entries.isEmpty() || sameColumnMap(entries, sources)) return null
    return entries.map { e -> e.copy(dst = e.dst.trim()) }
}
