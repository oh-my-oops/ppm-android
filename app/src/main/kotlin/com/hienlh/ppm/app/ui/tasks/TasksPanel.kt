package com.hienlh.ppm.app.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.PrefsStore
import com.hienlh.ppm.app.tasks
import com.hienlh.ppm.app.ui.ContextMenuPopup
import com.hienlh.ppm.app.ui.DropdownBelowEnd
import com.hienlh.ppm.app.ui.DropdownBelowStart
import com.hienlh.ppm.app.ui.FormInput
import com.hienlh.ppm.app.ui.MenuItem
import com.hienlh.ppm.app.ui.MenuSeparator
import com.hienlh.ppm.app.ui.pressable
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.TaskListQuery
import com.hienlh.ppm.client.saveTaskProjectSettings
import com.hienlh.ppm.client.tasksList
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.protocol.TaskConst
import com.hienlh.ppm.protocol.TaskDto
import com.hienlh.ppm.protocol.TaskFieldDef
import com.hienlh.ppm.protocol.TaskLiveParts
import com.hienlh.ppm.protocol.TaskMember
import com.hienlh.ppm.protocol.TaskProjectSettings
import com.hienlh.ppm.protocol.TaskProjectSummary
import com.hienlh.ppm.protocol.fieldValueText
import com.hienlh.ppm.state.tasks.ChildProgress
import com.hienlh.ppm.state.tasks.SnoozePatch
import com.hienlh.ppm.state.tasks.TaskFieldFilter
import com.hienlh.ppm.state.tasks.TaskListGroup
import com.hienlh.ppm.state.tasks.TaskViewFilters
import com.hienlh.ppm.state.tasks.UNSNOOZE_PATCH
import com.hienlh.ppm.state.tasks.cellKey
import com.hienlh.ppm.state.tasks.childProgress
import com.hienlh.ppm.state.tasks.countingQueryFor
import com.hienlh.ppm.state.tasks.dropIsNoop
import com.hienlh.ppm.state.tasks.groupTasks
import com.hienlh.ppm.state.tasks.linkStateView
import com.hienlh.ppm.state.tasks.listQueryFor
import com.hienlh.ppm.state.tasks.localYmd
import com.hienlh.ppm.state.tasks.memberName
import com.hienlh.ppm.state.tasks.parseTaskCodeQuery
import com.hienlh.ppm.state.tasks.planDrop
import com.hienlh.ppm.state.tasks.snoozeChoices
import com.hienlh.ppm.state.tasks.snoozedTasks
import com.hienlh.ppm.state.tasks.taskWaiting
import com.hienlh.ppm.state.tasks.tasksMatchingCode
import com.hienlh.ppm.state.tasks.typeLabel
import com.hienlh.ppm.state.tasks.urgencyLabel
import com.hienlh.ppm.state.tasks.visibleTasks
import com.hienlh.ppm.state.tasks.waitingOnMeCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs

/*
 * The Tasks section (`tasks-sidebar-panel.tsx`): a tablet rail section and the phone's Tasks
 * area. It owns the data — one list query per scope and filters, the projects' settings, live
 * link states — and the actions; [TaskListView] only draws.
 */

private val GROUP_LABEL = linkedMapOf("not_started" to "Not started", "active" to "Active", "waiting" to "Waiting", "closed" to "Closed")

/** "Open", "Active", "2 groups" (`groupFilterLabel`). */
internal fun groupFilterLabel(groups: List<String>): String = when (groups.size) {
    0 -> "Open"
    1 -> GROUP_LABEL[groups[0]] ?: groups[0]
    else -> "${groups.size} groups"
}

/** The tasks a code names, asked from the server (`lookupTasksByCode`); empty when none or on failure. */
internal suspend fun lookupTasksByCode(api: ApiClient, query: String, contextProject: String?, anywhere: Boolean = false): List<TaskDto>? {
    val code = parseTaskCodeQuery(query) ?: return null
    return try {
        tasksMatchingCode(api.tasksList(TaskListQuery("all", closed = true)).tasks, code, contextProject, anywhere)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }
}

@Composable
fun TasksPanel(model: AppModel, modifier: Modifier = Modifier) {
    val mobile = LocalPpmMobile.current
    val tasks = model.tasks
    val context = LocalContext.current
    remember(tasks) { tasks.attachPrefs(PrefsStore(context)) }
    LaunchedEffect(tasks, model.events) { tasks.bindEvents(model.events) }
    val scope = rememberCoroutineScope()
    val view = tasks.view
    val activeProject = model.selectedProject
    val projects = tasks.projects
    val own = projects.firstOrNull { it.name == activeProject }
    val enabledProjects = projects.filter { it.enabled }
    val showList = if (view.scope == "all") enabledProjects.isNotEmpty() else own?.enabled == true

    // Typing asks the server once it pauses; a code (`#82`) asks for the whole scope at once.
    var searched by remember { mutableStateOf(view.query) }
    LaunchedEffect(view.query) {
        delay(250)
        searched = view.query
    }
    val codeQuery = if (parseTaskCodeQuery(view.query) != null) view.query else ""
    val listQuery = if (showList) listQueryFor(view.scope, activeProject, view.filters, codeQuery.ifEmpty { if (parseTaskCodeQuery(searched) != null) "" else searched }) else null
    val entry = rememberTaskList(model, listQuery)
    val all = entry?.data?.tasks.orEmpty()
    val loading = listQuery != null && (entry == null || (entry.loading && entry.data == null))
    val error = entry?.error?.takeIf { entry.data == null }
    val counting = rememberTaskList(model, if (showList) countingQueryFor(view.scope, activeProject) else null)
    val everyTask = counting?.data?.tasks ?: all
    val f = view.filters
    val filtersOn = !f.isEmpty || view.query.isNotBlank()
    val waitingOnMe = waitingOnMeCount(everyTask, view.hiddenProjects, view.scope)
    val shown = remember(all, f, view.hiddenProjects, view.scope, codeQuery) { visibleTasks(all, f, view.hiddenProjects, view.scope, codeQuery) }
    val snoozed = remember(all, f, view.hiddenProjects, view.scope, codeQuery) { snoozedTasks(all, f, view.hiddenProjects, view.scope, codeQuery) }

    var picked by remember { mutableStateOf<String?>(null) }
    val activeTaskId = model.activeTabId?.let { model.tabOf(it) }?.takeIf { it.type == "task" }?.metadata?.get("taskId")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    val selectedId = activeTaskId ?: picked
    val expanded = remember(shown, selectedId) { setOfNotNull(selectedId, shown.firstOrNull { it.id == selectedId }?.parentId) }
    val groups = remember(shown, expanded) { groupTasks(shown, expanded) }
    val progress = remember(everyTask) { childProgress(everyTask) }

    val shownProjects = if (view.scope == "all") enabledProjects.map { it.name } else listOfNotNull(activeProject?.takeIf { own?.enabled == true })
    LaunchedEffect(model.api, shownProjects) { shownProjects.filter { it !in tasks.settings }.forEach(tasks::loadSettings) }
    val settings = tasks.settings
    val live = rememberLinkStates(model, shown.map { it.id })

    var sheetTask by remember { mutableStateOf<TaskDto?>(null) }
    val actions = rememberTaskActions(model, onOpen = if (mobile) { t: TaskDto, _: Boolean -> sheetTask = t } else null)
    var newOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }

    fun select(task: TaskDto, keep: Boolean) {
        picked = task.id
        actions.open(task, keep)
    }

    // A code that names one listed task previews it at once on a tablet; a phone waits for Enter.
    val contextProject = if (view.scope == "all") null else activeProject
    val code = parseTaskCodeQuery(codeQuery)
    val codeMatch = if (code != null && !loading) tasksMatchingCode(shown, code, contextProject) else emptyList()
    val jumpTo = codeMatch.singleOrNull()
    var lastQuery by remember { mutableStateOf(view.query) }
    var jumpPending by remember { mutableStateOf(false) }
    LaunchedEffect(view.query) {
        if (view.query == lastQuery) return@LaunchedEffect
        lastQuery = view.query
        jumpPending = parseTaskCodeQuery(view.query) != null
    }
    LaunchedEffect(jumpTo?.id, jumpPending, mobile) {
        if (!jumpPending || jumpTo == null || mobile) return@LaunchedEffect
        jumpPending = false
        select(jumpTo, keep = false)
    }
    val codeNamesElsewhere = code?.project != null && !code.project.equals(contextProject, ignoreCase = true)
    fun submitSearch() {
        val typed = parseTaskCodeQuery(view.query) ?: return
        if (codeMatch.size > 1) {
            tasks.error("${codeMatch.size} projects have a task ${view.query.trim()}: type it as project/${typed.number}")
            return
        }
        scope.launch {
            val found = codeMatch.firstOrNull() ?: if (typed.project != null) model.api?.let { lookupTasksByCode(it, view.query, contextProject)?.firstOrNull() } else null
            if (found != null) select(found, keep = true)
            else tasks.error("No task ${view.query.trim()}${if (contextProject != null && typed.project == null) " in $contextProject" else ""}")
        }
    }

    fun saveEnabled(project: String, on: Boolean) {
        val api = model.api ?: return
        scope.launch {
            try {
                api.saveTaskProjectSettings(project, buildJsonObject { put("enabled", on) })
                tasks.refreshProjects()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tasks.error(if (on) "Could not turn tasks on: ${e.message}" else "Could not save: ${e.message}")
            }
        }
    }

    val members = remember(settings.toMap()) {
        settings.values.flatMap { it.members }.distinctBy { it.id }
    }
    val tags = remember(everyTask) { everyTask.flatMap { it.tags }.toSortedSet().toList() }
    val filterFields = remember(activeProject, settings.toMap(), everyTask) {
        activeProject?.let { settings[it] }?.fieldDefs.orEmpty().filter { it.type == "select" || it.type == "date" }.map { def ->
            def to (if (def.type == "select" && !def.options.isNullOrEmpty()) def.options!! else everyTask.mapNotNull { t -> t.fields[def.key]?.let(::fieldValueText) }.toSortedSet().toList())
        }
    }
    val settingsProject = activeProject?.takeIf { p -> projects.any { it.name == p } }

    Column(modifier.fillMaxSize().testTag("tasks-panel")) {
        TasksHeader(
            model, settingsProject, showList, filterFields,
            onNewTask = { newOpen = true },
            onSettings = { settingsOpen = true },
            onTurnOff = {
                val p = activeProject ?: return@TasksHeader
                tasks.confirm = com.hienlh.ppm.app.AppTasks.Confirm(
                    "Turn off tasks for $p?",
                    "Its tasks are hidden from PPM, the AI tools and Telegram. Nothing is deleted: turning tasks back on shows them again.",
                    "Turn off", destructive = true,
                ) { saveEnabled(p, false) }
            },
            onSubmitSearch = ::submitSearch,
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            TaskListFilters(model, activeProject, enabledProjects, members, tags, waitingOnMe)
            val tokens = LocalPpmTokens.current
            @Composable
            fun note(text: String, color: androidx.compose.ui.graphics.Color = tokens.text3.color) =
                T(text, if (mobile) 14f else 12f, color, maxLines = 4, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 28.dp).testTag("tasks-note"))
            when {
                activeProject == null && view.scope == "project" -> note("Select a project to see its tasks")
                view.scope == "project" && own != null && !own.enabled -> TasksOffEmptyState(own) { saveEnabled(own.name, true) }
                view.scope == "all" && enabledProjects.isEmpty() -> note("No project has tasks turned on yet")
                error != null -> note(error, tokens.error.color)
                loading -> note("Loading tasks…")
                shown.isEmpty() -> {
                    val text = when {
                        codeQuery.isNotEmpty() -> if (codeNamesElsewhere) "${codeQuery.trim()} is not in this list. Press Enter to open it." else "No task ${codeQuery.trim()} here."
                        filtersOn -> "No task matches these filters"
                        snoozed.isNotEmpty() -> "Nothing to do now: every open task is snoozed"
                        all.isNotEmpty() -> "No task matches these filters"
                        else -> "No tasks yet. Add one with +, or ask a chat to."
                    }
                    note(text)
                    if (filtersOn) {
                        LinkButton("Clear filters", Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            tasks.updateView { it.copy(filters = TaskViewFilters(), query = "") }
                        }
                    }
                    if (snoozed.isNotEmpty()) TaskListView(model, groups, progress, view.scope == "all", selectedId, settings, live, snoozed, actions)
                }
                else -> TaskListView(model, groups, progress, view.scope == "all", selectedId, settings, live, snoozed, actions)
            }
        }
    }
    if (newOpen) {
        TaskNewDialog(model, view.scope, activeProject, enabledProjects, parent = null, onDismiss = { newOpen = false }) { created -> select(created, keep = true) }
    }
    if (settingsOpen && settingsProject != null) TaskProjectSettingsDialog(model, settingsProject, onDismiss = { settingsOpen = false })
    sheetTask?.let { t -> TaskSheet(model, t.id, t.project) { sheetTask = null } }
}

/** "Tasks", New task, Search and ···; the search field under it takes a task's code too. */
@Composable
private fun TasksHeader(
    model: AppModel,
    project: String?,
    enabled: Boolean,
    filterFields: List<Pair<TaskFieldDef, List<String>>>,
    onNewTask: () -> Unit,
    onSettings: () -> Unit,
    onTurnOff: () -> Unit,
    onSubmitSearch: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val tasks = model.tasks
    val view = tasks.view
    var menu by remember { mutableStateOf(false) }
    var fieldMenu by remember { mutableStateOf(false) }
    val btn = if (mobile) 44.dp else 28.dp
    Column(Modifier.fillMaxWidth().background(tokens.panel.color)) {
        Row(
            Modifier.fillMaxWidth().height(if (mobile) 48.dp else 36.dp).padding(start = if (mobile) 16.dp else 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PpmIcon(ProductIcon.ListTodo, 14.dp, if (mobile) tokens.text.color else tokens.text2.color)
                if (mobile) T("Tasks", 16f, tokens.text.color, weight = FontWeight.SemiBold)
                else T("TASKS", 11f, tokens.text2.color, weight = FontWeight.SemiBold)
            }
            Box(Modifier.alpha(if (enabled) 1f else 0.4f)) {
                IconBtn(ProductIcon.Plus, "New task", Modifier.testTag("tasks-new"), size = btn) { if (enabled) onNewTask() }
            }
            IconBtn(ProductIcon.Search, "Search tasks", Modifier.testTag("tasks-search"), size = btn, tint = if (view.searchOpen) tokens.accent.color else tokens.text3.color) {
                tasks.updateView { it.copy(searchOpen = !it.searchOpen) }
            }
            Box {
                IconBtn(ProductIcon.MoreHorizontal, "More task actions", Modifier.testTag("tasks-more"), size = btn) { menu = true }
                if (menu) {
                    DropdownBelowEnd(onDismiss = { menu = false }, minWidth = 208.dp) {
                        MenuItem("Task settings…", onClick = { menu = false; onSettings() }, enabled = project != null)
                        MenuItem("Filter by field", onClick = { fieldMenu = true; menu = false }, enabled = filterFields.isNotEmpty())
                        MenuItem("Clear filters", onClick = { menu = false; tasks.updateView { it.copy(filters = TaskViewFilters()) } })
                        if (project != null && enabled) {
                            MenuSeparator()
                            MenuItem("Turn off tasks for $project", onClick = { menu = false; onTurnOff() }, color = tokens.error)
                        }
                    }
                }
                if (fieldMenu) {
                    DropdownBelowEnd(onDismiss = { fieldMenu = false }, minWidth = 208.dp) {
                        Column(Modifier.heightIn(max = 288.dp).verticalScroll(rememberScrollState())) {
                            for ((def, values) in filterFields) {
                                T(def.label, 12f, tokens.text3.color, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                if (values.isEmpty()) MenuItem("No values yet", onClick = {}, enabled = false)
                                for (v in values) {
                                    MenuItem(v, onClick = {
                                        fieldMenu = false
                                        tasks.updateView { it.copy(filters = it.filters.copy(field = TaskFieldFilter(def.key, v))) }
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }
        if (view.searchOpen) {
            val state = rememberTextFieldState(view.query)
            LaunchedEffect(state) { snapshotFlow { state.text.toString() }.collect { q -> if (q != tasks.view.query) tasks.updateView { it.copy(query = q) } } }
            LaunchedEffect(view.query) { if (state.text.toString() != view.query) state.edit { replace(0, length, view.query) } }
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FormInput(
                    state, "Search, or #12 to jump to a task", Modifier.weight(1f).testTag("tasks-search-field"),
                    height = if (mobile) 44.dp else 32.dp, onSubmit = onSubmitSearch,
                )
                if (view.query.isNotEmpty()) IconBtn(ProductIcon.X, "Clear search", size = if (mobile) 44.dp else 24.dp) { tasks.updateView { it.copy(query = "") } }
            }
        }
    }
}

/** The `<project> | All projects` switch, the project chips, and the filter chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskListFilters(model: AppModel, project: String?, projects: List<TaskProjectSummary>, members: List<TaskMember>, tags: List<String>, waitingOnMe: Int) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val tasks = model.tasks
    val view = tasks.view
    val f = view.filters
    fun set(change: (TaskViewFilters) -> TaskViewFilters) = tasks.updateView { it.copy(filters = change(it.filters)) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(tokens.surfaceHover.color).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((s, label) in listOf("project" to (project ?: "This project"), "all" to "All projects")) {
                val on = view.scope == s
                val shape = RoundedCornerShape(6.dp)
                Box(
                    Modifier.weight(1f).height(if (mobile) 44.dp else 28.dp).clip(shape)
                        .background(if (on) tokens.panel.color else androidx.compose.ui.graphics.Color.Transparent)
                        .ppmClickable(shape = shape, role = androidx.compose.ui.semantics.Role.Tab) { tasks.updateView { it.copy(scope = s) } }
                        .testTag("tasks-scope-$s"),
                    contentAlignment = Alignment.Center,
                ) { T(label, if (mobile) 14f else 12f, if (on) tokens.text.color else tokens.text2.color, weight = if (on) FontWeight.Medium else FontWeight.Normal) }
            }
        }
        if (view.scope == "all" && projects.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (p in projects) {
                    val on = p.name !in view.hiddenProjects
                    Pill(p.name, on, count = p.open, strike = !on) {
                        tasks.updateView { v -> v.copy(hiddenProjects = if (on) v.hiddenProjects + p.name else v.hiddenProjects - p.name) }
                    }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("Waiting on you", f.waitingOnMe, Modifier.testTag("tasks-waiting-on-me"), count = waitingOnMe, icon = ProductIcon.Hand) { set { it.copy(waitingOnMe = !it.waitingOnMe) } }
            FilterChip(if (f.types.isEmpty()) "Type" else f.types.joinToString(", ", transform = ::typeLabel), f.types.isNotEmpty(), onClear = { set { it.copy(types = emptyList()) } }) { close ->
                for (t in TaskConst.TYPES) {
                    MenuItem(typeLabel(t), onClick = { set { it.copy(types = if (t in it.types) it.types - t else it.types + t) } }, lit = t in f.types, trailing = check(t in f.types))
                }
            }
            FilterChip(groupFilterLabel(f.groups), f.groups.isNotEmpty()) { close ->
                MenuItem("Open (not closed)", onClick = { set { it.copy(groups = emptyList()) }; close() }, trailing = check(f.groups.isEmpty()))
                MenuSeparator()
                for ((g, label) in GROUP_LABEL) {
                    MenuItem(label, onClick = { set { it.copy(groups = if (g in it.groups) it.groups - g else it.groups + g) } }, trailing = check(g in f.groups))
                }
            }
            val person = f.person?.let { p -> if (p == TaskConst.ME) "You" else members.firstOrNull { it.id == p }?.name ?: "Person" } ?: "Person"
            FilterChip(person, f.person != null, onClear = { set { it.copy(person = null) } }) { close ->
                T("Assignee or waiting on", 12f, tokens.text3.color, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                MenuItem("Anyone", onClick = { set { it.copy(person = null) }; close() }, trailing = check(f.person == null))
                MenuItem("You", onClick = { set { it.copy(person = TaskConst.ME) }; close() }, trailing = check(f.person == TaskConst.ME))
                for (m in members.filter { it.archivedAt == null }) MenuItem(m.name, onClick = { set { it.copy(person = m.id) }; close() }, trailing = check(f.person == m.id))
            }
            FilterChip(f.tag ?: "Tag", f.tag != null, onClear = { set { it.copy(tag = null) } }) { close ->
                if (tags.isEmpty()) T("No tags on these tasks", 12f, tokens.text3.color, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                else MenuItem("Any tag", onClick = { set { it.copy(tag = null) }; close() }, trailing = check(f.tag == null))
                for (t in tags) MenuItem(t, onClick = { set { it.copy(tag = t) }; close() }, trailing = check(f.tag == t))
            }
            f.field?.let { fld ->
                Pill("${fld.key}: ${fld.value}", on = true, icon = ProductIcon.X, modifier = Modifier.semantics { contentDescription = "Clear field filter" }) { set { it.copy(field = null) } }
            }
        }
    }
}

private fun check(on: Boolean): (@Composable () -> Unit)? = if (!on) null else ({ PpmIcon(ProductIcon.Check, 14.dp, LocalPpmTokens.current.accent.color) })

/** A filter chip with its menu; a lit one can be cleared with its ×. */
@Composable
private fun FilterChip(label: String, on: Boolean, onClear: (() -> Unit)? = null, menu: @Composable (close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(label, on, icon = ProductIcon.ChevronDown, modifier = Modifier.testTag("tasks-filter-$label")) { open = true }
            if (on && onClear != null) IconBtn(ProductIcon.X, "Clear filter", size = 22.dp, onClick = onClear)
        }
        if (open) {
            DropdownBelowStart(onDismiss = { open = false }, minWidth = 180.dp) {
                Column(Modifier.heightIn(max = 288.dp).verticalScroll(rememberScrollState())) { menu { open = false } }
            }
        }
    }
}

/** "Tasks are off for <project>", with Turn on tasks. */
@Composable
private fun TasksOffEmptyState(project: TaskProjectSummary, onTurnOn: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp).testTag("tasks-off"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PpmIcon(ProductIcon.ListTodo, 24.dp, tokens.text3.color)
        T("Tasks are off for ${project.name}", 14f, tokens.text.color, weight = FontWeight.Medium)
        T(
            if (project.hasManager) "This project has a manager chat, which normally turns tasks on by itself."
            else "Tasks turn on by themselves when the project gets a manager chat. Turning them off later hides them; nothing is deleted.",
            12f, tokens.text3.color, maxLines = 4,
        )
        PillButton("Turn on tasks", Modifier.testTag("tasks-turn-on"), onClick = onTurnOn)
    }
}

/** The urgency groups and the Snoozed fold, as a tablet list (drag to reorder) or a phone list. */
@Composable
internal fun TaskListView(
    model: AppModel,
    groups: List<TaskListGroup>,
    progress: Map<String, ChildProgress>,
    showProject: Boolean,
    selectedId: String?,
    settings: Map<String, TaskProjectSettings>,
    live: Map<String, TaskLiveParts>,
    snoozed: List<TaskDto>,
    actions: TaskActions,
) {
    val mobile = LocalPpmMobile.current
    val snoozedOpen = model.tasks.view.snoozedOpen
    TaskListBody(
        groups, progress, showProject, selectedId, settings, live, snoozed, actions, mobile,
        snoozedOpen = snoozedOpen,
        onToggleSnoozed = { model.tasks.updateView { it.copy(snoozedOpen = !it.snoozedOpen) } },
        hasManager = { p -> model.tasks.managerOf(p) != null },
    )
}

/** What [TaskListView] draws, with nothing of the model: the tests draw this. */
@Composable
internal fun TaskListBody(
    groups: List<TaskListGroup>,
    progress: Map<String, ChildProgress>,
    showProject: Boolean,
    selectedId: String?,
    settings: Map<String, TaskProjectSettings>,
    live: Map<String, TaskLiveParts>,
    snoozed: List<TaskDto>,
    actions: TaskActions,
    mobile: Boolean,
    snoozedOpen: Boolean,
    onToggleSnoozed: () -> Unit,
    hasManager: (String) -> Boolean,
) {
    val tokens = LocalPpmTokens.current
    var pressed by remember { mutableStateOf<TaskDto?>(null) }
    var menuFor by remember { mutableStateOf<Pair<TaskDto, Offset>?>(null) }
    // Drag: the row's and each group's place on screen, the dragged task and where it would land.
    val rowBounds = remember { mutableStateMapOf<String, Rect>() }
    val groupBounds = remember { mutableStateMapOf<String, Rect>() }
    var dragging by remember { mutableStateOf<TaskDto?>(null) }
    var dragY by remember { mutableStateOf(0f) }
    var moved by remember { mutableStateOf(0f) }

    fun targetAt(y: Float): Pair<String, Int>? {
        val g = groupBounds.entries.firstOrNull { y >= it.value.top && y < it.value.bottom }?.key
            ?: groupBounds.entries.minByOrNull { abs((it.value.top + it.value.bottom) / 2 - y) }?.key ?: return null
        val rows = groups.firstOrNull { it.urgency == g }?.rows.orEmpty().filter { it.depth == 0 && it.task.id != dragging?.id }
        val index = rows.count { r -> rowBounds[r.task.id]?.let { (it.top + it.bottom) / 2 < y } == true }
        return g to index
    }
    val target = dragging?.let { if (abs(moved) > 8f) targetAt(dragY) else null }

    fun drop(task: TaskDto, at: Pair<String, Int>) {
        val group = groups.firstOrNull { it.urgency == at.first }
        val groupRows = group?.rows.orEmpty().filter { it.depth == 0 }.map { it.task }
        val plan = planDrop(task, at.first, groupRows, at.second)
        when {
            plan.kind == "none" -> plan.reason?.let(actions::info)
            dropIsNoop(task, groupRows, plan) -> Unit
            plan.kind == "reorder" -> actions.reorder(task, plan.beforeId, plan.afterId)
            else -> plan.urgency?.let { actions.moveUrgency(task, it) }
        }
    }

    @Composable
    fun row(task: TaskDto, depth: Int) {
        val extra = live[task.id]
        if (mobile) {
            TaskRowMobile(
                task, depth, task.id == selectedId, showProject, progress[task.id], settings[task.project]?.members, extra, actions,
                onActions = { pressed = task },
            )
        } else {
            val drag = if (depth == 0 && !task.snoozed) {
                Modifier.pointerInput(task.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { at ->
                            dragging = task
                            moved = 0f
                            dragY = (rowBounds[task.id]?.top ?: 0f) + at.y
                        },
                        onDrag = { change, delta ->
                            change.consume()
                            dragY += delta.y
                            moved += delta.y
                        },
                        onDragEnd = {
                            val t = dragging
                            val at = if (abs(moved) > 8f) targetAt(dragY) else null
                            dragging = null
                            // A long-press that did not move opens the row's menu, as a right-click does.
                            if (t != null && at == null && abs(moved) <= 8f) menuFor = t to Offset(0f, (rowBounds[t.id]?.height ?: 0f))
                            if (t != null && at != null) drop(t, at)
                        },
                        onDragCancel = { dragging = null },
                    )
                }
            } else {
                Modifier
            }
            Box(
                Modifier.onGloballyPositioned { rowBounds[task.id] = it.boundsInRoot() }
                    .then(drag)
                    .secondaryClick { at -> menuFor = task to at },
            ) {
                TaskListRowView(
                    task, depth, task.id == selectedId, showProject, progress[task.id], settings[task.project]?.members, extra, actions,
                    dragging = dragging?.id == task.id,
                )
                if (menuFor?.first?.id == task.id && task.baseGroup != "closed") {
                    SnoozeRowMenu(task, menuFor!!.second, actions) { menuFor = null }
                }
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = if (mobile) 0.dp else 6.dp).padding(bottom = 12.dp).testTag("task-list")) {
        for (g in groups) {
            if (g.rows.isEmpty() && dragging == null) continue
            Column(
                Modifier.fillMaxWidth().onGloballyPositioned { groupBounds[g.urgency] = it.boundsInRoot() }
                    .then(if (dragging != null) Modifier.heightIn(min = 32.dp) else Modifier)
                    .testTag("task-group-${g.urgency}"),
            ) {
                GroupHeader(urgencyLabel(g.urgency), g.count, mobile)
                val dropHere = if (target?.first == g.urgency) target.second else -1
                var top = 0
                for (r in g.rows) {
                    val isDragged = r.task.id == dragging?.id
                    if (r.depth == 0 && !isDragged) {
                        if (top == dropHere) DropLine()
                        top++
                    }
                    if (mobile && r !== g.rows.first()) Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.borderSoft.color))
                    row(r.task, r.depth)
                }
                if (dropHere >= 0 && dropHere >= top) DropLine()
            }
        }
        if (snoozed.isNotEmpty()) {
            SnoozedHeader(snoozed.size, snoozedOpen, mobile, onToggleSnoozed)
            if (snoozedOpen) {
                Column(Modifier.testTag("task-snoozed-rows")) {
                    for (t in snoozed) row(t, 0)
                }
            }
        }
    }
    pressed?.let { t ->
        val (up, down) = cellNeighbours(groups, t)
        TaskRowActionSheet(t, showProject, settings[t.project]?.statuses, up, down, hasManager(t.project), actions) { pressed = null }
    }
}

/** The rows above and below [task] in its own cell: where Reorder can move it (`cellNeighbours`). */
internal fun cellNeighbours(groups: List<TaskListGroup>, task: TaskDto): Pair<TaskDto?, TaskDto?> {
    val cell = groups.firstOrNull { it.urgency == task.effectiveUrgency }?.rows.orEmpty().filter { it.depth == 0 && cellKey(it.task) == cellKey(task) }.map { it.task }
    val at = cell.indexOfFirst { it.id == task.id }
    if (at < 0) return null to null
    return cell.getOrNull(at - 1) to cell.getOrNull(at + 1)
}

/** A mouse's right button, without a touch's long-press (that one starts a drag). */
private fun Modifier.secondaryClick(open: (Offset) -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val down = event.changes.firstOrNull { it.pressed && !it.previousPressed } ?: continue
            if (down.type == PointerType.Mouse && event.buttons.isSecondaryPressed) {
                down.consume()
                open(down.position)
            }
        }
    }
}

@Composable
private fun DropLine() {
    Box(Modifier.padding(horizontal = 8.dp).fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(LocalPpmTokens.current.accent.color).testTag("task-drop-line"))
}

@Composable
private fun GroupHeader(label: String, count: Int, mobile: Boolean) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = if (mobile) 16.dp else 8.dp).padding(top = if (mobile) 12.dp else 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        T(label.uppercase(), if (mobile) 12f else 11f, tokens.text3.color, weight = FontWeight.SemiBold)
        CountBadge("$count")
        if (!mobile) Box(Modifier.weight(1f).height(1.dp).background(tokens.borderSoft.color))
    }
}

@Composable
private fun SnoozedHeader(count: Int, open: Boolean, mobile: Boolean, onToggle: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 28.dp).clip(RoundedCornerShape(6.dp))
            .ppmClickable(onClickLabel = if (open) "Fold snoozed tasks" else "Show snoozed tasks", onClick = onToggle)
            .padding(horizontal = if (mobile) 16.dp else 8.dp, vertical = 4.dp)
            .testTag("task-snoozed-toggle"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PpmIcon(if (open) ProductIcon.ChevronDown else ProductIcon.ChevronRight, 14.dp, tokens.text3.color)
        PpmIcon(ProductIcon.Moon, 14.dp, tokens.text3.color)
        T("SNOOZED", if (mobile) 12f else 11f, tokens.text3.color, weight = FontWeight.SemiBold)
        CountBadge("$count")
        if (!mobile) Box(Modifier.weight(1f).height(1.dp).background(tokens.borderSoft.color))
    }
}

/** The row's right-click (or still long-press) menu: the snooze choices; Pick a date… opens the picker. */
@Composable
private fun SnoozeRowMenu(task: TaskDto, at: Offset, actions: TaskActions, onDismiss: () -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val tokens = LocalPpmTokens.current
    val choices = snoozeChoices(task, localYmd())
    ContextMenuPopup(at, onDismiss) {
        Column(Modifier.width(260.dp).testTag("task-row-menu")) {
            if (picking) {
                MenuHeading("Snooze until…")
                TaskSnoozeOptions(task, { p -> onDismiss(); actions.snooze(task, p, announce = true) }, pickingDate = true)
                return@Column
            }
            T("SNOOZE UNTIL…", 11f, tokens.text3.color, weight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            for (c in choices.filter { it.id != "clear" }) {
                MenuItem(c.label, onClick = {
                    val p = c.patch
                    if (p == null) picking = true
                    else {
                        onDismiss()
                        actions.snooze(task, p, announce = true)
                    }
                }, enabled = !c.disabled, trailing = c.detail?.let { d -> { T(d, 11f, tokens.text3.color) } })
            }
            if (choices.any { it.id == "clear" }) {
                MenuSeparator()
                MenuItem("Unsnooze", onClick = { onDismiss(); actions.snooze(task, UNSNOOZE_PATCH, announce = true) })
            }
        }
    }
}

/** The meta line's external state: the first Jira key, else the first PR's state as a dot. */
@Composable
private fun ExternalMeta(task: TaskDto, live: TaskLiveParts?) {
    val tokens = LocalPpmTokens.current
    val jira = task.links.filter { it.kind == "jira" }
    if (jira.isNotEmpty()) {
        T(jira[0].ref + if (jira.size > 1) " +${jira.size - 1}" else "", 11f, tokens.text3.color, mono = true)
        return
    }
    val pr = task.links.firstOrNull { it.kind == "pr" } ?: return
    val v = linkStateView(pr, live?.linkStates?.firstOrNull { it.kind == "pr" && it.ref == pr.ref })
    val state = v.state ?: return
    if (state == "…" || state.startsWith("state unknown")) return
    val color = when (v.tone) {
        "ok" -> tokens.success.color
        "bad" -> tokens.error.color
        "warn" -> tokens.warning.color
        "info" -> tokens.info.color
        "merge" -> tokens.accent2.color
        else -> tokens.text3.color
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(color))
        T(state, 11.5f, tokens.text3.color)
    }
}

/** One tablet row (`TaskListRow`): glyph, code and title, the meta line, importance and assignee. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TaskListRowView(
    task: TaskDto,
    depth: Int,
    selected: Boolean,
    showProject: Boolean,
    progress: ChildProgress?,
    members: List<TaskMember>?,
    live: TaskLiveParts?,
    actions: TaskActions,
    dragging: Boolean = false,
) {
    val tokens = LocalPpmTokens.current
    val today = localYmd()
    val waiting = taskWaiting(task, members)
    val assignee = memberName(task.assigneeMemberId, members)
    val closed = task.baseGroup == "closed"
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .then(if (selected) Modifier.background(tokens.accentWash.color) else Modifier)
            .alpha(if (dragging) 0.4f else 1f)
            .pressable(shape = shape, onLongClick = {}) { actions.open(task, false) }
            .padding(start = if (depth == 1) 28.dp else 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
            .testTag("task-row-${task.id}"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (selected) Box(Modifier.width(2.dp).height(16.dp).background(tokens.accent.color))
        StatusGlyph(task.baseGroup, task.closedAs, 12.dp, Modifier.padding(top = 3.dp), name = task.statusName)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                T(com.hienlh.ppm.state.tasks.taskCode(task, if (showProject) null else task.project), 11.5f, tokens.text3.color, mono = true, modifier = Modifier.padding(top = 1.dp))
                T(
                    task.title, if (depth == 1) 12.5f else 13f, if (closed) tokens.text3.color else tokens.text.color, maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
            }
            if (depth == 0) {
                FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (task.type == "decision" || task.type == "reply") TypeBadge(task.type)
                    ExternalMeta(task, live)
                    if (waiting != null) WaitingLabel(waiting, today) else if (task.dueDate != null) DueLabel(task.dueDate, today)
                    SnoozeBadge(task, today)
                    if (progress != null && progress.total > 0) T("${progress.done}/${progress.total}", 11f, tokens.text3.color, mono = true)
                    SessionChip(live?.workingSession ?: task.workingSession, task.check) { sid ->
                        if (sid != null) actions.openChat(task, sid) else actions.startChat(task)
                    }
                    if (task.snoozed) PillButton("Unsnooze", primary = false, modifier = Modifier.testTag("task-unsnooze")) { actions.snooze(task, UNSNOOZE_PATCH, announce = true) }
                }
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
            ImportanceBars(task.importance)
            if (assignee != null && depth == 0) MemberAvatar(task.assigneeMemberId ?: "", assignee, 18.dp)
        }
    }
}

/** One phone row (`TaskRowMobile`): a status cell, code and title, the meta line and a chevron. */
@Composable
internal fun TaskRowMobile(
    task: TaskDto,
    depth: Int,
    selected: Boolean,
    showProject: Boolean,
    progress: ChildProgress?,
    members: List<TaskMember>?,
    live: TaskLiveParts?,
    actions: TaskActions,
    onActions: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val today = localYmd()
    val waiting = taskWaiting(task, members)
    val closed = task.baseGroup == "closed"
    val snoozeShown = task.snoozed || task.resurfacedToday
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (depth == 1) 52.dp else 62.dp)
            .then(if (selected) Modifier.background(tokens.accentWash.color) else Modifier)
            .pressable(onLongClick = { onActions() }) { actions.open(task, true) }
            .padding(start = if (depth == 1) 24.dp else 0.dp, end = 12.dp)
            .testTag("task-row-${task.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(50)).ppmClickable(onClickLabel = "Status: ${task.statusName}. Change", onClick = onActions).testTag("task-row-status"),
            contentAlignment = Alignment.Center,
        ) { StatusGlyph(task.baseGroup, task.closedAs, 16.dp) }
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                T(com.hienlh.ppm.state.tasks.taskCode(task, if (showProject) null else task.project), 12.5f, tokens.text3.color, mono = true)
                T(task.title, 15f, if (closed) tokens.text3.color else tokens.text.color, modifier = Modifier.weight(1f))
            }
            if (depth == 0) {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (task.type == "decision" || task.type == "reply") TypeBadge(task.type)
                    ExternalMeta(task, live)
                    waiting?.let { WaitingLabel(it, today) }
                    if (snoozeShown) SnoozeBadge(task, today, compact = task.snoozed)
                    if (progress != null && progress.total > 0) T("${progress.done}/${progress.total}", 11f, tokens.text3.color, mono = true)
                    if (!task.snoozed) {
                        Box(Modifier.weight(1f, fill = false)) {
                            SessionChip(live?.workingSession ?: task.workingSession, task.check) { sid -> if (sid != null) actions.openChat(task, sid) else actions.startChat(task) }
                        }
                    }
                    if (task.snoozed) PillButton("Unsnooze", primary = false, modifier = Modifier.testTag("task-unsnooze")) { actions.snooze(task, UNSNOOZE_PATCH, announce = true) }
                }
            }
        }
        PpmIcon(ProductIcon.ChevronRight, 16.dp, tokens.text3.color)
    }
}
