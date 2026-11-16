package com.hienlh.ppm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.chat.DeviceScheduleClock
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.schedulesDelete
import com.hienlh.ppm.client.schedulesList
import com.hienlh.ppm.client.schedulesUpdate
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ThemeTokens
import com.hienlh.ppm.design.TruncatedText
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.settings.ScheduleRowState
import com.hienlh.ppm.state.settings.SessionScheduleTone
import com.hienlh.ppm.state.settings.describeCron
import com.hienlh.ppm.state.settings.needsAttention
import com.hienlh.ppm.state.settings.nextRunText
import com.hienlh.ppm.state.settings.parseSchedulesList
import com.hienlh.ppm.state.settings.sessionScheduleStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What a chat's schedule chip does to one schedule (`api.patch` / `api.del` on `/api/schedules/:id`). */
interface SessionScheduleActions {
    /** Turns it on or off; throws with the server's sentence when refused. */
    suspend fun setEnabled(id: Int, enabled: Boolean)

    suspend fun delete(id: Int)

    companion object {
        fun of(api: ApiClient) = object : SessionScheduleActions {
            override suspend fun setEnabled(id: Int, enabled: Boolean) {
                api.schedulesUpdate(id, buildJsonObject { put("enabled", enabled) })
            }

            override suspend fun delete(id: Int) {
                api.schedulesDelete(id)
            }
        }
    }
}

private val SESSION_ID_RE = Regex("^[A-Za-z0-9_-]{8,64}$")

/**
 * The session schedules of one chat for its footer chip (`useSessionSchedules`): asked for once per
 * chat and again on every `schedules:changed`, so the chip follows a schedule the AI adds mid-turn.
 */
@Composable
fun ChatSessionSchedulesChip(model: AppModel, sessionId: String?, narrow: Boolean, modifier: Modifier = Modifier) {
    val api = model.api
    val events = model.events
    val scope = rememberCoroutineScope()
    var schedules by remember(sessionId) { mutableStateOf<List<ScheduleRowState>>(emptyList()) }
    val currentSession by rememberUpdatedState(sessionId)

    suspend fun refetch() {
        val id = sessionId?.takeIf { SESSION_ID_RE.matches(it) }
        if (api == null || id == null) {
            schedules = emptyList()
            return
        }
        try {
            val rows = parseSchedulesList(api.schedulesList(session = id))
            // An answer for an earlier chat (the tab moved on) is dropped rather than shown here.
            if (currentSession == id) schedules = rows
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep what is shown: the next change event asks again.
        }
    }

    LaunchedEffect(api, sessionId) { refetch() }
    DisposableEffect(events, api, sessionId) {
        val offChanged = events?.onSchedulesChanged { scope.launch { refetch() } }
        // What changed while the socket was down was never delivered.
        val offReady = events?.onGlobalReady { scope.launch { refetch() } }
        onDispose {
            offChanged?.invoke()
            offReady?.invoke()
        }
    }

    SessionSchedulesChip(
        schedules = schedules,
        narrow = narrow,
        actions = api?.let(SessionScheduleActions::of),
        onRefresh = { scope.launch { refetch() } },
        onError = { model.toast(ErrorToastMessage(it)) },
        modifier = modifier,
    )
}

/**
 * The schedules aimed at this chat as one chip of the footer's context row
 * (`session-schedules-chip.tsx`): a clock and how many, absent while there are none. A dot means
 * one needs a glance — PPM paused it, or it came due and waits for the chat to be idle. It opens a
 * popover (a bottom sheet on a phone) listing each with Pause/Resume and Delete.
 */
@Composable
fun SessionSchedulesChip(
    schedules: List<ScheduleRowState>,
    narrow: Boolean,
    actions: SessionScheduleActions?,
    onRefresh: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    if (schedules.isEmpty()) {
        if (open) open = false
        return
    }
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val attention = schedules.any(::needsAttention)
    val count = schedules.size
    val noun = if (count == 1) "schedule" else "schedules"
    val label = "$count $noun"

    FooterChip(
        narrow = narrow,
        modifier = modifier,
        expanded = open,
        tone = if (attention) FooterChipTone.Warning else FooterChipTone.Default,
        title = if (attention) "$label — one needs a look" else "$label for this chat",
        testTag = "session-schedules-chip",
        onClick = {
            if (!open) onRefresh()
            open = !open
        },
    ) {
        PpmIcon(ProductIcon.Clock, 14.dp, LocalFooterContentColor.current)
        BasicText(
            "$count",
            style = LocalFooterTextStyle.current.copy(
                color = if (attention) tokens.warning.color else tokens.text.color,
                fontWeight = FontWeight.Medium,
                fontFeatureSettings = "tnum",
            ),
            maxLines = 1,
            softWrap = false,
        )
        if (!narrow && !mobile) BasicText(noun, style = LocalFooterTextStyle.current, maxLines = 1, softWrap = false)
        if (attention) Box(Modifier.size(6.dp).clip(CircleShape).background(tokens.warning.color).testTag("session-schedules-attention"))
    }

    FooterPopover(open = open, onClose = { open = false }, label = "Schedules for this chat", align = FooterPopoverAlign.Left) {
        FooterPopoverHeader {
            PpmIcon(ProductIcon.Clock, 14.dp, tokens.text3.color)
            TruncatedText(
                AnnotatedString(label),
                ppmText(if (mobile) 14f else 13f, FontWeight.SemiBold, lineHeight = if (mobile) 20f else 18f).copy(color = tokens.text.color),
                Modifier.weight(1f, fill = false),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (mobile) Modifier else Modifier.heightIn(max = 288.dp))
                .verticalScroll(rememberScrollState()),
        ) {
            for (schedule in schedules) {
                SessionScheduleItem(schedule, actions, onChanged = onRefresh, onError = onError)
            }
            BasicText(
                "A schedule sends its prompt into this chat on its own, once the chat is idle. Settings → Schedules lists them all.",
                Modifier.padding(horizontal = if (mobile) 16.dp else 12.dp, vertical = 8.dp),
                style = ppmText(if (mobile) 12f else 11f, lineHeight = if (mobile) 19.5f else 17.9f).copy(color = tokens.text3.color),
            )
        }
    }
}

/** A status pill's colours (`TONE_CLASS`). */
internal fun scheduleToneColors(tokens: ThemeTokens, tone: SessionScheduleTone): Pair<Color, Color> = when (tone) {
    SessionScheduleTone.OK -> tokens.success.opacity(0.1).color to tokens.success.color
    SessionScheduleTone.WAIT -> tokens.warning.opacity(0.1).color to tokens.warning.color
    SessionScheduleTone.BAD -> tokens.error.opacity(0.1).color to tokens.error.color
    SessionScheduleTone.OFF -> tokens.text3.opacity(0.12).color to tokens.text3.color
}

/** A schedule's state in a word or two, as a small pill (`schedule-status-chip.tsx`). */
@Composable
internal fun ScheduleStatusPill(text: String, tone: SessionScheduleTone, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val (bg, fg) = scheduleToneColors(tokens, tone)
    BasicText(
        text,
        modifier.clip(RoundedCornerShape(4.dp)).background(bg).padding(horizontal = 6.dp, vertical = 2.dp),
        style = ppmText(10f, FontWeight.Medium, lineHeight = 15f).copy(color = fg),
        maxLines = 1,
        softWrap = false,
    )
}

/** One schedule in the popover, with its own actions; Delete asks once more in place. */
@Composable
private fun SessionScheduleItem(
    schedule: ScheduleRowState,
    actions: SessionScheduleActions?,
    onChanged: () -> Unit,
    onError: (String) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val scope = rememberCoroutineScope()
    var confirming by remember(schedule.id) { mutableStateOf(false) }
    var busy by remember(schedule.id) { mutableStateOf(false) }
    val status = sessionScheduleStatus(schedule, clock = DeviceScheduleClock)
    val on = schedule.enabled && schedule.pausedReason.isNullOrEmpty()
    val small = ppmText(if (mobile) 12f else 11f, lineHeight = if (mobile) 16f else 15f)

    fun run(fallback: String, action: suspend (SessionScheduleActions) -> Unit) {
        val act = actions ?: return
        busy = true
        scope.launch {
            try {
                action(act)
                onChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.message?.takeIf { it.isNotBlank() } ?: fallback)
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .cssBorder(tokens.borderSoft.color, sides = CssSides.Bottom)
            .padding(horizontal = if (mobile) 16.dp else 12.dp, vertical = if (mobile) 12.dp else 8.dp)
            .testTag("session-schedule-${schedule.id}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TruncatedText(
                AnnotatedString(schedule.name),
                ppmText(if (mobile) 14f else 12f, FontWeight.Medium, lineHeight = if (mobile) 20f else 16f).copy(color = tokens.text.color),
                Modifier.weight(1f),
            )
            ScheduleStatusPill(status.text, status.tone)
        }
        BasicText(
            "${describeCron(schedule.cronExpr)} · ${if (schedule.recurring) "Repeats" else "Runs once"}",
            Modifier.padding(top = 2.dp),
            style = small.copy(color = tokens.text3.color),
        )
        BasicText(nextRunText(schedule, clock = DeviceScheduleClock), style = small.copy(color = tokens.text3.color))
        status.detail?.let {
            BasicText(
                it,
                Modifier.padding(top = 2.dp),
                style = small.copy(color = if (status.tone == SessionScheduleTone.BAD) tokens.error.color else tokens.text3.color),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (mobile) 8.dp else 4.dp),
        ) {
            if (confirming) {
                BasicText("Delete this schedule?", style = small.copy(color = tokens.text2.color))
                Spacer(Modifier.weight(1f))
                FooterButton(onClick = { confirming = false }, enabled = !busy) {
                    BasicText("Cancel", style = LocalFooterTextStyle.current)
                }
                FooterButton(
                    onClick = { run("Could not delete it") { it.delete(schedule.id); confirming = false } },
                    variant = FooterButtonVariant.DangerFill,
                    pushEnd = false,
                    enabled = !busy && actions != null,
                    testTag = "session-schedule-confirm-delete",
                ) {
                    PpmIcon(ProductIcon.Trash2, 14.dp, LocalFooterContentColor.current)
                    BasicText("Delete", style = LocalFooterTextStyle.current)
                }
            } else {
                FooterButton(
                    onClick = { run(if (on) "Could not pause it" else "Could not resume it") { it.setEnabled(schedule.id, !on) } },
                    enabled = !busy && actions != null,
                    testTag = "session-schedule-toggle",
                ) {
                    PpmIcon(if (on) ProductIcon.Pause else ProductIcon.Play, 14.dp, LocalFooterContentColor.current)
                    BasicText(if (on) "Pause" else "Resume", style = LocalFooterTextStyle.current)
                }
                FooterButton(
                    onClick = { confirming = true },
                    variant = FooterButtonVariant.Danger,
                    enabled = !busy && actions != null,
                    testTag = "session-schedule-delete",
                ) {
                    PpmIcon(ProductIcon.Trash2, 14.dp, LocalFooterContentColor.current)
                    BasicText("Delete", style = LocalFooterTextStyle.current)
                }
            }
        }
    }
}
