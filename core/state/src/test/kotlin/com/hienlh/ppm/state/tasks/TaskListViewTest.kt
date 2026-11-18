package com.hienlh.ppm.state.tasks

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.client.TaskListQuery
import com.hienlh.ppm.protocol.TaskDetail
import com.hienlh.ppm.protocol.TaskListResponse
import com.hienlh.ppm.protocol.TaskWriteResult
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.GlobalEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The list's pure rules (`task-list-view.ts`, `ppm-tasks-list-logic.ts`) and the store (`ppm-tasks-store-core.ts`). */
class TaskListViewTest {
    @Test
    fun `tasks order by urgency, importance, rank, project then number`() {
        val list = listOf(
            task(id = "c", number = 3, effectiveUrgency = "today"),
            task(id = "a", number = 1, effectiveUrgency = "now", importance = "nice"),
            task(id = "b", number = 2, effectiveUrgency = "now", importance = "must", rank = 5.0),
            task(id = "d", number = 4, effectiveUrgency = "now", importance = "must", rank = 1.0),
        )
        assertEquals(listOf("d", "b", "a", "c"), orderTasks(list).map { it.id })
    }

    @Test
    fun `a changed task is folded into the lists it still belongs to`() {
        val q = TaskListQuery("ppm")
        val list = listOf(task(id = "a", number = 1), task(id = "b", number = 2))
        assertEquals(listOf("a"), foldTask(list, task(id = "b", number = 2, baseGroup = "closed"), q).map { it.id })
        assertEquals(listOf("c", "a", "b"), foldTask(list, task(id = "c", number = 3, effectiveUrgency = "now"), q).map { it.id })
        assertEquals(listOf("a", "b"), foldTask(list, task(id = "c", project = "other"), q).map { it.id })
        assertTrue(matchesQuery(task(title = "Fix login"), TaskListQuery("ppm", q = "LOGIN")))
    }

    @Test
    fun `visible tasks leave closed and snoozed out, and a code shows only its task`() {
        val tasks = listOf(
            task(id = "a", number = 1),
            task(id = "b", number = 2, baseGroup = "closed"),
            task(id = "c", number = 3, snoozed = true, snoozedUntil = "2026-11-20"),
            task(id = "d", number = 4, snoozed = true),
            task(id = "e", number = 5, waitingOnMemberId = "me", baseGroup = "active", fields = mapOf("env" to JsonPrimitive("prod"))),
            task(id = "f", number = 6, project = "web"),
        )
        val none = TaskViewFilters()
        assertEquals(listOf("a", "e", "f"), visibleTasks(tasks, none, emptyList(), "all").map { it.id })
        assertEquals(listOf("a", "e"), visibleTasks(tasks, none, listOf("web"), "all").map { it.id })
        assertEquals(listOf("e"), visibleTasks(tasks, none.copy(waitingOnMe = true), emptyList(), "all").map { it.id })
        assertEquals(listOf("e"), visibleTasks(tasks, none.copy(field = TaskFieldFilter("env", "prod")), emptyList(), "all").map { it.id })
        assertEquals(listOf("b"), visibleTasks(tasks, none, emptyList(), "all", "#2").map { it.id })
        assertEquals(listOf("c", "d"), snoozedTasks(tasks, none, emptyList(), "all").map { it.id })
        assertEquals(emptyList<String>(), snoozedTasks(tasks, none, emptyList(), "all", "ppm/3").map { it.id })
        assertEquals(1, waitingOnMeCount(tasks, emptyList(), "all"))
    }

    @Test
    fun `groups nest children under an expanded parent and count top rows only`() {
        val tasks = listOf(
            task(id = "p", number = 1, effectiveUrgency = "now"),
            task(id = "c1", number = 2, effectiveUrgency = "later", parentId = "p"),
            task(id = "c2", number = 3, effectiveUrgency = "now", parentId = "p", baseGroup = "closed"),
            task(id = "x", number = 4, effectiveUrgency = "today", parentId = "missing"),
        )
        val closed = groupTasks(tasks, emptySet())
        assertEquals(listOf("now", "today", "week", "later"), closed.map { it.urgency })
        assertEquals(listOf("p"), closed[0].rows.map { it.task.id })
        assertEquals(listOf("x"), closed[1].rows.map { it.task.id })
        val open = groupTasks(tasks, setOf("p"))
        assertEquals(listOf("p" to 0, "c2" to 1, "c1" to 1), open[0].rows.map { it.task.id to it.depth })
        assertEquals(1, open[0].count)
        assertEquals(ChildProgress(1, 2), childProgress(tasks)["p"])
    }

    @Test
    fun `a drop reorders within the cell, changes urgency across groups, or says why not`() {
        val a = task(id = "a", number = 1, rank = 1.0)
        val b = task(id = "b", number = 2, rank = 2.0)
        val other = task(id = "o", number = 3, importance = "must")
        val c = task(id = "c", number = 4, rank = 3.0)
        val rows = listOf(other, a, b, c)
        assertEquals(DropPlan("reorder", beforeId = "a"), planDrop(c, "week", rows, 1))
        assertEquals(DropPlan("reorder", afterId = "c"), planDrop(a, "week", rows, 3))
        assertEquals(DropPlan("urgency", urgency = "now"), planDrop(a, "now", emptyList(), 0))
        val raised = task(id = "r", urgency = "week", effectiveUrgency = "now")
        assertEquals("Its due date keeps it more urgent: change the due date first", planDrop(raised, "week", rows, 0).reason)
        assertEquals("Order changes only among tasks of the same importance and project", planDrop(other, "week", listOf(other, a), 1).reason)
        assertTrue(dropIsNoop(a, rows, DropPlan("reorder", beforeId = "b")))
        assertTrue(dropIsNoop(b, rows, DropPlan("reorder", afterId = "a")))
        assertEquals(DropPlan("reorder", beforeId = "a"), moveWithinCell(b, rows, up = true))
        assertEquals(DropPlan("reorder", afterId = "c"), moveWithinCell(b, rows, up = false))
        assertNull(moveWithinCell(other, rows, up = true))
    }

    @Test
    fun `the chat strip leads with the first open task, snoozed ones last`() {
        val tasks = listOf(
            task(id = "s", number = 1, effectiveUrgency = "now", snoozed = true, workingSessionId = "c"),
            task(id = "a", number = 2, effectiveUrgency = "later", workingSessionId = "c"),
            task(id = "z", number = 3, workingSessionId = "c", baseGroup = "closed"),
            task(id = "o", number = 4, workingSessionId = "other"),
        )
        val v = chatTaskStripView(tasks, "c")!!
        assertEquals(listOf("a", "s"), v.all.map { it.id })
        assertEquals(1, v.extra)
        assertEquals("This chat's task: #2 Task 2, To do, and 1 more", chatTaskStripLabel(v))
        assertNull(chatTaskStripView(tasks, "nobody"))
    }

    @Test
    fun `status moves close, reopen or update`() {
        val statuses = listOf(
            com.hienlh.ppm.protocol.TaskStatusDef("s", "To do", "not_started", null, 0),
            com.hienlh.ppm.protocol.TaskStatusDef("d", "Done", "closed", "done", 1),
            com.hienlh.ppm.protocol.TaskStatusDef("a", "Active", "active", null, 2),
        )
        assertEquals("none", statusMove(task(), "s", statuses))
        assertEquals("close", statusMove(task(), "d", statuses))
        assertEquals("update", statusMove(task(), "a", statuses))
        assertEquals("reopen", statusMove(task(baseGroup = "closed"), "a", statuses))
    }

    @Test
    fun `the store folds a write, and a stale write re-reads and toasts`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        var lists = 0
        var gets = 0
        val toasts = mutableListOf<String>()
        val store = TasksStore(
            scope,
            list = { lists++; TaskListResponse(listOf(task(id = "a", number = 1), task(id = "b", number = 2)), emptyList()) },
            get = { id -> gets++; TaskDetail(task(id = id), emptyList(), emptyList(), null) },
            toast = { toasts += it },
        )
        val q = TaskListQuery("ppm", closed = true)
        val stop = store.watchList(q)
        yield()
        assertEquals(1, lists)
        assertEquals(listOf("a", "b"), store.lists.value[listKey(q)]!!.data!!.tasks.map { it.id })
        store.write("b") { TaskWriteResult(task(id = "b", number = 2, effectiveUrgency = "now"), false) }
        assertEquals(listOf("b", "a"), store.lists.value[listKey(q)]!!.data!!.tasks.map { it.id })
        val answered = store.write("b") { throw PpmError.Server(409, "stale", code = "stale") }
        assertNull(answered)
        assertEquals(listOf(TasksStore.STALE_TOAST), toasts)
        assertEquals(2, lists)
        // A change in a project nobody watches drops the list rather than re-reading it.
        stop()
        store.onTasksChanged("ppm", listOf("b"))
        assertTrue(store.lists.value.isEmpty())
        assertEquals(2, lists)
        assertEquals(0, gets)
    }

    @Test
    fun `tasks pushes on the global socket reach their listener`() {
        val events = GlobalEvents(ApiClient("http://127.0.0.1:9/".toHttpUrl(), "t"), CoroutineScope(Job()))
        val seen = mutableListOf<String?>()
        val off = events.onTasksEvent { seen += it["type"].string }
        events.handle(WireJson.parse("""{"type":"tasks:changed","project":"ppm","taskIds":["a"]}"""))
        events.handle(WireJson.parse("""{"type":"tasks:project_changed","project":"ppm"}"""))
        events.handle(WireJson.parse("""{"type":"tasks:link_states","project":"ppm"}"""))
        off()
        events.handle(WireJson.parse("""{"type":"tasks:changed","project":"ppm","taskIds":[]}"""))
        assertEquals(listOf("tasks:changed", "tasks:project_changed", "tasks:link_states"), seen)
    }
}
