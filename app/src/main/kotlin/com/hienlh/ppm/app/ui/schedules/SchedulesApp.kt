package com.hienlh.ppm.app.ui.schedules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.PrefsStore
import com.hienlh.ppm.app.schedules
import com.hienlh.ppm.app.ui.BottomSheet
import com.hienlh.ppm.app.ui.ConfirmDialog
import com.hienlh.ppm.app.ui.ContextMenuPopup
import com.hienlh.ppm.app.ui.FormInput
import com.hienlh.ppm.app.ui.MenuItem
import com.hienlh.ppm.app.ui.MenuSeparator
import com.hienlh.ppm.app.ui.PpmSwitch
import com.hienlh.ppm.app.ui.ScheduleChatOption
import com.hienlh.ppm.app.ui.ScheduleCreateFormContent
import com.hienlh.ppm.app.ui.SelectField
import com.hienlh.ppm.app.ui.SelectOption
import com.hienlh.ppm.app.ui.WebDialog
import com.hienlh.ppm.app.ui.contextMenuGesture
import com.hienlh.ppm.app.ui.tasks.IconBtn
import com.hienlh.ppm.app.ui.tasks.T
import com.hienlh.ppm.app.ui.tasks.txt
import com.hienlh.ppm.client.schedulesCreate
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.state.schedules.ScheduleChip
import com.hienlh.ppm.state.schedules.ScheduleGroupId
import com.hienlh.ppm.state.schedules.ScheduleOverview
import com.hienlh.ppm.state.schedules.SchedulesFeedState
import com.hienlh.ppm.state.schedules.chatOf
import com.hienlh.ppm.state.schedules.chipCounts
import com.hienlh.ppm.state.schedules.filterSchedules
import com.hienlh.ppm.state.schedules.groupSchedules
import com.hienlh.ppm.state.schedules.lastRunLabel
import com.hienlh.ppm.state.schedules.nextRunLabel
import com.hienlh.ppm.state.schedules.parseScheduleOverview
import com.hienlh.ppm.state.schedules.projectLabel
import com.hienlh.ppm.state.schedules.projectsOf
import com.hienlh.ppm.state.schedules.scheduleAttention
import com.hienlh.ppm.state.schedules.schedulesSummary
import com.hienlh.ppm.state.settings.ScheduleProjectOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The Schedules window (`schedules-app.tsx`): every schedule of every project, filtered by project
 * and chip, grouped into Needs attention, Next 24 hours, Later and Off. On a tablet it is a list
 * with the selected schedule's details beside it once the window is 900 dp wide, and expanded
 * under its row when it is not; on a phone a list of cards whose tap opens the details in a sheet
 * and whose long press opens the actions.
 */
@Composable
fun SchedulesApp(model: AppModel, modifier: Modifier = Modifier, phone: Boolean = LocalPpmMobile.current) {
    val tokens = LocalPpmTokens.current
    val context = LocalContext.current
    val sched = model.schedules
    remember(sched) { sched.attachPrefs(PrefsStore(context)) }
    LaunchedEffect(sched, model.events) { sched.bindEvents(model.events) }
    DisposableEffect(sched) {
        val release = sched.use()
        onDispose { release() }
    }
    val feed = sched.feed
    val state = feed?.state?.collectAsState()?.value ?: SchedulesFeedState()
    val list = state.list
    val now = state.now.takeIf { it > 0 } ?: System.currentTimeMillis()
    val filter = sched.filter

    var selected by remember { mutableStateOf<Int?>(null) }
    var promptOf by remember { mutableStateOf<Int?>(null) }
    var deleting by remember { mutableStateOf<ScheduleOverview?>(null) }
    var creating by remember { mutableStateOf(false) }
    var actionsOf by remember { mutableStateOf<Pair<Int, Offset>?>(null) }

    // A caller's "Open in Schedules": select it, with every filter cleared so it is in view.
    LaunchedEffect(sched.pendingSelect) {
        sched.pendingSelect?.let { id ->
            selected = id
            sched.updateFilter { it.copy(chip = ScheduleChip.ALL, query = "", project = null) }
            sched.pendingSelect = null
        }
    }

    val shown = remember(list, filter, now) { filterSchedules(list, filter, now) }
    val groups = remember(shown, now) { groupSchedules(shown, now) }
    val counts = remember(list, filter.project, now) { chipCounts(list, filter.project, now) }
    val summary = remember(shown, now) { schedulesSummary(shown, now) }
    val current = list.firstOrNull { it.id == selected }
    val empty = state.loaded && list.isEmpty()
    fun select(s: ScheduleOverview) {
        selected = if (selected == s.id && !phone) null else s.id
    }

    BoxWithConstraints(modifier.fillMaxSize().background(tokens.bgSolid.color).testTag("schedules-app")) {
        val besideDetail = !phone && maxWidth >= 900.dp
        val wideRow = maxWidth >= 860.dp
        Column(Modifier.fillMaxSize()) {
            Toolbar(model, projectsOf(list), counts, phone) { creating = true }
            SummaryLine(summary.upcoming, summary.attention, summary.failed, summary.bypass, phone)
            Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.border.color))
            Row(Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.weight(1f).fillMaxHeight().testTag("schedules-list"),
                    contentPadding = if (phone) androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp) else androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    when {
                        state.error != null -> item { Centered(state.error.orEmpty(), tokens.error.color) }
                        !state.loaded -> item { Centered("Loading…", tokens.text3.color) }
                        empty -> item {
                            Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                PpmIcon(ProductIcon.CalendarClock, 32.dp, tokens.text3.opacity(0.5).color)
                                T("No schedules yet. A chat can schedule a reminder, or press New.", 12f, tokens.text3.color, maxLines = 3)
                            }
                        }
                        shown.isEmpty() -> item { Centered("Nothing matches these filters.", tokens.text3.color) }
                    }
                    if (!phone && shown.isNotEmpty()) item { HeaderRow(wideRow) }
                    for (g in groups) {
                        item(key = "g-${g.id.id}") {
                            T(
                                "${g.label.uppercase()} · ${g.items.size}",
                                10.5f,
                                if (g.id == ScheduleGroupId.ATTENTION) tokens.error.color else tokens.text3.color,
                                modifier = Modifier.padding(start = if (phone) 2.dp else 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp).testTag("schedules-group-${g.id.id}"),
                                weight = FontWeight.SemiBold,
                            )
                        }
                        items(g.items, key = { it.id }) { s ->
                            if (phone) {
                                ScheduleCard(model, s, now, onSelect = { select(s) }, onOpenPrompt = { promptOf = s.id }, onActions = { actionsOf = s.id to it })
                            } else {
                                Column {
                                    ScheduleRow(model, s, now, wideRow, selected == s.id, onSelect = { select(s) }, onOpenPrompt = { promptOf = s.id }, onActions = { actionsOf = s.id to it })
                                    if (selected == s.id && !besideDetail) {
                                        Box(Modifier.fillMaxWidth().background(tokens.accentWash.opacity(0.4).color).padding(12.dp)) {
                                            ScheduleDetail(model, s, now, onOpenPrompt = { promptOf = s.id }, onDelete = { deleting = s }, bare = true)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (besideDetail && current != null) {
                    Box(
                        Modifier.width(340.dp).fillMaxHeight().background(tokens.bgSolid.color)
                            .verticalScroll(rememberScrollState()).padding(14.dp).testTag("schedules-detail-pane"),
                    ) {
                        ScheduleDetail(model, current, now, onOpenPrompt = { promptOf = current.id }, onDelete = { deleting = current })
                    }
                }
            }
        }
    }

    if (phone && current != null) {
        BottomSheet(onDismiss = { selected = null }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 16.dp)) {
                ScheduleDetail(model, current, now, onOpenPrompt = { promptOf = current.id }, onDelete = { deleting = current })
            }
        }
    }
    actionsOf?.let { (id, at) ->
        val s = list.firstOrNull { it.id == id }
        if (s == null) {
            actionsOf = null
        } else {
            ActionsMenu(model, s, now, at, phone, onDismiss = { actionsOf = null }, onOpenPrompt = { promptOf = s.id }, onDelete = { deleting = s })
        }
    }
    list.firstOrNull { it.id == promptOf }?.let { s -> PromptSheet(model, s, phone) { promptOf = null } }
    deleting?.let { s ->
        ConfirmDialog(
            title = "Delete schedule",
            message = "Delete the schedule \"${s.name}\"? It will not run again.",
            confirm = "Delete",
            confirmTestTag = "schedule-delete-confirm",
            onConfirm = {
                deleting = null
                if (selected == s.id) selected = null
                sched.delete(s)
            },
            onDismiss = { deleting = null },
        )
    }
    if (creating) NewScheduleDialog(model) { creating = false }
}

@Composable
private fun Centered(text: String, color: androidx.compose.ui.graphics.Color) {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { T(text, 12f, color, maxLines = 3) }
}

/** The project, the chips with their counts, a search, and New (`schedules-toolbar.tsx`). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Toolbar(model: AppModel, projects: List<String>, counts: Map<ScheduleChip, Int>, phone: Boolean, onNew: () -> Unit) {
    val sched = model.schedules
    val filter = sched.filter
    val query = rememberTextFieldState(filter.query)
    LaunchedEffect(query) {
        snapshotFlow { query.text.toString() }.collect { q -> if (q != sched.filter.query) sched.updateFilter { it.copy(query = q) } }
    }
    val projectPicker = @Composable { m: Modifier ->
        SelectField(
            value = filter.project ?: "",
            options = listOf(SelectOption("", "All projects")) + projects.map { SelectOption(it, it) },
            onValueChange = { v -> sched.updateFilter { it.copy(project = v.ifEmpty { null }) } },
            modifier = m.testTag("schedules-project"),
        )
    }
    val search = @Composable { m: Modifier ->
        Row(m, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FormInput(query, "Search", Modifier.fillMaxWidth().testTag("schedules-search"))
        }
    }
    val chips = @Composable {
        for (chip in ScheduleChip.entries) Chip(chip, filter.chip == chip, counts[chip] ?: 0) { sched.pickChip(chip) }
    }
    if (phone) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                projectPicker(Modifier.width(140.dp))
                search(Modifier.weight(1f))
                SchedButton("New", ProductIcon.Plus, primary = true, modifier = Modifier.testTag("schedules-new"), onClick = onNew)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { chips() }
        }
    } else {
        FlowRow(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            projectPicker(Modifier.width(180.dp))
            chips()
            Spacer(Modifier.weight(1f))
            search(Modifier.width(200.dp))
            SchedButton("New", ProductIcon.Plus, primary = true, modifier = Modifier.testTag("schedules-new"), onClick = onNew)
        }
    }
}

@Composable
private fun Chip(chip: ScheduleChip, on: Boolean, count: Int, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .heightIn(min = if (LocalPpmMobile.current) 26.dp else 28.dp)
            .clip(shape)
            .border(1.dp, if (on) tokens.accentWashBorder.color else tokens.border.color, shape)
            .background(if (on) tokens.accentWash.color else androidx.compose.ui.graphics.Color.Transparent)
            .ppmClickable(shape = shape, onClick = onClick)
            .padding(horizontal = 10.dp)
            .testTag("schedules-chip-${chip.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        T(chip.label, 12f, if (on) tokens.text.color else tokens.text2.color)
        T("$count", 10.5f, if (on) tokens.accent.color else tokens.text3.color)
    }
}

@Composable
private fun SummaryLine(upcoming: Int, attention: Int, failed: Int, bypass: Int, phone: Boolean) {
    val tokens = LocalPpmTokens.current
    val text = buildAnnotatedString {
        val strong = SpanStyle(fontWeight = FontWeight.SemiBold, color = tokens.text.color)
        withStyle(strong) { append("$upcoming") }
        append(" run in the next 24 h")
        if (attention > 0) {
            append("   ")
            withStyle(SpanStyle(color = tokens.error.color)) {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("$attention") }
                append(" need attention")
            }
        }
        if (failed > 0) {
            append("   ")
            withStyle(SpanStyle(color = tokens.error.color)) { append("$failed failed") }
        }
        append("   ")
        withStyle(strong) { append("$bypass") }
        append(" run in Bypass")
    }
    BasicText(
        text,
        Modifier.fillMaxWidth().padding(horizontal = if (phone) 12.dp else 12.dp).padding(bottom = 8.dp).testTag("schedules-summary"),
        style = txt(11.5f, tokens.text2.color),
    )
}

private val COL_NEXT_NARROW = 118.dp
private val COL_NEXT_WIDE = 150.dp
private val COL_LAST = 160.dp

@Composable
private fun HeaderRow(wide: Boolean) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier.fillMaxWidth().background(tokens.bgSolid.color).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val c = tokens.text3.color
        Spacer(Modifier.width(16.dp))
        T("SCHEDULE", 10.5f, c, modifier = Modifier.weight(1f))
        T("NEXT RUN", 10.5f, c, modifier = Modifier.width(if (wide) COL_NEXT_WIDE else COL_NEXT_NARROW))
        if (wide) T("LAST RUN", 10.5f, c, modifier = Modifier.width(COL_LAST))
        T("MODE", 10.5f, c, modifier = Modifier.width(if (wide) 70.dp else 64.dp))
        T("ON", 10.5f, c, modifier = Modifier.width(36.dp))
        Spacer(Modifier.width(52.dp))
    }
}

/** One schedule as a row of the window (`schedule-overview-row.tsx`). */
@Composable
private fun ScheduleRow(
    model: AppModel,
    s: ScheduleOverview,
    now: Long,
    wide: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    onOpenPrompt: () -> Unit,
    onActions: (Offset) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val next = nextRunLabel(s, now)
    val last = lastRunLabel(s, now)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .background(if (selected) tokens.accentWash.color else androidx.compose.ui.graphics.Color.Transparent)
            .contextMenuGesture(onActions)
            .ppmClickable(onClick = onSelect)
            .alpha(if (s.enabled) 1f else 0.6f)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("schedule-row-${s.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        KindIcon(s.kind, Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            T(s.name, 12.5f, weight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProjectTag(projectLabel(s))
                if (s.kind == "session") T("→ ${s.targetTitle ?: "its chat"}", 11f, tokens.text3.color, modifier = Modifier.weight(1f, fill = false))
                s.task?.let { TaskTag(it.ref) }
                if (!wide) RunStatusLine(last.tone, last.text, last.whenText, Modifier.weight(1f, fill = false))
            }
        }
        Column(Modifier.width(if (wide) COL_NEXT_WIDE else COL_NEXT_NARROW)) {
            T(next.main, 12f, weight = FontWeight.Medium)
            if (next.sub.isNotEmpty()) T(next.sub, 11f, tokens.text3.color)
        }
        if (wide) RunStatusLine(last.tone, last.text, last.whenText, Modifier.width(COL_LAST))
        Box(Modifier.width(if (wide) 70.dp else 64.dp)) { PermissionPill(s.effectivePermissionMode) }
        Box(Modifier.width(36.dp)) { PpmSwitch(checked = s.enabled, onCheckedChange = { model.schedules.setEnabled(s, it) }) }
        Row(Modifier.width(52.dp), horizontalArrangement = Arrangement.End) {
            IconBtn(ProductIcon.FileText, openPromptLabel(s), size = 24.dp, onClick = onOpenPrompt)
            IconBtn(ProductIcon.Play, "Run now", size = 24.dp) { model.schedules.runNow(s) }
        }
    }
}

/** One schedule as a phone card (`schedule-overview-card.tsx`): a tap opens the details, a long press the actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleCard(model: AppModel, s: ScheduleOverview, now: Long, onSelect: () -> Unit, onOpenPrompt: () -> Unit, onActions: (Offset) -> Unit) {
    val tokens = LocalPpmTokens.current
    val next = nextRunLabel(s, now)
    val last = lastRunLabel(s, now)
    val attention = scheduleAttention(s, now)
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(shape)
            .border(1.dp, if (attention != null) tokens.error.opacity(0.45).color else tokens.border.color, shape)
            .background(tokens.panel.color)
            .contextMenuGesture(onActions)
            .ppmClickable(shape = shape, onClick = onSelect)
            .alpha(if (s.enabled) 1f else 0.6f)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("schedule-card-${s.id}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KindIcon(s.kind)
            T(s.name, 14.5f, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
            PpmSwitch(checked = s.enabled, onCheckedChange = { model.schedules.setEnabled(s, it) })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            ProjectTag(projectLabel(s))
            PermissionPill(s.effectivePermissionMode)
            s.task?.let { TaskTag(it.ref, 11f) }
            if (s.kind == "session") T("→ ${s.targetTitle ?: "its chat"}", 11.5f, tokens.text3.color)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(next.main) }
                    if (next.sub.isNotEmpty()) withStyle(SpanStyle(color = tokens.text3.color)) { append(" · ${next.sub}") }
                },
                Modifier.weight(1f),
                style = txt(13f, tokens.text.color),
                maxLines = 1,
            )
            IconBtn(ProductIcon.FileText, openPromptLabel(s), size = 32.dp, onClick = onOpenPrompt)
            IconBtn(ProductIcon.Play, "Run now", size = 32.dp) { model.schedules.runNow(s) }
        }
        RunStatusLine(last.tone, last.text, last.whenText)
    }
}

/** The actions of one schedule (`ScheduleActionsMenu`): a menu at the press on a tablet, a sheet on a phone. */
@Composable
private fun ActionsMenu(
    model: AppModel,
    s: ScheduleOverview,
    now: Long,
    at: Offset,
    phone: Boolean,
    onDismiss: () -> Unit,
    onOpenPrompt: () -> Unit,
    onDelete: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val sched = model.schedules
    val attention = scheduleAttention(s, now)
    val items = @Composable {
        fun act(run: () -> Unit): () -> Unit = { onDismiss(); run() }
        MenuItem("Run now", act { sched.runNow(s) })
        MenuItem(openPromptLabel(s), act(onOpenPrompt))
        s.task?.let { t -> MenuItem("Open task ${t.ref}", act { sched.openTask(s) }) }
        if (chatOf(s) != null) MenuItem("Open chat", act { sched.openChat(s) })
        MenuItem(if (s.enabled) "Turn off" else "Turn on", act { sched.setEnabled(s, !s.enabled) })
        attention?.let { MenuItem("Dismiss “${it.text}”", act { sched.dismiss(s) }) }
        MenuSeparator()
        MenuItem("Delete", act(onDelete), color = tokens.error)
    }
    if (phone) {
        BottomSheet(onDismiss = onDismiss, modifier = Modifier.testTag("schedule-actions")) {
            T(s.name, 13f, weight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Column(Modifier.padding(horizontal = 8.dp).padding(bottom = 8.dp)) { items() }
        }
    } else {
        ContextMenuPopup(at, onDismiss = onDismiss) { items() }
    }
}

/** "Open prompt": the whole prompt or script, read-only, with Copy (`schedule-prompt-sheet.tsx`). */
@Composable
private fun PromptSheet(model: AppModel, s: ScheduleOverview, phone: Boolean, onClose: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val clipboard = LocalClipboardManager.current
    val text = if (s.kind == "script") s.script ?: "" else s.prompt
    val body = @Composable {
        Column(Modifier.fillMaxWidth().testTag("schedule-prompt-${s.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(
                text.ifEmpty { "—" },
                Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(6.dp)).background(tokens.panel2.color)
                    .verticalScroll(rememberScrollState()).padding(10.dp),
                style = txt(12f, tokens.text.color, mono = true),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SchedButton("Copy", ProductIcon.Copy) {
                    try {
                        clipboard.setText(AnnotatedString(text))
                        model.toast(ErrorToastMessage("Copied", kind = ErrorToastMessage.Kind.SUCCESS))
                    } catch (_: Exception) {
                        model.toast(ErrorToastMessage("Could not copy"))
                    }
                }
            }
        }
    }
    if (phone) {
        BottomSheet(onDismiss = onClose) {
            T(s.name, 14f, weight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 12.dp).padding(bottom = 8.dp))
            Box(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) { body() }
        }
    } else {
        WebDialog(title = s.name, onDismiss = onClose, widest = 672.dp) { body() }
    }
}

/** New: the Settings schedules' own form (`ScheduleForm`); the list reads again once it is saved. */
@Composable
private fun NewScheduleDialog(model: AppModel, onClose: () -> Unit) {
    val projects = remember(model.projects) { model.projects.map { ScheduleProjectOption(name = it.name, path = it.path) } }
    Dialog(onDismissRequest = onClose) {
        ScheduleCreateFormContent(
            projects = projects,
            onDismiss = onClose,
            onSubmit = { payload ->
                onClose()
                val api = model.api ?: return@ScheduleCreateFormContent
                model.scope.launch {
                    try {
                        parseScheduleOverview(api.schedulesCreate(payload))?.let { model.schedules.feed?.put(it) }
                        model.schedules.feed?.refetch()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        model.toast(ErrorToastMessage(e.message?.takeIf { it.isNotBlank() } ?: "Could not save the schedule"))
                    }
                }
            },
            loadChats = { projectName ->
                model.api?.sessions(projectName, limit = 50, offset = 0)?.sessions
                    ?.map { ScheduleChatOption(it.id, it.title.trim().ifEmpty { "Untitled chat" }) }
                    .orEmpty()
            },
        )
    }
}
