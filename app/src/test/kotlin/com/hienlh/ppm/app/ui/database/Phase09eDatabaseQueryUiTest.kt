package com.hienlh.ppm.app.ui.database

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.database.ChangesetApplyResult
import com.hienlh.ppm.state.database.ChangesetPreview
import com.hienlh.ppm.state.database.DbCellValue
import com.hienlh.ppm.state.database.GridChangesPayload
import com.hienlh.ppm.state.database.GridColumnSchema
import com.hienlh.ppm.state.database.QueryHistoryItem
import com.hienlh.ppm.state.database.QueryHistoryResponse
import com.hienlh.ppm.state.database.QueryResultSet
import com.hienlh.ppm.state.database.QueryScriptEvent
import com.hienlh.ppm.state.database.QueryStatementResult
import com.hienlh.ppm.state.database.QueryStatementSpan
import com.hienlh.ppm.state.database.READONLY_REFUSAL_PREFIX
import com.hienlh.ppm.state.database.SavedQueryFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1024dp-h768dp")
class Phase09eDatabaseQueryUiTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    @Test
    fun tabletQueryTab_runsScriptWithStreamingResultsMessagesLineJumpFormatAndSaveOverwrite() {
        val initialScript = "select a.id, b.id from public.invoices a join public.invoices b on a.id = b.id;\nselect * from broken_table;"
        var savedFile: SavedQueryFile? = null
        var savedContent: String? = null

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                DatabaseQueryTabContent(
                    tabTitle = "Query 1",
                    initialSql = initialScript,
                    initialOpenedSql = initialScript,
                    connectionId = 1,
                    connectionName = "Payroll Prod",
                    dbType = "postgres",
                    schemas = listOf("public"),
                    defaultProjectPath = "/workspace/ppm",
                    projectName = "ppm",
                    onExecuteQueryStream = { _, _, _, onEvent ->
                        onEvent(
                            QueryScriptEvent.Start(
                                statements = listOf(
                                    QueryStatementSpan(1, 1),
                                    QueryStatementSpan(2, 2),
                                ),
                            ),
                        )
                        onEvent(QueryScriptEvent.Running(index = 0))
                        onEvent(
                            QueryScriptEvent.Statement(
                                QueryStatementResult(
                                    index = 0,
                                    sql = "select a.id, b.id from public.invoices a join public.invoices b on a.id = b.id",
                                    startLine = 1,
                                    endLine = 1,
                                    durationMs = 12,
                                    resultSets = listOf(
                                        QueryResultSet(
                                            columns = listOf(
                                                GridColumnSchema(name = "id", type = "bigint"),
                                                GridColumnSchema(name = "id", type = "bigint"),
                                            ),
                                            rows = listOf(
                                                listOf(DbCellValue.Num("1", 1.0), DbCellValue.Num("1", 1.0)),
                                            ),
                                            truncated = false,
                                        ),
                                    ),
                                ),
                            ),
                        )
                        onEvent(QueryScriptEvent.Running(index = 1))
                        onEvent(
                            QueryScriptEvent.Statement(
                                QueryStatementResult(
                                    index = 1,
                                    sql = "select * from broken_table",
                                    startLine = 2,
                                    endLine = 2,
                                    resultSets = emptyList(),
                                    durationMs = 4,
                                    error = "relation \"broken_table\" does not exist",
                                    errorLine = 2,
                                ),
                            ),
                        )
                        onEvent(QueryScriptEvent.Done(durationMs = 16))
                    },
                    onListDirectoryEntries = { _ ->
                        listOf("query-1.sql" to "file")
                    },
                    onSaveSqlFile = { file, content ->
                        savedFile = file
                        savedContent = content
                    },
                )
            }
        }

        // Run the script
        composeRule.onNodeWithTag("db-query-run-btn").performClick()

        // Verify Result 1 tab is auto-selected and renders the result grid with deduplicated column names (`id`, `id (2)`)
        composeRule.onNodeWithTag("db-query-tab-result-0-0").assertIsDisplayed()
        composeRule.onNodeWithTag("db-query-result-view").assertIsDisplayed()
        composeRule.onNodeWithTag("grid-col-header-id").assertIsDisplayed()
        composeRule.onNodeWithTag("grid-col-header-id (2)").assertIsDisplayed()

        // Error line 2 is highlighted in the editor gutter
        composeRule.onNodeWithTag("db-query-error-line-2").assertIsDisplayed()

        // Switch to Messages sub-tab and click Line 2 to jump cursor to line 2
        composeRule.onNodeWithTag("db-query-tab-messages").performClick()
        composeRule.onNodeWithTag("db-query-messages-panel").assertIsDisplayed()
        composeRule.onNodeWithTag("db-query-message-line-2").performClick()
        composeRule.onNodeWithTag("db-query-cursor-indicator").assertTextEquals("Ln 2")

        // Format SQL -> marks query tab dirty
        composeRule.onNodeWithTag("db-query-format-btn").performClick()
        composeRule.onNodeWithTag("db-query-dirty-dot").assertIsDisplayed()

        // Save As... -> detects existing query-1.sql and asks for overwrite confirmation
        composeRule.onNodeWithTag("db-query-save-as-btn").performClick()
        composeRule.onNodeWithTag("db-query-save-as-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("db-query-save-confirm-btn").performClick()
        composeRule.onNodeWithTag("db-query-save-overwrite-warning").assertIsDisplayed()
        composeRule.onNodeWithTag("db-query-save-overwrite-confirm").performClick()

        assertNotNull(savedFile)
        assertEquals("/workspace/ppm/query-1.sql", savedFile?.path)
        assertTrue(savedContent?.contains("SELECT") == true)
        composeRule.onNodeWithTag("db-query-tab-title").assertTextEquals("query-1.sql")
        assertEquals(0, composeRule.onAllNodesWithTag("db-query-dirty-dot").fetchSemanticsNodes().size)
    }

    @Test
    fun editableQueryResultAndReadonlyWriteOnce_supportResultEditingDiscardGuardRefreshAndWriteOnceReplay() {
        var runCount = 0
        var appliedChanges: GridChangesPayload? = null
        var lastWriteOncePassword: String? = null
        var simulateReadonlyRefusal = false

        val invoicesSchema = listOf(
            GridColumnSchema(name = "id", type = "bigint", nullable = false, pk = true, autoIncrement = true),
            GridColumnSchema(name = "name", type = "text", nullable = true),
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                DatabaseQueryTabContent(
                    tabTitle = "Query 2",
                    initialSql = "SELECT id, name FROM public.invoices",
                    connectionId = 1,
                    connectionName = "Payroll Prod",
                    dbType = "postgres",
                    schemas = listOf("public"),
                    onLoadTableSchema = { _, _ -> invoicesSchema },
                    onExecuteQueryStream = { spec, _, _, onEvent ->
                        runCount += 1
                        lastWriteOncePassword = spec.writeOncePassword
                        onEvent(QueryScriptEvent.Start(statements = listOf(QueryStatementSpan(1, 1))))
                        if (simulateReadonlyRefusal && spec.writeOncePassword == null) {
                            onEvent(
                                QueryScriptEvent.Statement(
                                    QueryStatementResult(
                                        index = 0,
                                        sql = spec.sql,
                                        startLine = 1,
                                        endLine = 1,
                                        resultSets = emptyList(),
                                        durationMs = 2,
                                        error = "$READONLY_REFUSAL_PREFIX: Payroll Prod",
                                        errorLine = 1,
                                    ),
                                ),
                            )
                            onEvent(QueryScriptEvent.Done(durationMs = 2))
                        } else {
                            val nameValue = if (appliedChanges != null) "Alice Saved" else "Alice"
                            onEvent(
                                QueryScriptEvent.Statement(
                                    QueryStatementResult(
                                        index = 0,
                                        sql = spec.sql,
                                        startLine = 1,
                                        endLine = 1,
                                        durationMs = 5,
                                        resultSets = listOf(
                                            QueryResultSet(
                                                columns = invoicesSchema,
                                                rows = listOf(
                                                    listOf(DbCellValue.Num("1", 1.0), DbCellValue.Text(nameValue)),
                                                ),
                                                truncated = false,
                                            ),
                                        ),
                                    ),
                                ),
                            )
                            onEvent(QueryScriptEvent.Done(durationMs = 5))
                        }
                    },
                    onPreviewResultChangeset = { _, _, payload ->
                        ChangesetPreview(
                            statementCount = payload.totalOperations,
                            script = "UPDATE \"public\".\"invoices\" SET \"name\" = 'Alice Saved' WHERE \"id\" = 1;",
                            references = emptyList(),
                        )
                    },
                    onApplyResultChangeset = { _, _, payload, _ ->
                        appliedChanges = payload
                        ChangesetApplyResult(inserted = 0, updated = 1, deleted = 0, cascaded = 0, executionTimeMs = 3)
                    },
                )
            }
        }

        // 1. Run query -> editable single-table result with PK `id`
        composeRule.onNodeWithTag("db-query-run-btn").performClick()
        composeRule.onNodeWithTag("db-query-result-view").assertIsDisplayed()

        // 2. Edit row in Form View so the result grid has unsaved edits
        composeRule.onNodeWithTag("db-form-view-toggle-btn").performClick()
        composeRule.onNodeWithTag("db-form-value-name").performClick()
        composeRule.onNodeWithTag("db-form-input-name").performTextReplacement("Alice Saved")
        composeRule.onNodeWithTag("db-form-back-to-grid").performClick()

        // 3. Re-running query triggers DiscardResultEditsDialog; Cancel keeps unsaved edits
        composeRule.onNodeWithTag("db-query-run-btn").performClick()
        composeRule.onNodeWithTag("db-query-discard-edits-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("db-query-discard-edits-cancel").performClick()

        // 4. Save result changeset -> applies changeset and refreshes only that statement's result set
        composeRule.onNodeWithTag("db-save-changes-btn").performClick()
        composeRule.onNodeWithTag("db-save-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("db-save-confirm-btn").performClick()
        assertEquals(1, appliedChanges?.updates?.size)
        assertTrue(runCount >= 2)

        // 5. Simulate readonly connection refusal -> shows `Run with write access (once)` dialog and replays with password
        simulateReadonlyRefusal = true
        composeRule.onNodeWithTag("db-query-run-btn").performClick()
        composeRule.onNodeWithTag("db-query-write-once-btn").assertIsDisplayed()
        composeRule.onNodeWithTag("db-query-write-once-btn").performClick()
        composeRule.onNodeWithTag("db-query-write-once-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("db-query-write-once-password").performTextReplacement("ppm-secret")
        composeRule.onNodeWithTag("db-query-write-once-submit").performClick()
        assertEquals("ppm-secret", lastWriteOncePassword)
    }

    @Test
    fun phoneQueryTab_supportsBottomThumbBarOptionsSheetExplainAndHistoryPanel() {
        var lastExplainFlag = false
        var newTabSql: String? = null

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                DatabaseQueryTabContent(
                    tabTitle = "Query 3",
                    initialSql = "SELECT * FROM public.invoices",
                    connectionId = 1,
                    connectionName = "Payroll Prod",
                    dbType = "postgres",
                    onExecuteQueryStream = { spec, _, _, onEvent ->
                        lastExplainFlag = spec.explain
                        onEvent(QueryScriptEvent.Start(statements = listOf(QueryStatementSpan(1, 1))))
                        onEvent(
                            QueryScriptEvent.Statement(
                                QueryStatementResult(
                                    index = 0,
                                    sql = spec.sql,
                                    startLine = 1,
                                    endLine = 1,
                                    durationMs = 3,
                                    resultSets = listOf(
                                        QueryResultSet(
                                            columns = listOf(GridColumnSchema(name = "QUERY PLAN", type = "text")),
                                            rows = listOf(listOf(DbCellValue.Text("Seq Scan on invoices"))),
                                            truncated = false,
                                        ),
                                    ),
                                ),
                            ),
                        )
                        onEvent(QueryScriptEvent.Done(durationMs = 3))
                    },
                    onLoadHistory = { _, _ ->
                        QueryHistoryResponse(
                            items = listOf(
                                QueryHistoryItem(
                                    id = 42,
                                    sql = "SELECT count(*) FROM public.invoices",
                                    status = "ok",
                                    error = null,
                                    rowCount = 1,
                                    durationMs = 8,
                                    ranAt = 1_762_551_000_000L,
                                    byAgent = true,
                                    database = "payroll",
                                ),
                            ),
                            retentionDays = 90,
                            maxSizeMb = 50,
                        )
                    },
                    onOpenNewQueryTab = { sql -> newTabSql = sql },
                )
            }
        }

        // Phone bottom thumb bar is visible
        composeRule.onNodeWithTag("db-query-phone-bottom-bar").assertIsDisplayed()

        // Open `⋯` Run options sheet and trigger Explain
        composeRule.onNodeWithTag("db-query-phone-options-btn").performClick()
        composeRule.onNodeWithTag("db-query-phone-options-sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("db-query-explain-btn").performClick()
        assertTrue(lastExplainFlag)

        // Verify `Plan 1 (1)` sub-tab is created
        composeRule.onNodeWithText("Plan 1 (1)").assertIsDisplayed()

        // Open History panel and verify history item + New tab + Replace
        composeRule.onNodeWithTag("db-query-history-btn").performClick()
        composeRule.onNodeWithTag("db-query-history-panel").assertIsDisplayed()
        composeRule.onNodeWithTag("db-query-history-item-42").assertIsDisplayed()
        composeRule.onNodeWithTag("db-query-history-retention").assertIsDisplayed()
        composeRule.onNodeWithTag("db-query-history-new-tab-42").performClick()
        assertEquals("SELECT count(*) FROM public.invoices", newTabSql)
    }
}
