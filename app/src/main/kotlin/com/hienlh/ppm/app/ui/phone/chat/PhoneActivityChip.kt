package com.hienlh.ppm.app.ui.phone.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ChatTab
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.ActivityChip
import com.hienlh.ppm.client.teamActivity
import com.hienlh.ppm.protocol.TeamMemberActivity
import com.hienlh.ppm.protocol.agentTranscriptProvider
import com.hienlh.ppm.state.AgentActivityWatch
import com.hienlh.ppm.state.AgentSessionIdentity
import com.hienlh.ppm.state.ChatState
import com.hienlh.ppm.state.SessionCollabState
import com.hienlh.ppm.state.payload
import com.hienlh.ppm.state.pollTeamActivity

/**
 * The context row's first chip on a phone (`activity-chip.tsx`): the agents this chat is running, the
 * background tasks its Claude CLI runs (each with a Stop), and
 * the sessions it is linked with, each a tab of the sheet the chip opens. The sessions tab is the
 * tablet's `LinkedSessionsPanel`, with Unlink, the Unlinked group and Stop all child sessions. Fed
 * as ChatPane feeds it: the agent activity subscription, the team's members, `/ws/global`'s links.
 * Nothing at all while the chat has neither. [visit] shows the tab a linked session opened in.
 */
@Composable
internal fun PhoneActivityChip(model: AppModel, tab: ChatTab, state: ChatState, visit: () -> Unit) {
    val sessionId = tab.session?.id ?: state.sessionId
    val identity = AgentSessionIdentity(tab.project, agentTranscriptProvider(tab.providerId), sessionId.orEmpty())
    val channel = model.events
    val agentActivity = remember(channel, identity) {
        if (channel != null && identity.sessionId.isNotEmpty() && identity.projectName.isNotEmpty()) {
            AgentActivityWatch(channel, identity.projectName, identity.providerId, identity.sessionId)
        } else {
            null
        }
    }
    DisposableEffect(agentActivity) {
        agentActivity?.start()
        onDispose { agentActivity?.stop() }
    }
    val running = agentActivity?.running?.collectAsState()?.value.orEmpty()
    val primaryTeam = state.team.primary
    var teamMembers by remember(primaryTeam) { mutableStateOf(emptyList<TeamMemberActivity>()) }
    val api = model.api
    LaunchedEffect(primaryTeam, api) {
        if (primaryTeam == null || api == null) return@LaunchedEffect
        pollTeamActivity({ api.teamActivity(primaryTeam) }) { teamMembers = it.members }
    }
    // Seeds `/ws/global`'s link map for this chat (`useLinkedSessions`).
    LaunchedEffect(api, tab.project, sessionId) {
        if (api != null && tab.project.isNotEmpty() && !sessionId.isNullOrEmpty()) model.seedSessionLinks(tab.project, sessionId)
    }
    val collab = channel?.collab?.collectAsState()?.value ?: SessionCollabState()
    val streaming = channel?.running?.collectAsState()?.value.orEmpty()
    val unreads = channel?.unread?.collectAsState()?.value.orEmpty()
    val unlinked = if (!sessionId.isNullOrEmpty()) model.unlinkedSessions[sessionId].orEmpty() else emptyMap()
    val items = remember(state.items, state.live, state.liveStartedAt) { state.itemsWithLive() }

    ActivityChip(
        sessionId = sessionId,
        running = running,
        teamMembers = teamMembers,
        items = items,
        sessions = model.sessions,
        collab = collab,
        streamingSessions = streaming,
        unreads = unreads,
        unlinkedForChat = unlinked,
        narrow = true,
        // A phone opens an agent's session in its sheet, as the transcript's Agent cards do.
        onOpenAgent = { model.openAgentSession(it.payload(identity, primaryTeam.orEmpty()), null) },
        onOpenLinkedSession = { view ->
            model.openLinkedSession(tab.project, view.sessionId, view.providerId, view.title)
            visit()
        },
        onSetSessionsUnlinked = { ids, unlink, atProvider ->
            sessionId?.let { model.setSessionsUnlinked(it, ids, unlink, atProvider) }
        },
        onStopChildSessions = {
            if (!sessionId.isNullOrEmpty() && tab.project.isNotEmpty()) model.stopChildSessions(tab.project, sessionId)
        },
        onError = { title, detail -> model.toast(ErrorToastMessage(title, detail)) },
        liveTasks = state.liveTasks,
        onStopTask = { id -> tab.controller?.stopBackgroundTask(id) },
    )
}
