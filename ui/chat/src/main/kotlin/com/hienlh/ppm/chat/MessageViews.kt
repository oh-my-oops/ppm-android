package com.hienlh.ppm.chat

import android.content.ClipData
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.ClampedText
import com.hienlh.ppm.design.CssColumn
import com.hienlh.ppm.design.CssRow
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.CssText
import com.hienlh.ppm.design.CursorText
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFormat
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.TextCursorRegion
import com.hienlh.ppm.design.ThemeShadow
import com.hienlh.ppm.design.colorsFade
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssExact
import com.hienlh.ppm.design.cssLines
import com.hienlh.ppm.design.cssScrollY
import com.hienlh.ppm.design.cssWidth
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberCssExact
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.protocol.ChatQuestions
import com.hienlh.ppm.protocol.Question
import com.hienlh.ppm.protocol.TurnStop
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.TurnUsage
import com.hienlh.ppm.state.ChatBlock
import com.hienlh.ppm.state.ChatItem
import com.hienlh.ppm.state.SessionTurn
import com.hienlh.ppm.state.TurnFileChange
import com.hienlh.ppm.state.truthy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * The time, the Copy text and Reply under the last message of a finished turn, and the files the
 * turn changed for its pill: the turn it closes (what "Revert turn" puts back), [key] the message
 * that ends it, and [onJump] scrolls the chat to the tool card that made an edit.
 */
data class MessageActions(
    val timestamp: Instant?,
    val copyText: String,
    val onReply: (() -> Unit)? = null,
    val changes: List<TurnFileChange> = emptyList(),
    val turn: SessionTurn? = null,
    val key: String = "",
    val onJump: ((String) -> Unit)? = null,
)

/** One message of the transcript (`MessageBubble` in message-list.tsx). */
@Composable
fun ChatMessageView(
    item: ChatItem,
    actions: MessageActions?,
    modifier: Modifier = Modifier,
    replies: ReplyTargets = ReplyTargets.None,
    /** Reply on a user's message; an answer's is in [actions]. */
    onReply: (() -> Unit)? = null,
    /** Edit, Fork and the versions on a user's message. */
    edits: UserMessageEdits? = null,
    onDismiss: () -> Unit = {},
) {
    when (item.role) {
        ChatItem.Role.USER -> UserBubble(item.text, item.timestamp, replies, modifier, onReply, edits)
        ChatItem.Role.ASSISTANT -> CompositionLocalProvider(LocalMarkdownImageScope provides item.id) {
            AssistantBlocks(item.blocks, live = false, actions = actions, modifier = modifier, usage = item.usage)
        }
        ChatItem.Role.SYSTEM -> SystemMessage(item.text, onDismiss, modifier)
    }
}

// Action bar

/**
 * Time, Copy and Reply under a message (`MessageActionBar`), then [extra] (a user message's Edit
 * and Fork, an answer's change pill): `flex-wrap gap-0.5`, so what does not fit goes to a line of
 * its own. Reply is a 44 px target on a phone, which makes its line that tall. The time's `title`
 * is the full date. A user's bar is flush with the end of the column ([alignEnd]); each item is
 * where its exact place in the row puts it, as the labels' widths are not whole pixels.
 */
@Composable
internal fun MessageActionBar(
    timestamp: Instant?,
    copyText: String,
    modifier: Modifier = Modifier,
    onReply: (() -> Unit)? = null,
    alignEnd: Boolean = false,
    extra: @Composable () -> Unit = {},
) {
    val tokens = LocalPpmTokens.current
    CssRow(modifier, gap = 2.dp, wrap = true, alignEnd = alignEnd) {
        if (timestamp != null) {
            // `pr-1` kept exact: 4 px is 10.5 at 2.625x, which padding would make 11.
            CssRow(Modifier.webTitle(remember(timestamp) { PpmFormat.fullDate(timestamp) }), end = 4.dp) {
                RelativeTime(timestamp, ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color, fontFeatureSettings = "tnum"))
            }
        }
        CopyActionButton(copyText)
        if (onReply != null) ActionButton(ProductIcon.RotateCcw, "Reply", "Reply to this message", touchTarget = true, onClick = onReply)
        extra()
    }
}

/** "Copy", then "Copied" with a check for 1.5 s. */
@Composable
internal fun CopyActionButton(text: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    ActionButton(if (copied) ProductIcon.Check else ProductIcon.Copy, if (copied) "Copied" else "Copy", "Copy message to clipboard") {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", text)))
            copied = true
            delay(1_500)
            copied = false
        }
    }
}

/**
 * The action bar's ghost pill (`ActionButton`): subtle, `hover:text-text-primary hover:bg-surface`,
 * and so while pressed, with its [title] for a mouse. A [touchTarget] is at least 44 px square on a
 * phone, its content centred.
 */
@Composable
internal fun ActionButton(icon: ProductIcon, label: String, title: String, touchTarget: Boolean = false, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val hovered by source.collectIsHoveredAsState()
    val lit = (if (pressed) 1f else colorsFade(hovered)).toDouble()
    val color = tokens.text.mix(tokens.text3, lit).color
    val shape = RoundedCornerShape(8.dp)
    val target = if (touchTarget && LocalPpmMobile.current) 44.dp else 0.dp
    CssRow(
        Modifier
            .clip(shape)
            .background(tokens.panel.opacity(lit).color)
            .hoverable(source)
            .webTitle(title)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .defaultMinSize(minWidth = target),
        gap = 4.dp,
        start = 6.dp,
        end = 6.dp,
        top = 4.dp,
        bottom = 4.dp,
        minHeight = target,
        centre = true,
    ) {
        PpmIcon(icon, 14.dp, color, Modifier.cssWidth(14.dp))
        CssText(label, ppmText(12f, lineHeight = 16f).copy(color = color))
    }
}

// Assistant

/**
 * An answer's blocks in order — text, thinking, tool calls — then the turn's cost notice and its
 * action bar. Each text block's pictures are named by the message's scope and the block's place.
 */
@Composable
fun AssistantBlocks(blocks: List<ChatBlock>, live: Boolean, actions: MessageActions?, modifier: Modifier = Modifier, usage: TurnUsage? = null) {
    val scope = LocalMarkdownImageScope.current
    // Stacked in Chrome's fractions, as the transcript stacks its messages.
    CssColumn(modifier.fillMaxWidth()) {
        CssColumn(Modifier.fillMaxWidth(), gap = 8.dp) {
            blocks.forEachIndexed { index, block ->
                // The turn is streaming: its last text or thinking block is still growing.
                val growing = live && index == blocks.lastIndex
                when (block) {
                    is ChatBlock.Text -> CssColumn {
                        CompositionLocalProvider(LocalMarkdownImageScope provides scope?.let { "$it/$index" }) {
                            WithoutOnCopy(MARKDOWN_MARKS) { SelectableMarkdown(block.text, growing) }
                        }
                        if (growing) StreamingMark()
                    }
                    is ChatBlock.Thinking -> ThinkingBlock(block.text, growing)
                    is ChatBlock.Tool -> {
                        val tool = block.tool
                        val itemId = tool.toolUseId
                        // Codex asking without stopping: a form in the turn, not a card about a call.
                        if (tool.name == ChatQuestions.ASYNC_QUESTION_TOOL && itemId != null) {
                            AsyncQuestionCard(itemId, tool.input["questions"].array.orEmpty().mapNotNull(Question::from))
                        } else {
                            ToolCardView(tool, completed = !live || readSettled(blocks, index))
                        }
                    }
                    is ChatBlock.Error -> InlineError(block.message)
                    // The web quotes it into the answer as markdown of its own.
                    is ChatBlock.Retry -> CssColumn {
                        val quote = "> ↻ ${block.reason} — retrying with **${block.accountLabel}**..."
                        WithoutOnCopy(MARKDOWN_MARKS) { SelectableMarkdown(quote, streaming = false) }
                        if (growing) StreamingMark()
                    }
                }
            }
        }
        if (actions != null && usage != null) TurnCostWarning(usage, Modifier.padding(top = 8.dp))
        // `-mt-1.5` against the column's `gap-2`: 2 px under the answer, 5.25 at 2.625x.
        if (actions != null) CssColumn(top = 2.dp) { TurnChangeRollup(actions) }
    }
}

/**
 * A text block's markdown, selectable (`select-text`, so the I-beam over its glyphs), counted by
 * the column around it at its exact height.
 */
@Composable
private fun SelectableMarkdown(text: String, streaming: Boolean) {
    val exact = rememberCssExact()
    SelectionContainer(Modifier.cssExact(exact)) {
        TextCursorRegion { MarkdownContent(text, streaming = streaming, exact = exact) }
    }
}

/** A read with no result yet is over once a later edit of the same file came back, which needed it. */
private fun readSettled(blocks: List<ChatBlock>, index: Int): Boolean {
    val read = (blocks[index] as ChatBlock.Tool).tool
    if (read.name != "Read" || read.result != null || read.requestId != null) return false
    val path = read.input["file_path"]?.takeIf(::truthy) ?: return false
    return blocks.subList(index + 1, blocks.size).any {
        it is ChatBlock.Tool && it.tool.name == "Edit" && it.tool.requestId == null && it.tool.result != null && it.tool.input["file_path"] == path
    }
}

/** Tailwind's `animate-pulse`: 2 s, down to half opacity and back. */
@Composable
fun pulse(): Float {
    val alpha by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(tween(1_000, easing = CubicBezierEasing(0.4f, 0f, 0.6f, 1f)), RepeatMode.Reverse),
        label = "pulse",
    )
    return alpha
}

/** The pulsing "Responding..." under text that is still streaming: inside the text's `select-text` div, so it has the I-beam too. */
@Composable
internal fun StreamingMark() {
    val tokens = LocalPpmTokens.current
    val alpha = pulse()
    TextCursorRegion {
        CursorText("Responding...", Modifier.alpha(alpha).cssLines(20f), ppmText(14f, lineHeight = 20f).copy(color = tokens.text3.color))
    }
}

/** An error event inside an answer. */
@Composable
internal fun InlineError(message: String) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(4.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(tokens.error.opacity(0.1).color, shape)
            .cssBorder(tokens.error.opacity(0.2).color, shape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PpmIcon(ProductIcon.AlertCircle, 12.dp, tokens.error.color)
        SelectionContainer(Modifier.weight(1f)) {
            BasicText(message, style = ppmText(12f, lineHeight = 16f).copy(color = tokens.error.color))
        }
    }
}

/**
 * A system message: the web draws them all as errors, each with its Dismiss — a 24 px `p-1` X in
 * `error/70`, lit under a mouse. The web hides the X from 768 px up until a mouse is over the
 * bubble (`md:opacity-0 md:group-hover:opacity-100`), which on a touch tablet leaves it invisible
 * for good; this shows it always, as the web's `can-hover:` controls show on a touch screen.
 */
@Composable
internal fun SystemMessage(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(12.dp)
    val dismiss = rememberHover(instant = true)
    Row(
        modifier
            .fillMaxWidth()
            .background(tokens.error.opacity(0.1).color, shape)
            .cssBorder(tokens.error.opacity(0.2).color, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PpmIcon(ProductIcon.AlertCircle, 16.dp, tokens.error.color)
        SelectionContainer(Modifier.weight(1f)) {
            BasicText(text, style = ppmText(14f, lineHeight = 20f).copy(color = tokens.error.color))
        }
        val pressed by dismiss.source.collectIsPressedAsState()
        val lit = pressed || dismiss.lit > 0f
        Box(
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .ppmClickable(
                    pressedColor = tokens.error.opacity(0.15).color,
                    hoverColor = tokens.error.opacity(0.15).color,
                    fade = false,
                    onClickLabel = "Dismiss",
                    source = dismiss.source,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) { PpmIcon(ProductIcon.XCircle, 16.dp, (if (lit) tokens.error else tokens.error.opacity(0.7)).color) }
    }
}

/** The thinking block's text: 11px, `leading-relaxed`. */
private val thinkingStyle = MarkdownStyle(size = 11f, lineHeightRatio = 1.625f)

/** The model's reasoning: open while it streams, folded with an estimated token count after. */
@Composable
internal fun ThinkingBlock(text: String, streaming: Boolean) {
    val tokens = LocalPpmTokens.current
    var expanded by remember { mutableStateOf(streaming) }
    LaunchedEffect(streaming) {
        if (!streaming && text.isNotEmpty()) expanded = false
    }
    val shape = RoundedCornerShape(4.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.panel.opacity(0.3).color)
            .cssBorder(tokens.border.opacity(0.5).color, shape),
    ) {
        val label = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color)
        Row(
            Modifier
                .fillMaxWidth()
                .ppmClickable(pressedColor = tokens.panel.color, hoverColor = tokens.panel.color) { expanded = !expanded }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (streaming) {
                PpmIcon(ProductIcon.Loader2, 12.dp, tokens.text3.color, spinning = true)
            } else {
                PpmIcon(ProductIcon.ChevronRight, 12.dp, tokens.text3.color, Modifier.rotate(if (expanded) 90f else 0f))
            }
            BasicText(if (streaming) "Thinking..." else "Thinking", style = label)
            Spacer(Modifier.weight(1f))
            if (!streaming && text.length > 100) {
                BasicText("${Math.round(text.length / 4.0)} tokens", style = label.copy(color = tokens.text3.opacity(0.5).color))
            }
        }
        if (expanded) {
            val scroll = rememberScrollState()
            // Pinned to the newest line while it streams.
            LaunchedEffect(streaming) {
                if (streaming) snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) }
            }
            Column(Modifier.heightIn(max = 240.dp).cssScrollY(scroll).padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                WithoutOnCopy(MARKDOWN_MARKS) {
                    SelectionContainer {
                        MarkdownContent(text, style = thinkingStyle.copy(color = tokens.text3.opacity(0.8)), streaming = streaming)
                    }
                }
            }
        }
    }
}

// Turn status

/**
 * What the turn is doing before any answer streams, or after a tool ran (`ThinkingIndicator`
 * in message-events.tsx). [elapsed] is the server's count of a wait for the first answer, sent
 * every 5 s while `connecting`; it shows while nothing has streamed yet ([waiting]).
 */
@Composable
fun ThinkingIndicator(label: String, elapsed: Int, waiting: Boolean, connecting: Boolean, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PpmIcon(ProductIcon.Loader2, 12.dp, tokens.text3.color, spinning = true)
            val text = buildAnnotatedString {
                append(label)
                if (waiting && elapsed > 0) {
                    withStyle(SpanStyle(color = tokens.text3.opacity(0.6).color)) { append("... (${elapsed}s)") }
                }
            }
            BasicText(text, style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text3.color))
        }
        if (connecting && elapsed >= 30) {
            BasicText(
                "Taking longer than usual — may be rate-limited or API slow. Try sending a new message to retry.",
                Modifier.padding(start = 20.dp),
                ppmText(12f, lineHeight = 16f).copy(color = tokens.warning.opacity(0.8).color),
            )
        }
    }
}

/** What Continue sends — the wording PPM's own retry uses for a turn cut short. */
const val CONTINUE_AFTER_STOP = "Continue from where you left off."

/** Why the last turn ended, when an error ended it, with a way to go on (`TurnStopBar`). */
@Composable
fun TurnStopBar(stop: TurnStop, onContinue: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    // `text-xs max-md:text-sm leading-snug`.
    val size = if (mobile) 14f else 12f
    val (title, detail) = PpmFormat.turnStop(stop.message, stop.subtype)
    Column(modifier.fillMaxWidth().background(tokens.error.opacity(0.1).color).cssBorder(tokens.error.opacity(0.2).color, sides = CssSides.Top)) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PpmIcon(ProductIcon.AlertCircle, 14.dp, tokens.error.color)
            Column(Modifier.weight(1f)) {
                BasicText(title, style = ppmText(size, FontWeight.Medium, lineHeight = size * 1.375f).copy(color = tokens.error.color))
                if (detail != null) {
                    ClampedText(
                        AnnotatedString(detail),
                        ppmText(size, lineHeight = size * 1.375f).copy(color = tokens.text2.color),
                        maxLines = if (mobile) 3 else 2,
                    )
                }
            }
            val shape = RoundedCornerShape(4.dp)
            Row(
                Modifier
                    .heightIn(min = if (mobile) 44.dp else 0.dp)
                    .clip(shape)
                    .background(tokens.bgSolid.color)
                    .ppmClickable(pressedColor = tokens.panel.color, hoverColor = tokens.panel.color, onClick = onContinue)
                    .cssBorder(tokens.border.color, shape)
                    .padding(horizontal = if (mobile) 16.dp else 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PpmIcon(ProductIcon.Play, 14.dp, tokens.text.color)
                // Outside the `leading-snug` block: the bar's own line, `text-xs` 16, `text-sm` 20.
                BasicText("Continue", style = ppmText(size, FontWeight.Medium, lineHeight = if (mobile) 20f else 16f).copy(color = tokens.text.color))
            }
        }
    }
}
