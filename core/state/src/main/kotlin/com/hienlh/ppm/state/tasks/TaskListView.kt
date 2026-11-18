package com.hienlh.ppm.state.tasks

import com.hienlh.ppm.client.TaskListQuery
import com.hienlh.ppm.protocol.TaskConst
import com.hienlh.ppm.protocol.TaskDto

/*
 * The Tasks list as pure functions (the web's `task-list-view.ts` and `ppm-tasks-list-logic.ts`):
 * which query the filters ask for, what the server cannot filter, the Now / Today / This week /
 * Later groups with children under their parent, folding one changed task into a list, and what a
 * drag means once dropped.
 */

/** A declared field's filter: only tasks whose [key] reads [value]. */
data class TaskFieldFilter(val key: String, val value: String)

/** The view's filters (`TaskViewFilters`). */
data class TaskViewFilters(
    val groups: List<String> = emptyList(),
    val types: List<String> = emptyList(),
    val person: String? = null,
    val tag: String? = null,
    val field: TaskFieldFilter? = null,
    val waitingOnMe: Boolean = false,
) {
    val isEmpty: Boolean get() = groups.isEmpty() && types.isEmpty() && person == null && tag == null && this.field == null && !waitingOnMe
}

/** The Tasks section's per-device view (`ppm:tasks-view`): [scope] `project` or `all`. */
data class TaskViewState(
    val scope: String = "project",
    val filters: TaskViewFilters = TaskViewFilters(),
    val query: String = "",
    val searchOpen: Boolean = false,
    val hiddenProjects: List<String> = emptyList(),
    val snoozedOpen: Boolean = false,
)

private fun idx(list: List<String>, v: String) = list.indexOf(v).let { if (it < 0) list.size else it }

/** "What to do first": effective urgency, importance, manual rank, project, number. */
val taskOrder: Comparator<TaskDto> = Comparator { a, b ->
    idx(TaskConst.URGENCIES, a.effectiveUrgency).compareTo(idx(TaskConst.URGENCIES, b.effectiveUrgency)).takeIf { it != 0 }
        ?: idx(TaskConst.IMPORTANCES, a.importance).compareTo(idx(TaskConst.IMPORTANCES, b.importance)).takeIf { it != 0 }
        ?: a.rank.compareTo(b.rank).takeIf { it != 0 }
        ?: a.projectKey.compareTo(b.projectKey).takeIf { it != 0 }
        ?: a.number.compareTo(b.number)
}

fun orderTasks(tasks: List<TaskDto>): List<TaskDto> = tasks.sortedWith(taskOrder)

/** The cell a task's rank is ordered within: its project, stored urgency and importance. */
fun cellKey(t: TaskDto): String = "${t.projectKey}\u0000${t.urgency}\u0000${t.importance}"

/** One stable key per distinct list query. */
fun listKey(q: TaskListQuery): String = listOf(
    q.project, q.groups.sorted().joinToString(","), q.types.sorted().joinToString(","), q.person ?: "", q.tag ?: "",
    q.q?.trim()?.lowercase() ?: "", if (q.closed) "1" else "0", if (q.snoozed) "z" else "", q.session ?: "",
).joinToString("|")

/** Whether a task belongs in a list asked for with [q] (the server's rules). */
fun matchesQuery(t: TaskDto, q: TaskListQuery): Boolean {
    if (t.deletedAt != null) return false
    if (q.project != "all" && t.project != q.project) return false
    val closedAsked = q.closed || "closed" in q.groups
    if (!closedAsked && t.baseGroup == "closed") return false
    if (!q.snoozed && t.snoozed) return false
    if (q.groups.isNotEmpty() && t.baseGroup !in q.groups) return false
    if (q.types.isNotEmpty() && t.type !in q.types) return false
    if (q.person != null && t.assigneeMemberId != q.person && t.waitingOnMemberId != q.person) return false
    if (q.tag != null && q.tag !in t.tags) return false
    if (q.session != null && t.workingSessionId != q.session) return false
    val text = q.q?.trim()?.lowercase()
    if (!text.isNullOrEmpty() && listOf(t.title, t.why, t.nextStep, t.notes).none { it?.lowercase()?.contains(text) == true }) return false
    return true
}

/** The list after one task changed: replaced, added if it now matches, dropped if not, re-ordered. */
fun foldTask(tasks: List<TaskDto>, changed: TaskDto, q: TaskListQuery): List<TaskDto> {
    val rest = tasks.filter { it.id != changed.id }
    return orderTasks(if (matchesQuery(changed, q)) rest + changed else rest)
}

/** Whether a change in [project] concerns a list asked for with [q]. */
fun listCovers(q: TaskListQuery, project: String): Boolean = q.project == "all" || q.project == project

/** The server query for a scope and the filters; a task-code search asks for the whole scope. */
fun listQueryFor(scope: String, project: String?, f: TaskViewFilters, q: String): TaskListQuery? {
    val name = if (scope == "all") "all" else project ?: return null
    if (parseTaskCodeQuery(q) != null) return TaskListQuery(name, closed = true, snoozed = true)
    return TaskListQuery(name, f.groups, f.types, f.person, f.tag, q.trim().ifEmpty { null }, closed = true, snoozed = true)
}

/** The scope's every task, filters left out: what child progress and the pickers count. */
fun countingQueryFor(scope: String, project: String?): TaskListQuery? = listQueryFor(scope, project, TaskViewFilters(), "")

private fun filtered(tasks: List<TaskDto>, f: TaskViewFilters, hidden: List<String>, scope: String): List<TaskDto> {
    val closedAsked = "closed" in f.groups
    return tasks.filter { t ->
        if (t.baseGroup == "closed" && !closedAsked) return@filter false
        if (scope == "all" && t.project in hidden) return@filter false
        if (f.waitingOnMe && !isWaitingOnMe(t)) return@filter false
        if (f.field != null && t.fieldText(f.field.key) != f.field.value) return@filter false
        true
    }
}

/** What the list shows; a task-code search shows that task alone, closed or snoozed or not. */
fun visibleTasks(tasks: List<TaskDto>, f: TaskViewFilters, hidden: List<String>, scope: String, q: String = ""): List<TaskDto> {
    val code = parseTaskCodeQuery(q)
    if (code != null) return tasks.filter { it.deletedAt == null && matchesTaskCode(it, code) }
    return filtered(tasks, f, hidden, scope).filter { !it.snoozed }
}

/** The "Snoozed N" fold: soonest day first, "until a link changes" last. */
fun snoozedTasks(tasks: List<TaskDto>, f: TaskViewFilters, hidden: List<String>, scope: String, q: String = ""): List<TaskDto> {
    if (parseTaskCodeQuery(q) != null) return emptyList()
    return filtered(tasks, f, hidden, scope).filter { it.snoozed }
        .sortedWith(compareBy<TaskDto> { it.snoozedUntil ?: "9999" }.thenBy { it.number })
}

fun waitingOnMeCount(tasks: List<TaskDto>, hidden: List<String>, scope: String): Int =
    tasks.count { it.deletedAt == null && !it.snoozed && isWaitingOnMe(it) && !(scope == "all" && it.project in hidden) }

data class ChildProgress(val done: Int, val total: Int)

/** Closed / all children per parent id. */
fun childProgress(tasks: List<TaskDto>): Map<String, ChildProgress> {
    val out = LinkedHashMap<String, ChildProgress>()
    for (t in tasks) {
        val parent = t.parentId ?: continue
        if (t.deletedAt != null) continue
        val p = out[parent] ?: ChildProgress(0, 0)
        out[parent] = ChildProgress(p.done + if (t.baseGroup == "closed") 1 else 0, p.total + 1)
    }
    return out
}

data class TaskListRow(val task: TaskDto, val depth: Int)

data class TaskListGroup(val urgency: String, val rows: List<TaskListRow>, val count: Int) {
    /** The group's top-level tasks, in order (what a drop indexes into). */
    val topTasks: List<TaskDto> get() = rows.filter { it.depth == 0 }.map { it.task }
}

/** Groups by effective urgency; a child whose parent is listed shows under it while it is expanded. */
fun groupTasks(tasks: List<TaskDto>, expanded: Set<String>): List<TaskListGroup> {
    val ordered = orderTasks(tasks)
    val ids = ordered.mapTo(HashSet()) { it.id }
    val childrenOf = LinkedHashMap<String, MutableList<TaskDto>>()
    for (t in ordered) if (t.parentId != null && t.parentId in ids) childrenOf.getOrPut(t.parentId!!) { mutableListOf() } += t
    return TaskConst.URGENCIES.map { urgency ->
        val top = ordered.filter { it.effectiveUrgency == urgency && !(it.parentId != null && it.parentId in ids) }
        val rows = buildList {
            for (t in top) {
                add(TaskListRow(t, 0))
                if (t.id in expanded) childrenOf[t.id].orEmpty().forEach { add(TaskListRow(it, 1)) }
            }
        }
        TaskListGroup(urgency, rows, top.size)
    }
}

/** What a drop asks for: [kind] `reorder` (before or after a neighbour), `urgency`, or `none` with a reason. */
data class DropPlan(val kind: String, val beforeId: String? = null, val afterId: String? = null, val urgency: String? = null, val reason: String? = null)

/** Dropping [dragged] at [index] of group [target]'s top-level rows. */
fun planDrop(dragged: TaskDto, target: String, groupRows: List<TaskDto>, index: Int): DropPlan {
    if (target != dragged.effectiveUrgency) {
        if (target == dragged.urgency) return DropPlan("none", reason = "Its due date keeps it more urgent: change the due date first")
        return DropPlan("urgency", urgency = target)
    }
    val rows = groupRows.filter { it.id != dragged.id }
    val cell = cellKey(dragged)
    val at = index.coerceIn(0, rows.size)
    rows.drop(at).firstOrNull { cellKey(it) == cell }?.let { return DropPlan("reorder", beforeId = it.id) }
    rows.take(at).lastOrNull { cellKey(it) == cell }?.let { return DropPlan("reorder", afterId = it.id) }
    return DropPlan("none", reason = "Order changes only among tasks of the same importance and project")
}

/** Whether a drop leaves the task where it is. */
fun dropIsNoop(dragged: TaskDto, groupRows: List<TaskDto>, plan: DropPlan): Boolean {
    if (plan.kind != "reorder") return plan.kind == "none"
    val ids = groupRows.map { it.id }
    val at = ids.indexOf(dragged.id)
    return if (plan.beforeId != null) ids.getOrNull(at + 1) == plan.beforeId else at > 0 && ids.getOrNull(at - 1) == plan.afterId
}

/**
 * The phone sheet's Move up / Move down: the neighbour in the same cell above or below, as the
 * web's action sheet asks for it; null when there is none.
 */
fun moveWithinCell(task: TaskDto, ordered: List<TaskDto>, up: Boolean): DropPlan? {
    val cell = ordered.filter { cellKey(it) == cellKey(task) && it.deletedAt == null }
    val at = cell.indexOfFirst { it.id == task.id }
    if (at < 0) return null
    return if (up) cell.getOrNull(at - 1)?.let { DropPlan("reorder", beforeId = it.id) } else cell.getOrNull(at + 1)?.let { DropPlan("reorder", afterId = it.id) }
}

/** The task strip at the top of a chat: the chat's open tasks, snoozed last. */
data class ChatTaskStripView(val primary: TaskDto, val all: List<TaskDto>, val extra: Int)

fun chatTasksQuery(sessionId: String) = TaskListQuery("all", snoozed = true, session = sessionId)

fun chatTaskStripView(tasks: List<TaskDto>, sessionId: String): ChatTaskStripView? {
    val mine = tasks.filter { it.deletedAt == null && it.baseGroup != "closed" && it.workingSessionId == sessionId }
    if (mine.isEmpty()) return null
    val ordered = orderTasks(mine)
    val all = ordered.filter { !it.snoozed } + ordered.filter { it.snoozed }
    return ChatTaskStripView(all[0], all, all.size - 1)
}

fun chatTaskStripLabel(v: ChatTaskStripView): String =
    "This chat's task: #${v.primary.number} ${v.primary.title}, ${v.primary.statusName}${if (v.extra > 0) ", and ${v.extra} more" else ""}"

/** The first [limit] open tasks in "what to do first" order (the manager chip). */
fun whatToDoFirst(tasks: List<TaskDto>, limit: Int = 10): List<TaskDto> =
    orderTasks(tasks.filter { it.baseGroup != "closed" && it.deletedAt == null && !it.snoozed }).take(limit)

/** Which write a move to [statusId] is (`statusMove`): `none`, `close`, `reopen` or `update`. */
fun statusMove(task: TaskDto, statusId: String, statuses: List<com.hienlh.ppm.protocol.TaskStatusDef>?): String = when {
    statusId == task.statusId -> "none"
    statuses?.firstOrNull { it.id == statusId }?.baseGroup == "closed" -> "close"
    task.baseGroup == "closed" -> "reopen"
    else -> "update"
}
