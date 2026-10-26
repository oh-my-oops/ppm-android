package com.hienlh.ppm.app.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.chat.AgentSessionOpener
import com.hienlh.ppm.chat.LocalAgentSessionOpener
import com.hienlh.ppm.chat.MarkdownStyle
import com.hienlh.ppm.chat.MarkdownView
import com.hienlh.ppm.chat.ToolCardView
import com.hienlh.ppm.chat.breakAnywhere
import com.hienlh.ppm.client.groupTranscript
import com.hienlh.ppm.design.CssColumn
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssFlex
import com.hienlh.ppm.design.cssLines
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.protocol.ChatEvent
import com.hienlh.ppm.protocol.GroupMessage
import com.hienlh.ppm.protocol.GroupTranscript
import com.hienlh.ppm.protocol.HistoryMessage
import com.hienlh.ppm.protocol.TranscriptConfig
import com.hienlh.ppm.state.AgentChildren
import com.hienlh.ppm.state.AgentProgress
import com.hienlh.ppm.state.ToolBlock
import kotlinx.coroutines.CancellationException

/** The transcript's text: nothing between it and the page sets a size, so the page's 16 px on lines 1.5 of it. */
private val TRANSCRIPT_TEXT = MarkdownStyle(size = 16f, lineHeightRatio = 1.5f)

/**
 * "<member> — full transcript" (group-full-transcript-view.tsx): the session behind a member's
 * reply, read-only — on a tablet the web's dialog (`max-h-[80vh] sm:max-w-2xl`, the title
 * `text-sm`), on a phone a sheet (`max-h-[85vh]`). A transcript the server cannot read says why,
 * with the reply's summary under it. Its Agent cards open the steps they hold, under a session id
 * the hub can never find, as the web's do for want of a session of their own.
 */
@Composable
internal fun GroupTranscriptView(model: AppModel, groupId: String, message: GroupMessage, onClose: () -> Unit) {
    val api = model.api ?: return
    val tokens = LocalPpmTokens.current
    val sessionRef = message.fullSessionRef.orEmpty()
    val loaded by produceState<Result<GroupTranscript>?>(null, api, groupId, sessionRef) {
        value = try {
            Result.success(api.groupTranscript(groupId, sessionRef))
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }
    val title = "${message.fromMember} — full transcript"
    val opener = remember(model) { AgentSessionOpener(null, model::openAgentSession) }
    // A `vh` is Chrome's large viewport: everything under the status bar.
    val density = LocalDensity.current
    val vh = with(density) { (LocalWindowInfo.current.containerSize.height - WindowInsets.statusBars.getTop(density)).toDp() }
    CompositionLocalProvider(LocalAgentSessionOpener provides opener) {
        if (LocalPpmMobile.current) {
            BottomSheet(onClose, Modifier.heightIn(max = vh * 0.85f)) {
                // `flex flex-col min-h-0 px-4 pb-2`, the title `mb-2 text-sm font-semibold`.
                CssColumn(
                    Modifier.fillMaxWidth().weight(1f, fill = false).padding(horizontal = 16.dp),
                    gap = 8.dp,
                    top = LocalSheetContentTop.current,
                    bottom = 8.dp,
                ) {
                    BasicText(title, Modifier.cssLines(20f), style = ppmText(14f, FontWeight.SemiBold, lineHeight = 20f).copy(color = tokens.text.color))
                    TranscriptBody(loaded, message.summary, Modifier.cssFlex())
                }
            }
        } else {
            // Nothing in the body takes the focus while it loads, so the cross has it.
            WebDialog(
                title,
                onClose,
                widest = 672.dp,
                titleStyle = ppmText(14f, FontWeight.SemiBold, lineHeight = 14f),
                closeFocused = true,
                tallest = vh * 0.8f,
            ) { TranscriptBody(loaded, message.summary, Modifier) }
        }
    }
}

/** `min-h-0 flex-1 overflow-y-auto`: a spinner 40 px down while it loads, then the transcript or why there is none. */
@Composable
private fun TranscriptBody(loaded: Result<GroupTranscript>?, summary: String?, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    val transcript = loaded?.getOrNull()
    if (transcript != null) {
        TranscriptMessages(transcript, modifier)
        return
    }
    Box(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        if (loaded == null) {
            Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                PpmIcon(ProductIcon.Loader2, 20.dp, tokens.accent.color, spinning = true)
            }
        } else {
            TranscriptUnavailable(loaded.exceptionOrNull()?.message ?: "Failed to load transcript", summary)
        }
    }
}

/**
 * `px-1 py-4 text-sm text-text-secondary`: "Full transcript unavailable" (`mb-2 font-medium
 * text-foreground`), why (`text-xs text-text-subtle`), and the reply's summary in a `<pre>`
 * (`mt-4 whitespace-pre-wrap break-words rounded-md bg-surface-elevated p-3 text-xs leading-relaxed`).
 */
@Composable
private fun TranscriptUnavailable(error: String, summary: String?) {
    val tokens = LocalPpmTokens.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 16.dp)) {
        BasicText(
            "Full transcript unavailable",
            Modifier.padding(bottom = 8.dp),
            style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text.color),
        )
        BasicText(error, style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color))
        if (!summary.isNullOrEmpty()) {
            BasicText(
                summary.removeSuffix("\n"),
                Modifier
                    .padding(top = 16.dp)
                    .fillMaxWidth()
                    .background(tokens.panel2.color, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                style = ppmText(12f, kind = FontKind.MONO, lineHeight = 19.5f).copy(color = tokens.text2.color),
            )
        }
    }
}

/**
 * The transcript (`px-1 py-1`, its rows `flex flex-col gap-3`): the session's input config, then
 * each message under its role; "Transcript is empty." when it has none.
 */
@Composable
private fun TranscriptMessages(transcript: GroupTranscript, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    val config = transcript.config?.rows().orEmpty()
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (config.isNotEmpty()) item { ConfigBlock(config) }
        items(transcript.messages) { TranscriptMessage(it) }
        if (transcript.messages.isEmpty()) {
            item {
                BasicText(
                    "Transcript is empty.",
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text3.color, textAlign = TextAlign.Center),
                )
            }
        }
    }
}

/** A message (`flex flex-col gap-1.5`): its role (`text-[10px] font-semibold uppercase tracking-wide text-text-subtle`), then what it says. */
@Composable
private fun TranscriptMessage(message: HistoryMessage) {
    val tokens = LocalPpmTokens.current
    val role = when (message.role) {
        HistoryMessage.Role.USER -> "User"
        HistoryMessage.Role.ASSISTANT -> "Assistant"
        HistoryMessage.Role.SYSTEM -> message.role.wire
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(role.uppercase(), style = ppmText(10f, FontWeight.SemiBold, lineHeight = 15f).copy(color = tokens.text3.color, letterSpacing = 0.025.em))
        MessageEvents(message)
    }
}

/**
 * What a message says: its text as markdown when it has no events; else each event in its order
 * (`flex flex-col gap-2`) — text as markdown, reasoning folded away, and each call as its card with
 * the result the message holds for it. Results are drawn in their calls' cards, nothing else shown.
 */
@Composable
private fun MessageEvents(message: HistoryMessage) {
    val events = message.events
    if (events.isEmpty()) {
        if (message.content.isNotBlank()) MarkdownView(message.content, style = TRANSCRIPT_TEXT)
        return
    }
    val results = remember(events) {
        events.filterIsInstance<ChatEvent.ToolResult>().filter { it.toolUseId != null }.associateBy { it.toolUseId }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (event in events) {
            when (event) {
                is ChatEvent.Text -> if (event.text.isNotBlank()) MarkdownView(event.text, style = TRANSCRIPT_TEXT)
                is ChatEvent.Thinking -> ThinkingBlock(event.text)
                is ChatEvent.ToolUse -> {
                    val result = event.toolUseId?.let { results[it] }
                    ToolCardView(remember(event, result) { toolBlock(event, result) }, completed = result != null)
                }
                else -> Unit
            }
        }
    }
}

/** A call's card as history makes it, with [result] the one the message holds for it. */
private fun toolBlock(use: ChatEvent.ToolUse, result: ChatEvent.ToolResult?): ToolBlock {
    val agent = use.name == "Agent" || use.name == "Task"
    return ToolBlock(
        toolUseId = use.toolUseId,
        name = use.name,
        input = use.input,
        result = result?.outcome,
        progress = if (agent) AgentProgress.of(use.children) else null,
        bgStatus = use.bgStatus,
        children = if (agent) AgentChildren.ofHistory(use.children, use.transcriptAvailable) else null,
    )
}

/** "Thinking", folded (`text-text-subtle hover:text-text-secondary`), the reasoning under it `px-3 py-2 text-xs leading-relaxed text-text-secondary`. */
@Composable
private fun ThinkingBlock(text: String) {
    val tokens = LocalPpmTokens.current
    Disclosure("Thinking", ProductIcon.Brain, tokens.text3, tokens.text2, FontWeight.Normal) {
        MarkdownView(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MarkdownStyle(12f, 1.625f, color = tokens.text2))
    }
}

/** The config's rows that are set, in the web's order. */
private fun TranscriptConfig.rows(): List<Pair<String, String>> =
    listOf("Model" to model, "Permission" to permissionMode, "CWD" to cwd, "Git branch" to gitBranch, "CLI version" to version)
        .mapNotNull { (name, value) -> value?.takeIf { it.isNotEmpty() }?.let { name to it } }

/** "Input config", folded (`font-medium text-text-secondary hover:text-foreground`). */
@Composable
private fun ConfigBlock(rows: List<Pair<String, String>>) {
    val tokens = LocalPpmTokens.current
    Disclosure("Input config", ProductIcon.Settings2, tokens.text2, tokens.text, FontWeight.Medium) { ConfigRows(rows) }
}

/**
 * `grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 px-3 py-2 text-xs`: each name (`text-text-subtle`) in
 * a column as wide as the widest, its value (`break-all font-mono text-text-secondary`) beside it.
 */
@Composable
private fun ConfigRows(rows: List<Pair<String, String>>) {
    val tokens = LocalPpmTokens.current
    Layout(
        content = {
            for ((name, value) in rows) {
                BasicText(name, style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color))
                BasicText(remember(value) { breakAnywhere(value) }, style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text2.color))
            }
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) { measurables, constraints ->
        val gapX = 12.dp.roundToPx()
        val gapY = 4.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val names = measurables.filterIndexed { index, _ -> index % 2 == 0 }.map { it.measure(loose) }
        val column = names.maxOfOrNull { it.width } ?: 0
        val valueWidth = (constraints.maxWidth - column - gapX).coerceAtLeast(0)
        val values = measurables.filterIndexed { index, _ -> index % 2 == 1 }.map { it.measure(loose.copy(maxWidth = valueWidth)) }
        val tops = IntArray(names.size)
        var height = 0
        names.indices.forEach { index ->
            if (index > 0) height += gapY
            tops[index] = height
            height += maxOf(names[index].height, values[index].height)
        }
        layout(constraints.maxWidth, height) {
            names.forEachIndexed { index, name ->
                name.place(0, tops[index])
                values[index].place(column + gapX, tops[index])
            }
        }
    }
}

/**
 * A box that opens (`rounded-md border border-border bg-surface-elevated/40`): its button (`flex
 * w-full items-center gap-1.5 px-2.5 py-1.5 text-xs`) a chevron that turns as it opens
 * (`transition-transform`), an icon and [label] in [color] or, under a mouse, [hoverColor]; open,
 * [content] under a border (`border-t border-border`). Folded to begin with.
 */
@Composable
private fun Disclosure(label: String, icon: ProductIcon, color: Rgba, hoverColor: Rgba, weight: FontWeight, content: @Composable () -> Unit) {
    val tokens = LocalPpmTokens.current
    var open by remember { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 90f else 0f, tween(150, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)), label = "chevron")
    val hover = rememberHover(instant = true)
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.panel2.opacity(0.4).color)
            .cssBorder(tokens.border.color, shape),
    ) {
        val ink = hoverColor.mix(color, hover.lit.toDouble()).color
        Row(
            Modifier
                .fillMaxWidth()
                .ppmClickable(pressedColor = Color.Transparent, source = hover.source) { open = !open }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PpmIcon(ProductIcon.ChevronRight, 14.dp, ink, Modifier.rotate(turn))
            PpmIcon(icon, 14.dp, ink)
            BasicText(label, style = ppmText(12f, weight, lineHeight = 16f).copy(color = ink))
        }
        if (open) {
            Box(Modifier.fillMaxWidth().cssBorder(tokens.border.color, sides = CssSides.Top)) { content() }
        }
    }
}
