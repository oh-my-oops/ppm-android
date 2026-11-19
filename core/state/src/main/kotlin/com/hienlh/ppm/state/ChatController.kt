package com.hienlh.ppm.state

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.ChatClientId
import com.hienlh.ppm.client.SocketConnection
import com.hienlh.ppm.client.teamMessages
import com.hienlh.ppm.client.teams
import com.hienlh.ppm.protocol.ApprovalRequest
import com.hienlh.ppm.protocol.BackgroundShell
import com.hienlh.ppm.protocol.ChatClientMessage
import com.hienlh.ppm.protocol.ProviderDefaults
import com.hienlh.ppm.protocol.ChatEvent
import com.hienlh.ppm.protocol.ChatFrame
import com.hienlh.ppm.protocol.EffortStatusReport
import com.hienlh.ppm.protocol.InlineImage
import com.hienlh.ppm.protocol.PickedAccount
import com.hienlh.ppm.protocol.PromptCacheState
import com.hienlh.ppm.protocol.QueuedMessage
import com.hienlh.ppm.protocol.ReplyReference
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.SessionPhase
import com.hienlh.ppm.protocol.AssistantUiRequest
import com.hienlh.ppm.protocol.AssistantUiResult
import com.hienlh.ppm.protocol.TabOpenAnswer
import com.hienlh.ppm.protocol.isAssistantProject
import com.hienlh.ppm.protocol.TabOpenRequest
import com.hienlh.ppm.protocol.TaskItem
import com.hienlh.ppm.protocol.TurnOptions
import com.hienlh.ppm.protocol.TurnStop
import com.hienlh.ppm.protocol.VersionGroup
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.generated.TurnReference
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
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/** Everything one chat shows, as one value the UI collects. */
data class ChatState(
    /** Null while a new chat waits for its first message, which creates the session. */
    val sessionId: String?,
    val providerId: String? = null,
    val items: List<ChatItem> = emptyList(),
    /** The blocks of the assistant message being streamed. */
    val live: List<ChatBlock>? = null,
    /** When [live] got its first event. */
    val liveStartedAt: Instant? = null,
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
    /** What [ChatTranscript.userOrdinalOffset] holds. */
    val userOrdinalOffset: Int = 0,
    /** What [ChatTranscript.historyPredecessorId] holds. */
    val historyPredecessorId: String? = null,
    /** The edited versions in this session's tree, by user-message ordinal. */
    val versionMap: Map<Int, VersionGroup> = emptyMap(),
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
    /** The account the stream last said a turn runs on (`liveAccount`): an `account_info`, or a retry moved onto another. */
    val liveAccount: PickedAccount? = null,
    /** The session's agent teams and what their inboxes received. */
    val team: TeamActivity = TeamActivity(),
    /** The commands the AI left running in the background, stopped ones included. */
    val backgroundShells: List<BackgroundShell> = emptyList(),
    /** The MCP servers this chat's Claude cannot use until someone signs in. */
    val mcpNeedsAuth: List<String> = emptyList(),
    /** Messages sent while a turn ran, not yet read by the AI, in the order it will read them: the server's list. */
    val queue: List<QueuedMessage> = emptyList(),
    /** What decides the effort the session runs at; null while nothing is known (no Claude session yet, an older server). */
    val effortStatus: EffortStatusReport? = null,
    /** A queued message this device asked back, for the composer ([ChatController.queueAnswerTaken]). */
    val queueAnswer: QueueTakeBackAnswer? = null,
    /** What [ChatTranscript.turnBindings] holds: the turn each message sent here opened, by the id it went under. */
    val turnBindings: Map<String, String> = emptyMap(),
) {
    val isRunning: Boolean get() = phase.isRunning
    /** The socket is open. A message sent while it is not shows at once and goes out once it is. */
    val connected: Boolean get() = connection == ChatController.Connection.Connected

    /** [items] with the turn being streamed after them as one more message, as the web's list holds it. */
    fun itemsWithLive(): List<ChatItem> {
        val blocks = live ?: return items
        return items + ChatItem("live", ChatItem.Role.ASSISTANT, blocks, liveStartedAt)
    }
}

/**
 * One open chat: loads its history, keeps its socket connected, and feeds both into a
 * [ChatTranscript]. Its work runs in a child of [parent], whose dispatcher must be a single
 * thread (the main one); call every method from that thread, and [stop] when the chat closes.
 *
 * A new chat has no [session] until its first message creates one (`useChat(null, …)`): until
 * [adopt] it loads and connects nothing, its provider can still change, and the model, effort
 * and thinking picked meanwhile ride on that first message.
 */
class ChatController(
    private val api: ApiClient,
    val project: String,
    session: SessionInfo?,
    parent: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    providerId: String = session?.providerId ?: "claude",
    /** What the chat it takes over from showed, shown until [session]'s history is in ([ChatTranscript.showStale]). */
    stale: List<ChatItem> = emptyList(),
    /**
     * The chat tab this chat belongs to (`?clientId=`): sent on every connect and kept across
     * reconnects, so the server knows the device a message came from. A tab hands the same id to
     * every chat it shows; a chat made on its own mints one.
     */
    val clientId: String = ChatClientId.mint(),
) {
    sealed interface Connection {
        data object Connecting : Connection
        data object Connected : Connection
        data class Reconnecting(val reason: String?) : Connection
    }

    val transcript = ChatTranscript(providerId).also { it.showStale(stale) }
    val settings = ChatSettings()

    /**
     * Opens what the AI's tab tools ask this device for (`tab_open`) and says how it went; the app
     * sets it. Without one every request is refused at once: the AI's tool waits for an answer
     * either way, so none is ever left unanswered.
     */
    var tabOpener: (suspend (TabOpenRequest) -> TabOpenAnswer)? = null

    /**
     * Answers the PPM Assistant's screen tools (`assistant_ui`) with what the result carries; the
     * app sets it for an Assistant session. Without one, or for a chat outside the Assistant's
     * project, every request is refused at once, as the web's device refuses it.
     */
    var assistantUi: (suspend (AssistantUiRequest) -> AssistantUiResult)? = null

    /** An Assistant session's picture of this device's screen, read for every message it sends (`uiSummary`). */
    var uiSummary: (() -> JsonObject?)? = null

    /**
     * The AI settings' default model and effort for a provider. A chat with no session has had no
     * `session_state`, so until it does its state shows these: what its first message runs on
     * unless something is picked (only picks ride on a message, [ChatSettings.turnOptions]).
     */
    var defaultsFor: ((providerId: String) -> ProviderDefaults?)? = null
        set(value) {
            field = value
            publish()
        }

    /** The settings behind [defaultsFor] changed. */
    fun defaultsChanged() = publish()

    /** Hears what may need the user's attention: the app decides whether the chat is in front. */
    var onAttention: ((ChatAttention) -> Unit)? = null
    var providerId: String = providerId
        private set
    var sessionId: String? = session?.id
        private set

    private val _state = MutableStateFlow(ChatState(sessionId = sessionId, providerId = providerId, items = transcript.shown))
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
    private var liveAccount: PickedAccount? = null
    private var team = TeamActivity()
    private var socket: SocketConnection? = null
    /** Frames sent while the socket was down, in order: the next connection sends them after `ready` (`ws-client.ts`). */
    private val unsent = ArrayList<String>()
    /** The wait before the next attempt after a drop, which [reconnect] cuts short. */
    private var retry: Job? = null
    private var lastFrameAt = clock()
    private var closeReason: String? = null
    private var failures = 0
    private var stopped = false
    private var reloading = false
    private var reloadAgain = false

    fun start() {
        scope.launch {
            while (true) {
                delay(5_000)
                closeIfSilent()
            }
        }
        if (sessionId != null) open()
    }

    /** The session a new chat's first message created (`setSessionId`): its history is read, then its socket opened. */
    fun adopt(session: SessionInfo) {
        if (sessionId != null || stopped) return
        sessionId = session.id
        providerId = session.providerId
        transcript.providerId = session.providerId
        publish()
        open()
    }

    /** The provider a chat with no session yet will start on (`handleProviderChange`); a session keeps its own. */
    fun chooseProvider(id: String) {
        if (sessionId != null || id == providerId) return
        providerId = id
        transcript.providerId = id
        publish()
    }

    private fun open() {
        scope.launch {
            // History first: a snapshot arriving before it would be merged into nothing.
            loadInitialHistory()
            if (stopped) return@launch
            connect()
            rehydrateTeam()
        }
    }

    fun stop() {
        stopped = true
        scope.cancel()
        socket?.close()
        socket = null
        unsent.clear()
    }

    /**
     * [options] carries the composer's mode; the model, effort and thinking are added
     * here. [replyTo] goes only if it quotes this chat: the server refuses any other. [images] ride
     * in the frame and [imagePaths] name their uploads; a message of pictures alone may have no text.
     * While the socket is down the message shows at once and goes out once it is back. Sent while
     * a turn runs it is queued instead ([ChatState.queue]); [sendNow] then has the AI read the queue at once.
     */
    fun send(
        text: String,
        options: TurnOptions = TurnOptions(),
        replyTo: ReplyReference? = null,
        images: List<InlineImage> = emptyList(),
        imagePaths: List<String> = emptyList(),
        sendNow: Boolean = false,
        /** Turns of other sessions the message points at, shown as chips (`turnRefs`). */
        turnRefs: List<TurnReference> = emptyList(),
    ) {
        val content = text.trim()
        if ((content.isEmpty() && images.isEmpty()) || sessionId == null || stopped) return
        val reply = replyTo?.takeIf { it.sessionId == sessionId && it.providerId == providerId }
        val message = transcript.send(content, settings.turnOptions(options), clock(), reply, images, imagePaths, sendNow, turnRefs, project)
        transmit(uiSummary?.invoke()?.let { message.copy(uiSummary = it) } ?: message)
        onAttention?.invoke(ChatAttention.Answered)
        publish()
    }

    /** Shown at once and stored for the session. Without a connection the pick still rides on the next message, as on the web. */
    fun setModel(model: String) = pick(settings.pickModel(model))

    fun setEffort(effort: Effort) = pick(settings.pickEffort(effort))

    fun setThinking(enabled: Boolean) = pick(settings.pickThinking(enabled))

    /** The model, effort and thinking the next message carries (`turnSettings`): what is known or picked, nothing else. */
    val turnPicks: TurnOptions get() = settings.turnOptions(TurnOptions())

    private fun pick(frame: ChatClientMessage) {
        transmit(frame)
        publish()
    }

    fun cancel() {
        transmit(ChatClientMessage.Cancel)
        transcript.cancelled()
        onAttention?.invoke(ChatAttention.Answered)
        publish()
    }

    /** Asks the AI to stop a background command; the next `background_registry` says `stopping`. */
    fun killBackgroundShell(shellId: String) {
        if (shellId.isEmpty()) return
        transmit(ChatClientMessage.KillBackgroundShell(shellId))
    }

    fun respond(request: ApprovalRequest, approved: Boolean, data: JsonElement? = null) {
        if (stopped) return
        transmit(transcript.respond(request, approved, data))
        onAttention?.invoke(ChatAttention.Answered)
        publish()
    }

    fun takeRejected() {
        transcript.takeRejected()
        publish()
    }

    /**
     * Edit on a queued message (`takeBackQueued`): the server answers with its text, which lands in
     * [ChatState.queueAnswer]. Remove ([discard]) drops it instead, so nothing comes back.
     */
    fun takeBackQueued(id: String, discard: Boolean = false) {
        if (stopped || id.isEmpty()) return
        transmit(transcript.queue.takeBack(id, discard))
    }

    /** Send now (`sendQueuedNow`): the AI reads the queue now instead of at its next step. */
    fun sendQueuedNow() {
        if (stopped) return
        transmit(ChatClientMessage.QueueSendNow)
    }

    /** The composer has [ChatState.queueAnswer]. */
    fun queueAnswerTaken() {
        transcript.queue.answerTaken()
        publish()
    }

    /** `markTeamRead`: the team panel was opened. */
    fun markTeamRead() {
        team = team.read()
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
        val session = sessionId ?: return
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
                    transcript.prependHistory(api.history(project, session, providerId, limit = 50, before = before), before)
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

    /** Reads the history again. */
    fun reload() = reloadHistory()

    /**
     * The toolbar's reload and the reconnect pill's Retry (`reloadChat`): a socket that is not open
     * starts over at once, in place of an attempt still under way, and an idle chat reads its
     * messages again.
     */
    fun reloadChat() {
        if (connection != Connection.Connected) reconnect()
        if (!transcript.isRunning) reloadHistory()
    }

    /**
     * The app came to the front, as the web's socket does on `visibilitychange`: one that is down
     * tries again now instead of waiting out its backoff, and one gone silent is replaced. Nothing
     * while one is opening.
     */
    fun resumed() {
        if (socket == null && connection is Connection.Reconnecting) reconnect() else closeIfSilent()
    }

    /** Drops the connection as a network loss would; it comes back by itself. */
    fun simulateConnectionLoss() {
        socket?.close()
    }

    // Socket

    private fun connect() {
        val session = sessionId ?: return
        if (stopped) return
        val socket = SocketConnection(api.chatSocketUrl(project, session, providerId, clientId))
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
                // What was sent while it was down goes after `ready`, in order.
                for (text in unsent) socket?.send(text)
                unsent.clear()
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
        when (val event = (frame as? ChatFrame.Event)?.event) {
            is ChatEvent.AccountInfo -> event.accountId?.let { liveAccount = PickedAccount(it, event.accountLabel) }
            // Only a retry that names where it went: the server was forced off the account shown.
            is ChatEvent.AccountRetry -> if (event.accountId != null && event.accountLabel != null) liveAccount = PickedAccount(event.accountId!!, event.accountLabel)
            else -> {}
        }
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
        retry = scope.launch {
            delay(wait)
            retry = null
            connect()
        }
    }

    /** On the socket when it is open; otherwise kept for the next connection, as the web's socket keeps it. */
    private fun transmit(message: ChatClientMessage) {
        val text = message.json.toString()
        val socket = socket
        if (socket != null && connection == Connection.Connected) socket.send(text) else if (!stopped) unsent += text
    }

    private fun closeIfSilent() {
        if (socket == null || connection != Connection.Connected || clock() - lastFrameAt <= SILENCE_LIMIT_MS) return
        closeReason = "No answer from the server for ${SILENCE_LIMIT_MS / 1000} seconds."
        reconnect()
    }

    /**
     * A new socket now, in place of the one open, opening or waited for (`WsClient.connect`). The
     * old one is let go first, so nothing sent meanwhile goes out on it: it waits for the new one.
     */
    private fun reconnect() {
        if (stopped || sessionId == null) return
        retry?.cancel()
        retry = null
        failures = 0
        socket?.let { old ->
            socket = null
            old.close()
        }
        if (connection == Connection.Connected) connection = Connection.Reconnecting(closeReason)
        publish()
        connect()
    }

    private fun perform(effects: List<ChatEffect>) {
        for (effect in effects) {
            when (effect) {
                is ChatEffect.Send -> transmit(effect.message)
                ChatEffect.ReloadHistory -> reloadHistory()
                is ChatEffect.AdoptSessionId -> sessionId = effect.id
                is ChatEffect.TeamDetected -> {
                    team = team.withTeam(effect.name)
                    // Every time, a replay's too: inbox pushes are never replayed, so this is what
                    // brings back the messages a dropped connection missed.
                    loadTeamMessages(effect.name)
                }
                is ChatEffect.TeamMessages -> team = team.received(effect.messages)
                is ChatEffect.OpenTab -> openTab(effect.request)
                is ChatEffect.AssistantUi -> answerAssistantUi(effect.request)
                is ChatEffect.Attention -> onAttention?.invoke(effect.attention)
            }
        }
    }

    /**
     * Opens the tab and answers on this socket once it is open, or checked (`answerTabOpen`). Off the
     * frame loop: a page check takes seconds, and the frames behind it must not wait.
     */
    private fun openTab(request: TabOpenRequest) {
        fun answer(result: TabOpenAnswer) =
            transmit(ChatClientMessage.TabOpenResult(request.requestId, result.opened, result.error, result.report))
        val opener = tabOpener ?: return answer(TabOpenAnswer(opened = false, error = TAB_OPEN_UNSUPPORTED))
        scope.launch {
            val result = try {
                opener(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TabOpenAnswer(opened = false, error = describe(e))
            }
            answer(result)
        }
    }

    /** Answers a screen tool on this socket (`answerAssistantUi`); off the frame loop, as [openTab]. */
    private fun answerAssistantUi(request: AssistantUiRequest) {
        val handler = assistantUi?.takeIf { isAssistantProject(project) }
            ?: return transmit(AssistantUiResult(request.requestId, null, ASSISTANT_UI_NOT_ASSISTANT))
        scope.launch {
            val result = try {
                handler(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AssistantUiResult(request.requestId, null, e.message?.takeIf { it.isNotBlank() } ?: "The device could not do this.")
            }
            transmit(result)
        }
    }

    // Team

    /**
     * The team named after this session, read back from disk: `team_detected` is only ever live,
     * and its replay ends with the turn, so a reopened chat would lose a team still at work.
     * Only that name: nothing on disk ties any other team to a session.
     */
    private suspend fun rehydrateTeam() {
        val session = sessionId ?: return
        val found = try {
            api.teams().any { it.name == session }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return
        }
        if (!found || stopped) return
        team = team.withTeam(session)
        publish()
        loadTeamMessages(session)
    }

    private fun loadTeamMessages(name: String) {
        scope.launch {
            val messages = try {
                api.teamMessages(name)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Deleted, or unreadable mid-write.
                return@launch
            }
            team = team.merged(messages)
            publish()
        }
    }

    // History

    private suspend fun loadInitialHistory() {
        val session = sessionId ?: return
        val token = transcript.activity
        try {
            val page = api.history(project, session, providerId, limit = 50)
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
        if (sessionId == null) return
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
                    val session = sessionId ?: break
                    try {
                        val page = api.history(
                            project, session, providerId,
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
        val session = sessionId ?: return
        val calls = transcript.shown.sumOf { it.taskCalls } + (transcript.live?.taskCalls ?: 0)
        val key = session to calls
        if (key == tasksAskedFor) return
        tasksAskedFor = key
        tasksJob?.cancel()
        if (calls == 0) {
            tasks = emptyList()
            return
        }
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
        val newChatDefaults = if (sessionId == null) defaultsFor?.invoke(providerId) else null
        _state.value = ChatState(
            sessionId = sessionId,
            providerId = providerId,
            items = transcript.shown,
            live = transcript.live?.blocks,
            liveStartedAt = transcript.live?.let { Instant.ofEpochMilli(transcript.liveStartedAt) },
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
            userOrdinalOffset = transcript.userOrdinalOffset,
            historyPredecessorId = transcript.historyPredecessorId,
            versionMap = transcript.versionMap,
            connection = connection,
            historyError = historyError,
            hasEarlier = transcript.historyStart > 0 || transcript.unexpandedCompact != null,
            loadingEarlier = loadingEarlier,
            earlierError = earlierError,
            tasks = tasks,
            model = settings.model ?: newChatDefaults?.model,
            effort = settings.effort ?: newChatDefaults?.effort,
            thinking = settings.thinking,
            promptCache = settings.promptCache,
            liveAccount = liveAccount,
            team = team,
            backgroundShells = transcript.backgroundShells,
            mcpNeedsAuth = transcript.mcpNeedsAuth,
            queue = transcript.queue.messages,
            queueAnswer = transcript.queue.answer,
            effortStatus = transcript.effortStatus,
            turnBindings = transcript.turnBindings,
        )
    }

    companion object {
        /**
         * The server pings every 5 s; this long without any frame means the socket is dead even
         * if the OS has not noticed (as on the web).
         */
        const val SILENCE_LIMIT_MS = 45_000L

        const val TAB_OPEN_UNSUPPORTED =
            "The PPM app on this device cannot open tabs yet. Open this chat in the PPM web app to see it."

        /** The web's refusal for a chat that is no Assistant session (`answer-assistant-ui.ts`). */
        const val ASSISTANT_UI_NOT_ASSISTANT =
            "This chat is not a PPM Assistant session; its device does not take Assistant requests."

        internal fun describe(error: Exception): String = error.message ?: error.toString()
    }
}
