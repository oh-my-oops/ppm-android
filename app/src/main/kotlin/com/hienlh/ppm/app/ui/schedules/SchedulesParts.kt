package com.hienlh.ppm.app.ui.schedules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.schedules
import com.hienlh.ppm.app.ui.PpmSwitch
import com.hienlh.ppm.app.ui.tasks.IconBtn
import com.hienlh.ppm.app.ui.tasks.T
import com.hienlh.ppm.app.ui.tasks.txt
import com.hienlh.ppm.client.schedulesListRuns
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.state.schedules.RunTone
import com.hienlh.ppm.state.schedules.ScheduleOverview
import com.hienlh.ppm.state.schedules.chatOf
import com.hienlh.ppm.state.schedules.clockLabel
import com.hienlh.ppm.state.schedules.isBypass
import com.hienlh.ppm.state.schedules.kindLabel
import com.hienlh.ppm.state.schedules.modeLabel
import com.hienlh.ppm.state.schedules.parseStamp
import com.hienlh.ppm.state.schedules.projectLabel
import com.hienlh.ppm.state.schedules.relativeAgo
import com.hienlh.ppm.state.schedules.runDuration
import com.hienlh.ppm.state.schedules.scheduleAttention
import com.hienlh.ppm.state.settings.ScheduleRunRow
import com.hienlh.ppm.state.settings.describeCron
import com.hienlh.ppm.state.settings.parseScheduleRuns
import com.hienlh.ppm.state.settings.zoneCity
import kotlinx.coroutines.CancellationException

/*
 * The small parts every Schedules view shares (`schedules-ui.tsx`), the detail of one schedule
 * (`schedule-overview-detail.tsx`) and its newest runs (`schedule-recent-runs.tsx`).
 */

internal fun kindIcon(kind: String): ProductIcon = when (kind) {
    "session" -> ProductIcon.Clock
    "script" -> ProductIcon.FileCode
    else -> ProductIcon.MessageSquare
}

@Composable
internal fun KindIcon(kind: String, modifier: Modifier = Modifier) =
    Box(modifier) { PpmIcon(kindIcon(kind), 16.dp, LocalPpmTokens.current.text3.color) }

/** "Open script" for a script, else "Open prompt". */
internal fun openPromptLabel(s: ScheduleOverview) = if (s.kind == "script") "Open script" else "Open prompt"

/** Bypass in warning colours with an icon; any other mode quiet, so Bypass is what the eye finds. */
@Composable
internal fun PermissionPill(mode: String) {
    val tokens = LocalPpmTokens.current
    val bypass = mode == "bypassPermissions"
    val shape = RoundedCornerShape(5.dp)
    Row(
        Modifier
            .height(18.dp)
            .clip(shape)
            .then(if (bypass) Modifier.border(1.dp, tokens.warning.opacity(0.4).color, shape).background(tokens.warning.opacity(0.15).color) else Modifier.background(tokens.surfaceHover.color))
            .padding(horizontal = 6.dp)
            .testTag("schedule-permission-$mode"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (bypass) PpmIcon(ProductIcon.AlertTriangle, 12.dp, tokens.warning.color)
        T(if (bypass) "BYPASS" else modeLabel(mode), 10.5f, if (bypass) tokens.warning.color else tokens.text2.color, weight = FontWeight.SemiBold)
    }
}

@Composable
internal fun toneColor(tone: RunTone): Color {
    val tokens = LocalPpmTokens.current
    return when (tone) {
        RunTone.OK -> tokens.success.color
        RunTone.BAD -> tokens.error.color
        RunTone.WARN -> tokens.warning.color
        RunTone.RUNNING -> tokens.info.color
        RunTone.NONE -> tokens.text3.color
    }
}

@Composable
internal fun RunStatusLine(tone: RunTone, text: String, whenText: String = "", modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val color = toneColor(tone)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val icon = when (tone) {
            RunTone.OK -> ProductIcon.CheckCircle2
            RunTone.BAD -> ProductIcon.XCircle
            RunTone.WARN -> ProductIcon.Clock
            RunTone.RUNNING -> ProductIcon.Loader2
            RunTone.NONE -> null
        }
        icon?.let { PpmIcon(it, 14.dp, color, spinning = tone == RunTone.RUNNING) }
        T(text, 11.5f, color, modifier = Modifier.weight(1f, fill = false))
        if (whenText.isNotEmpty()) T("· $whenText", 11.5f, tokens.text3.color)
    }
}

/** A stable colour per project name (`projectColor`): `hsl(h 70% 62%)`. */
internal fun projectColor(name: String): Color {
    var h = 0
    for (ch in name) h = (h * 31 + ch.code) % 360
    return Color.hsl(h.toFloat(), 0.7f, 0.62f)
}

@Composable
internal fun ProjectTag(name: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(projectColor(name)))
        T(name, 11f, LocalPpmTokens.current.text2.color)
    }
}

@Composable
internal fun TaskTag(ref: String, size: Float = 10.5f) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier.height(18.dp).clip(RoundedCornerShape(5.dp)).background(tokens.accentWash.color).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        PpmIcon(ProductIcon.ClipboardCheck, 12.dp, tokens.accent.color)
        T(ref, size, tokens.accent.color, weight = FontWeight.Medium)
    }
}

/** A button with text (`TEXT_BUTTON` / `PRIMARY_BUTTON`): 28 dp tall, 26 on a phone, fully rounded. */
@Composable
internal fun SchedButton(label: String, icon: ProductIcon? = null, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(50)
    val fg = if (primary) tokens.accentFg.color else tokens.text.color
    Row(
        modifier
            .height(if (LocalPpmMobile.current) 26.dp else 28.dp)
            .clip(shape)
            .border(1.dp, if (primary) tokens.accent.color else tokens.border.color, shape)
            .background(if (primary) tokens.accent.color else Color.Transparent)
            .ppmClickable(shape = shape, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon?.let { PpmIcon(it, 14.dp, fg) }
        T(label, 12f, fg, weight = if (primary) FontWeight.Medium else FontWeight.Normal)
    }
}

@Composable
private fun DetailCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).border(1.dp, tokens.border.color, shape).background(tokens.panel.color).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        T(title.uppercase(), 10.5f, tokens.text3.color, weight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun DefRow(term: String, value: String, mono: Boolean = false) {
    val tokens = LocalPpmTokens.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        T(term, 12f, tokens.text3.color, modifier = Modifier.width(76.dp))
        BasicText(value, Modifier.weight(1f), style = txt(12f, tokens.text.color, mono = mono))
    }
}

/**
 * One schedule in full: a warning when it runs in Bypass, its problem and Dismiss, the actions,
 * when it runs, its prompt and its recent runs. [bare] leaves out the title block (the row above
 * already shows it).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScheduleDetail(
    model: AppModel,
    s: ScheduleOverview,
    now: Long,
    onOpenPrompt: () -> Unit,
    onDelete: () -> Unit,
    bare: Boolean = false,
) {
    val tokens = LocalPpmTokens.current
    val actions = model.schedules
    val problem = scheduleAttention(s, now)
    Column(Modifier.fillMaxWidth().testTag("schedule-detail-${s.id}"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!bare) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KindIcon(s.kind, Modifier.padding(top = 2.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    T(s.name, 14f, weight = FontWeight.SemiBold, maxLines = 4)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProjectTag(projectLabel(s))
                        T(kindLabel(s.kind), 11f, tokens.text3.color)
                    }
                }
                PpmSwitch(checked = s.enabled, onCheckedChange = { actions.setEnabled(s, it) })
            }
        }
        if (isBypass(s)) {
            val shape = RoundedCornerShape(8.dp)
            Row(
                Modifier.fillMaxWidth().clip(shape).border(1.dp, tokens.warning.opacity(0.4).color, shape)
                    .background(tokens.warning.opacity(0.1).color).padding(horizontal = 10.dp, vertical = 8.dp).testTag("schedule-bypass-warning"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PpmIcon(ProductIcon.AlertTriangle, 16.dp, tokens.warning.color)
                BasicText(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Bypass permissions.") }
                        append(" Every tool runs without asking, in ${projectLabel(s)}, with nobody watching when it fires.")
                        if (s.kind == "session") append(" This is its chat's own mode.")
                    },
                    Modifier.weight(1f),
                    style = txt(12f, tokens.warning.color),
                )
            }
        }
        if (problem != null) {
            val shape = RoundedCornerShape(8.dp)
            Row(
                Modifier.fillMaxWidth().clip(shape).border(1.dp, tokens.error.opacity(0.4).color, shape)
                    .background(tokens.error.opacity(0.1).color).padding(horizontal = 10.dp, vertical = 8.dp).testTag("schedule-problem"),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicText(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("${problem.text}.") }
                        problem.detail?.let { append(" $it") }
                    },
                    Modifier.weight(1f),
                    style = txt(12f, tokens.error.color),
                )
                SchedButton("Dismiss", modifier = Modifier.testTag("schedule-dismiss")) { actions.dismiss(s) }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            SchedButton("Run now", ProductIcon.Play, primary = true, modifier = Modifier.testTag("schedule-run-now")) { actions.runNow(s) }
            SchedButton(openPromptLabel(s), ProductIcon.FileText, onClick = onOpenPrompt)
            s.task?.let { t -> SchedButton(t.ref, ProductIcon.ClipboardCheck, modifier = Modifier.testTag("schedule-open-task")) { actions.openTask(s) } }
            if (chatOf(s) != null) SchedButton("Open chat", ProductIcon.MessageSquare) { actions.openChat(s) }
            IconBtn(ProductIcon.Trash2, "Delete", size = 28.dp, onClick = onDelete)
        }
        if (s.mode == "tick") {
            DetailCard("When") {
                val upcoming = s.nextFires.mapNotNull { parseStamp(it) }.map { clockLabel(it, s.timezone, now) }
                DefRow("Cron", s.cronExpr, mono = true)
                DefRow("In words", describeCron(s.cronExpr) + if (s.kind == "session" && !s.recurring) ", once" else "")
                DefRow("Zone", s.timezone?.let(::zoneCity) ?: "The PPM host's")
                DefRow("Next", upcoming.joinToString(" · ").ifEmpty { "—" })
                if (s.kind == "session") DefRow("Sends to", s.targetTitle ?: "its chat")
            }
        }
        if (s.kind != "script") {
            DetailCard("Prompt") {
                val shape = RoundedCornerShape(6.dp)
                BasicText(
                    s.prompt,
                    Modifier.fillMaxWidth().heightIn(max = 96.dp).clip(shape).background(tokens.panel2.color)
                        .ppmClickable(shape = shape, onClickLabel = "Open the whole prompt", onClick = onOpenPrompt).padding(8.dp),
                    style = txt(11.5f, tokens.text2.color, mono = true),
                    maxLines = 5,
                )
            }
        }
        DetailCard("Recent runs") { RecentRuns(model, s, now) }
    }
}

private const val SHOWN_RUNS = 5

/** The newest runs of one schedule: outcome, when, how long, cost; its chat and its error or output a tap away. */
@Composable
private fun RecentRuns(model: AppModel, s: ScheduleOverview, now: Long) {
    val tokens = LocalPpmTokens.current
    val api = model.api
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(model.events) {
        // A run started from elsewhere shows up while the list is open.
        val off = model.events?.onSchedulesChanged { tick++ }
        onDispose { off?.invoke() }
    }
    val state by produceState<Pair<Boolean, List<ScheduleRunRow>>>(true to emptyList(), api, s.id, tick) {
        if (api == null) {
            value = false to emptyList()
            return@produceState
        }
        value = true to value.second
        value = try {
            false to parseScheduleRuns(api.schedulesListRuns(s.id, 20))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false to emptyList()
        }
    }
    val (loading, runs) = state
    var open by remember { mutableStateOf<Int?>(null) }
    if (loading && runs.isEmpty()) {
        T("Loading runs…", 11f, tokens.text3.color)
        return
    }
    if (runs.isEmpty()) {
        T("Not run yet.", 11f, tokens.text3.color)
        return
    }
    Column(Modifier.fillMaxWidth()) {
        for (run in runs.take(SHOWN_RUNS)) {
            val chat = run.rotatedToSessionId ?: run.sessionId
            val details = run.error ?: run.outputTruncated
            Row(Modifier.fillMaxWidth().heightIn(min = 28.dp).testTag("schedule-run-${run.id}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.weight(1f).ppmClickable(enabled = details != null, onClick = { open = if (open == run.id) null else run.id }),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val (tone, word) = when (run.status) {
                        "done" -> RunTone.OK to "Done"
                        "error" -> RunTone.BAD to "Failed"
                        "running" -> RunTone.RUNNING to "Running"
                        else -> RunTone.NONE to "Skipped"
                    }
                    RunStatusLine(tone, word)
                    val ago = parseStamp(run.startedAt)?.let { relativeAgo(it, now) } ?: ""
                    T(
                        "$ago · ${runDuration(run.startedAt, run.endedAt)}" + (run.costUsd?.let { " · $" + String.format(java.util.Locale.US, "%.2f", it) } ?: ""),
                        11f, tokens.text3.color,
                    )
                }
                if (chat != null) IconBtn(ProductIcon.ExternalLink, "Open this run's chat", size = 24.dp) { model.schedules.openChatId(s, chat) }
            }
            if (open == run.id && details != null) {
                BasicText(
                    details,
                    Modifier.fillMaxWidth().padding(bottom = 6.dp).heightIn(max = 192.dp).clip(RoundedCornerShape(4.dp)).background(tokens.panel2.color).padding(8.dp),
                    style = txt(10.5f, if (run.error != null) tokens.error.color else tokens.text2.color, mono = true),
                )
            }
        }
    }
}
