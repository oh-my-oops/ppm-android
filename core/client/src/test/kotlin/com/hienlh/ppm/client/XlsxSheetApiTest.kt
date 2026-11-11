package com.hienlh.ppm.client

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.TimeUnit

/** `GET …/files/xlsx-sheet` and `GET /api/fs/xlsx-sheet`, as `xlsx-preview.tsx` reads them. */
class XlsxSheetApiTest {
    private fun json(body: String, status: Int = 200) =
        MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body(body).build()

    private fun <T> withServer(block: suspend (MockWebServer, ApiClient) -> T): T = runBlocking {
        val server = MockWebServer()
        server.start()
        try { block(server, ApiClient(server.url("/"), "t")) } finally { server.close() }
    }

    @Test fun `a project workbook's sheet is read with its empty cells and cuts`() = withServer { server, api ->
        server.enqueue(json("""{"ok":true,"data":{"sheets":["Sales","Notes"],"sheet":1,"rows":[["a",null,"3"],["b"]],"columns":3,"truncatedRows":true,"truncatedColumns":false}}"""))
        val sheet = api.xlsxSheet("my app", "data/q3.xlsx", 1)
        assertEquals(XlsxSheet(listOf("Sales", "Notes"), 1, listOf(listOf("a", null, "3"), listOf("b")), 3, truncatedRows = true, truncatedColumns = false), sheet)
        val url = server.takeRequest(2, TimeUnit.SECONDS)!!.url
        assertEquals("/api/project/my%20app/files/xlsx-sheet", url.encodedPath)
        assertEquals("data/q3.xlsx", url.queryParameter("path"))
        assertEquals("1", url.queryParameter("sheet"))
    }

    @Test fun `a workbook outside any project goes through the fs route`() = withServer { server, api ->
        server.enqueue(json("""{"ok":true,"data":{"sheets":["S"],"sheet":0,"rows":[],"columns":0,"truncatedRows":false,"truncatedColumns":false}}"""))
        api.xlsxSheet("app", "/home/me/book.xlsm", 0)
        assertEquals("/api/fs/xlsx-sheet", server.takeRequest(2, TimeUnit.SECONDS)!!.url.encodedPath)
    }

    @Test fun `the server's reason for a workbook it cannot show is kept`() = withServer { server, api ->
        server.enqueue(json("""{"ok":false,"error":"This workbook is password-protected or in the legacy .xls format."}""", 400))
        try {
            api.xlsxSheet("app", "old.xlsx", 0)
            fail("expected a refusal")
        } catch (error: PpmError.Server) {
            assertEquals(400, error.status)
            assertEquals("This workbook is password-protected or in the legacy .xls format.", error.message)
        }
    }
}
