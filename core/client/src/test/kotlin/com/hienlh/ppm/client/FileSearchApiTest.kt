package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.FileReplaceFile
import com.hienlh.ppm.protocol.FileReplaceMatch
import com.hienlh.ppm.protocol.FileSearchOptions
import com.hienlh.ppm.protocol.WireJson
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.TimeUnit

/** `GET /files/search` and `POST /files/replace` as the search host answers them: bare JSON, no `{ ok, data }`. */
class FileSearchApiTest {
    private fun json(body: String, status: Int = 200) =
        MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body(body).build()

    private fun <T> withServer(block: suspend (MockWebServer, ApiClient) -> T): T = runBlocking {
        val server = MockWebServer()
        server.start()
        try { block(server, ApiClient(server.url("/"), "t")) } finally { server.close() }
    }

    @Test fun `the search host's bare reply is read, not refused as a non-PPM answer`() = withServer { server, api ->
        server.enqueue(json("""{"results":[{"file":"src/a.ts","matches":[{"line":4,"column":2,"length":3,"before":"  ","cutBefore":false,"text":"foo","after":"()"}]}],"total":1,"files":1,"limitHit":false,"engine":"rg"}"""))
        val answer = api.searchProjectFiles("app", FileSearchOptions("foo"))
        val match = answer.results.single().matches.single()
        assertEquals(4, match.line)
        assertEquals(2, match.column)
        assertEquals("foo", match.text)
        assertEquals("rg", answer.engine)
    }

    @Test fun `an old server's enveloped reply still reads`() = withServer { server, api ->
        server.enqueue(json("""{"ok":true,"data":{"results":[{"file":"a","matches":[{"lineNum":7,"content":"hit"}]}],"total":1}}"""))
        assertEquals(7, api.searchProjectFiles("app", FileSearchOptions("hit")).results.single().matches.single().lineNum)
    }

    @Test fun `exclude, ignore files and an open replace field reach the query as the web sends them`() = withServer { server, api ->
        server.enqueue(json("""{"results":[],"total":0,"files":0,"limitHit":false,"engine":"rg"}"""))
        api.searchProjectFiles("app", FileSearchOptions("x", include = " *.ts ", exclude = "**/fixtures/**", useIgnore = false, replace = "", preserveCase = true))
        val url = server.takeRequest(2, TimeUnit.SECONDS)!!.url
        assertEquals("*.ts", url.queryParameter("include"))
        assertEquals("**/fixtures/**", url.queryParameter("exclude"))
        assertEquals("false", url.queryParameter("useIgnore"))
        assertEquals("", url.queryParameter("replace"))
        assertEquals("true", url.queryParameter("preserveCase"))

        server.enqueue(json("""{"results":[],"total":0,"files":0,"limitHit":false,"engine":"rg"}"""))
        api.searchProjectFiles("app", FileSearchOptions("x"))
        val plain = server.takeRequest(2, TimeUnit.SECONDS)!!.url
        assertNull(plain.queryParameter("exclude"))
        assertNull(plain.queryParameter("replace"))
        assertNull(plain.queryParameter("preserveCase"))
    }

    @Test fun `a refused pattern keeps its 400 and message`() = withServer { server, api ->
        server.enqueue(json("""{"ok":false,"error":"regex parse error"}""", 400))
        try {
            api.searchProjectFiles("app", FileSearchOptions("("))
            fail("expected a refusal")
        } catch (error: PpmError.Server) {
            assertEquals(400, error.status)
            assertEquals("regex parse error", error.message)
        }
    }

    @Test fun `replace posts the matches and reads the bare report`() = withServer { server, api ->
        server.enqueue(json("""{"replaced":2,"files":1,"skipped":[{"file":"b.ts","reason":"changed"}],"failed":[]}"""))
        val report = api.replaceInProjectFiles("my app", listOf(FileReplaceFile("a.ts", listOf(FileReplaceMatch(1, 0, "foo", "bar")))))
        assertEquals(2, report.replaced)
        assertEquals("b.ts", report.skipped.single().file)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/api/project/my%20app/files/replace", request.url.encodedPath)
        assertEquals(
            WireJson.parse("""{"files":[{"file":"a.ts","matches":[{"line":1,"column":0,"text":"foo","replacement":"bar"}]}]}"""),
            WireJson.parse(request.body!!.utf8()),
        )
    }
}
