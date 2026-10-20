package com.hienlh.ppm.chat

import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFonts
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ThemeTokens
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.protocol.ToolOutcome
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.double
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.prettyString
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.ToolBlock
import com.hienlh.ppm.state.isAgent
import com.hienlh.ppm.state.truthy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** `text-xs`: the cards' text, 12px on a 16px line. */
private val cardText = ppmText(12f, lineHeight = 16f)

/** `<pre class="font-mono">` at 12px. */
private val monoText = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f)

/**
 * A tool call as the web's `ToolCard` draws it (src/web/components/chat/tool-cards.tsx): a tinted
 * chip, a one-line summary, the status on the right, details when expanded. [completed]: a call
 * without a result is over anyway, its turn having ended or a later edit of the file it read
 * having come back.
 */
@Composable
fun ToolCardView(tool: ToolBlock, completed: Boolean, modifier: Modifier = Modifier) {
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
    if (tool.isAgent) {
        AgentRow(tool, if (isError || bgFailed) Status.FAILED else if (isDone) Status.DONE else Status.RUNNING, bgRunning, modifier)
        return
    }
    val tokens = LocalPpmTokens.current
    val isEdit = tool.name == "Edit" || tool.name == "MultiEdit"
    // An edit opens on its diff, unless it failed and the diff was never applied.
    var expanded by remember { mutableStateOf(isEdit && !isError) }
    // An edit that fails shows nothing worth opening on; it collapses once.
    LaunchedEffect(isError) {
        if (isError && isEdit) expanded = false
    }
    val interactive = tool.name in setOf("AskUserQuestion", "ExitPlanMode")
    val shape = RoundedCornerShape(11.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.panel.color)
            .cssBorder((if (interactive) tokens.accentWashBorder else tokens.border).color, shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .ppmClickable(pressedColor = tokens.panel2.opacity(0.4).color, hoverColor = tokens.panel2.opacity(0.4).color) { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ToolChip(ToolChipStyle.of(tool.name, tool.input, isError, tokens))
            ToolSummaryLine(tool.name, tool.input, Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Codex's exit code for a shell call: its output can matter even when it failed.
                result?.exitCode?.let { code ->
                    BasicText("exit $code", style = ppmText(10f, kind = FontKind.MONO, lineHeight = 15f).copy(color = (if (code == 0) tokens.text3 else tokens.error).color))
                }
                StatusIcon(if (isError) Status.FAILED else if (isDone) Status.DONE else Status.RUNNING)
                PpmIcon(if (expanded) ProductIcon.ChevronDown else ProductIcon.ChevronRight, 12.dp, tokens.text3.color)
            }
        }
        if (expanded) {
            Column(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ToolDetails(tool.name, tool.input)
                if (result != null && showsResult(tool.name, result)) CollapsibleOutput(result.output)
            }
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

/** A backgrounded agent's call answers this the moment the agent starts (`isAsyncAgentLaunchAck`). */
internal fun isAsyncAgentLaunchAck(output: String): Boolean = output.contains("Async agent launched successfully")

internal fun stepCount(n: Int): String = if (n == 1) "1 step" else "$n steps"

// Chip and status

internal data class ToolChipStyle(val icon: ProductIcon, val background: Rgba, val foreground: Rgba) {
    companion object {
        fun of(name: String, input: JsonElement, isError: Boolean, t: ThemeTokens): ToolChipStyle {
            val wash = ToolChipStyle(ProductIcon.Code, t.accentWash, t.accent)
            fun tinted(icon: ProductIcon, color: Rgba) = ToolChipStyle(icon, color.opacity(0.15), color)
            fun washed(icon: ProductIcon) = wash.copy(icon = icon)
            fun plain(icon: ProductIcon) = ToolChipStyle(icon, t.panel2, t.text2)
            if (isError && (name == "Bash" || name == "PowerShell")) return tinted(ProductIcon.Terminal, t.error)
            if ((name == "Agent" || name == "Task") && !input["name"].string.isNullOrBlank()) return tinted(ProductIcon.Users, t.accent2)
            if (name.startsWith("mcp__ppm-db__")) {
                return if (name.endsWith("db_execute")) tinted(ProductIcon.DatabaseZap, t.warning) else tinted(ProductIcon.Database, t.info)
            }
            if (name == "mcp__ppm-tabs__open_url") return tinted(ProductIcon.Globe, t.info)
            if (name == "mcp__ppm-tabs__read_terminal" || name == "mcp__ppm-tabs__run_in_terminal") return plain(ProductIcon.Terminal)
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
        PpmIcon(style.icon, 14.dp, style.foreground.color)
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

/** The header's one line: the tool's name, then what it acted on. */
internal data class ToolSummaryText(val icon: ProductIcon? = null, val name: String, val detail: String, val monoDetail: Boolean = false)

/** `ToolSummary` in tool-cards.tsx. */
internal fun toolSummary(name: String, input: JsonElement): ToolSummaryText {
    // `String(v ?? "")`: a whole number reads without a decimal point.
    fun s(key: String): String {
        val value = input[key] ?: return ""
        value.string?.let { return it }
        val number = value.double ?: return ""
        return if (number == Math.floor(number) && Math.abs(number) < 9e15) number.toLong().toString() else number.toString()
    }
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
        "TaskCreate" -> ToolSummaryText(ProductIcon.ListTodo, name, truncate(s("subject"), 60))
        "TaskUpdate" -> ToolSummaryText(ProductIcon.ListTodo, name, "#${s("taskId")} → ${s("status")}")
        "TaskStop" -> ToolSummaryText(ProductIcon.ListTodo, name, "#${s("taskId")} stopped")
        "SendMessage" -> {
            val to = s("to")
            ToolSummaryText(ProductIcon.Send, name, if (to.isEmpty()) "" else "→ $to")
        }
        else -> ToolSummaryText(name = name, detail = "")
    }
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
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (summary.icon != null) PpmIcon(summary.icon, 12.dp, tokens.text.color)
        val text = buildAnnotatedString {
            append(summary.name)
            if (summary.detail.isNotEmpty()) {
                append(" ")
                val detail = if (summary.monoDetail) {
                    SpanStyle(color = tokens.text3.color, fontFamily = PpmFonts.mono, fontWeight = FontWeight.Normal)
                } else {
                    SpanStyle(color = tokens.text3.color)
                }
                withStyle(detail) { append(summary.detail) }
            }
        }
        BasicText(
            text,
            style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
        )
    }
}

// Details

/** What an expanded card shows about the call itself. */
@Composable
private fun ToolDetails(name: String, input: JsonElement) {
    val tokens = LocalPpmTokens.current
    fun s(key: String) = input[key].string ?: ""
    when (name) {
        "Bash", "PowerShell" -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (s("description").isNotEmpty()) {
                BasicText(s("description"), style = ppmText(12f, lineHeight = 16f, italic = true).copy(color = tokens.text3.color))
            }
            Mono(s("command"), tokens.text2)
        }
        "Read", "Write", "Edit", "MultiEdit", "NotebookEdit" -> FileDetails(name, input)
        "Glob" -> Mono(s("pattern") + if (s("path").isEmpty()) "" else " in ${s("path")}", tokens.text2)
        "Grep" -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Mono("/${s("pattern")}/", tokens.text2)
            if (s("path").isNotEmpty()) BasicText("in ${s("path")}", style = cardText.copy(color = tokens.text3.color))
        }
        "TodoWrite" -> TodoList(input["todos"].array.orEmpty())
        "WebFetch" -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val url = s("url")
            if (url.isNotEmpty()) {
                val uriHandler = LocalUriHandler.current
                // `hover:underline`, at once.
                val hover = rememberHover(instant = true)
                Row(
                    Modifier.ppmClickable(pressedColor = tokens.surfaceHover.color, source = hover.source) { runCatching { uriHandler.openUri(url) } },
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PpmIcon(ProductIcon.Globe, 12.dp, tokens.accent.color, Modifier.padding(top = 2.dp))
                    BasicText(
                        url,
                        style = monoText.copy(color = tokens.accent.color, textDecoration = if (hover.lit > 0f) TextDecoration.Underline else null),
                    )
                }
            }
            if (s("prompt").isNotEmpty()) BasicText(truncate(s("prompt"), 100), style = cardText.copy(color = tokens.text3.color))
        }
        "ToolSearch" -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Mono(s("query"), tokens.text2)
            input["max_results"].int?.let { BasicText("Max results: $it", style = cardText.copy(color = tokens.text3.color)) }
        }
        "AskUserQuestion" -> AnsweredQuestions(input)
        "TaskCreate" -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText(s("subject"), style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color))
            if (s("description").isNotEmpty()) BasicText(s("description"), style = cardText.copy(color = tokens.text3.color))
        }
        "ImageGen" -> if (s("prompt").isNotEmpty()) BasicText(s("prompt"), style = cardText.copy(color = tokens.text2.color))
        else -> Mono(if (input is JsonNull) "null" else input.prettyString, tokens.text2)
    }
}

@Composable
private fun FileDetails(name: String, input: JsonElement) {
    val tokens = LocalPpmTokens.current
    fun s(key: String) = input[key].string ?: ""
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Mono(s("notebook_path").ifEmpty { s("file_path") }, tokens.text2)
        if (name == "Edit" && (s("old_string").isNotEmpty() || s("new_string").isNotEmpty())) {
            EditDiffView(s("old_string"), s("new_string"))
        }
        if (name == "MultiEdit") {
            val edits = input["edits"].array.orEmpty()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                edits.forEachIndexed { index, edit ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        BasicText("Edit ${index + 1}", style = ppmText(10f).copy(color = tokens.text3.color))
                        EditDiffView(edit["old_string"].string ?: "", edit["new_string"].string ?: "")
                    }
                }
            }
        }
        if (name == "Write" && s("content").isNotEmpty()) {
            Box(Modifier.heightIn(max = 128.dp).verticalScroll(rememberScrollState())) {
                Mono(truncate(s("content"), 300), tokens.text3)
            }
        }
    }
}

/** `<pre class="font-mono whitespace-pre-wrap break-all">` at 12px. */
@Composable
private fun Mono(text: String, color: Rgba, modifier: Modifier = Modifier) {
    SelectionContainer(modifier.fillMaxWidth()) {
        BasicText(text, style = monoText.copy(color = color.color))
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
                BasicText(glyph, Modifier.alignByBaseline(), cardText.copy(color = color.color))
                BasicText(
                    todo["content"].string ?: "",
                    Modifier.alignByBaseline(),
                    cardText.copy(
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
            val answer = answers[question].string ?: ""
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BasicText(
                    (header?.let { "$it: " } ?: "") + question,
                    style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (option in q["options"].array.orEmpty()) {
                        val label = option["label"].string ?: ""
                        val selected = answer.split(", ").contains(label)
                        val shape = RoundedCornerShape(4.dp)
                        BasicText(
                            label,
                            Modifier
                                .background((if (selected) tokens.accent.opacity(0.2) else Rgba(0.0, 0.0, 0.0, 0.0)).color, shape)
                                .cssBorder((if (selected) tokens.accent else tokens.border).color, shape)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            cardText.copy(color = (if (selected) tokens.text else tokens.text3).color),
                        )
                    }
                }
                if (answer.isNotEmpty()) BasicText("Answer: $answer", style = cardText.copy(color = tokens.text.color))
            }
        }
    }
}

// Output

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
                    BasicText("Output (${output.split("\n").size} lines)", style = ppmText(10f).copy(color = color))
                }
            }
            when {
                collapsed -> Box(Modifier.heightIn(max = 64.dp).clipToBounds()) { Mono(remember(output) { collapsedPreview(output) }, tokens.text3) }
                long -> Box(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) { Mono(output, tokens.text3) }
                else -> Mono(output, tokens.text3)
            }
        }
    }
}

// Agent

/**
 * An `Agent`/`Task` call: one line with the step count and the step it is on
 * (`AgentCardSummary`). The web opens the subagent's session from here; this app does not have
 * that view yet, so the row does not open.
 */
@Composable
private fun AgentRow(tool: ToolBlock, status: Status, bgRunning: Boolean, modifier: Modifier) {
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
        BasicText(text, Modifier.weight(1f), style = cardText, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // `text-[10px]` on preflight's 1.5 line.
            if (bgRunning) BasicText("running…", style = ppmText(10f, lineHeight = 15f).copy(color = tokens.accent.color))
            if (tool.steps > 0) {
                BasicText(stepCount(tool.steps), style = ppmText(10f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text3.color))
            }
            StatusIcon(status)
        }
    }
}
