package com.hienlh.ppm.app.ui.phone

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ChatTab
import com.hienlh.ppm.app.ui.HistoryByDay
import com.hienlh.ppm.app.ui.PhoneSessionHistoryList
import com.hienlh.ppm.app.ui.TerminalBody
import com.hienlh.ppm.app.ui.TerminalMobileToolbar
import com.hienlh.ppm.app.ui.database.DatabaseQueryTabBody
import com.hienlh.ppm.app.ui.database.DatabaseSidebarPane
import com.hienlh.ppm.app.ui.database.DatabaseTableTabBody
import com.hienlh.ppm.app.ui.database.DbSqlObjectTabBody
import com.hienlh.ppm.app.ui.database.SqliteViewerTabBody
import com.hienlh.ppm.app.ui.phone.chat.PhoneAttachSheet
import com.hienlh.ppm.app.ui.phone.chat.PhoneChangedFileItem
import com.hienlh.ppm.app.ui.phone.chat.PhoneChatScreen
import com.hienlh.ppm.app.ui.phone.chat.PhoneMessagePriority
import com.hienlh.ppm.app.ui.phone.chat.PhoneModelEffortSheet
import com.hienlh.ppm.app.ui.phone.chat.PhoneModelOption
import com.hienlh.ppm.app.ui.phone.chat.PhoneModesSheet
import com.hienlh.ppm.app.ui.phone.chat.PhoneProviderOption
import com.hienlh.ppm.app.ui.phone.chat.PhoneSessionChangesSheet
import com.hienlh.ppm.app.ui.phone.chat.PhoneSessionInfoData
import com.hienlh.ppm.app.ui.phone.chat.PhoneSessionInfoSheet
import com.hienlh.ppm.app.ui.phone.files.PhoneDiffScreen
import com.hienlh.ppm.app.ui.phone.files.PhoneFileTabScreen
import com.hienlh.ppm.app.ui.phone.files.PhoneFilesTreeScreen
import com.hienlh.ppm.app.ui.phone.git.PhoneSourceControlScreen
import com.hienlh.ppm.app.ui.phone.settings.PhoneSettingsScreen
import com.hienlh.ppm.app.ui.phone.settings.resolvePhoneSettingsRoute
import com.hienlh.ppm.app.ui.phone.terminal.PhoneTerminalActionsSheet
import com.hienlh.ppm.app.ui.phone.terminal.PhoneTerminalStatusBar
import com.hienlh.ppm.app.ui.phone.terminal.resolvePhoneTerminalTab
import com.hienlh.ppm.app.ui.phone.tools.PhoneCloudShareSheet
import com.hienlh.ppm.app.ui.phone.tools.PhoneCloudShareState
import com.hienlh.ppm.app.ui.phone.tools.PhoneLogsScreen
import com.hienlh.ppm.app.ui.phone.tools.PhoneLogsState
import com.hienlh.ppm.app.ui.phone.tools.PhoneMonitorState
import com.hienlh.ppm.app.ui.phone.tools.PhoneMonitorTab
import com.hienlh.ppm.app.ui.phone.tools.PhonePortForwardingScreen
import com.hienlh.ppm.app.ui.phone.tools.PhonePortForwardingState
import com.hienlh.ppm.app.ui.phone.tools.PhoneSchedulesScreen
import com.hienlh.ppm.app.ui.phone.tools.PhoneSchedulesState
import com.hienlh.ppm.app.ui.phone.tools.PhoneSystemMonitorScreen
import com.hienlh.ppm.app.ui.projects.ProjectSwitcherSheet
import com.hienlh.ppm.chat.ChatTranscriptView
import com.hienlh.ppm.client.gitCommitMessage
import com.hienlh.ppm.client.namedTunnelStatus
import com.hienlh.ppm.client.queryLogs
import com.hienlh.ppm.client.schedulesList
import com.hienlh.ppm.client.schedulesRunNow
import com.hienlh.ppm.client.schedulesUpdate
import com.hienlh.ppm.client.tunnelStart
import com.hienlh.ppm.client.tunnelStop
import com.hienlh.ppm.client.tunnelsList
import com.hienlh.ppm.client.tunnelsTransports
import com.hienlh.ppm.design.kit.Sheet
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.MessagePriority
import com.hienlh.ppm.state.Effort
import com.hienlh.ppm.state.ModelNames
import com.hienlh.ppm.protocol.ProviderInfo
import com.hienlh.ppm.state.PermissionMode
import com.hienlh.ppm.state.logs.LogEntry
import com.hienlh.ppm.state.logs.LogFilter
import com.hienlh.ppm.state.logs.LogQueryResult
import com.hienlh.ppm.state.logs.logsQueryPairs
import com.hienlh.ppm.state.logs.rangeStart
import com.hienlh.ppm.state.settings.parseSchedulesList
import com.hienlh.ppm.state.terminal.TerminalState
import com.hienlh.ppm.state.tunnels.NamedTunnelStatus
import com.hienlh.ppm.state.tunnels.TailscaleAvailability
import com.hienlh.ppm.state.tunnels.TunnelEntry
import com.hienlh.ppm.state.tunnels.TunnelsModel
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
internal fun PhoneChatAreaHost(
    model: AppModel,
    tab: WorkspaceTab?,
    nav: PhoneNav,
    showHistoryInitially: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val openChatTab = remember(tab, model.openTabs) {
        tab?.takeIf { it.type == "chat" }
            ?: model.openTabs.map { it.first }.lastOrNull { it.type == "chat" }
    }
    val chatTab: ChatTab? = remember(model, openChatTab?.id) {
        openChatTab?.let { model.chat(it.id) }
    }
    val chatController = chatTab?.controller
    val chatState = chatController?.state?.collectAsState()?.value

    val fallbackDraft = remember { TextFieldState("") }
    val composerState = chatTab?.draft ?: fallbackDraft

    var showModelSheet by remember { mutableStateOf(false) }
    var showModesSheet by remember { mutableStateOf(false) }
    var showChangesSheet by remember { mutableStateOf(false) }
    var showSessionInfoSheet by remember { mutableStateOf(false) }
    var showAttachSheet by remember { mutableStateOf(false) }
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
    val permissionModeId = (chatTab?.permissionMode ?: PermissionMode.FALLBACK).value
    val accountName = chatTab?.servingAccount?.label
        ?: chatTab?.claimedAccountLabel
        ?: "Default"

    val isNewChat = chatTab == null || (chatTab.session == null && chatState?.items.isNullOrEmpty())
    val isStreaming = chatState?.isRunning == true
    val startingConversation = chatTab?.firstSendPending == true

    val unreadMap = model.events?.unread?.collectAsState()?.value.orEmpty()
    val runningSet = model.events?.running?.collectAsState()?.value.orEmpty()
    val dayGroups = remember(model.sessions) {
        HistoryByDay.groupSessionsByDay(model.sessions)
    }

    val changeFiles = remember(model.selectedProject, model.gitChangesCache) {
        val proj = model.selectedProject.orEmpty()
        val gitChanges = model.gitChangesCache[model.gitChangesKey(proj, null)]
            ?: model.gitChangesCache[proj]
        gitChanges?.files.orEmpty().map { file ->
            PhoneChangedFileItem(
                path = file.path,
                additions = (file.staged?.added ?: 0) + (file.unstaged?.added ?: 0),
                deletions = (file.staged?.removed ?: 0) + (file.unstaged?.removed ?: 0),
            )
        }
    }

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
                    model.activeTabId?.let(nav::visitTab)
                }
            },
            onStopTurn = { chatController?.cancel() },
            onScrollToWaitingCard = {},
            modifier = Modifier.testTag("phone-chat-screen"),
            isNewChat = isNewChat,
            startingConversation = startingConversation,
            projectName = model.selectedProject ?: model.serverName,
            recentSessions = model.sessions.take(5),
            onOpenRecentSession = { session ->
                model.openTab(session)
                model.activeTabId?.let(nav::visitTab)
            },
            pendingApproval = chatState?.pendingApproval,
            streaming = isStreaming,
            providerId = providerId,
            modelLabel = modelLabel,
            effortLabel = effortLabel,
            permissionModeId = permissionModeId,
            permissionModeLabel = (PermissionMode.of(permissionModeId) ?: PermissionMode.FALLBACK).shortLabel,
            accountName = accountName,
            changedFiles = changeFiles,
            onOpenAttachSheet = { showAttachSheet = true },
            onOpenModelEffortSheet = { showModelSheet = true },
            onOpenModesSheet = { showModesSheet = true },
            onOpenSessionChangesSheet = { showChangesSheet = true },
            onOpenSessionInfoSheet = { showSessionInfoSheet = true },
            transcriptContent = {
                if (chatController != null && chatState != null) {
                    ChatTranscriptView(
                        state = chatState,
                        onRespond = { req, ok, data -> chatController.respond(req, ok, data) },
                        onLoadEarlier = chatController::loadEarlier,
                        onReload = { chatController.reload() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            },
        )

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

        if (showChangesSheet) {
            PhoneSessionChangesSheet(
                files = changeFiles,
                onToggleReviewed = {},
                onOpenFileDiff = { path ->
                    showChangesSheet = false
                    val proj = model.selectedProject
                    if (!proj.isNullOrBlank()) {
                        model.openFileTab(proj, path)
                        model.activeTabId?.let(nav::visitTab)
                    }
                },
                onDismiss = { showChangesSheet = false },
            )
        }

        if (showSessionInfoSheet) {
            val session = chatTab?.session
            PhoneSessionInfoSheet(
                info = PhoneSessionInfoData(
                    sessionId = session?.id ?: openChatTab?.id ?: "new",
                    title = session?.title?.ifBlank { "Untitled session" }
                        ?: openChatTab?.title?.ifBlank { "New Chat" }
                        ?: "New Chat",
                    modelLabel = modelLabel,
                    modeLabel = permissionModeId,
                    accountLabel = accountName,
                    costLabel = "—",
                    projectName = model.selectedProject ?: model.serverName,
                ),
                onRename = { showSessionInfoSheet = false },
                onOpenModelSheet = {
                    showSessionInfoSheet = false
                    showModelSheet = true
                },
                onOpenModeSheet = {
                    showSessionInfoSheet = false
                    showModesSheet = true
                },
                onMarkAsUnread = { showSessionInfoSheet = false },
                onShowLogs = {
                    showSessionInfoSheet = false
                    model.openLogs()
                    model.activeTabId?.let(nav::visitTab)
                },
                onOpenSessionDebug = { showSessionInfoSheet = false },
                onDismiss = { showSessionInfoSheet = false },
            )
        }

        if (showAttachSheet) {
            PhoneAttachSheet(
                onPickPhotos = { showAttachSheet = false },
                onOpenCamera = { showAttachSheet = false },
                onPickFiles = { showAttachSheet = false },
                onDismiss = { showAttachSheet = false },
            )
        }

        if (showHistorySheet) {
            Sheet(
                title = "Chat History",
                onDismiss = { showHistorySheet = false },
                forceBottomSheet = true,
                modifier = Modifier.testTag("phone-chat-history-sheet"),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .testTag("phone-chat-history-list"),
                ) {
                    PhoneSessionHistoryList(
                        groups = dayGroups,
                        onOpenSession = { item ->
                            showHistorySheet = false
                            model.openTab(item)
                            model.activeTabId?.let(nav::visitTab)
                        },
                        onTogglePin = { item ->
                            scope.launch { model.togglePin(item) }
                        },
                        onDeleteSession = { item ->
                            scope.launch { model.delete(item) }
                        },
                        unreadTypes = unreadMap.mapValues { it.value.type },
                        runningSessionIds = runningSet,
                        activeSessionId = chatTab?.session?.id,
                        onRenameSession = { item, newTitle ->
                            scope.launch { model.rename(item, newTitle) }
                        },
                    )
                }
            }
        }
    }
}
