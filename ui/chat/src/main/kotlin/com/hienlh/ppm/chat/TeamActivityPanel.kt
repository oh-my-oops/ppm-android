package com.hienlh.ppm.chat

import android.icu.text.BreakIterator
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hienlh.ppm.design.ClampedText
import com.hienlh.ppm.design.CssColumn
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFonts
import com.hienlh.ppm.design.PpmFormat
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.TruncatedText
import com.hienlh.ppm.design.cssHeight
import com.hienlh.ppm.design.cssLines
import com.hienlh.ppm.design.cssPhase
import com.hienlh.ppm.design.cssPhased
import com.hienlh.ppm.design.cssReport
import com.hienlh.ppm.design.cssScrollX
import com.hienlh.ppm.design.cssScrollY
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberCssExact
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.protocol.TeamActivityFeed
import com.hienlh.ppm.protocol.TeamMemberActivity
import com.hienlh.ppm.protocol.TeamMessage
import com.hienlh.ppm.state.currentStep
import com.hienlh.ppm.state.formatDuration
import com.hienlh.ppm.state.pollTeamActivity
import com.hienlh.ppm.state.shortAgentType
import com.hienlh.ppm.state.teamConversation
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Which of a team's two lists the panel shows. */
private enum class TeamTab { Members, Messages }

/** `max-h-56`: either list scrolls past this. */
private const val LIST_MAX = 224

/**
 * An agent team's roster and its conversation, as two tabs (`TeamActivityPanel`): never one
 * scroll, as a long conversation would push the roster — who is working now — out of view.
 *
 * The team picked out of [teamNames] has its members read now and every 5 s while the panel is
 * shown ([fetch]), a failed read keeping the last answer; the conversation is the chat's [inbox]
 * with the replies that read returns. A member opens its session ([onOpenMember], with the team
 * and the member) only in a chat with a [sessionId]: a team name is not one.
 */
@Composable
fun TeamActivityPanel(
    teamNames: List<String>,
    inbox: List<TeamMessage>,
    sessionId: String?,
    fetch: suspend (team: String) -> TeamActivityFeed,
    onOpenMember: (team: String, member: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var picked by remember { mutableStateOf(teamNames.firstOrNull().orEmpty()) }
    // A team the session no longer lists gives way to the first.
    val team = picked.takeIf { it in teamNames } ?: teamNames.firstOrNull().orEmpty()
    var tab by remember { mutableStateOf(TeamTab.Members) }
    var feed by remember { mutableStateOf(TeamActivityFeed(emptyList(), emptyList())) }
    var loading by remember { mutableStateOf(false) }
    val read by rememberUpdatedState(fetch)
    suspend fun load(name: String): TeamActivityFeed {
        loading = true
        try {
            return read(name)
        } finally {
            loading = false
        }
    }
    LaunchedEffect(team) {
        if (team.isNotEmpty()) pollTeamActivity({ load(team) }) { feed = it }
    }
    val scope = rememberCoroutineScope()
    val conversation = remember(inbox, feed.outbound) { teamConversation(inbox, feed.outbound) }
    // Read at each render, as the web reads `Date.now()`: each poll moves the times on.
    val now = Instant.now()
    CssColumn(modifier.fillMaxWidth(), gap = 6.dp) {
        if (teamNames.size > 1) TeamPicker(teamNames, team, sessionId) { picked = it }
        TeamTabs(
            tab,
            members = feed.members.size,
            working = feed.members.count { it.isWorking },
            messages = conversation.size,
            loading = loading,
            onTab = { tab = it },
            onRefresh = {
                if (team.isNotEmpty()) {
                    scope.launch {
                        try {
                            feed = load(team)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                        }
                    }
                }
            },
        )
        if (tab == TeamTab.Members) {
            TeamMemberList(feed.members, loading, now) { member -> if (!sessionId.isNullOrEmpty()) onOpenMember(team, member) }
        } else {
            TeamMessageList(conversation)
        }
    }
}

/** `text-xs`: 12 on 16. */
private val xsText = ppmText(12f, lineHeight = 16f)

/** `text-[10px]` under `text-xs`'s line height of 4/3. */
private val tinyText = ppmText(10f, lineHeight = 40f / 3)

/** `text-[11px]` under the page's `line-height: 1.5`, `font-medium` when [active]. */
private fun tabText(active: Boolean) = ppmText(11f, if (active) FontWeight.Medium else FontWeight.Normal, lineHeight = 16.5f)

/** What a member is on: `font-mono text-[11px]` under the page's `line-height: 1.5`. */
private val stepText = ppmText(11f, kind = FontKind.MONO, lineHeight = 16.5f)

/** The body under a message: `text-[11px]` under `text-xs`'s 4/3. */
private val detailText = ppmText(11f, lineHeight = 44f / 3)

/** A message's kind and task: `text-[9px]` under `text-xs`'s 4/3. */
private val badgeText = ppmText(9f, lineHeight = 12f)

/**
 * One button per team when a session tracks more than one (`px-2 py-0.5 rounded-md`), the picked
 * one accent-tinted, scrolling sideways when they run out of room.
 */
@Composable
private fun TeamPicker(names: List<String>, selected: String, sessionId: String?, onPick: (String) -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier.cssHeight(20.5.dp).fillMaxWidth().cssScrollX(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (name in names) {
            key(name) {
                val active = name == selected
                val hover = rememberHover(enabled = !active)
                val shape = RoundedCornerShape(6.dp)
                BasicText(
                    // An implicit team is named after the session, an unreadable uuid.
                    if (name == sessionId) "Team (current session)" else name,
                    Modifier
                        .clip(shape)
                        .background(if (active) tokens.accent.opacity(0.1).color else Color.Transparent)
                        .ppmClickable(pressedColor = Color.Transparent, source = hover.source) { onPick(name) }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    tabText(active).copy(color = (if (active) tokens.accent else tokens.text.mix(tokens.text3, hover.lit.toDouble())).color),
                    softWrap = false,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Members and Messages, each with its count and 44dp tall for a finger (`min-h-[44px]`), the
 * members' also with how many work; then the refresh button at the end.
 */
@Composable
private fun TeamTabs(tab: TeamTab, members: Int, working: Int, messages: Int, loading: Boolean, onTab: (TeamTab) -> Unit, onRefresh: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(Modifier.cssHeight(44.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TeamTabButton(tab == TeamTab.Members, onClick = { onTab(TeamTab.Members) }) { style ->
            BasicText("Members", style = style)
            BasicText("$members", style = style.copy(color = tokens.text3.color))
            if (working > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PulseDot(6.dp, tokens.success.color)
                    BasicText("$working", style = style.copy(color = tokens.success.color))
                }
            }
        }
        TeamTabButton(tab == TeamTab.Messages, onClick = { onTab(TeamTab.Messages) }) { style ->
            BasicText("Messages", style = style)
            BasicText("$messages", style = style.copy(color = tokens.text3.color))
        }
        Spacer(Modifier.weight(1f))
        // `p-3`: `text-text-subtle hover:text-foreground`, with no fade.
        val hover = rememberHover(instant = true)
        Box(
            Modifier
                .semantics { contentDescription = "Refresh" }
                .ppmClickable(pressedColor = Color.Transparent, source = hover.source, onClick = onRefresh)
                .padding(12.dp),
        ) {
            PpmIcon(ProductIcon.RefreshCw, 12.dp, tokens.text.mix(tokens.text3, hover.lit.toDouble()).color, spinning = loading)
        }
    }
}

/**
 * `gap-1.5 px-2.5 rounded-md`: accent-tinted and `font-medium` when [active], else
 * `text-text-subtle hover:text-text-primary`; [content] gets the tab's text style.
 */
@Composable
private fun TeamTabButton(active: Boolean, onClick: () -> Unit, content: @Composable (TextStyle) -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover(enabled = !active)
    val shape = RoundedCornerShape(6.dp)
    val color = if (active) tokens.accent else tokens.text.mix(tokens.text3, hover.lit.toDouble())
    Row(
        Modifier
            .fillMaxHeight()
            .clip(shape)
            .background(if (active) tokens.accent.opacity(0.1).color else Color.Transparent)
            .ppmClickable(pressedColor = Color.Transparent, role = Role.Tab, source = hover.source, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { content(tabText(active).copy(color = color.color)) }
}

/** A [size] dot fading to half and back every 2 s (`animate-pulse`). */
@Composable
private fun PulseDot(size: Dp, color: Color) {
    val dim by rememberInfiniteTransition(label = "pulse").animateFloat(
        1f, 0.5f,
        infiniteRepeatable(tween(1_000, easing = CubicBezierEasing(0.4f, 0f, 0.6f, 1f)), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(Modifier.size(size).alpha(dim).background(color, CircleShape))
}

/** A member row's two lines when it has a step: the first, 2 apart, then the step's 16.5. */
private const val STEP_ROW_CONTENT = 16f + 2f + 16.5f

/**
 * How far down a row with a step its baseline is: the step's bottom, as a `truncate` box is a
 * scroll container, whose baseline CSS Align puts at its bottom edge. The two lines are centred
 * in the 36 the row has inside `py-1`.
 */
private const val STEP_ROW_BASELINE = 4f + (36f - STEP_ROW_CONTENT) / 2 + STEP_ROW_CONTENT

/**
 * The roster (`TeamMemberList`): rows of 44dp, 2dp apart, scrolling past 224dp; while there are
 * none, a line saying whether they are still being read.
 */
@Composable
private fun TeamMemberList(members: List<TeamMemberActivity>, loading: Boolean, now: Instant, onOpen: (String) -> Unit) {
    val tokens = LocalPpmTokens.current
    if (members.isEmpty()) {
        Row(
            Modifier.cssHeight(48.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val style = xsText.copy(color = tokens.text3.color)
            if (loading) {
                PpmIcon(ProductIcon.Loader2, 12.dp, tokens.text3.color, spinning = true)
                BasicText("Reading member sessions…", style = style)
            } else {
                BasicText("No member sessions recorded", style = style)
            }
        }
        return
    }
    // Each row is an inline-block button on a line of the list's own 16px text, 24 tall, sat on
    // its baseline. A step brings the row's baseline down to the step's bottom, and the line still
    // reaches as far under that as the text does, past the row's 2px margin or the last row's
    // nothing: 46.107 apart at 2.625x. Without a step the row sits on its dot or spinner, too
    // high for that.
    val density = LocalDensity.current.density
    val strut = PpmFonts.chromeBelowBaseline(16 * density, 24 * density, PpmFonts.SANS_ASCENT, PpmFonts.SANS_DESCENT) / density
    val unders = members.mapIndexed { index, member ->
        val margin = if (index == members.lastIndex) 0f else 2f
        if (currentStep(member).isNullOrEmpty()) margin else maxOf(margin, strut - (44f - STEP_ROW_BASELINE))
    }
    val height = minOf(44f * members.size + unders.sum(), LIST_MAX.toFloat())
    CssColumn(Modifier.cssHeight(height.dp).fillMaxWidth().cssScrollY()) {
        members.forEachIndexed { index, member ->
            key(member.name) {
                CssColumn(Modifier.fillMaxWidth(), bottom = unders[index].dp) { TeamMemberRow(member, now) { onOpen(member.name) } }
            }
        }
    }
}

/** What the right-hand side calls each state. */
private val STATE_LABELS = mapOf("working" to "working", "paused" to "paused", "no-transcript" to "no session")

/**
 * One teammate (`TeamMemberActivityRow`): a spinner while it works, else a dot, amber while paused;
 * its name, type and model; how long it ran and its state on the right, and a chevron a mouse
 * brings up; what it is on, in mono, under it. The state comes from its transcript, never its
 * inbox, which says active forever.
 */
@Composable
private fun TeamMemberRow(member: TeamMemberActivity, now: Instant, onOpen: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val working = member.isWorking
    val step = currentStep(member)
    val hover = rememberHover()
    val shape = RoundedCornerShape(6.dp)
    val content = if (step.isNullOrEmpty()) 16f else STEP_ROW_CONTENT
    // A button centres what it holds in the height it is given: 44 (`min-h-[44px]`) inside `py-1`.
    val top = 4f + (36f - content) / 2
    CssColumn(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .ppmClickable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, shape = shape, source = hover.source, onClick = onOpen)
            .padding(horizontal = 6.dp),
        gap = 2.dp,
        top = top.dp,
        bottom = (44f - top - content).dp,
    ) {
        MemberLine(
            lead = if (working) 12.dp else 6.dp,
            leadContent = {
                if (working) {
                    PpmIcon(ProductIcon.Loader2, 12.dp, tokens.success.color, spinning = true)
                } else {
                    Box(Modifier.size(6.dp).background((if (member.workState == "paused") tokens.warning else tokens.text3).color, CircleShape))
                }
            },
            name = member.name,
            type = shortAgentType(member.agentType),
            model = member.model?.takeIf { it.isNotEmpty() },
            elapsed = formatDuration(member.startedAt, if (working) null else member.lastEventAt, now),
            state = STATE_LABELS[member.workState].orEmpty(),
            stateColor = (if (working) tokens.success else tokens.text3).color,
            chevronAlpha = hover.lit,
        )
        if (!step.isNullOrEmpty()) {
            TruncatedText(AnnotatedString(step), stepText.copy(color = tokens.text3.color), Modifier.padding(start = 20.dp).cssLines(16.5f))
        }
    }
}

/**
 * A member's first line as Chrome's flexbox lays it out: the [lead] wide dot or spinner, the name,
 * the type and the model 8dp apart, and at the end (`ml-auto`) how long it ran, its state and the
 * chevron, 4dp apart. When the line runs out of room the name and the type give up width, each in
 * proportion to its own (`truncate`, so either can go to nothing); nothing else does. Every item
 * is centred on the 16dp line, at its exact place in fractions of a pixel, text as wide as it
 * measures before rounding, and put on the pixel that rounds to, as Chrome paints it.
 */
@Composable
private fun MemberLine(
    lead: Dp,
    leadContent: @Composable () -> Unit,
    name: String,
    type: String?,
    model: String?,
    elapsed: String?,
    state: String,
    stateColor: Color,
    chevronAlpha: Float,
) {
    val tokens = LocalPpmTokens.current
    val nameStyle = xsText.copy(color = tokens.text.color, fontWeight = FontWeight.Medium)
    val muted = tinyText.copy(color = tokens.text3.color)
    val modelLabel = model?.let { "($it)" }
    val measurer = rememberTextMeasurer()
    val exact = rememberCssExact()
    Layout(
        content = {
            Box(Modifier.layoutId("lead")) { leadContent() }
            TruncatedText(AnnotatedString(name), nameStyle, Modifier.layoutId("name"))
            if (type != null) TruncatedText(AnnotatedString(type), muted, Modifier.layoutId("type"))
            if (modelLabel != null) BasicText(modelLabel, Modifier.layoutId("model"), muted, softWrap = false, maxLines = 1)
            if (elapsed != null) BasicText(elapsed, Modifier.layoutId("elapsed"), muted, softWrap = false, maxLines = 1)
            BasicText(state, Modifier.layoutId("state"), muted.copy(color = stateColor), softWrap = false, maxLines = 1)
            PpmIcon(ProductIcon.ChevronRight, 12.dp, tokens.text3.color, Modifier.layoutId("chevron").alpha(chevronAlpha))
        },
        Modifier.fillMaxWidth().cssPhased(exact),
    ) { measurables, constraints ->
        val phase = exact.cssPhase()
        fun width(text: String?, style: TextStyle) =
            if (text == null) 0f else measurer.measure(text, style, softWrap = false, maxLines = 1).let { it.getLineRight(0) - it.getLineLeft(0) }
        val gap = 8.dp.toPx()
        val endGap = 4.dp.toPx()
        val icon = 12.dp.toPx()
        val line = 16.dp.toPx()
        val tiny = (40f / 3).sp.toPx()
        val leadWidth = lead.toPx()
        val nameWant = width(name, nameStyle)
        val typeWant = width(type, muted)
        val modelWidth = width(modelLabel, muted)
        val elapsedWidth = width(elapsed, muted)
        val stateWidth = width(state, muted)
        val endWidth = (if (elapsed != null) elapsedWidth + endGap else 0f) + stateWidth + endGap + icon
        val gaps = 2 + (if (type != null) 1 else 0) + (if (modelLabel != null) 1 else 0)
        val room = (constraints.maxWidth - leadWidth - modelWidth - endWidth - gaps * gap).coerceAtLeast(0f)
        val over = (nameWant + typeWant - room).coerceAtLeast(0f)
        val keep = if (over == 0f) 1f else (1f - over / (nameWant + typeWant)).coerceAtLeast(0f)
        val placed = ArrayList<Triple<Placeable, Int, Int>>(7)
        // [shrunk] is the width an item gave up to; one that kept its own is measured at it, so a
        // rounded-down box never cuts a name that fits.
        fun put(id: String, x: Float, height: Float, shrunk: Float? = null) {
            val measurable = measurables.firstOrNull { it.layoutId == id } ?: return
            val left = x.roundToInt()
            val box = if (shrunk == null) Constraints() else Constraints.fixedWidth(((x + shrunk).roundToInt() - left).coerceAtLeast(0))
            placed += Triple(measurable.measure(box), left, (phase + (line - height) / 2).roundToInt())
        }
        put("lead", 0f, leadWidth)
        var x = leadWidth + gap
        put("name", x, line, if (over > 0f) nameWant * keep else null)
        x += nameWant * keep + gap
        if (type != null) {
            put("type", x, tiny, if (over > 0f) typeWant * keep else null)
            x += typeWant * keep + gap
        }
        put("model", x, tiny)
        var end = constraints.maxWidth - icon
        put("chevron", end, icon)
        end -= endGap + stateWidth
        put("state", end, tiny)
        put("elapsed", end - endGap - elapsedWidth, tiny)
        layout(constraints.maxWidth, line.roundToInt()) {
            for ((placeable, left, y) in placed) placeable.place(left, y)
        }
    }
}

/**
 * The conversation (`TeamMessageList`), oldest first, scrolling past 224dp and gliding to its end
 * whenever a message comes in. 8dp under every message, the last too: the end it scrolls to is a
 * list item of its own.
 */
@Composable
private fun TeamMessageList(messages: List<TeamMessage>) {
    val tokens = LocalPpmTokens.current
    if (messages.isEmpty()) {
        BasicText(
            "No messages yet",
            Modifier.cssHeight(48.dp).fillMaxWidth().padding(vertical = 16.dp),
            xsText.copy(color = tokens.text3.color, textAlign = TextAlign.Center),
        )
        return
    }
    val scroll = rememberScrollState()
    LaunchedEffect(messages.size) {
        // `scrollIntoView({ behavior: "smooth" })`, once the new rows are laid out.
        withFrameNanos { }
        scroll.animateScrollTo(scroll.maxValue)
    }
    CssColumn(Modifier.fillMaxWidth().heightIn(max = LIST_MAX.dp).cssScrollY(scroll), gap = 8.dp, bottom = 8.dp) {
        for (message in messages) TeamMessageRow(message)
    }
}

/**
 * One message: who sent it to whom, the sender in its own colour, and when; its kind and task as
 * badges ahead of its headline; up to two lines of body under that. A payload is never shown
 * raw ([TeamMessagePreview]), and runs of white space read as one, as HTML shows them.
 */
@Composable
private fun TeamMessageRow(message: TeamMessage) {
    val tokens = LocalPpmTokens.current
    val preview = remember(message.text, message.summary) { TeamMessagePreview.of(message.text, message.summary) }
    val muted = tokens.text3.color
    CssColumn(Modifier.fillMaxWidth(), gap = 2.dp) {
        // What `toLocaleTimeString` makes of a time that does not parse.
        MessageHeader(message.from, cssColor(message.color) ?: muted, message.to, message.time?.let(PpmFormat::twoDigitTimeOfDay) ?: "Invalid Date")
        MessageHeadline(message.parsedType?.let(TEAM_BADGES::get), preview)
        preview.detail?.let {
            ClampedText(AnnotatedString(chromeBreaks(PpmFormat.collapsedWhitespace(it))), detailText.copy(color = muted), maxLines = 2, Modifier.fillMaxWidth().cssLines(44f / 3))
        }
    }
}

/**
 * Who sent a message, to whom and when (`flex items-center gap-1`, the time `ml-auto`), as Chrome
 * fits the four into the row: when they run out of room each gives up width in proportion to its
 * own ([flexShrink]), never below its longest unbreakable run, and its text wraps where Chrome
 * ends lines, so "team-lead" beside a long name takes two lines as on the web. Each is centred on
 * the row at its exact place.
 */
@Composable
private fun MessageHeader(from: String, fromColor: Color, to: String, time: String) {
    val muted = LocalPpmTokens.current.text3.color
    val items = remember(from, fromColor, to, time, muted) {
        listOf(
            chromeBreaks(from) to xsText.copy(color = fromColor, fontWeight = FontWeight.Medium),
            "→" to xsText.copy(color = muted),
            chromeBreaks(to) to xsText.copy(color = muted),
            chromeBreaks(time) to tinyText.copy(color = muted),
        )
    }
    val measurer = rememberTextMeasurer()
    // Each one's max-content and min-content, which only its text decides.
    val widths = remember(items, measurer) {
        fun width(text: String, style: TextStyle) =
            measurer.measure(text, style, softWrap = false, maxLines = 1).let { it.getLineRight(0) - it.getLineLeft(0) }
        items.map { (text, style) -> width(text, style) to minContent(text) { width(it, style) } }
    }
    val exact = rememberCssExact()
    Layout(
        content = { for ((text, style) in items) BasicText(text, style = style) },
        Modifier.fillMaxWidth().cssPhased(exact),
    ) { measurables, constraints ->
        val phase = exact.cssPhase()
        val gap = 4.dp.toPx()
        val room = constraints.maxWidth - gap * (items.size - 1)
        val sizes = flexShrink(widths.map { it.first }, widths.map { it.second }, room)
        val lefts = FloatArray(items.size)
        var x = 0f
        for (i in items.indices) {
            lefts[i] = x
            x += sizes[i] + gap
        }
        // `ml-auto`: what room is left goes before the time.
        lefts[items.lastIndex] += (room - sizes.sum()).coerceAtLeast(0f)
        // Each laid out in the whole pixels its width rounds up to, so that a line which fits in
        // Chrome's fractional width fits in Android's too.
        val placeables = measurables.mapIndexed { i, measurable -> measurable.measure(Constraints.fixedWidth(ceil(sizes[i] - 0.01f).toInt())) }
        val heights = placeables.mapIndexed { i, placeable ->
            val line = items[i].second.lineHeight.toPx()
            maxOf(1, (placeable.height / line).roundToInt()) * line
        }
        val height = heights.max()
        val box = (phase + height).roundToInt()
        exact.cssReport(height, box)
        layout(constraints.maxWidth, box) {
            placeables.forEachIndexed { i, placeable -> placeable.place(lefts[i].roundToInt(), (phase + (height - heights[i]) / 2).roundToInt()) }
        }
    }
}

/**
 * The longest run of [text] no line can end inside, under which Chrome will not shrink a flex item
 * (`min-width: auto`); the spaces ending a run hang past the line, so they do not count.
 */
private fun minContent(text: String, width: (String) -> Float): Float {
    val breaks = BreakIterator.getLineInstance()
    breaks.setText(text)
    var widest = 0f
    var start = breaks.first()
    var end = breaks.next()
    while (end != BreakIterator.DONE) {
        widest = maxOf(widest, width(text.substring(start, end).trimEnd(' ')))
        start = end
        end = breaks.next()
    }
    return widest
}

/**
 * Flex items shrunk to fit [room] as CSS flexbox resolves their lengths (§9.7), each
 * `flex-shrink: 1`: every item gives up width in proportion to its [base] size, and one that would
 * go under its [min] stops there while the rest share what is still over.
 */
internal fun flexShrink(base: List<Float>, min: List<Float>, room: Float): List<Float> {
    val sizes = base.toMutableList()
    if (base.sum() <= room) return sizes
    val frozen = BooleanArray(base.size)
    while (!frozen.all { it }) {
        val over = base.indices.sumOf { (if (frozen[it]) sizes[it] else base[it]).toDouble() } - room
        val scaled = base.indices.filter { !frozen[it] }.sumOf { base[it].toDouble() }
        val clamped = BooleanArray(base.size)
        var violation = 0.0
        for (i in base.indices) {
            if (frozen[i]) continue
            val target = base[i] - if (scaled > 0) over * base[i] / scaled else 0.0
            // Against the min itself: a target rounded up to a Float would read as held.
            clamped[i] = min[i] > target
            sizes[i] = (if (clamped[i]) min[i].toDouble() else target).toFloat()
            if (clamped[i]) violation += min[i] - target
        }
        if (violation <= 0) break
        for (i in base.indices) if (clamped[i]) frozen[i] = true
    }
    return sizes
}

/**
 * The headline in `text-foreground/90`, after the kind badge and the `#task` chip: each an
 * `inline-block px-1 rounded` with `mr-1`, its baseline on the line's.
 */
@Composable
private fun MessageHeadline(badge: Pair<String, TeamBadgeTone>?, preview: TeamMessagePreview) {
    val tokens = LocalPpmTokens.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val inline = remember(badge, preview.taskId, tokens, density) {
        val chips = buildList {
            badge?.let { (label, tone) -> add(Triple(label, tone.colors(tokens).first, tone.colors(tokens).second)) }
            preview.taskId?.let { add(Triple("#$it", tokens.panel2, tokens.text3)) }
        }
        chips.map { (label, fill, color) ->
            val text = measurer.measure(label, badgeText.copy(color = color.color), softWrap = false, maxLines = 1)
            with(density) {
                val pad = 4.dp.toPx()
                val width = text.getLineRight(0) + 2 * pad
                label to InlineTextContent(Placeholder((width + pad).toSp(), text.firstBaseline.toSp(), PlaceholderVerticalAlign.AboveBaseline)) {
                    Canvas(Modifier.fillMaxSize()) {
                        val top = size.height - text.firstBaseline
                        drawRoundRect(fill.color, Offset(0f, top), Size(width, text.size.height.toFloat()), CornerRadius(4.dp.toPx()))
                        drawText(text, topLeft = Offset(pad, top))
                    }
                }
            }
        }
    }
    val line = buildAnnotatedString {
        inline.forEachIndexed { index, (label, _) -> appendInlineContent("chip$index", label) }
        append(chromeBreaks(PpmFormat.collapsedWhitespace(preview.title)))
    }
    BasicText(
        line,
        Modifier.fillMaxWidth().cssLines(16f),
        xsText.copy(color = tokens.text.opacity(0.9).color),
        inlineContent = inline.withIndex().associate { (index, chip) -> "chip$index" to chip.second },
    )
}

/** The colour names Claude Code gives teammates, as CSS reads them. */
private val CSS_COLOR_NAMES = mapOf(
    "red" to 0xFFFF0000, "blue" to 0xFF0000FF, "green" to 0xFF008000, "yellow" to 0xFFFFFF00,
    "purple" to 0xFF800080, "orange" to 0xFFFFA500, "pink" to 0xFFFFC0CB, "cyan" to 0xFF00FFFF,
)

/**
 * `safeColor`: a sender's colour when it is a hex colour or a CSS colour name, else none and the
 * line's own grey. Of the names, those Claude Code hands out are known; any other reads as grey.
 */
internal fun cssColor(value: String?): Color? {
    if (value == null) return null
    if (!value.startsWith('#')) return CSS_COLOR_NAMES[value.lowercase()]?.let { Color(it) }
    val hex = value.substring(1)
    if (hex.any { Character.digit(it, 16) < 0 }) return null
    val full = when (hex.length) {
        3, 4 -> hex.map { "$it$it" }.joinToString("")
        6, 8 -> hex
        else -> return null
    }
    val alpha = if (full.length == 8) full.substring(6).toLong(16) else 0xFF
    return Color((alpha shl 24 or full.substring(0, 6).toLong(16)).toInt())
}
