package com.hienlh.ppm.app.ui.phone

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ChatTab
import com.hienlh.ppm.app.NewChatGate
import com.hienlh.ppm.app.attach
import com.hienlh.ppm.app.removeAttachment
import com.hienlh.ppm.app.ui.ConnectionChip
import com.hienlh.ppm.app.ui.PromptDialog
import com.hienlh.ppm.app.ui.SessionDebugDialog
import com.hienlh.ppm.app.ui.SessionHistoryPanel
import com.hienlh.ppm.app.ui.SessionListPanel
import com.hienlh.ppm.app.ui.rememberReconnectIndicator
import com.hienlh.ppm.app.ui.rememberSwitchAccounts
import com.hienlh.ppm.app.ui.phone.chat.PhoneAccountSheet
import com.hienlh.ppm.app.ui.phone.chat.PhoneAttachSheet
import com.hienlh.ppm.app.ui.phone.chat.PhoneBackgroundCommand
import com.hienlh.ppm.app.ui.phone.chat.PhoneChatScreen
import com.hienlh.ppm.app.ui.phone.chat.PhoneChatTabEffects
import com.hienlh.ppm.app.ui.phone.chat.PhoneMessagePriority
import com.hienlh.ppm.app.ui.phone.chat.PhoneModelEffortSheet
import com.hienlh.ppm.app.ui.phone.chat.PhoneModelOption
import com.hienlh.ppm.app.ui.phone.chat.PhoneModesSheet
import com.hienlh.ppm.app.ui.phone.chat.PhoneProviderGateBar
import com.hienlh.ppm.app.ui.phone.chat.PhoneProviderOption
import com.hienlh.ppm.app.ui.phone.chat.PhoneReplyEditBanner
import com.hienlh.ppm.app.ui.phone.chat.PhoneSessionChangesSheet
import com.hienlh.ppm.app.ui.phone.chat.PhoneSessionInfoData
import com.hienlh.ppm.app.ui.phone.chat.PhoneSessionInfoSheet
import com.hienlh.ppm.app.ui.phone.chat.PhoneSessionReview
import com.hienlh.ppm.app.ui.phone.chat.PhoneSessionReviewOverlay
import com.hienlh.ppm.app.ui.phone.chat.PhoneTranscriptLocals
import com.hienlh.ppm.app.ui.phone.chat.openPhoneSessionReview
import com.hienlh.ppm.app.ui.phone.chat.phoneAccountUsageText
import com.hienlh.ppm.app.ui.phone.chat.phoneChangedFiles
import com.hienlh.ppm.app.ui.phone.chat.phoneChangesChip
import com.hienlh.ppm.app.ui.phone.chat.phoneCostLabel
import com.hienlh.ppm.app.ui.phone.chat.phoneDraftAttachments
import com.hienlh.ppm.app.ui.phone.chat.phoneElapsedLabel
import com.hienlh.ppm.app.ui.phone.chat.rememberPhoneAttachPickers
import com.hienlh.ppm.app.ui.phone.chat.rememberPhoneChatUsage
import com.hienlh.ppm.app.ui.phone.chat.rememberTurnElapsed
import com.hienlh.ppm.app.ui.shell.OnboardingEntryRow
import com.hienlh.ppm.chat.CONTINUE_AFTER_STOP
import com.hienlh.ppm.chat.ChatEdits
import com.hienlh.ppm.chat.ChatTranscriptView
import com.hienlh.ppm.chat.FirstSendView
import com.hienlh.ppm.chat.McpSignInBar
import com.hienlh.ppm.chat.TurnStopBar
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.kit.Sheet
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.ProviderInfo
import com.hienlh.ppm.protocol.ReplyReference
import com.hienlh.ppm.protocol.TurnOptions
import com.hienlh.ppm.state.ChatController
import com.hienlh.ppm.state.Effort
import com.hienlh.ppm.state.MessagePriority
import com.hienlh.ppm.state.ModelNames
import com.hienlh.ppm.state.PermissionMode
import com.hienlh.ppm.state.resolveFooterUsage
import com.hienlh.ppm.state.workspace.WorkspaceTab
import java.time.Instant
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
internal fun PhoneChatAreaHost(
    model: AppModel,
    tab: WorkspaceTab?,
    nav: PhoneNav,
    showHistoryInitially: Boolean = false,
) {
    val openChatTab = remember(tab, model.openTabs) {
        tab?.takeIf { it.type == "chat" }
            ?: model.openTabs.map { it.first }.lastOrNull { it.type == "chat" }
    }
    // Read on every composition: the tab's chat is made after the tab appears (`syncChats`).
    val chatTab: ChatTab? = openChatTab?.let { model.chat(it.id) }
    PhoneChatContent(model, chatTab, openChatTab?.title, nav::visitTab, showHistoryInitially)
}

/**
 * The phone's chat for [chatTab] (none: the new-chat welcome, whose send opens one), wired as
 * ChatPane wires the tablet's. [visit] shows a workspace tab the chat opened (Logs, a new chat).
 */
@Composable
internal fun PhoneChatContent(
    model: AppModel,
    chatTab: ChatTab?,
    tabTitle: String?,
    visit: (String) -> Unit,
    showHistoryInitially: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val chatController = chatTab?.controller
    val chatState = chatController?.state?.collectAsState()?.value

    // What ChatPane does for its tab besides drawing it: title, opening message, prewarm, refused sends, written files.
    if (chatTab != null && chatController != null && chatState != null) {
        key(chatTab) { PhoneChatTabEffects(model, chatTab, chatController, chatState) }
    }
    val usage = if (chatTab != null && chatState != null) key(chatTab) { rememberPhoneChatUsage(model, chatTab, chatState) } else null
    val usageInfo = usage?.shown?.usage

    val fallbackDraft = remember { TextFieldState("") }
    val composerState = chatTab?.draft ?: fallbackDraft

    var showModelSheet by remember { mutableStateOf(false) }
    var showModesSheet by remember { mutableStateOf(false) }
    var showChangesSheet by remember { mutableStateOf(false) }
    var showSessionInfoSheet by remember { mutableStateOf(false) }
    var showAttachSheet by remember { mutableStateOf(false) }
    var showAccountSheet by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var debugOpen by remember { mutableStateOf(false) }
    var reviewing by remember { mutableStateOf<PhoneSessionReview?>(null) }
    var showHistorySheet by remember(showHistoryInitially) { mutableStateOf(showHistoryInitially) }
    val providerId = chatTab?.providerId ?: "claude"
    // What the chat runs on, as the tablet composer's chip shows it: the session's model and effort
    // from `session_state`, or, before a session exists, the provider's defaults (ChatController).
    val catalog = model.models
    val catalogEntries = catalog?.entries?.collectAsState()?.value
    LaunchedEffect(catalog, providerId) { catalog?.load(providerId) }
    val stateModel = chatState?.model
    val modelLabel = remember(catalogEntries, stateModel, providerId) {
        catalog?.displayName(stateModel, providerId)
            ?: ModelNames.effectiveModel(stateModel, providerId)?.let(ModelNames::displayName)
            ?: "Model"
    }
    val effortValue = chatState?.effort ?: ModelNames.suffixEffort(stateModel, providerId) ?: Effort.DEFAULT.value
    val effortLabel = Effort.label(effortValue)
    val thinkingEnabled = chatState?.thinking ?: true
    val permissionMode = chatTab?.permissionMode ?: PermissionMode.FALLBACK
    val permissionModeId = permissionMode.value

    // The account the chat runs on and its usage windows, as the footer's rings and ··· menu read them.
    val hasAccounts = providerId == "claude" || providerId == "codex"
    val switcher = rememberSwitchAccounts(model, providerId, enabled = showAccountSheet)
    val pickedAccountId = chatTab?.servingAccount?.id ?: chatTab?.claimedAccountId
    val pickedAccountLabel = chatTab?.servingAccount?.label ?: chatTab?.claimedAccountLabel
    val usageSummary = remember(providerId, usageInfo, pickedAccountId, pickedAccountLabel, switcher.accounts) {
        resolveFooterUsage(providerId, usageInfo, pickedAccountId, pickedAccountLabel, switcher.accounts)
    }
    val accountName = usageSummary.accountLabel ?: "Default account"

    val isStreaming = chatState?.isRunning == true
    val startingConversation = chatTab?.firstSendPending == true
    val isNewChat = (chatTab == null || (chatTab.session == null && chatState?.items.isNullOrEmpty())) && chatTab?.firstSendPreview == null
    val currentSession = chatTab?.session?.let { s -> model.sessions.firstOrNull { it.id == s.id } ?: s }

    val unreadMap = model.events?.unread?.collectAsState()?.value.orEmpty()

    // The files this session changed (`SessionChangesChip`), not the project's git status.
    val sessionChanges = chatTab?.sessionChanges
    val changeFiles = sessionChanges?.files?.collectAsState()?.value.orEmpty()
    val changesLoaded = sessionChanges?.loaded?.collectAsState()?.value == true
    val changesUnloaded = sessionChanges?.unloadedCount?.collectAsState()?.value ?: 0
    val projectPath = model.projects.firstOrNull { it.name == chatTab?.project }?.path
    val changesChip = if (sessionChanges != null && model.info?.minimalMode != true) phoneChangesChip(changeFiles, changesLoaded, changesUnloaded) else null

    // Voice input, for the engine Settings → Voice picked; asked for the microphone the first time.
    val engine = model.voiceEngine
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> model.voice.permitted(granted) }
    val toggleMic: (() -> Unit)? = if (chatTab != null && model.voice.supported(engine)) {
        { model.voice.toggle(chatTab, engine) { askMic.launch(Manifest.permission.RECORD_AUDIO) } }
    } else {
        null
    }
    val listening = chatTab?.voiceListening == true
    var listeningSince by remember { mutableStateOf<Instant?>(null) }
    var voiceNow by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(listening) {
        listeningSince = if (listening) Instant.now() else null
        while (listening) {
            voiceNow = Instant.now()
            kotlinx.coroutines.delay(1_000)
        }
    }

    val pickers = rememberPhoneAttachPickers { uris -> chatTab?.let { model.attach(context, it, uris) } }

    // The connection chip (`useReconnectIndicator`): restarted on foreground resume, Retry reloads the chat.
    val reconnect = rememberReconnectIndicator(
        isReconnecting = chatState?.connection is ChatController.Connection.Reconnecting,
        windowVisible = model.windowVisible,
    )
    LaunchedEffect(model.resumes) {
        if (model.resumes > 0) reconnect.restart()
    }

    val replyEditBanner = chatTab?.let { t ->
        val reply = t.replyTo
        when {
            t.editFork != null -> PhoneReplyEditBanner(isEditing = true, snippet = "Your next send replaces it")
            reply != null -> PhoneReplyEditBanner(
                isEditing = false,
                title = if (reply.role == ReplyReference.Role.USER) "Replying to your message" else "Replying to AI",
                snippet = reply.quote.replace('\n', ' '),
            )
            else -> null
        }
    }
    val visitActive = { model.activeTabId?.let(visit) }

    Box(modifier = Modifier.fillMaxSize()) {
        PhoneChatScreen(
            composerState = composerState,
            onSendMessage = { text, priority ->
                if (chatTab != null) {
                    chatTab.priority = when (priority) {
                        PhoneMessagePriority.NOW -> MessagePriority.NOW
                        PhoneMessagePriority.NEXT -> MessagePriority.NEXT
                        PhoneMessagePriority.LATER -> MessagePriority.LATER
                    }
                    if (chatTab.draft.text.toString() != text) {
                        chatTab.draft.setTextAndPlaceCursorAtEnd(text)
                    }
                    chatTab.submit()
                } else {
                    model.newChat(pendingMessage = text)
                    visitActive()
                }
            },
            onStopTurn = { chatController?.cancel() },
            // The transcript's own pill scrolls to the waiting card.
            onScrollToWaitingCard = null,
            modifier = Modifier.testTag("phone-chat-screen"),
            isNewChat = isNewChat,
            startingConversation = startingConversation,
            projectName = model.selectedProject ?: model.serverName,
            recentSessions = model.sessions,
            onOpenRecentSession = { session ->
                // Picking a chat from a new chat's welcome shows it in this tab, as the web's does.
                if (chatTab != null && chatTab.session == null) model.replaceTab(chatTab.tabId, session) else model.openTab(session)
                visitActive()
            },
            onQuickOrientation = if (model.info?.minimalMode == true) null else ({ model.learnPpmMode = true }),
            pendingApproval = chatState?.pendingApproval,
            streaming = isStreaming,
            elapsedTurnLabel = rememberTurnElapsed(isStreaming, chatState?.liveStartedAt),
            providerId = providerId,
            modelLabel = modelLabel,
            effortLabel = effortLabel,
            permissionModeId = permissionModeId,
            permissionModeLabel = permissionMode.shortLabel,
            accountName = accountName,
            accountUsageText = phoneAccountUsageText(usageSummary),
            // Spec mode has no client in this app: the chip stays hidden.
            onToggleSpecMode = null,
            changes = changesChip,
            backgroundCommands = chatState?.backgroundShells.orEmpty().filter { !it.isStopped }
                .map { PhoneBackgroundCommand(it.shellId, it.command, stopping = it.isStopping) },
            onViewBackgroundOutput = { id -> chatTab?.let { model.openBackgroundOutput(it.tabId, id) } },
            onStopBackgroundCommand = { id -> chatController?.killBackgroundShell(id) },
            replyEditBanner = replyEditBanner,
            onCancelReplyEdit = {
                if (chatTab?.editFork != null) chatTab.cancelEdit() else chatTab?.selectReply(null)
            },
            attachments = phoneDraftAttachments(chatTab?.attachments.orEmpty()),
            onRemoveAttachment = { id -> chatTab?.let { model.removeAttachment(it, id) } },
            onOpenAttachSheet = if (chatTab != null) ({ showAttachSheet = true }) else null,
            voiceRecording = listening,
            voiceElapsedLabel = listeningSince?.let { phoneElapsedLabel(it, voiceNow) } ?: "0:00",
            onToggleVoiceRecording = toggleMic,
            onOpenModelEffortSheet = { showModelSheet = true },
            onOpenModesSheet = { showModesSheet = true },
            onOpenAccountSwitcher = if (chatTab != null && hasAccounts) ({ showAccountSheet = true }) else null,
            onOpenSessionChangesSheet = {
                sessionChanges?.refresh()
                showChangesSheet = true
            },
            onOpenSessionInfoSheet = { showSessionInfoSheet = true },
            sendEnabled = !startingConversation,
            connectionChip = {
                if (chatController != null) {
                    ConnectionChip(
                        stage = reconnect.stage,
                        narrow = true,
                        onRetry = {
                            reconnect.restart()
                            chatController.reloadChat()
                        },
                    )
                }
            },
            bannersContent = {
                if (chatTab != null && chatController != null && chatState != null) {
                    PhoneChatBanners(model, chatTab, chatController, chatState.turnStop.takeIf { !chatState.isRunning }, chatState.mcpNeedsAuth)
                }
            },
            transcriptContent = {
                if (chatTab != null && chatController != null && chatState != null) {
                    PhoneTranscriptLocals(model, chatTab, chatController, chatState) {
                        val preview = chatTab.firstSendPreview
                        if (preview != null) {
                            FirstSendView(preview.content, preview.timestamp, Modifier.fillMaxSize())
                        } else {
                            // Fork and Edit are left out while a turn runs, and the switchers greyed out.
                            val running = chatState.isRunning
                            val editingId = chatTab.editFork?.messageId
                            val edits = remember(chatTab, running, editingId) {
                                ChatEdits(
                                    onFork = if (running) null else { item, anchor -> model.edits.fork(chatTab, item, anchor) },
                                    onEdit = if (running) null else chatTab::armEdit,
                                    editingId = editingId,
                                    onSwitchVersion = { model.edits.switchVersion(chatTab, it) },
                                    versionsDisabled = running,
                                )
                            }
                            ChatTranscriptView(
                                state = chatState,
                                onRespond = { req, ok, data -> chatController.respond(req, ok, data) },
                                onLoadEarlier = chatController::loadEarlier,
                                onReload = { chatController.reload() },
                                modifier = Modifier.fillMaxSize(),
                                jumps = remember(chatTab.tabId) { model.chatJumps.filter { it.first == chatTab.tabId }.map { it.second } },
                                welcome = {
                                    chatTab.welcomeList?.let { list ->
                                        SessionListPanel(model, list, onOpen = { model.replaceTab(chatTab.tabId, it) }, Modifier.padding(horizontal = 16.dp))
                                    }
                                },
                                welcomeActions = if (model.info?.minimalMode == true) null else ({ OnboardingEntryRow(model, compact = true) }),
                                onDismiss = chatController::dismissMessage,
                                onClearErrors = chatController::clearErrors,
                                jumpRequest = model.chatJump?.takeIf { it.sessionId == chatState.sessionId },
                                onJumpTaken = model::chatJumpDone,
                                // Not while the draft, which may carry a reply of its own, is on its way, nor while an edit's version is made.
                                onReply = if (chatTab.draftLoading || chatTab.editForking) null else chatTab::selectReply,
                                edits = edits,
                                bottomFade = true,
                            )
                        }
                    }
                }
            },
        )

        if (chatTab?.editForking == true) PhoneEditForkingOverlay()

        if (showModelSheet) {
            val project = chatTab?.project ?: model.selectedProject.orEmpty()
            val providerInfos = model.newChats.preparationCache.peekProviders(project)
                ?: model.newChatCache.providers(project)
                ?: emptyList()
            val providers = remember(providerInfos, providerId) {
                (providerInfos.takeIf { list -> list.any { it.id == providerId } } ?: providerInfos + ProviderInfo(providerId, providerId.replaceFirstChar { it.uppercase() }))
                    .map { info ->
                        PhoneProviderOption(
                            id = info.id,
                            name = info.name,
                            subtitle = if (info.id == providerId) "Active provider" else "",
                            effortLevels = Effort.optionsFor(info.id).map { it.label },
                        )
                    }
            }
            val defaults = model.newChatCache.settings()?.defaults?.get(providerId)
            val defaultEffortLabel = Effort.label(defaults?.effort ?: Effort.DEFAULT.value)
            val availableModels = remember(catalogEntries, providerId, defaults) {
                catalog?.models(providerId).orEmpty().map { option ->
                    PhoneModelOption(
                        id = option.value,
                        name = ModelNames.shortLabel(option.label, providerId),
                        description = if (option.value == defaults?.model) "Default" else "",
                        defaultEffort = defaultEffortLabel,
                    )
                }
            }
            PhoneModelEffortSheet(
                providers = providers,
                selectedProviderId = providerId,
                models = availableModels,
                selectedModelId = ModelNames.effectiveModel(stateModel, providerId).orEmpty(),
                selectedEffort = effortLabel,
                thinkingEnabled = thinkingEnabled,
                providerLocked = chatTab?.session != null || chatTab == null,
                onSelectProvider = { id -> chatTab?.let { model.newChats.pickProvider(it, id) } },
                onSelectModel = { id -> chatController?.setModel(id) },
                onSelectEffort = { label ->
                    Effort.entries.firstOrNull { it.label.equals(label, ignoreCase = true) }?.let { chatController?.setEffort(it) }
                },
                onToggleThinking = { enabled -> chatController?.setThinking(enabled) },
                onResetEffortToDefault = {
                    (Effort.of(defaults?.effort) ?: Effort.DEFAULT).let { chatController?.setEffort(it) }
                },
                onDismiss = { showModelSheet = false },
            )
        }

        if (showModesSheet) {
            PhoneModesSheet(
                selectedModeId = permissionModeId,
                onSelectMode = { modeId ->
                    val picked = PermissionMode.entries.firstOrNull { it.value == modeId }
                    if (picked != null) {
                        chatTab?.pickMode(picked)
                    }
                    showModesSheet = false
                },
                onDismiss = { showModesSheet = false },
            )
        }

        if (showChangesSheet && chatTab != null && sessionChanges != null) {
            PhoneSessionChangesSheet(
                files = phoneChangedFiles(changeFiles, projectPath),
                onToggleReviewed = { path ->
                    changeFiles.firstOrNull { it.path == path }?.let { file -> sessionChanges.setReviewed(listOf(file), !file.reviewed) }
                },
                onOpenFileDiff = { path ->
                    showChangesSheet = false
                    reviewing = openPhoneSessionReview(model, chatTab, chatState?.title ?: currentSession?.title, sessionChanges.paths, path)
                },
                onDismiss = { showChangesSheet = false },
            )
        }

        if (showSessionInfoSheet) {
            val session = currentSession
            val hasUnread = session != null && unreadMap.containsKey(session.id)
            PhoneSessionInfoSheet(
                info = PhoneSessionInfoData(
                    sessionId = session?.id ?: chatTab?.tabId ?: "new",
                    title = session?.title?.ifBlank { "Untitled session" }
                        ?: tabTitle?.ifBlank { "New Chat" }
                        ?: "New Chat",
                    modelLabel = modelLabel,
                    modeLabel = permissionMode.label,
                    accountLabel = accountName,
                    costLabel = phoneCostLabel(usageInfo?.totalCostUsd),
                    projectName = model.selectedProject ?: model.serverName,
                    createdByLabel = session?.parentTitle?.takeIf { session.spawnedBy != null && it.isNotBlank() },
                ),
                hasUnread = hasUnread,
                onRename = session?.let { { renaming = true } },
                onOpenModelSheet = {
                    showSessionInfoSheet = false
                    showModelSheet = true
                },
                onOpenModeSheet = {
                    showSessionInfoSheet = false
                    showModesSheet = true
                },
                // Unread lives in the server's event stream; with none open there is nothing to mark.
                onMarkAsUnread = session?.takeIf { model.events != null }?.let { s -> { if (hasUnread) model.markRead(s.id) else model.markUnread(s) } },
                onShowLogs = session?.let { s ->
                    {
                        model.openLogs(chat = s.id)
                        visitActive()
                    }
                },
                onOpenSessionDebug = session?.let { { debugOpen = true } },
                onDismiss = { showSessionInfoSheet = false },
            )
        }

        if (renaming && currentSession != null) {
            PromptDialog(
                message = "Rename session",
                initial = currentSession.title,
                onConfirm = { title ->
                    renaming = false
                    scope.launch { model.rename(currentSession, title) }
                },
                onDismiss = { renaming = false },
            )
        }

        if (debugOpen && chatTab != null && currentSession != null) {
            val itemCount by rememberUpdatedState((chatState?.items?.size ?: 0) + if (chatState?.live != null) 1 else 0)
            SessionDebugDialog(
                model = model,
                project = chatTab.project,
                sessionId = currentSession.id,
                messageCount = { itemCount },
                onDismiss = { debugOpen = false },
            )
        }

        if (showAttachSheet) {
            PhoneAttachSheet(
                onPickPhotos = pickers.photos,
                onOpenCamera = pickers.camera,
                onPickFiles = pickers.files,
                onDismiss = { showAttachSheet = false },
            )
        }

        if (showAccountSheet && chatTab != null && usage != null) {
            PhoneAccountSheet(
                model = model,
                tab = chatTab,
                usage = usage,
                summary = usageSummary,
                switcher = switcher,
                onDismiss = { showAccountSheet = false },
            )
        }

        if (showHistorySheet) {
            Sheet(
                title = "Chat History",
                onDismiss = { showHistorySheet = false },
                forceBottomSheet = true,
                modifier = Modifier.testTag("phone-chat-history-sheet"),
            ) {
                val list = model.sidebarList
                val search = model.sidebarSearch
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .testTag("phone-chat-history-list"),
                ) {
                    if (list != null && search != null) {
                        // The sidebar's history: search, tags, pin, rename, mark unread, delete with its confirm, Load more.
                        SessionHistoryPanel(
                            model,
                            list,
                            search,
                            onNavigate = {
                                showHistorySheet = false
                                visitActive()
                            },
                            Modifier.fillMaxSize(),
                        )
                    } else {
                        val tokens = LocalPpmTokens.current
                        BasicText(
                            "Choose a project to see its chats.",
                            Modifier.padding(16.dp),
                            style = ppmText(13f, lineHeight = 18f).copy(color = tokens.text3.color),
                        )
                    }
                }
            }
        }

        reviewing?.let { opened ->
            PhoneSessionReviewOverlay(
                model = model,
                opened = opened,
                onClose = { reviewing = null },
            )
        }
    }
}

/**
 * Between the transcript and the composer, as ChatPane stacks them: the new chat's provider gate
 * (`new-chat-provider-gate.tsx`), the stopped turn's Continue, and the MCP sign-in bar.
 */
@Composable
private fun PhoneChatBanners(
    model: AppModel,
    tab: ChatTab,
    chat: ChatController,
    stop: com.hienlh.ppm.protocol.TurnStop?,
    mcpNeedsAuth: List<String>,
) {
    if (tab.providerPending) {
        when (val gate = tab.gate) {
            is NewChatGate.Unavailable -> {
                val alternative = gate.alternatives.firstOrNull()
                PhoneProviderGateBar(
                    providerName = gate.provider,
                    message = "${gate.provider} is not available. Choose a provider for this chat.",
                    actionLabel = alternative?.let { "Use ${it.name}" } ?: "Check again",
                    onAction = {
                        if (alternative != null) model.newChats.select(tab, alternative.id, gate.settings) else model.newChats.retry(tab)
                    },
                )
            }
            is NewChatGate.Failed -> PhoneProviderGateBar(
                providerName = "Chat settings",
                message = "Could not load chat settings. ${gate.message}",
                actionLabel = "Retry",
                onAction = { model.newChats.retry(tab) },
            )
            null -> {
                val tokens = LocalPpmTokens.current
                BasicText(
                    "Preparing chat…",
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
                )
            }
        }
    }
    if (stop != null) {
        TurnStopBar(stop, onContinue = { chat.send(CONTINUE_AFTER_STOP, TurnOptions(permissionMode = tab.permissionMode?.value)) })
    }
    if (mcpNeedsAuth.any { it !in model.mcpSignInDismissed }) {
        McpSignInBar(
            mcpNeedsAuth,
            model.mcpSignInDismissed,
            onSignIn = { model.openMcpSignIn(tab.project.ifEmpty { null }, it) },
            onHide = model::dismissMcpSignIn,
        )
    }
}

/** Over the chat while an edit's version is made (`editForking`): every touch stopped there. */
@Composable
private fun PhoneEditForkingOverlay() {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier
            .fillMaxSize()
            .background(tokens.bgSolid.opacity(0.7).color)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PpmIcon(ProductIcon.Loader2, 16.dp, tokens.text2.color, spinning = true)
        BasicText("Creating edited version…", style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text2.color))
    }
}
