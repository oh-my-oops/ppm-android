package com.hienlh.ppm.chat

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.CssColumn
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFonts
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.TruncatedText
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssExact
import com.hienlh.ppm.design.cssLines
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberCssExact
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.protocol.AgentRunningEntry
import com.hienlh.ppm.protocol.TeamMemberActivity
import com.hienlh.ppm.state.ChatItem
import com.hienlh.ppm.state.RunningAgentView
import com.hienlh.ppm.state.buildRunningRows
import com.hienlh.ppm.state.findCardLabel
import com.hienlh.ppm.state.finishedSiblingRows
import com.hienlh.ppm.state.view
import java.time.Instant
import kotlinx.coroutines.delay

/** Avatars drawn before the rest fold into "+N". */
private const val MAX_AVATARS = 3

/**
 * Every agent still at work, in one line pinned under the conversation (`RunningAgentsBar`): who
 * runs comes from the hub's [running] feed, the team poll's [members] add a type and a start
 * time, and the chat's [items] name each card. One agent is a line that opens its session; more
 * are avatars and a count that open a list of three rows' height, a finished agent staying on it
 * while one launched beside it still runs. Nothing while nobody runs.
 */
@Composable
fun RunningAgentsBar(running: List<AgentRunningEntry>, members: List<TeamMemberActivity>, items: List<ChatItem>, onOpen: (RunningAgentView) -> Unit, modifier: Modifier = Modifier) {
    val runningRows = remember(running, members) { buildRunningRows(running, members) }
    val doneRows = remember(items, runningRows) { finishedSiblingRows(items, runningRows) }
    // Labels read the whole transcript: once per change, not on every tick.
    val labels = remember(items, runningRows, doneRows) {
        (runningRows + doneRows).mapNotNull { row -> row.cardId?.let { it to findCardLabel(items, it) } }.toMap()
    }
    // Elapsed times count while anything runs.
    var tick by remember { mutableIntStateOf(0) }
    val active = runningRows.isNotEmpty()
    LaunchedEffect(active) {
        while (active) {
            delay(1_000)
            tick++
        }
    }
    val total = runningRows.size + doneRows.size
    var open by remember { mutableStateOf(false) }
    // A batch down to one agent, or over, has no list to keep open; the next starts closed.
    LaunchedEffect(total) { if (total <= 1) open = false }
    if (runningRows.isEmpty()) return

    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val sm = LocalConfiguration.current.screenWidthDp >= 640
    // Read at each tick, as the web reads `Date.now()` on each render.
    val now = tick.let { Instant.now() }
    val views = (runningRows + doneRows).map { it.view(it.cardId?.let(labels::get), now) }
    val lead = views.first()
    Column(modifier.fillMaxWidth().background(tokens.panel.color).cssBorder(tokens.border.color, sides = CssSides.Top)) {
        val summary = Modifier
            .fillMaxWidth()
            // `min-h-11` for a coarse pointer, as a touch screen is.
            .heightIn(min = 44.dp)
            .ppmClickable(pressedColor = tokens.panel2.color, onClick = if (total == 1) ({ onOpen(lead) }) else ({ open = !open }))
            .padding(horizontal = if (mobile) 12.dp else 14.dp, vertical = 6.dp)
        if (total == 1) {
            Row(summary.webTitle("Open ${lead.name}'s session"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiveDot(done = false)
                NameAndStep(lead.name, lead.step?.takeIf { sm }, Modifier.weight(1f))
                lead.elapsed?.let { BasicText(it, style = elapsedText.copy(color = tokens.text3.color)) }
                PpmIcon(ProductIcon.ChevronRight, 14.dp, tokens.text3.color)
            }
        } else {
            Row(summary, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiveDot(done = false)
                AvatarStack(total)
                val count = "${runningRows.size} ${if (runningRows.size == 1) "agent" else "agents"} running"
                BasicText(count, if (sm) Modifier else Modifier.weight(1f), barText.copy(color = tokens.text.color, fontWeight = FontWeight.SemiBold), maxLines = 1, softWrap = false)
                if (doneRows.isNotEmpty()) BasicText("· ${doneRows.size} done", style = barText.copy(color = tokens.text3.color), maxLines = 1, softWrap = false)
                if (sm) {
                    val line = buildAnnotatedString {
                        append("— ")
                        withStyle(SpanStyle(color = tokens.text2.color, fontWeight = FontWeight.Medium)) { append(lead.name) }
                        lead.step?.let { step ->
                            append(" · ")
                            withStyle(SpanStyle(fontFamily = PpmFonts.mono)) { append(step) }
                        }
                    }
                    TruncatedText(line, barText.copy(color = tokens.text3.color), Modifier.weight(1f))
                }
                // `transition-transform`: 150 ms.
                val turn by animateFloatAsState(if (open) 180f else 0f, tween(150, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)), label = "chevron")
                PpmIcon(ProductIcon.ChevronUp, 14.dp, tokens.text3.color, Modifier.rotate(turn))
            }
            if (open) {
                // 45dp rows: three of them and the list stops growing. Stacked in fractions of a pixel, as
                // Chrome stacks them, where whole-pixel rows put the third 6 px under the web's.
                CssColumn(Modifier.fillMaxWidth().heightIn(max = 140.dp).verticalScroll(rememberScrollState()).padding(start = 8.dp, end = 8.dp), bottom = 8.dp) {
                    views.forEach { AgentListRow(it) { onOpen(it) } }
                }
            }
        }
    }
}

/** `text-xs`: 12 on 16. */
private val barText = ppmText(12f, lineHeight = 16f)

/** `font-mono text-[11px]` under `text-xs`'s line height of 4/3. */
private val elapsedText = ppmText(11f, kind = FontKind.MONO, lineHeight = 44f / 3)

/**
 * The single agent's name and, from 640dp up, its step: the name takes the width it needs and
 * gives way first only to nothing, the step (`flex-1 min-w-0`) takes what is left.
 */
@Composable
private fun NameAndStep(name: String, step: String?, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    Layout(
        content = {
            TruncatedText(AnnotatedString(name), barText.copy(color = tokens.text.color, fontWeight = FontWeight.SemiBold))
            if (step != null) TruncatedText(AnnotatedString(step), barText.copy(color = tokens.text3.color, fontFamily = PpmFonts.mono))
        },
        modifier,
    ) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val first = measurables[0].measure(Constraints(maxWidth = constraints.maxWidth))
        val second = measurables.getOrNull(1)?.let { (constraints.maxWidth - first.width - gap).coerceAtLeast(0) }?.let { measurables[1].measure(Constraints(minWidth = it, maxWidth = it)) }
        val height = maxOf(first.height, second?.height ?: 0)
        layout(constraints.maxWidth, height) {
            first.placeRelative(0, (height - first.height) / 2)
            second?.placeRelative(first.width + gap, (height - second.height) / 2)
        }
    }
}

/** One agent in the open list: its name, what it is on (or how it ended), and how long it has run. */
@Composable
private fun AgentListRow(view: RunningAgentView, onOpen: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(12.dp)
    // As tall as its two lines with `py-1` and `gap-px`, which their column keeps in fractions: 45dp.
    val exact = rememberCssExact()
    Row(
        Modifier
            .fillMaxWidth()
            .cssExact(exact)
            .heightIn(min = 44.dp)
            .clip(shape)
            .webTitle("Open ${view.name}'s session")
            .ppmClickable(pressedColor = tokens.panel2.color, onClick = onOpen)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LiveDot(done = view.row.done)
        CssColumn(Modifier.weight(1f), gap = 1.dp, top = 4.dp, bottom = 4.dp, exact = exact) {
            TruncatedText(AnnotatedString(view.name), listName.copy(color = (if (view.row.done) tokens.text2 else tokens.text).color), Modifier.cssLines(19.5f))
            val sub = when {
                view.row.failed -> "Failed"
                view.row.done -> "Finished"
                else -> view.step ?: "Starting…"
            }
            TruncatedText(AnnotatedString(sub), listSub.copy(color = (if (view.row.failed) tokens.error else tokens.text3).color), Modifier.cssLines(16.5f))
        }
        view.elapsed?.let { BasicText(it, style = listSub.copy(color = tokens.text3.color)) }
    }
}

/** `text-[13px] font-medium` under the page's `line-height: 1.5`. */
private val listName = ppmText(13f, FontWeight.Medium, lineHeight = 19.5f)

/** `font-mono text-[11px]` under the page's `line-height: 1.5`. */
private val listSub = ppmText(11f, kind = FontKind.MONO, lineHeight = 16.5f)

/** An 8dp green dot pinging while the agent runs (`animate-ping`, from 55% to nothing at twice the size); grey once it is done. */
@Composable
private fun LiveDot(done: Boolean) {
    val tokens = LocalPpmTokens.current
    if (done) {
        Box(Modifier.size(8.dp).background(tokens.text3.color, CircleShape))
        return
    }
    val ping by rememberInfiniteTransition(label = "ping").animateFloat(
        0f, 1f,
        infiniteRepeatable(
            keyframes {
                durationMillis = 1_000
                0f at 0 using CubicBezierEasing(0f, 0f, 0.2f, 1f)
                1f at 750
            },
        ),
        label = "ping",
    )
    Canvas(Modifier.size(8.dp)) {
        val radius = size.minDimension / 2
        drawCircle(tokens.success.opacity(0.55 * (1.0 - ping)).color, radius * (1 + ping))
        drawCircle(tokens.success.color, radius)
    }
}

/** Up to three bot avatars, each overlapping the last by 6dp in a 2dp ring of the panel, then "+N". */
@Composable
private fun AvatarStack(count: Int) {
    val tokens = LocalPpmTokens.current
    // `-ml-1.5`: each drawn over the one before.
    Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
        repeat(minOf(count, MAX_AVATARS)) {
            Box(
                Modifier
                    .size(20.dp)
                    .background(tokens.accent2.opacity(0.15).color, CircleShape)
                    .cssBorder(tokens.panel.color, CircleShape, 2.dp),
                contentAlignment = Alignment.Center,
            ) { PpmIcon(ProductIcon.Bot, 12.dp, tokens.accent2.color) }
        }
        if (count > MAX_AVATARS) {
            Box(
                Modifier
                    .size(20.dp)
                    .background(tokens.panel2.color, CircleShape)
                    .cssBorder(tokens.panel.color, CircleShape, 2.dp),
                contentAlignment = Alignment.Center,
            ) { BasicText("+${count - MAX_AVATARS}", style = ppmText(10f, FontWeight.SemiBold, lineHeight = 40f / 3).copy(color = tokens.text2.color)) }
        }
    }
}
