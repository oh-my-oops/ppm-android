package com.hienlh.ppm.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.TruncatedText
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.protocol.SessionLinkRelation
import com.hienlh.ppm.state.LinkedSessionStatus
import com.hienlh.ppm.state.LinkedSessionView
import com.hienlh.ppm.state.UNDO_UNLINK_MS
import com.hienlh.ppm.state.canUnlink
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val LEAVE_MS = 180L

private val GROUP_ORDER = listOf(
    SessionLinkRelation.PARENT,
    SessionLinkRelation.CHILD,
    SessionLinkRelation.MESSAGED,
)

data class LinkedSessionGroups(
    val visible: List<LinkedSessionView>,
    val hidden: List<LinkedSessionView>,
    val onUnlink: (List<String>) -> Unit,
    val onRelink: (List<String>) -> Unit,
)

/** True when [session] was created by this session and is currently running or waiting on user approval. */
fun isRunningChild(session: LinkedSessionView): Boolean =
    SessionLinkRelation.CHILD in session.relations &&
        session.status != LinkedSessionStatus.IDLE &&
        session.status != LinkedSessionStatus.NEW_MESSAGE

private fun relationHeading(relation: SessionLinkRelation): String = when (relation) {
    SessionLinkRelation.PARENT -> "Created this session"
    SessionLinkRelation.CHILD -> "Created by this session"
    SessionLinkRelation.MESSAGED -> "Messaged"
}

private fun linkedStatusText(status: LinkedSessionStatus): String = when (status) {
    LinkedSessionStatus.RUNNING -> "Running"
    LinkedSessionStatus.IDLE -> "Idle"
    LinkedSessionStatus.NEEDS_APPROVAL -> "Needs you"
    LinkedSessionStatus.NEW_MESSAGE -> "New message"
}

private data class UndoState(
    val token: Int,
    val ids: List<String>,
    val text: String,
)

/**
 * The Sessions tab of the footer's activity chip (`LinkedSessionsPanel` in `linked-sessions-panel.tsx`):
 * grouped by relation, with per-row unlink, "Unlink idle & done", 6-second Undo, folded Unlinked
 * relink section, and inline "Stop all child sessions" confirmation.
 */
@Composable
fun LinkedSessionsPanel(
    groups: LinkedSessionGroups,
    onOpenSession: (LinkedSessionView) -> Unit,
    onStopChildSessions: suspend () -> Unit,
    onError: (title: String, detail: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val reducedMotion = rememberPrefersReducedMotion()
    val scope = rememberCoroutineScope()
    val currentUnlink by rememberUpdatedState(groups.onUnlink)

    var confirming by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf<Set<String>>(emptySet()) }
    var unlinkedOpen by remember { mutableStateOf(false) }
    var undo by remember { mutableStateOf<UndoState?>(null) }
    var nextUndoToken by remember { mutableIntStateOf(1) }
    val pendingIds = remember { LinkedHashSet<String>() }

    DisposableEffect(Unit) {
        onDispose {
            if (pendingIds.isNotEmpty()) {
                val flush = pendingIds.toList()
                pendingIds.clear()
                currentUnlink(flush)
            }
        }
    }

    LaunchedEffect(undo?.token) {
        val current = undo ?: return@LaunchedEffect
        delay(UNDO_UNLINK_MS)
        if (undo?.token == current.token) undo = null
    }

    val visible = groups.visible
    val hidden = groups.hidden
    val runningChildren = remember(visible) { visible.filter(::isRunningChild) }
    val finished = remember(visible) { visible.filter { canUnlink(it.status) } }

    // If all running children finished externally while confirming, close the confirm box.
    LaunchedEffect(runningChildren.isEmpty()) {
        if (runningChildren.isEmpty()) confirming = false
    }

    fun doUnlink(items: List<LinkedSessionView>) {
        val eligible = items.filter { canUnlink(it.status) }
        val ids = eligible.map { it.sessionId }
        if (ids.isEmpty()) return
        val label = if (eligible.size == 1) {
            "Unlinked “${eligible.first().title}”"
        } else {
            "Unlinked ${eligible.size} sessions"
        }
        val token = nextUndoToken++
        if (reducedMotion) {
            groups.onUnlink(ids)
            undo = UndoState(token, ids, label)
            return
        }
        leaving = leaving + ids
        pendingIds.addAll(ids)
        scope.launch {
            delay(LEAVE_MS)
            val stillPending = ids.filter { pendingIds.remove(it) }
            leaving = leaving - ids.toSet()
            if (stillPending.isNotEmpty()) {
                currentUnlink(stillPending)
                undo = UndoState(token, stillPending, label)
            }
        }
    }

    fun doRelink(ids: List<String>) {
        pendingIds.removeAll(ids.toSet())
        leaving = leaving - ids.toSet()
        groups.onRelink(ids)
        undo = null
    }

    val grouped = remember(visible) {
        GROUP_ORDER.mapNotNull { relation ->
            val list = visible.filter { (it.relations.firstOrNull() ?: SessionLinkRelation.MESSAGED) == relation }
            if (list.isEmpty()) null else relation to list
        }
    }

    val listScroll = rememberScrollState()
    Column(modifier.fillMaxWidth().testTag("linked-sessions-panel")) {
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (mobile) Modifier else Modifier.heightIn(max = 256.dp))
                .verticalScroll(listScroll)
                .padding(4.dp),
        ) {
            if (grouped.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                    BasicText(
                        "No linked sessions shown. Unlinked ones are below.",
                        style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
                    )
                }
            }
            for ((relation, rows) in grouped) {
                BasicText(
                    relationHeading(relation).uppercase(),
                    Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
                    style = ppmText(10f, FontWeight.SemiBold, lineHeight = 14f).copy(
                        color = tokens.text3.color,
                        letterSpacing = 0.5.sp,
                    ),
                )
                for (session in rows) {
                    val action = if (canUnlink(session.status)) {
                        SessionRowAction.Unlink { doUnlink(listOf(session)) }
                    } else {
                        SessionRowAction.Busy
                    }
                    LinkedSessionRow(
                        session = session,
                        action = action,
                        leaving = session.sessionId in leaving,
                        dim = false,
                        onOpen = { onOpenSession(session) },
                    )
                }
            }
        }

        if (!confirming && (finished.isNotEmpty() || runningChildren.isNotEmpty())) {
            FooterPopoverFooter {
                if (finished.isNotEmpty()) {
                    FooterButton(
                        onClick = { doUnlink(finished) },
                        testTag = "unlink-finished",
                    ) {
                        PpmIcon(ProductIcon.Link2Off, if (mobile) 16.dp else 14.dp, LocalFooterContentColor.current)
                        BasicText(
                            "Unlink idle & done (${finished.size})",
                            style = LocalFooterTextStyle.current.copy(fontFeatureSettings = "tnum"),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
                if (runningChildren.isNotEmpty()) {
                    FooterButton(
                        onClick = { confirming = true },
                        variant = FooterButtonVariant.Danger,
                        pushEnd = true,
                        testTag = "stop-all-children",
                    ) {
                        PpmIcon(ProductIcon.Square, if (mobile) 16.dp else 14.dp, LocalFooterContentColor.current)
                        BasicText(
                            "Stop all child sessions",
                            style = LocalFooterTextStyle.current,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }

        if (confirming && runningChildren.isNotEmpty()) {
            StopChildSessionsConfirm(
                titles = runningChildren.map { it.title },
                busy = stopping,
                onCancel = { confirming = false },
                onConfirm = {
                    if (stopping) return@StopChildSessionsConfirm
                    stopping = true
                    scope.launch {
                        try {
                            onStopChildSessions()
                            confirming = false
                        } catch (e: Exception) {
                            onError("Could not stop the child sessions", e.message ?: e.toString())
                        } finally {
                            stopping = false
                        }
                    }
                },
            )
        }

        undo?.let { currentUndo ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(tokens.surfaceHover.color)
                    .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                    .testTag("undo-bar"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TruncatedText(
                    AnnotatedString(currentUndo.text),
                    ppmText(if (mobile) 14f else 12f, lineHeight = if (mobile) 20f else 16f).copy(color = tokens.text.color),
                    Modifier.weight(1f),
                )
                FooterButtonBox(
                    onClick = { doRelink(currentUndo.ids) },
                    textColorOverride = tokens.accent.color,
                    testTag = "undo-unlink-btn",
                ) {
                    PpmIcon(ProductIcon.RotateCcw, if (mobile) 16.dp else 14.dp, tokens.accent.color)
                    BasicText(
                        "Undo",
                        style = LocalFooterTextStyle.current.copy(fontWeight = FontWeight.Medium, color = tokens.accent.color),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }

        if (hidden.isNotEmpty()) {
            val chevronTurn by animateFloatAsState(
                targetValue = if (unlinkedOpen) 180f else 0f,
                animationSpec = tween(if (reducedMotion) 0 else 150),
                label = "unlinkedChevron",
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .cssBorder(tokens.borderSoft.color, sides = CssSides.Top)
                    .padding(4.dp),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = if (mobile) 44.dp else 34.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .ppmClickable(
                            pressedColor = tokens.surfaceHover.color,
                            hoverColor = tokens.surfaceHover.color,
                            shape = RoundedCornerShape(6.dp),
                            onClick = { unlinkedOpen = !unlinkedOpen },
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .semantics { stateDescription = if (unlinkedOpen) "expanded" else "collapsed" }
                        .testTag("unlinked-toggle"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PpmIcon(ProductIcon.Link2Off, 16.dp, tokens.text3.color)
                    BasicText(
                        "Unlinked",
                        style = ppmText(if (mobile) 14f else 13f, lineHeight = if (mobile) 20f else 18f).copy(color = tokens.text.color),
                    )
                    Spacer(Modifier.weight(1f))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BasicText(
                            "${hidden.size}",
                            style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color, fontFeatureSettings = "tnum"),
                        )
                        PpmIcon(ProductIcon.ChevronDown, 12.dp, tokens.text3.color, Modifier.rotate(chevronTurn))
                    }
                }
                FoldGroup(open = unlinkedOpen) {
                    val hiddenScroll = rememberScrollState()
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .then(if (mobile) Modifier else Modifier.heightIn(max = 160.dp))
                            .verticalScroll(hiddenScroll),
                    ) {
                        for (session in hidden) {
                            LinkedSessionRow(
                                session = session,
                                action = SessionRowAction.Relink { doRelink(listOf(session.sessionId)) },
                                leaving = false,
                                dim = true,
                                onOpen = { onOpenSession(session) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private sealed interface SessionRowAction {
    val kind: String
    data class Unlink(val onClick: () -> Unit) : SessionRowAction {
        override val kind: String = "unlink"
    }
    data class Relink(val onClick: () -> Unit) : SessionRowAction {
        override val kind: String = "relink"
    }
    data object Busy : SessionRowAction {
        override val kind: String = "busy"
    }
}

@Composable
private fun LinkedSessionRow(
    session: LinkedSessionView,
    action: SessionRowAction,
    leaving: Boolean,
    dim: Boolean,
    onOpen: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val reducedMotion = rememberPrefersReducedMotion()
    val density = LocalDensity.current
    val slideOffsetPx = with(density) { 16.dp.toPx() }
    val leaveProgress by animateFloatAsState(
        targetValue = if (leaving && !reducedMotion) 1f else 0f,
        animationSpec = tween(if (reducedMotion) 0 else 180),
        label = "sessionLeave",
    )

    val actionTitle = when (action) {
        is SessionRowAction.Relink -> "Relink — show “${session.title}” in this chat again"
        is SessionRowAction.Unlink -> "Unlink — hide “${session.title}” from this chat"
        SessionRowAction.Busy -> "Still working — stop it before unlinking"
    }
    val actionLabel = when (action) {
        is SessionRowAction.Relink -> "Relink ${session.title}"
        else -> "Unlink ${session.title}"
    }

    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationX = slideOffsetPx * leaveProgress
                alpha = 1f - leaveProgress
            }
            .testTag("linked-session:${session.sessionId}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val rowShape = RoundedCornerShape(6.dp)
        Row(
            Modifier
                .weight(1f)
                .defaultMinSize(minHeight = if (mobile) 44.dp else 34.dp)
                .clip(rowShape)
                .webTitle(session.title)
                .ppmClickable(
                    pressedColor = tokens.surfaceHover.color,
                    hoverColor = tokens.surfaceHover.color,
                    shape = rowShape,
                    onClick = onOpen,
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (dim) {
                Box(Modifier.size(8.dp).background(tokens.text3.color, CircleShape))
            } else {
                LinkedStatusDot(session.status)
            }
            if (session.providerId.isNotEmpty()) {
                ProviderBadge(session.providerId, size = 14.dp)
            } else {
                Spacer(Modifier.size(14.dp))
            }
            TruncatedText(
                AnnotatedString(session.title),
                ppmText(if (mobile) 14f else 13f, lineHeight = if (mobile) 20f else 18f).copy(
                    color = if (dim) tokens.text2.color else tokens.text.color,
                ),
                Modifier.weight(1f),
            )
            BasicText(
                linkedStatusText(session.status),
                style = ppmText(11f, lineHeight = 15f).copy(
                    color = if (session.status == LinkedSessionStatus.NEEDS_APPROVAL && !dim) tokens.error.color else tokens.text3.color,
                ),
                maxLines = 1,
                softWrap = false,
            )
        }

        val btnSize = if (mobile) 44.dp else 32.dp
        val btnShape = RoundedCornerShape(6.dp)
        val isBusy = action is SessionRowAction.Busy
        val interactions = remember { MutableInteractionSource() }
        val pressed by interactions.collectIsPressedAsState()
        val hovered by interactions.collectIsHoveredAsState()
        val lit = !isBusy && (pressed || (!mobile && hovered))

        Box(
            Modifier
                .size(btnSize)
                .alpha(if (isBusy) 0.35f else 1f)
                .clip(btnShape)
                .background(if (lit) tokens.surfaceHover.color else androidx.compose.ui.graphics.Color.Transparent)
                .webTitle(actionTitle)
                .semantics {
                    contentDescription = actionLabel
                    stateDescription = actionTitle
                }
                .hoverable(interactions, !isBusy)
                .clickable(
                    interactionSource = interactions,
                    indication = null,
                    enabled = !isBusy,
                    role = Role.Button,
                    onClick = {
                        when (action) {
                            is SessionRowAction.Unlink -> action.onClick()
                            is SessionRowAction.Relink -> action.onClick()
                            SessionRowAction.Busy -> Unit
                        }
                    },
                )
                .testTag("unlink-action:${session.sessionId}:${action.kind}"),
            contentAlignment = Alignment.Center,
        ) {
            val icon = if (action is SessionRowAction.Relink) ProductIcon.Link else ProductIcon.Link2Off
            PpmIcon(icon, 14.dp, if (lit) tokens.text.color else tokens.text3.color)
        }
    }
}

/**
 * Inline confirmation before stopping running child sessions (`StopChildSessionsConfirm` in
 * `stop-child-sessions-confirm.tsx`).
 */
@Composable
fun StopChildSessionsConfirm(
    titles: List<String>,
    busy: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val n = titles.size
    val what = if (n == 1) "child session" else "$n child sessions"
    val separator = if (n == 2) " and " else ", "
    val named = titles.joinToString(separator) { "“$it”" }
    val copy = buildAnnotatedString {
        withStyle(SpanStyle(color = tokens.text.color, fontWeight = FontWeight.SemiBold)) {
            append("Stop $what?")
        }
        append(" Their current turns end now. $named ${if (n == 1) "stays" else "stay"} in History and can be continued later. This session keeps running.")
    }

    Column(
        modifier
            .fillMaxWidth()
            .cssBorder(tokens.borderSoft.color, sides = CssSides.Top)
            .background(tokens.error.opacity(0.05).color)
            .padding(
                start = if (mobile) 16.dp else 12.dp,
                end = if (mobile) 16.dp else 12.dp,
                top = 10.dp,
                bottom = 12.dp,
            )
            .testTag("stop-children-confirm"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicText(
            copy,
            style = ppmText(if (mobile) 14f else 12f, lineHeight = if (mobile) 20f else 18f).copy(color = tokens.text2.color),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(if (mobile) 8.dp else 6.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FooterButtonBox(
                onClick = onCancel,
                modifier = if (mobile) Modifier.weight(1f) else Modifier,
                testTag = "stop-children-cancel",
            ) {
                BasicText("Cancel", style = LocalFooterTextStyle.current, maxLines = 1, softWrap = false)
            }
            FooterButtonBox(
                onClick = onConfirm,
                variant = FooterButtonVariant.DangerFill,
                enabled = !busy,
                modifier = if (mobile) Modifier.weight(1f) else Modifier,
                testTag = "stop-children-confirm-btn",
            ) {
                PpmIcon(ProductIcon.Square, if (mobile) 16.dp else 14.dp, LocalFooterContentColor.current)
                val btnLabel = if (busy) {
                    "Stopping…"
                } else {
                    "Stop ${if (n == 1) "1 session" else "$n sessions"}"
                }
                BasicText(btnLabel, style = LocalFooterTextStyle.current, maxLines = 1, softWrap = false)
            }
        }
    }
}
