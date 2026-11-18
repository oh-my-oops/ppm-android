package com.hienlh.ppm.app

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.client.schedulesDelete
import com.hienlh.ppm.client.schedulesDismiss
import com.hienlh.ppm.client.schedulesList
import com.hienlh.ppm.client.schedulesRunNow
import com.hienlh.ppm.client.schedulesUpdate
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.GlobalEvents
import com.hienlh.ppm.state.schedules.ScheduleChip
import com.hienlh.ppm.state.schedules.ScheduleOverview
import com.hienlh.ppm.state.schedules.SchedulesFeed
import com.hienlh.ppm.state.schedules.SchedulesFilter
import com.hienlh.ppm.state.schedules.chatOf
import com.hienlh.ppm.state.schedules.parseScheduleOverview
import com.hienlh.ppm.state.schedules.parseScheduleOverviews
import com.hienlh.ppm.state.schedules.projectLabel
import com.hienlh.ppm.state.schedules.scheduleProblem
import com.hienlh.ppm.state.windows.MAX_WINDOWS
import com.hienlh.ppm.state.windows.cascadeSpawnRect
import com.hienlh.ppm.state.workspace.isWindowPanelId
import com.hienlh.ppm.state.workspace.windowIdFromPanelId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.WeakHashMap

/**
 * The Schedules window on this model (`schedules-overview-store.ts`, `use-schedule-actions.ts`,
 * `open-schedules.ts`): one list of every schedule of every project, shared by the rail's button,
 * the drawer's tile and the window or tab; the writes they make; and opening the window. The feed
 * runs while anything showing schedules is on screen ([use]): it reads once, again on every
 * `schedules:changed`, every 30 s for runs that start and end on their own, and moves `now` so
 * "in 8h 10m" and the 24-hour count stay true. Main thread only, as the model is.
 */
@Stable
class AppSchedules internal constructor(private val model: AppModel) {
    private var built: Pair<ApiClient, SchedulesFeed>? = null

    /** The feed for the open server; a new one when the server changes. Null while not connected. */
    val feed: SchedulesFeed?
        get() {
            val api = model.api ?: return null
            built?.takeIf { it.first === api }?.let { return it.second }
            val next = SchedulesFeed(model.scope, { parseScheduleOverviews(api.schedulesList()) })
            built = api to next
            if (users > 0) next.refetch()
            return next
        }

    /** The window's filters, this device's (`ppm:schedules-filter` keeps the project). */
    var filter by mutableStateOf(SchedulesFilter())
        private set

    /** The schedule a caller asked to be selected ("Open in Schedules" on a task), until the list takes it. */
    var pendingSelect by mutableStateOf<Int?>(null)

    private var prefs: KeyValueStore? = null
    private var users = 0
    private var poll: Job? = null
    private var unbind: (() -> Unit)? = null
    private var boundEvents: GlobalEvents? = null

    fun attachPrefs(store: KeyValueStore) {
        if (prefs != null) return
        prefs = store
        val saved = store.get(FILTER_KEY)?.let(WireJson::parseOrNull)
        filter = SchedulesFilter(project = saved["project"].string)
    }

    fun updateFilter(change: (SchedulesFilter) -> SchedulesFilter) {
        val next = change(filter)
        if (next.project != filter.project) {
            prefs?.put(FILTER_KEY, buildJsonObject { next.project?.let { put("project", it) } }.toString())
        }
        filter = next
    }

    fun pickChip(chip: ScheduleChip) = updateFilter { it.copy(chip = chip) }

    /**
     * Keeps the shared list fresh while the caller is on screen (`useSchedulesFeed`); the answer
     * stops it. Several callers share one feed.
     */
    fun use(): () -> Unit {
        users++
        if (users == 1) start()
        var released = false
        return {
            if (!released) {
                released = true
                users--
                if (users == 0) stop()
            }
        }
    }

    /** Hears `schedules:changed` on [events]; idempotent per socket. */
    fun bindEvents(events: GlobalEvents?) {
        if (events === boundEvents && unbind != null) return
        unbind?.invoke()
        boundEvents = events
        if (events == null) {
            unbind = null
            return
        }
        val offChanged = events.onSchedulesChanged { if (users > 0) feed?.refetch() }
        val offReady = events.onGlobalReady { if (users > 0) feed?.refetch() }
        unbind = { offChanged(); offReady() }
    }

    private fun start() {
        feed?.refetch()
        poll?.cancel()
        poll = model.scope.launch {
            while (isActive) {
                delay(POLL_MS)
                feed?.let { it.tick(); it.refetch() }
            }
        }
    }

    private fun stop() {
        poll?.cancel()
        poll = null
    }

    // Actions (`use-schedule-actions.ts`)

    private fun success(text: String) = model.toast(ErrorToastMessage(text, kind = ErrorToastMessage.Kind.SUCCESS))

    private fun error(text: String) = model.toast(ErrorToastMessage(text))

    private fun message(e: Exception, fallback: String) = e.message?.takeIf { it.isNotBlank() } ?: fallback

    fun runNow(s: ScheduleOverview) {
        val api = model.api ?: return
        model.scope.launch {
            try {
                val res = api.schedulesRunNow(s.id)
                val runId = res["runId"].int
                when {
                    s.kind == "session" -> success("Queued: it is sent once its chat is idle")
                    runId != null -> success("Run #$runId started" + if (res["wasRunning"].bool == true) " (the previous run is still going)" else "")
                    else -> success(if (s.mode == "always-on") "Restarting" else "Tick started")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error(message(e, "Could not run it"))
            }
            feed?.refetch()
        }
    }

    fun setEnabled(s: ScheduleOverview, enabled: Boolean) {
        val api = model.api ?: return
        model.scope.launch {
            try {
                parseScheduleOverview(api.schedulesUpdate(s.id, buildJsonObject { put("enabled", enabled) }))?.let { feed?.put(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error(message(e, "Could not change it"))
            }
        }
    }

    fun dismiss(s: ScheduleOverview) {
        val api = model.api ?: return
        val problem = scheduleProblem(s, System.currentTimeMillis()) ?: return
        model.scope.launch {
            try {
                parseScheduleOverview(api.schedulesDismiss(s.id, problem.key))?.let { feed?.put(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error(message(e, "Could not dismiss it"))
                feed?.refetch()
            }
        }
    }

    /** Deletes once the user has confirmed (`Delete the schedule "…"? It will not run again.`). */
    fun delete(s: ScheduleOverview) {
        val api = model.api ?: return
        model.scope.launch {
            try {
                api.schedulesDelete(s.id)
                feed?.remove(s.id)
                success("Schedule deleted")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error(message(e, "Could not delete it"))
            }
        }
    }

    fun openTask(s: ScheduleOverview) {
        val task = s.task ?: return
        model.tasks.openTask(task.id, task.project, task.title, task.number)
    }

    /** The chat a reminder goes into, or the chat its last run opened. */
    fun openChat(s: ScheduleOverview) {
        chatOf(s)?.let { openChatId(s, it) }
    }

    /** One run's chat, of the schedule's project (`openSessionAnywhere`). */
    fun openChatId(s: ScheduleOverview, sessionId: String) {
        val project = projectLabel(s)
        if (model.projects.none { it.name == project }) {
            error("$project is not a project in this PPM any more.")
            return
        }
        model.openLinkedSession(project, sessionId, s.providerId, if (s.kind == "session") s.targetTitle ?: "" else "")
    }

    /**
     * Opens the Schedules window (`openSchedules`): a floating window on a tablet, the one already
     * open coming forward; the `schedules` tab on a phone. [select] asks the list to select one.
     */
    fun open(select: Int? = null) {
        pendingSelect = select
        val tabId = TAB_ID
        val layout = model.layout
        val shown = layout?.panels?.values?.firstOrNull { p -> p.tabs.any { it.id == tabId } }
        val windows = model.windows
        if (shown != null) {
            model.activate(tabId, shown.id)
            if (isWindowPanelId(shown.id)) windowIdFromPanelId(shown.id)?.let { windows?.focus(it) }
            return
        }
        model.openWorkspaceTab(type = "schedules", title = "Schedules", projectId = null)
        if (!model.wide || windows == null) return
        val now = model.layout ?: return
        val panel = now.panels.values.firstOrNull { p -> p.tabs.any { it.id == tabId } } ?: return
        val state = windows.state.value
        // At the cap it stays a tab, as a task does.
        if (isWindowPanelId(panel.id) || state.windows.size >= MAX_WINDOWS) return
        val rect = cascadeSpawnRect(state.windows.values.map { it.rect }, state.bounds, WINDOW_W, WINDOW_H)
        model.popOutTab(tabId, panel.id, rect = rect, closeTabsOnClose = true)
    }

    companion object {
        const val TAB_ID = "schedules"
        const val POLL_MS = 30_000L
        const val FILTER_KEY = "ppm:schedules-filter"

        /** Wide enough for the list and the detail side by side. */
        const val WINDOW_W = 1080.0
        const val WINDOW_H = 680.0
    }
}

private val holders = WeakHashMap<AppModel, AppSchedules>()

/** The Schedules window's state on this model. */
val AppModel.schedules: AppSchedules
    get() = holders.getOrPut(this) { AppSchedules(this) }
