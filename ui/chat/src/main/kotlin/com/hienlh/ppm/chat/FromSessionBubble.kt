package com.hienlh.ppm.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ProviderLogo
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssScrollY
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.protocol.FromSessionReference
import com.hienlh.ppm.protocol.generated.TurnReference
import java.time.Instant

/** A session to open from the transcript: a from-session bubble's sender, a turn chip's session. */
data class ChatSessionTarget(
    val projectName: String,
    val providerId: String,
    val sessionId: String,
    val title: String,
    /** The turn to bring on screen, when known. */
    val turnId: String? = null,
)

/** Opens another chat session (`openLinkedSession` / `openTurnRef`). */
fun interface ChatSessionOpener {
    fun open(target: ChatSessionTarget)
}

/** How this chat opens other sessions; none, and the header and chips only show where they lead. */
val LocalChatSessionOpener = staticCompositionLocalOf<ChatSessionOpener?> { null }

/** The project of the chat being read, for a chip to name another one (`currentProject`). */
@Composable
private fun currentProject(): String? = LocalAgentSessionOpener.current?.identity?.projectName?.takeIf { it.isNotEmpty() }

/**
 * A message another PPM session's AI sent into this chat (`from-session-bubble.tsx`): drawn apart
 * from the person's own bubbles — its own colours, on the left, headed with who sent it — and
 * offering only Copy: there is nothing of the person's to edit or fork. The header opens the
 * sending session. Drawn from the message's own block, so it needs no request.
 */
@Composable
internal fun FromSessionBubble(
    from: FromSessionReference,
    text: String,
    plainContent: String,
    turnRefs: List<TurnReference>?,
    timestamp: Instant?,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val opener = LocalChatSessionOpener.current
    val rad = tokens.rad.dp
    val shape = RoundedCornerShape(topStart = rad, topEnd = rad, bottomEnd = rad, bottomStart = 4.dp)
    val info = tokens.info
    Column(modifier.fillMaxWidth().semantics { contentDescription = "From session ${from.title}" }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // `max-w-[85%]`, as wide as what it holds.
        Column(
            Modifier
                .fillMaxWidth(0.85f)
                .wrapContentWidth(Alignment.Start)
                .background(info.opacity(0.1).color, shape)
                .cssBorder(info.opacity(0.3).color, shape)
                .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
        ) {
            val hover = rememberHover()
            val target = ChatSessionTarget(from.projectName, from.providerId, from.sessionId, from.title, from.turnId)
            Row(
                Modifier
                    .heightIn(min = if (LocalPpmMobile.current) 44.dp else 32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(info.opacity(0.1 * hover.lit).color)
                    .then(if (opener != null) Modifier.ppmClickable(pressedColor = info.opacity(0.1).color, source = hover.source) { opener.open(target) } else Modifier)
                    .semantics { contentDescription = if (from.turnId != null) "Open \"${from.title}\" at the turn that sent this" else "Open \"${from.title}\"" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val xs = ppmText(12f, lineHeight = 16f).copy(color = info.color)
                PpmIcon(ProductIcon.MessageSquare, 14.dp, info.color)
                BasicText("From:", style = xs)
                BasicText(from.title, Modifier.weight(1f, fill = false), xs.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText("·", Modifier.alpha(0.7f), xs)
                ProviderLogo(from.providerId, 16.dp, tokens.text.color)
                BasicText(providerLabel(from.providerId), Modifier.alpha(0.8f), xs)
            }
            if (!turnRefs.isNullOrEmpty()) TurnRefChipRow(turnRefs)
            // Folds like the person's own bubble; raw HTML off, as another session's AI wrote it.
            if (text.isNotEmpty()) CollapsibleMarkdown(text, toggle = info)
        }
        MessageActionBar(timestamp, plainContent) { CopyTurnLinkButton() }
    }
}

/**
 * The body clamped to two lines until "Show more" (`CollapsibleMessageBody`), then up to half the
 * window, scrolling. The toggle shows only when the clamp hides something: the body is measured.
 */
@Composable
private fun CollapsibleMarkdown(text: String, toggle: com.hienlh.ppm.design.Rgba) {
    var expanded by remember(text) { mutableStateOf(false) }
    var natural by remember(text) { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val clampPx = with(density) { 40.dp.roundToPx() }
    val half = with(density) { (LocalWindowInfo.current.containerSize.height / 2).toDp() }
    val body = if (expanded) {
        Modifier.heightIn(max = half).cssScrollY()
    } else {
        Modifier
            .clipToBounds()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(maxHeight = Int.MAX_VALUE))
                natural = placeable.height
                val height = minOf(placeable.height, clampPx).coerceAtLeast(constraints.minHeight)
                layout(placeable.width, height) { placeable.place(0, 0) }
            }
    }
    SelectionContainer(body) {
        MarkdownContent(text, rawHtml = false)
    }
    val overflowing = natural > clampPx + with(density) { 2.dp.roundToPx() }
    if (overflowing || expanded) {
        val hover = rememberHover()
        val color = toggle.mix(toggle.opacity(0.8), hover.lit.toDouble()).color
        Row(
            Modifier.padding(top = 4.dp).ppmClickable(pressedColor = Color.Transparent, source = hover.source) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PpmIcon(if (expanded) ProductIcon.ChevronUp else ProductIcon.ChevronDown, 12.dp, color)
            BasicText(if (expanded) "Show less" else "Show more", style = ppmText(12f, lineHeight = 16f).copy(color = color))
        }
    }
}

/** The chips a sent message carries, read from its block (`TurnRefChipRow`): `mb-1.5 flex flex-wrap gap-1.5`. */
@Composable
internal fun TurnRefChipRow(refs: List<TurnReference>, modifier: Modifier = Modifier) {
    val project = currentProject()
    FlowRow(modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (ref in refs) TurnRefChipView(ref, project)
    }
}

