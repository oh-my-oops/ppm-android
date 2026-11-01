package com.hienlh.ppm.state.files

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.client.searchProjectFiles
import com.hienlh.ppm.protocol.FileSearchOptions
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class ProjectFileSearchWireTest {
    private fun json(body: String, status: Int = 200) = MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body(body).build()

    @Test fun `search wire preserves flags glob and untrimmed query with header auth`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = ApiClient(server.url("/prefix/"), "token +/?")
            server.enqueue(json("""{"ok":true,"data":{"results":[{"file":"src/a.kt","matches":[{"lineNum":37,"content":"  hello"}]}],"total":1}}"""))
            val options = FileSearchOptions(" hello +?&#雪 ", true, false, true, " *.kt, src/** ")
            val answer = api.searchProjectFiles("p /雪", options)
            assertEquals(37, answer.results.single().matches.single().lineNum)
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/prefix/api/project/${ApiClient.escape("p /雪")}/files/search", request.url.encodedPath)
            assertEquals(options.query, request.url.queryParameter("q"))
            assertEquals(options.include, request.url.queryParameter("include"))
            assertEquals("true", request.url.queryParameter("caseSensitive"))
            assertEquals("false", request.url.queryParameter("wholeWord"))
            assertEquals("true", request.url.queryParameter("regex"))
            assertEquals("Bearer token +/?", request.headers["Authorization"])
            assertNull(request.url.queryParameter("token"))

            server.enqueue(json("""{"ok":true,"data":{"results":[],"total":0}}"""))
            api.searchProjectFiles("app", FileSearchOptions("hi"))
            assertNull(server.takeRequest(2, TimeUnit.SECONDS)!!.url.queryParameter("include"))
        }
    }

    @Test fun `read and write use shared typed read and mutation APIs without changing file text`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val backend = FileSearchBackend.api(ApiClient(server.url("/"), "password"))
            val project = "app /雪"
            val path = "src/a +?&#.txt"
            server.enqueue(json("""{"ok":true,"data":{"content":"hit\n\t","encoding":"utf-8"}}"""))
            val content = backend.read(project, path)
            assertEquals("hit\n\t", content.content)
            val read = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/api/project/${ApiClient.escape(project)}/files/read", read.url.encodedPath)
            assertEquals(path, read.url.queryParameter("path"))
            server.enqueue(json("""{"ok":true,"data":{"written":true}}"""))
            backend.write(project, path, "saved\r\n\t雪")
            val write = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("PUT", write.method)
            assertEquals("/api/project/${ApiClient.escape(project)}/files/write", write.url.encodedPath)
            val body = WireJson.parse(write.body!!.utf8())
            assertEquals(path, body["path"].string)
            assertEquals("saved\r\n\t雪", body["content"].string)
            assertEquals("Bearer password", write.headers["Authorization"])
        }
    }

    @Test fun `bounded server errors preserve 400 503 and 504 for actionable retry`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = ApiClient(server.url("/"), "password")
            for (status in listOf(400, 503, 504)) {
                server.enqueue(json("""{"ok":false,"error":"failure $status"}""", status))
                val error = try { api.searchProjectFiles("app", FileSearchOptions("hi")); error("Expected failure") } catch (error: PpmError.Server) { error }
                assertEquals(status, error.status)
                assertEquals("failure $status", error.message)
            }
        }
    }
}
