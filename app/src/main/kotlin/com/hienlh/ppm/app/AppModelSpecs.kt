package com.hienlh.ppm.app

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.SpecView
import com.hienlh.ppm.client.attachSpecSession
import com.hienlh.ppm.client.createSpec
import com.hienlh.ppm.client.spec
import com.hienlh.ppm.client.specBySession
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.SpecStartChoice
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.WeakHashMap

/**
 * Spec mode on the phone: which spec the Specs area shows, the workflow chooser a chat asked for,
 * and the chats whose first message will start a spec. The web keeps these in its chat tab
 * (`chat-tab.tsx`: `specStart`, `specPaneOpen`) and its sidebar; here they sit beside [AppModel],
 * main thread only, as the model is.
 */
@Stable
class AppSpecs internal constructor(private val model: AppModel) {
    /** The spec the Specs area shows; null shows the list. */
    var openSlug by mutableStateOf<String?>(null)
        internal set

    /** The open spec's title, for the app bar, once it is read. */
    var openTitle by mutableStateOf<String?>(null)

    /** The spec was opened from the Specs list, so Back returns to the list rather than leaving the area. */
    var openedFromList by mutableStateOf(false)
        internal set

    /** Something asked the phone shell to show the Specs area; the shell goes there and clears it. */
    var showRequested by mutableStateOf(false)

    /** The chat tab whose workflow chooser is up (Spec, Quick Spec, Bug Fix, and the task checks). */
    var choosingFor by mutableStateOf<String?>(null)

    /** History's list or tree (`chatHistoryViewMode`); null until this device or the server says. */
    var historyViewMode by mutableStateOf<String?>(null)

    /** Chats waiting for their first message's session, to bind the spec they were started as. */
    private val binders = mutableMapOf<String, Job>()

    /**
     * The tablet's chat tabs bound to a spec (`useSessionSpec`), by tab id: what the composer's
     * chip and Modes menu name, and where a request to show a spec finds its pane.
     */
    val bound = mutableStateMapOf<String, ChatSpecBinding>()

    /** Tablet chat tabs whose spec pane was hidden; a pane is open unless listed (`specPaneOpen`, open on desktop). */
    private val paneHidden = mutableStateMapOf<String, Boolean>()

    /** Specs whose pane must open in the chat bound to them (`takeSpecPaneRequest`), by slug. */
    private val paneRequests = mutableStateMapOf<String, Boolean>()

    /** Moves each time this device binds a chat to a spec, so a chat looks for its spec again at once. */
    var bindings by mutableIntStateOf(0)
        private set

    fun paneOpen(tabId: String): Boolean = paneHidden[tabId] != true

    fun setPaneOpen(tabId: String, open: Boolean) {
        if (open) paneHidden.remove(tabId) else paneHidden[tabId] = true
    }

    fun togglePane(tabId: String) = setPaneOpen(tabId, !paneOpen(tabId))

    /** The open chat tab bound to [slug], if any. */
    fun tabShowing(slug: String): String? =
        bound.entries.firstOrNull { it.value.slug == slug && model.tabOf(it.key) != null }?.key

    /** Asks the chat bound to [slug] to show its pane, when it next learns or already knows its spec. */
    fun requestPane(slug: String) {
        paneRequests[slug] = true
    }

    /** Whether a pane request for [slug] waits (read in composition, so a chat hears a new one). */
    fun paneRequested(slug: String): Boolean = paneRequests[slug] == true

    /** True once per request: the chat bound to [slug] should open its pane. */
    fun takePaneRequest(slug: String): Boolean = paneRequests.remove(slug) != null

    internal fun requestShow() {
        showRequested = true
    }

    /** Leaves the open spec for the list. */
    fun closeSpec() {
        openSlug = null
        openTitle = null
        openedFromList = false
    }

    /**
     * The spec bound to [sessionId], or a new one bound to it when there is none (`startSpecHere`):
     * its AI is told with its next message. Null when [choice] is a plain chat.
     */
    internal suspend fun bindSession(api: ApiClient, project: String, sessionId: String, title: String, choice: SpecStartChoice): String? {
        val (kind, workflow) = choice.specKind() ?: return null
        // A chat already bound (its first send carried the spec, or another device started one) keeps its spec.
        api.specBySession(project, sessionId)?.let { return it.slug }
        val view: SpecView = api.createSpec(project, SpecStartChoice.titleFrom(title).ifEmpty { "Spec" }, kind, workflow, choice.checks, sessionId)
        bindings++
        return view.slug
    }

    /** Waits for the chat in [tabId] to get its session, then binds the spec it was started as. */
    internal fun armBinder(tabId: String) {
        if (binders[tabId]?.isActive == true) return
        binders[tabId] = model.scope.launch {
            val tab = snapshotFlow { model.layout?.panelOf(tabId)?.tab(tabId) }
                .first { it == null || sessionOf(it) != null } ?: return@launch
            val choice = SpecStartChoice.from(tab.metadata["specStart"])
            val sessionId = sessionOf(tab) ?: return@launch
            if (!choice.isSpec) return@launch
            val api = model.api ?: return@launch
            val project = projectOf(tab) ?: return@launch
            try {
                bindSession(api, project, sessionId, model.sessions.firstOrNull { it.id == sessionId }?.title ?: tab.title, choice)
                    ?.let { slug -> bound[tabId] = ChatSpecBinding(slug) }
                model.patchTabMetadata(tabId, "specStart" to null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                model.toast(ErrorToastMessage("Could not start the spec", error.message))
            }
        }
    }

    /** Arms a binder for every chat tab that waits for its first message with a spec workflow chosen. */
    internal fun armPending(tabs: List<WorkspaceTab>) {
        for (tab in tabs) {
            if (tab.type == "chat" && sessionOf(tab) == null && SpecStartChoice.from(tab.metadata["specStart"]).isSpec) armBinder(tab.id)
        }
    }

    internal fun sessionOf(tab: WorkspaceTab): String? =
        model.chat(tab.id)?.session?.id ?: tab.metadata["sessionId"].string?.takeIf { it.isNotEmpty() }

    internal fun projectOf(tab: WorkspaceTab): String? =
        tab.metadata["projectName"].string?.takeIf { it.isNotEmpty() } ?: tab.projectId ?: model.selectedProject
}

/** A chat's spec as its tab knows it: the slug, and once read its title and task checks. */
data class ChatSpecBinding(val slug: String, val title: String? = null, val checks: String? = null)

private val holders = WeakHashMap<AppModel, AppSpecs>()

/** Spec mode's state on this model. */
val AppModel.specs: AppSpecs
    get() = holders.getOrPut(this) { AppSpecs(this) }

/** Shows the Specs list (the phone drawer's Specs). */
fun AppModel.openSpecs() {
    specs.closeSpec()
    specs.requestShow()
}

/**
 * Shows the spec [slug]. [fromList]: tapped in the Specs list, so Back returns there; otherwise
 * (a chat asked) Back returns to where the user came from.
 */
fun AppModel.openSpec(slug: String, fromList: Boolean = false) {
    specs.openSlug = slug
    specs.openTitle = null
    specs.openedFromList = fromList
    // A tablet shows a spec in the pane of the chat bound to it.
    specs.requestPane(slug)
    if (!fromList) specs.requestShow()
}

/**
 * Spec mode for the chat in [chatTabId], as the web's Modes → Workflow and its ✦ Spec chip do: a
 * chat bound to a spec shows it; any other chat gets the workflow chooser (Spec, Quick Spec, Bug
 * Fix and the task checks), which then calls [startSpec] with the choice.
 */
fun AppModel.startSpec(chatTabId: String) {
    val tab = tabOf(chatTabId) ?: return
    val sessionId = specs.sessionOf(tab)
    val project = specs.projectOf(tab)
    val api = api
    if (sessionId == null || project == null || api == null) {
        specs.choosingFor = chatTabId
        return
    }
    scope.launch {
        val bound = try {
            api.specBySession(project, sessionId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        if (bound != null) openSpec(bound.slug) else specs.choosingFor = chatTabId
    }
}

/**
 * Starts [choice] for the chat in [chatTabId]. A chat with a session gets its spec now, bound to
 * it, and the spec is shown (`startSpecHere`). A new chat keeps the choice in its tab (`specStart`,
 * which the web reads too) and its spec is made, bound to it, as soon as its first message makes
 * the session; choosing Chat takes the choice back.
 */
fun AppModel.startSpec(chatTabId: String, choice: SpecStartChoice) {
    specs.choosingFor = null
    val tab = tabOf(chatTabId) ?: return
    val sessionId = specs.sessionOf(tab)
    if (sessionId == null) {
        patchTabMetadata(chatTabId, "specStart" to if (choice.isSpec) choice.toJson() else null)
        if (choice.isSpec) specs.armBinder(chatTabId)
        return
    }
    if (!choice.isSpec) return
    val project = specs.projectOf(tab) ?: return
    bindAndShow(chatTabId, project, sessionId, sessions.firstOrNull { it.id == sessionId }?.title ?: tab.title, choice)
}

/**
 * `startSpecHere` (the tablet's Modes menu): the chat [tab], which has a session, gets a spec of
 * [choice] bound to it now, shown beside it. The chat's own session and project are used, as the
 * web's chat tab uses its own; a chat with no session keeps the choice for its first message.
 */
fun AppModel.startSpecHere(tab: ChatTab, choice: SpecStartChoice) {
    val session = tab.session ?: return startSpec(tab.tabId, choice)
    specs.choosingFor = null
    if (!choice.isSpec || tab.project.isEmpty()) return
    bindAndShow(tab.tabId, tab.project, session.id, sessions.firstOrNull { it.id == session.id }?.title ?: session.title, choice)
}

private fun AppModel.bindAndShow(chatTabId: String, project: String, sessionId: String, title: String, choice: SpecStartChoice) {
    val api = api ?: return
    scope.launch {
        try {
            specs.bindSession(api, project, sessionId, title, choice)?.let {
                specs.bound[chatTabId] = ChatSpecBinding(it)
                specs.setPaneOpen(chatTabId, true)
                openSpec(it)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            toast(ErrorToastMessage("Could not start the spec", error.message))
        }
    }
}

/** The workflow a chat with no session yet will start in, when it is a spec one. */
fun AppModel.pendingSpecStart(chatTabId: String): SpecStartChoice? {
    val tab = tabOf(chatTabId) ?: return null
    if (specs.sessionOf(tab) != null) return null
    return SpecStartChoice.from(tab.metadata["specStart"]).takeIf { it.isSpec }
}

/**
 * The Specs list's New spec (`newSpec` in specs-sidebar-panel.tsx): a chat that starts as a Spec,
 * whose first message describes what to build.
 */
fun AppModel.newSpec() {
    newChat(title = "New spec")
    val tabId = activeTabId ?: return
    startSpec(tabId, SpecStartChoice(workflow = "spec"))
}

/**
 * Opens the chat [sessionId] of a spec (its own chat, or a task's). A spec knows its chats by id
 * only, so the provider is asked for when not given; without it the tab would take the chat for
 * Claude's.
 */
fun AppModel.openSpecChat(project: String, sessionId: String, title: String, providerId: String? = null, then: () -> Unit = {}) {
    val api = api ?: return
    scope.launch {
        val provider = providerId ?: try {
            api.specBySession(project, sessionId)?.providerId
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        openLinkedSession(project, sessionId, provider ?: "", title)
        then()
    }
}

/**
 * The tablet's Specs list tap (`SpecsSidebarPanel.open`): the spec's chat with its pane open, or,
 * for a spec with no chat yet (one `ppm spec new` made), a new chat bound to it.
 */
fun AppModel.openSpecInChat(project: String, slug: String, title: String, sessionId: String?, then: () -> Unit = {}) {
    specs.requestPane(slug)
    if (sessionId != null) {
        openSpecChat(project, sessionId, title, then = then)
        return
    }
    val api = api ?: return
    scope.launch {
        try {
            val session = api.createSession(project, providerId = null, title = title)
            api.attachSpecSession(project, slug, session.id)
            openLinkedSession(project, session.id, session.providerId, title)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            toast(ErrorToastMessage("Could not open the spec", error.message))
        } finally {
            then()
        }
    }
}

/**
 * What showing the spec [slug] means on a tablet, where a spec sits beside its chat: the chat
 * bound to it comes forward with its pane open, or its chat is opened, or a new one made for it.
 */
fun AppModel.showSpecOnTablet(slug: String) {
    specs.tabShowing(slug)?.let { tabId ->
        specs.setPaneOpen(tabId, true)
        specs.takePaneRequest(slug)
        activate(tabId)
        return
    }
    val api = api ?: return
    val project = selectedProject ?: return
    scope.launch {
        val view = try {
            api.spec(project, slug)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            toast(ErrorToastMessage("Could not open the spec", error.message))
            return@launch
        }
        openSpecInChat(project, slug, view.title, view.sessionId)
    }
}
