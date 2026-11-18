package com.hienlh.ppm.chat

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.hienlh.ppm.design.ThemeShadow
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.protocol.bool
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.CursorText
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.LucideIcon
import com.hienlh.ppm.design.PpmFonts
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.TextCursorRegion
import com.hienlh.ppm.design.ThemeTokens
import com.hienlh.ppm.design.TruncatedText
import com.hienlh.ppm.design.WebCursor
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssScrollY
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.design.vector
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.protocol.ChatEvent
import com.hienlh.ppm.protocol.ToolOutcome
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.prettyString
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.BashPartial
import com.hienlh.ppm.state.SHELL_TOOLS
import com.hienlh.ppm.state.ToolBlock
import com.hienlh.ppm.state.agentCardPayload
import com.hienlh.ppm.state.isAgent
import com.hienlh.ppm.state.isAsyncAgentLaunchAck
import com.hienlh.ppm.state.truthy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.floor
import kotlinx.coroutines.flow.drop

/** `text-xs`: the cards' text, 12px on a 16px line. */
private val cardText = ppmText(12f, lineHeight = 16f)

/** `<pre class="font-mono">` at 12px. */
private val monoText = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f)

/** `text-[10px]` inside a card, on the line `text-xs` sets: 10 × 4/3. */
private val tinyText = ppmText(10f, lineHeight = 40f / 3)
private val tinyMono = ppmText(10f, kind = FontKind.MONO, lineHeight = 40f / 3)

/** `font-mono text-[11px]` on the card's line ratio. */
private val streamMono = ppmText(11f, kind = FontKind.MONO, lineHeight = 44f / 3)

/** Where a card is drawn: the chat, or an agent's session window, which expands an Agent card in place. */
enum class ToolCardVariant { Chat, Window }

/**
 * A tool call as the web's `ToolCard` draws it (src/web/components/chat/tool-cards.tsx): a tinted
 * chip, a one-line summary, the status on the right, details when expanded. [completed]: a call
 * without a result is over anyway, its turn having ended or a later edit of the file it read
 * having come back. In the chat an Agent/Task call is one row that opens its session; in the
 * session [ToolCardVariant.Window] it is a card like any other, its subagent's steps ([nested])
 * under its details — the window is already the live view of that work.
 */
@Composable
fun ToolCardView(tool: ToolBlock, completed: Boolean, modifier: Modifier = Modifier, variant: ToolCardVariant = ToolCardVariant.Chat, nested: List<ChatEvent>? = null) {
    val result = tool.result
    val isError = result?.isError ?: false
    val hasAnswers = tool.name == "AskUserQuestion" && truthy(tool.input["answers"])
    val wasApproved = tool.requestId != null && tool.approved != null
    // A backgrounded agent answers its call the moment it starts, then runs on past its turn:
    // only the status the server reports for it later says it is done.
    val bgStatus = if (tool.isAgent) tool.bgStatus else null
    val isBgAgent = tool.isAgent && (bgStatus != null || (result != null && !isError && isAsyncAgentLaunchAck(result.output)))
    val bgRunning = isBgAgent && bgStatus == null
    val bgFailed = bgStatus == "failed" || bgStatus == "stopped"
    val isDone = !bgRunning && (result != null || hasAnswers || wasApproved || completed)
    // The AI's tab tools get a card of their own, with an Open button beside the summary.
    val tabCall = if (tool.requestId == null) remember(tool.name, tool.input) { tabToolCall(tool.name, tool.input) } else null
    if (tabCall != null) {
        TabToolCard(tabCall, tool.toolUseId, result?.output, isError, isDone, modifier)
        return
    }
    // So do the session tools: the session they reached, its reply, and a way to open it.
    val collabCall = if (tool.requestId == null) remember(tool.name, tool.input) { sessionCollabToolCall(tool.name, tool.input) } else null
    if (collabCall != null) {
        SessionCollabToolCard(collabCall, result?.output, isError, isDone, modifier)
        return
    }
    // The vault's tools never show a locked value: a read is a count.
    val vaultCall = if (tool.requestId == null) remember(tool.name, tool.input) { vaultToolCall(tool.name, tool.input) } else null
    if (vaultCall != null) {
        VaultToolCard(vaultCall, tool.toolUseId, result?.output, isError, isDone, modifier)
        return
    }
    if (tool.isAgent && variant == ToolCardVariant.Chat) {
        val opener = LocalAgentSessionOpener.current
        AgentRow(tool, if (isError || bgFailed) Status.FAILED else if (isDone) Status.DONE else Status.RUNNING, bgRunning, modifier) {
            opener?.open?.invoke(agentCardPayload(tool, opener.identity, null), tool.children?.events.orEmpty())
        }
        return
    }
    val tokens = LocalPpmTokens.current
    val isEdit = tool.name == "Edit" || tool.name == "MultiEdit"
    val imagePath = remember(tool.name, tool.input, tool.requestId) { imageReadPath(tool) }
    // An edit opens on its diff, unless it failed and the diff was never applied; an image read on
    // the picture, which is the point of the card. Opened or closed by hand, it stays so.
    val openCards = LocalOpenCards.current
    val id = tool.toolUseId?.takeIf { openCards != null }
    var ownOpen by remember { mutableStateOf<Boolean?>(null) }
    val expanded = (if (id != null) openCards?.get(id) else ownOpen) ?: ((isEdit && !isError) || imagePath != null)
    val setExpanded: (Boolean) -> Unit = { open -> if (id != null) openCards?.put(id, open) else ownOpen = open }
    // An edit that fails shows nothing worth opening on: it closes as the failure comes in.
    var sawError by remember { mutableStateOf(isError) }
    LaunchedEffect(isError) {
        if (isError && !sawError && isEdit) setExpanded(false)
        sawError = isError
    }
    // A shell call still running shows what it has printed so far.
    val partial = if (result == null && tool.name in SHELL_TOOLS) tool.toolUseId?.let { LocalBashOutput.current[it] } else null
    val interactive = tool.name in setOf("AskUserQuestion", "ExitPlanMode") || tool.isAgent
    val shape = RoundedCornerShape(11.dp)
    // Named for "Show in chat" (`data-tool-ref`), and lit once it is jumped to (`[data-flash="card"]`):
    // the border the accent at 55%, a 1 px ring and a glow around it, in and out over 200 ms.
    val anchors = LocalEditAnchors.current
    val callId = tool.toolUseId
    if (anchors != null && callId != null) DisposableEffect(anchors, callId) { onDispose { anchors.cards.remove(callId) } }
    val lit by animateFloatAsState(if (anchors != null && callId != null && anchors.flash == callId) 1f else 0f, tween(200, easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)), label = "flash")
    val border = tokens.accent.opacity(0.55).mix(if (interactive) tokens.accentWashBorder else tokens.border, lit.toDouble())
    Column(
        modifier
            .fillMaxWidth()
            .then(if (anchors != null && callId != null) Modifier.onGloballyPositioned { anchors.cards[callId] = it } else Modifier)
            .then(
                if (lit > 0f) {
                    Modifier
                        .ppmShadow(ThemeShadow(0.0, 0.0, 22.0, -6.0, tokens.accent.opacity(0.5 * lit)), shape)
                        .ppmShadow(ThemeShadow(0.0, 0.0, 0.0, 1.0, tokens.accent.opacity(0.35 * lit)), shape)
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .background(tokens.panel.color)
            .cssBorder(border.color, shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .ppmClickable(pressedColor = tokens.panel2.opacity(0.4).color, hoverColor = tokens.panel2.opacity(0.4).color) { setExpanded(!expanded) }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ToolChip(ToolChipStyle.of(tool.name, tool.input, isError, tokens))
            ToolSummaryLine(tool.name, tool.input, Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (partial != null) BasicText("${lines(partial.lineCount)} streaming…", style = tinyText.copy(color = tokens.warning.color))
                if (!nested.isNullOrEmpty() && partial == null) BasicText(stepCount(nested.size), style = tinyMono.copy(color = tokens.text3.color))
                if (bgRunning) BasicText("running…", style = tinyText.copy(color = tokens.accent.color))
                // Codex's exit code for a shell call: its output can matter even when it failed.
                result?.exitCode?.let { code ->
                    BasicText("exit $code", style = tinyMono.copy(color = (if (code == 0) tokens.text3 else tokens.error).color))
                }
                StatusIcon(if (isError || bgFailed) Status.FAILED else if (isDone) Status.DONE else Status.RUNNING)
                PpmIcon(if (expanded) ProductIcon.ChevronDown else ProductIcon.ChevronRight, 12.dp, tokens.text3.color)
            }
        }
        if (expanded) {
            Column(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // `select-text`: the I-beam over the glyphs of what the card details; its buttons keep the arrow.
                TextCursorRegion {
                    ToolDetails(tool.name, tool.input, tool.toolUseId)
                    if (partial != null) StreamingBashOutput(partial)
                    if (!nested.isNullOrEmpty()) SubagentChildren(nested, window = false)
                    if (imagePath != null) ToolImagePreview(imagePath, tool.toolUseId)
                    // The preview names the file already, so the result's echo of it is left out.
                    if (result != null && showsResult(tool.name, result) && !(imagePath != null && !result.isError)) {
                        ToolResultView(tool.name, result.output)
                    }
                }
            }
        }
    }
}

/**
 * Whether each card is open, by call, kept by the transcript: the web's keeps every card mounted,
 * so one closed by hand stays closed after it scrolls away and back, and the lightbox leaves out a
 * closed card's picture, which has no `<img>` for it to collect. None, and each card keeps its own.
 */
internal val LocalOpenCards = staticCompositionLocalOf<SnapshotStateMap<String, Boolean>?> { null }

/** What each running shell call has printed so far, by call ([com.hienlh.ppm.state.ChatState.bashOutput]). */
internal val LocalBashOutput = compositionLocalOf<Map<String, BashPartial>> { emptyMap() }

private fun lines(n: Int) = if (n == 1) "1 line" else "$n lines"

/**
 * The output of a call still running (`StreamingBashOutput`): its last 200 lines, kept at the end
 * as more comes in unless scrolled more than 20 px up from it.
 */
@Composable
private fun StreamingBashOutput(output: BashPartial) {
    val tokens = LocalPpmTokens.current
    val scroll = rememberScrollState()
    val slack = with(LocalDensity.current) { 20.dp.toPx() }
    var scrolledUp by remember { mutableStateOf(false) }
    // Read where a scroll leaves it, as `onScroll` does: more output coming in moves nothing.
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.value }.drop(1).collect { scrolledUp = it < scroll.maxValue - slack }
    }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.maxValue }.collect { if (!scrolledUp && it != Int.MAX_VALUE) scroll.scrollTo(it) }
    }
    Column(Modifier.fillMaxWidth().cssBorder(tokens.border.color, sides = CssSides.Top).padding(top = 6.dp)) {
        Row(
            Modifier.padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PpmIcon(ProductIcon.Loader2, 12.dp, tokens.warning.color, spinning = true)
            CursorText("Output (${lines(output.lineCount)}, streaming...)", style = tinyText.copy(color = tokens.warning.color))
        }
        Box(Modifier.fillMaxWidth().heightIn(max = 240.dp).cssScrollY(scroll)) {
            Mono(output.tail, tokens.text3, breakAll = true, style = streamMono)
        }
    }
}

/**
 * A file change shows itself in the details, so its "updated successfully" is noise; an image
 * read comes back as a stand-in for the picture. Failures always show.
 */
@Composable
private fun showsResult(name: String, result: ToolOutcome): Boolean {
    val carriedImage = remember(result.output) { resultHasImagePlaceholder(result.output) }
    return result.isError || !(name in setOf("Edit", "MultiEdit", "Write", "NotebookEdit") || carriedImage)
}

/** The text an image block of a result is replaced with (`[image]`, `[image · 12KB]`), whole. */
private val IMAGE_PLACEHOLDER = Regex("""^\[image(?: · [\d.]+(?:B|KB|MB))?]$""")

/** Whether a result really carried an image (`resultHasImagePlaceholder` in shared/tool-result-content.ts). */
internal fun resultHasImagePlaceholder(output: String): Boolean {
    if (!output.startsWith("[")) return false
    val blocks = WireJson.parseOrNull(output) as? JsonArray ?: return false
    return blocks.any { it["type"].string == "text" && it["text"].string?.let(IMAGE_PLACEHOLDER::matches) == true }
}

/** Tools whose `file_path` names an image the card shows: Codex's ImageGen too, the picture it wrote being its point. */
private val IMAGE_PATH_TOOLS = setOf("Read", "ImageGen")

/** A path from the root, as `useBlobUrl` tells one: `/…`, or a Windows drive. */
private val ABSOLUTE_PATH = Regex("""^(/|[A-Za-z]:[/\\])""")

/**
 * `imageReadPath`: the image a call opened, or null when it is no picture the card can show. Only
 * an absolute path counts, as the only kind the raw-file route reads outside the project, and the
 * kind Read and ImageGen always give; an approval request is not a call, and shows none.
 */
internal fun imageReadPath(tool: ToolBlock): String? {
    if (tool.requestId != null || tool.name !in IMAGE_PATH_TOOLS) return null
    val path = tool.input["file_path"].string ?: return null
    if (!ABSOLUTE_PATH.containsMatchIn(path)) return null
    return if (isImageExtension(path)) path else null
}

internal fun stepCount(n: Int): String = if (n == 1) "1 step" else "$n steps"

// Chip and status

internal data class ToolChipStyle(
    val icon: ProductIcon,
    val background: Rgba,
    val foreground: Rgba,
    /** One of the few icons the web keeps on lucide, drawn instead of [icon]. */
    val lucide: LucideIcon? = null,
) {
    companion object {
        fun of(name: String, input: JsonElement, isError: Boolean, t: ThemeTokens): ToolChipStyle {
            val wash = ToolChipStyle(ProductIcon.Code, t.accentWash, t.accent)
            fun tinted(icon: ProductIcon, color: Rgba) = ToolChipStyle(icon, color.opacity(0.15), color)
            fun washed(icon: ProductIcon) = wash.copy(icon = icon)
            fun plain(icon: ProductIcon) = ToolChipStyle(icon, t.panel2, t.text2)
            if (isError && (name == "Bash" || name == "PowerShell")) return tinted(ProductIcon.Terminal, t.error)
            if ((name == "Agent" || name == "Task") && !input["name"].string.isNullOrBlank()) return tinted(ProductIcon.Users, t.accent2)
            dbToolOf(name)?.let { tool ->
                return if (tool == DB_EXECUTE_TOOL) tinted(ProductIcon.DatabaseZap, t.warning) else tinted(ProductIcon.Database, t.info)
            }
            deviceToolOf(name)?.let { tool ->
                return when (tool) {
                    OPEN_URL_TOOL -> tinted(ProductIcon.Globe, t.info)
                    OPEN_BRANCH_REVIEW_TOOL -> tinted(ProductIcon.GitBranch, t.info).copy(lucide = LucideIcon.FileDiff)
                    else -> plain(ProductIcon.Terminal)
                }
            }
            return when (name) {
                "Read", "Glob", "LS" -> washed(ProductIcon.FileSearch)
                "Grep", "Search", "WebSearch", "ToolSearch" -> washed(ProductIcon.Search)
                "WebFetch", "Fetch" -> tinted(ProductIcon.Globe, t.info)
                "Edit", "MultiEdit", "NotebookEdit" -> tinted(ProductIcon.FilePen, t.accent2)
                "Write" -> tinted(ProductIcon.FilePlus2, t.success)
                "Bash", "PowerShell" -> plain(ProductIcon.Terminal)
                "TodoWrite" -> tinted(ProductIcon.ListChecks, t.warning)
                "Task", "Agent" -> washed(ProductIcon.Bot)
                "SendMessage" -> tinted(ProductIcon.Send, t.info)
                "AskUserQuestion" -> washed(ProductIcon.CircleHelp)
                "ExitPlanMode" -> washed(ProductIcon.ClipboardList)
                "Skill" -> tinted(ProductIcon.Sparkles, t.accent2)
                "ImageGen" -> tinted(ProductIcon.ImagePlus, t.accent2)
                else -> plain(ProductIcon.Code)
            }
        }
    }
}

/** 24×24, radius 7, a 14px glyph. */
@Composable
internal fun ToolChip(style: ToolChipStyle) {
    Box(Modifier.size(24.dp).background(style.background.color, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
        if (style.lucide != null) PpmIcon(vector = style.lucide.vector, size = 14.dp, tint = style.foreground.color) else PpmIcon(style.icon, 14.dp, style.foreground.color)
    }
}

internal enum class Status { RUNNING, DONE, FAILED }

@Composable
internal fun StatusIcon(state: Status) {
    val tokens = LocalPpmTokens.current
    when (state) {
        Status.FAILED -> PpmIcon(ProductIcon.XCircle, 14.dp, tokens.error.color)
        Status.DONE -> PpmIcon(ProductIcon.CheckCircle2, 14.dp, tokens.success.color)
        Status.RUNNING -> PpmIcon(ProductIcon.Loader2, 14.dp, tokens.accent.color, spinning = true)
    }
}

// Summary

/**
 * The header's one line: an icon, the tool's name, then what it acted on. [detailIcon] opens the
 * detail and [endIcon] follows the line, as SendMessage's arrow and clock do. [parts] follow the
 * name as they are, for a line of several runs (PPM's own tools).
 */
internal data class ToolSummaryText(
    val icon: ProductIcon? = null,
    val name: String,
    val detail: String,
    val monoDetail: Boolean = false,
    val detailIcon: ProductIcon? = null,
    val endIcon: ProductIcon? = null,
    val parts: List<SummaryPart> = emptyList(),
)

/** A run of a summary line: in the text's colour, or the detail's when [subtle]. */
internal data class SummaryPart(val text: String, val subtle: Boolean = false, val mono: Boolean = false)

/** `ToolSummary` in tool-cards.tsx. */
internal fun toolSummary(name: String, input: JsonElement): ToolSummaryText {
    fun s(key: String): String = jsString(input[key])
    dbToolCall(name, input)?.let { return dbToolSummary(it) }
    deviceToolCall(name, input)?.let { return deviceToolSummary(it) }
    return when (name) {
        "Read", "Write", "Edit", "MultiEdit", "NotebookEdit" -> ToolSummaryText(name = name, detail = basename(s("file_path")))
        "Bash", "PowerShell" -> {
            val description = s("description")
            ToolSummaryText(name = name, detail = truncate(description.ifEmpty { s("command") }, 60), monoDetail = description.isEmpty())
        }
        "Glob" -> ToolSummaryText(name = name, detail = s("pattern"), monoDetail = true)
        "Grep" -> ToolSummaryText(name = name, detail = truncate(s("pattern"), 40), monoDetail = true)
        "WebSearch", "ToolSearch" -> ToolSummaryText(ProductIcon.Search, name, truncate(s("query"), 50))
        "WebFetch" -> ToolSummaryText(ProductIcon.Globe, name, truncate(s("url"), 50))
        "ImageGen" -> {
            val firstLine = s("prompt").split("\n").firstOrNull { it.isNotBlank() } ?: ""
            ToolSummaryText(ProductIcon.ImagePlus, "Image", truncate(firstLine.ifEmpty { basename(s("file_path")) }, 50))
        }
        "TodoWrite" -> {
            val todos = input["todos"].array.orEmpty()
            val done = todos.count { it["status"].string == "completed" }
            ToolSummaryText(ProductIcon.ListTodo, name, "$done/${todos.size} done")
        }
        "AskUserQuestion" -> {
            val count = input["questions"].array?.size ?: 0
            ToolSummaryText(name = name, detail = "$count question${if (count == 1) "" else "s"}${if (truthy(input["answers"])) " ✓" else ""}")
        }
        "ScheduleWakeup" -> {
            val reason = s("reason")
            ToolSummaryText(ProductIcon.Clock, name, "in ${formatDelay(s("delaySeconds").toDoubleOrNull() ?: 0.0)}${if (reason.isEmpty()) "" else " — ${truncate(reason, 50)}"}")
        }
        "Agent", "Task" -> {
            // The handle leads when there is one: it names the teammate, and SendMessage reaches it by it.
            val handle = input["name"].string?.trim()?.ifEmpty { null }
            val task = truncate(s("description").ifEmpty { s("prompt") }, if (handle != null) 44 else 60)
            if (handle == null) {
                ToolSummaryText(ProductIcon.Bot, name, task)
            } else {
                ToolSummaryText(name = "$name ", detail = "", parts = listOf(SummaryPart(handle), SummaryPart(" · $task", subtle = true)))
            }
        }
        "TaskCreate" -> ToolSummaryText(ProductIcon.ListTodo, name, truncate(s("subject"), 60))
        "TaskUpdate" -> ToolSummaryText(ProductIcon.ListTodo, name, "#${s("taskId")} → ${s("status")}")
        "TaskStop" -> ToolSummaryText(ProductIcon.ListTodo, name, "#${s("taskId")} stopped")
        "SendMessage" -> {
            // Who it went to and the author's label for it, else the line the recipient previews.
            val message = NormalizedSendMessage.of(input)
            val raw = message.summary ?: message.firstLine.ifEmpty { if (message.protocol != null) message.kind else "" }
            val label = if (raw.length > 72) raw.take(72) + "…" else raw
            ToolSummaryText(
                name = name,
                detail = message.to.ifEmpty { "unknown" } + if (label.isEmpty()) "" else " · $label",
                detailIcon = ProductIcon.ArrowRight,
                endIcon = if (message.notifyWhenIdle) ProductIcon.Clock else null,
            )
        }
        else -> ToolSummaryText(name = name, detail = "")
    }
}

/** `DbToolSummary`: what the tool does, on which connection, and its reason or first SQL line. */
internal fun dbToolSummary(call: DbToolCall): ToolSummaryText {
    val firstLine = call.sql.split("\n").firstOrNull { it.isNotBlank() }?.trim() ?: ""
    val detail = if (call.tool == DB_EXECUTE_TOOL && call.reason != null) call.reason else firstLine
    return ToolSummaryText(
        name = DB_TOOL_LABELS.getValue(call.tool) + " ",
        detail = "",
        parts = listOfNotNull(
            SummaryPart(call.connection + (call.database?.let { " / $it" } ?: "")),
            if (detail.isEmpty()) null else SummaryPart(" · ${truncate(detail, 60)}", subtle = true, mono = detail != call.reason),
        ),
    )
}

private val HTTP_SCHEME = Regex("^https?://")

/** `DeviceToolSummary`: what the tool did, and to what. */
internal fun deviceToolSummary(call: DeviceToolCall): ToolSummaryText = when (call) {
    is DeviceToolCall.OpenUrl -> ToolSummaryText(
        name = "Open app ",
        detail = "",
        parts = listOf(SummaryPart(truncate(call.url.replaceFirst(HTTP_SCHEME, ""), 60), subtle = true, mono = true)),
    )
    is DeviceToolCall.OpenBranchReview -> ToolSummaryText(
        name = "Branch Review ",
        detail = "",
        parts = listOf(SummaryPart("${truncate(call.head ?: "current branch", 40)} ← ${truncate(call.base ?: "main", 40)}", subtle = true, mono = true)),
    )
    is DeviceToolCall.RunInTerminal -> ToolSummaryText(
        name = "Type in terminal ",
        detail = "",
        parts = listOf(SummaryPart(truncate(call.command, 60), subtle = true, mono = true)),
    )
    is DeviceToolCall.ReadTerminal -> ToolSummaryText(
        name = "Read terminal",
        detail = "",
        parts = listOfNotNull(
            call.terminal?.let { SummaryPart(" $it", subtle = true, mono = true) },
            call.lines?.let { SummaryPart(" · $it lines", subtle = true) },
        ),
    )
}

/** `formatDelay`: 1800 → "30m", 90 → "1m 30s". */
internal fun formatDelay(seconds: Double): String {
    if (!seconds.isFinite() || seconds <= 0) return "0s"
    val whole = seconds.toLong()
    val h = whole / 3600
    val m = (whole % 3600) / 60
    val sec = Math.round(seconds % 60)
    val parts = mutableListOf<String>()
    if (h > 0) parts += "${h}h"
    if (m > 0) parts += "${m}m"
    if (sec > 0 || parts.isEmpty()) parts += "${sec}s"
    return parts.joinToString(" ")
}

@Composable
private fun ToolSummaryLine(name: String, input: JsonElement, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    val summary = remember(name, input) { toolSummary(name, input) }
    // `<Icon className="size-3 inline" /> {name}`: icons sit in the line, a space from the words.
    val text = buildAnnotatedString {
        if (summary.icon != null) {
            appendInlineContent("icon")
            append(" ")
        }
        append(summary.name)
        if (summary.detail.isNotEmpty() || summary.detailIcon != null) {
            append(" ")
            val detail = if (summary.monoDetail) {
                SpanStyle(color = tokens.text3.color, fontFamily = PpmFonts.mono, fontWeight = FontWeight.Normal)
            } else {
                SpanStyle(color = tokens.text3.color)
            }
            withStyle(detail) {
                if (summary.detailIcon != null) {
                    appendInlineContent("detailIcon")
                    append(" ")
                }
                append(summary.detail)
            }
        }
        // A mono run keeps the weight the mono face has: the web loads it at 400 only.
        for (part in summary.parts) {
            val color = (if (part.subtle) tokens.text3 else tokens.text).color
            val style = if (part.mono) SpanStyle(color = color, fontFamily = PpmFonts.mono, fontWeight = FontWeight.Normal) else SpanStyle(color = color)
            withStyle(style) { append(part.text) }
        }
        if (summary.endIcon != null) appendInlineContent("endIcon")
    }
    val inline = buildMap {
        summary.icon?.let { put("icon", inlineIcon(it, tokens.text.color)) }
        summary.detailIcon?.let { put("detailIcon", inlineIcon(it, tokens.text3.color)) }
        // `ml-1`.
        summary.endIcon?.let { put("endIcon", inlineIcon(it, tokens.text3.color, marginStart = 4f)) }
    }
    // `truncate`: the "…" in the line's own colour and face, whatever run it cuts.
    TruncatedText(text, ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color), modifier, inline)
}

/** A `size-3 inline` icon in a line of card text, [marginStart] after what comes before it. */
private fun inlineIcon(icon: ProductIcon, color: Color, marginStart: Float = 0f, end: Boolean = true) = InlineTextContent(
    Placeholder((12f + marginStart).sp, 12.sp, PlaceholderVerticalAlign.Center),
) {
    Box(Modifier.fillMaxSize(), contentAlignment = if (end) Alignment.CenterEnd else Alignment.CenterStart) {
        PpmIcon(icon, with(LocalDensity.current) { 12.sp.toDp() }, color, Modifier.offset(y = inlineIconDrop()))
    }
}

/**
 * How far under the middle of a 12px Geist line of 16 an inline icon's middle sits. Preflight gives
 * an `svg` `vertical-align: middle`: half an x-height above the baseline, which Chrome places from
 * the font's ascent and descent rounded to device pixels.
 */
@Composable
private fun inlineIconDrop(): Dp = with(LocalDensity.current) {
    val px = 12.sp.toPx()
    val line = 16.sp.toPx()
    val (ascent, descent) = PpmFonts.chromeAscentDescent(px, PpmFonts.SANS_ASCENT, PpmFonts.SANS_DESCENT)
    val baseline = floor((line - ascent - descent) / 2) + ascent
    (baseline - PpmFonts.SANS_X_HEIGHT * px / 2 - line / 2).toDp()
}

// Details

/** What an expanded card shows about the call itself. */
@Composable
private fun ToolDetails(name: String, input: JsonElement, toolUseId: String? = null) {
    val tokens = LocalPpmTokens.current
    fun s(key: String) = jsString(input[key])
    val dbCall = remember(name, input) { dbToolCall(name, input) }
    if (dbCall != null) return DbToolDetails(dbCall)
    val deviceCall = remember(name, input) { deviceToolCall(name, input) }
    if (deviceCall != null) return DeviceToolDetails(deviceCall)
    when (name) {
        "Bash", "PowerShell" -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (s("description").isNotEmpty()) {
                CursorText(s("description"), style = ppmText(12f, lineHeight = 16f, italic = true).copy(color = tokens.text3.color))
            }
            Mono(s("command"), tokens.text2, breakAll = true)
        }
        "Read", "Write", "Edit", "MultiEdit", "NotebookEdit" -> FileDetails(name, input, toolUseId)
        "Glob" -> Mono(s("pattern") + if (s("path").isEmpty()) "" else " in ${s("path")}", tokens.text2)
        "Grep" -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Mono("/${s("pattern")}/", tokens.text2)
            if (s("path").isNotEmpty()) CursorText("in ${s("path")}", style = cardText.copy(color = tokens.text3.color))
        }
        "TodoWrite" -> TodoList(input["todos"].array.orEmpty())
        "WebFetch" -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val url = s("url")
            if (url.isNotEmpty()) {
                val uriHandler = LocalUriHandler.current
                // `hover:underline`, at once.
                val hover = rememberHover(instant = true)
                Row(
                    Modifier
                        .pointerHoverIcon(WebCursor.Pointer, overrideDescendants = true)
                        .ppmClickable(pressedColor = Color.Transparent, source = hover.source) { runCatching { uriHandler.openUri(url) } },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PpmIcon(ProductIcon.Globe, 12.dp, tokens.accent.color)
                    BasicText(
                        remember(url) { if (BREAK in url) url else breakAnywhere(url) },
                        style = monoText.copy(color = tokens.accent.color, textDecoration = if (hover.lit > 0f) TextDecoration.Underline else null),
                    )
                }
            }
            if (s("prompt").isNotEmpty()) CursorText(truncate(s("prompt"), 100), style = cardText.copy(color = tokens.text3.color))
        }
        "SendMessage" -> SendMessageDetails(input)
        "Agent", "Task" -> AgentDetails(input)
        "ToolSearch" -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Mono(s("query"), tokens.text2)
            if (truthy(input["max_results"])) CursorText("Max results: ${s("max_results")}", style = cardText.copy(color = tokens.text3.color))
        }
        "AskUserQuestion" -> AnsweredQuestions(input)
        "ScheduleWakeup" -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // `Number(delaySeconds) || 0`.
            val seconds = s("delaySeconds").trim().ifEmpty { "0" }.toDoubleOrNull()?.takeIf { !it.isNaN() } ?: 0.0
            val line = buildAnnotatedString {
                appendInlineContent("clock")
                append("Wake in ")
                withStyle(SpanStyle(color = tokens.text.color, fontWeight = FontWeight.Medium)) { append(formatDelay(seconds)) }
                withStyle(SpanStyle(color = tokens.text3.color)) { append(" (${jsNumberText(seconds)}s)") }
            }
            // `mr-1`.
            val clock = inlineIcon(ProductIcon.Clock, tokens.text2.color, marginStart = 4f, end = false)
            CursorText(line, style = cardText.copy(color = tokens.text2.color), inlineContent = mapOf("clock" to clock))
            if (truthy(input["reason"])) CursorText(s("reason"), style = ppmText(12f, lineHeight = 16f, italic = true).copy(color = tokens.text2.color))
            if (truthy(input["prompt"])) {
                Column {
                    // `mb-0.5`.
                    CursorText("Prompt on wake", Modifier.padding(bottom = 2.dp), tinyText.copy(color = tokens.text3.color))
                    MiniMarkdown(s("prompt"), maxHeight = 192.dp)
                }
            }
        }
        "TaskCreate" -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CursorText(s("subject"), style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color))
            if (truthy(input["description"])) CursorText(s("description"), style = cardText.copy(color = tokens.text3.color))
            if (truthy(input["activeForm"])) CursorText(s("activeForm"), style = ppmText(12f, lineHeight = 16f, italic = true).copy(color = tokens.text3.color))
        }
        "TaskUpdate", "TaskStop" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CursorText("#${s("taskId")}", style = monoText.copy(color = tokens.text2.color))
            TaskStatusBadge(if (name == "TaskStop") "stopped" else s("status"))
        }
        "ImageGen" -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (truthy(input["prompt"])) CursorText(s("prompt"), style = cardText.copy(color = tokens.text2.color))
            if (input["transparentBackground"].bool == true) CursorText("Transparent background", style = cardText.copy(color = tokens.text3.color))
        }
        else -> Mono(if (input is JsonNull) "null" else input.prettyString, tokens.text2, breakAll = true)
    }
}

/** `DbToolDetails`: the connection, the reason the user read, the SQL, and the row count it expects. */
@Composable
private fun DbToolDetails(call: DbToolCall) {
    val tokens = LocalPpmTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val middle = Modifier.align(Alignment.CenterVertically)
            CursorText("Connection", middle, cardText.copy(color = tokens.text3.color))
            CursorText(call.connection, middle, ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color))
            call.database?.let { database ->
                val line = buildAnnotatedString {
                    append("database ")
                    withStyle(SpanStyle(color = tokens.text2.color, fontFamily = PpmFonts.mono)) { append(database) }
                }
                CursorText(line, middle, cardText.copy(color = tokens.text3.color))
            }
        }
        call.reason?.let { CursorText(it, style = ppmText(12f, lineHeight = 16f, italic = true).copy(color = tokens.text2.color)) }
        Box(Modifier.heightIn(max = 240.dp).cssScrollY()) {
            Mono(call.sql, tokens.text2, breakAll = true)
        }
        call.expectedRows?.let { rows ->
            val line = buildAnnotatedString {
                append("Expected rows changed: ")
                withStyle(SpanStyle(color = tokens.text2.color)) { append(jsNumberText(rows)) }
            }
            CursorText(line, style = cardText.copy(color = tokens.text3.color))
        }
    }
}

/** `DeviceToolDetails`: the address, the refs reviewed, the terminal read, or the command typed and where. */
@Composable
private fun DeviceToolDetails(call: DeviceToolCall) {
    val tokens = LocalPpmTokens.current
    when (call) {
        is DeviceToolCall.OpenUrl -> Mono(call.url, tokens.text2, breakAll = true)
        is DeviceToolCall.OpenBranchReview -> {
            val line = buildAnnotatedString {
                fun ref(text: String) = withStyle(SpanStyle(color = tokens.text2.color, fontFamily = PpmFonts.mono)) {
                    append(if (BREAK in text) text else breakAnywhere(text))
                }
                ref(call.head ?: "The branch checked out")
                append(" against ")
                ref(call.base ?: "main or master")
                call.repo?.let {
                    append(" in ")
                    ref(it)
                }
            }
            CursorText(line, style = cardText.copy(color = tokens.text3.color))
        }
        is DeviceToolCall.RunInTerminal -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Mono(call.command, tokens.text2, breakAll = true)
            call.cwd?.let { cwd ->
                val line = buildAnnotatedString {
                    append("in ")
                    withStyle(SpanStyle(color = tokens.text2.color, fontFamily = PpmFonts.mono)) {
                        append(if (BREAK in cwd) cwd else breakAnywhere(cwd))
                    }
                }
                CursorText(line, style = cardText.copy(color = tokens.text3.color))
            }
        }
        is DeviceToolCall.ReadTerminal -> {
            val line = buildAnnotatedString {
                if (call.terminal != null) {
                    append("Terminal ")
                    withStyle(SpanStyle(color = tokens.text2.color, fontFamily = PpmFonts.mono)) { append(call.terminal) }
                } else {
                    append("The chat's terminals")
                }
                call.lines?.let { append(", last $it lines") }
            }
            CursorText(line, style = cardText.copy(color = tokens.text3.color))
        }
    }
}

@Composable
private fun FileDetails(name: String, input: JsonElement, toolUseId: String?) {
    val tokens = LocalPpmTokens.current
    fun s(key: String) = input[key].string ?: ""
    val path = s("notebook_path").ifEmpty { s("file_path") }
    val hasEditDiff = name == "Edit" && (s("old_string").isNotEmpty() || s("new_string").isNotEmpty())
    val tabs = LocalChatTabs.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // `flex items-start gap-3`: the path opens the file, and an edit's diff opens in a tab of its own.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FileLink(path, ProductIcon.ExternalLink, tokens.text2, monoText, breakAll = true, Modifier.weight(1f, fill = false)) { tabs?.openFile(path) }
            if (hasEditDiff) {
                FileLink("View Diff", ProductIcon.Columns2, tokens.text3, cardText, breakAll = false) { tabs?.openDiff(path, s("old_string"), s("new_string")) }
            }
        }
        // Each edit named `<call>-<index>` for "Show in chat" (`data-edit-ref`).
        if (hasEditDiff) Box(Modifier.editAnchor(toolUseId?.let { "$it-0" })) { EditDiffView(s("old_string"), s("new_string")) }
        if (name == "MultiEdit") {
            val edits = input["edits"].array.orEmpty()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                edits.forEachIndexed { index, edit ->
                    Column(Modifier.editAnchor(toolUseId?.let { "$it-$index" }), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        CursorText("Edit ${index + 1}", style = tinyText.copy(color = tokens.text3.color))
                        EditDiffView(edit["old_string"].string ?: "", edit["new_string"].string ?: "")
                    }
                }
            }
        }
        if (name == "Write" && s("content").isNotEmpty()) {
            Box(Modifier.editAnchor(toolUseId?.let { "$it-0" }).heightIn(max = 128.dp).cssScrollY()) {
                Mono(truncate(s("content"), 300), tokens.text3)
            }
        }
    }
}

/**
 * A text button with an icon before it (`flex items-center gap-1`, the icon `size-3`), lit in the
 * accent and underlined under a mouse (`hover:text-primary hover:underline`). [breakAll]: it breaks
 * anywhere, as a path does; else it keeps to one line (`whitespace-nowrap`).
 */
@Composable
private fun FileLink(label: String, icon: ProductIcon, color: Rgba, style: TextStyle, breakAll: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover(instant = true)
    val lit = hover.lit > 0f
    val tint = (if (lit) tokens.accent else color).color
    Row(
        modifier.ppmClickable(pressedColor = tokens.surfaceHover.color, source = hover.source, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PpmIcon(icon, 12.dp, tint)
        BasicText(
            if (breakAll) remember(label) { if (BREAK in label) label else breakAnywhere(label) } else label,
            style = style.copy(color = tint, textDecoration = if (lit) TextDecoration.Underline else null),
            softWrap = breakAll,
            maxLines = if (breakAll) Int.MAX_VALUE else 1,
        )
    }
}

/**
 * `<pre class="font-mono whitespace-pre-wrap">` at 12px, `break-all` when [breakAll]: a line may
 * then end inside a word, wherever Chrome would end it, and a copy comes out as [text] was but
 * for its tabs, which [preText] lays out as spaces.
 */
@Composable
internal fun Mono(text: String, color: Rgba, modifier: Modifier = Modifier, breakAll: Boolean = false, style: TextStyle = monoText) {
    // A text that holds a U+200B of its own keeps the line breaker's breaks, so a copy can keep it.
    val breaking = breakAll && BREAK !in text
    // A `<pre>` ends its last line at a final newline; Compose would start an empty one after it.
    val laid = text.removeSuffix("\n")
    val shown = remember(laid, breaking) { if (breaking || '\t' in laid) preText(laid, breaking) else AnnotatedString(laid) }
    WithoutBreaksOnCopy(breaking) {
        SelectionContainer(modifier.fillMaxWidth()) {
            CursorText(shown, style = style.copy(color = color.color))
        }
    }
}

@Composable
private fun TodoList(todos: List<JsonElement>) {
    val tokens = LocalPpmTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (todo in todos) {
            val status = todo["status"].string ?: ""
            val done = status == "completed"
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val (glyph, color) = when (status) {
                    "completed" -> "✓" to tokens.success
                    "in_progress" -> "▶" to tokens.warning
                    else -> "○" to tokens.text3
                }
                // `items-start` and the mark's `mt-0.5`: it sits 2 px below the text, and each row is 2 px taller for it.
                CursorText(glyph, Modifier.padding(top = 2.dp), cardText.copy(color = color.color))
                CursorText(
                    todo["content"].string ?: "",
                    style = cardText.copy(
                        color = (if (done) tokens.text3 else tokens.text2).color,
                        textDecoration = if (done) TextDecoration.LineThrough else null,
                    ),
                )
            }
        }
    }
}

/** An `AskUserQuestion` call after the fact: each question, its options, the answer. */
@Composable
private fun AnsweredQuestions(input: JsonElement) {
    val tokens = LocalPpmTokens.current
    val questions = input["questions"].array.orEmpty()
    val answers = input["answers"]
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (q in questions) {
            val question = q["question"].string ?: ""
            val header = q["header"].string
            // Keyed by Codex's id when the question has one, by its text otherwise.
            val answer = answers[q["id"].string ?: question].string ?: answers[question].string ?: ""
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                CursorText(
                    (header?.let { "$it: " } ?: "") + question,
                    style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (option in q["options"].array.orEmpty()) {
                        val label = option["label"].string ?: ""
                        val selected = answer.split(", ").contains(label)
                        val shape = RoundedCornerShape(4.dp)
                        CursorText(
                            label,
                            Modifier
                                .background((if (selected) tokens.accent.opacity(0.2) else Rgba(0.0, 0.0, 0.0, 0.0)).color, shape)
                                .cssBorder((if (selected) tokens.accent else tokens.border).color, shape)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            cardText.copy(color = (if (selected) tokens.text else tokens.text3).color),
                        )
                    }
                }
                if (answer.isNotEmpty()) CursorText("Answer: $answer", style = cardText.copy(color = tokens.text.color))
                // Each option's preview stays readable after the answer: it is often the plan the answer agreed to.
                for (option in q["options"].array.orEmpty()) {
                    val preview = option["preview"].string?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    AnsweredPreview(option["label"].string ?: "", preview)
                }
            }
        }
    }
}

/** `details` of an answered option's preview: "Preview · label", opening on a `max-h-80` monospace box. */
@Composable
private fun AnsweredPreview(label: String, preview: String) {
    val tokens = LocalPpmTokens.current
    var open by remember(preview) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.ppmClickable(pressedColor = Color.Transparent) { open = !open },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PpmIcon(if (open) ProductIcon.ChevronDown else ProductIcon.ChevronRight, 12.dp, tokens.text3.color)
            BasicText("Preview · $label", style = cardText.copy(color = tokens.text3.color))
        }
        if (open) {
            val shape = RoundedCornerShape(4.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .background(tokens.bgSolid.color, shape)
                    .cssBorder(tokens.border.color, shape)
                    .cssScrollY(),
            ) {
                SelectionContainer {
                    BasicText(
                        preview,
                        Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        ppmText(11f, kind = FontKind.MONO, lineHeight = 11f * 1.5f).copy(color = tokens.text.color),
                    )
                }
            }
        }
    }
}

/** A Task card's status pill (`TaskStatusBadge`): the todo list's colours, stopped and the rest neutral. */
@Composable
private fun TaskStatusBadge(status: String) {
    val tokens = LocalPpmTokens.current
    val (text, border, fill) = when (status) {
        "completed" -> Triple(tokens.success, tokens.success.opacity(0.3), tokens.success.opacity(0.1))
        "in_progress" -> Triple(tokens.warning, tokens.warning.opacity(0.3), tokens.warning.opacity(0.1))
        // `bg-surface` is the panel.
        else -> Triple(tokens.text3, tokens.border, tokens.panel)
    }
    val shape = RoundedCornerShape(4.dp)
    CursorText(
        status,
        Modifier.background(fill.color, shape).cssBorder(border.color, shape).padding(horizontal = 6.dp, vertical = 2.dp),
        tinyText.copy(color = text.color),
    )
}

/**
 * `MiniMarkdown`: markdown at the text size around it — a card's 12px unless told otherwise — in
 * the secondary colour, scrolling past [maxHeight].
 */
@Composable
internal fun MiniMarkdown(content: String, maxHeight: Dp, size: Float = 12f, lineHeightRatio: Float = 16f / 12f, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    Box(modifier.fillMaxWidth().heightIn(max = maxHeight).cssScrollY()) {
        MarkdownView(content, style = MarkdownStyle(size = size, lineHeightRatio = lineHeightRatio, color = tokens.text2))
    }
}

/** An Agent/Task call as the session window details it: the teammate's name, the task, its type and prompt. */
@Composable
private fun AgentDetails(input: JsonElement) {
    val tokens = LocalPpmTokens.current
    fun s(key: String) = jsString(input[key])
    val handle = input["name"].string?.trim()?.ifEmpty { null }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (handle != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val middle = Modifier.align(Alignment.CenterVertically)
                CursorText("Name", middle, cardText.copy(color = tokens.text3.color))
                CursorText(handle, middle, ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color))
                Badge("teammate", tokens.accent2.opacity(0.15), tokens.accent2, middle)
                if (input["isolation"].string == "worktree") Badge("worktree", tokens.panel2, tokens.text3, middle)
            }
        }
        if (truthy(input["description"])) CursorText(s("description"), style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text2.color))
        if (truthy(input["subagent_type"])) CursorText("Type: ${s("subagent_type")}", style = cardText.copy(color = tokens.text3.color))
        if (truthy(input["prompt"])) MiniMarkdown(s("prompt"), maxHeight = 192.dp)
    }
}

/** `inline-block rounded px-1.5 py-0.5 text-[10px]`. */
@Composable
private fun Badge(label: String, fill: Rgba, text: Rgba, modifier: Modifier = Modifier) {
    CursorText(
        label,
        modifier.background(fill.color, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        tinyText.copy(color = text.color),
    )
}

/** A badge for a message kind (`KindBadge`); prose gets none. */
@Composable
private fun KindBadge(kind: String) {
    val tokens = LocalPpmTokens.current
    val badge = TEAM_BADGES[kind]
    val (fill, text) = badge?.second?.colors(tokens) ?: (tokens.panel2 to tokens.text3)
    CursorText(
        badge?.first ?: kind,
        Modifier.background(fill.color, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        tinyText.copy(color = text.color),
    )
}

/** `SendMessageDetails`: who it went to, then the message itself. */
@Composable
private fun SendMessageDetails(input: JsonElement) {
    val tokens = LocalPpmTokens.current
    val message = remember(input) { NormalizedSendMessage.of(input) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val middle = Modifier.align(Alignment.CenterVertically)
            CursorText("To", middle, cardText.copy(color = tokens.text3.color))
            CursorText(message.to.ifEmpty { "unknown" }, middle, ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color))
            if (message.kind != "message") Box(middle) { KindBadge(message.kind) }
            if (message.notifyWhenIdle) {
                Row(
                    middle.background(tokens.panel2.color, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PpmIcon(ProductIcon.Clock, 12.dp, tokens.text3.color)
                    CursorText("notify when idle", style = tinyText.copy(color = tokens.text3.color))
                }
            }
        }
        // The summary is in the header already.
        if (message.text.isNotEmpty()) MiniMarkdown(message.text, maxHeight = 160.dp)
        message.protocol?.let { protocol ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                protocol.approve?.let { approve ->
                    val line = buildAnnotatedString {
                        append(if (approve) "Approved" else "Rejected")
                        if (!protocol.requestId.isNullOrEmpty()) {
                            withStyle(SpanStyle(color = tokens.text3.color, fontFamily = PpmFonts.mono)) { append(" · ${protocol.requestId}") }
                        }
                    }
                    CursorText(line, style = cardText.copy(color = tokens.text2.color))
                }
                for (note in listOf(protocol.reason, protocol.feedback)) {
                    if (!note.isNullOrEmpty()) CursorText(note, style = cardText.copy(color = tokens.text3.color))
                }
            }
        }
        if (message.extras.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for ((key, value) in message.extras) {
                    val line = buildAnnotatedString {
                        withStyle(SpanStyle(fontFamily = PpmFonts.mono)) { append(key) }
                        append(": $value")
                    }
                    CursorText(line, style = cardText.copy(color = tokens.text3.color))
                }
            }
        }
    }
}

// Output

/**
 * A result as the web shows it (`ToolResultView`): SendMessage's delivery line, an agent's answer
 * as markdown with its raw form a tap away, else the raw output.
 */
@Composable
private fun ToolResultView(name: String, output: String) {
    val outcome = remember(name, output) { if (name == "SendMessage") SendMessageOutcome.parse(output) else null }
    val agentText = remember(name, output) { if (name == "Agent" || name == "Task") agentResultText(output) else null }
    when {
        outcome != null -> SendMessageOutcomeView(output, outcome)
        agentText != null -> AgentResultView(output, agentText)
        else -> CollapsibleOutput(output)
    }
}

/**
 * What an agent answered: the text blocks of the SDK's `[{type:"text",text}]` joined by a blank
 * line, a JSON string, or plain text that does not open like such an array. Null for anything
 * else, which shows raw.
 */
internal fun agentResultText(output: String): String? {
    // A bare word is a literal to the tree parser, where `JSON.parse` throws.
    val parsed = WireJson.parseOrNull(output)?.takeUnless { it is JsonPrimitive && !it.isString && !it.isJsonLiteral() }
        ?: return output.takeIf { it.isNotEmpty() && !it.startsWith("[{") }
    if (parsed is JsonArray) {
        val texts = parsed.filter { it["type"].string == "text" && !it["text"].string.isNullOrEmpty() }.joinToString("\n\n") { it["text"].string!! }
        if (texts.isNotEmpty()) return texts
    }
    return (parsed as? JsonPrimitive)?.takeIf { it.isString }?.content
}

private val JSON_NUMBER = Regex("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?")

private fun JsonPrimitive.isJsonLiteral() = content == "true" || content == "false" || content == "null" || JSON_NUMBER.matches(content.trim())

/** An agent's answer in markdown up to 240dp, the raw output behind "Show raw". */
@Composable
private fun AgentResultView(output: String, text: String) {
    val tokens = LocalPpmTokens.current
    var showRaw by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().cssBorder(tokens.border.color, sides = CssSides.Top)) {
        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MiniMarkdown(text, maxHeight = 240.dp)
            OutputToggle(if (showRaw) "Hide raw" else "Show raw", ProductIcon.Code) { showRaw = !showRaw }
            if (showRaw) {
                Box(Modifier.heightIn(max = 160.dp).cssScrollY()) {
                    Mono(output, tokens.text3, breakAll = true, style = tinyMono)
                }
            }
        }
    }
}

/** Whether the message arrived, with the runtime's JSON one tap away (`SendMessageOutcomeView`). */
@Composable
private fun SendMessageOutcomeView(output: String, outcome: SendMessageOutcome) {
    val tokens = LocalPpmTokens.current
    var showRaw by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().cssBorder(tokens.border.color, sides = CssSides.Top)) {
        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val middle = Modifier.align(Alignment.CenterVertically)
                val tone = if (outcome.ok) tokens.success else tokens.error
                PpmIcon(if (outcome.ok) ProductIcon.CheckCircle2 else ProductIcon.XCircle, 12.dp, tone.color, middle)
                CursorText(if (outcome.ok) "Delivered" else "Not delivered", middle, cardText.copy(color = tone.color))
                outcome.detail?.let { CursorText("· $it", middle, cardText.copy(color = tokens.text3.color)) }
                outcome.ref?.let { CursorText("[$it]", middle, tinyMono.copy(color = tokens.text3.color)) }
            }
            OutputToggle(if (showRaw) "Hide raw" else "Show raw", ProductIcon.Code) { showRaw = !showRaw }
            if (showRaw) {
                Box(Modifier.heightIn(max = 160.dp).cssScrollY()) {
                    Mono(output, tokens.text3, breakAll = true, style = tinyMono)
                }
            }
        }
    }
}

/** A `text-[10px] text-text-subtle hover:text-text-secondary` toggle with its icon. */
@Composable
private fun OutputToggle(label: String, icon: ProductIcon, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover()
    val color = tokens.text2.mix(tokens.text3, hover.lit.toDouble()).color
    Row(
        Modifier.ppmClickable(pressedColor = tokens.surfaceHover.color, source = hover.source, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PpmIcon(icon, 12.dp, color)
        BasicText(label, style = tinyText.copy(color = color))
    }
}

/** Longer than three lines or 200 characters: collapsed to four lines behind a toggle. */
internal fun isLongOutput(output: String): Boolean = output.split("\n").size > 3 || output.length > 200

/**
 * What a collapsed output shows of [output]: four lines under a 64dp cut, so its first four lines
 * (and of a line that never ends, more than four rows hold) are all it can show. Laying out the
 * rest of a long one would only stall the list.
 */
internal fun collapsedPreview(output: String): String {
    var end = 0
    var preview = output
    for (line in 0 until 4) {
        val next = output.indexOf('\n', end)
        if (next < 0) break
        if (line == 3) preview = output.substring(0, next)
        end = next + 1
    }
    return preview.take(2_000)
}

/** Raw output (`CollapsibleOutput`): collapsed as it first shows when long, then as the user leaves it. */
@Composable
private fun CollapsibleOutput(output: String) {
    val tokens = LocalPpmTokens.current
    val long = isLongOutput(output)
    var collapsed by remember { mutableStateOf(long) }
    Column(Modifier.fillMaxWidth().cssBorder(tokens.border.color, sides = CssSides.Top)) {
        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (long) {
                // `text-text-subtle hover:text-text-secondary`.
                val hover = rememberHover()
                val color = tokens.text2.mix(tokens.text3, hover.lit.toDouble()).color
                Row(
                    Modifier.ppmClickable(pressedColor = tokens.surfaceHover.color, source = hover.source) { collapsed = !collapsed },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PpmIcon(if (collapsed) ProductIcon.ChevronRight else ProductIcon.ChevronDown, 12.dp, color)
                    BasicText("Output (${output.split("\n").size} lines)", style = tinyText.copy(color = color))
                }
            }
            when {
                collapsed -> Box(Modifier.heightIn(max = 64.dp).clipToBounds()) {
                    Mono(remember(output) { collapsedPreview(output) }, tokens.text3, breakAll = true)
                }
                long -> Box(Modifier.heightIn(max = 240.dp).cssScrollY()) { Mono(output, tokens.text3, breakAll = true) }
                else -> Mono(output, tokens.text3, breakAll = true)
            }
        }
    }
}

// Agent

/**
 * An `Agent`/`Task` call in the chat: one line with the step count and the step it is on, which
 * opens the subagent's session (`AgentCardSummary`) — a tap, no expanding in place.
 */
@Composable
private fun AgentRow(tool: ToolBlock, status: Status, bgRunning: Boolean, modifier: Modifier, onOpen: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val handle = tool.input["name"].string?.trim()?.ifEmpty { null }
    val description = truncate(tool.input["description"].string ?: tool.input["prompt"].string ?: "", 60)
    val shape = RoundedCornerShape(11.dp)
    Row(
        modifier
            .fillMaxWidth()
            // `min-h-[44px]` where the primary pointer is coarse, as a touch screen is.
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, shape)
            .webTitle(if (handle != null) "Open $handle's session" else "Open agent session")
            .ppmClickable(pressedColor = tokens.panel2.opacity(0.4).color, hoverColor = tokens.panel2.opacity(0.4).color, onClick = onOpen)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ToolChip(
            if (handle == null) {
                ToolChipStyle(ProductIcon.Bot, tokens.accentWash, tokens.accent)
            } else {
                ToolChipStyle(ProductIcon.Users, tokens.accent2.opacity(0.15), tokens.accent2)
            },
        )
        val text = buildAnnotatedString {
            withStyle(SpanStyle(color = tokens.text.color, fontWeight = FontWeight.Medium)) {
                append(handle ?: description.ifEmpty { "Agent" })
            }
            tool.lastStep?.let { step -> withStyle(SpanStyle(color = tokens.text3.color)) { append(" · $step") } }
        }
        // The row's own colour, which the "…" takes: the button inherits the body's `text-text`.
        TruncatedText(text, cardText.copy(color = tokens.text.color), Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (bgRunning) BasicText("running…", style = tinyText.copy(color = tokens.accent.color))
            if (tool.steps > 0) BasicText(stepCount(tool.steps), style = tinyMono.copy(color = tokens.text3.color))
            StatusIcon(status)
            PpmIcon(ProductIcon.ChevronRight, 12.dp, tokens.text3.color)
        }
    }
}
