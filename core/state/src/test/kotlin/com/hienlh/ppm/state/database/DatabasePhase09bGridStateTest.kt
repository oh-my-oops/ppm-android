package com.hienlh.ppm.state.database

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabasePhase09bGridStateTest {

    @Test
    fun uniqueColumnKeysAndCellDisplay_preserveRepeatedColumnsBigIntNaNBinaryAndJsonTypes() {
        val keys = uniqueColumnKeys(listOf("id", "name", "id", "id (2)", "id"))
        assertEquals(listOf("id", "name", "id (3)", "id (2)", "id (4)"), keys)

        val responseJson = buildJsonObject {
            put(
                "columns",
                buildJsonArray {
                    add(buildJsonObject { put("name", "id"); put("type", "bigint") })
                    add(buildJsonObject { put("name", "id"); put("type", "integer") })
                    add(buildJsonObject { put("name", "score"); put("type", "double precision") })
                    add(buildJsonObject { put("name", "payload"); put("type", "bytea") })
                    add(buildJsonObject { put("name", "strFive"); put("type", "jsonb") })
                    add(buildJsonObject { put("name", "numFive"); put("type", "jsonb") })
                    add(buildJsonObject { put("name", "nullable"); put("type", "text") })
                },
            )
            put(
                "rows",
                buildJsonArray {
                    add(
                        buildJsonArray {
                            // bigint 2^63 - 1 sent as string
                            add(JsonPrimitive("9223372036854775807"))
                            add(JsonPrimitive(42))
                            // NaN sent as string
                            add(JsonPrimitive("NaN"))
                            // PNG header 89 50 4E 47 0D 0A 1A 0A + extra bytes (12 bytes total)
                            add(
                                buildJsonObject {
                                    put("\$binary", "iVBORw0KGgoAAAANSUhEUg==")
                                    put("size", 12390L)
                                    put("truncated", false)
                                },
                            )
                            // JSON string "5" vs number 5
                            add(JsonPrimitive("5"))
                            add(JsonPrimitive(5))
                            add(JsonNull)
                        },
                    )
                },
            )
            put("hasMore", true)
            put("sql", "SELECT * FROM \"items\"")
            put("rowKey", buildJsonArray { add(JsonPrimitive("id")) })
        }

        val page = parseGridResponse(responseJson, countKey = "k1")
        assertEquals(listOf("id", "id (2)", "score", "payload", "strFive", "numFive", "nullable"), page.columnKeys)
        assertEquals(1, page.rows.size)

        assertEquals(DbCellValue.Text("9223372036854775807"), page.cellAt(0, 0))
        assertEquals(DbCellValue.Num("42", 42.0), page.cellAt(1, 0))
        assertEquals(DbCellValue.Text("NaN"), page.cellAt(2, 0))
        assertEquals("12.1 KB · 89 50 4E 47 0D 0A 1A 0A…", formatCellDisplayText(page.cellAt(3, 0)))
        assertEquals(DbCellValue.Text("5"), page.cellAt(4, 0))
        assertEquals(DbCellValue.Num("5", 5.0), page.cellAt(5, 0))
        assertEquals("(NULL)", formatCellDisplayText(page.cellAt(6, 0)))
        assertEquals("(No Field)", formatCellDisplayText(DbCellValue.NoField))
    }

    @Test
    fun selectionStatsAndSelectedBlock_deduplicateOverlapsAndRoundFloatingPointNoise() {
        // 3 columns x 3 rows
        val grid = listOf(
            listOf(DbCellValue.Num("0.1", 0.1), DbCellValue.Num("0.2", 0.2), DbCellValue.Text("hello")),
            listOf(DbCellValue.Text("10"), DbCellValue.Null, DbCellValue.Num("5.5", 5.5)),
            listOf(DbCellValue.Num("100", 100.0), DbCellValue.Text("world"), DbCellValue.Num("200", 200.0)),
        )

        // Single cell -> null stats
        val singleSel = GridSelectionState(activeCell = 0 to 0)
        assertNull(computeSelectionStats(singleSel, 3, 3) { c, r -> grid[r][c] })

        // Overlapping range (0,0)-(2x2) + whole row 0 -> 5 unique cells across 2 rows
        val multiSel = GridSelectionState(
            ranges = listOf(GridRect(x = 0, y = 0, width = 2, height = 2)),
            selectedRows = setOf(0),
        )
        val stats = computeSelectionStats(multiSel, 3, 3) { c, r -> grid[r][c] }!!
        assertEquals(2, stats.rows)
        assertEquals(5, stats.count)
        // 0.1 + 0.2 + 10 = 10.3 (rounded cleanly without 10.300000000000001)
        assertEquals(10.3, stats.sum!!, 0.0)

        val block = selectedBlock(multiSel, 3, 3)
        assertEquals(listOf(0, 1), block.rows)
        assertEquals(listOf(0, 1, 2), block.columns)
        assertEquals(listOf(0, 1), selectedRowIndices(multiSel, 3))
    }

    @Test
    fun copyAsAndSqlLiterals_matchWebFormatsAcrossDialects() {
        val cols = listOf("id", "name", "payload", "tags")
        val row0 = linkedMapOf<String, DbCellValue>(
            "id" to DbCellValue.Text("9223372036854775807"),
            "name" to DbCellValue.Text("O'Reilly\nBooks"),
            "payload" to DbCellValue.Binary(base64 = "CAFE", size = 3, truncated = false),
            "tags" to DbCellValue.JsonArr(
                elements = listOf(DbCellValue.Text("a"), DbCellValue.Null, DbCellValue.Text("b")),
                rawJson = "[\"a\",null,\"b\"]",
            ),
        )
        val target = CopySqlTarget(
            table = "books",
            schema = "public",
            dialect = "postgres",
            keyColumns = listOf("id"),
            kinds = mapOf(
                "id" to ColumnKind.NUMBER,
                "name" to ColumnKind.TEXT,
                "payload" to ColumnKind.BINARY,
                "tags" to ColumnKind.OTHER,
            ),
        )

        // 1. Single cell copy without headers is never quoted
        val singleCopy = formatCopy(
            format = CopyFormat.TEXT_WITHOUT_HEADERS,
            columns = listOf("name"),
            rows = listOf(row0),
            target = target,
        )
        assertEquals("O'Reilly\nBooks", singleCopy)

        // 2. Multi-column tab copy quotes multiline cell spreadsheet-style
        val tabCopy = formatCopy(
            format = CopyFormat.TEXT_WITH_HEADERS,
            columns = listOf("id", "name"),
            rows = listOf(row0),
            target = target,
        )
        assertEquals("id\tname\r\n9223372036854775807\t\"O'Reilly\nBooks\"", tabCopy)

        // 3. SQL INSERTs on Postgres: bigint on NUMBER column is bare, bytea is '\x...', array is '{"a",NULL,"b"}'
        val insertSql = formatCopy(
            format = CopyFormat.INSERTS,
            columns = cols,
            rows = listOf(row0),
            target = target,
        )
        assertEquals(
            "INSERT INTO \"public\".\"books\" (\"id\", \"name\", \"payload\", \"tags\") VALUES (9223372036854775807, 'O''Reilly\nBooks', '\\x080144', '{\"a\",NULL,\"b\"}');",
            insertSql,
        )

        // 4. SQL UPDATEs with truncated binary produces comment so partial bytes are never written
        val rowTrunc = LinkedHashMap(row0).apply {
            put("payload", DbCellValue.Binary(base64 = "CAFE", size = 1048576L, truncated = true))
        }
        val updateSql = formatCopy(
            format = CopyFormat.UPDATES,
            columns = listOf("payload"),
            rows = listOf(rowTrunc),
            target = target,
        )
        assertEquals(
            "UPDATE \"public\".\"books\" SET \"payload\"=/* 1048576 bytes, not loaded */ WHERE \"id\"=9223372036854775807;",
            updateSql,
        )

        // 5. YAML dump
        val yaml = formatCopy(
            format = CopyFormat.YAML,
            columns = listOf("id", "name"),
            rows = listOf(row0),
            target = target,
        )
        assertTrue(yaml.contains("- id: '9223372036854775807'"))
        assertTrue(yaml.contains("  name: 'O''Reilly\nBooks'"))
    }

    @Test
    fun loadMoreRowCountAndExportFileName_handleAllStates() {
        assertEquals(
            LoadMoreDecision.IDLE,
            loadMoreDecision(lastVisibleRow = 50, loadedRows = 100, hasMore = true, busy = false, newRows = 0),
        )
        assertEquals(
            LoadMoreDecision.LOAD,
            loadMoreDecision(lastVisibleRow = 99, loadedRows = 100, hasMore = true, busy = false, newRows = 0),
        )
        assertEquals(
            LoadMoreDecision.BLOCKED_BY_NEW_ROWS,
            loadMoreDecision(lastVisibleRow = 99, loadedRows = 100, hasMore = true, busy = false, newRows = 2),
        )

        val page = DbGridPageData(
            rawColumns = listOf(ResultColumnMeta("id", "int4")),
            columnKeys = listOf("id"),
            rows = List(100) { listOf(DbCellValue.Num(it.toString(), it.toDouble())) },
            hasMore = true,
            countKey = "ck",
        )
        val timedOutCount = computeRowCountView(page, RowCountState(key = "ck", timedOut = true))!!
        assertEquals("Rows: Many", timedOutCount.text)
        assertTrue(timedOutCount.canCountExactly)

        val estimateCount = computeRowCountView(page, RowCountState(key = "ck", estimate = 250_000L, pending = true))!!
        assertEquals("Rows: ~250,000", estimateCount.text)
        assertTrue(estimateCount.counting)
        assertFalse(estimateCount.canCountExactly)

        assertEquals(100, reloadKeepLoadedLimit(20))
        assertEquals(500, reloadKeepLoadedLimit(500))
        assertEquals(10_000, reloadKeepLoadedLimit(50_000))
        assertEquals("sales_2026_q1.csv", gridExportFileName("sales/2026:q1", "csvExcel"))
    }
}
