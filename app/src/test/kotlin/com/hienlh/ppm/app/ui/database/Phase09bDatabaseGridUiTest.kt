package com.hienlh.ppm.app.ui.database

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.database.DbCellValue
import com.hienlh.ppm.state.database.DbGridPageData
import com.hienlh.ppm.state.database.GridColumnSchema
import com.hienlh.ppm.state.database.ResultColumnMeta
import com.hienlh.ppm.state.database.RowCountView
import com.hienlh.ppm.state.database.RowTotal
import com.hienlh.ppm.state.database.SortMenuAction
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
class Phase09bDatabaseGridUiTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val samplePage = DbGridPageData(
        rawColumns = listOf(
            ResultColumnMeta("id", "bigint"),
            ResultColumnMeta("name", "text"),
            ResultColumnMeta("amount", "numeric(10,2)"),
        ),
        columnKeys = listOf("id", "name", "amount"),
        rows = listOf(
            listOf(
                DbCellValue.Num("1", 1.0),
                DbCellValue.Text("Alice"),
                DbCellValue.Num("100.25", 100.25),
            ),
            listOf(
                DbCellValue.Num("2", 2.0),
                DbCellValue.Null,
                DbCellValue.Num("50.25", 50.25),
            ),
        ),
        hasMore = true,
        sql = "SELECT * FROM \"invoices\"",
        rowKey = listOf("id"),
        countKey = "invoices:public",
    )

    private val sampleSchema = listOf(
        GridColumnSchema(name = "id", type = "bigint", nullable = false, pk = true, autoIncrement = true),
        GridColumnSchema(name = "name", type = "text", nullable = true),
        GridColumnSchema(name = "amount", type = "numeric(10,2)", nullable = false, fkTable = "ledger", fkColumn = "id"),
    )

    @Test
    fun tabletDatabaseGrid_supportsSortSelectionStatsColumnsPanelFetchAllAndExport() {
        var sortedCol: String? = null
        var sortedAction: SortMenuAction? = null
        var exactCountRequested = false
        var fetchAllCalled = false
        var exportedFormat: String? = null
        var exportedCols: List<String> = emptyList()

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
                        text = "Rows: Many",
                        counting = false,
                        canCountExactly = true,
                        total = RowTotal.Many,
                    ),
                    onSortAction = { col, act ->
                        sortedCol = col
                        sortedAction = act
                    },
                    onCountExactly = { exactCountRequested = true },
                    onFetchAll = { fetchAllCalled = true },
                    onStartQuickExport = { fmt, cols ->
                        exportedFormat = fmt
                        exportedCols = cols
                    },
                )
            }
        }

        // 1. Row count badge "Rows: Many" triggers exact count when clicked
        composeRule.onNodeWithTag("db-row-count-badge").assertIsDisplayed()
        composeRule.onNodeWithTag("db-row-count-badge").performClick()
        assertTrue(exactCountRequested)

        // 2. Column sort menu via ⌄ button
        composeRule.onNodeWithTag("grid-col-menu-btn-id").performClick()
        composeRule.onNodeWithTag("grid-sort-desc-id").performClick()
        assertEquals("id", sortedCol)
        assertEquals(SortMenuAction.SORT_DESC, sortedAction)

        // 3. Select all via top-left corner '#' -> shows Rows / Count / Sum selection stats pill
        composeRule.onNodeWithTag("grid-corner-select-all").performClick()
        composeRule.onNodeWithTag("db-selection-stats-pill").assertIsDisplayed()
        composeRule.onNodeWithText("Rows: 2 · Count: 6 · Sum: 153.5").assertIsDisplayed()

        // 4. Columns visibility panel: hide 'amount', then Show all
        composeRule.onNodeWithTag("db-columns-panel-btn").performClick()
        composeRule.onNodeWithTag("db-columns-side-panel").assertIsDisplayed()
        composeRule.onNodeWithTag("db-col-toggle-amount").performClick()
        composeRule.onNodeWithTag("db-columns-show-all").performClick()

        // 5. Fetch all confirmation flow
        composeRule.onNodeWithTag("db-fetch-all-btn").performClick()
        composeRule.onNodeWithTag("db-fetch-all-confirm-banner").assertIsDisplayed()
        composeRule.onNodeWithTag("db-fetch-all-confirm-btn").performClick()
        assertTrue(fetchAllCalled)

        // 6. Quick export (SAF first before ticket)
        composeRule.onNodeWithTag("db-export-menu-btn").performClick()
        composeRule.onNodeWithTag("db-export-format-csvExcel").performClick()
        assertEquals("csvExcel", exportedFormat)
        assertEquals(listOf("id", "name", "amount"), exportedCols)
    }

    @Test
    fun phoneDatabaseGrid_renders52dpHeaderOverflowMenuAndBottomBar() {
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
                )
            }
        }

        composeRule.onNodeWithTag("db-table-phone-header").assertIsDisplayed()
        composeRule.onNodeWithText("invoices").assertIsDisplayed()
        composeRule.onNodeWithTag("db-phone-bottom-bar").assertIsDisplayed()
        composeRule.onNodeWithTag("db-phone-new-row-btn").assertIsDisplayed()
        composeRule.onNodeWithTag("db-phone-filters-btn").assertIsDisplayed()
        composeRule.onNodeWithTag("db-phone-save-btn").assertIsDisplayed()

        composeRule.onNodeWithTag("db-phone-overflow-btn").performClick()
        composeRule.onNodeWithTag("db-phone-overflow-menu").assertIsDisplayed()
    }
}
