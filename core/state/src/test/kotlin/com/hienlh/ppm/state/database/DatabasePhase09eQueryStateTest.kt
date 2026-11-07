package com.hienlh.ppm.state.database

import com.hienlh.ppm.protocol.TabOpenAnswer
import com.hienlh.ppm.protocol.TabOpenRequest
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.state.preview.PreviewLoads
import com.hienlh.ppm.state.preview.answerTabOpen
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabasePhase09eQueryStateTest {

    @Test
    fun `splitSqlStatements handles strings comments dollar quotes and mysql delimiters`() {
        val pgSql = """
            -- leading comment ; ignored
            SELECT 'a;b', "c;d", ${'$'}tag${'$'}inner;semi${'$'}tag${'$'} FROM/* nested /* ; */ comment */ users;
            SELECT E'escaped\';still_string', 2;
        """.trimIndent()
        val pgStmts = splitSqlStatementsWithLines(pgSql, "postgres")
        assertEquals(2, pgStmts.size)
        assertTrue(pgStmts[0].text.startsWith("SELECT 'a;b'"))
        assertEquals(2, pgStmts[0].startLine)
        assertEquals(3, pgStmts[1].startLine)

        // MySQL -- requires whitespace; # is a comment; DELIMITER // changes statement separator
        val mysqlSql = """
            SELECT 1--2;
            # hash comment ; ignored
            DELIMITER //
            CREATE PROCEDURE p()
            BEGIN
              SELECT 10;
              SELECT 20;
            END //
            DELIMITER ;
            SELECT 30;
        """.trimIndent()
        val mysqlRes = splitSqlScript(mysqlSql, "mysql")
        assertNull(mysqlRes.error)
        assertEquals(3, mysqlRes.statements.size)
        assertEquals("SELECT 1--2", mysqlRes.statements[0].text)
        assertTrue(mysqlRes.statements[1].text.contains("SELECT 10;\n  SELECT 20;"))
        assertEquals("SELECT 30", mysqlRes.statements[2].text)

        val directives = delimiterDirectives(mysqlSql)
        assertEquals(listOf("//", ";"), directives.map { it.delimiter })

        // Bare DELIMITER reports line error
        val badDelim = "SELECT 1;\nDELIMITER   \nSELECT 2;"
        val badRes = splitSqlScript(badDelim, "mysql")
        assertNotNull(badRes.error)
        assertEquals(2, badRes.error?.line)
    }

    @Test
    fun `statementAtCursor runLineOffset and cursorAfterReformat preserve statement and token position`() {
        val sql = "SELECT 1;\nSELECT\n  id,\n  name\nFROM users;\nSELECT 3;"
        val secondOffset = sql.indexOf("name")
        val stmt = statementAtCursor(sql, secondOffset, "postgres")
        assertNotNull(stmt)
        assertEquals(2, stmt!!.startLine)
        assertEquals(5, stmt.endLine)
        assertEquals(1, runLineOffset(null, stmt))
        assertEquals(4, runLineOffset(5, stmt))

        val selRun = selectionOrStatementRun(sql, "SELECT 99", 4, secondOffset, "postgres")
        assertEquals(SqlToRun("SELECT 99", 3), selRun)

        val stmtRun = selectionOrStatementRun(sql, "", null, secondOffset, "postgres")
        assertEquals(SqlToRun("SELECT\n  id,\n  name\nFROM users", 1), stmtRun)

        val formatted = formatSqlScript("select id, name from users where active = true", "postgres")
        assertTrue(formatted.contains("SELECT"))
        assertTrue(formatted.contains("\nFROM users"))
        assertTrue(formatted.contains("\nWHERE active = TRUE"))

        val oldSql = "select   id,   name from users"
        val oldCursor = oldSql.indexOf("name") + 2 // right after "na"
        val newCursor = cursorAfterReformat(oldSql, oldCursor, formatted, "postgres")
        assertEquals("na", formatted.substring(newCursor - 2, newCursor))
    }

    @Test
    fun `isReadOnlyQuery blocks write statements CTE writes mysql executable comments and backslash tricks`() {
        assertTrue(isReadOnlyQuery("SELECT * FROM users; EXPLAIN SELECT 1;", "postgres"))
        assertTrue(isReadOnlyQuery("SHOW CREATE TABLE items", "mysql"))
        assertFalse(isReadOnlyQuery("SELECT * INTO new_t FROM users", "postgres"))
        assertFalse(isReadOnlyQuery("WITH d AS (DELETE FROM users RETURNING *) SELECT * FROM d", "postgres"))
        assertFalse(isReadOnlyQuery("SELECT 1 /* !50000 DROP TABLE users */;", "postgres").not() &&
            isReadOnlyQuery("SELECT 1 /*!50000 UNION ALL SELECT 2 */; DROP TABLE u", "mysql"))
        assertFalse(isReadOnlyQuery("/*!50000 DROP TABLE users */", "mysql"))
        // MySQL backslash escape ambiguity must fail readonly check
        assertFalse(isReadOnlyQuery("SELECT 'a\\'; DROP TABLE users; --'", "mysql"))
    }

    @Test
    fun `computeSqlCompletions provides schema-aware columns tables aliases and operators`() {
        val objects = listOf(
            DbObjectItem(schema = "public", name = "invoices", kind = "table", rowEstimate = 100),
            DbObjectItem(schema = "public", name = "customers", kind = "table", rowEstimate = 50),
            DbObjectItem(schema = "audit", name = "logs", kind = "table", rowEstimate = 500),
        )
        val cols = mapOf(
            "public.invoices" to listOf(
                GridColumn(name = "id", type = "int4", nullable = false, pk = true),
                GridColumn(name = "customer_id", type = "int4", nullable = false),
                GridColumn(name = "total_cents", type = "int8", nullable = false),
            ),
            "public.customers" to listOf(
                GridColumn(name = "id", type = "int4", nullable = false, pk = true),
                GridColumn(name = "Display Name", type = "text", nullable = false),
            ),
        )
        val sql = "SELECT i.to FROM invoices i JOIN customers c ON i.customer_id = c.id"
        val cursor = sql.indexOf("i.to") + 4
        val dotSuggestions = computeSqlCompletions(
            sql = sql,
            cursorOffset = cursor,
            dialect = "postgres",
            defaultSchema = "public",
            schemas = listOf("public", "audit"),
            objects = objects,
            columnsByTableKey = cols,
        )
        assertEquals(listOf("total_cents"), dotSuggestions.map { it.label })

        // Quoted identifier insertion for column with spaces
        val custDotSql = "SELECT c. FROM customers c"
        val custDotSuggestions = computeSqlCompletions(
            sql = custDotSql,
            cursorOffset = custDotSql.indexOf("c.") + 2,
            dialect = "postgres",
            defaultSchema = "public",
            schemas = listOf("public", "audit"),
            objects = objects,
            columnsByTableKey = cols,
        )
        val displayCol = custDotSuggestions.first { it.label == "Display Name" }
        assertEquals("\"Display Name\"", displayCol.insertText)
    }

    @Test
    fun `applyQueryEvents shifts lines tracks result tabs detects readonly refusal and supports editable results`() {
        var run = startQueryRun(runId = "r1", lineOffset = 10, startedAt = 1_000L, kind = "query")
        val e1 = parseQueryScriptEventJson("""{"type":"start","statements":[{"startLine":1,"endLine":3}]}""")!!
        val e2 = parseQueryScriptEventJson("""{"type":"running","index":0}""")!!
        val e3 = parseQueryScriptEventJson(
            """{"type":"statement","result":{"index":0,"startLine":1,"endLine":3,"sql":"SELECT id, id, name FROM users","command":"SELECT","resultSets":[{"columns":[{"name":"id","type":"int4","nullable":false,"pk":true},{"name":"id","type":"int4","nullable":false,"pk":true},{"name":"name","type":"text","nullable":true,"pk":false}],"rows":[[1,1,"Alice"]],"truncated":false}]," notices":["notice 1"],"durationMs":18}}""",
        )!!
        val e4 = parseQueryScriptEventJson("""{"type":"done","durationMs":20}""")!!

        run = applyQueryEvents(
            run,
            listOf(
                TimedQueryEvent(e1, 1_005L),
                TimedQueryEvent(e2, 1_006L),
                TimedQueryEvent(e3, 1_018L),
                TimedQueryEvent(e4, 1_020L),
            ),
        )
        assertTrue(run.done)
        assertEquals(11, run.statements.single().startLine)
        assertEquals("Result 1", run.resultTabs.single().label)
        assertEquals("result-0-0", shownResultTab(MESSAGES_TAB, run).let { shownResultTab("auto", run) })

        val (_, rs) = resultOfTab(run, "result-0-0")!!
        val deduped = deduplicateResultColumns(rs.columns)
        assertEquals(listOf("id", "id (2)", "name"), deduped.map { it.name })

        // Duplicate column names prevent in-place editing
        val source = extractQueryTable("SELECT id, name FROM public.users", "public")
        assertEquals(ExtractedQueryTable("users", "public"), source)
        val tableCols = listOf(
            GridColumn(name = "id", type = "int4", nullable = false, pk = true),
            GridColumn(name = "name", type = "text", nullable = true, pk = false),
        )
        assertEquals(
            ResultEditability.Readonly,
            resultEditability(rs.columns, tableCols, source, readonly = false, explain = false),
        )
        // Unique columns with PK are editable
        val uniqueResultCols = listOf(rs.columns[0], rs.columns[2])
        val editable = resultEditability(uniqueResultCols, tableCols, source, readonly = false, explain = false)
        assertTrue(editable is ResultEditability.Editable)

        // Readonly refusal detection
        val roRun = failQueryRun(startQueryRun("r2"), "Connection is readonly: UPDATE is not allowed in readonly mode")
        assertTrue(refusedAsReadonly(roRun))
    }

    @Test
    fun `query history file helpers and answerTabOpen open_query work as specified`() = runTest {
        assertEquals("query-1.sql", defaultQueryFileName("Query 1"))
        assertEquals("report.sql", withSqlExtension("report"))
        assertEquals("report.sql", queryFileTitle("/home/user/sql/report.sql"))

        val meta = queryTabMetadata("SELECT 1;", queryNumber = 2, connectionId = 5, connectionName = "Prod")
        assertFalse(isQueryTabDirty(meta))
        val dirtyMeta = buildJsonObject {
            meta.forEach { (k, v) -> put(k, v) }
            put("currentSql", "SELECT 2;")
        }
        assertTrue(isQueryTabDirty(dirtyMeta))

        var openedQueryJson: JsonObject? = null
        val req = TabOpenRequest.Query(
            requestId = "req-1",
            query = WireJson.parse("""{"connectionId":5,"connectionName":"Prod","dbType":"postgres","sql":"SELECT * FROM invoices;"}""") as JsonObject,
        )
        val ans = answerTabOpen(
            request = req,
            previews = PreviewLoads(),
            openFile = { error("unexpected") },
            openUrl = { error("unexpected") },
            frame = { "Desktop" },
            openQuery = { q -> openedQueryJson = q.query },
        )
        assertEquals(TabOpenAnswer(opened = true), ans)
        assertEquals("SELECT * FROM invoices;", openedQueryJson?.get("sql")?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
    }
}
