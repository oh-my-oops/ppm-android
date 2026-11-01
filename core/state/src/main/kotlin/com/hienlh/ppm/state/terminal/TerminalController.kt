package com.hienlh.ppm.state.terminal

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.client.SocketConnection
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.double
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull

/**
 * Native owner of `use-terminal.ts`'s socket lifecycle. All methods and callbacks run on the
 * supplied scope's single thread (Android's main thread); only [state] may be read elsewhere.
 *
 * Install the emulator callbacks at construction, before [start]: output is delivered in order,
 * synchronously, including replay. It never passes through a flow that can drop bytes. On each
 * subsequent connection [onReset] runs before any output, so an owner can replace its emulator
 * and scrollback. Keep that owner alive while a tab is hidden, then [close] when it is removed.
 *
 * The buffer a server replays when a session is picked up again is history, not live output, and
 * comes through [onReplay] (falling back to [onOutput]) so an owner can show it without answering the
 * queries inside it: a program that asked for the cursor position or the device attributes was
 * answered when it asked, and an answer now lands at whatever is running, a prompt typically, as text
 * that Enter then runs.
 *
 * What the shell prints before the owner has reported a usable size through [resize] is held back, in
 * order, and written the moment the first size arrives, all of it through [onReplay]: held output is
 * history by then, live output too, and for a view nobody opens (a collapsed dock, a minimized window) it
 * can be minutes old, when the answer to a query inside it would land at whatever is running. The socket
 * itself opens at [start], so a shell stays attached whether or not its view is ever laid out. But the server replays its buffer as soon as a socket attaches, the shell made it for the size
 * it was last given, and an emulator wraps whatever it is written at the width it has *then*: one that has
 * not been laid out has its default, so a typed line that took three rows came back cut, a prompt's redraws
 * landed on the wrong rows, and nothing reflowed once the real width was known. The web fits its terminal
 * before it connects and sends its size first thing after the socket opens; so does this, and the owner's
 * emulator is at that size, and still empty, when it reports it. At most [MAX_HELD_BYTES] are held, the
 * newest, as the server's own buffer is. A reconnect or a restart drops them: the server replays the shell
 * again, and a restart is another shell.
 */
class TerminalController internal constructor(
    private val api: ApiClient,
    private val project: String,
    tabId: String,
    scope: CoroutineScope,
    private val store: KeyValueStore,
    keyPrefix: String,
    private val cwd: String?,
    initialSessionId: String,
    private val onOutput: (ByteArray) -> Unit,
    private val onReset: () -> Unit,
    private val clock: () -> Long,
    private val connectSocket: (String) -> TerminalConnection,
    private val onReplay: ((ByteArray) -> Unit)? = null,
) : AutoCloseable {
    /**
     * [keyPrefix] must identify the server; the key is `${keyPrefix}ppm:terminal-session:${tabId}`.
     * The cached id wins over tab metadata's [initialSessionId]. [cwd] is sent only for `new`.
     * Call [setVisible] when shown/hidden; a controller starts with resizing disabled.
     */
    constructor(
        api: ApiClient,
        project: String,
        tabId: String,
        scope: CoroutineScope,
        store: KeyValueStore,
        keyPrefix: String,
        cwd: String? = null,
        initialSessionId: String = "new",
        onOutput: (ByteArray) -> Unit,
        onReset: () -> Unit,
        onReplay: ((ByteArray) -> Unit)? = null,
    ) : this(
        api, project, tabId, scope, store, keyPrefix, cwd, initialSessionId, onOutput, onReset,
        System::currentTimeMillis, ::TerminalSocket, onReplay,
    )

    private val controllerScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val storageKey = "${keyPrefix}ppm:terminal-session:$tabId"
    private var sessionId = readSession() ?: initialSessionId.ifBlank { "new" }
    private val mutableState = MutableStateFlow(TerminalState())
    val state: StateFlow<TerminalState> = mutableState.asStateFlow()

    private var socket: TerminalConnection? = null
    private var socketJob: Job? = null
    private var reconnectJob: Job? = null
    private var resizeJob: Job? = null
    private var quietJob: Job? = null
    private var readyJob: Job? = null
    private var started = false
    private var closed = false
    private var hasConnected = false

    /** The session this connection asked for: what the URL named, `new` for a shell not yet made. */
    private var requestedSession = "new"

    /** Set by the `session` frame that says the server picked an existing shell up again; the next output is its buffer. */
    private var replayPending = false
    private var visible = false
    private var failures = 0
    private var lastActivity = 0L
    private var size: Pair<Int, Int>? = null
    private var sentSize: Pair<Int, Int>? = null

    /** What arrived before [size] was first known, oldest first; see the class comment. */
    private val held = ArrayDeque<Held>()

    /** What [held] adds up to, for the cap. A test reads it to see that nothing is kept once the output is written or dropped. */
    internal var heldBytes = 0
        private set
    private val active get() = started && !closed && controllerScope.isActive

    /** Idempotent. Construction alone never opens a socket. */
    fun start() {
        if (started || closed || !controllerScope.isActive) return
        started = true
        // Parent cancellation must close the transport too, even without an explicit close().
        controllerScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                close()
            }
        }
        connect()
        controllerScope.launch {
            while (isActive) {
                delay(15_000)
                probe(foreground = false)
            }
        }
    }

    /** Hidden tabs keep their socket/output, but never resize the remote PTY. */
    fun setVisible(visible: Boolean) {
        if (closed || this.visible == visible) return
        this.visible = visible
        resizeJob?.cancel()
        if (visible) scheduleResize()
    }

    /**
     * Retain the last usable size; send only after 100 ms of quiet and while visible. The first usable one is not waited
     * out: the owner's emulator is already that size and empty, so it is sent at once (or as the socket opens) and what the
     * shell printed meanwhile is written into the emulator now.
     */
    fun resize(cols: Int, rows: Int) {
        if (closed) return
        resizeJob?.cancel()
        if (cols < 20 || rows < 2) return
        val first = size == null
        size = cols to rows
        if (first) {
            sendSize()
            release()
        } else {
            scheduleResize()
        }
    }

    /** Raw keystrokes. Returns false when unavailable; input is never queued for another shell. */
    fun send(text: String): Boolean {
        val source = socket ?: return false
        if (!active || !state.value.connected || state.value.exited || state.value.error != null) return false
        if (source.send(text)) return true
        disconnected(source, "The message could not be sent.")
        return false
    }

    /** One complete UTF-8 input chunk, decoded just as the server decodes binary input frames. */
    fun sendBytes(bytes: ByteArray): Boolean = send(bytes.toString(Charsets.UTF_8))

    /** Probe immediately after app foregrounding; bypass a pending backoff if disconnected. */
    fun foreground() = probe(foreground = true)

    /** Forget this tab's session and create a fresh shell, including its original [cwd]. */
    fun restart() {
        if (!active) return
        disconnectSocket()
        sessionId = "new"
        persistSession(null)
        failures = 0
        mutableState.value = TerminalState()
        discardHeld()
        connect()
    }

    /** Permanent, idempotent disposal. Keeps the cached session for a later owner of this tab. */
    override fun close() {
        if (closed) return
        closed = true
        disconnectSocket()
        controllerScope.cancel()
        mutableState.value = state.value.copy(connected = false, reconnecting = false, shellReady = false)
    }

    private fun connect() {
        if (!active || state.value.exited) return
        disconnectSocket()
        mutableState.value = state.value.copy(connected = false, reconnecting = hasConnected || failures > 0, shellReady = false)
        lastActivity = clock()
        requestedSession = sessionId
        replayPending = false
        val source = try {
            connectSocket(terminalSocketUrl(api, project, sessionId, cwd))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            scheduleReconnect(error.message ?: "The connection ended.")
            return
        }
        socket = source
        socketJob = controllerScope.launch {
            try {
                for (event in source.events) {
                    if (source !== socket || !active) break
                    when (event) {
                        SocketConnection.Event.Opened -> opened(source)
                        is SocketConnection.Event.Text -> frame(event.text, event.text.toByteArray(Charsets.UTF_8))
                        is SocketConnection.Event.Binary -> frame(event.bytes.utf8(), event.bytes.toByteArray())
                        is SocketConnection.Event.Closed -> disconnected(source, event.reason)
                    }
                }
                disconnected(source, null)
            } finally {
                source.close()
            }
        }
    }

    private fun opened(source: TerminalConnection) {
        if (hasConnected) {
            // The server replays the whole shell again, so whatever was held for the last connection is in it.
            discardHeld()
            onReset()
        }
        // An owner can close/restart from its reset callback.
        if (!active || source !== socket) return
        hasConnected = true
        failures = 0
        lastActivity = clock()
        mutableState.value = TerminalState(connected = true, sessionId = state.value.sessionId)
        readyJob = controllerScope.launch {
            delay(3_000)
            markReady()
        }
        // As the web's onopen does: the shell reads its size from here on, and there is no burst of sizes to wait out.
        sendSize()
    }

    private fun frame(text: String, bytes: ByteArray) {
        lastActivity = clock()
        if (text == "\u0001PONG") return
        val json = if (text.startsWith("{")) WireJson.parseOrNull(text) else null
        val type = json["type"]
        // Match the web's truthy type check; malformed/untyped JSON remains ordinary PTY output.
        val control = type != null && type !is JsonNull && type.string != "" && type.bool != false && type.double != 0.0
        if (control) {
            when (type.string) {
                "session" -> json["id"].string?.takeIf { it.isNotBlank() }?.let {
                    // The server answers a connection with this frame and then, if the shell has printed anything,
                    // its whole buffer as one frame, with nothing to say where the replay ends. It is a replay only
                    // when the shell asked for already exists: `new`, or an id the server no longer knows (it makes
                    // another shell and says so with another id), has no buffer, and its first output is live.
                    replayPending = requestedSession != "new" && it == requestedSession
                    sessionId = it
                    mutableState.value = state.value.copy(sessionId = it)
                    persistSession(it)
                }
                "error" -> {
                    replayPending = false
                    cancelReadiness()
                    val message = json["message"].string ?: "Terminal error"
                    mutableState.value = state.value.copy(error = message, shellReady = false)
                    deliver("\r\n\u001b[31mError: $message\u001b[0m\r\n".toByteArray(Charsets.UTF_8), replay = false)
                }
                "exited" -> {
                    replayPending = false
                    cancelReadiness()
                    mutableState.value = state.value.copy(exited = true, shellReady = false, reconnecting = false)
                }
            }
            return
        }
        if (!state.value.shellReady && !state.value.exited && state.value.error == null) {
            quietJob?.cancel()
            quietJob = controllerScope.launch {
                delay(250)
                markReady()
            }
        }
        // Only the first output after that frame is the replay; whatever follows is live and its queries are answered.
        // A shell that has printed nothing has no buffer to replay, so its first live output is taken for one and its
        // queries go unanswered that once. The mistake the other way, a replay answered, is what this exists to stop.
        val replay = replayPending
        replayPending = false
        deliver(bytes, replay)
    }

    /** To the owner's emulator, or held until the owner has a size to lay it out at. */
    private fun deliver(bytes: ByteArray, replay: Boolean) {
        if (size != null) write(bytes, replay) else hold(bytes)
    }

    private fun write(bytes: ByteArray, replay: Boolean) {
        if (replay && onReplay != null) onReplay.invoke(bytes) else onOutput(bytes)
    }

    private fun hold(bytes: ByteArray) {
        held.addLast(Held(bytes))
        heldBytes += bytes.size
        // Keep the newest [MAX_HELD_BYTES]: whole pieces off the front, then the front of the oldest left, never inside a character.
        while (heldBytes > MAX_HELD_BYTES) {
            val oldest = held.first()
            val excess = heldBytes - MAX_HELD_BYTES
            if (oldest.size <= excess) {
                held.removeFirst()
                heldBytes -= oldest.size
            } else {
                var cut = oldest.from + excess
                while (cut < oldest.bytes.size && (oldest.bytes[cut].toInt() and 0xC0) == 0x80) cut++
                heldBytes -= cut - oldest.from
                oldest.from = cut
            }
        }
    }

    /**
     * The first size is known: what was held goes to the emulator it was waiting for, in the order it arrived, all of it as a
     * replay: a live piece that was held is no longer live, and nobody was there to answer what it asked.
     */
    private fun release() {
        val pieces = held.toList()
        discardHeld()
        for (piece in pieces) {
            if (closed) return
            if (piece.size > 0) write(if (piece.from == 0) piece.bytes else piece.bytes.copyOfRange(piece.from, piece.bytes.size), replay = true)
        }
    }

    private fun discardHeld() {
        held.clear()
        heldBytes = 0
    }

    private fun markReady() {
        if (!active || !state.value.connected || state.value.exited || state.value.error != null) return
        mutableState.value = state.value.copy(shellReady = true)
        cancelReadiness()
    }

    private fun cancelReadiness() {
        quietJob?.cancel()
        quietJob = null
        readyJob?.cancel()
        readyJob = null
    }

    private fun scheduleResize() {
        if (!active || !visible || !state.value.connected || state.value.exited || size == null || size == sentSize) return
        resizeJob?.cancel()
        resizeJob = controllerScope.launch {
            delay(100)
            sendSize()
        }
    }

    private fun sendSize() {
        val dimensions = size ?: return
        if (visible && send("\u0001RESIZE:${dimensions.first},${dimensions.second}")) sentSize = dimensions
    }

    private fun probe(foreground: Boolean) {
        if (!active || state.value.exited) return
        val source = socket
        if (source == null) {
            // The heartbeat must not truncate the exponential backoff. A foreground does.
            if (foreground) connect()
        } else if (clock() - lastActivity > 35_000) {
            connect()
        } else if (state.value.connected && !source.send("\u0001PING")) {
            disconnected(source, "The message could not be sent.")
            if (foreground) connect()
        }
    }

    private fun disconnected(source: TerminalConnection, reason: String?) {
        if (source !== socket) return
        disconnectSocket()
        mutableState.value = state.value.copy(connected = false, shellReady = false)
        if (active && !state.value.exited) scheduleReconnect(reason)
    }

    private fun scheduleReconnect(reason: String?) {
        mutableState.value = state.value.copy(reconnecting = true, error = reason ?: state.value.error)
        val wait = minOf(1_000L shl minOf(failures, 5), 30_000L)
        failures = minOf(failures + 1, 5)
        reconnectJob = controllerScope.launch {
            delay(wait)
            connect()
        }
    }

    private fun disconnectSocket() {
        replayPending = false
        reconnectJob?.cancel()
        reconnectJob = null
        resizeJob?.cancel()
        resizeJob = null
        cancelReadiness()
        val previous = socket
        socket = null // Ignore an old socket's close/queued frames before closing it.
        socketJob?.cancel()
        socketJob = null
        previous?.close()
        sentSize = null
    }

    private fun readSession(): String? = try {
        store.get(storageKey)?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    private fun persistSession(value: String?) {
        try {
            store.put(storageKey, value)
        } catch (_: Exception) {
            // A storage failure must not discard the live session or interrupt output.
        }
    }
}

/** The most [TerminalController] holds for an owner that has reported no size: what the server keeps of a shell's output (`MAX_BUFFER_SIZE`, 1 MB). */
internal const val MAX_HELD_BYTES = 1024 * 1024

/** One frame held back. [from] moves up when the front of the oldest piece is cut to keep within [MAX_HELD_BYTES]. */
private class Held(val bytes: ByteArray) {
    var from = 0
    val size get() = bytes.size - from
}
