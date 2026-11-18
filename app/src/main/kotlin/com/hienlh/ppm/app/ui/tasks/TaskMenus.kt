package com.hienlh.ppm.app.ui.tasks

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.tasks
import com.hienlh.ppm.app.ui.BottomSheet
import com.hienlh.ppm.app.ui.ConfirmDialog
import com.hienlh.ppm.app.ui.DropdownBelowEnd
import com.hienlh.ppm.app.ui.FormInput
import com.hienlh.ppm.app.ui.InputKind
import com.hienlh.ppm.app.ui.SelectField
import com.hienlh.ppm.app.ui.SelectOption
import com.hienlh.ppm.client.taskAddReminder
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.vector
import com.hienlh.ppm.protocol.TaskDto
import com.hienlh.ppm.protocol.TaskStatusDef
import com.hienlh.ppm.state.tasks.REMINDER_NOTE_MAX
import com.hienlh.ppm.state.tasks.SnoozePatch
import com.hienlh.ppm.state.tasks.UNSNOOZE_PATCH
import com.hienlh.ppm.state.tasks.addDaysYmd
import com.hienlh.ppm.state.tasks.hasSnooze
import com.hienlh.ppm.state.tasks.isSnoozeDay
import com.hienlh.ppm.state.tasks.localMomentText
import com.hienlh.ppm.state.tasks.localYmd
import com.hienlh.ppm.state.tasks.reminderChoices
import com.hienlh.ppm.state.tasks.shortDay
import com.hienlh.ppm.state.tasks.snoozeBadgeText
import com.hienlh.ppm.state.tasks.snoozeChoices
import com.hienlh.ppm.state.tasks.urgencyLabel
import com.hienlh.ppm.protocol.TaskConst
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/*
 * The small menus and sheets of the task views: "Snooze until…" (`task-snooze-options.tsx`,
 * `task-snooze-menu.tsx`), the phone row's long-press sheet (`task-row-action-sheet.tsx`), the
 * status grid, "Remind the manager at…" (`task-reminder-button.tsx`) and the confirm host.
 */

/** The system's date picker for a `YYYY-MM-DD` day, starting at [initial], no earlier than [min]. */
internal fun pickDay(context: Context, initial: String?, min: String?, onPick: (String) -> Unit) {
    val start = initial?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
    val dialog = DatePickerDialog(context, { _, y, m, d -> onPick(LocalDate.of(y, m + 1, d).toString()) }, start.year, start.monthValue - 1, start.dayOfMonth)
    min?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let {
        dialog.datePicker.minDate = it.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    dialog.show()
}

/** A menu row: icon, label, its detail at the right; 44px on a phone. */
@Composable
internal fun TaskMenuRow(label: String, icon: ImageVector?, modifier: Modifier = Modifier, detail: String? = null, enabled: Boolean = true, accent: Boolean = false, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier.fillMaxWidth().heightIn(min = if (mobile) 44.dp else 28.dp).clip(shape)
            .ppmClickable(enabled = enabled, shape = shape, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val alpha = if (enabled) 1f else 0.45f
        icon?.let { PpmIcon(it, 16.dp, tokens.text3.color.copy(alpha = alpha)) }
        T(label, if (mobile) 15f else 13f, (if (accent) tokens.accent.color else tokens.text.color).copy(alpha = alpha))
        Box(Modifier.weight(1f))
        detail?.let { T(it, 11.5f, tokens.text3.color.copy(alpha = alpha)) }
    }
}

private fun snoozeIcon(id: String): ImageVector = when (id) {
    "tomorrow" -> ProductIcon.Circle.vector
    "week" -> ProductIcon.Moon.vector
    "date" -> ProductIcon.CalendarClock.vector
    "link" -> ProductIcon.Link.vector
    else -> ProductIcon.Check.vector
}

/** The "Snooze until…" choices; Pick a date… opens a day field in place, saved with Snooze. */
@Composable
internal fun TaskSnoozeOptions(task: TaskDto, onPick: (SnoozePatch) -> Unit, pickingDate: Boolean = false, today: String = localYmd()) {
    val tokens = LocalPpmTokens.current
    val context = LocalContext.current
    var picking by remember { mutableStateOf(pickingDate) }
    var day by remember { mutableStateOf(addDaysYmd(today, 3)) }
    Column(Modifier.fillMaxWidth().testTag("task-snooze-options"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (c in snoozeChoices(task, today)) {
            TaskMenuRow(c.label, snoozeIcon(c.id), Modifier.testTag("snooze-choice-${c.id}"), detail = c.detail, enabled = !c.disabled, accent = c.id == "clear") {
                val p = c.patch
                if (p != null) onPick(p) else picking = !picking
            }
        }
        if (picking) {
            val ok = isSnoozeDay(day, today)
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val shape = RoundedCornerShape(6.dp)
                Box(
                    Modifier.weight(1f).heightIn(min = 28.dp).clip(shape).border(1.dp, tokens.border.color, shape)
                        .ppmClickable(shape = shape, onClickLabel = "Snooze until this day") { pickDay(context, day, addDaysYmd(today, 1)) { day = it } }
                        .padding(horizontal = 8.dp).testTag("snooze-date-field"),
                    contentAlignment = Alignment.CenterStart,
                ) { T(shortDay(day, today) + " · " + day, 13f, tokens.text.color) }
                PillButton("Snooze", enabled = ok, modifier = Modifier.testTag("snooze-date-save")) { onPick(com.hienlh.ppm.state.tasks.snoozeUntilPatch(day)) }
            }
        }
    }
}

/** A small filled pill button ("Snooze", "Set reminder"). */
@Composable
internal fun PillButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = true, icon: ProductIcon? = null, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(50)
    val fg = if (primary) tokens.accentFg.color else tokens.text2.color
    Row(
        modifier.heightIn(min = 28.dp).clip(shape)
            .then(if (primary) Modifier.background(tokens.accent.color.copy(alpha = if (enabled) 1f else 0.45f)) else Modifier.border(1.dp, tokens.borderSoft.color, shape).background(tokens.panel2.color))
            .ppmClickable(enabled = enabled, shape = shape, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon?.let { PpmIcon(it, 14.dp, fg) }
        T(label, 11.5f, fg, weight = if (primary) FontWeight.Medium else FontWeight.Normal)
    }
}

/** A heading over a menu's choices: small caps on a tablet, a sheet title on a phone. */
@Composable
internal fun MenuHeading(text: String) {
    val tokens = LocalPpmTokens.current
    if (LocalPpmMobile.current) T(text, 15f, tokens.text.color, modifier = Modifier.padding(bottom = 8.dp))
    else T(text.uppercase(), 11f, tokens.text3.color, weight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
}

/** A popover on a tablet (below its trigger), a bottom sheet on a phone. */
@Composable
internal fun TaskPopover(onDismiss: () -> Unit, tag: String, content: @Composable ColumnScope.() -> Unit) {
    if (LocalPpmMobile.current) {
        BottomSheet(onDismiss = onDismiss) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 12.dp).testTag(tag), content = content)
        }
    } else {
        DropdownBelowEnd(onDismiss = onDismiss, minWidth = 300.dp) {
            Column(Modifier.width(320.dp).padding(2.dp).testTag(tag), content = content)
        }
    }
}

/** The task page's Snooze button: "Snooze", or "Snoozed · Thu 20 Nov" while one is set. */
@Composable
internal fun TaskSnoozeButton(task: TaskDto, onSnooze: (SnoozePatch) -> Unit) {
    if (task.baseGroup == "closed") return
    var open by remember { mutableStateOf(false) }
    val snoozed = hasSnooze(task.snoozedUntil, task.snoozeUntilChange)
    val label = when {
        !snoozed -> "Snooze"
        task.snoozedUntil != null -> "Snoozed · ${shortDay(task.snoozedUntil!!, localYmd())}"
        else -> "Snoozed · until a link changes"
    }
    Box {
        Pill(label, on = snoozed, icon = ProductIcon.Moon, modifier = Modifier.testTag("task-snooze-button")) { open = !open }
        if (open) {
            TaskPopover({ open = false }, "task-snooze-popover") {
                MenuHeading("Snooze until…")
                TaskSnoozeOptions(task, { open = false; onSnooze(it) })
            }
        }
    }
}

/** The project's statuses as big buttons, three to a row (`TaskStatusGrid`). */
@Composable
internal fun TaskStatusGrid(project: String, statuses: List<TaskStatusDef>, currentId: String, onPick: (String) -> Unit) {
    val tokens = LocalPpmTokens.current
    Column(Modifier.fillMaxWidth().testTag("task-status-grid"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        T("Move to · $project statuses", 12f, tokens.text3.color, weight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 4.dp))
        if (statuses.isEmpty()) {
            T("Loading statuses…", 14f, tokens.text3.color, modifier = Modifier.padding(4.dp))
            return@Column
        }
        for (row in statuses.sortedBy { it.position }.chunked(3)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in row) {
                    val on = s.id == currentId
                    val shape = RoundedCornerShape(12.dp)
                    Row(
                        Modifier.weight(1f).heightIn(min = 48.dp).clip(shape)
                            .border(1.dp, if (on) tokens.accentWashBorder.color else tokens.borderSoft.color, shape)
                            .background(if (on) tokens.accentWash.color else tokens.panel.color)
                            .ppmClickable(shape = shape) { onPick(s.id) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("status-${s.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatusGlyph(s.baseGroup, s.closedAs, 16.dp)
                        T(s.name, 13f, if (on) tokens.text.color else tokens.text2.color, weight = if (on) FontWeight.Medium else FontWeight.Normal, maxLines = 2)
                    }
                }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * The sheet a long-press on a phone task row opens: the statuses, Urgency, Reorder… (Move up /
 * Move down inside the task's cell), Snooze until…, Ask manager about this and Copy link.
 */
@Composable
internal fun TaskRowActionSheet(
    task: TaskDto,
    showProject: Boolean,
    statuses: List<TaskStatusDef>?,
    up: TaskDto?,
    down: TaskDto?,
    hasManager: Boolean,
    actions: TaskActions,
    onClose: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var open by remember(task.id) { mutableStateOf<String?>(null) }
    fun act(f: () -> Unit) {
        onClose()
        f()
    }
    BottomSheet(onDismiss = onClose) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 8.dp).testTag("task-action-sheet")) {
            Row(Modifier.padding(start = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                T(com.hienlh.ppm.state.tasks.taskCode(task, if (showProject) null else task.project), 12.5f, tokens.text3.color, mono = true)
                T(task.title, 14f, tokens.text.color, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
            }
            TaskStatusGrid(task.project, statuses.orEmpty(), task.statusId) { id -> act { actions.setStatus(task, id, statuses) } }
            Box(Modifier.padding(vertical = 8.dp).fillMaxWidth().heightIn(min = 1.dp).background(tokens.borderSoft.color))
            TaskMenuRow("Urgency", ProductIcon.Zap.vector, detail = urgencyLabel(task.effectiveUrgency)) { open = if (open == "urgency") null else "urgency" }
            if (open == "urgency") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (u in TaskConst.URGENCIES) {
                        val on = task.urgency == u
                        val shape = RoundedCornerShape(12.dp)
                        Box(
                            Modifier.weight(1f).heightIn(min = 44.dp).clip(shape)
                                .border(1.dp, if (on) tokens.accentWashBorder.color else tokens.borderSoft.color, shape)
                                .background(if (on) tokens.accentWash.color else tokens.panel.color)
                                .ppmClickable(shape = shape) { act { actions.moveUrgency(task, u) } }
                                .testTag("urgency-$u"),
                            contentAlignment = Alignment.Center,
                        ) { T(urgencyLabel(u), 13f, if (on) tokens.text.color else tokens.text2.color) }
                    }
                }
            }
            TaskMenuRow("Reorder…", ProductIcon.ArrowUpDown.vector) { open = if (open == "reorder") null else "reorder" }
            if (open == "reorder") {
                Column(Modifier.padding(start = 32.dp)) {
                    TaskMenuRow("Move up", ProductIcon.ArrowUp.vector, enabled = up != null) { up?.let { act { actions.reorder(task, it.id, null) } } }
                    TaskMenuRow("Move down", ProductIcon.ArrowDown.vector, enabled = down != null) { down?.let { act { actions.reorder(task, null, it.id) } } }
                    if (up == null && down == null) {
                        T("Order changes only among tasks of the same urgency, importance and project.", 12f, tokens.text3.color, maxLines = 3, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    }
                }
            }
            if (task.baseGroup != "closed") {
                TaskMenuRow("Snooze until…", ProductIcon.Moon.vector, detail = if (task.snoozed) snoozeBadgeText(task, localYmd()) else null) {
                    open = if (open == "snooze") null else "snooze"
                }
                if (open == "snooze") {
                    Box(Modifier.padding(start = 32.dp, bottom = 8.dp)) { TaskSnoozeOptions(task, { p -> act { actions.snooze(task, p, announce = true) } }) }
                }
            }
            if (hasManager) TaskMenuRow("Ask manager about this", ProductIcon.MessageSquare.vector) { act { actions.askManager(task) } }
            TaskMenuRow("Copy link", ProductIcon.Link.vector) { act { actions.copyLink(task) } }
        }
    }
}

/** "Remind…" on a task's page: a one-shot reminder to the project's manager chat. */
@Composable
internal fun TaskRemindButton(model: AppModel, task: TaskDto, onSet: () -> Unit = {}) {
    if (task.baseGroup == "closed") return
    var open by remember { mutableStateOf(false) }
    Box {
        Pill("Remind…", on = false, icon = ProductIcon.CalendarClock, modifier = Modifier.testTag("task-remind-button")) { open = !open }
        if (open) {
            TaskPopover({ open = false }, "task-remind-popover") {
                MenuHeading("Remind the manager at…")
                TaskRemindOptions(task) { at, zone, note, done ->
                    setReminder(model, task, at, zone, note) { ok ->
                        done()
                        if (ok) {
                            open = false
                            onSet()
                        }
                    }
                }
            }
        }
    }
}

private fun setReminder(model: AppModel, task: TaskDto, at: String, zone: String, note: String, done: (Boolean) -> Unit) {
    val api = model.api ?: return done(false)
    model.scope.launch {
        try {
            api.taskAddReminder(task.id, at, zone, note.trim().ifEmpty { null })
            val device = ZoneId.systemDefault().id
            val where = if (zone == device) "" else " (${zone.substringAfterLast('/')})"
            model.toast(ErrorToastMessage("Reminder set for ${at.drop(11)}$where", kind = ErrorToastMessage.Kind.SUCCESS))
            done(true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            model.toast(ErrorToastMessage(e.message?.ifBlank { null } ?: "Could not set the reminder"))
            done(false)
        }
    }
}

/** The reminder's choices: in an hour, this evening, tomorrow morning, or a time on a clock of your choosing. */
@Composable
internal fun TaskRemindOptions(task: TaskDto, onSave: (at: String, zone: String, note: String, done: () -> Unit) -> Unit) {
    val tokens = LocalPpmTokens.current
    val context = LocalContext.current
    val device = remember { ZoneId.systemDefault().id }
    var zone by remember { mutableStateOf(device) }
    var at by remember { mutableStateOf(localMomentText(Instant.now().plusSeconds(7200), ZoneId.systemDefault()).dropLast(2) + "00") }
    val note = rememberTextFieldState()
    var busy by remember { mutableStateOf(false) }
    val choices = remember(zone) { reminderChoices(Instant.now(), ZoneId.of(zone)) }
    val zones = remember(zone) { ZoneId.getAvailableZoneIds().sorted().let { if (zone in it) it else listOf(zone) + it } }
    fun save(moment: String) {
        busy = true
        onSave(moment, zone, note.text.toString().take(REMINDER_NOTE_MAX)) { busy = false }
    }
    Column(Modifier.fillMaxWidth().testTag("task-remind-options"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (c in choices) {
            TaskMenuRow(c.label, ProductIcon.CalendarClock.vector, Modifier.testTag("remind-choice-${c.id}"), detail = c.at.drop(11) + if (c.id == "morning") " tomorrow" else "", enabled = !busy) { save(c.at) }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            T("PICK A TIME", 11f, tokens.text3.color, weight = FontWeight.SemiBold)
            val shape = RoundedCornerShape(6.dp)
            Box(
                Modifier.fillMaxWidth().heightIn(min = 28.dp).clip(shape).border(1.dp, tokens.border.color, shape)
                    .ppmClickable(shape = shape, onClickLabel = "Remind at") {
                        val start = runCatching { LocalDateTime.parse(at) }.getOrNull() ?: LocalDateTime.now()
                        val d = DatePickerDialog(context, { _, y, m, day ->
                            TimePickerDialog(context, { _, h, min -> at = "%04d-%02d-%02dT%02d:%02d".format(y, m + 1, day, h, min) }, start.hour, start.minute, true).show()
                        }, start.year, start.monthValue - 1, start.dayOfMonth)
                        d.show()
                    }
                    .padding(horizontal = 8.dp).testTag("remind-at-field"),
                contentAlignment = Alignment.CenterStart,
            ) { T(at.replace('T', ' '), 13f, tokens.text.color) }
            SelectField(zone, zones.map { SelectOption(it, if (it == device) "$it (this device)" else it) }, { zone = it })
            FormInput(note, "Note for the manager (optional)", Modifier.fillMaxWidth(), kind = InputKind.Url)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PillButton("Set reminder", enabled = !busy && at.isNotEmpty(), modifier = Modifier.testTag("remind-save")) { save(at) }
            }
        }
        T("The project's manager chat gets it once it is idle. Closing the task removes it.", 11f, tokens.text3.color, maxLines = 3, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

/** The question a task view asks before it acts (`useTaskConfirm`). */
@Composable
fun TaskConfirmHost(model: AppModel) {
    val c = model.tasks.confirm ?: return
    ConfirmDialog(
        title = c.title,
        message = c.body,
        confirm = c.confirm,
        destructive = c.destructive,
        confirmTestTag = "task-confirm",
        onConfirm = {
            model.tasks.confirm = null
            c.onYes()
        },
        onDismiss = { model.tasks.confirm = null },
    )
}

/** A small label used by sheets: a dot under the snoozed row. */
@Composable
internal fun Dot(color: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.Dp = 6.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(50)).background(color))
}

