package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.SessionAnswer
import com.hienlh.ppm.protocol.SessionAnswerFile
import com.hienlh.ppm.protocol.SessionBlockSummary
import com.hienlh.ppm.protocol.SessionFileChange
import com.hienlh.ppm.protocol.TurnRevertFile
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

/** The requests a session's changes make (`session-file-changes-client.ts`), and what their answers read as. */
class SessionChangesApiTest {
    private fun <T> withServer(vararg responses: MockResponse, block: suspend (MockWebServer, ApiClient) -> T): T = runBlocking {
        val server = MockWebServer()
        responses.forEach(server::enqueue)
        server.start()
        try {
            block(server, ApiClient(server.url("/"), "t"))
        } finally {
            server.close()
        }
    }

    private fun ok(data: String) = MockResponse.Builder().body("""{"ok":true,"data":$data}""").build()

    private val base = "/api/project/my%20app/chat/sessions/s%2F1/file-changes"

    @Test
    fun `the list is asked for with the transcript's paths and read field by field`() = withServer(
        ok(
            """{"files":[
              {"path":"/p/a.ts","status":"modified","additions":3,"deletions":1,"baseline":"session","version":"12:34","sinceReview":true,
               "blocks":[{"key":"k1","added":2,"removed":0,"kept":true,"calls":["t1","t2"]},{"key":"k2","added":1,"removed":1}],"base":"h"},
              {"path":"/p/b.png","status":"added","baseline":"head","binary":true,"version":"5:6","reviewed":true},
              {"status":"deleted"},
              {"path":"/p/c","status":"renamed","version":""}
            ]}""",
        ),
        ok("""{"files":"nope"}"""),
    ) { server, api ->
        val files = api.sessionFileChanges("my app", "s/1", listOf("/p/a.ts", "/p/z"))
        val request = server.takeRequest()
        assertEquals("POST $base", "${request.method} ${request.target}")
        assertEquals("""{"paths":["/p/a.ts","/p/z"]}""", request.body?.utf8())
        assertEquals(
            listOf(
                SessionFileChange(
                    "/p/a.ts", SessionFileChange.Status.MODIFIED, 3, 1, "session", version = "12:34", sinceReview = true,
                    blocks = listOf(SessionBlockSummary("k1", 2, 0, true, listOf("t1", "t2")), SessionBlockSummary("k2", 1, 1)), base = "h",
                ),
                SessionFileChange("/p/b.png", SessionFileChange.Status.ADDED, null, null, "head", binary = true, version = "5:6", reviewed = true),
                SessionFileChange("/p/c", SessionFileChange.Status.MODIFIED, version = ""),
            ),
            files,
        )
        // An answer of any other shape is no list, not a failure.
        assertEquals(emptyList<SessionFileChange>(), api.sessionFileChanges("my app", "s/1", emptyList()))
    }

    @Test
    fun `marks, answers, a turn's revert and an undo send what the web sends`() = withServer(
        ok("""{"updated":["/p/a.ts"],"stale":["/p/b"]}"""),
        ok("""{"files":[{"path":"/p/a.ts","stale":true,"file":null},{"path":"/p/b","error":"No copy","file":{"path":"/p/b","status":"added","version":"1"}}],"undoId":"u1"}"""),
        ok("""{"files":[{"path":"/p/a.ts","version":"v1","action":"edit","changes":2,"added":3,"removed":1,"skipped":[{"line":4,"by":["t9",""]}]},{"path":"/p/x","version":"","action":"restore","skipped":[]}]}"""),
        ok("""{"files":[],"undoId":"r1","stale":false}"""),
        ok("""{"files":[],"stale":true}"""),
    ) { server, api ->
        val a = SessionFileChange("/p/a.ts", SessionFileChange.Status.MODIFIED, version = "v1")
        val review = api.setSessionFilesReviewed("my app", "s/1", listOf(a), reviewed = false)
        assertEquals(listOf("/p/a.ts") to listOf("/p/b"), review.updated to review.stale)
        server.takeRequest().let {
            assertEquals("POST $base/reviewed", "${it.method} ${it.target}")
            assertEquals("""{"files":[{"path":"/p/a.ts","version":"v1"}],"reviewed":false}""", it.body?.utf8())
        }

        val answer = api.answerSessionChanges("my app", "s/1", SessionAnswer.REVERT, listOf(SessionAnswerFile("/p/a.ts", "v1", listOf("k1")), SessionAnswerFile("/p/b", "2")))
        server.takeRequest().let {
            assertEquals("POST $base/answer", "${it.method} ${it.target}")
            assertEquals("""{"answer":"revert","files":[{"path":"/p/a.ts","version":"v1","keys":["k1"]},{"path":"/p/b","version":"2"}]}""", it.body?.utf8())
        }
        assertEquals("u1", answer.undoId)
        assertEquals(listOf(true, false), answer.files.map { it.stale })
        assertEquals(listOf(null, "No copy"), answer.files.map { it.error })
        assertEquals(listOf(null, "/p/b"), answer.files.map { it.file?.path })

        val preview = api.revertSessionTurn("my app", "s/1", listOf("t1", "t2"))
        server.takeRequest().let {
            assertEquals("POST $base/revert-turn", "${it.method} ${it.target}")
            assertEquals("""{"calls":["t1","t2"]}""", it.body?.utf8())
        }
        assertEquals(
            listOf(
                TurnRevertFile("/p/a.ts", "v1", TurnRevertFile.Action.EDIT, 2, 3, 1, listOf(TurnRevertFile.Skipped(4, listOf("t9", "")))),
                TurnRevertFile("/p/x", "", TurnRevertFile.Action.RESTORE),
            ),
            preview.files,
        )
        val applied = api.revertSessionTurn("my app", "s/1", listOf("t1"), listOf("/p/a.ts" to "v1"))
        server.takeRequest().let { assertEquals("""{"calls":["t1"],"apply":[{"path":"/p/a.ts","version":"v1"}]}""", it.body?.utf8()) }
        assertEquals("r1" to false, applied.undoId to applied.stale)

        val undo = api.undoSessionAnswer("my app", "s/1", "u1")
        server.takeRequest().let {
            assertEquals("POST $base/undo", "${it.method} ${it.target}")
            assertEquals("""{"undoId":"u1"}""", it.body?.utf8())
        }
        assertEquals(true, undo.stale)
    }
}
