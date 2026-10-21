package com.hienlh.ppm.chat

import com.hienlh.ppm.protocol.WireJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** tab-tool-call.test.ts and db-tool-call.test.ts, case by case. */
class PpmToolCallsTest {
    private fun json(text: String) = WireJson.parse(text)

    @Test
    fun `tab tools under Claude's names, with the arguments as the input`() {
        assertEquals(TabToolCall("open_file", "src/a.ts", 42), tabToolCall("mcp__ppm-tabs__open_file", json("""{"path":"src/a.ts","line":42}""")))
        assertEquals(TabToolCall("open_preview", "out/report.html"), tabToolCall("mcp__ppm-tabs__open_preview", json("""{"path":"out/report.html","screenshot":false}""")))
    }

    @Test
    fun `tab tools under Codex's name, with the arguments wrapped`() {
        val input = json("""{"server":"ppm_tabs","tool":"open_file","arguments":{"path":"a.ts","line":3}}""")
        assertEquals(TabToolCall("open_file", "a.ts", 3), tabToolCall("ppm_tabs:open_file", input))
    }

    @Test
    fun `anything else is not a tab tool call`() {
        val path = json("""{"path":"a.ts"}""")
        assertNull(tabToolCall("Read", path))
        assertNull(tabToolCall("mcp__ppm-design__design_check", path))
        assertNull(tabToolCall("mcp__other-ppm-tabs__open_file", path))
        assertNull(tabToolCall("mcp__ppm_tabs__open_file", path))
        assertNull(tabToolCall("mcp__ppm-tabs__open_file\n", path))
        assertNull(tabToolCall("mcp__ppm-tabs__open_file", json("""{"path":"  "}""")))
        assertNull(tabToolCall("mcp__ppm-tabs__open_file", json("null")))
        assertNull(tabToolCall("ppm_tabs:open_file", json("""{"arguments":null,"path":"a.ts"}""")))
    }

    @Test
    fun `a line is kept only when it is a real one, and only for open_file`() {
        assertEquals(TabToolCall("open_file", "a.ts"), tabToolCall("mcp__ppm-tabs__open_file", json("""{"path":"a.ts","line":0}""")))
        assertEquals(TabToolCall("open_file", "a.ts"), tabToolCall("mcp__ppm-tabs__open_file", json("""{"path":"a.ts","line":2.5}""")))
        assertEquals(TabToolCall("open_file", "a.ts"), tabToolCall("mcp__ppm-tabs__open_file", json("""{"path":"a.ts","line":"4"}""")))
        assertEquals(TabToolCall("open_file", "a.ts"), tabToolCall("mcp__ppm-tabs__open_file", json("""{"path":"a.ts","line":10000001}""")))
        assertEquals(TabToolCall("open_preview", "a.html"), tabToolCall("mcp__ppm-tabs__open_preview", json("""{"path":"a.html","line":4}""")))
    }

    @Test
    fun `a result's text joins the text blocks and drops the screenshot's stand-in`() {
        val output = """[{"type":"text","text":"Opened report.html"},{"type":"text","text":"[image · 12KB]"}]"""
        assertEquals("Opened report.html", tabToolResultText(output))
    }

    @Test
    fun `Codex's text drops its label for the screenshot`() {
        assertEquals(
            "Opened r.html in a PPM tab.\nNo layout problems or runtime errors were found.",
            tabToolResultText("Opened r.html in a PPM tab.\nNo layout problems or runtime errors were found.\n[image image/jpeg]"),
        )
    }

    @Test
    fun `plain text passes through`() {
        assertEquals("Opened src/a.ts at line 4.", tabToolResultText("Opened src/a.ts at line 4."))
        assertEquals("[not json", tabToolResultText("[not json"))
    }

    @Test
    fun `the problem count is read from the line formatPreviewCheck writes it on`() {
        val head = "Opened report.html in a PPM tab on the user's device and checked it at 1280x720 CSS px. Page size 1280x2400."
        assertEquals(0L, previewProblemCount("$head\nNo layout problems or runtime errors were found."))
        assertEquals(1L, previewProblemCount("$head\n1 problem found. Fix each one in the page's files, then call open_preview again.\n\n```\nx\n```"))
        assertEquals(2L, previewProblemCount("$head\n2 problems found. Fix each one in the page's files, then call open_preview again."))
        // A count quoted from the page cannot stand in for the real one.
        assertEquals(1L, previewProblemCount("$head\n1 problem found. Fix…\n\nNo layout problems or runtime errors were found."))
        assertEquals(0L, previewProblemCount("$head\nNo layout problems or runtime errors were found.\nScreenshot: \n9 problems found."))
    }

    @Test
    fun `other results have no problem count`() {
        assertNull(previewProblemCount("Opened report.html in a PPM tab on the user's device, but it could not be checked: timeout."))
        assertNull(previewProblemCount("No PPM window has this chat open, so nothing was shown."))
    }

    @Test
    fun `device tools under either provider's name`() {
        assertEquals(DeviceToolCall.OpenUrl("http://localhost:5173/"), deviceToolCall("mcp__ppm-tabs__open_url", json("""{"url":"http://localhost:5173/"}""")))
        assertEquals(DeviceToolCall.ReadTerminal(), deviceToolCall("mcp__ppm-tabs__read_terminal", json("{}")))
        assertEquals(DeviceToolCall.ReadTerminal("3f2a9c1e", 200), deviceToolCall("mcp__ppm-tabs__read_terminal", json("""{"terminal":"3f2a9c1e","lines":200}""")))
        assertEquals(
            DeviceToolCall.RunInTerminal("sudo apt install ffmpeg", "tools"),
            deviceToolCall("ppm_tabs:run_in_terminal", json("""{"server":"ppm_tabs","tool":"run_in_terminal","arguments":{"command":"sudo apt install ffmpeg","cwd":"tools"}}""")),
        )
    }

    @Test
    fun `device tools are null for the file tools, other servers and calls missing what they need`() {
        assertNull(deviceToolCall("mcp__ppm-tabs__open_file", json("""{"path":"a.ts"}""")))
        assertNull(deviceToolCall("mcp__other__open_url", json("""{"url":"http://localhost:1/"}""")))
        assertNull(deviceToolCall("mcp__ppm-tabs__open_url", json("""{"url":" "}""")))
        assertNull(deviceToolCall("mcp__ppm-tabs__run_in_terminal", json("{}")))
        assertEquals(DeviceToolCall.ReadTerminal(), deviceToolCall("mcp__ppm-tabs__read_terminal", json("""{"lines":-1,"terminal":5}""")))
        assertNull(tabToolCall("mcp__ppm-tabs__open_url", json("""{"path":"a.ts"}""")))
        assertEquals("open_url", deviceToolOf("mcp__ppm-tabs__open_url"))
        assertEquals("read_terminal", deviceToolOf("ppm_tabs:read_terminal"))
        assertNull(deviceToolOf("mcp__ppm-tabs__open_file"))
        assertNull(deviceToolOf("Bash"))
    }

    @Test
    fun `database tools under Claude's and Codex's names, Codex's arguments wrapped`() {
        assertEquals(DbToolCall("db_query", "Prod", "SELECT 1"), dbToolCall("mcp__ppm-db__db_query", json("""{"connection":"Prod","sql":"SELECT 1","max_rows":5}""")))
        assertEquals(
            DbToolCall("db_execute", "Prod", "DELETE FROM t", database = "app", reason = "Drop it", expectedRows = 0.0),
            dbToolCall(
                "ppm_db:db_execute",
                json("""{"server":"ppm_db","tool":"db_execute","arguments":{"connection":"Prod","sql":"DELETE FROM t","reason":"Drop it","expected_rows":0,"database":"app"}}"""),
            ),
        )
        assertEquals("open_query", dbToolOf("ppm_db:open_query"))
    }

    @Test
    fun `database tools are not another server's tool, nor a call without a connection and SQL`() {
        assertNull(dbToolOf("mcp__ppm-tabs__open_file"))
        assertNull(dbToolOf("mcp__ppm-db__db_query_all"))
        assertNull(dbToolCall("ppm_tabs:open_file", json("""{"path":"a"}""")))
        assertNull(dbToolCall("mcp__ppm-db__db_query", json("""{"sql":"SELECT 1"}""")))
        assertNull(dbToolCall("mcp__ppm-db__db_query", json("\"SELECT 1\"")))
    }

    @Test
    fun `database and device calls are summed up as the web's cards sum them up`() {
        val change = toolSummary("ppm_db:db_execute", json("""{"arguments":{"connection":"Prod","database":"app","sql":"\n DELETE FROM t\n","reason":"Drop it"}}"""))
        assertEquals("Database change ", change.name)
        assertEquals(listOf(SummaryPart("Prod / app"), SummaryPart(" · Drop it", subtle = true)), change.parts)
        val query = toolSummary("mcp__ppm-db__db_query", json("""{"connection":"Prod","sql":"\n  SELECT 1\nFROM t"}"""))
        assertEquals(listOf(SummaryPart("Prod"), SummaryPart(" · SELECT 1", subtle = true, mono = true)), query.parts)
        val url = toolSummary("mcp__ppm-tabs__open_url", json("""{"url":"http://localhost:5173/"}"""))
        assertEquals("Open app ", url.name)
        assertEquals(listOf(SummaryPart("localhost:5173/", subtle = true, mono = true)), url.parts)
        val read = toolSummary("mcp__ppm-tabs__read_terminal", json("""{"terminal":"3f2a","lines":50}"""))
        assertEquals("Read terminal", read.name)
        assertEquals(listOf(SummaryPart(" 3f2a", subtle = true, mono = true), SummaryPart(" · 50 lines", subtle = true)), read.parts)
        assertEquals(emptyList<SummaryPart>(), toolSummary("mcp__ppm-tabs__read_terminal", json("{}")).parts)
    }
}
