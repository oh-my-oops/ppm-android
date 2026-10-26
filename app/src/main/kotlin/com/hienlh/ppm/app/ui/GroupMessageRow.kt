package com.hienlh.ppm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.chat.chromeBreaks
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFormat
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.protocol.GroupMember
import com.hienlh.ppm.protocol.GroupMessage
import com.hienlh.ppm.protocol.RosterMember

/**
 * What the feed knows of its speakers by name (`colorFor` and `isLeader` of group-chat-tab.tsx):
 * from the roster once it is read, else from the members the socket named.
 */
internal class GroupSpeakers(private val colors: Map<String, Rgba?>, private val leaders: Set<String>) {
    fun color(name: String): Rgba? = colors[name]

    fun isLeader(name: String): Boolean = name in leaders

    companion object {
        fun of(roster: List<GroupMember>, members: List<RosterMember>): GroupSpeakers =
            if (roster.isNotEmpty()) {
                GroupSpeakers(roster.associate { it.name to it.color?.let(Rgba::hex) }, roster.filter { it.isLeader }.mapTo(HashSet()) { it.name })
            } else {
                GroupSpeakers(members.associate { it.name to it.color?.let(Rgba::hex) }, members.filter { it.role == "leader" }.mapTo(HashSet()) { it.name })
            }
    }
}

/** `text-sm leading-relaxed`: 14 px on lines 1.625 of it. */
private const val BODY_LINE = 22.75f

/**
 * A message of the feed (group-message-item.tsx), as Slack lays one out: the speaker's avatar and
 * name in their colour and a bar of it down the left edge, "You" on a wash of the accent for the
 * user, a "leader" tag, whom the reply pulls in next, the time, and the text with each @mention in
 * the mentioned member's colour. [onMenu], for a message with a member's session behind it, is
 * where a long press or a right click asks for its menu: the web's hover icon needs a fine
 * pointer, which a touch tablet never reports, so that menu is the way to the transcript.
 */
@Composable
internal fun GroupMessageRow(message: GroupMessage, speakers: GroupSpeakers, onMenu: ((Offset) -> Unit)?) {
    val tokens = LocalPpmTokens.current
    val user = message.isUser
    val name = if (user) "You" else message.fromMember
    val accent = if (user) tokens.accent else speakers.color(message.fromMember) ?: tokens.accent
    val leader = !user && speakers.isLeader(message.fromMember)
    val target = message.toMember?.takeIf { it.isNotEmpty() && it != "all" }
    val hover = rememberHover()
    // `hover:bg-surface-elevated/50` takes the place of the user's `bg-primary/5`.
    val base = if (user) tokens.accent.opacity(0.05).color else Color.Transparent
    val ring = tokens.bgSolid.color
    Row(
        Modifier
            .fillMaxWidth()
            .hoverable(hover.source)
            .then(if (onMenu != null) Modifier.contextMenuGesture(onMenu) else Modifier)
            .background(lerp(base, tokens.panel2.opacity(0.5).color, hover.lit))
            // `inset 3px 0 0 0 <accent>`: over the fill, under the content.
            .drawBehind { drawRect(accent.color, size = Size(3.dp.toPx(), size.height)) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // `mt-0.5 size-8 rounded-full text-[11px] font-semibold text-white ring-2 ring-background`.
        Box(
            Modifier
                .padding(top = 2.dp)
                .size(32.dp)
                .drawBehind { drawCircle(ring, radius = size.minDimension / 2 + 2.dp.toPx()) }
                .background(accent.color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(initials(name), style = ppmText(11f, FontWeight.SemiBold, lineHeight = 16.5f).copy(color = Color.White), maxLines = 1, softWrap = false)
        }
        Column(Modifier.weight(1f)) {
            // `flex flex-wrap items-center gap-x-2 gap-y-0.5`.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(name, style = ppmText(14f, FontWeight.SemiBold, lineHeight = 20f).copy(color = (if (user) tokens.text else accent).color))
                if (leader) {
                    // `rounded bg-surface-elevated px-1.5 py-px text-[10px] font-medium text-text-secondary`.
                    BasicText(
                        "leader",
                        Modifier.background(tokens.panel2.color, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                        style = ppmText(10f, FontWeight.Medium, lineHeight = 15f).copy(color = tokens.text2.color),
                    )
                }
                val small = ppmText(11f, lineHeight = 16.5f).copy(color = tokens.text3.color)
                if (target != null) BasicText("→ $target", style = small)
                BasicText(message.createdAt?.let(PpmFormat::hourMinute).orEmpty(), style = small)
            }
            // `mt-0.5 whitespace-pre-wrap break-words text-sm leading-relaxed text-text-secondary`; an
            // empty one has no line at all, and a final line break starts none.
            val summary = message.summary.orEmpty()
            if (summary.isEmpty()) {
                Spacer(Modifier.height(2.dp))
            } else {
                val body = remember(summary, speakers, tokens.accent) { chromeBreaks(withMentions(summary.removeSuffix("\n"), speakers, tokens.accent)) }
                BasicText(body, Modifier.padding(top = 2.dp), style = ppmText(14f, lineHeight = BODY_LINE).copy(color = tokens.text2.color))
            }
        }
    }
}

/** `initials`: the first and last words' first letters, or a lone word's first two, upper-cased; "?" for no word. */
internal fun initials(name: String): String {
    val parts = name.trim().split(JS_SPACE).filter { it.isNotEmpty() }
    return when (parts.size) {
        0 -> "?"
        1 -> parts[0].take(2).uppercase()
        else -> (parts[0].take(1) + parts.last().take(1)).uppercase()
    }
}

/** What JavaScript's `\s` matches. */
private val JS_SPACE = Regex("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+")

/** `@` and a name of letters, digits and underscores (`/(@[\p{L}\p{N}_]+)/gu`). */
private val MENTION = Regex("@[\\p{L}\\p{N}_]+")

/**
 * `withMentions`: the text split around each mention, and every piece that starts with "@" and has
 * more after it in semibold, in the colour of the member it names or else the accent — a piece the
 * split left between mentions included, as the web's test does not tell them apart.
 */
private fun withMentions(text: String, speakers: GroupSpeakers, fallback: Rgba): AnnotatedString {
    val parts = ArrayList<String>()
    var last = 0
    for (match in MENTION.findAll(text)) {
        parts += text.substring(last, match.range.first)
        parts += match.value
        last = match.range.last + 1
    }
    parts += text.substring(last)
    return buildAnnotatedString {
        for (part in parts) {
            if (part.startsWith("@") && part.length > 1) {
                val color = speakers.color(part.substring(1)) ?: fallback
                withStyle(SpanStyle(color = color.color, fontWeight = FontWeight.SemiBold)) { append(part) }
            } else {
                append(part)
            }
        }
    }
}
