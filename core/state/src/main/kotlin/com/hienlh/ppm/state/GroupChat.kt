package com.hienlh.ppm.state

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.SocketConnection
import com.hienlh.ppm.client.addGroupMember
import com.hienlh.ppm.client.group
import com.hienlh.ppm.client.groupFeed
import com.hienlh.ppm.client.removeGroupMember
import com.hienlh.ppm.client.resumeGroup
import com.hienlh.ppm.client.sendGroupMessage
import com.hienlh.ppm.client.setGroupReplyCap
import com.hienlh.ppm.client.stopGroup
import com.hienlh.ppm.client.updateGroupMember
import com.hienlh.ppm.protocol.GroupMember
import com.hienlh.ppm.protocol.GroupMessage
import com.hienlh.ppm.protocol.GroupStatus
import com.hienlh.ppm.protocol.MemberStatus
import com.hienlh.ppm.protocol.MemberValues
import com.hienlh.ppm.protocol.RosterMember
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One group's feed, as the web's `use-group-chat.ts` runs it, with the roster `group-chat-tab.tsx`
 * keeps beside it: the feed fetched, then written live over the group socket; who is typing;
 * whether a reply burst runs; and the editable roster with its reply cap. Driven from one thread;
 * [close] when the tab goes.
 *
 * Where the web loses messages, this does not: a socket back after a drop fetches the feed again,
 * because the server replays only the burst still running, and one that ended meanwhile is never
 * sent.
 */
class GroupChat(
    private val api: ApiClient,
    val project: String,
    val groupId: String,
    parent: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class State(
        val messages: List<GroupMessage> = emptyList(),
        /** As `group_state` names them: live status and colour only. */
        val members: List<RosterMember> = emptyList(),
        val status: GroupStatus = GroupStatus.IDLE,
        /** Members composing a reply, in the order they started. */
        val typing: List<String> = emptyList(),
        val loading: Boolean = false,
        /** A reply burst is running: Stop instead of Resume. */
        val running: Boolean = false,
        val error: String? = null,
        /** The roster with persona and model (`GET /:id`), fetched again after each edit; empty until it comes. */
        val roster: List<GroupMember> = emptyList(),
        /** The reply cap, null until the group is read. */
        val replyCap: Int? = null,
        val rosterBusy: Boolean = false,
        val rosterError: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val typingTimers = HashMap<String, Job>()
    private var socket: SocketConnection? = null
    private var open = false

    /** The socket is up (tests: whether a drop was noticed). */
    internal val socketOpen: Boolean get() = open

    /** A `group_state` came on some socket: sends go over the socket from now on (the web's `isConnected`). */
    private var connected = false

    /** What was sent while the socket was down, sent after `ready` on the next one (`ws-client.ts`). */
    private val pending = ArrayList<String>()
    private var lastFrameAt = clock()
    private var failures = 0
    private var closed = false

    fun start() {
        loadFeed(initial = true)
        reloadRoster()
        connect()
        scope.launch {
            while (true) {
                delay(5_000)
                if (socket != null && clock() - lastFrameAt > SILENCE_LIMIT_MS) socket?.close()
            }
        }
    }

    fun close() {
        closed = true
        scope.cancel()
        socket?.close()
        socket = null
    }

    /** The app came to the front: a socket that died meanwhile is replaced now, not after its backoff. */
    fun wake() {
        if (closed || (socket != null && clock() - lastFrameAt < SILENCE_LIMIT_MS)) return
        failures = 0
        val dead = socket
        socket = null
        open = false
        dead?.close()
        connect()
    }

    fun send(content: String) {
        val text = content.trim()
        if (text.isEmpty()) return
        _state.update { it.copy(error = null, running = true) }
        if (connected) {
            transmit(buildJsonObject { put("type", "message"); put("content", text) }.toString())
        } else {
            scope.launch {
                try {
                    api.sendGroupMessage(groupId, text)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    _state.update { it.copy(error = error.message ?: "Failed to send message", running = false) }
                }
            }
        }
    }

    /** Stops the burst; the group waits paused for Resume. */
    fun stop() {
        if (connected) {
            transmit("""{"type":"stop"}""")
        } else {
            scope.launch { runCatching { api.stopGroup(groupId) } }
        }
        _state.update { it.copy(status = GroupStatus.PAUSED, running = false) }
    }

    fun resume() {
        scope.launch {
            try {
                api.resumeGroup(groupId)
                _state.update { it.copy(status = GroupStatus.ACTIVE, running = true) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(error = error.message ?: "Failed to resume") }
            }
        }
    }

    /** Saved on every change, as the web's number field does; a refused one reads the group back. */
    fun setReplyCap(turns: Int) {
        val cap = turns.coerceIn(1, 50)
        _state.update { it.copy(replyCap = cap) }
        scope.launch {
            try {
                api.setGroupReplyCap(groupId, cap)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                reloadRoster()
            }
        }
    }

    /** Adds a member, or edits [member]; throws what the server refused, for the form to show. */
    suspend fun saveMember(member: GroupMember?, values: MemberValues) {
        if (member != null) api.updateGroupMember(groupId, member.id, values) else api.addGroupMember(groupId, values)
        refreshRoster()
    }

    fun removeMember(member: GroupMember) {
        _state.update { it.copy(rosterBusy = true, rosterError = null) }
        scope.launch {
            try {
                api.removeGroupMember(groupId, member.id)
                refreshRoster()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(rosterError = error.message ?: "Failed to remove member") }
            } finally {
                _state.update { it.copy(rosterBusy = false) }
            }
        }
    }

    // Frames

    internal fun handle(frame: JsonElement) {
        when (frame["type"].string) {
            "group_state" -> {
                // The socket is back after a drop: what was written meanwhile was not all sent.
                if (connected) loadFeed(initial = false)
                connected = true
                val status = GroupStatus.of(frame["status"].string)
                val members = frame["members"].array.orEmpty().mapNotNull(RosterMember::from)
                _state.update { it.copy(status = status, members = members, running = status == GroupStatus.ACTIVE) }
            }
            "group_message" -> {
                val message = GroupMessage.from(frame["message"]) ?: return
                // A member's reply ends their typing line.
                clearTyping(message.fromMember)
                _state.update { state ->
                    if (state.messages.any { it.id == message.id }) state else state.copy(messages = state.messages + message)
                }
            }
            "member_status" -> {
                val id = frame["memberId"].string ?: return
                val status = MemberStatus.of(frame["status"].string)
                _state.update { state -> state.copy(members = state.members.map { if (it.id == id) it.copy(status = status) else it }) }
            }
            "typing" -> {
                val member = frame["member"].string ?: return
                _state.update { if (member in it.typing) it else it.copy(typing = it.typing + member) }
                typingTimers.remove(member)?.cancel()
                typingTimers[member] = scope.launch {
                    delay(TYPING_TIMEOUT_MS)
                    typingTimers.remove(member)
                    clearTyping(member)
                }
            }
            "group_done" -> {
                typingTimers.values.forEach { it.cancel() }
                typingTimers.clear()
                _state.update { it.copy(running = false, status = GroupStatus.IDLE, typing = emptyList()) }
            }
            "error" -> _state.update { it.copy(error = frame["message"].string, running = false) }
        }
    }

    private fun clearTyping(member: String) {
        _state.update { if (member in it.typing) it.copy(typing = it.typing - member) else it }
    }

    // Feed and roster

    /**
     * The last [FEED_LIMIT] messages. What came live while the request was out stays after them;
     * what was shown before it and is older than the window stays before them.
     */
    private fun loadFeed(initial: Boolean) {
        val before = _state.value.messages.mapTo(HashSet()) { it.id }
        if (initial) _state.update { it.copy(loading = true) }
        scope.launch {
            try {
                val fetched = api.groupFeed(groupId, FEED_LIMIT)
                val ids = fetched.mapTo(HashSet()) { it.id }
                _state.update { state ->
                    val older = state.messages.filter { it.id in before && it.id !in ids }
                    val live = state.messages.filter { it.id !in before && it.id !in ids }
                    state.copy(messages = older + fetched + live)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (initial) _state.update { it.copy(error = error.message ?: "Failed to load feed") }
            } finally {
                if (initial) _state.update { it.copy(loading = false) }
            }
        }
    }

    private fun reloadRoster() {
        scope.launch { refreshRoster() }
    }

    /** A failed read keeps the last roster read. */
    private suspend fun refreshRoster() {
        try {
            val group = api.group(groupId)
            _state.update { it.copy(roster = group.members, replyCap = group.maxTurns) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }
    }

    // Socket

    /** A frame the socket could not take waits for the next one, which a refused send brings about. */
    private fun transmit(text: String) {
        val socket = socket
        if (open && socket != null && socket.send(text)) return
        pending += text
    }

    private fun connect() {
        if (closed) return
        val socket = SocketConnection(api.groupSocketUrl(project, groupId))
        this.socket = socket
        lastFrameAt = clock()
        scope.launch {
            for (event in socket.events) {
                if (socket !== this@GroupChat.socket) continue
                when (event) {
                    SocketConnection.Event.Opened -> {
                        open = true
                        failures = 0
                        lastFrameAt = clock()
                        socket.send("""{"type":"ready"}""")
                        pending.forEach(socket::send)
                        pending.clear()
                    }
                    is SocketConnection.Event.Text -> frame(event.text)
                    is SocketConnection.Event.Binary -> frame(event.bytes.utf8())
                    is SocketConnection.Event.Closed -> Unit
                }
            }
            socketEnded(socket)
        }
    }

    private fun frame(text: String) {
        lastFrameAt = clock()
        WireJson.parseOrNull(text)?.let(::handle)
    }

    private fun socketEnded(ended: SocketConnection) {
        if (ended !== socket || closed) return
        socket = null
        open = false
        // 1 s, 2, 4, 8, 16, then every 30 s (`ws-client.ts`).
        val wait = minOf(1_000L shl minOf(failures, 5), 30_000L)
        failures++
        scope.launch {
            delay(wait)
            if (socket == null) connect()
        }
    }

    companion object {
        const val FEED_LIMIT = 200
        const val TYPING_TIMEOUT_MS = 8_000L

        /** The server pings every 15 s. */
        const val SILENCE_LIMIT_MS = 45_000L
    }
}
