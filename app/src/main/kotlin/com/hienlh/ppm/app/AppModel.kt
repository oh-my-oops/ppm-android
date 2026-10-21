package com.hienlh.ppm.app

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
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
import com.hienlh.ppm.chat.ToolImage
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
import com.hienlh.ppm.client.uploadedFile
import com.hienlh.ppm.client.putUiPrefs
import com.hienlh.ppm.client.renameSession
import com.hienlh.ppm.client.reorderProjects
import com.hienlh.ppm.client.resetTags
import com.hienlh.ppm.client.setDefaultTag
import com.hienlh.ppm.client.setTag
import com.hienlh.ppm.client.uiPrefs
import com.hienlh.ppm.client.updateTag
import com.hienlh.ppm.client.webFile
import com.hienlh.ppm.design.GalleryImage
import com.hienlh.ppm.design.PpmTheme
import com.hienlh.ppm.design.ViewerImage
import com.hienlh.ppm.protocol.ChatDraft
import com.hienlh.ppm.protocol.ChatReply
import com.hienlh.ppm.protocol.ChatSearchHit
import com.hienlh.ppm.protocol.DraftAttachment
import com.hienlh.ppm.protocol.FileMention
import com.hienlh.ppm.protocol.PickedAccount
import com.hienlh.ppm.protocol.ProjectInfo
import com.hienlh.ppm.protocol.ProjectTags
import com.hienlh.ppm.protocol.ProviderInfo
import com.hienlh.ppm.protocol.ReplyReference
import com.hienlh.ppm.protocol.ServerInfo
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.SessionTag
import com.hienlh.ppm.protocol.SessionUnread
import com.hienlh.ppm.protocol.SlashItem
import com.hienlh.ppm.protocol.SlashTrigger
import com.hienlh.ppm.protocol.ThemeSelection
import com.hienlh.ppm.protocol.TurnOptions
import com.hienlh.ppm.protocol.double
import com.hienlh.ppm.protocol.sortedForList
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.ChatController
import com.hienlh.ppm.state.ChatSearch
import com.hienlh.ppm.state.ClientCommand
import com.hienlh.ppm.state.DraftLocalStore
import com.hienlh.ppm.state.DraftSync
import com.hienlh.ppm.state.FileIndexStore
import com.hienlh.ppm.state.GlobalEvents
import com.hienlh.ppm.state.HealthCheck
import com.hienlh.ppm.state.HealthWatch
import com.hienlh.ppm.state.HistoryList
import com.hienlh.ppm.state.KeyBindings
import com.hienlh.ppm.state.MessagePriority
import com.hienlh.ppm.state.ModelCatalog
import com.hienlh.ppm.state.NewChatCache
import com.hienlh.ppm.state.workspace.NewChatDefaults
import com.hienlh.ppm.state.PermissionMode
import com.hienlh.ppm.state.RejectedSend
import com.hienlh.ppm.state.messageWithAttachments
import com.hienlh.ppm.state.SlashItemsStore
import com.hienlh.ppm.state.selectInlineImages
import com.hienlh.ppm.state.windows.MAX_WINDOWS
import com.hienlh.ppm.state.windows.WindowKind
import com.hienlh.ppm.state.windows.WindowStore
import com.hienlh.ppm.state.windows.WindowsState
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
import com.hienlh.ppm.state.workspace.openAiTab
import com.hienlh.ppm.state.workspace.resolveFileNavigation
import com.hienlh.ppm.state.workspace.resolveToolCallTarget
import com.hienlh.ppm.state.workspace.buildMetadataFromUrl
import com.hienlh.ppm.state.workspace.buildTitleFromUrl
import com.hienlh.ppm.state.workspace.isWindowPanelId
import com.hienlh.ppm.state.workspace.parsePpmLink
import com.hienlh.ppm.state.workspace.reopenedTab
import com.hienlh.ppm.state.workspace.tabIdFromUrl
import com.hienlh.ppm.state.workspace.tabSessionId
import com.hienlh.ppm.state.workspace.workspaceRemote
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
class AppModel(context: Context, val debug: DebugLaunch? = null) {
    sealed interface Load {
        data object Idle : Load
        data object Loading : Load
        data object Loaded : Load
        data class Failed(val message: String) : Load
    }

    /** The main thread: every controller below is driven from it. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = PrefsStore(context)

    /** Every message box's draft, in a file of its own: it is written on each keystroke. */
    private val draftPrefs = PrefsStore(context, "ppm_drafts")
    val store = ServerStore(prefs, KeystoreSecrets(context))

    /** The tab switcher's order, `default` or `recent`, kept on this device as the web keeps it. */
    var tabSortMode by mutableStateOf(prefs.get("tabSortMode") ?: "default")
        private set

    var api by mutableStateOf<ApiClient?>(null)
        private set

    /** Each project's `/` list per provider (`slash-items-cache.ts`), for the open server. */
    var slashItems by mutableStateOf<SlashItemsStore?>(null)
        private set

    /** The open project's file list, which the `@` picker searches (the web's file-store index). */
    var fileIndex by mutableStateOf<FileIndexStore?>(null)
        private set
    var info by mutableStateOf<ServerInfo?>(null)
        private set
    var projects by mutableStateOf(emptyList<ProjectInfo>())
        private set
    var projectsLoad by mutableStateOf<Load>(Load.Idle)
        private set
    var selectedProject by mutableStateOf<String?>(null)
        private set

    var sessions by mutableStateOf(emptyList<SessionInfo>())
        private set
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

    /** The open server's OS (`/api/system/host`), kept per server so the first frame already knows it. */
    var hostPlatform by mutableStateOf<String?>(null)
        private set

    /** The skin every window wears (use-explorer-skin.ts): the one picked, else Windows' on a Windows server, macOS' on any other. */
    val windowsSkin: Boolean
        get() = when (explorerSkin) {
            "windows" -> true
            "macos" -> false
            else -> hostPlatform == "win32"
        }

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

    /** The chat tabs with no session yet: their preparation, provider, account and first message. */
    internal val newChats = NewChats(this)

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

    init {
        restoreUiPrefs()
        // Message boxes start empty, so no copy of a file waiting in one can be wanted again.
        val appContext = context.applicationContext
        scope.launch(Dispatchers.IO) { clearAttachmentCopies(appContext) }
    }

    /** The theme picked on any device (`/api/settings/theme`). */
    var themeSelection by mutableStateOf(ThemeSelection("aurora", "system"))
        private set
    var systemIsDark by mutableStateOf(true)
    val theme: PpmTheme get() = PpmTheme.resolve(themeSelection.style, themeSelection.mode, systemIsDark)

    /** The app-wide socket of the open server: running and unread sessions. */
    var events by mutableStateOf<GlobalEvents?>(null)
        private set

    /** Each project's branch and how far it is from its upstream, for the tablet's status bar (the web's git `meta`). */
    val gitBranches = mutableStateMapOf<String, GitBranchStatus>()

    /** The projects PPM just wrote to a repository of (`git:changed`). */
    private val gitChanged = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** Whether the app is in front (the web's `document.hidden`, inverted). */
    var windowVisible = true
        set(value) {
            field = value
            // In the background Android may end the process at any time: the layout goes out now,
            // and so does each draft still waiting out its second.
            if (value) {
                activeSessionId?.let { events?.seen(it) }
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

    /** The error toast on screen, if any. */
    var toast by mutableStateOf<ErrorToastMessage?>(null)

    /** An error toast that is only its title. */
    var actionError: String?
        get() = toast?.title
        set(value) {
            toast = value?.let(::ErrorToastMessage)
        }

    /** The session whose Delete was chosen, until the user confirms. */
    var confirmDeleteSession by mutableStateOf<SessionInfo?>(null)

    /** The image lightbox, while it is open (`useImageOverlay`). */
    var lightbox by mutableStateOf<LightboxRequest?>(null)

    /** What the command palette opened with while it is open; null while it is closed. */
    var paletteQuery by mutableStateOf<String?>(null)
        private set

    /** The keyboard shortcuts in force: the server's overrides over the web's defaults. */
    var keyBindings by mutableStateOf(KeyBindings())
        private set

    /** Alt+↑ / Alt+↓ for a chat, by its tab's id: -1 for the user's previous message, 1 for the next. */
    val chatJumps = MutableSharedFlow<Pair<String, Int>>(extraBufferCapacity = 8)

    /** The chat mic, one for every message box. */
    val voice = VoiceInput(context, scope, api = { api }, toast = { toast = it })

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
        if (serverKey != historyServer) {
            historyServer = serverKey
            history.clear()
            canGoBack = false
        }
        closeWorkspace()
        events?.stop()
        events = null
        api = null
        slashItems = null
        fileIndex = null
        info = null
        projects = emptyList()
        projectsLoad = Load.Idle
        selectedProject = null
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
        slashItems = SlashItemsStore(scope)
        fileIndex = FileIndexStore(api, scope)
        openWorkspace(api)
        startEvents(api)
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

    suspend fun loadProjects() {
        val api = api ?: return
        projectsLoad = Load.Loading
        try {
            val projects = coroutineScope {
                val info = async { runCatching { api.info() }.getOrNull() }
                val uiPrefs = async { quietly { api.uiPrefs() } }
                val keys = async { quietly { api.keybindings() } }
                val projects = api.projects()
                this@AppModel.info = info.await()
                keys.await()?.let { keyBindings = KeyBindings(it) }
                uiPrefs.await()?.let { prefs ->
                    prefs.projectSortMode?.takeIf { it in PROJECT_SORT_MODES }?.let { projectSortMode = it }
                    projectOpenedAt = prefs.recentOpen
                    prefs.sidebarCollapsed?.let { sidebarCollapsed = it }
                    prefs.sidebarWidth?.let { sidebarWidth = it }
                    prefs.keepScreenAwake?.let { keepScreenAwake = it }
                    prefs.explorerSkin?.let { explorerSkin = it }
                    prefs.tabWrap?.let { tabWrap = it }
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
            link?.projectName?.takeIf { linked == null }?.let { actionError = noSuchProject(it) }
            val last = linked ?: debug?.project ?: prefs.get("lastProject.$serverKey")
            when {
                last != null && projects.any { it.name == last } ->
                    openProject(last, then = link?.takeIf { linked != null }?.let { l -> { store: WorkspaceStore -> openFromLink(store, l, last) } })
                projects.isNotEmpty() -> openProject(projects.first().name)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            projectsLoad = Load.Failed(describe(error))
        }
    }

    /** Identifies the open server in saved state, so each server keeps its own tabs. */
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
    }

    private fun saveUiPrefs() {
        prefs.put("uiPrefs.$serverKey", UiPrefs(projectSortMode, projectOpenedAt, sidebarCollapsed, sidebarWidth, keepScreenAwake, explorerSkin, tabWrap).toJson().toString())
    }

    private fun startEvents(api: ApiClient) {
        val events = GlobalEvents(api, scope)
        // Whatever changed while the socket was down — another device renamed, pinned or
        // started a chat — was never delivered.
        events.onReady = { scope.launch { refreshSessions(quiet = true) } }
        events.isShown = { id -> windowVisible && activeSessionId == id }
        events.onGitChanged = { gitChanged.tryEmit(it) }
        // A change only marks the list stale; it is read again when a picker shows it.
        events.onFileChanged = { fileIndex?.markStale(it) }
        events.onIndexChanged = { fileIndex?.rebuilt(it) }
        this.events = events
        events.start()
    }

    /**
     * The app came to the front: replace a socket that died meanwhile, and read the session
     * list again if it has not been read for a while (the web does both when a page shows).
     */
    fun becameActive() {
        events?.wake()
        refreshWorkspace()
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
            actionError = describe(error)
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
            actionError = describe(error)
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
            actionError = describe(error)
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
            actionError = describe(error)
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
    fun openFileTab(project: String, path: String) {
        withWorkspace { store ->
            store.openTab(
                NewTab(
                    type = "editor",
                    title = path.split('/', '\\').last(),
                    projectId = project,
                    metadata = buildJsonObject {
                        put("filePath", path)
                        put("projectName", project)
                    },
                ),
            )
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
                toast = ErrorToastMessage(title, describe(error))
                return@launch
            }
            openProject(project) { store ->
                try {
                    store.openAiTab(target, sessionId)
                } catch (error: IllegalStateException) {
                    toast = ErrorToastMessage(title, describe(error))
                }
            }
        }
    }

    /**
     * A file a chat message names in [project], by a link or an inline `path:line`
     * (`useMarkdownFileNavigation`): an editor tab at the lines named, a folder in an explorer, and
     * anything the host cannot open searched for in the palette.
     */
    fun openFileOrSearch(project: String, path: String, line: SourceLine?) {
        val api = api ?: return
        if (path.isEmpty()) return
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

    /** open-explorer.ts: a folder in an explorer window on a tablet. A phone's full-screen explorer is not in the app yet. */
    private fun openExplorer(path: String) {
        val windows = windows
        when {
            !wide || windows == null -> toast = ErrorToastMessage(
                "${path.trimEnd('/', '\\').split('/', '\\').last()} is a folder",
                "Folders do not open in the Android app yet.",
                kind = ErrorToastMessage.Kind.PLAIN,
            )
            windows.state.value.windows.size >= MAX_WINDOWS -> actionError = TOO_MANY_WINDOWS
            else -> windows.open(WindowKind.Explorer, buildJsonObject { put("path", path) })
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

    /**
     * Keeps [project]'s branch in [gitBranches] while the status bar shows it (`useGitChangesPoller`):
     * read every 10 s, and 300 ms after PPM writes to the repository, so a burst of writes costs one
     * read. A project with no repository, or several to choose from, shows no branch.
     */
    suspend fun watchGit(project: String) {
        val api = api ?: return
        val repo = quietly { api.gitRepos(project) }?.resolved ?: return
        suspend fun read() {
            // A failed read keeps the branch last shown.
            val status = quietly { api.gitStatus(project, repo) } ?: return
            if (this.api === api) gitBranches[project] = status
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

    /** Opens [name]; [then] runs on its tabs once they are shown, or at once when it is already open. */
    suspend fun openProject(name: String, then: ((WorkspaceStore) -> Unit)? = null) {
        val api = api ?: return
        if (name == selectedProject && sessionsLoad != Load.Idle) {
            then?.let(::withWorkspace)
            return
        }
        selectedProject = name
        waitingForWorkspace.clear()
        then?.let { waitingForWorkspace += it }
        events?.project = name
        resetProjectState()
        if (debug == null) prefs.put("lastProject.$serverKey", name)
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
        debug?.session?.let { id -> sessions.firstOrNull { it.id == id }?.let(::openTab) }
    }

    private fun resetProjectState() {
        fileIndex?.reset()
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
                if (!quiet) actionError = describe(error)
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

    // Tabs

    private fun openWorkspace(api: ApiClient) {
        val cache = WorkspaceCache(prefs, "workspace.$serverKey.", api.workspaceRemote(), scope, windowPanelsKey = "windowPanels.$serverKey")
        // One set of windows per server, as the web keeps one per origin.
        val windowStore = WindowStore(prefs, "windows.$serverKey")
        // Asked at each change, as the web asks `useIsMobile`: a phone opens every tab in the first panel.
        // A new chat tab is resolved from this device's cache before its first frame, as `openTab` resolves it.
        val chatCache = newChatCache
        val store = WorkspaceStore(cache, isMobile = { !wide }, windows = windowStore) { project ->
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
                    reconcileWindowPanels()
                }
            }
            store.state.collect { state ->
                syncChats(state)
                layout = state
                recordNavigation(state)
                reconcileWindowPanels()
            }
        }
    }

    /** A server's tabs go when another opens; their last changes still go out to it. */
    private fun closeWorkspace() {
        workspaceCache?.flush()
        workspaceJob?.cancel()
        workspaceJob = null
        workspace = null
        workspaceCache = null
        layout = null
        windows = null
        windowState = WindowsState()
        chats.values.forEach { it.stop() }
        chats.clear()
        shownSession = null
        waitingForWorkspace.clear()
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

    /** Runs [open] on the selected project's layout: a tab opened before it is shown would land in no project's. */
    private fun withWorkspace(open: (WorkspaceStore) -> Unit) {
        val store = workspace ?: return
        if (selectedProject != null && store.state.value.currentProject == selectedProject) open(store) else waitingForWorkspace += open
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
        for (panel in state.panels.values) for (tab in panel.tabs) if (tab.type == "chat") live[tab.id] = tab
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
                chat.clientCommand = { text -> runClientCommand(chat, text) }
                chat.closeMic = { voice.closeFor(chat) }
                chat.firstSend = { message -> newChats.sendFirst(chat, message) }
                chat.prewarmTouch = { newChats.touchPrewarm(chat) }
                chat.modePicked = { mode -> patchTabMetadata(id, "permissionMode" to JsonPrimitive(mode.value), "permissionModeSource" to JsonPrimitive("user")) }
                newChats.follow(chat, tab)
            }
        }
        val active = state.activeGridTab
        val api = api
        if (api != null) {
            // A phone shows the focused panel's tab; the desktop layout shows each grid panel's,
            // and each floating window's (one in the dock too: its tab stays live, as on the web).
            val shown = if (wide) {
                (state.gridPanelIds + state.panels.keys.filter(::isWindowPanelId)).mapNotNull { state.panels[it]?.activeTab }
            } else {
                listOfNotNull(active)
            }
            for (tab in shown) {
                val chat = chats[tab.id]?.takeUnless { it.started } ?: continue
                // Prepared first, so its draft and its gate join that one request.
                newChats.prepare(chat)
                chat.start(api, scope, DraftLocalStore(draftPrefs, serverKey), newChats.preparedDraft(chat))
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

    /** The tab bar's tabs, the phone's merged one: every grid panel's, this project's, with the panel each is in. */
    val openTabs: List<Pair<WorkspaceTab, String>> get() = layout?.mergedTabs.orEmpty()

    /** The tab the main area shows. */
    val activeTab: WorkspaceTab? get() = layout?.activeGridTab
    val activeTabId: String? get() = activeTab?.id

    /** Read from the store rather than [layout]: the global socket asks between two frames. */
    private val activeSessionId: String? get() = tabSessionId(workspace?.state?.value?.activeGridTab)

    /** The chat of a chat tab; none for a tab whose chat has not started. */
    fun chat(tabId: String): ChatTab? = chats[tabId]

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
        val project = selectedProject ?: return
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
            actionError = "This is not a PPM link: $link"
            return
        }
        val where = parsed.host + (parsed.port?.let { ":$it" } ?: "")
        fun matches(url: HttpUrl) = url.host.equals(parsed.host, ignoreCase = true) && (parsed.port == null || parsed.port == url.port)
        if (debug != null) {
            if (!matches(debug.url)) {
                actionError = "This link is for $where, not for the server open here."
                return
            }
        } else {
            val server = store.servers.value.firstOrNull { ApiClient.normalizedServerUrl(it.url)?.let(::matches) == true } ?: run {
                actionError = "No server at $where is saved here. Add it in Settings, Servers."
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
        val name = parsed.state.projectName ?: return
        if (projects.none { it.name == name }) {
            actionError = noSuchProject(name)
            return
        }
        scope.launch { openProject(name) { store -> openFromLink(store, parsed.state, name) } }
    }

    private fun noSuchProject(name: String) = "There is no project called \"$name\" on this server."

    /** The link's tab, then its legacy `?openChat=`, as app.tsx opens them. */
    private fun openFromLink(store: WorkspaceStore, link: UrlState, project: String) {
        link.tabType?.let { openFromUrl(store, it, link.tabIdentifier, project) }
        link.openChat?.let { openFromUrl(store, "chat", it, project) }
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
    fun openSettings() {
        val windows = windows
        if (!wide || windows == null) {
            withWorkspace { it.openTab(NewTab("settings", "Settings")) }
            return
        }
        val existing = windows.state.value.windows.values.firstOrNull { it.kind == WindowKind.Settings }
        when {
            existing != null -> windows.focus(existing.id)
            // At the cap the web's `open` raises the oldest window instead, which is not Settings.
            windows.state.value.windows.size >= MAX_WINDOWS -> actionError = TOO_MANY_WINDOWS
            else -> windows.open(WindowKind.Settings)
        }
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
    fun popOutTab(tabId: String, panelId: String) {
        val store = workspace ?: return
        if (store.popOutTab(tabId, panelId) == null) actionError = TOO_MANY_WINDOWS
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

    fun closeTab(tabId: String, panelId: String? = panelOf(tabId)) {
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

    /**
     * Shows or hides the dock. The web's toggle opens a terminal in an empty dock; this app has
     * no terminal yet, so it only shows what another device left there.
     */
    fun setDockVisible(visible: Boolean) {
        workspace?.setDockVisible(visible)
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

    /** Close Others: the other closable tabs of [keep]'s panel (`mobile-nav.tsx`). */
    fun closeOtherTabs(keep: String) {
        val panel = panelTabs(keep) ?: return
        closeAll(panel.second, panel.first.filter { it.id != keep && it.closable }.map { it.id })
    }

    /** Close to the Right: the closable tabs after [tabId] in its panel. */
    fun closeTabsToRight(tabId: String) {
        val panel = panelTabs(tabId) ?: return
        closeAll(panel.second, panel.first.dropWhile { it.id != tabId }.drop(1).filter { it.closable }.map { it.id })
    }

    /** Whether the tab menu offers Close Others: another closable tab in the panel. */
    fun canCloseOthers(tabId: String): Boolean = panelTabs(tabId)?.first?.any { it.id != tabId && it.closable } == true

    /** Whether it offers Close to the Right: the tab is not its panel's last. */
    fun hasTabsToRight(tabId: String): Boolean = panelTabs(tabId)?.first?.let { tabs -> tabs.indexOfFirst { it.id == tabId } in 0 until tabs.lastIndex } == true

    private fun panelTabs(tabId: String): Pair<List<WorkspaceTab>, String>? {
        val panelId = panelOf(tabId) ?: return null
        val panel = layout?.panels?.get(panelId) ?: return null
        return panel.tabs to panelId
    }

    private fun closeAll(panelId: String, ids: List<String>) {
        val store = workspace ?: return
        for (id in ids) store.closeTab(id, panelId)
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
        // A first message still on its way must not land in the chat picked here.
        chats[tabId]?.let { newChats.abandon(it, "You switched to another chat before it connected.") }
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
        patchTabMetadata(tab.tabId, "pendingMessage" to null)
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
                actionError = "Cancel reply before running this command"
                false
            }
            is ClientCommand.Clear -> {
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
        actionError = describe(error)
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

private val DOCK_POSITIONS = setOf("bottom", "left", "right")

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
        private set

    /** Its tab's metadata and title as the layout last had them (the web's `metadata` prop), kept by the model. */
    internal var metadata by mutableStateOf(JsonObject(emptyMap()))
    var tabTitle by mutableStateOf("")
        internal set

    /** The account its claim names, while that claim is for its provider (`claimMatchesProvider`). */
    val claimedAccountId: String?
        get() = metadata["pickedAccountId"].string?.takeIf { it.isNotEmpty() && metadata["pickedAccountProvider"].string == providerId }

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

    /** It took its tab's `pendingMessage` into the box; the model drops it from the tab once the chat is connected. */
    var tookPendingMessage = false

    /** The box takes the focus once first shown (`autoFocus`): a new chat's, or one handed a `pendingMessage`. */
    var focusOnShow = session == null

    val started: Boolean get() = controller != null

    /** What is typed in this tab's message box, kept while another tab is in front. */
    val draft = TextFieldState()

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

    /** For a message sent while the turn runs; back to Queue after each send. */
    var priority by mutableStateOf(MessagePriority.DEFAULT)

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

    /** The slash picker's highlighted row (`selectedIndex`), the first again whenever its query changes. */
    var slashSelection by mutableIntStateOf(0)

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

    // A closed picker's filter is "" on the web, so one opened again on nothing typed keeps its row.
    private fun showSlash(query: String?) {
        if ((query ?: "") != (slashQuery ?: "")) slashSelection = 0
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
        if (chat == null || (typed.isEmpty() && ready.isEmpty() && agent == null) || (!first && !chat.state.value.canSend) || firstSendPending) {
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
        val images = selectInlineImages(ready.map { it.imageData })
        val content = messageWithAttachments(text, ready, images)
        val imagePaths = ready.filter { it.isImage }.mapNotNull { it.serverPath }
        if (first) {
            // Kept on this device before the box empties: until the message is on a socket, the
            // draft is its only other copy. Only a save still waiting for the server is dropped.
            saveDraft()
            drafts?.cancelPendingSave()
            val message = FirstMessage(text, content, images, imagePaths, replyTo)
            emptyBox()
            firstSend?.invoke(message)
            return
        }
        // The priority goes only with a message sent during a turn, and is Queue again after it.
        chat.send(
            content,
            TurnOptions(permissionMode = permissionMode?.value, priority = if (chat.state.value.isRunning) priority.value else null),
            replyTo,
            images,
            imagePaths,
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
        providerId = session.providerId
        firstQueued = true
        drafts?.moveTo(session.id)
        controller?.adopt(session)
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
        controller?.send(message.content, TurnOptions(permissionMode = permission), message.replyTo, message.images, message.imagePaths)
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
        message?.let { restore(RejectedSend(it.content, it.replyTo, reason)) }
    }

    private fun endFirstSend() {
        firstMessage = null
        firstQueued = false
        firstSendPending = false
        firstSendPreview = null
    }

    /** The box after a send: no text, files, agent or reply, Queue again. */
    private fun emptyBox() {
        for (attachment in attachments) attachment.file?.parentFile?.deleteRecursively()
        draft.clearText()
        attachments.clear()
        agent = null
        replyTo = null
        pendingSend = false
        priority = MessagePriority.DEFAULT
        historyIndex = -1
    }

    /** A refused message back in the box (`restoreUnsentMessage`): after what is typed there, with the reply it carried. */
    fun restore(rejected: RejectedSend) {
        val typed = draft.text.toString()
        draft.setTextAndPlaceCursorAtEnd(if (typed.isNotBlank()) "$typed\n\n${rejected.content}" else rejected.content)
        replyTo = rejected.replyTo
        edited()
        requestFocus()
    }

    /**
     * Its chat connected (once it has a session), and its draft loaded from [draftStore], from
     * what its preparation read ([preparedDraft]) or from the server.
     */
    fun start(api: ApiClient, scope: CoroutineScope, draftStore: DraftLocalStore, preparedDraft: (suspend () -> ChatDraft?)? = null) {
        if (controller != null) return
        historyList = HistoryList(api, project, scope)
        welcomeList = HistoryList(api, project, scope)
        controller = ChatController(api, project, session, scope, providerId = providerId).also { it.start() }
        val drafts = DraftSync(api, project, tabId, session?.id ?: DraftSync.NEW_CHAT, draftStore, scope).also { drafts = it }
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
        closeMic?.invoke()
        draftLoad?.cancel()
        drafts?.flush()
        controller?.stop()
        controller = null
    }
}

/** How long Reply may wait for a tab's draft (`GATE_RELEASE_MS`). */
private const val DRAFT_GATE_MILLIS = 3_000L

/**
 * What `toast.error(title, { description, action })` shows: a title, a line under it, and one
 * button that runs [action] and closes the toast. Each one is a toast of its own, even with the
 * text of the last. Its [kind] is the icon in front: `toast.info`'s, or a plain `toast()`'s none.
 */
class ErrorToastMessage(val title: String, val description: String? = null, val action: Action? = null, val kind: Kind = Kind.ERROR) {
    class Action(val label: String, val run: () -> Unit)

    enum class Kind { ERROR, INFO, PLAIN }
}

/** The lightbox open on [images] at [index] (`useImageOverlay`'s state). */
class LightboxRequest(val images: List<GalleryImage>, val index: Int)
