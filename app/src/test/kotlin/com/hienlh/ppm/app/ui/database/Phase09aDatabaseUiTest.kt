package com.hienlh.ppm.app.ui.database

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.DbLoginCoordinator
import com.hienlh.ppm.client.DbLoginPromptInfo
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.database.ConnStatusState
import com.hienlh.ppm.state.database.ConnectionFormValues
import com.hienlh.ppm.state.database.DbObjectItem
import com.hienlh.ppm.state.database.DbObjectListState
import com.hienlh.ppm.state.database.DbRef
import com.hienlh.ppm.state.database.TreeConnection
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h1200dp-xxhdpi")
class Phase09aDatabaseUiTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    @Test
    fun databaseSidebarContent_displaysConnectionsObjectTreeExportWarningAndDriverInstall() {
        var installedDriver: String? = null
        var openedSqlFor: String? = null

        val conns = listOf(
            TreeConnection(
                id = 1,
                type = "postgres",
                name = "Payroll Prod",
                groupName = "Prod",
                readonly = 1,
                defaultDatabase = "payroll",
                singleDatabase = true,
            ),
            TreeConnection(
                id = 2,
                type = "mysql",
                name = "Analytics MySQL",
                groupName = null,
                readonly = 0,
                singleDatabase = false,
            ),
        )
        val objects = DbObjectListState(
            schemas = listOf("public"),
            objects = listOf(
                DbObjectItem(schema = "public", name = "employees", kind = "table", rowEstimate = 12_500L),
            ),
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                DatabaseSidebarContent(
                    connections = conns,
                    statusMap = mapOf(
                        1 to ConnStatusState.Open,
                        2 to ConnStatusState.Error("Driver mysql2 missing", missingDriverId = "mysql2"),
                    ),
                    databasesMap = emptyMap(),
                    collapsedFolders = emptySet(),
                    emptyFolders = emptyList(),
                    expandedConns = emptySet(),
                    currentDb = DbRef(1, "payroll"),
                    focusedDb = DbRef(2, "analytics"),
                    objectList = objects,
                    selectedSchema = "public",
                    openGroups = setOf("table"),
                    expandedObjects = emptySet(),
                    structuresMap = emptyMap(),
                    shownPerGroup = emptyMap(),
                    onInstallDriver = { installedDriver = it },
                    onOpenObjectSql = { openedSqlFor = it.name },
                )
            }
        }

        // Folder + connections + driver install button
        composeRule.onNodeWithText("Payroll Prod").assertIsDisplayed()
        composeRule.onNodeWithTag("db-install-driver-mysql2").performClick()
        assertEquals("mysql2", installedDriver)

        // Focused database differs from current -> Switch prompt shown
        composeRule.onNodeWithTag("db-focused-switch-prompt").assertIsDisplayed()

        // Object tree row with ~13k row estimate & SQL button
        composeRule.onNodeWithText("employees").assertIsDisplayed()
        composeRule.onNodeWithText("~13k").assertIsDisplayed()
        composeRule.onNodeWithTag("db-obj-sql-employees").performClick()
        assertEquals("employees", openedSqlFor)

        // Plaintext password warning when clicking Export
        composeRule.onNodeWithTag("db-export-btn").performClick()
        composeRule.onNodeWithTag("db-export-plaintext-warning").assertIsDisplayed()
    }

    @Test
    fun dbConnectionFormContent_switchesFieldsAndUrlAndSubmits() {
        var testedValues: ConnectionFormValues? = null

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                DbConnectionFormContent(
                    initialValues = ConnectionFormValues(
                        type = "postgres",
                        entry = "fields",
                        host = "db.internal",
                        port = "5432",
                        user = "admin",
                        database = "orders",
                    ),
                    onTestConnection = { testedValues = it },
                )
            }
        }

        // Switch to URL mode and verify built URL
        composeRule.onNodeWithTag("db-entry-url").performClick()
        composeRule.onNodeWithText("PostgreSQL · db.internal:5432 · user admin · database orders").assertIsDisplayed()

        composeRule.onNodeWithTag("db-form-test-btn").performClick()
        assertTrue(testedValues != null)
        assertEquals("postgres://admin@db.internal:5432/orders", testedValues!!.url)
    }

    @Test
    fun dbSqlObjectContent_switchesCreateSelectInsertScripts() {
        var queryOpened: String? = null

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                DbSqlObjectContent(
                    objectName = "users",
                    createSql = "CREATE TABLE users (id INT PRIMARY KEY);",
                    selectSql = "SELECT id FROM users LIMIT 100;",
                    insertSql = "INSERT INTO users (id) VALUES (1);",
                    onOpenInQuery = { queryOpened = it },
                )
            }
        }

        composeRule.onNodeWithText("CREATE TABLE users (id INT PRIMARY KEY);").assertIsDisplayed()
        composeRule.onNodeWithTag("db-sql-tab-select").performClick()
        composeRule.onNodeWithText("SELECT id FROM users LIMIT 100;").assertIsDisplayed()

        composeRule.onNodeWithTag("db-sql-open-query").performClick()
        assertEquals("SELECT id FROM users LIMIT 100;", queryOpened)
    }

    @Test
    fun dbLoginCoordinator_coalescesConcurrent428RequestsIntoSinglePrompt() = runBlocking {
        val promptCount = AtomicInteger(0)
        val coordinator = DbLoginCoordinator {
            promptCount.incrementAndGet()
            delay(50L)
            true
        }
        val prompt = DbLoginPromptInfo(
            connectionId = 7,
            name = "Staging PG",
            type = "postgres",
            user = "readonly",
            askUser = false,
        )

        val d1 = async { coordinator.awaitLogin(prompt) }
        val d2 = async { coordinator.awaitLogin(prompt) }
        val d3 = async { coordinator.awaitLogin(prompt) }

        assertTrue(d1.await())
        assertTrue(d2.await())
        assertTrue(d3.await())
        assertEquals(1, promptCount.get())
    }
}
