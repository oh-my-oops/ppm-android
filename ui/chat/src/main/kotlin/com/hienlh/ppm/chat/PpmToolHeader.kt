package com.hienlh.ppm.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ThemeTokens
import com.hienlh.ppm.design.TruncatedText
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.protocol.isAssistantProject
import kotlinx.coroutines.launch

/*
 * The header parts of a card for one of PPM's own tools (`ppm-tool-header.tsx`): the chip, the
 * readable line, and the buttons that open what the call named — a task's quick view (the one a
 * task chip opens) or a chat. Drawn by `ToolCardView`; the line itself is [ppmToolSummary].
 */

/**
 * Shows the task a code names (`lookupTasksByCode`, then the task's quick view): [chatProject] is
 * the project a bare `#12` belongs to, none in the Assistant, where it must name one task only.
 */
fun interface TaskCodeOpener {
    suspend fun open(code: String, chatProject: String?)
}

/** How a PPM tool card's task button opens its task; none, and the card offers no button. */
val LocalTaskCodeOpener = staticCompositionLocalOf<TaskCodeOpener?> { null }

/** The chip a PPM tool's card shows: one icon per tool family (`CHIPS`). */
internal fun ppmToolChipStyle(icon: PpmToolIcon, t: ThemeTokens): ToolChipStyle {
    fun tinted(glyph: ProductIcon, color: com.hienlh.ppm.design.Rgba) = ToolChipStyle(glyph, color.opacity(0.15), color)
    return when (icon) {
        PpmToolIcon.TASK -> ToolChipStyle(ProductIcon.ListTodo, t.accentWash, t.accent)
        PpmToolIcon.SEARCH -> tinted(ProductIcon.Search, t.info)
        PpmToolIcon.TURNS -> tinted(ProductIcon.History, t.info)
        PpmToolIcon.SCHEDULE -> tinted(ProductIcon.CalendarClock, t.warning)
        PpmToolIcon.REPORT -> tinted(ProductIcon.Send, t.info)
        PpmToolIcon.ASSISTANT -> tinted(ProductIcon.Sparkles, t.accent2)
        PpmToolIcon.DESIGN -> tinted(ProductIcon.Palette, t.accent2)
        PpmToolIcon.TOOL -> ToolChipStyle(ProductIcon.Wrench, t.panel2, t.text2)
    }
}

/** The line inside the header (`PpmToolSummaryText`): the readable name, then what the call was about, dimmer. */
@Composable
internal fun PpmToolSummaryLine(summary: PpmToolSummary, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val text = buildAnnotatedString {
        append(summary.label)
        summary.detail?.let { detail ->
            withStyle(SpanStyle(color = tokens.text3.color)) { append(" $detail") }
        }
    }
    TruncatedText(text, ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color), modifier.testTag("ppm-tool-summary"))
}

/** Buttons beside the header (`PpmToolLinks`): the task it named, the chat it named. Nothing when it named neither. */
@Composable
internal fun PpmToolLinks(summary: PpmToolSummary) {
    val chat = LocalAgentSessionOpener.current?.identity
    val project = chat?.projectName.orEmpty()
    val session = summary.session
    // A call from the PPM Assistant names other projects; with none named, the chat is not opened in the Assistant's.
    val chatTarget = session?.let { it.project ?: project.takeIf { p -> p.isNotEmpty() && !isAssistantProject(p) } }
    val sessionOpener = LocalChatSessionOpener.current
    val taskOpener = LocalTaskCodeOpener.current
    val task = summary.task?.takeIf { taskOpener != null }
    val openChat = session != null && chatTarget != null && sessionOpener != null
    if (task == null && !openChat) return
    Row(Modifier.padding(end = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (task != null) TaskCodeButton(task, if (isAssistantProject(project)) null else project.ifEmpty { null }, taskOpener!!)
        if (openChat) {
            LinkButton("Open the chat", "ppm-tool-open-chat", onClick = {
                sessionOpener!!.open(ChatSessionTarget(chatTarget!!, session!!.providerId ?: chat?.providerId.orEmpty(), session.sessionId, "", session.turnId))
            })
        }
    }
}

/** `LINK_BUTTON`: a text button in the accent, 28 px tall, 26 on a phone, at most 10rem wide. */
@Composable
private fun LinkButton(title: String, tag: String, onClick: () -> Unit, content: @Composable () -> Unit = {
    BasicText("Open chat", style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = LocalPpmTokens.current.accent.color), maxLines = 1)
}) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .height(if (LocalPpmMobile.current) 26.dp else 28.dp)
            .widthIn(max = 160.dp)
            .webTitle(title)
            .ppmClickable(pressedColor = tokens.accentWash.color, hoverColor = tokens.accentWash.color, shape = shape, onClickLabel = title, onClick = onClick)
            .padding(horizontal = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

/** A task's code as a button (`TaskCodeButton`): the task's quick view, a spinner while it is looked up. */
@Composable
private fun TaskCodeButton(code: String, chatProject: String?, opener: TaskCodeOpener) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    LinkButton("Show task $code", "ppm-tool-task", onClick = {
        if (!busy) {
            busy = true
            scope.launch {
                try {
                    opener.open(code, chatProject)
                } finally {
                    busy = false
                }
            }
        }
    }) {
        PpmIcon(if (busy) ProductIcon.Loader2 else ProductIcon.ListTodo, 14.dp, tokens.accent.color, spinning = busy)
        BasicText(
            code,
            style = ppmText(11.5f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.accent.color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
