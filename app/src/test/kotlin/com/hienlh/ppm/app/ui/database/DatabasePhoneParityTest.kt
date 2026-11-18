package com.hienlh.ppm.app.ui.database

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.PrefsStore
import com.hienlh.ppm.app.ui.phone.PhoneNav
import com.hienlh.ppm.app.ui.phone.PhoneShell
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
import com.hienlh.ppm.state.database.ConnectionFormValues
import com.hienlh.ppm.state.database.DbCellValue
import com.hienlh.ppm.state.database.DbGridPageData
import com.hienlh.ppm.state.database.DbObjectItem
import com.hienlh.ppm.state.database.DbObjectListState
import com.hienlh.ppm.state.database.DbRef
import com.hienlh.ppm.state.database.DbStructureColumn
import com.hienlh.ppm.state.database.DbTableStructure
import com.hienlh.ppm.state.database.GridColumnSchema
import com.hienlh.ppm.state.database.QueryResultSet
import com.hienlh.ppm.state.database.QueryScriptEvent
import com.hienlh.ppm.state.database.QueryStatementResult
import com.hienlh.ppm.state.database.QueryStatementSpan
import com.hienlh.ppm.state.database.READONLY_REFUSAL_PREFIX
import com.hienlh.ppm.state.database.ResultColumnMeta
import com.hienlh.ppm.state.database.RowCountView
import com.hienlh.ppm.state.database.RowTotal
import com.hienlh.ppm.state.database.TableEdit
import com.hienlh.ppm.state.database.TreeConnection
import com.hienlh.ppm.state.database.modelFromStructure
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

/**
 * The phone does what the tablet does with a database, as the web below `md` does: add, edit and
 * delete connections, install a driver, edit rows, run a refused script with write access once.
 * Structure is looked at, not changed, and Import/Export says it needs a wider screen — the web's
 * own phone behaviour (`structure-tab.tsx`, `impexp-tab.tsx`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class DatabasePhoneParityTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val connections = listOf(
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

    private val usersObjects = DbObjectListState(
        schemas = listOf("public"),
        objects = listOf(DbObjectItem(kind = "table", schema = "public", name = "users", rowEstimate = 120L)),
    )

    @Test
    fun phoneSidebar_addsEditsDuplicatesDeletesConnectionsAndInstallsDrivers() {
        var newConnection = 0
        var edited: Int? = null
        var duplicated: Int? = null
        var deleted: Int? = null
        var installed: String? = null
        var structureOf: String? = null

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
                    objectList = usersObjects,
                    selectedSchema = "public",
                    openGroups = setOf("table"),
                    expandedObjects = emptySet(),
                    structuresMap = emptyMap(),
                    shownPerGroup = emptyMap(),
                    onNewConnection = { newConnection++ },
                    onEditConnection = { edited = it.id },
                    onDuplicateConnection = { duplicated = it },
                    onDeleteConnection = { deleted = it },
                    onInstallDriver = { installed = it },
                    onOpenObjectStructure = { structureOf = it.name },
                )
            }
        }

        // Nothing is left to a tablet, and no read-only badge stands in for the controls
        assertTrue(composeRule.onAllNodesWithText("on a tablet", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("db-sidebar-readonly-badge").fetchSemanticsNodes().isEmpty())

        // + Connection, + Folder and Export are there, 44 dp tall; + Table and Imp/Exp are not (`editsStructure`/`impExp: !mobile`)
        composeRule.onNodeWithTag("db-new-connection-btn").assertIsDisplayed().assertHeightIsAtLeast(44.dp)
        composeRule.onNodeWithTag("db-new-folder-btn").assertExists()
        composeRule.onNodeWithTag("db-export-btn").assertExists()
        assertTrue(composeRule.onAllNodesWithTag("db-new-table-btn").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("db-open-impexp-btn").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("db-new-connection-btn").performClick()
        assertEquals(1, newConnection)

        // A table's structure opens to be looked at
        composeRule.onNodeWithTag("db-obj-structure-users").performClick()
        assertEquals("users", structureOf)

        // The missing driver installs from the phone
        composeRule.onNodeWithTag("db-connections-tree").performScrollToNode(hasTestTag("db-conn-row-2"))
        composeRule.onNodeWithTag("db-install-driver-pg").assertHeightIsAtLeast(44.dp).performClick()
        assertEquals("pg", installed)

        // The connection menu edits, duplicates and deletes
        composeRule.onNodeWithTag("db-connections-tree").performScrollToNode(hasTestTag("db-conn-row-1"))
        composeRule.onNodeWithTag("db-conn-menu-btn-1").performClick()
        composeRule.onNodeWithText("New query").assertIsDisplayed()
        composeRule.onNodeWithText("Edit").assertHeightIsAtLeast(44.dp).performClick()
        assertEquals(1, edited)
        composeRule.onNodeWithTag("db-conn-menu-btn-1").performClick()
        composeRule.onNodeWithText("Duplicate").performClick()
        assertEquals(1, duplicated)
        composeRule.onNodeWithTag("db-conn-menu-btn-1").performClick()
        composeRule.onNodeWithText("Delete").performClick()
        assertEquals(1, deleted)
    }

    @Test
    fun tabletSidebar_stillOffersNewTableAndImportExport() {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                DatabaseSidebarContent(
                    connections = connections,
                    statusMap = mapOf(1 to ConnStatusState.Open),
                    databasesMap = mapOf(1 to listOf("main_db")),
                    collapsedFolders = emptySet(),
                    emptyFolders = emptyList(),
                    expandedConns = setOf(1),
                    currentDb = DbRef(1, "main_db"),
                    focusedDb = DbRef(1, "main_db"),
                    objectList = usersObjects,
                    selectedSchema = "public",
                    openGroups = setOf("table"),
                    expandedObjects = emptySet(),
                    structuresMap = emptyMap(),
                    shownPerGroup = emptyMap(),
                )
            }
        }

        composeRule.onNodeWithTag("db-new-table-btn").assertExists()
        composeRule.onNodeWithTag("db-open-impexp-btn").assertExists()
        composeRule.onNodeWithTag("db-new-connection-btn").assertExists()
    }

    @Test
    fun phoneConnectionForm_savesAndConnectsFromFullWidthThumbButtons() {
        val saves = mutableListOf<Pair<ConnectionFormValues, Boolean>>()

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                DbConnectionFormContent(
                    initialValues = ConnectionFormValues(
                        type = "postgres",
                        name = "Orders",
                        entry = "fields",
                        host = "db.internal",
                        port = "5432",
                        user = "admin",
                        database = "orders",
                    ),
                    onSaveConnection = { values, connect -> saves += values to connect },
                )
            }
        }

        composeRule.onNodeWithTag("db-connection-form").assertIsDisplayed()
        composeRule.onNodeWithTag("db-form-tab-ssh").assertHeightIsAtLeast(44.dp)
        composeRule.onNodeWithTag("db-form-connect-btn").assertHeightIsAtLeast(44.dp)
        composeRule.onNodeWithTag("db-form-test-btn").assertHeightIsAtLeast(44.dp)
        composeRule.onNodeWithTag("db-form-save-btn").assertHeightIsAtLeast(44.dp).performClick()
        composeRule.onNodeWithTag("db-form-connect-btn").performClick()
        assertEquals(listOf(false, true), saves.map { it.second })
        assertEquals("db.internal", saves.first().first.host)
    }

    @Test
    fun phoneTableGrid_editsRowsThroughTheRowSheetAndSaves() {
        val schema = listOf(
            GridColumnSchema(name = "id", type = "int4", pk = true, nullable = false, autoIncrement = true),
            GridColumnSchema(name = "email", type = "text", pk = false, nullable = true),
        )
        val pageData = DbGridPageData(
            rawColumns = listOf(ResultColumnMeta("id", "int4"), ResultColumnMeta("email", "text")),
            columnKeys = listOf("id", "email"),
            rows = listOf(listOf(DbCellValue.Num("1", 1.0), DbCellValue.Text("alice@ppm.dev"))),
            hasMore = false,
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
                    rowCountView = RowCountView(text = "Rows: 1", counting = false, canCountExactly = false, total = RowTotal.Exact(1)),
                    readonlyEnabled = false,
                )
            }
        }

        // The thumb bar adds and saves rows, 44 dp tall, with no read-only badge in their place
        composeRule.onNodeWithTag("db-phone-new-row-btn").assertIsEnabled().assertHeightIsAtLeast(44.dp)
        composeRule.onNodeWithTag("db-phone-save-btn").assertHeightIsAtLeast(44.dp)
        assertTrue(composeRule.onAllNodesWithTag("db-phone-readonly-badge").fetchSemanticsNodes().isEmpty())

        // Tapping a row opens its sheet with inputs and a Save
        composeRule.onNodeWithTag("grid-row-canvas-0").performClick()
        composeRule.onNodeWithTag("db-phone-row-form-sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("db-phone-form-input-email").assertExists()
        composeRule.onNodeWithTag("db-phone-form-save-btn").assertExists()
        composeRule.onNodeWithTag("db-phone-form-close-btn").performClick()

        // Long-press offers Edit row; the overflow menu has Generate SQL and the Read-only/Editable switch
        composeRule.onNodeWithTag("grid-row-canvas-0").performTouchInput { longClick() }
        composeRule.onNodeWithTag("db-phone-edit-row-btn").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("db-phone-view-row-btn").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun phoneGrid_missingDriverInstallsFromThePhone() {
        var installed: String? = null
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                DatabaseTableTabContent(
                    tableName = "users",
                    schemaName = "public",
                    connectionName = "Prod DB",
                    dbType = "postgres",
                    pageData = null,
                    schemaList = emptyList(),
                    rowCountView = null,
                    missingDriverId = "pg",
                    onInstallMissingDriver = { installed = it },
                )
            }
        }

        assertTrue(composeRule.onAllNodesWithText("on a tablet", substring = true).fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("db-grid-install-driver-btn").assertHeightIsAtLeast(44.dp).performClick()
        assertEquals("pg", installed)
    }

    private fun refusedThenAllowed(onRun: (String?) -> Unit): suspend (QueryRunSpec, String, (okhttp3.Call) -> Unit, suspend (QueryScriptEvent) -> Unit) -> Unit =
        { spec, _, _, onEvent ->
            onRun(spec.writeOncePassword)
            onEvent(QueryScriptEvent.Start(statements = listOf(QueryStatementSpan(1, 1))))
            val refused = spec.writeOncePassword == null
            onEvent(
                QueryScriptEvent.Statement(
                    QueryStatementResult(
                        index = 0,
                        sql = spec.sql,
                        startLine = 1,
                        endLine = 1,
                        resultSets = if (refused) emptyList() else listOf(QueryResultSet(columns = emptyList(), rows = emptyList(), truncated = false)),
                        durationMs = 2,
                        error = if (refused) "$READONLY_REFUSAL_PREFIX: Payroll Prod" else null,
                        errorLine = if (refused) 1 else null,
                    ),
                ),
            )
            onEvent(QueryScriptEvent.Done(durationMs = 2))
        }

    @Test
    fun phoneQueryTab_runsARefusedScriptWithWriteAccessOnceAfterPpmsPassword() {
        val runs = mutableListOf<String?>()

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                DatabaseQueryTabContent(
                    tabTitle = "Query 1",
                    initialSql = "DELETE FROM payroll WHERE id = 7",
                    connectionId = 1,
                    connectionName = "Payroll Prod",
                    dbType = "postgres",
                    readonlyConnection = true,
                    onExecuteQueryStream = refusedThenAllowed { runs += it },
                )
            }
        }

        composeRule.onNodeWithTag("db-query-run-btn").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf<String?>(null), runs)

        // The web's bar: "<name> is readonly." and the 44 dp action, never a tablet note
        composeRule.onNodeWithText("Payroll Prod is readonly.").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("on a tablet", substring = true).fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("db-query-write-once-btn").assertHeightIsAtLeast(44.dp).performClick()

        // A bottom sheet: the title, the explanation, the refused script and PPM's password
        composeRule.onNodeWithTag("db-query-write-once-dialog").assertIsDisplayed()
        composeRule.onNodeWithText(WRITE_ONCE_TITLE).assertIsDisplayed()
        composeRule.onNodeWithText(writeOnceExplanation("Payroll Prod")).assertIsDisplayed()
        composeRule.onNode(hasText("DELETE FROM payroll WHERE id = 7") and hasAnyAncestor(hasTestTag("db-query-write-once-sql")), useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("db-query-write-once-submit").assertIsNotEnabled().assertHeightIsAtLeast(44.dp)
        composeRule.onNodeWithTag("db-query-write-once-password").performTextReplacement("ppm-secret")
        composeRule.onNodeWithTag("db-query-write-once-submit").assertIsEnabled().performClick()

        composeRule.waitForIdle()
        assertEquals(listOf(null, "ppm-secret"), runs)
        assertTrue(composeRule.onAllNodesWithTag("db-query-write-once-dialog").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("db-query-write-once-bar").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun phoneQueryTab_aPpmWithNoPasswordAsksForNoneAndStillRunsWithWriteAccess() {
        val runs = mutableListOf<String?>()

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                DatabaseQueryTabContent(
                    initialSql = "UPDATE payroll SET paid = true",
                    connectionName = "Payroll Prod",
                    readonlyConnection = true,
                    writeOncePasswordRequired = false,
                    onExecuteQueryStream = refusedThenAllowed { runs += it },
                )
            }
        }

        composeRule.onNodeWithTag("db-query-run-btn").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("db-query-write-once-btn").performClick()
        assertTrue(composeRule.onAllNodesWithTag("db-query-write-once-password").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("db-query-write-once-submit").assertIsEnabled().performClick()
        composeRule.waitForIdle()
        // An empty password is still sent as `writeOnce`, as the web's `{ writeOnce: { password } }`
        assertEquals(listOf(null, ""), runs)
    }

    @Test
    fun tabletQueryTab_writeOnceIsACentredCardWithCancelBesideRunOnce() {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                DatabaseQueryTabContent(
                    initialSql = "DELETE FROM payroll",
                    connectionName = "Payroll Prod",
                    readonlyConnection = true,
                    onExecuteQueryStream = refusedThenAllowed { },
                )
            }
        }

        composeRule.onNodeWithTag("db-query-run-btn").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("db-query-write-once-btn").performClick()
        composeRule.onNodeWithText(WRITE_ONCE_TITLE).assertIsDisplayed()
        composeRule.onNodeWithTag("db-query-write-once-cancel").performClick()
        assertTrue(composeRule.onAllNodesWithTag("db-query-write-once-dialog").fetchSemanticsNodes().isEmpty())
        // The offer stays for another try
        composeRule.onNodeWithTag("db-query-write-once-btn").assertIsDisplayed()
    }

    @Test
    fun phoneStructure_withUnsavedChangesShowsTheWebsNoticeOverTheReadOnlyStructure() {
        val structure = DbTableStructure(
            name = "employees",
            columns = listOf(
                DbStructureColumn(name = "id", type = "int", nullable = false),
                DbStructureColumn(name = "email", type = "text", nullable = true),
            ),
        )
        val base = modelFromStructure(structure, "postgres")
        val edit = TableEdit(base = base, current = base.copy(comment = "changed on a tablet"))
        var mobile by mutableStateOf(true)

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                DatabaseStructureTabContent(structure = structure, initialTableEdit = edit)
            }
        }

        composeRule.onNodeWithTag("db-structure-phone-readonly").assertExists()
        composeRule.onNodeWithText(
            "This tab holds changes to the table that are not saved. Open it on a larger screen to review and save them.",
        ).assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("db-structure-save-btn").fetchSemanticsNodes().isEmpty())
        // Refresh would drop the held change, so a phone does not offer it
        assertTrue(composeRule.onAllNodesWithTag("db-structure-refresh-btn").fetchSemanticsNodes().isEmpty())

        // The tablet edits the same change, with no notice
        mobile = false
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("db-structure-editor").assertExists()
        composeRule.onNodeWithTag("db-structure-save-btn").assertExists()
        assertTrue(composeRule.onAllNodesWithTag("db-structure-phone-notice").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun phoneShell_opensConnectionStructureAndImportExportTabsInsteadOfATabletNotice() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PrefsStore(context).apply { put("onboarding.dismissed", "1") }
        val model = AppModel(context).apply { selectedProject = "ppm" }
        val connTab = WorkspaceTab(id = "db-connection:new", type = "db-connection", title = "New Connection", projectId = "ppm")
        val structureTab = WorkspaceTab(
            id = "db-structure:1:users",
            type = "db-structure",
            title = "users · Structure",
            projectId = "ppm",
            metadata = buildJsonObject {
                put("connectionId", 1)
                put("tableName", "users")
            },
        )
        val impexpTab = WorkspaceTab(id = "db-impexp:default", type = "db-impexp", title = "Export", projectId = "ppm")
        val nav = PhoneNav(initialTabId = connTab.id)

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneShell(model = model, nav = nav, prefs = prefs, overrideOpenTabs = listOf(connTab, structureTab, impexpTab))
            }
        }

        composeRule.onNodeWithTag("phone-database-screen").assertExists()
        composeRule.onNodeWithTag("db-connection-form").assertExists()
        assertTrue(composeRule.onAllNodesWithText("on a tablet", substring = true).fetchSemanticsNodes().isEmpty())

        composeRule.runOnIdle { nav.visitTab(structureTab.id) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("db-structure-phone-readonly").assertExists()

        composeRule.runOnIdle { nav.visitTab(impexpTab.id) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("db-impexp-phone-wider-screen").assertExists()
        assertTrue(composeRule.onAllNodesWithText("on a tablet", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun queryScriptRequest_sendsWriteOnceOnlyForTheReplayAndEvenWithAnEmptyPassword() {
        val plain = queryScriptRequestJson(QueryRunSpec(sql = "DELETE FROM t"), "run-1")
        assertNull(plain["writeOnce"])
        assertEquals("run-1", (plain["runId"] as kotlinx.serialization.json.JsonPrimitive).content)

        val withPassword = queryScriptRequestJson(QueryRunSpec(sql = "DELETE FROM t", writeOncePassword = "pw"), "run-2")
        assertEquals("""{"password":"pw"}""", withPassword["writeOnce"].toString())

        val noPassword = queryScriptRequestJson(QueryRunSpec(sql = "DELETE FROM t", writeOncePassword = ""), "run-3")
        assertEquals("""{"password":""}""", noPassword["writeOnce"].toString())
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
