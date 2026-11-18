package com.hienlh.ppm.app.ui.tasks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.tasks
import com.hienlh.ppm.client.TaskListQuery
import com.hienlh.ppm.client.taskAddLink
import com.hienlh.ppm.client.taskClose
import com.hienlh.ppm.client.taskHistory
import com.hienlh.ppm.client.taskLinkStates
import com.hienlh.ppm.client.taskPatch
import com.hienlh.ppm.client.taskRemoveLink
import com.hienlh.ppm.client.taskReopen
import com.hienlh.ppm.client.taskReorder
import com.hienlh.ppm.client.taskUndo
import com.hienlh.ppm.protocol.TaskDto
import com.hienlh.ppm.protocol.TaskHistoryItem
import com.hienlh.ppm.protocol.TaskLink
import com.hienlh.ppm.protocol.TaskLiveParts
import com.hienlh.ppm.protocol.TaskProjectSettings
import com.hienlh.ppm.protocol.TaskStatusDef
import com.hienlh.ppm.state.tasks.SnoozePatch
import com.hienlh.ppm.state.tasks.TasksStore
import com.hienlh.ppm.state.tasks.UNSNOOZE_PATCH
import com.hienlh.ppm.state.tasks.listKey
import com.hienlh.ppm.state.tasks.localYmd
import com.hienlh.ppm.state.tasks.snoozeToastText
import com.hienlh.ppm.state.tasks.statusMove
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Every task view's writes and navigation (`useTaskWrite` and the `open*` helpers), so the views
 * only draw: the app answers them through the model, a test records them.
 */
interface TaskActions {
    /** A row or a child picked: a window on a tablet, the task's sheet on a phone; [keep] keeps its tab. */
    fun open(task: TaskDto, keep: Boolean)

    /** A task known only by id (a parent). */
    fun openById(id: String, project: String)

    fun openChat(task: TaskDto, sessionId: String)

    fun startChat(task: TaskDto)

    /** A snooze or an unsnooze; [announce] says where the task went (the list's toast with Unsnooze). */
    fun snooze(task: TaskDto, patch: SnoozePatch, announce: Boolean)

    fun moveUrgency(task: TaskDto, urgency: String)

    fun reorder(task: TaskDto, beforeId: String?, afterId: String?)

    fun setStatus(task: TaskDto, statusId: String, statuses: List<TaskStatusDef>?)

    /** A `TaskPatch` (null clears a field). */
    fun patch(task: TaskDto, patch: JsonObject)

    fun close(task: TaskDto)

    fun reopen(task: TaskDto)

    fun decide(task: TaskDto, optionId: String)

    fun addLink(task: TaskDto, kind: String, ref: String, done: (Boolean) -> Unit)

    fun removeLink(task: TaskDto, link: TaskLink)

    fun undo(task: TaskDto, entry: TaskHistoryItem, done: () -> Unit)

    fun askManager(task: TaskDto)

    fun copyLink(task: TaskDto)

    fun info(text: String)
}

/** One field set or cleared, as a patch. */
internal fun patchOf(key: String, value: String?): JsonObject = buildJsonObject { put(key, value?.let(::JsonPrimitive) ?: JsonNull) }

internal fun snoozeJson(p: SnoozePatch): JsonObject = buildJsonObject {
    put("snoozedUntil", p.snoozedUntil?.let(::JsonPrimitive) ?: JsonNull)
    put("snoozeUntilChange", p.snoozeUntilChange)
}

/** The model's answers to [TaskActions]; [onOpen] decides how a picked task opens. */
class AppTaskActions(private val model: AppModel, private val clipboard: (String) -> Unit, private val onOpen: ((TaskDto, Boolean) -> Unit)? = null) : TaskActions {
    private val tasks get() = model.tasks

    /** The task as the store holds it now: two writes in a row each carry the newest revision. */
    private fun fresh(t: TaskDto): TaskDto = tasks.store?.findTask(t.id)?.takeIf { it.revision >= t.revision } ?: t

    override fun open(task: TaskDto, keep: Boolean) {
        onOpen?.invoke(task, keep) ?: tasks.openTask(task.id, task.project, task.title, task.number, keep)
    }

    override fun openById(id: String, project: String) = tasks.openTask(id, project, "Task", null, keep = true)

    override fun openChat(task: TaskDto, sessionId: String) {
        val provider = task.workingSession.session?.takeIf { it.sessionId == sessionId }?.providerId
        tasks.openChat(task.project, sessionId, provider, task.workingSession.session?.title)
    }

    override fun startChat(task: TaskDto) = tasks.startChatAsking(fresh(task))

    override fun snooze(task: TaskDto, patch: SnoozePatch, announce: Boolean) {
        val t = fresh(task)
        tasks.write(t.id, { api -> api.taskPatch(t.id, t.revision, snoozeJson(patch)) }) { r ->
            if (!announce) return@write
            val text = snoozeToastText(r.task, localYmd())
            if (r.task.snoozed) {
                model.toast(
                    ErrorToastMessage(
                        text,
                        action = ErrorToastMessage.Action("Unsnooze") { snooze(r.task, UNSNOOZE_PATCH, announce = false) },
                        kind = ErrorToastMessage.Kind.SUCCESS,
                    ),
                )
            } else {
                model.toast(ErrorToastMessage(text, kind = ErrorToastMessage.Kind.SUCCESS))
            }
        }
    }

    override fun moveUrgency(task: TaskDto, urgency: String) = patch(task, patchOf("urgency", urgency))

    override fun reorder(task: TaskDto, beforeId: String?, afterId: String?) {
        val t = fresh(task)
        tasks.write(t.id, { api -> api.taskReorder(t.id, t.revision, beforeId, afterId) })
    }

    override fun setStatus(task: TaskDto, statusId: String, statuses: List<TaskStatusDef>?) {
        val t = fresh(task)
        when (statusMove(t, statusId, statuses)) {
            "close" -> tasks.write(t.id, { api -> api.taskClose(t.id, t.revision, statusId = statusId) })
            "reopen" -> tasks.write(t.id, { api -> api.taskReopen(t.id, t.revision, statusId) })
            "update" -> patch(t, patchOf("statusId", statusId))
        }
    }

    override fun patch(task: TaskDto, patch: JsonObject) {
        val t = fresh(task)
        tasks.keepTab(t.id)
        tasks.write(t.id, { api -> api.taskPatch(t.id, t.revision, patch) })
    }

    override fun close(task: TaskDto) {
        val t = fresh(task)
        tasks.write(t.id, { api -> api.taskClose(t.id, t.revision, closedAs = "done") })
    }

    override fun reopen(task: TaskDto) {
        val t = fresh(task)
        tasks.write(t.id, { api -> api.taskReopen(t.id, t.revision) })
    }

    override fun decide(task: TaskDto, optionId: String) = tasks.decide(fresh(task), optionId)

    override fun addLink(task: TaskDto, kind: String, ref: String, done: (Boolean) -> Unit) {
        val t = fresh(task)
        val api = model.api ?: return done(false)
        val store = tasks.store ?: return done(false)
        model.scope.launch {
            val ok = try {
                store.write(t.id) { api.taskAddLink(t.id, t.revision, kind, ref) } != null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tasks.error(e.message?.ifBlank { null } ?: "Could not add the link")
                false
            }
            done(ok)
        }
    }

    override fun removeLink(task: TaskDto, link: TaskLink) {
        val t = fresh(task)
        tasks.write(t.id, { api -> api.taskRemoveLink(t.id, t.revision, link) })
    }

    override fun undo(task: TaskDto, entry: TaskHistoryItem, done: () -> Unit) {
        tasks.write(task.id, { api -> api.taskUndo(entry.id) }) { done() }
    }

    override fun askManager(task: TaskDto) = tasks.askManager(task)

    override fun copyLink(task: TaskDto) = tasks.copyLink(clipboard, task)

    override fun info(text: String) = tasks.info(text)
}

/** The app's [TaskActions] for this screen. */
@Composable
fun rememberTaskActions(model: AppModel, onOpen: ((TaskDto, Boolean) -> Unit)? = null): TaskActions {
    val clipboard = LocalClipboardManager.current
    return remember(model, onOpen) { AppTaskActions(model, { clipboard.setText(AnnotatedString(it)) }, onOpen) }
}

/** A list the store keeps current while it is shown (`usePpmTaskList`); null query shows nothing. */
@Composable
fun rememberTaskList(model: AppModel, query: TaskListQuery?): TasksStore.ListEntry? {
    val store = model.tasks.store ?: return null
    val lists by store.lists.collectAsState()
    val key = query?.let(::listKey)
    DisposableEffect(store, key) {
        val stop = query?.let(store::watchList)
        onDispose { stop?.invoke() }
    }
    return key?.let { lists[it] }
}

/** One task's detail, kept current while shown (`usePpmTask`). */
@Composable
fun rememberTaskDetail(model: AppModel, id: String?): TasksStore.DetailEntry? {
    val store = model.tasks.store ?: return null
    val details by store.details.collectAsState()
    DisposableEffect(store, id) {
        val stop = id?.let(store::watchDetail)
        onDispose { stop?.invoke() }
    }
    return id?.let { details[it] }
}

/** The project's statuses, members and fields, read once it is shown. */
@Composable
fun rememberTaskSettings(model: AppModel, project: String?): TaskProjectSettings? {
    val tasks = model.tasks
    LaunchedEffect(model.api, project) {
        if (project != null && project !in tasks.settings) tasks.loadSettings(project)
    }
    return project?.let { tasks.settings[it] }
}

/** Live link states and working chats of [ids], again on every `tasks:link_states` (`usePpmTaskLinkStates`). */
@Composable
fun rememberLinkStates(model: AppModel, ids: List<String>): Map<String, TaskLiveParts> {
    val key = remember(ids) { ids.sorted() }
    val tick = model.tasks.linkTick
    val api = model.api
    val states by produceState(emptyMap<String, TaskLiveParts>(), api, key, tick) {
        if (api == null || key.isEmpty()) {
            value = emptyMap()
            return@produceState
        }
        try {
            value = api.taskLinkStates(key)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The states stay as they were.
        }
    }
    return states
}

/** One page of a task's history; read again when the task's revision moves. */
data class TaskHistoryPage(val items: List<TaskHistoryItem>, val loading: Boolean, val error: String?)

@Composable
fun rememberTaskHistory(model: AppModel, taskId: String, filter: String, revision: Int): TaskHistoryPage {
    val api = model.api
    val page by produceState(TaskHistoryPage(emptyList(), true, null), api, taskId, filter, revision) {
        if (api == null) return@produceState
        value = value.copy(loading = true)
        value = try {
            TaskHistoryPage(api.taskHistory(taskId, filter), false, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TaskHistoryPage(value.items, false, e.message ?: "Could not read the history")
        }
    }
    return page
}
