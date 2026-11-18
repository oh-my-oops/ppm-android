package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.TaskLink
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.string
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** `/api/tasks` (routes/ppm-tasks*.ts) as the app calls it, and the shapes it reads back. */
class TasksApiTest {
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

    private val task = """
        {"id":"11111111-1111-4111-8111-111111111111","projectKey":"k1","number":12,"title":"Fix login","type":"decision",
         "statusId":"s1","urgency":"week","importance":"must","rank":2.5,"why":"Users","nextStep":null,"notes":null,
         "dueDate":"2026-11-20","tags":["auth"],"assigneeMemberId":"me","waitingOnMemberId":null,"waitingSince":null,
         "parentId":null,"workingSessionId":"chat-1",
         "decision":{"question":"Which?","options":[{"id":"a","label":"Ship"},{"id":"b","label":"Wait"}],"chosen":{"optionId":"a","at":5,"by":"You"}},
         "fields":{"env":"prod","points":3,"gone":null},"links":[{"kind":"jira","ref":"NX-1","label":null}],
         "result":null,"handedOffAt":null,"snoozedUntil":null,"snoozeUntilChange":false,"snoozedAt":null,"resurfacedAt":null,
         "revision":7,"createdAt":1,"updatedAt":2,"closedAt":null,"deletedAt":null,"baseGroup":"active","closedAs":null,
         "effectiveUrgency":"today","urgencyRaised":true,"snoozed":false,"resurfacedToday":false,
         "project":"ppm","ref":"ppm/12","statusName":"In progress",
         "workingSession":{"kind":"session","session":{"sessionId":"chat-1","state":"running","title":"Login chat","runningSince":9}},
         "check":null}
    """.trimIndent()

    @Test
    fun `a list sends the filters in the web's order and reads tasks and projects`() = withServer(
        ok("""{"tasks":[$task],"projects":[{"name":"ppm","projectKey":"k1","enabled":true,"hasManager":true,"open":4,"now":1,"snoozed":2}]}"""),
    ) { server, api ->
        val list = api.tasksList(TaskListQuery("my app", groups = listOf("active", "waiting"), person = "me", q = " login ", closed = true, snoozed = true))
        assertEquals("/api/tasks?project=my%20app&group=active%2Cwaiting&person=me&q=login&closed=1&snoozed=1", server.takeRequest().target)
        val t = list.tasks.single()
        assertEquals(12, t.number)
        assertEquals("today", t.effectiveUrgency)
        assertTrue(t.urgencyRaised)
        assertEquals(2.5, t.rank, 0.0)
        assertEquals("prod", t.fieldText("env"))
        assertEquals("3", t.fieldText("points"))
        assertNull(t.fieldText("gone"))
        assertEquals("a", t.decision?.chosen?.optionId)
        assertEquals(listOf("Ship", "Wait"), t.decision?.options?.map { it.label })
        assertEquals("session", t.workingSession.kind)
        assertEquals("running", t.workingSession.session?.state)
        assertEquals("In progress", t.statusName)
        val p = list.projects.single()
        assertEquals(4, p.open)
        assertTrue(p.hasManager)
    }

    @Test
    fun `a detail reads children, link states and the Jira site`() = withServer(
        ok("""{"task":$task,"children":[],"linkStates":[{"kind":"jira","ref":"NX-1","state":{"status":"Done","category":"done"},"error":null,"checkedAt":3,"changedAt":null}],"jiraSite":"https://x.atlassian.net"}"""),
    ) { server, api ->
        val detail = api.taskDetail("11111111-1111-4111-8111-111111111111")
        assertEquals("/api/tasks/11111111-1111-4111-8111-111111111111", server.takeRequest().target)
        assertEquals("Done", detail.linkStates.single().state["status"].string)
        assertEquals("https://x.atlassian.net", detail.jiraSite)
    }

    @Test
    fun `a patch carries the revision, and a stale one is told apart`() = withServer(
        ok("""{"task":$task,"managerPrompt":true}"""),
        MockResponse.Builder().code(409).body("""{"ok":false,"error":"Task changed","code":"stale","revision":9}""").build(),
    ) { server, api ->
        val written = api.taskPatch("t1", 7, buildJsonObject { put("title", "New") })
        val body = WireJson.parse(server.takeRequest().body!!.utf8())
        assertEquals("New", body["title"].string)
        assertEquals(7, body["revision"].int)
        assertTrue(written.managerPrompt)
        try {
            api.taskPatch("t1", 7, buildJsonObject { put("title", "Again") })
            fail("expected a stale error")
        } catch (e: PpmError) {
            assertTrue(e.isStaleTask)
            assertFalse(e.isNoTaskSession)
        }
    }

    @Test
    fun `close, reopen, reorder, decide and start chat post to their own routes`() = withServer(
        ok("""{"task":$task,"managerPrompt":false}"""),
        ok("""{"task":$task,"managerPrompt":false}"""),
        ok("""{"task":$task,"managerPrompt":false}"""),
        ok("""{"task":$task,"managerPrompt":false,"delivered":"queued","sessionId":"chat-1"}"""),
        ok("""{"task":$task,"managerPrompt":false,"sessionId":"chat-9"}"""),
    ) { server, api ->
        api.taskClose("t1", 7, closedAs = "done")
        var r = server.takeRequest()
        assertEquals("/api/tasks/t1/close", r.target)
        assertEquals("done", WireJson.parse(r.body!!.utf8())["closedAs"].string)
        api.taskReopen("t1", 7)
        assertEquals("/api/tasks/t1/reopen", server.takeRequest().target)
        api.taskReorder("t1", 7, afterId = "t2")
        r = server.takeRequest()
        assertEquals("t2", WireJson.parse(r.body!!.utf8())["afterId"].string)
        val decided = api.taskDecide("t1", 7, "b")
        assertEquals("/api/tasks/t1/decide", server.takeRequest().target)
        assertEquals("queued", decided.delivered)
        val started = api.taskStartChat("t1", 7)
        assertEquals("/api/tasks/t1/start-chat", server.takeRequest().target)
        assertEquals("chat-9", started.sessionId)
    }

    @Test
    fun `a link is removed by its kind and ref, encoded, with the revision in the query`() = withServer(
        ok("""{"task":$task,"managerPrompt":false}"""),
    ) { server, api ->
        api.taskRemoveLink("t1", 7, TaskLink("pr", "o/r#4", null))
        val r = server.takeRequest()
        assertEquals("DELETE", r.method)
        assertEquals("/api/tasks/t1/links/pr%3Ao%2Fr%234?revision=7", r.target)
    }

    @Test
    fun `history, undo, link states and notify-manager`() = withServer(
        ok("""[{"id":4,"taskId":"t1","projectKey":"k1","at":10,"actor":{"kind":"manager","label":"Manager ppm"},"via":"tool","unattended":true,"kind":"updated","changes":[{"field":"urgency","before":"week","after":"now"}],"reason":"r","undoOf":null,"undoneBy":null,"undoable":true}]"""),
        ok("""{"task":$task,"managerPrompt":false}"""),
        ok("""{"tasks":{"t1":{"linkStates":[],"workingSession":{"kind":"start_chat"}}}}"""),
        ok("""{"sent":false,"reason":"budget"}"""),
    ) { server, api ->
        val h = api.taskHistory("t1", "ai").single()
        assertEquals("/api/tasks/t1/history?filter=ai", server.takeRequest().target)
        assertEquals("manager", h.actor.kind)
        assertTrue(h.undoable)
        assertTrue(h.unattended)
        assertEquals("now", h.changes.single().after.string)
        api.taskUndo(4)
        assertEquals("/api/tasks/history/4/undo", server.takeRequest().target)
        val live = api.taskLinkStates(listOf("t1", "t2"))
        assertEquals("/api/tasks/link-states?ids=t1%2Ct2", server.takeRequest().target)
        assertEquals("start_chat", live["t1"]?.workingSession?.kind)
        val sent = api.tasksNotifyManager("ppm")
        assertFalse(sent.sent)
        assertEquals("budget", sent.reason)
    }

    @Test
    fun `project settings read statuses, members and fields in position order`() = withServer(
        ok(
            """{"project":{"name":"ppm","projectKey":"k1","enabled":true,"hasManager":false,"open":1,"now":0,"snoozed":0,"enabledBy":"user","checkIntervalMin":45},
               "statuses":[{"id":"s2","name":"Done","baseGroup":"closed","closedAs":"done","position":2},{"id":"s1","name":"To do","baseGroup":"not_started","closedAs":null,"position":1}],
               "members":[{"id":"m1","name":"Việt","jiraAccountId":null,"githubLogin":"viet","slackUserId":null,"position":0,"archivedAt":null}],
               "fieldDefs":[{"key":"env","label":"Env","type":"select","options":["dev","prod"],"position":0}]}""",
        ),
    ) { server, api ->
        val s = api.taskProjectSettings("my app")
        assertEquals("/api/tasks/projects/my%20app/settings", server.takeRequest().target)
        assertEquals(listOf("s1", "s2"), s.statuses.map { it.id })
        assertEquals(45, s.checkIntervalMin)
        assertEquals("user", s.enabledBy)
        assertEquals("viet", s.members.single().githubLogin)
        assertEquals(listOf("dev", "prod"), s.fieldDefs.single().options)
    }

    @Test
    fun `a reminder posts the local moment and zone, and the list asks by task`() = withServer(
        MockResponse.Builder().code(201).body("""{"ok":true,"data":{"id":5}}""").build(),
        ok("""[]"""),
    ) { server, api ->
        api.taskAddReminder("t1", "2026-11-18T18:00", "Europe/London", " ")
        val r = server.takeRequest()
        assertEquals("/api/tasks/t1/reminders", r.target)
        val body = WireJson.parse(r.body!!.utf8())
        assertEquals("2026-11-18T18:00", body["at"].string)
        assertNull(body["note"])
        api.taskReminders("t1")
        assertEquals("/api/schedules?task=t1", server.takeRequest().target)
    }
}
