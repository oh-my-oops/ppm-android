package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.ApprovalRequest
import com.hienlh.ppm.protocol.ChatClientMessage
import com.hienlh.ppm.protocol.ChatEvent
import com.hienlh.ppm.protocol.ChatFrame
import com.hienlh.ppm.protocol.HistoryPage
import com.hienlh.ppm.protocol.Reported
import com.hienlh.ppm.protocol.SessionPhase
import com.hienlh.ppm.protocol.SessionStateFrame
import com.hienlh.ppm.protocol.TurnSnapshot
import com.hienlh.ppm.protocol.TurnStop
import kotlinx.serialization.json.JsonElement
import java.time.Instant

/** Something the transcript needs done outside itself. */
sealed interface ChatEffect {
    data class Send(val message: ChatClientMessage) : ChatEffect
    data object ReloadHistory : ChatEffect
    data class AdoptSessionId(val id: String) : ChatEffect
}

/**
 * What one chat shows, rebuilt from history pages and chat-socket frames. No I/O: frames go in,
 * effects come out, so every rule here runs in a plain unit test. It follows
 * `src/web/hooks/use-chat.ts`. Not thread-safe: one thread (the main one) drives it.
 */
class ChatTranscript {
    var items: List<ChatItem> = emptyList()
        private set

    /** The assistant message being streamed; it joins [items] at `done`. */
    var live: TurnBuilder? = null
        private set
    var phase: SessionPhase = SessionPhase.IDLE
        private set
    var pendingApproval: ApprovalRequest? = null
        private set

    /** Kept out of [items] so that a history reload cannot erase it. */
    var turnStop: TurnStop? = null
        private set
    var title: String? = null
        private set
    var compacting = false
        private set

    /** Something to tell the user that belongs to no message, such as a refused send. */
    var notice: String? = null
        private set

    /** Index of the first loaded message in the whole transcript; above 0, older ones exist. */
    var historyStart = 0
        private set
    var hasLoadedHistory = false
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
                apply(frame.event, replaying = false, effects)
            }
            is ChatFrame.SessionState -> applySessionState(frame.state, now, effects)
            is ChatFrame.TurnEvents -> applySnapshot(frame.snapshot, now, effects)
            is ChatFrame.PhaseChanged -> applyPhase(frame.phase, effects)
            is ChatFrame.TurnStopped -> turnStop = frame.stop
            is ChatFrame.MessageRejected -> {
                val id = frame.clientMessageId
                val index = if (id == null) -1 else items.indexOfLast { it.id == id }
                if (index >= 0) {
                    items = items.toMutableList().also { it.removeAt(index) }
                    if (index == items.size && live == null && phase == SessionPhase.INITIALIZING) phase = SessionPhase.IDLE
                }
                notice = frame.message
            }
            is ChatFrame.UserMessage -> {
                // Sent from another device; the sender never gets this echo.
                activity++
                val last = items.lastOrNull()
                if (!(last?.role == ChatItem.Role.USER && last.text == frame.content)) appendUser(frame.content, now = now)
            }
            is ChatFrame.TitleUpdated -> if (frame.title.isNotEmpty()) title = frame.title
            is ChatFrame.ApprovalResolved -> {
                val id = frame.requestId
                val answers = frame.answers
                if (id == null || pendingApproval?.requestId == id) pendingApproval = null
                if (id != null && answers != null) live?.addAnswers(answers, id)
            }
            is ChatFrame.CompactStatus -> compacting = frame.compacting
            // The AI's tool waits for an answer from this device; never leave it hanging.
            is ChatFrame.TabOpen -> effects += ChatEffect.Send(
                ChatClientMessage.TabOpenResult(frame.requestId, opened = false, error = TAB_OPEN_UNSUPPORTED),
            )
        }
        return effects
    }

    // User actions

    /** The message to put on the socket; the transcript shows it at once. */
    fun send(content: String, now: Long = System.currentTimeMillis()): ChatClientMessage.Message {
        localCounter++
        val id = "local-user-$localCounter-$now"
        activity++
        turnStop = null
        notice = null
        // The server skips a pending approval when a message arrives.
        pendingApproval = null
        if (phase.isRunning) {
            // A follow-up: what streamed so far stays above the new message.
            finalizeLive(now)
        } else {
            phase = SessionPhase.INITIALIZING
            turnFinalized = false
        }
        appendUser(content, id, now)
        return ChatClientMessage.Message(id, content)
    }

    fun respond(request: ApprovalRequest, approved: Boolean, data: JsonElement? = null): ChatClientMessage.ApprovalResponse {
        if (pendingApproval?.requestId == request.requestId) pendingApproval = null
        if (approved && data != null) live?.addAnswers(data, request.requestId)
        return ChatClientMessage.ApprovalResponse(request.requestId, approved, data)
    }

    fun dismissNotice() {
        notice = null
    }

    // History

    /**
     * Shows a history page in place of what is shown, unless something happened since
     * [activity] was read for the request; the next idle reloads it then.
     */
    fun applyHistory(page: HistoryPage, requestedAt: Int, now: Long = System.currentTimeMillis()): Boolean {
        if (requestedAt != activity) return false
        items = page.messages.mapNotNull(ChatItem::fromHistory)
        historyStart = page.start
        historyLoadedAt = now
        hasLoadedHistory = true
        if (!phase.isRunning) live = null
        return true
    }

    /** Adds the page of older messages fetched with `before`. */
    fun prependHistory(page: HistoryPage, before: Int) {
        if (before != historyStart) return
        items = page.messages.mapNotNull(ChatItem::fromHistory) + items
        historyStart = page.start
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
        if (!state.title.isNullOrEmpty()) title = state.title
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
            finalizeLive(now)
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
        for (event in snapshot.events) apply(event, replaying = true, effects)
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
        // A turn can end after frames were lost (no `done` seen): reconcile from history.
        if (replayWasTruncated || (wasRunning && !turnFinalized)) {
            finalizeLive(System.currentTimeMillis())
            effects += ChatEffect.ReloadHistory
        }
    }

    private fun apply(event: ChatEvent, replaying: Boolean, effects: MutableList<ChatEffect>) {
        when (event) {
            is ChatEvent.Text, is ChatEvent.Thinking, is ChatEvent.ToolUse, is ChatEvent.ToolResult -> addToLive(event)
            is ChatEvent.Error -> if (live != null || phase.isRunning) {
                addToLive(event)
            } else {
                localCounter++
                items = items + ChatItem(
                    id = "local-error-$localCounter",
                    role = ChatItem.Role.SYSTEM,
                    blocks = listOf(ChatBlock.Error(event.message)),
                    timestamp = Instant.now(),
                )
            }
            is ChatEvent.Approval -> {
                addToLive(event)
                // On replay, `session_state.pendingApproval` is the authority.
                if (!replaying) pendingApproval = event.request
            }
            ChatEvent.Done -> {
                finalizeLive(System.currentTimeMillis())
                turnFinalized = true
            }
            is ChatEvent.SessionMigrated -> effects += ChatEffect.AdoptSessionId(event.newSessionId)
            is ChatEvent.Other -> Unit
        }
    }

    private fun addToLive(event: ChatEvent) {
        val turn = live ?: TurnBuilder(live = true).also { live = it }
        turn.add(event)
    }

    private fun finalizeLive(now: Long) {
        val turn = live ?: return
        live = null
        if (turn.isEmpty) return
        localCounter++
        items = items + ChatItem("local-assistant-$localCounter", ChatItem.Role.ASSISTANT, turn.blocks, Instant.ofEpochMilli(now))
    }

    private fun appendUser(content: String, id: String? = null, now: Long) {
        localCounter++
        items = items + ChatItem(id ?: "local-user-$localCounter", ChatItem.Role.USER, listOf(ChatBlock.Text(content)), Instant.ofEpochMilli(now))
    }

    companion object {
        const val TAB_OPEN_UNSUPPORTED =
            "The PPM app on this device cannot open tabs yet. Open this chat in the PPM web app to see it."
    }
}
