package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.AgentRunningEntry
import com.hienlh.ppm.protocol.AgentSource
import com.hienlh.ppm.protocol.AgentTranscriptFrame
import com.hienlh.ppm.protocol.AgentTranscriptMessages
import com.hienlh.ppm.protocol.ChatEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlin.random.Random

/**
 * What an agent session stream needs of `/ws/global` (`global-ws-channel.ts`). Deliberately not a
 * queue: a subscribe flushed after a reconnect would resume from the cursor it was built with, not
 * the newest one, so [sendIfOpen] drops and every caller sends again from [onGlobalReady].
 */
interface GlobalChannel {
    /** Sends now, or drops; whether it went out. */
    fun sendIfOpen(text: String): Boolean

    /** Runs [listener] on every `global_ready`, the first connect and each reconnect; returns its remover. */
    fun onGlobalReady(listener: () -> Unit): () -> Unit

    /** Runs [listener] on every agent transcript and activity frame; returns its remover. */
    fun onAgentFrame(listener: (AgentTranscriptFrame) -> Unit): () -> Unit
}

/** One step held, with its de-dupe key and time for ordering (`StreamEntry`). */
data class AgentStreamEntry(val key: String, val ts: Long, val event: ChatEvent)

/** The hub's own cap per subscription, so a long session stays drawable (`MAX_STREAM_ENTRIES`). */
const val MAX_AGENT_STREAM_ENTRIES = 2000

/**
 * One `agent-transcript:events` page on top of what is held (`applyEnvelopeBatch`): [reset]
 * starts again from nothing; a key already held is updated in place when the page says
 * `replace`, otherwise skipped as a page sent twice; a new key goes where its time sorts, since a
 * live tick can land while a backlog is still draining.
 */
fun applyAgentPage(current: List<AgentStreamEntry>, page: AgentTranscriptFrame.Page): List<AgentStreamEntry> {
    val next = if (page.reset) ArrayList() else ArrayList(current)
    val keys = next.mapTo(HashSet()) { it.key }
    for (envelope in page.events) {
        val entry = AgentStreamEntry(envelope.key, envelope.ts, envelope.event)
        if (envelope.key in keys) {
            if (envelope.replace) next[next.indexOfFirst { it.key == envelope.key }] = entry
            continue
        }
        var at = next.size
        while (at > 0 && next[at - 1].ts > envelope.ts) at--
        next.add(at, entry)
        keys += envelope.key
    }
    return if (next.size > MAX_AGENT_STREAM_ENTRIES) next.subList(next.size - MAX_AGENT_STREAM_ENTRIES, next.size).toList() else next
}

/** `Math.random().toString(36).slice(2, 10)`: eight base-36 characters. */
private fun subIdSuffix(random: Random): String = buildString {
    repeat(8) { append("0123456789abcdefghijklmnopqrstuvwxyz"[random.nextInt(36)]) }
}

/**
 * One Agent card's or teammate's session followed live (`use-agent-session-stream.ts`). The
 * subscription is sent at [start] and again on every `global_ready` with the last cursor the hub
 * handed back, so a reconnect resumes with no gap and no step twice. Driven from one thread, the
 * channel's.
 */
class AgentSessionStream(
    private val channel: GlobalChannel,
    private val projectName: String,
    private val providerId: String,
    private val sessionId: String,
    private val source: AgentSource,
    random: Random = Random.Default,
) {
    data class State(
        val entries: List<AgentStreamEntry> = emptyList(),
        /**
         * The hub found a readable transcript for this source. False before its first answer and
         * after one that found none: the window shows the steps it already knew both times.
         */
        val available: Boolean = false,
        val running: Boolean = false,
        val loading: Boolean = true,
        val error: String? = null,
    ) {
        val events: List<ChatEvent> get() = entries.map { it.event }

        /** What the window shows: the stream once the hub has a transcript, else [fallback]. */
        fun shown(fallback: List<ChatEvent>): List<ChatEvent> = if (available) events else fallback
    }

    private val subId = "ats-" + subIdSuffix(random)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var cursor: JsonObject? = null
    private var removers: List<() -> Unit> = emptyList()

    fun start() {
        if (removers.isNotEmpty()) return
        removers = listOf(channel.onAgentFrame(::receive), channel.onGlobalReady(::subscribe))
        subscribe()
    }

    fun stop() {
        if (removers.isEmpty()) return
        removers.forEach { it() }
        removers = emptyList()
        channel.sendIfOpen(AgentTranscriptMessages.unsubscribe(subId))
    }

    private fun subscribe() {
        channel.sendIfOpen(AgentTranscriptMessages.subscribe(subId, projectName, providerId, sessionId, source, cursor))
    }

    private fun receive(frame: AgentTranscriptFrame) {
        if (frame.subId != subId) return
        when (frame) {
            is AgentTranscriptFrame.Page -> {
                cursor = frame.cursor
                _state.value = State(applyAgentPage(_state.value.entries, frame), frame.available, frame.running, loading = false)
            }
            is AgentTranscriptFrame.Error -> _state.value = _state.value.copy(available = false, loading = false, error = frame.code)
            is AgentTranscriptFrame.Activity -> Unit
        }
    }
}

/**
 * Who is running in one session right now (`use-agent-activity.ts`): each push is the whole list,
 * so a resubscribe on `global_ready` is just the same message again.
 */
class AgentActivityWatch(
    private val channel: GlobalChannel,
    private val projectName: String,
    private val providerId: String,
    private val sessionId: String,
    random: Random = Random.Default,
) {
    private val subId = "aa-" + subIdSuffix(random)
    private val _running = MutableStateFlow<List<AgentRunningEntry>>(emptyList())
    val running: StateFlow<List<AgentRunningEntry>> = _running.asStateFlow()

    private var removers: List<() -> Unit> = emptyList()

    fun start() {
        if (removers.isNotEmpty()) return
        removers = listOf(channel.onAgentFrame(::receive), channel.onGlobalReady(::subscribe))
        subscribe()
    }

    fun stop() {
        if (removers.isEmpty()) return
        removers.forEach { it() }
        removers = emptyList()
        channel.sendIfOpen(AgentTranscriptMessages.activityUnsubscribe(subId))
    }

    private fun subscribe() {
        channel.sendIfOpen(AgentTranscriptMessages.activitySubscribe(subId, projectName, providerId, sessionId))
    }

    private fun receive(frame: AgentTranscriptFrame) {
        if (frame is AgentTranscriptFrame.Activity && frame.subId == subId) _running.value = frame.running
    }
}
