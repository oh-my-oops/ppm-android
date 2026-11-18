package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.ApprovalRequest
import com.hienlh.ppm.protocol.BackgroundShell
import com.hienlh.ppm.protocol.ChatClientMessage
import com.hienlh.ppm.protocol.ChatEvent
import com.hienlh.ppm.protocol.ChatFrame
import com.hienlh.ppm.protocol.ChatReply
import com.hienlh.ppm.protocol.ChatTurnRefs
import com.hienlh.ppm.protocol.EffortStatusReport
import com.hienlh.ppm.protocol.HistoryMessage
import com.hienlh.ppm.protocol.HistoryPage
import com.hienlh.ppm.protocol.InlineImage
import com.hienlh.ppm.protocol.ReplyReference
import com.hienlh.ppm.protocol.Reported
import com.hienlh.ppm.protocol.SessionPhase
import com.hienlh.ppm.protocol.SessionStateFrame
import com.hienlh.ppm.protocol.TabOpenRequest
import com.hienlh.ppm.protocol.TeamMessage
import com.hienlh.ppm.protocol.TurnOptions
import com.hienlh.ppm.protocol.TurnSnapshot
import com.hienlh.ppm.protocol.TurnStop
import com.hienlh.ppm.protocol.TurnUsage
import com.hienlh.ppm.protocol.VersionGroup
import com.hienlh.ppm.protocol.generated.TurnReference
import kotlinx.serialization.json.JsonElement
import java.time.Instant

/** Something the transcript needs done outside itself. */
sealed interface ChatEffect {
    data class Send(val message: ChatClientMessage) : ChatEffect
    data object ReloadHistory : ChatEffect
    data class AdoptSessionId(val id: String) : ChatEffect

    /** The session has the agent team [name]: worth following, and its inbox history worth reading (`team_detected`). */
    data class TeamDetected(val name: String) : ChatEffect

    /** Messages a team's inbox just received (`team_inbox`). */
    data class TeamMessages(val messages: List<TeamMessage>) : ChatEffect

    /** The AI's tab tool asked this device for a tab; it waits for the answer, which must always come. */
    data class OpenTab(val request: TabOpenRequest) : ChatEffect

    /** Worth a sound, and for an approval a toast, when the chat is not in front of the user. */
    data class Attention(val attention: ChatAttention) : ChatEffect
}

/** A message the server refused: what was typed, what it quoted, and why ([message]). */
data class RejectedSend(
    val content: String,
    val replyTo: ReplyReference?,
    val message: String,
    /** The turn chips it carried, which go back to the composer with it. */
    val turnRefs: List<TurnReference>? = null,
)

/**
 * What one chat shows, rebuilt from history pages and chat-socket frames. No I/O: frames go in,
 * effects come out, so every rule here runs in a plain unit test. It follows
 * `src/web/hooks/use-chat.ts`. Not thread-safe: one thread (the main one) drives it.
 */
class ChatTranscript(var providerId: String? = null) {
    var items: List<ChatItem> = emptyList()
        private set

    /** The assistant message being streamed; it joins [items] at `done`. */
    var live: TurnBuilder? = null
        private set

    /** When [live] got its first event. */
    var liveStartedAt = 0L
        private set
    var phase: SessionPhase = SessionPhase.IDLE
        private set
    var pendingApproval: ApprovalRequest? = null
        private set

    /**
     * What each shell call of the live turn has printed while it runs, by call (`bash_output`),
     * until its result comes, the turn ends or it is stopped.
     */
    var bashOutput: Map<String, BashPartial> = emptyMap()
        private set

    /** Kept out of [items] so that a history reload cannot erase it. */
    var turnStop: TurnStop? = null
        private set
    var title: String? = null
        private set
    var compacting = false
        private set

    /** Seconds the server has waited for a first answer; 0 outside `connecting` (`connectingElapsed`). */
    var connectingElapsed = 0
        private set

    /** What PPM is doing ahead of the provider (`status_update`), shown in place of the phase until the turn's account is known. */
    var statusMessage: String? = null
        private set

    /** A send the server refused, as it was typed and quoted, until the composer takes it back ([takeRejected]). */
    var rejected: RejectedSend? = null
        private set

    /** The commands the AI left running in the background, stopped ones included, as the server's last `background_registry` listed them. */
    var backgroundShells: List<BackgroundShell> = emptyList()
        private set

    /** The MCP servers the session's Claude cannot use until someone signs in, as the server last listed them. */
    var mcpNeedsAuth: List<String> = emptyList()
        private set

    /** What decides the effort the session runs at, as the server last said; null while nothing is known. */
    var effortStatus: EffortStatusReport? = null
        private set

    /**
     * Which turn each message sent in this chat opened or joined, by the id it was sent under
     * (`turn-binding-store.ts`): from `turn_started`, or replayed in `session_state` to a socket
     * that joined late. Covers the stretch between a send and the history reload that stamps the
     * message with its turn. Newest last, at most [MAX_TURN_BINDINGS].
     */
    var turnBindings: Map<String, String> = emptyMap()
        private set

    private fun bindTurn(clientMessageId: String, turnId: String) {
        if (turnBindings[clientMessageId] == turnId) return
        val next = LinkedHashMap(turnBindings)
        next.remove(clientMessageId)
        next[clientMessageId] = turnId
        while (next.size > MAX_TURN_BINDINGS) next.remove(next.keys.first())
        turnBindings = next
    }

    /** Messages sent while a turn ran, not yet read by the AI: the server's list. */
    val queue = MessageQueue()

    /** Sends the server may still refuse, by id: what a refusal hands back. Kept a minute, 20 at most. */
    private val unanswered = LinkedHashMap<String, Unanswered>()

    /** Index of the first loaded message in the whole transcript; above 0, older ones exist. */
    var historyStart = 0
        private set
    var hasLoadedHistory = false
        private set

    /** The user messages above [historyStart] (`userOrdinalOffset`), so that a window numbers its own as the whole transcript would. */
    var userOrdinalOffset = 0
        private set

    /** What a fork or edit of the first loaded message continues from, when that is not loaded (`historyPredecessorId`). */
    var historyPredecessorId: String? = null
        private set

    /** The edited versions in this session's tree, by user-message ordinal (`versionMap`). */
    var versionMap: Map<Int, VersionGroup> = emptyMap()
        private set

    // Live frames must arrive contiguous. A gap, or a ping counting past what arrived, asks the
    // server once for a snapshot of the running turn.
    var streamSeq = 0
        private set
    private var cursorKnown = false
    private var resyncInFlight = false
    private var resyncTarget = 0
    private var replayTruncated = false
    private var turnFinalized = true

    /** Bumped by anything that makes a history page requested before it out of date. */
    var activity = 0
        private set
    private var historyLoadedAt: Long? = null
    private var localCounter = 0

    val isRunning: Boolean get() = phase.isRunning

    // Socket

    /**
     * A new connection: the server greets it with the session state and, when a turn runs, a
     * snapshot. The cursor is learned again from those.
     */
    fun socketOpened() {
        cursorKnown = false
        resyncInFlight = false
        replayTruncated = false
    }

    fun handle(frame: ChatFrame, now: Long = System.currentTimeMillis()): List<ChatEffect> {
        val effects = mutableListOf<ChatEffect>()
        when (frame) {
            is ChatFrame.Ping -> {
                val seq = frame.streamSeq
                if (seq != null && !replayTruncated) {
                    if (!cursorKnown && !phase.isRunning) {
                        // Nothing is in flight to recover; this is where counting starts.
                        streamSeq = seq
                        cursorKnown = true
                    } else if ((!cursorKnown || seq > streamSeq) && !resyncInFlight) {
                        requestResync(seq, effects)
                    }
                }
            }
            is ChatFrame.Event -> {
                frame.streamSeq?.let { advance(it, effects) }
                apply(frame.event, replaying = false, effects, now)
            }
            is ChatFrame.SessionState -> applySessionState(frame.state, now, effects)
            is ChatFrame.TurnEvents -> applySnapshot(frame.snapshot, now, effects)
            is ChatFrame.PhaseChanged -> {
                connectingElapsed = if (frame.phase == SessionPhase.CONNECTING) frame.elapsed ?: 0 else 0
                applyPhase(frame.phase, effects)
            }
            is ChatFrame.TurnStopped -> turnStop = frame.stop
            is ChatFrame.MessageRejected -> {
                // Without an id, the latest send of the same text and reply (`message_rejected` in use-chat.ts).
                val id = frame.clientMessageId
                    ?: unanswered.entries.lastOrNull { it.value.content == frame.content && it.value.replyTo == frame.replyTo }?.key
                val sent = id?.let(unanswered::remove)
                val index = if (id == null) -1 else items.indexOfLast { it.id == id }
                if (index >= 0) {
                    items = items.toMutableList().also { it.removeAt(index) }
                    if (index == items.size && live == null && phase == SessionPhase.INITIALIZING) phase = SessionPhase.IDLE
                }
                if (sent != null) rejected = RejectedSend(sent.content, sent.replyTo, frame.message, sent.turnRefs ?: frame.turnRefs)
            }
            is ChatFrame.UserMessage -> {
                // One sent from another device, or a queued message (this device's too) that the AI
                // has just started on. The sender's own bubble, when it drew one, carries the same id.
                activity++
                val clientId = frame.clientMessageId
                val drawn = clientId != null && items.any { it.id == clientId }
                // Mid-turn it splits the turn: what streamed so far is its own reply, and the stream
                // after it starts a new one.
                if (phase.isRunning && !drawn) finalizeLive()
                val last = items.lastOrNull()
                val repeated = clientId == null && last?.role == ChatItem.Role.USER && last.text == frame.content
                if (!drawn && !repeated) appendUser(frame.content, clientId, now)
            }
            is ChatFrame.EffortStatusChanged -> effortStatus = frame.status
            is ChatFrame.QueueState -> queue.replace(frame.queue)
            is ChatFrame.QueueTakenBack -> queue.answered(frame)
            is ChatFrame.TitleUpdated -> if (frame.title.isNotEmpty()) title = frame.title
            is ChatFrame.ApprovalResolved -> {
                val id = frame.requestId
                val answers = frame.answers
                if (id == null || pendingApproval?.requestId == id) pendingApproval = null
                if (id != null && answers != null) live?.addAnswers(answers, id)
                effects += ChatEffect.Attention(ChatAttention.Answered)
            }
            is ChatFrame.CompactStatus -> compacting = frame.compacting
            is ChatFrame.TabOpen -> effects += ChatEffect.OpenTab(frame.request)
            is ChatFrame.BackgroundRegistry -> backgroundShells = frame.shells
            is ChatFrame.McpStatus -> mcpNeedsAuth = frame.needsAuth
            is ChatFrame.TurnStarted -> frame.clientMessageId?.let { bindTurn(it, frame.turnId) }
        }
        return effects
    }

    // User actions

    /**
     * The frame that sends [content]. While no turn runs it is shown at once, with [replyTo] quoted
     * above it as the server will store it. While one runs it is queued (`sendMessage`): nothing is
     * drawn, the reply streaming goes on untouched, and the message shows in [queue] until the AI
     * starts on it, when the server's `user_message` puts it in the conversation. [sendNow] has
     * the AI read the queue at once.
     */
    fun send(
        content: String,
        options: TurnOptions = TurnOptions(),
        now: Long = System.currentTimeMillis(),
        replyTo: ReplyReference? = null,
        images: List<InlineImage> = emptyList(),
        imagePaths: List<String> = emptyList(),
        sendNow: Boolean = false,
        turnRefs: List<TurnReference> = emptyList(),
        /** The chat's project, which the bubble's references block names as this one's. */
        ownProject: String? = null,
    ): ChatClientMessage.Message {
        localCounter++
        val id = "local-user-$localCounter-$now"
        activity++
        turnStop = null
        unanswered.values.removeAll { now - it.sentAt >= 60_000 }
        unanswered[id] = Unanswered(content, replyTo, now, turnRefs.takeIf { it.isNotEmpty() })
        if (unanswered.size > 20) unanswered.remove(unanswered.keys.first())
        if (phase.isRunning) {
            // A refusal still finds it in [unanswered] and gives its text back.
            return ChatClientMessage.Message(id, content, options, replyTo, images, imagePaths, queued = true, sendNow = sendNow, turnRefs = turnRefs)
        }
        // The server skips a pending approval when a message arrives.
        pendingApproval = null
        phase = SessionPhase.INITIALIZING
        turnFinalized = false
        appendUser(ChatReply.encode(ChatTurnRefs.encode(content, turnRefs, ownProject), replyTo), id, now)
        return ChatClientMessage.Message(id, content, options, replyTo, images, imagePaths, turnRefs = turnRefs)
    }

    fun respond(request: ApprovalRequest, approved: Boolean, data: JsonElement? = null): ChatClientMessage.ApprovalResponse {
        if (pendingApproval?.requestId == request.requestId) pendingApproval = null
        if (approved && data != null) live?.addAnswers(data, request.requestId)
        return ChatClientMessage.ApprovalResponse(request.requestId, approved, data)
    }

    /** The composer has the refused send back, or has offered it. */
    fun takeRejected() {
        rejected = null
    }

    /** The turn was told to stop: no call streams its output any more (`cancelStreaming`). */
    fun cancelled() {
        bashOutput = emptyMap()
    }

    /** `dismissMessage`: one message out of this view, such as an error's bubble. A reload brings back what history keeps. */
    fun dismissMessage(id: String) {
        items = items.filterNot { it.id == id }
    }

    /** `clearErrors`: every system message out of this view. */
    fun clearErrors() {
        items = items.filterNot { it.role == ChatItem.Role.SYSTEM }
    }

    // History

    /**
     * Shows a history page in place of what is shown, unless something happened since
     * [activity] was read for the request; the next idle reloads it then.
     */
    fun applyHistory(page: HistoryPage, requestedAt: Int, now: Long = System.currentTimeMillis()): Boolean {
        if (requestedAt != activity) return false
        items = page.messages.mapNotNull(ChatItem::fromHistory)
        // Kept when the server sends none, as the web keeps it.
        page.versionMap?.let { versionMap = it }
        applyWindow(page)
        historyLoadedAt = now
        hasLoadedHistory = true
        if (!phase.isRunning) live = null
        return true
    }

    /**
     * What the chat this one took over from showed (`staleSwap`), kept on screen until this
     * session's history is in: versions of a conversation share their start, so only the end
     * visibly changes, where a loading screen would flash over all of it.
     */
    fun showStale(stale: List<ChatItem>) {
        if (!hasLoadedHistory && items.isEmpty()) items = stale
    }

    /** Adds the page of older messages fetched with `before`, less any already shown. */
    fun prependHistory(page: HistoryPage, before: Int) {
        if (before != historyStart) return
        val have = items.mapTo(HashSet()) { it.id }
        items = page.messages.mapNotNull(ChatItem::fromHistory).filterNot { it.id in have } + items
        applyWindow(page)
    }

    /** `applyHistoryWindow`: where [page] starts, and what goes with that. */
    private fun applyWindow(page: HistoryPage) {
        historyStart = page.start
        userOrdinalOffset = page.userOrdinalOffset
        historyPredecessorId = if (page.start > 0) page.predecessorId else null
    }

    // Before compactions

    /** The conversation before each compaction loaded so far, by the id of the summary it ends at. */
    private var expansions: Map<String, List<ChatItem>> = emptyMap()
    private var shownFor: Pair<List<ChatItem>, Map<String, List<ChatItem>>>? = null
    private var shownItems: List<ChatItem> = emptyList()

    /**
     * What the chat shows (`renderedMessages`): [items], with each loaded segment above the
     * summary it ends at, and a segment's own summary expanded the same way.
     */
    val shown: List<ChatItem>
        get() {
            if (expansions.isEmpty()) return items
            shownFor?.let { (from, with) -> if (from === items && with === expansions) return shownItems }
            val out = ArrayList<ChatItem>(items.size)
            val seen = HashSet<String>()
            fun visit(list: List<ChatItem>) {
                for (item in list) {
                    val before = expansions[item.id]
                    // A segment that somehow held its own summary would otherwise never end.
                    if (!before.isNullOrEmpty() && seen.add(item.id)) visit(before)
                    out += item
                }
            }
            visit(items)
            shownFor = items to expansions
            shownItems = out
            return out
        }

    /** The topmost compact summary shown whose earlier conversation is not loaded (`findTopUnexpandedCompact`). */
    val unexpandedCompact: ChatItem?
        get() = shown.firstOrNull { it.transcriptPath != null && it.id !in expansions }

    /** Shows [messages], read from [path], above the compact summary [compactId] (`expandCompact`). */
    fun expand(compactId: String, path: String, messages: List<HistoryMessage>) {
        val prefix = PreCompact.idPrefix(path)
        expansions = expansions + (compactId to messages.mapNotNull(ChatItem::fromHistory).map { it.copy(id = prefix + it.id) })
    }

    // Frames

    private fun advance(seq: Int, effects: MutableList<ChatEffect>) {
        when {
            !cursorKnown -> {
                streamSeq = seq
                cursorKnown = true
            }
            // The prefix is gone from the server's buffer; the idle reload fills it in.
            replayTruncated -> streamSeq = maxOf(streamSeq, seq)
            seq == streamSeq + 1 -> streamSeq = seq
            seq > streamSeq && !resyncInFlight -> requestResync(seq, effects)
        }
    }

    private fun requestResync(seq: Int, effects: MutableList<ChatEffect>) {
        resyncInFlight = true
        resyncTarget = seq
        effects += ChatEffect.Send(ChatClientMessage.Resync)
    }

    private fun applySessionState(state: SessionStateFrame, now: Long, effects: MutableList<ChatEffect>) {
        mcpNeedsAuth = state.mcpNeedsAuth
        for (binding in state.turnBindings) bindTurn(binding.clientMessageId, binding.turnId)
        // Answers that do not carry the queue leave it as it was.
        state.queue?.let(queue::replace)
        (state.effortStatus as? Reported.Value)?.let { effortStatus = it.value }
        val wasRunning = phase.isRunning
        phase = state.phase
        val stop = state.turnStop
        if (state.phase.isRunning) {
            activity++
            if (!wasRunning) turnFinalized = false
            turnStop = null
        } else if (stop is Reported.Value) {
            turnStop = stop.value
        }
        val stateTitle = state.title
        if (
            !stateTitle.isNullOrEmpty() &&
            stateTitle != "Resumed Chat" &&
            stateTitle != "Chat" &&
            !(providerId == "antigravity" && stateTitle == "New Chat")
        ) {
            title = stateTitle
        }
        val approval = state.pendingApproval
        if (approval is Reported.Value) pendingApproval = approval.value
        compacting = state.compacting
        // A running turn is followed by its snapshot, which sets the cursor.
        if (state.phase.isRunning) return

        // The server was idle when it answered: every frame counted before the resync belonged
        // to a finished turn, which the history holds.
        if (resyncInFlight) {
            streamSeq = maxOf(streamSeq, resyncTarget)
            cursorKnown = true
        }
        val replayWasTruncated = replayTruncated
        resyncInFlight = false
        replayTruncated = false
        val historyFresh = historyLoadedAt?.let { now - it < 5_000 } ?: false
        if (replayWasTruncated || wasRunning || !historyFresh) {
            finalizeLive()
            effects += ChatEffect.ReloadHistory
            // The greeting and the answer to `ready` both say idle; one reload covers both.
            historyLoadedAt = now
        }
    }

    private fun applySnapshot(snapshot: TurnSnapshot, now: Long, effects: MutableList<ChatEffect>) {
        activity++
        if (snapshot.events.isEmpty() && snapshot.userMessage == null) return
        val user = snapshot.userMessage
        if (user != null) {
            // Remove this turn's copy (from history, or streamed here) before the snapshot puts
            // it back, or it shows twice.
            var start = items.size
            while (start > 0 && items[start - 1].role == ChatItem.Role.ASSISTANT) start--
            if (start > 0 && items[start - 1].role == ChatItem.Role.USER && items[start - 1].text == user) {
                items = items.subList(0, start - 1).toList()
            }
            val last = items.lastOrNull()
            if (!(last?.role == ChatItem.Role.USER && last.text == user)) appendUser(user, now = now)
        }
        live = null
        for (event in snapshot.events) apply(event, replaying = true, effects, now)
        replayTruncated = snapshot.truncated
        val seq = snapshot.streamSeq
        if (!snapshot.truncated && seq != null) {
            streamSeq = seq
            cursorKnown = true
        }
        resyncInFlight = false
    }

    private fun applyPhase(next: SessionPhase, effects: MutableList<ChatEffect>) {
        val wasRunning = phase.isRunning
        phase = next
        if (next.isRunning) {
            activity++
            turnStop = null
            if (!wasRunning) turnFinalized = false
            return
        }
        val replayWasTruncated = replayTruncated
        resyncInFlight = false
        replayTruncated = false
        compacting = false
        statusMessage = null
        // A turn can end after frames were lost (no `done` seen): reconcile from history.
        if (replayWasTruncated || (wasRunning && !turnFinalized)) {
            finalizeLive()
            effects += ChatEffect.ReloadHistory
        }
    }

    private fun apply(event: ChatEvent, replaying: Boolean, effects: MutableList<ChatEffect>, now: Long) {
        if (event is ChatEvent.ToolResult) event.toolUseId?.let { if (it in bashOutput) bashOutput = bashOutput - it }
        when (event) {
            is ChatEvent.Text, is ChatEvent.Thinking, is ChatEvent.ToolUse, is ChatEvent.ToolResult, is ChatEvent.AccountRetry -> {
                val parent = parentOf(event)
                if (parent == null) addToLive(event, now) else routeChild(parent, event)
            }
            // Only while its card still runs: a backgrounded command prints on after its result.
            is ChatEvent.BashOutput -> if (live?.unanswered(event.toolUseId)?.name in SHELL_TOOLS) {
                val id = event.toolUseId
                bashOutput = bashOutput + (id to (bashOutput[id]?.plus(event.content, event.lineCount) ?: BashPartial.of(event.content, event.lineCount)))
            }
            is ChatEvent.SubagentStatus -> if (live?.setBgStatus(event.toolUseId, event.status) != true) {
                updateFinalizedAgent(event.toolUseId) { it.copy(bgStatus = event.status) }
            }
            is ChatEvent.Error -> {
                val parent = event.parent
                when {
                    // A subagent's error is its card's, never a line of the turn.
                    parent != null -> routeChild(parent, event)
                    live != null || phase.isRunning -> addToLive(event, now)
                    items.lastOrNull().let { it?.role != ChatItem.Role.SYSTEM || it.text != event.message } -> {
                        // A message of its own, as history keeps one; the same error twice in a row shows once.
                        localCounter++
                        items = items + ChatItem(
                            id = "local-error-$localCounter",
                            role = ChatItem.Role.SYSTEM,
                            blocks = listOf(ChatBlock.Text(event.message)),
                            timestamp = Instant.now(),
                        )
                    }
                }
            }
            is ChatEvent.Approval -> {
                addToLive(event, now)
                // On replay, `session_state.pendingApproval` is the authority.
                if (!replaying) {
                    pendingApproval = event.request
                    effects += ChatEffect.Attention(ChatAttention.Asked(event.request))
                }
            }
            // A subagent's own end finishes nothing of the turn.
            is ChatEvent.Done -> if (event.parent == null) {
                // A second `done` for the same turn (provider, then the stream loop) finds the phase idle.
                if (phase != SessionPhase.IDLE) effects += ChatEffect.Attention(ChatAttention.Done)
                finalizeLive(event.lastMessageUuid, event.usage)
                turnFinalized = true
                statusMessage = null
                bashOutput = emptyMap()
            }
            is ChatEvent.StatusUpdate -> statusMessage = event.message + (event.accountLabel?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "")
            is ChatEvent.AccountInfo -> statusMessage = null
            is ChatEvent.SessionMigrated -> effects += ChatEffect.AdoptSessionId(event.newSessionId)
            is ChatEvent.TeamDetected -> effects += ChatEffect.TeamDetected(event.teamName)
            is ChatEvent.TeamInbox -> effects += ChatEffect.TeamMessages(event.messages)
            is ChatEvent.Other -> Unit
        }
    }

    private fun parentOf(event: ChatEvent): String? = when (event) {
        is ChatEvent.Text -> event.parent
        is ChatEvent.Thinking -> event.parent
        is ChatEvent.ToolUse -> event.parent
        is ChatEvent.ToolResult -> event.parent
        else -> null
    }

    /**
     * A subagent's event, on its card. A backgrounded agent works on after the turn that started it
     * ended: its events then go on that turn's card (`routeToFinalizedParent`).
     */
    private fun routeChild(parent: String, event: ChatEvent) {
        if (live?.hasAgent(parent) != true) childFinalized(parent, event)
        live?.add(event)
    }

    private fun childFinalized(parent: String, event: ChatEvent) {
        // The live turn drops a question's call; so does a finished one.
        if (event is ChatEvent.ToolUse && event.name == "AskUserQuestion") return
        updateFinalizedAgent(parent) { it.withChild(parent, event) ?: it }
    }

    /** The newest message whose own Agent/Task card is the call [toolUseId], with that card changed. */
    private fun updateFinalizedAgent(toolUseId: String, change: (ToolBlock) -> ToolBlock) {
        fun isCard(block: ChatBlock) = block is ChatBlock.Tool && block.tool.isAgent && block.tool.toolUseId == toolUseId
        val index = items.indexOfLast { item -> item.role == ChatItem.Role.ASSISTANT && item.blocks.any(::isCard) }
        if (index < 0) return
        val item = items[index]
        val blocks = item.blocks.map { if (isCard(it)) ChatBlock.Tool(change((it as ChatBlock.Tool).tool)) else it }
        if (blocks == item.blocks) return
        items = items.toMutableList().also { it[index] = item.copy(blocks = blocks) }
    }

    private fun addToLive(event: ChatEvent, now: Long) {
        val turn = live ?: TurnBuilder(live = true).also {
            live = it
            liveStartedAt = now
        }
        turn.add(event)
    }

    /**
     * The streamed answer as a message: stamped when it began streaming, as the web stamps it, and
     * with the SDK's id once `done` names it, which is what finds it again after a reload.
     */
    private fun finalizeLive(sdkUuid: String? = null, usage: TurnUsage? = null) {
        val turn = live ?: return
        live = null
        if (turn.isEmpty) return
        localCounter++
        items = items + ChatItem(
            "local-assistant-$localCounter", ChatItem.Role.ASSISTANT, turn.blocks, Instant.ofEpochMilli(liveStartedAt),
            sdkUuid = sdkUuid, answerText = turn.answerText, taskCalls = turn.taskCalls, usage = usage,
        )
    }

    private fun appendUser(content: String, id: String? = null, now: Long) {
        localCounter++
        items = items + ChatItem(id ?: "local-user-$localCounter", ChatItem.Role.USER, listOf(ChatBlock.Text(content)), Instant.ofEpochMilli(now))
    }

    private class Unanswered(val content: String, val replyTo: ReplyReference?, val sentAt: Long, val turnRefs: List<TurnReference>? = null)

    companion object {
        /** Bindings kept per chat; old ones are only ever asked about by a Copy turn link (`MAX_BINDINGS`). */
        const val MAX_TURN_BINDINGS = 500
    }
}
