package com.hienlh.ppm.app.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.schedules
import com.hienlh.ppm.app.tasks
import com.hienlh.ppm.app.ui.BottomSheet
import com.hienlh.ppm.app.ui.DropdownBelowEnd
import com.hienlh.ppm.app.ui.FormInput
import com.hienlh.ppm.app.ui.FormTextArea
import com.hienlh.ppm.app.ui.MenuItem
import com.hienlh.ppm.app.ui.SelectField
import com.hienlh.ppm.app.ui.SelectOption
import com.hienlh.ppm.app.ui.openInBrowser
import com.hienlh.ppm.client.schedulesDelete
import com.hienlh.ppm.client.taskReminders
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.protocol.TaskConst
import com.hienlh.ppm.protocol.TaskDetail
import com.hienlh.ppm.protocol.TaskDto
import com.hienlh.ppm.protocol.TaskFieldDef
import com.hienlh.ppm.protocol.TaskHistoryItem
import com.hienlh.ppm.protocol.TaskLink
import com.hienlh.ppm.protocol.TaskMember
import com.hienlh.ppm.protocol.TaskProjectSettings
import com.hienlh.ppm.protocol.TaskStatusDef
import com.hienlh.ppm.protocol.fieldValueText
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.state.settings.ScheduleRowState
import com.hienlh.ppm.state.settings.nextRunText
import com.hienlh.ppm.state.settings.parseSchedulesList
import com.hienlh.ppm.state.tasks.IMPORTANCE_LABEL
import com.hienlh.ppm.state.tasks.URGENCY_LABEL
import com.hienlh.ppm.state.tasks.ageShort
import com.hienlh.ppm.state.tasks.cellKey
import com.hienlh.ppm.state.tasks.historyEntryText
import com.hienlh.ppm.state.tasks.isAiEntry
import com.hienlh.ppm.state.tasks.localYmd
import com.hienlh.ppm.state.tasks.memberName
import com.hienlh.ppm.state.tasks.optionLetter
import com.hienlh.ppm.state.tasks.sessionChipView
import com.hienlh.ppm.state.tasks.taskLinkWebHref
import com.hienlh.ppm.state.tasks.taskWaiting
import com.hienlh.ppm.state.tasks.typeLabel
import com.hienlh.ppm.state.tasks.viaText
import com.hienlh.ppm.state.tasks.whenShort
import com.hienlh.ppm.state.workspace.WorkspaceTab
import com.hienlh.ppm.design.vector
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * A task's page (`task-tab.tsx`, `task-detail-desktop.tsx`, `mobile/task-detail-mobile.tsx`): the
 * `task` tab on a tablet (two columns from 760 dp of its own width) and, on a phone, a page with
 * Details / Activity, the status and Done in the thumb zone, drawn in the Tasks area or in a sheet.
 */

/** The phone page drawn inside a sheet: × closes it, Full screen turns it into the tab. */
class TaskSheetControls(val onClose: () -> Unit, val onExpand: () -> Unit)

/** The `task` tab (`TaskTab`): the task the tab names, its project's settings, and the page. */
@Composable
fun TaskTabBody(model: AppModel, tab: WorkspaceTab, onBack: (() -> Unit)? = null) {
    val tokens = LocalPpmTokens.current
    val taskId = tab.metadata["taskId"].string
    val preview = tab.metadata["preview"].bool == true
    LaunchedEffect(model.tasks, model.events) { model.tasks.bindEvents(model.events) }
    val entry = rememberTaskDetail(model, taskId)
    val detail = entry?.data
    val project = detail?.task?.project ?: tab.metadata["projectName"].string
    val settings = rememberTaskSettings(model, project)
    // The tab's title follows the task's ("#12 Fix login").
    LaunchedEffect(detail?.task?.title, detail?.task?.number, model.selectedProject) {
        val t = detail?.task ?: return@LaunchedEffect
        model.retitleTab(tab.id, model.tasks.taskTabTitle(t.number, t.title, t.project))
    }
    if (taskId == null) {
        T("This tab names no task.", 14f, tokens.text3.color, modifier = Modifier.padding(24.dp))
        return
    }
    if (detail == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            if (entry?.error != null) T(entry.error!!, 14f, tokens.text3.color, maxLines = 4)
            else if (entry?.loading != false) PpmIcon(ProductIcon.Loader2, 20.dp, tokens.text3.color, spinning = true)
            else T("This task could not be read.", 14f, tokens.text3.color)
        }
        return
    }
    TaskDetailView(model, detail, settings, preview, sheet = null, onBack = onBack)
}

/** A task's phone page in a bottom sheet; Full screen turns it into the tab. */
@Composable
fun TaskSheet(model: AppModel, taskId: String, project: String, onClose: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val entry = rememberTaskDetail(model, taskId)
    val detail = entry?.data
    val settings = rememberTaskSettings(model, detail?.task?.project ?: project)
    BottomSheet(onDismiss = onClose, swipeFromContent = false) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.92f).testTag("task-sheet")) {
            if (detail != null) {
                val controls = TaskSheetControls(onClose) {
                    onClose()
                    model.tasks.openTask(detail.task.id, detail.task.project, detail.task.title, detail.task.number, keep = true)
                }
                TaskDetailView(model, detail, settings, preview = false, sheet = controls)
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (entry?.error != null) T(entry.error!!, 14f, tokens.text3.color, maxLines = 4)
                    else PpmIcon(ProductIcon.Loader2, 20.dp, tokens.text3.color, spinning = true)
                }
            }
        }
    }
}

/** The page for the device: two columns or a stack on a tablet, Details / Activity on a phone. */
@Composable
fun TaskDetailView(model: AppModel, detail: TaskDetail, settings: TaskProjectSettings?, preview: Boolean, sheet: TaskSheetControls?, onBack: (() -> Unit)? = null) {
    val actions = rememberTaskActions(model)
    val hasManager = model.tasks.managerOf(detail.task.project) != null
    var childOpen by remember { mutableStateOf(false) }
    var fieldsOpen by remember { mutableStateOf(false) }
    val pieces = TaskPagePieces(model, detail, settings, actions, hasManager, onAddChild = { childOpen = true }, onManageFields = { fieldsOpen = true })
    if (LocalPpmMobile.current) TaskDetailPhone(pieces, sheet, onBack) else TaskDetailTablet(pieces, preview)
    if (childOpen) {
        TaskNewDialog(model, "project", detail.task.project, emptyList(), parent = detail.task, onDismiss = { childOpen = false }) { c ->
            model.tasks.openTask(c.id, c.project, c.title, c.number, keep = true)
        }
    }
    if (fieldsOpen) TaskProjectSettingsDialog(model, detail.task.project, initialSection = "fields") { fieldsOpen = false }
}

/** What both layouts draw from. */
class TaskPagePieces(
    val model: AppModel,
    val detail: TaskDetail,
    val settings: TaskProjectSettings?,
    val actions: TaskActions,
    val hasManager: Boolean,
    val onAddChild: () -> Unit,
    val onManageFields: () -> Unit,
) {
    val task: TaskDto get() = detail.task
}

@Composable
private fun TaskDetailTablet(p: TaskPagePieces, preview: Boolean) {
    val tokens = LocalPpmTokens.current
    val task = p.task
    BoxWithConstraints(Modifier.fillMaxSize().background(tokens.bgSolid.color).testTag("task-detail")) {
        val main: @Composable ColumnScope.() -> Unit = {
            TaskDetailHeader(p, preview)
            if (task.type == "decision" || task.decision != null) DecisionCard(p)
            RemindersCard(p.model, task.id)
            ResultCard(p)
            NotesCard(p)
            LinksCard(p)
            ChildrenCard(p)
            HistoryTimeline(p, phone = false)
        }
        val aside: @Composable ColumnScope.() -> Unit = {
            WorkingSessionBlock(p)
            PropertiesPanel(p)
        }
        if (maxWidth >= 760.dp) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = main)
                Box(Modifier.width(1.dp).fillMaxHeight().background(tokens.borderSoft.color))
                Column(Modifier.width(300.dp).fillMaxHeight().background(tokens.panel.color).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = aside)
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = main)
                Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.borderSoft.color))
                Column(Modifier.fillMaxWidth().background(tokens.panel.color).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = aside)
            }
        }
    }
}

/** `<project> / #12`, the preview hint, Copy link, Snooze, Remind…, Ask manager, Mark done; the title; the chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskDetailHeader(p: TaskPagePieces, preview: Boolean) {
    val tokens = LocalPpmTokens.current
    val task = p.task
    val closed = task.baseGroup == "closed"
    val today = localYmd()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(RoundedCornerShape(4.dp)).background(tokens.surfaceHover.color).padding(horizontal = 6.dp)) { T(task.project, 12f, tokens.text.color, weight = FontWeight.Medium) }
            T("/", 13f, tokens.text3.color)
            TaskCodeText(p.model, task, task.project, 13f)
            if (preview) T("Preview · edit to keep the tab", 12f, tokens.text3.color)
            LinkButton("Copy link", icon = ProductIcon.Link, color = tokens.text2.color) { p.actions.copyLink(task) }
            TaskSnoozeButton(task) { patch -> p.actions.patch(task, snoozeJson(patch)) }
            TaskRemindButton(p.model, task)
            if (p.hasManager) PillButton("Ask manager", primary = false, icon = ProductIcon.MessageSquare) { p.actions.askManager(task) }
            if (closed) PillButton("Reopen", primary = false, icon = ProductIcon.RotateCcw) { p.actions.reopen(task) }
            else PillButton("Mark done", icon = ProductIcon.Check, modifier = Modifier.testTag("task-mark-done")) { p.actions.close(task) }
        }
        TitleEditor(task, size = 20f) { p.actions.patch(task, patchOf("title", it)) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusGlyph(task.baseGroup, task.closedAs, 12.dp)
                T(task.statusName, 12f, tokens.text2.color)
            }
            if (task.type != "task") TypeBadge(task.type)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                UrgencyPill(task.effectiveUrgency)
                if (task.urgencyRaised) T("auto", 10.5f, tokens.accent.color)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ImportanceBars(task.importance)
                T(IMPORTANCE_LABEL[task.importance] ?: task.importance, 12f, tokens.text2.color)
            }
            DueLabel(task.dueDate, today)
            // The due date is beside it already: the label keeps only an age.
            taskWaiting(task, p.settings?.members)?.let { w -> WaitingLabel(w.copy(dueDate = null), today) }
            SnoozeBadge(task, today)
        }
    }
}

/** The title, edited in place: a tap edits, Enter or leaving saves, whitespace folded. */
@Composable
private fun TitleEditor(task: TaskDto, size: Float, onRename: (String) -> Unit) {
    val tokens = LocalPpmTokens.current
    var editing by remember { mutableStateOf(false) }
    val closed = task.baseGroup == "closed"
    if (editing) {
        val state = remember(task.id) { TextFieldState(task.title) }
        fun commit() {
            editing = false
            val t = state.text.toString().replace(Regex("\\s+"), " ").trim()
            if (t.isNotEmpty() && t != task.title) onRename(t.take(TaskConst.TITLE_MAX))
        }
        FormInput(state, "Task title", Modifier.fillMaxWidth().testTag("task-title-input"), height = 40.dp, onFocusChanged = { if (!it) commit() }, onSubmit = ::commit)
    } else {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).ppmClickable(onClickLabel = "Click to rename") { editing = true }.testTag("task-title"),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            T(com.hienlh.ppm.state.tasks.taskCode(task, task.project), size * 0.7f, tokens.text3.color, mono = true, modifier = Modifier.padding(top = 4.dp))
            T(task.title, size, if (closed) tokens.text2.color else tokens.text.color, weight = FontWeight.SemiBold, maxLines = 6, modifier = Modifier.weight(1f))
        }
    }
}

/** A plain-text editor with Save / Cancel (`PlainTextEditor`). */
@Composable
private fun PlainTextEditor(value: String, label: String, placeholder: String, max: Int, onSave: (String) -> Unit, onCancel: () -> Unit) {
    val state = remember { TextFieldState(value) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FormTextArea(state, placeholder, Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("task-text-$label"))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
            LinkButton("Cancel", color = LocalPpmTokens.current.text2.color, onClick = onCancel)
            PillButton("Save", modifier = Modifier.testTag("task-text-save")) { onSave(state.text.toString().take(max)) }
        }
    }
}

/** A Decision task's options, each with Choose; the options edited one per line. */
@Composable
private fun DecisionCard(p: TaskPagePieces) {
    val tokens = LocalPpmTokens.current
    val task = p.task
    val d = task.decision
    val options = d?.options.orEmpty()
    val chosen = d?.chosen
    var editing by remember { mutableStateOf(false) }
    fun letter(id: String, i: Int) = if (Regex("^[a-zA-Z]$").matches(id)) id.uppercase() else optionLetter(i)
    val sentTo = when (task.workingSession.kind) {
        "manager" -> "the manager chat"
        "session" -> "\"${task.workingSession.session?.title?.trim()?.ifEmpty { null } ?: "the working chat"}\""
        else -> "the working chat (start one first)"
    }
    TaskSection(
        d?.question?.trim()?.ifEmpty { null } ?: "Options", icon = TaskIcons.Scale, decision = true, tag = "task-card-decision",
        aside = if (editing) null else ({ LinkButton("Edit", icon = ProductIcon.Pencil) { editing = true } }),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                editing -> PlainTextEditor(options.joinToString("\n") { it.label }, "options", "One option per line\nFor example: Qualifying earnings, as in October", 10_000, { text ->
                    editing = false
                    val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.take(TaskConst.DECISION_OPTIONS_MAX)
                    if (lines.isNotEmpty()) {
                        p.actions.patch(task, buildJsonObject {
                            put("decision", buildJsonObject {
                                put("question", d?.question?.let(::JsonPrimitive) ?: JsonNull)
                                put("options", JsonArray(lines.map(::JsonPrimitive)))
                            })
                        })
                    }
                }) { editing = false }
                options.isEmpty() -> T("No options yet. Add them here, or ask the manager to prepare them.", 13f, tokens.text3.color, maxLines = 3)
                else -> options.forEachIndexed { i, o ->
                    val isChosen = chosen?.optionId == o.id
                    Row(
                        Modifier.fillMaxWidth().then(if (chosen != null && !isChosen) Modifier.alpha(0.6f) else Modifier).testTag("decision-option-${o.id}"),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(if (isChosen) tokens.success.color else tokens.accent2.color.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isChosen) PpmIcon(ProductIcon.Check, 12.dp, androidx.compose.ui.graphics.Color.White)
                            else T(letter(o.id, i), 11f, tokens.accent2.color, weight = FontWeight.SemiBold)
                        }
                        T(o.label, 13f, tokens.text.color, maxLines = 20, modifier = Modifier.weight(1f))
                        PillButton(if (isChosen) "Send again" else "Choose ${letter(o.id, i)}", primary = !isChosen) { p.actions.decide(task, o.id) }
                    }
                }
            }
            T(
                if (chosen != null) "Chosen by ${chosen.by} · sent to $sentTo. Choosing again sends the new choice."
                else "Your choice is sent to $sentTo word for word and recorded in history.",
                12f, tokens.text3.color, maxLines = 3,
            )
        }
    }
}

/** The reminders this task owns, each with Remove; nothing while there is none. */
@Composable
private fun RemindersCard(model: AppModel, taskId: String) {
    val tokens = LocalPpmTokens.current
    val api = model.api
    var tick by remember { mutableStateOf(0) }
    DisposableEffect(model.events) {
        val off = model.events?.onSchedulesChanged { tick++ }
        onDispose { off?.invoke() }
    }
    val reminders by produceState(emptyList<ScheduleRowState>(), api, taskId, tick) {
        if (api == null) return@produceState
        try {
            value = parseSchedulesList(api.taskReminders(taskId))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep what is shown.
        }
    }
    val scope = rememberCoroutineScope()
    if (reminders.isEmpty()) return
    Column(Modifier.fillMaxWidth().testTag("task-reminders"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (r in reminders) {
            val shape = RoundedCornerShape(8.dp)
            val problem = !r.pausedReason.isNullOrEmpty()
            Row(
                Modifier.fillMaxWidth().clip(shape)
                    .border(1.dp, if (problem) tokens.error.color.copy(alpha = 0.45f) else tokens.accentWashBorder.color, shape)
                    .background(if (problem) tokens.error.color.copy(alpha = 0.1f) else tokens.accentWash.color)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PpmIcon(ProductIcon.CalendarClock, 18.dp, tokens.accent.color)
                Column(Modifier.weight(1f)) {
                    val next = nextRunText(r).removePrefix("Next: ")
                    T(if (r.enabled) "Reminder · $next" else "Reminder · off", 13f, tokens.text.color, weight = FontWeight.SemiBold)
                    T(
                        listOfNotNull("to ${r.targetTitle ?: "the manager chat"}", r.pausedReason).joinToString(" · "),
                        11.5f, if (problem) tokens.error.color else tokens.text2.color, maxLines = 2,
                    )
                }
                IconBtn(ProductIcon.ExternalLink, "Open in Schedules", size = 28.dp) { model.schedules.open(select = r.id) }
                IconBtn(ProductIcon.X, "Remove reminder", size = 28.dp) {
                    scope.launch {
                        try {
                            api?.schedulesDelete(r.id)
                            tick++
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            model.tasks.error(e.message ?: "Could not remove the reminder")
                        }
                    }
                }
            }
        }
    }
}

/** What the work came to; "No result yet" with Record result. */
@Composable
private fun ResultCard(p: TaskPagePieces) {
    val tokens = LocalPpmTokens.current
    val task = p.task
    var editing by remember { mutableStateOf(false) }
    fun save(v: String) {
        editing = false
        val text = v.trim()
        if (text == (task.result?.text ?: "")) return
        p.actions.patch(task, buildJsonObject { put("result", if (text.isEmpty()) JsonNull else buildJsonObject { put("text", text) }) })
    }
    val result = task.result
    if (result != null && !editing) {
        val shape = RoundedCornerShape(8.dp)
        Column(
            Modifier.fillMaxWidth().clip(shape).border(1.dp, tokens.success.color.copy(alpha = 0.3f), shape).background(tokens.success.color.copy(alpha = 0.05f)).padding(12.dp).testTag("task-card-result"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PpmIcon(ProductIcon.CheckCircle2, 14.dp, tokens.success.color)
                T("Result", 12f, tokens.text.color, weight = FontWeight.Medium)
                T("${result.by} · ${whenShort(result.at)}", 12f, tokens.text3.color, modifier = Modifier.weight(1f))
                LinkButton("Edit", icon = ProductIcon.Pencil) { editing = true }
            }
            T(result.text, 13f, tokens.text2.color, maxLines = 40)
        }
        return
    }
    val handedOff = task.workingSession.kind == "session" && task.handedOffAt != null
    TaskSection("Result", icon = ProductIcon.CheckCircle2.vector, tag = "task-card-result") {
        if (editing) {
            PlainTextEditor(result?.text ?: "", "result", "What the work came to", TaskConst.RESULT_MAX, ::save) { editing = false }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                T("No result yet" + if (handedOff) " · the working chat is checked until one is recorded" else "", 13f, tokens.text3.color, maxLines = 3, modifier = Modifier.weight(1f, fill = false))
                LinkButton("Record result") { editing = true }
            }
        }
    }
}

/** Why, Next step and free notes; Edit opens all three and saves those that changed. */
@Composable
private fun NotesCard(p: TaskPagePieces) {
    val tokens = LocalPpmTokens.current
    val task = p.task
    var editing by remember { mutableStateOf(false) }
    TaskSection("Notes", tag = "task-card-notes", aside = if (editing) null else ({ LinkButton("Edit", icon = ProductIcon.Pencil) { editing = true } })) {
        if (editing) {
            val why = remember { TextFieldState(task.why ?: "") }
            val next = remember { TextFieldState(task.nextStep ?: "") }
            val notes = remember { TextFieldState(task.notes ?: "") }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                T("Why", 12f, tokens.text2.color, weight = FontWeight.Medium)
                FormTextArea(why, "", Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("task-notes-why"))
                T("Next step", 12f, tokens.text2.color, weight = FontWeight.Medium)
                FormTextArea(next, "", Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("task-notes-next"))
                T("Notes", 12f, tokens.text2.color, weight = FontWeight.Medium)
                FormTextArea(notes, "", Modifier.fillMaxWidth().heightIn(min = 80.dp).testTag("task-notes-notes"))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                    LinkButton("Cancel", color = tokens.text2.color) { editing = false }
                    PillButton("Save", modifier = Modifier.testTag("task-notes-save")) {
                        fun norm(s: TextFieldState) = s.text.toString().trim().ifEmpty { null }
                        val patch = buildJsonObject {
                            if (norm(why) != task.why) put("why", norm(why)?.take(TaskConst.WHY_MAX)?.let(::JsonPrimitive) ?: JsonNull)
                            if (norm(next) != task.nextStep) put("nextStep", norm(next)?.take(TaskConst.WHY_MAX)?.let(::JsonPrimitive) ?: JsonNull)
                            if (norm(notes) != task.notes) put("notes", norm(notes)?.take(TaskConst.NOTES_MAX)?.let(::JsonPrimitive) ?: JsonNull)
                        }
                        editing = false
                        if (patch.isNotEmpty()) p.actions.patch(task, patch)
                    }
                }
            }
        } else if (task.why == null && task.nextStep == null && task.notes == null) {
            T("Add why, the next step or notes", 13f, tokens.text3.color, modifier = Modifier.ppmClickable { editing = true })
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                task.why?.let { T("Why: $it", 13f, tokens.text2.color, maxLines = 40) }
                task.nextStep?.let { T("Next step: $it", 13f, tokens.text2.color, maxLines = 40) }
                task.notes?.let { T(it, 13f, tokens.text2.color, maxLines = 400) }
            }
        }
    }
}

private val LINK_KIND_LABEL = linkedMapOf("jira" to "Jira key", "pr" to "Pull request", "session" to "PPM chat", "url" to "URL")
private val LINK_PLACEHOLDER = mapOf("jira" to "NX-6318 or https://…/browse/NX-6318", "pr" to "owner/repo#123", "session" to "chat session id", "url" to "https://…")

/** A link as the page draws it: a web link opens in the browser, a chat in PPM. */
@Composable
internal fun TaskLinkChip(link: TaskLink, detail: TaskDetail?, onOpenChat: (String) -> Unit, info: (String) -> Unit) {
    val context = LocalContext.current
    val href = taskLinkWebHref(link, detail?.jiraSite)
    val live = detail?.linkStates?.firstOrNull { it.kind == link.kind && it.ref == link.ref }
    val open: (() -> Unit)? = when {
        link.kind == "session" -> ({ onOpenChat(link.ref) })
        href != null -> ({ openInBrowser(context, href) { info("No app can open $href") } })
        else -> null
    }
    LinkPill(link, live, open)
}

/** Links with their live state and when PPM last checked; + Add takes a kind and a reference. */
@Composable
private fun LinksCard(p: TaskPagePieces) {
    val tokens = LocalPpmTokens.current
    val task = p.task
    var adding by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf("jira") }
    val ref = rememberTextFieldState()
    val checked = p.detail.linkStates.mapNotNull { it.checkedAt }.maxOrNull()
    TaskSection("Links", count = "${task.links.size}", tag = "task-card-links", aside = {
        checked?.let { T("live · checked ${ageShort(it)} ago", 12f, tokens.text3.color) }
        if (!adding) LinkButton("Add", icon = ProductIcon.Plus, modifier = Modifier.testTag("task-link-add")) { adding = true }
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (task.links.isEmpty() && !adding) T("No links. Add a Jira key, a PR or a chat to see its live state here.", 13f, tokens.text3.color, maxLines = 3)
            for (l in task.links) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f, fill = false)) { TaskLinkChip(l, p.detail, { sid -> p.actions.openChat(task, sid) }, p.actions::info) }
                    if (l.label != null && l.kind != "session") T(l.label!!, 12f, tokens.text3.color)
                    if (l.kind == "jira" && taskLinkWebHref(l, p.detail.jiraSite) == null) T("no Jira site", 12f, tokens.text3.color)
                    Box(Modifier.weight(1f))
                    IconBtn(ProductIcon.X, "Remove ${l.ref}", size = 28.dp) { p.actions.removeLink(task, l) }
                }
            }
            if (p.detail.jiraSite == null && task.links.any { it.kind == "jira" }) {
                T("To open Jira keys, add one issue by its full URL (https://…/browse/NX-6318): its site is kept for this project.", 12f, tokens.text3.color, maxLines = 3)
            }
            if (adding) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.width(136.dp)) { SelectField(kind, LINK_KIND_LABEL.map { (k, v) -> SelectOption(k, v) }, { kind = it }) }
                    FormInput(ref, LINK_PLACEHOLDER[kind] ?: "", Modifier.weight(1f).testTag("task-link-ref"), height = 32.dp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                    LinkButton("Cancel", color = tokens.text2.color) { adding = false }
                    PillButton("Add", enabled = ref.text.isNotBlank(), modifier = Modifier.testTag("task-link-submit")) {
                        p.actions.addLink(task, kind, ref.text.toString().trim()) { ok ->
                            if (ok) {
                                ref.edit { replace(0, length, "") }
                                adding = false
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Child tasks with "1/3" done; a tap opens one, + Add child creates one under this task. */
@Composable
private fun ChildrenCard(p: TaskPagePieces) {
    val tokens = LocalPpmTokens.current
    val children = p.detail.children
    val done = children.count { it.baseGroup == "closed" }
    TaskSection(
        "Child tasks", count = if (children.isEmpty()) null else "$done/${children.size}", tag = "task-card-children",
        aside = { LinkButton("Add child", icon = ProductIcon.Plus, modifier = Modifier.testTag("task-add-child"), onClick = p.onAddChild) },
    ) {
        if (children.isEmpty()) {
            T("No child tasks. Split the work into smaller tasks with Add child.", 13f, tokens.text3.color, maxLines = 3)
            return@TaskSection
        }
        Column {
            for (c in children) {
                val who = memberName(c.assigneeMemberId, p.settings?.members)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 28.dp).clip(RoundedCornerShape(6.dp)).ppmClickable { p.actions.open(c, true) }.testTag("task-child-${c.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusGlyph(c.baseGroup, c.closedAs, 12.dp, name = c.statusName)
                    T(com.hienlh.ppm.state.tasks.taskCode(c, c.project), 12f, tokens.text3.color, mono = true)
                    T(c.title, 13f, if (c.baseGroup == "closed") tokens.text3.color else tokens.text.color, modifier = Modifier.weight(1f))
                    T(c.statusName, 11f, tokens.text3.color, mono = true)
                    who?.let { MemberAvatar(c.assigneeMemberId ?: "", it, 18.dp) }
                }
            }
        }
    }
}

private val HISTORY_TABS = listOf("all" to "History", "ai" to "AI changes", "status" to "Status only")

/** History / AI changes / Status only, with Undo on what can still be undone. */
@Composable
private fun HistoryTimeline(p: TaskPagePieces, phone: Boolean) {
    val tokens = LocalPpmTokens.current
    val task = p.task
    var filter by remember { mutableStateOf("all") }
    val page = rememberTaskHistory(p.model, task.id, filter, task.revision)
    val ai = rememberTaskHistory(p.model, task.id, "ai", task.revision)
    var undoing by remember { mutableStateOf<Long?>(null) }
    val shape = RoundedCornerShape(8.dp)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, tokens.borderSoft.color, shape).background(tokens.panel.color).testTag("task-card-history")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((id, label) in HISTORY_TABS) {
                val on = filter == id
                Column(Modifier.ppmClickable(role = androidx.compose.ui.semantics.Role.Tab) { filter = id }.testTag("task-history-tab-$id")) {
                    Row(Modifier.height(40.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (id == "all") PpmIcon(ProductIcon.History, 14.dp, if (on) tokens.text.color else tokens.text2.color)
                        T(label, 13f, if (on) tokens.text.color else tokens.text2.color, weight = if (on) FontWeight.Medium else FontWeight.Normal)
                        if (id == "ai" && ai.items.isNotEmpty()) CountBadge("${ai.items.size}")
                    }
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (on) tokens.accent.color else androidx.compose.ui.graphics.Color.Transparent))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.borderSoft.color))
        Column(Modifier.padding(horizontal = 12.dp)) {
            when {
                page.error != null -> T(page.error, 12f, tokens.error.color, modifier = Modifier.padding(vertical = 12.dp))
                page.loading && page.items.isEmpty() -> T("Loading history…", 12f, tokens.text3.color, modifier = Modifier.padding(vertical = 12.dp))
                page.items.isEmpty() -> T("Nothing here yet.", 12f, tokens.text3.color, modifier = Modifier.padding(vertical = 12.dp))
            }
            page.items.forEachIndexed { i, e ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.borderSoft.color))
                HistoryRow(e, p.settings?.statuses, p.settings?.members, phone, undoing == e.id) {
                    undoing = e.id
                    p.actions.undo(task, e) { undoing = null }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.borderSoft.color))
            Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PpmIcon(ProductIcon.Info, 14.dp, tokens.text3.color)
                T(
                    "Full detail is kept for ${TaskConst.HISTORY_DETAIL_DAYS} days and AI changes can be undone in that time. After that only created, status changes and closed remain.",
                    11.5f, tokens.text3.color, maxLines = 4,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryRow(e: TaskHistoryItem, statuses: List<TaskStatusDef>?, members: List<TaskMember>?, phone: Boolean, undoing: Boolean, onUndo: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val ai = isAiEntry(e)
    val undone = e.undoneBy != null
    val text = historyEntryText(e, statuses, members)
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("task-history-${e.id}"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MemberAvatar(if (e.actor.kind == "user") TaskConst.ME else e.actor.label, e.actor.label, if (phone) 28.dp else 22.dp, ai = ai)
        Column(Modifier.weight(1f)) {
            T("${e.actor.label} $text", 13f, if (undone) tokens.text3.color else tokens.text.color, maxLines = 6)
            e.reason?.let { T("“$it”", 12f, tokens.text2.color, maxLines = 4, modifier = Modifier.padding(top = 2.dp)) }
            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                T(whenShort(e.at), 11.5f, tokens.text3.color)
                viaText(e.via)?.let { T(it, 11.5f, tokens.text3.color) }
                if (ai && !undone) {
                    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(tokens.accent2.color.copy(alpha = 0.12f)).padding(horizontal = 6.dp)) {
                        T("AI change", 10.5f, tokens.accent2.color, weight = FontWeight.SemiBold)
                    }
                }
                if (undone) T("undone", 11.5f, tokens.text3.color)
                if (e.unattended) UnattendedBadge()
            }
        }
        if (e.undoable) {
            PillButton("Undo", primary = false, enabled = !undoing, icon = ProductIcon.Undo2, modifier = Modifier.heightIn(min = if (phone) 40.dp else 28.dp).testTag("task-undo-${e.id}"), onClick = onUndo)
        }
    }
}

/** The chat doing the task, its live state and the work check; Open chat, or Start chat. */
@Composable
private fun WorkingSessionBlock(p: TaskPagePieces) {
    val tokens = LocalPpmTokens.current
    val task = p.task
    val working = task.workingSession
    val view = sessionChipView(working, task.check, System.currentTimeMillis())
    val shape = RoundedCornerShape(8.dp)
    @Composable
    fun heading() = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        T("WORKING SESSION", 11f, tokens.text3.color, weight = FontWeight.SemiBold)
        T(com.hienlh.ppm.state.tasks.taskCode(task, task.project), 11.5f, tokens.text3.color, mono = true)
    }
    val s = working.session
    if (working.kind == "start_chat" || s == null) {
        Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, tokens.border.color, shape).padding(12.dp).testTag("task-working-none"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            heading()
            T("No chat works on this task and the project has no manager chat.", 13f, tokens.text2.color, maxLines = 3)
            PillButton("Start chat", icon = ProductIcon.Plus, modifier = Modifier.fillMaxWidth().testTag("task-start-chat")) { p.actions.startChat(task) }
        }
        return
    }
    val manager = working.kind == "manager"
    val live = when {
        view.tone == "stalled" -> "Stalled"
        view.tone == "finished" -> "Finished, no report"
        view.tone == "gone" -> "Deleted"
        s.state == "running" -> "Running"
        s.state == "waiting_card" -> "Waiting on you"
        s.state == "unknown" -> "State unknown"
        else -> "Idle"
    }
    val tone = when {
        view.tone == "stalled" -> tokens.warning.color
        view.tone == "finished" || view.tone == "need" -> tokens.error.color
        view.tone == "run" || s.state == "running" -> tokens.success.color
        else -> tokens.text2.color
    }
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, tokens.borderSoft.color, shape).background(tokens.panel.color).padding(12.dp).testTag("task-working"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(tokens.accentWash.color), contentAlignment = Alignment.Center) {
                PpmIcon(if (manager) ProductIcon.Bot else ProductIcon.MessageCircle, 16.dp, tokens.accent.color)
            }
            Column(Modifier.weight(1f)) {
                heading()
                T(if (manager) "Manager" + (s.title?.let { " · $it" } ?: "") else s.title?.trim()?.ifEmpty { null } ?: "Chat", 13.5f, tokens.text.color, weight = FontWeight.SemiBold)
                val since = if (s.runningSince != null && s.state == "running") " · ${ageShort(s.runningSince!!)} into this turn" else ""
                val stalled = if (view.tone == "stalled") view.detail?.removePrefix("stalled ")?.let { " · $it" } ?: "" else ""
                T(live + stalled + since, 12f, tone, weight = FontWeight.Medium)
                T(if (manager) "The project's manager chat · not handed to a child chat" else "Handed to this chat", 12f, tokens.text3.color, maxLines = 2)
            }
        }
        PillButton("Open chat", enabled = s.state != "gone", icon = ProductIcon.ExternalLink, modifier = Modifier.fillMaxWidth().testTag("task-open-chat")) { p.actions.openChat(task, s.sessionId) }
        LinkButton("Start a new chat for this task", Modifier.fillMaxWidth(), color = tokens.text3.color) { p.actions.startChat(task) }
    }
}

/** Every property, editable in place. */
@Composable
private fun PropertiesPanel(p: TaskPagePieces) {
    val tokens = LocalPpmTokens.current
    val task = p.task
    val members = p.settings?.members.orEmpty()
    val people = listOf(SelectOption("", "Nobody"), SelectOption(TaskConst.ME, "You")) +
        members.filter { it.archivedAt == null || it.id == task.assigneeMemberId || it.id == task.waitingOnMemberId }.map { SelectOption(it.id, it.name) }
    val assignee = members.firstOrNull { it.id == task.assigneeMemberId }
    val accounts = assignee?.let { a -> listOfNotNull(a.jiraAccountId?.let { "Jira $it" }, a.githubLogin?.let { "GitHub @$it" }, a.slackUserId?.let { "Slack $it" }).joinToString(" · ") }.orEmpty()
    val statuses = p.settings?.statuses ?: listOf(TaskStatusDef(task.statusId, task.statusName, task.baseGroup, task.closedAs, 0))
    val parent = rememberTaskDetail(p.model, task.parentId)?.data?.task
    val created = rememberTaskHistory(p.model, task.id, "status", task.revision).items.firstOrNull { it.kind == "created" }
    val lists by (p.model.tasks.store?.lists ?: kotlinx.coroutines.flow.MutableStateFlow(emptyMap())).collectAsState()
    val order = lists.values.firstNotNullOfOrNull { e ->
        val cell = e.data?.tasks.orEmpty().filter { cellKey(it) == cellKey(task) && it.baseGroup != "closed" }
        val at = cell.indexOfFirst { it.id == task.id }
        if (at >= 0) "${at + 1} of ${cell.size}" else null
    }
    Column(Modifier.fillMaxWidth().testTag("task-properties"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Prop("Status") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusGlyph(task.baseGroup, task.closedAs, 12.dp)
                Box(Modifier.weight(1f)) { SelectField(task.statusId, statuses.sortedBy { it.position }.map { SelectOption(it.id, it.name) }, { p.actions.setStatus(task, it, p.settings?.statuses) }) }
            }
        }
        Prop("Type") { SelectField(task.type, (TaskConst.TYPES + task.type).distinct().map { SelectOption(it, typeLabel(it)) }, { p.actions.patch(task, patchOf("type", it)) }) }
        Prop("Urgency", aside = if (task.urgencyRaised) "auto" else null) {
            SelectField(
                task.urgency,
                TaskConst.URGENCIES.map { u -> SelectOption(u, (URGENCY_LABEL[u] ?: u) + if (u == task.urgency && task.urgencyRaised) " (shown as ${URGENCY_LABEL[task.effectiveUrgency]})" else "") },
                { p.actions.patch(task, patchOf("urgency", it)) },
            )
            if (task.urgencyRaised) {
                T("Raised from ${URGENCY_LABEL[task.urgency]} by the due date. Due ${task.dueDate} keeps it at least ${URGENCY_LABEL[task.effectiveUrgency]}.", 12f, tokens.text3.color, maxLines = 3)
            }
        }
        Prop("Importance") { SelectField(task.importance, TaskConst.IMPORTANCES.map { SelectOption(it, IMPORTANCE_LABEL[it] ?: it) }, { p.actions.patch(task, patchOf("importance", it)) }) }
        Prop("Due") { DayField(task.dueDate, "Due date", Modifier.fillMaxWidth()) { p.actions.patch(task, patchOf("dueDate", it)) } }
        Prop("Assignee") {
            SelectField(task.assigneeMemberId ?: "", people, { p.actions.patch(task, patchOf("assigneeMemberId", it.ifEmpty { null })) })
            if (accounts.isNotEmpty()) T(accounts, 12f, tokens.text3.color)
        }
        Prop("Waiting on") {
            SelectField(task.waitingOnMemberId ?: "", people, { p.actions.patch(task, patchOf("waitingOnMemberId", it.ifEmpty { null })) })
            if (task.waitingOnMemberId != null && task.waitingSince != null) T("since ${whenShort(task.waitingSince!!)} · ${ageShort(task.waitingSince!!)}", 12f, tokens.text3.color)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.borderSoft.color))
        Prop("Project") { T(task.project, 13f, tokens.text.color) }
        Prop("Parent") {
            val pid = task.parentId
            if (pid == null) T("None", 13f, tokens.text3.color)
            else LinkButton(parent?.let { com.hienlh.ppm.state.tasks.taskCode(it, task.project) + " " + it.title } ?: "Open parent", Modifier.testTag("task-parent")) { p.actions.openById(pid, task.project) }
        }
        Prop("Tags") { TagsEditor(task.tags) { p.actions.patch(task, buildJsonObject { put("tags", JsonArray(it.map(::JsonPrimitive))) }) } }
        Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.borderSoft.color))
        CustomFields(task, p.settings?.fieldDefs.orEmpty(), { key, v -> p.actions.patch(task, buildJsonObject { put("fields", buildJsonObject { put(key, v) }) }) }, p.onManageFields)
        Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.borderSoft.color))
        T(
            "Created " + (created?.let { "${whenShort(it.at)} by ${it.actor.label}" } ?: whenShort(task.createdAt)) +
                (order?.let { " · Order $it in ${URGENCY_LABEL[task.urgency]} · ${IMPORTANCE_LABEL[task.importance]}" } ?: ""),
            12f, tokens.text3.color, maxLines = 3,
        )
    }
}

@Composable
private fun Prop(label: String, aside: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val tokens = LocalPpmTokens.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(label, 11.5f, tokens.text3.color, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
            aside?.let {
                Box(Modifier.clip(RoundedCornerShape(4.dp)).background(tokens.accentWash.color).padding(horizontal = 6.dp)) { T(it, 10.5f, tokens.accent.color) }
            }
        }
        content()
    }
}

/** The task's tags as chips, each removable, and a field that adds one on Enter or a comma. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsEditor(tags: List<String>, onSave: (List<String>) -> Unit) {
    val tokens = LocalPpmTokens.current
    val draft = rememberTextFieldState()
    fun add() {
        val t = draft.text.toString().trim().removeSuffix(",").trim().take(TaskConst.TAG_MAX)
        draft.edit { replace(0, length, "") }
        if (t.isEmpty() || t in tags || tags.size >= TaskConst.TAGS_MAX) return
        onSave(tags + t)
    }
    LaunchedEffect(draft) {
        androidx.compose.runtime.snapshotFlow { draft.text.toString() }.collect { if (it.endsWith(",")) add() }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("task-tags")) {
        for (t in tags) {
            Row(Modifier.clip(RoundedCornerShape(6.dp)).background(tokens.surfaceHover.color).padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                T(t, 12f, tokens.text2.color)
                IconBtn(ProductIcon.X, "Remove tag $t", size = 22.dp) { onSave(tags - t) }
            }
        }
        FormInput(draft, if (tags.isEmpty()) "Add a tag" else "+ tag", Modifier.width(96.dp).testTag("task-tag-input"), height = 24.dp, onFocusChanged = { if (!it) add() }, onSubmit = ::add)
    }
}

/** The project's declared fields, typed, with Manage; then the task's undeclared ones, raw. */
@Composable
private fun CustomFields(task: TaskDto, defs: List<TaskFieldDef>, onSave: (String, kotlinx.serialization.json.JsonElement) -> Unit, onManage: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val sorted = defs.sortedBy { it.position }
    val declared = sorted.map { it.key }.toSet()
    val other = task.fields.filterKeys { it !in declared }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("task-custom-fields")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("FIELDS · ${task.project.uppercase()}", 11f, tokens.text3.color, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            LinkButton("Manage", onClick = onManage)
        }
        if (sorted.isEmpty()) T("No fields declared for this project.", 12f, tokens.text3.color)
        for (d in sorted) {
            val shown = fieldValueText(task.fields[d.key]) ?: ""
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    T(d.label, 13f, tokens.text.color)
                    T(d.type, 11f, tokens.text3.color, mono = true)
                }
                Box(Modifier.weight(1.1f)) {
                    when (d.type) {
                        "date" -> DayField(shown.ifEmpty { null }, d.label, Modifier.fillMaxWidth()) { v -> onSave(d.key, v?.let(::JsonPrimitive) ?: JsonNull) }
                        "select" -> {
                            val options = listOf(SelectOption("", "—")) + d.options.orEmpty().map { SelectOption(it, it) } +
                                (if (shown.isNotEmpty() && shown !in d.options.orEmpty()) listOf(SelectOption(shown, shown)) else emptyList())
                            SelectField(shown, options, { v -> onSave(d.key, if (v.isEmpty()) JsonNull else JsonPrimitive(v)) })
                        }
                        else -> CommitInput(shown, "${d.label} (${d.type})", Modifier.fillMaxWidth(), placeholder = "—") { v ->
                            when {
                                v.isEmpty() -> onSave(d.key, JsonNull)
                                d.type == "number" -> v.toDoubleOrNull()?.let { n -> onSave(d.key, JsonPrimitive(n)) }
                                else -> onSave(d.key, JsonPrimitive(v))
                            }
                        }
                    }
                }
            }
        }
        if (other.isNotEmpty()) {
            Row {
                T("OTHER FIELDS", 11f, tokens.text3.color, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                T("not declared", 11f, tokens.text3.color)
            }
            for ((k, v) in other) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("task-undeclared-$k")) {
                    T(k, 12f, tokens.text2.color, mono = true, modifier = Modifier.weight(1f))
                    T(v.toString(), 12f, tokens.text.color, mono = true, maxLines = 4, modifier = Modifier.weight(1.1f))
                }
            }
        }
    }
}

/** The phone page: top bar, Details / Activity, and the status and Done in the thumb zone. */
@Composable
private fun TaskDetailPhone(p: TaskPagePieces, sheet: TaskSheetControls?, onBack: (() -> Unit)?) {
    val tokens = LocalPpmTokens.current
    val task = p.task
    var tab by remember { mutableStateOf("details") }
    var statusOpen by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val history = rememberTaskHistory(p.model, task.id, "all", task.revision)
    val away = history.items.count { it.unattended && it.undoneBy == null && it.at > System.currentTimeMillis() - 24 * 3_600_000L }
    val closed = task.baseGroup == "closed"
    Column(Modifier.fillMaxSize().background(tokens.bgSolid.color).testTag("task-detail-phone")) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).background(tokens.panel.color).padding(start = if (sheet != null) 12.dp else 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (sheet == null && onBack != null) IconBtn(ProductIcon.ArrowLeft, "Back to tasks", size = 44.dp, onClick = onBack)
            Box(Modifier.clip(RoundedCornerShape(4.dp)).background(tokens.surfaceHover.color).padding(horizontal = 6.dp)) { T(task.project, 13f, tokens.text.color, weight = FontWeight.Medium) }
            T("/", 12f, tokens.text3.color)
            TaskCodeText(p.model, task, task.project, 13f)
            Box(Modifier.weight(1f))
            if (sheet != null) PillButton("Full screen", primary = false, icon = ProductIcon.Maximize2, modifier = Modifier.testTag("task-sheet-full-screen"), onClick = sheet.onExpand)
            Box {
                IconBtn(ProductIcon.MoreHorizontal, "Task actions", Modifier.testTag("task-actions"), size = if (sheet != null) 28.dp else 44.dp) { more = true }
                if (more) {
                    DropdownBelowEnd(onDismiss = { more = false }, minWidth = 220.dp) {
                        MenuItem("Copy link", onClick = { more = false; p.actions.copyLink(task) })
                        MenuItem("Add child", onClick = { more = false; p.onAddChild() })
                        if (p.hasManager) MenuItem("Ask manager", onClick = { more = false; p.actions.askManager(task) })
                        MenuItem("Start a new chat for this task", onClick = { more = false; p.actions.startChat(task) })
                    }
                }
            }
            if (sheet != null) IconBtn(ProductIcon.X, "Close", Modifier.testTag("task-sheet-close"), size = 28.dp, onClick = sheet.onClose)
        }
        Row(Modifier.fillMaxWidth().background(tokens.panel.color)) {
            for ((id, label) in listOf("details" to "Details", "activity" to "Activity")) {
                val on = tab == id
                Column(Modifier.weight(1f).ppmClickable(role = androidx.compose.ui.semantics.Role.Tab) { tab = id }.testTag("task-tab-$id")) {
                    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                        T(label, 15f, if (on) tokens.text.color else tokens.text2.color, weight = if (on) FontWeight.Medium else FontWeight.Normal)
                        if (id == "activity") {
                            CountBadge("${history.items.size}")
                            if (away > 0) Dot(tokens.warning.color, 8.dp)
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (on) tokens.accent.color else tokens.borderSoft.color))
                }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (tab == "details") {
                TitleEditor(task, 18f) { p.actions.patch(task, patchOf("title", it)) }
                PhoneChips(p)
                if (away > 0) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(tokens.warning.color.copy(alpha = 0.1f)).ppmClickable { tab = "activity" }.padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        UnattendedBadge()
                        T("$away ${if (away == 1) "change" else "changes"} · see Activity", 12f, tokens.text2.color)
                    }
                }
                if (task.type == "decision" || task.decision != null) DecisionCard(p)
                RemindersCard(p.model, task.id)
                WorkingSessionBlock(p)
                ResultCard(p)
                NotesCard(p)
                LinksCard(p)
                ChildrenCard(p)
                PropertiesPanel(p)
            } else {
                HistoryTimeline(p, phone = true)
            }
        }
        Row(Modifier.fillMaxWidth().background(tokens.panel.color).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier.weight(1f).height(48.dp).clip(shape).border(1.dp, tokens.border.color, shape).background(tokens.panel.color)
                    .ppmClickable(shape = shape, onClickLabel = "Status: ${task.statusName}. Change") { statusOpen = true }
                    .padding(horizontal = 12.dp).testTag("task-status-button"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                StatusGlyph(task.baseGroup, task.closedAs, 16.dp)
                T(task.statusName, 15f, tokens.text.color, modifier = Modifier.weight(1f, fill = false))
                PpmIcon(ProductIcon.ChevronDown, 16.dp, tokens.text3.color)
            }
            Row(
                Modifier.weight(1f).height(48.dp).clip(shape)
                    .then(if (closed) Modifier.border(1.dp, tokens.accent.color, shape) else Modifier.background(tokens.accent.color))
                    .ppmClickable(shape = shape) { if (closed) p.actions.reopen(task) else p.actions.close(task) }
                    .testTag(if (closed) "task-reopen" else "task-mark-done"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                val fg = if (closed) tokens.accent.color else tokens.accentFg.color
                PpmIcon(if (closed) ProductIcon.RotateCcw else ProductIcon.Check, 18.dp, fg)
                T(if (closed) "Reopen" else "Done", 15f, fg, weight = FontWeight.Medium)
            }
        }
    }
    if (statusOpen) {
        BottomSheet(onDismiss = { statusOpen = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                TaskStatusGrid(task.project, p.settings?.statuses.orEmpty(), task.statusId) { id ->
                    statusOpen = false
                    p.actions.setStatus(task, id, p.settings?.statuses)
                }
            }
        }
    }
}

/** The phone's chips under the title: status, urgency, importance, due, the wait and the snooze, then Snooze and Remind…. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhoneChips(p: TaskPagePieces) {
    val task = p.task
    val today = localYmd()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        if (task.type != "task") TypeBadge(task.type)
        UrgencyPill(task.effectiveUrgency)
        ImportanceBars(task.importance)
        DueLabel(task.dueDate, today)
        taskWaiting(task, p.settings?.members)?.let { WaitingLabel(it.copy(dueDate = null), today) }
        SnoozeBadge(task, today)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TaskSnoozeButton(task) { patch -> p.actions.patch(task, snoozeJson(patch)) }
        TaskRemindButton(p.model, task)
        if (p.hasManager) PillButton("Ask manager", primary = false, icon = ProductIcon.MessageSquare) { p.actions.askManager(task) }
    }
}
