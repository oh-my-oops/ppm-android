package com.hienlh.ppm.app

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.assistantBasics
import com.hienlh.ppm.client.assistantProviders
import com.hienlh.ppm.protocol.ASSISTANT_PROJECT_NAME
import com.hienlh.ppm.protocol.ProviderInfo
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.isAssistantProject
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.GlobalEvents
import com.hienlh.ppm.state.assistant.ASSISTANT_PERMISSION_MODE
import com.hienlh.ppm.state.assistant.ASSISTANT_TAB_TITLE
import com.hienlh.ppm.state.assistant.AssistantReturn
import com.hienlh.ppm.state.assistant.AssistantStore
import com.hienlh.ppm.state.assistant.AssistantView
import com.hienlh.ppm.state.assistant.MANAGER_PROVIDER_ID
import com.hienlh.ppm.state.assistant.MANAGER_WINDOW_H
import com.hienlh.ppm.state.assistant.MANAGER_WINDOW_W
import com.hienlh.ppm.state.assistant.assistantSpawnRect
import com.hienlh.ppm.state.assistant.branchTakesMain
import com.hienlh.ppm.state.assistant.buildUiSummary
import com.hienlh.ppm.state.assistant.managerChatTitle
import com.hienlh.ppm.state.assistant.nextAssistantChatEpoch
import com.hienlh.ppm.state.windows.MAX_WINDOWS
import com.hienlh.ppm.state.windows.WindowVisualState
import com.hienlh.ppm.state.windows.cascadeSpawnRect
import com.hienlh.ppm.state.windows.frontWindowId
import com.hienlh.ppm.state.workspace.DOCK_PANEL_ID
import com.hienlh.ppm.state.workspace.NewTab
import com.hienlh.ppm.state.workspace.WorkspacePanel
import com.hienlh.ppm.state.workspace.WorkspaceStore
import com.hienlh.ppm.state.workspace.WorkspaceTab
import com.hienlh.ppm.state.workspace.isWindowPanelId
import com.hienlh.ppm.state.workspace.tabSessionId
import com.hienlh.ppm.state.workspace.windowIdFromPanelId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.WeakHashMap

/**
 * The PPM Assistant beside [AppModel] (`open-assistant.ts`, `use-assistant-session-choice.ts`,
 * `open-manager-chat.ts`): its store of cards, counts and manager chats, the one `assistant` tab
 * (a floating window on a tablet, a full-screen tab on a phone), which session that tab's chat
 * runs, and the manager chats it opens. Main thread only, as the model is.
 */
@Stable
class AppAssistant internal constructor(private val model: AppModel) {
    /** Cards, waiting counts, manager chats and the main session; null until a server is open. */
    var store by mutableStateOf<AssistantStore?>(null)
        private set

    /** Configured providers that can run the Assistant; null while loading. */
    var providers by mutableStateOf<List<ProviderInfo>?>(null)
        private set

    /** The providers could not be read. */
    var providersError by mutableStateOf(false)
        private set

    /** The Assistant's own default provider (`default_provider`), once read. */
    private var assistantDefault: String? = null

    /** The Assistant's sessions, read once a session is named without its provider. */
    private var sessions: List<SessionInfo>? = null
    private var sessionsSynced by mutableStateOf(false)
    private var sessionsLoading = false

    /** A branch taken from the main session, until the server's change of main arrives. */
    var promoting by mutableStateOf<String?>(null)
        private set

    /** The stream's project filter, kept on this device for the run (`assistant-filter-store`). */
    private var filter: String? = null

    private var unsubscribe: List<() -> Unit> = emptyList()

    val view: AssistantView? get() = store?.view?.value

    /** Starts the Assistant's store on a newly opened server. */
    fun connect(api: ApiClient, events: GlobalEvents) {
        unsubscribe.forEach { it() }
        val next = AssistantStore(api, model.scope, initialFilter = filter, onFilterChanged = { filter = it })
        store = next
        providers = null
        providersError = false
        sessions = null
        sessionsSynced = false
        promoting = null
        next.start()
        var first = true
        unsubscribe = listOf(
            events.onAssistantEvent { type, frame -> next.handleEvent(type, frame) },
            // Whatever changed while the socket was down was never delivered.
            events.onGlobalReady {
                if (first) first = false else next.refresh()
            },
        )
        model.scope.launch { loadProviders(api) }
    }

    private suspend fun loadProviders(api: ApiClient) {
        try {
            assistantDefault = try {
                api.assistantBasics().defaultProvider
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val list = api.assistantProviders()
            if (model.api === api) providers = list
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (model.api === api) {
                providers = providers ?: emptyList()
                providersError = true
            }
        }
    }

    /** A store and providers already read, in place of [connect] (tests). */
    @androidx.annotation.VisibleForTesting
    internal fun use(store: AssistantStore, providers: List<ProviderInfo>?) {
        this.store = store
        this.providers = providers
        providersError = false
    }

    /** Whether [sessionId] is a project's manager chat (the Tasks package and Chat History ask). */
    fun isManagerChat(sessionId: String?): Boolean = view?.isManagerChat(sessionId) == true

    /** An Assistant tab's chat starts once its tab names a session or a provider. */
    fun chatReady(tab: WorkspaceTab): Boolean =
        tab.type == "assistant" && (tab.metadata["sessionId"].string?.isNotEmpty() == true || tab.metadata["providerId"].string?.isNotEmpty() == true)

    /** The screen tools and the screen summary of the Assistant tab's chat. */
    fun wire(chat: ChatTab) {
        chat.uiSummary = { buildUiSummary(model.layout, model.windows?.state?.value, !model.wide) }
        val tools = AssistantScreenTools(model)
        chat.assistantUi = { request -> tools.answer(request, chat) }
    }

    // ------------------------------------------------------------------------------------------
    // The tab
    // ------------------------------------------------------------------------------------------

    /** The Assistant's tab and the panel holding it; one on screen before one in a hidden grid. */
    fun tab(): Pair<WorkspaceTab, String>? {
        val state = model.layout ?: return null
        val all = state.panels.values.flatMap { p -> p.tabs.filter { it.type == "assistant" }.map { it to p.id } }
        return all.firstOrNull { (_, pid) -> pid in state.gridPanelIds || isWindowPanelId(pid) } ?: all.firstOrNull()
    }

    /**
     * `openAssistant`: the Assistant on screen, on [sessionId] when one is named. A tablet moves a
     * newly opened tab into a window of its own, which closing closes the tab; a phone keeps it a tab.
     */
    fun open(sessionId: String? = null, providerId: String? = null) {
        model.onWorkspace { store -> openIn(store, sessionId, providerId) }
    }

    internal fun openIn(store: WorkspaceStore, sessionId: String? = null, providerId: String? = null): String {
        // A phone's grids are per project: a copy left in another project's hidden grid moves here.
        val carried = moveHiddenCopy(store)
        val state = store.state.value
        val wasOpen = state.panels.values.any { p -> p.tabs.any { it.type == "assistant" } }
        val keptSession = carried?.get("sessionId").string
        val metadata = buildJsonObject {
            put("projectName", ASSISTANT_PROJECT_NAME)
            (sessionId ?: keptSession)?.let { put("sessionId", it) }
            (providerId ?: carried?.get("providerId").string?.takeIf { sessionId == null })?.let { put("providerId", it) }
            if (carried != null) {
                put("permissionMode", ASSISTANT_PERMISSION_MODE)
                put("permissionModeSource", "user")
            }
        }
        val id = store.openTab(NewTab("assistant", ASSISTANT_TAB_TITLE, null, metadata, closable = true))
        if (id.isEmpty()) return id
        showInWindow(store, id, wasOpen)
        // An open Assistant was focused as it is; switching it to the requested session remounts its chat.
        val current = store.state.value.panelOf(id)?.tab(id)?.metadata
        if (wasOpen && sessionId != null && current?.get("sessionId").string != sessionId) {
            store.updateTab(id) {
                it.withMetadata(
                    "sessionId" to JsonPrimitive(sessionId),
                    "providerId" to providerId?.let(::JsonPrimitive),
                    "pendingMessage" to null,
                    "clearedFrom" to null,
                    "assistantChatEpoch" to JsonPrimitive(nextAssistantChatEpoch(current)),
                )
            }
        }
        return id
    }

    private fun moveHiddenCopy(store: WorkspaceStore): JsonObject? {
        val state = store.state.value
        var carried: JsonObject? = null
        for (panel in state.panels.values.toList()) {
            if (panel.id in state.gridPanelIds || isWindowPanelId(panel.id) || panel.id == DOCK_PANEL_ID) continue
            val stale = panel.tabs.firstOrNull { it.type == "assistant" } ?: continue
            carried = stale.metadata
            store.closeTab(stale.id, panel.id)
        }
        return carried
    }

    /** Already in a window: it comes forward. Just opened on a tablet: it moves into a window that is its home. */
    private fun showInWindow(store: WorkspaceStore, tabId: String, wasOpen: Boolean) {
        val panelId = store.state.value.panelOf(tabId)?.id ?: return
        val windowId = windowIdFromPanelId(panelId)
        if (windowId != null) {
            model.windows?.focus(windowId)
            return
        }
        if (wasOpen || !model.wide) return
        val windows = model.windows ?: return
        store.popOutTab(tabId, panelId, closeTabsOnClose = true, rect = assistantSpawnRect(windows.state.value.bounds))
    }

    /** The rail button (`toggleAssistant`): a window in front minimizes, anything else brings the Assistant forward. */
    fun toggle() {
        val windows = model.windows
        val windowId = tab()?.second?.let(::windowIdFromPanelId)
        val all = windows?.state?.value?.windows
        val win = windowId?.let { all?.get(it) }
        if (windows != null && win != null && win.state != WindowVisualState.Minimized && frontWindowId(all!!) == win.id) {
            windows.setState(win.id, WindowVisualState.Minimized)
            return
        }
        open()
    }

    /** Whether the Assistant tab sits in a floating window. */
    fun inWindow(tabId: String): Boolean = model.layout?.panelOf(tabId)?.id?.let(::isWindowPanelId) == true

    // ------------------------------------------------------------------------------------------
    // Which session the tab's chat runs (`useAssistantSessionChoice`)
    // ------------------------------------------------------------------------------------------

    /** A switch of the tab's chat: one metadata write, the chat made again on the session it names. */
    fun swap(tabId: String, vararg patch: Pair<String, JsonElement?>) {
        val current = model.tabMetadata(tabId)
        model.patchTabMetadata(
            tabId,
            "pendingMessage" to null,
            "clearedFrom" to null,
            "permissionMode" to JsonPrimitive(ASSISTANT_PERMISSION_MODE),
            "permissionModeSource" to JsonPrimitive("user"),
            *patch,
            "projectName" to JsonPrimitive(ASSISTANT_PROJECT_NAME),
            "assistantChatEpoch" to JsonPrimitive(nextAssistantChatEpoch(current)),
        )
    }

    /**
     * Settles which session [tab] shows, as the web's effects do: a fresh open shows the main
     * session; with none, a provider is picked for a new one; a session named without its
     * provider takes the one the list says. Called whenever the tab or the store changes.
     */
    fun settle(tab: WorkspaceTab, view: AssistantView) {
        val sessionId = tab.metadata["sessionId"].string?.takeIf { it.isNotEmpty() }
        val providerId = tab.metadata["providerId"].string?.takeIf { it.isNotEmpty() }
        val main = view.main
        if (sessionId == null && providerId == null && view.mainLoaded) {
            if (main != null) {
                swap(tab.id, "sessionId" to JsonPrimitive(main.sessionId), "providerId" to JsonPrimitive(main.providerId))
                return
            }
            val list = providers ?: return
            if (list.isEmpty()) return
            val preferred = listOf(assistantDefault, model.newChatCache.settings()?.defaultProvider)
            val pick = preferred.firstNotNullOfOrNull { id -> list.firstOrNull { it.id == id } } ?: list.first()
            model.patchTabMetadata(
                tab.id,
                "providerId" to JsonPrimitive(pick.id),
                "permissionMode" to JsonPrimitive(ASSISTANT_PERMISSION_MODE),
                "permissionModeSource" to JsonPrimitive("user"),
                "projectName" to JsonPrimitive(ASSISTANT_PROJECT_NAME),
            )
            return
        }
        if (sessionId != null && providerId == null) {
            val known = if (sessionId == main?.sessionId) main.providerId else sessions?.firstOrNull { it.id == sessionId }?.providerId
            if (known != null || sessionsSynced) {
                model.patchTabMetadata(
                    tab.id,
                    "providerId" to JsonPrimitive(known ?: "claude"),
                    "permissionMode" to JsonPrimitive(ASSISTANT_PERMISSION_MODE),
                    "permissionModeSource" to JsonPrimitive("user"),
                )
            } else {
                loadSessions()
            }
        }
        if (promoting != null && main?.sessionId == promoting) promoting = null
    }

    private fun loadSessions() {
        val api = model.api ?: return
        if (sessionsLoading) return
        sessionsLoading = true
        model.scope.launch {
            try {
                sessions = api.sessions(ASSISTANT_PROJECT_NAME).sessions
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Failed to be read: waiting longer will not make the session appear.
            } finally {
                sessionsLoading = false
                sessionsSynced = true
                tab()?.first?.let { t -> view?.let { settle(t, it) } }
            }
        }
    }

    /** The tab shows a session other than the main one (and not a branch on its way to being it). */
    fun oneOff(tab: WorkspaceTab, view: AssistantView): Boolean {
        val sessionId = tab.metadata["sessionId"].string?.takeIf { it.isNotEmpty() } ?: return false
        val main = view.main?.sessionId ?: return false
        return sessionId != main && sessionId != promoting
    }

    /** "Back to main". */
    fun backToMain(tabId: String) {
        val main = view?.main ?: return
        swap(tabId, "sessionId" to JsonPrimitive(main.sessionId), "providerId" to JsonPrimitive(main.providerId))
    }

    /**
     * Editing a message, forking or stepping through versions while on the main session moves the
     * main session along: the branch the user goes on with is the conversation, and Telegram follows.
     */
    fun followBranch(from: String?, to: String) {
        val store = store ?: return
        if (!branchTakesMain(from, to, view?.main?.sessionId)) return
        promoting = to
        model.scope.launch {
            val error = store.setMain(to)
            if (error != null) {
                if (promoting == to) promoting = null
                model.toast(ErrorToastMessage("This branch could not become the main Assistant session", error))
            }
        }
    }

    /** A fork of the Assistant's chat folds back into its tab (`handleFork`); false for any other chat. */
    internal fun takeFork(project: String, session: SessionInfo, pendingMessage: String): Boolean {
        if (!isAssistantProject(project)) return false
        val (tab, _) = tab() ?: return false
        val from = tabSessionId(tab)
        swap(
            tab.id,
            "sessionId" to JsonPrimitive(session.id),
            "providerId" to JsonPrimitive(session.providerId),
            "pendingMessage" to JsonPrimitive(pendingMessage),
        )
        followBranch(from, session.id)
        return true
    }

    /** `/clear` in the Assistant starts a new session in its own tab (`handleClear`); false for any other chat. */
    internal fun takeClear(chat: ChatTab): Boolean {
        if (!isAssistantProject(chat.project)) return false
        swap(chat.tabId, "sessionId" to null, "clearedFrom" to chat.session?.id?.let(::JsonPrimitive))
        return true
    }

    // ------------------------------------------------------------------------------------------
    // Manager chats (`open-manager-chat.ts`)
    // ------------------------------------------------------------------------------------------

    /**
     * Opens [projectName]'s manager chat [sessionId], or another chat of it ([chatTitle] and
     * [chatProvider]). A tablet shows it in a window of its own without switching project; a phone
     * switches project and opens it as a tab that remembers where the user came from.
     */
    fun openManagerChat(projectName: String, sessionId: String, chatTitle: String? = null, chatProvider: String? = null) {
        val title = chatTitle ?: managerChatTitle(projectName)
        val provider = chatProvider ?: MANAGER_PROVIDER_ID
        if (model.projects.none { it.name == projectName }) {
            model.toast(ErrorToastMessage("$projectName is not a project in this PPM any more."))
            return
        }
        val windows = model.windows
        if (model.wide && windows != null) openInWindow(projectName, sessionId, title, provider) else openOnPhone(projectName, sessionId, title, provider)
    }

    private fun openInWindow(projectName: String, sessionId: String, title: String, provider: String) {
        val windows = model.windows ?: return
        model.onWorkspace { store ->
            val state = store.state.value
            for (panel in state.panels.values) {
                if (!isWindowPanelId(panel.id) || panel.tabs.none { tabSessionId(it) == sessionId }) continue
                windowIdFromPanelId(panel.id)?.let(windows::focus)
                return@onWorkspace
            }
            if (windows.state.value.windows.size >= MAX_WINDOWS) {
                model.toast(ErrorToastMessage("Too many windows are open. Close one to open the manager chat."))
                return@onWorkspace
            }
            val before = state.panels.values.flatMap(WorkspacePanel::tabs).map { it.id }.toSet()
            val id = store.openTab(
                NewTab(
                    type = "chat",
                    title = title,
                    projectId = null,
                    metadata = buildJsonObject {
                        put("projectName", projectName)
                        put("sessionId", sessionId)
                        put("providerId", provider)
                        put("managerWindow", true)
                    },
                ),
            )
            // Already open in the grid: it was brought forward there, where the user put it.
            if (id.isEmpty() || id in before) return@onWorkspace
            val panelId = store.state.value.panelOf(id)?.id ?: return@onWorkspace
            val ws = windows.state.value
            val rect = cascadeSpawnRect(ws.windows.values.map { it.rect }, ws.bounds, MANAGER_WINDOW_W, MANAGER_WINDOW_H)
            if (store.popOutTab(id, panelId, closeTabsOnClose = true, rect = rect) == null) {
                model.toast(ErrorToastMessage("Too many windows are open. Close one to open the manager chat."))
            }
        }
    }

    private fun openOnPhone(projectName: String, sessionId: String, title: String, provider: String) {
        val from = model.selectedProject
        model.scope.launch {
            model.openProject(projectName) { store ->
                val id = store.openTab(
                    NewTab(
                        type = "chat",
                        title = title,
                        projectId = projectName,
                        metadata = buildJsonObject {
                            put("projectName", projectName)
                            put("sessionId", sessionId)
                            put("providerId", provider)
                        },
                    ),
                )
                if (id.isNotEmpty()) {
                    store.updateTab(id) {
                        it.withMetadata(
                            "returnTo" to buildJsonObject {
                                put("kind", "assistant")
                                put("project", if (from == null || from == projectName) kotlinx.serialization.json.JsonNull else JsonPrimitive(from))
                            },
                            "returnNote" to JsonPrimitive(true),
                        )
                    }
                }
            }
        }
    }

    /** "‹ Assistant": back to the project the chip was tapped in, with the Assistant on screen. */
    fun returnToAssistant(tabId: String, back: AssistantReturn) {
        model.patchTabMetadata(tabId, "returnTo" to null, "returnNote" to null)
        val project = back.project
        if (project != null && project != model.selectedProject) {
            model.scope.launch { model.openProject(project) { store -> openIn(store) } }
        } else {
            open()
        }
    }

    /** The one-time note folded away. */
    fun dropReturnNote(tabId: String) = model.patchTabMetadata(tabId, "returnNote" to null)

    /** "Open in project": the window goes, and the chat opens in its project's grid (`openManagerChatInProject`). */
    fun openManagerChatInProject(tab: WorkspaceTab) {
        val projectName = tab.metadata["projectName"].string ?: return
        val sessionId = tabSessionId(tab) ?: return
        val provider = tab.metadata["providerId"].string ?: MANAGER_PROVIDER_ID
        val panelId = model.layout?.panelOf(tab.id)?.id
        val windowId = panelId?.let(::windowIdFromPanelId)
        model.onWorkspace { store ->
            if (windowId != null) store.closeWindow(windowId)
        }
        if (model.projects.none { it.name == projectName }) {
            model.toast(ErrorToastMessage("$projectName is not a project in this PPM any more."))
            return
        }
        model.scope.launch {
            model.openProject(projectName) { store ->
                store.openTab(
                    NewTab(
                        type = "chat",
                        title = tab.title,
                        projectId = projectName,
                        metadata = buildJsonObject {
                            put("projectName", projectName)
                            put("sessionId", sessionId)
                            put("providerId", provider)
                        },
                    ),
                )
            }
        }
    }
}

private val holders = WeakHashMap<AppModel, AppAssistant>()

/** The PPM Assistant on this model. */
val AppModel.assistant: AppAssistant
    get() = holders.getOrPut(this) { AppAssistant(this) }

/** Whether [tab] is an Assistant tab whose chat can start (`syncChats`). */
internal fun AppModel.assistantChatReady(tab: WorkspaceTab): Boolean = assistant.chatReady(tab)

/** Opens the PPM Assistant (the palette's "PPM Assistant", the drawer tile, a deep link). */
fun AppModel.openAssistant(sessionId: String? = null, providerId: String? = null) = assistant.open(sessionId, providerId)
