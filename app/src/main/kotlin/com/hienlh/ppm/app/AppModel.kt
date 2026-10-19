package com.hienlh.ppm.app

import android.content.Context
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.client.ServerProfile
import com.hienlh.ppm.client.ServerStore
import com.hienlh.ppm.client.createTag
import com.hienlh.ppm.client.deleteSession
import com.hienlh.ppm.client.deleteSessions
import com.hienlh.ppm.client.deleteTag
import com.hienlh.ppm.client.putUiPrefs
import com.hienlh.ppm.client.renameSession
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
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.sortedForList
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.ChatController
import com.hienlh.ppm.state.ChatSearch
import com.hienlh.ppm.state.GlobalEvents
import com.hienlh.ppm.state.HistoryList
import com.hienlh.ppm.state.MessagePriority
import com.hienlh.ppm.state.ModelCatalog
import com.hienlh.ppm.state.PermissionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl

/**
 * What the app shows (`AppModel.swift`): the open server, its projects, a project's chats and
 * the chat tabs open on it, and the theme. Lives as long as the process, so a rotation or a
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

    /** Tab ids, the most recently shown first: the tab switcher's Recent order. */
    val recentTabs = mutableStateListOf<String>()

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

    val tabs = mutableStateListOf<ChatTab>()
    var activeTabId by mutableStateOf<String?>(null)
        private set

    /** The project sheet's order: `recent`, `priority` (the server's own) or `name`, shared through the server's UI prefs. */
    var projectSortMode by mutableStateOf("priority")
        private set

    /** When each project was last opened (epoch ms), for the Recent order and the age beside each. */
    var projectOpenedAt by mutableStateOf(emptyMap<String, Long>())
        private set

    /** The theme picked on any device (`/api/settings/theme`). */
    var themeSelection by mutableStateOf(ThemeSelection("aurora", "system"))
        private set
    var systemIsDark by mutableStateOf(true)
    val theme: PpmTheme get() = PpmTheme.resolve(themeSelection.style, themeSelection.mode, systemIsDark)

    /** The app-wide socket of the open server: running and unread sessions. */
    var events by mutableStateOf<GlobalEvents?>(null)
        private set

    /** Whether the app is in front (the web's `document.hidden`, inverted). */
    var windowVisible = true
        set(value) {
            field = value
            if (value) activeTabId?.let { events?.seen(it) }
        }

    var showSignIn by mutableStateOf(false)

    /** Settings' Add server was chosen: the connect screen shows over everything until it is done or cancelled. */
    var addingServer by mutableStateOf(false)
    var actionError by mutableStateOf<String?>(null)

    /** The session whose Delete was chosen, until the user confirms. */
    var confirmDeleteSession by mutableStateOf<SessionInfo?>(null)

    /** The tag whose Delete was chosen in Manage tags, until the user confirms. */
    var confirmDeleteTag by mutableStateOf<SessionTag?>(null)

    /** Delete old sessions was chosen, until the user says how old. */
    var askDeleteOld by mutableStateOf(false)

    /** Whether the Settings tab is open; it is the one shown while [activeTabId] is [SETTINGS_TAB]. */
    var settingsOpen by mutableStateOf(false)
        private set
    var deletingOld by mutableStateOf(false)
        private set

    val serverName: String get() = if (debug != null) "Debug server" else store.selected?.name ?: "PPM"

    /** Whether there is a server to show; otherwise the app asks for one. */
    val hasServer: Boolean get() = debug != null || store.servers.value.isNotEmpty()
    val activeTab: ChatTab? get() = tabs.firstOrNull { it.id == activeTabId }
    val currentProject: ProjectInfo? get() = projects.firstOrNull { it.name == selectedProject }

    /** Every open tab's id in the order shown: the chats, then Settings when it is open. */
    val tabIds: List<String> get() = tabs.map { it.id } + if (settingsOpen) listOf(SETTINGS_TAB) else emptyList()

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
        closeTabs()
        events?.stop()
        events = null
        api = null
        info = null
        projects = emptyList()
        projectsLoad = Load.Idle
        selectedProject = null
        resetProjectState()
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
                    prefs.projectSortMode?.takeIf { it in setOf("recent", "priority", "name") }?.let { projectSortMode = it }
                    projectOpenedAt = prefs.recentOpen
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

    private fun startEvents(api: ApiClient) {
        val events = GlobalEvents(api, scope)
        // Whatever changed while the socket was down — another device renamed, pinned or
        // started a chat — was never delivered.
        events.onReady = { scope.launch { refreshSessions(quiet = true) } }
        events.isShown = { id -> windowVisible && activeTabId == id }
        this.events = events
        events.start()
    }

    /**
     * The app came to the front: replace a socket that died meanwhile, and read the session
     * list again if it has not been read for a while (the web does both when a page shows).
     */
    fun becameActive() {
        events?.wake()
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
        sessions.firstOrNull { it.id == tab.id }?.title?.takeIf { it.isNotEmpty() }?.let { return it }
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
        tabs.forEach { it.historyList?.remove(session.id) }
        if (tabs.any { it.id == session.id }) closeTab(session.id)
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
        for (tab in tabs) tab.historyList?.patch(matches, change)
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
        for (tab in tabs) tab.historyList?.let { if (it.tag == tag.id) it.tag = null }
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
        val api = api ?: return
        scope.launch { quietly { api.putUiPrefs(buildJsonObject { put("projectSortMode", mode) }) } }
    }

    /** Opening a project puts it first in the Recent order, on every device (`touchRecent`). */
    private fun touchProject(name: String) {
        val times = projectOpenedAt + (name to System.currentTimeMillis())
        projectOpenedAt = times
        val api = api ?: return
        scope.launch {
            quietly { api.putUiPrefs(buildJsonObject { put("recentOpen", JsonObject(times.mapValues { JsonPrimitive(it.value) })) }) }
        }
    }

    suspend fun openProject(name: String) {
        val api = api ?: return
        if (name == selectedProject && sessionsLoad != Load.Idle) return
        closeTabs()
        selectedProject = name
        events?.project = name
        resetProjectState()
        if (debug == null) prefs.put("lastProject.$serverKey", name)
        touchProject(name)
        sidebarList = HistoryList(api, name, scope)
        homeList = HistoryList(api, name, scope)
        sidebarSearch = ChatSearch(api, name, scope)
        models = ModelCatalog(api, name)
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
        restoreTabs()
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

    /** Opens a session in a tab, or brings its tab forward. */
    fun openTab(session: SessionInfo) {
        if (tabs.none { it.id == session.id }) tabs += ChatTab(session)
        activate(session.id)
    }

    /**
     * Shows a tab. A tab the user picked has its unread cleared even when it was marked by
     * hand; one shown for them (restored, or next after a close) only loses the others.
     */
    fun activate(id: String, picked: Boolean = true) {
        if (id == SETTINGS_TAB) {
            if (!settingsOpen) return
            activeTabId = id
            touchRecent(id)
            return
        }
        val tab = tabs.firstOrNull { it.id == id } ?: return
        val api = api ?: return
        val project = selectedProject ?: return
        activeTabId = id
        touchRecent(id)
        if (picked) events?.markRead(id) else if (windowVisible) events?.seen(id)
        tab.start(api, project, scope)
        saveTabs()
    }

    private fun touchRecent(id: String) {
        recentTabs.remove(id)
        recentTabs.add(0, id)
    }

    fun pickTabSort(mode: String) {
        tabSortMode = mode
        prefs.put("tabSortMode", mode)
    }

    /** Settings opens as a tab of its own, as on the web's phone layout. */
    fun openSettings() {
        settingsOpen = true
        activate(SETTINGS_TAB)
    }

    fun closeTab(id: String) {
        recentTabs.remove(id)
        if (id == SETTINGS_TAB) {
            settingsOpen = false
            if (activeTabId == id) {
                activeTabId = null
                tabs.lastOrNull()?.let { activate(it.id, picked = false) }
            }
            return
        }
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        tabs[index].stop()
        tabs.removeAt(index)
        if (activeTabId == id) {
            activeTabId = null
            when {
                tabs.isNotEmpty() -> activate(tabs[maxOf(0, index - 1)].id, picked = false)
                settingsOpen -> activate(SETTINGS_TAB)
            }
        }
        saveTabs()
    }

    /** Close Others: every tab but [keep]. */
    fun closeOtherTabs(keep: String) = closeWhere(keep) { it != keep }

    /** Close to the Right: the tabs after [id]. */
    fun closeTabsToRight(id: String) {
        val after = tabIds.dropWhile { it != id }.drop(1).toSet()
        closeWhere(id) { it in after }
    }

    /** Closes the tabs [gone] picks at once, showing [fallback] if the one in front went. */
    private fun closeWhere(fallback: String, gone: (String) -> Boolean) {
        val closing = tabIds.filter(gone)
        if (closing.isEmpty()) return
        for (tab in tabs) if (gone(tab.id)) tab.stop()
        tabs.removeAll { gone(it.id) }
        if (gone(SETTINGS_TAB)) settingsOpen = false
        recentTabs.removeAll(closing)
        if (activeTabId?.let(gone) == true) activate(fallback) else saveTabs()
    }

    /**
     * Shows another session in this tab, as picking one from the chat's own History does; a
     * session already open elsewhere is brought forward instead.
     */
    fun replaceTab(id: String, session: SessionInfo) {
        if (tabs.any { it.id == session.id }) {
            activate(session.id)
            return
        }
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        tabs[index].stop()
        tabs[index] = ChatTab(session)
        recentTabs.remove(id)
        activate(session.id)
    }

    /** A new chat in a new tab; the server's default provider when [providerId] is null. */
    suspend fun newChat(providerId: String? = null) {
        val api = api ?: return
        val project = selectedProject ?: return
        try {
            val session = api.createSession(project, providerId)
            if (selectedProject != project) return
            sessions = (listOf(session) + sessions.filter { it.id != session.id }).sortedForList()
            openTab(session)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            actionError = describe(error)
        }
    }

    /** A project's tabs go when another opens; Settings, which belongs to no project, stays. */
    private fun closeTabs() {
        tabs.forEach { it.stop() }
        recentTabs.removeAll { it != SETTINGS_TAB }
        tabs.clear()
        if (activeTabId != SETTINGS_TAB) activeTabId = null
    }

    private val tabsKey: String get() = "tabs.$serverKey.${selectedProject.orEmpty()}"

    private fun saveTabs() {
        if (debug != null || selectedProject == null) return
        val saved = buildJsonObject {
            put(
                "tabs",
                JsonArray(
                    tabs.map { tab ->
                        buildJsonObject {
                            put("id", tab.id)
                            put("providerId", tab.session.providerId)
                            put("title", tab.session.title)
                        }
                    },
                ),
            )
            // Settings is not saved: the chat behind it is the one to come back to.
            put("active", (if (activeTabId == SETTINGS_TAB) recentTabs.firstOrNull { it != SETTINGS_TAB } else activeTabId).orEmpty())
        }
        prefs.put(tabsKey, saved.toString())
    }

    /** The tabs this project had open; only the one in front connects until another is chosen. */
    private fun restoreTabs() {
        if (debug != null) return
        val saved = prefs.get(tabsKey)?.let(WireJson::parseOrNull) ?: return
        val restored = saved["tabs"].array.orEmpty().mapNotNull { entry ->
            val id = entry["id"].string ?: return@mapNotNull null
            val session = sessions.firstOrNull { it.id == id } ?: SessionInfo(
                id = id,
                providerId = entry["providerId"].string ?: "claude",
                title = entry["title"].string ?: "",
            )
            ChatTab(session)
        }
        tabs.clear()
        tabs.addAll(restored)
        // Settings in front stays there; the restored tabs connect once one is picked.
        if (activeTabId == SETTINGS_TAB) return
        val active = saved["active"].string
        if (active != null && tabs.any { it.id == active }) {
            activate(active, picked = false)
        } else {
            tabs.firstOrNull()?.let { activate(it.id, picked = false) }
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

    companion object {
        /** The Settings tab's id among the chat tabs'. */
        const val SETTINGS_TAB = "__settings__"
    }
}

/**
 * One open chat tab. Its connection starts when the tab is first shown and stays up while the
 * tab is open, so a tab behind still shows its turn streaming.
 */
@Stable
class ChatTab(val session: SessionInfo) {
    val id: String = session.id
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

    fun start(api: ApiClient, project: String, scope: CoroutineScope) {
        if (controller != null) return
        historyList = HistoryList(api, project, scope)
        controller = ChatController(api, project, session, scope).also { it.start() }
    }

    fun stop() {
        controller?.stop()
        controller = null
    }
}
