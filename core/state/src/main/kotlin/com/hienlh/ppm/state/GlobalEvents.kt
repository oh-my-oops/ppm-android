package com.hienlh.ppm.state

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.SocketConnection
import com.hienlh.ppm.client.markUnread
import com.hienlh.ppm.protocol.SessionUnread
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The app-wide socket (`/ws/global`, run as the web's `use-global-events.ts` runs it): which
 * sessions have a turn running and which are unread, on every project, kept in step with the
 * other devices. One per open server; like [ChatController], driven from one thread, and
 * [stop] when the server closes.
 */
class GlobalEvents(
    private val api: ApiClient,
    parent: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _isConnected = MutableStateFlow(false)

    /** Up and told which project to watch (after `global_ready`). */
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _running = MutableStateFlow<Set<String>>(emptySet())

    /** Sessions with a turn in flight, on any project. */
    val running: StateFlow<Set<String>> = _running.asStateFlow()

    private val _unread = MutableStateFlow<Map<String, SessionUnread>>(emptyMap())

    /** Sessions waiting to be read, on any project. */
    val unread: StateFlow<Map<String, SessionUnread>> = _unread.asStateFlow()

    /**
     * The project open on this device: the server watches its files, and its routes carry the
     * read and unread calls.
     */
    var project: String? = null
        set(value) {
            if (value == field) return
            field = value
            sendWatch()
            syncRunning()
            loadUnread()
        }

    /**
     * Runs on every `global_ready` — the first connect and each reconnect — because what changed
     * while the socket was down was never delivered.
     */
    var onReady: (() -> Unit)? = null

    /**
     * Whether a session is in front of the user right now. Its unread is cleared as it arrives,
     * unless it was marked by hand (the web's active-tab auto-clear).
     */
    var isShown: ((String) -> Boolean)? = null

    /** PPM wrote to a repository of this project (`git:changed`): a commit, a checkout, a push. */
    var onGitChanged: ((project: String) -> Unit)? = null

    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private var socket: SocketConnection? = null

    /** Reads the socket, or waits to connect again. */
    private var socketJob: Job? = null
    private var lastFrameAt = clock()
    private var lastPingAt = clock()
    private var failures = 0
    private var stopped = false

    fun start() {
        connect()
        scope.launch {
            while (true) {
                delay(5_000)
                tick()
            }
        }
    }

    fun stop() {
        stopped = true
        scope.cancel()
        socket?.close()
        socket = null
        _isConnected.value = false
    }

    /**
     * The app came to the front: a socket that died meanwhile is replaced now, not after its
     * backoff (the web does this when the page becomes visible).
     */
    fun wake() {
        if (stopped || (socket != null && clock() - lastFrameAt < SILENCE_LIMIT_MS)) return
        failures = 0
        socketJob?.cancel()
        val dead = socket
        socket = null
        dead?.close()
        connect()
    }

    /**
     * Clears a session's unread here and on the server, which tells every other device. Picking
     * a chat does this even when it was marked unread by hand.
     */
    fun markRead(sessionId: String) {
        if (sessionId !in _unread.value) return
        _unread.value -= sessionId
        val project = project ?: return
        fire { api.markRead(project, sessionId) }
    }

    /** "Mark as unread": unread on every device, and it stays while the chat is in front. */
    fun markUnread(sessionId: String, title: String?) {
        val project = project ?: return
        _unread.value += sessionId to SessionUnread(
            type = "done",
            count = 1,
            projectName = project,
            sessionTitle = title ?: _unread.value[sessionId]?.sessionTitle,
            manual = true,
        )
        fire { api.markUnread(project, sessionId) }
    }

    /** The chat is in front of the user: its unread goes, unless it was marked by hand. */
    fun seen(sessionId: String) {
        val entry = _unread.value[sessionId] ?: return
        if (!entry.manual) markRead(sessionId)
    }

    // Frames

    internal fun handle(frame: JsonElement) {
        when (frame["type"].string) {
            "global_ready" -> {
                failures = 0
                _isConnected.value = true
                sendWatch()
                syncRunning()
                loadUnread()
                onReady?.invoke()
            }
            "session:unread_changed" -> applyUnread(frame)
            "session:phase_changed" -> {
                val id = frame["sessionId"].string ?: return
                if (frame["phase"].string == "idle") _running.value -= id else _running.value += id
            }
            // Every phase from now on carries the new id, so the old one's `idle` never comes.
            "session:migrated" -> frame["oldSessionId"].string?.let { _running.value -= it }
            "git:changed" -> frame["projectName"].string?.let { onGitChanged?.invoke(it) }
        }
    }

    private fun applyUnread(frame: JsonElement) {
        val id = frame["sessionId"].string ?: return
        val count = frame["unreadCount"].int
        if (count == 0) {
            _unread.value -= id
            return
        }
        val existing = _unread.value[id]
        val project = frame["projectName"].string ?: ""
        _unread.value += id to SessionUnread(
            type = frame["unreadType"].string?.takeIf { it.isNotEmpty() } ?: "done",
            // -1: one more, without the total.
            count = if (count != null && count > 0) count else (existing?.count ?: 0) + 1,
            projectName = project.ifEmpty { existing?.projectName ?: "" },
            sessionTitle = frame["sessionTitle"].string ?: existing?.sessionTitle,
            // The broadcast of a hand-made mark carries it; others keep what was there.
            manual = frame["manual"].bool ?: existing?.manual ?: false,
        )
        if (isShown?.invoke(id) == true) seen(id)
    }

    /** Indicators are never worth failing over: a failed read keeps what is shown. */
    private fun syncRunning() {
        fire { _running.value = api.runningSessions() }
    }

    private fun loadUnread() {
        val project = project ?: return
        fire {
            val entries = api.unreadSessions(project)
            _unread.value = entries
            for (id in entries.keys) if (isShown?.invoke(id) == true) seen(id)
        }
    }

    private fun fire(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
    }

    // Socket

    private fun connect() {
        if (stopped) return
        val socket = SocketConnection(api.globalSocketUrl())
        this.socket = socket
        lastFrameAt = clock()
        socketJob = scope.launch {
            for (event in socket.events) receive(event, socket)
            socketEnded(socket)
        }
    }

    private fun receive(event: SocketConnection.Event, source: SocketConnection) {
        if (source !== socket) return
        when (event) {
            SocketConnection.Event.Opened -> lastFrameAt = clock()
            is SocketConnection.Event.Text -> frame(event.text)
            is SocketConnection.Event.Binary -> frame(event.bytes.utf8())
            is SocketConnection.Event.Closed -> Unit
        }
    }

    private fun frame(text: String) {
        lastFrameAt = clock()
        WireJson.parseOrNull(text)?.let(::handle)
    }

    private fun socketEnded(ended: SocketConnection) {
        if (ended !== socket || stopped) return
        socket = null
        _isConnected.value = false
        // 1 s, 2, 4, 8, 16, then every 30 s (`ws-client.ts`).
        val wait = minOf(1_000L shl minOf(failures, 5), 30_000L)
        failures++
        socketJob = scope.launch {
            delay(wait)
            connect()
        }
    }

    private fun tick() {
        val socket = socket ?: return
        val now = clock()
        if (now - lastFrameAt > SILENCE_LIMIT_MS) {
            socket.close()
        } else if (_isConnected.value && now - lastPingAt >= PING_INTERVAL_MS) {
            lastPingAt = now
            socket.send("""{"type":"ping"}""")
        }
    }

    private fun sendWatch() {
        val socket = socket
        val project = project
        if (!_isConnected.value || socket == null || project == null) return
        socket.send(
            buildJsonObject {
                put("type", "watch")
                put("projectName", project)
            }.toString(),
        )
    }

    companion object {
        /** Well under the server's own idle timeout, so a quiet socket is never reaped as dead. */
        const val PING_INTERVAL_MS = 20_000L

        /** No frame, pong included, for this long: the socket is dead even if the OS has not noticed. */
        const val SILENCE_LIMIT_MS = 45_000L
    }
}
