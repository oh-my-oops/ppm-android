package com.hienlh.ppm.app

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.client.isNoTaskSession
import com.hienlh.ppm.client.taskDecide
import com.hienlh.ppm.client.taskDetail
import com.hienlh.ppm.client.taskProjectSettings
import com.hienlh.ppm.client.taskProjects
import com.hienlh.ppm.client.taskStartChat
import com.hienlh.ppm.client.tasksList
import com.hienlh.ppm.client.tasksNotifyManager
import com.hienlh.ppm.protocol.TaskDto
import com.hienlh.ppm.state.tasks.TaskChatButtons
import com.hienlh.ppm.state.tasks.TaskChatRef
import com.hienlh.ppm.state.tasks.TaskManagerLike
import com.hienlh.ppm.state.tasks.taskChatButtons
import com.hienlh.ppm.state.tasks.taskManagerChat
import com.hienlh.ppm.protocol.TaskProjectSettings
import com.hienlh.ppm.protocol.TaskProjectSummary
import com.hienlh.ppm.protocol.TaskWriteResult
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.GlobalEvents
import com.hienlh.ppm.state.tasks.TaskFieldFilter
import com.hienlh.ppm.state.tasks.TaskViewFilters
import com.hienlh.ppm.state.tasks.TaskViewState
import com.hienlh.ppm.state.tasks.TasksStore
import com.hienlh.ppm.state.tasks.decisionDeliveredMessage
import com.hienlh.ppm.state.tasks.notifyOutcomeMessage
import com.hienlh.ppm.state.tasks.railNowCount
import com.hienlh.ppm.state.workspace.isWindowPanelId
import com.hienlh.ppm.state.windows.MAX_WINDOWS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.WeakHashMap

/**
 * PPM Tasks on this model: the store of lists and details (`ppm-tasks-store.ts`), the Tasks
 * section's per-device view (`ppm-tasks-view-store.ts`), each project's task settings, the
 * switched-on projects with their counts, and the writes every task view makes with what goes
 * around them — the stale re-read, the failure toast and "Tell the manager now?". Main thread
 * only, as the model is.
 */
/** A task as its opener knows it, before it has been read (`TaskTabTarget`). */
data class TaskTarget(val id: String, val project: String, val title: String, val number: Int?)

@Stable
class AppTasks internal constructor(private val model: AppModel) {
    private var built: Pair<ApiClient, TasksStore>? = null

    /** The store for the open server; a new one when the server changes. Null while not connected. */
    val store: TasksStore?
        get() {
            val api = model.api ?: return null
            built?.takeIf { it.first === api }?.let { return it.second }
            val next = TasksStore(model.scope, { q -> api.tasksList(q) }, { id -> api.taskDetail(id) }, ::info)
            val replaced = built != null
            built = api to next
            // Another server: what was read from the one before is not this one's.
            if (replaced) {
                projects = emptyList()
                settings.clear()
                refreshProjects()
            }
            return next
        }

    /** Every switched-on project with its counts (`GET /api/tasks/projects`), for the rail and the pickers. */
    var projects by mutableStateOf<List<TaskProjectSummary>>(emptyList())
        private set

    /** Each project's statuses, members and fields, read once it is shown and again when PPM says it changed. */
    val settings = mutableStateMapOf<String, TaskProjectSettings>()

    /** The Tasks section's view, per device (`ppm:tasks-view`). */
    var view by mutableStateOf(TaskViewState())
        private set

    /** Parents whose children are shown under them in the list. */
    var expanded by mutableStateOf(emptySet<String>())

    /**
     * The manager chats (`GET /api/assistant/managers`): which chat is its project's manager. Null
     * until read. Manager chats are ported separately; [managerChatsSource] is the hook that part
     * replaces with its own shared list.
     */
    var managers by mutableStateOf<List<ManagerChatRef>?>(null)
        private set

    /** Where [managers] comes from; the manager-chats port may point it at its own list. */
    var managerChatsSource: suspend (ApiClient) -> List<ManagerChatRef>? = ::readManagerChats

    /** Bumped on `tasks:link_states`: every shown list re-reads its tasks' live states. */
    var linkTick by mutableStateOf(0)
        private set

    /** A question a task view asks before it acts (`useTaskConfirm`); shown by `TaskConfirmHost`. */
    data class Confirm(val title: String, val body: String, val confirm: String, val destructive: Boolean, val onYes: () -> Unit)

    var confirm by mutableStateOf<Confirm?>(null)

    /** Start chat, asking first when it would replace a chat that still exists. */
    fun startChatAsking(task: TaskDto) = startChat(task, confirmReplace = { yes ->
        val title = task.workingSession.session?.title?.trim()?.ifEmpty { null } ?: "A chat"
        confirm = Confirm(
            "Start a new chat for this task?",
            "\"$title\" works on ${task.ref} now. The new chat takes its place as the working session; the old chat is kept and keeps running.",
            "Start new chat",
            destructive = false,
            onYes = yes,
        )
    })

    data class ManagerChatRef(val sessionId: String, val projectName: String, val providerId: String?)

    /** The task whose quick view is open (a chat's strip, or a task chip in a message); drawn by `TaskPeekHost`. */
    var peek by mutableStateOf<TaskTarget?>(null)

    /** "Show in sidebar" (`revealTaskInSidebar`, `openList`): the Tasks section, scoped so [project] shows. */
    fun revealInSidebar(project: String) {
        val v = view
        val scope = if (v.scope == "project" && model.selectedProject == project) "project" else "all"
        val unhide = scope == "all" && project in v.hiddenProjects
        if (scope != v.scope || unhide) updateView { it.copy(scope = scope, hiddenProjects = if (unhide) it.hiddenProjects - project else it.hiddenProjects) }
        model.showSidebarSection("tasks")
    }

    private var prefs: KeyValueStore? = null
    private var boundEvents: GlobalEvents? = null
    private var bound = false
    private var unbind: (() -> Unit)? = null
    private val notifyAsk = HashMap<String, Job>()

    /** Reads this device's view once, from [store]. */
    fun attachPrefs(store: KeyValueStore) {
        if (prefs != null) return
        prefs = store
        view = readView(store.get(VIEW_KEY)) ?: view
    }

    fun updateView(change: (TaskViewState) -> TaskViewState) {
        view = change(view)
        prefs?.put(VIEW_KEY, writeView(view))
    }

    /** Hears `/ws/global` while a task view is shown; idempotent per socket. */
    fun bindEvents(events: GlobalEvents?) {
        if (bound && events === boundEvents) return
        bound = true
        unbind?.invoke()
        boundEvents = events
        if (events == null) {
            // No socket yet: the counts and the manager list are still read once.
            unbind = null
            refreshProjects()
            refreshManagers()
            return
        }
        val offTasks = events.onTasksEvent { frame ->
            val project = frame["project"].string ?: return@onTasksEvent
            val s = store ?: return@onTasksEvent
            when (frame["type"].string) {
                "tasks:changed" -> s.onTasksChanged(project, frame["taskIds"].array.orEmpty().mapNotNull { it.string })
                // Only what is shown re-reads its link states (`usePpmTaskLinkStates`).
                "tasks:link_states" -> {
                    linkTick++
                    return@onTasksEvent
                }
                else -> {
                    s.onProjectChanged(project)
                    if (project in settings) loadSettings(project)
                }
            }
            refreshProjects()
        }
        val offReady = events.onGlobalReady {
            store?.resync()
            refreshProjects()
            refreshManagers()
        }
        unbind = { offTasks(); offReady() }
        refreshProjects()
        refreshManagers()
    }

    fun refreshProjects() {
        val api = model.api ?: return
        model.scope.launch {
            try {
                projects = api.taskProjects()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The rail count and pickers keep what they showed.
            }
        }
    }

    fun refreshManagers() {
        val api = model.api ?: return
        model.scope.launch {
            try {
                managerChatsSource(api)?.let { managers = it }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A server without manager chats: no chip, Ask manager says there is none.
            }
        }
    }

    fun loadSettings(project: String) {
        val api = model.api ?: return
        model.scope.launch {
            try {
                settings[project] = api.taskProjectSettings(project)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Pickers show what the task carries (status name) until it can be read.
            }
        }
    }

    /** The project's manager chat, if it has one. */
    fun managerOf(project: String): ManagerChatRef? = managers?.firstOrNull { it.projectName == project }

    private fun managerList(): List<TaskManagerLike>? = managers?.map { TaskManagerLike(it.projectName, it.sessionId, it.providerId) }

    /** The task's manager chat: the listed one, else the task's own working session while the list loads (`taskManagerChat`). */
    fun managerChatOf(task: TaskDto): TaskChatRef? = taskManagerChat(task, managerList())

    /** Open chat and Ask manager, one button per chat (`taskChatButtons`). */
    fun chatButtons(task: TaskDto): TaskChatButtons = taskChatButtons(task, managerList())

    /** The project [sessionId] is the manager chat of, or null (`managerProjectOf`). */
    fun managerProjectOf(sessionId: String?): String? = sessionId?.let { id -> managers?.firstOrNull { it.sessionId == id }?.projectName }

    /** The rail's count of open Now tasks in the scope the section shows. */
    fun railCount(): Int = railNowCount(projects, view.scope, model.selectedProject, view.hiddenProjects)

    /**
     * Runs one write (`useTaskWrite().run`): its answer is folded in, a stale one re-read (null,
     * already told), a manager project asked "Tell the manager now?", any other failure a toast.
     */
    fun write(taskId: String?, run: suspend (ApiClient) -> TaskWriteResult, then: (TaskWriteResult) -> Unit = {}) {
        val api = model.api ?: return
        val s = store ?: return
        model.scope.launch {
            try {
                val answer = s.write(taskId) { run(api) } ?: return@launch
                offerManagerPrompt(answer)
                then(answer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.toast(ErrorToastMessage(e.message?.ifBlank { null } ?: "Could not save the task"))
            }
        }
    }

    /** A Decision choice: where it went; Send again when not sent; Start chat when nobody can take it. */
    fun decide(task: TaskDto, optionId: String) {
        val api = model.api ?: return
        val s = store ?: return
        model.scope.launch {
            val r = try {
                s.write(task.id) { api.taskDecide(task.id, task.revision, optionId) } ?: return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.isNoTaskSession) {
                    model.toast(
                        ErrorToastMessage(
                            "No chat can take this choice: start a chat for the task first",
                            action = ErrorToastMessage.Action("Start chat and send") {
                                startChat(task, confirmReplace = null) { started -> decide(started, optionId) }
                            },
                            durationMillis = 15_000,
                        ),
                    )
                } else {
                    model.toast(ErrorToastMessage(e.message?.ifBlank { null } ?: "Could not record the choice"))
                }
                return@launch
            }
            offerManagerPrompt(r)
            if (r.deliveryError != null || r.delivered == null) {
                model.toast(
                    ErrorToastMessage(
                        "Choice recorded, but not sent: ${r.deliveryError ?: "the chat did not take it"}",
                        action = ErrorToastMessage.Action("Send again") { decide(s.findTask(r.task.id) ?: r.task, optionId) },
                        durationMillis = 30_000,
                    ),
                )
            } else {
                model.toast(ErrorToastMessage(decisionDeliveredMessage(r.delivered, r.task.workingSession), kind = ErrorToastMessage.Kind.SUCCESS))
            }
        }
    }

    /**
     * Start chat: a new chat becomes the task's working session and opens. [confirmReplace] is
     * asked first when it would replace a chat that still exists (null skips the question).
     */
    fun startChat(task: TaskDto, confirmReplace: ((onYes: () -> Unit) -> Unit)?, then: (TaskDto) -> Unit = {}) {
        val go = {
            write(task.id, { api -> api.taskStartChat(task.id, task.revision) }) { r ->
                r.sessionId?.let { model.openLinkedSession(task.project, it, "claude", r.task.title) }
                then(r.task)
            }
        }
        if (confirmReplace != null && com.hienlh.ppm.state.tasks.startChatReplaces(task.workingSession)) confirmReplace(go) else go()
    }

    /** A write answered `managerPrompt`: ask once, 1.5 s after the last write to that project (`ASK_AFTER_MS`). */
    private fun offerManagerPrompt(answer: TaskWriteResult) {
        if (!answer.managerPrompt) return
        val project = answer.task.project
        notifyAsk.remove(project)?.cancel()
        notifyAsk[project] = model.scope.launch {
            delay(ASK_AFTER_MS)
            notifyAsk.remove(project)
            var message: ErrorToastMessage? = null
            message = ErrorToastMessage(
                "Tell the $project manager now?",
                description = "Otherwise it reads your changes on its next turn.",
                action = ErrorToastMessage.Action("Tell now") { notifyManager(project) },
                cancel = ErrorToastMessage.Action("Next turn") { message?.let(model::dismissToast) },
                kind = ErrorToastMessage.Kind.PLAIN,
                persistent = true,
            )
            model.toast(message)
        }
    }

    fun notifyManager(project: String) {
        val api = model.api ?: return
        model.scope.launch {
            try {
                val r = api.tasksNotifyManager(project)
                model.toast(ErrorToastMessage(notifyOutcomeMessage(r.sent, r.reason, r.error), kind = ErrorToastMessage.Kind.PLAIN))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.toast(ErrorToastMessage("Could not reach the manager: ${e.message}"))
            }
        }
    }

    fun info(text: String) = model.toast(ErrorToastMessage(text, kind = ErrorToastMessage.Kind.PLAIN))

    fun error(text: String) = model.toast(ErrorToastMessage(text))

    /** Copy link (`copyTaskLink`): the task's address on the clipboard. */
    fun copyLink(clipboard: (String) -> Unit, task: TaskDto) {
        clipboard(taskLink(task.project, task.id))
        model.toast(ErrorToastMessage("Link to ${task.ref} copied", kind = ErrorToastMessage.Kind.SUCCESS))
    }

    /**
     * Opens a task (`openTask`). A tablet shows it in a floating window — the tab focused when it is
     * already on screen, a preview it replaces closed, a new window at the cap left as a tab; a phone
     * shows the task's page. [keep] opens it as a kept tab rather than a preview.
     */
    fun openTask(taskId: String, project: String, title: String, number: Int?, keep: Boolean = false) {
        val tabId = "task:$taskId"
        val layout = model.layout
        val shown = layout?.panels?.values?.firstOrNull { p -> p.tabs.any { it.id == tabId } }
        if (shown != null) {
            model.activate(tabId, shown.id)
            if (keep) keepTab(taskId)
            return
        }
        // A plain selection replaces the preview it left before.
        if (model.wide) {
            layout?.panels?.values?.forEach { p ->
                p.tabs.filter { it.type == "task" && it.metadata["preview"].bool == true }.forEach { model.closeTab(it.id, p.id) }
            }
        }
        val label = if (number != null) taskTabTitle(number, title, project) else title
        model.openWorkspaceTab(
            type = "task",
            title = label,
            metadata = buildJsonObject {
                put("taskId", taskId)
                put("projectName", project)
                if (!keep) put("preview", true)
            },
        )
        if (!model.wide) return
        val now = model.layout ?: return
        val panel = now.panels.values.firstOrNull { p -> p.tabs.any { it.id == tabId } } ?: return
        if (isWindowPanelId(panel.id) || model.windowState.windows.size >= MAX_WINDOWS) return
        model.popOutTab(tabId, panel.id)
    }

    /** An edit or a double-click keeps the task's preview tab (`keepTaskTab`). */
    fun keepTab(taskId: String) {
        val tabId = "task:$taskId"
        if (model.tabOf(tabId)?.metadata?.get("preview").bool == true) model.patchTabMetadata(tabId, "preview" to null)
    }

    /** "#12 Title" in its project's panel, "ppm/12 Title" elsewhere (`taskTabTitle`). */
    fun taskTabTitle(number: Int, title: String, project: String): String =
        if (project == model.selectedProject) "#$number $title" else "$project/$number $title"

    /** Opens the chat a task names, in the task's project. */
    fun openChat(project: String, sessionId: String, providerId: String?, title: String?) {
        model.openLinkedSession(project, sessionId, providerId ?: "", title ?: "")
    }

    /** "Ask manager": opens the manager chat with the task in its composer, nothing sent. */
    fun askManager(task: TaskDto) {
        val manager = managerChatOf(task)
        if (manager == null) {
            info("${task.project} has no manager chat yet")
            return
        }
        model.openLinkedSession(task.project, manager.sessionId, manager.providerId ?: "", "")
        val text = listOfNotNull(
            "Task ${task.ref}: \"${task.title}\" (${task.statusName})",
            task.nextStep?.let { "Next step: $it" },
            "Link: ${taskLink(task.project, task.id)}",
        ).joinToString("\n")
        model.scope.launch {
            // The manager chat's tab is placed a moment after a project switch.
            delay(300)
            model.terminalToChat(text, label = task.ref, project = task.project, asContext = true)
        }
    }

    /** The task's address in PPM (`taskLink`): `<origin>/project/<p>/task/<id>`. */
    fun taskLink(project: String, taskId: String): String {
        val base = model.api?.baseUrl?.toString()?.trimEnd('/') ?: ""
        return "$base/project/${ApiClient.escape(project)}/task/$taskId"
    }

    private fun readView(text: String?): TaskViewState? {
        val json = text?.let(WireJson::parseOrNull) ?: return null
        val f = json["filters"]
        return TaskViewState(
            scope = json["scope"].string?.takeIf { it == "all" || it == "project" } ?: "project",
            filters = TaskViewFilters(
                groups = f["groups"].array.orEmpty().mapNotNull { it.string },
                types = f["types"].array.orEmpty().mapNotNull { it.string },
                person = f["person"].string,
                tag = f["tag"].string,
                field = f["field"]["key"].string?.let { k -> f["field"]["value"].string?.let { TaskFieldFilter(k, it) } },
                waitingOnMe = f["waitingOnMe"].bool ?: false,
            ),
            hiddenProjects = json["hiddenProjects"].array.orEmpty().mapNotNull { it.string },
            snoozedOpen = json["snoozedOpen"].bool ?: false,
        )
    }

    private fun writeView(v: TaskViewState): String = buildJsonObject {
        put("scope", v.scope)
        put(
            "filters",
            buildJsonObject {
                put("groups", JsonArray(v.filters.groups.map(::JsonPrimitive)))
                put("types", JsonArray(v.filters.types.map(::JsonPrimitive)))
                v.filters.person?.let { put("person", it) }
                v.filters.tag?.let { put("tag", it) }
                v.filters.field?.let { fld -> put("field", buildJsonObject { put("key", fld.key); put("value", fld.value) }) }
                put("waitingOnMe", v.filters.waitingOnMe)
            },
        )
        put("hiddenProjects", JsonArray(v.hiddenProjects.map(::JsonPrimitive)))
        put("snoozedOpen", v.snoozedOpen)
    }.toString()

    companion object {
        const val VIEW_KEY = "ppm:tasks-view"
        const val ASK_AFTER_MS = 1_500L
    }
}

private suspend fun readManagerChats(api: ApiClient): List<AppTasks.ManagerChatRef>? =
    api.call("GET", "/api/assistant/managers")["managers"].array?.mapNotNull { m ->
        val id = m["sessionId"].string ?: return@mapNotNull null
        val project = m["projectName"].string ?: return@mapNotNull null
        AppTasks.ManagerChatRef(id, project, m["providerId"].string)
    }

private val holders = WeakHashMap<AppModel, AppTasks>()

/** PPM Tasks' state on this model. */
val AppModel.tasks: AppTasks
    get() = holders.getOrPut(this) { AppTasks(this) }
