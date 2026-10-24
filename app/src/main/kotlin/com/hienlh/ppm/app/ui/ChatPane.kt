package com.hienlh.ppm.app.ui

import android.content.ContentResolver
import android.net.Uri
import android.view.DragEvent
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.selectAll
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ChatTab
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.NewChatGate
import com.hienlh.ppm.app.PREWARM_SETTLE_MILLIS
import com.hienlh.ppm.app.PrewarmAsk
import com.hienlh.ppm.app.attach
import com.hienlh.ppm.app.tracksTextFocus
import com.hienlh.ppm.app.ui.tablet.dashedBorder
import com.hienlh.ppm.chat.AgentSessionOpener
import com.hienlh.ppm.chat.CONTINUE_AFTER_STOP
import com.hienlh.ppm.chat.ChatImages
import com.hienlh.ppm.chat.ChatTabs
import com.hienlh.ppm.chat.ChatTranscriptView
import com.hienlh.ppm.chat.FirstSendView
import com.hienlh.ppm.chat.LocalAgentSessionOpener
import com.hienlh.ppm.chat.LocalChatImages
import com.hienlh.ppm.chat.LocalChatTabs
import com.hienlh.ppm.chat.LocalMarkdownImages
import com.hienlh.ppm.chat.LocalToolImages
import com.hienlh.ppm.chat.MarkdownImages
import com.hienlh.ppm.chat.RelativeTime
import com.hienlh.ppm.chat.RunningAgentsBar
import com.hienlh.ppm.chat.ToolImages
import com.hienlh.ppm.chat.TurnStopBar
import com.hienlh.ppm.client.teamActivity
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFormat
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.WebCursor
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.client.chatUsage
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.TeamMemberActivity
import com.hienlh.ppm.protocol.UsageInfo
import com.hienlh.ppm.protocol.agentTranscriptProvider
import com.hienlh.ppm.state.AgentActivityWatch
import com.hienlh.ppm.state.AgentSessionIdentity
import com.hienlh.ppm.state.ChatController
import com.hienlh.ppm.state.ChatState
import com.hienlh.ppm.state.HistoryList
import com.hienlh.ppm.state.UsageCache
import com.hienlh.ppm.state.UsageReader
import com.hienlh.ppm.state.UsageScope
import com.hienlh.ppm.state.payload
import com.hienlh.ppm.state.pollTeamActivity
import com.hienlh.ppm.state.workspace.SourceLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * One chat tab (`chat-tab.tsx`): the conversation, what the server said about the last message,
 * why the last turn stopped, and the bottom toolbar — History and the connection — over the
 * message box.
 */
@Composable
fun ChatPane(model: AppModel, tab: ChatTab, modifier: Modifier = Modifier) {
    val chat = tab.controller ?: return
    val state by chat.state.collectAsState()
    val tokens = LocalPpmTokens.current
    var panel by remember(tab.tabId) { mutableStateOf<ToolbarPanel?>(null) }
    // The title the server gives a new chat goes into every list and onto its tab, as the web's tab carries it.
    LaunchedEffect(state.title) {
        state.title?.takeIf { it.isNotEmpty() }?.let {
            tab.session?.let { session -> model.renameSession(session.id, it) }
            model.retitleTab(tab.tabId, it)
        }
    }
    // Text the tab was opened with is in the box now: once connected, the tab stops offering it.
    LaunchedEffect(state.canSend, state.sessionId) {
        if (state.canSend && state.sessionId != null) model.pendingMessageTaken(tab)
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
    // A message the server refused (`onMessageRejected`): back in the box when the box holds
    // nothing it would join, else offered back from the toast.
    LaunchedEffect(state.rejected) {
        val rejected = state.rejected ?: return@LaunchedEffect
        fun restore() {
            tab.restore(rejected)
            model.toast = ErrorToastMessage("Message not sent", "${rejected.message} Your text is back in the input.")
        }
        if (tab.draft.text.isBlank() && tab.replyTo == null) {
            restore()
        } else {
            model.toast = ErrorToastMessage("Message not sent", rejected.message, ErrorToastMessage.Action("Restore", ::restore))
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
    val agentIdentity = AgentSessionIdentity(tab.project, agentTranscriptProvider(tab.providerId), tab.session?.id ?: state.sessionId.orEmpty())
    val agentOpener = remember(agentIdentity) { AgentSessionOpener(agentIdentity, model::openAgentSession) }
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
    val pickedLabel = tab.servingAccount?.label ?: tab.claimedAccountLabel
    val pickedAccountId = tab.servingAccount?.id ?: tab.claimedAccountId
    val isClaude = tab.providerId.isEmpty() || tab.providerId == "claude"
    val activity = LocalActivity.current
    val context = LocalContext.current
    val fileDrop = remember(tab) {
        ChatFileDrop(tab) { uris, drag ->
            activity?.requestDragAndDropPermissions(drag)
            model.attach(context, tab, uris)
        }
    }
    BoxWithConstraints(modifier.fillMaxSize().dragAndDropTarget(shouldStartDragAndDrop = { true }, target = fileDrop)) {
        // The chat's own `@container`: under 420 px the chips drop their words.
        val narrow = maxWidth <= 420.dp
        // `backdrop-blur-sm` under the drop overlay: CSS's blur(8px) is Android's at a radius of 13.
        Column(Modifier.fillMaxSize().then(if (fileDrop.showing) Modifier.blur(13.dp) else Modifier)) {
            if (tab.providerPending) NewChatGateLine(model, tab)
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
                }
            }
            CompositionLocalProvider(
                LocalChatImages provides images,
                LocalToolImages provides toolImages,
                LocalMarkdownImages provides markdownImages,
                LocalChatTabs provides tabs,
                LocalAgentSessionOpener provides agentOpener,
            ) {
                val preview = tab.firstSendPreview
                if (preview != null) {
                    FirstSendView(preview.content, preview.timestamp, Modifier.weight(1f))
                } else {
                    ChatTranscriptView(
                        state,
                        onRespond = { request, approved, data -> chat.respond(request, approved, data) },
                        onLoadEarlier = chat::loadEarlier,
                        onReload = { chat.reload() },
                        modifier = Modifier.weight(1f),
                        jumps = remember(tab.tabId) { model.chatJumps.filter { it.first == tab.tabId }.map { it.second } },
                        welcome = {
                            // `w-full px-4`; picking a chat shows it in this tab.
                            tab.welcomeList?.let { list ->
                                SessionListPanel(model, list, onOpen = { model.replaceTab(tab.tabId, it) }, Modifier.padding(horizontal = 16.dp))
                            }
                        },
                        onDismiss = chat::dismissMessage,
                        onClearErrors = chat::clearErrors,
                        // Not while the draft, which may carry a reply of its own, is on its way.
                        onReply = if (tab.draftLoading) null else tab::selectReply,
                    )
                }
            }
            // Agents still working: the last thing under the conversation.
            RunningAgentsBar(
                runningAgents,
                teamMembers,
                remember(state.items, state.live, state.liveStartedAt) { state.itemsWithLive() },
                onOpen = { model.openAgentSession(it.payload(agentIdentity, primaryTeam.orEmpty()), null) },
            )
            val stop = state.turnStop
            if (stop != null && !state.isRunning) TurnStopBar(stop, onContinue = { chat.send(CONTINUE_AFTER_STOP) })
            // `border-t border-border bg-panel`, around the toolbar and the message box.
            Column(Modifier.fillMaxWidth().background(tokens.panel.color).line(top = true, tokens.border.color)) {
                Column(Modifier.fillMaxWidth().line(top = false, tokens.border.opacity(0.5).color)) {
                    fun toggle(which: ToolbarPanel) {
                        panel = if (panel == which) null else which
                    }
                    ChatToolbar(
                        model, tab, chat, state, panel, narrow,
                        toggle = ::toggle,
                        usageChip = if (isClaude || tab.providerId == "codex") {
                            {
                                UsageChip(usage?.usage ?: UsageInfo(), pickedLabel, pickedAccountId, panel == ToolbarPanel.Usage, narrow) {
                                    toggle(ToolbarPanel.Usage)
                                }
                            }
                        } else {
                            null
                        },
                    )
                    val list = tab.historyList
                    if (panel == ToolbarPanel.History && list != null) ChatHistoryDropdown(model, tab, list) { panel = null }
                    if (panel == ToolbarPanel.Config) AiSettingsPanel(model)
                    if (panel == ToolbarPanel.Usage && (isClaude || tab.providerId == "codex")) {
                        val shown = usage?.usage ?: UsageInfo()
                        val selectAccount: (suspend (String, String?) -> String?)? = if (!tab.providerPending && !tab.firstSendPending) {
                            { id, label -> model.selectAccount(tab, id, label) { usageReader.read(refresh = false) } }
                        } else {
                            null
                        }
                        if (isClaude) {
                            ClaudeUsagePanel(
                                model,
                                shown,
                                usageLoading,
                                usage?.fetchedAt,
                                selectedAccountId = pickedAccountId ?: shown.activeAccountId,
                                onSelectAccount = selectAccount,
                                onReload = { paneScope.launch { usageReader.read(refresh = true) } },
                                onClose = { panel = null },
                            )
                        } else {
                            CodexUsagePanel(
                                model,
                                shown,
                                selectedAccountId = pickedAccountId ?: shown.activeAccountId,
                                onSelectAccount = selectAccount,
                                onReload = { usageReader.read(refresh = true) },
                                onClose = { panel = null },
                            )
                        }
                    }
                }
                val pickers = rememberComposerPickers(model, tab)
                ComposerPickerList(model, tab, pickers)
                ChatComposer(model, tab, chat, state, narrow, fileDrop, pickers)
            }
        }
        if (fileDrop.showing) DropFilesOverlay()
    }
}

/**
 * Files dragged in from another app, as `chat-tab.tsx` takes them: "Drop files to attach" over the
 * chat while they hover it, then attached once wherever in it they are let go — the message box
 * too, where the web attaches them twice. Chrome reports a drag of text files as text, so it shows
 * no overlay for those, but still attaches them; plain text dropped here is swallowed, as the
 * web's handlers swallow it. Also the message box's own target, to keep the drop from its field.
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
        files = description != null && (0 until description.mimeTypeCount).any { !description.getMimeType(it).startsWith("text/") }
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

/** The panel the toolbar has open under it; one at a time. */
private enum class ToolbarPanel { History, Config, Usage }

/** `border-t` or `border-b`. */
fun Modifier.line(top: Boolean, color: Color): Modifier = cssBorder(color, sides = if (top) CssSides.Top else CssSides.Bottom)

/**
 * The row over the message box (`chat-history-bar.tsx`): History, AI settings and the usage chip
 * on the left, scrolling sideways when they run out of room; on the right the reload button with
 * the connection dot, then the overflow menu. AI settings show the chat's provider once it has a
 * session, else a gear.
 */
@Composable
private fun ChatToolbar(
    model: AppModel,
    tab: ChatTab,
    chat: ChatController,
    state: ChatState,
    panel: ToolbarPanel?,
    narrow: Boolean,
    toggle: (ToolbarPanel) -> Unit,
    usageChip: (@Composable () -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ToolbarChip(ProductIcon.History, if (narrow) null else "History", active = panel == ToolbarPanel.History) { toggle(ToolbarPanel.History) }
            val provider = tab.providerId
            if (tab.session != null && provider.isNotEmpty() && provider != "mock") {
                ToolbarChip(if (narrow) null else capitalized(provider), active = panel == ToolbarPanel.Config, onClick = { toggle(ToolbarPanel.Config) }) { color ->
                    ProviderBadge(provider, foreground = color)
                }
            } else {
                SettingsGearButton(active = panel == ToolbarPanel.Config) { toggle(ToolbarPanel.Config) }
            }
            usageChip?.invoke()
        }
        Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ReloadButton(state.canSend) { chat.reload() }
            // Its actions are a session's.
            tab.session?.let { OverflowButton(model, it) }
        }
    }
}

@Composable
private fun ToolbarChip(icon: ProductIcon, title: String?, active: Boolean, onClick: () -> Unit) =
    ToolbarChip(title, active, onClick) { color ->
        Box(Modifier.size(12.dp, 16.5.dp), contentAlignment = Alignment.Center) { PpmIcon(icon, 12.dp, color) }
    }

/**
 * `px-1.5 py-0.5 rounded text-[11px]`, accent-tinted while its panel is open, and otherwise
 * `hover:text-foreground hover:bg-surface-elevated`; [leading] is drawn in the chip's colour.
 */
@Composable
private fun ToolbarChip(title: String?, active: Boolean, onClick: () -> Unit, leading: @Composable (Color) -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(4.dp)
    val hover = rememberHover(enabled = !active)
    val color = if (active) tokens.accent else tokens.text.mix(tokens.text2, hover.lit.toDouble())
    Row(
        Modifier
            .clip(shape)
            .background(if (active) tokens.accent.opacity(0.1).color else Color.Transparent)
            .ppmClickable(
                pressedColor = tokens.panel2.color,
                hoverColor = if (active) Color.Transparent else tokens.panel2.color,
                shape = shape,
                source = hover.source,
                onClick = onClick,
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        leading(color.color)
        if (title != null) BasicText(title, style = ppmText(11f, lineHeight = 16.5f).copy(color = color.color))
    }
}

/**
 * AI settings before a chat has a provider of its own (`p-1 rounded`, a 12 px gear): accent-tinted
 * while open, else `text-text-subtle hover:text-text-secondary hover:bg-surface-elevated`.
 */
@Composable
private fun SettingsGearButton(active: Boolean, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(4.dp)
    val hover = rememberHover(enabled = !active)
    val color = if (active) tokens.accent else tokens.text2.mix(tokens.text3, hover.lit.toDouble())
    Box(
        Modifier
            .clip(shape)
            .background(if (active) tokens.accent.opacity(0.1).color else Color.Transparent)
            .ppmClickable(
                pressedColor = tokens.panel2.color,
                hoverColor = if (active) Color.Transparent else tokens.panel2.color,
                shape = shape,
                source = hover.source,
                onClick = onClick,
            )
            .padding(4.dp),
    ) {
        PpmIcon(ProductIcon.Settings2, 12.dp, color.color)
    }
}

/**
 * Reads the messages again; its dot is the connection — green, or a pulsing red while the
 * socket is down.
 */
@Composable
private fun ReloadButton(connected: Boolean, reload: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    var spinning by remember { mutableStateOf(false) }
    val pulse = rememberInfiniteTransition(label = "pulse")
    // `animate-pulse`: down to half and back every 2 s.
    val dim by pulse.animateFloat(
        1f, 0.5f,
        infiniteRepeatable(tween(1_000, easing = CubicBezierEasing(0.4f, 0f, 0.6f, 1f)), RepeatMode.Reverse),
        label = "dot",
    )
    Box(
        Modifier.size(16.dp).ppmClickable(pressedColor = Color.Transparent) {
            reload()
            spinning = true
            scope.launch {
                delay(600)
                spinning = false
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(ProductIcon.RefreshCw, 12.dp, if (connected) tokens.text2.opacity(0.6).color else tokens.error.color, spinning = spinning)
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 2.dp, y = (-2).dp)
                .size(6.dp)
                .alpha(if (connected) 1f else dim)
                .clip(CircleShape)
                .background((if (connected) tokens.success else tokens.error).color),
        )
    }
}

/**
 * Session actions that do not earn a place of their own (`chat-toolbar-overflow-menu.tsx`),
 * `hover:text-text-secondary hover:bg-surface-elevated`.
 */
@Composable
private fun OverflowButton(model: AppModel, session: SessionInfo) {
    val tokens = LocalPpmTokens.current
    val unread = model.unreadSessions()[session.id] != null
    var open by remember { mutableStateOf(false) }
    val hover = rememberHover()
    Box(
        Modifier
            .size(20.dp)
            .clip(RoundedCornerShape(4.dp))
            .ppmClickable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, source = hover.source) { open = true },
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(ProductIcon.MoreHorizontal, 14.dp, tokens.text2.mix(tokens.text3, hover.lit.toDouble()).color)
        if (open) {
            DropdownAboveEnd(onDismiss = { open = false }, minWidth = 190.dp) {
                if (unread) {
                    MenuItem("Mark as read", onClick = {
                        open = false
                        model.markRead(session.id)
                    }, icon = { MenuIcon(ProductIcon.BellOff, tokens.warning) }, vertical = 12.dp)
                } else {
                    MenuItem("Mark as unread", onClick = {
                        open = false
                        model.markUnread(model.sessions.firstOrNull { it.id == session.id } ?: session)
                    }, icon = { MenuIcon(ProductIcon.Circle, tokens.accent) }, vertical = 12.dp)
                }
            }
        }
    }
}

/**
 * The chat's own History panel (`border-t border-border/30 bg-surface`): a title search, the tag
 * chips, then the sessions in the dense list the web shows here; picking one shows it in this
 * tab. Tag management is left out until this app has it.
 */
@Composable
private fun ChatHistoryDropdown(model: AppModel, tab: ChatTab, list: HistoryList, close: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val state by list.state.collectAsState()
    val scope = rememberCoroutineScope()
    val line = tokens.border.opacity(0.3).color
    val query = remember(list) { TextFieldState(list.query) }
    LaunchedEffect(query, list) { snapshotFlow { query.text.toString() }.collect { list.query = it } }
    var refreshing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(tokens.panel.color).line(top = true, line)) {
        // `flex items-center gap-1.5 px-2 py-1 border-b border-border/30`.
        Row(
            Modifier.fillMaxWidth().line(top = false, line).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PpmIcon(ProductIcon.Search, 12.dp, tokens.text3.color)
            // A phone's inputs are 16 px whatever the class says.
            PpmTextField(query, "Search sessions...", Modifier.weight(1f), desktopStyle = ppmText(11f, lineHeight = 16.5f))
            SmallIconButton(ProductIcon.CalendarX2, tokens.error, enabled = !model.deletingOld) { model.askDeleteOld = true }
            SmallIconButton(ProductIcon.RefreshCw, tokens.text2, spinning = refreshing || state.loading || model.deletingOld, enabled = !refreshing) {
                scope.launch {
                    refreshing = true
                    model.refresh(list)
                    refreshing = false
                }
            }
        }
        val all = state.sessions(model.sessions)
        if (model.tags.tags.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().line(top = false, line)) {
                // These chips, unlike the session list's, have no `hover:`.
                TagChipBar(model.tags, all.size, state.tag, vertical = 4.dp, hover = false) { list.tag = it }
            }
        }
        val sessions = state.shown(all)
        val unread = model.unreadSessions()
        Column(Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
            when {
                (state.loading || (state.searchPage == null && model.sessionsLoad == AppModel.Load.Loading)) && all.isEmpty() ->
                    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        PpmIcon(ProductIcon.Loader2, 14.dp, tokens.text3.color, spinning = true)
                    }
                sessions.isEmpty() -> BasicText(
                    if (query.text.isEmpty()) "No sessions yet" else "No matching sessions",
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    style = ppmText(11f, lineHeight = 16.5f).copy(color = tokens.text3.color, textAlign = TextAlign.Center),
                )
                else -> {
                    for (session in sessions) {
                        HistoryBarRow(model, session, unread[session.id]?.type, unread.containsKey(session.id)) {
                            model.replaceTab(tab.tabId, session)
                            close()
                        }
                    }
                    if (state.hasMore(model.hasMoreSessions)) {
                        // `hover:text-text-secondary hover:bg-surface-elevated`.
                        // Lit while loading too: CSS `:hover` matches a disabled button.
                        val hover = rememberHover()
                        val text = tokens.text2.mix(tokens.text3, hover.lit.toDouble()).color
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .ppmClickable(
                                    enabled = !state.loadingMore,
                                    pressedColor = tokens.panel2.color,
                                    hoverColor = tokens.panel2.color,
                                    source = hover.source,
                                    hoverWhileDisabled = true,
                                ) {
                                    scope.launch { list.loadMore(model.sessions, model.hasMoreSessions) }
                                }
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (state.loadingMore) PpmIcon(ProductIcon.Loader2, 12.dp, text, spinning = true)
                            BasicText(if (state.loadingMore) "Loading..." else "Load more", style = ppmText(11f, lineHeight = 16.5f).copy(color = text))
                        }
                    }
                }
            }
        }
    }
}

/** `p-0.5 rounded` around a 12 px icon in the subtle colour, [hoverTint] under a mouse. */
@Composable
private fun SmallIconButton(icon: ProductIcon, hoverTint: Rgba, spinning: Boolean = false, enabled: Boolean = true, size: Dp = 12.dp, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    // Lit while disabled too: CSS `:hover` matches a disabled button.
    val hover = rememberHover()
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.5f)
            .clip(RoundedCornerShape(4.dp))
            .ppmClickable(enabled = enabled, pressedColor = tokens.panel2.color, source = hover.source, hoverWhileDisabled = true, onClick = onClick)
            .padding(2.dp),
    ) {
        PpmIcon(icon, size, hoverTint.mix(tokens.text3, hover.lit.toDouble()).color, spinning = spinning)
    }
}

/**
 * `px-3 py-1.5 gap-2`, 11 px: provider, tag dot, title, then pin, rename and delete — always
 * shown on a touch screen, where nothing hovers — and the age. Unread: tinted by what happened
 * last, in the text colour at medium weight. A long press opens the chat's menu.
 */
@Composable
private fun HistoryBarRow(model: AppModel, session: SessionInfo, unreadType: String?, isUnread: Boolean, open: () -> Unit) {
    val tokens = LocalPpmTokens.current
    var renaming by remember { mutableStateOf(false) }
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    val color = if (isUnread) tokens.text else tokens.text2
    val bot = session.title.startsWith("[PPM]")
    val title = if (bot) session.title.drop(7) else session.title
    // `hover:bg-surface-elevated`, on the row itself, so it lights while renaming too.
    val hover = rememberHover()
    Box(
        Modifier
            .fillMaxWidth()
            .background(if (isUnread) tokens.notification(unreadType).opacity(0.1).color else Color.Transparent)
            .background(tokens.panel2.opacity(hover.lit.toDouble()).color)
            .hoverable(hover.source),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .pressable(pressedColor = tokens.panel2.color, enabled = !renaming, onLongClick = { menuAt = it }, onClick = open)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProviderBadge(session.providerId)
            session.tag?.let { TagDot(it.color) }
            if (renaming) {
                InlineRename(model, session, RenameField.HistoryBar, Modifier.weight(1f)) { renaming = false }
            } else {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (bot) PpmIcon(ProductIcon.Bot, 12.dp, tokens.text2.color)
                    BasicText(
                        PpmFormat.collapsedWhitespace(title).ifEmpty { "Untitled" },
                        style = ppmText(11f, if (isUnread) FontWeight.Medium else FontWeight.Normal, lineHeight = 16.5f).copy(color = color.color),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                RowIcon(
                    if (session.pinned) ProductIcon.PinOff else ProductIcon.Pin,
                    if (session.pinned) tokens.accent else tokens.text3,
                    if (session.pinned) tokens.accent.opacity(0.7) else tokens.text2,
                    fade = true,
                ) {
                    model.scope.launch { model.togglePin(session) }
                }
                RowIcon(ProductIcon.Pencil, tokens.text3, tokens.text2) { renaming = true }
                RowIcon(ProductIcon.Trash2, tokens.text3, tokens.error, hoverFill = tokens.error.opacity(0.2)) { model.confirmDeleteSession = session }
                session.updatedAt?.let {
                    Box(Modifier.width(64.dp), contentAlignment = Alignment.CenterEnd) {
                        RelativeTime(it, ppmText(10f, lineHeight = 15f).copy(color = tokens.text3.color))
                    }
                }
            }
        }
        menuAt?.let { at ->
            SessionContextMenu(model, session, at, onDismiss = { menuAt = null }, onRename = { renaming = true }, onDelete = { model.confirmDeleteSession = session })
        }
    }
}

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
            textStyle = ppmText(size, lineHeight = size * field.lineRatio).copy(color = tokens.text.color),
            cursorBrush = SolidColor(tokens.accent.color),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            onKeyboardAction = { finish(save = true) },
            lineLimits = TextFieldLineLimits.SingleLine,
        )
        RowIcon(ProductIcon.Check, tokens.success, tokens.success.opacity(0.8)) { finish(save = true) }
        RowIcon(ProductIcon.X, tokens.text3, tokens.text2) { finish(save = false) }
    }
}
