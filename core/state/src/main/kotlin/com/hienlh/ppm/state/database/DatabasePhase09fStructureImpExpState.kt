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
    val generated: Boolean = false,
    val computedExpression: String? = null,
    val autoIncrement: Boolean = false,
    val unsigned: Boolean? = null,
    val zeroFill: Boolean? = null,
    val comment: String? = null,
    val collation: String? = null,
    val charset: String? = null,
    val computedStored: Boolean = false,
    val onUpdate: String? = null,
    val identity: String? = null,
    val sqliteAutoincrement: Boolean = false,
) {
    val isGenerated: Boolean get() = generated || !computedExpression.isNullOrEmpty()
}

data class DbPrimaryKey(
    val name: String?,
    val columns: List<String>,
)

data class DbIndexKey(
    val column: String? = null,
    val order: String = "ASC", // "ASC" | "DESC"
    val expression: String? = null,
    val descending: Boolean = order.equals("DESC", ignoreCase = true),
    val nulls: String? = null,
    val length: Int? = null,
    val opclass: String? = null,
) {
    val isExpression: Boolean get() = !expression.isNullOrEmpty()
}

data class DbIndex(
    val name: String,
    val unique: Boolean,
    val primary: Boolean,
    val columns: List<String> = emptyList(),
    val method: String? = null,
    val keys: List<DbIndexKey> = emptyList(),
    val where: String? = null,
) {
    val type: String? get() = method
    val filter: String? get() = where
}

data class DbUniqueConstraint(
    val name: String?,
    val columns: List<String>,
)

data class DbForeignKey(
    val name: String?,
    val schema: String? = null,
    val table: String = "",
    val columns: List<String>,
    val referencedSchema: String?,
    val referencedTable: String,
    val referencedColumns: List<String>,
    val onUpdate: String? = null,
    val onDelete: String? = null,
) {
    val refSchema: String? get() = referencedSchema
    val refTable: String get() = referencedTable
    val refColumns: List<String> get() = referencedColumns
    val sourceSchema: String? get() = schema
    val sourceTable: String get() = table
}

data class DbCheckConstraint(
    val name: String?,
    val expression: String,
    val column: String? = null,
) {
    val definition: String get() = expression

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("name", name?.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
        put("expression", expression)
        if (!column.isNullOrEmpty()) put("column", column)
    }
}

data class DbTrigger(
    val name: String,
    val timing: String,
    val events: List<String>,
    val definition: String? = null,
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
    val database: String? = null,
    val schema: String? = null,
    val table: String,
    val kind: String = "table", // "table" | "view" | "matview" | "foreign"
    val comment: String? = null,
    val engine: String? = null,
    val withoutRowId: Boolean? = null,
    val strict: Boolean? = null,
    val columns: List<DbStructureColumn> = emptyList(),
    val primaryKey: DbPrimaryKey? = null,
    val indexes: List<DbIndex> = emptyList(),
    val uniques: List<DbUniqueConstraint> = emptyList(),
    val foreignKeys: List<DbForeignKey> = emptyList(),
    val references: List<DbForeignKey> = emptyList(),
    val checks: List<DbCheckConstraint> = emptyList(),
    val triggers: List<DbTrigger> = emptyList(),
    val dependencies: List<DbDependency> = emptyList(),
    val rowKey: List<String> = emptyList(),
    val rowKeyIsRowid: Boolean = false,
) {
    val name: String get() = table
    val withoutRowid: Boolean get() = withoutRowId == true
    val referencedBy: List<DbForeignKey> get() = references
}

private fun JsonObject.strOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

private fun JsonObject.boolOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull

private fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull

private fun JsonObject.strList(key: String): List<String> =
    ((this[key] as? JsonArray).orEmpty()).mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.contentOrNull }

fun parseDbForeignKey(obj: JsonObject, defaultRefTable: String? = null): DbForeignKey? {
    val sourceSchema = obj.strOrNull("sourceSchema") ?: obj.strOrNull("schema")
    val sourceTable = obj.strOrNull("sourceTable") ?: obj.strOrNull("table") ?: ""
    val referencedSchema = obj.strOrNull("refSchema") ?: obj.strOrNull("referencedSchema")
    val referencedTable = obj.strOrNull("refTable") ?: obj.strOrNull("referencedTable") ?: defaultRefTable ?: return null
    val cols = obj.strList("columns")
    val refCols = obj.strList("refColumns").ifEmpty { obj.strList("referencedColumns") }
    return DbForeignKey(
        name = obj.strOrNull("name"),
        schema = sourceSchema,
        table = sourceTable,
        columns = cols,
        referencedSchema = referencedSchema,
        referencedTable = referencedTable,
        referencedColumns = refCols,
        onUpdate = obj.strOrNull("onUpdate"),
        onDelete = obj.strOrNull("onDelete"),
    )
}

fun parseDbTableStructure(json: JsonObject): DbTableStructure {
    val table = json.strOrNull("name") ?: json.strOrNull("table").orEmpty()
    val columns = ((json["columns"] as? JsonArray).orEmpty()).mapIndexedNotNull { idx, el ->
        val c = el as? JsonObject ?: return@mapIndexedNotNull null
        val name = c.strOrNull("name") ?: return@mapIndexedNotNull null
        val computedExpr = c.strOrNull("computedExpression")
            ?: c.strOrNull("generated")?.takeIf { it != "true" && it != "false" }
        val isGenerated = c.boolOrNull("generated") ?: !computedExpr.isNullOrEmpty()
        DbStructureColumn(
            ordinal = c.intOrNull("ordinalPosition") ?: c.intOrNull("ordinal") ?: (idx + 1),
            name = name,
            type = c.strOrNull("type").orEmpty(),
            nullable = c.boolOrNull("nullable") ?: true,
            defaultValue = c.strOrNull("defaultValue"),
            generated = isGenerated,
            computedExpression = computedExpr,
            autoIncrement = c.boolOrNull("autoIncrement") ?: false,
            unsigned = c.boolOrNull("unsigned"),
            zeroFill = c.boolOrNull("zerofill") ?: c.boolOrNull("zeroFill"),
            comment = c.strOrNull("comment"),
            collation = c.strOrNull("collation"),
            charset = c.strOrNull("charset"),
            computedStored = c.boolOrNull("computedStored") ?: false,
            onUpdate = c.strOrNull("onUpdate"),
            identity = c.strOrNull("identity"),
            sqliteAutoincrement = c.boolOrNull("sqliteAutoincrement") ?: false,
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
        val idxCols = idxObj.strList("columns")
        val parsedKeys = ((idxObj["keys"] as? JsonArray).orEmpty()).mapNotNull { kEl ->
            val kObj = kEl as? JsonObject ?: return@mapNotNull null
            val col = kObj.strOrNull("column")
            val expr = kObj.strOrNull("expression")?.takeIf { it != "true" && it != "false" }
            if (col == null && expr == null) return@mapNotNull null
            val desc = kObj.boolOrNull("descending") ?: (kObj.strOrNull("order") == "DESC")
            DbIndexKey(
                column = col,
                order = if (desc) "DESC" else "ASC",
                expression = expr,
                descending = desc,
                nulls = kObj.strOrNull("nulls"),
                length = kObj.intOrNull("length"),
                opclass = kObj.strOrNull("opclass"),
            )
        }
        val keys = parsedKeys.ifEmpty {
            idxCols.map { col -> DbIndexKey(column = col, order = "ASC", expression = null, descending = false) }
        }
        DbIndex(
            name = name,
            unique = idxObj.boolOrNull("unique") ?: false,
            primary = idxObj.boolOrNull("primary") ?: false,
            columns = idxCols.ifEmpty { keys.mapNotNull { it.column } },
            method = idxObj.strOrNull("method") ?: idxObj.strOrNull("type"),
            keys = keys,
            where = idxObj.strOrNull("where") ?: idxObj.strOrNull("filter"),
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

    val referencesJson = (json["references"] as? JsonArray) ?: (json["referencedBy"] as? JsonArray)
    val references = referencesJson.orEmpty().mapNotNull { el ->
        (el as? JsonObject)?.let { parseDbForeignKey(it, defaultRefTable = table) }
    }

    val checks = ((json["checks"] as? JsonArray).orEmpty()).mapNotNull { el ->
        val chObj = el as? JsonObject ?: return@mapNotNull null
        val expr = chObj.strOrNull("expression") ?: chObj.strOrNull("definition") ?: return@mapNotNull null
        DbCheckConstraint(
            name = chObj.strOrNull("name"),
            expression = expr,
            column = chObj.strOrNull("column"),
        )
    }

    val triggers = ((json["triggers"] as? JsonArray).orEmpty()).mapNotNull { el ->
        val trObj = el as? JsonObject ?: return@mapNotNull null
        val trName = trObj.strOrNull("name") ?: return@mapNotNull null
        DbTrigger(
            name = trName,
            timing = trObj.strOrNull("timing").orEmpty(),
            events = trObj.strList("events"),
            definition = trObj.strOrNull("definition"),
        )
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
        database = json.strOrNull("database"),
        schema = json.strOrNull("schema"),
        table = table,
        kind = json.strOrNull("kind") ?: "table",
        comment = json.strOrNull("comment"),
        engine = json.strOrNull("engine"),
        withoutRowId = json.boolOrNull("withoutRowid") ?: json.boolOrNull("withoutRowId"),
        strict = json.boolOrNull("strict"),
        columns = columns,
        primaryKey = primaryKey,
        indexes = indexes,
        uniques = uniques,
        foreignKeys = foreignKeys,
        references = references,
        checks = checks,
        triggers = triggers,
        dependencies = dependencies,
        rowKey = json.strList("rowKey"),
        rowKeyIsRowid = json.boolOrNull("rowKeyIsRowid") ?: false,
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
            computedExpression = if (col.generated) col.computedExpression.orEmpty() else "",
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
    if (!col.defaultValue.isNullOrEmpty() && !col.generated) parts += "DEFAULT ${col.defaultValue}"
    if (col.generated && !col.computedExpression.isNullOrEmpty()) parts += "AS (${col.computedExpression})"
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
    val collation: String? = null,
    val computedStored: Boolean = false,
    val onUpdate: String? = null,
    val identity: String? = null,
    val sqliteAutoincrement: Boolean = false,
) {
    val type: String get() = dataType
    val unsigned: Boolean get() = isUnsigned
    val zerofill: Boolean get() = isZeroFill

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("type", dataType)
        put("notNull", notNull)
        put("autoIncrement", autoIncrement)
        put("defaultValue", defaultValue.ifEmpty { null }?.let(::JsonPrimitive) ?: JsonNull)
        put("computedExpression", computedExpression.ifEmpty { null }?.let(::JsonPrimitive) ?: JsonNull)
        put("comment", comment.ifEmpty { null }?.let(::JsonPrimitive) ?: JsonNull)
        put("unsigned", isUnsigned)
        put("zerofill", isZeroFill)
        put("collation", collation?.let(::JsonPrimitive) ?: JsonNull)
        put("computedStored", computedStored)
        put("onUpdate", onUpdate?.let(::JsonPrimitive) ?: JsonNull)
        put("identity", identity?.let(::JsonPrimitive) ?: JsonNull)
        put("sqliteAutoincrement", sqliteAutoincrement)
    }
}

data class TableModelPrimaryKey(
    val constraintName: String = "",
    val columnIds: List<String> = emptyList(),
    val id: String = "pk",
) {
    val name: String? get() = constraintName.trim().ifEmpty { null }
    val columns: List<String> get() = columnIds

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("id", id.ifBlank { "pk" })
        put("name", constraintName.trim().ifEmpty { null }?.let(::JsonPrimitive) ?: JsonNull)
        put("columns", buildJsonArray { columnIds.forEach { add(JsonPrimitive(it)) } })
    }
}

data class TableModelIndexColumn(
    val columnId: String? = null,
    val order: String = "ASC", // "ASC" | "DESC"
    val expression: String? = null,
    val nulls: String? = null, // "first" | "last"
    val length: Int? = null,
    val opclass: String? = null,
) {
    val descending: Boolean get() = order.equals("DESC", ignoreCase = true)

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("columnId", columnId?.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
        put("expression", expression?.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
        put("descending", descending)
        if (nulls == "first" || nulls == "last") put("nulls", nulls)
        if (length != null && length > 0) put("length", length)
        if (!opclass.isNullOrEmpty()) put("opclass", opclass)
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
    val name: String get() = constraintName
    val unique: Boolean get() = isUnique
    val method: String? get() = indexType.trim().takeIf { it.isNotEmpty() && !it.equals("btree", ignoreCase = true) }
    val where: String? get() = filter.trim().ifEmpty { null }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", constraintName)
        put("columns", buildJsonArray { columns.forEach { add(it.toJsonObject()) } })
        put("unique", isUnique)
        put("method", method?.let(::JsonPrimitive) ?: JsonNull)
        put("where", where?.let(::JsonPrimitive) ?: JsonNull)
    }
}

data class TableModelUnique(
    val id: String,
    val constraintName: String,
    val columnIds: List<String> = emptyList(),
) {
    val name: String? get() = constraintName.trim().ifEmpty { null }
    val columns: List<String> get() = columnIds

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name?.let(::JsonPrimitive) ?: JsonNull)
        put("columns", buildJsonArray { columnIds.forEach { add(JsonPrimitive(it)) } })
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
    val name: String? get() = constraintName.trim().ifEmpty { null }
    val columns: List<String> get() = columnIds
    val refSchema: String? get() = referencedSchema
    val refTable: String get() = referencedTable
    val refColumns: List<String> get() = referencedColumns
    val onUpdate: String? get() = onUpdateAction.trim().ifEmpty { null }
    val onDelete: String? get() = onDeleteAction.trim().ifEmpty { null }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name?.let(::JsonPrimitive) ?: JsonNull)
        put("columns", buildJsonArray { columnIds.forEach { add(JsonPrimitive(it)) } })
        put("refSchema", referencedSchema?.trim()?.ifEmpty { null }?.let(::JsonPrimitive) ?: JsonNull)
        put("refTable", referencedTable)
        put("refColumns", buildJsonArray { referencedColumns.forEach { add(JsonPrimitive(it)) } })
        put("onUpdate", onUpdate?.let(::JsonPrimitive) ?: JsonNull)
        put("onDelete", onDelete?.let(::JsonPrimitive) ?: JsonNull)
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
    val checks: List<DbCheckConstraint> = emptyList(),
) {
    val engine: String? get() = tableEngine.trim().ifEmpty { null }
    val withoutRowid: Boolean get() = withoutRowId

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("schema", schema?.let(::JsonPrimitive) ?: JsonNull)
        put("name", name)
        put("columns", buildJsonArray { columns.forEach { add(it.toJsonObject()) } })
        put("primaryKey", primaryKey?.takeIf { it.columnIds.isNotEmpty() }?.toJsonObject() ?: JsonNull)
        put("indexes", buildJsonArray { indexes.forEach { add(it.toJsonObject()) } })
        put("uniques", buildJsonArray { uniques.forEach { add(it.toJsonObject()) } })
        put("foreignKeys", buildJsonArray { foreignKeys.forEach { add(it.toJsonObject()) } })
        put(
            "checks",
            buildJsonArray {
                checks.forEach { chk ->
                    add(
                        buildJsonObject {
                            put("name", chk.name?.let(::JsonPrimitive) ?: JsonNull)
                            put("expression", chk.expression)
                            if (chk.column != null) put("column", chk.column)
                        },
                    )
                }
            },
        )
        put("comment", comment.ifEmpty { null }?.let(::JsonPrimitive) ?: JsonNull)
        put("engine", engine?.let(::JsonPrimitive) ?: JsonNull)
        put("withoutRowid", withoutRowId)
        put("strict", strict)
    }
}

/** Parses a [TableModel] from JSON (`src/web/lib/db-table-edit.ts` `isModel`). */
fun parseTableModel(obj: JsonObject?, defaultDialect: String = "postgres"): TableModel? {
    if (obj == null) return null
    val name = obj.strOrNull("name") ?: return null
    val colsArr = obj["columns"] as? JsonArray ?: return null
    val indexesArr = obj["indexes"] as? JsonArray ?: return null
    val uniquesArr = obj["uniques"] as? JsonArray ?: return null
    val fksArr = obj["foreignKeys"] as? JsonArray ?: return null
    val checksArr = obj["checks"] as? JsonArray ?: return null

    val dialect = obj.strOrNull("dialect")?.takeIf { it.isNotBlank() } ?: defaultDialect
    val schema = obj.strOrNull("schema")
    val comment = obj.strOrNull("comment").orEmpty()
    val engine = (obj.strOrNull("engine") ?: obj.strOrNull("tableEngine")).orEmpty()
    val withoutRowId = (obj.boolOrNull("withoutRowid") == true) || (obj.boolOrNull("withoutRowId") == true)
    val strict = obj.boolOrNull("strict") == true

    val columns = colsArr.mapNotNull { el ->
        val c = el as? JsonObject ?: return@mapNotNull null
        val cName = c.strOrNull("name") ?: return@mapNotNull null
        TableModelColumn(
            id = c.strOrNull("id")?.takeIf { it.isNotBlank() } ?: "c:$cName",
            name = cName,
            dataType = c.strOrNull("type") ?: c.strOrNull("dataType") ?: "text",
            notNull = c.boolOrNull("notNull") == true,
            autoIncrement = c.boolOrNull("autoIncrement") == true,
            isUnsigned = (c.boolOrNull("unsigned") == true) || (c.boolOrNull("isUnsigned") == true),
            isZeroFill = (c.boolOrNull("zerofill") == true) || (c.boolOrNull("isZeroFill") == true),
            defaultValue = c.strOrNull("defaultValue").orEmpty(),
            computedExpression = c.strOrNull("computedExpression").orEmpty(),
            computedStored = c.boolOrNull("computedStored") == true,
            comment = c.strOrNull("comment").orEmpty(),
            collation = c.strOrNull("collation"),
            onUpdate = c.strOrNull("onUpdate"),
            identity = c.strOrNull("identity"),
            sqliteAutoincrement = c.boolOrNull("sqliteAutoincrement") == true,
        )
    }

    val pkObj = obj["primaryKey"] as? JsonObject
    val primaryKey = if (pkObj != null) {
        val pkCols = pkObj.strList("columns").ifEmpty { pkObj.strList("columnIds") }
        if (pkCols.isNotEmpty()) {
            TableModelPrimaryKey(
                constraintName = pkObj.strOrNull("name") ?: pkObj.strOrNull("constraintName").orEmpty(),
                columnIds = pkCols,
                id = pkObj.strOrNull("id")?.takeIf { it.isNotBlank() } ?: "pk",
            )
        } else {
            null
        }
    } else {
        null
    }

    val indexes = indexesArr.mapNotNull { el ->
        val iObj = el as? JsonObject ?: return@mapNotNull null
        val iCols = (iObj["columns"] as? JsonArray)?.mapNotNull { kEl ->
            val kObj = kEl as? JsonObject ?: return@mapNotNull null
            val desc = (kObj.boolOrNull("descending") == true) || (kObj.strOrNull("order")?.equals("DESC", ignoreCase = true) == true)
            TableModelIndexColumn(
                columnId = kObj.strOrNull("columnId"),
                order = if (desc) "DESC" else "ASC",
                expression = kObj.strOrNull("expression"),
                nulls = kObj.strOrNull("nulls"),
                length = kObj.intOrNull("length"),
                opclass = kObj.strOrNull("opclass"),
            )
        }.orEmpty()
        val cName = iObj.strOrNull("name") ?: iObj.strOrNull("constraintName").orEmpty()
        TableModelIndex(
            id = iObj.strOrNull("id")?.takeIf { it.isNotBlank() } ?: "i:$cName",
            constraintName = cName,
            isUnique = (iObj.boolOrNull("unique") == true) || (iObj.boolOrNull("isUnique") == true),
            indexType = iObj.strOrNull("method") ?: iObj.strOrNull("indexType").orEmpty(),
            filter = iObj.strOrNull("where") ?: iObj.strOrNull("filter").orEmpty(),
            columns = iCols,
        )
    }

    val uniques = uniquesArr.mapNotNull { el ->
        val uObj = el as? JsonObject ?: return@mapNotNull null
        val uName = uObj.strOrNull("name") ?: uObj.strOrNull("constraintName").orEmpty()
        val uCols = uObj.strList("columns").ifEmpty { uObj.strList("columnIds") }
        TableModelUnique(
            id = uObj.strOrNull("id")?.takeIf { it.isNotBlank() } ?: "u:$uName",
            constraintName = uName,
            columnIds = uCols,
        )
    }

    val foreignKeys = fksArr.mapNotNull { el ->
        val fkObj = el as? JsonObject ?: return@mapNotNull null
        val fkName = fkObj.strOrNull("name") ?: fkObj.strOrNull("constraintName").orEmpty()
        val fkCols = fkObj.strList("columns").ifEmpty { fkObj.strList("columnIds") }
        val refCols = fkObj.strList("refColumns").ifEmpty { fkObj.strList("referencedColumns") }
        TableModelForeignKey(
            id = fkObj.strOrNull("id")?.takeIf { it.isNotBlank() } ?: "f:$fkName",
            constraintName = fkName,
            columnIds = fkCols,
            referencedSchema = fkObj.strOrNull("refSchema") ?: fkObj.strOrNull("referencedSchema"),
            referencedTable = fkObj.strOrNull("refTable") ?: fkObj.strOrNull("referencedTable").orEmpty(),
            referencedColumns = refCols,
            onUpdateAction = fkObj.strOrNull("onUpdate") ?: fkObj.strOrNull("onUpdateAction").orEmpty(),
            onDeleteAction = fkObj.strOrNull("onDelete") ?: fkObj.strOrNull("onDeleteAction").orEmpty(),
        )
    }

    val checks = checksArr.mapNotNull { el ->
        val chkObj = el as? JsonObject ?: return@mapNotNull null
        val expr = chkObj.strOrNull("expression") ?: return@mapNotNull null
        DbCheckConstraint(
            name = chkObj.strOrNull("name"),
            expression = expr,
            column = chkObj.strOrNull("column"),
        )
    }

    return TableModel(
        dialect = dialect,
        schema = schema,
        name = name,
        comment = comment,
        tableEngine = engine,
        withoutRowId = withoutRowId,
        strict = strict,
        columns = columns,
        primaryKey = primaryKey,
        indexes = indexes,
        uniques = uniques,
        foreignKeys = foreignKeys,
        checks = checks,
    )
}

/** The metadata field the Structure tab's unsaved edit is kept under (`src/web/lib/db-table-edit.ts`). */
const val TABLE_EDIT_FIELD = "tableEdit"

/** Unsaved structure edit kept in a `db-structure` tab's metadata (`src/web/lib/db-table-edit.ts`). */
data class TableEdit(
    val base: TableModel,
    val current: TableModel,
    val isNew: Boolean = false,
)

fun encodeTableEditJson(edit: TableEdit): JsonObject = buildJsonObject {
    put("base", edit.base.toJsonObject())
    put("current", edit.current.toJsonObject())
    if (edit.isNew) put("isNew", true)
}

fun readTableEdit(metadata: JsonObject?, defaultDialect: String = "postgres"): TableEdit? {
    val editObj = metadata?.get(TABLE_EDIT_FIELD) as? JsonObject ?: return null
    val dialect = metadata.strOrNull("dbType")?.let {
        when (it.lowercase()) {
            "mysql", "mariadb" -> "mysql"
            "sqlite" -> "sqlite"
            else -> "postgres"
        }
    } ?: defaultDialect
    val base = parseTableModel(editObj["base"] as? JsonObject, dialect) ?: return null
    val current = parseTableModel(editObj["current"] as? JsonObject, dialect) ?: return null
    val isNew = editObj.boolOrNull("isNew") == true
    return TableEdit(base = base, current = current, isNew = isNew)
}

/** True when a `db-structure` tab holds unsaved changes (`isStructureTabDirty` in `src/web/lib/db-table-edit.ts`). */
fun isStructureTabDirty(metadata: JsonObject?, defaultDialect: String = "postgres"): Boolean {
    val edit = readTableEdit(metadata, defaultDialect) ?: return false
    return !sameTableModel(edit.base, edit.current)
}

fun nextTableEdit(
    edit: TableEdit?,
    live: DbTableStructure?,
    dialect: String = "postgres",
    change: (TableModel) -> TableModel,
): TableEdit? {
    val start = edit ?: if (live != null) {
        val m = modelFromStructure(live, dialect)
        TableEdit(base = m, current = m, isNew = false)
    } else {
        null
    } ?: return null
    val current = change(start.current)
    if (!start.isNew && sameTableModel(start.base, current)) return null
    return start.copy(current = current)
}

fun resetTableEdit(edit: TableEdit?): TableEdit? =
    if (edit?.isNew == true) edit.copy(current = edit.base) else null

fun withTableEdit(metadata: JsonObject?, edit: TableEdit?): JsonObject = buildJsonObject {
    metadata?.forEach { (k, v) ->
        if (k != TABLE_EDIT_FIELD) put(k, v)
    }
    if (edit != null) {
        put(TABLE_EDIT_FIELD, encodeTableEditJson(edit))
    }
}

fun newTableTabMetadata(model: TableModel, tableNumber: Int, newTableId: String): JsonObject = buildJsonObject {
    put("newTableId", newTableId)
    put("tableNumber", tableNumber)
    put("schemaName", model.schema.orEmpty())
    put("newTable", true)
    put(TABLE_EDIT_FIELD, encodeTableEditJson(TableEdit(base = model, current = model, isNew = true)))
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
            idx.keys.all { !it.isExpression && it.order == "ASC" } &&
            uniqueSignature(idx.columns) in uniqueSets
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

fun modelFromStructure(structure: DbTableStructure, dialect: String = "postgres"): TableModel {
    val idByName = HashMap<String, String>()
    val columns = structure.columns.map { col ->
        val id = baseColumnId(col.name)
        idByName[col.name] = id
        val split = if (dialect == "mysql") {
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
            computedExpression = if (col.generated) col.computedExpression.orEmpty() else "",
            comment = col.comment.orEmpty(),
            collation = col.collation,
            computedStored = col.computedStored,
            onUpdate = col.onUpdate,
            identity = col.identity,
            sqliteAutoincrement = col.sqliteAutoincrement,
        )
    }

    val colId: (String) -> String = { name -> idByName[name] ?: baseColumnId(name) }

    val primaryKey = if (structure.primaryKey != null && structure.primaryKey.columns.isNotEmpty()) {
        TableModelPrimaryKey(
            constraintName = structure.primaryKey.name.orEmpty(),
            columnIds = structure.primaryKey.columns.map(colId),
            id = "pk",
        )
    } else null

    val indexes = ownIndexes(structure).map { idx ->
        TableModelIndex(
            id = "ix:${idx.name}",
            constraintName = idx.name,
            isUnique = idx.unique,
            indexType = idx.method?.takeIf { !it.equals("btree", ignoreCase = true) }.orEmpty(),
            filter = idx.where.orEmpty(),
            columns = idx.keys.map { key ->
                val known = key.column != null && idByName.containsKey(key.column)
                TableModelIndexColumn(
                    columnId = if (known) colId(key.column!!) else null,
                    order = key.order,
                    expression = if (known) null else (key.expression ?: key.column),
                    nulls = key.nulls,
                    length = key.length,
                    opclass = key.opclass,
                )
            },
        )
    }

    val uniques = structure.uniques.mapIndexed { i, u ->
        TableModelUnique(
            id = if (u.name != null) "uq:${u.name}" else "uq#$i",
            constraintName = u.name.orEmpty(),
            columnIds = u.columns.map(colId),
        )
    }

    val foreignKeys = structure.foreignKeys.mapIndexed { i, fk ->
        TableModelForeignKey(
            id = if (fk.name != null) "fk:${fk.name}" else "fk#$i",
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
        dialect = dialect,
        schema = structure.schema,
        name = structure.table,
        comment = structure.comment.orEmpty(),
        tableEngine = structure.engine.orEmpty(),
        withoutRowId = structure.withoutRowid == true,
        strict = structure.strict == true,
        columns = columns,
        primaryKey = primaryKey,
        indexes = indexes,
        uniques = uniques,
        foreignKeys = foreignKeys,
        checks = structure.checks,
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
    val first = blankColumn(dialect, 1, "col:initial_1").copy(name = "id", notNull = true, autoIncrement = true)
    return TableModel(
        dialect = dialect,
        schema = schema,
        name = "new_table",
        comment = "",
        tableEngine = "",
        withoutRowId = false,
        strict = false,
        columns = listOf(first),
        primaryKey = TableModelPrimaryKey(constraintName = "", columnIds = listOf(first.id), id = "pk"),
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
            id = model.primaryKey?.id ?: "pk",
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
    val removedNames = model.columns.filter { it.id in ids }.map { it.name }.toSet()
    val columns = model.columns.filter { it.id !in ids }
    val pkIds = model.primaryKey?.columnIds?.filter { it !in ids }.orEmpty()
    val primaryKey = if (pkIds.isNotEmpty()) {
        TableModelPrimaryKey(
            constraintName = model.primaryKey?.constraintName.orEmpty(),
            columnIds = pkIds,
            id = model.primaryKey?.id ?: "pk",
        )
    } else null

    val indexes = model.indexes
        .map { idx -> idx.copy(columns = idx.columns.filter { it.columnId == null || it.columnId !in ids }) }
        .filter { it.columns.isNotEmpty() }

    val uniques = model.uniques
        .map { u -> u.copy(columnIds = u.columnIds.filter { it !in ids }) }
        .filter { it.columnIds.isNotEmpty() }

    val foreignKeys = model.foreignKeys
        .map { fk ->
            val self = isSelfReference(model, fk)
            val keptCols = mutableListOf<String>()
            val keptRefs = mutableListOf<String>()
            fk.columnIds.forEachIndexed { i, id ->
                val refCol = fk.referencedColumns.getOrNull(i).orEmpty()
                if (id !in ids && !(self && refCol in removedNames)) {
                    keptCols += id
                    keptRefs += refCol
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
        if (idx.columns.isEmpty()) {
            problems += ModelProblem(section = TableModelSection.INDEXES, itemId = idx.id, message = "Index needs at least one column")
        }
        val ids = idx.columns.filter { it.expression == null }.mapNotNull { it.columnId }
        for (msg in keyProblems("Index", idx.constraintName, ids, true, model, idx.id).filter { !it.contains("at least one") }) {
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
    kind: String, // "pk" | "idx" | "uq" | "fk" | "PK" | "IX" | "UQ" | "FK"
    table: String,
    columnNames: List<String>,
): String {
    val clean: (String) -> String = { s ->
        s.trim().replace(NON_IDENT_RE, "_").replace(EDGE_UNDERSCORE_RE, "")
    }
    val t = clean(table).ifEmpty { "table" }
    val cols = columnNames.map(clean).filter { it.isNotEmpty() }.joinToString("_")
    return if (kind.equals("pk", ignoreCase = true)) {
        "${t}_pkey"
    } else {
        listOf(t, cols, kind.lowercase()).filter { it.isNotEmpty() }.joinToString("_")
    }
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

fun primaryKeyHasName(dialect: String): Boolean = dialect == "postgres"

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
        id = pk.id,
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
            val name = if (c.columnId != null) columnName(model, c.columnId) else c.expression.orEmpty()
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
                generated = c.computedExpression.isNotEmpty(),
                computedExpression = c.computedExpression.ifEmpty { null },
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
        val base: TableModel,
        val current: TableModel,
    ) : StructureChange {
        constructor(database: String? = null, target: TableModel) : this(
            database = database,
            base = target,
            current = target,
        )

        val target: TableModel get() = current

        override fun toJsonObject(): JsonObject = buildJsonObject {
            put("kind", "alter")
            put("base", base.toJsonObject())
            put("current", current.toJsonObject())
        }
    }

    data class Create(
        override val database: String? = null,
        val target: TableModel,
    ) : StructureChange {
        val current: TableModel get() = target

        override fun toJsonObject(): JsonObject = buildJsonObject {
            put("kind", "create")
            put("current", target.toJsonObject())
        }
    }

    data class DropTable(
        override val database: String? = null,
        val schema: String? = null,
        val table: String,
    ) : StructureChange {
        override fun toJsonObject(): JsonObject = buildJsonObject {
            put("kind", "drop-table")
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
            put("kind", "truncate-table")
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
            put("kind", "rename-table")
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
        val newName: String get() = backupName

        override fun toJsonObject(): JsonObject = buildJsonObject {
            put("kind", "backup-table")
            put("schema", schema?.let(::JsonPrimitive) ?: JsonNull)
            put("table", table)
            put("newName", backupName)
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
            put("kind", "rename-column")
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
            put("kind", "drop-column")
            put("schema", schema?.let(::JsonPrimitive) ?: JsonNull)
            put("table", table)
            put("column", column)
        }
    }
}

fun structurePreviewRequestJson(change: StructureChange): JsonObject = buildJsonObject {
    put("change", change.toJsonObject())
}

fun structureApplyRequestJson(
    change: StructureChange,
    sql: String,
    allowRecreate: Boolean = false,
): JsonObject = buildJsonObject {
    put("change", change.toJsonObject())
    if (allowRecreate) put("allowRecreate", true)
    put("sql", sql)
}

private val BACKUP_TIMESTAMP_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss", Locale.US).withZone(ZoneOffset.UTC)

fun backupTableName(table: String, atEpochMillis: Long = System.currentTimeMillis()): String {
    val stamp = BACKUP_TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(atEpochMillis))
    return "_${table}_$stamp"
}

data class StructureStatement(
    val sql: String,
    val recreatesTable: Boolean = false,
    val phase: String? = null, // "before" | "after" | null
)

data class StructurePreview(
    val dialect: String = "",
    val transactionalDdl: Boolean = true,
    val recreate: Boolean = false,
    val statements: List<StructureStatement> = emptyList(),
    val sql: String = "",
    val warnings: List<String> = emptyList(),
) {
    val transactional: Boolean get() = transactionalDdl
}

data class StructureFailure(
    val statement: String = "",
    val index: Int = 0,
    val applied: Int = 0,
    val total: Int = 0,
    val error: String = "",
    val failedIndex: Int = index,
    val ranCount: Int = applied,
    val rolledBack: Boolean = false,
    val statements: List<String> = emptyList(),
)

enum class StatementOutcome(val wire: String, val label: String) {
    RAN("ran", "Ran"),
    ROLLED_BACK("rolled-back", "Rolled back"),
    FAILED("failed", "Failed"),
    SKIPPED("not-run", "Did not run"),
}

val OUTCOME_LABEL: Map<StatementOutcome, String> = StatementOutcome.entries.associateWith { it.label }

data class StatementReport(
    val index: Int,
    val sql: String,
    val outcome: StatementOutcome,
)

fun parseStructurePreview(json: JsonObject, defaultDialect: String = ""): StructurePreview? {
    val dialect = json.strOrNull("dialect") ?: defaultDialect
    val sql = (json["sql"] as? JsonPrimitive)?.contentOrNull ?: return null
    val statementsArr = json["statements"] as? JsonArray ?: return null
    val statements = mutableListOf<StructureStatement>()
    for (el in statementsArr) {
        when (el) {
            is JsonObject -> {
                val stmtSql = el.strOrNull("sql") ?: return null
                val phase = el.strOrNull("phase")
                if (phase != null && phase != "before" && phase != "after") return null
                statements += StructureStatement(
                    sql = stmtSql,
                    recreatesTable = el.boolOrNull("recreatesTable") ?: false,
                    phase = phase,
                )
            }
            is JsonPrimitive -> {
                val stmtSql = el.takeIf { it !is JsonNull }?.contentOrNull ?: return null
                statements += StructureStatement(sql = stmtSql)
            }
            else -> return null
        }
    }
    val transactional = json.boolOrNull("transactional")
        ?: json.boolOrNull("transactionalDdl")
        ?: (if (dialect.isNotEmpty()) dialect != "mysql" else true)
    return StructurePreview(
        dialect = dialect,
        transactionalDdl = transactional,
        recreate = json.boolOrNull("recreate") ?: false,
        statements = statements,
        sql = sql,
        warnings = json.strList("warnings"),
    )
}

fun newerPreviewOf(payload: JsonObject?): StructurePreview? {
    if (payload == null) return null
    val d = (payload["data"] as? JsonObject) ?: (payload["preview"] as? JsonObject) ?: payload
    val hasRecreate = d.boolOrNull("recreate") != null
    val hasTransactional = d.boolOrNull("transactional") != null || d.boolOrNull("transactionalDdl") != null
    if (!hasRecreate || !hasTransactional) return null
    return parseStructurePreview(d)
}

fun failureOf(payload: JsonObject?): StructureFailure? {
    if (payload == null) return null
    val d = (payload["data"] as? JsonObject) ?: payload
    val index = d.intOrNull("index")
    val statement = d.strOrNull("statement")
    if (index != null && statement != null) {
        val applied = d.intOrNull("applied") ?: 0
        val total = d.intOrNull("total") ?: 0
        return StructureFailure(
            statement = statement,
            index = index,
            applied = applied,
            total = total,
            error = d.strOrNull("error") ?: payload.strOrNull("error").orEmpty(),
            failedIndex = index,
            ranCount = applied,
            rolledBack = d.boolOrNull("rolledBack") ?: false,
            statements = d.strList("statements"),
        )
    }
    val error = d.strOrNull("error") ?: payload.strOrNull("error") ?: return null
    val failedIndex = d.intOrNull("failedIndex") ?: return null
    val statements = d.strList("statements")
    if (statements.isEmpty()) return null
    val ranCount = d.intOrNull("ranCount") ?: 0
    return StructureFailure(
        statement = statements.getOrElse(failedIndex) { "" },
        index = failedIndex,
        applied = ranCount,
        total = statements.size,
        error = error,
        failedIndex = failedIndex,
        ranCount = ranCount,
        rolledBack = d.boolOrNull("rolledBack") ?: false,
        statements = statements,
    )
}

fun statementReports(preview: StructurePreview, failure: StructureFailure): List<StatementReport> {
    val commitFailed = failure.index < 0
    val isTransactional = preview.transactional
    fun outcomeOf(s: StructureStatement, i: Int): StatementOutcome? {
        if (i == failure.index) return StatementOutcome.FAILED
        if (s.sql.startsWith("--")) return null
        if (s.phase == "after") return StatementOutcome.RAN
        if (!commitFailed && i > failure.index) return StatementOutcome.SKIPPED
        return if (s.phase == "before" || !isTransactional) {
            StatementOutcome.RAN
        } else {
            StatementOutcome.ROLLED_BACK
        }
    }
    val reports = preview.statements.mapIndexedNotNull { i, s ->
        val out = outcomeOf(s, i) ?: return@mapIndexedNotNull null
        StatementReport(index = i, sql = s.sql, outcome = out)
    }
    if (!commitFailed) return reports
    val afterIdx = preview.statements.indexOfFirst { it.phase == "after" }
    val commitReport = StatementReport(index = -1, sql = "COMMIT;", outcome = StatementOutcome.FAILED)
    return if (afterIdx < 0) {
        reports + commitReport
    } else {
        val before = reports.filter { it.index < afterIdx }
        val after = reports.filter { it.index >= afterIdx }
        before + commitReport + after
    }
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
    ImpExpFormatOption(id = "jsonl", label = "JSON lines/NDJSON file(s)", ext = ".jsonl", exportable = true, importable = true),
    ImpExpFormatOption(id = "json", label = "JSON file(s)", ext = ".json", exportable = true, importable = true),
    ImpExpFormatOption(id = "sql", label = "SQL file(s)", ext = ".sql", exportable = true, importable = false),
    ImpExpFormatOption(id = "csv", label = "CSV file(s)", ext = ".csv", exportable = true, importable = true),
    ImpExpFormatOption(id = "xlsx", label = "MS Excel file(s)", ext = ".xlsx", exportable = true, importable = false),
    ImpExpFormatOption(id = "xml", label = "XML file(s)", ext = ".xml", exportable = true, importable = false),
)

fun importFormatOfFileName(fileName: String): String? {
    val lower = fileName.lowercase()
    if (lower.endsWith(".csv") || lower.endsWith(".tsv") || lower.endsWith(".txt")) return "csv"
    if (lower.endsWith(".jsonl") || lower.endsWith(".ndjson")) return "jsonl"
    if (lower.endsWith(".json")) return "json"
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
    LabeledChoice("\n", "LF"),
    LabeledChoice("\r", "CR"),
    LabeledChoice("\r\n", "CRLF"),
)

val CSV_BOOLEAN_FORMATS: List<LabeledChoice> = listOf(
    LabeledChoice("true_false", "true/false"),
    LabeledChoice("true_false_upper", "TRUE/FALSE"),
    LabeledChoice("1_0", "1/0"),
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
    val delimiter: String = "",
    val header: Boolean = true,
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("delimiter", if (delimiter == "auto") "" else delimiter)
        put("header", header)
    }
}

data class JsonOptions(
    val style: String = "array",
    val keyField: String = "",
    val rootField: String = "",
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("style", style)
        put("keyField", keyField)
        put("rootField", rootField)
    }
}

data class XmlWriteOptions(
    val rootElement: String = "",
    val itemElement: String = "",
) {
    val rowElement: String get() = itemElement

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("rootElement", rootElement)
        put("itemElement", itemElement)
    }
}

data class ExportFormatOptions(
    val csv: CsvWriteOptions = CsvWriteOptions(),
    val json: JsonOptions = JsonOptions(),
    val xml: XmlWriteOptions = XmlWriteOptions(),
    val xlsxSingleFile: Boolean = false,
) {
    val xlsxInSingleFile: Boolean get() = xlsxSingleFile

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("csv", csv.toJsonObject())
        put("json", json.toJsonObject())
        put("xml", xml.toJsonObject())
        put("xlsxSingleFile", xlsxSingleFile)
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
    val src: String,
    val dst: String,
    val skip: Boolean = false,
) {
    constructor(
        source: String,
        dst: String,
        ignore: Boolean = false,
        @Suppress("UNUSED_PARAMETER") legacy: Unit = Unit,
    ) : this(
        src = source,
        dst = dst,
        skip = ignore,
    )

    val source: String get() = src
    val ignore: Boolean get() = skip

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("src", src)
        put("dst", dst)
        if (skip) put("skip", true)
    }
}

fun columnMapProblem(columnMap: List<ColumnMapEntry>?): String? {
    if (columnMap.isNullOrEmpty()) return null
    val active = columnMap.filter { !it.skip }
    if (active.isEmpty()) return "Keep at least one column mapped"
    val seen = HashSet<String>()
    val duplicates = LinkedHashSet<String>()
    for (entry in active) {
        val src = entry.src.trim()
        val dst = entry.dst.trim()
        if (src.isEmpty() || dst.isEmpty()) return "Source and target columns must be defined"
        if (!seen.add(dst)) duplicates.add(dst)
    }
    return if (duplicates.isNotEmpty()) {
        "Target columns must be unique, duplicates found: ${duplicates.joinToString(", ")}"
    } else null
}

const val IMPORT_MAX_FILE_BYTES: Long = 128L * 1024L * 1024L

val IMPORT_ACTIONS: List<LabeledChoice> = listOf(
    LabeledChoice("createTable", "Create table/append"),
    LabeledChoice("appendData", "Append data"),
    LabeledChoice("truncate", "Truncate and import"),
    LabeledChoice("dropCreateTable", "Drop and create table"),
)

fun outputFileNameProblem(name: String): String? {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "The file needs a name"
    if (name.any { it.code in 0x00..0x1f || it.code == 0x7f }) return "\"$name\" holds a control character"
    if (name.contains('/') || name.contains('\\')) return "\"$name\" is a path; give a file name"
    if (trimmed == "." || trimmed == "..") return "\"$name\" is not a file name"
    if (name.encodeToByteArray().size > 255) return "\"${name.take(40)}…\" is longer than a file name may be"
    return null
}

private val NON_FILE_CHARS_RE = Regex("""[^\w.-]+""")
private val EDGE_UNDERSCORES_RE = Regex("""^_+|_+$""")
private val KNOWN_EXPORT_EXT_RE = Regex("""\.(csv|tsv|txt|xlsx|xls|jsonl|ndjson|json|xml|sql)$""", RegexOption.IGNORE_CASE)

fun defaultExportFileName(sourceName: String, format: String): String {
    val clean = sourceName.trim().replace('/', '_').replace('\\', '_').ifEmpty { "export" }
    val ext = IMPEXP_FILE_FORMATS.firstOrNull { it.id == format }?.ext ?: ".$format"
    return if (clean.lowercase().endsWith(ext)) clean else "$clean$ext"
}

private val ZIP_TIMESTAMP_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss", Locale.US).withZone(ZoneOffset.UTC)

fun defaultZipName(database: String? = null, atEpochMillis: Long? = null): String {
    if (atEpochMillis != null) {
        return "zip-archive-${ZIP_TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(atEpochMillis))}.zip"
    }
    if (!database.isNullOrBlank()) {
        val base = database.trim().replace(NON_FILE_CHARS_RE, "_").replace(EDGE_UNDERSCORES_RE, "").ifEmpty { "export" }
        return "$base.zip"
    }
    return "zip-archive-${ZIP_TIMESTAMP_FORMATTER.format(Instant.now())}.zip"
}

fun importSourceName(fileName: String): String {
    val base = fileName.trim().replace('\\', '/').substringAfterLast('/')
    val stripped = base.replace(Regex("""\.[^.]*$"""), "")
    return stripped.ifEmpty { base.ifEmpty { "imported_data" } }
}

data class ImpExpJobItemStatus(
    val source: String,
    val target: String,
    val state: String, // "queued" | "running" | "done" | "error" | "stopped"
    val rowsRead: Long = 0L,
    val rowsWritten: Long = 0L,
    val totalRows: Long? = null,
    val error: String? = null,
) {
    constructor(
        source: String,
        target: String,
        state: String,
        rowsProcessed: Long,
        totalRows: Long? = null,
        error: String? = null,
    ) : this(
        source = source,
        target = target,
        state = state,
        rowsRead = rowsProcessed,
        rowsWritten = rowsProcessed,
        totalRows = totalRows,
        error = error,
    )

    val rowsProcessed: Long get() = if (rowsWritten > 0L) rowsWritten else rowsRead
}

data class ImpExpMessage(
    val id: Int = 0,
    val time: Long = 0L,
    val level: String, // "info" | "warning" | "error" | "debug"
    val item: String? = null,
    val text: String,
) {
    constructor(
        id: Int,
        at: Long,
        level: String,
        item: String? = null,
        text: String,
        @Suppress("UNUSED_PARAMETER") legacy: Unit = Unit,
    ) : this(
        id = id,
        time = at,
        level = level,
        item = item,
        text = text,
    )

    val at: Long get() = time
}

data class ImpExpOutputFile(
    val name: String,
    val size: Long,
    val mimeType: String = "application/octet-stream",
)

data class ImpExpJobStatus(
    val id: String,
    val kind: String, // "export" | "import"
    val state: String, // "running" | "done" | "error" | "stopped"
    val startedAt: Long,
    val endedAt: Long? = null,
    val currentItem: String? = null,
    val items: List<ImpExpJobItemStatus> = emptyList(),
    val messages: List<ImpExpMessage> = emptyList(),
    val messageCount: Int = messages.size,
    val files: List<ImpExpOutputFile> = emptyList(),
    val zipFile: ImpExpOutputFile? = null,
) {
    constructor(
        id: String,
        kind: String,
        state: String,
        startedAt: Long,
        finishedAt: Long? = null,
        currentItem: String? = null,
        items: List<ImpExpJobItemStatus> = emptyList(),
        messages: List<ImpExpMessage> = emptyList(),
        nextMessageId: Int = messages.size,
        files: List<ImpExpOutputFile> = emptyList(),
        zipFile: ImpExpOutputFile? = null,
        @Suppress("UNUSED_PARAMETER") legacy: Unit = Unit,
    ) : this(
        id = id,
        kind = kind,
        state = state,
        startedAt = startedAt,
        endedAt = finishedAt,
        currentItem = currentItem,
        items = items,
        messages = messages,
        messageCount = nextMessageId,
        files = files,
        zipFile = zipFile,
    )

    val finishedAt: Long? get() = endedAt
    val nextMessageId: Int get() = messageCount
}

data class ImportPreviewResult(
    val sources: List<String> = emptyList(),
    val source: String = "",
    val columns: List<String>,
    val rows: List<Map<String, DbCellValue>>,
    val warnings: List<String> = emptyList(),
    val truncated: Boolean = false,
)

fun parseImpExpJobStatus(json: JsonObject, sinceOffset: Int = 0): ImpExpJobStatus {
    val items = ((json["items"] as? JsonArray).orEmpty()).mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val rowsRead = (o["rowsRead"] as? JsonPrimitive)?.longOrNull
            ?: (o["rowsProcessed"] as? JsonPrimitive)?.longOrNull
            ?: 0L
        val rowsWritten = (o["rowsWritten"] as? JsonPrimitive)?.longOrNull
            ?: (o["rowsProcessed"] as? JsonPrimitive)?.longOrNull
            ?: 0L
        ImpExpJobItemStatus(
            source = o.strOrNull("source").orEmpty(),
            target = o.strOrNull("target").orEmpty(),
            state = o.strOrNull("state") ?: "queued",
            rowsRead = rowsRead,
            rowsWritten = rowsWritten,
            totalRows = (o["totalRows"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull,
            error = o.strOrNull("error"),
        )
    }
    val messages = ((json["messages"] as? JsonArray).orEmpty()).mapIndexedNotNull { idx, el ->
        val o = el as? JsonObject ?: return@mapIndexedNotNull null
        val msgId = o.intOrNull("id") ?: (sinceOffset + idx + 1)
        val msgTime = (o["time"] as? JsonPrimitive)?.longOrNull
            ?: (o["at"] as? JsonPrimitive)?.longOrNull
            ?: 0L
        val rawLevel = o.strOrNull("level") ?: "info"
        val normLevel = if (rawLevel == "warn") "warning" else rawLevel
        ImpExpMessage(
            id = msgId,
            time = msgTime,
            level = normLevel,
            item = o.strOrNull("item"),
            text = o.strOrNull("text").orEmpty(),
        )
    }
    val files = ((json["files"] as? JsonArray).orEmpty()).mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val name = o.strOrNull("name").orEmpty()
        val defaultMime = if (name.lowercase().endsWith(".zip")) "application/zip" else "application/octet-stream"
        ImpExpOutputFile(
            name = name,
            size = (o["size"] as? JsonPrimitive)?.longOrNull ?: 0L,
            mimeType = o.strOrNull("mimeType") ?: defaultMime,
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
    val endedAt = (json["endedAt"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull
        ?: (json["finishedAt"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull
    val messageCount = json.intOrNull("messageCount")
        ?: json.intOrNull("nextMessageId")
        ?: (sinceOffset + messages.size)
    return ImpExpJobStatus(
        id = json.strOrNull("id").orEmpty(),
        kind = json.strOrNull("kind") ?: "export",
        state = json.strOrNull("state") ?: "running",
        startedAt = (json["startedAt"] as? JsonPrimitive)?.longOrNull ?: System.currentTimeMillis(),
        endedAt = endedAt,
        currentItem = json.strOrNull("currentItem"),
        items = items,
        messages = messages,
        messageCount = messageCount,
        files = files,
        zipFile = zipFile,
    )
}

fun parseImportPreview(json: JsonObject, source: String = ""): ImportPreviewResult {
    val sources = json.strList("sources")
    val srcName = json.strOrNull("source") ?: source
    val columns = json.strList("columns")
    val rows = ((json["rows"] as? JsonArray).orEmpty()).mapNotNull { el ->
        when (el) {
            is JsonArray -> {
                val rowMap = LinkedHashMap<String, DbCellValue>()
                columns.forEachIndexed { idx, col ->
                    val cellEl = el.getOrNull(idx) ?: JsonNull
                    rowMap[col] = parseDbCellValue(cellEl)
                }
                rowMap
            }
            is JsonObject -> {
                val rowMap = LinkedHashMap<String, DbCellValue>()
                for ((k, v) in el) {
                    rowMap[k] = parseDbCellValue(v)
                }
                rowMap
            }
            else -> null
        }
    }
    return ImportPreviewResult(
        sources = sources,
        source = srcName,
        columns = columns,
        rows = rows,
        warnings = json.strList("warnings"),
        truncated = json.boolOrNull("truncated") ?: false,
    )
}

// ── `impexp-state.ts` Wizard State & Pure Reducers ──

const val TEMPLATE_SOURCE = "__TEMPLATE__"
const val QUERY_SOURCE = "query"

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
    sourceLabel = "(not selected)",
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

private fun isTemplateSource(source: String): Boolean =
    source == TEMPLATE_SOURCE || source.equals("__template__", ignoreCase = true)

fun defaultRowTarget(form: ImpExpForm, row: ImpExpRow): String {
    if (isTemplateSource(row.source)) return ""
    if (form.targetType == "database") return row.source
    if (form.targetType == "xlsx" && form.exportOptions.xlsxSingleFile) return row.source
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
    val bySource = prevRows.filter { !isTemplateSource(it.source) }.associateBy { it.source }
    val template = prevRows.firstOrNull { isTemplateSource(it.source) } ?: templateRow()
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
    val prev = form.rows.firstOrNull { !isTemplateSource(it.source) }
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
    val template = form.rows.firstOrNull { isTemplateSource(it.source) } ?: templateRow()
    val next = mutableListOf(template)
    for (u in uploads) {
        val sheets = if (u.sources.isNotEmpty()) u.sources else listOf<String?>(null)
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
                    target = template.target,
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
        action = "createTable",
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
    } else if (targetType == "database") {
        targetType = "csv"
    }
    val next = form.copy(sourceType = sourceType, targetType = targetType)
    return when (sourceType) {
        "database" -> next.copy(rows = syncDatabaseRows(next, next.sourceTables))
        "query" -> next.copy(rows = syncQueryRows(next))
        else -> {
            val matchingUploads = next.uploads.filter { it.format == null || it.format == sourceType }
            next.copy(rows = syncUploadRows(next, matchingUploads))
        }
    }
}

fun isFileSource(type: String): Boolean =
    type != "database" && type != "query"

fun targetTypeOptions(source: String): List<LabeledChoice> =
    if (isFileSource(source)) {
        listOf(LabeledChoice("database", "Database"))
    } else {
        IMPEXP_FILE_FORMATS.map { LabeledChoice(it.id, it.label) }
    }

fun setTargetType(form: ImpExpForm, targetType: String): ImpExpForm {
    if (targetType == form.targetType || targetTypeOptions(form.sourceType).none { it.id == targetType }) {
        return form
    }
    val prevDefaultTargets = form.rows.associate { r -> r.id to defaultRowTarget(form, r) }
    val next = form.copy(targetType = targetType)
    val rows = next.rows.map { r ->
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
    if (row == null || isTemplateSource(row.source)) return form
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
    if (isTemplateSource(row.source)) {
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
    form.rows.filter { !isTemplateSource(it.source) && it.checked }

fun rowSourceLabel(form: ImpExpForm, row: ImpExpRow): String {
    if (isTemplateSource(row.source)) return "(not selected)"
    if (form.sourceType == "query") return QUERY_SOURCE
    return row.sourceLabel
}

fun columnsLinkText(row: ImpExpRow): String {
    val map = row.columnMap
    if (map.isNullOrEmpty()) return "all"
    val kept = map.count { !it.skip }
    val renamed = map.any { !it.skip && it.dst.trim() != it.src }
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
        ?: return "\"$fileName\" does not have a supported import extension (.csv, .tsv, .txt, .json, .jsonl, .ndjson)"
    if (size == 0L) return "$fileName is empty"
    if (size > IMPORT_MAX_FILE_BYTES) {
        val mb = (IMPORT_MAX_FILE_BYTES / (1024L * 1024L)).toInt()
        return "$fileName is larger than $mb MB, the most one file can be"
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
        return "The query is empty"
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
            if (u.format != null && u.format != form.sourceType) {
                return "\"${u.fileName}\" is not a ${form.sourceType.uppercase()} file"
            }
        }
    }
    val isSingleWorkbook = form.targetType == "xlsx" && form.exportOptions.xlsxSingleFile
    val seenFileNames = HashSet<String>()
    for (row in rows) {
        val target = rowTarget(form, row)
        if (target.isEmpty()) return "Target is missing for \"${row.sourceLabel}\""
        if (form.targetType != "database" && !isSingleWorkbook) {
            val fileProblem = outputFileNameProblem(target)
            if (fileProblem != null) return fileProblem
            val key = target.lowercase()
            if (!seenFileNames.add(key)) {
                return "Two export items use the same output file name \"$target\""
            }
        }
        val mapProblem = columnMapProblem(row.columnMap)
        if (mapProblem != null) return "${row.sourceLabel}: $mapProblem"
    }
    if (form.targetType != "database" && form.archiveAsZip && form.zipFileName.trim().isNotEmpty()) {
        val zipProblem = outputFileNameProblem(form.zipFileName.trim())
        if (zipProblem != null) return zipProblem
    }
    return null
}

fun exportRequest(form: ImpExpForm): JsonObject {
    val rows = activeRows(form)
    val format = if (form.targetType == "database") "csv" else form.targetType
    val sourceObj = if (form.sourceType == "query") {
        val queryRow = rows.firstOrNull() ?: ImpExpRow(id = "q", source = QUERY_SOURCE, sourceLabel = QUERY_SOURCE)
        buildJsonObject {
            put("type", "query")
            put("sql", form.querySql)
            put("target", rowTarget(form, queryRow))
            if (!queryRow.columnMap.isNullOrEmpty()) {
                put("columns", buildJsonArray { queryRow.columnMap.forEach { add(it.toJsonObject()) } })
            }
        }
    } else {
        buildJsonObject {
            put("type", "database")
            if (!form.sourceDb.schema.isNullOrEmpty()) put("schema", form.sourceDb.schema)
            put(
                "tables",
                buildJsonArray {
                    for (r in rows) {
                        add(
                            buildJsonObject {
                                put("name", r.source)
                                put("target", rowTarget(form, r))
                                if (!r.columnMap.isNullOrEmpty()) {
                                    put("columns", buildJsonArray { r.columnMap.forEach { add(it.toJsonObject()) } })
                                }
                            },
                        )
                    }
                },
            )
        }
    }
    val zipName = form.zipFileName.trim().ifEmpty { defaultZipName(form.sourceDb.database) }
    val normalizedZip = if (zipName.lowercase().endsWith(".zip")) zipName else "$zipName.zip"
    return buildJsonObject {
        put("source", sourceObj)
        put("format", format)
        put("options", form.exportOptions.toJsonObject())
        put(
            "zip",
            if (form.archiveAsZip) {
                buildJsonObject { put("name", normalizedZip) }
            } else {
                JsonNull
            },
        )
    }
}

fun importRequest(form: ImpExpForm): JsonObject {
    val rows = activeRows(form)
    val uploadById = form.uploads.associateBy { it.localId }
    val format = if (form.sourceType in setOf("csv", "json", "jsonl")) form.sourceType else "csv"
    return buildJsonObject {
        put("format", format)
        put("options", form.importOptions.toJsonObject())
        if (!form.targetDb.schema.isNullOrEmpty()) put("schema", form.targetDb.schema)
        put(
            "files",
            buildJsonArray {
                for (r in rows) {
                    val u = r.uploadLocalId?.let { uploadById[it] }
                    add(
                        buildJsonObject {
                            put("upload", u?.uploadId.orEmpty())
                            put("source", r.source)
                            put("target", rowTarget(form, r))
                            put("action", r.action)
                            if (!r.columnMap.isNullOrEmpty()) {
                                put("columns", buildJsonArray { r.columnMap.forEach { add(it.toJsonObject()) } })
                            }
                        },
                    )
                }
            },
        )
    }
}

fun uploadPreviewRequest(form: ImpExpForm, row: ImpExpRow): JsonObject {
    val format = if (form.sourceType in setOf("csv", "json", "jsonl")) form.sourceType else "csv"
    return buildJsonObject {
        put("format", format)
        put("options", form.importOptions.toJsonObject())
        if (!row.columnMap.isNullOrEmpty()) {
            put("columns", buildJsonArray { row.columnMap.forEach { add(it.toJsonObject()) } })
        }
    }
}

fun itemStatusText(item: ImpExpJobItemStatus?): String {
    if (item == null) return ""
    val numFmt = NumberFormat.getIntegerInstance(Locale.US)
    return when (item.state) {
        "queued", "pending" -> "Queued"
        "running" -> when {
            item.rowsWritten > 0L -> "${numFmt.format(item.rowsWritten)} rows written"
            item.rowsRead > 0L -> "${numFmt.format(item.rowsRead)} rows read"
            else -> "Running"
        }
        "done" -> {
            val n = if (item.rowsWritten > 0L) item.rowsWritten else item.rowsRead
            if (n > 0L) "${numFmt.format(n)} rows written" else "Done"
        }
        "stopped", "canceled" -> "Stopped"
        "error" -> item.error ?: "Error"
        else -> ""
    }
}

val MESSAGE_LEVELS = listOf("debug", "info", "warning", "error")
val DEFAULT_SHOWN_LEVELS = setOf("info", "warning", "error")

fun filterMessages(
    messages: List<ImpExpMessage>,
    levels: Set<String>,
    filterText: String,
): List<ImpExpMessage> {
    val q = filterText.trim().lowercase()
    val expandedLevels = buildSet {
        addAll(levels)
        if ("warning" in levels) add("warn")
        if ("warn" in levels) add("warning")
    }
    return messages.filter { m ->
        if (m.level !in expandedLevels) return@filter false
        if (q.isEmpty()) return@filter true
        val itemMatch = m.item?.lowercase()?.contains(q) == true
        itemMatch || m.text.lowercase().contains(q)
    }
}

fun formatImpExpDuration(startedAt: Long, finishedAt: Long? = null, now: Long = System.currentTimeMillis()): String {
    val ms = max(0L, (finishedAt ?: now) - startedAt)
    if (ms == 0L) return "0"
    if (ms < 1000L) return "${ms} ms"
    if (ms < 10_000L) return String.format(Locale.US, "%.1f s", ms / 1000.0)
    return "${(ms / 1000.0).roundToInt()} s"
}

private val CLOCK_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US).withZone(ZoneOffset.UTC)

fun messageClock(at: Long): String = CLOCK_FORMATTER.format(Instant.ofEpochMilli(at))

fun messageLines(messages: List<ImpExpMessage>): String =
    messages.joinToString("\n") { m ->
        val itemPart = if (!m.item.isNullOrEmpty()) " [${m.item}]" else ""
        "${messageClock(m.time)} ${m.level.uppercase()}$itemPart ${m.text}"
    }

fun levelCounts(messages: List<ImpExpMessage>): Map<String, Int> {
    val counts = mutableMapOf("debug" to 0, "info" to 0, "warning" to 0, "error" to 0, "warn" to 0)
    for (m in messages) {
        val norm = if (m.level == "warn") "warning" else m.level
        counts[norm] = (counts[norm] ?: 0) + 1
        if (norm == "warning") counts["warn"] = counts["warning"] ?: 0
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
            state = "queued",
            rowsRead = 0L,
            rowsWritten = 0L,
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
        messageCount = preserved.size,
        files = emptyList(),
    )
}

fun resetColumnMap(sources: List<String>): List<ColumnMapEntry> =
    sources.map { s -> ColumnMapEntry(src = s, dst = s, skip = false) }

fun sameColumnMap(entries: List<ColumnMapEntry>, sources: List<String>): Boolean =
    entries.size == sources.size &&
        entries.indices.all { i -> !entries[i].skip && entries[i].src == sources[i] && entries[i].dst.trim() == sources[i] }

fun confirmedColumnMap(entries: List<ColumnMapEntry>, sources: List<String>): List<ColumnMapEntry>? {
    if (entries.isEmpty() || sameColumnMap(entries, sources)) return null
    return entries.map { e -> e.copy(dst = e.dst.trim()) }
}
