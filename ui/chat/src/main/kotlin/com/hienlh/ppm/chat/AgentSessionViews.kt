package com.hienlh.ppm.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.CssColumn
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.CursorText
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.TextCursorRegion
import com.hienlh.ppm.design.TruncatedText
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssExact
import com.hienlh.ppm.design.cssHeight
import com.hienlh.ppm.design.cssLazyItem
import com.hienlh.ppm.design.cssLines
import com.hienlh.ppm.design.cssScrollY
import com.hienlh.ppm.design.cssScrollbar
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberCssExact
import com.hienlh.ppm.design.rememberCssLazyStack
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.protocol.ChatEvent
import com.hienlh.ppm.state.AgentSessionIdentity
import com.hienlh.ppm.state.AgentSessionPayload
import com.hienlh.ppm.state.AgentSessionStatus
import com.hienlh.ppm.state.AgentSessionStream
import com.hienlh.ppm.state.AgentStepRow
import com.hienlh.ppm.state.GlobalChannel
import com.hienlh.ppm.state.ToolBlock
import com.hienlh.ppm.state.agentSessionStatus
import com.hienlh.ppm.state.agentStepRows

/**
 * Opens an agent's session for the chat it is provided in (`useOpenAgentSession` with the chat's
 * `AgentSessionContext`): the window on a tablet, the sheet on a phone. [identity] is the chat's
 * own session; with none, a card opens on the steps it holds.
 */
class AgentSessionOpener(val identity: AgentSessionIdentity?, val open: (payload: AgentSessionPayload, fallback: List<ChatEvent>?) -> Unit)

/** None, and an Agent row has nowhere to open its session. */
val LocalAgentSessionOpener = compositionLocalOf<AgentSessionOpener?> { null }

/**
 * An agent's session, followed live (`AgentSessionWindowContent`): the window's body on a tablet
 * and the sheet's on a phone. [fallback] is what the chat held of it, shown until — or unless —
 * the hub has a transcript to stream. [channel] is `/ws/global`; none yet, and it waits.
 */
@Composable
fun AgentSessionContent(payload: AgentSessionPayload?, channel: GlobalChannel?, fallback: List<ChatEvent>?, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    // Another source is another subscription; a new title or prompt is not.
    val stream = remember(payload?.projectName, payload?.providerId, payload?.sessionId, payload?.source, channel) {
        if (payload != null && channel != null) AgentSessionStream(channel, payload.projectName, payload.providerId, payload.sessionId, payload.source) else null
    }
    DisposableEffect(stream) {
        stream?.start()
        onDispose { stream?.stop() }
    }
    // A payload naming no session gets the hub's `bad_request`, without asking it.
    val state = stream?.state?.collectAsState()?.value
        ?: AgentSessionStream.State(loading = payload != null, error = if (payload == null) "bad_request" else null)
    val events = state.shown(fallback.orEmpty())
    val status = agentSessionStatus(events, state.loading, state.running)
    // Each card stays as it was left when scrolled away and back, as the web keeps every one mounted.
    val openCards = remember(payload?.key) { mutableStateMapOf<String, Boolean>() }
    Column(modifier.fillMaxSize().background(tokens.panel.color)) {
        // The header and the prompt row stacked in fractions, as Chrome stacks them: 193 px at 2.625x,
        // where rounded apiece they come to 194 and every step under them starts a pixel low.
        CssColumn(Modifier.fillMaxWidth()) {
            // As tall as the title's line with `py-1.5`, which the title's column keeps in fractions.
            val exact = rememberCssExact()
            Row(
                Modifier.fillMaxWidth().cssExact(exact).cssBorder(tokens.border.opacity(0.3).color, sides = CssSides.Bottom).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // `ml-auto` on "offline": the rest keep to the left, the title giving way first.
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CssColumn(Modifier.weight(1f, fill = false), top = 6.dp, bottom = 6.dp, exact = exact) {
                        TruncatedText(AnnotatedString(payload?.title ?: "Agent session"), headerTitle.copy(color = tokens.text.color), Modifier.cssLines(16f))
                    }
                    if (events.isNotEmpty()) BasicText(stepCount(events.count { it is ChatEvent.ToolUse }), style = headerTiny.copy(color = tokens.text3.color))
                    StatusBadge(status)
                }
                if (!state.available && !state.loading) {
                    BasicText(
                        "offline",
                        Modifier.webTitle("No live transcript for this session — showing steps already held in memory"),
                        headerTiny.copy(color = tokens.text3.color),
                    )
                }
            }
            payload?.prompt?.takeIf { it.isNotEmpty() }?.let { PromptRow(it, payload.key) }
        }
        when {
            state.loading && events.isEmpty() -> Row(
                Modifier.weight(1f).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                PpmIcon(ProductIcon.Loader2, 12.dp, tokens.text3.color, spinning = true)
                BasicText("Loading session…", style = headerTitle.copy(color = tokens.text3.color, fontWeight = FontWeight.Normal))
            }
            events.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                BasicText(
                    if (state.error != null) "Could not load this session" else "No recorded steps",
                    style = headerTitle.copy(color = tokens.text3.color, fontWeight = FontWeight.Normal, textAlign = TextAlign.Center),
                )
            }
            else -> CompositionLocalProvider(LocalOpenCards provides openCards) {
                SubagentChildren(events, window = true, Modifier.weight(1f))
            }
        }
    }
}

/** `text-xs font-medium`, in a row with no line height of its own: the page's 1.5. */
private val headerTitle = ppmText(12f, FontWeight.Medium, lineHeight = 16f)

/** `text-[10px]` under the page's `line-height: 1.5`. */
private val headerTiny = ppmText(10f, lineHeight = 15f)

/** Nothing while loading, then running, failed or done (`StatusBadge`). */
@Composable
private fun StatusBadge(status: AgentSessionStatus) {
    val tokens = LocalPpmTokens.current
    when (status) {
        AgentSessionStatus.LOADING -> Unit
        AgentSessionStatus.RUNNING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PpmIcon(ProductIcon.Loader2, 10.dp, tokens.accent.color, spinning = true)
            BasicText("running", style = headerTiny.copy(color = tokens.accent.color))
        }
        AgentSessionStatus.FAILED -> BasicText("failed", style = headerTiny.copy(color = tokens.error.color))
        AgentSessionStatus.DONE -> BasicText("done", style = headerTiny.copy(color = tokens.success.color))
    }
}

/** The call's own prompt, folded behind a row (`Prompt`), 44dp tall for a finger as a coarse pointer gets it. */
@Composable
private fun PromptRow(prompt: String, key: String) {
    val tokens = LocalPpmTokens.current
    var open by remember(key) { mutableStateOf(false) }
    CssColumn(Modifier.fillMaxWidth().cssBorder(tokens.border.opacity(0.3).color, sides = CssSides.Bottom), bottom = if (open) 8.dp else 0.dp) {
        // `text-text-subtle hover:text-foreground`.
        val hover = rememberHover()
        val color = tokens.text.mix(tokens.text3, hover.lit.toDouble()).color
        Row(
            Modifier
                .fillMaxWidth()
                // Its line and `py-1.5` come to 28.5, short of `min-h-[44px]`.
                .cssHeight(44.dp)
                .ppmClickable(source = hover.source) { open = !open }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PpmIcon(if (open) ProductIcon.ChevronDown else ProductIcon.ChevronRight, 12.dp, color)
            BasicText("Prompt", style = promptText.copy(color = color))
        }
        if (open) {
            // `select-text`: the I-beam over the prompt's glyphs.
            TextCursorRegion {
                CursorText(prompt, Modifier.fillMaxWidth().padding(horizontal = 12.dp).cssLines(16.5f), promptText.copy(color = tokens.text2.color))
            }
        }
    }
}

/** `text-[11px]` under the page's `line-height: 1.5`. */
private val promptText = ppmText(11f, lineHeight = 16.5f)

/**
 * An agent's steps (`SubagentChildren`): its text run together, each call a card with its result.
 * The [window]'s own fill its body; a card's sit under a 2dp rule, 256dp tall at most (384 from
 * 768 up). Either follows the newest step unless the user has scrolled more than 20dp up from it.
 */
@Composable
internal fun SubagentChildren(events: List<ChatEvent>, window: Boolean, modifier: Modifier = Modifier) {
    val rows = remember(events) { agentStepRows(events) }
    val follow = with(LocalDensity.current) { 20.dp.toPx() }
    if (window) {
        val list = rememberLazyListState()
        var scrolledUp by remember { mutableStateOf(false) }
        LaunchedEffect(list) {
            snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }.collect { scrolledUp = list.distanceToEnd() > follow }
        }
        LaunchedEffect(rows.size) {
            if (scrolledUp) return@LaunchedEffect
            // The new rows are laid out by the next frame.
            withFrameNanos { }
            list.scrollToEnd()
        }
        // `space-y-1` in fractions of a pixel, as Chrome stacks the steps.
        val stack = rememberCssLazyStack(4.dp)
        LazyColumn(
            modifier.fillMaxWidth().cssScrollbar(list),
            state = list,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            items(rows.size) { index -> StepRow(rows[index], window, Modifier.cssLazyItem(stack, index, last = index == rows.lastIndex)) }
        }
    } else {
        val tokens = LocalPpmTokens.current
        val scroll = rememberScrollState()
        var scrolledUp by remember { mutableStateOf(false) }
        LaunchedEffect(scroll) {
            snapshotFlow { scroll.value }.collect { scrolledUp = scroll.maxValue - it > follow }
        }
        LaunchedEffect(rows.size) {
            if (scrolledUp) return@LaunchedEffect
            withFrameNanos { }
            scroll.scrollTo(scroll.maxValue)
        }
        val max = if (LocalConfiguration.current.screenWidthDp >= 768) 384.dp else 256.dp
        Column(
            // Its `mt-1` falls inside the 6dp the card's body puts between its parts. `border-accent/20`
            // is `--panel-2` at 20%, the web's `accent` being a hover surface: barely a rule at all.
            modifier
                .fillMaxWidth()
                .heightIn(max = max)
                .cssBorder(tokens.panel2.opacity(0.2).color, width = 2.dp, sides = CssSides.Start)
                .cssScrollY(scroll)
                .padding(start = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            rows.forEach { StepRow(it, window) }
        }
    }
}

@Composable
private fun StepRow(row: AgentStepRow, window: Boolean, modifier: Modifier = Modifier) {
    when (row) {
        // `text-text-secondary text-[11px]`, at the line height of the text around it: the page's
        // 1.5 in the window, a card's `text-xs` 4/3 inside one.
        is AgentStepRow.Text -> {
            // One paragraph counts as its lines, as Chrome adds them up; anything with blocks as it measures.
            val paragraph = window && remember(row.text) { Markdown.parse(row.text).singleOrNull() is MarkdownBlock.Paragraph }
            val lines = if (paragraph) Modifier.cssLines(16.5f, max = 96.dp) else Modifier
            // Under no `select-none`, here or in a card's `select-text` details: the I-beam over its glyphs.
            TextCursorRegion {
                MiniMarkdown(row.text, maxHeight = 96.dp, size = 11f, lineHeightRatio = if (window) 1.5f else 4f / 3, modifier = modifier.then(lines))
            }
        }
        is AgentStepRow.Tool -> {
            val use = row.use
            val block = remember(use, row.result) { ToolBlock(use.toolUseId, use.name, use.input, result = row.result ?: use.result, bgStatus = use.bgStatus) }
            ToolCardView(block, completed = row.result != null, modifier, variant = ToolCardVariant.Window, nested = use.children)
        }
    }
}

/** How far the end of the list is below the screen; past the items on screen, as good as forever. */
private fun LazyListState.distanceToEnd(): Int {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return 0
    if (last.index < info.totalItemsCount - 1) return Int.MAX_VALUE
    return last.offset + last.size + info.afterContentPadding - info.viewportEndOffset
}

private suspend fun LazyListState.scrollToEnd() {
    val last = layoutInfo.totalItemsCount - 1
    if (last < 0) return
    if (layoutInfo.visibleItemsInfo.lastOrNull()?.index != last) scrollToItem(last)
    val remaining = distanceToEnd()
    if (remaining > 0) scrollBy(remaining.toFloat())
}
