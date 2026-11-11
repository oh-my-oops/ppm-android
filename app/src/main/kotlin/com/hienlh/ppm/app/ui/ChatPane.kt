package com.hienlh.ppm.app.ui

import android.content.ContentResolver
import android.net.Uri
import android.view.DragEvent
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.selectAll
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ChatTab
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.NewChatGate
import com.hienlh.ppm.app.PREWARM_SETTLE_MILLIS
import com.hienlh.ppm.app.PrewarmAsk
import com.hienlh.ppm.app.attach
import com.hienlh.ppm.app.tracksTextFocus
import com.hienlh.ppm.app.ui.files.ENTRY_DRAG_MIME
import com.hienlh.ppm.app.ui.files.TREE_PATH_MIME
import com.hienlh.ppm.app.ui.tablet.dashedBorder
import com.hienlh.ppm.chat.AgentSessionContent
import com.hienlh.ppm.chat.AgentSessionOpener
import com.hienlh.ppm.chat.AsyncQuestions
import com.hienlh.ppm.chat.BackgroundCommandBar
import com.hienlh.ppm.chat.CONTINUE_AFTER_STOP
import com.hienlh.ppm.chat.ChatEdits
import com.hienlh.ppm.chat.ChatImages
import com.hienlh.ppm.chat.ChatTabs
import com.hienlh.ppm.chat.ChatTranscriptView
import com.hienlh.ppm.chat.FirstSendView
import com.hienlh.ppm.chat.LocalAgentSessionOpener
import com.hienlh.ppm.chat.LocalAsyncQuestions
import com.hienlh.ppm.chat.LocalChatImages
import com.hienlh.ppm.chat.LocalChatTabs
import com.hienlh.ppm.chat.LocalMarkdownImages
import com.hienlh.ppm.chat.LocalRunInTerminal
import com.hienlh.ppm.chat.LocalToolImages
import com.hienlh.ppm.chat.LocalTurnChanges
import com.hienlh.ppm.chat.MarkdownImages
import com.hienlh.ppm.chat.McpSignInBar
import com.hienlh.ppm.chat.ToolImages
import com.hienlh.ppm.chat.TurnChangesHost
import com.hienlh.ppm.chat.TurnStopBar
import com.hienlh.ppm.app.ui.phone.chat.PhoneQueuedMessagesStack
import com.hienlh.ppm.app.ui.phone.chat.QueuedMessageAnswerEffect
import com.hienlh.ppm.app.ui.phone.chat.phoneQueuedMessages
import com.hienlh.ppm.client.chatUsage
import com.hienlh.ppm.client.teamActivity
import com.hienlh.ppm.design.CssColumn
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.WebCursor
import com.hienlh.ppm.design.chromeCaret
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssFlex
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberChromeCaret
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.protocol.ChatEvent
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.TeamMemberActivity
import com.hienlh.ppm.protocol.TurnOptions
import com.hienlh.ppm.protocol.UsageInfo
import com.hienlh.ppm.protocol.agentTranscriptProvider
import com.hienlh.ppm.state.AgentActivityWatch
import com.hienlh.ppm.state.AgentSessionFallbacks
import com.hienlh.ppm.state.AgentSessionIdentity
import com.hienlh.ppm.state.AgentSessionPayload
import com.hienlh.ppm.state.ChatController
import com.hienlh.ppm.state.ChatState
import com.hienlh.ppm.state.ReconnectStage
import com.hienlh.ppm.state.SessionCollabState
import com.hienlh.ppm.state.UsageCache
import com.hienlh.ppm.state.UsageReader
import com.hienlh.ppm.state.UsageScope
import com.hienlh.ppm.state.payload
import com.hienlh.ppm.state.pollTeamActivity
import com.hienlh.ppm.state.workspace.SourceLine
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * One chat tab (`chat-tab.tsx`): the conversation (fading out over its bottom 12dp), optional
 * stopped-turn and MCP sign-in bars, and the unified chat footer — one context row of chips
 * (activity, session changes, connection on the left; usage rings, History, and ··· on the right)
 * directly over the single-bar composer, with floating `/` and `@` pickers and footer popovers.
 */
@Composable
fun ChatPane(model: AppModel, tab: ChatTab, modifier: Modifier = Modifier) {
    val chat = tab.controller ?: return
    val state by chat.state.collectAsState()
    val mobile = LocalPpmMobile.current
    val density = LocalDensity.current

    // The title the server gives a new chat goes into every list and onto its tab, as the web's tab carries it.
    LaunchedEffect(state.title) {
        state.title?.takeIf { it.isNotEmpty() }?.let {
            tab.session?.let { session -> model.renameSession(session.id, it) }
            model.retitleTab(tab.tabId, it)
        }
    }
    // Text the tab was opened with is in the box now: once connected, the tab stops offering it.
    LaunchedEffect(state.connected, state.sessionId) {
        if (state.connected && state.sessionId != null) model.pendingMessageTaken(tab)
    }
    // A new chat on screen has the process its first message will run on started while that
    // message is written (`useChatPrewarm`), with what it will carry: asked once the picks hold
    // still, again as they change. Not while its provider resolves, which would warm a guess.
    val prewarm = if (tab.session == null && !tab.providerPending) {
        PrewarmAsk(tab.providerId, tab.permissionMode?.value, tab.claimedAccountId, chat.turnPicks)
    } else {
        null
    }
    DisposableEffect(tab, prewarm) {
        tab.prewarm = prewarm
        onDispose { if (tab.prewarm == prewarm) tab.prewarm = null }
    }
    LaunchedEffect(tab, prewarm) {
        if (prewarm == null) return@LaunchedEffect
        delay(PREWARM_SETTLE_MILLIS)
        model.newChats.prewarm(tab, prewarm)
    }
    QueuedMessageAnswerEffect(model, tab, chat, state)
    // A message the server refused (`onMessageRejected`): back in the box when the box holds
    // nothing it would join, else offered back from the toast.
    LaunchedEffect(state.rejected) {
        val rejected = state.rejected ?: return@LaunchedEffect
        fun restore() {
            tab.restore(rejected)
            model.toast(ErrorToastMessage("Message not sent", "${rejected.message} Your text is back in the input."))
        }
        if (tab.draft.text.isBlank() && tab.replyTo == null) {
            restore()
        } else {
            model.toast(ErrorToastMessage("Message not sent", rejected.message, ErrorToastMessage.Action("Restore", ::restore)))
        }
        chat.takeRejected()
    }
    // This chat's usage reading (`useUsage`): the session's binding, or before one the account the
    // tab claimed — nothing while its provider resolves, which would read a guess.
    val fallbackCache = remember { UsageCache() }
    val usageCache = model.usageCache ?: fallbackCache
    val usageReader = remember(usageCache, tab) {
        UsageReader(usageCache) { scope, refresh ->
            model.api?.chatUsage(scope.project, scope.providerId, scope.sessionId, scope.previewAccountId, refresh)
        }
    }
    val usageScope = if (tab.providerPending) null else UsageScope(tab.project, tab.providerId, tab.session?.id, tab.claimedAccountId)
    LaunchedEffect(usageReader, usageScope) {
        if (usageScope != null) usageReader.run(usageScope, model.newChats.preparation(tab.tabId))
    }
    val usageSnapshot by usageReader.snapshot.collectAsState()
    val usageLoading by usageReader.loading.collectAsState()
    val usage = usageReader.shown(usageScope, usageSnapshot)
    val paneScope = rememberCoroutineScope()
    // The stream's report of who served is the second writer of the serving account, taken once
    // each: a later turn's report, even of the same account, outranks a pick made since. Rotation
    // keeps the session, so the reading's scope stays put: its binding is read again at once.
    val live = state.liveAccount
    SideEffect {
        if (live !== tab.liveAccountTaken) {
            val moved = live != null && live.id != tab.liveAccountTaken?.id
            tab.liveAccountTaken = live
            if (live != null) tab.servingAccount = live
            if (moved) paneScope.launch { usageReader.read(refresh = false) }
        }
    }
    // The session this chat's Agent cards and running agents belong to (`AgentSessionContext`).
    val currentSessionId = tab.session?.id ?: state.sessionId
    val agentIdentity = AgentSessionIdentity(tab.project, agentTranscriptProvider(tab.providerId), currentSessionId.orEmpty())
    var dockedAgent by remember(tab.tabId) { mutableStateOf<AgentSessionPayload?>(null) }
    var dockedFallbacks by remember(tab.tabId) { mutableStateOf(AgentSessionFallbacks()) }
    var dockWidthDp by remember(tab.tabId) { mutableFloatStateOf(440f) }
    var preferAgentWindow by remember { mutableStateOf(false) }
    val openAgentSessionFromChat: (AgentSessionPayload, List<ChatEvent>?) -> Unit = remember(model, mobile, preferAgentWindow) {
        { payload, fallback ->
            if (payload.sessionId.isNotEmpty()) {
                if (fallback != null) dockedFallbacks = dockedFallbacks.with(payload.key, fallback)
                if (mobile || preferAgentWindow) {
                    model.openAgentSession(payload, fallback)
                } else {
                    dockedAgent = payload
                }
            }
        }
    }
    val agentOpener = remember(agentIdentity, openAgentSessionFromChat) {
        AgentSessionOpener(agentIdentity, openAgentSessionFromChat)
    }
    // Who is running (`useAgentActivity`), and the team's members (`useTeamActivityFeed`) for their type and start.
    val channel = model.events
    val agentActivity = remember(channel, agentIdentity) {
        if (channel != null && agentIdentity.sessionId.isNotEmpty() && agentIdentity.projectName.isNotEmpty()) {
            AgentActivityWatch(channel, agentIdentity.projectName, agentIdentity.providerId, agentIdentity.sessionId)
        } else {
            null
        }
    }
    DisposableEffect(agentActivity) {
        agentActivity?.start()
        onDispose { agentActivity?.stop() }
    }
    val runningAgents = agentActivity?.running?.collectAsState()?.value.orEmpty()
    val primaryTeam = state.team.primary
    var teamMembers by remember(primaryTeam) { mutableStateOf(emptyList<TeamMemberActivity>()) }
    val api = model.api
    LaunchedEffect(primaryTeam, api) {
        if (primaryTeam == null || api == null) return@LaunchedEffect
        pollTeamActivity({ api.teamActivity(primaryTeam) }) { teamMembers = it.members }
    }
    // Seed `/ws/global`'s link map for this chat (`useLinkedSessions`).
    LaunchedEffect(api, tab.project, currentSessionId) {
        if (api != null && tab.project.isNotEmpty() && !currentSessionId.isNullOrEmpty()) {
            model.seedSessionLinks(tab.project, currentSessionId)
        }
    }
    val collabState = channel?.collab?.collectAsState()?.value ?: SessionCollabState()
    val streamingSessions = channel?.running?.collectAsState()?.value.orEmpty()
    val unreads = channel?.unread?.collectAsState()?.value.orEmpty()
    val unlinkedForChat = if (!currentSessionId.isNullOrEmpty()) model.unlinkedSessions[currentSessionId].orEmpty() else emptyMap()

    // Connection indicator (`useReconnectIndicator`): 800ms grace, 8s lost, restarted on foreground resume.
    val reconnect = rememberReconnectIndicator(
        isReconnecting = state.connection is ChatController.Connection.Reconnecting,
        windowVisible = model.windowVisible,
    )
    LaunchedEffect(model.resumes) {
        if (model.resumes > 0) reconnect.restart()
    }

    val itemsWithLive = remember(state.items, state.live, state.liveStartedAt) { state.itemsWithLive() }
    val sessionChanges = tab.sessionChanges
    val isRunning = state.isRunning
    LaunchedEffect(sessionChanges, itemsWithLive, isRunning) {
        if (sessionChanges != null) {
            val (paths) = tab.sessionWrites.of(itemsWithLive)
            sessionChanges.update(paths, isRunning)
        }
    }

    val pickedLabel = tab.servingAccount?.label ?: tab.claimedAccountLabel
    val pickedAccountId = tab.servingAccount?.id ?: tab.claimedAccountId
    val activity = LocalActivity.current
    val context = LocalContext.current
    val fileDrop = remember(tab) {
        ChatFileDrop(tab) { uris, drag ->
            activity?.requestDragAndDropPermissions(drag)
            model.attach(context, tab, uris)
        }
    }
    val popoverHost = remember(tab.tabId) { FooterPopoverHostState() }
    val pickers = rememberComposerPickers(model, tab)

    var contextRowLeftPx by remember { mutableIntStateOf(0) }
    var contextRowTopPx by remember { mutableIntStateOf(0) }
    var contextRowWidthPx by remember { mutableIntStateOf(0) }
    var composerLeftPx by remember { mutableIntStateOf(0) }
    var composerTopPx by remember { mutableIntStateOf(0) }
    var composerWidthPx by remember { mutableIntStateOf(0) }
    val coordsHolder = remember {
        object {
            var root: LayoutCoordinates? = null
            var row: LayoutCoordinates? = null
            var composer: LayoutCoordinates? = null
        }
    }
    fun syncOverlayBounds() {
        val root = coordsHolder.root
        if (root == null || !root.isAttached) return
        val row = coordsHolder.row
        if (row != null && row.isAttached) {
            val pos = root.localPositionOf(row, Offset.Zero)
            contextRowLeftPx = pos.x.roundToInt()
            contextRowTopPx = pos.y.roundToInt()
            contextRowWidthPx = row.size.width
        }
        val comp = coordsHolder.composer
        if (comp != null && comp.isAttached) {
            val pos = root.localPositionOf(comp, Offset.Zero)
            composerLeftPx = pos.x.roundToInt()
            composerTopPx = pos.y.roundToInt()
            composerWidthPx = comp.size.width
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .onGloballyPositioned {
                coordsHolder.root = it
                syncOverlayBounds()
            }
            .dropZone(shouldStartDragAndDrop = { true }, target = fileDrop),
    ) {
        val paneWidthDp = maxWidth.value
        val isPortrait = maxWidth < maxHeight
        val activeDockedAgent = if (!mobile) dockedAgent else null
        val dockMode = resolveTabletAgentDockMode(paneWidthDp, isPortrait, preferAgentWindow)
        val clampedDockWidthDp = clampTabletAgentDockWidth(dockWidthDp, paneWidthDp)
        val dockedSide = activeDockedAgent != null && dockMode == TabletAgentDockMode.DOCKED_SIDE
        val effectiveChatWidth = if (dockedSide) (maxWidth - (clampedDockWidthDp + 12f).dp).coerceAtLeast(420.dp) else maxWidth
        // The chat's own `@container`: under 420 px the chips drop their words, under 600 px changes drop line totals.
        val narrow = effectiveChatWidth <= 420.dp
        val hideChangeTotals = effectiveChatWidth <= 600.dp
        // `backdrop-blur-sm` under the drop overlay and the edit's: CSS's blur(8px) is Android's at a radius of 13.
        val blurred = if (fileDrop.showing || tab.editForking) Modifier.blur(13.dp) else Modifier

        CompositionLocalProvider(LocalFooterPopoverHost provides popoverHost) {
            Box(
                Modifier
                    .fillMaxSize()
                    .then(if (dockedSide) Modifier.padding(end = (clampedDockWidthDp + 12f).dp) else Modifier)
                    .then(blurred),
            ) {
                CssColumn(Modifier.fillMaxSize()) {
                    if (tab.providerPending) NewChatGateLine(model, tab)
                    BackgroundCommandBar(
                        state.backgroundShells,
                        onViewOutput = { model.openBackgroundOutput(tab.tabId, it.shellId) },
                        onStop = { chat.killBackgroundShell(it.shellId) },
                    )
                    val stop = state.turnStop
                    val showStopBar = stop != null && !state.isRunning
                    val showMcpBar = state.mcpNeedsAuth.any { it !in model.mcpSignInDismissed }
                    val footerDirectlyAfterList = !showStopBar && !showMcpBar

                    // `[data-chat-list] + [data-chat-footer]`: when nothing sits between the message list and
                    // the footer, the footer pulls up 12dp over the list's 12dp bottom fade band.
                    CssColumn(
                        Modifier.cssFlex(grow = true).fillMaxWidth(),
                        gaps = if (footerDirectlyAfterList) listOf(0.dp, (-12).dp) else null,
                    ) {
                        val images = remember(tab.project) {
                            object : ChatImages {
                                override suspend fun load(path: String) = model.chatImage(tab.project, path)
                                override suspend fun read(path: String) = model.chatImageFile(tab.project, path)
                            }
                        }
                        val toolImages = remember(tab.project) { ToolImages { model.toolImage(tab.project, it) } }
                        val markdownImages = remember(tab.project) {
                            object : MarkdownImages {
                                override suspend fun local(path: String) = model.toolImage(tab.project, path)
                                override suspend fun web(url: String) = model.webImage(url)
                            }
                        }
                        val tabs = remember(tab.project, tab.session?.id) {
                            object : ChatTabs {
                                override fun openFile(path: String) = model.openFileTab(tab.project, path)
                                override fun openDiff(path: String, original: String, modified: String) = model.openDiffTab(tab.project, path, original, modified)
                                override fun openToolFile(tool: String, path: String, line: Int?) {
                                    tab.session?.let { model.openToolFileTab(tab.project, it.id, tool, path, line) }
                                }
                                override fun openFileOrSearch(path: String, line: SourceLine?) = model.openFileOrSearch(tab.project, path, line)
                                override fun openFilePath(path: String) = model.openFilePath(path, tab.project)
                            }
                        }
                        CompositionLocalProvider(
                            LocalChatImages provides images,
                            LocalToolImages provides toolImages,
                            LocalMarkdownImages provides markdownImages,
                            LocalChatTabs provides tabs,
                            LocalRunInTerminal provides { command -> model.runInTerminal(command, tab.project) },
                            LocalAgentSessionOpener provides agentOpener,
                            // A Codex question's answer goes out as the composer would send it, in its permission mode.
                            LocalAsyncQuestions provides AsyncQuestions(
                                state.items,
                                if (state.connected) { content -> chat.send(content, TurnOptions(permissionMode = tab.permissionMode?.value)) } else null,
                            ),
                        ) {
                            val preview = tab.firstSendPreview
                            if (preview != null) {
                                FirstSendView(preview.content, preview.timestamp, Modifier.cssFlex(grow = true))
                            } else {
                                val turnChanges = rememberTurnChangesHost(model, tab, state)
                                // Fork and Edit are left out while a turn runs, and the switchers greyed out.
                                val running = state.isRunning
                                val editingId = tab.editFork?.messageId
                                val edits = remember(tab, running, editingId) {
                                    ChatEdits(
                                        onFork = if (running) null else { item, anchor -> model.edits.fork(tab, item, anchor) },
                                        onEdit = if (running) null else tab::armEdit,
                                        editingId = editingId,
                                        onSwitchVersion = { model.edits.switchVersion(tab, it) },
                                        versionsDisabled = running,
                                    )
                                }
                                CompositionLocalProvider(LocalTurnChanges provides turnChanges) {
                                    ChatTranscriptView(
                                        state,
                                        onRespond = { request, approved, data -> chat.respond(request, approved, data) },
                                        onLoadEarlier = chat::loadEarlier,
                                        onReload = { chat.reload() },
                                        modifier = Modifier.cssFlex(grow = true),
                                        jumps = remember(tab.tabId) { model.chatJumps.filter { it.first == tab.tabId }.map { it.second } },
                                        welcome = {
                                            // `w-full px-4`; picking a chat shows it in this tab.
                                            tab.welcomeList?.let { list ->
                                                SessionListPanel(model, list, onOpen = { model.replaceTab(tab.tabId, it) }, Modifier.padding(horizontal = 16.dp))
                                            }
                                        },
                                        welcomeActions = if (model.info?.minimalMode == true) null else ({
                                            com.hienlh.ppm.app.ui.shell.OnboardingEntryRow(model, compact = true)
                                        }),
                                        onDismiss = chat::dismissMessage,
                                        onClearErrors = chat::clearErrors,
                                        jumpRequest = model.chatJump?.takeIf { it.sessionId == state.sessionId },
                                        onJumpTaken = model::chatJumpDone,
                                        // Not while the draft, which may carry a reply of its own, is on its way, nor
                                        // while an edit's version is made.
                                        onReply = if (tab.draftLoading || tab.editForking) null else tab::selectReply,
                                        edits = edits,
                                        bottomFade = true,
                                        overlappedByFooter = footerDirectlyAfterList,
                                    )
                                }
                            }
                        }

                        if (showStopBar && stop != null) {
                            TurnStopBar(stop, onContinue = { chat.send(CONTINUE_AFTER_STOP, TurnOptions(permissionMode = tab.permissionMode?.value)) })
                        }
                        if (showMcpBar) {
                            McpSignInBar(
                                state.mcpNeedsAuth,
                                model.mcpSignInDismissed,
                                onSignIn = { model.openMcpSignIn(tab.project.ifEmpty { null }, it) },
                                onHide = model::dismissMcpSignIn,
                            )
                        }

                        // Unified footer (`data-chat-footer`): `shrink-0 px-2 pb-1.5 md:px-3 md:pb-3`, no border/divider.
                        val footerPadX = if (mobile) 8.dp else 12.dp
                        val footerPadBottom = if (mobile) 6.dp else 12.dp
                        val contextRowTopPad = if (footerDirectlyAfterList) 12.dp else if (mobile) 6.dp else 8.dp
                        val contextRowBottomPad = if (mobile) 6.dp else 8.dp

                        Column(
                            Modifier
                                .fillMaxWidth()
                                .zIndex(20f)
                                .testTag("chat-footer")
                                .padding(start = footerPadX, end = footerPadX, bottom = footerPadBottom),
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .onGloballyPositioned { coords ->
                                        coordsHolder.row = coords
                                        syncOverlayBounds()
                                    }
                                    .testTag("chat-context-row")
                                    .padding(
                                        start = 2.dp,
                                        end = 2.dp,
                                        top = contextRowTopPad,
                                        bottom = contextRowBottomPad,
                                    ),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    Modifier
                                        .weight(1f)
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(if (narrow || mobile) 4.dp else 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    ActivityChip(
                                        sessionId = currentSessionId,
                                        running = runningAgents,
                                        teamMembers = teamMembers,
                                        items = itemsWithLive,
                                        sessions = model.sessions,
                                        collab = collabState,
                                        streamingSessions = streamingSessions,
                                        unreads = unreads,
                                        unlinkedForChat = unlinkedForChat,
                                        narrow = narrow,
                                        onOpenAgent = { openAgentSessionFromChat(it.payload(agentIdentity, primaryTeam.orEmpty()), null) },
                                        onOpenLinkedSession = { view ->
                                            model.openLinkedSession(tab.project, view.sessionId, view.providerId, view.title)
                                        },
                                        onSetSessionsUnlinked = { ids, unlink, atProvider ->
                                            currentSessionId?.let { cid -> model.setSessionsUnlinked(cid, ids, unlink, atProvider) }
                                        },
                                        onStopChildSessions = {
                                            if (!currentSessionId.isNullOrEmpty() && tab.project.isNotEmpty()) {
                                                model.stopChildSessions(tab.project, currentSessionId)
                                            }
                                        },
                                        onError = { title, detail -> model.toast(ErrorToastMessage(title, detail)) },
                                    )
                                    if (model.info?.minimalMode != true && sessionChanges != null) {
                                        val changeFiles by sessionChanges.files.collectAsState()
                                        val changesLoaded by sessionChanges.loaded.collectAsState()
                                        val changesLoading by sessionChanges.loading.collectAsState()
                                        val changesUnloadedCount by sessionChanges.unloadedCount.collectAsState()
                                        SessionChangesChip(
                                            files = changeFiles,
                                            loaded = changesLoaded,
                                            loading = changesLoading,
                                            unloadedCount = changesUnloadedCount,
                                            projectPath = model.projects.firstOrNull { it.name == tab.project }?.path,
                                            providerId = tab.providerId,
                                            narrow = narrow,
                                            hideTotals = hideChangeTotals,
                                            onReview = { path ->
                                                tab.session?.let {
                                                    model.openSessionReview(tab.project, it.id, state.title, tab.providerId, sessionChanges.paths, path)
                                                }
                                            },
                                            onOpen = sessionChanges::refresh,
                                            onRefresh = sessionChanges::refresh,
                                            onSetReviewed = sessionChanges::setReviewed,
                                        )
                                    }
                                    ConnectionChip(
                                        stage = reconnect.stage,
                                        narrow = narrow,
                                        onRetry = {
                                            reconnect.restart()
                                            chat.reloadChat()
                                        },
                                    )
                                }
                                ChatPaneFooterTools(
                                    model = model,
                                    tab = tab,
                                    chat = chat,
                                    state = state,
                                    agentIdentity = agentIdentity,
                                    usageInfo = usage?.usage ?: UsageInfo(),
                                    usageLoading = usageLoading,
                                    lastFetchedAt = usage?.fetchedAt,
                                    onRefreshUsage = { usageReader.read(refresh = true) },
                                    onReadUsageQuietly = { usageReader.read(refresh = false) },
                                    pickedAccountId = pickedAccountId,
                                    pickedAccountLabel = pickedLabel,
                                    narrow = narrow,
                                    connectionTrouble = reconnect.stage != ReconnectStage.HIDDEN,
                                )
                            }

                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .onGloballyPositioned { coords ->
                                        coordsHolder.composer = coords
                                        syncOverlayBounds()
                                    },
                            ) {
                                // `QueuedMessagesStack`: what was sent while the AI worked, until it reads it.
                                PhoneQueuedMessagesStack(
                                    messages = phoneQueuedMessages(state.queue),
                                    onSendNow = chat::sendQueuedNow,
                                    onEdit = { chat.takeBackQueued(it) },
                                    onRemove = { chat.takeBackQueued(it, discard = true) },
                                )
                                if (tab.editFork != null) EditingLine(onCancel = tab::cancelEdit)
                                ChatComposer(
                                    model = model,
                                    tab = tab,
                                    chat = chat,
                                    state = state,
                                    narrow = narrow,
                                    fileDrop = fileDrop,
                                    pickers = pickers,
                                    offline = reconnect.stage != ReconnectStage.HIDDEN,
                                )
                            }
                        }
                    }
                }

                // Tablet footer popovers (`FooterPopover`): anchored 4dp (`mb-1`) above `chat-context-row`.
                FooterPopoverScrim(popoverHost)
                if (popoverHost.entries.isNotEmpty() && contextRowWidthPx > 0) {
                    val rowTopDp = with(density) { contextRowTopPx.coerceAtLeast(0).toDp() }
                    val rowWidthDp = with(density) { contextRowWidthPx.toDp() }
                    Box(
                        Modifier
                            .offset { IntOffset(contextRowLeftPx, 0) }
                            .width(rowWidthDp)
                            .height(rowTopDp),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        FooterPopoverHostSlot(popoverHost)
                    }
                }

                // Floating `/` and `@` pickers (`data-composer-picker="floating"`): composer-wide, 6dp (`mb-1.5`)
                // above the composer anchor, overlapping `chat-context-row` without moving it.
                if (pickers.isShowing && composerWidthPx > 0) {
                    val pickerBottomPx = (composerTopPx - with(density) { 6.dp.roundToPx() }).coerceAtLeast(0)
                    val pickerHeightDp = with(density) { pickerBottomPx.toDp() }
                    val composerWidthDp = with(density) { composerWidthPx.toDp() }
                    Box(
                        Modifier
                            .offset { IntOffset(composerLeftPx, 0) }
                            .width(composerWidthDp)
                            .height(pickerHeightDp),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        ComposerPickerList(
                            model = model,
                            tab = tab,
                            pickers = pickers,
                            narrow = narrow,
                            variant = PickerVariant.FLOATING,
                        )
                    }
                }
            }
        }
        if (activeDockedAgent != null) {
            val fallbackEvents = dockedFallbacks[activeDockedAgent.key] ?: model.agentFallbacks[activeDockedAgent.key]
            val onOpenInWindow = {
                preferAgentWindow = true
                dockedAgent = null
                model.openAgentSession(activeDockedAgent, fallbackEvents)
            }
            val onCloseDock = { dockedAgent = null }
            if (dockMode == TabletAgentDockMode.DOCKED_SIDE) {
                Row(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .width((clampedDockWidthDp + 12f).dp)
                        .fillMaxHeight(),
                ) {
                    TabletAgentResizeHandle(
                        onDragDeltaDp = { deltaDp ->
                            dockWidthDp = clampTabletAgentDockWidth(dockWidthDp - deltaDp, paneWidthDp)
                        },
                    )
                    TabletAgentSidePanel(
                        payload = activeDockedAgent,
                        model = model,
                        fallbackEvents = fallbackEvents,
                        onOpenInWindow = onOpenInWindow,
                        onClose = onCloseDock,
                        modifier = Modifier.width(clampedDockWidthDp.dp).fillMaxHeight(),
                    )
                }
            } else if (dockMode == TabletAgentDockMode.SLIDE_OVER) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.42f))
                        .testTag("agent-dock-scrim")
                        .ppmClickable(pressedColor = Color.Transparent, onClick = onCloseDock),
                )
                val slideOverWidth = 560.dp.coerceAtMost((maxWidth - 48.dp).coerceAtLeast(320.dp))
                TabletAgentSidePanel(
                    payload = activeDockedAgent,
                    model = model,
                    fallbackEvents = fallbackEvents,
                    onOpenInWindow = onOpenInWindow,
                    onClose = onCloseDock,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(slideOverWidth)
                        .fillMaxHeight(),
                )
            }
        }
        if (fileDrop.showing) DropFilesOverlay()
        if (tab.editForking) EditForkingOverlay()
    }
}

/** How an agent session is presented from [ChatPane] on a tablet (`Tablet 1 · Chat` Section 9). */
internal enum class TabletAgentDockMode {
    /** Resizable side panel docked to the right of the chat (`320..640` dp, default `440` dp) while keeping chat >= 420 dp. */
    DOCKED_SIDE,
    /** 560 dp slide-over panel from the right edge with a dimmed scrim when portrait or pane width < 752 dp (420 + 12 + 320). */
    SLIDE_OVER,
    /** Floating window in `WindowLayer` when `preferWindow` is chosen (`Open in window`). */
    WINDOW,
}

/**
 * Resolves how an agent session opened from a tablet chat pane should be displayed (`Tablet 1 · Chat`
 * Notes 70, 88–92):
 * - When [preferWindow] is true, opens in `WindowLayer` ([TabletAgentDockMode.WINDOW]).
 * - In portrait ([isPortrait]) or when the pane is narrower than `752` dp (`420` dp min chat + `12` dp handle + `320` dp min dock),
 *   slides over the right side with a scrim ([TabletAgentDockMode.SLIDE_OVER]) so the chat is never squeezed below 420 dp.
 * - Otherwise docks beside the chat ([TabletAgentDockMode.DOCKED_SIDE]).
 */
internal fun resolveTabletAgentDockMode(
    paneWidthDp: Float,
    isPortrait: Boolean,
    preferWindow: Boolean,
): TabletAgentDockMode = when {
    preferWindow -> TabletAgentDockMode.WINDOW
    isPortrait || paneWidthDp < 752f -> TabletAgentDockMode.SLIDE_OVER
    else -> TabletAgentDockMode.DOCKED_SIDE
}

/**
 * Clamps the docked agent panel width (`320..640` dp, default `440` dp) while ensuring the parent
 * chat pane retains at least `420` dp plus the `12` dp resize handle (`Tablet 1 · Chat` Notes 88–89).
 */
internal fun clampTabletAgentDockWidth(requestedWidthDp: Float, paneWidthDp: Float): Float {
    val maxAllowed = (paneWidthDp - 432f).coerceAtMost(640f).coerceAtLeast(320f)
    return requestedWidthDp.coerceIn(320f, maxAllowed)
}

@Composable
private fun TabletAgentResizeHandle(onDragDeltaDp: (Float) -> Unit) {
    val tokens = LocalPpmTokens.current
    val density = LocalDensity.current
    val onDrag by rememberUpdatedState(onDragDeltaDp)
    Box(
        Modifier
            .width(12.dp)
            .fillMaxHeight()
            .testTag("agent-dock-resize-handle")
            .pointerInput(density) {
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(with(density) { dragAmount.toDp().value })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(tokens.border.color))
        Box(
            Modifier
                .size(width = 4.dp, height = 36.dp)
                .background(tokens.text3.opacity(0.35).color, RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
private fun TabletAgentSidePanel(
    payload: AgentSessionPayload,
    model: AppModel,
    fallbackEvents: List<ChatEvent>?,
    onOpenInWindow: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val title = payload.title?.takeIf { it.isNotBlank() } ?: "Agent session"
    Column(
        modifier
            .testTag("agent-dock-panel")
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, sides = CssSides.Start),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(42.dp)
                .background(tokens.panel2.color)
                .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PpmIcon(ProductIcon.Bot, 14.dp, tokens.accent.color)
            BasicText(
                title,
                Modifier.weight(1f),
                style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color),
                maxLines = 1,
            )
            Box(
                Modifier
                    .testTag("agent-dock-open-window")
                    .background(tokens.panel.color, RoundedCornerShape(6.dp))
                    .cssBorder(tokens.border.color, RoundedCornerShape(6.dp))
                    .ppmClickable(pressedColor = tokens.panel2.color, shape = RoundedCornerShape(6.dp), onClick = onOpenInWindow)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    "Open in window",
                    style = ppmText(11f, FontWeight.Medium, lineHeight = 14f).copy(color = tokens.text2.color),
                )
            }
            Box(
                Modifier
                    .size(28.dp)
                    .testTag("agent-dock-close")
                    .ppmClickable(pressedColor = tokens.panel.color, shape = RoundedCornerShape(6.dp), onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.X, 14.dp, tokens.text2.color)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AgentSessionContent(payload, model.events, fallbackEvents)
        }
    }
}

/**
 * Files dragged in from another app, as `chat-tab.tsx` takes them: "Drop files to attach" over the
 * chat while they hover it, then attached once wherever in it they are let go — the message box
 * too, where the web attaches them twice. Chrome reports a drag of text files as text, so it shows
 * no overlay for those, but still attaches them; plain text dropped here is swallowed, as the
 * web's handlers swallow it. A row of the project tree (`application/x-ppm-path`) is no file: it
 * lights the same overlay and its path goes into the box as `@path ` ([ChatTab.dropPath]), once
 * wherever in the chat it is let go. Entries of an explorer window (the shared `application/x-ppm-paths` alone) are neither, as the
 * web's overlay looks only for a file or a tree row: no overlay, and nothing is taken from them.
 * Also the message box's own target, to keep the drop from its field. On a tablet both are zones of the
 * working area's [DropRouter], so a window floating over the chat has what is let go on the window, and the chat has the rest.
 */
class ChatFileDrop(private val tab: ChatTab, private val drop: (List<Uri>, DragEvent) -> Unit) : DragAndDropTarget {
    /** Entries less exits, over the chat and the message box (`dragCounterRef`). */
    private var depth = 0
    private var files = false

    val showing: Boolean
        get() = tab.filesOver

    private fun count(to: Int) {
        depth = to
        tab.filesOver = depth > 0 && files
    }

    override fun onStarted(event: DragAndDropEvent) {
        val description = event.toAndroidDragEvent().clipDescription
        files = description != null && (0 until description.mimeTypeCount).any {
            val type = description.getMimeType(it)
            !type.startsWith("text/") && type != ENTRY_DRAG_MIME
        }
    }

    override fun onEntered(event: DragAndDropEvent) {
        count(depth + 1)
    }

    override fun onExited(event: DragAndDropEvent) {
        count(maxOf(0, depth - 1))
    }

    override fun onEnded(event: DragAndDropEvent) {
        count(0)
    }

    override fun onDrop(event: DragAndDropEvent): Boolean {
        count(0)
        val drag = event.toAndroidDragEvent()
        val clip = drag.clipData ?: return true
        // A row of the project tree is looked for first, as `handleDrop` does: not a file, its path goes into the box.
        if (clip.description.hasMimeType(TREE_PATH_MIME)) {
            clip.getItemAt(0).text?.toString()?.takeIf { it.isNotEmpty() }?.let(tab::dropPath)
            return true
        }
        val uris = (0 until clip.itemCount).mapNotNull { index -> clip.getItemAt(index).uri?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT } }
        if (uris.isNotEmpty()) drop(uris, drag)
        return true
    }
}

/**
 * `absolute inset-0 z-50 flex items-center justify-center bg-background/80 backdrop-blur-sm
 * border-2 border-dashed border-primary rounded-lg`: an upload arrow over "Drop files to
 * attach", `gap-2 text-primary`.
 */
@Composable
private fun BoxScope.DropFilesOverlay() {
    val tokens = LocalPpmTokens.current
    val radius = 12.dp
    Column(
        Modifier
            .matchParentSize()
            .background(tokens.bgSolid.opacity(0.8).color, RoundedCornerShape(radius))
            .dashedBorder(2.dp, tokens.accent.color, radius),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PpmIcon(ProductIcon.Upload, 32.dp, tokens.accent.color)
        BasicText("Drop files to attach", style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.accent.color))
    }
}

/**
 * Over the whole chat while an edit's version is made and connects (`editForking`): `absolute
 * inset-0 z-50 bg-background/70 backdrop-blur-sm`, "Creating edited version…" at its centre, and
 * every touch on the chat stopped there.
 */
@Composable
private fun BoxScope.EditForkingOverlay() {
    val tokens = LocalPpmTokens.current
    val color = tokens.text2.color
    Row(
        Modifier
            .matchParentSize()
            .background(tokens.bgSolid.opacity(0.7).color)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PpmIcon(ProductIcon.Loader2, 16.dp, color, spinning = true)
        BasicText("Creating edited version…", style = ppmText(14f, lineHeight = 20f).copy(color = color))
    }
}

/**
 * Over the message box while an edit is armed (`editFork`): `flex items-center justify-between
 * gap-2 px-3 py-1.5 text-xs text-muted-foreground border-t border-border bg-muted/40`, and Cancel,
 * after which the next send is a new message — `rounded px-2 py-1 min-h-[28px] hover:bg-accent
 * hover:text-accent-foreground transition-colors`, lit while pressed too.
 */
@Composable
private fun EditingLine(onCancel: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val style = ppmText(12f, lineHeight = 16f)
    Row(
        Modifier.fillMaxWidth().background(tokens.panel.opacity(0.4).color).line(top = true, tokens.border.color).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText("Editing message — your next send replaces it", Modifier.weight(1f), style.copy(color = tokens.text2.color))
        val hover = rememberHover()
        val pressed by hover.source.collectIsPressedAsState()
        val lit = if (pressed) 1.0 else hover.lit.toDouble()
        val color = tokens.text.mix(tokens.text2, lit).color
        Row(
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .webTitle("Cancel edit (send as a new message instead)")
                .ppmClickable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, source = hover.source, onClick = onCancel)
                .heightIn(min = 28.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PpmIcon(ProductIcon.X, 14.dp, color)
            BasicText("Cancel", style = style.copy(color = color))
        }
    }
}

/**
 * The line over a new chat whose provider is still being resolved (new-chat-provider-gate.tsx):
 * "Preparing chat…", or why it could not be and what to do about it — `flex flex-wrap items-center
 * gap-2 px-3 py-1 text-xs text-muted-foreground`, its buttons `text-primary underline`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewChatGateLine(model: AppModel, tab: ChatTab) {
    val tokens = LocalPpmTokens.current
    val style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color)
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        when (val gate = tab.gate) {
            is NewChatGate.Unavailable -> {
                BasicText("${gate.provider} is not available. Choose a provider for this chat.", style = style)
                for (provider in gate.alternatives) {
                    GateButton("Use ${provider.name}", style) { model.newChats.select(tab, provider.id, gate.settings) }
                }
                GateButton("Check again", style) { model.newChats.retry(tab) }
            }
            is NewChatGate.Failed -> {
                BasicText("Could not load chat settings. ${gate.message}", style = style)
                GateButton("Retry", style) { model.newChats.retry(tab) }
            }
            null -> BasicText("Preparing chat…", style = style)
        }
    }
}

/** A bare `<button>` of the gate line: its text only, in the primary colour and underlined. */
@Composable
private fun GateButton(label: String, style: TextStyle, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    BasicText(
        label,
        Modifier.pointerHoverIcon(WebCursor.Default).clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick),
        style = style.copy(color = tokens.accent.color, textDecoration = TextDecoration.Underline),
    )
}

/** `border-t` or `border-b`. */
fun Modifier.line(top: Boolean, color: Color): Modifier = cssBorder(color, sides = if (top) CssSides.Top else CssSides.Bottom)

/**
 * `p-0.5 rounded` with a 12 px icon in [tint], and [hoverTint] (on [hoverFill]) under a mouse: at
 * once, as the web's `transition-opacity` leaves colours, unless [fade] (`transition-all`).
 */
@Composable
private fun RowIcon(icon: ProductIcon, tint: Rgba, hoverTint: Rgba, hoverFill: Rgba? = null, fade: Boolean = false, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover(instant = !fade)
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .ppmClickable(
                pressedColor = tokens.panel2.color,
                hoverColor = hoverFill?.color ?: Color.Transparent,
                fade = fade,
                source = hover.source,
                onClick = onClick,
            )
            .padding(2.dp),
    ) {
        PpmIcon(icon, 12.dp, hoverTint.mix(tint, hover.lit.toDouble()).color)
    }
}

/**
 * The web's two rename fields, `bg-surface-elevated py-0.5 rounded border`: the history bar's is
 * `text-[11px] px-1 focus:border-primary` and keeps the page's 1.5 line height, the History list's
 * `text-xs px-1.5 focus:border-primary/50` with that size's own. On a phone both type at 16 px
 * (`globals.css`), each keeping its line height's ratio.
 */
enum class RenameField(val size: Float, val lineRatio: Float, val padding: Dp, val focusAlpha: Double) {
    HistoryBar(11f, 1.5f, 4.dp, 1.0),
    HistoryList(12f, 4f / 3f, 6.dp, 0.5),
}

/** Renaming in place, as [field]: Return or leaving the field saves, the check saves, Escape and the cross give up. */
@Composable
fun InlineRename(model: AppModel, session: SessionInfo, field: RenameField, modifier: Modifier = Modifier, onDone: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val size = if (LocalPpmMobile.current) 16f else field.size
    val state = rememberTextFieldState(session.title)
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    fun finish(save: Boolean) {
        if (finished) return
        finished = true
        val title = state.text.toString()
        onDone()
        if (save) model.scope.launch { model.rename(session, title) }
    }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        state.edit { selectAll() }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val shape = RoundedCornerShape(4.dp)
        val textStyle = ppmText(size, lineHeight = size * field.lineRatio).copy(color = tokens.text.color)
        val caret = rememberChromeCaret(state, textStyle)
        BasicTextField(
            state,
            Modifier
                .weight(1f)
                .focusRequester(focus)
                .tracksTextFocus(state)
                .onFocusChanged {
                    if (it.isFocused) focused = true else if (focused) finish(save = true)
                }
                .onPreviewKeyEvent {
                    val escape = it.type == KeyEventType.KeyDown && it.key == Key.Escape
                    if (escape) finish(save = false)
                    escape
                }
                .clip(shape)
                .background(tokens.panel2.color)
                .cssBorder((if (focused) tokens.accent.opacity(field.focusAlpha) else tokens.border).color, shape)
                .padding(horizontal = field.padding, vertical = 2.dp),
            textStyle = textStyle,
            cursorBrush = caret.brush,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            onKeyboardAction = { finish(save = true) },
            lineLimits = TextFieldLineLimits.SingleLine,
            onTextLayout = caret.onTextLayout,
            interactionSource = caret.interactions,
            decorator = { inner -> Box(Modifier.chromeCaret(caret)) { inner() } },
            scrollState = caret.scroll,
        )
        RowIcon(ProductIcon.Check, tokens.success, tokens.success.opacity(0.8)) { finish(save = true) }
        RowIcon(ProductIcon.X, tokens.text3, tokens.text2) { finish(save = false) }
    }
}

/**
 * What each turn's change pill answers against (`SessionChangesContext`): [tab]'s session list,
 * its Review tab, and on a phone a sheet up to 90% of the page with `rounded-t-[14px]`.
 */
@Composable
internal fun rememberTurnChangesHost(model: AppModel, tab: ChatTab, state: ChatState): TurnChangesHost {
    val changes = tab.sessionChanges
    val title by rememberUpdatedState(state.title)
    val statusBar = WindowInsets.statusBars.getTop(LocalDensity.current)
    return remember(changes, tab, statusBar) {
        val sheetMax = Modifier.layout { measurable, constraints ->
            val max = ((constraints.maxHeight - statusBar) * 0.9f).toInt().coerceIn(constraints.minHeight, constraints.maxHeight)
            val placeable = measurable.measure(constraints.copy(maxHeight = max))
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        TurnChangesHost(
            changes,
            openReview = { path ->
                val session = tab.session
                if (session != null && changes != null) model.openSessionReview(tab.project, session.id, title, tab.providerId, changes.paths, path)
            },
            sheet = { dismiss, content -> BottomSheet(dismiss, sheetMax, radius = 14.dp) { content(LocalSheetContentTop.current) } },
            ownsKeys = { model.activeTabId == tab.tabId },
        )
    }
}
