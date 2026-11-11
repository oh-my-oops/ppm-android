package com.hienlh.ppm.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
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
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.double
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * The `ppm-sessions` tools as a card (`session-collab-tool-card.tsx`, `session-collab-tool-call.ts`):
 * which session was created or messaged, whether the AI is still waiting on it, its answer once it
 * came (or why none did), and a way to open that session. Everything is from an AI or the tool's
 * own output, so every field is checked; anything malformed leaves the raw text to show instead.
 */

/** A call of `create_session`, `send_to_session` or `session_status` (`SessionCollabToolCall`). */
internal sealed interface SessionCollabToolCall {
    data class Create(val title: String, val task: String, val provider: String?, val model: String?, val wait: Boolean) : SessionCollabToolCall

    data class Send(val sessionId: String, val message: String, val wait: Boolean) : SessionCollabToolCall

    data class Status(val sessionIds: List<String>) : SessionCollabToolCall

    val waits: Boolean
        get() = when (this) {
            is Create -> wait
            is Send -> wait
            is Status -> false
        }
}

/** One session as `session_status` reports it (`SessionStatus`), the fields the card shows. */
internal data class CollabSessionStatus(
    val sessionId: String,
    val title: String,
    val providerId: String,
    val phase: String,
    val waitingFor: String?,
    val unreadReplies: Int,
)

/** Why a wait ended: the other session's reply, or the reason there was none (`SessionWaitOutcome`). */
internal sealed interface CollabReply {
    data class Replied(val text: String, val turnId: String?) : CollabReply

    data class None(val reason: String) : CollabReply
}

/** What the tool answered, when its output is the server's JSON (`SessionCollabToolResult`). */
internal data class SessionCollabToolResult(
    val sessionId: String? = null,
    val title: String? = null,
    val providerId: String? = null,
    val delivered: String? = null,
    val status: CollabSessionStatus? = null,
    val reply: CollabReply? = null,
    val sessions: List<CollabSessionStatus>? = null,
)

/** Where a card stands (`SessionCollabCardState`). */
internal enum class CollabCardState { RUNNING, WAITING, ANSWERED, ERROR, STOPPED }

private val COLLAB_TOOL_NAME = Regex("""^(?:mcp__ppm-sessions__|ppm_sessions:)(create_session|send_to_session|session_status)$""")
private val WAIT_REASONS = setOf("turn_ended_without_reply", "timeout", "cancelled")
private val DELIVERIES = setOf("inserted", "queued", "started")

private fun JsonElement?.nonBlank(): String? = string?.takeIf { it.isNotBlank() }

/** The call an event is, under either provider's name, or null (`sessionCollabToolCall`). */
internal fun sessionCollabToolCall(name: String, input: JsonElement?): SessionCollabToolCall? {
    val tool = COLLAB_TOOL_NAME.matchEntire(name)?.groupValues?.get(1) ?: return null
    if (input !is JsonObject) return null
    val args = (input["arguments"] as? JsonObject) ?: input
    val wait = args["wait"].bool == true
    return when (tool) {
        "create_session" -> SessionCollabToolCall.Create(
            title = args["title"].nonBlank() ?: "New session",
            task = args["task"].string ?: "",
            provider = args["provider"].nonBlank(),
            model = args["model"].nonBlank(),
            wait = wait,
        )
        "send_to_session" -> args["session_id"].nonBlank()?.let { SessionCollabToolCall.Send(it, args["message"].string ?: "", wait) }
        else -> SessionCollabToolCall.Status((args["session_ids"] as? JsonArray).orEmpty().mapNotNull { it.nonBlank() })
    }
}

private fun readStatus(value: JsonElement?): CollabSessionStatus? {
    if (value !is JsonObject) return null
    val sessionId = value["sessionId"].string ?: return null
    val phase = value["phase"].string ?: return null
    return CollabSessionStatus(
        sessionId = sessionId,
        title = value["title"].string ?: "Untitled session",
        providerId = value["providerId"].string ?: "",
        phase = phase,
        waitingFor = value["waitingFor"].string?.takeIf { it == "approval" || it == "question" },
        unreadReplies = value["unreadReplies"].double?.toInt() ?: 0,
    )
}

private fun readReply(value: JsonElement?): CollabReply? {
    if (value !is JsonObject) return null
    val replied = value["replied"].bool
    if (replied == true) return value["text"].string?.let { CollabReply.Replied(it, value["turnId"].string) }
    if (replied == false) return value["reason"].string?.takeIf { it in WAIT_REASONS }?.let(CollabReply::None)
    return null
}

/** The tool's answer as the server wrote it, or null when the output is not that JSON (`sessionCollabToolResult`). */
internal fun sessionCollabToolResult(output: String): SessionCollabToolResult? {
    val raw = tabToolResultText(output).trim()
    if (!raw.startsWith("{")) return null
    val parsed = WireJson.parseOrNull(raw) as? JsonObject ?: return null
    val status = readStatus(parsed["status"])
    val sessions = (parsed["sessions"] as? JsonArray)?.mapNotNull(::readStatus)
    val result = SessionCollabToolResult(
        sessionId = (parsed["sessionId"].string ?: status?.sessionId)?.takeIf { it.isNotEmpty() },
        title = parsed["title"].string ?: status?.title,
        providerId = (parsed["providerId"].string ?: status?.providerId?.takeIf { it.isNotEmpty() })?.takeIf { it.isNotEmpty() },
        delivered = parsed["delivered"].string?.takeIf { it in DELIVERIES },
        status = status,
        reply = readReply(parsed["reply"]),
        sessions = sessions,
    )
    return result.takeIf { it != SessionCollabToolResult() }
}

/** A stopped turn never streams the MCP answer, so a finished turn with no result was cut short (`sessionCollabCardState`). */
internal fun collabCardState(call: SessionCollabToolCall, done: Boolean, isError: Boolean, hasOutput: Boolean): CollabCardState = when {
    isError -> CollabCardState.ERROR
    hasOutput -> CollabCardState.ANSWERED
    done -> CollabCardState.STOPPED
    call.waits -> CollabCardState.WAITING
    else -> CollabCardState.RUNNING
}

/** Why a wait ended without a reply (`waitEndedText`). */
internal fun waitEndedText(reason: String): String = when (reason) {
    "turn_ended_without_reply" -> "It finished its turn without replying."
    "timeout" -> "No reply before the wait timed out."
    else -> "The wait was cancelled."
}

/** What a stopped card says (`stoppedCallText`). */
internal fun stoppedCallText(call: SessionCollabToolCall): String =
    if (call.waits) "Wait cancelled: the turn was stopped before a reply came." else "The turn ended before this tool answered."

/** A session's phase and what it waits on, in words (`sessionStatusText`). */
internal fun sessionStatusText(status: CollabSessionStatus): String = when {
    status.waitingFor == "approval" -> "Needs approval"
    status.waitingFor == "question" -> "Has a question"
    status.phase == "idle" -> "Idle"
    else -> "Running"
}

private val xs = ppmText(12f, lineHeight = 16f)
private val tiny = ppmText(10f, lineHeight = 40f / 3)

@Composable
internal fun SessionCollabToolCard(
    call: SessionCollabToolCall,
    output: String?,
    isError: Boolean,
    done: Boolean,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val opener = LocalChatSessionOpener.current
    val chat = LocalAgentSessionOpener.current?.identity
    val project = chat?.projectName.orEmpty()
    var expanded by remember { mutableStateOf(false) }
    val result = remember(output, isError) { if (output == null || isError) null else sessionCollabToolResult(output) }
    val rawText = remember(output) { output?.let(::tabToolResultText).orEmpty() }
    val targetId = when (call) {
        is SessionCollabToolCall.Create -> result?.sessionId
        is SessionCollabToolCall.Send -> call.sessionId
        is SessionCollabToolCall.Status -> null
    }
    val title = result?.title ?: (call as? SessionCollabToolCall.Create)?.title
    val providerId = result?.providerId ?: (call as? SessionCollabToolCall.Create)?.provider ?: chat?.providerId
    val state = collabCardState(call, done, isError, output != null)
    val stopped = state == CollabCardState.STOPPED
    val reply = result?.reply
    val (verb, icon) = when (call) {
        is SessionCollabToolCall.Create -> "Created" to ProductIcon.UserPlus
        is SessionCollabToolCall.Send -> "Message to" to ProductIcon.MessageSquare
        is SessionCollabToolCall.Status -> "Session status" to ProductIcon.Search
    }
    fun open(sessionId: String, provider: String?, name: String?, turnId: String? = null) {
        opener?.open(ChatSessionTarget(project, provider.orEmpty(), sessionId, name ?: "", turnId))
    }
    val shape = RoundedCornerShape(11.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.panel.color)
            .cssBorder(tokens.info.opacity(0.3).color, shape),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = if (mobile) 44.dp else 0.dp)
                    .ppmClickable(pressedColor = tokens.panel2.opacity(0.4).color, hoverColor = tokens.panel2.opacity(0.4).color) { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ToolChip(ToolChipStyle(icon, tokens.info.opacity(0.15), tokens.info))
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val medium = ppmText(12f, FontWeight.Medium, lineHeight = 16f)
                    BasicText(verb, style = medium.copy(color = tokens.text.color), maxLines = 1)
                    if (call !is SessionCollabToolCall.Status) {
                        if (title != null) {
                            BasicText(title, Modifier.weight(1f, fill = false), medium.copy(color = tokens.text3.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (!providerId.isNullOrEmpty()) ProviderLogo(providerId, 16.dp, tokens.text.color)
                    } else {
                        result?.sessions?.let { list ->
                            BasicText("· ${list.size} ${if (list.size == 1) "session" else "sessions"}", style = medium.copy(color = tokens.text3.color))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        state == CollabCardState.WAITING -> BasicText("waiting for reply…", style = tiny.copy(color = tokens.accent.color))
                        reply is CollabReply.Replied -> BasicText("replied", style = tiny.copy(color = tokens.success.color))
                        reply is CollabReply.None -> BasicText("no reply", style = tiny.copy(color = tokens.warning.color))
                        stopped -> BasicText(if (call.waits) "wait cancelled" else "stopped", style = tiny.copy(color = tokens.warning.color))
                    }
                    when (state) {
                        CollabCardState.ERROR -> StatusIcon(Status.FAILED)
                        CollabCardState.STOPPED -> PpmIcon(ProductIcon.AlertCircle, 14.dp, tokens.warning.color)
                        CollabCardState.ANSWERED -> StatusIcon(Status.DONE)
                        else -> StatusIcon(Status.RUNNING)
                    }
                    PpmIcon(if (expanded) ProductIcon.ChevronDown else ProductIcon.ChevronRight, 12.dp, tokens.text3.color)
                }
            }
            if (targetId != null && opener != null) {
                val label = "Open ${title ?: "the session"}"
                Box(
                    Modifier
                        .padding(end = 4.dp)
                        .height(if (mobile) 44.dp else 28.dp)
                        .widthIn(min = if (mobile) 44.dp else 0.dp)
                        .webTitle(label)
                        .ppmClickable(pressedColor = tokens.accentWash.color, hoverColor = tokens.accentWash.color, shape = RoundedCornerShape(6.dp), onClickLabel = label) {
                            open(targetId, providerId, title)
                        }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText("Open session", style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.accent.color))
                }
            }
        }
        val note = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp)
        if (isError && rawText.isNotEmpty()) BasicText(rawText, note, xs.copy(color = tokens.error.color))
        if (reply is CollabReply.None) BasicText(waitEndedText(reply.reason), note, xs.copy(color = tokens.text3.color))
        if (stopped) BasicText(stoppedCallText(call), note, xs.copy(color = tokens.text3.color))
        if (reply is CollabReply.Replied) {
            CollabReplyBlock(reply.text, onOpen = if (targetId != null && opener != null) ({ open(targetId, providerId, title, reply.turnId) }) else null)
        }
        if (expanded) {
            Column(note, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (call) {
                    is SessionCollabToolCall.Create -> if (call.task.isNotEmpty()) Excerpt("Task", call.task)
                    is SessionCollabToolCall.Send -> if (call.message.isNotEmpty()) Excerpt("Message", call.message)
                    is SessionCollabToolCall.Status -> Unit
                }
                if (call !is SessionCollabToolCall.Status) result?.status?.let { StatusLine(it, null) }
                result?.sessions?.forEach { status ->
                    StatusLine(status, if (opener != null) ({ open(status.sessionId, status.providerId, status.title) }) else null)
                }
                if (result == null && !isError && rawText.isNotEmpty()) {
                    Box(Modifier.fillMaxWidth().heightIn(max = 240.dp).cssScrollY()) { Mono(rawText, tokens.text3) }
                }
            }
        }
    }
}

/** The other session's answer, two lines until opened (`ReplyBlock`). */
@Composable
private fun CollabReplyBlock(text: String, onOpen: (() -> Unit)?) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    var open by remember(text) { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    val clamp = with(LocalDensity.current) { 32.dp.roundToPx() }
    Column(
        Modifier
            .padding(start = 10.dp, end = 10.dp, bottom = 8.dp)
            .fillMaxWidth()
            .background(tokens.bgSolid.opacity(0.6).color, shape)
            .cssBorder(tokens.border.color, shape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        val body = if (open) {
            Modifier
        } else {
            Modifier.clipToBounds().layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(maxHeight = Int.MAX_VALUE))
                layout(placeable.width, minOf(placeable.height, clamp)) { placeable.place(0, 0) }
            }
        }
        SelectionContainer(body) { MarkdownContent(text, rawHtml = false) }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val link = xs.copy(color = tokens.accent.opacity(0.8).color)
            val target = Modifier.heightIn(min = if (mobile) 44.dp else 0.dp)
            Box(target.ppmClickable(pressedColor = Color.Transparent) { open = !open }, contentAlignment = Alignment.Center) {
                BasicText(if (open) "Show less" else "Show reply", style = link)
            }
            if (onOpen != null) {
                Box(target.ppmClickable(pressedColor = Color.Transparent, onClick = onOpen), contentAlignment = Alignment.Center) {
                    BasicText("Open the turn that replied", style = link)
                }
            }
        }
    }
}

/** `Excerpt`: its label, then the text, six lines at most. */
@Composable
private fun Excerpt(label: String, text: String) {
    val tokens = LocalPpmTokens.current
    Column {
        BasicText(label, style = xs.copy(color = tokens.text3.color))
        BasicText(text, style = xs.copy(color = tokens.text2.color), maxLines = 6, overflow = TextOverflow.Ellipsis)
    }
}

/** One session's line: its provider, title and phase, opening it when it can (`StatusLine`). */
@Composable
private fun StatusLine(status: CollabSessionStatus, onOpen: (() -> Unit)?) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (onOpen != null && LocalPpmMobile.current) 44.dp else 0.dp)
            .then(if (onOpen != null) Modifier.ppmClickable(pressedColor = tokens.panel2.opacity(0.4).color, onClick = onOpen) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (status.providerId.isNotEmpty()) ProviderLogo(status.providerId, 16.dp, tokens.text.color) else Box(Modifier.size(16.dp))
        BasicText(status.title, Modifier.weight(1f, fill = false), ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText("· ${sessionStatusText(status)}", style = xs.copy(color = tokens.text3.color), maxLines = 1)
        if (status.unreadReplies > 0) BasicText("· ${status.unreadReplies} unread", style = xs.copy(color = tokens.info.color), maxLines = 1)
    }
}
