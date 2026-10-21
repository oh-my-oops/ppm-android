package com.hienlh.ppm.state

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.SocketConnection
import com.hienlh.ppm.protocol.ApprovalRequest
import com.hienlh.ppm.protocol.ChatClientMessage
import com.hienlh.ppm.protocol.ChatEvent
import com.hienlh.ppm.protocol.ChatFrame
import com.hienlh.ppm.protocol.PromptCacheState
import com.hienlh.ppm.protocol.ReplyReference
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.SessionPhase
import com.hienlh.ppm.protocol.TaskItem
import com.hienlh.ppm.protocol.TurnOptions
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
    val providerId: String? = null,
    val items: List<ChatItem> = emptyList(),
    /** The blocks of the assistant message being streamed. */
    val live: List<ChatBlock>? = null,
    val phase: SessionPhase = SessionPhase.IDLE,
    val pendingApproval: ApprovalRequest? = null,
    val turnStop: TurnStop? = null,
    /** What each running shell call of the live turn has printed, by call. */
    val bashOutput: Map<String, BashPartial> = emptyMap(),
    val title: String? = null,
    val compacting: Boolean = false,
    val connectingElapsed: Int = 0,
    val statusMessage: String? = null,
    /** A refused send for the composer to take back ([ChatController.takeRejected]). */
    val rejected: RejectedSend? = null,
    val historyStart: Int = 0,
    val hasLoadedHistory: Boolean = false,
    val connection: ChatController.Connection = ChatController.Connection.Connecting,
    val historyError: String? = null,
    /** Something above what is shown can still be loaded: an older page, or the conversation before a compaction. */
    val hasEarlier: Boolean = false,
    val loadingEarlier: Boolean = false,
    /** Why loading what is above failed; nothing more loads by itself until it is asked again. */
    val earlierError: String? = null,
    /** Claude's tasks in this chat, read while it shows calls to its task tools. */
    val tasks: List<TaskItem> = emptyList(),
    /** What [ChatSettings] holds. */
    val model: String? = null,
    val effort: String? = null,
    val thinking: Boolean = true,
    val promptCache: PromptCacheState? = null,
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
    val settings = ChatSettings()
    val providerId: String = session.providerId
    var sessionId: String = session.id
        private set

    private val _state = MutableStateFlow(ChatState(sessionId = session.id, providerId = session.providerId))
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private var connection: Connection = Connection.Connecting
    private var historyError: String? = null
    private var loadingEarlier = false
    private var earlierError: String? = null
    private var tasks: List<TaskItem> = emptyList()
    /** The session and the count of task-tool calls [tasks] were last asked for. */
    private var tasksAskedFor: Pair<String, Int>? = null
    private var tasksJob: Job? = null
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

    /**
     * [options] carries the composer's mode and priority; the model, effort and thinking are added
     * here. [replyTo] goes only if it quotes this chat: the server refuses any other.
     */
    fun send(text: String, options: TurnOptions = TurnOptions(), replyTo: ReplyReference? = null) {
        val content = text.trim()
        val socket = socket
        if (content.isEmpty() || socket == null || connection != Connection.Connected) return
        val reply = replyTo?.takeIf { it.sessionId == sessionId && it.providerId == providerId }
        socket.send(transcript.send(content, settings.turnOptions(options), clock(), reply).json.toString())
        publish()
    }

    /** Shown at once and stored for the session. Without a connection the pick still rides on the next message, as on the web. */
    fun setModel(model: String) = pick(settings.pickModel(model))

    fun setEffort(effort: Effort) = pick(settings.pickEffort(effort))

    fun setThinking(enabled: Boolean) = pick(settings.pickThinking(enabled))

    private fun pick(frame: ChatClientMessage) {
        socket?.send(frame.json.toString())
        publish()
    }

    fun cancel() {
        socket?.send(ChatClientMessage.Cancel.json.toString())
        transcript.cancelled()
        publish()
    }

    fun respond(request: ApprovalRequest, approved: Boolean, data: JsonElement? = null) {
        val socket = socket ?: return
        socket.send(transcript.respond(request, approved, data).json.toString())
        publish()
    }

    fun takeRejected() {
        transcript.takeRejected()
        publish()
    }

    fun dismissMessage(id: String) {
        transcript.dismissMessage(id)
        publish()
    }

    fun clearErrors() {
        transcript.clearErrors()
        publish()
    }

    /**
     * What is above the shown messages (`loadMore`): the next older page while there is one, and
     * after the last of them the conversation before the topmost compact summary. A failure lands in
     * [ChatState.earlierError]; asking again is the retry. Runs in the chat's own scope, so that
     * whatever asked can go away meanwhile.
     */
    fun loadEarlier() {
        if (loadingEarlier) return
        val before = transcript.historyStart
        val compact = transcript.unexpandedCompact
        val path = compact?.transcriptPath
        if (before <= 0 && (compact == null || path == null)) return
        loadingEarlier = true
        earlierError = null
        publish()
        scope.launch {
            try {
                if (before > 0) {
                    transcript.prependHistory(api.history(project, sessionId, providerId, limit = 50, before = before), before)
                } else if (compact != null && path != null) {
                    transcript.expand(compact.id, path, api.preCompactMessages(project, path, PreCompact.rawId(compact.id)))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                earlierError = describe(error)
            } finally {
                loadingEarlier = false
                publish()
            }
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
        if (frame is ChatFrame.SessionState) settings.apply(frame.state)
        val ended = (frame as? ChatFrame.Event)?.event as? ChatEvent.Done
        val usage = ended?.usage
        if (usage != null && ended.parent == null && transcript.phase.isRunning) settings.turnEnded(usage, clock())
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

    /**
     * `useTasks`: the tasks read again 300 ms after the count of task-tool calls shown changes, and
     * none while there are none. A failed read keeps what was there.
     */
    private fun askForTasks() {
        val calls = transcript.shown.sumOf { it.taskCalls } + (transcript.live?.taskCalls ?: 0)
        val key = sessionId to calls
        if (key == tasksAskedFor) return
        tasksAskedFor = key
        tasksJob?.cancel()
        if (calls == 0) {
            tasks = emptyList()
            return
        }
        val session = sessionId
        tasksJob = scope.launch {
            delay(300)
            try {
                tasks = api.sessionTasks(project, session)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@launch
            }
            publish()
        }
    }

    private fun publish() {
        askForTasks()
        _state.value = ChatState(
            sessionId = sessionId,
            providerId = providerId,
            items = transcript.shown,
            live = transcript.live?.blocks,
            phase = transcript.phase,
            pendingApproval = transcript.pendingApproval,
            turnStop = transcript.turnStop,
            bashOutput = transcript.bashOutput,
            title = transcript.title,
            compacting = transcript.compacting,
            connectingElapsed = transcript.connectingElapsed,
            statusMessage = transcript.statusMessage,
            rejected = transcript.rejected,
            historyStart = transcript.historyStart,
            hasLoadedHistory = transcript.hasLoadedHistory,
            connection = connection,
            historyError = historyError,
            hasEarlier = transcript.historyStart > 0 || transcript.unexpandedCompact != null,
            loadingEarlier = loadingEarlier,
            earlierError = earlierError,
            tasks = tasks,
            model = settings.model,
            effort = settings.effort,
            thinking = settings.thinking,
            promptCache = settings.promptCache,
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
