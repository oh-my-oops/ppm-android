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
              "schema": null,
              "name": "employees",
              "kind": "table",
              "comment": "Staff directory",
              "engine": "InnoDB",
              "withoutRowid": false,
              "rowKey": ["id"],
              "rowKeyIsRowid": false,
              "columns": [
                {
                  "name": "id",
                  "type": "int unsigned zerofill",
                  "nullable": false,
                  "autoIncrement": true,
                  "generated": false,
                  "computedExpression": null
                },
                {
                  "name": "email",
                  "type": "varchar(255)",
                  "nullable": false,
                  "defaultValue": "'a@b.c'",
                  "comment": "Primary login",
                  "generated": false
                },
                {
                  "name": "manager_id",
                  "type": "int unsigned",
                  "nullable": true,
                  "generated": false
                },
                {
                  "name": "email_domain",
                  "type": "varchar(255)",
                  "nullable": true,
                  "generated": true,
                  "computedExpression": "substring_index(email, '@', -1)"
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
                  "columns": ["id"],
                  "keys": [{ "column": "id", "expression": null, "descending": false }]
                },
                {
                  "name": "uq_email",
                  "unique": true,
                  "primary": false,
                  "columns": ["email"],
                  "keys": [{ "column": "email", "expression": null, "descending": false }]
                },
                {
                  "name": "idx_manager_desc",
                  "unique": false,
                  "primary": false,
                  "method": "BTREE",
                  "where": null,
                  "columns": ["manager_id"],
                  "keys": [{ "column": "manager_id", "expression": null, "descending": true }]
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
                  "columns": ["manager_id"],
                  "refSchema": null,
                  "refTable": "employees",
                  "refColumns": ["id"],
                  "onUpdate": "CASCADE",
                  "onDelete": "SET NULL"
                }
              ],
              "references": [
                {
                  "name": "fk_dept_lead",
                  "schema": null,
                  "table": "departments",
                  "columns": ["lead_id"],
                  "refColumns": ["id"],
                  "onUpdate": "NO ACTION",
                  "onDelete": "SET NULL"
                }
              ],
              "checks": [
                {
                  "name": "chk_email_nonempty",
                  "expression": "email <> ''",
                  "column": "email"
                }
              ]
            }
            """.trimIndent(),
        ).jsonObject

        val structure = parseDbTableStructure(rawStructure)
        assertEquals("employees", structure.name)
        assertEquals("InnoDB", structure.engine)
        assertEquals(4, structure.columns.size)
        assertFalse(structure.columns[0].computedStored)
        assertTrue(structure.columns[3].isGenerated)
        assertFalse(structure.columns[3].computedStored)
        assertEquals("substring_index(email, '@', -1)", structure.columns[3].computedExpression)
        assertEquals(1, structure.references.size)
        assertEquals("departments", structure.references[0].table)
        assertEquals(1, structure.checks.size)
        assertEquals("email <> ''", structure.checks[0].expression)

        // Non-server fallback keys are ignored by parseDbTableStructure
        val nonServerStructure = parseDbTableStructure(
            Json.parseToJsonElement(
                """
                {
                  "table": "wrong_name",
                  "withoutRowId": true,
                  "columns": [{ "name": "c", "type": "int", "nullable": false, "generated": "a + b" }],
                  "indexes": [{ "name": "ix", "unique": false, "primary": false, "type": "HASH", "filter": "c > 0", "keys": [{ "column": "c", "order": "DESC" }] }],
                  "foreignKeys": [{ "name": "fk", "columns": ["c"], "referencedTable": "other", "referencedColumns": ["id"] }],
                  "referencedBy": [{ "name": "ref_fk", "table": "child", "columns": ["p_id"], "refColumns": ["id"] }],
                  "checks": [{ "name": "chk", "definition": "c > 0" }]
                }
                """.trimIndent(),
            ).jsonObject,
        )
        assertEquals("", nonServerStructure.name)
        assertFalse(nonServerStructure.withoutRowid)
        assertFalse(nonServerStructure.columns[0].generated)
        assertNull(nonServerStructure.columns[0].computedExpression)
        assertNull(nonServerStructure.indexes[0].method)
        assertNull(nonServerStructure.indexes[0].where)
        assertFalse(nonServerStructure.indexes[0].keys[0].descending)
        assertTrue(nonServerStructure.foreignKeys.isEmpty())
        assertTrue(nonServerStructure.references.isEmpty())
        assertTrue(nonServerStructure.checks.isEmpty())

        // ownIndexes filters out PRIMARY and the index backing uq_email
        val ownIdx = ownIndexes(structure)
        assertEquals(1, ownIdx.size)
        assertEquals("idx_manager_desc", ownIdx[0].name)
        assertEquals("BTREE", ownIdx[0].method)
        assertTrue(ownIdx[0].keys[0].descending)

        // ownIndexes matches named uniques by name only, and unnamed uniques by columns regardless of DESC/where (db-table-model.ts:120)
        val sqliteUniquesStructure = structure.copy(
            indexes = listOf(
                DbIndex(
                    name = "idx_email_extra",
                    unique = true,
                    primary = false,
                    columns = listOf("email"),
                    keys = listOf(DbIndexKey(column = "email", descending = false)),
                ),
                DbIndex(
                    name = "sqlite_autoindex_employees_1",
                    unique = true,
                    primary = false,
                    columns = listOf("manager_id"),
                    keys = listOf(DbIndexKey(column = "manager_id", descending = true)),
                    where = "manager_id IS NOT NULL",
                ),
            ),
            uniques = listOf(
                DbUniqueConstraint(name = "uq_email", columns = listOf("email")),
                DbUniqueConstraint(name = null, columns = listOf("manager_id")),
            ),
        )
        val sqliteOwn = ownIndexes(sqliteUniquesStructure)
        assertEquals(listOf("idx_email_extra"), sqliteOwn.map { it.name })

        // Read-only helpers
        val roCols = columnRows(structure)
        assertTrue(roCols[0].isPrimaryKey)
        assertTrue(roCols[0].isAutoIncrement)
        assertEquals("id, email", columnNames(structure.columns, setOf(0, 1)))
        val defs = columnDefinitions(structure, setOf(0, 1))
        assertTrue(defs.contains("PRIMARY KEY"))
        assertTrue(defs.contains("COMMENT 'Primary login'"))

        // Editable TableModel conversion takes dialect from connection type ("mysql"), not structure JSON
        val baseModel = modelFromStructure(structure, "mysql")
        assertEquals("mysql", baseModel.dialect)
        assertFalse(primaryKeyHasName(baseModel.dialect))
        assertTrue(sameTableModel(baseModel, baseModel.copy()))
        assertEquals("int", baseModel.columns[0].dataType)
        assertFalse(baseModel.columns[0].computedStored)
        assertTrue(baseModel.columns[0].isUnsigned)
        assertTrue(baseModel.columns[0].isZeroFill)
        assertEquals("int unsigned zerofill", declaredType(baseModel.columns[0], "mysql"))
        assertEquals("substring_index(email, '@', -1)", baseModel.columns[3].computedExpression)
        assertFalse(baseModel.columns[3].computedStored)

        // Verify TableModel.toJsonObject matches server's structure-change-request.ts
        val modelJson = baseModel.toJsonObject()
        assertEquals("employees", modelJson["name"]?.jsonPrimitive?.content)
        val firstColJson = modelJson["columns"]!!.jsonArray[0].jsonObject
        assertEquals("c:id", firstColJson["id"]?.jsonPrimitive?.content)
        assertEquals("false", firstColJson["computedStored"]?.jsonPrimitive?.content)
        assertTrue(firstColJson["defaultValue"]!!.isNullLiteral())
        val idxJson = modelJson["indexes"]!!.jsonArray[0].jsonObject
        assertEquals("ix:idx_manager_desc", idxJson["id"]?.jsonPrimitive?.content)
        assertEquals("true", idxJson["columns"]!!.jsonArray[0].jsonObject["descending"]?.jsonPrimitive?.content)
        val fkJson = modelJson["foreignKeys"]!!.jsonArray[0].jsonObject
        assertEquals("fk:fk_emp_manager", fkJson["id"]?.jsonPrimitive?.content)
        assertEquals("employees", fkJson["refTable"]?.jsonPrimitive?.content)
        assertEquals("CASCADE", fkJson["onUpdate"]?.jsonPrimitive?.content)
        assertEquals("SET NULL", fkJson["onDelete"]?.jsonPrimitive?.content)

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
        assertEquals(3, afterRemoveManager.columns.size)
        assertTrue(afterRemoveManager.indexes.isEmpty())
        assertTrue(afterRemoveManager.foreignKeys.isEmpty())

        // Validation problems
        val clashCol = blankColumn("mysql", 5, "col:clash").copy(
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
        val baseModel = newTableModel("postgres", "public")
        val draftModel = baseModel.copy(name = "orders")
        val createChange = StructureChange.Create(database = "app", target = draftModel)
        val createJson = createChange.toJsonObject()
        assertEquals("create", createJson["kind"]?.jsonPrimitive?.content)
        assertEquals("orders", createJson["current"]?.jsonObject?.get("name")?.jsonPrimitive?.content)

        val alterChange = StructureChange.Alter(database = "app", base = baseModel, current = draftModel)
        val previewReq = structurePreviewRequestJson(alterChange)
        assertEquals("alter", previewReq["change"]?.jsonObject?.get("kind")?.jsonPrimitive?.content)
        assertEquals("new_table", previewReq["change"]?.jsonObject?.get("base")?.jsonObject?.get("name")?.jsonPrimitive?.content)
        assertEquals("orders", previewReq["change"]?.jsonObject?.get("current")?.jsonObject?.get("name")?.jsonPrimitive?.content)

        val applyReq = structureApplyRequestJson(alterChange, "ALTER TABLE \"public\".\"new_table\" RENAME TO \"orders\";", allowRecreate = true)
        assertEquals("alter", applyReq["change"]?.jsonObject?.get("kind")?.jsonPrimitive?.content)
        assertEquals("true", applyReq["allowRecreate"]?.jsonPrimitive?.content)
        assertEquals("ALTER TABLE \"public\".\"new_table\" RENAME TO \"orders\";", applyReq["sql"]?.jsonPrimitive?.content)

        val prevTz = java.util.TimeZone.getDefault()
        val (backupName, zipName) = try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Ho_Chi_Minh"))
            backupTableName("orders", 1730000000000L) to defaultZipName(1730000000000L)
        } finally {
            java.util.TimeZone.setDefault(prevTz)
        }
        assertEquals("_orders_2024-10-27-10-33-20", backupName)
        assertEquals("zip-archive-2024-10-27-10-33-20.zip", zipName)
        val backupChange = StructureChange.BackupTable(schema = "public", table = "orders", backupName = backupName)
        assertEquals("backup-table", backupChange.toJsonObject()["kind"]?.jsonPrimitive?.content)
        assertEquals(backupName, backupChange.toJsonObject()["newName"]?.jsonPrimitive?.content)

        // TableEdit metadata round-trip & isStructureTabDirty (src/web/lib/db-table-edit.ts)
        val cleanMeta = withTableEdit(null, TableEdit(base = baseModel, current = baseModel, isNew = false))
        assertFalse(isStructureTabDirty(cleanMeta))
        val dirtyMeta = withTableEdit(cleanMeta, TableEdit(base = baseModel, current = draftModel, isNew = false))
        assertTrue(isStructureTabDirty(dirtyMeta))
        val parsedEdit = readTableEdit(dirtyMeta)
        assertNotNull(parsedEdit)
        assertEquals("new_table", parsedEdit!!.base.name)
        assertEquals("orders", parsedEdit.current.name)
        assertNull(resetTableEdit(parsedEdit))
        val newTblMeta = newTableTabMetadata(baseModel, tableNumber = 2, newTableId = "nt-1")
        assertEquals(true, readTableEdit(newTblMeta)?.isNew)

        // Server 200 / 409 StructurePreview payload: { sql, statements, recreate, warnings, transactional } (no dialect field!)
        val conflictPayload = Json.parseToJsonElement(
            """
            {
              "sql": "ALTER TABLE \"public\".\"new_table\" ADD COLUMN \"age\" integer;\nCREATE INDEX \"idx_age\" ON \"public\".\"new_table\" (\"age\");",
              "statements": [
                { "sql": "ALTER TABLE \"public\".\"new_table\" ADD COLUMN \"age\" integer" },
                { "sql": "CREATE INDEX \"idx_age\" ON \"public\".\"new_table\" (\"age\")" }
              ],
              "recreate": true,
              "warnings": ["Recreates table"],
              "transactional": true
            }
            """.trimIndent(),
        ).jsonObject

        val newer = newerPreviewOf(conflictPayload)
        assertNotNull(newer)
        assertTrue(newer!!.recreate)
        assertTrue(newer.transactional)
        assertEquals(2, newer.statements.size)
        assertEquals(listOf("Recreates table"), newer.warnings)

        // Server 400 StructureApplyFailure payload: { statement, index, applied, total }
        val pgPreview = StructurePreview(
            transactional = true,
            recreate = false,
            statements = listOf(
                StructureStatement("ALTER TABLE t ADD a int"),
                StructureStatement("ALTER TABLE t ADD b bad_type"),
                StructureStatement("CREATE INDEX idx_a ON t(a)"),
            ),
            sql = "ALTER TABLE t ADD a int;\nALTER TABLE t ADD b bad_type;\nCREATE INDEX idx_a ON t(a);",
        )
        val pgFailJson = Json.parseToJsonElement(
            """
            {
              "statement": "ALTER TABLE t ADD b bad_type",
              "index": 1,
              "applied": 0,
              "total": 3
            }
            """.trimIndent(),
        ).jsonObject
        val pgFailure = failureOf(pgFailJson)!!
        val pgReports = statementReports(pgPreview, pgFailure)
        assertEquals(StatementOutcome.ROLLED_BACK, pgReports[0].outcome)
        assertEquals(StatementOutcome.FAILED, pgReports[1].outcome)
        assertEquals(StatementOutcome.SKIPPED, pgReports[2].outcome)

        // Non-server failureOf fallback shape (`failedIndex` + `statements`) is rejected
        assertNull(
            failureOf(
                Json.parseToJsonElement(
                    """{ "error": "boom", "failedIndex": 0, "ranCount": 0, "statements": ["ALTER TABLE t ADD a int"] }""",
                ).jsonObject,
            ),
        )

        val mysqlPreview = pgPreview.copy(transactional = false)
        val mysqlFailJson = Json.parseToJsonElement(
            """
            {
              "statement": "ALTER TABLE t ADD b bad_type",
              "index": 1,
              "applied": 1,
              "total": 3
            }
            """.trimIndent(),
        ).jsonObject
        val mysqlFailure = failureOf(mysqlFailJson)!!
        val mysqlReports = statementReports(mysqlPreview, mysqlFailure)
        assertEquals(StatementOutcome.RAN, mysqlReports[0].outcome)
        assertEquals(StatementOutcome.FAILED, mysqlReports[1].outcome)
        assertEquals(StatementOutcome.SKIPPED, mysqlReports[2].outcome)
    }

    @Test
    fun impExpFormState_handlesExportImportUploadsColumnMapPreviewAndJobStatusWireContracts() {
        val currentDb = ImpExpDatabase(connectionId = 1, database = "analytics", schema = "public")
        var form = databaseExportForm(currentDb, table = "users")
        assertEquals("", form.zipFileName)
        assertEquals("Export (1)", impExpTitle(form))
        assertNull(runBlocker(form))

        // setTargetType refuses "database" when source is "database" or "query" (impexp-state.ts:163)
        assertEquals("csv", setTargetType(form, "database").targetType)
        val queryForm = gridExportForm(currentDb, format = "csv", sql = "SELECT * FROM users")
        assertEquals("csv", setTargetType(queryForm, "database").targetType)
        assertEquals("json", setTargetType(form, "json").targetType)

        form = addTables(form, listOf("orders"))
        assertEquals(2, activeRows(form).size)
        val exportJson = exportRequest(form)
        assertEquals("csv", exportJson["format"]?.jsonPrimitive?.content)
        assertTrue(exportJson["zip"]!!.isNullLiteral())
        val prevTz = java.util.TimeZone.getDefault()
        val emptyZipExportJson = try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Ho_Chi_Minh"))
            exportRequest(form.copy(archiveAsZip = true), now = 1730000000000L)
        } finally {
            java.util.TimeZone.setDefault(prevTz)
        }
        assertEquals(
            "zip-archive-2024-10-27-10-33-20.zip",
            emptyZipExportJson["zip"]?.jsonObject?.get("name")?.jsonPrimitive?.content,
        )
        val typedZipExportJson = exportRequest(
            form.copy(archiveAsZip = true, zipFileName = "my-typed-bundle"),
            now = 1730000000000L,
        )
        assertEquals(
            "my-typed-bundle",
            typedZipExportJson["zip"]?.jsonObject?.get("name")?.jsonPrimitive?.content,
        )
        val exportSource = exportJson["source"]!!.jsonObject
        assertEquals("database", exportSource["type"]?.jsonPrimitive?.content)
        assertEquals("public", exportSource["schema"]?.jsonPrimitive?.content)
        val exportTables = exportSource["tables"]!!.jsonArray
        assertEquals(2, exportTables.size)
        assertEquals("users", exportTables[0].jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals("users.csv", exportTables[0].jsonObject["target"]?.jsonPrimitive?.content)

        // Duplicate output file name blocks run
        val secondRowId = activeRows(form)[1].id
        val dupForm = updateRow(form, secondRowId, target = "users.csv")
        assertEquals(
            "Two export items use the same output file name \"users.csv\"",
            runBlocker(dupForm),
        )

        // Switch to import with CSV upload (only csv, json, jsonl are importable)
        var impForm = importIntoForm(currentDb)
        assertEquals("database", setTargetType(impForm, "csv").targetType)
        assertEquals("Add at least one file to import", runBlocker(impForm))

        // 128 MB upload limit check
        assertNotNull(uploadProblem("huge.csv", IMPORT_MAX_FILE_BYTES + 1))
        assertNull(uploadProblem("valid.csv", 1024L))

        val upload = ImpExpUpload(
            localId = "up-1",
            fileName = "customers.csv",
            size = 4096L,
            format = "csv",
            state = "uploading",
            progress = 0.5,
        )
        impForm = addUploads(impForm, listOf(upload))
        assertEquals("csv", impForm.sourceType)
        assertEquals("Wait for \"customers.csv\" to finish uploading", runBlocker(impForm))

        // Patch upload as ready
        impForm = patchUpload(
            impForm,
            localId = "up-1",
            state = "ready",
            progress = 1.0,
            id = "srv-up-1",
        )
        val fileRows = activeRows(impForm)
        assertEquals(1, fileRows.size)
        assertEquals("customers", fileRows[0].source)
        assertEquals("customers.csv", fileRows[0].sourceLabel)
        assertNull(runBlocker(impForm, targetReadonly = false))
        assertEquals("Target database connection is read-only", runBlocker(impForm, targetReadonly = true))

        // Template row updates action across all rows using server ImportAction ("truncate")
        impForm = updateRow(impForm, "row-template", action = "truncate")
        assertTrue(activeRows(impForm).all { it.action == "truncate" })

        // Column map validation & confirmation ({ src, dst, skip })
        val defaultMap = resetColumnMap(listOf("id", "name"))
        assertNull(confirmedColumnMap(defaultMap, listOf("id", "name")))
        val customMap = listOf(
            ColumnMapEntry(src = "id", dst = "customer_id", skip = false),
            ColumnMapEntry(src = "name", dst = "name", skip = true),
        )
        val confirmed = confirmedColumnMap(customMap, listOf("id", "name"))
        assertNotNull(confirmed)
        impForm = updateRow(impForm, fileRows[0].id, columnMap = confirmed, updateColumnMap = true)
        assertEquals("1 / 2", columnsLinkText(activeRows(impForm)[0]))

        // Upload preview request & response (`rows: unknown[][]`, `warnings: string[]`)
        val previewReq = uploadPreviewRequest(impForm, activeRows(impForm)[0])
        assertEquals("csv", previewReq["format"]?.jsonPrimitive?.content)
        assertEquals("", previewReq["options"]?.jsonObject?.get("csv")?.jsonObject?.get("delimiter")?.jsonPrimitive?.content)
        assertEquals(2, previewReq["columns"]?.jsonArray?.size)

        val previewResp = parseImportPreview(
            Json.parseToJsonElement(
                """
                {
                  "columns": ["customer_id"],
                  "rows": [
                    [101],
                    [null]
                  ],
                  "warnings": ["Skipped column name"]
                }
                """.trimIndent(),
            ).jsonObject,
        )
        assertEquals(listOf("customer_id"), previewResp.columns)
        assertEquals(2, previewResp.rows.size)
        assertEquals("101", (previewResp.rows[0]["customer_id"] as DbCellValue.Num).rawText)
        assertEquals(DbCellValue.Null, previewResp.rows[1]["customer_id"])
        assertEquals(listOf("Skipped column name"), previewResp.warnings)

        // Non-server rows-as-object entries in preview are ignored
        val previewObjRows = parseImportPreview(
            Json.parseToJsonElement(
                """{ "columns": ["customer_id"], "rows": [{ "customer_id": 101 }], "warnings": [] }""",
            ).jsonObject,
        )
        assertTrue(previewObjRows.rows.isEmpty())

        // Import job request wire shape: { format, options, schema, files: [{ upload, source, target, action, columns }] }
        val impReq = importRequest(impForm)
        assertEquals("csv", impReq["format"]?.jsonPrimitive?.content)
        assertEquals("public", impReq["schema"]?.jsonPrimitive?.content)
        val impFiles = impReq["files"]!!.jsonArray
        assertEquals(1, impFiles.size)
        val firstFile = impFiles[0].jsonObject
        assertEquals("srv-up-1", firstFile["upload"]?.jsonPrimitive?.content)
        assertEquals("customers", firstFile["source"]?.jsonPrimitive?.content)
        assertEquals("customers", firstFile["target"]?.jsonPrimitive?.content)
        assertEquals("truncate", firstFile["action"]?.jsonPrimitive?.content)
        val firstColMap = firstFile["columns"]!!.jsonArray[0].jsonObject
        assertEquals("id", firstColMap["src"]?.jsonPrimitive?.content)
        assertEquals("customer_id", firstColMap["dst"]?.jsonPrimitive?.content)
        assertNull(firstColMap["skip"])
        val secondColMap = firstFile["columns"]!!.jsonArray[1].jsonObject
        assertEquals("name", secondColMap["src"]?.jsonPrimitive?.content)
        assertEquals("true", secondColMap["skip"]?.jsonPrimitive?.content)

        // Job status response wire shape: { endedAt, items: [{ rowsRead, rowsWritten }], messages: [{ level: "warning", text, time }], messageCount }
        val statusResp = parseImpExpJobStatus(
            Json.parseToJsonElement(
                """
                {
                  "id": "job-99",
                  "kind": "import",
                  "state": "stopped",
                  "startedAt": 1000,
                  "endedAt": 2500,
                  "items": [
                    {
                      "source": "customers.csv",
                      "target": "customers",
                      "state": "stopped",
                      "rowsRead": 500,
                      "rowsWritten": 400
                    }
                  ],
                  "messages": [
                    { "level": "warning", "text": "Stopped by user", "time": 2500 }
                  ],
                  "messageCount": 3,
                  "files": []
                }
                """.trimIndent(),
            ).jsonObject,
        )
        assertEquals("stopped", statusResp.state)
        assertEquals(2500L, statusResp.endedAt)
        assertEquals(3, statusResp.messageCount)
        assertEquals(500L, statusResp.items[0].rowsRead)
        assertEquals(400L, statusResp.items[0].rowsWritten)
        assertEquals("warning", statusResp.messages[0].level)
        assertEquals(2500L, statusResp.messages[0].time)
        assertEquals(1, levelCounts(statusResp.messages)["warning"])

        // Non-server job status fallback fields (`finishedAt`, `nextMessageId`, `rowsProcessed`, `at`) are ignored
        val nonServerJobStatus = parseImpExpJobStatus(
            Json.parseToJsonElement(
                """
                {
                  "id": "job-100",
                  "kind": "export",
                  "state": "done",
                  "startedAt": 1000,
                  "finishedAt": 9999,
                  "nextMessageId": 42,
                  "items": [{ "source": "users", "target": "users.csv", "state": "done", "rowsProcessed": 77 }],
                  "messages": [{ "level": "info", "text": "Done", "at": 8888 }],
                  "files": []
                }
                """.trimIndent(),
            ).jsonObject,
        )
        assertNull(nonServerJobStatus.endedAt)
        assertEquals(1, nonServerJobStatus.messageCount)
        assertEquals(0L, nonServerJobStatus.items[0].rowsRead)
        assertEquals(0L, nonServerJobStatus.items[0].rowsWritten)
        assertEquals(0L, nonServerJobStatus.messages[0].time)
    }
}

private fun kotlinx.serialization.json.JsonElement.isNullLiteral(): Boolean =
    this is kotlinx.serialization.json.JsonNull
