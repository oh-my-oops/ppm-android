package com.hienlh.ppm.chat

import android.content.ClipData
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFormat
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ThemeShadow
import com.hienlh.ppm.design.colorsFade
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.protocol.TurnStop
import com.hienlh.ppm.state.ChatBlock
import com.hienlh.ppm.state.ChatItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/** The time, the Copy text and Reply under the last message of a finished turn. */
data class MessageActions(val timestamp: Instant?, val copyText: String, val onReply: (() -> Unit)? = null)

/** One message of the transcript (`MessageBubble` in message-list.tsx). */
@Composable
fun ChatMessageView(
    item: ChatItem,
    actions: MessageActions?,
    modifier: Modifier = Modifier,
    replies: ReplyTargets = ReplyTargets.None,
    /** Reply on a user's message; an answer's is in [actions]. */
    onReply: (() -> Unit)? = null,
    onDismiss: () -> Unit = {},
) {
    when (item.role) {
        ChatItem.Role.USER -> UserBubble(item.text, item.timestamp, replies, modifier, onReply)
        ChatItem.Role.ASSISTANT -> AssistantBlocks(item.blocks, live = false, actions = actions, modifier = modifier)
        ChatItem.Role.SYSTEM -> SystemMessage(item.text, onDismiss, modifier)
    }
}

// Action bar

/**
 * Time, Copy and Reply under a message (`MessageActionBar`). Reply is a 44 px target on a phone,
 * which makes the row that tall. Edit and Fork are not in this app yet.
 */
@Composable
internal fun MessageActionBar(timestamp: Instant?, copyText: String, modifier: Modifier = Modifier, onReply: (() -> Unit)? = null) {
    val tokens = LocalPpmTokens.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (timestamp != null) {
            RelativeTime(
                timestamp,
                ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color, fontFeatureSettings = "tnum"),
                Modifier.padding(end = 4.dp),
            )
        }
        CopyActionButton(copyText)
        if (onReply != null) ActionButton(ProductIcon.RotateCcw, "Reply", touchTarget = true, onClick = onReply)
    }
}

/** "Copy", then "Copied" with a check for 1.5 s. */
@Composable
internal fun CopyActionButton(text: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    ActionButton(if (copied) ProductIcon.Check else ProductIcon.Copy, if (copied) "Copied" else "Copy") {
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
 * and so while pressed. A [touchTarget] is at least 44 px square on a phone, its content centred.
 */
@Composable
internal fun ActionButton(icon: ProductIcon, label: String, touchTarget: Boolean = false, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val hovered by source.collectIsHoveredAsState()
    val lit = (if (pressed) 1f else colorsFade(hovered)).toDouble()
    val color = tokens.text.mix(tokens.text3, lit).color
    val shape = RoundedCornerShape(8.dp)
    val target = if (touchTarget && LocalPpmMobile.current) 44.dp else 0.dp
    Row(
        Modifier
            .clip(shape)
            .background(tokens.panel.opacity(lit).color)
            .hoverable(source)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .defaultMinSize(target, target)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
    ) {
        PpmIcon(icon, 14.dp, color)
        BasicText(label, style = ppmText(12f, lineHeight = 16f).copy(color = color))
    }
}

// Assistant

/** An answer's blocks in order — text, thinking, tool calls — then its action bar. */
@Composable
fun AssistantBlocks(blocks: List<ChatBlock>, live: Boolean, actions: MessageActions?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEachIndexed { index, block ->
                // The turn is streaming: its last text or thinking block is still growing.
                val growing = live && index == blocks.lastIndex
                when (block) {
                    is ChatBlock.Text -> Column {
                        SelectionContainer { MarkdownView(block.text, streaming = growing) }
                        if (growing) StreamingMark()
                    }
                    is ChatBlock.Thinking -> ThinkingBlock(block.text, growing)
                    is ChatBlock.Tool -> ToolCardView(block.tool, turnRunning = live)
                    is ChatBlock.Error -> InlineError(block.message)
                }
            }
        }
        // `-mt-1.5` against the column's `gap-2`: 2 px under the answer.
        if (actions != null) MessageActionBar(actions.timestamp, actions.copyText, Modifier.padding(top = 2.dp), actions.onReply)
    }
}

/** Tailwind's `animate-pulse`: 2 s, down to half opacity and back. */
@Composable
internal fun pulse(): Float {
    val alpha by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(tween(1_000, easing = CubicBezierEasing(0.4f, 0f, 0.6f, 1f)), RepeatMode.Reverse),
        label = "pulse",
    )
    return alpha
}

/** The pulsing "Thinking..." under text that is still streaming. */
@Composable
internal fun StreamingMark() {
    val tokens = LocalPpmTokens.current
    val alpha = pulse()
    BasicText("Thinking...", Modifier.alpha(alpha), ppmText(14f, lineHeight = 20f).copy(color = tokens.text3.color))
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
            .border(1.dp, tokens.error.opacity(0.2).color, shape)
            .padding(horizontal = 9.dp, vertical = 7.dp),
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
            .border(1.dp, tokens.error.opacity(0.2).color, shape)
            .padding(horizontal = 13.dp, vertical = 9.dp),
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
            .border(1.dp, tokens.border.opacity(0.5).color, shape)
            .padding(1.dp),
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
            Column(Modifier.heightIn(max = 240.dp).verticalScroll(scroll).padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                SelectionContainer {
                    MarkdownView(text, style = thinkingStyle.copy(color = tokens.text3.opacity(0.8)), streaming = streaming)
                }
            }
        }
    }
}

// Turn status

/**
 * What the turn is doing before any answer streams, or after a tool ran (`ThinkingIndicator`
 * in message-events.tsx). [waitingSince] is set while nothing has streamed yet: the wait is counted.
 */
@Composable
fun ThinkingIndicator(label: String, waitingSince: Instant?, connecting: Boolean, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val elapsed = waitingSince?.let { Duration.between(it, Instant.ofEpochMilli(now)).seconds.coerceAtLeast(0) } ?: 0
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PpmIcon(ProductIcon.Loader2, 12.dp, tokens.text3.color, spinning = true)
            val text = buildAnnotatedString {
                append(label)
                if (waitingSince != null && elapsed > 0) {
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
    Column(modifier.fillMaxWidth().background(tokens.error.opacity(0.1).color)) {
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(tokens.error.opacity(0.2).color))
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PpmIcon(ProductIcon.AlertCircle, 14.dp, tokens.error.color)
            Column(Modifier.weight(1f)) {
                BasicText(title, style = ppmText(size, FontWeight.Medium, lineHeight = size * 1.375f).copy(color = tokens.error.color))
                if (detail != null) {
                    BasicText(
                        detail,
                        style = ppmText(size, lineHeight = size * 1.375f).copy(color = tokens.text2.color),
                        maxLines = if (mobile) 3 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val shape = RoundedCornerShape(4.dp)
            Row(
                Modifier
                    .heightIn(min = if (mobile) 44.dp else 0.dp)
                    .clip(shape)
                    .background(tokens.bgSolid.color)
                    .border(1.dp, tokens.border.color, shape)
                    .ppmClickable(pressedColor = tokens.panel.color, hoverColor = tokens.panel.color, onClick = onContinue)
                    .padding(horizontal = if (mobile) 17.dp else 11.dp, vertical = 7.dp),
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
