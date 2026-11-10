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

/**
 * Routes a [PhoneArea] (either from an active [WorkspaceTab] or a synthetic phone area ID) to its
 * dedicated redesigned phone screen composable wired to [AppModel].
 */
@Composable
internal fun PhoneWorkspaceAreaBody(
    model: AppModel,
    area: PhoneArea,
    tab: WorkspaceTab?,
    nav: PhoneNav,
    showChatHistoryInitially: Boolean = false,
) {
    when (area) {
        PhoneArea.CHAT -> PhoneChatAreaHost(
            model = model,
            tab = tab,
            nav = nav,
            showHistoryInitially = showChatHistoryInitially,
        )
        PhoneArea.PROJECTS -> ProjectSwitcherSheet(
            model = model,
            onDismiss = { nav.closeSheet(SheetKey.ProjectSwitcher) },
            asFlyoutCard = false,
        )
        PhoneArea.FILES -> PhoneFilesAreaHost(
            model = model,
            tab = tab,
            nav = nav,
        )
        PhoneArea.SOURCE_CONTROL -> PhoneSourceControlAreaHost(
            model = model,
            tab = tab,
            nav = nav,
        )
        PhoneArea.TERMINAL -> PhoneTerminalAreaHost(
            model = model,
            tab = tab,
            nav = nav,
        )
        PhoneArea.DATABASE -> PhoneDatabaseAreaHost(
            model = model,
            tab = tab,
            nav = nav,
        )
        PhoneArea.MONITOR -> PhoneMonitorAreaHost(
            model = model,
        )
        PhoneArea.LOGS -> PhoneLogsAreaHost(
            model = model,
        )
        PhoneArea.SCHEDULES -> PhoneSchedulesAreaHost(
            model = model,
        )
        PhoneArea.PORT_FORWARDING -> PhonePortForwardingAreaHost(
            model = model,
            nav = nav,
        )
        PhoneArea.CLOUD_AND_SHARE -> PhoneCloudShareAreaHost(
            model = model,
            onDismiss = { nav.closeSheet(SheetKey.CloudShare) },
        )
        PhoneArea.SETTINGS -> PhoneSettingsAreaHost(
            model = model,
        )
    }
}

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
    var thinkingEnabled by remember { mutableStateOf(true) }

    val providerId = chatTab?.providerId ?: "claude"
    val modelLabel = remember(openChatTab?.metadata, providerId) {
        openChatTab?.metaString("model")?.takeIf { it.isNotBlank() } ?: when (providerId) {
            "codex" -> "Codex"
            "cursor" -> "Cursor"
            else -> "Claude Sonnet 4.6"
        }
    }
    val effortLabel = remember(openChatTab?.metadata) {
        (openChatTab?.metaString("effort") ?: "Medium").replaceFirstChar { it.uppercase() }
    }
    val permissionModeId = chatTab?.permissionMode?.value ?: PermissionMode.DEFAULT.value
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
            onStopTurn = { chatController?.stop() },
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
            val providers = remember(providerId) {
                listOf(
                    PhoneProviderOption(
                        id = providerId,
                        name = providerId.replaceFirstChar { it.uppercase() },
                        subtitle = "Active provider",
                    ),
                )
            }
            val availableModels = remember(modelLabel) {
                listOf(
                    PhoneModelOption(
                        id = modelLabel,
                        name = modelLabel,
                        description = "Default model",
                    ),
                )
            }
            PhoneModelEffortSheet(
                providers = providers,
                selectedProviderId = providerId,
                models = availableModels,
                selectedModelId = modelLabel,
                selectedEffort = effortLabel,
                thinkingEnabled = thinkingEnabled,
                providerLocked = chatTab?.session != null,
                onSelectProvider = { id -> chatController?.chooseProvider(id) },
                onSelectModel = { id -> chatController?.setModel(id) },
                onSelectEffort = {},
                onToggleThinking = { enabled ->
                    thinkingEnabled = enabled
                    chatController?.setThinking(enabled)
                },
                onResetEffortToDefault = {},
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

@Composable
internal fun PhoneFilesAreaHost(
    model: AppModel,
    tab: WorkspaceTab?,
    nav: PhoneNav,
) {
    if (tab == null || tab.type == "files") {
        PhoneFilesTreeScreen(
            model = model,
            onNavigate = {
                model.activeTabId?.let(nav::visitTab)
            },
        )
        return
    }

    val path = tab.metaString("filePath").orEmpty()
    val buffer = tab.metadata["inlineContent"].string
        ?: if (tab.metadata["isUntitled"].bool == true) {
            tab.metadata["unsavedContent"].string.orEmpty()
        } else {
            null
        }
    val controller = if (buffer == null) {
        remember(model, model.api, tab.id, path) { model.fileDocument(tab) }
    } else {
        null
    }

    DisposableEffect(controller) {
        controller?.show(readAsText = false)
        onDispose { controller?.hide() }
    }

    val docState = controller?.state?.collectAsState()?.value
    val displayPath = path.ifBlank { tab.title.ifBlank { "Untitled" } }

    PhoneFileTabScreen(
        filePath = displayPath,
        textContent = docState?.content ?: buffer.orEmpty(),
        binaryBytes = docState?.raw?.bytes,
        dirty = docState?.dirty == true,
        loading = docState?.loading == true,
        error = docState?.error,
        cannotDisplayBinary = docState?.cannotDisplay == true,
        onSave = {},
        onRetry = { controller?.refresh() },
    )
}

@Composable
internal fun PhoneSourceControlAreaHost(
    model: AppModel,
    tab: WorkspaceTab?,
    nav: PhoneNav,
) {
    if (tab != null && tab.type == "git-diff") {
        val filePath = tab.metaString("filePath") ?: tab.title.ifBlank { "diff" }
        val diffText = tab.metaString("diff") ?: tab.metaString("inlineContent").orEmpty()
        PhoneDiffScreen(
            filePath = filePath,
            diffText = diffText,
            onOpenFile = {
                val project = tab.projectId ?: model.selectedProject
                if (!project.isNullOrBlank()) {
                    model.openFileTab(project, filePath)
                    model.activeTabId?.let(nav::visitTab)
                }
            },
        )
        return
    }

    val project = model.selectedProject.orEmpty()
    val initialStatus = model.gitBranches[project]
    val initialChanges = model.gitChangesCache[model.gitChangesKey(project, null)]
        ?: model.gitChangesCache[project]

    PhoneSourceControlScreen(
        projectName = project,
        api = model.api,
        initialStatus = initialStatus,
        initialChanges = initialChanges,
        aiAvailable = model.info?.minimalMode != true,
        onWriteWithAi = { repo ->
            model.api?.gitCommitMessage(project, repo)
        },
        onOpenFile = { path ->
            if (project.isNotEmpty()) {
                model.openFileTab(project, path)
                model.activeTabId?.let(nav::visitTab)
            }
        },
        onDrawerClick = { nav.openDrawer() },
        onTitleClick = { nav.pushSheet(SheetKey.TabSwitcher) },
        onStatusChanged = { model.refreshGitStatus() },
    )
}

@Composable
internal fun PhoneTerminalAreaHost(
    model: AppModel,
    tab: WorkspaceTab?,
    nav: PhoneNav,
) {
    val gridTabs = model.openTabs.map { it.first }
    val dockTabs = model.dockTabs
    val activeDockTabId = model.layout?.dockPanel?.activeTabId
    val fallbackTab = remember(tab, gridTabs, dockTabs, model.selectedProject) {
        tab
            ?: gridTabs.lastOrNull { it.type == "terminal" }
            ?: dockTabs.lastOrNull { it.type == "terminal" }
            ?: WorkspaceTab(
                id = "terminal:1",
                type = "terminal",
                title = "Terminal",
                projectId = model.selectedProject,
            )
    }
    val resolvedTab = remember(fallbackTab, gridTabs, dockTabs, activeDockTabId, model.selectedProject) {
        resolvePhoneTerminalTab(
            requestedTab = fallbackTab,
            gridTabs = gridTabs,
            dockTabs = dockTabs,
            activeDockTabId = activeDockTabId,
            currentProject = model.selectedProject,
        )
    }

    val session = remember(model, model.api, resolvedTab.id) {
        model.terminal(resolvedTab)
    }

    if (session != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag("phone-terminal-screen"),
        ) {
            TerminalBody(
                model = model,
                tab = resolvedTab,
            )
        }
    } else {
        var ctrlMode by remember { mutableStateOf(false) }
        var selectMode by remember { mutableStateOf(false) }
        var actionsSheetOpen by remember { mutableStateOf(false) }

        val offlineState = remember(model.api, model.connectionLost, resolvedTab) {
            TerminalState(
                connected = model.api != null && !model.connectionLost,
                reconnecting = model.connectionLost,
                sessionId = resolvedTab.metaString("sessionId"),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("phone-terminal-screen"),
        ) {
            PhoneTerminalStatusBar(
                state = offlineState,
                cwd = resolvedTab.metaString("cwd") ?: model.currentProject?.path,
                foldedInLandscape = false,
                onOpenActionsSheet = { actionsSheetOpen = true },
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
            TerminalMobileToolbar(
                ctrlMode = ctrlMode,
                onToggleCtrl = { ctrlMode = !ctrlMode },
                selectMode = selectMode,
                onToggleSelect = { selectMode = !selectMode },
                onKey = { _, _ -> },
                onCopy = {},
                onPaste = {},
                onSendToChat = {},
                onOpenLinks = {},
                phoneKeyRowMode = true,
                hideWhenHardwareKeyboardConnected = false,
            )
        }

        if (actionsSheetOpen) {
            PhoneTerminalActionsSheet(
                sessionId = offlineState.sessionId,
                cwd = resolvedTab.metaString("cwd") ?: model.currentProject?.path,
                hasSelection = false,
                selectMode = selectMode,
                linkCount = 0,
                exited = false,
                projectTerminals = gridTabs.filter { it.type == "terminal" },
                activeTerminalId = resolvedTab.id,
                onCopy = { actionsSheetOpen = false },
                onPaste = { actionsSheetOpen = false },
                onToggleSelect = {
                    selectMode = !selectMode
                    actionsSheetOpen = false
                },
                onOpenLinks = { actionsSheetOpen = false },
                onSendToChat = { actionsSheetOpen = false },
                onRestart = { actionsSheetOpen = false },
                onSelectTerminal = { picked ->
                    actionsSheetOpen = false
                    nav.visitTab(picked.id)
                    model.activate(picked.id)
                },
                onDismiss = { actionsSheetOpen = false },
            )
        }
    }
}

@Composable
internal fun PhoneDatabaseAreaHost(
    model: AppModel,
    tab: WorkspaceTab?,
    nav: PhoneNav,
) {
    val onNavigateToWorkspaceTab: () -> Unit = {
        model.activeTabId?.let(nav::visitTab)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("phone-database-screen"),
    ) {
        if (tab == null) {
            DatabaseSidebarPane(
                model = model,
                onNavigate = onNavigateToWorkspaceTab,
                readOnly = true,
            )
        } else {
            val readOnlyTab = remember(tab) {
                tab.withMetadata("readOnly" to JsonPrimitive(true))
            }
            when (readOnlyTab.type) {
                "database" -> DatabaseTableTabBody(model, readOnlyTab)
                "sqlite" -> SqliteViewerTabBody(model, readOnlyTab)
                "db-query" -> DatabaseQueryTabBody(model, readOnlyTab)
                "db-sql" -> DbSqlObjectTabBody(model, readOnlyTab)
                else -> DatabaseSidebarPane(
                    model = model,
                    onNavigate = onNavigateToWorkspaceTab,
                    readOnly = true,
                )
            }
        }
    }
}

@Composable
internal fun PhoneMonitorAreaHost(
    model: AppModel,
) {
    val monitorState = remember { PhoneMonitorState() }
    val wantProcesses = monitorState.activeTab != PhoneMonitorTab.OVERVIEW &&
        monitorState.activeTab != PhoneMonitorTab.PERFORMANCE

    DisposableEffect(model, wantProcesses) {
        val release = model.retainResourceMonitor(wantProcesses = wantProcesses)
        onDispose { release() }
    }

    PhoneSystemMonitorScreen(
        state = monitorState,
        snapshot = model.sharedMetricsSnapshot,
    )
}

@Composable
internal fun PhoneLogsAreaHost(
    model: AppModel,
) {
    val logsState = remember { PhoneLogsState() }
    var entries by remember { mutableStateOf<List<LogEntry>>(emptyList()) }

    LaunchedEffect(model.api, logsState.timeRange) {
        val api = model.api ?: return@LaunchedEffect
        try {
            val filter = LogFilter()
            val from = rangeStart(logsState.timeRange, System.currentTimeMillis())
            val pairs = logsQueryPairs(filter, logsState.timeRange, from, before = null)
            val json = api.queryLogs(pairs)
            val parsed = LogQueryResult.from(json)
            entries = parsed.entries
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep existing log entries on transient failure.
        }
    }

    DisposableEffect(model, model.events) {
        val releaseTail = model.retainLogsTail()
        val ev = model.events
        val unsubLines = ev?.onLogsLines { msg ->
            if (logsState.follow) {
                val incoming = msg["entries"].array.orEmpty().mapNotNull(LogEntry::from)
                if (incoming.isNotEmpty()) {
                    entries = (entries + incoming).takeLast(500)
                }
            }
        }
        onDispose {
            unsubLines?.invoke()
            releaseTail()
        }
    }

    PhoneLogsScreen(
        state = logsState,
        entries = entries,
    )
}

@Composable
internal fun PhonePortForwardingAreaHost(
    model: AppModel,
    nav: PhoneNav,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val portState = remember { PhonePortForwardingState() }
    var tunnels by remember { mutableStateOf<List<TunnelEntry>>(emptyList()) }
    var tailscale by remember { mutableStateOf<TailscaleAvailability?>(null) }
    var cloudflare by remember { mutableStateOf<NamedTunnelStatus?>(null) }

    suspend fun refreshTunnels(force: Boolean = false) {
        val api = model.api ?: return
        try {
            val data = api.tunnelsList(force)
            tunnels = TunnelsModel.parseList(data)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    LaunchedEffect(model.api) {
        val api = model.api ?: return@LaunchedEffect
        refreshTunnels()
        try {
            tailscale = TailscaleAvailability.from(api.tunnelsTransports())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        try {
            cloudflare = NamedTunnelStatus.from(api.namedTunnelStatus())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    val tailscaleReady = tailscale?.available ?: true
    val tailscaleDetail = tailscale?.dnsName?.takeIf { it.isNotBlank() } ?: "tailnet-01.ts.net"
    val cloudflareReady = cloudflare?.let { !it.hostname.isNullOrBlank() } ?: true
    val cloudflareDetail = cloudflare?.hostname?.takeIf { it.isNotBlank() }
        ?: model.namedHostname?.takeIf { it.isNotBlank() }
        ?: "ppm.example.com"

    PhonePortForwardingScreen(
        state = portState,
        tunnels = tunnels,
        tailscaleReady = tailscaleReady,
        tailscaleDetail = tailscaleDetail,
        cloudflareReady = cloudflareReady,
        cloudflareDetail = cloudflareDetail,
        onStartForward = { port, transport ->
            val api = model.api ?: return@PhonePortForwardingScreen
            scope.launch {
                try {
                    api.tunnelStart(port = port, via = transport)
                    refreshTunnels(force = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        },
        onStopTunnel = { entry ->
            val api = model.api
            tunnels = tunnels.filterNot { it.pid == entry.pid && it.port == entry.port }
            if (api != null) {
                scope.launch {
                    try {
                        api.tunnelStop(entry.pid)
                        refreshTunnels(force = true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
            }
        },
        onOpenInTab = { entry ->
            val url = entry.url?.takeIf { it.isNotBlank() } ?: "http://localhost:${entry.port ?: 3210}"
            model.openWebPreviewTab(url = url, port = entry.port, via = entry.via ?: "cloudflare")
            model.activeTabId?.let(nav::visitTab)
        },
        onOpenInBrowser = { entry ->
            val url = entry.url?.takeIf { it.isNotBlank() } ?: "http://localhost:${entry.port ?: 3210}"
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }
        },
        onCopyLink = { entry ->
            val url = entry.url?.takeIf { it.isNotBlank() } ?: "http://localhost:${entry.port ?: 3210}"
            runCatching {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("Tunnel URL", url))
            }
        },
    )
}

@Composable
internal fun PhoneSchedulesAreaHost(
    model: AppModel,
) {
    val scope = rememberCoroutineScope()
    val schedulesState = remember { PhoneSchedulesState() }

    suspend fun reloadSchedules() {
        val api = model.api ?: return
        try {
            schedulesState.schedules = parseSchedulesList(api.schedulesList(enabledOnly = false))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    LaunchedEffect(model.api) {
        reloadSchedules()
    }

    PhoneSchedulesScreen(
        state = schedulesState,
        onRunOrRestart = { row ->
            val api = model.api ?: return@PhoneSchedulesScreen
            scope.launch {
                try {
                    api.schedulesRunNow(row.id)
                    reloadSchedules()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        },
        onToggleEnabled = { row, enabled ->
            schedulesState.toggleScheduleEnabled(row.id, enabled)
            val api = model.api ?: return@PhoneSchedulesScreen
            scope.launch {
                try {
                    api.schedulesUpdate(row.id, buildJsonObject { put("enabled", enabled) })
                    reloadSchedules()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        },
    )
}

@Composable
internal fun PhoneCloudShareAreaHost(
    model: AppModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val cloudState = remember(model.namedHostname, model.localUrl) {
        PhoneCloudShareState(
            initialLocalUrl = model.localUrl ?: "http://localhost:3210",
            initialTunnelActive = !model.namedHostname.isNullOrBlank(),
            initialPublicUrl = model.namedHostname?.takeIf { it.isNotBlank() }?.let { host ->
                if (host.startsWith("http://") || host.startsWith("https://")) host else "https://$host"
            } ?: "https://ppm.cloud/workstation-01",
        )
    }

    PhoneCloudShareSheet(
        state = cloudState,
        onDismiss = onDismiss,
        onCopyUrl = { url ->
            runCatching {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("PPM Share URL", url))
            }
        },
    )
}

@Composable
internal fun PhoneSettingsAreaHost(
    model: AppModel,
) {
    val initialRoute = remember(model.requestedSettingsCategory) {
        resolvePhoneSettingsRoute(model.requestedSettingsCategory)
    }

    PhoneSettingsScreen(
        model = model,
        initialRoute = initialRoute,
        modifier = Modifier.testTag("phone-settings-screen"),
    )
}
