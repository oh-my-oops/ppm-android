package com.hienlh.ppm.chat

import android.icu.text.DateTimePatternGenerator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFonts
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.TruncatedText
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.ScheduleRunReference
import com.hienlh.ppm.state.settings.ScheduleClock
import com.hienlh.ppm.state.settings.describeCron
import com.hienlh.ppm.state.settings.formatInZone
import com.hienlh.ppm.state.settings.scheduleRunNote
import com.hienlh.ppm.state.settings.zoneCity
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A schedule's time as Chrome writes `{ hour: "2-digit", minute: "2-digit" }` (and `month: "short",
 * day: "numeric"` before it) in the device's locale: "08:00 AM", "Nov 15, 08:00 AM", or "08:00".
 */
val DeviceScheduleClock = ScheduleClock { instant, zone, withDate ->
    val locale = Locale.getDefault()
    val pattern = DateTimePatternGenerator.getInstance(locale)
        .getBestPattern(if (withDate) "MMMdjjmm" else "jjmm", DateTimePatternGenerator.MATCH_HOUR_FIELD_LENGTH)
    DateTimeFormatter.ofPattern(pattern, locale).withZone(zone).format(instant).replace(' ', ' ')
}

/**
 * A prompt a session schedule sent into this chat (`schedule-run-line.tsx`): the person wrote it
 * once, earlier, and PPM sent it on its own, so it is one quiet line — which schedule ran, when, and
 * whether it was sent late or stood for several fires — that opens to the prompt and when the
 * schedule fires. Drawn from the message's own block, so it reads the same after a reload.
 */
@Composable
fun ScheduleRunLine(
    run: ScheduleRunReference,
    /** The prompt, without its block. */
    text: String,
    modifier: Modifier = Modifier,
    /** For tests: what "today" is when deciding whether to print the date. */
    now: Instant? = null,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    var open by remember { mutableStateOf(false) }
    val note = scheduleRunNote(run.catchUp, run.coalesced)
    // `sm:` (640 px): on a narrower screen the clock already says "schedule" and the note moves into
    // the opened panel, so the name is not squeezed to a few letters.
    val wide = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } >= 640.dp
    val at = now ?: Instant.now()
    val fired = formatInZone(run.firedAt, run.timezone, at, DeviceScheduleClock)
    val muted = tokens.text3.color
    val label = ppmText(12f, lineHeight = 16f).copy(color = muted)

    Column(modifier.fillMaxWidth().testTag("schedule-run-${run.scheduleId}"), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .ppmClickable(shape = RoundedCornerShape(50), onClickLabel = if (open) "Hide the scheduled prompt" else "Show the scheduled prompt") { open = !open }
                .semantics { contentDescription = if (open) "Hide the scheduled prompt" else "Show the scheduled prompt" }
                .heightIn(min = if (mobile) 44.dp else 28.dp)
                .padding(horizontal = 12.dp)
                .testTag("schedule-run-toggle"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PpmIcon(ProductIcon.Clock, 14.dp, muted)
            if (wide) BasicText("Schedule", style = label, maxLines = 1, softWrap = false)
            TruncatedText(
                AnnotatedString(run.name),
                label.copy(color = tokens.text2.color, fontWeight = FontWeight.Medium),
                Modifier.weight(1f, fill = false),
            )
            BasicText("ran · $fired", style = label, maxLines = 1, softWrap = false)
            if (wide && note != null) BasicText("· $note", style = label, maxLines = 1, softWrap = false)
            PpmIcon(if (open) ProductIcon.ChevronDown else ProductIcon.ChevronRight, 12.dp, muted)
        }
        if (open) {
            val shape = RoundedCornerShape(8.dp)
            Column(
                Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth(0.85f)
                    .clip(shape)
                    .background(tokens.panel.opacity(0.4).color, shape)
                    .cssBorder(tokens.borderSoft.color, shape)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("schedule-run-panel"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SelectionContainer {
                    BasicText(
                        text.ifEmpty { "(empty prompt)" },
                        style = ppmText(14f, lineHeight = 20f).copy(color = if (text.isEmpty()) muted else tokens.text.color),
                    )
                }
                val details = buildAnnotatedString {
                    append(describeCron(run.cron))
                    append(" · ")
                    withStyle(SpanStyle(fontFamily = PpmFonts.mono)) { append(run.cron) }
                    append(" · ")
                    append(run.timezone?.let(::zoneCity) ?: "the PPM host's time zone")
                    if (run.scheduledFor != run.firedAt) append(" · due ${formatInZone(run.scheduledFor, run.timezone, at, DeviceScheduleClock)}")
                    if (!wide && note != null) append(" · $note")
                }
                BasicText(details, style = ppmText(11f, lineHeight = 15f).copy(color = muted))
            }
        }
    }
}
