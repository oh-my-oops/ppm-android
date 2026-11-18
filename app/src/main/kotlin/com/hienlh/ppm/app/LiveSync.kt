package com.hienlh.ppm.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import com.hienlh.ppm.client.DbConnectionsEvents
import com.hienlh.ppm.client.PpmHttp
import com.hienlh.ppm.client.extensionContributions
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.GlobalEvents
import com.hienlh.ppm.state.RenamedChat
import com.hienlh.ppm.state.workspace.WorkspaceState
import com.hienlh.ppm.state.workspace.tabSessionId
import kotlinx.coroutines.launch

/** The reserved project the PPM Assistant's sessions live in (`ASSISTANT_PROJECT`). */
private const val ASSISTANT_PROJECT = "__assistant__"

/**
 * What changed on another device, the `ppm` CLI or the Assistant and is shown here, as revisions a
 * screen re-reads on (`useSettingsChanged`, `EXTENSIONS_CHANGED_EVENT`).
 */
class LiveSyncState {
    private val sections = mutableStateMapOf<String, Int>()

    /** Moved on by every `extensions:changed`: Settings → Extensions reads its list again. */
    var extensionsRevision by mutableIntStateOf(0)
        private set

    fun bump(section: String) {
        sections[section] = (sections[section] ?: 0) + 1
    }

    fun bumpExtensions() {
        extensionsRevision++
    }

    /** The sum of [names]' revisions: changes whenever any of them changes. */
    fun revision(names: Collection<String>): Int = names.sumOf { sections[it] ?: 0 }
}

/**
 * The open chat tabs of [sessionId] a rename retitles (`retitleOpenChatTabs`): none for the
 * Assistant's project, and never a Design chat's, whose tab is named by its design.
 */
internal fun chatTabsToRetitle(layout: WorkspaceState?, projectName: String, sessionId: String, title: String): List<String> {
    if (projectName == ASSISTANT_PROJECT || layout == null) return emptyList()
    return layout.panels.values.flatMap { it.tabs }
        .filter { it.type == "chat" && tabSessionId(it) == sessionId && it.title != title }
        .filter { it.metadata["designSlug"].string.isNullOrEmpty() }
        .map { it.id }
}

/**
 * The `/ws/global` broadcasts that mean "read it again" (`use-global-events.ts`): the session
 * list (and a rename's tabs), the project list, settings, saved database connections, extension
 * contributions; the Designs sidebar listens for `design:list_changed` itself.
 */
internal fun AppModel.wireLiveSync(events: GlobalEvents) {
    events.onSessionsListChanged { project, renamed -> sessionsListChanged(project, renamed) }
    events.onProjectsChanged { refreshProjectRegistrations() }
    events.onSettingsChanged { section, clientId ->
        // This device's own save, echoed back: nothing to read again.
        if (clientId != null && clientId == PpmHttp.SETTINGS_CLIENT_ID) return@onSettingsChanged
        settingsChanged(section)
    }
    events.onDbConnectionsChanged { DbConnectionsEvents.notifyConnectionsChanged() }
    events.onExtensionsChanged { extensionsChanged() }
}

private fun AppModel.sessionsListChanged(project: String, renamed: RenamedChat?) {
    if (renamed != null) {
        for (tabId in chatTabsToRetitle(layout, project, renamed.sessionId, renamed.title)) retitleTab(tabId, renamed.title)
        if (project == selectedProject) renameSession(renamed.sessionId, renamed.title)
    }
    // Only the list this device holds: another project's is read when it is opened.
    if (project == selectedProject) scope.launch { refreshSessions(quiet = true) }
}

/** `refreshAppWideSettings`, then every open pane showing [section] reads it again. */
private fun AppModel.settingsChanged(section: String) {
    when (section) {
        "theme", "themes" -> scope.launch { loadTheme() }
        "keybindings" -> reloadKeybindings()
        // A chat opened next must not start from the AI settings cached before the change.
        "ai" -> newChats.preparationCache.clear()
    }
    liveSync.bump(section)
}

/** `handleExtensionsChanged`: the contributions read again, then Settings → Extensions. */
private fun AppModel.extensionsChanged() {
    val client = api ?: return
    scope.launch {
        runCatching { client.extensionContributions() }.getOrNull()?.let { extensions?.takeContributions(it) }
        liveSync.bumpExtensions()
    }
}

/**
 * Real input at this device — a tap, a key, a scroll, typing in the composer — while the app is
 * on screen: `presence:input`, at most once a minute (`usePresenceHeartbeat`). A soft keyboard's
 * typing reaches no key event, so the composer reports its edits through here too.
 */
internal fun AppModel.notePresenceInput() {
    if (windowVisible) events?.presence?.input()
}
