package com.hienlh.ppm.state.database

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class DatabasePhase09cFilterStateTest {

    private fun columnKindFromTs(name: String): ColumnKind =
        ColumnKind.valueOf(name.uppercase(Locale.US))

    private fun parseResultToJson(res: FilterParseResult): JsonObject = when (res) {
        is FilterParseResult.Ok -> buildJsonObject {
            put("ok", true)
            put(
                "anyOf",
                buildJsonArray {
                    for (andGroup in res.anyOf) {
                        add(
                            buildJsonArray {
                                for (cond in andGroup) {
                                    add(cond.toJsonObject())
                                }
                            },
                        )
                    }
                },
            )
        }
        is FilterParseResult.Err -> buildJsonObject {
            put("ok", false)
            put(
                "error",
                buildJsonObject {
                    put("message", res.error.message)
                    put("start", res.error.start)
                    put("end", res.error.end)
                },
            )
        }
    }

    private fun anyColumnResultToJson(res: AnyColumnParseResult): JsonObject = when (res) {
        is AnyColumnParseResult.Ok -> buildJsonObject {
            put("ok", true)
            put(
                "groups",
                buildJsonArray {
                    for (group in res.groups) {
                        add(group.toJsonObject())
                    }
                },
            )
        }
        is AnyColumnParseResult.Err -> buildJsonObject {
            put("ok", false)
            put(
                "error",
                buildJsonObject {
                    put("message", res.error.message)
                    put("start", res.error.start)
                    put("end", res.error.end)
                },
            )
        }
    }

    @Test
    fun goldenVectorsFromTypeScriptParserMatch100Percent() {
        val stream = javaClass.classLoader?.getResourceAsStream("db-filter-golden.json")
        assertNotNull("db-filter-golden.json must exist in test resources", stream)
        val raw = stream!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val vectors = Json.parseToJsonElement(raw).jsonArray
        assertTrue("Expected at least 200 golden vectors", vectors.size >= 200)

        for ((idx, itemEl) in vectors.withIndex()) {
            val item = itemEl.jsonObject
            val type = item["type"]!!.jsonPrimitive.content
            val text = item["text"]!!.jsonPrimitive.content
            val zone = ZoneId.of(item["zone"]!!.jsonPrimitive.content)
            val now = Instant.parse(item["nowIso"]!!.jsonPrimitive.content)
            val expected = item["expected"]!!.jsonObject

            val actual = if (type == "column") {
                val kind = columnKindFromTs(item["kind"]!!.jsonPrimitive.content)
                parseResultToJson(parseFilter(text, kind, now, zone))
            } else {
                val cols = item["columns"]!!.jsonArray.map { colEl ->
                    val colObj = colEl.jsonObject
                    FilterableColumn(
                        name = colObj["name"]!!.jsonPrimitive.content,
                        kind = columnKindFromTs(colObj["kind"]!!.jsonPrimitive.content),
                    )
                }
                anyColumnResultToJson(parseAnyColumnFilter(text, cols, now, zone))
            }

            assertEquals(
                "Mismatch at golden vector #$idx ($type, text=${Json.encodeToString(kotlinx.serialization.serializer(), text)}, zone=$zone)",
                expected,
                actual,
            )
        }
    }

    @Test
    fun americaNewYorkAndAsiaHoChiMinhDstWeekSpansMatchExpectedConditions() {
        val nyZone = ZoneId.of("America/New_York")
        val hcmcZone = ZoneId.of("Asia/Ho_Chi_Minh")

        // Week of US spring-forward DST change (Sunday 2024-03-10):
        // Monday 2024-03-04 is -05:00, Monday 2024-03-11 is -04:00 -> splits into 2 dateRange conditions
        val nySpringDstNow = Instant.parse("2024-03-07T15:00:00Z")
        val nyWeekRes = parseFilter("THIS WEEK", ColumnKind.DATETIMETZ, nySpringDstNow, nyZone)
        assertTrue(nyWeekRes is FilterParseResult.Ok)
        val nyAnyOf = (nyWeekRes as FilterParseResult.Ok).anyOf
        assertEquals(
            listOf(
                listOf(
                    FilterCondition(op = "dateRange", from = "2024-03-04 00:00:00", offset = "-05:00"),
                    FilterCondition(op = "dateRange", to = "2024-03-11 00:00:00", offset = "-04:00"),
                ),
            ),
            nyAnyOf,
        )

        // Same instant in Asia/Ho_Chi_Minh (no DST, steady +07:00) -> single dateRange condition
        val hcmcWeekRes = parseFilter("THIS WEEK", ColumnKind.DATETIMETZ, nySpringDstNow, hcmcZone)
        assertTrue(hcmcWeekRes is FilterParseResult.Ok)
        val hcmcAnyOf = (hcmcWeekRes as FilterParseResult.Ok).anyOf
        assertEquals(
            listOf(
                listOf(
                    FilterCondition(
                        op = "dateRange",
                        from = "2024-03-04 00:00:00",
                        to = "2024-03-11 00:00:00",
                        offset = "+07:00",
                    ),
                ),
            ),
            hcmcAnyOf,
        )
    }

    @Test
    fun pastingFiftyLinesBuildsSingleInConditionAndPreservesTabFilters() {
        val pasted50 = (1..50).joinToString("\n") { "item_$it" }
        val filterText = linesFilter(LinesMode.IS, pasted50)
        val parsed = parseFilter(filterText, ColumnKind.TEXT)
        assertTrue(parsed is FilterParseResult.Ok)
        val anyOf = (parsed as FilterParseResult.Ok).anyOf
        assertEquals(1, anyOf.size)
        assertEquals(1, anyOf[0].size)
        assertEquals("in", anyOf[0][0].op)
        assertEquals(50, anyOf[0][0].values?.size)
        assertEquals(FilterValue.Str("item_1"), anyOf[0][0].values?.first())
        assertEquals(FilterValue.Str("item_50"), anyOf[0][0].values?.last())

        // Tab metadata persistence round-trip + toggling off
        var filters = NO_FILTERS
        filters = withColumnFilter(filters, "status", filterText)
        filters = withMultiFilter(filters, "alice")
        filters = withFilterOff(filters, "status", off = true)

        val meta = withTabFilters(mapOf("table" to "orders"), filters)
        val restored = readTabFilters(meta)
        assertEquals(filters, restored)

        // Switched-off column filter is excluded from active FilterRequest while multi stays active
        val cols = listOf(
            FilterableColumn("status", ColumnKind.TEXT),
            FilterableColumn("owner", ColumnKind.TEXT),
        )
        val req = filterRequest(restored, cols)
        assertTrue(req.filters.isEmpty())
        assertEquals(2, req.anyColumn.size)

        // Refused filter on readonly connection (403) blames the rawSql column
        val sqlFilters = withColumnFilter(NO_FILTERS, "status", "{$$ > 0}")
        val sqlReq = filterRequest(sqlFilters, cols)
        val blamed = refusedFilters(sqlReq, 403, "Connection is read-only")
        assertEquals(mapOf("status" to "Connection is read-only"), blamed)
    }

    @Test
    fun funnelMenuSetFilterAndValuePickerHelpersMatchWeb() {
        val setTxt = setFilterText(
            kind = FunnelKind.NUMBER,
            first = ConditionInput(">=", "10"),
            join = "and",
            second = ConditionInput("<=", "50"),
        )
        assertEquals(">=10 <=50", setTxt)

        val pickedTxt = pickedCellValuesFilter(
            kind = ColumnKind.TEXT,
            values = listOf(DbCellValue.Text("active"), DbCellValue.Null, DbCellValue.Text("a, b")),
        )
        assertEquals("=\"active\",NULL,=\"a, b\"", pickedTxt)
        val parsedPicked = parseFilter(pickedTxt, ColumnKind.TEXT)
        assertTrue(parsedPicked is FilterParseResult.Ok)
        assertEquals(
            listOf(
                listOf(FilterCondition(op = "in", values = listOf(FilterValue.Str("active"), FilterValue.Str("a, b")))),
                listOf(FilterCondition(op = "isNull")),
            ),
            (parsedPicked as FilterParseResult.Ok).anyOf,
        )

        assertTrue(canChooseValues(ColumnKind.TEXT))
        assertTrue(canChooseValues(ColumnKind.DATE))
        assertFalse(canChooseValues(ColumnKind.BINARY))
        assertFalse(canChooseValues(ColumnKind.JSON))
    }
}
