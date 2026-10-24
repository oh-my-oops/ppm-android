package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.AgentSource
import com.hienlh.ppm.protocol.ChatEvent
import com.hienlh.ppm.protocol.ToolOutcome
import com.hienlh.ppm.protocol.agentSessionKey
import com.hienlh.ppm.protocol.agentTranscriptProvider
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.windows.MAX_WINDOWS
import com.hienlh.ppm.state.windows.Rect
import com.hienlh.ppm.state.windows.WindowKind
import com.hienlh.ppm.state.windows.WindowRuntimeState
import com.hienlh.ppm.state.windows.WindowsState
import com.hienlh.ppm.state.windows.portraitSpawnRect
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * An agent's or teammate's session (`use-open-agent-session.ts`, `agent-session-window-content.tsx`):
 * a floating window on a tablet, the full-height sheet on a phone, both showing the same steps.
 */

/** The session a chat's cards belong to (`AgentSessionIdentity`); an empty [sessionId] opens nothing. */
data class AgentSessionIdentity(val projectName: String, val providerId: String, val sessionId: String)

/**
 * What a window or sheet opens a session with (`AgentSessionWindowPayload`): the identity the hub
 * subscribes on, and what the opener already knows to show. A window keeps it as its payload.
 */
data class AgentSessionPayload(
    val projectName: String,
    /** `claude` or `codex`, the only hubs there are. */
    val providerId: String,
    val sessionId: String,
    val source: AgentSource,
    /** The window's and sheet's title: a teammate's handle, the card's description. */
    val title: String? = null,
    /** The Agent/Task call's own prompt, a row that folds open above the steps. */
    val prompt: String? = null,
) {
    /** One window or sheet per session: opening the same key again focuses it. */
    val key: String get() = agentSessionKey(sessionId, source)

    fun toJson(): JsonObject = buildJsonObject {
        put("projectName", projectName)
        put("providerId", providerId)
        put("sessionId", sessionId)
        put("source", source.toJson())
        title?.let { put("title", it) }
        prompt?.let { put("prompt", it) }
    }

    companion object {
        /** A window's payload read back; null when it names no session and source. */
        fun from(json: JsonObject?): AgentSessionPayload? {
            val sessionId = json["sessionId"].string ?: return null
            val source = AgentSource.from(json["source"]) ?: return null
            return AgentSessionPayload(
                json["projectName"].string.orEmpty(),
                agentTranscriptProvider(json["providerId"].string),
                sessionId,
                source,
                json["title"].string,
                json["prompt"].string,
            )
        }
    }
}

/**
 * What tapping an Agent/Task card opens: that call's own session, named by its handle or its
 * description. A chat with no identity of its own still opens the card, under a session id the
 * hub can never find, so the window shows the steps the card holds.
 */
fun agentCardPayload(tool: ToolBlock, identity: AgentSessionIdentity?, projectName: String?): AgentSessionPayload {
    val cardId = tool.toolUseId.orEmpty()
    val owner = identity ?: AgentSessionIdentity(projectName.orEmpty(), agentTranscriptProvider(null), "local:${cardId.ifEmpty { "card" }}")
    val handle = addressableName(tool.input)
    val description = if (handle != null) "" else truncated(tool.input["description"].string ?: tool.input["prompt"].string ?: "", 60)
    return AgentSessionPayload(
        owner.projectName,
        owner.providerId,
        owner.sessionId,
        AgentSource.Card(cardId),
        title = if (handle != null) "Session — $handle" else description.ifEmpty { "Agent session" },
        prompt = tool.input["prompt"].string ?: tool.input["description"].string ?: "",
    )
}

private fun truncated(value: String, max: Int) = if (value.length > max) value.take(max) + "…" else value

/** What a row of the running agents bar opens: its card's session, or the teammate's of [teamName]. */
fun RunningAgentView.payload(identity: AgentSessionIdentity, teamName: String): AgentSessionPayload = AgentSessionPayload(
    identity.projectName,
    identity.providerId,
    identity.sessionId,
    row.cardId?.let { AgentSource.Card(it) } ?: AgentSource.Member(teamName, row.memberName.orEmpty()),
    title = "Session — $name",
    prompt = prompt,
)

/** What an open does, given the windows open now. */
sealed interface AgentSessionOpening {
    /** A phone: the sheet shows it. */
    data object Sheet : AgentSessionOpening

    /** Its window is open already, and comes forward. */
    data class Focus(val windowId: String) : AgentSessionOpening

    /**
     * A new window at [rect]. At the window cap the oldest agent window, [closeFirst], gives way —
     * never a window the user still has open for something else.
     */
    data class Open(val rect: Rect, val closeFirst: String? = null) : AgentSessionOpening

    /** At the cap with no agent window to give way: "Too many windows open". */
    data object Refused : AgentSessionOpening
}

/**
 * How the session [key] opens over [windows]. Decided before anything opens, because
 * `WindowStore.open` at the cap brings back the oldest window instead of opening one.
 */
fun agentSessionOpening(windows: WindowsState, key: String, sheet: Boolean): AgentSessionOpening {
    if (sheet) return AgentSessionOpening.Sheet
    val all = windows.windows.values
    all.firstOrNull { it.agentSessionKey == key }?.let { return AgentSessionOpening.Focus(it.id) }
    if (all.size < MAX_WINDOWS) return AgentSessionOpening.Open(portraitSpawnRect(all.map { it.rect }, windows.bounds))
    val oldest = all.filter { it.kind == WindowKind.AgentSession }.minByOrNull { it.rank } ?: return AgentSessionOpening.Refused
    return AgentSessionOpening.Open(portraitSpawnRect(all.filter { it.id != oldest.id }.map { it.rect }, windows.bounds), oldest.id)
}

/** The session an agent window shows; null for any other window. */
private val WindowRuntimeState.agentSessionKey: String?
    get() = if (kind == WindowKind.AgentSession) AgentSessionPayload.from(payload)?.key else null

/** The sessions on screen: each agent window's, and the sheet's. */
fun agentSessionKeysOnScreen(windows: Collection<WindowRuntimeState>, sheet: AgentSessionPayload?): Set<String> =
    windows.mapNotNullTo(LinkedHashSet()) { it.agentSessionKey }.apply { sheet?.let { add(it.key) } }

/**
 * The steps a chat already held for each session (`agent-session-fallback-store.ts`), shown until —
 * or unless — the hub streams it, since a window never had the card to read them from. The opener
 * seeds them; [pruned] drops every session no window or sheet shows any more, so one opened once
 * does not keep its steps alive for the rest of the app's life.
 */
data class AgentSessionFallbacks(val entries: Map<String, List<ChatEvent>> = emptyMap()) {
    operator fun get(key: String): List<ChatEvent>? = entries[key]

    fun with(key: String, events: List<ChatEvent>) = copy(entries = entries + (key to events))

    fun pruned(onScreen: Set<String>) = if (entries.keys.all { it in onScreen }) this else copy(entries = entries.filterKeys { it in onScreen })
}

/** The badge in a session's header: nothing while loading, then running, failed or done. */
enum class AgentSessionStatus { LOADING, RUNNING, FAILED, DONE }

/**
 * Best effort: a transcript has no end marker of its own, so a stream that stopped running on a
 * failed result reads as failed.
 */
fun agentSessionStatus(events: List<ChatEvent>, loading: Boolean, running: Boolean): AgentSessionStatus = when {
    loading -> AgentSessionStatus.LOADING
    running -> AgentSessionStatus.RUNNING
    (events.lastOrNull() as? ChatEvent.ToolResult)?.outcome?.isError == true -> AgentSessionStatus.FAILED
    else -> AgentSessionStatus.DONE
}

/** One row of a session's steps (`SubagentChildren`): text run together, or a call and its result. */
sealed interface AgentStepRow {
    data class Text(val text: String) : AgentStepRow

    data class Tool(val use: ChatEvent.ToolUse, val result: ToolOutcome? = null) : AgentStepRow
}

/**
 * [events] as rows: consecutive text joins up to the next call, a result goes on the first call of
 * its id still without one (or, with no id, the last such call), and nothing else is drawn.
 */
fun agentStepRows(events: List<ChatEvent>): List<AgentStepRow> {
    val rows = ArrayList<AgentStepRow>()
    val text = StringBuilder()
    for (event in events) {
        when (event) {
            is ChatEvent.Text -> text.append(event.text)
            is ChatEvent.ToolUse -> {
                if (text.isNotEmpty()) {
                    rows += AgentStepRow.Text(text.toString())
                    text.clear()
                }
                rows += AgentStepRow.Tool(event)
            }
            is ChatEvent.ToolResult -> {
                val id = event.toolUseId
                val open = { row: AgentStepRow -> row is AgentStepRow.Tool && row.result == null }
                val at = if (!id.isNullOrEmpty()) {
                    rows.indexOfFirst { open(it) && (it as AgentStepRow.Tool).use.toolUseId == id }
                } else {
                    rows.indexOfLast(open)
                }
                if (at >= 0) rows[at] = (rows[at] as AgentStepRow.Tool).copy(result = event.outcome)
            }
            else -> Unit
        }
    }
    if (text.isNotEmpty()) rows += AgentStepRow.Text(text.toString())
    return rows
}
