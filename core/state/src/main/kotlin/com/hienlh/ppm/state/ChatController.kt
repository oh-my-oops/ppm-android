package com.hienlh.ppm.state

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.SocketConnection
import com.hienlh.ppm.protocol.ApprovalRequest
import com.hienlh.ppm.protocol.ChatClientMessage
import com.hienlh.ppm.protocol.ChatFrame
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.SessionPhase
import com.hienlh.ppm.protocol.TurnStop
import com.hienlh.ppm.protocol.WireJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

/** Everything one chat shows, as one value the UI collects. */
data class ChatState(
    val sessionId: String,
    val items: List<ChatItem> = emptyList(),
    /** The blocks of the assistant message being streamed. */
    val live: List<ChatBlock>? = null,
    val phase: SessionPhase = SessionPhase.IDLE,
    val pendingApproval: ApprovalRequest? = null,
    val turnStop: TurnStop? = null,
    val title: String? = null,
    val compacting: Boolean = false,
    val notice: String? = null,
    val historyStart: Int = 0,
    val hasLoadedHistory: Boolean = false,
    val connection: ChatController.Connection = ChatController.Connection.Connecting,
    val historyError: String? = null,
    val loadingOlder: Boolean = false,
) {
    val isRunning: Boolean get() = phase.isRunning
    val canSend: Boolean get() = connection == ChatController.Connection.Connected
}

/**
 * One open chat: loads its history, keeps its socket connected, and feeds both into a
 * [ChatTranscript]. Its work runs in a child of [parent], whose dispatcher must be a single
 * thread (the main one); call every method from that thread, and [stop] when the chat closes.
 */
class ChatController(
    private val api: ApiClient,
    val project: String,
    session: SessionInfo,
    parent: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface Connection {
        data object Connecting : Connection
        data object Connected : Connection
        data class Reconnecting(val reason: String?) : Connection
    }

    val transcript = ChatTranscript()
    val providerId: String = session.providerId
    var sessionId: String = session.id
        private set

    private val _state = MutableStateFlow(ChatState(sessionId = session.id))
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private var connection: Connection = Connection.Connecting
    private var historyError: String? = null
    private var loadingOlder = false
    private var socket: SocketConnection? = null
    private var lastFrameAt = clock()
    private var closeReason: String? = null
    private var failures = 0
    private var stopped = false
    private var reloading = false
    private var reloadAgain = false

    fun start() {
        scope.launch {
            // History first: a snapshot arriving before it would be merged into nothing.
            loadInitialHistory()
            if (!stopped) connect()
        }
        scope.launch {
            while (true) {
                delay(5_000)
                closeIfSilent()
            }
        }
    }

    fun stop() {
        stopped = true
        scope.cancel()
        socket?.close()
        socket = null
    }

    fun send(text: String) {
        val content = text.trim()
        val socket = socket
        if (content.isEmpty() || socket == null || connection != Connection.Connected) return
        socket.send(transcript.send(content, clock()).json.toString())
        publish()
    }

    fun cancel() {
        socket?.send(ChatClientMessage.Cancel.json.toString())
    }

    fun respond(request: ApprovalRequest, approved: Boolean, data: JsonElement? = null) {
        val socket = socket ?: return
        socket.send(transcript.respond(request, approved, data).json.toString())
        publish()
    }

    fun dismissNotice() {
        transcript.dismissNotice()
        publish()
    }

    suspend fun loadOlder() {
        if (transcript.historyStart <= 0 || loadingOlder) return
        loadingOlder = true
        publish()
        val before = transcript.historyStart
        try {
            val page = api.history(project, sessionId, providerId, limit = 50, before = before)
            transcript.prependHistory(page, before)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            historyError = describe(error)
        } finally {
            loadingOlder = false
            publish()
        }
    }

    /** Reads the history again — the web's reload button above the composer. */
    fun reload() = reloadHistory()

    /** Drops the connection as a network loss would; it comes back by itself. */
    fun simulateConnectionLoss() {
        socket?.close()
    }

    // Socket

    private fun connect() {
        if (stopped) return
        val socket = SocketConnection(api.chatSocketUrl(project, sessionId, providerId))
        this.socket = socket
        closeReason = null
        lastFrameAt = clock()
        scope.launch {
            for (event in socket.events) receive(event)
            socketEnded(socket)
        }
    }

    private suspend fun receive(event: SocketConnection.Event) {
        when (event) {
            SocketConnection.Event.Opened -> {
                failures = 0
                connection = Connection.Connected
                lastFrameAt = clock()
                transcript.socketOpened()
                socket?.send(ChatClientMessage.Ready.json.toString())
            }
            is SocketConnection.Event.Text -> frame(event.text)
            is SocketConnection.Event.Binary -> frame(event.bytes.utf8())
            // `close()` reports no reason; keep the one it was closed for.
            is SocketConnection.Event.Closed -> if (event.reason != null) closeReason = event.reason
        }
        publish()
    }

    private suspend fun frame(text: String) {
        lastFrameAt = clock()
        // The snapshot of a long turn can run to megabytes: decode it off the main thread.
        val frame = withContext(Dispatchers.Default) { WireJson.parseOrNull(text)?.let(ChatFrame::from) } ?: return
        perform(transcript.handle(frame, clock()))
    }

    private fun socketEnded(ended: SocketConnection) {
        if (ended !== socket || stopped) return
        socket = null
        failures++
        connection = Connection.Reconnecting(closeReason)
        publish()
        // 0.25 s, 0.5, 1, 2, 4, then every 8 s.
        val wait = minOf(250L shl minOf(failures - 1, 5), 8_000L)
        scope.launch {
            delay(wait)
            connect()
        }
    }

    private fun closeIfSilent() {
        val socket = socket ?: return
        if (connection != Connection.Connected || clock() - lastFrameAt <= SILENCE_LIMIT_MS) return
        closeReason = "No answer from the server for ${SILENCE_LIMIT_MS / 1000} seconds."
        socket.close()
    }

    private fun perform(effects: List<ChatEffect>) {
        for (effect in effects) {
            when (effect) {
                is ChatEffect.Send -> socket?.send(effect.message.json.toString())
                ChatEffect.ReloadHistory -> reloadHistory()
                is ChatEffect.AdoptSessionId -> sessionId = effect.id
            }
        }
    }

    // History

    private suspend fun loadInitialHistory() {
        val token = transcript.activity
        try {
            val page = api.history(project, sessionId, providerId, limit = 50)
            page.canonicalSessionId?.let { sessionId = it }
            transcript.applyHistory(page, token, clock())
            historyError = null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            historyError = describe(error)
        }
        publish()
    }

    /** Reloads what is shown. Overlapping requests collapse into one more run. */
    private fun reloadHistory() {
        if (reloading) {
            reloadAgain = true
            return
        }
        reloading = true
        scope.launch {
            try {
                do {
                    reloadAgain = false
                    val token = transcript.activity
                    // The same window as shown: everything from the first loaded message on.
                    val start = if (transcript.hasLoadedHistory) transcript.historyStart else null
                    try {
                        val page = api.history(
                            project, sessionId, providerId,
                            limit = if (start == null) 50 else null, from = start,
                        )
                        page.canonicalSessionId?.let { sessionId = it }
                        transcript.applyHistory(page, token, clock())
                        historyError = null
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        historyError = describe(error)
                    }
                    publish()
                } while (reloadAgain && !stopped)
            } finally {
                reloading = false
            }
        }
    }

    private fun publish() {
        _state.value = ChatState(
            sessionId = sessionId,
            items = transcript.items,
            live = transcript.live?.blocks,
            phase = transcript.phase,
            pendingApproval = transcript.pendingApproval,
            turnStop = transcript.turnStop,
            title = transcript.title,
            compacting = transcript.compacting,
            notice = transcript.notice,
            historyStart = transcript.historyStart,
            hasLoadedHistory = transcript.hasLoadedHistory,
            connection = connection,
            historyError = historyError,
            loadingOlder = loadingOlder,
        )
    }

    companion object {
        /**
         * The server pings every 5 s; this long without any frame means the socket is dead even
         * if the OS has not noticed (as on the web).
         */
        const val SILENCE_LIMIT_MS = 45_000L

        internal fun describe(error: Exception): String = error.message ?: error.toString()
    }
}
