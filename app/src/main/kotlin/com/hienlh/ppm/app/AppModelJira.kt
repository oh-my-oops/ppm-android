package com.hienlh.ppm.app

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hienlh.ppm.client.jiraCancelResult
import com.hienlh.ppm.client.jiraCreateWatcher
import com.hienlh.ppm.client.jiraDebugResult
import com.hienlh.ppm.client.jiraDeleteConfig
import com.hienlh.ppm.client.jiraDeleteResult
import com.hienlh.ppm.client.jiraDeleteWatcher
import com.hienlh.ppm.client.jiraEditWatcher
import com.hienlh.ppm.client.jiraGetUnreadCount
import com.hienlh.ppm.client.jiraListConfigProjects
import com.hienlh.ppm.client.jiraListConfigs
import com.hienlh.ppm.client.jiraListResults
import com.hienlh.ppm.client.jiraListWatchers
import com.hienlh.ppm.client.jiraMarkResultRead
import com.hienlh.ppm.client.jiraPullWatcher
import com.hienlh.ppm.client.jiraPutConfig
import com.hienlh.ppm.client.jiraResumeResult
import com.hienlh.ppm.client.jiraTestConfig
import com.hienlh.ppm.client.jiraTestJql
import com.hienlh.ppm.client.jiraUpdateWatcher
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.GlobalEvents
import com.hienlh.ppm.state.jira.JiraTestResult
import com.hienlh.ppm.state.jira.parseJiraPulled
import com.hienlh.ppm.state.jira.parseJiraTestResult
import com.hienlh.ppm.state.jira.parseJiraUnreadCount
import com.hienlh.ppm.state.settings.JiraConfigRow
import com.hienlh.ppm.state.settings.JiraProjectOption
import com.hienlh.ppm.state.settings.JiraResultRow
import com.hienlh.ppm.state.settings.JiraWatcherRow
import com.hienlh.ppm.state.settings.parseJiraConfigs
import com.hienlh.ppm.state.settings.parseJiraProjectOptions
import com.hienlh.ppm.state.settings.parseJiraResults
import com.hienlh.ppm.state.settings.parseJiraWatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.WeakHashMap

/**
 * The Jira panel's state on this model (`jira-store.ts`, `use-jira-live-refresh.ts`): the projects
 * with their database ids, every saved Jira site, the watchers of the config being shown, the
 * tracked tickets and the unread count, and the writes the panel makes — optimistic where the
 * web's are. Main thread only, as the model is.
 */
@Stable
class AppJira internal constructor(private val model: AppModel) {
    var projectsWithIds by mutableStateOf<List<JiraProjectOption>>(emptyList())
        private set
    var configs by mutableStateOf<List<JiraConfigRow>>(emptyList())
        private set
    var watchers by mutableStateOf<List<JiraWatcherRow>>(emptyList())
        private set
    var results by mutableStateOf<List<JiraResultRow>>(emptyList())
        private set
    var unreadCount by mutableIntStateOf(0)
        private set

    /** The config whose watchers a shown view keeps current (`useJiraLiveRefresh(configId)`). */
    var watchedConfigId: Int? = null

    private var unbind: (() -> Unit)? = null
    private var boundEvents: GlobalEvents? = null

    /** The open project's database row and its Jira config (`projectEntry`, `config`). */
    fun projectEntry(project: String? = model.selectedProject): JiraProjectOption? = projectsWithIds.firstOrNull { it.name == project }

    fun configFor(projectId: Int?): JiraConfigRow? = projectId?.let { id -> configs.firstOrNull { it.projectId == id } }

    private suspend fun <T> quietly(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun launch(block: suspend () -> Unit) {
        model.scope.launch { block() }
    }

    fun loadConfigs() = launch { model.api?.let { api -> quietly { parseJiraConfigs(api.jiraListConfigs()) }?.let { configs = it } } }

    fun loadProjects() = launch { model.api?.let { api -> quietly { parseJiraProjectOptions(api.jiraListConfigProjects()) }?.let { projectsWithIds = it } } }

    fun loadWatchers(configId: Int) = launch {
        model.api?.let { api -> quietly { parseJiraWatchers(api.jiraListWatchers(configId)) }?.let { watchers = it } }
    }

    suspend fun loadResults(watcherId: Int? = null, status: String? = null) {
        val api = model.api ?: return
        quietly { parseJiraResults(api.jiraListResults(watcherId, status)) }?.let { results = it }
    }

    fun refreshResults(watcherId: Int? = null, status: String? = null) = launch { loadResults(watcherId, status) }

    fun loadUnreadCount() = launch { model.api?.let { api -> quietly { parseJiraUnreadCount(api.jiraGetUnreadCount()) }?.let { unreadCount = it } } }

    // Config

    /** Saves the site; answers whether the saved token was cleared because the site or email changed. */
    suspend fun saveConfig(projectId: Int, baseUrl: String, email: String, token: String?): Boolean {
        val api = model.api ?: return false
        val saved = api.jiraPutConfig(projectId, baseUrl, email, token)
        configs = parseJiraConfigs(api.jiraListConfigs())
        return saved["tokenCleared"].bool == true
    }

    suspend fun deleteConfig(projectId: Int) {
        val api = model.api ?: return
        api.jiraDeleteConfig(projectId)
        configs = configs.filter { it.projectId != projectId }
        watchers = emptyList()
    }

    suspend fun testConnection(projectId: Int): Boolean {
        val api = model.api ?: return false
        return api.jiraTestConfig(projectId)["connected"].bool == true
    }

    // Watchers

    suspend fun createWatcher(configId: Int, name: String, jql: String, promptTemplate: String?, intervalMs: Int, mode: String) {
        val api = model.api ?: return
        api.jiraCreateWatcher(configId, name, jql, promptTemplate, intervalMs, mode)
        watchers = parseJiraWatchers(api.jiraListWatchers(configId))
    }

    /** An edit: an emptied template is sent as null, so the default prompt comes back. */
    suspend fun updateWatcher(w: JiraWatcherRow, name: String, jql: String, promptTemplate: String?, intervalMs: Int, mode: String) {
        val api = model.api ?: return
        api.jiraEditWatcher(w.id, name, jql, promptTemplate, intervalMs, mode)
        watchers = parseJiraWatchers(api.jiraListWatchers(w.configId))
    }

    fun deleteWatcher(w: JiraWatcherRow) = launch {
        val api = model.api ?: return@launch
        quietly { api.jiraDeleteWatcher(w.id) } ?: return@launch
        quietly { parseJiraWatchers(api.jiraListWatchers(w.configId)) }?.let { watchers = it }
    }

    /** Optimistic: the switch moves at once and back when the server refuses. */
    fun toggleWatcher(id: Int, enabled: Boolean) {
        val api = model.api ?: return
        watchers = watchers.map { if (it.id == id) it.copy(enabled = enabled) else it }
        launch {
            if (quietly { api.jiraUpdateWatcher(id, enabled = enabled) } == null) {
                watchers = watchers.map { if (it.id == id) it.copy(enabled = !enabled) else it }
            }
        }
    }

    /** Polls now; answers how many new tickets came in. */
    suspend fun pullWatcher(id: Int): Int {
        val api = model.api ?: return 0
        val pulled = parseJiraPulled(api.jiraPullWatcher(id))
        loadResults()
        return pulled
    }

    suspend fun testJql(configId: Int, jql: String): JiraTestResult {
        val api = model.api ?: return JiraTestResult(emptyList(), 0)
        return parseJiraTestResult(api.jiraTestJql(configId, jql))
    }

    // Tickets

    /** Optimistic: the card leaves at once whatever the server says. */
    fun softDeleteResult(id: Int) {
        val api = model.api ?: return
        results = results.filter { it.id != id }
        launch { quietly { api.jiraDeleteResult(id) } }
    }

    private fun queueOptimistically(id: Int, fallback: String, call: suspend () -> Unit) {
        val prev = results.firstOrNull { it.id == id }?.status
        results = results.map { if (it.id == id) it.copy(status = "queued") else it }
        launch {
            if (quietly { call() } == null) {
                results = results.map { if (it.id == id) it.copy(status = prev ?: fallback) else it }
            }
        }
    }

    fun startDebug(id: Int, prompt: String?) {
        val api = model.api ?: return
        queueOptimistically(id, "pending") { api.jiraDebugResult(id, prompt) }
    }

    fun resumeDebug(id: Int) {
        val api = model.api ?: return
        queueOptimistically(id, "failed") { api.jiraResumeResult(id) }
    }

    fun cancelDebug(id: Int) = launch {
        val api = model.api ?: return@launch
        quietly { api.jiraCancelResult(id) } ?: return@launch
        loadResults()
    }

    fun markRead(id: Int) {
        val api = model.api ?: return
        results = results.map { if (it.id == id) it.copy(readAt = Instant.now().toString()) else it }
        unreadCount = maxOf(0, unreadCount - 1)
        launch { quietly { api.jiraMarkResultRead(id) } }
    }

    /** Opens a ticket's debug chat as `[Jira] KEY` in the open project, marking a finished one read. */
    fun openSession(r: JiraResultRow) {
        val sessionId = r.sessionId ?: return
        if (r.status == "done" && r.readAt == null) markRead(r.id)
        openChat(r.issueKey, sessionId)
    }

    private fun openChat(issueKey: String, sessionId: String) {
        val project = model.selectedProject ?: return
        model.openLinkedSession(project, sessionId, "claude", "[Jira] $issueKey")
    }

    /**
     * Hears the Jira events on [events]: a config change reads the sites and projects again, a
     * watcher change the watchers, tickets and unread count, and a finished debug says so.
     */
    fun bindEvents(events: GlobalEvents?) {
        if (events === boundEvents && unbind != null) return
        unbind?.invoke()
        boundEvents = events
        if (events == null) {
            unbind = null
            return
        }
        unbind = events.onJiraEvent { type, frame ->
            when (type) {
                "jira:config_changed" -> {
                    loadConfigs()
                    loadProjects()
                }
                "jira:watchers_changed" -> {
                    watchedConfigId?.let(::loadWatchers)
                    refreshResults()
                    loadUnreadCount()
                }
                "jira:status_change" -> {
                    refreshResults()
                    val key = frame["issueKey"].string ?: return@onJiraEvent
                    when (frame["status"].string) {
                        "done" -> {
                            loadUnreadCount()
                            val sessionId = frame["sessionId"].string
                            model.toast(
                                ErrorToastMessage(
                                    "Debug complete: $key",
                                    kind = ErrorToastMessage.Kind.SUCCESS,
                                    action = sessionId?.let { id -> ErrorToastMessage.Action("View") { openChat(key, id) } },
                                ),
                            )
                        }
                        "failed" -> model.toast(ErrorToastMessage("Debug failed: $key"))
                    }
                }
            }
        }
    }

    /** What a shown Jira view reads when it appears. */
    fun loadAll() {
        loadConfigs()
        loadProjects()
        refreshResults()
        loadUnreadCount()
    }
}

private val holders = WeakHashMap<AppModel, AppJira>()

/** The Jira panel's state on this model. */
val AppModel.jira: AppJira
    get() = holders.getOrPut(this) { AppJira(this) }
