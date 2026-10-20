package com.hienlh.ppm.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFonts
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ThemeTokens
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.protocol.ToolOutcome
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.double
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.prettyString
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.ToolBlock
import com.hienlh.ppm.state.ToolSummary
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** `text-xs`: the cards' text, 12px on a 16px line. */
private val cardText = ppmText(12f, lineHeight = 16f)

/** `<pre class="font-mono">` at 12px. */
private val monoText = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f)

/**
 * A tool call as the web's `ToolCard` draws it (src/web/components/chat/tool-cards.tsx): a tinted
 * chip, a one-line summary, the status on the right, details when expanded. [turnRunning]: the
 * turn holding the call is still running, so a call without a result is in progress.
 */
@Composable
fun ToolCardView(tool: ToolBlock, turnRunning: Boolean, modifier: Modifier = Modifier) {
    val isError = tool.result?.isError ?: false
    val hasAnswers = tool.name == "AskUserQuestion" && tool.input["answers"] != null
    val isDone = tool.result != null || hasAnswers || !turnRunning
    if (tool.name == "Agent" || tool.name == "Task") {
        AgentRow(tool, isDone, modifier)
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
            .border(1.dp, (if (interactive) tokens.accentWashBorder else tokens.border).color, shape)
            .padding(1.dp),
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
                if (tool.steps > 0) {
                    BasicText(stepCount(tool.steps), style = ppmText(10f, kind = FontKind.MONO).copy(color = tokens.text3.color))
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
                val result = tool.result
                if (result != null && showsResult(tool.name, result)) CollapsibleOutput(result.output)
            }
        }
    }
}

/** A file change shows itself in the details; its "updated successfully" is noise. */
private fun showsResult(name: String, result: ToolOutcome): Boolean =
    !(name in setOf("Edit", "MultiEdit", "Write", "NotebookEdit") && !result.isError)

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
            ToolSummaryText(name = name, detail = "$count question${if (count == 1) "" else "s"}${if (input["answers"] != null) " ✓" else ""}")
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
        else -> ToolSummaryText(name = ToolSummary.displayName(name), detail = ToolSummary.detail(name, input))
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
                                .border(1.dp, (if (selected) tokens.accent else tokens.border).color, shape)
                                .padding(horizontal = 7.dp, vertical = 3.dp),
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

/** A huge text stalls layout; the web scrolls it, nobody reads past this. */
private fun clipped(output: String): String {
    val limit = 20_000
    return if (output.length <= limit) output else output.take(limit) + "\n… ${output.length - limit} more characters"
}

/** Raw output (`CollapsibleOutput`). */
@Composable
private fun CollapsibleOutput(output: String) {
    val tokens = LocalPpmTokens.current
    val long = isLongOutput(output)
    var collapsed by remember(output) { mutableStateOf(long) }
    val text = remember(output) { clipped(output) }
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.border.color))
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
                collapsed -> Box(Modifier.heightIn(max = 64.dp).clipToBounds()) { Mono(text, tokens.text3) }
                long -> Box(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) { Mono(text, tokens.text3) }
                else -> Mono(text, tokens.text3)
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
private fun AgentRow(tool: ToolBlock, isDone: Boolean, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    val handle = tool.input["name"].string?.takeIf { it.isNotBlank() }
    val description = truncate(tool.input["description"].string ?: tool.input["prompt"].string ?: "", 60)
    val failed = tool.result?.isError ?: false
    val shape = RoundedCornerShape(11.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.panel.color)
            .border(1.dp, tokens.border.color, shape)
            .padding(horizontal = 11.dp, vertical = 9.dp),
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
            if (tool.steps > 0) {
                BasicText(stepCount(tool.steps), style = ppmText(10f, kind = FontKind.MONO).copy(color = tokens.text3.color))
            }
            StatusIcon(if (failed) Status.FAILED else if (isDone) Status.DONE else Status.RUNNING)
        }
    }
}
