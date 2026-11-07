package com.hienlh.ppm.state.database

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabasePhase09fStructureImpExpStateTest {

    @Test
    fun structureParsingAndTableEditorModel_preserveColumnIdentityCleanReferencesAndValidateProblems() {
        val rawStructure = Json.parseToJsonElement(
            """
            {
              "dialect": "mysql",
              "database": "shop",
              "schema": null,
              "table": "employees",
              "kind": "table",
              "comment": "Staff directory",
              "engine": "InnoDB",
              "columns": [
                {
                  "ordinal": 1,
                  "name": "id",
                  "type": "int unsigned zerofill",
                  "nullable": false,
                  "autoIncrement": true
                },
                {
                  "ordinal": 2,
                  "name": "email",
                  "type": "varchar(255)",
                  "nullable": false,
                  "defaultValue": "'a@b.c'",
                  "comment": "Primary login"
                },
                {
                  "ordinal": 3,
                  "name": "manager_id",
                  "type": "int unsigned",
                  "nullable": true
                }
              ],
              "primaryKey": {
                "name": "PRIMARY",
                "columns": ["id"]
              },
              "indexes": [
                {
                  "name": "PRIMARY",
                  "unique": true,
                  "primary": true,
                  "keys": [{ "column": "id", "order": "ASC" }]
                },
                {
                  "name": "uq_email",
                  "unique": true,
                  "primary": false,
                  "keys": [{ "column": "email", "order": "ASC" }]
                },
                {
                  "name": "idx_manager_desc",
                  "unique": false,
                  "primary": false,
                  "type": "BTREE",
                  "keys": [{ "column": "manager_id", "order": "DESC" }]
                }
              ],
              "uniques": [
                {
                  "name": "uq_email",
                  "columns": ["email"]
                }
              ],
              "foreignKeys": [
                {
                  "name": "fk_emp_manager",
                  "schema": null,
                  "table": "employees",
                  "columns": ["manager_id"],
                  "referencedSchema": null,
                  "referencedTable": "employees",
                  "referencedColumns": ["id"],
                  "onUpdate": "CASCADE",
                  "onDelete": "SET NULL"
                }
              ],
              "dependencies": [
                {
                  "direction": "usedBy",
                  "object": { "schema": null, "name": "active_employees_view", "kind": "view" }
                }
              ]
            }
            """.trimIndent(),
        ).jsonObject

        val structure = parseDbTableStructure(rawStructure)
        assertEquals("mysql", structure.dialect)
        assertEquals("employees", structure.table)
        assertEquals("InnoDB", structure.engine)
        assertEquals(3, structure.columns.size)
        // ownIndexes filters out PRIMARY and the index backing uq_email
        val ownIdx = ownIndexes(structure)
        assertEquals(1, ownIdx.size)
        assertEquals("idx_manager_desc", ownIdx[0].name)

        // Read-only helpers
        val roCols = columnRows(structure)
        assertTrue(roCols[0].isPrimaryKey)
        assertTrue(roCols[0].isAutoIncrement)
        assertEquals("id, email", columnNames(structure.columns, setOf(0, 1)))
        val defs = columnDefinitions(structure, setOf(0, 1))
        assertTrue(defs.contains("PRIMARY KEY"))
        assertTrue(defs.contains("COMMENT 'Primary login'"))

        // Editable TableModel conversion
        val baseModel = modelFromStructure(structure)
        assertTrue(sameTableModel(baseModel, baseModel.copy()))
        assertEquals("int", baseModel.columns[0].dataType)
        assertTrue(baseModel.columns[0].isUnsigned)
        assertTrue(baseModel.columns[0].isZeroFill)
        assertEquals("int unsigned zerofill", declaredType(baseModel.columns[0], "mysql"))

        // Rename PK column "id" -> "emp_id": preserves id="c:id" and updates self-referencing FK referencedColumns!
        val idCol = baseModel.columns[0]
        val renamedModel = upsertColumn(baseModel, idCol.copy(name = "emp_id"))
        assertEquals("c:id", renamedModel.columns[0].id)
        assertEquals(listOf("emp_id"), renamedModel.foreignKeys[0].referencedColumns)
        assertFalse(sameTableModel(baseModel, renamedModel))

        // Rename table updates self-referencing FK referencedTable
        val renamedTableModel = withTableName(renamedModel, "staff")
        assertEquals("staff", renamedTableModel.foreignKeys[0].referencedTable)

        // Removing manager_id removes idx_manager_desc and fk_emp_manager
        val afterRemoveManager = removeColumns(renamedModel, setOf("c:manager_id"))
        assertEquals(2, afterRemoveManager.columns.size)
        assertTrue(afterRemoveManager.indexes.isEmpty())
        assertTrue(afterRemoveManager.foreignKeys.isEmpty())

        // Validation problems
        val clashCol = blankColumn("mysql", 4, "col:clash").copy(
            name = "email",
            autoIncrement = true,
            computedExpression = "id + 1",
        )
        val colErrs = columnProblems(renamedModel, clashCol)
        assertTrue(colErrs.any { it.contains("already exists") })
        assertTrue(colErrs.any { it.contains("both autoincrement and computed") })

        assertTrue(keyNameTaken(renamedModel, "UQ_EMAIL"))
        assertFalse(keyNameTaken(renamedModel, "UQ_EMAIL", exceptId = renamedModel.uniques[0].id))
        assertEquals("employees_email_uq", autoConstraintName("uq", "employees", listOf("email")))
        assertEquals("employees_pkey", autoConstraintName("pk", "employees", listOf("id")))
    }

    @Test
    fun structureSavePreviewAndFailureReports_distinguishRolledBackAndMySqlPartialCommits() {
        val model = newTableModel("postgres", "public")
        val createChange = StructureChange.Create(database = "app", target = model)
        assertEquals("create", createChange.toJsonObject()["kind"]?.jsonPrimitive?.content)

        val backupName = backupTableName("orders", 1730000000000L)
        assertTrue(backupName.startsWith("orders_backup_"))

        val conflictPayload = Json.parseToJsonElement(
            """
            {
              "error": "Structure changed since preview",
              "preview": {
                "dialect": "postgres",
                "transactionalDdl": true,
                "recreate": true,
                "statements": [
                  { "sql": "ALTER TABLE \"public\".\"new_table\" ADD COLUMN \"age\" integer", "recreatesTable": true }
                ],
                "sql": "ALTER TABLE \"public\".\"new_table\" ADD COLUMN \"age\" integer;",
                "warnings": ["Recreates table"]
              }
            }
            """.trimIndent(),
        ).jsonObject

        val newer = newerPreviewOf(conflictPayload)
        assertNotNull(newer)
        assertTrue(newer!!.recreate)
        assertEquals(listOf("Recreates table"), newer.warnings)

        val pgFailJson = Json.parseToJsonElement(
            """
            {
              "error": "syntax error",
              "failedIndex": 1,
              "ranCount": 1,
              "rolledBack": true,
              "statements": ["ALTER TABLE t ADD a int", "ALTER TABLE t ADD b bad_type", "CREATE INDEX idx_a ON t(a)"]
            }
            """.trimIndent(),
        ).jsonObject
        val pgFailure = failureOf(pgFailJson)!!
        val pgReports = statementReports(pgFailure)
        assertEquals(StatementOutcome.ROLLED_BACK, pgReports[0].outcome)
        assertEquals(StatementOutcome.FAILED, pgReports[1].outcome)
        assertEquals(StatementOutcome.SKIPPED, pgReports[2].outcome)

        val mysqlFailure = pgFailure.copy(rolledBack = false)
        val mysqlReports = statementReports(mysqlFailure)
        assertEquals(StatementOutcome.RAN, mysqlReports[0].outcome)
        assertEquals(StatementOutcome.FAILED, mysqlReports[1].outcome)
        assertEquals(StatementOutcome.SKIPPED, mysqlReports[2].outcome)
    }

    @Test
    fun impExpFormState_handlesExportImportUploadsMultiSheetXlsxColumnMapAndRunBlockers() {
        val currentDb = ImpExpDatabase(connectionId = 1, database = "analytics", schema = "public")
        var form = databaseExportForm(currentDb, table = "users")
        assertEquals("Export (1)", impExpTitle(form))
        assertNull(runBlocker(form))

        form = addTables(form, listOf("orders"))
        assertEquals(2, activeRows(form).size)
        val exportJson = exportRequest(form)
        assertEquals("csv", exportJson["targetKind"]?.jsonPrimitive?.content)
        assertEquals(2, exportJson["source"]?.jsonObject?.get("items")?.jsonArray?.size)

        // Duplicate output file name blocks run
        val secondRowId = activeRows(form)[1].id
        val dupForm = updateRow(form, secondRowId, target = "users.csv")
        assertEquals(
            "Two export items use the same output file name \"users.csv\"",
            runBlocker(dupForm),
        )

        // Switch to import with XLSX multi-sheet upload
        var impForm = importIntoForm(currentDb)
        assertEquals("Add at least one file to import", runBlocker(impForm))

        // 128 MB upload limit check
        assertNotNull(uploadProblem("huge.csv", IMPORT_MAX_FILE_BYTES + 1))
        assertNull(uploadProblem("valid.xlsx", 1024L))

        val upload = ImpExpUpload(
            localId = "up-1",
            fileName = "workbook.xlsx",
            size = 4096L,
            format = "xlsx",
            state = "uploading",
            progress = 0.5,
        )
        impForm = addUploads(impForm, listOf(upload))
        assertEquals("xlsx", impForm.sourceType)
        assertEquals("Wait for \"workbook.xlsx\" to finish uploading", runBlocker(impForm))

        // Patch upload as ready with 2 sheets
        impForm = patchUpload(
            impForm,
            localId = "up-1",
            state = "ready",
            progress = 1.0,
            uploadId = "srv-up-1",
            sources = listOf("Customers", "Invoices"),
        )
        val sheetRows = activeRows(impForm)
        assertEquals(2, sheetRows.size)
        assertEquals("Customers", sheetRows[0].source)
        assertEquals("workbook.xlsx · Customers", sheetRows[0].sourceLabel)
        assertEquals("Invoices", sheetRows[1].source)
        assertNull(runBlocker(impForm, targetReadonly = false))
        assertEquals("Target database connection is read-only", runBlocker(impForm, targetReadonly = true))

        // Template row updates action across all rows
        impForm = updateRow(impForm, "row-template", action = "truncateAppend")
        assertTrue(activeRows(impForm).all { it.action == "truncateAppend" })

        // Column map validation & confirmation
        val defaultMap = resetColumnMap(listOf("id", "name"))
        assertNull(confirmedColumnMap(defaultMap, listOf("id", "name")))
        val customMap = listOf(
            ColumnMapEntry(source = "id", dst = "customer_id", ignore = false),
            ColumnMapEntry(source = "name", dst = "name", ignore = true),
        )
        val confirmed = confirmedColumnMap(customMap, listOf("id", "name"))
        assertNotNull(confirmed)
        impForm = updateRow(impForm, sheetRows[0].id, columnMap = confirmed, updateColumnMap = true)
        assertEquals("1 / 2", columnsLinkText(activeRows(impForm)[0]))

        val impReq = importRequest(impForm)
        assertEquals("xlsx", impReq["sourceFormat"]?.jsonPrimitive?.content)
        assertEquals(2, impReq["items"]?.jsonArray?.size)
    }
}
