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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.TaskTarget
import com.hienlh.ppm.app.tasks
import com.hienlh.ppm.app.ui.BottomSheet
import com.hienlh.ppm.app.ui.DropdownBelowStart
import com.hienlh.ppm.app.ui.FooterButtonBox
import com.hienlh.ppm.app.ui.FooterChip
import com.hienlh.ppm.app.ui.FooterChipTone
import com.hienlh.ppm.app.ui.FooterPopover
import com.hienlh.ppm.app.ui.FooterPopoverAlign
import com.hienlh.ppm.app.ui.FooterPopoverFooter
import com.hienlh.ppm.app.ui.FooterPopoverHeader
import com.hienlh.ppm.app.ui.FormTextArea
import com.hienlh.ppm.app.ui.LocalFooterContentColor
import com.hienlh.ppm.app.ui.LocalFooterTextStyle
import com.hienlh.ppm.app.ui.MenuItem
import com.hienlh.ppm.app.ui.MenuSeparator
import com.hienlh.ppm.client.TaskListQuery
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.protocol.TaskConst
import com.hienlh.ppm.protocol.TaskDto
import com.hienlh.ppm.protocol.TaskProjectSettings
import com.hienlh.ppm.protocol.TaskProjectSummary
import com.hienlh.ppm.state.tasks.IMPORTANCE_LABEL
import com.hienlh.ppm.state.tasks.URGENCY_LABEL
import com.hienlh.ppm.state.tasks.chatTaskStripLabel
import com.hienlh.ppm.state.tasks.chatTaskStripView
import com.hienlh.ppm.state.tasks.chatTasksQuery
import com.hienlh.ppm.state.tasks.chipListView
import com.hienlh.ppm.state.tasks.isWaitingOnMe
import com.hienlh.ppm.state.tasks.localYmd
import com.hienlh.ppm.state.tasks.peekChipTitle
import com.hienlh.ppm.state.tasks.peekDuePresets
import com.hienlh.ppm.state.tasks.peekLinks
import com.hienlh.ppm.state.tasks.sessionChipView
import com.hienlh.ppm.state.tasks.taskCode
import com.hienlh.ppm.state.tasks.taskWaiting
import com.hienlh.ppm.state.tasks.tasksChipLabel

/*
 * A task inside a chat: the strip at the top of a chat that works on tasks (`chat-task-strip/`),
 * a task's quick view (`peek/`) and the manager chat's Tasks chip (`chip/`).
 */

private fun TaskDto.target() = TaskTarget(id, project, title, number)

/**
 * The strip at the top of a chat that works on tasks: "#12 Fix login", its status (or on whom it
 * waits), due date and snooze; a tap opens the quick view. Several tasks show the first in "what
 * to do first" order and "+N", which lists them all. Nothing while the chat works on no open task.
 */
@Composable
fun ChatTaskStrip(model: AppModel, sessionId: String?, projectName: String?, modifier: Modifier = Modifier) {
    if (sessionId.isNullOrEmpty()) return
    LaunchedEffect(model.tasks, model.events) { model.tasks.bindEvents(model.events) }
    val entry = rememberTaskList(model, chatTasksQuery(sessionId))
    val view = entry?.data?.tasks?.let { chatTaskStripView(it, sessionId) } ?: return
    ChatTaskStripView(view.primary, view.all, view.extra, projectName, model, modifier) { model.tasks.peek = it.target() }
}

@Composable
internal fun ChatTaskStripView(
    primary: TaskDto,
    all: List<TaskDto>,
    extra: Int,
    projectName: String?,
    model: AppModel?,
    modifier: Modifier = Modifier,
    onPeek: (TaskDto) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    var listOpen by remember { mutableStateOf(false) }
    val label = chatTaskStripLabel(com.hienlh.ppm.state.tasks.ChatTaskStripView(primary, all, extra))
    Column(
        modifier.fillMaxWidth().background(tokens.bgSolid.color).semantics { contentDescription = label }.testTag("chat-task-strip"),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = if (mobile) 8.dp else 12.dp, vertical = 4.dp).padding(end = if (mobile) 32.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier.weight(1f).height(if (mobile) 26.dp else 28.dp).clip(RoundedCornerShape(6.dp))
                    .ppmClickable(onClickLabel = "Show #${primary.number} ${primary.title}") { onPeek(primary) }
                    .padding(horizontal = 6.dp).testTag("chat-task-strip-open"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (mobile) 6.dp else 8.dp),
            ) {
                if (!mobile) PpmIcon(ProductIcon.ListTodo, 14.dp, tokens.text3.color)
                StripRowContent(primary, projectName, model, Modifier.weight(1f))
            }
            if (extra > 0) {
                Box {
                    val shape = RoundedCornerShape(50)
                    Box(
                        Modifier.height(if (mobile) 26.dp else 28.dp).clip(shape)
                            .border(1.dp, if (listOpen) tokens.accentWashBorder.color else tokens.borderSoft.color, shape)
                            .background(if (listOpen) tokens.accentWash.color else tokens.panel2.color)
                            .ppmClickable(onClickLabel = "$extra more ${if (extra == 1) "task" else "tasks"} this chat works on") { listOpen = !listOpen }
                            .padding(horizontal = 8.dp).testTag("chat-task-strip-more"),
                        contentAlignment = Alignment.Center,
                    ) { T("+$extra", 11f, tokens.text2.color, mono = true) }
                    if (listOpen) {
                        val rows: @Composable ColumnScope.() -> Unit = {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                PpmIcon(ProductIcon.ListTodo, 14.dp, tokens.accent.color)
                                T("Tasks this chat works on", if (mobile) 14f else 13f, tokens.text.color, weight = FontWeight.SemiBold)
                                CountBadge("${all.size}")
                            }
                            Column(Modifier.testTag("chat-task-strip-list")) {
                                for (t in all) {
                                    Row(
                                        Modifier.fillMaxWidth().heightIn(min = if (mobile) 44.dp else 28.dp).clip(RoundedCornerShape(6.dp))
                                            .ppmClickable(onClickLabel = "Show #${t.number} ${t.title}") { listOpen = false; onPeek(t) }
                                            .padding(horizontal = 10.dp).testTag("chat-task-strip-row-${t.id}"),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) { StripRowContent(t, projectName, model, Modifier.weight(1f)) }
                                }
                            }
                        }
                        if (mobile) BottomSheet(onDismiss = { listOpen = false }) { Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), content = rows) }
                        else DropdownBelowStart(onDismiss = { listOpen = false }, minWidth = 380.dp, content = rows)
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.border.color))
    }
}

/** Glyph, code, title, then the status (or the wait), the due date and the snooze. */
@Composable
private fun StripRowContent(t: TaskDto, projectName: String?, model: AppModel?, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    val today = localYmd()
    StatusGlyph(t.baseGroup, t.closedAs, 12.dp, name = t.statusName)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (model != null) TaskCodeText(model, t, projectName) else T(taskCode(t, projectName), 11.5f, tokens.text3.color, mono = true)
        T(t.title, 12.5f, tokens.text.color, modifier = Modifier.weight(1f, fill = false))
    }
    val waiting = taskWaiting(t)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (waiting != null) Box(Modifier.widthIn(max = 200.dp)) { WaitingLabel(waiting, today) }
        else T(t.statusName, 11.5f, tokens.text3.color, modifier = Modifier.widthIn(max = 120.dp))
        if (t.dueDate != null && waiting == null) DueLabel(t.dueDate, today)
        SnoozeBadge(t, today, compact = true)
    }
}

/**
 * The quick view the strip, a task chip or a sent message's chip opened (`TaskChipPeek`): a small
 * panel on a tablet, a bottom sheet on a phone, with the task's window (or full screen) one
 * button away.
 */
@Composable
fun TaskPeekHost(model: AppModel, target: TaskTarget) {
    val close = { model.tasks.peek = null }
    if (LocalPpmMobile.current) {
        val height = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
        BottomSheet(onDismiss = close) {
            Column(Modifier.fillMaxWidth().heightIn(max = height).testTag("task-peek-sheet")) {
                TaskPeekBody(model, target, sheet = true, onLeave = close, onOpenPage = { close(); model.tasks.openTask(it.id, it.project, it.title, it.number, keep = true) })
            }
        }
    } else {
        Dialog(onDismissRequest = close) {
            val tokens = LocalPpmTokens.current
            val shape = RoundedCornerShape(10.dp)
            Column(Modifier.width(400.dp).heightIn(max = 560.dp).clip(shape).border(1.dp, tokens.border.color, shape).background(tokens.panel.color).testTag("task-peek-popover")) {
                TaskPeekBody(model, target, sheet = false, onLeave = close, onClose = close, onOpenPage = { close(); model.tasks.openTask(it.id, it.project, it.title, it.number, keep = true) })
            }
        }
    }
}

/**
 * A task's quick view: code and title, the fields a glance ends in changing, the links, Open chat (a
 * chat the task was handed to), Ask manager (the project's manager chat) and the full page.
 */
@Composable
internal fun ColumnScope.TaskPeekBody(
    model: AppModel,
    target: TaskTarget,
    sheet: Boolean,
    onLeave: () -> Unit,
    onOpenPage: (TaskTarget) -> Unit,
    onBack: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    val entry = rememberTaskDetail(model, target.id)
    val detail = entry?.data
    val task = detail?.task
    val settings = rememberTaskSettings(model, task?.project ?: target.project)
    val actions = rememberTaskActions(model)
    val chat = task?.let { sessionChipView(it.workingSession, it.check, System.currentTimeMillis()) }
    Row(
        Modifier.fillMaxWidth().padding(start = if (onBack != null) 4.dp else 12.dp, end = 8.dp, top = if (sheet) 4.dp else 8.dp, bottom = 8.dp).testTag("task-peek"),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (onBack != null) IconBtn(ProductIcon.ChevronLeft, "Back to tasks", Modifier.testTag("task-peek-back"), size = 28.dp, onClick = onBack)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val number = task?.number ?: target.number
            if (number != null) T(if ((task?.project ?: target.project) == target.project) "#$number" else "${task?.project}/$number", 12f, tokens.text3.color, mono = true, modifier = Modifier.padding(top = 2.dp))
            T(task?.title ?: target.title, if (sheet) 15f else 13f, if (task?.baseGroup == "closed") tokens.text2.color else tokens.text.color, weight = FontWeight.SemiBold, maxLines = 4, modifier = Modifier.weight(1f).testTag("task-peek-title"))
        }
        if (onClose != null) IconBtn(ProductIcon.X, "Close", Modifier.testTag("task-peek-close"), size = 24.dp, onClick = onClose)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.borderSoft.color))
    Column(Modifier.weight(1f, fill = false).heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(horizontal = if (sheet) 16.dp else 12.dp, vertical = if (sheet) 12.dp else 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (task == null) {
            Box(Modifier.fillMaxWidth().heightIn(min = 96.dp), contentAlignment = Alignment.Center) {
                if (entry?.error != null) T(entry.error!!, 13f, tokens.text3.color, maxLines = 4)
                else if (entry?.loading != false) PpmIcon(ProductIcon.Loader2, 16.dp, tokens.text3.color, spinning = true)
                else T("This task could not be read.", 13f, tokens.text3.color)
            }
        } else {
            TaskPeekFields(task, settings, actions)
            PeekLinks(task, detail, onOpenChat = { sid -> onLeave(); actions.openChat(task, sid) }, info = actions::info)
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.borderSoft.color))
    // Open chat only for a chat the task was handed to; the manager chat is Ask manager's (`taskChatButtons`).
    val buttons = task?.let { model.tasks.chatButtons(it) }
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("task-peek-footer")) {
        // Both beside the page button do not fit a narrow footer with their icons: below 350 dp the
        // two drop their icons and keep their whole words.
        val bare = buttons?.crowded == true && maxWidth < 350.dp
        Row(Modifier.fillMaxWidth().padding(horizontal = if (sheet) 12.dp else 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            buttons?.openChat?.let { open ->
                PillButton("Open chat", primary = false, enabled = chat?.tone != "gone", icon = ProductIcon.MessageCircle.takeUnless { bare }, modifier = Modifier.testTag("task-peek-chat")) {
                    if (task != null && chat?.tone != "gone") {
                        onLeave()
                        actions.openChat(task, open.sessionId)
                    }
                }
            }
            if (buttons?.askManager != null) {
                // A manager chat working on the task that was deleted: nothing to ask.
                val gone = buttons.openChat == null && chat?.tone == "gone"
                PillButton("Ask manager", primary = false, enabled = !gone, icon = ProductIcon.MessageSquare.takeUnless { bare }, modifier = Modifier.testTag("task-peek-ask-manager")) {
                    if (task != null && !gone) {
                        onLeave()
                        actions.askManager(task)
                    }
                }
            }
            Box(Modifier.weight(1f))
            val page = task?.target() ?: target
            if (sheet) PillButton("Full screen", icon = ProductIcon.Maximize2, modifier = Modifier.testTag("task-peek-page")) { onOpenPage(page) }
            else PillButton("Open in window", icon = ProductIcon.AppWindow, modifier = Modifier.testTag("task-peek-page")) { onOpenPage(page) }
        }
    }
}

/** Status, urgency, importance, assignee, waiting on and due as chips, each a menu; then the next step. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskPeekFields(task: TaskDto, settings: TaskProjectSettings?, actions: TaskActions) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val unnamed = if (settings != null) "Unknown member" else "…"
    val members = settings?.members.orEmpty()
    fun nameOf(id: String?): String? = when {
        id == null -> null
        id == TaskConst.ME -> "You"
        else -> members.firstOrNull { it.id == id }?.name ?: unnamed
    }
    val people = listOf("" to "Nobody", TaskConst.ME to "You") +
        members.filter { it.archivedAt == null || it.id == task.assigneeMemberId || it.id == task.waitingOnMemberId }.map { it.id to it.name }
    val assignee = nameOf(task.assigneeMemberId)
    val waiting = nameOf(task.waitingOnMemberId)
    val urgency = URGENCY_LABEL[task.effectiveUrgency] ?: task.effectiveUrgency
    val statuses = settings?.statuses?.sortedBy { it.position }.orEmpty().ifEmpty { listOf(com.hienlh.ppm.protocol.TaskStatusDef(task.statusId, task.statusName, task.baseGroup, task.closedAs, 0)) }
    FlowRow(
        Modifier.testTag("task-peek-chips"),
        horizontalArrangement = Arrangement.spacedBy(if (mobile) 8.dp else 6.dp),
        verticalArrangement = Arrangement.spacedBy(if (mobile) 14.dp else 6.dp),
    ) {
        MenuChip("status", peekChipTitle("Status", task.statusName), false, statuses.map { it.id to it.name }, task.statusId, { actions.setStatus(task, it, settings?.statuses) }) {
            StatusGlyph(task.baseGroup, task.closedAs, 12.dp)
            T(task.statusName, if (mobile) 13f else 12f, tokens.text.color)
        }
        MenuChip(
            "urgency",
            if (task.urgencyRaised) "Urgency: $urgency (raised from ${URGENCY_LABEL[task.urgency]} by the due date)" else peekChipTitle("Urgency", urgency),
            false, TaskConst.URGENCIES.map { it to (URGENCY_LABEL[it] ?: it) }, task.urgency, { actions.patch(task, patchOf("urgency", it)) },
        ) {
            PpmIcon(ProductIcon.Clock, 14.dp, urgencyColor(tokens, task.effectiveUrgency))
            T(urgency, if (mobile) 13f else 12f, urgencyColor(tokens, task.effectiveUrgency), weight = FontWeight.Medium)
            if (task.urgencyRaised) T("auto", 10f, tokens.accent.color)
        }
        MenuChip("importance", peekChipTitle("Importance", IMPORTANCE_LABEL[task.importance]), false, TaskConst.IMPORTANCES.map { it to (IMPORTANCE_LABEL[it] ?: it) }, task.importance, { actions.patch(task, patchOf("importance", it)) }) {
            ImportanceBars(task.importance)
            T(IMPORTANCE_LABEL[task.importance] ?: task.importance, if (mobile) 13f else 12f, tokens.text.color)
        }
        MenuChip("assignee", peekChipTitle("Assignee", assignee), assignee == null, people, task.assigneeMemberId ?: "", { actions.patch(task, patchOf("assigneeMemberId", it.ifEmpty { null })) }) {
            if (assignee != null) {
                MemberAvatar(task.assigneeMemberId ?: "", assignee, 16.dp)
                T(assignee, if (mobile) 13f else 12f, tokens.text.color, modifier = Modifier.widthIn(max = 160.dp))
            } else {
                PpmIcon(ProductIcon.UserPlus, 14.dp, tokens.text3.color)
                T("Assignee", if (mobile) 13f else 12f, tokens.text3.color)
            }
        }
        MenuChip("waiting", peekChipTitle("Waiting on", waiting), waiting == null, people, task.waitingOnMemberId ?: "", { actions.patch(task, patchOf("waitingOnMemberId", it.ifEmpty { null })) }) {
            PpmIcon(ProductIcon.Hand, 14.dp, tokens.warning.color)
            if (waiting != null) {
                T("Waiting on ", if (mobile) 13f else 12f, tokens.text3.color)
                T(waiting, if (mobile) 13f else 12f, tokens.text.color, modifier = Modifier.widthIn(max = 160.dp))
            } else {
                T("Waiting on", if (mobile) 13f else 12f, tokens.text3.color)
            }
        }
        DueChip(task.dueDate) { actions.patch(task, patchOf("dueDate", it)) }
    }
    NextStepPeek(task.nextStep) { actions.patch(task, patchOf("nextStep", it)) }
}

/** A chip whose press opens a menu of the field's choices, the current one marked. */
@Composable
private fun MenuChip(
    field: String,
    title: String,
    empty: Boolean,
    choices: List<Pair<String, String>>,
    value: String,
    onChange: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ChipBox(title, empty, open, "task-peek-chip-$field", { open = true }, content)
        if (open) {
            DropdownBelowStart(onDismiss = { open = false }, minWidth = 176.dp) {
                for ((v, label) in choices) {
                    MenuItem(
                        label,
                        onClick = {
                            open = false
                            if (v != value) onChange(v)
                        },
                        lit = v == value,
                        trailing = if (v == value) ({ PpmIcon(ProductIcon.Check, 14.dp, LocalPpmTokens.current.accent.color) }) else null,
                        modifier = Modifier.testTag("task-peek-choice-$v"),
                    )
                }
            }
        }
    }
}

/** The chip's box: 28 dp (32 dp on a phone), a set field solid, an empty one faint. */
@Composable
private fun ChipBox(title: String, empty: Boolean, open: Boolean, tag: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier.heightIn(min = if (mobile) 32.dp else 28.dp).clip(shape)
            .border(1.dp, if (open) tokens.accent.color else tokens.borderSoft.color, shape)
            .background(if (empty) androidx.compose.ui.graphics.Color.Transparent else tokens.surfaceHover.color)
            .ppmClickable(shape = shape, onClickLabel = title, onClick = onClick)
            .semantics { contentDescription = title }
            .padding(horizontal = 8.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

/** Due: Today, Tomorrow, In a week, Pick a date… and, when set, No due date. */
@Composable
private fun DueChip(value: String?, onSave: (String?) -> Unit) {
    val tokens = LocalPpmTokens.current
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val today = localYmd()
    val title = if (value != null) "Due date: ${com.hienlh.ppm.state.tasks.dueView(value, today)?.label ?: value} ($value)" else peekChipTitle("Due date", null)
    fun choose(v: String?) {
        if (v != value) onSave(v)
    }
    Box {
        ChipBox(title, value == null, open, "task-peek-chip-due", { open = true }) {
            if (value != null) {
                DueLabel(value, today)
            } else {
                PpmIcon(TaskIcons.Calendar, 14.dp, tokens.text3.color)
                T("Due", 12f, tokens.text3.color)
            }
        }
        if (open) {
            DropdownBelowStart(onDismiss = { open = false }, minWidth = 176.dp) {
                for (p in peekDuePresets(today)) {
                    MenuItem(p.label, onClick = { open = false; choose(p.ymd) }, trailing = { T(p.hint, 12f, tokens.text3.color) }, modifier = Modifier.testTag("task-peek-choice-${p.label}"))
                }
                MenuItem("Pick a date…", onClick = {
                    open = false
                    pickDay(context, value, null) { choose(it) }
                }, icon = { PpmIcon(TaskIcons.Calendar, 14.dp, tokens.text3.color) }, modifier = Modifier.testTag("task-peek-choice-pick"))
                if (value != null) {
                    MenuSeparator()
                    MenuItem("No due date", onClick = { open = false; choose(null) }, modifier = Modifier.testTag("task-peek-choice-none"))
                }
            }
        }
    }
}

/** The next step folded to two lines; a tap unfolds it, leaving it saves. */
@Composable
private fun NextStepPeek(value: String?, onSave: (String?) -> Unit) {
    val tokens = LocalPpmTokens.current
    var editing by remember { mutableStateOf(false) }
    if (editing) {
        val state = remember { TextFieldState(value ?: "") }
        fun commit() {
            editing = false
            val v = state.text.toString().trim().take(TaskConst.WHY_MAX)
            if (v != (value ?: "")) onSave(v.ifEmpty { null })
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            T("Next step", 11.5f, tokens.text3.color, weight = FontWeight.Medium)
            FormTextArea(state, "What happens next", Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("task-peek-next-step-input"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                LinkButton("Cancel", color = tokens.text2.color) { editing = false }
                PillButton("Save", modifier = Modifier.testTag("task-peek-next-step-save"), onClick = ::commit)
            }
        }
        return
    }
    val text = value?.trim().orEmpty()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).ppmClickable(onClickLabel = if (text.isNotEmpty()) "Next step: $text" else "Add the next step") { editing = true }
            .padding(vertical = 4.dp).testTag("task-peek-next-step"),
    ) {
        T("Next step", 11.5f, tokens.text3.color, weight = FontWeight.Medium)
        if (text.isNotEmpty()) T(text, 13f, tokens.text.color, maxLines = 2) else T("What happens next", 13f, tokens.text3.color)
    }
}

/** The task's links with their live state, four at most before "+N". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeekLinks(task: TaskDto, detail: com.hienlh.ppm.protocol.TaskDetail?, onOpenChat: (String) -> Unit, info: (String) -> Unit) {
    if (task.links.isEmpty()) return
    val tokens = LocalPpmTokens.current
    var expanded by remember(task.id) { mutableStateOf(false) }
    val (shown, hidden) = peekLinks(task.links, expanded)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("task-peek-links")) {
        T("Links", 11.5f, tokens.text3.color, weight = FontWeight.Medium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (l in shown) TaskLinkChip(l, detail, onOpenChat, info)
            if (hidden > 0) LinkButton("+$hidden", Modifier.testTag("task-peek-links-more")) { expanded = true }
        }
    }
}

/**
 * The Tasks chip in a manager chat's context row: "<n> tasks · <k> now", a red dot while something
 * is in Now. It opens the project's "what to do first" list. Nothing unless [sessionId] is a
 * manager chat (`AppTasks.managers`, from `/api/assistant/managers`) whose project has tasks on.
 */
@Composable
fun ManagerTasksChip(model: AppModel, sessionId: String?, narrow: Boolean, modifier: Modifier = Modifier) {
    val tasks = model.tasks
    LaunchedEffect(model.api) { if (tasks.managers == null) tasks.refreshManagers() }
    LaunchedEffect(tasks, model.events) { tasks.bindEvents(model.events) }
    val project = tasks.managerProjectOf(sessionId) ?: return
    // `AppTasks.projects` is read again on every tasks event, so the counts follow the list.
    val summary = tasks.projects.firstOrNull { it.name == project }
    val s = summary?.takeIf { it.enabled && it.name == project } ?: return
    ManagerTasksChipView(model, s, narrow, modifier)
}

@Composable
internal fun ManagerTasksChipView(model: AppModel, summary: TaskProjectSummary, narrow: Boolean, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    var open by remember { mutableStateOf(false) }
    val count = summary.open
    val now = summary.now
    FooterChip(
        narrow = narrow,
        modifier = modifier,
        expanded = open,
        tone = if (now > 0) FooterChipTone.AlertBorder else FooterChipTone.Default,
        title = "$count open ${if (count == 1) "task" else "tasks"} · $now now",
        contentDescription = tasksChipLabel(count, now),
        testTag = "manager-tasks-chip",
        onClick = { open = !open },
    ) {
        if (now > 0) Box(Modifier.size(6.dp).clip(CircleShape).background(tokens.error.color).testTag("tasks-now-dot"))
        PpmIcon(ProductIcon.ListTodo, 14.dp, tokens.text3.color)
        BasicText("$count", style = LocalFooterTextStyle.current.copy(color = tokens.text.color, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"), maxLines = 1)
        if (!narrow && !mobile) {
            BasicText(if (count == 1) "task" else "tasks", style = LocalFooterTextStyle.current.copy(color = LocalFooterContentColor.current), maxLines = 1)
            if (now > 0) BasicText("· $now now", style = LocalFooterTextStyle.current.copy(color = tokens.error.color), maxLines = 1)
        }
    }
    FooterPopover(open = open, onClose = { open = false }, label = "Tasks", align = FooterPopoverAlign.Left, maxWidth = 520.dp) {
        ManagerTasksList(model, summary.name, count) { open = false }
    }
}

/** The project's "what to do first" list; a row shows its quick view on a page of the same popover. */
@Composable
private fun ColumnScope.ManagerTasksList(model: AppModel, project: String, total: Int, onClose: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val entry = rememberTaskList(model, TaskListQuery(project))
    val tasks = entry?.data?.tasks.orEmpty()
    var mineOnly by remember { mutableStateOf(false) }
    var peek by remember { mutableStateOf<TaskTarget?>(null) }
    val mine = tasks.filter { it.deletedAt == null && isWaitingOnMe(it) }
    val view = if (mineOnly) chipListView(mine, flat = true) else chipListView(tasks)
    val more = if (mineOnly) view.more else maxOf(view.more, total - view.ids.size)
    val live = rememberLinkStates(model, view.ids)
    val actions = rememberTaskActions(model)
    peek?.let { target ->
        TaskPeekBody(
            model, target, sheet = mobile, onLeave = onClose, onBack = { peek = null },
            onOpenPage = { onClose(); model.tasks.openTask(it.id, it.project, it.title, it.number, keep = true) },
        )
        return
    }
    FooterPopoverHeader {
        PpmIcon(ProductIcon.ListTodo, 14.dp, tokens.accent.color)
        T("Tasks", if (mobile) 14f else 13f, tokens.text.color, weight = FontWeight.SemiBold)
        CountBadge("$total")
        T("$project · what to do first", if (mobile) 12f else 13f, tokens.text3.color, modifier = Modifier.weight(1f))
        Pill("Waiting on you", mineOnly, Modifier.testTag("manager-tasks-waiting-on-me"), count = mine.size.takeIf { it > 0 }, icon = ProductIcon.Hand) { mineOnly = !mineOnly }
    }
    Column(Modifier.weight(1f, fill = false).heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp).testTag("manager-tasks-list")) {
        when {
            entry?.error != null -> T(entry.error!!, 13f, tokens.error.color, modifier = Modifier.padding(12.dp))
            entry?.loading != false && tasks.isEmpty() -> T("Loading tasks…", 13f, tokens.text3.color, modifier = Modifier.padding(12.dp))
            view.groups.isEmpty() -> T(if (mineOnly) "Nothing is waiting on you" else "No open tasks", 13f, tokens.text3.color, modifier = Modifier.padding(horizontal = if (mobile) 16.dp else 12.dp, vertical = 12.dp))
        }
        for ((urgency, rows) in view.groups) {
            T((URGENCY_LABEL[urgency] ?: urgency).uppercase(), 10.5f, tokens.text3.color, weight = FontWeight.SemiBold, modifier = Modifier.padding(start = if (mobile) 16.dp else 12.dp, top = 8.dp, bottom = 2.dp))
            for (t in rows) {
                val working = live[t.id]?.workingSession ?: t.workingSession
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = if (mobile) 16.dp else 8.dp).testTag("manager-task-${t.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (mobile) 12.dp else 8.dp),
                ) {
                    StatusGlyph(t.baseGroup, t.closedAs, 14.dp, name = t.statusName)
                    Column(
                        Modifier.weight(1f).heightIn(min = if (mobile) 44.dp else 28.dp).clip(RoundedCornerShape(6.dp))
                            .ppmClickable(onClickLabel = "Show #${t.number} ${t.title}") { peek = t.target() }.padding(horizontal = 4.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            T(taskCode(t, t.project), 12f, tokens.text3.color, mono = true)
                            T(t.title, if (mobile) 14f else 13f, tokens.text.color, modifier = Modifier.weight(1f, fill = false))
                        }
                        val waiting = taskWaiting(t)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (waiting != null) WaitingLabel(waiting, localYmd())
                            else {
                                T(t.statusName, if (mobile) 12f else 11.5f, tokens.text3.color, modifier = Modifier.widthIn(max = 110.dp))
                                if (t.dueDate != null) DueLabel(t.dueDate, localYmd()) else T("—", 11.5f, tokens.text3.color)
                            }
                        }
                    }
                    Box(Modifier.widthIn(max = 160.dp)) {
                        SessionChip(working, t.check) { sid ->
                            if (sid != null) {
                                onClose()
                                actions.openChat(t.copy(workingSession = working), sid)
                            }
                        }
                    }
                }
            }
        }
    }
    FooterPopoverFooter {
        T((if (more > 0) "$more more" else "") + if (mobile) (if (more > 0) " · " else "") + "tap a task to see it" else "", 12f, tokens.text3.color, modifier = Modifier.weight(1f))
        FooterButtonBox(onClick = {
            onClose()
            model.tasks.revealInSidebar(project)
        }, testTag = "manager-tasks-show-in-sidebar") {
            PpmIcon(ProductIcon.PanelRight, 14.dp, tokens.text2.color)
            T("Show in sidebar", 12f, tokens.text2.color)
        }
    }
}

/** The app's answer to a task chip on a sent message: the task's quick view. */
fun taskRefOpener(model: AppModel) = com.hienlh.ppm.chat.TaskRefOpener { ref ->
    model.tasks.peek = TaskTarget(ref.taskId, ref.projectName, ref.title, ref.number)
}
