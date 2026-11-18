package com.hienlh.ppm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.TruncatedText
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.protocol.AgentRunningEntry
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.SessionUnread
import com.hienlh.ppm.protocol.TeamMemberActivity
import com.hienlh.ppm.state.ChatItem
import com.hienlh.ppm.state.LinkedSessionStatus
import com.hienlh.ppm.state.LinkedSessionView
import com.hienlh.ppm.state.RunningAgentView
import com.hienlh.ppm.state.SessionCollabState
import com.hienlh.ppm.state.buildRunningRows
import com.hienlh.ppm.state.collectLinkedSessions
import com.hienlh.ppm.state.findCardLabel
import com.hienlh.ppm.state.finishedSiblingRows
import com.hienlh.ppm.state.lastMessageFrom
import com.hienlh.ppm.state.linkedSessionStatus
import com.hienlh.ppm.state.splitUnlinked
import com.hienlh.ppm.state.view
import java.time.Instant
import kotlinx.coroutines.delay

enum class ActivityTab {
    Agents,
    Sessions,
}

/**
 * First chip of the chat footer's context row (`ActivityChip` in `activity-chip.tsx`): merges
 * running agents and linked sessions into one chip with a two-tab popover/sheet.
 */
@Composable
fun ActivityChip(
    sessionId: String?,
    running: List<AgentRunningEntry>,
    teamMembers: List<TeamMemberActivity>,
    items: List<ChatItem>,
    sessions: List<SessionInfo>,
    collab: SessionCollabState,
    streamingSessions: Set<String>,
    unreads: Map<String, SessionUnread>,
    unlinkedForChat: Map<String, Long>,
    narrow: Boolean,
    onOpenAgent: (RunningAgentView) -> Unit,
    onOpenLinkedSession: (LinkedSessionView) -> Unit,
    onSetSessionsUnlinked: (ids: List<String>, unlink: Boolean, atProvider: (String) -> Long) -> Unit,
    onStopChildSessions: suspend () -> Unit,
    onError: (title: String, detail: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val runningRows = remember(running, teamMembers) { buildRunningRows(running, teamMembers) }
    val doneRows = remember(items, runningRows) { finishedSiblingRows(items, runningRows) }
    val labels = remember(items, runningRows, doneRows) {
        (runningRows + doneRows).mapNotNull { row -> row.cardId?.let { it to findCardLabel(items, it) } }.toMap()
    }

    var tick by remember { mutableIntStateOf(0) }
    val agentsActive = !sessionId.isNullOrEmpty() && runningRows.isNotEmpty()
    LaunchedEffect(agentsActive) {
        while (agentsActive) {
            delay(1_000)
            tick++
        }
    }
    val now = tick.let { Instant.now() }
    val agentViews = if (agentsActive) {
        (runningRows + doneRows).map { it.view(it.cardId?.let(labels::get), now) }
    } else {
        emptyList()
    }

    val linked = remember(sessionId, sessions, items, collab) {
        if (sessionId.isNullOrEmpty()) {
            emptyList()
        } else {
            collectLinkedSessions(
                sessionId = sessionId,
                list = sessions,
                items = items,
                liveLinks = collab.links[sessionId].orEmpty(),
                announced = collab.announced,
                interactedAt = collab.interactedAt[sessionId].orEmpty(),
            )
        }
    }
    val linkedViews = remember(linked, streamingSessions, unreads, collab.pendingApprovals) {
        linked.map { l ->
            LinkedSessionView(
                sessionId = l.sessionId,
                title = l.title,
                providerId = l.providerId,
                relations = l.relations,
                status = linkedSessionStatus(l.sessionId, streamingSessions, unreads, collab.pendingApprovals),
                lastInteractionAt = l.lastInteractionAt,
            )
        }
    }
    val lastFrom = remember(items) { lastMessageFrom(items) }
    val split = remember(linkedViews, unlinkedForChat, lastFrom) {
        splitUnlinked(linkedViews, unlinkedForChat, lastFrom)
    }

    var open by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(ActivityTab.Sessions) }

    val visibleSessions = split.visible
    val needsYou = visibleSessions.any { it.status == LinkedSessionStatus.NEEDS_APPROVAL }
    val sessionsRunning = visibleSessions.any { it.status == LinkedSessionStatus.RUNNING }
    val hasAgents = agentsActive
    val hasSessions = visibleSessions.isNotEmpty() || split.hidden.isNotEmpty()
    val visible = !sessionId.isNullOrEmpty() && (hasAgents || hasSessions)

    LaunchedEffect(visible) {
        if (!visible) open = false
    }
    if (!visible) return

    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val hideWords = narrow || mobile

    val unread = visibleSessions.any { it.status == LinkedSessionStatus.NEW_MESSAGE }
    val dot = when {
        needsYou -> LinkedSessionStatus.NEEDS_APPROVAL
        hasAgents || sessionsRunning -> LinkedSessionStatus.RUNNING
        unread -> LinkedSessionStatus.NEW_MESSAGE
        else -> LinkedSessionStatus.IDLE
    }
    val shownTab = if (hasAgents) selectedTab else ActivityTab.Sessions

    val title = listOfNotNull(
        if (hasAgents) "${runningRows.size} ${if (runningRows.size == 1) "agent" else "agents"} running" else null,
        if (hasSessions) "${visibleSessions.size} linked" else null,
        if (needsYou) "one needs you" else null,
    ).joinToString(" · ")

    val groups = LinkedSessionGroups(
        visible = visibleSessions,
        hidden = split.hidden,
        onUnlink = { ids -> onSetSessionsUnlinked(ids, true) { id -> lastFrom[id] ?: 0L } },
        onRelink = { ids -> onSetSessionsUnlinked(ids, false) { 0L } },
    )

    FooterChip(
        narrow = narrow,
        modifier = modifier,
        expanded = open,
        tone = if (needsYou) FooterChipTone.AlertBorder else FooterChipTone.Default,
        gap = if (hideWords) 5.dp else 7.dp,
        title = title,
        contentDescription = title,
        testTag = "activity-chip",
        onClick = {
            if (open) {
                open = false
            } else {
                selectedTab = when {
                    needsYou -> ActivityTab.Sessions
                    hasAgents -> ActivityTab.Agents
                    else -> ActivityTab.Sessions
                }
                open = true
            }
        },
    ) {
        LinkedStatusDot(dot)
        if (hasAgents) {
            Row(
                Modifier.testTag("activity-part:agents"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PpmIcon(ProductIcon.Bot, 14.dp, tokens.text3.color)
                BasicText(
                    "${runningRows.size}",
                    style = LocalFooterTextStyle.current.copy(
                        color = tokens.text.color,
                        fontWeight = FontWeight.Medium,
                        fontFeatureSettings = "tnum",
                    ),
                    maxLines = 1,
                    softWrap = false,
                )
                if (!hideWords) {
                    BasicText(
                        if (runningRows.size == 1) "agent" else "agents",
                        style = LocalFooterTextStyle.current,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        if (hasAgents && hasSessions) {
            Box(Modifier.width(1.dp).height(12.dp).background(tokens.border.color))
        }
        if (hasSessions) {
            Row(
                Modifier.testTag("activity-part:sessions"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PpmIcon(ProductIcon.Link, 14.dp, tokens.text3.color)
                BasicText(
                    "${visibleSessions.size}",
                    style = LocalFooterTextStyle.current.copy(
                        color = tokens.text.color,
                        fontWeight = FontWeight.Medium,
                        fontFeatureSettings = "tnum",
                    ),
                    maxLines = 1,
                    softWrap = false,
                )
                if (!hideWords) {
                    BasicText(
                        "linked",
                        style = LocalFooterTextStyle.current,
                        maxLines = 1,
                        softWrap = false,
                    )
                    if (needsYou) {
                        BasicText(
                            "· needs you",
                            style = LocalFooterTextStyle.current.copy(color = tokens.error.color),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }

    FooterPopover(
        open = open,
        onClose = { open = false },
        label = "Agents and linked sessions",
        align = FooterPopoverAlign.Left,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .cssBorder(tokens.borderSoft.color, sides = CssSides.Bottom)
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasAgents) {
                ActivityTabButton(
                    label = "Agents",
                    icon = ProductIcon.Bot,
                    count = runningRows.size,
                    active = shownTab == ActivityTab.Agents,
                    alert = false,
                    modifier = Modifier.weight(1f),
                    testTag = "activity-tab:agents",
                    onClick = { selectedTab = ActivityTab.Agents },
                )
            }
            ActivityTabButton(
                label = "Sessions",
                icon = ProductIcon.Link,
                count = visibleSessions.size,
                active = shownTab == ActivityTab.Sessions,
                alert = needsYou,
                modifier = Modifier.weight(1f),
                testTag = "activity-tab:sessions",
                onClick = { selectedTab = ActivityTab.Sessions },
            )
        }

        if (shownTab == ActivityTab.Agents) {
            ActivityAgentList(
                agents = agentViews,
                onOpen = { view ->
                    open = false
                    onOpenAgent(view)
                },
            )
        } else {
            LinkedSessionsPanel(
                groups = groups,
                onOpenSession = { linkedSession ->
                    open = false
                    onOpenLinkedSession(linkedSession)
                },
                onStopChildSessions = onStopChildSessions,
                onError = onError,
            )
        }
    }
}

@Composable
private fun ActivityTabButton(
    label: String,
    icon: ProductIcon,
    count: Int,
    active: Boolean,
    alert: Boolean,
    modifier: Modifier = Modifier,
    testTag: String,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val shape = RoundedCornerShape(7.dp)
    val height = if (mobile) 44.dp else 30.dp
    val fontSize = if (mobile) 14f else 12f
    val lineHeight = if (mobile) 20f else 16f

    Row(
        modifier
            .height(height)
            .clip(shape)
            .then(
                if (active) {
                    Modifier
                        .background(tokens.panel.color)
                        .cssBorder(tokens.borderSoft.color, shape)
                } else {
                    Modifier
                },
            )
            .ppmClickable(
                pressedColor = tokens.surfaceHover.color,
                hoverColor = if (active) androidx.compose.ui.graphics.Color.Transparent else tokens.surfaceHover.color,
                shape = shape,
                role = Role.Tab,
                onClick = onClick,
            )
            .semantics { selected = active }
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        PpmIcon(icon, 14.dp, tokens.text3.color)
        BasicText(
            label,
            style = ppmText(fontSize, FontWeight.Medium, lineHeight = lineHeight).copy(
                color = if (active) tokens.text.color else tokens.text2.color,
            ),
            maxLines = 1,
            softWrap = false,
        )
        BasicText(
            "$count",
            style = ppmText(11f, lineHeight = 15f).copy(
                color = tokens.text3.color,
                fontFeatureSettings = "tnum",
            ),
            maxLines = 1,
            softWrap = false,
        )
        if (alert) {
            Box(Modifier.size(6.dp).background(tokens.error.color, CircleShape))
        }
    }
}

@Composable
private fun ActivityAgentList(
    agents: List<RunningAgentView>,
    onOpen: (RunningAgentView) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (mobile) Modifier else Modifier.heightIn(max = 220.dp))
            .verticalScroll(scroll)
            .padding(4.dp),
    ) {
        for (view in agents) {
            val rowShape = RoundedCornerShape(6.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 44.dp)
                    .clip(rowShape)
                    .webTitle("Open ${view.name}'s session")
                    .ppmClickable(
                        pressedColor = tokens.surfaceHover.color,
                        hoverColor = tokens.surfaceHover.color,
                        shape = rowShape,
                        onClick = { onOpen(view) },
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("agent-row:${view.row.key}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LinkedStatusDot(if (view.row.done) LinkedSessionStatus.IDLE else LinkedSessionStatus.RUNNING)
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    TruncatedText(
                        AnnotatedString(view.name),
                        ppmText(13f, FontWeight.Medium, lineHeight = 19.5f).copy(
                            color = if (view.row.done) tokens.text2.color else tokens.text.color,
                        ),
                    )
                    val subtitle = when {
                        view.row.failed -> "Failed"
                        view.row.done -> "Finished"
                        else -> view.step ?: "Starting…"
                    }
                    TruncatedText(
                        AnnotatedString(subtitle),
                        ppmText(11f, kind = FontKind.MONO, lineHeight = 16.5f).copy(
                            color = if (view.row.failed) tokens.error.color else tokens.text3.color,
                        ),
                    )
                }
                view.elapsed?.let { elapsed ->
                    BasicText(
                        elapsed,
                        style = ppmText(11f, kind = FontKind.MONO, lineHeight = 16.5f).copy(color = tokens.text3.color),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                PpmIcon(ProductIcon.ChevronRight, 12.dp, tokens.text3.color)
            }
        }
    }
}
