package com.hienlh.ppm.app.ui.database

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.database.ColumnKind
import com.hienlh.ppm.state.database.DbCellValue
import com.hienlh.ppm.state.database.DbGridPageData
import com.hienlh.ppm.state.database.FilterText
import com.hienlh.ppm.state.database.FilterableColumn
import com.hienlh.ppm.state.database.GridColumnSchema
import com.hienlh.ppm.state.database.GridFilters
import com.hienlh.ppm.state.database.ResultColumnMeta
import com.hienlh.ppm.state.database.RowCountView
import com.hienlh.ppm.state.database.RowTotal
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1024dp-h768dp")
class Phase09cDatabaseFilterUiTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val samplePage = DbGridPageData(
        rawColumns = listOf(
            ResultColumnMeta("id", "bigint"),
            ResultColumnMeta("name", "text"),
            ResultColumnMeta("ledger_id", "bigint"),
        ),
        columnKeys = listOf("id", "name", "ledger_id"),
        rows = listOf(
            listOf(
                DbCellValue.Num("1", 1.0),
                DbCellValue.Text("Alice"),
                DbCellValue.Num("10", 10.0),
            ),
            listOf(
                DbCellValue.Num("2", 2.0),
                DbCellValue.Text("Bob"),
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
        GridColumnSchema(name = "ledger_id", type = "bigint", nullable = false, fkTable = "ledgers", fkColumn = "id"),
    )

    @Test
    fun tabletFilterRow_supportsFunnelSetFilterChooseValuesFkLookupAndFiltersSidePanel() {
        var latestFilters = GridFilters()

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
                    onFiltersChanged = { latestFilters = it },
                    onLoadValues = { _, _, _ ->
                        LookupValueListResult(
                            values = listOf(
                                DbCellValue.Text("Alice"),
                                DbCellValue.Text("Bob"),
                                DbCellValue.Null,
                            ),
                            hasMore = false,
                        )
                    },
                    onLoadLookup = { _, _, _, _, _ ->
                        LookupTableRowsResult(
                            columns = listOf(
                                FilterableColumn("id", ColumnKind.NUMBER),
                                FilterableColumn("title", ColumnKind.TEXT),
                            ),
                            rows = listOf(
                                mapOf(
                                    "id" to DbCellValue.Num("10", 10.0),
                                    "title" to DbCellValue.Text("Operating Account"),
                                ),
                                mapOf(
                                    "id" to DbCellValue.Num("20", 20.0),
                                    "title" to DbCellValue.Text("Payroll Reserve"),
                                ),
                            ),
                            hasMore = false,
                        )
                    },
                )
            }
        }

        // 1. Tablet filter row is displayed directly under column titles
        composeRule.onNodeWithTag("db-tablet-filter-row").assertIsDisplayed()

        // 2. Open funnel menu on 'name' column -> click "Equals..." -> Set Filter Dialog -> enter "Alice" -> OK
        composeRule.onNodeWithTag("db-filter-funnel-btn-name").performClick()
        composeRule.onNodeWithTag("db-funnel-menu-name").assertIsDisplayed()
        composeRule.onNodeWithTag("db-funnel-item-name-Equals...").performClick()
        composeRule.onNodeWithTag("db-set-filter-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("db-set-filter-first-value").performTextReplacement("Alice")
        composeRule.onNodeWithTag("db-set-filter-ok-btn").performClick()
        assertEquals("=\"Alice\"", latestFilters.columns["name"]?.text)

        // 3. Clear 'name' filter with × button, then open ⋮ Choose value dialog and pick "Bob" + NULL
        composeRule.onNodeWithTag("db-filter-clear-name").performClick()
        assertEquals(null, latestFilters.columns["name"])
        composeRule.onNodeWithTag("db-filter-choose-btn-name").performClick()
        composeRule.onNodeWithTag("db-choose-value-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("db-choose-value-row-Bob").performClick()
        composeRule.onNodeWithTag("db-choose-value-row-NULL").performClick()
        composeRule.onNodeWithTag("db-choose-value-ok-btn").performClick()
        assertEquals("=\"Bob\",NULL", latestFilters.columns["name"]?.text)

        // 4. Open ⋯ FK Dictionary Lookup on 'ledger_id' -> pick row 10 ("Operating Account") -> OK
        composeRule.onNodeWithTag("db-filter-lookup-btn-ledger_id").performClick()
        composeRule.onNodeWithTag("db-fk-lookup-dialog").assertIsDisplayed()
        composeRule.onNodeWithText("Operating Account").assertIsDisplayed()
        composeRule.onNodeWithTag("db-fk-lookup-row-10").performClick()
        composeRule.onNodeWithTag("db-fk-lookup-ok-btn").performClick()
        assertEquals("=\"10\"", latestFilters.columns["ledger_id"]?.text)

        // 5. Open Filters side panel -> toggle 'name' filter off -> Clear all
        composeRule.onNodeWithTag("db-filters-panel-btn").performClick()
        composeRule.onNodeWithTag("db-filters-side-panel").assertIsDisplayed()
        composeRule.onNodeWithTag("db-filter-panel-toggle-name").performClick()
        assertEquals(true, latestFilters.columns["name"]?.off)
        composeRule.onNodeWithTag("db-filters-clear-all-btn").performClick()
        assertEquals(emptyMap<String, FilterText>(), latestFilters.columns)
    }

    @Test
    fun phoneFilterChipsAndBottomSheets_supportColumnFilterSheetAndUnifiedColumnsAndFiltersSheet() {
        var latestFilters = GridFilters(
            columns = mapOf("id" to FilterText(">=10")),
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                DatabaseTableTabContent(
                    tableName = "invoices",
                    schemaName = "public",
                    connectionName = "Payroll Prod",
                    dbType = "postgres",
                    pageData = samplePage,
                    schemaList = sampleSchema,
                    initialFilters = latestFilters,
                    rowCountView = RowCountView(
                        text = "Rows: 2",
                        counting = false,
                        canCountExactly = false,
                        total = RowTotal.Exact(2),
                    ),
                    onFiltersChanged = { latestFilters = it },
                )
            }
        }

        // 1. Phone filter chips bar shows active chip for 'id'
        composeRule.onNodeWithTag("db-phone-filter-chips").assertIsDisplayed()
        composeRule.onNodeWithTag("db-phone-filter-chip-id").assertIsDisplayed()

        // 2. Tap column header '⌄' on 'name' -> opens PhoneColumnFilterSheet -> enter "Alice" -> Apply
        composeRule.onNodeWithTag("grid-col-menu-btn-name").performClick()
        composeRule.onNodeWithTag("db-phone-column-filter-sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("db-phone-filter-sheet-input").performTextReplacement("Alice")
        composeRule.onNodeWithText("Understood · Apply filters the rows").assertIsDisplayed()
        composeRule.onNodeWithTag("db-phone-filter-apply-btn").performClick()
        assertEquals("Alice", latestFilters.columns["name"]?.text)

        // 3. Tap bottom bar 'Filters' button -> opens unified Columns and filters sheet
        composeRule.onNodeWithTag("db-phone-filters-btn").performClick()
        composeRule.onNodeWithTag("db-phone-columns-and-filters-sheet").assertIsDisplayed()
        composeRule.onNodeWithText("Columns and filters").assertIsDisplayed()
        composeRule.onNodeWithTag("db-phone-cols-filters-done-btn").performClick()
    }
}
