package com.hienlh.ppm.state.database

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabasePhase09dEditStateTest {

    @Test
    fun `sameDbCellValue distinguishes string 5 from number 5 and Null from NoField`() {
        assertFalse(sameDbCellValue(DbCellValue.Text("5"), DbCellValue.Num("5", 5.0)))
        assertFalse(sameDbCellValue(DbCellValue.Null, DbCellValue.NoField))
        assertTrue(sameDbCellValue(DbCellValue.Num("42", 42.0), DbCellValue.Num("42", 42.0)))
        assertTrue(
            sameDbCellValue(
                DbCellValue.JsonObj("""{"a":1,"b":"x"}"""),
                DbCellValue.JsonObj("""{"b":"x","a":1}"""),
            ),
        )
    }

    @Test
    fun `changeset with 3 edits 1 insert 1 delete and untouched new row builds expected payload`() {
        var cs = EMPTY_CHANGESET
        val row1Key = mapOf("id" to DbCellValue.Num("1", 1.0))
        val row2Key = mapOf("id" to DbCellValue.Num("2", 2.0))
        val row3Key = mapOf("id" to DbCellValue.Num("3", 3.0))
        val rowKeys = mapOf("r1" to row1Key, "r2" to row2Key, "r3" to row3Key)

        // 3 edits across 2 saved rows
        cs = editCells(
            cs,
            listOf(
                CellChange("r1", "name", DbCellValue.Text("Alice2"), key = row1Key, original = DbCellValue.Text("Alice")),
                CellChange("r1", "age", DbCellValue.Num("31", 31.0), key = row1Key, original = DbCellValue.Num("30", 30.0)),
                CellChange("r2", "name", DbCellValue.Text("Bob2"), key = row2Key, original = DbCellValue.Text("Bob")),
            ),
        )
        // 1 new row with edited cells + 1 untouched new row (must be omitted from inserts)
        cs = addRows(
            cs,
            listOf(
                NewRow("__new_1", mapOf("name" to DbCellValue.Text("Charlie"), "id" to DbCellValue.NoField)),
                NewRow("__new_2", emptyMap()),
            ),
        )
        // 1 deleted row
        cs = deleteRows(cs, listOf("r3"))

        val payload = toGridChanges(cs, rowKeys)
        assertEquals(1, payload.inserts.size)
        assertEquals(mapOf("name" to DbCellValue.Text("Charlie")), payload.inserts[0])
        assertEquals(2, payload.updates.size)
        assertEquals(1, payload.deletes.size)
        assertEquals(row3Key, payload.deletes[0])
        assertEquals(4, changedRowCount(cs))

        // Reverting r2's cell back to its original value removes it from pending updates
        cs = editCells(
            cs,
            listOf(CellChange("r2", "name", DbCellValue.Text("Bob"), key = row2Key)),
        )
        val afterRevert = toGridChanges(cs, rowKeys)
        assertEquals(1, afterRevert.updates.size)
        assertEquals("r1", cs.cells.keys.first { !isNewRowId(it) })
    }

    @Test
    fun `cloneValues omits autoIncrement PK and truncated binary cells`() {
        val columns = listOf(
            CloneColumn(name = "id", pk = true, autoIncrement = true),
            CloneColumn(name = "code", pk = true, autoIncrement = false),
            CloneColumn(name = "avatar", pk = false),
            CloneColumn(name = "thumb", pk = false),
            CloneColumn(name = "name", pk = false),
        )
        val row = mapOf(
            "id" to DbCellValue.Num("10", 10.0),
            "code" to DbCellValue.Text("A-10"),
            "avatar" to DbCellValue.Binary(base64 = "AAAA", size = 8192, truncated = true),
            "thumb" to DbCellValue.Binary(base64 = "AAAA", size = 3, truncated = false),
            "name" to DbCellValue.Text("Item"),
        )
        val cloned = cloneValues(row, columns)
        assertFalse("id" in cloned)
        assertFalse("avatar" in cloned)
        assertTrue("code" in cloned)
        assertTrue("thumb" in cloned)
        assertEquals(DbCellValue.Text("Item"), cloned["name"])
    }

    @Test
    fun `ChangesetHistory caps at 200 steps and supports undo and redo`() {
        var h = ChangesetHistory()
        for (i in 1..205) {
            val next = editCells(
                h.present,
                listOf(CellChange("r1", "v", DbCellValue.Num("$i", i.toDouble()), key = mapOf("id" to DbCellValue.Num("1", 1.0)))),
            )
            h = recordChange(h, next)
        }
        assertEquals(HISTORY_LIMIT, h.past.size)
        val undone = undoChange(h)
        assertEquals(DbCellValue.Num("204", 204.0), undone.present.cells["r1"]?.get("v")?.newVal)
        val redone = redoChange(undone)
        assertEquals(DbCellValue.Num("205", 205.0), redone.present.cells["r1"]?.get("v")?.newVal)
    }

    @Test
    fun `saveScript prepends checked cascade scripts in server deepest-first order and bypass checks references`() {
        val preview = ChangesetPreview(
            statementCount = 1,
            script = "DELETE FROM \"users\" WHERE \"id\" = 1;",
            references = listOf(
                ChangesetReference(
                    table = TableRef("public", "order_items"),
                    count = 5,
                    paths = listOf("order_items.order_id -> orders.id -> users.id"),
                    script = "DELETE FROM \"order_items\" WHERE \"order_id\" IN (10, 11);",
                ),
                ChangesetReference(
                    table = TableRef("public", "orders"),
                    count = 2,
                    paths = listOf("orders.user_id -> users.id"),
                    script = "DELETE FROM \"orders\" WHERE \"user_id\" = 1;",
                ),
            ),
        )
        val checked = setOf("public.orders", "public.order_items")
        val cascaded = cascadeTables(preview.references, checked)
        assertEquals(listOf("order_items", "orders"), cascaded.map { it.name })
        val fullScript = saveScript(preview, checked)
        assertEquals(
            """
            DELETE FROM "order_items" WHERE "order_id" IN (10, 11);
            DELETE FROM "orders" WHERE "user_id" = 1;
            DELETE FROM "users" WHERE "id" = 1;
            """.trimIndent(),
            fullScript,
        )
        assertFalse(shouldBypassSaveDialog(dontAskAgain = true, preview = preview))
        assertTrue(
            shouldBypassSaveDialog(
                dontAskAgain = true,
                preview = preview.copy(references = emptyList()),
            ),
        )
    }

    @Test
    fun `parseFormText preserves 64-bit bigint above 15 digits as Text`() {
        val big = "9007199254740993" // 16 digits (> 2^53)
        val parsedBig = parseFormText(big, FormFieldKind.NUMBER)
        assertEquals(FormParse.Valid(DbCellValue.Text(big)), parsedBig)

        val small = "42"
        val parsedSmall = parseFormText(small, FormFieldKind.NUMBER)
        assertEquals(FormParse.Valid(DbCellValue.Num("42", 42.0)), parsedSmall)
    }

    @Test
    fun `htmlDocument strips nested meta and link tags and injects strict CSP`() {
        val malicious = """<me<meta http-equiv="refresh" content="0;url=https://evil.test">ta http-equiv="Content-Security-Policy" content="default-src *"><link rel="stylesheet" href="https://evil.test/a.css"><p>Safe</p>"""
        val doc = htmlDocument(malicious)
        assertTrue(doc.contains(HTML_FRAME_POLICY))
        assertFalse(doc.contains("evil.test"))
        assertFalse(doc.contains("default-src *"))
        assertTrue(doc.contains("<p>Safe</p>"))
    }

    @Test
    fun `autodetectFormat detects PNG binary HTML XML and JSON`() {
        val pngBase64 = java.util.Base64.getEncoder().encodeToString(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00),
        )
        val pngCell = listOf(CellDataCell(0, 0, "img", "img", DbCellValue.Binary(pngBase64, 9)))
        assertEquals(CellDataFormat.PICTURE, autodetectFormat(pngCell))

        val htmlCell = listOf(CellDataCell(0, 0, "body", "body", DbCellValue.Text("<div>Hello</div>")))
        assertEquals(CellDataFormat.HTML, autodetectFormat(htmlCell))

        val xmlCell = listOf(CellDataCell(0, 0, "doc", "doc", DbCellValue.Text("<?xml version=\"1.0\"?><note>Hi</note>")))
        assertEquals(CellDataFormat.XML, autodetectFormat(xmlCell))

        val jsonCell = listOf(CellDataCell(0, 0, "meta", "meta", DbCellValue.Text("{\"k\":1}")))
        assertEquals(CellDataFormat.JSON, autodetectFormat(jsonCell))
    }

    @Test
    fun `readJsonDocuments and documentChanges validate columns and lock PK`() {
        val ndjson = """
            {"name": "A", "score": 10}
            {"name": "B", "score": 20}
        """.trimIndent()
        val parsed = readJsonDocuments(ndjson, single = false)
        assertTrue(parsed is JsonDocumentsResult.Ok)
        assertEquals(2, (parsed as JsonDocumentsResult.Ok).docs.size)

        val cols = listOf(
            DocumentColumn(key = "id", pk = true, autoIncrement = true),
            DocumentColumn(key = "name", pk = false),
        )
        val pkEditDoc = Json.parseToJsonElement("""{"id": 99, "name": "New"}""") as JsonObject
        val failRes = documentChanges(
            rowId = "r1",
            columns = cols,
            currentRow = mapOf("id" to DbCellValue.Num("1", 1.0), "name" to DbCellValue.Text("Old")),
            storedRow = mapOf("id" to DbCellValue.Num("1", 1.0), "name" to DbCellValue.Text("Old")),
            key = mapOf("id" to DbCellValue.Num("1", 1.0)),
            doc = pkEditDoc,
            changeset = EMPTY_CHANGESET,
        )
        assertTrue(failRes.isFailure)

        val newRowRes = newRowValues(cols, pkEditDoc)
        assertTrue(newRowRes.isSuccess)
        assertNull(newRowRes.getOrThrow()["id"]) // auto-increment PK skipped on new row
        assertEquals(DbCellValue.Text("New"), newRowRes.getOrThrow()["name"])
    }

    @Test
    fun `detailKeyFilters returns NULL when no master row is selected and generateSql formats WHERE IS NULL`() {
        val ref = GridReference(
            direction = "in",
            constraintName = "orders_user_id_fkey",
            columns = listOf("id"),
            targetSchema = "public",
            targetTable = "orders",
            targetColumns = listOf("user_id"),
        )
        val emptyFilters = detailKeyFilters(ref, emptyList(), { _, _ -> DbCellValue.Num("1", 1.0) })
        assertEquals(mapOf("user_id" to "NULL"), emptyFilters)

        val activeFilters = detailKeyFilters(ref, listOf(0, 1), { _, r -> DbCellValue.Num("${r + 10}", (r + 10).toDouble()) })
        assertEquals(mapOf("user_id" to "=\"10\",=\"11\""), activeFilters)

        val gen = generateSql(
            statement = GeneratedStatement.UPDATE,
            rows = listOf(
                SqlSourceRow(
                    values = mapOf("name" to DbCellValue.Text("Updated")),
                    where = mapOf("id" to DbCellValue.Num("5", 5.0), "deleted_at" to DbCellValue.Null),
                ),
            ),
            valueColumns = listOf("name"),
            whereColumns = listOf("id", "deleted_at"),
            target = CopySqlTarget(table = "users", schema = "public", dialect = "postgres"),
        )
        assertEquals(1, gen.statementCount)
        assertEquals(
            "UPDATE \"public\".\"users\" SET \"name\" = 'Updated' WHERE \"id\" = 5 AND \"deleted_at\" IS NULL;",
            gen.sql,
        )
    }
}
