package com.hienlh.ppm.state.tasks

import com.hienlh.ppm.client.TaskListQuery
import com.hienlh.ppm.client.isStaleTask
import com.hienlh.ppm.protocol.TaskDetail
import com.hienlh.ppm.protocol.TaskDto
import com.hienlh.ppm.protocol.TaskListResponse
import com.hienlh.ppm.protocol.TaskWriteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The PPM Tasks store (the web's `ppm-tasks-store-core.ts`): lists kept per query, details per
 * task. A write's answer is folded into every cached list and detail at once; a `/ws/global`
 * change re-reads only what someone is looking at and drops the rest. A write that hit a newer
 * revision is never retried: the store re-reads and says [STALE_TOAST].
 */
class TasksStore(
    private val scope: CoroutineScope,
    private val list: suspend (TaskListQuery) -> TaskListResponse,
    private val get: suspend (String) -> TaskDetail,
    private val toast: (String) -> Unit,
) {
    data class ListEntry(val query: TaskListQuery, val data: TaskListResponse?, val loading: Boolean, val error: String?, val watchers: Int)

    data class DetailEntry(val data: TaskDetail?, val loading: Boolean, val error: String?, val watchers: Int)

    private val _lists = MutableStateFlow<Map<String, ListEntry>>(emptyMap())
    val lists: StateFlow<Map<String, ListEntry>> = _lists.asStateFlow()

    private val _details = MutableStateFlow<Map<String, DetailEntry>>(emptyMap())
    val details: StateFlow<Map<String, DetailEntry>> = _details.asStateFlow()

    /** Each read is numbered per list or task; a write's answer also counts, so a read sent before it loses. */
    private val asks = HashMap<String, Int>()

    private fun nextAsk(key: String): Int = ((asks[key] ?: 0) + 1).also { asks[key] = it }

    private fun patchList(key: String, f: (ListEntry) -> ListEntry) = _lists.update { m -> m[key]?.let { m + (key to f(it)) } ?: m }

    private fun patchDetail(id: String, f: (DetailEntry) -> DetailEntry) = _details.update { m -> m[id]?.let { m + (id to f(it)) } ?: m }

    /** Starts showing [query]; the answer is fetched unless already held. Call the result to stop. */
    fun watchList(query: TaskListQuery): () -> Unit {
        val key = listKey(query)
        val had = _lists.value[key]
        _lists.update { it + (key to (had?.copy(watchers = had.watchers + 1) ?: ListEntry(query, null, false, null, 1))) }
        if (had?.data == null && had?.loading != true) scope.launch { fetchList(query) }
        return { patchList(key) { it.copy(watchers = maxOf(0, it.watchers - 1)) } }
    }

    fun watchDetail(id: String): () -> Unit {
        val had = _details.value[id]
        _details.update { it + (id to (had?.copy(watchers = had.watchers + 1) ?: DetailEntry(null, false, null, 1))) }
        if (had?.data == null && had?.loading != true) scope.launch { fetchDetail(id) }
        return { patchDetail(id) { it.copy(watchers = maxOf(0, it.watchers - 1)) } }
    }

    suspend fun fetchList(query: TaskListQuery) {
        val key = listKey(query)
        if (_lists.value[key] == null) _lists.update { it + (key to ListEntry(query, null, false, null, 0)) }
        patchList(key) { it.copy(loading = true) }
        val asked = nextAsk("list:$key")
        try {
            val data = list(query)
            if (asks["list:$key"] == asked) patchList(key) { it.copy(data = data, loading = false, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (asks["list:$key"] == asked) patchList(key) { it.copy(loading = false, error = e.message ?: e.toString()) }
        }
    }

    suspend fun fetchDetail(id: String) {
        if (_details.value[id] == null) _details.update { it + (id to DetailEntry(null, false, null, 0)) }
        patchDetail(id) { it.copy(loading = true) }
        val asked = nextAsk("task:$id")
        try {
            val data = get(id)
            if (asks["task:$id"] == asked) patchDetail(id) { it.copy(data = data, loading = false, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (asks["task:$id"] == asked) patchDetail(id) { it.copy(loading = false, error = e.message ?: e.toString()) }
        }
    }

    /** Folds a task the server just answered with into every cached list and detail. */
    fun applyTask(task: TaskDto) {
        if (_details.value[task.id]?.data != null) nextAsk("task:${task.id}")
        for ((key, entry) in _lists.value) if (entry.data != null && listCovers(entry.query, task.project)) nextAsk("list:$key")
        _lists.update { m ->
            m.mapValues { (_, e) ->
                val data = e.data
                if (data != null && listCovers(e.query, task.project)) e.copy(loading = false, data = data.copy(tasks = foldTask(data.tasks, task, e.query))) else e
            }
        }
        _details.update { m ->
            m.mapValues { (id, e) ->
                val data = e.data ?: return@mapValues e
                when {
                    id == task.id -> e.copy(loading = false, data = data.copy(task = task))
                    data.children.any { it.id == task.id } -> e.copy(data = data.copy(children = data.children.map { if (it.id == task.id) task else it }))
                    else -> e
                }
            }
        }
    }

    /**
     * Runs a write: its answer is applied; a stale write re-reads and toasts instead, answering
     * null. Every other failure is thrown to the caller.
     */
    suspend fun write(taskId: String?, run: suspend () -> TaskWriteResult): TaskWriteResult? {
        try {
            val answer = run()
            applyTask(answer.task)
            return answer
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!e.isStaleTask) throw e
            val project = taskId?.let { id -> _details.value[id]?.data?.task?.project ?: findProject(id) }
            if (taskId != null) refreshDetail(taskId)
            if (project != null) {
                refreshLists(project)
            } else {
                _lists.value.values.filter { it.watchers > 0 }.forEach { scope.launch { fetchList(it.query) } }
            }
            toast(STALE_TOAST)
            return null
        }
    }

    fun onTasksChanged(project: String, taskIds: List<String>) {
        refreshLists(project)
        taskIds.forEach(::refreshDetail)
        for ((id, e) in _details.value) {
            if (id !in taskIds && e.data?.children?.any { it.id in taskIds } == true) refreshDetail(id)
        }
    }

    fun onProjectChanged(project: String) {
        refreshLists(project)
        for ((id, e) in _details.value) if (e.data?.task?.project == project) refreshDetail(id)
    }

    /** After a lost connection: re-read what is shown, forget what is not. */
    fun resync() {
        val keptLists = _lists.value.filterValues { it.watchers > 0 }
        val keptDetails = _details.value.filterValues { it.watchers > 0 }
        _lists.value = keptLists
        _details.value = keptDetails
        keptLists.values.forEach { scope.launch { fetchList(it.query) } }
        keptDetails.keys.forEach { scope.launch { fetchDetail(it) } }
    }

    /** Every task any cached list or detail holds, by id: the latest copy the app has. */
    fun findTask(id: String): TaskDto? =
        _details.value[id]?.data?.task ?: _lists.value.values.firstNotNullOfOrNull { e -> e.data?.tasks?.firstOrNull { it.id == id } }

    private fun findProject(taskId: String): String? = findTask(taskId)?.project

    private fun refreshLists(project: String) {
        val refetch = mutableListOf<TaskListQuery>()
        _lists.update { m ->
            m.filter { (_, e) ->
                if (!listCovers(e.query, project)) return@filter true
                if (e.watchers > 0) refetch += e.query
                e.watchers > 0
            }
        }
        refetch.forEach { scope.launch { fetchList(it) } }
    }

    private fun refreshDetail(id: String) {
        val e = _details.value[id] ?: return
        if (e.watchers > 0) scope.launch { fetchDetail(id) } else _details.update { it - id }
    }

    companion object {
        const val STALE_TOAST = "Changed elsewhere — refreshed"
    }
}
