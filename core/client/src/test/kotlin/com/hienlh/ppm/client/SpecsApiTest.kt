package com.hienlh.ppm.client

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** `/api/project/:name/specs` (routes/specs.ts) as the app calls it, and the shapes it reads back. */
class SpecsApiTest {
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

    private val view = """
        {"slug":"login","title":"Login flow","kind":"feature","workflow":"requirements-first","checks":"standard",
         "folder":"specs/login","current":"requirements","order":["requirements","design","tasks"],
         "phases":{
           "requirements":{"status":"draft","file":"specs/login/requirements.md","issues":[{"line":3,"severity":"error","message":"No criteria"}],"text":"# Login","submitted":true},
           "design":{"status":"missing","file":"specs/login/design.md","issues":[],"text":null,"submitted":false,"approveBlocked":"Approve requirements.md first."},
           "tasks":{"status":"missing","file":"specs/login/tasks.md","issues":[],"text":null,"submitted":false}},
         "needsDrawing":false,"drawing":"not-needed","designSlug":null,"testCommand":null,
         "waves":{"waves":[["1","2"],["3"]],"moved":[{"id":"3","reason":"shares src/a.ts with 1"}],"optional":[],"issues":[]},
         "tasks":[
           {"id":"1","title":"Model","line":4,"optional":false,"status":"done","done":true,"next":"finished","blockedReason":null,"wave":0,
            "requirements":["1.1"],"deps":[],"files":["src/a.ts"],"tests":[],"noTest":null,"screen":null,"children":[],
            "run":{"status":"done","sessionId":"task-1","green":{"at":"x","command":"bun test","outcome":"pass","failing":[],"passed":4,"failed":0,"weak":false,"output":"","durationMs":1}},
            "testing":{"kind":"suite","startedAt":"2026-11-21T09:00:00.000Z"}},
           {"id":"3","title":"UI","line":9,"optional":true,"status":"todo","done":false,"next":"start","blockedReason":null,"wave":null,
            "requirements":[],"deps":["1"],"files":[],"tests":[],"noTest":null,"screen":null,"children":[],"run":null,"testing":{"kind":"bogus","startedAt":"x"}}],
         "approvals":{},"baseline":null,"baselineRunning":false,"suite":{"newFailures":["a.test.ts > b"]},"suiteRunning":false,
         "run":{"active":true,"startedAt":"x","wave":1},"runLive":true,"sessionId":"chat-1","changeRequests":[],"updatedAt":"2026-11-11T08:00:00.000Z"}
    """.trimIndent()

    @Test
    fun `the list reads every line, and a server without the History progress field gives no tasks`() = withServer(
        ok(
            """[{"slug":"login","title":"Login","kind":"bugfix","checks":"thorough","current":"execution","tasksDone":2,"tasksTotal":5,
               "red":1,"running":1,"waitingForYou":true,"sessionId":"chat-1","updatedAt":"2026-11-11T08:00:00.000Z",
               "tasks":[{"id":"1","status":"done","optional":false,"sessionId":"t1"},{"id":"2","status":"red","optional":false,"sessionId":null}]},
              {"slug":"old","title":"Old","kind":"feature","checks":"standard","current":"design","tasksDone":0,"tasksTotal":0,
               "red":0,"running":0,"waitingForYou":false,"sessionId":null,"updatedAt":"2026-11-10T08:00:00.000Z"}]""",
        ),
    ) { server, api ->
        val list = api.specs("my app")
        assertEquals("/api/project/my%20app/specs", server.takeRequest().target)
        assertEquals(listOf("login", "old"), list.map { it.slug })
        val login = list[0]
        assertEquals("bugfix", login.kind)
        assertEquals("thorough", login.checks)
        assertEquals("execution", login.current)
        assertEquals(2, login.tasksDone)
        assertEquals(5, login.tasksTotal)
        assertTrue(login.waitingForYou)
        assertEquals(listOf(SpecSummaryTask("1", "done", false, "t1"), SpecSummaryTask("2", "red", false, null)), login.tasks)
        assertEquals("chat-1", login.sessionId)
        assertTrue(list[1].tasks.isEmpty())
        assertNull(list[1].sessionId)
    }

    @Test
    fun `a spec is read with its documents, waves, tasks, suite and run`() = withServer(ok(view)) { server, api ->
        val spec = api.spec("ppm", "login")
        assertEquals("/api/project/ppm/specs/login", server.takeRequest().target)
        assertEquals("Login flow", spec.title)
        val req = spec.phases.getValue("requirements")
        assertEquals("draft", req.status)
        assertTrue(req.submitted)
        assertTrue(req.canAct)
        assertEquals("# Login", req.text)
        assertEquals(listOf(SpecIssue(3, "error", "No criteria")), req.issues)
        assertNull(spec.phases.getValue("design").text)
        assertFalse(spec.phases.getValue("design").canAct)
        assertNull(req.approveBlocked)
        assertEquals("Approve requirements.md first.", spec.phases.getValue("design").approveBlocked)
        assertEquals(listOf(listOf("1", "2"), listOf("3")), spec.waves)
        assertEquals(mapOf("3" to "shares src/a.ts with 1"), spec.moved)
        assertEquals(listOf("1", "3"), spec.tasks.map { it.id })
        assertEquals("task-1", spec.tasks[0].run?.sessionId)
        assertEquals(4, spec.tasks[0].run?.green?.passed)
        assertTrue(spec.tasks[1].optional)
        // The whole-suite run a green task's task_green call still waits on; an unknown kind is none.
        assertEquals(SpecTaskTesting("suite", java.time.Instant.parse("2026-11-21T09:00:00Z")), spec.tasks[0].testing)
        assertNull(spec.tasks[1].testing)
        assertEquals(listOf("1"), spec.tasks[1].deps)
        assertEquals(listOf("a.test.ts > b"), spec.suiteNewFailures)
        assertTrue(spec.running)
        assertFalse(spec.interrupted)
        assertEquals(1, spec.run?.wave)
        assertEquals("chat-1", spec.sessionId)
        // The one required task is done, so Run all has nothing left.
        assertTrue(spec.allDone)
    }

    @Test
    fun `approve posts the phase with continue, and reads the message and the spec it answers`() = withServer(
        ok("""{"message":"Approved requirements.md.","spec":$view}"""),
    ) { server, api ->
        val result = api.approveSpec("ppm", "login", SpecPhases.REQUIREMENTS)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/project/ppm/specs/login/approve/requirements", request.target)
        assertEquals("""{"continue":true}""", request.body?.utf8())
        assertEquals("Approved requirements.md.", result.message)
        assertEquals("login", result.spec.slug)
    }

    @Test
    fun `request changes posts the note with continue and reads the spec back`() = withServer(ok(view)) { server, api ->
        val spec = api.requestSpecChanges("ppm", "login", SpecPhases.DESIGN, "Use OAuth, not passwords")
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/project/ppm/specs/login/request-changes/design", request.target)
        assertEquals("""{"note":"Use OAuth, not passwords","continue":true}""", request.body?.utf8())
        assertEquals("login", spec.slug)
    }

    @Test
    fun `run all and stop post to the spec's run routes`() = withServer(
        ok("""{"message":"The tasks were handed to the spec's chat.","spec":$view}"""),
        ok("""{"message":"Stopped.","spec":$view}"""),
    ) { server, api ->
        assertEquals("The tasks were handed to the spec's chat.", api.runAllSpec("ppm", "login").message)
        val run = server.takeRequest()
        assertEquals("POST", run.method)
        assertEquals("/api/project/ppm/specs/login/run", run.target)
        assertEquals("{}", run.body?.utf8())
        assertEquals("Stopped.", api.stopSpec("ppm", "login").message)
        val stop = server.takeRequest()
        assertEquals("POST", stop.method)
        assertEquals("/api/project/ppm/specs/login/stop", stop.target)
    }

    @Test
    fun `a task is started, reset, the suite checked and the checks set on their own routes`() = withServer(
        ok("""{"message":"Task 3 was handed to the spec's chat.","spec":$view}"""),
        ok(view),
        ok("""{"ok":true,"message":"Clean.","spec":$view}"""),
        ok(view),
    ) { server, api ->
        api.runSpecTask("ppm", "login", "3")
        assertEquals("/api/project/ppm/specs/login/tasks/3/run", server.takeRequest().target)
        api.resetSpecTask("ppm", "login", "3")
        assertEquals("/api/project/ppm/specs/login/tasks/3/reset", server.takeRequest().target)
        assertEquals("Clean.", api.checkSpecSuite("ppm", "login").message)
        assertEquals("/api/project/ppm/specs/login/suite/check", server.takeRequest().target)
        api.setSpecChecks("ppm", "login", "thorough")
        val checks = server.takeRequest()
        assertEquals("PUT", checks.method)
        assertEquals("/api/project/ppm/specs/login/checks", checks.target)
        assertEquals("""{"checks":"thorough"}""", checks.body?.utf8())
    }

    @Test
    fun `a new spec is bound to the chat it was started in`() = withServer(ok(view)) { server, api ->
        api.createSpec("ppm", "Login flow", "bugfix", "requirements-first", "thorough", sessionId = "chat-1")
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/project/ppm/specs", request.target)
        assertEquals(
            """{"title":"Login flow","kind":"bugfix","workflow":"requirements-first","checks":"thorough","sessionId":"chat-1"}""",
            request.body?.utf8(),
        )
    }

    @Test
    fun `by-session answers the binding, or null for a chat with no spec`() = withServer(
        ok("""{"slug":"login","taskId":"2","providerId":"codex"}"""),
        ok("null"),
    ) { server, api ->
        assertEquals(SpecBinding("login", "2", "codex"), api.specBySession("ppm", "chat:1"))
        assertEquals("/api/project/ppm/specs/by-session/chat%3A1", server.takeRequest().target)
        assertNull(api.specBySession("ppm", "chat-2"))
    }

    @Test
    fun `a gate that says no is thrown with the server's reason`() = withServer(
        MockResponse.Builder().code(409).body("""{"ok":false,"error":"Approve design.md first."}""").build(),
    ) { _, api ->
        try {
            api.approveSpec("ppm", "login", SpecPhases.TASKS)
            fail("expected a refusal")
        } catch (e: PpmError.Server) {
            assertEquals(409, e.status)
            assertEquals("Approve design.md first.", e.message)
        }
    }

    @Test
    fun `the History view mode is read from the ui prefs and saved as the one key`() = withServer(
        ok("""{"chatHistoryViewMode":"flat","wordWrap":true}"""),
        ok("""{"chatHistoryViewMode":"tree"}"""),
        ok("""{"chatHistoryViewMode":"sideways"}"""),
    ) { server, api ->
        assertEquals("flat", api.chatHistoryViewMode())
        server.takeRequest()
        api.putChatHistoryViewMode("tree")
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/api/settings/ui-prefs", put.target)
        assertEquals("""{"chatHistoryViewMode":"tree"}""", put.body?.utf8())
        assertNull(api.chatHistoryViewMode())
    }
}
