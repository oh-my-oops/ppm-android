package com.hienlh.ppm.app.ui.database

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.database.DbDependency
import com.hienlh.ppm.state.database.DbForeignKey
import com.hienlh.ppm.state.database.DbIndex
import com.hienlh.ppm.state.database.DbIndexKey
import com.hienlh.ppm.state.database.DbObjectItem
import com.hienlh.ppm.state.database.DbPrimaryKey
import com.hienlh.ppm.state.database.DbStructureColumn
import com.hienlh.ppm.state.database.DbStructureObjectRef
import com.hienlh.ppm.state.database.DbTableStructure
import com.hienlh.ppm.state.database.DbUniqueConstraint
import com.hienlh.ppm.state.database.ImpExpDatabase
import com.hienlh.ppm.state.database.ImpExpJobItemStatus
import com.hienlh.ppm.state.database.ImpExpJobStatus
import com.hienlh.ppm.state.database.ImpExpMessage
import com.hienlh.ppm.state.database.ImpExpOutputFile
import com.hienlh.ppm.state.database.StructurePreview
import com.hienlh.ppm.state.database.StructureStatement
import com.hienlh.ppm.state.database.TreeConnection
import com.hienlh.ppm.state.database.newImpExpForm
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
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
class Phase09fDatabaseStructureImpExpUiTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val sampleStructure = DbTableStructure(
        dialect = "mysql",
        database = "shop",
        schema = null,
        table = "employees",
        kind = "table",
        comment = "Staff directory",
        engine = "InnoDB",
        columns = listOf(
            DbStructureColumn(ordinal = 1, name = "id", type = "int unsigned", nullable = false, autoIncrement = true),
            DbStructureColumn(ordinal = 2, name = "email", type = "varchar(255)", nullable = false),
        ),
        primaryKey = DbPrimaryKey(name = "PRIMARY", columns = listOf("id")),
        indexes = listOf(
            DbIndex(
                name = "idx_email",
                unique = false,
                primary = false,
                method = "BTREE",
                keys = listOf(DbIndexKey(column = "email", order = "ASC")),
            ),
        ),
        uniques = listOf(
            DbUniqueConstraint(name = "uq_email", columns = listOf("email")),
        ),
        foreignKeys = listOf(
            DbForeignKey(
                name = "fk_emp_self",
                schema = null,
                table = "employees",
                columns = listOf("id"),
                referencedSchema = null,
                referencedTable = "employees",
                referencedColumns = listOf("id"),
                onUpdate = "CASCADE",
                onDelete = "RESTRICT",
            ),
        ),
        dependencies = listOf(
            DbDependency(
                direction = "usedBy",
                `object` = DbStructureObjectRef(schema = null, name = "active_employees_view", kind = "view"),
            ),
        ),
    )

    @Test
    fun tabletStructureEditor_supportsEditingColumnsConstraintsDdlPreviewRecreateGateConflictAndFailureReport() {
        var openedDataTable: String? = null
        var applyAttempt = 0
        var lastAppliedSql: String? = null

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                DatabaseStructureTabContent(
                    structure = sampleStructure,
                    dialect = "mysql",
                    dbType = "mysql",
                    schema = null,
                    database = "shop",
                    connectionName = "MySQL Prod",
                    isNewTable = false,
                    readonly = false,
                    onOpenDataTab = { tbl, _ -> openedDataTable = tbl },
                    onPreviewChange = {
                        StructurePreview(
                            dialect = "mysql",
                            transactionalDdl = false,
                            recreate = true,
                            statements = listOf(
                                StructureStatement("ALTER TABLE `employees` ADD COLUMN `salary` decimal(10,2)", recreatesTable = true),
                            ),
                            sql = "ALTER TABLE `employees` ADD COLUMN `salary` decimal(10,2);",
                            warnings = listOf("Recreates table employees"),
                        )
                    },
                    onApplyChange = { _, sql, _ ->
                        applyAttempt++
                        lastAppliedSql = sql
                        if (applyAttempt == 1) {
                            throw PpmError.Server(
                                status = 409,
                                serverMessage = "Structure changed since preview",
                                data = Json.parseToJsonElement(
                                    """
                                    {
                                      "sql": "ALTER TABLE `employees` COMMENT = 'Staff';\nALTER TABLE `employees` ADD COLUMN `salary` decimal(10,2) NOT NULL;\nCREATE INDEX `idx_sal` ON `employees`(`salary`);",
                                      "statements": [
                                        { "sql": "ALTER TABLE `employees` COMMENT = 'Staff'" },
                                        { "sql": "ALTER TABLE `employees` ADD COLUMN `salary` decimal(10,2) NOT NULL" },
                                        { "sql": "CREATE INDEX `idx_sal` ON `employees`(`salary`)" }
                                      ],
                                      "recreate": false,
                                      "warnings": [],
                                      "transactional": false
                                    }
                                    """.trimIndent(),
                                ),
                            )
                        } else {
                            throw PpmError.Server(
                                status = 400,
                                serverMessage = "Duplicate column name 'salary'",
                                data = Json.parseToJsonElement(
                                    """
                                    {
                                      "statement": "ALTER TABLE `employees` ADD COLUMN `salary` decimal(10,2) NOT NULL",
                                      "index": 1,
                                      "applied": 1,
                                      "total": 3
                                    }
                                    """.trimIndent(),
                                ),
                            )
                        }
                    },
                )
            }
        }

        // Verify sections & clickable FK link
        composeRule.onNodeWithTag("db-structure-editor").assertIsDisplayed()
        composeRule.onNodeWithTag("db-structure-copy-names-btn").performClick()
        composeRule.onNodeWithTag("db-structure-copy-banner").assertIsDisplayed()
        composeRule.onNodeWithTag("db-structure-fk-link-employees").performScrollTo().performClick()
        assertEquals("employees", openedDataTable)

        // Add column via dialog
        composeRule.onNodeWithTag("db-structure-add-col-btn").performScrollTo().performClick()
        composeRule.onNodeWithTag("db-structure-column-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("db-col-dialog-name-input").performTextReplacement("salary")
        composeRule.onNodeWithTag("db-col-dialog-type-chip-decimal(10,2)").performScrollTo().performClick()
        composeRule.onNodeWithTag("db-col-dialog-save-btn").performScrollTo().performClick()

        // Dirty badge & Save dialog
        composeRule.onNodeWithTag("db-structure-dirty-badge").assertIsDisplayed()
        composeRule.onNodeWithTag("db-structure-save-btn").performClick()
        composeRule.onNodeWithTag("db-structure-save-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("db-structure-mysql-nontx-note").assertIsDisplayed()
        composeRule.onNodeWithTag("db-structure-preview-warnings").assertIsDisplayed()

        // Check Allow recreate and execute (1st attempt -> 409 newerPreviewOf)
        composeRule.onNodeWithTag("db-structure-allow-recreate-checkbox").performClick()
        composeRule.onNodeWithTag("db-structure-save-execute-btn").performClick()
        composeRule.waitForIdle()
        assertEquals("ALTER TABLE `employees` ADD COLUMN `salary` decimal(10,2);", lastAppliedSql)
        composeRule.onNodeWithTag("db-structure-conflict-banner").assertIsDisplayed()

        // 2nd attempt -> sends updated SQL -> 400 statement failure report
        composeRule.onNodeWithTag("db-structure-save-execute-btn").performClick()
        composeRule.waitForIdle()
        assertEquals(
            "ALTER TABLE `employees` COMMENT = 'Staff';\nALTER TABLE `employees` ADD COLUMN `salary` decimal(10,2) NOT NULL;\nCREATE INDEX `idx_sal` ON `employees`(`salary`);",
            lastAppliedSql,
        )
        composeRule.onNodeWithTag("db-structure-failure-report").assertIsDisplayed()
        composeRule.onNodeWithTag("db-structure-stmt-outcome-0").assertIsDisplayed()
        composeRule.onNodeWithTag("db-structure-stmt-outcome-1").assertIsDisplayed()
        composeRule.onNodeWithTag("db-structure-stmt-outcome-2").assertIsDisplayed()
    }

    @Test
    fun phoneStructureAndImpExp_enforceMobileReadonlyLargerScreenAndWiderScreenGuards() {
        var isNewTable by mutableStateOf(false)
        var showImpExp by mutableStateOf(false)

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                if (showImpExp) {
                    DatabaseImpExpTabContent(
                        form = newImpExpForm(ImpExpDatabase(1, "shop", "public")),
                    )
                } else {
                    DatabaseStructureTabContent(
                        structure = sampleStructure,
                        dialect = "mysql",
                        dbType = "mysql",
                        isNewTable = isNewTable,
                    )
                }
            }
        }

        // 1. Existing table on phone -> read-only structure view
        composeRule.onNodeWithTag("db-structure-phone-readonly").assertIsDisplayed()

        // 2. New table on phone -> "Open on a larger screen"
        isNewTable = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("db-structure-phone-larger-screen").assertIsDisplayed()

        // 3. ImpExp on phone -> "Needs a wider screen"
        showImpExp = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("db-impexp-phone-wider-screen").assertIsDisplayed()
    }

    @Test
    fun tabletImpExpWizardAndSqliteViewer_supportTableSelectionColumnMapPreviewJobMessagesAndSqliteModes() {
        var form by mutableStateOf(newImpExpForm(ImpExpDatabase(1, "analytics", "public")))
        var jobStatus by mutableStateOf<ImpExpJobStatus?>(null)
        var downloadedFile: String? = null
        var showSqlite by mutableStateOf(false)
        var mobileMode by mutableStateOf(false)
        var selectedSqliteTable by mutableStateOf<String?>("users")
        var openedQueryCount = 0

        val objects = listOf(
            DbObjectItem(schema = "public", name = "users", kind = "table", rowEstimate = 120L),
            DbObjectItem(schema = "public", name = "orders", kind = "table", rowEstimate = 4500L),
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobileMode) {
                if (!showSqlite) {
                    DatabaseImpExpTabContent(
                        form = form,
                        connections = listOf(
                            TreeConnection(id = 1, name = "Analytics PG", type = "postgres", readonly = 0),
                        ),
                        availableObjects = objects,
                        jobStatus = jobStatus,
                        onFormChange = { form = it },
                        onRunJob = {
                            jobStatus = ImpExpJobStatus(
                                id = "job-42",
                                kind = "export",
                                state = "done",
                                startedAt = 1000L,
                                finishedAt = 2200L,
                                items = listOf(
                                    ImpExpJobItemStatus(
                                        source = "users",
                                        target = "users.csv",
                                        state = "done",
                                        rowsProcessed = 120L,
                                    ),
                                ),
                                messages = listOf(
                                    ImpExpMessage(id = 1, at = 1500L, level = "info", item = "users", text = "Exported 120 rows"),
                                ),
                                files = listOf(
                                    ImpExpOutputFile(name = "users.csv", size = 4096L, mimeType = "text/csv"),
                                ),
                            )
                        },
                        onDownloadOutputFile = { downloadedFile = it.name },
                    )
                } else {
                    SqliteViewerTabContent(
                        filePath = "data/app.sqlite",
                        project = "demo",
                        objects = objects,
                        selectedTable = selectedSqliteTable,
                        onSelectTable = { selectedSqliteTable = it },
                        onNewQuery = { openedQueryCount++ },
                    )
                }
            }
        }

        // ImpExp Wizard on Tablet
        composeRule.onNodeWithTag("db-impexp-wizard").assertIsDisplayed()
        composeRule.onNodeWithTag("db-impexp-add-all-tables-btn").performClick()

        // Configure columns on 'users'
        composeRule.onNodeWithTag("db-impexp-row-cols-btn-users").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("db-impexp-columns-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("db-impexp-col-map-check-name").performClick()
        composeRule.onNodeWithTag("db-impexp-col-map-confirm-btn").performClick()

        // Preview 100 rows dialog
        composeRule.onNodeWithTag("db-impexp-row-preview-btn-users").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("db-impexp-preview-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("db-impexp-preview-close-btn").performClick()

        // Run export job & download output file
        composeRule.onNodeWithTag("db-impexp-run-btn").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("db-impexp-messages-panel").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("db-impexp-download-file-users.csv").performClick()
        assertEquals("users.csv", downloadedFile)

        // Switch to SQLite Viewer on Tablet
        showSqlite = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sqlite-viewer-tablet").assertIsDisplayed()
        composeRule.onNodeWithTag("sqlite-viewer-sidebar").assertIsDisplayed()
        composeRule.onNodeWithTag("sqlite-table-item-orders").performClick()
        assertEquals("orders", selectedSqliteTable)
        composeRule.onNodeWithTag("sqlite-new-query-btn").performClick()
        assertTrue(openedQueryCount > 0)

        // Switch SQLite Viewer to Phone
        mobileMode = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sqlite-viewer-phone").assertIsDisplayed()
        composeRule.onNodeWithTag("sqlite-phone-table-picker-btn").performClick()
        composeRule.onNodeWithTag("sqlite-table-item-users").performClick()
        assertEquals("users", selectedSqliteTable)
    }

    @Test
    fun structureTabEditPersistenceAndTabCloseConfirmDialog_preserveTableEditAndConfirmClose() {
        var persistedEdit by mutableStateOf<com.hienlh.ppm.state.database.TableEdit?>(null)
        var remountGeneration by mutableStateOf(0)
        var closeConfirmedCount = 0
        var closeCancelledCount = 0
        var losingTabs by mutableStateOf<List<com.hienlh.ppm.state.workspace.WorkspaceTab>>(emptyList())

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                androidx.compose.runtime.key(remountGeneration) {
                    DatabaseStructureTabContent(
                        structure = sampleStructure,
                        dialect = "mysql",
                        dbType = "mysql",
                        schema = "shop",
                        initialTableEdit = persistedEdit,
                        onTableEditChanged = { persistedEdit = it },
                    )
                }
                DbTabCloseConfirmDialogContent(
                    losingTabs = losingTabs,
                    onCancel = { closeCancelledCount++ },
                    onConfirm = { closeConfirmedCount++ },
                )
            }
        }

        // 1. Add a column -> persistedEdit is updated with dirty TableEdit
        composeRule.onNodeWithTag("db-structure-add-col-btn").performScrollTo().performClick()
        composeRule.onNodeWithTag("db-col-dialog-name-input").performTextReplacement("bonus")
        composeRule.onNodeWithTag("db-col-dialog-type-chip-decimal(10,2)").performScrollTo().performClick()
        composeRule.onNodeWithTag("db-col-dialog-save-btn").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(persistedEdit != null)
        assertTrue(persistedEdit!!.current.columns.any { it.name == "bonus" })

        // 2. Simulate tab switch unmount/remount -> restored from initialTableEdit
        remountGeneration = 1
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("db-structure-dirty-badge").assertIsDisplayed()

        // 3. Verify Confirm close tabs dialog (single tab & multiple tabs)
        losingTabs = listOf(
            com.hienlh.ppm.state.workspace.WorkspaceTab(
                id = "db-structure:1::public:employees",
                type = "db-structure",
                title = "employees · Structure",
            ),
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("db-tab-close-confirm-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("db-tab-close-confirm-accept").performClick()
        assertEquals(1, closeConfirmedCount)

        losingTabs = listOf(
            com.hienlh.ppm.state.workspace.WorkspaceTab(id = "t1", type = "db-query", title = "Query 1"),
            com.hienlh.ppm.state.workspace.WorkspaceTab(id = "t2", type = "database", title = "orders"),
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("db-tab-close-confirm-cancel").performClick()
        assertEquals(1, closeCancelledCount)
    }
}
