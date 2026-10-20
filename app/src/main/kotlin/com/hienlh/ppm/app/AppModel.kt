package com.hienlh.ppm.app

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
import com.hienlh.ppm.client.newChatProviderSettings
import com.hienlh.ppm.client.projectImage
import com.hienlh.ppm.client.putUiPrefs
import com.hienlh.ppm.client.renameSession
import com.hienlh.ppm.client.reorderProjects
import com.hienlh.ppm.client.resetTags
import com.hienlh.ppm.client.setDefaultTag
import com.hienlh.ppm.client.setTag
import com.hienlh.ppm.client.uiPrefs
import com.hienlh.ppm.client.updateTag
import com.hienlh.ppm.design.PpmTheme
import com.hienlh.ppm.protocol.ChatSearchHit
import com.hienlh.ppm.protocol.ProjectInfo
import com.hienlh.ppm.protocol.ProjectTags
import com.hienlh.ppm.protocol.ProviderInfo
import com.hienlh.ppm.protocol.ServerInfo
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.SessionTag
import com.hienlh.ppm.protocol.SessionUnread
import com.hienlh.ppm.protocol.ThemeSelection
import com.hienlh.ppm.protocol.double
import com.hienlh.ppm.protocol.sortedForList
import com.hienlh.ppm.state.ChatController
import com.hienlh.ppm.state.ChatSearch
import com.hienlh.ppm.state.GlobalEvents
import com.hienlh.ppm.state.HistoryList
import com.hienlh.ppm.state.MessagePriority
import com.hienlh.ppm.state.ModelCatalog
import com.hienlh.ppm.state.PermissionMode
import com.hienlh.ppm.state.workspace.NewTab
import com.hienlh.ppm.state.workspace.SplitDirection
import com.hienlh.ppm.state.workspace.WorkspaceCache
import com.hienlh.ppm.state.workspace.WorkspaceState
import com.hienlh.ppm.state.workspace.WorkspaceStore
import com.hienlh.ppm.state.workspace.WorkspaceTab
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
    val store = ServerStore(prefs, KeystoreSecrets(context))

    /** The tab switcher's order, `default` or `recent`, kept on this device as the web keeps it. */
    var tabSortMode by mutableStateOf(prefs.get("tabSortMode") ?: "default")
        private set

    var api by mutableStateOf<ApiClient?>(null)
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

    /**
     * Where the dock sits: `bottom`, `left` or `right`. The web keeps it in the browser (the
     * server drops the key), so each device has its own.
     */
    var dockPosition by mutableStateOf(prefs.get("dockPosition")?.takeIf { it in DOCK_POSITIONS } ?: "bottom")
        private set

    /** The chat behind each chat tab in memory, by tab id; a tab whose chat has not started has none. */
    private val chats = mutableStateMapOf<String, ChatTab>()

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
            // In the background Android may end the process at any time: the layout goes out now.
            if (value) activeSessionId?.let { events?.seen(it) } else workspaceCache?.flush()
        }

    var showSignIn by mutableStateOf(false)

    /** Settings' Add server was chosen: the connect screen shows over everything until it is done or cancelled. */
    var addingServer by mutableStateOf(false)
    var actionError by mutableStateOf<String?>(null)

    /** The session whose Delete was chosen, until the user confirms. */
    var confirmDeleteSession by mutableStateOf<SessionInfo?>(null)

    /** What the command palette opened with while it is open; null while it is closed. */
    var paletteQuery by mutableStateOf<String?>(null)
        private set

    /** Text for a new chat's message box, by session, until its tab shows (the web's `pendingMessage`). */
    private val pendingDrafts = mutableMapOf<String, String>()

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
        closeWorkspace()
        events?.stop()
        events = null
        api = null
        info = null
        projects = emptyList()
        projectsLoad = Load.Idle
        selectedProject = null
        gitBranches.clear()
        resetProjectState()
        restoreUiPrefs()
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
        openWorkspace(api)
        startEvents(api)
        coroutineScope {
            launch { loadTheme() }
            loadProjects()
        }
    }

    suspend fun loadProjects() {
        val api = api ?: return
        projectsLoad = Load.Loading
        try {
            val projects = coroutineScope {
                val info = async { runCatching { api.info() }.getOrNull() }
                val uiPrefs = async { quietly { api.uiPrefs() } }
                val projects = api.projects()
                this@AppModel.info = info.await()
                uiPrefs.await()?.let { prefs ->
                    prefs.projectSortMode?.takeIf { it in PROJECT_SORT_MODES }?.let { projectSortMode = it }
                    projectOpenedAt = prefs.recentOpen
                    prefs.sidebarCollapsed?.let { sidebarCollapsed = it }
                    prefs.sidebarWidth?.let { sidebarWidth = it }
                    saveUiPrefs()
                }
                projects
            }
            this.projects = projects
            projectsLoad = Load.Loaded
            val last = debug?.project ?: prefs.get("lastProject.$serverKey")
            when {
                last != null && projects.any { it.name == last } -> openProject(last)
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
    }

    private fun saveUiPrefs() {
        prefs.put("uiPrefs.$serverKey", UiPrefs(projectSortMode, projectOpenedAt, sidebarCollapsed, sidebarWidth).toJson().toString())
    }

    private fun startEvents(api: ApiClient) {
        val events = GlobalEvents(api, scope)
        // Whatever changed while the socket was down — another device renamed, pinned or
        // started a chat — was never delivered.
        events.onReady = { scope.launch { refreshSessions(quiet = true) } }
        events.isShown = { id -> windowVisible && activeSessionId == id }
        events.onGitChanged = { gitChanged.tryEmit(it) }
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

    /** A tab's title: the list's, which carries a rename made anywhere, else the tab's own. */
    fun title(tab: ChatTab, liveTitle: String?): String {
        sessions.firstOrNull { it.id == tab.session.id }?.title?.takeIf { it.isNotEmpty() }?.let { return it }
        return liveTitle?.takeIf { it.isNotEmpty() } ?: tab.session.title.ifEmpty { "New Chat" }
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
        chats.values.forEach { it.historyList?.remove(session.id) }
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
        for (tab in chats.values) tab.historyList?.patch(matches, change)
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

    suspend fun openProject(name: String) {
        val api = api ?: return
        if (name == selectedProject && sessionsLoad != Load.Idle) return
        selectedProject = name
        waitingForWorkspace.clear()
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
        val cache = WorkspaceCache(prefs, "workspace.$serverKey.", api.workspaceRemote(), scope)
        // Asked at each change, as the web asks `useIsMobile`: a phone opens every tab in the first panel.
        val store = WorkspaceStore(cache, isMobile = { !wide })
        workspaceCache = cache
        workspace = store
        hydratedOnce = false
        workspaceJob = scope.launch {
            store.state.collect { state ->
                syncChats(state)
                layout = state
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

    /** Follows the window's width; a wider window shows every grid panel, so their chats connect. */
    fun setWindowWide(value: Boolean) {
        if (wide == value) return
        wide = value
        workspace?.state?.value?.let(::syncChats)
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
            if (tab == null || tabSessionId(tab) != chat.session.id) {
                chat.stop()
                chats.remove(id)
            }
        }
        for ((id, tab) in live) {
            if (id in chats) continue
            val sessionId = tabSessionId(tab) ?: continue
            val project = tab.projectId ?: tab.metaString("projectName") ?: state.currentProject ?: continue
            val session = sessions.firstOrNull { it.id == sessionId }
                ?: SessionInfo(id = sessionId, providerId = tab.metaString("providerId") ?: "claude", title = tab.title)
            chats[id] = ChatTab(id, session, project).also { chat ->
                pendingDrafts.remove(sessionId)?.let { chat.draft.setTextAndPlaceCursorAtEnd(it) }
            }
        }
        val active = state.activeGridTab
        val api = api
        if (api != null) {
            // A phone shows the focused panel's tab; the desktop layout shows each grid panel's.
            val shown = if (wide) state.gridPanelIds.mapNotNull { state.panels[it]?.activeTab } else listOfNotNull(active)
            for (tab in shown) chats[tab.id]?.start(api, scope)
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

    /** Settings opens as a tab on a phone, as on the web's (`openSettings`). */
    fun openSettings() = withWorkspace { it.openTab(NewTab("settings", "Settings")) }

    /** Opens the command palette, its search holding [query]. */
    fun openPalette(query: String = "") {
        paletteQuery = query
    }

    fun closePalette() {
        paletteQuery = null
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
        store.updateTab(tabId) { tab ->
            tab.copy(title = session.title.ifEmpty { "Chat" })
                .withMetadata("sessionId" to JsonPrimitive(session.id), "providerId" to JsonPrimitive(session.providerId))
        }
        events?.markRead(session.id)
    }

    /** The title the server gives a chat goes onto its tab too, so every browser shows it (chat-tab.tsx). */
    fun retitleTab(tabId: String, title: String) {
        val store = workspace ?: return
        if (store.state.value.panelOf(tabId)?.tab(tabId)?.title == title) return
        store.updateTab(tabId) { it.copy(title = title) }
    }

    /** A chat tab made on the web before its first message has no session yet; this gives it one. */
    suspend fun startChat(tabId: String) {
        val api = api ?: return
        val project = selectedProject ?: return
        val store = workspace ?: return
        val provider = store.state.value.panelOf(tabId)?.tab(tabId)?.metaString("providerId")?.takeIf { it != "default" }
        try {
            val session = api.createSession(project, provider)
            if (selectedProject != project || workspace !== store) return
            sessions = (listOf(session) + sessions.filter { it.id != session.id }).sortedForList()
            store.updateTab(tabId) { tab ->
                tab.withMetadata(
                    "sessionId" to JsonPrimitive(session.id),
                    "providerId" to JsonPrimitive(session.providerId),
                    "providerPending" to null,
                    "focusedProviderOnOpen" to null,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            actionError = describe(error)
        }
    }

    /**
     * A new chat in a new tab (in [panelId] when given). With no [providerId], the provider the
     * web would pick: the last focused chat's when the server follows focus, else its default.
     * [draft] waits in its message box, to be edited before it is sent.
     */
    suspend fun newChat(providerId: String? = null, panelId: String? = null, draft: String? = null) {
        val api = api ?: return
        val project = selectedProject ?: return
        try {
            // Captured now, before another panel can take focus; unread, the server picks its default.
            val focused = layout?.lastFocusedChatProviders?.get(project)
            val provider = providerId ?: quietly { api.newChatProviderSettings() }?.resolve(focused)
            val session = api.createSession(project, provider)
            if (selectedProject != project) return
            sessions = (listOf(session) + sessions.filter { it.id != session.id }).sortedForList()
            if (draft != null) pendingDrafts[session.id] = draft
            openTab(session, panelId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            actionError = describe(error)
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

const val SIDEBAR_MIN_WIDTH = 200
const val SIDEBAR_DEFAULT_WIDTH = 280
const val SIDEBAR_MAX_WIDTH = 600

/**
 * The chat of one chat tab ([tabId] in the layout). Its connection starts when the tab is first
 * shown and stays up while the tab is open, so a tab behind still shows its turn streaming.
 */
@Stable
class ChatTab(val tabId: String, val session: SessionInfo, val project: String) {
    var controller by mutableStateOf<ChatController?>(null)
        private set

    /** What is typed in this tab's message box, kept while another tab is in front. */
    val draft = TextFieldState()

    /** This chat's History list, with its own search, tag chip and pages. */
    var historyList by mutableStateOf<HistoryList?>(null)
        private set

    /** Whether its History shows Manage tags; kept while the History list is closed, as on the web. */
    var showsTagSettings by mutableStateOf(false)

    /** Picked in the composer; until then the chip shows the fallback and messages carry none, so the server keeps the session's own. */
    var permissionMode by mutableStateOf<PermissionMode?>(null)

    /** For a message sent while the turn runs; back to Queue after each send. */
    var priority by mutableStateOf(MessagePriority.DEFAULT)

    fun start(api: ApiClient, scope: CoroutineScope) {
        if (controller != null) return
        historyList = HistoryList(api, project, scope)
        controller = ChatController(api, project, session, scope).also { it.start() }
    }

    fun stop() {
        controller?.stop()
        controller = null
    }
}
