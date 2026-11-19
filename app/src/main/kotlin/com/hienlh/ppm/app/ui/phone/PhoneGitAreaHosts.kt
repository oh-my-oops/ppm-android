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
import com.hienlh.ppm.app.ui.phone.git.PhoneGitDiffTabHost
import com.hienlh.ppm.app.ui.phone.git.PhoneGitTabBodyHost
import com.hienlh.ppm.app.ui.phone.git.isPhoneGitTabBody
import com.hienlh.ppm.app.ui.phone.git.rememberPhoneGitTabActions
import com.hienlh.ppm.app.ui.phone.git.PhoneSourceControlScreen
import com.hienlh.ppm.client.gitStageHunks
import com.hienlh.ppm.client.gitUnstageHunks
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
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
internal fun PhoneSourceControlAreaHost(
    model: AppModel,
    tab: WorkspaceTab?,
    nav: PhoneNav,
) {
    if (tab != null && tab.type == "git-diff") {
        PhoneGitDiffTabHost(
            model = model,
            tab = tab,
            onOpenFile = { project, path ->
                model.openFileTab(project, path)
                model.activeTabId?.let(nav::visitTab)
            },
        )
        return
    }
    // Resolving a conflict, Review changes, Branch review, the commit list and the Git Graph: the
    // tablet's own tab body, full screen (`TabBody`), as the web below `md` shows each of them.
    if (tab != null && isPhoneGitTabBody(tab)) {
        PhoneGitTabBodyHost(model = model, tab = tab)
        return
    }

    val project = model.selectedProject.orEmpty()
    val initialStatus = model.gitBranches[project]
    val initialChanges = model.cachedGitChanges(project)
        ?: model.gitChangesCache[model.gitChangesKey(project, null)]

    // While this screen is up it is what reads the project's changes, as the old panel was: watchGit
    // stands down, and the drawer badge, the tree's letters and the chat's changes chip follow its reads.
    DisposableEffect(model, project) {
        if (project.isNotEmpty()) model.onGitStatusPanelMounted(project)
        onDispose { if (project.isNotEmpty()) model.onGitStatusPanelUnmounted(project) }
    }
    LaunchedEffect(model.api, project) {
        if (project.isNotEmpty()) model.loadGitRepos(project)
    }
    val refreshEvents = remember(model, project) { phoneGitRefreshEvents(model, project) }
    val repo = com.hienlh.ppm.state.git.GitRepoScope.resolveRepo(model.gitDiscovery[project], model.gitChosenRepoPath[project])
    val linesTarget = remember(project) { mutableStateOf<Pair<com.hienlh.ppm.state.git.FileChange, String>?>(null) }
    val tabActions = rememberPhoneGitTabActions(model, project, repo, nav, onPickLines = { change, scope -> linesTarget.value = change to scope })

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
        refreshEvents = refreshEvents,
        onChangesLoaded = { repo, changes -> model.applyGitChanges(project, repo, changes) },
        commitDrafts = model.commitDrafts,
        chosenRepoPath = model.gitChosenRepoPath[project],
        onRepoChosen = { repo -> model.chooseGitRepo(project, repo.path) },
        onStageHunks = { change, hunks ->
            val client = model.api ?: error("Not connected")
            val repo = com.hienlh.ppm.state.git.GitRepoScope.resolveRepo(model.gitDiscovery[project], model.gitChosenRepoPath[project])
            val items = hunks.map { com.hienlh.ppm.client.HunkSelectionItem(hunk = it.index, id = it.id) }
            if (change.isStaged) {
                client.gitUnstageHunks(project, change.path, items, repo)
            } else {
                client.gitStageHunks(project, change.path, items, repo)
            }
        },
        tabActions = tabActions,
        onNotice = { text -> model.toast(com.hienlh.ppm.app.ErrorToastMessage(text, kind = com.hienlh.ppm.app.ErrorToastMessage.Kind.SUCCESS)) },
        onDraftNotUsed = {
            model.toast(
                com.hienlh.ppm.app.ErrorToastMessage(
                    com.hienlh.ppm.state.git.WriteWithAi.DRAFT_NOT_USED_TITLE,
                    com.hienlh.ppm.state.git.WriteWithAi.DRAFT_NOT_USED_DESCRIPTION,
                    kind = com.hienlh.ppm.app.ErrorToastMessage.Kind.PLAIN,
                ),
            )
        },
    )

    // "Stage lines…" / "Unstage lines…": the tablet's line picker, a bottom sheet on a phone (`hunk-stage-dialog.tsx`).
    linesTarget.value?.let { (change, scope) ->
        com.hienlh.ppm.app.ui.git.GitHunkStageDialog(
            model = model,
            project = project,
            repo = repo,
            filePath = change.path,
            scope = scope,
            onClose = { linesTarget.value = null },
            onApplied = { model.refreshGitStatus(project) },
        )
    }
}

/**
 * When the phone Source Control reads the changes again (the old panel's three triggers): PPM wrote to the
 * project's repository or one of its files changed (`gitChangedEvents`, which the app bar's Refresh status
 * also sends), the app came back to the front, and every 5 s while it is in front.
 */
internal fun phoneGitRefreshEvents(model: AppModel, project: String): Flow<Unit> = merge(
    model.gitChangedEvents.filter { it == project }.map { },
    snapshotFlow { model.windowVisible }.drop(1).filter { it }.map { },
    flow {
        while (true) {
            delay(5_000)
            if (model.windowVisible) emit(Unit)
        }
    },
)
