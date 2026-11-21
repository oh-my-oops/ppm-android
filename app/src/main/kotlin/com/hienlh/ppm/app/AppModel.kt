package com.hienlh.ppm.app

import com.hienlh.ppm.protocol.ChatTurnRefs
import com.hienlh.ppm.client.ChatClientId
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.hienlh.ppm.app.update.AppUpdater
import com.hienlh.ppm.chat.ToolImage
import com.hienlh.ppm.client.renameProject
import com.hienlh.ppm.client.removeProject
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.GitBranchStatus
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.client.ServerProfile
import com.hienlh.ppm.client.ServerStore
import com.hienlh.ppm.client.UiPrefs
import com.hienlh.ppm.client.createTag
import com.hienlh.ppm.client.deleteSession
import com.hienlh.ppm.client.deleteSessions
import com.hienlh.ppm.client.deleteTag
import com.hienlh.ppm.client.gitRepos
import com.hienlh.ppm.client.gitStatus
import com.hienlh.ppm.client.keybindings
import com.hienlh.ppm.client.projectImage
import com.hienlh.ppm.client.fsStat
import com.hienlh.ppm.client.rawFile
import com.hienlh.ppm.client.readProjectFile
import com.hienlh.ppm.client.uploadedFile
import com.hienlh.ppm.client.putUiPrefs
import com.hienlh.ppm.client.renameSession
import com.hienlh.ppm.client.sessionLinks
import com.hienlh.ppm.client.setSessionAccount
import com.hienlh.ppm.client.stopChildSessions
import com.hienlh.ppm.client.settingsListThemes
import com.hienlh.ppm.client.reorderProjects
import com.hienlh.ppm.client.resetTags
import com.hienlh.ppm.client.setDefaultTag
import com.hienlh.ppm.client.setTag
import com.hienlh.ppm.client.uiPrefs
import com.hienlh.ppm.client.updateTag
import com.hienlh.ppm.client.webFile
import com.hienlh.ppm.client.fullResourceSamples
import com.hienlh.ppm.client.logsIssuesSummary
import com.hienlh.ppm.client.androidCapabilities
import com.hienlh.ppm.client.remoteDesktopCapabilities
import com.hienlh.ppm.design.GalleryImage
import com.hienlh.ppm.design.IconFramework
import com.hienlh.ppm.design.PpmLog
import com.hienlh.ppm.design.PpmTheme
import com.hienlh.ppm.design.ViewerImage
import com.hienlh.ppm.state.files.FileDocumentClassifier
import com.hienlh.ppm.state.files.FileDocumentViewer
import com.hienlh.ppm.protocol.ChatDraft
import com.hienlh.ppm.protocol.ChatEvent
import com.hienlh.ppm.protocol.ChatReply
import com.hienlh.ppm.protocol.ChatSearchHit
import com.hienlh.ppm.protocol.DraftAttachment
import com.hienlh.ppm.protocol.FileMention
import com.hienlh.ppm.protocol.Group
import com.hienlh.ppm.protocol.LinkedSessionUnlinks
import com.hienlh.ppm.protocol.McpServerName
import com.hienlh.ppm.protocol.McpSignInDismissals
import com.hienlh.ppm.protocol.PickedAccount
import com.hienlh.ppm.protocol.ProjectInfo
import com.hienlh.ppm.protocol.ProjectTags
import com.hienlh.ppm.protocol.ProviderDefaults
import com.hienlh.ppm.protocol.ProviderInfo
import com.hienlh.ppm.protocol.ReplyReference
import com.hienlh.ppm.protocol.ServerInfo
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.SessionTag
import com.hienlh.ppm.protocol.SessionUnread
import com.hienlh.ppm.protocol.SlashItem
import com.hienlh.ppm.protocol.SlashTrigger
import com.hienlh.ppm.protocol.TabOpenAnswer
import com.hienlh.ppm.protocol.TabOpenRequest
import com.hienlh.ppm.protocol.ThemeSelection
import com.hienlh.ppm.protocol.TurnOptions
import com.hienlh.ppm.protocol.double
import com.hienlh.ppm.protocol.sortedForList
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.AgentSessionFallbacks
import com.hienlh.ppm.state.AgentSessionOpening
import com.hienlh.ppm.state.AgentSessionPayload
import com.hienlh.ppm.state.AttentionNotice
import com.hienlh.ppm.state.BackgroundOutput
import com.hienlh.ppm.state.ChatOpenPlan
import com.hienlh.ppm.state.planChatOpen
import com.hienlh.ppm.state.ChatAttention
import com.hienlh.ppm.state.ChatController
import com.hienlh.ppm.state.ChatItem
import com.hienlh.ppm.state.ChatJumpRequest
import com.hienlh.ppm.state.ChatSearch
import com.hienlh.ppm.state.ClientCommand
import com.hienlh.ppm.state.DraftLocalStore
import com.hienlh.ppm.state.DraftSync
import com.hienlh.ppm.state.FileIndexStore
import com.hienlh.ppm.state.GlobalEvents
import com.hienlh.ppm.state.GroupChat
import com.hienlh.ppm.state.HealthCheck
import com.hienlh.ppm.state.HealthWatch
import com.hienlh.ppm.state.HistoryList
import com.hienlh.ppm.state.KeyBindings
import com.hienlh.ppm.state.McpSignInBackend
import com.hienlh.ppm.state.McpSignInFlow
import com.hienlh.ppm.state.ModelCatalog
import com.hienlh.ppm.state.NewChatCache
import com.hienlh.ppm.state.NotificationTones
import com.hienlh.ppm.state.updateUnlinks
import com.hienlh.ppm.state.agentSessionKeysOnScreen
import com.hienlh.ppm.state.agentSessionOpening
import com.hienlh.ppm.state.workspace.NewChatDefaults
import com.hienlh.ppm.state.PermissionMode
import com.hienlh.ppm.state.RejectedSend
import com.hienlh.ppm.state.SessionChanges
import com.hienlh.ppm.state.SessionChangesBackend
import com.hienlh.ppm.state.SessionChangesList
import com.hienlh.ppm.state.SessionTurnsMemo
import com.hienlh.ppm.state.SessionTurnsStore
import com.hienlh.ppm.state.SessionWrites
import com.hienlh.ppm.state.messageWithAttachments
import com.hienlh.ppm.state.monotonicMillis
import com.hienlh.ppm.state.SlashItemsStore
import com.hienlh.ppm.state.UsageCache
import com.hienlh.ppm.state.selectInlineImages
import com.hienlh.ppm.state.windows.MAX_WINDOWS
import com.hienlh.ppm.state.windows.WindowKind
import com.hienlh.ppm.state.windows.WindowStore
import com.hienlh.ppm.state.windows.WindowsState
import com.hienlh.ppm.state.preview.PreviewLoads
import com.hienlh.ppm.state.preview.PreviewTicket
import com.hienlh.ppm.state.preview.answerTabOpen
import com.hienlh.ppm.state.workspace.DOCK_PANEL_ID
import com.hienlh.ppm.state.workspace.FileNavigation
import com.hienlh.ppm.state.workspace.NavEntry
import com.hienlh.ppm.state.workspace.NavHistory
import com.hienlh.ppm.state.workspace.NewTab
import com.hienlh.ppm.state.workspace.SourceLine
import com.hienlh.ppm.state.workspace.SplitDirection
import com.hienlh.ppm.state.workspace.UrlState
import com.hienlh.ppm.state.workspace.WorkspaceCache
import com.hienlh.ppm.state.workspace.WorkspaceState
import com.hienlh.ppm.state.workspace.WorkspaceStore
import com.hienlh.ppm.state.workspace.WorkspaceTab
import com.hienlh.ppm.state.workspace.WorkspacePanel
import com.hienlh.ppm.state.workspace.BulkClose
import com.hienlh.ppm.state.workspace.bulkCloseIds
import com.hienlh.ppm.state.workspace.tabHoldsUnsavedWork
import com.hienlh.ppm.state.workspace.visibleTabs
import com.hienlh.ppm.state.workspace.openAiBranchReviewTab
import com.hienlh.ppm.state.workspace.openAiQueryTab
import com.hienlh.ppm.state.workspace.openAiTab
import com.hienlh.ppm.state.workspace.openAiUrlTab
import com.hienlh.ppm.state.workspace.resolveFileNavigation
import com.hienlh.ppm.state.workspace.resolveToolCallTarget
import com.hienlh.ppm.state.workspace.buildMetadataFromUrl
import com.hienlh.ppm.state.workspace.buildTitleFromUrl
import com.hienlh.ppm.state.workspace.frontTabs
import com.hienlh.ppm.state.workspace.heldTabs
import com.hienlh.ppm.state.workspace.isWindowPanelId
import com.hienlh.ppm.state.workspace.parsePpmLink
import com.hienlh.ppm.state.workspace.reopenedTab
import com.hienlh.ppm.state.workspace.tabIdFromUrl
import com.hienlh.ppm.state.workspace.tabSessionId
import com.hienlh.ppm.state.workspace.workspaceRemote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import okhttp3.HttpUrl

/**
 * What the app shows (`AppModel.swift`): the open server, its projects, a project's chats, the
 * tabs open on it (the layout every browser of that server shares) and the theme. Lives as long as the process, so a rotation or a
 * trip to the background keeps every chat connected. Main thread only.
 */
@Stable
class AppModel(context: Context, val debug: DebugLaunch? = null, private val windowId: String? = null, initialProject: String? = null) {
    sealed interface Load {
        data object Idle : Load
        data object Loading : Load
        data object Loaded : Load
        data class Failed(val message: String) : Load
    }

    /** The main thread: every controller below is driven from it. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var initialProject = initialProject
    private val prefs = PrefsStore(context)
    private val terminalClipboard = context.applicationContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    private val terminals = mutableMapOf<String, com.hienlh.ppm.terminal.NativeTerminal>()
    private var knownTerminalIds = emptySet<String>()
    private val documents = mutableMapOf<String, com.hienlh.ppm.state.files.FileDocumentController>()

    /** File buffers, like shells, survive switching tabs and are released with their tab. */
    fun fileDocument(tab: WorkspaceTab): com.hienlh.ppm.state.files.FileDocumentController? {
        val path = tab.metaString("filePath") ?: return null
        val client = api ?: return null
        val project = tab.projectId ?: tab.metaString("projectName")
        documents[tab.id]?.let { current ->
            if (current.state.value.path == path && current.state.value.project == project) return current
            current.close()
        }
        return com.hienlh.ppm.state.files.FileDocumentController(client, project, path, scope).also { documents[tab.id] = it }
    }

    /** An open editor tab holds unsaved edits to [path] of [project] (a search replace never writes under it). */
    fun hasUnsavedEdits(project: String, path: String): Boolean =
        documents.values.any { it.state.value.let { s -> s.project == project && s.path == path && s.dirty } }

    private fun syncDocuments(state: WorkspaceState) {
        val live = state.panels.values.flatMap { it.tabs }.filter { it.type == "editor" }.map { it.id }.toSet()
        for (id in documents.keys.toList()) if (id !in live) documents.remove(id)?.close()
    }

    /**
     * A tab's shell, made the first time it is asked for and kept, parser and socket, across tab, panel and dock moves until the tab
     * closes. [syncTerminals] asks as soon as the tab is in front anywhere, shown or not; a terminal that is shown first asks itself.
     */
    fun terminal(tab: WorkspaceTab): com.hienlh.ppm.terminal.NativeTerminal? {
        terminals[tab.id]?.let { return it }
        val client = api ?: return null
        return com.hienlh.ppm.terminal.NativeTerminal(
            client, terminalProject(tab), tab.id, scope, prefs, "terminal.$serverKey.", tab.metaString("cwd"),
            initialSessionId = tab.metaString("sessionId") ?: "new",
            copyToClipboard = { text -> terminalClipboard.setPrimaryClip(android.content.ClipData.newPlainText("Terminal", text)) },
        ).also { terminals[tab.id] = it }
    }

    /**
     * Ends the shells of tabs that went and attaches the shell of every terminal tab the panels hold ([heldTabs]): the one in
     * front or behind another tab, in the open project's grid or one seen earlier, in a window (a minimized one's too) or in the
     * dock (collapsed on a phone, hidden on a tablet). Attached, not shown: a view that is never laid out holds what its shell
     * prints and says nothing of a size, but its socket is open, so the server does not end the shell 30 minutes after the last
     * socket went (`RECONNECT_GRACE_MS`). This is more than the web mounts, which is only a panel's front tab ([frontTabs]): there
     * a tab behind another has no socket after a reload, is ended by that grace, and the next visit starts a new shell without a
     * word. A shell stays attached while its tab is behind another, in another panel or in another project, until the tab
     * closes: it is in [terminals] and only a tab that is gone takes it out, which is the web's keep-alive. A tab of a project
     * whose layout is not loaded yet is in no panel here, so it is attached once it is, as in the web.
     */
    internal fun syncTerminals(state: WorkspaceState) {
        val live = state.panels.values.flatMap { it.tabs }.filter { it.type == "terminal" }.map { it.id }.toSet()
        for (id in knownTerminalIds - live) {
            terminals.remove(id)?.close()
            prefs.put("terminal.$serverKey.ppm:terminal-session:$id", null)
        }
        knownTerminalIds = live
        for (tab in heldTabs(state)) {
            if (tab.type == "terminal" && tab.id !in terminals) terminal(tab)?.controller?.start()
        }
    }

    /** The shell of [tabId] if it has been made; the test of what [syncTerminals] attached. */
    internal fun terminalOf(tabId: String): com.hienlh.ppm.terminal.NativeTerminal? = terminals[tabId]

    /** A shell in the open project, or in none: the web opens it all the same, with no project on the tab. */
    fun openTerminal(inDock: Boolean = wide, panelId: String? = null) {
        val tab = terminalTab(selectedProject)
        withWorkspace { store -> if (inDock) store.openInDock(tab) else store.openTab(tab, panelId) }
    }

    /** An untitled editor tab (`useTabStore.openNewFile`), in [panelId] when given. */
    fun openNewFile(panelId: String? = null, language: String? = null, title: String? = null) {
        withWorkspace { store ->
            val num = com.hienlh.ppm.state.workspace.nextUntitledNumber(store.state.value.panels)
            store.openTab(
                NewTab(
                    type = "editor",
                    title = title ?: "Untitled-$num",
                    projectId = null,
                    metadata = buildJsonObject {
                        put("isUntitled", true)
                        put("untitledNumber", num)
                        language?.let { put("language", it) }
                    },
                    closable = true,
                ),
                panelId,
            )
        }
    }

    /** Opens the Problems list in the dock on desktop, or as a tab on mobile (`useOpenProblems`). */
    fun openProblems() {
        val tab = NewTab(type = "problems", title = "Problems", projectId = null, closable = true)
        withWorkspace { store -> if (wide) store.openInDock(tab) else store.openTab(tab) }
    }

    /**
     * "Open in Terminal" (`openTerminalAt`): a shell in the dock that starts in [dir], an absolute
     * host path. [project] keeps the tab on a project when the folder belongs to one; a folder of the
     * host's own explorer passes none.
     */
    fun openTerminalAt(dir: String, project: String? = null) {
        val tab = terminalAtTab(dir, project)
        withWorkspace { store -> store.openInDock(tab) }
    }

    internal fun terminalCommandTaken(tabId: String) = patchTabMetadata(tabId, "pendingCommand" to null)

    /**
     * Code-block action (`runInTerminal`): type a command into a terminal that is on screen, whichever
     * project its shell was started for ([shownTerminalOf]), or open one in the dock to hold it
     * ([runInTerminalTab]). No project is opened first: the dock is the same for every project.
     */
    fun runInTerminal(command: String, projectName: String? = selectedProject) {
        if (command.isBlank()) return
        val state = layout ?: return
        val shown = shownTerminalOf(state)
        if (shown != null) terminal(shown)?.pendingCommand = command
        else withWorkspace { store -> store.openInDock(runInTerminalTab(command, projectName)) }
    }

    /** Put terminal text into an editable chat draft; never submit it automatically. */
    fun terminalToChat(text: String, label: String = "Terminal output", project: String? = selectedProject, newTab: Boolean = false, asContext: Boolean = false) {
        // A shell opened with no project is named "" (see [terminal]): its text goes to any chat, as the web's `sendToChat` with no `projectName`.
        if (project != null && project.isEmpty()) return terminalToChat(text, label, null, newTab, asContext)
        if (project != null && project != selectedProject) {
            scope.launch { openProject(project) { terminalToChat(text, label, project, newTab, asContext) } }
            return
        }
        val state = layout ?: return
        val target = if (newTab) null else state.panels.values.flatMap { panel ->
            panel.tabs.filter { it.type == "chat" && (project == null || it.projectId == project || it.metaString("projectName") == project) }
                .map { tab ->
                    val rank = if (panel.activeTabId != tab.id) 0 else if (panel.id == state.focusedPanelId) 2 else 1
                    Triple(tab, rank, (tab.metadata["lastActiveAt"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: -1.0)
                }
        }.maxWithOrNull(compareBy<Triple<WorkspaceTab, Int, Double>> { it.second }.thenBy { it.third })?.first
        val chat = target?.let { chats[it.id] }
        if (chat == null) withWorkspace { store -> store.openTab(sendToChatTab(text, label, project, asContext)) } else {
            chat.attachments += ComposerAttachment(
                id = java.util.UUID.randomUUID().toString(), name = label, isImage = false,
                status = ComposerAttachment.Status.READY, textContent = text,
            )
            activate(chat.tabId)
            chat.requestFocus()
        }
        if (!wide) setDockVisible(false)
    }

    /** Every message box's draft, in a file of its own: it is written on each keystroke. */
    private val draftPrefs = PrefsStore(context, "ppm_drafts")
    val store = ServerStore(prefs, KeystoreSecrets(context), windowId?.let { "window.$it.selectedServer" } ?: "selectedServer")

    /** The tab switcher's order, `default` or `recent`, kept on this device as the web keeps it. */
    var tabSortMode by mutableStateOf(prefs.get("tabSortMode") ?: "default")
        private set

    /** Set when a server is opened; `internal` so a test can point a view at a fake one. */
    var api by mutableStateOf<ApiClient?>(null)
        internal set

    /** Each project's `/` list per provider (`slash-items-cache.ts`), for the open server. */
    var slashItems by mutableStateOf<SlashItemsStore?>(null)
        internal set

    /** The open project's file list, which the `@` picker searches (the web's file-store index). */
    var fileIndex by mutableStateOf<FileIndexStore?>(null)
        internal set

    var fileTree by mutableStateOf<com.hienlh.ppm.state.files.ProjectFileTree?>(null)
        private set
    var fileMutations by mutableStateOf<com.hienlh.ppm.state.files.FileMutations?>(null)
        private set

    var hostFilesystem by mutableStateOf<com.hienlh.ppm.state.osexplorer.HostFilesystemStore?>(null)
        private set
    var hostPickerPreferences by mutableStateOf<com.hienlh.ppm.state.osexplorer.HostPickerPreferences?>(null)
        private set
    var explorerPreferences by mutableStateOf<com.hienlh.ppm.state.osexplorer.ExplorerPreferences?>(null)
        private set
    var explorerSheetPath by mutableStateOf<String?>(null)
        private set
    private val hostExplorers = mutableMapOf<String, com.hienlh.ppm.state.osexplorer.ExplorerSession>()

    private var hostUploadQueue: com.hienlh.ppm.state.osexplorer.HostUploads? = null

    fun hostUploads(separator: String): com.hienlh.ppm.state.osexplorer.HostUploads? {
        hostUploadQueue?.let { return it }
        val client = api ?: return null
        return com.hienlh.ppm.state.osexplorer.HostUploads(scope, client, separator) { affected ->
            if (api === client) filesMutated(com.hienlh.ppm.state.files.FileMutationReport(affectedDirectories = affected))
        }.also { hostUploadQueue = it }
    }

    fun hostExplorer(id: String, path: String): com.hienlh.ppm.state.osexplorer.ExplorerSession? {
        hostExplorers[id]?.let { return it }
        val client = api ?: return null
        val preferences = explorerPreferences ?: return null
        return com.hienlh.ppm.state.osexplorer.ExplorerSession(client, preferences, scope, path) { next ->
            if (id == "phone-explorer") explorerSheetPath = next
            else windows?.setPayload(id, buildJsonObject { put("path", next) })
        }.also { hostExplorers[id] = it }
    }

    fun closeExplorerSheet() {
        explorerSheetPath = null
        hostExplorers.remove("phone-explorer")?.close()
    }

    fun openHostFile(path: String) {
        withWorkspace { it.openTab(NewTab(type = "editor", title = path.split('/', '\\').last(),
            metadata = buildJsonObject { put("filePath", path) })) }
        if (!wide) closeExplorerSheet()
    }

    /** A file chip's file (`FilePathChip`): opened whether or not it can be read, as the web's chip does after its `/api/fs/read` either way. */
    fun openFilePath(path: String, projectName: String? = selectedProject) {
        val tab = filePathTab(path, projectName)
        withWorkspace { it.openTab(tab) }
    }

    /** Refresh explicit mutation results even when the host watcher misses the change. */
    fun filesMutated(report: com.hienlh.ppm.state.files.FileMutationReport) {
        val affected = report.affectedDirectories
        for (explorer in hostExplorers.values) affected.forEach(explorer::directoryChanged)
        for (project in projects) {
            val directories = affected.mapNotNull { com.hienlh.ppm.state.workspace.relativeProjectPath(project.path, it) }
            if (directories.isEmpty()) continue
            fileIndex?.markStale(project.name)
            for (directory in directories) fileTree?.invalidateFolder(project.name, directory)
        }
        for (document in documents.values) {
            val state = document.state.value
            val absolute = if (Regex("""^(/|[A-Za-z]:[/\\])""").containsMatchIn(state.path)) state.path
                else projects.firstOrNull { it.name == state.project }?.let {
                    com.hienlh.ppm.state.workspace.absoluteProjectPath(it.path, state.path)
                } ?: continue
            if (affected.any { com.hienlh.ppm.state.workspace.relativeProjectPath(it, absolute) != null }) document.refresh()
        }
    }

    /** The usage readings every chat on the open server shares (use-usage.ts's module cache). */
    var usageCache by mutableStateOf<UsageCache?>(null)
        private set

    /** The status bar's version chip for the open server (upgrade-button.tsx); `internal` for a test's fake server. */
    var upgrade by mutableStateOf<com.hienlh.ppm.state.ServerUpgrade?>(null)
        internal set

    /** The versions Later was chosen for on each server while the app runs, as the web's `sessionStorage` keeps them. */
    private val upgradeDismissals = HashMap<String, MutableSet<String>>()
    var info by mutableStateOf<ServerInfo?>(null)
        internal set

    /** Whether `/api/android/capabilities` reports `enabled: true` on the open server (`use-android-available.ts`). */
    var androidEnabled by mutableStateOf(false)
        internal set

    /** The Android entry surfaces only when `enabled` is true and `minimalMode` is off (`use-android-available.ts`). */
    val androidAvailable: Boolean
        get() = info?.minimalMode != true && androidEnabled

    /** Whether `/api/remote-desktop/capabilities` reports `platformSupported: true` (`use-remote-desktop-available.ts`). */
    var remoteDesktopPlatformSupported by mutableStateOf(true)
        internal set

    /** The Remote Desktop entry surfaces only when `platformSupported` is true and `minimalMode` is off (`use-remote-desktop-available.ts`). */
    val remoteDesktopAvailable: Boolean
        get() = info?.minimalMode != true && remoteDesktopPlatformSupported
    var projects by mutableStateOf(emptyList<ProjectInfo>())
        internal set
    var projectsLoad by mutableStateOf<Load>(Load.Idle)
        private set
    var selectedProject by mutableStateOf<String?>(null)
        internal set

    /** The open project's file-naming framework, which file icons resolve `*.module.ts` by (`project-framework-store.ts`). */
    var iconFramework by mutableStateOf<IconFramework?>(null)
        private set
    private val iconFrameworks = HashMap<String, IconFramework?>()
    private var iconFrameworkFor: String? = null

    var sessions by mutableStateOf(emptyList<SessionInfo>())
        internal set
    var sessionsLoad by mutableStateOf<Load>(Load.Idle)
        private set
    var hasMoreSessions by mutableStateOf(false)
        private set
    var providers by mutableStateOf(emptyList<ProviderInfo>())
        private set
    var tags by mutableStateOf(ProjectTags())
        private set

    /** The drawer's History (the sidebar's on a tablet). Each chat's History has its own. */
    var sidebarList by mutableStateOf<HistoryList?>(null)
        private set

    /** The home screen's list, under the tiles, with a search and tag chip of its own. */
    var homeList by mutableStateOf<HistoryList?>(null)
        private set

    /** The drawer's box searches messages as well as titles. */
    var sidebarSearch by mutableStateOf<ChatSearch?>(null)
        private set

    /** The models each provider offers, shared by the project's chats. */
    var models by mutableStateOf<ModelCatalog?>(null)
        private set
    private var sessionsReadAt: Long? = null
    private var syncsRunning by mutableIntStateOf(0)

    /** The debug launch's session, once opened: every later read of the list leaves the tabs alone. */
    private var debugSessionOpened = false

    /** Reads of the shared page under way; the list says it is syncing while there are any. */
    val syncingSessions: Boolean get() = syncsRunning > 0

    /** Why the last read of the shared page failed, until one succeeds. */
    var sessionsSyncError by mutableStateOf<String?>(null)
        private set

    /** The open server's layout, shared with every browser on it (the web's `usePanelStore`). */
    private var workspace: WorkspaceStore? = null
    private var workspaceCache: WorkspaceCache? = null
    private var workspaceJob: Job? = null

    /** The first project of a server reads the server's copy of its tabs even when this device has one, as a page load does. */
    private var hydratedOnce = false

    /** The project and layout the window panels were last mended for (`reconcileRunKey`). */
    private var reconciledFor: String? = null

    /** The layout as drawn: the store's state, mirrored where Compose sees it. */
    var layout by mutableStateOf<WorkspaceState?>(null)
        private set

    /**
     * The window is at least 768 dp wide (the web's `md`): the desktop layout, where every grid
     * panel is on screen and a tab opens in the focused one. Under it, the phone's.
     */
    var wide by mutableStateOf(false)
        private set

    /** The desktop sidebar folded to its rail (⌘B on the web), shared through the server's UI prefs. */
    var sidebarCollapsed by mutableStateOf(false)
        private set

    /** The desktop sidebar's width in dp, 200–600, shared like [sidebarCollapsed]. */
    var sidebarWidth by mutableIntStateOf(SIDEBAR_DEFAULT_WIDTH)
        private set

    /** Keep the screen on while any turn runs (`keepScreenAwake`), shared like [sidebarCollapsed]. */
    var keepScreenAwake by mutableStateOf(true)
        private set

    /** The look of the floating windows (`explorerSkin`): `windows`, `macos`, or `auto`/null to follow the server's OS. */
    var explorerSkin by mutableStateOf<String?>(null)
        private set

    /** A panel's tabs wrap onto more rows rather than scroll (`tabWrap`), shared like [sidebarCollapsed]. */
    var tabWrap by mutableStateOf(false)
        private set

    /** The MCP servers whose sign-in bar was hidden (`mcpSignInDismissed`), shared like [sidebarCollapsed]. */
    var mcpSignInDismissed by mutableStateOf<List<String>>(emptyList())
        private set

    /** The section the rail last opened (`sidebarActiveTab`), shared like [sidebarCollapsed]; it may be one this app has not. */
    var sidebarTab by mutableStateOf("history")
        private set

    /** The rail's order as it was dragged on the web (`sidebarTabOrder`), shared like [sidebarCollapsed]. */
    var sidebarTabOrder by mutableStateOf<List<String>>(emptyList())
        private set

    /** How Source Control lists changed files (`flat` or `tree`), shared through the server's UI prefs. */
    var gitStatusViewMode by mutableStateOf("flat")
        private set

    /** Desktop word wrap (`wordWrap`), shared through the server's UI prefs. */
    var wordWrap by mutableStateOf(true)
        private set

    /** Phone-local word wrap (`mobileWordWrap`), kept on this device only. */
    var mobileWordWrap by mutableStateOf(prefs.get("mobileWordWrap") != "false")
        private set

    /** Device-local LSP code intelligence toggle (`lspEnabled`). */
    var lspEnabled by mutableStateOf(prefs.get("lspEnabled") != "false")
        private set

    /** Tab bar style (`editorTabStyle`): `default`, `boxed` or `pill`. */
    var editorTabStyle by mutableStateOf("default")
        private set

    /** Inline git blame (`inlineBlame`), shared through the server's UI prefs. */
    var inlineBlame by mutableStateOf(true)
        private set

    /** The editor's gutter change bars (`gutterChanges`), install-wide; off until the tour's question is answered. */
    var gutterChanges by mutableStateOf(false)
        private set

    /**
     * Diffs side by side rather than inline (`diffSideBySide`, on by default), shared through the
     * server's UI prefs: the diff tab and Review changes. A phone is always inline.
     */
    var diffSideBySide by mutableStateOf(true)
        private set

    /** Design Canvas floating windows (`designWindows`), shared through the server's UI prefs. */
    var designWindows by mutableStateOf(false)
        private set

    /** Jira integration toggle (`jiraEnabled`), shared through the server's UI prefs. */
    var jiraEnabled by mutableStateOf(false)
        private set

    /** Per-chat unlinked linked-session map (`unlinkedSessions`), shared through the server's UI prefs. */
    var unlinkedSessions by mutableStateOf<Map<String, Map<String, Long>>>(emptyMap())
        internal set

    /** Remote Desktop stats overlay (`remoteDesktopStatsVisible`), shared through the server's UI prefs. */
    var remoteDesktopStatsVisible by mutableStateOf(false)
        internal set

    /** Remote Desktop warning gate dismissed (`remoteDesktopWarningDismissed`), shared through the server's UI prefs. */
    var remoteDesktopWarningDismissed by mutableStateOf(false)
        internal set

    /** Device-local Remote Desktop quality choice (`remoteDesktopQuality`). */
    var remoteDesktopQuality by mutableStateOf(com.hienlh.ppm.state.remotedesktop.DEFAULT_PRESET_ID)
        internal set

    /** Device-local Remote Desktop view style (`remoteDesktopViewStyle`). */
    var remoteDesktopViewStyle by mutableStateOf(com.hienlh.ppm.state.remotedesktop.DEFAULT_VIEW_STYLE)
        internal set

    /** Device-local Remote Desktop custom scale (`remoteDesktopCustomScale`). */
    var remoteDesktopCustomScale by mutableStateOf(com.hienlh.ppm.state.remotedesktop.DEFAULT_CUSTOM_SCALE)
        internal set

    /** Device-local Remote Desktop custom quality "More" toggle (`remoteDesktopCustomQualityMore`). */
    var remoteDesktopCustomQualityMore by mutableStateOf(false)
        internal set

    /** Device-local Remote Desktop custom bitrate percent (`remoteDesktopCustomQualityPercent`). */
    var remoteDesktopCustomQualityPercent by mutableIntStateOf(com.hienlh.ppm.state.remotedesktop.CUSTOM_QUALITY_DEFAULT_PERCENT)
        internal set

    /** Device-local Remote Desktop custom FPS (`remoteDesktopCustomFps`). */
    var remoteDesktopCustomFps by mutableIntStateOf(com.hienlh.ppm.state.remotedesktop.CUSTOM_FPS_DEFAULT)
        internal set

    /** Device-local Remote Desktop cursor visibility (`remoteDesktopShowCursor`). */
    var remoteDesktopShowCursor by mutableStateOf(true)
        internal set

    /** Device-local Remote Desktop clipboard sync (`remoteDesktopClipboardSync`). */
    var remoteDesktopClipboardSync by mutableStateOf(true)
        internal set

    /** Device-local Remote Desktop preferred H.264 encoder (`remoteDesktopCodec`). */
    var remoteDesktopCodec by mutableStateOf<String?>(null)
        internal set

    /** Requested Settings category when opened via deep link or `openSettings(category)`. */
    var requestedSettingsCategory by mutableStateOf<String?>(null)
        internal set

    fun setGitViewMode(mode: String) {
        if (mode != "flat" && mode != "tree") return
        gitStatusViewMode = mode
        saveUiPrefs()
        val client = api ?: return
        scope.launch {
            quietly { client.putUiPrefs(buildJsonObject { put("gitStatusViewMode", mode) }) }
        }
    }

    fun setKeepScreenAwakePref(enabled: Boolean) {
        keepScreenAwake = enabled
        saveUiPrefs()
        putUiPref { put("keepScreenAwake", enabled) }
    }

    fun setTabWrapPref(enabled: Boolean) {
        tabWrap = enabled
        saveUiPrefs()
        putUiPref { put("tabWrap", enabled) }
    }

    /** Settings → Appearance's switch; saving it also answers the tour's question about the bars (the server settles it). */
    fun setGutterChangesPref(on: Boolean) {
        gutterChanges = on
        saveUiPrefs()
        putUiPref { put("gutterChanges", on) }
    }

    /** Reads `gutterChanges` back from the server, where a tour answer is written; unset keeps it off. */
    fun reloadGutterChangesPref() {
        val api = api ?: return
        scope.launch {
            val saved = quietly { api.uiPrefs() }?.gutterChanges ?: return@launch
            if (saved != gutterChanges) {
                gutterChanges = saved
                saveUiPrefs()
            }
        }
    }

    fun setWordWrapPref(enabled: Boolean) {
        if (wide) {
            wordWrap = enabled
            saveUiPrefs()
            putUiPref { put("wordWrap", enabled) }
        } else {
            mobileWordWrap = enabled
            prefs.put("mobileWordWrap", enabled.toString())
        }
    }

    fun toggleDiffSideBySide() {
        diffSideBySide = !diffSideBySide
        saveUiPrefs()
        putUiPref { put("diffSideBySide", diffSideBySide) }
    }

    fun setEditorTabStylePref(style: String) {
        if (style !in setOf("default", "boxed", "pill")) return
        editorTabStyle = style
        saveUiPrefs()
        putUiPref { put("editorTabStyle", style) }
    }

    fun setExplorerSkinPref(skin: String) {
        if (skin !in setOf("auto", "windows", "macos")) return
        explorerSkin = skin
        saveUiPrefs()
        putUiPref { put("explorerSkin", skin) }
    }

    fun setLspEnabledPref(enabled: Boolean) {
        lspEnabled = enabled
        prefs.put("lspEnabled", enabled.toString())
    }

    fun setDesignWindowsPref(enabled: Boolean) {
        designWindows = enabled
        saveUiPrefs()
        putUiPref { put("designWindows", enabled) }
    }

    fun setJiraEnabledPref(enabled: Boolean) {
        jiraEnabled = enabled
        saveUiPrefs()
        putUiPref { put("jiraEnabled", enabled) }
        // Turned off while its section is open: Explorer instead, as the web does.
        if (!enabled && sidebarTab == "jira") openSidebarSection("explorer")
    }

    fun setRemoteDesktopWarningDismissedPref(dismissed: Boolean) {
        remoteDesktopWarningDismissed = dismissed
        saveUiPrefs()
        putUiPref { put("remoteDesktopWarningDismissed", dismissed) }
    }

    fun setRemoteDesktopStatsVisiblePref(visible: Boolean) {
        remoteDesktopStatsVisible = visible
        saveUiPrefs()
        putUiPref { put("remoteDesktopStatsVisible", visible) }
    }

    fun setRemoteDesktopQualityPref(choice: String) {
        val parsed = com.hienlh.ppm.state.remotedesktop.parseQualityChoice(choice)
        remoteDesktopQuality = parsed
        prefs.put("remoteDesktopQuality.$serverKey", parsed)
    }

    fun setRemoteDesktopViewStylePref(style: String) {
        val parsed = com.hienlh.ppm.state.remotedesktop.parseViewStyle(style)
        remoteDesktopViewStyle = parsed
        prefs.put("remoteDesktopViewStyle.$serverKey", parsed)
    }

    fun setRemoteDesktopCustomScalePref(scale: Double) {
        val clamped = com.hienlh.ppm.state.remotedesktop.clampCustomScale(scale)
        remoteDesktopCustomScale = clamped
        prefs.put("remoteDesktopCustomScale.$serverKey", clamped.toString())
    }

    fun setRemoteDesktopCustomQualityPref(
        percent: Int,
        fps: Int,
        more: Boolean = remoteDesktopCustomQualityMore,
    ) {
        val p = com.hienlh.ppm.state.remotedesktop.clampCustomQualityPercent(percent, more)
        val f = com.hienlh.ppm.state.remotedesktop.clampCustomFps(fps)
        remoteDesktopCustomQualityMore = more
        remoteDesktopCustomQualityPercent = p
        remoteDesktopCustomFps = f
        prefs.put("remoteDesktopCustomQualityMore.$serverKey", more.toString())
        prefs.put("remoteDesktopCustomQualityPercent.$serverKey", p.toString())
        prefs.put("remoteDesktopCustomFps.$serverKey", f.toString())
    }

    fun setRemoteDesktopShowCursorPref(show: Boolean) {
        remoteDesktopShowCursor = show
        prefs.put("remoteDesktopShowCursor.$serverKey", show.toString())
    }

    fun setRemoteDesktopClipboardSyncPref(enabled: Boolean) {
        remoteDesktopClipboardSync = enabled
        prefs.put("remoteDesktopClipboardSync.$serverKey", enabled.toString())
    }

    fun setRemoteDesktopCodecPref(encoder: String?) {
        val clean = encoder?.takeIf { it.isNotEmpty() }
        remoteDesktopCodec = clean
        prefs.put("remoteDesktopCodec.$serverKey", clean)
    }

    fun updateDeviceNameLocal(name: String) {
        val current = info ?: return
        info = current.copy(deviceName = name.trim().ifEmpty { null })
    }

    /**
     * Immediately updates the saved password token for the active server after changing the
     * password in Settings → General so the current session stays signed in without 401.
     */
    fun updateActiveServerPassword(newToken: String) {
        val trimmed = newToken.trim()
        if (trimmed.isEmpty()) return
        store.selected?.let { server -> store.updateToken(trimmed, server) }
        api?.let { currentApi -> api = ApiClient(currentApi.baseUrl, trimmed) }
    }

    /** Invalidates file indexes after File Filters settings are updated. */
    fun invalidateFileIndexes(projectName: String? = null) {
        if (projectName != null) {
            fileIndex?.markStale(projectName)
        } else {
            for (proj in projects) fileIndex?.markStale(proj.name)
        }
        fileTree?.reconnected()
    }

    /** Reloads keyboard shortcuts from `/api/settings/keybindings`. */
    fun reloadKeybindings() {
        val client = api ?: return
        scope.launch {
            quietly { client.keybindings() }?.let { keyBindings = KeyBindings(it) }
        }
    }

    /** Picks an imported custom theme (`style = "custom"` + `customThemeId`). */
    suspend fun pickCustomTheme(customThemeId: String, isDarkBase: Boolean) {
        val previous = themeSelection
        themeSelection = ThemeSelection("custom", if (isDarkBase) "dark" else "light", customThemeId)
        if (!report { api?.setTheme(themeSelection) }) themeSelection = previous
    }

    /** The built-in sections, then Jira when it is on (`getAvailableTabs`). */
    private val availableSidebarSections: List<String>
        get() = if (jiraEnabled) SIDEBAR_SECTIONS + "jira" else SIDEBAR_SECTIONS

    /** This app's sections in the rail's order: those dragged into place first, then the rest as the web lists them (`resolveTabOrder`). */
    val sidebarSections: List<String>
        get() {
            val available = availableSidebarSections
            val ordered = (sidebarTabOrder.filter { it in available } + available).distinct()
            return if (info?.minimalMode == true) ordered.filter { it in MINIMAL_SIDEBAR_SECTIONS } else ordered
        }

    /** The section shown: the one opened last, or the rail's first when that is one this app has not (the drawer's fallback). */
    val sidebarSection: String
        get() = sidebarTab
            .takeIf { (it in availableSidebarSections || it.startsWith("ext:")) && (info?.minimalMode != true || it in MINIMAL_SIDEBAR_SECTIONS) }
            ?: sidebarSections.first()

    /** The open server's OS (`/api/system/host`), kept per server so the first frame already knows it. */
    var hostPlatform by mutableStateOf<String?>(null)
        private set

    /** The skin every window wears (use-explorer-skin.ts): the one picked, else Windows' on a Windows server, macOS' on any other. */
    val windowsSkin: Boolean
        get() = com.hienlh.ppm.state.osexplorer.explorerSkinIsWindows(explorerSkin, hostPlatform)

    /** The open server's floating windows (the web's `useWindowStore`), drawn on a tablet only. */
    var windows by mutableStateOf<WindowStore?>(null)
        private set

    /** Their state as drawn. */
    var windowState by mutableStateOf(WindowsState())
        private set

    /**
     * Where the dock sits: `bottom`, `left` or `right`. The web keeps it in the browser (the
     * server drops the key), so each device has its own.
     */
    var dockPosition by mutableStateOf(prefs.get("dockPosition")?.takeIf { it in DOCK_POSITIONS } ?: "bottom")
        private set

    /** The chat behind each chat tab in memory, by tab id; a tab whose chat has not started has none. */
    private val chats = mutableStateMapOf<String, ChatTab>()

    /** The review behind each session-review tab in memory, by tab id. */
    private val reviews = mutableStateMapOf<String, ReviewTab>()

    /** The group chat behind each group tab in memory, by tab id. */
    private val groupChats = mutableStateMapOf<String, GroupTab>()

    /** Each session's turns: an open chat publishes its own, a Review tab reads them (`useSessionTurnsStore`). */
    val sessionTurns = SessionTurnsStore()

    /** Revisions of what other devices changed, which open screens read again on (LiveSync.kt). */
    internal val liveSync = LiveSyncState()

    /** "Show in chat" on its way to the chat showing its session (`useChatJumpStore`). */
    var chatJump by mutableStateOf<ChatJumpRequest?>(null)
        private set

    /** The chat tabs with no session yet: their preparation, provider, account and first message. */
    internal val newChats = NewChats(this)

    /** Fork, Edit and the version switcher on the chats' messages. */
    internal val edits = MessageEdits(this)

    /** This device's copy of what a new chat is resolved from, for the open server. */
    internal val newChatCache: NewChatCache get() = NewChatCache(prefs, serverKey)

    /** The session of the tab in front when the chats were last synced, to tell when another one comes forward. */
    private var shownSession: String? = null

    /** Tabs asked for while the selected project's layout was still on its way; opened once it is shown. */
    private val waitingForWorkspace = mutableListOf<(WorkspaceStore) -> Unit>()

    /** The project sheet's order: `recent`, `priority` (the server's own) or `name`, shared through the server's UI prefs. */
    var projectSortMode by mutableStateOf("priority")
        private set

    /** When each project was last opened (epoch ms), for the Recent order and the age beside each. */
    var projectOpenedAt by mutableStateOf(emptyMap<String, Long>())
        private set

    /** Device-local `logsBadge` preference (`use-logs-badge.ts`): when false, `/api/logs/issues/summary` is never called. */
    var logsBadge by mutableStateOf(true)

    init {
        restoreUiPrefs()
    }

    /** The theme picked on any device (`/api/settings/theme`). */
    var themeSelection by mutableStateOf(ThemeSelection("aurora", "system"))
        private set
    var customThemes by mutableStateOf(emptyList<PpmTheme>())
        private set
    var systemIsDark by mutableStateOf(true)
    val theme: PpmTheme
        get() = PpmTheme.resolve(
            style = themeSelection.style,
            mode = themeSelection.mode,
            systemIsDark = systemIsDark,
            customThemes = customThemes,
            customThemeId = themeSelection.customThemeId,
        )

    fun updateCustomThemes(items: List<com.hienlh.ppm.state.settings.ImportedThemeItem>) {
        customThemes = items.map { item ->
            PpmTheme.fromCustom(
                id = item.id,
                name = item.name,
                mode = item.mode,
                tokenMap = item.tokens,
                mobileOverrides = item.mobileOverrides,
                swatchStrings = item.swatch,
            )
        }
    }

    /** The app-wide socket of the open server: running and unread sessions. */
    var events by mutableStateOf<GlobalEvents?>(null)
        private set

    /** The extension host socket (`/ws/extensions`) and state store. */
    var extensions by mutableStateOf<com.hienlh.ppm.state.extensions.ExtensionClient?>(null)
        private set

    /** Likely PPM bug count from `/api/logs/issues/summary` for the Logs footer badge. */
    var logsLikelyBugs by mutableIntStateOf(0)

    val showLogsBadgeDot: Boolean
        get() = logsBadge && info?.minimalMode == false && logsLikelyBugs > 0

    private var logsBadgePollJob: Job? = null
    internal var openLogsWindowCount = 0
    private var logsReadyOff: (() -> Unit)? = null
    private var logsBoundEvents: GlobalEvents? = null
    internal var logsTailSender: ((String) -> Unit)? = null
    internal var reportClipboardCopy: ((String) -> Boolean)? = null
    internal var remoteDesktopSender: ((JsonElement) -> Unit)? = null

    private fun sendLogsTailFrame(frame: String) {
        val custom = logsTailSender
        if (custom != null) {
            custom(frame)
        } else {
            events?.sendIfOpen(frame)
        }
    }

    /** Keeps the server pushing new lines while any Logs view is mounted (`retainLogsTail`, `use-logs-feed.ts:35-46`). */
    fun retainLogsTail(): () -> Unit {
        val ev = events
        if (logsBoundEvents !== ev) {
            logsReadyOff?.invoke()
            logsReadyOff = null
            logsBoundEvents = ev
            if (openLogsWindowCount > 0) {
                val subscribe = { sendLogsTailFrame("""{"type":"logs:subscribe"}""") }
                subscribe()
                logsReadyOff = ev?.onGlobalReady(subscribe)
            }
        }
        if (openLogsWindowCount++ == 0) {
            val subscribe = { sendLogsTailFrame("""{"type":"logs:subscribe"}""") }
            subscribe()
            logsReadyOff = ev?.onGlobalReady(subscribe)
        }
        var released = false
        return {
            if (!released) {
                released = true
                openLogsWindowCount = (openLogsWindowCount - 1).coerceAtLeast(0)
                if (openLogsWindowCount == 0) {
                    logsReadyOff?.invoke()
                    logsReadyOff = null
                    sendLogsTailFrame("""{"type":"logs:unsubscribe"}""")
                }
            }
        }
    }

    var sharedMetricsSnapshot by mutableStateOf<com.hienlh.ppm.state.sysmon.MetricsSnapshot?>(null)
    var sharedMetricsHistory by mutableStateOf<List<com.hienlh.ppm.state.sysmon.MetricsHistoryPoint>>(emptyList())
    var sharedMetricsTickCount by mutableIntStateOf(0)
    var sharedMetricsConnected by mutableStateOf(false)
    internal val resourceTierTracker = com.hienlh.ppm.state.sysmon.ResourceStreamTierTracker()
    private var resourceStreamJob: Job? = null

    fun retainResourceMonitor(wantProcesses: Boolean, reconnectDelayMs: Long = 5_000L): () -> Unit {
        applyResourceReconcile(resourceTierTracker.retain(wantProcesses), reconnectDelayMs)
        var released = false
        return {
            if (!released) {
                released = true
                applyResourceReconcile(resourceTierTracker.release(wantProcesses), reconnectDelayMs)
            }
        }
    }

    internal fun applyResourceReconcile(
        action: com.hienlh.ppm.state.sysmon.StreamReconcileAction,
        reconnectDelayMs: Long = 5_000L,
    ) {
        when (action) {
            com.hienlh.ppm.state.sysmon.StreamReconcileAction.NoOp -> {}
            com.hienlh.ppm.state.sysmon.StreamReconcileAction.Disconnect -> {
                resourceStreamJob?.cancel()
                resourceStreamJob = null
                sharedMetricsConnected = false
            }
            is com.hienlh.ppm.state.sysmon.StreamReconcileAction.Connect -> {
                resourceStreamJob?.cancel()
                val currentApi = api ?: return
                val wantProc = action.wantProcesses
                sharedMetricsTickCount = 0
                resourceStreamJob = scope.launch {
                    while (isActive) {
                        sharedMetricsTickCount = 0
                        try {
                            currentApi.fullResourceSamples(processes = wantProc).collect { sampleJson ->
                                val parsed = com.hienlh.ppm.state.sysmon.MetricsSnapshot.from(sampleJson) ?: return@collect
                                sharedMetricsSnapshot = parsed
                                sharedMetricsHistory = com.hienlh.ppm.state.sysmon.SysMonRingBuffer.appendSnapshot(sharedMetricsHistory, parsed)
                                sharedMetricsTickCount += 1
                                sharedMetricsConnected = true
                            }
                            sharedMetricsConnected = false
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: PpmError.Unauthorized) {
                            sharedMetricsConnected = false
                            return@launch
                        } catch (_: Exception) {
                            sharedMetricsConnected = false
                        }
                        delay(reconnectDelayMs)
                    }
                }
            }
        }
    }

    fun updateLogsBadge(enabled: Boolean, pollIntervalMs: Long = 10 * 60_000L) {
        logsBadge = enabled
        prefs.put("logsBadge.$serverKey", enabled.toString())
        reconcileLogsBadge(pollIntervalMs)
    }

    internal fun reconcileLogsBadge(pollIntervalMs: Long = 10 * 60_000L) {
        val currentApi = api
        if (currentApi == null || !logsBadge || info?.minimalMode != false) {
            logsBadgePollJob?.cancel()
            logsBadgePollJob = null
            logsLikelyBugs = 0
            return
        }
        refreshLogsIssuesSummary()
        if (logsBadgePollJob?.isActive != true) {
            logsBadgePollJob = scope.launch {
                while (isActive) {
                    delay(pollIntervalMs)
                    if (windowVisible && logsBadge && info?.minimalMode == false) {
                        refreshLogsIssuesSummary()
                    }
                }
            }
        }
    }

    fun refreshLogsIssuesSummary() {
        if (!logsBadge || info?.minimalMode != false) {
            logsLikelyBugs = 0
            return
        }
        val currentApi = api ?: return
        scope.launch {
            val summaryJson = quietly { currentApi.logsIssuesSummary() } ?: return@launch
            if (this@AppModel.api !== currentApi || !logsBadge || info?.minimalMode != false) return@launch
            logsLikelyBugs = com.hienlh.ppm.state.logs.LogIssuesSummary.from(summaryJson).likelyBugs
        }
    }

    /** Each project's branch and how far it is from its upstream, for the tablet's status bar (the web's git `meta`). */
    val gitBranches = mutableStateMapOf<String, GitBranchStatus>()

    /** Discovered git repositories per project (`GET /git/repos`). */
    val gitDiscovery = mutableStateMapOf<String, com.hienlh.ppm.client.GitRepos>()

    /** User-chosen repo path per project for multi-repo projects (`ppm-git-repo-choice`). */
    private val gitRepoChoices = com.hienlh.ppm.state.git.GitRepoChoices(prefs)
    val gitChosenRepoPath = mutableStateMapOf<String, String>()

    /** Device-local reviewed blob map per `(project, base, head)` (`ppm:branch-review:*`). */
    val branchReviewStore = com.hienlh.ppm.state.git.BranchReviewStore(prefs)

    /** Most recent `GET /git/changes` payload per project. */
    val gitChangesCache = mutableStateMapOf<String, com.hienlh.ppm.client.GitChanges>()

    /** Explorer file git status letter (`M`, `A`, `D`, `R`, `C`, `U`) per project. */
    val gitFileStatuses = mutableStateMapOf<String, Map<String, String>>()

    /** Explorer folder aggregate git status letter per project. */
    val gitFolderStatuses = mutableStateMapOf<String, Map<String, String>>()

    /** Shared commit draft sync across devices (`GET / PUT /git/commit-draft` + `/ws/global`). */
    val commitDrafts = com.hienlh.ppm.state.git.CommitDraftSync(scope)

    /** Project whose BranchPicker modal is currently open (`StatusBar` or `GitBranchRow`). */
    var branchPickerProject by mutableStateOf<String?>(null)
        private set

    /** Mounted `GitStatusPanel` count per project so `watchGit` stands down while Source Control is open. */
    val gitStatusPanelOpenCount = mutableStateMapOf<String, Int>()

    fun onGitStatusPanelMounted(project: String) {
        gitStatusPanelOpenCount[project] = (gitStatusPanelOpenCount[project] ?: 0) + 1
    }

    fun onGitStatusPanelUnmounted(project: String) {
        val next = (gitStatusPanelOpenCount[project] ?: 0) - 1
        if (next <= 0) gitStatusPanelOpenCount.remove(project) else gitStatusPanelOpenCount[project] = next
    }

    fun openBranchPicker(project: String) {
        branchPickerProject = project
    }

    fun closeBranchPicker() {
        branchPickerProject = null
    }

    /** The projects PPM just wrote to a repository of (`git:changed`). */
    private val gitChanged = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val gitChangedEvents: kotlinx.coroutines.flow.SharedFlow<String> get() = gitChanged
    /** `file:changed` as (project, path): a viewer that reads a file without a document (the xlsx preview) follows its own file. */
    private val fileChanged = MutableSharedFlow<Pair<String, String?>>(extraBufferCapacity = 16)
    val fileChangedEvents: kotlinx.coroutines.flow.SharedFlow<Pair<String, String?>> get() = fileChanged

    /** Whether the app is in front (the web's `document.hidden`, inverted). */
    private var _windowVisible by mutableStateOf(true)
    var windowVisible: Boolean
        get() = _windowVisible
        set(value) {
            _windowVisible = value
            // In the background Android may end the process at any time: the layout goes out now,
            // and so does each draft still waiting out its second.
            if (value) {
                activeSessionId?.let { events?.seen(it) }
                terminals.values.forEach { it.controller.foreground() }
            } else {
                workspaceCache?.flush()
                for (chat in chats.values) chat.flushDraft()
            }
        }

    var showSignIn by mutableStateOf(false)

    private var health = HealthWatch()

    /** The connection-lost overlay is up: the server has not answered for 15 s. */
    var connectionLost by mutableStateOf(false)
        private set

    /** The open server's named-tunnel hostname, read while it answered; in memory only, as on the web. */
    var namedHostname by mutableStateOf<String?>(null)
        private set

    /** The open server's address on its own network, kept per server like the web's `ppm-last-local-url`. */
    var localUrl by mutableStateOf<String?>(null)
        private set

    private val history = NavHistory()
    private var historyServer: String? = null

    /** Back has a tab to return to: the browser history `useUrlSync` builds, on this server. */
    var canGoBack by mutableStateOf(false)
        private set

    /** A link that came before its server's projects were read, with that server's key: opened once they are. */
    private var pendingLink: Pair<String, UrlState>? = null

    /** Settings' Add server was chosen: the connect screen shows over everything until it is done or cancelled. */
    var addingServer by mutableStateOf(false)

    /** The toasts up, the newest first as sonner keeps them, those on their way out included. */
    val toasts = mutableStateListOf<AppToast>()
    private var toastCount = 0L

    /** `toast()`: [message] in front of the others. */
    fun toast(message: ErrorToastMessage) {
        toasts.add(0, AppToast(++toastCount, message))
        AppLogLines.toast(message)
    }

    /** `toast.dismiss(id)`: [message] goes, if it is still up. */
    fun dismissToast(message: ErrorToastMessage) {
        toasts.firstOrNull { it.message === message }?.dismiss()
    }

    /** A toast raised again under its id: [next] in place of [shown], while that is up. False once it is not. */
    fun replaceToast(shown: ErrorToastMessage, next: ErrorToastMessage): Boolean {
        val toast = toasts.firstOrNull { it.message === shown && !it.dismissed } ?: return false
        toast.message = next
        return true
    }

    /** `removeToast`: [toast] has left, and comes off the stack. */
    fun forgetToast(toast: AppToast) {
        toasts.remove(toast)
    }

    init {
        // A debug launch's toasts, raised once the app is up and 450 ms apart, as the probe raises the web's.
        debug?.toasts?.takeIf { it.isNotEmpty() }?.let { raised ->
            scope.launch {
                delay(1_000)
                raised.forEachIndexed { i, message ->
                    if (i > 0) delay(450)
                    toast(message)
                }
            }
        }
    }

    /** Offers this app's newer releases, and installs one the user takes. */
    private val updater = AppUpdater(context, this)

    /** An error toast that is only its title. */
    fun actionError(title: String) = toast(ErrorToastMessage(title))

    /** The HTML previews on screen, which the AI's `open_preview` asks how its page rendered. */
    val previews = PreviewLoads()

    /** The session whose Delete was chosen, until the user confirms. */
    var confirmDeleteSession by mutableStateOf<SessionInfo?>(null)

    /** The image lightbox, while it is open (`useImageOverlay`). */
    var lightbox by mutableStateOf<LightboxRequest?>(null)

    /** The agent session the phone's sheet shows (`useAgentSessionSheetStore`). */
    var agentSheet by mutableStateOf<AgentSessionPayload?>(null)
        private set

    /** What the chat held of each agent session on screen, shown until the hub streams it. */
    var agentFallbacks by mutableStateOf(AgentSessionFallbacks())
        private set

    /** Something drawn over the whole app as CSS's `fixed inset-0` draws it: a usage panel gone fullscreen. */
    var screenOverlay by mutableStateOf<(@Composable () -> Unit)?>(null)

    /** The background command whose output the panel shows (`panelShellId`), with the chat tab that lists it. */
    var backgroundOutput by mutableStateOf<BackgroundOutputRequest?>(null)
        private set

    /** What that panel's box holds: one for the whole window, as the web mounts one panel. */
    val backgroundOutputShown = BackgroundOutput()

    /** The MCP sign-in open over the app (`McpSignInDialog`), and the flow it drives. */
    var mcpSignIn by mutableStateOf<McpSignIn?>(null)
        private set

    /** What the command palette opened with while it is open; null while it is closed. */
    var paletteQuery by mutableStateOf<String?>(null)
        private set

    /** Whether the Cloud & Share popover (tablet rail) or bottom sheet (phone) is open. */
    var cloudShareOpen by mutableStateOf(false)

    /** Project name when the New Design dialog is requested from the command palette; null while closed. */
    var newDesignProject by mutableStateOf<String?>(null)

    /** Whether the global Compare Files picker dialog is open. */
    var comparePickerOpen by mutableStateOf(false)

    /** One-shot request to open the phone navigation drawer on a given section id. */
    var mobileDrawerOpenRequest by mutableStateOf<String?>(null)

    /** Non-null when Learn PPM is open (`false` = guided tour, `true` = quick orientation). */
    var learnPpmMode by mutableStateOf<Boolean?>(null)

    /** The keyboard shortcuts in force: the server's overrides over the web's defaults. */
    var keyBindings by mutableStateOf(KeyBindings())
        private set

    /** Alt+↑ / Alt+↓ for a chat, by its tab's id: -1 for the user's previous message, 1 for the next. */
    val chatJumps = MutableSharedFlow<Pair<String, Int>>(extraBufferCapacity = 8)

    /** The chat mic, one for every message box. */
    val voice = VoiceInput(context, scope, api = { api }, toast = ::toast)

    /** What the mic listens with, kept on this device (`voiceEngine`): Settings → Voice picks it. */
    var voiceEngine by mutableStateOf(VoiceEngine.of(prefs.get("voiceEngine")))
        private set

    /** Mod+Shift+V and the palette's Voice Input (`toggle-voice-input`), by the tab whose box should answer. */
    val voiceToggles = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** The tag whose Delete was chosen in Manage tags, until the user confirms. */
    var confirmDeleteTag by mutableStateOf<SessionTag?>(null)

    /** Delete old sessions was chosen, until the user says how old. */
    var askDeleteOld by mutableStateOf(false)

    var deletingOld by mutableStateOf(false)
        private set

    val serverName: String get() = if (debug != null) "Debug server" else store.selected?.name ?: "PPM"

    /** Whether there is a server to show; otherwise the app asks for one. */
    val hasServer: Boolean get() = debug != null || store.servers.value.isNotEmpty()
    val currentProject: ProjectInfo? get() = projects.firstOrNull { it.name == selectedProject }

    /** The projects in the order the project sheet shows them. */
    val sortedProjects: List<ProjectInfo>
        get() = when (projectSortMode) {
            // Never-opened projects keep the server's order, after the opened ones.
            "recent" -> projects.sortedByDescending { projectOpenedAt[it.name] ?: Long.MIN_VALUE }
            "name" -> java.text.Collator.getInstance().let { collator -> projects.sortedWith { a, b -> collator.compare(a.name, b.name) } }
            else -> projects
        }

    /** What is typed in the home list's search box. */
    var sidebarQuery: String
        get() = sidebarList?.query ?: ""
        set(value) {
            sidebarList?.query = value
            sidebarSearch?.query = value
        }

    // Servers

    /** Checks the address and the password before anything is saved. */
    suspend fun addServer(url: HttpUrl, password: String) {
        val api = ApiClient(url, password)
        val info = api.info()
        api.checkAuth()
        store.add(info.deviceName ?: url.host, url.toString(), password)
        // Added from the sign-in screen: the new server has its password, so that screen goes.
        showSignIn = false
    }

    /** Opens another saved server; the sign-in screen comes back only if that one has no password either. */
    fun switchServer(server: ServerProfile) {
        showSignIn = false
        store.select(server)
    }

    suspend fun signInAgain(password: String) {
        val server = store.selected ?: return
        val url = ApiClient.normalizedServerUrl(server.url) ?: throw PpmError.InvalidUrl()
        ApiClient(url, password).checkAuth()
        store.updateToken(password, server)
        showSignIn = false
        opening?.cancel()
        opening = scope.launch { openSelectedServer() }
    }

    fun remove(server: ServerProfile) = store.remove(server)

    /**
     * Forgets the open server's password and opens it again, which closes its sockets and, with no
     * password left, asks for one (`showSignIn`). The server keeps its single token; nothing to revoke.
     */
    fun logOut() {
        val server = store.selected ?: return
        store.forgetToken(server)
        opening?.cancel()
        opening = scope.launch { openSelectedServer() }
    }

    private var opened: String? = null
    private var opening: Job? = null

    /**
     * Opens the selected server unless it is already the open one: the activity asks each time
     * it is created, and this model outlives it.
     */
    fun showSelectedServer() {
        val key = if (debug != null) "debug" else store.selectedId.value
        if (key == opened) return
        opened = key
        opening?.cancel()
        opening = scope.launch { openSelectedServer() }
    }

    /** Called whenever the selected server changes, and again once its password is given. */
    private suspend fun openSelectedServer() {
        projectListRevision++
        projectRefresh?.cancel()
        if (serverKey != historyServer) {
            historyServer = serverKey
            history.clear()
            canGoBack = false
        }
        closeWorkspace()
        logsBadgePollJob?.cancel()
        logsBadgePollJob = null
        logsLikelyBugs = 0
        resourceStreamJob?.cancel()
        resourceStreamJob = null
        resourceTierTracker.reset()
        sharedMetricsSnapshot = null
        sharedMetricsHistory = emptyList()
        sharedMetricsTickCount = 0
        sharedMetricsConnected = false
        events?.stop()
        events = null
        extensions?.stop()
        extensions = null
        api = null
        slashItems = null
        fileIndex = null
        fileTree?.close()
        fileTree = null
        fileMutations = null
        hostFilesystem?.close()
        hostFilesystem = null
        explorerPreferences = null
        hostPickerPreferences = null
        usageCache = null
        upgrade?.close()
        upgrade = null
        info = null
        androidEnabled = false
        remoteDesktopPlatformSupported = true
        projects = emptyList()
        projectsLoad = Load.Idle
        selectedProject = null
        iconFrameworkFor = null
        iconFramework = null
        gitBranches.clear()
        keyBindings = KeyBindings()
        health = HealthWatch()
        connectionLost = false
        namedHostname = null
        resetProjectState()
        restoreUiPrefs()
        localUrl = prefs.get("localUrl.$serverKey")
        hostPlatform = prefs.get("hostPlatform.$serverKey")
        val api = if (debug != null) {
            ApiClient(debug.url, debug.password)
        } else {
            val server = store.selected ?: return
            val url = ApiClient.normalizedServerUrl(server.url) ?: return
            val token = store.token(server) ?: run {
                showSignIn = true
                return
            }
            ApiClient(url, token)
        }
        this.api = api
        AppLogLines.opened(api)
        slashItems = SlashItemsStore(scope)
        fileIndex = FileIndexStore(api, scope)
        fileMutations = com.hienlh.ppm.state.files.FileMutations(api)
        hostFilesystem = com.hienlh.ppm.state.osexplorer.HostFilesystemStore(api, scope)
        explorerPreferences = com.hienlh.ppm.state.osexplorer.ExplorerPreferences(prefs, "explorer.$serverKey.")
        hostPickerPreferences = com.hienlh.ppm.state.osexplorer.HostPickerPreferences(prefs, "explorer.$serverKey.ppm-recent-paths")
        fileTree = com.hienlh.ppm.state.files.ProjectFileTree(
            com.hienlh.ppm.state.files.FileTreeBackend.api(api), scope,
            com.hienlh.ppm.state.files.FileTreePersistence(prefs, "files.$serverKey."),
        )
        usageCache = UsageCache()
        upgrade = com.hienlh.ppm.state.ServerUpgrade(
            api,
            scope,
            upgradeDismissals.getOrPut(serverKey) { mutableSetOf() },
            notify = { toast(ErrorToastMessage(it.text, kind = if (it.error) ErrorToastMessage.Kind.ERROR else ErrorToastMessage.Kind.INFO)) },
            // Once the new server answers, everything is read from it again, as the web reloads the page.
            reopen = ::reopenServer,
        )
        openWorkspace(api)
        startEvents(api)
        startExtensions(api)
        scope.launch { readAddresses(api) }
        coroutineScope {
            launch { loadTheme() }
            loadProjects()
        }
    }

    /**
     * Where the connection-lost overlay can send the user: the named tunnel's hostname (the web
     * reads it at start) and the server's own-network address (the web when its share popover
     * opens; here at start, so the overlay can offer it the first time it is needed).
     */
    private suspend fun readAddresses(api: ApiClient) {
        coroutineScope {
            launch { quietly { api.namedTunnelHostname() }?.let { if (this@AppModel.api === api) namedHostname = it } }
            launch {
                val platform = quietly { api.hostPlatform() } ?: return@launch
                if (this@AppModel.api !== api) return@launch
                hostPlatform = platform
                prefs.put("hostPlatform.$serverKey", platform)
            }
            launch {
                val url = quietly { api.localUrl() }?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return@launch
                if (this@AppModel.api !== api) return@launch
                localUrl = url
                prefs.put("localUrl.$serverKey", url)
            }
            launch {
                val capsJson = quietly { api.androidCapabilities() } ?: return@launch
                if (this@AppModel.api !== api) return@launch
                androidEnabled = com.hienlh.ppm.state.androiddevice.parseAndroidCapabilities(capsJson).enabled
            }
            launch {
                val capsJson = quietly { api.remoteDesktopCapabilities() }
                if (this@AppModel.api !== api) return@launch
                remoteDesktopPlatformSupported = if (capsJson != null) {
                    com.hienlh.ppm.state.remotedesktop.RemoteDesktopCapabilities.from(capsJson).platformSupported
                } else {
                    false
                }
            }
        }
    }

    /**
     * use-server-reload.ts while the app is in front: [api]'s health every 10 s, every 2 s while
     * it is down. When it answers after an outage the server is opened again, as the web
     * reloads the page.
     */
    suspend fun watchHealth(api: ApiClient) {
        while (true) {
            delay(health.nextDelayMs)
            val back = health.record(checkHealth(api), System.currentTimeMillis())
            AppLogLines.connection(api, was = connectionLost, now = health.overlay)
            connectionLost = health.overlay
            if (back) {
                reopenServer()
                return
            }
        }
    }

    /** The overlay's Retry: an answer opens the server again, anything else leaves the overlay up. */
    suspend fun retryConnection() {
        val api = api ?: return
        if (health.retry(checkHealth(api))) {
            connectionLost = false
            reopenServer()
        }
    }

    private suspend fun checkHealth(api: ApiClient): HealthCheck = try {
        api.health()
        HealthCheck.Up
    } catch (error: CancellationException) {
        throw error
    } catch (_: PpmError.Unreachable) {
        HealthCheck.NoAnswer
    } catch (_: Exception) {
        HealthCheck.Error
    }

    /** Everything read from the server again, as after the web's `location.reload()`. */
    private fun reopenServer() {
        opening?.cancel()
        opening = scope.launch { openSelectedServer() }
    }

    /**
     * The overlay's "Open on local network": the same server at its own-network address, kept as
     * a server of its own beside this one (the web opens that address as another page).
     */
    fun openLocalNetwork() {
        val url = localUrl ?: return
        val current = store.selected ?: return
        val token = store.token(current) ?: return
        val target = ApiClient.normalizedServerUrl(url)
        val known = store.servers.value.firstOrNull { ApiClient.normalizedServerUrl(it.url) == target }
        if (known != null) store.select(known) else store.add("${current.name} (local network)", url, token)
    }

    private var projectListRevision = 0L
    private var projectRefresh: Job? = null

    var addingProject by mutableStateOf(false)
    var projectActions by mutableStateOf<ProjectInfo?>(null)
    var projectActionsCanReorder by mutableStateOf(true)
    var phoneProjectRename by mutableStateOf<String?>(null)

    fun projectAppearanceUpdated(client: ApiClient, project: ProjectInfo) {
        if (api !== client) return
        projectListRevision++
        projects = projects.map { if (it.name == project.name) project else it }
        if (projectActions?.name == project.name) projectActions = project
    }

    suspend fun renameRegisteredProject(client: ApiClient, project: ProjectInfo, name: String) {
        check(api === client) { "The connected server changed." }
        val renamed = client.renameProject(project.name, name)
        if (api !== client) return
        val wasActive = selectedProject == project.name
        projectListRevision++
        projects = projects.map { if (it.name == project.name) renamed else it }
        forgetProjectRegistration(project.name)
        projectActions = null
        if (wasActive) openProject(renamed.name)
    }

    suspend fun removeRegisteredProject(client: ApiClient, project: ProjectInfo) {
        check(api === client) { "The connected server changed." }
        client.removeProject(project.name)
        if (api !== client) return
        val wasActive = selectedProject == project.name
        projectListRevision++
        projects = projects.filterNot { it.name == project.name }
        forgetProjectRegistration(project.name)
        projectActions = null
        if (wasActive) projects.firstOrNull()?.let { openProject(it.name) }
    }

    private fun forgetProjectRegistration(name: String) {
        workspace?.forgetProject(name)
        workspaceCache?.forget(name)
        newChats.preparationCache.clear()
        newChatCache.forgetProject(name)
        com.hienlh.ppm.state.files.FileTreePersistence(prefs, "files.$serverKey.").forget(name)
        iconFrameworks.remove("$serverKey\u0000$name")
        projectOpenedAt = projectOpenedAt - name
        saveUiPrefs()
        if (selectedProject == name) {
            selectedProject = null
            events?.project = null
            waitingForWorkspace.clear()
            resetProjectState()
            iconFramework = null
            iconFrameworkFor = null
            if (debug == null || windowId != null) prefs.put(lastProjectKey, null)
        }
    }

    /** A completed registration refreshes only projects, preserving the current layout preferences. */
    suspend fun projectRegistered(client: ApiClient, project: ProjectInfo) {
        if (api !== client) return
        val fresh = client.projects()
        if (api !== client) return
        projectListRevision++
        projects = fresh
        projectsLoad = Load.Loaded
        val added = fresh.firstOrNull { it.path == project.path } ?: return
        openProject(added.name)
    }

    suspend fun loadProjects() {
        val api = api ?: return
        projectsLoad = Load.Loading
        try {
            val projects = coroutineScope {
                // A server too old for this app fails the open; any other failure leaves the info out.
                val info = async { runCatching { api.info() }.getOrElse { if (it is PpmError.OutdatedServer) throw it else null } }
                val uiPrefs = async { quietly { api.uiPrefs() } }
                val keys = async { quietly { api.keybindings() } }
                val projects = api.projects()
                this@AppModel.info = info.await()
                reconcileLogsBadge()
                keys.await()?.let { keyBindings = KeyBindings(it) }
                uiPrefs.await()?.let { prefs ->
                    prefs.projectSortMode?.takeIf { it in PROJECT_SORT_MODES }?.let { projectSortMode = it }
                    projectOpenedAt = newerOpenTimes(projectOpenedAt, prefs.recentOpen)
                    prefs.sidebarCollapsed?.let { sidebarCollapsed = it }
                    prefs.sidebarWidth?.let { sidebarWidth = it }
                    prefs.keepScreenAwake?.let { keepScreenAwake = it }
                    prefs.explorerSkin?.let { explorerSkin = it }
                    prefs.tabWrap?.let { tabWrap = it }
                    prefs.mcpSignInDismissed?.let { mcpSignInDismissed = it }
                    prefs.sidebarActiveTab?.let { sidebarTab = it }
                    prefs.sidebarTabOrder?.let { sidebarTabOrder = it }
                    prefs.gitStatusViewMode?.let { gitStatusViewMode = it }
                    prefs.wordWrap?.let { wordWrap = it }
                    prefs.editorTabStyle?.let { editorTabStyle = it }
                    prefs.inlineBlame?.let { inlineBlame = it }
                    prefs.gutterChanges?.let { gutterChanges = it }
                    prefs.diffSideBySide?.let { diffSideBySide = it }
                    prefs.designWindows?.let { designWindows = it }
                    prefs.remoteDesktopStatsVisible?.let { remoteDesktopStatsVisible = it }
                    prefs.remoteDesktopWarningDismissed?.let { remoteDesktopWarningDismissed = it }
                    prefs.jiraEnabled?.let { jiraEnabled = it }
                    unlinkedSessions = prefs.unlinkedSessions
                    saveUiPrefs()
                }
                projects
            }
            this.projects = projects
            projectsLoad = Load.Loaded
            // Only on the server it was for: that one may not have opened, and another opened since.
            val link = pendingLink?.takeIf { it.first == serverKey }?.second
            pendingLink = null
            val linked = link?.projectName?.takeIf { name -> projects.any { it.name == name } }
            link?.projectName?.takeIf { linked == null }?.let { actionError(noSuchProject(it)) }
            val last = linked ?: (if (windowId != null) prefs.get(lastProjectKey) else null) ?: initialProject ?: debug?.project ?: prefs.get(lastProjectKey)
            initialProject = null
            when {
                last != null && projects.any { it.name == last } ->
                    openProject(
                        last,
                        then = link?.takeIf { linked != null }?.let { l -> { store: WorkspaceStore -> openFromLink(store, l, last) } }
                            ?: link?.assistant?.let { a -> { store: WorkspaceStore -> assistant.openIn(store, a.sessionId, a.providerId); Unit } },
                    )
                projects.isNotEmpty() -> openProject(projects.first().name)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            projectsLoad = Load.Failed(describe(error))
        }
    }

    /** Identifies the open server in saved state, so each server keeps its own tabs. */
    private val lastProjectKey: String get() = "lastProject.$serverKey" + (windowId?.let { ".$it" } ?: "")
    private val windowStorageKey: String get() = serverKey + (windowId?.let { ".$it" } ?: "")

    private val serverKey: String get() = if (debug != null) "debug" else store.selected?.id ?: "none"

    /**
     * The UI prefs this device last had for the open server, or the web's defaults for a server it
     * has not opened: the web keeps them in localStorage, so it draws with them before the server
     * answers, and so does this — the sidebar no longer starts at 280 and jumps to its width.
     */
    private fun restoreUiPrefs() {
        val saved = prefs.get("uiPrefs.$serverKey")?.let { runCatching { UiPrefs.from(Json.parseToJsonElement(it)) }.getOrNull() } ?: UiPrefs()
        projectSortMode = saved.projectSortMode?.takeIf { it in PROJECT_SORT_MODES } ?: "priority"
        projectOpenedAt = saved.recentOpen
        sidebarCollapsed = saved.sidebarCollapsed ?: false
        sidebarWidth = saved.sidebarWidth ?: SIDEBAR_DEFAULT_WIDTH
        keepScreenAwake = saved.keepScreenAwake ?: true
        explorerSkin = saved.explorerSkin
        tabWrap = saved.tabWrap ?: false
        mcpSignInDismissed = saved.mcpSignInDismissed ?: emptyList()
        sidebarTab = saved.sidebarActiveTab ?: "history"
        sidebarTabOrder = saved.sidebarTabOrder ?: emptyList()
        gitStatusViewMode = saved.gitStatusViewMode ?: "flat"
        wordWrap = saved.wordWrap ?: true
        editorTabStyle = saved.editorTabStyle ?: "default"
        inlineBlame = saved.inlineBlame ?: true
        gutterChanges = saved.gutterChanges ?: false
        diffSideBySide = saved.diffSideBySide ?: true
        designWindows = saved.designWindows ?: false
        remoteDesktopStatsVisible = saved.remoteDesktopStatsVisible ?: false
        remoteDesktopWarningDismissed = saved.remoteDesktopWarningDismissed ?: false
        jiraEnabled = saved.jiraEnabled ?: false
        unlinkedSessions = saved.unlinkedSessions
        logsBadge = prefs.get("logsBadge.$serverKey")?.toBooleanStrictOrNull() ?: true
        remoteDesktopQuality = com.hienlh.ppm.state.remotedesktop.parseQualityChoice(prefs.get("remoteDesktopQuality.$serverKey"))
        remoteDesktopViewStyle = com.hienlh.ppm.state.remotedesktop.parseViewStyle(prefs.get("remoteDesktopViewStyle.$serverKey"))
        remoteDesktopCustomScale = com.hienlh.ppm.state.remotedesktop.clampCustomScale(
            prefs.get("remoteDesktopCustomScale.$serverKey")?.toDoubleOrNull(),
        )
        val rdMore = prefs.get("remoteDesktopCustomQualityMore.$serverKey")?.toBooleanStrictOrNull() ?: false
        remoteDesktopCustomQualityMore = rdMore
        remoteDesktopCustomQualityPercent = com.hienlh.ppm.state.remotedesktop.clampCustomQualityPercent(
            prefs.get("remoteDesktopCustomQualityPercent.$serverKey")?.toIntOrNull(),
            rdMore,
        )
        remoteDesktopCustomFps = com.hienlh.ppm.state.remotedesktop.clampCustomFps(
            prefs.get("remoteDesktopCustomFps.$serverKey")?.toIntOrNull(),
        )
        remoteDesktopShowCursor = prefs.get("remoteDesktopShowCursor.$serverKey")?.toBooleanStrictOrNull() ?: true
        remoteDesktopClipboardSync = prefs.get("remoteDesktopClipboardSync.$serverKey")?.toBooleanStrictOrNull() ?: true
        remoteDesktopCodec = prefs.get("remoteDesktopCodec.$serverKey")?.takeIf { it.isNotEmpty() }
    }

    private fun saveUiPrefs() {
        val saved = UiPrefs(
            projectSortMode = projectSortMode,
            recentOpen = projectOpenedAt,
            sidebarCollapsed = sidebarCollapsed,
            sidebarWidth = sidebarWidth,
            keepScreenAwake = keepScreenAwake,
            explorerSkin = explorerSkin,
            tabWrap = tabWrap,
            mcpSignInDismissed = mcpSignInDismissed,
            sidebarActiveTab = sidebarTab,
            sidebarTabOrder = sidebarTabOrder,
            gitStatusViewMode = gitStatusViewMode,
            wordWrap = wordWrap,
            editorTabStyle = editorTabStyle,
            inlineBlame = inlineBlame,
            gutterChanges = gutterChanges,
            diffSideBySide = diffSideBySide,
            designWindows = designWindows,
            remoteDesktopStatsVisible = remoteDesktopStatsVisible,
            remoteDesktopWarningDismissed = remoteDesktopWarningDismissed,
            jiraEnabled = jiraEnabled,
            unlinkedSessions = unlinkedSessions,
        )
        prefs.put("uiPrefs.$serverKey", saved.toJson().toString())
    }

    private fun startEvents(api: ApiClient) {
        val events = GlobalEvents(api, scope)
        // Whatever changed while the socket was down — another device renamed, pinned or
        // started a chat — was never delivered.
        events.onReady = {
            scope.launch { refreshSessions(quiet = true) }
            // Pushes missed while the socket was down, or a server that restarted and keeps no list.
            for (chat in chats.values) chat.sessionChanges?.reconnected()
            fileTree?.reconnected()
            hostExplorers.values.forEach { it.refresh() }
            documents.values.forEach { it.reconnected() }
        }
        events.isShown = { id -> windowVisible && activeSessionId == id }
        assistant.connect(api, events)
        events.onGitChanged = { gitChanged.tryEmit(it) }
        events.onGitCommitDraft = { project, repoPath, message, _, clientId ->
            val repo = com.hienlh.ppm.state.git.GitRepoScope.resolveRepo(gitDiscovery[project], gitChosenRepoPath[project])
            commitDrafts.onRemoteDraft(project, repoPath, message, clientId, repo)
        }
        // A change only marks the list stale; it is read again when a picker shows it.
        events.onFileChanged = { project, path ->
            fileIndex?.markStale(project)
            fileTree?.onFileChanged(project, path)
            documents.values.forEach { it.fileChanged(project, path) }
            fileChanged.tryEmit(project to path)
            gitChanged.tryEmit(project)
        }
        events.onIndexChanged = { fileIndex?.rebuilt(it) }
        events.onSessionFileChanges = { push ->
            for (chat in chats.values) {
                if (chat.project == push.projectName && chat.session?.id == push.sessionId) chat.sessionChanges?.push(push.files, push.gone)
            }
        }
        events.onSessionCreated = { created ->
            if (created.projectName == selectedProject && sessions.none { it.id == created.sessionId }) {
                sessions = (
                    listOf(
                        SessionInfo(
                            id = created.sessionId,
                            providerId = created.providerId,
                            title = created.title,
                            createdAt = runCatching { java.time.Instant.parse(created.createdAt) }.getOrNull(),
                            spawnedBy = created.spawnedBy,
                            parentTitle = created.parentTitle,
                        ),
                    ) + sessions
                ).sortedForList()
            }
        }
        wireLiveSync(events)
        // The tour's answer about an editor setting is saved by the server into `ui_prefs`, here or on another device.
        events.onFeatureIntrosChanged { change, _ -> if (change == "answer" || change == "settings") reloadGutterChangesPref() }
        events.onLogsIssuesChanged {
            if (openLogsWindowCount == 0) {
                refreshLogsIssuesSummary()
            }
        }
        this.events = events
        events.start()
    }

    private fun startExtensions(api: ApiClient) {
        val ext = com.hienlh.ppm.state.extensions.ExtensionClient(api, scope)
        wireExtensionClient(ext)
        this.extensions = ext
        ext.start()
    }

    internal fun wireExtensionClient(ext: com.hienlh.ppm.state.extensions.ExtensionClient) {
        ext.onActivationError = { extId, error ->
            toast(ErrorToastMessage("Extension \"$extId\" failed to activate: $error"))
        }
        ext.onPersistWebviewState = { key, stateJson ->
            prefs.put("extWebviewState.$serverKey.$key", stateJson)
        }
        ext.onLoadWebviewState = { key ->
            prefs.get("extWebviewState.$serverKey.$key")
        }
        ext.onWebviewCreated = { msg ->
            val slug = ext.coordinator.viewSlug(msg.viewType)
            val locallyRequested = ext.coordinator.takeLocalDispatch(slug, msg.projectPath)
            val projName = msg.projectName ?: projects.firstOrNull { it.path == msg.projectPath }?.name
            val candidates = openTabs.map { it.first }.filter { t ->
                t.type == "extension" &&
                    ext.coordinator.viewSlug(t.metaString("viewType").orEmpty()) == slug
            }
            val existingTab = if (projName != null) {
                candidates.firstOrNull { t -> (t.metaString("projectName") ?: t.projectId) == projName }
                    ?: candidates.firstOrNull { t -> (t.metaString("projectName") ?: t.projectId).isNullOrEmpty() }
            } else {
                candidates.firstOrNull()
            }
            if (existingTab != null) {
                patchTabMetadata(
                    existingTab.id,
                    "panelId" to JsonPrimitive(msg.panelId),
                    "viewType" to JsonPrimitive(slug),
                    "extensionId" to JsonPrimitive(msg.extensionId),
                    *(if (projName != null) arrayOf("projectName" to JsonPrimitive(projName)) else emptyArray()),
                )
                val wasRecovery = ext.coordinator.consumeRecovery(slug)
                if (locallyRequested && !wasRecovery) {
                    activate(existingTab.id)
                }
            } else if (locallyRequested && !ext.coordinator.isRecentlyClosed(slug, projName)) {
                val targetProject = projName ?: selectedProject
                withWorkspace { store ->
                    store.openTab(
                        NewTab(
                            type = "extension",
                            title = msg.title,
                            projectId = targetProject,
                            closable = true,
                            metadata = buildJsonObject {
                                put("panelId", msg.panelId)
                                put("viewType", slug)
                                put("extensionId", msg.extensionId)
                                if (targetProject != null) put("projectName", targetProject)
                            },
                        ),
                    )
                }
            }
        }
        ext.onTabOpen = { msg ->
            val metaProjectName = (msg.metadata["projectName"] as? JsonPrimitive)?.contentOrNull
            if (msg.tabType == "git-review" && !metaProjectName.isNullOrEmpty()) {
                val path = (msg.metadata["path"] as? JsonPrimitive)?.contentOrNull
                val repo = (msg.metadata["repo"] as? JsonPrimitive)?.contentOrNull
                openGitReview(metaProjectName, path, repo)
            } else {
                withWorkspace { store ->
                    store.openTab(
                        NewTab(
                            type = msg.tabType,
                            title = msg.title,
                            projectId = msg.projectId,
                            closable = msg.closable,
                            metadata = JsonObject(msg.metadata),
                        ),
                    )
                }
            }
        }
        ext.onProjectSwitch = { projName ->
            if (projects.any { it.name == projName }) {
                scope.launch { openProject(projName) }
            }
        }
        ext.onNotification = { msg ->
            val kind = when (msg.level) {
                "error" -> ErrorToastMessage.Kind.ERROR
                "warn" -> ErrorToastMessage.Kind.WARNING
                else -> ErrorToastMessage.Kind.INFO
            }
            val firstAction = msg.actions.firstOrNull()
            val secondAction = msg.actions.getOrNull(1)
            toast(
                ErrorToastMessage(
                    title = msg.message,
                    action = firstAction?.let { label ->
                        ErrorToastMessage.Action(label) {
                            ext.sendNotificationAction(msg.id, label)
                        }
                    },
                    cancel = secondAction?.let { label ->
                        ErrorToastMessage.Action(label) {
                            ext.sendNotificationAction(msg.id, label)
                        }
                    },
                    onDismiss = if (msg.actions.isNotEmpty()) {
                        { ext.sendNotificationAction(msg.id, null) }
                    } else {
                        null
                    },
                    kind = kind,
                ),
            )
        }
    }

    /**
     * Dispatches an extension command (`ext-command-dispatch.ts`), automatically passing the active
     * project's path (or chosen git repository path for `git-graph.*` commands) when [args] is null.
     */
    fun dispatchExtCommand(
        command: String,
        args: List<JsonElement>? = null,
        recovery: Boolean = false,
        projectName: String? = null,
    ) {
        val ext = extensions ?: return
        if (args != null) {
            val argPath = (args.firstOrNull() as? JsonPrimitive)?.contentOrNull
            ext.coordinator.recordCommandDispatch(command, argPath, recovery)
            ext.sendCommand(command, args)
            return
        }
        val targetProjectName = if (recovery) projectName else (projectName ?: selectedProject)
        if (targetProjectName.isNullOrEmpty()) {
            ext.coordinator.recordCommandDispatch(command, null, recovery)
            ext.sendCommand(command, emptyList())
            return
        }
        val runsGit = com.hienlh.ppm.state.git.GitRepoScope.commandRunsGit(command)
        val cachedProj = projects.firstOrNull { it.name == targetProjectName }
        if (cachedProj != null && (!runsGit || cachedProj.name in gitDiscovery)) {
            val projectPath = if (runsGit) {
                com.hienlh.ppm.state.git.GitRepoScope.resolveRepo(
                    gitDiscovery[cachedProj.name],
                    gitChosenRepoPath[cachedProj.name],
                )?.path ?: cachedProj.path
            } else {
                cachedProj.path
            }
            ext.coordinator.recordCommandDispatch(command, projectPath, recovery)
            ext.sendCommand(command, listOf(JsonPrimitive(projectPath)))
            return
        }
        scope.launch {
            val proj = cachedProj ?: api?.let { client ->
                quietly { client.projects() }?.firstOrNull { it.name == targetProjectName }
            }
            val projectPath = if (proj != null) {
                if (runsGit) {
                    loadGitRepos(proj.name)
                    com.hienlh.ppm.state.git.GitRepoScope.resolveRepo(
                        gitDiscovery[proj.name],
                        gitChosenRepoPath[proj.name],
                    )?.path ?: proj.path
                } else {
                    proj.path
                }
            } else {
                null
            }
            ext.coordinator.recordCommandDispatch(command, projectPath, recovery)
            val effectiveArgs = if (projectPath != null) listOf(JsonPrimitive(projectPath)) else emptyList()
            ext.sendCommand(command, effectiveArgs)
        }
    }

    /** How many times the app has come to the front: each restarts the reconnect pill's clocks. */
    var resumes by mutableIntStateOf(0)
        private set

    /**
     * The app came to the front: replace the sockets that died or went silent meanwhile, read the
     * session list again if it has not been read for a while (the web does both when a page shows),
     * and look for a newer release of the app once a day.
     */
    /** A second window/browser may have changed registrations while this one was hidden. */
    internal fun refreshProjectRegistrations() {
        val client = api ?: return
        if (projectsLoad != Load.Loaded || projectRefresh?.isActive == true) return
        val revision = projectListRevision
        projectRefresh = scope.launch {
            try {
                val fresh = client.projects()
                if (api !== client || projectListRevision != revision) return@launch
                val changes = com.hienlh.ppm.state.reconcileProjectRegistrations(projects, fresh, selectedProject)
                projects = fresh
                projectListRevision++
                for (name in changes.invalidated) forgetProjectRegistration(name)
                projectActions = projectActions?.let { old -> fresh.firstOrNull { it.name == old.name && it.path == old.path } }
                changes.selected?.takeIf { it != selectedProject }?.let { openProject(it) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep the current list on transient connection failure. */ }
        }
    }

    fun becameActive() {
        resumes++
        AppTrace.front(this)
        refreshProjectRegistrations()
        for (tab in chats.values) tab.controller?.resumed()
        for (group in groupChats.values) group.chat.wake()
        events?.wake()
        extensions?.wake()
        refreshWorkspace()
        updater.becameActive()
        val readAt = sessionsReadAt ?: return
        if (System.currentTimeMillis() - readAt > 15_000) scope.launch { refreshSessions(quiet = true) }
    }

    /** The unread sessions of a project, for the project's dot. */
    fun unread(project: String?, unread: Map<String, SessionUnread>): List<SessionUnread> =
        if (project == null) emptyList() else unread.values.filter { it.projectName == project }

    /** The most urgent unread kind on a project (`selectProjectUrgentType`). */
    fun urgentUnread(project: String, unread: Map<String, SessionUnread>): String? {
        val priority = mapOf("done" to 0, "question" to 1, "approval_request" to 2)
        return unread(project, unread).maxByOrNull { priority[it.type] ?: 0 }?.type
    }

    /** A tab's title: the list's, which carries a rename made anywhere, else the tab's own; a new chat's is its tab's. */
    fun title(tab: ChatTab, liveTitle: String?): String {
        val session = tab.session ?: return tab.tabTitle.ifEmpty { "New Chat" }
        sessions.firstOrNull { it.id == session.id }?.title?.takeIf { it.isNotEmpty() }?.let { return it }
        return liveTitle?.takeIf { it.isNotEmpty() } ?: session.title.ifEmpty { "New Chat" }
    }

    /** A tab's live title, carried into the list as the web's chat tab does. */
    fun renameSession(id: String, title: String) = patchSession(id) { if (it.title != title) it.copy(title = title) else it }

    // Managing sessions

    /** Renames in every list now; a refused rename reads the list again. */
    suspend fun rename(session: SessionInfo, title: String) {
        val trimmed = title.trim()
        val api = api ?: return
        val project = selectedProject ?: return
        if (trimmed.isEmpty() || trimmed == session.title) return
        patchSession(session.id) { it.copy(title = trimmed) }
        try {
            api.renameSession(project, session.id, trimmed)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            actionError(describe(error))
            refreshSessions(quiet = true)
        }
    }

    /** Deletes once confirmed ([confirmDeleteSession]), closing its tab too. */
    suspend fun delete(session: SessionInfo) {
        val api = api ?: return
        val project = selectedProject ?: return
        sessions = sessions.filter { it.id != session.id }
        sidebarList?.remove(session.id)
        homeList?.remove(session.id)
        chats.values.forEach {
            it.historyList?.remove(session.id)
            it.welcomeList?.remove(session.id)
        }
        closeSessionTabs(session.id)
        try {
            api.deleteSession(project, session.id, session.providerId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            actionError(describe(error))
            refreshSessions(quiet = true)
        }
        reloadTags()
    }

    /** Deletes the unpinned sessions older than [days] (the server decides which), then reads the list again. */
    suspend fun deleteSessions(days: Int) {
        val api = api ?: return
        val project = selectedProject ?: return
        deletingOld = true
        try {
            api.deleteSessions(project, days)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            actionError(describe(error))
        } finally {
            deletingOld = false
        }
        refreshSessions(quiet = true)
    }

    /** Puts the tag on now; a refused change puts the old one back. */
    suspend fun setTag(tag: SessionTag?, session: SessionInfo) {
        val api = api ?: return
        val project = selectedProject ?: return
        val previous = session.tag
        patchSession(session.id) { it.copy(tag = tag) }
        try {
            api.setTag(tag?.id, project, session.id)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            patchSession(session.id) { it.copy(tag = previous) }
            actionError(describe(error))
        }
        reloadTags()
    }

    fun markUnread(session: SessionInfo) = events?.markUnread(session.id, session.title)

    fun markRead(sessionId: String) = events?.markRead(sessionId)

    private fun patchSession(id: String, change: (SessionInfo) -> SessionInfo) = patchSessions({ it.id == id }, change)

    /**
     * Changes sessions in every list that shows them. A pin moves one, so the shared page is put
     * back in order (the lists sort what they show themselves).
     */
    private fun patchSessions(matches: (SessionInfo) -> Boolean, change: (SessionInfo) -> SessionInfo) {
        val list = sessions.map { if (matches(it)) change(it) else it }
        if (list != sessions) sessions = list.sortedForList()
        sidebarList?.patch(matches, change)
        homeList?.patch(matches, change)
        for (tab in chats.values) {
            tab.historyList?.patch(matches, change)
            tab.welcomeList?.patch(matches, change)
        }
    }

    /** Opens what the search found. */
    fun open(hit: ChatSearchHit) {
        val known = sessions.firstOrNull { it.id == hit.sessionId }
        openTab(known ?: SessionInfo(id = hit.sessionId, providerId = hit.providerId, title = hit.title ?: ""))
    }

    // Tags

    /** Reads the project's tags again, as Manage tags does when it opens. */
    suspend fun reloadTags() {
        val api = api ?: return
        val project = selectedProject ?: return
        val tags = quietly { api.tags(project) } ?: return
        if (selectedProject == project) this.tags = tags
    }

    /** Answers whether it was made, so that the form can close. */
    suspend fun createTag(name: String, color: String): Boolean {
        val api = api ?: return false
        val project = selectedProject ?: return false
        if (!report { api.createTag(project, name, color) }) return false
        reloadTags()
        return true
    }

    /** Answers whether it was changed, so that the row can close. */
    suspend fun updateTag(tag: SessionTag, name: String, color: String): Boolean {
        val api = api ?: return false
        val project = selectedProject ?: return false
        if (!report { api.updateTag(project, tag.id, name, color) }) return false
        reloadTags()
        // Each session carries its own copy of its tag.
        tags.tags.firstOrNull { it.id == tag.id }?.let { updated -> patchSessions({ it.tag?.id == tag.id }) { it.copy(tag = updated) } }
        return true
    }

    /** Once confirmed ([confirmDeleteTag]). Its sessions lose it at once; a refused delete reads them again. */
    suspend fun deleteTag(tag: SessionTag) {
        val api = api ?: return
        val project = selectedProject ?: return
        patchSessions({ it.tag?.id == tag.id }) { it.copy(tag = null) }
        // A chip that is gone cannot stay picked: its list would show nothing, with no chip lit.
        sidebarList?.let { if (it.tag == tag.id) it.tag = null }
        homeList?.let { if (it.tag == tag.id) it.tag = null }
        for (tab in chats.values) tab.historyList?.let { if (it.tag == tag.id) it.tag = null }
        if (!report { api.deleteTag(project, tag.id) }) refreshSessions(quiet = true)
        reloadTags()
    }

    /** The tag new chats get; picking the one that already is clears it. */
    suspend fun toggleDefaultTag(tag: SessionTag) {
        val api = api ?: return
        val project = selectedProject ?: return
        val id = if (tags.defaultTagId == tag.id) null else tag.id
        if (report { api.setDefaultTag(id, project) }) tags = tags.copy(defaultTagId = id)
    }

    /** Puts back the tags a project starts with; the sessions are read again, since a tag that went is no longer on them. */
    suspend fun resetTags() {
        val api = api ?: return
        val project = selectedProject ?: return
        if (report { api.resetTags(project) }) refreshSessions(quiet = true)
    }

    // Theme

    suspend fun loadTheme() {
        val api = api ?: return
        quietly { api.themeSelection() }?.let { themeSelection = it }
        quietly { api.settingsListThemes() }?.let { raw ->
            updateCustomThemes(com.hienlh.ppm.state.settings.parseImportedThemes(raw))
        }
    }

    /** Picks the theme for every device, as the web's status bar picker does. */
    suspend fun pickTheme(theme: PpmTheme) {
        val previous = themeSelection
        themeSelection = ThemeSelection(theme.style, if (theme.isDark) "dark" else "light")
        if (!report { api?.setTheme(themeSelection) }) themeSelection = previous
    }

    /** Light, dark or the system's, keeping the style (`THEME_MODE_OPTIONS`). */
    suspend fun pickThemeMode(mode: String) {
        val previous = themeSelection
        themeSelection = previous.copy(mode = mode)
        if (!report { api?.setTheme(themeSelection) }) themeSelection = previous
    }

    // Projects and sessions

    /** Orders the project sheet for every device, as `setProjectSortMode` does. */
    fun pickProjectSort(mode: String) {
        projectSortMode = mode
        saveUiPrefs()
        val api = api ?: return
        scope.launch { quietly { api.putUiPrefs(buildJsonObject { put("projectSortMode", mode) }) } }
    }

    /** The Priority order dragged in the project switcher: shown at once, and kept for every device (`reorderProjects`). */
    fun reorderProjects(order: List<String>) {
        projectListRevision++
        val byName = projects.associateBy { it.name }
        projects = order.mapNotNull { byName[it] } + projects.filter { it.name !in order }
        val api = api ?: return
        scope.launch { quietly { api.reorderProjects(order) } }
    }

    /** Uploaded avatars being read or read, by project and version: one request however many avatars ask. */
    private val projectImages = mutableMapOf<String, Deferred<ImageBitmap?>>()

    /** A project's uploaded avatar; null when it has none or it would not load, where the web draws the initials. */
    suspend fun projectImage(project: ProjectInfo): ImageBitmap? {
        val version = project.image ?: return null
        val api = api ?: return null
        val key = "${project.name}\u0000$version"
        val load = projectImages.getOrPut(key) {
            scope.async {
                quietly {
                    val bytes = api.projectImage(project.name, version)
                    withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
                }
            }
        }
        // A failure is not kept: the next avatar drawn asks again, as an <img> mounted again does.
        return load.await().also { if (it == null) projectImages.remove(key, load) }
    }

    /** An image sent with a message: drawn, and its file, for the lightbox to save or copy. */
    private class ChatUpload(val image: ImageBitmap, val bytes: ByteArray)

    /** Images sent with messages, read or being read, by file name: the web's `imageBlobCache`, kept for the session. */
    private val chatImages = mutableMapOf<String, Deferred<ChatUpload?>>()

    private suspend fun chatUpload(project: String, path: String): ChatUpload? {
        val api = api ?: return null
        val key = path.split('/', '\\').last()
        val load = chatImages.getOrPut(key) {
            scope.async {
                quietly {
                    val bytes = api.uploadedFile(project, path)
                    withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }?.let { ChatUpload(it, bytes) }
                }
            }
        }
        return load.await().also { if (it == null) chatImages.remove(key, load) }
    }

    /** An image a message carries (`AuthImageThumbnail`); null when it will not load, and asked again next time. */
    suspend fun chatImage(project: String, path: String): ImageBitmap? = chatUpload(project, path)?.image

    /** The same image's file, for the lightbox: the blob the web's thumbnail hands it. */
    suspend fun chatImageFile(project: String, path: String): ViewerImage? =
        chatUpload(project, path)?.let { ViewerImage(path.split('/', '\\').last(), it.bytes, null) }

    /**
     * An image a tool card previews (`useBlobUrl`): read each time a card asks, as the web reads it
     * on every mount, and failed with what went wrong — the HTTP status, or `network`.
     */
    suspend fun toolImage(project: String, path: String): ToolImage {
        val api = api ?: return ToolImage.Failed("network")
        val file = try {
            api.rawFile(project, path)
        } catch (error: CancellationException) {
            throw error
        } catch (error: PpmError.Server) {
            return ToolImage.Failed(error.status.toString())
        } catch (_: PpmError.Unauthorized) {
            return ToolImage.Failed("401")
        } catch (_: Exception) {
            return ToolImage.Failed("network")
        }
        return withContext(Dispatchers.Default) { ToolImage.decode(file.bytes, file.contentType) }
    }

    /** A picture a message shows from the web (MdImage's plain `<img src>`), read from wherever it is. */
    suspend fun webImage(url: String): ToolImage {
        val file = try {
            webFile(url)
        } catch (error: CancellationException) {
            throw error
        } catch (error: PpmError.Server) {
            return ToolImage.Failed(error.status.toString())
        } catch (_: Exception) {
            return ToolImage.Failed("network")
        }
        return withContext(Dispatchers.Default) { ToolImage.decode(file.bytes, file.contentType) }
    }

    /** A file a chat names, in an editor tab of [project] (`openTab` in a tool card); focused when it is open already. */
    fun openFileTab(project: String, path: String, line: Long? = null, endLine: Long? = null) {
        if (project != selectedProject) {
            scope.launch { openProject(project) { openFileTab(project, path, line, endLine) } }
            return
        }
        withWorkspace { store ->
            val isSqlite = line == null && FileDocumentClassifier.classify(path) == FileDocumentViewer.SQLITE
            val id = store.openTab(
                NewTab(
                    type = if (isSqlite) "sqlite" else "editor",
                    title = path.split('/', '\\').last(),
                    projectId = project,
                    metadata = buildJsonObject {
                        put("filePath", path)
                        put("projectName", project)
                    },
                ),
            )
            if (line != null && id.isNotEmpty()) store.updateTab(id) {
                it.withMetadata(
                    "lineNumber" to JsonPrimitive(line), "endLine" to endLine?.let(::JsonPrimitive),
                    "revealAt" to JsonPrimitive(System.currentTimeMillis()),
                )
            }
        }
    }

    /**
     * A tab tool card's Open (`TabToolCard`): the file an `open_file` / `open_preview` call named,
     * resolved on the host and opened beside the chat [sessionId] as the AI's own call opened it,
     * in the chat's project, which is brought up first. What went wrong is a toast.
     */
    fun openToolFileTab(project: String, sessionId: String, tool: String, path: String, line: Int?) {
        val api = api ?: return
        val title = "Could not open ${path.split('/', '\\').last()}"
        scope.launch {
            val target = try {
                resolveToolCallTarget(tool, path, line, project, projects.firstOrNull { it.name == project }?.path) { api.fsStat(it) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                toast(ErrorToastMessage(title, describe(error)))
                return@launch
            }
            openProject(project) { store ->
                try {
                    store.openAiTab(target, sessionId)
                } catch (error: IllegalStateException) {
                    toast(ErrorToastMessage(title, describe(error)))
                }
            }
        }
    }

    /**
     * use-chat.ts for a chat that is not in front: a request that waits sounds and raises a toast
     * that stays, with a way to the chat; its answer, or the chat moving on, takes that toast back;
     * the turn's end only sounds. The unread side comes from the server (`session:unread_changed`).
     */
    private fun chatAttention(chat: ChatTab, attention: ChatAttention) {
        val sessionId = chat.controller?.sessionId ?: chat.session?.id
        when (attention) {
            is ChatAttention.Asked -> {
                if (sessionId == null || sessionInFront(sessionId)) return
                val notice = AttentionNotice.of(attention.request, chat.project, sessionId)
                NotificationSounds.play(notice.sound)
                val raised = ErrorToastMessage(
                    notice.title,
                    notice.description,
                    ErrorToastMessage.Action("Go to session") { goToSession(sessionId) },
                    kind = if (notice.warning) ErrorToastMessage.Kind.WARNING else ErrorToastMessage.Kind.INFO,
                    persistent = true,
                )
                chat.attentionToast = raised
                toast(raised)
            }
            ChatAttention.Answered -> {
                chat.attentionToast?.let(::dismissToast)
                chat.attentionToast = null
            }
            ChatAttention.Done -> if (sessionId != null && !sessionInFront(sessionId)) NotificationSounds.play(NotificationTones.DONE)
        }
    }

    /** `isSessionTabActive`: the app is in front and some panel, a window's too, shows the session's tab. */
    private fun sessionInFront(sessionId: String): Boolean {
        if (!windowVisible) return false
        val panels = workspace?.state?.value?.panels ?: return false
        return panels.values.any { tabSessionId(it.activeTab) == sessionId }
    }

    /**
     * The toast's "Go to session": the first panel holding the session's tab shows it. The web takes
     * any tab naming the session, its Review tab too, which has no way to answer; this takes its chat.
     */
    private fun goToSession(sessionId: String) {
        val store = workspace ?: return
        for (panel in store.state.value.panels.values) {
            val tab = panel.tabs.firstOrNull { tabSessionId(it) == sessionId } ?: continue
            store.setActiveTab(tab.id, panel.id)
            return
        }
    }

    /**
     * The server's `tab_open` on [chat]'s socket (`answerTabOpen`): the tab the AI asked for,
     * opened beside the chat in the chat's project, and for a page it made, how that page rendered.
     */
    private suspend fun answerAiTabOpen(chat: ChatTab, request: TabOpenRequest): TabOpenAnswer {
        val api = api ?: return TabOpenAnswer(opened = false, error = "the PPM app is not connected")
        val sessionId = chat.controller?.sessionId ?: chat.session?.id
            ?: return TabOpenAnswer(opened = false, error = "the chat has no session")
        return answerTabOpen(
            request,
            previews,
            openFile = { target ->
                inChatProject(chat.project) { store ->
                    val tabId = store.openAiTab(target, sessionId)
                    val tab = store.state.value.panelOf(tabId)?.tab(tabId)
                    // The key the tab's preview loads under, as its metadata names the file.
                    val key = if (tab == null) {
                        PreviewLoads.key(target.projectName, target.filePath)
                    } else {
                        PreviewLoads.key(tab.metaString("projectName"), tab.metaString("filePath") ?: target.filePath)
                    }
                    PreviewTicket(key, previews.latest(key))
                }
            },
            openUrl = { url -> inChatProject(chat.project) { store -> store.openAiUrlTab(url, sessionId, api.baseUrl.host) } },
            openTerminal = { terminal ->
                inChatProject(chat.project) { store ->
                    val id = store.openInDock(NewTab(
                        type = "terminal", title = terminal.cwd.trimEnd('/').substringAfterLast('/').ifEmpty { terminal.cwd },
                        projectId = terminal.projectName ?: chat.project,
                        metadata = buildJsonObject {
                            put("sessionId", terminal.terminalId)
                            put("cwd", terminal.cwd)
                            put("projectName", terminal.projectName ?: chat.project)
                        },
                    ))
                    prefs.put("terminal.$serverKey.ppm:terminal-session:$id", terminal.terminalId)
                }
            },
            openQuery = { queryReq ->
                inChatProject(chat.project) { store ->
                    store.openAiQueryTab(queryReq.query, sessionId)
                }
            },
            openBranchReview = { review ->
                inChatProject(chat.project) { store ->
                    // The repository the server named scopes that tab alone (its `repo` metadata); the device's choice stays.
                    store.openAiBranchReviewTab(review, sessionId)
                }
            },
            frame = { if (wide) "Desktop" else "Phone" },
        )
    }

    /**
     * Runs [open] on [project]'s tabs once they are shown, bringing that project up first, as a
     * tab the AI opens lands in its chat's workspace (`showChatWorkspace`). Throws what [open]
     * throws, or when the project never comes up — another one was chosen meanwhile.
     */
    private suspend fun <T> inChatProject(project: String, open: (WorkspaceStore) -> T): T {
        val outcome = CompletableDeferred<Result<T>>()
        scope.launch { openProject(project) { store -> outcome.complete(runCatching { open(store) }) } }
        val result = withTimeoutOrNull(CHAT_PROJECT_WAIT_MS) { outcome.await() }
            ?: throw IllegalStateException("PPM could not show the chat's project")
        return result.getOrThrow()
    }

    /** A web-preview tab's page moved to [url] on its own origin: the tab reopens there (`currentUrl`). */
    internal fun keepPreviewUrl(tabId: String, url: String) {
        if (tabOf(tabId)?.metaString("currentUrl") == url) return
        patchTabMetadata(tabId, "currentUrl" to JsonPrimitive(url))
    }

    /**
     * A file a chat message names in [project], by a link or an inline `path:line`
     * (`useMarkdownFileNavigation`): an editor tab at the lines named, a folder in an explorer, and
     * anything the host cannot open searched for in the palette.
     */
    fun openFileOrSearch(project: String, path: String, line: SourceLine?) {
        val api = api ?: return
        if (path.isEmpty()) return
        // A background command's output file opens its output panel, while a chat still lists it.
        if (path.endsWith(".output")) {
            backgroundShellFor(path)?.let {
                backgroundOutput = it
                return
            }
        }
        val root = projects.firstOrNull { it.name == project }?.path
        scope.launch {
            when (val open = resolveFileNavigation(path, line, project, root, System.currentTimeMillis()) { api.fsStat(it) }) {
                is FileNavigation.Editor -> withWorkspace { store ->
                    val id = store.openTab(open.tab)
                    // A tab already open is only brought forward: the lines are handed to it too.
                    if (open.reveal && id.isNotEmpty()) store.updateTab(id) { it.copy(metadata = open.tab.metadata) }
                }
                is FileNavigation.Folder -> openExplorer(open.path)
                is FileNavigation.Search -> openPalette(open.query)
            }
        }
    }

    /** Independent tablet windows; one reusable full-screen sheet on a phone. */
    fun openExplorer(path: String = hostFilesystem?.state?.value?.host?.home ?: "~") {
        val windows = windows
        when {
            !wide || windows == null -> {
                explorerSheetPath = path
                hostExplorers["phone-explorer"]?.go(path)
            }
            windows.state.value.windows.size >= MAX_WINDOWS -> actionError(TOO_MANY_WINDOWS)
            else -> windows.open(WindowKind.Explorer, buildJsonObject { put("path", path) })
        }
    }

    /**
     * `openSessionReview`: the session's Review tab, on [selectPath] when one was picked. Asked
     * again, the tab already open takes the newer [paths] and selects that file once more.
     */
    fun openSessionReview(project: String, sessionId: String, title: String?, providerId: String?, paths: List<String>, selectPath: String?) {
        val patch = arrayOf<Pair<String, JsonElement?>>(
            "paths" to JsonArray(paths.map(::JsonPrimitive)),
            // The header links back to the chat by its name, which can change after the tab opened.
            *(if (!title.isNullOrEmpty()) arrayOf<Pair<String, JsonElement?>>("chatTitle" to JsonPrimitive(title)) else emptyArray()),
            // A new object each time: asking for the same file again must still select it.
            "select" to selectPath?.let { buildJsonObject { put("path", it); put("at", System.currentTimeMillis()) } },
        )
        withWorkspace { store ->
            val id = store.openTab(
                NewTab(
                    type = "session-review",
                    title = if (!title.isNullOrEmpty()) "Changes: $title" else "Session changes",
                    projectId = project,
                    metadata = buildJsonObject {
                        put("projectName", project)
                        put("sessionId", sessionId)
                        providerId?.let { put("providerId", it) }
                        for ((key, value) in patch) if (value != null) put(key, value)
                    },
                ),
            )
            if (id.isNotEmpty()) patchTabMetadata(id, *patch)
        }
    }

    /** An edit's change in a diff tab of its own (`openEditDiff`). */
    fun openDiffTab(project: String, path: String, original: String, modified: String) {
        withWorkspace { store ->
            store.openTab(
                NewTab(
                    type = "git-diff",
                    title = "Diff ${path.split('/', '\\').last()}",
                    projectId = project,
                    metadata = buildJsonObject {
                        put("filePath", path)
                        put("projectName", project)
                        put("original", original)
                        put("modified", modified)
                    },
                ),
            )
        }
    }

    fun gitChangesKey(project: String, repoPath: String?): String =
        com.hienlh.ppm.state.git.CommitDraftSync.draftKey(project, repoPath)

    fun cachedGitChanges(project: String, repoPath: String? = null): com.hienlh.ppm.client.GitChanges? {
        val resolvedPath = repoPath ?: com.hienlh.ppm.state.git.GitRepoScope.resolveRepo(
            gitDiscovery[project],
            gitChosenRepoPath[project],
        )?.path
        return gitChangesCache[gitChangesKey(project, resolvedPath)]
    }

    /** Sidebar badge count for Source Control (`selectChangeCount`). */
    fun gitChangeCount(project: String?): Int {
        if (project == null) return 0
        cachedGitChanges(project)?.let { return it.files.size }
        val status = gitBranches[project] ?: return 0
        return status.staged.size + status.unstaged.size + status.untracked.size
    }

    private val gitReposInflight = mutableMapOf<String, CompletableDeferred<Unit>>()

    suspend fun loadGitRepos(project: String, force: Boolean = false) {
        val client = api ?: return
        if (!force && project in gitDiscovery) {
            gitRepoChoices.chosen(project)?.let { gitChosenRepoPath[project] = it }
            return
        }
        val existing = gitReposInflight[project]
        if (existing != null && !force) {
            existing.await()
            return
        }
        val request = CompletableDeferred<Unit>()
        gitReposInflight[project] = request
        try {
            val repos = quietly { client.gitRepos(project) }
            if (repos != null && this.api === client) {
                gitDiscovery[project] = repos
                val saved = gitRepoChoices.chosen(project)
                if (saved != null) gitChosenRepoPath[project] = saved
            }
        } finally {
            if (gitReposInflight[project] === request) {
                gitReposInflight.remove(project)
            }
            request.complete(Unit)
        }
    }

    fun chooseGitRepo(project: String, repoPath: String) {
        gitRepoChoices.choose(project, repoPath)
        gitChosenRepoPath[project] = repoPath
        gitChanged.tryEmit(project)
    }

    fun applyGitStatus(project: String, repo: com.hienlh.ppm.client.GitRepo?, status: GitBranchStatus) {
        val rebased = com.hienlh.ppm.state.git.GitRepoScope.rebaseStatusPaths(status, repo)
        gitBranches[project] = rebased
        val files = com.hienlh.ppm.state.git.GitChangesView.buildFileStatusMap(rebased)
        gitFileStatuses[project] = files
        gitFolderStatuses[project] = com.hienlh.ppm.state.git.GitChangesView.buildFolderStatusMap(files)
    }

    fun applyGitChanges(project: String, repo: com.hienlh.ppm.client.GitRepo?, changes: com.hienlh.ppm.client.GitChanges) {
        gitChangesCache[gitChangesKey(project, repo?.path)] = changes
        applyGitStatus(project, repo, com.hienlh.ppm.state.git.GitChangesView.changesToStatus(changes))
    }

    fun refreshGitStatus(project: String? = selectedProject) {
        val target = project ?: selectedProject ?: return
        gitChanged.tryEmit(target)
    }

    fun openGitReview(project: String, path: String? = null, repo: String? = null) {
        val select = if (!path.isNullOrEmpty() || !repo.isNullOrEmpty()) {
            buildJsonObject {
                if (!path.isNullOrEmpty()) put("path", path)
                if (!repo.isNullOrEmpty()) put("repo", repo)
                put("at", System.currentTimeMillis())
            }
        } else {
            null
        }
        withWorkspace { store ->
            val id = store.openTab(
                NewTab(
                    type = "git-review",
                    title = "Review changes",
                    projectId = project,
                    metadata = buildJsonObject {
                        put("projectName", project)
                        if (select != null) put("select", select)
                    },
                ),
            )
            if (id.isNotEmpty() && select != null) patchTabMetadata(id, "select" to select)
        }
    }

    fun openGitConflictTab(project: String, filePath: String) {
        withWorkspace { store ->
            store.openTab(
                NewTab(
                    type = "conflict-editor",
                    title = "Conflict: ${filePath.split('/', '\\').last()}",
                    projectId = project,
                    metadata = buildJsonObject {
                        put("filePath", filePath)
                        put("projectName", project)
                    },
                ),
            )
        }
    }

    fun openGitDiffFileTab(project: String, filePath: String) {
        withWorkspace { store ->
            store.openTab(
                NewTab(
                    type = "git-diff",
                    title = "Diff: ${filePath.split('/', '\\').last()}",
                    projectId = project,
                    metadata = buildJsonObject {
                        put("filePath", filePath)
                        put("projectName", project)
                    },
                ),
            )
        }
    }

    fun openProjectEditorTab(project: String, filePath: String) {
        withWorkspace { store ->
            store.openTab(
                NewTab(
                    type = "editor",
                    title = filePath.split('/', '\\').last().ifEmpty { filePath },
                    projectId = project,
                    metadata = buildJsonObject {
                        put("filePath", filePath)
                        put("projectName", project)
                    },
                ),
            )
        }
    }

    fun openGitHistoryTab(project: String) {
        withWorkspace { store ->
            store.openTab(
                NewTab(
                    type = "git-log",
                    title = "Commits",
                    projectId = project,
                    metadata = buildJsonObject {
                        put("projectName", project)
                    },
                ),
            )
        }
    }

    fun openBranchReviewTab(project: String) {
        withWorkspace { store ->
            store.openTab(
                NewTab(
                    type = "branch-review",
                    title = "Review branch",
                    projectId = project,
                    metadata = buildJsonObject {
                        put("projectName", project)
                    },
                ),
            )
        }
    }

    fun openWorkspaceTab(
        type: String,
        title: String,
        metadata: JsonObject = buildJsonObject {},
        projectId: String? = selectedProject,
    ) {
        withWorkspace { store ->
            store.openTab(
                NewTab(
                    type = type,
                    title = title,
                    projectId = projectId,
                    metadata = metadata,
                ),
            )
        }
    }

    fun notifyGitChanged(project: String) {
        gitChanged.tryEmit(project)
    }

    /**
     * Keeps [project]'s branch in [gitBranches] while the status bar shows it (`useGitChangesPoller`):
     * read every 10 s, and 300 ms after PPM writes to the repository, so a burst of writes costs one
     * read. A project with no repository, or several to choose from, shows no branch.
     */
    suspend fun watchGit(project: String) {
        val api = api ?: return
        loadGitRepos(project)
        suspend fun read() {
            if ((gitStatusPanelOpenCount[project] ?: 0) > 0) return
            val discovery = gitDiscovery[project] ?: quietly { api.gitRepos(project) } ?: return
            val repo = com.hienlh.ppm.state.git.GitRepoScope.resolveRepo(discovery, gitChosenRepoPath[project]) ?: return
            // A failed read keeps the branch last shown.
            val status = quietly { api.gitStatus(project, repo) } ?: return
            if (this.api === api) applyGitStatus(project, repo, status)
        }
        coroutineScope {
            launch {
                gitChanged.filter { it == project }.collectLatest {
                    delay(300)
                    read()
                }
            }
            while (true) {
                read()
                delay(10_000)
            }
        }
    }

    /**
     * Reads the Recent times again (`refreshRecent`): another device may have opened a project since
     * this one connected. Called whenever a project switcher opens; a newer time this app holds stays.
     */
    fun refreshRecentProjects() {
        val api = api ?: return
        scope.launch {
            val server = quietly { api.uiPrefs() }?.recentOpen ?: return@launch
            val merged = newerOpenTimes(projectOpenedAt, server)
            if (merged != projectOpenedAt) {
                projectOpenedAt = merged
                saveUiPrefs()
            }
        }
    }

    /** Opening a project puts it first in the Recent order, on every device (`touchRecent`). */
    private fun touchProject(name: String) {
        val times = projectOpenedAt + (name to System.currentTimeMillis())
        projectOpenedAt = times
        saveUiPrefs()
        val api = api ?: return
        scope.launch {
            quietly { api.putUiPrefs(buildJsonObject { put("recentOpen", JsonObject(times.mapValues { JsonPrimitive(it.value) })) }) }
        }
    }

    /**
     * Reads [project]'s `package.json` once per server for the icons' framework; neither overlay
     * until the answer lands, rather than the previous project's.
     */
    private fun detectIconFramework(api: ApiClient, project: String) {
        val key = "$serverKey\u0000$project"
        iconFrameworkFor = key
        if (key in iconFrameworks) {
            iconFramework = iconFrameworks[key]
            return
        }
        iconFramework = null
        scope.launch {
            val framework = try {
                IconFramework.of(api.readProjectFile(project, "package.json"))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (this@AppModel.api !== api || projects.none { it.name == project }) return@launch
            iconFrameworks[key] = framework
            if (iconFrameworkFor == key) iconFramework = framework
        }
    }

    /** Opens [name]; [then] runs on its tabs once they are shown, or at once when it is already open. */
    suspend fun openProject(name: String, then: ((WorkspaceStore) -> Unit)? = null) {
        val api = api ?: return
        if (name == selectedProject && sessionsLoad != Load.Idle) {
            then?.let(::withWorkspace)
            return
        }
        selectedProject = name
        detectIconFramework(api, name)
        waitingForWorkspace.clear()
        then?.let { waitingForWorkspace += it }
        events?.project = name
        resetProjectState()
        if (debug == null || windowId != null) prefs.put(lastProjectKey, name)
        touchProject(name)
        sidebarList = HistoryList(api, name, scope)
        homeList = HistoryList(api, name, scope)
        sidebarSearch = ChatSearch(api, name, scope)
        models = ModelCatalog(api, name)
        // Apart from the list: a failed read of the sessions must not leave the tabs unshown.
        scope.launch { showWorkspace(name) }
        sessionsLoad = Load.Loading
        syncsRunning++
        try {
            coroutineScope {
                val providers = async { quietly { api.providers(name) } }
                val tags = async { quietly { api.tags(name) } }
                val page = api.sessions(name)
                if (selectedProject != name) return@coroutineScope
                sessions = page.sessions.sortedForList()
                hasMoreSessions = page.hasMore
                sessionsReadAt = System.currentTimeMillis()
                this@AppModel.providers = providers.await().orEmpty()
                this@AppModel.tags = tags.await() ?: ProjectTags()
                sessionsLoad = Load.Loaded
                sessionsSyncError = null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            sessionsLoad = Load.Failed(describe(error))
            if (selectedProject == name) sessionsSyncError = describe(error)
        } finally {
            syncsRunning--
        }
        if (selectedProject != name) return
        if (debugSessionOpened) return
        debug?.session?.let { id -> sessions.firstOrNull { it.id == id }?.let { debugSessionOpened = true; openTab(it) } }
    }

    private fun resetProjectState() {
        fileIndex?.reset()
        fileTree?.reset()
        sessions = emptyList()
        sessionsLoad = Load.Idle
        hasMoreSessions = false
        providers = emptyList()
        tags = ProjectTags()
        sidebarList = null
        homeList = null
        sidebarSearch = null
        models = null
        sessionsReadAt = null
        sessionsSyncError = null
    }

    /** Reads the shared first page again. A [quiet] read runs in the background: when it fails, the list shown simply stays. */
    suspend fun refreshSessions(quiet: Boolean = false) {
        val api = api ?: return
        val project = selectedProject ?: return
        syncsRunning++
        try {
            coroutineScope {
                val tags = async { quietly { api.tags(project) } }
                val page = api.sessions(project)
                if (selectedProject != project) return@coroutineScope
                sessions = page.sessions.sortedForList()
                hasMoreSessions = page.hasMore
                sessionsLoad = Load.Loaded
                sessionsReadAt = System.currentTimeMillis()
                sessionsSyncError = null
                tags.await()?.let { this@AppModel.tags = it }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (selectedProject == project) {
                sessionsSyncError = describe(error)
                if (!quiet) actionError(describe(error))
            }
        } finally {
            syncsRunning--
        }
    }

    /** A list's refresh button: its search is asked again, or else the shared page is read again. */
    suspend fun refresh(list: HistoryList) {
        list.reload()
        if (list.state.value.searchPage == null) refreshSessions()
    }

    suspend fun togglePin(session: SessionInfo) {
        val api = api ?: return
        val project = selectedProject ?: return
        val pinned = !session.pinned
        patchSession(session.id) { it.copy(pinned = pinned) }
        if (!report { api.setPinned(pinned, project, session.id) }) {
            patchSession(session.id) { it.copy(pinned = !pinned) }
            refreshSessions(quiet = true)
        }
    }

    fun providerName(id: String): String = providers.firstOrNull { it.id == id }?.name ?: id.replaceFirstChar { it.uppercase() }

    // Agent sessions

    /**
     * Opens an agent's or teammate's session (`useOpenAgentSession`): the sheet on a phone; on a
     * tablet its window, brought forward when it is open already. At the window cap the oldest
     * agent window makes room, and with none to, the open is refused rather than closing a window
     * the user is still using. [fallback]: the steps the chat holds of it.
     */
    fun openAgentSession(payload: AgentSessionPayload, fallback: List<ChatEvent>?) {
        // Nothing the hub can stream without a session.
        if (payload.sessionId.isEmpty()) return
        if (fallback != null) agentFallbacks = agentFallbacks.with(payload.key, fallback)
        val store = windows
        val opening = if (store == null) AgentSessionOpening.Sheet else agentSessionOpening(store.state.value, payload.key, sheet = !wide)
        when (opening) {
            AgentSessionOpening.Sheet -> agentSheet = payload
            is AgentSessionOpening.Focus -> store?.focus(opening.windowId)
            is AgentSessionOpening.Open -> {
                opening.closeFirst?.let { store?.close(it) }
                store?.open(WindowKind.AgentSession, payload.toJson(), opening.rect)
            }
            AgentSessionOpening.Refused -> toast(ErrorToastMessage("Too many windows open", "Close one to open this session."))
        }
        pruneAgentFallbacks()
    }

    fun closeAgentSheet() {
        agentSheet = null
        pruneAgentFallbacks()
    }

    /** Lets go of the steps of every session no window or sheet shows any more. */
    private fun pruneAgentFallbacks() {
        agentFallbacks = agentFallbacks.pruned(agentSessionKeysOnScreen(windows?.state?.value?.windows?.values.orEmpty(), agentSheet))
    }

    // Tabs

    private fun openWorkspace(api: ApiClient) {
        val cache = WorkspaceCache(prefs, "workspace.$serverKey.", api.workspaceRemote(), scope, windowPanelsKey = "windowPanels.$windowStorageKey")
        // One set of windows per server, as the web keeps one per origin.
        val windowStore = WindowStore(prefs, "windows.$windowStorageKey")
        // Asked at each change, as the web asks `useIsMobile`: a phone opens every tab in the first panel.
        // A new chat tab is resolved from this device's cache before its first frame, as `openTab` resolves it.
        val chatCache = newChatCache
        val store = WorkspaceStore(cache, isMobile = { !wide }, windows = windowStore, warn = { PpmLog.w("PPM", "workspace: $it") }) { project ->
            project?.let { NewChatDefaults(chatCache.settings(), chatCache.providers(it)?.map(ProviderInfo::id)) }
        }
        workspaceCache = cache
        workspace = store
        windows = windowStore
        hydratedOnce = false
        reconciledFor = null
        workspaceJob = scope.launch {
            launch {
                windowStore.state.collect {
                    windowState = it
                    val liveExplorers = it.windows.values.filter { window -> window.kind == WindowKind.Explorer }.map { window -> window.id }.toSet()
                    for (id in hostExplorers.keys.toList()) {
                        if (id != "phone-explorer" && id !in liveExplorers) hostExplorers.remove(id)?.close()
                    }
                    reconcileWindowPanels()
                    pruneAgentFallbacks()
                }
            }
            store.state.collect { state ->
                syncChats(state)
                syncReviews(state)
                syncGroups(state)
                syncTerminals(state)
                syncDocuments(state)
                layout = state
                recordNavigation(state)
                reconcileWindowPanels()
            }
        }
    }

    /** A server's tabs go when another opens; their last changes still go out to it. */
    private fun closeWorkspace(flush: Boolean = true) {
        hostUploadQueue?.close()
        hostUploadQueue = null
        hostExplorers.values.forEach { it.close() }
        hostExplorers.clear()
        explorerSheetPath = null
        addingProject = false
        projectActions = null
        phoneProjectRename = null
        documents.values.forEach { it.close() }
        documents.clear()
        terminals.values.forEach { it.close() }
        terminals.clear()
        knownTerminalIds = emptySet()
        if (flush) workspaceCache?.flush()
        workspaceJob?.cancel()
        workspaceJob = null
        workspace = null
        workspaceCache = null
        layout = null
        windows = null
        windowState = WindowsState()
        // Another server's agent sessions are not this one's to stream.
        agentSheet = null
        agentFallbacks = AgentSessionFallbacks()
        chats.values.forEach { it.stop() }
        chats.clear()
        groupChats.values.forEach { it.chat.close() }
        groupChats.clear()
        shownSession = null
        waitingForWorkspace.clear()
    }

    /** Called only when a separate Android task finishes, not during Activity recreation. */
    internal fun disposeWindow(cleanupScope: CoroutineScope) {
        val cache = workspaceCache
        val closingChats = chats.values.toList()
        opening?.cancel()
        events?.stop()
        events = null
        fileTree?.close()
        hostFilesystem?.close()
        closeWorkspace(flush = false)
        cleanupScope.launch {
            try {
                withTimeoutOrNull(5_000) {
                    coroutineScope {
                        launch { cache?.flushPending() }
                        for (chat in closingChats) launch { chat.finishDraftSync() }
                    }
                }
            } finally { scope.coroutineContext[Job]?.cancel() }
        }
    }

    /**
     * Shows [name]'s tabs. The server's copy is read first on a server's first project and when
     * this device has none (app.tsx): an empty layout made before it arrived would be the newer
     * one, and would replace it on every device.
     */
    private suspend fun showWorkspace(name: String) {
        val store = workspace ?: return
        val cache = workspaceCache ?: return
        if (!hydratedOnce || !cache.has(name)) {
            hydratedOnce = true
            cache.hydrate(name)
        }
        if (workspace !== store || selectedProject != name) return
        store.switchProject(name)
        val waiting = waitingForWorkspace.toList()
        waitingForWorkspace.clear()
        for (open in waiting) open(store)
    }

    /** [withWorkspace] for the model's companions (`AppAssistant`). */
    internal fun onWorkspace(open: (WorkspaceStore) -> Unit) = withWorkspace(open)

    /**
     * Runs [open] on the selected project's layout: a tab opened before it is shown would land in no
     * project's. With no project selected it runs at once, on the layout that has no project: Settings,
     * a terminal and a host's file open there as they do on the web.
     */
    private fun withWorkspace(open: (WorkspaceStore) -> Unit) {
        val store = workspace ?: run {
            PpmLog.w("PPM", "workspace: no layout open, dropped a tab request (project $selectedProject)")
            return
        }
        if (store.state.value.currentProject == selectedProject) open(store) else {
            PpmLog.i("PPM", "workspace: tab request waits for $selectedProject (layout shows ${store.state.value.currentProject}, ${waitingForWorkspace.size} waiting)")
            waitingForWorkspace += open
        }
    }

    /** Back in front: another device may have changed this project's tabs meanwhile (the web reads them on every load). */
    private fun refreshWorkspace() {
        val store = workspace ?: return
        val cache = workspaceCache ?: return
        val project = store.state.value.currentProject ?: return
        scope.launch {
            if (cache.hydrate(project) && workspace === store && store.state.value.currentProject == project) store.reloadProject(project)
        }
    }

    /**
     * Follows the window's width; a wider window shows every grid panel, so their chats connect.
     * Narrowed past the desktop layout, the floating windows go, and a detached tab comes back
     * to the grid rather than wait in a window nothing shows (as the web's layer unmounting does).
     */
    fun setWindowWide(value: Boolean) {
        if (wide == value) return
        wide = value
        if (!value) workspace?.redockAllWindows()
        if (!value) moveWindowsToPhone()
        workspace?.state?.value?.let(::syncChats)
        reconcileWindowPanels()
    }

    /**
     * use-window-panel-reconcile.ts: detached tabs and their windows are saved apart, so a start
     * can bring back one without the other. Once per project and layout, the two are mended —
     * on a tablet after the windows are back (else every panel looks orphaned), on a phone at
     * once, where no window shows and every detached tab comes home.
     */
    private fun reconcileWindowPanels() {
        val store = workspace ?: return
        val project = store.state.value.currentProject ?: return
        if (wide && !windowState.restored) return
        val key = "$project|${!wide}"
        if (reconciledFor == key) return
        reconciledFor = key
        store.reconcileWindowPanels()
    }

    /**
     * Gives each chat tab in memory its chat, ends the chats of tabs that went (closed here or on
     * another device) and connects the ones on screen; the others connect once shown, then stay up.
     */
    private fun syncChats(state: WorkspaceState) {
        val live = HashMap<String, WorkspaceTab>()
        for (panel in state.panels.values) for (tab in panel.tabs) if (tab.type == "chat" || tab.type == "design" || assistantChatReady(tab)) live[tab.id] = tab
        for ((id, chat) in chats.toList()) {
            val tab = live[id]
            // A tab that went to another session is a new chat; one that got the session its first message made is the same.
            if (tab == null || tabSessionId(tab) != chat.session?.id) {
                chat.stop()
                chats.remove(id)
            }
        }
        for ((id, tab) in live) {
            val existing = chats[id]
            if (existing != null) {
                newChats.follow(existing, tab)
                continue
            }
            val project = tab.projectId ?: tab.metaString("projectName") ?: state.currentProject ?: continue
            val sessionId = tabSessionId(tab)
            val session = sessionId?.let { sessions.firstOrNull { it.id == sessionId } ?: SessionInfo(id = sessionId, providerId = tab.metaString("providerId") ?: "claude", title = tab.title) }
            chats[id] = ChatTab(id, session, project, session?.providerId ?: tab.metaString("providerId") ?: "claude").also { chat ->
                chat.permissionMode = PermissionMode.of(tab.metaString("permissionMode"))
                // Text handed to a new tab to be edited before it is sent (`pendingMessage`): a fork's, "Ask AI"'s.
                // A fork's quote becomes a reply in the fork, which copied the quoted message in.
                tab.metaString("pendingMessage")?.let { pending ->
                    val decoded = ChatReply.decode(pending)
                    chat.draft.setTextAndPlaceCursorAtEnd(decoded.content)
                    if (session != null) chat.replyTo = decoded.replyTo?.copy(sessionId = session.id)
                    chat.tookPendingMessage = true
                    chat.focusOnShow = true
                }
                (tab.metadata["pendingContexts"] as? JsonArray)?.forEach { entry ->
                    val context = entry as? JsonObject ?: return@forEach
                    val text = context["text"].string ?: return@forEach
                    chat.attachments += ComposerAttachment(
                        id = java.util.UUID.randomUUID().toString(), name = context["label"].string ?: "Selected code",
                        isImage = false, status = ComposerAttachment.Status.READY, textContent = text,
                    )
                    chat.tookPendingMessage = true
                    chat.focusOnShow = true
                }
                tab.metaString("pendingImageDataUrl")?.takeIf { it.startsWith("data:image/") && it.contains(";base64,") }?.let { dataUrl ->
                    val mediaType = dataUrl.substringAfter("data:").substringBefore(";base64,")
                    val b64 = dataUrl.substringAfter(";base64,")
                    if (b64.isNotEmpty()) {
                        chat.attachments += ComposerAttachment(
                            id = java.util.UUID.randomUUID().toString(),
                            name = tab.metaString("pendingImageName") ?: "design-screenshot.jpg",
                            isImage = true,
                            status = ComposerAttachment.Status.READY,
                            mediaType = mediaType,
                            probed = true,
                            imageData = com.hienlh.ppm.protocol.InlineImage(b64, mediaType),
                        )
                        chat.tookPendingMessage = true
                        chat.focusOnShow = true
                    }
                }
                chat.clientCommand = { text -> runClientCommand(chat, text) }
                chat.closeMic = { voice.closeFor(chat) }
                chat.firstSend = { message -> newChats.sendFirst(chat, message) }
                chat.editSend = { message -> edits.send(chat, message) }
                chat.prewarmTouch = {
                    newChats.touchPrewarm(chat)
                    notePresenceInput()
                }
                chat.tabOpener = { request -> answerAiTabOpen(chat, request) }
                if (tab.type == "assistant") assistant.wire(chat)
                chat.onAttention = { attention -> chatAttention(chat, attention) }
                chat.defaultsFor = { provider -> newChatCache.settings()?.defaults?.get(provider) }
                chat.modePicked = { mode -> patchTabMetadata(id, "permissionMode" to JsonPrimitive(mode.value), "permissionModeSource" to JsonPrimitive("user")) }
                chat.changesListed = { sessionId, list ->
                    for (review in reviews.values) if (review.sessionId == sessionId && review.project == project) review.takeLive(list)
                }
                newChats.follow(chat, tab)
            }
        }
        val active = state.activeGridTab
        val api = api
        if (api != null) {
            for (tab in shownTabs(state)) {
                val chat = chats[tab.id]?.takeUnless { it.started } ?: continue
                // Prepared first, so its draft and its gate join that one request.
                newChats.prepare(chat)
                chat.start(api, scope, DraftLocalStore(draftPrefs, serverKey), newChats.preparedDraft(chat), sessionTurns)
                chat.turnLinks.start(api, scope, { name -> projects.any { it.name == name } }) { title, detail -> toast(ErrorToastMessage(title, detail)) }
                newChats.started(chat)
            }
        }
        // A chat that comes forward by itself (restored, or next after a close) loses its unread
        // unless it was marked by hand; one the user picks loses it either way (`activate`).
        val sessionId = tabSessionId(active)
        if (sessionId != shownSession) {
            shownSession = sessionId
            if (sessionId != null && windowVisible) events?.seen(sessionId)
        }
    }

    /**
     * The tabs on screen: a phone shows the focused panel's; the desktop layout shows each grid
     * panel's, and each floating window's (one in the dock too: its tab stays live, as on the web).
     */
    private fun shownTabs(state: WorkspaceState): List<WorkspaceTab> = if (wide) {
        (state.gridPanelIds + state.panels.keys.filter(::isWindowPanelId)).mapNotNull { state.panels[it]?.activeTab }
    } else {
        listOfNotNull(state.activeGridTab)
    }

    /**
     * Gives each session-review tab in memory its review and ends those of tabs that went; a
     * review starts once its tab is first shown, then follows the tab's metadata until it closes.
     */
    private fun syncReviews(state: WorkspaceState) {
        val live = HashMap<String, WorkspaceTab>()
        for (panel in state.panels.values) for (tab in panel.tabs) if (tab.type == "session-review") live[tab.id] = tab
        for ((id, review) in reviews.toList()) {
            val tab = live[id]
            if (tab == null || tab.metaString("projectName") != review.project || tab.metaString("sessionId") != review.sessionId) {
                review.stop()
                reviews.remove(id)
            }
        }
        for ((id, tab) in live) {
            reviews[id]?.let { it.follow(tab); continue }
            // A tab that names no session shows "No chat session." and holds nothing.
            val project = tab.metaString("projectName")?.takeIf { it.isNotEmpty() } ?: continue
            val sessionId = tab.metaString("sessionId")?.takeIf { it.isNotEmpty() } ?: continue
            reviews[id] = ReviewTab(id, project, sessionId).also { it.follow(tab) }
        }
        val api = api ?: return
        for (tab in shownTabs(state)) {
            val review = reviews[tab.id]?.takeUnless { it.started } ?: continue
            review.start(api, scope, sessionTurns) { reviewAnswered(review.project, review.sessionId) }
        }
    }

    /** The Review tab of a session-review tab; none for a tab that names no session. */
    fun review(tabId: String): ReviewTab? = reviews[tabId]

    /**
     * Gives a group tab its group chat once the tab is first shown, as the web mounts it, and
     * closes the chats of tabs that went or that name another group now.
     */
    private fun syncGroups(state: WorkspaceState) {
        val live = HashMap<String, WorkspaceTab>()
        for (panel in state.panels.values) for (tab in panel.tabs) if (tab.type == "group") live[tab.id] = tab
        for ((id, group) in groupChats.toList()) {
            val tab = live[id]
            if (tab == null || tab.metaString("groupId") != group.chat.groupId || groupProject(tab) != group.chat.project) {
                group.chat.close()
                groupChats.remove(id)
            }
        }
        val api = api ?: return
        for (tab in shownTabs(state)) {
            if (tab.type != "group" || tab.id in groupChats) continue
            val groupId = tab.metaString("groupId")?.takeIf { it.isNotEmpty() } ?: continue
            groupChats[tab.id] = GroupTab(GroupChat(api, groupProject(tab), groupId, scope).also { it.start() })
        }
    }

    /** The project a group tab names, else the open one (group-chat-tab.tsx). */
    private fun groupProject(tab: WorkspaceTab): String = tab.metaString("projectName") ?: selectedProject.orEmpty()

    /** The group chat of a group tab; none for a tab that names no group, or one not shown yet. */
    fun group(tabId: String): GroupTab? = groupChats[tabId]

    /** A group's tab (group-list.tsx), named after it; a group already open comes to the front. */
    fun openGroup(project: String, group: Group) {
        withWorkspace { store ->
            store.openTab(
                NewTab(
                    type = "group",
                    title = group.name,
                    projectId = project,
                    metadata = buildJsonObject {
                        put("groupId", group.id)
                        put("projectName", project)
                    },
                ),
            )
        }
    }

    /** An answer landed: the session's changes bars ask again, those that ever asked (`announceReviewMarks`). */
    private fun reviewAnswered(project: String, sessionId: String) {
        for (chat in chats.values) {
            if (chat.project != project || chat.session?.id != sessionId) continue
            chat.sessionChanges?.takeIf { it.loaded.value }?.refresh()
        }
    }

    /** The Review tab's link back to its chat (`openChat`): the session's tab brought forward, or opened. */
    fun openReviewChat(review: ReviewTab) {
        withWorkspace { store ->
            store.openTab(
                NewTab(
                    type = "chat",
                    title = review.chatTitle ?: "Chat",
                    projectId = review.project,
                    metadata = buildJsonObject {
                        put("projectName", review.project)
                        put("sessionId", review.sessionId)
                        review.providerId?.let { put("providerId", it) }
                    },
                ),
            )
        }
    }

    /** A turn chip's "Show in chat": its call's card, or the turn's prompt, once the chat shows it. */
    fun showInChat(review: ReviewTab, messageId: String, call: String) {
        chatJump = ChatJumpRequest(review.sessionId, call, messageId, monotonicMillis())
        openReviewChat(review)
    }

    /** The chat took [request] up, or gave up on it; a newer request stays. */
    fun chatJumpDone(request: ChatJumpRequest) {
        if (chatJump === request) chatJump = null
    }

    /** A turn chip or turn link (`openTurnRef`): its session's chat shown, then asked to bring the turn on screen. */
    fun openTurnRef(projectName: String, providerId: String, sessionId: String, turnId: String, title: String) {
        if (projects.none { it.name == projectName }) return toast(ErrorToastMessage("Project \"$projectName\" is not in this PPM"))
        chatJump = ChatJumpRequest(sessionId, null, null, monotonicMillis(), turnId)
        openLinkedSession(projectName, sessionId, providerId, title)
    }

    /** The tab bar's tabs, the phone's merged one: every grid panel's, this project's, with the panel each is in. */
    val openTabs: List<Pair<WorkspaceTab, String>> get() = layout?.mergedTabs.orEmpty()

    /** The tab the main area shows. */
    val activeTab: WorkspaceTab? get() = layout?.activeGridTab
    val activeTabId: String? get() = activeTab?.id

    /** Read from the store rather than [layout]: the global socket asks between two frames, and the trace from any thread. */
    internal val activeSessionId: String? get() = tabSessionId(workspace?.state?.value?.activeGridTab)

    /** The chat of a chat tab; none for a tab whose chat has not started. */
    fun chat(tabId: String): ChatTab? = chats[tabId]

    /** `openPanel`: the output of a command [tabId]'s chat lists. */
    fun openBackgroundOutput(tabId: String, shellId: String) {
        backgroundOutput = BackgroundOutputRequest(tabId, shellId)
    }

    fun closeBackgroundOutput() {
        backgroundOutput = null
    }

    /**
     * Signs in to [serverName] as a chat in [project] sees it: started at once and cancelled if the
     * dialog closes first. A claude.ai connector is connected on claude.ai instead, with no flow.
     */
    fun openMcpSignIn(project: String?, serverName: String) {
        val api = api ?: return
        mcpSignIn?.flow?.close()
        val flow = if (McpServerName.isClaudeAiConnector(serverName)) {
            null
        } else {
            McpSignInFlow(serverName, McpSignInBackend.api(api, project), scope, onSignedIn = ::undismissMcpSignIn).also { it.start() }
        }
        mcpSignIn = McpSignIn(serverName, flow)
    }

    fun closeMcpSignIn() {
        mcpSignIn?.flow?.close()
        mcpSignIn = null
    }

    /** The sign-in bar's ×: [names] hidden in every chat until one of them is signed in to. */
    fun dismissMcpSignIn(names: List<String>) {
        storeMcpSignInDismissed(McpSignInDismissals.dismissing(names, mcpSignInDismissed))
        toast(ErrorToastMessage(
            "Sign-in bar hidden",
            "You can still sign in to these MCP servers from AI Resources → MCP. The bar comes back only for a server that is not on this list.",
            kind = ErrorToastMessage.Kind.INFO,
        ))
    }

    /** Signed in, so no longer a server the user chose to ignore: should it need a sign-in again, the bar says so. */
    private fun undismissMcpSignIn(name: String) {
        McpSignInDismissals.undismissing(name, mcpSignInDismissed)?.let(::storeMcpSignInDismissed)
    }

    private fun storeMcpSignInDismissed(names: List<String>) {
        mcpSignInDismissed = names
        saveUiPrefs()
        putUiPref { put("mcpSignInDismissed", JsonArray(names.map(::JsonPrimitive))) }
    }

    /**
     * Hides or shows linked sessions in [chatId]'s list (`setSessionsUnlinked`), synced across
     * devices via `/api/settings/ui-prefs`.
     */
    fun setSessionsUnlinked(
        chatId: String,
        ids: Collection<String>,
        unlink: Boolean,
        atProvider: (String) -> Long = { 0L },
    ) {
        val next = updateUnlinks(unlinkedSessions, chatId, ids, unlink, atProvider)
        if (next == unlinkedSessions) return
        unlinkedSessions = next
        saveUiPrefs()
        putUiPref { put("unlinkedSessions", LinkedSessionUnlinks.encode(next)) }
    }

    /** Seeds `/ws/global`'s link map for [sessionId] from `/chat/sessions/:id/links`. */
    suspend fun seedSessionLinks(projectName: String, sessionId: String) {
        val client = api ?: return
        val links = quietly { client.sessionLinks(projectName, sessionId) } ?: return
        if (links.isNotEmpty()) events?.seedSessionLinks(sessionId, links)
    }

    /** Stops every running direct child session created by [sessionId]. */
    suspend fun stopChildSessions(projectName: String, sessionId: String) {
        val client = api ?: throw IllegalStateException("Not connected")
        client.stopChildSessions(projectName, sessionId)
    }

    /**
     * Opens a linked session (`openLinkedSession`, `openChatSession`): its chat tab in the current
     * workspace when it is this project's; another project's in a floating window on a tablet, and
     * through the project switch on a phone (`planChatOpen`); an Assistant session in the Assistant.
     */
    fun openLinkedSession(projectName: String, sessionId: String, providerId: String, title: String) {
        val canonical = if (sessionId.startsWith("chat:")) sessionId.substringAfter('/') else sessionId
        val resolvedProvider = providerId.ifEmpty {
            sessions.firstOrNull { it.id == canonical }?.providerId ?: "claude"
        }
        val resolvedTitle = title.ifEmpty { "Chat" }
        if (projectName != selectedProject) {
            val plan = planChatOpen(projectName, selectedProject, mobile = !wide || windows == null)
            if (plan == ChatOpenPlan.ASSISTANT) return assistant.open(canonical, resolvedProvider)
            if (projects.none { it.name == projectName }) return
            if (plan == ChatOpenPlan.WINDOW) {
                // At the window cap the chat still opens: the screen switches to its project instead.
                return openChatInWindow(projectName, canonical, resolvedProvider, resolvedTitle) {
                    switchToLinkedSession(projectName, canonical, resolvedProvider, resolvedTitle)
                }
            }
            switchToLinkedSession(projectName, canonical, resolvedProvider, resolvedTitle)
            return
        }
        withWorkspace { store ->
            store.openTab(
                NewTab(
                    type = "chat",
                    title = resolvedTitle,
                    projectId = projectName,
                    metadata = buildJsonObject {
                        put("projectName", projectName)
                        put("sessionId", canonical)
                        put("providerId", resolvedProvider)
                    },
                ),
            )
            events?.markRead(canonical)
        }
    }

    /** The phone's way to another project's chat: the screen switches to [projectName], then its tab opens. */
    private fun switchToLinkedSession(projectName: String, canonical: String, resolvedProvider: String, resolvedTitle: String) {
        scope.launch {
            openProject(projectName) { store ->
                store.openTab(
                    NewTab(
                        type = "chat",
                        title = resolvedTitle,
                        projectId = projectName,
                        metadata = buildJsonObject {
                            put("projectName", projectName)
                            put("sessionId", canonical)
                            put("providerId", resolvedProvider)
                        },
                    ),
                )
                events?.markRead(canonical)
            }
        }
    }

    /** `findByOutput`: the open chat tab and command a `.output` file name belongs to, across every chat. */
    private fun backgroundShellFor(name: String): BackgroundOutputRequest? {
        for ((id, chat) in chats) {
            val shells = chat.controller?.state?.value?.backgroundShells ?: continue
            BackgroundOutput.findByOutput(name, shells.asSequence())?.let { return BackgroundOutputRequest(id, it.shellId) }
        }
        return null
    }

    /** The grid panels in grid order, for the tab switcher's "Panel N" groups. */
    val gridPanelIds: List<String> get() = layout?.gridPanelIds.orEmpty()

    /** The tab switcher's Recent order (`mobile-nav.tsx`): the latest shown first, the never-shown last in tab order. */
    fun recentOrder(tabs: List<Pair<WorkspaceTab, String>>): List<Pair<WorkspaceTab, String>> =
        tabs.withIndex()
            .sortedWith(compareByDescending<IndexedValue<Pair<WorkspaceTab, String>>> { it.value.first.metadata["lastActiveAt"].double ?: -1.0 }.thenBy { it.index })
            .map { it.value }

    private fun panelOf(tabId: String): String? = openTabs.firstOrNull { it.first.id == tabId }?.second

    /**
     * Opens a session in a tab, or brings its tab forward (`openSessionInItsTab`): in [panelId]
     * when given, else in the focused panel (a phone's first).
     */
    fun openTab(session: SessionInfo, panelId: String? = null) {
        val project = selectedProject ?: run {
            PpmLog.w("PPM", "workspace: no project selected, session ${session.id} not opened")
            return
        }
        withWorkspace { store ->
            store.openTab(
                NewTab(
                    type = "chat",
                    title = session.title.ifEmpty { "Chat" },
                    projectId = project,
                    metadata = buildJsonObject {
                        put("projectName", project)
                        put("sessionId", session.id)
                        put("providerId", session.providerId)
                    },
                ),
                panelId,
            )
            events?.markRead(session.id)
        }
    }

    /**
     * A row of the bell (`handleGoToSession`): the session's chat in its project, its tab brought
     * forward when it is open there. A session filed under no project the server has opens nothing,
     * and says so ([false]) so the list stays. The web names every such chat Claude's; this names
     * the provider it knows.
     */
    fun openUnreadSession(sessionId: String, projectName: String): Boolean {
        if (projects.none { it.name == projectName }) return false
        val providerId = sessions.firstOrNull { it.id == sessionId }?.providerId ?: "claude"
        scope.launch {
            openProject(projectName) { store ->
                store.openTab(
                    NewTab(
                        type = "chat",
                        title = "Chat",
                        projectId = projectName,
                        metadata = buildJsonObject {
                            put("projectName", projectName)
                            put("sessionId", sessionId)
                            put("providerId", providerId)
                        },
                    ),
                )
            }
        }
        return true
    }

    /** `useUrlSync`'s pushState: the tab in front (the focused panel's, as the web's address follows it), in its project. */
    private fun recordNavigation(state: WorkspaceState) {
        val project = state.currentProject ?: return
        val tabId = state.panels[state.focusedPanelId]?.activeTabId ?: return
        history.record(NavEntry(project, tabId))
        canGoBack = history.canGoBack
    }

    /**
     * Android's Back, as the browser's: the tab shown before, in its own project (the web stays in
     * the open one and opens the tab there), opened again from its address if it was closed since.
     * An entry that cannot be shown any more is passed over.
     */
    fun goBack() {
        while (true) {
            val entry = history.back() ?: break
            if (showEntry(entry)) break
        }
        canGoBack = history.canGoBack
    }

    private fun showEntry(entry: NavEntry): Boolean {
        if (projects.none { it.name == entry.project }) return false
        if (entry.project != selectedProject) {
            scope.launch { openProject(entry.project) { store -> showTab(store, entry) } }
            return true
        }
        return showTab(workspace ?: return false, entry)
    }

    private fun showTab(store: WorkspaceStore, entry: NavEntry): Boolean {
        val state = store.state.value
        val panel = (state.gridPanelIds + DOCK_PANEL_ID).mapNotNull { state.panels[it] }.firstOrNull { panel -> panel.tabs.any { it.id == entry.tabId } }
        if (panel != null) {
            activate(entry.tabId, panel.id)
            return true
        }
        val tab = reopenedTab(entry.tabId, entry.project) ?: return false
        if (tab.type == "settings") openSettings() else openNewTab(store, tab)
        return true
    }

    /**
     * A `ppm://<host>[:<port>]/project/{name}/{type}/{id}` link, opened as the web opens its own
     * address at start: the link's server if it is not the open one, then the project, then the tab.
     */
    fun openLink(link: String) {
        val parsed = parsePpmLink(link) ?: run {
            actionError("This is not a PPM link: $link")
            return
        }
        val where = parsed.host + (parsed.port?.let { ":$it" } ?: "")
        fun matches(url: HttpUrl) = url.host.equals(parsed.host, ignoreCase = true) && (parsed.port == null || parsed.port == url.port)
        if (debug != null) {
            if (!matches(debug.url)) {
                actionError("This link is for $where, not for the server open here.")
                return
            }
        } else {
            val server = store.servers.value.firstOrNull { ApiClient.normalizedServerUrl(it.url)?.let(::matches) == true } ?: run {
                actionError("No server at $where is saved here. Add it in Settings, Servers.")
                return
            }
            if (store.selected?.id != server.id) {
                pendingLink = server.id to parsed.state
                store.select(server)
                return
            }
        }
        if (projectsLoad != Load.Loaded) {
            pendingLink = serverKey to parsed.state
            return
        }
        parsed.state.assistant?.let { a -> return openAssistant(a.sessionId, a.providerId) }
        val name = parsed.state.projectName ?: return
        if (projects.none { it.name == name }) {
            actionError(noSuchProject(name))
            return
        }
        scope.launch { openProject(name) { store -> openFromLink(store, parsed.state, name) } }
    }

    private fun noSuchProject(name: String) = "There is no project called \"$name\" on this server."

    /** The link's tab, then its legacy `?openChat=`, as app.tsx opens them. */
    private fun openFromLink(store: WorkspaceStore, link: UrlState, project: String) {
        link.tabType?.let { openFromUrl(store, it, link.tabIdentifier, project) }
        link.openChat?.let { openFromUrl(store, "chat", it, project) }
        // `?openTask=<id>` (Telegram's "Open in PPM" on a task card): the task in its tab, kept.
        link.openTask?.let { tasks.openTask(it, project, "Task", null, keep = true) }
    }

    /** `autoOpenFromUrl`: the focused panel's tab with the address's id, or a new tab built from the address. */
    private fun openFromUrl(store: WorkspaceStore, type: String, identifier: String?, project: String) {
        if (type == "settings") return openSettingsWhenWindowsReady()
        val state = store.state.value
        val focused = state.panels[state.focusedPanelId]
        val expected = tabIdFromUrl(type, identifier)
        if (focused != null && focused.tabs.any { it.id == expected }) return activate(expected, focused.id)
        val metadata = buildMetadataFromUrl(type, identifier, project) ?: return
        openNewTab(store, NewTab(type, buildTitleFromUrl(type, identifier), projectId = project, metadata = metadata))
    }

    private fun openNewTab(store: WorkspaceStore, tab: NewTab) {
        store.openTab(tab)
        tab.metadata["sessionId"]?.jsonPrimitive?.contentOrNull?.let { events?.markRead(it) }
    }

    /** Shows a tab the user picked: its chat's unread clears even when it was marked by hand. */
    fun activate(tabId: String, panelId: String? = panelOf(tabId)) {
        val store = workspace ?: return
        store.setActiveTab(tabId, panelId)
        tabSessionId(panelId?.let { store.state.value.panels[it] }?.tab(tabId))?.let { events?.markRead(it) }
    }

    fun pickTabSort(mode: String) {
        tabSortMode = mode
        prefs.put("tabSortMode", mode)
    }

    /**
     * open-settings.ts: Settings in a floating window on a tablet — the one already open comes
     * forward, as there is only one set of settings — and as a tab on a phone.
     */
    fun openSettings(category: String? = null) {
        if (!category.isNullOrBlank()) {
            requestedSettingsCategory = category
        }
        val windows = windows
        if (!wide || windows == null) {
            withWorkspace {
                it.openTab(
                    NewTab(
                        type = "settings",
                        title = "Settings",
                        metadata = if (!category.isNullOrBlank()) buildJsonObject { put("category", category) } else JsonObject(emptyMap()),
                    ),
                )
            }
            return
        }
        val existing = windows.state.value.windows.values.firstOrNull { it.kind == WindowKind.Settings }
        val payload = if (!category.isNullOrBlank()) buildJsonObject { put("category", category) } else null
        when {
            existing != null -> {
                if (payload != null) windows.setPayload(existing.id, payload)
                windows.focus(existing.id)
            }
            // At the cap the web's `open` raises the oldest window instead, which is not Settings.
            windows.state.value.windows.size >= MAX_WINDOWS -> actionError(TOO_MANY_WINDOWS)
            else -> if (payload != null) windows.open(WindowKind.Settings, payload) else windows.open(WindowKind.Settings)
        }
    }

    /** Opens System Monitor in a floating window on tablet or as a tab on phone. */
    fun openSystemMonitor(tab: String = "overview") {
        val windows = windows
        val payload = buildJsonObject { put("sysmonTab", tab) }
        if (!wide || windows == null) {
            withWorkspace { it.openTab(NewTab("system-monitor", "System Monitor", metadata = payload)) }
            return
        }
        val existing = windows.state.value.windows.values.firstOrNull { it.kind == WindowKind.SystemMonitor }
        when {
            existing != null -> {
                windows.setPayload(existing.id, payload)
                windows.focus(existing.id)
            }
            windows.state.value.windows.size >= MAX_WINDOWS -> actionError(TOO_MANY_WINDOWS)
            else -> windows.open(WindowKind.SystemMonitor, payload)
        }
    }

    /** Opens Logs in a floating window on tablet or as a tab on phone (`open-logs.ts`). */
    fun openLogs(view: String = "logs", chat: String? = null) {
        val windows = windows
        val payload = buildJsonObject {
            put("logsView", view)
            if (!chat.isNullOrEmpty()) put("logsChat", chat)
        }
        if (!wide || windows == null) {
            withWorkspace {
                it.openTab(NewTab("logs", "Logs", metadata = payload))
            }
            if (!chat.isNullOrEmpty()) {
                val logsTab = workspace?.state?.value?.panels?.values?.asSequence()?.flatMap { p -> p.tabs }?.firstOrNull { t -> t.type == "logs" }
                if (logsTab != null) {
                    patchTabMetadata(logsTab.id, "logsView" to JsonPrimitive(view), "logsChat" to JsonPrimitive(chat))
                }
            }
            return
        }
        val existing = windows.state.value.windows.values.firstOrNull { it.kind == WindowKind.Logs }
        when {
            existing != null -> {
                windows.setPayload(existing.id, payload)
                windows.focus(existing.id)
            }
            windows.state.value.windows.size >= MAX_WINDOWS -> actionError(TOO_MANY_WINDOWS)
            else -> windows.open(WindowKind.Logs, payload)
        }
    }

    /** Opens Remote Desktop in a floating window on tablet or as a tab on phone (`open-remote-desktop.ts`). */
    fun openRemoteDesktop() {
        val windows = windows
        if (!wide || windows == null) {
            withWorkspace { it.openTab(NewTab("remote-desktop", "Remote Desktop")) }
            return
        }
        val existing = windows.state.value.windows.values.firstOrNull { it.kind == WindowKind.RemoteDesktop }
        when {
            existing != null -> windows.focus(existing.id)
            windows.state.value.windows.size >= MAX_WINDOWS -> actionError(TOO_MANY_WINDOWS)
            else -> windows.open(WindowKind.RemoteDesktop)
        }
    }

    /** Opens or focuses the Android Device / Emulator Controller workspace tab (`open-android.ts`). */
    fun openAndroid() {
        withWorkspace { store ->
            for ((panelId, panel) in store.state.value.panels) {
                val existing = panel.tabs.firstOrNull { it.type == "android" }
                if (existing != null) {
                    activate(existing.id, panelId)
                    return@withWorkspace
                }
            }
            store.openTab(NewTab("android", "Android"))
        }
    }

    /** Updates an `"android"` tab's pinned `avdId` metadata and tab title (`android-tab.tsx`). */
    fun updateAndroidTabDevice(tabId: String, avdId: String?, title: String) {
        val store = workspace ?: return
        store.updateTab(tabId) {
            it.copy(title = title).withMetadata("avdId" to avdId?.let(::JsonPrimitive))
        }
    }

    /** Opens the Port Forwarding sidebar section. */
    fun openPortForwarding() {
        pickSidebarSection("tunnels")
    }

    /**
     * Opens or focuses a Design Mode tab (`open-design-tab.ts`), popping it out into a floating
     * window on tablet when [designWindows] is enabled.
     */
    fun openDesignTab(
        slug: String,
        title: String,
        projectName: String? = selectedProject,
        sessionId: String? = null,
        providerId: String? = null,
        fresh: Boolean = false,
    ) {
        val proj = projectName ?: selectedProject ?: return
        val tabId = "design:$slug"
        withWorkspace { store ->
            val state = store.state.value
            // Check if already in a floating window panel
            for ((panelId, panel) in state.panels) {
                if (!isWindowPanelId(panelId)) continue
                val existing = panel.tabs.firstOrNull { it.id == tabId }
                if (existing != null) {
                    val winId = panelId.removePrefix("window:")
                    windows?.focus(winId)
                    store.setActiveTab(tabId, panelId)
                    return@withWorkspace
                }
            }
            // Check if already in a grid panel
            for ((panelId, panel) in state.panels) {
                if (panelId !in state.gridPanelIds) continue
                val existing = panel.tabs.firstOrNull { it.id == tabId }
                if (existing != null) {
                    if (wide && designWindows) {
                        store.popOutTab(tabId, panelId)
                    } else {
                        activate(tabId, panelId)
                    }
                    return@withWorkspace
                }
            }
            val meta = buildJsonObject {
                put("designSlug", slug)
                put("projectName", proj)
                if (!sessionId.isNullOrBlank()) put("sessionId", sessionId)
                if (!providerId.isNullOrBlank()) put("providerId", providerId)
                if (fresh) put("fresh", true)
            }
            store.openTab(
                NewTab(
                    type = "design",
                    title = title.ifBlank { slug },
                    projectId = proj,
                    metadata = meta,
                ),
            )
            if (!sessionId.isNullOrBlank()) events?.markRead(sessionId)
            if (wide && designWindows) {
                val afterState = store.state.value
                val hostPanelId = afterState.gridPanelIds.firstOrNull { pid ->
                    afterState.panels[pid]?.tabs?.any { it.id == tabId } == true
                }
                if (hostPanelId != null) {
                    store.popOutTab(tabId, hostPanelId)
                }
            }
        }
    }

    /**
     * "Build in new chat" handoff (`design-tab.tsx`): opens a fresh chat tab with [prompt]
     * pre-filled in its composer and optional [screenshotDataUrl] attached inline.
     */
    fun openBuildInNewChatTab(
        projectName: String,
        title: String,
        prompt: String,
        screenshotDataUrl: String? = null,
        screenshotFilename: String = "design.jpg",
    ) {
        withWorkspace { store ->
            store.openTab(
                NewTab(
                    type = "chat",
                    title = "Build: $title",
                    projectId = projectName,
                    metadata = buildJsonObject {
                        put("projectName", projectName)
                        put("pendingMessage", prompt)
                        if (!screenshotDataUrl.isNullOrBlank()) {
                            put("pendingImageDataUrl", screenshotDataUrl)
                            put("pendingImageName", screenshotFilename)
                        }
                    },
                ),
            )
        }
    }

    /** Opens a forwarded port or URL in a web-preview tab. */
    fun openWebPreviewTab(url: String, port: Int? = null, via: String = "cloudflare") {
        val title = if (port != null) "localhost:$port" else buildTitleFromUrl("web-preview", url)
        val meta = buildJsonObject {
            put("url", url)
            put("currentUrl", url)
            put("via", via)
            if (port != null) put("port", port)
        }
        withWorkspace { it.openTab(NewTab(type = "web-preview", title = title, metadata = meta)) }
    }

    /** close-window.ts: a window's ×, Escape on its titlebar; a detached tab goes back to the grid. */
    fun closeWindow(id: String) {
        workspace?.closeWindow(id)
    }

    /**
     * tab-pop-out-menu-item.tsx, "Open in window": the tab goes on, live, in a floating window of
     * its own, and back to [panelId] when the window closes. The menu offers it only where it can
     * work, so a refusal is the shared window cap.
     */
    fun popOutTab(
        tabId: String,
        panelId: String,
        rect: com.hienlh.ppm.state.windows.Rect? = null,
        closeTabsOnClose: Boolean = false,
    ) {
        val store = workspace ?: return
        if (store.popOutTab(tabId, panelId, closeTabsOnClose, rect) == null) actionError(TOO_MANY_WINDOWS)
    }

    /**
     * Settings from a link: on a tablet once the saved windows are back, since bringing them back
     * replaces whatever is open (`openSettingsOnceWindowLayerIsReady`).
     */
    private fun openSettingsWhenWindowsReady() {
        scope.launch {
            snapshotFlow { !wide || windowState.restored }.first { it }
            openSettings()
        }
    }

    /** Opens the command palette, its search holding [query]. */
    fun openPalette(query: String = "") {
        paletteQuery = query
    }

    fun closePalette() {
        paletteQuery = null
    }

    /** Opens or focuses an AI resource editor tab (`src/web/components/ai-resources/open-resource-tab.ts`). */
    fun openAiResourceTab(item: com.hienlh.ppm.state.settings.AiResourceItem, projectPath: String) {
        withWorkspace { store ->
            val st = store.state.value
            for (pid in st.gridPanelIds) {
                val existing = st.panels[pid]?.tabs?.firstOrNull { tab ->
                    tab.type == "ai-resource" && tab.metadata["filePath"].string == item.filePath
                }
                if (existing != null) {
                    store.setActiveTab(existing.id, pid)
                    store.setFocusedPanel(pid)
                    return@withWorkspace
                }
            }
            store.openTab(
                NewTab(
                    type = "ai-resource",
                    title = item.name,
                    projectId = null,
                    closable = true,
                    metadata = buildJsonObject {
                        put("filePath", item.filePath)
                        put("name", item.name)
                        put("resourceType", item.type)
                        put("scope", item.scope)
                        put("readOnly", item.readOnly)
                        put("shadowed", item.shadowed)
                        item.shadowedBySource?.let { src ->
                            put("shadowedBy", buildJsonObject { put("source", src) })
                        }
                        put("project", projectPath)
                    },
                ),
            )
        }
    }

    /** Alt+] / Alt+[: the focused panel's next or previous tab, round the ends. */
    fun cycleTab(forward: Boolean) {
        val state = layout ?: return
        val panelId = state.focusedPanelId.takeIf { it in state.gridPanelIds } ?: state.gridPanelIds.firstOrNull() ?: return
        val panel = state.panels[panelId] ?: return
        val tabs = panel.tabs
        if (tabs.size < 2) return
        val index = tabs.indexOfFirst { it.id == panel.activeTabId }
        activate(tabs[if (forward) (index + 1) % tabs.size else (index - 1 + tabs.size) % tabs.size].id, panelId)
    }

    /** Mod+1…9: the project at that place in the server's list, as the web counts them. */
    fun openProjectAt(position: Int) {
        val project = projects.getOrNull(position - 1) ?: return
        scope.launch { openProject(project.name) }
    }

    /** Alt+↑ / Alt+↓ move the chat on screen in the focused panel, and no other (`ownsGlobalShortcut`). */
    fun jumpInChat(direction: Int) {
        val tabId = activeTabId ?: return
        chatJumps.tryEmit(tabId to direction)
    }

    /** Opens or closes the mic of the chat in front, which does nothing for a tab that is not a chat. */
    fun toggleVoiceInput() {
        val tabId = activeTabId ?: return
        voiceToggles.tryEmit(tabId)
    }

    fun selectVoiceEngine(engine: VoiceEngine) {
        voiceEngine = engine
        prefs.put("voiceEngine", engine.id)
    }

    private fun onTabClosing(tabId: String) {
        com.hienlh.ppm.state.database.clearUnsavedGridRows(tabId)
        val tab = tabOf(tabId) ?: return
        if (tab.type == "extension") {
            val viewType = tab.metaString("viewType")
            val projName = tab.metaString("projectName") ?: tab.projectId
            extensions?.coordinator?.recordWebviewClosed(viewType, projName)
            tab.metaString("panelId")?.takeIf { it.isNotEmpty() }?.let { extPanelId ->
                extensions?.closeWebview(extPanelId)
            }
        }
    }

    data class PendingTabClose(
        val panelId: String?,
        val tabs: List<WorkspaceTab>,
        val losingTabs: List<WorkspaceTab>,
    )

    var pendingTabClose by mutableStateOf<PendingTabClose?>(null)
        private set

    private val tabCloseConfirmOverlay: @Composable () -> Unit = {
        com.hienlh.ppm.app.ui.database.DbTabCloseConfirmHost(this)
    }

    fun confirmPendingTabClose() {
        val pending = pendingTabClose ?: return
        pendingTabClose = null
        if (screenOverlay === tabCloseConfirmOverlay) screenOverlay = null
        val store = workspace ?: return
        for (t in pending.tabs) {
            val pid = pending.panelId ?: panelOf(t.id)
            if (tabOf(t.id) != null) {
                onTabClosing(t.id)
                store.closeTab(t.id, pid)
            }
        }
    }

    fun cancelPendingTabClose() {
        pendingTabClose = null
        if (screenOverlay === tabCloseConfirmOverlay) screenOverlay = null
    }

    fun closeTab(tabId: String, panelId: String? = panelOf(tabId), force: Boolean = false) {
        val tab = tabOf(tabId)
        if (!force && tab != null && com.hienlh.ppm.state.database.losesDbWorkOnClose(tab)) {
            pendingTabClose = PendingTabClose(panelId = panelId, tabs = listOf(tab), losingTabs = listOf(tab))
            if (screenOverlay == null) screenOverlay = tabCloseConfirmOverlay
            return
        }
        onTabClosing(tabId)
        workspace?.closeTab(tabId, panelId)
    }

    // The desktop layout's panels and dock

    /** A touch in a panel focuses it, as a mouse-down does on the web: the next tab opens there. */
    fun focusPanel(panelId: String) {
        val store = workspace ?: return
        if (store.state.value.focusedPanelId != panelId) store.setFocusedPanel(panelId, rememberChat = false)
    }

    /** An empty panel's Close Panel; what it still holds goes to the panel before it. */
    fun closePanel(panelId: String) {
        workspace?.closePanel(panelId)
    }

    /** The open project's dock tabs; the dock holds every project's. */
    val dockTabs: List<WorkspaceTab>
        get() {
            val state = layout ?: return emptyList()
            return state.dockPanel?.tabs.orEmpty().filter { it.projectId.isNullOrEmpty() || state.currentProject == null || it.projectId == state.currentProject }
        }

    /** Showing an empty dock opens the project's first terminal, as on the web. */
    fun setDockVisible(visible: Boolean) {
        workspace?.setDockVisible(visible)
        if (visible && dockTabs.isEmpty()) openTerminal(inDock = true)
    }

    /** The dock's share of the main area. The layout keeps it within 15–85 %: what it kept is returned. */
    fun setDockHeight(percent: Double): Double? {
        val store = workspace ?: return null
        store.setDockHeight(percent)
        return store.state.value.dock.height
    }

    /** Maximize / restore: for this run only, as on the web. */
    fun toggleDockExpanded() {
        workspace?.toggleDockExpanded()
    }

    fun pickDockPosition(position: String) {
        if (position !in DOCK_POSITIONS) return
        dockPosition = position
        prefs.put("dockPosition", position)
    }

    fun toggleSidebar() {
        sidebarCollapsed = !sidebarCollapsed
        saveUiPrefs()
        putUiPref { put("sidebarCollapsed", sidebarCollapsed) }
    }

    /** A rail section tapped (`handleTabClick`): a folded sidebar unfolds on it, and the open one folds the sidebar. */
    fun pickSidebarSection(id: String) {
        when {
            sidebarCollapsed -> {
                openSidebarSection(id)
                toggleSidebar()
            }
            sidebarSection == id -> toggleSidebar()
            else -> openSidebarSection(id)
        }
    }

    private fun openSidebarSection(id: String) {
        sidebarTab = id
        saveUiPrefs()
        putUiPref { put("sidebarActiveTab", id) }
    }

    /** Shows [id] in the sidebar without folding an already-open panel (`setSidebarActiveTab` / `showSection`). */
    fun showSidebarSection(id: String) {
        openSidebarSection(id)
        if (wide) {
            if (sidebarCollapsed) toggleSidebar()
        } else {
            mobileDrawerOpenRequest = id
        }
    }

    /** The tab bar's "Wrap Tabs" / "Scroll Tabs". */
    fun toggleTabWrap() {
        tabWrap = !tabWrap
        saveUiPrefs()
        putUiPref { put("tabWrap", tabWrap) }
    }

    /** Follows the drag; [saveSidebarWidth] sends the width the drag ended on. */
    fun resizeSidebar(width: Int) {
        sidebarWidth = width.coerceIn(SIDEBAR_MIN_WIDTH, SIDEBAR_MAX_WIDTH)
    }

    fun saveSidebarWidth() {
        saveUiPrefs()
        putUiPref { put("sidebarWidth", sidebarWidth) }
    }

    /** One UI pref, for every device; a refused one is simply not kept. */
    private fun putUiPref(change: JsonObjectBuilder.() -> Unit) {
        val api = api ?: return
        scope.launch { quietly { api.putUiPrefs(buildJsonObject(change)) } }
    }

    /** A tab dropped on another panel's middle goes to the end of its tabs, in front. */
    fun moveTab(tabId: String, fromPanelId: String, toPanelId: String) {
        workspace?.moveTab(tabId, fromPanelId, toPanelId)
    }

    /** A tab dropped on a panel's edge: a new panel on that side holds it. */
    fun splitPanel(direction: SplitDirection, tabId: String, fromPanelId: String, targetPanelId: String) {
        workspace?.splitPanel(direction, tabId, fromPanelId, targetPanelId)
    }

    /**
     * A tab a mouse dropped among a bar's tabs, before [beforeTabId] (last for null). The web
     * counts its drop index before taking the tab out, so a tab moved right lands one place past
     * the mark it was dropped on; here it lands on the mark.
     */
    fun dropTab(tabId: String, fromPanelId: String, toPanelId: String, beforeTabId: String?) {
        val store = workspace ?: return
        val tabs = store.state.value.panels[toPanelId]?.tabs ?: return
        if (fromPanelId == toPanelId) {
            val rest = tabs.filter { it.id != tabId }
            store.reorderTab(tabId, toPanelId, rest.indexOfFirst { it.id == beforeTabId }.takeIf { it >= 0 } ?: rest.size)
        } else {
            store.moveTab(tabId, fromPanelId, toPanelId, tabs.indexOfFirst { it.id == beforeTabId }.takeIf { it >= 0 })
        }
    }

    /** A tab's Mark as unread: its chat, under the tab's title. */
    fun markUnread(sessionId: String, title: String) = events?.markUnread(sessionId, title)

    /** A tab's Pin / Unpin (`setTabPinned`): a pinned tab sits at the start of its bar and outlives the bulk closes. */
    fun setTabPinned(tabId: String, pinned: Boolean) {
        workspace?.setTabPinned(tabId, pinned)
    }

    /**
     * Close Others / to the Right / Saved / All from [anchorId]'s menu (`bulkCloseIds`), over the
     * tabs its bar shows ([shown] narrows them further, as minimal mode does on a phone): never a
     * pinned tab, and Close Saved never one holding unsaved work. The others still ask first.
     */
    fun bulkCloseTabs(anchorId: String, kind: BulkClose, panelId: String? = panelOf(anchorId), shown: (WorkspaceTab) -> Boolean = { true }) {
        val pid = panelId ?: return
        val tabs = panelTabs(pid, shown) ?: return
        closeAll(pid, bulkCloseIds(tabs, anchorId, kind, ::tabHoldsUnsavedWork))
    }

    /** Whether the menu offers [kind]: it would close some tab (`mobile-nav.tsx`). */
    fun canBulkClose(anchorId: String, kind: BulkClose, panelId: String? = panelOf(anchorId), shown: (WorkspaceTab) -> Boolean = { true }): Boolean =
        panelId?.let { panelTabs(it, shown) }?.let { tabs -> bulkCloseIds(tabs, anchorId, kind) { false }.isNotEmpty() } == true

    /** The palette's and the shortcut's Pin / Unpin Tab (`togglePinActiveTab`): the focused panel's active tab. */
    fun togglePinActiveTab() {
        val active = focusedPanelTabs()?.let { (tabs, panel) -> tabs.firstOrNull { it.id == panel.activeTabId } } ?: return
        setTabPinned(active.id, !active.pinned)
    }

    /** Close Saved / Close All from the palette or a shortcut (`bulkCloseInFocusedPanel`), anchored on the focused panel's active tab. */
    fun bulkCloseInFocusedPanel(kind: BulkClose) {
        val (tabs, panel) = focusedPanelTabs() ?: return
        closeAll(panel.id, bulkCloseIds(tabs, panel.activeTabId.orEmpty(), kind, ::tabHoldsUnsavedWork))
    }

    private fun focusedPanelTabs(): Pair<List<WorkspaceTab>, WorkspacePanel>? {
        val state = layout ?: return null
        val panel = state.panels[state.focusedPanelId] ?: return null
        return visibleTabs(panel.tabs, state.currentProject) to panel
    }

    /** The tabs [panelId]'s bar shows, as `visibleTabs` has them. */
    private fun panelTabs(panelId: String, shown: (WorkspaceTab) -> Boolean): List<WorkspaceTab>? {
        val state = layout ?: return null
        val panel = state.panels[panelId] ?: return null
        return visibleTabs(panel.tabs, state.currentProject).filter(shown)
    }

    private fun closeAll(panelId: String, ids: List<String>) {
        val store = workspace ?: return
        val tabs = ids.mapNotNull(::tabOf)
        val losing = tabs.filter { com.hienlh.ppm.state.database.losesDbWorkOnClose(it) }
        if (losing.isNotEmpty()) {
            pendingTabClose = PendingTabClose(panelId = panelId, tabs = tabs, losingTabs = losing)
            if (screenOverlay == null) screenOverlay = tabCloseConfirmOverlay
            return
        }
        for (id in ids) {
            onTabClosing(id)
            store.closeTab(id, panelId)
        }
    }

    /** A deleted session's tabs go with it, wherever they are. */
    private fun closeSessionTabs(sessionId: String) {
        val store = workspace ?: return
        for (panel in store.state.value.panels.values.toList()) {
            for (tab in panel.tabs) if (tabSessionId(tab) == sessionId) store.closeTab(tab.id, panel.id)
        }
    }

    /**
     * Shows another session in this tab, as picking one from the chat's own History does; a
     * session already open in a tab is brought forward instead.
     */
    fun replaceTab(tabId: String, session: SessionInfo) {
        val store = workspace ?: return
        val open = openTabs.firstOrNull { tabSessionId(it.first) == session.id }
        if (open != null) {
            activate(open.first.id)
            return
        }
        // A first message or an edit still on its way must not land in the chat picked here.
        chats[tabId]?.let {
            newChats.abandon(it, "You switched to another chat before it connected.")
            edits.abandon(it, "You switched to another chat before it connected.")
        }
        // A tab with a session is no new chat: nothing of one stays to be resolved, claimed or typed.
        store.updateTab(tabId) { tab ->
            tab.copy(title = session.title.ifEmpty { "Chat" }).withMetadata(
                "sessionId" to JsonPrimitive(session.id),
                "providerId" to JsonPrimitive(session.providerId),
                "providerPending" to null,
                "focusedProviderOnOpen" to null,
                "pickedAccountId" to null,
                "pickedAccountLabel" to null,
                "pickedAccountProvider" to null,
                "pendingMessage" to null,
            )
        }
        events?.markRead(session.id)
    }

    internal fun tabOf(tabId: String): WorkspaceTab? = workspace?.state?.value?.panelOf(tabId)?.tab(tabId)

    /** A tab's metadata as the layout has it now (`currentTabMetadata`). */
    internal fun tabMetadata(tabId: String): JsonObject? = tabOf(tabId)?.metadata

    /** `patchTabMetadata`: [changes] merged into the tab's metadata, a null removing its key. */
    internal fun patchTabMetadata(tabId: String, vararg changes: Pair<String, JsonElement?>) {
        val store = workspace ?: return
        if (tabOf(tabId) == null) return
        store.updateTab(tabId) { it.withMetadata(*changes) }
    }

    internal fun updateTabTitleAndMetadata(
        tabId: String,
        title: String? = null,
        vararg changes: Pair<String, JsonElement?>,
    ) {
        val store = workspace ?: return
        if (tabOf(tabId) == null) return
        store.updateTab(tabId) { tab ->
            val withMeta = tab.withMetadata(*changes)
            if (title != null) withMeta.copy(title = title) else withMeta
        }
    }

    /**
     * Moves [chat] onto an account picked in its usage panel (`handleSelectAccount`). A session is
     * bound to it from its next message — said out loud, as the switch costs a full prompt-cache
     * write — and [reloadUsage] reads the new binding's figures at once; a tab with no session
     * claims it instead, to be redeemed when its session is made. Answers the server's refusal,
     * shown beside the cards where the pick was made, or null.
     */
    suspend fun selectAccount(chat: ChatTab, accountId: String, label: String?, reloadUsage: suspend () -> Unit): String? {
        val session = chat.session
        if (session == null) {
            patchTabMetadata(
                chat.tabId,
                "pickedAccountId" to JsonPrimitive(accountId),
                "pickedAccountLabel" to (label?.let(::JsonPrimitive) ?: JsonNull),
                "pickedAccountProvider" to JsonPrimitive(chat.providerId),
            )
            return null
        }
        val api = api ?: return null
        try {
            api.setSessionAccount(chat.project, session.id, accountId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            signInIfRefused(error)
            return error.message?.takeIf { it.isNotEmpty() } ?: "Could not switch account"
        }
        chat.servingAccount = PickedAccount(accountId, label)
        scope.launch { reloadUsage() }
        toast(ErrorToastMessage("This chat will use the selected account from the next message.", kind = ErrorToastMessage.Kind.SUCCESS))
        return null
    }

    /** A session made here goes into the list at once (`upsertSession`). */
    internal fun addSession(project: String, session: SessionInfo) {
        if (selectedProject != project) return
        sessions = (listOf(session) + sessions.filter { it.id != session.id }).sortedForList()
    }

    /** The project's tags as a new chat's preparation read them (`seedTags`). */
    internal fun seedTags(project: String, tags: ProjectTags) {
        if (selectedProject == project) this.tags = tags
    }

    /** A refused password asks for it again, as any other refused request does. */
    internal fun signInIfRefused(error: Exception) {
        if (error is PpmError.Unauthorized) showSignIn = true
    }

    /** The chat took its tab's `pendingMessage` and is connected: the tab drops it, so it is not offered again. */
    fun pendingMessageTaken(tab: ChatTab) {
        if (!tab.tookPendingMessage) return
        tab.tookPendingMessage = false
        patchTabMetadata(tab.tabId, "pendingMessage" to null, "pendingContexts" to null)
    }

    /** The title the server gives a chat goes onto its tab too, so every browser shows it (chat-tab.tsx). */
    fun retitleTab(tabId: String, title: String) {
        val store = workspace ?: return
        if (store.state.value.panelOf(tabId)?.tab(tabId)?.title == title) return
        store.updateTab(tabId) { it.copy(title = title) }
    }

    /**
     * A new chat in a new tab (in [panelId] when given), with no session until its first message
     * (`openTab({ type: "chat", title: "AI Chat", metadata: { projectName } })`): the layout gives it
     * the provider this device's cache resolves, else it waits on its preparation. A `/clear` names
     * its [providerId], the [permissionMode] it ran in — a cached default the new chat's preparation
     * may still replace — the chat it left in [clearedFrom], and its [title] if it was given one.
     * [pendingMessage] waits in its message box, to be edited before it is sent.
     */
    fun newChat(
        providerId: String? = null,
        panelId: String? = null,
        pendingMessage: String? = null,
        title: String? = null,
        clearedFrom: String? = null,
        permissionMode: String? = null,
    ) {
        val project = selectedProject ?: return
        withWorkspace { store ->
            store.openTab(
                NewTab(
                    type = "chat",
                    title = title?.takeIf { it.isNotEmpty() } ?: "AI Chat",
                    projectId = project,
                    metadata = buildJsonObject {
                        put("projectName", project)
                        providerId?.let { put("providerId", it) }
                        if (permissionMode != null) {
                            put("permissionMode", permissionMode)
                            put("permissionModeSource", "cache")
                        }
                        clearedFrom?.let { put("clearedFrom", it) }
                        pendingMessage?.let { put("pendingMessage", it) }
                    },
                ),
                panelId,
            )
        }
    }

    /**
     * A fork of a chat in a tab of its own (`handleFork`'s `openTab`), titled [title]: the message
     * it was forked at waits in its box ([pendingMessage]) to be sent as it is or changed first.
     */
    internal fun openFork(project: String, session: SessionInfo, title: String, pendingMessage: String) {
        if (assistant.takeFork(project, session, pendingMessage)) return
        withWorkspace { store ->
            store.openTab(
                NewTab(
                    type = "chat",
                    title = title,
                    projectId = project,
                    metadata = buildJsonObject {
                        put("projectName", project)
                        put("sessionId", session.id)
                        put("providerId", session.providerId)
                        put("pendingMessage", pendingMessage)
                    },
                ),
            )
        }
    }

    /** Reads [tab]'s `/` list unless one read in the last half hour is held (`loadSlashItems`). */
    fun loadSlashItems(tab: ChatTab) {
        val api = api ?: return
        val store = slashItems ?: return
        // Not while the provider is still being resolved: the list would be another provider's.
        if (tab.providerPending) return
        scope.launch { store.load(api, tab.project, tab.providerId, tab.session?.id) }
    }

    /**
     * The slash picker's Refresh: the server reads skills from disk again, then [tab]'s list is
     * read. Its arrows turn until the server has answered, whether or not the picker stays open.
     */
    fun refreshSlashItems(tab: ChatTab) {
        val api = api ?: return
        val store = slashItems ?: return
        if (tab.slashRefreshing) return
        tab.slashRefreshing = true
        scope.launch {
            try {
                store.refresh(api, tab.project, tab.providerId, tab.session?.id)
            } finally {
                tab.slashRefreshing = false
            }
        }
    }

    /** A slash item picked in [tab]: into its box, and first among the recent ones. */
    fun pickSlash(tab: ChatTab, item: SlashItem, raiseKeyboard: Boolean) {
        tab.pickSlash(item, raiseKeyboard)
        val api = api ?: return
        slashItems?.recordUse(item, api, tab.project, tab.providerId)
    }

    /** What [ChatTab.clientCommand] answers: `/clear` opens a new chat beside [tab], one written while replying is refused. */
    private fun runClientCommand(tab: ChatTab, text: String): Boolean? {
        val items = slashItems?.payload(tab.project, tab.providerId)?.items.orEmpty()
        return when (val command = ClientCommand.of(text, items, replying = tab.replyTo != null)) {
            null -> null
            ClientCommand.Refused -> {
                actionError("Cancel reply before running this command")
                false
            }
            is ClientCommand.Clear -> {
                if (assistant.takeClear(tab)) return true
                // The mode goes over as a cached default only: the new chat starts from the settings like any other.
                newChat(tab.providerId, panelOf(tab.tabId), title = command.title, clearedFrom = tab.session?.id, permissionMode = tab.permissionMode?.value)
                true
            }
        }
    }

    /** The message to show for [error]; a refused password asks for it again. */
    fun describe(error: Exception): String {
        if (error is PpmError.Unauthorized) showSignIn = true
        val message = error.message ?: error.toString()
        if (error is PpmError.Unreachable) {
            return "$message\n\nIs this device on the same network (or Tailscale) as the server, and is PPM running there?"
        }
        return message
    }

    /** Runs [call]; a failure is shown as the action's error. Answers whether it succeeded. */
    private suspend fun report(call: suspend () -> Unit): Boolean = try {
        call()
        true
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        actionError(describe(error))
        false
    }

    private suspend fun <T> quietly(call: suspend () -> T): T? = try {
        call()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

}

/**
 * The project a terminal tab's shell is started for, as `terminal-tab.tsx` reads it
 * (`metadata.projectName`) and `useTerminal` names it (`projectName ?? ""`): not the tab's own
 * `projectId`, and not whichever project is open when the tab is first shown. A shell with no
 * project is named "", which the server starts in the host's home folder, or in the tab's `cwd`.
 */
internal fun terminalProject(tab: WorkspaceTab): String = tab.metaString("projectName") ?: ""

/**
 * The terminal a command run from a code block is typed into (`use-terminal-command-queue`): one on
 * screen, whichever project its shell was started for. The web hands the command to every terminal on
 * screen and each queues it, so one command is typed into all of them; here exactly one takes it: the
 * focused panel's terminal if that panel shows one, else the first shown, the dock's before the grid's.
 */
internal fun shownTerminalOf(state: WorkspaceState): WorkspaceTab? {
    val focused = state.panels[state.focusedPanelId]?.takeIf { it.id in state.gridPanelIds }?.activeTab
    if (focused?.type == "terminal") return focused
    val dock = if (state.dock.visible) state.dockPanel?.activeTab else null
    return (listOf(dock) + state.gridPanelIds.map { state.panels[it]?.activeTab }).firstOrNull { it?.type == "terminal" }
}

/**
 * The chat `sendToChat` opens when none takes the text: a plain "Chat" until its session names it, on
 * no project, with the project only in its metadata (when there is one) and the text as its first
 * message or, as [asContext], a context chip.
 */
internal fun sendToChatTab(text: String, label: String, project: String?, asContext: Boolean) = NewTab(
    type = "chat",
    title = "Chat",
    projectId = null,
    metadata = buildJsonObject {
        project?.takeIf { it.isNotEmpty() }?.let { put("projectName", it) }
        if (asContext) put("pendingContexts", JsonArray(listOf(buildJsonObject { put("text", text); put("label", label) })))
        else put("pendingMessage", text)
    },
)

/**
 * The tab that holds a command when no terminal is on screen (`runInTerminal`): in the dock, with the
 * project as given on the tab and named in its metadata only when that is not empty, and the command as
 * its `pendingCommand` for the shell to type once it has a prompt.
 */
internal fun runInTerminalTab(command: String, projectName: String?) = NewTab(
    type = "terminal",
    title = "Terminal",
    projectId = projectName,
    metadata = buildJsonObject {
        projectName?.takeIf { it.isNotEmpty() }?.let { put("projectName", it) }
        put("pendingCommand", command)
    },
)

/**
 * A terminal tab as the web opens one (`openTab`): the project it belongs to on the tab and in its
 * metadata, neither when there is none.
 */
internal fun terminalTab(project: String?) = NewTab(
    type = "terminal",
    title = "Terminal",
    projectId = project,
    metadata = buildJsonObject {
        project?.let { put("projectName", it) }
    },
)

/**
 * The tab of "Open in Terminal" (`openTerminalAt`): titled by the folder's name, or by the whole
 * path when that is empty ("/", a trailing separator), starting in [dir]. The project is the tab's
 * as given, and named in the metadata only when it is not empty (`projectName ? {…} : {}`).
 */
internal fun terminalAtTab(dir: String, project: String?) = NewTab(
    type = "terminal",
    title = dir.split('/', '\\').last().ifEmpty { dir },
    projectId = project,
    metadata = buildJsonObject {
        put("cwd", dir)
        project?.takeIf { it.isNotEmpty() }?.let { put("projectName", it) }
    },
)

/**
 * The tab of a file chip (`FilePathChip`): titled by the file's name, belonging to no project, with
 * the project the chip was shown in only named in its metadata, and not even there when it has none
 * (`if (pName) meta.projectName = pName`).
 */
internal fun filePathTab(path: String, projectName: String?) = NewTab(
    type = "editor",
    title = path.split('/', '\\').last(),
    projectId = null,
    metadata = buildJsonObject {
        put("filePath", path)
        projectName?.takeIf { it.isNotEmpty() }?.let { put("projectName", it) }
    },
)

private val DOCK_POSITIONS = setOf("bottom", "left", "right")

/** The sections this app has, in the web's default rail order (`BUILTIN_SIDEBAR_TABS`). */
private val SIDEBAR_SECTIONS = listOf("history", "tasks", "teams", "designs", "specs", "explorer", "search", "git", "database", "tunnels", "ai-resources")
private val MINIMAL_SIDEBAR_SECTIONS = setOf("history", "explorer", "search")

private val PROJECT_SORT_MODES = setOf("recent", "priority", "name")

/** The web's toast when every window slot is taken. */
private const val TOO_MANY_WINDOWS = "Too many windows open — close one first"

const val SIDEBAR_MIN_WIDTH = 200
const val SIDEBAR_DEFAULT_WIDTH = 280
const val SIDEBAR_MAX_WIDTH = 600

/**
 * The chat of one chat tab ([tabId] in the layout). Its connection starts when the tab is first
 * shown and stays up while the tab is open, so a tab behind still shows its turn streaming. A new
 * chat has no [session] until its first message makes one, which the tab then keeps ([adopt]).
 */
@Stable
class ChatTab(val tabId: String, session: SessionInfo?, val project: String, providerId: String) {
    var session by mutableStateOf(session)
        private set

    /** Where its messages go: the session's provider, or before one the tab's. */
    var providerId by mutableStateOf(providerId)
        private set

    var controller by mutableStateOf<ChatController?>(null)
        internal set

    /** Its tab's metadata and title as the layout last had them (the web's `metadata` prop), kept by the model. */
    internal var metadata by mutableStateOf(JsonObject(emptyMap()))
    var tabTitle by mutableStateOf("")
        internal set

    /** The account its claim names, while that claim is for its provider (`claimMatchesProvider`). */
    val claimedAccountId: String?
        get() = metadata["pickedAccountId"].string?.takeIf { it.isNotEmpty() && metadata["pickedAccountProvider"].string == providerId }

    /** The name that claim gives its account, while the claim is for its provider. */
    val claimedAccountLabel: String?
        get() = if (claimedAccountId != null) metadata["pickedAccountLabel"].string else null

    /** Its provider is still being resolved (`providerPending`): the gate line shows, the mode and model chips wait. */
    var providerPending by mutableStateOf(false)
        internal set

    /** Why its provider could not be resolved; null while that is still being tried. */
    var gate by mutableStateOf<NewChatGate?>(null)
        internal set

    /** The mode was picked by hand in this tab, so no preparation replaces it (`permissionChosenByUser`). */
    internal var permissionChosenByUser = false

    /** Its first message is on its way: its session is being made, then connected (`firstSendPending`). */
    var firstSendPending by mutableStateOf(false)
        private set

    /** What shows in place of the transcript meanwhile. */
    var firstSendPreview by mutableStateOf<FirstSendPreview?>(null)
        private set

    /** The first message in flight, and whether its session exists yet, so it waits for the socket. */
    private var firstMessage: FirstMessage? = null
    private var firstQueued = false

    /** Counts first sends; one overtaken (put back, given up, the tab gone) stops at its next step. */
    internal var firstSendAttempt = 0
        private set
    internal var firstSendJob: Job? = null

    /** The accounts claimed for it before its session, one per provider (`useChatAccountClaim`). */
    internal val claims = HashMap<String, Deferred<PickedAccount?>>()

    /** What its first message's process is warmed with while it is on screen (`useChatPrewarm`); null while it asks nothing. */
    internal var prewarm: PrewarmAsk? = null

    /** When that was last asked for, so writing asks again at most once a minute. */
    internal var prewarmAskedAt = 0L

    /** Someone wrote in the box (`touchPrewarm`); set by the model. */
    var prewarmTouch: (() -> Unit)? = null

    /** Sends a first message when it has no session (set by the model). */
    var firstSend: ((FirstMessage) -> Unit)? = null

    /** Puts a mode picked in the composer on the tab too (set by the model). */
    var modePicked: ((PermissionMode) -> Unit)? = null

    /** The message the next send replaces (`editFork`); null while a send is a new message. */
    var editFork by mutableStateOf<EditFork?>(null)
        private set

    /** An edit was sent and its version is being made and connected (`editForking`): the chat is covered meanwhile. */
    var editForking by mutableStateOf(false)
        internal set

    /** Sends an edited message, which goes on in a new version of this chat (set by the model). */
    var editSend: ((EditMessage) -> Unit)? = null

    /** Opens the tabs the AI asks for on this chat's socket (`tab_open`), and says how that went. */
    var tabOpener: (suspend (TabOpenRequest) -> TabOpenAnswer)? = null

    /** An Assistant chat's screen tools and screen summary (set by the model; `AppAssistant.wire`). */
    var assistantUi: (suspend (com.hienlh.ppm.protocol.AssistantUiRequest) -> com.hienlh.ppm.protocol.AssistantUiResult)? = null
    var uiSummary: (() -> JsonObject?)? = null

    /** Hears what may need the user while this chat is not in front (set by the model). */
    var onAttention: ((ChatAttention) -> Unit)? = null

    /** The AI settings' default model and effort per provider, shown while there is no session (set by the model). */
    var defaultsFor: ((String) -> ProviderDefaults?)? = null

    /** The toast this chat raised for a request still waiting (`approvalToastRef`): it goes once that is answered. */
    internal var attentionToast: ErrorToastMessage? = null

    /** The edited message waiting for its version's socket (`pendingSendRef`), and the work that waits. */
    internal var pendingEdit: PendingEdit? = null
    internal var editJob: Job? = null

    /** It took its tab's `pendingMessage` into the box; the model drops it from the tab once the chat is connected. */
    var tookPendingMessage = false

    /** The box takes the focus once first shown (`autoFocus`): a new chat's, or one handed a `pendingMessage`. */
    var focusOnShow = session == null

    val started: Boolean get() = controller != null

    /** What is typed in this tab's message box, kept while another tab is in front. */
    val draft = TextFieldState()

    /** This tab's id on its chat socket (`?clientId=`), the same across reconnects and session switches. */
    val clientId: String = ChatClientId.mint()

    /** Its turn chips, Copy turn link and history window. */
    val turnLinks = ChatTabTurnLinks(this)

    /**
     * The account this chat is on as far as the app can tell (`servingAccount`): written by a
     * switch the server accepted and by the stream naming who served, the later of the two
     * winning; cleared for another conversation.
     */
    var servingAccount by mutableStateOf<PickedAccount?>(null)

    /** The stream's report [servingAccount] last took, so each is taken once however often the chat is drawn. */
    internal var liveAccountTaken: PickedAccount? = null

    /** This chat's History list, with its own search, tag chip and pages. */
    var historyList by mutableStateOf<HistoryList?>(null)
        private set

    /** The chats under its welcome while it has no message (`chat-welcome.tsx`), searched apart from History. */
    var welcomeList by mutableStateOf<HistoryList?>(null)
        private set

    /** Whether its History shows Manage tags; kept while the History list is closed, as on the web. */
    var showsTagSettings by mutableStateOf(false)

    /** Picked in the composer; until then the chip shows the fallback and messages carry none, so the server keeps the session's own. */
    var permissionMode by mutableStateOf<PermissionMode?>(null)

    /**
     * What a message sent now carries of the composer's choices: the mode alone. A message carries
     * no priority: sent while a turn runs it is queued, or read at once on Send now.
     */
    internal fun sendOptions() = TurnOptions(permissionMode = permissionMode?.value)

    /** The message the next one answers, shown over the message box until it is sent or cancelled. */
    var replyTo by mutableStateOf<ReplyReference?>(null)

    /** The subagent the next message delegates to, a chip over the box; sent as "Use the X agent to …". */
    var agent by mutableStateOf<String?>(null)

    /** Which past message ↑/↓ put in the box, counted back from the newest; -1 while it holds a draft. */
    var historyIndex = -1

    /**
     * Counts each time the message box is asked for focus: a reply picked or cancelled, a refused
     * message put back, files added or pasted, the box's padding or the agent's cross tapped.
     */
    var focusRequests by mutableIntStateOf(0)
        private set

    /** Whether the last of those brings the keyboard, even to a box that kept its focus. */
    var raisesKeyboard = true
        private set

    /** Files on their way into the next message, in the order they were added (`attachments`). */
    val attachments = mutableStateListOf<ComposerAttachment>()

    /** Send was pressed while a file was still uploading: the message goes once none is (`pendingSend`). */
    var pendingSend by mutableStateOf(false)

    /** Files are being dragged over the chat: its "Drop files to attach" overlay is up (`isDragging`). */
    var filesOver by mutableStateOf(false)

    /** Whether Send has anything to send (`hasContent`): text, a file that did not fail, or an agent. */
    val hasContent: Boolean
        get() = draft.text.isNotBlank() || attachments.any { it.status != ComposerAttachment.Status.ERROR } || agent != null

    /**
     * Every file its session has changed (`useSessionFileChanges`), which the changes bar and each
     * turn's tray share; null while it has no session.
     */
    var sessionChanges by mutableStateOf<SessionChanges?>(null)
        private set

    /** What its transcript's tools wrote, worked out again only for the messages that changed. */
    val sessionWrites = SessionWrites()
    private var changesApi: ApiClient? = null
    private var changesScope: CoroutineScope? = null
    private var changesJob: Job? = null

    /** Each list its changes bar gets, for a Review tab on the same session (`announceSessionChanges`); set by the model. */
    var changesListed: ((sessionId: String, list: SessionChangesList) -> Unit)? = null

    /** A list for the session it is on now: one asked for under another is never shown under this. */
    private fun watchChanges() {
        changesJob?.cancel()
        val api = changesApi
        val scope = changesScope
        val id = session?.id
        if (api == null || scope == null || id == null || project.isEmpty()) {
            sessionChanges = null
            return
        }
        val job = SupervisorJob(scope.coroutineContext[Job]).also { changesJob = it }
        val changes = SessionChanges(SessionChangesBackend.api(api, project, id), scope + job)
        sessionChanges = changes
        scope.launch(job) { changes.listed.collect { list -> if (list != null) changesListed?.invoke(id, list) } }
    }

    /** Publishes its turns from [start] to [stop], so a Review tab on its session names the turn behind each block. */
    private var turnsJob: Job? = null

    /** What the transcript shows, the turn being streamed included, as turns (`useSessionTurnsStore().publish`). */
    private fun publishTurns(controller: ChatController, store: SessionTurnsStore, scope: CoroutineScope): Job = scope.launch {
        val memo = SessionTurnsMemo()
        var published: String? = null
        try {
            controller.state
                .distinctUntilChanged { a, b -> a.sessionId == b.sessionId && a.items === b.items && a.live === b.live }
                .collect { state ->
                    val id = state.sessionId
                    val before = published
                    if (before != null && before != id) store.release(before)
                    published = id
                    if (id != null) store.publish(id, memo.of(state.itemsWithLive()), live = true)
                }
        } finally {
            published?.let(store::release)
        }
    }

    /** The box's unsent message, kept on this device and on the server (`useDraft`); from [start] on. */
    private var drafts: DraftSync? = null
    private var draftLoad: Job? = null

    /**
     * The draft is still on its way (`draftLoading`): Reply waits for it, but for
     * [DRAFT_GATE_MILLIS] at most, so a server that does not answer cannot keep it hidden.
     */
    var draftLoading by mutableStateOf(true)
        private set

    /** A loaded draft went into the box, for the box to take the focus at its end; its composer clears it. */
    var draftFocus by mutableStateOf(false)

    /** What is typed after the `/` that opened the slash picker (`slashFilter`); null while it is closed. */
    var slashQuery by mutableStateOf<String?>(null)
        private set

    /** What is typed after the `@` that opened the file picker (`fileFilter`); null while it is closed. */
    var fileQuery by mutableStateOf<String?>(null)
        private set

    /** The slash picker's highlighted row (`selectedIndex`), the first again whenever its query or tab changes. */
    var slashSelection by mutableIntStateOf(0)

    /** Active kind tab (`All`, `Skills`, `Commands`, `Agents`) in the slash picker; resets to `All` when closed. */
    var slashKind by mutableStateOf(com.hienlh.ppm.state.SlashKindFilter.ALL)

    fun pickSlashKind(kind: com.hienlh.ppm.state.SlashKindFilter) {
        if (slashKind != kind) {
            slashKind = kind
            slashSelection = 0
        }
    }

    /** The file picker's highlighted row, the first again whenever its query changes. */
    var fileSelection by mutableIntStateOf(0)

    /** The slash picker's Refresh is waiting on the server (`refreshing`): its arrows turn, and another press does nothing. */
    var slashRefreshing by mutableStateOf(false)

    /**
     * Acts on a message the app runs itself (`handleInputSend`): false keeps it in the box, true
     * when it ran, null for any other message. Set by the model.
     */
    var clientCommand: ((String) -> Boolean?)? = null

    /** The mic is open for this box (`isListening`): its button is red and pulses. */
    var voiceListening by mutableStateOf(false)

    /** Whisper is turning what this box's mic heard into text (`isTranscribing`); the mic waits for it. */
    var voiceTranscribing by mutableStateOf(false)

    /** What the box held as the mic opened, trimmed: what it hears goes after it (`preVoiceTextRef`). */
    internal var preVoiceText = ""

    /** Closes the mic of a box being sent from, dropping what it would still write. Set by the model. */
    var closeMic: (() -> Unit)? = null

    /**
     * The pickers after the user changed the text (`updatePickerState`), from what is left of
     * the caret: the slash picker first, else the file picker, else neither.
     */
    fun updatePickers(text: CharSequence, caret: Int) {
        val before = text.subSequence(0, caret.coerceIn(0, text.length)).toString()
        val slash = SlashTrigger.queryBefore(before)
        showSlash(slash)
        showFiles(if (slash == null) FileMention.queryBefore(before) else null)
    }

    fun closePickers() {
        showSlash(null)
        showFiles(null)
    }

    // A closed picker's filter is "" on the web, so one opened again on nothing typed keeps its row;
    // each new opening starts on the All tab (`if (!visible) setKind("all")`).
    private fun showSlash(query: String?) {
        if ((query ?: "") != (slashQuery ?: "")) slashSelection = 0
        if (query == null) slashKind = com.hienlh.ppm.state.SlashKindFilter.ALL
        slashQuery = query
    }

    private fun showFiles(query: String?) {
        if ((query ?: "") != (fileQuery ?: "")) fileSelection = 0
        fileQuery = query
    }

    /**
     * A slash item picked (`slashSelected`): an agent becomes the chip and its `/query` goes; any
     * other takes the `/query`'s place as it is typed, with its sigil and a space, the caret after.
     * [raiseKeyboard] as for [requestFocus]: a key leaves the box focused, a tap took the focus.
     */
    fun pickSlash(item: SlashItem, raiseKeyboard: Boolean) {
        val text = draft.text.toString()
        val caret = draft.selection.min
        val before = text.substring(0, caret)
        val head = if (item.type == "agent") SlashTrigger.stripQuery(before) else SlashTrigger.replaceQuery(before, item.invocation)
        if (item.type == "agent") agent = item.name
        draft.edit {
            replace(0, length, head + text.substring(caret))
            selection = TextRange(head.length)
        }
        closePickers()
        // The web leaves the draft as it was until the next keystroke.
        edited()
        requestFocus(raiseKeyboard)
    }

    /** A file picked (`fileSelected`): `@path ` in place of the `@query` left of the caret, else at the end. */
    fun pickFile(path: String, raiseKeyboard: Boolean) {
        val picked = FileMention.pick(path, draft.text.toString(), draft.selection.min)
        draft.edit {
            replace(0, length, picked.text)
            selection = TextRange(picked.caret)
        }
        showFiles(null)
        edited()
        requestFocus(raiseKeyboard)
    }

    /**
     * A project row dropped on the chat (`externalPaths`, and the message box's own drop): `@path ` at the end of the box
     * ([FileMention.append]) and the box focused, the keyboard with it as for a file dropped there. The web puts the path in
     * twice when the box itself is let go on, because the box's handler and the chat's both run; here a drop is one event. The
     * pickers are left as they were, as the web's write leaves them.
     */
    fun dropPath(path: String) {
        val dropped = FileMention.append(path, draft.text.toString())
        draft.edit {
            replace(0, length, dropped.text)
            selection = TextRange(dropped.caret)
        }
        edited()
        requestFocus()
    }

    /**
     * What the mic heard so far (`voiceResultCb`), after what the box held as it opened, with the
     * caret at the end; the pickers are left as they were, as the web's write does not reach them.
     */
    fun voiceResult(text: String) {
        draft.setTextAndPlaceCursorAtEnd(if (preVoiceText.isNotEmpty()) "$preVoiceText $text" else text)
        // The web leaves the draft as it was until the next keystroke.
        edited()
    }

    fun selectReply(reply: ReplyReference?) {
        replyTo = reply
        edited()
        requestFocus()
    }

    /**
     * The box changed (`onContentChange`): typed in, a past message recalled, a refused one put
     * back, a reply picked or dropped — or a file uploaded or taken out, which the web leaves to
     * the next keystroke. Saved as the draft, with the files the server has. [text] is what the
     * box is about to hold when the change is still being made.
     */
    fun edited(text: CharSequence = draft.text) {
        saveDraft(text)
        prewarmTouch?.invoke()
    }

    private fun saveDraft(text: CharSequence = draft.text) {
        val files = attachments.mapNotNull { file -> file.serverPath?.let { DraftAttachment(file.name, it) } }
        drafts?.save(ChatDraft(text.toString(), files, replyTo))
    }

    /** The app is leaving the screen: a save still waiting goes now. */
    fun flushDraft() {
        drafts?.flush()
    }

    internal suspend fun finishDraftSync() { drafts?.flushAndJoin() }

    /**
     * A draft that loaded (`initialValue`, `initialAttachments`, `draft.replyTo`): its text only
     * into an empty box, since what was typed wins, the caret at its end ([draftFocus]); its files
     * ahead of any added meanwhile; its reply unless one was picked, and only one into this chat,
     * as the server refuses any other on every send.
     */
    private fun show(loaded: ChatDraft) {
        if (loaded.content.isNotEmpty() && draft.text.isEmpty()) {
            draft.setTextAndPlaceCursorAtEnd(loaded.content)
            draftFocus = true
        }
        attachments.addAll(0, loaded.attachments.map { draftAttachment(it.name, it.path) })
        val reply = loaded.replyTo
        val session = session
        if (replyTo == null && session != null && reply?.sessionId == session.id && reply.providerId == session.providerId) replyTo = reply
    }

    /**
     * The message box focused, keyboard and all. Unless [raiseKeyboard] is false, the keyboard
     * comes back even when Back put it away from a box still focused: on the web a tapped button
     * took the focus, so its textarea's `focus()` is a fresh one. A paste leaves it as it is,
     * since the textarea pasted into never lost the focus. Asked from a tap, it keeps the tap
     * from taking the focus away first ([TextInputFocus.asked]).
     */
    fun requestFocus(raiseKeyboard: Boolean = true) {
        raisesKeyboard = raiseKeyboard
        focusRequests++
        TextInputFocus.asked()
    }

    /**
     * A mode picked in the composer (`handleModeChange`), kept on the tab; none while its provider
     * resolves or its first message goes, when the web's box offers no change.
     */
    fun pickMode(mode: PermissionMode) {
        if (providerPending || firstSendPending) return
        permissionChosenByUser = true
        permissionMode = mode
        modePicked?.invoke(mode)
    }

    /** Send pressed (`handleSend`): the message now, or once the files still uploading are up; nothing while a first one goes. */
    fun submit() {
        if (firstSendPending) return
        if (attachments.any { it.status == ComposerAttachment.Status.UPLOADING }) {
            if (draft.text.isNotBlank() || attachments.any { it.status != ComposerAttachment.Status.ERROR }) pendingSend = true
            return
        }
        sendNow()
    }

    /** A send waiting on uploads goes as soon as none is left. */
    fun sendIfQueued() {
        if (pendingSend && attachments.none { it.status == ComposerAttachment.Status.UPLOADING }) sendNow()
    }

    /**
     * `executeSend` and the chat's `handleSend`: the text with its agent and attachments, the
     * pictures that fit inline, and every image's uploaded path; then an empty box, Queue again.
     * A chat with no session hands it to [firstSend], which makes the session first.
     */
    private fun sendNow() {
        val chat = controller
        val typed = draft.text.toString().trim()
        val agent = agent
        val ready = attachments.filter { it.status == ComposerAttachment.Status.READY }
        val first = session == null
        val turnRefs = turnLinks.refs
        // A chip still loading or one whose turn is gone holds the send back (`turnChipsBlockedReason`).
        if (chat == null || (typed.isEmpty() && ready.isEmpty() && agent == null && turnRefs.isEmpty()) || firstSendPending || turnLinks.blockedReason != null) {
            pendingSend = false
            return
        }
        // The agent goes in as words the model delegates on; the bubble turns them back into its chip.
        val text = if (agent != null) "Use the $agent agent to $typed".trimEnd() else typed
        closePickers()
        // Sending closes the mic, whatever becomes of the message: what it would still write
        // would land in the box the message just left.
        if (voiceListening) closeMic?.invoke()
        when (clientCommand?.invoke(text)) {
            false -> {
                pendingSend = false
                return
            }
            true -> {
                drafts?.clear()
                emptyBox()
                return
            }
            null -> {}
        }
        val edit = editFork
        if (edit != null && session != null) {
            // Kept on this device before the box empties, as a first message is. The edit goes
            // through a fork, which takes only text: every file is named by its marker.
            saveDraft()
            drafts?.cancelPendingSave()
            val message = EditMessage(messageWithAttachments(text, ready, emptyList()), edit.anchor, replyTo, permissionMode?.value, turnRefs)
            editFork = null
            emptyBox()
            editSend?.invoke(message)
            return
        }
        val images = selectInlineImages(ready.map { it.imageData })
        val content = messageWithAttachments(text, ready, images)
        val imagePaths = ready.filter { it.isImage }.mapNotNull { it.serverPath }
        if (first) {
            // Kept on this device before the box empties: until the message is on a socket, the
            // draft is its only other copy. Only a save still waiting for the server is dropped.
            saveDraft()
            drafts?.cancelPendingSave()
            val message = FirstMessage(text, content, images, imagePaths, replyTo, turnRefs)
            emptyBox()
            firstSend?.invoke(message)
            return
        }
        chat.send(
            content,
            sendOptions(),
            replyTo,
            images,
            imagePaths,
            turnRefs = turnRefs,
        )
        // Only now: the message is on the session's socket.
        drafts?.clear()
        emptyBox()
    }

    /** A first message sent: shown over "Starting conversation...", the box closed to input meanwhile. Answers the attempt. */
    internal fun beginFirstSend(message: FirstMessage): Int {
        firstMessage = message
        firstQueued = false
        firstSendPending = true
        firstSendPreview = FirstSendPreview(message.content, Instant.now())
        return ++firstSendAttempt
    }

    /** The session its first message made: from now on this tab is that chat, its draft kept under it. */
    internal fun adopt(session: SessionInfo) {
        this.session = session
        servingAccount = null
        providerId = session.providerId
        firstQueued = true
        drafts?.moveTo(session.id)
        controller?.adopt(session)
        watchChanges()
    }

    /** Before its session the tab may change provider (`setProviderId`), and the chat follows. */
    internal fun chooseProvider(id: String) {
        if (session != null || id == providerId) return
        providerId = id
        controller?.chooseProvider(id)
    }

    /** The new session's socket is up: the first message goes on it, and its draft, still under [DraftSync.NEW_CHAT], goes. */
    internal fun flushFirst(permission: String?) {
        val message = firstMessage ?: return
        controller?.send(message.content, TurnOptions(permissionMode = permission), message.replyTo, message.images, message.imagePaths, turnRefs = message.turnRefs)
        endFirstSend()
        drafts?.clear(DraftSync.NEW_CHAT)
    }

    /** The first message given up on: what would follow stops, and once its session was made its `__new__` draft goes. */
    internal fun abandonFirst() {
        firstSendAttempt++
        if (firstQueued) drafts?.clear(DraftSync.NEW_CHAT)
    }

    /** The first message back in the box after what is typed there, saved as the draft again, [reason] said beside it. */
    internal fun restoreFirst(reason: String) {
        val message = firstMessage
        endFirstSend()
        message?.let { restore(RejectedSend(it.content, it.replyTo, reason, it.turnRefs)) }
    }

    private fun endFirstSend() {
        firstMessage = null
        firstQueued = false
        firstSendPending = false
        firstSendPreview = null
    }

    /** The box after a send: no text, files, agent or reply. */
    internal fun emptyBox() {
        for (attachment in attachments) attachment.file?.parentFile?.deleteRecursively()
        draft.clearText()
        attachments.clear()
        agent = null
        replyTo = null
        pendingSend = false
        historyIndex = -1
        turnLinks.clear()
    }

    /** A refused message back in the box (`restoreUnsentMessage`): after what is typed there, with the reply it carried. */
    fun restore(rejected: RejectedSend) {
        val typed = draft.text.toString()
        draft.setTextAndPlaceCursorAtEnd(if (typed.isNotBlank()) "$typed\n\n${rejected.content}" else rejected.content)
        replyTo = rejected.replyTo
        turnLinks.merge(rejected.turnRefs)
        edited()
        requestFocus()
    }

    /**
     * Edit on one of its messages (`handleEdit`): the message's text in the box in place of what
     * was there, what it answered picked again — in this conversation, which the edit goes on —
     * and the next send replacing it, from [anchor] on.
     */
    fun armEdit(item: ChatItem, anchor: String?) {
        val decoded = ChatReply.decode(item.text)
        val quoted = decoded.replyTo
        val session = session
        replyTo = if (quoted != null && session != null) quoted.copy(sessionId = session.id) else quoted
        editFork = EditFork(anchor, item.id)
        // Its turn chips back as chips, the block out of the text.
        val refs = ChatTurnRefs.decode(decoded.content)
        turnLinks.replace(refs.turnRefs)
        draft.setTextAndPlaceCursorAtEnd(refs.content)
        edited()
        requestFocus()
    }

    /** Cancel on the editing line (`handleCancelEdit`): the next send is a new message again, and the box and its draft are emptied. */
    fun cancelEdit() {
        editFork = null
        editForking = false
        replyTo = null
        drafts?.clear()
        clearBox()
    }

    /** The box emptied for the chat (`clearSignal`): its text, files and agent, the draft left as it is. */
    private fun clearBox() {
        for (attachment in attachments) attachment.file?.parentFile?.deleteRecursively()
        draft.clearText()
        attachments.clear()
        agent = null
        turnLinks.clear()
    }

    /**
     * The tab goes on in another session of its conversation (`setSessionId` after an edit, or from
     * the version switcher), as the web's tab does without a new one: its chat starts over on
     * [next], showing what this one showed until that history is in (`staleSwap`). An edit's draft
     * follows it ([moveDraft]); a switch empties the box and reads the draft [next] has.
     */
    internal fun swapSession(next: SessionInfo, moveDraft: Boolean) {
        val from = startedWith
        val old = controller
        val stale = old?.transcript?.items.orEmpty()
        // A request the old session raised a toast for is not this one's to answer (the web's session effect).
        onAttention?.invoke(ChatAttention.Answered)
        old?.stop()
        turnsJob?.cancel()
        turnsJob = null
        turnLinks.sessionChanged()
        session = next
        providerId = next.providerId
        servingAccount = null
        closePickers()
        if (!moveDraft) {
            editFork = null
            replyTo = null
            clearBox()
        }
        if (from == null) {
            controller = null
            return
        }
        val controller = ChatController(from.api, project, next, from.scope, providerId = providerId, stale = stale, clientId = clientId).also {
            it.tabOpener = tabOpener
            it.assistantUi = assistantUi
            it.uiSummary = uiSummary
            it.onAttention = onAttention
            it.defaultsFor = defaultsFor
            it.start()
        }
        this.controller = controller
        turnsJob = from.turns?.let { publishTurns(controller, it, from.scope) }
        watchChanges()
        if (moveDraft) {
            drafts?.moveTo(next.id)
        } else {
            // A save still waiting goes under the session it was typed for, as the web's timer still fires.
            drafts?.flush()
            loadDraft(DraftSync(from.api, project, tabId, next.id, from.draftStore, from.scope), null, from.scope)
        }
    }

    /** The edited message waiting for its version's socket, taken: it goes no other way now. */
    internal fun takePendingEdit(): PendingEdit? = pendingEdit.also { pendingEdit = null }

    /** Its version's socket is up: the edited message goes on it, and the draft it was written under goes. */
    internal fun flushEdit() {
        val pending = takePendingEdit() ?: return
        controller?.send(pending.content, TurnOptions(permissionMode = pending.permissionMode), pending.replyTo, turnRefs = pending.turnRefs)
        drafts?.clear(pending.draftId)
    }

    /** An edited message that never reached its version's socket: the draft it was written under goes, and it is back in the box. */
    internal fun abandonEdit(pending: PendingEdit, reason: String) {
        editForking = false
        drafts?.clear(pending.draftId)
        restore(RejectedSend(pending.content, pending.replyTo, reason, pending.turnRefs))
    }

    /**
     * Its chat connected (once it has a session), and its draft loaded from [draftStore], from
     * what its preparation read ([preparedDraft]) or from the server.
     */
    fun start(
        api: ApiClient,
        scope: CoroutineScope,
        draftStore: DraftLocalStore,
        preparedDraft: (suspend () -> ChatDraft?)? = null,
        turns: SessionTurnsStore? = null,
    ) {
        if (controller != null) return
        startedWith = Started(api, scope, draftStore, turns)
        historyList = HistoryList(api, project, scope)
        welcomeList = HistoryList(api, project, scope)
        val controller = ChatController(api, project, session, scope, providerId = providerId, clientId = clientId).also {
            it.tabOpener = tabOpener
            it.assistantUi = assistantUi
            it.uiSummary = uiSummary
            it.onAttention = onAttention
            it.defaultsFor = defaultsFor
            it.start()
        }
        this.controller = controller
        turnsJob = turns?.let { publishTurns(controller, it, scope) }
        changesApi = api
        changesScope = scope
        watchChanges()
        loadDraft(DraftSync(api, project, tabId, session?.id ?: DraftSync.NEW_CHAT, draftStore, scope), preparedDraft, scope)
    }

    /** What [start] was given, which a chat on another session of the conversation starts with too. */
    private class Started(val api: ApiClient, val scope: CoroutineScope, val draftStore: DraftLocalStore, val turns: SessionTurnsStore?)

    private var startedWith: Started? = null

    /** [drafts] the box's from now on, and its draft into the box once it loads. */
    private fun loadDraft(drafts: DraftSync, preparedDraft: (suspend () -> ChatDraft?)?, scope: CoroutineScope) {
        this.drafts = drafts
        draftLoad?.cancel()
        draftLoading = true
        draftLoad = scope.launch {
            // Past the gate a draft still goes in when it comes, into a box left empty.
            val gate = launch {
                delay(DRAFT_GATE_MILLIS)
                draftLoading = false
            }
            val loaded = drafts.load(preparedDraft)
            gate.cancel()
            draftLoading = false
            loaded?.let(::show)
        }
    }

    /** The tab went: a save still waiting goes, as it would have a second later, its mic closes, and a first message waiting goes with it. */
    fun stop() {
        firstSendAttempt++
        firstSendJob?.cancel()
        editJob?.cancel()
        closeMic?.invoke()
        draftLoad?.cancel()
        drafts?.flush()
        controller?.stop()
        controller = null
        turnsJob?.cancel()
        turnsJob = null
        changesJob?.cancel()
        sessionChanges = null
    }
}

/** How long Reply may wait for a tab's draft (`GATE_RELEASE_MS`). */
private const val DRAFT_GATE_MILLIS = 3_000L

/** How long a `tab_open` waits for its chat's project to come up before it says it could not. */
private const val CHAT_PROJECT_WAIT_MS = 15_000L

/**
 * What `toast.error(title, { description, action })` shows: a title, a line under it, and one
 * button that runs [action] and closes the toast. Each one is a toast of its own, even with the
 * text of the last. Its [kind] is the icon in front: `toast.info`'s, or a plain `toast()`'s none.
 */
class ErrorToastMessage(
    val title: String,
    val description: String? = null,
    val action: Action? = null,
    val kind: Kind = Kind.ERROR,
    /** `duration: Infinity`: it stays until it is closed, its action runs or its raiser takes it back. */
    val persistent: Boolean = false,
    /** `duration: n`: how long it stays, in milliseconds; sonner's four seconds when none is given. */
    val durationMillis: Long? = null,
    val cancel: Action? = null,
    val onDismiss: (() -> Unit)? = null,
) {
    class Action(val label: String, val run: () -> Unit)

    enum class Kind { ERROR, INFO, SUCCESS, WARNING, PLAIN }
}

/**
 * A toast in the stack, as sonner keeps one: what it says, which its raiser may change while it is
 * up, and whether it was dismissed (`delete`) — it then slides out, and the toaster takes it off
 * the stack once it is out of sight.
 */
@Stable
class AppToast internal constructor(val id: Long, message: ErrorToastMessage) {
    var message by mutableStateOf(message)
        internal set

    /** Closed, timed out, swiped away or taken back by its raiser. */
    var dismissed by mutableStateOf(false)
        private set

    private var dismissNotified = false

    fun dismiss(byAction: Boolean = false) {
        if (dismissed) return
        dismissed = true
        if (!byAction && !dismissNotified) {
            dismissNotified = true
            message.onDismiss?.invoke()
        }
    }
}

/** The lightbox open on [images] at [index] (`useImageOverlay`'s state). */
class LightboxRequest(val images: List<GalleryImage>, val index: Int)

/** The command the output panel is open on, and the chat tab whose registry keeps its status. */
data class BackgroundOutputRequest(val tabId: String, val shellId: String)

/** The MCP server a sign-in is open on, and the flow that signs in to it — none for a claude.ai connector. */
class McpSignIn(val serverName: String, val flow: McpSignInFlow?)
