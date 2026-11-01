package com.hienlh.ppm.state.files

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvDocumentTest {
    private val fixture = javaClass.getResourceAsStream("/csv-web-fixtures.json")!!.bufferedReader().use {
        Json.parseToJsonElement(it.readText()).jsonObject
    }

    @Test fun `parser matches web on quoted malformed unicode and generated inputs`() {
        for (sample in fixture.getValue("parser").jsonArray) {
            val data = sample.jsonObject
            val content = data.getValue("content").jsonPrimitive.content
            val expected = CsvDocument(strings(data.getValue("headers")), data.getValue("rows").jsonArray.map(::strings))
            assertEquals(content, expected, CsvDocument.parse(content))
        }
    }

    @Test fun `sorting matches web TanStack including small table auto-detection and stable equal values`() {
        for (sample in fixture.getValue("sorting").jsonArray) {
            val data = sample.jsonObject
            val document = CsvDocument(listOf("value"), strings(data.getValue("values")).map(::listOf))
            assertEquals(data.getValue("asc").jsonArray.map { it.jsonPrimitive.int }, document.sortedRows(0))
            assertEquals(data.getValue("desc").jsonArray.map { it.jsonPrimitive.int }, document.sortedRows(0, true))
            assertEquals(document.rows.indices.toList(), document.sortedRows(null))
        }
    }

    @Test fun `long fields do not require recursive parsing`() {
        val content = "a,b\n\"" + "x,".repeat(100_000) + "\",ok"
        val document = CsvDocument.parse(content)
        assertEquals(200_000, document.rows.single()[0].length)
        assertEquals("ok", document.rows.single()[1])
    }

    private fun strings(value: JsonElement) = value.jsonArray.map { it.jsonPrimitive.content }
}
