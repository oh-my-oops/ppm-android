package com.hienlh.ppm.app.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.tasks
import com.hienlh.ppm.app.ui.DialogButton
import com.hienlh.ppm.app.ui.FieldLabel
import com.hienlh.ppm.app.ui.FormInput
import com.hienlh.ppm.app.ui.InputKind
import com.hienlh.ppm.app.ui.PpmSwitch
import com.hienlh.ppm.app.ui.SelectField
import com.hienlh.ppm.app.ui.SelectOption
import com.hienlh.ppm.app.ui.WebDialog
import com.hienlh.ppm.client.saveTaskProjectSettings
import com.hienlh.ppm.client.taskCreate
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.protocol.TaskConst
import com.hienlh.ppm.protocol.TaskDto
import com.hienlh.ppm.protocol.TaskFieldDef
import com.hienlh.ppm.protocol.TaskMember
import com.hienlh.ppm.protocol.TaskProjectSettings
import com.hienlh.ppm.protocol.TaskProjectSummary
import com.hienlh.ppm.protocol.TaskStatusDef
import com.hienlh.ppm.state.tasks.IMPORTANCE_LABEL
import com.hienlh.ppm.state.tasks.URGENCY_LABEL
import com.hienlh.ppm.state.tasks.localYmd
import com.hienlh.ppm.state.tasks.typeLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * New task (`task-new-dialog.tsx`) and Task settings (`task-project-settings-dialog.tsx` with its
 * statuses, members and fields editors).
 */

/** A day field: the day and a calendar; a tap opens the system's picker, × clears it. */
@Composable
internal fun DayField(value: String?, label: String, modifier: Modifier = Modifier, onPick: (String?) -> Unit) {
    val tokens = LocalPpmTokens.current
    val context = LocalContext.current
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier.heightIn(min = 28.dp).clip(shape).border(1.dp, tokens.borderSoft.color, shape)
            .ppmClickable(shape = shape, onClickLabel = label) { pickDay(context, value, null) { onPick(it) } }
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        com.hienlh.ppm.design.PpmIcon(TaskIcons.Calendar, 13.dp, tokens.text3.color)
        T(value ?: "—", 13f, if (value == null) tokens.text3.color else tokens.text.color, modifier = Modifier.weight(1f))
        if (value != null) IconBtn(ProductIcon.X, "Clear $label", size = 26.dp) { onPick(null) }
    }
}

/** New task: title, the project (all-projects scope), type, due, urgency and importance. */
@Composable
internal fun TaskNewDialog(
    model: AppModel,
    scope: String,
    project: String?,
    projects: List<TaskProjectSummary>,
    parent: TaskDto?,
    onDismiss: () -> Unit,
    onCreated: (TaskDto) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val coroutine = rememberCoroutineScope()
    val fixedProject = parent?.project ?: if (scope == "project") project else null
    val title = rememberTextFieldState()
    val enabled = projects.map { it.name }
    var target by remember { mutableStateOf(fixedProject ?: project?.takeIf { it in enabled } ?: enabled.firstOrNull() ?: "") }
    var type by remember { mutableStateOf("task") }
    var urgency by remember { mutableStateOf("week") }
    var importance by remember { mutableStateOf("should") }
    var due by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val ready = title.text.isNotBlank() && target.isNotEmpty() && !busy
    fun submit() {
        if (!ready) return
        val api = model.api ?: return
        val store = model.tasks.store ?: return
        busy = true
        coroutine.launch {
            val r = try {
                store.write(null) {
                    api.taskCreate(
                        target,
                        buildJsonObject {
                            put("title", title.text.toString().trim())
                            put("type", type)
                            put("urgency", urgency)
                            put("importance", importance)
                            due?.let { put("dueDate", it) }
                            parent?.let { put("parentId", it.id) }
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.tasks.error(e.message?.ifBlank { null } ?: "Could not create the task")
                null
            }
            busy = false
            r ?: return@launch
            model.toast(ErrorToastMessage("Created ${r.task.ref}", kind = ErrorToastMessage.Kind.SUCCESS))
            onDismiss()
            onCreated(r.task)
        }
    }
    WebDialog(if (parent != null) "New child of ${parent.ref}" else "New task", onDismiss, widest = 448.dp) {
        Column(Modifier.fillMaxWidth().testTag("task-new-dialog"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FieldLabel("Title")
                FormInput(title, "What needs doing", Modifier.fillMaxWidth().testTag("task-new-title"), height = 36.dp, onSubmit = ::submit)
            }
            if (fixedProject == null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FieldLabel("Project")
                    SelectField(target, projects.map { SelectOption(it.name, it.name) }, { target = it })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FieldLabel("Type")
                    SelectField(type, TaskConst.TYPES.map { SelectOption(it, typeLabel(it)) }, { type = it })
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FieldLabel("Due")
                    DayField(due, "Due date", Modifier.fillMaxWidth().height(36.dp)) { due = it }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FieldLabel("Urgency")
                    SelectField(urgency, TaskConst.URGENCIES.map { SelectOption(it, URGENCY_LABEL[it] ?: it) }, { urgency = it })
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FieldLabel("Importance")
                    SelectField(importance, TaskConst.IMPORTANCES.map { SelectOption(it, IMPORTANCE_LABEL[it] ?: it) }, { importance = it })
                }
            }
            if (type == "decision") T("Add the options on the task once it exists, or let the manager prepare them.", 12f, tokens.text3.color, maxLines = 3)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                DialogButton("Cancel", primary = false, dark = true, enabled = true, modifier = Modifier, height = 36.dp, onClick = onDismiss)
                DialogButton("Create task", primary = true, dark = true, enabled = ready, modifier = Modifier.testTag("task-new-create"), height = 36.dp, onClick = ::submit)
            }
        }
    }
}

private val SECTIONS = listOf("general" to "General", "statuses" to "Statuses", "members" to "Members", "fields" to "Fields")
private val BASE_GROUPS = listOf(SelectOption("not_started", "Not started"), SelectOption("active", "Active"), SelectOption("waiting", "Waiting"), SelectOption("closed", "Closed"))
private val CLOSED_AS = listOf(SelectOption("done", "Done"), SelectOption("dropped", "Dropped"))
private val FIELD_TYPE_LABEL = mapOf("text" to "Text", "number" to "Number", "select" to "Select", "date" to "Date", "link" to "Link")
private val KEY_RE = Regex("^[a-z][a-z0-9_]{0,39}$")
private const val CHECK_MIN = TaskConst.CHECK_INTERVAL_MIN_MIN
private const val CHECK_MAX = TaskConst.CHECK_INTERVAL_MAX_MIN

/** Task settings for one project: the switch, the check interval, statuses, members and fields. */
@Composable
internal fun TaskProjectSettingsDialog(model: AppModel, project: String, initialSection: String = "general", onDismiss: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val coroutine = rememberCoroutineScope()
    val tasks = model.tasks
    LaunchedEffect(project) { tasks.loadSettings(project) }
    val settings = tasks.settings[project]
    var section by remember { mutableStateOf(initialSection) }
    fun apply(update: JsonObject) {
        val api = model.api ?: return
        coroutine.launch {
            try {
                tasks.settings[project] = api.saveTaskProjectSettings(project, update)
                tasks.refreshProjects()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tasks.error("Could not save: ${e.message}")
            }
        }
    }
    WebDialog("Task settings · $project", onDismiss, widest = 672.dp, description = "Statuses, people and fields are this project's own.") {
        Column(Modifier.fillMaxWidth().testTag("task-settings-dialog"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((id, label) in SECTIONS) {
                    val on = section == id
                    Column(Modifier.ppmClickable(role = androidx.compose.ui.semantics.Role.Tab) { section = id }.testTag("task-settings-$id")) {
                        T(label, 14f, if (on) tokens.text.color else tokens.text2.color, weight = if (on) FontWeight.Medium else FontWeight.Normal, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                        Box(Modifier.fillMaxWidth().height(2.dp).background(if (on) tokens.accent.color else androidx.compose.ui.graphics.Color.Transparent))
                    }
                }
            }
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (settings == null) {
                    T("Loading…", 14f, tokens.text3.color)
                    return@Column
                }
                when (section) {
                    "general" -> GeneralSettings(project, settings, ::apply, tasks::error)
                    "statuses" -> StatusesEditor(settings.statuses, ::apply)
                    "members" -> MembersEditor(settings.members, ::apply)
                    else -> FieldsEditor(settings.fieldDefs, ::apply)
                }
            }
        }
    }
}

@Composable
private fun GeneralSettings(project: String, settings: TaskProjectSettings, apply: (JsonObject) -> Unit, onError: (String) -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            T("Tasks for $project", 14f, tokens.text.color, weight = FontWeight.Medium)
            T("Off hides the tasks from PPM, the AI tools and Telegram. Nothing is deleted.", 12f, tokens.text3.color, maxLines = 3)
        }
        PpmSwitch(settings.project.enabled, { on -> apply(buildJsonObject { put("enabled", on) }) })
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        T("Check handed-off work every", 14f, tokens.text.color)
        CommitInput("${settings.checkIntervalMin}", "Check interval in minutes", Modifier.width(64.dp), kind = InputKind.Integer) { v ->
            val n = v.toDoubleOrNull()?.let { Math.round(it).toInt() }
            if (n == null || n < CHECK_MIN || n > CHECK_MAX) onError("Pick $CHECK_MIN to $CHECK_MAX minutes")
            else apply(buildJsonObject { put("checkIntervalMin", n) })
        }
        T("minutes", 14f, tokens.text.color)
    }
    T("A chat a task was handed to is checked until the task has a result. A stalled chat, or one that finished without reporting, wakes the manager.", 12f, tokens.text3.color, maxLines = 4)
}

/** Saves on Enter or when left, only when the text changed; an empty value is refused when [required]. */
@Composable
internal fun CommitInput(value: String, label: String, modifier: Modifier = Modifier, required: Boolean = false, placeholder: String = "", kind: InputKind = InputKind.Url, onCommit: (String) -> Unit) {
    val state = remember(value) { TextFieldState(value) }
    fun commit() {
        val v = state.text.toString().trim()
        if (v == value.trim()) return
        if (required && v.isEmpty()) {
            state.edit { replace(0, length, value) }
            return
        }
        onCommit(v)
    }
    FormInput(
        state, placeholder.ifEmpty { label }, modifier, kind = kind, height = 32.dp,
        onFocusChanged = { focused -> if (!focused) commit() },
        onSubmit = ::commit,
    )
}

@Composable
private fun StatusesEditor(statuses: List<TaskStatusDef>, save: (JsonObject) -> Unit) {
    val tokens = LocalPpmTokens.current
    val sorted = statuses.sortedBy { it.position }
    var deleting by remember { mutableStateOf<String?>(null) }
    var replacement by remember { mutableStateOf("") }
    fun update(vararg entries: JsonObject) = save(buildJsonObject { put("statuses", buildJsonObject { put("update", JsonArray(entries.toList())) }) })
    T("Every status belongs to a base group, so lists across projects can merge them.", 12f, tokens.text3.color, maxLines = 2)
    sorted.forEachIndexed { i, s ->
        Row(Modifier.fillMaxWidth().testTag("task-status-row"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CommitInput(s.name, "Status name", Modifier.weight(1f), required = true) { name -> update(buildJsonObject { put("id", s.id); put("name", name) }) }
            Box(Modifier.width(120.dp)) {
                SelectField(s.baseGroup, BASE_GROUPS, { g ->
                    update(buildJsonObject {
                        put("id", s.id)
                        put("baseGroup", g)
                        put("closedAs", if (g == "closed") JsonPrimitive(s.closedAs ?: "done") else JsonNull)
                    })
                })
            }
            Box(Modifier.width(96.dp)) {
                if (s.baseGroup == "closed") SelectField(s.closedAs ?: "done", CLOSED_AS, { c -> update(buildJsonObject { put("id", s.id); put("closedAs", c) }) })
            }
            IconBtn(ProductIcon.ArrowUp, "Move up", size = 32.dp) {
                sorted.getOrNull(i - 1)?.let { b -> update(buildJsonObject { put("id", s.id); put("position", b.position) }, buildJsonObject { put("id", b.id); put("position", s.position) }) }
            }
            IconBtn(ProductIcon.ArrowDown, "Move down", size = 32.dp) {
                sorted.getOrNull(i + 1)?.let { b -> update(buildJsonObject { put("id", s.id); put("position", b.position) }, buildJsonObject { put("id", b.id); put("position", s.position) }) }
            }
            IconBtn(ProductIcon.Trash2, "Delete ${s.name}", size = 32.dp) {
                if (sorted.size > 1) {
                    deleting = s.id
                    replacement = sorted.firstOrNull { it.id != s.id }?.id ?: ""
                }
            }
        }
        if (deleting == s.id) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(tokens.surfaceHover.color).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                T("Move its tasks to", 12f, tokens.text2.color)
                Box(Modifier.width(144.dp)) { SelectField(replacement, sorted.filter { it.id != s.id }.map { SelectOption(it.id, it.name) }, { replacement = it }) }
                LinkButton("Delete", color = tokens.error.color) {
                    save(buildJsonObject {
                        put("statuses", buildJsonObject {
                            put("delete", buildJsonArray { add(buildJsonObject { put("id", s.id); put("replacementId", replacement.ifEmpty { null }?.let(::JsonPrimitive) ?: JsonNull) }) })
                        })
                    })
                    deleting = null
                }
                LinkButton("Cancel", color = tokens.text2.color) { deleting = null }
            }
        }
    }
    LinkButton("Add status", icon = ProductIcon.Plus) {
        save(buildJsonObject { put("statuses", buildJsonObject { put("add", buildJsonArray { add(buildJsonObject { put("name", "New status"); put("baseGroup", "not_started") }) }) }) })
    }
}

@Composable
private fun MembersEditor(members: List<TaskMember>, save: (JsonObject) -> Unit) {
    val tokens = LocalPpmTokens.current
    val sorted = members.sortedWith(compareBy<TaskMember> { it.archivedAt != null }.thenBy { it.position })
    fun set(id: String, key: String, value: Any?) = save(buildJsonObject {
        put("members", buildJsonObject {
            put("update", buildJsonArray {
                add(buildJsonObject {
                    put("id", id)
                    when (value) {
                        is Boolean -> put(key, value)
                        is String -> put(key, value)
                        else -> put(key, JsonNull)
                    }
                })
            })
        })
    })
    T("You are always a member; add the people tasks wait on or are assigned to.", 12f, tokens.text3.color, maxLines = 2)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (h in listOf("NAME", "JIRA ACCOUNT ID", "GITHUB LOGIN", "SLACK ID")) T(h, 10.5f, tokens.text3.color, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Box(Modifier.width(32.dp))
    }
    for (m in sorted) {
        Row(
            Modifier.fillMaxWidth().then(if (m.archivedAt != null) Modifier.alpha(0.6f) else Modifier).testTag("task-member-row"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CommitInput(m.name, "Name", Modifier.weight(1f), required = true) { set(m.id, "name", it) }
            CommitInput(m.jiraAccountId ?: "", "Jira account id", Modifier.weight(1f)) { set(m.id, "jiraAccountId", it.ifEmpty { null }) }
            CommitInput(m.githubLogin ?: "", "GitHub login", Modifier.weight(1f)) { set(m.id, "githubLogin", it.ifEmpty { null }) }
            CommitInput(m.slackUserId ?: "", "Slack id", Modifier.weight(1f)) { set(m.id, "slackUserId", it.ifEmpty { null }) }
            IconBtn(if (m.archivedAt != null) ProductIcon.RotateCcw else ProductIcon.Trash2, if (m.archivedAt != null) "Restore ${m.name}" else "Archive ${m.name}", size = 32.dp) {
                set(m.id, "archived", m.archivedAt == null)
            }
        }
    }
    LinkButton("Add member", icon = ProductIcon.Plus) {
        save(buildJsonObject { put("members", buildJsonObject { put("add", buildJsonArray { add(buildJsonObject { put("name", "New member") }) }) }) })
    }
}

@Composable
private fun FieldsEditor(fields: List<TaskFieldDef>, save: (JsonObject) -> Unit) {
    val tokens = LocalPpmTokens.current
    val sorted = fields.sortedBy { it.position }
    val newKey = rememberTextFieldState()
    fun upsert(f: TaskFieldDef, label: String = f.label, type: String = f.type, options: List<String>? = f.options) = save(buildJsonObject {
        put("fields", buildJsonObject {
            put("upsert", buildJsonArray {
                add(buildJsonObject {
                    put("key", f.key)
                    put("label", label)
                    put("type", type)
                    put("options", options?.let { o -> JsonArray(o.map(::JsonPrimitive)) } ?: JsonNull)
                    put("position", f.position)
                })
            })
        })
    })
    T("Keys use lowercase letters, digits and underscores. Separate select options with commas.", 12f, tokens.text3.color, maxLines = 2)
    for (f in sorted) {
        Row(Modifier.fillMaxWidth().testTag("task-field-row"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            T(f.key, 12f, tokens.text2.color, mono = true, modifier = Modifier.width(112.dp))
            CommitInput(f.label, "Label", Modifier.weight(1f), required = true) { upsert(f, label = it) }
            Box(Modifier.width(96.dp)) {
                SelectField(f.type, TaskConst.FIELD_TYPES.map { SelectOption(it, FIELD_TYPE_LABEL[it] ?: it) }, { t -> upsert(f, type = t, options = if (t == "select") f.options ?: emptyList() else null) })
            }
            Box(Modifier.weight(1.2f)) {
                if (f.type == "select") {
                    CommitInput(f.options.orEmpty().joinToString(", "), "Options", Modifier.fillMaxWidth(), placeholder = "Option A, Option B") { v ->
                        upsert(f, options = v.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                    }
                }
            }
            IconBtn(ProductIcon.Trash2, "Delete field ${f.key}", size = 32.dp) {
                save(buildJsonObject { put("fields", buildJsonObject { put("delete", buildJsonArray { add(JsonPrimitive(f.key)) }) }) })
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val key = newKey.text.toString().trim().lowercase()
        val ok = KEY_RE.matches(key) && sorted.none { it.key == key }
        FormInput(newKey, "new_field_key", Modifier.weight(1f), height = 32.dp)
        LinkButton("Add field", icon = ProductIcon.Plus, enabled = ok) {
            save(buildJsonObject {
                put("fields", buildJsonObject {
                    put("upsert", buildJsonArray { add(buildJsonObject { put("key", key); put("label", key.replace('_', ' ')); put("type", "text"); put("position", sorted.size) }) })
                })
            })
            newKey.edit { replace(0, length, "") }
        }
    }
}

