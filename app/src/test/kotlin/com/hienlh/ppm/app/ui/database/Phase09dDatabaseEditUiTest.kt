package com.hienlh.ppm.app.ui.database

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.database.DbCellValue
import com.hienlh.ppm.state.database.DbGridPageData
import com.hienlh.ppm.state.database.GridChangesPayload
import com.hienlh.ppm.state.database.GridColumnSchema
import com.hienlh.ppm.state.database.GridReference
import com.hienlh.ppm.state.database.ResultColumnMeta
import com.hienlh.ppm.state.database.RowCountView
import com.hienlh.ppm.state.database.RowTotal
import com.hienlh.ppm.state.database.TableRef
import com.hienlh.ppm.state.database.TableReferences
import com.hienlh.ppm.state.database.parseChangesetPreview
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1024dp-h768dp")
class Phase09dDatabaseEditUiTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val samplePage = DbGridPageData(
        rawColumns = listOf(
            ResultColumnMeta("id", "bigint"),
            ResultColumnMeta("name", "text"),
            ResultColumnMeta("payload", "jsonb"),
            ResultColumnMeta("ledger_id", "bigint"),
        ),
        columnKeys = listOf("id", "name", "payload", "ledger_id"),
        rows = listOf(
            listOf(
                DbCellValue.Num("1", 1.0),
                DbCellValue.Text("Alice"),
                DbCellValue.Text("{\"tier\":\"gold\"}"),
                DbCellValue.Num("10", 10.0),
            ),
            listOf(
                DbCellValue.Num("2", 2.0),
                DbCellValue.Text("Bob"),
                DbCellValue.Text("<meta http-equiv=\"Content-Security-Policy\" content=\"*\"><p>Hello</p>"),
                DbCellValue.Num("20", 20.0),
            ),
        ),
        hasMore = false,
        sql = "SELECT * FROM \"invoices\"",
        rowKey = listOf("id"),
        countKey = "invoices:public",
    )

    private val sampleSchema = listOf(
        GridColumnSchema(name = "id", type = "bigint", nullable = false, pk = true, autoIncrement = true),
        GridColumnSchema(name = "name", type = "text", nullable = true),
        GridColumnSchema(name = "payload", type = "jsonb", nullable = true),
        GridColumnSchema(name = "ledger_id", type = "bigint", nullable = false, fkTable = "ledgers", fkColumn = "id"),
    )

    @Test
    fun tabletEditableGrid_supportsCellEditInsertDeleteSaveDialogCascadeAnd409Conflict() {
        var previewCalls = 0
        var lastAppliedChanges: GridChangesPayload? = null
        var lastCascades: List<TableRef> = emptyList()
        var currentPage by mutableStateOf(samplePage)

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                DatabaseTableTabContent(
                    tableName = "invoices",
                    schemaName = "public",
                    connectionName = "Payroll Prod",
                    dbType = "postgres",
                    pageData = currentPage,
                    schemaList = sampleSchema,
                    rowCountView = RowCountView(
                        text = "Rows: 2",
                        counting = false,
                        canCountExactly = false,
                        total = RowTotal.Exact(2),
                    ),
                    readonlyEnabled = false,
                    onPreviewChangeset = { payload ->
                        previewCalls += 1
                        parseChangesetPreview(
                            Json.parseToJsonElement(
                                """
                                {
                                  "statementCount": ${payload.totalOperations},
                                  "script": "UPDATE \"public\".\"invoices\" SET \"name\" = 'Alice Updated' WHERE \"id\" = 1;\nDELETE FROM \"public\".\"invoices\" WHERE \"id\" = 2;",
                                  "references": [
                                    {
                                      "schema": "public",
                                      "table": "invoice_lines",
                                      "paths": [["invoice_lines", "invoices"]],
                                      "cascadesInDb": false,
                                      "script": "DELETE FROM \"public\".\"invoice_lines\" WHERE \"invoice_id\" = 2;"
                                    }
                                  ]
                                }
                                """.trimIndent(),
                            ) as JsonObject,
                        )
                    },
                    onApplyChangeset = { payload, cascades ->
                        lastAppliedChanges = payload
                        lastCascades = cascades
                        throw PpmError.Server(
                            status = 409,
                            code = "CONFLICT",
                            serverMessage = "Statement 1 of 2 failed: The row was changed or deleted by someone else since it was loaded. Nothing was saved. Reload to see its current values.",
                            data = buildJsonObject {
                                put("statementIndex", 0)
                                put("statementCount", 2)
                                put("sql", "UPDATE \"public\".\"invoices\" SET \"name\" = 'Alice Updated' WHERE \"id\" = 1")
                                put("affected", 0)
                            },
                        )
                    },
                )
            }
        }

        // Switch to Form View to edit "name" on row 0 ("Alice" -> "Alice Updated")
        composeRule.onNodeWithTag("db-form-view-toggle-btn").performClick()
        composeRule.onNodeWithTag("db-form-value-name").performClick()
        composeRule.onNodeWithTag("db-form-input-name").performTextReplacement("Alice Updated")
        composeRule.onNodeWithTag("db-form-back-to-grid").performClick()

        // Insert an untouched new row (omitted from changeset until a cell is edited)
        composeRule.onNodeWithTag("db-insert-row-btn").performClick()
        composeRule.onNodeWithTag("db-inline-cancel-btn").performClick()

        // Select row 1 ("Bob", id = 2) via row gutter and delete it
        composeRule.onNodeWithTag("grid-row-marker-1").performClick()
        composeRule.onNodeWithTag("db-delete-row-btn").performClick()

        // Re-sort rows (Bob moves to row 0, Alice moves to row 1) BEFORE saving (C1 verification)
        composeRule.runOnIdle {
            currentPage = samplePage.copy(rows = samplePage.rows.reversed())
        }

        // Click Save (2)
        composeRule.onNodeWithTag("db-save-changes-btn").assertIsDisplayed()
        composeRule.onNodeWithTag("db-save-changes-btn").performClick()

        // Verify Save Dialog and CASCADE checklist
        composeRule.onNodeWithTag("db-save-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("db-save-cascade-section").assertIsDisplayed()
        composeRule.onNodeWithTag("db-save-cascade-check-all").performClick()
        assertTrue(previewCalls >= 1)

        // Click Confirm Save -> returns 409 Conflict with structured ChangesetFailure
        composeRule.onNodeWithTag("db-save-confirm-btn").performClick()
        composeRule.onNodeWithTag("db-save-error-banner").assertIsDisplayed()
        composeRule.onNodeWithTag("db-save-conflict-sql").assertIsDisplayed()
        assertEquals(1, lastAppliedChanges?.updates?.size)
        assertEquals(mapOf("id" to DbCellValue.Num("1", 1.0)), lastAppliedChanges?.updates?.first()?.key)
        assertEquals(mapOf("name" to DbCellValue.Text("Alice")), lastAppliedChanges?.updates?.first()?.original)
        assertEquals(1, lastAppliedChanges?.deletes?.size)
        assertEquals(mapOf("id" to DbCellValue.Num("2", 2.0)), lastAppliedChanges?.deletes?.first())
        assertEquals(0, lastAppliedChanges?.inserts?.size) // untouched new row was omitted!
        assertEquals(1, lastCascades.size)
        assertEquals("invoice_lines", lastCascades.first().table)
    }

    @Test
    fun tabletPanelsAndTools_supportFormViewCellDataJsonDocGenerateSqlAndReferencesMasterDetail() {
        var openedQuerySql: String? = null
        var jumpedTable: String? = null

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                DatabaseTableTabContent(
                    tableName = "invoices",
                    schemaName = "public",
                    connectionName = "Payroll Prod",
                    dbType = "postgres",
                    pageData = samplePage,
                    schemaList = sampleSchema,
                    rowCountView = RowCountView(
                        text = "Rows: 2",
                        counting = false,
                        canCountExactly = false,
                        total = RowTotal.Exact(2),
                    ),
                    readonlyEnabled = false,
                    tableReferences = TableReferences(
                        outgoing = listOf(
                            GridReference(
                                direction = "out",
                                constraintName = "fk_invoices_ledger",
                                columns = listOf("ledger_id"),
                                targetSchema = "public",
                                targetTable = "ledgers",
                                targetColumns = listOf("id"),
                            ),
                        ),
                        incoming = listOf(
                            GridReference(
                                direction = "in",
                                constraintName = "fk_lines_invoice",
                                columns = listOf("id"),
                                targetSchema = "public",
                                targetTable = "invoice_lines",
                                targetColumns = listOf("invoice_id"),
                            ),
                        ),
                    ),
                    onOpenReferencedTableTab = { table, _, _ -> jumpedTable = table },
                    onOpenQueryTab = { sql -> openedQuerySql = sql },
                )
            }
        }

        // Select single cell in row 1 via canvas and open Cell Data side panel
        composeRule.onNodeWithTag("grid-row-canvas-1").performClick()
        composeRule.onNodeWithTag("db-cell-data-panel-btn").performClick()
        composeRule.onNodeWithTag("db-cell-data-side-panel").assertIsDisplayed()
        composeRule.onNodeWithTag("db-cell-format-html").performScrollTo().performClick()
        composeRule.onNodeWithTag("db-cell-data-html-header").assertIsDisplayed()

        // Switch to References side panel and click incoming reference to open Master/Detail pane
        composeRule.onNodeWithTag("db-references-panel-btn").performClick()
        composeRule.onNodeWithTag("db-reference-item-invoice_lines").performClick()
        composeRule.onNodeWithTag("db-master-detail-pane").assertIsDisplayed()
        composeRule.onNodeWithTag("db-master-detail-open-tab-btn").performClick()
        assertEquals("invoice_lines", jumpedTable)

        // Open Generate SQL dialog and click Open in Query tab
        composeRule.onNodeWithTag("db-generate-sql-btn").performClick()
        composeRule.onNodeWithTag("db-generate-sql-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("db-gen-sql-open-query-btn").performClick()
        assertTrue(openedQuerySql?.contains("INSERT INTO") == true && openedQuerySql?.contains("\"invoices\"") == true)

        // Switch to Form View and jump to FK row
        composeRule.onNodeWithTag("db-form-view-toggle-btn").performClick()
        composeRule.onNodeWithTag("db-form-view").assertIsDisplayed()
        composeRule.onNodeWithTag("db-form-fk-jump-ledger_id").performClick()
        assertEquals("ledgers", jumpedTable)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp")
    fun phoneRowFormSheet_flushesDirtyFieldDraftBeforeNextOrSaveWithoutRequiringImeAction() {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                DatabaseTableTabContent(
                    tableName = "invoices",
                    schemaName = "public",
                    connectionName = "Payroll Prod",
                    dbType = "postgres",
                    pageData = samplePage,
                    schemaList = sampleSchema,
                    rowCountView = RowCountView(
                        text = "Rows: 2",
                        counting = false,
                        canCountExactly = false,
                        total = RowTotal.Exact(2),
                    ),
                    readonlyEnabled = false,
                )
            }
        }

        // Open Phone Row Form Sheet via "+ New row" button
        composeRule.onNodeWithTag("db-phone-new-row-btn").performClick()
        composeRule.onNodeWithTag("db-phone-row-form-sheet").assertIsDisplayed()

        // Navigate back to row 0 ("Alice") and type a new name without pressing Enter
        composeRule.onNodeWithTag("db-phone-form-prev-btn").performClick()
        composeRule.onNodeWithTag("db-phone-form-prev-btn").performClick()
        composeRule.onNodeWithTag("db-phone-form-input-name").performTextReplacement("Alice Mobile Edited")

        // Tap Next ▶ directly — must flush dirty draft into changeset before advancing
        composeRule.onNodeWithTag("db-phone-form-next-btn").performClick()

        // Close sheet and verify "Save (1)" button is displayed on phone bottom bar
        composeRule.onNodeWithTag("db-phone-form-close-btn").performClick()
        composeRule.onNodeWithText("Save (1)").assertIsDisplayed()
    }
}
