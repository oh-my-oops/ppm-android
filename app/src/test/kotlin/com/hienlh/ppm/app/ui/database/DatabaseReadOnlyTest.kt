package com.hienlh.ppm.app.ui.database

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.chat.DB_EXECUTE_APPROVAL
import com.hienlh.ppm.chat.DB_EXECUTE_TOOL
import com.hienlh.ppm.chat.DB_QUERY_TOOL
import com.hienlh.ppm.chat.DB_TOOL_LABELS
import com.hienlh.ppm.chat.DbExecuteApprovalInput
import com.hienlh.ppm.chat.DbToolCall
import com.hienlh.ppm.chat.OPEN_QUERY_TOOL
import com.hienlh.ppm.chat.commitsByItself
import com.hienlh.ppm.chat.dbExecuteApprovalInput
import com.hienlh.ppm.chat.dbToolCall
import com.hienlh.ppm.chat.dbToolOf
import com.hienlh.ppm.chat.selfCommitNote
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.state.database.ConnStatusState
import com.hienlh.ppm.state.database.DbCellValue
import com.hienlh.ppm.state.database.DbColumnRefItem
import com.hienlh.ppm.state.database.DbGridPageData
import com.hienlh.ppm.state.database.DbObjectItem
import com.hienlh.ppm.state.database.DbObjectListState
import com.hienlh.ppm.state.database.DbRef
import com.hienlh.ppm.state.database.GridColumnSchema
import com.hienlh.ppm.state.database.QueryResultSet
import com.hienlh.ppm.state.database.QueryScriptEvent
import com.hienlh.ppm.state.database.QueryStatementResult
import com.hienlh.ppm.state.database.QueryStatementSpan
import com.hienlh.ppm.state.database.READONLY_REFUSAL_PREFIX
import com.hienlh.ppm.state.database.ResultColumnMeta
import com.hienlh.ppm.state.database.RowCountView
import com.hienlh.ppm.state.database.RowTotal
import com.hienlh.ppm.state.database.TreeConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class DatabaseReadOnlyTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    @Test
    fun sidebarReadOnly_showsConnectionsDatabasesObjectsAndNewQueryWithoutAnyWriteControls() {
        val connections = listOf(
            TreeConnection(
                id = 1,
                type = "postgres",
                name = "Prod DB",
                groupName = "Production",
                readonly = 0,
                defaultDatabase = "main_db",
                singleDatabase = false,
            ),
            TreeConnection(
                id = 2,
                type = "postgres",
                name = "Z Missing Driver DB",
                groupName = "Production",
                readonly = 0,
                defaultDatabase = "legacy_db",
                singleDatabase = false,
            ),
        )
        val objectList = DbObjectListState(
            schemas = listOf("public"),
            objects = listOf(
                DbObjectItem(kind = "table", schema = "public", name = "users", rowEstimate = 120L),
            ),
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                DatabaseSidebarContent(
                    connections = connections,
                    statusMap = mapOf(
                        1 to ConnStatusState.Open,
                        2 to ConnStatusState.Error("Driver pg not installed", missingDriverId = "pg"),
                    ),
                    databasesMap = mapOf(1 to listOf("main_db")),
                    collapsedFolders = emptySet(),
                    emptyFolders = emptyList(),
                    expandedConns = setOf(1),
                    currentDb = DbRef(1, "main_db"),
                    focusedDb = DbRef(1, "main_db"),
                    objectList = objectList,
                    selectedSchema = "public",
                    openGroups = setOf("table"),
                    expandedObjects = setOf("1/main_db|public.users"),
                    structuresMap = mapOf(
                        "1/main_db|public.users" to listOf(
                            DbColumnRefItem(schema = "public", table = "users", name = "id", type = "int4", pk = true),
                            DbColumnRefItem(schema = "public", table = "users", name = "email", type = "text", pk = false),
                        ),
                    ),
                    shownPerGroup = emptyMap(),
                    readOnly = true,
                )
            }
        }

        // Read-only badge, + Query, connections, databases, tables, and columns are visible
        composeRule.onNodeWithTag("db-sidebar-readonly-badge").assertIsDisplayed()
        composeRule.onNodeWithTag("db-new-query-btn").assertIsDisplayed()
        composeRule.onNodeWithTag("db-folder-Production").assertIsDisplayed()
        composeRule.onNodeWithTag("db-conn-row-1").assertIsDisplayed()
        composeRule.onNodeWithTag("db-database-row-1-main_db").assertIsDisplayed()
        composeRule.onNodeWithTag("db-obj-row-users").assertIsDisplayed()
        composeRule.onNodeWithText("email").assertIsDisplayed()
        composeRule.onNodeWithTag("db-readonly-connections-tablet-note").assertIsDisplayed()
        composeRule.onNodeWithTag("db-connections-tree").performScrollToNode(hasTestTag("db-conn-row-2"))
        composeRule.onNodeWithTag("db-readonly-driver-tablet-note", useUnmergedTree = true).assertIsDisplayed()

        // No write controls in sidebar header, missing-driver row, or object rows
        assertTrue(composeRule.onAllNodesWithTag("db-new-connection-btn").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("db-new-folder-btn").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("db-new-table-btn").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("db-open-impexp-btn").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("db-export-btn").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("db-install-driver-pg").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("db-obj-structure-users").fetchSemanticsNodes().isEmpty())

        // Connection context menu shows only read actions (Connect, New query, Disconnect), no Edit/Duplicate/Delete
        composeRule.onNodeWithTag("db-connections-tree").performScrollToNode(hasTestTag("db-conn-row-1"))
        composeRule.onNodeWithTag("db-conn-menu-btn-1").performClick()
        composeRule.onNodeWithText("Connect").assertIsDisplayed()
        composeRule.onNodeWithText("New query").assertIsDisplayed()
        composeRule.onNodeWithText("Disconnect").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("Edit").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("Duplicate").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("Delete").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun tableGridReadOnly_showsRowsFiltersAndReadOnlyRowSheetWithNoWriteControls() {
        val schema = listOf(
            GridColumnSchema(name = "id", type = "int4", pk = true, nullable = false, autoIncrement = true),
            GridColumnSchema(name = "email", type = "text", pk = false, nullable = true),
            GridColumnSchema(
                name = "org_id",
                type = "int4",
                pk = false,
                nullable = true,
                fkTable = "orgs",
                fkSchema = "public",
                fkColumn = "id",
            ),
        )
        val pageData = DbGridPageData(
            rawColumns = listOf(
                ResultColumnMeta("id", "int4"),
                ResultColumnMeta("email", "text"),
                ResultColumnMeta("org_id", "int4"),
            ),
            columnKeys = listOf("id", "email", "org_id"),
            rows = listOf(
                listOf(DbCellValue.Num("1", 1.0), DbCellValue.Text("alice@ppm.dev"), DbCellValue.Num("10", 10.0)),
                listOf(DbCellValue.Num("2", 2.0), DbCellValue.Text("bob@ppm.dev"), DbCellValue.Null),
            ),
            hasMore = true,
            sql = "SELECT * FROM \"users\"",
            rowKey = listOf("id"),
            countKey = "users:public",
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                DatabaseTableTabContent(
                    tableName = "users",
                    schemaName = "public",
                    connectionName = "Prod DB",
                    dbType = "postgres",
                    pageData = pageData,
                    schemaList = schema,
                    rowCountView = RowCountView(
                        text = "Rows: Many",
                        counting = false,
                        canCountExactly = true,
                        total = RowTotal.Many,
                    ),
                    readonlyEnabled = false,
                    readOnly = true,
                )
            }
        }

        // Header, rows, filters button, and static Read-only badge are shown
        composeRule.onNodeWithTag("db-table-phone-header").assertIsDisplayed()
        composeRule.onNodeWithTag("db-phone-bottom-bar").assertIsDisplayed()
        composeRule.onNodeWithTag("db-phone-readonly-badge").assertIsDisplayed()
        composeRule.onNodeWithTag("db-phone-filters-btn").assertIsDisplayed()
        composeRule.onNodeWithTag("grid-col-header-email").assertIsDisplayed()
        composeRule.onNodeWithTag("grid-row-canvas-0").assertIsDisplayed()

        // No write buttons anywhere in the grid or bottom bar
        assertTrue(composeRule.onAllNodesWithTag("db-phone-new-row-btn").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("db-phone-save-btn").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("db-save-changes-btn").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("db-insert-row-btn").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("db-clone-row-btn").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("db-delete-row-btn").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("db-edit-cell-btn").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("db-set-null-btn").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("db-revert-row-btn").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("db-readonly-toggle-btn").fetchSemanticsNodes().isEmpty())

        // Overflow menu has read actions only (no Read-only/Editable toggle, no Generate SQL)
        composeRule.onNodeWithTag("db-phone-overflow-btn").performClick()
        composeRule.onNodeWithTag("db-phone-overflow-menu").assertIsDisplayed()
        composeRule.onNodeWithTag("db-fetch-all-btn").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("db-readonly-toggle-btn").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("db-generate-sql-btn").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("db-phone-overflow-btn").performClick()

        // Tapping a row opens the read-only row sheet (shows value, no Save, no Revert/Delete, no NULL/FK picker/inputs)
        composeRule.onNodeWithTag("grid-row-canvas-0").performClick()
        composeRule.onNodeWithTag("db-phone-row-form-sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("db-phone-form-readonly-badge").assertIsDisplayed()
        composeRule.onNodeWithText("alice@ppm.dev").assertIsDisplayed()
        composeRule.onNodeWithTag("db-phone-form-prev-btn").assertIsDisplayed()
        composeRule.onNodeWithTag("db-phone-form-next-btn").assertIsDisplayed()
        composeRule.onNodeWithTag("db-phone-form-fk-jump-org_id").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("db-phone-form-save-btn").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("db-phone-form-revert-row").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("db-phone-form-delete-row").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("db-phone-form-null-email").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("db-phone-form-fk-pick-org_id").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("db-phone-form-input-email").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("db-phone-form-close-btn").performClick()

        // Long-pressing a row shows "View row" instead of "Edit row"
        composeRule.onNodeWithTag("grid-row-canvas-0").performTouchInput { longClick() }
        composeRule.onNodeWithTag("db-phone-cell-sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("db-phone-view-row-btn").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("db-phone-edit-row-btn").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun queryTabReadOnly_runsReadQueriesShowsReadOnlyNoteOnRefusedWriteAndHidesWriteOnceAndResultEdits() {
        var simulateReadonlyRefused = false
        val usersSchema = listOf(
            GridColumnSchema(name = "id", type = "int4", nullable = false, pk = true),
            GridColumnSchema(name = "email", type = "text", nullable = true, pk = false),
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                DatabaseQueryTabContent(
                    tabTitle = "Query 1",
                    initialSql = "SELECT id, email FROM public.users",
                    connectionId = 1,
                    connectionName = "Prod DB",
                    dbType = "postgres",
                    schemas = listOf("public"),
                    readonlyConnection = false,
                    readOnly = true,
                    onLoadTableSchema = { _, _ -> usersSchema },
                    onExecuteQueryStream = { spec, _, _, onEvent ->
                        onEvent(QueryScriptEvent.Start(statements = listOf(QueryStatementSpan(1, 1))))
                        if (simulateReadonlyRefused) {
                            onEvent(
                                QueryScriptEvent.Statement(
                                    QueryStatementResult(
                                        index = 0,
                                        sql = spec.sql,
                                        startLine = 1,
                                        endLine = 1,
                                        resultSets = emptyList(),
                                        durationMs = 2,
                                        error = "$READONLY_REFUSAL_PREFIX: Prod DB",
                                        errorLine = 1,
                                    ),
                                ),
                            )
                            onEvent(QueryScriptEvent.Done(durationMs = 2))
                        } else {
                            onEvent(
                                QueryScriptEvent.Statement(
                                    QueryStatementResult(
                                        index = 0,
                                        sql = spec.sql,
                                        startLine = 1,
                                        endLine = 1,
                                        durationMs = 4,
                                        resultSets = listOf(
                                            QueryResultSet(
                                                columns = usersSchema,
                                                rows = listOf(
                                                    listOf(
                                                        DbCellValue.Num("1", 1.0),
                                                        DbCellValue.Text("alice@ppm.dev"),
                                                    ),
                                                ),
                                                truncated = false,
                                            ),
                                        ),
                                    ),
                                ),
                            )
                            onEvent(QueryScriptEvent.Done(durationMs = 4))
                        }
                    },
                )
            }
        }

        // Static Read-only badge in query toolbar
        composeRule.onNodeWithTag("db-query-readonly-badge").assertIsDisplayed()

        // Run read query -> result grid is strictly read-only with no write buttons
        composeRule.onNodeWithTag("db-query-run-btn").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("db-query-result-view").assertIsDisplayed()
        composeRule.onNodeWithTag("grid-col-header-email").assertIsDisplayed()
        composeRule.onNodeWithTag("grid-row-canvas-0").assertIsDisplayed()
        composeRule.onNodeWithTag("db-phone-readonly-badge").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("db-phone-new-row-btn").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("db-phone-save-btn").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("db-save-changes-btn").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("db-insert-row-btn").fetchSemanticsNodes().isEmpty())

        // Run a write query that gets refused as readonly -> shows tablet note, never "Run with write access (once)"
        simulateReadonlyRefused = true
        composeRule.onNodeWithTag("db-query-run-btn").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("db-query-readonly-tablet-note").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("db-query-write-once-btn").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aiDatabaseTools_parsesDbQueryOpenQueryDbExecuteAndSelfCommitNote() {
        assertEquals(DB_QUERY_TOOL, dbToolOf("mcp__ppm-db__db_query"))
        assertEquals(OPEN_QUERY_TOOL, dbToolOf("ppm_db:open_query"))
        assertEquals(DB_EXECUTE_TOOL, dbToolOf("mcp__ppm-db__db_execute"))
        assertEquals("ppm:db_execute", DB_EXECUTE_APPROVAL)
        assertEquals("Database query", DB_TOOL_LABELS[DB_QUERY_TOOL])
        assertEquals("Open query", DB_TOOL_LABELS[OPEN_QUERY_TOOL])
        assertEquals("Database change", DB_TOOL_LABELS[DB_EXECUTE_TOOL])

        val openCall = dbToolCall(
            "mcp__ppm-db__open_query",
            WireJson.parse("""{"connection":"payroll-prod","database":"hr","sql":"SELECT * FROM employees"}"""),
        )
        assertEquals(
            DbToolCall(
                tool = "open_query",
                connection = "payroll-prod",
                sql = "SELECT * FROM employees",
                database = "hr",
            ),
            openCall,
        )

        val approval = dbExecuteApprovalInput(
            WireJson.parse(
                """{"connection":"payroll-prod","database":"hr","sql":"UPDATE employees SET active = false WHERE id = 7","reason":"Deactivate departed employee","expected_rows":1,"dbType":"postgres"}""",
            ),
        )
        assertEquals(
            DbExecuteApprovalInput(
                connection = "payroll-prod",
                database = "hr",
                sql = "UPDATE employees SET active = false WHERE id = 7",
                reason = "Deactivate departed employee",
                expectedRows = 1.0,
                dbType = "postgres",
            ),
            approval,
        )

        val ddlThenDml = "ALTER TABLE users ADD COLUMN role TEXT;\nUPDATE users SET role = 'member';"
        assertTrue(commitsByItself(ddlThenDml))
        assertFalse(commitsByItself("UPDATE users SET role = 'member';"))
        assertNull(selfCommitNote("postgres", ddlThenDml))
        assertEquals(
            "MySQL and MariaDB commit CREATE, ALTER, DROP, TRUNCATE and RENAME by themselves, with everything before them. If a later statement fails, only what follows the last one is rolled back.",
            selfCommitNote("mysql", ddlThenDml),
        )
    }
}
